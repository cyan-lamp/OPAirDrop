package com.oshare.airdrop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

        check("the ABI version is the one the app was built against", Native.abiVersion() == 2,
                "abiVersion() returned " + Native.abiVersion());

        receiveLandsEveryMember(archive, inbox, expected);
        serveAnswersAPeerAndStoresWhatItSends(archive, scratch, expected);
        aStreamThatGivesUpKeepsItsOwnException(archive, inbox);
        aMissingStreamIsRefusedRatherThanDereferenced(inbox);
        aSettingOutsideItsRangeIsRefusedBeforeAByteIsRead(archive, scratch);
        overwritingReplacesRatherThanNumbering(archive, scratch);

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("all checks passed");
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
    private static void serveAnswersAPeerAndStoresWhatItSends(
            Path archive, Path scratch, List<String> expected) throws Exception {
        Path inbox = Files.createDirectories(scratch.resolve("served"));
        byte[] body = Files.readAllBytes(archive);
        String head = "POST /Upload HTTP/1.1\r\n"
                + "Host: air\r\n"
                + "Content-Type: application/x-cpio\r\n"
                + "Content-Length: " + body.length + "\r\n\r\n";
        ByteArrayOutputStream requests = new ByteArrayOutputStream();
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
}
