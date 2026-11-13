package com.oshare.airdrop;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oshare.airdrop.core.AirDropBrowser;
import com.oshare.airdrop.core.AirDropClient;
import com.oshare.airdrop.core.AirDropLink;
import com.oshare.airdrop.core.AirDropPeer;
import com.oshare.airdrop.core.BleWake;
import com.oshare.airdrop.core.Identity;
import com.oshare.airdrop.core.IdentityStore;
import com.oshare.airdrop.core.Outgoing;
import com.oshare.airdrop.core.PeerNames;
import com.oshare.airdrop.core.Trace;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The sending half: what was shared to us, who is nearby, and how far along the transfer is.
 *
 * <p>This activity is where the app is entered when something is shared to it, so it has to
 * work without {@link MainActivity} ever having run. That means it owns three jobs the
 * receiving service does not: reading the incoming intent into files, advertising over
 * Bluetooth, and browsing for whoever answers.
 *
 * <p>The advertisement is started before the first query and stopped once a peer is chosen.
 * It is a trigger, not a transport: a phone in Everyone mode stays dark on port 8770 until
 * it hears one, so browsing without it finds nothing and reports no error.
 *
 * <p>Scanning repeats while the screen is open. A peer that was asleep when the first window
 * opened answers the next one, and a user who is still unlocking the other phone should not
 * have to find a refresh button. Answers are merged across rounds rather than replacing the
 * list, so a row never blinks out between two packets — a PTR-only answer that arrived
 * after a complete one would otherwise erase the address a tap needs.
 *
 * <p>Colours are duplicated from {@link MainActivity} instead of shared: its palette lives in
 * a private nested class, and widening that to serve one more screen would put the whole
 * visual language of the app behind a refactor of a three-thousand-line file. Nine constants
 * with a comment are the cheaper mistake.
 */
public final class SendActivity extends Activity {
    private static final int REQUEST_FILES = 0x5E4D;
    private static final int REQUEST_PERMISSIONS = 0x5E4E;

    /**
     * Pause between scan rounds: long enough not to flood the link, short enough to feel live.
     *
     * <p>It used to be 800 ms, and it was paid in the one place it hurt most. A round that
     * found nothing gave the screen an empty list until the window (2.5 s) <em>plus</em>
     * this gap had passed -- 3.3 s, which is the number users reported as "finding the
     * device is slow". The gap is only a breather between two sockets, and a quarter of a
     * second is a breather; the extra half second bought nothing and was added to the worst
     * case of every round.
     */
    private static final long SCAN_GAP_MS = 250L;

    /**
     * How often the scan chain looks up while a transfer owns the link. It does not browse.
     *
     * <p>A round is a 2.5 second window that sends three mDNS queries, and those are
     * ordinary AWDL frames: they leave through the same queue as the file's data frames.
     * The chain used to keep running during a transfer at {@code SCAN_GAP_MS * 4}, which
     * after the gap was shortened to 250 ms meant a round started every second against a
     * window of two and a half — over seventy per cent of the link's time spent asking for
     * peers while the list was hidden behind the progress view.
     *
     * <p>That is not free. It is the difference between the 3 MB/s this link reached
     * before and the slower transfers measured after, and it lands hardest on the one
     * moment the user is watching: the offer's handshake competes with queries that
     * nothing on screen is waiting for. So the chain still ticks -- it has exactly one
     * owner and letting it lapse is what once left the list frozen -- but a tick during a
     * transfer only re-arms itself.
     */
    private static final long SCAN_SENDING_MS = 1_200L;

    /**
     * The gap to use after a round that found nobody.
     *
     * <p>Zero, because that is what the number means here: {@code browse} already listened
     * for its full window, so waiting again only adds a hole. The hole is not harmless --
     * the socket is closed and a fresh one has to join the group again, and an answer that
     * arrives in that slice is lost until the next round, which is exactly the 300 ms to
     * 1.4 s spread measured for "first peer". Once anything has been found the screen is
     * updating on its own and the quarter-second breather is the polite thing to do.
     */
    private long scanGap() {
        return found.isEmpty() ? 0L : SCAN_GAP_MS;
    }

    /**
     * How long a refused offer waits after re-waking the peer before trying again.
     *
     * <p>A receiver that was only asleep reopens its answer port once the Bluetooth
     * trigger reaches it, and that took 2.3 s on the device this was measured on — from
     * the advertisement going out at 05:44:01.319 to the first accepted connection at
     * 05:44:03.653. Retrying earlier lands on the same closed port; this is long enough to
     * cover that, and a scan round finishes inside it, so the peer the retry uses is the
     * one the link has just reported rather than the snapshot that failed.
     */
    private static final long WAKE_MS = 2_400L;

    // The palette: dark() and light() from MainActivity, cut down to what this screen uses.
    private int bg, card, text, textDim, accent, divider, green, red, ripple;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /**
     * The offer runs here, and nowhere else.
     *
     * <p>{@link #io} is one thread that a browse round can hold for its whole window -
     * two and a half seconds - and the row tap used to queue behind it. The tap is the
     * one moment this screen is judged on: an offer parked behind a round that is still
     * asking about a list the user has already chosen from is a prompt that appears a
     * round late, with nothing in the log to say why because the delay happened before
     * the offer's first line was written.
     *
     * <p>Measured, not assumed: with the scan chain re-armed immediately after a round
     * that found something, {@code io} was busy for a round in every two and three
     * quarters, so the average tap waited over a second and the worst case waited the
     * whole window. A second thread costs one parked thread per open screen.
     */
    private final ExecutorService offer = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AirDropPeer> found = new ArrayList<>();

