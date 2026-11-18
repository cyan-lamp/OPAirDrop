package com.oshare.airdrop;

import android.content.Context;

import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * AWDL 链路的看护者：只**观察**内核层在不在，从不建它。
 *
 * <p># 为什么它不再装驱动
 *
 * <p>这个类的第一版会在链路缺席时自己调 {@link DriverSetup#boot}。那是错的，而且错在分界上，
 * 不是错在参数上：
 *
 * <ol>
 *   <li><b>代价不对等。</b>{@code boot} 的冷路径要关 Wi-Fi 至少二十五秒（真机量到过 289 秒），
 *       还要写全局 freezer。而磁贴是「一秒级」的开关——人点它是想立刻开或关，不是等五分钟，
 *       而且期间网是断的。
 *   <li><b>幂等性不对等。</b>磁贴会被误触、连点、在下拉面板里滑过。{@code boot} 不是幂等操作：
 *       中途砍掉一次驱动交换得到的是 {@code rc=10}，那是这台机器上唯一能把 Wi-Fi 弄丢的操作。
 *   <li><b>责任要分层。</b>磁贴是应用层（{@code receive.manual}、前台服务、8770、BLE、
 *       复用已有的 {@code awdltun0} 发 mDNS）；内核层（insmod / rmmod / {@code awdld}）
 *       只属于功能页那个按钮，因为那是人明确按下的、带进度和后果说明的一次操作。
 * </ol>
 *
 * <p># 那它现在负责什么
 *
 * <p>两件，都是应用层内部的事：
 *
 * <ul>
 *   <li><b>判活。</b>磁贴画一个绿灯之前必须知道对端是不是真能看见这台机器。判据只有一个：
 *       {@code awdltun0} 在不在。回执不是判据——{@code files/stack-state} 是**那一刻的账**，
 *       这台机器上量到过回执写着 {@code tun=up} 而隧道早已不在的组合。
 *   <li><b>补广播。</b>链路在、而 announcer 没在的时候叫它一次。链路晚于接收端建好，是这台
 *       机器的常态（磁贴先开、人在功能页后建链），那一段里 8770 在听、蓝牙在喊，而 mDNS 一句
 *       都发不出去。
 * </ul>
 *
 * <p>不决定「该不该收」（那是 {@link ReceiveService#listening}），不碰 8770，不改设置，
 * 不碰内核模块。
 */
final class StackKeeper {

    private static final String TAG = "OPAirDrop";

    /**
     * 那条隧道。判活只认它一个，理由见类注释；名字与 {@code awdl-stack.sh} 同一个词。
     */
    private static final String TUNNEL = Responder.DEFAULT_INTERFACE;

    /** 多久看一眼。八秒是一次蓝牙广播的间隔量级，比它更勤只是在问同一个问题。 */
    private static final long TICK_MS = 8_000L;

    /** 看护线程。同一时刻只有一个；{@link #wanted} 是它该不该继续。 */
    private static volatile Thread worker;
    private static volatile boolean wanted;

    /**
     * 现在这条链路在不在。
     *
     * <p>给磁贴用，所以只读内存：那一次枚举由看护线程去做，磁贴不该在画一格图标的时候去
     * 问网卡。
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
     * <p>线程名与功能页那条（{@code opairdrop-driver}）不同：两条可能同时在跑，日志里要分得清
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
     * <p>只置一个标志，不 interrupt：这条线程除了枚举网卡什么都不做，一眼就能退出，没有需要
     * 抢在它前面保护的东西。
     */
    static void unwatch() {
        wanted = false;
    }

    /** 看护本体：看链路，链路在就让广播再起一次，不在就如实说。 */
    private static void loop(Context context) {
        try {
            while (wanted) {
                boolean before = linked;
                linked = link();
                if (linked) {
                    if (!before || !note.isEmpty()) {
                        // 链路从无到有，或者上一次那句话已经不成立：两个表面都要改。字没变而
                        // 磁贴那一格必须变的事也在这里——只按字去重会让一块红灯亮到底。
                        note = "";
                        push(context);
                    }
                    // 链路在而广播没在，是另一条独立的失败（换网卡、守护进程重启换了 ifindex、
                    // 上次启动时链路还没建好就放弃了）。这一句幂等，起来之后什么都不做。
                    Listener.of(context).retryAnnouncing();
                } else if (before || note.isEmpty()) {
                    // 只写一次。这句话要去通知栏，不是去日志，而它每八秒重复一次会让那条
                    // 常驻通知一直重绘。
                    note = "AWDL 链路未建立，请在功能页建链";
                    Log.w(TAG, "stack| " + TUNNEL + " is absent; the tile cannot be green");
                    push(context);
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
     * 把「链路现在是什么样」推到人看得见的地方去。
     *
     * <p>两个表面，因为这条路径上应用可能没有打开——通知栏那条和磁贴那一格就是全部。
     */
    private static void push(Context context) {
        ReceiveService.refresh(context);
        AirdropTile.refresh(context);
    }
}
