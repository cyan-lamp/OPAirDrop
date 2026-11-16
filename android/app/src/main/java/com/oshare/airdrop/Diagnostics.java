package com.oshare.airdrop;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 诊断日志的落盘。
 *
 * <p># 为什么这件事以前不存在
 *
 * <p>全项目此前没有一行落盘日志：91 处 {@code Log.i/w/e} 全部只进 logcat，而 logcat 是开发者
 * 的东西——它绕在环形缓冲里，拔了线就没了，普通用户也没有一个能读到它的入口。于是「出问题时
 * 日志记到多细」这个设置项一直没有消费者：旋钮在，线没接。这里接的就是那根线。
 *
 * <p># 为什么写 {@code Documents/AirDrop}
 *
 * <p>三个候选里只有它合适。{@code Download} 是收文件的落点，把日志混进去会让人在下拉列表里
 * 看见一堆 {@code .log}；应用私有目录（{@code /data/data/…}）没有 root 打不开，而「能拿到
 * 日志」正是这件事的全部意义。{@code Documents} 是系统文件管理器里一级可见、又不与任何一类
 * 媒体抢位置的地方，人插上电脑就能拿走。
 *
 * <p># 为什么不直接用 {@link java.io.File}
 *
 * <p>写不了。{@code targetSdk} 37 是 scoped storage：公共目录下建文件只认 {@link MediaStore}
 * ——先插一行拿到内容 Uri，再往这个 Uri 里写字。直接 {@code new FileOutputStream(
 * "/storage/emulated/0/Documents/AirDrop/x.log")} 会 {@code EPERM}。绝对路径连"被改写成
 * {@code (invalid)/…}"都轮不到，那是用 {@code File} 建目录时才会看到的现象。做法与
 * {@link Publish} 写收件目录那条路完全一致，只是那边写一次就完，这边要反复追加。
 *
 * <p># 为什么按天分文件
 *
 * <p>一个文件写到几百 MB 之后，人打不开、也不好发给别人。按天切，最坏情况是一天的量；文件名
 * 里带日期，翻"昨天那次失败"时不必在一条时间轴上找位置。旧文件不自动删：日志是证据，自动清理
 * 掉的证据等于没有证据，而这一页已经把目录写在屏幕上，清不清由人决定。
 *
 * <p># 为什么要在另一条线程上写
 *
 * <p>调用点分布在网络线程、蓝牙回调和 UI 线程里。一次 {@code insert} 加一次 {@code openOutputStream}
 * 是几毫秒到几十毫秒的系统调用，落在 UI 线程上就是掉帧，落在收包的循环里就是拖慢传输——而日志
 * 是给人看问题用的，它自己不该成为问题。所以所有写入都排到一条专用的 {@link HandlerThread}，
 * 调用方只往队列里放一行字符串。队列里攒够一批再写，顺带把「每个报文一行」这种高频写入合并成
 * 一次系统调用。
 *
 * <p># 写不进去怎么办
 *
 * <p>什么都不做，只往 logcat 留一句。日志写失败不该让任何一条真实功能失败：一次接收成功了但
 * 日志没记上，比反过来好得多。这条和 {@link Publish} 的取舍是同一条——那边是「保存失败要让人
 * 知道」，因为那是他要的文件；这边是「记不上就算了」。
 */
final class Diagnostics {

    /** 公共目录下的相对路径。{@code RELATIVE_PATH} 要求带尾斜杠，这里是给人看的形式。 */
    static final String FOLDER = Environment.DIRECTORY_DOCUMENTS + "/AirDrop";

    /** 给设置页显示用的绝对说法。{@code /storage/emulated/0} 和 {@code /sdcard} 是同一个地方。 */
    static final String PATH = "/sdcard/" + FOLDER;

    /** 攒够这么多行就写一次。约 4 KB，正好是一个磁盘块的量级，又不至于让一次崩溃丢掉太多。 */
    private static final int FLUSH_LINES = 48;

    /** 但也不能攒太久：一行都不来的时候，最后那几行要在这么久之后自己落地。 */
    private static final long FLUSH_DELAY_MS = 1000;

    private static final Object LOCK = new Object();

