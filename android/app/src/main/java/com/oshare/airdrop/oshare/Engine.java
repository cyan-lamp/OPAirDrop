package com.oshare.airdrop.oshare;

import android.content.Context;

/**
 * 收发体系路由：本应用的收发动作走哪一套。
 *
 * <p>两个体系共享同一个自研链路底座（StackKeeper 的 wonder0/awdltun0 与 hans 守护）——
 * 一加互传的 AirDrop 收发并不自带驱动管理，它跑在系统里已就绪的那条 AWDL 链路上，
 * 而这条链路在我们这台机器上正是自研栈建的。所以「切换体系」切的不是栈，是**收发动作
 * 由谁完成**：
 *
 * <ul>
 *   <li>{@link #OSHARE}（默认）：分享转给一加互传的发送界面（{@code IOSShareActivity}，
 *       priv-app，自带浮窗分享豁免）；接收由一加互传负责——它的接收开关开 10 分钟，与
 *       自研的「手动开监听」是同一形态，不是限制。</li>
 *   <li>{@link #CUSTOM}：自研 SendActivity 面板 + 自研接收监听（ReceiveService）。</li>
 * </ul>
 *
 * <p>存 SharedPreferences 而不是 settings.conf：这是 UI 路由偏好，Rust 侧不感知它。
 * 默认 {@link #OSHARE} 是用户的明确要求。
 */
public final class Engine {

    public static final String OSHARE = "oshare";
    public static final String CUSTOM = "custom";

    private static final String PREFS = "engine";
    private static final String KEY = "engine";

    /** 当前选中的收发体系，默认一加互传。 */
    public static String current(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, OSHARE);
    }

    /** 收发动作现在是否交给一加互传。 */
    public static boolean isOshare(Context context) {
        return OSHARE.equals(current(context));
    }

    public static void set(Context context, String engine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, engine).apply();
    }

    private Engine() {
    }
}
