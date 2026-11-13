package com.oshare.airdrop.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checks for the mDNS responder: query matching, harvesting and one live socket. */
public final class MdnsResponderTest {
    private static int checks;

    private static final String INSTANCE = "Ma.koto's 13";
    private static final String HOST = "makoto-13.local";
    private static final String HASH =
        "9f2c1d7b5a4e3f6071c8d9e0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4";
    private static final String ADDRESS = "fe80::1c2d:3e4f:5a6b:7c8d";

    public static void main(String[] args) throws Exception {
        escapedInstanceSurvivesRoundTrip();
        announcedRecordsParse();
        servicePtrQueryAnswered();
        instanceQueriesAnswered();
        hostQueryAnswered();
        unrelatedAndMalformedTrafficIgnored();
        unicastQueryEchoesId();
        peersAreHarvestedOnce();
        ownAnnouncementIsIgnored();
        peersExpire();
        answersOverLoopbackSocket();
        System.out.println("MdnsResponderTest OK (" + checks + " checks)");
    }

    // --- wire shape -------------------------------------------------------

    private static void escapedInstanceSurvivesRoundTrip() throws Exception {
        MdnsResponder responder = newResponder(listener());
        DnsMessage message = DnsMessage.parse(responder.announcement());
        DnsMessage.Record srv = message.records(DnsMessage.TYPE_SRV).get(0);

        check(srv.name.contains("\\."), "instance dot is escaped on the wire");
        eq("Ma.koto's 13", AirDropPeer.displayNameOf(srv.name), "display name round trip");
        check(AirDropPeer.isAirdropService(srv.name), "escaped instance still matches the service");
        // The escaped dot is one label, so the name keeps exactly four labels.
        eq(4, srv.name.split("(?<!\\\\)\\.").length, "label count");
    }

    private static void announcedRecordsParse() throws Exception {
        MdnsResponder responder = newResponder(listener());
        DnsMessage message = DnsMessage.parse(responder.announcement());

        check(message.isResponse(), "announcement is a response");
        check((message.records().size() == 4), "four records");
        eq(0, message.id(), "multicast announcement id");

        List<AirDropPeer> peers = AirDropPeer.from(message);
        eq(1, peers.size(), "one peer");
        eq("Ma.koto's 13", peers.get(0).displayName, "peer display name");
        eq(8770, peers.get(0).port, "peer port");
        eq(HASH, peers.get(0).certHash, "peer cert hash");
        eq(0x9e1bL, peers.get(0).flags, "peer flags");
        eq(ADDRESS, peers.get(0).addresses.get(0), "peer address");
    }

    // --- query handling ---------------------------------------------------

    private static void servicePtrQueryAnswered() throws Exception {
        MdnsResponder responder = newResponder(listener());
        MdnsResponder.Reply reply = responder.handle(DnsMessage.query(AirDropPeer.SERVICE, DnsMessage.TYPE_PTR));

        check(reply != null, "PTR query answered");
        check(!reply.unicast, "multicast answer by default");
        DnsMessage answer = DnsMessage.parse(reply.packet);
        eq(1, answer.records(DnsMessage.TYPE_PTR).size(), "PTR present");
        eq(1, answer.records(DnsMessage.TYPE_SRV).size(), "SRV as additional");
        eq(1, answer.records(DnsMessage.TYPE_TXT).size(), "TXT as additional");
        eq(1, answer.records(DnsMessage.TYPE_AAAA).size(), "AAAA as additional");
        eq(0, answer.questions().size(), "answer carries no question");

        List<AirDropPeer> peers = AirDropPeer.from(answer);
        eq("Ma.koto's 13", peers.get(0).displayName, "answer names us");
    }

