package com.oshare.airdrop;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.lang.ref.WeakReference;

/**
 * 冷启动的绘制动画遮罩：两环「由下往上」用 Canvas 自绘。
 *
 * <p># 为什么不用系统 splash 的 AVD（三次失败的总账）
 *
 * <p>这台 ColorOS 的 AVD 内联动画只可靠支持 alpha：trimPathEnd 对 stroke path 不跑
 * （2026-11-26 实测只剩耳朵显示）、clip-path 的 pathData morph 不跑（揭示式静默），
 * alpha 逐段点亮有方向感但不是绘制（用户评「一坨」「左右颜色不一样」）。绘制动画
 * 要的是「圆弧随时间延伸」——只有 Canvas 的 drawArc(sweep) 能精确表达。
 *
 * <p># 与系统 splash 的衔接
 *
 * <p>系统 splash（themes 里 windowSplashScreenAnimatedIcon）已改为**只显示中心点**
 * （splash_icon_static）：ColorOS 在首帧后约 450ms 撤掉它。遮罩在 onCreate 时就
 * 叠在最上层（背景与 splash 同色），撤掉的瞬间遮罩已经在画点——点从未消失，
 * 两环随后由下往上绘制，画完（含耳朵）整层淡出，露出主界面。
 *
 * <p># 绘制的几何
 *
 * <p>圆心在屏幕中心；图标视口 108dp 以 {@link #ICON_DP} 的显示尺寸映射到像素。
 * 缺口环的缺口朝下（边缘 120°/60°，与 launcher 图标一致）。两环同时绘制：
 * 左弧从 120° 顺时针延伸、右弧从 60° 逆时针延伸，进度相同——左右对称由下往上。
 * 外环比内环稍慢（用户要求）。颜色用 SweepGradient（以圆心为轴），左右两侧
 * 是同一个渐变的两半，不会出现左右颜色不同。
 */
public final class SplashOverlay {

    /** 系统 splash 图标的显示直径（dp）。ColorOS 约 200-240，取中——遮罩只画点与环， */
    private static final float ICON_DP = 200f;

    /** 时间轴（ms）：点 0-150；内环 150-400；外环 220-500；耳 380-520；整层淡出 550-800。 */
    private static final long DOT_END = 150;
    private static final long INNER_START = 150;
    private static final long INNER_END = 400;
    private static final long OUTER_START = 220;
    private static final long OUTER_END = 500;
    private static final long EARS_START = 380;
    private static final long EARS_END = 520;
    private static final long FADE_START = 550;
    private static final long TOTAL = 800;

    /** 环的缺口边缘角（度，drawArc 坐标系：0=三点钟，正角顺时针）。底部缺口朝下。 */
    private static final float LEFT_EDGE = 120f;
    private static final float RIGHT_EDGE = 60f;
    /** 各环的绘制跨度：左弧 120°→270°（+150），右弧 60°→-90°（-150）。 */
    private static final float SWEEP = 150f;

    private static WeakReference<View> attached;

    private SplashOverlay() {}

