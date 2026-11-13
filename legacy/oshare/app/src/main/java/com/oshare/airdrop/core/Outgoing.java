package com.oshare.airdrop.core;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * One file on its way out: what to call it, how the receiver should type it, and how
 * to get its bytes.
 *
 * <p>The archive member name is {@link #bomPath}, which the {@code /Ask} body also
 * carries as {@code FileBomPath}. Those two must agree — the receiver decides where to
 * put the file from the announcement, but it finds it in the archive by name, so a
 * mismatch means a transfer that succeeds on both ends and lands nowhere.
 *
 * <p>{@code FileBomPath} is {@code ./<name>} and not the bare name: the value is a path
 * inside the folder being offered, and the leading {@code ./} is how a receiver that
 * validates the field spells "in this transfer's root". This is not cosmetic — the
 * transfer that first landed a file on a live iPhone offered exactly {@code ./probe.txt}
 * as the BOM path and archived the member under the same name, while the loopback test
 * that followed used a bare name. Our own receiver accepts both, because
 * {@link Cpio#safeName} strips the prefix, so the difference only shows up against a
 * peer that does not: the field and the member name are kept identical here, in the
 * spelling that is known to be accepted.
 *
 * <p>The bytes are opened per transfer rather than held. A shared video is read once,
 * straight into the socket, so peak memory stays at one buffer no matter the size.
 */
public final class Outgoing {
    /** Opens a fresh stream over the content; called once per transfer. */
    public interface Provider {
        InputStream open() throws IOException;
    }

    public final String name;
    public final String type;
    public final String bomPath;
    public final long length;

    private final Provider provider;

    private Outgoing(String name, String type, String bomPath, long length, Provider provider) {
        this.name = name;
        this.type = type;
        this.bomPath = bomPath;
        this.length = length;
        this.provider = provider;
    }

    /** A file from disk. {@code type} may be a MIME type or a UTI. */
    public static Outgoing of(File file, String type) {
        if (file == null) throw new IllegalArgumentException("file == null");
        String name = file.getName();
        long length = file.length();
        return new Outgoing(name, normalizeType(type, name), "./" + name, length,
            () -> new FileInputStream(file));
    }

    /** Content already in memory, such as text pasted into the chooser. */
    public static Outgoing of(String name, String type, byte[] data) {
        byte[] payload = data == null ? new byte[0] : data;
        return new Outgoing(name, normalizeType(type, name), "./" + name, payload.length,
            () -> new ByteArrayInputStream(payload));
    }

    /** Content behind a provider, for anything that is neither a file nor a byte[]. */
    public static Outgoing of(String name, String type, long length, Provider provider) {
        if (provider == null) throw new IllegalArgumentException("provider == null");
        return new Outgoing(name, normalizeType(type, name), "./" + name, length, provider);
    }

    /**
     * Overrides the archive member path; defaults to {@code ./<name>}.
     *
     * <p>Used for anything that arrives as a folder, where the member name has to carry
     * the directory part. The value is still put into the same {@code ./}-rooted shape
     * as the default, so no caller can accidentally produce a bare path and end up with
     * an offer and an archive that disagree; anything that escapes the root is refused
     * later by {@link Cpio#safeName} on the receiving end.
     */
    public Outgoing at(String path) {
        if (path == null || path.trim().isEmpty()) return this;
        String cleaned = path.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (!cleaned.startsWith("./")) cleaned = "./" + cleaned;
        return new Outgoing(name, type, cleaned, length, provider);
    }

    public InputStream open() throws IOException {
        return provider.open();
    }

    /**
     * The {@code FileType} AirDrop expects is a UTI, not a MIME type. macOS and iOS use
     * it to pick an icon and a handler, so sending {@code image/jpeg} where a Mac
     * expects {@code public.jpeg} degrades presentation without breaking the transfer.
     * The table covers what people actually share; anything else passes through, since
     * a wrong-but-plausible type beats an invented one.
     */
    static String normalizeType(String type, String name) {
        String raw = type == null || type.trim().isEmpty() ? guessFromName(name) : type.trim();
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        switch (lower) {
            case "image/jpeg": case "image/jpg": return "public.jpeg";
            case "image/png": return "public.png";
            case "image/heic": case "image/heif": return "public.heic";
            case "image/gif": return "com.compuserve.gif";
            case "image/webp": return "org.webmproject.webp";
            case "video/mp4": return "public.mpeg-4";
            case "video/quicktime": return "com.apple.quicktime-movie";
            case "audio/mpeg": case "audio/mp3": return "public.mp3";
            case "audio/mp4": case "audio/m4a": return "com.apple.m4a-audio";
            case "text/plain": return "public.plain-text";
            case "application/pdf": return "com.adobe.pdf";
            case "application/zip": return "public.zip-archive";
            default: return raw;
        }
    }

    private static String guessFromName(String name) {
        if (name == null) return AirDropProtocol.OCTET_STREAM;
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        switch (extension) {
            case "jpg": case "jpeg": return "public.jpeg";
            case "png": return "public.png";
            case "heic": return "public.heic";
            case "gif": return "com.compuserve.gif";
            case "mp4": return "public.mpeg-4";
            case "mov": return "com.apple.quicktime-movie";
            case "mp3": return "public.mp3";
            case "txt": return "public.plain-text";
            case "pdf": return "com.adobe.pdf";
            case "zip": return "public.zip-archive";
            default: return AirDropProtocol.OCTET_STREAM;
        }
    }

    @Override
    public String toString() {
        return name + " (" + type + ", " + length + " B)";
    }
}
