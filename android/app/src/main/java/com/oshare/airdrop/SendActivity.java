package com.oshare.airdrop;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLSocket;

/**
 * 发送面板：仿一加互传「与 iPhone 互传」那一页，形态是一块从底边上滑的玻璃。
 *
 * <p># 为什么是这一页，而不是一张列表
 *
 * <p>按下分享面板里那行「发送到 iPhone」的人，此刻已经决定要发了；他要看的是「对面在哪、
 * 我点谁」，不是一份文件清单。所以这一页照那个页面的份量分三段：**顶部**（返回 + 标题 +
 * 本设备名）、**中部**（搜索态是雷达，结果态是头像）、**底部**（说明卡）。文件信息收成标题
 * 下面一行小字。
 *
 * <p># 为什么背景要用窗口模糊，而不是自绘一块假的
 *
 * <p>主界面的导航胶囊用 {@link RealtimeBlurView} 捕获它背后的内容，因为那里两者在同一个
 * Activity 里，能拿到 {@code RenderNode}。这一页是另一个 Activity，它背后是**别人的**窗口
 * （一加互传的分享面板，或者主屏），拿不到。能拿到那个后景的只有系统：
 * {@link Window#setBackgroundBlurRadius}，Android 12 起跨窗口实时模糊。所以「同款」在这里
 * 不是同一个实现，是同一个观感——玻璃背后确实有东西在动。
 *
 * <p>代价是窗口必须透明（{@code windowIsTranslucent} + 透明窗口背景），否则系统模糊的是
 * 它自己。于是这块面板自己负责画圆角、描边与那层半透明底色：
 * {@link #sheetColor()} 保留一点透明度，让模糊透出来。
 *
 * <p># 上滑与下滑都是自己的
 *
 * <p>窗口动画在主题里换成了 0 时长（{@code @anim/none}）。上滑是第一帧之前把面板挪到屏幕
 * 下方再动画回来；下滑是 {@link Sheet} 拦下的纵向拖动，松手按位移与速度决定回弹还是走人。
 * 平台的淡入淡出留在这里只会变成第二段动作。
 *
 * <p># 头像上那圈进度是真的
 *
 * <p>它来自 Rust 侧读文件时的计数（每 512 KiB 一次），不是定时器。四个阶段对应协议四步：
 * 连上 → 报价（对面在弹确认）→ 传字节 → 收端确认。中间那段可能停十几秒，所以说的是
 * 「等待对方接受」，而不是一个停在 0% 的进度条。
 *
 * <p># 换深浅色不重建页面
 *
 * <p>{@code configChanges} 里带 {@code uiMode}，所以系统日夜切换时这个 Activity 不会被销毁
 * 重建——那会让正在传的那一圈进度连同它的 {@link Transfer} 一起消失。改动落在
 * {@link #onConfigurationChanged}：换一张配色表，重建视图树，然后从字段里把状态重放一遍。
 * 视图只是状态的投影，状态不放在视图里。
 */
public final class SendActivity extends Activity implements Peers.Watcher {

    /**
     * 两个超时在表里各有行：{@code client.connect_timeout_ms} 与 {@code client.read_timeout_ms}。
     *
     * <p>这里的常量是它们成为一行之前的值，也是表读不出来时的回落。读取的超时是
     * {@code SO_TIMEOUT} 的语义——「多久收不到任何字节」，不是「整次投送多久」，
     * 所以慢速大文件靠的是数据一直在流动，而不是把总数估得足够大。
     *
     * <p># 为什么是十五秒，而不是四十五秒
     *
     * <p>这里原来按 ASK-2a 那句「senders wait about 45 s」定成 45 s，理由是「接收端自己
     * 有 15 s 弹窗超时，比它短就等于没等人回答」。那个理由是错的，错在一个前提上：
     * {@code /Ask} 的 {@code 200} **不是**人点了接受之后才来的，它是「我已经问过我的主人了」
     * ——会话在那里就建好了，弹窗才开始显示。等真人在对面手机上做决定的那一段，是
     * {@code /Upload} 在等它的答复，不是 {@code /Ask}。
     *
     * <p>所以两个等待要分开给耐心：问一句 HTTP 十五秒足够（本就该是毫秒级的），
     * 人对着弹窗做决定的那段才需要长——见 {@link #UPLOAD_READ_TIMEOUT_MS}。
     * 四十多秒全压在第一个上，唯一的实际效果是把「对方不答」这件事也拖长了三倍。
     */
    private static final String TAG = "OPAirDrop";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /**
     * 等「对面收完没有」的读超时。
     *
     * <p>档案已经在发了，对面手机上那个人多半正看着弹窗。这一段他犹豫多久都合法，所以
     * 放宽到九十秒——与参考实现一致。它只在这条连接上生效，从 {@link Native.Progress#SENDING}
     * 那一刻起才换上去（见 {@link #transfer}），所以不会把「对方根本不答」也一起拖成九十秒。
     */
    private static final int UPLOAD_READ_TIMEOUT_MS = 90_000;

    /**
     * 发送缓冲区的上限，六十四 KB。
     *
     * <p>不设的时候平台会把它长到一兆以上，于是「写完了」这句话就不再是「发出去了」：整份
     * 档案落进内核队列时调用就返回了，界面上那一圈立刻走到 100%，而线路上一块还没走完。
     * 留在这里的数字与参考实现相同（{@code HttpCall.SEND_BUFFER}），它的日志里记着代价：
     * 24 MB 的视频在 50 ms 内报「已写 1.06 MB」，然后是 7.24 秒的静默。
     *
     * <p>这个尺寸仍然远大于这条链路的带宽时延积，所以它限制的是「最多能抢跑多少」，不是
     * 吞吐。必须在下 {@code connect} 之前设：平台在建 socket 的时候就把队列定好，事后再设
     * 只是给一个尺寸已经错了的队列换个标签。
     */
    private static final int SEND_BUFFER = 64 * 1024;

    /** 上滑进来的时长。比系统对话框略慢：它是整块面板在动，不是一个小窗在淡入。 */
    private static final int ENTER_MS = 340;
    /** 下滑走人的时长。要短：手指已经做了决定，动画只是收尾。 */
    private static final int EXIT_MS = 220;
    /** 回弹的时长。 */
    private static final int SPRING_MS = 240;

    /**
     * 跟手的阻尼：每个输入事件把「还差的距离」走掉这么多。
     *
     * <p>匀速拖动时它有一个恒定的稳态误差，大小是「每个事件走过的位移 × (1−k)/k」。这里取
     * 0.5，也就是误差等于一个事件的位移：以每秒 1000 像素拖、120Hz 输入算，约 8 像素，不到
     * 半个指甲盖；慢慢拖时误差不到 1 像素。手指一停，目标不再移动，这个误差在几个事件内归零
     * ——所以它是「跟得上但有一点分量」，不是「一直落后一截」。
     *
     * <p>为什么必须有它：输入事件是离散的，屏幕是 120Hz，而手指不是。没有阻尼，位置就是每来
     * 一个事件跳一下；有了它，位置是连续的。这也是「阻尼调大一点」能被感知到的唯一方式。
     */
    private static final float FOLLOW = 0.5f;

    /** 面板顶部的圆角。和导航胶囊同一个数。 */
    private static final float CORNER_DP = 28f;

    /** 面板顶部那圈模糊的半径。系统模糊，单位是屏幕像素。 */
    private static final float BLUR_DP = 28f;

    /**
     * 往下拖出去多远才算「要关」。按面板高度的比例算，不是固定像素——这块面板在不同分辨率上
     * 高度差很多，写死像素在小屏上会变成「一碰就走」。
     *
     * <p>一开始是 0.18（不到五分之一）。太浅了：手指在面板上往下带一下、想够到下面的内容，
     * 只要越过这条线、抬手时又带了一点速度，面板就关掉了。而「我要关掉」和「我想往下看看」
     * 在中段本来就是分不清的，得过了三成才算意图明确。
     *
     * <p>甩动（速度过阈值）不看这一条：短促、方向明确，没有「想够内容」这种解释。
     */
    private static final float LEAVE_FRACTION = 0.28f;

    private Scheme S;
    private float density;

    /** 要发的东西。复制到 cache 之后才进这个列表。 */
    private final List<File> files = new ArrayList<>();
    private long totalBytes;

    /**
     * {@link #senderId()} 算过一次之后留在这里。
     *
     * <p>整个进程一份，因为它是这台设备的身份，不是这一次投送的名字；进程重启后重新派生，
     * 但派生的输入是证书，所以下次还是这个值。
     */
    private static volatile String senderId;

    private Peers peers;

    /**
     * 蓝牙广播的开启请求码。
     *
     * <p>没有这条广播，iPhone 不会把 AirDrop 服务拉起来——我们的 mDNS 查询会一路走到
     * 无线侧、每两秒一次、帧帧合法，然后一个回包都收不到。原因和组播、和 QU 位都无关：
     * 对端的服务没开。
     */
    private static final int ASK_BLE = 0x0B1E;

    /** 根：负责下滑关闭与上滑进入。它在配置变更时**不**重建，重建的是它里面的内容。 */
    private Sheet sheet;
    /** 内容容器。{@link #rebuild} 清空它再填。 */
    private FrameLayout content;
    private LinearLayout stage;
    private TextView subtitle;

    /** 标题下一行说发了什么。重建时从它重放。 */
    private String subtitleText = "正在准备……";

    /** 中部此刻说的话（没有设备时显示在雷达下面）。 */
    private String searching = "正在搜索附近设备……";
    /** 最近一次发现到的、能连的设备。 */
    private List<Peers.Peer> found = new ArrayList<>();

    /**
     * 最近一次浏览报上来的全部对端，包含还没上屏的。
     *
     * <p>{@link #found} 是「该画的」，这一份是「见过的」。两者的差别只有一个原因：名字还没到，
     * 所以先不画（见 {@link #settle}）。真名晚到时（{@link #showRealName}）要靠这一份把那一台
     * 补上——不然它得等下一个 mDNS 报文，而那个报文可能一秒后才来。
     */
    private List<Peers.Peer> reachable = new ArrayList<>();

    /** 每台设备一句话状态，键是设备名；重扫与换主题时复用，避免文字闪一下。 */
    private final Map<String, String> statuses = new LinkedHashMap<>();
    /**
     * 这一句要不要用告警色（红）说。
     *
     * <p>和 {@link #statuses} 分开存，而不是从文字里认出「对方拒绝」四个字：判定在这里是一次
     * 异常类型的比较（{@link RefusedException}），在渲染那边再比对文案就成了第二套判据——改一次
     * 措辞，颜色会悄悄退回蓝。换主题重建时也要能在，所以存在这张表里而不是直接写在视图上。
     */
    private final Map<String, Boolean> statusAlarms = new LinkedHashMap<>();
    /** 每台设备当前的进度 [0..1]。环只认它，所以重建之后环还在原处。 */
    private final Map<String, Float> progressOf = new LinkedHashMap<>();
    /** 当前这一份视图里的头像与状态字。重建时清空重填，进度更新按名字找。 */
    private final Map<String, Nick> nicks = new LinkedHashMap<>();
    private final Map<String, TextView> stateViews = new LinkedHashMap<>();
    /**
     * 设备格子的名字那一行，键是 mDNS 实例名（hash）。
     *
     * <p>/Discover 回来之前只有 hash，回来之后才换成对方自己起的名字。视图重建时按 hash 找回
     * 来重填，所以这个 map 跟着视图走；真名本身另存在 {@link #realNames} 里，跨重建常在。
     */
    private final Map<String, TextView> nameViews = new LinkedHashMap<>();
    /** hash → 对方在 AirDrop 里设的真名。发现阶段拿不到，/Discover 应答到达后填入。 */
    private final Map<String, String> realNames = new LinkedHashMap<>();
    /**
     * 上一次替哪台设备问过名字。键是稳定地址，值是那一刻。
     *
     * <p>一次问不到不该永远不问：对面锁屏、正忙、AirDrop 刚切走时握手都会失败，而它可能十几
     * 秒后就好了。以前这里是「一个地址一次，进程内不再重试」——结果是那格子的名字要等到重新
     * 进面板（新一次会话、空表）才可能补上，中间一直写着占位。
     */
    private final Map<String, Long> probed = new LinkedHashMap<>();
    /** 同一台设备两次问名字至少隔这么久。问一次要连 TLS，问得太勤纯耗电。 */
    private static final long NAME_RETRY_MS = 15_000;

    /**
     * 一台设备凑齐地址端口之后，为了等名字最多先不上屏多久。
     *
     * <p>1.5 秒是量出来的位置：真机日志里「ready」到「probed name」之间是 71 / 195 / 200 /
     * 600 / 718 毫秒（{@code ready ...:8770 after 544 ms} → {@code probed name for 7bda... ->
     * 不辛苦命苦} 相隔 600 毫秒）。1.5 秒把正常的那一段全都盖住，剩下的时间只留给「问了它
     * 不答」——那时照型号显示，不能让一台设备因为问不到名字就一直隐形。
     */
    private static final long NAME_HOLD_MS = 1_500;

    /**
     * 「等名字的窗口到期」那一下，由 {@link #armSettle} 排出来，到点再算一次。
     *
     * <p>道理在 {@link #settle} 的注释里：这一页的重算以前完全靠报文驱动，而「放弃等名字」
     * 是一个不看报文的时刻。iPhone 锁屏、或者它只是一遍遍重复答同一个地址（那种重复只更新
     * 时刻，不算「变了」，连重画都不触发）时，没人来叫我们——那台设备就会一直停在「没上屏」。
     */
    private final Runnable settleDue = () -> settle(System.currentTimeMillis());

