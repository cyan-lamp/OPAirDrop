package com.oshare.airdrop;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.oshare.airdrop.Native;
import com.oshare.airdrop.Report;
import com.oshare.airdrop.SettingsScreen;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * The listening socket, and the loop that hands each connection to the library.
 *
 * <p># Why the socket is here and not in Rust
 *
 * <p>On Android the certificate, the interface and the permission to bind all belong to the
 * app, not to the protocol. {@link Native#serve} therefore takes a pair of streams rather
 * than a port, and this class is the part that owns the port: it listens, accepts, hands the
 * streams over, and closes. That split is also why the library did not have to change when the
 * stream became a TLS one: the socket is an {@link SSLServerSocket} here, the bytes the
 * library reads are the same bytes, and nothing above it knows the difference.
 *
 * <p># The certificate is one object, used twice
 *
 * <p>The identity is loaded once per start, and the same instance both builds the socket and
 * supplies the fingerprint published as {@code h}. A sender reads {@code h} before it connects
 * and refuses a handshake that does not match it, so a device that published one certificate and
 * presented another would be rejected on the sender's screen, where nobody can read why. Passing
 * the object rather than the two facts makes that mismatch impossible to write.
 *
 * <p>Not asking the client for a certificate is deliberate and was measured on the legacy stack:
 * a phone asked for one during TLS 1.3 does not present it, it gives up — the failure moves into
 * the handshake, from "reset after the transfer" to "no handshake at all".
 *
 * <p># Why one thread and not a pool
 *
 * <p>A receiver answers one peer at a time — an AirDrop transfer is one connection, and a
 * second sender waits its turn rather than writing into the same directory concurrently.
 * Serialising them is not a limitation to work around; it is the rule that keeps two
 * arrivals from racing for the same file name. The thread exists only because a socket
 * cannot be read on the UI thread at all.
 *
 * <p># Why the settings are read per connection and not at start
 *
 * <p>The name a peer sees, the directory files land in and the byte budget are all settings,
 * and a person who changes one expects the next transfer to use it — not the next launch.
 * Re-reading a handful of {@code key=value} lines per connection costs nothing next to the
 * transfer it configures, and it removes the class of bug where the page and the receiver
 * disagree about what is configured.
 *
 * <p># What this class does not do
 *
 * <p>It does not decide anything about a transfer. Which requests are answered, what the
 * answers say, whether a member may replace a file — all of that is in the library, where it
 * is tested on the host. The only judgement here is which directory to use and what to put
 * on the page, both of which are the app's business.
 */
final class Listener {
    /**
     * The tag every line from this class goes out under.
     *
     * <p>The same string the legacy app logged under, so the two are greppable together:
     * "what did the old build do at this point" is a question that gets asked whenever a
     * connection behaves differently from the way it used to.
     *
     * <p>There is deliberately very little written here, and none of it is on the page. A
     * receiver's failures are mostly a peer that changed its mind, and a person cannot act on
     * one; what a log is for is the case nobody can reproduce — a handshake that died, a
     * request that never arrived — and that is exactly the case the page must stay quiet
     * about.
     */
    private static final String TAG = "OPAirDrop";

    /**
     * The port AirDrop answers on.
     *
     * <p>Fixed by the protocol's discovery record: a peer that finds this device is told the
     * port in that record, and 8770 is what every implementation advertises.
     */
    static final int PORT = 8770;

    /**
     * How long a peer has to finish its TLS handshake.
     *
     * <p>Twelve seconds, from the legacy server. A handshake on this link measured about a
     * second; the rest is allowance for the phone that is still arithmeticking when the radio
     * drops out. Without a bound, a peer that walks away mid-handshake holds the serving
     * thread until TCP itself gives up, which is minutes.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 12_000;

    /**
     * How long a finished handshake has to say something.
     *
     * <p>This bound matters more here than it did in the legacy server, and the reason is the
     * difference between them: that one gave every connection a thread of its own, so a silent
     * socket cost a thread; this one has a single thread, so a silent socket costs every other
     * sender until it expires. A phone that completes a handshake and then changes its mind —
     * which it does repeatedly while a share sheet is open — is the common case, not an attack.
     * Eight seconds is the legacy figure, and it is an order of magnitude longer than a real
     * sender needs to frame its first request.
     */
    private static final int REQUEST_TIMEOUT_MS = 8_000;

    /**
     * How long a transfer may go without delivering a byte before it is dropped.
     *
     * <p>Sixty seconds, as in the legacy server, and it takes over from
     * {@link #REQUEST_TIMEOUT_MS} at the moment the library asks what to do about an upload.
     * That is the one point where a serving thread can tell the two apart without watching the
     * bytes: the request has been parsed and the body has not. Without the raise, a body that
     * paused for eight seconds would be cut off in the middle of a file.
     */
    private static final int TRANSFER_TIMEOUT_MS = 60_000;

    /** Told, on the listener's thread, that a line the page shows has changed. */
    interface Watcher {
        void listenerChanged();
    }

    /**
     * The state line and whether it is bad news, as one value.
     *
     * <p>Two volatile fields would be two writes, and a page drawn between them would show
     * new words in the old colour. One immutable object is one write, so a reader sees a
     * state that existed.
     */
    private static final class Line {
        final String text;
        final boolean problem;

        Line(String text, boolean problem) {
            this.text = text;
            this.problem = problem;
        }
    }

    private final Context context;

    /**
     * The one listener in this process.
     *
     * <p>One, because the port is one: a second instance would fail to bind, and a page that
     * stopped the receiver on the way out would interrupt a transfer every time somebody
     * pressed back. So the listener belongs to the process and the page attaches to it — the
     * page is a view of the receiver, not its owner.
     *
     * <p>Guarded by the class, because two activities can be created in either order.
     */
    private static Listener shared;

    /**
     * Told, on the listener's thread, that a line the page shows has changed.
     *
     * <p>Null while no page is attached: the listener keeps running with nothing to tell,
     * which is what lets it outlive the activity that started it.
     */
    private volatile Watcher watcher;

    /** Guards the three fields below, which the UI thread reads through the getters. */
    private final Object lock = new Object();

    private ServerSocket server;
    private Thread worker;
    /** Volatile because the loop reads it outside the lock, in the finally that tidies up. */
    private volatile boolean stopping;

    /**
     * The announcer: the half of discovery that makes this device appear in a share sheet.
     *
     * <p>Owned here, and started only after {@link #PORT} is actually bound, because the two are
     * one promise: a receiver that accepts nothing but announces anyway is worse than an
     * invisible one — it is a device a person taps and then waits on. Every caller that starts
     * the receiver therefore gets the announcement for free, and there is exactly one place
     * where the pair can drift apart.
     *
     * <p>Guarded by {@link #lock}.
     */
    private Responder announcer;

    /**
     * Why there is no announcer, when there is none for a reason worth saying.
     *
     * <p>Separate from {@link Responder#aside()} because it is written before the announcer
     * exists — an unreadable service-id file means there is no object to ask.
     */
    private volatile String asideProblem = "";

    /** The state line: what the listener is doing, worded for a person. */
    private volatile Line state = new Line("未开始", false);
    /** What the last connection that stored something left behind; empty when none has. */
    private volatile String last = "";

    private Listener(Context context) {
        this.context = context;
    }

    /**
     * The process's listener, with the page that will draw it attached.
     *
     * <p>A method rather than an exposed field so that the "one per process" rule is written
     * once; calling it twice returns the same object and the state it has already reached.
     */
    static synchronized Listener open(Context context, Watcher watcher) {
        Listener listener = of(context);
        listener.watcher = watcher;
        return listener;
    }

    /**
     * The process's listener, with nobody attached to it.
     *
     * <p>For callers that start and stop the receiver rather than draw it — the switch in
     * {@link Oshare} is one. They must not go through {@link #open}, because that method
     * assigns the watcher: a background caller passing {@code null} would silently detach the
     * page that is currently on screen, and the page would then stop being redrawn while
     * everything still worked.
     */
    static synchronized Listener of(Context context) {
        if (shared == null) {
            // The application context: the listener outlives every activity, and one holding
            // an activity would keep a whole view tree alive while nothing is drawn on it.
            shared = new Listener(context.getApplicationContext());
        }
        return shared;
    }

    /** Detaches the page. The receiver keeps running; there is simply nobody to tell. */
    void unwatch() {
        watcher = null;
    }

    /** Whether the loop is currently bound or binding. */
    boolean running() {
        synchronized (lock) {
            return worker != null;
        }
    }

    /** The state line, with the port in it once it is known. */
    String state() {
        return state.text;
    }

    /** Whether {@link #state()} is bad news. */
    boolean problem() {
        return state.problem;
    }

    /** What last landed, or empty when nothing has. */
    String last() {
        return last;
    }

    /**
     * What the announcer has to complain about, or empty.
     *
     * <p>A separate line from {@link #state()} on purpose: the receiver can be listening
     * perfectly while nothing can find it, and that combination — "正在监听" with an empty share
     * sheet — is the one that sends a person looking in the wrong place. Empty when all is well
     * because the state line is already saying something true.
     */
    String aside() {
        Responder announcing;
        synchronized (lock) {
            announcing = announcer;
        }
        if (announcing != null) {
            return announcing.aside();
        }
        return asideProblem;
    }

    /** Where files go when {@code receive.directory} is blank. */
    String directory() {
        return defaultDirectory().getAbsolutePath();
    }

    /**
     * Binds the port and starts accepting, or puts the reason on the page.
     *
     * <p>Returns immediately: binding is I/O, and the caller is the UI thread. A bind that
     * fails — the port is taken by another receiver, or the app lost its network permission —
     * is reported through {@link #state()} rather than thrown, because there is nobody on
     * this thread to catch it.
     */
    void start() {
        synchronized (lock) {
            if (worker != null) {
                return;
            }
            stopping = false;
            state = new Line("正在绑定 " + PORT + "…", false);
            worker = new Thread(this::loop, "opairdrop-listener");
            worker.setDaemon(true);
            worker.start();
        }
        // Outside the lock: the watcher redraws the page, and that page asks this object
        // questions the lock would answer only after it has been released.
        tell();
    }

    /**
     * Stops accepting and releases the port.
     *
     * <p>Closing the socket is what unblocks {@code accept}; there is no other way to
     * interrupt a thread parked in it. A transfer already in progress is allowed to finish —
     * killing it mid-archive would leave half a file that looks whole — so the loop ends
     * after the current connection does, and {@link #running()} stays true until then.
     */
    void stop() {
        ServerSocket open;
        boolean shown;
        synchronized (lock) {
            stopping = true;
            open = server;
            server = null;
            shown = worker != null;
            if (shown) {
                state = new Line("正在停止…", false);
            }
        }
        closeQuietly(open);
        if (shown) {
            tell();
        }
    }

    /** The loop: load the certificate, bind, accept, serve, until told to stop or the socket breaks. */
    private void loop() {
        Identity identity;
        try {
            // Once per start, and before the bind, because the same object has to do both
            // jobs: it builds the socket and it supplies the fingerprint published as `h`.
            // The name inside it is the name a peer reads, so the certificate and the
            // announcement say the same thing — see the class comment.
            identity = new IdentityStore(context.getFilesDir()).loadOrCreate(peerName());
        } catch (IOException | GeneralSecurityException failed) {
            // No certificate means no listener at all, and that is stricter than the rule for
            // the announcer below. An unfindable receiver still accepts from anything that
            // knows the address; a receiver with no certificate cannot complete a single
            // handshake, and the failure would appear on the sender's screen where there is
            // nothing to read. This is the only place a person can be told.
            end();
            setState("无法建立本机证书：" + describe(failed), true);
            return;
        }

        SSLServerSocket bound = null;
        try {
            bound = (SSLServerSocket) identity.serverContext()
                    .getServerSocketFactory().createServerSocket();
            // Without this a stopped listener cannot be restarted for a minute or two: the
            // port stays in TIME_WAIT and the bind fails with "address already in use".
            // Restarting is something a person does immediately after stopping.
            //
            // The legacy server also set a 1 MB receive buffer here, before the bind, because
            // the window scale factor is fixed by the SYN-ACK and a value set after accept
            // would be capped by a scale chosen for the old size. It is not carried over: that
            // server measured it on two 5.1 MB transfers from the same phone and got 118.0 KB/s
            // against 119.9 KB/s — one and a half per cent, which is noise. The window was
            // never what paced this link.
            bound.setReuseAddress(true);
            bound.bind(new InetSocketAddress(PORT));
            // Never asked for, and this was measured on the legacy stack rather than assumed:
            // with setWantClientAuth(true) the log over seventy-five lines carried no
            // "handshake" line at all, only "reset after 2034 ms"; with it off every connection
            // completed a TLS 1.3 handshake and was reset afterwards. A phone asked for a
            // client certificate during TLS 1.3 does not present one, it gives up — the
            // failure moves into the handshake, from "reset after the transfer" to "no
            // handshake at all", where nothing on this side can see why.
            bound.setNeedClientAuth(false);
        } catch (IOException | GeneralSecurityException refused) {
            closeQuietly(bound);
            end();
            setState("无法监听 " + PORT + "：" + describe(refused), true);
            return;
        }

        synchronized (lock) {
            if (stopping) {
                // Stopped while binding: do not go on to accept on a socket nobody wants.
                closeQuietly(bound);
                end();
                setState("已停止", false);
                return;
            }
            server = bound;
        }
        setState("正在监听 " + PORT + "，等待附近设备", false);
        // After the bind, never before: see the field comment on why the two are one promise.
        beginAnnouncing(identity);

        try {
            while (true) {
                Socket peer;
                try {
                    peer = bound.accept();
                } catch (IOException broken) {
                    synchronized (lock) {
                        if (stopping) {
                            break;
                        }
                    }
                    // A bound socket that can no longer accept is not going to start: the
                    // descriptor is gone or the interface it bound to is. Ending here with
                    // the reason on the page beats spinning on a socket that always fails.
                    setState("接受连接失败：" + describe(broken), true);
                    break;
                }
                // try-with-resources and not a bare close: a serve that throws still has to
                // release the descriptor, and a leaked one per connection ends in "too many
                // open files" after a few hundred transfers.
                try (Socket connection = peer) {
                    // The cast is what the socket factory above guarantees; accepting a plain
                    // Socket here is not a case that can arise, and a defensive instanceof
                    // would only hide it if it did.
                    serve((SSLSocket) connection);
                } catch (IOException gone) {
                    setState("连接中断：" + describe(gone), true);
                }
            }
        } finally {
            // First, so the last thing peers hear from this device is that it is gone. The
            // withdrawal travels through the announcer's own socket and has to leave before
            // anything about this start is torn down.
            endAnnouncing();
            closeQuietly(bound);
            end();
            if (stopping) {
                setState("已停止", false);
            } else {
                // The loop ended because the socket broke. Its reason is already on the
                // page; this redraw is for the button, which has to go back to 开始接收 now
                // that there is no thread behind it.
                tell();
            }
        }
    }

    /**
     * Starts publishing this device as an AirDrop receiver.
     *
     * <p>Called from the loop thread, after the bind, and that placement is the whole design:
     * naming the device means reading the service-id file and the settings table, which is file
     * I/O, and the thread that just got here is the one thread in this process that is allowed
     * to wait.
     *
     * <p>Failing to announce does not stop the receiver. A port that is listening can still be
     * sent to by anything that already knows the address — a Mac that saw this device in an
     * earlier session, a test with {@code nc} — so the honest outcome is "listening, but
     * unfindable", said out loud through {@link #aside()}, rather than a switch that refuses to
     * go on because a filename could not be read.
     *
     * <p>The name a peer sees is read here and not per announcement: it travels in the SRV
     * target, which is a record every announcement repeats, and re-reading a setting to decide
     * what to keep publishing would mean the device changed its name mid-flight without ever
     * withdrawing the old one. A person who renames the device gets the new name the next time
     * the receiver starts — which is also when the old name is withdrawn.
     *
     * <p>Both names on the wire come from the certificate: the SRV target is
     * {@link Identity#computerName()} transformed for DNS, and the TXT {@code h} is
     * {@link Identity#certHash()}. Reading them from the object rather than from the settings
     * again is what keeps a capture from showing a device that calls itself one thing, certifies
     * itself as another and pins a third.
     *
     * <p>The one place the pair can still disagree is a rename while the receiver is running:
     * the `/Discover` and `/Ask` answers are rebuilt from the settings on every connection, so
     * they say the new name at once, while the certificate and this record cannot change until
     * the next start. That is a minute of inconsistency on a device whose owner is holding the
     * phone, and the alternative — re-signing a certificate between two connections — would
     * break the pin every sender has already read.
     */
    private void beginAnnouncing(Identity identity) {
        ServiceId service = ServiceId.loadOrCreate(context.getFilesDir());
        Responder announcing = new Responder(
                context,
                service.current,
                service.stale,
                Responder.hostNameFor(identity.computerName()),
                identity.certHash(),
                PORT,
                Responder.DEFAULT_INTERFACE,
                this::tell);
        asideProblem = "";
        synchronized (lock) {
            announcer = announcing;
        }
        announcing.start();
    }

    /** Takes the instance back and closes the announcer's socket. Safe to call when there is none. */
    private void endAnnouncing() {
        Responder announcing;
        synchronized (lock) {
            announcing = announcer;
            announcer = null;
        }
        if (announcing != null) {
            announcing.stop();
        }
    }

    /**
     * The name a peer shows: {@code device.name}, then {@code device.model}, then the phone's
     * own model.
     *
     * <p>Both blank rows are the table's defaults and each means "use the next one down", which
     * is the only answer that is never wrong for somebody who has not thought about it. An
     * unreadable table falls back to the same place for the same reason.
     *
     * <p>The chain has to match {@code settings::name} in Rust, and that is not a duplication
     * to be tidied away: this copy is what the certificate's CN and the SRV target are made
     * from, both of which are fixed for the lifetime of the socket, while the answer a peer
     * reads is built per connection. Two implementations of the same rule is the cost of
     * freezing one of them.
     *
     * <p>Package-private because the feature page prints it. The two rows there answer "what
     * will a peer read", and a second copy of the chain in a view class would not be a frozen
     * twin but a third rule to keep in step.
     */
    String peerName() {
        SettingsScreen screen = settingsNow();
        String named = value(screen, "device.name");
        return named.isEmpty() ? peerModel(screen) : named;
    }

    /**
     * The model a peer reads: {@code device.model}, then the phone's own.
     *
     * <p>Only ever appears in the {@code /Ask} answer — {@code /Discover} carries the name and
     * the capability blob, nothing about the hardware — so this is the field a person sees on
     * the other device after tapping their own.
     */
    String peerModel() {
        return peerModel(settingsNow());
    }

    private String peerModel(SettingsScreen screen) {
        String model = value(screen, "device.model");
        return model.isEmpty() ? systemModel() : model;
    }

    /** What {@code Build} reports, which is the last step of both chains. */
    static String systemModel() {
        return Build.MODEL == null ? "Android" : Build.MODEL;
    }

    /** The table as it stands now, or null when it cannot be read — every caller treats that as blank rows. */
    private SettingsScreen settingsNow() {
        try {
            return SettingsScreen.open(SettingsFile.of(context).toPath());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** One text row, trimmed; empty when the row is missing, blank, or there is no table at all. */
    private static String value(SettingsScreen screen, String key) {
        SettingsScreen.Setting setting = screen == null ? null : screen.setting(key);
        return setting == null ? "" : setting.value().trim();
    }

    /** Completes the handshake, hands the connection to the library, and puts what it stored on the page. */
    private void serve(SSLSocket peer) {
        long openedAt = System.nanoTime();
        try {
            // Server mode is the default for a socket from an SSLServerSocket; stated anyway,
            // because the mode is what decides whether this side offers a certificate.
            peer.setUseClientMode(false);
            // Nagle held the opening flight of every legacy handshake to four round trips: the
            // flight is four records of 788, 445, 273 and 100 bytes, each under a segment, each
            // waiting on the one before it — measured at 6757 ms on a link whose retransmit
            // timer is 1520 ms. Turning it off lets the flight leave back to back. A transfer
            // never felt this, because it fills a segment on every write.
            peer.setTcpNoDelay(true);
            peer.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            peer.startHandshake();
            // What was negotiated, once there is a session to ask. It is worth a line because
            // a peer that settled on something unexpected — TLS 1.2 where 1.3 was expected, a
            // suite whose key size this radio chokes on — looks exactly like a healthy
            // connection until its first request dies, and by then the handshake is long gone
            // from anywhere it could be inspected.
            javax.net.ssl.SSLSession session = peer.getSession();
            Log.i(TAG, "handshake from " + where(peer) + ": " + session.getProtocol() + " "
                    + session.getCipherSuite() + " in " + millis(openedAt) + " ms");
            // Handshake done, so the request bound takes over: see REQUEST_TIMEOUT_MS for why
            // this one is the bound that matters when there is only one serving thread.
            peer.setSoTimeout(REQUEST_TIMEOUT_MS);
        } catch (IOException | RuntimeException failed) {
            // Deliberately not put on the page. A peer that connects and then abandons — the
            // share sheet closed, the file went to another device, this is the fourth
            // reconnect of the minute — is routine, and a red line every two seconds would be
            // noise pretending to be evidence. A bind or an accept failure is a different
            // thing and is reported: those are this device's own problem and they recur.
            //
            // It does go to the log, and the difference matters: the page is for the person
            // holding the phone, the log is for the case nobody can reproduce. A failed
            // handshake is invisible everywhere else — the sender sees its own screen, and
            // this side had nothing to say until this line existed.
            Log.i(TAG, "handshake from " + where(peer) + " failed after " + millis(openedAt)
                    + " ms: " + describe(failed));
            return;
        }

        Path file = SettingsFile.of(context).toPath();
        SettingsScreen screen;
        try {
            screen = SettingsScreen.open(file);
        } catch (IOException | RuntimeException unreadable) {
            // A settings file that does not parse is the one failure a person can fix by
            // hand, and refusing the connection is the alternative to receiving with values
            // nobody chose.
            setState("设置读不出来，连接被拒：" + unreadable.getMessage(), true);
            return;
        }

        String directory = directoryOf(screen);
        // 谁在看进度。每条连接一个，因为接收端一次只服务一个对端（见类注释「Why one
        // thread and not a pool」），所以进度条上最多只有一条连接的数字。
        Meter meter = new Meter(context);
        try {
            // 有人要发东西过来时问一次。问的是人，所以这条线程会停在这里等——它本来也只
            // 服务这一个连接，等的时候没有别的事该做。
            byte[] frame = Native.serveWatched(
                    directory,
                    screen.stored(),
                    Build.MODEL,
                    peer.getInputStream(),
                    peer.getOutputStream(),
                    summary -> {
                        // The one moment a serving thread can tell a request from a body: the
                        // library has parsed the request and is about to read a file. From here
                        // on, a gap of eight seconds means a slow transfer, not an idle peer.
                        try {
                            peer.setSoTimeout(TRANSFER_TIMEOUT_MS);
                        } catch (IOException gone) {
                            // The connection died while the offer was being drawn; the read
                            // that follows reports it, and this bound no longer matters.
                        }
                        // 谁想发什么。这三个字段（名字、件数、字节数）只在此时存在：连接一结束
                        // 就没有任何地方还记得它，而对端到底在分享面板里叫什么，是「别人能不能
                        // 找到我们」这件事唯一能留下证据的地方。
                        Log.i(TAG, "offer from " + summary.replace('\t', ' '));
                        return Confirmation.ask(context, summary);
                    },
                    meter);
            // A discovery probe and an empty share both come back empty; neither is worth a
            // line on the page, and leaving the previous transfer up is the truthful state
            // of "nothing new arrived".
            Report report = Report.of(frame);
            if (!report.empty()) {
                last = summarize(report, directory);
                // 通知上那句话就是页面上那句话，一次传输只有一种说法。收尾在这里而不是在
                // 回调里：回调不知道哪一块是最后一块——total 是对端声明的，它可以少发，
                // 也可以什么都不声明，所以「最后一块」只有 native 调用返回才成立。
                meter.finished(last);
                setState("正在监听 " + PORT + "，等待附近设备", false);
            }
        } catch (IOException | RuntimeException refused) {
            // 对端走掉和真正出错，在异常类型上是同一件事，在意思上是两件。legacy 的同一处
            // catch 也只把 "Connection reset" 写进日志、不上报，理由写得很直白：分享面板关了、
            // 文件发给了别的设备、这是这一分钟里第四次重连——每两秒闪一次的红线不是证据。
            // 真正需要人看一眼的失败（设置读不出来、目录写不进去）仍然上线。
            if (routine(refused)) {
                Log.i(TAG, "connection from " + where(peer) + " ended: " + describe(refused));
                return;
            }
            setState("接收失败：" + describe(refused), true);
        } finally {
            // 走到一半的连接：进度条还挂着「正在接收」，而它已经不会再动一分一毫了。收回去。
            // 一次都没落地过字节的连接不会碰任何通知——这次握手失败或者这次探测，不该把
            // 上一个传输留下的结果一起抹掉。
            meter.abandon();
        }
    }

    /**
     * Whether a connection failure is the routine kind: the peer went away.
     *
     * <p>Two of the three ways out of a transfer are not failures by this device, and they
     * arrive as the same unchecked {@link IOException} a broken disk would: a reset after the
     * sender closed its share sheet, and a pipe closed under a reply nobody is left to read.
     * Telling them apart here is what keeps the state line meaning "something is wrong with
     * this receiver" rather than "somebody's phone changed its mind".
     *
     * <p>Checked through the cause chain because the reset surfaces wrapped: an
     * {@link SSLException} is a normal part of the path, and its cause is the socket one.
     */
    private static boolean routine(Throwable failed) {
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketException) {
                // Reset by peer, broken pipe, socket closed: all "the other end stopped
                // listening", which is what a closed share sheet looks like from here.
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * Where this connection writes, from the settings as they are now.
     *
     * <p>A blank {@code receive.directory} means "the app's own download folder" — the table
     * says so, and it is the only default that needs no storage permission and is still
     * reachable over USB. A value that names somewhere the app cannot write is not second-
     * guessed here: the inbox reports the failure, and its message names the path.
     */
    private String directoryOf(SettingsScreen screen) {
        SettingsScreen.Setting setting = screen.setting("receive.directory");
        String value = setting == null ? "" : setting.value().trim();
        return value.isEmpty() ? directory() : value;
    }

    /** The app's own download folder, or a private one when external storage is absent. */
    private File defaultDirectory() {
        File external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return external != null ? external : new File(context.getFilesDir(), "Download");
    }

    /** One line for the page: what landed and where. */
    private String summarize(Report report, String directory) {
        int count = report.names().size();
        String size = bytes(report.bytes());
        String where = directory.substring(directory.lastIndexOf('/') + 1);
        if (count == 1) {
            return "收到「" + report.names().get(0) + "」，" + size + "，存到 " + where + "/";
        }
        return "收到 " + count + " 个文件，共 " + size + "，存到 " + where + "/";
    }

    /**
     * A byte count in the largest unit that keeps it readable.
     *
     * <p>Package-private because the transfer notification prints the same numbers: one rule
     * for how a byte count reads, not two — the same reason the name chain above is not
     * duplicated in the view that shows it.
     */
    static String bytes(long total) {
        if (total < 1024) {
            return total + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = total;
        for (String unit : units) {
            value /= 1024;
            if (value < 1024 || unit.equals(units[units.length - 1])) {
                return String.format(java.util.Locale.US, "%.1f %s", value, unit);
            }
        }
        return total + " B";
    }

    /** A failure's own words, or its class when it has none. */
    private static String describe(Throwable failed) {
        String message = failed.getMessage();
        return message == null || message.isEmpty() ? failed.toString() : message;
    }

    /** The peer's address, for a log line; "?" when the socket never had one. */
    private static String where(Socket peer) {
        java.net.InetAddress address = peer.getInetAddress();
        return address == null ? "?" : address.getHostAddress();
    }

    /** How long something took, in whole milliseconds, for a log line. */
    private static long millis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /**
     * 把库里报的字节数变成至多每秒五条的通知，并且知道自己屏幕上还有没有东西要收回去。
     *
     * <p># 为什么必须节流
     *
     * <p>{@link Native.Landing#landed} 是按落地块调用的，一次传输就是成千上万次，而每一条
     * 通知都是一次 binder 事务，全都发生在正在读字节的那条线程上——传输在等它。进度条每秒
     * 重画超过五次，人眼也读不出区别，所以这里选的是「够快」而不是「最快」。
     *
     * <p># 为什么收尾不在这里的回调里
     *
     * <p>回调不知道哪一块是最后一块。{@code total} 是对端声明的，它可以少发、也可以什么都
     * 不声明，所以「收完了」这件事只有 native 调用返回才成立，由 {@link #finished} 接手。
     */
    private static final class Meter implements Native.Landing {

        /** 两次投递之间的最小间隔。 */
        private static final long INTERVAL_MS = 200;

        private final Context context;

        /** 上次投递的时刻（{@link System#nanoTime}）；0 表示一次都还没投递过。 */
        private long postedAt;

        /** 屏幕上有没有一条属于这次连接的进度条。 */
        private boolean showing;

        /** 这次连接有没有结果。有结果之后就不再收尾。 */
        private boolean settled;

        Meter(Context context) {
            this.context = context;
        }

        @Override
        public void landed(long done, long total) {
            long now = System.nanoTime();
            // 第一条立刻投出去：否则前两百毫秒里一个小文件已经收完了，人什么都没看见。
            if (postedAt != 0 && now - postedAt < INTERVAL_MS * 1_000_000L) {
                return;
            }
            postedAt = now;
            showing = true;
            ReceiveService.progress(context, done, total);
        }

        /** 有结果了：把进度条换成那句话。 */
        void finished(String text) {
            settled = true;
            ReceiveService.finished(context, text);
        }

        /**
         * 把属于这次连接的进度条收回去。
         *
         * <p>只收自己画过的那条。一次都没落地过字节的连接——握手失败的、设置读不出来的、
         * 对端只是来探测一下的——不该把上一个传输留下的结果抹掉。
         */
        void abandon() {
            if (showing && !settled) {
                ReceiveService.abandon(context);
            }
        }
    }

    private void setState(String text, boolean failed) {
        state = new Line(text, failed);
        tell();
    }

    /** Redraws the page if one is attached, and does nothing at all if none is. */
    private void tell() {
        Watcher attached = watcher;
        if (attached != null) {
            attached.listenerChanged();
        }
    }

    /** Clears the fields {@link #running()} reads, so the page draws 开始接收 again. */
    private void end() {
        synchronized (lock) {
            worker = null;
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is how the loop is told to stop; a socket that is already closed, or
            // one whose descriptor went away with the interface, has nothing left to say.
        }
    }
}
