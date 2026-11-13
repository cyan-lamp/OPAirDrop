package com.oshare.airdrop.core;

import java.io.File;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything an AirDrop receiver is, in one object: an identity, an mDNS responder
 * that advertises it, and the TLS endpoint on 8770 that accepts transfers.
 *
 * <p>Order matters. The identity has to exist before the {@code h=} in the TXT record
 * can be right, the server has to be bound before the SRV record names its port, and
 * the responder is started last because it is the only part that tells the world we
 * are here. Shutdown runs in reverse.
 *
 * <p>Nothing here imports Android: the whole receive path is exercisable on the JVM
 * over loopback and on a device through {@code app_process}, which is how it was
 * debugged before any APK existed.
 */
public final class AirDropLink implements AutoCloseable {
    /** The vif name the driver insists on; see {@code mac80211.c}. */
    public static final String DEFAULT_INTERFACE = MdnsResponder.DEFAULT_INTERFACE;

    /**
     * Capability bits for the TXT {@code flags} entry, written in decimal.
     *
     * <p>The number is {@code 137} = {@code 0x089} = SupportsURLSharing |
     * SupportsMixedTypes | SupportsDiscoverMaybe, which is the minimal receiver set
     * SPEC MDNS-5 names by number ("A minimal receiver SHOULD therefore advertise
     * {@code 0x089}"). MDNS-5 also refuses any receiver that sets neither
     * SupportsPipelining nor SupportsMixedTypes, so this is not decoration: a record
     * without one of those two bits is discarded before it is ever resolved.
     *
     * <p>It is decimal because that is how the field is read. A phone's own entry in
     * this same record says {@code flags=13311}, and a decimal parse of the hex string
     * this constant used to hold -- {@code 9e1b} -- stops at the {@code e} and yields
     * {@code 9}. Nine is still a legal word (URLSharing | MixedTypes) which is why the
     * mistake never failed loudly; it just silently dropped SupportsDiscoverMaybe, the
     * one bit that says "you may post /Discover to me". {@link AirDropPeer#parseFlags}
     * keeps its hex fallback for peers that publish the other way round.
     */
    public static final String DEFAULT_FLAGS = "137";

    public interface Listener {
        /** All three pieces are up; {@code host} is the name peers will see. */
        void onStarted(Identity identity, String host, int serverPort, int mdnsPort);

        /** A peer announced itself, or its records changed. */
        void onPeer(AirDropPeer peer);

        /** A sender is offering something right now. */
        void onAsk(AirDropProtocol.Ask ask);

        /** Files landed; {@code files} are the members just written. */
        void onReceived(List<File> files);

        void onError(String message, Throwable error);
    }

    private final File identityDirectory;
    private final File inboxDirectory;
    private final String computerName;
    private final String modelName;
    private final String interfaceName;
    private final String flags;
    private final Listener listener;

    /**
     * The DNS-SD instance name, which is not the computer name.
     *
     * <p>SPEC MDNS-1 makes the instance name the receiver's <em>service ID</em> and
     * defines that ID as twelve lowercase hex digits, six random bytes; MDNS-3 adds
     * that it stays the same for the whole visible session and is reused as
     * {@code SenderID} in {@code /Discover} and {@code /Ask}. The peer we are trying
     * to appear in does exactly this -- its own record reads
     * {@code 87eb19167bcd._airdrop._tcp.local} -- so an instance called "Air" is the
     * one shape in the whole record a sender cannot mistake for AirDrop. The display
     * name a person reads is a different field entirely ({@code ReceiverComputerName}
     * in the {@code /Discover} answer); this one is an identifier.
     *
     * <p>Assigned in {@link #start()} from {@link ServiceId} rather than here, because
     * the value is only useful if it survives the process: a fresh random ID per launch
     * is a fresh random instance per launch, and a peer that cached the last one lists
     * two devices until the PTR expires -- seventy-five minutes of a share sheet showing
     * a phone that is not there. The stored ID makes the second launch announce what the
     * peer already knows; the IDs stored beside it are what gets withdrawn.
     */
    private volatile String serviceId;

    private volatile Identity identity;
    private volatile ReceiveHandler handler;
    private volatile AirDropServer server;
    private volatile MdnsResponder responder;
    private volatile String host;

    public AirDropLink(File identityDirectory, File inboxDirectory, String computerName,
                       String modelName, String interfaceName) {
        this(identityDirectory, inboxDirectory, computerName, modelName, interfaceName, null);
    }

