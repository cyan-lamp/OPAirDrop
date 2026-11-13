package com.oshare.airdrop.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Answers the questions the other probes cannot: what does the peer <em>actually</em>
 * advertise, and what happens if we simply talk to it?
 *
 * <p>The existing probes assume the answer is well formed and report only what our own
 * parser made of it. This one prints every record in the response verbatim — TTLs, TXT
 * bytes, SRV target, addresses — because the open questions here are matters of fact
 * about Apple's records, not about our code: which TXT keys exist, what {@code fl} means,
 * whether a cert hash is published at all.
 *
 * <p>Then it opens a TLS connection with <em>no pin</em> and says what came back: protocol,
 * cipher, ALPN, and the fingerprint of whatever certificate was served. Pinning is the
 * right behaviour for shipping code, where a wrong peer must be refused; it is the wrong
 * behaviour for a diagnostic, where the point is to learn what the peer is. Finally it
 * sends {@code /Discover} — the one request that is read-only and shows no UI on the other
 * side — to see whether the peer will answer us at all.
 *
 * <p>It deliberately never sends {@code /Ask}: on a real Apple device that puts a dialog in
 * front of a person, which is not something a probe should do unannounced.
 *
 * <p>Usage: {@code app_process /system/bin com.oshare.airdrop.core.PeerProbe [iface] [seconds]}
 */
public final class PeerProbe {
    private static final String TAG = "peer-probe";

    public static void main(String[] args) {
        String iface = args.length > 0 && !args[0].isEmpty() ? args[0] : AirDropLink.DEFAULT_INTERFACE;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        say("watching " + iface + " for " + seconds + "s, then greeting whoever answers");

        try {
            String self = linkLocalOf(iface);
            Map<String, AirDropPeer> peers = browse(iface, seconds);

            say("");
            say("=== peers (" + peers.size() + ") ===");
            for (AirDropPeer peer : peers.values()) {
                say(describe(peer) + (peer.addresses.contains(self) ? "  [this device]" : ""));
            }

            for (AirDropPeer peer : peers.values()) {
                if (peer.addresses.contains(self)) continue;
                say("");
                say("=== greeting \"" + peer.displayName + "\" ===");
                greet(peer, iface);
            }
        } catch (Exception failed) {
            say("failed: " + failed);
            failed.printStackTrace(System.out);
        }
    }

    // ---------------------------------------------------------------- browsing

