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
        /** 真实图标的前景（绘制完成时交叉淡入，最终态与 app 图标完全一致）。 */
        private final android.graphics.drawable.Drawable foreground;
        private final long startedAt = System.currentTimeMillis();
        private final DecelerateInterpolator curve = new DecelerateInterpolator(1.5f);

        OverlayView(Activity host) {
            super(host);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.BUTT);
            android.graphics.drawable.Drawable d = host.getDrawable(R.drawable.ic_launcher_foreground);
            if (d != null) {
                int side = (int) (ICON_DP * host.getResources().getDisplayMetrics().density);
                d.setBounds((int) (host.getResources().getDisplayMetrics().widthPixels / 2f - side / 2f),
                        (int) (host.getResources().getDisplayMetrics().heightPixels / 2f - side / 2f),
                        (int) (host.getResources().getDisplayMetrics().widthPixels / 2f + side / 2f),
                        (int) (host.getResources().getDisplayMetrics().heightPixels / 2f + side / 2f));
            }
            foreground = d;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long t = System.currentTimeMillis() - startedAt;
            int w = getWidth();
            int h = getHeight();
            float density = getResources().getDisplayMetrics().density;
            // 圆心与半径：图标视口 108dp 以 ICON_DP 的显示尺寸铺开，一视口单位 =
            // ICON_DP × density / 108 像素。圆心在屏幕中心（系统 splash 的图标也是居中）。
            float cx = w / 2f;
            float cy = h / 2f;
            float unit = ICON_DP * density / 108f;
            float rDot = 6.5f * unit;
            float rInner = 12f * unit;
            float rOuter = 20.25f * unit;

            // 背景：引用 launcher 背景色资源（values-night 有深色覆盖），自动适配深浅色。
            bg.setColor(getResources().getColor(R.color.ic_launcher_background));
            canvas.drawRect(0, 0, w, h, bg);

            // 整层淡出（550ms 后）：直接调 View 的 alpha，onDraw 里只管图形。
            if (t > FADE_START) {
                float fade = 1f - curve.getInterpolation(
                        Math.min(1f, (t - FADE_START) / (float) (TOTAL - FADE_START)));
                setAlpha(fade);
            }

            // 中心点：0-150ms 淡入，之后一直在。
            if (t < DOT_END) {
                fill.setAlpha((int) (255 * curve.getInterpolation(t / (float) DOT_END)));
            } else {
                fill.setAlpha(255);
            }
            fill.setShader(dotShader(cx, cy, rDot * 1.5f));
            canvas.drawCircle(cx, cy, rDot, fill);
            fill.setShader(null);

            // 内环：两段弧同时延伸（左 120°→270°，右 60°→-90°）。
            float pInner = progress(t, INNER_START, INNER_END);
            if (pInner > 0) {
                ringGradient(cx, cy, rInner);
                ring.setStrokeWidth(5.5f * unit);
                canvas.drawArc(cx - rInner, cy - rInner, cx + rInner, cy + rInner,
                        LEFT_EDGE, SWEEP * pInner, false, ring);
                canvas.drawArc(cx - rInner, cy - rInner, cx + rInner, cy + rInner,
                        RIGHT_EDGE, -SWEEP * pInner, false, ring);
                // 耳朵在弧端点上，环闭合时随进度出现。
                float earAlpha = Math.max(0f, (pInner - 0.85f) / 0.15f);
                if (earAlpha > 0) {
                    fill.setAlpha((int) (255 * earAlpha));
                    fill.setShader(null);
                    canvas.drawCircle(cx - rInner * 0.5f, cy + rInner * 0.866f,
                            2.75f * unit, fill);
                    canvas.drawCircle(cx + rInner * 0.5f, cy + rInner * 0.866f,
                            2.75f * unit, fill);
                }
            }

            // 外环：同几何，稍慢。
            float pOuter = progress(t, OUTER_START, OUTER_END);
            if (pOuter > 0) {
                ringGradient(cx, cy, rOuter);
                ring.setStrokeWidth(5.5f * unit);
                canvas.drawArc(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter,
                        LEFT_EDGE, SWEEP * pOuter, false, ring);
                canvas.drawArc(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter,
                        RIGHT_EDGE, -SWEEP * pOuter, false, ring);
                float earAlpha = Math.max(0f, (pOuter - 0.85f) / 0.15f);
                if (earAlpha > 0) {
                    fill.setAlpha((int) (255 * earAlpha));
                    fill.setShader(null);
                    canvas.drawCircle(cx - rOuter * 0.365f, cy + rOuter * 0.866f,
                            2.75f * unit, fill);
                    canvas.drawCircle(cx + rOuter * 0.365f, cy + rOuter * 0.866f,
                            2.75f * unit, fill);
                }
            }

            // 最终态：真实的图标前景（ic_launcher_foreground，与 app 图标同一个 drawable）
            // 交叉淡入盖住 stroke 版——stroke 只是绘制过程的笔触，落定的一帧必须是图标
            // 本身（2026-11-26 用户要求「最终状态与 app 图标一致」）。
            if (foreground != null) {
                float fgAlpha = Math.max(0f,
                        (progress(t, OUTER_START, OUTER_END) - 0.8f) / 0.2f);
                if (fgAlpha > 0) {
                    foreground.setAlpha((int) (255 * fgAlpha));
                    foreground.draw(canvas);
                }
            }

            if (t < TOTAL) {
                postInvalidateOnAnimation();
            } else {
                // 画完淡出完毕：从窗口里摘掉自己。
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) {
                    parent.removeView(this);
                }
                attached.clear();
            }
        }

        private static float progress(long t, long start, long end) {
            if (t <= start) {
                return 0f;
            }
            if (t >= end) {
                return 1f;
            }
            float p = (t - start) / (float) (end - start);
            return new DecelerateInterpolator(1.5f).getInterpolation(p);
        }

        private Shader dotShader(float cx, float cy, float r) {
            return new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    0xFF47A3F8, 0xFF1B5CF0, Shader.TileMode.CLAMP);
        }

        private void ringGradient(float cx, float cy, float r) {
            // SweepGradient 以圆心为轴：整条环是同一个锥形渐变的两半，左右不会各成一色。
            int[] colors = {0xFF1B5CF0, 0xFF47A3F8, 0xFF1B5CF0};
            ring.setShader(new SweepGradient(cx, cy, colors, null));
        }
    }
}
