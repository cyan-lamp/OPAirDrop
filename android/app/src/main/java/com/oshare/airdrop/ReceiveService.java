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
        if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "接收", NotificationManager.IMPORTANCE_LOW);
            // 这一条不是要人做什么，只是一个必须存在的凭据：前台服务不挂通知就不算前台。
            // 关掉声音与角标，免得每十分钟窗口到期时响一次。
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }

        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("正在接收")
                .setContentText(line())
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    /** 通知上那句话：窗口开着就说还剩多久，否则说跟着互传。 */
    private String line() {
        return Oshare.label(this) + " · AirDrop 接收中";
    }
}
