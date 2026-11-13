package com.oshare.airdrop;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import com.oshare.airdrop.Native;
import com.oshare.airdrop.Report;
import com.oshare.airdrop.SettingsScreen;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;

/**
 * The listening socket, and the loop that hands each connection to the library.
 *
 * <p># Why the socket is here and not in Rust
 *
 * <p>On Android the certificate, the interface and the permission to bind all belong to the
 * app, not to the protocol. {@link Native#serve} therefore takes a pair of streams rather
 * than a port, and this class is the part that owns the port: it listens, accepts, hands the
 * streams over, and closes. That split is also what lets the same library serve a TLS stream
 * later without changing a line of Rust.
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
     * The port AirDrop answers on.
     *
     * <p>Fixed by the protocol's discovery record: a peer that finds this device is told the
     * port in that record, and 8770 is what every implementation advertises.
     */
    static final int PORT = 8770;

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

    /** The loop: bind, accept, serve, until told to stop or the socket breaks. */
    private void loop() {
        ServerSocket bound = null;
        try {
            bound = new ServerSocket();
            // Without this a stopped listener cannot be restarted for a minute or two: the
            // port stays in TIME_WAIT and the bind fails with "address already in use".
            // Restarting is something a person does immediately after stopping.
            bound.setReuseAddress(true);
            bound.bind(new InetSocketAddress(PORT));
        } catch (IOException refused) {
            closeQuietly(bound);
            end();
            setState("无法监听 " + PORT + "：" + refused.getMessage(), true);
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
                    serve(connection);
                } catch (IOException gone) {
                    setState("连接中断：" + describe(gone), true);
                }
            }
        } finally {
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

    /** Hands one connection to the library and puts what it stored on the page. */
    private void serve(Socket peer) {
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
        try {
            // 有人要发东西过来时问一次。问的是人，所以这条线程会停在这里等——它本来也只
            // 服务这一个连接，等的时候没有别的事该做。
            byte[] frame = Native.serveDecided(
                    directory,
                    screen.stored(),
                    Build.MODEL,
                    peer.getInputStream(),
                    peer.getOutputStream(),
                    summary -> Confirmation.ask(context, summary));
            // A discovery probe and an empty share both come back empty; neither is worth a
            // line on the page, and leaving the previous transfer up is the truthful state
            // of "nothing new arrived".
            Report report = Report.of(frame);
            if (!report.empty()) {
                last = summarize(report, directory);
                setState("正在监听 " + PORT + "，等待附近设备", false);
            }
        } catch (IOException | RuntimeException refused) {
            setState("接收失败：" + describe(refused), true);
        }
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

    /** A byte count in the largest unit that keeps it readable. */
    private static String bytes(long total) {
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
