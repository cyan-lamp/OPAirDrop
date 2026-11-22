package com.oshare.airdrop;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code _airdrop._tcp} responder: the half of discovery that makes this device appear in
 * another phone's share sheet.
 *
 * <p># Why this exists at all
 *
 * <p>A share sheet is a listener, not a poller. Measured against a real phone, the whole log of
 * a session held exactly one mDNS question: the sheet is built from whatever announcements arrive
 * while it is open, and it evicts an entry a couple of seconds after the last one. There is
 * therefore no request to answer that would have made the device appear — being in the list *is*
 * the announcement arriving. A receiver that never announces is a receiver nobody can send to,
 * however correctly it listens on 8770.
 *
 * <p># Why the socket is here and the records are in Rust
 *
 * <p>Which interface to join the group on, and the multicast lock that keeps the kernel from
 * dropping the traffic once the screen goes off, are Android's business. What the four records
 * say, in which order, with which lifetimes, and what a given question is entitled to receive —
 * all of that is {@code crates/ffi/src/respond.rs}, where it is tested on the host against the
 * legacy implementation that was the only version ever proven to appear in the sheet. This class
 * is the socket, the schedule, and the shutdown.
 *
 * <p># Why {@code awdltun0} and not {@code wonder0}
 *
 * <p>{@code wonder0} carries the same link-local and is the address the phone used to answer to,
 * but the driver delivers almost nothing back into an ethernet vif — over a 35 second run 9295
 * frames arrived on the radio and {@code wonder0}'s receive counter moved by eight — and what it
 * does emit on the way out is an ordinary 802.11 data frame, which the peer drops one layer above
 * the MAC. A tun carries plain IPv6 both ways, and {@code awdld} puts the AWDL framing underneath
 * it.
 *
 * <p># The cadence
 *
 * <p>Three seconds. Not the sixty an mDNS responder would use on a wired LAN, and not the eight
 * this app started with: eight left an entry with two seconds of headroom against the phone's own
 * eviction timer, so one lost frame was enough to make the device vanish from a panel that had
 * already listed it. Three gives three independent chances inside that timer, and it is
 * affordable only because this side asks nobody anything — a receiver is found, it does not find.
 * Every question sent from here would be a multicast round the peer has to answer, competing for
 * the same air slots the TLS handshake needs.
 *
 * <p># What this class deliberately does not do
 *
 * <p>It does not harvest the announcements of others into a peer list; that list is {@link Peers}'
 * job, on the interface a Mac would be reachable over. It does not pause while a transfer is
 * running, because {@link Listener} cannot tell a completed handshake from a connection that was
 * abandoned, and pausing on a bare accept is the mistake the legacy stack measured and retracted:
 * a phone in its retry loop opened thirty-three connections, finished one handshake, and kept the
 * announcer silent throughout.
 *
 * <p>It does not send queries, and it does not answer questions about anything but AirDrop.
 */
final class Responder {

    private static final String TAG = "OPAirDrop";

    /** The port mDNS owns. One socket holds it, for answering and announcing alike. */
    static final int PORT = 5353;

    /** The IPv6 multicast group mDNS uses. There is no IPv4 group here on purpose. */
    static final String GROUP = "ff02::fb";

    /** The interface {@code awdld} creates; see the class comment for why not {@code wonder0}. */
    static final String DEFAULT_INTERFACE = "awdltun0";

    /** Longest datagram we will look at. A responder answer with several records fits easily. */
    private static final int MAX_PACKET = 9000;

    /** How often the announcement goes out. See the class comment for where three comes from. */
    private static final long ANNOUNCE_MS = 3_000L;

    /** Resolution of the schedule; also the granularity at which a stop is noticed. */
    private static final long TICK_MS = 250L;

    /**
     * How often the interface pin is re-checked.
     *
     * <p>The pin lives in the kernel as an interface index, and this app rebuilds its link on
     * purpose: {@code awdld} creates {@code awdltun0} on every start, and each new interface gets
     * a new index. A socket still pinned to the old number answers every send with
     * {@code ENETUNREACH} while the link looks healthy from the outside.
     */
    private static final long ARM_CHECK_MS = 2_000L;

    /**
     * How many copies of the withdrawal go out when the socket closes.
     *
     * <p>One is what RFC 6762 §10.1 asks for on a link where a multicast frame is heard by
     * everyone awake. Here the peer sleeps in slots, so one frame is one chance. Three is not
     * spread over announcements the way the legacy stack spread it — the socket is closing, and
     * there is no next tick to ride on — so they leave back to back, which still triples the odds
     * without making a caller wait seconds for a stop to finish.
     */
    private static final int WITHDRAW_COPIES = 3;

