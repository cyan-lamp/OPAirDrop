package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Codec checks for the mDNS layer; runs on a plain JVM, no device needed. */
public final class MdnsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        announcementRoundTrip();
        compressedResponseParses();
        txtFlagsParsing();
        appleFlagsKeyAccepted();
        malformedPacketsRejected();
        System.out.println("MdnsTest OK (" + checks + " checks)");
    }

    private static void announcementRoundTrip() throws Exception {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("h", "9f2c1d7b5a4e3f6071c8d9e0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4");
        txt.put("fl", "9e1b");
        byte[] packet = AirDropPeer.announce(
            "Makoto's 13", "makoto-13.local", 8770, txt, List.of("fe80::1c2d:3e4f:5a6b:7c8d"));

        DnsMessage message = DnsMessage.parse(packet);
        check(message.isResponse(), "announcement is a response");
        check(message.records(DnsMessage.TYPE_PTR).size() == 1, "one PTR");
        check(message.records(DnsMessage.TYPE_SRV).size() == 1, "one SRV");
        check(message.records(DnsMessage.TYPE_TXT).size() == 1, "one TXT");
        check(message.records(DnsMessage.TYPE_AAAA).size() == 1, "one AAAA");

        List<AirDropPeer> peers = AirDropPeer.from(message);
        check(peers.size() == 1, "one peer parsed");
        AirDropPeer peer = peers.get(0);
        eq("Makoto's 13", peer.displayName, "display name");
        eq(8770, peer.port, "port");
        eq("makoto-13.local", peer.host, "host");
        eq("9e1b", Long.toHexString(peer.flags), "flags");
        eq("9f2c1d7b5a4e3f6071c8d9e0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4", peer.certHash, "cert hash");
        eq(1, peer.addresses.size(), "address count");
        eq("fe80::1c2d:3e4f:5a6b:7c8d", peer.addresses.get(0), "address text");
    }

    private static void compressedResponseParses() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write16(out, 0);
        write16(out, 0x8400);
        write16(out, 0);
        write16(out, 1);
        write16(out, 0);
        write16(out, 0);
        // record name: "iPhone._airdrop._tcp.local" written once at offset 12
        int nameOffset = out.size();
        DnsMessage.writeName(out, "iPhone._airdrop._tcp.local");
        write16(out, DnsMessage.TYPE_SRV);
        write16(out, DnsMessage.CLASS_IN);
        write32(out, 120);
        ByteArrayOutputStream rdata = new ByteArrayOutputStream();
        write16(rdata, 0);
        write16(rdata, 0);
        write16(rdata, 8770);
        DnsMessage.writeName(rdata, "iPhone.local");
        byte[] raw = rdata.toByteArray();
        write16(out, raw.length);
        out.write(raw, 0, raw.length);

        byte[] packet = out.toByteArray();
        DnsMessage message = DnsMessage.parse(packet);
        DnsMessage.Record record = message.records(DnsMessage.TYPE_SRV).get(0);
        eq(nameOffset, 12, "record name at offset 12");
        eq(8770, record.port(), "compressed SRV port");
        eq("iPhone.local", record.target(), "SRV target");
        eq("iPhone", AirDropPeer.displayNameOf(record.name), "instance display name");
    }

    private static void txtFlagsParsing() {
        // MDNS-4: the capability word is published in decimal, so digits are read as
        // digits even though they would also be legal hex. "1234" is one thousand two
        // hundred and thirty-four, not 0x1234 -- reading it as hex turns the URLSharing
        // bit into something else entirely.
        eq(1234L, AirDropPeer.parseFlags("1234"), "digits are decimal");
        eq(89L, AirDropPeer.parseFlags("89"), "decimal 89, not hex 0x89");
        eq(0x9e1bL, AirDropPeer.parseFlags("9e1b"), "letters force the hex fallback");
        eq(0L, AirDropPeer.parseFlags(""), "empty flags");
        eq(0L, AirDropPeer.parseFlags(null), "null flags");
        eq(0L, AirDropPeer.parseFlags("zz"), "invalid flags");
        eq("Makoto's 13", AirDropPeer.unescape("Makoto's 13"), "plain name");
        eq("a.b", AirDropPeer.unescape("a\\.b"), "escaped dot");
    }

    /**
     * The same peer described under both spellings of the capability key.
     *
     * <p>An iPhone 15 Pro answering on {@code _airdrop._tcp} carries a single TXT string,
     * {@code flags=111611}, and no {@code fl}. Reading only {@code fl} left every Apple
     * peer at flags 0 -- which the offer path cannot tell apart from "declared nothing",
     * so the DVZip bit in that word was never looked at. Both keys must land on the same
     * field, and both must survive the late-TXT pool that fills a peer found earlier.
     */
    private static void appleFlagsKeyAccepted() throws Exception {
        Map<String, String> apple = new LinkedHashMap<>();
        apple.put("flags", "111611");
        DnsMessage message = DnsMessage.parse(AirDropPeer.announce(
            "iPhone", "iphone.local", 8770, apple, List.of("fe80::1")));
        eq(0x1b3fbL, AirDropPeer.from(message).get(0).flags, "Apple's `flags` key is read");
        eq(0x2L, AirDropPeer.from(message).get(0).flags & 0x2L, "and its DVZip bit with it");

        Map<String, String> draft = new LinkedHashMap<>();
        draft.put("fl", "111611");
        DnsMessage legacy = DnsMessage.parse(AirDropPeer.announce(
            "iPhone", "iphone.local", 8770, draft, List.of("fe80::1")));
        eq(0x1b3fbL, AirDropPeer.from(legacy).get(0).flags, "the draft's `fl` key still works");

        // Both at once: the draft spelling is the one already in every log this project
        // has kept, so it decides when they disagree rather than the answer depending on
        // map order.
        Map<String, String> both = new LinkedHashMap<>();
        both.put("fl", "21");
        both.put("flags", "111611");
        DnsMessage mixed = DnsMessage.parse(AirDropPeer.announce(
            "iPhone", "iphone.local", 8770, both, List.of("fe80::1")));
        eq(21L, AirDropPeer.from(mixed).get(0).flags, "`fl` wins when both are present");
    }

    private static void malformedPacketsRejected() {
        try {
            DnsMessage.parse(new byte[8]);
            throw new AssertionError("short header accepted");
        } catch (DnsMessage.FormatException expected) {
            check(true, "short header rejected");
        }
        try {
            DnsMessage.parse(new byte[] {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x3f});
            throw new AssertionError("truncated question accepted");
        } catch (DnsMessage.FormatException expected) {
            check(true, "truncated question rejected");
        }
    }

    private static void write16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void write32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24) & 0xff);
        out.write((int) (value >>> 16) & 0xff);
        out.write((int) (value >>> 8) & 0xff);
        out.write((int) value & 0xff);
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("failed: " + what);
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private MdnsTest() {
    }
}
