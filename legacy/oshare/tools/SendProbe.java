package com.oshare.airdrop.core;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The send-side counterpart of {@link LinkClientProbe}, and the on-device twin of
 * {@code SendTest}.
 *
 * <p>{@code LinkClientProbe} speaks the protocol by hand; this one does not. It builds a
 * real {@link Identity}, a real {@link Outgoing} list and a real {@link AirDropClient},
 * then points them at whatever answers the mDNS query on the link. So the code under test
 * here is the same code the app will run when the user taps Share — only the peer is
 * different. The peer that answers will normally be the app's own receiver on this same
 * device, which makes the whole exchange a loopback: outbound HTTP, TLS pinning, cpio
 * streaming and the receiver's unpacking all get exercised, and the probe then reads the
 * inbox back and compares digests.
 *
 * <p>What that still does not prove: that an Apple peer accepts the 802.11 frames
 * underneath, and that the TXT record keys and flags are the ones iOS expects. Those need
 * an iPhone in the room.
 *
 * <p>Usage: {@code app_process /system/bin com.oshare.airdrop.core.SendProbe [iface] [inbox]}
 */
public final class SendProbe {
    private static final String TAG = "send-probe";

    private static final String DEFAULT_INBOX =
        "/storage/emulated/0/Android/data/com.oshare.airdrop/files/incoming";

    /** 1 MiB + 37: past every internal buffer and not a round number, so truncation shows. */
    private static final int BULK = (1 << 20) + 37;

    public static void main(String[] args) {
        String iface = args.length > 0 && !args[0].isEmpty() ? args[0] : AirDropLink.DEFAULT_INTERFACE;
        File inbox = new File(args.length > 1 && !args[1].isEmpty() ? args[1] : DEFAULT_INBOX);
        say("sending through " + iface + " to whoever answers; reading back from " + inbox);

        try {
            AirDropPeer peer = discover(iface);
            say("peer \"" + peer.displayName + "\" host=" + peer.host + " port=" + peer.port);
            say("advertised h=" + shortHash(peer.certHash) + " fl=" + Long.toHexString(peer.flags)
                + " addresses=" + peer.addresses);

            // A fresh identity per run: the probe owns no keystore, and a new hash makes it
            // visible on the receiving side that these really are separate senders.
            Identity identity = Identity.generate("send-probe");
            say("sender name=" + identity.computerName() + " h=" + shortHash(identity.certHash()));

            List<Outgoing> files = payloads();
            say("archive " + AirDropClient.archiveLength(files) + " B over " + files.size()
                + " members:");
            for (Outgoing file : files) say("  " + file);

            AirDropClient client = new AirDropClient(identity, iface, "SM8750");
            AirDropClient.Result result = client.send(peer, files, new Trace());
            say("done " + result.files + " files / " + result.bytes + " B in " + result.millis
                + " ms, receiver=\"" + result.receiverName + "\" model=" + result.receiverModel);

            verdict(compare(inbox, files));
        } catch (Exception failed) {
            say("failed: " + failed);
            failed.printStackTrace(System.out);
        }
    }

    /**
     * Three members, each carrying a different risk.
     *
     * <p>The text file is the ordinary case. The bulk file is larger than every buffer in
     * the path, so it only survives if streaming works. The empty file is the smallest
     * legal member and the one a hand-written writer most often gets wrong: cpio locates
     * the next header from the declared size, so a zero-length member must still emit its
     * padding correctly.
     */
    private static List<Outgoing> payloads() {
        long stamp = System.currentTimeMillis();
        List<Outgoing> files = new ArrayList<>();

        String text = "sent by " + TAG + " at " + stamp + "\n";
        files.add(Outgoing.of(TAG + "-" + stamp + ".txt", "text/plain",
            text.getBytes(StandardCharsets.UTF_8)));

        byte[] bulk = new byte[BULK];
        new Random(stamp).nextBytes(bulk);
        files.add(Outgoing.of(TAG + "-" + stamp + ".bin", "application/octet-stream",
            bulk.length, () -> new ByteArrayInputStream(bulk)));

        files.add(Outgoing.of(TAG + "-" + stamp + "-empty.txt", "text/plain", new byte[0]));
        return files;
    }

    /**
     * Reads every member back out of the inbox and compares length and digest with what
     * {@link Outgoing#open()} produces now.
     *
     * <p>A missing file is reported, not thrown: the whole point of a probe is to print
     * what happened, and the difference between "nothing arrived" and "wrong bytes
     * arrived" is the useful part.
     */
    private static List<String> compare(File inbox, List<Outgoing> files) throws Exception {
        List<String> failures = new ArrayList<>();
        for (Outgoing file : files) {
            File landed = new File(inbox, file.name);
            if (!landed.isFile()) {
                failures.add(file.name + ": missing");
                say("  " + file.name + ": missing");
                continue;
            }
            String expected = digest(file.open());
            String actual = digest(new FileInputStream(landed));
            boolean same = landed.length() == file.length && expected.equals(actual);
            say("  " + file.name + ": " + landed.length() + " B sha=" + shortHash(actual)
                + (same ? " ok" : " MISMATCH expected " + file.length + " B sha=" + shortHash(expected)));
            if (!same) failures.add(file.name + ": differs");
        }

        StringBuilder cleanup = new StringBuilder("to clean up: rm -f");
        for (Outgoing file : files) cleanup.append(" '").append(new File(inbox, file.name))
            .append("'");
        say(cleanup.toString());
        return failures;
    }