    /** {@code RECORD_PTR} and friends: which of the four records an announcement carries. */
    static final int RECORD_PTR = 1;
    static final int RECORD_SRV = 2;
    static final int RECORD_TXT = 4;
    static final int RECORD_ADDRESS = 8;
    static final int RECORD_ALL = RECORD_PTR | RECORD_SRV | RECORD_TXT | RECORD_ADDRESS;

    /** Each record keeps its own published lifetime. */
    static final int TTL_AS_PUBLISHED = -1;

    /**
     * What {@link #txt()} publishes, as one ordered map.
     *
     * <p>Four keys, and each one is load-bearing:
     *
     * <p>{@code flags} is the capability word in decimal — {@code 13311} = {@code 0x33ff},
     * which is the exact word an iPhone publishes for itself. It is decimal because that is how
     * the field is read: the same phone's own entry in a neighbouring record says
     * {@code flags=13311}, and an earlier build of the legacy app published the hex spelling
     * {@code 9e1b}, which parses as {@code 9} — still a legal word, so the mistake never failed
     * loudly, it just silently dropped the one bit that says a sender may ask. A receiver with
     * neither of the two bits the protocol requires (mixed types, pipelining) is discarded
     * before it is ever resolved, which is why this is not decoration.
     *
     * <p>Where {@code 0x33ff} comes from, and why it is copied verbatim rather than reasoned
     * about: a capture of this device's own AWDL link ({@code .workbuddy/tmp/air1.pcap},
     * frame 3) holds an Apple vendor-specific action frame — OUI {@code 00:17:f2}, category
     * {@code 0x7f}, transmitter {@code 86:b2:eb:36:35:3d} — whose TXT payload is
     * {@code flags=13311} next to the service UUID
     * {@code fb21a1ba-07a1-4ee3-b466-d7a7379ff84f}. That transmitter is not this one; this
     * device's own record in the same file is {@code h=857B86CE…} / {@code flags=137} from
     * {@code c2:1e:af:43:00:d0}. So {@code 0x33ff} is what an iPhone says about itself, captured
     * on the wire, and every value below that is not it was a guess.
     *
     * <p>Which bits, from three independent readings of the same field: the OpenDrop-derived
     * {@code AirDropReceiverFlags} table, the {@code omdrop} protocol spec's MDNS-4, and
     * arXiv 2606.26967 §3.2. They agree bit for bit, and they agree on two bits that matter
     * for how quickly a sender can reach this device:
     *
     * <p>{@code 0x002} {@code SupportsDVZip} — the upload body may arrive as DVZip blocks. The
     * spec's MDNS-5 says what its absence costs: "Without {@code SupportsDVZip}, senders upload
     * gzip", and the paper describes the same bit as "enables adaptive compression". A sender
     * that cannot use DVZip buffers the whole archive before it can compress it as one stream.
     *
     * <p>{@code 0x004} {@code SupportsPipelining} — a sender may start uploading while the
     * question is still on screen. The paper is explicit that reception and consent are separate
     * gates: "Upload data streams into memory while the Ask prompt is displayed; the semaphore
     * gates processing, not reception." Advertising it is what tells a sender it may begin
     * early instead of waiting for the answer to come back.
     *
     * <p>Two earlier values, both of them wrong, and the record of what they actually were
     * because the difference is the whole point:
     *
     * <p>{@code 137} = {@code 0x0089} — URL sharing, mixed types, Discover. The spec's own
     * "minimal receiver" example, which is what this was written as. Mandatory bits only, and
     * neither {@code 0x002} nor {@code 0x004}.
     *
     * <p>{@code 2185} = {@code 0x0889} — introduced to add those two bits, and it did not. The
     * arithmetic in the comment that shipped with it claims {@code 137 | 0x002 | 0x004}, but
     * that is {@code 143} = {@code 0x008f}; {@code 2185 & 0x002} and {@code 2185 & 0x004} are
     * both zero, and {@code 2185 ^ 137} is {@code 0x0800}. So the run that "tested DVZip and
     * pipelining" advertised neither, added a bit the reference phone does not set, and any
     * conclusion drawn from it was about nothing. {@code 0x33ff} sets both bits and clears that
     * one.
     *
     * <p>{@code 0x33ff} also sets {@code 0x040 SupportsIris}, {@code 0x100}, {@code 0x200},
     * {@code 0x1000} and {@code 0x2000}, and this comment does not claim to know what the last
     * four do. They are here because they are in the reference word and the alternative is
     * another guess: MDNS-5's "MUST NOT advertise a capability it doesn't implement" is about
     * not promising a pipeline this device will not run, and what these bits buy a sender is
     * unknown — whereas a word that differs from every phone's is a word a sender can branch on.
     * The one bit that is *cleared* is {@code 0x800}, which is clear in the reference word and
     * which the {@code 2185} run set by accident; whatever it means, no phone sets it.
     *
     * <p>{@code fl} is the same word under the name the older drafts used. iOS reads
     * {@code flags}; the drafts this code grew up on say {@code fl}. Both, because a word we
     * publish twice costs twelve bytes and a word a peer cannot find costs the device.
     *
     * <p>{@code h} is the SHA-256 of this device's certificate, which is what a sender pins
     * before it will trust a self-signed handshake. It is the fingerprint of the very identity
     * the TLS socket was built from — handed in rather than looked up here, so the two cannot
     * disagree: publishing one certificate and presenting another makes every sender see a device
     * that does not match its own advertisement, and the rejection happens on their screen where
     * there is nothing to read. Uppercase hex without separators, which is the shape iOS writes.
     *
     * <p>{@code model} is what the device calls itself, {@link Listener#systemModel()} — the
     * same string the {@code /Ask} answer carries as {@code ReceiverModelName}. It is published
     * because it is the one thing a peer is known to read from this record: this crate's own
     * {@code browse.rs} reads {@code model} off every iPhone it finds.
     *
     * <p>It is <em>not</em> the field that decides whether an iPhone transcodes. An earlier
     * note here claimed it was, on the strength of Apple's "HEVC for supported devices, H.264
     * for all other devices" — but publishing {@code model} changed nothing measurable: a
     * 93 MB clip still arrived as 26.5 MB. The branch Apple describes is taken on
     * {@code ReceiverMediaCapabilities} in the {@code /Discover} answer, not on this record,
     * which is why that blob is the thing worth being careful with (see
     * {@code opairdrop_airdrop::MEDIA_CAPABILITIES}).
     */
    private Map<String, String> txt() {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("h", certHash);
        pairs.put("model", Listener.systemModel());
        pairs.put("fl", FLAGS);
        pairs.put("flags", FLAGS);
        return pairs;
    }

