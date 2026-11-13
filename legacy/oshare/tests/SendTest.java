package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end send path over loopback TLS: {@link AirDropClient} talks to a real
 * {@link AirDropServer} running the real {@link ReceiveHandler}, so the whole chain
 * — pinned handshake, {@code /Discover}, {@code /Ask}, chunked {@code /Upload},
 * streaming cpio, extraction to disk — is exercised without a device.
 *
 * <p>This is the offline counterpart of {@code SendProbe}. Anything that passes here
 * can still fail against an Apple peer, because fidelity of the records is a separate
 * question; anything that fails here is wrong regardless of who is listening.
 */
public final class SendTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("oshare-send").toFile();
        try {
            sendsFilesAndTheyLandIntact(root);
            streamsLargeContent(root);
            reportsPhasesAndProgress(root);
            askCarriesTheSenderIdentity(root);
            memberLengthMatchesWhatTheWriterEmits();
            typesNormalizeToUtis();
            aWrongPinStopsTheHandshake(root);
        } finally {
            deleteTree(root);
        }
        System.out.println("SendTest OK (" + checks + " checks)");
    }

    /** The ordinary case: a few small files, arriving byte for byte. */
    private static void sendsFilesAndTheyLandIntact(File root) throws Exception {
        try (Receiver receiver = Receiver.start(new File(root, "plain"), "Pixel 9")) {
            AirDropClient client = new AirDropClient(
                Identity.generate("Laptop"), null, "SM8750");

            byte[] note = "hello from the sending side\n".getBytes(StandardCharsets.UTF_8);
            byte[] blob = new byte[5000];
            for (int i = 0; i < blob.length; i++) blob[i] = (byte) (i * 31 + 7);

            List<Outgoing> files = Arrays.asList(
                Outgoing.of("note.txt", "text/plain", note),
                // A zero-length member is legal cpio and a real edge case: sharing an
                // empty file must not produce an archive the receiver rejects.
                Outgoing.of("empty.jpg", "image/jpeg", new byte[0]),
                Outgoing.of("blob.bin", "application/octet-stream", blob));

            AirDropClient.Result result = client.send(receiver.peer, files, null);

            eq("Pixel 9", result.receiverName, "discover reports the receiver name");
            eq("SM8750", result.receiverModel, "discover reports the receiver model");
            eq(3, result.files, "result counts the files");
            eq(AirDropClient.archiveLength(files), result.bytes, "result counts archive bytes");

            Map<String, byte[]> landed = receiver.inboxContents();
            eq(3, landed.size(), "three members extracted");
            check(Arrays.equals(note, landed.get("note.txt")), "text member intact");
            check(Arrays.equals(new byte[0], landed.get("empty.jpg")), "empty member intact");
            check(Arrays.equals(blob, landed.get("blob.bin")), "binary member intact");
        }
    }

    /**
     * Content larger than one chunk and larger than the progress step, driven through
     * a provider so nothing is guaranteed to be in memory. This is the path a video
     * takes; if it works, the streaming plumbing is wired correctly.
     */
    private static void streamsLargeContent(File root) throws Exception {
        File source = new File(root, "big.bin");
        int size = 3 * 1024 * 1024 + 17;
        byte[] pattern = new byte[size];
        for (int i = 0; i < size; i++) pattern[i] = (byte) (i % 251);

        try (FileOutputStream out = new FileOutputStream(source)) {
            out.write(pattern);
        }

        try (Receiver receiver = Receiver.start(new File(root, "large"), "Mac mini")) {
            AirDropClient client = new AirDropClient(
                Identity.generate("Laptop"), null, "SM8750");

            Outgoing big = Outgoing.of(source, "application/octet-stream");
            eq((long) size, big.length, "file length read from disk");

            AirDropClient.Result result = client.send(receiver.peer,
                java.util.Collections.singletonList(big), null);
            eq(1, result.files, "one file sent");
            eq(AirDropClient.archiveLength(java.util.Collections.singletonList(big)),
                result.bytes, "archive size matches the prediction");

            byte[] landed = receiver.inboxContents().get("big.bin");
            check(landed != null, "large member extracted");
            eq(size, landed.length, "large member length");
            check(Arrays.equals(pattern, landed), "large member intact");
        }
    }

    /** Phase transitions arrive in order and progress is reported while uploading. */
    private static void reportsPhasesAndProgress(File root) throws Exception {
        byte[] payload = new byte[1024 * 1024];
        try (Receiver receiver = Receiver.start(new File(root, "phases"), "Pixel 9")) {
            AirDropClient client = new AirDropClient(
                Identity.generate("Laptop"), null, "SM8750");

            List<AirDropClient.Stage> stages = new ArrayList<>();
            List<Long> sent = new ArrayList<>();
            AirDropClient.Result result = client.send(receiver.peer,
                java.util.Collections.singletonList(
                    Outgoing.of("payload.bin", "application/octet-stream", payload)),
                new AirDropClient.Progress() {
                    @Override
                    public void onStage(AirDropClient.Stage stage) {
                        stages.add(stage);
                    }

                    @Override
                    public void onProgress(long moved, long total) {
                        sent.add(moved);
                        if (moved > total) throw new AssertionError("progress past the total");
                    }
                });

            eq(Arrays.asList(AirDropClient.Stage.DISCOVERING, AirDropClient.Stage.ASKING,
                AirDropClient.Stage.UPLOADING, AirDropClient.Stage.DONE), stages, "stage order");
            check(!sent.isEmpty(), "progress was reported at least once");
            check(sent.get(sent.size() - 1) > 0, "progress moved forward");
            eq(AirDropClient.archiveLength(java.util.Collections.singletonList(
                Outgoing.of("payload.bin", "application/octet-stream", payload))),
                result.bytes, "final byte count equals the archive");
        }
    }

    /** The offer names us the way the receiving side will show it. */
    private static void askCarriesTheSenderIdentity(File root) throws Exception {
        try (Receiver receiver = Receiver.start(new File(root, "ask"), "Pixel 9")) {
            AirDropClient client = new AirDropClient(
                Identity.generate("Laptop"), null, "SM8750");

            client.send(receiver.peer, Arrays.asList(
                Outgoing.of("photo.jpg", "image/jpeg", new byte[3]),
                Outgoing.of("moved.pdf", "application/pdf", new byte[3]).at("docs/moved.pdf")),
                null);

            AirDropProtocol.Ask ask = receiver.handler.lastAsk();
            check(ask != null, "handler saw the offer");
            eq("Laptop", ask.senderName, "sender name");
            eq("SM8750", ask.senderModel, "sender model");
            eq("com.apple.finder", ask.bundleId, "bundle id");
            eq(2, ask.files.size(), "both files offered");
            eq("photo.jpg", ask.files.get(0).name, "first display name");
            eq("public.jpeg", ask.files.get(0).type, "mime type raised to a UTI");
            eq("./photo.jpg", ask.files.get(0).bomPath, "bom path is rooted at the transfer");
            eq("./docs/moved.pdf", ask.files.get(1).bomPath, "bom path override survives");
            // ASK-5 semantics: the member name the receiver resolves is the BOM path with
            // the transfer root stripped, so the two spellings must converge there.
            eq("docs/moved.pdf", ask.files.get(1).memberName(), "override strips to a usable name");
            Map<String, byte[]> landed = receiver.inboxContents();
            check(landed.containsKey("docs/moved.pdf"),
                "archive member arrived under the offered path, got " + landed.keySet());
            check(landed.containsKey("photo.jpg"),
                "plain member arrived under its bare name, got " + landed.keySet());
        }
    }

    /** The size reported to the progress bar has to equal what actually goes out. */
    private static void memberLengthMatchesWhatTheWriterEmits() throws Exception {
        String[] names = {"a", "ab", "abc", "abcd", "note.txt", "docs/moved.pdf",
            "IMG_20260101_120000.jpg", "文件.txt"};
        for (String name : names) {
            for (int size = 0; size < 9; size++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Cpio.writeMember(out, name, Cpio.MODE_REGULAR, new byte[size], 1);
                eq(Cpio.memberLength(name, size), (long) out.size(),
                    "member length for " + name + "/" + size);

                byte[] streamed = new byte[size];
                ByteArrayOutputStream streamedOut = new ByteArrayOutputStream();
                Cpio.writeMember(streamedOut, name, Cpio.MODE_REGULAR, size,
                    new java.io.ByteArrayInputStream(streamed), 1);
                eq((long) out.size(), (long) streamedOut.size(),
                    "streamed member writes the same bytes for " + name + "/" + size);
            }
        }
        eq(Cpio.memberLength("TRAILER!!!", 0), Cpio.trailerLength(), "trailer length");
    }

    /** MIME types become UTIs where the mapping is known, and pass through where not. */
    private static void typesNormalizeToUtis() {
        eq("public.jpeg", Outgoing.normalizeType("image/jpeg", "x"), "jpeg");
        eq("public.png", Outgoing.normalizeType("IMAGE/PNG", "x"), "png, case-insensitive");
        eq("public.mpeg-4", Outgoing.normalizeType("video/mp4", "x"), "mp4");
        eq("public.jpeg", Outgoing.normalizeType(null, "photo.JPG"), "guessed from an upper-case suffix");
        eq("com.example.custom", Outgoing.normalizeType("com.example.custom", "x"), "unknown types pass through");
        eq(AirDropProtocol.OCTET_STREAM, Outgoing.normalizeType(null, "no-extension"), "no suffix");
    }

    /** Pinning is the whole security model; a mismatched fingerprint must not connect. */
    private static void aWrongPinStopsTheHandshake(File root) throws Exception {
        try (Receiver receiver = Receiver.start(new File(root, "pin"), "Pixel 9")) {
            AirDropClient client = new AirDropClient(
                Identity.generate("Laptop"), null, "SM8750");

            AirDropPeer impostor = new AirDropPeer(receiver.peer.serviceName,
                receiver.peer.displayName, receiver.peer.host, receiver.peer.port,
                "0000000000000000000000000000000000000000000000000000000000000000",
                0L, null, null, receiver.peer.addresses);

            boolean refused = false;
            try {
                client.send(impostor,
                    java.util.Collections.singletonList(
                        Outgoing.of("x.txt", "text/plain", new byte[1])), null);
            } catch (IOException expected) {
                refused = true;
            }
            check(refused, "a fingerprint mismatch aborts the transfer");
            eq(0, receiver.inboxContents().size(), "nothing landed");
        }
    }

    /** A running receiver: identity, inbox, handler, server, and the peer record for it. */
    private static final class Receiver implements AutoCloseable {
        final Identity identity;
        final Inbox inbox;
        final ReceiveHandler handler;
        final AirDropServer server;
        final AirDropPeer peer;

        private Receiver(Identity identity, Inbox inbox, ReceiveHandler handler,
                         AirDropServer server, AirDropPeer peer) {
            this.identity = identity;
            this.inbox = inbox;
            this.handler = handler;
            this.server = server;
            this.peer = peer;
        }

        static Receiver start(File directory, String name) throws Exception {
            Identity identity = Identity.generate(name);
            Inbox inbox = new Inbox(directory);
            ReceiveHandler handler = new ReceiveHandler(name, "SM8750", inbox);
            AirDropServer server = new AirDropServer(identity, handler);
            server.start(0);
            // Loopback is IPv4 here: the server binds the wildcard address and
            // InetAddress.getByName("::1") is not guaranteed to reach it.
            AirDropPeer peer = new AirDropPeer(name + "._airdrop._tcp.local", name,
                "pixel.local", server.port(), identity.certHash(), 0L,
                "1", null, java.util.Collections.singletonList("127.0.0.1"));
            return new Receiver(identity, inbox, handler, server, peer);
        }

        /** Member name to bytes, read back from the inbox directory, subfolders included. */
        Map<String, byte[]> inboxContents() throws IOException {
            Map<String, byte[]> out = new LinkedHashMap<>();
            walk(inbox.directory(), "", out);
            return out;
        }

        /**
         * Recursive, with '/' in the keys, because an offer may name
         * {@code docs/moved.pdf}: a flat listing would report that member as missing and
         * hide the very thing the test is checking.
         */
        private void walk(File directory, String prefix, Map<String, byte[]> out)
            throws IOException {
            File[] children = directory.listFiles();
            if (children == null) return;
            java.util.Arrays.sort(children, java.util.Comparator.comparing(File::getName));
            for (File child : children) {
                if (child.isDirectory()) {
                    walk(child, prefix + child.getName() + "/", out);
                } else {
                    out.put(prefix + child.getName(), Files.readAllBytes(child.toPath()));
                }
            }
        }

        @Override
        public void close() {
            server.close();
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

    private SendTest() {
    }
}
