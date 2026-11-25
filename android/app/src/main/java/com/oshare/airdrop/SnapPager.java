package com.oshare.airdrop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.HorizontalScrollView;

/**
 * 三页的横向翻页器：每页和视口一样宽，跟手移动，松手吸附到最近的一页。
 *
 * <p># 为什么不是 ViewPager
 *
 * <p>翻页这件事只有三个要求：页与页之间跟手、松手吸附、被底栏拖动时能停在两页之间的任意小数
 * 位置。前两条 {@code ViewPager2} 也能做，第三条不能——它的位置是整页对齐的，拖动过程中只能
 * 靠动画近似，而底栏的跟手反馈要求「手指在哪里，页就在哪里」。
 *
 * <p># 为什么自己接管触摸
 *
 * <p>横向一页一页翻，而纵向要留给页内的滚动。系统的横滑只允许一次滑过任意距离，手指一甩就
 * 可能跨两页；这里把可移动范围限制在「按下位置前后各一页」，超出的部分转成橡皮筋位移，于是
 * 一次手势最多只翻一页。
 *
 * <p># 为什么过冲要既位移又拉伸
 *
 * <p>只位移看起来像内容整体滑开了一条缝；只拉伸看不出边缘在哪。位移加宽（以被拉的那条边为
 * 轴心）才读得出「这块布被拽住了」。拉伸量按可见宽度算，不是按三页总宽——按总宽算出来的比例
 * 只有千分之几，肉眼看不见。
 */
final class SnapPager extends HorizontalScrollView {
    /** 吸附完成后报告落在哪一页。 */
    interface PageListener {
        void onPage(int index);
    }

    /** 每一帧滚动位置变化都报告一次，浮动玻璃好按新位置重拍霜面。 */
    interface ScrollListener {
        void onScroll();
    }

    private PageListener onPage;
    private ScrollListener onScroll;
    /** 一页的宽度。0 表示还没测量过。外壳用它把滚动位置换算成小数页位置。 */
    int pageWidth = 0;
    private int currentPage = 0;
    /** 还没测量就要求跳页时的重试预算：有上限，否则会一直往消息队列里塞自己。 */
    private int anchorRetries;
    /** 第一次真实布局是否已经完成。之后宽度再变（旋转）就要重新对位。 */
    private boolean firstLayoutDone;
    private VelocityTracker tracker;

    private float downX;
    private float downY;
    /** 这一次手势归谁，见下面的 AXIS_*。判定之后整条手势都不再改判。 */
    private int axis = AXIS_NONE;
    /** 还没判定方向。 */
    private static final int AXIS_NONE = 0;
    /** 横滑：归翻页器。 */
    private static final int AXIS_HORIZONTAL = 1;
    /** 纵向：归页内那张滚动表，翻页器整条手势都不碰。 */
    private static final int AXIS_VERTICAL = 2;
    /** 方向判定的门槛（系统那 8dp 的 touch slop）。 */
    private final int slop;
    private int startScroll;
    private ValueAnimator settle;
    /**
     * 正在吸附去的那一像素位置；{@code -1} 表示没有吸附在跑。
     *
     * <p>存在的理由见 {@link #scrollToPage}：吸附动画一启动就会把落点报给外壳，外壳接着会
     * 要求「立刻跳到第 N 页」。如果照办，页面会先跳到终点、下一帧又被动画拉回半路重走一遍，
     * 松手那一下看起来就像被格了一下。记住落点，同一个落点的立即跳转直接忽略。
     */
    private int settleTarget = -1;
    private ValueAnimator bounce;
    /** 越过边缘的那部分位移里，有多少真的透进视图。越小越硬。 */
    private static final float RUBBER = 0.06f;

