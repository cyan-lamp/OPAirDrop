package com.oshare.airdrop;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The bring-up receipt, as the app reads it.
 *
 * <p># What it is
 *
 * <p>{@code awdl-stack.sh boot} writes one line to {@code files/stack-state} on every exit,
 * including the ones that only produce a protocol number, and that line is the only thing the
 * app is allowed to show about the link. Three shapes:
 *
 * <pre>
 *   stack up boot=a1b2c3d4 iface=wonder0 freq=5745 ll=fe80::... wifi=0 daemon=28854 tun=up
 *   stack partial boot=a1b2c3d4 iface=wonder0 freq=5745 ll=fe80::... wifi=1 daemon=none tun=absent
 *   stack failed boot=a1b2c3d4 rc=16 stage=driver-swap detail=wonder.ko loaded but registered no wiphy
 * </pre>
 *
 * <p># Why the receipt names a boot
 *
 * <p>Because it outlives what it describes. {@code files/stack-state} is an ordinary file in
 * the app's data directory; nothing deletes it at shutdown. After a reboot it still says
 * {@code stack up ... daemon=28854}, and every word of that is about a phone state that no
 * longer exists — no modules, no {@code wonder0}, and a pid that belongs to nothing. Showing
 * it unchanged is the worst failure this screen can have: it says the opposite of the truth at
 * exactly the moment the person is looking at the phone wondering why nothing can see it.
 *
 * <p>{@code awdl-stack.sh} had already solved this for itself ({@code same_boot
 * "$STATE/stack-boot"}); the receipt simply did not carry the answer. Now it does, and the
 * comparison happens here, against {@code /proc/sys/kernel/random/boot_id} — world-readable,
 * so no {@code su} and no extra process to ask.
 *
 * <p>Neither side naming a boot is not a mismatch. This class is also compiled against a
 * desktop JDK for {@link StackCheck}, where {@code /proc} holds no such file; "I cannot tell
 * which boot this is" must not turn every receipt in the checks into a stale one. Nor is
 * {@code boot=unknown}, which is the script saying the same thing about itself.
 *
 * <p># Why it is parsed and not printed
 *
 * <p>Printing the line is tempting and wrong: it is English key=value written for a log, and
 * the two questions a person has — "is it working" and "if not, which step" — are both
 * answerable from it but neither is spelled in it. So the fields are pulled out and the
 * sentence is written here, in one place, where a check can drive every shape the script can
 * produce. The receipt itself still goes to the log verbatim; this class never rewrites it.
 *
 * <p># Why a failed receipt is not the same as no receipt
 *
 * <p>No file means the script never got that far — it was not run, or it was killed, or the
 * copy failed. A failure means it ran and stopped at a named step. Collapsing the two into
 * "something went wrong" throws away the only part that is actionable: {@code stage} and
 * {@code rc} say whether the phone lost its Wi-Fi driver ({@code driver-swap}) or never got to
 * the swap at all ({@code wifi}, {@code precondition}).
 *
 * <p>No Android type appears here on purpose: the JVM check compiles this class against a
 * desktop JDK and drives it with a scratch directory.
 */
public final class StackState {
    /** Where {@code boot_receipt} in {@code tools/awdl-stack.sh} publishes the line. */
    public static final String RECEIPT = "stack-state";

    /** The interface the tap creates. Spelled once; {@code awdl-stack.sh} uses the same word. */
    public static final String IFACE = "wonder0";

    /**
     * Where the kernel keeps this boot's id.
     *
     * <p>Mode 0444, so the app can read it without {@code su}. It is read on every {@link
     * #read} rather than cached: it is one small file, and a stale cache of "which boot is
     * this" would be the same mistake this class exists to prevent.
     */
    private static final String BOOT_ID = "/proc/sys/kernel/random/boot_id";

    /** Where the stack stands, as the receipt's second word says. */
    public enum Standing {
        /** Link up, daemon running, tunnel open — everything the receipt can promise. */
        UP,
        /** The link is up but something after it is not; {@link State#missing()} says what. */
        PARTIAL,
        /** The script stopped at a named step. {@link State#stage()} and {@link State#rc()}. */
        FAILED,
        /** A line this build does not recognise — an older or newer script. */
        UNKNOWN
    }

    private StackState() {}

    /** One receipt line, split into the things the page asks of it. */
    public static final class State {
        private final String line;
        private final Standing standing;
        private final String fields;
        private final String detail;
        private final boolean stale;

        private State(String line, Standing standing, String fields, String detail,
                String currentBoot) {
            this.line = line;
            this.standing = standing;
            this.fields = fields;
            this.detail = detail;
            // A mismatch is only claimed when both sides really name a boot. Three shapes
            // deliberately do not count as evidence of an older boot: this machine not naming
            // its own id (the JVM check has no /proc), and `boot=unknown`, which is the script
            // saying it could not read its id — a run that just succeeded must not be reported
            // as stale because of that. A receipt with no `boot=` field at all is the one shape
            // that *is* stale: it predates the field, so it cannot be about this boot.
            String receiptBoot = field("boot");
            this.stale = !currentBoot.isEmpty()
                    && !"unknown".equals(receiptBoot)
                    && !currentBoot.equalsIgnoreCase(receiptBoot);
        }

