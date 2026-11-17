package com.oshare.airdrop;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 功能页：装驱动、卸载驱动，加一根共享 Wi-Fi 射频的开关。
 *
 * <p># 这一页有哪几块
 *
 * <p>驱动、射频、保存位置、设备显示。接收开关在首页（接收文件那一行），其余几个开关随「脱离
 * 一加互传」一起删掉（同名文件固定加编号、不再跟随互传），传输状态也不需要了。驱动管装和
 * 卸——有时候要恢复原厂驱动和网络环境；射频是唯一还留着的开关，因为它拨错了手机就上不了网；
 * 保存位置决定文件落哪个目录；设备显示决定对端在列表里看到什么名字。
 *
 * <p># 驱动为什么在最前面
 *
 * <p>它一天只按一次，按完之后整天都不再碰——按「常用程度」排，它应该在最下面。但它换的是
 * 手机的内核驱动：下面那张卡说的是「此刻的链路」，而冷启动之后 {@code wonder0} 根本还不存在，
 * 那张卡在说的是一个没有的东西。把前提放在末尾，等于让最要紧的那件事要滚过一张「此刻没有链路」
 * 的卡才找得到。所以它排第一，而它下面那句话也只说一件事：这条链路现在到哪一步了。
 */
final class FeaturePage {
    private static final String TAG = "OPAirDrop";

    /**
     * 换驱动那半分钟里，按钮下面那句话。
     *
     * <p>写清楚「Wi-Fi 会断」是这一句的全部目的：这半分钟里手机的 Wi-Fi 图标会消失，如果
     * 页面上只说「正在装」，那个人会以为装坏了。
     */
    private static final String RUNNING_LINE = "正在换驱动并建链，大约半分钟，Wi-Fi 会断。";

    /**
     * 等回执时多久看一眼那两个文件。
     *
     * <p>250ms 是守护进程自己的轮询节奏（{@code awdld} 每 250ms 读一次请求文件）。比它更密只是
     * 白读同一份盘面，比它更疏则回执已经写完、页面还空着，看起来像没反应。
     */
    private static final int ANSWER_TICK_MS = 250;

    /**
     * 最多看多少眼，八十眼即二十秒。
     *
     * <p>二十秒是守护进程开 Wi-Fi 的上限（{@code ON_LIMIT_US}）：它自己都等不到的事，这一页
     * 没有理由等更久。到了这里不停在「失败」上——停在哪句话由
     * {@link RadioShare.State#subtitle()} 说，这里只是不再看了。
     */
    private static final int ANSWER_TICKS = 80;

    /**
     * 输入浮窗那张卡的圆角，单位 dp。
     *
     * <p>22 不是随便挑的：它就是 {@code Chrome.CARD_RADIUS}，页面里每一张卡的圆角。浮窗是从
     * 「保存位置」那一行里弹出来的，如果它比那一行所属的卡更圆或更方，看起来就像另一个 App 的
     * 控件飘在上面。同一档半径让它是「那张卡被拎起来了」，而不是「弹了个对话框」。
     *
     * <p>这里抄一份常量而不是直接读 {@code Chrome.CARD_RADIUS}，是因为那个字段是 private——
     * 页面与 Chrome 之间只走方法，不互相翻对方的常量表。要改就两处一起改，注释互相指着。
     */
    private static final float SHEET_RADIUS_DP = 22f;

    /**
     * 浮窗霜面的模糊半径，单位是 {@link RealtimeBlurView} 的「scratch 像素」（屏幕上看到
     * 的扩散约是这个数的两倍，见它里面的 {@code FACTOR}）。
     *
     * <p># 为什么从 5 改成 20
     *
     * <p>5 是这条玻璃原来的值，和导航胶囊、顶部霜面一样。那三块都是**窄条**（高度不到 60dp），
     * 背后露出来的内容只是「一条边」，5 已经够糊。浮窗不一样：它是 86% 屏宽、一两百 dp 高的一
     * 整块，背后是整页的文字。同样 5 的半径，在这么大片面积上只够把字糊成灰色轮廓——人眼仍
     * 然认得出那是哪一行，于是读起来是「卡片是透明的」。
     *
     * <p>20 是 5 的四倍，屏幕上约 40px 的扩散。到这个程度，背后那页只剩色块与明暗，卡片才
     * 真的是一张卡，而不是一块盖在字上的玻璃。
     */
    private static final float SHEET_BLUR_RADIUS = 20f;

    /**
     * 浮窗里输入框、取消、保存三件东西的底色不透明度，单位 0-255。
     *
     * <p>0xBF = 191 ≈ 75%。这三块原来是实底（{@code cardHi} / {@code accent} 原样），压在
     * 一片强模糊的霜面上显得像贴上去的三张纸；给到 75%，霜面的明暗能从底下透上来一点，它们
     * 才像长在这张卡上的一层。
     */
    private static final int SHEET_CONTROL_ALPHA = 0xBF;

    private final Activity host;
    private final Chrome chrome;
    private final Scheme S;
    private final Listener listener;
    /** 这页读到的那份表；每次切到本页都重新打开，别的页改过的值才会被看见。 */
    private final SettingsScreen screen;
    /** 整页重画的入口；只有一件事之后用得着它，见 {@link #showDriver}。 */
    private final Runnable onChanged;

    /**
     * 驱动卡上的四个零件：徽章、那句话、按钮、回执原文。
     *
     * <p>装一次要半分钟，这半分钟里这四样都得变（按钮置灰、跳秒、最后换成回执），而这一页
     * 不重建——所以留着引用就地改。
     */
    private TextView driverPill;
    private TextView driverNote;
    private TextView uninstallButton;
    private TextView driverButton;
    private TextView driverReceipt;

    /** 卸载在跑。和安装互斥：两个都动内核模块，一起来只会把回执写乱。 */
    private boolean uninstallBusy;