    /** {@code 0x33ff} in the decimal the field is read as; see {@link #txt()}. */
    private static final String FLAGS = "13311";

    private final Context context;
    private final String instance;
    private final String host;
    /** The certificate fingerprint published as {@code h}; see {@link #txt()}. */
    private final String certHash;
    private final int port;
    private final String interfaceName;

    /** Told when {@link #aside()} starts saying something; null when nobody is listening. */
    private final Runnable onChange;

    /** Guards the fields below, which the UI thread reads through {@link #running()} etc. */
    private final Object lock = new Object();

    /**
     * The one socket, once it is open.
     *
     * <p>Volatile because the receive loop, the schedule and the sender all read it without
     * taking {@link #lock} — the lock guards the transitions, not every use, and a stale read
     * here would mean a thread happily sending on a socket that {@link #stop()} already closed.
     */
    private volatile MulticastSocket socket;
    private Thread opener;
    private Thread receiveThread;
    private Thread tickThread;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean running;

    /**
     * Pacing of the bind retry, and the thread that runs it.
     *
     * <p>Two seconds, not a backoff. The failure this retries is "the tunnel does not exist
     * yet", and the tunnel appears on a scale of seconds; a backoff would spend a minute
     * away from a link that is already there, and every one of those seconds is a device
     * that answers on 8770 and announces nothing.
     */
    private static final long RETRY_MS = 2000L;
    private Thread retrier;

    /** The group address, scoped to the interface where the platform allows it. */
    private volatile InetAddress group;
    private volatile int armedIndex = -1;
    private volatile long armedCheckedAt;

    /** When the last announcement actually left, in wall-clock millis; {@code 0} forces one. */
    private volatile long announcedAt;

    /** Why the announcer is not doing its job, worded for a person; empty when it is. */
    private volatile String trouble = "";

    /**
     * Names this device used to answer to, waiting for a socket to be withdrawn through.
     *
     * <p>Guarded by {@link #lock}; emptied the first time the socket opens, because after that
     * the withdrawal is a send and there is nothing left to remember.
     */
    private final List<String> ghosts = new ArrayList<>();

