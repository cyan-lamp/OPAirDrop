package com.oshare.airdrop;

import android.content.Context;
import android.os.Messenger;
import android.util.Log;

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
 * 这三件事现在有两个可能的所在：本应用自己的通知（{@link Confirmation} 与
 * {@link ReceiveService}），或者互传进程里那张原生卡片与弹窗（{@link OShareReceiveBridge}）。
 * 这个类是唯一知道「两个所在说的是同一件事」的地方：它产生事件，谁在听谁就画。
 *
 * <p># 为什么状态按 session 存在这里
 *
 * <p>卡片上的「接受」，最后必须落回停在 {@link Confirmation} 里等答案的那条线程，而卡片在
 * 另一个进程、另一个线程上。中间要穿过的是一条 Messenger 通道，它只能传字符串，所以两个
 * 进程得用同一个名字指同一件事——那就是 {@code session}。这个名字由这里产生，同时交给
 * {@link Confirmation#ask}，于是通知上的按钮和卡片上的按钮回答的是同一个闩。
 *
 * <p># 为什么状态码是这些数
 *
 * <p>1002 与 1006/1007 是互传那一侧的词汇（{@code OShareReceiveBridge} 用它区分「邀请」与
 * 「已经失败的」，这里沿用），中间两个是这个项目自己起的号。真正的判据是 {@code phase}：
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
     * 等人回答多久。
     *
     * <p>和 {@link Confirmation} 同一个数，而且是同一个理由：发送端自己有超时，比它早一点
     * 放手更礼貌。两条路用两个数字的话，同一次邀请在通知上和卡片上会有两个寿命。
     */
    private static final long DECISION_TIMEOUT_MS = 30_000;

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
     * <p>参数是「谁没了」而不是没有参数：这条通道现在走回环 TCP（见 {@link BridgeServer}），
     * 一条连接断开和另一条连接建立可以发生在同一个瞬间，无条件清空会把新的那条一起抹掉。
     */
    static void detach(Messenger expected) {
        synchronized (LOCK) {
            if (ui == expected) {
                ui = null;
            }
        }
    }

    /** 现在有人能画吗。决定这一次是问卡片还是问通知。 */
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

    /** 人按了接受或拒绝。送进等答案的那个闩里。 */
    static boolean answer(String session, boolean accepted) {
        Attempt attempt = find(session);
        if (attempt == null) {
            // 表里没有这一次接收，但通知可能还挂着：这张表在收尾时就退了，而通知要等
            // {@link Confirmation} 那边超时或回答才撤。人在这段缝里按下去，仍然该被听见——
            // 所以退到那个闩上再试一次，而不是当作「问题不存在」。
            Confirmation.answer(session, accepted);
            return false;
        }
        attempt.answer(accepted);
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

    private static Attempt find(String session) {
        if (session == null || session.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            return LIVE.get(session);
        }
    }

    private static void emit(JSONObject event) {
        Messenger target;
        synchronized (LOCK) {
            target = ui;
        }
        if (target == null) {
            return;
        }
        BridgeMessenger.sendEvent(target, event);
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

        /** 这条连接。人说取消时关掉它，读字节的那条线程会从这里出来。 */
        private final Socket peer;

        /** 人按过取消。收尾时用来说「已取消」而不是「连接已断开」。 */
        private volatile boolean cancelled;

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

        /** 有没有对外说过话。没说过就不用收尾。 */
        private boolean announced;

        /** 结果已经说过了。收尾不再覆盖它。 */
        private boolean settled;

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
            Confirmation.Offer offer = Confirmation.parse(summary);
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
            boolean attached = attached();
            // 两条路对发送端是同一个 30 秒，对人却是两种界面（卡片 / 本应用通知）。哪一条被走到
            // 只有这里说得出来，而「屏幕上什么都没出现」这半句排查永远从这里开始。
            Log.i(TAG, "invitation " + session + " from " + sender + " attached=" + attached
                    + " files=" + (offer.files.isEmpty() ? "none" : offer.files.size())
                    // 链接分享没有文件，但它不是「什么都没报」。过去这一行在这种情况下写
                    // `files=unreported`，读日志的人会去找一个不存在的解析失败。
                    + " links=" + offer.links);
            long askedAt = System.currentTimeMillis();
            boolean wanted = attached
                    ? await(DECISION_TIMEOUT_MS)
                    : Confirmation.ask(context, session, summary);
            // 「人说了不行」和「一直没人理」对发送端是同一个 503，对人却是两句不同的话。
            // 这一行把两者分开，也把耗时写下来——三十秒这个数只有在拒绝之后才看得见，
            // 而人看到的是一张很久之前就弹出来的卡片。
            Log.i(TAG, "invitation " + session
                    + (wanted ? " accepted" : answered ? " refused" : " unanswered")
                    + " after " + (System.currentTimeMillis() - askedAt) + " ms via "
                    + (attached ? "card" : "notification"));
            if (!wanted) {
                // 拒绝的话必须说清是谁拒的。过去两种情况共用「已拒绝」，于是超时被读成
                // 「我没点它，它自己拒的」——那不是人看错，是我们没写明白。
                // `expired` 是标题表里本来就有的一个相位（「邀请已过期」），只是从来没有
                // 人发过它：状态码两边都是 1004（对发送端都是同一个 503），区别只在标题。
                end(answered ? "rejected" : "expired", REJECTED, answered
                        ? "已拒绝"
                        : (DECISION_TIMEOUT_MS / 1000) + " 秒内没人应答，已放弃");
            } else {
                // 答过之后，最后那一帧不能再留在「邀请」上。
                //
                // 界面晚到一步时会按最后那一帧重放（{@link #snapshot}），而这条连接马上就要
                // 去读 /Upload 了。中间这一段如果不说话，重放出来的就是那张已经答过的邀请——
                // 于是同一个问题被问第二遍，这一次没人会答，三十秒后屏幕上出现「已拒绝」。
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
                int percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
                emit(newEvent("receiving", RECEIVING).put("progress", percent));
            } catch (JSONException impossible) {
                Log.w(TAG, "receive progress unavailable", impossible);
            }
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

        /** 没收到，或者收到了没存下来。{@code message} 是给人看的那句话。 */
        void failed(String message) {
            end("failed", FAILED, message);
        }

        /**
         * 这条连接结束了，但它没有结果可报。
         *
         * <p>只收自己说过话的那一次。一次都没说过话的连接——握手的、探测的、设置读不出来
         * 的——不该在卡片上留下任何痕迹。
         */
        void abandon() {
            if (cancelled) {
                end("cancelled", CANCELLED, "已取消");
            } else {
                end("failed", FAILED, "连接已断开");
            }
        }

        /** 人说收，或者说不收。两个表面最后都落到这里。 */
        void answer(boolean yes) {
            accepted = yes;
            answered = true;
            decided.countDown();
            // 通知那条路可能正在等：通知按钮和卡片按钮回答的是同一件事，所以两边都叫醒。
            // 没有人在等的时候它什么也不做。
            Confirmation.answer(session, yes);
        }

        /**
         * 人说取消。
         *
         * <p>还在等人回答的时候，取消就是拒绝；已经在收的时候，取消是把连接关掉——那条
         * 读字节的线程会从 read 里出来，走的是和对端走掉完全一样的一条路。
         */
        void cancel() {
            cancelled = true;
            if (decided.getCount() > 0) {
                answer(false);
                return;
            }
            try {
                peer.close();
            } catch (IOException alreadyGone) {
                // 已经断了：那条线程自己会走到收尾。
            }
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
