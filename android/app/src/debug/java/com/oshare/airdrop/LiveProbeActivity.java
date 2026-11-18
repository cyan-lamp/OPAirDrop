package com.oshare.airdrop;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * 探针的第二个入口：**不经过广播**。
 *
 * <p>为什么要有它：ColorOS 会把闲置的应用冻进 {@code DEFER_BY_OPLUS / FROZEN} 名单。被冻住的
 * 应用收不到广播 —— 连 {@code -n} 指定组件的显式广播也一样，整条 {@code BroadcastRecord} 挂在
 * 队列里 {@code terminalCount=0}、{@code dispatchClockTime=1970-01-01}、
 * {@code reason: mBroadcastConsumerDeferApply}，而 {@code am broadcast} 照样回 {@code result=0}。
 * 2026-11-19 这一晚就是这么卡住的：探针"发出去了"，日志里一个字都没有。
 *
 * <p>启动 Activity 不受这个限制，而且它恰好能解冻进程。所以这里的动作顺序是**先 finish、后投卡**：
 * 界面一交还出去，前台就落回别的应用（没解冻之前那个应用），等 700 ms 之后
 * {@code invite_fresh} 那一跳把卡投出来时，SystemUI 看到的前台应用已经不是我们了 ——
 * 这正是「人在别的应用里，对面发来一张照片」那个真实场景。
 *
 * <p>{@code Theme.NoDisplay} 要求 {@code onResume} 之前就 finish，这里在 {@code onCreate}
 * 里就调了，屏幕上不会闪一下。
 */
public final class LiveProbeActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("mode");
        final String chosen = mode == null ? "invite_fresh" : mode;
        finish();
        // 300 ms 只是让前台真的落回去；invite_fresh 自己还会再等 700 ms。
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> LiveProbe.fire(this, chosen), 300);
    }
}