    // {gone} and {sending} are read from the browse thread now, not only from the main
    // one: the round ends on them, and a field the writer's thread never published can
    // leave that round listening to the end of its window while a transfer is already
    // queued behind it. Volatile is the whole fix; the reads are once per 250 ms slice.
    private volatile boolean gone;
    private volatile boolean sending;
    private boolean scanning;
    private boolean advertising;

    /** Logcat tag for the wire trace; kept separate from the app's other tags. */
    private static final String TRACE_TAG = "OPAirDrop";

    /*
     * The core package is deliberately free of Android so a transfer can be rehearsed
     * on a plain JVM, which is why the trace sink is installed here instead of imported
     * there. A static initialiser rather than onCreate: the class is loaded before any
     * transfer can start, and a transfer begun from a restored intent would otherwise
     * race the install and lose its first request.
     */
    static {
        // Logcat keeps only the last few seconds of a busy link, and the request whose
        // timing is in question happened before anyone started reading. The same lines
        // therefore go to the probe file when there is one, which is how "the bar stopped
        // here" gets an answer on the other side of it: connected, sndbuf, header out,
        // first byte out.
        // Added rather than installed: the receiver service keeps a sink of its own for
        // the transfers that arrive while no screen is open, and replacing it here would
        // silence exactly the half of the process this screen is not running.
        Trace.addSink(text -> {
            android.util.Log.i(TRACE_TAG, text);
            append("trace " + text);
        });
    }

    /**
     * Milestones of one transfer, kept in a file as well as in logcat.
     *
     * <p>Logcat is the wrong medium for the question this answers, twice over. The relay
     * prints its own report with {@code System.out}, which lands in the same ring buffer
     * and evicts everything older than a few seconds of a busy link; and the interval that
     * matters -- from the receiver tapping accept to the progress line starting to move --
     * is over long before anyone thinks to start reading. A file in the app's own external
     * directory survives both, and it is the only record that can say whether a bar that
     * sat still was a stalled socket or a stalled archive.
     */
    private static volatile File probeFile;

    private static void probe(String text) {
        android.util.Log.i(TRACE_TAG, text);
        append(text);
    }

