package com.oshare.airdrop.core;

import java.util.Collection;
import java.util.Locale;

/**
 * Turns the name a sharing app gives its content into an archive member name.
 *
 * <p>A display name is not a member name. The three differences that matter:
 *
 * <ul>
 *   <li>Providers hand out whole paths ({@code DCIM/Camera/IMG_0001.jpg}). A member name
 *       is one path segment, and {@link Cpio#safeName} would reject or misplace the
 *       rest, so only the last segment survives.
 *   <li>Two files can share a name. Send two photos picked from different albums and both
 *       may be called {@code image.jpg}; the receiver then writes both under the same
 *       name and the first is silently lost. {@link #unique} numbers the second one.
 *   <li>Names can be unusable: empty, {@code .}, {@code ..}, full of control characters,
 *       or longer than the 255 bytes a filesystem will take for one component.
 * </ul>
 *
 * <p>Uniqueness is decided case-insensitively. iOS writes to APFS, which is
 * case-insensitive by default, and Android's external storage sits on FAT-derived
 * drivers that treat {@code Photo.JPG} and {@code photo.jpg} as the same file. Comparing
 * the way the receiver will compare is the only way the numbering prevents a collision
 * rather than merely tidying the offer.
 */
public final class SharedNames {
    /** What a nameless share is called. Never empty, so the archive always has a member. */
    public static final String FALLBACK = "shared-file";

    /**
     * The most bytes one path component may occupy. Filesystems stop at 255; the margin
     * covers the {@code ./} the archive prefixes and leaves room to number a duplicate.
     */
    private static final int MAX_BYTES = 200;

    private SharedNames() {}

    /** A single safe path segment, never empty. */
    public static String clean(String raw, String fallback) {
        String name = raw == null ? "" : raw.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) name = name.substring(separator + 1);
        name = withoutControlCharacters(name).trim();
        if (unusable(name)) {
            String alternative = withoutControlCharacters(fallback == null ? "" : fallback.trim()).trim();
            name = unusable(alternative) ? FALLBACK : alternative;
        }
        return fit(name);
    }

    /**
     * {@link #clean} plus a {@code -2}, {@code -3}… suffix if the name is already taken.
     *
     * <p>The name is recorded in {@code taken} before it is returned, in the lower-cased
     * form used for comparison, so the caller only has to pass one set along a batch.
     */
    public static String unique(String wanted, Collection<String> taken) {
        String base = clean(wanted, FALLBACK);
        if (taken.add(key(base))) return base;
        String extension = extensionOf(base);
        String stem = base.substring(0, base.length() - extension.length());
        for (int number = 2; ; number++) {
            String candidate = compose(stem, "-" + number, extension);
            // A candidate that got its suffix truncated away would collide with the name
            // that made it necessary, so compose() guarantees the suffix and this loop
            // always terminates.
            if (taken.add(key(candidate))) return candidate;
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean unusable(String name) {
        return name.isEmpty() || name.equals(".") || name.equals("..");
    }

    private static String withoutControlCharacters(String name) {
        StringBuilder kept = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            // Control characters and DEL: rejected by ext4, APFS, and MediaStore alike.
            if (character >= 0x20 && character != 0x7f) kept.append(character);
        }
        return kept.toString();
    }

    /** Splits off a plausible extension; a leading dot is part of the name, not one. */
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return "";
        String extension = name.substring(dot);
        // ".tar.gz" style tails longer than this are not extensions, just names with dots.
        return extension.length() <= 17 ? extension : "";
    }

    private static String fit(String name) {
        if (bytes(name) <= MAX_BYTES) return name;
        String extension = extensionOf(name);
        String stem = name.substring(0, name.length() - extension.length());
        return compose(stem, "", extension);
    }

    /**
     * Joins stem, suffix and extension under the byte budget, keeping the suffix and the
     * extension whole and giving the stem whatever is left.
     */
    private static String compose(String stem, String suffix, String extension) {
        int budget = Math.max(1, MAX_BYTES - bytes(suffix) - bytes(extension));
        return truncate(stem, budget) + suffix + extension;
    }

    /** Shortens by whole code points: a cut inside a surrogate pair yields a broken name. */
    private static String truncate(String stem, int budget) {
        StringBuilder kept = new StringBuilder(stem.length());
        int used = 0;
        for (int index = 0; index < stem.length(); ) {
            int codePoint = stem.codePointAt(index);
            int width = width(codePoint);
            if (used + width > budget) break;
            kept.appendCodePoint(codePoint);
            used += width;
            index += Character.charCount(codePoint);
        }
        return kept.toString();
    }

    private static int bytes(String text) {
        int total = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            total += width(codePoint);
            index += Character.charCount(codePoint);
        }
        return total;
    }

    private static int width(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }
}