        /** The whole line, for the log. Empty when nothing was written. */
        public String line() {
            return line;
        }

        /** Whether anything was written at all. */
        public boolean present() {
            return !line.isEmpty();
        }

        /** Where the stack stands. */
        public Standing standing() {
            return standing;
        }

        /**
         * Whether the receipt promises everything it can, about this boot: link, daemon, tunnel.
         *
         * <p>Staleness is folded in on purpose. Every caller that asks this question is really
         * asking "is AWDL up now", and a receipt from the previous boot can only answer it by
         * lying: the modules it names were unloaded by the reboot.
         */
        public boolean up() {
            return standing == Standing.UP && !stale;
        }

        /**
         * Whether the receipt is about a boot that has ended.
         *
         * <p>True only when both sides name a boot and the names differ. A phone that is off the
         * stack after a reboot still holds the previous line, and every field in it — the pid
         * above all — describes something that no longer exists.
         */
        public boolean stale() {
            return stale;
        }

        /** The boot the receipt was written on, or empty when it does not say. */
        public String boot() {
            return field("boot");
        }

        /** {@code wonder0} or {@code absent}. */
        public String iface() {
            return field("iface");
        }

        /** Whether the interface exists, without asking whether it carries anything. */
        public boolean ifacePresent() {
            return IFACE.equals(iface());
        }

        /** The link's frequency in MHz, or 0 when the receipt does not name one. */
        public int freqMhz() {
            return number(field("freq"));
        }

        /** The link-local address the daemon advertises, or empty. */
        public String linkLocal() {
            return field("ll");
        }

        /** The daemon's pid, or 0 when the receipt says it did not start. */
        public int daemonPid() {
            return number(field("daemon"));
        }

        /** Whether the daemon has its tunnel ({@code tun=up}). */
        public boolean tunnelUp() {
            return "up".equals(field("tun"));
        }

        /**
         * Whether Wi-Fi is on, or null when the receipt could not read it.
         *
         * <p>Three answers, not two: {@code wifi=unknown} is the script saying it could not
         * tell, and folding that into "off" would let the page claim the phone is offline
         * while it is not.
         */
        public Boolean wifiOn() {
            String word = field("wifi");
            if ("1".equals(word)) {
                return Boolean.TRUE;
            }
            if ("0".equals(word)) {
                return Boolean.FALSE;
            }
            return null;
        }

        /** The step a failure stopped at: {@code wifi}, {@code precondition}, {@code driver-swap}. */
        public String stage() {
            return field("stage");
        }

        /** The script's exit code for a failure, or 0. */
        public int rc() {
            return number(field("rc"));
        }

        /** The script's own reason for a failure, in its words. */
        public String detail() {
            return detail;
        }

        /** What is missing after a partial success, as one of {@code link}, {@code daemon}, {@code tunnel}. */
        public String missing() {
            if (!ifacePresent()) {
                return "link";
            }
            if (daemonPid() == 0) {
                return "daemon";
            }
            if (!tunnelUp()) {
                return "tunnel";
            }
            return "";
        }

        /**
         * The state in one sentence, for the line under the switch.
         *
         * <p>Every sentence is either a quote of the receipt or a statement about the receipt —
         * never a guess about the radio. That is why "AWDL 已就绪" is not said when the receipt
         * only says {@code stack partial}: something is missing, and the page names it.
         */
        public String subtitle() {
            if (!present()) {
                return "还没有回执：驱动没有装过，或者脚本没有跑到写回执这一步";
            }
            if (stale) {
                // Two sentences, because "it worked last time" and "it failed last time" are
                // different news about this boot — and the same news about the next step.
                return standing == Standing.UP
                        ? "上次开机装好过，这次开机还没装"
                        : "回执是上次开机写的，这次开机还没装过";
            }
            switch (standing) {
                case UP:
                    return "已就绪：AWDL 在 " + iface() + " 上以 " + freqMhz() + " MHz 建链，守护进程与隧道都在";
                case PARTIAL:
                    return "只成功了一半：" + missingInWords() + "（回执：" + line + "）";
                case FAILED:
                    return "没装成：" + stageInWords() + "（rc=" + rc() + "）";
                default:
                    return "回执看不懂：「" + line + "」";
            }
        }

        /** Whether the row should be drawn as a failure. */
        public boolean failed() {
            return standing == Standing.FAILED;
        }

        /**
         * The step a failure stopped at, in a person's words, with the code explained where the
         * code is specific.
         *
         * <p>The codes are {@code load_stack}'s, and the two that recur have causes worth naming
         * rather than numbering: {@code 16} is the load that succeeds and registers nothing
         * (usually -12 for memory when the HAL is already down, or -12 for the HAL when it is
         * not), and {@code 10} is the one that leaves the phone without Wi-Fi if forced.
         */
        public String stageInWords() {
            String stage = stage();
            if (stage.isEmpty()) {
                return "原因没有写";
            }
            switch (stage) {
                case "wifi":
                    return "Wi-Fi 这一步没过" + (detail().isEmpty() ? "" : "：" + detailInWords());
                case "precondition":
                    return "这个 boot 里还有我们的模块在跑，但没有本轮标记，为安全停手";
                case "driver-swap":
                    return "换驱动失败" + codeInWords();
                default:
                    return "停在 " + stage + " 这一步" + codeInWords();
            }
        }