    /**
     * 这一页自己记的「正在装」。
     *
     * <p>与 {@link DriverSetup#running()} 并存不是重复：那个是跨页面重建的真相，这个是这一页
     * 那一轮跳秒的开关。启动那一刻 {@code RUNNING} 还没置上，只看它的话计时器会立刻退出。
     */
    private boolean driverBusy;

    /** 第几轮装驱动。换一轮就 +1，旧轮的计时器和回调凭这个认出自己已经过期。 */
    private int driverRun;

    FeaturePage(Activity host, Chrome chrome, Listener listener, SettingsScreen screen,
                Runnable onChanged) {
        this.host = host;
        this.chrome = chrome;
        this.S = chrome.scheme();
        this.listener = listener;
        this.screen = screen;
        this.onChanged = onChanged;
    }

    View view() {
        LinearLayout body = chrome.pageBody();

        // 驱动排在最前，尽管它一天只按一次（每次重启按一次）。理由在类注释「驱动为什么在
        // 最前面」那一段：冷启动之后 wonder0 根本不存在，下面每一张卡说的都是一个还没建起来
        // 的链路。放在最底下的话，最要紧的那件事要滚过三张「此刻没有链路」的卡才找得到。
        body.addView(chrome.sectionTitle("驱动"));
        TextView driverHint = chrome.caption(
                "把原厂的 qca 与 mac80211 换成打过补丁的两个，再装 wonder.ko 建链。约半分钟，"
                        + "每次重启后要再按一次。");
        driverHint.setPadding(chrome.dp(6), 0, chrome.dp(6), chrome.dp(8));
        body.addView(driverHint);
        body.addView(driverCard(), chrome.margins(0, 8));

        // 接收板块已撤：接收开关在首页「接收文件」那一行，设备名在设置页，这里不再重复。
        // 开关板块也撤了：同名文件固定加编号、跟随一加互传随脱离互传一起去掉，没有可拨的开关；
        // 射频这一项是唯一仍要拨的，留在这一页。传输状态也不需要了。
        body.addView(chrome.sectionTitle("射频"), chrome.margins(6, 0));
        TextView radioNote = chrome.caption(
                "共用一根天线：共用时会丢一部分包，丢多少取决于 Wi-Fi 落在哪个信道，回执里的 MHz 就是落点。");
        radioNote.setPadding(chrome.dp(6), 0, chrome.dp(6), chrome.dp(8));
        body.addView(radioNote);
        body.addView(radioCard(), chrome.margins(0, 8));

        // 接收文件保存位置原先在设置页的「接收」里。那一块撤了，但这一项是唯一还在用的接收设置，
        // 于是单独拎出来放在这里：它和驱动/射频一样是「按了就换行为」的东西，留一席之地。
        body.addView(chrome.sectionTitle("保存位置"), chrome.margins(6, 0));
        body.addView(directoryCard(), chrome.margins(0, 8));

        // 设备显示：原来在设置页叫「设备」，是一行文本设置（对端看到的名字）。它是「改完对端
        // 立刻读到什么」的行为，和这一页其余「拨一下就换行为」的东西同类，于是搬过来。原先还有
        // 第二行改机型，已撤——理由见 deviceCard()。
        body.addView(chrome.sectionTitle("设备显示"), chrome.margins(6, 0));
        body.addView(deviceCard(), chrome.margins(0, 8));

        return chrome.scroller(body);
    }