    public AirDropLink(File identityDirectory, File inboxDirectory, String computerName,
                       String modelName, String interfaceName, Listener listener) {
        this(identityDirectory, inboxDirectory, computerName, modelName, interfaceName,
            DEFAULT_FLAGS, listener);
    }

    public AirDropLink(File identityDirectory, File inboxDirectory, String computerName,
                       String modelName, String interfaceName, String flags, Listener listener) {
        if (identityDirectory == null) throw new IllegalArgumentException("identityDirectory == null");
        if (inboxDirectory == null) throw new IllegalArgumentException("inboxDirectory == null");
        if (computerName == null || computerName.trim().isEmpty()) {
            throw new IllegalArgumentException("empty computer name");
        }
        this.identityDirectory = identityDirectory;
        this.inboxDirectory = inboxDirectory;
        this.computerName = computerName.trim();
        this.modelName = modelName == null || modelName.trim().isEmpty() ? "Android" : modelName.trim();
        this.interfaceName = interfaceName;
        this.flags = flags == null ? DEFAULT_FLAGS : flags;
        this.listener = listener;
    }

    /**
     * Brings the endpoint up. Blocking: RSA generation alone can take a couple of
     * seconds the first time, so this belongs on a worker thread.
     */
    public void start() throws IOException {
        start(AirDropPeer.DEFAULT_PORT, MdnsResponder.PORT);
    }

    /** Explicit ports; {@code 0} picks a free one, which tests rely on. */
    public void start(int serverPort, int mdnsPort) throws IOException {
        if (responder != null || server != null) throw new IllegalStateException("already started");

        if (interfaceName != null && !hasInterface(interfaceName)) {
            // Without the vif there is no link-local address and no multicast group;
            // starting anyway would advertise an endpoint nobody can reach.
            throw new IOException("interface " + interfaceName
                + " is missing; bring the awdl link up first (awdl-link.sh up)");
        }

        Identity current;
        try {
            current = new IdentityStore(identityDirectory).loadOrCreate(computerName);
        } catch (GeneralSecurityException broken) {
            throw new IOException("cannot create the AirDrop identity", broken);
        }
        identity = current;
        host = hostNameFor(computerName);

        // Read back before anything is announced. Same ID as the previous launch means the
        // peer updates the device it already has; a fresh one means a second device beside
        // it, which is exactly the "two Air" a share sheet showed.
        ServiceId service = ServiceId.loadOrCreate(identityDirectory);
        serviceId = service.current;

        Inbox inbox = new Inbox(inboxDirectory);
        ReceiveHandler receiving = new ReceiveHandler(computerName, modelName, inbox);
        receiving.setListener(new ReceiveHandler.Listener() {
            @Override
            public void onAsk(AirDropProtocol.Ask ask) {
                Listener target = listener;
                if (target != null) target.onAsk(ask);
            }

            @Override
            public void onReceived(List<File> files) {
                Listener target = listener;
                if (target != null) target.onReceived(files);
            }
        });
        handler = receiving;

        AirDropServer endpoint = new AirDropServer(current, receiving, this::report);
        // Wired before start() so the very first accepted connection is already covered;
        // the listener replays the current count when it is set, so ordering is only a
        // detail for readers.
        endpoint.setActiveListener(this::onConversationsChanged);
        endpoint.start(serverPort);
        server = endpoint;

        MdnsResponder advertising = new MdnsResponder(serviceId, host, endpoint.port(),
            new MdnsResponder.Source() {
                @Override
                public Map<String, String> txt() {
                    // Re-read per send so a rotated identity or a new flag applies to
                    // the very next answer instead of after a restart.
                    Map<String, String> txt = new LinkedHashMap<>();
                    txt.put("h", current.certHash());
                    // The same word under both names: iOS reads `flags`, the drafts this
                    // code grew up on say `fl`. Emitting one leaves half the peers on the
                    // other side of a silent "declared nothing", and the two strings cost
                    // six bytes against a 1400-byte answer.
                    txt.put("fl", flags);
                    txt.put("flags", flags);
                    return txt;
                }

                @Override
                public List<String> addresses() {
                    return linkLocalAddresses(interfaceName);
                }
            },
            new MdnsResponder.Listener() {
                @Override
                public void onPeer(AirDropPeer peer) {
                    Listener target = listener;
                    if (target != null) target.onPeer(peer);
                }

                @Override
                public void onError(String message, Throwable error) {
                    report(message, error);
                }
            });
        try {
            advertising.start(interfaceName, mdnsPort);
        } catch (IOException failed) {
            // Half a receiver is worse than none: undo the server so a caller that
            // retries does not find the port taken by a previous attempt.
            endpoint.close();
            server = null;
            handler = null;
            throw failed;
        }
        // Answers are repeated far more often than mDNS nominally requires, because
        // the listener on the other side is not a cache that slowly ages out: it is a
        // share sheet that a person opens for a few seconds and judges by what appears
        // in it. A phone that opens the sheet in the fifty-fifth second of a
        // sixty-second cycle sees an empty list and closes it again; over a link with
        // one peer and a few hundred bytes per answer, saying it every three seconds
        // costs nothing that the air does not already carry as beacons.
        //
        // Questions are the other half of that trade and go the other way: a receiver
        // asks nobody anything. It is found, not finding. Both intervals start at zero
        // because every question this side sends is a multicast round the peer has to
        // answer -- on an AWDL link where a single TLS flight already needs seconds of
        // slots, a question every three seconds plus a throwaway-port probe every few
        // more was competing with the handshake it was supposed to be enabling. The
        // symptom was six failed connections in two minutes, each dying with the peer's
        // ClientHello answered and our ServerHello never acknowledged. A caller that
        // really does need to look around (a sender listing devices) turns this back on
        // through {@link #setDiscovery(boolean)}.
        // Three seconds, and the earlier eight-second figure is retracted. That one rested
        // on the belief that the phone caches the record and re-asks when its share sheet
        // opens. Measured against a real phone it does not: the whole log of a session
        // held exactly one mDNS question, so the share sheet is built from whatever
        // announcements happen to arrive while it is open. Eight seconds therefore left
        // the entry with two seconds of headroom against the phone's own eviction timer,
        // and one lost frame was enough to make the device vanish from the panel -- which
        // is precisely the "appears late, then disappears" report this fixes. Three
        // seconds gives three independent chances inside that timer. The slot contention
        // that made eight attractive came from this side also asking questions and
        // probing every 250ms; both are off now, so the announcement is the only
        // scheduled sender and can afford to be this frequent.
        advertising.setIntervals(3_000L, 0L);
        advertising.setProbeInterval(0L);
        responder = advertising;

        // The history holds instances that earlier launches left in a peer's cache. A zero
        // TTL is the only thing that removes one, and these are independent records, so the
        // order against our own first announcement is cosmetic -- done first so the log
        // reads as "clean up, then appear".
        List<String> withdrawn = new ArrayList<>();
        for (String stale : service.stale) {
            if (advertising.withdraw(stale)) withdrawn.add(stale);
        }
        if (!withdrawn.isEmpty()) service.forget(identityDirectory, withdrawn);

        Listener target = listener;
        if (target != null) {
            target.onStarted(current, host, endpoint.port(), advertising.port());
        }
    }

