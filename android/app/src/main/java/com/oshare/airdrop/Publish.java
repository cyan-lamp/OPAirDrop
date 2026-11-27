package com.oshare.airdrop;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p># 重名不让这一份消失
 *
 * <p>插入时把名字交给媒体库去重是不够的。它自己会在重名时试 {@code name (1).ext} 到
 * {@code name (32).ext}，全占满就抛 {@code Failed to build unique file}；更要紧的是它
 * 的「这个名字空着吗」是一次查询，而门口现在同时站着好几条连接（见 {@code Listener}
 * 的类注释）——两条连接对同一个名字各查一次，都得到「空着」，然后一起去建同一个文件。
 * 2026-11-21 的日志正是这个形状：四条失败落在 {@code 26.080} 到 {@code 26.156} 的
 * 七十六毫秒里，而单份 5.7 MB 的串行拷贝要几百毫秒，它们只能是并发撞的。
 *
 * <p>所以这里自己负责这件事：撞上了就换一个号再插（见 {@link #numbered}）。序号从 1
 * 顺次试，不是从计数器取，因为需要的是「最小可用名」，不是「下一个没用过的数」——
 * 目录里的 {@code (1)} 可能早就被上一轮占了。
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

    /**
     * 给暂存目录的名字加的不重号。
     *
     * <p>光靠时间戳不够了：门口现在同时站着好几条连接（见 {@code Listener} 的类注释），
     * 而两条在同一个毫秒里要目录是完全可能的。那种情况下 {@code mkdirs()} 第二次会返回
     * false——第二条连接于是死在一句「暂存目录建不出来」上，而那句话对谁都不成立。
     *
     * <p>进程内计数器就够：这台设备上只有一个应用进程会建暂存目录，而重启之后计数器从 1
     * 重新开始，时间戳却已经换了一个。
     */
    private static final AtomicLong STAGES = new AtomicLong();

    private static final int BUFFER = 64 * 1024;

    private Publish() {}

    /**
     * 这一次接收的暂存目录。
     *
     * <p>建在应用自己的外部目录里：那里不需要权限，和公共目录同一个分区，而且它本来
     * 就是这个应用唯一一直写的地方。每次一个时间戳子目录，上一轮万一没清干净，也不会和
     * 这一轮的文件名撞上。时间戳后面再跟一个进程内序号：同一个毫秒里两条连接都要目录时，
     * 只有时间戳的名字是一样的（见 {@link #STAGES}）。
     */
    static File stage(Context context) throws IOException {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File root = new File(base != null ? base : context.getFilesDir(), STAGING);
        File directory = new File(root, Long.toString(System.currentTimeMillis(), 36)
                + "-" + STAGES.incrementAndGet());
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
    /**
     * 落盘发布：把暂存目录里的文件搬进公共目录并插入媒体库。
     *
     * <p>2026-11-27：**这一段必须串行**。传输本身可以并行（每条连接写自己的暂存目录），
     * 但「搬进公共目录 + 媒体库 IS_PENDING 提交 + 同名去重」共用同一批系统状态，两条并发
     * 时真机实测两份都没进相册。所以只锁发布这一段，传输不再排队——这正是方案 B 需要的形态。
     */
    static synchronized Result run(Context context, Report report, File staged, String configured, boolean overwrite)
            throws IOException {
        String fixed = configured == null ? "" : configured.trim();
        List<Entry> entries = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String reason = "";
        long landed = 0;
        // 同一个名字在一次 offer 里出现多遍是常见形状：对端把一张照片加进选择两次、或者
        // 在「转换后再发」的流程里把原图和转换结果列在一起（见 2026-11-21 那条 10 项、
        // 只有 2 个唯一名的 offer）。库里按名字落盘，所以那 5 个同名成员写到的是暂存区里
        // 的同一个文件——搬第 2 遍开始就是同一份字节的重复拷贝，而每一次都要在公共目录里
        // 再抢一次同一个名字，撞车的次数随重复数平方上涨。
        //
        // 这里按名字收一次。丢掉的不是内容：那些成员本来就是同一份。
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        // 实况照片的识别与合并（2026-11-26，iPhone 真机取证：IMG_0232.JPG + IMG_0232.MOV
        // + metadata.plist 三件平级成员）。同 stem 的图+视频配对 → 合成 Motion Photo
        // （视频嵌进图片尾部，相册长按即播），视频与包标记 plist 不再单独落库。
        // 实况照片（2026-11-26）：识别同 stem 的图+视频配对与包标记 plist。配对识别
        // 与 Motion Photo 合成（MotionPhoto.make）已经完成并被 MediaProvider 判定通过
        // （o_is_live_photo=1），但 ColorOS 相册的 UI 显示还有最后一层私有逻辑没有
        // 打通（判定列=1 的条目相册仍不显示实况角标）——在那一层解决之前，视频照常
        // 单独落库（Movies），保证图和视频两样东西用户都拿得到；合成逻辑保留在
        // MotionPhoto/Publish 的代码路径里，带 MotionPhotoInfo 的图片入库一并生效。
        java.util.Map<String, String> livePairs = MotionPhoto.pairs(report.names());
        java.util.Set<String> merged = new java.util.HashSet<>();
        for (String name : report.names()) {
            if (MotionPhoto.isPlistMarker(name) || MotionPhoto.isAppleDouble(name)) {
                merged.add(name);
            }
        }
        // 配对视频并入合成图后不再单独落库（OPPO 格式实测四家全通，图里已含视频）。
        merged.addAll(livePairs.keySet());
        Log.i(TAG, "实况配对输入：names=" + report.names() + " -> pairs=" + livePairs);
        if (!livePairs.isEmpty()) {
            Log.i(TAG, "实况照片 " + livePairs.size() + " 组：图与视频分别入库");
        }
        // 合成结果（图 stem -> Motion）：入库时带 Oplus 扩展列（o_is_live_photo 等），
        // MediaProvider 判定层已打通；相册 UI 的显示待其私有逻辑打通后自动生效。
        java.util.Map<String, MotionPhoto.Motion> liveMotion = new java.util.HashMap<>();

        for (String name : report.names()) {
            if (!seen.add(name)) {
                continue;
            }
            // Live Photo 包的标记文件（metadata.plist）：不是内容，不落库。
            if (merged.contains(name)) {
                continue;
            }
            File source = new File(staged, name);
            // 成员的另一种形态是目录（cpio 里的 TYPE_DIRECTORY），它没有字节可搬；而
            // 「名字在报告里、文件却不在」只可能是库里中途放弃了这个成员，两种情况都不该
            // 在公共目录里留下一份空文件。
            if (!source.isFile()) {
                continue;
            }
            // 配对图：把视频嵌进图片（Motion Photo），字节写回暂存的同一位——暂存目录
            // 发布完就删，覆盖无副作用；合成失败保持原图照常落库，视频那一份也照常
            // 落库（两样东西都不丢）。
            if (livePairs.containsValue(name)) {
                try {
                    String videoName = null;
                    for (java.util.Map.Entry<String, String> pair : livePairs.entrySet()) {
                        if (pair.getValue().equals(name)) {
                            videoName = pair.getKey();
                        }
                    }
                    if (videoName != null) {
                        // Oplus 扫描器的硬条件：EXIF 的 UserComment 必须 oplus_ 开头
                        // （checkOplusTag，反编译 ModernMediaScanner 确认）。原来这里换的是
                        // 一整段 OPPO EXIF 模板；现在保留原图的 EXIF（它带着 Apple 的
                        // MakerNote 与 Live Photo 的 ContentIdentifier，见 MotionPhoto.make），
                        // 所以只改这一个字段。必须在 make() 之前：saveAttributes 重写文件会
                        // 截掉 EOI 之后的东西，而 make() 正是要在那里嵌视频。
                        android.media.ExifInterface exif =
                                new android.media.ExifInterface(source.getPath());
                        exif.setAttribute("UserComment", "oplus_" + source.getName());
                        exif.saveAttributes();
                        MotionPhoto.Motion motion = MotionPhoto.make(
                                readAll(source), readAll(new File(staged, videoName)));
                        java.nio.file.Files.write(source.toPath(), motion.data);
                        liveMotion.put(name, motion);
                        Log.i(TAG, "实况照片已合成：" + source.getName() + "（含 "
                                + motion.data.length / 1024 + " KiB，视频 "
                                + motion.durationMs + "ms " + motion.width + "x" + motion.height + "）");
                    }
                } catch (IOException | RuntimeException failure) {
                    Log.w(TAG, "Motion Photo 合成失败，图按原图落库：" + failure);
                    for (java.util.Map.Entry<String, String> pair : livePairs.entrySet()) {
                        if (pair.getValue().equals(name)) {
                            merged.remove(pair.getKey());
                        }
                    }
                }
            }
            // 实况照片的 Oplus 扩展列：相册的实况判定读 MediaStore 行的 o_is_live_photo
            // 与 o_video_*（2026-11-26 实测：文件 XMP 对齐后相册仍不认，列才是开关）。
            // 这几列在条目插入时一起给（owner 写自己的行），事后补写会被拒。
            ContentValues extra = null;
            MotionPhoto.Motion motion = liveMotion.get(name);
            if (motion != null) {
                extra = new ContentValues();
                extra.put("o_is_live_photo", 1);
                extra.put("o_video_size", motion.data.length);
                extra.put("o_video_duration", motion.durationMs);
                extra.put("o_video_width", motion.width);
                extra.put("o_video_height", motion.height);
            }
            Bucket bucket = fixed.isEmpty() ? Bucket.of(name) : null;
            String directory = bucket == null ? fixed : bucket.root + "/" + FOLDER;
            try {
                Entry entry = save(context, source, source.getName(), bucket, directory,
                        overwrite, extra);
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
                              String directory, boolean overwrite, ContentValues extra)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String mime = mimeOf(name);
        Uri collection = bucket == null
                ? MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : bucket.collection;
        if (overwrite) {
            replace(resolver, collection, name, directory);
        }

        Uri uri = insert(resolver, collection, name, mime, directory, extra);

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
     * 在公共目录里要一行，重名就让号。
     *
     * <p>媒体库给的名字只在插入那一刻核对一次，而我们门口同时站着好几条连接，所以
     * 「名字空着」这个答案随时可能过期。撞上了它抛 {@link IllegalStateException}
     * （消息是 {@code Failed to build unique file}），这里接住、改一个号、再要一次。
     *
     * <p>只接这一种失败。其它 {@code RuntimeException}——没有权限、卷卸了、列不存在——
     * 换成别的名字也一样过不去，重试只会把真正的原因埋到第三十二次之后。
     */
    private static Uri insert(ContentResolver resolver, Uri collection, String name, String mime,
                              String directory, ContentValues extra) throws IOException {
        RuntimeException refusal = null;
        for (String candidate : numbered(name)) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, candidate);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (extra != null) {
                values.putAll(extra);
            }
            try {
                Uri uri = resolver.insert(collection, values);
                if (uri == null) {
                    // 不是「名字被占」的形态（那是抛异常），而是这一卷根本不给建行。
                    throw new IOException("无法在「" + directory + "」里创建文件");
                }
                return uri;
            } catch (IllegalStateException taken) {
                String message = taken.getMessage();
                if (message == null || !message.contains("unique file")) {
                    throw taken;
                }
                refusal = taken;
            }
        }
        throw new IOException("「" + name + "」在「" + directory + "」里连着三十二个名字都被占了",
                refusal);
    }

    /**
     * 一轮候选名：原名，然后 {@code name (1).ext} 到 {@code name (32).ext}。
     *
     * <p>三十二是媒体库自己的上限（它内部就是这个数），这里不比它少也不比它多：少一个
     * 就可能在它还能成的地方先失败，多一个则它一定先抛。
     *
     * <p>序号插在扩展名前面而不是末尾：{@code clip.mp4 (1)} 不是视频，相册按扩展名认
     * 类型，那样存下去就是一个打不开的条目。
     */
    private static Iterable<String> numbered(String name) {
        List<String> candidates = new ArrayList<>(33);
        candidates.add(name);
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 || dot == name.length() - 1 ? name : name.substring(0, dot);
        String suffix = dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot);
        for (int number = 1; number <= 32; number++) {
            candidates.add(stem + " (" + number + ")" + suffix);
        }
        return candidates;
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

    /** 整文件读进内存（合成 Motion Photo 时的 JPG 与 MOV 都不大：视频几 MiB 级）。 */
    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[64 << 10];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

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
