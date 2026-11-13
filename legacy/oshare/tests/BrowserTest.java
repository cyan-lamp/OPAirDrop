package com.oshare.airdrop.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline checks for {@link AirDropBrowser}: the parts that do not need a radio.
 *
 * <p>{@code browse()} itself needs a link and a peer, so what is checked here is
 * everything it decides <em>after</em> a packet arrives — the merge rules that turn a
 * stream of answers from several devices into one list, and the refusal to treat a
 * query as an answer. Both have been wrong in other implementations in ways that look
 * like "the peer disappeared" on a screen, which is why they are pinned here.
 */
public final class BrowserTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        queryAsksForUnicastAnswers();
        answersFromTwoDevicesMerge();
        duplicateAnswersDoNotDuplicateRows();
        richerAnswerReplacesPoorer();
        txtAndSrvInSeparatePacketsCompleteEachOther();
        sameDeviceSurvivesAServiceRebuild();
        queriesAreNotAnswers();
        brokenPacketsAreSkipped();
        selfIsExcluded();
        System.out.println("BrowserTest OK (" + checks + " checks)");
    }

    /**
     * The query has to carry the unicast-response bit and one PTR question. Without the
     * bit, answers go to the group on 5353, which a browser on an ephemeral port never
     * sees — the failure mode is an empty list and no error.
     */
    private static void queryAsksForUnicastAnswers() throws Exception {
        DnsMessage message = DnsMessage.parse(AirDropBrowser.query());
        check(!message.isResponse(), "query is not a response");
        check(message.records().isEmpty(), "a question carries no answers");
        List<DnsMessage.Question> questions = message.questions();
        eq(1, questions.size(), "exactly one question");
        eq(AirDropPeer.SERVICE, questions.get(0).name, "asked about the AirDrop service");
        eq(DnsMessage.TYPE_PTR, questions.get(0).type, "PTR question: the whole instance list");
        eq(DnsMessage.CLASS_IN, questions.get(0).cls, "internet class");
        check(questions.get(0).unicast, "unicast response requested");
    }

    /** Two devices, each a complete record set: both rows, in arrival order. */
    private static void answersFromTwoDevicesMerge() {
        List<AirDropPeer> peers = new ArrayList<>();
        int added = AirDropBrowser.merge(peers, answer("Anna's iPhone", "annas-iphone.local",
            "aa11", 8770, "fe80::1"));
        eq(1, added, "first device added");
        added = AirDropBrowser.merge(peers, answer("Desk Mac", "desk.local",
            "bb22", 8770, "fe80::2"));
        eq(1, added, "second device added");
        eq(2, peers.size(), "two rows");
        eq("Anna's iPhone", peers.get(0).displayName, "first row keeps its own name");
        eq("Desk Mac", peers.get(1).displayName, "second row keeps its own name");
        eq("fe80::1", peers.get(0).addresses.get(0), "first row keeps its own address");
        eq("aa11", peers.get(0).certHash, "cert hash carried through");
    }

    /** The same instance answering twice is one row, not two. */
    private static void duplicateAnswersDoNotDuplicateRows() {
        List<AirDropPeer> peers = new ArrayList<>();
        AirDropBrowser.merge(peers, answer("Anna's iPhone", "annas-iphone.local",
            "aa11", 8770, "fe80::1"));
        int added = AirDropBrowser.merge(peers, answer("Anna's iPhone", "annas-iphone.local",
            "aa11", 8770, "fe80::1"));
        eq(0, added, "repeat answer adds nothing");
        eq(1, peers.size(), "still one row");
    }

    /**
     * A name-only answer must not overwrite a complete one. Responders do send these —
     * a PTR on its own, or an SRV without TXT — and replacing would blank the address
     * the row is about to dial.
     */
    private static void richerAnswerReplacesPoorer() {
        List<AirDropPeer> peers = new ArrayList<>();
        AirDropBrowser.merge(peers, answer("Anna's iPhone", "annas-iphone.local",
            "aa11", 8770, "fe80::1"));

        List<AirDropPeer> hollow = new ArrayList<>(List.of(new AirDropPeer(
            "Anna's iPhone." + AirDropPeer.SERVICE_TYPE + "." + AirDropPeer.DOMAIN,
            "Anna's iPhone", "annas-iphone.local", 0, "", 0L, null, null, List.of())));
        AirDropBrowser.merge(peers, hollow);
        eq(1, peers.size(), "hollow answer does not add a row");
        eq("fe80::1", peers.get(0).addresses.get(0), "the address survived the hollow answer");
        eq(8770, peers.get(0).port, "the port survived the hollow answer");

        // And the reverse: a row that has an address but no TXT must be upgraded in place
        // when the TXT finally arrives, not doubled. A peer with no address and no port
        // is deliberately never added -- the row would do nothing until the packet that
        // carries its address shows up -- so the starting point here is an SRV packet,
        // not a name-only one.
        List<AirDropPeer> fresh = new ArrayList<>();
        AirDropBrowser.merge(fresh, partial("Anna's iPhone", "annas-iphone.local", 8770,
            "fe80::1", "aa11", AirDropPeer.RECORD_SRV | AirDropPeer.RECORD_ADDRESS, null));
        eq(1, fresh.size(), "an address is enough to be worth a row");
        eq("", fresh.get(0).certHash, "which starts with no certificate hash");
        eq(0L, fresh.get(0).flags, "and no capability word");

        AirDropBrowser.merge(fresh, answer("Anna's iPhone", "annas-iphone.local",
            "aa11", 8770, "fe80::1"));
        eq(1, fresh.size(), "the TXT upgraded the row, it did not add one");
        eq("aa11", fresh.get(0).certHash, "upgrade brought the hash");
        eq(0x9e1bL, fresh.get(0).flags, "upgrade brought the capability word");
    }

    /**
     * The same device keeps its identity when the receiver rebuilds its AirDrop service.
     *
     * <p>Measured on a phone that was still showing its AirDrop sheet: it came back under a
     * new SRV target — {@code 5ef8dfae-…local} became {@code 60e29e38-…local} — while the
     * instance name and the address stayed put. The row a user taps is a snapshot of the
     * old target, so recovering from a refused offer needs to find that row again without
     * asking the user to. Matching on the target, the port or the address would call this
     * a different device and report a failure for a peer that never went away.
     */
    private static void sameDeviceSurvivesAServiceRebuild() {
        AirDropPeer before = new AirDropPeer("6d5ef020da0f.airdrop._tcp.local",
            "6d5ef020da0f", "5ef8dfae-9d4f-4fbb-90bb-ac83fd08ef81.local", 8770, "", 0L,
            null, null, List.of("fe80::5433:87ff:fe25:dd81"));
        AirDropPeer after = new AirDropPeer("6d5ef020da0f.airdrop._tcp.local",
            "6d5ef020da0f", "60e29e38-0364-4331-afda-406d9879f375.local", 8770, "", 0L,
            null, null, List.of("fe80::5433:87ff:fe25:dd81"));
        check(before.sameDevice(after), "a rebuild under a new SRV target is the same device");

        AirDropPeer other = new AirDropPeer("be059b76a423.airdrop._tcp.local", "be059b76a423",
            "be059b76a423.local", 8770, "", 0L, null, null, List.of("fe80::1"));
        check(!before.sameDevice(other), "another phone on the link is not the same device");
        check(!before.sameDevice(null), "no peer is not the same device");
    }

    /**
     * TXT and SRV in separate packets still make one complete row.
     *
     * <p>Apple splits the answer: the SRV and the AAAA together, the TXT in a packet of
     * its own, in either order. Parsed packet by packet, the SRV one produced a row with
     * an empty certificate hash and a capability word of zero, and the TXT one produced
     * no row at all — so whichever came second was thrown away. That is what "finding the
     * device is slow" looked like: the row was there but hollow, and dialling it needed
     * another round of asking.
     */
    private static void txtAndSrvInSeparatePacketsCompleteEachOther() {
        // TXT first: no address, so no row, but the record has to be remembered.
        Map<String, DnsMessage.Txt> txtFirstPool = new HashMap<>();
        List<AirDropPeer> found = new ArrayList<>();
        List<AirDropPeer> txtOnly = partial("Anna's iPhone", "annas-iphone.local", 8770,
            "fe80::1", "aa11", AirDropPeer.RECORD_TXT, txtFirstPool);
        eq(0, txtOnly.size(), "a TXT-only packet is not a row");
        eq(0, AirDropBrowser.merge(found, txtOnly), "and it adds nothing to the list");

        List<AirDropPeer> srvLater = partial("Anna's iPhone", "annas-iphone.local", 8770,
            "fe80::1", "aa11", AirDropPeer.RECORD_SRV | AirDropPeer.RECORD_ADDRESS, txtFirstPool);
        eq(1, AirDropBrowser.merge(found, srvLater), "the SRV packet makes the row");
        eq("aa11", found.get(0).certHash, "with the hash the TXT packet had left in the pool");
        eq(21L, found.get(0).flags, "and the capability word, read as decimal");

        // SRV first: the row appears hollow, and the TXT packet has to complete it.
        Map<String, DnsMessage.Txt> srvFirstPool = new HashMap<>();
        List<AirDropPeer> later = new ArrayList<>();
        AirDropBrowser.merge(later, partial("Anna's iPhone", "annas-iphone.local", 8770,
            "fe80::1", "aa11", AirDropPeer.RECORD_SRV | AirDropPeer.RECORD_ADDRESS, srvFirstPool));
        eq("", later.get(0).certHash, "the SRV packet alone brings no hash");

        List<AirDropPeer> txtLater = partial("Anna's iPhone", "annas-iphone.local", 8770,
            "fe80::1", "aa11", AirDropPeer.RECORD_TXT, srvFirstPool);
        eq(0, AirDropBrowser.merge(later, txtLater), "the TXT packet adds no row");
        eq(1, AirDropBrowser.applyPool(later, srvFirstPool),
            "but it completes the row that is already there");
        eq("aa11", later.get(0).certHash, "the hash arrived after the address did");
        eq(1, later.size(), "and there is still one row");
    }

    /**
     * Queries travel the same group as answers. A browser that parsed them would find
     * an "AirDrop service" with no records and no name, and dial it.
     */
    private static void queriesAreNotAnswers() {
        eq(0, AirDropBrowser.peersIn(AirDropBrowser.query(), AirDropBrowser.query().length).size(),
            "a query is not a peer");
        check(AirDropBrowser.peersIn(null, 0).isEmpty(), "null packet tolerated");
        check(AirDropBrowser.peersIn(new byte[0], 0).isEmpty(), "empty packet tolerated");
    }

    /** One unreadable packet must not hide the readable ones around it. */
    private static void brokenPacketsAreSkipped() {
        byte[] truncated = new byte[] {0, 0, (byte) 0x84, 0, 0, 1, 0, 0, 0, 0, 0, 0};
        check(AirDropBrowser.peersIn(truncated, truncated.length).isEmpty(),
            "truncated answer is skipped, not thrown");
    }

    /** The app's own responder answers its own browse; that row must not be offered. */
    private static void selfIsExcluded() {
        List<AirDropPeer> peers = new ArrayList<>();
        AirDropBrowser.merge(peers, answer("This phone", "this-phone.local",
            "cccc", 8770, "fe80::3"));
        AirDropBrowser.merge(peers, answer("Anna's iPhone", "annas-iphone.local",
            "aa11", 8770, "fe80::1"));
        List<AirDropPeer> kept = AirDropBrowser.excluding(peers, "CCCC");
        eq(1, kept.size(), "own instance dropped, case-insensitively");
        eq("Anna's iPhone", kept.get(0).displayName, "the other device is the survivor");
        eq(2, AirDropBrowser.excluding(peers, null).size(), "no hash means no filtering");
    }

    /** Builds and parses one full answer, so the test walks the real record path. */
    private static List<AirDropPeer> answer(String instance, String host, String hash,
                                            int port, String address) {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("h", hash);
        txt.put("fl", "9e1b");
        byte[] packet = AirDropPeer.announce(instance, host, port, txt, List.of(address));
        try {
            return AirDropPeer.from(DnsMessage.parse(packet));
        } catch (DnsMessage.FormatException failed) {
            throw new AssertionError("self-built announcement did not parse: " + failed);
        }
    }

    /**
     * One partial answer — only the records named by {@code types} — parsed the way
     * {@code browse()} parses it, pool and all.
     *
     * <p>{@code fl} goes out as {@code 21}, decimal as MDNS-4 requires, so the assertions
     * that read it back also pin the number base.
     */
    private static List<AirDropPeer> partial(String instance, String host, int port,
                                             String address, String hash, int types,
                                             Map<String, DnsMessage.Txt> pool) {
        Map<String, String> txt = new LinkedHashMap<>();
        txt.put("h", hash);
        txt.put("fl", "21");
        byte[] packet = AirDropPeer.announce(instance, host, port, txt, List.of(address), 0, types);
        return AirDropBrowser.peersIn(packet, packet.length, pool);
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private BrowserTest() {
    }
}
