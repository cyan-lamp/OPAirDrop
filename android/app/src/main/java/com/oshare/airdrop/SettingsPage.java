package com.oshare.airdrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置页：那些「值」——名字、目录、上限、时长。
 *
 * <p># 它画表，它不认识表
 *
 * <p>这一页不点名任何一个设置键。{@link SettingsScreen#open} 把库里的目录和存下来的值配成
 * 一对，这里只是把拿到的分组走一遍：Rust 的表里加一行，这里就多一行，控件也对。目录存在的
 * 全部理由就是这个，而一旦这个文件里出现了一个键，它就不成立了。
 *
 * <p># 开关不在这里
 *
 * <p>开关在「功能」页，因为它们拨一下就生效，而这里的东西要按保存。混在一起会让人拨完开关
 * 去找保存按钮，或者按了保存以为开关也一起存了。
 *
 * <p># 错误出现在引起它的地方
 *
 * <p>一个字段在失去焦点时查一次，按保存时再查一次，提示写在字段下面而不是弹一个「一个值超出
 * 范围」的 toast：在十几行的一页里，toast 什么也没说。保存是全有或全无——一个字段不合格，就
 * 一个字节都不写，于是保存后看到的这一页，就是下次启动会加载的那一页。
 *
 * <p># 浮层结构
 *
 * <p>表单铺满整个内容区，保存/还原那一行浮在它上面。给根视图加内边距会把滚动区截在胶囊上方，
 * 霜面就永远只盖着一片纯色；所以让列表从按钮行底下滚过去，玻璃底下才有东西可以模糊。
 */
final class SettingsPage {
    /** 浮动按钮行的高度。定死它，滚动区的下内边距才能事先算出来。 */
    private static final int BAR_HEIGHT = 64;
    /** 按钮行占掉的那条带，交给外壳加到滚动区的下内边距里。 */
    private static final int BAR_INSET = 66;

    /** 控件属于某一行，所以拒绝可以写在引起它的那个字段下面。 */
    private static final class Row {
        final SettingsScreen.Setting setting;
        TextView problem;
        TextView reading;
        EditText field;
        TextView[] dots;
        View[] options;

        Row(SettingsScreen.Setting setting) {
            this.setting = setting;
        }
    }

    /**
     * 页面根。它自己声明要多留一条底部空间：浮动按钮行压在滚动区上，外壳算内边距时不知道
     * 这件事，所以由它说出来。
     */
    private final class Shell extends FrameLayout implements Chrome.BottomInset {
        Shell() {
            super(host);
        }

        @Override
        public int extraBottomDp() {
            return BAR_INSET;
        }
    }

    private final Activity host;
    private final Chrome chrome;
    private final Scheme S;
    private final LinearLayout body;
    private final Map<String, Row> rows = new LinkedHashMap<>();

    /** 表读不出来时非空：那一刻没有可编辑的东西，只剩一条理由和一个删除按钮。 */
    private final Exception unreadable;
    private SettingsScreen screen;

    /**
     * 人是否已经按过保存。
     *
     * <p>在那之前，空着的一格是正在输入，不是错误；在那之后，每一次按键都重新查，改对了提示
     * 就当场消失。
     */
    private boolean attempted;

    /** 正在把模型写进控件，这时监听器不该往回写。 */
    private boolean applying;

    SettingsPage(Activity host, Chrome chrome) {
        this.host = host;
        this.chrome = chrome;
        this.S = chrome.scheme();
        this.body = chrome.pageBody();

        File file = SettingsFile.of(host);
        Exception failed = null;
        try {
            screen = SettingsScreen.open(file.toPath());
        } catch (IOException | RuntimeException cannotRead) {
            failed = cannotRead;
        }
        unreadable = failed;
        if (failed == null) {
            fill();
        }
    }

    View view() {
        if (unreadable != null) {
            return brokenPage(SettingsFile.of(host), unreadable);
        }
        Shell root = new Shell();
        BounceScrollView scroll = chrome.scroller(body);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout bar = buttonBar();
        final FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, chrome.dp(BAR_HEIGHT), Gravity.BOTTOM);
        barParams.leftMargin = chrome.dp(18);
        barParams.rightMargin = chrome.dp(18);
        barParams.bottomMargin = chrome.dp(22);
        root.addView(bar, barParams);

        // 按钮行要落在导航胶囊上方，而导航胶囊的高度只有系统栏内边距知道
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            barParams.bottomMargin = bottom + chrome.dp(90);
            bar.setLayoutParams(barParams);
            return insets;
        });
        // 内边距是「派发」过来的，不是随时能读的属性：这一页第一次翻开时才建，那时窗口早派发
        // 完了，监听器不会被补叫，按钮行就会一直停在构造时的 22dp——正好压在导航胶囊上，还
        // 被胶囊的霜面模糊掉一半。接上窗口时主动要一次。
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attached) {
                attached.requestApplyInsets();
            }

            @Override
            public void onViewDetachedFromWindow(View detached) {
            }
        });
        return root;
    }

    /** 有没有改了但没保存的东西。切页和返回时问它。 */
    boolean dirty() {
        return screen != null && screen.dirty();
    }

    /**
     * 要离开这一页之前问一句，前提是真有改动。
     *
     * <p>写在这里而不是让外壳自己判断：知不知道有未保存的改动，是这一页的事。
     */
    void confirmLeave(Runnable leave) {
        if (!dirty()) {
            leave.run();
            return;
        }
        ask("放弃未保存的改动？", "这一页的改动还没有写入。", "放弃", leave);
    }

    /**
     * 设置读不出来时这一页的样子。
     *
     * <p>给一个删除文件的按钮，因为解析不了的文件只有这一个修法；按钮上写出是哪个文件，因为
     * 人正要失去他设过的一切，而「重置」不会告诉他失去的是什么。
     */
    private View brokenPage(File file, Exception failed) {
        LinearLayout page = chrome.pageBody();
        page.addView(chrome.sectionTitle("设置"));
        LinearLayout card = chrome.card();
        card.addView(chrome.text("设置读不出来", 16, true, S.text));
        TextView why = chrome.text(String.valueOf(failed.getMessage()), 13, false, S.red);
        why.setPadding(0, chrome.dp(6), 0, chrome.dp(6));
        card.addView(why);
        card.addView(chrome.caption(file.getAbsolutePath()));
        page.addView(card, chrome.margins(0, 16));

        TextView delete = chrome.mainButton("删除该文件并重试", view -> {
            if (file.delete() || !file.exists()) {
                host.recreate();
            } else {
                chrome.say("删不掉：" + file.getAbsolutePath(), S.red);
            }
        });
        page.addView(delete, chrome.margins(0, 0));
        return chrome.scroller(page);
    }

    /**
     * 两个不属于任何一行的动作：丢掉全部改动，或者把这一页写下去。
     *
     * <p>它是一块浮起来的玻璃状面板：和卡片同色、圆角 22、带投影。白色实底在深色模式下会是
     * 一条刺眼的白带，而这里没有一条白带是有理由的。
     */
    private FrameLayout buttonBar() {
        FrameLayout bar = new FrameLayout(host);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(S.card);
        bg.setCornerRadius(chrome.dp(22));
        bar.setBackground(bg);
        // 投影来自圆角轮廓；被用来裁剪的轮廓不再投影，而子视图自己会裁圆角
        bar.setClipToOutline(false);
        bar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), chrome.dp(22));
            }
        });
        bar.setElevation(chrome.dp(10));

        LinearLayout line = chrome.row();
        line.setPadding(chrome.dp(10), chrome.dp(10), chrome.dp(10), chrome.dp(10));

        TextView revert = chrome.ghostButton("还原", view -> {
            if (!screen.dirty()) {
                chrome.say("没有改动", S.textDim);
                return;
            }
            ask("放弃全部改动？", "回到上次保存的内容。", "放弃", () -> {
                screen.revert();
                attempted = false;
                apply();
                clearProblems();
            });
        });
        TextView save = chrome.mainButton("保存", view -> save());

        LinearLayout.LayoutParams revertLp = chrome.mm(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        revertLp.rightMargin = chrome.dp(10);
        line.addView(revert, revertLp);
        line.addView(save, chrome.mm(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        bar.addView(line, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return bar;
    }

    /** 画出库报告的每一个分组，按它给的顺序。 */
    private void fill() {
        body.removeAllViews();
        rows.clear();

        for (SettingsScreen.Section section : screen.sections()) {
            boolean any = false;
            for (SettingsScreen.Setting setting : section.settings()) {
                if (setting.control() != SettingsScreen.Control.SWITCH) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                continue;
            }
            body.addView(chrome.sectionTitle(section.title()));
            String note = section.note();
            if (note != null && !note.isEmpty()) {
                TextView hint = chrome.caption(note);
                hint.setPadding(chrome.dp(6), 0, chrome.dp(6), chrome.dp(8));
                body.addView(hint);
            }
            body.addView(groupCard(section), chrome.margins(0, 16));
        }

        body.addView(chrome.sectionTitle("关于"), chrome.margins(4, 0));
        body.addView(aboutCard(), chrome.margins(0, 16));

        // 这一页写的是哪个文件，放在最后：「我的设置去哪了」是一个关于路径的问题，而这个应用
        // 里没有第二个地方能读到路径。
        TextView footer = chrome.caption(
                rows.size() + " 行 → " + SettingsFile.of(host).getAbsolutePath());
        footer.setPadding(chrome.dp(6), chrome.dp(4), chrome.dp(6), 0);
        body.addView(footer);
    }

    /** 一组一行卡的集合：行与行之间是整幅分隔线，卡的两端圆角由卡片自己给。 */
    private View groupCard(SettingsScreen.Section section) {
        LinearLayout card = chrome.card();
        boolean first = true;
        for (SettingsScreen.Setting setting : section.settings()) {
            if (setting.control() == SettingsScreen.Control.SWITCH) {
                // 开关在「功能」页：它们拨一下就写，和这一页的保存按钮不是同一种操作。
                continue;
            }
            if (!first) {
                card.addView(chrome.divider(), chrome.fixedDivider(0, 0));
            }
            card.addView(rowView(setting), chrome.margins(first ? 0 : 16, 6));
            first = false;
        }
        return card;
    }

    /** 一行：名字、它是干什么的、控件，以及它被拒时的理由。 */
    private View rowView(SettingsScreen.Setting setting) {
        Row row = new Row(setting);
        rows.put(setting.key(), row);

        LinearLayout box = chrome.column();

        TextView name = chrome.text(setting.title(), 15, true, S.text);
        name.setOnLongClickListener(view -> {
            askReset(row);
            return true;
        });
        box.addView(name);

        if (!setting.summary().isEmpty()) {
            TextView summary = chrome.caption(setting.summary());
            summary.setPadding(0, chrome.dp(3), 0, 0);
            box.addView(summary);
        }

        switch (setting.control()) {
            case NUMBER:
                row.field = numberField(row);
                box.addView(row.field, chrome.margins(10, 2));
                row.reading = chrome.caption("");
                box.addView(row.reading);
                break;
            case CHOICE:
                choiceOptions(row);
                for (int i = 0; i < row.options.length; i++) {
                    box.addView(row.options[i], chrome.margins(i == 0 ? 10 : 8, 0));
                }
                break;
            case TEXT:
                row.field = textField(row);
                box.addView(row.field, chrome.margins(10, 2));
                break;
            default:
                break;
        }

        row.problem = chrome.text("", 12.5f, false, S.red);
        row.problem.setVisibility(View.GONE);
        box.addView(row.problem, chrome.margins(2, 0));
        return box;
    }

    private EditText numberField(Row row) {
        EditText field = plainField(row);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        return field;
    }

    private EditText textField(Row row) {
        EditText field = plainField(row);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        if (row.setting.maxLength() > 0) {
            field.setHint("最多 " + row.setting.maxLength() + " 个字符");
        }
        return field;
    }

    /**
     * 一个接到模型上的输入框。
     *
     * <p>数字和文本共用，因为接线是一样的：每一次按键都写进模型，失焦时查一次。写两份就会
     * 有两次机会让其中一份忘了 {@code applying} 这层保护，然后自己触发自己。
     */
    private EditText plainField(Row row) {
        EditText field = chrome.darkInput("");
        field.setText(row.setting.value());
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                if (applying) {
                    return;
                }
                edit(row, text.toString());
            }
        });
        field.setOnFocusChangeListener((view, focused) -> {
            if (!focused) {
                showProblem(row);
            }
        });
        return field;
    }

    /**
     * 一组选项，legacy 那个「○ / ●」的选法。
     *
     * <p>不用下拉框：这些选项各有各的后果（日志记到多细），排开成一行一行才看得见另外几个
     * 是什么；下拉框把选项藏在一次点击之后，选完也不告诉你还有别的。
     *
     * <p>{@code row.options} 在返回之前就填好：{@link #highlightChoice} 是按行下标改背景的，
     * 数组晚一步挂到行上，高亮那一刻就没东西可改。
     */
    private void choiceOptions(Row row) {
        List<String> options = row.setting.options();
        row.dots = new TextView[options.size()];
        row.options = new View[options.size()];
        for (int i = 0; i < options.size(); i++) {
            final String value = options.get(i);
            LinearLayout option = chrome.row();
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(chrome.dp(16));
            bg.setColor(S.cardHi);
            option.setBackground(bg);
            option.setPadding(chrome.dp(14), chrome.dp(12), chrome.dp(14), chrome.dp(12));
            option.setOnClickListener(view -> pick(row, value));
            chrome.pressable(option);

            TextView dot = chrome.text("○", 16, true, S.textDim);
            row.dots[i] = dot;
            option.addView(dot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView label = chrome.text(value, 14.5f, true, S.text);
            label.setPadding(chrome.dp(12), 0, 0, 0);
            option.addView(label, chrome.mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.options[i] = option;
        }
        highlightChoice(row);
    }

    /** 选中那一项：填实的圆点 + 主色描边。 */
    private void highlightChoice(Row row) {
        if (row.dots == null || row.options == null) {
            return;
        }
        List<String> options = row.setting.options();
        for (int i = 0; i < row.dots.length; i++) {
            boolean selected = options.get(i).equals(row.setting.value());
            row.dots[i].setText(selected ? "●" : "○");
            row.dots[i].setTextColor(selected ? S.accent : S.textDim);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(chrome.dp(16));
            bg.setColor(selected ? 0x143B82F6 : S.cardHi);
            if (selected) {
                bg.setStroke(chrome.dp(1), S.accent);
            }
            row.options[i].setBackground(bg);
        }
    }

    private void pick(Row row, String value) {
        screen.set(row.setting.key(), value);
        highlightChoice(row);
        if (attempted) {
            showProblem(row);
        }
    }

    /** 记下一次编辑，并且只重画这次编辑能改到的东西。 */
    private void edit(Row row, String text) {
        screen.set(row.setting.key(), text);
        showReading(row);
        if (attempted) {
            showProblem(row);
        }
    }

    /** 数字的说法，放在数字旁边：{@code 8589934592} 旁边写「8 GiB」。 */
    private void showReading(Row row) {
        if (row.reading == null) {
            return;
        }
        String reading = row.setting.reading();
        String range = row.setting.range();
        StringBuilder line = new StringBuilder();
        if (!reading.isEmpty()) {
            line.append(reading);
        }
        if (!range.isEmpty()) {
            if (line.length() > 0) {
                line.append(" · ");
            }
            line.append("范围 ").append(range);
        }
        row.reading.setText(line);
    }

    private void showProblem(Row row) {
        String problem = row.setting.problem();
        if (problem == null) {
            row.problem.setVisibility(View.GONE);
        } else {
            row.problem.setText(problem);
            row.problem.setVisibility(View.VISIBLE);
        }
    }

    private void clearProblems() {
        for (Row row : rows.values()) {
            row.problem.setVisibility(View.GONE);
        }
    }

    private void apply() {
        applying = true;
        try {
            for (Row row : rows.values()) {
                SettingsScreen.Setting setting = row.setting;
                if (row.field != null) {
                    row.field.setText(setting.value());
                }
                highlightChoice(row);
                showReading(row);
            }
        } finally {
            applying = false;
        }
    }

    private void save() {
        attempted = true;
        List<String> problems;
        try {
            problems = screen.save();
        } catch (IOException failed) {
            chrome.say("没保存成功：" + failed.getMessage(), S.red);
            return;
        }

        if (problems.isEmpty()) {
            // 库把值改写成了它自己的存法（去空白、标准化），所以控件要从它那里重画，而不是
            // 留着人刚敲的那份近似值。
            apply();
            clearProblems();
            chrome.say("已保存", S.green);
            return;
        }

        clearProblems();
        for (String problem : problems) {
            mark(problem);
        }
        chrome.say("有 " + problems.size() + " 项需要改正，一项都没写入", S.red);
    }

    /** 把一条拒绝（形如 {@code key: 说明}）写到它点名的那一行下面。 */
    private void mark(String problem) {
        int colon = problem.indexOf(": ");
        if (colon < 0) {
            // 没有键的消息意味着页面和库对一个谁都不认领的值有分歧——那是 bug 不是手误，所以
            // 整条照原样显示。
            chrome.say(problem, S.red);
            return;
        }
        Row row = rows.get(problem.substring(0, colon));
        if (row == null) {
            chrome.say(problem, S.red);
            return;
        }
        row.problem.setText(problem.substring(colon + 2));
        row.problem.setVisibility(View.VISIBLE);
    }

    /** 把一行放回表里的默认值，先说出那个值是什么。 */
    private void askReset(Row row) {
        ask(
                "恢复「" + row.setting.title() + "」的默认值？",
                "默认是 " + describe(row.setting) + "。",
                "恢复",
                () -> {
                    screen.reset(row.setting.key());
                    apply();
                    showProblem(row);
                });
    }

    /**
     * 一个值在对话框里的说法。
     *
     * <p>数字的存储形式是它最不可读的一面：问「要不要恢复成 8589934592」是一个没人答得上来
     * 的问题，所以默认值按输入框会显示的样子说出来。
     */
    private String describe(SettingsScreen.Setting setting) {
        String value = setting.defaultValue();
        String reading = setting.defaultReading();
        if (setting.control() != SettingsScreen.Control.NUMBER) {
            return value.isEmpty() ? "空" : value;
        }
        return reading.isEmpty() ? value : value + "（" + reading + "）";
    }

    private View aboutCard() {
        LinearLayout card = chrome.rowCard();
        LinearLayout row = chrome.featureRow(
                IconView.SHIELD, "关于 OPAirDrop", "版本、源代码与开源许可",
                chrome.text("›", 20, true, S.textDim));
        chrome.addRow(card, row, chrome.rowHeight());
        chrome.setRowAction(row, true, Scheme.CORNERS_ALL,
                () -> host.startActivity(new Intent(host, AboutActivity.class)));
        return card;
    }

    private void ask(String title, String message, String confirm, Runnable action) {
        new AlertDialog.Builder(host)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton(confirm, (dialog, which) -> action.run())
                .show();
    }
}
