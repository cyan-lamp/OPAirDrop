package com.oshare.airdrop;

import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.Method;

/**
 * 面板背景的实时模糊。
 *
 * <p># 它不是窗口模糊
 *
 * <p>{@code Window.setBackgroundBlurRadius} 模糊的是**整个窗口**：窗口多大，糊多大。我们的窗口
 * 是全屏的（面板要从底边上滑、要能滑出去），于是面板以外那一片也会被糊掉，而且每一帧都要
 * 重新采样整屏——合成线程跟不上，手感就是慢半拍。
 *
 * <p>一加互传那一页不是这么做的。它的面板由一个 {@code COUIBottomSheetDialog} 承载，模糊来自
 * {@code ViewRootManager.getBackgroundBlurDrawable()}：拿一个模糊 Drawable 设成**某个 View 的
 * background**，模糊范围就是这个 View 的边界。于是——
 *
 * <ul>
 *   <li>面板之外的像素一个都不糊；</li>
 *   <li>面板移动（跟手、上滑、回弹）时模糊跟着这个 View 走，实时；</li>
 *   <li>面积只有面板那么大，代价与整屏无关。</li>
 * </ul>
 *
 * <p>那条 Drawable 在 AOSP 里叫 {@code BackgroundBlurDrawable}，由
 * {@code ViewRootImpl.createBackgroundBlurDrawable()} 创建（Android 12 起），ColorOS 只是把
 * 它包了一层材质参数。两者都要用反射：它们是 {@code @hide}，编译期不在 SDK 里，运行期在系统
 * 里。
 *
 * <p># 拿不到是什么样
 *
 * <p>{@link #attach} 返回 {@code null}。调用方退回纯色面板——一块不透明的纸，不是玻璃，但
 * 能用。这条退路必须留着：它取决于 ROM 有没有实现那个隐藏方法，而这不是我们能控制的。
 */
final class PanelGlass {

    /** 系统造出来的模糊 Drawable。设给面板的 background，它自己铺满并跟着 View 走。 */
    private final Drawable drawable;
    /** 这条 Drawable 是从哪个入口拿到的：{@code coloros} / {@code aosp}。只用于日志。 */
    private final String source;
    private final Method setColor;
    private final Method setBlurRadius;
    private final Method setCornerRadius;
    private final Method setCornerRadiusOne;

    private PanelGlass(Drawable drawable, String source) {
        this.drawable = drawable;
        this.source = source;
        this.setColor = method(drawable, "setColor", int.class);
        this.setBlurRadius = method(drawable, "setBlurRadius", int.class);
        this.setCornerRadius = method(drawable, "setCornerRadius",
                float.class, float.class, float.class, float.class);
        this.setCornerRadiusOne = method(drawable, "setCornerRadius", float.class);
    }

    /**
     * 给 {@code host} 造一块会跟着它走的玻璃。
     *
     * <p>必须在 View **已经 attached** 之后调用：{@code ViewRootImpl} 是 attach 的产物，早于
     * 那一刻两者都还不在。
     *
     * @return 拿不到就 {@code null}，调用方要走纯色退路
     */
    static PanelGlass attach(View host) {
        Drawable drawable = fromColorOs(host);
        if (drawable != null) {
            return new PanelGlass(drawable, "coloros");
        }
        drawable = fromAosp(host);
        return drawable == null ? null : new PanelGlass(drawable, "aosp");
    }

    /** 走的是哪条反射路径。真机上「没模糊」时，第一件事是看它。 */
    String source() {
        return source;
    }

    /** 设给面板的 background。它自己画模糊，不需要再包一层。 */
    Drawable drawable() {
        return drawable;
    }

    /** 模糊半径，屏幕像素。 */
    void radius(int px) {
        invoke(setBlurRadius, px);
    }

    /** 叠在模糊之上的颜色。给它页底色的半透明版：既压住背后的杂乱，又让模糊透上来。 */
    void color(int argb) {
        invoke(setColor, argb);
    }

    /**
     * 圆角。先试四个角各自设（面板是「上两角圆、下两角方」），不行就退回四个角一样。
     *
     * <p>底部那两角在屏幕外面，圆不圆看不出来——所以退回单值时直接用顶部的值，不是妥协。
     */
    void corners(float top, float bottom) {
        if (invoke(setCornerRadius, top, top, bottom, bottom)) {
            return;
        }
        invoke(setCornerRadiusOne, top);
    }

    // ------------------------------------------------------------------ 反射

    /**
     * ColorOS 的入口。一加互传用的就是它，所以这台机器上一定有。
     *
     * <p>{@code ViewRootManager(View)} 构造，{@code getBackgroundBlurDrawable()} 取 Drawable。
     * 取到之后半径与圆角仍然设在 Drawable 自己身上，和 AOSP 那条路一致。
     */
    private static Drawable fromColorOs(View host) {
        try {
            Class<?> type = Class.forName("com.oplus.view.ViewRootManager");
            Object manager = type.getConstructor(View.class).newInstance(host);
            Object drawable = type.getMethod("getBackgroundBlurDrawable").invoke(manager);
            return drawable instanceof Drawable ? (Drawable) drawable : null;
        } catch (Throwable missing) {
            return null;
        }
    }

    /** AOSP 的入口：{@code View.getViewRootImpl().createBackgroundBlurDrawable()}。 */
    private static Drawable fromAosp(View host) {
        try {
            Method root = View.class.getDeclaredMethod("getViewRootImpl");
            root.setAccessible(true);
            Object viewRoot = root.invoke(host);
            if (viewRoot == null) {
                return null;
            }
            Method create = viewRoot.getClass()
                    .getDeclaredMethod("createBackgroundBlurDrawable");
            create.setAccessible(true);
            Object drawable = create.invoke(viewRoot);
            return drawable instanceof Drawable ? (Drawable) drawable : null;
        } catch (Throwable missing) {
            return null;
        }
    }

    private static Method method(Object target, String name, Class<?>... signature) {
        try {
            Method found = target.getClass().getMethod(name, signature);
            found.setAccessible(true);
            return found;
        } catch (Throwable missing) {
            return null;
        }
    }

    /** 调一次，成功返回 true。任何异常都是「这个 ROM 没这个方法」，不是错误。 */
    private boolean invoke(Method target, Object... arguments) {
        if (target == null) {
            return false;
        }
        try {
            target.invoke(drawable, arguments);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }
}
