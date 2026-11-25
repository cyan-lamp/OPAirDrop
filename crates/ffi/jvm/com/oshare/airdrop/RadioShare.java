package com.oshare.airdrop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The radio switch, as two files in the app's own directory.
 *
 * <p># What this is not
 *
 * <p>It is not a setting. A setting is a value the app owns and stores in
 * {@code settings.conf}; this is a request to another process, and the answer to that request
 * is written back by somebody else. Storing it as a setting would give the same fact two
 * homes that can disagree — and the home the radio actually obeys is {@code files/radio-share},
 * because that is the file {@code awdld} watches and {@code awdl-stack.sh} reads. A switch
 * drawn from {@code settings.conf} would show what the page last wrote, not what the daemon
 * was asked, and the page would be able to say "共享中" while the file said {@code exclusive}.
 *
 * <p># Three readers, one word
 *
 * <p>{@code share} and {@code exclusive} are spelled the same in three places:
 * {@code Want::parse} in {@code crates/awdl/src/bin/awdld.rs}, {@code radio_want()} in
 * {@code tools/awdl-stack.sh}, and {@link Want} here. The receipt's four shapes —
 * {@code share confirmed sta=5745}, {@code exclusive confirmed}, {@code <word> pending},
 * {@code <word> failed <why>} — are written by the daemon and, when there is no daemon, by the
 * script. This class is the third reader and the only writer of the request, so a change to
 * the vocabulary has to be made in all three or the switch will quietly ask for something
 * nobody answers.
 *
 * <p># Why the receipt is shown instead of folded into the switch
 *
 * <p>The switch answers "what did the user ask for" and the receipt answers "what did the
 * radio end up doing", and on this phone those are two different questions: the daemon can
 * report {@code share failed wi-fi on, no association}, and no position of a switch can say
 * that. So the switch shows the request — it does not bounce back on failure, because
 * bouncing would silently rewrite the user's preference into the opposite one and the next
 * {@code start} would act on it — and the line under it shows the receipt, in the daemon's
 * own words, in red when they are a failure.
 *
 * <p># Why the request is renamed into place
 *
 * <p>The daemon polls this file four times a second and acts on whatever it reads. A truncated
 * write is visible in between: the daemon would read an empty file, fail to parse it, and
 * publish {@code share failed unknown request ""} — a receipt describing a race rather than a
 * radio. Writing a sibling and renaming it makes the new contents appear at the name in one
 * step, so every read sees either the old word or the new one.
 *
 * <p>No Android type appears here on purpose: the JVM check compiles this class against a
 * desktop JDK and drives it with a scratch directory, so the parsing and the wording are
 * checked without an emulator.
 */
public final class RadioShare {
    /** The request. Written here, read by the daemon and by the bring-up script. */
    public static final String REQUEST = "radio-share";
    /** The receipt. Written by whoever acted, read here. */
    public static final String RECEIPT = "radio-state";

    private RadioShare() {}

    /** What the user wants the radio to be doing, spelled as the file spells it. */
    public enum Want {
        /** The station keeps the radio and AWDL shares it. Lossy, and by how much depends on
         * the channel the station lands on. */
        SHARE("share"),
        /** The radio belongs to the tap. The phone has no Wi-Fi for the duration. */
        EXCLUSIVE("exclusive");

        private final String word;

        Want(String word) {
            this.word = word;
        }

        /** The spelling used in the request file, in the log and in the receipt. */
        public String word() {
            return word;
        }

        /** The word read back, or null when the text is not one of them. */
        public static Want of(String word) {
            for (Want want : values()) {
                if (want.word.equals(word)) {
                    return want;
                }
            }
            return null;
        }
    }

    /** Where a request stands, as the daemon words it. */
    public enum Standing {
        /** The radio is in the requested state. For {@link Want#SHARE} that means the station
         * has a channel, not merely that Wi-Fi was switched on. */
        CONFIRMED,
        /** Asked for, not yet true. */
        PENDING,
        /** The last attempt did not take effect. {@link Receipt#detail} says why. */
        FAILED,
        /** A line this build does not recognise — an older or newer daemon. */
        UNKNOWN
    }

    /** The last line of the receipt, split into the three things the page asks of it. */
    public static final class Receipt {
        private final String line;
        private final Want word;
        private final Standing standing;
        private final String detail;

        private Receipt(String line, Want word, Standing standing, String detail) {
            this.line = line;
            this.word = word;
            this.standing = standing;
            this.detail = detail;
        }

