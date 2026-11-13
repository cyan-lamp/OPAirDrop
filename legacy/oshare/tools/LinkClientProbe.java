package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLSocket;

/**
 * Device-side counterpart to {@link StackProbe}: this one starts <em>nothing</em>.
 *
 * <p>It plays the iPhone. It asks the mDNS responder that the installed app left
 * running, pins the TLS connection to the hash that answer advertised, and walks the
 * three requests a sender makes. So every answer below comes from
 * {@code com.oshare.airdrop}'s own process, not from this probe — which is the only way
 * to check the app's wiring (service, directories, device name) without an Apple device
 * in the room. What it still cannot tell us is whether iOS likes the 802.11 frames
 * underneath.
 *
 * <p>Usage: {@code app_process /system/bin com.oshare.airdrop.core.LinkClientProbe [iface]}
 */
public final class LinkClientProbe {
    private static final String TAG = "client-probe";

    public static void main(String[] args) {
        String iface = args.length > 0 && !args[0].isEmpty() ? args[0] : AirDropLink.DEFAULT_INTERFACE;
        say("asking the app on " + iface + " — this process opens no server");

        try {
            AirDropPeer peer = discover(iface);
            say("answered by \"" + peer.displayName + "\" host=" + peer.host + " port=" + peer.port);
            say("advertised h=" + shortHash(peer.certHash) + " fl=" + Long.toHexString(peer.flags)
                + (peer.receiverProtocol == null ? "" : " rp=" + peer.receiverProtocol));
            if (peer.addresses.isEmpty()) throw new IllegalStateException("no address in the answer");
            say("addresses " + peer.addresses);

            transfer(peer, iface);
            say("done — the app answered every step");
        } catch (Exception failed) {
            say("failed: " + failed);
            failed.printStackTrace(System.out);
        }
    }

    /**
     * One QU question for {@code _airdrop._tcp.local}, multicast on the link first and
     * unicast to the interface's own link-local address as a fallback.
     *
     * <p>Why multicast: it is what a real peer does, and on this device 5353 is shared
     * with the system {@code mdnsd} under {@code SO_REUSEADDR}. For a *unicast* datagram
     * that ambiguity means the kernel picks one of the sockets that match, and it is not
     * obliged to pick ours — the first version of this probe timed out for exactly that
     * reason. A datagram sent to {@code ff02::fb} is delivered to every socket that
     * joined the group, so the app cannot be skipped.
     *
     * <p>Why an ephemeral source port: a query that does not come from 5353 must be
     * answered directly (RFC 6762 §5.4), which keeps this probe from adding another
     * listener on the shared port.
     */
    private static AirDropPeer discover(String iface) throws Exception {
        NetworkInterface network = NetworkInterface.getByName(iface);
        if (network == null) throw new IOException("interface " + iface + " is missing");

        InetAddress local = null;
        // getInetAddresses() hands back an Enumeration, not an Iterable.
        for (InetAddress address : java.util.Collections.list(network.getInetAddresses())) {
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
            // Loopback stays enabled on purpose: the app is on this very host, so a
            // multicast datagram only reaches it if the kernel loops a copy back.
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

    /** Reads until a response with our id shows up, or the socket's timeout expires. */
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

    /** The three requests a sender makes, pinned to the hash the mDNS answer carried. */
    private static void transfer(AirDropPeer peer, String iface) throws Exception {
        String address = null;
        for (String candidate : peer.addresses) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith("fe80")) address = candidate;
        }
        if (address == null) address = peer.addresses.get(0);
        InetAddress remote = InetAddress.getByName(address + "%" + iface);

        // Pinning on the advertised hash is the real sender behaviour: if the served
        // certificate is not the one we were told about, iOS refuses too, and it does so
        // by dropping the connection rather than by telling us why.
        try (SSLSocket socket = (SSLSocket) Identity.clientContext(peer.certHash)
            .getSocketFactory().createSocket(remote, peer.port)) {
            socket.setSoTimeout(20_000);
            socket.startHandshake();
            String served = Identity.fingerprint(
                ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getEncoded());
            say("handshake ok served=" + shortHash(served) + " cipher="
                + socket.getSession().getCipherSuite() + " pin held");

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            post(out, AirDropProtocol.PATH_DISCOVER, Bplist.encode(Bplist.dictionary()));
            Reply discovered = read(in);
            Map<String, Object> caps = Bplist.parseDictionary(discovered.body);
            say("discover " + discovered.status + " keys=" + caps.keySet()
                + " name=" + Bplist.string(caps, "ReceiverComputerName")
                + " model=" + Bplist.string(caps, "ReceiverModelName"));
            if (discovered.status != 200) throw new IllegalStateException("discover " + discovered.status);

            String name = "appprobe-" + System.currentTimeMillis() + ".txt";
            post(out, AirDropProtocol.PATH_ASK, askBody(name));
            Reply asked = read(in);
            say("ask " + asked.status + " name=" + Bplist.string(asked.body == null ? Map.of()
                : Bplist.parseDictionary(asked.body), "ReceiverComputerName"));
            if (asked.status != 200) throw new IllegalStateException("ask " + asked.status);

            upload(in, out, name, ("hello from " + TAG).getBytes(StandardCharsets.UTF_8));
            Reply stored = read(in);
            say("upload " + stored.status + " -> the app's own inbox should hold " + name);
            if (stored.status != 200) throw new IllegalStateException("upload " + stored.status);
        }
    }

