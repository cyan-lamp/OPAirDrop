package com.oshare.airdrop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 应用里全部手绘图标。
 *
 * <p># 为什么是画的，不是矢量资源
 *
 * <p>每个图标都是一组直线和圆，坐标全部按边长取比例。画成 {@code VectorDrawable} 要有十几份
 * XML、十几套 path 数据，还要在改动比例时同时改十几处；这里每个图标十行，改一个数字就整体
 * 缩放。线宽也是按边长算的（{@code 0.08w}），所以同一个图标放在 22dp 的底栏槽里和 44dp 的
 * 图标砖里看起来是同一支笔画的。
 *
 * <p># 类型编号就是原版的编号
 *
 * <p>底栏按 {@code 0..2} 取，页面里的行按 {@code 3..10} 取。编号与图形的一一对应关系写在这里
 * 一次，页面只引用常量名——把 {@code 5} 写进页面，就再也没人知道 5 是手机还是证件。
 */
final class IconView extends View {
    /** 首页：屋脊加两面墙。 */
    static final int HOME = 0;
    /** 功能 / 状态：两圈雷达加中心点。 */
    static final int RADAR = 1;
    /** 设置：托盘上一个向上的箭头。 */
    static final int SEND = 2;
    /** 盾牌：框架服务（LSPosed）。 */
    static final int SHIELD = 3;
    /** 终端提示符：超级用户权限。 */
    static final int TERMINAL = 4;
    /** 手机：设备型号。 */
    static final int PHONE = 5;
    /** 证件：版本号。 */
    static final int CARD = 6;
    /** 芯片：ColorOS 版本。 */
    static final int CHIP = 7;
    /** 安卓机器人：Android 版本。 */
    static final int ANDROID = 8;
    /** 带勾的盾牌：安全补丁。 */
    static final int PATCH = 9;
    /** 齿轮：内核版本。 */
    static final int KERNEL = 10;
    /** 三条带旋钮的滑杆：一般设置。 */
    static final int SLIDERS = 11;
    /** 首页「接收文件」：同一只托盘，箭头尖端朝下落进托盘（见 {@link #receive}）。 */
    static final int RECEIVE = 15;
    /** 哨兵：这一行不要图标砖，文字从卡片左缘起笔（见 Chrome.featureRow）。 */
    static final int NO_ICON = -1;

    /**
     * 底栏三格专用的三个图标。
     *
     * <p>和上面那批分开编号，是因为它们在 22dp 的槽里要读得出来：底栏一格里只有 22dp 见方，
     * 图形占不满就显轻，线条多一根就糊成一团。所以这三个是按「22dp 下只剩轮廓」重画的，不是把
     * 页内图标缩小——页内那些是 44dp 图标砖里的 22dp 图形，四周有底色托着，可以细。
     *
     * <p>语义也对齐了页名：首页是房子、功能是三条滑杆（这一页全是开关）、设置是齿轮。原来是
     * 房子 / 雷达 / 上传托盘——雷达是「在收」的意思，却被贴在「功能」上；上传托盘贴在「设置」
     * 上更是没道理。
     */
    static final int NAV_HOME = 12;
    static final int NAV_FEATURE = 13;
    static final int NAV_SETTINGS = 14;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int type;
    private int color = 0xFF8A93A6;

    IconView(Context context, int type) {
        super(context);
        this.type = type;
    }

    void setColor(int value) {
        color = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * 0.08f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);

