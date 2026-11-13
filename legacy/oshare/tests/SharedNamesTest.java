package com.oshare.airdrop.core;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Offline checks for {@link SharedNames}, the step between "what a sharing app calls this"
 * and "what the archive member is called".
 *
 * <p>Every rule pinned here is a way a transfer can look successful and lose a file:
 * a name that carried a directory, two photos both called {@code image.jpg}, a name the
 * receiver's filesystem refuses, or a truncation that cut a character in half. None of
 * them raise an error anywhere; the second file just is not there.
 */
public final class SharedNamesTest {
    private static int checks;

    public static void main(String[] args) {
        plainNameSurvives();
        providerPathKeepsOnlyTheLastSegment();
        blankNamesBecomeTheFallback();
        controlCharactersAreDropped();
        longNamesKeepTheirExtension();
        multibyteNamesAreCutBetweenCharacters();
        duplicatesGetANumber();
        numberingIgnoresCase();
        numberingSkipsNamesAlreadyTaken();
        suffixSurvivesOnAVeryLongName();
        dottedFilesAreNotExtensions();
        System.out.println("SharedNamesTest OK (" + checks + " checks)");
    }

    private static void plainNameSurvives() {
        eq("photo.jpg", SharedNames.clean("photo.jpg", null), "already usable");
        eq("holiday.mov", SharedNames.clean("  holiday.mov  ", null), "trimmed");
    }

    /**
     * The display name is often a whole path. Only the last segment is a member name:
     * anything else makes the receiver create directories nobody asked for.
     */
    private static void providerPathKeepsOnlyTheLastSegment() {
        eq("IMG_0001.JPG", SharedNames.clean("DCIM/Camera/IMG_0001.JPG", null),
            "forward slashes");
        eq("a.pdf", SharedNames.clean("storage\\emulated\\0\\a.pdf", null), "backslashes");
        eq(SharedNames.FALLBACK, SharedNames.clean("DCIM/", null), "trailing separator only");
    }

    private static void blankNamesBecomeTheFallback() {
        eq(SharedNames.FALLBACK, SharedNames.clean("", null), "empty");
        eq(SharedNames.FALLBACK, SharedNames.clean("   ", null), "whitespace");
        eq(SharedNames.FALLBACK, SharedNames.clean(".", null), "current directory");
        eq(SharedNames.FALLBACK, SharedNames.clean("..", null), "parent directory");
        eq(SharedNames.FALLBACK, SharedNames.clean(null, null), "absent");
        eq(SharedNames.FALLBACK, SharedNames.clean(null, "  "), "unusable fallback");
        eq("clip.mp4", SharedNames.clean(null, "clip.mp4"), "caller's fallback is used");
    }

    private static void controlCharactersAreDropped() {
        eq("badname.txt", SharedNames.clean("bad\u0000na\u001fme.txt", null), "NUL and US");
        eq("del.txt", SharedNames.clean("d\u007fel.txt", null), "DEL");
    }

    /** A name over the byte budget is shortened, and the extension is what must survive. */
    private static void longNamesKeepTheirExtension() {
        String huge = "a".repeat(300) + ".jpeg";
        String fitted = SharedNames.clean(huge, null);
        check(utf8(fitted) <= 200, "fits in 200 bytes, was " + utf8(fitted));
        check(fitted.endsWith(".jpeg"), "extension kept, was " + fitted);
        check(fitted.startsWith("aaaa"), "keeps the front of the name");
        eq(200, utf8(fitted), "uses the whole budget");
    }

    /** Cutting by bytes would split a three-byte character into a replacement character. */
    private static void multibyteNamesAreCutBetweenCharacters() {
        String name = SharedNames.clean("照".repeat(120), null);
        check(utf8(name) <= 200, "fits in 200 bytes, was " + utf8(name));
        eq(66, name.codePointCount(0, name.length()), "66 three-byte characters");
        for (int index = 0; index < name.length(); index++) {
            eq('照', name.charAt(index), "character " + index + " is intact");
        }
    }

    /** Two files with one name: the second would overwrite the first on the receiver. */
    private static void duplicatesGetANumber() {
        Set<String> taken = new LinkedHashSet<>();
        eq("photo.jpg", SharedNames.unique("photo.jpg", taken), "first keeps the name");
        eq("photo-2.jpg", SharedNames.unique("photo.jpg", taken), "second is numbered");
        eq("photo-3.jpg", SharedNames.unique("photo.jpg", taken), "third continues");
        eq(3, taken.size(), "three names recorded");
    }

    /** iOS writes to a case-insensitive filesystem, so the numbering must ignore case. */
    private static void numberingIgnoresCase() {
        Set<String> taken = new LinkedHashSet<>();
        eq("Photo.JPG", SharedNames.unique("Photo.JPG", taken), "first as spelled");
        eq("photo-2.jpg", SharedNames.unique("photo.jpg", taken), "different case still a clash");
    }

    private static void numberingSkipsNamesAlreadyTaken() {
        Set<String> taken = new LinkedHashSet<>();
        taken.add("photo.jpg");
        taken.add("photo-2.jpg");
        eq("photo-3.jpg", SharedNames.unique("photo.jpg", taken), "first free number");
    }

    /**
     * The suffix lives where truncation would cut. If it is lost, the candidate equals the
     * name that forced the numbering and the search never ends.
     */
    private static void suffixSurvivesOnAVeryLongName() {
        Set<String> taken = new LinkedHashSet<>();
        String first = SharedNames.unique("b".repeat(300) + ".jpeg", taken);
        String second = SharedNames.unique("b".repeat(300) + ".jpeg", taken);
        check(!first.equals(second), "two distinct names");
        check(second.endsWith(".jpeg"), "extension kept, was " + second);
        check(second.contains("-2"), "number kept, was " + second);
        check(utf8(second) <= 200, "fits in 200 bytes, was " + utf8(second));
    }

    /** A leading dot starts a hidden file, not an extension; the suffix goes at the end. */
    private static void dottedFilesAreNotExtensions() {
        Set<String> taken = new LinkedHashSet<>();
        eq(".gitignore", SharedNames.unique(".gitignore", taken), "hidden file kept whole");
        eq(".gitignore-2", SharedNames.unique(".gitignore", taken), "suffix appended");
    }

    private static int utf8(String text) {
        int total = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            total += codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            index += Character.charCount(codePoint);
        }
        return total;
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) throw new AssertionError(what);
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }

    private SharedNamesTest() {
    }
}
