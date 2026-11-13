package com.oshare.airdrop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.OverScroller;
import android.widget.ScrollView;

/**
 * 纵向滚动，越过边缘时带一点刚性橡皮筋。
 *
 * <p># 为什么不用系统的 edge effect
 *
 * <p>系统在边缘画一道光或者一层拉伸，而它的位移预算是 48dp 左右——手甩得快时，那一小段在两
 * 三帧里就走完了，读起来是「页面被砸停」。这里把越界量自己攒起来：位移按被拽住的那条边做轴
 * 心拉伸，松手 180ms 直线回位，不回摆。
 *
 * <p># 为什么要自己管 fling
 *
 * <p>{@link ScrollView} 的 scroller 是私有的，而「甩到底之后橡皮筋要弹回来」需要一个可靠的
 * 结束信号。自己持有一个 {@link OverScroller}，在 {@link #computeScroll} 里就能同时拿到位置
 * 与速度，也就能按速度决定弹回来要多深。
 */
final class BounceScrollView extends ScrollView {
    private ValueAnimator bounce;
    private boolean dragging;
    /** 自己的 fling 滚动器：需要「甩完了」这个信号，而 ScrollView 不提供。 */
    private final OverScroller flingScroller;
    /**
     * 累积的越界量，单位是滚动像素（顶部为负，底部为正）。
     *
     * <p>{@link ScrollView} 会把自己的 scrollY 夹在边缘，{@code overScrollBy} 每次只带一帧
     * 的增量，所以越界总量必须在这里自己攒。
     */
    private float overscrollAccum;
    /** 接近刚性的橡皮：越过边缘只有一点点让步。 */
    private static final float RUBBER = 0.06f;
    /** 甩出去的冲击深度 = 速度 × 这个秒数。快甩明显扎进去，轻推几乎不动。 */
    private static final float FLING_GAIN = 0.06f;
    /** 甩出冲击的下潜动画。 */
    private ValueAnimator rubberOut;

