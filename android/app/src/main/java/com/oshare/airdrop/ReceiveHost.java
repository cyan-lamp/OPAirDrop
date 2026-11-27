package com.oshare.airdrop;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 还没结束的每一次接收的登记簿，以及那颗等答案的闩。
 *
 * <p># 它为什么存在
 *
 * <p>接收这件事本身完全在协议层里：{@link Listener} 收字节，库决定答什么，文件落在磁盘
 * 上。需要人过目的只有一刻——有人要发东西——那一刻由流体云胶囊（{@link LiveCard}）或本应用
 * 自己的通知（{@link Confirmation} 与 {@link ReceiveService}）送到眼前，两条路最后都把答案
 * 落回同一个闩上。这个类就是那份登记加那颗闩：按 {@code session} 记住没结束的接收，好让
 * 卡片、通知、广播里的按钮找得到它。
 *
 * <p># 它不画，也不再说事件
 *
 * <p>2026-11-16 之前这里往互传进程推 {@code incoming_transfer}，由它照着画卡。那条路撤掉时
 * 只掐了出口（{@code emit} 变成空方法），拼事件的那几段、binder 桥那个回信地址、以及界面晚
 * 到时用来重放的 {@code snapshot} 全都留在原地——五处调用每收一次仍在拼一个 {@code JSONObject}
 * 然后扔掉，而 {@code snapshot} 一个调用者也没有。2026-11-19 把这些一并清了。
 *
 * <p>现在对外可见的状态只有两个出口，都不经过这里：卡片由 {@link LiveCard} 直接推，进度由
 * {@code Listener.Meter} 投到那张卡上。这个类只管登记与回答。
 *
 * <p># 为什么状态按 session 存在这里
 *
 * <p>「接受」最后必须落回停在 {@link Confirmation} 里等答案的那条线程，而按下去的地方在
 * 系统流体云里。中间隔着一条只能传字符串的路，两头得用同一个名字指同一件事——那就是
 * {@code session}。这个名字由这里产生，同时交给 {@link Confirmation#ask}，于是流体云上的
 * 按钮和通知上的按钮回答的是同一个闩。
 *
 * <p># 它不做什么
 *
 * <p>它不判断该不该收。该不该收是人的事，它只负责把问题送到人眼前、把答案带回来。它也不
 * 管文件落在哪儿——落点由 {@link Listener} 算出来，说给人听的是 {@code Listener.last()}。
 */
final class ReceiveHost {

    private static final String TAG = "OPAirDrop";

    /** 有没有一条传输正在跑：iPhone 一次只推一个流，第二条要如实拒绝（见 decide）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 卡片上【取消接收】那颗按钮的广播。带 {@link Confirmation#EXTRA_ID}，值是 session。
     *
     * <p>它和 {@link Confirmation#ACCEPT}／{@link Confirmation#DECLINE} 走同一个接收器
     * （{@link DecisionReceiver}）：那两颗回答的是「收不收」，这一颗回答的是「收到一半还要不要
     * 继续」，但三颗按钮落在同一件事上——同一个 session 命名的同一次接收。分成两个接收器，
     * 就要在两处各写一遍「先去表里找这一条」，而找错一次就是取消掉别人的传输。
     */
    static final String CANCEL = "com.oshare.airdrop.RECEIVE_CANCEL";

    /** 保护下面这份表。{@link Attempt} 自己的字段不归它管。 */
    private static final Object LOCK = new Object();

    /**
     * 还没结束的接收，键是 session。
     *
     * <p>只用来回答指令：卡片、通知、广播里的按钮都拿着 session 来这里找那次接收。它不再
     * 「重放快照」——界面晚到时该看的是 {@link LiveCard} 自己手上那张卡，不是这里补画一遍。
     */
    private static final LinkedHashMap<String, Attempt> LIVE = new LinkedHashMap<>();

    /** session 与 id 的同一个来源：一次接收一个号，只增不减。 */
    private static final AtomicLong IDS = new AtomicLong();

    private ReceiveHost() {}

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
        // 这一行是为了分开两种「按了取消没反应」：广播没到这里（卡片那颗按钮带的是旧
        // session），还是到了但表里已经没有这条连接。两者修法完全不同。
        Log.i(TAG, "cancel(" + session + ") -> " + (attempt != null ? "found" : "no such session"));
        if (attempt == null) {
            return false;
        }
        attempt.cancel();
        return true;
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

    /*
     * 这里原来有一个 {@code private static void emit(JSONObject)}，往互传进程推一条
     * {@code incoming_transfer}，由它照着画卡。
     *
     * <p>2026-11-16 15:45 起它就是个空方法体了。当时真机上出现了一件怪事：传输过程中通知栏
     * 和流体云里**同时**有一加互传自己的一条普通通知，它不属于我们，也点不动——原因是每收一次
     * 我们还在给互传发事件，它就照着画一张。出口当天下班前就掐了，拼事件的那五处却留到
     * 2026-11-19 才清掉，中间每收一次都白拼一个 {@code JSONObject} 扔进垃圾桶。
     *
     * <p>顺带说明一件容易读反的事：LSPosed 作用域**没有**跟着删。2026-11-19 换了一套实现又装
     * 回来的 {@link FluidModule} 管的是另一件事——把流体云的 {@code remindType} 从 0 抬到 11，
     * 让卡片自己摊开。它跟互传进程无关，作用域是 {@code com.android.systemui}。
     */

    private static void forget(String session) {
        synchronized (LOCK) {
            LIVE.remove(session);
        }
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

        /** 这次邀请的答案，以及它是怎么来的。五个状态的关系只写在那里。 */
        private final Decision decision = new Decision();

        /**
         * 这次邀请是在云端卡上问的（{@link LiveCard}），不是本应用的通知。
         *
         * <p>一条连接一个值，在决定那一刻定下来：系统不肯提升我们时它就是假的，之后的进度与
         * 结果都走原来那条通知，不该去碰一张不存在的卡。
         */
        private volatile boolean cloud;

        /** 这条连接。人说取消时关掉它，读字节的那条线程会从这里出来。 */
        private final Socket peer;

        /**
         * 进度卡上【取消接收】那颗按钮，第一次画进度时造一次。
         *
         * <p>造一次而不是每帧都造：进度卡每两百毫秒重画一次（见 {@link Listener.Meter}），
         * 而 {@code PendingIntent.getBroadcast} 是一次会走到系统进程的调用，稳定在这个对象上
         * 之后，重画就只剩一次 {@code notify}。
         */
        private PendingIntent cancelling;

        /** 对面叫什么。摘要解析出来之后才可能知道，后面几条要说的话都接着用它。 */
        private String sender = "Apple 设备";

        /**
         * 对面报了几份。
         *
         * <p>只要一个数：进度卡第二行那句「3 个文件」用它，而卡片此后每一帧都要说这句话，
         * 所以它得留在字段上，不能只活过一次邀请。还不知道是 0，对面没报也是 0——两种情形
         * 说的话一样，没有必要分开记。
         */
        private int offeredCount;

        /**
         * 这次邀请解析出来之后的那一份，留着给收尾用。
         *
         * <p>链接分享的收尾拿不到任何字节作凭据：那些地址只在邀请那一帧的摘要里出现过一次。
         * 不在这里留住，等 {@code serve} 返回时就已经没有地方还记得对面到底发了哪几条——
         * 而「哪一条」正是链接的全部内容。
         */
        private Confirmation.Offer offer;

        /**
         * 有没有对外说过话。没说过就不用收尾。
         *
         * <p>{@code volatile} 是给 {@link #progress} 看的：进度从 2026-11-19 起由另一条线程
         * 投递（见 {@code Listener.Meter}），那条线程要读这个标志才敢画卡。没有它，一个
         * 早先在读线程上写下的 {@code true} 可以永远不被看到，进度就永远画不出来。
         */
        private volatile boolean announced;

        /**
         * 结果已经说过了。收尾不再覆盖它。
         *
         * <p>同样是因为投递换了线程：这一位由读线程在收尾时置上，由投递线程在每一次进度
         * 之前检查。不加 {@code volatile}，一条**迟到的**进度就可能盖掉「接收完成」——
         * 屏幕上于是出现一张停在 97%、写着正在接收、再也不会动的卡。
         */
        private volatile boolean settled;

        /**
         * 最后一次说出去的进度；负数表示对端什么都没声明。
         *
         * <p>留着它是为了失败卡：一条断在中途的连接，轨道该停在她真正走到的那个数上，而不是
         * 拉满（失败画成功）也不该转圈（那是「还在动」）。见 {@link LiveCard#failed}。
         */
        private int percent = -1;

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
            return decision.answered();
        }

        /**
         * 人自己按过【取消接收】没有。
         *
         * <p>问这一句的地方只有两处，而且问的都是同一件事：**这次收尾该不该说成失败**。
         * 人按了取消之后，{@link HangUp#now} 会发 RST，读字节的那条线程从 {@code read} 里
         * 冒出来的异常与「对面走了」完全同形（{@code Socket closed} / {@code Connection
         * reset}），下游那两处失败分支分不出来——但这里分得出来，因为只有人会调
         * {@link #cancel}。所以判据不是异常长什么样，是人做过什么，与 {@link #answered}
         * 那条判据同源。
         */
        boolean cancelled() {
            return decision.cancelled();
        }

        /**
         * 人明确说了「不」（不是超时、也不是取消传送）。
         *
         * <p>这一位要单独问，是因为它和 {@link #accepted} 不一样：{@code accepted=false} 也
         * 包含「一直没人回答」。而这两种收尾在 {@link Listener} 那里的下场相反——没人回答是
         * 安静的，人拒绝了则会被后面那句 {@code because(...)} 说成「连接被系统切断，检查后台
         * 限网」。人明明点了拒绝，屏幕上却劝他查路由器。
         */
        boolean declined() {
            return decision.declined();
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
        /** 这条邀请的通知 id：每条一个，互不覆盖（2026-11-27）。 */
        private int inviteId() {
            return 0x0B00 + 1 + (Math.abs(session.hashCode()) % 0x60);
        }

        boolean decide(String summary) {
            // 2026-11-27：前一条传输还在跑时**根本不弹卡**。iPhone 一次只推一个流，第二条
            // 并行连接会空等 20 秒再把正在传的那条一起掐断；而先弹卡再拒绝，用户会看到一张
            // 按钮全失效、也撤不掉的死卡（用户指出的问题）。所以在这里就如实拒绝，卡不出现。
            if (BUSY.get()) {
                Log.w(TAG, "invitation " + session
                        + " refused before the card: another transfer is still running");
                return false;
            }
            // 一次邀请一个数，卡片和通知都从这里拿：让两条路共用一个常量是上一版的做法，
            // 那是「同一个数」靠人记得；现在它们读同一行设置，是结构上的同一个数。
            long decisionMs = Confirmation.decisionTimeout(context);
            Confirmation.Offer offer = Confirmation.parse(summary, decisionMs);
            // 同一份解析结果留一份在字段上：链接收尾要用它（见 {@link #deliveredLinks}），
            // 而摘要是这条连接里唯一一次出现过那些地址的地方。
            this.offer = offer;
            sender = offer.sender;
            announced = true;
            // 先把「哪几份」记下来再画卡：卡片第一帧就该看得到件数，而不是先写一句
            // 「准备中」、等下一次刷新才对。
            offeredCount = offer.files.size();
            // 只有一条表面：流体云。互传那张卡与它的叫醒逻辑已经撤掉（2026-11-16 16:40），
            // 于是这里不再有「卡片在不在」这个中间状态——发出去就是发出去了。
            Log.i(TAG, "invitation " + session + " from " + sender
                    + " files=" + (offer.files.isEmpty() ? "none" : offer.files.size())
                    // 链接分享没有文件，但它不是「什么都没报」。过去这一行在这种情况下写
                    // `files=unreported`，读日志的人会去找一个不存在的解析失败。
                    + " links=" + offer.links);
            long askedAt = System.currentTimeMillis();
            // 问在哪：只有流体云这一条。应用级的浮窗已经撤掉（2026-11-16 15:10）——它要么
            // 抢走前台、要么把胶囊挡住，为了「更醒目」付出的代价比收益大；底部那块面板也跟着
            // 互传的卡一起撤了（2026-11-16 16:40）。系统不肯提升我们时退到本应用的抬头通知：
            // 它比互传那张卡可靠一个量级，发出去就是发出去了，没有「binder 活着但没画」这种
            // 状态（2026-11-16 13:14 撞过一次，屏幕上什么都没有，超时后按时过期）。
            // 流体云只有一张：上一张邀请还没结论时，这条用普通通知问人，不排队、不阻塞。
            cloud = LiveCard.allowed(context);
            if (cloud) {
                // 接到东西了，这张卡本该自己摊开：消息藏在胶囊里等于没送到。摊开这件事应用侧
                // 没有开关——早先那条路是靠 Xposed 模块进 SystemUI 调插件的 onExpand，代价是
                // 通知中心里看不见这张卡、点空白区域收不回去，那条广播已经撤了。现在分两种
                // 情形：启用可选的模块（FluidModule 把 remindType 抬到 11），卡片由系统自己
                // 摆开；不启用，就靠高重要性渠道 + setOnlyAlertOnce(false) 先抬头一次把人叫到，
                // 看不看得清那两个按钮交给用户点一下胶囊。
                PendingIntent yes = Confirmation.decision(context, session, true);
                PendingIntent no = Confirmation.decision(context, session, false);
                LiveCard.invite(context, inviteId(), offer.title(), offer.body(), yes, no);
            }
            // 三条出口都不通的时候，别让对面等满六十秒。
            //
            // 出口只有三条：胶囊（cloud）、普通通知（Confirmation.ask）、以及正开着的界面。
            // 前两条都要 POST_NOTIFICATIONS 这条运行时权限，而新装的应用默认是关的——
            // 1.0.0 就是这么装上去的：授权为 false，系统把每一条通知静默丢弃，于是每次邀请
            // 都走满六十秒再判拒绝。人看到的是「iPhone 发了，这边没反应」，日志里写的是
            // `unanswered`；那句话在这里是假的，真相是"没有人被告知"。
            //
            // 所以这一条不是优化，是把假话改成真话：没有出口就当场说不，让对面几百毫秒内
            // 拿到 401，而不是等它自己超时。
            if (!cloud && !Notices.allowed(context) && !Listener.of(context).watched()) {
                Log.w(TAG, "invitation " + session
                        + " dropped: no surface at all (notifications off, nothing on screen)");
                end();
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
                LiveCard.dropInvite(context, inviteId());
                LiveCard.releaseInvite();
                if (cloud && !wanted) {
                    LiveCard.drop(context);
                    cloud = false;
                }
            }
            // 「人说了不行」和「一直没人理」对发送端是同一个 401，对人却是两句不同的话。
            // 这一行把两者分开，也把耗时写下来——超时这个数只有在拒绝之后才看得见，
            // 而人看到的是一张很久之前就弹出来的卡片。
            Log.i(TAG, "invitation " + session
                    + " " + decision.verdict()
                    + " after " + (System.currentTimeMillis() - askedAt) + " ms"
                    // 前半句是「问在哪」，后半句是「答在哪」。它们可以不同：卡片先问、三秒后
                    // 兜底通知接手，人是从通知进来的——这正是那段兜底存在的理由，日志必须能
                    // 把它和「卡片画出来了」区分开。
                    + " asked=" + asked
                    + " answered=" + (decision.answered() && decision.from() != null
                            ? decision.from() : "nobody"));
            if (!wanted) {
                // 「人说了不行」和「一直没人理」的分别只在上一条日志里：对发送端是同一个 401，
                // 对人也一样——这张卡撤掉，不留话。要分得清就得去看那一行。
                end();
            } else {
                // 答过之后，邀请那张卡不能再挂着两个按钮：人已经按了，按钮还亮着就会有人再按
                // 一次。这一帧把它改写成「正在接收」。说的是实话——人说了收，接下来就是收；
                // 字节还没到，所以没有百分比。
                progress(0, 0);
            }
            return wanted;
        }

        /** 走到哪儿了。{@code total} 为 0 是「对端什么都没声明」，不是「零字节」。 */
        /** 已落盘的成员数 / 发送端报的成员总数（0 = 没报，见 {@link #line}）。 */
        private int membersDone;
        private int membersTotal;

        /** 卡片最快多久换一次（与发送侧同一个理由：别让通知拖慢传输线程）。 */
    // 2026-11-27：400 ms 一次是「每秒 2-3 次通知重建」，每次重建系统都要重新走一遍流体云
    // 展开流程（LSPosed 日志里每秒一条「提醒级别 0 → 11」就是它）。慢速链路下这种重建要持续
    // 几十秒，纯属给 SystemUI 加负载。放到 1 秒一次，进度看起来照样是连续的。
    private static final long CARD_EVERY_MS = 1000;

        private final Handler main = new Handler(Looper.getMainLooper());
        private long lastCardAt;

        void progress(long done, long total) {
            progress(done, total, membersDone, membersTotal);
        }

        void progress(long done, long total, int doneMembers, int totalMembers) {
            if (!announced || settled) {
                return;
            }
            // native 侧的约定是「成员数为 0 表示自上次报告以来没有成员完成」，不是「已收
            // 0 份」（见 `crates/airdrop/src/inbox.rs` 的 `Progress`）。每写一块都会来一次
            // 带 0 的调用，照抄就会让「已收 N/M 份」那半句在传输过程中反复消失又出现，
            // 看上去像进度在倒退。
            if (doneMembers > 0) {
                membersDone = doneMembers;
            }
            if (totalMembers > 0) {
                membersTotal = totalMembers;
            }
            // 只有「每一份都声明了大小」时 total 才是可信的分母；实况/混合场景下它只是
            // 部分值，用它算百分比会画出「100% 还在传」的假进度。不可信时 percent 留 -1，
            // 卡片只报「已收 X」。
            // total 现在由 native 侧算好：/Ask 每份都报了 size 时是那些之和，否则是
            // /Upload 的 Content-Length（线上字节总数）。两种都比「部分之和」可信，
            // 所以只要它大于 0 就能当分母（2026-11-26 修：之前按 offer.fullySized 把
            // Content-Length 也一起否了，实况于是彻底没有进度）。
            percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
            BUSY.set(true);
            if (cloud) {
                // 同一个 id，同一朵云：邀请那张卡在这里变成进度，不需要先撤再发。
                //
                // 进度卡上带【取消接收】：点了「接收」之后，人在系统这一侧唯一的出口就是它。
                // 不发这一颗，一张传到 30% 的卡上就一个按钮也没有——人只能等，或者去杀进程。
                // 投卡是 Binder 到 system_server，再让 SystemUI 重建流体云卡片（本机还挂着
                // LSPosed 那个「每个 notify 都把卡展开一次」的钩子）。这件事**不能**在接收
                // 线程上做：一条通知慢 100ms，接收线程就少读 100ms；几秒下来 TCP 窗口填满，
                // 对端重传几次就把连接 abort 掉——2026-11-27 00:16 那次 58 MB 在 76% 断掉
                // （Software caused connection abort）就是这个形状。
                //
                // 所以：按 400ms 节拍、扔回主线程投；接收线程只管读字节。
                LiveCard.useCard(inviteId());
                long now = SystemClock.uptimeMillis();
                if (percent >= 0 && percent < 100 && now - lastCardAt < CARD_EVERY_MS) {
                    return;
                }
                lastCardAt = now;
                String text = line(done, total);
                int shown = percent;
                PendingIntent cancel = cancelAction();
                main.post(() -> LiveCard.updateWithCancel(context, "正在接收", text, shown,
                        cancel));
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
            if (offeredCount > 0) {
                // 类型化件数（2026-11-26 用户规格，见 Confirmation.tally）：照片说「张」、
                // 视频说「个」、其余与混合说「份」。
                parts.add(Confirmation.tally(offer == null
                        ? java.util.Collections.emptyList() : offer.files, offeredCount));
            }
            // 连 Content-Length 都没有（chunked 分帧）时的最后一把刻度：已落盘成员数 /
            // 发送端报的件数。只在**没有字节分母**时才用——两边的计数单位并不相同
            // （分子是归档成员，分母是 /Ask 的件数，实况一个 .pvt 解出好几个成员），
            // 一起显示就会出现「已收 20/5 个」。
            if (total <= 0 && membersDone > 0 && membersTotal > 0) {
                parts.add("已收 " + membersDone + "/" + membersTotal + " 份");
            }
            if (total > 0) {
                // 封顶到 total：对端可以多发，而一条写着「112%」的卡只会让人以为哪里坏了。
                parts.add(Explains.bytes(Math.min(done, total)) + " / " + Explains.bytes(total));
            } else if (done > 0) {
                // 总数未知（iPhone 发实况照片时 offer 不带 size、上传还走 chunked 分帧，
                // 连 /Upload 的 Content-Length 都没有——2026-11-24 实测）：没有分母就没有
                // 百分比，但「已经到了多少」是事实，写上去比一行光秃秃的「1 个文件」有用。
                parts.add("已收 " + Explains.bytes(done));
            }
            return parts.isEmpty() ? "准备中" : String.join(" · ", parts);
        }

        /**
         * 收完了，文件已经在公共目录里。
         *
         * <p>卡片上只说两个数：几份、多少字节。落点不在这张卡上——它写的是「3 个文件 ·
         * 4.2 MiB」，而「存到哪儿」由 {@code Listener.summarize} 那一句给，落在首页那一行。
         * 一张卡塞不下第三个数，何况人下一步要的多半是点【打开文件】，不是读路径。
         *
         * <p>没存下的那几份不计进这里的件数：这个数指的是真的躺在磁盘上的东西，少了几份
         * 要单独说出来（同一句里的「另有 N 个没存下」）。
         */
        void delivered(Publish.Result published) {
            // 成功的收尾走这里、不走 end()：不清这一位的话，「前一条传输中」会永远为真，
            // 之后每一张邀请都被拒绝（2026-11-27 真机：第一条成功后，第二条点了没反应）。
            BUSY.set(false);
            if (!announced || settled) {
                return;
            }
            settled = true;
            if (cloud) {
                // 收完不是结束：人还要去打开那份东西。所以这张卡不自己消失，留到人按「完成」。
                // 第二行和发送完成那张说同一句话的形状：几份、多少字节。这里不写「100%」——
                // 完成这件事由标题说，而百分比在一张已经画满的轨道旁边是同一个意思说两遍。
                LiveCard.useCard(inviteId());
                LiveCard.finishWithActions(context, "接收完成",
                        published.entries().size() + " 个文件 · " + Explains.bytes(published.bytes()),
                        openAction(context, published));
                // 收完也想摊开：这张卡上【打开文件】是收尾那一步真正的按钮，而它在摊开的那一面
                // 才看得见。但摊不摊开不归应用管——应用侧没有这个开关，早先硬撬（Xposed 进
                // SystemUI 叫插件的 onExpand）会让这张卡在通知中心缺席、并且点空白区域收不
                // 回去。所以这里不补救，宁可让人点一下胶囊；要它自己开，去启用那个可选的模块。
                cloud = false;
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
                LiveCard.useCard(inviteId());
                LiveCard.finishWithLink(context, "接收完成", linkLine(offer),
                        openLink(context, offer.urls));
                cloud = false;
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
            // 失败的收尾也不走 end()：不清这一位，之后每张邀请都会被当成「前一条还在跑」
            // 而拒绝——真机现象是「传输已经结束了，再来一张还是被拒」（2026-11-27）。
            BUSY.set(false);
            if (cloud) {
                // 只挂【完成】，没有【重试】：重来一次要对面重新发，而对面那台手机不归我们叫醒。
                // 轨道停在真走到的那个数上（{@link #percent}），不拉满——失败画成成功，是这里
                // 最不能犯的错。
                LiveCard.useCard(inviteId());
                LiveCard.failedWithActions(context, "接收失败", message, percent, null);
                cloud = false;
            }
            settle();
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
            // 三种收尾里有两种是**完全安静**的：人按取消走撤卡（{@link #end}），没人应答也
            // 走撤卡，两条都不写日志；第三种失败也只由 {@code Listener} 那边先喊一句「连接
            // 断了」。所以「这次到底是谁断的」在没有这一行的时候是查不出来的：2026-11-19
            // 21:01–21:03 连收七次，六次停在 64 / 192 / 576 KiB，卡片上什么都不留、日志里也
            // 分不出「人按了取消」和「对面走了」——而这两件事在下一步该改哪儿上，答案相反。
            Log.i(TAG, "receive " + session + " ended "
                    + decision.ending()
                    + " at " + percent + "%");
            if (decision.cancelled()) {
                // 人自己按的【取消接收】：这不是失败，是**他要的**结果。
                //
                // 过去这里先看 accepted，于是「人点了接收、传到一半按了取消」照样走
                // {@code failed("连接意外断开")}，胶囊上弹一条说对面断了的红字——因果整个
                // 说反了：对面没有断，是我们把它掐了。取消只有一个下场：撤卡，不留话。
                end();
            } else if (decision.accepted()) {
                failed("连接意外断开");
            } else {
                // 人按了取消、或者一直没人应答——在这边是同一个收尾：撤卡，不留话。过去这两者
                // 靠事件里那个 phase 分（{@code "cancelled"} / {@code "failed"}），而那条路
                // 2026-11-19 已经清了，现在能分开它们的只有上面那一行日志。
                end();
            }
        }

        /** 人说收，或者说不收。两个表面最后都落到这里。 */
        void answer(boolean yes, String from) {
            decision.answer(yes, from);
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
            decision.cancel();
            // 先把屏幕上这张卡收掉，再去动连接。
            //
            // 昨晚那次顺序反了：写 400 之后要停一会儿（不停就是 RST，400 会跟着丢），而
            // 卡片要等读循环断开、走到 {@link #abandon} 才撤——于是「按了取消没反应，连点
            // 几次才消失」。撤卡是本地的事，与连接怎么收尾无关，先做掉。
            end();
            // 与 HangUp 里那一行配对：这一行是「广播到了、决定取消」，那一行是「RST 交给内核」。
            // 两行相减就是我们的反应时间；再和对面说失败的时刻比，才知道延迟在谁那儿。
            Log.i(TAG, "cancel requested at " + percent + "%");
            if (!decision.answered()) {
                // 人是从卡片上的取消进来的，所以来源记 card；日志里那一句要知道是谁按的。
                answer(false, "card");
                return;
            }
            abort();
        }

        /**
         * 把这条连接掐掉。**必须不在调用者线程上做**——{@link HangUp#now} 和
         * {@link HangUp#refuse} 都自己开线程，连同「那条读字节的线程怎么办」都在它们那里。
         *
         * <p>这里只留一件本地的事：{@code peer} 此刻可能还是握手前的裸 socket，也可能是
         * TLS 那一层。两种都行——两组断法在两边都成立，而取消只有一条路可走：把这条连接
         * 弄没，且用对面认得的语言。
         */
        private void abort() {
            // 已经在收（人点过接收）时走「半关+排空+干净关」：对面是苹果的**发送**实现，
            // 2026-11-24 四模式 A/B 实测它只认 FIN——任何响应字节（400/500）都会让它停在
            // 「正在发送」，RST 更是只换来静默重试。见 HangUp.refuse 的实测表。
            // 还在等人应答时走 RST —— 那一步 Rust 自己会回 401，这半条连接上没有别的语言。
            if (decision.accepted()) {
                HangUp.refuse(peer);
            } else {
                HangUp.now(peer);
            }
        }

        private void end() {
            BUSY.set(false);
            if (cloud) {
                // 没有收成就没有可留的东西：一张停在中间态、也没人能再点的常驻卡，比没有更糟。
                // 失败是唯一的例外，它走 {@link #failed}——那一句解释值得留在屏幕上几秒。
                LiveCard.drop(context);
                cloud = false;
            }
            settle();
        }

        private void settle() {
            // 只在说过话的那一次置位：一次都没报到人眼前的连接（握手、探测、设置读不出来）
            // 没有结果可覆盖，这一位对它没有意义。
            if (announced) {
                settled = true;
            }
            // 退表是无条件的，包括一次话都没说过的连接。握手、探测、设置读不出来的那些连接
            // 在界面上不该留下任何痕迹，但它们在这张表里各占一个位置——漏收的结果是这张表
            // 只增不减，而且每一条都指向一个早就关掉的 socket。
            ReceiveHost.forget(session);
        }

        /**
         * 邀请卡上的倒计时。每秒改写一次，直到有人回答、超时、或者这次接待收尾。
         *
         * <p>用的是 {@link #decided} 当节拍器，不是另建一个"停"标志：{@code await(1s)} 返回真
         * 就说明有人答了（接受或拒绝），直接退出；返回假就是又过了一秒，接着画。超时那一下
         * 也由它自然退出——最后一帧画到 1 秒就不会再画，不会出现「还有 0 秒」挂在屏幕上。
         *
         * <p>60 秒这条上限由 {@link Confirmation#decisionTimeout} 决定，这里只负责显示它，
         * 不自己算一遍：两处各写一个数，改了一处另一处就会说谎。
         */
        private boolean await(long timeoutMs) {
            return decision.await(timeoutMs);
        }
    }
}
