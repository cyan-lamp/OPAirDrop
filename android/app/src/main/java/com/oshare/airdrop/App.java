package com.oshare.airdrop;

import android.app.Application;
import android.os.Build;

/**
 * 进程启动时把接收端对齐到那块磁贴的开关。
 *
 * <p>接在这里而不是某个页面里，是为了让「开关开着就该在收」这件事在没人看屏幕时也成立：
 * 进程被磁贴、被通知、被系统拉起，都该立刻对齐一次。放在 {@code MainActivity} 里会让它变成
 * 「打开过一次应用之后才对齐」，那是一个只在重启后才暴露的差别。
 *
 * <p>只做一件事，跟着进程走：对齐一次接收端的开停（判据与语义见 {@link ReceiveService}）。
 *
 * <p># 顺带把日志接上
 *
 * <p>{@link Log#install} 也在这里。诊断日志要落盘，落盘要知道两件事：往哪写（一个 Context），
 * 写多细（{@code log.level}）。这两件事都只在进程启动时确定一次就够——把 {@code install}
 * 放在 Activity 里会让「被磁贴拉起的进程」没有日志，而那恰好是最需要日志的一种启动方式。
 */
public final class App extends Application {

    private static final String TAG = "OPAirDrop";

    @Override
    public void onCreate() {
        super.onCreate();
        // 先接日志，再干别的：后面每一步失败都该留下痕迹。
        Log.install(this);
        // 每个进程的第一行写清楚「这是哪一版、在哪台机器上」。日志是按天切的一个文件，一天里
        // 进程会被拉起很多次（磁贴、通知、分享、开机），不写这一行的话，读文件的人分不清哪几行
        // 属于同一次启动，也分不清这份日志是不是上一版留下的。这三样正好是排问题时要先确定的。
        Log.i(TAG, "process started: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE
                + ") on " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT);
        // 不传页面：这里没有页面，也不该碰任何一个页面正在用的观察者。
        ReceiveService.follow(this);
    }
}
