package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

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
 * 谁的许可就能出现在人眼前、并且能带按钮」的表面。等流体云那张卡片接上之后，这里就是卡片
 * 不可用时的退路，消息内容不变。
 *
 * <p># 为什么答案要回到这里而不是就地处理
 *
 * <p>问的人和被问的人不在同一个线程上：{@code serve} 的那条线程停在 {@link #ask} 里等答案，
 * 而按钮的回调是广播，跑在主线程。中间这个 {@link Waiter} 就是那根线——一个闩、一个布尔值，
 * 别的一概不做。
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

    /** 广播里带的请求编号。 */
    static final String EXTRA_ID = "id";

    private static final String CHANNEL = "confirm";

    /** 通知 id 的起点。同一个时刻可能不止一笔，靠编号错开。 */
    private static final int NOTIFICATION_BASE = 0x0A20;

    /** 等人回答多久。AirDrop 的发送端自己也有超时，比它早一点放手更礼貌。 */
    private static final long TIMEOUT_MS = 30_000;

    /** 正在等的那些问题，键是请求编号。 */
    private static final Map<String, Waiter> OPEN = new ConcurrentHashMap<>();

    private static final AtomicInteger NEXT = new AtomicInteger();

    private Confirmation() {}

    /** 一个等着被回答的问题。 */
    private static final class Waiter {
        final String summary;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean accepted;

        Waiter(String summary) {
            this.summary = summary;
        }
    }

    /**
     * 问一次，并阻塞到有人回答或超时。
     *
     * <p>会阻塞调用线程：调用它的是服务连接的那条线程，不是主线程。
     */
    static boolean ask(Context context, String summary) {
        String id = Integer.toString(NEXT.incrementAndGet());
        Waiter waiter = new Waiter(summary);
        OPEN.put(id, waiter);
        int notification = NOTIFICATION_BASE + (NEXT.get() % 32);
        long askedAt = System.currentTimeMillis();
        try {
            post(context, id, notification, summary);
            waiter.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Log.i(TAG, "confirm " + id + " " + (waiter.accepted ? "accepted" : "declined")
                    + " after " + (System.currentTimeMillis() - askedAt) + " ms");
            return waiter.accepted;
        } catch (InterruptedException interrupted) {
            // 接收端正在被关掉：按拒绝处理，并把中断还回去，让上层知道它该退出。
            //
            // 这一行也留着：服务端只有一条线程，中断标志一旦被置上就没人清，之后每一次
            // `await` 都会立刻抛出来——症状是「每个传输都被拒收」，而原因在这一行里。
            Log.i(TAG, "confirm " + id + " interrupted after "
                    + (System.currentTimeMillis() - askedAt) + " ms");
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException unavailable) {
            // 通知发不出去（渠道被禁用、系统拒收）：没有人会被问，所以不能收。
            //
            // 这一行是必须的，不是顺手加的：走这条路的结果是「每一次传输都被拒收」，
            // 而对端只会看到 503，本机什么也不显示。没有它，故障现场只剩一个「怎么发
            // 都发不过来」的抱怨。
            Log.w(TAG, "confirm " + id + " could not be shown: " + unavailable, unavailable);
            return false;
        } finally {
            OPEN.remove(id);
            cancel(context, notification);
        }
    }

    /** 按钮的回调入口。重复回答、或者回答一个已经超时的问题，都不做任何事。 */
    static void answer(String id, boolean accepted) {
        Waiter waiter = OPEN.remove(id);
        if (waiter == null) {
            return;
        }
        Log.i(TAG, "confirm " + id + (accepted ? " accepted" : " declined") + " by the person");
        waiter.accepted = accepted;
        waiter.latch.countDown();
    }

    /** 这个请求还在等吗。给测试和排查用。 */
    static boolean waiting(String id) {
        return OPEN.containsKey(id);
    }

    private static void post(Context context, String id, int notification, String summary) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            // 没有通知服务就是没有人会被问到，和发不出去是同一件事，但这里的现场更早。
            Log.w(TAG, "confirm " + id + ": no notification manager");
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
                .setContentTitle(title(summary))
                .setContentText(detail(summary))
                .setStyle(new Notification.BigTextStyle().bigText(detail(summary)))
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null, "接受", action(context, id, true, notification)).build())
                .addAction(new Notification.Action.Builder(
                        null, "拒绝", action(context, id, false, notification)).build());
        manager.notify(notification, builder.build());
        Log.i(TAG, "confirm " + id + " posted as " + notification + ": "
                + summary.replace('\t', ' '));
    }

    private static PendingIntent action(Context context, String id, boolean accepted, int notification) {
        Intent intent = new Intent(context, DecisionReceiver.class)
                .setAction(accepted ? ACCEPT : DECLINE)
                .putExtra(EXTRA_ID, id);
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

    /** 标题里放对方的名字：这是唯一一个「要不要收这个人的东西」的判断依据。 */
    private static String title(String summary) {
        String[] field = summary.split("\t", -1);
        String sender = field.length > 0 && !field[0].isBlank() ? field[0] : "附近的设备";
        return sender + " 想给你发文件";
    }

    /** 正文说清几件、多大。 */
    private static String detail(String summary) {
        String[] field = summary.split("\t", -1);
        long files = count(field, 1);
        long bytes = Math.max(count(field, 2), 0);
        long links = Math.max(count(field, 3), 0);
        // 纯链接分享是一个 `Files` 为空、`Items` 非空的 offer。只说件数会把它写成「0 个
        // 文件」——一句「没东西可收」的话，而人正是在这一刻决定收不收。
        StringBuilder what = new StringBuilder();
        if (files != 0 || links == 0) {
            what.append(files < 0 ? "?" : Long.toString(files)).append(" 个文件");
        }
        if (links > 0) {
            if (what.length() > 0) {
                what.append("、");
            }
            what.append(links).append(" 个链接");
        }
        return what + (bytes > 0 ? " · " + size(bytes) : "") + "，30 秒内不回答就当作拒绝。";
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
