package com.oshare.airdrop;

import android.content.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * LSPosed 框架的连接与状态，供首页环境板块展示。
 *
 * <p>用 libxposed 的**官方服务接口**（{@code XposedServiceHelper}）：模块应用绑到框架的
 * service，拿到 {@link XposedService} 后可以读框架版本、查作用域、申请作用域——申请时
 * LSPosed 会弹系统确认框，批准后 scope 事件回调。这是 LSPosed 自己的接口，不走 root、
 * 不碰它的数据库。
 *
 * <p>绑定是异步的：{@link #connect} 在 Application onCreate 注册监听，框架在（也可能不在，
 * 比如没装 LSPosed 或模块未启用）之后回调 {@link Listener#onChanged}，UI 收到后重画。
 * 状态缓存在静态字段里，首页每次构建直接读。
 *
 * <p>依赖说明：{@code io.github.libxposed:service} 是 implementation（普通应用进程里用，
 * 必须进包）；它不夹带 api 包的类，与 FluidModule 用的框架注入版 api 不冲突。
 */
public final class Lsp {

    /** FluidModule 的作用域声明（src/main/resources/META-INF/xposed/scope.list）。 */
    public static final String SCOPE_SYSTEMUI = "com.android.systemui";
    public static final String SCOPE_SYSTEM = "system";

    /** 状态变化（绑定/掉线/scope 变化）时通知 UI 重画。 */
    public interface Listener {
        void onChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile XposedService service;
    /** 最近一次 getScope 的结果；未绑定时 null。 */
    private static volatile List<String> scope;

    /** 注册框架服务监听。重复调用安全：helper 内部幂等，listener 只挂一次。 */
    public static void connect(Context context) {
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService bound) {
                service = bound;
                refreshScope();
                notifyChanged();
            }

            @Override
            public void onServiceDied(XposedService dead) {
                service = null;
                scope = null;
                notifyChanged();
            }
        });
    }

    /** UI 订阅状态变化；回调发生在主线程不保证，UI 侧自行 post。 */
    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    /** 框架服务连上了没有。连上才有版本号与作用域可谈。 */
    public static boolean framework() {
        return service != null;
    }

    /** 重读一次作用域（行点击时刷新缓存，不触发事件）。 */
    public static void refresh() {
        refreshScope();
    }

    /** 作用域是否齐全：systemui 与 system 两条都在。未连接时 false。 */
    public static boolean scoped() {
        List<String> current = scope;
        return current != null && current.contains(SCOPE_SYSTEMUI) && current.contains(SCOPE_SYSTEM);
    }

    /** 版本行：「LSPosed 1.9.7 · API 102」。未连接时空串。 */
    public static String versionLine() {
        XposedService s = service;
        if (s == null) {
            return "";
        }
        return "LSPosed " + s.getFrameworkVersion() + " · API " + s.getApiVersion();
    }

    /** 申请缺失的作用域；LSPosed 会弹系统确认框，结果走 scope 事件回调。 */
    public static void requestScope() {
        XposedService s = service;
        if (s == null) {
            return;
        }
        java.util.List<String> want = new java.util.ArrayList<>();
        List<String> current = scope;
        if (current == null || !current.contains(SCOPE_SYSTEMUI)) {
            want.add(SCOPE_SYSTEMUI);
        }
        if (current == null || !current.contains(SCOPE_SYSTEM)) {
            want.add(SCOPE_SYSTEM);
        }
        if (want.isEmpty()) {
            return;
        }
        s.requestScope(want, new XposedService.OnScopeEventListener() {
            @Override
            public void onScopeRequestApproved(List<String> approved) {
                refreshScope();
                notifyChanged();
            }

            @Override
            public void onScopeRequestFailed(String reason) {
                notifyChanged();
            }
        });
    }

    private static void refreshScope() {
        XposedService s = service;
        scope = s == null ? null : s.getScope();
    }

    private static void notifyChanged() {
        for (Listener listener : LISTENERS) {
            listener.onChanged();
        }
    }

    private Lsp() {
    }
}
