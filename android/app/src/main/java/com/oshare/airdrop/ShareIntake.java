package com.oshare.airdrop;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一次「分享」变成一个能发出的文件清单。
 *
 * <p># 为什么先落盘
 *
 * <p>分享出来的东西是 {@code content://} 一个 URI，授权只在这个进程收到这次 Intent 的期间
 * 有效。发送要跑几十秒到几分钟，中间可能还要重试一次，所以文件必须先复制到自己能打开的地方：
 * 拿着一个过期的 URI 去发，会在传输中途以一句「Permission denial」结束，而那时对面已经弹过
 * 接收确认了。
 *
 * <p># 为什么落在 cacheDir
 *
 * <p>cache 是系统在空间紧张时唯一敢自己删的地方，也正是这些副本该有的待遇——发完就没用了。
 * 每次进来先清空上一次的残留：一个 4 GB 的视频副本留在那里，用户下一次看到的「存储空间不足」
 * 就是自己造成的。
 *
 * <p># 为什么纯文本也变成文件
 *
 * <p>{@code EXTRA_TEXT} 是最常见的分享形态（分享一段话、一个链接）。AirDrop 只收文件，
 * 所以文字落成一个 {@code .txt}。这不是权宜：对面收到的就是一份能存下来的文本，而不是一个
 * 打不开的「剪贴板」。
 */
final class ShareIntake {

    private static final String TAG = "OPAirDrop";

    /** 复制出来的文件都放这里，随用随清。 */
    private static final String DIRECTORY = "outgoing";

    /** 一次分享的结果：文件，或者为什么一个都没有。 */
    static final class Batch {
        final List<File> files = new ArrayList<>();
        String problem;
    }

    private ShareIntake() {}

    /**
     * 解析一条 {@code SEND}/{@code SEND_MULTIPLE} 意图并复制文件。会读写磁盘，不要在主线程调用。
     */
    static Batch resolve(Context context, Intent intent) {
        Batch batch = new Batch();
        if (intent == null) {
            batch.problem = "没有可发送的内容";
            return batch;
        }
        List<Uri> uris = new ArrayList<>();

        // EXTRA_STREAM 与 ClipData 是同一件事的两种说法，新版系统两个都填。合并时按 URI
        // 去重：同一张图被列两次，对面会收到两个同名文件，而第二个是「photo (1).jpg」。
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                add(uris, clip.getItemAt(index).getUri());
            }
        }
        Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        add(uris, single);
        ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        if (many != null) {
            for (Uri uri : many) {
                add(uris, uri);
            }
        }

        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (uris.isEmpty() && (text == null || text.length() == 0)) {
            batch.problem = "这条分享里没有文件";
            return batch;
        }

        File directory = new File(context.getCacheDir(), DIRECTORY);
        clear(directory);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            batch.problem = "无法创建临时目录";
            return batch;
        }

        for (Uri uri : uris) {
            try {
                batch.files.add(copy(context, uri, directory));
            } catch (Exception failed) {
                Log.w(TAG, "cannot take " + uri + ": " + failed);
                // 一张图坏了不该让其余九张发不出去，但也不能悄悄少发：问题记下来，页面会说。
                batch.problem = "有一个文件读不出来，已跳过";
            }
        }
        if (!uris.isEmpty() && batch.files.isEmpty() && batch.problem == null) {
            batch.problem = "没有文件能读出来";
        }

        if (text != null && text.length() > 0) {
            try {
                batch.files.add(write(directory, "分享的文字.txt", text.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception failed) {
                batch.problem = "文字存不下来：" + failed.getMessage();
            }
        }
        return batch;
    }

    private static void add(List<Uri> uris, Uri uri) {
        if (uri != null && !uris.contains(uri)) {
            uris.add(uri);
        }
    }

    /** 复制一个 URI 到临时目录，名字取系统报的那个。 */
    private static File copy(Context context, Uri uri, File directory) throws Exception {
        String name = name(context, uri);
        File target = unique(directory, name);
        ContentResolver resolver = context.getContentResolver();
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IllegalStateException("打开后没有内容");
            }
            // 8 KiB：小到不会为一张 20 KB 的图浪费内存，大到一条 4 GB 的视频也只有几千次系统调用。
            byte[] chunk = new byte[8 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
        }
        return target;
    }

    private static File write(File directory, String name, byte[] content) throws Exception {
        File target = unique(directory, name);
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(content);
        }
        return target;
    }

    /** 系统给的名字，拿不到就用 URI 的最后一段，再拿不到就叫「文件」。 */
    private static String name(Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String shown = cursor.getString(0);
                if (shown != null && !shown.isBlank()) {
                    return clean(shown);
                }
            }
        } catch (Exception ignored) {
            // 有的 provider 不支持 query；名字只是好看，不值得为它失败。
        }
        String last = uri.getLastPathSegment();
        return clean(last == null || last.isBlank() ? "文件" : last);
    }

    /**
     * 去掉名字里的路径分隔符与控制字符。
     *
     * <p>名字是别人给的：一个 {@code ../../} 开头或者带 NUL 的名字，落到文件名上就是一次目录
     * 穿越。cpio 那一侧还会再挡一次（{@code safe_name}），这里挡是因为本地也不该建出那种文件。
     */
    private static String clean(String name) {
        String cleaned = name.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
        return cleaned.isEmpty() ? "文件" : cleaned;
    }

    /** 同名的第二个文件加序号，而不是覆盖第一个。 */
    private static File unique(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }
        String stem = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int index = 2; index < 1000; index++) {
            candidate = new File(directory, stem + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, System.currentTimeMillis() + "-" + name);
    }

    /** 清掉上一次的副本。删不掉就算了——cache 里的东西系统迟早会收走。 */
    private static void clear(File directory) {
        File[] stale = directory.listFiles();
        if (stale == null) {
            return;
        }
        for (File file : stale) {
            if (!file.delete()) {
                Log.w(TAG, "stale copy kept: " + file);
            }
        }
    }
}
