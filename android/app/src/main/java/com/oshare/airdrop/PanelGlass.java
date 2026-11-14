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
 * <p># 参数设在谁身上：这里踩过一次坑
 *
 * <p>拿到那个 Drawable 之后，自然会以为半径、圆角、颜色都设在 Drawable 上——AOSP 的
 * {@code BackgroundBlurDrawable} 确实如此。但这台机器上不是：它的
 * {@code com.android.internal.graphics.drawable.BackgroundBlurDrawable} 公开方法里**一个模糊
 * 相关的都没有**，只有 Drawable 的通用接口（{@code setAlpha}、{@code setBounds}……）。
 *
 * <p>所有参数都在 {@code com.oplus.view.ViewRootManager} 上：
 *
 * <pre>
 *   setBlurRadius(int)
 *   setCornerRadius(float)                     // 四角同值
 *   setCornerRadius(float,float,float,float)   // 四角各自设
 *   setColor(int)
 *   setBlurParams(OplusBlurParam)
 * </pre>
 *
 * <p>之前的版本把这几条反射发给了 Drawable，于是每一条都静默失败——{@link #invoke} 对「方法
 * 不存在」是返回 false 而不是抛异常，因为它必须容忍 ROM 差异。模糊看起来还在，因为系统给了
 * 一个默认半径；但**圆角一直是 0**，面板顶上两角就是方的，而圆角恰是唯一看得见的那一项。
 * 症状与原因隔了一层，只有把方法表打出来才看得见。
 *
 * <p>所以这里把「控制对象」和「接收背景的对象」分开：Drawable 只能当 background，参数一律
 * 发给 {@code target}——ColorOS 是 manager，AOSP 是 Drawable 自己。
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
    /**
     * 参数发给谁。ColorOS 是 {@code ViewRootManager}，AOSP 是 Drawable 自己。见类注释。
     */
    private final Object target;
    private final Method setBlurRadius;
    private final Method setCornerRadius;
    private final Method setCornerRadiusOne;

    private PanelGlass(Drawable drawable, String source, Object target) {
        this.drawable = drawable;
        this.source = source;
        this.target = target;
        this.setBlurRadius = method(target, "setBlurRadius", int.class);
        this.setCornerRadius = method(target, "setCornerRadius",
                float.class, float.class, float.class, float.class);
        this.setCornerRadiusOne = method(target, "setCornerRadius", float.class);
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
        Object manager = colorOsManager(host);
        if (manager != null) {
            Drawable drawable = invokeDrawable(manager, "getBackgroundBlurDrawable");
            if (drawable != null) {
                return new PanelGlass(drawable, "coloros", manager);
            }
        }
        Drawable drawable = fromAosp(host);
        return drawable == null ? null : new PanelGlass(drawable, "aosp", drawable);
    }

    /** 走的是哪条反射路径。真机上「没模糊」时，第一件事是看它。 */
    String source() {
        return source;
    }

    /** 设给面板的 background。它自己画模糊，不需要再包一层。 */
    Drawable drawable() {
        return drawable;
    }

    /** 模糊半径，屏幕像素。@return 这个 ROM 认不认这条参数 */
    boolean radius(int px) {
        return invoke(setBlurRadius, px);
    }

    /**
     * 圆角。先试四个角各自设（面板是「上两角圆、下两角方」），不行就退回四个角一样。
     *
     * <p>底部那两角在屏幕外面，圆不圆看不出来——所以退回单值时直接用顶部的值，不是妥协。
     *
     * <p>这一条是**看得见的**那一条：模糊由合成器按这块区域的矩形算，圆角只影响这块矩形的
     * 四角。参数发错地方（见类注释），面板顶上两角就是方的——内容被 {@code clipToOutline}
     * 裁成了圆的，模糊却从方角里溢出来一圈，看起来像一层没对齐的雾。
     *
     * @return 这个 ROM 认不认这条参数
     */
    boolean corners(float top, float bottom) {
        if (invoke(setCornerRadius, top, top, bottom, bottom)) {
            return true;
        }
        return invoke(setCornerRadiusOne, top);
    }

    // ------------------------------------------------------------------ 反射

    /**
     * ColorOS 的入口：{@code new ViewRootManager(View)}。
     *
     * <p>返回的是 **manager** 而不是 Drawable——参数要发给它，所以这个引用必须留着。
     * 一加互传用的也是它，所以这台机器上一定有。
     */
    private static Object colorOsManager(View host) {
        try {
            Class<?> type = Class.forName("com.oplus.view.ViewRootManager");
            return type.getConstructor(View.class).newInstance(host);
        } catch (Throwable missing) {
            return null;
        }
    }

    /** 在 manager 上取那条 Drawable。拿不到就是「这个 ROM 没接上」，走 AOSP。 */
    private static Drawable invokeDrawable(Object manager, String name) {
        try {
            Object drawable = manager.getClass().getMethod(name).invoke(manager);
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

    /**
     * 按名字和签名找方法。
     *
     * <p>先走 {@code getMethod}（只找 public）。找不到再翻整条继承链上的 declared——ColorOS
     * 那几个 setter 平时不出现在公开文档里，不一定被标成 public，而「没标」和「不存在」在
     * 调用方看来是一样的静默失败，正是上一次踩坑的形状。
     */
    private static Method method(Object target, String name, Class<?>... signature) {
        if (target == null) {
            return null;
        }
        try {
            Method found = target.getClass().getMethod(name, signature);
            found.setAccessible(true);
            return found;
        } catch (Throwable missing) {
            // 掉到下面翻私有方法。
        }
        for (Class<?> level = target.getClass(); level != null && level != Object.class;
             level = level.getSuperclass()) {
            for (Method candidate : level.getDeclaredMethods()) {
                if (!candidate.getName().equals(name)
                        || !java.util.Arrays.equals(candidate.getParameterTypes(), signature)) {
                    continue;
                }
                try {
                    candidate.setAccessible(true);
                    return candidate;
                } catch (Throwable hidden) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 调一次，成功返回 true。任何异常都是「这个 ROM 没这个方法」，不是错误。 */
    private boolean invoke(Method method, Object... arguments) {
        if (method == null) {
            return false;
        }
        try {
            method.invoke(target, arguments);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }
}