        /** The whole line, for the page to show verbatim. Empty when nothing was written. */
        public String line() {
            return line;
        }

        /** Which request this line is about, or null when its first word is not one of ours. */
        public Want word() {
            return word;
        }

        /** Where that request stands. Null only when there is no line at all. */
        public Standing standing() {
            return standing;
        }

        /** {@code sta=5745} for a confirmed share, the daemon's reason for a failure. */
        public String detail() {
            return detail;
        }

        /** Whether anything has been written at all. */
        public boolean present() {
            return !line.isEmpty();
        }

        /** Whether this line was written about {@code want} and not about an older request. */
        public boolean answers(Want want) {
            return word == want;
        }
    }

    /** The two files read together: what was asked, and what came back. */
    public static final class State {
        private final Want want;
        private final boolean asked;
        private final Receipt receipt;

        private State(Want want, boolean asked, Receipt receipt) {
            this.want = want;
            this.asked = asked;
            this.receipt = receipt;
        }

        /** The requested state. {@link Want#EXCLUSIVE} when nobody has ever asked. */
        public Want want() {
            return want;
        }

        /** Whether a request file exists, as opposed to the default nobody wrote. */
        public boolean asked() {
            return asked;
        }

        /** The daemon's last word. */
        public Receipt receipt() {
            return receipt;
        }

        /** Whether the receipt describes the request standing now, rather than an older one. */
        public boolean answered() {
            return receipt.answers(want);
        }

        /**
         * Whether the receipt has stopped moving: it answers the request and says it is over.
         *
         * <p>{@link #answered()} is not this. {@code share pending} is a real answer to the
         * request on disk and the page should print it — but it is the daemon saying it is
         * still working, and a page that reads it as the end of the conversation freezes there.
         * That is exactly what happened on the phone: the switch was flipped back to sharing,
         * the daemon wrote {@code share pending} within one poll, the page stopped asking after
         * it, and forty seconds later it still read "正在开 Wi-Fi" while the disk said
         * {@code share confirmed sta=5745} and Wi-Fi was up.
         *
         * <p>So there are two questions here and they have different answers: whether the
         * receipt is <em>about</em> this request ({@code answered}), and whether it is the
         * <em>last</em> word on it ({@code settled}). Only the second one means stop polling.
         */
        public boolean settled() {
            if (!answered()) {
                return false;
            }
            Standing standing = receipt.standing();
            return standing == Standing.CONFIRMED || standing == Standing.FAILED;
        }

        /**
         * The station's channel in MHz, or 0 when the receipt does not name one.
         *
         * <p>0 unless the receipt answers the request standing now and says it is settled. A
         * stale {@code share confirmed sta=5220} left over from before the switch was turned
         * off still contains a channel, and handing it out would let the page print a channel
         * for a radio that is not on it — the cheapest possible version of a believable lie.
         */
        public int staMhz() {
            if (!answered() || receipt.standing() != Standing.CONFIRMED) {
                return 0;
            }
            String detail = receipt.detail();
            int at = detail.indexOf("sta=");
            if (at < 0) {
                return 0;
            }
            int end = at + 4;
            while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
                end++;
            }
            try {
                return Integer.parseInt(detail.substring(at + 4, end));
            } catch (NumberFormatException notANumber) {
                return 0;
            }
        }

        /**
         * The state in one sentence, for the line under the switch.
         *
         * <p>Every sentence here is either a quote of what the daemon said or a statement
         * about the request — never a guess about the radio. That rule is why there is no
         * sentence claiming "Wi-Fi is off" until a receipt says {@code exclusive confirmed}:
         * with no daemon running, the station is in whatever state the last {@code svc} left
         * it, and a page that asserts otherwise teaches the user to distrust the page.
         */
        public String subtitle() {
            if (!receipt.present()) {
                if (!asked) {
                    // The default. Said out loud because it is the state this app starts in:
                    // no session is running, so the AP stays connected either way.
                    return "默认：会话未开始，AP 保持连接（Wi-Fi 开启）";
                }
                return "已请求" + chinese(want) + "，还没有回执——守护进程可能没在运行";
            }
            if (!receipt.answers(want)) {
                return "已请求" + chinese(want) + "，回执还停在「" + receipt.line() + "」";
            }
            switch (receipt.standing()) {
                case CONFIRMED:
                    if (want == Want.EXCLUSIVE) {
                        // 2026-11-25 会话制：独占不再关 Wi-Fi——AP 的断开由应用侧的
                        // WifiManager 负责，守护只确认 Wi-Fi 开着（STA 接口可用）。
                        return "独占中：AP 已断开，Wi-Fi 保持开启";
                    }
                    int mhz = staMhz();
                    return mhz == 5745
                            ? "共存中：AP 在 149 信道，与 AWDL 同信道，互不打断"
                            : mhz > 0
                                    ? "共享中：AP 在 " + mhz + " MHz（跨信道，会话将断开它）"
                                    : "共享中：Wi-Fi 开着，与 AWDL 共用射频";
                case PENDING:
                    return want == Want.SHARE
                            ? "正在开 Wi-Fi，等 STA 拿到信道"
                            : "正在确认 Wi-Fi 开启";
                case FAILED:
                    return (want == Want.SHARE ? "共享失败：" : "独占失败：") + reason(receipt.detail());
                default:
                    return "回执看不懂：「" + receipt.line() + "」";
            }
        }

