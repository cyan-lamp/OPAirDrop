package com.oshare.airdrop;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 从后台发一张卡的探针（只在 debug 包里）。
 *
 * <p>存在的理由是这一条观测条件：**应用自己在前台时系统会抑制那颗胶囊**。而任何从 Activity
 * 里发通知的做法都必然把应用带到前台，于是「通知到达时胶囊会不会自动展开」这个问题永远测不
 * 出来。广播进来时应用还在后台，这才是真实场景——人在别的应用里，对面发来一张照片。
 *
 * <pre>
 *   adb shell am broadcast -a com.oshare.airdrop.PROBE --es mode invite
 *   adb shell am broadcast -a com.oshare.airdrop.PROBE --es mode invite_high
 *   adb shell am broadcast -a com.oshare.airdrop.PROBE --es mode invite_twice
 *   adb shell am broadcast -a com.oshare.airdrop.PROBE --es mode progress
 *   adb shell am broadcast -a com.oshare.airdrop.PROBE --es mode drop
 * </pre>
 */
public final class LiveProbe extends BroadcastReceiver {

    private static final String TAG = "OPAirDrop";

    @Override
    public void onReceive(Context context, Intent intent) {
        String mode = intent.getStringExtra("mode");
        if (mode == null) {
            mode = "invite";
        }
        Log.i(TAG, "probe " + mode + ", allowed=" + LiveCard.allowed(context));
        PendingIntent accept = open(context, 41);
        PendingIntent refuse = open(context, 42);
        switch (mode) {
            case "invite_banner_fresh":
                // 先把本应用的通知全清掉，排除「组内成员不抬头」这个可能。
                ((android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                        .cancelAll();
                new android.os.Handler(context.getMainLooper()).postDelayed(
                        () -> LiveCard.inviteBanner(context, "卷卷薯 想发给你文件",
                                "1 个文件 · 2.4 MB", accept, refuse), 700);
                break;
            case "invite_banner":
                LiveCard.inviteBanner(context, "卷卷薯 想发给你文件", "1 个文件 · 2.4 MB", accept, refuse);
                break;
            case "invite_high":
                LiveCard.invite(context, "卷卷薯 想发给你文件", "1 个文件 · 2.4 MB", accept, refuse);
                break;
            case "invite_twice":
                LiveCard.invite(context, "卷卷薯 想发给你文件", "1 个文件 · 2.4 MB", accept, refuse);
                new android.os.Handler(context.getMainLooper()).postDelayed(
                        () -> LiveCard.invite(context, "卷卷薯 想发给你文件",
                                "1 个文件 · 2.4 MB · 刚刚", accept, refuse), 900);
                break;
            case "oplus_tile":
                probeOplusTile(context);
                break;
            case "progress":
                LiveCard.update(context, "正在接收", "45%", 45);
                break;
            case "drop":
                LiveCard.drop(context);
                break;
            default:
                LiveCard.invite(context, "卷卷薯 想发给你文件", "1 个文件 · 2.4 MB", accept, refuse);
                break;
        }
    }

    /**
     * 只问一件事：我们能不能用 Oplus 的磁贴桥。
     *
     * <p>{@code OplusTileManager} 的方法在 framework 里是 {@code whitelist}（公开可见），但"能调到"
     * 和"调了不被拦"是两件事。这里把能调的都调一遍，把异常原样打出来——是权限问题还是根本没有
     * 这个服务，日志里会自己说。
     */
    private static void probeOplusTile(Context context) {
        String tag = "OPAirDrop";
        try {
            Class<?> manager = Class.forName("com.oplus.quicksettings.OplusTileManager");
            Object instance = manager.getMethod("getInstance").invoke(null);
            Log.i(tag, "oplus tile: getInstance ok -> " + instance);
            android.content.ComponentName self = new android.content.ComponentName(
                    context, AirdropTile.class);
            try {
                Object tile = manager.getMethod("getOplusTile", android.content.ComponentName.class)
                        .invoke(instance, self);
                Log.i(tag, "oplus tile: getOplusTile ok -> " + tile);
            } catch (Throwable failure) {
                Log.w(tag, "oplus tile: getOplusTile refused", failure);
            }
            try {
                // 互传那个磁贴：系统给它的 Tile 长什么样，就是"内容"的契约。
                Object oshare = manager.getMethod("getOplusTile", android.content.ComponentName.class)
                        .invoke(instance, new android.content.ComponentName("com.coloros.oshare",
                                "com.oplus.oshare.OshareTileService"));
                Log.i(tag, "oplus tile: oshare tile = " + oshare);
                if (oshare != null) {
                    for (String getter : new String[]{"getLabel", "getSubtitle", "getState",
                            "getStateDescription", "getContentDescription", "getIcon"}) {
                        try {
                            Object value = oshare.getClass().getMethod(getter).invoke(oshare);
                            Log.i(tag, "oplus tile: oshare." + getter + "() = " + value);
                        } catch (Throwable none) {
                            Log.i(tag, "oplus tile: oshare." + getter + "() n/a");
                        }
                    }
                }
            } catch (Throwable failure) {
                Log.w(tag, "oplus tile: oshare tile refused", failure);
            }
            try {
                manager.getMethod("updateRequest", android.os.Bundle.class,
                                android.content.ComponentName.class)
                        .invoke(instance, new android.os.Bundle(), self);
                Log.i(tag, "oplus tile: updateRequest ok");
            } catch (Throwable failure) {
                Log.w(tag, "oplus tile: updateRequest refused", failure);
            }
        } catch (Throwable missing) {
            Log.w(tag, "oplus tile: no OplusTileManager on this build", missing);
        }
    }

    private static PendingIntent open(Context context, int request) {
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, request, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
