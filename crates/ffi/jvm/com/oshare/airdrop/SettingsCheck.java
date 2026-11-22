package com.oshare.airdrop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asserts, from the Java side, that the settings page can be drawn and saved.
 *
 * <p>Run by {@code check.sh}; the argument is a directory to work in.
 *
 * <p>The page itself is {@link SettingsScreen}. What is checked here is what that class
 * cannot check on its own: that the catalog it reads is well formed, that every row is
 * classified under a heading, that a default fits the control it is drawn with, and — the
 * one that matters — that the two independent validators agree. Every violating value below
 * is put to {@link SettingsScreen.Setting#problem()} and to the library's own save, and both
 * have to refuse it. That is the check a single implementation cannot pass by agreeing with
 * itself.
 */
public final class SettingsCheck {
    private static final List<String> failures = new ArrayList<>();
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        Path home = Files.createDirectories(Paths.get(args[0]));
        Path file = home.resolve("settings.conf");

        List<SettingsScreen.Setting> rows = SettingsScreen.catalog();
        theCatalogIsWellFormed(rows);

        SettingsScreen screen = SettingsScreen.open(file);
        thePageCoversTheTable(screen, rows);
        everyDefaultFitsItsOwnControl(screen);

        aSaveIsWhatTheNextLaunchReads(file, screen);
        anEditIsUndoneByRevertAndReset(file, screen);
        bothSidesRefuseTheSameValues(file);
        anUnreadableFileStillHasAUsableTable(home);

        // 失败必须以非零退出码说出来。这个类原来是唯一不这么做的：它数着 failures，
        // 却从来不看，于是 `check.sh` 照样 exit 0 打印 "ok, N checks"——一条真失败的
        // 断言在日志里只是一个 FAIL 字样，构建是绿的。2026-11-21 就是这么发现
        // 「目录冒充不可读文件」那条断言在退化成真时被静默放过的。
        if (!failures.isEmpty()) {
            System.out.println("FAILED " + failures.size() + " of " + checks);
            for (String failure : failures) {
                System.out.println("  " + failure);
            }
            System.exit(1);
        }
        System.out.println("ok, " + checks + " checks");
    }

    /**
     * 文件一个字节都读不出来时，表也得是能用的。
     *
     * <p>这条锁的是设备上真发生过的一次：{@code settings.conf} 的属主还是重装前的 uid，
     * 应用读它得到 {@code EACCES}，而当时的接收入口把「读不出来」当成「拒掉这条连接」，
     * 于是整台手机收不到任何东西——首页那行从「监听中」变成「异常」。
     *
     * <p>{@link SettingsScreen#open} 是那个故障的修法：读不出来就把文件改名挪开，再按
     * 「首次启动」读一遍。改名只需要目录的写权限，不需要文件的读权限，所以这条路在
     * {@code EACCES} 下也走得通——这正是它比「让用户去点删除」强的地方。
     *
     * <p>两条断言分别对应两条分支：挪得开时页面必须能用（旧字节留在
     * {@code .unreadable} 里），挪不开时必须把原来那个错原样抛出去，让页面能说清楚
     * 到底怎么了，而不是假装成一次首次启动。
     */
    private static void anUnreadableFileStillHasAUsableTable(Path home) throws IOException {
        // 拿一个目录当配置文件：`fs::read` 对它返回 `EISDIR`，这条恢复路径就是这么进去的。
        // 不用 chmod 造 EACCES，是因为这个检查在 Windows 上也跑，那里没有 POSIX 权限。
        Path unusable = Files.createDirectories(home.resolve("unreadable.conf"));
        Path aside = home.resolve("unreadable.conf.unreadable");

        SettingsScreen table = SettingsScreen.open(unusable);
        check("a file that cannot be read is recovered instead of refused", true, "");
        check("the recovery says where the old bytes went", aside.equals(table.recoveredFrom()),
                String.valueOf(table.recoveredFrom()));
        check("the old bytes are still there under the aside name", Files.isDirectory(aside),
                "nothing was moved");
        check("the recovered file is gone from its own name", !Files.exists(unusable),
                "it is still in the way");

        // 设置页不是第一个读这张表的人：启动时 `Tuning` 已经读过一遍，那一次就把文件挪走了。
        // 所以「页面要能看到落点」必须对**后来**读到的实例也成立，不能只在亲手改名的那个
        // 实例上成立。这条断言的就是那一刻——真机上漏过，页面对被挪走的文件一个字没说。
        SettingsScreen later = SettingsScreen.open(unusable);
        check("a later read still reports the moved file", aside.equals(later.recoveredFrom()),
                String.valueOf(later.recoveredFrom()));

        check("a table built without reading has every row",
                table.sections().stream().mapToInt(section -> section.settings().size()).sum()
                        == SettingsScreen.catalog().size(),
                "row count differs from the catalog");

        SettingsScreen.Setting directory = table.setting("receive.directory");
        check("the fallback table has a receive directory row", directory != null, "missing");
        check("the fallback receive directory is empty", "".equals(directory.value()),
                directory.value());
        check("the fallback table names no fixed directory", directory.value().trim().isEmpty(),
                directory.value());

        SettingsScreen.Setting overwrite = table.setting("receive.overwrite");
        check("the fallback table has an overwrite row", overwrite != null, "missing");
        check("the fallback overwrite reads as off", "false".equals(overwrite.value()),
                overwrite.value());

        check("the fallback table is not dirty", !table.dirty(), "it looks edited");

        // 第二条分支：连改名都不行。落点上先放一个非空目录，`REPLACE_EXISTING` 对非空目录
        // 会拒绝（`DirectoryNotEmptyException`），于是 `open` 只能把原来那个错原样抛出。
        Path stuck = Files.createDirectories(home.resolve("stuck.conf"));
        Files.createDirectories(home.resolve("stuck.conf.unreadable"));
        Files.write(home.resolve("stuck.conf.unreadable/in-the-way"), new byte[] {1});
        boolean rethrown;
        try {
            SettingsScreen.open(stuck);
            rethrown = false;
        } catch (IOException expected) {
            rethrown = true;
        }
        check("a file that cannot be moved aside still reports the original failure", rethrown,
                "the recovery invented a way through");
    }
    private static void theCatalogIsWellFormed(List<SettingsScreen.Setting> rows) {
        check("the catalog has rows", !rows.isEmpty(), "it was empty");
        Set<String> seen = new LinkedHashSet<>();
        for (SettingsScreen.Setting row : rows) {
            check("the key of a row is unique: " + row.key(), seen.add(row.key()), "it appears twice");
            check("the key of a row is grouped: " + row.key(), row.key().contains("."),
                    "no group prefix");
            check("the row for " + row.key() + " has a title", !row.title().isEmpty(), "empty");
            check("the row for " + row.key() + " explains itself", !row.summary().isEmpty(), "empty");
            check("the row for " + row.key() + " can be drawn",
                    row.control() != null, "no control");
        }
    }

    /** A row the page cannot place would appear at the bottom under "其他" for no reason. */
    private static void thePageCoversTheTable(
            SettingsScreen screen, List<SettingsScreen.Setting> rows) {
        List<SettingsScreen.Setting> drawn = new ArrayList<>();
        List<String> tableOrder = keys(rows);
        for (SettingsScreen.Section section : screen.sections()) {
            check("the heading " + section.title() + " explains itself",
                    !section.note().isEmpty(), "empty note");
            check("the heading " + section.title() + " is not the fallback",
                    !section.title().equals(SettingsScreen.UNSORTED),
                    "these keys have no heading: " + keys(section.settings()));
            check("the heading " + section.title() + " is in table order",
                    inTableOrder(section.settings(), tableOrder), keys(section.settings()).toString());
            drawn.addAll(section.settings());
        }
        check("every row is under a heading", drawn.size() == rows.size(),
                rows.size() + " rows, " + drawn.size() + " drawn");
        check("a key finds its own row", screen.setting(rows.get(0).key()) != null, "not found");
        check("an unknown key finds nothing", screen.setting("no.such.key") == null, "it found one");
    }

    private static void everyDefaultFitsItsOwnControl(SettingsScreen screen) {
        for (SettingsScreen.Setting row : SettingsScreen.catalog()) {
            check("the default for " + row.key() + " fits its control",
                    row.problem() == null, row.value() + " — " + row.problem());
        }

        SettingsScreen.Setting bytes = screen.setting("receive.byte_limit");
        check("a byte count is shown in binary units",
                bytes.range().contains("MiB") && bytes.range().contains("TiB"), bytes.range());
        SettingsScreen.Setting time = screen.setting("server.read_timeout_ms");
        check("a duration is shown in seconds", time.range().contains("秒"), time.range());
        SettingsScreen.Setting count = screen.setting("discovery.remembered_peers");
        check("a bare count has no unit", count.unit().isEmpty(), count.unit());
        check("a bare count says what it allows", count.range().contains("–"), count.range());
        SettingsScreen.Setting level = screen.setting("log.level");
        check("a choice lists its options", level.range().contains("debug"), level.range());
        SettingsScreen.Setting text = screen.setting("device.name");
        check("a text field says how long it may be", text.range().contains("200"), text.range());

        // The reset dialog has to say what it will restore. Reading that from the live value
        // is the mistake these three exist to catch: after an edit, `reading()` moves and
        // `defaultReading()` must not.
        check("a default is readable in words", "8 GiB".equals(bytes.defaultReading()),
                bytes.defaultReading());
        screen.set("receive.byte_limit", "1048576");
        check("the reading follows the edit", "1 MiB".equals(bytes.reading()), bytes.reading());
        check("the default reading ignores the edit",
                "8 GiB".equals(bytes.defaultReading()), bytes.defaultReading());
        screen.revert();
        check("a row that is not a number has no reading",
                level.defaultReading().isEmpty(), level.defaultReading());
    }

    private static void aSaveIsWhatTheNextLaunchReads(Path file, SettingsScreen screen)
            throws Exception {
        String before = load(file);
        screen.set("log.level", "debug");
        screen.set("receive.overwrite", "true");
        check("an edit makes the page dirty", screen.dirty(), "it stayed clean");
        check("a good page saves", screen.save().isEmpty(), "it was refused");

        String after = load(file);
        check("the save changed the stored text", !after.equals(before), "nothing changed");
        check("the stored text is the one the page would write",
                body(after).equals(screen.stored()), body(after));
        check("a saved page is no longer dirty", !screen.dirty(), "it is still dirty");

        SettingsScreen reopened = SettingsScreen.open(file);
        check("the reopened page shows the saved choice",
                "debug".equals(reopened.setting("log.level").value()),
                reopened.setting("log.level").value());
        check("the reopened page shows the saved switch",
                "true".equals(reopened.setting("receive.overwrite").value()),
                reopened.setting("receive.overwrite").value());
    }

    private static void anEditIsUndoneByRevertAndReset(Path file, SettingsScreen screen)
            throws Exception {
        screen.set("log.level", "off");
        check("the edit is visible", "off".equals(screen.setting("log.level").value()), "no");
        screen.revert();
        check("revert goes back to what was loaded",
                "debug".equals(screen.setting("log.level").value()),
                screen.setting("log.level").value());
        check("revert leaves the page clean", !screen.dirty(), "still dirty");

        screen.reset("log.level");
        check("reset uses the table's default", "debug".equals(screen.setting("log.level").value()),
                screen.setting("log.level").value());
    }

    /**
     * One violating value per control kind, put to both validators.
     *
     * <p>The strings are the ones a person can actually type: a spinner cannot produce
     * anything but its options, so the choice case is a value from an older version, and the
     * text cases are what a paste can carry in.
     */
    private static void bothSidesRefuseTheSameValues(Path file) throws Exception {
        String before = load(file);
        SettingsScreen screen = SettingsScreen.open(file);

        refuse(file, screen, "receive.overwrite", "maybe", "a switch that is neither true nor false");
        refuse(file, screen, "server.read_timeout_ms", "10", "a number below its floor");
        refuse(file, screen, "client.read_timeout_ms", "9999999", "a number above its ceiling");
        refuse(file, screen, "receive.decision_timeout_ms", "2.5", "a number that is not whole");
        refuse(file, screen, "log.level", "verbose", "a value outside its options");
        refuse(file, screen, "device.name", repeat('x', 300), "text longer than its field");
        refuse(file, screen, "device.name", "a\tb", "text that would break the storage line");

        check("a refused save leaves the file alone", load(file).equals(before),
                "the file changed to " + load(file));
    }

    private static void refuse(
            Path file, SettingsScreen screen, String key, String value, String what)
            throws Exception {
        screen.set(key, value);
        String mine = screen.setting(key).problem();
        check(what + " is refused by the page", mine != null, "the page accepted " + value);

        List<String> problems = screen.save();
        check(what + " stops the save", !problems.isEmpty(), "the save went through");
        check("the refusal for " + what + " names the field",
                problems.isEmpty() || problems.get(0).startsWith(key + ":"),
                problems.toString());

        // The same value, straight to the library: a second implementation, not a second call
        // into the first.
        try {
            Native.saveSettings(file.toString(), edited(load(file), key, value));
            check(what + " is refused by the library", false, "the library accepted " + value);
        } catch (IllegalArgumentException refused) {
            check(what + " is refused by the library", true, "");
            check("the library's refusal for " + what + " says why",
                    refused.getMessage().length() > 10, refused.getMessage());
        }
        screen.revert();
    }

    /** The stored text with one key's value replaced, as a hand edit would do. */
    private static String edited(String text, String key, String value) {
        Pattern line = Pattern.compile("(?m)^" + Pattern.quote(key) + "=.*$");
        return line.matcher(text).replaceFirst(Matcher.quoteReplacement(key + "=" + value));
    }

    private static String repeat(char fill, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            out.append(fill);
        }
        return out.toString();
    }

    private static List<String> keys(List<SettingsScreen.Setting> settings) {
        List<String> keys = new ArrayList<>();
        for (SettingsScreen.Setting setting : settings) {
            keys.add(setting.key());
        }
        return keys;
    }

    private static boolean inTableOrder(
            List<SettingsScreen.Setting> settings, List<String> tableOrder) {
        int previous = -1;
        for (SettingsScreen.Setting setting : settings) {
            int at = tableOrder.indexOf(setting.key());
            if (at <= previous) {
                return false;
            }
            previous = at;
        }
        return true;
    }

    private static String load(Path file) throws IOException {
        return new String(Native.loadSettings(file.toString()), StandardCharsets.UTF_8);
    }

    /** The stored text without its version head, which is not a value. */
    private static String body(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  ok    " + what);
        } else {
            failures.add(what + (detail == null || detail.isEmpty() ? "" : ": " + detail));
            System.out.println("  FAIL  " + what + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        }
    }

    private SettingsCheck() {}
}
