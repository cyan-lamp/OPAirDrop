package com.oshare.airdrop;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 控制中心里那块【AirDrop】：开就是「在收」，关就是「不在收」。
 *
 * <p># 为什么要有它
 *
 * <p>在此之前，接收端什么时候算「在收」取决于别人的东西——互传的「所有人（10 分钟）」。那是一个
 * 十分钟后自己到期的计时器：时间一到，我们的接收端跟着安静地停，屏幕上没有任何说法。人在控制
 * 中心看得见互传的开关，却看不见我们的状态，于是「怎么又收不到了」这件事没有答案。
 *
 * <p>这块磁贴把那个决定权拿回来，只有两种状态，没有第三种：**开**，服务在跑、8770 在听、
 * mDNS 与 BLE 都在发；**关**，三样一起停。状态就是磁贴本身的颜色，不需要再解释。
 *
 * <p># 它必须能冷启动
 *
 * <p>系统在展开通知栏时绑定这个服务，所以进程被杀之后磁贴照样点得动。点了之后走的是
 * {@link ReceiveService#setManual} + {@link ReceiveService#apply}：写凭据、起前台服务。
 * 磁贴的点击会带来一个短暂的允许窗口，这正是「应用在后台也能开始接收」需要的那一下。
 *
 * <p># 那个「第三种」是另一回事
 *
 * <p>开关仍然只有两个位置。但「开」之后还有一件事不归磁贴管：内核层（模块、{@code awdld}、
 * {@code awdltun0}）得在，否则 mDNS 连网卡都找不着，对面手机上什么都不会出现。这一段由
 * {@link StackKeeper} 去对齐，而它给出的结论必须有人显示——否则「开着」与「开着但谁也看不见」
 * 在屏幕上长得一模一样，那正是 2026-11-18 16:56 那次的现象。
 *
 * <p>所以磁贴多了【说了话的灰】这一格，说的是链路的事，不是开关的事：开关还在开的位置，接收端
 * 也还在听，只是这台机器此刻报不出名字。第二次点它仍然是「关」——一个开关只该有一个含义，
 * 状态另开一格去讲。
 *
 * <p># 为什么那一格是灰的，不是 {@code STATE_UNAVAILABLE}
 *
 * <p>因为后者在 Android 里不是「一种灰」而是「不存在」：{@code Tile.click()} 见到
 * {@code STATE_UNAVAILABLE} 直接 return，点击连 {@link #onClick} 都到不了。2026-11-18 19:55
 * 真机上它长成了一次死锁——开、关、再开之后这一格变灰且点不动，而它自己那句说明正是「点一下
 * 重试」。现在两种异常都用 {@code STATE_INACTIVE}：同样是暗的，但还接得住下一次点击。
 */
public final class AirdropTile extends TileService {

    private static final String TAG = "OPAirDrop";

    /** 磁贴的样子只能在主线程上改，而 {@link #refresh} 是从看护线程上被叫到的。 */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 此刻被系统绑着的那一个实例。没有人在看这一格的时候是 {@code null}。
     *
     * <p>有这个引用，{@link #refresh} 才能**当场**把图标改掉。{@link #requestListeningState}
     * 是绕系统一圈：系统要愿意在下拉面板可见时回调 {@link #onStartListening}，而那一次回调
     * 在 ColorOS 上不保证来（真机上量到过：服务在跑、链路在、这一格还是灰的，直到面板被重新
     * 展开）。一格图标不该依赖一次不确定的回调。
     */
    private static volatile AirdropTile bound;

    /**
     * 上一次有人点「开」的时刻，用来把「正在启动」与「起不来」分开。
     *
     * <p>点完那一瞬间服务确实还没起来——{@code bindService} 是异步的，而 {@code render()}
     * 紧接着就跑。没有这一位，那一格会在每次打开时先说一句「起不来」，然后才被改成好的；
     * 有了它，同一句话在头两秒里是「正在启动…」。
     */
    private static volatile long askedAt;

    @Override
    public void onStartListening() {
        super.onStartListening();
        bound = this;
        Log.i(TAG, "tile| listening");
        render();
    }

    @Override
    public void onStopListening() {
        if (bound == this) {
            bound = null;
        }
        super.onStopListening();
    }

    @Override
    public void onClick() {
        super.onClick();
        // 三种状态，三个动作。分开写是因为它们的「再点一次」含义不同：
        //
        //   关着                    → 开
        //   开着但服务没起来        → 再开一次（这是唯一能做的重试）
        //   开着、在听、但链路不在  → 去功能页（这里不建链，见下面的注释）
        boolean wanted = ReceiveService.listening(this);
        if (wanted && ReceiveService.running()) {
            if (!StackKeeper.linked()) {
                // 开着，8770 在听，蓝牙也在喊——但没有 awdltun0 就没有一条 mDNS 广播发得出去，
                // 对面手机上什么都不会出现。这里**不**偷偷 insmod：那要关二十五秒 Wi-Fi，
                // 中途被打断还会把 Wi-Fi 弄丢（理由见 StackKeeper 的类注释）。所以把人送到
                // 唯一能建链的那一页去。
                openFeature();
                return;
            }
            ReceiveService.setManual(this, false);
            ReceiveService.apply(this);
        } else {
            // 就是一个开关：打开就一直收，关掉就停。没有十分钟的窗口，也没有第二处设置——
            // 一个开关只该有一个含义（见 2026-11-16 15:10 那条：别人的十分钟窗口正是"又收不到
            // 了"的来源）。
            ReceiveService.enable(this, 0);
            askedAt = SystemClock.uptimeMillis();
            // 再敲一次门，用绑定而不是 startForegroundService：应用在后台时后者会被系统
            // 「接受但不执行」（真机上量到过 app=null、startForegroundCount=0），于是开关
            // 亮着、8770 没人听，直到把应用切到前台。理由与实测见 ReceiveService#poke。
            ReceiveService.poke(this);
            if (!Notices.ready(this)) {
                // 磁贴是个 Service，弹不出权限框。缺任何一条，刚打开的这个接收端都是哑的：
                // 没有通知，「有人要发东西给你」一个字也送不出去；没有蓝牙广播，对端连这台
                // 机器在不在都不知道——两种缺口在磁贴上长得一模一样（都是「开着但没反应」）。
                //
                // 所以把人送到主界面去：那里会把缺的那一条摆出来，并把框弹出去。这一下是他
                // 自己点的，startActivityAndCollapse 不受后台启动限制。
                askForPermissions();
            }
        }
        render();
        // 再画一次，而且这一次要落在 {@code settling} 之外（1.5 秒）：上面那一画发生在「开」
        // 的请求刚发出去的时候，服务要靠一次异步绑定才建得出来，那一刻 {@code running} 还是
        // false。一秒八之后它要么已经在了（这一格转绿），要么是真的没起来（这一格说出那句
        // 重试）。只画一次就会一直停在中间那个说法上。
        MAIN.postDelayed(this::render, 1_800);
    }

    /**
     * 把功能页摆到人面前。
     *
     * <p>用 {@link MainActivity#EXTRA_FEATURE} 而不是「打开应用」：应用首页说的是「怎么收」，
     * 功能页说的是「怎么把链路建起来」，而此刻他缺的正是后者。
     */
    private void openFeature() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_FEATURE, true);
        try {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (RuntimeException refused) {
            Log.w(TAG, "磁贴拉不起功能页：" + refused);
        }
    }

    /**
     * 把主界面拉起来，让它在可见的状态下把缺的几条权限问一次。
     *
     * <p>带上 {@link MainActivity#EXTRA_PERMISSIONS}：这是区分「他自己打开应用看看」和「他
     * 刚点了开、明确要能被发现」的唯一凭据。前者只弹从没问过的框，后者连「系统已经不再弹」的
     * 那几条也可以直接落到设置页——他已经在等文件了，这时候唯一有用的动作就是把开关勾上。
     */
    private void askForPermissions() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_PERMISSIONS, true);
        try {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (RuntimeException refused) {
            // 个别 ROM 在这一步会拒。磁贴自己已经切到「开」，而主界面的胶囊会写明缺什么，
            // 所以这里只留一行日志，不再弹第二条通知去解释一条发不出去的通知。
            Log.w(TAG, "磁贴拉不起主界面：" + refused);
        }
    }

    /**
     * 请系统把这格磁贴重画一次。
     *
     * <p>从 {@link StackKeeper} 那条线程上调：链路的有无不是人点的，磁贴自己不会知道，而它
     * 又只有被绑定的时候才画得出东西——{@code getQsTile()} 在没有实例时是 {@code null}。
     *
     * <p>{@link TileService#requestListeningState} 正是为这件事存在的：系统会（在下拉面板可见
     * 时）回调 {@link #onStartListening}，于是 {@link #render()} 读到的是**刚问过**的结论。
     * 面板没展开的时候这一次是空的，而那时也没有人在看这格图标。
     */
    static void refresh(Context context) {
        // 有人在看这一格：当场改。这一条是 2026-11-18 19:55 那条「关掉再打开，磁贴变成灰的、
        // 点不动」的一半答案——服务起来之后没有任何东西重画过它，而 service 起来本身不会让
        // 系统去问一次。见 {@link #bound}。
        AirdropTile self = bound;
        if (self != null) {
            MAIN.post(self::render);
            return;
        }
        try {
            requestListeningState(context, new ComponentName(context, AirdropTile.class));
        } catch (RuntimeException refused) {
            // 个别 ROM 在这一步会拒。链路状态本身是对的，只是这格图标要等到人下拉通知栏
            // 那一刻才更新——比让一次刷新把看护线程崩掉好。
            Log.w(TAG, "磁贴没能重画：" + refused);
        }
    }

    /** 磁贴的样子只反映一件事：现在收不收。 */
    private void render() {
        Tile tile = getQsTile();
        if (tile == null) {
            // 系统还没把这一格交到手上。没有 tile 对象就没有地方可以写，而这不是错误路径：
            // 面板收起时它本来就可能是 null。留一行，是因为「图标不听话」时要先排除这一种。
            Log.i(TAG, "tile| no tile object");
            return;
        }
        // 绿灯要同时满足三件事：他想要收（{@code receive.manual}）、进程真的在听（服务在跑）、
        // 对面真的看得见（{@code awdltun0} 在）。少任何一条，这一格都不该是绿的——假的绿灯
        // 比红灯难查得多。
        boolean wanted = ReceiveService.listening(this);
        boolean live = wanted && ReceiveService.running();
        boolean linked = StackKeeper.linked();
        // 刚点过「开」的两秒里，服务还可能在那次异步绑定的路上。这一段不该被说成失败。
        boolean settling = SystemClock.uptimeMillis() - askedAt < 1_500;
        Log.i(TAG, "tile| wanted=" + wanted + " live=" + live + " linked=" + linked
                + " settling=" + settling);

        if (wanted && !live) {
            // 开关拨上去了，服务却没起来。这一格必须说这件事，而且点它有救：下一次点击会再
            // 叫一次。**所以这里不能用 STATE_UNAVAILABLE**——那个状态在 AOSP 里就是「不可用」，
            // {@code Tile.click()} 见到它直接 return，点击根本到不了 onClick。旧版正是这样：
            // 说「点一下重试」，同时把这一格变成点不动的灰块，于是那句重试永远等不到人。
            //
            // 剩下的两种状态都可点，选灰的那个：ACTIVE 会是一盏假绿灯（服务没起来，却亮着）。
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(settling ? "正在启动…" : "监听没起来，点一下重试");
        } else if (live && !linked) {
            // 在听，但没有 awdltun0：mDNS 没有网卡可发。「亮着 = 能被发现」在这台机器上曾经
            // 是假的，所以这里不点绿灯；点它会去功能页，那里才有建链按钮。同样必须是可点的
            // 状态——不可用的一格会把「点这里建链」也一起废掉。
            tile.setState(Tile.STATE_INACTIVE);
            String trouble = StackKeeper.note();
            tile.setSubtitle(trouble.isEmpty() ? "链路未建立，点这里建链" : trouble);
        } else {
            tile.setState(live ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setSubtitle(null);
        }
        Log.i(TAG, "tile| state=" + tile.getState());
        tile.setLabel(getString(R.string.tile_airdrop));
        // 用应用自己的图标（蓝色圆点 + 两道环），取的是**裁剪过视口**的那一份
        // （{@code tile_airdrop.xml}：同一组路径，viewport 收到 48 并让图形顶格画）。
        // 颜色不用管：磁贴本来就按状态给图标上 tint（开=亮、关=暗），drawable 里那个蓝色渐变
        // 在这一格会被刷成纯白，只在别处（比如以后直接当图标用）才看得见。
        //
        // 尺寸这件事到此为止了：系统把图标 setBounds 到固定的 24dp 槽位，不读 drawable 的
        // 固有尺寸，所以「再放大一点」只能靠 drawable 里把图形画得更满。上限与实测数字见
        // tile_airdrop.xml 的文件头（结论：当前已占槽位 96%，最多到 1.04 倍，再多就裁边）。
        tile.setIcon(Icon.createWithResource(this, R.drawable.tile_airdrop));
        tile.updateTile();
    }
}
