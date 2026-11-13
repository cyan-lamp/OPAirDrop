package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A discovered (or advertised) AirDrop endpoint: service instance + TXT + addresses. */
public final class AirDropPeer {
    public static final String SERVICE_TYPE = "_airdrop._tcp";
    public static final String DOMAIN = "local";
    public static final String SERVICE = SERVICE_TYPE + "." + DOMAIN;
    public static final int DEFAULT_PORT = 8770;

    /** Record kinds in an announcement; see {@link #announce(String, String, int, Map, List, int, int)}. */
    public static final int RECORD_PTR = 1;
    public static final int RECORD_SRV = 2;
    public static final int RECORD_TXT = 4;
    public static final int RECORD_ADDRESS = 8;
    public static final int RECORD_ALL = RECORD_PTR | RECORD_SRV | RECORD_TXT | RECORD_ADDRESS;

    /**
     * Sentinel for "leave each record the lifetime it was written with".
     *
     * <p>Negative rather than zero because zero is already spoken for: a record with a
     * zero TTL is the withdrawal of RFC 6762 §10.1, so {@code 0} has to reach the wire as
     * {@code 0}. Any negative value would do; this one is named so the call sites read as
     * a choice rather than as a magic number that happens to be invalid.
     */
    public static final int TTL_AS_PUBLISHED = -1;

    public final String serviceName;
    public final String displayName;
    public final String host;
    public final int port;
    public final String certHash;
    public final long flags;
    public final String receiverProtocol;
    public final String receiverProtocolHash;
    public final List<String> addresses;

    AirDropPeer(String serviceName, String displayName, String host, int port, String certHash,
                long flags, String receiverProtocol, String receiverProtocolHash, List<String> addresses) {
        this.serviceName = serviceName;
        this.displayName = displayName;
        this.host = host;
        this.port = port;
        this.certHash = certHash;
        this.flags = flags;
        this.receiverProtocol = receiverProtocol;
        this.receiverProtocolHash = receiverProtocolHash;
        this.addresses = List.copyOf(addresses);
    }

    /** Picks SRV/TXT/AAAA for the first `_airdrop._tcp` instance found in a response. */
    public static List<AirDropPeer> from(DnsMessage message) throws DnsMessage.FormatException {
        return from(message, null);
    }

    /**
     * The same, with a pool of TXT records carried over from earlier packets.
     *
     * <p>A responder is free to split one answer across several packets, and Apple's
     * does: the SRV and the AAAA arrive together, the TXT in a packet of its own. Reading
     * one packet at a time therefore produced peers with no {@code h}, no {@code fl} and
     * no {@code rp} -- an empty certificate hash, a capability word of zero -- even though
     * the device had announced all three. Passing the pool in lets a later packet fill
     * those fields, and lets a TXT that arrives before the SRV be remembered until the
     * address shows up.
     *
     * @param txtPool records seen so far, keyed by normalised instance name; may be null
     */
    public static List<AirDropPeer> from(DnsMessage message, Map<String, DnsMessage.Txt> txtPool)
            throws DnsMessage.FormatException {
        Map<String, DnsMessage.Record> srv = new LinkedHashMap<>();
        Map<String, DnsMessage.Record> txt = new LinkedHashMap<>();
        Map<String, List<String>> v6 = new LinkedHashMap<>();

        for (DnsMessage.Record record : message.records()) {
            if (record.type == DnsMessage.TYPE_SRV && isAirdropService(record.name)) {
                srv.put(normalize(record.name), record);
            }
        }
        for (DnsMessage.Record record : message.records()) {
            String key = normalize(record.name);
            if (record.type == DnsMessage.TYPE_TXT) {
                DnsMessage.Txt values = record.txt();
                if (txtPool != null) txtPool.put(key, values);
                txt.put(key, record);
            }
            if (record.type == DnsMessage.TYPE_AAAA) v6.computeIfAbsent(key, k -> new ArrayList<>()).add(record.addressV6());
        }

        List<AirDropPeer> peers = new ArrayList<>(srv.size());
        for (Map.Entry<String, DnsMessage.Record> entry : srv.entrySet()) {
            DnsMessage.Record record = entry.getValue();
            DnsMessage.Record txtRecord = txt.get(entry.getKey());
            DnsMessage.Txt values;
            if (txtRecord != null) {
                values = txtRecord.txt();
            } else if (txtPool != null && txtPool.containsKey(entry.getKey())) {
                values = txtPool.get(entry.getKey());
            } else {
                values = DnsMessage.Txt.parse("");
            }
            String host = record.target();
            List<String> addresses = v6.getOrDefault(normalize(host), List.of());
            peers.add(new AirDropPeer(
                record.name,
                displayNameOf(record.name),
                host,
                record.port(),
                values.get("h"),
                parseFlags(flagWord(values)),
                values.get("rp"),
                values.get("rpH"),
                addresses));
        }
        return peers;
    }

