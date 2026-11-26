package com.oshare.airdrop;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p># 三条线程纪律（2026-11-27 定，见 Plan.md §6.10-4 / §6.10-7）
 *
 * <ol>
 *   <li><b>本类所有 {@code su} 只跑在 {@code opairdrop-radio} 这一条线程上。</b>每条 su 都要
 *       {@code waitFor(3000)}，以前整条链跑在主线程上：最长 3 秒阻塞所有 {@code main.post}，
 *       期间界面刷新、对端快照、射频副标题一起卡住。</li>
 *   <li><b>裁决带代次。</b>以前 {@code reapply} 用 {@code removeCallbacksAndMessages(null)}
 *       清空主线程队列，顺带把已经排着的跨信道轮询和看门狗一起取消——「看门狗时灵时不灵」
 *       就是这么来的。现在只比代次：旧代次的轮询链自己退出，新代次重新开始。</li>
 *   <li><b>说的话要么是量到的，要么带时刻。</b>「热点已断开」这句以前在 {@code su} 返回之后
 *       就写，不管 wlan0 到底是什么状态；现在下发之后回读 {@code /sys/class/net/wlan0/flags}，
 *       写的是读回来的结论，失败时如实写「未生效」。这句话还有有效期（{@link #ACTION_HOLD_MS}），过期后
 *       副标题自动回落到磁盘回执 {@code radio-state}，不再停在最后一次动作上。</li>
 * </ol>
 */
public final class RadioSession {

    /** 会话的两个因子。任一为真即会话中。 */
    private static volatile boolean sendActive;
    private static volatile boolean listening;

    /** 看门狗开关与排程标志（exclusive 会话中周期性确保 down）。 */
    private static volatile boolean watchdogOn;
    private static volatile boolean watchdogScheduled;

    /**
     * 最近一次射频动作的人话，以及写下它的时刻。
     *
     * <p>回执（`radio-state`）只说守护的事，断开/恢复热点是应用自己干的，那句话只有这里有。
     * 但它是一个**动作**，不是**状态**：会话里做过一次动作之后状态可能又变了（系统把 wlan0
     * 拉回来、用户手动连上），所以它带有效期，过期就闭嘴，让磁盘回执说话。
     */
    private static volatile String lastAction;
    private static volatile long lastActionAt;

    /** 动作那句话的有效期：4 秒足够人看见一次反馈，又短到不会盖住随后变化的真实状态。 */
    private static final long ACTION_HOLD_MS = 4_000;

    /** 最近一次射频动作的人话；还没有动作、或动作已经过期时返回空串。 */
    public static String lastAction() {
        String line = lastAction;
        if (line == null || System.currentTimeMillis() - lastActionAt > ACTION_HOLD_MS) {
            return "";
        }
        return line;
    }

    private static void action(String line) {
        lastAction = line;
        lastActionAt = System.currentTimeMillis();
        Log.i("OPAirDrop", "STA 共存：" + line);
    }

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /** 所有 su / ip link 只在这条线程上跑（纪律 1）。 */
    private static final ExecutorService RADIO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "opairdrop-radio");
        thread.setDaemon(true);
        return thread;
    });

    /** 裁决代次：每来一次 {@link #reapply} 加一，旧代次的轮询与旧排队的 apply 自己退出（纪律 2）。 */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    /** 最近一次进入本类的 Context，供后台线程与看门狗使用。 */
    private static volatile Context appContext;

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

    /**
     * 按当前状态重放一遍射频请求。幂等，可以在任何线程调。
     *
     * <p>不清理主线程队列：清一次就把别人的排程（跨信道轮询、看门狗）一起删了。新代次
     * 一旦开始，旧代次的轮询链在下一拍自己看到代次变了就退出。
     */
    public static void reapply(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        int mine = GENERATION.incrementAndGet();
        submit(() -> {
            if (mine != GENERATION.get()) {
                return;
            }
            apply(app);
        });
    }

    /** 排到射频线程上。已在射频线程时直接跑，避免自己排到自己后面去。 */
    private static void submit(Runnable task) {
        if (Thread.currentThread().getName().equals("opairdrop-radio")) {
            task.run();
            return;
        }
        RADIO.execute(task);
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
                    action("会话外·共享：恢复热点连接");
                    stopWatchdog();
                    bringUp();
                    RadioShare.ask(dir, RadioShare.Want.SHARE);
                } else {
                    action("会话外·独占：热点保持断开，不做动作");
                }
                return;
            }

            RadioShare.ask(dir, want);
            if (want == RadioShare.Want.EXCLUSIVE) {
                // 独占：断开热点并挂看门狗（周期确认 down，见类注释）。
                action("会话中·独占：断开热点");
                startWatchdog();
                return;
            }
            // 共享：先恢复关联（独占期间可能断着），等确认后再看 STA 落点——
            // AP 恰好在 149 就共存，落在别的信道再断开（第 3 条的例外）。
            action("会话中·共享：恢复热点，等信道判定");
            stopWatchdog();
            bringUp();
            pollShareSettled(0, GENERATION.get());
        } catch (java.io.IOException failed) {
            // 请求写不进去（磁盘/目录）：射频保持原状，下次会话事件会再试。
            Log.w("OPAirDrop", "射频请求写不进去：" + failed);
        }
    }

    /** share 确认后：STA 在 149 放行；在其他信道断开热点（Wi-Fi 保持开启）。 */
    private static void pollShareSettled(int round, int mine) {
        HANDLER.postDelayed(() -> {
            if (mine != GENERATION.get()) {
                return;
            }
            Context app = appContext;
            if (app == null) {
                return;
            }
            RadioShare.State state = RadioShare.read(app.getFilesDir());
            if (!state.answered()
                    || state.receipt().standing() != RadioShare.Standing.CONFIRMED) {
                if (round < POLLS && (sendActive || listening)) {
                    pollShareSettled(round + 1, mine);
                }
                return;
            }
            int mhz = state.staMhz();
            if (mhz > 0 && mhz != 5745) {
                submit(() -> {
                    action("会话中·共享：AP 在 " + mhz + " MHz，跨信道，断开热点");
                    bringDown();
                });
            } else if (mhz == 5745) {
                action("会话中·共享：AP 在 149 信道，同信道共存，热点保持");
            }
        }, POLL);
    }

    /** 看门狗的一拍：先把排程标志清掉，再确认一次 down，最后重新排。 */
    private static final Runnable WATCHDOG_ROUND = () -> {
        watchdogScheduled = false;
        if (!watchdogOn) {
            return;
        }
        submit(() -> {
            if (!watchdogOn) {
                return;
            }
            ensureDown();
            scheduleWatchdog();
        });
    };

    private static void scheduleWatchdog() {
        if (watchdogScheduled || !watchdogOn) {
            return;
        }
        watchdogScheduled = true;
        HANDLER.postDelayed(WATCHDOG_ROUND, WATCHDOG_TICK);
    }

    private static void startWatchdog() {
        watchdogOn = true;
        bringDown();
        scheduleWatchdog();
    }

    private static void stopWatchdog() {
        watchdogOn = false;
        watchdogScheduled = false;
        HANDLER.removeCallbacks(WATCHDOG_ROUND);
    }

    /** 看门狗的一拍：独占会话期间确保热点是断开的。 */
    private static void ensureDown() {
        if (isDown()) {
            return;
        }
        action("看门狗：热点被系统拉回，重新断开");
        bringDown();
    }

    /** 下发一条 ip link 并回读，返回「量到的状态是否就是想要的那个」（纪律 3）。 */
    private static boolean setLink(boolean up) {
        try {
            Process process = new ProcessBuilder("su", "-c",
                    "ip link set " + STA_IF + (up ? " up" : " down")).start();
            process.waitFor(3000, TimeUnit.MILLISECONDS);
            process.destroy();
        } catch (Throwable failed) {
            Log.w("OPAirDrop", (up ? "恢复" : "断开") + "热点失败：" + failed);
            return false;
        }
        return up != isDown();
    }

    private static boolean isDown() {
        // 判据是「接口被管理性地关掉了吗」，也就是 `IFF_UP` 那一位有没有置——它不带 scope，
        // 就是 `/sys/class/net/wlan0/flags` 的最低位。**不能用 `ip link` 的 `state`**：
        // wlan0 恢复后、还没关联上 AP 时 operstate 是 `NO-CARRIER ... state DOWN`，那是
        // 「没连着」不是「被关了」，按它判会把刚恢复成功的动作报成「未生效」
        // （2026-11-27 01:23 真机实测：`flags=0x1003`、`ip link` 里 UP 与 state DOWN 同时出现）。
        try {
            Process process = new ProcessBuilder("su", "-c",
                    "cat /sys/class/net/" + STA_IF + "/flags").start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor(3000, TimeUnit.MILLISECONDS);
            process.destroy();
            if (line == null || line.trim().isEmpty()) {
                return false;
            }
            return (Long.decode(line.trim()) & 0x1L) == 0;
        } catch (Throwable failed) {
            Log.w("OPAirDrop", "读 wlan0 状态失败：" + failed);
            return false;
        }
    }

    /**
     * 断开热点：链路层 down（Wi-Fi 总开关不动，supplicant 立刻 DISCONNECTED）。
     *
     * <p>回执写的是**读回来的**状态：{@code su} 退出 0 只说明命令被接受，不说明接口真的
     * down 了（系统侧可能立刻把它拉回来）。
     */
    private static void bringDown() {
        if (setLink(false)) {
            action("热点已断开（Wi-Fi 保持开启，wlan0 DOWN）");
        } else {
            action("断开热点未生效（wlan0 仍 UP）");
        }
    }

    /** 恢复热点：链路层 up（autojoin 自动把关联接回来），同样回读后再说话。 */
    private static void bringUp() {
        if (setLink(true)) {
            action("热点已恢复（wlan0 UP）");
        } else {
            action("恢复热点未生效（wlan0 仍 DOWN）");
        }
    }
}
