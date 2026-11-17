package com.oshare.airdrop;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.WindowInsets;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 许可全文：关于页那两行点开之后到的地方。
 *
 * <p># 为什么是一个「能显示两份文档」的页面，而不是两个页面
 *
 * <p>这两份文档除了标题和内容以外没有别的区别：都是一份 raw 里的纯文本、可选中、一屏放
 * 不下、要能返回。写两个 Activity 就是把「滚动 + 避让状态栏 + 返回」抄两遍，然后等着其中
 * 一份在某次改动里悄悄少掉一句 {@code setClipToPadding}。
 *
 * <p>所以这个页面只认两件事：哪一份 raw、叫什么名字。调用方递进来（见 {@code
 * AboutActivity.showDoc}），它自己不认识「GPL」「CRATES」这些业务词——加第三份许可时只
 * 需要在这里多一个常量，别处一行都不用动。
 *
 * <p># 两份文档的排版不一样，这是量出来的，不是猜的
 *
 * <p>{@code licenses.txt} 里最长的一行 60 列（生成器压的），{@code LICENSE} 最长 77 列。
 * 这块屏宽 360dp，减去页面 18dp × 2、卡片 18dp × 2，文字可用 288dp。正文那一档字号是 13sp，
 * 等宽体一个字符约 0.6em ≈ 7.8dp，于是 60 列要 468dp、77 列要 600dp——两份都放不下。
 *
 * <p>放不下之后的两种处理，对这两种文本的后果完全不同：
 *
 * <ul>
 *   <li>表：一折行，crate 名和它的许可就分到两行上，三列对齐当场失效，这张表也就没用了。
 *       所以它<b>不折</b>，横向拖。</li>
 *   <li>散文：换行不承载任何含义。GPL 的条款编号、段落、缩进都在字符里，折行不会少一个字。
 *       所以它<b>折</b>，而且换比例字体——等宽字折出来的折点落在固定列上，屏幕上会出现大
 *       量只剩一个词的残行（“free”、“by”、“you”），那是在读一份被切碎的文件。</li>
 * </ul>
 *
 * <p># 光换比例字体还不够：原文的 77 列硬换行得先拆掉
 *
 * <p>上一版只换了字体，截图里仍然是「每段两行、第二行只剩一两个词」。原因不在字体，在
 * {@code LICENSE} 本身：它是按 77 列排的老式纯文本，每段内部的换行是<b>排版</b>留下的，
 * 不是句子之间真的断开。屏幕只放得下约 44 个字符，于是原文的每个软换行都被当成硬换行照
 * 收，一段就被切成了两截。
 *
 * <p>所以显示前要做一次「归段」：按行首缩进判断哪一行是新段、哪一行是上一段的续行，同一
 * 段内部的换行折成一个空格，段落之间的空行保留。缩进的含义是量出来的（{@code LICENSE} 里
 * 一共只有 0/1/2/3/4/12/20/21/23/28 这几种首字符缩进）：
 *
 * <ul>
 *   <li>0：续行。GPL 的老式排法是段首缩进、续行顶格，所以顶格的行几乎总是上一段的尾巴。
 *       例外是紧跟空行的顶格行——那是新段的开头（全文 16 处，例如条款 2 里的「Activities
 *       other than copying…」）。</li>
 *   <li>1：开头那四行版权声明，自成一格。</li>
 *   <li>2：正文段的段首，也包含 {@code 0.} … {@code 12.} 这十二个条款的标题行。</li>
 *   <li>4：{@code a) b) c)} 列表项，以及「How to Apply」里那几块示例通知文本。</li>
 *   <li>3 与 ≥12：居中的标题行（{@code GNU GENERAL PUBLIC LICENSE}、{@code Preamble}、
 *       {@code NO WARRANTY} 等）。</li>
 * </ul>
 *
 * <p>标题行单独成段并居中加粗——原文就是用缩进把它们顶到中间的，重排后照做，读者才分得清
 * 哪里是条款、哪里是章名。
 *
 * <p><b>原文一个字都没改。</b> {@code res/raw/license_gpl.txt} 是 {@code LICENSE} 的逐字节
 * 副本，GPL 要求随二进制分发时必须附上原样文本；重排只发生在 {@code read()} 之后、上屏之
 * 前，是显示层的事。改的只有换行位置，字句、缩进所表达的层级、条款编号全部保留。
 *
 * <p># 为什么文字可以长按选中
 *
 * <p>许可全文的用处之一就是被人复制走（贴进 issue、贴进发行说明）。禁掉选择等于让人对着
 * 屏幕抄。
 */
