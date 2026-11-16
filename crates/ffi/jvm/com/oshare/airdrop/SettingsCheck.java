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
    private static int failures = 0;

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
        check("reset uses the table's default", "info".equals(screen.setting("log.level").value()),
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
        if (ok) {
            System.out.println("  ok    " + what);
        } else {
            failures++;
            System.out.println("  FAIL  " + what + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        }
    }

    private SettingsCheck() {}
}
