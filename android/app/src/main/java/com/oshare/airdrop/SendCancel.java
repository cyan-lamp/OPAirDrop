package com.oshare.airdrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 发送那张卡上【取消发送】的回调。
 *
 * <p>它和 {@link DecisionReceiver} 是两条不同的路，所以分成两个类而不是加一个 action 了事：
 * 那一个接的是**收**的事（要不要收、收到一半要不要停），这一个接的是**发**的事。合成一个
 * 类的结果是它要同时认识 {@link ReceiveHost} 和 {@link SendHost}，而这两边没有一处共用状态。
 *
 * <p>为什么不是「收到广播就地关连接」：那一下同样会落在主线程上，而关一条正在传数据的
 * {@code SSLSocket} 会写 TLS 收尾——2026-11-19 那记 {@code NetworkOnMainThreadException} 就是
 * 这么来的（见 {@link HangUp}）。这里只把 session 交回 {@link SendHost#cancel}，真正动手在
 * 发送线程那边，由 {@link SendHost.Canceller} 接着。
 */
public final class SendCancel extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String session = intent.getStringExtra(SendHost.EXTRA_SESSION);
        if (session == null) {
            return;
        }
        // 返回值是「真的停下了才算真」。页面已经销毁（那边 unregister 过）时这里会拿到
        // false——那时没有传输可停，卡片也不该由这条广播去撤，交给它自己那一趟的收尾。
        if (!SendHost.cancel(session)) {
            Log.i("OPAirDropSend", "cancel from the card: no send is listening for " + session);
        }
    }
}
