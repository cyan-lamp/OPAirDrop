package com.oshare.airdrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * <p># 为什么保存按钮不在页脚
 *
 * <p>它原来是一条浮在整页底部的玻璃横条。问题不是它不好看，是它**压着内容**：这一页最后剩下
 * 的两块正好是「诊断」和「关于」，而那条横条浮在底部时，关于那一行和页脚的路径被它盖住一半——
 * 一个常年看不全的收尾，和没有一个收尾是一样的。
 *
 * <p>撤掉之后它当然还要在，只是搬到了它真正管的东西下面：诊断板块的卡下方。这一页现在只剩诊断
 * 有可编辑的值（发现、发送、接收、设备四块都移到功能页了），所以一颗「保存」写在哪儿都只有一
 * 个作用对象，而写在它旁边，人不必先想一想这颗按钮管的是谁。
 */
final class SettingsPage {

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

    /** 页面根。滚动容器直接装进来，不再需要额外让出底部那条按钮行。 */
    private final class Shell extends FrameLayout {
        Shell() {
            super(host);
        }

        /**
         * 点到输入框外面就把光标收起来、键盘落下去。
         *
         * <p>只在按下那一刻判断，而且只认「当前正聚焦的那个输入框」：别的输入框没聚焦，点在它
         * 上面本来就会把焦点移过去，不该在这里插手。落点用屏幕坐标比，因为滚动容器会滚、行的
         * 位置一直在变。返回 false 把事件照常往下传，否则这一下点击就被这里吃掉了，按钮也不响应。
         */
        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                View focused = findFocus();
                if (focused instanceof EditText) {
                    int[] location = new int[2];
                    focused.getLocationOnScreen(location);
                    float x = event.getRawX();
                    float y = event.getRawY();
                    boolean inside = x >= location[0] && x <= location[0] + focused.getWidth()
                            && y >= location[1] && y <= location[1] + focused.getHeight();
                    if (!inside) {
                        focused.clearFocus();
                        android.view.inputmethod.InputMethodManager keyboard =
                                host.getSystemService(android.view.inputmethod.InputMethodManager.class);
                        if (keyboard != null) {
                            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(event);
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

    /** 诊断板块下方那句「现在记多细」。保存之后要就地改掉，见 {@link #refreshLevelHint}。 */
    private TextView levelHint;

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
                chrome.say("删不掉：" + file.getAbsolutePath());
            }
        });
        page.addView(delete, chrome.margins(0, 0));
        return chrome.scroller(page);
    }

