package com.oshare.airdrop;

import com.oshare.airdrop.core.AirDropClient;
import com.oshare.airdrop.core.AirDropPeer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Prepares the device for a tap while the list is still on screen.
 *
 * <p>This used to hold an empty TLS session per peer, on the reasoning that the handshakes
 * are the slow part and could be paid early. Half of that reasoning survived contact with a
 * real iPhone and half of it did not. What a tap actually inherits from an earlier connect
 * is three things that belong to the device rather than to a socket: the neighbour entry
 * through the AWDL tunnel, the route with its initial congestion window, and the pinned
 * {@link javax.net.ssl.SSLContext} in the process-wide cache. Those outlive the socket that
 * produced them, and no peer can forget them.
 *
 * <p>The socket itself does not survive. An iPhone rebuilds its AirDrop service while the
 * list is open — its advertised machine id changed from 01f04635-... to 7e091bc2-... inside
 * one session — and a connection opened before the rebuild is answered by nothing at all
 * once the user taps. The failure is not a refusal, which would be easy to see, but silence:
 * no status, no reset, not one byte, and the offer dies at its read timeout with the other
 * user never having been asked. An offer on a socket opened at the tap arrived in 1.0 to 1.4
 * seconds in the same session that the warm one went unanswered.
 *
 * <p>So the warm-up no longer warms a connection; it warms the path {@link
 * AirDropClient#prime} walks, which cannot fail in a way the offer depends on. It still has
 * to run early, because the congestion window is read once, while the connection is being
 * set up, and it still has to run off the thread drawing the list, because it reaches for
 * root.
 */
final class PeerWarmup {
    private final String interfaceName;

    /**
     * Peers a priming pass is already queued for.
     *
     * <p>The list is offered again every scan round, a few hundred milliseconds apart, and
     * priming shells out to {@code ip route} each time. Without this the single worker would
     * spend every round re-issuing a route that is already installed.
     */
    private final Set<String> pending = new HashSet<>();

    /**
     * Set when the screen is gone.
     *
     * <p>Priming is cheap but it is not free and it is not useful after the user has left,
     * so a pass that was queued before the exit is dropped rather than run.
     */
    private boolean stopped;

    /**
     * One thread for every priming pass. It reaches for a shell and takes tens of
     * milliseconds, which must never happen on the thread drawing the list or on the one
     * running a browse round.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "airdrop-warmup");
        thread.setDaemon(true);
        return thread;
    });

    PeerWarmup(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    /**
     * Primes {@code peer} if it is not primed already.
     *
     * <p>Called once per row per browse round, so the common case is a lookup that finds the
     * work already queued and returns.
     */
    void offer(final AirDropPeer peer) {
        if (peer == null || peer.port <= 0) return;
        final String key = keyOf(peer);
        synchronized (pending) {
            if (stopped) return;
            if (!pending.add(key)) return;
        }
        try {
            worker.execute(() -> prime(peer, key));
        } catch (RejectedExecutionException closing) {
            // The screen went away between the check above and this post.
            synchronized (pending) {
                pending.remove(key);
            }
        }
    }

    /** Runs one priming pass; runs on {@link #worker}. */
    private void prime(AirDropPeer peer, String key) {
        try {
            synchronized (pending) {
                if (stopped) return;
            }
            AirDropClient.prime(peer, interfaceName);
        } finally {
            synchronized (pending) {
                pending.remove(key);
            }
        }
    }

    /**
     * Stops the worker; called when the screen goes away.
     *
     * <p>There is nothing to close any more, which is the point: the previous version had to
     * tear down TLS sessions from a thread of its own, because writing a close_notify is
     * network I/O and doing it from {@code Activity.onDestroy} threw
     * {@code NetworkOnMainThreadException} and killed the process. Priming leaves no such
     * object behind.
     */
    void clear() {
        synchronized (pending) {
            if (stopped) return;
            stopped = true;
            pending.clear();
        }
        worker.shutdown();
    }

    /**
     * Identity of a peer across browse rounds: the certificate when it published one, and
     * the host or service name when it did not — iOS stopped advertising a fingerprint, so
     * the name is the only stable handle left.
     */
    private static String keyOf(AirDropPeer peer) {
        String identity = peer.certHash;
        if (identity == null || identity.isEmpty()) identity = peer.host;
        if (identity == null || identity.isEmpty()) identity = peer.serviceName;
        return identity == null ? "" : identity.toLowerCase(Locale.ROOT);
    }
}
