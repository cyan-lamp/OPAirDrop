package com.oshare.airdrop;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
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
import android.widget.TextView;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

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

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 300_000;

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

    private Peers peers;

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

    /** 每台设备一句话状态，键是设备名；重扫与换主题时复用，避免文字闪一下。 */
    private final Map<String, String> statuses = new LinkedHashMap<>();
    /** 每台设备当前的进度 [0..1]。环只认它，所以重建之后环还在原处。 */
    private final Map<String, Float> progressOf = new LinkedHashMap<>();
    /** 当前这一份视图里的头像与状态字。重建时清空重填，进度更新按名字找。 */
    private final Map<String, Nick> nicks = new LinkedHashMap<>();
    private final Map<String, TextView> stateViews = new LinkedHashMap<>();

    /** 正在滑出。防止连按两次返回键跑两遍动画。 */
    private boolean closing;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        S = schemeOf(getResources().getConfiguration());
        density = getResources().getDisplayMetrics().density;

        buildShell();
        showPanel();
        slideIn();

        new Thread(this::intake, "opairdrop-intake").start();
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

    @Override
    protected void onStart() {
        super.onStart();
        peers = new Peers(this, this);
        peers.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (peers != null) {
            peers.stop();
            peers = null;
        }
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
            android.util.Log.i("OPAirDrop", "panel glass: unavailable, solid background");
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
        android.util.Log.i("OPAirDrop", "panel glass: " + panel.source()
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

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), 0, dp(20), 0);
        content.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(topBar());
        column.addView(subtitleLine());

        stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        // 中部整块上下居中：雷达与它下面那行字是一个整体，居中的是整体，不是雷达自己。
        stage.setGravity(Gravity.CENTER);
        column.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(bottomCard());

        renderStage();
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
            sheet.invalidate();
        }
    }

    /** 正在拖动 / 正在做位移动画。它决定雷达要不要每 32ms invalidate 一次。 */
    private boolean dragging;

    // ------------------------------------------------------------------ 顶部

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(14), 0, dp(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(24);
        back.setTextColor(S.text);
        back.setGravity(Gravity.CENTER);
        // 用卡片色而不是 cardHi：cardHi 和页底色几乎同值（#F1F3F6 / #F0F1F3），浅色下那圈底
        // 完全看不出来，只剩一个孤零零的「‹」浮在玻璃上。
        back.setBackground(round(S.card, 22));
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
        nicks.clear();
        stateViews.clear();

        if (found.isEmpty()) {
            Radar radar = new Radar();
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(180), dp(180));
            size.gravity = Gravity.CENTER_HORIZONTAL;
            stage.addView(radar, size);

            TextView hint = new TextView(this);
            hint.setText(searching);
            hint.setTextSize(14);
            hint.setTextColor(S.textDim);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(20), 0, 0);
            stage.addView(hint);
            return;
        }

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setGravity(Gravity.CENTER);
        for (Peers.Peer peer : found) {
            grid.addView(deviceCell(peer));
        }
        stage.addView(grid);
    }

    @Override
    public void onPeers(List<Peers.Peer> peers, String problem) {
        List<Peers.Peer> usable = new ArrayList<>();
        for (Peers.Peer peer : peers) {
            if (peer.connectable()) {
                usable.add(peer);
            }
        }
        found = usable;
        searching = problem != null ? problem : "正在搜索附近设备……";

        // 设备集合没变就不重建：重建会把正在跑的那圈进度丢掉一帧，而进度每秒来两次。
        if (!nicks.keySet().equals(namesOf(usable))) {
            renderStage();
        }
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
        cell.setPadding(dp(8), 0, dp(8), dp(16));

        Nick nick = new Nick();
        cell.addView(nick, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView name = new TextView(this);
        name.setText(peer.name);
        name.setTextSize(14);
        name.setTextColor(S.text);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(8), 0, 0);
        cell.addView(name);

        TextView state = new TextView(this);
        state.setTextSize(12);
        state.setTextColor(S.accent);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dp(4), 0, 0);
        String known = statuses.get(peer.name);
        state.setText(known == null ? "" : known);
        cell.addView(state);

        nicks.put(peer.name, nick);
        stateViews.put(peer.name, state);
        // 重建之前就已经在传的那台，环要接着上一次的位置画，而不是从 0 开始。
        Float progress = progressOf.get(peer.name);
        if (progress != null) {
            nick.progress(progress);
        }

        cell.setOnClickListener(view -> send(peer));
        return cell;
    }

    // ------------------------------------------------------------------ 底部

    /** 说明卡：和参照一样，告诉人为什么对面还没出现。 */
    private View bottomCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(S.card, 22));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(20);
        card.setLayoutParams(params);

        TextView head = new TextView(this);
        head.setText("如何与 iPhone 互传？");
        head.setTextSize(15);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(S.text);
        card.addView(head);

        TextView body = new TextView(this);
        body.setText("方式一：在控制中心打开 NFC，碰触一下 iPhone 即可互传\n"
                + "方式二：iPhone 安装并打开「OPPO 互联」应用");
        body.setTextSize(12.5f);
        body.setTextColor(S.textDim);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(8), 0, 0);
        card.addView(body);
        return card;
    }

    // ------------------------------------------------------------------ 传输

    private void intake() {
        ShareIntake.Batch batch = ShareIntake.resolve(this, getIntent());
        runOnUiThread(() -> {
            files.clear();
            files.addAll(batch.files);
            totalBytes = 0;
            for (File file : files) {
                totalBytes += file.length();
            }
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
        sending = true;
        status(peer, "正在连接…");

        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }

        new Thread(() -> {
            try {
                byte[] frame = transfer(peer, paths);
                Report report = Report.parse(
                        new String(frame, java.nio.charset.StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    sending = false;
                    // 环褪回未开始：留着满圈会读成「还在跑」，而它已经跑完了。
                    progressOf.remove(peer.name);
                    redrawProgress(peer.name);
                    status(peer, "已发送 · " + size(report.bytes));
                });
            } catch (Exception failed) {
                String message = failed.getMessage() == null ? failed.toString() : failed.getMessage();
                runOnUiThread(() -> {
                    sending = false;
                    progressOf.remove(peer.name);
                    redrawProgress(peer.name);
                    status(peer, message);
                });
            }
        }, "opairdrop-send").start();
    }

    private byte[] transfer(Peers.Peer peer, List<String> paths) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(peer.address, peer.port), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        SSLSocket tls = (SSLSocket) trusting().getSocketFactory()
                .createSocket(raw, peer.host.isEmpty() ? peer.address : peer.host, peer.port, true);
        tls.setUseClientMode(true);
        tls.startHandshake();

        try (SSLSocket session = tls) {
            return Native.sendWithProgress(
                    peer.host.isEmpty() ? peer.address : peer.host,
                    settingsText(),
                    senderName(),
                    modelName(),
                    UUID.randomUUID().toString(),
                    paths.toArray(new String[0]),
                    session.getInputStream(),
                    session.getOutputStream(),
                    (phase, sent, total) -> runOnUiThread(() -> {
                        switch (phase) {
                            case Native.Progress.READY -> status(peer, "已连接，正在请求发送…");
                            case Native.Progress.WAITING -> status(peer, "等待对方接受…");
                            case Native.Progress.SENDING -> {
                                progressOf.put(peer.name,
                                        total == 0 ? 0f : Math.min(1f, (float) sent / total));
                                redrawProgress(peer.name);
                                status(peer, "发送中 "
                                        + (total == 0 ? 0 : sent * 100 / total) + "%");
                            }
                            case Native.Progress.DONE -> {
                                progressOf.put(peer.name, 1f);
                                redrawProgress(peer.name);
                                status(peer, "已发送，等待确认…");
                            }
                            default -> {
                            }
                        }
                    }));
        }
    }

    /** 写一句状态。存进表里，重建之后还能重放。 */
    private void status(Peers.Peer peer, String line) {
        statuses.put(peer.name, line);
        TextView state = stateViews.get(peer.name);
        if (state != null) {
            state.setText(line);
        }
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
                android.util.Log.d("OPAirDrop", "layout during motion: asked=" + top
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
                        android.util.Log.d("OPAirDrop", "drag finger=" + dy
                                + " panel=" + (shown - startTop) + " top=" + sheet.getTop()
                                + " frac=" + sheet.getTranslationY());
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    boolean dragged = moving;
                    moving = false;
                    if (!dragged) {
                        // 只是点了一下（或者按在空白处又抬手）：层是按下时建的，这里还回去，
                        // 否则它会和雷达的呼吸动画长期叠在一起——层里每一次 invalidate 都要
                        // 重画整块纹理。
                        layer(false);
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

    private static SSLContext trusting() throws Exception {
        TrustManager[] trust = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust, new SecureRandom());
        return context;
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
