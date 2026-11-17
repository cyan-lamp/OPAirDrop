package com.oshare.airdrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关于：这一版是什么、源码在哪、包里带着谁的许可。
 *
 * <p># 为什么单开一个 Activity
 *
 * <p>它是唯一一个「看」的页面——不拨开关，不改值，看完就走。挂在设置页下面当一个可展开
 * 的段落，会让人以为它是设置的一部分；做成独立页面，返回就是退出，和看一条通知的份量相当。
 *
 * <p># 四个板块的顺序（源码 → 开源许可 → 版本信息 → 版本更新）
 *
 * <p>这个顺序是从「人打开这一页想知道什么」排的：先问「这是谁写的、我能看吗」，再问
 * 「我能拿它做什么」（许可），然后才是「我手上这一份是哪一版」。版本这两节放最后还有一层
 * 实际理由：它们是唯一会变的两块，长在页面最下面时，前两节的位置在每一版里都一样。
 *
 * <p># 「版本信息」与「版本更新」为什么是两个板块
 *
 * <p>它们长得像，做的事情却相反：上面那张卡是<b>读</b>（这一份是什么，不会因为按一下就变），
 * 下面那一行是<b>做</b>（去问一句、可能把浏览器打开）。曾经把「检测更新」塞进版本信息那张
 * 卡的下半截，于是「版本Code 10000」和一颗会发网络请求的按钮落在同一个框里——同一张卡里的
 * 东西被默认当成一类，人就会以为上面几条也能点。分开之后，板块标题自己说明了下半截是干什么
 * 的，而「版本信息」这张卡回到纯粹的陈述。
 *
 * <p># 为什么前两块是「一行 + 一个 ›」，而不是一段说明文字
 *
 * <p>上一版把仓库结构、crate 清单、许可范围全写在卡片里，于是这一页最长的三屏是散文。
 * 那些内容没有一条是必须当场读完的——它们是「点进去看」的东西。做成一行按钮只保留一句
 * 摘要（仓库地址 / 许可名 + 行数），点开才是全文；这样一屏能看完三节，而全文一行没删。
 * 行本身用 {@code rowCard + featureRow + addRow}，和功能页【接收文件保存位置】是同一套
 * 结构，不是这里另画的一种行。
 *
 * <p># 为什么许可要一个子页，而不是在行下面展开
 *
 * <p>GPL-2.0 全文 339 行、近 18KB。展开在行情里会把上面两节推到屏幕外，而且那一行按下去
 * 之后自己变成三屏高，人再按一次才能收回去——一个「看」的动作不该改变页面的骨架。子页
 * 有自己的标题和返回，退出就是回到这一页，位置不变。
 *
 * <p># 每一条都来自包本身
 *
 * <p>版本号读 {@link PackageInfo}，ABI 读 {@link Native#abiVersion}，许可读 raw 资源。
 * 这个页面里没有一处关于「包里装了什么」的说法是手写的——手写的会过期，而过期的关于页比
 * 没有关于页更糟：它在断言。
 *
 * <p># 配色跟着主界面走
 *
 * <p>它自己是另一个 Activity，拿不到主界面那份 {@link Scheme}，所以从头按系统的日夜模式建
 * 一张同样的表。写死颜色的话，深色下这一页会是一块白纸从黑色界面里弹出来——而这正是以前
 * 的样子。
 */
public final class AboutActivity extends Activity {
    /** 外壳期望的 ABI。对不上时这一页要说出来，而不是显示一个「关于」就完事。 */
    private static final int EXPECTED_ABI = Native.ABI_VERSION;

    /** 源码仓库。行里显示的就是它，点一下交给浏览器。 */
    private static final String REPO = "https://github.com/cyan-lamp/OPAirDrop";

    /**
     * 查最新发行版用的接口。
     *
     * <p># 为什么用列表接口而不是 {@code /releases/latest}
     *
     * <p>{@code /releases/latest} 在「一个发行版都没有」的时候回 404——和「仓库不存在」
     * 「仓库转私有」回的是同一个 404。三种情况对人有三种完全不同的意思（去发布一个 /
     * 地址写错了 / 该重新登录），却被压成同一个状态码，于是页面上只能写一句谁都对不上的
     * 话。列表接口在没有发行版时回 200 + {@code []}，这个空数组是能分开判断的。
     *
     * <p>{@code per_page=1}：只要最新那条。GitHub 按创建时间倒序给，第一条就是最新的，
     * 而完整的列表在发行版多起来以后能有几百 KB——一个「检测更新」不该为此下一份目录。
     */
    private static final String RELEASES_API =
            "https://api.github.com/repos/cyan-lamp/OPAirDrop/releases?per_page=1";

    /** 连接和读取各自的超时。加起来最多 16 秒，比人愿意盯着一行「正在检查…」更短一点。 */
    private static final int TIMEOUT_MS = 8000;

    private Chrome chrome;
    private Scheme S;

    /** 本机版本号（只有 {@code versionName}，不带 versionCode），比较版本时用。 */
    private String currentVersion = "";

    /** 「检测更新」那一行的副标题；检查的过程与结果都写在这上面。 */
    private TextView updateLine;
    /** 正在查。<b>不是</b>「查过一次」——连点两下不该发两个请求。 */
    private boolean checking;
    /**
     * 查到的、而且比本机新的那个发行版；{@code null} 表示「没有更新的」。
     *
     * <p>它同时决定那一行按下去做什么：有它就去打开发行版页面，没有它就是再查一次。
     * 这样「有新版本」这件事只有一个落点，不必再弹一个框问人要不要去。
     */
    private Release newer;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        S = Scheme.of(mode != Configuration.UI_MODE_NIGHT_YES);
        chrome = new Chrome(this, S);
        currentVersion = versionName();

        LinearLayout body = chrome.pageBody();
        body.addView(back());
        body.addView(title());

        body.addView(chrome.sectionTitle("源码"), chrome.margins(16, 0));
        body.addView(sourceCard(), chrome.margins(0, 16));

        body.addView(chrome.sectionTitle("开源许可"));
        body.addView(licenseCard(), chrome.margins(0, 16));

        body.addView(chrome.sectionTitle("版本信息"));
        body.addView(versionCard(), chrome.margins(0, 16));

        // 检测更新自成一节，理由见类注释「两个板块为什么分开」。间距和上面几节一样是 16：
        // 它现在是这一页最后一块，收尾不需要比别处多留，滚动区自己还有 24 的底边。
        body.addView(chrome.sectionTitle("版本更新"));
        body.addView(updateCard(), chrome.margins(0, 16));

        ScrollView scroll = chrome.scroller(body);
        // 这一页没有顶部霜面，所以内容不许滚到状态栏底下：主界面那三页让内容从胶囊底下穿
        // 过去，是因为那里有一层玻璃把文字糊掉；这里没有玻璃，字就会直接压在时间和电量图标
        // 上，读起来是两个东西叠在一起。裁在状态栏下沿，滚出去的就真的看不见了。
        scroll.setClipToPadding(true);
        // 这一页没有标题胶囊，不必让出顶部那一颗的高度：只要避开系统栏，再留一点呼吸。
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
            view.setPadding(0, bars.top + chrome.dp(4), 0, bars.bottom + chrome.dp(24));
            return insets;
        });
        setContentView(scroll);
    }

    /**
     * 「‹ 返回」。
     *
     * <p>手势返回本来就能走，但这一页没有 ActionBar，屏幕上没有任何东西说明「怎么回去」。
     * 一个字的宽度换掉这份犹豫，划算。
     */
    private View back() {
        TextView back = chrome.text("‹ 返回", 15, true, S.accent);
        back.setPadding(0, chrome.dp(12), 0, chrome.dp(12));
        back.setOnClickListener(view -> finish());
        chrome.pressable(back);
        return back;
    }

    /**
     * 页面标题。只有一个「关于」。
     *
     * <p>这里原先还有一句副标题（{@code "OPAirDrop — 与 iPhone、Mac 互传的接收端"}）。删掉
     * 它的理由不是「太挤」，而是它在<b>说同一件事第二遍</b>：这一页是从设置页那一行【关于
     * OPAirDrop】点进来的，那一行的标题已经写着应用名；进来之后再念一遍应用名和它的用途，
     * 对已经知道自己装了什么的人没有一个字的增量。而这一页四个板块的标题都是「这两三个字说
     * 清一块」，多出来的这句会让标题区看起来比板块标题更重要——事实上这块屏幕里没有比
     * 「源码在哪」更需要被先看到的句子。
     */
    private View title() {
        return chrome.text("关于", 22, true, S.text);
    }

    /** 行尾那颗「›」。三行共用一份，免得三处各写一个字号。 */
    private TextView chevron() {
        return chrome.text("›", 20, true, S.textDim);
    }

    /**
     * 源码：一行，点开仓库。
     *
     * <p>副标题就是地址本身，不是「在浏览器中打开仓库」这种句式。地址是可以被读、被记住、
     * 被抄走的；一句话不能。而且这一行点下去确实会进浏览器——地址先出现，点开之后看到的是
     * 同一个地址，这一下就不算意外。
     */
    private View sourceCard() {
        LinearLayout card = chrome.rowCard();
        LinearLayout row = chrome.featureRow(
                IconView.NO_ICON, "源代码仓库",
                "github.com/cyan-lamp/OPAirDrop", chevron());
        chrome.addRow(card, row, chrome.rowHeight());
        chrome.setRowAction(row, true, Scheme.CORNERS_ALL, () -> open(REPO));
        return card;
    }

    /**
     * 开源许可：两行，一行是项目自己的 GPL-2.0，一行是随包进来的 crate 清单。
     *
     * <p># 为什么是两行而不是一行
     *
     * <p>「许可」在这个包里其实有两份，而且不是同一个东西：{@code LICENSE} 是这个项目
     * 自己声明的 GPL-2.0（用它的条件），{@code licenses.txt} 是 13 个 Rust crate 各自的
     * MIT / Apache-2.0 / 0BSD / Zlib / Unicode-3.0（随包分发的义务）。合成一行「开源许可」
     * 会让人以为点进去能看到全部，实际上只看到一半——而漏掉的那一半正好是别人要求你必须
     * 附上的那部分。
     *
     * <p>圆角分开给：上面一行 {@code CORNERS_TOP}、下面一行 {@code CORNERS_BOTTOM}，
     * 这样按下高亮只铺到自己那一行，不会有一块直角压在卡片圆角上。
     */
    private View licenseCard() {
        LinearLayout card = chrome.rowCard();

        LinearLayout gpl = chrome.featureRow(
                IconView.NO_ICON, "GNU 通用公共许可第二版",
                "本项目自身的许可，全文 339 行", chevron());
        chrome.addRow(card, gpl, chrome.rowHeight());
        chrome.setRowAction(gpl, true, Scheme.CORNERS_TOP, () -> showDoc(
                LicenseActivity.GPL, "GNU 通用公共许可第二版"));

        // 两行都是 NO_ICON，文字从 18dp 起笔，所以用 textDivider（18dp）而不是
        // insetDivider（74dp）——后者的 74 是给 44dp 图标砖让位的。见 Chrome.textDivider。
        card.addView(chrome.divider(), chrome.textDivider());

        LinearLayout crates = chrome.featureRow(
                IconView.NO_ICON, "第三方组件许可",
                "随包分发的 Rust crate 清单，由 cargo metadata 生成", chevron());
        chrome.addRow(card, crates, chrome.rowHeight());
        chrome.setRowAction(crates, true, Scheme.CORNERS_BOTTOM, () -> showDoc(
                LicenseActivity.CRATES, "第三方组件许可"));
        return card;
    }

    /** 版本信息：三行「这一版是什么」。一张卡里的三条，行间一条从卡片左缘起笔的线。 */
    private View versionCard() {
        LinearLayout card = chrome.card();
        String[][] facts = {
                {"版本", version()},
                {"包名", getPackageName()},
                {"Rust 核心", core()},
        };
        for (int i = 0; i < facts.length; i++) {
            if (i > 0) {
                card.addView(chrome.divider(), chrome.margins(10, 10));
            }
            card.addView(fact(facts[i][0], facts[i][1]));
        }
        return card;
    }

    /**
     * 检测更新：一行，副标题写过程和结果。
     *
     * <p># 为什么状态写在这一行里，而不是弹一条通知
     *
     * <p>这一页的 {@link Chrome} 没有接 {@code Notice}（那是主界面专属的一条管道，见
     * {@code MainActivity.notice}），{@code chrome.say} 在这里是空的。与其为一个页面再拉
     * 一条通知管道，不如把答案写在人按下去的那一行上：他按的是这一行，回话也该在这一行。
     *
     * <p># 为什么用 addWrappingRow
     *
     * <p>「发现新版本 v1.2.0（当前 1.0.0），点一下打开发行版页面」在窄屏上折成两行，而
     * {@code addRow} 钉死 66dp，第二行会被裁掉半截——看起来像字体坏了，而不是像有话说
     * 不完。这一行给最小高度，短的时候仍然是 66。
     */
    private View updateCard() {
        LinearLayout card = chrome.rowCard();
        TextView[] sub = new TextView[1];
        LinearLayout row = chrome.featureRow(
                IconView.NO_ICON, "检测更新",
                "当前 " + currentVersion, null, sub);
        updateLine = sub[0];
        chrome.addWrappingRow(card, row);
        chrome.setRowAction(row, true, Scheme.CORNERS_ALL, this::onUpdateRow);
        return card;
    }

    /**
     * 按下去做什么：查到新版本之后，这一行就变成「去打开发行版页面」。
     *
     * <p>两件事共用一行是有意的。人按「检测更新」的目的不是「看到一个版本号」，而是「拿到
     * 新的那一份」；把它拆成「检测」和「去下载」两颗按钮，中间那一步（发现有了，再去找下载
     * 在哪）就得由人自己补。副标题把当前这一行的作用说出来，所以它不是隐藏行为。
     */
    private void onUpdateRow() {
        if (newer != null) {
            open(newer.url);
            return;
        }
        check();
    }

    /**
     * 查一次。跑在后台线程上，结果回主线程。
     *
     * <p># 为什么要一个线程
     *
     * <p>Android 不许在主线程上发网络请求（{@code NetworkOnMainThreadException}），而且
     * 就算许，8 秒的连接超时也会把界面冻住——那 8 秒里连「返回」都按不动。
     *
     * <p># 为什么 {@code checking} 在按下的瞬间就置位
     *
     * <p>一次检查要走一两秒，这期间那一行看起来和没按一样。人在这种时候会再按两下，而每
     * 一下都是一个新线程、一个新请求——三个请求回来时抢着改同一行字，最后显示的是先到的
     * 那个结果。挡住第二次按下比事后去重省事。
     */
    private void check() {
        if (checking) {
            return;
        }
        checking = true;
        newer = null;
        updateLine.setText("正在检查…");
        new Thread(() -> {
            Release result = fetchLatest();
            runOnUiThread(() -> settle(result));
        }, "opairdrop-update").start();
    }

    /**
     * 把查回来的结果写成一句话。
     *
     * <p>四种结果各有各的说法，因为它们要人做的事完全不同：连不上（检查网络）、没有发行版
     * （等作者发）、已是最新（什么都不用做）、有新版本（去下载）。压成一句「检查失败」的话，
     * 前两种就都变成了「再试一次」——而其中一种再试一百次也不会有发行版。
     *
     * <p>{@code isFinishing} 那一句是防崩：线程跑完时人可能已经返回离开了，这时 {@code
     * updateLine} 还挂在已经拆掉的窗口上，改它就是 {@code IllegalStateException}。
     */
    private void settle(Release result) {
        checking = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (result.problem != null) {
            updateLine.setText("检查失败：" + result.problem);
            return;
        }
        if (result.tag == null) {
            updateLine.setText("仓库里还没有发布过发行版");
            return;
        }
        if (newerThan(result.tag, currentVersion)) {
            newer = result;
            updateLine.setText("发现新版本 " + result.tag
                    + "（当前 " + currentVersion + "），点一下打开发行版页面");
            return;
        }
        updateLine.setText("已是最新（" + result.tag + "）");
    }

    /**
     * 问 GitHub 要最新一条发行版。
     *
     * <p>返回的对象永不抛异常：网络这件事的每一种失败都要变成一句人话，所以这里把异常收在
     * {@link Release#problem} 里带回去，由 {@link #settle} 决定怎么说。
     */
    private Release fetchLatest() {
        HttpURLConnection link = null;
        try {
            link = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            link.setConnectTimeout(TIMEOUT_MS);
            link.setReadTimeout(TIMEOUT_MS);
            // Accept 是 GitHub 建议的版本选择头；UA 不给的话接口会拒——匿名请求也要求一个
            // 能认出调用方的名字，这一条在文档里写着，不是可选的礼仪。
            link.setRequestProperty("Accept", "application/vnd.github+json");
            link.setRequestProperty("User-Agent", "OPAirDrop/" + currentVersion);

            int code = link.getResponseCode();
            if (code == 404) {
                // 仓库不存在、或者转成了私有。两种都是「这个地址现在指不到东西」。
                return Release.problem("仓库地址打不开（HTTP 404）");
            }
            if (code == 403 || code == 429) {
                // 匿名调用每小时 60 次，超了就是这个码。过一会儿自己会好，所以要说清楚
                // 是限流而不是坏了。
                return Release.problem("GitHub 限流了，过一会儿再试（HTTP " + code + "）");
            }
            if (code != 200) {
                return Release.problem("HTTP " + code);
            }

            JSONArray list = new JSONArray(read(link.getInputStream()));
            if (list.length() == 0) {
                // 200 + 空数组：仓库是通的，只是还没发过发行版。这和「失败」是两件事。
                return Release.EMPTY;
            }
            JSONObject latest = list.getJSONObject(0);
            String tag = latest.optString("tag_name", "").trim();
            String url = latest.optString("html_url", "").trim();
            if (tag.isEmpty()) {
                return Release.problem("发行版里没有 tag");
            }
            return Release.of(tag, url.isEmpty() ? REPO + "/releases" : url);
        } catch (Exception failed) {
            // 断网、DNS、TLS、JSON 坏掉……对人来说都是「这次没查成」，原文附在后面给
            // 排障用。异常类名比 getMessage 更可靠：后者常常是 null。
            String why = failed.getMessage();
            return Release.problem(failed.getClass().getSimpleName()
                    + (why == null ? "" : ": " + why));
        } finally {
            if (link != null) {
                link.disconnect();
            }
        }
    }

    /** 整段读成字符串。UTF-8 写死：GitHub 的接口只给 UTF-8。 */
    private static String read(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * {@code tag} 是不是比 {@code current} 新。
     *
     * <p>只比数字段，不管前缀：仓库的 tag 可能叫 {@code v1.2.0}，也可能叫 {@code 1.2.0}，
     * 两种都有人用，而 {@code versionName} 永远不带 v。用正则抓出第一串「数字.数字.数字」，
     * 缺的段按 0 补——{@code 1.2} 等于 {@code 1.2.0}。
     *
     * <p>抓不到数字（比如 tag 叫 {@code nightly}）就返回 false，页面上落成「已是最新」。
     * 这是一个保守的选择：宁可漏报一次，也不要把一个说不出版本号的东西说成「比你现在的新」。
     */
    private static boolean newerThan(String tag, String current) {
        int[] theirs = numbers(tag);
        int[] ours = numbers(current);
        if (theirs == null || ours == null) {
            return false;
        }
        int length = Math.max(theirs.length, ours.length);
        for (int i = 0; i < length; i++) {
            int left = i < theirs.length ? theirs[i] : 0;
            int right = i < ours.length ? ours[i] : 0;
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    /** 字符串里第一串 {@code 1.2.3} 形式的数字；没有就是 {@code null}。 */
    private static int[] numbers(String text) {
        Matcher matcher = Pattern.compile("\\d+(?:\\.\\d+)*").matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return null;
        }
        String[] parts = matcher.group().split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException overflow) {
                // 段长到装不进 int（20 位以上的「版本号」）：当作没有版本号处理。
                return null;
            }
        }
        return out;
    }

    /** 查回来的那一条。三个字段合起来只有四种状态，见 {@link #settle}。 */
    private static final class Release {
        /** 没有发行版（200 + 空数组）。 */
        static final Release EMPTY = new Release(null, null, null);

        final String tag;
        final String url;
        final String problem;

        private Release(String tag, String url, String problem) {
            this.tag = tag;
            this.url = url;
            this.problem = problem;
        }

        static Release of(String tag, String url) {
            return new Release(tag, url, null);
        }

        static Release problem(String why) {
            return new Release(null, null, why);
        }
    }

    /** 打开一个链接。没有浏览器时不能静默——把地址本身交出来，人还能抄走。 */
    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException noBrowser) {
            new AlertDialog.Builder(this)
                    .setTitle("没有能打开链接的应用")
                    .setMessage(url)
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    /**
     * 打开许可子页。
     *
     * <p>标题跟着一起递过去，而不是让子页自己按常量查表：那份表会逼着子页认识「GPL」和
     * 「CRATES」这两个名字，意味着以后加第三份许可要改两个文件。现在子页只认「一份 raw
     * 资源和它的标题」。
     */
    private void showDoc(int doc, String label) {
        startActivity(new Intent(this, LicenseActivity.class)
                .putExtra(LicenseActivity.EXTRA_DOC, doc)
                .putExtra(LicenseActivity.EXTRA_LABEL, label));
    }

    /** 一行的样子：小字说是哪一项，正文说是什么。值可能很长，所以竖着排。 */
    private View fact(String name, CharSequence value) {
        LinearLayout box = chrome.column();
        box.addView(chrome.caption(name));
        TextView text = chrome.text(value.toString(), 15, false, S.text);
        text.setLineSpacing(chrome.dp(1), 1f);
        text.setPadding(0, chrome.dp(2), 0, 0);
        box.addView(text);
        return box;
    }

    private String version() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.PackageInfoFlags.of(0));
            return info.versionName + "（versionCode " + info.getLongVersionCode() + "）";
        } catch (PackageManager.NameNotFoundException impossible) {
            // 自己的包名查不到，只可能是这个 Activity 不在这台机器上。
            return "读不出来";
        }
    }

    /** 只有 {@code versionName} 的版本号，给「检测更新」比较用。读不到就是空串。 */
    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.PackageInfoFlags.of(0));
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException impossible) {
            return "";
        }
    }

    /**
     * 核心库报的 ABI，以及它是否就是外壳认识的那个。
     *
     * <p>这里报不一致而不是报错退出：不认识 ABI 的库仍然可能收发正常，把一个还能用的应用
     * 挡住，比让人看着一行「核心 ABI 3（外壳认 2）」自己决定要命得多。
     */
    private String core() {
        int abi;
        try {
            abi = Native.abiVersion();
        } catch (UnsatisfiedLinkError missing) {
            return "库没有加载（" + missing.getMessage() + "）";
        }
        String agrees = abi == EXPECTED_ABI ? "与外壳一致" : "外壳期望 " + EXPECTED_ABI;
        return "ABI " + abi + "（" + agrees + "）· " + System.mapLibraryName("opairdrop_ffi");
    }
}
