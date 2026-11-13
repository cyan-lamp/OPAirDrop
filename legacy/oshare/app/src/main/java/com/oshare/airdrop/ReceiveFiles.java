package com.oshare.airdrop;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Publishes only completed file attachments, keeping partial MediaStore rows hidden. */
final class ReceiveFiles {
    static final String DIRECTORY = ".OPAirShare-incoming";
    private ReceiveFiles() {}

    static JSONArray attachments(Object target) throws Exception {
        JSONArray files = new JSONArray();
        List<?> attachments = (List<?>) target.getClass().getMethod("d").invoke(target);
        if (attachments.isEmpty() || attachments.size() > 256)
            throw new IllegalArgumentException("仅支持 1 至 256 个文件");
        for (Object attachment : attachments) {
            if (!attachment.getClass().getName().equals("com.google.android.gms.nearby.sharing.FileAttachment"))
                throw new IllegalArgumentException("暂不支持文本、网址或文件夹接收");
            Object uri = field(attachment, "d");
            files.put(new JSONObject().put("attachmentId", ((Number) field(attachment, "g")).longValue()).put("name", safeName(String.valueOf(field(attachment, "a"))))
                    .put("size", -1).put("estimatedSize", ((Number) field(attachment, "c")).longValue())
                    .put("mime", mime((String) field(attachment, "f")))
                    .put("source", uri == null ? "" : uri.toString()));
        }
        return files;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static File staging(Context context) throws Exception {
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIRECTORY);
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("无法创建接收暂存目录");
        return root.getCanonicalFile();
    }

    static String safeName(String name) {
        String value = name == null ? "" : name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")) value = "AirDrop file";
        // Keep filenames within common filesystem byte limits, preserving code points.
        while (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 220)
            value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
        return value;
    }

    private static String mime(String value) {
        return value != null && value.matches("[\\w.+-]+/[\\w.+-]+")
                ? value : "application/octet-stream";
    }

