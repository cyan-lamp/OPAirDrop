package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

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
     * 传输进度通知的 id。
     *
     * <p>另起一段而不是复用 {@link #NOTIFICATION}：那一条是服务的身份，它整个生命周期都
     * 得挂着、都得是 ongoing；进度条只跟一次传输同生共死。合成一条的话，服务「我在这儿」
     * 的凭据就要看一次传输有没有发生过，而人把收完的那条结果划掉，就等于划掉了前台服务
     * 的证明。id 空间本来也分得开：{@code 0x0A1D} 是服务，{@code 0x0A20}–{@code 0x0A3F}
     * 是 {@link Confirmation} 的确认框，这条从 {@code 0x0A40} 起。
     *
     * <p>固定值而不是像确认框那样分槽：接收端一次只服务一个对端（见 {@link Listener} 的
     * 类注释「Why one thread and not a pool」），所以同一个时刻最多只有一条传输，不需要
     * 第二个号。
     */
    private static final int TRANSFER = 0x0A40;

    /**
     * 手动开关的位置。
     *
     * <p>「已经在收」不能当作「该收」的理由：窗口打开时接收端本来就在收，于是窗口一到期的
     * 判断会看见「还在收」而继续收下去——窗口从此自我续命，永远不会关。这是真机上量到的
     * 第一个 bug。手动打开的接收端要另有一个凭据，这个文件就是那个凭据。
     */
    private static final String PREFERENCES = "opairdrop";

    private static final String MANUAL = "receive.manual";

    /** 人手动开了接收端没有。 */
    static boolean manual(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(MANUAL, false);
    }

    /** 记下（或撤掉）手动开的凭据。功能页那个开关是唯一的写者。 */
    static void setManual(Context context, boolean on) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(MANUAL, on).apply();
    }

    /**
     * 该不该收：窗口开着，或者平时跟着互传且互传是「所有人」，或者有人已经在收。
     *
     * <p>第二条是给功能页那个手动开关用的：手动打开接收端时既没有窗口也没有互传的许可，
     * 但只要那个开关是自己拨开的，服务的职责就是把它钉住。少了它，手动开的接收端在离开应用
     * 九十秒后就会安静地停掉；而把「线程在跑」直接当成理由又会让窗口永远关不掉——两件事都要
     * 成立，所以凭据写在 {@link #manual} 里，而不是看线程。
     */
    static boolean listening(Context context) {
        if (manual(context) && Listener.of(context).running()) {
            return true;
        }
        return Oshare.following(context) && Oshare.everyone(context);
    }

    /** 把进程状态对齐到 {@link #listening}。幂等，可以在任何线程调用。 */
    static void apply(Context context) {
        if (listening(context)) {
            start(context);
        } else {
            stop(context);
        }
    }

    /** 观察互传的接收范围，并立刻对齐一次。跟着进程走，只注册一次。 */
    static void follow(Context context) {
        Context app = context.getApplicationContext();
        synchronized (ReceiveService.class) {
            if (!watching) {
                android.database.ContentObserver observer =
                        new android.database.ContentObserver(new android.os.Handler(
                                android.os.Looper.getMainLooper())) {
                            @Override
                            public void onChange(boolean selfChange) {
                                apply(app);
                            }
                        };
                app.getContentResolver().registerContentObserver(
                        android.provider.Settings.System.getUriFor(Oshare.KEY), false, observer);
                watching = true;
            }
        }
        apply(app);
    }

    private static boolean watching;

    private static void start(Context context) {
        Intent intent = new Intent(context, ReceiveService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException refused) {
            // Android 12 起，应用在后台时启动前台服务会被拒（ForegroundServiceStartNotAllowed）。
            // 这不是错误而是系统的取舍：这时候人来按一下应用，{@code App.onCreate} 会再对齐一次。
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
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification());
        Listener.of(this).start();
        // 不 sticky：进程被杀之后由 App/面板重新对齐，而不是让系统在一个没人知道的时刻
        // 把它拉起来——那种重启没有窗口、没有理由，只有一条通知。
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Listener.of(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            ensureChannel(manager);
        }

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("正在接收")
                .setContentText(line())
                .setContentIntent(open(this))
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
     * 这条渠道，第一次用到时建。服务身份与传输进度都发在它上面。
     *
     * <p>低优先级、无声音、无角标：这条渠道上的东西都不是请人做什么的请求，一个是「我
     * 在这儿」的凭据，一个是正在走的字节数。代价是「接收完成」也是安静的——只在通知栏里
     * 留一句话，不当场响。面板上那行字才是主要的告知处，通知是它迟到的副本。
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
     * 画一条正在走的进度，或者更新它。
     *
     * <p>从服务这个连接的那条线程调用，所以这里除了组装并投递一条通知什么都不做：格式化
     * 以外的活都落在正在读字节的线程上，而那条线程正被传输等着。
     *
     * <p>{@code total == 0} 是「对端什么都没声明」，不是「零字节」。这时画不确定态——一条
     * 转着的进度条——而不是一个分母为零的假比例。{@code done} 超过 {@code total} 是按
     * {@code total} 封顶显示的：对端可以多发，一条 100% 的进度条比一条 112% 的有用。
     */
    static void progress(Context context, long done, long total) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        ensureChannel(manager);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("正在接收")
                .setContentText(soFar(done, total))
                .setContentIntent(open(context))
                .setOngoing(true)
                // 一条传输会往同一个 id 上投很多条通知。少了这个，每一条都是一次全新的
                // 提醒，而不是同一条状态的更新。
                .setOnlyAlertOnce(true);
        if (total > 0) {
            // 千分之一而不是字节：setProgress 收的是 int，而这两个数是 long。一个 4 GiB
            // 的传输用 int 装不下，用千分数就永远装得下，而且进度条本来也只需要三位
            // 有效数字。
            double fraction = Math.min(done, total) / (double) total;
            builder.setProgress(1000, (int) Math.round(fraction * 1000), false);
        } else {
            builder.setProgress(0, 0, true);
        }
        manager.notify(TRANSFER, builder.build());
    }

    /** 进度条下面那句话：有分母就说走到哪儿了，没有就说到现在收了多少。 */
    private static String soFar(long done, long total) {
        if (total > 0) {
            return Listener.bytes(Math.min(done, total)) + " / " + Listener.bytes(total);
        }
        return "已收到 " + Listener.bytes(done);
    }

    /**
     * 收完了：把进度条换成结果，并让它能被划掉。
     *
     * <p>不再是 ongoing，这条通知已经不需要为任何还在进行的事作证了，而人划掉它就是
     * 「我看过了」。点开仍然回主界面：收件目录的路径就在那句话里，而这个应用还没有一套
     * 浏览目录的入口，凭空造一个 {@code FileProvider} 只为让通知能点开一个文件夹，
     * 不值得。
     */
    static void finished(Context context, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        ensureChannel(manager);
        manager.notify(TRANSFER, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("接收完成")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(context))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    /** 把进度条收回去：连接结束了，但它没有结果可报。 */
    static void abandon(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(TRANSFER);
        }
    }

    /** 通知上那句话：窗口开着就说还剩多久，否则说跟着互传。 */
    private String line() {
        return Oshare.label(this) + " · AirDrop 接收中";
    }
}