    private static void append(String text) {
        File file = probeFile;
        if (file == null) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
            out.write((System.currentTimeMillis() + " " + text + "\n").getBytes("UTF-8"));
        } catch (IOException | RuntimeException ignored) {
            // A probe that cannot be written must never be the reason a send fails.
        }
    }

    /**
     * Marks the start of a session in the probe file.
     *
     * <p>This used to truncate, on the theory that {@code onCreate} means a fresh process
     * and therefore nothing worth keeping. It does not. A share can be handed to a process
     * that has been alive for half a minute and already served one transfer, and the
     * second screen's {@code onCreate} then deleted the first transfer's trace -- which is
     * how a run whose radio log showed fifteen megabytes leaving the device produced a
     * probe file containing nothing but an idle browse loop and one warm connection. The
     * measurement was not lost to a bug in the transfer; it was deleted by the next thing
     * the user did.
     *
     * <p>So sessions are appended and delimited. The truncation is kept only as a size
     * bound, because a file that grows without limit eventually costs more to pull than the
     * run it describes.
     */
    private static void resetProbe() {
        File file = probeFile;
        if (file == null) return;
        boolean keep = file.length() < PROBE_MAX_BYTES;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, keep)) {
            long now = System.currentTimeMillis();
            out.write((now + " ===== session (process up "
                    + android.os.SystemClock.elapsedRealtime() / 1000L + " s) =====\n")
                    .getBytes("UTF-8"));
        } catch (IOException | RuntimeException ignored) {
            // Same rule as append(): a probe must never be the reason a send fails.
        }
    }

    /** Size past which the probe starts over instead of growing. */
    private static final long PROBE_MAX_BYTES = 4L * 1024 * 1024;

    /**
     * Failed rounds tolerated before the list gives up.
     *
     * <p>The link is torn down and rebuilt whenever the AWDL session restarts, and a
     * round that lands in that gap fails for a reason that is already gone by the next
     * one. Stopping on the first failure turned a one-second rebuild into a screen that
     * said "failed" until the user left and came back.
     */
    private static final int SCAN_STRIKES = 3;

    /**
     * How long to wait for the endpoint's tun before scanning anyway, and how often to
     * look. The node is a separate {@code app_process} that has to be spawned through
     * {@code su} and then create the interface, and on this hardware that is a couple of
     * seconds — long enough that a round fired immediately always loses.
     */
    private static final long ENDPOINT_WAIT_MS = 20_000L;
    /**
     * How often that wait is re-checked. The link does not announce itself, so this is
     * also how long the browse sits idle after the tun is actually ready.
     *
     * <p>Measured on the device: the tun appeared 312 ms after the node was spawned, but
     * the endpoint was only reported 90 ms later -- and that 90 ms was this step, not
     * anything being built. It is the first term in "from tapping share to seeing the
     * phone", so it is polled at display cadence instead of at a quarter-second one: the
     * wait costs a few wakes and is bounded by {@link #ENDPOINT_WAIT_MS}.
     */
    private static final long ENDPOINT_STEP_MS = 25L;

    /** Consecutive failed rounds, cleared by the first round that answers. */
    private int strikes;

    /*
     * The discovery timeline: this window's start, and two one-shot flags so each
     * milestone is written once.
     *
     * "Finding a device takes three or four seconds" is a number nobody could act on
     * because it was never split up. Four things sit between the tap and a row: the
     * process starting, the share being read, the node's tun existing, and the first
     * mDNS answer coming back. Three of them are ours to shorten and one is the other
     * phone's, and without a clock on each there was no way to tell which was paying.
     * These lines are the clock; they cost one log write each and they stay, because
     * the next regression will need them too.
     */
    private long openedAt;
    private boolean endpointLogged, peerLogged;
    /** What the peer list currently shows, so an identical round does not rebuild it. */
    private String shownPeers = "";

    private AirDropBrowser browser;
    /**
     * Holds an idle TLS session to every device the list is showing.
     *
     * <p>The interval a user judges this screen by starts at the tap, and the first third of
     * it is a TCP handshake through the AWDL tunnel plus a TLS handshake on top — work that
     * does not depend on the files and can be finished while the row is merely visible. See
     * {@link PeerWarmup}.
     */
    private PeerWarmup warmup;
    private Identity identity;
    private List<Outgoing> files = new ArrayList<>();

    private LinearLayout fileList, peerList;
    private TextView filesSummary, scanStatus, progressLabel, progressValue, action;
    private View progressTrack, progressFill;
    private int progressPercent = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // Resolved before the first timeline line rather than after it: the file is the
        // only medium that outlives this process, and until it is set every write here
        // is dropped -- which is exactly the line that says how long the process took.
        File dir = getExternalFilesDir(null);
        probeFile = dir == null ? null : new File(dir, "send-probe.log");
        // Truncated here, while the process is young enough that nothing has been written
        // yet, instead of on the tap. Clearing it at the tap -- which is what this used to
        // do -- threw away the lines that say how long the list took to fill, because all
        // of them are written before there is anything to tap.
        resetProbe();
        openedAt = SystemClock.uptimeMillis();
        // The tap is earlier than this by however long the process took, and a cold
        // process is the normal case when the share sheet is the entry point. Measuring
        // from the process' own start keeps that half of the wait in the timeline
        // instead of hiding it before t=0.
        Trace.write("timeline: onCreate +" + (openedAt - Process.getStartUptimeMillis())
            + " ms into the process");
        boolean light = (getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO;
        palette(light);
        // Before the peer list is built: the names this device already knows are what
        // turn a row from "a rotated hex instance" into a phone somebody recognises, and
        // they come off disk, not off the air.
        PeerNames.attach(this);
        setContentView(buildUi());
        int bars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        android.view.WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) controller.setSystemBarsAppearance(light ? bars : 0, bars);

        warmup = new PeerWarmup(AirDropLink.DEFAULT_INTERFACE);
        askForBluetooth();
        /*
         * Start looking for peers before the share is read, not after it.
         *
         * Both are on the path between tapping share and seeing the other phone, and the
         * node costs a JVM launch through su, so running them in series paid for both.
         * Bringing only the node forward -- which is what this used to do -- paid for the
         * launch but still left the asking until last, and the asking is the expensive
         * half: measured 2026-11-11 22:49, our first query is answered in 3 ms by our own
         * instance, and the other phone's first answer arrived 2380 ms after the query.
         * That 2.38 s is the other phone waking, not us, and there is nothing to shave.
         *
         * So the round starts here instead. When the entry point is the document picker
         * the file is not resolved for seconds afterwards (4.8 s on the run above) and
         * every one of them used to be spent waiting for a wake-up that could have been
         * happening already. Now the wake-up lands behind the picker: by the time there
         * is something to send the list is full, which is the part the user watches.
         * startScanning() is idempotent, so the call the share path makes later only
         * re-arms the advertisement.
         */
        startScanning();
        readIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A second share while this window is open replaces the first: the user pointed at
        // something else, and keeping the old list would send the wrong files.
        if (intent != null && shares(intent.getAction())) readIntent(intent);
    }

    @Override
    protected void onDestroy() {
        gone = true;
        main.removeCallbacksAndMessages(null);
        if (advertising) {
            BleWake.stop();
            advertising = false;
        }
        if (warmup != null) warmup.clear();
        io.shutdownNow();
        offer.shutdownNow();
        super.onDestroy();
    }

    private static boolean shares(String action) {
        return Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    // ------------------------------------------------------------------ intent

    private void readIntent(Intent intent) {
        if (intent == null || !shares(intent.getAction())) {
            // Opened from the app's own button: nothing is shared yet, so let the user pick.
            // Every other path in this screen assumes there is something to send.
            chooseFiles();
            return;
        }
        io.execute(() -> {
            List<Outgoing> read;
            String failure = null;
            try {
                read = ShareIntake.read(this, intent);
            } catch (IOException | RuntimeException broken) {
                read = new ArrayList<>();
                failure = String.valueOf(broken.getMessage());
            }
            List<Outgoing> ready = read;
            String problem = failure;
            main.post(() -> {
                if (gone) return;
                if (problem != null) {
                    // On the same clock as the other milestones. Without this line a share
                    // that produced nothing leaves no trace at all -- every later probe is
                    // written from startScanning(), which this branch never reaches.
                    Trace.write("timeline: share unreadable +"
                        + (SystemClock.uptimeMillis() - openedAt) + " ms: " + problem);
                    scanStatus.setText("");
                    showAction(getString(R.string.send_failed, problem), false, red);
                    return;
                }
                files = ready;
                renderFiles();
                Trace.write("timeline: " + ready.size() + " file(s) resolved +"
                    + (SystemClock.uptimeMillis() - openedAt) + " ms");
                if (ready.isEmpty()) {
                    scanStatus.setText(R.string.send_nothing);
                    showAction(getString(R.string.send_choose), false, accent);
                    return;
                }
                startScanning();
            });
        });
    }

    private void chooseFiles() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, REQUEST_FILES);
        } catch (ActivityNotFoundException noPicker) {
            showAction(getString(R.string.send_failed, noPicker.getMessage()), false, red);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_FILES) return;
        if (result != RESULT_OK || data == null) {
            if (files.isEmpty()) finish();
            return;
        }
        // Re-uses the share path rather than a second reader: a picked document and a shared
        // one differ only in where the list of URIs comes from.
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int index = 0; index < data.getClipData().getItemCount(); index++) {
                uris.add(data.getClipData().getItemAt(index).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            if (files.isEmpty()) finish();
            return;
        }
        Intent shaped = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*");
        shaped.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        readIntent(shaped);
    }

    // ------------------------------------------------------------------ scanning

    private void startScanning() {
        if (sending || gone) return;
        if (scanning) {
            // The chain is already running, but the advertisement is not: every transfer
            // stops it, and a retry after one would otherwise ask a peer that nothing has
            // just woken. Re-arming it is the whole point of pressing retry.
            ensureAdvertising();
            return;
        }
        scanning = true;
        strikes = 0;
        scanStatus.setText(R.string.send_scanning);
        ensureAdvertising();
        awaitEndpoint(0L);
    }

    /**
     * Holds the wake broadcast up for as long as a row could still be tapped.
     *
     * <p>Not fatal when it cannot start: a peer that is already awake still answers. Saying
     * so beats an empty list with no reason attached to it.
     */
    private void ensureAdvertising() {
        if (advertising || gone || sending) return;
        advertising = BleWake.start(this);
        if (!advertising) {
            String why = BleWake.lastError();
            if (why != null) scanStatus.setText(getString(R.string.send_no_advert, why));
        }
    }

    /**
     * Holds the first round until the AWDL endpoint exists.
     *
     * <p>Sharing a file into this app means {@link MainActivity} never ran, and that is
     * the only place that started the endpoint — so the interface the browser binds to
     * does not exist yet, and a round launched now fails on a link that is still being
     * built. Three of those in a row exhaust {@link #SCAN_STRIKES} and turn a two-second
     * startup into a screen that says "failed" until it is left and reopened, which is
     * exactly what opening the share sheet and sending used to do.
     */
    private void awaitEndpoint(long waited) {
        if (gone || !scanning) return;
        if (AirDropRuntime.link() != null) {
            if (!endpointLogged) {
                endpointLogged = true;
                // The clock stops here for everything this side owns: past this point the
                // browse is asking, and the remaining wait is the other phone's answer.
                Trace.write("timeline: endpoint up +"
                    + (SystemClock.uptimeMillis() - openedAt) + " ms; first query now");
            }
            scanRound();
            return;
        }
        if (waited == 0L) AirDropRuntime.start(this);
        if (waited >= ENDPOINT_WAIT_MS) {
            // Stop waiting and let the round report what is actually wrong: a reason the
            // user can act on beats a spinner over an empty list.
            scanRound();
            return;
        }
        main.postDelayed(() -> awaitEndpoint(waited + ENDPOINT_STEP_MS), ENDPOINT_STEP_MS);
    }

    private void scanRound() {
        if (gone) return;
        // A transfer owns the link and the list is hidden behind the progress view, so
        // there is nothing to ask for. The round is re-armed rather than dropped: the
        // chain has exactly one owner, and letting it lapse here is what left the list
        // frozen after a send the peer refused.
        if (sending) {
            // See SCAN_SENDING_MS: the chain stays armed, but nothing is asked for.
            main.postDelayed(this::scanRound, SCAN_SENDING_MS);
            return;
        }
        if (browser == null) browser = new AirDropBrowser(AirDropLink.DEFAULT_INTERFACE);
        io.execute(() -> {
            List<AirDropPeer> round;
            String failure = null;
            // The window is a deadline, not a delay: a peer that answers in the first
            // packet should appear in the list then, not 2.5 seconds later when the
            // browse stops listening. The round still ends with the same full list.
            AirDropBrowser.Sink early = peers -> {
                List<AirDropPeer> visible = AirDropBrowser.excluding(peers, ownHash());
                if (visible.isEmpty()) return;
                // Started here rather than after the round ends: a peer that answered in the
                // first packet is on screen almost two seconds before the browse window
                // closes, and those two seconds are free time to pay for the handshake.
                warm(visible);
                main.post(() -> {
                    if (gone || sending) return;
                    noteFirstPeer(visible);
                    AirDropBrowser.merge(found, visible);
                    renderPeers();
                });
            };
            try {
                // Ends the window the moment a transfer starts. The browse holds the
                // same AWDL queue the offer needs, so a round that insisted on its full
                // 2.5 seconds kept the offer waiting behind it -- and the tap is exactly
                // when the user is watching. {gone} is in the condition for the same
                // reason on the way out: a finished screen has nothing left to ask for.
                round = browser.browse(AirDropBrowser.DEFAULT_WINDOW_MS, early,
                    () -> sending || gone);
            } catch (IOException | RuntimeException broken) {
                round = new ArrayList<>();
                failure = String.valueOf(broken.getMessage());
                // On the same clock as "endpoint up" and "first peer", so a report of a
                // slow or empty list says whether the round even reached the link. The
                // message is the evidence: "interface awdltun0 is missing" is the link
                // being rebuilt under the round, not a peer that stayed silent.
                Trace.write("timeline: round failed: " + failure);
            }
            // A peer that only answered at the end of the window is not covered by the sink.
            if (failure == null) warm(AirDropBrowser.excluding(round, ownHash()));
            List<AirDropPeer> answers = round;
            String problem = failure;
            main.post(() -> {
                if (gone) return;
                if (problem != null) {
                    // A missing wonder0 lands here, and so does a round that overlapped a
                    // link rebuild. The two are only told apart by trying again, so the
                    // screen keeps asking until the failures stop looking transient.
                    if (++strikes >= SCAN_STRIKES) {
                        scanning = false;
                        scanStatus.setText(getString(R.string.send_failed, problem));
                        return;
                    }
                } else {
                    strikes = 0;
                    // Our own instance answers every query. Offering it would be offering
                    // to send the files to the phone holding them.
                    List<AirDropPeer> visible = AirDropBrowser.excluding(answers, ownHash());
                    noteFirstPeer(visible);
                    AirDropBrowser.merge(found, visible);
                    renderPeers();
                }
                main.postDelayed(this::scanRound, scanGap());
            });
        });
    }

    /**
     * Asks the warmer to have a session ready for each of {@code peers}.
     *
     * <p>Called from the browse thread, once per round, with whatever that round has shown
     * so far — the warmer is idempotent, so repeating a peer costs a map lookup. Nothing
     * here waits for a connection: a receiver that is asleep will not answer until the
     * Bluetooth trigger has done its work, and a round that blocks on that would show its
     * results a timeout later.
     */
    private void warm(List<AirDropPeer> peers) {
        if (gone || sending || peers.isEmpty()) return;
        for (AirDropPeer peer : peers) warmup.offer(peer);
    }

    /**
     * Writes the one line that says when this screen stopped being empty.
     *
     * <p>Called from both places a peer can arrive — the round's early sink, and the
     * round's own result for a device that only answered at the end of the window — so
     * whichever path the first answer takes is the one that stamps the clock. Once.
     */
    private void noteFirstPeer(List<AirDropPeer> visible) {
        if (peerLogged || visible.isEmpty()) return;
        peerLogged = true;
        Trace.write("timeline: first peer \"" + visible.get(0).serviceName + "\" +"
            + (SystemClock.uptimeMillis() - openedAt) + " ms");
    }

    private String ownHash() {
        Identity mine = identity();
        return mine == null ? null : mine.certHash();
    }

    /** The identity that introduces us: the running endpoint's, or the one on disk. */
    private Identity identity() {
        AirDropLink running = AirDropRuntime.link();
        if (running != null) {
            identity = running.identity();
            return identity;
        }
        if (identity != null) return identity;
        try {
            identity = new IdentityStore(new File(getFilesDir(), "airdrop")).loadOrCreate(deviceName());
            return identity;
        } catch (IOException | GeneralSecurityException failed) {
            String reason = String.valueOf(failed.getMessage());
            main.post(() -> {
                if (!gone) showAction(getString(R.string.send_failed, reason), false, red);
            });
            return null;
        }
    }

    /** Same source as the receiver's name, so both halves introduce us identically. */
    private String deviceName() {
        String stored = null;
        try {
            stored = Settings.Global.getString(getContentResolver(), AirDropName.SETTING);
        } catch (RuntimeException ignored) {
            // Locked-down builds refuse the read; the model string still works.
        }
        String name = AirDropName.resolve(stored, null, Build.MODEL);
        return name == null || name.trim().isEmpty() ? "OPAirDrop" : name.trim();
    }

    // ------------------------------------------------------------------ sending

    private void send(AirDropPeer peer) {
        send(peer, false);
    }

    /**
     * @param afterWake whether this is the second attempt of one tap — the one made after
     *                  the peer was woken again and the row re-read. It cannot recurse: a
     *                  peer that is genuinely gone would otherwise keep the user watching a
     *                  progress line that never resolves.
     */
    private void send(AirDropPeer peer, boolean afterWake) {
        if (sending || files.isEmpty()) return;
        Identity mine = identity();
        if (mine == null) return;
        sending = true;
        // One transfer, one file. The retry after a wake is the second act of the same
        // tap and is worth reading next to the first, so only the first attempt clears it.
        // A separator rather than a delete. Everything worth reading about the wait before
        // this tap -- process start, endpoint up, first peer -- was written above this
        // line, and deleting the file here is what made the old measurements begin at the
        // tap and therefore never cover the part the user actually complains about.
        probe(afterWake ? "--- retry ---" : "--- transfer ---");
        probe("tap " + peer.displayName + " files=" + files.size()
            + " afterWake=" + afterWake
            // The address this tap will dial, next to the one the peer connected from last
            // time. Those two are the only way to tell a peer that moved from a peer that
            // was never at the address mDNS gave us.
            + " at " + peer.addresses + ":" + peer.port);
        if (advertising) {
            // The receiver is awake now; advertising through the transfer buys nothing.
            BleWake.stop();
            advertising = false;
        }
        progressPercent = -1;
        progress(0f);
        progressLabel.setTextColor(textDim);
        progressLabel.setText(getString(R.string.send_stage_discovering, peer.displayName));
        progressValue.setText("");
        renderPeers();

        List<Outgoing> outgoing = files;
        // On {offer}, not {io}: the browse round is a blocking call and {io} may still be
        // inside one. Queueing the offer behind it is what made a tap wait a whole window
        // when the timing was bad, and the wait was invisible because the progress line
        // had already switched to "looking for <name>".
        offer.execute(() -> {
            // No session is collected here on purpose. Everything the warmer prepared --
            // the tunnel route with its initial congestion window and the pinned context --
            // belongs to the device and is already in place; the connection itself is built
            // inside send(), at this moment, because a connection older than the peer's own
            // service rebuild is answered by nothing at all.
            try {
                AirDropClient client = new AirDropClient(mine, AirDropLink.DEFAULT_INTERFACE,
                    Build.MODEL);
                AirDropClient.Result result = client.send(peer, outgoing,
                    new AirDropClient.Progress() {
                        @Override
                        public void onStage(AirDropClient.Stage stage) {
                            if (stage == AirDropClient.Stage.DONE) return;
                            probe("stage " + stage);
                            main.post(() -> {
                                if (!gone) progressLabel.setText(stageText(stage, peer));
                            });
                        }

                        @Override
                        public void onProgress(long sent, long total) {
                            probe("progress " + sent + "/" + total);
                            main.post(() -> {
                                if (gone) return;
                                progress(total <= 0 ? 1f : Math.min(1f, sent / (float) total));
                                progressValue.setText(getString(R.string.send_progress,
                                    size(sent), size(total)));
                            });
                        }

                        @Override
                        public void onPeerNamed(String name) {
                            // Filed against the addresses this tap was aimed at, not against
                            // the instance name in the row: by the time the next round of
                            // answers arrives the peer may have rotated that name again, and
                            // the address is the half that does not change.
                            probe("peer calls itself \"" + name + "\"");
                            for (String address : peer.addresses) PeerNames.note(address, name);
                            main.post(() -> {
                                if (!gone) renderPeers();
                            });
                        }
                    });
                probe("accepted after " + result.millis + " ms");
                main.post(() -> {
                    if (gone) return;
                    sending = false;
                    progress(1f);
                    progressLabel.setTextColor(green);
                    progressLabel.setText(getString(R.string.send_done, peer.displayName,
                        result.files, seconds(result.millis)));
                    progressValue.setText("");
                    showAction(getString(R.string.send_close), false, accent);
                });
            } catch (IOException | RuntimeException failed) {
                String reason = explain(failed);
                if (!afterWake && neverArrived(failed)) {
                    // The port refused: the row is a snapshot, and the receiver may have
                    // rebuilt its service or only been asleep. Nothing has reached the
                    // other user yet, so a second attempt costs them nothing.
                    wakeAndRetry(peer, reason);
                    return;
                }
                reportFailure(reason);
            }
        });
    }

    /**
     * Whether the offer died before the receiver's user could have been asked.
     *
     * <p>A {@code ConnectException} is the answer port being closed — what an iPhone does
     * when an AirDrop sheet is dismissed, and also what it did on this device while the
     * sheet was still on screen, because it had rebuilt its service and the row still
     * named the old one. Nothing is retried over a warmed session any more, so what
     * arrives here is the port's final word rather than a hiccup on one session.
     */
    private static boolean neverArrived(Throwable failed) {
        for (Throwable t = failed; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * Wakes the peer again and offers to the row as the link reports it now.
     *
     * <p>The wait is not politeness. A receiver whose port refused is asleep or rebuilding,
     * and it starts answering only once the Bluetooth trigger lands — 2.5 s between the
     * advertisement going out and the first accepted connection on the device this was
     * measured on. The scan chain runs a full round inside that window, so the peer handed
     * to the second attempt carries whatever address and port the link has settled on since
     * the tap, rather than the snapshot that just failed.
     */
    private void wakeAndRetry(AirDropPeer stale, String reason) {
        if (gone) return;
        progressLabel.setTextColor(textDim);
        progressLabel.setText(getString(R.string.send_stage_waking, stale.displayName));
        progressValue.setText("");
        ensureAdvertising();
        main.postDelayed(() -> {
            if (gone) return;
            AirDropPeer fresh = null;
            for (AirDropPeer one : found) {
                if (one.sameDevice(stale)) {
                    fresh = one;
                    break;
                }
            }
            if (fresh == null) {
                reportFailure(reason);
                return;
            }
            Trace.write("* woke the peer again; offering to " + fresh.host + ":" + fresh.port
                + " instead of " + stale.host + ":" + stale.port);
            // send() stands down while a transfer owns the screen; this attempt is the one
            // that owns it now.
            sending = false;
            send(fresh, true);
        }, WAKE_MS);
    }

    private void reportFailure(String reason) {
        main.post(() -> {
            if (gone) return;
            sending = false;
            progressPercent = -1;
            progress(0f);
            progressLabel.setTextColor(red);
            progressLabel.setText(getString(R.string.send_failed, reason));
            progressValue.setText("");
            scanStatus.setText(R.string.send_peer_hint);
            // Retry is the same tap on the same row, so the list comes back rather than
            // sending the user hunting for a device that is still there.
            showAction(getString(R.string.send_retry), true, accent);
            showPeers();
            // Scanning stopped while the transfer ran. Restart it here so the row the
            // retry points at is one the screen is still refreshing — and so the wake
            // broadcast, which send() stopped, is running again.
            startScanning();
        });
    }

    private String stageText(AirDropClient.Stage stage, AirDropPeer peer) {
        switch (stage) {
            case ASKING:
                return getString(R.string.send_stage_asking, peer.displayName);
            case UPLOADING:
                return getString(R.string.send_sending, peer.displayName);
            default:
                return getString(R.string.send_stage_discovering, peer.displayName);
        }
    }

    /**
     * The transport's own words for why a send stopped, replaced with something the
     * person holding the phone can act on.
     *
     * <p>A refused connection is the peer having closed its answer port -- which is what
     * happens when the other side taps Cancel or dismisses the sheet -- and the line on
     * screen used to read "isConnected failed: ECONNREFUSED (Connection refused)". That
     * sentence is true, and it names nothing that has to change, which is the other
     * device. The raw text still goes to the trace: when this is a bug rather than a
     * cancelled sheet, the errno is the evidence.
     *
     * <p>Matching on the text rather than the class because the interesting failure
     * arrives as {@code java.net.ConnectException} from one path and
     * {@code sun.net.ConnectionResetException} from another, both private to the
     * platform, and a subclass check would be a guess about which one this build throws.
     * Anything unrecognised is passed through as it came rather than flattened into a
     * tidy sentence that might be about the wrong thing.
     */
    private String explain(Throwable failed) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failed; t != null && chain.length() < 4096; t = t.getCause()) {
            chain.append(t.getClass().getName()).append(' ').append(t.getMessage()).append(' ');
            if (t.getCause() == t) break;
        }
        String raw = chain.toString().trim();
        Trace.write("! send stopped: " + raw);
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("econnrefused") || lower.contains("connection refused"))
            return getString(R.string.send_why_refused);
        if (lower.contains("econnreset") || lower.contains("connection reset")
                || lower.contains("broken pipe"))
            return getString(R.string.send_why_reset);
        if (lower.contains("timed out") || lower.contains("timeout"))
            return getString(R.string.send_why_timeout);
        if (lower.contains("enetunreach") || lower.contains("network is unreachable")
                || lower.contains("no route to host") || lower.contains("unknownhost"))
            return getString(R.string.send_why_unreachable);
        String message = failed.getMessage();
        return message == null || message.trim().isEmpty() ? failed.toString() : message;
    }

    private void progress(float fraction) {
        int percent = Math.round(Math.max(0f, Math.min(1f, fraction)) * 100f);
        if (percent == progressPercent) return;
        progressPercent = percent;
        LinearLayout.LayoutParams params =
            (LinearLayout.LayoutParams) progressFill.getLayoutParams();
        params.weight = percent == 0 ? 0.0001f : percent;
        progressFill.setLayoutParams(params);
        progressTrack.setVisibility(percent == 0 ? View.INVISIBLE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ rendering

    private void renderFiles() {
        fileList.removeAllViews();
        long total = 0;
        for (Outgoing file : files) total += Math.max(0L, file.length);
        filesSummary.setText(files.isEmpty()
            ? getString(R.string.send_nothing)
            : getString(R.string.send_files_summary, files.size(), size(total)));
        for (int index = 0; index < files.size(); index++) {
            if (index > 0) fileList.addView(divider());
            fileList.addView(fileRow(files.get(index)));
        }
    }

    private View fileRow(Outgoing file) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView badge = label(badge(file.name), 11f, true, accent);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(tint(accent, 0x1A), dp(9)));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        badgeParams.rightMargin = dp(12);
        row.addView(badge, badgeParams);

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(file.name, 14f, false, text);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        lines.addView(name);
        lines.addView(label(size(file.length) + "  ·  " + type(file.type), 12f, false, textDim));
        row.addView(lines, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** Rebuilds the peer rows; a round that found the same devices does nothing. */
    private void showPeers() {
        StringBuilder signature = new StringBuilder();
        for (AirDropPeer peer : found) {
            // The learned name is part of the signature, not decoration: it arrives in a
            // packet of its own, separate from the answer that put the row on screen, and a
            // signature that ignored it would keep every row titled with its hex name until
            // something else changed.
            signature.append(peer.serviceName).append('=')
                .append(browser == null ? "" : String.valueOf(browser.nameFor(peer)))
                .append('\n');
        }
        if (sending) signature.append("sending");
        if (signature.toString().equals(shownPeers)) return;
        shownPeers = signature.toString();

        peerList.removeAllViews();
        for (int index = 0; index < found.size(); index++) {
            if (index > 0) peerList.addView(divider());
            peerList.addView(peerRow(found.get(index)));
        }
    }

    private void renderPeers() {
        showPeers();
        if (found.isEmpty()) return;
        scanStatus.setText(getString(R.string.send_devices, found.size()));
    }

    private View peerRow(AirDropPeer peer) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setBackground(rippleRow());

        View badge = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(tint(accent, 0x1A));
        dot.setStroke(Math.max(1, dp(1.5f)), accent);
        badge.setBackground(dot);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        badgeParams.rightMargin = dp(12);
        row.addView(badge, badgeParams);

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        // The name the device goes by, when the link told us. The AirDrop record only
        // carries Apple's rotating identity, so until the Continuity answer arrives this
        // row is titled with that hex name -- and the hex stays in the subtitle afterwards,
        // because two iPhones can share a name while only one of them is on this link.
        String raw = peer.displayName == null ? peer.serviceName : peer.displayName;
        String known = browser == null ? null : browser.nameFor(peer);
        boolean named = known != null && !known.isEmpty();
        TextView name = label(named ? known : raw, 15f, true, text);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lines.addView(name);
        lines.addView(label(named ? raw + "  ·  " + where(peer) : where(peer), 12f, false, textDim));
        row.addView(lines, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(label(getString(R.string.send_action), 14f, true, accent));
        if (sending) row.setAlpha(0.45f);
        else row.setOnClickListener(clicked -> send(peer));
        return row;
    }

    /**
     * The address the row dials, with the hostname only as a fallback.
     *
     * <p>The address first because it is what the offer actually uses and what a second
     * look at the wire would show. The hostname used to win, and on an iPhone it is a
     * fresh UUID ending in {@code .local} -- true, useless, and it pushed the one fact
     * worth reading off the row.
     */
    private String where(AirDropPeer peer) {
        if (!peer.addresses.isEmpty()) {
            String address = peer.addresses.get(0);
            if (address != null && !address.isEmpty()) return address;
        }
        return peer.host == null ? "" : peer.host;
    }

    private void showAction(String message, boolean retry, int color) {
        action.setText(message);
        action.setTextColor(color == accent ? 0xFFFFFFFF : color);
        action.setBackground(rounded(color == accent ? accent : tint(color, 0x1F), dp(14)));
        action.setVisibility(View.VISIBLE);
        action.setOnClickListener(clicked -> {
            if (retry) {
                action.setVisibility(View.GONE);
                sending = false;
                shownPeers = "";
                renderPeers();
                startScanning();
            } else if (files.isEmpty()) {
                chooseFiles();
            } else {
                finish();
            }
        });
    }

    private void askForBluetooth() {
        ArrayList<String> missing = new ArrayList<>();
        for (String needed : new String[]{
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT}) {
            if (checkSelfPermission(needed) != PackageManager.PERMISSION_GRANTED) {
                missing.add(needed);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQUEST_PERMISSIONS || gone || sending || files.isEmpty()) return;
        // A grant now makes the advertisement possible. Without it the scan still runs; it
        // just asks a question nobody has been woken up to answer.
        if (!advertising) startScanning();
    }

    // ------------------------------------------------------------------ layout

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setClipToPadding(false);
        final int pad = dp(20);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(pad, bars.top + dp(12), pad, bars.bottom + dp(12));
            return insets;
        });

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(getString(R.string.send_title), 24f, true, text),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = label(getString(R.string.send_close), 14f, true, textDim);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setOnClickListener(clicked -> finish());
        header.addView(close);
        root.addView(header, width());

        filesSummary = label("", 13f, false, textDim);
        LinearLayout.LayoutParams summaryParams = width();
        summaryParams.topMargin = dp(4);
        summaryParams.bottomMargin = dp(14);
        root.addView(filesSummary, summaryParams);

        fileList = card();
        root.addView(fileList, width());

        LinearLayout devicesHeader = horizontal();
        devicesHeader.setGravity(Gravity.CENTER_VERTICAL);
        devicesHeader.addView(label(getString(R.string.send_devices_title), 13f, true, textDim),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scanStatus = label(getString(R.string.send_scanning), 12f, false, textDim);
        devicesHeader.addView(scanStatus);
        LinearLayout.LayoutParams devicesParams = width();
        devicesParams.topMargin = dp(22);
        devicesParams.bottomMargin = dp(8);
        root.addView(devicesHeader, devicesParams);

        peerList = card();
        root.addView(peerList, width());

        // Pushes the progress block to the bottom, so it sits in the same place whether or
        // not there is a device list above it.
        root.addView(new View(this), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        progressLabel = label("", 13f, false, textDim);
        root.addView(progressLabel, width());

        progressTrack = buildProgressTrack();
        LinearLayout.LayoutParams trackParams = width();
        trackParams.topMargin = dp(10);
        root.addView(progressTrack, trackParams);

        progressValue = label("", 12f, false, textDim);
        LinearLayout.LayoutParams valueParams = width();
        valueParams.topMargin = dp(6);
        root.addView(progressValue, valueParams);

        action = label("", 15f, true, 0xFFFFFFFF);
        action.setGravity(Gravity.CENTER);
        action.setVisibility(View.GONE);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        actionParams.topMargin = dp(14);
        root.addView(action, actionParams);

        // The bar exists from the first frame and is merely invisible, so starting a send
        // does not move the button the user is about to press.
        progressTrack.setVisibility(View.INVISIBLE);
        return root;
    }

    /** A two-part bar: the track is fixed, the fill's weight is the percentage. */
    private View buildProgressTrack() {
        LinearLayout track = horizontal();
        track.setWeightSum(100f);
        track.setBackground(rounded(divider, dp(3)));
        progressFill = new View(this);
        progressFill.setBackground(rounded(green, dp(3)));
        track.addView(progressFill, new LinearLayout.LayoutParams(0, dp(6), 0.0001f));
        return track;
    }

    private LinearLayout card() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(rounded(card, dp(18)));
        return list;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        params.leftMargin = dp(14);
        params.rightMargin = dp(14);
        line.setLayoutParams(params);
        return line;
    }

    private static LinearLayout.LayoutParams width() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private TextView label(String value, float size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(Math.max(1f, radius));
        return shape;
    }

    /**
     * A ripple clipped to the card's corner radius. The mask carries the shape; giving the
     * drawable a content instead would paint a square highlight over the rounded top and
     * bottom rows.
     */
    private RippleDrawable rippleRow() {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), null,
            rounded(0xFF000000, dp(14)));
    }

    /** @param alpha 0-255 applied to an opaque colour, for the tinted fills */
    private static int tint(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private void palette(boolean light) {
        if (light) {
            bg = 0xFFF0F1F3;
            card = 0xFFFFFFFF;
            text = 0xFF17181A;
            textDim = 0xFF5F6368;
            accent = 0xFF2563EB;
            divider = 0x14000000;
            green = 0xFF16A34A;
            red = 0xFFDC2626;
            ripple = 0x14000000;
        } else {
            bg = 0xFF1F1F1F;
            card = 0xFF303030;
            text = 0xFFF5F5F5;
            textDim = 0xFF9E9E9E;
            accent = 0xFF398FFE;
            divider = 0x1FFFFFFF;
            green = 0xFF22C55E;
            red = 0xFFEF4444;
            ripple = 0x1AFFFFFF;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ formatting

    /** Up to four characters of the extension: what the coloured square in a file row says. */
    private static String badge(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "?";
        String extension = name.substring(dot + 1).toUpperCase(Locale.ROOT);
        return extension.length() > 4 ? extension.substring(0, 4) : extension;
    }

    /** The UTI is what gets sent; the tail after its last dot is what a person reads. */
    private static String type(String uti) {
        if (uti == null) return "";
        int dot = uti.lastIndexOf('.');
        return dot < 0 ? uti : uti.substring(dot + 1);
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s",
            value, units[unit]);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }
}
