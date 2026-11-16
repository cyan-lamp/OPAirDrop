package com.oshare.airdrop;

import android.content.Context;

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

    /** 谁来画那张卡。发送页进来时给一次，之后所有阶段都用它。 */
    private static Context app;
    private static Canceller canceller;

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

    /** 起一次发送：还没连上，没有百分比可说，画不确定态。 */
    static void begin(String session, String peer, String contents) {
        show("发送到 " + peer, "准备中 · " + contents, -1);
    }

    /** 连上了，等对方点接受。这一档同样没有百分比。 */
    static void waiting(String session, String peer, String line) {
        show("等待对方接受", line, -1);
    }

    /**
     * 正在走字节。
     *
     * @param percent 0–100；负数表示对端没声明总数——按不确定态画，而不是停在 0%
     * @param line    第二行，例如 {@code 3 个文件 · 2.6 MB}。百分比不写在这句里：胶囊上已经有了，
     *                同一句话在两处出现就是早晚对不上的那种重复
     */
    static void progress(String session, String peer, int percent, String line) {
        show("发送到 " + peer, percent < 0 ? line : percent + "% · " + line, percent);
    }

    /** 结束了：成功留一张「发送完成」，失败留一张说法，都不自动消失，人自己收。 */
    static void finished(String session, String peer, int status, String line) {
        Context context = context();
        if (context == null) {
            return;
        }
        if (status == SUCCESS) {
            // 发送完成不等于这件事结束：胶囊该留在那儿让人看见「发完了」，直到人按【完成】。
            // finishWithActions 挂的那颗按钮正是这个作用——它通过 CardDismiss 广播撤掉通知，
            // 不自己消失。文件去了对面，本机没有可打开的落点，所以 open 传 null，只剩【完成】。
            LiveCard.finishWithActions(context, "发送完成", line, null);
            return;
        }
        // 失败不画进度条：一条满格的轨道配一句「发送失败」是自相矛盾的。
        LiveCard.drop(context);
        if (LiveCard.allowed(context)) {
            LiveCard.update(context, label(status), line, -1);
            LiveCard.dropLater(8000);
        }
    }

    private static void show(String title, String line, int percent) {
        Context context = context();
        if (context == null || !LiveCard.allowed(context)) {
            return;
        }
        LiveCard.update(context, title, line, percent);
    }

    private static Context context() {
        synchronized (LOCK) {
            return app;
        }
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
            case REFUSED: return "对方没有接收";
            case CANCELLED: return "已取消";
            case TIMEOUT: return "发送超时";
            case 1013: return "对方空间不足";
            default: return "发送失败";
        }
    }
}
