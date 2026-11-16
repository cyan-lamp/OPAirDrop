package com.oshare.airdrop;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;

import java.io.File;

/**
 * 流体云那张卡上的【打开文件】。
 *
 * <p># 为什么是一个 Activity，而不是 {@link android.content.BroadcastReceiver}
 *
 * <p>因为它曾经是广播，而广播这条路从 Android 12 起被平台堵死了。
 *
 * <p>「点通知→落在一个不画界面的组件→由它去起界面」这套写法有个名字叫**通知蹦床**
 * （notification trampoline）。它的问题是真的：人点下去之后，应用可以在中间做任何事、也可以
 * 什么都不做，系统无法向人保证「点了就有反应」。所以平台规定，一条通知的 PendingIntent 只
 * 允许落到**界面**上，落到广播或服务之后再起界面会被直接拦掉。
 *
 * <p>2026-11-16 的现场就是这样，一点提示都没有：
 *
 * <pre>
 * E/ActivityTaskManager: Background activity launch blocked!
 *   callingUidProcState: FOREGROUND_SERVICE; isPendingIntent: false
 *   intent: VIEW content://media/… typ=image/jpeg cmp=com.coloros.gallery3d/…ViewGallery
 *   result code=102 (BAL_BLOCK)
 * </pre>
 *
 * <p>{@code isPendingIntent: false} 是这句话的要点：那一刻这次启动已经和「人点的那条通知」没有
 * 关系了，它只是「一个后台进程擅自要起界面」，于是被按规则拦下。相册没打开，也没有任何报错——
 * 人看到的是「按了没反应」。
 *
 * <p># 那为什么还要中间这一层
 *
 * <p>因为按下去要发生两件事：**把卡收掉**，然后才打开东西。撤卡只能在我们的代码里做，而打开
 * 东西的那一下又必须是平台认可的「人点的」——能同时满足这两条的，只有把落点放在一个 Activity 上：
 * 人点的是它，它就是人点的那个界面；起相册是它在自己可见的时候做的下一跳，平台允许。
 *
 * <p>这个窗口一个像素都不画（{@link android.R.style#Theme_Translucent_NoTitleBar}），撤完卡就
 * 起下一跳、起完立刻收自己，所以人看到的是相册直接盖上来的样子。而且它**必定**会收掉自己：
 * 无论下一跳起没起得来，{@link #onResume} 末尾都 finish——一个透明的、留住触摸的窗口比什么都
 * 不打开更糟。
 *
 * <p># 打开什么，按类型分
 *
 * <p>**照片和视频进相册**——人要看的是那张照片，不是它躺在哪个目录。**其余文件打开文件管理
 * 里它所在的那一层**——一个 PDF 用系统查看器打开往往只是"能看"，而人拿到文件之后的下一步通常
 * 是挪走它、发给别人、重命名，那都得在文件管理里做。
 *
 * <p># 「打开它所在的那一层」为什么要走私有组件（2026-11-16 实测记录）
 *
 * <p>最初写的是标准做法：把一个**目录文档 URI**（{@code content://com.android.externalstorage.
 * documents/document/primary:Download%2FAirDrop}）配上 {@code type=vnd.android.document/directory}
 * 发出去，再 {@code setPackage("com.coloros.filemanager")} 指定收件人。结果是**弹出系统的「打开
 * 方式」选择器**，里面是阿里云盘、夸克、微信输入法、文件——就是不直接进文件管理。
 *
 * <p>一开始以为是权限没补，查下来不是。把 {@code cmd package query-activities} 打出来看，
 * 全机只有四个组件声明能处理目录 MIME（{@code com.android.documentsui}、{@code com.alicloud.
 * databox}、{@code com.quark.browser}、{@code com.tencent.wetype}），**{@code com.coloros.
 * filemanager} 不在其中**。它没有注册这个 intent-filter，所以 {@code resolveActivity} 返回 null，
 * 代码退回通用意图，系统就弹了选择器。补权限解决不了「对方没声明要接这个东西」。
 *
 * <p>文件管理真正认的入口是它自己的 {@code com.oplus.filebrowser.FileBrowserActivity}：翻开它的
 * manifest，这个 activity 是 {@code exported="true"} 且**没有**挂 {@code android:permission}
 * （同一份 manifest 里旁边的 {@code OtgFileBrowserActivity} 就明确挂了
 * {@code oppo.permission.OPPO_COMPONENT_SAFE}，可见作者是会给组件上锁的，没锁就是允许外部调用）。
 * 它不看 URI，看 extra：{@code CurrentDir} 收一个**绝对路径**，实测传
 * {@code /storage/emulated/0/Download/AirDrop} 之后，界面直接停在面包屑「全部文件 › Download ›
 * AirDrop」上，标题就是 AirDrop 目录名，里面的文件列得好好的。
 *
 * <p>同一份 APK 里还有一条 {@code filemanager://deeplink.filebrowser?...} 的深链，看上去更"官方"，
 * 但它要 {@code com.oplus.permission.safe.PROTECT}——第三方应用发过去是
 * {@code SecurityException}（{@code mCallingUid=2000}），那条路对我们关闭。所以走组件，不走深链。
 *
 * <p>代价是这套东西只对 ColorOS 有效。因此这里按「能用就用最好的、用不了逐级退」来写：私有组件
 * → 系统文档界面（{@code com.android.documentsui} 认目录 URI）→ 通用目录意图。第一跳失败不该
 * 让人什么都没有，第三跳至少能让他自己选一个能看目录的应用。
 */