    /** Prints the one line worth grepping for, and makes the exit code match it. */
    private static void verdict(List<String> failures) {
        if (failures.isEmpty()) {
            say("VERDICT ok — every member landed byte for byte");
            return;
        }
        say("VERDICT mismatch — " + failures);
        System.exit(1);
    }

    private static String digest(InputStream in) throws Exception {
        try (InputStream source = in) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = source.read(buffer)) > 0) sha.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte value : sha.digest()) hex.append(String.format(Locale.ROOT, "%02X", value));
            return hex.toString();
        }
    }

    /**
     * One QU question for {@code _airdrop._tcp.local}, multicast first.
     *
     * <p>Copied from {@code LinkClientProbe} on purpose rather than shared: these probes
     * are meant to be read as standalone evidence, and the multicast-not-unicast reason —
     * 5353 here is shared with the system {@code mdnsd} under {@code SO_REUSEADDR}, so a
     * unicast datagram is not obliged to reach our socket, while a group datagram is
     * delivered to everyone who joined — is the kind of thing that gets lost in a helper.
     */
    private static AirDropPeer discover(String iface) throws Exception {
        NetworkInterface network = NetworkInterface.getByName(iface);
        if (network == null) throw new IOException("interface " + iface + " is missing");

        InetAddress local = null;
        for (InetAddress address : Collections.list(network.getInetAddresses())) {
            if (address instanceof java.net.Inet6Address && address.isLinkLocalAddress()) local = address;
        }
        if (local == null) throw new IOException("no link-local address on " + iface);

        int id = (int) (System.currentTimeMillis() & 0x7fff);
        byte[] question = new DnsMessage.Builder(id, 0)
            .question(AirDropPeer.SERVICE, DnsMessage.TYPE_PTR, DnsMessage.CLASS_IN | DnsMessage.CLASS_UNICAST)
            .build();

        try (java.net.MulticastSocket socket = new java.net.MulticastSocket(new InetSocketAddress(0))) {
            socket.setSoTimeout(3000);
            InetAddress group = InetAddress.getByName(MdnsResponder.GROUP + "%" + iface);
            say("asking " + MdnsResponder.GROUP + "%" + iface + ":" + MdnsResponder.PORT);
            socket.send(new DatagramPacket(question, question.length,
                new InetSocketAddress(group, MdnsResponder.PORT)));

            AirDropPeer answer = await(socket, id);
            if (answer == null) {
                say("nothing on the group, asking " + local.getHostAddress() + " directly");
                socket.send(new DatagramPacket(question, question.length,
                    new InetSocketAddress(local, MdnsResponder.PORT)));
                answer = await(socket, id);
            }
            if (answer == null) throw new IllegalStateException("no answer — is the app running?");
            return answer;
        }
    }

    private static AirDropPeer await(DatagramSocket socket, int id) throws Exception {
        byte[] buffer = new byte[2048];
        while (true) {
            DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(incoming);
            } catch (java.net.SocketTimeoutException quiet) {
                return null;
            }
            DnsMessage message = DnsMessage.parse(
                java.util.Arrays.copyOf(incoming.getData(), incoming.getLength()));
            if (!message.isResponse()) continue;
            if (message.id() != id) throw new IllegalStateException("id " + message.id() + " != " + id);
            List<AirDropPeer> peers = AirDropPeer.from(message);
            if (peers.isEmpty()) continue;
            say("answer " + incoming.getLength() + " B from " + incoming.getAddress().getHostAddress()
                + ": ptr=" + message.records(DnsMessage.TYPE_PTR).size()
                + " srv=" + message.records(DnsMessage.TYPE_SRV).size()
                + " txt=" + message.records(DnsMessage.TYPE_TXT).size()
                + " aaaa=" + message.records(DnsMessage.TYPE_AAAA).size());
            return peers.get(0);
        }
    }

    /** Stage changes plus four progress marks — enough to see progress without a wall of text. */
    private static final class Trace implements AirDropClient.Progress {
        private int mark = -1;

        @Override
        public void onStage(AirDropClient.Stage stage) {
            say("stage " + stage);
        }

        @Override
        public void onProgress(long sent, long total) {
            if (total <= 0) return;
            int reached = sent >= total ? 4 : (int) (sent * 4 / total);
            if (reached <= mark) return;
            mark = reached;
            say("  " + reached * 25 + "% " + sent + "/" + total + " B");
        }
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 16 ? String.valueOf(hash) : hash.substring(0, 16) + "…";
    }

    private static void say(String message) {
        System.out.println(TAG + ": " + message);
    }

    private SendProbe() {
    }
}