    /**
     * 驱动：一颗按钮，加一行回执。
     *
     * <p># 为什么这一页要有一颗「装驱动」的按钮
     *
     * <p>在这之前，装驱动是在电脑上敲 {@code adb shell su -c "sh awdl-stack.sh boot"}。那件事
     * 不能在重启之后由 App 自己做——{@code su} 要人来按，而开机自启一个 {@code su} 调用等于
     * 把提权做成常驻。所以做成一颗按了才发生的按钮：整包资产在 APK 里，按下去解包、交给
     * {@code su}、跑脚本，人只需要点一下。
     *
     * <p># 为什么显示的是回执的那一句，而不是这里编的一句
     *
     * <p>这条链路有四种失败，每一种要说的话都不一样（Wi-Fi 关不掉、原厂驱动卸不掉、装上了
     * 但没注册出 wiphy、链路起来了守护进程没起）。这些话只有脚本知道，{[@code StackState]}
     * 负责把它们翻成人话，这一页只负责印。要是这里再写一句「安装失败，请重试」，那四种失败
     * 就都变成了同一句话——而人正是靠这四种的区别决定下一步做什么（换内存？关 HAL？重启？）。
     *
     * <p># 为什么按钮下面是回执原文
     *
     * <p>人话会概括，原文不会。{@code stack failed rc=16 stage=driver-swap detail=...} 那一行
     * 是排障时唯一能贴给别人看的东西，所以它原样留着，缩到 12.5sp、灰的，不跟人话抢。唯一的
     * 例外是它来自上一次开机：那时前面垫一句「上次开机：」，见 {@link #receiptText}。
     *
     * <p># 为什么回执要认开机
     *
     * <p>{@code files/stack-state} 是个普通文件，关机不会清掉它。重启之后它还写着
     * {@code daemon=28854}，而那个进程早就不存在——照原样画出来，就是在这个人正举着手机想
     * 「为什么对面看不见我」的时候，告诉他「已就绪」。所以这里问 {@link
     * StackState.State#stale()}，回执不是本次开机的就一律降级：徽章灰、按钮退回「装驱动并建链」、
     * 原文带前缀。
     */
    private View driverCard() {
        // 跨页面重建的「正在装」只有 DriverSetup 知道：这一页在半分钟里被切走再切回来时，
        // 新一轮实例读到的 RUNNING 还是 true，于是接着画那颗灰按钮，而不是又给一颗能按的。
        driverBusy = DriverSetup.running();
        StackState.State state = StackState.read(filesDir());
        // stop 不清回执文件：卸载后 stack-state 里还是那行「stack up」，boot 又没变，于是按回执
        // 读出来仍是「已就绪」——刚好说反。用一份持久标记记住「人按过卸载且成功」，重画时以它
        // 为准，直到下一次装成功才清掉。安装只会置回，不会凭空冒出「已就绪」。
        boolean uninstalled = uninstalled();
        LinearLayout card = chrome.card();

        LinearLayout head = chrome.row();
        head.addView(chrome.text("内核驱动", 16, true, S.text),
                chrome.mm(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        driverPill = chrome.statusPill(
                driverBusy ? "正在装" : uninstallBusy ? "正在卸"
                        : uninstalled ? "未安装" : standingLabel(state),
                driverBusy || uninstallBusy ? S.accent
                        : uninstalled ? S.gray : standingColor(state));
        head.addView(driverPill);
        card.addView(head);

        driverNote = chrome.text(
                driverBusy ? RUNNING_LINE
                        : uninstalled ? "已恢复原厂驱动与网络环境，需要时按【安装】重新建链"
                        : state.subtitle(),
                13.5f, false,
                driverBusy ? S.textDim : uninstalled ? S.textDim : noteColor(state));
        driverNote.setPadding(0, chrome.dp(8), 0, chrome.dp(12));
        card.addView(driverNote);

        // 卸载在左、安装在右，同一行各占一半：卸载是「回到原厂」的退路，装是常规动作。
        // 装/卸都会动内核模块，同一时刻只允许一个在跑，另一个跟着置灰。
        LinearLayout actions = chrome.row();
        uninstallButton = chrome.ghostButton("卸载", view -> startUninstall());
        driverButton = chrome.mainButton(driverBusy ? "正在装…" : buttonLabel(state),
                view -> startDriver());
        // mainButton 默认 padding 20/13、字 15sp，比 ghostButton 的 16/11、字 14sp 大一圈，
        // 两个并排时蓝底比灰底高一截。改成和卸载一致的内边距与字号，蓝底灰底一般大。
        driverButton.setPadding(chrome.dp(16), chrome.dp(11), chrome.dp(16), chrome.dp(11));
        driverButton.setTextSize(14);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(uninstallButton, half);
        LinearLayout.LayoutParams halfRight = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        halfRight.setMargins(chrome.dp(8), 0, 0, 0);
        actions.addView(driverButton, halfRight);
        card.addView(actions, chrome.mm(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0f));
        boolean busy = driverBusy || uninstallBusy;
        uninstallButton.setEnabled(!busy);
        uninstallButton.setAlpha(busy ? 0.5f : 1f);
        driverButton.setEnabled(!busy);
        driverButton.setAlpha(busy ? 0.5f : 1f);

        driverReceipt = chrome.caption(receiptText(state));
        driverReceipt.setTextColor(receiptColor(state));
        driverReceipt.setPadding(0, chrome.dp(10), 0, 0);
        driverReceipt.setVisibility(state.present() ? View.VISIBLE : View.GONE);
        card.addView(driverReceipt);

        if (driverBusy) {
            // 这一页是「半路接上」的：那一轮不归它启动，但跳秒得从它这里接着数。
            followDriver(++driverRun, 0);
        }
        return card;
    }

    /**
     * 按下去：置灰、起线程、回主线程收尾。
     *
     * <p>{@link DriverSetup#boot} 会阻塞半分钟（等 {@code su}、等脚本），所以只能在别的线程
     * 里跑；主线程上那半分钟是「无响应」对话框，而这个 App 的自用场景里，人正是在盯着这一页
     * 看它动。
     *
     * <p>名字在这里读，不在线程里读：{@code screen} 是这一页的东西，跨线程读它就是一份没人
     * 保证的盘面。名字留空就是让守护进程用手机的主机名（{@code device.name} 的说明里写着
     * 同一件事），所以空串是正常输入，不是缺失。
     */
    private void startDriver() {
        if (driverBusy || DriverSetup.running() || driverButton == null) {
            return;
        }
        driverBusy = true;
        int run = ++driverRun;
        SettingsScreen.Setting named = screen.setting("device.name");
        String airdropName = named == null ? "" : named.value().trim();
        // Logged at entry, not only at the end: a run on a phone without root dies in under a
        // second and produces no script output, so this line is the only proof of *when* it
        // started — which is what tells a stray press apart from the page running it itself.
        Log.i(TAG, "driver| press run=" + run);

        driverButton.setEnabled(false);
        driverButton.setAlpha(0.5f);
        driverButton.setText("正在装…");
        if (driverPill != null) {
            chrome.setPill(driverPill, "正在装", S.accent);
        }
        driverNote.setTextColor(S.textDim);
        driverNote.setText(RUNNING_LINE);
        followDriver(run, 0);

        new Thread(() -> {
            DriverSetup.Result result = DriverSetup.boot(host, airdropName);
            host.runOnUiThread(() -> showDriver(run, result));
        }, "opairdrop-driver").start();
    }

    /**
     * 卸载：跑 {@code awdl-stack.sh stop}，卸掉打过补丁的驱动、恢复原厂驱动和网络环境。
     *
     * <p>和安装走同一条 su 通道（{@link DriverSetup#run}），只是参数是 stop；它是「有时候我也要用
     * 回原厂」的那条退路，装/卸期间两个按钮都置灰，避免两个改内核的动作叠在一起。
     */
    private void startUninstall() {
        if (uninstallBusy || driverBusy || DriverSetup.running() || uninstallButton == null) {
            return;
        }
        uninstallBusy = true;
        uninstallButton.setEnabled(false);
        uninstallButton.setAlpha(0.5f);
        uninstallButton.setText("正在卸…");
        driverButton.setEnabled(false);
        driverButton.setAlpha(0.5f);
        if (driverPill != null) {
            chrome.setPill(driverPill, "正在卸", S.accent);
        }
        driverNote.setTextColor(S.textDim);
        driverNote.setText("正在卸载驱动、恢复原厂驱动与网络环境…");

        new Thread(() -> {
            DriverSetup.Result result = DriverSetup.run("stop", host);
            host.runOnUiThread(() -> showUninstall(result));
        }, "opairdrop-uninstall").start();
    }

    /**
     * 「人按过卸载且成功」的标记。stop 不清 stack-state 回执，只能靠这个把徽章从「已就绪」改回
     * 「未安装」；写进 SharedPreferences，重启应用也不会把卸载后的状态记回「已就绪」。
     */
    private static final String PREFS_DRIVER = "driver_state";
    private static final String KEY_UNINSTALLED = "uninstalled";

    private boolean uninstalled() {
        return host.getSharedPreferences(PREFS_DRIVER, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_UNINSTALLED, false);
    }

    private void setUninstalled(boolean value) {
        host.getSharedPreferences(PREFS_DRIVER, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_UNINSTALLED, value).apply();
    }

    /** 卸载收尾：不论成败都整页重画——盘面（原厂驱动）已经变了，就地改四个控件改不完。 */
    private void showUninstall(DriverSetup.Result result) {
        uninstallBusy = false;
        for (String line : result.lines()) {
            Log.i(TAG, "uninstall| " + line);
        }
        Log.i(TAG, "uninstall| code=" + result.code()
                + (result.problem().isEmpty() ? "" : " problem=" + result.problem()));
        boolean ok = result.problem().isEmpty() && result.code() == 0;
        if (ok) {
            // 置位后重画，徽章才会从旧回执的「已就绪」改成「未安装」。
            setUninstalled(true);
            chrome.say("已卸载驱动，恢复原厂驱动与网络环境");
        } else {
            chrome.say("卸载未成功：" + result.subtitle());
        }
        // 整页重画，让驱动徽章、note、按钮、回执都回到「原厂」的真实状态。
        refreshSelf();
    }

    /** 让 MainActivity 重画这一页（它才有 pages[] 的句柄）。 */
    private void refreshSelf() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /**
     * 跳秒。
     *
     * <p>半分钟没有数字在动，就是一个看起来卡死的界面。数字也只到这一步为止：脚本的输出要等
     * 它整条跑完才有（{@code ProcessBuilder} 那一头是块读的），编一个假的进度条比没有更糟。
     *
     * <p>三重退出：这一轮被新的一轮接管、页面已经拆掉、或者收尾那一次已经把按钮改回可按。
     * 第二重和 {@code followAnswer} 里那句同一个理由——切走之后这个循环只在对一堆没人看的
     * TextView 赋值。
     */
    private void followDriver(int run, int seconds) {
        if (run != driverRun || driverButton == null || !driverButton.isAttachedToWindow()) {
            return;
        }
        if (!driverBusy) {
            return;
        }
        driverButton.setText("正在装… " + seconds + " 秒");
        driverButton.postDelayed(() -> followDriver(run, seconds + 1), 1000);
    }

    /**
     * 收尾：把回执写成这一页的样子。
     *
     * <p>成功时整页重画：这一轮动过的东西比这张卡多得多——守护进程起来了、{@code radio-state}
     * 换了、链路是新的。其余几张卡说的都是「此刻」，就地改四个控件改不完它们；而盘面上已经是
     * 新的真相了，重画一遍比重画一半便宜。
     *
     * <p>失败时就地改，不重画：失败没有改变盘面上任何别的东西，重画只会把人的滚动位置丢掉，
     * 让他重新找一遍刚才按的那颗按钮——失败之后要接着按的正是它。
     */
    private void showDriver(int run, DriverSetup.Result result) {
        if (run != driverRun) {
            // 这一轮已经过期：更新的一轮接管了这张卡，它的回执才该显示。
            return;
        }
        driverBusy = false;
        StackState.State state = result.state();
        for (String line : result.lines()) {
            Log.i(TAG, "driver| " + line);
        }
        if (!result.problem().isEmpty()) {
            // The one thing worth having in the log when the screen says "没有 root": the run
            // never reached the script, so there are no lines above to explain it.
            Log.i(TAG, "driver| problem| " + result.problem());
        }
        if (driverButton != null) {
            driverButton.setEnabled(true);
            driverButton.setAlpha(1f);
            driverButton.setText(buttonLabel(state));
        }
        if (driverPill != null) {
            chrome.setPill(driverPill, standingLabel(state), standingColor(state));
        }
        if (driverNote != null) {
            driverNote.setText(result.subtitle());
            driverNote.setTextColor(
                    result.problem().isEmpty() ? noteColor(state) : S.red);
        }
        if (driverReceipt != null) {
            driverReceipt.setText(receiptText(state));
            driverReceipt.setTextColor(receiptColor(state));
            driverReceipt.setVisibility(state.present() ? View.VISIBLE : View.GONE);
        }
        if (!state.up()) {
            chrome.say(result.problem().isEmpty() ? "没装成，原因在卡片上" : result.problem());
            return;
        }
        // 装成功，清掉「已卸载」标记：下一次重画才会按新回执显示「已就绪」。
        setUninstalled(false);
        chrome.say("AWDL 已就绪");
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /** 徽章上那几个字：只区分回执的几种样子，不重复下面那句话。 */
    private String standingLabel(StackState.State state) {
        if (!state.present()) {
            return "未装";
        }
        if (state.stale()) {
            // 回执在，但它说的是上一次开机。用「本次未装」而不是「已就绪」：冷启动之后
            // wonder0 根本不存在，这时候说就绪是这一页能犯的最坏的错——人正拿着手机看
            // 为什么对面看不见自己。
            return "本次未装";
        }
        switch (state.standing()) {
            case UP:
                return "已就绪";
            case PARTIAL:
                return "一半";
            case FAILED:
                return "失败";
            default:
                return "看不懂";
        }
    }

    private int standingColor(StackState.State state) {
        if (!state.present() || state.stale()) {
            // 灰，和「未装」同一个颜色：这两种情况下该做的事是同一件——按下去。
            return S.gray;
        }
        switch (state.standing()) {
            case UP:
                return S.green;
            case PARTIAL:
                // 一半不是坏：链路在，守护进程没起。用橙色是因为它既不该被读成成功，也还没到
                // 「重装驱动」那一步——该做的是看回执里缺的那一样。
                return S.orange;
            case FAILED:
                return S.red;
            default:
                return S.gray;
        }
    }

    /**
     * 安装按钮上那几个字：一律「安装」。
     *
     * <p>不按回执分「装驱动并建链 / 重新装一次 / 再试一次」：对人来说按下去都是同一件事——装。
     * 回执的细节在卡片上那句话和回执原文里，不该再挤到按钮上。boot 认本轮标记，装过之后再按
     * 走短路径，几秒就回，所以「安装」既是第一次也是重试时该说的话。
     */
    private String buttonLabel(StackState.State state) {
        return "安装";
    }

    /**
     * 卡片上那句话的颜色。
     *
     * <p>旧回执里的失败不该标红：那一轮已经过去了，红字会把人指向一个不存在的问题。红色只
     * 留给本次开机真的失败的那一次。
     */
    private int noteColor(StackState.State state) {
        return state.failed() && !state.stale() ? S.red : S.textDim;
    }

    private int receiptColor(StackState.State state) {
        return noteColor(state);
    }

    /**
     * 回执原文，加上「这条不是本次开机的」这层说明。
     *
     * <p>行本身一个字不改：日志里、{@link StackState} 里都是原样，这里只在前面垫五个字。但
     * 不垫不行——原封不动摆上去，人读到的是「daemon=28854 在跑」，而冷启动之后那个 pid 连
     * 进程号都可能已经被别人用了。
     */
    private String receiptText(StackState.State state) {
        if (!state.present()) {
            return "";
        }
        return state.stale() ? "上次开机：" + state.line() : state.line();
    }

    /**
     * 射频：一颗开关，加一行守护进程自己写回来的话。
     *
     * <p># 为什么这一颗不在设置表里
     *
     * <p>{@code settings.conf} 是这一页其他开关的家，但这一颗不是设置，是对另一个进程的请求：
     * 守护进程只看 {@code files/radio-share}，{@code awdl-stack.sh} 也只看那一个文件。把它再
     * 存进设置文件，同一个事实就有两个家，而无线电只认其中一个——于是这一页可以显示「共享中」
     * 而守护进程正在独占，或者反过来。所以这里读写的就是那两个文件，见 {@link RadioShare}。
     *
     * <p># 为什么失败不把开关拨回去
     *
     * <p>拨回去等于悄悄把人的偏好改成相反的，下一次 {@code start} 会照那个相反的来做。失败是
     * 射频没做到，不是人没要求，所以开关留在原处，回执那一行变红并说出原因。要重试就再拨一次。
     */
    private View radioCard() {
        RadioShare.State state = RadioShare.read(filesDir());
        LinearLayout card = chrome.rowCard();

        Toggle toggle = toggle(state.want() == RadioShare.Want.SHARE);
        TextView[] sub = new TextView[1];
        LinearLayout row = chrome.featureRow(
                IconView.NO_ICON, "WiFi 共享射频", state.subtitle(), toggle, sub);
        // 说明是一句完整的话，两行放不下：定高会把它裁在卡片下沿上。
        chrome.addWrappingRow(card, row);
        // 这里不挂 pressableRow。理由和「有开关的行不按」是同一条：这一行没有点击动作，唯一
        // 能按的东西是右边那颗开关本身。整行泛起的涟漪会把「按这里能拨」指到空白处，而空白处
        // 按下去什么也不会发生——一处按下去没反应的高亮，比没有高亮更让人以为界面卡了。

        // 回执原文，一行、灰的，失败时红的。人话在上面那句，这一行是不能被人话改写的原始记录：
        // 落在哪个信道、失败的理由原文，都在这里。
        TextView record = chrome.caption(state.receipt().present() ? state.receipt().line() : "");
        record.setTextColor(state.failed() ? S.red : S.textDim);
        // 左缘 18dp，和上面「WiFi 共享射频」那行文字落在同一条竖线上。原来给的是 74
        // （= 18 行内边距 + 44 图标砖 + 12 间隙，那是**有图标砖**时文字的左缘），可这一行没有
        // 砖：74 让它单独往右缩进一大截，看起来像卡片里又套了一块东西，而不是紧跟着上面那句
        // 话的补充说明。
        record.setPadding(chrome.dp(18), 0, chrome.dp(18), chrome.dp(12));
        record.setVisibility(state.receipt().present() ? View.VISIBLE : View.GONE);
        card.addView(record);

        toggle.setOnChange((button, checked) -> {
            RadioShare.Want want =
                    checked ? RadioShare.Want.SHARE : RadioShare.Want.EXCLUSIVE;
            try {
                RadioShare.ask(filesDir(), want);
            } catch (IOException failed) {
                // 请求没写进去：开关必须自己弹回去，否则它是一句关于射频的假话，而这个假话的
                // 原因是磁盘，不是射频。
                button.setChecked(!checked);
                sub[0].setText("写不进请求文件：" + failed.getMessage());
                sub[0].setTextColor(S.red);
                return;
            }
            followAnswer(sub[0], record, 0);
        });
        return card;
    }

    /**
     * 等守护进程回话：每 250ms 读一次那两个文件，直到回执对盘上这一次请求说出结论。
     *
     * <p>「说出结论」是 {@link RadioShare.State#settled()}，不是 {@code answered()}。
     * {@code share pending} 也是一个答案——它是守护进程说「我还在弄」，页面该原样印出来——但
     * 把它当成谈话结束就再也不看了。设备上真发生过：拨回共享，守护进程一个轮询之内就写了
     * {@code share pending}，这一页信了、停了，四十秒后还停在「正在开 Wi-Fi」，而盘上是
     * {@code share confirmed sta=5745}、Wi-Fi 也早就开了。
     *
     * <p>没有超时就报错这一说——超时之后停在哪个状态，由 {@link RadioShare.State#subtitle()}
     * 说出来（「已请求共享，还没有回执——守护进程可能没在运行」），而不是这里再编一句。二十秒
     * 是守护进程自己的上限（{@code ON_LIMIT_US}），再宽一点只是给它收尾的余地。
     *
     * <p>先问 {@code isAttachedToWindow}：这一页切走会整页重画，那时这个循环还挂在旧视图上，
     * 继续拨下去只是在给一堆没人看的 TextView 赋值。
     */
    private void followAnswer(TextView sub, TextView record, int tick) {
        if (!sub.isAttachedToWindow()) {
            return;
        }
        RadioShare.State state = RadioShare.read(filesDir());
        sub.setText(state.subtitle());
        sub.setTextColor(state.failed() ? S.red : S.textDim);
        // 每次都照盘面重写这一行，包括「没有回执」那种情况。写成「有就改、没有就不动」会让
        // 上一轮回执留在屏幕上，而上面那句已经改成「还没有回执」——两句里有一句是假的。
        record.setText(state.receipt().present() ? state.receipt().line() : "");
        record.setTextColor(state.failed() ? S.red : S.textDim);
        record.setVisibility(state.receipt().present() ? View.VISIBLE : View.GONE);
        if (state.settled() || tick >= ANSWER_TICKS) {
            return;
        }
        sub.postDelayed(() -> followAnswer(sub, record, tick + 1), ANSWER_TICK_MS);
    }

    /**
     * 保存位置一行：显示当前生效的目录，点一下弹输入框改。
     *
     * <p>值取自设置表 receive.directory；空的意思是按文件类型分目录保存，这一句写在副标题里，
     * 免得空值被读成「没设置好」。写盘走 SettingsScreen 的校验 + save，和设置页同一套，错误的
     * 路径会被库挡下来并说清楚原因。
     */
    /** 设备显示那一行的副标题引用，保存后就地刷新。 */
    private TextView nameDisplayValue;

    /**
     * 设备显示：对端在列表里看到的名字。
     *
     * <p>名字空着的时候对端读到的是机型（{@code Listener.peerName} 的兜底链，和 Rust 那边
     * {@code settings::name} 是同一条），副标题要把「此刻真正生效的那个值」写出来，否则空行
     * 会被读成「对端什么都看不到」。
     *
     * <p># 这里原本还有第二行，改「对端看到的机型」，已经撤掉
     *
     * <p>撤的理由不是嫌多，是<b>它下面没有东西可改</b>：机型这一项的全部作用就是喂
     * {@code /Ask} 的应答和名字的兜底，而这两处读的是同一张设置表；改了它，对端看到的
     * 列表条目里的名字会跟着变——一个「机型」输入框悄悄改掉了「名字」，这比没有这个框更
     * 容易让人困惑。真正要改名字的人用上面那一行就够，剩下的人要的是系统报的那个型号。
     * {@code device.model} 这个键留在设置表里（Rust 侧、协议应答都还读它），只是这一页不再
     * 提供修改入口。
     */
    private View deviceCard() {
        LinearLayout card = chrome.rowCard();

        TextView[] nameSub = new TextView[1];
        LinearLayout nameRow = chrome.featureRow(
                IconView.NO_ICON, "对端看到的名字", nameLine(),
                chrome.text("›", 20, true, S.textDim), nameSub);
        nameDisplayValue = nameSub[0];
        chrome.addRow(card, nameRow, chrome.rowHeight());
        // 卡片里只剩这一行，四个角都给满：CORNERS_TOP/BOTTOM 是给「上下两行拼一张卡」用的，
        // 单行用它会得到一对平角压在卡片圆角上。
        chrome.setRowAction(nameRow, true, Scheme.CORNERS_ALL,
                () -> askSetting("device.name", "对端看到的名字",
                        "留空则用设备型号", () -> {
                            if (nameDisplayValue != null) {
                                nameDisplayValue.setText(nameLine());
                            }
                        }));
        return card;
    }

    /** 对端此刻会读到的名字；名字留空时注明它其实是机型。 */
    private String nameLine() {
        String shown = listener.peerName();
        if (!storedText("device.name").isEmpty()) {
            return shown;
        }
        return shown + "（名字留空，用的是设备型号）";
    }

    /** 一行存下来的原文（去空白）；表读不出来或没这一行就是空串。 */
    private String storedText(String key) {
        SettingsScreen fresh = settingsNow();
        SettingsScreen.Setting setting = fresh == null ? null : fresh.setting(key);
        return setting == null ? "" : setting.value().trim();
    }

    private TextView directoryValue;

    private View directoryCard() {
        LinearLayout card = chrome.rowCard();
        TextView[] sub = new TextView[1];
        LinearLayout row = chrome.featureRow(
                IconView.NO_ICON, "接收文件保存位置", directoryLine(),
                chrome.text("›", 20, true, S.textDim), sub);
        directoryValue = sub[0];
        chrome.addRow(card, row, chrome.rowHeight());
        chrome.setRowAction(row, true, Scheme.CORNERS_ALL, this::askDirectory);
        return card;
    }

    /** 目录那行的副标题：填了就显示路径，空就说明默认行为。 */
    private String directoryLine() {
        // 现读盘，不依赖构造时那份 screen：保存之后要立刻显示新值，而 screen 是 final 的旧快照。
        SettingsScreen fresh = settingsNow();
        SettingsScreen.Setting setting = fresh == null ? null : fresh.setting("receive.directory");
        String value = setting == null ? "" : setting.value().trim();
        return value.isEmpty() ? "留空：按文件类型分目录保存" : value;
    }

    /** 弹一个输入框改保存位置；框里是存下来的原文，不是加了说明的副标题。 */
    private void askDirectory() {
        askSetting("receive.directory", "接收文件保存位置", "留空则按文件类型分目录保存",
                () -> {
                    if (directoryValue != null) {
                        directoryValue.setText(directoryLine());
                    }
                });
    }

    /**
     * 弹一个输入框改一行文本设置。所有「点一下弹框改一个字符串」的行共用：目录、名字。
     *
     * <p># 为什么不再用 {@code AlertDialog}
     *
     * <p>因为它给不了这个形状。{@code AlertDialog} 的内容区长在系统主题的那张纸上：直角的
     * 窗口背景、系统给的标题字号、系统给的按钮排布。圆角只能靠给窗口塞一张圆角 drawable 假装
     * 出来，而那张纸本身还在——四个角会露出底下那层方角。霜面更没法做：模糊要一个能跟着卡片
     * 走的 {@link RealtimeBlurView}，也就是一个我们能往里放子视图的容器。
     *
     * <p>所以换成自绘的 {@link Dialog}：窗口背景设成全透明，卡片自己长成什么样就是什么样。
     * 卡片里面是一层霜面 + 一层内容，霜面与导航胶囊同一套参数（同一个类、同一个捕获根、
     * 同一档半径与色调），所以它读起来就是「胶囊那种玻璃」，而不是另一种玻璃。
     *
     * @param key      设置表的键
     * @param title    对话框标题
     * @param hint     输入框占位提示
     * @param onSaved  保存成功后回调，用来就地刷新那一行
     */
    private void askSetting(String key, String title, String hint, Runnable onSaved) {
        SettingsScreen fresh = settingsNow();
        SettingsScreen.Setting stored = fresh == null ? null : fresh.setting(key);
        EditText field = chrome.darkInput(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setText(stored == null ? "" : stored.value());
        field.setSelection(field.getText().length());

        Dialog dialog = new Dialog(host);
        // 系统标题栏要关掉：这张卡片的标题由我们自己排，用的是这一页统一的那种小标题，
        // 留着系统的会得到两行标题。
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 高度必须自己说一遍。{@code Dialog.setContentView(View)} 内部走的是
        // {@code new LayoutParams(MATCH_PARENT, MATCH_PARENT)}——弹窗的内容区默认被撑满，
        // 传 WRAP_CONTENT 进去，卡片才贴着内容。（还有一半原因在 sheet() 里：那层霜面也得
        // 定住高度，否则它会替卡片把整个可用高度占掉。）
        dialog.setContentView(sheet(title, field, key, onSaved, dialog),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        Window window = dialog.getWindow();
        if (window != null) {
            // 窗口自己不画任何东西——连默认的那张白纸也不要，否则卡片的圆角外面会多一圈方角。
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.45f);
            // 键盘顶起来的时候让窗口自己缩，卡片跟着往上走，而不是被键盘盖住下半截。
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            window.setLayout(sheetWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        dialog.setOnShowListener(shown -> {
            field.requestFocus();
            field.postDelayed(() -> {
                InputMethodManager keyboard = host.getSystemService(InputMethodManager.class);
                if (keyboard != null) {
                    keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 100);
        });
        dialog.show();
    }

    /**
     * 浮窗那张卡：霜面打底，内容压在上面。
     *
     * <p>霜面是**压在底下一层**而不是给卡片设背景：{@link RealtimeBlurView} 要自己 onDraw 才能
     * 把背后的内容糊出来，设成背景就只剩一张静态图。所以卡片本体是一块圆角实底（拿不到模糊时
     * 它就是卡片本身，读得清），上面盖一层同圆角的玻璃，再上面才是标题、输入框和按钮。
     */
    private View sheet(String title, EditText field, String key, Runnable onSaved, Dialog dialog) {
        int radius = chrome.dp(SHEET_RADIUS_DP);
        int width = sheetWidth();

        LinearLayout content = chrome.column();
        content.setPadding(chrome.dp(22), chrome.dp(20), chrome.dp(22), chrome.dp(18));
        content.addView(chrome.text(title, 17, true, S.text));
        // 输入框与两颗按钮的底色统一压到 75%（见 washControl）。圆角沿用 Chrome 里
        // darkInput / ghostButton / mainButton 各自那一档（14 / 16 / 16），不顺手统一成一个
        // 数：14 的输入框配 16 的按钮，两个角不一样圆，上下挨着就看得出来。
        washControl(field, S.cardHi, 14);
        content.addView(field, chrome.margins(14, 0));

        LinearLayout actions = chrome.row();
        TextView cancel = chrome.ghostButton("取消", view -> dialog.dismiss());
        TextView save = chrome.mainButton("保存", view -> {
            // 先关窗再写：写成功了要弹一条「已保存」，而那条提示挂在主界面的通知条上，
            // 浮窗还盖着的时候它会被挡在下面。
            dialog.dismiss();
            saveSetting(key, field.getText().toString(), onSaved);
        });
        washControl(cancel, S.cardHi, 16);
        washControl(save, S.accent, 16);
        LinearLayout.LayoutParams cancelLp = chrome.mm(0, chrome.dp(46), 1f);
        cancelLp.rightMargin = chrome.dp(10);
        actions.addView(cancel, cancelLp);
        actions.addView(save, chrome.mm(0, chrome.dp(46), 1f));
        content.addView(actions, chrome.margins(18, 0));

        // 先把内容量一遍，才知道玻璃该多高。理由见下面那段注释——这一步不是优化，是必需的。
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = content.getMeasuredHeight();

        FrameLayout card = new FrameLayout(host);
        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(radius);
        base.setColor(S.card);
        card.setBackground(base);

        RealtimeBlurView glass = new RealtimeBlurView(host);
        glass.setCaptureRoot(captureRoot());
        glass.setCornerRadius(radius);
        glass.setFill(S.card);
        glass.setLiquidGlass(true);
        glass.setBlurRadius(SHEET_BLUR_RADIUS);
        // 完全不透明地画那层糊过的东西。原来给的是 240（94%），剩下那 6% 足够让背后的字露出
        // 笔画，于是整张卡看着是「蒙了一层」而不是「一块材料」。
        glass.setOpacity(255);
        glass.setTint((S.navTint & 0x00FFFFFF) | 0x4D << 24);
        // 下面三样是这颗玻璃原来挂着的「立体感」，现在一律不挂：
        //
        //   setRim(...)        —— 沿卡片内缘的一圈渐变描边。它在窄条胶囊上是「轮廓」，在这么
        //                         大一张卡上就是一圈发白的框，四角尤其明显。
        //   setSheen(...)      —— 一条斜穿的白色高光带，12% 白。卡片一大，它就从「一道反光」
        //                         变成「一块斜着贴上去的亮片」。
        //   setGlassLift(...)  —— 整体提亮 8 级。提亮是为了让玻璃比页面「亮一档」，可这一页的
        //                         卡片本来就比页底亮，再提就是发灰。
        //
        // 三样都不设，也就是它们各自的默认值（无描边、无高光、不提亮）。

        // 玻璃的高度是算出来的，不是 MATCH_PARENT——这一条是这张卡片能不能贴合内容的关键。
        //
        // RealtimeBlurView 没有自己的 onMeasure，用的是 View 的默认实现：给 AT_MOST 的可用
        // 高度，它就把那个高度原样返回。它躺在 WRAP_CONTENT 的 FrameLayout 里、又要 MATCH_PARENT
        // 时，父容器递过去的正是 AT_MOST(整块可用高度)，于是这一层完全透明的玻璃把卡片撑成了
        // 满屏：圆角只剩最上面和最下面各一段，中间一大片空霜面，看起来像"卡片没画出来"。
        // 量出内容的真实高度再钉死，父容器量到的就是内容高度。
        final FrameLayout.LayoutParams glassLp =
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        card.addView(glass, glassLp);

        // 横竖屏、字体大小变化之后宽度会变，内容的高度跟着变，玻璃要跟上。高度变回去不会影响
        // 内容的高度（内容只吃宽度），所以这里不会来回抖。
        card.addOnLayoutChangeListener((view, left, top, right, bottom, ol, ot, or, ob) -> {
            int now = bottom - top;
            if (now > 0 && glassLp.height != now) {
                glassLp.height = now;
                glass.setLayoutParams(glassLp);
            }
        });

        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** 浮窗的宽度：屏宽的 86%。窗口和内容测量必须用同一个数，差一点量出来的高度就是错的。 */
    private int sheetWidth() {
        return (int) (host.getResources().getDisplayMetrics().widthPixels * 0.86f);
    }

    /** 霜面要糊的那一层。功能页只持有 Activity，读口在 {@link MainActivity#blurRoot}。 */
    private View captureRoot() {
        return host instanceof MainActivity ? ((MainActivity) host).blurRoot() : null;
    }

    /**
     * 把浮窗里一件控件的实底换成 75% 不透明的同色底，圆角不变。
     *
     * <p># 为什么重新画一张，而不是改原来那张
     *
     * <p>{@code GradientDrawable} 只进不出：{@code setColor} 之后没有 {@code getColor}，读不回
     * {@code darkInput} / {@code ghostButton} / {@code mainButton} 当时填的是哪个色。而这三个
     * 颜色都有出处（{@link Scheme#cardHi}、{@link Scheme#accent}），调用方本来就拿着——直接再
     * 画一张，比给 Chrome 加三个「带 alpha」的重载干净：那三个重载只有这里会用。
     *
     * <p># 为什么只改这三件
     *
     * <p>设置页、关于页里的同一个 {@code darkInput} 仍然是不透明实底。它们底下没有霜面，压到
     * 75% 只会把页面底色透出来，看起来像没画完。这一条限定在浮窗内。
     *
     * @param control  输入框或按钮
     * @param color    它原来的实底色（只看低 24 位）
     * @param radiusDp 圆角，必须和控件原来那一档一致（14 是输入框、16 是按钮）
     */
    private void washControl(View control, int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(chrome.dp(radiusDp));
        bg.setColor((color & 0x00FFFFFF) | SHEET_CONTROL_ALPHA << 24);
        control.setBackground(bg);
    }

    /** 写一行文本设置；成功回调刷新，失败把库给的原因说出来。 */
    private void saveSetting(String key, String value, Runnable onSaved) {
        SettingsScreen fresh = settingsNow();
        if (fresh == null) {
            chrome.say("设置读不出来，没保存");
            return;
        }
        fresh.set(key, value);
        List<String> problems;
        try {
            problems = fresh.save();
        } catch (IOException failed) {
            chrome.say("没保存成功：" + failed.getMessage());
            return;
        }
        if (!problems.isEmpty()) {
            chrome.say(problems.get(0));
            return;
        }
        onSaved.run();
        chrome.say("已保存");
    }

    /** 现开一份设置表：这一页的 screen 可能已经旧了，拿旧快照保存会覆盖别处的改动。 */
    private SettingsScreen settingsNow() {
        try {
            return SettingsScreen.open(SettingsFile.of(host).toPath());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private java.io.File filesDir() {
        return host.getFilesDir();
    }

    /**
     * 一颗跟着配色的拨杆。
     *
     * <p>不用平台的 {@code Switch}：这个 app 没有 Material 库，拿到的还是 Android 5 那套九宫格
     * drawable，圆点本来就比轨道大一圈。自己画的那颗见 {@link Toggle}。
     */
    private Toggle toggle(boolean on) {
        return new Toggle(host, S, on);
    }
}