public final class LicenseActivity extends Activity {
    /** 这份文档是哪个 raw。值只要在这个类里唯一即可，不是资源 id。 */
    static final String EXTRA_DOC = "doc";
    /** 文档的标题，由调用方给。 */
    static final String EXTRA_LABEL = "label";

    /** 本项目自己声明的许可：仓库根的 {@code LICENSE}，GPL-2.0。 */
    static final int GPL = 1;
    /** 随包分发的第三方 crate 许可清单，由 {@code cargo metadata} 生成。 */
    static final int CRATES = 2;

    /**
     * 缩进到这个宽度以上的行，是居中的章名而不是正文。
     *
     * <p>12 是从原文量出来的下界（实际出现 12/20/21/23/28，都是制表符凑出来的居中），缩进
     * 只有 3 的那一行也是标题（{@code TERMS AND CONDITIONS FOR COPYING…}），单独用
     * {@link #TERMS_HEADING_INDENT} 认一下。
     */
    private static final int HEADING_INDENT = 12;
    /** 见 {@link #HEADING_INDENT}：缩进 3 的那一行是「条款正文」的章名。 */
    private static final int TERMS_HEADING_INDENT = 3;

    /**
     * 两份许可全文共用的正文字号。颜色也共用，写死 {@code S.text}。
     *
     * <p>13 是和这一页其余正文（{@code Chrome.text} 的行值）同一档，不是另调的数。表原先用
     * 10sp 是为了在一屏里多塞几列，那笔账算的是「看得见多少列」，漏算的是「这是两份同样要
     * 被人读完的文件」——理由写在 {@link #textCard} 的注释里。
     */
    private static final float BODY_SP = 13f;

    private Chrome chrome;
    private Scheme S;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        S = Scheme.of(mode != Configuration.UI_MODE_NIGHT_YES);
        chrome = new Chrome(this, S);

