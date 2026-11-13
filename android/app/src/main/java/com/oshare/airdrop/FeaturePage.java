package com.oshare.airdrop;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private final Activity host;
    private final Chrome chrome;
    private final Scheme S;
    private final Listener listener;
    /** 这页读到的那份表；每次切到本页都重新打开，别的页改过的值才会被看见。 */
    private final SettingsScreen screen;

    /**
     * 接收端那一行：拨杆和它下面那句话。
     *
     * <p>「跟随」这一项在下面那张卡里也能改，改完这一行必须立刻跟着变——跟随开着时接收端的
     * 拨杆是灰的（它归一加互传管），关掉跟随它才该能按。留着这两个引用，是为了改完就地刷新这
     * 一行，而不是整页重建：重建会把滚动位置、正在播的拨杆动画一起丢掉。
     */
    private Toggle receiveToggle;
    private TextView receiveState;

    FeaturePage(Activity host, Chrome chrome, Listener listener, SettingsScreen screen) {
        this.host = host;
        this.chrome = chrome;
        this.S = chrome.scheme();
        this.listener = listener;
        this.screen = screen;
    }

    View view() {
        LinearLayout body = chrome.pageBody();

        body.addView(chrome.sectionTitle("接收"));
        body.addView(receiveCard(), chrome.margins(0, 12));

        // 按钮占掉的那一段还回去：卡的下留白 12 + 这里 6 = 18，和首页「卡→下一段标题」的
        // 16 是同一个节奏。
        body.addView(chrome.sectionTitle("开关"), chrome.margins(6, 0));
        body.addView(switchCard(), chrome.margins(0, 16));

        body.addView(chrome.sectionTitle("传输状态"));
        body.addView(statusCard(), chrome.margins(0, 16));

        return chrome.scroller(body);
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