    private static void instanceQueriesAnswered() throws Exception {
        MdnsResponder responder = newResponder(listener());
        String instanceName = responder.serviceName();

        MdnsResponder.Reply srv = responder.handle(DnsMessage.query(instanceName, DnsMessage.TYPE_SRV));
        check(srv != null, "SRV query answered");
        DnsMessage srvAnswer = DnsMessage.parse(srv.packet);
        eq(1, srvAnswer.records(DnsMessage.TYPE_SRV).size(), "SRV answered");
        eq(0, srvAnswer.records(DnsMessage.TYPE_PTR).size(), "no unsolicited PTR");
        eq(1, srvAnswer.records(DnsMessage.TYPE_TXT).size(), "TXT alongside SRV");

        MdnsResponder.Reply txt = responder.handle(DnsMessage.query(instanceName, DnsMessage.TYPE_TXT));
        DnsMessage txtAnswer = DnsMessage.parse(txt.packet);
        eq(1, txtAnswer.records(DnsMessage.TYPE_TXT).size(), "TXT answered");
        eq(0, txtAnswer.records(DnsMessage.TYPE_SRV).size(), "no SRV for a TXT query");

        MdnsResponder.Reply any = responder.handle(DnsMessage.query(instanceName, DnsMessage.TYPE_ANY));
        DnsMessage anyAnswer = DnsMessage.parse(any.packet);
        eq(1, anyAnswer.records(DnsMessage.TYPE_SRV).size(), "ANY covers SRV");
        eq(1, anyAnswer.records(DnsMessage.TYPE_TXT).size(), "ANY covers TXT");
        eq(1, anyAnswer.records(DnsMessage.TYPE_AAAA).size(), "ANY covers the address");
    }

    private static void hostQueryAnswered() throws Exception {
        MdnsResponder responder = newResponder(listener());

        MdnsResponder.Reply reply = responder.handle(DnsMessage.query(HOST, DnsMessage.TYPE_AAAA));
        check(reply != null, "AAAA query answered");
        DnsMessage answer = DnsMessage.parse(reply.packet);
        eq(1, answer.records(DnsMessage.TYPE_AAAA).size(), "one address");
        eq(0, answer.records(DnsMessage.TYPE_SRV).size(), "no SRV for a host query");
        eq(0, answer.records(DnsMessage.TYPE_PTR).size(), "no PTR for a host query");

        check(responder.handle(DnsMessage.query(HOST, DnsMessage.TYPE_SRV)) == null,
            "host name has no SRV to give");
    }

    private static void unrelatedAndMalformedTrafficIgnored() {
        Recording listener = new Recording();
        MdnsResponder responder = newResponder(listener);

        check(responder.handle(DnsMessage.query("_ipp._tcp.local", DnsMessage.TYPE_PTR)) == null,
            "other services ignored");
        check(responder.handle(DnsMessage.query(AirDropPeer.SERVICE, DnsMessage.TYPE_SRV)) == null,
            "PTR-only name is not an SRV owner");
        eq(0, listener.errors, "no errors for valid silence");

        check(responder.handle(new byte[] {1, 2, 3}) == null, "short packet dropped");
        eq(1, listener.errors, "malformed packet reported");
        eq(0, responder.peers().size(), "malformed packet harvests nothing");
    }

    private static void unicastQueryEchoesId() throws Exception {
        MdnsResponder responder = newResponder(listener());
        byte[] query = new DnsMessage.Builder(0x1234, 0)
            .question(AirDropPeer.SERVICE, DnsMessage.TYPE_PTR, DnsMessage.CLASS_IN | DnsMessage.CLASS_UNICAST)
            .build();

        MdnsResponder.Reply reply = responder.handle(query);
        check(reply != null, "QU query answered");
        check(reply.unicast, "QU honoured");
        eq(0x1234, DnsMessage.parse(reply.packet).id(), "query id echoed");
    }

    // --- peer bookkeeping -------------------------------------------------