    SnapPager(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setOnPage(PageListener listener) {
        onPage = listener;
    }

    void setOnScrollListener(ScrollListener listener) {
        onScroll = listener;
    }

    /**
     * 测量之前先把每一页的宽度钉死。
     *
     * <p>{@link HorizontalScrollView} 用 UNSPECIFIED 的宽度去量它的子条，于是子条里那些
     * {@code MATCH_PARENT} 的页在第一次布局时会退化成「按内容包住」——内核版本号那种很长的
     * 字符串会把那一页撑出屏幕，而屏幕会先画出一帧错位的画面，下一帧才被 {@code onSizeChanged}
     * 纠正。先钉宽度，第一帧就是对的，横竖屏都一样。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width > 0 && width != pageWidth
                && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                && windowRotationAgrees(width, heightMeasureSpec)) {
            int previous = pageWidth;
            pageWidth = width;
            pinPageWidths(width);
            // 旋转之后（configChanges 让 Activity 活着）滚动位置还停在「旧页宽 × 当前页」，
            // 屏幕上是两页各露一条。布局落定后重新对一次位。
            if (previous > 0 && firstLayoutDone) {
                post(() -> scrollToPage(currentPage, false));
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * 窗口的宽高比和屏幕的旋转是否一致。
     *
     * <p>冷启动时系统会先量一次「旋转还没生效」的尺寸（例如竖屏里量出 2376×1080）。按那个尺
     * 寸钉页宽会闪一帧横过来的布局。这种情况直接跳过，等马上就会来的那次正确测量。用宽高比而
     * 不是「宽 > 高就跳过」，是因为真横屏时宽本来就该大于高。
     */
    private boolean windowRotationAgrees(int width, int heightMeasureSpec) {
        Display display = getDisplay();
        if (display == null) {
            return true;
        }
        boolean windowLandscape = width > MeasureSpec.getSize(heightMeasureSpec);
        boolean rotationLandscape = display.getRotation() == Surface.ROTATION_90
                || display.getRotation() == Surface.ROTATION_270;
        return windowLandscape == rotationLandscape;
    }

    private void pinPageWidths(int width) {
        ViewGroup content = (ViewGroup) getChildAt(0);
        if (content == null) {
            return;
        }
        for (int i = 0; i < content.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = content.getChildAt(i).getLayoutParams();
            lp.width = width;
            content.getChildAt(i).setLayoutParams(lp);
        }
    }

    /**
     * 只抢横滑，纵向一律放过。
     *
     * <p>不能把判定交给 {@code HorizontalScrollView} 自己：它只看横向位移有没有超过 slop，
     * 完全不看纵向。手指往上翻页内正文时，横向总会抖出十来个像素，于是每一次往上滑都被判成往
     * 左翻页，正文一辈子滚不动。这里改成比两个方向谁大，并且一旦判成纵向就不再改判——否则手
     * 指斜着滑到一半横向分量变大，页面会在正文滚到一半时突然跳页。
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 新手势先不判方向，等第一次 MOVE 说明。锚点在这里取：一旦这里判成横滑，
                // onTouchEvent 拿到的是从后面某次 MOVE 开始的流（系统不会补一个 DOWN），
                // 那时手指位置和滚动位置已经错开，取不到配对的锚点。
                axis = AXIS_NONE;
                downX = event.getX();
                downY = event.getY();
                startScroll = getScrollX();
                obtainTracker(event);
                // 吸附动画不在这里取消（2026-11-25 修「切页偶发停在两页之间」）：切页动画
                // 进行中的任何触摸都会先送来 DOWN，原来在这一下就把动画掐掉，之后判成
                // 纵轴（人只是想滚动内容）就再也没有吸附兜底——停在两页之间。取消推迟到
                // 横轴判定成立的那一刻。
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (axis == AXIS_VERTICAL) {
                    return false;
                }
                if (axis == AXIS_HORIZONTAL) {
                    return true;
                }
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                if (dx > slop && dx > dy) {
                    axis = AXIS_HORIZONTAL;
                    // 从「接管的那一刻」重新对锚：接管点已经离按下点有一整个 slop 的距离，
                    // 继续用按下点当锚会让内容在这一帧里凭空平移 slop 那么远。
                    downX = event.getX();
                    downY = event.getY();
                    startScroll = getScrollX();
                    // 正在跑的吸附动画在这里才取消（2026-11-25 修「切页偶发停在两页之间」）：
                    // 原来在 ACTION_DOWN 就取消，切页动画进行中手指一按（哪怕只是想纵向滚动
                    // 内容）动画就没了，随后判成纵轴的话谁也不会再吸附——页面停在两页之间。
                    // 横轴成立说明新手势真的要接管翻页，这时取消才安全。
                    if (settle != null) {
                        settle.cancel();
                    }
                    if (bounce != null) {
                        bounce.cancel();
                    }
                    if (tracker != null) {
                        tracker.addMovement(event);
                    }
                    return true;
                }
                if (dy > slop && dy >= dx) {
                    axis = AXIS_VERTICAL;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                axis = AXIS_NONE;
                return false;
            default:
                return false;
        }
    }

    /** 换一条新手势：旧的速度采样丢掉，用这次的按下点重新开一段。 */
    private void obtainTracker(MotionEvent down) {
        if (tracker != null) {
            tracker.recycle();
        }
        tracker = VelocityTracker.obtain();
        tracker.addMovement(down);
    }

    @Override
    protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        if (onScroll != null) {
            onScroll.onScroll();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        firstLayoutDone = true;
        // onMeasure 已经钉过页宽（旋转时还重新对过位）；这里兜住那些绕开 EXACTLY 测量的尺寸
        // 变化。
        if (width > 0 && width != pageWidth) {
            pageWidth = width;
            // 正在跑的吸附动画是朝「旧页宽」的坐标去的，放着不管会停在两页中间
            if (settle != null) {
                settle.cancel();
            }
            if (bounce != null) {
                bounce.cancel();
            }
            pinPageWidths(width);
            scrollToPage(currentPage, false);
        }
    }

    /**
     * 跳到某一页。
     *
     * <p>{@code smooth} 为假时是「立刻落位」，但有一个例外：吸附动画正朝同一个位置走的时候
     * 直接返回。松手的顺序是「起吸附动画 → 报告落点 → 外壳要求跳到落点」，若不认这个例外，
     * 中间那一下会把页面按到终点、随后动画又把它拉回松手的位置重走，读起来像卡了一帧。
     */
    void scrollToPage(int index, boolean smooth) {
        currentPage = index;
        if (pageWidth > 0) {
            anchorRetries = 0;
            int target = index * pageWidth;
            if (smooth) {
                animateScrollTo(target);
                return;
            }
            if (settle != null && settleTarget == target) {
                return;
            }
            if (settle != null) {
                settle.cancel();
                settleTarget = -1;
            }
            if (bounce != null) {
                bounce.cancel();
            }
            scrollTo(target, 0);
            return;
        }
        if (index != 0 && anchorRetries < 10) {
            // 第一次布局之前就被调用（例如 retheme 之后立刻恢复当前页）：几何还不知道，
            // 在消息循环上重试，直到测量把 pageWidth 钉下来。
            anchorRetries++;
            post(() -> scrollToPage(currentPage, false));
        }
    }

    /**
     * 慢一点的吸附。
     *
     * <p>系统横滑的收尾固定 ~250ms，读起来像被拽了一下。这里换成 460ms 的减速曲线；结束回调
     * 再把位置钉死在目标上——中途掉帧时最后一帧可能停在离页边界差几像素的地方，屏幕上就是两
     * 页各露一条。
     */
    private void animateScrollTo(int target) {
        if (settle != null) {
            settle.cancel();
        }
        settleTarget = target;
        settle = ValueAnimator.ofInt(getScrollX(), target);
        settle.setInterpolator(new DecelerateInterpolator(1.6f));
        settle.setDuration(460);
        settle.addUpdateListener(a -> scrollTo((Integer) a.getAnimatedValue(), 0));
        settle.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (a == settle) {
                    settle = null;
                    settleTarget = -1;
                    if (!cancelled) {
                        scrollTo(target, 0);
                    }
                }
            }
        });
        settle.start();
    }

    /**
     * 越过边缘的橡皮筋：沿拖动方向平移并拉伸整条内容。
     *
     * <p>拉伸量按可见宽度算，轴心钉在被越过的那条边上——于是靠近的那条边跟着手指走，内容朝拖
     * 动方向变长。
     */
    private void setRubber(float shift) {
        View content = getChildAt(0);
        if (content == null) {
            return;
        }
        content.setTranslationX(shift);
        float visible = getWidth();
        float over = Math.abs(shift);
        if (over > 0.5f && visible > 0) {
            content.setPivotX(shift > 0 ? getScrollX() : getScrollX() + visible);
            content.setScaleX((visible + over) / visible);
        } else {
            content.setScaleX(1f);
            content.setPivotX(visible / 2f);
        }
        if (onScroll != null) {
            onScroll.onScroll();
        }
    }

    /**
     * 由底栏驱动的连续翻页：跟随给定的小数页位置（0..2）实时移动。
     *
     * <p>底栏和正文的宽度差很多，所以它交给这里的永远是「小数页位置」而不是像素：底栏自己把
     * 手指位移换算成页的百分比，这里只负责把它变成滚动位置。
     */
    void scrubTo(float position) {
        if (pageWidth <= 0) {
            return;
        }
        if (settle != null) {
            settle.cancel();
        }
        if (bounce != null) {
            bounce.cancel();
        }
        View content = getChildAt(0);
        int stripMax = content == null ? 0 : Math.max(0, content.getWidth() - pageWidth);
        int x = Math.round(position * pageWidth);
        setRubber(0f);
        scrollTo(Math.max(0, Math.min(stripMax, x)), 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 走到这里说明按下时没有子视图要吃它（页面里没有可滚的东西），整条手势都
                // 归翻页器。若那张滚动表吃掉了按下，这里根本不会被叫到，方向由
                // onInterceptTouchEvent 判。
                // 吸附动画不在这里取消（同 onInterceptTouchEvent）：等横轴成立再接管。
                axis = AXIS_HORIZONTAL;
                downX = event.getX();
                downY = event.getY();
                startScroll = getScrollX();
                if (tracker == null) {
                    obtainTracker(event);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (axis != AXIS_HORIZONTAL) {
                    return false;
                }
                if (tracker == null) {
                    obtainTracker(event);
                }
                tracker.addMovement(event);
                // 横轴成立（第一个够远的 MOVE）：这时才取消吸附动画，理由见 onInterceptTouchEvent。
                if (settle != null) {
                    settle.cancel();
                }
                if (bounce != null) {
                    bounce.cancel();
                }
                float desired = startScroll + (downX - event.getX());
                ViewGroup content = (ViewGroup) getChildAt(0);
                int stripMax = content == null
                        ? 0 : Math.max(0, content.getWidth() - getWidth());
                // 一次手势只许走一页，超出部分按橡皮筋抵抗
                float low = Math.max(0, startScroll - pageWidth);
                float high = Math.min(stripMax, startScroll + pageWidth);
                float clamped = Math.max(low, Math.min(high, desired));
                scrollTo((int) clamped, 0);
                setRubber((clamped - desired) * RUBBER);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                axis = AXIS_NONE;
                float velocity = 0f;
                if (tracker != null) {
                    tracker.addMovement(event);
                    tracker.computeCurrentVelocity(1000, ViewConfiguration
                            .get(getContext()).getScaledMaximumFlingVelocity());
                    velocity = tracker.getXVelocity();
                    tracker.recycle();
                    tracker = null;
                }
                // 刚性释放：拉出去的那一块直接弹回原状，不回摆
                View content = getChildAt(0);
                float shift = content == null ? 0f : content.getTranslationX();
                if (shift != 0f) {
                    if (bounce != null) {
                        bounce.cancel();
                    }
                    bounce = ValueAnimator.ofFloat(shift, 0f);
                    bounce.setInterpolator(new LinearInterpolator());
                    bounce.setDuration(180);
                    bounce.addUpdateListener(a -> setRubber((Float) a.getAnimatedValue()));
                    bounce.start();
                }
                snap(velocity);
                return true;
            }
            default:
                return true;
        }
    }

    /**
     * 带速度的吸附：甩一下正好翻一页。
     *
     * <p>拖动本身也翻不过一页（见 ACTION_MOVE 的夹取），所以一次手势最多换一页。
     */
    private void snap(float velocity) {
        if (pageWidth <= 0) {
            return;
        }
        float position = (float) getScrollX() / pageWidth;
        int pageCount = ((ViewGroup) getChildAt(0)).getChildCount();
        int minFling = ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity();
        int target = Math.abs(velocity) > minFling
                ? (velocity < 0 ? currentPage + 1 : currentPage - 1)
                : Math.round(position);
        target = Math.max(currentPage - 1, Math.min(currentPage + 1, target));
        target = Math.max(0, Math.min(pageCount - 1, target));
        if (target != currentPage || getScrollX() != target * pageWidth) {
            animateScrollTo(target * pageWidth);
            currentPage = target;
            if (onPage != null) {
                onPage.onPage(target);
            }
        }
    }
}
