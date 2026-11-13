package com.oshare.airdrop;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.oshare.airdrop.core.Outgoing;
import com.oshare.airdrop.core.SharedNames;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads what the system share sheet handed us into the files {@code AirDropClient} sends.
 *
 * <p>The shape of the input is not guaranteed by anything. {@code EXTRA_STREAM} may be one
 * {@link Uri}, a list of them, or absent; the URIs may also be in the clip data, in which
 * case {@code EXTRA_STREAM} is often missing entirely. Some apps put a bare path string
 * where a {@code Uri} belongs. So the entry point collects from every place at once and
 * de-duplicates by URI, and anything it cannot read becomes nothing rather than an error.
 *
 * <p>Length is the awkward part: a cpio header declares the size before the bytes, so
 * every file needs a known length before the transfer starts. {@code OpenableColumns.SIZE}
 * usually answers, and an {@link AssetFileDescriptor}'s declared length covers most of the
 * rest, but a live pipe or a cloud-backed provider cannot say. Those are copied into the
 * cache directory once, which turns "unknown" into "measured" at the cost of one write.
 * Only the unknown cases pay it.
 *
 * <p>The stream is otherwise opened from the provider at send time, not held. The grant
 * that came with the intent lasts as long as the receiving activity, and the transfer runs
 * while that activity is on screen, so streaming straight out of the provider avoids
 * duplicating a multi-gigabyte video into the cache.
 */
public final class ShareIntake {
    /** What a text share with no subject is called. */
    private static final String TEXT_NAME = "shared-text.txt";

    private static final int COPY_BUFFER = 65536;

    private ShareIntake() {}