        switch (type) {
            case HOME:
                // 墙在 ±0.35w，屋脊在 −0.30h，地面在 +0.28h：整个图形占 0.70w 宽、0.58h 高，
                // 和另外两个图标的视觉重量对齐。
                house(c, cx, cy, w, h);
                break;
            case RADAR:
                radar(c, cx, cy, w);
                break;
            case SEND:
                send(c, cx, cy, w, h);
                break;
            case RECEIVE:
                receive(c, cx, cy, w, h);
                break;
            case SHIELD:
                shield(c, cx, cy, w, h, false);
                break;
            case TERMINAL:
                terminal(c, cx, cy, w, h);
                break;
            case PHONE:
                phone(c, cx, w, h);
                break;
            case CARD:
                card(c, w, h);
                break;
            case CHIP:
                chip(c, cx, cy, w, h);
                break;
            case ANDROID:
                android(c, cx, cy, w, h);
                break;
            case PATCH:
                shield(c, cx, cy, w, h, true);
                break;
            case KERNEL:
                gear(c, cx, cy, w);
                break;
            case NAV_HOME:
                navHome(c, w, h);
                break;
            case NAV_FEATURE:
                navSwitches(c, w, h);
                break;
            case NAV_SETTINGS:
                navGear(c, cx, cy, w);
                break;
            default:
                sliders(c, w, h);
                break;
        }
    }

    private void house(Canvas c, float cx, float cy, float w, float h) {
        float s = w * 0.35f;
        float eave = cy - h * 0.02f;
        float apex = cy - h * 0.30f;
        float floor = cy + h * 0.28f;
        c.drawLine(cx - s, eave, cx, apex, paint);
        c.drawLine(cx + s, eave, cx, apex, paint);
        c.drawLine(cx - s, eave, cx - s, floor, paint);
        c.drawLine(cx + s, eave, cx + s, floor, paint);
        c.drawLine(cx - s, floor, cx + s, floor, paint);
    }

    private void radar(Canvas c, float cx, float cy, float w) {
        c.drawCircle(cx, cy, w * 0.20f, paint);
        c.drawCircle(cx, cy, w * 0.35f, paint);
        // 中心点实心：两圈都是描边，中间再空着就看不出圆心在哪。
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, w * 0.07f, paint);
    }

    /** 上传：托盘开口朝上，箭头从托盘里向上指出去。 */
    private void send(Canvas c, float cx, float cy, float w, float h) {
        tray(c, cx, cy, w, h);
        arrow(c, cx, cy, w, h);
    }

    /**
     * 下载：托盘照旧开口朝上，箭头尖端朝下落进托盘。
     *
     * <p># 为什么不再用 {@code rotate(180)} 复用 {@link #arrow}
     *
     * <p>上一版是「托盘不动、把箭头绕中心转 180°」。{@code rotate} 是**点对称**：图形上每一点
     * {@code (cx+dx, cy+dy)} 变成 {@code (cx-dx, cy-dy)}。{@code arrow} 的竖线占
     * {@code cy-0.38h ~ cy+0.18h}，点对称之后变成 {@code cy-0.18h ~ cy+0.38h}——整支箭**下移
     * 0.20h**。而托盘底在 {@code cy+0.36h}，于是箭尾（{@code cy+0.38h}）比托盘底还低 0.02h，
     * 从托盘下方扎了出来；箭尖（{@code cy-0.18h}）又悬在托盘开口上方。两截都在托盘轮廓上，
     * 读起来就是「箭头和 U 型重合」。
     *
     * <p>而且形状也变小了：发送图标占 {@code cy-0.38h ~ cy+0.36h}，旋转后的接收图标只占
     * {@code cy-0.18h ~ cy+0.38h}，上半屏空着——并排两行，接收那只看起来明显小一号。
     *
     * <p># 现在怎么画
     *
     * <p>竖线与 {@link #arrow} 画的是**同一根线段**（{@code cy-0.38h ~ cy+0.18h}），只把那一撇
     * 从顶端换到底端、并且朝下张开。于是两支箭互为镜像：
     * <ul>
     *   <li>外接框完全一致（{@code x: ±0.28w}，{@code y: cy-0.38h ~ cy+0.36h}），两个图标等大；</li>
     *   <li>箭尖落在 {@code cy+0.18h}，在托盘开口（{@code cy+0.02h}）之内 0.16h，离托盘底
     *       （{@code cy+0.36h}）还空 0.18h——没有任何一笔压在托盘轮廓上。</li>
     * </ul>
     */
    private void receive(Canvas c, float cx, float cy, float w, float h) {
        tray(c, cx, cy, w, h);
        // 箭杆：与 arrow() 同一根，长度 0.56h，尾端在 cy-0.38h（和发送图标一样高）。
        c.drawLine(cx, cy - h * 0.38f, cx, cy + h * 0.18f, paint);
        // 两撇从箭尖往回斜上去，夹角与 arrow() 的两撇一致（同一组 0.19w / 0.19h 比例）。
        c.drawLine(cx, cy + h * 0.18f, cx - w * 0.19f, cy - h * 0.01f, paint);
        c.drawLine(cx, cy + h * 0.18f, cx + w * 0.19f, cy - h * 0.01f, paint);
    }

    /** 收发共用：开口朝上的托盘。 */
    private void tray(Canvas c, float cx, float cy, float w, float h) {
        Path tray = new Path();
        tray.moveTo(cx - w * 0.28f, cy + h * 0.02f);
        tray.lineTo(cx - w * 0.28f, cy + h * 0.36f);
        tray.lineTo(cx + w * 0.28f, cy + h * 0.36f);
        tray.lineTo(cx + w * 0.28f, cy + h * 0.02f);
        c.drawPath(tray, paint);
    }

    /** 收发共用：一支向上的箭头。接收时只把这一段旋转 180°（见 {@link #receive}）。 */
    private void arrow(Canvas c, float cx, float cy, float w, float h) {
        c.drawLine(cx, cy + h * 0.18f, cx, cy - h * 0.38f, paint);
        c.drawLine(cx, cy - h * 0.38f, cx - w * 0.19f, cy - h * 0.19f, paint);
        c.drawLine(cx, cy - h * 0.38f, cx + w * 0.19f, cy - h * 0.19f, paint);
    }

    /** 盾牌轮廓；{@code check} 为真时中间画一个勾，否则画一个点。 */
    private void shield(Canvas c, float cx, float cy, float w, float h, boolean check) {
        Path path = new Path();
        path.moveTo(cx, cy - h * 0.42f);
        path.lineTo(cx + w * 0.36f, cy - h * 0.22f);
        path.lineTo(cx + w * 0.36f, cy + h * 0.10f);
        path.quadTo(cx + w * 0.30f, cy + h * 0.36f, cx, cy + h * 0.44f);
        path.quadTo(cx - w * 0.30f, cy + h * 0.36f, cx - w * 0.36f, cy + h * 0.10f);
        path.lineTo(cx - w * 0.36f, cy - h * 0.22f);
        path.close();
        c.drawPath(path, paint);
        if (check) {
            paint.setStrokeWidth(w * 0.075f);
            c.drawLine(cx - w * 0.14f, cy + h * 0.01f, cx - w * 0.04f, cy + h * 0.11f, paint);
            c.drawLine(cx - w * 0.04f, cy + h * 0.11f, cx + w * 0.16f, cy - h * 0.11f, paint);
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy - h * 0.02f, w * 0.09f, paint);
    }

    private void terminal(Canvas c, float cx, float cy, float w, float h) {
        c.drawRoundRect(w * 0.10f, h * 0.16f, w * 0.90f, h * 0.84f,
                w * 0.18f, w * 0.18f, paint);
        paint.setStrokeWidth(w * 0.07f);
        c.drawLine(cx - w * 0.22f, cy - h * 0.04f, cx - w * 0.10f, cy + h * 0.06f, paint);
        c.drawLine(cx - w * 0.10f, cy + h * 0.06f, cx - w * 0.22f, cy + h * 0.16f, paint);
        c.drawLine(cx, cy + h * 0.16f, cx + w * 0.24f, cy + h * 0.16f, paint);
    }

    private void phone(Canvas c, float cx, float w, float h) {
        c.drawRoundRect(w * 0.20f, h * 0.06f, w * 0.80f, h * 0.94f,
                w * 0.16f, w * 0.16f, paint);
        paint.setStrokeWidth(w * 0.07f);
        c.drawLine(cx, h * 0.79f, cx, h * 0.85f, paint);
    }

    private void card(Canvas c, float w, float h) {
        c.drawRoundRect(w * 0.10f, h * 0.20f, w * 0.90f, h * 0.80f,
                w * 0.16f, w * 0.16f, paint);
        paint.setStrokeWidth(w * 0.07f);
        c.drawLine(w * 0.24f, h * 0.40f, w * 0.56f, h * 0.40f, paint);
        c.drawLine(w * 0.24f, h * 0.60f, w * 0.72f, h * 0.60f, paint);
    }

    private void chip(Canvas c, float cx, float cy, float w, float h) {
        c.drawRoundRect(w * 0.28f, h * 0.28f, w * 0.72f, h * 0.72f,
                w * 0.10f, w * 0.10f, paint);
        paint.setStrokeWidth(w * 0.07f);
        float[] pins = {0.36f, 0.50f, 0.64f};
        for (float p : pins) {
            c.drawLine(w * p, h * 0.10f, w * p, h * 0.28f, paint);
            c.drawLine(w * p, h * 0.72f, w * p, h * 0.90f, paint);
            c.drawLine(w * 0.10f, h * p, w * 0.28f, h * p, paint);
            c.drawLine(w * 0.72f, h * p, w * 0.90f, h * p, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, w * 0.05f, paint);
    }

    private void android(Canvas c, float cx, float cy, float w, float h) {
        float r = w * 0.34f;
        RectF dome = new RectF(cx - r, cy - h * 0.10f, cx + r, cy - h * 0.10f + 2 * r);
        c.drawArc(dome, 180, 180, false, paint);
        c.drawLine(cx - r, cy - h * 0.10f, cx + r, cy - h * 0.10f, paint);
        paint.setStrokeWidth(w * 0.07f);
        c.drawLine(cx - w * 0.20f, cy - h * 0.28f, cx - w * 0.34f, cy - h * 0.44f, paint);
        c.drawLine(cx + w * 0.20f, cy - h * 0.28f, cx + w * 0.34f, cy - h * 0.44f, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx - w * 0.13f, cy + h * 0.02f, w * 0.045f, paint);
        c.drawCircle(cx + w * 0.13f, cy + h * 0.02f, w * 0.045f, paint);
    }

    private void gear(Canvas c, float cx, float cy, float w) {
        c.drawCircle(cx, cy, w * 0.24f, paint);
        paint.setStrokeWidth(w * 0.07f);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(cx + dx * w * 0.26f, cy + dy * w * 0.26f,
                    cx + dx * w * 0.38f, cy + dy * w * 0.38f, paint);
        }
        c.drawCircle(cx, cy, w * 0.09f, paint);
    }

    private void sliders(Canvas c, float w, float h) {
        float[] knob = {0.28f, 0.62f, 0.40f};
        for (int i = 0; i < 3; i++) {
            float y = h * (0.25f + 0.25f * i);
            c.drawLine(w * 0.15f, y, w * 0.85f, y, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(w * knob[i], y, w * 0.10f, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
    }

    // ------------------------------------------------- 底栏三个（22dp 专用）

    /**
     * 房子：带着圆角五边形轮廓和一道门。
     *
     * <p>不用直角折线拼五条边，是因为 22dp 下一个尖角只有不到 1dp 的余地，抗锯齿会把它磨圆成
     * 一颗糊掉的点；每只角用一段二次曲线收圆之后，轮廓在小尺寸下仍然是「一眼看出是屋顶」的
     * 形状。门是最后加的：没有门，这个轮廓在 22dp 下也像一支向上的箭头。
     */
    private void navHome(Canvas c, float w, float h) {
        float[] xs = {0.15f * w, 0.15f * w, 0.50f * w, 0.85f * w, 0.85f * w};
        float[] ys = {0.885f * h, 0.44f * h, 0.135f * h, 0.44f * h, 0.885f * h};
        roundedPolygon(c, xs, ys, w * 0.09f);

        paint.setStrokeWidth(w * 0.075f);
        c.drawRoundRect(0.395f * w, 0.635f * h, 0.605f * w, 0.885f * h,
                w * 0.03f, w * 0.03f, paint);
        paint.setStrokeWidth(w * 0.08f);
    }

    /**
     * 三条滑杆：功能页全是开关。
     *
     * <p>滑杆比「一堆方格子」好在不用解释：一条线加一颗拨到不同位置的圆点，本身就是这一页
     * 在做的事。三颗圆点刻意错开，读起来是「可以调」，而不是一条被切了三段的横线。
     */
    private void navSwitches(Canvas c, float w, float h) {
        paint.setStrokeWidth(w * 0.075f);
        float[] rows = {0.25f, 0.50f, 0.75f};
        float[] knobs = {0.665f, 0.355f, 0.575f};
        for (int i = 0; i < rows.length; i++) {
            float y = h * rows[i];
            c.drawLine(w * 0.13f, y, w * 0.87f, y, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(w * knobs[i], y, w * 0.105f, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setStrokeWidth(w * 0.08f);
    }

    /**
     * 齿轮：一圈轮缘、八颗齿、中间一个轴孔。
     *
     * <p>齿比轮缘粗（0.11w 对 0.085w）：一样粗的话，八条短线加一个圆在 22dp 下读起来是太阳，
     * 粗一档才会先看到「凸出来的齿」，再看到那个圆。
     */
    private void navGear(Canvas c, float cx, float cy, float w) {
        paint.setStrokeWidth(w * 0.085f);
        c.drawCircle(cx, cy, w * 0.20f, paint);
        paint.setStrokeWidth(w * 0.11f);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45 + 22.5);
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(cx + dx * w * 0.225f, cy + dy * w * 0.225f,
                    cx + dx * w * 0.365f, cy + dy * w * 0.365f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, w * 0.065f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * 0.08f);
    }

    /**
     * 把一串顶点连成圆角多边形：每只角用一段二次曲线收圆，半径向内退的距离不超过邻边的一半。
     *
     * <p>顶点按顺序给（顺逆都行），内部自动闭合。
     */
    private void roundedPolygon(Canvas c, float[] xs, float[] ys, float radius) {
        int n = xs.length;
        Path path = new Path();
        for (int i = 0; i < n; i++) {
            int prev = (i + n - 1) % n;
            int next = (i + 1) % n;
            float bx = xs[i], by = ys[i];
            float ax = xs[prev], ay = ys[prev];
            float dx = xs[next], dy = ys[next];
            float l1 = (float) Math.hypot(ax - bx, ay - by);
            float l2 = (float) Math.hypot(dx - bx, dy - by);
            if (l1 < 0.001f || l2 < 0.001f) {
                continue;
            }
            float r = Math.min(radius, Math.min(l1, l2) / 2f);
            float inX = bx + (ax - bx) / l1 * r;
            float inY = by + (ay - by) / l1 * r;
            float outX = bx + (dx - bx) / l2 * r;
            float outY = by + (dy - by) / l2 * r;
            if (path.isEmpty()) {
                path.moveTo(inX, inY);
            } else {
                path.lineTo(inX, inY);
            }
            path.quadTo(bx, by, outX, outY);
        }
        path.close();
        c.drawPath(path, paint);
    }
}
