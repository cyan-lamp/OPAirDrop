package com.oshare.airdrop;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     * How long a peer has to finish its TLS handshake, when the table cannot be read.
     *
     * <p>Twelve seconds, from the legacy server. A handshake on this link measured about a
     * second; the rest is allowance for the phone that is still arithmeticking when the radio
     * drops out. Without a bound, a peer that walks away mid-handshake holds the serving
     * thread until TCP itself gives up, which is minutes.
     *
     * <p>这个数字现在是回落：真正生效的是设置里的 {@code server.handshake_timeout_ms}（默认
     * 也是 12 秒）。留着常量是因为设置文件可能读不出来，而一个读不出来的文件不该把这条界变成
     * 「不等」。
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
     * How long a transfer may go without delivering a byte before it is dropped, when the table
     * cannot be read.
     *
     * <p>Sixty seconds, as in the legacy server, and it takes over from
     * {@link #REQUEST_TIMEOUT_MS} at the moment the library asks what to do about an upload.
     * That is the one point where a serving thread can tell the two apart without watching the
     * bytes: the request has been parsed and the body has not. Without the raise, a body that
     * paused for eight seconds would be cut off in the middle of a file.
     *
     * <p>生效的数字是设置里的 {@code server.read_timeout_ms}，默认同样是 60 秒；常量只在设置
     * 读不出来时兜底。
     */
    private static final int TRANSFER_TIMEOUT_MS = 60_000;

    /**
     * How long a closing connection may take to go quiet.
     *
     * <p>Used as a socket timeout while draining after the transfer has already been answered,
     * so it is not a bound on anything useful happening — it is the pause that ends the drain.
     * A quarter second is several round trips on this link: the only thing expected after the
     * reply is the sender's own closing record, one segment, already in flight.
     */
    private static final int CLOSING_QUIET_MS = 250;

    /**
     * The longest a connection may be drained before it is closed anyway.
     *
     * <p>{@link #CLOSING_QUIET_MS} ends the drain for a peer that has stopped talking; this
     * ends it for a peer that has not. Both are needed because the serving thread is single
     * (see the class comment on why): a sender that keeps writing after its transfer was
     * answered must not hold every other sender behind it. One second is the same order as
     * the pause, and still short enough that the next transfer is not waiting on it.
     */
    private static final long CLOSING_DRAIN_NANOS = 1_000_000_000L;

    /**
     * The receive buffer: on this link it buys progress reporting and nothing else.
     *
     * <p>Left alone, the kernel sizes this from {@code tcp_rmem} and, on a link that never
     * drops a packet, grows it to the top of that range. A 49.3 MB transfer measured on
     * 2026-11-15 advertised 4.19 MB and never shrank it once. That window is what the sender's
     * progress bar runs on, because the phone counts bytes it has handed to its socket, not
     * bytes that arrived here: it read 100% with 7.4 MB still in flight, seventy-five seconds
     * of 正在发送 after the last file had already landed. Half a megabyte puts that tail under
     * five.
     *
     * <p>It costs no throughput, and the arithmetic says so plainly: this link carries
     * 98.8 KB/s at a 24.5 ms round trip, so its bandwidth-delay product is 2.5 KB. A buffer two
     * hundred times the pipe is still two hundred times the pipe. The legacy stack measured the
     * same thing from the other side — 1 MB against the default, 118.0 and 119.9 KB/s, one and
     * a half per cent — which is why it was dropped when this class was written. It is back for
     * the reporting, not for the rate.
     *
     * <p>Set on the server socket before the bind: the window scale is fixed in the SYN-ACK,
     * and a value set after accept would be capped by a scale chosen for the old size.
     */
    private static final int RECEIVE_BUFFER_BYTES = 512 * 1024;

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

    /**
     * 蓝牙广播到底发出去了没有。
     *
     * <p>这一位存在的理由只有一个：{@link #announceOverBluetooth} 是一次性的敲门，敲不开就
     * 结束了，不像 socket 那样会重连。而「先拨开关、再在权限框上点允许」是最常见的顺序——
     * 那次敲门必然发生在权限到手之前。没有这一位，就没有任何依据判断「现在再敲一次」是否
     * 有意义，只能盲目重试或者永远不试。
     */
    private volatile boolean announced;

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
     * <p>For callers that start and stop the receiver rather than draw it — the tile switch
     * is one. They must not go through {@link #open}, because that method
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

    /**
     * 现在有人在看这一页吗。
     *
     * <p>接收端需要知道这件事，只有在一个地方：邀请找不到任何出口的时候。通知发不出去、
     * 胶囊也提升不了，而屏幕上还有个页面在画——那这个人就还有机会看见。反过来，没有页面
     * 也没有通知，就等于没有人被告知，那一次的三十秒等待只是在让对面白白等到自己的超时。
     */
    boolean watched() {
        return watcher != null;
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

    /**
     * 留空时文件去哪儿，一句话，给设置页面那一行用。
     *
     * <p>不再是「应用自己的下载目录」——那个目录在 {@code Android/data/} 底下，
     * Android 11 起连 MTP 都进不去，文件名管理器里也看不见（见 {@link Publish}）。
     */
    String directory() {
        return Publish.describe();
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
            // would be capped by a scale chosen for the old size. Its measurement — 118.0
            // against 119.9 KB/s — is why the value was not carried over, and that half of the
            // finding still holds: the window was never what paced this link. What the window
            // does decide is how far the sender's progress bar can run ahead of what this side
            // has actually taken in, and for that it is now set smaller. See
            // RECEIVE_BUFFER_BYTES.
            bound.setReuseAddress(true);
            bound.setReceiveBufferSize(RECEIVE_BUFFER_BYTES);
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
                // Not try-with-resources any more, and the comment that used to be here said
                // why it was: a serve that throws still has to release the descriptor. That
                // still has to be true, so the close moved into `hangUp` and stayed in a
                // `finally` — but it is no longer a bare `close()`, because a bare close is
                // what made the sender's screen hang. See {@link #hangUp}.
                //
                // The catch that used to sit here is gone with the resource: it caught the
                // `IOException` the implicit close could throw, and `serve` reports its own
                // failures without throwing any. Nothing in this block can throw now.
                try {
                    // The cast is what the socket factory above guarantees; accepting a plain
                    // Socket here is not a case that can arise, and a defensive instanceof
                    // would only hide it if it did.
                    serve((SSLSocket) peer);
                } finally {
                    hangUp((SSLSocket) peer);
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
        // 第二层公告：蓝牙。iPhone 的隔空投送先听这二十字节，听见了才把 AWDL 上的服务叫起来，
        // 之后才轮得到上面那条 mDNS 记录被读到。少这一层时，「查询发得完全正确、每两秒一次、
        // 却谁都不答」正是它会表现成的样子——对端在每一层都在，只是没人听 8770。
        //
        // 与 mDNS 同一个生命周期：两者都是「这台机器现在可以被发现」这句话的实现，分开启停会
        // 让中间那段变成「mDNS 说在、蓝牙说不在」，而对端只信后者。
        announceOverBluetooth();
    }

    /**
     * 敲一次门。失败不改变接收端的结论——已经醒着的对端照样会答。
     *
     * <p>二十字节和四个广播参数都在 Rust 侧（{@code ble::manufacturer_data}），这里只把状态行
     * 记下来：它是「为什么对面看不见我」唯一能留下的一句话，而这个问题在别的层看起来都正常。
     */
    private void announceOverBluetooth() {
        try {
            String line = new String(Native.bleAdvertise(context), java.nio.charset.StandardCharsets.UTF_8);
            Log.i(TAG, "ble: " + line);
            // Rust 侧把四态压成这一行（idle / starting / advertising / failed: 原因），所以
            // 判断只有一个依据：开头不是 `failed: ` 就算醒了。`already started` 也算——这条
            // 路径只在广播没发出去时才被叫到，真撞上它说明别处已经把它拉起来了，结论一样。
            announced = !line.startsWith("failed: ") || line.contains("already started");
        } catch (Throwable refused) {
            // 一个没有蓝牙权限的接收端仍然能在 8770 上收东西，所以这里不是错误路径。
            announced = false;
            Log.w(TAG, "ble advertisement did not start: " + refused);
        }
    }

    /**
     * 权限刚补齐时补敲一次门。
     *
     * <p>{@link #beginAnnouncing} 只在 {@code start()} 里跑一次，而蓝牙广播那一步会当场失败
     * 返回。于是「先拨开关、再在权限框上点允许」——最常见的那个顺序——会留下一个安静的窟窿：
     * 接收端在听，人以为在等，而这台机器在对端的扫描里依然不存在，直到开关被拨第二次。人刚
     * 点完「允许」就发现还是收不到东西，这是最难自证的一件事。
     *
     * <p>两道闸：没在跑的接收端不敲（广播没有意义），已经发出去的也不敲（重复注册只会得到
     * {@code already started}）。所以从 {@code onResume} 每回一次都调是安全的。
     */
    void reannounce() {
        synchronized (lock) {
            if (announcer == null || announced) {
                return;
            }
        }
        announceOverBluetooth();
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
        // 两盏灯一起灭：只停 mDNS 会让蓝牙继续广播「我在这儿」，而对端听见之后连上来的是一
        // 个已经不在听的端口。
        //
        // 这一位跟着一起复位，因为它是「这一次生命周期的广播发出去了没有」；下一次 start 是
        // 新的一次，得重新问一遍。
        announced = false;
        try {
            Log.i(TAG, "ble stopped: " + new String(Native.bleStop(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable refused) {
            Log.w(TAG, "ble advertisement did not stop: " + refused);
        }
    }

    /**
     * The name a peer shows: {@code device.name}, then the phone's own name.
     *
     * <p>The row is the person's answer to "what should the other phone call this one", so it
     * wins outright. When it is blank the answer is the name the system itself uses — the one in
     * Settings → About → Device name, which Bluetooth advertises too — and not the model. That
     * was the old last step and it was the wrong one in the place it was most visible: an
     * untouched installation introduced itself to an iPhone as "PJZ110" while its owner called
     * the same phone something else everywhere else. {@link #systemName} still ends at the model
     * when the system reports nothing, so the chain can never come back empty.
     *
     * <p>The chain has to match {@code settings::name} in Rust, and that is not a duplication
     * to be tidied away: this copy is what the certificate's CN and the SRV target are made
     * from, both of which are fixed for the lifetime of the socket, while the answer a peer
     * reads is built per connection. Two implementations of the same rule is the cost of
     * freezing one of them.
     *
     * <p>Package-private because the feature page prints it: that row answers "what will a peer
     * read", and a second copy of the chain in a view class would not be a frozen twin but a
     * third rule to keep in step.
     */
    String peerName() {
        SettingsScreen screen = settingsNow();
        String named = value(screen, "device.name");
        return named.isEmpty() ? systemName(context) : named;
    }

    /**
     * 系统给这台手机起的名字：设置里那个「设备名称」，蓝牙广播的也是它。
     *
     * <p>从这里读而不是自己拼，是因为这件事只有一个答案——用户在「关于手机」里改了名字，蓝牙、
     * 热点、以及这一行都跟着变，而 {@code Build} 里没有任何一个字段记得那个名字。
     *
     * <p>两条路，顺序不是随便定的：{@code Settings.Global.DEVICE_NAME} 是系统自己存的那个字符串，
     * 读它不要权限，也是设置页显示的那个值；蓝牙适配器的名字是第二条，因为有些 ROM 只在蓝牙开着
     * 的时候才把名字写进那张表。两条都拿不到才落到机型上——那正是这一版要改掉的旧行为，留它只是
     * 因为「PJZ110」也好过一个空名字。
     *
     * <p>返回的串已经 trim 过；只有机型那一档可能为空（{@code Build.MODEL} 理论上是非空的，
     * {@link #systemModel} 兜了一层）。
     */
    static String systemName(Context context) {
        if (context != null) {
            try {
                String named = android.provider.Settings.Global.getString(
                        context.getContentResolver(),
                        android.provider.Settings.Global.DEVICE_NAME);
                if (named != null && !named.trim().isEmpty()) {
                    return named.trim();
                }
            } catch (Throwable unreadable) {
                // 个别 ROM 把这张表锁着。读不到就往下走，这不是错误。
            }
            try {
                android.bluetooth.BluetoothAdapter adapter =
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                String named = adapter == null ? null : adapter.getName();
                if (named != null && !named.trim().isEmpty()) {
                    return named.trim();
                }
            } catch (Throwable refused) {
                // API 31 起 getName() 要 BLUETOOTH_CONNECT，没授权就是 SecurityException。
            }
        }
        return systemModel();
    }

    /** What {@code Build} reports, which is the last step of both chains. */
    static String systemModel() {
        return Build.MODEL == null ? "Android" : Build.MODEL;
    }

    /** The table as it stands now, or null when it cannot be read — every caller treats that as blank rows. */
    private SettingsScreen settingsNow() {
        return Tuning.read(context);
    }

    /** One text row, trimmed; empty when the row is missing, blank, or there is no table at all. */
    private static String value(SettingsScreen screen, String key) {
        SettingsScreen.Setting setting = screen == null ? null : screen.setting(key);
        return setting == null ? "" : setting.value().trim();
    }

    /**
     * 一行时间设置，取不到就用 {@code fallback}。
     *
     * <p>夹范围和回落都在 {@link Tuning#ms} 里：发送端、收件箱、邀请卡片读的是同一张表，
     * 而「手改出来的零算不算数」这个问题只能有一个答案。
     */
    private static int timeoutOf(SettingsScreen screen, String key, int fallback) {
        return (int) Tuning.ms(screen, key, fallback);
    }

    /** Completes the handshake, hands the connection to the library, and puts what it stored on the page. */
    private void serve(SSLSocket peer) {
        long openedAt = System.nanoTime();
        // 表读不出来就是 null，下面每个取数都会回落到常量——握手这个界不该因为设置文件坏了而
        // 从「等 12 秒」变成「不等」。
        SettingsScreen tuned = settingsNow();
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
            // 这一行是表里的「握手超时」，常量只是表读不出来时的回落：一个把大文件传了八分钟
            // 的人，和他在同一个页面上改的其它数字，应该真的生效。
            peer.setSoTimeout(timeoutOf(tuned, "server.handshake_timeout_ms", HANDSHAKE_TIMEOUT_MS));
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

        String configured = directoryOf(screen);
        // 一次接收，三个表面：本应用的通知、互传那张卡片、以及页面上的进度。它们说的是同一
        // 件事，所以由同一个对象发出去，而不是各自在自己那一层里再算一遍（见 ReceiveHost）。
        ReceiveHost.Attempt attempt = ReceiveHost.open(context, peer);
        // 谁在看进度。每条连接一个，因为接收端一次只服务一个对端（见类注释「Why one
        // thread and not a pool」），所以进度条上最多只有一条连接的数字。
        Meter meter = new Meter(attempt);
        // 库里按路径写文件，而公共目录（Pictures、Movies…）在 targetSdk 37 下只能走媒体库，
        // 所以先落到应用自己的暂存目录，收完再由 Publish 搬进公共目录并挂到媒体库上。
        // 暂存目录只属于这一次连接，finally 里无条件删掉。
        File staged = null;
        try {
            staged = Publish.stage(context);
            // 有人要发东西过来时问一次。问的是人，所以这条线程会停在这里等——它本来也只
            // 服务这一个连接，等的时候没有别的事该做。
            byte[] frame = Native.serveWatched(
                    staged.getAbsolutePath(),
                    screen.stored(),
                    Build.MODEL,
                    // 对端在 /Discover 里读到的名字。空着的时候由 native 自己拼出来的是机型，
                    // 而这台手机在系统里叫什么只有这里知道，所以两个都交过去。
                    systemName(context),
                    peer.getInputStream(),
                    peer.getOutputStream(),
                    summary -> {
                        // The one moment a serving thread can tell a request from a body: the
                        // library has parsed the request and is about to read a file. From here
                        // on, a gap of eight seconds means a slow transfer, not an idle peer.
                        // 这一段的上限是表里的「读取超时」：传一个几百兆的视频时，两次读到数据
                        // 之间的空档可以很长，而这条界只该由拿着手机的人来定。
                        try {
                            peer.setSoTimeout(
                                    timeoutOf(tuned, "server.read_timeout_ms", TRANSFER_TIMEOUT_MS));
                        } catch (IOException gone) {
                            // The connection died while the offer was being drawn; the read
                            // that follows reports it, and this bound no longer matters.
                        }
                        // 谁想发什么。这三个字段（名字、件数、字节数）只在此时存在：连接一结束
                        // 就没有任何地方还记得它，而对端到底在分享面板里叫什么，是「别人能不能
                        // 找到我们」这件事唯一能留下证据的地方。
                        Log.i(TAG, "offer from " + summary.replace('\t', ' '));
                        // 问在有卡片的时候是卡片，没卡片的时候是通知——两条路都落回同一个闩，
                        // 所以这里不需要知道这一刻是哪一种。
                        return attempt.decide(summary);
                    },
                    meter);
            // A discovery probe and an empty share both come back empty; neither is worth a
            // line on the page, and leaving the previous transfer up is the truthful state
            // of "nothing new arrived".
            Report report = Report.of(frame);
            if (!report.empty()) {
                // 有的进了相册、有的进了文档：这一次接收的落点不再是一个目录，所以下面那句话
                // 由发布结果算出来，而不是由手上这份配置算出来。
                Publish.Result published = Publish.run(context, report, staged, configured, overwrite(screen));
                last = summarize(published);
                // 通知上那句话就是页面上那句话，一次传输只有一种说法。收尾在这里而不是在
                // 回调里：回调不知道哪一块是最后一块——total 是对端声明的，它可以少发，
                // 也可以什么都不声明，所以「最后一块」只有 native 调用返回才成立。
                meter.finished(last);
                // 卡片上要说的不是那句话，而是「存到哪儿了」和「每一份叫什么」——这个应用
                // 没有浏览目录的入口，除了这里没有第二个地方会说出来。
                attempt.delivered(published);
                setState("正在监听 " + PORT + "，等待附近设备", false);
            }
        } catch (IOException | RuntimeException refused) {
            // 对端走掉和真正出错，在异常类型上是同一件事，在意思上是两件。legacy 的同一处
            // catch 也只把 "Connection reset" 写进日志、不上报，理由写得很直白：分享面板关了、
            // 文件发给了别的设备、这是这一分钟里第四次重连——每两秒闪一次的红线不是证据。
            // 真正需要人看一眼的失败（设置读不出来、目录写不进去、连接被系统掐断）仍然上线。
            if (routine(refused)) {
                Log.i(TAG, "connection from " + where(peer) + " ended: " + describe(refused));
                return;
            }
            String because = because(refused);
            Log.w(TAG, "receive from " + where(peer) + " failed: " + describe(refused));
            setState("接收失败：" + because, true);
            // 页面、卡片、日志说的是同一句话，但页面可能是关着的——而人刚刚是从胶囊上点的
            // 「接收」，那颗胶囊才是他此刻还在看的东西。所以失败必须推到卡片上，而且不能
            // 像其余出口那样把卡撤掉（见 ReceiveHost.Attempt#failed）。
            attempt.failed(because);
        } finally {
            // 暂存里的东西只有两种下场：搬进公共目录，或者被删掉。走到这里时两种都已经发生
            // 过了，剩下的是空目录和搬失败的那几份——留着它们只是让 app 的数据目录一直长大。
            Publish.discard(staged);
            // 卡片那一侧要收尾，而且它还要从「还没结束的接收」那张表里退出去：一条握手失败的
            // 连接从来没对外说过话，但它在表里占着一个位置，不收回来的话这张表只增不减。
            //
            // 这里原来还有一句 {@code meter.abandon()}，去撤一条普通通知上的进度条。那条
            // 通知连同它一起没了（2026-11-19）：进度只画在流体云那张卡上，一处一句。
            attempt.abandon();
        }
    }

    /**
     * Ends a connection in a way the sender can see, instead of dropping it.
     *
     * <p>A {@code close()} on a socket whose receive buffer still holds bytes does not send a
     * FIN. The kernel sends RST, and the peer is told its transfer was aborted rather than
     * completed. That is exactly what a 162.6 MB transfer measured on 2026-11-15 looks like at
     * the end, on {@code awdltun0}, this device as the receiver:
     *
     * <pre>
     *   1794707288.180051 S: [F.] seq 1641, ack 170189078
     *   1794707292.129041 C: [P.] seq 170189078:170189102 length 24
     *   1794707292.129160 S: [R] seq 4143657351, win 0
     * </pre>
     *
     * <p>The sender's last record — 24 bytes, its TLS {@code close_notify} — arrives 2 ms
     * before this side answers RST. So the file was received in full, said so on this screen,
     * and the iPhone went on saying 正在发送: from its side the connection never ended, it was
     * destroyed, and a destroyed connection is not a completed one. Six of those RSTs went out
     * in that one teardown.
     *
     * <p>The order below is the whole fix, and each step is load bearing:
     *
     * <ol>
     *   <li>{@code shutdownOutput()} sends the FIN <em>now</em>. Closing after the drain would
     *       send one too, but only once the drain had finished — and the FIN is precisely the
     *       sentence the sender is waiting for.
     *   <li>Reading to EOF is what keeps the close from being a reset: by the time the
     *       descriptor goes, there is nothing left unread to discard.
     *   <li>{@code close()} last, on a socket with an empty receive buffer, sends a FIN and no
     *       more.
     * </ol>
     *
     * <p>Every step ignores its own failure. Reaching here means the transfer is over one way
     * or another — answered, refused, or the peer already gone — and a half-closed socket that
     * has started resetting is not a fault this device can act on. Nothing goes on the page for
     * the same reason {@link #routine} exists: a share sheet being closed is not news.
     */
    private void hangUp(SSLSocket peer) {
        try {
            peer.shutdownOutput();
        } catch (IOException alreadyGone) {
            // The connection died during the transfer, and the catch in the accept loop has
            // already said so on the page.
        }
        long until = System.nanoTime() + CLOSING_DRAIN_NANOS;
        try {
            peer.setSoTimeout(CLOSING_QUIET_MS);
            java.io.InputStream rest = peer.getInputStream();
            byte[] sink = new byte[512];
            while (System.nanoTime() < until) {
                if (rest.read(sink) < 0) {
                    break;
                }
            }
        } catch (IOException done) {
            // A timeout here is the normal exit: the peer has said everything it had. EOF and
            // a reset land in the same place, which is "stop reading".
        } finally {
            closeQuietly(peer);
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
                //
                // 一个例外：「Software caused connection abort」（ECONNABORTED）。它不是对端
                // 发来的 RST，而是**本机内核**中止了这条连接——和上面那几种恰好相反。归纳进
                // 「对端走掉」的代价在 2026-11-17 付过一次：ColorOS 的后台限网（nwpower）在
                // 应用离开前台的同一毫秒掐掉 socket，人点「接收」时已经晚了三秒，这里把
                // 它当成别人自己走了，于是屏幕上什么都没有。
                return !aborted(cause);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * 这条连接是不是被本机掐断的（{@code ECONNABORTED}）。
     *
     * <p>Java 把 errno 丢在异常消息里，所以只能读那句话。选消息而不是选异常类型，是因为
     * {@code SocketException} 在这一层已经把 reset、broken pipe、closed 全都收进了同一个类，
     * 而这三句是 libcore 的固定译文、Android 每个版本都这么写。
     *
     * <p>为什么不把 ECONNABORTED 归成「对端走掉」：这两个 errno 的方向相反。reset 是**对面**
     * 发了 RST（分享面板关了），abort 是**我们自己**的内核把 fd 收了（后台限网、VPN 掉了、
     * 息屏断网）。前者不该打扰人，后者恰恰是这台机器出了问题——而它偏偏长得最像前者。
     */
    private static boolean aborted(Throwable socket) {
        String message = socket.getMessage();
        return message != null && message.contains("Software caused connection abort");
    }

    /**
     * 这个人指定的接收目录，没指定就是空。
     *
     * <p>空不再翻译成一个默认路径，而是交给 {@link Publish}：那里按扩展名分五桶。填了值
     * 就全部进那一个目录——这条承诺写在设置表的 summary 里，也由 {@code Publish.run} 里
     * 那一行 {@code fixed.isEmpty()} 守住。
     */
    private String directoryOf(SettingsScreen screen) {
        SettingsScreen.Setting setting = screen.setting("receive.directory");
        return setting == null ? "" : setting.value().trim();
    }

    /** 同名时覆盖。表里的开关是 {@code "true"}/{@code "false"} 两个字符串。 */
    private boolean overwrite(SettingsScreen screen) {
        SettingsScreen.Setting setting = screen.setting("receive.overwrite");
        return setting != null && "true".equals(setting.value());
    }

    /** 一行话：落了什么、落在哪儿。 */
    private String summarize(Publish.Result published) {
        List<Publish.Entry> entries = published.entries();
        // 说不下的那几份要单独提一句：少了东西而不说，比多一句更难查。用「另有」起头，
        // 是因为它挂在「存到 X/」后面——直接接「1 个没存下」读起来像在说那个目录本身。
        String lost = published.skipped().isEmpty()
                ? "" : "，另有 " + published.skipped().size() + " 个没存下";
        if (entries.size() == 1) {
            Publish.Entry entry = entries.get(0);
            return "收到「" + entry.name + "」，" + bytes(entry.size)
                    + "，存到 " + entry.directory + "/" + lost;
        }
        return "收到 " + entries.size() + " 个文件，共 " + bytes(published.bytes())
                + "，存到 " + where(entries) + lost;
    }

    /**
     * 这一批落在哪几处。
     *
     * <p>五类分开存之后，「存到 X/」这句话可能同时有五个答案，而通知只有一行。一处就把
     * 整个相对路径说出来（{@code Pictures/OPAirDrop/}）；两处点名；三处以上点名前三个加
     * 总数——再多就不是一句话，而是一张表了。
     */
    private static String where(List<Publish.Entry> entries) {
        Set<String> roots = new LinkedHashSet<>();
        for (Publish.Entry entry : entries) {
            // 只取第一段：五个桶共用一个 OPAirDrop 子目录，能区分它们的是前面那一层。
            String directory = entry.directory;
            int slash = directory.indexOf('/');
            roots.add(slash < 0 ? directory : directory.substring(0, slash));
        }
        if (roots.size() == 1) {
            return entries.get(0).directory + "/";
        }
        List<String> names = new ArrayList<>(roots);
        if (names.size() == 2) {
            return names.get(0) + "、" + names.get(1) + " 两处";
        }
        return names.get(0) + "、" + names.get(1) + "、" + names.get(2)
                + " 等 " + names.size() + " 处";
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

    /**
     * 同一件事给人看的说法。
     *
     * <p>{@link #describe} 是给日志的：异常原文，能拿去搜。这一句是给拿着手机的人的——他
     * 不知道 socket 是什么，他要的是「刚才那一下为什么没成、下回该改哪里」。
     *
     * <p>只有 ECONNABORTED 需要翻译。其余非例行的失败（目录写不进去、设置读不出来）原文
     * 本来就带着人能看懂的那半句，比如文件名和路径。
     */
    private static String because(Throwable failed) {
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (aborted(cause)) {
                // 把可行动的那一步一起说出来。措辞与首屏那条提示条、设置页同一个说法对齐
                // （见 Notices.gap 的「后台被限网」），否则人会以为是两件事。
                return "连接被系统切断，检查后台限网";
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return describe(failed);
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
     * 把库里报的字节数变成至多每秒五条的状态更新。
     *
     * <p># 为什么必须节流
     *
     * <p>{@link Native.Landing#landed} 是按落地块调用的，一次传输就是成千上万次，而每一次
     * 更新都是一次 binder 事务，全都发生在正在读字节的那条线程上——传输在等它。进度每秒
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

        /**
         * 这一次接收的落点：流体云那张卡（{@link ReceiveHost}）。
         *
         * <p>只有它一处。这里原来还写着「本应用的通知和卡各一份」，那条通知已经撤掉
         * （2026-11-19）：同一条传输在两个地方各画一遍，人看到的只会是两个不一样的数。
         */
        private final ReceiveHost.Attempt attempt;

        /** 上次投递的时刻（{@link System#nanoTime}）；0 表示一次都还没投递过。 */
        private long postedAt;

        Meter(ReceiveHost.Attempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public void landed(long done, long total) {
            long now = System.nanoTime();
            // 第一条立刻投出去：否则前两百毫秒里一个小文件已经收完了，人什么都没看见。
            if (postedAt != 0 && now - postedAt < INTERVAL_MS * 1_000_000L) {
                return;
            }
            postedAt = now;
            // 进度只画在流体云那张卡上（{@code attempt.progress}）。普通通知不再画任何进度：
            // 同一条传输在两处同时走进度，一处是胶囊、一处是通知栏，人只会看见两个不一样的数。
            attempt.progress(done, total);
        }

        /** 有结果了。 */
        void finished(String text) {
            // 收完那句话由流体云的卡说（带【打开文件】【完成】）。这里不再另发一条普通通知，
            // 否则一次接收会在通知栏和胶囊里各留一条同样的话，而其中一条点不动。
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

    /**
     * The same, for an accepted connection.
     *
     * <p>An overload rather than a common {@code AutoCloseable}: the two call sites want
     * different things said about them — one is the loop being told to stop, the other is the
     * last step of a transfer — and a shared body would flatten that into one comment about
     * neither.
     */
    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // The peer may have reset the connection while it was being drained. The
            // descriptor is gone either way, which is all this method promises.
        }
    }
}
