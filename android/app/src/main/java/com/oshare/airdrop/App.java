package com.oshare.airdrop;

import android.app.Application;

/**
 * 进程启动时把接收端对齐到那块磁贴的开关。
 *
 * <p>接在这里而不是某个页面里，是为了让「开关开着就该在收」这件事在没人看屏幕时也成立：
 * 进程被磁贴、被通知、被系统拉起，都该立刻对齐一次。放在 {@code MainActivity} 里会让它变成
 * 「打开过一次应用之后才对齐」，那是一个只在重启后才暴露的差别。
 *
 * <p>只做一件事，跟着进程走：对齐一次接收端的开停（判据与语义见 {@link ReceiveService}）。
 */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 不传页面：这里没有页面，也不该碰任何一个页面正在用的观察者。
        ReceiveService.follow(this);
    }
}