    /** 冷启动时调用（onCreate 且 savedInstanceState==null）。遮罩自绘自拆，无需外部控制。 */
    public static void attach(Activity activity) {
        if (attached != null && attached.get() != null) {
            return;
        }
        View overlay = new OverlayView(activity);
        activity.runOnUiThread(() -> {
            try {
                activity.addContentView(overlay,
                        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                overlay.bringToFront();
                attached = new WeakReference<>(overlay);
            } catch (Throwable failed) {
                Log.w("OPAirDrop", "绘制遮罩挂不上：" + failed);
            }
        });
    }

    private static final class OverlayView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint();
        private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        /** 内层（点+内环甜甜圈+内耳）与外层（外环甜甜圈+外耳）的位图：与图标
         *  pathData 完全相同的几何（甜甜圈用 arcTo 外弧 300° + 内弧反向 -300° 构造，
         *  缺口边缘 120°/60° 的径向直边），fill 用图标同款渐变。 */
        private final android.graphics.Bitmap layerInner;
        private final android.graphics.Bitmap layerOuter;
        private final long startedAt = System.currentTimeMillis();
        private final DecelerateInterpolator curve = new DecelerateInterpolator(1.5f);
        private final float unit;
        private final float cx;
        private final float cy;

        OverlayView(Activity host) {
            super(host);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            // setShader(null) 之后 fill 回落到 color——不设就是默认黑色（浅色模式下
            // 耳朵全是黑点，2026-11-26 用户实测）。耳朵色取图标渐变的中点蓝。
            fill.setColor(0xFF2F81F7);
            float density = host.getResources().getDisplayMetrics().density;
            unit = ICON_DP * density / 108f;
            cx = host.getResources().getDisplayMetrics().widthPixels / 2f;
            cy = host.getResources().getDisplayMetrics().heightPixels / 2f;
            layerInner = renderLayer(host, false);
            layerOuter = renderLayer(host, true);
        }

        /**
         * 层位图的三段 path，各自独立 drawPath（2026-11-26 修「耳朵全消失」）：耳朵
         * 原来与甜甜圈并进同一个 Path——nonzero 填充下耳圆（CW）落在环带上时 winding
         * 相互抵消，整只耳被吞掉。拆开各自 draw 后互不影响。
         *
         * <p>耳的位置照抄 ic_launcher_foreground：内环耳恰在描边端点（θ=120°/60°，
         * 中径 12），外环耳不对称（左耳 120°、右耳 67°）。环端点画上耳圆后，平端
         * 被圆帽罩住——「端点凹陷半个圆」的观感与图标一致。
         */
        private void drawInnerLayer(Canvas c, Paint paint) {
            android.graphics.Path dot = new android.graphics.Path();
            dot.addCircle(54f * unit, 54f * unit, 6.5f * unit, android.graphics.Path.Direction.CW);
            c.drawPath(dot, paint);
            c.drawPath(donut(14.75f * unit, 9.25f * unit), paint);
            android.graphics.Path earL = new android.graphics.Path();
            earL.addCircle(48f * unit, 64.39f * unit, 2.75f * unit, android.graphics.Path.Direction.CW);
            c.drawPath(earL, paint);
            android.graphics.Path earR = new android.graphics.Path();
            earR.addCircle(60f * unit, 64.39f * unit, 2.75f * unit, android.graphics.Path.Direction.CW);
            c.drawPath(earR, paint);
        }

        private void drawOuterLayer(Canvas c, Paint paint) {
            c.drawPath(donut(23f * unit, 17.5f * unit), paint);
            android.graphics.Path earL = new android.graphics.Path();
            earL.addCircle((54f - 10.12f) * unit, 71.54f * unit,
                    2.75f * unit, android.graphics.Path.Direction.CW);
            c.drawPath(earL, paint);
            android.graphics.Path earR = new android.graphics.Path();
            earR.addCircle((54f + 7.38f) * unit, 71.54f * unit,
                    2.75f * unit, android.graphics.Path.Direction.CW);
            c.drawPath(earR, paint);
        }

        /** 缺口环（甜甜圈）：外弧从 120° 顺时针 300° 到 60°，径向直边，内弧反向回。 */
        private android.graphics.Path donut(float ro, float ri) {
            android.graphics.Path p = new android.graphics.Path();
            android.graphics.RectF oOuter = new android.graphics.RectF(
                    54f * unit - ro, 54f * unit - ro, 54f * unit + ro, 54f * unit + ro);
            android.graphics.RectF oInner = new android.graphics.RectF(
                    54f * unit - ri, 54f * unit - ri, 54f * unit + ri, 54f * unit + ri);
            p.arcTo(oOuter, 120, 300);
            float x = 54f * unit + ri * (float) Math.cos(Math.toRadians(60));
            float y = 54f * unit + ri * (float) Math.sin(Math.toRadians(60));
            p.lineTo(x, y);
            p.arcTo(oInner, 60, -300);
            p.close();
            return p;
        }

        /** 把一层渲染成位图（108 视口单位 × unit 的方形，渐变与图标同款）。 */
        private android.graphics.Bitmap renderLayer(Activity host, boolean outer) {
            int side = (int) (108f * unit);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                    side, side, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new LinearGradient(
                    30f * unit, 26f * unit, 78f * unit, 82f * unit,
                    0xFF47A3F8, 0xFF1B5CF0, Shader.TileMode.CLAMP));
            if (outer) {
                drawOuterLayer(c, paint);
            } else {
                drawInnerLayer(c, paint);
            }
            return b;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long t = System.currentTimeMillis() - startedAt;
            int w = getWidth();
            int h = getHeight();

            // 背景：引用 launcher 背景色资源（values-night 有深色覆盖），自动适配深浅色。
            bg.setColor(getResources().getColor(R.color.ic_launcher_background));
            canvas.drawRect(0, 0, w, h, bg);

            // 整层淡出（550ms 后）。
            if (t > FADE_START) {
                float fade = 1f - curve.getInterpolation(
                        Math.min(1f, (t - FADE_START) / (float) (TOTAL - FADE_START)));
                setAlpha(fade);
            }

            // 中心点：0-150ms 淡入，之后一直在（fill 已带耳朵蓝，shader 着渐变）。
            float dotAlpha = t < DOT_END
                    ? curve.getInterpolation(t / (float) DOT_END) : 1f;
            fill.setAlpha((int) (255 * dotAlpha));
            fill.setShader(new LinearGradient(
                    cx - rDot(), cy - rDot(), cx + rDot(), cy + rDot(),
                    0xFF47A3F8, 0xFF1B5CF0, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, rDot(), fill);
            fill.setShader(null);
            fill.setAlpha(255);

            float pInner = progress(t, INNER_START, INNER_END);
            float pOuter = progress(t, OUTER_START, OUTER_END);
            // 位图揭示：扇形角度跟随对应环的绘制进度——「弧画到哪，图形就实体化到哪」。
            // 内层位图不含点（点单独画，保证淡入时机）。
            reveal(canvas, layerInner, pInner);
            reveal(canvas, layerOuter, pOuter);

            // stroke 弧画在位图之上：已揭示段被位图的不透明填充盖住，露出的只有
            // 「笔尖」那一段——绘制引导感与图形实体化同步。
            ringGradient();
            ring.setStrokeWidth(5.5f * unit);
            if (pInner > 0) {
                canvas.drawArc(arcOval(rInner()), LEFT_EDGE, SWEEP * pInner, false, ring);
                canvas.drawArc(arcOval(rInner()), RIGHT_EDGE, -SWEEP * pInner, false, ring);
            }
            if (pOuter > 0) {
                canvas.drawArc(arcOval(rOuter()), LEFT_EDGE, SWEEP * pOuter, false, ring);
                canvas.drawArc(arcOval(rOuter()), RIGHT_EDGE, -SWEEP * pOuter, false, ring);
            }

            if (t < TOTAL) {
                postInvalidateOnAnimation();
            } else {
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) {
                    parent.removeView(this);
                }
                if (layerInner != null) {
                    layerInner.recycle();
                }
                if (layerOuter != null) {
                    layerOuter.recycle();
                }
            }
        }

