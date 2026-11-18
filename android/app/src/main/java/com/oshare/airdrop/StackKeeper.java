package com.oshare.airdrop;

import android.content.Context;

import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * AWDL 链路的看护者：让「磁贴开着」这句话在**两层**上都成立。
 *
 * <p># 它补的是哪一个窟窿
 *
 * <p>「能被 iPhone 看见」这件事有两层，缺一层都不成立：
 *
 * <ol>
 *   <li><b>应用层</b>：{@code receive.manual} 为真、{@link ReceiveService} 是前台、8770 在
 *       LISTEN、蓝牙在广播；
 *   <li><b>内核层</b>：{@code wonder.ko} 等四个模块在、{@code awdld} 在跑、{@code awdltun0}
 *       这条隧道在。
 * </ol>
 *
 * <p>磁贴（{@link AirdropTile}）只碰得到第一层——它走的是 {@link ReceiveService#enable}，
 * 而把内核层拉起来的那一个入口 {@link DriverSetup#boot} 全项目只有功能页那个【安装】按钮在调。
 * 2026-11-18 16:56 的真机日志把这条缝暴露得很干净：
 *
 * <pre>
 *   W OPAirDrop: announcer could not start on awdltun0 — java.io.IOException: 没有网卡 awdltun0（AWDL 链路没建）
 * </pre>
 *
 * <p>于是「从磁贴开、不打开应用」这个最自然的用法，恰好是唯一不会建链的用法：接收端在听、
 * 蓝牙在喊，而 mDNS 广播连网卡都没找着——分享面板里当然什么都没有。
 *
 * <p># 为什么判据是 ip link 而不是回执
 *
 * <p>{@code files/stack-state} 是**那一刻的账**，不是现在的实况。这台机器上量到过最坏的一
 * 种组合：回执写着 {@code stack up boot=a6cb6b5b ... daemon=21727 tun=up}，而同一个 boot 里
 * 模块早已卸掉、守护进程早就不在、{@code awdltun0} 也没有——两个 boot id 一致，所以
 * {@link StackState.State#up()} 会说「已就绪」。回执只能回答「上一次建链走到哪一步」，回答
 * 不了「现在还通不通」。
 *
 * <p>「现在还通不通」只有一个诚实的判据：{@code awdltun0} 这条隧道在不在。它由 {@code awdld}
 * 创建并持有，守护进程一退出内核就把它收走；反过来说，它在就等于守护进程在。
 * {@link Responder#rearm()} 走的是同一个探针（{@code NetworkInterface.getByName}），并且
 * 在真机上打出过 {@code re-pinned the announcer to awdltun0 (index 141)}，所以这个调用在应用
 * 进程里看得见这条隧道，不需要 root。
 *
 * <p># 为什么它敢自己动驱动
 *
 * <p>因为不动才是错的。{@code awdl-stack.sh boot} 的冷路径会把 Wi-Fi 关掉约二十五秒——这是
 * AWDL 独占射频的代价，不是这里能绕过去的东西；而 {@code share} 那套仲裁要等在跑的
 * {@code awdld}，链路没起来时它无从谈起。所以顺序只能是先建链、再谈分享。代价说在明处：
 * 建链期间 {@link #note()} 写着「Wi-Fi 会断开」，磁贴显示【不可用】。
 *
 * <p># 它不管什么
 *
 * <p>不决定「该不该收」（那是 {@link ReceiveService#listening}），不碰 8770，不发广播，
 * 不改设置。它只做两件事：链路不在就把它建起来；广播借着链路建好之后再起一次。
 */
final class StackKeeper {

    private static final String TAG = "OPAirDrop";

    /**
     * 那条隧道。判活只认它一个，理由见类注释；名字与 {@code awdl-stack.sh} 同一个词。
     */
    private static final String TUNNEL = Responder.DEFAULT_INTERFACE;

    /** 多久看一眼。八秒是一次蓝牙广播的间隔量级，比它更勤只是在问同一个问题。 */
    private static final long TICK_MS = 8_000L;

    /**
     * 第一次重试等多久，以及它的上限。
     *
     * <p>必须有退避，而且必须封顶。一次冷装要关二十五秒 Wi-Fi；链路若是**根本**建不起来
     * （驱动没装成、机型不对、内存不够），一个固定间隔的重试会把手机变成每隔一分钟掉一次线的
     * 机器，而人只会觉得「这个 App 把网搞坏了」。六十秒起步、每次翻倍、十五分钟封顶：前几次
     * 覆盖「链路只是晚到」这种会自己好的情况，之后退到「偶尔再试一次」，不再拿手机的网去赌。
     */
    private static final long FIRST_RETRY_MS = 60_000L;
    private static final long LAST_RETRY_MS = 900_000L;

    /** 看护线程。同一时刻只有一个；{@link #wanted} 是它该不该继续。 */
    private static volatile Thread worker;
    private static volatile boolean wanted;

    /**
     * 现在这条链路在不在。
     *
     * <p>给磁贴用，所以只读内存：那一次 {@code ip link} 由看护线程去做，磁贴不该在画一格图标
     * 的时候去枚举网卡。
     */
    private static volatile boolean linked;

    /** 通知上、磁贴上那句话；空表示一切正常。 */
    private static volatile String note = "";

    private StackKeeper() {}

    /**
     * 这条隧道现在在不在。
     *
     * <p>唯一的活判据，见类注释。枚举失败按「不在」算：一个答不上来的探针不能当成好消息——
     * 那正是这台机器上次栽的那个跟头（回执说是好的）。
     */
    static boolean link() {
        try {
            return NetworkInterface.getByName(TUNNEL) != null;
        } catch (SocketException cannotTell) {
            return false;
        }
    }

    /** 通知上那句话的补充：不空的时候它比「附近设备可发现」更该被读到。 */
    static String note() {
        return note;
    }

    /** 链路在不在，读的是看护线程上一次的结论。 */
    static boolean linked() {
        return linked;
    }

    /**
     * 开始看护。幂等：接收端每次 {@code startForegroundService} 都会走到这里。
     *
     * <p>线程名与功能页那条（{@code opairdrop-driver}）不同：两条都可能同时在跑，日志里要分得清
     * 哪一条是人在按、哪一条是自己醒的。
     */
    static void watch(Context context) {
        wanted = true;
        if (worker != null) {
            return;
        }
        synchronized (StackKeeper.class) {
            if (worker != null) {
                return;
            }
            Context app = context.getApplicationContext();
            Thread thread = new Thread(() -> loop(app), "opairdrop-stack");
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }
    }

    /**
     * 不再看护。
     *
     * <p>只置一个标志，**不 interrupt**：这条线程可能正卡在 {@code su} 里跑那二十五秒的驱动
     * 交换，而 {@code ProcessBuilder} 被中断时 {@link DriverSetup#run} 会 {@code destroy()}
     * 那个 su——把一次做到一半的换驱动砍掉，是这台机器上唯一能把 Wi-Fi 弄丢的操作
     * （{@code rc=10}，见 {@code awdl-stack.sh}）。所以「停下来」的意思是「这一轮跑完就别再看
     * 了」，不是「现在就断」。
     */
    static void unwatch() {
        wanted = false;
    }

    /** 看护本体：链路不在就建，建好了就让广播再起一次。 */
    private static void loop(Context context) {
        long retryMs = FIRST_RETRY_MS;
        long nextAttempt = 0;
        try {
            while (wanted) {
                // 先记下上一次的结论：链路的有无与「那句话」是两件事。链路刚断时那句话可能
                // 还是空的，字没变而磁贴那一格必须变——只按字去重会让一块绿灯亮到底。
                boolean before = linked;
                linked = link();
                if (linked) {
                    if (!before) {
                        // 链路从无到有：上一次那句话作废，退避也回到起点（下次真出事，先按
                        // 六十秒再问一次，而不是接着上一次的十五分钟）。
                        note = "";
                        retryMs = FIRST_RETRY_MS;
                        push(context);
                    }
                    // 链路在而广播没在，是另一条独立的失败（换网卡、守护进程重启换了 ifindex、
                    // 上次启动时链路还没建好就放弃了）。这一句幂等，起来之后什么都不做。
                    Listener.of(context).retryAnnouncing();
                } else {
                    if (before) {
                        push(context);
                    }
                    long now = System.currentTimeMillis();
                    if (now >= nextAttempt) {
                        nextAttempt = now + retryMs;
                        retryMs = Math.min(retryMs * 2, LAST_RETRY_MS);
                        build(context);
                    }
                }
                if (!wanted) {
                    break;
                }
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException stop) {
                    // 没人该中断这条线程（见 unwatch），真被中断了也只是提前看一眼 wanted。
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            worker = null;
        }
    }

    /**
     * 建一次链，并把结果说出来。
     *
     * <p>{@link DriverSetup#boot} 自己会解包、比对、跑 {@code boot}，并且在**每一条**退路上
     * 写回执——这里不重做其中任何一步，只负责把「正在做」和「做成了没有」变成一句人话。
     */
    private static void build(Context context) {
        if (DriverSetup.running()) {
            // 功能页那个按钮已经在跑了。让它跑完，不抢：两次 boot 会互相踩（见 DriverSetup.RUNNING）。
            return;
        }
        say(context, "正在建 AWDL 链路（冷装几十秒，Wi-Fi 会断开）");
        Log.i(TAG, "stack| building: " + TUNNEL + " is absent");
        DriverSetup.Result result = DriverSetup.boot(context, name(context));
        for (String line : result.lines()) {
            Log.i(TAG, "stack| " + line);
        }
        Log.i(TAG, "stack| boot code=" + result.code() + " receipt=" + result.state().line());
        if (result.code() == DriverSetup.STILL_RUNNING) {
            // 脚本还在跑，不是失败。说成失败会让人去点第二次，而那正是要避免的：
            // 一次半途的驱动交换叠上第二次，手机会没有 Wi-Fi。等下一个 tick，回执自己会到。
            say(context, "还在建 AWDL 链路，稍等一下");
            return;
        }
        if (link()) {
            linked = true;
            say(context, "");
            // 建好之后立刻把广播再起一次，不等下一个 tick：这条路径上人正拿着手机等文件，
            // 而那一次 tick 最多要等八秒。它是幂等的，链路在的时候本来也每 tick 叫一次。
            Listener.of(context).retryAnnouncing();
            return;
        }
        // 建不起来也要有个说法，而且要短：这句话要去通知栏，不是去日志。回执的原文在日志里，
        // 完整的那一句在应用的功能页上。
        say(context, "接收开着，但 AWDL 没建起来：" + result.subtitle());
    }

    /** 交给守护进程的那个名字：与功能页读同一行，空串表示让它用手机自己的主机名。 */
    private static String name(Context context) {
        SettingsScreen screen = Tuning.read(context);
        SettingsScreen.Setting named = screen == null ? null : screen.setting("device.name");
        return named == null ? "" : named.value().trim();
    }

    /**
     * 换一句状态，并且**只在真的换了**的时候把两个表面刷一遍。
     *
     * <p>去重不是为了省消息，是为了省一次通知更新：看护线程每八秒过一次，同样的字重复发会让
     * 通知栏那条一直闪（{@code notify} 对同一条 id 是一次重绘）。
     */
    private static void say(Context context, String text) {
        if (text.equals(note)) {
            return;
        }
        note = text;
        push(context);
    }

    /**
     * 把「链路现在是什么样」推到人看得见的地方去。
     *
     * <p>两个表面，因为这条路径上应用**没有打开**——通知栏那条和磁贴那一格就是全部。这也是
     * 这个类唯一对外做的事：它不决定收不收，不碰 8770，不发广播。
     */
    private static void push(Context context) {
        ReceiveService.refresh(context);
        AirdropTile.refresh(context);
    }
}
