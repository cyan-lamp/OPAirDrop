package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有人要发东西过来时，问一次。
 *
 * <p># 为什么是通知，不是对话框
 *
 * <p>这一刻应用十有八九没有可见的页面——手机可能在桌上、屏幕黑着。从后台启动 Activity 在
 * Android 10 之后是被禁止的，而 {@code AlertDialog} 又必须有窗口。通知是唯一一个「不需要
 * 谁的许可就能出现在人眼前、并且能带按钮」的表面。流体云那张卡片接上之后，这里就是卡片
 * 不可用时的退路，消息内容不变。
 *
 * <p># 为什么答案要回到这里而不是就地处理
 *
 * <p>问的人和被问的人不在同一个线程上：{@code serve} 的那条线程停在 {@link #ask} 里等答案，
 * 而按钮的回调是广播，跑在主线程。中间这个 {@link Waiter} 就是那根线——一个闩、一个布尔值，
 * 别的一概不做。
 *
 * <p># 为什么键是 session，不是自增号
 *
 * <p>同一个问题现在有两个可能的所在：这一条通知，或者互传进程里那张卡片。它们各自在自己的
 * 进程、自己的线程上按下去，但必须松开同一个闩——否则人先在卡片上点了「接受」，通知还挂着
 * 等三十秒，超时之后把这次接收判成拒绝。两个进程只能靠一个字符串对齐，那就是 {@code session}。
 * 自增号只在本进程里有意义，所以这里改成按 {@code session} 索引，由 {@link ReceiveHost}
 * 产生并同时交给两边。
 *
 * <p># 为什么超时按拒绝算
 *
 * <p>没有回答不等于同意。三十秒不回答就收下一个没人看过的文件，比错过一次传输糟得多——
 * 后者对面可以再发一次，前者收下的东西没人认领。三十秒这个数比发送端自己的超时早一点，
 * 好让框在对面放弃之前就消失；晚一点就只剩「按了接受，那边早走了」。
 */
final class Confirmation {

    private static final String TAG = "OPAirDrop";

    /** 广播的动作：同意与拒绝。 */
    static final String ACCEPT = "com.oshare.airdrop.CONFIRM_ACCEPT";
    static final String DECLINE = "com.oshare.airdrop.CONFIRM_DECLINE";

    /**
     * 广播里带的键。
     *
     * <p>名字还叫 {@code id}——它从第一天起就是这个意思，改名只会让
     * {@link DecisionReceiver} 和已经装上的旧通知对不上。它现在装的是一个 session 字符串。
     */
    static final String EXTRA_ID = "id";

    private static final String CHANNEL = "confirm";

    /** 通知 id 的起点。同一个时刻可能不止一笔，靠编号错开。 */
    private static final int NOTIFICATION_BASE = 0x0A20;

    /** 等人回答多久的回落值。AirDrop 的发送端自己也有超时，比它早一点放手更礼貌。 */
    private static final long TIMEOUT_MS = 30_000;

    /**
     * 等人回答多久——表里是 {@code receive.decision_timeout_ms}，上面是它成为一行之前的数。
     *
     * <p>这个数进文案（{@link #parse}）、进等待（{@link #ask}），也进卡片那条路
     * （{@code ReceiveHost}）——一个数，三个地方，都在这里取，因为「通知上说三十秒、实际
     * 五秒就放弃」这种错只有分开取才做得出来。
     */
    static long decisionTimeout(Context context) {
        return Tuning.ms(context, "receive.decision_timeout_ms", TIMEOUT_MS);
    }

    /** 正在等的那些问题，键是 session。 */
    private static final Map<String, Waiter> OPEN = new ConcurrentHashMap<>();

    private static final AtomicInteger NEXT = new AtomicInteger();

    private Confirmation() {}

    /**
     * 一次邀请里人能读到的两句话。
     *
     * <p>三个表面（通知、卡片、以及将来的任何一个）说的是同一件事，就必须说同样的话。所以
     * 措辞不再散在 {@code post} 里，而是从摘要一次解析出来，谁要谁拿。{@code summary} 是库给
     * 的那一行：{@code "名字\t件数\t字节数\t链接数"}。
     */
    static final class Offer {
        /** 对面叫什么。它是「要不要收这个人的东西」的唯一判断依据。 */
        final String sender;

        /**
         * 这次要发过来的每一份：叫什么、多大。
         *
         * <p>空表有两个来源，而且都对：一次纯链接分享（{@code Items} 有、{@code Files}
         * 没有），或者对面是一个连文件名都不报的老发送端。两种情况下「哪几份」都是真的
         * 不知道，所以调用方说「文件信息未知」，而不是编一个。
         */
        final List<Item> files;

        /**
         * 这次要发过来几个链接。{@code Items} 非空的 offer 就是它。
         *
         * <p>和 {@link #files} 并列而不是折进去：链接不是文件，收下来也不会是一个文件。
         * 但「一个链接都没有」和「有链接、没有文件」对人是两件不同的事，所以它得单独留着，
         * 让摘要和日志都说得准——把链接分享写成「文件信息未知」，是把我们知道的事说成不知道。
         */
        final long links;

        private final String body;

        private Offer(String sender, String body, List<Item> files, long links) {
            this.sender = sender;
            this.body = body;
            this.files = files;
            this.links = links;
        }

        String title() {
            return sender + " 想给你发文件";
        }

        /** 正文说清几件、多大。 */
        String body() {
            return body;
        }
    }

    /**
     * 一封邀请里的其中一份。
     *
     * <p>和传输完成之后那份的区别只有一个：这里没有落点。还没收，当然还没存到哪儿。
     *
     * <p>{@code size} 为负是「对面没说」，不是「零字节」。文件夹条目从来不带字节数，而
     * 一份真的空文件是 0——这两个在话里必须说得不一样，所以用负数表示未知。
     */
    static final class Item {
        final String name;
        final long size;

        Item(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    /**
     * 把库给的那一行摘要解析成两句人话。
     *
     * <p>{@code timeoutMs} 是这次邀请的寿命（{@link #decisionTimeout}）。它进文案是因为
     * 那句话得跟实际到点时刻对得上：写死「三十秒」而设置里是五秒的话，人会在第 5 秒看见
     * 一张卡自己变成「已放弃」，而卡上刚刚还说三十秒。
     */
    static Offer parse(String summary, long timeoutMs) {
        String[] field = summary == null ? new String[0] : summary.split("\t", -1);
        String sender = field.length > 0 && !field[0].isBlank() ? field[0] : "附近的设备";
        long declared = count(field, 1);
        long bytes = Math.max(count(field, 2), 0);
        long links = Math.max(count(field, 3), 0);
        // 纯链接分享是一个 `Files` 为空、`Items` 非空的 offer。只说件数会把它写成「0 个
        // 文件」——一句「没东西可收」的话，而人正是在这一刻决定收不收。
        StringBuilder what = new StringBuilder();
        if (declared != 0 || links == 0) {
            what.append(declared < 0 ? "?" : Long.toString(declared)).append(" 个文件");
        }
        if (links > 0) {
            if (what.length() > 0) {
                what.append("、");
            }
            what.append(links).append(" 个链接");
        }
        // 「当作拒绝」和结果页那句「已放弃」是两句话说了两件事。人没看见这条邀请、或者看见了
        // 还没来得及伸手，都不等于他拒绝了——发送端拿到的是同一个 503，但屏幕上说什么由这里
        // 决定。到点之后那张卡写的是「已放弃」，所以在还没到点的时候就得是同一句话。
        return new Offer(sender, what + (bytes > 0 ? " · " + size(bytes) : "")
                + "，" + seconds(timeoutMs) + " 秒内没人应答就放弃。", entries(field, 4), links);
    }

    /** 一个超时写成整秒，给文案用。 */
    private static long seconds(long timeoutMs) {
        return Math.max(1, Math.round(timeoutMs / 1000.0));
    }

    /**
     * 第五个字段：{@code 名字<US>大小<RS>名字<US>大小…}。
     *
     * <p>没有这个字段、或者它是空的，都是一个空表。老库只给四个字段，而一次链接分享的
     * 第五个字段本来就该是空的——两种情形对这里的意思一样：哪几份，不知道。
     *
     * <p>名字里的分隔符由库那一侧就替换掉了（见 {@code summary_of}），所以这里按分隔符
     * 切开就是切对了，不需要再猜。
     */
    private static List<Item> entries(String[] field, int at) {
        if (field.length <= at || field[at].isEmpty()) {
            return Collections.emptyList();
        }
        List<Item> items = new ArrayList<>();
        for (String entry : field[at].split("\u001e", -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            String[] pair = entry.split("\u001f", -1);
            // 名字为空说明对面发来一个没有 FileName 的条目。给一个能读的占位，而不是让
            // 卡片上出现一个空白行——那看起来像渲染坏了，而不是像对面没说。
            String name = pair[0].isBlank() ? "文件" : pair[0];
            items.add(new Item(name, pair.length > 1 ? count(pair, 1) : -1));
        }
        return items;
    }

    /** 问一次，并阻塞到有人回答或超时。没有 session 可用时（老路径）自己起一个。 */
    static boolean ask(Context context, String summary) {
        return ask(context, "confirm-" + NEXT.incrementAndGet(), summary);
    }

    /**
     * 问一次，并阻塞到有人回答或超时。
     *
     * <p>会阻塞调用线程：调用它的是服务连接的那条线程，不是主线程。
     *
     * <p>{@code session} 是这个问题在两个进程里的同一个名字。同一个 session 再问一次会覆盖
     * 上一次挂着的通知，而不是叠一条新的——重问只该改内容，不该多出一个框。
     */
    static boolean ask(Context context, String session, String summary) {
        long timeoutMs = decisionTimeout(context);
        Offer offer = parse(summary, timeoutMs);
        Waiter waiter = new Waiter(session, offer,
                NOTIFICATION_BASE + (NEXT.incrementAndGet() % 32));
        Waiter previous = OPEN.put(session, waiter);
        if (previous != null) {
            // 上一次还挂着：让停在它上面的那条线程按拒绝走掉，别把同一个问题问两遍。
            previous.accepted = false;
            previous.latch.countDown();
        }
        long askedAt = System.currentTimeMillis();
        try {
            post(context, session, waiter.notification, offer);
            waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            Log.i(TAG, "confirm " + session + " " + (waiter.accepted ? "accepted" : "declined")
                    + " after " + (System.currentTimeMillis() - askedAt) + " ms");
            return waiter.accepted;
        } catch (InterruptedException interrupted) {
            // 接收端正在被关掉：按拒绝处理，并把中断还回去，让上层知道它该退出。
            //
            // 这一行也留着：服务端只有一条线程，中断标志一旦被置上就没人清，之后每一次
            // `await` 都会立刻抛出来——症状是「每个传输都被拒收」，而原因在这一行里。
            Log.i(TAG, "confirm " + session + " interrupted after "
                    + (System.currentTimeMillis() - askedAt) + " ms");
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException unavailable) {
            // 通知发不出去（渠道被禁用、系统拒收）：没有人会被问，所以不能收。
            //
            // 这一行是必须的，不是顺手加的：走这条路的结果是「每一次传输都被拒收」，
            // 而对端只会看到 503，本机什么也不显示。没有它，故障现场只剩一个「怎么发
            // 都发不过来」的抱怨。
            Log.w(TAG, "confirm " + session + " could not be shown: " + unavailable, unavailable);
            return false;
        } finally {
            OPEN.remove(session, waiter);
            cancel(context, waiter.notification);
        }
    }

    /**
     * 按钮的回调入口。重复回答、或者回答一个已经超时的问题，都不做任何事。
     *
     * <p>两条来路：通知上的按钮走 {@link DecisionReceiver}，卡片上的按钮由
     * {@link ReceiveHost} 送进来。它们带着同一串字符，所以这里不需要知道人在哪个表面上按的。
     */
    static void answer(String session, boolean accepted) {
        if (session == null) {
            return;
        }
        Waiter waiter = OPEN.remove(session);
        if (waiter == null) {
            return;
        }
        Log.i(TAG, "confirm " + session + (accepted ? " accepted" : " declined") + " by the person");
        waiter.accepted = accepted;
        waiter.latch.countDown();
    }

    /** 这个请求还在等吗。给测试和排查用。 */
    static boolean waiting(String session) {
        return session != null && OPEN.containsKey(session);
    }

    /** 一个等着被回答的问题。 */
    private static final class Waiter {
        final String session;
        final Offer offer;
        final int notification;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean accepted;

        Waiter(String session, Offer offer, int notification) {
            this.session = session;
            this.offer = offer;
            this.notification = notification;
        }
    }

    private static void post(Context context, String session, int notification, Offer offer) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            // 没有通知服务就是没有人会被问到，和发不出去是同一件事，但这里的现场更早。
            Log.w(TAG, "confirm " + session + ": no notification manager");
            return;
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "接收确认", NotificationManager.IMPORTANCE_HIGH);
            // 高优先级是必要的：这一条过期就作废，人得当场看见。
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(offer.title())
                .setContentText(offer.body())
                .setStyle(new Notification.BigTextStyle().bigText(offer.body()))
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null, "接受", action(context, session, true, notification)).build())
                .addAction(new Notification.Action.Builder(
                        null, "拒绝", action(context, session, false, notification)).build());
        manager.notify(notification, builder.build());
        Log.i(TAG, "confirm " + session + " posted as " + notification + ": "
                + offer.title() + " / " + offer.body());
    }

    /**
     * 「接收 / 拒绝」两个按钮的落点，给不是这条通知的另一个表面用（今天就是流体云那张卡）。
     *
     * <p>复用的是同一个 {@link DecisionReceiver} 与同一串 session：从哪个表面按下去，答案都
     * 落到同一个闩上，而那个闩在 {@code ReceiveHost} 手里。requestCode 固定成一对常量，是因为
     * 云端卡一次只有一张——按通知 id 派生反而会和通知那条路撞号。
     */
    static PendingIntent decision(Context context, String session, boolean accepted) {
        return action(context, session, accepted, accepted ? 0x0B01 : 0x0B02);
    }

    private static PendingIntent action(Context context, String session, boolean accepted,
                                        int notification) {
        Intent intent = new Intent(context, DecisionReceiver.class)
                .setAction(accepted ? ACCEPT : DECLINE)
                .putExtra(EXTRA_ID, session);
        // 编号进 requestCode：两个动作、多个并发请求，用同一个 requestCode 会互相覆盖。
        int requestCode = notification * 2 + (accepted ? 1 : 0);
        return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancel(Context context, int notification) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(notification);
        }
    }

    /** 摘要里第 {@code at} 个字段的值；缺字段或者不是数字都给 -1，让调用方自己决定说法。 */
    private static long count(String[] field, int at) {
        if (field.length <= at || field[at].isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(field[at].trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.US, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }
}
