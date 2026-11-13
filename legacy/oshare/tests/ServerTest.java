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
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLSocket;

/**
 * End-to-end receive path over loopback TLS: the same {@code /Discover → /Ask →
 * /Upload} sequence Apple's client runs, driven against {@link AirDropServer}.
 *
 * <p>This is the offline stand-in for the device test: it covers the TLS handshake
 * against our self-signed certificate, HTTP keep-alive across the three requests,
 * de-chunking, gzip, and the cpio-to-disk step.
 */
public final class ServerTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("oshare-server").toFile();
        Identity identity = Identity.generate("Pixel 9");
        Inbox inbox = new Inbox(new File(root, "downloads"));

        RecordingHandler handler = new RecordingHandler(inbox);
        ErrorLog errors = new ErrorLog();
        try (AirDropServer server = new AirDropServer(identity, handler, errors)) {
            server.start(0);
            check(server.port() > 0, "bound a port");

            fullSession(identity, server, handler, inbox);
            wrongContentTypeRejected(identity, server);
            contentLengthArchiveAccepted(identity, server, inbox);
            malformedAskRejected(identity, server);
            unknownPathRejected(identity, server);
            getRejected(identity, server);
            eq(0, errors.unexpected(), "no server-side failures");
        } finally {
            deleteTree(root);
        }
        System.out.println("ServerTest OK (" + checks + " checks)");
    }

    /** One connection carrying all three requests, exactly like the real client. */
    private static void fullSession(Identity identity, AirDropServer server,
                                    RecordingHandler handler, Inbox inbox) throws Exception {
        try (SSLSocket socket = connect(identity, server.port())) {
            // The peer is pinned to the fingerprint we advertise, and TLS must agree.
            X509Certificate peer = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            eq(identity.certHash(), Identity.fingerprint(peer.getEncoded()), "peer certificate fingerprint");

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            post(out, "/Discover", plistHeaders(), Bplist.encode(Bplist.dictionary()));
            Reply discover = read(in);
            eq(200, discover.status, "discover status");
            eq("keep-alive", discover.header("connection"), "discover keeps the connection");
            Map<String, Object> discoverBody = Bplist.parseDictionary(discover.body);
            eq("Pixel 9", Bplist.string(discoverBody, "ReceiverComputerName"), "discover name");
            eq("SM8750", Bplist.string(discoverBody, "ReceiverModelName"), "discover model");
            eq("{\"Version\": 1}",
                new String(Bplist.bytes(discoverBody, "ReceiverMediaCapabilities"), StandardCharsets.UTF_8),
                "media capabilities");
            eq(1, handler.discoveries.get(), "handler saw discover");

            post(out, "/Ask", plistHeaders(), askBody());
            Reply ask = read(in);
            eq(200, ask.status, "ask status");
            Map<String, Object> askBody = Bplist.parseDictionary(ask.body);
            eq("Pixel 9", Bplist.string(askBody, "ReceiverComputerName"), "ask answer name");
            eq(1, handler.asks.get(), "handler saw ask");
            eq("Makoto's 13", handler.lastAsk.senderName, "parsed sender name");
            eq("holiday.jpg", handler.lastAsk.summary(), "parsed offer");

            upload(in, out, "holiday.jpg", "jpeg payload".getBytes(StandardCharsets.UTF_8));
            Reply reply = read(in);
            eq(200, reply.status, "upload status");
            eq("0", reply.header("content-length"), "upload has an empty body");
            eq("close", reply.header("connection"), "upload closes the connection");
            eq(1, handler.uploads.get(), "handler saw upload");

            check(new File(inbox.directory(), "holiday.jpg").isFile(), "file landed");
            eq("jpeg payload",
                new String(Files.readAllBytes(new File(inbox.directory(), "holiday.jpg").toPath()),
                    StandardCharsets.UTF_8),
                "file content");
        }
    }

    /**
     * UP-4: {@code /Upload} answers 406 when the body is not an archive at all, so the
     * sender learns immediately instead of streaming megabytes into a refusal.
     *
     * <p>{@code plistHeaders()} is the wrong lever here — it sends octet-stream, which
     * UP-1b leaves as a legal "I am not telling you what this is" archive type. A plist
     * content type is unambiguously not an archive.
     */
    private static void wrongContentTypeRejected(Identity identity, AirDropServer server) throws Exception {
        try (SSLSocket socket = connect(identity, server.port())) {
            OutputStream out = socket.getOutputStream();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/x-plist");
            post(out, "/Upload", headers, new byte[0]);
            Reply reply = read(socket.getInputStream());
            eq(406, reply.status, "non-archive content type required");
        }
    }

    /**
     * UP-1a: a {@code Content-Length} body is as acceptable as a chunked one — the spec
     * requires both framing styles to be accepted, so neither may be turned into an
     * error. The bytes here are what our own sender puts on the wire: an odc archive
     * inside DVZip, declared with a length instead of chunked.
     */
    private static void contentLengthArchiveAccepted(Identity identity, AirDropServer server,
                                                     Inbox inbox) throws Exception {
        byte[] content = "odc inside dvzip".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        DvZip.Encoder dvzip = new DvZip.Encoder(archive);
        Cpio.writeOdcMember(dvzip, ".", Cpio.MODE_DIRECTORY, new byte[0], 0);
        Cpio.writeOdcMember(dvzip, "./framing.txt", Cpio.MODE_REGULAR, content, 1);
        Cpio.writeOdcTrailer(dvzip);
        dvzip.finish();

        try (SSLSocket socket = connect(identity, server.port())) {
            OutputStream out = socket.getOutputStream();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", AirDropProtocol.UPLOAD_CONTENT_TYPE);
            out.write(("POST /Upload HTTP/1.1\r\n"
                + "Host: fe80::1%wonder0\r\n"
                + "Content-Type: " + AirDropProtocol.UPLOAD_CONTENT_TYPE + "\r\n"
                + "Content-Length: " + archive.size() + "\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(archive.toByteArray());
            out.flush();
            eq(200, read(socket.getInputStream()).status, "content-length archive accepted");
        }

        File landed = new File(inbox.directory(), "framing.txt");
        check(landed.isFile(), "content-length archive landed");
        eq("odc inside dvzip", new String(Files.readAllBytes(landed.toPath()),
            StandardCharsets.UTF_8), "content-length archive content");
    }

    private static void malformedAskRejected(Identity identity, AirDropServer server) throws Exception {
        try (SSLSocket socket = connect(identity, server.port())) {
            OutputStream out = socket.getOutputStream();
            post(out, "/Ask", plistHeaders(), "not a plist".getBytes(StandardCharsets.US_ASCII));
            Reply reply = read(socket.getInputStream());
            eq(400, reply.status, "undecodable ask rejected");
        }
    }

    private static void unknownPathRejected(Identity identity, AirDropServer server) throws Exception {
        try (SSLSocket socket = connect(identity, server.port())) {
            OutputStream out = socket.getOutputStream();
            post(out, "/Nope", plistHeaders(), new byte[0]);
            eq(404, read(socket.getInputStream()).status, "unknown path");
        }
    }

    private static void getRejected(Identity identity, AirDropServer server) throws Exception {
        try (SSLSocket socket = connect(identity, server.port())) {
            OutputStream out = socket.getOutputStream();
            out.write("GET /Discover HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            eq(405, read(socket.getInputStream()).status, "get not allowed");
        }
    }

    // ------------------------------------------------------------- client side

    private static SSLSocket connect(Identity identity, int port) throws Exception {
        SSLSocket socket = (SSLSocket) Identity.clientContext(identity.certHash())
            .getSocketFactory().createSocket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(15_000);
        socket.startHandshake();
        return socket;
    }

    private static Map<String, String> plistHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", AirDropProtocol.OCTET_STREAM);
        headers.put("User-Agent", AirDropProtocol.USER_AGENT);
        headers.put("Connection", "keep-alive");
        return headers;
    }

    private static void post(OutputStream out, String path, Map<String, String> headers, byte[] body)
        throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("POST ").append(path).append(" HTTP/1.1\r\n");
        head.append("Host: fe80::1%wonder0\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /** Chunked upload preceded by {@code Expect: 100-continue}, gzip-compressed cpio. */
    private static void upload(InputStream in, OutputStream out, String name, byte[] content)
        throws IOException {
        ByteArrayOutputStream archiveBytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(archiveBytes)) {
            gzip.write(Cpio.write(Arrays.asList(new Cpio.Entry("./" + name, 0100644, content))));
        }
        byte[] archive = archiveBytes.toByteArray();

        String head = "POST /Upload HTTP/1.1\r\n"
            + "Host: fe80::1%wonder0\r\n"
            + "Content-Type: application/x-cpio\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "Expect: 100-continue\r\n"
            + "User-Agent: AirDrop/1.0\r\n"
            + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        Reply ready = read(in);
        eq(100, ready.status, "server invites the body");

        // Two chunks, so the de-chunker crosses a boundary on the way in.
        int split = archive.length / 2;
        writeChunk(out, archive, 0, split);
        writeChunk(out, archive, split, archive.length - split);
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void writeChunk(OutputStream out, byte[] data, int offset, int length)
        throws IOException {
        out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data, offset, length);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] askBody() {
        List<Object> files = new ArrayList<>();
        files.add(Bplist.dictionary(
            "FileName", "holiday.jpg",
            "FileType", "public.jpeg",
            "FileBomPath", "./holiday.jpg",
            "FileIsDirectory", Boolean.FALSE,
            "ConvertMediaFormats", 0L));
        Map<String, Object> body = Bplist.dictionary(
            "SenderComputerName", "Makoto's 13",
            "SenderModelName", "MacBookPro18,3",
            "SenderID", "AA:BB:CC:DD:EE:FF",
            "BundleID", "com.apple.finder",
            "ConvertMediaFormats", Boolean.FALSE);
        body.put("Files", files);
        return Bplist.encode(body);
    }

    /** Minimal response reader: status line, headers, Content-Length body. */
    private static Reply read(InputStream in) throws IOException {
        String status = HttpRequest.readLine(in);
        if (status == null) throw new IOException("connection closed before a response");
        String[] parts = status.split(" ");
        Reply reply = new Reply();
        reply.status = Integer.parseInt(parts[1]);
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
        String length = reply.header("content-length");
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

        String header(String name) {
            return headers.get(name);
        }
    }

    private static final class RecordingHandler implements AirDropServer.Handler {
        final AtomicInteger discoveries = new AtomicInteger();
        final AtomicInteger asks = new AtomicInteger();
        final AtomicInteger uploads = new AtomicInteger();
        volatile AirDropProtocol.Ask lastAsk;
        private final Inbox inbox;

        RecordingHandler(Inbox inbox) {
            this.inbox = inbox;
        }

        @Override
        public byte[] onDiscover(byte[] request) {
            discoveries.incrementAndGet();
            return AirDropProtocol.discoverAnswer("Pixel 9", "SM8750");
        }

        @Override
        public byte[] onAsk(AirDropProtocol.Ask ask) {
            asks.incrementAndGet();
            lastAsk = ask;
            return AirDropProtocol.askAnswer("Pixel 9", "SM8750");
        }

        @Override
        public void onUpload(InputStream body, String contentType) throws IOException {
            uploads.incrementAndGet();
            inbox.extract(body);
        }
    }

    private static final class ErrorLog implements AirDropServer.Listener {
        private final List<String> messages = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onError(String message, Throwable error) {
            messages.add(message + (error == null ? "" : " | " + error));
        }

        /** Peers hanging up mid-flight is normal; anything else is a defect. */
        int unexpected() {
            int count = 0;
            for (String message : new ArrayList<>(messages)) {
                String lower = message.toLowerCase(Locale.ROOT);
                boolean benign = lower.contains("connection reset") || lower.contains("socket closed")
                    || lower.contains("broken pipe") || lower.contains("connection ended")
                    || lower.contains("closed by peer") || lower.contains("ssl");
                if (!benign) {
                    System.out.println("  server error: " + message);
                    count++;
                }
            }
            return count;
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError("failed: " + what);
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private ServerTest() {
    }
}
