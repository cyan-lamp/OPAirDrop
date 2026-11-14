package com.oshare.airdrop;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.LinearGradient;
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
     * 跟手位移每帧朝目标走多少。
     *
     * <p>0.6 是「跟手」与「不抖」之间的取舍：1.0 完全跟手，但把输入事件的 3–8 像素回跳也
     * 一起画出来；0.3 很稳。0.6 的滞后约一帧。
     */
    private static final float SMOOTH = 0.6f;

    /** 面板顶部的圆角。和导航胶囊同一个数。 */
    private static final float CORNER_DP = 28f;

    /** 面板顶部那圈模糊的半径。系统模糊，单位是屏幕像素。 */
    private static final float BLUR_DP = 28f;

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
    /** 面板背景的实时模糊；{@code null} 表示这台机器给不了，走纯色退路。 */
    private PanelGlass glass;
    private volatile boolean sending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        S = schemeOf(getResources().getConfiguration());
        density = getResources().getDisplayMetrics().density;
        applyWindow();

        buildShell();
        slideIn();

        new Thread(this::intake, "opairdrop-intake").start();
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
     * 窗口本身保持透明，什么模糊都不加。
     *
     * <p>模糊挪到面板那个 View 上去了（{@link PanelGlass}）：窗口级模糊会把面板以外整片屏幕
     * 一起糊掉，而且每帧重采整屏。窗口这一层只要透明，让圆角外面能透出背后的界面。
     */
    private void applyWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    }

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
        panel.radius(dp(BLUR_DP));
        panel.color(glassTint());
        panel.corners(dp(CORNER_DP), 0f);
        sheet.setBackground(panel.drawable());
        android.util.Log.i("OPAirDrop", "panel glass: " + panel.source()
                + " radius=" + dp(BLUR_DP) + " corner=" + dp(CORNER_DP));
    }

    /** 系统栏图标跟着深浅走。主题里那两条只在 Activity 创建时生效一次。 */
    private void applyWindowBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
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
        int alpha = S.light ? 0xC0 : 0xC8;
        return (S.bg & 0x00FFFFFF) | (alpha << 24);
    }

    /** 换配色时重刷玻璃的染色；没有玻璃就刷退路底色。 */
    private void tintSheet() {
        if (sheet == null) {
            return;
        }
        if (glass != null) {
            glass.color(glassTint());
            return;
        }
        Drawable background = sheet.getBackground();
        if (background instanceof GradientDrawable) {
            ((GradientDrawable) background).setColor(sheetColor());
        }
    }

    // ------------------------------------------------------------------ 骨架

    private void buildShell() {
        sheet = new Sheet(this);

        GradientDrawable shape = new GradientDrawable();
        float radius = dp(CORNER_DP);
        // 上两角圆、下两角方：它是从底边长出来的一整块，底边在屏幕外面。
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0f, 0f, 0f, 0f});
        shape.setColor(sheetColor());
        sheet.setBackground(shape);

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

        setContentView(sheet);
        applyWindowBars();
        // 玻璃要等 attach 之后才装得上（模糊 Drawable 由 ViewRootImpl 创建），post 正好等到那一刻。
        sheet.post(this::installGlass);
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
            sheet.setTranslationY(height > 0 ? height : dp(600));
            sheet.setVisibility(View.VISIBLE);
            layer(true);
            sheet.animate()
                    .translationY(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(() -> layer(false))
                    .start();
        });
    }

    /**
     * 把面板提升成一个硬件层，或还回去。
     *
     * <p># 为什么位移必须走层
     *
     * <p>{@code setTranslationY} 不是一次「搬动」，它会让这个 View 连同整棵子树重新绘制一遍：
     * 每一帧都要重新栅格化这块 1440×3168 上的全部文字、再走一遍那条全屏圆角描边路径。文字在
     * 每一帧落到略微不同的亚像素位置上，抗锯齿结果就跟着变——肉眼看到的就是**字在抖**，同时
     * 帧预算被烧光。
     *
     * <p>硬件层之后，内容只栅格化一次，之后每帧只是把这张纹理挪个位置，由合成器完成。代价是
     * 一张全屏纹理（约 18 MB），所以只在移动期间拿着，停下就还回去——常驻的话它和窗口模糊
     * 抢同一块 GPU 内存，反而更慢。
     */
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
        if (!on) {
            dragging = false;
            // 拖动期间雷达停了呼吸（理由见 {@link Radar#onDraw}），还回层之后要把它叫醒。
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
        private float downY;
        private float downX;
        /**
         * 按下那一刻面板已经在的位置。
         *
         * <p>位移必须相对**这里**算，不能相对 0：一次没滑走的手势结束后，回弹动画的最后一帧
         * 和下一次按下之间可能只隔了几十毫秒，面板停在半路；若还按「手指位移 = 面板位移」，
         * 第一个 MOVE 就会把它从半路拽回顶部——看起来是手指一按，面板自己跳了一下，接着怎么
         * 拖都对不上。真机上撞到的就是这个。
         */
        private float startY;
        /**
         * 当前真正画在屏幕上的位移。
         *
         * <p>它不是 {@code startY + 手指位移}：输入事件本身是抖的。同一串 MOVE 里相邻两个
         * 采样点会出现「先到 1254、退回 1251、再到 1288」这种回跳，幅度 3–8 像素；1:1 照抄
         * 就是把手指的抖动原样画出来，读起来是面板在颤。这里用指数平滑把它压掉：每帧朝目标
         * 走 60%，滞后约一帧（16ms），人感觉不到，抖动能滤掉大半。
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

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downX = event.getX();
                    downY = event.getY();
                    moving = false;
                    // 上一个回弹或上滑可能还在跑。它每帧都往 translationY 里写一个值，和手指
                    // 写进去的那个互相覆盖——同一帧里先被动画拉回、再被手指推开，读起来就是
                    // 整个窗口在上下摆。先让它停，位置从此只由手指决定。
                    animate().cancel();
                    // 上一段滑出被这一下按停，closing 要放掉：见 onTouchEvent 里的同名分支。
                    closing = false;
                    // 按下就建层：见 {@link #layer} 里「为什么在按下时就建」。
                    layer(true);
                    // 停在哪就从哪接着拖：见 {@link #startY}。
                    startY = getTranslationY();
                    shown = startY;
                    tracker.clear();
                    tracker.addMovement(event);
                    return false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dy = event.getY() - downY;
                    float dx = Math.abs(event.getX() - downX);
                    // 只接管「明显向下、且比横向更竖」的移动。横向留给以后可能加的翻页，
                    // 而点击（位移不到 slop）根本不进这里。
                    if (dy > slop && dy > dx) {
                        tracker.addMovement(event);
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
            tracker.addMovement(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downX = event.getX();
                    downY = event.getY();
                    moving = false;
                    animate().cancel();
                    // 上一段滑出被这一下按停：把 closing 放掉，否则它会一直为真，而上面那个
                    // 判断会把此后**每一次**触摸都吞掉——面板从此完全不动，看起来就是卡死。
                    // 这是只能靠真机发现的：`close()` 的 finish 挂在动画的 end action 上，
                    // 动画被取消，那个 end action 就永远不会跑。
                    closing = false;
                    layer(true);
                    startY = getTranslationY();
                    shown = startY;
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
                    // 只往下拖。往上推没有对应语义（这一页已经在最上面了），硬跟手会读成失灵。
                    //
                    // 不取整：层已经建好，小数位移只是把纹理放到下一个十六分之一像素上，由
                    // 合成器插值，本来就平滑；取整反而把它变成 1 像素一级的台阶——手指每帧
                    // 移动不到一像素时，那正是「整个窗口在抖」的来源。
                    float dy = Math.max(0f, event.getY() - downY);
                    float target = startY + dy;
                    shown += (target - shown) * SMOOTH;
                    if (Math.abs(target - shown) < 0.5f) {
                        shown = target;
                    }
                    setTranslationY(shown);
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
                    boolean leave = getTranslationY() > getHeight() * 0.18f || velocity > fling;
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
        sheet.animate()
                .translationY(0f)
                .setDuration(SPRING_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> layer(false))
                .start();
    }

    /** 滑出屏幕再结束。返回键与下滑走同一条路，读起来是同一个动作。 */
    private void close() {
        if (closing) {
            return;
        }
        closing = true;
        // 返回键那条路没有拖动过，层还没建；滑出去的这段同样要走层。
        layer(true);
        sheet.animate()
                .translationY(sheet.getHeight())
                .setDuration(EXIT_MS)
                .setInterpolator(new AccelerateInterpolator(1.4f))
                .withEndAction(this::finish)
                .start();
        // 兜底。上面那个 end action 挂在动画上，而动画是可以被下一次按下取消的——那一刻
        // finish 就永远不会来，页面停在一半、closing 一直为真。带着 closing 再判一次：中途
        // 被按停时那个标记已经被清掉，这一下什么也不做。
        sheet.postDelayed(() -> {
            if (closing) {
                finish();
            }
        }, EXIT_MS + 150);
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
        try {
            byte[] text = Native.loadSettings(SettingsFile.of(this).getAbsolutePath());
            for (String line : new String(text, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                int equals = line.indexOf('=');
                if (equals > 0 && line.substring(0, equals).trim().equals("device.name")) {
                    String value = line.substring(equals + 1).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception unreadable) {
            // 读不出来就用型号：这一项不是必填。
        }
        return modelName();
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
