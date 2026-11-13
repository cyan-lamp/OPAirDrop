package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import com.oshare.airdrop.core.AirDropLink;
import com.oshare.airdrop.core.AirDropPeer;
import com.oshare.airdrop.core.AirDropProtocol;
import com.oshare.airdrop.core.PeerNames;
import com.oshare.airdrop.core.Trace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the Mosey-free AirDrop endpoint for the lifetime of the app.
 *
 * <p>The service exists because the endpoint must outlive the activity: a transfer
 * arrives over TLS while the user is looking at the share sheet, not at our window.
 *
 * <p>It runs in the <em>foreground</em> because a started service does not actually
 * survive here. On this build closing the activity is enough for the platform to log
 * {@code Stopping service due to app idle} and reap it about ninety seconds later,
 * which {@code START_STICKY} cannot prevent — the process is being trimmed, not killed.
 * A phone sitting on a desk is precisely the situation the receiver is for, so the
 * notification is the price of the feature, not a nicety.
 *
 * <p>All work happens on one worker thread: RSA generation on first launch is slow
 * enough to jank the UI, and {@link AirDropLink} is designed to be started once and
 * then left alone. The callbacks stay on that thread too, so UI code has to hop.
 */
public final class AirDropRuntime extends Service {
    public static final String TAG = "OPAirDrop";
    private static final String ACTION_START = "com.oshare.airdrop.START_AIRDROP";

    private static final String CHANNEL = "receiver";
    private static final int NOTIFICATION_ID = 0x0A1D;

    /**
     * How long to wait before trying again after a failed start. The usual failure is
     * {@code wonder0} not existing yet: the module brings the link up at boot, which can
     * land after the app has already been opened. Retrying makes the receiver heal itself
     * instead of staying dead until the next launch.
     */
    private static final long RETRY_MILLIS = 15_000L;

    /**
     * How long the node gets to create its interface. Opening a tun, addressing it and
     * writing the route is a handful of syscalls once the JVM is up, but the JVM itself
     * has to start first; the relay harness allows ten seconds for the same step.
     */
    private static final long START_TIMEOUT_MILLIS = 10_000L;

    /** How often the link under the endpoint is re-checked once it is up. */
    private static final long WATCH_MILLIS = 1_000L;

    /**
     * How often the node's tunnel is looked for while it is being created.
     *
     * <p>The node is a separate {@code app_process}, so its tun appears when that JVM has
     * started -- not when this thread asks. Every poll therefore adds its own remainder to
     * the wait, and at a tenth of a second that remainder was up to 100 ms of a path the
     * user is watching: from tapping share to the device list filling in. Measured end to
     * end, this step and its twin in {@code SendActivity} were most of the "finding the
     * device is slow" that was not the retry gap. Checking at display cadence costs a few
     * extra syscalls and is still bounded by {@link #START_TIMEOUT_MILLIS}.
     */
    private static final long INTERFACE_STEP_MILLIS = 20L;

    private static volatile AirDropLink link;
    private static volatile String lastError;
    private static volatile List<File> lastReceived = Collections.emptyList();
    private static volatile String lastSender = "";

