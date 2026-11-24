package com.oshare.airdrop;

import android.content.Context;
import android.util.Log;

/**
 * 接收端的广播（评审拆分路线：从 {@code Listener} 里切出来的发现层）——mDNS 记录
 * （{@link Responder}）与蓝牙二十字节这两层「这台机器可以被发现」的实现，同一生命周期，
 * 分开启停会让中间那段变成「mDNS 说在、蓝牙说不在」，而对端只信后者。
 *
 * <p>状态行（{@code aside}）与生命周期（起/停）都收在这里；Listener 只保留「谁在问」
 * 与「什么时候该问」。
 */
final class Announcer {

    private static final String TAG = "OPAirDrop";

    /** 广播状态变了（起来/失败/停了），页面该刷新一下。 */
    interface Tell {
        void say();
    }

    private final Context context;
    private final Tell tell;
    private final Object lock = new Object();
    /**
     * The announcer: the half of discovery that makes this device appear in a share sheet.
     *
     * <p>Owned here, and started only after {@code Listener.PORT} is actually bound, because the two are
     * one promise: a receiver that accepts nothing but announces anyway is worse than an
     * invisible one — it is a device a person taps and then waits on. Every caller that starts
     * the receiver therefore gets the announcement for free, and there is exactly one place
     * where the pair can drift apart.
     *
     * <p>Guarded by {@link #lock}.
     */
    private Responder announcer;

    /**
     * 这一次生命周期里已经有人去造 {@link Responder} 了。
     *
     * <p>{@link #announcer} 非 null 不足以回答这个问题：从「决定要造」到「把它放进去」之间隔着
     * {@code ServiceId.loadOrCreate} 的读文件与 {@link Responder} 的构造，那几毫秒里
     * {@link #beginAnnouncing} 可以被第二个人叫到。2026-11-18 真机上量到过，两条线程各造了一个：
     *
     * <pre>
     *   19:38:52.680  27364  ble: starting
     *   19:38:52.681  27362  ble: starting
     *   19:38:52.682  27367  announcing 7f5ed39dbd10 at air.local:8770 on awdltun0
     *   19:38:52.683  27370  announcing 7f5ed39dbd10 at air.local:8770 on awdltun0
     * </pre>
     *
     * <p>那两条线程是 {@code start()} 里的 {@link #beginAnnouncing} 和 {@link StackKeeper} 那侧
     * 链路一就绪就打来的 {@link #retryAnnouncing}——真机上链路通常比磁贴早，所以这一撞是常规
     * 路径而不是边角。后果不是多几行日志：{@link #announcer} 只装得下一个，先造的那个成了孤儿，
     * 它的 socket 与两条线程没人收，{@link #endAnnouncing} 也永远不会停它。于是「关」之后仍有人
     * 每三秒公告一次——对端看见一台点了却没人接的设备。
     *
     * <p>用一位独立的标志而不是让 {@link #announcer} 早点非 null：那个人造到一半就抛异常时，
     * 留一个空的 {@link #announcer} 会让 {@link #retryAnnouncing} 以为「有对象了，重启它」，
     * 而重启一个构造失败的对象救不回任何东西。
     *
     * <p>Guarded by {@link #lock}；{@link #endAnnouncing} 里落回 false，下一次启动是新的一次。
     */
    private boolean begun;

    /**
     * Why there is no announcer, when there is none for a reason worth saying.
     *
     * <p>Separate from {@link Responder#aside()} because it is written before the announcer
     * exists — an unreadable service-id file means there is no object to ask.
     */
    private volatile String asideProblem = "";

