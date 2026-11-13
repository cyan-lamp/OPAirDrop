package com.oshare.airdrop.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Semantics of the three AirDrop endpoints, checked on a plain JVM. */
public final class ProtocolTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        askParsesSenderPayload();
        askRequestRoundTrips();
        discoverRequestBody();
        linksOnlyAsk();
        iconIsDetected();
        emptyAskTolerated();
        itemNamesAreSandboxed();
        summariesDescribeTheOffer();
        answersCarryIdentity();
        discoverAnswerCapabilities();
        missingSenderNameFallsBackToModel();
        radiotapStatesThePhy();
        System.out.println("ProtocolTest OK (" + checks + " checks)");
    }

    /** The shape Apple's Finder actually posts to {@code /Ask}. */
    private static void askParsesSenderPayload() throws Exception {
        List<Object> files = new ArrayList<>();
        files.add(file("holiday.jpg", "public.jpeg", "./holiday.jpg", false));
        files.add(file("recap.pdf", "com.adobe.pdf", "./recap.pdf", false));
        Map<String, Object> body = Bplist.dictionary(
            "SenderComputerName", "Makoto's 13",
            "SenderModelName", "MacBookPro18,3",
            "SenderID", "AA:BB:CC:DD:EE:FF",
            "BundleID", "com.apple.finder",
            "ConvertMediaFormats", Boolean.FALSE);
        body.put("Files", files);

        AirDropProtocol.Ask ask = AirDropProtocol.ask(Bplist.encode(body));
        eq("Makoto's 13", ask.senderName, "sender name");
        eq("MacBookPro18,3", ask.senderModel, "sender model");
        eq("AA:BB:CC:DD:EE:FF", ask.senderId, "sender id");
        eq("com.apple.finder", ask.bundleId, "bundle id");
        check(!ask.convertMediaFormats, "no transcoding requested");
        eq(2, ask.files.size(), "file count");
        eq("holiday.jpg", ask.files.get(0).name, "first name");
        eq("public.jpeg", ask.files.get(0).type, "first type");
        eq("./holiday.jpg", ask.files.get(0).bomPath, "first bom path");
        check(!ask.files.get(0).directory, "file is not a directory");
        eq("recap.pdf", ask.files.get(1).name, "second name");
        eq(0, ask.links.size(), "no links");
        check(!ask.hasIcon, "no icon");
    }

    /** What we encode for an outgoing send must parse back into the same request. */
    private static void askRequestRoundTrips() throws Exception {
        List<AirDropProtocol.Item> files = new ArrayList<>();
        files.add(new AirDropProtocol.Item("佐藤の写真.jpg", "public.jpeg", "./佐藤の写真.jpg",
            false, 4096L));
        files.add(new AirDropProtocol.Item("notes", "public.folder", "./notes", true));

        String transferId = AirDropProtocol.newTransferId();
        byte[] body = AirDropProtocol.askRequest(
            "Pixel 9", "SM8750", "11:22:33:44:55:66", transferId, files,
            Collections.<String>emptyList());
        AirDropProtocol.Ask ask = AirDropProtocol.ask(body);

        eq("Pixel 9", ask.senderName, "round-trip name");
        eq("SM8750", ask.senderModel, "round-trip model");
        eq("11:22:33:44:55:66", ask.senderId, "round-trip id");
        eq("com.apple.finder", ask.bundleId, "fixed bundle id");
        check(!ask.convertMediaFormats, "round-trip flag");
        eq(2, ask.files.size(), "round-trip files");
        eq("佐藤の写真.jpg", ask.files.get(0).name, "unicode name survives");
        check(ask.files.get(1).directory, "directory flag survives");
        eq("notes", ask.files.get(1).memberName(), "directory member name");
        // The size is what separates the current form from the legacy one; a receiver
        // given no size has to assume zero and will not prompt.
        eq(4096L, ask.files.get(0).size, "declared size survives");

        Map<String, Object> raw = Bplist.parseDictionary(body);
        check(raw.get("TransferID") instanceof Map, "current form carries TransferID");
        Map<?, ?> transfer = (Map<?, ?>) raw.get("TransferID");
        eq(transferId, transfer.get("id"), "TransferID is the caller's id");
        check(raw.get("TransferType") instanceof Map, "current form carries TransferType");
        check(((Map<?, ?>) raw.get("TransferType")).containsKey("files"),
            "TransferType names files");
        // A file whose size is unknown must be described without FileSize rather than
        // with a zero, which a receiver would believe.
        Map<?, ?> directory = (Map<?, ?>) ((List<?>) raw.get("Files")).get(1);
        check(!directory.containsKey("FileSize"), "unknown size is omitted, not zeroed");
        eq(Boolean.FALSE, directory.get("ShouldConvertMediaFormats"),
            "per-file conversion flag is the current spelling");
    }

    /** An empty dictionary is a legal /Discover body; a body with a name is the useful one. */
    private static void discoverRequestBody() throws Exception {
        Map<String, Object> body = Bplist.parseDictionary(
            AirDropProtocol.discoverRequest("Pixel 9", "SM8750", "11:22:33:44:55:66"));
        eq("Pixel 9", body.get("SenderComputerName"), "discover names the sender");
        eq("SM8750", body.get("SenderModelName"), "discover names the model");
        eq("11:22:33:44:55:66", body.get("SenderID"), "discover names the id");
        check(!body.isEmpty(), "discover body is not empty");
    }

    private static void linksOnlyAsk() throws Exception {
        Map<String, Object> body = Bplist.dictionary(
            "SenderComputerName", "iPhone",
            "SenderModelName", "iPhone16,2");
        body.put("Items", new ArrayList<Object>(Arrays.asList("https://example.com/a")));

        AirDropProtocol.Ask ask = AirDropProtocol.ask(Bplist.encode(body));
        eq(0, ask.files.size(), "no files");
        eq(1, ask.links.size(), "one link");
        eq("https://example.com/a", ask.links.get(0), "link value");
        eq("https://example.com/a", ask.summary(), "summary is the link");
    }

    private static void iconIsDetected() throws Exception {
        Map<String, Object> body = Bplist.dictionary(
            "SenderComputerName", "iPhone",
            "FileIcon", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        check(AirDropProtocol.ask(Bplist.encode(body)).hasIcon, "icon present");

        Map<String, Object> plain = Bplist.dictionary("SenderComputerName", "iPhone");
        check(!AirDropProtocol.ask(Bplist.encode(plain)).hasIcon, "icon absent");
    }

    /** An empty dictionary is legal; every accessor must return a safe default. */
    private static void emptyAskTolerated() throws Exception {
        AirDropProtocol.Ask ask = AirDropProtocol.ask(Bplist.encode(Bplist.dictionary()));
        eq("", ask.senderName, "empty name");
        eq("", ask.senderModel, "empty model");
        eq("", ask.senderId, "empty id");
        eq("", ask.bundleId, "empty bundle");
        eq(0, ask.files.size(), "empty files");
        eq(0, ask.links.size(), "empty links");
        eq("", ask.summary(), "empty summary");
        eq("", ask.displayName(), "empty display name");
    }

    /** {@code FileBomPath} may be hostile; the archive name must stay inside the sandbox. */
    private static void itemNamesAreSandboxed() throws Exception {
        eq("holiday.jpg", item("holiday.jpg", "./holiday.jpg").memberName(), "dot-slash stripped");
        eq("sub/photo.jpg", item("photo.jpg", "./sub/photo.jpg").memberName(), "subdirectory kept");
        eq("abs.jpg", item("abs.jpg", "/abs.jpg").memberName(), "leading slash stripped");
        // A traversal path is rejected, so the plain name is used instead.
        eq("passwd", item("passwd", "../../etc/passwd").memberName(), "traversal falls back to name");
        // A missing FileBomPath falls back to FileName.
        eq("plain.txt", item("plain.txt", "").memberName(), "empty bom path");
        // A missing FileType must not leave the field empty.
        eq("public.content", new AirDropProtocol.Item("x", null, "", false).type, "default type");
    }

    private static void summariesDescribeTheOffer() throws Exception {
        List<Object> files = new ArrayList<>();
        files.add(file("a.jpg", "public.jpeg", "./a.jpg", false));
        files.add(file("b.jpg", "public.jpeg", "./b.jpg", false));
        files.add(file("c.jpg", "public.jpeg", "./c.jpg", false));
        Map<String, Object> body = Bplist.dictionary("SenderComputerName", "Mac");
        body.put("Files", files);

        AirDropProtocol.Ask ask = AirDropProtocol.ask(Bplist.encode(body));
        eq("a.jpg", AirDropProtocol.ask(Bplist.encode(singleFile())).summary(), "single file shows its name");
        eq("3 files", ask.summary(), "several files are counted");
    }

    private static void answersCarryIdentity() throws Exception {
        Map<String, Object> ask = Bplist.parseDictionary(
            AirDropProtocol.askAnswer("Pixel 9", "SM8750"));
        eq("Pixel 9", Bplist.string(ask, "ReceiverComputerName"), "ask answer name");
        eq("SM8750", Bplist.string(ask, "ReceiverModelName"), "ask answer model");
        eq(2, ask.size(), "ask answer has exactly two keys");

        Map<String, Object> discover = Bplist.parseDictionary(
            AirDropProtocol.discoverAnswer("Pixel 9", "SM8750"));
        eq("Pixel 9", Bplist.string(discover, "ReceiverComputerName"), "discover answer name");
        eq("SM8750", Bplist.string(discover, "ReceiverModelName"), "discover answer model");
        eq(3, discover.size(), "discover answer has three keys");
    }

    private static void discoverAnswerCapabilities() throws Exception {
        Map<String, Object> answer = Bplist.parseDictionary(
            AirDropProtocol.discoverAnswer("Pixel 9", "SM8750"));
        byte[] capabilities = Bplist.bytes(answer, "ReceiverMediaCapabilities");
        eq("{\"Version\": 1}", new String(capabilities, StandardCharsets.UTF_8), "default capabilities");

        byte[] custom = "{\"Version\":1,\"ImageTypes\":[]}".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> tuned = Bplist.parseDictionary(
            AirDropProtocol.discoverAnswer("Pixel 9", "SM8750", custom));
        check(Arrays.equals(custom, Bplist.bytes(tuned, "ReceiverMediaCapabilities")), "custom capabilities");
    }

    private static void missingSenderNameFallsBackToModel() throws Exception {
        AirDropProtocol.Ask ask = AirDropProtocol.ask(Bplist.encode(
            Bplist.dictionary("SenderModelName", "iPhone16,2")));
        eq("", ask.senderName, "no name supplied");
        eq("iPhone16,2", ask.displayName(), "model used as display name");
    }

    private static Map<String, Object> singleFile() {
        List<Object> files = new ArrayList<>();
        files.add(file("a.jpg", "public.jpeg", "./a.jpg", false));
        Map<String, Object> body = Bplist.dictionary("SenderComputerName", "Mac");
        body.put("Files", files);
        return body;
    }

    private static AirDropProtocol.Item item(String name, String bomPath) {
        return new AirDropProtocol.Item(name, "public.data", bomPath, false);
    }

    private static Map<String, Object> file(String name, String type, String bomPath, boolean directory) {
        return Bplist.dictionary(
            "FileName", name,
            "FileType", type,
            "FileBomPath", bomPath,
            "FileIsDirectory", directory,
            "ConvertMediaFormats", 0L);
    }

    /**
     * The bytes that decide how fast the frame leaves the radio.
     *
     * <p>Radiotap states a rate twice over: a legacy number in Mb/s, and for 802.11n/ac an
     * MCS index that overrides it. Both halves have a known mask -- a byte saying which of
     * the following fields are meant -- and a field written without its bit set is not a
     * slow frame, it is a frame where the driver keeps whatever it was already doing. That
     * reads on the wire exactly like the chip refusing the field, so the masks are pinned
     * here rather than inferred from another transfer.
     */
    private static void radiotapStatesThePhy() {
        AwdlFrame.setTxRateMbps(AwdlFrame.DEFAULT_RATE_MBPS);
        AwdlFrame.setTxPhy(-1, 20, false, false);
        byte[] legacy = AwdlFrame.radiotapHeader();
        eq(9, legacy.length, "legacy header: eight byte preamble plus one rate byte");
        eq(0x04, legacy[4] & 0xff, "legacy header states the rate and nothing else");
        eq(72, legacy[8] & 0xff, "thirty-six Mb/s is seventy-two half-megabit units");

        // Single stream, MCS 7, eighty megahertz, 802.11ac, long guard interval.
        AwdlFrame.setTxPhy(7, 80, true, false);
        byte[] vht = AwdlFrame.radiotapHeader();
        eq(24, vht.length, "rate, MCS and VHT fields pack without padding");
        eq(0x00280004, present(vht), "present word: rate, MCS, VHT");
        eq(72, vht[8] & 0xff, "the legacy rate stays underneath the MCS");
        eq(0x03, vht[9] & 0xff, "MCS known mask admits the index and the bandwidth");
        eq(0x01, vht[10] & 0xff, "MCS flags say forty megahertz");
        eq(7, vht[11] & 0xff, "MCS index seven");
        eq(0x0044, vht[12] & 0xff | ((vht[13] & 0xff) << 8), "VHT known mask: guard interval and bandwidth");
        eq(0x00, vht[14] & 0xff, "VHT flags: long guard interval");
        eq(0x02, vht[15] & 0xff, "VHT bandwidth: eighty megahertz");
        eq(0x17, vht[16] & 0xff, "one space-time stream, MCS seven");

        // A short guard interval has to show up in both known masks and in the flags.
        AwdlFrame.setTxPhy(4, 40, true, true);
        byte[] sgi = AwdlFrame.radiotapHeader();
        eq(0x07, sgi[9] & 0xff, "MCS known mask admits the guard interval");
        eq(0x03, sgi[10] & 0xff, "MCS flags: forty megahertz, short guard interval");
        eq(0x04, sgi[14] & 0xff, "VHT flags: short guard interval");
        eq(0x01, sgi[15] & 0xff, "VHT bandwidth: forty megahertz");
        eq(0x14, sgi[16] & 0xff, "one space-time stream, MCS four");

        // 802.11n tops out at forty megahertz, so the MCS field is all there is.
        AwdlFrame.setTxPhy(5, 80, false, false);
        byte[] ht = AwdlFrame.radiotapHeader();
        eq(12, ht.length, "an MCS without VHT is three bytes past the rate");
        eq(0x00080004, present(ht), "present word: rate and MCS only");
        eq(0x01, ht[10] & 0xff, "802.11n asks for forty megahertz, the widest it has");

        // Zero restores the rate-less header the very first frames carried.
        AwdlFrame.setTxRateMbps(0);
        AwdlFrame.setTxPhy(-1, 20, false, false);
        eq(8, AwdlFrame.radiotapHeader().length, "no fields at all is eight bytes");
        eq(0, present(AwdlFrame.radiotapHeader()), "an empty present word");

        AwdlFrame.setTxRateMbps(AwdlFrame.DEFAULT_RATE_MBPS);
    }

    private static int present(byte[] radiotap) {
        return (radiotap[4] & 0xff)
            | ((radiotap[5] & 0xff) << 8)
            | ((radiotap[6] & 0xff) << 16)
            | ((radiotap[7] & 0xff) << 24);
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

    private ProtocolTest() {
    }
}
