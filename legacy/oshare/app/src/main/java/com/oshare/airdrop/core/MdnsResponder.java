package com.oshare.airdrop.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * The {@code _airdrop._tcp} responder that runs on the AWDL link.
 *
 * <p>One socket owns {@code [::]:5353}. It joins {@code ff02::fb} on a single
 * interface — the AirDrop link, never WLAN — because multicast membership is what
 * decides which arriving packets this socket sees: the system resolver on
 * {@code wlan0} may join the same group without us receiving any of its traffic,
 * and vice versa.
 *
 * <p>Three things happen in the receive loop: unsolicited announcements from other
 * responders are harvested into {@link #peers()}, queries that name our records are
 * answered, everything else is dropped. Records we publish are rebuilt from
 * {@link Source} on every send, so a renewed certificate or a changed link-local
 * address shows up in the next answer instead of being baked in at start.
 *
 * <p>{@link #handle(byte[])} is the whole protocol and needs no socket, which is how
 * the tests drive it.
 */
public final class MdnsResponder implements AutoCloseable {
    public static final int PORT = 5353;
    public static final String GROUP = "ff02::fb";
    /**
     * The interface the AWDL node creates; see {@code AwdlNode}.
     *
     * <p>Not {@code wonder0}. That vif carries the same link-local as this one and is
     * the address the phone used to answer to, but the driver delivers almost nothing
     * back into an ethernet vif — over a 35 second run 9295 frames arrived on the radio
     * and {@code wonder0}'s receive counter moved by eight — and what it does emit on
     * the way out is an ordinary 802.11 data frame, which the peer drops one layer above
     * the MAC. A tun carries plain IPv6 both ways, and the node puts the AWDL framing
     * underneath it.
     */
    public static final String DEFAULT_INTERFACE = "awdltun0";

    /** Longest TTL we hand out (PTR/TXT), reused to expire peers we learned about. */
    private static final long PEER_TTL_MS = 4_500_000L;
    private static final int MAX_PACKET = 9000;

    /**
     * How many copies of a withdrawal go out in total: one immediately, the rest on the
     * following announcements. See {@link #repeatWithdrawals()}.
     */
    private static final int WITHDRAW_REPEATS = 3;

    /**
     * How long the throwaway port stays open after the first question goes out.
     *
     * <p>Three and a half seconds, because one and three quarters is what it took. The
     * single run in which a phone answered a question from a port that was not 5353
     * answered it 1.76 s after the ask, and the version of this probe that waited nine
     * hundred milliseconds therefore reported "no answer" for an answer that was already
     * on its way -- a socket that closes early cannot tell "ignored" from "answered
     * late", and the two need opposite fixes.
     *
     * <p>The wait is affordable now that the probe has its own thread: it runs beside the
     * send loop instead of inside it, so a long listening window costs a port and nothing
     * else. Inside the send loop it had to be short, because every millisecond of it was
     * a millisecond the relay was not relaying.
     */
    private static final int PROBE_WINDOW_MS = 3_500;

    /**
     * How long to wait for an answer before asking again, within the window above.
     *
     * <p>The question is repeated rather than merely listened for, because one frame gets
     * one chance to land while the peer's radio is awake, and the peer's availability
     * windows run on a cycle this side does not schedule. A repeat costs a hundred bytes
     * and turns one chance into three.
     */
    private static final int PROBE_RETRY_MS = 1_000;

    /**
     * The DNS-SD meta-query: the list of service types a responder has registered.
     *
     * <p>Used by {@link #probeOnce()} as the control question. It is not part of AirDrop
     * and no peer needs to answer it for anything to work -- its only job is to be
     * answerable by a responder that has registered nothing we asked for.
     */
    private static final String ENUMERATION = "_services._dns-sd._udp.local";

    /** How often the interface pin is re-checked; a rebuild takes longer than this. */
    private static final long ARM_CHECK_MS = 2_000L;

    /** Everything we publish, re-read on each announcement so it can change over time. */
    public interface Source {
        /** TXT pairs: at least {@code h} (certificate hash) and {@code fl} (capability bits). */
        Map<String, String> txt();

        /** IPv6 link-local addresses of the advertising interface. */
        List<String> addresses();
    }

    /** Diagnostics and discovery callbacks; neither is allowed to break the loop. */
    public interface Listener {
        /** A peer appeared, or its records changed since we last saw it. */
        void onPeer(AirDropPeer peer);

        void onError(String message, Throwable error);
    }

    /** An answer ready to send, plus the destination the querier asked for. */
    public static final class Reply {
        public final byte[] packet;
        public final boolean unicast;

        Reply(byte[] packet, boolean unicast) {
            this.packet = packet;
            this.unicast = unicast;
        }
    }

    /** One peer as last heard, with the timestamp that decides its expiry. */
    private static final class Seen {
        final AirDropPeer peer;
        final long atMillis;

        Seen(AirDropPeer peer, long atMillis) {
            this.peer = peer;
            this.atMillis = atMillis;
        }
    }

    /** What a query asks of us: which records, and whether it wants them directly. */
    private static final class Ask {
        int types;
        boolean unicast;

        boolean isEmpty() {
            return types == 0;
        }
    }

    private final String instance;
    private final String host;
    private final int port;
    private final Source source;
    private final Listener listener;

    private final Map<String, Seen> seen = new LinkedHashMap<>();

    /**
     * Instances we are taking back, each with the number of copies still owed.
     *
     * <p>Kept here rather than sent in a loop at the call site because the repeats have to
     * be spread over the link's schedule: {@link #withdraw(String)} is called from startup
     * and from shutdown, and neither moment is a good place to block for six seconds.
     */
    private final Map<String, Integer> retiring = new LinkedHashMap<>();

    private volatile MulticastSocket socket;
    private volatile InetAddress group;
    private volatile boolean running;

    /**
     * Link the socket was pinned to, and the interface index that pinning landed on.
     *
     * <p>The index is worth remembering because the pin lives in the kernel as a number.
     * Tearing the AWDL link down and bringing it back gives {@code wonder0} a new index,
     * while this socket keeps pointing at the old one — sends then fail with
     * {@code ENETUNREACH} although the link looks healthy from the outside, and every
     * query and announcement is silently lost. {@link #rearm()} compares the two.
     */
    private volatile String interfaceName;
    private volatile int armedIndex = -1;

    /** When the pin was last verified; the check is cheap but pointless every tick. */
    private volatile long armedCheckedAt;

    /**
     * When the last announcement actually left, in wall-clock millis; {@code 0} forces one
     * on the next tick.
     *
     * <p>A field rather than a local of {@link #tickLoop()} so {@link #setPaused(boolean)}
     * can force an announcement the moment a conversation ends: the pause exists to give
     * slots back to TCP, and the one thing that must not happen when the slots are handed
     * back is a device that is still connected-looking but no longer announced.
     */
    private volatile long announcedAt;

    /**
     * True while a transfer is actually running, i.e. while this side would rather not
     * spend slots on itself.
     *
     * <p>The announcement is not free. Three seconds of interval produces a multicast frame
     * of about four hundred bytes, and on this link a frame is not sent by asking for the
     * air -- it is placed in a window that the peer's radio has agreed to be awake for, and
     * it competes with the TLS flight that the peer is waiting on.
     *
     * <p>What this flag deliberately does <b>not</b> track is a bare accepted connection.
     * That was the first version, and a phone stuck in its retry loop broke it: thirty-three
     * connections arrived over one session, only one ever finished a handshake, and each
     * held the announcement down for the twelve seconds it took to be abandoned -- so the
     * announcer was effectively silent, at fifteen seconds a frame, which is longer than
     * anyone waits before concluding the device is not in the sheet. A connection that
     * cannot handshake is a failure with no transfer behind it, and holding discovery
     * hostage to it loses the only thing the announcement exists for. The caller therefore
     * flips this only after a handshake completes, where "a transfer is running" is true
     * rather than merely hoped for.
     */
    private volatile boolean paused;

    /**
     * Longest this side will stay silent while paused.
     *
     * <p>Six seconds, twice the normal interval. It was fifteen, on the reasoning that a
     * normal pause is short and a pathological one should still refresh before a sheet
     * goes stale. Measured against a phone in its retry loop, fifteen was on the wrong
     * side of the only number that matters: a person gives a share sheet a few seconds
     * before deciding the device is not there, so a pause that long does not keep the
     * record fresh, it makes the device absent. Two intervals still leaves the transfer
     * most of the air -- a twenty-megabyte upload runs for minutes, not seconds -- while
     * never letting a sheet go blind. The record itself lives four thousand five hundred
     * seconds, so nothing expires from a cache either way.
     */
    private static final long PAUSED_ANNOUNCE_MS = 6_000L;
    private volatile Thread receiveThread;
    private volatile Thread tickThread;
    private volatile Thread probeThread;
    private volatile long announceIntervalMs = 60_000L;
    private volatile long queryIntervalMs = 30_000L;
    private volatile long probeIntervalMs = 0L;
    /** Counts probe windows, so alternate ones can ask a different question. */
    private volatile int probeRounds;

    public MdnsResponder(String instance, String host, int port, Source source) {
        this(instance, host, port, source, null);
    }

    public MdnsResponder(String instance, String host, int port, Source source, Listener listener) {
        if (instance == null || instance.isEmpty()) throw new IllegalArgumentException("empty instance name");
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("empty host name");
        this.instance = instance;
        this.host = host;
        this.port = port;
        this.source = source;
        this.listener = listener;
    }

    /** Overrides the periodic schedule; {@code 0} disables that half of it. */
    public void setIntervals(long announceMs, long queryMs) {
        this.announceIntervalMs = Math.max(0, announceMs);
        this.queryIntervalMs = Math.max(0, queryMs);
    }

    /** See {@link #probeOnce()}; {@code 0} leaves the throwaway-port probe off. */
    public void setProbeInterval(long probeMs) {
        this.probeIntervalMs = Math.max(0, probeMs);
        // Started from here rather than from start(), because the interval is chosen
        // after the socket exists: the caller wants the link up before it decides how
        // often to ask, and a thread launched in start() would read the default of zero
        // and exit on its first pass.
        if (probeIntervalMs > 0 && running && probeThread == null) {
            synchronized (this) {
                if (probeThread == null) probeThread = daemon(this::probeLoop, "mdns-probe");
            }
        }
    }

    /**
     * Holds the periodic announcement back while somebody is talking to us.
     *
     * <p>Called with {@code true} while a conversation is open and {@code false} when it
     * closes; see {@link #paused} for why this is worth doing at all. Un-pausing forces an
     * announcement on the next tick rather than waiting out the interval, because the
     * previous sentence of the protocol is "I am here" and it has been withheld.
     *
     * <p>Queries are deliberately not suppressed: a query is the peer asking, and going
     * quiet on a direct question is not saving a slot, it is failing to answer. The
     * throwaway-port probe and the outbound query are the caller's to switch off through
     * {@link #setIntervals} and {@link #setProbeInterval}, and on the receive side both
     * already are.
     */
    public void setPaused(boolean pause) {
        boolean was = this.paused;
        this.paused = pause;
        if (was && !pause) {
            // Wakes the tick loop on its next quarter-second pass with an announcement
            // already due.
            announcedAt = 0L;
            Trace.write("* mdns announcement resumed");
        } else if (!was && pause) {
            Trace.write("* mdns announcement paused (a transfer is running)");
        }
    }

    /** True while announcements are being held back; diagnostics only. */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Asks the throwaway-port question on its own schedule, beside the send loop.
     *
     * <p>Its own thread because a probe now listens for seconds: inside the send loop
     * that would be seconds of a paused link every cycle, and pausing the relay is the
     * one cost this link cannot pay. The two threads meet only in the peer table, which
     * is synchronized, and in the socket, which this one opens fresh each time.
     */
    private void probeLoop() {
        while (running) {
            long interval = probeIntervalMs;
            if (interval <= 0) {
                // Turned off mid-run: keep the thread and look again, rather than having
                // to reason about who restarts it if it is turned back on.
                pause(500);
                continue;
            }
            if (!pause(interval)) break;
            if (running) probeOnce();
        }
    }

    /** Sleeps for {@code millis}; returns false when the sleep was cut short by a stop. */
    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException stop) {
            return false;
        }
    }

    /** Binds 5353 on {@code wonder0} and starts answering. */
    public void start() throws IOException {
        start(DEFAULT_INTERFACE, PORT);
    }

    /**
     * Binds an explicit interface and port. A {@code null} interface skips the group
     * join and multicast send, which leaves unicast answers working on loopback.
     */
    public void start(String interfaceName, int port) throws IOException {
        if (running) throw new IllegalStateException("already started");
        NetworkInterface network = null;
        if (interfaceName != null) {
            network = NetworkInterface.getByName(interfaceName);
            if (network == null) throw new IOException("no interface named " + interfaceName);
        }

        MulticastSocket socket = new MulticastSocket(null);
        try {
            // Must precede bind: v1.2.0's mosey_server and the system resolver both
            // hold 5353, and without reuse the bind fails while the link is shared.
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            if (network != null) {
                socket.setNetworkInterface(network);
                // Multicast loopback stays at the platform default: our own datagrams do
                // come back, and harvest() drops them by comparing instance names, which
                // is cheaper than the deprecated setLoopbackMode() call would be.
                InetAddress multicast = groupFor(interfaceName);
                socket.joinGroup(new InetSocketAddress(multicast, PORT), network);
                this.group = multicast;
                this.interfaceName = interfaceName;
                this.armedIndex = network.getIndex();
                this.armedCheckedAt = System.currentTimeMillis();
                // Every packet this socket sends carries its hop limit. The kernel's
                // default for IPv6 multicast is 1, and a peer that enforces the mDNS
                // rule drops such a packet without a word of complaint.
                if (!MulticastHop.raise(socket, MulticastHop.MDNS)) {
                    report("hop limit left at the kernel default", null);
                }
            }
        } catch (IOException failed) {
            closeQuietly(socket);
            throw failed;
        }
        this.socket = socket;
        this.running = true;

        receiveThread = daemon(this::receiveLoop, "mdns-recv");
        tickThread = daemon(this::tickLoop, "mdns-tick");
    }

    private static InetAddress groupFor(String interfaceName) throws IOException {
        try {
            // A scoped group address pins the send to that interface even when the
            // routing table has no opinion about ff02::fb (wonder0 has no route).
            return InetAddress.getByName(GROUP + "%" + interfaceName);
        } catch (IOException unscoped) {
            return InetAddress.getByName(GROUP);
        }
    }

    /**
     * Re-pins the socket when the link it was bound to has been rebuilt underneath it.
     *
     * <p>The link on this device is not permanent: {@code awdl-link.sh} deletes and
     * re-creates the virtual interface on every restart, and each new interface gets a
     * new index. Whatever this socket pinned itself to at {@link #start} then names an
     * interface that no longer exists, and the kernel answers every multicast send with
     * {@code ENETUNREACH} — a failure that looks like a permission problem but is only
     * a stale number.
     *
     * <p>Re-pinning on the same socket, rather than replacing it, keeps the bound port
     * and the receive loop untouched: no packet is dropped between two sockets, and no
     * thread has to be restarted.
     *
     * @return true when the pin moved and a failed send is worth retrying
     */
    private boolean rearm() {
        String name = this.interfaceName;
        MulticastSocket socket = this.socket;
        if (name == null || socket == null) return false;
        try {
            NetworkInterface network = NetworkInterface.getByName(name);
            if (network == null) {
                // Mid-rebuild: forget the index so the next check re-pins unconditionally.
                this.armedIndex = -1;
                return false;
            }
            int index = network.getIndex();
            if (index == this.armedIndex) return false;
            socket.setNetworkInterface(network);
            InetAddress multicast = groupFor(name);
            socket.joinGroup(new InetSocketAddress(multicast, PORT), network);
            this.group = multicast;
            this.armedIndex = index;
            return true;
        } catch (IOException failed) {
            report("re-pin to " + name + " failed", failed);
            return false;
        }
    }

    /** True when {@code error} is the kernel saying it has no route, not a real failure. */
    private static boolean unreachable(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("ENETUNREACH")) return true;
        }
        return false;
    }

    private static Thread daemon(Runnable body, String name) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public boolean isRunning() {
        return running;
    }

    /** Port actually bound; differs from {@link #PORT} when {@code 0} was requested. */
    public int port() {
        MulticastSocket socket = this.socket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    /** Service instance as it appears on the wire, i.e. with dots and backslashes escaped. */
    String serviceName() {
        return AirDropPeer.escapeLabel(instance) + "." + AirDropPeer.SERVICE;
    }

    /** Our advertisement: PTR, SRV, TXT and one AAAA per known address. */
    public byte[] announcement() {
        return AirDropPeer.announce(instance, host, port, source.txt(), source.addresses(), 0, AirDropPeer.RECORD_ALL);
    }

    /**
     * Publishes our records once, and one repeat of every withdrawal still owed.
     *
     * <p>The repeats ride on this schedule rather than on a loop of their own because this
     * is the tick that is already timed to the link: an announcement goes out every three
     * seconds, and a withdrawal has exactly the same problem an announcement has -- a
     * single multicast frame only lands if the peer happens to be on our channel in that
     * slot, and AWDL gives no guarantee that it is.
     */
    public void announce() {
        try {
            transmit(announcement(), null);
        } catch (IOException failed) {
            report("announce failed", failed);
        }
        repeatWithdrawals();
    }

    /**
     * One more copy of each withdrawal still owed, dropping the ones that are paid off.
     *
     * <p>Three rounds rather than one, and rather than forever. Once is what RFC 6762
     * §10.1 asks for on an ordinary link, where a multicast frame is heard by everyone
     * awake; here the peer sleeps in slots, and the whole point of this record is that
     * forgetting it costs seventy-five minutes. Forever is not an option for the opposite
     * reason: a withdrawn instance is a name we are not using, and repeating it every
     * three seconds for the life of the process would be a device that keeps claiming a
     * name it has already given up.
     */
    private void repeatWithdrawals() {
        Map<String, Integer> owed;
        synchronized (retiring) {
            if (retiring.isEmpty()) return;
            owed = new LinkedHashMap<>(retiring);
        }
        for (Map.Entry<String, Integer> entry : owed.entrySet()) {
            String name = entry.getKey();
            int left = entry.getValue();
            if (left <= 0 || !sendWithdrawal(name)) {
                // Refused means the send itself is failing; keeping the entry lets the next
                // tick try again rather than dropping the only record that takes it back.
                if (left <= 0) dropWithdrawal(name);
                continue;
            }
            if (left == 1) dropWithdrawal(name);
            else synchronized (retiring) { retiring.put(name, left - 1); }
        }
    }

    private void dropWithdrawal(String name) {
        synchronized (retiring) {
            retiring.remove(name);
        }
    }

    /** The instance name we publish, as twelve hex digits; see {@code ServiceId}. */
    public String instance() {
        return instance;
    }

    /**
     * Takes back an instance this device published earlier, by re-sending its records
     * with a zero TTL (RFC 6762 §10.1).
     *
     * <p>Nothing else removes an instance from a peer's list. Stopping the responder only
     * stops the repeats; every record it ever sent stays valid for the lifetime it carried,
     * and this responder publishes the PTR with 4500 seconds -- an hour and a quarter of a
     * phone showing a device that stopped existing. The records sent here are the same
     * shape as a normal announcement with the old instance name, because that is what a
     * cache matches on: same name, same type, zero lifetime, delete.
     *
     * <p>The TXT and address records go along with it. A peer that had resolved the old
     * instance holds all three, and withdrawing only the PTR leaves the SRV target and the
     * address pinned under a name that no longer points anywhere.
     *
     * <p>Sent now and repeated by {@link #repeatWithdrawals()} for the next couple of
     * announcements, because the first copy leaves at the least reliable moment there is:
     * during startup, when the link has just come up and the peer has not yet had a reason
     * to be listening on our slot.
     *
     * @return true when the packet left, false when the caller should try again later
     */
    public boolean withdraw(String oldInstance) {
        if (oldInstance == null || oldInstance.isEmpty() || oldInstance.equals(instance)) {
            // Empty is "nothing to take back". Equal is the case that matters most: a
            // withdrawal names an instance, and withdrawing the one we are still using
            // would delete us from the peer -- the opposite of the intent.
            return true;
        }
        synchronized (retiring) {
            retiring.put(oldInstance, WITHDRAW_REPEATS - 1);
        }
        return sendWithdrawal(oldInstance);
    }

    /** One copy of a withdrawal on the wire; the bookkeeping lives in the callers. */
    private boolean sendWithdrawal(String oldInstance) {
        byte[] packet = AirDropPeer.announce(
            oldInstance, host, port, source.txt(), source.addresses(), 0,
            AirDropPeer.RECORD_ALL, 0);
        try {
            transmit(packet, null);
            Trace.write("* mdns withdrew " + oldInstance + " (" + packet.length + " B, ttl 0)");
            return true;
        } catch (IOException failed) {
            report("withdraw failed", failed);
            return false;
        }
    }

    /** Asks the link who else speaks AirDrop. */
    public void query() {
        try {
            transmit(AirDropPeer.query(), null);
        } catch (IOException failed) {
            report("query failed", failed);
        }
    }

    /**
     * Asks the same question from a throwaway port, and listens on it for the answer.
     *
     * <p>This exists because of a rule we are on the wrong side of. A query sent from
     * port 5353 is an ordinary mDNS query, and an ordinary responder may answer it to
     * the group -- which is correct, and which arrives here never, because the AWDL
     * bridge forwards unicast frames only: across every capture of this link the count
     * of inbound frames addressed to a 33:33 group is zero, while the phone's own
     * answers, whenever it gave any, were unicast to us. RFC 6762 §6.7 covers exactly
     * this case from the other end: a query whose source port is not 5353 is a
     * one-shot question from something that is not a responder, and the answer MUST be
     * unicast back to that port. So the question is asked from an ephemeral port, and
     * the answer has nowhere to go but here.
     *
     * <p>It also sidesteps a second problem: a peer that has already decided we are
     * not worth answering will answer a new questioner that it has never judged.
     */
    public void probeOnce() {
        String name = this.interfaceName;
        if (name == null || !running) return;
        MulticastSocket probe = null;
        try {
            NetworkInterface network = NetworkInterface.getByName(name);
            if (network == null) return;
            probe = new MulticastSocket(null);
            probe.setReuseAddress(true);
            // Bound to the wildcard rather than to a concrete address: which link-local
            // the kernel picks as the source is its business, and an answer addressed to
            // the ephemeral port has to reach this socket either way.
            probe.bind(new InetSocketAddress(InetAddress.getByName("::"), 0));
            probe.setNetworkInterface(network);
            InetAddress multicast = groupFor(name);
            probe.joinGroup(new InetSocketAddress(multicast, PORT), network);
            MulticastHop.raise(probe, MulticastHop.MDNS);
            // Every second question asks for the list of services instead of for AirDrop.
            // The two answers mean different things, and until now there was only one
            // question, so "the phone is not registered as an AirDrop receiver" and "the
            // phone does not answer mDNS over this link at all" produced the same silence
            // and the same next step, which was to change our own packets again. The
            // enumeration query is answered by any responder on the interface whatever it
            // has registered, so it splits those two apart: an answer to it means the
            // link, the group address and the hop limit are all fine and the missing thing
            // is the AirDrop registration on the other side.
            boolean enumerating = (probeRounds++ & 1) == 1;
            byte[] question = enumerating
                ? DnsMessage.query(ENUMERATION, DnsMessage.TYPE_PTR)
                : AirDropPeer.query();
            probe.send(new DatagramPacket(question, question.length, multicast, PORT));
            int port = probe.getLocalPort();
            Trace.write("* mdns probe from :" + port + " as " + question.length
                + " B asking " + (enumerating ? ENUMERATION : AirDropPeer.SERVICE)
                + ", listening " + PROBE_WINDOW_MS + " ms");
            long until = System.currentTimeMillis() + PROBE_WINDOW_MS;
            long resendAt = System.currentTimeMillis() + PROBE_RETRY_MS;
            int heard = 0;
            while (running) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                // A timeout is the schedule, not an error: it is what wakes this loop up to
                // ask again while the window is still open.
                probe.setSoTimeout((int) Math.min(PROBE_RETRY_MS, left));
                DatagramPacket incoming = new DatagramPacket(new byte[MAX_PACKET], MAX_PACKET);
                try {
                    probe.receive(incoming);
                } catch (SocketTimeoutException quiet) {
                    if (System.currentTimeMillis() < resendAt) continue;
                    resendAt = System.currentTimeMillis() + PROBE_RETRY_MS;
                    probe.send(new DatagramPacket(question, question.length, multicast, PORT));
                    Trace.write("* mdns probe again from :" + port);
                    continue;
                }
                heard++;
                byte[] packet = new byte[incoming.getLength()];
                System.arraycopy(incoming.getData(), incoming.getOffset(), packet, 0, packet.length);
                String from = String.valueOf(incoming.getSocketAddress());
                if (incoming.getPort() == port) {
                    // Our own question, looped back by the multicast path. Counting it as
                    // an answer would turn "nobody replied" into "somebody replied", which
                    // is the one mistake this probe exists to avoid making.
                    Trace.write("* mdns probe loopback from " + from + " ignored");
                    heard--;
                    continue;
                }
                // Harvesting is the point: whatever answered is a peer whether or not it
                // asked us anything, and records read here are the only ones this side
                // will ever see from a phone that has stopped talking to the group.
                Reply reply = handle(packet);
                Trace.write("* mdns probe heard " + packet.length + " B from " + from
                    + (reply == null ? " (no answer due)" : " (answering " + reply.packet.length + " B)"));
                if (reply != null) {
                    try {
                        transmit(reply.packet, reply.unicast ? incoming.getSocketAddress() : null);
                    } catch (IOException failed) {
                        report("probe answer failed", failed);
                    }
                }
            }
            // Said out loud on the way out because "eight probes and no answers" and
            // "eight probes that heard nothing and then closed" look the same in a log
            // that only prints the questions.
            Trace.write("* mdns probe done from :" + port + ", heard " + heard);
        } catch (IOException | RuntimeException failed) {
            report("mdns probe failed", failed);
        } finally {
            closeQuietly(probe);
        }
    }

    /** Peers heard from, most recently updated last. */
    public List<AirDropPeer> peers() {
        synchronized (seen) {
            List<AirDropPeer> out = new ArrayList<>(seen.size());
            for (Seen entry : seen.values()) out.add(entry.peer);
            return out;
        }
    }

    /** Drops peers we have not heard from within the PTR TTL. */
    public void prune(long nowMillis) {
        synchronized (seen) {
            seen.entrySet().removeIf(entry -> nowMillis - entry.getValue().atMillis > PEER_TTL_MS);
        }
    }

    /**
     * Consumes one datagram: harvests whatever it advertises, and answers it when it
     * asks about us. Returns {@code null} when no answer is due.
     */
    public Reply handle(byte[] packet) {
        DnsMessage message;
        try {
            message = DnsMessage.parse(packet);
        } catch (DnsMessage.FormatException malformed) {
            report("malformed mDNS packet", malformed);
            return null;
        }

        harvest(message);
        if (message.isResponse()) return null;

        Ask ask = new Ask();
        for (DnsMessage.Question question : message.questions()) {
            int types = typesFor(question);
            if (types == 0) continue;
            ask.types |= types;
            ask.unicast |= question.unicast;
        }
        if (ask.isEmpty()) return null;

        // mDNS keeps id 0 on multicast responses; a unicast answer echoes the query id
        // so the querier can match it against the socket it used.
        int id = ask.unicast ? message.id() : 0;
        byte[] answer = AirDropPeer.announce(
            instance, host, port, source.txt(), source.addresses(), id, ask.types);
        return new Reply(answer, ask.unicast);
    }

    /** Which of our record kinds a single question asks for; {@code 0} means none. */
    private int typesFor(DnsMessage.Question question) {
        boolean any = question.type == DnsMessage.TYPE_ANY;
        if (DnsMessage.sameName(question.name, AirDropPeer.SERVICE)) {
            if (!any && question.type != DnsMessage.TYPE_PTR) return 0;
            // Apple answers a PTR question with the instance records as additionals,
            // which is what a sender needs before it can open a connection.
            return AirDropPeer.RECORD_ALL;
        }
        if (DnsMessage.sameName(question.name, serviceName())) {
            if (any) return AirDropPeer.RECORD_SRV | AirDropPeer.RECORD_TXT | AirDropPeer.RECORD_ADDRESS;
            // An SRV answer is useless without the TXT (it carries the certificate hash)
            // and the address, so those ride along as additionals. TXT and AAAA queries
            // come from a peer that already holds the rest, and get exactly what they asked.
            if (question.type == DnsMessage.TYPE_SRV) {
                return AirDropPeer.RECORD_SRV | AirDropPeer.RECORD_TXT | AirDropPeer.RECORD_ADDRESS;
            }
            if (question.type == DnsMessage.TYPE_TXT) return AirDropPeer.RECORD_TXT;
            if (question.type == DnsMessage.TYPE_AAAA) return AirDropPeer.RECORD_ADDRESS;
            return 0;
        }
        if (DnsMessage.sameName(question.name, host)) {
            if (!any && question.type != DnsMessage.TYPE_AAAA) return 0;
            return AirDropPeer.RECORD_ADDRESS;
        }
        return 0;
    }

    private void harvest(DnsMessage message) {
        List<AirDropPeer> found;
        try {
            found = AirDropPeer.from(message);
        } catch (DnsMessage.FormatException broken) {
            report("unreadable record in mDNS packet", broken);
            return;
        }
        if (found.isEmpty()) return;

        String self = serviceName().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        for (AirDropPeer peer : found) {
            String key = peer.serviceName.toLowerCase(Locale.ROOT);
            if (key.equals(self)) continue;
            boolean changed;
            synchronized (seen) {
                // Re-insert so the list stays ordered by recency; the key itself never
                // grows, a peer that changes its port stays one entry.
                Seen previous = seen.remove(key);
                seen.put(key, new Seen(peer, now));
                changed = previous == null || differs(previous.peer, peer);
            }
            if (changed && listener != null) {
                try {
                    listener.onPeer(peer);
                } catch (RuntimeException ignored) {
                    // A misbehaving observer must not take the responder down.
                }
            }
        }
    }

    private static boolean differs(AirDropPeer before, AirDropPeer after) {
        return before.port != after.port
            || !before.host.equals(after.host)
            || !before.certHash.equals(after.certHash)
            || before.flags != after.flags
            || !before.addresses.equals(after.addresses);
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET];
        while (running) {
            DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(incoming);
            } catch (IOException stopped) {
                if (running) report("receive failed", stopped);
                break;
            }
            byte[] packet = new byte[incoming.getLength()];
            System.arraycopy(incoming.getData(), incoming.getOffset(), packet, 0, packet.length);

            Reply reply = handle(packet);
            if (reply == null) continue;
            try {
                transmit(reply.packet, reply.unicast ? incoming.getSocketAddress() : null);
            } catch (IOException failed) {
                report("reply failed", failed);
            }
        }
    }

    private void tickLoop() {
        long queriedAt = 0;
        while (running) {
            long now = System.currentTimeMillis();
            // Cheap insurance taken before anything is sent: the link can be rebuilt at
            // any moment, and a stale pin turns every send below into ENETUNREACH.
            if (now - armedCheckedAt >= ARM_CHECK_MS) {
                armedCheckedAt = now;
                rearm();
            }
            // While paused the interval becomes a floor, not an off switch: the point is
            // to stop competing with a handshake, not to disappear from a sheet that is
            // already open. The 250 ms tick is the resolution of both numbers, so a
            // pause of any length is honoured within a quarter second.
            long interval = paused ? Math.max(PAUSED_ANNOUNCE_MS, announceIntervalMs) : announceIntervalMs;
            if (interval > 0 && now - announcedAt >= interval) {
                announcedAt = now;
                announce();
            }
            if (queryIntervalMs > 0 && now - queriedAt >= queryIntervalMs) {
                queriedAt = now;
                query();
            }
            // The throwaway-port probe is not on this schedule and not on this thread:
            // see probeLoop(). It listens for seconds at a time, which is exactly what
            // this loop cannot afford to do.
            prune(now);
            try {
                Thread.sleep(250);
            } catch (InterruptedException stop) {
                break;
            }
        }
    }

    /**
     * Sends to the multicast group, or to {@code unicastTo} when the querier asked
     * for a direct answer. Multicast on a {@code null} interface is a no-op error.
     *
     * <p>{@code ENETUNREACH} is retried once: on this device it almost always means the
     * interface behind the pin was rebuilt, which {@link #rearm()} repairs in place. A
     * single retry is enough — if the second attempt fails too, the link is genuinely
     * down and the caller's schedule will come back to it.
     */
    private void transmit(byte[] packet, SocketAddress unicastTo) throws IOException {
        MulticastSocket socket = this.socket;
        if (socket == null) throw new IOException("responder not started");
        try {
            sendOnce(socket, packet, unicastTo);
        } catch (IOException failed) {
            if (!unreachable(failed) || !rearm()) throw failed;
            sendOnce(socket, packet, unicastTo);
        }
    }

    private void sendOnce(MulticastSocket socket, byte[] packet, SocketAddress unicastTo)
        throws IOException {
        DatagramPacket datagram;
        if (unicastTo instanceof InetSocketAddress) {
            datagram = new DatagramPacket(packet, packet.length, unicastTo);
        } else {
            InetAddress group = this.group;
            if (group == null) throw new IOException("no multicast group on this interface");
            datagram = new DatagramPacket(packet, packet.length, group, PORT);
        }
        socket.send(datagram);
    }

    private void report(String message, Throwable error) {
        if (listener == null) return;
        try {
            listener.onError(message, error);
        } catch (RuntimeException ignored) {
            // Same rule as onPeer: observers cannot break the loop.
        }
    }

    @Override
    public void close() {
        running = false;
        closeQuietly(socket);
        socket = null;
        group = null;
        armedIndex = -1;
        Thread receiving = receiveThread;
        if (receiving != null) receiving.interrupt();
        receiveThread = null;
        Thread ticking = tickThread;
        if (ticking != null) ticking.interrupt();
        tickThread = null;
        synchronized (seen) {
            seen.clear();
        }
        synchronized (retiring) {
            retiring.clear();
        }
    }

    private static void closeQuietly(MulticastSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (RuntimeException ignored) {
            // shutting down anyway
        }
    }
}
