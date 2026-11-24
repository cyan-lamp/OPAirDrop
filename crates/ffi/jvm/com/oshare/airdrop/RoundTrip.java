package com.oshare.airdrop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Asserts, from the Java side, that the Rust side did what it said it would.
 *
 * <p>Run by {@code check.sh}; arguments are the archive to hand over, the directory to
 * receive it into, and the names the archive holds, in order.
 *
 * <p>This exists because "it compiles" proves nothing about a JNI boundary. The symbol can
 * be misspelled, the table lookup can be off, the callback can hand back a length the
 * caller never promised — none of which the Rust compiler can see. Everything below is a
 * claim the Rust tests also make, restated where the JVM can check it.
 */
public final class RoundTrip {
    private static int failures = 0;
    // Counted, not inferred. This class used to print "all checks passed" and no number, and
    // the four classes beside it each printed "ok, N checks" — so anyone adding up the five
    // summary lines got a total that silently lacked this section's checks. The documents
    // carried 355 / 375 / 381 for months; the real figure is 394 (see Plan.md §6.5).
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        Path archive = Paths.get(args[0]);
        Path inbox = Paths.get(args[1]);
        Path scratch = Paths.get(args[3]);
        // Names come from a file, not from argv: a shell that hands a Java program its
        // arguments through the Windows code page would mangle 假期 before this code saw
        // it, and the failure would look like the library's.
        List<String> expected = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) {
                expected.add(line);
            }
        }

        check("the ABI version is the one the app was built against",
                Native.abiVersion() == Native.ABI_VERSION,
                "abiVersion() returned " + Native.abiVersion());

        receiveLandsEveryMember(archive, inbox, expected);
        serveAnswersAPeerAndStoresWhatItSends(archive, scratch, expected);
        serveWatchedReportsWhatItLands(archive, scratch, expected);
        aStreamThatGivesUpKeepsItsOwnException(archive, inbox);
        aVanishingPeerIsOneConnectionAndNotTheProcess(scratch);
        aMissingStreamIsRefusedRatherThanDereferenced(inbox);
        aSettingOutsideItsRangeIsRefusedBeforeAByteIsRead(archive, scratch);
        overwritingReplacesRatherThanNumbering(archive, scratch);

        if (failures > 0) {
            System.out.println("FAILED " + failures + " of " + checks);
            System.exit(1);
        }
        System.out.println("ok, " + checks + " checks");
    }

    private static void receiveLandsEveryMember(Path archive, Path inbox, List<String> expected)
            throws Exception {
        byte[] frame;
        try (InputStream in = new FileInputStream(archive.toFile())) {
            frame = Native.receiveArchive(inbox.toString(), null, in);
        } catch (IOException error) {
            check("a well-formed archive is accepted", false, error.toString());
            return;
        }

        check("a report frame comes back", frame != null && frame.length >= 8,
                "frame was " + (frame == null ? "null" : frame.length + " bytes"));

        Report report = Report.of(frame);
        List<String> names = report.names();
        check("the report names every member in archive order", names.equals(expected),
                "expected " + expected + ", got " + names);

        long total = report.bytes();
        long onDisk = 0;
        for (String name : names) {
            Path path = inbox.resolve(name);
            check("the member is on disk: " + name, Files.exists(path), path + " is missing");
            if (Files.isRegularFile(path)) {
                onDisk += Files.size(path);
            }
        }
        check("the reported byte total is what landed", total == onDisk,
                "report says " + total + ", disk holds " + onDisk);

        check("no temporary file is left behind",
                listTemporaries(inbox).isEmpty(),
                "found " + listTemporaries(inbox));

        // A second transfer into the same inbox must not overwrite the first, which is the
        // rule that keeps one sender's photo from replacing another's.
        byte[] again;
        try (InputStream in = new FileInputStream(archive.toFile())) {
            again = Native.receiveArchive(inbox.toString(), null, in);
        }
        List<String> second = Report.of(again).names();
        check("a second transfer into the same inbox gets fresh names",
                second.size() == expected.size() && !second.equals(names),
                "second transfer reported " + second);
    }

    /**
     * The whole protocol, driven from Java: a request goes in, an answer comes back out
     * through a different stream, and the archive lands.
     *
     * <p>{@link #receiveLandsEveryMember} starts after the head has been parsed, so it can
     * pass while the thing Android actually needs — answering a peer — is missing entirely.
     * This walks the same path a phone would: one stream of request bytes in, one stream of
     * answer bytes out, both handed to the library, and asserts the answer exists, says
     * {@code 200}, and names the same members that reached the disk.
     */
    /**
     * 一份会被接受的 /Ask 请求体（bplist，评审 P0-1 之后 /Upload 必须跟在同一条连接的
     * 接受之后）。由 {@code ask_request}（Sender "Ada's iPhone"、一个 hello.txt）确定性
     * 编码生成，Rust 侧 round-trip 测试保证编码稳定。
     */
    private static final byte[] ASK_BODY = {
            (byte) 0x62, (byte) 0x70, (byte) 0x6c, (byte) 0x69, (byte) 0x73, (byte) 0x74, (byte) 0x30, (byte) 0x30,
            (byte) 0xd9, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07,
            (byte) 0x08, (byte) 0x09, (byte) 0x0a, (byte) 0x0b, (byte) 0x0c, (byte) 0x0d, (byte) 0x0e, (byte) 0x0f,
            (byte) 0x12, (byte) 0x15, (byte) 0x21, (byte) 0x5f, (byte) 0x10, (byte) 0x12, (byte) 0x53, (byte) 0x65,
            (byte) 0x6e, (byte) 0x64, (byte) 0x65, (byte) 0x72, (byte) 0x43, (byte) 0x6f, (byte) 0x6d, (byte) 0x70,
            (byte) 0x75, (byte) 0x74, (byte) 0x65, (byte) 0x72, (byte) 0x4e, (byte) 0x61, (byte) 0x6d, (byte) 0x65,
            (byte) 0x5f, (byte) 0x10, (byte) 0x0f, (byte) 0x53, (byte) 0x65, (byte) 0x6e, (byte) 0x64, (byte) 0x65,
            (byte) 0x72, (byte) 0x4d, (byte) 0x6f, (byte) 0x64, (byte) 0x65, (byte) 0x6c, (byte) 0x4e, (byte) 0x61,
            (byte) 0x6d, (byte) 0x65, (byte) 0x58, (byte) 0x53, (byte) 0x65, (byte) 0x6e, (byte) 0x64, (byte) 0x65,
            (byte) 0x72, (byte) 0x49, (byte) 0x44, (byte) 0x58, (byte) 0x42, (byte) 0x75, (byte) 0x6e, (byte) 0x64,
            (byte) 0x6c, (byte) 0x65, (byte) 0x49, (byte) 0x44, (byte) 0x5f, (byte) 0x10, (byte) 0x13, (byte) 0x43,
            (byte) 0x6f, (byte) 0x6e, (byte) 0x76, (byte) 0x65, (byte) 0x72, (byte) 0x74, (byte) 0x4d, (byte) 0x65,
            (byte) 0x64, (byte) 0x69, (byte) 0x61, (byte) 0x46, (byte) 0x6f, (byte) 0x72, (byte) 0x6d, (byte) 0x61,
            (byte) 0x74, (byte) 0x73, (byte) 0x5a, (byte) 0x54, (byte) 0x72, (byte) 0x61, (byte) 0x6e, (byte) 0x73,
            (byte) 0x66, (byte) 0x65, (byte) 0x72, (byte) 0x49, (byte) 0x44, (byte) 0x5c, (byte) 0x54, (byte) 0x72,
            (byte) 0x61, (byte) 0x6e, (byte) 0x73, (byte) 0x66, (byte) 0x65, (byte) 0x72, (byte) 0x54, (byte) 0x79,
            (byte) 0x70, (byte) 0x65, (byte) 0x55, (byte) 0x46, (byte) 0x69, (byte) 0x6c, (byte) 0x65, (byte) 0x73,
            (byte) 0x55, (byte) 0x49, (byte) 0x74, (byte) 0x65, (byte) 0x6d, (byte) 0x73, (byte) 0x5c, (byte) 0x41,
            (byte) 0x64, (byte) 0x61, (byte) 0x27, (byte) 0x73, (byte) 0x20, (byte) 0x69, (byte) 0x50, (byte) 0x68,
            (byte) 0x6f, (byte) 0x6e, (byte) 0x65, (byte) 0x56, (byte) 0x69, (byte) 0x50, (byte) 0x68, (byte) 0x6f,
            (byte) 0x6e, (byte) 0x65, (byte) 0x5a, (byte) 0x38, (byte) 0x41, (byte) 0x32, (byte) 0x42, (byte) 0x3a,
            (byte) 0x31, (byte) 0x46, (byte) 0x3a, (byte) 0x39, (byte) 0x43, (byte) 0x5f, (byte) 0x10, (byte) 0x10,
            (byte) 0x63, (byte) 0x6f, (byte) 0x6d, (byte) 0x2e, (byte) 0x61, (byte) 0x70, (byte) 0x70, (byte) 0x6c,
            (byte) 0x65, (byte) 0x2e, (byte) 0x66, (byte) 0x69, (byte) 0x6e, (byte) 0x64, (byte) 0x65, (byte) 0x72,
            (byte) 0x08, (byte) 0xd1, (byte) 0x10, (byte) 0x11, (byte) 0x52, (byte) 0x69, (byte) 0x64, (byte) 0x5f,
            (byte) 0x10, (byte) 0x24, (byte) 0x35, (byte) 0x43, (byte) 0x35, (byte) 0x43, (byte) 0x35, (byte) 0x43,
            (byte) 0x35, (byte) 0x43, (byte) 0x2d, (byte) 0x35, (byte) 0x43, (byte) 0x35, (byte) 0x43, (byte) 0x2d,
            (byte) 0x34, (byte) 0x43, (byte) 0x35, (byte) 0x43, (byte) 0x2d, (byte) 0x39, (byte) 0x43, (byte) 0x35,
            (byte) 0x43, (byte) 0x2d, (byte) 0x35, (byte) 0x43, (byte) 0x35, (byte) 0x43, (byte) 0x35, (byte) 0x43,
            (byte) 0x35, (byte) 0x43, (byte) 0x35, (byte) 0x43, (byte) 0x35, (byte) 0x43, (byte) 0xd1, (byte) 0x13,
            (byte) 0x14, (byte) 0x55, (byte) 0x66, (byte) 0x69, (byte) 0x6c, (byte) 0x65, (byte) 0x73, (byte) 0xd0,
            (byte) 0xa1, (byte) 0x16, (byte) 0xd5, (byte) 0x17, (byte) 0x18, (byte) 0x19, (byte) 0x1a, (byte) 0x1b,
            (byte) 0x1c, (byte) 0x1d, (byte) 0x1e, (byte) 0x1f, (byte) 0x20, (byte) 0x58, (byte) 0x46, (byte) 0x69,
            (byte) 0x6c, (byte) 0x65, (byte) 0x4e, (byte) 0x61, (byte) 0x6d, (byte) 0x65, (byte) 0x58, (byte) 0x46,
            (byte) 0x69, (byte) 0x6c, (byte) 0x65, (byte) 0x54, (byte) 0x79, (byte) 0x70, (byte) 0x65, (byte) 0x5b,
            (byte) 0x46, (byte) 0x69, (byte) 0x6c, (byte) 0x65, (byte) 0x42, (byte) 0x6f, (byte) 0x6d, (byte) 0x50,
            (byte) 0x61, (byte) 0x74, (byte) 0x68, (byte) 0x5f, (byte) 0x10, (byte) 0x0f, (byte) 0x46, (byte) 0x69,
            (byte) 0x6c, (byte) 0x65, (byte) 0x49, (byte) 0x73, (byte) 0x44, (byte) 0x69, (byte) 0x72, (byte) 0x65,
            (byte) 0x63, (byte) 0x74, (byte) 0x6f, (byte) 0x72, (byte) 0x79, (byte) 0x5f, (byte) 0x10, (byte) 0x19,
            (byte) 0x53, (byte) 0x68, (byte) 0x6f, (byte) 0x75, (byte) 0x6c, (byte) 0x64, (byte) 0x43, (byte) 0x6f,
            (byte) 0x6e, (byte) 0x76, (byte) 0x65, (byte) 0x72, (byte) 0x74, (byte) 0x4d, (byte) 0x65, (byte) 0x64,
            (byte) 0x69, (byte) 0x61, (byte) 0x46, (byte) 0x6f, (byte) 0x72, (byte) 0x6d, (byte) 0x61, (byte) 0x74,
            (byte) 0x73, (byte) 0x59, (byte) 0x68, (byte) 0x65, (byte) 0x6c, (byte) 0x6c, (byte) 0x6f, (byte) 0x2e,
            (byte) 0x74, (byte) 0x78, (byte) 0x74, (byte) 0x5e, (byte) 0x70, (byte) 0x75, (byte) 0x62, (byte) 0x6c,
            (byte) 0x69, (byte) 0x63, (byte) 0x2e, (byte) 0x63, (byte) 0x6f, (byte) 0x6e, (byte) 0x74, (byte) 0x65,
            (byte) 0x6e, (byte) 0x74, (byte) 0x5b, (byte) 0x2e, (byte) 0x2f, (byte) 0x68, (byte) 0x65, (byte) 0x6c,
            (byte) 0x6c, (byte) 0x6f, (byte) 0x2e, (byte) 0x74, (byte) 0x78, (byte) 0x74, (byte) 0x08, (byte) 0x08,
            (byte) 0xa0, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x1b, (byte) 0x00, (byte) 0x30, (byte) 0x00,
            (byte) 0x42, (byte) 0x00, (byte) 0x4b, (byte) 0x00, (byte) 0x54, (byte) 0x00, (byte) 0x6a, (byte) 0x00,
            (byte) 0x75, (byte) 0x00, (byte) 0x82, (byte) 0x00, (byte) 0x88, (byte) 0x00, (byte) 0x8e, (byte) 0x00,
            (byte) 0x9b, (byte) 0x00, (byte) 0xa2, (byte) 0x00, (byte) 0xad, (byte) 0x00, (byte) 0xc0, (byte) 0x00,
            (byte) 0xc1, (byte) 0x00, (byte) 0xc4, (byte) 0x00, (byte) 0xc7, (byte) 0x00, (byte) 0xee, (byte) 0x00,
            (byte) 0xf1, (byte) 0x00, (byte) 0xf7, (byte) 0x00, (byte) 0xf8, (byte) 0x00, (byte) 0xfa, (byte) 0x01,
            (byte) 0x05, (byte) 0x01, (byte) 0x0e, (byte) 0x01, (byte) 0x17, (byte) 0x01, (byte) 0x23, (byte) 0x01,
            (byte) 0x35, (byte) 0x01, (byte) 0x51, (byte) 0x01, (byte) 0x5b, (byte) 0x01, (byte) 0x6a, (byte) 0x01,
            (byte) 0x76, (byte) 0x01, (byte) 0x77, (byte) 0x01, (byte) 0x78, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x22, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x79
    };

    /** /Ask 请求头：体长来自 {@link #ASK_BODY}。 */
    private static byte[] askHead() {
        return ("POST /Ask HTTP/1.1\r\n"
                + "Host: air\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + ASK_BODY.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static void serveAnswersAPeerAndStoresWhatItSends(
            Path archive, Path scratch, List<String> expected) throws Exception {
        Path inbox = Files.createDirectories(scratch.resolve("served"));
        byte[] body = Files.readAllBytes(archive);
        String head = "POST /Upload HTTP/1.1\r\n"
                + "Host: air\r\n"
                + "Content-Type: application/x-cpio\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        requests.write(askHead());
        requests.write(ASK_BODY);
        requests.write(head.getBytes(StandardCharsets.US_ASCII));
        requests.write(body);

        ByteArrayOutputStream answers = new ByteArrayOutputStream();
        byte[] frame;
        ByteArrayInputStream in = new ByteArrayInputStream(requests.toByteArray());
        try {
            // A blank name is what the table's default is, and the model stands in for it;
            // the answer's wording is asserted in Rust, this only needs the call to work
            // with the arguments an app would really pass.
            frame = Native.serve(inbox.toString(), "device.name=\n", "Pixel Test", in, answers);
        } finally {
            in.close();
        }

        String answer = new String(answers.toByteArray(), StandardCharsets.ISO_8859_1);
        check("the peer is answered", !answer.isEmpty(), "nothing was written to the output stream");
        check("the answer is a 200", answer.startsWith("HTTP/1.1 200"), answer.lines().findFirst().orElse(""));

        List<String> names = Report.of(frame).names();
        check("the report names every member the connection stored", names.equals(expected),
                "expected " + expected + ", got " + names);
        for (String name : names) {
            check("the served member is on disk: " + name,
                    Files.exists(inbox.resolve(name)), inbox.resolve(name) + " is missing");
        }
        check("serving leaves no temporary file behind", listTemporaries(inbox).isEmpty(),
                "found " + listTemporaries(inbox));
        // The head promised `body.length` bytes and the endpoint had to take them all: a
        // leftover would mean the archive was framed by the wrong number and the sender's
        // next byte would be read as a new request.
        check("the whole request was consumed", in.available() == 0,
                in.available() + " byte(s) of the request were never read");
    }

    /**
     * The same connection as {@link #serveAnswersAPeerAndStoresWhatItSends}, through the export
     * that also reports progress.
     *
     * <p>This is the assertion {@code cargo test} cannot make. A Rust test links the rlib, so a
     * mistyped JNI symbol name compiles there and fails on a phone instead, at the first
     * connection, as an {@code UnsatisfiedLinkError} in a log line nobody is reading. Calling
     * the method resolves the symbol the moment this class is linked.
     *
     * <p>The numbers are held against the report rather than against a constant, because that
     * is the only promise a watcher gets: its last number is what actually landed, and it never
     * goes backwards on the way there. No `/Ask` came first, so nothing declared a size — the
     * denominator falls back to the head's `Content-Length`（2026-11-24：实况照片的 offer 不带
     * size，旧做法全程 -1% 让流体云不画轨道），and it must not drift mid-transfer.
     */
    private static void serveWatchedReportsWhatItLands(
            Path archive, Path scratch, List<String> expected) throws Exception {
        Path inbox = Files.createDirectories(scratch.resolve("watched"));
        byte[] body = Files.readAllBytes(archive);
        String head = "POST /Upload HTTP/1.1\r\n"
                + "Host: air\r\n"
                + "Content-Type: application/x-cpio\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        requests.write(askHead());
        requests.write(ASK_BODY);
        requests.write(head.getBytes(StandardCharsets.US_ASCII));
        requests.write(body);

        List<long[]> seen = new ArrayList<>();
        ByteArrayOutputStream answers = new ByteArrayOutputStream();
        byte[] frame;
        ByteArrayInputStream in = new ByteArrayInputStream(requests.toByteArray());
        try {
            // "Pixel Test" twice: this check is about the report frame, and passing the same
            // string for the model and the phone's own name keeps the name fallback out of it.
            frame = Native.serveWatched(inbox.toString(), "", "Pixel Test", "Pixel Test", in,
                    answers, summary -> true,
                    (done, total) -> seen.add(new long[] {done, total}));
        } finally {
            in.close();
        }

        Report report = Report.of(frame);
        check("a watched connection stores what it was sent", report.names().equals(expected),
                "expected " + expected + ", got " + report.names());
        check("a watcher on a transfer that landed bytes is told something", !seen.isEmpty(),
                "the callback was never made");

        boolean backwards = false;
        long total = -1;
        boolean totalDrifted = false;
        boolean totalTooSmall = false;
        for (int step = 0; step < seen.size(); step++) {
            if (step > 0 && seen.get(step)[0] < seen.get(step - 1)[0]) {
                backwards = true;
            }
            if (seen.get(step)[1] <= 0) {
                continue;
            }
            if (total == -1) {
                total = seen.get(step)[1];
            } else if (seen.get(step)[1] != total) {
                totalDrifted = true;
            }
            if (seen.get(step)[0] > seen.get(step)[1]) {
                totalTooSmall = true;
            }
        }
        long last = seen.isEmpty() ? -1 : seen.get(seen.size() - 1)[0];
        check("a watcher is never told the transfer went backwards", !backwards, describe(seen));
        check("a watcher's last number is what actually landed", last == report.bytes(),
                "last " + last + ", landed " + report.bytes());
        check("the fallback denominator is the head's content length and never drifts",
                !totalDrifted && total == body.length, describe(seen));
        check("payload never claims more than the promised body", !totalTooSmall, describe(seen));
    }

    /** A watcher's log as one line, for a failure message: {@code done/total}, in order. */
    private static String describe(List<long[]> seen) {
        StringBuilder line = new StringBuilder();
        for (long[] step : seen) {
            line.append(line.length() == 0 ? "" : ", ").append(step[0]).append('/').append(step[1]);
        }
        return line.toString();
    }

    private static void aStreamThatGivesUpKeepsItsOwnException(Path archive, Path inbox)
            throws Exception {
        int cut = (int) (Files.size(archive) / 2);
        String marker = "the test's own diagnosis";
        try (InputStream in = new FailingStream(Files.readAllBytes(archive), cut, marker)) {
            Native.receiveArchive(inbox.toString(), null, in);
            check("a stream that throws fails the call", false, "no exception was thrown");
        } catch (IOException error) {
            check("the stream's own exception reaches the caller",
                    marker.equals(error.getMessage()),
                    "message was " + error.getMessage());
        }
    }

    /**
     * A peer that vanishes mid-request must cost one connection, not the process.
     *
     * <p>The endpoint answers before it reads a body, so a request that promises a body and
     * then loses its peer is answered {@code 200} <em>and</em> fails: the answer has already
     * left, the body never arrived, and the reader is holding the exception that says why.
     * Both are true at once, and the call still ends with an empty report — which is the state
     * that used to be unrepresentable across the JNI boundary. {@code NewByteArray} was called
     * with that {@link SocketException} still pending, and CheckJNI aborts the whole process on
     * any JNI call made in that state, so this conversation killed a phone rather than ending a
     * connection. Measured there, in {@code adb logcat -b crash}:
     *
     * <pre>
     * Abort message: 'JNI DETECTED ERROR IN APPLICATION: JNI NewByteArray called with
     *     pending exception java.net.SocketException: Connection reset in call to NewByteArray'
     * </pre>
     *
     * <p>None of this is Android-specific — a desktop JVM refuses the same call the same way,
     * by aborting — which is why the case belongs here as well as in a probe on a device. What
     * is asserted is the shape a receiver needs: the peer is answered, nothing is stored, the
     * caller gets the socket's own exception rather than a substitute, and the checks after
     * this one still get to run.
     */
    private static void aVanishingPeerIsOneConnectionAndNotTheProcess(Path scratch)
            throws Exception {
        Path inbox = Files.createDirectories(scratch.resolve("vanishing"));
        // `/Discover` and not `/Upload`: the answer does not depend on the body, so the whole
        // sequence — answer, then fail on the body — happens without a decision being asked.
        String head = "POST /Discover HTTP/1.1\r\n"
                + "Host: air\r\n"
                + "Content-Type: application/x-apple-binary-plist\r\n"
                + "Content-Length: 200\r\n\r\n";
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
        requests.write(head.getBytes(StandardCharsets.US_ASCII));
        requests.write(new byte[12], 0, 12);

        String marker = "Connection reset";
        ByteArrayOutputStream answers = new ByteArrayOutputStream();
        Throwable stopped = null;
        byte[] frame = null;
        try (InputStream in = new VanishingStream(requests.toByteArray(), marker)) {
            frame = Native.serveDecided(inbox.toString(), "", "Pixel Test", in, answers,
                    summary -> true);
        } catch (Throwable error) {
            stopped = error;
        }

        String answer = new String(answers.toByteArray(), StandardCharsets.ISO_8859_1);
        check("a peer is answered before its body fails",
                answer.startsWith("HTTP/1.1 200"),
                answer.lines().findFirst().orElse("nothing was written"));
        check("the socket's own exception reaches the caller",
                stopped instanceof SocketException && marker.equals(stopped.getMessage()),
                String.valueOf(stopped));
        check("an abandoned control body stores nothing",
                frame == null || Report.of(frame).names().isEmpty(),
                "report named " + (frame == null ? "<none>" : Report.of(frame).names().toString()));
        check("an abandoned control body leaves no temporary file behind",
                listTemporaries(inbox).isEmpty(), "found " + listTemporaries(inbox));
    }

    private static void aMissingStreamIsRefusedRatherThanDereferenced(Path inbox) {
        try {
            Native.receiveArchive(inbox.toString(), null, null);
            check("a null stream is refused", false, "no exception was thrown");
        } catch (NullPointerException error) {
            check("a null stream is refused with NullPointerException", true, "");
        } catch (Throwable error) {
            check("a null stream is refused with NullPointerException", false, error.toString());
        }
    }

    /**
     * A setting is the only reason the receive path can be told to do something different, so
     * a value the table forbids has to be refused here — before a single archive byte is
     * consumed, because a receiver that starts writing and then objects has already written.
     */
    private static void aSettingOutsideItsRangeIsRefusedBeforeAByteIsRead(Path archive, Path scratch)
            throws Exception {
        Path nowhere = scratch.resolve("never-created");
        try (InputStream in = new FileInputStream(archive.toFile())) {
            Native.receiveArchive(nowhere.toString(), "receive.byte_limit=1\n", in);
            check("a value outside its range is refused", false, "no exception was thrown");
        } catch (IllegalArgumentException error) {
            check("a value outside its range is refused", error.getMessage().contains("between"),
                    "message was " + error.getMessage());
            check("the refusal names the range so it can be corrected",
                    error.getMessage().contains("receive.byte_limit"), error.getMessage());
        }
        check("a refused setting does not create the inbox", !Files.exists(nowhere),
                nowhere + " was created anyway");
    }

    /**
     * {@code receive.overwrite} is the one rule a person can turn off. Turning it on must
     * replace the file under the sender's name, not make a sibling next to it.
     */
    private static void overwritingReplacesRatherThanNumbering(Path archive, Path scratch)
            throws Exception {
        Path inbox = Files.createDirectories(scratch.resolve("overwriting"));
        Path note = inbox.resolve("hello.txt");
        Files.write(note, "older".getBytes(StandardCharsets.UTF_8));

        byte[] frame;
        try (InputStream in = new FileInputStream(archive.toFile())) {
            frame = Native.receiveArchive(inbox.toString(), "receive.overwrite=true\n", in);
        }

        List<String> landed = Report.of(frame).names();
        check("overwriting leaves the sender's name alone", landed.contains("hello.txt"),
                "report was " + landed);
        check("no sibling is made when overwriting",
                !Files.exists(inbox.resolve("hello (1).txt")), "a numbered copy was made");
        check("the member replaced what was there",
                new String(Files.readAllBytes(note), StandardCharsets.UTF_8).equals("hello air drop\n"),
                "hello.txt holds " + new String(Files.readAllBytes(note), StandardCharsets.UTF_8));
    }

    private static List<String> listTemporaries(Path directory) throws IOException {
        List<String> found = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try (var paths = Files.walk(directory)) {
            paths.forEach(path -> {
                if (path.getFileName().toString().endsWith(".opairdrop-part")) {
                    found.add(path.toString());
                }
            });
        }
        return found;
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  ok    " + what);
        } else {
            failures++;
            System.out.println("  FAIL  " + what + (detail.isEmpty() ? "" : ": " + detail));
        }
    }

    /** Answers {@code cut} bytes and then throws, the way a socket that times out does. */
    private static final class FailingStream extends InputStream {
        private final byte[] bytes;
        private final int cut;
        private final String message;
        private int at;

        FailingStream(byte[] bytes, int cut, String message) {
            this.bytes = bytes;
            this.cut = cut;
            this.message = message;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int got = read(one, 0, 1);
            return got < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (at >= cut) {
                throw new IOException(message);
            }
            int count = Math.min(length, cut - at);
            System.arraycopy(bytes, at, buffer, offset, count);
            at += count;
            return count;
        }
    }

    /**
     * Delivers its bytes and then fails the way a reset socket does, every time it is read.
     *
     * <p>The type is the point. {@link SocketException} is what a peer that goes away arrives
     * as, and it is the type the receiving connection treats as routine rather than
     * reportable, so a test that threw a plain {@link IOException} would walk the same JNI
     * path and prove less about the decision at the end of it.
     *
     * <p>It keeps throwing rather than answering end-of-stream once spent, because that is
     * what a dead socket does: the endpoint drains an abandoned body, and the second read is
     * the one that found the pending-exception bug.
     */
    private static final class VanishingStream extends InputStream {
        private final byte[] bytes;
        private final String message;
        private int at;

        VanishingStream(byte[] bytes, String message) {
            this.bytes = bytes;
            this.message = message;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int got = read(one, 0, 1);
            return got < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (at >= bytes.length) {
                throw new SocketException(message);
            }
            int count = Math.min(length, bytes.length - at);
            System.arraycopy(bytes, at, buffer, offset, count);
            at += count;
            return count;
        }
    }
}