    /**
     * @param instance the bare DNS-SD label, i.e. {@link ServiceId#current}; escaped on the wire
     *     by the library, so a name containing a dot stays one name
     * @param ghosts the names this device used to answer to, i.e. {@link ServiceId#stale}; they
     *     are withdrawn as soon as there is a socket to withdraw them through, which is the only
     *     way a phone that cached one of them stops offering a receiver that no longer exists
     * @param host the full {@code something.local} the SRV record targets
     * @param certHash the SHA-256 of the certificate this device will present, i.e.
     *     {@link Identity#certHash()}; must be the same identity the TLS socket is built from,
     *     because a sender pins what it read here before it trusts the handshake
     * @param port the port a sender should connect to, i.e. {@link Listener#PORT}
     * @param onChange told, on whichever thread noticed, that {@link #aside()} has something to
     *     say; may be null. Without it a start that failed would only be visible to a page that
     *     happened to redraw after the failure, and the failure this class exists to report —
     *     no AWDL link — takes longer to happen than the redraw a switch press triggers.
     */
    Responder(Context context, String instance, List<String> ghosts, String host, String certHash,
            int port, String interfaceName, Runnable onChange) {
        this.context = context.getApplicationContext();
        this.instance = instance;
        this.host = host;
        this.certHash = certHash == null ? "" : certHash;
        this.port = port;
        this.interfaceName = interfaceName;
        this.onChange = onChange;
        if (ghosts != null) {
            for (String ghost : ghosts) {
                if (ghost != null && !ghost.isEmpty() && !ghost.equals(instance)) {
                    this.ghosts.add(ghost);
                }
            }
        }
    }

