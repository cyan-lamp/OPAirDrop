package com.oshare.airdrop;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 关于：这一版是什么、源码在哪、包里带着谁的许可。
 *
 * <p># 为什么单开一个 Activity
 *
 * <p>它是唯一一个「看」的页面——不拨开关，不改值，看完就走。挂在设置页下面当一个可展开的
 * 段落，会让人以为它是设置的一部分；做成独立页面，返回就是退出，和看一条通知的份量相当。
 *
 * <p># 许可全文为什么收起来
 *
 * <p>{@code res/raw/licenses.txt} 有二十五 KB，展开在页面中间会把上面三行真正有人要看的东西
 * （版本、包名、源码在哪）顶到屏幕外。折起来的那一按是有意为之的：它要让「这里确实有全文」
 * 是一句可以验证的话，而不是一句承诺。
 *
 * <p># 每一条都来自包本身
 *
 * <p>版本号读 {@link PackageInfo}，ABI 读 {@link Native#abiVersion}，许可读 raw 资源，
 * {@code licenses.txt} 由 {@code android/tools/licenses.py} 从 {@code cargo metadata} 生成。
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
    private static final int EXPECTED_ABI = 2;

    private Chrome chrome;
    private Scheme S;

    /** 许可全文，按下按钮时才有内容；收起来只是 GONE，那二十五 KB 留在里面不再读第二遍。 */
    private TextView licenses;
    /** 展开按钮，展开之后要改字。 */
    private TextView toggle;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        S = Scheme.of(mode != Configuration.UI_MODE_NIGHT_YES);
        chrome = new Chrome(this, S);

        LinearLayout body = chrome.pageBody();
        body.addView(back());
        body.addView(title());
        body.addView(headCard(), chrome.margins(0, 16));
        body.addView(chrome.sectionTitle("源码"));
        body.addView(sourceCard(), chrome.margins(0, 16));
        body.addView(chrome.sectionTitle("开源许可"));
        body.addView(licenseCard(), chrome.margins(0, 16));
        body.addView(footer());

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

    private View title() {
        LinearLayout box = chrome.column();
        box.addView(chrome.text("关于", 22, true, S.text));
        TextView sub = chrome.caption("OPAirDrop — 与 iPhone、Mac 互传的接收端");
        sub.setPadding(0, chrome.dp(4), 0, 0);
        box.addView(sub);
        return box;
    }

    /** 三行「这一版是什么」。一张卡里的三条，行间一条从卡片左缘起笔的线。 */
    private View headCard() {
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

    private View sourceCard() {
        LinearLayout card = chrome.card();
        card.addView(wrapped(
                "全部源码在一个仓库里：repo-OPAirDrop。界面是 android/，核心是 crates/，"
                        + "两者经 JNI 相连。本机是唯一副本，尚未推送到任何远端。"));
        TextView tree = chrome.text(
                "crates/airdrop    发现与会话\n"
                        + "crates/http       请求与上传\n"
                        + "crates/mdns       服务发现\n"
                        + "crates/settings   参数表（设置页画的就是它）\n"
                        + "android/app       这一屏，以及首页、功能、设置三页",
                12f, false, S.textDim);
        // 等宽：这张表靠空格对齐，比例字体一换行就散了
        tree.setTypeface(Typeface.MONOSPACE);
        tree.setLineSpacing(chrome.dp(2), 1f);
        tree.setPadding(0, chrome.dp(12), 0, 0);
        card.addView(tree);
        return card;
    }

    private View licenseCard() {
        LinearLayout card = chrome.card();
        card.addView(wrapped(
                "随包分发的 Rust crate 共 13 个，许可为 MIT、Apache-2.0、0BSD、Zlib、"
                        + "Unicode-3.0，没有 GPL 一类要求开源的传染性许可。"
                        + "Java 侧没有第三方 jar，只用 Android 框架。"));
        TextView note = chrome.caption("清单由 cargo metadata 生成，不是手写的。");
        note.setPadding(0, chrome.dp(8), 0, 0);
        card.addView(note);

        toggle = chrome.ghostButton("展开许可全文", view -> toggleLicenses());
        card.addView(toggle, chrome.margins(14, 0));

        // 等宽、不横拖、按卡片宽度换行。这三件事是一套的：
        //
        // 生成器把整份文本压在 60 列以内（licenses.py 的 WIDTH，超了它自己会报错退出），
        // 60 列正好是这张卡片在 10sp 下放得下的宽度，于是框线不断、crate 表的三列对齐，
        // 而正文段落里比这更长的那几句本来就是散文，换行不影响读。
        //
        // 反过来的做法试过：保留原文件的 78 列、改横拖。结果是整块内容四屏宽——有一段的
        // 原文就是一行 243 个字符——拖到右边时大多数行已经在屏外，整块看起来是空的。
        licenses = chrome.text("", 10f, false, S.textDim);
        licenses.setTypeface(Typeface.MONOSPACE);
        licenses.setLineSpacing(chrome.dp(2), 1f);
        licenses.setPadding(0, chrome.dp(12), 0, 0);
        licenses.setVisibility(View.GONE);
        card.addView(licenses);
        return card;
    }

    private View footer() {
        TextView footer = chrome.caption(
                "本项目自身尚未声明许可：仓库根没有 LICENSE 文件，按著作权法默认保留所有权利。");
        footer.setPadding(chrome.dp(2), chrome.dp(4), chrome.dp(2), 0);
        return footer;
    }

    /** 一段可换行的正文。行距比默认松一点，长段落才读得下去。 */
    private TextView wrapped(String text) {
        TextView view = chrome.text(text, 14, false, S.text);
        view.setLineSpacing(chrome.dp(3), 1f);
        return view;
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

    /**
     * 第一次按下才读那二十五 KB，第二次按下收起来。
     *
     * <p>懒读不只是省内存：不按下这一下，就没有任何理由把一份没人看的文件读进内存。按钮上的
     * 字跟着状态走，否则收起之后它还在说「展开」。
     */
    private void toggleLicenses() {
        if (licenses.getVisibility() == View.VISIBLE) {
            licenses.setVisibility(View.GONE);
            toggle.setText("展开许可全文");
            return;
        }
        if (licenses.getText().length() == 0) {
            licenses.setText(readLicenses());
        }
        licenses.setVisibility(View.VISIBLE);
        toggle.setText("收起许可全文");
    }

    private String readLicenses() {
        try (InputStream in = getResources().openRawResource(R.raw.licenses)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failed) {
            return "许可全文读不出来：" + failed;
        }
    }
}