    /**
     * 排队用的那一个 {@link Runnable}。
     *
     * <p>必须是一个**常量**，不能每次写 {@code Diagnostics::flush}。{@code Handler.removeCallbacks}
     * 是拿 {@code equals} 比对队列里的实例的，而方法引用每写一次就是一个新对象，也不实现
     * {@code equals}——于是"先撤掉上一次的延迟"这一步从来没生效过，安静下来之后队列里会叠着一堆
     * 一秒后的 flush。flush 本身幂等（第二批进来时 pending 已经空了，直接返回），不会写错，
     * 但去抖的意图落空，白跑。用同一个实例才真的撤得掉。
     */
    private static final Runnable FLUSH = Diagnostics::flush;

    /** 待写的行。只有持有 {@link #LOCK} 时才能碰。 */
    private static final List<String> pending = new ArrayList<>();

    private static Context context;
    private static Handler worker;

    /** 缓存当天的 Uri，免得每批都去查一次媒体库。换天时自动作废。 */
    private static Uri current;
    private static String currentName;

    private Diagnostics() {}

    /**
     * 接上进程。在 {@link App#onCreate} 里调一次。
     *
     * <p>{@code getApplicationContext()} 而不是把 Activity 存下来：日志的生命周期跟着进程走，
     * 存 Activity 会把一个已经销毁的窗口一直吊在这里。
     */
    static void install(Context app) {
        if (app == null) {
            return;
        }
        context = app.getApplicationContext();
        HandlerThread thread = new HandlerThread("opairdrop-log");
        thread.setDaemon(true);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    /**
     * 记一行。
     *
     * <p>{@code level} 是 {@code log.level} 里的那个词（{@code error}/{@code warn}/{@code info}/
     * {@code debug}），过滤在 {@link Log} 里做完了，到这里就是要写的。
     */
    static void write(String level, String tag, String message) {
        if (context == null || worker == null) {
            // 还没 install——极早期的日志（比如 Application 自己的构造过程）落不了盘，
            // 那些行仍然在 logcat 里，不算丢。
            return;
        }
        String line = stamp() + " " + letter(level) + "/" + tag + ": " + message + "\n";
        boolean flushNow;
        synchronized (LOCK) {
            pending.add(line);
            flushNow = pending.size() >= FLUSH_LINES;
        }
        if (flushNow) {
            worker.post(FLUSH);
        } else {
            // 每来一行都重置计时：连续写入时由 FLUSH_LINES 触发，安静下来之后由这一次触发。
            worker.removeCallbacks(FLUSH);
            worker.postDelayed(FLUSH, FLUSH_DELAY_MS);
        }
    }

    /** 把攒着的行写下去。可以在任何线程调。 */
    static void flush() {
        List<String> batch;
        synchronized (LOCK) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        String name = "airdrop-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())
                + ".log";
        StringBuilder text = new StringBuilder();
        for (String line : batch) {
            text.append(line);
        }
        try {
            append(name, text.toString());
        } catch (IOException | RuntimeException failed) {
            // 唯一的说法是 logcat：这条路本身就是为了「没有 logcat 的人」而存在的，所以这句
            // 只对开发者有意义，不该再往屏幕上报。
            android.util.Log.w("OPAirDrop", "diagnostics not written: " + failed);
        }
    }

    /**
     * 往当天的文件末尾追加。
     *
     * <p>追加用 {@code openOutputStream(uri, "wa")}——媒体库对自家行支持这个模式。文件还不存在
     * 的时候走 {@link #create}：那一步必须"先写字节、再放出来"，理由见那里的注释。
     *
     * <p>追加失败不再往同一个 Uri 上重试：那个 Uri 多半已经跟着文件一起被删了（人在文件管理器
     * 里删掉、或媒体库自己清理过），再写一次只会拿到同样的错。作废缓存、重建一份更直接。
     */
    private static void append(String name, String text) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Uri uri = existing(resolver, name);
        if (uri != null) {
            try (OutputStream out = resolver.openOutputStream(uri, "wa")) {
                if (out == null) {
                    throw new IOException("no stream for " + uri);
                }
                out.write(bytes);
                return;
            } catch (IOException | RuntimeException gone) {
                forget();
            }
        }
        create(resolver, name, bytes);
    }

