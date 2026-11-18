package com.oshare.airdrop;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 接收端的入场券：这个应用能不能出现在人眼前、这台机器能不能被对端听见。
 *
 * <p># 为什么它值得单独一个文件
 *
 * <p>接收是两件事同时成立才发生的，而它们各有一条**运行时**权限，新装的应用默认都是关的：
 *
 * <p>**通知（{@code POST_NOTIFICATIONS}）** 管「有人在发东西给你」这句话送不送得到人眼前。关着
 * 的时候 {@link android.app.NotificationManager#notify} 既不抛异常也不返回值，它只是把通知丢掉。
 * 于是「没申请过这条权限」的表现不是崩溃，是**整个应用安静地什么反应都没有**：iPhone 点发送，
 * 本机不出任何提示，三十秒后对面收到 401。2026-11-16 的现场就是这样——{@code dumpsys package}
 * 里 {@code POST_NOTIFICATIONS: granted=false}，{@code dumpsys notification} 里
 * {@code AppSettings: com.oshare.airdrop importance=NONE}。而代码里全仓只有蓝牙那条权限有申请点，
 * 通知这条从来没有。
 *
 * <p>**蓝牙广播（{@code BLUETOOTH_ADVERTISE}）** 管「这台机器在不在」。iPhone 的隔空投送先听那
 * 二十字节的广播，听见了才把 AWDL 上的服务叫起来；没有它，8770 端口在听、mDNS 记录也在发，而
 * 对端从头到尾不知道这儿有台机器——日志里只有一行 {@code ble: failed: BLUETOOTH_ADVERTISE not
 * granted}。这条权限过去只在发送页申请，因为发送端也要广播；于是**只收不发的人**永远拿不到它：
 * 开关是绿的，人以为在等，其实没人在被等。同一件事还牵着 {@code BLUETOOTH_CONNECT}——缺它
 * 连「适配器开着吗」都问不出来，细节见 {@link #radio}。
 *
 * <p># 什么时候问
 *
 * <p>**一打开就问一次**，一次问全（{@link #first}）。这个应用只有一件事可做——收发文件；打开它
 * 就是已经表明了意图，没有「先看看再决定要不要权限」这种中间状态。三条各问一遍、之后再不打扰，
 * 比等人拨开接收开关才弹第一个框更诚实：那时候他已经在等了，而框才刚开始问。
 *
 * <p>但只问**从没问过的那几条**。系统对「问过两次仍被拒绝」的权限会静默失败——再调
 * {@code requestPermissions} 连框都不出现，人看到的是「点了没反应」。所以每次启动都把同样三
 * 条重问一遍，只会把「被拒绝」这个事实演成「应用坏了」。问过的就不再问，缺口交给界面上的状态
 * 胶囊和副标题说（{@link #shortGap}、{@link #gap}），这是它们存在的理由。
 *
 * <p>问过之后还愿意再问的时机只有一个：人主动拨开接收开关、点控制中心那块磁贴。那一刻他明确
 * 表示「我要能被发现」，请求的动机也才落在一句他自己能读懂的话上。所以 {@link #ensure} 保留
 * 「系统不再问就跳到设置页」这条路，{@link #first} 没有——启动时把人扔进系统设置，是最快的
 * 卸载理由之一。
 */
final class Notices {

    private static final String TAG = "OPAirDrop";

    /**
     * 接收端缺一不可的几条。
     *
     * <p>顺序有讲究：系统按这个顺序逐个弹框，通知在前——它是人最能理解的那一条，也是「为什么
     * 要有权限」这个问题的答案。
     *
     * <p>蓝牙那两条必须一起给，少一条都白给：{@code ADVERTISE} 是「能不能往外说」，{@code
     * CONNECT} 是「能不能先看一眼自己的适配器开着没有」。代码是这么走的——先
     * {@code adapter.isEnabled()}（受 CONNECT 保护），再注册广播（受 ADVERTISE 保护）。缺
     * CONNECT 时那句话在第一步就抛 {@link SecurityException}，然后被 {@code BleRadio} 归成
     * 「没有权限」直接返回，广播一次都不会注册。只申请 ADVERTISE 会得到一个漂亮但没用的
     * 权限框：勾了，日志里还是 {@code ble: failed: NO_PERMISSION}。
     */
    private static final String[] NEEDED = {
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
    };

    /** 与 {@link ReceiveService} 同一个偏好文件：这是同一个应用的状态，不另开一份。 */
    private static final String PREFERENCES = "opairdrop";

    /**
     * 已经问过哪几条。用来区分「从没问过」和「问过、系统已经不再弹了」。
     *
     * <p>记的是**逐条**一个集合，不是一个布尔。这一条是被 1.0.0 的实现教会的：那时只有一个
     * {@code notice.asked} 布尔，且只管通知。后来蓝牙那两条加进来，那些装过旧版的人全都被判成
     * 「已经问过了」，于是永远看不到蓝牙的权限框——开关照旧是绿的，对端照旧发现不了这台机器。
     * 权限是会随版本增加的，标记就必须能一条一条地记。
     */
    private static final String ASKED = "notice.asked.permissions";

    /**
     * 1.0.0 用过的那个布尔标记，只为升级上来的人留着。
     *
     * <p>它只有「问过通知」这一个含义，但不能直接丢掉：那些人确确实实被问过一次，升级后立刻再
     * 弹一遍同样的框，是在证明我们没有记住任何东西。读到它就当作三条都已问过，然后写进新格式。
     */
    private static final String LEGACY_ASKED = "notice.asked";

    /**
     * 问过「允许后台运行」没有。
     *
     * <p>和 {@link #ASKED} 分开记，因为它根本不是一条权限：答案不从
     * {@code onRequestPermissionsResult} 回来，而是一个会盖住整屏的系统对话框，人可以选择
     * 直接返回。这种东西不该每次启动都弹一次——那是骚扰，而不是提问。记一个布尔，只为压住
     * **启动时**那一条路；人自己拨接收开关、点磁贴时要的那次不受它管（见 {@link #ensure}）。
     */
    private static final String ASKED_AWAKE = "notice.asked.awake";

    /** 请求码。只有一处请求点，但也只该有一个号。 */
    static final int REQUEST = 0x0C01;

    private Notices() {}

    /** 此刻能不能发通知。 */
    static boolean allowed(Context context) {
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 此刻能不能广播「我在这儿」。缺它的时候，对端根本不知道这台机器的存在。
     *
     * <p>两条一起看：{@code ADVERTISE} 管说，{@code CONNECT} 管问自己一句「适配器开着吗」。
     * 只给前者，{@code BleRadio.start} 会在 {@code isEnabled()} 上抛出去、当场返回——广播
     * 注册这一行代码根本到不了。所以「蓝牙齐了」这件事必须两条都为真，否则人看到的是勾了的
     * 权限框和一行失败日志。
     */
    static boolean radio(Context context) {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 这台机器肯不肯让我们在没人看着的时候继续收。
     *
     * <p>它不是第三条运行时权限，而是一份**系统策略名单**：AOSP 的电池优化白名单，也就是
     * {@code dumpsys deviceidle whitelist} 那一份。第三方应用读得到、也能通过系统确认框把自己
     * 写进去，但没法绕过那个框。
     *
     * <p>为什么它和通知、蓝牙并列：2026-11-17 实测的因果链是
     * {@code App bg(IMMEDIATELY)} → {@code Close socket:[10456]} → 抓包里同一毫秒的本机 RST。
     * 也就是说 iPhone 点发送、邀请正常弹出、人点接收的那一刻，连接已经断了 3.4 秒。通知那两条
     * 权限管的是「人知不知道」，这一条管的是「知道了还接不接得住」——缺它的表现是**一路都像
     * 正常的，只在最后一步断**，比前两种更难反推。
     *
     * <p>设备上的策略写在 {@code /system_ext/etc/oappnetcontrol_config.xml}：默认
     * {@code config="011"}，淘宝、拼多多那类白名单应用是 {@code config="111"}。那份 XML 只有
     * 系统能改，但 nwpower 判「这个 uid 该不该被掐」时读的正是上面这份 AOSP 白名单，所以我们
     * 能把自己写进去。加进去之后同样的动作两次都跑完了。
     *
     * <p>取不到 PowerManager 时当作放行：这条缺口只会让后台变脆，不该在系统本身缺件的机器上
     * 把接收开关一起判成坏的。
     */
    static boolean awake(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power == null || power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** 三条都在：接收开关此刻说的话才是真的。 */
    static boolean ready(Context context) {
        return allowed(context) && radio(context) && awake(context);
    }

    /**
     * 缺的是哪一条——短到能塞进胶囊的写法；齐了返回 {@code null}。
     *
     * <p>三条权限对应三个完全不同的事实，而人看到的都是「开着但没用」：缺通知是**人被蒙在鼓里**
     * （有邀请，没人被告知）；缺蓝牙广播是**这台机器不存在**（对端根本不会发起）；缺后台存活是
     * **接不住**（邀请看得见、点下去就断）。胶囊只有一格，写不下理由，只能写缺哪一样；完整那句
     * 话在 {@link #gap}。
     */
    static String shortGap(Context context) {
        if (!allowed(context)) {
            return "缺通知权限";
        }
        if (!radio(context)) {
            return "缺蓝牙权限";
        }
        return awake(context) ? null : "受后台限网";
    }

    /**
     * 同一个缺口的完整说法，给副标题和提示条用；齐了返回 {@code null}。
     *
     * <p>这三句话写在这里，而不是各页面各写一份：三处（首页、磁贴、主界面）说的是同一件事，写
     * 散了就会出现「胶囊说缺蓝牙、副标题却说缺通知」这种自相矛盾的界面。
     */
    static String gap(Context context) {
        if (!allowed(context)) {
            return "通知权限未开，收不到邀请";
        }
        if (!radio(context)) {
            return "蓝牙广播没开，iPhone 发现不了这台设备";
        }
        return awake(context) ? null : "后台被限网，点接收会断开";
    }

    /** 缺的里面，从没问过的那几条。 */
    private static String[] unseen(Context context) {
        Set<String> already = asked(context);
        return select(context, permission -> !already.contains(permission));
    }

    /** 缺的里面，系统还愿意再问的那几条（人拒过一次，但仍没选「不再询问」）。 */
    private static String[] again(Activity activity) {
        return select(activity, activity::shouldShowRequestPermissionRationale);
    }

    /**
     * 按条件筛 {@link #NEEDED}，只留下此刻没拿到、且 {@code keep} 点头的那些。
     *
     * <p>三处要的都是同一件事的不同切片（没问过、还能再问），而「没拿到」这个判断写三遍
     * 迟早会有一处忘了看 {@code checkSelfPermission}，多筛出一个已经给过的权限。所以只写一遍。
     */
    private static String[] select(Context context, Predicate<String> keep) {
        String[] held = new String[NEEDED.length];
        int count = 0;
        for (String permission : NEEDED) {
            boolean granted = context.checkSelfPermission(permission)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted && keep.test(permission)) {
                held[count++] = permission;
            }
        }
        String[] out = new String[count];
        System.arraycopy(held, 0, out, 0, count);
        return out;
    }

    /**
     * 已经问过哪几条。
     *
     * <p>{@code getStringSet} 拿到的可能是 SharedPreferences 内部那个集合本身，所以调用方只读、
     * 要改先自己拷一份（见 {@link #remember}）——原地 add 会绕过一次 commit，写进去的东西下次
     * 开机就没了。
     */
    private static Set<String> asked(Context context) {
        android.content.SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        Set<String> asked = preferences.getStringSet(ASKED, null);
        if (asked != null) {
            return asked;
        }
        // 1.0.0 只有那一个布尔，且它只管通知。改格式时不把它当不存在：那些人确实被问过一次。
        if (preferences.getBoolean(LEGACY_ASKED, false)) {
            return new HashSet<>(Arrays.asList(NEEDED));
        }
        return Collections.emptySet();
    }

    /** 记下「这几条问过了」。只记真递出去的那些——没递的不该被算作问过。 */
    private static void remember(Context context, String[] permissions) {
        if (permissions.length == 0) {
            return;
        }
        android.content.SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        Set<String> asked = new HashSet<>(asked(context));
        Collections.addAll(asked, permissions);
        android.content.SharedPreferences.Editor edit =
                preferences.edit().putStringSet(ASKED, asked);
        // 旧标记到这里就没用了。留着它，下一个读到它的人（可能就是以后的我）还得再推一遍
        // 「它和新格式是什么关系」。
        edit.remove(LEGACY_ASKED).apply();
    }

    /**
     * 启动时问一次：把**从没问过的**那几条一起递出去。问出去了返回 {@code true}。
     *
     * <p>与 {@link #ensure} 的分工只有一条：这里不跳系统设置页。启动时把人扔进设置，是在他没
     * 表达任何意图的情况下夺走屏幕；而拨开关那一刻不一样，他要的东西就在那个开关后面。
     *
     * <p>三条一起递，系统不会弹三个框：{@code ADVERTISE} 和 {@code CONNECT} 同属「附近设备」
     * 那一组，会合并成一个框；通知独立一个。所以人看到的是两个框，先「附近设备」后「通知」。
     */
    static boolean first(Activity activity) {
        String[] fresh = unseen(activity);
        if (fresh.length == 0) {
            return false;
        }
        remember(activity, fresh);
        Log.i(TAG, "asking for " + fresh.length + " permission(s) at launch");
        activity.requestPermissions(fresh, REQUEST);
        return true;
    }

    /**
     * 人主动要「能被发现」时问：拨开接收开关、点控制中心那块磁贴。返回**此刻**是不是齐了
     * （刚递出去的那一次，答案不会立刻回来）。
     *
     * <p>三条路按顺序试：
     *
     * <p>1. 有从没问过的 → 只递那几条。升级加了新权限时走这条：旧标记不该把新权限一起挡掉。
     * <p>2. 全问过、但系统还愿意再问（{@code shouldShowRequestPermissionRationale} 为真）→
     *       再弹一次。人在那一刻拨开关，就是又给了我们一个请求的理由。
     * <p>3. 系统已经不再问了（人拒过两次，之后 {@code requestPermissions} 静默失败）→ 直接
     *       打开系统设置那一页。这时候除了人自己去勾那一下没有别的出路；什么都不做，等于让他
     *       对着一个永远收不到东西的开关。
     */
    static boolean ensure(Activity activity) {
        if (ready(activity)) {
            return true;
        }
        // 通知和蓝牙那两条要一个权限框，后台存活要的是另一个东西。分开走，因为它们的
        // 「不再问」语义不同：权限问过两次后系统自己闭嘴，而这条只在启动路径上闭嘴。
        if (!allowed(activity) || !radio(activity)) {
            String[] fresh = unseen(activity);
            // 有没问过的就先问那些——升级新增权限时，旧标记不能把新权限一起挡掉。只有全都问过，
            // 才轮到「系统还愿不愿意再问」这个判据。
            String[] ask = fresh.length > 0 ? fresh : again(activity);
            if (ask.length > 0) {
                // 系统按传进去的顺序弹框，而这个顺序来自 NEEDED：通知在前。别在这里重排——同一个
                // 请求在两次启动里换顺序，人会以为自己点错了。
                remember(activity, ask);
                Log.i(TAG, "asking for " + ask.length + " permission(s)"
                        + (fresh.length > 0 ? " (not asked before)" : " (again)"));
                activity.requestPermissions(ask, REQUEST);
            } else {
                Log.w(TAG, "permissions are off for good; opening the system page");
                open(activity);
            }
            return false;
        }
        // 两条权限都齐了，缺的只可能是「后台能不能活着」。这一条不设「问过就别问」：
        // 走到这里的人刚拨了开关或点了磁贴，他已经等了文件，这个框正是他要的东西。
        wake(activity);
        return false;
    }

    /**
     * 启动路径上该不该主动问「允许后台运行」。
     *
     * <p>和 {@link #ensure} 的分工只有一条：这里问过一次就不再问。启动时把整屏交给一个系统
     * 确认框，和启动时把人扔进设置页是同一类冒犯——他还没表达任何意图，而这条缺口只在实际
     * 接收时才咬人。缺口改由胶囊和副标题说（{@link #shortGap}、{@link #gap}）。
     */
    static boolean unseenAwake(Context context) {
        if (awake(context)) {
            return false;
        }
        return !context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(ASKED_AWAKE, false);
    }

    /**
     * 弹系统那个「允许后台运行」的确认框。弹出去返回 {@code true}。
     *
     * <p>这一条没有 {@code onRequestPermissionsResult}：答案只能在下次回到页面时用
     * {@link #awake} 读回来。所以这里做的事只有半件——把框递到人眼前。
     *
     * <p>两级退路，选法和 {@link #open} 一致：哪一页能让人一次解决。直接对话框最好，一句话、
     * 一个「允许」；ROM 拦掉这个 action 时退到电池优化列表，那里能找到本应用；再不行退到应用
     * 详情，「耗电管理」就在那一页里。都不通就算了——这条缺口不致命，只是后台变脆。
     */
    static boolean wake(Activity activity) {
        if (awake(activity)) {
            return false;
        }
        // 先记下「问过」。只影响 {@link #unseenAwake} 那一条路，所以放在跳转之前——跳出去了
        // 也可能一个框都没弹（ROM 拦 action），那时候更不该下次再试一遍。
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(ASKED_AWAKE, true).apply();
        Intent ask = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", activity.getPackageName(), null));
        try {
            activity.startActivity(ask);
            Log.i(TAG, "asking to be left running in the background");
            return true;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "no dialog for battery optimizations", unavailable);
            return openBattery(activity);
        }
    }

    /** 「允许后台运行」那两级退路。都走不通返回 {@code false}。 */
    private static boolean openBattery(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return true;
        } catch (RuntimeException alsoMissing) {
            // 列表页也没有。退到应用详情——ColorOS 的「耗电管理」入口在那一页，人还能自己
            // 找到路；什么都不做，等于让他对着一个会断的开关。
            Log.w(TAG, "no battery optimization page either", alsoMissing);
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.getPackageName(), null)));
            } catch (RuntimeException nowhere) {
                Log.w(TAG, "nowhere to send them for background running", nowhere);
            }
            return false;
        }
    }

    /**
     * 系统设置里的那一页。
     *
     * <p>两个落点，选的标准只有一条：**这一页能不能一次解决全部缺口**。
     *
     * <p>只缺通知 → 开「通知」那一页。那里就一条开关，点一下就完，不用在权限列表里找。
     * <p>缺的有蓝牙 → 开应用详情。ColorOS 的「通知」页里只有一条「允许通知」，蓝牙那两项
     * （「附近的设备」）根本不在那一页上——把人送过去，他会来回翻三遍然后退出来，以为这个
     * 应用坏了。2026-11-16 实测就是这一幕：三条全缺、点开关，跳到了只有「允许通知」的一页。
     */
    static void open(Context context) {
        // 只缺通知（蓝牙齐）才值得去通知页；只要缺到蓝牙，就得去应用详情。
        boolean onlyNotifications = !allowed(context) && radio(context);
        Intent settings = onlyNotifications
                ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.getPackageName(), null));
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(settings);
        } catch (RuntimeException unavailable) {
            // 极少数 ROM 没有这一页。退到应用详情，那里同样能进去。
            try {
                context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.getPackageName(), null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException alsoUnavailable) {
                Log.w(TAG, "no settings page for permissions", alsoUnavailable);
            }
        }
    }
}
