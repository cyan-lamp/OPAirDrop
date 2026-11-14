package com.oshare.airdrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 功能页：会影响行为的那几个开关，加上此刻的状态。
 *
 * <p># 为什么开关单独一页
 *
 * <p>开关和数字读起来完全不像同一类东西。数字是「值」，改完按保存，改坏了有范围可查；开关是
 * 「行为」，拨一下立刻生效，没有保存这一步可等。放在同一页里，人拨完开关会去找保存按钮，而
 * 在数字页按了保存之后会以为开关也一起存了。分开之后，两页各自只有一种操作。
 *
 * <p># 为什么这里的开关一拨就写
 *
 * <p>保存按钮在另一页，所以这里没有可等的时机。拨动即写入、写入即生效，失败就把开关拨回去并
 * 在它下面写清楚原因——一个拨过去却没生效的开关，是这一页唯一会骗人的样子。
 *
 * <p># 为什么接收端那个开关有时是灰的
 *
 * <p>跟随一加互传开着的时候，接收端的主人就不是这一页了，而是互传的接收范围。此时让这个开关
 * 还能拨，等于让界面能显示「在接收」而对外一个应答都发不出去。
 *
 * <p># 驱动为什么在最前面
 *
 * <p>它一天只按一次，按完之后整天都不再碰——按「常用程度」排，它应该在最下面。但它换的是
 * 手机的内核驱动：下面每一张卡说的都是「此刻的链路」，而冷启动之后 {@code wonder0} 根本还
 * 不存在，那些卡在说的是一个没有的东西。把前提放在末尾，等于让最要紧的那件事要滚过三张
 * 「此刻没有链路」的卡才找得到。所以它排第一，而它下面那句话也只说一件事：这条链路现在到
 * 哪一步了。
 *
 * <p># 版式从哪来
 *
 * <p>legacy 那一版这一页叫「服务状态」：一张 66dp 行的状态卡、一块传输状态。这里的骨架照搬，
 * 只是把「AWDL / QuickShare / OShare 三个服务」换成了这一版真正有的两样东西（接收端、一加
 * 互传），并把手上的开关接在它们下面。
 *
 * <p># 原来那颗「刷新状态」按钮为什么没了
 *
 * <p>它调的是外壳的整页重建：把首页和这一页都丢掉重画一遍。但这一页拨完任何一个开关都已经
 * 就地刷过对应的那行字（{@code syncReceive()}、{@code write()} 之后重读），首页进
 * {@code onResume} 时也会自己重建——那颗按钮按下去能看到的变化，本来就已经在屏幕上了。
 * 留着它只是教人以为「状态会过期，得手动打一针」。
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

    private final Activity host;
    private final Chrome chrome;
    private final Scheme S;
    private final Listener listener;
    /** 这页读到的那份表；每次切到本页都重新打开，别的页改过的值才会被看见。 */
    private final SettingsScreen screen;
    /** 整页重画的入口；只有一件事之后用得着它，见 {@link #showDriver}。 */
    private final Runnable onChanged;

    /**
     * 接收端那一行：拨杆和它下面那句话。
     *
     * <p>「跟随」这一项在下面那张卡里也能改，改完这一行必须立刻跟着变——跟随开着时接收端的
     * 拨杆是灰的（它归一加互传管），关掉跟随它才该能按。留着这两个引用，是为了改完就地刷新这
     * 一行，而不是整页重建：重建会把滚动位置、正在播的拨杆动画一起丢掉。
     */
    private Toggle receiveToggle;
    private TextView receiveState;

    /**
     * 两行自报名字右侧那个值。
     *
     * <p>改完就地换，不重建这一页：重建会把滚动位置和正在播的动画一起丢掉，而这两行在接收
     * 那一块的最下面，重建之后人还得再滚回来。
     */
    private TextView nameValue;
    private TextView modelValue;

    /**
     * 建这一页时另开的那份表，只用来回答「这两行存了什么」。
     *
     * <p>不是 {@link #screen}：那份是随这一页一起读的，人在设置页改完名字再滑回来，它就已经
     * 旧了。这两行说的是「对端此刻会读到什么」，旧值比不显示更糟。
     */
    private SettingsScreen reported;

    /**
     * 驱动卡上的四个零件：徽章、那句话、按钮、回执原文。
     *
     * <p>装一次要半分钟，这半分钟里这四样都得变（按钮置灰、跳秒、最后换成回执），而这一页
     * 不重建——所以留着引用就地改。
     */
    private TextView driverPill;
    private TextView driverNote;
    private TextView driverButton;
    private TextView driverReceipt;

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

        body.addView(chrome.sectionTitle("接收"), chrome.margins(6, 0));
        body.addView(receiveCard(), chrome.margins(0, 12));
        body.addView(identityCard(), chrome.margins(0, 8));

        // 射频排在接收后面、表里那些开关前面：它是这一页唯一一个「拨错了手机就上不了网」的
        // 开关，而且它有一个别人写回来的回执要显示，和表里那几项拨完只写文件的不一样。
        body.addView(chrome.sectionTitle("射频"), chrome.margins(6, 0));
        TextView radioNote = chrome.caption(
                "共用一根天线：共用时会丢一部分包，丢多少取决于 Wi-Fi 落在哪个信道，回执里的 MHz 就是落点。");
        radioNote.setPadding(chrome.dp(6), 0, chrome.dp(6), chrome.dp(8));
        body.addView(radioNote);
        body.addView(radioCard(), chrome.margins(0, 8));

        // 按钮占掉的那一段还回去：卡的下留白 12 + 这里 6 = 18，和首页「卡→下一段标题」的
        // 16 是同一个节奏。
        body.addView(chrome.sectionTitle("开关"), chrome.margins(6, 0));
        body.addView(switchCard(), chrome.margins(0, 16));

        body.addView(chrome.sectionTitle("传输状态"));
        body.addView(statusCard(), chrome.margins(0, 16));

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
        LinearLayout card = chrome.card();

        LinearLayout head = chrome.row();
        head.addView(chrome.text("内核驱动", 16, true, S.text),
                chrome.mm(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        driverPill = chrome.statusPill(
                driverBusy ? "正在装" : standingLabel(state),
                driverBusy ? S.accent : standingColor(state));
        head.addView(driverPill);
        card.addView(head);

        driverNote = chrome.text(driverBusy ? RUNNING_LINE : state.subtitle(), 13.5f, false,
                driverBusy ? S.textDim : noteColor(state));
        driverNote.setPadding(0, chrome.dp(8), 0, chrome.dp(12));
        card.addView(driverNote);

        driverButton = chrome.mainButton(driverBusy ? "正在装…" : buttonLabel(state),
                view -> startDriver());
        driverButton.setEnabled(!driverBusy);
        driverButton.setAlpha(driverBusy ? 0.5f : 1f);
        card.addView(driverButton, chrome.mm(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0f));

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
            chrome.say(result.problem().isEmpty() ? "没装成，原因在卡片上" : result.problem(), S.red);
            return;
        }
        chrome.say("AWDL 已就绪", S.green);
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
     * 按钮上那几个字，按回执说。
     *
     * <p>「重新装一次」在成功之后仍然给：驱动是每重启一次就要再跑一遍的东西，而这个按钮是
     * 唯一一条能把已经起来的栈重跑一遍的路（{@code boot} 认本轮标记，第二次会走短路径）。
     *
     * <p>回执来自上一次开机时，按钮退回「装驱动并建链」，和从没装过一样：这一次开机确实还
     * 没跑过，而这几个字正是该做的事。成功之后按它走的是短路径，五秒，不会又换一遍驱动。
     */
    private String buttonLabel(StackState.State state) {
        if (!state.present() || state.stale()) {
            return "装驱动并建链";
        }
        return state.up() ? "重新装一次" : "再试一次";
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
                IconView.CHIP, "WiFi 共享射频", state.subtitle(), toggle, sub);
        // 说明是一句完整的话，两行放不下：定高会把它裁在卡片下沿上。见 switchCard() 里同一句。
        chrome.addWrappingRow(card, row);
        chrome.pressableRow(row, Scheme.CORNERS_ALL);

        // 回执原文，一行、灰的，失败时红的。人话在上面那句，这一行是不能被人话改写的原始记录：
        // 落在哪个信道、失败的理由原文，都在这里。
        TextView record = chrome.caption(state.receipt().present() ? state.receipt().line() : "");
        record.setTextColor(state.failed() ? S.red : S.textDim);
        record.setPadding(chrome.dp(74), 0, chrome.dp(18), chrome.dp(12));
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

    private java.io.File filesDir() {
        return host.getFilesDir();
    }

    /**
     * 两行状态：接收端本身和一加互传的接收范围。
     *
     * <p>接收端不是设置表里的一项，而是这个进程里那个 {@link Listener}——它不写文件，它启停
     * socket。跟随开着时它显示此刻真实的状态并置灰：「界面说在接收，对端找不到我」是这一行
     * 唯一可能出的错，置灰就是为了不让人亲手制造它。
     */
    private View receiveCard() {
        LinearLayout card = chrome.rowCard();
        boolean following = Oshare.following(host);

        boolean running = listener.running();
        TextView[] sub = new TextView[1];
        Toggle receiver = toggle(running);
        receiveToggle = receiver;
        receiver.setEnabled(!following);
        LinearLayout listenRow = chrome.featureRow(
                IconView.RADAR, "接收端", listenLine(following), receiver, sub);
        chrome.addRow(card, listenRow, chrome.rowHeight());
        receiver.setOnChange((button, checked) -> {
            // 先记下这次是「人拨的」，再动线程：服务那边靠这个凭据区分「人开的」和
            // 「窗口开的」，否则窗口一过期，接收端会以「我还在收」为由活下去。
            ReceiveService.setManual(host, checked);
            if (checked) {
                listener.start();
            } else {
                listener.stop();
            }
            // 手动开的接收端也要有前台身份，否则离开这一页九十秒后系统就把进程收走了。
            // 服务自己的判断会看「已经在收」这一条，所以这里不需要另传一个理由。
            ReceiveService.apply(host);
            // 接收端从自己的线程报告结果，所以这里等一拍再按它说的把开关摆正：绑定失败时
            // 开关必须自己弹回去，否则它就成了一句假话。
            button.postDelayed(() -> button.setChecked(listener.running()), 120);
        });
        // 状态那句话随接收端变，但这一页不重建，所以拨完立刻改一次
        receiveState = sub[0];
        if (receiveState != null) {
            receiveState.setText(listenLine(following));
        }

        card.addView(chrome.divider(), chrome.insetDivider());

        int share = Oshare.stateOf(host);
        TextView sharePill = chrome.statusPill(
                share == 1 ? "所有人" : share == 2 ? "仅联系人" : share == 0 ? "已关闭" : "读不到",
                share == 1 ? S.green : share < 0 ? S.red : S.gray);
        LinearLayout shareRow = chrome.featureRow(
                IconView.PHONE, "一加互传", Oshare.label(host), sharePill);
        chrome.addRow(card, shareRow, chrome.rowHeight());
        chrome.setRowAction(shareRow, false, null, null);
        return card;
    }

    /**
     * 接收端那行下面那句：先说此刻在干什么，再说这个开关归谁管。
     *
     * <p>两个信息合成一句，是因为这一行只有一个位置——右侧已经给了拨杆。跟随开着时拨杆是灰
     * 的，那句话必须解释它为什么是灰的；跟随关着时那句话只剩状态。
     */
    private String listenLine(boolean following) {
        String state = listener.state();
        // 「在收听但没人找得到我」是这一行唯一说不出口的错，所以广播那边的抱怨必须拼在这里：
        // 端口在监听、分享面板里却空着，人只会去查 iPhone 或者查网络，而真正的原因是
        // AWDL 链路没建起来。只在有话说时才拼，正常时不占位置。
        String aside = listener.aside();
        if (!aside.isEmpty()) {
            state = state + " · " + aside;
        }
        // 窗口开着的时候，「跟随一加互传」这句话是错的：那十分钟里接收端恰恰不听互传的。
        // 说出真正的原因和还剩多久，人才知道要不要再按一次面板。
        return following ? state + " · 跟随一加互传" : state;
    }

    /**
     * 按「跟随」此刻的意思把接收端那一行重画一遍。
     *
     * <p>只重画这一行：整页重建会把滚动位置和正在播的动画一起丢掉，而改的只是这两个属性。
     */
    private void syncReceive() {
        if (receiveToggle == null) {
            return;
        }
        boolean following = Oshare.following(host);
        receiveToggle.setEnabled(!following);
        if (receiveState != null) {
            receiveState.setText(listenLine(following));
        }
    }

    /**
     * 两行自报的名字：对端在列表里看到的、以及在探测应答里读到的。
     *
     * <p># 为什么这两行不跟「开关」那片待在一起
     *
     * <p>那一片是「改完立刻生效、没有回执可看」的东西。这两行改完，应答确实立刻换了，但证书
     * 里的 CN 和 mDNS 里的 SRV target 是建 socket 那一刻定下的，要重启接收端才跟着换——混在
     * 开关里，人改完看不到变化，只会以为没生效。
     *
     * <p># 为什么显示生效值，而不是存下来的那个串
     *
     * <p>空着也是一项设置：空的意思是「跟着下一级走」。把空印成一个空行，人读到的是「对端
     * 什么都看不到」，而实际对端读到的是系统型号。括号里那几个字是给这种时候用的——名字空着
     * 而机型写着，两行会显示同一个串，不说明就像排错了版。
     */
    private View identityCard() {
        LinearLayout card = chrome.rowCard();
        reported = settingsNow();

        TextView[] nameSub = new TextView[1];
        LinearLayout nameRow = chrome.featureRow(
                IconView.PHONE, "对端看到的名字", nameLine(), chevron(), nameSub);
        nameValue = nameSub[0];
        chrome.addWrappingRow(card, nameRow);
        chrome.setRowAction(nameRow, true, Scheme.CORNERS_TOP,
                () -> ask("device.name", "对端看到的名字",
                        "留空则跟着机型：" + listener.peerModel()));

        card.addView(chrome.divider(), chrome.insetDivider());

        TextView[] modelSub = new TextView[1];
        LinearLayout modelRow = chrome.featureRow(
                IconView.ANDROID, "对端看到的机型", modelLine(), chevron(), modelSub);
        modelValue = modelSub[0];
        chrome.addWrappingRow(card, modelRow);
        chrome.setRowAction(modelRow, true, Scheme.CORNERS_BOTTOM,
                () -> ask("device.model", "对端看到的机型",
                        "留空则用系统型号：" + Listener.systemModel()));
        return card;
    }

    /** 行尾那个「›」：这一行点进去有事可做。 */
    private TextView chevron() {
        return chrome.text("›", 20, true, S.textDim);
    }

    /** 名字此刻对端会读到的那个串，加一句它是自己来的还是跟上来的。 */
    private String nameLine() {
        String shown = listener.peerName();
        if (!storedText("device.name").isEmpty()) {
            return shown;
        }
        return shown + (storedText("device.model").isEmpty() ? "（系统型号）" : "（跟随机型）");
    }

    /** 机型此刻对端会读到的那个串。 */
    private String modelLine() {
        String shown = listener.peerModel();
        return storedText("device.model").isEmpty() ? shown + "（系统型号）" : shown;
    }

    /** 一行存下来的原文（去空白）；表读不出来或者没有这一行，就是空串。 */
    private String storedText(String key) {
        SettingsScreen.Setting setting = reported == null ? null : reported.setting(key);
        return setting == null ? "" : setting.value().trim();
    }

    /**
     * 此刻盘面上的表，现开一份。
     *
     * <p>不用 {@link #screen}：那份跟着这一页一起读，人在设置页改完再滑回来它就已经旧了。写也
     * 一样现开一份——拿旧快照保存，会把人在设置页改过的别的行一起写回旧值。
     */
    private SettingsScreen settingsNow() {
        try {
            return SettingsScreen.open(SettingsFile.of(host).toPath());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * 弹一个输入框改一行。
     *
     * <p>框里填的是存下来的原文，不是上面印着的生效值：生效值可能是从下一级跟上来的，把它填
     * 进框里再保存，等于把「跟随」改成「写死」——而人只是想改一个字。
     */
    private void ask(String key, String title, String hint) {
        EditText field = chrome.darkInput(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        SettingsScreen.Setting stored = reported == null ? null : reported.setting(key);
        field.setText(stored == null ? "" : stored.value());
        field.setSelection(field.getText().length());

        // 输入框自己带着底色和圆角，对话框不给边距，所以垫一层；宽高交给这一层，输入框满宽。
        FrameLayout frame = new FrameLayout(host);
        frame.setPadding(chrome.dp(24), chrome.dp(8), chrome.dp(24), 0);
        frame.addView(field, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(title)
                .setView(frame)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存",
                        (dialog1, which) -> written(key, field.getText().toString()))
                .create();

        // 键盘要自己请，且得在窗口真出现之后请。实测（PJZ110 / Android 15）：只在 show() 之前
        // 设 SOFT_INPUT_STATE_ALWAYS_VISIBLE，键盘不弹，框上连光标都没有——对话框的初焦点没给
        // 这个输入框，输入法按「没有可写字的框」算了一遍。onShow 里先抢焦点，再等一帧让输入法
        // 接上这个新窗口，然后直接点名要它显示。
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
     * 写一行，然后把这两行按盘面重算。
     *
     * <p>两行一起重算，因为它们是同一条链：名字空着的时候名字就等于机型，改机型也就改了名字。
     *
     * <p>拒绝时不重开对话框：这里唯一会拒绝的是太长或带制表符这类输错，库给出的那句话（形如
     * {@code device.name: 最多 200 个字符}）说得很清楚，而盘上一行都没动——上面显示的那一行
     * 仍然是真话。把刚敲的字再摆一遍，只会让人以为第一次没按到。
     */
    private void written(String key, String text) {
        SettingsScreen fresh = settingsNow();
        if (fresh == null) {
            chrome.say("设置读不出来，没保存", S.red);
            return;
        }
        fresh.set(key, text);
        List<String> problems;
        try {
            problems = fresh.save();
        } catch (IOException failed) {
            chrome.say("没保存成功：" + failed.getMessage(), S.red);
            return;
        }
        if (!problems.isEmpty()) {
            chrome.say(problems.get(0), S.red);
            return;
        }
        reported = fresh;
        if (nameValue != null) {
            nameValue.setText(nameLine());
        }
        if (modelValue != null) {
            modelValue.setText(modelLine());
        }
        // 运行中改名只改了一半：应答每次连接都重读设置，证书与广播名要到下次启动才换。这就是
        // 一句话的后半截，不能省——省掉它，人会在 iPhone 上看到一个还没换的名字，以为没存。
        chrome.say(listener.running()
                ? "已保存 · 证书与广播名要重启接收端才换"
                : "已保存", S.green);
    }

    /**
     * 表里每一个开关，按目录给的顺序。
     *
     * <p>这里不点名任何一个键：加一个开关只需在 Rust 的表里加一行，这页不用改。
     */
    private View switchCard() {
        LinearLayout card = chrome.rowCard();
        List<SettingsScreen.Setting> settings = switches();
        for (int i = 0; i < settings.size(); i++) {
            if (i > 0) {
                card.addView(chrome.divider(), chrome.insetDivider());
            }
            // 只有一行时四个角都圆：先判 first 再判 last 会让独苗只剩上两个圆角
            boolean first = i == 0;
            boolean last = i == settings.size() - 1;
            float[] corners = first && last ? Scheme.CORNERS_ALL
                    : first ? Scheme.CORNERS_TOP
                    : last ? Scheme.CORNERS_BOTTOM : null;
            addSwitch(card, settings.get(i), corners);
        }
        if (settings.isEmpty()) {
            TextView empty = chrome.caption("这份设置表里没有开关。");
            empty.setPadding(chrome.dp(18), chrome.dp(16), chrome.dp(18), chrome.dp(16));
            card.addView(empty);
        }
        return card;
    }

    private void addSwitch(LinearLayout card, SettingsScreen.Setting setting, float[] corners) {
        // 开关在表里的取值是 "true"/"false"，不是 "on"/"off"：crates/settings 的 check() 只认这两
        // 个字面量，写别的会被拒（拒得对——它是设置的唯一真相）。这里曾经按 "on" 读、按 "on" 写，
        // 于是默认开着的那一项在页面上显示成关着，拨一下就报「只能是开或关」。
        boolean on = "true".equals(setting.value());

        LinearLayout box = chrome.column();
        Toggle toggle = toggle(on);
        LinearLayout row = chrome.featureRow(
                IconView.SLIDERS, setting.title(), setting.summary(), toggle);
        // 这一行的右边被拨杆占去 50dp，中间那条文字就只剩下一百来 dp——设置表里的说明常常是
        // 一句完整的话，两行放不下、要折成三行。66dp 是下限不是定高：定高会把第三行裁在卡片
        // 下沿上，而且裁得只剩半行字，看起来像字号坏了（设备信息卡里的内核那一行同病）。
        row.setMinimumHeight(chrome.dp(chrome.rowHeight()));
        row.setPadding(chrome.dp(18), chrome.dp(8), chrome.dp(18), chrome.dp(8));
        box.addView(row, chrome.mm(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0f));

        TextView problem = chrome.text("", 12.5f, false, S.red);
        problem.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = chrome.margins(4, 8);
        plp.leftMargin = chrome.dp(74);
        box.addView(problem, plp);

        card.addView(box, chrome.mm(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0f));

        // 按下高亮挂在整块上：一行里除了文字还有一个开关，只有整块一起亮才读得出是哪一行
        chrome.pressableRow(box, corners);

        toggle.setOnChange((button, checked) -> {
            // 先记下拨之前的值。写入是「先记在内存、save() 时才校验」的两段式，存不下的话内存
            // 里留着的就是那个被拒的坏值——只把开关拨回去是不够的，模型本身也得一起退回来，
            // 否则下一次读 setting.value() 读到的还是它。
            String before = setting.value();
            screen.set(setting.key(), checked ? "true" : "false");
            List<String> problems;
            try {
                problems = screen.save();
            } catch (IOException failed) {
                problems = List.of("写不进去：" + failed.getMessage());
            }
            if (problems.isEmpty()) {
                problem.setVisibility(View.GONE);
                // 跟随这一项改了之后，接收端要立刻按新的意思对齐，不然这一页自己就前后矛盾。
                ReceiveService.follow(host);
                syncReceive();
                chrome.say(setting.title() + (checked ? " 已打开" : " 已关闭"), S.text);
                return;
            }
            // 存不下就把开关拨回文件里的值：留着它拨过去的样子，等于让人以为设置生效了。
            screen.set(setting.key(), before);
            button.setChecked("true".equals(before));
            problem.setText(String.join("\n", problems));
            problem.setVisibility(View.VISIBLE);
        });
    }

    /**
     * 传输状态：上一次收到了什么，文件落在哪里。
     *
     * <p>legacy 那块是实时速度双列（↑/↓ 加两个大数字），这一版的接收端一次只答一个对端、
     * 报告是整次传输结束后才回来的，没有实时的字节流可以印。所以同一张卡改成印「上次接收」：
     * 一个 30sp 的标题位换成一句话——数字没有的时候，一个「—」比一个假的 0.0 更诚实。
     */
    private View statusCard() {
        LinearLayout card = chrome.card();
        String last = listener.last();

        LinearLayout head = chrome.row();
        head.addView(chrome.text("上次接收", 16, true, S.text),
                chrome.mm(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(chrome.statusPill(
                last.isEmpty() ? "空闲" : "已收到", last.isEmpty() ? S.gray : S.green));
        card.addView(head);

        TextView line = chrome.text(last.isEmpty() ? "还没有收到文件" : last, 15, false,
                last.isEmpty() ? S.textDim : S.text);
        line.setPadding(0, chrome.dp(10), 0, chrome.dp(4));
        card.addView(line);

        card.addView(chrome.divider(), chrome.margins(12, 12));

        card.addView(chrome.caption("接收目录"));
        TextView directory = chrome.text(directory(), 13.5f, false, S.textDim);
        directory.setPadding(0, chrome.dp(3), 0, 0);
        card.addView(directory);
        return card;
    }

    private String directory() {
        SettingsScreen.Setting setting = screen.setting("receive.directory");
        String value = setting == null ? "" : setting.value().trim();
        return value.isEmpty() ? listener.directory() : value;
    }

    /** 表里的开关，保持目录的分组顺序。 */
    private List<SettingsScreen.Setting> switches() {
        List<SettingsScreen.Setting> found = new java.util.ArrayList<>();
        for (SettingsScreen.Section section : screen.sections()) {
            for (SettingsScreen.Setting setting : section.settings()) {
                if (setting.control() == SettingsScreen.Control.SWITCH) {
                    found.add(setting);
                }
            }
        }
        return found;
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
