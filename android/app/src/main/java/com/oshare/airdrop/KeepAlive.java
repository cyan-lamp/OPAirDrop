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
 * <p><b>所以只拦这一刀。判据是「谁发起的」</b>，不是包名，也不是日志里那串 {@code o-stop(40)}：
 * 那串字是 Athena 清理框架自己打印的标记，根本没进 AMS 的参数表。真实的调用链是
 *
 * <pre>
 * OplusAthenaAmManager.forceStopPackage                 ← 划掉的真正发起者，在 system_server 内
 *   → AMS.forceStopPackage(String,int)                  reason 这个重载没有
 *   → AMS.forceStopPackageLocked(...,"from pid 4535 (system)",10)   ← 4535 就是 system_server
 * </pre>
 *
 * <p>AMS 那层的 reason 是它用 {@code Binder.getCallingPid()} 现拼的，于是 pid 本身就是答案：
 *
 * <ul>
 *   <li>reason 里的 pid == 自己 —— 划掉、后台清理。放行：任务照常从最近列表消失，进程和
 *       前台服务留着，卡片立刻能弹。</li>
 *   <li>reason 里的 pid == 别人 —— 设置里的「强制停止」、adb（实测是 {@code from pid 2887}）。
 *       原样执行。</li>
 * </ul>
 *
 * <p>后者是这套东西的应急出口，所以它必须一直在：钩子出任何问题，设置里点一下「强制停止」
 * 仍然能真的停掉。另外 {@code persist.opairdrop.keepalive=0} 可以让整个钩子让路（见
 * {@link #enabled()}）。
 *
 * <p><b>还有第二手要挡：{@code FLAG_STOPPED}。</b>光不杀进程不够，2026-11-23 21:29 实测：
 * 划掉之后 {@code pid} 一个数都没变，但 {@code dumpsys package} 里 {@code stopped=true}。
 * 这个标记一立，包就「从没启动过」——隐式广播收不到、JobScheduler 不跑、被系统当成空闲态
 * 下次一起收走。所以跳过后还要把它抹回来，见 {@link #scheduleStoppedReset()}。
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
     * 划掉那一刀留下的指纹：reason 里的 pid <b>就是 system_server 自己</b>。
     *
     * <p>原以为能直接拿到 {@code o-stop(40)}，2026-11-23 21:05 的调用栈证明拿不到 ——
     * 那串字是 Athena 清理框架自己打印的标记，根本没进 AMS 的参数表：
     *
     * <pre>
     * OplusAthenaAmManager.forceStopPackage          ← prockill.clear 模块，划掉的真正发起者
     *   → AMS.forceStopPackage(String,int)                     reason 没有这个参数
     *   → AMS.forceStopPackage(String,int,int,String)          reason = null
     *   → AMS.forceStopPackageLocked(...,"from pid 4535 (system)",10)   ← 4535 = system_server
     *   → AMS.forceStopPackageInternalLocked(...,"from pid 4535 (system)",10,-10000)
     * </pre>
     *
     * <p>所以判据换成「发起者是不是系统自己」：{@code forceStopPackageLocked} 那一层的 reason
     * 是 AMS 用 {@code Binder.getCallingPid()} 现拼的，系统内部直接调用的场合拼出来就是自己的
     * pid。设置里的「强制停止」和 adb 是跨进程调进来的，pid 是别人的。
     */
    private static final java.util.regex.Pattern REASON_PID =
            java.util.regex.Pattern.compile("from pid (\\d+)");

    /** 关掉整个钩子的开关，默认开。{@code setprop persist.opairdrop.keepalive 0} 即可让路。 */
    private static final String SWITCH = "persist.opairdrop.keepalive";

    /** 已经放过多少次。只用来打日志，看它是不是真的在生效。 */
    private static volatile int skipped;

    /** 见过的外部发起打够几条就停，避免每次划掉都刷一行。 */
    private static volatile int noted;

    /** {@code stopped} 抹回来成功多少次。只用来打日志。 */
    private static volatile int restored;

    /** 抹失败了几次。前三次要留下堆栈，之后静默 —— 这不该成为刷屏源。 */
    private static volatile int restoreFails;

    /**
     * 调用链深度 + 这条链上跳过几刀。{@code [0]} 深度，{@code [1]} 跳过数。
     *
     * <p>ThreadLocal 而不是普通字段：不同包（或同一时刻不同用户）的 force-stop 可能同时在
     * 不同 binder 线程上跑，共享一个计数器会互相把深度减到负、或者替别人收尾。
     */
    private static final ThreadLocal<int[]> NEST = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[2];
        }
    };

    private static Method propGet;

    private static Method setStopped;

    /** 延迟补刀用的 handler，懒建。 */
    private static volatile android.os.Handler retry;

    /**
     * 日志出口。{@code android.util.Log} 只写 logcat，而 system_server 开机那两分钟里主缓冲
     * 会被刷满 —— 2026-11-23 20:52 那轮就是靠这个把「钩子到底装上没」整个丢掉，只能靠
     * 「pid 变了」反推它没装。{@link XposedModule#log} 走 lspd 的 verbose 文件，不受缓冲影响。
     */
    private static volatile XposedModule host;

    /**
     * 装过没有。{@code onSystemServerStarting} 与 {@code onPackageReady} 是两个入口，
     * 只要两个都在 system_server 侧触发，就会对同一批 {@code Method} 再叠一层 hook ——
     * 那意味着一次划掉打两行日志、{@link #skipped} 翻倍，排查时全是噪音。
     *
     * <p>只在拿到 AMS 之后才置位，不放在方法开头：万一这个入口给的 loader 解析不了
     * {@code services.jar}（比如退化成了某个子 loader），另一个入口应该还有机会重试；
     * 反过来「先占位」会把唯一成功的那次也堵死。
     */
    private static boolean installed;

    private KeepAlive() {
    }

    /** 两路都写：文件日志留档（开机不错过），logcat 方便实时看。 */
    private static void info(String message) {
        Log.i(TAG, message);
        XposedModule module = host;
        if (module != null) {
            try {
                module.log(Log.INFO, TAG, message);
            } catch (Throwable ignored) {
                // 框架日志都写不进去就算了，logcat 那条还在。
            }
        }
    }

    private static void warn(String message, Throwable t) {
        Log.e(TAG, message, t);
        XposedModule module = host;
        if (module != null) {
            try {
                module.log(Log.ERROR, TAG, message, t);
            } catch (Throwable ignored) {
                // 同上。
            }
        }
    }

    static synchronized void install(XposedModule module, ClassLoader loader) {
        host = module;
        if (installed) {
            info("system_server 这一侧已经装过，跳过这个重复入口，loader=" + loader);
            return;
        }

        // 先打这一行。没有它就无法区分「模块根本没进 system_server」和「进了但类没找到」——
        // 2026-11-23 19:55 那次就是靠「这里一行都没有」才判断出问题在注入这一层。
        info("system_server 这一侧进来了，loader=" + loader);

        Class<?> ams;
        try {
            // initialize=false：这个类在 system_server 启动早期就被用到了，这里只是要它的
            // Method 表，不该因为装钩子而触发一次静态初始化。
            ams = Class.forName(AMS, false, loader);
        } catch (Throwable t) {
            warn("找不到 " + AMS + "，划掉仍然会强停", t);
            return;
        }
        installed = true;

        int hooked = 0;
        int index = 0;
        for (Method method : ams.getDeclaredMethods()) {
            // 名字全收，不按签名筛。
            //
            // 原来只认「params[0] == String」，2026-11-23 20:46 实测证明那是个错误的前提：
            // 划掉那次进去的调用，两个 String 参数只有 [com.oshare.airdrop, from pid 4504 (system)]，
            // 真正的原因码 o-stop(40) 在 ActivityManager 下一行才打印出来 —— 也就是说带原因码的
            // 是另一个重载，而它被上面那个条件挡在门外了。包名到底在第几个参数、是 String 还是
            // String[]，交给运行时的 {@link #indexOfPkg} 去认。
            if (!method.getName().startsWith("forceStop")) {
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
                info("已钩住 " + method.toGenericString());
            } catch (Throwable t) {
                warn("钩不上 " + method + "，这一支继续原样强停", t);
            }
        }
        info("划掉不再强停：钩住 " + hooked + " 个 forceStop*");
    }

    /**
     * 包名在第几个参数。认 String 与 String[] 两种；{@code null} 表示这次调用跟我们无关。
     */
    private static int indexOfPkg(XposedInterface.Chain chain, Method method) {
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (params[i] == String.class) {
                Object value = chain.getArg(i);
                if (PKG.equals(value)) {
                    return i;
                }
            } else if (params[i] == String[].class) {
                Object value = chain.getArg(i);
                if (value instanceof String[]) {
                    for (String s : (String[]) value) {
                        if (PKG.equals(s)) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }

    private static Object onForceStop(XposedInterface.Chain chain, Method target) throws Throwable {
        // 一次划掉会依次进 4 个重载，它们是一条嵌套调用链。只有最外层那次返回之后，
        // 整条链才算走完 —— 标记 stopped 的那一行可能在外层、在里层跳过之后才执行，
        // 所以抹标记不能放在跳过的那一层，必须放到最外层收尾。
        int[] nest = NEST.get();
        nest[0]++;
        try {
            if (indexOfPkg(chain, target) >= 0) {
                int caller = callerPid(chain, target);
                if (caller == android.os.Process.myPid()) {
                    if (!enabled()) {
                        info("keepalive 关着，放过这一刀：from pid " + caller);
                        return chain.proceed();
                    }
                    int n = ++skipped;
                    info("划掉只收任务，不强停进程（第 " + n + " 次）：" + target.getName()
                            + "  from pid " + caller);
                    nest[1]++;
                    // forceStopPackageLocked 返回「有没有真的停掉什么」。跳过就等于没停。
                    return target.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }
                if (caller > 0 && noted < 8) {
                    // 外部发起：原样执行，只留档。这些行是「设置里点强制停止还能不能停」的
                    // 唯一证据，将来判据万一出错也全靠它们反推错在哪一层，所以前两条带调用栈。
                    noted++;
                    info("外部发起，照原样执行 from pid " + caller + "\n  方法="
                            + target.toGenericString() + "\n  参数=" + argsOf(chain, target)
                            + (noted <= 2 ? "\n  调用栈=" + stack() : ""));
                }
                // caller == -1：这个重载根本不带 reason（划掉那条链上中间两跳就是这样），
                // 静默放行，让更里面那一跳去判。
            }
            return chain.proceed();
        } finally {
            // 最外层收尾：这一整条链里确实跳过某一刀，才去抹标记。
            if (--nest[0] == 0 && nest[1] > 0) {
                nest[1] = 0;
                clearStoppedFlag(chain.getThisObject());
            }
        }
    }

    /**
     * 把 {@code FLAG_STOPPED} 抹回去，让包看起来「正常启动过」。
     *
     * <p>为什么需要单独一手：跳过 {@code forceStopPackageLocked} 只是让它别杀进程，
     * 包被标成「已停止」是另一处写的。2026-11-23 21:29 的实测很清楚 —— {@code pid} 前后
     * 都是 25985，{@code stopped} 从 {@code false} 变成了 {@code true}。带这个标记的包
     * 收不到隐式广播、JobScheduler 不跑、被系统当成「从没启动过」，下一次后台清理就名正言顺。
     *
     * <p>走的是 {@code PackageManagerInternal.setPackageStoppedState}，也就是 AMS 自己
     * 立/拔这个标记用的那个口子，不是旁门左道。两处都要留一手：
     *
     * <ul>
     *   <li>立刻抹一次 —— 覆盖「标记在我们跳过之前就写好了」的情况。</li>
     *   <li>一秒后再抹一次 —— 覆盖「外层返回之后 Athena 才补写」的情况，那种场合第一次
     *       抹得太早，等于没抹。</li>
     * </ul>
     */
    private static void clearStoppedFlag(final Object ams) {
        if (ams == null) {
            return;
        }
        applyUnstopped(ams);
        try {
            // 延迟补刀。system_server 的主线程 Looper 一直活着，post 一个 Runnable 很便宜。
            if (retry == null) {
                retry = new android.os.Handler(android.os.Looper.getMainLooper());
            }
            retry.postDelayed(() -> applyUnstopped(ams), 1000);
        } catch (Throwable t) {
            if (restoreFails++ < 3) {
                warn("排不上延迟抹标记，只做了立刻那一次", t);
            }
        }
    }

    /** 真正调 {@code setPackageStoppedState(pkg, false, 0)}。失败只记账，不外抛。 */
    private static void applyUnstopped(Object ams) {
        try {
            Object pmi = packageManagerInternal(ams);
            if (pmi == null) {
                if (restoreFails++ < 3) {
                    warn("拿不到 PackageManagerInternal，stopped 标记抹不掉", null);
                }
                return;
            }
            if (setStopped == null) {
                // 从接口上取方法，不从实现类取：实现类是包内可见的，invoke 会被访问检查挡下来。
                Class<?> pmiClass = Class.forName(
                        "android.content.pm.PackageManagerInternal", false, ams.getClass().getClassLoader());
                Method m = pmiClass.getMethod(
                        "setPackageStoppedState", String.class, boolean.class, int.class);
                m.setAccessible(true);
                setStopped = m;
            }
            setStopped.invoke(pmi, PKG, Boolean.FALSE, 0);
            int n = ++restored;
            if (n <= 3 || n % 20 == 0) {
                info("已抹掉 stopped 标记（第 " + n + " 次）");
            }
        } catch (Throwable t) {
            // 抹不掉不算致命：进程还活着，只是缺少「像是正常启动过」这一层身份。
            if (restoreFails++ < 3) {
                warn("抹 stopped 标记失败，包仍会被当成没启动过", t);
            }
        }
    }

    /** 拿 {@code PackageManagerInternal}：先问 AMS 要，不行就走 {@code LocalServices}。 */
    private static Object packageManagerInternal(Object ams) throws Throwable {
        try {
            Method getter = ams.getClass().getMethod("getPackageManagerInternal");
            Object pmi = getter.invoke(ams);
            if (pmi != null) {
                return pmi;
            }
        } catch (NoSuchMethodException ignored) {
            // 这个版本没这个 getter，往下走。
        }
        ClassLoader loader = ams.getClass().getClassLoader();
        Class<?> pmiClass = Class.forName("android.content.pm.PackageManagerInternal", false, loader);
        Class<?> localServices = Class.forName("com.android.server.LocalServices", false, loader);
        Method getService = localServices.getMethod("getService", Class.class);
        return getService.invoke(null, pmiClass);
    }

    /** 这次调用长什么样。只给日志用。 */
    private static String argsOf(XposedInterface.Chain chain, Method method) {
        Class<?>[] params = method.getParameterTypes();
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            try {
                Object value = chain.getArg(i);
                sb.append(value instanceof String ? "\"" + value + "\"" : String.valueOf(value));
            } catch (Throwable t) {
                sb.append('?');
            }
        }
        return sb.append(')').toString();
    }

    /** 谁调的这一刀。只给日志用。 */
    private static String stack() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.equals(Thread.class.getName()) || cls.equals(KeepAlive.class.getName())) {
                continue;
            }
            sb.append("\n    at ").append(frame);
            if (sb.length() > 1600) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 这一刀是谁发起的：返回 reason 里那个 pid，没有 reason 参数（或拼不出数字）时返回 -1。
     *
     * <p>不按下标取。<b>2026-11-23 20:41 的代价：</b>从后往前找第一个 String，取到的是
     * {@code from pid 4527 (system)}，当时以为是「调用方描述」就丢掉了 —— 其实它正是判据本身。
     * 那一轮还顺带证明了另一件事：{@code o-stop(40)} 不在任何参数里，所以按原因码前缀找必然落空。
     *
     * <p>判据为什么成立：{@code forceStopPackageLocked} 收到的 reason 是 AMS 用
     * {@code Binder.getCallingPid()} 现场拼的。
     *
     * <ul>
     *   <li>{@code == Process.myPid()} —— Athena 在 system_server 进程内直接调进来，一句 Java
     *       调用、没有跨进程，所以 pid 就是自己。</li>
     *   <li>{@code != Process.myPid()} —— 设置里的「强制停止」与 {@code am force-stop} 都得
     *       跨 Binder 进来，pid 是别人。</li>
     *   <li>{@code -1} —— 这个重载压根不带 reason（划掉那条链上中间两跳就是这样），静默放行。</li>
     * </ul>
     */
    private static int callerPid(XposedInterface.Chain chain, Method method) {
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (params[i] != String.class) {
                continue;
            }
            Object value = chain.getArg(i);
            if (!(value instanceof String)) {
                continue;
            }
            java.util.regex.Matcher m = REASON_PID.matcher((String) value);
            if (!m.find()) {
                continue;
            }
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // reason 是外部拼的串，理论上进不来非数字，兜一下别让钩子抛。
                return -1;
            }
        }
        return -1;
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
