package com.oshare.airdrop;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首页：一眼看完「这台设备现在是什么状况」。
 *
 * <p># 三块，从上到下
 *
 * <p>传输两张卡（发送文件 / 接收文件）、环境一张卡（root 权限）、设备信息六行。最上面那张开
 * 场白卡片已经拿掉：它说的是「这个应用是什么」，而打开应用的人已经知道了——它占掉的正好是
 * 第一屏最贵的那块位置。现在页首直接就是「传输」分组标题。
 *
 * <p># 为什么收发挨在一起、root 单列在环境
 *
 * <p>接收文件原先在「环境」里，和发送文件分在两处——可收发本是一件事的两面，人打开这一页
 * 要先同时看到「我要发」和「我在收」。于是接收挪到传输板块、紧跟发送下面，用分隔线分开；
 * 环境板块让位给 root 权限，它回答的是「这台机器给没给 root、是谁给的」，是这一个 app 能否
 * 装驱动的前提，和收发不是一个层面。
 *
 * <p># 这一页只回答问题，不放开关
 *
 * <p>开关会改行为，而这里每一行说的都是「刚刚发生了什么」。把两者放在同一页，人就会以为那
 * 一行是按钮，或者以为那个按钮只是状态。改行为的东西全在「功能」页。
 */
final class HomePage {
    /**
     * 本次构建编译时对齐的 ABI。
     *
     * <p>与库自报的数字比一次：APK 里的 {@code .so} 比调用它的 Java 旧时，这两个数就会不
     * 相等。这一步要在第一次结构化调用之前完成——两个数字是一句诊断，段错误不是。
     *
     * <p>数字本身在 {@link Native#ABI_VERSION}。这里原本也写着一个 2，于是库升到 3 的时候
     * 它没跟着动，接收端拒绝启动并报「库的 ABI 是 3，本版需要 2」——明明是新的库，却说它旧。
     */
    private static final int EXPECTED_ABI = Native.ABI_VERSION;

    private final Activity host;
    private final Chrome chrome;
    private final Scheme S;
    private final Listener listener;
    /** root 探测结束后用来重画那一行；由 MainActivity 传进来（就是 refresh）。 */
    private final Runnable onRootChecked;

    HomePage(Activity host, Chrome chrome, Listener listener, Runnable onRootChecked) {
        this.host = host;
        this.chrome = chrome;
        this.S = chrome.scheme();
        this.listener = listener;
        this.onRootChecked = onRootChecked;
    }

    View view() {
        LinearLayout body = chrome.pageBody();

        // 6dp：题头自己只有 8dp 下留白、没有上留白，直接贴着滚动容器的 4dp 顶边会顶到导航
        // 胶囊下沿上。不是 16——那是原来那张开场白卡片的间距，一张 48dp 高的卡片才需要那么多。
        body.addView(chrome.sectionTitle("传输"), chrome.margins(6, 0));
        body.addView(sendCard(), chrome.margins(0, 16));

        body.addView(chrome.sectionTitle("环境"));
        body.addView(envCard(), chrome.margins(0, 16));

        body.addView(chrome.sectionTitle("设备信息"));
        body.addView(devCard(), chrome.margins(0, 16));

        return chrome.scroller(body);
    }

