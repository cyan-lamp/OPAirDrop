package com.oshare.airdrop.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLSocket;

/**
 * Device-side probe for M2: runs the whole receiver — identity on disk, mDNS
 * advertisement, TLS endpoint — and then transfers a file to itself over loopback.
 *
 * <p>Why loopback: the parts that differ between the JVM and a phone are the TLS
 * provider (Conscrypt vs SunJSSE), the file system, and the multicast socket bound to
 * {@code wonder0}. A loopback session exercises all three except the radio, so a
 * failure here is a real failure, and a pass means only the air interface is unproven.
 *
 * <p>Usage: {@code app_process /system/bin com.oshare.airdrop.core.StackProbe [iface] [seconds]}
 */
public final class StackProbe {
    private static final String TAG = "stack-probe";

    public static void main(String[] args) {
        String iface = args.length > 0 && !args[0].isEmpty() ? args[0] : AirDropLink.DEFAULT_INTERFACE;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 30;

        File root = new File("/data/local/tmp/stack-probe");
        File identityDir = new File(root, "identity");
        File inboxDir = new File(root, "inbox");
        say("interface " + iface + " identity " + identityDir + " inbox " + inboxDir);

        final int[] errors = {0};
        final List<String> peers = new ArrayList<>();
        AirDropLink.Listener listener = new AirDropLink.Listener() {
            @Override
            public void onStarted(Identity identity, String host, int serverPort, int mdnsPort) {
                say("started host=" + host + " h=" + identity.certHash().substring(0, 16)
                    + "… mdns=:" + mdnsPort + " tls=:" + serverPort);
            }

            @Override
            public void onPeer(AirDropPeer peer) {
                peers.add(peer.toString());
                say("peer " + peer);
            }

            @Override
            public void onAsk(AirDropProtocol.Ask ask) {
                say("ask from " + ask.senderName + ": " + ask.summary());
            }

            @Override
            public void onReceived(List<File> files) {
                for (File file : files) say("received " + file.getName() + " " + file.length() + " B");
            }

            @Override
            public void onError(String message, Throwable error) {
                errors[0]++;
                say("error " + message + (error == null ? "" : ": " + error));
            }
        };

        AirDropLink link = new AirDropLink(identityDir, inboxDir, "OPAirDrop Probe", "SM8750",
            iface, listener);

        long started = System.currentTimeMillis();
        try {
            link.start();
        } catch (IOException failed) {
            say("start failed: " + failed.getMessage());
            return;
        }
        say("startup " + (System.currentTimeMillis() - started) + " ms");

        try {
            checkAdvertisement(link);
            checkLoopbackTransfer(link);
            if (seconds > 0) {
                say("listening for " + seconds + "s — watch for a real peer");
                Thread.sleep(seconds * 1000L);
                say("peers=" + link.peers().size() + " seen=" + peers.size());
            }
        } catch (Exception failed) {
            say("probe failed: " + failed);
            failed.printStackTrace(System.out);
        } finally {
            link.close();
            say("closed errors=" + errors[0]);
        }
    }

    /** The announcement a peer would parse: our TXT hash has to match the certificate. */
    private static void checkAdvertisement(AirDropLink link) throws Exception {
        byte[] packet = link.announcement();
        DnsMessage message = DnsMessage.parse(packet);
        List<DnsMessage.Record> txt = message.records(DnsMessage.TYPE_TXT);
        List<DnsMessage.Record> srv = message.records(DnsMessage.TYPE_SRV);
        List<DnsMessage.Record> aaaa = message.records(DnsMessage.TYPE_AAAA);
        say("announce " + packet.length + " B: ptr=" + message.records(DnsMessage.TYPE_PTR).size()
            + " srv=" + srv.size() + " txt=" + txt.size() + " aaaa=" + aaaa.size());
        if (txt.isEmpty()) throw new IllegalStateException("no TXT in the announcement");
        Map<String, String> values = txt.get(0).txt().values();
        say("txt " + values);
        String advertised = values.get("h");
        if (advertised == null || !advertised.equals(link.identity().certHash())) {
            throw new IllegalStateException("h=" + advertised + " != " + link.identity().certHash());
        }
        if (aaaa.isEmpty()) {
            say("warning: no AAAA — the link has no link-local address yet");
        } else {
            say("aaaa " + aaaa.get(0).addressV6());
        }
    }

    /** Discover → Ask → Upload against ourselves, over the real TLS provider. */
    private static void checkLoopbackTransfer(AirDropLink link) throws Exception {
        String file = "probe-" + System.currentTimeMillis() + ".txt";
        byte[] content = ("hello from " + TAG).getBytes(StandardCharsets.UTF_8);

        try (SSLSocket socket = (SSLSocket) Identity.clientContext(link.identity().certHash())
            .getSocketFactory().createSocket(InetAddress.getLoopbackAddress(), link.serverPort())) {
            socket.setSoTimeout(20_000);
            socket.startHandshake();
            String peerHash = Identity.fingerprint(
                ((java.security.cert.X509Certificate) socket.getSession().getPeerCertificates()[0])
                    .getEncoded());
            say("handshake ok peer=" + peerHash.substring(0, 16) + "… cipher=" + socket.getSession().getCipherSuite());
            if (!peerHash.equals(link.identity().certHash())) {
                throw new IllegalStateException("served certificate is not the advertised one");
            }

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            post(out, "/Discover", Bplist.encode(Bplist.dictionary()));
            Reply discover = read(in);
            say("discover " + discover.status + " body=" + new String(discover.body, StandardCharsets.UTF_8).length() + " B");
            if (discover.status != 200) throw new IllegalStateException("discover " + discover.status);

            post(out, "/Ask", askBody(file));
            Reply ask = read(in);
            say("ask " + ask.status);
            if (ask.status != 200) throw new IllegalStateException("ask " + ask.status);

            upload(in, out, file, content);
            Reply done = read(in);
            say("upload " + done.status);
            if (done.status != 200) throw new IllegalStateException("upload " + done.status);
        }

        File landed = new File(link.handler().inbox().directory(), file);
        String text = landed.isFile() ? new String(Files.readAllBytes(landed.toPath()), StandardCharsets.UTF_8) : "";
        say("landed " + landed + " " + landed.length() + " B -> " + text);
        if (!landed.isFile()) throw new IllegalStateException("upload did not reach the inbox");
        if (!text.equals(new String(content, StandardCharsets.UTF_8))) {
            throw new IllegalStateException("content mismatch: " + text);
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

        String head = "POST /Upload HTTP/1.1\r\n"
            + "Host: 127.0.0.1\r\n"
            + "Content-Type: application/x-cpio\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "Expect: 100-continue\r\n"
            + "User-Agent: " + AirDropProtocol.USER_AGENT + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        if (read(in).status != 100) throw new IOException("continue not offered");

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
            "SenderModelName", "MacBookPro18,3",
            "SenderID", "00:11:22:33:44:55",
            "BundleID", "com.apple.finder",
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

    private static void say(String message) {
        System.out.println(TAG + ": " + message);
    }

    private StackProbe() {
    }
}
