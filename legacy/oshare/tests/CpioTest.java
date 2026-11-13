package com.oshare.airdrop.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Codec checks for the cpio layer; runs on a plain JVM. */
public final class CpioTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        newcRoundTrip();
        odcArchiveParses();
        odcLayoutIsUnpadded();
        gzipWrappedArchiveExtracts();
        namesAreSanitized();
        streamingSinkControlsRetention();
        truncatedArchiveRejected();
        System.out.println("CpioTest OK (" + checks + " checks)");
    }

    private static void newcRoundTrip() throws Exception {
        byte[] payload = new byte[200_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);

        List<Cpio.Entry> source = new ArrayList<>();
        source.add(new Cpio.Entry(".", 0040755, new byte[0]));
        source.add(new Cpio.Entry("./holiday.jpg", 0100644, "jpeg bytes".getBytes(StandardCharsets.US_ASCII)));
        source.add(new Cpio.Entry("./empty.txt", 0100644, new byte[0]));
        source.add(new Cpio.Entry("./big.bin", 0100644, payload));
        source.add(new Cpio.Entry("./佐藤の写真.jpg", 0100644, new byte[] {1, 2, 3}));

        byte[] archive = Cpio.write(source);
        List<Cpio.Entry> parsed = Cpio.read(archive);

        // The "." member is skipped by name sanitizing, so four survive.
        eq(4, parsed.size(), "member count");
        eq("holiday.jpg", parsed.get(0).name, "first name");
        eq("jpeg bytes", new String(parsed.get(0).data, StandardCharsets.US_ASCII), "first payload");
        check(!parsed.get(0).isDirectory(), "regular file");
        eq(0, parsed.get(1).data.length, "empty member");
        eq(200_000, parsed.get(2).data.length, "large member length");
        check(java.util.Arrays.equals(payload, parsed.get(2).data), "large member bytes");
        eq("佐藤の写真.jpg", parsed.get(3).name, "unicode name");
        check(java.util.Arrays.equals(new byte[] {1, 2, 3}, parsed.get(3).data), "unicode member bytes");
    }

    /** odc uses octal fields and pads nothing: name, NUL, data, next header. */
    private static void odcArchiveParses() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        odcMember(out, "pad", 0100644, "even".getBytes(StandardCharsets.US_ASCII));
        odcMember(out, "pads", 0100644, "odd!".getBytes(StandardCharsets.US_ASCII));
        odcMember(out, "sub/", 0040755, new byte[0]);
        odcMember(out, "TRAILER!!!", 0, new byte[0]);

        List<Cpio.Entry> parsed = Cpio.read(out.toByteArray());
        eq(3, parsed.size(), "odc member count");
        eq("pad", parsed.get(0).name, "odc even name");
        eq("even", new String(parsed.get(0).data, StandardCharsets.US_ASCII), "odc even payload");
        eq("pads", parsed.get(1).name, "odc odd name");
        eq("odd!", new String(parsed.get(1).data, StandardCharsets.US_ASCII), "odc odd payload");
        eq("sub", parsed.get(2).name, "odc directory name");
        check(parsed.get(2).isDirectory(), "odc directory mode decoded");
    }

    /**
     * The archive's bytes, position by position.
     *
     * <p>Written because the round-trip tests could not fail: the writer inserted a pad
     * byte after an odd-length name and the reader skipped one, so encode-then-decode
     * agreed with itself while every file a real receiver extracted was shifted by a
     * byte. This checks the offsets instead of the round trip. {@code ./IMG_1234} has an
     * odd namesize (10 characters plus {@code ./} plus the NUL), which is exactly the
     * case the pad used to fire on.
     */
    private static void odcLayoutIsUnpadded() throws Exception {
        String name = "./IMG_1234";
        byte[] body = {0x11, 0x22, 0x33, 0x44};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Cpio.writeOdcMember(out, name, 0100644, body, 1);
        byte[] wire = out.toByteArray();

        int nameSize = name.length() + 1;
        check(nameSize % 2 == 1, "the interesting case has an odd namesize");
        eq(76 + nameSize + body.length, wire.length, "no byte between the name and the data");
        eq((long) wire.length, Cpio.odcMemberLength(name, body.length),
            "the length the progress bar uses matches the wire");
        eq(nameSize, Integer.parseInt(new String(wire, 59, 6, StandardCharsets.US_ASCII), 8),
            "c_namesize counts the NUL");
        eq(0100644, Integer.parseInt(new String(wire, 18, 6, StandardCharsets.US_ASCII), 8),
            "c_mode carries the permission bits UP-2a names");
        eq(0, wire[76 + nameSize - 1] & 0xff, "the name ends with a NUL");
        eq(0x11, wire[76 + nameSize] & 0xff, "the data starts right after that NUL");
        eq(0x44, wire[wire.length - 1] & 0xff, "and the last byte is the file's last byte");
    }

    private static void gzipWrappedArchiveExtracts() throws Exception {
        List<Cpio.Entry> source = new ArrayList<>();
        source.add(new Cpio.Entry("./note.txt", 0100644, "compressed body".getBytes(StandardCharsets.US_ASCII)));
        byte[] archive = Cpio.write(source);

        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gzipped)) {
            gz.write(archive);
        }
        check((gzipped.toByteArray()[0] & 0xff) == 0x1f, "gzip magic present");
        List<Cpio.Entry> parsed = Cpio.read(gzipped.toByteArray());
        eq(1, parsed.size(), "gzip member count");
        eq("compressed body", new String(parsed.get(0).data, StandardCharsets.US_ASCII), "gzip payload");
    }

    private static void namesAreSanitized() {
        eq("a/b", Cpio.safeName("./a/b"), "dot prefix stripped");
        eq("abs/path", Cpio.safeName("/abs/path"), "absolute made relative");
        eq("a/b", Cpio.safeName("a//b"), "empty segment dropped");
        eq("a/b", Cpio.safeName("a/./b"), "single dot dropped");
        eq("a", Cpio.safeName("a/"), "trailing slash dropped");
        eq(null, Cpio.safeName(".."), "bare parent rejected");
        eq(null, Cpio.safeName("../../etc/passwd"), "escape rejected");
        eq(null, Cpio.safeName("a/../../b"), "embedded escape rejected");
        eq(null, Cpio.safeName(""), "empty rejected");
        eq(null, Cpio.safeName(null), "null rejected");
        eq(null, Cpio.safeName("."), "current directory rejected");
    }

    private static void streamingSinkControlsRetention() throws Exception {
        List<Cpio.Entry> source = new ArrayList<>();
        source.add(new Cpio.Entry("./keep.txt", 0100644, "keep".getBytes(StandardCharsets.US_ASCII)));
        source.add(new Cpio.Entry("./drop.txt", 0100644, "drop".getBytes(StandardCharsets.US_ASCII)));
        byte[] archive = Cpio.write(source);

        final List<String> seen = new ArrayList<>();
        final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        int stored = Cpio.extract(new java.io.ByteArrayInputStream(archive), new Cpio.Sink() {
            @Override
            public OutputStream open(String name, long size, int mode) {
                seen.add(name + ":" + size);
                return name.equals("keep.txt") ? kept : null;
            }
        });
        eq(2, seen.size(), "sink saw every member");
        eq("keep.txt:4", seen.get(0), "first member metadata");
        eq("drop.txt:4", seen.get(1), "second member metadata");
        eq(1, stored, "only kept members counted");
        eq("keep", new String(kept.toByteArray(), StandardCharsets.US_ASCII), "kept bytes");
    }

    private static void truncatedArchiveRejected() {
        try {
            List<Cpio.Entry> source = new ArrayList<>();
            source.add(new Cpio.Entry("./a.txt", 0100644, "0123456789".getBytes(StandardCharsets.US_ASCII)));
            byte[] archive = Cpio.write(source);
            byte[] cut = new byte[archive.length - 30];
            System.arraycopy(archive, 0, cut, 0, cut.length);
            Cpio.read(cut);
            throw new AssertionError("accepted a truncated archive");
        } catch (IOException expected) {
            check(true, "truncated archive rejected");
        }
        try {
            Cpio.read("this is not a cpio archive".getBytes(StandardCharsets.US_ASCII));
            throw new AssertionError("accepted a foreign archive");
        } catch (IOException expected) {
            check(true, "foreign archive rejected");
        }
    }

    private static void odcMember(ByteArrayOutputStream out, String name, int mode, byte[] data) {
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        int nameSize = raw.length + 1;
        ascii(out, "070707");
        octal(out, 0, 6);          // c_dev
        octal(out, 1, 6);          // c_ino
        octal(out, mode, 6);       // c_mode
        octal(out, 0, 6);          // c_uid
        octal(out, 0, 6);          // c_gid
        octal(out, 1, 6);          // c_nlink
        octal(out, 0, 6);          // c_rdev
        octal(out, 0, 11);         // c_mtime
        octal(out, nameSize, 6);   // c_namesize
        octal(out, data.length, 11); // c_filesize
        out.write(raw, 0, raw.length);
        out.write(0);
        out.write(data, 0, data.length);
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        out.write(raw, 0, raw.length);
    }

    private static void octal(ByteArrayOutputStream out, long value, int width) {
        String text = Long.toOctalString(value);
        StringBuilder padded = new StringBuilder(width);
        for (int i = text.length(); i < width; i++) padded.append('0');
        ascii(out, padded.append(text).toString());
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

    private CpioTest() {
    }
}
