package com.oshare.airdrop;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 页面里所有零件的出处：卡片、行、徽章、按钮、图标砖、分隔线。
 *
 * <p># 为什么要有这一层
 *
 * <p>三个页面的行长得一样，而这套 UI 的「一样」是很具体的一串数字：卡片圆角 22dp、内边距
 * 18/16、行高 66dp、图标砖 44dp、砖与文字之间 12dp、分隔线从 74dp 处开始（= 18 内边距 + 44
 * 砖 + 12 间隙）。这些数字抄在三个文件里，改一次就要改三处，漏掉一处就是「这一页的行比那一页
 * 高两像素」——没人能说清哪里不对，但都看得出来。
 *
 * <p># 为什么带着配色进来
 *
 * <p>零件的颜色全部来自 {@link Scheme}。工具箱自己不去问「现在是不是深色」：那种判断散落在
 * 二十个方法里，只要有一处漏了，深色模式下就会出现一个白底的行。模式在构造时定下，之后每个
 * 零件只管取色。
 */
final class Chrome {
    /** 卡片圆角。行内的按下高亮要跟它对齐，所以是公用的。 */
    private static final float CARD_RADIUS = 22f;
    /** 图标砖的边长，也是行高的主要成分。 */
    private static final int TILE = 44;
    /** 行内左右内边距。卡片自己是 0 内边距，全部下放到行上，按下高亮才能满幅。 */
    private static final int ROW_PAD = 18;
    /** 砖与文字之间的间隙。分隔线从「18 + 44 + 12」处起笔，与文字左缘对齐。 */
    private static final int GAP = 12;
    /** 设备信息类的行高。 */
    private static final int ROW_HEIGHT = 66;

    /** 提示条：外壳里那颗浮动玻璃胶囊。页面自己弹 toast 的话，材质就断在那一处。 */
    interface Notice {
        void show(String message, int color);
    }

    /**
     * 滚动钩子：页面里的滚动容器把滚动位置交给外壳。
     *
     * <p>这两个回调是外壳的两件动画的触发源：导航胶囊在内容上滑时下沉、下滑时浮回；标题胶囊
     * 的霜面/描边/阴影跟着内容的滚动进度生长。页面自己不知道胶囊的存在，所以由外壳注册进来。
     */
    interface ScrollHooks {
        /** {@code up=true} 表示内容往上滚（手指往上推），也就是用户在往下翻。 */
        void onScrollDirection(boolean up);

        /** 每一次滚动位置变化。用于标题胶囊的边界进度。 */
        void onScrollChanged();
    }

    /**
     * 页面根自己声明要多留一条底部空间。
     *
     * <p>设置页底部浮着一颗按钮行，它不是滚动内容的一部分，而是压在内容之上。外壳在算滚动区
     * 的 {@code paddingBottom} 时只知道系统栏的高度，不知道这颗按钮行有多高——不留这一条，
     * 表单最后一行会被按钮行永久压住，而且滚到底也看不见。
     *
     * <p>由页面根实现而不是靠一条常量：三个页面里只有设置页有浮动按钮，外壳不该知道这件事。
     */
    interface BottomInset {
        /** 需要额外留出的底部高度，单位 dp。 */
        int extraBottomDp();
    }

    private final Activity host;
    private final Scheme scheme;
    private Notice notice;
    private ScrollHooks hooks;

    Chrome(Activity host, Scheme scheme) {
        this.host = host;
        this.scheme = scheme;
    }

    Scheme scheme() {
        return scheme;
    }

    void setNotice(Notice sink) {
        notice = sink;
    }

    void setScrollHooks(ScrollHooks sink) {
        hooks = sink;
    }

    /** 说一句话，用外壳那颗浮动胶囊。没有外壳时（单元里）什么也不做。 */
    void say(String message) {
        say(message, scheme.text);
    }

    void say(String message, int color) {
        if (notice != null) {
            notice.show(message, color);
        }
    }

    int dp(float value) {
        return Math.round(host.getResources().getDisplayMetrics().density * value);
    }

    // -------------------------------------------------------------- 容器

