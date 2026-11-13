package com.oshare.airdrop.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds AirDrop receivers on a link: one mDNS query, then whatever answers within a
 * window.
 *
 * <p>The receive side of this app answers these queries ({@link MdnsResponder}); this is
 * the asking side, and it is deliberately a separate object from {@link AirDropLink}.
 * A sender needs discovery once, while a receiver needs a responder for as long as it
 * is up — sharing one implementation would mean a responder that has to be started to
 * ask a question.
 *
 * <p>Two details are inherited from the probe that made this work, and both are load
 * bearing:
 *
 * <ul>
 *   <li>The query carries the unicast-response bit ({@code QU}). Without it every
 *       responder on the link answers to the multicast group on port 5353, which this
 *       socket — bound to an ephemeral port — would never hear. With it, answers come
 *       back to the sender's own address and port.
 *   <li>The socket does not join the group. Joining is what lets a listener hear
 *       <em>other people's</em> queries and unsolicited announcements; a browser wants
 *       neither, and the join is also what fails first when the system mDNS daemon
 *       already holds port 5353.
 * </ul>
 *
 * <p>Answers can arrive in pieces: a responder is free to send the PTR in one packet
 * and the SRV/TXT/AAAA in another, and several devices answer a single query. Merging
 * is therefore part of browsing rather than an afterthought — see {@link #merge}.
 */
public final class AirDropBrowser {
    /** How long a browse listens by default. Long enough for a sleepy radio, short enough to tap. */
    public static final long DEFAULT_WINDOW_MS = 2500L;

    /**
     * Spacing between repeated queries inside one window; mDNS is lossy by design.
     *
     * <p>A full second, not less: Bonjour drops a question it has already been asked
     * within the last second, so a tighter loop spends packets that are never answered.
     * Three rounds fit in {@link #DEFAULT_WINDOW_MS}.
     */
    private static final long REPEAT_MS = 1000L;

    /**
     * When the first query is asked a second time, before {@link #REPEAT_MS} is due.
     *
     * <p>The first query leaves the moment {@code browse} is called, which on this device
     * is the same moment the node has just finished building the tunnel: nothing has yet
     * confirmed that the link carries multicast, and the frame is sent blind. Measured
     * over eight cold starts, "first peer" split into two groups -- 377 to 615 ms when the
     * first query got through, and 1333 to 1418 ms when it did not, which is one whole
     * {@link #REPEAT_MS} later. Six of the eight were in the slow group.
     *
     * <p>Re-asking early costs nothing when the first copy arrived: Bonjour drops a
     * question it has already seen within the last second, so the duplicate is discarded
     * rather than answered twice. When the first copy was lost, it is the difference
     * between waiting a second and not. Short enough to still land before a receiver's
     * answer to the original would have, so the two windows overlap instead of queueing.
     */
    private static final long FOLLOW_UP_MS = 180L;

    /**
     * Most a single answer can be. A full record set with several addresses is well
     * under a kilobyte; the rest is headroom for whatever a peer decides to include.
     */
    private static final int PACKET = 4096;

    /**
     * The second service asked about on the same link, for the title of a row only.
     *
     * <p>iOS does not publish a readable name on {@code _airdrop._tcp}. What it publishes
     * there is a rotating identity -- one iPhone, one AWDL MAC, three instance names inside
     * a few minutes -- so a list built on that name shows a phone as {@code a1e9ac0eb2e6}
     * and, worse, shows the same phone twice once it rotates. The device still advertises
     * Continuity on {@code _companion-link._tcp} from the same link-local address, and that
     * instance name is the name the device goes by.
     *
     * <p>Nothing about the offer depends on this question. The address from the AirDrop
     * record is what dials, and its port and TXT are what the offer introduces itself with.
     * A link where nobody answers this is a link with hex titles, not a broken browse, so
     * every failure below is swallowed.
     */
    public static final String NAME_SERVICE = "_companion-link._tcp.local";

    /**
     * Names learned so far, keyed by lower-cased link-local address.
     *
     * <p>Keyed by address rather than by instance name on purpose: the AirDrop instance name
     * is the thing that rotates, and an address is what the two records have in common. Two
     * devices cannot hold one link-local address at the same time, so an address is a
     * session-scoped identity even where a name is not.
     *
     * <p>Concurrent because a browse fills it on its own thread while the screen that asked
     * for the round reads it to title its rows.
     *
     * <p>Shared rather than owned: the name a person recognises is not in any mDNS answer,
     * it is in the plist of a {@code /Discover} -- and the {@code /Discover} worth having is
     * the one the peer sends <em>us</em>, which is answered by the receiver endpoint on a
     * thread that has never heard of this object. {@link PeerNames} is where both halves
     * meet, and keying on the device behind the address is what lets a name outlive both
     * the rotation of the instance name it was learned under and the address the peer
     * happens to speak from.
     */
    private final String interfaceName;

    /** @param interfaceName the link to ask on, e.g. {@code wonder0} */
    public AirDropBrowser(String interfaceName) {
        if (interfaceName == null || interfaceName.trim().isEmpty()) {
            throw new IllegalArgumentException("interfaceName == null");
        }
        this.interfaceName = interfaceName.trim();
    }

    public String interfaceName() {
        return interfaceName;
    }

    /**
     * The name {@code peer} goes by on its own device, or {@code null} when nobody said.
     *
     * <p>{@code null} rather than an empty string or the instance name: the caller already
     * has the instance name and only needs to know whether something better arrived. A
     * fallback returned from here would be indistinguishable from a real answer, and the
     * row would stop telling the two apart in the trace as well.
     */
    public String nameFor(AirDropPeer peer) {
        if (peer == null) return null;
        for (String address : peer.addresses) {
            String name = PeerNames.of(address);
            if (name != null && !name.isEmpty()) return name;
        }
        return null;
    }

    /** Every name learned so far, keyed by device. Read-only, for a screen or a trace. */
    public Map<String, String> names() {
        return PeerNames.all();
    }

    /**
     * Told about peers while the window is still open.
     *
     * <p>A browse that only answers at the end of its window makes the screen empty for
     * as long as the window lasts, even when the peer replied in the first tenth of a
     * second — the wait is the window, not the radio. Handing each new peer over as it
     * arrives lets a list fill at the speed the link actually answers.
     *
     * <p>Called from the browsing thread with an immutable snapshot; a caller that
     * touches a view must post it to its own thread.
     */
    public interface Sink {
        void onPeers(List<AirDropPeer> merged);
    }

    /**
     * Asks once and waits {@code windowMillis}; blocking, so call it off the main thread.
     *
     * <p>An answer that cannot be parsed is skipped rather than fatal: one peer sending a
     * record set this parser does not understand must not hide the peer that does.
     */
    public List<AirDropPeer> browse(long windowMillis) throws IOException {
        return browse(windowMillis, null);
    }

    /**
     * The same, reporting each newly seen peer to {@code sink} as the window runs.
     *
     * <p>Only a peer the window had not seen before is reported; a later answer that
     * merely fills in an address or a TXT record replaces the entry in place without a
     * second call, because a list that re-renders on every packet flickers.
     */
    public List<AirDropPeer> browse(long windowMillis, Sink sink) throws IOException {
        return browse(windowMillis, sink, null);
    }

    /**
     * The same, ending the window as soon as {@code stop} says so.
     *
     * <p>The window is a listening budget, not a promise: a round that is still open when
     * the user taps a row is asking questions about a list that is no longer on screen,
     * and its queries leave through the same AWDL queue as the offer's handshake. The
     * caller that has something better to do with the link says so here instead of waiting
     * out a deadline it no longer wants.
     *
     * <p>Checked once per receive slice, so the window ends within a quarter second rather
     * than at the next query. A partial list is the right answer: the caller merges it, and
     * the peers it already has are the ones the round was asked for.
     *
     * @param stop may be null, which means "run the whole window"
     */
    public List<AirDropPeer> browse(long windowMillis, Sink sink,
                                    java.util.function.BooleanSupplier stop) throws IOException {
        NetworkInterface network = NetworkInterface.getByName(interfaceName);
        if (network == null) throw new IOException("interface " + interfaceName + " is missing");

        InetAddress local = localLinkAddress(network);
        if (local == null) {
            throw new IOException("no link-local address on " + interfaceName
                + " — is the AWDL link up?");
        }

        byte[] query = query();
        long started = System.nanoTime();
        long deadline = started + Math.max(0L, windowMillis) * 1_000_000L;
        List<AirDropPeer> found = new ArrayList<>();

        // TXT records outlive the packet they came in. Apple answers a query in pieces --
        // the SRV and the AAAA in one packet, the TXT in another -- and the two can arrive
        // in either order. Parsing each packet on its own therefore handed the screen a
        // peer with no certificate hash and no capability word, which is the difference
        // between an entry that dials straight away and one that has to be asked again.
        // The pool is per browse: it is a cache for one window, not state that should
        // survive into the next one, where the peer may have gone away.
        Map<String, DnsMessage.Txt> pool = new HashMap<>();

        // What the Continuity answers are joined through; see namesIn. Per window for the
        // same reason as the pool above: they describe who answered now, not who exists.
        Map<String, String> hostOfInstance = new HashMap<>();
        Map<String, List<String>> addressesOfHost = new HashMap<>();
        byte[] titleQuery = nameQuery();

        try (MulticastSocket socket = new MulticastSocket(new InetSocketAddress(0))) {
            // Pin the socket to the link as well as zoning the destination. The zone
            // alone already selects wonder0, but the kernel's fallback when no zone
            // survives is whichever interface happens to own ff00::/8 first -- on this
            // device that is dummy0, and the query then leaves on a link with no peer.
            try {
                socket.setNetworkInterface(network);
            } catch (IOException unpinned) {
                // Scoped addresses below still carry the zone; the window can proceed.
            }
            // The query leaves with the hop limit mDNS asks for. Without this the kernel
            // sends 1, and a peer that checks the field ignores the query entirely.
            MulticastHop.raise(socket, MulticastHop.MDNS);
            InetAddress group = group(network);
            long next = 0L;
            /** When the blind first query is asked again, or zero when nothing is owed. */
            long followUp = 0L;
            // Counted so the trace can say which copy of the question a peer was finally
            // answering: "first peer at 2.6 s" means one thing when it followed the first
            // query and something else entirely when it followed the third.
            int queried = 0;
            int heard = 0;
            // Instances this window has already asked about by name.
            //
            // The group question returns the instance with its SRV and its address but
            // routinely without its TXT, and Apple sends that TXT as a second packet. In
            // the measured runs the second packet did not arrive until after the *next*
            // group question, a second later, because a duplicate group question inside
            // that second is suppressed by the responder -- so the screen sat with a peer
            // it could see and could not introduce itself to, for a second it did not have
            // to spend. A question for the instance itself is a different question, is
            // therefore not a duplicate, and can be asked the moment the name is known.
            Set<String> askedByName = new HashSet<>();

            while (System.nanoTime() < deadline) {
                if (stop != null && stop.getAsBoolean()) break;
                long now = System.nanoTime();
                if (now >= next) {
                    // Every round goes to the group. The repeat rounds used to be aimed
                    // at {@code local} -- this device's own link-local address -- so they
                    // never left the radio: a window that lost its first packet sat in
                    // silence for the remaining two seconds, and the list only recovered
                    // on the next round some 3.3 seconds later. That silence was the
                    // whole of "finding the other phone is slow".
                    //
                    // Rounds stay REPEAT_MS apart because Bonjour suppresses a duplicate
                    // question that arrives within a second of the previous one; asking
                    // faster would be discarded by the receiver rather than answered.
                    //
                    // A send is never allowed to end the window: the link can be rebuilt
                    // mid-browse, and a caller that wanted 2.5 seconds of listening should
                    // get them even if one round cannot be sent.
                    try {
                        send(socket, query, group);
                        queried++;
                        Trace.write("browse: query #" + queried + " +"
                            + (System.nanoTime() - started) / 1_000_000L + " ms");
                    } catch (IOException groupRefused) {
                        // Keep listening; the next round tries again.
                    }
                    // The title question rides every round rather than being asked once:
                    // it is lossy exactly like the one above, and a round that reaches no
                    // peer leaves the rows named from the previous round. Not counted in
                    // {@code queried}, which numbers the AirDrop rounds in the trace.
                    try {
                        send(socket, titleQuery, group);
                    } catch (IOException titleRefused) {
                        // Rows keep whatever name they already had.
                    }
                    next = now + REPEAT_MS * 1_000_000L;
                    followUp = now + FOLLOW_UP_MS * 1_000_000L;
                } else if (followUp != 0L && now >= followUp) {
                    // See FOLLOW_UP_MS: a second copy of the first question, only while
                    // the window is young.
                    try {
                        send(socket, query, group);
                        queried++;
                        Trace.write("browse: query #" + queried + " (follow-up) +"
                            + (System.nanoTime() - started) / 1_000_000L + " ms");
                    } catch (IOException groupRefused) {
                        // The original is still on its way; nothing else to do.
                    }
                    try {
                        // The title question is asked blind the first time too, and for the
                        // same reason is worth a second copy while the window is young.
                        send(socket, titleQuery, group);
                    } catch (IOException titleRefused) {
                        // Covered by the next round.
                    }
                    followUp = 0L;
                }

                long left = deadline - System.nanoTime();
                if (left <= 0L) break;
                // Wake up in time to send whatever is due rather than after it was due.
                // The follow-up was the only deadline this looked at, and the repeat was
                // the one that mattered: a slice of 250 ms meant a round due at one second
                // left at 1.185, so the group question that finally produced the peer's
                // second packet was 185 ms late for no reason. Same reasoning, same fix,
                // now covering both deadlines and whichever is nearer.
                long due = followUp == 0L ? next : Math.min(next, followUp);
                long slice = Math.min(250L,
                    Math.max(1L, (due - System.nanoTime()) / 1_000_000L));
                socket.setSoTimeout((int) Math.max(1L, Math.min(slice, left / 1_000_000L)));
                try {
                    DatagramPacket incoming = new DatagramPacket(new byte[PACKET], PACKET);
                    socket.receive(incoming);
                    int length = incoming.getLength();
                    List<AirDropPeer> parsed = peersIn(incoming.getData(), length, pool);
                    int added = merge(found, parsed);
                    added += applyPool(found, pool);
                    // A packet that names a device adds no peer, so it would otherwise end
                    // here with nothing to show for it -- and the row it names is already
                    // on screen under its instance name.
                    int named = namesIn(incoming.getData(), length,
                        hostOfInstance, addressesOfHost);
                    // Which arrived: our answer, a piece of it, or somebody else's
                    // question on the same group. Without this the only fact a slow
                    // window leaves behind is its total, which cannot be acted on --
                    // a peer that answered late and a peer we could not hear look the
                    // same from the outside. Three packets is enough to tell a parse
                    // failure from silence; anything past that is the other devices on
                    // the link, and a busy room would fill the trace with them.
                    heard++;
                    if (heard <= 3 || added > 0 || named > 0) {
                        Trace.write("browse: rx " + length + " B +"
                            + (System.nanoTime() - started) / 1_000_000L + " ms, "
                            + parsed.size() + " peer(s), " + added + " new, "
                            + named + " name(s)");
                    }
                    if ((added > 0 || named > 0) && sink != null) {
                        sink.onPeers(Collections.unmodifiableList(new ArrayList<>(found)));
                    }
                    // A peer that answered with no TXT at all is dialable but not offerable:
                    // the capability word and the certificate hash both live in the TXT, and
                    // without them the offer has nothing to introduce itself with. Asking
                    // such an instance once, by name, is what removes the wait for the next
                    // group question. Bounded by the set: a peer that keeps refusing to
                    // describe itself costs one packet per window, not one per answered
                    // query.
                    //
                    // The test is "no TXT", not "no certificate hash", and the difference
                    // cost a measurement to learn. An iPhone's TXT carries flags and no
                    // {@code h} at all -- measured flags=111611 with an empty hash on every
                    // answer -- so gating on the hash alone had this side asking the same
                    // phone, every window, for a record it had already sent in full. The
                    // flag word and the hash are both absent only when the TXT itself is
                    // missing, which is the case worth one extra question.
                    for (AirDropPeer incomplete : found) {
                        if (!incomplete.certHash.isEmpty() || incomplete.flags != 0L) continue;
                        if (incomplete.serviceName.isEmpty()) continue;
                        if (!askedByName.add(incomplete.serviceName)) continue;
                        try {
                            send(socket, txtQuery(incomplete.serviceName), group);
                            // Deliberately not counted in {@code queried}: that number
                            // labels the group rounds in the trace, and a by-name question
                            // is not a round. Mixing them would make "query #4" mean two
                            // different things in two logs.
                            Trace.write("browse: asked \"" + incomplete.serviceName
                                + "\" for its TXT +"
                                + (System.nanoTime() - started) / 1_000_000L + " ms");
                        } catch (IOException refused) {
                            // The next group question covers it; nothing else to do.
                        }
                    }
                } catch (SocketTimeoutException quiet) {
                    // Nothing this slice; the loop re-checks the deadline.
                }
            }
        }
        return found;
    }

    /** The query every browse sends: one PTR question, unicast response requested. */
    static byte[] query() {
        return new DnsMessage.Builder(id(), 0)
            .question(AirDropPeer.SERVICE, DnsMessage.TYPE_PTR,
                DnsMessage.CLASS_IN | DnsMessage.CLASS_UNICAST)
            .build();
    }

    /**
     * The question owed to one instance that answered without its TXT record.
     *
     * <p>Asked against the instance name rather than the service, which is the whole
     * point: the responder suppresses a question it has already been asked within the
     * last second, and the group question is exactly that question. This one is not a
     * repeat, so it is answered immediately instead of at the next round.
     *
     * <p>{@code ANY} rather than {@code TXT} alone. The instance is asked about at the
     * moment we have its address but nothing else, and the record set for one name is
     * three records — SRV, TXT, AAAA — which fit one answer packet comfortably. Asking
     * for {@code ANY} gets the certificate hash and, in the same packet, a fresh port
     * and address, so a peer whose SRV we half-heard is repaired by the same question
     * that completes it. Where a responder answers only the type asked for, this still
     * covers the TXT; where it answers the whole name, it saves the follow-up.
     *
     * <p>The name is passed through untouched: {@link DnsMessage#writeName} already
     * treats {@code \.} as the two bytes of a label rather than as a separator, which is
     * how a DNS-SD instance name that contains a dot arrives from the PTR record.
     */
    static byte[] txtQuery(String instanceName) {
        return new DnsMessage.Builder(id(), 0)
            .question(instanceName, DnsMessage.TYPE_ANY,
                DnsMessage.CLASS_IN | DnsMessage.CLASS_UNICAST)
            .build();
    }

    /**
     * The title question: one PTR for the Continuity service, unicast response requested.
     *
     * <p>Same two properties as {@link #query}, for the same two reasons: without the
     * unicast bit the answer goes to the group on 5353, which a socket bound to an
     * ephemeral port never hears, and the socket must not join the group.
     */
    static byte[] nameQuery() {
        return new DnsMessage.Builder(id(), 0)
            .question(NAME_SERVICE, DnsMessage.TYPE_PTR,
                DnsMessage.CLASS_IN | DnsMessage.CLASS_UNICAST)
            .build();
    }

    /**
     * Fills {@code names} with the Continuity instance name behind each address.
     *
     * <p>Three records make one entry and they do not have to arrive together: the SRV
     * carries instance to hostname, the AAAA hostname to address, and Apple splits them
     * across packets the same way it splits the AirDrop answer. The two pools are therefore
     * per browse window, and every packet re-joins whatever the window has learned so far --
     * which is also what lets an SRV that arrived first be completed by an address later.
     *
     * <p>The instance label is unescaped, not formatted: a DNS-SD instance name containing
     * a dot arrives as {@code \.} and is one label, and printing it raw would show the
     * escape to the user.
     *
     * @return how many addresses this packet named for the first time, or renamed
     */
    static int namesIn(byte[] packet, int length,
                       Map<String, String> hostOfInstance, Map<String, List<String>> addressesOfHost) {
        if (packet == null || length <= 0) return 0;
        byte[] slice = packet.length == length ? packet : java.util.Arrays.copyOf(packet, length);
        try {
            DnsMessage message = DnsMessage.parse(slice);
            if (!message.isResponse()) return 0;
            for (DnsMessage.Record record : message.records()) {
                if (record.type == DnsMessage.TYPE_SRV && isNameService(record.name)) {
                    hostOfInstance.put(normalize(record.name), normalize(record.target()));
                } else if (record.type == DnsMessage.TYPE_AAAA) {
                    addressesOfHost.computeIfAbsent(normalize(record.name), k -> new ArrayList<>())
                        .add(record.addressV6());
                }
            }
            int named = 0;
            for (Map.Entry<String, String> instance : hostOfInstance.entrySet()) {
                List<String> addresses = addressesOfHost.get(instance.getValue());
                if (addresses == null) continue;
                String name = instanceLabel(instance.getKey());
                if (name.isEmpty()) continue;
                for (String address : addresses) {
                    if (address == null || address.isEmpty()) continue;
                    String previous = PeerNames.of(address);
                    if (name.equals(previous)) continue;
                    // Filed through PeerNames, which refuses the rotated hex instance
                    // names. This record set is the Continuity one, and on the peers
                    // measured here its instance label is a hash like a1e9ac0eb2e6 --
                    // showing that as a device name is worse than showing nothing.
                    PeerNames.note(address, name);
                    if (!name.equals(PeerNames.of(address))) continue;
                    named++;
                    // One line per device, not per packet: this is the only place the name
                    // is known to have been learned, and a round that repeats the same
                    // answer fifty times should not push fifty lines past it.
                    Trace.write("browse: \"" + name + "\" is " + address);
                }
            }
            return named;
        } catch (DnsMessage.FormatException unreadable) {
            // Somebody else's answer, or a record set this parser does not know. The row
            // keeps its instance name; nothing about the browse depends on this packet.
            return 0;
        }
    }

    /** Whether {@code name} is an instance of the Continuity service. */
    private static boolean isNameService(String name) {
        String lower = normalize(name).toLowerCase(Locale.ROOT);
        return lower.endsWith("." + NAME_SERVICE) || lower.equals(NAME_SERVICE);
    }

    /** {@code iPhone\.\032._companion-link._tcp.local} to {@code iPhone 2}. */
    private static String instanceLabel(String instanceName) {
        String name = DnsMessage.trimDot(instanceName == null ? "" : instanceName);
        if (name.toLowerCase(Locale.ROOT).endsWith("." + NAME_SERVICE)) {
            name = name.substring(0, name.length() - NAME_SERVICE.length() - 1);
        }
        return AirDropPeer.unescape(name).trim();
    }

    private static String normalize(String name) {
        return DnsMessage.trimDot(name == null ? "" : name);
    }

    /**
     * Parses one datagram into peers, ignoring anything that is not an answer.
     *
     * <p>Queries from other devices travel the same group and land on the same socket,
     * so a receive loop that trusted every packet would answer its own question with
     * somebody else's.
     */
    static List<AirDropPeer> peersIn(byte[] packet, int length) {
        return peersIn(packet, length, null);
    }

    /**
     * The same, remembering TXT records in {@code pool} and reading back what earlier
     * packets put there.
     *
     * <p>Both directions matter. A TXT-only packet adds nothing to the returned list --
     * no address, no port, so nothing to dial -- but it must still be remembered, or the
     * SRV that follows has no capability word to pair with. And an SRV-only packet has
     * to find a TXT that arrived before it, which {@link AirDropPeer#from(DnsMessage, Map)}
     * takes care of once it is handed the pool.
     *
     * @param pool records seen so far in this browse, keyed by instance name; may be null
     */
    static List<AirDropPeer> peersIn(byte[] packet, int length, Map<String, DnsMessage.Txt> pool) {
        if (packet == null || length <= 0) return Collections.emptyList();
        byte[] slice = packet.length == length ? packet : java.util.Arrays.copyOf(packet, length);
        try {
            DnsMessage message = DnsMessage.parse(slice);
            if (!message.isResponse()) return Collections.emptyList();
            return AirDropPeer.from(message, pool);
        } catch (DnsMessage.FormatException unreadable) {
            return Collections.emptyList();
        }
    }

    /**
     * Gives peers already in {@code found} the TXT fields the pool has since learned.
     *
     * <p>A TXT record can arrive after the SRV that carried the address, and that packet
     * parses into no peer at all -- there is no SRV in it, so there is nothing to dial --
     * which means {@link #merge} has no entry to update. Without this pass the pool would
     * only ever help the packets that happen to come TXT-first, and half the devices on a
     * link would keep the empty certificate hash and the zero capability word that made
     * the list slow to act on.
     *
     * @return how many entries gained something
     */
    static int applyPool(List<AirDropPeer> found, Map<String, DnsMessage.Txt> pool) {
        if (found.isEmpty() || pool.isEmpty()) return 0;
        int changed = 0;
        for (int i = 0; i < found.size(); i++) {
            AirDropPeer peer = found.get(i);
            DnsMessage.Txt values = pool.get(poolKey(peer.serviceName));
            if (values == null) continue;
            AirDropPeer filled = new AirDropPeer(peer.serviceName, peer.displayName, peer.host,
                peer.port, values.get("h"), AirDropPeer.parseFlags(AirDropPeer.flagWord(values)),
                values.get("rp"), values.get("rpH"), peer.addresses);
            AirDropPeer combined = AirDropPeer.combine(peer, filled);
            if (combined != peer) {
                found.set(i, combined);
                changed++;
            }
        }
        return changed;
    }

    /** The form {@link AirDropPeer#from(DnsMessage, Map)} files pool entries under. */
    private static String poolKey(String serviceName) {
        if (serviceName == null) return "";
        return DnsMessage.trimDot(serviceName).toLowerCase(Locale.ROOT);
    }

    /**
     * Adds {@code from} to {@code into}, one entry per service instance.
     *
     * <p>Fields merge rather than replace. A responder splits its answer across packets,
     * and each packet carries part of the peer -- SRV and AAAA together, TXT separately --
     * so an entry is completed over several answers. Replacing wholesale would drop the
     * address on the TXT packet and the capability word on the SRV one.
     *
     * <p>A peer that still has no address and no port is not added: it cannot be dialled,
     * so it would be a row that does nothing until the packet carrying its address
     * arrives. It is not lost either -- its TXT has already gone into the browse's pool.
     *
     * <p>Public because a caller that browses in rounds accumulates across them, and a
     * screen that replaces its whole list on every round would make devices blink out
     * between two packets.
     *
     * @return how many peers {@code into} gained or learned more about
     */
    public static int merge(List<AirDropPeer> into, List<AirDropPeer> from) {
        if (from == null || from.isEmpty()) return 0;
        int changed = 0;
        for (AirDropPeer peer : from) {
            int at = indexOf(into, peer);
            if (at < 0) {
                if (peer.addresses.isEmpty() || peer.port <= 0) continue;
                // The instance name is not a stable identity, and this is where that shows.
                // Apple rotates it -- observed on one iPhone, one AWDL MAC, going from
                // 0f3870 back to a fresh name and back again within a few minutes, with the
                // host UUID changing on every announcement as well. Keyed on the name and
                // never expired, each rotation left the previous entry behind and the peer
                // list grew a row for a device that no longer answers: "found 2" for one
                // phone, and the stale row was the one sorted first, so a tap went to an
                // identity the peer had already retired.
                //
                // An address is what actually identifies a device on this link. Two
                // separate devices cannot hold the same link-local IPv6 at the same time,
                // so a new instance that resolves to an address we already have is the
                // same device wearing a new name: supersede the old row instead of adding
                // beside it. Replaced rather than combined, because combining keeps the
                // name of the entry already in the list -- which is the one being retired.
                int same = indexOfSameDevice(into, peer);
                if (same >= 0) {
                    // Renamed in place, keeping the fields the old row had that this answer
                    // did not carry: an SRV-only answer still arrives with no TXT behind it,
                    // and a row that lost its capability word would stop being offerable
                    // exactly when the peer had finally answered again.
                    into.set(same, AirDropPeer.combine(peer, into.get(same)));
                    changed++;
                    continue;
                }
                into.add(peer);
                changed++;
            } else {
                AirDropPeer combined = AirDropPeer.combine(into.get(at), peer);
                if (combined != into.get(at)) {
                    into.set(at, combined);
                    changed++;
                }
            }
        }
        return changed;
    }

    private static int indexOf(List<AirDropPeer> peers, AirDropPeer peer) {
        String key = keyOf(peer);
        for (int i = 0; i < peers.size(); i++) {
            if (keyOf(peers.get(i)).equals(key)) return i;
        }
        return -1;
    }

    /**
     * The entry that is the same device as {@code peer} under a different instance name.
     *
     * <p>An address is the only identity an AirDrop answer offers that outlives a rename:
     * the instance name is rotated by the peer, the hostname is a fresh UUID on every
     * announcement, and an iPhone's TXT carries no certificate hash at all. Two devices
     * cannot answer from one link-local address at the same moment, so an overlap is the
     * same device renamed rather than a second device.
     *
     * <p>Case-folded because the address reaches here as text: one path formats it from
     * bytes and another echoes what a responder wrote, and those two spell a hexadecimal
     * digit differently.
     */
    private static int indexOfSameDevice(List<AirDropPeer> peers, AirDropPeer peer) {
        if (peer.addresses.isEmpty()) return -1;
        for (int i = 0; i < peers.size(); i++) {
            AirDropPeer other = peers.get(i);
            for (String address : peer.addresses) {
                for (String theirs : other.addresses) {
                    if (address != null && address.equalsIgnoreCase(theirs)) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Identity of a peer across answers. The service instance name is what a responder
     * repeats; the host is only a tie-breaker for the odd case of a device advertising
     * two instances with the same name, which cannot happen on one link.
     */
    private static String keyOf(AirDropPeer peer) {
        String name = peer.serviceName == null ? "" : peer.serviceName;
        return name.toLowerCase(Locale.ROOT);
    }

    /** Drops the peer that is this device, so the list never offers "send to yourself". */
    public static List<AirDropPeer> excluding(List<AirDropPeer> peers, String certHash) {
        if (peers == null || peers.isEmpty()) return Collections.emptyList();
        if (certHash == null || certHash.isEmpty()) return peers;
        String mine = certHash.toLowerCase(Locale.ROOT);
        List<AirDropPeer> kept = new ArrayList<>(peers.size());
        for (AirDropPeer peer : peers) {
            String theirs = peer.certHash == null ? "" : peer.certHash.toLowerCase(Locale.ROOT);
            if (!theirs.equals(mine)) kept.add(peer);
        }
        return kept;
    }

    /** The group address with the interface zone attached, or without it when refused. */
    private InetAddress group(NetworkInterface network) throws IOException {
        try {
            return InetAddress.getByName(MdnsResponder.GROUP + "%" + network.getName());
        } catch (IOException unscoped) {
            return InetAddress.getByName(MdnsResponder.GROUP);
        }
    }

    private static void send(MulticastSocket socket, byte[] query, InetAddress to)
        throws IOException {
        socket.send(new DatagramPacket(query, query.length,
            new InetSocketAddress(to, MdnsResponder.PORT)));
    }

    /** First link-local IPv6 on the interface: the only address an AWDL peer answers on. */
    private static InetAddress localLinkAddress(NetworkInterface network) {
        for (InetAddress address : Collections.list(network.getInetAddresses())) {
            if (address instanceof java.net.Inet6Address && address.isLinkLocalAddress()) {
                return address;
            }
        }
        return null;
    }

    private static int id() {
        return (int) (System.nanoTime() & 0x7fff);
    }
}
