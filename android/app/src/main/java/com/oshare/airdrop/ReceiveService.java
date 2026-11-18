package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 接收端的前台身份：让「这十分钟里可以被找到」是一句能兑现的话。
 *
 * <p># 为什么非要有它
 *
 * <p>接收端是一个守护线程，线程活在进程里。应用没有页面、也没有前台服务时，这个进程就是系统
 * 随时可以回收的缓存进程——屏幕一黑、内存一紧，端口就没了，而对面的手机上看到的是「这个设备
 * 不可用」。旧版踩过同一条线：关掉 Activity 大约九十秒后，ColorOS 用
 * {@code Stopping service due to app idle} 收走了它。
 *
 * <p># 为什么类型是 connectedDevice
 *
 * <p>前台服务必须申报类型。这里做的是与外部设备直接通信，{@code connectedDevice} 就是这一类；
 * {@code dataSync} 也说得通，但它自带时限，而「十分钟窗口」正好会撞上那个时限——用一个有期限的
 * 类型去实现一个有期限的功能，到期时说不清是谁先放弃的。
 *
 * <p># 它不决定开不开
 *
 * <p>该不该收由 {@link #listening} 判断（跟互传，或人手动开的）。这个服务只负责在「该收」
 * 的时候把进程钉住，在「不该收」的时候退出。两份判断就是两个真相，所以这里一句 if 都没有，
 * 只有 {@link #listening} 这一个谓词。
 */
public final class ReceiveService extends Service {

    private static final String TAG = "OPAirDrop";

    /** 通知 id。固定值：这个服务只有一条通知，它表达的是「同一个状态」。 */
    private static final int NOTIFICATION = 0x0A1D;

    private static final String CHANNEL = "receive";

    /**
     * 手动开关的位置。
     *
     * <p>「已经在收」不能当作「该收」的理由：窗口打开时接收端本来就在收，于是窗口一到期的
     * 判断会看见「还在收」而继续收下去——窗口从此自我续命，永远不会关。这是真机上量到的
     * 第一个 bug。手动打开的接收端要另有一个凭据，这个文件就是那个凭据。
     */
    private static final String PREFERENCES = "opairdrop";

    private static final String MANUAL = "receive.manual";

    /** 这次「开」到什么时刻为止（毫秒时间戳）。0 表示一直开。 */
    private static final String UNTIL = "receive.until";

    /** 限时接收的收摊计时器。 */
    private static final android.os.Handler CLOSER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * 这条通知现在挂在通知栏上。
     *
     * <p>{@link StackKeeper} 会从它自己的线程上要求改那句话，而改的办法是
     * {@link NotificationManager#notify}——那条通知不存在的时候发它是「凭空造一条」，所以要有
     * 一位记着 {@link #startForeground} 有没有真的发生过。它在 {@link #onDestroy} 里落回去，
     * 于是停止之后再来的更新什么也不做。
     */
    private static volatile boolean shown;

    /** 人手动开了接收端没有。 */
    static boolean manual(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(MANUAL, false);
    }

    /** 记下（或撤掉）手动开的凭据。磁贴与功能页那个开关是写者。 */
    static void setManual(Context context, boolean on) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(MANUAL, on).putLong(UNTIL, 0).apply();
    }

    /**
     * 开接收。{@code minutes <= 0} 表示一直开（磁贴走这条）；正数留给需要限时的调用方。
     *
     * <p>期限那段原来服务于磁贴面板里的「iPhone（10 分钟）」。面板已经撤掉，磁贴现在是一个纯粹
     * 的开关——给 {@code 0}，开了就一直收、关了就停，不再有会自己到期的中间态。
     */
    static void enable(Context context, long minutes) {
        long until = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(MANUAL, true).putLong(UNTIL, until).apply();
        apply(context);
    }

    /** 剩下的毫秒数；0 表示一直开；负数表示已经过期。 */
    static long remaining(Context context) {
        long until = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getLong(UNTIL, 0);
        return until == 0 ? 0 : until - System.currentTimeMillis();
    }

    /**
     * 该不该收：只看那一个开关。
     *
     * <p>2026-11-16 起这条判据不再读互传的「所有人（10 分钟）」：那个窗口是别人的计时器，十分钟
     * 一到就自己关掉，而我们的接收端会跟着安静地停——屏幕上没有任何说法，人只会觉得"又不好使
     * 了"。现在只有一个凭据：{@link #manual}，由控制中心那块【AirDrop】磁贴（和功能页那个开关）
     * 写。开关开着就收，关掉就停，没有第三种状态。
     */
    static boolean listening(Context context) {
        if (!manual(context)) {
            return false;
        }
        long until = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getLong(UNTIL, 0);
        return until == 0 || System.currentTimeMillis() < until;
    }

    /** 把进程状态对齐到 {@link #listening}。幂等，可以在任何线程调用。 */
    static void apply(Context context) {
        if (listening(context)) {
            start(context);
        } else {
            stop(context);
        }
    }

    /** 把进程状态对齐到那块磁贴的开关。幂等，可以在任何线程调用。 */
    static void follow(Context context) {
        apply(context.getApplicationContext());
    }

    private static void start(Context context) {
        Intent intent = new Intent(context, ReceiveService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException refused) {
            // Android 12 起，应用在后台时启动前台服务会被拒（ForegroundServiceStartNotAllowed）。
            // 这不是错误而是系统的取舍：磁贴那一下会带一个几秒的允许窗口，而被拒时人还在
            // 通知栏里，界面上的开关会如实显示「没开」——所以这里只记一行。
            Log.w(TAG, "前台服务没能启动：" + refused);
        }
    }

    private static void stop(Context context) {
        try {
            context.stopService(new Intent(context, ReceiveService.class));
        } catch (RuntimeException ignored) {
            // 已经不在跑了。
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!listening(this)) {
            // 走到这里说明对齐和启动之间状态又变了（窗口刚好到期）。直接退，别留一条
            // 「正在接收」的通知去解释一件没发生的事。
            //
            // 但「直接退」之前必须先 startForeground：这个服务是被 startForegroundService()
            // 叫起来的，系统从那以后就在等一次 startForeground，五秒等不到就抛
            // ForegroundServiceDidNotStartInTimeException，而这条异常是主线程上的未捕获
            // 异常——整个进程被杀。真机上量到过一次：从外面拉起服务时恰好不满足条件，应用
            // 直接消失，日志里只剩 TUN_READY 之后的空白。所以先挂上再摘掉，那几十毫秒的
            // 通知一闪是这个 API 的入场费，不是可以省掉的。
            startForeground(NOTIFICATION, notification(this));
            stopForeground(STOP_FOREGROUND_REMOVE);
            shown = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification(this));
        shown = true;
        Listener.of(this).start();
        // 应用层到这里就齐了；内核层（模块、awdld、awdltun0）由它去对齐，理由见 StackKeeper：
        // 磁贴只碰得到这一层，而这正是「从磁贴开、不打开应用」时 iPhone 看不见这台机器的原因。
        StackKeeper.watch(this);
        // 限时开着的，到点自己收摊：那个期限是我们自己的承诺，不该等某个观察者来兑现。
        long left = remaining(this);
        if (left != 0) {
            CLOSER.removeCallbacksAndMessages(null);
            CLOSER.postDelayed(() -> {
                if (!listening(this)) {
                    stop(this);
                }
            }, Math.max(1000, left));
        }
        // 不再叫互传的门：流体云是我们自己发的一条实时通知（LiveCard），系统负责画它，
        // 谁的进程在不在都与它无关。
        // 不 sticky：进程被杀之后由 App/面板重新对齐，而不是让系统在一个没人知道的时刻
        // 把它拉起来——那种重启没有窗口、没有理由，只有一条通知。
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // 顺序是有讲究的：先叫看护者停手，再关广播，最后把这个进程的前台身份收掉。
        //
        // unwatch 只置一个标志（理由见 StackKeeper#unwatch）：它此刻可能正卡在那二十五秒的
        // 驱动交换里，而把做到一半的交换砍掉是这台机器上唯一能把 Wi-Fi 弄丢的操作。所以「停」
        // 是「这一轮跑完别看」的意思，它自己会退。
        StackKeeper.unwatch();
        Listener.of(this).stop();
        shown = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 重画通知上那句话，不改变这条通知在不在。
     *
     * <p>唯一的调用方是 {@link StackKeeper}：它会在自己那条线程上发现「链路没了，正在重建」
     * 或者「建不起来，因为××」，而这两种情况人只有可能在通知栏上看到——应用没打开（这本来
     * 就是磁贴那条路径的前提）。
     *
     * <p>{@link #shown} 是那道闸：{@code notify} 对一条不存在的通知是「凭空造一条」，会让
     * 一个已经停掉的接收端在通知栏上留一条假的「正在接收」。所以这里不判断该不该收，只判断
     * 这条通知此刻在不在栏上。
     */
    static void refresh(Context context) {
        if (!shown) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.notify(NOTIFICATION, notification(context.getApplicationContext()));
    }

    /**
     * 造这条通知。
     *
     * <p>静态的，因为它有第二个调用方 {@link #refresh}——那一位手里只有一个 {@code Context}，
     * 没有 Service 实例，而这正是它存在的前提（服务在跑的时候才有通知要改）。
     */
    private static Notification notification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            ensureChannel(manager);
        }

        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("正在接收")
                .setContentText(line())
                .setContentIntent(open(context))
                .setOngoing(true)
                .build();
    }

    /** 点通知去哪儿：主界面。服务那条与传输那条共用同一个 {@link PendingIntent}。 */
    private static PendingIntent open(Context context) {
        return PendingIntent.getActivity(
                context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * 这条渠道，第一次用到时建。**只剩服务身份一条通知**发在它上面。
     *
     * <p>低优先级、无声音、无角标：这条渠道上的东西不是请人做什么的请求，是「我在这儿」的
     * 凭据。传输的进度与结果不走这里——它们走流体云那张卡（{@link LiveCard}），一处一句，
     * 不再有「同一件事在通知栏和胶囊里各说一遍、数字还对不上」的第二种说法。
     */
    private static void ensureChannel(NotificationManager manager) {
        if (manager.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "接收", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    /**
     * 通知上那句话：一切正常就说端口，链路有问题就说那件事。
     *
     * <p>这句话只说我们自己的事：磁贴开着，接收端就在跑。以前它要跟着互传的措辞走
     * （「所有人（10 分钟）」），那是别人的窗口，已经不再决定我们收不收。
     *
     * <p>「正在监听 8770 · 附近设备可发现」在链路没建起来时是**假的**：8770 确实在听，但
     * iPhone 的分享面板里什么都没有，而人看着这句话只会去对面手机上找问题。所以
     * {@link StackKeeper#note()} 不空的时候它顶掉这一整句——那是唯一能解释「开着却看不见」
     * 的地方，而这条路径上应用根本没打开，通知栏就是唯一的屏幕。
     */
    private static String line() {
        String trouble = StackKeeper.note();
        return trouble.isEmpty() ? "正在监听 " + Listener.PORT + " · 附近设备可发现" : trouble;
    }
}
