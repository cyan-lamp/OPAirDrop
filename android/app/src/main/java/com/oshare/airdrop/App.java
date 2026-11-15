package com.oshare.airdrop;

import android.app.Application;

/**
 * 进程启动时把接收端接到互传的开关上。
 *
 * <p>接在这里而不是某个页面里，是因为跟随必须在没人看屏幕时也成立：{@link Oshare} 的观察者
 * 一旦注册就随进程存在，页面开不开都不影响它。放在 {@code MainActivity} 里会让「跟随」变成
 * 「打开过一次应用之后才跟随」，那是一个只在重启后才会暴露的差别。
 *
 * <p>这里做两件事，都只做一次、都跟着进程走：把接收端接到互传的开关上，把接收界面要用的
 * 那条回环通道开起来（{@link BridgeServer}）。启动顺序、单例归属、开关语义分别在
 * {@link Listener}、{@link Oshare} 里，各自只回答一个问题。
 */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 不传页面：这里没有页面，也不该碰任何一个页面正在用的观察者。
        ReceiveService.follow(this);
        // 接收界面（跑在互传进程里）从这里找过来。跟着进程走，只监听一次；起不来不是致命
        // 错误，接收会退回本应用自己的通知。
        BridgeServer.start(this);
    }
}
