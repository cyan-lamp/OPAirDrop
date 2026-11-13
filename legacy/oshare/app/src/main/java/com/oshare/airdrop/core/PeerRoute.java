package com.oshare.airdrop.core;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gives the route to one peer an initial congestion window larger than the kernel's ten
 * segments.
 *
 * <p>Measured on the device, and it is the whole of the slow start: an upload reaches
 * 128 KB in thirteen milliseconds, then stops for 1.28 s while the sender's window is
 * eight segments wide. The link is not the problem -- {@code minrtt} is 7.7 ms while the
 * transfer runs -- and neither is the sender's buffer, which still holds a hundred
 * kilobytes nobody can put on the air. What limits it is {@code cwnd}.
 *
 * <p>{@code cwnd} is ten because it starts at ten and the first loss halves it. Ten is
 * also the only value the stack can pick: this kernel has no
 * {@code /proc/sys/net/ipv4/tcp_init_cwnd} to read a default from. The value that can be
 * reached is per route -- {@code RTAX_INITCWND} -- so it is set here, on the peer's own
 * /128, which wins over the on-link /64 the tunnel installs.
 *
 * <p>Two details make the difference between helping and doing nothing. The route belongs
 * in table 1087 and nowhere else: Android resolves every {@code fe80::} destination
 * through {@code ip -6 rule ... lookup 1087} for this app's uid, and a route written to
 * {@code main} is a route nothing reads. And a loss early in the transfer sets
 * {@code ssthresh} to half the flight rather than to seven segments, so raising the
 * starting window raises the recovery point with it.
 */
final class PeerRoute {

    /**
     * Segments to start with. The link delivers about 3.85 MB/s and its real round trip
     * is 8 ms, so 34 segments of 1389 B -- 47 KB -- already covers that pipe with room to
     * spare, while staying far below the 256-slot transmit queue.
     */
    private static final int INIT_CWND = 34;

    /** Where Android's own rule sends a {@code fe80::} destination for this app. */
    private static final String TABLE = "1087";

    /** The route needs root; a shell that never answers must not hold up a warm-up. */
    private static final long SHELL_TIMEOUT_MS = 2_000L;

    /** Give up after this many attempts, so a device without root is not asked forever. */
    private static final int MAX_TRIES = 3;

    /** Keyed by interface identity and address; see {@link #stamp}. */
    private static final Set<String> DONE = Collections.synchronizedSet(new HashSet<String>());
    private static final Map<String, Integer> TRIES = new HashMap<String, Integer>();

    private PeerRoute() {
    }

    /**
     * Installs the route for {@code address} if it has not been installed already.
     *
     * <p>Called before the socket is created, because {@code initcwnd} is read once, when
     * the connection is set up; a route added afterwards changes nothing for a connection
     * that already exists.
     */
    static void install(String address, String iface) {
        if (address == null || iface == null || iface.isEmpty()) return;
        String bare = address;
        int zone = bare.indexOf('%');
        if (zone >= 0) bare = bare.substring(0, zone);
        if (!bare.toLowerCase(Locale.ROOT).startsWith("fe80")) return;

        String key = iface + "@" + stamp(iface) + " " + bare;
        if (DONE.contains(key)) return;
        // A transient refusal -- the tunnel arriving a moment after the address is known
        // -- still gets its retry; a device without root is not asked forever.
        synchronized (TRIES) {
            Integer tried = TRIES.get(key);
            int attempted = tried == null ? 0 : tried;
            if (attempted >= MAX_TRIES) return;
            TRIES.put(key, attempted + 1);
        }

        String command = "ip -6 route replace " + bare + "/128 dev " + iface
            + " table " + TABLE + " initcwnd " + INIT_CWND;
        if (run(command)) {
            DONE.add(key);
            // Logged because the alternative is guessing: the kernel reports the window
            // it actually used, and a sample showing cwnd 10 where 34 was asked for is
            // only interpretable if the install is known to have succeeded.
            Trace.write("* initcwnd " + INIT_CWND + " set for " + bare + " on " + iface);
        } else {
            Trace.write("! initcwnd route refused for " + bare + " on " + iface);
        }
    }

    /**
     * Identifies the current tunnel behind a name.
     *
     * <p>A route lives and dies with the interface it names, and this app restarts its
     * node -- deleting the tunnel and building a new one -- whenever it is relaunched. A
     * cache keyed on the name alone would remember the route from the previous interface
     * and skip the install on the new one. The index is the interface's identity for as
     * long as it exists, and reading it costs a file read rather than a shell.
     */
    private static String stamp(String iface) {
        String path = "/sys/class/net/" + iface + "/ifindex";
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] one = new byte[16];
            int read = in.read(one);
            if (read <= 0) return "?";
            StringBuilder digits = new StringBuilder(read);
            for (int i = 0; i < read; i++) {
                char c = (char) (one[i] & 0xff);
                if (c >= '0' && c <= '9') digits.append(c);
            }
            return digits.length() == 0 ? "?" : digits.toString();
        } catch (IOException missing) {
            return "?";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing to do: the read already answered.
                }
            }
        }
    }

    /** Runs one command as root and reports whether it exited cleanly. */
    private static boolean run(String command) {
        Process shell = null;
        try {
            shell = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
            // Drained on its own thread: a shell that says more than a pipe buffer holds
            // would otherwise block on the write while this side waits for the exit, and
            // both would wait until the timeout.
            drain(shell.getInputStream());
            long deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    return shell.exitValue() == 0;
                } catch (IllegalThreadStateException alive) {
                    try {
                        Thread.sleep(20L);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return false;
        } catch (IOException noRoot) {
            return false;
        } finally {
            if (shell != null) shell.destroy();
        }
    }

    private static void drain(final InputStream out) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] skip = new byte[256];
                try {
                    while (out.read(skip) != -1) {
                        // Discarded: the exit status is the answer, not the text.
                    }
                } catch (IOException closed) {
                    // The process went away; the status still stands.
                }
            }
        }, "peer-route");
        reader.setDaemon(true);
        reader.start();
    }
}
