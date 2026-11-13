package com.oshare.airdrop.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Codec checks for the binary plist layer; runs on a plain JVM. */
public final class PlistTest {
    private static int checks;
    /** Directory where payloads are left for the Python cross-check. */
    private static final File INTEROP = new File("build/interop");

    public static void main(String[] args) throws Exception {
        decodesPythonFixture();
        askRoundTrip();
        unicodeRoundTrip();
        extendedLengthsRoundTrip();
        dateRoundTrip();
        malformedPayloadsRejected();
        writeInteropFixtures();
        System.out.println("PlistTest OK (" + checks + " checks)");
    }

    /** Byte-for-byte output of Python's plistlib for {b: True, n: 7, s: "hi"}. */
    private static void decodesPythonFixture() throws Exception {
        byte[] payload = hex(
            "62706c6973743030d30102030405065162516e5173091007526869"
                + "080f1113151618"
                + "000000000000010100000000000000070000000000000000000000000000001b");
        Map<String, Object> map = Bplist.parseDictionary(payload);
        eq(3, map.size(), "fixture key count");
        eq(Boolean.TRUE, map.get("b"), "fixture boolean");
        eq(7L, map.get("n"), "fixture integer");
        eq("hi", map.get("s"), "fixture string");
    }

    /** The real /Ask shape, sent and parsed back. */
    private static void askRoundTrip() throws Exception {
        Map<String, Object> file = Bplist.dictionary(
            "FileName", "holiday.jpg",
            "FileType", "public.jpeg",
            "FileBomPath", "./holiday.jpg",
            "FileIsDirectory", Boolean.FALSE,
            "ConvertMediaFormats", 0L);
        List<Object> files = new ArrayList<>();
        files.add(file);
        Map<String, Object> ask = Bplist.dictionary(
            "SenderComputerName", "Makoto's 13",
            "BundleID", "com.apple.finder",
            "SenderModelName", "MacBookPro18,3",
            "SenderID", "AA:BB:CC:DD:EE:FF",
            "ConvertMediaFormats", Boolean.FALSE,
            "Files", files);

        byte[] payload = Bplist.encode(ask);
        Map<String, Object> back = Bplist.parseDictionary(payload);
        eq(6, back.size(), "ask field count");
        eq("Makoto's 13", Bplist.string(back, "SenderComputerName"), "sender name");
        eq("com.apple.finder", Bplist.string(back, "BundleID"), "bundle id");
        eq(Boolean.FALSE, back.get("ConvertMediaFormats"), "convert flag");
        eq(false, Bplist.flag(back, "ConvertMediaFormats", true), "convert flag helper");
        eq("absent", fallbackOf(back, "Nope"), "missing field fallback");

        List<?> parsed = Bplist.list(back, "Files");
        eq(1, parsed.size(), "file count");
        Map<?, ?> entry = (Map<?, ?>) parsed.get(0);
        eq("./holiday.jpg", Bplist.string(entry, "FileBomPath"), "bom path");
        eq("public.jpeg", Bplist.string(entry, "FileType"), "uti");
        eq(0L, entry.get("ConvertMediaFormats"), "per-file convert flag");
    }

    private static void unicodeRoundTrip() throws Exception {
        Map<String, Object> map = Bplist.dictionary(
            "ReceiverComputerName", "佐藤のMacBook",
            "ascii", "plain");
        byte[] payload = Bplist.encode(map);
        // Non-ASCII strings must use the UTF-16 marker, not the UTF-8 one, so that
        // strict bplist00 parsers still accept the payload.
        check(contains(payload, "佐藤のMacBook".getBytes(StandardCharsets.UTF_16BE)),
            "utf-16 encoded on the wire");
        Map<String, Object> back = Bplist.parseDictionary(payload);
        eq("佐藤のMacBook", Bplist.string(back, "ReceiverComputerName"), "unicode string");
        eq("plain", Bplist.string(back, "ascii"), "ascii neighbour");
    }

