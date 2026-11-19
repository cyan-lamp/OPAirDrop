package com.oshare.airdrop;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 发送侧的那张卡：现在由我们自己发，交给系统的实时通知。
 *
 * <p># 它以前是什么
 *
 * <p>这个类原先是一条桥：把「准备中／等待接受／正在发送」这些阶段写成 JSON，经回环套接字交给
 * 一加互传的进程，由互传的发送卡片画出来。那条链上有三处会坏（进程在不在、binder 通不通、钩子
 * 对不对得上这一版 ROM），而且每一处坏了都没有说法——卡片不出现，人就以为发送没开始。
 *
 * <p># 现在
 *
 * <p>同样几个阶段，直接落到 {@link LiveCard} 上：一条进度型的实时通知，ColorOS 把它画在流体云
 * 那个位置。**发出去就是发出去了**，没有"另一端在不在"这个问题。调用方一行没改：仍然是
 * {@code begin / waiting / progress / finished}，因为这张卡的语义本来就是那四个阶段。
 *
 * <p># 状态码照互传的词表
 *
 * <p>{@link #SUCCESS} 等几个码是互传自己的编号（{@code SendUiState} 那一套）。留着它们不是
 * 恋旧：{@link SendSession} 与 {@link SendActivity} 一直在用这些数说话，换一套编号只会让两边的
 * 对照表多一张，而话还是那几句。
 */
final class SendHost {

    private static final String TAG = "OPAirDropSend";

    static final int SUCCESS = 1006;
    static final int REFUSED = 1008;
    static final int CANCELLED = 1009;
    static final int TIMEOUT = 1010;
    static final int TRANSFERRING = 1005;
    static final int WAITING = 1001;
    static final int FAILED = 1000;

    private static final Object LOCK = new Object();

    /** 卡片上【取消发送】按下去要打开的那个动作。由 {@link SendCancel} 接住。 */
    static final String CANCEL = "com.oshare.airdrop.SEND_CANCEL";

    /** 那条广播里带的「停哪一次」。字符串而不只是布尔：发送页拿它比对当前这一趟。 */
    static final String EXTRA_SESSION = "session";

    /** 卡片上【重试】按下去要打开的那个请求码。重试永远指回发送页，所以只有一个码。 */
    private static final int RETRY_REQUEST = 0x0B05;

    /**
     * 【取消发送】的请求码。
     *
     * <p>只有一个码而不是 {@code 0x0D00 + 编号} 那样的分段：同一时刻只跑得了一次发送
     * （{@code SendActivity.sending} 是全局唯一的那一趟），所以不存在两张卡抢同一个码。
     * 接收侧要分段是因为可以有两台 iPhone 同时发过来。
     */
    private static final int STOP_REQUEST = 0x0D00;


    /** 谁来画那张卡。发送页进来时给一次，之后所有阶段都用它。 */
    private static Context app;
    private static Canceller canceller;

    /**
     * 把上一次发送原样再发一遍的那段动作。
     *
     * <p>存在这里而不是只留在页面里，是因为卡上的【重试】会去叫一次发送页——而那时页面可能
     * 已经被系统回收、又刚被这条 intent 建起来。这件事的答案（发给谁、发哪几个文件）在发送
     * 那一次就定了，和页面活不活着无关，所以它跟着这个类走。
     *
     * <p>{@code null} 是「没有可重来的」：进程被系统杀掉之后就是这样，那时卡上不该挂着【重试】。
     */
    private static Runnable replay;

    /**
     * 最后一次说出去的百分比。失败卡把轨道停在它上面，见 {@link LiveCard#failedWithActions}。
     *
     * <p>写在这里而不是让调用方算：失败那句话里没有进度，而 {@link #finished} 拿到的只有状态码。
     */
    private static int lastPercent = -1;

    /**
     * 最近造出来的那颗【取消发送】，连同它属于哪一趟。
     *
     * <p>造一次而不是每帧都造：进度每两百毫秒重画一次，而 {@code PendingIntent.getBroadcast}
     * 会走到系统进程；一趟发送按一百次算，就是白跑一百次 binder。带着 {@code session} 一起
     * 存，是为了换了一趟就重造——只认控件不认趟次，会让第二趟的取消去停第一趟（那时早就没
     * 人在跑了），人按下去就是一颗不响的按钮。
     */
    private static PendingIntent stopping;
    private static String stoppingSession;

    private SendHost() {}

    /** 卡片上的「取消」要落回正在跑的那一次发送，那是唯一知道怎么停下它的人。 */
    interface Canceller {
        /** @return 真的停下了才算真；{@code false} 会让卡片把按钮的状态收回去 */
        boolean cancel(String session);
    }

    static void attach(Context context) {
        synchronized (LOCK) {
            app = context.getApplicationContext();
        }
    }

    /** 通道断开的旧签名，仍然被调用方用着：这里没有通道，只有一张卡。 */
    static void detach(Object ignored) {
        // 有意留空。
    }

    static boolean linked() {
        synchronized (LOCK) {
            return app != null;
        }
    }

    static void register(Canceller listener) {
        synchronized (LOCK) {
            canceller = listener;
        }
    }

    static void unregister(Canceller listener) {
        synchronized (LOCK) {
            if (canceller == listener) {
                canceller = null;
            }
        }
    }

    /** 卡片上按了取消：交回给正在跑的那一次发送，让它自己停。 */
    static boolean cancel(String session) {
        Canceller listener;
        synchronized (LOCK) {
            listener = canceller;
        }
        if (listener == null) {
            return false;
        }
        try {
            return listener.cancel(session);
        } catch (Throwable failure) {
            Log.w(TAG, "cancel from the card failed", failure);
            return false;
        }
    }

    /**
     * 记下「这一次怎么重来」。{@link SendActivity#send} 在开线程之前交过来。
     *
     * <p>交过来的是动作本身，不是一个可以重新拼出来的描述：要重来一次需要的东西（对端那条
     * 记录、缓存里那几个文件）全在页面手上，把它们拆成字段再拼回去，等于把「怎么发」写两遍。
     */
    static void remember(Runnable action) {
        synchronized (LOCK) {
            replay = action;
        }
    }

    /**
     * 卡上按了【重试】：再发一遍刚才那一次。
     *
     * <p>在发送页的主线程上被调用（{@link SendActivity} 收到那条 intent 之后），因为这个动作
     * 要做的事和「人又点了一次那台设备」完全一样——包括它会去碰视图。
     *
     * @return {@code false} 表示没有可重来的（进程被杀过、或者页面还没收到文件），调用方该换一
     *         条路走，而不是什么也不做
     */
    static boolean retry() {
        Runnable action;
        synchronized (LOCK) {
            action = replay;
        }
        if (action == null) {
            return false;
        }
        try {
            action.run();
            return true;
        } catch (Throwable failure) {
            Log.w(TAG, "retry from the card failed", failure);
            return false;
        }
    }

    /** 起一次发送：还没连上，没有百分比可说，画不确定态。 */
    static void begin(String session, String peer, String contents) {
        lastPercent = -1;
        show(session, "发送到 " + peer, "准备中 · " + contents, -1);
    }

    /** 连上了，等对方点接受。这一档同样没有百分比。 */
    static void waiting(String session, String peer, String line) {
        show(session, "等待对方接受", line, -1);
    }

    /**
     * 正在走字节。
     *
     * @param percent 0–100；负数表示对端没声明总数——按不确定态画，而不是停在 0%
     * @param line    第二行，例如 {@code 3 个文件 · 2.6 MB}。百分比不写在这句里：胶囊上已经有了，
     *                同一句话在两处出现就是早晚对不上的那种重复
     */
    static void progress(String session, String peer, int percent, String line) {
        show(session, "发送到 " + peer, percent < 0 ? line : percent + "% · " + line, percent);
    }

    /**
     * 结束了：成功留一张「发送完成」，失败留一张说法，**两张都不自己消失，人自己收**。
     *
     * <p># 成功为什么只剩一颗按钮
     *
     * <p>文件去了对面，本机没有可打开的落点，所以没有【打开文件】。留下的那颗【完成】是这张卡
     * 唯一的出口——它按下去才撤，不按就一直挂着。一张自己消失的完成提示，等于把「发没发出去」
     * 这个问题又还给了人。
     *
     * <p># 失败为什么有两颗
     *
     * <p>失败是唯一一种「人不做点什么，这件事就永远没完成」的状态，所以除了收尾，还要给一条
     * 重新来过的路：左【重试】右【完成】。
     *
     * <p># 取消为什么不在这张卡上，直接撤
     *
     * <p>2026-11-19 15:07 那一版给取消留了一张「已取消 · 保持两台设备都亮屏、隔空投送不切走，
     * 再点一次设备重试」的终态卡，实测一句话里两处错：标题和副行都在说「已取消」（副行是按下
     * 面那个兜底 {@code else} 拼出来的，兜底没给这一支留分支），而那句「再点一次设备重试」是
     * 给**故障**写的建议——人刚刚明确说了不要，叫他重试等于问他第二次。更糟的是它按失败卡的
     * 规格弹了一次 heads-up，在人刚按完的那一秒把面板顶到他脸上。
     *
     * <p>取消不需要一张要人再按一次才收得掉的墓碑卡：那件事实现在就在他手底下发生完了。撤离
     * 与接收侧同一个口径——{@link ReceiveHost.Attempt} 收一半被停下时也是 {@code drop}，
     * 理由写在那里：「一张停在中间态、也没人能再点的常驻卡，比没有更糟」。同一件事在收和发两个
     * 方向不该长得不一样。
     *
     * <p>进度停在断开那一刻：{@code lastPercent}。拉满的轨道在胶囊上就是「收完了」的意思，而把
     * 失败画成成功是这里最不能犯的错。
     */
    static void finished(String session, String peer, int status, String line) {
        Context context = context();
        if (context == null) {
            return;
        }
        if (status == SUCCESS) {
            LiveCard.finishWithActions(context, "发送完成", line, null);
            return;
        }
        if (status == CANCELLED) {
            LiveCard.drop(context);
            return;
        }
        LiveCard.failedWithActions(context, label(status), line, lastPercent, retry(context));
    }

    /**
     * 三个进行中的相位都从这里落到卡上，**都带【取消发送】**。
     *
     * <p># 为什么连「准备中」「等待对方接受」也要给这颗按钮
     *
     * <p>人在这一档最需要的恰恰是它：发错了设备、发错了那一份文件、突然想起该先问一句——
     * 这些都是「还没开始就已经后悔」，而那时对面手机上甚至已经弹出了那张邀请卡。等到字节
     * 开始走才允许取消，等于逼人先让错误的东西真的发出去。
     *
     * <p># 它和接收侧那颗分工不同
     *
     * <p>接收侧的【取消接收】只在进度中出现，因为邀请阶段已经有【拒绝】——同一件事摆两颗
     * 按钮，人会以为它们不一样。发送侧没有那个前提：邀请在**对面**的手机上，我们这张卡上
     * 从头到尾只有这一颗。
     *
     * <p>{@code session} 为空时不挂按钮。那意味着调用方自己都不认为这是一趟可以停的发送。
     */
    private static void show(String session, String title, String line, int percent) {
        Context context = context();
        if (context == null || !LiveCard.allowed(context)) {
            return;
        }
        lastPercent = percent;
        LiveCard.updateWithStop(context, title, line, percent, "取消发送", stop(context, session));
    }

    /**
     * 【取消发送】那颗按钮，按下去是一条广播。
     *
     * <p># 为什么是广播而不是像【重试】那样 {@code getActivity}
     *
     * <p>【重试】必须起界面——它要做的事就是「人又点了一次那台设备」。取消正相反：人的意思
     * 是「这件事我不要了」，这时把面板推到他脸上是最不该做的反应。走广播，什么也不弹，卡片
     * 自己换一行「已取消」。
     *
     * <p># 为什么不用 {@code exported=false} 之外的任何保护
     *
     * <p>{@link SendCancel} 是 {@code exported=false} 的，能给它发广播的只有本应用自己的
     * {@code PendingIntent}。再加一层签名校验只会多一份要维护的凭据，挡不住任何真实的攻击者
     * ——能往这里发广播的人，本来就已经能在进程里干活了。
     */
    private static PendingIntent stop(Context context, String session) {
        if (session == null) {
            return null;
        }
        synchronized (LOCK) {
            if (stopping != null && session.equals(stoppingSession)) {
                return stopping;
            }
            Intent intent = new Intent(context, SendCancel.class)
                    .setAction(CANCEL)
                    .putExtra(EXTRA_SESSION, session);
            stopping = PendingIntent.getBroadcast(context, STOP_REQUEST, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            stoppingSession = session;
            return stopping;
        }
    }

    private static Context context() {
        synchronized (LOCK) {
            return app;
        }
    }

    /** 有没有可重来的那一次。没有就不该在卡上挂【重试】。 */
    private static boolean retryable() {
        synchronized (LOCK) {
            return replay != null;
        }
    }

    /**
     * 卡上【重试】指向哪儿：发送页，带着一个说「这次是重试」的动作。
     *
     * <p>走 {@code getActivity} 而不是广播，和接收侧那记【打开文件】同一个理由：通知上按下去
     * 的那一下若先进广播、再由广播起页面，Android 12 起这条蹦床是被拦掉的（{@code BAL_BLOCK}），
     * 人看到的是一颗点了没反应的按钮。
     *
     * <p>{@code NEW_TASK} 不是多余：发送页的窗口不参与任务栈，没有它，这一下可能落在别的
     * 应用的任务里；{@code SINGLE_TOP} 让页面还活着时走 {@code onNewIntent}，而不是叠出第二块
     * 面板。
     */
    private static PendingIntent retryIntent(Context context) {
        Intent intent = new Intent(context, SendActivity.class)
                .setAction(SendActivity.ACTION_RETRY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, RETRY_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 失败卡上那颗【重试】；没有可重来的就返回 {@code null}，这张卡就只剩【完成】。 */
    private static PendingIntent retry(Context context) {
        return retryable() ? retryIntent(context) : null;
    }

    /**
     * 通知标题上的那句话。
     *
     * <p>和 {@link #label(int)} 分开：进行中的三个相位没有状态码（它们是 1005 这一档里的
     * 三段），同一个码要说的却是三句不同的话。
     */
    static String headline(String phase, int status) {
        switch (phase) {
            case "preparing": return "正在准备发送";
            case "waiting": return "等待对方接受";
            case "transfer": return "正在发送";
            default: return label(status);
        }
    }

    /** 状态码到一句话。 */
    static String label(int status) {
        switch (status) {
            case SUCCESS: return "发送完成";
            // 与 {@link SendActivity} 那行同词：1008 是 `/Ask` 被拒，人在对面手机上点了拒绝，
            // 「没有接收」读起来像「还没收到」，把已经发生的事说成了还没发生。
            case REFUSED: return "对方拒绝";
            case CANCELLED: return "已取消";
            case TIMEOUT: return "发送超时";
            case 1013: return "对方空间不足";
            default: return "发送失败";
        }
    }
}