    /** Sends a PTR query every 4 s and dumps every response, whole. */
    private static Map<String, AirDropPeer> browse(String iface, int seconds) throws Exception {
        Map<String, AirDropPeer> peers = new LinkedHashMap<>();
        int id = (int) (System.currentTimeMillis() & 0x7fff);
        byte[] question = new DnsMessage.Builder(id, 0)
            .question(AirDropPeer.SERVICE, DnsMessage.TYPE_PTR,
                DnsMessage.CLASS_IN | DnsMessage.CLASS_UNICAST)
            .build();

        try (java.net.MulticastSocket socket = new java.net.MulticastSocket(new InetSocketAddress(0))) {
            socket.setSoTimeout(1000);
            InetAddress group = InetAddress.getByName(MdnsResponder.GROUP + "%" + iface);
            say("asking " + MdnsResponder.GROUP + "%" + iface + ":" + MdnsResponder.PORT
                + " — multicast, because a unicast datagram may land on another socket on 5353");

            long deadline = System.currentTimeMillis() + seconds * 1000L;
            long nextQuery = System.currentTimeMillis();
            byte[] buffer = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextQuery) {
                    socket.send(new DatagramPacket(question, question.length,
                        new InetSocketAddress(group, MdnsResponder.PORT)));
                    nextQuery = System.currentTimeMillis() + 4000L;
                }
                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(incoming);
                } catch (java.net.SocketTimeoutException quiet) {
                    continue;
                }
                DnsMessage message = DnsMessage.parse(
                    java.util.Arrays.copyOf(incoming.getData(), incoming.getLength()));
                if (!message.isResponse()) continue;
                dump(message, incoming);
                for (AirDropPeer peer : AirDropPeer.from(message)) peers.put(peer.serviceName, peer);
            }
        }
        return peers;
    }

    /** Every record as it arrived, then our own reading of it — the difference matters. */
    private static void dump(DnsMessage message, DatagramPacket packet) throws Exception {
        say("");
        say("answer " + packet.getLength() + " B from "
            + packet.getAddress().getHostAddress() + (packet.getPort() == 5353 ? "" : ":" + packet.getPort())
            + " (id " + message.id() + ")");
        for (DnsMessage.Record record : message.records()) {
            StringBuilder line = new StringBuilder("  ");
            line.append(typeName(record.type)).append(' ').append(record.name)
                .append("  ttl=").append(record.ttl);
            switch (record.type) {
                case DnsMessage.TYPE_SRV:
                    line.append("  port=").append(record.port()).append(" target=").append(record.target());
                    break;
                case DnsMessage.TYPE_TXT:
                    line.append("  ").append(record.txt().values());
                    line.append("  raw=").append(hex(record.rdata()));
                    break;
                case DnsMessage.TYPE_AAAA:
                    line.append("  ").append(record.addressV6());
                    break;
                default:
                    line.append("  raw=").append(hex(record.rdata()));
                    break;
            }
            say(line.toString());
        }
        List<AirDropPeer> parsed = AirDropPeer.from(message);
        for (AirDropPeer peer : parsed) say("  -> " + describe(peer));
        if (parsed.isEmpty()) say("  -> nothing we can use (no SRV for an _airdrop instance)");
    }

    private static String describe(AirDropPeer peer) {
        return peer.displayName + " host=" + peer.host + ":" + peer.port
            + " h=" + (peer.certHash == null || peer.certHash.isEmpty() ? "(none)" : peer.certHash)
            + " fl=0x" + Long.toHexString(peer.flags)
            + " rp=" + (peer.receiverProtocol == null ? "" : peer.receiverProtocol)
            + " addresses=" + peer.addresses;
    }

    // ---------------------------------------------------------------- greeting

    /**
     * TLS with no pin, on purpose, then {@code /Discover}.
     *
     * <p>Nothing here checks identity: this is how we find out what the peer's identity is.
     * The fingerprint that comes back is what a real client would have to match against the
     * hash the peer publishes in its TXT record, so printing both is the whole comparison.
     */
    private static void greet(AirDropPeer peer, String iface) {
        String address = null;
        for (String candidate : peer.addresses) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith("fe80")) address = candidate;
        }
        if (address == null && !peer.addresses.isEmpty()) address = peer.addresses.get(0);
        if (address == null) {
            say("no address to greet — the response carried no AAAA for " + peer.host);
            return;
        }

        try {
            InetAddress remote = InetAddress.getByName(address + "%" + iface);
            say("connecting to " + address + "%" + iface + ":" + peer.port + " with no pin");
            SSLContext loose = SSLContext.getInstance("TLS");
            loose.init(null, new TrustManager[] { new AcceptAnything() }, new SecureRandom());

            try (SSLSocket socket = (SSLSocket) loose.getSocketFactory().createSocket(remote, peer.port)) {
                socket.setSoTimeout(15_000);
                socket.startHandshake();
                say("handshake ok protocol=" + socket.getSession().getProtocol()
                    + " cipher=" + socket.getSession().getCipherSuite()
                    + " alpn=" + quoted(socket.getApplicationProtocol()));
                X509Certificate served = (X509Certificate) socket.getSession().getPeerCertificates()[0];
                String fingerprint = Identity.fingerprint(served.getEncoded());
                say("served cert subject=" + served.getSubjectX500Principal().getName()
                    + " sha256=" + fingerprint);
                if (peer.certHash != null && !peer.certHash.isEmpty()) {
                    say("advertised h=" + peer.certHash + " -> "
                        + (peer.certHash.equalsIgnoreCase(fingerprint) ? "MATCH" : "DIFFERS"));
                } else {
                    say("advertised no h= at all, so a pinning client has nothing to check against");
                }

                discover(socket, peer.host);
            }
        } catch (Exception failed) {
            say("greeting failed: " + failed);
        }
    }

    /** The one request that shows no UI on the far side, so it is safe to send unannounced. */
    private static void discover(SSLSocket socket, String host) throws Exception {
        byte[] body = Bplist.encode(Bplist.dictionary());
        String head = "POST " + AirDropProtocol.PATH_DISCOVER + " HTTP/1.1\r\n"
            + "Host: " + (host == null ? "airdrop.local" : host) + "\r\n"
            + "Content-Type: " + AirDropProtocol.OCTET_STREAM + "\r\n"
            + "User-Agent: " + AirDropProtocol.USER_AGENT + "\r\n"
            + "Connection: close\r\n"
            + "Content-Length: " + body.length + "\r\n\r\n";

        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();

        InputStream in = socket.getInputStream();
        String status = HttpRequest.readLine(in);
        if (status == null) {
            say("discover: the peer closed without answering");
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String header = HttpRequest.readLine(in);
            if (header == null || header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon > 0) {
                headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    header.substring(colon + 1).trim());
            }
        }
        int code = Integer.parseInt(status.split(" ")[1]);
        byte[] payload = readBody(in, headers);
        say("discover: " + status + " headers=" + headers.keySet());
        if (payload.length > 0) {
            Map<String, Object> caps = Bplist.parseDictionary(payload);
            say("discover: keys=" + caps.keySet()
                + " computer=\"" + Bplist.string(caps, "ReceiverComputerName") + "\""
                + " model=" + Bplist.string(caps, "ReceiverModelName"));
        } else {
            say("discover: no body");
        }
        say("discover: verdict " + (code == 200 ? "peer answered us" : "peer refused (" + code + ")"));
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String encoding = headers.getOrDefault("transfer-encoding", "");
        if (encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            while (true) {
                String size = HttpRequest.readLine(in);
                if (size == null) break;
                int semi = size.indexOf(';');
                int length = Integer.parseInt((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
                if (length == 0) {
                    HttpRequest.readLine(in);
                    break;
                }
                byte[] chunk = new byte[length];
                int cursor = 0;
                while (cursor < length) {
                    int read = in.read(chunk, cursor, length - cursor);
                    if (read < 0) break;
                    cursor += read;
                }
                body.write(chunk, 0, cursor);
                HttpRequest.readLine(in);
            }
            return body.toByteArray();
        }

        String length = headers.get("content-length");
        if (length == null) return new byte[0];
        byte[] body = new byte[Integer.parseInt(length.trim())];
        int cursor = 0;
        while (cursor < body.length) {
            int read = in.read(body, cursor, body.length - cursor);
            if (read < 0) break;
            cursor += read;
        }
        return body;
    }

    // ---------------------------------------------------------------- helpers

    private static String linkLocalOf(String iface) throws IOException {
        NetworkInterface network = NetworkInterface.getByName(iface);
        if (network == null) throw new IOException("interface " + iface + " is missing");
        for (InetAddress address : Collections.list(network.getInetAddresses())) {
            if (address instanceof java.net.Inet6Address && address.isLinkLocalAddress()) {
                String text = address.getHostAddress();
                int scope = text.indexOf('%');
                return scope < 0 ? text : text.substring(0, scope);
            }
        }
        throw new IOException("no link-local address on " + iface);
    }

    private static String typeName(int type) {
        switch (type) {
            case DnsMessage.TYPE_A: return "A   ";
            case DnsMessage.TYPE_PTR: return "PTR ";
            case DnsMessage.TYPE_TXT: return "TXT ";
            case DnsMessage.TYPE_AAAA: return "AAAA";
            case DnsMessage.TYPE_SRV: return "SRV ";
            case DnsMessage.TYPE_NSEC: return "NSEC";
            default: return String.format(Locale.ROOT, "%-4d", type);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte value : data) text.append(String.format(Locale.ROOT, "%02x", value));
        return text.toString();
    }

    private static String quoted(String value) {
        return value == null || value.isEmpty() ? "(none)" : value;
    }

    private static void say(String message) {
        System.out.println(TAG + ": " + message);
    }

    /** For a diagnostic only: the pinning path lives in {@link Identity#clientContext}. */
    private static final class AcceptAnything implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private PeerProbe() {
    }
}