    /**
     * 协议栈说它在广播了没有。
     *
     * <p>这一位存在的理由只有一个：{@code overBluetooth} 是一次性的敲门，敲不开就
     * 结束了，不像 socket 那样会重连。而「先拨开关、再在权限框上点允许」是最常见的顺序——
     * 那次敲门必然发生在权限到手之前。没有这一位，就没有任何依据判断「现在再敲一次」是否
     * 有意义，只能盲目重试或者永远不试。
     *
     * <p>判据只认 {@code advertising}。原来是「开头不是 {@code failed: } 就算醒了」，那会把
     * {@code starting} 也算成已醒——而 {@code starting} 恰恰是后台线程那次调用**唯一**的返回：
     * {@code bleAdvertise()} 把注册投给电台线程之后立刻读状态，读到的一定是刚置上的
     * {@code starting}。于是 {@link StackKeeper} 那每八秒一次的补敲一旦发生，这一位就永远停在
     * true；后来栈真的回了 {@code failed:}，{@link #reannounce} 也再不会被放行——而它要补的
     * 正是「点完允许、重新敲一次」那个窟窿。
     */
    private volatile boolean announced;

    Announcer(Context context, Tell tell) {
        this.context = context;
        this.tell = tell;
    }

void begin(Identity identity) {
        // 一道闸，先占位再干活。理由见 {@link #begun}：先造后发的话，两条线程会各造一个，
        // 而只有一个能被停掉。
        synchronized (lock) {
            if (begun) {
                return;
            }
            begun = true;
        }
        try {
            ServiceId service = ServiceId.loadOrCreate(context.getFilesDir());
            Responder announcing = new Responder(
                    context,
                    service.current,
                    service.stale,
                    Responder.hostNameFor(identity.computerName()),
                    identity.certHash(),
                    Listener.PORT,
                    Responder.DEFAULT_INTERFACE,
                    tell::say);
            asideProblem = "";
            synchronized (lock) {
                announcer = announcing;
            }
            announcing.start();
        } catch (RuntimeException refused) {
            // 造不出来就把位子还回去，{@link #retryAnnouncing} 才还有下一次机会——它那一段
            // 「上一次连 Responder 都没造出来」说的正是这里。占着位子不放会让链路随后就绪
            // 时也只是安静地看着，而人对面看到的是一台不存在的设备。
            synchronized (lock) {
                begun = false;
            }
            asideProblem = "广播未起：" + refused;
            Log.w(TAG, "announcer could not be created", refused);
            return;
        }
        // 第二层公告：蓝牙。iPhone 的隔空投送先听这二十字节，听见了才把 AWDL 上的服务叫起来，
        // 之后才轮得到上面那条 mDNS 记录被读到。少这一层时，「查询发得完全正确、每两秒一次、
        // 却谁都不答」正是它会表现成的样子——对端在每一层都在，只是没人听 8770。
        //
        // 与 mDNS 同一个生命周期：两者都是「这台机器现在可以被发现」这句话的实现，分开启停会
        // 让中间那段变成「mDNS 说在、蓝牙说不在」，而对端只信后者。
        overBluetooth();
    }

private void overBluetooth() {
        try {
            String line = new String(Native.bleAdvertise(context), java.nio.charset.StandardCharsets.UTF_8);
            Log.i(TAG, "ble: " + line);
            // Rust 侧把四态压成这一行（idle / starting / advertising / failed: 原因），而这里
            // 要回答的是**「现在还有一份注册在栈上吗」**，不是「这一次敲成功了吗」。
            //
            // `starting` 必须算没醒：它只说明话递出去了、回话还没到（后台线程那次调用的唯一
            // 返回），而 {@link #retryAnnouncing} 与 {@link #reannounce} 靠这一位决定「再敲
            // 一次还有没有意义」。再敲一次本身不要紧——{@code bleAdvertise} 见到 {@code
            // is_awake} 就直接回流状态行，不会重复注册。
            //
            // `already started` 要算醒：那是栈在说「已经有一份在播」（{@code ADVERTISE_FAILED_
            // ALREADY_STARTED}，见 {@code ble::explained}）。它比我们这本账更权威，所以照旧认它。
            announced = line.startsWith("advertising") || line.contains("already started");
        } catch (Throwable refused) {
            // 一个没有蓝牙权限的接收端仍然能在 8770 上收东西，所以这里不是错误路径。
            announced = false;
            Log.w(TAG, "ble advertisement did not start: " + refused);
        }
    }

void reannounce() {
        synchronized (lock) {
            if (announcer == null || announced) {
                return;
            }
        }
        overBluetooth();
    }