    /** 正在滑出。防止连按两次返回键跑两遍动画。 */
    private boolean closing;

    /**
     * 「先选文件」那条路的请求码。
     *
     * <p>只有从首页点进来才走它：系统分享面板那条路进来时文件已经在 intent 里，没有可选的。
     */
    private static final int PICK_FILES = 0x5E4D;

    /**
     * 这张页面是被流体云上那颗【重试】叫起来的。
     *
     * <p>不是 {@code ACTION_SEND} 的另一种写法：那一条带着文件，这一条什么也不带——要发的东西
     * 上一次就定了，{@link SendHost#retry} 手上就有。带上文件反而会变成第二次解析、第二份缓存
     * 副本，而人按那颗按钮要的只是「再发一遍刚才那个」。
     */
    static final String ACTION_RETRY = "com.oshare.airdrop.action.RETRY";

    /** 选择器已经弹出去了。它回来之前再点一次不该叠出第二个。 */
    private boolean picking;

    /**
     * 正在跑的那条连接。
     *
     * <p>取消发送只有一条路：把这个 socket 关掉，正在读/写的线程立刻抛出来。协议里没有
     * 「收手」这种说法——对面已经在收字节，唯一的停法就是断线。所以这个字段不是为了优雅，
     * 是为了让人点了取消之后真的停。
     */
    private volatile Socket live;

    /** 这一次停是用户点的，不是出故障。catch 里靠它决定说「已取消」还是说原因。 */
    private volatile boolean cancelling;
    /** 面板的窗口。它不参与任务栈，也就不会被系统摆成小窗、不会在最近任务里留一张卡。 */
    private Dialog panel;

    /** 正在跑的那段位移（上滑、回弹、滑出）。按下时要能把它停掉。 */
    private ValueAnimator slide;

    /**
     * 面板**应当**画在哪（相对它自己的自然位置 0）。位置只有这一个真相。
     *
     * <p>{@code top} 和 {@code translationY} 都是它的投影，见 {@link #apply()}。
     *
     * <p>为什么需要一个字段而不是读 {@code getTop()}：视图的位置不是只有我们在改。布局一遍
     * ——到 inset、换主题、设备列表重建、文字变了要重新量——父容器就会把子视图摆回它自己算出来
     * 的那个位置，也就是 0。之前 {@link #place} 拿 {@code getTop()} 当「现在在哪」，于是一次
     * 布局之后，下一帧算出「从 0 走到 y」的整段差，把面板又整个推回去：屏幕上就是上滑到一半
     * 突然顿一下、甚至来回弹。
     */
    private float placed;

    /** 面板背景的实时模糊；{@code null} 表示这台机器给不了，走纯色退路。 */
    private PanelGlass glass;
    /** 盖在模糊之上的染色层。模糊只给「看到背后」，可读性由它保证。 */
    private View tint;
    private volatile boolean sending;

    /**
     * 这一次投送的名字，卡片期间不变。
     *
     * <p>随机串而不是对端名字：同一台 iPhone 连着发两次是两件事，用名字当键会让第二次的
     * 进度接到第一次那张已经结束的卡上——那张卡的状态是终态，于是第二次看上去一直没动。
     *
     * <p>{@code volatile}：写在主线程（{@link #send}），读在卡片那条线程（取消从流体云
     * 进来），中间没有锁，靠它保证读到的是这一次的那个串。
     */
    private volatile String sendSession = "";

    /**
     * 这一页的视图已经没了，但传输可能还在跑。
     *
     * <p>它决定两件事：{@link #onDestroy} 要不要把 {@link #cardCancel} 从 {@link SendHost} 上
     * 摘下来（有传输在跑就还不能摘），以及发送线程收尾时要不要摘。写在最后一个收尾点上而不是
     * 在 {@code onDestroy} 里延时——传输可能还要跑几分钟，一个恒为真的布尔比一条定时器诚实。
     */
    private volatile boolean pageGone;

    /**
     * 卡片上那个「取消」落回这里。
     *
     * <p>先比 {@link #sendSession} 再比 {@link #sending}：卡片可能停在一张上一次留下的
     * 卡上（它还没走完那段收尾动画），那时点取消不该把这一次打断。两次发送对人来说听起来
     * 一样，只有那个随机串分得开。
     */
    private final SendHost.Canceller cardCancel = session -> {
        if (!sending || !session.equals(sendSession)) {
            return false;
        }
        cancelSend();
        return true;
    };

    /**
     * 一台设备占多宽、头像画多大。都按 dp 定，因为要拿来算「一行能放几个」。
     *
     * <p>头像比原先小了一号：这一版要的是「一眼看见身边都有谁」，而不是把某一台放大。
     */
    private static final int CELL_DP = 86;
    private static final int NICK_DP = 62;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        S = schemeOf(getResources().getConfiguration());
        density = getResources().getDisplayMetrics().density;
        PeerNames.load(this);

        buildShell();
        showPanel();
        slideIn();

        // 这一页在人眼前的这段时间里，流体云该有（判据见 {@link SendSession}）。
        SendSession.opened();
        // 告诉卡片由谁画：不 attach 的话 SendHost 没有 Context，begin/progress/finished 全都
        // 悄悄 return——卡片一帧都不会出现，而日志里什么都没有。页面活着它就活着。
        SendHost.attach(this);
        // 卡片上那个「取消」要说回这里。装/卸在页面这一层，而不是每次发送：卡片在两次
        // 发送之间也可能是活的（终态那张还要放一段动画），那时按下去什么都不该发生。
        SendHost.register(cardCancel);

