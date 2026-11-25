package com.oshare.airdrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
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

    /**
     * 这个进程里真的有接收端在跑。
     *
     * <p>与 {@link #listening} 是两件事，而磁贴需要后者：{@code receive.manual} 是人的**意愿**，
     * 它写进去之后服务可能根本没起来——磁贴从后台调 {@code startForegroundService} 会被系统拒
     * （Android 12 起），而人点完就在 iPhone 上找设备了。只看意愿的开关于是会亮一颗假绿灯，
     * 那比红灯难查得多：红色会让人再点一次，绿色只会让人以为是自己手机的问题。
     *
     * <p>写在 {@link #onStartCommand} 挂了通知之后、{@link #onDestroy} 开头落回去，于是它答的是
     * 「现在有没有人在 8770 上听」，而不是「上一次成没成」。
     */
    private static volatile boolean running;

    /** 服务此刻在不在跑。给磁贴与看护共用的活判据。 */
    static boolean running() {
        return running;
    }

    /** {@link #onBind} 交出去的那张空位。没有方法，也没有状态：它只证明「实例建出来了」。 */
    private static final IBinder SEAT = new Binder();

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
        // 先叫看护者，再起服务：这一段之后本进程就要开始收包了，而 HANS 什么时候来冻它不由
        // 我们决定。见 armGuard。
        armGuard(context);
        apply(context);
    }

    /**
     * 把内核层那个看护者叫起来。只在「人决定要收」这一刻叫一次。
     *
     * <p>看护者是 {@code awdl-stack.sh} 里那段循环，把本进程从冻结的 cgroup 子树挪到
     * {@code /sys/fs/cgroup/system}，见那个文件 GUARD 那一段。这里只负责让它的寿命等于
     * {@link #MANUAL} 的寿命：开监听时它在，关掉三分钟后它自己走。
     *
     * <p>为什么不在 {@code boot} 那里了事：建链和想收是两次按键，最常见的顺序是先建链、
     * 后开磁贴。由 {@code boot} 顺手拉起的看护者在那时读到的是 `receive.manual=false`，
     * 人还没走到磁贴它就已经走了——那正是它需要防的那件事。
     *
     * <p>另一条线程：{@code su} 这一跳是几十到几百毫秒，而磁贴那一下是按秒算的。失败不断言
     * 任何事——没有 root 的机器上收包本来就只是应用层的事，链路有没有建由 {@link StackKeeper}
     * 在通知栏上如实说。
     */
    private static void armGuard(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            DriverSetup.Result result = DriverSetup.watch(app);
            int code = result.code();
            if (code != 0) {
                // 不是 subtitle()：那一句在没有 problem 的时候会退回读 stack-state 的回执，
                // 于是「脚本里没有 watch 这个子命令」会被写成「已就绪：AWDL 在 wonder0 上…」，
                // 一句真话回答了一个没人问的问题。这里要的是脚本自己最后说的那一行。
                String why = result.problem();
                if (why.isEmpty() && !result.lines().isEmpty()) {
                    why = result.lines().get(result.lines().size() - 1);
                }
                Log.w(TAG, "看护者没能起来：code=" + code + (why.isEmpty() ? "" : " " + why));
            }
        }, "opairdrop-guard").start();
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
        // 先松手再停。反过来的话，stopService 拆掉了实例，可那条绑定线还拉着
        // 「还有人在要它」，系统会把它重新建起来——磁贴关了、8770 又亮着，
        // 而人以为它关了。见 release()。
        release(context);
        try {
            context.stopService(new Intent(context, ReceiveService.class));
        } catch (RuntimeException ignored) {
            // 已经不在跑了。
        }
    }

    /**
     * 用一次绑定把服务叫醒：不给它「后台启动」这一个理由拒绝的机会。
     *
     * <p># 它补的是哪一条
     *
     * <p>2026-11-18 真机上量到的那条：应用在后台（桌面或最近任务里，进程还在但在缓存组）
     * 时从磁贴点「开」，{@code receive.manual} 写成了 true，磁贴也亮着，而
     * {@code dumpsys activity services com.oshare.airdrop} 里是这么写的——
     *
     * <pre>
     *   ServiceRecord{… com.oshare.airdrop/.ReceiveService}
     *     app=null                       ← 没有 attach 到任何进程
     *     startRequested=true
     *     code:SYSTEM_ALLOW_LISTED       ← 系统说了「允许」
     *     startForegroundCount=0         ← 但一次 startForeground 都没发生过
     * </pre>
     *
     * <p>也就是：{@code startForegroundService} 被**接受**了，却没有被执行。onStartCommand
     * 从来没有被调用过，于是 8770 没人听、mDNS 与蓝牙都没发、iPhone 的分享面板里干干净净。
     * 人这时看到的是一格亮着的磁贴，而唯一的出路是把应用切到前台——那一刻挂起的启动才被兑现。
     * 这就是「关了再开就收不到，必须打开 App」这条报告的机制。
     *
     * <p># 为什么绑定能过去
     *
     * <p>{@code startForegroundService} 与 {@code bindService} 在 AMS 里走的是两条路：前者
     * 带着「后台启动前台服务」的全套审查（那个 ALLOW_LISTED 就是审查的结论），后者只有一个
     * 条件——调用方（此刻正被系统点着的那块磁贴）活着。绑定到自己的服务时，AMS 直接把这个
     * service 实例建出来（{@code bringUpServiceLocked}），挂起的启动也随之兑现。
     *
     * <p># 这条线要留着，不能连上就解绑
     *
     * <p>第一版是「连上就解绑」，理由是这次绑定要的只是「把实例建出来」。真机上量到的是另一种
     * 结局：解绑 30 毫秒后系统把它拆了——
     *
     * <pre>
     *   19:34:59.214  ble: starting
     *   19:34:59.215  announcing 7f5ed39dbd10 at air.local:8770 on awdltun0
     *   19:34:59.244  ble stopped: idle          ← 29 毫秒
     * </pre>
     *
     * <p>因为拆与不拆取决于「还有没有人要它」。这次 startForegroundService 正是那个被接受但
     * 没被兑现的请求（见上面的 dumpsys），它不构成一个理由；真正让实例站着的是这条绑定。
     * 解绑等于把刚敲开的门又关上，磁贴亮着、8770 又没人听了。
     *
     * <p>所以这条线一直持到「关」：{@link #release} 在 {@link #stop} 里被调用，那一刻两个理由
     * 一起拿走。这中间它不花任何东西——绑定在自己的服务上，没有可见的副作用。
     *
     * <p>失败只记一行：没有这一次，磁贴仍然是磁贴，只是又回到那条「必须打开 App」的路上。
     */
    static void poke(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, ReceiveService.class);
        ServiceConnection fresh = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // 什么也不做。这一次绑定的全部用处已经在 AMS 里兑现了：实例建出来了。
                //
                // 这里**不能**解绑。真机上量到过：解绑的 30 毫秒后系统把服务拆了
                // （onDestroy → `ble stopped: idle`），因为拆与不拆取决于「还有没有人要它」，
                // 而这次 startForegroundService 正是那个没被兑现的请求（见上面的 dumpsys）。
                // 解绑等于把刚敲开的门又关上，磁贴亮着、8770 又没人听了。
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // 服务被系统收走了（或者我们自己停的）。下一次「开」会重新 poke。
                forget(this);
            }
        };
        synchronized (ReceiveService.class) {
            if (bond != null) {
                // 已经绑着了。第二次 bind 会在 AMS 里多出一条连接记录，而它换不回任何东西。
                return;
            }
            try {
                if (app.bindService(intent, fresh, Context.BIND_AUTO_CREATE)) {
                    bond = fresh;
                }
            } catch (RuntimeException refused) {
                Log.w(TAG, "绑定接收服务没成：" + refused);
            }
        }
    }

    /**
     * 松手：把 {@link #poke} 那次绑定解掉。
     *
     * <p>必须在 {@link #stop} 之前——「关」这个动作要同时拿走两个理由，缺一个服务都还在：
     * 一个是 {@code stopService}，另一个就是这条绑定的线。只解绑不停服务，服务会因为
     * startRequested 还在而活着；只停服务不解绑，它会被这条线拉着不放。
     */
    private static void release(Context context) {
        ServiceConnection held;
        synchronized (ReceiveService.class) {
            held = bond;
            bond = null;
        }
        if (held == null) {
            return;
        }
        try {
            context.getApplicationContext().unbindService(held);
        } catch (RuntimeException ignored) {
            // 系统已经先解了（进程被杀过，或者服务被收走时自动断的）。
        }
    }

    private static void forget(ServiceConnection which) {
        synchronized (ReceiveService.class) {
            if (bond == which) {
                bond = null;
            }
        }
    }

    /**
     * {@link #poke} 那条线。非 null 就代表「我们还绑着它」。
     *
     * <p>它是这个类里唯一一处「因为有人在要它，所以服务必须活着」的状态，所以 {@link #stop}
     * 要把它清掉——否则关掉之后再点开、再关，服务永远停不下来。
     */
    private static ServiceConnection bond;

    /**
     * 服务被创建时对齐一次，不等 {@code onStartCommand}。
     *
     * <p>两条路能把它创建出来：{@code startForegroundService}（磁贴与功能页那条），以及
     * {@code bindService}（见 {@link #poke}）。后者创建的实例不带「五秒内必须 startForeground」
     * 那个期限，但这里仍然立刻把通知挂上：服务在跑的每一毫秒都该有那条「正在接收」，而
     * 「进来时就已经该收」这件事与「怎么进来的」无关。
     *
     * <p>{@link #up} 幂等，所以 {@code onStartCommand} 随后再来一次也只是重复同一组赋值。
     */
    @Override
    public void onCreate() {
        super.onCreate();
        if (listening(this)) {
            up();
        }
    }

    /**
     * 进入「在收」：挂上身份，起接收端，开始看链路。
     *
     * <p>抽出来是因为有两个入口（{@link #onCreate} 与 {@link #onStartCommand}），而它们要做的
     * 是同一件事。写在两处就会有两种说法，迟早会分家。
     */
    private void up() {
        startForeground(NOTIFICATION, notification(this));
        shown = true;
        running = true;
        // 磁贴那一格此刻可能还停在「正在启动…」：它上一次画是在点击的那一刻，而服务要靠一次
        // 异步绑定才建得出来，中间没有任何东西会去重画它。这一句就是那个「有人该重画了」。
        // 没有它，一格图标会一直灰着，而 8770 其实已经在听——2026-11-18 19:55 真机上就是这样，
        // 而且那一格当时还被写成了点不动的 STATE_UNAVAILABLE。
        AirdropTile.refresh(this);
        Listener.of(this).start();
        // 应用层到这里就齐了。内核层（模块、awdld、awdltun0）这里**不建**：建它要关二十五秒
        // Wi-Fi，只有功能页那个按钮该做这件事。看护者只负责在它不在时如实说，理由见 StackKeeper。
        StackKeeper.watch(this);
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
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        up();
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
        // sticky（2026-11-25 改）：接收开着的进程被系统杀掉（比如 ColorOS 从最近任务清理）
        // 之后，系统自动把它拉起来——监听恢复、常驻通知回来。原来 NOT_STICKY 的理由是
        // 「重启没有窗口、没有理由」，但那说的是任意进程死亡；凭据（receive.manual）就
        // 是理由：开着接收被杀，就该在原地站起来。forceStop 场景由 KeepAlive 的
        // system_server 钩子拦，两道防线各管各的。
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 第一句就落回去：从这一刻起 8770 正在关，磁贴不该再亮。下面两步都是「还能收到」的
        // 最后一段，而人看见的是磁贴那一格。
        running = false;
        // 顺序是有讲究的：先叫看护者停手，再关广播，最后把这个进程的前台身份收掉。
        //
        // unwatch 只置一个标志（理由见 StackKeeper#unwatch）：它那条线程除了枚举网卡什么都不做，
        // 一眼就能退出，所以「停」是「下一眼别看」的意思。
        StackKeeper.unwatch();
        Listener.of(this).stop();
        shown = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 这张空位只有一个用途：让 {@link #poke} 那次绑定走得通。绑定在这里不是「对外提供
        // 服务」，而是唯一一条能把服务实例从后台拉起来的窄门（理由见 poke）。返回 null 会让
        // 系统把这次绑定当成失败，门就白敲了。
        return SEAT;
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
     * 划掉最近任务时系统会先知会一下还在跑的服务（{@code onTaskRemoved}）。
     *
     * <p>2026-11-25 实测：KeepAlive 的钩子拦住了 AMS 杀进程（划卡后 pid 不变），但划卡的
     * 发起者（OplusAthenaAmManager）自己还会发一轮 forceStop 通知清理广播，NotificationManagerService
     * 照单全收——进程活着，常驻通知却被摘走了。这里加一手兜底：延迟两秒把通知重新提交
     * （服务此刻仍是前台，重新 {@code startForeground} 合法且幂等）。延迟是给系统的清理
     * 留时间——立刻重挂会被随后落下的那轮取消再摘一次。
     */
    @Override
    public void onTaskRemoved(android.content.Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!shown) {
            return;
        }
        CLOSER.postDelayed(() -> {
            if (shown && running()) {
                startForeground(NOTIFICATION, notification(this));
                Log.i(TAG, "划掉任务后重新挂上常驻通知");
            }
        }, 2000);
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