    private static void peersAreHarvestedOnce() {
        Recording listener = new Recording();
        MdnsResponder responder = newResponder(listener);

        byte[] phone = AirDropPeer.announce("iPhone", "iPhone.local", 8770,
            Map.of("h", "aa11", "fl", "9e1b"), List.of("fe80::1"));
        check(responder.handle(phone) == null, "announcement needs no answer");
        eq(1, responder.peers().size(), "peer learned");
        eq(1, listener.peers.size(), "listener told once");
        eq("iPhone", listener.peers.get(0).displayName, "peer name");
        eq(0x9e1bL, listener.peers.get(0).flags, "peer flags");

        responder.handle(phone);
        eq(1, listener.peers.size(), "identical announcement is not news");

        byte[] moved = AirDropPeer.announce("iPhone", "iPhone.local", 9999,
            Map.of("h", "aa11", "fl", "9e1b"), List.of("fe80::1"));
        responder.handle(moved);
        eq(2, listener.peers.size(), "changed port is news");
        eq(1, responder.peers().size(), "one entry per service instance");
        eq(9999, responder.peers().get(0).port, "entry updated in place");
    }

    private static void ownAnnouncementIsIgnored() {
        Recording listener = new Recording();
        MdnsResponder responder = newResponder(listener);

        responder.handle(responder.announcement());
        eq(0, responder.peers().size(), "we do not discover ourselves");
        eq(0, listener.peers.size(), "no self callback");
    }

    private static void peersExpire() {
        MdnsResponder responder = newResponder(listener());
        responder.handle(AirDropPeer.announce("iPhone", "iPhone.local", 8770,
            Map.of("h", "aa11"), List.of("fe80::1")));
        eq(1, responder.peers().size(), "peer before expiry");

        responder.prune(System.currentTimeMillis() + 60_000);
        eq(1, responder.peers().size(), "peer inside the TTL");

        responder.prune(System.currentTimeMillis() + 5_000_000);
        eq(0, responder.peers().size(), "peer past the PTR TTL");
    }

    // --- live socket ------------------------------------------------------

    /** Binds a real socket on loopback and asks it a question the way a peer would. */
    private static void answersOverLoopbackSocket() throws Exception {
        MdnsResponder responder = newResponder(listener());
        responder.setIntervals(0, 0);
        responder.start(null, 0);
        try {
            check(responder.isRunning(), "responder running");
            check(responder.port() > 0, "ephemeral port assigned");

            byte[] query = new DnsMessage.Builder(0, 0)
                .question(AirDropPeer.SERVICE, DnsMessage.TYPE_PTR, DnsMessage.CLASS_IN | DnsMessage.CLASS_UNICAST)
                .build();
            try (DatagramSocket client = new DatagramSocket()) {
                client.setSoTimeout(5_000);
                client.send(new DatagramPacket(query, query.length,
                    InetAddress.getByName("127.0.0.1"), responder.port()));

                DatagramPacket incoming = new DatagramPacket(new byte[4000], 4000);
                client.receive(incoming);
                byte[] raw = Arrays.copyOf(incoming.getData(), incoming.getLength());
                DnsMessage answer = DnsMessage.parse(raw);
                check(answer.isResponse(), "socket answer is a response");
                eq(1, answer.records(DnsMessage.TYPE_PTR).size(), "socket answer has the PTR");
                eq(8770, answer.records(DnsMessage.TYPE_SRV).get(0).port(), "socket answer has the port");
            }
        } finally {
            responder.close();
        }
        check(!responder.isRunning(), "responder closed");
    }

    // --- scaffolding ------------------------------------------------------

    private static MdnsResponder newResponder(MdnsResponder.Listener listener) {
        MdnsResponder.Source source = new MdnsResponder.Source() {
            @Override
            public Map<String, String> txt() {
                Map<String, String> txt = new LinkedHashMap<>();
                txt.put("h", HASH);
                txt.put("fl", "9e1b");
                return txt;
            }

            @Override
            public List<String> addresses() {
                return List.of(ADDRESS);
            }
        };
        return new MdnsResponder(INSTANCE, HOST, 8770, source, listener);
    }

    private static Recording listener() {
        return new Recording();
    }

    private static final class Recording implements MdnsResponder.Listener {
        final List<AirDropPeer> peers = new ArrayList<>();
        int errors;

        @Override
        public void onPeer(AirDropPeer peer) {
            peers.add(peer);
        }

        @Override
        public void onError(String message, Throwable error) {
            errors++;
        }
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

    private MdnsResponderTest() {
    }
}