        Intent source = getIntent();
        if (ACTION_RETRY.equals(source.getAction())) {
            // 卡上那记【重试】。把它当成一次性的：动作改掉，屏幕转一下、主题换一下都不会
            // 让这一页重放一遍发送。
            source.setAction(null);
            // 重来的动作多半还在（同一个进程里）；不在（进程被杀过）就退回选文件——
            // 人按那颗按钮要的是「再发一次」，不是看一块空面板。
            if (!SendHost.retry()) {
                requestFiles();
            }
        } else if (shared(source)) {
            new Thread(() -> intake(source), "opairdrop-intake").start();
        } else {
            // 空手来的（首页「发送文件」）：先把文件选出来，再谈发给谁。
            requestFiles();
        }
    }

    /**
     * 页面还活着的时候，又来了一条分享。
     *
     * <p>不接这一段会很难查：系统把新的 {@code SEND} 交给已经在栈顶的这一页，不会重建它，
     * 而 {@link #onCreate} 早就跑完了——于是手上还是上一次的文件，人会以为「分享没生效」。
     * 换掉文件就够了；正在跑的那次传输不动，它读的是自己那份路径列表。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (ACTION_RETRY.equals(intent.getAction())) {
            setIntent(intent);
            intent.setAction(null);
            if (!SendHost.retry()) {
                requestFiles();
            }
            return;
        }
        if (!shared(intent)) {
            return;
        }
        setIntent(intent);
        new Thread(() -> intake(intent), "opairdrop-intake").start();
    }

    /**
     * 这条 intent 里有没有现成的文件。
     *
     * <p>看 action 而不是看有没有 data：{@code SEND} 里附带一段文字也算有内容，而文字要在
     * {@link ShareIntake#resolve} 里才变成文件——在这里替它判空会让「只分享一句话」变成静默失败。
     */
    private static boolean shared(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    /**
     * 让人挑文件。
     *
     * <p>用 {@code OPEN_DOCUMENT} 而不是 {@code GET_CONTENT}：前者给的是长期可读的 URI、来源是
     * 系统文档服务，相册、云盘、下载都在同一个选择器里，而且不需要任何存储权限。
     *
     * <p>选完之后不直接发：这一页本来就是「先看见谁在，再点谁」。选择器只是把手上空着这件事
     * 补上，发不发仍然由人决定。
     */
    private void requestFiles() {
        if (picking) {
            return;
        }
        picking = true;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(picker, PICK_FILES);
        } catch (ActivityNotFoundException none) {
            picking = false;
            Log.w(TAG, "no document picker on this device", none);
            subtitleText = "这台机器没有文件选择器";
            if (subtitle != null) {
                subtitle.setText(subtitleText);
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES) {
            return;
        }
        picking = false;

        Intent share = shareOf(result, data);
        if (share == null) {
            // 取消就是「不发了」。面板这时还是空的，留着它只会让人再点一次返回。
            close();
            return;
        }
        // 换成那条 intent 再走一遍：下面所有代码只有「读这条 intent 里的文件」一种说法，
        // 不会出现「选出来的文件」和「分享进来的文件」两套处理。
        setIntent(share);
        new Thread(() -> intake(share), "opairdrop-intake").start();
    }

    /** 选择器的结果拼成一条我们认识的 {@code SEND_MULTIPLE}；一个文件都没有时返回 {@code null}。 */
    private static Intent shareOf(int result, Intent data) {
        if (result != RESULT_OK || data == null) {
            return null;
        }
        ArrayList<android.net.Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                android.net.Uri uri = clip.getItemAt(index).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return null;
        }
        Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
        share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        return share;
    }

    /**
     * 把面板放进一个独立窗口。
     *
     * <p># 为什么是 Dialog，而不是 Activity 自己的窗口
     *
     * <p>Activity 的窗口归系统的窗口管理摆布：从相册分享进来时会被塞进自由窗口（小窗），离开后
     * 还会在最近任务里留一张卡。Dialog 的窗口不参与这套逻辑——它只是浮在当前 Activity 之上的
     * 一层，没有任务、不进最近任务。一加互传也是这么做的：`IOSShareActivity` 是 Activity，
     * 面板由 `COUIBottomSheetDialog` 承载；那个 Dialog 的窗口是 `setLayout(-1,-1)` 全屏，和
     * 我们这里一样。
     *
     * <p>Activity 自己用 {@code windowNoDisplay}，屏幕上一个像素都不画。
     */
    private void showPanel() {
        panel = new Dialog(this, R.style.SendPanelDialog);
        Window window = panel.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            // 面板会移到屏幕下方再滑上来，布局不能把它裁在窗口里。
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
        panel.setContentView(sheet);
        // 点外面不关：这一页满屏，外面没有可点的地方；而且误触一下就把传输面板关掉很糟。
        panel.setCanceledOnTouchOutside(false);
        panel.setOnDismissListener(dismissed -> finish());
        panel.show();

        applyWindowBars();
        // 玻璃要等 attach 之后才装得上（模糊 Drawable 由 ViewRootImpl 创建）。
        sheet.post(this::installGlass);
    }

    /**
     * 页面走了。
     *
     * <p>只记「页面不在了」，不停传输：{@link SendSession} 把「页面」和「字节」分成两个布尔，
     * 正是为了这一刻——人把应用划掉时，传输该继续跑完，云也该继续在。
     *
     * <p># 取消的落点要不要一起撤
     *
     * <p>**只看有没有传输在跑**。这里原来无条件 {@code unregister}，注释说「页面销毁之后按下去
     * 只会调到一个已经没有传输可停的对象上」——那句话是错的：{@link #live} 和 {@link #cancelling}
     * 是这一页的字段，页面对象本身还活着（发送线程正握着它），socket 也还开着。撤掉注册的结果
     * 不是「按了没用」，而是**卡片上那颗【取消发送】变成一颗彻底不响的按钮**——而人把面板划走
     * 正是为了去看别的，那时能按的只剩卡片。
     *
     * <p>没有传输在跑时照旧撤掉：{@code SendHost.canceller} 是静态的，不撤就等于把一个已经
     * 销毁的页面永久留在那里。有传输在跑时留着，等这一趟自己收尾再撤——见发送线程里那两处
     * {@code pageGone} 的判断。
     */
    @Override
    protected void onDestroy() {
        SendSession.closed();
        pageGone = true;
        if (!sending) {
            SendHost.unregister(cardCancel);
        }
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (preview()) {
            return;
        }
        // 顺序是有意义的：先敲门，再问路。BLE 广播是 iPhone 把 AirDrop 服务拉起来的那声
        // 敲门，mDNS 查询是问路。反过来的话，第一轮查询问的是一台还没开门的设备。
        wake();
        peers = new Peers(this, this);
        peers.start();
    }

    /**
     * 发那条叫醒 iPhone 的 AirDrop BLE 广播。
     *
     * <p>这一层不运任何数据，它只是让对端把 8770 端口上的服务打开。没有它，我们这边的
     * 查询会一路走到无线侧（抓包能看见，每两秒一帧，`_airdrop._tcp.local`、跳数 255、地址
     * 帧尾都对），回包一个也没有——因为对端"无线开着、服务没开"，而帧再怎么对也开不了一扇
     * 没装的门。
     *
     * <p>权限没给就先要，要到再发：{@code BLUETOOTH_ADVERTISE} 在 API 31+ 是运行时权限。
     * 用户拒绝也不拦着搜索——已经醒着的对端照样会答。
     */
    private void wake() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT}, ASK_BLE);
            return;
        }
        say(Native.bleAdvertise(this));
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] granted, int[] state) {
        super.onRequestPermissionsResult(request, granted, state);
        if (request != ASK_BLE) {
            return;
        }
        // 拒绝是允许的结局：这条路只是"叫醒"，不是"通信"。页面不会因此变成一张错误页。
        say(Native.bleAdvertise(this));
    }

    /**
     * 把广播的现状写进日志。
     *
     * <p>句子是 Rust 写的（{@code ble::explained}），这里只落笔：状态机在那边，话也该由
     * 那边说，否则同一个原因会有两种措辞，而人只会在其中一种里找线索。
     */
    private static void say(byte[] line) {
        if (line == null) {
            Log.w("OPAirDrop", "ble: (no state line)");
            return;
        }
        String text = new String(line, java.nio.charset.StandardCharsets.UTF_8);
        if (text.startsWith("failed: ")) {
            Log.w("OPAirDrop", "ble: " + text);
        } else {
            Log.i("OPAirDrop", "ble: " + text);
        }
    }

    /**
     * 调试用：{@code am start … -e preview 5} 时拿假设备把版式演出来，不真的搜索。
     *
     * <p>真机上凑不齐那么多台对端，而「一行放几个、在第几个折行、长名字会不会顶出格子」只有看着
     * 截图才改得动。只在 debug 包里认这个参数；release 包里这段是死的。
     */
    private boolean preview() {
        if (!BuildConfig.DEBUG) {
            return false;
        }
        int count = getIntent().getIntExtra("preview", 0);
        if (count <= 0) {
            return false;
        }
        // 名字故意长短不一：一格 86dp，装不下就该被省略号截掉，而不是把旁边那台挤走。
        String[] names = {"青", "张三的 iPhone 15 Pro", "MacBook Pro", "林子"};
        List<Peers.Peer> fake = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Peers.Peer peer = new Peers.Peer(names[i % names.length]);
            peer.host = "fake" + i + ".local";
            peer.address = "fe80::" + (i + 1);
            peer.port = 8770;
            fake.add(peer);
        }
        stage.postDelayed(() -> {
            found = fake;
            renderStage();
        }, 400);
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 这一页要走了，别再为一个已经没人看的格子排重算。
        if (stage != null) {
            stage.removeCallbacks(settleDue);
        }
        if (peers != null) {
            peers.stop();
            peers = null;
        }
        // 人离开了这一页，就别再叫对端起床：广播一直在，iPhone 会一直把 AWDL 和 AirDrop
        // 服务供着，那是白花的电，也不是我们的功能。
        Native.bleStop();
    }

    /**
     * 日夜切换：不重建 Activity，只换一张表再重画。
     *
     * <p>这里**不能**调 {@code recreate()}：那一瞬间正在传的那条线程还握着一个已经没人看的
     * 视图，进度会停住，而文件还在传——界面与事实分成两半。重画视图树没有这个问题，
     * 因为进度存在 {@link #progressOf} 里，视图只是把它画出来。
     */
    @Override
    public void onConfigurationChanged(Configuration next) {
        super.onConfigurationChanged(next);
        Scheme fresh = schemeOf(next);
        if (fresh.light == S.light) {
            return;
        }
        S = fresh;
        applyWindowBars();
        tintSheet();
        rebuild();
    }

    // ------------------------------------------------------------------ 窗口

    /**
     * 给面板装上那块会跟着它走的玻璃。
     *
     * <p>必须在 attach 之后：模糊 Drawable 由 {@code ViewRootImpl} 创建，而它是 attach 的产物。
     * {@link View#post} 恰好满足这一点——视图还没 attach 时，排进去的 Runnable 会等到 attach
     * 之后才跑。
     *
     * <p>拿不到就什么也不做，面板保持 {@link #sheetColor()} 那个不透明页底：一块实心的纸，
     * 不是玻璃，但读得清、拖得动。
     */
    private void installGlass() {
        PanelGlass panel = PanelGlass.attach(sheet);
        if (panel == null) {
            // 这台机器没有那两条隐藏路径。面板留在不透明页底上——能读、能拖，只是不是玻璃。
            Log.i("OPAirDrop", "panel glass: unavailable, solid background");
            return;
        }
        glass = panel;
        // 两条都靠反射，都可能被 ROM 静默拒掉；把结果打出来，省得下次又只能靠眼睛猜
        // 「圆角是不是没生效」。
        boolean round = panel.corners(dp(CORNER_DP), 0f);
        boolean blur = panel.radius(dp(BLUR_DP));
        // 只设模糊，不染色：叠色自己盖一层 {@link #tint}（理由见 buildShell 里的注释）。
        sheet.setBackground(panel.drawable());
        tintSheet();
        Log.i("OPAirDrop", "panel glass: " + panel.source()
                + " radius=" + dp(BLUR_DP) + (blur ? "" : "(rejected)")
                + " corner=" + dp(CORNER_DP) + (round ? "" : "(rejected)"));
    }

    /** 系统栏图标跟着深浅走。主题里那两条只在窗口创建时生效一次。 */
    private void applyWindowBars() {
        Window window = panel == null ? null : panel.getWindow();
        WindowInsetsController controller = window == null ? null : window.getInsetsController();
        if (controller == null) {
            return;
        }
        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(S.light ? mask : 0, mask);
    }

    /**
     * 面板自己的颜色：不透明页底。
     *
     * <p>它是**拿不到模糊时的退路**，也是玻璃装好之前那一瞬的底色。留透明没有意义——背后
     * 什么都没有，半透明只会让那一下闪出背后的界面。
     */
    private int sheetColor() {
        return S.bg;
    }

    /**
     * 叠在模糊之上的那层染色。
     *
     * <p>{@code BackgroundBlurDrawable.setColor} 画在模糊结果之上，alpha 决定「能透多少」。
     * 比导航胶囊的 240 更透：那一块背后是同色的卡片，240 还看得出层次；这一块背后是壁纸，
     * 遮到 240 就只剩一块白板。再薄就会让标题压到别人的图标上。
     */
    private int glassTint() {
        // 208/255。比导航胶囊的 240 透，因为背后是壁纸而不是同色卡片；又不能不透，白字要靠
        // 它压住浅色壁纸的亮部。深浅同值：透明度是结构，不是配色。
        return (S.bg & 0x00FFFFFF) | 0xD0000000;
    }

    /** 换配色时重刷染色层；没有玻璃时它就是不透明的页底。 */
    private void tintSheet() {
        if (tint == null) {
            return;
        }
        tint.setBackground(shape(glass != null ? glassTint() : sheetColor()));
    }

    /** 面板轮廓：上两角圆、下两角方——它是从底边长出来的一整块，底边在屏幕外面。 */
    private GradientDrawable shape(int color) {
        GradientDrawable shape = new GradientDrawable();
        float radius = dp(CORNER_DP);
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0f, 0f, 0f, 0f});
        shape.setColor(color);
        return shape;
    }

    // ------------------------------------------------------------------ 骨架

    private void buildShell() {
        sheet = new Sheet(this);

        float radius = dp(CORNER_DP);
        sheet.setBackground(shape(sheetColor()));

        // 圆角只有一个来源：这个裁剪。
        //
        // 模糊层（系统给的 Drawable）和染色层（{@link #tint}）之前各设了一套圆角，而
        // BackgroundBlurDrawable 只认单一半径、面板要的是「上圆下方」——两边对不上，屏幕上
        // 就是顶部两个角出现两条半径不同的弧。改成把 sheet 自己裁成那个轮廓，两层都不用再管
        // 圆角，也再不可能对不上。
        sheet.setClipToOutline(true);
        sheet.setOutlineProvider(new android.view.ViewOutlineProvider() {
            private final RectF box = new RectF();
            private final Path path = new Path();

            @Override
            public void getOutline(View view, Outline outline) {
                float corner = dp(CORNER_DP);
                box.set(0f, 0f, view.getWidth(), view.getHeight());
                path.reset();
                path.addRoundRect(box,
                        new float[]{corner, corner, corner, corner, 0f, 0f, 0f, 0f},
                        Path.Direction.CW);
                // 上圆下方是凸的，可以走 setConvexPath；Outline 自己的 setRoundRect 只能四角同值。
                outline.setConvexPath(path);
            }
        });

        // 染色层：盖在模糊之上、内容之下。
        //
        // 不用 BackgroundBlurDrawable.setColor 做这件事——那个方法在这台机器上没有任何效果
        // （模糊出来了，叠色没上，深色主题的白字直接落在浅色壁纸上）。自己盖一层同名同义的
        // 半透明页底，行为完全可控，也不再依赖那条反射是否被 ROM 实现。
        tint = new View(this);
        tint.setBackground(shape(sheetColor()));
        sheet.addView(tint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        sheet.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 一圈上亮下暗的描边，和导航胶囊同一条配方。没有它，玻璃的边界只是一处颜色突变。
        sheet.addView(new Rim(radius, dp(1.4f)), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 状态栏 / 导航条的高度进内边距，页面内容不压在系统栏底下。
        sheet.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            content.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        // 面板不交给 Activity 的窗口——它在 {@link #showPanel} 里被放进自己的 Dialog。
        rebuild();
    }
    /** 把内容整棵重建。配置变更、以及第一次搭骨架时都走这里。 */
    private void rebuild() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        nicks.clear();
        stateViews.clear();
        nameViews.clear();

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), 0, dp(20), 0);
        content.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(topBar());
        column.addView(subtitleLine());

        stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        // 设备列表从左上角排起，所以这一层不能被居中；空态的雷达自己带居中。
        stage.setGravity(Gravity.TOP);
        column.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(bottomCard());

        renderStage();
        // 上面换了 stage，旧 stage 上排着的那次「等名字到期」跟着一起没了（回调挂在视图上），
        // 不在这儿重排，那几台正在等名字的设备就要等到下一条 mDNS 才知道该上屏了。
        armSettle(System.currentTimeMillis());
    }

    /**
     * 先藏起来，等量出高度，再挪到屏幕下方滑上来。
     *
     * <p>不用 {@code onPreDraw} 里 {@code return false} 那一招（取消首帧、下一帧才是动画的
     * 第一帧）：真机上它把整个页面停在了一件都没画的状态——首帧被取消，动画的 invalidate
     * 又被吞掉，窗口有 surface、{@code HAS_DRAWN}，屏幕上却只有背后的桌面。窗口本身是透明
     * 的，所以「先不可见一帧」看不出任何东西，代价为零。
     */
    private void slideIn() {
        sheet.setVisibility(View.INVISIBLE);
        sheet.post(() -> {
            int height = sheet.getHeight();
            place(height > 0 ? height : dp(600));
            sheet.setVisibility(View.VISIBLE);
            layer(true);
            slideTo(offset(), 0f, ENTER_MS, new DecelerateInterpolator(1.6f),
                    () -> layer(false));
        });
    }

    /**
     * 把面板从当前位置动画到 {@code to}。
     *
     * <p>用 {@link ValueAnimator} 而不是 {@code sheet.animate()}：后者只会写 {@code translationY}，
     * 而位移必须走 {@link #place}（真正的 top），否则模糊不跟着面板走。
     */
    private void slideTo(float from, float to, int duration, android.animation.TimeInterpolator curve,
                         Runnable end) {
        cancelSlide();
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(duration);
        animator.setInterpolator(curve);
        animator.addUpdateListener(frame -> place((Float) frame.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (slide == animator) {
                    slide = null;
                }
                if (end != null) {
                    end.run();
                }
            }
        });
        slide = animator;
        animator.start();
    }

    /** 停掉正在跑的位移。按下时要做的第一件事就是它。 */
    private void cancelSlide() {
        if (slide != null) {
            ValueAnimator running = slide;
            slide = null;
            running.cancel();
        }
    }

    /**
     * 把面板提升成一个硬件层，或还回去。
     *
     * <p># 为什么位移必须走层
     *
     * <p>{@code setTranslationY} 不是一次「搬动」，它会让这个 View 连同整棵子树重新绘制一遍：
     * 每一帧都要重新栅格化这块 1440×3168 上的全部文字、再走一遍那条全屏圆角描边路径。文字在
     * 每一帧落到略微不同的亚像素位置上，抗锯齿结果就跟着变——肉眼看到的就是**字在抖**。
     *
     * <p>层之后，内容只栅格化一次，之后每帧只是把这张纹理挪个位置，由合成器完成。
     *
     * <p># 为什么在按下时就建，而不是第一帧移动时
     *
     * <p>建层本身要渲染一遍整棵树、再分配一张全屏纹理——几十毫秒。放在移动的第一帧做，那几
     * 十毫秒就变成了「手指先动了、面板没跟上」。按下到开始移动之间通常有几十毫秒的空档，正好
     * 用掉它：那一刻画面还没位移，慢一点也看不出来。
     */
    private void layer(boolean on) {
        sheet.setLayerType(on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
        // 建层的那一刻就停雷达，不是等第一次 MOVE。
        //
        // 层里的任何一次 invalidate 都要把整块 1440×3168 的纹理重画一遍。雷达每 32ms 叫一次，
        // 于是只要「层已建好、雷达还在转」，就已经是每秒三十次全屏重绘——拖动再叠上来，读起来
        // 就是整个界面在抖。按下到第一次移动之间通常有几十毫秒，正好够它抖一下。
        dragging = on;
        if (!on) {
            // 还回层之后把雷达叫醒。
            //
            // 只 invalidate 面板不够：层里的子 View 各自有一份已经录好的显示列表，父容器重放
            // 一遍并不会重新调它们的 onDraw，于是雷达那个「画完再排下一帧」的自我续命就没被
            // 挂回去——手指抬起来了，环却停在上一次画到的那一圈。见 {@link Radar#wake}。
            sheet.invalidate();
            if (radarView != null) {
                radarView.wake();
            }
        }
    }

    /** 正在拖动 / 正在做位移动画。它决定雷达要不要每 32ms invalidate 一次。 */
    private boolean dragging;

    /**
     * 此刻屏上那个雷达。空态才有，设备列表一出来就没有了。
     *
     * <p>留一个引用是为了「叫醒」这条唯一的路：{@link #layer} 停下它之后，能把帧循环挂回去的
     * 只有发起停下的那个人，而它手上只有这个字段。
     */
    private Radar radarView;

    // ------------------------------------------------------------------ 顶部

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(14), 0, dp(10));

        // 返回键：圆底 + 正中央的箭头。用图片而不是「‹」字形——字形在不同字体里重心偏上偏左，
        // 看着总不像居中；矢量箭头几何重心就在视口中心，配 Gravity.CENTER 才是真的居中。
        // 圆底 75% 透明：和下面那张说明卡同一档透度，页面是一整块玻璃而不是几块实心补丁。
        FrameLayout back = new FrameLayout(this);
        back.setBackground(round(alpha(S.card, 0.75f), 22));
        android.widget.ImageView arrow = new android.widget.ImageView(this);
        arrow.setImageResource(R.drawable.ic_back);
        // 用文字色给箭头上色，深浅色主题都跟得上；写死白色在浅色下会看不见。
        arrow.setColorFilter(S.text);
        arrow.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        back.addView(arrow, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        back.setOnClickListener(view -> close());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);

        TextView title = new TextView(this);
        title.setText("与 iPhone 互传");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(S.text);
        titles.addView(title);

        TextView self = new TextView(this);
        self.setTextSize(12.5f);
        self.setTextColor(S.textDim);
        self.setPadding(0, dp(3), 0, 0);
        self.setText("本设备：" + senderName() + "（Air）");
        titles.addView(self);

        bar.addView(titles);
        return bar;
    }

    /** 标题下面那一行：发了什么。 */
    private View subtitleLine() {
        subtitle = new TextView(this);
        subtitle.setTextSize(12.5f);
        subtitle.setTextColor(S.textDim);
        subtitle.setPadding(dp(2), 0, 0, dp(6));
        subtitle.setText(subtitleText);
        return subtitle;
    }

    // ------------------------------------------------------------------ 中部

    /** 按当前状态（搜索 / 有设备）重画中部。 */
    private void renderStage() {
        if (stage == null) {
            return;
        }
        stage.removeAllViews();
        // 上一帧的雷达已经随 removeAllViews 脱离窗口，它的帧循环由 onDetachedFromWindow 停掉；
        // 这里把引用也放掉，免得还回去的时候叫醒一个不在屏上的。
        radarView = null;
        nicks.clear();
        stateViews.clear();
        nameViews.clear();

        if (found.isEmpty()) {
            // 空态自己居中：外面这一层已经改成顶部对齐，留给设备列表从左上角排。
            LinearLayout middle = new LinearLayout(this);
            middle.setOrientation(LinearLayout.VERTICAL);
            middle.setGravity(Gravity.CENTER);

            Radar radar = new Radar();
            radarView = radar;
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(180), dp(180));
            size.gravity = Gravity.CENTER_HORIZONTAL;
            middle.addView(radar, size);

            TextView hint = new TextView(this);
            hint.setText(searching);
            hint.setTextSize(14);
            hint.setTextColor(S.textDim);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(20), 0, 0);
            middle.addView(hint);

            stage.addView(middle, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
            return;
        }

        // 从文件信息下面那行的左上角开始，向右排，排满一行折到下一行。行首对齐，不整行居中。
        //
        // 这条对齐规则是数出来的，不是审美：格子宽度取「整行平分」，即 320dp 可用宽度分三格，
        // 每格 106dp。于是
        //   一台   —— 第一格，靠左；
        //   两台   —— 第二台的中心正好落在屏幕中线上（20dp + 一格半 = 屏宽一半）；
        //   三台   —— 第一台与第三台到中线的距离相等，互为镜像。
        // 如果改整行居中，两台时整行会整体右移半格，第二台就离开中线了——那是不对的。
        //
        // 用平分而不是死板的 86dp：三台 86dp 塞进 320dp，右边会空出小半屏，看着像没排完。
        int span = getResources().getDisplayMetrics().widthPixels - dp(40);
        int columns = Math.max(1, span / dp(CELL_DP));
        int cellW = span / columns;
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setGravity(Gravity.START);
        LinearLayout row = null;
        for (int i = 0; i < found.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                // 顶对齐：名字有的占一行有的占两行，居中会让同一行的头像高低错开。
                row.setGravity(Gravity.TOP);
                rows.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            row.addView(deviceCell(found.get(i)), new LinearLayout.LayoutParams(
                    cellW, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // 设备可能比这一屏多（一间办公室里同时开着十几台）。能滚，但不越过边框。
        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.addView(rows, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        stage.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onPeers(List<Peers.Peer> peers, String problem) {
        reachable = peers;
        searching = problem != null ? problem : "正在搜索附近设备……";
        long now = System.currentTimeMillis();
        settle(now);
        probeNames(now);
    }

    /**
     * 排一次「窗口到期就重算」，时间是还没上屏的那几台里最近的那个期限。
     *
     * <p>只给还在窗口里的排：窗口已经过的那台在 {@link #settle} 里必然被放上屏，再为它排一次
     * 就是排一个 {@code 0 毫秒} 的即时任务，而重算又会为它再排一次——那就成了空转。
     *
     * <p>每次都是「删掉旧的、按最早的期限重排」，晚到的设备不会把先到的那台往后推。
     */
    private void armSettle(long now) {
        stage.removeCallbacks(settleDue);
        long due = Long.MAX_VALUE;
        for (Peers.Peer peer : reachable) {
            if (!peer.connectable() || known(peer) != null) {
                continue;
            }
            long window = peer.readyAt + NAME_HOLD_MS;
            if (window > now) {
                due = Math.min(due, window);
            }
        }
        if (due != Long.MAX_VALUE) {
            stage.postDelayed(settleDue, due - now);
        }
    }

    /**
     * 按「能连的」算出「该上屏的」，然后画。
     *
     * <p>两张表分开，是因为名字要连上去问才知道，而人在屏上看到的是「一格头像 + 一行名字」。
     * 以前这两件事同时发生：格子画出来的那一刻名字还没有，于是先写型号，一两秒后再被真名换
     * 掉——2026-11-18 的现场是这样：
     *
     * <pre>
     *   iPhone 出现了，下方先写「附近设备」，随即被真名替换成真名
     * </pre>
     *
     * <p>「附近设备」是最坏的一种占位：它连「这是台 iPhone」都没说出来，而且换的那一下，宽度
     * 和高度都可能变，看着像列表在抖。既然真名几乎一定到（{@link #NAME_HOLD_MS} 说的就是多
     * 久），就别先画一个假的——等名字到了，头像和名字一起出现。
     *
     * <p>等不到也不能让设备隐形：过了那一小段时间照型号显示，这是「问了它不答」唯一的出路。
     */
    private void settle(long now) {
        List<Peers.Peer> usable = new ArrayList<>();
        for (Peers.Peer peer : reachable) {
            if (!peer.connectable()) {
                continue;
            }
            if (known(peer) == null && now - peer.readyAt < NAME_HOLD_MS) {
                continue;
            }
            usable.add(peer);
        }
        found = usable;

        // 设备集合没变就不重建：重建会把正在跑的那圈进度丢掉一帧，而进度每秒来两次。
        if (!nicks.keySet().equals(namesOf(usable))) {
            renderStage();
        }

        // 名字可能在这一轮才到（型号、缓存、刚问回来的真名），而设备集合没变——那种情况下
        // 不重建整屏，只把那一行字换掉。
        for (Peers.Peer peer : usable) {
            setLabel(nameViews.get(peer.name), label(peer));
        }

        // 还在等名字的那几台，到点得有人再来算一次：报文不一定来，见 {@link #armSettle}。
        armSettle(now);
    }

    /**
     * 缓存里没有名字的设备，后台问一次 {@code /Discover}。
     *
     * <p>按稳定地址去重：hash 与 UUID 每次广播都变，用它们当键会让同一台设备每秒重查一遍。
     * 问不到的地址留个时刻，隔 {@link #NAME_RETRY_MS} 再试——问一次要连 TLS，但一直不试就等
     * 于这格子永远没名字。
     *
     * <p>这一圈走的是**能连的全部**，不只是上屏的那些：正因为还在等名字的那几台没上屏，它们
     * 才最需要这一问。反过来的话（只问已经上屏的），等待就永远结束不了。
     */
    private void probeNames(long now) {
        for (Peers.Peer peer : reachable) {
            if (!peer.connectable()) {
                continue;
            }
            String key = PeerNames.key(peer);
            if (key == null || PeerNames.get(peer) != null || realNames.containsKey(peer.name)) {
                continue;
            }
            Long tried = probed.get(key);
            if (tried != null && now - tried < NAME_RETRY_MS) {
                continue;
            }
            probed.put(key, now);
            probeName(peer);
        }
    }

    /**
     * 一台设备在界面上叫什么。
     *
     * <p>三道来源，一道比一道准：本次会话问到的真名 → 上次按稳定地址存下的真名 → 占位。
     *
     * <p>**占位从来不是实例名**。以前直接画 {@code peer.name}，而 iPhone 的实例名是一串每
     * 分钟换一次的 hash：打开发送面板先看见乱码，一两秒后它自己变成真名。那一下闪动既没有
     * 信息，也让人以为列表出错了。现在占位用型号（{@link Peers.Peer#kind()}）——「一台
     * iPhone」说得对，而且它和真名的差别只是前缀，换上去时宽度不会突然翻倍。
     */
    private String label(Peers.Peer peer) {
        String known = known(peer);
        return known == null ? peer.kind() : known;
    }

    /**
     * 这台设备的真名，知道的话；不知道返回 {@code null}。
     *
     * <p>两道来源，都是「对方自己说的」：本次会话里 {@code /Discover} 刚问到的，或上次按稳定
     * 地址存下的。{@link #label} 在场，{@link #onPeers} 拿它决定这一台该不该上屏——所以它单独
     * 一个方法，两处必须是同一个答案。
     */
    private String known(Peers.Peer peer) {
        String name = realNames.get(peer.name);
        return name != null ? name : PeerNames.get(peer);
    }

    /**
     * 换掉格子上那行名字，换的时候淡一下。
     *
     * <p>淡进而不是直接跳：占位变真名是这一屏上唯一会发生的一次字变，跳变看着像掉帧。
     * 180 毫秒足够看出「它刚刚认出了对方」，又不至于让人等。
     */
    private static void setLabel(TextView view, String wanted) {
        if (view == null || wanted == null || wanted.contentEquals(view.getText())) {
            return;
        }
        view.setText(wanted);
        view.animate().cancel();
        view.setAlpha(0.3f);
        view.animate().alpha(1f).setDuration(180).start();
    }

    /** 表里现在这一台。设备可能被清掉又重新加回来，那时地址、端口都该用新的。 */
    private Peers.Peer current(String name) {
        for (Peers.Peer peer : found) {
            if (peer.name.equals(name)) {
                return peer;
            }
        }
        return null;
    }

    private static java.util.Set<String> namesOf(List<Peers.Peer> peers) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (Peers.Peer peer : peers) {
            names.add(peer.name);
        }
        return names;
    }

    /** 一台设备：圆头像（带进度环）+ 名字 + 状态。 */
    private View deviceCell(Peers.Peer peer) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(5), 0, dp(5), dp(14));

        Nick nick = new Nick();
        cell.addView(nick, new LinearLayout.LayoutParams(dp(NICK_DP), dp(NICK_DP)));

        TextView name = new TextView(this);
        // 真名两条来源：本次会话里 /Discover 刚问到的，或上次存下的（按稳定地址查）。
        // 都没有就写型号——不写实例名，理由见 {@link #label}。
        name.setText(label(peer));
        name.setTextSize(12.5f);
        name.setTextColor(S.text);
        name.setGravity(Gravity.CENTER);
        // 名字换行，不硬截成一行：格子就 106dp 宽，「张三的 iPhone 15 Pro」这类名字截一行只剩
        // 「张三的 iP…」，认不出是谁。两行封顶，还不下才省略——一台设备不该把下面顶出屏幕。
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setLineSpacing(0, 0.95f);
        name.setPadding(0, dp(6), 0, 0);
        cell.addView(name);

        TextView state = new TextView(this);
        state.setTextSize(10.5f);
        state.setTextColor(Boolean.TRUE.equals(statusAlarms.get(peer.name)) ? S.red : S.accent);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dp(2), 0, 0);
        String known = statuses.get(peer.name);
        state.setText(known == null ? "" : known);
        cell.addView(state);

        nicks.put(peer.name, nick);
        stateViews.put(peer.name, state);
        nameViews.put(peer.name, name);
        // 重建之前就已经在传的那台，环要接着上一次的位置画，而不是从 0 开始。
        Float progress = progressOf.get(peer.name);
        if (progress != null) {
            nick.progress(progress);
        }

        // 一次只跑一个传输，所以「正在传」时点任何一台都当作取消：那时页面上没有第二件事
        // 可以开始，而人点下去的意思只可能是「停下」。
        //
        // 点的时候回表里取一次这一台：对端可能已经被清掉又加回来了（一次广播空白就够了），
        // 而这个格子抓着的是旧对象，地址端口都可能是上一轮的。
        cell.setOnClickListener(view -> {
            if (sending) {
                cancelSend();
                return;
            }
            Peers.Peer live = current(peer.name);
            send(live != null ? live : peer);
        });
        return cell;
    }

    /**
     * 停下正在跑的那一次。
     *
     * <p>关 socket 而不是发信号：传输线程此刻多半停在 {@code read} 上等对端回话，唯一能把它
     * 叫醒的只有「这条连接没了」。异常会在发送线程里抛出，走和失败同一条收尾路径，只是说法
     * 不同——{@link #cancelling} 记着这次是谁做的决定。
     *
     * <p># 三件容易写漏的事
     *
     * <p>**一、{@code cancelling} 要先置，哪怕没有连接可关。** 原来这里是
     * {@code if (live == null) return;} 打头，等于「还没连上时点取消什么也不算」。从点下那台
     * 设备到第一条连接建立，中间要过一次 DNS/SRP 查询、一次 TCP、一次 TLS 握手——2–5 秒。
     * 这个窗口里人点了取消：{@code cancelling} 没置、socket 没有、{@code sending} 还是 true，
     * 于是屏幕上没有反应，人也发不出第二次（{@link #send} 开头就被 {@code sending} 挡回去）。
     * 现在先把话记下，传输线程走到下一个检查点（{@link #transfer} 开头、{@link #open} 建完、
     * {@code /Ask} 换连接）就会自己中止。
     *
     * <p>**二、close 不能在主线程做。** 这个方法是视图点击进来的，也就是主线程；而
     * {@code live} 是 {@code SSLSocket}，它的 {@code close()} 会先写一个 TLS {@code close_notify}
     * ——一次真实的网络写。{@link HangUp#now} 把它挪到后台线程，并且用 {@code SO_LINGER(0)}
     * 让它发的是 RST 而不是 FIN：对面 iPhone 该看到「这次传被中止了」，而不是「传完了」。
     *
     * <p>**三、{@code live} 在换连接的空档里指的还是上一条。** {@code /Discover} 那条答完就
     * 作废，{@code second} 会先关掉它再新建一条。若取消正好落在这一瞬，关掉的是一个已经关掉的
     * socket，新那条却照常建起来继续传。兜底还是上面那句 {@code cancelling}：{@link #open} 建完
     * 连接会再看一眼它。
     */
    private void cancelSend() {
        if (!sending) {
            return;
        }
        cancelling = true;
        Log.i(TAG, "send cancelled by the person");
        HangUp.now(live);
    }

    /**
     * 一趟发送结束后的收尾：三个「正在跑」的标记一起放回空闲。
     *
     * <p>两个收尾点（成功、失败）原来各写一遍这三行。分开写的问题不是重复，而是**两处都少了
     * 同一件事**：页面在发送途中被划走时，{@link #onDestroy} 特意没有摘掉 {@link #cardCancel}
     * （摘了卡片上那颗【取消发送】就成了一颗不响的按钮），于是这件事必须由「传输真的结束」
     * 这一刻来做，否则 {@link SendHost} 会一直握着一个已销毁的页面。
     */
    private void settle() {
        sending = false;
        live = null;
        cancelling = false;
        if (pageGone) {
            SendHost.unregister(cardCancel);
        }
    }

    // ------------------------------------------------------------------ 底部

    /** 说明卡：和参照一样，告诉人为什么对面还没出现。 */
    private View bottomCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        // 75% 透明：背后是玻璃面板，卡片只压暗一点点，不再是一块实心补丁。
        card.setBackground(round(alpha(S.card, 0.75f), 22));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(20);
        card.setLayoutParams(params);

        TextView head = new TextView(this);
        head.setText("iPhone 那边要做什么？");
        head.setTextSize(15);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(S.text);
        card.addView(head);

        TextView body = new TextView(this);
        // 只说 iPhone 上真正需要动的那两件事。它不需要打开任何分享面板，也不需要装任何东西：
        // AirDrop 在「所有人」状态下自己会在这条直连链路上应答。
        body.setText("① 控制中心把 AirDrop 设为「所有人」，10 分钟内有效\n"
                + "② 屏幕保持解锁亮着——锁屏后 iPhone 会暂时离开这条直连链路");
        body.setTextSize(12.5f);
        body.setTextColor(S.textDim);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(8), 0, 0);
        card.addView(body);
        return card;
    }

    // ------------------------------------------------------------------ 传输

    private void intake(Intent source) {
        ShareIntake.Batch batch = ShareIntake.resolve(this, source);
        runOnUiThread(() -> {
            files.clear();
            files.addAll(batch.files);
            totalBytes = 0;
            for (File file : files) {
                totalBytes += file.length();
            }
            Log.i(TAG, "intake: " + files.size() + " file(s), " + totalBytes + " bytes"
                    + (batch.problem == null ? "" : ", problem: " + batch.problem));
            subtitleText = files.isEmpty()
                    ? (batch.problem == null ? "没有可发送的内容" : batch.problem)
                    : files.size() + " 个文件 · " + size(totalBytes);
            if (subtitle != null) {
                subtitle.setText(subtitleText);
            }
        });
    }

    private void send(Peers.Peer peer) {
        if (sending) {
            return;
        }
        if (files.isEmpty()) {
            status(peer, "没有可发送的内容");
            return;
        }
        // 先起名字，再置 {@link #sending}：反过来的话，中间那一瞬 sending 已经是 true 而键
        // 还是上一次那个，卡片上的取消会越过 cardCancel 里「是不是这一次」的判断。
        sendSession = UUID.randomUUID().toString();
        sending = true;
        // 上一次按过取消、而那一次又没能走到收尾（线程卡在握手、页面被系统收回），
        // {@code cancelling} 就会留着 true。不在这里清掉，这一趟刚起跑就会在
        // {@link #open} 里被自己判成「人已经取消了」——一次无辜的秒失败，而且看起来
        // 像「点发送完全没反应」。
        cancelling = false;
        SendSession.began();
        Log.i(TAG, "send to " + peer.name + " — " + peer.address + ":" + peer.port
                + " host=" + peer.host + ", " + files.size() + " file(s)");
        status(peer, "正在连接…");
        // 失败时卡上那颗【重试】要落回这里。把「再发一遍」记成动作本身，而不是记一对参数：
        // 这一次要发的东西全在 {@link #files} 里、发去哪全在这个 peer 上，写第二份就是第二个
        // 会过期的副本。
        SendHost.remember(() -> send(peer));
        // 也从这一刻起卡片接手这块屏幕。两边的数字取自下面同一个进度回调，不各算一遍。
        // 标题上的名字用 {@link #label}：这里多半还只有「iPhone」，握手那一步会把它换成真名。
        SendHost.begin(sendSession, label(peer), files.size() + " 个文件");

        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }

        new Thread(() -> {
            // 这一趟走到哪儿了。写在对象里是因为它由进度回调填、由 catch 读。
            //
            // 两件事必须分开记：{@code moving} 是「对面已经点了接受」（进度相位进了 SENDING），
            // 它决定超时那句该说「没有接受」还是「不再回应」；{@code bytes} 是「有多少字节真的
            // 写进了那条 socket」，它决定中断那句该不该怪环境。前者推不出后者——刚进 SENDING
            // 相位时可能一个字节都还没写出去；后者也推不出前者，因为 setSoTimeout 那一下
            // 看的就是相位。
            Flight flight = new Flight();
            try {
                byte[] frame = transfer(peer, paths, flight);
                Report report = Report.parse(
                        new String(frame, java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    settle();
                    SendSession.ended();
                    // 环褪回未开始：留着满圈会读成「还在跑」，而它已经跑完了。
                    progressOf.remove(peer.name);
                    redrawProgress(peer.name);
                    status(peer, "已发送 · " + size(report.bytes));
                    // 终态。卡片只在 SUCCESS 上放成功的动画，所以这个码不能换成别的。
                    SendHost.finished(sendSession, peer.name, SendHost.SUCCESS,
                            files.size() + " 个文件 · " + size(report.bytes));
                });
            } catch (Exception failed) {
                // 先把这个决定记下来：下面那段 lambda 里 cancelling 会被清掉，那时再问就
                // 只会得到 false，标题永远是「发送失败」。
                boolean stopped = cancelling;
                if (stopped) {
                    // 人按下的停不是故障。这条走 I 而不是 W：日志是要拿去读的，一个 W 会把
                    // 「他说不要了」读成一次事故。异常名照留——它是「确实断在这条 socket 上」
                    // 的证据（HangUp 关掉之后，这里必然是 Socket is closed）。
                    Log.i(TAG, "send stopped: " + peer.name + " — " + failed);
                } else {
                    Log.w(TAG, "send failed: " + peer.name, failed);
                }
                // 对端说「不收」是这条链上唯一一种不算故障的失败：连接通了、TLS 握手成了、
                // /Discover 回了 200、连名字都拿到了，缺的只有那台手机上的一档设置。所以
                // 它有自己的说法——英文那一句（"… is not accepting files"）留给日志，页面
                // 上要说的是该去改什么。类型是 Rust 那边给的（status::REFUSED 对应
                // RefusedException），不是在这里比对文案：比文案判定了，改一次措辞就会
                // 悄悄退化成「发送失败」。
                boolean refused = failed instanceof RefusedException;
                // 第三种「不算故障」的失败：对面出示的证书不是它广播的那一份。和「不收」一样，
                // 该说的不是「发送失败」，而是「对面不是它说的那台机器、已经停了」。类型由
                // {@link PeerMismatch} 给，理由同上：文案会改，类型不会。
                boolean impostor = failed instanceof PeerMismatch;
                // 等不到回答。分成两档说，是因为人要做的事不同：还没走字节，等的是对面手机
                // 上那一下「接受」；走了一半才超时，是对面不再读这条连接了。
                boolean timedOut = failed instanceof SocketTimeoutException;
                String message = stopped ? "已取消"
                        : refused ? "对方拒绝"
                        : impostor ? "对方身份不符"
                        : timedOut ? (flight.moving ? "对方不再回应" : "对方没有接受")
                        // 原文（"Connection reset" 之类）只进日志：页面那行是给站在两台手机
                        // 中间的人看的，他要知道的是「还得再试一次」，不是异常类名。
                        : "连接被中断";
                // 「谁、为什么、去改哪里」这句是给「手机在别人手里」那一刻写的，所以只点名
                // 设置和屏幕。名字那一截留到主线程再拼：真名是握手时问回来的，而这里是发送
                // 线程——在这一侧读 realNames 会和一个正在写的 UI 线程撞上，而且读到的多半
                // 还是发送开始前那个占位。标题不在这里写：那一句由 {@link SendHost#label}
                // 按状态码给，两处各写一份就会有两套说法。
                runOnUiThread(() -> {
                    settle();
                    SendSession.ended();
                    progressOf.remove(peer.name);
                    redrawProgress(peer.name);
                    status(peer, message, refused);
                    String who = label(peer);
                    // 副行只说人做的那个动作，不再补「也许对面根本没看见询问」。补了就是两件事
                    // 并列摆着，让一句已经确定的话听起来像猜测——401 是 iPhone 自己回的，
                    // 它就是在问过之后被拒的。要改设置、要亮屏那类指导在页面底部的说明卡里，
                    // 那里才是一份「对方没出现该去动哪里」的清单。
                    //
                    // 取消这一支不进卡片（{@link SendHost#finished} 直接撤卡），这里留着只因为
                    // 它是这段分支的完整一张表：漏了它，下面那个兜底会把「再点一次设备重试」
                    // 塞给一个刚刚说了不要的人——2026-11-19 15:07 那一版就是这么错的。
                    String detail = stopped
                            ? who + " 按下取消，这一趟没有发完"
                            : refused
                            ? who + " 拒绝了这次发送"
                            : impostor
                            ? message + " · 已中止，没有发出任何内容。对方的证书若是刚换过，重新搜索一次设备再试"
                            : timedOut
                            ? who + (flight.moving
                                    ? " 不再回应 · 保持亮屏、别切走隔空投送，再点一次设备重试"
                                    : " 没有接受 · 弹窗出现后十五秒内点「接受」；"
                                            + "没有弹窗就把隔空投送改成「所有人（10 分钟）」并保持亮屏")
                            // 走过字节的中断和零字节的中断，人要做的不是同一件事，所以这里
                            // 按「有没有真的发出去过」分流，和接收侧 {@code Listener.because}
                            // 是同一套判据、同一个道理（那边是「有没有真的收下来」）。
                            //
                            // 那句「保持两台设备都亮屏、隔空投送不切走」是给**故障**写的：连
                            // 都没连上、一个字节都没动，最可能就是环境。已经在流字节的连接断
                            // 在中途，多半是对面在手机上按了停、或者那台手机把屏幕关了——
                            // 这时候劝人再去调环境，等于替对面那次「停」编一个不存在的理由。
                            // 所以只说发生了什么：发了多少、没发完。**刻意不点名是谁停的**，
                            // 从我们这侧看，「对面取消」和「对面断网」长得一模一样。
                            : flight.bytes > 0
                                    ? "传输在中途被中断，已发出 " + size(flight.bytes) + "，没有发完"
                                    : message + " · 保持两台设备都亮屏、隔空投送不切走，"
                                            + "再点一次设备重试";
                    // 各有各的码：人按了取消是 1009，对面不收是 1008，等不到回答是 1010，
                    // 其余（含「对方身份不符」「连接被中断」）都是失败。卡片按这个码选动画
                    // 和那行字，不读上面那几个布尔——词表只有这一份。
                    SendHost.finished(sendSession, peer.name,
                            stopped ? SendHost.CANCELLED
                                    : refused ? SendHost.REFUSED
                                    : timedOut ? SendHost.TIMEOUT
                                    : SendHost.FAILED,
                            detail);
                });
            }
        }, "opairdrop-send").start();
    }

    private byte[] transfer(Peers.Peer peer, List<String> paths, Flight flight) throws Exception {
        // 人可能在连上之前就把这一趟取消了（卡片上的【取消发送】从「准备中」就在）。这时
        // 还去建连接，是拿几秒钟的握手换一次立刻作废——对面手机上会白弹一次邀请。
        if (cancelling) {
            throw new IOException("cancelled before connecting");
        }
        // 每次投送都重读一次表，和接收端一样：改完设置不必重启应用，下一次点设备就是新值。
        SettingsScreen tuned = Tuning.read(this);
        int connectMs = (int) Tuning.ms(tuned, "client.connect_timeout_ms", CONNECT_TIMEOUT_MS);
        int readMs = (int) Tuning.ms(tuned, "client.read_timeout_ms", READ_TIMEOUT_MS);
        int uploadReadMs =
                (int) Tuning.ms(tuned, "client.upload_read_timeout_ms", UPLOAD_READ_TIMEOUT_MS);
        // 上一次记日志时的十分位。只用来去重，跨传输复用没有意义，所以是局部变量。
        int[] quarter = {-1};

        // 第一条：/Discover 走这里，答完就作废。
        SSLSocket[] held = {open(peer, connectMs, readMs)};

        // 第二条：/Ask 和 /Upload 走这里。谁来换、什么时候换由 Rust 定——它在 /Discover
        // 与 /Ask 之间调一次 {@code connect}——这里只负责「再连一次」这件事本身。
        //
        // 为什么非换不可：iPhone 一条连接只读一个请求。答完 /Discover 之后它就不读了，而
        // socket 还开着，于是写进同一条连接的 /Ask 换回来的不是 401、不是 reset、不是关闭，
        // 是什么都没有。本仓为此白等了整整一个读超时，界面停在「正在请求发送…」。
        Native.Link second = () -> {
            if (cancelling) {
                // 人已经点了取消。再连一条只会让取消慢一步，所以让传输就地从这里失败。
                throw new IOException("cancelled before the second connection");
            }
            Log.i(TAG, "second connection for /Ask");
            closeQuietly(held[0]);
            held[0] = null;
            SSLSocket next = open(peer, connectMs, readMs);
            held[0] = next;
            return next;
        };

        try {
            return Native.sendWithProgress(
                    peer.host.isEmpty() ? peer.address : peer.host,
                    settingsText(),
                    senderName(),
                    modelName(),
                    senderId(),
                    UUID.randomUUID().toString(),
                    paths.toArray(new String[0]),
                    held[0].getInputStream(),
                    held[0].getOutputStream(),
                    second,
                    new Native.Progress() {
                        @Override public void name(String peerName) {
                            // Rust 在发送线程上回调；改视图必须回到主线程。
                            runOnUiThread(() -> showRealName(peer, peerName));
                        }

                        @Override public void report(int phase, long sent, long total) {
                        if (phase == Native.Progress.SENDING) {
                            // 从这一刻起等的不再是「对方要不要」，而是「那个档案收完没有」
                            // ——人要先在对面手机上点接受，档案才可能开始落地。两个等待
                            // 该给的耐心不一样：弹窗十五秒内就该有结果，点了接受之后不设
                            // 上限会让人以为卡住。
                            flight.moving = true;
                            try {
                                held[0].setSoTimeout(uploadReadMs);
                            } catch (Exception noTimeout) {
                                Log.w(TAG, "cannot lengthen the read timeout for the archive",
                                        noTimeout);
                            }
                        }
                        // 只往后两个相位记字节数：等待相位送进来的 0 会把已经走过的量抹掉，
                        // 而这两个相位是单调的，后来的值不会比先前小。
                        if (phase == Native.Progress.SENDING || phase == Native.Progress.DONE) {
                            flight.bytes = sent;
                        }
                        runOnUiThread(() -> {
                            switch (phase) {
                                case Native.Progress.READY -> {
                                    Log.i(TAG, "phase ready (handshake done, asking)");
                                    status(peer, "已连接，正在请求发送…");
                                    SendHost.waiting(sendSession, peer.name, "已连接，正在请求发送…");
                                }
                                case Native.Progress.WAITING -> {
                                    Log.i(TAG, "phase waiting (the person has been asked)");
                                    status(peer, "等待对方接受…");
                                    SendHost.waiting(sendSession, peer.name, "等待对方接受…");
                                }
                                case Native.Progress.SENDING -> {
                                    progressOf.put(peer.name,
                                            total == 0 ? 0f : Math.min(1f, (float) sent / total));
                                    redrawProgress(peer.name);
                                    String count = "发送中 " + (total == 0 ? 0 : sent * 100 / total) + "%";
                                    status(peer, count);
                                    // 卡片上的百分比走胶囊，第二行给的是字节数：同一句「45%」在
                                    // 两个地方各写一遍，晚一帧就是两个数。
                                    // 胶囊标题用真名（/Discover 带回的那个），不是 hash。
                                    SendHost.progress(sendSession, label(peer),
                                            total == 0 ? -1 : (int) (sent * 100 / total),
                                            files.size() + " 个文件" + (total == 0 ? ""
                                                    : " · " + size(sent) + " / " + size(total)));
                                    // 回调每秒来几十上百次。只在一成、两成……这些整点上留痕，
                                    // 否则一个 200 MB 的文件能把整段日志冲掉。
                                    int step = total == 0 ? -1 : (int) (sent * 10 / total);
                                    if (step != quarter[0]) {
                                        quarter[0] = step;
                                        Log.i(TAG, "phase sending " + (total == 0 ? "?"
                                                : sent * 100 / total + "%") + " (" + sent
                                                + "/" + total + " bytes)");
                                    }
                                }
                                case Native.Progress.DONE -> {
                                    Log.i(TAG, "phase done (all bytes written, waiting for the report)");
                                    progressOf.put(peer.name, 1f);
                                    redrawProgress(peer.name);
                                    status(peer, "已发送，等待确认…");
                                }
                                default -> {
                                }
                            }
                        });
                        }
                    });
        } finally {
            // 关最后那一条。中途换过连接的话，前一条已经在 second 里关掉了——那条连接上
            // 已经答过一个请求，留着它没有任何用处。
            closeQuietly(held[0]);
            held[0] = null;
        }
    }

    /**
     * 连一次，握手完，返回一条能收发字节的 TLS 连接。
     *
     * <p>{@link #live} 在握手之前就指向裸 socket、握手之后改指 TLS 那一层：取消只有一条路
     * ——把 socket 关掉——而握手正卡着的时候，能关的也只有下面那条。
     *
     * <p># 握手只认它广播的那份证书
     *
     * <p>上下文按对端的 {@code fingerprint} 建（{@code Identity.clientContext}），所以对面
     * 出示的证书必须正好哈希成它在 TXT 里登出来的那个值。这不是「多一层校验」的洁癖：发现和
     * 连接是两次独立的交换，中间谁都可以插进来先答查询、再拿自己的证书完成握手。钉住之后，
     * 答查询的那台机器必须同时握着那张证书的私钥。
     *
     * <p>指纹是空的时候照常连（{@code clientContext(null)} 接受任意自签链）：TXT 分几条记录
     * 到达，型号到了指纹可能还没到，此时拒绝发送等于把「记录还没收全」判成「对端有问题」。
     * 代价是这一条连接没有任何东西可钉，日志里会记一行。
     */
    /**
     * 开一条到对方的连接，并把它登记成「当前这条可以被取消」。
     *
     * <p>连接本身在 {@link #connectTls}——预查询那条不碰 live 的连接也走它，证书钉住的逻辑
     * 因此只有一份。
     */
    private SSLSocket open(Peers.Peer peer, int connectMs, int readMs) throws IOException {
        live = rawSocket(peer, connectMs, readMs);
        try {
            SSLSocket tls = connectTls(peer, live);
            live = tls;
            // 握手这一段可能有好几百毫秒，人在那期间点了取消的话，{@link #cancelSend} 能关到的
            // 只有裸 socket（它当时还不知道 TLS 那一层）。这里再看一眼：真取消了就抛出去，
            // 由下面那个 catch 把刚建成的这条关掉，别让传输白跑一趟 /Discover。
            if (cancelling) {
                throw new IOException("cancelled during the connection");
            }
            return tls;
        } catch (IOException failed) {
            closeQuietly(live);
            live = null;
            throw failed;
        }
    }

    /** TCP 连接：选项在 connect 之前设，地址带 scope。预查询也用这条，不登记 live。 */
    private static Socket rawSocket(Peers.Peer peer, int connectMs, int readMs) throws IOException {
        Socket raw = new Socket();
        // 两个选项都要在 connect 之前设，理由见 {@link #SEND_BUFFER}：队列的尺寸是建 socket
        // 时定的。失败不算致命——不设也能传，只是进度会变成「写进队列了多少」。
        try {
            raw.setTcpNoDelay(true);
        } catch (SocketException refused) {
            Log.i(TAG, "tcp nodelay refused: " + refused);
        }
        try {
            raw.setSendBufferSize(SEND_BUFFER);
        } catch (SocketException refused) {
            Log.i(TAG, "send buffer " + SEND_BUFFER + " refused: " + refused);
        }
        // 地址不能直接当字符串用：链路本地 IPv6 少了 scope，connect 会立刻回 EINVAL。
        raw.connect(new InetSocketAddress(Peers.address(peer), peer.port), connectMs);
        raw.setSoTimeout(readMs);
        // 关掉 linger：对端先走的时候，本端已经写下去、还没被确认的那一段不该让 close 卡住。
        try {
            raw.setSoLinger(false, 0);
        } catch (SocketException refused) {
            Log.i(TAG, "so_linger refused: " + refused);
        }
        return raw;
    }

    /** TLS 握手 + 证书钉住。{@code raw} 由调用方拥有，失败时由调用方关。 */
    private static SSLSocket connectTls(Peers.Peer peer, Socket raw) throws IOException {
        if (peer.fingerprint.isEmpty()) {
            Log.i(TAG, "no fingerprint for " + peer.name + ": the certificate cannot be pinned");
        }
        try {
            SSLSocket tls = (SSLSocket) Identity.clientContext(peer.fingerprint).getSocketFactory()
                    .createSocket(raw, peer.host.isEmpty() ? peer.address : peer.host, peer.port, true);
            tls.setUseClientMode(true);
            tls.startHandshake();
            Log.i(TAG, "connected to " + peer.name + ", sndbuf " + raw.getSendBufferSize()
                    + ", nodelay " + raw.getTcpNoDelay());
            return tls;
        } catch (Exception failed) {
            // 指纹不符是这条链上唯一一种「对方不是它说的那台机器」的失败，而且它到这一层已经
            // 被 JSSE 套了两三层。先把它捞出来换成自己的类型，界面上才不会说成「发送失败」。
            PeerMismatch mismatch = PeerMismatch.of(failed);
            if (mismatch != null) {
                throw mismatch;
            }
            if (failed instanceof IOException) {
                throw (IOException) failed;
            }
            throw new IOException("TLS did not start: " + failed, failed);
        }
    }

    /**
     * 后台问一台设备「你叫什么」，不开始传输。
     *
     * <p>只在缓存没有这台设备时跑。连接与握手走 {@link #rawSocket}/{@link #connectTls}，和真正
     * 发送用的是同一套（证书钉住也在内），但绝不碰 {@link #live}——否则一次后台查询会把正在
     * 传的那条连接顶掉。问完就关：{@code /Discover} 只发一条请求，对面不会弹窗。
     */
    private void probeName(Peers.Peer peer) {
        if (PeerNames.get(peer) != null) {
            return;
        }
        new Thread(() -> {
            SettingsScreen tuned = Tuning.read(this);
            int connectMs = (int) Tuning.ms(tuned, "client.connect_timeout_ms", CONNECT_TIMEOUT_MS);
            int readMs = (int) Tuning.ms(tuned, "client.read_timeout_ms", READ_TIMEOUT_MS);
            Socket raw = null;
            SSLSocket tls = null;
            try {
                raw = rawSocket(peer, connectMs, readMs);
                tls = connectTls(peer, raw);
                byte[] answer = Native.discoverName(
                        peer.host.isEmpty() ? peer.address : peer.host,
                        settingsText(), senderName(), modelName(), senderId(),
                        tls.getInputStream(), tls.getOutputStream());
                String name = new String(answer, java.nio.charset.StandardCharsets.UTF_8);
                if (!name.trim().isEmpty()) {
                    PeerNames.put(peer, name);
                    runOnUiThread(() -> showRealName(peer, name));
                    Log.i(TAG, "probed name for " + peer.name + " -> " + name);
                }
            } catch (Exception failed) {
                // 问不到很正常：对面锁屏、AirDrop 关了、或者还没答完。留着 hash，下一次
                // 广播会再试。不记日志——每台设备每两秒一次，会把日志冲掉。
            } finally {
                closeQuietly(tls != null ? tls : raw);
            }
        }, "opairdrop-name-" + peer.name).start();
    }

    /** 关掉一条连接，关不掉也只是记一笔——它本来就可能是对面先关的。 */
    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception alreadyGone) {
            Log.i(TAG, "closing a connection that was already gone", alreadyGone);
        }
    }

    /** 写一句状态。存进表里，重建之后还能重放。 */
    private void status(Peers.Peer peer, String line) {
        status(peer, line, false);
    }

    /**
     * 写一句状态，并决定它是普通色还是告警色。
     *
     * <p>{@code alarm} 传 {@code true} 的只有「对方拒绝」这一种：它不是一个过渡中的进度，而是
     * 这一轮投送的结论，和其余几行（正在连接…、已发送…）不是一类话，用同一个蓝色会读成同一种事。
     */
    private void status(Peers.Peer peer, String line, boolean alarm) {
        statuses.put(peer.name, line);
        statusAlarms.put(peer.name, alarm);
        TextView state = stateViews.get(peer.name);
        if (state != null) {
            state.setText(line);
            state.setTextColor(alarm ? S.red : S.accent);
        }
    }

    /**
     * /Discover 应答带回了对方自己起的名字：把格子上那行占位换掉。
     *
     * <p>真名在发现阶段拿不到（iPhone 广播的 mDNS 实例名是每分钟换一次的 hash），只有连上、
     * 问过 /Discover 才知道。所以这里允许改变已经画出来的视图，也把真名记进 {@link #realNames}：
     * 重建（重扫、换主题）之后那一行仍要是真名，不能退回去显示占位。
     */
    private void showRealName(Peers.Peer peer, String real) {
        if (real == null || real.trim().isEmpty()) {
            return;
        }
        realNames.put(peer.name, real);
        PeerNames.put(peer, real);
        // 名字到的那一刻就重算，而不是等下一个报文。
        //
        // 这一台很可能正压在 {@link #settle} 的「等名字」窗口里、还没上屏：那时 nameViews 里
        // 没有它，单独去换那一行字什么都不会发生，而它下一次有机会出现要等 mDNS 再来一条——
        // iPhone 重复答同一个地址时那条记录不算「变了」，连重画都不会触发。重算一次，头像和
        // 这个名字就会一起出现，而不是先画个占位再被换掉。
        settle(System.currentTimeMillis());
        Log.i(TAG, "peer name from /Discover: " + peer.name + " -> " + real);
    }

    /** 按 {@link #progressOf} 重画某一台的环。视图可能不在（还没建或已重建）。 */
    private void redrawProgress(String name) {
        Nick nick = nicks.get(name);
        if (nick == null) {
            return;
        }
        Float progress = progressOf.get(name);
        nick.progress(progress == null ? 0f : progress);
    }

    // ------------------------------------------------------------------ 关闭

    /**
     * 下滑关闭的根。
     *
     * <p>它同时是内容的父，所以这里拦手势不会漏掉任何一块：从标题、雷达、说明卡上往下拉，
     * 走的都是同一个判定。
     */
    private final class Sheet extends FrameLayout {
        private final int slop;
        private final int fling;
        private final VelocityTracker tracker = VelocityTracker.obtain();
        /**
         * 按下那一刻的**屏幕**坐标，不是视图坐标。
         *
         * <p>这是整段手势里唯一必须小心的地方：`event.getY()` 是相对**接住事件的这个视图**算的，
         * 而拖动时移动的恰恰就是这个视图。于是面板每往下走一像素，后续事件的 `getY()` 就少一像素，
         * 手指的真实位移被自己抵消掉一半——手指走 800 像素，面板只走 400。更要紧的是这个抵消带
         * 反馈：本帧算出来的位移会影响下一帧读到的坐标，手指有一像素噪声，面板就来回荡一像素。
         * 这就是「整个界面上下抖动」，和模糊、硬件层、雷达都无关，关掉哪一个都还在。
         *
         * <p>`getRawX/Y()` 相对屏幕，视图怎么移动都不变，反馈回路从根上没有了。
         */
        private float downRawY;
        private float downRawX;
        /**
         * 按下那一刻面板已经在的位置。
         *
         * <p>位移必须相对**这里**算，不能相对 0：一次没滑走的手势结束后，回弹动画的最后一帧
         * 和下一次按下之间可能只隔了几十毫秒，面板停在半路；若还按「手指位移 = 面板位移」，
         * 第一个 MOVE 就会把它从半路拽回顶部——看起来是手指一按，面板自己跳了一下，接着怎么
         * 拖都对不上。真机上撞到的就是这个。
         */
        private float startTop;
        /**
         * 按下去之后真正画出来的位置（{@code getTop()}）。
         *
         * <p>它只在「往下走」和「明显往回拖」时更新，小幅回退保持不动——见 MOVE 分支里的注释。
         * 和 {@link #startTop} 分开是因为两者含义不同：一个是基准，一个是当前落点。
         */
        private float shown;
        /** 这一次触摸里模糊是否已经关掉了。只在第一帧 MOVE 上做一次，不是每帧。 */
        private boolean moving;

        Sheet(Activity activity) {
            super(activity);
            ViewConfiguration configuration = ViewConfiguration.get(activity);
            slop = configuration.getScaledTouchSlop();
            // 三倍最小速度：这个手势要「甩」，不是「慢慢挪一点点」。
            fling = configuration.getScaledMinimumFlingVelocity() * 3;
        }

        /**
         * 布局之后把位置按住。
         *
         * <p>父容器每次布局都会把子视图摆到它算出来的位置——对这个面板来说是 0。上滑、回弹、
         * 滑出、跟手这四段位移全部写成「真实的 top」，所以一次布局就是一次打回原点：面板正在
         * 往上滑，屏幕中间忽然闪一帧回到最上面，再被下一帧推回来。一次手势里只要夹进两次布局，
         * 读起来就是「抖」。
         *
         * <p>这些布局从外面来，不由我们决定：系统栏 inset 到达、发现设备后中部重建、标题下那行
         * 小字从「正在准备……」变成「1 个文件 · 5 B」——每条都发生在上滑那 340 毫秒里。
         *
         * <p>所以位置不交给父容器：它想摆在哪不重要，{@link #placed} 说了算。这里用
         * {@code onLayout} 收拾，是因为 {@code ViewGroup.layout} 是 final——框架在把 frame
         * 设成 0 之后才回调到这里，正好剩下最后一步能改。
         */
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            apply();
            if (android.util.Log.isLoggable("OPAirDrop", android.util.Log.DEBUG)
                    && (slide != null || dragging)) {
                Log.d("OPAirDrop", "layout during motion: asked=" + top
                        + " kept=" + getTop() + " slide=" + (slide != null));
            }
        }

        /**
         * 把事件换算到屏幕坐标再交给速度追踪器。
         *
         * <p>理由和 {@link #downRawY} 一样：`VelocityTracker` 看到的是事件自带的坐标，而事件坐标
         * 是相对当前 `top` 的。拖动中 `top` 一直在变，直接喂进去算出来的「速度」里混着面板自己
         * 抵消掉的那一半，甩一下会被判成没甩。加上此刻的 `top` 就还原成屏幕坐标，位移纯粹来自
         * 手指。
         *
         * <p>用副本是因为 `offsetLocation` 会改写事件本身，而事件还要继续往下传。
         */
        private void track(MotionEvent event) {
            MotionEvent copy = MotionEvent.obtain(event);
            copy.offsetLocation(getLeft(), getTop());
            tracker.addMovement(copy);
            copy.recycle();
        }

        /**
         * 抬手时把层还回去——不管这一次触摸最后被谁接住。
         *
         * <p>点一个按钮、点一格设备，DOWN 之后就往子 view 去了，{@link #onTouchEvent} 再也
         * 不会被调用，于是那里的 {@code layer(false)} 永远等不到。留下来的不只是「层一直挂着」：
         * 雷达在 DOWN 那一刻已经停了，而它停下来之后就没人会再叫它——**点一下界面，搜索动画
         * 就死在那一圈上**。这是人一眼就看得见的那种坏。
         *
         * <p>拖动那条路不在这里收尾：那时 {@code moving} 为真，层要跟着回弹动画一起还回去
         * （见 {@link SendActivity#springBack()}）。提前还掉，回弹的那三百毫秒就会重新逐帧
         * 栅格化，抖的正是它。
         */
        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            // 先记下来：onTouchEvent 的 UP 分支会把 moving 清掉，等 super 返回之后就读不到了。
            boolean dragged = moving;
            boolean handled = super.dispatchTouchEvent(event);
            if (!dragged && !closing
                    && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                layer(false);
            }
            return handled;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    moving = false;
                    // 上一个回弹或上滑可能还在跑。它每帧都在写位置，和手指写的那个互相覆盖
                    // ——同一帧里先被动画拉回、再被手指推开，读起来就是整个窗口在上下摆。
                    // 先让它停，位置从此只由手指决定。
                    cancelSlide();
                    // 上一段滑出被这一下按停，closing 要放掉：见 onTouchEvent 里的同名分支。
                    closing = false;
                    // 按下就建层：见 {@link #layer} 里「为什么在按下时就建」。
                    layer(true);
                    // 停在哪就从哪接着拖：见 {@link #startTop}。
                    startTop = offset();
                    shown = startTop;
                    tracker.clear();
                    track(event);
                    return false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dy = event.getRawY() - downRawY;
                    float dx = Math.abs(event.getRawX() - downRawX);
                    // 只接管「明显向下、且比横向更竖」的移动。横向留给以后可能加的翻页，
                    // 而点击（位移不到 slop）根本不进这里。
                    if (dy > slop && dy > dx) {
                        track(event);
                        // 一旦确认是要拖，就把面板提升成硬件层：见 {@link #layer}。
                        if (!moving) {
                            moving = true;
                            dragging = true;
                            layer(true);
                        }
                        return true;
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // 滑出的过程中不接受新的拖动，但**按下**例外：见下面那一支——滑到一半又把手按
            // 回来是「我不想关」，不是「失灵」。
            if (closing && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                return true;
            }
            track(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    moving = false;
                    cancelSlide();
                    // 上一段滑出被这一下按停：把 closing 放掉，否则它会一直为真，而上面那个
                    // 判断会把此后**每一次**触摸都吞掉——面板从此完全不动，看起来就是卡死。
                    // 这是只能靠真机发现的：`close()` 的 finish 挂在动画的 end action 上，
                    // 动画被取消，那个 end action 就永远不会跑。
                    closing = false;
                    layer(true);
                    startTop = offset();
                    shown = startTop;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    // 空白处按下时 DOWN 由这个 ViewGroup 自己接住（没有子 view 要它），
                    // 于是后续 MOVE 不进 onInterceptTouchEvent，直接到这里。提升层要在这里
                    // 也做一次，否则从标题或雷达上开始拖，整段都还在每帧重绘。
                    if (!moving) {
                        moving = true;
                        dragging = true;
                        layer(true);
                    }
                    // 只往下拖。往上推没有对应语义（这一页已经在最上面了）。
                    //
                    // 坐标必须是屏幕坐标：见 {@link #downRawY}。
                    //
                    // 这里曾经有一个「小幅回退不跟」的死区，本意是滤掉手指的几像素回跳。它带来
                    // 两个问题：手指慢慢往回走时面板先纹丝不动、攒够了再一下跳过去（读起来就是
                    // 「慢慢滑会跳变」）；而且这个跳跃在换向时最明显。放弃死区，改用阻尼——阻尼
                    // 是连续的，任何输入都对应一个连续的位置，不存在「攒着」这回事。
                    float dy = Math.max(0f, event.getRawY() - downRawY);
                    float target = startTop + dy;
                    shown += (target - shown) * FOLLOW;
                    if (Math.abs(target - shown) < 0.35f) {
                        shown = target;
                    }
                    place(shown);
                    // 常开的调试钩子：跟手这类东西只能靠数字判断（手指走了多少、面板走了多少、
                    // 有没有反向）。release 里默认不输出，需要时 `adb shell setprop
                    // log.tag.OPAirDrop DEBUG` 打开。
                    if (android.util.Log.isLoggable("OPAirDrop", android.util.Log.DEBUG)) {
                        Log.d("OPAirDrop", "drag finger=" + dy
                                + " panel=" + (shown - startTop) + " top=" + sheet.getTop()
                                + " frac=" + sheet.getTranslationY());
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    boolean dragged = moving;
                    moving = false;
                    if (!dragged) {
                        // 只是点了一下：层是按下时建的。还层这件事统一在
                        // {@link #dispatchTouchEvent} 里做——那里连「被子 view 接住的点击」
                        // 也收得到，而这里收不到。所以这一支什么也不做，只把手势状态清掉。
                        return true;
                    }
                    tracker.computeCurrentVelocity(1000);
                    float velocity = tracker.getYVelocity();
                    boolean leave = offset() > getHeight() * LEAVE_FRACTION || velocity > fling;
                    if (leave) {
                        close();
                    } else {
                        springBack();
                    }
                    return true;
                }
                default -> {
                    return true;
                }
            }
        }
    }

    private void springBack() {
        slideTo(offset(), 0f, SPRING_MS, new DecelerateInterpolator(1.6f),
                () -> layer(false));
    }

    /**
     * 把面板放到 y（相对它在父容器里的自然位置 0）。
     *
     * <p># 为什么不用 translationY
     *
     * <p>面板背景是一块系统给的 {@code BackgroundBlurDrawable}，它按**视图的 bounds** 去采样
     * 背后那块区域。`translationY` 只是渲染层的偏移：`top` 没变，bounds 没变，于是模糊采样的
     * 还是原来那一块，只是被 canvas 平移了——背景和内容对不上，而且每一帧对不上的量都不同，
     * 读起来就是「整个界面在抖」。
     *
     * <p>`offsetTopAndBottom` 改的是视图真正的 `top`，bounds 跟着变，模糊跟着走。一加互传那个
     * 底部弹层拖动时用的也是这一条（`COUIBottomSheetBehavior` 里就是 offset）。
     *
     * <p>代价是它只活到下一次布局：父容器一布局就把 `top` 摆回 0。所以这里不拿 `top` 当位置的
     * 真相，而是先记进 {@link #placed}，由 {@link #apply} 每帧重新投影——布局把 `top` 冲掉了，
     * 下一帧（以及 {@link Sheet#onLayout} 里那一次）再按 `placed` 摆回去。
     */
    private void place(float y) {
        if (y != y) {
            return;   // NaN：动画被取消时出现过一次，别把它写进位置
        }
        placed = y;
        apply();
    }

    /**
     * 把 {@link #placed} 投影到 {@code top} 和 {@code translationY} 上。
     *
     * <p>布局之后必须再调一次：一次布局会把 {@code top} 摆回 0（面板在父容器里的自然位置）。
     * {@link Sheet#onLayout} 里就干这件事。
     */
    private void apply() {
        // 整数部分给 top：模糊按视图的 bounds 采样，bounds 必须是整数像素，否则每帧都要替
        // 视图取一次整，模糊区域会在两个像素之间跳。
        int whole = (int) Math.floor(placed);
        int delta = whole - sheet.getTop();
        if (delta != 0) {
            sheet.offsetTopAndBottom(delta);
        }
        // 小数部分给渲染层。1400 像素高的面板，手指慢速拖动时每个事件只走零点几像素，全压进
        // top 就只能取整：走三帧才动一像素，读起来是「一格一格地跳」。这不到一像素的余量交给
        // translationY，合成器按亚像素插值，位移就是连续的。
        sheet.setTranslationY(placed - whole);
    }

    /** 面板此刻真正画在哪。就是 {@link #placed}——不读 {@code getTop()}，理由见那里。 */
    private float offset() {
        return placed;
    }

    /** 滑出屏幕再结束。返回键与下滑走同一条路，读起来是同一个动作。 */
    private void close() {
        if (closing) {
            return;
        }
        closing = true;
        // 返回键那条路没有拖动过，层还没建；滑出去的这段同样要走层。
        layer(true);
        slideTo(offset(), sheet.getHeight(), EXIT_MS,
                new AccelerateInterpolator(1.4f), this::dismissPanel);
        // 兜底。上面那个 end action 挂在动画上，而动画是可以被下一次按下取消的——那一刻
        // finish 就永远不会来，页面停在一半、closing 一直为真。带着 closing 再判一次：中途
        // 被按停时那个标记已经被清掉，这一下什么也不做。
        sheet.postDelayed(() -> {
            if (closing) {
                dismissPanel();
            }
        }, EXIT_MS + 150);
    }

    /** 关掉面板窗口，Activity 随之结束（Dialog 的 dismiss 回调里 finish）。 */
    private void dismissPanel() {
        if (panel != null && panel.isShowing()) {
            panel.dismiss();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        close();
    }

    // ------------------------------------------------------------------ 自绘

    /** 一圈上亮下暗的描边，圆角跟着面板走。 */
    private final class Rim extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private final float radius;
        private final float stroke;
        /** 描边的渐变。按高度缓存：拖动时整页每帧重绘，每帧新建一个 shader 是白扔的分配。 */
        private Shader shade;
        private int shadeHeight = -1;
        private boolean shadeLight;

        Rim(float radius, float stroke) {
            super(SendActivity.this);
            this.radius = radius;
            this.stroke = stroke;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int height = getHeight();
            if (getWidth() <= 0 || height <= 0) {
                return;
            }
            float half = stroke / 2f;
            box.set(half, half, getWidth() - half, height - half);
            path.reset();
            path.addRoundRect(box,
                    new float[]{radius, radius, radius, radius, 0f, 0f, 0f, 0f},
                    Path.Direction.CW);
            if (shade == null || shadeHeight != height || shadeLight != S.light) {
                shadeHeight = height;
                shadeLight = S.light;
                shade = new LinearGradient(0f, 0f, 0f, height,
                        S.light ? 0xB3FFFFFF : 0x73FFFFFF,
                        S.light ? 0x33FFFFFF : 0x1AFFFFFF,
                        Shader.TileMode.CLAMP);
            }
            paint.setShader(shade);
            canvas.drawPath(path, paint);
        }
    }

    /** 圆头像：手机图形 + 一圈进度。 */
    private final class Nick extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float progress;

        Nick() {
            super(SendActivity.this);
            fill.setColor(S.light ? Color.WHITE : 0xFF2A2D33);
            ink.setColor(S.light ? Color.BLACK : Color.WHITE);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(dp(2.2f));
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(S.accent);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(3));
            ring.setStrokeCap(Paint.Cap.ROUND);
        }

        void progress(float value) {
            float next = Math.max(0f, Math.min(1f, value));
            if (next == progress) {
                return;
            }
            progress = next;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float radius = Math.min(w, h) / 2f - dp(4);
            float cx = w / 2f;
            float cy = h / 2f;

            fill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, fill);

            // 手机图形：窄、高、圆角大，比例照参照图。
            float phoneW = radius * 0.60f;
            float phoneH = radius * 0.96f;
            box.set(cx - phoneW / 2f, cy - phoneH / 2f, cx + phoneW / 2f, cy + phoneH / 2f);
            float corner = phoneW * 0.28f;
            canvas.drawRoundRect(box, corner, corner, ink);
            canvas.drawLine(cx - phoneW * 0.16f, box.top + phoneH * 0.11f,
                    cx + phoneW * 0.16f, box.top + phoneH * 0.11f, ink);

            if (progress > 0f) {
                box.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawArc(box, -90f, 360f * progress, false, ring);
            }
        }
    }

    /** 搜索态的雷达：两圈很淡的呼吸环 + 一个蓝圆 + 手机图形。 */
    private final class Radar extends View {
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float phase;
        private boolean attached;

        Radar() {
            super(SendActivity.this);
            halo.setColor(S.accent);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(dp(1));
            disc.setColor(S.accent);
            glyph.setColor(Color.WHITE);
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(dp(2.4f));
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
        }

        @Override
        protected void onDetachedFromWindow() {
            // 关掉自己的帧循环。这个 View 在每次发现设备、每次换主题时都会被丢掉，留下一个
            // 还在 postInvalidateDelayed 的孤儿，等于每次换主题都多一条永不停止的心跳。
            attached = false;
            super.onDetachedFromWindow();
        }

        /**
         * 把帧循环重新挂上。
         *
         * <p>呼吸环是靠 {@link #onDraw} 末尾的那一次 {@code postInvalidateDelayed(32)} 自我
         * 续命的——它没有独立的定时器，所以「暂停一次」就等于**把这条链剪断**，而不是踩一脚
         * 刹车。剪断之后没有人会再叫它，于是手指一抬，环就停在上一次画到的那一圈不动了。
         *
         * <p>2026-11-18 的真机现场就是这样：点一下页面（甚至只是按在空白处再抬手），搜索动画
         * 从此静止，直到下一次发现设备把整块中部重建。所以暂停的人必须负责叫醒，见
         * {@link SendActivity#layer(boolean)}。
         */
        void wake() {
            if (attached && !dragging) {
                postInvalidateDelayed(32);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float core = Math.min(w, h) * 0.30f;
            float far = Math.min(w, h) * 0.47f;

            // 两圈呼吸环，相位差半圈：读起来像「它在找人」。
            phase = (phase + 0.012f) % 1f;
            for (int index = 0; index < 2; index++) {
                float grown = (phase + index * 0.5f) % 1f;
                halo.setAlpha((int) (70 * (1f - grown)));
                canvas.drawCircle(cx, cy, core + (far - core) * grown, halo);
            }

            canvas.drawCircle(cx, cy, core, disc);
            float phoneW = core * 0.52f;
            float phoneH = core * 0.82f;
            box.set(cx - phoneW / 2f, cy - phoneH / 2f, cx + phoneW / 2f, cy + phoneH / 2f);
            float corner = phoneW * 0.3f;
            canvas.drawRoundRect(box, corner, corner, glyph);
            canvas.drawLine(cx - phoneW * 0.14f, box.top + phoneH * 0.1f,
                    cx + phoneW * 0.14f, box.top + phoneH * 0.1f, glyph);

            // 拖动期间不自转。
            //
            // 面板这时是一个硬件层，层里的任何一次 invalidate 都要把整块 1440×3168 的纹理
            // 重画一遍——每 32ms 一次，一边还在跟手，就是一秒三十次全屏重绘。停掉它，拖动的
            // 每一帧就只剩下「把已有纹理挪个位置」。呼吸环没人盯着看的那半秒里，静止它不亏。
            if (attached && !dragging) {
                postInvalidateDelayed(32);
            }
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static Scheme schemeOf(Configuration configuration) {
        int mode = configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return Scheme.of(mode != Configuration.UI_MODE_NIGHT_YES);
    }

    private Drawable round(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    /** 取一个颜色的 rgb，把 alpha 换成 {@code fraction}（0–1）。深浅色都从同一张表取色。 */
    private static int alpha(int color, float fraction) {
        return Color.argb(Math.round(Color.alpha(color) * fraction),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private static String modelName() {
        String maker = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL;
        if (model.toLowerCase(Locale.US).startsWith(maker.toLowerCase(Locale.US))) {
            return model;
        }
        return (maker + " " + model).trim();
    }

    /**
     * 本机在对方手机上的身份，装一次就是这一个值。
     *
     * <p>它**不是** `TransferID`。那个是一次传输的名字，每次点设备都会重新生成；`SenderID`
     * 是这台设备的身份，iPhone 按它归并「同一个发送方」，所以每次换一个值等于每次都让自己
     * 变成一个陌生设备。派生自证书（{@link Identity#senderId()}）同时拿到稳定性和「不用另存
     * 一份」。
     *
     * <p>缓存不是为了省 CPU，是为了省一次密钥生成：身份不存在时 {@code loadOrCreate} 会当场
     * 生成一份证书，那是几百毫秒；放在第二次点设备上白等没有意义。
     */
    private String senderId() throws IOException, GeneralSecurityException {
        String known = senderId;
        if (known == null) {
            known = new IdentityStore(getFilesDir()).loadOrCreate(senderName()).senderId();
            senderId = known;
            // 真机上唯一能核对的东西就是这一行：两次投送之间它必须一模一样。不一样，就是接收
            // 端把同一台手机记成了两台，而这在对面手机上是看不到原因的一串同名记录。
            Log.i(TAG, "sender id " + known);
        }
        return known;
    }

    /** 我在这台机器上的名字：设置里的 {@code device.name}，没设就用型号。 */
    private String senderName() {
        String text = settingsText();
        if (text != null) {
            for (String line : text.split("\n")) {
                int equals = line.indexOf('=');
                if (equals > 0 && line.substring(0, equals).trim().equals("device.name")) {
                    String value = line.substring(equals + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        }
        return modelName();
    }

    /**
     * 存下来的设置文本，读不出来就给 null——那与「什么都没设」是同一件事。
     *
     * <p>发送侧只读 {@code http.response_limit} 一行，却把整份文本传下去：以后发送端再
     * 认一行，就不必再长一个参数，也不必在 Java 这边再维护一份键名。
     */
    private String settingsText() {
        try {
            byte[] text = Native.loadSettings(SettingsFile.of(this).getAbsolutePath());
            return new String(text, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            // 读不出来就用默认：这一项不是必填，而挡住发送是比默认值更坏的答案。
            return null;
        }
    }

    /**
     * 这一趟发送走到了哪儿。
     *
     * <p>只回答两个问题，而它们在失败时说的不是同一件事：{@link #moving}——对面点了接受没有；
     * {@link #bytes}——有多少字节真的写进了那条 socket。传输线程里那个进度回调填，同一个线程
     * 的 catch 读，所以不需要锁，{@code volatile} 只是为了让「跨了 runOnUiThread 之后再读」
     * 这件事在读的人眼里是显然的。
     */
    private static final class Flight {
        /** 进度相位进过 SENDING：对面已经在手机上点了接受。 */
        volatile boolean moving;

        /** 已经写出去的字节数。{@link Native.Progress#SENDING} 之前恒为 0。 */
        volatile long bytes;
    }

    private static final class Report {
        long bytes;

        static Report parse(String text) {
            Report report = new Report();
            for (String line : text.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0 && line.substring(0, tab).equals("bytes")) {
                    try {
                        report.bytes = Long.parseLong(line.substring(tab + 1).trim());
                    } catch (NumberFormatException notANumber) {
                        report.bytes = 0;
                    }
                }
            }
            return report;
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
        return String.format(Locale.US, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }
}
