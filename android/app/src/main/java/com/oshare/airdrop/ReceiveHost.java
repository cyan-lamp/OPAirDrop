package com.oshare.airdrop;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Messenger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次接收从「有人要发」到「收完了」的每一步，都从这里说出去。
 *
 * <p># 它为什么存在
 *
 * <p>接收这件事本身完全在协议层里：{@link Listener} 收字节，库决定答什么，文件落在磁盘
 * 上。这些都不需要界面。需要界面的只有三件事——有人要发东西、走到百分之几、收完了——而
 * 这三件事现在都落在流体云胶囊（{@link LiveCard}）与本应用自己的通知（{@link Confirmation}
 * 与 {@link ReceiveService}）上。这个类是唯一知道「两条路说的是同一件事」的地方：它产生
 * 事件，谁在听谁就画。
 *
 * <p># 为什么状态按 session 存在这里
 *
 * <p>「接受」最后必须落回停在 {@link Confirmation} 里等答案的那条线程，而按下去的地方在
 * 系统流体云里。中间隔着一条只能传字符串的路，两头得用同一个名字指同一件事——那就是
 * {@code session}。这个名字由这里产生，同时交给 {@link Confirmation#ask}，于是流体云上的
 * 按钮和通知上的按钮回答的是同一个闩。
 *
 * <p># 为什么状态码是这些数
 *
 * <p>1002 与 1006/1007 是从互传那一侧沿用的号（分别指「邀请」与「已经失败的」），中间两个
 * 是这个项目自己起的。真正的判据是 {@code phase}：
 * 对面按 {@code phase} 决定标题和按钮，{@code status} 只在读不到 {@code phase} 时兜底。
 *
 * <p># 它不做什么
 *
 * <p>它不判断该不该收。该不该收是人的事，它只负责把问题送到人眼前、把答案带回来。它也不
 * 管文件落在哪儿——目录由 {@link Listener} 决定，这里只是把结果照原样说出去。
 */
final class ReceiveHost {

    private static final String TAG = "OPAirDrop";

    /** 有人要发东西过来，等一个回答。 */
    private static final int PENDING = 1002;
    /** 答应之后正在收。 */
    private static final int RECEIVING = 1003;
    /** 人不收。 */
    private static final int REJECTED = 1004;
    /** 收完了并且已经落到磁盘上。 */
    private static final int COMPLETE = 1006;
    /** 没收到，或者收到了没存下来。 */
    private static final int FAILED = 1007;
    /** 人中途取消，或者被对面撤掉。 */
    private static final int CANCELLED = 1008;

    /**
     * 卡片上【取消接收】那颗按钮的广播。带 {@link Confirmation#EXTRA_ID}，值是 session。
     *
     * <p>它和 {@link Confirmation#ACCEPT}／{@link Confirmation#DECLINE} 走同一个接收器
     * （{@link DecisionReceiver}）：那两颗回答的是「收不收」，这一颗回答的是「收到一半还要不要
     * 继续」，但三颗按钮落在同一件事上——同一个 session 命名的同一次接收。分成两个接收器，
     * 就要在两处各写一遍「先去表里找这一条」，而找错一次就是取消掉别人的传输。
     */
    static final String CANCEL = "com.oshare.airdrop.RECEIVE_CANCEL";

    /** 保护下面两个字段。{@link Attempt} 自己的字段不归它管。 */
    private static final Object LOCK = new Object();

    /** 互传进程里那个 UI 的回信地址。空表示这个时刻没有谁能画。 */
    private static Messenger ui;

    /** 还没结束的接收，键是 session。用来回答指令和重放快照。 */
    private static final LinkedHashMap<String, Attempt> LIVE = new LinkedHashMap<>();

    /** session 与 id 的同一个来源：一次接收一个号，只增不减。 */
    private static final AtomicLong IDS = new AtomicLong();

    private ReceiveHost() {}

    /**
     * 通道另一端报到了。
     *
     * <p>收到 {@code hello} 时调一次。之后每一条事件都往这里送，直到对面消失。
     */
    static void attach(Messenger client) {
        synchronized (LOCK) {
            ui = client;
        }
    }

    /**
     * 对面没了。只清掉还是它的那一个，免得把一个刚连上来的新客户端一起抹掉。
     *
     * <p>参数是「谁没了」而不是没有参数：每个连接一个界面，一条连接断开和另一条连接建立
     * 可以发生在同一个瞬间，无条件清空会把新的那条一起抹掉。
     */
    static void detach(Messenger expected) {
        synchronized (LOCK) {
            if (ui == expected) {
                ui = null;
            }
        }
    }

    /** 现在有人能画吗。决定这一次是走流体云还是走通知。 */
    static boolean attached() {
        synchronized (LOCK) {
            return ui != null && ui.getBinder().isBinderAlive();
        }
    }

    /** 开一次接收。每个连接一个，不管这个连接最后会不会真的传东西。 */
    static Attempt open(Context context, Socket peer) {
        long id = IDS.incrementAndGet();
        Attempt attempt = new Attempt(context, id, "airdrop-" + id, peer);
        synchronized (LOCK) {
            LIVE.put(attempt.session, attempt);
        }
        return attempt;
    }

    /**
     * 人按了接受或拒绝。送进等答案的那个闩里。
     *
     * <p>{@code from} 是「人在哪个表面上按的」：{@code "card"}（系统流体云胶囊）或
     * {@code "notification"}（本应用通知，经 {@link DecisionReceiver}）。以前这里只记
     * {@code attached}，于是无论人从哪里按，日志都写 {@code via card}——排查「卡片没
     * 画出来、人是靠兜底通知进来的」那次，这条日志正好把唯一的线索写反了。
     */
    static boolean answer(String session, boolean accepted, String from) {
        Attempt attempt = find(session);
        if (attempt == null) {
            // 表里没有这一次接收，但通知可能还挂着：这张表在收尾时就退了，而通知要等
            // {@link Confirmation} 那边超时或回答才撤。人在这段缝里按下去，仍然该被听见——
            // 所以退到那个闩上再试一次，而不是当作「问题不存在」。
            Confirmation.answer(session, accepted);
            return false;
        }
        attempt.answer(accepted, from);
        return true;
    }

    /** 人按了取消。传到正在读字节的那条线程上，是关掉连接。 */
    static boolean cancel(String session) {
        Attempt attempt = find(session);
        if (attempt == null) {
            return false;
        }
        attempt.cancel();
        return true;
    }

    /**
     * 把还没结束的每一次接收重说一遍。
     *
     * <p>界面在一个时刻打开，而接收可能在它打开之前就开始了：进程被杀掉又拉起来、弹窗被
     * 关掉再点开、卡片重画。每一条都从同一次接收的最后一条事件接着往下说，所以人看到的
     * 不会是一个空白的「正在恢复」，而是这件事现在的样子。
     */
    static void snapshot() {
        ArrayList<Attempt> live;
        synchronized (LOCK) {
            live = new ArrayList<>(LIVE.values());
        }
        for (Attempt attempt : live) {
            attempt.replay();
        }
    }

    /**
     * 「打开文件」按下去去哪儿：那一份文件的 {@code content://media/…}。
     *
     * <p>收完之后字节已经交给媒体库，本应用手上没有可共享的路径（{@code file://} 从 Android 7
     * 起不能穿过进程边界），所以能指向这份文件、又能被别的应用读到的只有这一串。多份时打开第一
     * 份——通知上的一个按钮没有"选哪一份"这一说，而人要的通常是刚发过来的那张。
     *
     * <p>这里用 {@code getActivity} 而不是 {@code getBroadcast}：通知点下去的 PendingIntent 必须
     * 落在界面上。落在广播、再由广播去起相册，平台会按「通知蹦床」拦掉这次启动——相册不打开，
     * 也不会有任何提示。落点那一层做什么，见 {@link OpenDownload}。
     */
    private static android.app.PendingIntent openAction(Context context, Publish.Result published) {
        for (Publish.Entry entry : published.entries()) {
            if (entry.uri == null) {
                continue;
            }
            android.content.Intent open = new android.content.Intent(context, OpenDownload.class)
                    .putExtra(OpenDownload.EXTRA_URI, entry.uri.toString())
                    .putExtra(OpenDownload.EXTRA_MIME, context.getContentResolver().getType(entry.uri))
                    .putExtra(OpenDownload.EXTRA_DIR, entry.directory);
            return android.app.PendingIntent.getActivity(context, 0x0B03, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
        }
        return null;
    }

    /**
     * 【打开链接】按下去要去哪儿。
     *
     * <p>{@code OpenLink} 那一层是必需的，不是顺手抽出来的。两条理由，缺一条这条路都不成立：
     *
     * <ul>
     *   <li>通知上的 PendingIntent 只允许直接落到**界面**上。落到广播或服务、再由它去起浏览器，
     *       会被平台按 {@code BAL_BLOCK} 拦掉，一点提示都没有——这就是 {@link OpenDownload}
     *       当初那一课（见它的类注释里的 {@code isPendingIntent: false}）。</li>
     *   <li>按下去还得先把这张卡收掉。撤卡只能在我们自己的代码里做，而那一刻我们要么在一个
     *       界面上，要么什么也做不了。</li>
     * </ul>
     *
     * <p>request code 与【打开文件】的 0x0B03 不同：同一个 request code 的两个 PendingIntent
     * 会互相覆盖 extras，而这两颗按钮会先后出现在同一台机器上（先收文件，再收链接）。
     *
     * <p>三条链接也只有一颗按钮：这里只交第一条。一次点击开出三个标签页不是"都打开"，是把人
     * 丢进三个他自己没选过的页面。
     */
    static android.app.PendingIntent openLink(Context context, List<String> urls) {
        android.content.Intent open = new android.content.Intent(context, OpenLink.class)
                .putExtra(OpenLink.EXTRA_URL, urls.get(0))
                // 还有几条没开。只进日志，不进界面：卡上那句话说的是「几个链接」，而这里是
                // 「这一下开了几个」。两件事，不混。
                .putExtra(OpenLink.EXTRA_MORE, urls.size() - 1);
        return android.app.PendingIntent.getActivity(context, 0x0B05, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    private static Attempt find(String session) {
        if (session == null || session.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            return LIVE.get(session);
        }
    }

    /**
     * 这次邀请还在等人回答没有。
     *
     * <p>兜底通知用它决定还要不要挂：有人按了、或者这次已经过期，那次邀请就从表里退掉了，
     * 而定时器可能还排着队。没有这个判据，一条已经作废的邀请会在几秒后冒出一条点不动的通知。
     */
    static boolean pending(String session) {
        return find(session) != null;
    }

    /**
     * 往互传进程发事件的那条路，已经停用。
     *
     * <p>2026-11-16 15:45：接收侧不再依赖互传画卡（改由 {@link LiveCard}），但这条桥还留着，
     * 于是真机上出现了一件怪事——传输过程中通知栏和流体云里**同时**有一加互传自己的一条普通
     * 通知，它不属于我们，也点不动。原因是每收一次我们还在给互传发事件，它就照着画一张。
     *
     * <p>这里先掐断出口；整个桥接（含 LSPosed 作用域）的删除是下一步。
     */
    private static void emit(JSONObject event) {
        // 有意留空：本应用的所有对外可见状态都走 LiveCard。
    }

    private static void forget(String session) {
        synchronized (LOCK) {
            LIVE.remove(session);
        }
    }

    /** 扩展名到 MIME。猜不出来给一个通用的，不给人一个「打不开」的空字符串。 */
    private static String mime(String name) {
        String guessed = java.net.URLConnection.guessContentTypeFromName(name);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    /**
     * 一次接收。
     *
     * <p>生命周期只有一条线：{@link #decide} 问一次，{@link #progress} 报进度，
     * {@link #delivered} 或 {@link #end} 收尾。{@code decide} 可能根本没被调用——一次探测、
     * 一次握手失败、一次读完就走的连接都不到这一步——所以 {@code announced} 之前什么都不
     * 用收尾，也不该收尾：把上一条传输的结果抹掉，比什么都不显示更糟。
     */
    static final class Attempt {

        private final Context context;
        private final long id;
        private final String session;

        /** 问一次并等答案。{@link #decided} 落下来之后才读。 */
        private final CountDownLatch decided = new CountDownLatch(1);
        private volatile boolean accepted;

        /**
         * 有人真的回答过。
         *
         * <p>和 {@link #accepted} 不是一件事：两者都为假时，「人说了不行」和「一直没人理」
         * 长得一模一样，而对人来说这是两句话——前者该说「已拒绝」，后者该说「没人应答」。
         * 过去两种情况共用一句「已拒绝」，于是超时被读成「它自己拒的」，这不是猜错，是
         * 我们从来没把这件事记下来。
         */
        private volatile boolean answered;

        /**
         * 人在哪个表面上回答的：{@code "card"} 或 {@code "notification"}，没人回答时是
         * {@code null}。
         *
         * <p>记它的理由只有一条：卡片会不会出现是这台机器上最不可观测的一件事，
         * {@link #attached()} 只说明 binder 活着，所以「这次到底是怎么被回答的」必须是事实，
         * 不能由 {@code attached} 推出来。
         */
        private volatile String answeredFrom;

        /**
         * 这次邀请是在云端卡上问的（{@link LiveCard}），不是本应用的通知。
         *
         * <p>一条连接一个值，在决定那一刻定下来：系统不肯提升我们时它就是假的，之后的进度与
         * 结果都走原来那条通知，不该去碰一张不存在的卡。
         */
        private volatile boolean cloud;

        /** 这条连接。人说取消时关掉它，读字节的那条线程会从这里出来。 */
        private final Socket peer;

        /** 人按过取消。收尾时用来说「已取消」而不是「连接已断开」。 */
        private volatile boolean cancelled;

        /**
         * 进度卡上【取消接收】那颗按钮，第一次画进度时造一次。
         *
         * <p>造一次而不是每帧都造：进度卡每两百毫秒重画一次（见 {@link Listener.Meter}），
         * 而 {@code PendingIntent.getBroadcast} 是一次会走到系统进程的调用，稳定在这个对象上
         * 之后，重画就只剩一次 {@code notify}。
         */
        private PendingIntent cancelling;

        /** 对面叫什么。第一条事件之后才可能知道，后面几条要接着用。 */
        private String sender = "Apple 设备";

        /**
         * 对面报上来的那份清单：叫什么、多大。
         *
         * <p>邀请那一刻就知道了，而且必须一路带着。卡片每收到一条事件就整份重建、重新
         * 算一次摘要，它只看这一条——所以只挂在邀请那一帧上的话，「2 个文件」会在进度
         * 刚开始时就消失，卡片退回「文件信息未知」，而那正是这个字段要修的东西。
         *
         * <p>{@code null} 是「还不知道」，空表是「对面没报」。两者对卡片的说法一样，对
         * 排查不一样，所以在这里分开。
         */
        private JSONArray offered;

        /**
         * 这次邀请解析出来之后的那一份，留着给收尾用。
         *
         * <p>链接分享的收尾拿不到任何字节作凭据：那些地址只在邀请那一帧的摘要里出现过一次。
         * 不在这里留住，等 {@code serve} 返回时就已经没有地方还记得对面到底发了哪几条——
         * 而「哪一条」正是链接的全部内容。
         */
        private Confirmation.Offer offer;

        /** 有没有对外说过话。没说过就不用收尾。 */
        private boolean announced;

        /** 结果已经说过了。收尾不再覆盖它。 */
        private boolean settled;

        /**
         * 最后一次说出去的进度；负数表示对端什么都没声明。
         *
         * <p>留着它是为了失败卡：一条断在中途的连接，轨道该停在她真正走到的那个数上，而不是
         * 拉满（失败画成功）也不该转圈（那是「还在动」）。见 {@link LiveCard#failed}。
         */
        private int percent = -1;

        /** 最后说出去的那条，用来重放。跨线程读，所以是 volatile。 */
        private volatile JSONObject last;

        private Attempt(Context context, long id, String session, Socket peer) {
            this.context = context;
            this.id = id;
            this.session = session;
            this.peer = peer;
        }

        String session() {
            return session;
        }

        /**
         * 有没有人回答过这次邀请。见 {@link #answered}。
         *
         * <p>{@link Listener} 用它分辨「这条连接怎么断的」值不值得说：没人回答过的连接断了，
         * 是这次邀请自己过期（或者人根本没被告知），不是一次失败——尤其不能把邀请超时自己
         * 收掉连接时的 {@code ECONNABORTED} 说成「连接被系统切断，检查后台限网」。
         */
        boolean answered() {
            return answered;
        }

        /**
         * 有人要发东西过来：说出去，然后等人回答。
         *
         * <p>会阻塞调用线程，和 {@link Confirmation#ask} 一样，因为调用它的那条线程本来就
         * 只为这一条连接服务，等的时候没有别的事该做。
         *
         * <p>问的是谁，取决于这一刻有没有人画。互传进程在的时候，问题是那张卡片；不在的
         * 时候，问题是本应用自己的通知。两条路都回到同一个闩上，所以「同时出现两个框」这个
         * 情形在这里不可能发生——不是靠判断，是靠只有一条路被走到。
         */
        boolean decide(String summary) {
            // 一次邀请一个数，卡片和通知都从这里拿：让两条路共用一个常量是上一版的做法，
            // 那是「同一个数」靠人记得；现在它们读同一行设置，是结构上的同一个数。
            long decisionMs = Confirmation.decisionTimeout(context);
            Confirmation.Offer offer = Confirmation.parse(summary, decisionMs);
            // 同一份解析结果留一份在字段上：链接收尾要用它（见 {@link #deliveredLinks}），
            // 而摘要是这条连接里唯一一次出现过那些地址的地方。
            this.offer = offer;
            sender = offer.sender;
            announced = true;
            // 先把「哪几份」记下来再报事件：卡片看到邀请的那一帧就该看得到件数和名字，
            // 而不是先看一眼「文件信息未知」、等下一次更新才对。
            offered = filesOf(offer.files);
            try {
                emit(newEvent("pending", PENDING)
                        .put("name", sender)
                        .put("message", offer.body())
                        .put("progress", -1));
            } catch (JSONException impossible) {
                Log.w(TAG, "receive invitation unavailable", impossible);
            }
            // 只有一条表面：流体云。互传那张卡与它的叫醒逻辑已经撤掉（2026-11-16 16:40），
            // 于是这里不再有「卡片在不在」这个中间状态——发出去就是发出去了。
            Log.i(TAG, "invitation " + session + " from " + sender
                    + " files=" + (offer.files.isEmpty() ? "none" : offer.files.size())
                    // 链接分享没有文件，但它不是「什么都没报」。过去这一行在这种情况下写
                    // `files=unreported`，读日志的人会去找一个不存在的解析失败。
                    + " links=" + offer.links);
            long askedAt = System.currentTimeMillis();
            // 问在哪：系统画的实时通知（状态栏那颗胶囊，展开就是带两个按钮的大卡），或者——
            // 系统不肯提升我们时——本应用的抬头通知。前者比互传那张卡可靠一个量级：它发出去
            // 就是发出去了，没有「binder 活着但没画」这种状态（2026-11-16 13:14 撞过一次，
            // 屏幕上什么都没有，三十秒后按时过期）。
            // 问在哪：先是底部那块面板（互传同一种形态，人在传文件时认得的就是它），起不来才
            // 退回流体云那张卡。两条路的答案都落进同一个闩，所以这里的先后只影响"人看到什么"。
            // 问在哪：只有流体云这一条。应用级的浮窗已经撤掉（2026-11-16 15:10）——它要么
            // 抢走前台、要么把胶囊挡住，为了"更醒目"付出的代价比收益大。
            cloud = LiveCard.allowed(context);
            if (cloud) {
                // 接到东西了，这张卡本该自己摊开：消息藏在胶囊里等于没送到。但摊开这件事
                // 应用侧没有开关——原先靠 Xposed 模块进 SystemUI 调插件的 onExpand，代价是
                // 通知中心里看不见这张卡、点空白区域收不回去。现在改由系统决定：这条通知带
                // 高重要性渠道 + setOnlyAlertOnce(false)，会先抬头一次把人叫到；看不看得清
                // 那两个按钮，交给用户点一下胶囊。
                LiveCard.invite(context, offer.title(), offer.body(),
                        Confirmation.decision(context, session, true),
                        Confirmation.decision(context, session, false));
            }
            // 三条出口都不通的时候，别让对面等满六十秒。
            //
            // 出口只有三条：胶囊（cloud）、普通通知（Confirmation.ask）、以及正开着的界面。
            // 前两条都要 POST_NOTIFICATIONS 这条运行时权限，而新装的应用默认是关的——
            // 1.0.0 就是这么装上去的：授权为 false，系统把每一条通知静默丢弃，于是每次邀请
            // 都走满三十秒再判拒绝。人看到的是「iPhone 发了，这边没反应」，日志里写的是
            // `unanswered`；那句话在这里是假的，真相是"没有人被告知"。
            //
            // 所以这一条不是优化，是把假话改成真话：没有出口就当场说不，让对面几百毫秒内
            // 拿到 401，而不是等它自己超时。
            if (!cloud && !Notices.allowed(context) && !Listener.of(context).watched()) {
                Log.w(TAG, "invitation " + session
                        + " dropped: no surface at all (notifications off, nothing on screen)");
                end("rejected", REJECTED, "通知权限未开，这台设备现在收不到邀请");
                return false;
            }
            boolean panel = false;
            // 「问在哪」与「卡还在不在」原来是同一个变量，而下面的 finally 会在被拒时把 cloud
            // 置回 false（那时卡片确实该撤），日志却在更后面才写——于是每一行 refused 都落成
            // `asked=nobody`。读起来像「根本没有卡片、没人被告知」，真相却是「卡片在过，人拒了」。
            // 2026-11-19 19:52:23 那一行就是这么被读错的：它被当成「流体云那一路没被选中」的
            // 证据，而那时唯一的异常只是拒绝之后卡片被撤掉了。
            //
            // 三档而不是两档：上一条 return 保证到这里至少有一条出口在，所以「没有云卡」不一定
            // 是「通知」，也可能只是界面正开着（通知权限关着的时候问的就是屏幕）。
            final String asked = cloud ? "cloud"
                    : Notices.allowed(context) ? "notice" : "screen";
            boolean wanted = false;
            try {
                wanted = (panel || cloud) ? await(decisionMs) : Confirmation.ask(context, session, summary);
            } finally {
                // 云卡只在没接受时撤。接受的那条路马上会被 {@link #progress} 改写成进度，
                // 收完再由 {@link #delivered} 收尾。
                if (cloud && !wanted) {
                    LiveCard.drop(context);
                    cloud = false;
                }
            }
            // 「人说了不行」和「一直没人理」对发送端是同一个 401，对人却是两句不同的话。
            // 这一行把两者分开，也把耗时写下来——超时这个数只有在拒绝之后才看得见，
            // 而人看到的是一张很久之前就弹出来的卡片。
            Log.i(TAG, "invitation " + session
                    + (wanted ? " accepted" : answered ? " refused" : " unanswered")
                    + " after " + (System.currentTimeMillis() - askedAt) + " ms"
                    // 前半句是「问在哪」，后半句是「答在哪」。它们可以不同：卡片先问、三秒后
                    // 兜底通知接手，人是从通知进来的——这正是那段兜底存在的理由，日志必须能
                    // 把它和「卡片画出来了」区分开。
                    + " asked=" + asked
                    + " answered=" + (answered && answeredFrom != null ? answeredFrom : "nobody"));
            if (!wanted) {
                // 拒绝的话必须说清是谁拒的。过去两种情况共用「已拒绝」，于是超时被读成
                // 「我没点它，它自己拒的」——那不是人看错，是我们没写明白。
                // `expired` 是标题表里本来就有的一个相位（「邀请已过期」），只是从来没有
                // 人发过它：状态码两边都是 1004（对发送端都是同一个 401），区别只在标题。
                end(answered ? "rejected" : "expired", REJECTED, answered
                        ? "已拒绝"
                        : (decisionMs / 1000) + " 秒内没人应答，已放弃");
            } else {
                // 答过之后，最后那一帧不能再留在「邀请」上。
                //
                // 界面晚到一步时会按最后那一帧重放（{@link #snapshot}），而这条连接马上就要
                // 去读 /Upload 了。中间这一段如果不说话，重放出来的就是那张已经答过的邀请——
                // 于是同一个问题被问第二遍，这一次没人会答，六十秒后屏幕上出现「已拒绝」。
                // 人在那一刻的感觉就是「我没点拒绝，它自己拒的」。
                //
                // 说的是实话：人说了收，接下来就是收。字节还没到，所以没有百分比。
                progress(0, 0);
            }
            return wanted;
        }

        /** 走到哪儿了。{@code total} 为 0 是「对端什么都没声明」，不是「零字节」。 */
        void progress(long done, long total) {
            if (!announced || settled) {
                return;
            }
            try {
                percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
                if (cloud) {
                    // 同一个 id，同一朵云：邀请那张卡在这里变成进度，不需要先撤再发。
                    //
                    // 进度卡上带【取消接收】：点了「接收」之后，人在系统这一侧唯一的出口就是它。
                    // 不发这一颗，一张传到 30% 的卡上就一个按钮也没有——人只能等，或者去杀进程。
                    LiveCard.updateWithCancel(context, "正在接收", line(done, total), percent,
                            cancelAction());
                }
                emit(newEvent("receiving", RECEIVING).put("progress", percent));
            } catch (JSONException impossible) {
                Log.w(TAG, "receive progress unavailable", impossible);
            }
        }

        /**
         * 【取消接收】那颗按钮。
         *
         * <p>request code 按 id 分：同一台机器上可能同时有两条接收在进行（两台 iPhone 一起
         * 发是真实的场景），而 {@code PendingIntent} 的 extras 只按 request code 区分——共用一个
         * 编号，第二张卡会把第一张的 session 覆盖掉，于是人按「取消」取消掉的是另一次传输。
         * 落在一个别的按钮都不用的段里（0x0C00 起），也是为了不和【接收】【拒绝】【打开文件】
         * 撞上。
         */
        private PendingIntent cancelAction() {
            if (cancelling == null) {
                Intent intent = new Intent(context, DecisionReceiver.class)
                        .setAction(CANCEL)
                        .putExtra(Confirmation.EXTRA_ID, session);
                cancelling = PendingIntent.getBroadcast(context,
                        0x0C00 + (int) (id & 0x3FF), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            return cancelling;
        }

        /**
         * 卡片第二行：{@code 45% · 3 个文件 · 2.6 MiB / 5.8 MiB}。
         *
         * <p>和发送卡那句是同一个形状，因为人在这两件事上问的是同一个问题。三段各管一件事：
         * 百分比是胶囊上那个数，件数说这一次是几份东西，字节数是「还剩多久」唯一能算的凭据
         * ——2.6 MB 里的百分比看不出还剩多久。
         *
         * <p>缺哪一段就不写那一段，而不是写个占位：对端没声明总数时没有分母，与其编一个
         * 「共 0 B」，不如只说「3 个文件」。三段都缺（刚接受，字节还没到）时说的是「准备中」。
         */
        private String line(long done, long total) {
            List<String> parts = new ArrayList<>(3);
            if (percent >= 0) {
                parts.add(percent + "%");
            }
            int count = offered == null ? 0 : offered.length();
            if (count > 0) {
                parts.add(count + " 个文件");
            }
            if (total > 0) {
                // 封顶到 total：对端可以多发，而一条写着「112%」的卡只会让人以为哪里坏了。
                parts.add(Listener.bytes(Math.min(done, total)) + " / " + Listener.bytes(total));
            }
            return parts.isEmpty() ? "准备中" : String.join(" · ", parts);
        }

        /**
         * 收完了，文件已经在公共目录里。
         *
         * <p>每一份的字节数与落点都从发布结果里取，不再共用一份参数：一次接收可能同时进
         * 相册和文档，两个目录，而这块卡片上「存到哪儿」是唯一一句人真正需要的话。
         *
         * <p>没存下的那几份只报件数，不把它们混进 files 里——卡片上一条文件应当指着一份
         * 真的存在的东西。
         */
        void delivered(Publish.Result published) {
            if (!announced || settled) {
                return;
            }
            settled = true;
            if (cloud) {
                // 收完不是结束：人还要去打开那份东西。所以这张卡不自己消失，留到人按「完成」。
                // 第二行和发送完成那张说同一句话的形状：几份、多少字节。这里不写「100%」——
                // 完成这件事由标题说，而百分比在一张已经画满的轨道旁边是同一个意思说两遍。
                LiveCard.finishWithActions(context, "接收完成",
                        published.entries().size() + " 个文件 · " + Listener.bytes(published.bytes()),
                        openAction(context, published));
                // 收完也摊开：这张卡上「存到哪儿」和【打开文件】是收尾那一步真正的信息，
                // 而它们只在摊开的那一面看得见。
                // 摊开这件事已经不做了：应用侧没有这个开关，硬撬（Xposed 进 SystemUI）会让这张
                // 卡在通知中心缺席、并且点空白区域收不回去。宁可是一颗点一下才展开的胶囊。
                cloud = false;
            }
            try {
                JSONArray files = new JSONArray();
                for (Publish.Entry entry : published.entries()) {
                    files.put(new JSONObject()
                            .put("name", entry.name)
                            .put("size", entry.size)
                            .put("mime", mime(entry.name))
                            // 存到哪儿是这块卡片上唯一一句人真正需要的话：这个应用没有浏览
                            // 目录的入口，除了这里没有第二个地方会说出来。
                            .put("directory", entry.directory)
                            // 「打开文件」按下去要有一个能被别的应用读到的地址。收完之后
                            // 字节归媒体库管，我们手上没有可共享的路径，所以只有媒体库给的
                            // 这一串能担这件事——留空就等于那个按钮点了没反应。
                            .put("uri", entry.uri == null ? "" : entry.uri.toString()));
                }
                JSONObject event = newEvent("complete", COMPLETE)
                        .put("progress", 100)
                        .put("terminal", true)
                        .put("published", true)
                        .put("files", files);
                List<String> notes = new ArrayList<>();
                // 一张卡上已经有发送端自己报的那个总量了，再写一句「共 X」就是同一件事说两遍，
                // 而且两边的单位还不一样（对方写 MB，我们写 MiB）。只有真丢了东西的时候这个
                // 数才不一样、才有话说——那时候它说的是「存下来的到底有多少」。
                if (!published.skipped().isEmpty()) {
                    notes.add("存下 " + Listener.bytes(published.bytes()));
                    notes.add(published.skipped().size() + " 个没存下");
                }
                if (!notes.isEmpty()) {
                    event.put("message", String.join("，", notes));
                }
                emit(event);
            } catch (JSONException impossible) {
                Log.w(TAG, "receive completion unavailable", impossible);
            }
            ReceiveHost.forget(session);
        }

        /**
         * 收到的是链接，不是文件。
         *
         * <p># 为什么它得是单独一个出口
         *
         * <p>一次纯链接分享一个字节都不会 {@code /Upload}：对面只是把 {@code Items} 里那串
         * 地址报过来，问一声要不要，这边说收，事情就结束了。所以 {@link Listener#serve} 里
         * 那条「有东西落地」的收尾分支（{@code report} 非空）永远走不到它——牌面上「什么都
         * 没收到」，而实际收到的是这次分享的全部内容。
         *
         * <p>不走这里的代价是明确可见的：{@link #abandon} 看见 {@code accepted} 为真、
         * {@code settled} 为假，会把一次**成功**画成「接收失败：连接意外断开」。线是通的、
         * 邀请是收了的、对面也拿到了 200，只有这张卡在说假话。
         *
         * @return 这次是不是按链接收下并结账了。{@code false} 表示「这不是一条能结账的链接
         *         offer」（没接受、没有地址、或者早就结过账），调用方该继续走原来的收尾路径
         */
        boolean deliveredLinks() {
            if (!announced || settled || offer == null || offer.urls.isEmpty()) {
                return false;
            }
            settled = true;
            if (cloud) {
                // 和文件那一份同样的形状、同样的理由：收完不自己消失，留到人按【完成】——
                // 人拿到链接之后的下一步是打开它，而这张卡是唯一告诉他「东西在哪」的东西。
                LiveCard.finishWithLink(context, "接收完成", linkLine(offer),
                        openLink(context, offer.urls));
                cloud = false;
            }
            try {
                JSONArray list = new JSONArray();
                for (String url : offer.urls) {
                    list.put(url);
                }
                JSONObject event = newEvent("complete", COMPLETE)
                        .put("progress", 100)
                        .put("terminal", true)
                        .put("published", true)
                        .put("links", list);
                emit(event);
            } catch (JSONException impossible) {
                Log.w(TAG, "receive completion unavailable", impossible);
            }
            ReceiveHost.forget(session);
            return true;
        }

        /**
         * 链接那张卡的第二行：{@code 1 个链接 · apps.apple.com}。
         *
         * <p>和文件那张「N 个文件 · 4.2 MiB」是同一个形状：先说几个，再说是什么。文件用
         * 字节数说「多大」，链接没有重量，能说的只有它落在哪台主机上——而这一句正好回答
         * 人能问的唯一问题：「这是不是我认识的那个站」。
         *
         * <p>主机读不出来时（自定义 scheme、对面塞了个不成形的地址）退回整条地址。少写这
         * 一段，这张卡就等于什么也没说。
         */
        private static String linkLine(Confirmation.Offer offer) {
            String first = offer.urls.get(0);
            String host = android.net.Uri.parse(first).getHost();
            return offer.links + " 个链接 · " + (host == null || host.isEmpty() ? first : host);
        }

        /**
         * 没收到，或者收到了没存下来。{@code message} 是给人看的那句话。
         *
         * <p>和其余出口唯一的区别是**不撤卡**。卡片是这次接收唯一一个一直跟在人眼前的东西
         * （页面可能根本没开着，普通通知可能因为没授通知权限而从来没出现），而撤掉它等于把
         * 「刚才那一下为什么没成」一起撤掉。
         *
         * <p>2026-11-17 那次「点发送、点接收，流体云通知自己关了」正是这么来的：连接在回话
         * 之前就被 ColorOS 的后台限网掐断，失败一路被当成「对端走掉」，卡片按常规收尾撤掉，
         * 人看到的是通知无故消失——一次**失败**被画成了一次**没有发生**。
         */
        void failed(String message) {
            if (cloud) {
                // 只挂【完成】，没有【重试】：重来一次要对面重新发，而对面那台手机不归我们叫醒。
                // 轨道停在真走到的那个数上（{@link #percent}），不拉满——失败画成成功，是这里
                // 最不能犯的错。
                LiveCard.failedWithActions(context, "接收失败", message, percent, null);
                cloud = false;
            }
            settle("failed", FAILED, message);
        }

        /**
         * 这条连接结束了，但它没有结果可报。
         *
         * <p>只收自己说过话的那一次。一次都没说过话的连接——握手的、探测的、设置读不出来
         * 的——不该在卡片上留下任何痕迹。
         *
         * <p>「没有结果」分三种，第三种不能漏：
         *
         * <ul>
         *   <li>人自己按了取消 —— 那张卡该干脆利落地消失。
         *   <li>人还没回答，连接就没了（分享面板关了、文件发给了别的设备、这分钟里第四次
         *       重连）—— 撤卡，不留话。这是 {@code Listener.routine} 想保住的那份安静。
         *   <li>**人说了「收」，东西却没到手** —— 这是失败，而且原来走的也是撤卡那条路。
         *       走那条路的结果是：native 侧把错误吞掉、返回一个空 frame 的那些情形，Java
         *       这边一行异常都看不到，卡片照样被撤掉，人看到的还是「通知自己关了」。
         * </ul>
         *
         * <p>判据用 {@code accepted} 而不是异常类型，是因为「值不值得说」这件事跟异常长什么
         * 样无关，跟人做过什么有关：人点了接收之后，任何一个断法都是「这次没收到」。
         */
        void abandon() {
            if (cancelled) {
                end("cancelled", CANCELLED, "已取消");
            } else if (accepted) {
                failed("连接意外断开");
            } else {
                end("failed", FAILED, "连接已断开");
            }
        }

        /** 人说收，或者说不收。两个表面最后都落到这里。 */
        void answer(boolean yes, String from) {
            accepted = yes;
            answered = true;
            answeredFrom = from;
            decided.countDown();
            // 通知那条路可能正在等：通知按钮和卡片按钮回答的是同一件事，所以两边都叫醒。
            // 没有人在等的时候它什么也不做。
            Confirmation.answer(session, yes);
        }

        /**
         * 人说取消。
         *
         * <p>还在等人回答的时候，取消就是拒绝；已经在收的时候，取消是把连接掐掉——那条
         * 读字节的线程会从 read 里出来，走的是和对端走掉完全一样的一条路。
         *
         * <p>真正关连接那一步不在这里做，见 {@link #abort}。
         */
        void cancel() {
            cancelled = true;
            if (decided.getCount() > 0) {
                // 人是从卡片上的取消进来的，所以来源记 card；日志里那一句要知道是谁按的。
                answer(false, "card");
                return;
            }
            abort();
        }

        /**
         * 把这条连接掐掉。**必须不在调用者线程上做**——{@link HangUp#now} 负责这条约束，
         * 连同「为什么必须发 RST 而不是好好说再见」「那条读字节的线程怎么办」都在它那里。
         *
         * <p>这里只留一件本地的事：{@code peer} 此刻可能还是握手前的裸 socket，也可能是
         * TLS 那一层。两种都行——{@code setSoLinger} 与 {@code close} 在两边都成立，而取消
         * 只有一条路可走：把这条连接弄没。
         */
        private void abort() {
            HangUp.now(peer);
        }

        /** 把最后那条再说一遍。界面晚到的时候用。 */
        void replay() {
            JSONObject event = last;
            if (event != null) {
                ReceiveHost.emit(event);
            }
        }

        /** 一条新事件，四条都带着的字段写在这里，免得每处都记一遍。 */
        private JSONObject newEvent(String phase, int status) throws JSONException {
            JSONObject event = new JSONObject()
                    .put("ev", "incoming_transfer")
                    .put("session", session)
                    .put("id", id)
                    .put("phase", phase)
                    .put("status", status)
                    .put("name", sender)
                    .put("terminal", false)
                    .put("published", false)
                    .put("progress", -1);
            // 邀请里那份清单，每一帧都带上。卡片的摘要只看得见手上这一条事件，所以它必须
            // 每一条都完整——这不是缓存，是因为「同一件事只有一种说法」在这里的具体做法。
            // 收完之后 {@link #delivered} 会用自己的那份盖掉它：那一份带着落点，才是人真正
            // 需要的下一句话。
            if (offered != null && offered.length() > 0) {
                event.put("files", offered);
            }
            return event;
        }

        /**
         * 邀请里那份清单，转成卡片认的形状。
         *
         * <p>和 {@link #delivered} 里那份只差一个 {@code directory}——还没收，当然还没有
         * 落点。卡片按名字画前三个、按字节数画总数，两个字段都有就够了。
         *
         * <p>大小未知的（文件夹条目）给 -1 而不是 0：卡片那边见到负数会只报件数不报总数，
         * 而这正是「对面没说」该有的说法。给 0 会画出一个「共 0 B」，那是假话。
         */
        private static JSONArray filesOf(List<Confirmation.Item> files) {
            JSONArray out = new JSONArray();
            for (Confirmation.Item file : files) {
                try {
                    out.put(new JSONObject()
                            .put("name", file.name)
                            .put("size", file.size));
                } catch (JSONException impossible) {
                    Log.w(TAG, "receive invitation file unavailable", impossible);
                }
            }
            return out;
        }

        private void end(String phase, int status, String message) {
            if (cloud) {
                // 没有收成就没有可留的东西：一张停在中间态、也没人能再点的常驻卡，比没有更糟。
                // 失败是唯一的例外，它走 {@link #failed}——那一句解释值得留在屏幕上几秒。
                LiveCard.drop(context);
                cloud = false;
            }
            settle(phase, status, message);
        }

        private void settle(String phase, int status, String message) {
            if (announced && !settled) {
                settled = true;
                try {
                    emit(newEvent(phase, status)
                            .put("terminal", true)
                            .put("published", false)
                            .put("retryable", false)
                            .put("message", message));
                } catch (JSONException impossible) {
                    Log.w(TAG, "receive " + phase + " unavailable", impossible);
                }
            }
            // 退表是无条件的，包括一次话都没说过的连接。握手、探测、设置读不出来的那些连接
            // 在界面上不该留下任何痕迹，但它们在这张表里各占一个位置——漏收的结果是这张表
            // 只增不减，而且每一条都指向一个早就关掉的 socket。
            ReceiveHost.forget(session);
        }

        private void emit(JSONObject event) {
            last = event;
            ReceiveHost.emit(event);
        }

        private boolean await(long timeoutMs) {
            try {
                decided.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                // 接收端正在被关掉。把中断还回去，让上层知道它该退出。
                Thread.currentThread().interrupt();
                return false;
            }
            return accepted;
        }
    }
}