    /**
     * Same instance, with whatever {@code fresh} learned that {@code current} lacked.
     *
     * <p>Answers arrive in pieces and each piece only knows part of the peer, so replacing
     * one with the other loses information either way: a TXT-only packet has the
     * capability word but no address, an SRV-only packet the reverse. Merging field by
     * field is the only combination that ends up complete.
     */
    static AirDropPeer combine(AirDropPeer current, AirDropPeer fresh) {
        List<String> addresses = fresh.addresses.isEmpty() ? current.addresses : fresh.addresses;
        int port = fresh.port > 0 ? fresh.port : current.port;
        String host = blank(fresh.host) ? current.host : fresh.host;
        String certHash = blank(fresh.certHash) ? current.certHash : fresh.certHash;
        long flags = fresh.flags != 0L ? fresh.flags : current.flags;
        String rp = blank(fresh.receiverProtocol) ? current.receiverProtocol : fresh.receiverProtocol;
        String rpH = blank(fresh.receiverProtocolHash) ? current.receiverProtocolHash : fresh.receiverProtocolHash;

        // By value, not by identity. Every answer is parsed into fresh strings and a fresh
        // address list, so an identity comparison calls any repeat packet a change: the
        // list re-renders on each one and every "did this teach us anything" counter ticks,
        // which is exactly what {@link AirDropBrowser#merge} returns to its caller.
        if (addresses.equals(current.addresses) && port == current.port
            && Objects.equals(host, current.host)
            && Objects.equals(certHash, current.certHash)
            && flags == current.flags
            && Objects.equals(rp, current.receiverProtocol)
            && Objects.equals(rpH, current.receiverProtocolHash)) {
            return current;
        }
        return new AirDropPeer(current.serviceName, current.displayName, host, port, certHash,
            flags, rp, rpH, addresses);
    }

    private static boolean blank(String value) {
        return value == null || value.isEmpty();
    }

    /** Builds a DNS query asking for the whole service (PTR + instance records). */
    public static byte[] query() {
        return DnsMessage.query(SERVICE, DnsMessage.TYPE_PTR);
    }

    /** Builds our own announcement: PTR, SRV, TXT and optional AAAA. */
    public static byte[] announce(String instance, String host, int port, Map<String, String> txt,
                                  List<String> addresses) {
        return announce(instance, host, port, txt, addresses, 0, RECORD_ALL);
    }

    /**
     * Same, but with an explicit message id and a mask of record kinds. The mDNS
     * responder uses this to answer a query with only the records it asked for,
     * echoing the query id when the answer goes unicast.
     */
    public static byte[] announce(String instance, String host, int port, Map<String, String> txt,
                                  List<String> addresses, int id, int types) {
        return announce(instance, host, port, txt, addresses, id, types, TTL_AS_PUBLISHED);
    }

    /**
     * Same, with every record's time to live forced to {@code ttl}.
     *
     * <p>{@code 0} is the withdrawal of RFC 6762 §10.1: a record sent with a zero TTL
     * means "delete this", and it is the only way to take back an instance that this
     * device published earlier. A responder that merely stops announcing is remembered
     * for the full lifetime of whatever it last said, which for the PTR here is
     * seventy-five minutes -- that is not a cache detail, it is how long a peer keeps
     * showing a device that is not there.
     *
     * <p>{@link #TTL_AS_PUBLISHED} keeps the per-record values instead.
     */
    public static byte[] announce(String instance, String host, int port, Map<String, String> txt,
                                  List<String> addresses, int id, int types, int ttl) {
        String serviceName = escapeLabel(instance) + "." + SERVICE;
        DnsMessage.Builder builder =
            new DnsMessage.Builder(id, DnsMessage.FLAG_RESPONSE | DnsMessage.FLAG_AUTHORITATIVE);

        if ((types & RECORD_PTR) != 0) {
            ByteArrayOutputStream ptr = new ByteArrayOutputStream();
            DnsMessage.writeName(ptr, serviceName);
            builder.answer(SERVICE, DnsMessage.TYPE_PTR, ttl(4500, ttl), ptr.toByteArray());
        }

        if ((types & RECORD_SRV) != 0) {
            ByteArrayOutputStream srv = new ByteArrayOutputStream();
            DnsMessage.write16(srv, 0);
            DnsMessage.write16(srv, 0);
            DnsMessage.write16(srv, port);
            DnsMessage.writeName(srv, host);
            builder.answer(serviceName, DnsMessage.TYPE_SRV, ttl(120, ttl), srv.toByteArray());
        }

        if ((types & RECORD_TXT) != 0) {
            ByteArrayOutputStream txtOut = new ByteArrayOutputStream();
            for (Map.Entry<String, String> entry : txt.entrySet()) {
                byte[] raw = (entry.getKey() + "=" + entry.getValue()).getBytes(StandardCharsets.UTF_8);
                txtOut.write(raw.length);
                txtOut.write(raw, 0, raw.length);
            }
            builder.answer(serviceName, DnsMessage.TYPE_TXT, ttl(4500, ttl), txtOut.toByteArray());
        }

        if ((types & RECORD_ADDRESS) != 0) {
            for (String address : addresses) {
                byte[] raw = ipv6Bytes(address);
                if (raw != null) builder.answer(host, DnsMessage.TYPE_AAAA, ttl(120, ttl), raw);
            }
        }
        return builder.build();
    }