    /**
     * 发送入口：整个应用被打开的理由，所以排在最前。
     *
     * <p># 这一行和系统分享面板进的是同一页
     *
     * <p>两条路的区别只在「手上有没有文件」：从别的应用分享过来时，文件已经在 intent 里；
     * 从这里点进去时，人只有「我要发点东西」这个念头。所以这里不塞任何 data，就让
     * {@link SendActivity} 自己先弹出选择器——判断只有一处，不会出现「分享进来的和点进来
     * 的不是同一页」这种两套逻辑。空手进去时那一页也知道该怎么开场。
     */
    private View sendCard() {
        LinearLayout card = chrome.rowCard();
        LinearLayout row = chrome.featureRow(
                IconView.SEND, "发送文件", "选文件，发给附近的 Apple 设备",
                chrome.text("›", 20, true, S.textDim));
        chrome.addRow(card, row, chrome.rowHeight());
        chrome.setRowAction(row, true, Scheme.CORNERS_TOP, () -> {
            android.content.Intent send = new android.content.Intent(host, SendActivity.class);
            try {
                host.startActivity(send);
            } catch (Throwable unavailable) {
                // 起不来只可能是这一版的清单里没有它。说清楚，而不是什么都不发生。
                chrome.say("发送页打不开：" + unavailable);
            }
        });

        // 接收文件原先在「环境」那张卡里。挪到传输板块、紧跟发送文件下面：收发本就是一件事的
        // 两面，人打开这一页要先看到「我要发」和「我在收」这两个动作，而不是把它们分到两处。
        card.addView(chrome.divider(), chrome.insetDivider());
        TextView[] receiveSubOut = new TextView[1];
        LinearLayout receiveRow = chrome.featureRow(
                IconView.RECEIVE, "接收文件", listenState(), buildListenPill(), receiveSubOut);
        receiveSub = receiveSubOut[0];
        chrome.addRow(card, receiveRow, chrome.rowHeight());
        chrome.setRowAction(receiveRow, true, Scheme.CORNERS_BOTTOM, () -> {
            // 和控制中心那块【AirDrop】磁贴走同一条路：都读写 ReceiveService 的 manual 凭据。
            // 之前这里直接 listener.start/stop，只动了底层监听、没写凭据，结果磁贴那边（或下次
            // follow 对齐）一看凭据没变就把接收端改回去——两个开关各说各话。现在拨这一行，就是
            // 拨那个磁贴：开用 enable(0)（一直开），关用 setManual(false)+apply。
            boolean wasOn = ReceiveService.listening(host);
            if (wasOn) {
                ReceiveService.setManual(host, false);
                ReceiveService.apply(host);
                chrome.say("接收文件已关闭");
            } else {
                ReceiveService.enable(host, 0);
                // 先起接收端、再要权限：这两件事的顺序决定了人看到什么。反过来问，人可能
                // 在权限框上犹豫一会儿，而这段时间里开关还是关的——他刚点的那个动作像是没
                // 生效。接收端先跑起来，权限框只回答「看不看得见」，不决定「收不收」。
                //
                // 缺的不是一条权限：没有通知，「有人要发东西给你」这句话一个字也送不出去；
                // 没有蓝牙广播，对端连这台机器在不在都不知道。两条都在这里问，因为缺任何
                // 一条，这个开关都是假的。
                Notices.ensure(host);
                String gap = Notices.gap(host);
                chrome.say(gap == null ? "接收文件已开启" : "接收已开启，但" + gap);
            }
            // 就地刷新胶囊与副标题（这一页此刻不重建），磁贴下次展开会自己按新凭据重画。
            // 权限那条要在拨完之后重新算：`ensure` 只是把框弹出去，答案不会立刻回来。
            String blind = wasOn ? null : Notices.shortGap(host);
            chrome.setPill(listenPill,
                    wasOn ? "已停止" : blind != null ? blind : "监听中",
                    wasOn ? S.gray : blind != null ? S.orange : S.green);
            if (receiveSub != null) {
                receiveSub.setText(listenState());
            }
        });
        return card;
    }

    /**
     * 「接收文件」那行的状态胶囊与副标题引用：拨完就地刷新，不必重建整页。
     * 每次画卡都会重新赋值（{@link #sendCard()} 每次 new）。
     */
    private TextView listenPill;
    private TextView receiveSub;

    /** 接收文件那行的状态胶囊，每次画卡现取——状态在运行中会变。 */
    private TextView buildListenPill() {
        boolean running = ReceiveService.listening(host);
        boolean failed = listener.problem();
        // 在跑、却缺一条权限：这是唯一一个「看起来一切正常、实际全聋」的状态。胶囊这时
        // 不能说「监听中」——那是在说端口在听，而人关心的是他能不能收到东西。缺的那一块
        // 必须写在这一行上，否则它只能从「怎么又收不到」这个抱怨里被反推出来。
        //
        // 权限排在 `problem()` 之前：库报错是这一版没编好，人自己修不了；权限是他点两下就能
        // 解决的事，而它恰恰是此刻真正拦住他的那一个。
        String blind = running ? Notices.shortGap(host) : null;
        listenPill = chrome.statusPill(
                blind != null ? blind : failed ? "异常" : running ? "监听中" : "已停止",
                blind != null ? S.orange : failed ? S.red : running ? S.green : S.gray);
        return listenPill;
    }