        /** Whether the row should be drawn as a failure. */
        public boolean failed() {
            return answered() && receipt.standing() == Standing.FAILED;
        }
    }

    /** Reads both files. Missing files are not errors: they are the first launch. */
    public static State read(File dir) {
        File request = new File(dir, REQUEST);
        Want want = Want.of(firstLine(request));
        if (want == null) {
            // Absent, empty, or a word this build does not know: all three are "nothing was
            // asked for", which is the same state the daemon opens with.
            want = Want.EXCLUSIVE;
        }
        // Asked is about the file existing, not about what it says. An empty file is somebody
        // having written nothing — a half-finished manual edit, a crash — and calling that
        // "nobody ever asked" would hide a damaged file behind the default.
        return new State(want, request.isFile(), parse(firstLine(new File(dir, RECEIPT))));
    }

    /**
     * Asks for {@code want}.
     *
     * <p>Returns only once the request is in place, so the page can start waiting for an answer
     * immediately. There is no verification here that the daemon picked it up: that is what the
     * receipt is for, and a page that assumed delivery would have nothing to show when the
     * daemon is not running.
     */
    public static void ask(File dir, Want want) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("no directory at " + dir.getAbsolutePath());
        }
        File target = new File(dir, REQUEST);
        File sibling = new File(dir, REQUEST + ".new");
        try (FileOutputStream out = new FileOutputStream(sibling)) {
            out.write((want.word() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        try {
            Files.move(
                    sibling.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            // Same directory, same filesystem, so the phone never takes this path. The JVM
            // check runs on whatever host it is given, and a Windows one cannot rename onto
            // an existing file — there the replacement is copy-then-delete, which is safe
            // because nothing polls that directory on a developer's desk.
            Files.move(sibling.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** One catalog of the daemon's reasons, in the person's words. Unknown ones pass through. */
    static String reason(String why) {
        if (why == null || why.isEmpty()) {
            return "没有说原因";
        }
        switch (why) {
            case "wi-fi did not turn on":
                return "Wi-Fi 没有打开";
            case "wi-fi on, no association":
                return "Wi-Fi 开了，但 STA 没有关联到信道";
            case "cannot read wi-fi state":
                return "读不到 Wi-Fi 状态";
            default:
                break;
        }
        // "wi-fi still on" / "wi-fi still off" / "wi-fi still unknown"——2026-11-25 会话制
        // 之后两种状态都在等 Wi-Fi 开启，失败原因就是它没开成。
        if (why.startsWith("wi-fi still")) {
            return "Wi-Fi 没有按请求开启";
        }
        if (why.startsWith("unknown request")) {
            return "守护进程看不懂这个请求（新旧版本不一致）";
        }
        if (why.startsWith("svc wifi")) {
            return "调 svc wifi 失败：" + why;
        }
        return why;
    }

    private static String chinese(Want want) {
        return want == Want.SHARE ? "共享" : "独占";
    }

    /** One receipt line into its three parts. */
    static Receipt parse(String text) {
        String line = text == null ? "" : text.trim();
        if (line.isEmpty()) {
            return new Receipt("", null, null, "");
        }
        String[] fields = line.split("\\s+", 3);
        Want word = Want.of(fields[0]);
        Standing standing = fields.length < 2 ? Standing.UNKNOWN : standing(fields[1]);
        String detail = fields.length < 3 ? "" : fields[2];
        return new Receipt(line, word, standing, detail);
    }

    private static Standing standing(String word) {
        switch (word) {
            case "confirmed":
                return Standing.CONFIRMED;
            case "pending":
                return Standing.PENDING;
            case "failed":
                return Standing.FAILED;
            default:
                return Standing.UNKNOWN;
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