public final class OpenDownload extends Activity {

    private static final String TAG = "OPAirDrop";

    /** 这台 ROM 上相册与文件管理的包名。查过：{@code pm list packages | grep -i gallery}。 */
    private static final String GALLERY = "com.coloros.gallery3d";
    private static final String FILES = "com.coloros.filemanager";

    /**
     * 文件管理里那个「浏览目录」的界面。
     *
     * <p>写死类名是因为它没有对外暴露的 action：能在 manifest 里查到它 {@code exported}，
     * 但没有任何 intent-filter 能让 {@code ACTION_VIEW} 命中它。{@link #folder} 里对这条路
     * 是 try-catch 的，类名变了最多是退到下一跳，不会崩。
     */
    private static final String FILES_BROWSER = "com.oplus.filebrowser.FileBrowserActivity";

    /** 它读的那个 extra，值是绝对路径。反汇编 {@code FileBrowserActivity} 得到的键名。 */
    private static final String EXTRA_CURRENT_DIR = "CurrentDir";

    /** 系统文档界面。Android 10 起目录浏览是这个类，它认目录文档 URI。 */
    private static final String DOCUMENTS = "com.android.documentsui";
    private static final String DOCUMENTS_BROWSER = "com.android.documentsui.files.FilesActivity";

    static final String EXTRA_URI = "uri";
    static final String EXTRA_MIME = "mime";
    static final String EXTRA_DIR = "dir";

