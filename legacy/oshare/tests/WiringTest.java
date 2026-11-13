package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * M2 wiring: the identity on disk, the handler that answers Apple's client, and the
 * endpoint that ties mDNS and TLS together.
 *
 * <p>The link is started on loopback ({@code null} interface, port 0) so the whole
 * receive path — handshake against the stored certificate, keep-alive, de-chunking,
 * gzip, cpio into the inbox — runs exactly as it will on the device, only without
 * the radio.
 */
public final class WiringTest {
    private static final String NAME = "Pixel 9";
    private static final String MODEL = "SM8750";
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("oshare-wiring").toFile();
        try {
            identityPersists(root);
            brokenIdentityReplaced(new File(root, "broken"));
            hostLabels();
            handlerAnswers(new File(root, "handler"));
            uploadsAreDeltas(new File(root, "deltas"));
            linkServesOverLoopback(new File(root, "link"));
            missingInterfaceRejected(new File(root, "missing"));
        } finally {
            deleteTree(root);
        }
        System.out.println("WiringTest OK (" + checks + " checks)");
    }

    // ------------------------------------------------------------- identity

    private static void identityPersists(File root) throws Exception {
        File dir = new File(root, "identity");
        IdentityStore store = new IdentityStore(dir);
        check(!store.exists(), "nothing stored yet");

        Identity first = store.loadOrCreate(NAME);
        check(store.exists(), "identity written");
        check(new File(dir, "identity.key").isFile(), "key file present");
        check(new File(dir, "identity.der").isFile(), "certificate file present");
        eq(64, first.certHash().length(), "hex sha-256");

        Identity again = new IdentityStore(dir).loadOrCreate(NAME);
        eq(first.certHash(), again.certHash(), "same fingerprint after reload");
        eq(first.x509().getSubjectX500Principal(), again.x509().getSubjectX500Principal(),
            "same subject after reload");
        // The reloaded pair must still be able to serve TLS, which is the only thing
        // that proves the private key survived PKCS#8 round-tripping.
        check(again.serverContext() != null, "reloaded identity builds a context");

        store.clear();
        check(!store.exists(), "identity cleared");
        Identity third = store.loadOrCreate(NAME);
        check(!first.certHash().equals(third.certHash()), "cleared identity is a new device");
    }

    private static void brokenIdentityReplaced(File dir) throws Exception {
        IdentityStore store = new IdentityStore(dir);
        Identity original = store.loadOrCreate(NAME);

        Files.write(new File(dir, "identity.key").toPath(),
            "not a key".getBytes(StandardCharsets.US_ASCII));
        eq(null, store.load(NAME), "unreadable key is not loaded");

        Identity recovered = store.loadOrCreate(NAME);
        check(recovered.certHash() != null, "a usable identity after corruption");
        check(!original.certHash().equals(recovered.certHash()), "corruption forces a new pair");
        check(new File(dir, "identity.key.tmp").isFile() == false, "no temporary left behind");
    }

    // ---------------------------------------------------------------- naming

    private static void hostLabels() {
        eq("pixel-9.local", AirDropLink.hostNameFor("Pixel 9"), "spaces become hyphens");
        eq("ma-koto-s-13.local", AirDropLink.hostNameFor("Ma.koto's 13"), "dots and quotes dropped");
        eq("opairdrop.local", AirDropLink.hostNameFor("   "), "blank name falls back");
        eq("opairdrop.local", AirDropLink.hostNameFor(""), "empty name falls back");
        eq("我的手机.local", AirDropLink.hostNameFor("我的手机"), "utf-8 labels survive");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) longName.append('a');
        String host = AirDropLink.hostNameFor(longName.toString());
        check(host.endsWith(".local"), "long name still gets a suffix");
        check(host.getBytes(StandardCharsets.UTF_8).length <= 69, "label fits in 63 bytes");
        check(AirDropLink.hostNameFor("--x--").equals("x.local"), "hyphens are trimmed");
    }

    // --------------------------------------------------------------- handler

    private static void handlerAnswers(File dir) throws Exception {
        Inbox inbox = new Inbox(new File(dir, "inbox"));
        ReceiveHandler handler = new ReceiveHandler(NAME, MODEL, inbox);
        Recording listener = new Recording();
        handler.setListener(listener);

        byte[] discover = handler.onDiscover(Bplist.encode(Bplist.dictionary()));
        Map<String, Object> body = Bplist.parseDictionary(discover);
        eq(NAME, Bplist.string(body, "ReceiverComputerName"), "discover name");
        eq(MODEL, Bplist.string(body, "ReceiverModelName"), "discover model");
        eq(AirDropProtocol.MEDIA_CAPABILITIES,
            new String(Bplist.bytes(body, "ReceiverMediaCapabilities"), StandardCharsets.UTF_8),
            "media capabilities");

        AirDropProtocol.Ask ask = AirDropProtocol.ask(askBody());
        byte[] answer = handler.onAsk(ask);
        eq(NAME, Bplist.string(Bplist.parseDictionary(answer), "ReceiverComputerName"), "ask name");
        eq(ask, handler.lastAsk(), "offer remembered");
        eq(1, listener.asks.size(), "listener told about the offer");
        eq("Makoto's 13", listener.asks.get(0).senderName, "sender name parsed");
        eq("holiday.jpg", listener.asks.get(0).summary(), "offered item parsed");
    }

    private static void uploadsAreDeltas(File dir) throws Exception {
        File downloads = new File(dir, "inbox");
        ReceiveHandler handler = new ReceiveHandler(NAME, MODEL, new Inbox(downloads));
        Recording listener = new Recording();
        handler.setListener(listener);

        handler.onUpload(new java.io.ByteArrayInputStream(
            archive("dup.txt", "first".getBytes(StandardCharsets.UTF_8))), "application/x-cpio");
        handler.onUpload(new java.io.ByteArrayInputStream(
            archive("dup.txt", "second".getBytes(StandardCharsets.UTF_8))), "application/x-cpio");

        eq(2, listener.received.size(), "one callback per upload");
        eq(1, listener.received.get(0).size(), "first upload wrote one file");
        eq("dup.txt", listener.received.get(0).get(0).getName(), "first name");
        eq(1, listener.received.get(1).size(), "second upload wrote one file");
        eq("dup (1).txt", listener.received.get(1).get(0).getName(), "collision renamed");
        eq("second", new String(Files.readAllBytes(listener.received.get(1).get(0).toPath()),
            StandardCharsets.UTF_8), "second content");
        eq("first", new String(Files.readAllBytes(new File(downloads, "dup.txt").toPath()),
            StandardCharsets.UTF_8), "first content untouched");
    }

    // ------------------------------------------------------------------ link

    private static void linkServesOverLoopback(File dir) throws Exception {
        File identityDir = new File(dir, "identity");
        File inboxDir = new File(dir, "inbox");
        Recording listener = new Recording();
        AirDropLink link = new AirDropLink(identityDir, inboxDir, NAME, MODEL, null, listener);
        try (AirDropLink running = link) {
            running.start(0, 0);
            check(running.isRunning(), "link is up");
            check(running.serverPort() > 0, "server bound");
            check(running.mdnsPort() > 0, "responder bound");
            eq("pixel-9.local", running.host(), "advertised host");
            eq(1, listener.started.get(), "started callback once");
            eq(0, running.peers().size(), "nobody else on loopback");

            Identity identity = running.identity();
            check(identity != null, "identity exposed");
            eq(running.serverPort(), listener.serverPort, "callback carries the bound port");

            // The TXT record is what a peer pins us by: it has to be the certificate
            // we are actually serving, not a cached copy.
            byte[] announcement = running.announcement();
            Map<String, String> txt = txtOf(announcement);
            eq(identity.certHash(), txt.get("h"), "h= is the serving certificate");
            eq(AirDropLink.DEFAULT_FLAGS, txt.get("fl"), "capability bits advertised");

            session(identity, running.serverPort());
            eq(1, listener.received.size(), "received callback once");
            check(new File(inboxDir, "holiday.jpg").isFile(), "file written through the link");
            eq(0, listener.peers.size(), "no peer callbacks on an empty link");
        }
        check(!link.isRunning(), "closed");
    }

    private static void missingInterfaceRejected(File dir) throws Exception {
        AirDropLink link = new AirDropLink(new File(dir, "identity"), new File(dir, "inbox"),
            NAME, MODEL, "wonder-not-here");
        try {
            link.start(0, 0);
            throw new AssertionError("started without a link");
        } catch (IOException expected) {
            check(expected.getMessage().contains("wonder-not-here"), "names the missing interface");
            check(expected.getMessage().contains("awdl-link.sh"), "says how to fix it");
        }
        check(!link.isRunning(), "half-started link reports stopped");
        link.close();
    }

    // ---------------------------------------------------------- client side

    private static void session(Identity identity, int port) throws Exception {
        try (SSLSocket socket = connect(identity, port)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            post(out, "/Discover", Bplist.encode(Bplist.dictionary()));
            Reply discover = read(in);
            eq(200, discover.status, "discover status");
            eq(NAME, Bplist.string(Bplist.parseDictionary(discover.body), "ReceiverComputerName"),
                "discover answer over the wire");

            post(out, "/Ask", askBody());
            Reply ask = read(in);
            eq(200, ask.status, "ask status");

            upload(in, out, "holiday.jpg", "jpeg payload".getBytes(StandardCharsets.UTF_8));
            Reply done = read(in);
            eq(200, done.status, "upload status");
            eq("close", done.header("connection"), "upload closes the connection");
        }
    }

    private static SSLSocket connect(Identity identity, int port) throws Exception {
        SSLSocket socket = (SSLSocket) Identity.clientContext(identity.certHash())
            .getSocketFactory().createSocket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(15_000);
        socket.startHandshake();
        return socket;
    }

    private static void post(OutputStream out, String path, byte[] body) throws IOException {
        String head = "POST " + path + " HTTP/1.1\r\n"
            + "Host: pixel-9.local\r\n"
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
        byte[] archive = archive(name, content);
        String head = "POST /Upload HTTP/1.1\r\n"
            + "Host: pixel-9.local\r\n"
            + "Content-Type: application/x-cpio\r\n"
            + "Transfer-Encoding: chunked\r\n"
            + "Expect: 100-continue\r\n"
            + "User-Agent: " + AirDropProtocol.USER_AGENT + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        eq(100, read(in).status, "server invites the body");
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

    private static byte[] archive(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(Cpio.write(Arrays.asList(new Cpio.Entry("./" + name, 0100644, content))));
        }
        return bytes.toByteArray();
    }

    private static byte[] askBody() {
        Map<String, Object> item = Bplist.dictionary(
            "FileName", "holiday.jpg",
            "FileType", "public.jpeg",
            "FileBomPath", "./holiday.jpg",
            "FileIsDirectory", Boolean.FALSE,
            "ConvertMediaFormats", 0L);
        Map<String, Object> body = Bplist.dictionary(
            "SenderComputerName", "Makoto's 13",
            "SenderModelName", "MacBookPro18,3",
            "SenderID", "AA:BB:CC:DD:EE:FF",
            "BundleID", "com.apple.finder",
            "ConvertMediaFormats", Boolean.FALSE);
        body.put("Files", new ArrayList<>(List.of(item)));
        return Bplist.encode(body);
    }

    /** TXT pairs of the first TXT record, as a peer would read them. */
    private static Map<String, String> txtOf(byte[] announcement) throws DnsMessage.FormatException {
        List<DnsMessage.Record> records = DnsMessage.parse(announcement).records(DnsMessage.TYPE_TXT);
        check(!records.isEmpty(), "announcement carries TXT");
        return records.get(0).txt().values();
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

    private static final class Recording implements AirDropLink.Listener, ReceiveHandler.Listener {
        final AtomicInteger started = new AtomicInteger();
        final List<AirDropPeer> peers = new ArrayList<>();
        final List<AirDropProtocol.Ask> asks = new ArrayList<>();
        final List<List<File>> received = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        volatile int serverPort;

        @Override
        public void onStarted(Identity identity, String host, int serverPort, int mdnsPort) {
            this.serverPort = serverPort;
            started.incrementAndGet();
        }

        @Override
        public void onPeer(AirDropPeer peer) {
            peers.add(peer);
        }

        @Override
        public void onAsk(AirDropProtocol.Ask ask) {
            asks.add(ask);
        }

        @Override
        public void onReceived(List<File> files) {
            received.add(files);
        }

        @Override
        public void onError(String message, Throwable error) {
            errors.add(message + (error == null ? "" : " | " + error));
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
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

    private WiringTest() {
    }
}