    /**
     * 诊断板块下面那两颗：丢掉日志设置的改动，或者把它写下去。
     *
     * <p>它们只管诊断板块里的东西，而诊断板块里现在**只剩一行** {@code log.level}——发现、
     * 发送、接收、设备四块都搬到功能页去了，原来的 {@code log.preview_bytes}（报文长度）也摘了
     * （没有读取方的滑杆）。所以「只管日志级别」在实现上不需要给 {@link SettingsScreen#save}
     * 加一个键白名单：可见的、可改的本来就只有这一行，其余的写回去时值与加载时一字不差。
     *
     * <p>写成白名单反而更危险：白名单里没有的键不会报错，它只是静静地不保存——将来谁把一行挪
     * 回这一页，就得先猜为什么改了没反应。
     *
     * <p>保存成完要把 {@code log.level} 重新读一次（见 {@link #save}）：不重读的话，人把级别
     * 从 info 改成 debug、回到首页发一个文件，日志里那一档的细节一行都不会多——那个设置看着
     * 生效了，实际上要等下次启动。这是「保存按钮只管日志」这件事唯一必须自己多走的一步。
     */
    private View logActions() {
        LinearLayout line = chrome.row();

        TextView revert = chrome.ghostButton("还原", view -> {
            if (!screen.dirty()) {
                chrome.say("没有改动");
                return;
            }
            ask("放弃诊断的改动？", "回到上次保存的内容。", "放弃", () -> {
                screen.revert();
                attempted = false;
                apply();
                clearProblems();
            });
        });
        TextView save = chrome.mainButton("保存", view -> save());

        LinearLayout.LayoutParams revertLp =
                chrome.mm(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        revertLp.rightMargin = chrome.dp(10);
        line.addView(revert, revertLp);
        line.addView(save, chrome.mm(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return line;
    }

    /**
     * 日志写到哪儿、现在记多细。
     *
     * <p>这一块是「日志级别」这个旋钮的落点：人调完级别之后，下一个问题一定是「那日志在哪」。
     * 上一版的答案是设置文件自己的路径，那是另一个问题的答案。
     *
     * <p># 为什么它长在卡里，而不是卡外面
     *
     * <p>卡里面每一行都从同一条竖线起头（页面 24dp + 卡内 24dp = 48dp）。放到卡外面就只能自己
     * 猜一个内边距——上一版猜的是 6dp，结果那一行比「日志级别」往左了 16dp，两行字明明说着同一
     * 件事，起头却不在一条线上。挪进卡里，对齐是布局给的，不用谁去记一个数。
     *
     * <p>路径写成 {@code /sdcard/Documents/AirDrop} 而不是 {@code Documents/AirDrop}：人在文件
     * 管理器里看到的、在电脑上插线看到的，都是前者。目录名后面接一句按天分文件，是因为文件名
     * 带日期，不说明的话人打开文件管理器看到一串 {@code airdrop-2026-11-16.log} 会先愣一下。
     */
    private View logNote() {
        LinearLayout box = chrome.column();
        box.addView(chrome.text("日志文件", 15, true, S.text));
        TextView where = chrome.caption(Diagnostics.PATH + "，按天一个文件。");
        where.setPadding(0, chrome.dp(3), 0, 0);
        box.addView(where);

        levelHint = chrome.caption(Log.describe(host) + "。");
        levelHint.setPadding(0, chrome.dp(2), 0, 0);
        box.addView(levelHint);
        return box;
    }

    /**
     * 改完级别之后把那句话换掉。
     *
     * <p>不重建整页：重建会把滚动位置和人刚敲的东西一起丢掉，而他刚刚只是按了一下保存。这一句
     * 是页面上唯一会因为保存而变的事实，让它自己更新最省。
     */
    private void refreshLevelHint() {
        if (levelHint != null) {
            levelHint.setText(Log.describe(host) + "。");
        }
    }

    /** 画出库报告的每一个分组，按它给的顺序。 */
    private void fill() {
        body.removeAllViews();
        rows.clear();

        for (SettingsScreen.Section section : screen.sections()) {
            // 发现、发送、接收三块都撤了：发现没有可拨的，发送没有可设的，接收里唯一还用的
            // 「接收文件保存位置」已移到功能页的【保存位置】板块。设备名（对端看到的名字）也
            // 移到功能页，改名【设备显示】——都是「拨一下就换对端看到什么」的行为，归到一处。
            // 那一页原来还有一行改机型，已撤（理由见 FeaturePage.deviceCard）。
            // 这一页只剩诊断和关于。
            String heading = section.title();
            if ("发现".equals(heading) || "发送".equals(heading)
                    || "接收".equals(heading) || "设备".equals(heading)) {
                continue;
            }
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
            // 动作行紧跟在它管的那张卡后面，中间不再隔任何东西：按钮和它作用的东西之间不该隔着
            // 一整块别的板块。原来那一行「日志写到哪」挪进了卡里（见 {@link #logNote}），所以
            // 这里只剩两颗按钮。
            if (logs(section)) {
                body.addView(logActions(), chrome.margins(0, 6));
            }
        }

        body.addView(chrome.sectionTitle("关于"), chrome.margins(4, 0));
        body.addView(aboutCard(), chrome.margins(0, 16));

        // 这一行原来是「N 行 → /data/user/0/com.oshare.airdrop/files/settings.conf」。
        //
        // 撤掉它有三条理由，按分量排：
        //
        // 1. 它答的是一个几乎没人问的问题。设置文件的路径是排障时才需要的东西，而排障的人
        //    手里有 adb 和日志，不靠在设置页最后一行找。对其他人来说那是一行十六进制的噪音。
        // 2. 它是这一页唯一一个不吃页面栅格的东西——卡里的字从 36dp 起笔（页面 18 + 卡内
        //    18），这一行挂着 6dp 的内边距，从 24dp 起笔。它长得像一条脚注，但位置上像是
        //    另一层的内容，于是看着总有一点没对齐。
        // 3. 「N 行」这个数没有任何用处：行数是表决定的，不是人改得动的，写出来既不能验证
        //    什么也不能操作什么。
    }

    /**
     * 这一组是不是日志（诊断）那一组。
     *
     * <p>按行的键判断而不是按标题判断：标题是给人看的字，随时可能被改词；{@code log.*} 是表里的
     * 契约，改它得连 Rust 那张表一起改。用标题的话，某天把「诊断」改成「日志」这颗按钮就会
     * 悄悄消失——不报错，只是不见了。
     */
    private boolean logs(SettingsScreen.Section section) {
        for (SettingsScreen.Setting setting : section.settings()) {
            if (setting.key().startsWith("log.")) {
                return true;
            }
        }
        return false;
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
        // 「日志写到哪」作为卡里最后一行挂着：它和上面两行说的是同一件事（这个级别记下来的东西
        // 存在哪），而且它对不齐的问题只有长在卡里才彻底没有。分隔线照旧用整幅的那种。
        if (logs(section)) {
            card.addView(chrome.divider(), chrome.fixedDivider(0, 0));
            card.addView(logNote(), chrome.margins(16, 0));
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
            chrome.say("没保存成功：" + failed.getMessage());
            return;
        }

        if (problems.isEmpty()) {
            // 库把值改写成了它自己的存法（去空白、标准化），所以控件要从它那里重画，而不是
            // 留着人刚敲的那份近似值。
            apply();
            clearProblems();
            // 级别是进程中唯一被缓存的一份设置（见 Log）。不在这里重读，人把 info 改成 debug
            // 之后发的那个文件，日志里一行报文都不会多——旋钮看着转动了，线还没接上。
            Log.reload(host);
            refreshLevelHint();
            chrome.say("已保存");
            return;
        }

        clearProblems();
        for (String problem : problems) {
            mark(problem);
        }
        chrome.say("有 " + problems.size() + " 项需要改正，一项都没写入");
    }

    /** 把一条拒绝（形如 {@code key: 说明}）写到它点名的那一行下面。 */
    private void mark(String problem) {
        int colon = problem.indexOf(": ");
        if (colon < 0) {
            // 没有键的消息意味着页面和库对一个谁都不认领的值有分歧——那是 bug 不是手误，所以
            // 整条照原样显示。
            chrome.say(problem);
            return;
        }
        Row row = rows.get(problem.substring(0, colon));
        if (row == null) {
            chrome.say(problem);
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
                IconView.NO_ICON, "关于 OPAirDrop", "版本、源代码与开源许可",
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
