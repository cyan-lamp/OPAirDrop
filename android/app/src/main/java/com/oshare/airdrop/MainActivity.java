package com.oshare.airdrop;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;

/**
 * 应用外壳：三页横向翻页，一块顶部霜面，一颗浮动标题胶囊，一条浮动导航胶囊。
 *
 * <p># 层级为什么是这个顺序
 *
 * <p>{@code FrameLayout root → SnapPager（铺满）→ frostVeil → noticeWrap → topBar → navBar}。
 * 顺序就是 z 序：霜面盖在内容上，标题胶囊盖在霜面上，导航胶囊在最上层。翻页器必须是最底
 * 层的那一个，因为它同时是模糊的**捕获根**——{@link RealtimeBlurView#setCaptureRoot} 会把
 * 它的 {@code RenderNode} 离线录一遍再模糊。捕获根不能是浮动面板的祖先，否则录制会重入到
 * 自己（"Recording currently in progress"），所以面板是它的兄弟，不是它的子节点。
 *
 * <p># 三处的模糊是同一张配方
 *
 * <p>顶部遮罩、标题胶囊、导航胶囊的模糊参数逐项相同：{@code blurRadius 5f}、liquid-glass
 * 饱和提升、对角高光、不透明度 240、同一种染色。差一项就会露馅——底部胶囊比顶部通透时，
 * 两块玻璃读起来是两种材料。
 *
 * <p># 一切边界都跟着滚动
 *
 * <p>静止在页顶时，标题胶囊只有文字，没有边框、没有阴影、没有霜面；正文往上滚过 28dp 的
 * 过程中，霜面、双边描边、投影一起长出来，滚回顶部再一起退掉。这不是装饰：一页正文顶到
 * 状态栏下面时，没有这层霜，字会从时钟底下穿过去。
 *
 * <p># 只有两页会重画
 *
 * <p>接收端状态一变，首页和功能页都要重画；设置页不重画，因为那里可能有改了一半还没保存的
 * 输入，而状态变化恰好会在人正打字的时候发生。切走时它才被丢掉，回来时按最新读一次表。
 */
public final class MainActivity extends Activity {
    /** 底栏三格，顺序就是页序。 */
    private static final String[] TABS = {"首页", "功能", "设置"};

    /**
     * 底栏三格的图标，用 NAV_ 那一组而不是页内那组。
     *
     * <p>页内的图标是 44dp 图标砖里的 22dp 图形，四周有主色底托着，线条可以细；底栏一格里
     * 只有 22dp 见方，没有底托，同一个图形缩到这儿会糊成一团。NAV_ 那三个是按 22dp 重画的：
     * 房子（首页）、三条滑杆（功能页全是开关）、齿轮（设置）。
     */
    private static final int[] TAB_ICONS = {
            IconView.NAV_HOME, IconView.NAV_FEATURE, IconView.NAV_SETTINGS};

    /** 重建（换深浅色、字体大小、旋转）之后停回原来那一页。 */
    private static final String KEY_TAB = "tab";

    /**
     * 「这次是被人点名下叫来的，权限可以问得强硬一点」。
     *
     * <p>只有一个发送方：控制中心的【AirDrop】磁贴。它是 Service，自己弹不出权限框，只能把人
     * 送到这一页；而它传过来的这个标记，就是「他刚点了开」这件事的唯一凭据——有了它，系统已经
     * 不再弹的那些权限可以被直接落到设置页，而没有它就只能弹框，不能把人扔进设置。
     */
    static final String EXTRA_PERMISSIONS = "ask_permissions";

    private Listener listener;
    /** 当前这套配色。深色与浅色各一张表，见 {@link Scheme}。 */
    private Scheme S;
    private Chrome chrome;

    private FrameLayout root;
    private SnapPager pager;
    private FrameLayout[] pages;

    /** 顶部全宽霜面：从屏幕顶端铺到胶囊底边再往下一个胶囊高。 */
    private RealtimeBlurView frostVeil;
    private RealtimeBlurView topBlur;
    private RealtimeBlurView navBlur;
    private RealtimeBlurView noticeBlur;

    private FrameLayout topBar;
    private FrameLayout navBar;
    private FrameLayout noticeWrap;
    private TextView noticeBar;
    private LinearLayout tabsRow;
    private IconView[] tabIcons;
    private TextView[] tabLabels;

    private int currentTab;
    private int insetTop;
    private int insetBottom;

    /** 标题边界的当前进度 [0..1]，用来跳过重复写入。 */
    private float topEdge = -1f;
    /** 导航胶囊此刻是展开还是收起。 */
    private boolean navShown = true;
    /** 收起 / 展开那一口气泡动画。触摸驱动的形变要能把它掐掉。 */
    private ValueAnimator navMorph;

    /**
     * 设置页只在真的要看它时才建。
     *
     * <p>建出来就一直留着，直到切走：重建会把没保存的输入扔掉，而状态变化引起的重画恰好
     * 会在人正打字的时候发生。
     */
    private SettingsPage settings;

    private boolean lightMode;

    /**
     * 这一页有没有在启动时递过一次权限请求。
     *
     * <p>{@code onWindowFocusChanged} 会因为权限框出现、消失反复回调，没有这个位就会变成
     * 「弹框 → 失焦 → 关框 → 又弹框」的循环。位只在本页存活期内有效：重建（换主题、转屏）后
     * 重来一次也无害，{@link Notices#first} 自己会挡住「已经问过」的那几条。
     */
    private boolean askedOnOpen;