    /** 只在第一次 resume 时起下一跳：旋转、被系统重新 resume 都不该再开一次相册。 */
    private boolean launched;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // 第一件事永远是撤卡：按了「打开文件」之后，那张「接收完成」就没有留下的理由。
        // 撤卡不涉及起界面，放在这里最稳——哪怕后面那一步失败，卡也已经按人的意思收掉了。
        LiveCard.drop(this);
    }

    /**
     * 起下一跳。
     *
     * <p>放在 resume 而不是 create，是因为这一跳能不能成取决于「我们此刻算不算在前台」：
     * onCreate 时这个窗口还没被标记成可见，平台仍可能把它当成一次后台启动拦掉；到了 resume，
     * 它就是人眼前那个界面，起相册是它正当的下一步。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (launched) {
            return;
        }
        launched = true;

        Intent raw = getIntent();
        String uri = raw == null ? null : raw.getStringExtra(EXTRA_URI);
        if (uri != null) {
            String mime = raw.getStringExtra(EXTRA_MIME);
            boolean media = mime != null && (mime.startsWith("image/") || mime.startsWith("video/"));
            if (media) {
                // 相册只认文件本身，一条路。
                try {
                    startActivity(media(Uri.parse(uri), mime));
                } catch (RuntimeException refused) {
                    Log.w(TAG, "could not open what was just received: " + refused);
                }
            } else {
                // 目录有三条路，逐条试：能起来的就那一条，起不来的那条不该拦住下一条。
                openFolder(raw.getStringExtra(EXTRA_DIR));
            }
        }
        // 无论上面成没成，这一层都不能留下：它没有界面，留着只会挡住别人。
        finish();
    }

    /** 照片、视频：交给相册；相册不认这一份时退回系统选择器。 */
    private Intent media(Uri uri, String mime) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                // NEW_TASK：相册该开在它自己的任务里，而不是塞进这个空壳所在的任务。这样从相册
                // 返回时回到人原来待的地方，而不是回到一个已经 finish 掉的透明窗口。
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent gallery = new Intent(view).setPackage(GALLERY);
        if (gallery.resolveActivity(getPackageManager()) != null) {
            return gallery;
        }
        return view;
    }

    /**
     * 其余文件：打开它所在的那一层目录。
     *
     * <p>{@code directory} 是相对公共根的路径（例如 {@code Download/AirDrop}）。三条候选按
     * 「落到多准」排序，逐条 {@code startActivity}，第一条成的就结束：
     *
     * <ol>
     *   <li><b>文件管理的私有界面</b> + {@code CurrentDir} 绝对路径 —— 唯一真正「停在那一层」的
     *       一条。清单里写着 exported 且没挂权限，实测也确实能起、能落对位置。</li>
     *   <li><b>系统文档界面</b> + 目录文档 URI —— 通用 ROM 上的正解。ColorOS 上它也在，只是
     *       长相和文件管理不一样；作为第二跳，保证「不是 ColorOS 也不至于弹选择器」。</li>
     *   <li><b>通用目录意图</b> —— 最后一条。会弹「打开方式」，但总比按了没反应强：
     *       人至少能从里面挑一个应用。</li>
     * </ol>
     *
     * <p>每条都单独 catch。第二跳抛 {@code SecurityException} 不代表第一跳没成功——反过来说，
     * 第一跳抛了也不代表第二跳也会抛，所以不能用一个 try 包住三条。
     *
     * <p># 这里没有「目录不存在就往上退一级」的判断，故意的（2026-11-16 实测记录）
     *
     * <p>上一版有一段看起来很稳妥的防御：{@code while (!absolute.isDirectory() && parent != null)
     * absolute = parent}，理由是「目录被删了就别把它传过去，文件管理会退到根目录，比老老实实
     * 退下一跳更让人困惑」。这段代码把第一跳整条废掉了。
     *
     * <p>因为应用**没有**存储权限，也不该有——文件是走 {@code MediaStore} 写进去的，写公共目录
     * 不需要权限。而没有权限的后果是对 {@code /sdcard} 下任何路径 {@code stat} 都得到
     * {@code EACCES}，{@code isDirectory()} 一律返回 **false**。用应用自己的身份验证一次就清楚：
     *
     * <pre>
     * $ adb shell run-as com.oshare.airdrop ls -d /sdcard/Download/AirDrop
     * ls: /sdcard/Download/AirDrop: Permission denied
     * </pre>
     *
     * <p>于是那个循环把 {@code Download/AirDrop} 一路退到 {@code Download}、{@code /storage/
     * emulated/0}、{@code /}，每一级都「不是目录」，最后 {@code absolute.isDirectory()} 还是
     * false，第一跳压根没执行——直接落到第三条通用意图，人看到的就是「还是弹打开方式选择器」。
     * 而同一个意图用 {@code adb shell am start} 发（shell 有权限）是能进到那一层的，所以上一次
     * 「实测成功」并不是假话，只是两种身份看到的不是同一个世界。
     *
     * <p>现在不探了，直接按记录下来的路径发。代价是目录真的被删掉时文件管理会停在「全部文件」根
     * 目录；那种情况人自己知道删过什么，而为了它把每一次都变成弹选择器，代价明显不划算。
     * 真要探也只能问媒体库，但空目录在媒体库里没有行，问不出「目录在不在」——问不出，就不问。
     */
    private void openFolder(String directory) {
        if (directory == null || directory.isEmpty()) {
            Log.w(TAG, "received a file but no folder was recorded for it");
            return;
        }
        // 文件管理那一条要绝对路径。拼接是纯字符串运算，不需要任何权限；真正读不读得到由文件管理
        // 自己做，它当然读得到。
        File absolute = new File(Environment.getExternalStorageDirectory(), directory);
        Intent browser = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(FILES, FILES_BROWSER))
                .putExtra(EXTRA_CURRENT_DIR, absolute.getAbsolutePath())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (start(browser)) {
            return;
        }

        Uri tree = Uri.parse("content://com.android.externalstorage.documents/document/primary:"
                + Uri.encode(directory));
        Intent documents = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(DOCUMENTS, DOCUMENTS_BROWSER))
                .setDataAndType(tree, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (start(documents)) {
            return;
        }

        Intent any = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(tree, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!start(any)) {
            Log.w(TAG, "no file manager on this device would open " + directory);
        }
    }

    /**
     * 起一次，成败用返回值说。
     *
     * <p>用 try 而不是先 {@code resolveActivity} 查一遍：这套私有组件在包可见性（Android 11 起
     * 的 package visibility）下，没在 manifest 里 {@code <queries>} 声明的包查不到，但**显式
     * 组件**的启动是豁免的。也就是说「查不到」和「起不来」不是一回事，查一遍反而会误判成不可用。
     * 直接起、接住异常，是这两件事上唯一可靠的判据。
     */
    private boolean start(Intent intent) {
        try {
            startActivity(intent);
            // debug 档记一笔「走的是哪一跳」。这条日志是给「点了却弹了别的」这种反馈用的：
            // 有它就能当场分清是三条路里的哪一条接的手，没有它就只剩猜测。默认的 info 档不记，
            // 因为正常一次点击不值得占一行。
            Log.d(TAG, "route taken: " + (intent.getComponent() == null
                    ? String.valueOf(intent.getData())
                    : intent.getComponent().flattenToShortString()));
            return true;
        } catch (RuntimeException no) {
            // ActivityNotFoundException（类名变了）与 SecurityException（厂家后来给组件上了锁）
            // 都落在这里，处理方式一样：换下一条路。
            Log.i(TAG, "not this route: " + intent.getComponent() + " — " + no);
            return false;
        }
    }
}
