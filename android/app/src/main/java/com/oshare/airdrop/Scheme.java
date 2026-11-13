package com.oshare.airdrop;

/**
 * 一套配色。
 *
 * <p># 为什么是一次性建好的一张表
 *
 * <p>这套 UI 里每个圆角、每条分隔线、每块玻璃的染色都取自同一张表。分散成一个个
 * {@code if (lightMode)} 之后，漏掉一处不会报错，只会让那一处在深色下变成一块读不出字的白
 * 方块——而漏掉的那一处总是在别的地方。所以模式只决定「哪一行被选中」，取色本身不再判断。
 *
 * <p># 数值来自原版
 *
 * <p>浅色页底 {@code #F0F1F3}、卡片纯白、加载态 {@code #F1F3F6}、主色 {@code #2563EB}；
 * 深色页底 {@code #1F1F1F}、卡片 {@code #303030}。玻璃的染色（{@link #navTint}）就是页底
 * 带一点透明度：一块比页面亮的玻璃会把自己画成一个发光的方块，边界比内容还显眼。
 *
 * <p>这两组数不能只改一半。页底改成一个数、卡片还是另一个数，卡片就从「浮在纸上」变成
 * 「贴在纸上的一块补丁」；而 {@link #veil} 与 {@link #bg} 不同源时，顶部遮罩会在状态栏下面
 * 露出一条颜色不一样的横带。
 */
final class Scheme {
    /** 卡片里的首行：上两角圆，下两角方。 */
    static final float[] CORNERS_TOP = {22, 22, 0, 0};
    /** 卡片里的末行：上两角方，下两角圆。 */
    static final float[] CORNERS_BOTTOM = {0, 0, 22, 22};
    /** 只装一行的卡片：四角都圆。 */
    static final float[] CORNERS_ALL = {22, 22, 22, 22};

    static final Scheme LIGHT = new Scheme(true,
            0xFFF0F1F3, 0xFFFFFFFF, 0xFFF1F3F6, 0xFFECECEC, 0xB3F0F1F3,
            0xFF2563EB, 0xFF1D4ED8, 0xFF17181A, 0xFF5F6368,
            0xFF16A34A, 0xFFDC2626, 0xFFD97706, 0xFF9E9E9E,
            0x14000000, 0xFF3B82F6, 0xFF1E40AF, 0x382563EB, 0xDDF0F1F3,
            0x14000000, 0xFFD5D9DF, 0xFFFFFFFF);

    static final Scheme DARK = new Scheme(false,
            0xFF1F1F1F, 0xFF303030, 0xFF3A3A3A, 0xFF3A3A3A, 0x991F1F1F,
            0xFF398FFE, 0xFF1D4ED8, 0xFFF5F5F5, 0xFF9E9E9E,
            0xFF22C55E, 0xFFEF4444, 0xFFF59E0B, 0xFF8A8A8A,
            0x1FFFFFFF, 0xFF2563EB, 0xFF1D4ED8, 0x403B82F6, 0xD91F1F1F,
            0x1AFFFFFF, 0xFF4A4C50, 0xFFEDEDED);

    static Scheme of(boolean light) {
        return light ? LIGHT : DARK;
    }

    /** 当前是不是浅色。模糊配方（高光、提亮、羽化）要跟着它走。 */
    final boolean light;

    /** 页面底色，同时也是每块玻璃的填充色。 */
    final int bg;
    /** 卡片。 */
    final int card;
    /** 卡片里的浅一档表面：输入框、次级按钮。 */
    final int cardHi;
    final int rowPress;
    /** 玻璃的染色：{@link #bg} 带一点透明度。 */
    final int navTint;
    final int accent;
    final int accentD;
    final int text;
    final int textDim;
    final int green;
    final int red;
    final int orange;
    final int gray;
    /** 卡片内的分隔线。 */
    final int divider;
    final int heroStart;
    final int heroEnd;
    final int glow;
    /** 顶部遮罩在这套模式下的霜面染色。 */
    final int veil;
    /** 整行按下的高亮。 */
    final int ripple;
    /** 拔杆关着时的轨道。开着时是 {@link #accent}。 */
    final int trackOff;
    /** 拔杆的圆点。深色下不是纯白：一块纯白圆点在 #303030 的卡片上会跳出来抢视线。 */
    final int thumb;

    private Scheme(boolean light, int bg, int card, int cardHi, int rowPress, int navTint,
                   int accent, int accentD, int text, int textDim, int green, int red,
                   int orange, int gray, int divider, int heroStart, int heroEnd, int glow,
                   int veil, int ripple, int trackOff, int thumb) {
        this.light = light;
        this.bg = bg;
        this.card = card;
        this.cardHi = cardHi;
        this.rowPress = rowPress;
        this.navTint = navTint;
        this.accent = accent;
        this.accentD = accentD;
        this.text = text;
        this.textDim = textDim;
        this.green = green;
        this.red = red;
        this.orange = orange;
        this.gray = gray;
        this.divider = divider;
        this.heroStart = heroStart;
        this.heroEnd = heroEnd;
        this.glow = glow;
        this.veil = veil;
        this.ripple = ripple;
        this.trackOff = trackOff;
        this.thumb = thumb;
    }

    /** 状态色：说「好」用绿，说「坏了」用红，说「还没测出来」用灰。 */
    int forState(boolean fine, boolean unknown) {
        return unknown ? gray : fine ? green : red;
    }
}
