package com.oshare.airdrop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The bring-up receipt, driven against every shape the script can write.
 *
 * <p>All of these are sentences that can be wrong without an emulator: a partial bring-up
 * reported as ready, a failure reported with its code instead of its cause, a missing file
 * reported as a failure, a `detail=` containing spaces swallowing the fields before it. The
 * inputs are strings the script really produced on the phone — copied here verbatim, including
 * the empty slots ({@code ll=none}, {@code daemon=none}, {@code tun=absent}).
 *
 * <p>Run: {@code java com.oshare.airdrop.StackCheck <scratch directory>}
 */
public final class StackCheck {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: StackCheck <scratch directory>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("cannot use " + dir);
            System.exit(2);
        }

        noReceiptIsNotAFailure(dir);
        aFullBringUpReadsAsReady(dir);
        aReceiptFromTheLastBootIsNotReady();
        aReceiptWithoutABootNameIsNotStaleWithoutEvidence();
        aLinkWithoutADaemonIsNotReady();
        anAbsentInterfaceSaysSo();
        aDriverSwapFailureNamesTheCause();
        aWifiFailureIsItsOwnStage();
        anUnknownLineIsNeitherSuccessNorFailure();
        detailWithSpacesIsKeptWhole();
        paddingAndBlankLinesAreTolerated(dir);

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

    /** Nothing written yet: "not run", not "failed", and the page must not claim otherwise. */
    private static void noReceiptIsNotAFailure(File dir) throws IOException {
        clear(dir);
        StackState.State state = StackState.read(dir);
        check("no receipt is not present", !state.present(), state.line());
        check("no receipt is not ready", !state.up(), state.standing().toString());
        check("no receipt is not a failure", !state.failed(), state.standing().toString());
        check("no receipt says the drivers were never installed",
                state.subtitle().contains("还没有回执"), state.subtitle());
    }

    /** The line a cold `boot` ended with on the phone, five runs in a row. */
    private static void aFullBringUpReadsAsReady(File dir) throws IOException {
        write(dir, "stack up iface=wonder0 freq=5745 ll=fe80::80ca:efff:fe94:508b"
                + " wifi=0 daemon=28854 tun=up");
        StackState.State state = StackState.read(dir);
        check("a full line is up", state.up(), state.standing().toString());
        check("the interface is read", "wonder0".equals(state.iface()), state.iface());
        check("the interface is present", state.ifacePresent(), state.iface());
        check("the frequency is read", state.freqMhz() == 5745, "" + state.freqMhz());
        check("the link-local is read", state.linkLocal().startsWith("fe80::"), state.linkLocal());
        check("the daemon pid is read", state.daemonPid() == 28854, "" + state.daemonPid());
        check("the tunnel is read", state.tunnelUp(), state.line());
        check("wifi=0 is off and not unknown", Boolean.FALSE.equals(state.wifiOn()),
                "" + state.wifiOn());
        check("nothing is missing", state.missing().isEmpty(), state.missing());
        check("the sentence names the interface and the channel",
                state.subtitle().contains("wonder0") && state.subtitle().contains("5745"),
                state.subtitle());
        check("a ready stack is not drawn as a failure", !state.failed(), state.subtitle());
    }

    /**
     * The receipt that outlived its boot: the worst sentence this screen can print.
     *
     * <p>Copied from a real cold boot on the phone — after {@code reboot}, with {@code lsmod}
     * showing no {@code wonder} and {@code /sys/class/net} holding no {@code wonder0}, the app
     * still showed the previous run's line and called it ready. Everything in it is a fact about
     * a phone that no longer exists, including the pid.
     */
    private static void aReceiptFromTheLastBootIsNotReady() {
        String lastBoot = "stack up boot=a1b2c3d4 iface=wonder0 freq=5745"
                + " ll=fe80::80ca:efff:fe94:508b wifi=0 daemon=28854 tun=up";

        StackState.State afterReboot = StackState.parse(lastBoot, "9f3c77e1");
        check("a receipt from the last boot is stale", afterReboot.stale(), afterReboot.boot());
        check("a receipt from the last boot is not up, whatever it says",
                !afterReboot.up(), afterReboot.standing().toString());
        check("its own word is not rewritten", afterReboot.standing() == StackState.Standing.UP,
                afterReboot.standing().toString());
        check("it is not drawn as a failure either", !afterReboot.failed(),
                afterReboot.standing().toString());
        check("the sentence says it is from the last boot",
                afterReboot.subtitle().contains("上次开机"), afterReboot.subtitle());
        check("the sentence does not claim ready", !afterReboot.subtitle().contains("已就绪"),
                afterReboot.subtitle());
        check("the boot it belongs to is still readable", "a1b2c3d4".equals(afterReboot.boot()),
                afterReboot.boot());
        check("the line is kept whole for the log", afterReboot.line().equals(lastBoot),
                afterReboot.line());

        StackState.State sameBoot = StackState.parse(lastBoot, "a1b2c3d4");
        check("the same boot is not stale", !sameBoot.stale(), sameBoot.boot());
        check("the same boot reads as ready", sameBoot.up(), sameBoot.subtitle());

        // A failure from the last boot says nothing about this one: it must not be shown as
        // this boot's failure, and it must not be shown as ready either.
        StackState.State staleFailure = StackState.parse(
                "stack failed boot=a1b2c3d4 rc=16 stage=driver-swap detail=no wiphy", "9f3c77e1");
        check("a failure from the last boot is still stale", staleFailure.stale(),
                staleFailure.subtitle());
        check("a stale failure is not up", !staleFailure.up(), staleFailure.subtitle());
        check("a stale failure still names its stage", "driver-swap".equals(staleFailure.stage()),
                staleFailure.stage());
        check("a stale failure's sentence is about the boot, not the drivers",
                staleFailure.subtitle().contains("上次开机"), staleFailure.subtitle());
    }

    /**
     * No boot name on either side: the honest answer is "cannot tell", so nothing is demoted.
     *
     * <p>This is the shape of a receipt written by a script older than {@code boot=}, and it is
     * also every case in this file. If "unknown" were treated as "mismatch", the JVM check would
     * run on a desktop with no {@code /proc/sys/kernel/random/boot_id} and every ready receipt
     * here would silently become stale.
     */
    private static void aReceiptWithoutABootNameIsNotStaleWithoutEvidence() {
        String noBoot = "stack up iface=wonder0 freq=5745 ll=none wifi=0 daemon=7 tun=up";
        StackState.State unknownBoth = StackState.parse(noBoot, "");
        check("no boot anywhere is not stale", !unknownBoth.stale(), unknownBoth.boot());
        check("no boot anywhere still reads as ready", unknownBoth.up(),
                unknownBoth.subtitle());

        // But on a phone that does know its boot, a receipt that does not name one cannot be
        // about this boot: `boot=` is written by the same script on every exit.
        StackState.State oneSided = StackState.parse(noBoot, "9f3c77e1");
        check("a receipt with no boot on a phone that has one is stale", oneSided.stale(),
                oneSided.subtitle());
        check("that receipt is not up", !oneSided.up(), oneSided.subtitle());

        // `boot=unknown` is the script's own "I could not read my id". The run it belongs to
        // may be the one that just finished, so it must not be demoted on that word alone —
        // otherwise a successful install would be reported as stale by its own fallback.
        StackState.State couldNotTell = StackState.parse(
                "stack up boot=unknown iface=wonder0 freq=5745 ll=none wifi=0 daemon=7 tun=up",
                "9f3c77e1");
        check("boot=unknown is not evidence of an older boot", !couldNotTell.stale(),
                couldNotTell.boot());
        check("and it still reads as ready", couldNotTell.up(), couldNotTell.subtitle());
    }

    /** Link up, no daemon: the shape that used to be printed as if it were ready. */
    private static void aLinkWithoutADaemonIsNotReady() {
        StackState.State state = StackState.parse(
                "stack partial iface=wonder0 freq=5745 ll=fe80::80ca:efff:fe94:508b"
                        + " wifi=1 daemon=none tun=absent");
        check("a partial line is not up", !state.up(), state.standing().toString());
        check("a partial line is not a failure either", !state.failed(),
                state.standing().toString());
        check("a missing daemon is named", "daemon".equals(state.missing()), state.missing());
        check("daemon=none reads as no pid", state.daemonPid() == 0, "" + state.daemonPid());
        check("the sentence says the daemon did not start",
                state.subtitle().contains("守护进程"), state.subtitle());
        check("the sentence keeps the receipt for the log", state.subtitle().contains("stack partial"),
                state.subtitle());
    }

    /** No interface at all: the first thing that goes wrong on a cold boot. */
    private static void anAbsentInterfaceSaysSo() {
        StackState.State state = StackState.parse(
                "stack partial iface=absent freq=? ll=none wifi=1 daemon=none tun=absent");
        check("iface=absent is not present", !state.ifacePresent(), state.iface());
        check("an absent interface is what is missing", "link".equals(state.missing()),
                state.missing());
        check("the sentence names the missing interface",
                state.subtitle().contains("链路"), state.subtitle());
        check("a placeholder frequency is not a number", state.freqMhz() == 0,
                "" + state.freqMhz());
    }

    /** The rc=16 this week's debugging was about: named, and explained rather than numbered. */
    private static void aDriverSwapFailureNamesTheCause() {
        StackState.State state = StackState.parse("stack failed rc=16 stage=driver-swap"
                + " detail=wonder.ko loaded but registered no wiphy; see dmesg for wonder_probe");
        check("a failed line is a failure", state.failed(), state.standing().toString());
        check("a failed line is not up", !state.up(), state.standing().toString());
        check("the stage is read", "driver-swap".equals(state.stage()), state.stage());
        check("the code is read", state.rc() == 16, "" + state.rc());
        check("the detail is read whole",
                state.detail().startsWith("wonder.ko loaded") && state.detail().endsWith("wonder_probe"),
                state.detail());
        check("the sentence says the driver swap failed",
                state.subtitle().contains("换驱动失败"), state.subtitle());
        check("the sentence explains 16 instead of only numbering it",
                state.stageInWords().contains("-12"), state.stageInWords());
        check("the sentence carries the code", state.subtitle().contains("16"), state.subtitle());
    }

    /** Wi-Fi would not turn off: the stage before the swap, and a different cure. */
    private static void aWifiFailureIsItsOwnStage() {
        StackState.State wifiOff = StackState.parse("stack failed rc=4 stage=wifi"
                + " detail=Wi-Fi did not disable");
        check("the wifi stage is its own", "wifi".equals(wifiOff.stage()), wifiOff.stage());
        check("the sentence names Wi-Fi", wifiOff.subtitle().contains("Wi-Fi"),
                wifiOff.subtitle());
        check("the reason is quoted", wifiOff.subtitle().contains("did not disable"),
                wifiOff.subtitle());

        StackState.State precondition = StackState.parse(
                "stack failed rc=3 stage=precondition"
                        + " detail=module wonder is live and there is no marker for this boot");
        check("the precondition stage is explained",
                precondition.stageInWords().contains("本轮标记"), precondition.stageInWords());
        check("a precondition is not blamed on the drivers",
                !precondition.stageInWords().contains("驱动"), precondition.stageInWords());
    }

    /** An older or newer script: shown as written, never as a success. */
    private static void anUnknownLineIsNeitherSuccessNorFailure() {
        StackState.State state = StackState.parse("link ready iface=wonder0");
        check("an unknown line is not up", !state.up(), state.standing().toString());
        check("an unknown line is not a failure", !state.failed(), state.standing().toString());
        check("an unknown line is quoted, not summarised",
                state.subtitle().contains("link ready iface=wonder0"), state.subtitle());
    }

    /** `detail=` is the only field with spaces; it must not eat the fields before it. */
    private static void detailWithSpacesIsKeptWhole() {
        StackState.State state = StackState.parse("stack failed rc=2 stage=wifi"
                + " detail=cannot read the Wi-Fi state");
        check("a spaced detail is kept whole",
                "cannot read the Wi-Fi state".equals(state.detail()), state.detail());
        check("a spaced detail does not break the fields before it",
                "wifi".equals(state.stage()) && state.rc() == 2,
                state.stage() + "/" + state.rc());
    }

    /** A trailing newline, leading spaces, and an empty file: the three easy ones to get wrong. */
    private static void paddingAndBlankLinesAreTolerated(File dir) throws IOException {
        StackState.State padded = StackState.parse("  stack up iface=wonder0 freq=5745"
                + " ll=none wifi=unknown daemon=12 tun=up\n");
        check("surrounding whitespace is trimmed", padded.up(), padded.standing().toString());
        check("wifi=unknown is three-valued, not off", padded.wifiOn() == null,
                "" + padded.wifiOn());
        check("ll=none is not an address", "none".equals(padded.linkLocal()), padded.linkLocal());

        write(dir, "");
        StackState.State blank = StackState.read(dir);
        check("an empty file is not present", !blank.present(), blank.line());
        check("an empty file is not a failure", !blank.failed(), blank.standing().toString());

        blank = StackState.parse(null);
        check("null is not present", !blank.present(), blank.line());
    }

    private static void clear(File dir) {
        for (String name : dir.list()) {
            new File(dir, name).delete();
        }
    }

    private static void write(File dir, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, StackState.RECEIPT))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (ok ? "" : "  <- " + detail));
        if (!ok) {
            failures.add(what + "  <- " + detail);
        }
    }
}