    /** {@code override} when a caller forced a lifetime, the record's own value otherwise. */
    private static int ttl(int published, int override) {
        return override == TTL_AS_PUBLISHED ? published : override;
    }

    /**
     * Escapes the two bytes that would otherwise be read as structure: a dot would
     * split the label, a backslash would start an escape. Everything else — spaces
     * included — travels verbatim, which is what mDNS puts on the wire.
     */
    static String escapeLabel(String label) {
        StringBuilder out = new StringBuilder(label.length() + 4);
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '\\' || c == '.') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    static byte[] ipv6Bytes(String address) {
        try {
            return java.net.InetAddress.getByName(address).getAddress();
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    static boolean isAirdropService(String name) {
        String lower = normalize(name).toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(SERVICE) || lower.equals(SERVICE);
    }

    /**
     * Whether {@code other} is the same device this row is about.
     *
     * <p>A row is a snapshot, and a receiver does not keep the snapshot true: when iOS
     * rebuilds its AirDrop service the SRV target changes, the port can change, and the
     * addresses usually stay — but the instance name, which is what the row is titled
     * with, survives. Recovering from a refused offer means finding the same device again
     * rather than asking the user to hunt for a row that never left the screen.
     */
    public boolean sameDevice(AirDropPeer other) {
        if (other == null) return false;
        if (serviceName != null && serviceName.equalsIgnoreCase(other.serviceName)) return true;
        return displayName != null && displayName.equalsIgnoreCase(other.displayName);
    }

    static String displayNameOf(String serviceInstance) {
        String lower = serviceInstance.toLowerCase(java.util.Locale.ROOT);
        int end = lower.lastIndexOf("." + SERVICE);
        String name = end > 0 ? serviceInstance.substring(0, end) : serviceInstance;
        return unescape(name);
    }

    static String unescape(String label) {
        StringBuilder out = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c == '\\' && i + 1 < label.length()) {
                char next = label.charAt(i + 1);
                if (Character.isDigit(next) && i + 3 < label.length()) {
                    out.append((char) Integer.parseInt(label.substring(i + 1, i + 4)));
                    i += 3;
                    continue;
                }
                out.append(next);
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The capability word, under whichever key the peer chose.
     *
     * <p>iOS publishes this field as TXT {@code flags} — a capture of an iPhone 15 Pro
     * answering on {@code _airdrop._tcp} carries exactly one string, {@code flags=111611},
     * and no {@code fl} at all. Reading only {@code fl} therefore left every Apple peer at
     * {@link #flags} 0, which is indistinguishable from "this peer declared nothing", so
     * the bits that decide the archive format were never actually consulted. Both names
     * are accepted; the draft's spelling wins when a peer sends both so that a peer echoing
     * our own answer keeps parsing the way it always did.
     */
    static String flagWord(DnsMessage.Txt values) {
        String draft = values.get("fl");
        return draft == null || draft.isEmpty() ? values.get("flags") : draft;
    }

    /** The capability word is a decimal string, under {@code fl} or {@code flags} (MDNS-4). */
    static long parseFlags(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        try {
            return Long.parseLong(raw, 10);
        } catch (NumberFormatException decimal) {
            // Some stacks publish the same number in hex; a decimal parse of "89" gives 89
            // while the bits meant 0x89, so the fallback is worth having -- but only after
            // the spec'd form has been tried.
            try {
                return Long.parseLong(raw, 16);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    private static String normalize(String name) {
        return DnsMessage.trimDot(name).toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return displayName + " @ " + (addresses.isEmpty() ? host : addresses.get(0)) + ":" + port
            + (certHash.isEmpty() ? "" : " h=" + certHash.substring(0, Math.min(12, certHash.length())) + "…");
    }
}