    /**
     * Reacts to the endpoint's open-conversation count by standing the announcer down.
     *
     * <p>This is the whole reason the server publishes the count. The share sheet on the
     * other side is a listener, not a poller: it lists what was announced while it was
     * open, and it gives a clicked device about a second and a half to produce a
     * ServerHello before it gives up on the port and tries another one. Our announcement
     * runs every three seconds and is a frame that has to be placed in a window the peer's
     * radio is awake for -- the same currency the handshake is paying in. Standing it down
     * for the duration of the conversation is the cheapest slot that can be given back,
     * because at that moment being in the list has already done its job.
     *
     * <p>Called from the accept thread and from the connection threads, so it must be
     * cheap and must not block; {@link MdnsResponder#setPaused(boolean)} is both.
     */
    private void onConversationsChanged(int open) {
        MdnsResponder current = responder;
        if (current == null) {
            // Before start() has published it, connections cannot exist either.
            return;
        }
        current.setPaused(open > 0);
    }

    public Identity identity() {
        return identity;
    }

    public String host() {
        return host;
    }

    public String computerName() {
        return computerName;
    }

    /** The DNS-SD instance name, i.e. the service ID; {@code null} before {@link #start()}. */
    public String serviceId() {
        return serviceId;
    }

    public int serverPort() {
        AirDropServer current = server;
        return current == null ? -1 : current.port();
    }

    public int mdnsPort() {
        MdnsResponder current = responder;
        return current == null ? -1 : current.port();
    }

    public boolean isRunning() {
        return responder != null && server != null;
    }

