package com.oshare.airdrop.core;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Writing an untrusted archive to disk: names, collisions, links, budget. */
public final class InboxTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("oshare-inbox").toFile();
        try {
            extractWritesMembers(root);
            collisionsGetSuffixed(root);
            duplicateInsideOneArchive(root);
            symlinksDropped(root);
            traversalDropped(root);
            budgetFailureRollsBack(root);
        } finally {
            deleteTree(root);
        }
        System.out.println("InboxTest OK (" + checks + " checks)");
    }

    private static void extractWritesMembers(File root) throws Exception {
        File dir = new File(root, "plain");
        Inbox inbox = new Inbox(dir);
        inbox.extract(stream(Cpio.write(Arrays.asList(
            new Cpio.Entry("./holiday.jpg", 0100644, bytes("jpeg payload")),
            new Cpio.Entry("./sub/", 0040755, new byte[0]),
            new Cpio.Entry("./sub/notes.txt", 0100644, bytes("notes")),
            new Cpio.Entry("./佐藤.jpg", 0100644, bytes("unicode"))))));

        eq(4, inbox.names().size(), "member count including directory");
        eq("holiday.jpg", inbox.names().get(0), "sanitized name");
        check(new File(dir, "holiday.jpg").isFile(), "file written");
        eq("jpeg payload", read(new File(dir, "holiday.jpg")), "content");
        check(new File(dir, "sub").isDirectory(), "directory created");
        eq("notes", read(new File(dir, "sub/notes.txt")), "nested content");
        eq("unicode", read(new File(dir, "佐藤.jpg")), "unicode name on disk");
        eq(12L + 5L + 7L, inbox.bytes(), "byte accounting");
        eq(inbox.names().size(), inbox.files().size(), "files mirror names");
    }

    private static void collisionsGetSuffixed(File root) throws Exception {
        File dir = new File(root, "collide");
        byte[] archive = Cpio.write(Arrays.asList(
            new Cpio.Entry("./report.pdf", 0100644, bytes("first"))));

        Inbox inbox = new Inbox(dir);
        inbox.extract(stream(archive));
        inbox.extract(stream(archive));
        inbox.extract(stream(archive));

        eq("report.pdf", inbox.names().get(0), "original name");
        eq("report (1).pdf", inbox.names().get(1), "first duplicate");
        eq("report (2).pdf", inbox.names().get(2), "second duplicate");
        eq("first", read(new File(dir, "report (2).pdf")), "duplicate content");
    }

    private static void duplicateInsideOneArchive(File root) throws Exception {
        File dir = new File(root, "internal");
        Inbox inbox = new Inbox(dir);
        inbox.extract(stream(Cpio.write(Arrays.asList(
            new Cpio.Entry("./a.txt", 0100644, bytes("one")),
            new Cpio.Entry("./a.txt", 0100644, bytes("two"))))));
        eq("a.txt", inbox.names().get(0), "first member");
        eq("a (1).txt", inbox.names().get(1), "second member renamed");
        eq("one", read(new File(dir, "a.txt")), "first content kept");
        eq("two", read(new File(dir, "a (1).txt")), "second content kept");
    }

    private static void symlinksDropped(File root) throws Exception {
        File dir = new File(root, "links");
        Inbox inbox = new Inbox(dir);
        inbox.extract(stream(Cpio.write(Arrays.asList(
            new Cpio.Entry("./link", 0120777, bytes("/etc/passwd")),
            new Cpio.Entry("./real.txt", 0100644, bytes("real"))))));
        eq(1, inbox.names().size(), "only the regular file survives");
        eq("real.txt", inbox.names().get(0), "kept member");
        check(!new File(dir, "link").exists(), "no symlink on disk");
    }

    private static void traversalDropped(File root) throws Exception {
        File dir = new File(root, "escape");
        check(dir.mkdirs(), "target directory");
        Inbox inbox = new Inbox(dir);
        inbox.extract(stream(Cpio.write(Arrays.asList(
            new Cpio.Entry("../evil.txt", 0100644, bytes("pwned")),
            new Cpio.Entry("/absolute.txt", 0100644, bytes("abs")),
            new Cpio.Entry("./ok.txt", 0100644, bytes("ok"))))));

        eq(2, inbox.names().size(), "traversal member dropped");
        eq("absolute.txt", inbox.names().get(0), "leading slash stripped, still inside");
        check(!new File(root, "evil.txt").exists(), "nothing escaped the inbox");
        check(!new File("/absolute.txt").exists(), "no absolute write");
        eq("ok", read(new File(dir, "ok.txt")), "normal member written");
    }

    private static void budgetFailureRollsBack(File root) throws Exception {
        File dir = new File(root, "budget");
        Inbox inbox = new Inbox(dir, 16);
        byte[] big = new byte[4096];
        boolean failed;
        try {
            inbox.extract(stream(Cpio.write(Arrays.asList(
                new Cpio.Entry("./big.bin", 0100644, big)))));
            failed = false;
        } catch (IOException expected) {
            failed = true;
            check(expected.getMessage().contains("exceeds"), "budget reported");
        }
        check(failed, "oversized member rejected");
        eq(0, inbox.names().size(), "failed member not recorded");
        eq(0L, inbox.bytes(), "nothing counted");
        String[] left = dir.list();
        check(left != null && left.length == 0, "no partial file left behind");
    }

    private static ByteArrayInputStream stream(byte[] archive) {
        return new ByteArrayInputStream(archive);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
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

    private InboxTest() {
    }
}