    /**
     * The files this intent offers, in the order the sender listed them.
     *
     * <p>An empty list means the intent carried nothing we know how to send — a text-only
     * share with no text, or a URI we cannot open. The caller decides whether that is
     * worth telling the user about.
     */
    public static List<Outgoing> read(Context context, Intent intent) throws IOException {
        if (context == null) throw new IllegalArgumentException("context == null");
        if (intent == null) return new ArrayList<>();
        // Held past the caller's lifetime: the provider is queried again while uploading,
        // and an activity context there would be a leak with a crash at the end of it.
        Context app = context.getApplicationContext();
        ContentResolver resolver = app.getContentResolver();
        String declared = declaredType(intent);

        Set<String> taken = new LinkedHashSet<>();
        List<Outgoing> files = new ArrayList<>();
        for (Uri uri : streams(intent)) {
            String name = displayName(resolver, uri);
            String type = mimeType(resolver, uri, declared, name);
            String member = SharedNames.unique(name, taken);
            long length = length(resolver, uri);
            // A source that cannot declare its size is copied once and then read from the
            // copy: the archive header needs the length before the bytes, and a pipe that
            // was drained to measure it cannot be drained again to send it.
            final File staged = length < 0 ? stage(app, uri, member) : null;
            if (staged != null) length = staged.length();
            final long size = length;
            files.add(Outgoing.of(member, type, size,
                staged != null ? () -> new FileInputStream(staged) : () -> open(app, uri)));
        }

        if (files.isEmpty()) {
            String text = text(intent);
            if (!text.isEmpty()) {
                String name = SharedNames.unique(textName(intent), taken);
                files.add(Outgoing.of(name, "text/plain", text.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return files;
    }

    /**
     * Every URI the intent carries, from both {@code EXTRA_STREAM} and the clip data.
     *
     * <p>A share of one photo arrives as {@code ACTION_SEND} with a single stream; several
     * photos arrive as {@code ACTION_SEND_MULTIPLE} with a list; and some senders fill only
     * the clip data. Reading all three and de-duplicating is the only way to not drop files
     * depending on which app did the sharing.
     */
    static List<Uri> streams(Intent intent) {
        List<Uri> found = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        boolean many = Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction());
        if (many) {
            for (Uri uri : parcelableList(intent)) add(found, seen, uri);
        } else {
            add(found, seen, parcelableStream(intent));
        }
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                add(found, seen, clip.getItemAt(index).getUri());
            }
        }
        return found;
    }

    private static ArrayList<Uri> parcelableList(Intent intent) {
        try {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            return list == null ? new ArrayList<>() : list;
        } catch (RuntimeException wrongType) {
            // A sender that stuffed something else in there; the clip data still works.
            return new ArrayList<>();
        }
    }

    private static Uri parcelableStream(Intent intent) {
        try {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } catch (RuntimeException wrongType) {
            // Some apps put a plain path string where a Uri belongs.
            try {
                String path = intent.getStringExtra(Intent.EXTRA_STREAM);
                return path == null || path.trim().isEmpty() ? null : Uri.fromFile(new File(path.trim()));
            } catch (RuntimeException stillWrong) {
                return null;
            }
        }
    }

    /** Keeps the first occurrence of each URI; a clip item often repeats the stream. */
    private static void add(List<Uri> found, List<String> seen, Uri uri) {
        Uri usable = normalize(uri);
        if (usable == null) return;
        String key = usable.toString();
        if (seen.contains(key)) return;
        seen.add(key);
        found.add(usable);
    }

    /**
     * A URI we can open: {@code content} from a provider, {@code file} from disk, or a
     * scheme-less value that is really a path ({@code /sdcard/DCIM/x.jpg}).
     */
    private static Uri normalize(Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if (scheme == null) {
            String path = uri.toString();
            return path.startsWith("/") ? Uri.fromFile(new File(path)) : null;
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (lower.equals("content") || lower.equals("file") || lower.equals("android.resource")) {
            return uri;
        }
        return null;
    }

    static String displayName(ContentResolver resolver, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            // Not queryable, and the path already says the name.
            return new File(uri.getPath() == null ? "" : uri.getPath()).getName();
        }
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (RuntimeException refused) {
            // Providers may refuse the query; the URI's last segment is a usable guess.
        }
        String tail = uri.getLastPathSegment();
        return tail == null || tail.trim().isEmpty() ? SharedNames.FALLBACK : tail;
    }

    /** The size in bytes, or {@code -1} when nothing can say without reading it all. */
    static long length(ContentResolver resolver, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            File file = new File(uri.getPath() == null ? "" : uri.getPath());
            return file.isFile() ? file.length() : -1L;
        }
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                long size = cursor.getLong(0);
                if (size >= 0) return size;
            }
        } catch (RuntimeException refused) {
            // Fall through to the descriptor.
        }
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null) {
                long declared = descriptor.getDeclaredLength();
                if (declared >= 0) return declared;
                long full = descriptor.getLength();
                if (full >= 0) return full;
            }
        } catch (IOException | RuntimeException noDescriptor) {
            // A provider that cannot even open a descriptor has no length to declare.
        }
        return -1L;
    }

    /**
     * The type to announce, preferring what the provider says over what the intent claims.
     *
     * <p>{@code * / *} is what a sender writes when it does not know, and passing that on
     * would make the receiver choose no handler at all, so it counts as "unknown" here and
     * the extension gets a turn before {@link Outgoing} falls back to a byte stream.
     */
    static String mimeType(ContentResolver resolver, Uri uri, String declared, String name) {
        String type = null;
        try {
            type = resolver.getType(uri);
        } catch (RuntimeException refused) {
            // Treat as unknown.
        }
        if (unknown(type)) type = declared;
        if (unknown(type)) type = fromExtension(name);
        return unknown(type) ? null : type;
    }

    private static boolean unknown(String type) {
        if (type == null) return true;
        String trimmed = type.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() || trimmed.equals("*/*") || trimmed.equals("application/unknown");
    }

    private static String fromExtension(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }

    private static String declaredType(Intent intent) {
        String type = intent.getType();
        return unknown(type) ? null : type;
    }

    /** The shared text, or empty. Only the plain-text extra: HTML is a different problem. */
    static String text(Intent intent) {
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        return text == null ? "" : text.toString();
    }

    /** Text shares are filed under their subject when the sender gives one. */
    private static String textName(Intent intent) {
        CharSequence subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        String trimmed = subject == null ? "" : subject.toString().trim();
        if (trimmed.isEmpty()) return TEXT_NAME;
        return trimmed.toLowerCase(Locale.ROOT).endsWith(".txt") ? trimmed : trimmed + ".txt";
    }

    /** Copies a provider stream into the cache so its length stops being a mystery. */
    private static File stage(Context context, Uri uri, String member) throws IOException {
        File directory = new File(context.getCacheDir(), "share-intake/" + System.nanoTime());
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("cannot create " + directory);
        }
        File target = new File(directory, member);
        try (InputStream input = open(context, uri);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[COPY_BUFFER];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return target;
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) {
            return new FileInputStream(new File(uri.getPath() == null ? "" : uri.getPath()));
        }
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("provider returned no stream for " + uri);
        return input;
    }
}