        private String missingInWords() {
            switch (missing()) {
                case "link":
                    return IFACE + " 不存在，链路没建起来";
                case "daemon":
                    return "链路起来了，但守护进程没起";
                case "tunnel":
                    return "守护进程起了，但没有隧道";
                default:
                    return "回执自称成功却缺东西";
            }
        }

        private String codeInWords() {
            switch (rc()) {
                case 10:
                    return "（rc=10：卸不掉原厂 qca 驱动）";
                case 11:
                    return "（rc=11：装不上 opairshare_wonder_provider.ko）";
                case 12:
                    return "（rc=12：装不上打过补丁的 qca_cld3_peach_v2.ko）";
                case 13:
                    return "（rc=13：卸不掉原厂 mac80211）";
                case 14:
                    return "（rc=14：装不上打过补丁的 mac80211.ko）";
                case 15:
                    return "（rc=15：insmod wonder.ko 失败）";
                case 16:
                    return "（rc=16：wonder.ko 装上了但没注册出 wiphy，多半是 -12：HAL 没停干净或内存不够）";
                default:
                    return "（rc=" + rc() + "）";
            }
        }

        private String detailInWords() {
            return detail().isEmpty() ? "没说原因" : detail();
        }

        /** The value of {@code key=} in the line, or empty. */
        private String field(String key) {
            String needle = key + "=";
            // Leading space so the first pair matches the same pattern as the rest; `at` then
            // indexes the space before the key, and everything is cut from the padded string.
            String padded = " " + fields;
            int at = padded.indexOf(" " + needle);
            if (at < 0) {
                return "";
            }
            int start = at + 1 + needle.length();
            int end = start;
            while (end < padded.length() && !Character.isWhitespace(padded.charAt(end))) {
                end++;
            }
            return padded.substring(start, end);
        }
    }

    /** Reads the receipt. A missing file is not an error: it is "the script never ran". */
    public static State read(File dir) {
        return read(dir, currentBoot());
    }

    /** Same, with the boot named by the caller — the checks drive both outcomes on a
     *  machine that may or may not have {@code /proc} (评审 P1-23 的 CI 教训). */
    public static State read(File dir, String currentBoot) {
        return parse(firstLine(new File(dir, RECEIPT)), currentBoot);
    }

    /** Reads a receipt from text, for a caller that already has it. */
    public static State parse(String text) {
        return parse(text, currentBoot());
    }

    /**
     * Reads a receipt as if the phone had been on {@code currentBoot} since it was written.
     *
     * <p>The explicit boot is what lets {@link StackCheck} drive both outcomes on a desktop,
     * where {@link #currentBoot()} answers "".
     */
    public static State parse(String text, String currentBoot) {
        String line = text == null ? "" : text.trim();
        if (line.isEmpty()) {
            return new State("", Standing.UNKNOWN, "", "", currentBoot);
        }
        // `detail=` is the only field whose value contains spaces and it is always last, so the
        // line is cut there first rather than relying on the split to stop somewhere sensible.
        String head = line;
        String detail = "";
        int at = line.indexOf(" detail=");
        if (at >= 0) {
            detail = line.substring(at + " detail=".length()).trim();
            head = line.substring(0, at);
        }
        String[] words = head.split("\\s+");
        Standing standing = Standing.UNKNOWN;
        if (words.length >= 2 && "stack".equals(words[0])) {
            switch (words[1]) {
                case "up":
                    standing = Standing.UP;
                    break;
                case "partial":
                    standing = Standing.PARTIAL;
                    break;
                case "failed":
                    standing = Standing.FAILED;
                    break;
                default:
                    break;
            }
        }
        // Everything after "stack <word>" is `k=v` pairs; kept as one string for `field()`.
        String fields = head;
        int firstSpace = head.indexOf(' ');
        if (firstSpace >= 0) {
            int second = head.indexOf(' ', firstSpace + 1);
            fields = second < 0 ? "" : head.substring(second + 1);
        }
        return new State(line, standing, fields, detail, currentBoot);
    }

    /**
     * This boot's short id, or "" when this machine does not name one.
     *
     * <p>Cut at the first dash to match {@code boot_short()} in {@code awdl-stack.sh}, which
     * does the same with {@code cut -d- -f1}; the two have to agree on the spelling or every
     * receipt would read as stale.
     */
    public static String currentBoot() {
        String id = firstLine(new File(BOOT_ID));
        int dash = id.indexOf('-');
        return dash < 0 ? id : id.substring(0, dash);
    }

    private static int number(String word) {
        if (word == null || word.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(word);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** The first line of {@code file}, trimmed; empty for a missing or unreadable file. */
    private static String firstLine(File file) {
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException absent) {
            return "";
        }
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.trim();
    }
}
