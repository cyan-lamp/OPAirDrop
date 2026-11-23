package com.oshare.airdrop;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 「从最近任务划掉」不再等于强停进程。
 *
 * <p>ColorOS 的划掉不是 AOSP 的行为。AOSP 划掉只是把任务从最近列表里去掉，服务是
 * {@code stopWithTask="false"} 就继续跑；这台机器上它会接着补一刀
 * {@code ActivityManagerService.forceStopPackageLocked}，把包整个置成 stopped 状态：
 *
 * <pre>
 * 19:21:56.345  RecentTasks.remove → ActivityTaskSupervisor.cleanUpRemovedTask
 * 19:21:56.347  ActivityManager: Force stopping com.oshare.airdrop appid=10409 user=0: o-stop(40)
 * 19:21:56.348  ActivityManager: Killing 32026:com.oshare.airdrop/u0a409 (adj 430):
 *                                        stop com.oshare.airdrop due to o-stop(40)
 * </pre>
 *
 * <p>代价是「收文件」这件事整个停摆：进程没了，广播没了，iPhone 那边点发送当然什么都不弹。
 * 之前是靠守卫进程每 3 秒把它拉回来，但那是补丁——拉起来还要重新 announce、重新等隧道，
 * 慢的那几十秒才是人真正感觉到的东西。
 *
 * <p><b>所以只拦这一刀。</b>判据是 reason，不是包名：同一份日志里另外两次停止用的是
 * {@code from pid 2887}（adb 与设置里的「强制停止」都走这条），所以
 *
 * <ul>
 *   <li>{@code o-stop(...)} —— 划掉、后台清理。放行：任务照常从最近列表消失，进程和前台
 *       服务留着，卡片立刻能弹。</li>
 *   <li>{@code from pid ...} —— 用户明确要停。原样执行。</li>
 * </ul>
 *
 * <p>后者是这套东西的应急出口，所以它必须一直在：钩子出任何问题，设置里点一下「强制停止」
 * 仍然能真的停掉。另外 {@code persist.opairdrop.keepalive=0} 可以让整个钩子让路（见
 * {@link #enabled()}）。
 *
 * <p><b>这个类跑在 system_server 里。</b>钩子抛异常会把系统框架带下去，所以每一处都
 * 自己兜住，兜不住就 {@code chain.proceed()} —— 退回原行为只是少了这个便利，而
 * 少一个 {@code try} 是一次开机循环。
 */
final class KeepAlive {

    private static final String TAG = "OPAirDrop";

    /** 只认这一个包。别家的 force-stop 一个字节都不动。 */
    private static final String PKG = "com.oshare.airdrop";

    /** 系统框架里的那个类。它在 {@code services.jar}，由 system_server 自己的 loader 加载。 */
    private static final String AMS = "com.android.server.am.ActivityManagerService";

    /**
     * 划掉那一次停止的 reason 前缀。完整串是 {@code o-stop(40)}，40 是 OPPO 自己的原因码；
     * 按前缀认是为了换码之后不失效，而 {@code from pid} 那条不会被误伤。
     */
    private static final String RECENTS_PREFIX = "o-stop(";

    /** 关掉整个钩子的开关，默认开。{@code setprop persist.opairdrop.keepalive 0} 即可让路。 */
    private static final String SWITCH = "persist.opairdrop.keepalive";

    /** 已经放过多少次。只用来打日志，看它是不是真的在生效。 */
    private static volatile int skipped;

    /** 见过的其他 reason 打够几条就停，避免每次划掉都刷一行。 */
    private static volatile int noted;

    private static Method propGet;

    private KeepAlive() {
    }

    static void install(XposedModule module, ClassLoader loader) {
        Class<?> ams;
        try {
            // initialize=false：这个类在 system_server 启动早期就被用到了，这里只是要它的
            // Method 表，不该因为装钩子而触发一次静态初始化。
            ams = Class.forName(AMS, false, loader);
        } catch (Throwable t) {
            Log.e(TAG, "找不到 " + AMS + "，划掉仍然会强停", t);
            return;
        }

        int hooked = 0;
        int index = 0;
        for (Method method : ams.getDeclaredMethods()) {
            if (!"forceStopPackageLocked".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            // 第一个参数是包名，这是唯一不用猜的东西；后面的签名随版本变，交给 reasonOf 找。
            if (params.length < 2 || params[0] != String.class) {
                continue;
            }
            try {
                module.hook(method)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        // 钩子里出任何事都当没钩过：放行原方法最多是「照旧被杀」。
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .setId("opairdrop-keepalive-" + (index++))
                        .intercept(chain -> onForceStop(chain, method));
                hooked++;
                Log.i(TAG, "已钩住 " + method);
            } catch (Throwable t) {
                Log.e(TAG, "钩不上 " + method + "，这一支继续原样强停", t);
            }
        }
        Log.i(TAG, "划掉不再强停：钩住 " + hooked + " 个 forceStopPackageLocked");
    }

    private static Object onForceStop(XposedInterface.Chain chain, Method target) throws Throwable {
        try {
            if (PKG.equals(chain.getArg(0))) {
                String reason = reasonOf(chain, target);
                if (reason != null && reason.startsWith(RECENTS_PREFIX)) {
                    if (!enabled()) {
                        Log.i(TAG, "keepalive 关着，放过这一刀：" + reason);
                        return chain.proceed();
                    }
                    int n = ++skipped;
                    Log.i(TAG, "划掉只收任务，不强停进程（第 " + n + " 次）：" + reason);
                    // forceStopPackageLocked 返回「有没有真的停掉什么」。跳过就等于没停。
                    return target.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }
                if (reason != null && noted < 8) {
                    noted++;
                    // 其他 reason 原样执行，但要记下来：将来「设置里点强制停止还能不能停」
                    // 这个问题，答案就在这些行里。
                    Log.i(TAG, "别的停止原因，照原样执行：" + reason);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "判据出错，交给原方法", t);
        }
        return chain.proceed();
    }

    /**
     * 取 reason。它在签名里的位置随版本挪，所以不写死下标：从后往前找第一个 String 参数
     * （包名在 0，不算）。
     */
    private static String reasonOf(XposedInterface.Chain chain, Method method) {
        Class<?>[] params = method.getParameterTypes();
        for (int i = params.length - 1; i >= 1; i--) {
            if (params[i] == String.class) {
                Object value = chain.getArg(i);
                return value instanceof String ? (String) value : null;
            }
        }
        return null;
    }

    private static boolean enabled() {
        try {
            if (propGet == null) {
                propGet = Class.forName("android.os.SystemProperties")
                        .getMethod("get", String.class, String.class);
            }
            Object value = propGet.invoke(null, SWITCH, "1");
            return !"0".equals(value);
        } catch (Throwable t) {
            // 读不到开关就当它开着：这个钩子的失效方向是「照旧被杀」，不是「停不掉」。
            return true;
        }
    }
}
