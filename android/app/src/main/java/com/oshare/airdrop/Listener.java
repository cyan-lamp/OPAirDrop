package com.oshare.airdrop;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p># Where the door is, and why it stays open
 *
 * <p>Accepting is one thread; serving is not. The accept loop hands each connection to a thread
 * of its own and goes straight back to {@code accept()}, because the slow part of serving is not
 * this device's work — it is a person deciding. A connection that has asked whether to receive
 * something can sit there for the whole sixty-second invitation timeout, and while it did, the
 * old loop was inside its {@code serve()}: the next peer's TLS handshake had not even begun. On
 * 2026-11-19 19:54 that closed the door for 12.3 seconds ({@code offer} at 23.400,
 * {@code accepted} at 35.755), and on the other side of the link it looks exactly like a
 * receiver that is slow to show a card, or one that has gone away.
 *
 * <p>What is still one at a time is the card. The invitation, the progress drawn on it, and the
 * move of finished files into the public directory all happen under {@link #card} — and that
 * lock is taken in the one place a person is asked, never before the handshake. There is one
 * fluid-cloud card on this device (one notification id), so two invitations at once would
 * overwrite each other's buttons; and two publishes at once would race for the same file name,
 * which is the rule this class has always kept. Handshakes, request headers and {@code
 * /Discover} probes were never part of that rule — holding the gate across them is precisely
 * what made a parked invitation look like a dead receiver.
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
     * 同时接待的连接数上限。
     *
     * <p>八个，而这个数不是调参调出来的：一个发送端会开两条连接（一条 {@code /Discover}
     * 探测、一条 {@code /Ask} 邀请），所以八条就是四台设备在同一秒里点发送。再往上不是
     * 拒绝，是退回旧做法——见 {@link #handOver}。
     */
    private static final int PEERS = 8;

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
     * <p>每条连接现在都有自己的线程（见类注释），所以一条不说话的 socket 不再连累别人：它
     * 只花掉一个线程、一个描述符、以及「还没结束的接收」那张表里的一个位置——而那张表会被
     * 界面重放到卡片上。上限仍然要有：不设的话同样这条连接会占着线程直到 TCP 自己放弃，那是
     * 几分钟。一台握完手就改了主意的手机会反复来（分享面板开着的时候就是这样），那是常态，
     * 不是攻击；八秒是 legacy 的数字，比一个真实发送端拼出第一句请求需要的时长多一个量级。
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
     * ends it for a peer that has not. A thread of its own made this bound less urgent — a drain
     * no longer blocks anybody else — but it is still what keeps a peer that keeps writing after
     * its transfer was answered from pinning a thread and a descriptor until TCP gives up. One
     * second is the same order as the pause, and still short enough that the next transfer is
     * not waiting on it.
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
     * 此刻正在被接待的连接数：握着 socket、握着线程的那些。
     *
     * <p>只用来判 {@link #PEERS}。用它而不是一个池对象，是因为这里要的只有一件事——「手里
     * 还有没有手」。线程本身就是池：它们跑完就结束，没有需要回收的队列，也没有需要关闭的
     * executor；{@code stop()} 关掉的是端口，不是一条正在往里写字节的传输。
     */
    private final AtomicInteger serving = new AtomicInteger();

    /**
     * 那一次「问人」的独占权：一张卡，一次只问一个人。
     *
     * <p># 为什么要有
     *
     * <p>这台设备只有一张流体云卡——{@link LiveCard} 用的是同一个通知 id（{@code 0x0B00}）。
     * 两条邀请同时在场时，后一条会把前一条的【接收】【拒绝】盖掉，而按钮里带着各自的
     * session，于是人按下去取消/接收的是另一次传输。一旦接受了，进度也画在同一张卡上：两条
     * 传输各画各的百分比，人看到的只会是两个都不对的数。最后还有一步只该一个人做——把暂存
     * 目录里的文件搬进公共目录、在那里决定重名谁赢（见 {@link Publish#run}）。
     *
     * <p># 为什么在这里，而不是在 accept 之后
     *
     * <p>它必须**恰好**套在「问人」这一步上，早一点都不行。套在握手前（或套在整个
     * {@code serve()} 外面）就是改之前的样子：一次邀请停满六十秒，这期间下一条连接的 TLS
     * 握手根本还没开始，`/Discover` 也答不了——对面看到的是「这台设备有时很久才显示，有时
     * 一直不显示」。
     *
     * <p># 谁拿、谁放
     *
     * <p>拿在 {@link #serve} 的 {@code decide} 回调里（库第一次要问人时），放在同一条线程的
     * finally 里。用 {@link ReentrantLock#isHeldByCurrentThread()} 判断「我拿过没有」，而不是
     * 另设一个标志位：这件事锁自己就知道，多一个变量就多一个会和它不一致的地方。
     *
     * <p>代价写在这里，因为它是一个真实的取舍：两条邀请同时到达时，第二条会等到第一条被回答
     * 或者过期为止。这是「一台设备只有一张卡」的直接后果，不是排队排错了地方——而在此之前，
     * 那段时间连门都是关着的。
     */
    private final ReentrantLock card = new ReentrantLock();

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
     * 这一次生命周期里已经有人去造 {@link Responder} 了。
     *
     * <p>{@link #announcer} 非 null 不足以回答这个问题：从「决定要造」到「把它放进去」之间隔着
     * {@code ServiceId.loadOrCreate} 的读文件与 {@link Responder} 的构造，那几毫秒里
     * {@link #beginAnnouncing} 可以被第二个人叫到。2026-11-18 真机上量到过，两条线程各造了一个：
     *
     * <pre>
     *   19:38:52.680  27364  ble: starting
     *   19:38:52.681  27362  ble: starting
     *   19:38:52.682  27367  announcing 7f5ed39dbd10 at air.local:8770 on awdltun0
     *   19:38:52.683  27370  announcing 7f5ed39dbd10 at air.local:8770 on awdltun0
     * </pre>
     *
     * <p>那两条线程是 {@code start()} 里的 {@link #beginAnnouncing} 和 {@link StackKeeper} 那侧
     * 链路一就绪就打来的 {@link #retryAnnouncing}——真机上链路通常比磁贴早，所以这一撞是常规
     * 路径而不是边角。后果不是多几行日志：{@link #announcer} 只装得下一个，先造的那个成了孤儿，
     * 它的 socket 与两条线程没人收，{@link #endAnnouncing} 也永远不会停它。于是「关」之后仍有人
     * 每三秒公告一次——对端看见一台点了却没人接的设备。
     *
     * <p>用一位独立的标志而不是让 {@link #announcer} 早点非 null：那个人造到一半就抛异常时，
     * 留一个空的 {@link #announcer} 会让 {@link #retryAnnouncing} 以为「有对象了，重启它」，
     * 而重启一个构造失败的对象救不回任何东西。
     *
     * <p>Guarded by {@link #lock}；{@link #endAnnouncing} 里落回 false，下一次启动是新的一次。
     */
    private boolean begun;

    /**
     * 这一次启动用的身份，留着是为了能把广播重新开一次。
     *
     * <p>{@link #beginAnnouncing} 只在 {@code start()} 里跑一次，那一次很可能撞上「链路还
     * 没建好」——磁贴刚点开时正是如此（见 {@link StackKeeper}）。链路随后建好，广播却已经
     * 放弃了，除了人再拨一次开关没有第二个机会。
     *
     * <p>用存下来的这一份而不是重新读：证书是这次启动的证书，广播里的名字和它必须一致
     * （见 {@link #beginAnnouncing}）。重开广播不是新的一次启动，重新读文件反而会让两边分家。
     */
    private volatile Identity identity;

    /**
     * Why there is no announcer, when there is none for a reason worth saying.
     *
     * <p>Separate from {@link Responder#aside()} because it is written before the announcer
     * exists — an unreadable service-id file means there is no object to ask.
     */
    private volatile String asideProblem = "";

    /**
     * 协议栈说它在广播了没有。
     *
     * <p>这一位存在的理由只有一个：{@link #announceOverBluetooth} 是一次性的敲门，敲不开就
     * 结束了，不像 socket 那样会重连。而「先拨开关、再在权限框上点允许」是最常见的顺序——
     * 那次敲门必然发生在权限到手之前。没有这一位，就没有任何依据判断「现在再敲一次」是否
     * 有意义，只能盲目重试或者永远不试。
     *
     * <p>判据只认 {@code advertising}。原来是「开头不是 {@code failed: } 就算醒了」，那会把
     * {@code starting} 也算成已醒——而 {@code starting} 恰恰是后台线程那次调用**唯一**的返回：
     * {@code bleAdvertise()} 把注册投给电台线程之后立刻读状态，读到的一定是刚置上的
     * {@code starting}。于是 {@link StackKeeper} 那每八秒一次的补敲一旦发生，这一位就永远停在
     * true；后来栈真的回了 {@code failed:}，{@link #reannounce} 也再不会被放行——而它要补的
     * 正是「点完允许、重新敲一次」那个窟窿。
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
     * killing it mid-archive would leave half a file that looks whole — and it finishes on the
     * thread that is serving it, so the loop itself ends at once: {@link #running()} goes false
     * when the port closes, whether or not a transfer is still draining behind it.
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
            this.identity = identity;
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
                // 绝不在这条线程上 serve：见类注释「Where the door is」。接待一条连接里最慢的
                // 一件事是等人点按钮——一次邀请可以停满六十秒，停在这条线程上就等于把门口关了
                // 那么久，而门口关着的时候，下一条连接连 TLS 握手都还没开始。
                //
                // The cast is what the socket factory above guarantees; accepting a plain
                // Socket here is not a case that can arise, and a defensive instanceof
                // would only hide it if it did.
                handOver((SSLSocket) peer);
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
                // that the port is closed — a transfer still draining on its own thread does
                // not make this device reachable again.
                tell();
            }
        }
    }

    /**
     * 把一条已经接下的连接交给它自己的线程，然后立刻回去接听下一条。
     *
     * <p>「门一直开着」就是这一件事。接待一条连接里最慢的步骤不是本机的活，是等人点按钮：
     * 一次邀请可以停满整张表的六十秒，而 2026-11-19 19:54 那次停了 12.3 秒——那段时间里旧
     * 代码正停在 {@code serve()} 里，下一条连接的 TLS 握手根本没开始。
     *
     * <p>并发有上限，但上限不是丢连接：手边的线程用完时，第九条连接回到这条线程上被接待，
     * 也就是旧行为——它会让下一条多等一会儿，但它不会被拒绝，也不会被扔进一个没人读的队列。
     * 真实的上限是四台设备同时发：每台开两条连接（一条探测、一条邀请）。
     *
     * <p>线程是 daemon，跑完就结束。{@link #stop()} 不去碰它们：它关的是端口，而一条正在
     * 往盘里写字节的传输该被允许写完——半份文件看起来和完整的那份一模一样。
     */
    private void handOver(SSLSocket peer) {
        if (serving.get() >= PEERS) {
            handle(peer);
            return;
        }
        serving.incrementAndGet();
        Thread thread = new Thread(() -> {
            try {
                handle(peer);
            } finally {
                serving.decrementAndGet();
            }
        }, "opairdrop-peer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 接待一条连接，直到它结束：{@code serve}，然后无论结果如何都把它好好送走。
     *
     * <p>{@code serve} 把自己的失败都收在里头，一声不抛；这里仍然接一层，因为接住它的代价
     * 是零，而不接的代价是旧的死法——那条线程以前就是 accept 循环本身，于是一次没预料到的
     * 运行时异常死掉的不是一条连接，是整个接收端。
     */
    private void handle(SSLSocket peer) {
        try {
            serve(peer);
        } catch (RuntimeException unexpected) {
            Log.w(TAG, "connection from " + where(peer) + " broke the server: "
                    + describe(unexpected));
        } finally {
            hangUp(peer);
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
        // 一道闸，先占位再干活。理由见 {@link #begun}：先造后发的话，两条线程会各造一个，
        // 而只有一个能被停掉。
        synchronized (lock) {
            if (begun) {
                return;
            }
            begun = true;
        }
        try {
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
        } catch (RuntimeException refused) {
            // 造不出来就把位子还回去，{@link #retryAnnouncing} 才还有下一次机会——它那一段
            // 「上一次连 Responder 都没造出来」说的正是这里。占着位子不放会让链路随后就绪
            // 时也只是安静地看着，而人对面看到的是一台不存在的设备。
            synchronized (lock) {
                begun = false;
            }
            asideProblem = "广播未起：" + refused;
            Log.w(TAG, "announcer could not be created", refused);
            return;
        }
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
            // Rust 侧把四态压成这一行（idle / starting / advertising / failed: 原因），而这里
            // 要回答的是**「现在还有一份注册在栈上吗」**，不是「这一次敲成功了吗」。
            //
            // `starting` 必须算没醒：它只说明话递出去了、回话还没到（后台线程那次调用的唯一
            // 返回），而 {@link #retryAnnouncing} 与 {@link #reannounce} 靠这一位决定「再敲
            // 一次还有没有意义」。再敲一次本身不要紧——{@code bleAdvertise} 见到 {@code
            // is_awake} 就直接回流状态行，不会重复注册。
            //
            // `already started` 要算醒：那是栈在说「已经有一份在播」（{@code ADVERTISE_FAILED_
            // ALREADY_STARTED}，见 {@code ble::explained}）。它比我们这本账更权威，所以照旧认它。
            announced = line.startsWith("advertising") || line.contains("already started");
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
     * <p>两道闸：没在跑的接收端不敲（广播没有意义），已经确认在广播的也不敲——再敲一次不会
     * 重复注册（{@code bleAdvertise} 见到 {@code is_awake} 就直接回流状态行），但那一次是空转，
     * 也拿不到新信息。所以从 {@code onResume} 每回一次都调是安全的。
     */
    void reannounce() {
        synchronized (lock) {
            if (announcer == null || announced) {
                return;
            }
        }
        announceOverBluetooth();
    }

    /**
     * 链路刚建好之后，把广播再起一次。幂等，可以从任何线程调用。
     *
     * <p>{@link #reannounce} 补的是「蓝牙那一下敲早了」，这一位补的是另一条、也是 2026-11-18
     * 真机上量到的那条：接收端起来的时候 AWDL 链路还不存在，{@link Responder#bind()} 抛
     * {@code 没有网卡 awdltun0}，{@code aside()} 从此一直写着「广播未起」——而链路在二十五秒
     * 后才建好。那之后有两条路，旧代码两条都没有：等下一次 {@code start()}（要人去关再开一次
     * 开关），或者就这么听着 8770 而谁也找不到。
     *
     * <p>一道闸，与 {@code reannounce} 同一个理由：接收端没在跑就不叫——广播「我在这儿、来发
     * 给我」而 8770 没在听，是比看不见更坏的一种存在。已经在跑的那一半不再直接返回：mDNS 与
     * 蓝牙是「这台机器可以被发现」的两层，只坏一层时这一句才有事可做（见下面的注释）。
     *
     * <p>{@link Responder#start()} 允许这样被叫第二次：它在失败路径上会把 opener 清掉，那时候
     * 再叫一次就是真的重新 bind 一次（看 {@code Responder#open} 里的注释，那里写的就是这个
     * 场景）。
     */
    void retryAnnouncing() {
        Responder announcing;
        Identity who;
        synchronized (lock) {
            if (worker == null || stopping) {
                return;
            }
            announcing = announcer;
            who = identity;
        }
        if (announcing == null) {
            // 上一次连 Responder 都没造出来（读 service-id 就失败了）。身份在手就再试一次，
            // 那会重走一遍读文件与起蓝牙广播。
            if (who != null) {
                beginAnnouncing(who);
            }
            return;
        }
        if (announcing.running()) {
            // mDNS 在，蓝牙不在。这是「磁贴关掉再打开」之后真机上落到的那条缝：Responder 被
            // stop 之后再 start 是一次全新的 bind，成功了；而 BLE 那一次 advertise 落在 stop
            // 的尾巴上（{@code ble stopped: idle} 之后紧接着的一次 start），r0 侧没有重新注册，
            // 于是 iPhone 的分享面板永远等不到那二十字节——mDNS 发得再对也没人听。
            //
            // {@code announceOverBluetooth} 幂等：{@code bleAdvertise} 见到 {@code is_awake}
            // 就回流状态行，不会重复注册。所以这里可以每 tick 叫一次；真卡在 {@code starting}
            // 时它还会每 tick 留一行，那正是我们想看见的那种「没动静」。
            if (!announced) {
                announceOverBluetooth();
            }
            return;
        }
        announcing.start();
    }

    /** Takes the instance back and closes the announcer's socket. Safe to call when there is none. */
    private void endAnnouncing() {
        Responder announcing;
        synchronized (lock) {
            announcing = announcer;
            announcer = null;
            // 下一次 start 是新的一次「谁去公告」，{@link #begun} 得跟着这条生命周期一起回来。
            begun = false;
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
        // 谁在看进度。每条连接一个：进度只画在那条连接自己的邀请上，而同一时刻只有一条连接
        // 拿着卡（见 {@link #card}），所以卡上那个数永远只属于一个人。
        Meter meter = new Meter(attempt);
        // 库里按路径写文件，而公共目录（Pictures、Movies…）在 targetSdk 37 下只能走媒体库，
        // 所以先落到应用自己的暂存目录，收完再由 Publish 搬进公共目录并挂到媒体库上。
        // 暂存目录只属于这一次连接，finally 里无条件删掉。
        File staged = null;
        // 卡住的时候取一次栈（见 {@link Stall}）。只在 {@code serveWatched} 那一段里开着：
        // 收完之后 {@code Publish} 搬文件也可能要几秒，那时「线安静」是正常的，不该报。
        Stall stall = null;
        try {
            staged = Publish.stage(context);
            stall = new Stall(meter, where(peer));
            stall.start();
            // 有人要发东西过来时问一次。问的是人，所以这条线程会停在这里等——它只服务这一个
            // 连接，等的时候确实没有别的事该做；但别的连接各有各的线程，门不会因此关上。
            byte[] frame = Native.serveWatched(
                    staged.getAbsolutePath(),
                    screen.stored(),
                    Build.MODEL,
                    // 对端在 /Discover 里读到的名字。空着的时候由 native 自己拼出来的是机型，
                    // 而这台手机在系统里叫什么只有这里知道，所以两个都交过去。
                    systemName(context),
                    // 包一层只为记下「它什么时候开口、第一句是什么」。见 {@link Spoken}：它不改
                    // 字节、不改时机，只是把读到的抄一份。
                    new Spoken(peer.getInputStream(), where(peer), System.currentTimeMillis()),
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
                        // 门在这里才关上，而且只关到这一步为止（见 {@link #card}）：一台设备
                        // 只有一张流体云卡，两条邀请同时在场会互相盖掉对方的按钮；接受之后的
                        // 进度、以及最后把文件搬进公共目录那一步，也都在这把锁里。而握手、读
                        // 请求头、/Discover 全在锁外——把它们排在这扇门后面，正是「弹窗迟迟
                        // 不出来」和「有时候一直不显示这台设备」的成因（见类注释）。
                        card.lock();
                        // 问在有卡片的时候是卡片，没卡片的时候是通知——两条路都落回同一个闩，
                        // 所以这里不需要知道这一刻是哪一种。
                        return attempt.decide(summary);
                    },
                    meter);
            // 字节已经收完了，接下来的安静属于搬文件，不属于这条线。
            stall.stop();
            // 而且要在这里就把进度投递停掉：下面 {@code meter.finished} 和
            // {@code attempt.delivered} 要往同一张卡上写「接收完成」，一条还在飞的进度落在
            // 它后面，屏幕上就是一张停在 97%、再也不会动的卡。{@code close} 会等投递线程退出。
            meter.close();
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
            } else if (attempt.deliveredLinks()) {
                // 一次纯链接分享：`Items` 里有地址，`Files` 是空的，所以一个字节都不会
                // `/Upload`，`report` 也就是空的——上面那条分支永远走不到它。
                //
                // 不在这里结账的后果不是「少一张卡」，而是一张**说假话的卡**：finally 里的
                // {@link ReceiveHost.Attempt#abandon} 看见「人说收了」却没结过账，会把这次
                // 成功画成「接收失败：连接意外断开」。线是通的、对面拿到了 200，只有屏幕上
                // 那句话是假的。
                //
                // 页面上那句话仍然要换掉：人刚刚在卡片上点了接收，页面还停在「等待附近设备」
                // 就等于什么都没发生。链接没有落点也没有字节数，所以这句只说到"收到了"为止。
                last = "已收到链接";
                setState("正在监听 " + PORT + "，等待附近设备", false);
            }
        } catch (IOException | RuntimeException refused) {
            // 对端走掉和真正出错，在异常类型上是同一件事，在意思上是两件。legacy 的同一处
            // catch 也只把 "Connection reset" 写进日志、不上报，理由写得很直白：分享面板关了、
            // 文件发给了别的设备、这是这一分钟里第四次重连——每两秒闪一次的红线不是证据。
            // 真正需要人看一眼的失败（设置读不出来、目录写不进去、连接被系统掐断）仍然上线。
            // 这一行回答的是「这次是谁先撒手」。2026-11-19 21:01–21:04 连收七次，六次停在
            // 64 / 192 / 576 KiB，每次日志里只有一句 {@code connection … ended: Socket closed}，
            // 而那句话在 libcore 里是**本机**关掉 fd 的措辞（对端走掉留下的是 reset 或 EOF）
            // ——它既可能是「对面收手、本机内核跟着收 fd」，也可能是「这一侧有人 close 了它」，
            // 两种可能指向的改法正好相反，而光靠这一句话分不出来。所以把手上的三样证据一起
            // 打出来：异常到底是哪一类、这条 socket 在**本进程里**有没有被 close 过、断之前线
            // 安静了多久。三样凑齐了，{@code closed=true} 且安静几秒，那就是人在等一个卡住的
            // 传输，而不是网络把人踢了；`false` 且只安静几十毫秒，才是对面先撒手。
            Log.i(TAG, "connection from " + where(peer) + " broke after " + millis(openedAt)
                    + " ms: " + refused.getClass().getName() + ", closed=" + peer.isClosed()
                    + ", " + meter.quiet() + ", " + bytes(meter.bytes()) + " landed");
            if (routine(refused)) {
                Log.i(TAG, "connection from " + where(peer) + " ended: " + describe(refused));
                return;
            }
            // 没人回答过的连接断了，同样不上报。
            //
            // 这一支是为 2026-11-19 15:18 抓到的那一条加的：邀请挂了 30 秒没人应答，是我们
            // 自己把这次邀请判成 expired、自己把连接收掉的，内核于是回 ECONNABORTED——它从
            // 上面 {@link #routine} 里漏了出来（abort 本来就不该算「对端走掉」），一路走到
            // {@code because(…, 0)}，得到「连接被系统切断，检查后台限网」。网络一点问题都没有，
            // 只是没人点那一下。卡片那一层恰好侥幸没事（{@code Attempt.failed} 有 {@code if
            // (cloud)} 把关，没人回答就没有 cloud），但页面那行是红的，还劝人去查路由器。
            //
            // 判据用「有没有人回答过」而不是「有没有字节落地」：这一支的字节数必然是 0，
            // 落地量分不出「一直没人理」和「人点了、三秒后才断」。11-17 那次后台限网不受影响
            // ——那次人**点了**接收，只是 socket 早就死了，answered 为真，仍按失败上报。
            if (!attempt.answered()) {
                Log.i(TAG, "connection from " + where(peer) + " ended unanswered: "
                        + describe(refused));
                return;
            }
            // 先把投递停掉再往卡上写失败：否则一条还在飞的「正在接收」会落在失败那句后面，
            // 屏幕上就只剩一张停在半路、再也不会动的进度卡。上面那两处 {@code return} 由
            // {@code finally} 兜住——它们本来就不许碰卡。
            meter.close();
            String because = because(refused, meter.bytes());
            Log.w(TAG, "receive from " + where(peer) + " failed: " + describe(refused));
            setState("接收失败：" + because, true);
            // 页面、卡片、日志说的是同一句话，但页面可能是关着的——而人刚刚是从胶囊上点的
            // 「接收」，那颗胶囊才是他此刻还在看的东西。所以失败必须推到卡片上，而且不能
            // 像其余出口那样把卡撤掉（见 ReceiveHost.Attempt#failed）。
            attempt.failed(because);
        } finally {
            // 这一次接待结束了，把卡交出去，下一条邀请才能问出口。用 isHeldByCurrentThread
            // 而不是另设一个标志位：这把锁有没有被这条线程拿过，它自己知道；多一个变量就是
            // 多一个会和它不一致的地方。握手失败、设置读不出来的那些连接从来没拿到过，
            // 这里也就什么都不做。
            if (card.isHeldByCurrentThread()) {
                card.unlock();
            }
            // 断了的时候上面那句 {@code stall.stop()} 走不到（异常直接跳到 finally），所以这里
            // 再收一次。收完之后线还会安静很久——那是收尾，不是卡住。
            if (stall != null) {
                stall.stop();
            }
            // 投递线程也一样：上面那两处都走不到的时候（比如 {@code Publish.stage} 就抛了）
            // 这里收。幂等，只有第一次真做事。
            meter.close();
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
     * <p>为什么不把 ECONNABORTED 归成「对端走掉」：两个 errno 的方向不一样。reset 一定是
     * **对面**发了 RST（分享面板关了）；abort 是本机这条连接被中止——后台限网、VPN 掉了、
     * 息屏断网，或者，如 2026-11-19 实测的那样，**对端在 AWDL 这条隧道上收手**，我们这边的
     * 内核跟着把 fd 收了。前者不该打扰人，后者要看情况，所以它单独判一次，具体说法见
     * {@link #because(Throwable, long)}。
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

    /**
     * A failure's own words, or its class when it has none.
     *
     * <p>只给日志用。屏幕上那句走 {@link #readable}：这里的原文可以是一整段英文加十六进制
     * （见 {@code dvzip.rs} 的诊断），那是拿来搜和拿来读证据的，不是拿来给人看的。
     */
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
     *
     * <p># ECONNABORTED 要看「断了之前走过字节没有」
     *
     * <p>这个 errno 原来是**一律**翻成「检查后台限网」的，依据是 2026-11-17 那次：ColorOS 的
     * nwpower 在应用离开前台时掐掉 socket，那时确实一个字节都没走。
     *
     * <p>2026-11-19 14:55 的实测推翻了「一律」：iPhone 发一个 15.5 MB 的视频过来，传到 2.3 MB
     * 时那边按了取消，我们这一侧抛的是
     *
     * <pre>
     * 14:55:18.287 W/OPAirDrop: receive from fe80::704d:92ff:fe08:dd2%awdltun0 failed:
     *                            Software caused connection abort
     * </pre>
     *
     * <p>——同一个 errno，意思却相反：这次是**对面**停的。按原来那句话去查后台限网，人会在
     * 一个没有问题的设置上打转。
     *
     * <p>所以判据换成「有没有字节真的落地」：{@code /Upload} 一个字节都没开始就走完的连接，
     * 只可能是本机这边先没了（限网、息屏、接口掉了）；走了几兆才断的，是传到一半被停下——
     * 而这一次是谁停的，从我们这一侧分不出来（对面取消、和对面手机息屏断网，留下的都是这一句），
     * 所以那句话不点名，只说发生了什么、丢了多少。
     *
     * @param landed 断之前已经落地的字节数
     */
    private static String because(Throwable failed, long landed) {
        // 兜底那一句走 {@link #readable} 而不是 {@link #describe}：原因见那边。这一处是**唯一**
        // 会把这句送到屏幕上的地方（{@code setState} 与 {@code attempt.failed} 都用它）。
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (aborted(cause)) {
                if (landed > 0) {
                    return "传输在中途被中断，已收到 " + bytes(landed) + "，没有存下";
                }
                // 把可行动的那一步一起说出来。措辞与首屏那条提示条、设置页同一个说法对齐
                // （见 Notices.gap 的「后台被限网」），否则人会以为是两件事。
                return "连接被系统切断，检查后台限网";
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return readable(failed);
    }

    /**
     * 一句人能读的失败原因。原文只进日志。
     *
     * <p>这里原来直接把 {@link #describe} 的返回值交出去，理由是「目录写不进去、设置读不出来
     * 这类原文本来就带着人能看懂的那半句」。2026-11-19 20:57 那张卡把这个理由推翻了：人看到的是
     *
     * <pre>
     * 接收失败 · transport: the archive could not be read:
     *            implausible DVZip block length 0 (header 0x00000000 at offset 115)
     * </pre>
     *
     * <p>——英文、术语、十六进制，三样他都不知道该怎么读。而那时这段原文还只有一句；随后
     * {@code dvzip.rs} 为了查清那一次的成因又往里加了「读过几块、body 起始字节、首块解出什么」，
     * 加起来上百个字符的十六进制。那些字节是**给日志的**：{@link #describe} 原样进 logcat
     * （见 {@code serve} 里那句 {@code Log.w(… failed: …)}），屏幕上不该出现它们。
     *
     * <p>所以分成两条出口。认不出来的失败回一句不点名的实话，而不是把英文倒给屏幕：认不出来的
     * 那些（SSL 握手没谈成、磁盘写满）本来就该去日志里看，屏幕上多编一个字都是猜。
     */
    private static String readable(Throwable failed) {
        String raw = describe(failed);
        // 这几句都来自 Rust 侧 {@code Display} 的固定措辞（inbox::Error 的五个变体），
        // 是这条链路上**唯一**稳定的锚点：异常类型一路被 JNI 抹平成 IOException。
        if (raw.contains("the archive could not be read")) {
            return "对面发来的文件包读不出来，让他再发一次";
        }
        if (raw.contains("the inbox could not be written")) {
            return "文件没能写到存储上，检查空间够不够";
        }
        if (raw.contains("exceeds the inbox budget of")) {
            return "一次传的超过了单次接收上限，没有存下";
        }
        if (raw.contains("no free name for")) {
            return "同名文件太多，另起一个名字也存不下";
        }
        if (raw.contains("resolves outside the inbox")) {
            return "这一份的文件名不安全，没有存下";
        }
        return "连接在中途出了问题，这一份没有存下";
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
     * 一条连接上「对端开口说了什么、隔了多久」，只记一次。
     *
     * <p># 为什么要在流上加这一层
     *
     * <p>库是阻塞地读请求的：读不到就一声不响。于是日志里只剩一行「握手成功」和很久之后的一行
     * {@code offer}，中间那段空白有两种完全相反的解释——对端没开口，或者我们没读——而两者在
     * 现有日志里长得一模一样。2026-11-19 15:35:03 那次就是这样：握手 289 毫秒完成，之后二十三秒
     * 什么都没有，然后第二次握手、立刻来 {@code offer}。要修这条路，先得知道第一个字节是什么
     * 时候到的、第一行说的是 {@code /Discover} 还是 {@code /Ask}。
     *
     * <p># 只看，不碰
     *
     * <p>读到的字节原样往后传，只是顺手抄一份：不改长度、不改时机、不提前读。一旦它开始参与，
     * 它就不再是证据，而是另一个可能出错的地方。
     */
    private static final class Spoken extends FilterInputStream {
        /** 一行请求最多留这么长，够看到方法、路径和版本。 */
        private static final int CAP = 120;

        private final String who;
        /** 握手完成、交给库的那一刻。等的是「它什么时候开口」，不是「它什么时候连上来」。 */
        private final long readyAt;

        private final ByteArrayOutputStream head = new ByteArrayOutputStream(CAP);

        /** 已经说过那一行了。一条连接只说第一次，后续是请求体。 */
        private boolean said;

        Spoken(InputStream raw, String who, long readyAt) {
            super(raw);
            this.who = who;
            this.readyAt = readyAt;
        }

        /** 单字节读那条路要用的一个格子，省掉每字节一次分配。 */
        private final byte[] single = new byte[1];

        @Override
        public int read() throws IOException {
            int one = super.read();
            if (one >= 0) {
                single[0] = (byte) one;
                keep(single, 0, 1);
            }
            return one;
        }

        @Override
        public int read(byte[] into, int offset, int length) throws IOException {
            int got = super.read(into, offset, length);
            if (got > 0) {
                keep(into, offset, got);
            }
            return got;
        }

        private void keep(byte[] from, int offset, int length) {
            int room = CAP - head.size();
            if (room > 0) {
                head.write(from, offset, Math.min(length, room));
                tell();
            }
        }

        /** 攒到一整行（或者攒满）就说一句；{@code \r} 与 {@code \n} 都算行尾。 */
        private void tell() {
            if (said) {
                return;
            }
            byte[] seen = head.toByteArray();
            int end = 0;
            while (end < seen.length && seen[end] != '\r' && seen[end] != '\n') {
                end++;
            }
            if (end == seen.length && seen.length < CAP) {
                return;
            }
            said = true;
            Log.i(Listener.TAG, "first line from " + who + " after "
                    + (System.currentTimeMillis() - readyAt) + " ms: "
                    + new String(seen, 0, end, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * 把库里报的字节数变成至多每秒五条的状态更新。
     *
     * <p># 为什么投递不在读字节的那条线程上
     *
     * <p>{@link Native.Landing#landed} 是从 {@code inbox} 的写循环里调过来的，也就是在**正
     * 读着 socket 的那条线程**上。这里原来是就地投递：节流到每 200 ms 一条，然后直接
     * {@code attempt.progress}——一次 {@code notify} 到 SystemUI，外加一次
     * {@code getNotificationChannel}，全都压在读线程里。
     *
     * <p>节流压住的是**频率**，压不住**阻塞**：只要单次投递本身要几百毫秒（这台机器上那条
     * binder 路上还压着一个 Xposed 模块），200 ms 这个间隔就永远满足，于是每一条都放行、
     * 每一条都堵在读线程上。对端看到的是一条不读数据的连接，收到的字节就只能一格格往前挪
     * ——2026-11-19 21:17 那次是 1.2 秒恰好 128 KiB（约 100 KiB/s），而同一台 iPhone、同一条
     * {@code awdltun0} 上发送方向是 2.5 MB/s，差 25 倍。
     *
     * <p>所以投递搬到自己的线程上：{@code landed} 只做两件事——记下数字、把信箱点亮。读线程
     * 不再等 UI，线也就不会因为 UI 忙而停。数字仍然是最新的：信箱只有一格，投递线程每次取
     * 的都是它醒来那一刻的值，中间被覆盖掉的那些本来就没人看得见。
     *
     * <p># 为什么必须停得下来
     *
     * <p>进度和收尾是同一张卡上的两句话，先后顺序不能乱：一条还在飞的进度落在「接收完成」
     * 之后，屏幕上就是一张停在 97%、还写着正在接收、再也不会动的卡。所以 {@link #close} 是
     * 阻塞的——它等到投递线程真的退出才返回，调用点在 {@code serveWatched} 返回之后、
     * {@code delivered} 之前；{@code ReceiveHost} 的 {@code settled} 是第二道闸。
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

        /**
         * 单格信箱：读线程放，投递线程取。见类注释。
         *
         * <p>读线程只在这一把锁里停几微秒——投递线程画卡时是**在锁外**的（它取完数字就
         * 出去），所以 UI 慢多久都不会变成读线程的等待。
         */
        private final Object slot = new Object();

        /** 有新的数字没画过。由 {@link #slot} 守护。 */
        private boolean pending;

        /** 已经收工，投递线程该退出了。由 {@link #slot} 守护。 */
        private boolean closed;

        /** 对端声明的总数；0 表示它什么都没说。 */
        private volatile long total;

        /** 投出去几条、一共花了多少毫秒、最坏一条多少毫秒。只给日志。 */
        private long posts;
        private long postMillis;
        private long worstMillis;

        /** 上面那笔账报过一次没有。由 {@link #slot} 守护。 */
        private boolean tallied;

        /**
         * 最后一次报上来的已落地字节数。
         *
         * <p>只为了分辨一件事：这条连接断的时候，**有没有一个字节真的走过**。零字节断开是
         * 「对面还没开始/本机还没来得及开始」，走了几兆才断开是「传到一半被停下」——这两种
         * 在人那边要做的事完全不同，而异常本身只有一个 errno。
         *
         * <p>写在节流判断之前而不是之后：被节流掉的那几次恰恰是最后几次，漏记的话，一个在
         * 两百毫秒内断掉的传输会被读成「一个字节都没走」。
         */
        private volatile long done;

        /**
         * 最后一次有字节落地的时刻（{@link System#nanoTime}）；0 表示一个字节都没走过。
         *
         * <p>存在的理由只有一个：连接断掉的那一刻，{@code Socket closed} 这句话分不出「对面
         * 传到一半收手」和「对面早就停了、几秒后才断」。差的就是这一段时间——断之前线安静了
         * 五十毫秒是中止，安静了四秒是**停住**，而停住要找的是别的东西（见 {@code serve} 的
         * catch 里那一行）。
         */
        private volatile long lastAt;

        /** 画卡的那条线程。一条连接一条，随 {@link #close} 退出。 */
        private final Thread poster;

        Meter(ReceiveHost.Attempt attempt) {
            this.attempt = attempt;
            this.poster = new Thread(this::post, "opairdrop-post");
            this.poster.setDaemon(true);
            this.poster.start();
        }

        @Override
        public void landed(long done, long total) {
            this.done = done;
            this.total = total;
            // 记在投递之前：被节流掉的那几次恰恰是最后几次，漏记的话，一个在两百毫秒内断掉
            // 的传输会被读成「一个字节都没走过」。
            this.lastAt = System.nanoTime();
            synchronized (slot) {
                pending = true;
                slot.notifyAll();
            }
        }

        /**
         * 投递线程：有新数字就画一张，画完歇够 {@link #INTERVAL_MS}。
         *
         * <p>歇的那一段用「截止时刻」而不是 {@code wait(200)}：{@link #landed} 每来一块就
         * {@code notifyAll} 一次，按间隔睡会被提前叫醒，节流等于没有。提前醒只重算剩下的时间。
         */
        private void post() {
            while (true) {
                synchronized (slot) {
                    while (!pending && !closed) {
                        try {
                            slot.wait();
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                    if (closed) {
                        return;
                    }
                    pending = false;
                }
                // 锁外画卡：UI 慢多久都只慢这一条线程，读线程不受影响。
                long before = System.nanoTime();
                attempt.progress(done, total);
                long took = (System.nanoTime() - before) / 1_000_000L;
                // 这三个数只有这一条线程写，读的是 close()，靠 join 建立先后。
                posts++;
                postMillis += took;
                if (took > worstMillis) {
                    worstMillis = took;
                }
                long next = System.nanoTime() + INTERVAL_MS * 1_000_000L;
                synchronized (slot) {
                    while (!closed) {
                        long left = (next - System.nanoTime()) / 1_000_000L;
                        if (left <= 0) {
                            break;
                        }
                        try {
                            slot.wait(left);
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                    if (closed) {
                        return;
                    }
                }
            }
        }

        /**
         * 收工：等投递线程退出，返回之后不会再有进度上卡。
         *
         * <p>幂等，而且会从三条路进来（收完、断开、{@code finally} 兜底）——只有第一次做事。
         * 它阻塞到投递线程真的退出为止，这是「收尾那句话不会被迟到的进度盖掉」的唯一保证。
         */
        void close() {
            synchronized (slot) {
                closed = true;
                slot.notifyAll();
            }
            poster.interrupt();
            try {
                poster.join(2_000);
            } catch (InterruptedException interrupted) {
                // 正在关掉接收端。把中断还回去，让上层知道它该退出。
                Thread.currentThread().interrupt();
            }
            boolean say = false;
            synchronized (slot) {
                if (!tallied && posts > 0) {
                    tallied = true;
                    say = true;
                }
            }
            if (say) {
                // 这笔账回答的是「读线程是不是在等 UI」：总时间占传输时长一大半、最坏一次
                // 几百毫秒，那就是。数字不该出现在卡上，只该出现在这里。
                Log.i(TAG, "progress posts: " + posts + " in " + postMillis
                        + " ms, worst " + worstMillis + " ms");
            }
        }

        /** 有没有字节真的落地过。见 {@link #done}。 */
        long bytes() {
            return done;
        }

        /** 线安静了多久，毫秒；一个字节都还没走过时是 -1。见 {@link #lastAt}。 */
        long silentMillis() {
            long since = lastAt;
            return since == 0 ? -1 : (System.nanoTime() - since) / 1_000_000L;
        }

        /** 同一件事，说成日志里的半句话。 */
        String quiet() {
            long silent = silentMillis();
            return silent < 0 ? "no byte ever landed" : "last byte " + silent + " ms before the break";
        }

        /** 有结果了。 */
        void finished(String text) {
            // 收完那句话由流体云的卡说（带【打开文件】【完成】）。这里不再另发一条普通通知，
            // 否则一次接收会在通知栏和胶囊里各留一条同样的话，而其中一条点不动。
        }
    }

    /**
     * 线安静下来的时候，把那条读字节的线程在哪儿打出来。
     *
     * <p># 为什么要一条线程干这件事
     *
     * <p>2026-11-19 21:01–21:04 那六次中断，卡片上的数停在 64–576 KiB 就不动了，过一会儿
     * 连接才断。数不动只有一个直接原因：{@link Meter#landed} 没被调用，也就是没有字节落地。
     * 但「没有字节落地」有两种完全不同的情形，而它们在日志上长得一模一样：
     *
     * <p>一种是**对面不发了**——那边停了、AWDL 那头在重传、或者对面在等我们。这时这条线程
     * 正躺在 {@code read} 里，栈上只有一帧 socketRead。
     *
     * <p>另一种是**我们自己没在读**——写盘、算摘要、或者别的什么同步调用把这条线程占住了。
     * 2026-11-19 之前最大的那一处是进度投递（每条进度都要走 binder 到 SystemUI，而这条路上
     * 还压着一个 Xposed 模块），它已经搬到 {@link Meter} 自己的线程上；如果栈上仍然出现
     * binder 帧，说明还有一处没搬完。对端看到的是一条不读数据的连接——那和我们「收得慢」是
     * 两件事，要改的地方也不同。
     *
     * <p>两种只在栈上分得开，所以线安静超过 {@link #LIMIT_MS} 就取一次栈，一条连接只取一次。
     */
    private static final class Stall implements Runnable {

        /**
         * 静默多久算「卡住」。
         *
         * <p>比节流的间隔（200 ms）大一个数量级，比人开始怀疑（三秒左右）小一点：慢传输里
         * 合法的空档也有一两百毫秒，而任何超过两秒半的空档，人都已经在看那块屏幕了。
         */
        private static final long LIMIT_MS = 2_500;

        private final Meter meter;
        private final String who;
        /** 要取栈的那条线程：就是调 {@code serveWatched} 的那条。 */
        private final Thread reader;
        private volatile boolean halted;

        Stall(Meter meter, String who) {
            this.meter = meter;
            this.who = who;
            this.reader = Thread.currentThread();
        }

        void start() {
            Thread watcher = new Thread(this, "opairdrop-stall");
            watcher.setDaemon(true);
            watcher.start();
        }

        /** 这一段结束了——收完了、断了、或者根本没开始。可以重复调。 */
        void stop() {
            halted = true;
        }

        @Override
        public void run() {
            while (!halted) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException done) {
                    return;
                }
                long silent = meter.silentMillis();
                // 还没开始传的时候不报：那会儿的「安静」只是人在看那张邀请卡。
                if (silent < LIMIT_MS || meter.bytes() == 0) {
                    continue;
                }
                Log.w(TAG, "no byte for " + silent + " ms at " + bytes(meter.bytes())
                        + " from " + who + "; the reading thread was here:" + stackOf(reader));
                return;
            }
        }
    }

    /** 一条线程此刻的栈，一行一帧，最里面那帧在前。 */
    private static String stackOf(Thread thread) {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : thread.getStackTrace()) {
            out.append("\n    at ").append(frame);
        }
        return out.toString();
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
