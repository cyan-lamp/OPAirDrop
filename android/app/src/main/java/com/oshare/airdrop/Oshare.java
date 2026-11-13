package com.oshare.airdrop;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.oshare.airdrop.SettingsScreen;

import java.io.IOException;

/**
 * 一加互传的「接收范围」，作为本机是否应答 AirDrop 的开关。
 *
 * <p># 为什么用互传的开关，而不是自己的第二个开关
 *
 * <p>两台设备都要为「我现在可以被找到」这件事给出一个答案，而用户只想按一次。互传的
 * 接收范围本来就是那个答案：它由系统的设置页写入，由系统自己在到期时收回，由系统的
 * 连接层执行。我们读同一个值，于是两个入口是同一件事，而不是两件需要保持同步的事。
 *
 * <p># 为什么读 {@code Settings.System} 而不是 hook 互传
 *
 * <p>{@code oshare_state} 是互传写进 {@code Settings.System} 的普通整数。读它不需要权限，
 * 也不需要进到互传的进程里：{@code ContentObserver} 就够，而且互传自己改这个值的两条路
 * （用户在设置页选择、10 分钟到期自动降级）都会经过同一个 provider，所以两条路都会回调到
 * 这里。hook 会得到同样的信息，代价是把我们的稳定性绑在它混淆过的类名上。
 *
 * <p># 为什么只有「所有人」算开
 *
 * <p>互传的三个取值是：{@code 1} 所有人（10 分钟）、{@code 2} 仅限联系人、{@code 0} 接收
 * 关闭。本栈没有联系人过滤，所以 {@code 2} 对我们只意味着一件事：无法兑现「只让联系人找到
 * 我」。继续监听就等于对每一个对端承诺一个我们做不到的范围——宁可少收，不可多暴露。
 * 10 分钟到期时系统正是把值降为 {@code 2}（或 {@code 0}），于是到期、手动改小、手动关闭，
 * 在这里是同一种处理。
 *
 * <p># 为什么这里是只读的
 *
 * <p>这个类只回答「互传说的是什么」，不决定接收端开不开。合成两个来源（互传的开关、通知栏
 * 与功能页那个手动开关）是 {@link ReceiveService} 的事：那个判断需要一个地方看全，
 * 而分别放在两个类里就会有两份「现在该不该开」，迟早不一致。
 */
final class Oshare {
    private static final String TAG = "OPAirDrop";

    /** 互传写入 {@code Settings.System} 的键：接收范围。 */
    static final String KEY = "oshare_state";

    /** 所有人（10 分钟）：唯一一个承诺「任何设备都能找到我」的取值。 */
    private static final int EVERYONE = 1;

    /** 互传自己也没有打开接收，或者值读不出来。 */
    private static final int NOT_LISTENING = -1;

    private Oshare() {
    }

    /**
     * 当前接收范围，读不出来时返回负数。
     *
     * <p>读一个世界可读的 provider 值仍然可能失败（受限用户、被裁掉的 provider），所以这里
     * 不假装它一定成功：返回值会被当成「不开」，那是这个判断唯一安全的默认。
     */
    static int stateOf(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(), KEY, 0);
        } catch (RuntimeException unreadable) {
            Log.w(TAG, "读不到 " + KEY + "：" + unreadable);
            return NOT_LISTENING;
        }
    }

    /** 互传此刻是不是「所有人（10 分钟）」——唯一一个我们能兑现的范围。 */
    static boolean everyone(Context context) {
        return stateOf(context) == EVERYONE;
    }

    /** 接收范围的一句话说明，给状态页看。 */
    static String label(Context context) {
        switch (stateOf(context)) {
            case EVERYONE:
                return "所有人（10 分钟）";
            case 2:
                return "仅限联系人（不跟随：本栈不过滤联系人）";
            case 0:
                return "接收关闭";
            default:
                return "读不到，按关闭处理";
        }
    }

    /**
     * 设置页里的「跟随一加互传接收范围」。
     *
     * <p>读不出来时按「跟随」处理：默认值就是跟随，而一个读不出来的设置文件更可能是还没
     * 有人写过它，而不是有人特意关掉了这一项。
     */
    static boolean following(Context context) {
        try {
            SettingsScreen screen = SettingsScreen.open(SettingsFile.of(context).toPath());
            SettingsScreen.Setting setting = screen.setting("receive.follow_oshare");
            return setting == null || !"false".equals(setting.value().trim());
        } catch (IOException | RuntimeException unreadable) {
            Log.w(TAG, "读不到 receive.follow_oshare，按跟随处理：" + unreadable);
            return true;
        }
    }
}
