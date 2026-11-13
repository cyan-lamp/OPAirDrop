package com.oshare.airdrop.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** DVZip block framing and the odc archive the sender writes; plain JVM, no device. */
public final class DvZipTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        smallPayloadIsStoredNotDeflated();
        compressiblePayloadIsDeflated();
        incompressiblePayloadIsStored();
        blockBoundariesDecodeExactly();
        nothingWrittenProducesNoBlocks();
        emptyFirstBlockIsNotADvZipBody();
        archiveRoundTripsThroughDvZip();
        streamingOdcLengthMatchesTheWriter();
        bareArchivesAreNotMistakenForDvZip();
        truncatedBlockRejected();
        implausibleLengthRejected();
        System.out.println("DvZipTest OK (" + checks + " checks)");
    }

    /**
     * The case a text file hits: 18 bytes cannot be made smaller, so the block goes
     * out stored and costs exactly four bytes. This is the shape that was measured
     * working against a real receiver.
     */
    private static void smallPayloadIsStoredNotDeflated() throws Exception {
        byte[] payload = "oshare awdl probe\n".getBytes(StandardCharsets.US_ASCII);
        byte[] framed = encode(payload);

        eq(4 + payload.length, framed.length, "one stored block is header plus payload");
        check((framed[0] & 0x80) != 0, "high bit marks the block stored");
        eq(payload.length, beLength(framed, 0), "declared length is the raw size");
        check(java.util.Arrays.equals(payload, decode(framed)), "stored bytes survive");
    }

    /** 64 KiB of one byte repeats perfectly, so the block must be deflated and shrink. */
    private static void compressiblePayloadIsDeflated() throws Exception {
        byte[] payload = new byte[DvZip.BLOCK];
        java.util.Arrays.fill(payload, (byte) 'A');
        byte[] framed = encode(payload);

        check((framed[0] & 0x80) == 0, "high bit clear means deflated");
        int declared = beLength(framed, 0);
        check(declared < payload.length, "deflate shrank the block: " + declared);
        eq(framed.length, 4 + declared, "block length matches its header");
        check(java.util.Arrays.equals(payload, decode(framed)), "deflated block inflates");
    }

    /** Random bytes grow under deflate, which is exactly when storing wins. */
    private static void incompressiblePayloadIsStored() throws Exception {
        byte[] payload = new byte[DvZip.BLOCK];
        new Random(20261110L).nextBytes(payload);
        byte[] framed = encode(payload);

        check((framed[0] & 0x80) != 0, "random block is stored");
        eq(4 + payload.length, framed.length, "stored block is exactly its size");
        check(java.util.Arrays.equals(payload, decode(framed)), "random bytes survive");
    }

    /** DVZ-2 puts one independent block per 64 KiB; the tail is a short final block. */
    private static void blockBoundariesDecodeExactly() throws Exception {
        byte[] payload = new byte[200_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) ((i * 31) % 251);
        byte[] framed = encode(payload);

        int blocks = 0;
        int at = 4;
        while (at <= framed.length) {
            int declared = beLength(framed, at - 4);
            at += declared;
            blocks++;
            if (at == framed.length) break;
            at += 4;
        }
        eq(4, blocks, "200000 bytes is three full blocks and a tail");
        check(java.util.Arrays.equals(payload, decode(framed)), "every block reassembles");
    }

    /** A body with nothing to say writes no block at all, not an empty one. */
    private static void nothingWrittenProducesNoBlocks() throws Exception {
        byte[] none = encode(new byte[0]);
        eq(0, none.length, "no input means no blocks");
        eq(-1, DvZip.decode(new ByteArrayInputStream(none)).read(), "empty body reads as empty");
    }

    /**
     * A zero-length first block is legal in the format but must not be treated as a
     * DVZip signature, or a truncated upload would decode to an empty file.
     */
    private static void emptyFirstBlockIsNotADvZipBody() {
        check(!DvZip.looksLikeBlock(new byte[] {0, 0, 0, 0}), "zero length is not a block");
        check(!DvZip.looksLikeBlock(new byte[3]), "three bytes cannot be a header");
        check(!DvZip.looksLikeBlock(null), "null is not a block");
        check(DvZip.looksLikeBlock(new byte[] {(byte) 0x80, 0, 0, 18}), "stored 18 is a block");
        check(DvZip.looksLikeBlock(new byte[] {0, 0, 1, 0}), "deflated 256 is a block");
    }

    /** The whole send path in one assertion: odc archive, DVZip wrapped, parsed back. */
    private static void archiveRoundTripsThroughDvZip() throws Exception {
        List<Cpio.Entry> source = new ArrayList<>();
        source.add(new Cpio.Entry(".", 0040755, new byte[0]));
        source.add(new Cpio.Entry("./note.txt", 0100644,
            "hello from the sender".getBytes(StandardCharsets.UTF_8)));
        source.add(new Cpio.Entry("./大文件.bin", 0100644, new byte[3000]));
        byte[] archive = Cpio.writeOdc(source);

        byte[] body = encode(archive);
        List<Cpio.Entry> parsed = Cpio.read(body);
        eq(2, parsed.size(), "dvzip archived member count");
        eq("note.txt", parsed.get(0).name, "dvzip archived name");
        eq("hello from the sender", new String(parsed.get(0).data, StandardCharsets.UTF_8),
            "dvzip archived payload");
        eq(3000, parsed.get(1).data.length, "dvzip archived binary length");
    }

    /** {@code odcMemberLength} must be the writer's own arithmetic, not a second opinion. */
    private static void streamingOdcLengthMatchesTheWriter() throws Exception {
        String[] names = {".", "./a", "./longer-name.txt", "./佐藤の写真.jpg"};
        byte[][] data = {new byte[0], new byte[] {1}, new byte[64], new byte[1000]};
        int[] modes = {0040755, 0100644, 0100644, 0100644};

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long predicted = Cpio.odcTrailerLength();
        for (int i = 0; i < names.length; i++) {
            Cpio.writeOdcMember(out, names[i], modes[i], data[i].length,
                new ByteArrayInputStream(data[i]), i + 1);
            predicted += Cpio.odcMemberLength(names[i], data[i].length);
        }
        Cpio.writeOdcTrailer(out);

        eq(predicted, (long) out.size(), "predicted odc length is the bytes written");
        List<Cpio.Entry> parsed = Cpio.read(out.toByteArray());
        eq(3, parsed.size(), "dot entry is not extracted");
        eq("佐藤の写真.jpg", parsed.get(2).name, "unicode name in odc");
        eq(1000, parsed.get(2).data.length, "streamed member length");
    }

    /** Sniffing has to leave the two other bodies alone, or the receive path breaks. */
    private static void bareArchivesAreNotMistakenForDvZip() throws Exception {
        byte[] newc = Cpio.write(java.util.Collections.singletonList(
            new Cpio.Entry("./a.txt", 0100644, "x".getBytes(StandardCharsets.US_ASCII))));
        byte[] odc = Cpio.writeOdc(java.util.Collections.singletonList(
            new Cpio.Entry("./a.txt", 0100644, "x".getBytes(StandardCharsets.US_ASCII))));

        check(!DvZip.looksLikeBlock(new byte[] {newc[0], newc[1], newc[2], newc[3]}),
            "newc magic is not a block header");
        check(!DvZip.looksLikeBlock(new byte[] {odc[0], odc[1], odc[2], odc[3]}),
            "odc magic is not a block header");
        eq(1, Cpio.read(newc).size(), "bare newc still parses");
        eq(1, Cpio.read(odc).size(), "bare odc still parses");
    }

    private static void truncatedBlockRejected() throws Exception {
        byte[] payload = new byte[1000];
        new Random(7L).nextBytes(payload);
        byte[] framed = encode(payload);
        byte[] cut = new byte[framed.length - 100];
        System.arraycopy(framed, 0, cut, 0, cut.length);
        try {
            decode(cut);
            throw new AssertionError("accepted a truncated block");
        } catch (IOException expected) {
            check(true, "truncated block rejected");
        }
    }

    private static void implausibleLengthRejected() throws Exception {
        // 1 MiB in the header, nothing behind it: not a block DVZ-2 allows.
        byte[] lying = {0, 16, 0, 0, 0, 0, 0, 0};
        try {
            decode(lying);
            throw new AssertionError("accepted an oversized block length");
        } catch (IOException expected) {
            check(true, "oversized block length rejected");
        }
    }

    private static byte[] encode(byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DvZip.Encoder encoder = new DvZip.Encoder(out);
        encoder.write(payload, 0, payload.length);
        encoder.finish();
        return out.toByteArray();
    }

    private static byte[] decode(byte[] framed) throws IOException {
        InputStream in = DvZip.decode(new ByteArrayInputStream(framed));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer, 0, buffer.length)) >= 0) {
            if (read == 0) continue;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static int beLength(byte[] framed, int offset) {
        return ((framed[offset] & 0x7f) << 24) | ((framed[offset + 1] & 0xff) << 16)
            | ((framed[offset + 2] & 0xff) << 8) | (framed[offset + 3] & 0xff);
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

    private DvZipTest() {
    }
}
