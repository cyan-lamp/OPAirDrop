package com.oshare.airdrop;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * 流体云那张「接收完成」卡上的【打开链接】。
 *
 * <p># 为什么需要这一层
 *
 * <p>{@link OpenDownload} 那个类注释里记着一整段：通知上的 PendingIntent 只允许直接落到
 * **界面**上，落到广播或服务之后再起界面，平台会按 {@code BAL_BLOCK} 直接拦掉，屏幕上什么
 * 都不会发生。所以哪怕这次要做的只是「打开一个网址」，也必须先落在一个真实的窗口上。
 *
 * <p>而这一层还顺手做了另一件非做不可的事：**撤卡**。按了【打开链接】之后，那张「接收完成」
 * 就没有留下的理由，而撤卡只能在我们自己的代码里做。
 *
 * <p># 和 {@link OpenDownload} 的唯一区别：这次要打开的地址来自别人
 *
 * <p>文件那一条的地址是我们自己刚写进媒体库的，来源受控。这一条的地址是**对面那台手机说的**，
 * 中间只经过一次字符清洗（见 {@code serve.rs} 的 {@code links_of}），没有任何校验。它现在要
 * 被交给 {@code ACTION_VIEW}，也就是交给系统去挑选一个应用来处理——这条路上，一个
 * {@code file://} 或一个自定义 scheme 能做的事远不止"打开一个网页"。
 *
 * <p>所以这里只放行 {@code http} 与 {@code https}。这不是洁癖：AirDrop 的链接分享本来就是
 * 从浏览器或分享面板发起的一次"看看这个"，那两种就是它全部的正当形态；其余的一律不打开、
 * 记一行日志。**宁可什么也不打开，也不能替对面把一个我们没读懂的 scheme 交出去。**
 *
 * <p>窗口本身一个像素都不画（{@link android.R.style#Theme_Translucent_NoTitleBar}），起完
 * 下一跳立刻收自己——它没有界面，留着只会挡住别人，而一个留住触摸的透明窗口比什么都不打开
 * 更糟。
 */
public final class OpenLink extends Activity {

    private static final String TAG = "OPAirDrop";

    /** 要打开的那一条。 */
    static final String EXTRA_URL = "url";

    /** 这次分享里另外还有几条没打开。只进日志。 */
    static final String EXTRA_MORE = "more";

    /** 只在第一次 resume 时起下一跳：被系统重新 resume 不该再开一次浏览器。 */
    private boolean launched;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // 第一件事永远是撤卡：按了【打开链接】之后，这张「接收完成」就没有留下的理由。
        // 撤卡不涉及起界面，放在这里最稳——哪怕后面那一步失败，卡也已经按人的意思收掉了。
        LiveCard.drop(this);
    }

    /**
     * 起下一跳。
     *
     * <p>放在 resume 而不是 create：onCreate 时这个窗口还没被标记成可见，平台仍可能把这一跳
     * 当成一次后台启动拦掉；到了 resume，它就是人眼前那个界面，起浏览器是它正当的下一步。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (launched) {
            return;
        }
        launched = true;

        Intent raw = getIntent();
        String url = raw == null ? null : raw.getStringExtra(EXTRA_URL);
        if (url != null) {
            open(url, raw.getIntExtra(EXTRA_MORE, 0));
        }
        // 无论上面成没成，这一层都不能留下。
        finish();
    }

    /**
     * 打开一条，只打开这一条。
     *
     * <p>不先 {@code resolveActivity} 查一遍再起：包可见性下「查不到」和「起不来」不是一件事
     * （见 {@link OpenDownload} 里 {@code start} 那一段注释），直接起、接住异常，是这两件事上
     * 唯一可靠的判据。
     */
    private void open(String url, int more) {
        Uri target = Uri.parse(url);
        String scheme = target.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            // 不打开，但也不装作没发生：这一行是「为什么按了没反应」唯一的解释。
            Log.w(TAG, "not opening a link that is not http(s): " + describe(scheme, url));
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW, target)
                // NEW_TASK：浏览器该开在它自己的任务里，而不是塞进这个空壳所在的任务。这样从
                // 浏览器返回时回到人原来待的地方，而不是回到一个已经 finish 掉的透明窗口。
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            Log.i(TAG, "opened " + host(target) + (more > 0 ? " (" + more + " more not opened)" : ""));
        } catch (RuntimeException no) {
            // 这台机器上没有能接 http(s) 的应用。极少见，但它是"按了没反应"的一种真实原因。
            Log.w(TAG, "no browser would open " + host(target) + ": " + no);
        }
    }

    /** 日志里那一句只说主机：整条地址可能有几百个字符，而它一行都装不下。 */
    private static String host(Uri uri) {
        String host = uri.getHost();
        return host == null || host.isEmpty() ? "a link" : host;
    }

    /**
     * 一条不该打开的地址，写成一行日志。
     *
     * <p>地址本身<b>截断</b>再记：它是别人给的任意长度的字符串，完整打出来能把一行日志冲垮，
     * 而判断"这是什么东西"只需要开头那几个字符。
     */
    private static String describe(String scheme, String url) {
        String head = url.length() > 60 ? url.substring(0, 60) + "…" : url;
        return "scheme=" + scheme + " url=" + head;
    }
}