    /**
     * 新建当天那份日志，并把第一批字节写进去。
     *
     * <p># 顺序不能反：写入在前，提交在后
     *
     * <p>{@code IS_PENDING=0} 不是"把标记清掉"这么轻的事——媒体库在这一步会对那一行做一次文件
     * 扫描，扫不到对应文件就把记录删掉。所以"先插一行、立刻提交、过一会儿再写"这条路走不通：
     * 提交那一刻磁盘上还是一个不存在的文件，行当场消失，随后写的时候只会拿到
     * {@code FileNotFoundException: No item at content://media/external/file/…}，而且目录里
     * 会留下一个空的 {@code AirDrop/}。这正是这一版第一稿踩的坑。
     *
     * <p>正确的顺序与 {@link Publish#save} 完全一致：插行（挂着 {@code IS_PENDING}）→ 写字节
     * → 提交。挂着的时候媒体库对它只做一半的事，扫描不会发生。
     */
    private static void create(ContentResolver resolver, String name, byte[] bytes)
            throws IOException {
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String directory = FOLDER + "/";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        // text/x-log 而不是 text/plain：后者会让媒体库把名字补成 `…log.txt`（名字的后缀和 MIME
        // 不一致时它会补一个自己的）。这个 MIME 的后缀本来就是 log，名字才不会被改。
        // 即便哪天它还是被改了也不算错——查找按日期前缀走，见 existing()。
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/x-log");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("could not create " + directory + name);
        }
        try (OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) {
                throw new IOException("no stream for " + uri);
            }
            out.write(bytes);
        } catch (IOException | RuntimeException failed) {
            try {
                resolver.delete(uri, null, null);
            } catch (RuntimeException ignored) {
                // 删不掉也不能盖住真正的原因。
            }
            throw failed instanceof IOException ? (IOException) failed : new IOException(failed);
        }
        ContentValues committed = new ContentValues();
        committed.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, committed, null, null);
        synchronized (LOCK) {
            current = uri;
            currentName = name;
        }
    }

    /**
     * 当天那份日志的 Uri，没有就返回 {@code null}（由 {@link #create} 去建）。
     *
     * <p>必须"先找"：媒体库对重名不做覆盖，它加后缀，于是每批日志都插一行的话，一天下来
     * {@code Documents/AirDrop} 里会是 {@code airdrop-… (1).log} 到 {@code (37).log}。
     *
     * <p># 为什么按前缀找，而不是按全名
     *
     * <p>因为我们说了不算。媒体库会把文件名改成和 MIME 一致的样子——{@code text/plain} 的
     * {@code airdrop-2026-11-16.log} 落到盘上叫 {@code airdrop-2026-11-16.log.txt}。按全名
     * 精确匹配就永远找不到自己刚建的那个文件，于是每启动一次就多一份 {@code (1)}、{@code (2)}，
     * 而"一天一个文件"的承诺当场失效（这正是第二稿踩的坑）。所以只拿日期这一段当前缀去匹配，
     * 后缀由媒体库决定，我们跟着它走。
     *
     * <p>取 {@code ORDER BY _id ASC} 的第一条：万一目录里已经有 {@code (1)}，也该继续往最早的
     * 那份里写，而不是每次挑一个。
     */
    private static Uri existing(ContentResolver resolver, String name) {
        synchronized (LOCK) {
            if (current != null && name.equals(currentName)) {
                return current;
            }
        }
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String directory = FOLDER + "/";
        // 名字形如 airdrop-2026-11-16.log：静下来看它其实两段，日期一段、后缀一段。
        // 日期这一段一天之内不会变，`(1)` 与 `.txt` 都是媒体库后来加的，所以拿日期当前缀。
        String base = name.replace(".log", "");
        Long found = null;
        try (Cursor row = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{directory, base + "%"},
                MediaStore.MediaColumns._ID + " ASC")) {
            if (row != null && row.moveToFirst()) {
                found = row.getLong(0);
            }
        } catch (RuntimeException unreadable) {
            // 查不了就当没有，往下走 insert。insert 也会再挡一次，那时才认失败。
        }
        if (found == null) {
            return null;
        }
        Uri uri = ContentUris.withAppendedId(collection, found);
        synchronized (LOCK) {
            current = uri;
            currentName = name;
        }
        return uri;
    }

    /** 一次新的写入要作废缓存——文件被人在文件管理器里删掉之后，旧 Uri 会一直写不进去。 */
    static void forget() {
        synchronized (LOCK) {
            current = null;
            currentName = null;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** 单字母级别，和 logcat 的 {@code I/W/E/D} 一致，读过日志的人才不用再学一套。 */
    private static String letter(String level) {
        switch (level) {
            case "error": return "E";
            case "warn": return "W";
            case "debug": return "D";
            default: return "I";
        }
    }
}