    /**
     * 链路刚建好之后，把广播再起一次。幂等，可以从任何线程调用。
     *
     * <p>{@link #reannounce} 补的是「蓝牙那一下敲早了」，这一位补的是另一条、也是 2026-11-18
     * 真机上量到的那条：接收端起来的时候 AWDL 链路还不存在，{@link Responder#bind()} 抛
     * {@code 没有网卡 awdltun0}，{@code aside()} 从此一直写着「广播未起」——而链路在二十五秒
     * 后才建好。那之后有两条路，旧代码两条都没有：等下一次 {@code start()}（要人去关再开一次
     * 开关），或者就这么听着 8770 而谁也找不到。
     *
     * <p>一道闸，与 {@code reannounce} 同一个理由：接收端没在跑就不叫——广播「我在这儿、来发
     * 给我」而 8770 没在听，是比看不见更坏的一种存在。已经在跑的那一半不再直接返回：mDNS 与
     * 蓝牙是「这台机器可以被发现」的两层，只坏一层时这一句才有事可做（见下面的注释）。
     *
     * <p>{@link Responder#start()} 允许这样被叫第二次：它在失败路径上会把 opener 清掉，那时候
     * 再叫一次就是真的重新 bind 一次（看 {@code Responder#open} 里的注释，那里写的就是这个
     * 场景）。
     */
    void retry(Identity who) {
        Responder announcing;
        synchronized (lock) {
            announcing = announcer;
        }
        if (announcing == null) {
            // 上一次连 Responder 都没造出来（读 service-id 就失败了）。身份在手就再试一次，
            // 那会重走一遍读文件与起蓝牙广播。
            if (who != null) {
                begin(who);
            }
            return;
        }
        if (announcing.running()) {
            // mDNS 在，蓝牙不在。这是「磁贴关掉再打开」之后真机上落到的那条缝：Responder 被
            // stop 之后再 start 是一次全新的 bind，成功了；而 BLE 那一次 advertise 落在 stop
            // 的尾巴上（{@code ble stopped: idle} 之后紧接着的一次 start），r0 侧没有重新注册，
            // 于是 iPhone 的分享面板永远等不到那二十字节——mDNS 发得再对也没人听。
            //
            // {@code announceOverBluetooth} 幂等：{@code bleAdvertise} 见到 {@code is_awake}
            // 就回流状态行，不会重复注册。所以这里可以每 tick 叫一次；真卡在 {@code starting}
            // 时它还会每 tick 留一行，那正是我们想看见的那种「没动静」。
            if (!announced) {
                overBluetooth();
            }
            return;
        }
        announcing.start();
    }

void end() {
        Responder announcing;
        synchronized (lock) {
            announcing = announcer;
            announcer = null;
            // 下一次 start 是新的一次「谁去公告」，{@link #begun} 得跟着这条生命周期一起回来。
            begun = false;
        }
        if (announcing != null) {
            announcing.stop();
        }
        // 两盏灯一起灭：只停 mDNS 会让蓝牙继续广播「我在这儿」，而对端听见之后连上来的是一
        // 个已经不在听的端口。
        //
        // 这一位跟着一起复位，因为它是「这一次生命周期的广播发出去了没有」；下一次 start 是
        // 新的一次，得重新问一遍。
        announced = false;
        try {
            Log.i(TAG, "ble stopped: " + new String(Native.bleStop(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable refused) {
            Log.w(TAG, "ble advertisement did not stop: " + refused);
        }
    }

    /**
     * 「为什么对面看不见我」那一行的现在版本。
     *
     * <p>与 {@code Listener.state()} 分开写：接收端可以在听而广播没起来，那个组合
     * （「正在监听」+ 空的分享面板）最会把人引到错的地方去。一切正常时返回空串，
     * 让状态行自己说话。
     */
    String aside() {
        Responder announcing;
        synchronized (lock) {
            announcing = announcer;
        }
        if (announcing != null) {
            return announcing.aside();
        }
        return asideProblem;
    }
}
