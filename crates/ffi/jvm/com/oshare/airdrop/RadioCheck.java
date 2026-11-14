package com.oshare.airdrop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The radio switch, driven against a scratch directory.
 *
 * <p>The switch itself is two files and a sentence, and every way it can be wrong is a
 * sentence that lies about the radio: claiming a share that was never confirmed, showing an
 * old receipt as the answer to a new request, printing an English reason at a person, calling
 * a failure "共享中". None of those need an emulator to catch — they need the four receipt
 * shapes the daemon can write, a request file that changed under the reader, and a comparison.
 *
 * <p>Run: {@code java com.oshare.airdrop.RadioCheck <scratch directory>}
 */
public final class RadioCheck {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: RadioCheck <scratch directory>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("cannot use " + dir);
            System.exit(2);
        }

        nothingAskedYet(dir);
        askingPutsTheWordInTheFile(dir);
        everyReceiptShapeReachesThePage(dir);
        anOlderReceiptIsNotAnAnswer(dir);
        aFailureIsNotDrawnAsSuccess(dir);
        askingTwiceLeavesOneFile(dir);
        aFileWithPaddingStillParses(dir);

        System.out.println();
        if (!failures.isEmpty()) {
            System.out.println("FAILED " + failures.size() + " of " + checks);
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println("ok, " + checks + " checks");
    }

    /** A directory nobody has touched: exclusive, and the page says so in the person's words. */
    private static void nothingAskedYet(File dir) throws Exception {
        clear(dir);
        RadioShare.State state = RadioShare.read(dir);
        check("no request file means exclusive", state.want() == RadioShare.Want.EXCLUSIVE,
                state.want().toString());
        check("no request file is not the same as an answered request", !state.asked(),
                "asked=" + state.asked());
        check("no receipt is not an answer", !state.answered(), "answered");
        check("the default state says the phone has no Wi-Fi",
                state.subtitle().contains("独占") && state.subtitle().contains("Wi-Fi"),
                state.subtitle());
        check("the default state is not drawn as a failure", !state.failed(), "failed");
        check("the default state names no channel", state.staMhz() == 0, "" + state.staMhz());
    }

    /** Asking is one line, no leftover, and it survives being read back. */
    private static void askingPutsTheWordInTheFile(File dir) throws Exception {
        clear(dir);
        RadioShare.ask(dir, RadioShare.Want.SHARE);
        File request = new File(dir, RadioShare.REQUEST);
        check("the request file exists after asking", request.isFile(), request.getPath());
        check("the request file holds the word the daemon parses",
                "share".equals(text(request).trim()), quote(text(request)));
        check("no sibling is left behind", !new File(dir, RadioShare.REQUEST + ".new").exists(),
                "a .new file was left in the directory");

        RadioShare.State state = RadioShare.read(dir);
        check("the request is read back", state.want() == RadioShare.Want.SHARE,
                state.want().toString());
        check("a request with no answer yet is not an answer", !state.answered(), "answered");
        check("a waiting request says it is waiting",
                state.subtitle().contains("已请求") && state.subtitle().contains("回执"),
                state.subtitle());

        RadioShare.ask(dir, RadioShare.Want.EXCLUSIVE);
        check("asking again replaces the word",
                "exclusive".equals(text(request).trim()), quote(text(request)));
        check("still one file", dir.list().length == 1, describe(dir));
    }

    /** Every shape the daemon writes, read back as what it means. */
    private static void everyReceiptShapeReachesThePage(File dir) throws Exception {
        RadioShare.ask(dir, RadioShare.Want.SHARE);

        receipt(dir, "share pending");
        RadioShare.State pending = RadioShare.read(dir);
        check("pending is an answer to the request on disk", pending.answered(), "not answered");
        check("pending says it is pending",
                pending.receipt().standing() == RadioShare.Standing.PENDING,
                pending.receipt().standing().toString());
        check("pending is not drawn as settled", !pending.failed(), "failed");
        // ...but it is not the end of the conversation either. The page waits for this one to
        // become false: a waiting receipt that counted as finished is a page that stops asking
        // and freezes on "正在开 Wi-Fi" while the radio has already moved on.
        check("pending is answered but not settled", !pending.settled(), "settled");

        receipt(dir, "share confirmed sta=5745");
        RadioShare.State shared = RadioShare.read(dir);
        check("a confirmed share is settled",
                shared.receipt().standing() == RadioShare.Standing.CONFIRMED,
                shared.receipt().standing().toString());
        check("and that is a settled answer, so the page stops asking", shared.settled(),
                "not settled");
        check("the channel is lifted out of the receipt", shared.staMhz() == 5745,
                "" + shared.staMhz());
        check("the channel reaches the sentence", shared.subtitle().contains("5745"),
                shared.subtitle());
        check("the sentence claims sharing and not a guarantee",
                shared.subtitle().contains("共享")
                        && !shared.subtitle().contains("%")
                        && !shared.subtitle().contains("丢包率"),
                shared.subtitle());

        receipt(dir, "share confirmed");
        check("a confirmation without a channel still reads as a share",
                RadioShare.read(dir).subtitle().contains("共享"),
                RadioShare.read(dir).subtitle());

        receipt(dir, "exclusive confirmed");
        RadioShare.State off = RadioShare.read(dir);
        check("a receipt about the other word is not an answer", !off.answered(),
                "exclusive confirmed answered a share request");
        check("a stale receipt is quoted rather than used",
                off.subtitle().contains("回执还停在"), off.subtitle());
        check("a stale receipt's confirmation is not reported as a state",
                !off.subtitle().contains("没有 Wi-Fi"), off.subtitle());
    }

    /** The receipt the page shows must be about the request standing now. */
    private static void anOlderReceiptIsNotAnAnswer(File dir) throws Exception {
        clear(dir);
        receipt(dir, "share confirmed sta=5220");
        RadioShare.ask(dir, RadioShare.Want.EXCLUSIVE);
        RadioShare.State asked = RadioShare.read(dir);
        check("a receipt for the other word does not answer this one", !asked.answered(),
                "the old share receipt was taken as an answer to exclusive");
        check("a stale receipt's channel is not handed out", asked.staMhz() == 0,
                "" + asked.staMhz());
        check("but the stale line is still quoted, because it is what happened last",
                asked.subtitle().contains("share confirmed sta=5220"), asked.subtitle());

        receipt(dir, "exclusive confirmed");
        RadioShare.State done = RadioShare.read(dir);
        check("the new receipt answers it", done.answered(), "not answered");
        check("exclusive says the phone has no Wi-Fi",
                done.subtitle().contains("没有 Wi-Fi"), done.subtitle());
        check("exclusive names no channel", done.staMhz() == 0, "" + done.staMhz());
    }

    /** A failure keeps the switch where the user put it, and says so in words. */
    private static void aFailureIsNotDrawnAsSuccess(File dir) throws Exception {
        clear(dir);
        RadioShare.ask(dir, RadioShare.Want.SHARE);

        receipt(dir, "share failed wi-fi on, no association");
        RadioShare.State noAssociation = RadioShare.read(dir);
        check("the request is unchanged by a failure",
                noAssociation.want() == RadioShare.Want.SHARE, noAssociation.want().toString());
        check("a failure is drawn as one", noAssociation.failed(), "not failed");
        // A failure is the daemon's last word on this request, so the page must stop asking
        // too — otherwise it sits there polling for twenty seconds a radio that already gave up.
        check("a failure is settled", noAssociation.settled(), "not settled");
        check("the reason is in the person's words",
                noAssociation.subtitle().contains("关联"), noAssociation.subtitle());
        check("the reason is not the daemon's English",
                !noAssociation.subtitle().contains("association"), noAssociation.subtitle());

        receipt(dir, "share failed wi-fi did not turn on");
        check("a second reason is translated too",
                RadioShare.read(dir).subtitle().contains("没有打开"),
                RadioShare.read(dir).subtitle());

        receipt(dir, "exclusive failed wi-fi still on");
        RadioShare.ask(dir, RadioShare.Want.EXCLUSIVE);
        check("a failure to turn it off is worded as exclusive",
                RadioShare.read(dir).subtitle().startsWith("独占失败"),
                RadioShare.read(dir).subtitle());

        receipt(dir, "share failed something this build never heard of");
        RadioShare.ask(dir, RadioShare.Want.SHARE);
        check("an unknown reason is passed through rather than swallowed",
                RadioShare.read(dir).subtitle().contains("never heard of"),
                RadioShare.read(dir).subtitle());

        receipt(dir, "garbage from a newer daemon");
        RadioShare.State unknown = RadioShare.read(dir);
        check("a line with no word is not an answer", !unknown.answered(), "answered");
        check("an unreadable receipt is not a failure of the request", !unknown.failed(), "failed");
    }

    /** Asking the same thing twice is one file with one word in it. */
    private static void askingTwiceLeavesOneFile(File dir) throws Exception {
        clear(dir);
        RadioShare.ask(dir, RadioShare.Want.SHARE);
        RadioShare.ask(dir, RadioShare.Want.SHARE);
        check("the same request twice leaves one file", dir.list().length == 1, describe(dir));
        check("and the same word", "share".equals(text(new File(dir, RadioShare.REQUEST)).trim()),
                quote(text(new File(dir, RadioShare.REQUEST))));
    }

    /** What the daemon and the script accept: a word, a newline, and whatever padding. */
    private static void aFileWithPaddingStillParses(File dir) throws Exception {
        clear(dir);
        write(new File(dir, RadioShare.REQUEST), "  share  \n\n");
        check("whitespace around the word is not a different word",
                RadioShare.read(dir).want() == RadioShare.Want.SHARE,
                RadioShare.read(dir).want().toString());

        write(new File(dir, RadioShare.REQUEST), "");
        RadioShare.State empty = RadioShare.read(dir);
        check("an empty request file reads as the default, not as a parse error",
                empty.want() == RadioShare.Want.EXCLUSIVE, empty.want().toString());
        check("an empty request file is not 'nobody asked'", empty.asked(), "asked=false");
    }

    // ------------------------------------------------------------------ helpers

    private static void clear(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.delete()) {
                throw new IOException("cannot clear " + file);
            }
        }
    }

    private static void receipt(File dir, String line) throws IOException {
        write(new File(dir, RadioShare.RECEIPT), line + "\n");
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String text(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String describe(File dir) {
        String[] names = dir.list();
        return names == null ? "(unreadable)" : String.join(", ", names);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\n", "\\n") + "\"";
    }

    private static void check(String what, boolean held, String detail) {
        checks++;
        if (held) {
            System.out.println("  ok  " + what);
            return;
        }
        System.out.println("  NO  " + what + (detail.isEmpty() ? "" : "  -- " + detail));
        failures.add(what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }
}
