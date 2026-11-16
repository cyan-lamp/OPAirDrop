package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * 我们自己的那张云：一条系统绘制的实时通知（Android 16 起的 Live Updates）。
 *
 * <p># 为什么不抄互传的卡片
 *
 * <p>互传那张卡是它自己的窗口，画在它的进程里。要用它，就得先让互传活着、把它叫醒、再钩住
 * 它画卡的那一层——这条链上有三处会坏（进程在不在、binder 通不通、钩子对不对得上这一版
 * ROM），而且**没有一处会告诉我们它到底画出来了没有**。2026-11-16 13:14 就是这样：`attached`
 * 为真、事件也送到了互传进程，屏幕上什么都没有，三十秒后按时过期。
 *
 * <p>Android 16 把这件事收进了系统：一条「进度型」的常驻通知 + {@code setRequestPromotedOngoing}，
 * 系统就把它提升为实时通知，ColorOS 把它画在流体云那个位置。画的人是系统，所以我们不需要知道
 * 它是怎么画的，也不需要它在不在——通知发出去就是发出去了。
 *
 * <p># 它和普通通知的关系
 *
 * <p>同一个通知 id、同一个渠道。系统愿意提升，它出现在状态栏那颗胶囊里；不愿意（用户关了、
 * 这台机器不支持），它仍然是一条带着进度的常驻通知，人照样点得到。**所以这条路没有失败态**：
 * 最差也等于今天兜底的那条通知。
 *
 * <p># 什么时候会拿不到
 *
 * <p>{@link NotificationManager#canPostPromotedNotifications()} 为假。那是系统层面的开关
 * （用户可以在设置里关掉某个应用的实时通知），调用方据此决定要不要换一套观感，而不是猜。
 */
final class LiveCard {

    private static final String TAG = "OPAirDrop";

    /** 与接收确认分开的一条渠道：这一条不该响，也不该被「接收确认」的静音连带。 */
    private static final String CHANNEL = "live";

    /** 同一个位置、高重要性的一条渠道，只为回答「胶囊不展开是不是因为重要性太低」。 */
    private static final String CHANNEL_LOUD = "live_loud";

    /** 一张卡一个 id：同时只有一个传输在走，第二个会覆盖第一个，这正是我们要的。 */
    private static final int ID = 0x0B00;

    /** 抬头那一条用另一个 id：它和云里的卡是两条通知，不能互相覆盖。 */
    private static final int PLAIN_ID = 0x0B10;

    /** 试验用的第三条：见 {@link #inviteBanner}。 */
    private static final int BANNER_ID = 0x0B20;
    private static final String CHANNEL_BANNER = "live_banner";

    /** 最近一次画卡用的 Context。{@link #dropLater} 需要一个，而它没有参数可以拿。 */
    private static Context lastContext;

    /** 撤掉那张已完成卡片用的计时器（见 {@link #finishAndDismiss}）。 */
    private static final android.os.Handler LATER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private LiveCard() {}

    /** 这张机器此刻允许我们发实时通知没有。 */
    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        return manager.canPostPromotedNotifications();
    }

    /**
     * 画一张在走的卡。
     *
     * @param percent 0–100；负数表示「不知道进度」，这时卡片画成不确定态而不是停在 0%
     */
    static void update(Context context, String title, String shortText, int percent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        channel(manager, CHANNEL);
        lastContext = context.getApplicationContext();

        Notification.ProgressStyle style = new Notification.ProgressStyle()
                // 形状跟着进度走：不设这个，系统画的是一段静态装饰而不是一条在走的轨道。
                .setStyledByProgress(true)
                .setProgressIndeterminate(percent < 0);
        if (percent >= 0) {
            style.setProgress(Math.min(100, percent));
        }

        Notification card = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(shortText)
                // 胶囊里那一行字。系统只在有它的时候才把这张卡提升到状态栏那个位置。
                .setShortCriticalText(shortText)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setRequestPromotedOngoing(true)
                .build();
        manager.notify(ID, card);
        Log.i(TAG, "live card: " + title + " · " + shortText + " · " + percent + "%");
    }

    /** 收尾：进度拉满，短句换成结果，仍然挂着让系统放完那一段动画。 */
    static void finish(Context context, String title, String shortText) {
        update(context, title, shortText, 100);
    }

    /**
     * 收尾，并在片刻之后撤掉。
     *
     * <p>为什么要撤：这张卡是常驻（{@code ONGOING}）的，一张「已完成」的常驻卡会一直占着胶囊
     * 那个位置——而那个位置是给人的，不是给历史的。留几秒是为了让完成那一下被看见。
     */
    static void finishAndDismiss(Context context, String title, String shortText, long afterMs) {
        update(context, title, shortText, 100);
        LATER.postDelayed(() -> drop(context), Math.max(1000, afterMs));
    }

    /**
     * 一张「要不要收」的卡：同一个胶囊位置，展开后带两个按钮。
     *
     * <p>这一条测的是这件事：胶囊自己放不下按钮（它是一颗 chip），所以按钮只能出现在**展开
     * 之后**。点胶囊究竟会就地展开成一张带按钮的大卡，还是只是把通知栏拉下来，决定了「接收/
     * 拒绝」能不能一步点到——这是这个方案唯一的交互未知数，所以它必须先在真机上看一眼。
     *
     * @param accept 按「接受」时发的广播；调用方负责把它接到自己的决定上
     * @param refuse 同上，按「拒绝」
     */
    static void invite(Context context, String title, String shortText,
                       android.app.PendingIntent accept, android.app.PendingIntent refuse) {
        // 高重要性，不是低：**提升成实时通知并不会让它自己出现**，会不会抬头由渠道重要性决定。
        // 2026-11-16 实测：同一条提升通知，低重要性渠道只有一颗 chip；换成高重要性渠道后它
        // 自动展开浮在别的应用之上（前提是应用的通知设置里「横幅」勾上，这一项不在本应用
        // 的控制范围内）。
        invite(context, CHANNEL_LOUD, title, shortText, accept, refuse);
    }

    /**
     * 邀请做成一**条普通的高重要性通知**：不请求提升，于是它走系统的横幅。
     *
     * <p>2026-11-16 的分工是这样定下来的：流体云那颗胶囊**只有点了才展开**，而且页面上的小字
     * 明写着「实时活动…不受通知设置影响」——应用的通知设置里那个「横幅」管的是普通通知。
     * 既然两条路的开关是分开的，就让它们各做各擅长的事：**要人马上做决定的用横幅**（它自己
     * 浮出来，视图归我们，按钮多高都行），**显示进度的用云**（胶囊里那颗，不打扰人）。
     */
    static void inviteBanner(Context context, String title, String shortText,
                             android.app.PendingIntent accept, android.app.PendingIntent refuse) {
        banner(context, title, shortText, accept, refuse, null);
    }

    /** 带自定义视图的那一版：按钮 64dp，约系统默认高度的 1.5 倍。 */
    static void inviteBannerTall(Context context, String title, String shortText,
                                 android.app.PendingIntent accept, android.app.PendingIntent refuse,
                                 android.widget.RemoteViews view) {
        banner(context, title, shortText, accept, refuse, view);
    }

    private static void banner(Context context, String title, String shortText,
                               android.app.PendingIntent accept, android.app.PendingIntent refuse,
                               android.widget.RemoteViews view) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        channel(manager, CHANNEL_LOUD);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_LOUD)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(shortText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "拒绝", refuse).build())
                .addAction(new Notification.Action.Builder(null, "接收", accept).build());
        if (view != null) {
            builder.setCustomContentView(view).setCustomHeadsUpContentView(view);
        }
        manager.notify(PLAIN_ID, builder.build());
        Log.i(TAG, "banner invite" + (view == null ? "" : " (custom view)") + ": " + title
                + " · " + shortText);
    }

    /** 撤掉横幅那一条。 */
    static void dropBanner(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(PLAIN_ID);
        }
    }

    private static void invite(Context context, String channel, String title, String shortText,
                               android.app.PendingIntent accept, android.app.PendingIntent refuse) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        channel(manager, channel);
        Notification card = new Notification.Builder(context, channel)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(shortText)
                .setShortCriticalText(shortText)
                // 邀请阶段**不画进度条**：这时没有任何字节在走，一条轨道只会让人以为传输已经开始。
                // 轨道从「点了接收」那一刻起才该出现（{@link #update} 会给这张卡换上 ProgressStyle）。
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setRequestPromotedOngoing(true)
                // 先「拒绝」后「接收」：加进来的顺序就是左右顺序，而主导动作该在右边——
                // 这是拿手机的那只手够得最舒服的一侧，也是「接受」与「取消」惯常的排布。
                .addAction(new Notification.Action.Builder(null, "拒绝", refuse).build())
                .addAction(new Notification.Action.Builder(null, "接收", accept).build())
                .build();
        manager.notify(ID, card);
        Log.i(TAG, "live invite (" + channel + "): " + title + " · " + shortText);
    }

    /**
     * 收完的那张卡：进度拉满，挂【打开文件】【完成】，**不自己消失**。
     *
     * <p>为什么不让它自动关：传输结束不是这件事的结束，人还要去打开那份文件。一张自己消失的
     * 完成提示，等于把「东西在哪」这个问题留给了人去翻相册。留到他按「完成」为止，才是把最后
     * 一步交还在他手上。
     *
     * @param open 打开那份文件的 intent；没有可打开的（比如只有链接）时传 {@code null}，
     *             那就只挂「完成」
     */
    static void finishWithActions(Context context, String title, String shortText,
                                  android.app.PendingIntent open) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        channel(manager, CHANNEL);
        lastContext = context.getApplicationContext();

        Notification.ProgressStyle style = new Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(100);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(shortText)
                .setShortCriticalText(shortText)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setRequestPromotedOngoing(true);
        if (open != null) {
            builder.addAction(new Notification.Action.Builder(null, "打开文件", open).build());
        }
        builder.addAction(new Notification.Action.Builder(null, "完成",
                android.app.PendingIntent.getBroadcast(context, 0x0B04,
                        new android.content.Intent(context, CardDismiss.class),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE)).build());
        manager.notify(ID, builder.build());
        Log.i(TAG, "live done: " + title + " · " + shortText);
    }

    /** 过一会儿自己撤掉：用于「失败」这种看一眼就够、又不该一直占着位置的状态。 */
    static void dropLater(long afterMs) {
        Context context = lastContext;
        if (context == null) {
            return;
        }
        LATER.postDelayed(() -> drop(context), Math.max(1000, afterMs));
    }

    /** 撤掉。传 {@code null} 之外什么都不需要。 */
    static void drop(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(ID);
        }
    }

    private static void channel(NotificationManager manager, String id) {
        if (manager.getNotificationChannel(id) != null) {
            return;
        }
        boolean loud = CHANNEL_LOUD.equals(id);
        NotificationChannel channel = new NotificationChannel(id,
                loud ? "传输邀请" : "传输进度",
                loud ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW);
        // 高重要性是为了抬头（heads-up），不是为了让手机响：邀请要人看见，不该吓人一跳。
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