    private static void post(OutputStream out, String path, byte[] body) throws IOException {
        String head = "POST " + path + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\n"
            + "Content-Type: " + AirDropProtocol.OCTET_STREAM + "\r\n"
            + "User-Agent: " + AirDropProtocol.USER_AGENT + "\r\n"
            + "Connection: keep-alive\r\n"
            + "Content-Length: " + body.length + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void upload(InputStream in, OutputStream out, String name, byte[] content)
        throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(Cpio.write(List.of(new Cpio.Entry("./" + name, 0100644, content))));
        }
        byte[] archive = bytes.toByteArray();

        String head = "POST " + AirDropProtocol.PATH_UPLOAD + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\n"
            + "Content-Type: " + AirDropProtocol.UPLOAD_CONTENT_TYPE + "\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "Expect: 100-continue\r\n"
            + "User-Agent: " + AirDropProtocol.USER_AGENT + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        // The 100 has to be waited for: sending the body early is what makes some servers
        // (ours included) lose the first chunk run.
        if (read(in).status != 100) throw new IOException("the app did not offer 100-continue");

        int split = archive.length / 2;
        chunk(out, archive, 0, split);
        chunk(out, archive, split, archive.length - split);
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void chunk(OutputStream out, byte[] data, int offset, int length) throws IOException {
        out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data, offset, length);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] askBody(String name) {
        Map<String, Object> item = Bplist.dictionary(
            "FileName", name,
            "FileType", "public.plain-text",
            "FileBomPath", "./" + name,
            "FileIsDirectory", Boolean.FALSE,
            "ConvertMediaFormats", 0L);
        Map<String, Object> body = Bplist.dictionary(
            "SenderComputerName", "probe",
            "SenderModelName", "iPhone17,1",
            "SenderID", "00:11:22:33:44:55",
            "BundleID", "com.apple.springboard",
            "ConvertMediaFormats", Boolean.FALSE);
        body.put("Files", new ArrayList<>(List.of(item)));
        return Bplist.encode(body);
    }

    private static Reply read(InputStream in) throws IOException {
        String status = HttpRequest.readLine(in);
        if (status == null) throw new IOException("connection closed before a response");
        Reply reply = new Reply();
        reply.status = Integer.parseInt(status.split(" ")[1]);
        while (true) {
            String header = HttpRequest.readLine(in);
            if (header == null) throw new IOException("headers truncated");
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon > 0) {
                reply.headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    header.substring(colon + 1).trim());
            }
        }
        String length = reply.headers.get("content-length");
        int size = length == null ? 0 : Integer.parseInt(length);
        reply.body = new byte[size];
        int cursor = 0;
        while (cursor < size) {
            int read = in.read(reply.body, cursor, size - cursor);
            if (read < 0) throw new IOException("body truncated");
            cursor += read;
        }
        return reply;
    }

    private static final class Reply {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 16 ? String.valueOf(hash) : hash.substring(0, 16) + "…";
    }

    private static void say(String message) {
        System.out.println(TAG + ": " + message);
    }

    private LinkClientProbe() {
    }
}
