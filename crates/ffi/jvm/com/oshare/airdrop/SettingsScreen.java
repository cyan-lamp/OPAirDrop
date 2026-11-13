package com.oshare.airdrop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings page, as data: which controls to draw, in which order, and what saving does.
 *
 * <p>This class contains no Android type on purpose. The Activity that shows this page is a
 * renderer — it walks {@link #sections()}, adds one row per {@link Setting}, and calls
 * {@link #set} when a control changes — so everything that can be wrong about the page can
 * be wrong here instead of in a simulator: a control drawn for a kind that does not exist, a
 * default that is not inside its own range, a save that stores something the next launch
 * cannot read.
 *
 * <p># The table is the page
 *
 * <p>Nothing here lists the settings. {@link #open} asks the library for the catalog and for
 * the stored values, and the two are put side by side. Adding a row to the Rust table makes a
 * control appear without an edit here; removing one makes it disappear. The one thing that
 * has to be kept by hand is {@link #sectionOf}: which heading a key belongs under. A key
 * whose prefix no heading claims lands in {@link #UNSORTED}, and the JVM check asserts that
 * section is empty — so a new key forces a decision instead of silently appearing at the
 * bottom of a page as "其他".
 *
 * <p># Validation happens twice, and that is not duplication
 *
 * <p>{@link Setting#problem()} refuses a value before a byte is written, so the page can put
 * the message under the field that caused it. The library refuses the same value again at
 * {@link #save}, from the same table, on the other side of the boundary. The two are
 * independent implementations of one contract: if they ever disagree the save fails loudly
 * rather than storing something unreadable, and the JVM check feeds every violating value to
 * both and asserts they both refuse it.
 */
public final class SettingsScreen {
    /** Which widget the page draws for a value. */
    public enum Control {
        /** One switch. */
        SWITCH,
        /** A whole number inside {@link Setting#min()}..{@link Setting#max()}. */
        NUMBER,
        /** One of {@link Setting#options()}, drawn as a spinner. */
        CHOICE,
        /** Free text of at most {@link Setting#maxLength()} characters. */
        TEXT
    }

    /** The heading a key with no claimed prefix falls under. Never shown when empty. */
    public static final String UNSORTED = "其他";

    /** One row of the page: a key, its control, its wording, and the value being edited. */
    public static final class Setting {
        private final String key;
        private final Control control;
        private final String defaultValue;
        private final String title;
        private final String summary;
        private final String unit;
        private final long min;
        private final long max;
        private final int maxLength;
        private final List<String> options;
        private String value;

        private Setting(
                String key,
                Control control,
                String defaultValue,
                String title,
                String summary,
                String unit,
                long min,
                long max,
                int maxLength,
                List<String> options) {
            this.key = key;
            this.control = control;
            this.defaultValue = defaultValue;
            this.title = title;
            this.summary = summary;
            this.unit = unit;
            this.min = min;
            this.max = max;
            this.maxLength = maxLength;
            this.options = Collections.unmodifiableList(options);
            this.value = defaultValue;
        }

        /** Dotted name, the same string the stored file uses. */
        public String key() {
            return key;
        }

        /** The widget to draw. */
        public Control control() {
            return control;
        }

        /** The title line. */
        public String title() {
            return title;
        }

        /** The sentence under the title. */
        public String summary() {
            return summary;
        }

        /** The value being edited, as it would be stored. */
        public String value() {
            return value;
        }

        /** The table's default, for the row's own "restore" action. */
        public String defaultValue() {
            return defaultValue;
        }

        /** Smallest accepted value, for {@link Control#NUMBER}. */
        public long min() {
            return min;
        }

        /** Largest accepted value, for {@link Control#NUMBER}. */
        public long max() {
            return max;
        }

        /** What a number counts, in the person's words; empty for the other kinds. */
        public String unit() {
            return unit;
        }

        /** Longest accepted text, for {@link Control#TEXT}. */
        public int maxLength() {
            return maxLength;
        }

        /** The options, for {@link Control#CHOICE}. */
        public List<String> options() {
            return options;
        }

        /** The allowed span, worded for a subtitle: "64 MiB – 1 TiB", or "" for a switch. */
        public String range() {
            switch (control) {
                case NUMBER:
                    return format(min) + " – " + format(max);
                case CHOICE:
                    return String.join(" / ", options);
                case TEXT:
                    return "最多 " + maxLength + " 个字符";
                default:
                    return "";
            }
        }

        /**
         * The value read back in a larger unit — "8 GiB", "60 秒" — for the field's hint.
         *
         * <p>Display only, and never parsed: an edit that arrives as "8.0 GiB" must not be
         * turned back into a number, because the text a person types is the number itself.
         */
        public String reading() {
            return reading(value);
        }

        /**
         * The default read back the same way — "8 GiB" for {@code 8589934592}.
         *
         * <p>Separate from {@link #reading} because the two answer different questions, and a
         * dialog that offers to restore a default has to answer the second one. Asking whether
         * to go back to {@code 8589934592} is a question nobody can answer; going back to
         * "8 GiB" is one they can.
         */
        public String defaultReading() {
            return reading(defaultValue);
        }

        private String reading(String text) {
            if (control != Control.NUMBER) {
                return "";
            }
            try {
                return format(Long.parseLong(text.trim()));
            } catch (NumberFormatException notANumber) {
                return "";
            }
        }

        /**
         * Why this value cannot be stored, or null when it can.
         *
         * <p>The wording is the person's, not the contract's: it has to fit under a field.
         */
        public String problem() {
            switch (control) {
                case SWITCH:
                    return isSwitchValue(value) ? null : "只能是开或关";
                case NUMBER: {
                    long number;
                    try {
                        number = Long.parseLong(value.trim());
                    } catch (NumberFormatException notANumber) {
                        return "要填一个整数";
                    }
                    if (number < min || number > max) {
                        return "要在 " + range() + " 之间";
                    }
                    return null;
                }
                case CHOICE:
                    return options.contains(value) ? null : "只能是 " + String.join(" / ", options) + " 之一";
                default:
                    if (value.length() > maxLength) {
                        return "最多 " + maxLength + " 个字符";
                    }
                    if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                        return "不能包含制表符或换行";
                    }
                    return null;
            }
        }

        /**
         * A count written in the unit a person reads it in — "8 GiB", "60 秒", "2500 毫秒".
         *
         * <p>Falls back to the bare number rather than to a unit: a number with the wrong
         * suffix is a lie, and the field next to it already says what is counted.
         */
        private String format(long number) {
            switch (unit) {
                case "字节":
                    return size(number);
                case "毫秒":
                    return duration(number);
                default:
                    return Long.toString(number);
            }
        }
    }

    /** A heading and the rows under it. */
    public static final class Section {
        private final String title;
        private final String note;
        private final List<Setting> settings = new ArrayList<>();

        private Section(String title, String note) {
            this.title = title;
            this.note = note;
        }

        /** The heading. */
        public String title() {
            return title;
        }

        /** One line saying what the heading covers. */
        public String note() {
            return note;
        }

        /**
         * The rows under this heading, in table order.
         *
         * <p>The list is live: a {@link Setting} holds its own value, so changing a control
         * does not reorder anything.
         */
        public List<Setting> settings() {
            return settings;
        }
    }

    private final Path file;
    private final List<Setting> settings = new ArrayList<>();
    private final Map<String, String> loaded = new LinkedHashMap<>();
    private final List<Section> sections = new ArrayList<>();

    private SettingsScreen(Path file) {
        this.file = file;
    }

    /**
     * Reads the catalog and the values stored at {@code file}, and pairs them up.
     *
     * <p>A missing file is not a failure: it is the first launch, and every row shows the
     * table's default. A file that exists and does not parse is refused — the message names
     * the line — because answering with defaults would throw away what the person had set.
     */
    public static SettingsScreen open(Path file) throws IOException {
        SettingsScreen screen = new SettingsScreen(file);
        for (Setting setting : catalog()) {
            screen.settings.add(setting);
            screen.loaded.put(setting.key, setting.value);
        }
        String stored = new String(Native.loadSettings(file.toString()), StandardCharsets.UTF_8);
        for (Setting setting : screen.settings) {
            String value = valuesOf(stored).get(setting.key);
            if (value != null) {
                setting.value = value;
                screen.loaded.put(setting.key, value);
            }
        }
        screen.buildSections();
        return screen;
    }

    /**
     * The catalog alone, every row at its default.
     *
     * <p>The order is the table's, which is also the order the file is written in.
     */
    public static List<Setting> catalog() {
        String text = new String(Native.settingsCatalog(), StandardCharsets.UTF_8);
        List<Setting> rows = new ArrayList<>();
        for (String line : linesOf(text)) {
            if (line.startsWith("#")) {
                continue;
            }
            rows.add(parse(line));
        }
        return rows;
    }

    /** The page: headings in the order their first row appears. */
    public List<Section> sections() {
        return sections;
    }

    /** The row named {@code key}, or null when the table has no such row. */
    public Setting setting(String key) {
        for (Setting setting : settings) {
            if (setting.key.equals(key)) {
                return setting;
            }
        }
        return null;
    }

    /** Records an edit. Nothing is validated and nothing is written yet. */
    public void set(String key, String text) {
        Setting setting = setting(key);
        if (setting == null) {
            throw new IllegalArgumentException("no such setting: " + key);
        }
        setting.value = text == null ? "" : text;
    }

    /** Puts a row back to the table's default. */
    public void reset(String key) {
        Setting setting = setting(key);
        if (setting == null) {
            throw new IllegalArgumentException("no such setting: " + key);
        }
        setting.value = setting.defaultValue;
    }

    /** Whether anything differs from what was loaded. */
    public boolean dirty() {
        for (Setting setting : settings) {
            if (!setting.value.equals(loaded.get(setting.key))) {
                return true;
            }
        }
        return false;
    }

    /** Drops every edit, back to what the last load or save produced. */
    public void revert() {
        for (Setting setting : settings) {
            setting.value = loaded.get(setting.key);
        }
    }

    /** The text that {@link #save} would store, in table order. */
    public String stored() {
        StringBuilder out = new StringBuilder();
        for (Setting setting : settings) {
            out.append(setting.key).append('=').append(setting.value.trim()).append('\n');
        }
        return out.toString();
    }

    /**
     * Stores the page, or explains why it cannot be.
     *
     * <p>Returns one message per row that is unacceptable, each prefixed with the key, and
     * writes nothing in that case — a page with one bad field must not save the other
     * thirteen, because the person would see a saved page and a wrong value next launch.
     *
     * <p>An empty list means stored. A failure that this class did not predict (the library
     * refusing something {@link Setting#problem()} accepted) comes back as a single message
     * without a key prefix: it means the two sides of the boundary disagree, which is a bug
     * rather than something the person can fix.
     */
    public List<String> save() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Setting setting : settings) {
            String problem = setting.problem();
            if (problem != null) {
                problems.add(setting.key + ": " + problem);
            }
        }
        if (!problems.isEmpty()) {
            return problems;
        }

        String written;
        try {
            written = new String(
                    Native.saveSettings(file.toString(), stored()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException refused) {
            return List.of(refused.getMessage());
        }
        // What the library stored is what the next launch reads, so it becomes the baseline
        // for "dirty" — and a value the library normalized is shown as it will be read.
        for (Setting setting : settings) {
            String value = valuesOf(written).get(setting.key);
            if (value != null) {
                setting.value = value;
                loaded.put(setting.key, value);
            }
        }
        return problems;
    }

    private void buildSections() {
        Map<String, Section> byTitle = new LinkedHashMap<>();
        for (Setting setting : settings) {
            String title = sectionOf(setting.key);
            Section section = byTitle.get(title);
            if (section == null) {
                section = new Section(title, noteOf(title));
                byTitle.put(title, section);
                sections.add(section);
            }
            section.settings.add(setting);
        }
    }

    /** Which heading a key belongs under. A key nobody claims lands in {@link #UNSORTED}. */
    static String sectionOf(String key) {
        int dot = key.indexOf('.');
        String prefix = dot < 0 ? key : key.substring(0, dot);
        switch (prefix) {
            case "device":
                return "设备";
            case "discover":
            case "discovery":
                return "发现";
            case "receive":
            case "server":
                return "接收";
            case "client":
            case "http":
                return "发送";
            case "log":
                return "诊断";
            default:
                return UNSORTED;
        }
    }

    private static String noteOf(String title) {
        switch (title) {
            case "设备":
                return "对端在列表里看到的名字。";
            case "发现":
                return "搜索对端，以及记住哪些设备。";
            case "接收":
                return "别人发给你时等多久、收到哪。";
            case "发送":
                return "你发给别人时等多久、收多少。";
            case "诊断":
                return "出问题时日志记到多细。";
            default:
                return "还没有归类的设置。";
        }
    }

    /** One catalog line into a row. Throws rather than skipping, so a bad table is loud. */
    private static Setting parse(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 5) {
            throw new IllegalStateException("a catalog line has five fields: " + line);
        }
        String key = fields[0];
        String spec = fields[1];
        String value = fields[2];
        String title = fields[3];
        String summary = fields[4];

        if (spec.equals("switch")) {
            return new Setting(key, Control.SWITCH, value, title, summary, "", 0, 0, 0,
                    new ArrayList<>());
        }
        String[] parts = spec.split(":", -1);
        if (parts[0].equals("number") && parts.length == 4) {
            return new Setting(key, Control.NUMBER, value, title, summary, unitName(parts[3]),
                    Long.parseLong(parts[1]), Long.parseLong(parts[2]), 0, new ArrayList<>());
        }
        if (parts[0].equals("choice") && parts.length == 2) {
            List<String> options = new ArrayList<>();
            Collections.addAll(options, parts[1].split("\\|", -1));
            return new Setting(key, Control.CHOICE, value, title, summary, "", 0, 0, 0, options);
        }
        if (parts[0].equals("text") && parts.length == 2) {
            return new Setting(key, Control.TEXT, value, title, summary, "", 0, 0,
                    Integer.parseInt(parts[1]), new ArrayList<>());
        }
        throw new IllegalStateException("unknown kind spec for " + key + ": " + spec);
    }

    private static String unitName(String spec) {
        switch (spec) {
            case "bytes":
                return "字节";
            case "ms":
                return "毫秒";
            case "plain":
                return "";
            default:
                throw new IllegalStateException("unknown unit: " + spec);
        }
    }

    private static boolean isSwitchValue(String value) {
        return value.equals("true") || value.equals("false");
    }

    /** A byte count in the largest binary unit that keeps it readable. */
    static String size(long bytes) {
        long[] steps = {1L << 40, 1L << 30, 1L << 20, 1L << 10};
        String[] names = {"TiB", "GiB", "MiB", "KiB"};
        for (int index = 0; index < steps.length; index++) {
            if (Math.abs(bytes) >= steps[index]) {
                return tidy((double) bytes / steps[index], names[index]);
            }
        }
        return bytes + " B";
    }

    /**
     * A span of milliseconds in seconds or minutes when it divides evenly.
     *
     * <p>Only when it divides evenly: "1.5 秒" next to a field that stores 1500 reads like
     * the field holds 1.5, and the person then edits it to 1.5 and is refused.
     */
    static String duration(long milliseconds) {
        if (milliseconds != 0 && milliseconds % 60_000 == 0) {
            return milliseconds / 60_000 + " 分钟";
        }
        if (milliseconds != 0 && milliseconds % 1_000 == 0) {
            return milliseconds / 1_000 + " 秒";
        }
        return milliseconds + " 毫秒";
    }

    /** A quotient as "8" or "1.5", with no trailing ".0". */
    private static String tidy(double value, String name) {
        if (value == Math.rint(value)) {
            return (long) value + " " + name;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, name);
    }

    private static List<String> linesOf(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Map<String, String> valuesOf(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : linesOf(text)) {
            int at = line.indexOf('=');
            if (!line.startsWith("#") && at > 0) {
                values.put(line.substring(0, at), line.substring(at + 1));
            }
        }
        return values;
    }
}