        /** 扇形揭示：以圆心为顶点、左右两个扇形随进度向上张开，clip 后画层位图。 */
        private void reveal(Canvas canvas, android.graphics.Bitmap layer, float p) {
            if (p <= 0 || layer == null || layer.isRecycled()) {
                return;
            }
            float big = (float) Math.hypot(getWidth(), getHeight());
            android.graphics.RectF oval = new android.graphics.RectF(
                    cx - big, cy - big, cx + big, cy + big);
            android.graphics.Path fan = new android.graphics.Path();
            fan.moveTo(cx, cy);
            fan.arcTo(oval, LEFT_EDGE, SWEEP * p);
            fan.close();
            fan.moveTo(cx, cy);
            fan.arcTo(oval, RIGHT_EDGE, -SWEEP * p);
            fan.close();
            canvas.save();
            canvas.clipPath(fan);
            canvas.drawBitmap(layer, cx - layer.getWidth() / 2f, cy - layer.getHeight() / 2f, bmp);
            canvas.restore();
        }

        private float rDot() {
            return 6.5f * unit;
        }

        private float rInner() {
            return 12f * unit;
        }

        private float rOuter() {
            return 20.25f * unit;
        }

        private android.graphics.RectF arcOval(float r) {
            return new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
        }

        private void ringGradient() {
            // SweepGradient 以圆心为轴：整条环是同一个锥形渐变的两半，左右不会各成一色。
            ring.setShader(new SweepGradient(cx, cy,
                    new int[]{0xFF1B5CF0, 0xFF47A3F8, 0xFF1B5CF0}, null));
        }

        private static float progress(long t, long start, long end) {
            if (t <= start) {
                return 0f;
            }
            if (t >= end) {
                return 1f;
            }
            return new DecelerateInterpolator(1.5f).getInterpolation(
                    (t - start) / (float) (end - start));
        }
    }
}
