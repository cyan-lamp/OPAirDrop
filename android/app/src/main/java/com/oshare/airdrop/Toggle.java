package com.oshare.airdrop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

/**
 * 胶囊里那颗拨杆。
 *
 * <p># 为什么不用 {@code android.widget.Switch}
 *
 * <p>这个 app 只用平台 SDK，没有 AppCompat 也没有 Material 库，所以 {@code Switch} 拿到的是
 * {@code Theme.Material} 自带的 {@code Widget.Material.CompoundButton.Switch}：那套 drawable 是
 * Android 5 的九宫格，轨道 24dp 而圆点 32dp，圆点本来就画在轨道外面。九宫格按行高拉伸之后，
 * 圆点比轨道高出一头、还从轨道左端探出去半个身位——在设置行里看起来像控件坏了，不是像开关。
 *
 * <p>换库能把这一颗控件修好，代价是给一个只有两页 UI 的 app 引进一整套资源合并的默认值（见
 * {@code AppTheme} 那段注释）。这颗拨杆本身不到两百行：一条圆角轨道、一颗圆点、一段 180ms 的
 * 位移插值，颜色从 {@link Scheme} 里取。所以自己画。
 *
 * <p># 画法
 *
 * <p>尺寸 50×30dp，轨道半径取高度的一半（真胶囊）。关：{@link Scheme#trackOff}；开：
 * {@link Scheme#accent}。位移用 0…1 的 {@code pos} 一个数表示：轨道颜色按 {@code pos} 插值，
 * 圆点圆心也按它插值，所以拨到一半时颜色和位置是同一个进度，不会一前一后。
 */
final class Toggle extends View {

    /** 松手、点按之后要听的那一下。参数是自己和新的状态。 */
    interface OnChange {
        void onChange(Toggle toggle, boolean checked);
    }

    private static final float WIDTH_DP = 50f;
    private static final float HEIGHT_DP = 30f;
    /** 圆点离轨道边缘留多少：轨道高 30、圆点直径 24，上下各 3。 */
    private static final float PAD_DP = 3f;
    /** 拨过去用多久。比翻页的 200ms 略短：这是一颗开关，不是一次转场。 */
    private static final int DURATION = 180;

    private final Scheme scheme;
    private final float density;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();

    private boolean checked;
    /** 0 = 全在左边（关），1 = 全在右边（开）。动画把它推过去。 */
    private float pos;
    private android.animation.ValueAnimator slide;

    private OnChange onChange;

    Toggle(Context context, Scheme scheme, boolean checked) {
        super(context);
        this.scheme = scheme;
        this.density = context.getResources().getDisplayMetrics().density;
        this.checked = checked;
        this.pos = checked ? 1f : 0f;
        setClickable(true);
        setFocusable(true);
    }

    private float dp(float value) {
        return value * density;
    }

    void setOnChange(OnChange listener) {
        onChange = listener;
    }

    boolean isChecked() {
        return checked;
    }

    /** 只摆位置，不回调——回调和 {@code Switch} 一样，只由人拨才算数。 */
    void setChecked(boolean value) {
        setChecked(value, false);
    }

    private void setChecked(boolean value, boolean fromTouch) {
        if (checked == value && slide == null) {
            return;
        }
        checked = value;
        if (slide != null) {
            slide.cancel();
        }
        slide = android.animation.ValueAnimator.ofFloat(pos, value ? 1f : 0f);
        slide.setDuration(DURATION);
        slide.setInterpolator(new DecelerateInterpolator());
        slide.addUpdateListener(a -> {
            pos = (Float) a.getAnimatedValue();
            invalidate();
        });
        slide.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                slide = null;
            }
        });
        slide.start();
        if (fromTouch && onChange != null) {
            onChange.onChange(this, value);
        }
    }

    @Override
    public boolean performClick() {
        if (!isEnabled()) {
            return false;
        }
        setChecked(!checked, true);
        return super.performClick();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(
                resolveSize(Math.round(dp(WIDTH_DP)), widthSpec),
                resolveSize(Math.round(dp(HEIGHT_DP)), heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float pad = dp(PAD_DP);
        float r = (h - pad * 2f) / 2f;

        // 关到开的轨道颜色是同一条插值上的两个端点，所以拨到一半时颜色和圆点位置同步
        trackPaint.setColor(blend(scheme.trackOff, scheme.accent, pos));
        track.set(0f, 0f, w, h);
        canvas.drawRoundRect(track, h / 2f, h / 2f, trackPaint);

        // 按下时圆点鼓一点：这一行整块都能点，手指下的反馈得落在看得见的那颗东西上
        float grow = isPressed() && isEnabled() ? dp(1f) : 0f;
        float cx = pad + r + pos * (w - pad * 2f - r * 2f);
        thumbPaint.setColor(scheme.thumb);
        canvas.drawCircle(cx, h / 2f, r + grow, thumbPaint);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        // 灰掉就是灰掉：跟随开着的时候这颗拨杆归一加互传管，按不动要看得出来
        setAlpha(enabled ? 1f : 0.4f);
        invalidate();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    /** 两条不透明色之间按 t 混色。t 只取 0…1，不夹会插出越界的通道值。 */
    private static int blend(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF)
                - ((from >>> 24) & 0xFF)) * k);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF)
                - ((from >> 16) & 0xFF)) * k);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF)
                - ((from >> 8) & 0xFF)) * k);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