    BounceScrollView(Context context) {
        super(context);
        flingScroller = new OverScroller(context);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    private void stopRubberAnims() {
        if (bounce != null) {
            bounce.cancel();
        }
        if (rubberOut != null) {
            rubberOut.cancel();
        }
    }

    /**
     * 甩出冲击：从当前橡皮位置下潜到冲击峰值，结束后接上回弹。
     *
     * <p>这一段替换掉 {@link OverScroller} 自带的边缘弹簧：它只有 48dp 的行程，在甩的速度下
     * 一两帧就到底，视觉上就是「砸停一下」。
     */
    private void startRubberOut(float from, float to) {
        stopRubberAnims();
        rubberOut = ValueAnimator.ofFloat(from, to);
        rubberOut.setInterpolator(new DecelerateInterpolator(2f));
        rubberOut.setDuration(260);
        rubberOut.addUpdateListener(a -> setRubberY((Float) a.getAnimatedValue()));
        rubberOut.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (a == rubberOut) {
                    rubberOut = null;
                    if (!cancelled && !dragging) {
                        bounceBack();
                    }
                }
            }
        });
        rubberOut.start();
    }

    private int scrollRange() {
        View content = getChildAt(0);
        if (content == null) {
            return 0;
        }
        return Math.max(0, content.getHeight() - getHeight()
                + getPaddingTop() + getPaddingBottom());
    }

    @Override
    public void fling(int velocityY) {
        View content = getChildAt(0);
        if (content == null) {
            super.fling(velocityY);
            return;
        }
        // 越界只给固定的一小段：给半个视口时，scroller 自己的弹簧会把整段越界吃掉，硬甩之后
        // 橡皮筋要很久才回来
        flingScroller.fling(getScrollX(), getScrollY(), 0, velocityY,
                0, 0, 0, scrollRange(), 0,
                (int) (48 * getResources().getDisplayMetrics().density + 0.5f));
        postInvalidateOnAnimation();
    }

    /**
     * 橡皮筋位置：以被拽住的那条边为轴，把内容朝拖动方向拉长。
     *
     * <p># 轴心必须落在内容自己的坐标里
     *
     * <p>底部回弹时，屏幕上那条静止的底边在内容坐标里是 {@code content.getHeight()}，不是视口
     * 高度 {@code getHeight()}。设置页比一屏长得多：拿视口高度当轴心，那个点落在屏幕中段，于是
     * 同一次缩放里轴心上方的字往上走、下方的字往下走——屏幕中间凭空出现一条谁也没动的折痕，
     * 读起来就是「轴不在被拉的那条边上」。
     *
     * <p># 为什么不额外平移
     *
     * <p>平移和「以这条边为轴」的缩放叠在一起时，真正不动的那个点被推到屏幕外大约一页高的地方：
     * 被拽的边跟着手指走，对面那条边走两倍，视觉上更像整页被拖走而不是被拽住。只留缩放，被拽
     * 的那条边就真的钉在原处，内容从它开始变长，远端恰好走「越界量 × RUBBER」那么远。
     */
    private void setRubberY(float accum) {
        View content = getChildAt(0);
        if (content == null) {
            return;
        }
        // 硬上限：一直拖、或者甩得很猛，都不能把橡皮拉过一屏
        float height = getHeight();
        float cap = height > 0 ? height : 2400f;
        accum = Math.max(-cap, Math.min(cap, accum));
        overscrollAccum = accum;
        float over = Math.abs(accum) * RUBBER;
        if (over > 0.5f && height > 0) {
            content.setPivotY(accum < 0 ? 0f : content.getHeight());
            content.setScaleY((height + over) / height);
        } else {
            content.setScaleY(1f);
            content.setPivotY(height / 2f);
        }
    }

    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int oldScrollX, int oldScrollY,
            int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY,
            boolean isTouchEvent) {
        View content = getChildAt(0);
        int effective = deltaY;
        if (content != null && deltaY != 0) {
            if (overscrollAccum != 0f) {
                // 橡皮状态只在「滚动确实被钉在对应边缘」时才可信：其他情况（手势打断回弹、
                // 甩动残留）必须重新对齐，否则吸收掉的量会和真实位置越差越远，页面会滚进一片
                // 永久的空白
                boolean pinned = (overscrollAccum < 0f && oldScrollY <= 0)
                        || (overscrollAccum > 0f && oldScrollY >= scrollRangeY);
                if (pinned) {
                    float accum = overscrollAccum + deltaY;
                    if ((overscrollAccum < 0f && accum >= 0f)
                            || (overscrollAccum > 0f && accum <= 0f)) {
                        // 又拖回边缘内侧了：剩下的交给正常滚动，橡皮归零
                        effective = Math.round(accum);
                        setRubberY(0f);
                    } else {
                        effective = 0;
                        float cap = getHeight() > 0 ? getHeight() : 2400f;
                        setRubberY(Math.max(-cap, Math.min(cap, accum)));
                    }
                    stopRubberAnims();
                    return super.overScrollBy(deltaX, effective, oldScrollX, oldScrollY,
                            scrollRangeX, scrollRangeY, 0, 0, isTouchEvent);
                }
                // 状态过期：按真实位置重新对齐，然后走正常的范围内处理
                setRubberY(0f);
            }
            int desired = oldScrollY + deltaY;
            int clamped = Math.max(0, Math.min(scrollRangeY, desired));
            float over = desired - clamped;
            if (over != 0f) {
                // 越过边缘：吸收进橡皮筋
                effective = 0;
                setRubberY(over);
            } else {
                effective = deltaY;
                setRubberY(0f);
            }
            stopRubberAnims();
        }
        return super.overScrollBy(deltaX, effective, oldScrollX, oldScrollY,
                scrollRangeX, scrollRangeY, 0, 0, isTouchEvent);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopRubberAnims();
                dragging = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                bounceBack();
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (flingScroller.computeScrollOffset()) {
            int y = flingScroller.getCurrY();
            int range = scrollRange();
            int clamped = Math.max(0, Math.min(range, y));
            if (clamped != y) {
                // 滚动器跑到了边缘外：y - clamped 是「绝对越界量」，不是一个增量。把它喂进
                // overScrollBy 的增量记账里，每帧都会 accum += 整个偏移，几帧之内就会指数级
                // 放大——这就是硬甩时那一下闪光和错位。
                if (getScrollY() != clamped) {
                    scrollTo(getScrollX(), clamped);
                }
                if (rubberOut != null && rubberOut.isRunning()) {
                    // 下潜动画正在跑，别插手
                } else {
                    float density = getResources().getDisplayMetrics().density;
                    float peak = Math.abs(flingScroller.getCurrVelocity()) * FLING_GAIN;
                    if (peak < 48 * density) {
                        // 轻推：滚动器自己那点弹簧够用了
                        if (bounce != null && bounce.isRunning()) {
                            bounce.cancel();
                        }
                        setRubberY(y - clamped);
                    } else {
                        float cap = getHeight() * 0.35f;
                        float from = overscrollAccum;
                        float target = Math.signum(y - clamped)
                                * Math.min(cap, Math.max(Math.abs(from), peak));
                        flingScroller.forceFinished(true);
                        startRubberOut(from, target);
                    }
                }
            } else if (y != getScrollY()) {
                overScrollBy(0, y - getScrollY(), 0, getScrollY(), 0, range, 0, 0, true);
            } else if (overscrollAccum != 0f) {
                // 滚动器回到了被钉住的边缘，橡皮还有剩余：弹回去
                boolean atEdge = (overscrollAccum < 0f && getScrollY() <= 0)
                        || (overscrollAccum > 0f && getScrollY() >= range);
                if (atEdge) {
                    if (!dragging) {
                        bounceBack();
                    }
                } else {
                    setRubberY(0f);
                }
            }
            postInvalidateOnAnimation();
        } else if (!dragging && overscrollAccum != 0f) {
            // 甩动结束时橡皮还有剩余，说明是卡在边缘了：弹回去
            bounceBack();
        }
    }

    private void bounceBack() {
        View content = getChildAt(0);
        if (content == null) {
            return;
        }
        float accum = overscrollAccum;
        if (accum == 0f) {
            return;
        }
        if (bounce != null && bounce.isRunning()) {
            return;
        }
        // 下潜动画结束时会接上这里；它跑着的时候 computeScroll 每帧都在调，会重复进来
        if (rubberOut != null && rubberOut.isRunning()) {
            return;
        }
        bounce = ValueAnimator.ofFloat(accum, 0f);
        bounce.setInterpolator(new LinearInterpolator());
        bounce.setDuration(180);
        bounce.addUpdateListener(a -> setRubberY((Float) a.getAnimatedValue()));
        bounce.start();
    }
}
