package com.oshare.airdrop;

import android.content.Context;
import android.util.Log;

import com.oshare.airdrop.core.MdnsResponder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs the process that speaks AWDL, and keeps it running.
 *
 * <p>The radio side of this app cannot live in here. Putting an AWDL frame on the air
 * needs {@code AF_PACKET} bound to an ifindex and needs {@code /dev/tun}; both are root
 * only, and an app process never is. So the data plane runs as its own root process,
 * started with {@code app_process} against this APK's own dex -- the same class the probe
 * scripts use, with the same arguments.
 *
 * <p>What it leaves behind is an interface, {@link #TUN}, carrying exactly one thing: a
 * link-local IPv6 address whose {@code fe80::/64} route lives in the table Android's
 * policy rule actually consults. Everything above it -- ICMPv6, TCP, TLS, the AirDrop
 * HTTP dialect -- is the kernel's, and the app reaches the peer with an ordinary socket
 * bound to that interface. Nothing above this class has to know AWDL exists.
 *
 * <p>The interface name is deliberate rather than derived: the responder and the browser
 * both bind to it by name, and a name that moved between runs would be the kind of bug
 * that looks like a phone that stopped answering.
 */
public final class AwdlNode {

    public static final String TAG = "OPAirDrop";

    /** The interface the node creates; the app's sockets bind to this. */
    public static final String TUN = MdnsResponder.DEFAULT_INTERFACE;

    /** The vdev frames are transmitted on. Never {@code wonder0}: see the class comment. */
    static final String IFACE = "wondertap0";

    /** The class with a {@code main}, run under {@code app_process}. */
    private static final String CLASS = "com.oshare.airdrop.core.AwdlData";

    private static final String LIBRARY = "libawdl_inject.so";

    /** Where the node's own log goes; unreadable from the app, readable from a shell. */
    static final String LOG = "/data/local/tmp/oshare-awdl.log";

    /**
     * Every process already running this node, as a {@code pkill} pattern.
     *
     * <p>The pattern cannot be written out in full: the shell that runs this command is
     * itself a process whose command line contains everything the pattern is looking
     * for, so a literal {@code pkill -f <class name>} matches its own shell and ends it
     * before the node it was about to start exists. The array brackets make the pattern
     * match the node without matching the text of the pattern, which is the one way to
     * say "the class name" in a command line that must not contain the class name.
     */
    private static final String STALE_PATTERN = "core[.]AwdlData";

    /**
     * Makes room in the driver's target table before a node is started.
     *
     * <p>The driver keeps the receivers it has been told to talk to in eight slots and
     * never evicts one. Every node start aims the link at the peer it heard, and a move
     * costs a slot that is never refunded, so the table is a budget spent across runs
     * rather than within one: left alone it fills up after a handful of app launches and
     * from then on every frame completes with status3 and reaches nobody -- the radio
     * still hears the phone, nothing on our side reports an error, and it reads exactly
     * like a peer that stopped answering. Clearing on the eighth run is too late to be
     * useful, so this looks at the count and clears with room to spare.
     *
     * <p>Clearing means stopping the vif, and only the module's own script knows how to
     * put it back (it is a cfg80211 vendor command, not an {@code ip} one). When that
     * script is not on the device the count is left alone and said out loud instead: a
     * quiet failure here is indistinguishable from the bug it is guarding against.
     */
    private static final String TARGET_GUARD =
            "T=$(ls /sys/module/*/parameters/opairshare_awdl_target_state 2>/dev/null | head -1); "
            + "if [ -n \"$T\" ]; then "
            + "held=$(sed -n 's/.*peers=\\([0-9]*\\).*/\\1/p' \"$T\" 2>/dev/null | head -1); "
            + "case \"$held\" in 6|7|8) "
            + "S=/data/local/tmp/awdl-link.sh; "
            + "if [ -f \"$S\" ]; then "
            + "sh \"$S\" reset >/dev/null 2>&1; "
            + "log -t OPAirDrop \"target table $held/8, reset before node start\"; "
            + "else log -t OPAirDrop \"target table $held/8 and no $S to clear it\"; "
            + "fi ;; esac; "
            + "fi; ";

    /** How often the tunnel is looked for while the shell is bringing the node up. */
    private static final long POLL_MILLIS = 20L;

    /**
     * How long the tunnel gets to appear once the shell has started the node.
     *
     * <p>Generous next to the work it covers -- the node's JVM takes about a quarter of a
     * second to reach the point where it opens the tun -- because the only thing a shorter
     * budget buys is a false "it did not start" on a phone that is busy loading the share
     * sheet.
     */
    private static final long TUNNEL_MILLIS = 5_000L;

    private static Process node;
    private static String lastError = "";

    private AwdlNode() {
    }

    /** The last reason a start failed, for the UI to show instead of a spinner. */
    public static synchronized String lastError() {
        return lastError;
    }

    public static synchronized boolean running() {
        return node != null && node.isAlive();
    }

    /** Starts the node if it is not already up; returns whether it is up now. */
    public static synchronized boolean start(Context context) {
        if (running())
            return true;

        File library;
        try {
            library = unpack(context);
        } catch (IOException missing) {
            lastError = "native library: " + missing.getMessage();
            Log.w(TAG, "cannot unpack " + LIBRARY, missing);
            return false;
        }

        /*
         * The address the node puts on the tun is derived from the vdev's own MAC, which
         * is the same MAC wonder0 already carries -- so wonder0 holds an identical
         * fe80:: and source selection would have two candidates for every packet the app
         * sends. The vif is not used for anything once the tun is there (the driver
         * delivers almost nothing back into an ethernet vdev), so its addresses go away
         * rather than compete.
         */
        /*
         * The last argument is this app's uid. The node needs it to install the policy
         * rule that sends our link-local traffic into the tun, and it cannot look the
         * uid up on its own: it runs as root, where every lookup answers zero.
         */
        /*
         * Anything already holding the tun goes first, and the interface is removed as
         * well, so the next node builds one from nothing instead of inheriting the
         * address of a node that is no longer there. The class name is assembled from
         * two halves in the shell for the reason given on {@link #STALE_PATTERN}.
         *
         * The wait after the kill is bounded by the interface actually going away, not
         * by a fixed delay. A share-sheet launch is usually a cold start with no node
         * to kill, and a flat 400 ms there was pure latency on the very path the user
         * watches: from tapping share to the device list filling in. When a node does
         * have to die the tun disappears within a tick, and the loop leaves then.
         */
        String command = "pkill -f '" + STALE_PATTERN + "' 2>/dev/null; "
                + "i=0; while [ $i -lt 20 ] && ip link show " + TUN
                + " >/dev/null 2>&1; do sleep 0.05; i=$((i+1)); done; "
                + TARGET_GUARD
                + "ip link del " + TUN + " 2>/dev/null; "
                + "ip -6 addr flush dev wonder0 2>/dev/null; "
                // The node's own output has no clock, and the file is shared by every
                // node this app ever starts, so it is delimited rather than truncated:
                // a restart two hundred milliseconds after the last one is a fact about
                // the start-up race, and truncating deletes exactly the evidence that
                // shows it. One rotation keeps that bounded without spending the
                // previous launch's log to do it -- which is how the evidence for the
                // last failure in this file was lost once already.
                + "if [ -f " + LOG + " ] && [ $(stat -c %s " + LOG
                + " 2>/dev/null || echo 0) -gt 65536 ]; then mv -f " + LOG
                + " " + LOG + ".1 2>/dev/null; fi; "
                + "echo \"=== node start $(date +%H:%M:%S) shell=$$ ===\" >> " + LOG + "; "
                + "N=com.oshare.airdrop.core.Awdl\"\"Data; "
                + "CLASSPATH=" + context.getPackageCodePath()
                + " app_process /system/bin $N"
                + " " + IFACE + " 0 33:33:00:00:00:fb auto 8770 _airdrop._tcp.local"
                + " " + TUN + " " + library.getAbsolutePath()
                + " " + android.os.Process.myUid()
                // The app's pid, so the node can leave when the app does: a node whose
                // app is gone is a tunnel nobody is listening on, holding a name the next
                // launch has to fight for.
                + " " + android.os.Process.myPid()
                + " >> " + LOG + " 2>&1 & "
                // Started in the background only so the node's pid can be named on the
                // line that records how it ended. A pid is what turns "a node died here"
                // into "that node died here", and with a restart two hundred milliseconds
                // behind the first one, the difference is the whole diagnosis.
                + "P=$!; wait $P; "
                // How a node ended matters as much as when: a signal is printed by the
                // shell as "Terminated", but an exit status is what a crash looks like.
                + "echo \"=== node exit $? pid=$P $(date +%H:%M:%S) ===\" >> " + LOG;

        try {
            node = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            lastError = "";
            Log.i(TAG, "node started on " + IFACE + " -> " + TUN);
        } catch (IOException refused) {
            lastError = "su: " + refused.getMessage();
            Log.w(TAG, "cannot start the node", refused);
            return false;
        }
        // The command above is a shell, and a shell runs on its own: it kills the old
        // node and deletes the interface before it launches the new one, but this method
        // returned as soon as the shell existed. A caller that then polls for the
        // interface can find the previous node's tunnel, still holding a link-local
        // address and looking exactly like a live link, a few milliseconds before the
        // shell deletes it -- and every socket bound to it in the meantime dies with the
        // interface. That is not hypothetical: it was three quarters of a second of
        // "finding the device is slow", because the first browse round failed on a
        // missing interface and the screen waited out the retry gap.
        //
        // Waiting for the old tunnel to go away first closes that window, because the
        // shell cannot launch the node before it has done the same thing. When there is
        // no previous node -- the usual case, a share-sheet launch that is a cold start
        // -- there was never an interface and this returns at once.
        awaitGone(TUN, 2_000L);
        // ...and the tunnel that replaces it is waited for here rather than by the caller.
        //
        // "The old one is gone" and "there is a tunnel now" are two different moments, and
        // the gap between them is where the share screen lost three seconds. Measured on
        // the device: the shell was started at .751, the old tunnel went at .815, the new
        // node opened its own at .084, and a caller that asked "is there an interface?"
        // in that 270 ms window got the honest answer "no" -- or, when the old node was a
        // moment slower to die, got "yes" about a tunnel that was about to be deleted out
        // from under it, and the endpoint then failed to bind with "no interface named".
        // Waiting here means a true return is a tunnel that exists and belongs to the node
        // this call started, which is the only claim the caller can act on.
        return running() && awaitPresent(TUN, TUNNEL_MILLIS);
    }

    /**
     * Waits for {@code interfaceName} to appear; {@code false} if the node died first.
     *
     * <p>Bounded like {@link #awaitGone}, and for the same reason: a tunnel that never
     * shows is something the layer above reports, not something to wait on forever.
     */
    private static boolean awaitPresent(String interfaceName, long budgetMillis) {
        long deadline = System.currentTimeMillis() + budgetMillis;
        while (System.currentTimeMillis() < deadline) {
            if (hasInterface(interfaceName)) return true;
            if (!running()) return false;
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return hasInterface(interfaceName);
    }

    /**
     * Waits for {@code interfaceName} to disappear, giving up after {@code budgetMillis}.
     *
     * <p>Bounded rather than indefinite: a tunnel that will not go away is a reason to
     * start anyway and let the layer above report what is missing, not a reason to leave
     * the runtime thread waiting forever.
     */
    private static void awaitGone(String interfaceName, long budgetMillis) {
        long deadline = System.currentTimeMillis() + budgetMillis;
        while (hasInterface(interfaceName) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static boolean hasInterface(String interfaceName) {
        try {
            return NetworkInterface.getByName(interfaceName) != null;
        } catch (SocketException unavailable) {
            return false;
        }
    }

    /** Ends the node; the tun disappears with it. */
    public static synchronized void stop() {
        if (node == null)
            return;
        node.destroy();
        node = null;
    }

    /**
     * Writes the shim out of the APK, because a root process started by {@code su} has no
     * {@code Context} to ask where the app's native libraries were unpacked -- and on
     * this packaging they may not have been unpacked at all.
     */
    private static File unpack(Context context) throws IOException {
        File target = new File(context.getFilesDir(), LIBRARY);
        long packaged = packagedSize(context);
        if (target.isFile() && target.length() == packaged)
            return target;

        try (ZipFile apk = new ZipFile(context.getPackageCodePath())) {
            ZipEntry entry = apk.getEntry("lib/arm64-v8a/" + LIBRARY);
            if (entry == null)
                throw new IOException(LIBRARY + " is not in the APK");
            try (InputStream in = apk.getInputStream(entry);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[16 * 1024];
                int got;
                while ((got = in.read(buffer)) > 0)
                    out.write(buffer, 0, got);
            }
        }
        // The node runs as root and only needs to read it.
        //noinspection ResultOfMethodCallIgnored
        target.setReadable(true, false);
        return target;
    }

    /** Size of the packaged shim, so a rebuilt one is noticed and replaced. */
    private static long packagedSize(Context context) {
        try (ZipFile apk = new ZipFile(context.getPackageCodePath())) {
            ZipEntry entry = apk.getEntry("lib/arm64-v8a/" + LIBRARY);
            return entry == null ? -1 : entry.getSize();
        } catch (IOException unreadable) {
            return -1;
        }
    }
}
