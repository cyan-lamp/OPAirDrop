package com.oshare.airdrop;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 「STA 共存」的会话控制器：什么时候该对 AP 连接做什么。
 *
 * <p># 会话是什么
 *
 * <p>会话 = 发送页开着 <b>或</b> 接收监听开着。会话中按开关控制 AP：
 *
 * <ul>
 *   <li>开关开（{@link RadioShare.Want#SHARE}）：请求共享，回执确认后看 STA 落点——
 *       AP 恰好在 149（5745 MHz）就是同信道共存，一个字节都不动；AP 不在 149 就断开
 *       热点（Wi-Fi 保持开启），跨信道的 45% 损耗不值得付。</li>
 *   <li>开关关（{@link RadioShare.Want#EXCLUSIVE}）：请求独占并断开热点，Wi-Fi 一样
 *       保持开启（守护与脚本在 2026-11-25 会话制改造后都不再关 Wi-Fi）。</li>
 * </ul>
 *
 * <p># 会话外的规则（用户的规则表，2026-11-25 定稿）
 *
 * <p>开关开：恢复 AP 关联。开关关：什么都不做——断开的状态保持下去，既不恢复也
 * 不再断开；用户手动连上的就让它连着。会话外两种状态都永不主动断开。
 *
 * <p># 热点怎么断、怎么恢复
 *
 * <p>用 root 的链路层原语 {@code ip link set wlan0 down/up}（2026-11-26 实测定稿）：
 * down 后 supplicant 立刻 DISCONNECTED（热点断了，Wi-Fi 总开关仍是开启状态），
 * up 后 autojoin 自动把关联接回 COMPLETED——恢复动作连 netId 都不需要。wonder0
 * 在同一芯片的另一个接口上，down 不影响 AWDL 链路。
 *
 * <p>为什么不用 WifiManager.disableNetwork：Android 12 起对非系统应用收口——
 * {@code getConnectionInfo} 的 networkId 恒为 -1（「cleared for non-owner」，
 * 2026-11-26 实测轮询 20 次全是 -1），{@code disableNetwork} 对用户的网络被
 * 系统静默忽略。这两条 API 在这台机器上都是摆设。
 *
 * <p># 看门狗
 *
 * <p>独占会话期间每 5 秒确认一次 wlan0 是 down 的：框架的故障恢复（ClientModeManager
 * 检测到接口异常会重建 STA）可能把接口拉起来，看门狗把它按回去。会话结束或切到
 * 共享时看门狗停——exclusive 的会话外保持 down（第 7/8 条不恢复），但不再强按。
 */
public final class RadioSession {

    /** 会话的两个因子。任一为真即会话中。 */
    private static volatile boolean sendActive;
    private static volatile boolean listening;

    /** 看门狗开关与句柄（exclusive 会话中周期性确保 down）。 */
    private static volatile boolean watchdogOn;
    /** 看门狗的自续体：同一实例才能被 removeCallbacks 精确摘掉。 */
    private static Runnable watchdogTick;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    /** share 确认后的跨信道检查：250ms 一次，最多 40 次（10 秒）。 */
    private static final long POLL = 250;
    private static final int POLLS = 40;
    /** 独占看门狗的节拍。 */
    private static final long WATCHDOG_TICK = 5000;

    /** wlan0 的名字。ColorOS 的 STA 接口固定是它（共享模式也在这上面关联）。 */
    private static final String STA_IF = "wlan0";

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
        Log.i("OPAirDrop", "射频裁决：session=" + session + " send=" + sendActive
                + " listen=" + listening + " want=" + want.word());

        try {
            if (!session) {
                // 会话外的规则（2026-11-25 按用户的规则表定稿）：
                //  - 共享（开关开）：恢复 AP 关联——第 9/10 条「会话结束恢复热点」。
                //  - 独占（开关关）：什么都不做——第 7/8 条「无需恢复热点」，
                //    断开的状态保持到下一次会话（或用户手动连）。
                //  两种情况下都不主动断开（第 2/4 条：会话外连着就连着）。
                if (want == RadioShare.Want.SHARE) {
                    stopWatchdog();
                    bringUp(app);
                    RadioShare.ask(dir, RadioShare.Want.SHARE);
                }
                return;
            }

            RadioShare.ask(dir, want);
            if (want == RadioShare.Want.EXCLUSIVE) {
                // 独占：断开热点并挂看门狗（周期确认 down，见类注释）。
                startWatchdog(app);
                return;
            }
            // 共享：先恢复关联（独占期间可能断着），等确认后再看 STA 落点——
            // AP 恰好在 149 就共存，落在别的信道再断开（第 3 条的例外）。
            stopWatchdog();
            bringUp(app);
            pollShareSettled(app, 0);
        } catch (java.io.IOException failed) {
            // 请求写不进去（磁盘/目录）：射频保持原状，下次会话事件会再试。
            Log.w("OPAirDrop", "射频请求写不进去：" + failed);
        }
    }

    /** share 确认后：STA 在 149 放行；在其他信道断开热点（Wi-Fi 保持开启）。 */
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
                Log.i("OPAirDrop", "STA 共存：AP 在 " + mhz + " MHz，跨信道，断开热点");
                bringDown(app);
            }
        }, POLL);
    }

    private static void startWatchdog(Context app) {
        watchdogOn = true;
        bringDown(app);
        if (watchdogTick == null) {
            watchdogTick = () -> {
                if (!watchdogOn) {
                    return;
                }
                ensureDown(app);
                HANDLER.postDelayed(watchdogTick, WATCHDOG_TICK);
            };
            HANDLER.postDelayed(watchdogTick, WATCHDOG_TICK);
        }
    }

    private static void stopWatchdog() {
        watchdogOn = false;
        if (watchdogTick != null) {
            HANDLER.removeCallbacks(watchdogTick);
        }
    }

    /** 看门狗的一拍：独占会话期间确保热点是断开的。 */
    private static void ensureDown(Context app) {
        if (isDown()) {
            return;
        }
        Log.i("OPAirDrop", "STA 共存：看门狗发现热点已连上，重新断开");
        bringDown(app);
    }

    private static boolean isDown() {
        // 接口 down 时 operstate 是 DOWN/NO-CARRIER；连着时是 UP。用 ip link 读。
        try {
            Process process = new ProcessBuilder("su", "-c",
                    "ip link show " + STA_IF + " | grep -oE 'state [A-Z]+'")
                    .start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            process.destroy();
            return line != null && !line.contains("UP");
        } catch (Throwable failed) {
            Log.w("OPAirDrop", "读 wlan0 状态失败：" + failed);
            return false;
        }
    }

    /** 断开热点：链路层 down（Wi-Fi 总开关不动，supplicant 立刻 DISCONNECTED）。 */
    private static void bringDown(Context app) {
        try {
            Process process = new ProcessBuilder("su", "-c",
                    "ip link set " + STA_IF + " down").start();
            process.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            process.destroy();
            Log.i("OPAirDrop", "STA 共存：已断开热点（" + STA_IF + " down），Wi-Fi 保持开启");
        } catch (Throwable failed) {
            Log.w("OPAirDrop", "断开热点失败：" + failed);
        }
    }

    /** 恢复热点：链路层 up（autojoin 自动把关联接回来）。 */
    private static void bringUp(Context app) {
        try {
            Process process = new ProcessBuilder("su", "-c",
                    "ip link set " + STA_IF + " up").start();
            process.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS);
            process.destroy();
            Log.i("OPAirDrop", "STA 共存：已恢复热点（" + STA_IF + " up）");
        } catch (Throwable failed) {
            Log.w("OPAirDrop", "恢复热点失败：" + failed);
        }
    }
}