    /**
     * The mDNS host label for a device name.
     *
     * <p>Dots become hyphens because this label is concatenated with {@code .local} and a dot
     * would make it two labels; everything that would need escaping on the wire is dropped. The
     * name a person reads travels to the sender in the {@code /Discover} answer, not in this
     * label, so nothing is lost by being strict here.
     */
    static String hostNameFor(String deviceName) {
        StringBuilder label = new StringBuilder();
        String trimmed = deviceName == null ? "" : deviceName.trim();
        for (int i = 0; i < trimmed.length(); ) {
            int codePoint = trimmed.codePointAt(i);
            i += Character.charCount(codePoint);
            boolean ascii = codePoint < 0x80;
            boolean allowed = ascii
                    ? (Character.isLetterOrDigit(codePoint) || codePoint == '-' || codePoint == '_')
                    : !Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint);
            if (allowed) {
                label.appendCodePoint(codePoint);
            } else if (label.length() > 0 && label.charAt(label.length() - 1) != '-') {
                label.append('-');
            }
        }
        int start = 0;
        while (start < label.length() && label.charAt(start) == '-') {
            start++;
        }
        label.delete(0, start);
        while (label.length() > 0 && label.charAt(label.length() - 1) == '-') {
            label.setLength(label.length() - 1);
        }
        String host = label.length() == 0 ? "opairdrop" : label.toString();
        // A single DNS label tops out at 63 bytes; truncate on a code point boundary.
        while (host.getBytes(StandardCharsets.UTF_8).length > 63) {
            host = host.substring(0, host.offsetByCodePoints(host.length(), -1));
        }
        return host.toLowerCase(Locale.ROOT) + ".local";
    }

    /**
     * The IPv6 link-local addresses of {@code name}, without the scope suffix.
     *
     * <p>The AAAA record carries the bare address: the peer learns the scope from the interface
     * the packet arrived on, and {@code fe80::1%awdltun0} is not a legal AAAA rdata.
     */
    static List<String> linkLocalAddresses(String interfaceName) {
        if (interfaceName == null) {
            return Collections.emptyList();
        }
        NetworkInterface network;
        try {
            network = NetworkInterface.getByName(interfaceName);
        } catch (SocketException unavailable) {
            return Collections.emptyList();
        }
        if (network == null) {
            return Collections.emptyList();
        }
        List<String> addresses = new ArrayList<>();
        for (InterfaceAddress entry : network.getInterfaceAddresses()) {
            InetAddress address = entry.getAddress();
            if (!(address instanceof Inet6Address) || !address.isLinkLocalAddress()) {
                continue;
            }
            String text = address.getHostAddress();
            int scope = text.indexOf('%');
            if (scope >= 0) {
                text = text.substring(0, scope);
            }
            if (!addresses.contains(text)) {
                addresses.add(text);
            }
        }
        return addresses;
    }

    /** Whether the socket is open and the schedule is running. */
    boolean running() {
        synchronized (lock) {
            return socket != null;
        }
    }

    /**
     * One clause for the page, saying what the announcer is doing when it is not doing it.
     *
     * <p>Empty while all is well, because the receiving line is already busy saying something
     * true and a second "everything is fine" only takes space. Non-empty after a start that never
     * got a socket — which on this device usually means the AWDL link is not up, and that is
     * exactly the thing a person looking at an empty share sheet needs to know.
     */
    String aside() {
        String problem = trouble;
        return problem.isEmpty() ? "" : "广播未起：" + problem;
    }

    /**
     * Opens the socket and starts answering, or records why it could not.
     *
     * <p>Returns immediately and reports failure through {@link #aside()}: the caller is the UI
     * thread, and binding a socket — plus resolving an interface that may be mid-rebuild — is I/O
     * that has no business there.
     */
    void start() {
        synchronized (lock) {
            if (opener != null || socket != null) {
                return;
            }
            running = true;
            announcedAt = 0L;
            trouble = "";
            opener = daemon(this::open, "opairdrop-mdns-open");
        }
    }

    /**
     * Takes the instance back and closes the socket.
     *
     * <p>The withdrawal is the whole reason this is not just a {@code close()}. A responder that
     * stops announcing is not a responder that has withdrawn anything: every record it ever sent
     * stays valid for the lifetime it carried, and the PTR here lives seventy-five minutes — an
     * hour and a quarter of a share sheet offering a device that stopped receiving. Records are
     * sent again with a zero lifetime, which is what a cache matches on: same name, same type,
     * zero, delete.
     *
     * <p>Sent from the caller's thread, and that is affordable: three datagrams are three
     * {@code sendto} calls, not three seconds. The legacy stack's {@code close()} intended the
     * same thing and never sent anything — its {@code withdraw} refused the instance it was
     * currently publishing, which on the way out is always the one that matters. The guard is
     * right at startup (withdrawing the name you are about to keep would delete yourself) and
     * wrong at shutdown, so the two are kept apart here: this method does not go through the
     * "is it still ours" check.
     */
    void stop() {
        MulticastSocket held;
        Thread opening;
        Thread receiving;
        Thread ticking;
        Thread retrying;
        synchronized (lock) {
            running = false;
            held = socket;
            socket = null;
            // The opener is a real thread name here, not the receive loop: after a start whose
            // bind is still in flight, the thing that has to be told to give up is the thread
            // doing the binding. Leaving it unnamed would leak a binder that opens a socket
            // nobody is holding ten seconds after the switch went off.
            opening = opener;
            opener = null;
            receiving = receiveThread;
            receiveThread = null;
            ticking = tickThread;
            tickThread = null;
            retrying = retrier;
            retrier = null;
        }
        if (held != null) {
            // Withdraw before closing, and on this thread: the packets have to leave through the
            // very socket that is about to go away.
            withdraw(held);
            closeQuietly(held);
        }
        interrupt(opening);
        interrupt(receiving);
        interrupt(ticking);
        interrupt(retrying);
        releaseLock();
    }

    /** Opens the socket and hands it to the two loops; the body of the opener thread. */
    private void open() {
        MulticastSocket opened;
        try {
            opened = bind();
        } catch (IOException | RuntimeException refused) {
            Log.w(TAG, "announcer could not start on " + interfaceName, refused);
            fail(refused);
            // Clearing the opener is what makes a second flip of the switch mean something. On
            // this device the usual failure is that the AWDL link is not up yet, i.e. exactly
            // the case where the person will fix it and try again -- and a guard that still
            // sees a finished opener would answer that second attempt with silence, which is
            // indistinguishable from the switch being broken.
            synchronized (lock) {
                opener = null;
            }
            // 失败之后自己接着试，而不是等人再拨一次开关。
            //
            // 这台机器上最常见的失败就是 `bind()` 抛「没有网卡 awdltun0」——接收端比 AWDL
            // 隧道早。原来只有两处会重试：`StackKeeper` 的 tick，和人来关再开一次。前者属于
            // 某个界面，App 重启而开关还开着的时候根本没人叫它；后者要人自己发现「面板里没有
            // 这台设备」。2026-11-21 凌晨连着两次都是这个形态：链路在、8770 在听、什么都不广播，
            // 于是谁都看不见。
            scheduleRetry();
            return;
        }

        boolean keep;
        synchronized (lock) {
            // Hand over from "someone is opening" to "a socket exists" inside one hold of the
            // lock: two separate writes would leave a moment where both are null, and a start()
            // landing in that moment opens a second socket on a port that is already bound.
            opener = null;
            keep = running;
            if (keep) {
                socket = opened;
            }
        }
        if (!keep) {
            // A stop() arrived while we were binding. Nothing is published yet, so there is
            // nothing to withdraw; the multicast lock does have to go back, because stop() ran
            // before bind() got as far as acquiring it.
            closeQuietly(opened);
            releaseLock();
            return;
        }

        Thread recv = daemon(this::receiveLoop, "opairdrop-mdns-recv");
        Thread tick = daemon(this::tickLoop, "opairdrop-mdns-tick");
        synchronized (lock) {
            receiveThread = recv;
            tickThread = tick;
        }
        // Before the first announcement, so a peer that cached an older name of this device
        // stops offering it at the same moment it starts offering the current one.
        clearGhosts(opened);
        Log.i(TAG, "announcing " + instance + " at " + host + ":" + port + " on " + interfaceName);
    }

    /**
     * Withdraws every name this device used to answer to.
     *
     * <p>{@link ServiceId} remembers up to eight of them precisely so this moment can happen. A
     * crash in the middle of writing the file leaves it unparseable, the next start invents a new
     * name — which is right, a half-written one must not be reused — and the old name goes on
     * being offered by every phone that already cached it, for the seventy-five minutes the PTR
     * lives. A zero-lifetime announcement under the old name is the only way to take that back,
     * and it cannot be sent before a socket exists.
     */
    private void clearGhosts(MulticastSocket held) {
        List<String> old;
        synchronized (lock) {
            if (ghosts.isEmpty()) {
                return;
            }
            old = new ArrayList<>(ghosts);
            ghosts.clear();
        }
        for (String name : old) {
            withdraw(held, name);
        }
    }

    /**
     * Binds {@code [::]:5353} and joins the group on one interface.
     *
     * <p>One socket owns the port: it receives the queries it must answer and sends both the
     * announcements and the withdrawals, so there is no second socket to disagree with it about
     * what this device publishes.
     */
    private MulticastSocket bind() throws IOException {
        NetworkInterface network = NetworkInterface.getByName(interfaceName);
        if (network == null) {
            throw new IOException("没有网卡 " + interfaceName + "（AWDL 链路没建）");
        }

        MulticastSocket opened = new MulticastSocket(null);
        try {
            // Before bind, and not after: the resolver on this platform also holds 5353 while a
            // link is shared, and without reuse the bind fails instead of sharing.
            opened.setReuseAddress(true);
            // The IPv6 wildcard explicitly. The group is a v6 address, and a socket bound to the
            // v4 wildcard on a dual-stack platform is a question about which family actually got
            // bound -- one that is answered differently by different runtimes.
            opened.bind(new InetSocketAddress(InetAddress.getByName("::"), PORT));
            opened.setNetworkInterface(network);
            InetAddress multicast = groupFor(interfaceName);
            opened.joinGroup(new InetSocketAddress(multicast, PORT), network);
            this.group = multicast;
            this.armedIndex = network.getIndex();
            this.armedCheckedAt = System.currentTimeMillis();
            // Every packet carries its hop limit, and the kernel's default for IPv6 multicast is
            // 1. A peer that enforces the mDNS rule on that field drops the packet before it looks
            // at the address, so without this the link is silent in a way that looks like a peer
            // that does not speak mDNS.
            if (!MulticastHop.raise(opened, MulticastHop.MDNS)) {
                Log.w(TAG, "hop limit left at the kernel default; a checking peer may drop us");
            }
        } catch (IOException failed) {
            closeQuietly(opened);
            throw failed;
        }

        // Without this lock the kernel drops multicast packets as soon as the screen goes off:
        // the power policy concludes nobody is listening.
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            WifiManager.MulticastLock lock = wifi.createMulticastLock("opairdrop-respond");
            lock.setReferenceCounted(false);
            lock.acquire();
            this.multicastLock = lock;
        }
        return opened;
    }

    /**
     * The group address, scoped when the platform lets us.
     *
     * <p>A scoped address pins the send to that interface even when the routing table has no
     * opinion about {@code ff02::fb} — and for a tun whose route lives in a policy table, that is
     * every time.
     */
    private static InetAddress groupFor(String interfaceName) throws IOException {
        try {
            return InetAddress.getByName(GROUP + "%" + interfaceName);
        } catch (IOException unscoped) {
            return InetAddress.getByName(GROUP);
        }
    }

    /** Receives one datagram at a time and answers the ones that ask about us. */
    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET];
        try {
            while (running) {
                MulticastSocket held = socket;
                if (held == null) {
                    break;
                }
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                try {
                    held.receive(incoming);
                } catch (IOException stopped) {
                    if (running) {
                        Log.w(TAG, "mDNS receive failed", stopped);
                        fail(stopped);
                    }
                    break;
                }
                byte[] packet = new byte[incoming.getLength()];
                System.arraycopy(incoming.getData(), incoming.getOffset(), packet, 0, packet.length);

                byte[] answer;
                try {
                    answer = Native.mdnsReply(packet, instance, host, port, txtPairs(), addresses());
                } catch (IOException | RuntimeException notForUs) {
                    // 5353 also carries printers, projectors and other people's AirPlay. A packet
                    // that is not DNS, or one we have no business answering, is not a failure —
                    // turning it into one would put an error line on the page every few seconds
                    // in a flat full of devices.
                    continue;
                }
                if (answer.length == 0) {
                    continue;
                }
                // The first byte is the send mode: see Native#mdnsReply.
                boolean unicast = answer[0] == 1;
                byte[] body = Arrays.copyOfRange(answer, 1, answer.length);
                try {
                    transmit(body, unicast ? incoming.getSocketAddress() : null);
                } catch (IOException failed) {
                    Log.w(TAG, "mDNS answer failed", failed);
                }
            }
        } finally {
            // Reaching here means the socket is gone or unusable; the page should say so rather
            // than keep claiming the device is visible.
            if (running) {
                fail(new IOException("广播循环已结束"));
            }
        }
    }

    /** Announces on a schedule, and re-pins the socket when the link was rebuilt under it. */
    private void tickLoop() {
        while (running) {
            long now = System.currentTimeMillis();
            if (now - armedCheckedAt >= ARM_CHECK_MS) {
                armedCheckedAt = now;
                rearm();
            }
            if (now - announcedAt >= ANNOUNCE_MS) {
                announcedAt = now;
                announce();
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException stop) {
                break;
            }
        }
    }

    /** Publishes our four records once, as a response with id 0 and each record's own TTL. */
    private void announce() {
        MulticastSocket held = socket;
        if (held == null) {
            return;
        }
        byte[] packet;
        try {
            packet = Native.mdnsAnnounce(
                    instance, host, port, txtPairs(), addresses(), 0, RECORD_ALL, TTL_AS_PUBLISHED);
        } catch (RuntimeException refused) {
            // Only unchecked, because mdnsAnnounce does not declare IOException: a malformed
            // address is a bug on our side, not a condition to wait out.
            Log.w(TAG, "mDNS announcement could not be built", refused);
            fail(refused);
            return;
        }
        try {
            transmit(packet, null);
        } catch (IOException failed) {
            // Reported, not fatal: the next tick is three seconds away, and a single lost
            // announcement is what the cadence exists to absorb.
            Log.w(TAG, "mDNS announcement failed", failed);
        }
    }

    /** Sends our records again with a zero lifetime, so peers delete the instance. */
    private void withdraw(MulticastSocket held) {
        withdraw(held, instance);
    }

    /**
     * The same thing for an arbitrary name.
     *
     * <p>Separate from the announcement path on purpose: an announcement publishes what we are,
     * a withdrawal publishes that a name is over. The two differ only in the name and in the
     * zero, and keeping them apart is what lets one be sent on a schedule and the other only
     * when something ends.
     */
    private void withdraw(MulticastSocket held, String name) {
        byte[] packet;
        try {
            packet = Native.mdnsAnnounce(name, host, port, txtPairs(), addresses(),
                    0, RECORD_ALL, 0);
        } catch (RuntimeException refused) {
            // Same reason as in announce(): mdnsAnnounce declares no checked exception, and a
            // withdrawal we cannot build is worth a line in the log, not a crash on the way out.
            Log.w(TAG, "mDNS withdrawal could not be built", refused);
            return;
        }
        InetAddress multicast = group;
        if (multicast == null) {
            return;
        }
        for (int copy = 0; copy < WITHDRAW_COPIES; copy++) {
            try {
                held.send(new DatagramPacket(packet, packet.length, multicast, PORT));
            } catch (IOException failed) {
                Log.w(TAG, "mDNS withdrawal failed", failed);
                return;
            }
        }
    }

    /**
     * Re-pins the socket when the interface it was bound to has been rebuilt.
     *
     * <p>Re-pinning on the same socket, rather than replacing it, keeps the bound port and the
     * receive loop untouched: no packet is dropped between two sockets, and no thread has to be
     * restarted.
     *
     * @return true when the pin moved, i.e. a failed send is worth retrying
     */
    private boolean rearm() {
        MulticastSocket held = socket;
        if (held == null || interfaceName == null) {
            return false;
        }
        try {
            NetworkInterface network = NetworkInterface.getByName(interfaceName);
            if (network == null) {
                // Mid-rebuild: forget the index so the next check re-pins unconditionally.
                armedIndex = -1;
                return false;
            }
            int index = network.getIndex();
            if (index == armedIndex) {
                return false;
            }
            held.setNetworkInterface(network);
            InetAddress multicast = groupFor(interfaceName);
            held.joinGroup(new InetSocketAddress(multicast, PORT), network);
            group = multicast;
            armedIndex = index;
            Log.i(TAG, "re-pinned the announcer to " + interfaceName + " (index " + index + ")");
            return true;
        } catch (IOException failed) {
            Log.w(TAG, "re-pin to " + interfaceName + " failed", failed);
            return false;
        }
    }

    /**
     * Sends to the group, or back to {@code unicastTo} when the querier asked for a direct
     * answer.
     *
     * <p>{@code ENETUNREACH} is retried once: on this device it almost always means the interface
     * behind the pin was rebuilt, which {@link #rearm()} repairs in place. A single retry is
     * enough — if the second attempt fails too, the link is genuinely down and the schedule will
     * come back to it.
     */
    private void transmit(byte[] packet, SocketAddress unicastTo) throws IOException {
        MulticastSocket held = socket;
        if (held == null) {
            throw new IOException("广播未启动");
        }
        try {
            sendOnce(held, packet, unicastTo);
        } catch (IOException failed) {
            if (!unreachable(failed) || !rearm()) {
                throw failed;
            }
            sendOnce(held, packet, unicastTo);
        }
    }

    private void sendOnce(MulticastSocket held, byte[] packet, SocketAddress unicastTo)
            throws IOException {
        DatagramPacket datagram;
        if (unicastTo instanceof InetSocketAddress) {
            datagram = new DatagramPacket(packet, packet.length, unicastTo);
        } else {
            InetAddress multicast = group;
            if (multicast == null) {
                throw new IOException("这个网卡上没有组播组");
            }
            datagram = new DatagramPacket(packet, packet.length, multicast, PORT);
        }
        held.send(datagram);
    }

    /** True when {@code error} is the kernel saying it has no route, not a real failure. */
    private static boolean unreachable(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("ENETUNREACH")) {
                return true;
            }
        }
        return false;
    }

    /** The TXT pairs as the bridge takes them: {@code key=value}, in the order we publish. */
    private String[] txtPairs() {
        Map<String, String> pairs = txt();
        String[] lines = new String[pairs.size()];
        int at = 0;
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            lines[at++] = pair.getKey() + "=" + pair.getValue();
        }
        return lines;
    }

    /** This interface's link-local addresses, rebuilt on every send so a change shows up. */
    private String[] addresses() {
        List<String> found = linkLocalAddresses(interfaceName);
        return found.toArray(new String[0]);
    }

    private void fail(Throwable why) {
        trouble = why.getMessage() == null || why.getMessage().isEmpty()
                ? why.toString()
                : why.getMessage();
        if (onChange != null) {
            try {
                onChange.run();
            } catch (RuntimeException ignored) {
                // A page that cannot be told is not a reason to lose the reason it wanted.
            }
        }
    }

    /**
     * Retries the bind every {@link #RETRY_MS} until there is a socket or the switch goes off.
     *
     * <p>One retrier at a time; it dies with the switch. {@link #start()} is what it calls, and
     * that method is already safe to call from anywhere -- it returns while an opener is in
     * flight and does the whole thing again once one is not.
     */
    private void scheduleRetry() {
        synchronized (lock) {
            if (!running || retrier != null) {
                return;
            }
            retrier = daemon(() -> {
                while (true) {
                    try {
                        Thread.sleep(RETRY_MS);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    synchronized (lock) {
                        if (!running || socket != null) {
                            retrier = null;
                            return;
                        }
                        if (opener != null) {
                            continue;
                        }
                    }
                    start();
                }
                synchronized (lock) {
                    retrier = null;
                }
            }, "opairdrop-mdns-retry");
        }
    }

    private void releaseLock() {
        WifiManager.MulticastLock held;
        synchronized (lock) {
            held = multicastLock;
            multicastLock = null;
        }
        if (held != null) {
            try {
                held.release();
            } catch (RuntimeException ignored) {
                // 没拿到或者已经放了。
            }
        }
    }

    private static Thread daemon(Runnable body, String name) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Asks a loop to finish early.
     *
     * <p>Interrupting is not how any of these threads is expected to stop — they all check
     * {@link #running} — but the schedule sleeps for up to {@link #TICK_MS} and the opener may be
     * waiting on an interface that is mid-rebuild. The interrupt only spares the caller from
     * sitting through that wait while a closed socket is still in scope.
     */
    private static void interrupt(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    private static void closeQuietly(MulticastSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (RuntimeException ignored) {
            // 已经关了。
        }
    }
}