    /**
     * 环境一行：root 权限。
     *
     * <p>接收端已经挪到传输板块、和发送文件挨在一起了；互传那一行早在脱离一加互传时删掉。
     * 这一张卡现在只剩一个问题：机器给没给 root、是谁给的。
     *
     * <p>第一次进来时探测还没跑完，先显示「检测中」占位；{@link RootInfo#probe} 在后台线程查完
     * 会回调 {@link #onRootChecked} 重画这一行。之后用进程内缓存，不再反复起 su 弹授权。
     */
    private View envCard() {
        LinearLayout card = chrome.rowCard();

        // LSPosed 框架一行：流体云那一刀跑在 SystemUI 进程里，它的作用域勾没勾全直接
        // 决定卡片听不听话——这是环境的一部分，和 root 排在一起。
        chrome.addRow(card, lspRow(), chrome.rowHeight());
        card.addView(chrome.divider(), chrome.insetDivider());

        RootInfo.Snapshot root = RootInfo.get();
        String pill;
        int pillColor;
        String subtitle;
        if (!root.checked) {
            pill = "检测中";
            pillColor = S.gray;
            subtitle = "正在读取 root 授权状态";
        } else if (root.granted) {
            pill = "已获取";
            pillColor = S.green;
            // 「 · 」和「LSPosed 框架」那一行的版本号同一格式。
            subtitle = root.manager.isEmpty()
                    ? "已获得 root 授权"
                    : root.manager + (root.version.isEmpty() ? "" : " · " + root.version);
        } else {
            pill = "未获取";
            pillColor = S.red;
            subtitle = "请在管理器授予 root 权限";
        }
        TextView rootPill = chrome.statusPill(pill, pillColor);
        LinearLayout rootRow = chrome.featureRow(
                IconView.TERMINAL, "root 权限", subtitle, rootPill);
        chrome.addRow(card, rootRow, chrome.rowHeight());
        // 可按压：点一下重新测一次授权（人刚在 KernelSU / Magisk 里点了授权，不必重启应用）。
        // root 行现在排在 LSPosed 之后，是这张卡的最后一行：按压高亮的圆角跟着换到下面两角。
        chrome.setRowAction(rootRow, true, Scheme.CORNERS_BOTTOM, () -> {
            chrome.say("正在重新检测 root 权限");
            RootInfo.probe(host, onRootChecked);
        });

        // 没测过就起一次；测过了但显示「未获取」也允许点行重测，这里不自动重跑。
        if (!root.checked) {
            RootInfo.probe(host, onRootChecked);
        }
        return card;
    }

    /**
     * LSPosed 框架一行。三态，全部从 {@link Lsp} 的缓存现读：
     *
     * <ul>
     *   <li>框架在且作用域齐全：绿胶囊「已生效」，副标题是框架版本和我们的 API 版本——
     *       这两样回答「hook 环境是否就绪」，比一句「正常」有用。</li>
     *   <li>框架在但作用域缺：橙胶囊「作用域未齐」，行可按——走 LSPosed 官方的
     *       {@code requestScope}，框架自己弹确认框，批下来 scope 事件会重画这一行。</li>
     *   <li>框架不在/未启用：灰胶囊「未连接」。LSPosed 的服务只对它认得的模块开放，
     *       连不上就说明框架没装、模块没启用，或者框架还没下发凭据。</li>
     *   </ul>
     */
    private View lspRow() {
        boolean ready = Lsp.framework() && Lsp.scoped();
        String pill;
        int pillColor;
        String subtitle;
        if (Lsp.framework()) {
            if (Lsp.scoped()) {
                pill = "已生效";
                pillColor = S.green;
                subtitle = Lsp.versionLine();
            } else {
                pill = "作用域未齐";
                pillColor = S.orange;
                subtitle = "点这里向 LSPosed 申请作用域";
            }
        } else {
            pill = "未连接";
            pillColor = S.gray;
            subtitle = "未检测到 LSPosed 框架，或模块尚未启用";
        }

        TextView lspPill = chrome.statusPill(pill, pillColor);
        LinearLayout row = chrome.featureRow(
                IconView.CARD, "LSPosed 框架", subtitle, lspPill);
        // 三态都可点（2026-11-25 用户要求：已生效也要有涟漪）——未齐走申请，已生效重读
        // 一次作用域并确认，未连接只是说一声。没有点击动作的行挂着涟漪才会让人误会，
        // 反过来，有意义的行就该有反馈。
        chrome.setRowAction(row, true, Scheme.CORNERS_TOP, () -> {
            if (Lsp.framework()) {
                if (Lsp.scoped()) {
                    Lsp.refresh();
                    chrome.say(Lsp.scoped()
                            ? "LSPosed 作用域齐全：" + Lsp.versionLine()
                            : "作用域有变化，点这行重新申请");
                } else {
                    Lsp.requestScope();
                    chrome.say("已向 LSPosed 申请作用域，请在弹窗里确认");
                }
            } else {
                chrome.say("未检测到 LSPosed 框架");
            }
        });
        return row;
    }

