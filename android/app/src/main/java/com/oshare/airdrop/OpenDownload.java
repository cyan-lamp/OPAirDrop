package com.oshare.airdrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * 流体云那张卡上的【打开文件】。
 *
 * <p># 为什么是一条广播，而不是直接 {@code ACTION_VIEW} 的 PendingIntent
 *
 * <p>因为按下去要发生两件事：**把卡收掉**，然后才打开东西。一个 PendingIntent 只能指向一个
 * 组件、做一件事；把两件事塞进一个 Activity 会在中间闪一下白屏。广播是那一秒里最安静的一层：
 * 撤卡在这里做，打开交给别人。
 *
 * <p># 打开什么，按类型分
 *
 * <p>**照片和视频进相册**——人要看的是那张照片，不是它躺在哪个目录。**其余文件打开文件管理
 * 里它所在的那一层**——一个 PDF 用系统查看器打开往往只是"能看"，而人拿到文件之后的下一步通常
 * 是挪走它、发给别人、重命名，那都得在文件管理里做。
 */
public final class OpenDownload extends BroadcastReceiver {

    private static final String TAG = "OPAirDrop";

    /** 这台 ROM 上相册与文件管理的包名。查过：{@code pm list packages | grep -i gallery}。 */
    private static final String GALLERY = "com.coloros.gallery3d";
    private static final String FILES = "com.coloros.filemanager";

    static final String EXTRA_URI = "uri";
    static final String EXTRA_MIME = "mime";
    static final String EXTRA_DIR = "dir";

    @Override
    public void onReceive(Context context, Intent intent) {
        // 第一件事永远是撤卡：按了「打开文件」之后，那张「接收完成」就没有留下的理由。
        LiveCard.drop(context);

        String raw = intent.getStringExtra(EXTRA_URI);
        if (raw == null) {
            return;
        }
        Uri uri = Uri.parse(raw);
        String mime = intent.getStringExtra(EXTRA_MIME);
        boolean media = mime != null && (mime.startsWith("image/") || mime.startsWith("video/"));

        Intent target = media ? media(context, uri, mime)
                : folder(context, intent.getStringExtra(EXTRA_DIR));
        if (target == null) {
            return;
        }
        try {
            context.startActivity(target);
        } catch (RuntimeException refused) {
            Log.w(TAG, "could not open what was just received: " + refused);
        }
    }

    /** 照片、视频：交给相册；相册不认这一份时退回系统选择器。 */
    private Intent media(Context context, Uri uri, String mime) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent gallery = new Intent(view).setPackage(GALLERY);
        if (gallery.resolveActivity(context.getPackageManager()) != null) {
            return gallery;
        }
        return view;
    }

    /**
     * 其余文件：打开它所在的那一层目录。
     *
     * <p>{@code directory} 是相对公共根的路径（例如 {@code Download/AirDrop}），拼成
     * {@code primary:Download/AirDrop} 就是一个目录文档 URI，文件管理与系统文档界面都认。
     */
    private Intent folder(Context context, String directory) {
        if (directory == null || directory.isEmpty()) {
            return null;
        }
        Uri tree = Uri.parse("content://com.android.externalstorage.documents/document/primary:"
                + Uri.encode(directory));
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(tree, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent manager = new Intent(open).setPackage(FILES);
        if (manager.resolveActivity(context.getPackageManager()) != null) {
            return manager;
        }
        return open;
    }
}