    LinearLayout column() {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    LinearLayout row() {
        LinearLayout line = new LinearLayout(host);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    /** 一张卡片：圆角白面（深色下是 #303030），18/16 的内边距。 */
    LinearLayout card() {
        LinearLayout box = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(scheme.card);
        bg.setCornerRadius(dp(CARD_RADIUS));
        box.setBackground(bg);
        box.setPadding(dp(ROW_PAD), dp(16), dp(ROW_PAD), dp(16));
        return box;
    }

    /**
     * 装整行按钮的卡片：内边距清零。
     *
     * <p>卡片自带的内边距会让行缩在中间，按下高亮就只在中间那一块出现；清零之后行是满幅的，
     * 高亮从卡片边缘铺到另一侧边缘，和分隔线严格对齐。
     */
    LinearLayout rowCard() {
        LinearLayout box = card();
        box.setPadding(0, 0, 0, 0);
        return box;
    }

    /** 页面正文：左右 18dp，顶部留一点，底部留给浮动底栏。真正的让位在 applyInsets 里改。 */
    LinearLayout pageBody() {
        LinearLayout body = column();
        body.setPadding(dp(18), dp(4), dp(18), dp(28));
        return body;
    }

    /**
     * 页面的滚动容器：正文装进来，顺带把滚动位置转给外壳。
     *
     * <p>背景必须透明——滚动容器默认是主题的窗口底色，不透明就会把身后的 {@code frostVeil}
     * 整块盖住，顶部渐近模糊当场失效（看起来像「模糊没生效」，其实是被挡了）。竖向滚动条也关
     * 掉，它是另一种材质。
     *
     * <p>{@code dy} 用 2dp 做阈值：滚动开始时会有 1px 级别的抖动，不设阈值的话胶囊会在手指刚
     * 碰上去的一瞬间抖一下。
     */
    BounceScrollView scroller(LinearLayout body) {
        BounceScrollView scroll = new BounceScrollView(host);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        scroll.setOnScrollChangeListener((view, scrollX, scrollY, oldX, oldY) -> {
            int dy = scrollY - oldY;
            if (hooks != null) {
                if (dy > dp(2)) {
                    hooks.onScrollDirection(true);
                } else if (dy < -dp(2)) {
                    hooks.onScrollDirection(false);
                }
                hooks.onScrollChanged();
            }
        });
        return scroll;
    }

    // -------------------------------------------------------------- 文字

    TextView text(String value, float sizeSp, boolean bold, int color) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTypeface(bold
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.DEFAULT,
                bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setTextColor(color);
        return view;
    }

    TextView caption(String value) {
        return text(value, 12.5f, false, scheme.textDim);
    }

    /** 分组标题：小号、加粗、全大写、拉开字距。 */
    TextView sectionTitle(String label) {
        TextView view = text(label, 12.5f, true, scheme.textDim);
        view.setAllCaps(true);
        view.setLetterSpacing(0.08f);
        view.setPadding(dp(6), 0, dp(6), dp(8));
        return view;
    }

    // ------------------------------------------------------------ 布局参数

    LinearLayout.LayoutParams mm(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    LinearLayout.LayoutParams margins(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = mm(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    /**
     * 分隔线的参数。
     *
     * <p>一条空的 {@link View} 没有内在尺寸，高度不钉死就会塌成 0——分隔线消失这种事，在代码
     * 里看不出来，只有屏幕上少了一条线。
     */
    LinearLayout.LayoutParams fixedDivider(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = mm(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1), 0f);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    /** 卡片内的分隔线，左端与文字左缘（18 + 44 + 12）对齐。 */
    LinearLayout.LayoutParams insetDivider() {
        LinearLayout.LayoutParams lp = mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), 0f);
        lp.leftMargin = dp(ROW_PAD + TILE + GAP);
        return lp;
    }

    View divider() {
        View line = new View(host);
        line.setBackgroundColor(scheme.divider);
        return line;
    }

    // -------------------------------------------------------------- 徽章

    /** 带底色的小徽章，例如服务状态。 */
    TextView badge(String label, int color) {
        TextView view = text(label, 12.5f, true, color);
        paintBadge(view, color);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        return view;
    }

    void setBadge(TextView view, String label, int color) {
        view.setText(label);
        view.setTextColor(color);
        paintBadge(view, color);
    }

    private void paintBadge(TextView view, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x2A000000);
        bg.setCornerRadius(dp(999));
        view.setBackground(bg);
    }

    /** 被动状态胶囊：健康是绿的，不健康是红的。整行才是按钮，它自己不可点。 */
    TextView statusPill(String label, int color) {
        TextView view = text(label, 13, true, color);
        view.setGravity(Gravity.CENTER);
        paintPill(view, color);
        view.setPadding(dp(14), dp(7), dp(14), dp(7));
        return view;
    }

    void setPill(TextView view, String label, int color) {
        view.setText(label);
        view.setTextColor(color);
        paintPill(view, color);
    }

    private void paintPill(TextView view, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x2E000000);
        bg.setCornerRadius(dp(999));
        bg.setStroke(dp(1), (color & 0x00FFFFFF) | 0x66000000);
        view.setBackground(bg);
    }

    // -------------------------------------------------------------- 按钮

    TextView mainButton(String label, View.OnClickListener listener) {
        TextView button = text(label, 15, true, 0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(scheme.accent);
        button.setBackground(bg);
        button.setPadding(dp(20), dp(13), dp(20), dp(13));
        button.setOnClickListener(listener);
        pressable(button);
        return button;
    }

    TextView ghostButton(String label, View.OnClickListener listener) {
        TextView button = text(label, 14, false, scheme.text);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(scheme.cardHi);
        button.setBackground(bg);
        button.setPadding(dp(16), dp(11), dp(16), dp(11));
        button.setOnClickListener(listener);
        pressable(button);
        return button;
    }

    /** 按下去缩一下。缩放是唯一一个不改变布局的按下反馈。 */
    void pressable(View view) {
        view.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    target.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    target.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /**
     * 整行的按下高亮：涟漪从手指落点扩散，松手淡出。
     *
     * <p>{@code cornerDp} 按 {左上, 右上, 右下, 左下} 给：卡片第一行圆上两角、最后一行圆下两
     * 角，中间的行不圆——否则卡片中间会浮出四个圆角。
     *
     * <p>不可点的行必须吃掉事件（返回 true），可点的行必须放行（返回 false）：放行才能走到
     * {@code performClick}，吃掉才能保证 UP 一定回到这一行，而不是被外层的滚动容器抢走、把
     * 高亮永远留在按下状态。
     */
    void pressableRow(View row, float[] cornerDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        // 遮罩只用 alpha：不透明白色，否则涟漪会被整个裁掉
        mask.setColor(0xFFFFFFFF);
        if (cornerDp != null) {
            mask.setCornerRadii(new float[] {
                    dp(cornerDp[0]), dp(cornerDp[0]),
                    dp(cornerDp[1]), dp(cornerDp[1]),
                    dp(cornerDp[2]), dp(cornerDp[2]),
                    dp(cornerDp[3]), dp(cornerDp[3])});
        }
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(scheme.ripple), null, mask);
        row.setBackground(ripple);
        row.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ripple.setHotspot(event.getX(), event.getY());
                    view.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    break;
                default:
                    break;
            }
            return !view.isClickable();
        });
    }

    /** 整行当按钮：可点时挂高亮与动作，不可点时把两样都摘掉（那一行只显示状态）。 */
    void setRowAction(View row, boolean enabled, float[] cornerDp, Runnable action) {
        if (enabled) {
            row.setOnClickListener(view -> action.run());
            pressableRow(row, cornerDp);
            return;
        }
        row.setOnClickListener(null);
        row.setOnTouchListener(null);
        row.setClickable(false);
        row.setLongClickable(false);
        row.setBackground(null);
    }

    // -------------------------------------------------------------- 行

    /** 圆角图标砖：主色 12% 的底，中间一个同色图标。 */
    View iconTile(int iconType) {
        FrameLayout tile = new FrameLayout(host);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((scheme.accent & 0x00FFFFFF) | 0x1F000000);
        bg.setCornerRadius(dp(14));
        tile.setBackground(bg);
        IconView icon = new IconView(host, iconType);
        icon.setColor(scheme.accent);
        tile.addView(icon, new FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.CENTER));
        return tile;
    }

    LinearLayout featureRow(int iconType, String title, String subtitle, View right) {
        return featureRow(iconType, title, subtitle, right, null);
    }

    /**
     * 一行：图标砖 + 标题/副标题，右侧挂一个控件。
     *
     * <p>{@code subOut} 把副标题那块文字交回给调用方，好在状态变化时只改那一行——比如「检
     * 测中」变成「已连接」，不必重画整页。
     */
    LinearLayout featureRow(int iconType, String title, String subtitle, View right,
                            TextView[] subOut) {
        LinearLayout line = row();
        if (iconType != IconView.NO_ICON) {
            View tile = iconTile(iconType);
            tile.setLayoutParams(new LinearLayout.LayoutParams(dp(TILE), dp(TILE)));
            line.addView(tile);
        }

        LinearLayout mid = column();
        // 有图标砖时文字从图标右边 12dp 起笔。没有图标时不能再额外加缩进：调用方把这一行放进
        // rowCard + addRow，行本身已经左右各缩进 18dp，mid 再加一次就会比卡片标题（card 的 18dp
        // padding）右移一截——功能页那两行和内核驱动标题对不齐就是这么来的。
        mid.setPadding(dp(iconType == IconView.NO_ICON ? 0 : GAP), 0, dp(8), 0);
        mid.addView(text(title, 15, true, scheme.text));
        TextView sub = caption(subtitle);
        sub.setPadding(0, dp(2), 0, 0);
        mid.addView(sub);
        if (subOut != null) {
            subOut[0] = sub;
        }
        line.addView(mid, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (right != null) {
            line.addView(right, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return line;
    }

    /** 一行满幅的行：左右内边距下放到行上，高度固定，按下高亮才能铺满。 */
    void addRow(LinearLayout card, View row, int heightDp) {
        row.setPadding(dp(ROW_PAD), 0, dp(ROW_PAD), 0);
        card.addView(row, mm(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp), 0f));
    }

    /**
     * 一行满幅的行，高度按内容长：{@link #ROW_HEIGHT} 是下限，不是上限。
     *
     * <p>设备信息卡里有一行的值不是短句——内核 release 串后面挂着厂商的构建标记，五十来个字
     * 符在屏幕宽度里必然折成两行。钉死 66dp 会把第二行裁掉半截，而且裁得很不显眼：行还在、
     * 图标还在、字只是缺了下半，看起来像字体坏了。这种行给最小高度，短的行仍然是 66，长的
     * 自己长高。
     */
    void addWrappingRow(LinearLayout card, View row) {
        row.setPadding(dp(ROW_PAD), dp(8), dp(ROW_PAD), dp(8));
        row.setMinimumHeight(dp(ROW_HEIGHT));
        card.addView(row, mm(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
    }

    int rowHeight() {
        return ROW_HEIGHT;
    }

    /** 设备信息行：图标砖 + 标题，值叠在标题下面。 */
    LinearLayout deviceRow(int iconType, String title, TextView valueView) {
        LinearLayout line = row();
        View tile = iconTile(iconType);
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(TILE), dp(TILE)));
        line.addView(tile);

        LinearLayout mid = column();
        mid.setPadding(dp(GAP), 0, dp(8), 0);
        mid.addView(text(title, 15, true, scheme.text));
        valueView.setTextSize(13.5f);
        valueView.setTextColor(scheme.textDim);
        valueView.setPadding(0, dp(3), 0, 0);
        mid.addView(valueView);
        line.addView(mid, mm(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return line;
    }

    EditText darkInput(String hint) {
        EditText field = new EditText(host);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(scheme.text);
        field.setHintTextColor(scheme.light ? 0xFF5A6376 : 0xFF8A8A8A);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(scheme.cardHi);
        bg.setCornerRadius(dp(14));
        field.setBackground(bg);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        return field;
    }
}