    private final Runnable noticeHide = this::hideNotice;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        lightMode = mode != Configuration.UI_MODE_NIGHT_YES;
        S = Scheme.of(lightMode);
        chrome = new Chrome(this, S);
        chrome.setNotice(this::showNotice);
        // 调试探针：`am start -n …/.MainActivity --ez live_probe true` 用一段假进度把实时通知演
        // 一遍。它回答的那个问题只有看屏幕才能回答——「这台机器的系统愿不愿意把我们的通知提升
        // 到状态栏那颗胶囊里」。只在 debug 包里存在。
        if (BuildConfig.DEBUG) {
            String probe = getIntent().getStringExtra("live_probe");
            if (probe == null && getIntent().getBooleanExtra("live_probe", false)) {
                probe = "progress";
            }
            if (probe != null) {
                liveProbe(probe);
            }
        }
        // 页面不知道胶囊的存在，所以由外壳把两件动画接上：内容上滑时导航胶囊沉下去，每一次
        // 滚动都让顶部玻璃按滚动进度重新长一遍。
        chrome.setScrollHooks(new Chrome.ScrollHooks() {
            @Override
            public void onScrollDirection(boolean up) {
                setNavVisible(!up);
            }

            @Override
            public void onScrollChanged() {
                updateTopEdge();
            }
        });
        // 进程里那一个接收端，接到现在这个页面。旋转屏幕时拿到的还是同一个对象，所以正在
        // 进行的传输不会被转一下手机打断。
        listener = Listener.open(this, () -> runOnUiThread(this::refresh));
        setContentView(buildUi());
        // 深色/浅色切换会让这个 Activity 整个重建（uiMode 不在 configChanges 里，也不该在：
        // 颜色、模糊配方、启动资源全都要跟着换）。不记住停在哪一页，人一换主题就被弹回首页。
        if (state != null) {
            int tab = state.getInt(KEY_TAB, 0);
            if (tab != 0) {
                commitTab(tab, false);
            }
        }
        // 权限不在这里问：这一页此刻还不是一个活着的窗口，弹出去的框可能根本不会出现。
        // 见 onWindowFocusChanged。
    }

    /**
     * 打开应用就把入场券问齐。
     *
     * <p>放在窗口拿到焦点之后，而不是 {@code onCreate} 里：{@code requestPermissions} 需要这一页
     * 已经是一个「活着的窗口」。在 {@code onCreate} 里直接叫，系统有时收下这次请求却一个框都
     * 不弹——人看到的是应用正常打开、什么都没发生，而三条权限一条都没拿到。
     *
     * <p>只问从没问过的那几条（{@link Notices#first}）。这个应用只有一件事可做，打开它就是表示
     * 了意图，所以第一次进来就把话说清楚；但人拒绝过之后不再重复弹框——系统对被拒两次的权限会
     * 静默失败，重复问只会把「他不想给」演成「点了没反应」。缺口改由首页那颗胶囊和副标题说。
     */
    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus || askedOnOpen) {
            return;
        }
        askedOnOpen = true;
        openPermissions();
    }

    /**
     * 把缺的权限补上：先说话，再问。
     *
     * <p>入场券不齐时，接收端有两条互不相干的原因会静默失聪：
     *
     * <p>**缺通知**：8770 在听、iPhone 也找得到这台机器，而「有人要发给你文件」这句话一个字
     * 也送不到人眼前——对面三十秒后拿到 503，本机安安静静。1.0.0 装上之后就是这样：全仓只有
     * 蓝牙那条权限有申请点，通知这条从来没有，而 Android 13 起新装应用的通知默认是关的，于是
     * 邀请一次都没出现过。
     *
     * <p>**缺蓝牙广播**：反过来的安静。通知这条路是通的，可这台机器在对端的扫描里根本不存在，
     * 于是从来没有人发起邀请——也就没有通知可发。这条过去只在发送页申请（发送端也要广播），
     * 于是**只收不发的人**永远拿不到它。
     *
     * <p>提示条只在接收端已经在跑时才出：那时开关是绿的、东西进不来，这句话是在解释一个真实
     * 的矛盾。接收端没跑的时候不出——人还没表示要收东西，先摆一条「收不到邀请」，是在替他回答
     * 一个他还没问的问题。
     */
    private void openPermissions() {
        if (Notices.ready(this)) {
            return;
        }
        // 这一下是被控制中心那块磁贴叫来的吗？叫来的意思是：他刚点了「开」，明确要能被发现，
        // 所以缺的那几条值得连设置页一起用上——系统不再弹框的时候，那是唯一的出路。
        // 单纯打开应用没有这个待遇：一进来就把人扔进系统设置，是最快的卸载理由之一。
        Intent came = getIntent();
        boolean insisted = came != null && came.getBooleanExtra(EXTRA_PERMISSIONS, false);
        if (insisted) {
            // 只对这一次生效。留着它，转个屏重建出来的这一页会以为自己又被人从磁贴点了一次。
            came.removeExtra(EXTRA_PERMISSIONS);
        }
        // 缺口只在「有人要收东西」时才说：接收端在跑，或他刚从磁贴点名要开。其余情况先不说——
        // 人还没表示要收东西，摆一条「收不到邀请」是在替他回答一个他还没问的问题。
        boolean collecting = insisted || ReceiveService.listening(this);
        String gap = collecting ? Notices.gap(this) : null;
        if (gap != null) {
            // 先说话，再问。权限框会盖住这条气泡，但人关掉框之后读到的还是它——而如果他点了
            // 拒绝，这句话就是屏幕上唯一还站着的解释。
            showNotice(gap, S.orange);
        }
        if (!Notices.allowed(this) || !Notices.radio(this)) {
            if (insisted) {
                Notices.ensure(this);
            } else {
                Notices.first(this);
            }
            return;
        }
        // 通知和蓝牙都齐了，缺的只可能是「后台能不能活着」。这一条弹的不是权限框，是会盖住
        // 整屏的系统确认框，所以分两种问法：他刚点过磁贴就随叫随问；单纯打开应用只问一次
        // （见 Notices.unseenAwake）——启动时把整屏交给一个对话框，和启动时把人扔进设置页
        // 是同一类冒犯。
        if (insisted || (collecting && Notices.unseenAwake(this))) {
            Notices.wake(this);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_TAB, currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置回来时，互传的范围可能已经变了，而设置文件也可能被改过——重新对齐一次
        // 比追着问哪一处变了便宜。重复调用只对齐、不重复注册观察者，见 ReceiveService.follow。
        ReceiveService.follow(this);
        // 另一件事：人可能是刚去系统设置里把蓝牙权限勾上的。那次「允许」不经过我们的回调，
        // 而接收端启动时敲的那次广播门已经落空了——回到这一页是唯一能补上的时机。内部有两
        // 道闸（没在跑、已经发出去），所以每次进来都调是安全的。
        Listener.of(this).reannounce();
        refresh();
    }

    /**
     * 权限框的答案。
     *
     * <p>要回一句话，是因为结果对人是完全不同的事实：齐了，这台机器从此能被叫醒；缺一条，
     * 接收端会一直绿着、一直收不到邀请。而「接收已开启」那句话说在两种情况下长得一模一样，
     * 所以差别的这一句只能由这里补。
     *
     * <p>一次会回来两条（{@link Notices#ensure} 把缺的一起递了出去），所以这里不去看
     * {@code granted} 数组，而是问那两条权限此刻的真实状态——数组只说了人点了什么，没说的
     * 是系统最终给了什么。
     */
    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(code, permissions, granted);
        if (code != Notices.REQUEST) {
            return;
        }
        String gap = Notices.gap(this);
        // 现在这个回调也会在「刚打开应用」之后响一次，那时接收端可能还没开——所以齐了的话
        // 不说「可以收到邀请了」（那要有东西在听才成立），说一句两边都站得住的。
        showNotice(gap == null ? "权限齐了，可以开始互传了" : gap,
                gap == null ? S.green : S.orange);
        // 权限框刚走完，人是主动的，这时候接上「允许后台运行」最顺——那一串框本来就是同一个
        // 动作：「让它真的收得到东西」。缺这一步，人刚点完两个允许，屏幕上剩下的仍是
        // 「后台被限网，点接收会断开」，而他下一步不知道该点哪儿。
        if (!Notices.ready(this) && Notices.allowed(this) && Notices.radio(this)) {
            Notices.wake(this);
        }
        // 接收端已经在跑，而蓝牙广播是它启动那一刻敲的一次门——那次敲在权限到手之前，必然
        // 落空。补这一下，人才不必为了「刚点过允许」再把开关拨一次。
        Listener.of(this).reannounce();
        // 首页那颗胶囊说的是同一件事，跟着重画一次，不必等下一次进出页面。
        refresh();
    }

    @Override
    protected void onDestroy() {
        // 走的是这一页，不是接收端。人按下返回就停掉的接收端，会在离开屏幕的那一刻丢下一次
        // 传输，而 AirDrop 的全部意义就是没人看着它也在应答。解挂只是不再重画这一页。
        listener.unwatch();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentTab != 0) {
            selectTab(0, true);
            return;
        }
        if (settings != null && settings.dirty()) {
            settings.confirmLeave(() -> {
                settings = null;
                pages[2].removeAllViews();
                finish();
            });
            return;
        }
        super.onBackPressed();
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ------------------------------------------------------------- 外壳

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(S.bg);
        // 让导航胶囊的 elevation 投影能溢出自己的边界
        root.setClipChildren(false);
        root.setClipToPadding(false);

        pager = new SnapPager(this);
        pager.setFillViewport(true);
        pager.setBackgroundColor(S.bg);
        // 内容跟手横滑时不许露出发亮的边缘：边缘一旦发亮，页与页之间那道缝就成了视觉主体
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(frostVeil(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        root.addView(notice(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        pages = new FrameLayout[] {
                holder(), holder(), holder(),
        };
        LinearLayout pagerRow = new LinearLayout(this);
        pagerRow.setOrientation(LinearLayout.HORIZONTAL);
        for (FrameLayout page : pages) {
            pagerRow.addView(page, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pager.addView(pagerRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.setOnPage(index -> selectTab(index, false));

        replacePage(0, new HomePage(this, chrome, listener, this::refresh).view());
        replacePage(1, featurePage());

        root.addView(buildTopCapsule());
        root.addView(buildNavPill());

        // 翻页时每一帧都让三块玻璃重拍：只在 PreDraw 上刷新的话，滑动过程中霜面会停在上
        // 一页的背面，看起来像一层贴纸被拖着走。
        pager.setOnScrollListener(() -> {
            materializeSettingsAhead();
            refreshGlass();
            // 霜面的淡入进度跟着小数页位置走，于是切页时顶部玻璃是交叉溶解的
            updateTopEdge();
        });

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            insetTop = bars.top;
            insetBottom = bars.bottom;
            applyInsets();
            return insets;
        });

        selectTab(0, false);
        return root;
    }

    /** 一页的容器。页宽由 {@link SnapPager#onMeasure} 钉死。 */
    private FrameLayout holder() {
        FrameLayout page = new FrameLayout(this);
        page.setClipChildren(false);
        return page;
    }

    /**
     * 霜面的捕获根：整个翻页容器。
     *
     * <p>导航胶囊、顶部渐隐、通知条，以及功能页那个输入浮窗，糊的都是这一层——
     * {@link RealtimeBlurView} 取「背后是什么」的办法是把根视图软件重绘一遍，所以它需要
     * 一个确切的根，而不是「屏幕上碰巧压在下面的那些像素」。
     *
     * <p>功能页只能拿到 Activity，看不到这个字段。留一个包级读口，比让每个调用方自己
     * {@code findViewById} 猜一棵树稳：树一改，猜的地方不会报错，只会糊错。
     */
    View blurRoot() {
        return pager;
    }

    /**
     * 顶部全宽霜面。
     *
     * <p>它是一张平板：没有圆角、没有描边、没有羽化——唯一的边界是底部那条由竖向渐变遮罩
     * 做出来的溶解。所以 {@code backing} 必须关掉：中途霜面强度不到 1 时，一块不透明底色会
     * 把底下正在滚的内容整块盖成灰浆，而不是让内容在玻璃里透出来。
     */
    private RealtimeBlurView frostVeil() {
        frostVeil = new RealtimeBlurView(this);
        frostVeil.setCaptureRoot(pager);
        frostVeil.setFill(S.bg);
        frostVeil.setBlurRadius(5f);
        frostVeil.setSheen(lightMode ? 0x1FFFFFFF : 0x26FFFFFF);
        frostVeil.setGlassLift(lightMode ? 8 : 0);
        frostVeil.setOpacity(240);
        frostVeil.setTint((S.navTint & 0x00FFFFFF) | 0x4D << 24);
        frostVeil.setLiquidGlass(true);
        frostVeil.setCornerRadius(0f);
        frostVeil.setBackingEnabled(false);
        return frostVeil;
    }

    /**
     * 顶部遮罩的高度与渐隐带，跟着标题胶囊的几何走。
     *
     * <p>整块高度 = 胶囊顶 + 两个胶囊高；胶囊那一整段是满霜，往下再一个胶囊高溶解到透明。
     * 这样状态栏那一条是实的，胶囊下方没有一条硬横线——那条线是上一版最显眼的破绽。
     */
    private void syncFrostVeil() {
        if (frostVeil == null || topBar == null || topBar.getHeight() == 0) {
            return;
        }
        float pillTop = topBar.getY();
        int pillH = topBar.getHeight();
        int veilH = (int) (pillTop + pillH * 2);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frostVeil.getLayoutParams();
        if (lp.height != veilH) {
            lp.height = veilH;
            frostVeil.setLayoutParams(lp);
        }
        if (veilH > 0) {
            frostVeil.setVerticalFade((pillTop + pillH) / veilH, 1f);
        }
    }

    private void applyInsets() {
        FrameLayout.LayoutParams top =
                (FrameLayout.LayoutParams) topBar.getLayoutParams();
        top.topMargin = Math.max(dp(8), insetTop);
        topBar.setLayoutParams(top);

        FrameLayout.LayoutParams nav = (FrameLayout.LayoutParams) navBar.getLayoutParams();
        nav.bottomMargin = insetBottom + dp(22);
        navBar.setLayoutParams(nav);

        FrameLayout.LayoutParams notice =
                (FrameLayout.LayoutParams) noticeWrap.getLayoutParams();
        notice.bottomMargin = insetBottom + dp(96);
        noticeWrap.setLayoutParams(notice);

        syncFrostVeil();
        applyPageInsets();
    }

    /**
     * 页面正文让开系统栏、标题胶囊和导航胶囊。
     *
     * <p>内边距加在滚动容器上、且 {@code clipToPadding} 关着：静止时正文从胶囊下方开始，
     * 往上滚时从玻璃底下穿过去——霜面底下必须始终有东西在动，否则那块玻璃看起来就是一块
     * 纯色方块。
     *
     * <p>底部的 100dp 是给导航胶囊让位的通用量。这里曾经还要再叠一层：设置页底部浮着一条
     * 保存按钮行，它压在滚动区上面，外壳算内边距时看不见它，于是由页面自己声明一个
     * {@code Chrome.BottomInset} 补上那段。那颗按钮已经搬到诊断板块下面、跟着内容一起滚，
     * 不再有任何东西浮在滚动区之上，所以这一层连同那个接口一起撤了。
     */
    private void applyPageInsets() {
        if (pages == null) {
            return;
        }
        for (FrameLayout page : pages) {
            ScrollView scroll = scrollIn(page);
            if (scroll == null) {
                continue;
            }
            scroll.setClipToPadding(false);
            scroll.setPadding(0, insetTop + dp(55), 0, insetBottom + dp(100));
        }
    }

    /** 一页里那个纵向滚动容器；没有就返回 null（例如读不出设置时的纯文本页）。 */
    private static ScrollView scrollIn(View view) {
        if (view instanceof ScrollView) {
            return (ScrollView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ScrollView found = scrollIn(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // --------------------------------------------------------- 顶部胶囊

    private View buildTopCapsule() {
        // 只包住文字：模糊层不能参与 wrap_content 的测量（一个 MATCH_PARENT 的子视图会把
        // 胶囊撑成整屏），所以先把它藏起来量一次文字，再用确切的尺寸量一次让模糊铺满。
        topBar = new FrameLayout(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                boolean shown = topBlur.getVisibility() == VISIBLE;
                topBlur.setVisibility(GONE);
                super.onMeasure(widthSpec, heightSpec);
                topBlur.setVisibility(shown ? VISIBLE : GONE);
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        };
        topBar.setBackground(null);
        // 滚动时的那圈阴影来自圆角轮廓。clipToOutline 必须关：被用来裁剪的轮廓不再投影，
        // 而模糊层自己会裁掉圆角外的部分。
        topBar.setClipToOutline(false);
        topBar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getHeight() / 2f);
            }
        });
        // 页顶没有边界，所以起点是 0；updateTopEdge 随滚动把它长出来
        topBar.setElevation(0f);

        topBlur = new RealtimeBlurView(this);
        topBlur.setCaptureRoot(pager);
        topBlur.setFill(S.bg);
        topBlur.setLiquidGlass(true);
        topBlur.setBlurRadius(5f);
        topBlur.setSheen(lightMode ? 0x1FFFFFFF : 0x26FFFFFF);
        // 深色下任何提亮都会让玻璃比页面亮，把边界重新画出来，所以那里保持中性
        topBlur.setGlassLift(lightMode ? 8 : 0);
        topBlur.setOpacity(240);
        topBlur.setTint((S.navTint & 0x00FFFFFF) | 0x4D << 24);
        topBlur.setGlow(true, lightMode ? 0x30000000 : 0x4DFFFFFF);
        topBlur.setSoftEdge(!lightMode, lightMode ? 0 : dp(14));
        topBlur.setRim(true, dp(1.4f),
                lightMode ? 0xB3FFFFFF : 0x73FFFFFF,
                lightMode ? 0x33FFFFFF : 0x1AFFFFFF);
        topBlur.setRimDual(true);
        topBlur.setRimStrength(0f);
        topBlur.setFrostStrength(0f);
        topBar.addView(topBlur, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout line = chrome.row();
        // 紧凑：20sp 的标题不变，四周只留一点点
        line.setPadding(dp(11), dp(4), dp(11), dp(4));
        line.addView(chrome.text("OPAirDrop", 20, true, S.text));
        topBar.addView(line, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 圆角取高度的一半，任何字号下都是真胶囊
        topBar.post(() -> topBlur.setCornerRadius(topBar.getHeight() / 2f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        // 左缘与卡片对齐（正文左右内边距 18dp）
        lp.leftMargin = dp(18);
        topBar.setLayoutParams(lp);
        // 霜面的高度和渐隐带挂在胶囊的几何上，位置一变就要重新对
        topBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> syncFrostVeil());
        return topBar;
    }

    // --------------------------------------------------------- 导航胶囊

    private View buildNavPill() {
        navBar = new FrameLayout(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(28));
        shape.setColor(S.navTint);
        navBar.setBackground(shape);
        // 四周边界阴影：由圆角轮廓投出的 elevation 阴影。clipToOutline 必须关——被裁剪的
        // 轮廓不再投影，而模糊层自己会裁。
        navBar.setClipToOutline(false);
        navBar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(28));
            }
        });
        navBar.setElevation(dp(12));

        navBlur = new RealtimeBlurView(this);
        navBlur.setCaptureRoot(pager);
        navBlur.setCornerRadius(dp(28));
        navBlur.setFill(S.bg);
        navBlur.setLiquidGlass(true);
        navBlur.setBlurRadius(5f);
        navBlur.setSheen(lightMode ? 0x1FFFFFFF : 0x26FFFFFF);
        navBlur.setGlassLift(lightMode ? 8 : 0);
        navBlur.setOpacity(240);
        navBlur.setTint((S.navTint & 0x00FFFFFF) | 0x4D << 24);
        // 按下时那团淡蓝色光：贴着手指走，松手淡出。范围 ×2。
        navBlur.setGlow(true, lightMode ? 0x597EC8FF : 0x5980D0FF);
        navBlur.setGlowScale(2f);
        navBlur.setGlowParticles(true);
        // 深色下只要跟着手指的那团光，不要卫星火花和白热引燃
        navBlur.setGlowPlain(!lightMode);
        // 上下两边都亮、中间暗的双边描边：读起来是上下都有光照着
        navBlur.setRim(true, dp(1.4f),
                lightMode ? 0xB3FFFFFF : 0x73FFFFFF,
                lightMode ? 0x33FFFFFF : 0x1AFFFFFF);
        navBlur.setRimDual(true);
        navBar.addView(navBlur, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tabsRow = chrome.row();
        tabsRow.setPadding(dp(10), dp(6), dp(10), dp(8));

        tabIcons = new IconView[TABS.length];
        tabLabels = new TextView[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            LinearLayout tab = chrome.column();
            tab.setGravity(Gravity.CENTER);

            IconView icon = new IconView(this, TAB_ICONS[i]);
            tabIcons[i] = icon;
            tab.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

            TextView label = chrome.text(TABS[i], 11, false, S.textDim);
            label.setPadding(0, dp(3), 0, 0);
            tabLabels[i] = label;
            tab.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            tab.setLayoutParams(chrome.mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabsRow.addView(tab);
        }

        // 整条胶囊是一个触面，按左/中/右分成三格。轻点（没有滑动）= 切到那一格，带吸附
        // 动画。滑动用**相对映射**：手指走过一个胶囊宽 = 走过三页，锚点是按下那一刻的小数页
        // 位置，所以从当前所在的位置接着拖，不会在按下瞬间跳页。
        final float[] downX = {0f}, downY = {0f}, startPos = {0f}, pos = {0f};
        final boolean[] moved = {false};
        final int[] hover = {-1};
        tabsRow.setOnTouchListener((view, ev) -> {
            float x = ev.getX(), y = ev.getY();
            ViewGroup group = (ViewGroup) view;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = x;
                    downY[0] = y;
                    moved[0] = false;
                    hover[0] = Math.min(TABS.length - 1, Math.max(0,
                            (int) (x / Math.max(1f, view.getWidth()) * TABS.length)));
                    startPos[0] = pager.pageWidth > 0
                            ? pager.getScrollX() / (float) pager.pageWidth
                            : currentTab;
                    pos[0] = startPos[0];
                    navBar.animate().cancel();
                    cancelNavMorph();
                    group.getChildAt(hover[0]).animate().scaleX(0.94f).scaleY(0.94f)
                            .setDuration(90).start();
                    navBlur.showGlow(x);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!moved[0] && (Math.abs(x - downX[0]) > view.getWidth() * 0.06f
                            || Math.abs(y - downY[0]) > dp(14))) {
                        moved[0] = true;
                    }
                    if (moved[0]) {
                        pos[0] = Math.max(0f, Math.min(TABS.length - 1f,
                                startPos[0] + (x - downX[0]) * TABS.length
                                        / Math.max(1f, view.getWidth())));
                        pager.scrubTo(pos[0]);
                        int near = Math.max(0, Math.min(TABS.length - 1, Math.round(pos[0])));
                        if (near != hover[0]) {
                            if (hover[0] >= 0) {
                                group.getChildAt(hover[0]).animate()
                                        .scaleX(1f).scaleY(1f).setDuration(120).start();
                            }
                            hover[0] = near;
                        }
                        group.getChildAt(near).animate().scaleX(0.94f).scaleY(0.94f)
                                .setDuration(120).start();
                        deformNav(x - downX[0], y - downY[0]);
                        navBlur.moveGlow(x);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    springNav();
                    if (hover[0] >= 0) {
                        group.getChildAt(hover[0]).animate()
                                .scaleX(1f).scaleY(1f).setDuration(140).start();
                    }
                    navBlur.hideGlow();
                    if (!moved[0]) {
                        tapVibrate();
                        if (hover[0] != currentTab) {
                            selectTab(hover[0], true);
                        }
                    } else {
                        // 落在最近的一格；正好一半时朝手指来向偏一点
                        float dir = x - downX[0];
                        float lean = dir < 0 ? -0.05f : dir > 0 ? 0.05f : 0f;
                        int target = Math.max(0, Math.min(TABS.length - 1,
                                Math.round(pos[0] + lean)));
                        if (target != currentTab) {
                            selectTab(target, true);
                        } else {
                            pager.scrollToPage(currentTab, true);
                        }
                    }
                    hover[0] = -1;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    springNav();
                    if (hover[0] >= 0) {
                        group.getChildAt(hover[0]).animate()
                                .scaleX(1f).scaleY(1f).setDuration(140).start();
                    }
                    navBlur.hideGlow();
                    hover[0] = -1;
                    return true;
                default:
                    return false;
            }
        });
        navBar.addView(tabsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = dp(34);
        lp.rightMargin = dp(34);
        lp.bottomMargin = dp(22);
        navBar.setLayoutParams(lp);
        return navBar;
    }

    /**
     * 弹性形变：横向拖拽把胶囊向两边对称拉长（同时轻微压扁，面积近似守恒，像被拉扯的糖），
     * 纵向拖拽带阻尼地跟着手指走。
     */
    private void deformNav(float dx, float dy) {
        if (navBar == null) {
            return;
        }
        float w = navBar.getWidth(), h = navBar.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        navBar.animate().cancel();
        cancelNavMorph();
        float maxY = dp(36);
        float shiftY = maxY * (dy / (maxY + Math.abs(dy)));
        float amtY = Math.abs(shiftY) / maxY;
        // 横向拉伸：走过约 150dp 到达最大
        float amtX = Math.min(1f, Math.abs(dx) / dp(150));
        navBar.setTranslationX(0f);
        navBar.setTranslationY(shiftY * 0.30f);
        navBar.setPivotX(w / 2f);
        navBar.setPivotY(dy > 0 ? 0 : h);
        navBar.setScaleX(1f + 0.10f * amtX);
        navBar.setScaleY(1f + 0.05f * amtY - 0.04f * amtX);
    }

    /** 刚性释放：被拉长的胶囊直接弹回原状，不回摆。 */
    private void springNav() {
        if (navBar == null) {
            return;
        }
        navBar.animate().cancel();
        cancelNavMorph();
        navBar.animate().translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(new LinearInterpolator())
                .start();
    }

    private void cancelNavMorph() {
        if (navMorph != null) {
            navMorph.cancel();
        }
    }

    /** 系统那声「一下」的触感。没有马达的设备上什么也不做。 */
    private void tapVibrate() {
        try {
            VibratorManager manager = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            if (manager == null) {
                return;
            }
            Vibrator vibrator = manager.getDefaultVibrator();
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        } catch (RuntimeException ignored) {
            // 触感是锦上添花：系统拒绝（省电模式、无权限）时不该让切页失败。
        }
    }

    /**
     * 沉浸式导航胶囊：往上滑时它朝屏幕底边中心沉下去像一颗气泡，往下滑时从同一个点重新
     * 鼓出来。竖向先动、横向滞后并带一点鼓包，所以读起来是被按下去而不是被缩小。
     */
    private void setNavVisible(boolean show) {
        if (show == navShown || navBar == null) {
            return;
        }
        navShown = show;
        if (navMorph != null) {
            navMorph.cancel();
        }
        // 轴心是屏幕底边中心，不是胶囊自己的底边：沉下去和鼓出来必须共用同一个原点
        ViewGroup.LayoutParams glp = navBar.getLayoutParams();
        int bottomMargin = glp instanceof FrameLayout.LayoutParams
                ? ((FrameLayout.LayoutParams) glp).bottomMargin : 0;
        navBar.setPivotX(navBar.getWidth() / 2f);
        navBar.setPivotY(navBar.getHeight() + bottomMargin);
        // 从上一次被打断的位置接着走，被打断时不会跳一下
        float tiny = 0.01f, tyMax = dp(24);
        float sx0 = navBar.getScaleX(), sy0 = navBar.getScaleY();
        float a0 = navBar.getAlpha(), ty0 = navBar.getTranslationY();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        if (show) {
            animator.setDuration(380);
            animator.addUpdateListener(a -> {
                float p = (Float) a.getAnimatedValue();
                // 竖向领先：跑到 75% 时已经完全鼓起来
                float v = Math.min(1f, p / 0.75f);
                float lead = 1f - (1f - v) * (1f - v);
                float sy = sy0 + (1f - sy0) * lead;
                // 横向滞后，中途鼓过 1 再收
                float lag = Math.min(1f, p / 0.9f);
                float hcurve = lag < 0.5f
                        ? 2f * lag * lag
                        : 1f - 2f * (1f - lag) * (1f - lag);
                float bulge = 0.18f * (float) Math.sin(Math.PI
                        * Math.min(1f, Math.max(0f, (p - 0.5f) / 0.5f)));
                float sx = sx0 + (1f - sx0) * hcurve + bulge * (1f - sx0);
                // 浮上来时略微越过静止点
                float r = Math.min(1f, p / 0.8f);
                float rise = 1f - (1f - r) * (1f - r);
                navBar.setTranslationY(ty0 * (1f - rise)
                        - dp(5) * (float) Math.sin(Math.PI * p));
                navBar.setScaleX(sx);
                navBar.setScaleY(sy);
                navBar.setAlpha(Math.min(1f, a0 + p * 1.6f));
            });
        } else {
            animator.setDuration(320);
            animator.addUpdateListener(a -> {
                float p = (Float) a.getAnimatedValue();
                // 竖向先掉，把胶囊压扁
                float v = Math.min(1f, p / 0.7f);
                float sy = sy0 + (tiny - sy0) * v * v;
                // 横向停一拍，最后一次张开，然后收掉
                float lag = Math.min(1f, Math.max(0f, (p - 0.15f) / 0.85f));
                float hcurve = lag < 0.5f
                        ? 2f * lag * lag
                        : 1f - 2f * (1f - lag) * (1f - lag);
                float widen = Math.max(0f, sx0 - tiny) * 0.10f
                        * (float) Math.sin(Math.PI * Math.min(1f, p / 0.4f));
                float sx = sx0 + (tiny - sx0) * hcurve + widen;
                navBar.setTranslationY(ty0 + (tyMax - ty0) * p * p);
                navBar.setScaleX(Math.max(tiny, sx));
                navBar.setScaleY(Math.max(tiny, sy));
                navBar.setAlpha(a0 * (1f - Math.min(1f, p * 1.8f)));
            });
        }
        navMorph = animator;
        animator.start();
    }

    // --------------------------------------------------------- 滚动联动

    /** 一页的霜面进度 [0..1]：正文滚过约 28dp 就到了满强度。 */
    private float pageScrollT(int index) {
        if (pages == null || index < 0 || index >= pages.length) {
            return 0f;
        }
        ScrollView scroll = scrollIn(pages[index]);
        return scroll == null
                ? 0f
                : Math.max(0f, Math.min(1f, scroll.getScrollY() / (float) dp(28)));
    }

    /**
     * 标题边界（双边描边 + 投影 + 霜面）跟着当前页的滚动进度长出来，滚回顶部再退掉。
     *
     * <p>两页之间按翻页器的**小数位置**插值：切页过程中可见的霜面必须在「旧页已滚」和「新
     * 页在顶」之间连续交叉，而不是在 {@code currentTab} 翻过去的那一下跳变。
     */
    private void updateTopEdge() {
        if (topBar == null || topBlur == null || pages == null) {
            return;
        }
        float t = 0f;
        if (pager != null && pager.pageWidth > 0) {
            float frac = pager.getScrollX() / (float) pager.pageWidth;
            int i0 = Math.max(0, Math.min(pages.length - 1, (int) frac));
            int i1 = Math.max(0, Math.min(pages.length - 1, i0 + 1));
            float f = Math.max(0f, Math.min(1f, frac - i0));
            float t0 = pageScrollT(i0), t1 = pageScrollT(i1);
            t = t0 + (t1 - t0) * f;
        }
        if (t == topEdge) {
            return;
        }
        topEdge = t;
        // 霜面强度是独立的量，不是 view alpha：半透明的霜会让正文透上来变成一块灰浆
        topBlur.setFrostStrength(t);
        if (frostVeil != null) {
            frostVeil.setFrostStrength(t);
        }
        topBar.setElevation(dp(12) * t);
        topBlur.setRimStrength(t);
        // 深色下玻璃边缘保留宽羽化直到描边接手；浅色下不羽化，只让描边和阴影淡入
        if (!lightMode) {
            topBlur.setSoftEdge(true, dp(14) * (1f - t));
        }
    }

    /** 三块玻璃一起重拍。整页替换在模糊视图看来和「什么都没发生」一样，不重拍就留着旧背面。 */
    private void refreshGlass() {
        if (topBlur != null) {
            topBlur.refresh();
        }
        if (navBlur != null) {
            navBlur.refresh();
        }
        if (frostVeil != null) {
            frostVeil.refresh();
        }
        if (noticeBlur != null) {
            noticeBlur.refresh();
        }
    }

    // ------------------------------------------------------------- 切页

    private void selectTab(int index, boolean smooth) {
        if (pages == null || index < 0 || index >= pages.length) {
            return;
        }
        boolean leaving = currentTab == 2 && index != 2 && settings != null;
        if (leaving && settings.dirty()) {
            settings.confirmLeave(() -> commitTab(index, smooth));
            return;
        }
        commitTab(index, smooth);
    }

    private void commitTab(int index, boolean smooth) {
        currentTab = index;
        if (index == 2 && settings == null) {
            settings = new SettingsPage(this, chrome);
            replacePage(2, settings.view());
        }
        // 切走时不在这里删第 2 页：这一句跑在吸附动画**开始**的时候，那时设置页还占着大半个
        // 屏幕，一删就能看见它一边滑走一边变空。真正的删除在
        // {@link #materializeSettingsAhead} 里——等它整页滑出视口再删。
        pager.scrollToPage(index, smooth);
        updateTabs();
        setNavVisible(true);
        updateTopEdge();
    }

    /**
     * 手指刚把功能页拖出屏幕、还没松手时，先把设置页画出来。
     *
     * <p>设置页平时不存在——它只在真的停到第 2 页上时才建，切走就丢。于是「从功能页横滑过去」
     * 这条路上，手指拖动的那几百毫秒里右边一直是空的：松手吸附完成，设置页才凭空出现。读起来
     * 就是「切页没有跟手动画」——其实跟手是有的，只是没有东西可跟。首页滑到功能页看不出这个
     * 问题，因为功能页一直在。
     *
     * <p>两个阈值之间留了一段回滞：过三分之一页就建，退回「一整个功能页」才丢。用同一个阈值
     * 收放的话，手指在线上来回抖两下，就会反复把这一页建了又删。
     *
     * <p>退回去要丢掉，是因为它手里那份表是建的时候读的：留着它，功能页改过东西之后滑进来会
     * 看到旧值，一按保存又把旧值写回去。
     */
    private void materializeSettingsAhead() {
        if (pager == null || pager.pageWidth <= 0) {
            return;
        }
        int x = pager.getScrollX();
        int page = pager.pageWidth;
        if (settings == null) {
            if (x > page + page / 3) {
                settings = new SettingsPage(this, chrome);
                replacePage(2, settings.view());
            }
        } else if (x <= page && currentTab != 2) {
            // 第 2 页的左缘正好贴在视口右缘以外：一个像素都看不见了，可以丢。
            //
            // 条件里那句 currentTab != 2 是给「带着未保存改动想切走、又问了一句『放弃吗』然后
            // 选留下」这条路留的：那时手指已经把页拖走了，currentTab 却还是 2，页不能清空。
            settings = null;
            pages[2].removeAllViews();
        }
    }

    private void updateTabs() {
        for (int i = 0; i < TABS.length; i++) {
            boolean selected = i == currentTab;
            tabIcons[i].setColor(selected ? S.accent : S.textDim);
            tabLabels[i].setTextColor(selected ? S.accent : S.textDim);
            tabIcons[i].animate().scaleX(selected ? 1.12f : 1f)
                    .scaleY(selected ? 1.12f : 1f).setDuration(180).start();
        }
    }

    /**
     * 重画会变的那两页。
     *
     * <p>首页和功能页说的全是「此刻」——监听在不在、最后收到了什么、互传范围是什么——拿着
     * 上一次的 View 去刷新，等于维护两份真相。设置页不在这里：见类注释最后一段。
     */
    private void refresh() {
        if (pages == null || isFinishing() || isDestroyed()) {
            return;
        }
        replacePage(0, new HomePage(this, chrome, listener, this::refresh).view());
        replacePage(1, featurePage());
        updateTabs();
    }

    private void replacePage(int index, View content) {
        FrameLayout page = pages[index];
        page.removeAllViews();
        page.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyPageInsets();
        refreshGlass();
    }

    /**
     * 功能页，顺手把表读出来给它。
     *
     * <p>读不出来时不画一个空页：那一页上的每个开关都要写回同一个文件，没有表就没有可以写
     * 的地方。这时给一张卡片说明白，指向设置页那个删除按钮。
     */
    private View featurePage() {
        SettingsScreen screen;
        try {
            screen = SettingsScreen.open(SettingsFile.of(this).toPath());
        } catch (IOException | RuntimeException failed) {
            return brokenFeaturePage(failed);
        }
        return new FeaturePage(this, chrome, listener, screen, this::refresh).view();
    }

    private View brokenFeaturePage(Exception failed) {
        LinearLayout body = chrome.pageBody();
        body.addView(chrome.sectionTitle("接收"));
        LinearLayout card = chrome.card();
        card.addView(chrome.text("设置读不出来", 16, true, S.text));
        TextView why = chrome.caption("开关没有可写的地方：" + failed.getMessage());
        why.setPadding(0, dp(3), 0, 0);
        card.addView(why);
        TextView hint = chrome.caption("到「设置」页可以删除那个文件后重试。");
        hint.setPadding(0, dp(8), 0, 0);
        card.addView(hint);
        body.addView(card, chrome.margins(0, 16));
        return chrome.scroller(body);
    }

    // ------------------------------------------------------------- 提示条

    /**
     * 浮动提示条：一颗真正的玻璃胶囊，落在导航胶囊上方。
     *
     * <p>外层容器必须从文字那一层推导尺寸：模糊子视图的默认测量会吃掉整个可用空间
     * （{@code AT_MOST} 下按最大可用值算），直接把胶囊撑成整屏。
     */
    private View notice() {
        noticeWrap = new FrameLayout(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                measureChild(noticeBar, widthSpec, heightSpec);
                setMeasuredDimension(
                        resolveSize(noticeBar.getMeasuredWidth(), widthSpec),
                        resolveSize(noticeBar.getMeasuredHeight(), heightSpec));
                noticeBlur.measure(
                        MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        };
        noticeWrap.setElevation(dp(8));
        noticeWrap.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });

        noticeBar = chrome.text("", 13, true, S.text);
        noticeBar.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        // 盖在模糊层上的染色，带一点透明，让底下的霜面还看得见
        bg.setColor(lightMode ? 0xD9F5F5F5 : 0xD95F5F5F);
        noticeBar.setBackground(bg);
        noticeBar.setPadding(dp(18), dp(11), dp(18), dp(11));

        noticeBlur = new RealtimeBlurView(this);
        noticeBlur.setCaptureRoot(pager);
        noticeBlur.setCornerRadius(dp(14));
        noticeBlur.setFill(S.bg);
        noticeBlur.setLiquidGlass(true);
        noticeWrap.addView(noticeBlur, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        noticeWrap.addView(noticeBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        noticeWrap.setVisibility(View.GONE);
        return noticeWrap;
    }

    private void showNotice(String message, int color) {
        if (noticeWrap == null) {
            return;
        }
        root.removeCallbacks(noticeHide);
        noticeBar.setText(message);
        noticeBar.setTextColor(color);
        noticeWrap.setVisibility(View.VISIBLE);
        noticeWrap.setAlpha(0f);
        noticeWrap.setTranslationY(dp(12));
        noticeWrap.animate().alpha(1f).translationY(0f).setDuration(180).start();
        root.postDelayed(noticeHide, 3600);
    }

    private void hideNotice() {
        if (noticeWrap == null || noticeWrap.getVisibility() != View.VISIBLE) {
            return;
        }
        noticeWrap.animate().alpha(0f).translationY(dp(12)).setDuration(160)
                .withEndAction(() -> noticeWrap.setVisibility(View.GONE)).start();
    }

    /**
     * 拿一段假进度试一次实时通知。
     *
     * <p>为什么要有它：{@link LiveCard} 能不能用，取决于系统的两个开关（版本、以及用户有没有
     * 关掉本应用的实时通知），而且「提升成功」这件事**没有任何回调**——只有状态栏那颗胶囊会
     * 不会出现。所以验证方式只能是发一条真的看屏幕，而这条路径不该被塞进任何一次真实传输里
     * 去赌。
     */
    private void liveProbe(String kind) {
        boolean allowed = LiveCard.allowed(this);
        Log.i("OPAirDrop", "live probe (" + kind + "): allowed=" + allowed);
        showNotice(allowed ? "系统允许实时通知，看状态栏那颗胶囊" : "系统不允许实时通知，只会是一条普通通知",
                S.text);
        if ("invite".equals(kind) || "invite_high".equals(kind) || "invite_twice".equals(kind)) {
            // 带两个按钮的邀请卡。三种投法测三件事：
            //   invite      —— 低重要性渠道（现状）
            //   invite_high —— 高重要性渠道，看抬头会不会把胶囊撑开
            //   invite_twice—— 同一条通知再发一遍，看内容变化会不会触发展开
            if ("invite_high".equals(kind)) {
                LiveCard.invite(this, "卷卷薯 想发给你文件", "1 个文件 · 2.4 MB", open(41), open(42));
            } else {
                LiveCard.invite(this, "卷卷薯 想发给你文件", "1 个文件 · 2.4 MB", open(41), open(42));
                if ("invite_twice".equals(kind)) {
                    new android.os.Handler(getMainLooper()).postDelayed(
                            () -> LiveCard.invite(this, "卷卷薯 想发给你文件",
                                    "1 个文件 · 2.4 MB · 刚刚", open(41), open(42)), 900);
                }
            }
            return;
        }
        new Thread(() -> {
            String title = "收到 1 个文件";
            String line = "青 · 正在接收";
            for (int percent = 0; percent <= 100; percent += 4) {
                int now = percent;
                runOnUiThread(() -> LiveCard.update(this, title, line, now));
                android.os.SystemClock.sleep(400);
            }
            runOnUiThread(() -> LiveCard.finish(this, "接收完成", "1 个文件 · 2.4 MB"));
        }, "live-probe").start();
    }

    /** 按钮落点：把应用拉到前面。探针只关心按钮在不在、点得到，不关心点了做什么。 */
    private android.app.PendingIntent open(int request) {
        return android.app.PendingIntent.getActivity(this, request,
                new android.content.Intent(this, MainActivity.class),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
    }
}