    /**
     * The one thread that files finished transfers into MediaStore.
     *
     * <p>Deliberately not the thread that just wrote the bytes. {@code /Upload} is not
     * answered until the handler returns, so anything slow in {@link Listener#onReceived}
     * is time the sender's progress bar spends at 100% with nothing on either screen to
     * explain it -- and publishing is a second pass over the same bytes, which for a video
     * is seconds. One thread rather than a pool because this is I/O against one volume and
     * the order the files arrived in is the order they should appear in the gallery.
     *
     * <p>Static and daemon: it holds no state worth tearing down, and the app process can
     * outlive several service lifetimes.
     */
    private static final ExecutorService PUBLISH = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "airdrop-publish");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Where the receive conversation is written down.
     *
     * <p>The core package reports what it sent and what came back, and it does that
     * through {@link Trace} rather than through Android's log, so somebody in this
     * process has to own the destination. It cannot be the send screen: a transfer can
     * arrive while no screen is open at all, which is the ordinary case for a receiver
     * and the only case where the user has nothing else to look at.
     *
     * <p>A file as well as logcat, for the reason the send side has one: the relay prints
     * its own report into the same ring buffer, and a busy AWDL link evicts everything
     * older than a few seconds -- which is precisely the seconds a failed transfer lived
     * in. Truncated once per process, because a log that accumulates across launches
     * cannot be read in one screen.
     */
    private static final Trace.Sink SINK = text -> {
        Log.i(TAG, text);
        appendProbe(text);
    };

    private static volatile File probeFile;
    private static boolean probeInstalled;

    private Thread worker;
    private volatile boolean stopped;

    /** Whether this failure episode has already been rebuilt without the back-off. */
    private boolean retried;

    /** Starts the endpoint if it is not running; safe to call from any activity. */
    public static void start(Context context) {
        Intent intent = new Intent(context, AirDropRuntime.class).setAction(ACTION_START);
        try {
            // Foreground services must be requested through this call, otherwise the
            // subsequent startForeground() is rejected on recent releases.
            context.startForegroundService(intent);
        } catch (RuntimeException refused) {
            // Background-start restrictions. Nothing to do but record it: the endpoint
            // stays down until the app is opened again.
            lastError = "startForegroundService: " + refused.getMessage();
            Log.w(TAG, "cannot start the receiver now", refused);
        }
    }

    /** The running endpoint, or {@code null} while it is still coming up. */
    public static AirDropLink link() {
        return link;
    }

    /** Last failure reported by mDNS or TLS, for the diagnostics pane. */
    public static String lastError() {
        return lastError;
    }

    /**
     * Files from the most recent completed transfer, newest last.
     *
     * <p>The paths are the inbox copies, which are removed once they have been published
     * to MediaStore -- so a caller that wants to show a transfer after the fact should use
     * the folder the notification named, not these paths. They stay useful for the seconds
     * in which a transfer is still landing, which is the only time anything reads them.
     */
    public static List<File> lastReceived() {
        return lastReceived;
    }

    /** Name of the device that sent {@link #lastReceived()}, as it introduced itself. */
    public static String lastSender() {
        return lastSender;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        installProbe();
        // Before the endpoint answers anything: a name learned in an earlier session has
        // to be in the map by the time the first answer comes back, because that is what
        // the send screen reads to label a row.
        PeerNames.attach(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Before anything slow: the platform allows about five seconds between
        // startForegroundService() and this call, and the first launch has an RSA key
        // to generate.
        enterForeground(getString(R.string.receiver_starting));
        ensureStarted();
        // Restart after a kill: the endpoint is the whole point of the service, and
        // the identity is on disk, so coming back costs a few milliseconds.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        AirDropLink current = link;
        link = null;
        if (current != null) current.close();
        // The node holds a tun and a raw socket on the radio; leaving it behind would
        // keep transmitting AWDL frames for a service nobody is listening to.
        AwdlNode.stop();
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
        super.onDestroy();
    }

    private synchronized void ensureStarted() {
        if (worker != null || link != null) return;
        Thread thread = new Thread(this::run, "airdrop-runtime");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void run() {
        String name = deviceName();
        File identityDir = new File(getFilesDir(), "airdrop");
        File inboxDir = inboxDirectory();

        while (!stopped) {
            // The endpoint is useless without the link, and the link is a different
            // process, so it is checked first: an interface that never appeared is a
            // clearer failure than a socket that could not bind to it.
            if (!AwdlNode.start(this) || !awaitInterface(AwdlNode.TUN)) {
                lastError = AwdlNode.lastError().isEmpty()
                    ? "the AWDL node did not create " + AwdlNode.TUN
                    : AwdlNode.lastError();
                Log.w(TAG, "no link: " + lastError);
                // A node that is alive but never produced its tunnel is the one case the
                // retry cannot recover from on its own: {@code AwdlNode.start()} answers
                // "already running" for it, so the next pass would wait out the whole
                // {@link #START_TIMEOUT_MILLIS} again for an interface that is not coming,
                // and again after that. Ending it here makes every retry a real one.
                AwdlNode.stop();
                showStatus(getString(R.string.receiver_failed, lastError));
                if (!waitBeforeRetry()) return;
                continue;
            }

            AirDropLink endpoint = new AirDropLink(identityDir, inboxDir, name, Build.MODEL,
                AwdlNode.TUN, new Listener());
            try {
                endpoint.start();
                link = endpoint;
                retried = false;
                lastError = null;
                Log.i(TAG, "airdrop link up as \"" + name + "\" (" + endpoint.host() + ") tls=:"
                    + endpoint.serverPort() + " mdns=:" + endpoint.mdnsPort()
                    + " inbox=" + inboxDir);
                showStatus(getString(R.string.receiver_ready, name));

                // The endpoint lives only as long as the link under it. A node that dies
                // takes the tun with it, and every socket bound to that interface then
                // goes quiet without an error anyone would see -- so the teardown is
                // noticed here and turned into a rebuild instead of a spinner.
                if (!watchLink()) return;
                lastError = "AWDL link lost";
                Log.w(TAG, "link lost, rebuilding the node");
                endpoint.close();
                link = null;
                AwdlNode.stop();
                showStatus(getString(R.string.receiver_failed, lastError));
                if (!waitBeforeRetry()) return;
            } catch (Exception failed) {
                lastError = failed instanceof IOException
                    ? String.valueOf(failed.getMessage())
                    : failed.toString();
                Log.w(TAG, "airdrop link failed: " + failed);
                endpoint.close();
                // Whatever the node is doing, it is not serving this endpoint: either its
                // process is gone or its tunnel is. Leaving it in place would make the
                // retry below wait on {@link #START_TIMEOUT_MILLIS} for an interface that
                // is never coming back, so it goes first and the retry starts clean.
                AwdlNode.stop();
                showStatus(getString(R.string.receiver_failed, lastError));
                if (!waitBeforeRetry()) return;
            }
        }
    }

    /**
     * Holds the endpoint open while the link is up; {@code false} means the service is
     * shutting down, {@code true} means the link went away and the loop should rebuild.
     */
    private boolean watchLink() {
        while (!stopped) {
            if (!AwdlNode.running() || !hasInterface(AwdlNode.TUN)) return true;
            try {
                Thread.sleep(WATCH_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Waits for the node to create its interface; nothing is worth doing before it exists. */
    private boolean awaitInterface(String interfaceName) {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (hasInterface(interfaceName)) return true;
            if (!AwdlNode.running()) return false;
            try {
                Thread.sleep(INTERFACE_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return hasInterface(interfaceName);
    }

    private static boolean hasInterface(String interfaceName) {
        try {
            return NetworkInterface.getByName(interfaceName) != null;
        } catch (SocketException unavailable) {
            return false;
        }
    }

    /** Sleeps between attempts; {@code false} means the service is going away. */
    private boolean pause() {
        try {
            Thread.sleep(RETRY_MILLIS);
            return !stopped;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The wait between a failure and the next attempt: none for the first one.
     *
     * <p>The tun existing is not the same as the tun staying, and the gap between the two
     * is small enough to lose. Measured on the device, in the one run out of eight where
     * it went wrong: {@code node started} at 52.811, the interface was there long enough
     * for the runtime to see it, and by 53.088 the endpoint could no longer bind to it --
     * the node had died in the twenty milliseconds after it created its tunnel. Backing
     * off for the full {@link #RETRY_MILLIS} there turns a lost race into fifteen seconds
     * of empty device list on the share screen, which is the exact complaint this whole
     * chase started from: a rebuild costs a quarter of a second, so it happens at once.
     *
     * <p>Only the first failure gets that treatment. A second one in a row is not a race
     * any more -- {@code wonder0} is missing, or the radio refused -- and hammering
     * {@code su} every 250 ms would be noise on the log and on the battery, so the
     * back-off applies from then on and {@link #retried} is cleared by a link that comes
     * up.
     */
    private boolean waitBeforeRetry() {
        if (!retried) {
            retried = true;
            Log.i(TAG, "rebuilding at once, then backing off if it fails again");
            return !stopped;
        }
        return pause();
    }

    /**
     * Where transfers land: app-specific external storage, so no permission is needed
     * and the files are still reachable over MTP. Public Download needs MediaStore and
     * comes with the settings screen in M4.
     */
    private File inboxDirectory() {
        File external = getExternalFilesDir("incoming");
        return external != null ? external : new File(getFilesDir(), "incoming");
    }

    /**
     * Points the core's trace output at this service, once per process.
     *
     * <p>The file sits beside the inbox rather than inside it: it is a log of what the
     * endpoint did, not something a transfer produced, and the inbox's {@code Budgeted}
     * accounting would count its bytes against the next upload's quota if it lived there.
     *
     * <p>Truncation happens here and only here. Every other writer opens the file for
     * append, so the log cannot be cleared mid-transfer by a second sink being installed.
     */
    private void installProbe() {
        if (probeInstalled) return;
        // Inside the app's private directory rather than the external one, because the
        // external copy is not readable from a shell: SELinux denies `run-as cat` under
        // Android/data even though the directory itself lists, so the one file that
        // survives a logcat eviction was the one nobody could open. The private path
        // reads back with `run-as com.oshare.airdrop cat files/receive-probe.log`.
        File directory = getFilesDir();
        File file = new File(directory, "receive-probe.log");
        resetProbe(file);
        probeFile = file;
        probeInstalled = true;
        Trace.addSink(SINK);
        Log.i(TAG, "receive probe -> " + file);
    }

    private static void resetProbe(File file) {
        try {
            new FileOutputStream(file, false).close();
        } catch (IOException | RuntimeException ignored) {
            // An unwritable directory costs the log, not the receiver.
        }
    }

    private static void appendProbe(String text) {
        File file = probeFile;
        if (file == null) return;
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((System.currentTimeMillis() + " " + text + "\n").getBytes("UTF-8"));
        } catch (IOException | RuntimeException ignored) {
            // Same reasoning as above: a trace line is never worth an exception on the
            // thread that is holding the transfer together.
        }
    }

    /**
     * The name peers see. The settings screen stores it in {@code Settings.Global}
     * (shared with the hooked OShare UI), and the model name is the fallback.
     */
    private String deviceName() {
        String stored = null;
        try {
            stored = Settings.Global.getString(getContentResolver(), AirDropName.SETTING);
        } catch (RuntimeException ignored) {
            // Reading global settings can fail on locked-down builds; the model is fine.
        }
        String name = AirDropName.resolve(stored, null, Build.MODEL);
        return name == null || name.trim().isEmpty() ? "OPAirDrop" : name.trim();
    }

    private void enterForeground(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
            getString(R.string.receiver_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.receiver_channel_description));
        manager.createNotificationChannel(channel);

        Notification notification = build(text);
        try {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (RuntimeException refused) {
            // Refused when the app is not allowed to start a foreground service in the
            // current state. The worker still runs; it just may be reaped when idle.
            lastError = "startForeground: " + refused.getMessage();
            Log.w(TAG, "cannot enter the foreground", refused);
        }
    }

    /**
     * Refreshes the notification text; the first call happens in {@link #enterForeground}.
     *
     * <p>Named {@code showStatus} and not {@code notify} on purpose: this class inherits
     * {@link Object#notify()}, so an inner class calling {@code notify(text)} would resolve
     * to the monitor method and fail to compile.
     */
    private void showStatus(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            manager.notify(NOTIFICATION_ID, build(text));
        } catch (RuntimeException ignored) {
            // A notification that cannot be updated is not worth failing a transfer over.
        }
    }

    private Notification build(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_airdrop)
            .setContentTitle(getString(R.string.receiver_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build();
    }

    private final class Listener implements AirDropLink.Listener {
        @Override
        public void onStarted(com.oshare.airdrop.core.Identity identity, String host,
                              int serverPort, int mdnsPort) {
            Log.i(TAG, "identity " + identity.certHash().substring(0, 12) + "… host=" + host);
        }

        @Override
        public void onPeer(AirDropPeer peer) {
            Log.i(TAG, "peer " + peer);
        }

        @Override
        public void onAsk(AirDropProtocol.Ask ask) {
            lastSender = ask.senderName;
            Log.i(TAG, "offer from " + ask.senderName + ": " + ask.summary());
            // The offer is the moment the user would look at the phone, so the
            // notification stops saying "ready" and starts naming the sender.
            showStatus(getString(R.string.receiver_offered, ask.senderName));
        }

        @Override
        public void onReceived(List<File> files) {
            lastReceived = Collections.unmodifiableList(new ArrayList<>(files));
            for (File file : files) Log.i(TAG, "received " + file.getAbsolutePath() + " (" + file.length() + " B)");
            // Only hand the batch off here. Doing the copy inline would hold the /Upload
            // response until it finished, which the sender sees as a transfer that reached
            // 100% and then hung.
            PUBLISH.execute(() -> publish(new ArrayList<>(files)));
        }

        /**
         * Copies a finished transfer out of the inbox and into MediaStore.
         *
         * <p>The inbox is app-specific external storage: no gallery indexes it and the only
         * way in is a path under {@code Android/data} that nobody browses. A photo that
         * stays there has been received and cannot be found, which from the other side of
         * the screen is the same thing as a transfer that never arrived.
         *
         * <p>Failure is not an error the user has to act on. The files are still in the
         * inbox -- reachable over MTP, and this message says so -- and the next transfer
         * does not care that this one could not be filed.
         */
        private void publish(List<File> files) {
            List<ReceiveFiles.Published> saved;
            try {
                saved = ReceiveFiles.publishInbox(AirDropRuntime.this, files);
            } catch (Exception failed) {
                Log.w(TAG, "could not publish " + files.size() + " received file(s)", failed);
                Trace.write("* publish failed (" + failed.getMessage() + "), kept in " + inboxDirectory());
                showStatus(getString(R.string.receiver_kept, files.size()));
                return;
            }
            for (ReceiveFiles.Published one : saved) Trace.write("* filed " + one);
            // An archive of nothing but directories files as nothing, and "saved 0 files"
            // would be a worse sentence than "kept in the app directory".
            if (saved.isEmpty()) {
                showStatus(getString(R.string.receiver_kept, files.size()));
                return;
            }
            showStatus(getString(R.string.receiver_saved, saved.size(), ReceiveFiles.describe(saved)));
        }

        @Override
        public void onError(String message, Throwable error) {
            lastError = message;
            Log.w(TAG, "airdrop: " + message, error);
        }
    }
}
