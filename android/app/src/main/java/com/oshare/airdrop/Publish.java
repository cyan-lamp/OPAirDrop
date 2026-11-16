package com.oshare.airdrop;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把收到的文件放进公共目录：照片与视频进 {@code DCIM/AirDrop}，其余进
 * {@code Download/AirDrop}；{@code receive.directory} 填了目录则全部进那一个。
 *
 * <p># 为什么不能直接写文件
 *
 * <p>这个应用是 {@code targetSdk 37}，从 Android 11 起，公共目录就不再是「路径」而是
 * 「媒体库里的行」：{@code File} 往 {@code /sdcard/Pictures} 里写会被 FUSE 直接拒掉。
 * 唯一的路是 {@link ContentResolver#insert} —— 先插一行，拿到内容 Uri，再往里写字节。
 * 实测过四个目标路径（{@code Pictures/}、{@code Movies/}、{@code Documents/}、
 * {@code Download/}）都收，而绝对路径 {@code /sdcard/Pictures/OPAirDrop} 会被改写成
 * {@code (invalid)/sdcard/...}，所以这里只传相对路径。
 *
 * <p># 为什么要过一次暂存
 *
 * <p>库（{@link Native}）按路径写文件，而且它的价值有一半在那些路径规则上：先写
 * {@code .name.partial} 再原子改名、成员名不许逃出接收目录、一个成员写完才开下一个。
 * 把这些换成「往一个 fd 里写」需要库侧再开一套接口，而收益只是省一次同分区的顺序拷贝。
 * 所以库仍旧写到应用自己的目录（那里不需要任何权限），写完再由这里搬到公共目录。
 *
 * <p># 为什么先挂着 IS_PENDING
 *
 * <p>插入之后、字节写完之前，那一行在媒体库里是半成品：相册会去缩略图它，文件管理器
 * 会让人打开一个长度不对的文件。{@code IS_PENDING = 1} 把它们藏起来，整份拷完并核对过
 * 字节数才置 0，也就是「要么看不见，要么就是完整的一份」。
 *
 * <p># 失败怎么算
 *
 * <p>一个文件存不下不牵连别的：这一批里成功的留在公共目录，失败的记在 {@link Result}
 * 里，由调用方说给用户听。整批回滚（legacy 的 mosey 版就是那么做的）在「收到五个、
 * 存下三个」的时候会连那三个一起删掉，而那三个已经是这人手里的东西了。
 */
final class Publish {

    private static final String TAG = "OPAirDrop";

    /**
     * 落在公共目录下的那层文件夹名。
     *
     * <p>2026-11-16 从 {@code OPAirDrop} 改成 {@code AirDrop}，同时把落点收成两处：照片与视频进
     * {@code DCIM/AirDrop}（相册默认就扫这里，收完能在相册里直接看见），其余一切进
     * {@code Download/AirDrop}（那是唯一一个什么都收的地方）。
     */
    private static final String FOLDER = "AirDrop";

    /** 一次传输一个暂存目录，收完就删。 */
    private static final String STAGING = "_staging";

    private static final int BUFFER = 64 * 1024;

    private Publish() {}

    /**
     * 这一次接收的暂存目录。
     *
     * <p>建在应用自己的外部目录里：那里不需要权限，和公共目录同一个分区，而且它本来
     * 就是这个应用唯一一直写的地方。每次一个时间戳子目录，
     * 上一轮万一没清干净，也不会和这一轮的文件名撞上。
     */
    static File stage(Context context) throws IOException {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File root = new File(base != null ? base : context.getFilesDir(), STAGING);
        File directory = new File(root, Long.toString(System.currentTimeMillis(), 36));
        if (!directory.mkdirs()) {
            throw new IOException("暂存目录建不出来：" + directory);
        }
        return directory;
    }

    /** 删掉一棵暂存树。发布成功后调用；失败时也调用，半截文件不该留着占地方。 */
    static void discard(File directory) {
        if (directory == null) {
            return;
        }
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                discard(child);
            }
        }
        if (!directory.delete()) {
            Log.w(TAG, "staged file left behind: " + directory);
        }
    }

    /**
     * 页面上「接收目录」那一行，留空时显示的就是这个。
     *
     * <p>写成一类一句而不是把目录并排列出来：那一行只有 13.5sp，两条
     * {@code Xxx/AirDrop} 连起来已经是一堵墙，而人真正要认的是「照片进相册」这件事。
     */
    static String describe() {
        return "照片和视频进 " + Bucket.PHOTO.root + "/" + FOLDER + "，其余进 "
                + Bucket.OTHER.root + "/" + FOLDER;
    }

    /**
     * 把暂存目录里的一份份文件搬到公共目录。
     *
     * @param configured {@code receive.directory} 的值；非空则全部进那一个目录，不再按类型分
     * @param overwrite  同名同目录的行先删掉，让新的一份顶上去。媒体库自己遇到重名会改名成
     *                   {@code name (1).ext}，那是它替人做的决定，不是这个开关的意思
     * @return 每一份最终落在哪儿，以及哪些没存下
     * @throws IOException 一份都没存下时抛出第一个原因；存下了一部分则不抛
     */
    static Result run(Context context, Report report, File staged, String configured, boolean overwrite)
            throws IOException {
        String fixed = configured == null ? "" : configured.trim();
        List<Entry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String reason = "";
        long landed = 0;
        for (String name : report.names()) {
            File source = new File(staged, name);
            // 成员的另一种形态是目录（cpio 里的 TYPE_DIRECTORY），它没有字节可搬；而
            // 「名字在报告里、文件却不在」只可能是库里中途放弃了这个成员，两种情况都不该
            // 在公共目录里留下一份空文件。
            if (!source.isFile()) {
                continue;
            }
            Bucket bucket = fixed.isEmpty() ? Bucket.of(name) : null;
            String directory = bucket == null ? fixed : bucket.root + "/" + FOLDER;
            try {
                Entry entry = save(context, source, source.getName(), bucket, directory, overwrite);
                entries.add(entry);
                landed += entry.size;
            } catch (IOException | RuntimeException failure) {
                Log.w(TAG, "could not publish " + name + ": " + failure);
                skipped.add(source.getName());
                if (reason.isEmpty()) {
                    reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                }
            }
        }
        if (entries.isEmpty() && !reason.isEmpty()) {
            throw new IOException(reason);
        }
        // 报的是真正落到公共目录的字节数，不是收到的：有文件没存下时，这两个数不一样，
        // 而通知上那句话说的是「存到」，不该把没存下的算进去。
        return new Result(entries, landed, skipped, reason);
    }

    /**
     * 一份文件的一次发布。
     *
     * <p>插入、写完、核对、提交，四步任何一步失败都把这一行删掉——删的是这一行，不是
     * 已经提交过的别的行。
     */
    private static Entry save(Context context, File source, String name, Bucket bucket,
                              String directory, boolean overwrite) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String mime = mimeOf(name);
        Uri collection = bucket == null
                ? MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : bucket.collection;
        if (overwrite) {
            replace(resolver, collection, name, directory);
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("无法在「" + directory + "」里创建文件");
        }

        long copied = 0;
        try {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) {
                    throw new IOException("「" + directory + "」不给写");
                }
                byte[] buffer = new byte[BUFFER];
                for (int count; (count = in.read(buffer)) != -1; ) {
                    out.write(buffer, 0, count);
                    copied += count;
                }
            }
            if (copied != source.length()) {
                throw new IOException("写进去的字节数和收到的对不上：" + name);
            }
            ContentValues committed = new ContentValues();
            committed.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, committed, null, null) != 1) {
                throw new IOException("保存「" + name + "」失败");
            }
        } catch (IOException | RuntimeException failure) {
            try {
                resolver.delete(uri, null, null);
            } catch (RuntimeException ignored) {
                // 删不掉也不能盖住真正的原因，那一行本来就还挂着 IS_PENDING，媒体库看不见它。
            }
            throw failure;
        }

        // 系统可能改过名字（重名时加 (1)），也可能把路径挪回规范的位置。报告里的必须是
        // 文件真正叫什么，而不是我们请求的名字——否则通知上那句话立刻就是假的。
        String finalName = name;
        String finalDirectory = directory;
        try (Cursor row = resolver.query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH},
                null, null, null)) {
            if (row != null && row.moveToFirst()) {
                if (!row.isNull(0)) {
                    finalName = row.getString(0);
                }
                if (!row.isNull(1)) {
                    finalDirectory = row.getString(1);
                }
            }
        }
        return new Entry(finalName, strip(finalDirectory), copied, uri);
    }

    /**
     * 打开覆盖时，先让位。
     *
     * <p>只删同一个目录下的同名行。这个开关本来就是「再发一次，用新的那份」，而媒体库的
     * 做法是留两份：{@code IMG_0001.jpg} 和 {@code IMG_0001 (1).jpg}，相册里并排出现。
     */
    private static void replace(ContentResolver resolver, Uri collection, String name, String directory) {
        try (Cursor row = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{strip(directory) + "/", name}, null)) {
            if (row == null) {
                return;
            }
            List<Long> ids = new ArrayList<>();
            while (row.moveToNext()) {
                ids.add(row.getLong(0));
            }
            for (long id : ids) {
                resolver.delete(Uri.withAppendedPath(collection, Long.toString(id)), null, null);
            }
        } catch (RuntimeException ignored) {
            // 删不掉就按不覆盖走：多一份比丢一份好，而这不是值得让整次接收失败的事。
        }
    }

    /** {@code RELATIVE_PATH} 是带尾斜杠的（{@code Pictures/OPAirDrop/}），话里不带。 */
    private static String strip(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1
                ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /**
     * 这个文件名进哪一类。
     *
     * <p>按扩展名分，不按 MIME：库给回来的是名字，对端也可能什么都不声明，而一个
     * {@code .mov} 是视频这件事在两边都成立。认不出来的进「其他」，也就是下载目录——
     * 那是唯一一个「什么都收」的地方。
     */
    private static String mimeOf(String name) {
        String type = TYPES.get(extension(name));
        return type == null ? "application/octet-stream" : type;
    }

    /**
     * 三类落点：照片、视频、其余。
     *
     * <p>前两类的公共目录根都是 {@code DCIM}——相册扫的是它，不是 {@code Pictures}。声音、文档、
     * 压缩包一律进 {@code Download}：把它们混进 DCIM 只会让相册里多出一堆打不开的条目。
     */
    private enum Bucket {
        PHOTO(Environment.DIRECTORY_DCIM, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
        VIDEO(Environment.DIRECTORY_DCIM, MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
        OTHER(Environment.DIRECTORY_DOWNLOADS, MediaStore.Downloads.EXTERNAL_CONTENT_URI);

        final String root;
        final Uri collection;

        Bucket(String root, Uri collection) {
            this.root = root;
            this.collection = collection;
        }

        static Bucket of(String name) {
            String extension = extension(name);
            if (PHOTOS.contains(extension)) {
                return PHOTO;
            }
            if (VIDEOS.contains(extension)) {
                return VIDEO;
            }
            return OTHER;
        }
    }

    private static final java.util.Set<String> PHOTOS = set(
            "jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
            "dng", "tif", "tiff", "raw", "cr2", "nef", "arw", "orf", "rw2");

    private static final java.util.Set<String> VIDEOS = set(
            "mp4", "mov", "m4v", "mkv", "webm", "3gp", "3g2", "avi", "wmv", "flv",
            "mpg", "mpeg", "ts", "mts", "m2ts");

    /** 扩展名到 MIME。查不到的一律 {@code application/octet-stream}。 */
    private static final java.util.Map<String, String> TYPES = types();

    private static java.util.Set<String> set(String... values) {
        return java.util.Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(java.util.Arrays.asList(values)));
    }

    private static java.util.Map<String, String> types() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        String[][] rows = {
                // 图
                {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"jpe", "image/jpeg"},
                {"png", "image/png"}, {"gif", "image/gif"}, {"webp", "image/webp"},
                {"bmp", "image/bmp"}, {"heic", "image/heic"}, {"heif", "image/heif"},
                {"avif", "image/avif"}, {"dng", "image/x-adobe-dng"}, {"tif", "image/tiff"},
                {"tiff", "image/tiff"}, {"raw", "image/x-raw"},
                // 视频
                {"mp4", "video/mp4"}, {"mov", "video/quicktime"}, {"m4v", "video/x-m4v"},
                {"mkv", "video/x-matroska"}, {"webm", "video/webm"}, {"3gp", "video/3gpp"},
                {"3g2", "video/3gpp2"}, {"avi", "video/x-msvideo"}, {"wmv", "video/x-ms-wmv"},
                {"flv", "video/x-flv"}, {"mpg", "video/mpeg"}, {"mpeg", "video/mpeg"},
                {"ts", "video/mp2t"}, {"mts", "video/mp2t"}, {"m2ts", "video/mp2t"},
                // 音频
                {"mp3", "audio/mpeg"}, {"m4a", "audio/mp4"}, {"aac", "audio/aac"},
                {"wav", "audio/x-wav"}, {"flac", "audio/flac"}, {"ogg", "audio/ogg"},
                {"oga", "audio/ogg"}, {"opus", "audio/opus"}, {"amr", "audio/amr"},
                {"aiff", "audio/x-aiff"}, {"aif", "audio/x-aiff"}, {"wma", "audio/x-ms-wma"},
                // 文档
                {"txt", "text/plain"}, {"md", "text/markdown"}, {"csv", "text/csv"},
                {"pdf", "application/pdf"}, {"doc", "application/msword"},
                {"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
                {"rtf", "application/rtf"}, {"odt", "application/vnd.oasis.opendocument.text"},
                {"xls", "application/vnd.ms-excel"},
                {"xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
                {"ods", "application/vnd.oasis.opendocument.spreadsheet"},
                {"ppt", "application/vnd.ms-powerpoint"},
                {"pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
                {"odp", "application/vnd.oasis.opendocument.presentation"},
                {"epub", "application/epub+zip"},
                // 其他
                {"zip", "application/zip"}, {"rar", "application/vnd.rar"},
                {"7z", "application/x-7z-compressed"}, {"tar", "application/x-tar"},
                {"gz", "application/gzip"}, {"bz2", "application/x-bzip2"},
                {"xz", "application/x-xz"}, {"apk", "application/vnd.android.package-archive"},
                {"ipa", "application/octet-stream"}, {"iso", "application/x-iso9660-image"},
                {"dmg", "application/x-apple-diskimage"}, {"exe", "application/vnd.microsoft.portable-executable"},
                {"bin", "application/octet-stream"}, {"json", "application/json"},
                {"xml", "application/xml"}, {"html", "text/html"}, {"htm", "text/html"},
        };
        for (String[] row : rows) {
            map.put(row[0], row[1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /** 一份文件在公共目录里的最终身份。 */
    static final class Entry {
        final String name;
        /** 相对公共根，例如 {@code Pictures/OPAirDrop}；不带尾斜杠。 */
        final String directory;
        final long size;

        /**
         * 媒体库给这一行的地址，{@code content://media/…}。
         *
         * <p>它存在的唯一理由是「打开文件」那个按钮。这个应用收完就把字节交给媒体库，
         * 自己手上不再有可共享的路径，而 {@code file://} 从 Android 7 起就不许穿过进程
         * 边界了——所以能指向这份文件、又能被别的应用读到的，只有这一串。
         *
         * <p>不留下来的代价过去已经出现过：按钮画得出、点下去什么也不发生，因为事件里
         * 每一份都没有 {@code uri}，而 {@code openFiles} 只认 {@code content} 开头的。
         */
        final Uri uri;

        Entry(String name, String directory, long size, Uri uri) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.uri = uri;
        }
    }

    /** 一次发布的全部结果。 */
    static final class Result {
        private final List<Entry> entries;
        private final long bytes;
        private final List<String> skipped;
        private final String reason;

        Result(List<Entry> entries, long bytes, List<String> skipped, String reason) {
            this.entries = java.util.Collections.unmodifiableList(entries);
            this.bytes = bytes;
            this.skipped = java.util.Collections.unmodifiableList(skipped);
            this.reason = reason;
        }

        List<Entry> entries() {
            return entries;
        }

        long bytes() {
            return bytes;
        }

        List<String> skipped() {
            return skipped;
        }

        String reason() {
            return reason;
        }
    }
}