    private static void extendedLengthsRoundTrip() throws Exception {
        byte[] blob = new byte[400];
        for (int i = 0; i < blob.length; i++) blob[i] = (byte) (i * 7);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) longText.append((char) ('a' + (i % 26)));

        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) wide.put("k" + i, (long) i);

        Map<String, Object> map = Bplist.dictionary(
            "FileIcon", blob,
            "longText", longText.toString(),
            "wide", wide,
            "empty", new byte[0]);
        byte[] payload = Bplist.encode(map);
        Map<String, Object> back = Bplist.parseDictionary(payload);

        byte[] parsedBlob = Bplist.bytes(back, "FileIcon");
        eq(blob.length, parsedBlob.length, "icon length");
        check(java.util.Arrays.equals(blob, parsedBlob), "icon bytes");
        eq(longText.toString(), Bplist.string(back, "longText"), "long string");
        eq(20, ((Map<?, ?>) back.get("wide")).size(), "wide dict size");
        eq(19L, ((Map<?, ?>) back.get("wide")).get("k19"), "wide dict tail");
        eq(0, Bplist.bytes(back, "empty").length, "empty data");
    }

    private static void dateRoundTrip() throws Exception {
        Date when = new Date(1700000000000L);
        Map<String, Object> map = Bplist.dictionary("when", when);
        Object back = Bplist.parseDictionary(Bplist.encode(map)).get("when");
        check(back instanceof Date, "date type preserved");
        eq(when.getTime(), ((Date) back).getTime(), "date value");
    }

    private static void malformedPayloadsRejected() {
        rejects("not a plist at all, definitely long enough to pass the length gate!!",
            "foreign payload");
        rejects("", "empty payload");
        byte[] good;
        try {
            good = Bplist.encode(Bplist.dictionary("k", "v"));
        } catch (RuntimeException failure) {
            throw new AssertionError(failure);
        }
        byte[] truncated = new byte[good.length - 8];
        System.arraycopy(good, 0, truncated, 0, truncated.length);
        rejectsBytes(truncated, "truncated payload");
    }

    private static void writeInteropFixtures() throws Exception {
        if (!INTEROP.exists() && !INTEROP.mkdirs()) {
            throw new IllegalStateException("cannot create " + INTEROP);
        }
        Map<String, Object> ask = Bplist.dictionary(
            "SenderComputerName", "Makoto's 13",
            "BundleID", "com.apple.finder",
            "SenderModelName", "MacBookPro18,3",
            "SenderID", "AA:BB:CC:DD:EE:FF",
            "ConvertMediaFormats", Boolean.FALSE,
            "Files", List.of(Bplist.dictionary(
                "FileName", "佐藤.jpg",
                "FileType", "public.jpeg",
                "FileBomPath", "./佐藤.jpg",
                "FileIsDirectory", Boolean.FALSE,
                "ConvertMediaFormats", 0L)));
        write(new File(INTEROP, "java-ask.plist"), Bplist.encode(ask));

        Map<String, Object> discover = Bplist.dictionary(
            "ReceiverComputerName", "Makoto's 13",
            "ReceiverModelName", "MacBookPro18,3",
            "ReceiverMediaCapabilities",
            "{\"Version\":1}".getBytes(StandardCharsets.UTF_8));
        write(new File(INTEROP, "java-discover.plist"), Bplist.encode(discover));
        check(true, "interop fixtures written");
    }

    private static void write(File target, byte[] payload) throws Exception {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(payload);
        }
    }

    private static Object fallbackOf(Map<String, Object> map, String key) {
        Object value = Bplist.string(map, key);
        return value == null ? "absent" : value;
    }

    private static void rejects(String text, String what) {
        rejectsBytes(text.getBytes(StandardCharsets.US_ASCII), what);
    }

    private static void rejectsBytes(byte[] payload, String what) {
        try {
            Bplist.parse(payload);
            throw new AssertionError("accepted " + what);
        } catch (Bplist.FormatException expected) {
            check(true, what + " rejected");
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
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

    private PlistTest() {
    }
}