    /**
     * 设备信息六行：型号、版本号、内核、ColorOS、Android、安全补丁。
     *
     * <p>六行纯展示，不挂按压高亮（2026-11-25 按用户要求去掉）：它们没有点击动作，
     * 按下去没反应的高亮反而让人以为界面出了错。
     */
    private View devCard() {
        LinearLayout card = chrome.rowCard();

        TextView model = value();
        TextView build = value();
        TextView kernel = value();
        TextView coloros = value();
        TextView android = value();
        TextView patch = value();

        fillDeviceInfo(model, build, coloros, android, patch);
        fillKernel(kernel);

        TextView[] values = {model, build, kernel, coloros, android, patch};
        String[] titles = {"设备型号", "版本号", "内核版本", "ColorOS 版本", "Android 版本", "安全补丁"};
        int[] icons = {IconView.PHONE, IconView.CARD, IconView.KERNEL,
                IconView.CHIP, IconView.ANDROID, IconView.PATCH};

        for (int i = 0; i < titles.length; i++) {
            LinearLayout row = chrome.deviceRow(icons[i], titles[i], values[i]);
            // 六行都用「最小 66」而不是「正好 66」：内核那一行折行时不该被裁掉下半截。
            chrome.addWrappingRow(card, row);
            if (i < titles.length - 1) {
                card.addView(chrome.divider(), chrome.insetDivider());
            }
        }
        return card;
    }

    // -------------------------------------------------------------- 内容

    private TextView value() {
        return chrome.text("—", 13.5f, false, S.textDim);
    }

    /** 接收端那句话；库没加载时把原因顶上来，比「未开始」有用得多。 */
    private String listenState() {
        int abi;
        try {
            abi = Native.abiVersion();
        } catch (Throwable failed) {
            return "库没有加载：" + failed;
        }
        if (abi != EXPECTED_ABI) {
            return "库的 ABI 是 " + abi + "，本版需要 " + EXPECTED_ABI;
        }
        if (!listener.last().isEmpty()) {
            return listener.last();
        }
        // 没有正在发生的事，而接收端在跑、却缺一条权限：这句话要压在 `state()` 上面。
        // `state()` 说的是端口在不在听，人问的是「东西为什么没来」——这两句话在权限缺着的
        // 时候指的是完全不同的事实，而只有第二句是他需要的。
        if (ReceiveService.listening(host)) {
            String gap = Notices.gap(host);
            if (gap != null) {
                return gap;
            }
        }
        return listener.state();
    }

    /**
     * 五行来自公开 API 和两次反射读系统属性。
     *
     * <p>{@code ro.vendor.oplus.market.name} 是市场名（「一加 13」），比 {@code Build.MODEL}
     * 的设备代号好认；ColorOS 版本没有公开 API，只能反射 {@code SystemProperties}——和
     * {@code getprop} 读的是同一份数据，但不起进程、不要 root。
     *
     * <p>参数名没有一个叫 {@code android}：那会把这个文件里每一处 {@code android.os.Build}
     * 都解析成一个局部变量，而编译器只会说「找不到符号 os」。
     */
    private void fillDeviceInfo(TextView model, TextView build, TextView coloros,
                                TextView androidVersion, TextView patch) {
        setInfo(model, firstNonEmpty(
                sysProp("ro.vendor.oplus.market.name"), android.os.Build.MODEL));
        setInfo(build, android.os.Build.DISPLAY);
        setInfo(coloros, sysProp("ro.build.version.oplusrom"));
        setInfo(androidVersion, android.os.Build.VERSION.RELEASE);
        setInfo(patch, android.os.Build.VERSION.SECURITY_PATCH);
    }

    /**
     * 内核版本，直接问 {@code uname(2)}。
     *
     * <p>整串 release 都印出来（{@code 6.6.118-android15-8-g9bc34d5b0c79-...}）：前面几位是
     * 内核版本，后面那段是厂商的构建标记，出问题时要的正是后面那段。
     */
    private void fillKernel(TextView view) {
        try {
            setInfo(view, android.system.Os.uname().release);
        } catch (Throwable unreadable) {
            view.setText("未知");
        }
    }

    private static void setInfo(TextView view, String text) {
        view.setText(text == null || text.isEmpty() ? "未知" : text);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : (second == null ? "" : second);
    }

    private static String sysProp(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (String) properties.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable restricted) {
            return "";
        }
    }
}
