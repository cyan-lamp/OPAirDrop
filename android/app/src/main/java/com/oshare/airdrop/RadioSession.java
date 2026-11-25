package com.oshare.airdrop;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * 「STA 共存」的会话控制器：什么时候该对射频提出什么请求。
 *
 * <p># 会话是什么
 *
 * <p>会话 = 发送页开着 <b>或</b> 接收监听开着。射频策略只在这段期间生效：
 *
 * <ul>
 *   <li>开关开（{@link RadioShare.Want#SHARE}）：请求共享。回执确认后看 STA 落在哪——
 *       AP 恰好在 149（5745 MHz）就是同信道共存，一个字节都不动；AP 不在 149 就断开
 *       AP（Wi-Fi 保持开启），跨信道的 45% 损耗不值得付。</li>
 *   <li>开关关（{@link RadioShare.Want#EXCLUSIVE}）：请求独占，并断开 AP。Wi-Fi 一样
 *       保持开启（守护与脚本在 2026-11-25 会话制改造后都不再关 Wi-Fi），断开的只是
 *       关联。</li>
 * </ul>
 *
 * <p>会话外（两个因子都不在）：恢复——AP 重新关联，请求文件写成 {@code share}。
 *
 * <p># AP 怎么断、怎么恢复
 *
 * <p>用 {@link WifiManager#disableNetwork}/{@link WifiManager#enableNetwork}：前者把
 * 当前网络标记为不自动加入并断开关联（Wi-Fi 总开关不动），后者恢复自动加入并立即
 * 重连。netId 在断开前从 {@link WifiInfo#getNetworkId} 记下来。这需要
 * {@code CHANGE_WIFI_STATE} 权限（manifest 里已声明）。
 *
 * <p># 为什么轮询回执
 *
 * <p>share 的确认是守护进程写的，断 AP 要等它确认之后（否则抢占时机对不上）：确认行
 * 出现时读 {@code sta=}——149 放行，其他信道断开。轮询复用 {@link #POLL}，上限
 * {@link #POLLS}，超了就放弃（Wi-Fi 还开着，损失只是没断干净）。
 */
public final class RadioSession {

    /** 会话的两个因子。任一为真即会话中。 */
    private static volatile boolean sendActive;
    private static volatile boolean listening;

    /** 断开前记下的网络，恢复时用它重连。 */
    private static volatile int savedNetId = -1;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    /** share 确认后的跨信道检查：250ms 一次，最多 40 次（10 秒）。 */
    private static final long POLL = 250;
    private static final int POLLS = 40;

    private RadioSession() {}

    /** SendActivity 的生命周期挂点。 */
    public static void setSendActive(Context context, boolean active) {
        sendActive = active;
        reapply(context);
    }

    /** ReceiveService 的生命周期挂点（onStartCommand 起监听之后、onDestroy 停掉之后）。 */
    public static void setListening(Context context, boolean active) {
        listening = active;
        reapply(context);
    }

    /** 用户拨了开关：偏好变了，按当前会话状态立即重放一遍。 */
    public static void onWantChanged(Context context) {
        reapply(context);
    }

    /** 按当前状态重放一遍射频请求。幂等：随便调。 */
    public static void reapply(Context context) {
        Context app = context.getApplicationContext();
        HANDLER.removeCallbacksAndMessages(null);
        HANDLER.post(() -> apply(app));
    }

    private static void apply(Context app) {
        boolean session = sendActive || listening;
        File dir = app.getFilesDir();
        RadioShare.Want want = RadioShare.read(dir).want();

        try {
            if (!session) {
                // 会话外：恢复 AP 关联，射频请求停在 share（Wi-Fi 开 + STA 关联）。
                restoreAp(app);
                RadioShare.ask(dir, RadioShare.Want.SHARE);
                return;
            }

            RadioShare.ask(dir, want);
            if (want == RadioShare.Want.EXCLUSIVE) {
                // 独占：先断 AP，再等守护把 Wi-Fi 打开（如果它还关着）。顺序无妨，
                // 两件事互不依赖。
                dropAp(app);
                return;
            }
            // 共享：等确认，再看 STA 落点决定要不要断 AP（跨信道才断）。
            pollShareSettled(app, 0);
        } catch (java.io.IOException failed) {
            // 请求写不进去（磁盘/目录）：射频保持原状，下次会话事件会再试。
            Log.w("OPAirDrop", "射频请求写不进去：" + failed);
        }
    }

    /** share 确认后：STA 在 149 放行；在其他信道断 AP（Wi-Fi 保持开启）。 */
    private static void pollShareSettled(Context app, int round) {
        HANDLER.postDelayed(() -> {
            RadioShare.State state = RadioShare.read(app.getFilesDir());
            if (!state.answered()
                    || state.receipt().standing() != RadioShare.Standing.CONFIRMED) {
                if (round < POLLS && (sendActive || listening)) {
                    pollShareSettled(app, round + 1);
                }
                return;
            }
            int mhz = state.staMhz();
            if (mhz > 0 && mhz != 5745) {
                dropAp(app);
            }
        }, POLL);
    }

    /** 断开当前 AP：记下 netId，禁用该网络（Wi-Fi 总开关不动）。 */
    private static void dropAp(Context app) {
        WifiManager wifi = app.getSystemService(WifiManager.class);
        if (wifi == null) {
            return;
        }
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || info.getNetworkId() < 0) {
            return;
        }
        savedNetId = info.getNetworkId();
        try {
            wifi.disableNetwork(savedNetId);
            Log.i("OPAirDrop", "STA 共存：已断开 AP（netId=" + savedNetId + "），Wi-Fi 保持开启");
        } catch (Throwable failed) {
            Log.w("OPAirDrop", "断开 AP 失败：" + failed);
        }
    }

    /** 恢复 AP：把断开前那个网络重新启用，自动加入会把关联接回来。 */
    private static void restoreAp(Context app) {
        int netId = savedNetId;
        savedNetId = -1;
        if (netId < 0) {
            return;
        }
        WifiManager wifi = app.getSystemService(WifiManager.class);
        if (wifi == null) {
            return;
        }
        try {
            wifi.enableNetwork(netId, true);
            Log.i("OPAirDrop", "STA 共存：已恢复 AP（netId=" + netId + "）");
        } catch (Throwable failed) {
            Log.w("OPAirDrop", "恢复 AP 失败：" + failed);
        }
    }
}