        int doc = getIntent().getIntExtra(EXTRA_DOC, GPL);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label == null || label.isEmpty()) {
            label = "许可全文";
        }

        LinearLayout body = chrome.pageBody();
        body.addView(back());
        body.addView(title(doc, label));
        body.addView(textCard(doc), chrome.margins(16, 0));

        ScrollView scroll = chrome.scroller(body);
        // 和关于页同一条理由：这一页没有顶部霜面，内容不许滚到状态栏底下，否则字会直接压在
        // 时间与电量上。裁在状态栏下沿，滚出去的才真的看不见。
        scroll.setClipToPadding(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
            view.setPadding(0, bars.top + chrome.dp(4), 0, bars.bottom + chrome.dp(24));
            return insets;
        });
        setContentView(scroll);
    }

    private View back() {
        TextView back = chrome.text("‹ 返回", 15, true, S.accent);
        back.setPadding(0, chrome.dp(12), 0, chrome.dp(12));
        back.setOnClickListener(view -> finish());
        chrome.pressable(back);
        return back;
    }

    /**
     * 标题 + 一句说明这份文档被怎么对待过。
     *
     * <p>说明必须说准，而且两份文档说得不一样，因为它们确实被处理得不一样：散文那边重排了
     * 换行（原文 77 列硬折，屏幕只放 44 个字符），表这边一个字没动、只是允许横向拖。写同一
     * 句话会变成假话——「原文未做改动」在散文页错，「重排过换行」在表页错。
     */
    private View title(int doc, String label) {
        LinearLayout box = chrome.column();
        box.addView(chrome.text(label, 22, true, S.text));
        TextView sub = chrome.caption(doc == CRATES
                ? "随包分发的原文，未做改动；一行太长，可以左右拖。"
                : "随包分发的原文，仅按屏宽重排换行，字句未改。");
        sub.setPadding(0, chrome.dp(4), 0, 0);
        box.addView(sub);
        return box;
    }

    /**
     * 一整张卡里装全文。表的排版与正文的排版不同，理由见类注释里那段「量出来的」。
     *
     * <p>表外面那层 {@link HorizontalScrollView} 只做一件事：让 {@code TextView} 拿到一个
     * 不受屏宽约束的宽度，于是 60 列排在同一行上，三列仍然对齐。它不折行、不缩字号，代价是
     * 右边有一小截要拖过去才看得见——这比一张散掉的表便宜。
     *
     * <p>横向滚动条关掉：这一页主方向是竖着读，屏幕底部突然出现一条横条会让人以为整页都能
     * 左右拖。手指在表上左右动的时候它自己会走。
     *
     * <p># 两份文档的字号与颜色为什么必须一样
     *
     * <p>这两页是同一个页面（同一个 Activity、同一个标题结构、同一个返回），从同一个板块的
     * 上下两行点进来。给人看到的却是两套字：表那边 10sp 灰字，正文这边 13sp 正常色。人不会
     * 把它读成「这是两种排版」，会读成「许可也分三六九等」——而这两份文档的重量恰好相反：
     * 表里那些 crate 的 MIT / Apache-2.0 条款是随包分发的法定义务，一个字都不能少；GPL 是本
     * 项目自己选的那份。没有理由让义务更重的那份用更小的字、更淡的色。
     *
     * <p>它们看起来「次要」，其实来自一个技术上的副作用：表要在一屏里塞下更多列，所以压了小
     * 字号和灰度。<b>那是排版的因，不该长成语义上的果。</b>现在两边同 13sp、同 {@code S.text}，
     * 代价是表要横着多拖一截（60 列 × 约 7.8dp ≈ 468dp，屏上能看见约 37 列）。对齐没受影响：
     * 表仍然不折行，多出来的部分在右边，拖过去就是。
     *
     * <p>正文那边是比例字体 + 13sp，折行点交给断词规则；行距 4dp，因为 13sp 的行贴在一起会
     * 连成一片，读两行就串行。
     */
    private View textCard(int doc) {
        boolean table = doc == CRATES;
        LinearLayout card = chrome.card();
        TextView text = chrome.text("", BODY_SP, false, S.text);
        if (table) {
            // 表：原文原样交给 TextView，行是谁就是谁，一格都不许并。
            text.setText(read(doc));
            text.setTypeface(Typeface.MONOSPACE);
            // 行距 3dp。10sp 的时候是 2，字号涨到 13 之后同一个 2 就贴住了——行距是相对字高
            // 的事，字号一动它就得跟着动。仍然比正文那边（4dp）紧一点：表是「一行一条」，
            // 松成散文那样反而读不出哪几行属于同一个 crate。
            text.setLineSpacing(chrome.dp(3), 1f);
            HorizontalScrollView pan = new HorizontalScrollView(this);
            pan.setHorizontalScrollBarEnabled(false);
            pan.addView(text);
            card.addView(pan);
        } else {
            // 散文：先归段再上屏。理由见类注释「光换比例字体还不够」那节。
            text.setText(paragraphs(read(doc)));
            text.setLineSpacing(chrome.dp(4), 1f);
            // 章名用的是 AlignmentSpan（居中），它只在 TextView 有确定宽度时才有意义。
            // WRAP_CONTENT 下宽度跟着最长一行走，居中等于没居。
            text.setLayoutParams(
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(text);
        }
        // 选中在两种排版下都要：许可全文的用处之一就是被复制走。
        text.setTextIsSelectable(true);
        return card;
    }

    /**
     * 把按列硬折过的纯文本还原成段落，交给 TextView 按屏宽自己折。
     *
     * <p>分两步：先按空行切块，再决定这一块怎么合。规则全部来自对 {@code LICENSE} 的统计，
     * 不是想象：
     *
     * <ol>
     *   <li><b>空行断块。</b>原文就是用它分段的。而且这份文件里段首缩进 2、续行顶格，段落
     *       内部不会出现「缩进又变大」的行——所以按空行切就够，不需要再猜哪一行是续行。</li>
     *   <li><b>标题行</b>（缩进 3 或 ≥12）单独成段、居中加粗。它们被制表符顶到中间，原文
     *       就是这个意思，照做。</li>
     *   <li><b>块内换行折成空格</b>，包括缩进 4 的 {@code a) b) c)} 列表项和示例通知文本——
     *       为什么示例也不保留行断，见 {@link #write}。</li>
     * </ol>
     *
     * <p>段内的单个空格不碰：原文有 {@code "program.  It is"} 这种双空格，{@link
     * String#trim()} 只砍首尾，行内原样保留，只在行与行之间补一个。
     */
    private CharSequence paragraphs(String raw) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        List<String> block = new ArrayList<>();

        for (String line : raw.split("\n", -1)) {
            if (line.trim().isEmpty()) {
                // 空行：块到此为止。连续两个空行只切一次，因为 block 空了就没什么可写的。
                write(out, block);
                continue;
            }
            int indent = indentOf(line);
            if (indent == TERMS_HEADING_INDENT || indent >= HEADING_INDENT) {
                // 标题自己一格：前面那一段先落地，标题单独一行，后面的正文另起一段。
                write(out, block);
                block.add(line);
                writeHeading(out, block);
                continue;
            }
            block.add(line);
        }
        write(out, block);
        return out;
    }

    /**
     * 把攒好的一块合并成一段接进输出，然后清空。{@code block} 为空时什么都不做。
     *
     * <p>块内一律折成一个空格，包括「How to Apply」里那几段示例通知文本。本来想给示例
     * 保留原换行（版权头、启动画面各占一行，那是样板的一部分），装到机器上一看反而更糟：
     * 示例单行 68 列，屏幕一行只放 44 个字符，于是「保留的换行」和「TextView 自己的折行」
     * 混在一起，屏幕上出现 {@code author}、{@code redistribute it} 这种半行——比合并还难
     * 读。样板感丢一点，换一屏干净的字，值。块与块之间本来就有空行，示例仍是独立段落。
     */
    private void write(SpannableStringBuilder out, List<String> block) {
        if (block.isEmpty()) {
            return;
        }
        StringBuilder para = new StringBuilder();
        for (String line : block) {
            if (para.length() > 0) {
                para.append(' ');
            }
            para.append(line.trim());
        }
        block.clear();
        append(out, para, false);
    }

    /** 标题行（块里可能有两行，例如 {@code GNU GENERAL PUBLIC LICENSE} 与版本号）。 */
    private void writeHeading(SpannableStringBuilder out, List<String> block) {
        for (String line : block) {
            append(out, new StringBuilder(line.trim()), true);
        }
        block.clear();
    }

    /**
     * 接一段到输出，居中标题顺手打上两个 span。
     *
     * <p>段间用空行（{@code "\n\n"}）而不是靠 {@code setLineSpacing} 拉开——行距是一行之内
     * 的呼吸，段距是段落之间的距离，两件事挤在一个参数里，下次调其中一个必然弄坏另一个。
     */
    private void append(SpannableStringBuilder out, CharSequence para, boolean heading) {
        int start = out.length();
        out.append(para);
        int end = out.length();
        if (heading) {
            out.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new StyleSpan(Typeface.BOLD),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        out.append("\n\n");
    }

    /**
     * 行首缩进量，制表符按 8 列展开。
     *
     * <p>GPL 原文用制表符把标题顶到中间（{@code "\t\t    GNU GENERAL PUBLIC LICENSE"}），
     * 按 8 展开之后就是 20 列。不展开的话一个 {@code \t} 只算 1 列，标题会被当成正文并进上
     * 一段。
     */
    private static int indentOf(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += 8 - (width % 8);
            } else {
                break;
            }
        }
        return width;
    }

    /**
     * 读 raw。
     *
     * <p>没做懒加载：这一页存在的唯一理由就是显示这份文件，进来必读，藏一层「按下才读」只
     * 会让屏幕先空一拍。读失败也不抛——那说明这个 APK 里缺了资源，页面上写出来比崩掉有用。
     */
    private String read(int doc) {
        int id = doc == CRATES ? R.raw.licenses : R.raw.license_gpl;
        try (InputStream in = getResources().openRawResource(id)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failed) {
            return "读不出来：" + failed;
        }
    }
}
