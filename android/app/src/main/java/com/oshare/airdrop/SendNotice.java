package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 发送这件事在通知栏上的那一份。
 *
 * <p># 为什么要它在
 *
 * <p>发送页是一块会滑走的玻璃。人点了头像、对面还在犹豫的那十几秒里，他完全可能把面板推下去
 * 看别的东西——那时「发出去了没有」就只剩通知栏能回答。收的那一侧早就有这样一条（
 * {@link ReceiveService#progress}），发的一侧一直是空的：字节在走，界面上什么也不说。
 *
 * <p># 一条，不是一条条
 *
 * <p>固定 id、{@code setOnlyAlertOnce}：一次发送从头到尾只更新同一条通知。每个进度都新发一条
 * 会长出一串，而它们说的是同一件事。
 *
 * <p># 渠道另开
 *
 * <p>不和接收共用一个渠道：那条叫「接收」，静音是因为「我在收」不该打断人；发送是**人自己
 * 按下**的动作，它的结果值得一个默认级别的提醒。混在一起就只能取两者里更安静的那个。
 */
final class SendNotice {

    private static final String CHANNEL = "opairdrop_send";

    /**
     * 和接收侧错开：两个方向各占一个 id，同时发生（收一份、发一份）时不会互相顶掉。
     */
    private static final int ID = 0x4A11;

    private SendNotice() {}

    /** 已经开始，但还没有字节可数：连上了、等着对方接受。 */
    static void waiting(Context context, String peer, String line) {
        post(context, "发送到 " + peer, line, -1f, true);
    }

    /**
     * 正在走字节。
     *
     * <p>{@code fraction} 小于 0 表示对端没声明总数——那是「不知道」，不是「百分之零」，
     * 所以画不确定态而不是一条停在 0 的条。
     */
    static void progress(Context context, String peer, float fraction, String line) {
        post(context, "发送到 " + peer, line, fraction, true);
    }

    /** 结束了。留下一条能被划掉的结论，而不是一条永远挂着的进行中。 */
    static void finished(Context context, String title, String line) {
        post(context, title, line, -2f, false);
    }

    /**
     * @param fraction 小于 -1 表示「结束，不画进度条」；-1 表示不确定态；其余按比例画
     * @param ongoing  还在跑。结束之后这一条该能被划掉——人划掉它就是「我看过了」
     */
    private static void post(Context context, String title, String line,
                             float fraction, boolean ongoing) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        ensureChannel(manager);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(line)
                .setContentIntent(open(context))
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true);

        if (fraction <= -2f) {
            // 结束：不带进度条。留着满圈会读成「还在跑」。
        } else if (fraction < 0f) {
            builder.setProgress(0, 0, true);
        } else {
            // 千分数：setProgress 收 int，而字节数是 long。见接收侧同一个理由。
            builder.setProgress(1000, (int) Math.round(Math.min(1f, fraction) * 1000), false);
        }
        manager.notify(ID, builder.build());
    }

    private static PendingIntent open(Context context) {
        return PendingIntent.getActivity(
                context, 1, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void ensureChannel(NotificationManager manager) {
        if (manager.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "发送", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