    /** Peers currently visible on the link, newest state per instance. */
    public List<AirDropPeer> peers() {
        MdnsResponder current = responder;
        return current == null ? Collections.emptyList() : current.peers();
    }

    /** Re-announces immediately, e.g. after the user renamed the device. */
    public void announce() {
        MdnsResponder current = responder;
        if (current != null) current.announce();
    }

    /**
     * Turns outgoing mDNS questions on or off; off by default.
     *
     * <p>A receiver is discovered, it does not discover: everything it could learn by
     * asking is a peer it has no use for, and each question costs a multicast round
     * the peer must answer. So {@link #start()} leaves this off, and only a caller
     * that genuinely needs to populate a device list -- a sender, before the user has
     * picked anybody -- should ask for it.
     *
     * <p>{@code on} also enables the throwaway-port probe, which exists to tell "the
     * peer is not registered as a receiver" apart from "the peer does not answer
     * mDNS on this link at all". That distinction only matters while looking.
     */
    public void setDiscovery(boolean on) {
        MdnsResponder current = responder;
        if (current == null) return;
        current.setIntervals(3_000L, on ? 3_000L : 0L);
        current.setProbeInterval(on ? 4_000L : 0L);
    }

    /** The exact packet {@link #announce()} would send; empty once the link is down. */
    public byte[] announcement() {
        MdnsResponder current = responder;
        return current == null ? new byte[0] : current.announcement();
    }

    public ReceiveHandler handler() {
        return handler;
    }

    @Override
    public void close() {
        MdnsResponder advertising = responder;
        responder = null;
        if (advertising != null) {
            // Take the instance back before the socket goes. Without this the device
            // lingers in every peer's list for the PTR lifetime -- seventy-five minutes of
            // a share sheet offering a receiver that has stopped -- because a responder
            // that stops announcing is not a responder that has withdrawn anything. The
            // next launch republishes the same ID, so the cost is one packet.
            String current = serviceId;
            if (current != null) advertising.withdraw(current);
            advertising.close();
        }
        AirDropServer endpoint = server;
        server = null;
        if (endpoint != null) endpoint.close();
        handler = null;
    }

    private void report(String message, Throwable error) {
        Listener target = listener;
        if (target != null) target.onError(message, error);
    }

    static boolean hasInterface(String name) {
        try {
            return NetworkInterface.getByName(name) != null;
        } catch (SocketException unavailable) {
            return false;
        }
    }

    /**
     * Link-local v6 addresses of {@code name}, without the {@code %iface} scope that
     * {@link InetAddress#getHostAddress()} appends — the AAAA record is the bare
     * address, and the peer learns the scope from the interface it arrived on.
     */
    static List<String> linkLocalAddresses(String name) {
        if (name == null) return Collections.emptyList();
        NetworkInterface network;
        try {
            network = NetworkInterface.getByName(name);
        } catch (SocketException unavailable) {
            return Collections.emptyList();
        }
        if (network == null) return Collections.emptyList();
        List<String> addresses = new ArrayList<>();
        for (InterfaceAddress entry : network.getInterfaceAddresses()) {
            InetAddress address = entry.getAddress();
            if (!(address instanceof Inet6Address) || !address.isLinkLocalAddress()) continue;
            String text = address.getHostAddress();
            int scope = text.indexOf('%');
            if (scope >= 0) text = text.substring(0, scope);
            if (!addresses.contains(text)) addresses.add(text);
        }
        return Collections.unmodifiableList(addresses);
    }

    /**
     * Turns a device name into an mDNS host label. Dots become hyphens because the
     * label is concatenated with {@code .local}, and everything that would need
     * escaping on the wire is dropped — the display name travels to the sender in the
     * {@code /Discover} answer, not in this label.
     */
    static String hostNameFor(String computerName) {
        StringBuilder label = new StringBuilder();
        String trimmed = computerName == null ? "" : computerName.trim();
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
        while (start < label.length() && label.charAt(start) == '-') start++;
        label.delete(0, start);
        while (label.length() > 0 && label.charAt(label.length() - 1) == '-') {
            label.setLength(label.length() - 1);
        }
        String host = label.length() == 0 ? "opairdrop" : label.toString();
        // A single DNS label tops out at 63 bytes; truncate on a code point boundary.
        while (host.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63) {
            host = host.substring(0, host.offsetByCodePoints(host.length(), -1));
        }
        return host.toLowerCase(Locale.ROOT) + ".local";
    }
}
