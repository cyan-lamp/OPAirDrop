package com.oshare.airdrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 通知上那两个按钮的回调。
 *
 * <p>它是 {@code exported=false} 的：能给它发广播的只有本应用自己，而那两个按钮的
 * {@link android.app.PendingIntent} 带着本应用的身份，所以通知栏按下去仍然到得了这里。
 * 反过来，任何别的应用都发不进来——一个能被外面伪造的「接受」，等于没有问过。
 *
 * <p>这里只做一件事：把编号和答案交给 {@link Confirmation}。所有状态都在那边，这个类不存
 * 任何东西，所以它被系统在任意时刻重建也不会丢什么。
 */
public final class DecisionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String id = intent.getStringExtra(Confirmation.EXTRA_ID);
        if (id == null) {
            return;
        }
        boolean accepted = Confirmation.ACCEPT.equals(intent.getAction());
        // 交给 ReceiveHost，而不是直接给 Confirmation。两个表面（这张通知、互传那张卡片）
        // 按下去的是同一个问题，所以答案必须落到同一个闩上；而那个闩在 ReceiveHost 手里，
        // Confirmation 只是它下面的一层。过去这里直连 Confirmation，等于让通知的答案绕过
        // 了卡片的闩——两边各答一次才发现漏了。
        ReceiveHost.answer(id, accepted, "notification");
    }
}