    static JSONArray publish(Context context, JSONArray files) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        // Snapshot once for the entire batch. A concurrent WebUI edit affects the next save.
        String configured = ReceiveDirectory.decode(android.provider.Settings.Global.getString(resolver, ReceiveDirectory.SETTING));
        List<Uri> created = new ArrayList<>();
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String type = mime(file.optString("mime"));
                Uri collection = type.startsWith("image/") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : type.startsWith("video/") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String folder = type.startsWith("image/") ? Environment.DIRECTORY_PICTURES
                        : type.startsWith("video/") ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
                String directory = configured.isEmpty() ? folder + "/OPAirShare" : configured;
                if (!configured.isEmpty()) collection = MediaStore.Files.getContentUri("external");
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(file.getString("name")));
                values.put(MediaStore.MediaColumns.MIME_TYPE, type);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri destination = resolver.insert(collection, values);
                if (destination == null) throw new IllegalStateException("无法创建保存文件");
                created.add(destination);
                long bytes = 0;
                long expected;
                try (Source source = openSource(context, file);
                     OutputStream out = resolver.openOutputStream(destination, "w")) {
                    InputStream in = source.input;
                    expected = source.size;
                    if (in == null || out == null) throw new IllegalStateException("无法读取已接收文件");
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = in.read(buffer)) != -1;) {
                        out.write(buffer, 0, count);
                        bytes = Math.addExact(bytes, count);
                    }
                }
                if (expected >= 0 && bytes != expected) throw new IllegalStateException("文件大小不一致：" + file.getString("name"));
                String name = file.getString("name");
                try (android.database.Cursor row = resolver.query(destination, new String[]{
                        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
                    if (row != null && row.moveToFirst()) { name = row.getString(0); directory = row.getString(1); }
                }
                result.put(new JSONObject().put("name", name).put("size", bytes)
                        .put("directory", directory).put("mime", type).put("uri", destination.toString()));
            }
            // Commit only after the entire batch has been copied and checked.
            for (Uri uri : created) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, values, null, null) != 1) throw new IllegalStateException("保存文件失败");
                context.grantUriPermission("com.coloros.oshare", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            cleanupStaging(context);
            return result;
        } catch (Exception failure) {
            for (Uri uri : created) try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            throw failure;
        }
    }

    /**
     * One received file as MediaStore stored it, in the terms a gallery shows it.
     *
     * <p>The name is read back after the commit rather than assumed: MediaStore resolves a
     * collision by renaming, so "IMG_0001.HEIC" arriving twice becomes "IMG_0001 (1).HEIC"
     * and only the row knows which. Reporting the name that was asked for would leave the
     * notification naming a file that does not exist.
     */
    static final class Published {
        final String name;
        final String directory;
        final long size;
        final Uri uri;

        Published(String name, String directory, long size, Uri uri) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.uri = uri;
        }

        @Override
        public String toString() {
            return directory + name + " (" + size + " B)";
        }
    }

    /**
     * Publishes files this process wrote itself -- an {@code /Upload} that
     * {@code Inbox} has already unwrapped, sanitised and bounded.
     *
     * <p>A sibling of {@link #publish} rather than a flag on it, because the two agree on
     * the MediaStore protocol and on nothing else. That one takes attachment descriptors
     * from the OShare UI and copies out of a {@code content://} URI it has to re-authorise
     * first; this one takes plain files in a directory this process owns. That one leaves
     * its source alone, because the sender's copy is not ours to delete; this one removes
     * the inbox copy once the file is published, which is what stops the inbox from
     * becoming a second, invisible copy of the gallery that grows until the volume fills.
     * And that one grants every URI to {@code com.coloros.oshare} because a different
     * package is about to display it; nothing outside this process displays these.
     *
     * <p>Publishing is best effort by design: a batch that fails is rolled back whole, the
     * inbox copies stay where they are, and the caller is told nothing was published. The
     * inbox is the durable copy -- reachable over MTP, counted against {@code Inbox}'s byte
     * budget -- so the worst outcome of a MediaStore refusal is a file in the wrong place
     * rather than a file that is gone. A half-published batch would be worse than either:
     * half the photos in the gallery and half nowhere the user looks.
     */
    static List<Published> publishInbox(Context context, List<File> files) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        // Snapshot once for the whole batch, exactly as publish() does: a settings change
        // made while a transfer is being filed belongs to the next transfer.
        String configured = ReceiveDirectory.decode(
            android.provider.Settings.Global.getString(resolver, ReceiveDirectory.SETTING));
        List<Uri> created = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        List<File> published = new ArrayList<>();
        try {
            for (File file : files) {
                // Directories are in the record too -- an archive can contain them -- and a
                // directory is not something a collection can hold.
                if (!file.isFile()) continue;
                String type = mimeOf(file);
                Uri collection = type.startsWith("image/") ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : type.startsWith("video/") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String folder = type.startsWith("image/") ? Environment.DIRECTORY_PICTURES
                        : type.startsWith("video/") ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
                String directory = configured.isEmpty() ? folder + "/OPAirShare" : configured;
                if (!configured.isEmpty()) collection = MediaStore.Files.getContentUri("external");
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(file.getName()));
                values.put(MediaStore.MediaColumns.MIME_TYPE, type);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri destination = resolver.insert(collection, values);
                if (destination == null) throw new IllegalStateException("无法创建保存文件：" + file.getName());
                created.add(destination);
                long expected = file.length();
                long bytes;
                try (InputStream in = new FileInputStream(file)) {
                    bytes = copyInto(resolver, destination, in);
                }
                if (bytes != expected) throw new IllegalStateException("文件大小不一致：" + file.getName());
                sizes.add(bytes);
                published.add(file);
            }
            // Commit only after the whole batch has been copied and checked, so a failure
            // leaves no visible row behind.
            for (Uri uri : created) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, values, null, null) != 1) throw new IllegalStateException("保存文件失败");
            }
            List<Published> result = new ArrayList<>();
            for (int i = 0; i < created.size(); i++) {
                Uri uri = created.get(i);
                String name = published.get(i).getName();
                String directory = "";
                try (android.database.Cursor row = resolver.query(uri, new String[]{
                        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
                    if (row != null && row.moveToFirst()) {
                        if (row.getString(0) != null) name = row.getString(0);
                        if (row.getString(1) != null) directory = row.getString(1);
                    }
                }
                result.add(new Published(name, trimSlash(directory), sizes.get(i), uri));
            }
            // Only now is the inbox copy redundant. Deleting before the commit would trade a
            // visible duplicate for an invisible loss if the update failed. A copy that
            // refuses to be deleted is left where it is: the gallery row is correct either
            // way, and the inbox has a byte budget rather than a promise.
            for (File file : published) file.delete();
            return result;
        } catch (Exception failure) {
            for (Uri uri : created) try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            throw failure;
        }
    }

    /** A phrase naming where a published batch went, for the notification and the trace. */
    static String describe(List<Published> saved) {
        List<String> places = new ArrayList<>();
        for (Published one : saved) if (!places.contains(one.directory)) places.add(one.directory);
        return String.join("、", places);
    }

    private static String trimSlash(String directory) {
        String value = directory == null ? "" : directory;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /**
     * The type MediaStore needs, from the extension alone.
     *
     * <p>From the extension and not from the content, because the alternative is a sniffing
     * pass over the first bytes of every received file -- another read of the whole transfer
     * before the user is told it arrived. The extension is what the sender chose and what the
     * gallery will use to decide which app opens the result, so it is also the field that has
     * to be right. A name with no extension falls back to the generic binary type, which lands
     * in Download, where a file whose type is unknown belongs.
     */
    private static String mimeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "application/octet-stream";
        String type = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return type != null && type.matches("[\\w.+-]+/[\\w.+-]+") ? type : "application/octet-stream";
    }

    private static long copyInto(ContentResolver resolver, Uri destination, InputStream in) throws Exception {
        long bytes = 0;
        try (OutputStream out = resolver.openOutputStream(destination, "w")) {
            if (out == null) throw new IllegalStateException("无法写入保存文件");
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, count);
                bytes = Math.addExact(bytes, count);
            }
        }
        return bytes;
    }

    private static final class Source implements AutoCloseable {
        final InputStream input;
        final long size;
        final android.content.res.AssetFileDescriptor descriptor;
        Source(InputStream input, long size, android.content.res.AssetFileDescriptor descriptor) {
            this.input = input; this.size = size; this.descriptor = descriptor;
        }
        @Override public void close() throws Exception {
            try { input.close(); } finally { if (descriptor != null) descriptor.close(); }
        }
    }

    private static void cleanupStaging(Context context) {
        try {
            File root = staging(context);
            File[] files = root.listFiles();
            if (files != null) for (File file : files)
                if (file.isFile() && file.getCanonicalFile().getParentFile().equals(root)) file.delete();
        } catch (Exception ignored) { /* Cleanup never changes a successful publication. */ }
    }

    private static Source openSource(Context context, JSONObject file) throws Exception {
        String source = file.optString("source");
        Uri uri = source.isEmpty() ? null : Uri.parse(source);
        if (uri != null && "content".equals(uri.getScheme())) {
            String authority = uri.getAuthority();
            if (!"media".equals(authority) && (authority == null || !authority.equals("com.google.android.mosey.fileprovider")))
                throw new SecurityException("非接收文件来源");
            android.content.res.AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (descriptor == null) throw new IllegalStateException("接收文件无法读取");
            try { return new Source(descriptor.createInputStream(), descriptor.getLength(), descriptor); }
            catch (Exception failure) { descriptor.close(); throw failure; }
        }
        File root = staging(context);
        File path = uri != null && "file".equals(uri.getScheme())
                ? new File(uri.getPath()).getCanonicalFile()
                : new File(root, safeName(file.getString("name"))).getCanonicalFile();
        if (!path.getPath().startsWith(root.getPath() + File.separator) || !path.isFile())
            throw new SecurityException("接收文件不在暂存目录内");
        return new Source(new FileInputStream(path), path.length(), null);
    }
}
