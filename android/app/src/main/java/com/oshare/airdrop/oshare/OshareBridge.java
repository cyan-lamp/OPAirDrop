package com.oshare.airdrop.oshare;

import android.app.Activity;
import android.content.Intent;

/**
 * 一加互传（com.coloros.oshare）的入口桥。
 *
 * <p>它的发送界面 {@code com.oplus.oshare.ui.IOSShareActivity} 注册了标准的
 * {@code ACTION_SEND / SEND_MULTIPLE + *​/*}——和本应用的 SendActivity 完全同构，
 * 直接 {@code setClassName} 调用即可。它是 priv-app（装在 /product/priv-app），
 * 自带两样第三方拿不到的东西：ColorOS 浮窗分享豁免（分享给它永远不会被框成小窗）、
 * 与流体云的原生集成。
 */
public final class OshareBridge {

    private static final String PKG = "com.coloros.oshare";
    /** OShare 的 AirDrop 发送界面。 */
    private static final String IOS_SHARE = "com.oplus.oshare.ui.IOSShareActivity";
    /** OShare 的主入口：应用内选文件发送、接收开关都在里面。 */
    private static final String MAIN = "com.oplus.oshare.ui.ShareShortcutActivity";

    /**
     * 把一条分享转发给一加互传的发送界面。
     *
     * <p>转发整条 intent（action/extras/type/ClipData 原样保留），只改目标。我们持有
     * 上游授予的读授权；带上 {@code FLAG_GRANT_READ_URI_PERMISSION}，系统会在
     * startActivity 那一刻把授权延伸到 OShare——授权在目标接住时即已落地，
     * 之后调用方 finish 不影响它。
     *
     * @return false = 没有转发成功（一加互传不在或起不来），调用方回落自研面板。
     */
    public static boolean forwardShare(Activity from, Intent source) {
        Intent out = new Intent(source);
        out.setClassName(PKG, IOS_SHARE);
        out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            from.startActivity(out);
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * 打开一加互传自己的应用（选文件发送、接收开关都在里面）。
     *
     * @return false = 打不开（应用不存在），调用方自己说话。
     */
    public static boolean openApp(Activity from) {
        try {
            from.startActivity(new Intent().setClassName(PKG, MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private OshareBridge() {
    }
}
