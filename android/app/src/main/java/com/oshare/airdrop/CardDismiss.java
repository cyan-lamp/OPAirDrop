package com.oshare.airdrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 收完那张卡上的「完成」按下去之后，把它收起来。
 *
 * <p>为什么需要一条广播：{@code PendingIntent} 只能指向某个组件，而「把这张卡撤掉」是一个动作
 * 而不是一个界面——用 Activity 去做会闪一下白屏，用这一条就什么也不发生。
 */
public final class CardDismiss extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        LiveCard.dropById(context, intent.getIntExtra("card_id", 0x0B00));
        // 点【打开文件】时，那颗原始动作随广播带过来：撤完卡再触发它。
        android.app.PendingIntent open = intent.getParcelableExtra(LiveCard.OPEN_PI);
        if (open != null) {
            try {
                open.send();
            } catch (android.app.PendingIntent.CanceledException ignored) {
                // 那颗动作已失效：卡撤了，打开这件事只能作罢。
            }
        }
    }
}
