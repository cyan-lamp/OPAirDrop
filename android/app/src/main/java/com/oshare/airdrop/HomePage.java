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
 * <p>发送入口一行、环境两行、设备信息六行。最上面那张开场白卡片已经拿掉：它说的是「这个
 * 应用是什么」，而打开应用的人已经知道了——它占掉的正好是第一屏最贵的那块位置，把真正要
 * 点的那一行和两个前提条件一起挤到折线以下。现在页首直接就是「传输」分组标题。
 *
 * <p># 为什么环境那两行从 LSP / root 换成了接收端 / 一加互传
 *
 * <p>legacy 要靠 LSPosed 注入互传进程、要靠 root 读框架状态，所以那两行问的是「桥搭起来
 * 没有」。这一版接收端在自己的进程里监听 8770，不再需要框架也不再需要 root，于是同一张卡
 * 要回答的问题变成了「接收端在不在跑」「互传把本机设成谁能找到」——还是两行、还是一颗状态
 * 胶囊、还是同一条分隔线，只是答案换成了这一版真正依赖的两样东西。
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
     */
    private static final int EXPECTED_ABI = 2;

    private final Activity host;
    private final Chrome chrome;
    private final Scheme S;
    private final Listener listener;

    HomePage(Activity host, Chrome chrome, Listener listener) {
        this.host = host;
        this.chrome = chrome;
        this.S = chrome.scheme();
        this.listener = listener;
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

    /** 发送入口：整个应用被打开的理由，所以排在最前。 */
    private View sendCard() {
        LinearLayout card = chrome.rowCard();
        LinearLayout row = chrome.featureRow(
                IconView.SEND, "发送文件", "选文件，发给附近的 Apple 设备",
                chrome.text("›", 20, true, S.textDim));
        chrome.addRow(card, row, chrome.rowHeight());
        chrome.setRowAction(row, true, Scheme.CORNERS_ALL,
                () -> chrome.say("主动发送还没接入：现在靠接收端应答对端", S.orange));
        return card;
    }

    /**
     * 环境两行：接收端、一加互传。
     *
     * <p>分隔线从文字左缘起笔（18 内边距 + 44 图标砖 + 12 间隙），和下面设备信息卡一致——
     * 一条从卡片左缘起笔的线会把两行的图标砖串成一条，读起来像表格的竖线。
     */
    private View envCard() {
        LinearLayout card = chrome.rowCard();

        boolean running = listener.running();
        boolean failed = listener.problem();
        TextView listenPill = chrome.statusPill(
                failed ? "异常" : running ? "监听中" : "已停止",
                failed ? S.red : running ? S.green : S.gray);
        LinearLayout listenRow = chrome.featureRow(
                IconView.RADAR, "接收端", listenState(), listenPill);
        chrome.addRow(card, listenRow, chrome.rowHeight());
        // 整行点一下就把接收端拉起来 / 停下：这是首页唯一一个「看状态顺手动一下」的地方，
        // 但它不写任何设置，所以不算开关。
        chrome.setRowAction(listenRow, !Oshare.following(host), Scheme.CORNERS_TOP, () -> {
            // 先记住拨之前的样子：start/stop 是往工作线程派活，回来问 running() 时答案已经
            // 变了，于是提示词会和刚发生的事正好相反。
            boolean wasRunning = listener.running();
            if (wasRunning) {
                listener.stop();
            } else {
                listener.start();
            }
            chrome.say("接收端正在" + (wasRunning ? "停止" : "启动"), S.text);
        });

        card.addView(chrome.divider(), chrome.insetDivider());

        int share = Oshare.stateOf(host);
        TextView sharePill = chrome.statusPill(shareLabel(share), shareColor(share));
        LinearLayout shareRow = chrome.featureRow(
                IconView.PHONE, "一加互传", Oshare.label(host), sharePill);
        chrome.addRow(card, shareRow, chrome.rowHeight());
        chrome.setRowAction(shareRow, false, null, null);
        return card;
    }

    /**
     * 设备信息六行：型号、版本号、内核、ColorOS、Android、安全补丁。
     *
     * <p>六行都不可点，但仍然挂着按压高亮（第一行圆上两角、最后一行圆下两角）：这张卡是整页
     * 最像列表的一块，没有按下反馈的那一行会让人以为界面卡住了。
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
            chrome.pressableRow(row, i == 0 ? Scheme.CORNERS_TOP
                    : i == titles.length - 1 ? Scheme.CORNERS_BOTTOM : null);
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
        return listener.state();
    }

    private String shareLabel(int share) {
        switch (share) {
            case 1:
                return "所有人";
            case 2:
                return "仅联系人";
            case 0:
                return "已关闭";
            default:
                return "读不到";
        }
    }

    private int shareColor(int share) {
        if (share == 1) {
            return S.green;
        }
        return share < 0 ? S.red : S.gray;
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
