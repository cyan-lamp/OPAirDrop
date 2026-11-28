package com.oshare.airdrop;

import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 让「收到 iPhone 文件」那张流体云卡片自己摊开的一小段代码。
 *
 * <p>为什么这件事非得跑在别人进程里：展开与否不归应用决定。{@code addLiveAlert} 打的那行日志
 * 里 {@code remindType} 只有 0，而这个值不是应用能塞进通知的 ——
 * {@code OplusLiveAlertNotificationsRepository#entryToLiveAlert} 会用 Gson 现拼一份
 * {@code OplusLiveAlertNotificationOptions} 把 extras 整个覆盖掉，覆盖时填进去的就是它自己
 * 算出来的结果：
 *
 * <pre>
 * access$entryToReminderType(repo, entry, z)
 *     = z ? (fullScreenIntent != null ? 13 : sticky ? 12 : 11) : 0
 * </pre>
 *
 * <p>{@code z} 是 {@code shouldInterrupt}，来自一条故意不打日志的 PEEK 打断决策
 * （{@code makeUnLoggableAndUnFluidConditionDecision}）。邀请卡在这条链上被判「不打断」，
 * 于是恒 0；状态机的 {@code EVENT_DATA_CARDS} 拿 {@code transientReminderSize} 一比，判
 * {@code REFUSED}，胶囊就停在胶囊。
 *
 * <p>官方旋钮三条全试过、全部无效（2026-11-19 实测）：{@code setPriority(PRIORITY_HIGH)}、
 * {@code setCategory(CATEGORY_CALL)}、换一条有声通道 —— 渠道声明 {@code mImportance=4} 且确实
 * 带提示音，{@code dumpsys} 里那条 NotificationRecord 的 ranking importance 仍是 3，
 * {@code remindType} 仍是 0。所以只剩这一条路：把这个 0 抬成 11。
 *
 * <p>为什么只改返回值、不去调 {@code onExpand}：老的那版 {@code FluidModule} 是硬摊开，代价是
 * 通知中心里那张卡缺席、点空白也收不回去。这里只把 {@code remindType} 从 0 改成 11，等于告诉
 * 状态机「这条是强制提醒」，剩下的 {@code CAPSULE → CARD} 迁移由它自己走完，收放都还归系统管。
 *
 * <p>只对自己的通知动手：别家的通知原样返回，否则整台机器的每一条通知都会被强制展开。
 *
 * <p><b>第二刀：我们自己在前台时也不拦。</b>过了 {@code remindType} 那关之后还有一道闸，
 * {@code com.oplus.systemui.plugins.seedling.data.interceptor.a}（{@code AppFrontInterceptor}）：
 * 它拿通知的包名去比当前前台应用，命中就把整张卡丢掉 —— 日志里 {@code shouldFilter ret true,
 * frontTaskId [693]}，紧接着状态机 {@code cardLiveSize=0}、{@code result=REFUSED}。副作用是
 * 「收到文件时你正开着 OPAirDrop，就什么都不会弹」。
 *
 * <p>它自己的代码里留了一个出口（{@code frontBlackList}：命中的包即使在前台也放行），这里把
 * 自己塞进那个出口，而不是去改判定条件。实现上不调 {@code chain.proceed()}：原方法体做完判定
 * 只会把结论写进 {@code model.e} 然后一路往下传，所以对自家的模型把 {@code e} 清成 false、
 * 直接进下一环，效果等于「这个包不在前台」。
 */
public final class FluidModule extends XposedModule {

    private static final String TAG = "OPAirDrop";

    private static final String SYSTEM_UI = "com.android.systemui";
    /**
     * system_server 的作用域名。日志里 LSPosed 打的前缀是 {@code (system)}，但它交给模块的
     * {@code getPackageName()} 到底是不是这两个字，只有真机日志能回答 —— 两种情况都在
     * {@link #onPackageReady} 里按 {@code system} 判，同时把真值打进日志。
     */
    private static final String SYSTEM_SERVER = "system";
    private static final String SELF = "com.oshare.airdrop";

    private static final String REPOSITORY =
            "com.oplus.systemui.statusbar.notification.livealert.data.repository"
                    + ".OplusLiveAlertNotificationsRepository";
    private static final String ENTRY_TO_REMIND_TYPE = "access$entryToReminderType";

    /**
     * 卡片那一层的 ViewModel：{@code handleDataTimeout} 在里面，也就是「三秒到了，收回去」。
     *
     * <p>它和上面的拦截器都在**流体云插件自己的 {@code DexClassLoader}** 里，用 SystemUI 的
     * loader {@code Class.forName} 找不到（2026-11-19 实测，日志是 {@code ClassNotFoundException}）。
     * 所以要先等到插件加载自己的类，借它那一刻的 loader。
     *
     * <p>**2026-11-21 系统更新后这个类名换过一次，而且是那种最难发现的换法**：新的
     * {@code viewmodel.h} 依然存在（变成了一个只有三个状态回调的空壳 {@code ...statemachineengine.f}），
     * 所以 {@code Class.forName} 照样成功，只在取字段时才报 {@code NoSuchFieldException: d} ——
     * 日志里那一行「卡片不收回那一刀没装上：java.lang.NoSuchFieldException: d」就是这么来的。
     * 真正搬走的是 {@code viewmodel.i}（继承 {@code seedling.base.b}），字段也重新排过：
     * 卡列表 {@code d → e}、卡状态 {@code g → h}。
     *
     * <p>所以现在两个名字都试，而且**字段不再按名字取**（见 {@link #findStateField}）：
     * 混淆器每次发版都会重排短名，但「状态是一个可变的 String，里面写着 CARD_STATE_*」
     * 这个结构是它画卡时绕不开的。
     */
    private static final String[] CARD_VIEW_MODELS = {
            "com.oplus.systemui.plugins.seedling.card.ui.viewmodel.i",
            "com.oplus.systemui.plugins.seedling.card.ui.viewmodel.h",
    };

    /** {@code z} 为真、没有全屏意图、也不是 sticky 时系统自己就会给的值：胶囊 + 立即提醒。 */
    private static final int REMIND_TYPE_FORCED = 11;

    /**
     * 参数表第二个是 {@code NotificationEntry}，装钩子时从它身上取出来的 {@code getSbn}。
     * 装不上就是 null —— 那时 {@link #packageOf} 返回 null，钩子等于什么都没做。
     */
    private static Method sbnGetter;

    /** 插件的两刀都装完了没有。装完后 {@link #onLoadClass} 立刻让路。 */
    private static volatile boolean sPluginHooked;
    /** 正在装：期间 {@code Class.forName} 会再次穿过 loadClass，必须放行否则自己等自己。 */
    private static volatile boolean sInstallingPlugin;
    /** 等插件加载类的那只临时钩子；装完就摘掉，不给每一次类加载都留一层壳。 */
    private static XposedInterface.HookHandle sLoaderWatch;

    /**
     * 同一份 ViewModel 里那个卡片状态字符串（{@code CARD_STATE_AUTO} 之类）。
     *
     * <p>旧版叫 {@code g}、新版叫 {@code h}。同样按结构找：见 {@link #findStateField}。
     */
    private static java.lang.reflect.Field sCardState;

    /**
     * system_server 那一支：划掉不再强停进程，见 {@link KeepAlive}。
     *
     * <p>它和流体云共用一个模块入口，只是作用域不同。**不能用 {@code onPackageReady} 接**：
     * 那个回调在 system_server 里根本不会来（它只对普通应用进程发包名），
     * 2026-11-23 19:37 那次重启就是这么白等一轮的 —— system_server 的日志里没有一行
     * {@code OPAirDrop}，而 SystemUI 的三刀全都在。这个回调的签名里只有一个 classLoader，
     * 也正是「还没开始跑、只知道拿哪把加载器」的意思。
     */
    /*
     * 诊断用：静态初始化一跑就留痕。2026-11-23 那一轮，system_server 里连一行 OPAirDrop 都没有，
     * 单靠这一条就能分辨「类根本没被加载」和「加载了但回调没来」——其余两种可能各自有日志，
     * 唯独「压根没加载」是静默的。
     */
    static {
        Log.i(TAG, "FluidModule 类已加载");
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            log(Log.INFO, TAG, "收到 onSystemServerStarting，开始装划掉不强停那一刀");
            KeepAlive.install(this, param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "划掉不再强停那一刀没装上：" + t, t);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        // system_server 里也走一遍这个回调（现代 API 在 system 作用域下同样回调），
        // 顺手在这里补装，两条入口互为备份。
        log(Log.INFO, TAG, "onPackageReady 进程=" + param.getPackageName());
        if (SYSTEM_SERVER.equals(param.getPackageName())) {
            try {
                KeepAlive.install(this, param.getClassLoader());
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "划掉不再强停那一刀没装上（onPackageReady 入口）：" + t, t);
            }
            return;
        }
        if (!SYSTEM_UI.equals(param.getPackageName())) {
            return;
        }
        try {
            install(param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "流体云钩子没装上：" + t, t);
        }
    }

    private void install(ClassLoader loader) {
        // 第一刀在 SystemUI 自己的 loader 里就能装；后两刀在插件的 loader 里，得等它们出现。
        try {
            hookRemindType(loader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "提醒级别那一刀没装上：" + t, t);
        }
        try {
            watchPluginLoader();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "插件加载监听没装上：" + t, t);
        }
    }

    /**
     * 插件里的那两刀要等插件自己把类加载出来才装得上。
     *
     * <p>为什么不在 {@code onPackageReady} 里直接 {@code Class.forName(..., systemUiLoader)}：实测
     * 报 {@code ClassNotFoundException} —— 流体云是一个独立插件，它有自己的 {@code DexClassLoader}，
     * SystemUI 的 loader 看不见它的类。
     *
     * <p>所以这里钩住 {@code ClassLoader#loadClass(String)}，等那两个类名出现时，借**那一刻的
     * {@code this}（也就是插件的 loader）**去装正式的两刀；装完立刻 {@code unhook()}，不给往后
     * 每一次类加载都留一层壳。判断只做两次 {@code String.equals}，命中之前等于让路。
     */
    private void watchPluginLoader() throws Exception {
        Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
        sLoaderWatch = hook(loadClass)
                .setPriority(PRIORITY_LOWEST)
                // 类加载错了会连累整个进程：任何意外都按下不表，让原始逻辑自己跑完。
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .setId("opairdrop-plugin-loader")
                .intercept(this::onLoadClass);
        log(Log.INFO, TAG, "等流体云插件把自己的类加载出来");
    }

    private Object onLoadClass(XposedInterface.Chain chain) throws Throwable {
        if (sPluginHooked || sInstallingPlugin) {
            return chain.proceed();
        }
        Object name = chain.getArg(0);
        // 2026-11-27：插件里的类被混淆成单字母（`…seedling.card.ui.viewmodel.i` 就是卡片
        // ViewModel），所以按 `LiveAlertModel`/`SeedlingContext` 这些显示名猜候选永远 MISS。
        // 这里把疑似插件的类加载全部记下来：重启后触发一次缩回（那些类是懒加载的），
        // 日志里就会出现真实全名，钩子就能按真名装上。
        if (name instanceof String) {
            String text = (String) name;
            if (text.contains("seedling") || text.contains("LiveAlert")) {
                log(Log.INFO, TAG, "插件类加载: " + text);
            }
        }
        // 只认「卡片 ViewModel」这一个触发点，不认拦截器：拦截器那一刀按 2026-11-19 的取证
        // 本来就不该装（装上反而让状态机判 REFUSED）。认它是白等一类加载。
        if (!CARD_VIEW_MODELS[0].equals(name) && !CARD_VIEW_MODELS[1].equals(name)) {
            return chain.proceed();
        }
        sInstallingPlugin = true;
        try {
            Object loaded = chain.proceed();
            Object self = chain.getThisObject();
            if (self instanceof ClassLoader) {
                installPluginHooks((ClassLoader) self);
            }
            return loaded;
        } finally {
            sInstallingPlugin = false;
        }
    }

    private void installPluginHooks(ClassLoader pluginLoader) {
        log(Log.INFO, TAG, "拿到流体云插件的 loader：" + pluginLoader);
        // 2026-11-28：换路——不再钩插件（插件类是动态加载的，hook 会被 LSPosed 的动态代码
        // 保护拦掉），改成钩 SystemUI **主 APK** 里的 remindType 计算。项目注释里那个公式
        //   remindType = promoted ? (fullScreenIntent != null ? 13 : sticky ? 12 : 11) : 0
        // 就在 `entryToReminderType` 里；它是主 APK 类，onLoad 时已存在，可以合法 hook。
        // 强制返回 13（强提醒·始终），卡片就不会 3 秒缩回。
        try {
            Class<?> repo = Class.forName(
                    "com.oplus.systemui.statusbar.notification.livealert.data.repository"
                            + ".OplusLiveAlertNotificationsRepository",
                    false, FluidModule.class.getClassLoader());
            for (java.lang.reflect.Method method : repo.getDeclaredMethods()) {
                if (!"entryToReminderType".equals(method.getName())) {
                    continue;
                }
                hook(method)
                        .setPriority(PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .setId("opairdrop-remind-type-13")
                        .intercept(chain -> 13);
                log(Log.INFO, TAG, "已钩住 entryToReminderType → 恒返 13（卡片不缩回）");
                break;
            }
        } catch (Throwable missing) {
            log(Log.ERROR, TAG, "Repository.entryToReminderType 钩不上：" + missing);
        }
        // 前台抑制那一刀**故意不装**。2026-11-19 01:43 试过：装上之后它确实把卡放行了，
        // 但同一轮状态机反而判 REFUSED（cardLiveSize=1 / capsuleLiveSize=1 /
        // transientReminderSize=0 / cardsLevel=-1），比不装还差。
        // 原因是我们替原方法跳过了它写进模型的那一步，连带把「这是一条瞬时提醒」的标记弄丢了。
        // 而 01:37 那轮（这一刀没装上）证明：remindType=11 本身就已经越过这道闸，
        // 根本不需要动它。方法留着，是不想让这段取证白丢；调用点删掉。
        try {
            hookCardTimeout(pluginLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "卡片不收回那一刀没装上：" + t, t);
        }
        try {
            hookShrinkTimer(pluginLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "缩回定时器那一刀没装上：" + t, t);
        }
        try {
            // 第三刀的现行版本：拦动作终点 onHide-card（见方法注释）。与上面的定时器钩并存，
            // 任何一条生效都能让卡片不缩回。
            hookSeedlingHide(pluginLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "seedling onHide 那一刀没装上：" + t, t);
        }
        sPluginHooked = true;
        XposedInterface.HookHandle watch = sLoaderWatch;
        sLoaderWatch = null;
        if (watch != null) {
            try {
                watch.unhook();
            } catch (Throwable ignored) {
                // 摘不掉也只是多一层让路，不值得为它报错。
            }
        }
    }
    private void hookRemindType(ClassLoader loader) throws Exception {
        Class<?> repository = Class.forName(REPOSITORY, false, loader);

        // 按名字找而不是 getDeclaredMethod(全名四个参数)：签名里那三个类型名一个都不用写死，
        // 系统换实现时这里更容易活下来。
        Method target = null;
        for (Method method : repository.getDeclaredMethods()) {
            if (ENTRY_TO_REMIND_TYPE.equals(method.getName()) && method.getReturnType() == int.class) {
                target = method;
                break;
            }
        }
        if (target == null) {
            log(Log.ERROR, TAG, REPOSITORY + " 里找不到 " + ENTRY_TO_REMIND_TYPE + "，这一版系统改了实现");
            return;
        }

        Class<?>[] params = target.getParameterTypes();
        if (params.length != 3 || params[2] != boolean.class) {
            log(Log.ERROR, TAG, "签名对不上，放弃： " + target);
            return;
        }
        sbnGetter = params[1].getMethod("getSbn");

        hook(target)
                .setPriority(PRIORITY_DEFAULT)
                // 钩子里出了任何事都当作没钩过：返回原值总比让 SystemUI 崩掉强。
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .setId("opairdrop-fluid-remind-type")
                .intercept(this::lift);
        log(Log.INFO, TAG, "已钩住 " + target);
    }

    /**
     * 「三秒到了，收回胶囊」这一刀：钩 {@code CardContainerViewModel.l(String)}。
     *
     * <p>取证（2026-11-19 01:38 那轮 logcat，逐行对得上）：
     *
     * <pre>
     *   01:38:39.481 CardContainerRoot-->setTouchableRegion. curState: CARD_STATE_AUTO
     *   01:38:42.456 CardContainerViewModel-->handleDataTimeout
     *   01:38:42.456 BeforeEvent:EVENT_CARD_TIME_OUT PreState:START_STATE CurState:CARD_STATE
     *   01:38:42.456 try transfer CARD_TO_START_BY_TIME_OUT
     * </pre>
     *
     * <p>也就是说，卡片展开后是**被人定时打了一下 {@code handleDataTimeout}** 收回去的，不是
     * 什么「动画播完了」。{@code l()} 是那段处理的唯一入口，它做两件事：打那行
     * {@code handleDataTimeout} 日志，然后发 {@code EVENT_CARD_TIME_OUT}。
     *
     * <p>顺便说明一个容易误会的地方：{@code LiveAlertModel} 里那个
     * {@code InstantDuration: 3000} **不是计时器**，它是 {@code toString()} 按
     * {@code remindType == 11} 现算出来给人看的值（{@code iD2 == 11 ? 3000 : 5000}），
     * 改它不会影响任何时长。
     */
    private void hookCardTimeout(ClassLoader pluginLoader) throws Exception {
        Class<?> viewModel = null;
        for (String name : CARD_VIEW_MODELS) {
            try {
                viewModel = Class.forName(name, false, pluginLoader);
                break;
            } catch (ClassNotFoundException ignored) {
                // 另一个名字在更早/更晚的版本里，接着试。
            }
        }
        if (viewModel == null) {
            log(Log.ERROR, TAG, "两个类名都没有，这一版改了实现");
            return;
        }

        Method target = null;
        for (Method method : viewModel.getDeclaredMethods()) {
            if ("l".equals(method.getName())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == String.class
                    && method.getReturnType() == void.class) {
                target = method;
                break;
            }
        }
        if (target == null) {
            log(Log.ERROR, TAG, viewModel.getName() + " 里找不到 l(String)，这一版改了实现");
            return;
        }

        // 字段放在方法认出来之后再取：认错了类就根本不该动它的字段。
        sCardState = findStateField(viewModel);
        log(Log.INFO, TAG, "卡片状态字段=" + sCardState);

        hook(target)
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .setId("opairdrop-card-keep")
                .intercept(this::keepCard);
        log(Log.INFO, TAG, "已钩住卡片超时 " + target);
    }

    /**
     * 「三秒到了，缩回胶囊」的**现行**路径：{@code com.heytap.log.collect.auto.d.I(key, delay, int, task)}。
     *
     * <p>2026-11-24 00:56 现场取证：卡片展开后整 3 秒 {@code onHide-capsule}，而旧路径的
     * {@code handleDataTimeout}/{@code EVENT_CARD_TIME_OUT} 自启动以来 0 命中——11-21 系统更新
     * 之后，收回不再走卡片 ViewModel 的超时回调，改由插件里这个通用定时调度器执行。
     * 时长在 {@code data/interactor/m} 里按 remindType 二选一：**11（STRONG_SHORT，正是
     * {@link #lift} 强推的那个「能自动展开」的级别）→ 3000 ms，其余 → 5000 ms**。
     * 也就是说「自动展开」和「3 秒就缩」在这个 ROM 里是同一个常量绑死的，没有先展开又耐久的级别。
     *
     * <p>为什么钩调度器而不是钩 {@code onHide} 的执行者（{@code interactor/m.G()}）：G() 同时被
     * 「通知撤除」的正规路径复用（2026-11-24 00:48:36 removeLiveAlert 那轮实测它也在调 G()），
     * 跳过它会让正规撤除也失效。而 {@code I()} 的第一个参数就是 livealert 的 key
     * （形如 {@code 0|com.oshare.airdrop|2816|null|10409}），按 key 放行/跳过，其它应用的卡不受影响；
     * 我们的卡靠数据事件（列表增删）照样能被正常撤掉。
     *
     * <p>9 个调用方（notification/j / capsule strategy / A·B·F·r·card-q 各 viewmodel）全都
     * 汇进这一个方法，所以这是唯一需要动的咽喉。
     */
    private void hookShrinkTimer(ClassLoader pluginLoader) throws Exception {
        Class<?> scheduler;
        try {
            scheduler = Class.forName("com.heytap.log.collect.auto.d", false, pluginLoader);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "调度器类 com.heytap.log.collect.auto.d 不存在，这一版改了实现");
            return;
        }

        Method target = null;
        for (Method method : scheduler.getDeclaredMethods()) {
            if ("I".equals(method.getName())
                    && method.getParameterCount() == 4
                    && method.getParameterTypes()[0] == String.class
                    && method.getParameterTypes()[1] == long.class
                    && method.getParameterTypes()[2] == int.class
                    && method.getReturnType() == void.class) {
                target = method;
                break;
            }
        }
        if (target == null) {
            log(Log.ERROR, TAG, scheduler.getName() + " 里找不到 I(String,long,int,…)，这一版改了实现");
            return;
        }

        hook(target)
                .setPriority(PRIORITY_DEFAULT)
                // 出任何意外都放行原逻辑：宁可卡缩回，不能让 SystemUI 的定时器哑掉。
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .setId("opairdrop-shrink-timer")
                .intercept(this::skipShrinkTimer);
        log(Log.INFO, TAG, "已钩住缩回定时器 " + target);
    }

    /** 跳过计数：日志只在第 1 次和此后每 25 次打一条，进度卡每秒刷新好几轮，别刷屏。 */
    private static int sSkipCount;
    private static int sOtherCount;

    private Object skipShrinkTimer(XposedInterface.Chain chain) throws Throwable {
        Object key = chain.getArg(0);
        // 2026-11-27：key 不是包名，而是 "capsuleToBubble" 这类动作名（见下面 11-25 的诊断注释），
        // 所以 contains(SELF) 永远不成立——这就是「钩住了缩回定时器、卡片却仍然 3 秒缩回」的原因。
        // 改判 delay：3000 ms 正是「展开后 3 秒缩回」的时长，跳过这一条调度即可。
        Object rawDelay = chain.getArg(1);
        long delayMs = rawDelay instanceof Number ? ((Number) rawDelay).longValue() : -1L;
        if (delayMs == 3000L) {
            sSkipCount++;
            if (sSkipCount == 1 || sSkipCount % 25 == 0) {
                log(Log.INFO, TAG, "第 " + sSkipCount + " 次跳过自家的缩回定时器（key=" + key + "）");
            }
            // 不调 proceed()：这个定时任务根本不排。
            return null;
        }
        // 诊断：2026-11-25 现场显示 key 从来判不上（提醒级别打了 30 次、这条一次没有），
        // 卡 3 秒就缩回。key 只见 过 "capsuleToBubble" 这类动作名——那不是 per-card 的。
        // 这里把 delay（arg1，「3 秒缩回」应为 3000）和调用栈一起打出来，定位真正的
        // 排队调用方。栈是混淆后的名字，但调用链结构 + delay 足以对上号。
        sOtherCount++;
        if (sOtherCount <= 50) {
            Object delay = chain.getArg(1);
            StringBuilder frames = new StringBuilder();
            StackTraceElement[] stack = new Throwable().getStackTrace();
            for (int i = 1; i < Math.min(stack.length, 8); i++) {
                frames.append(stack[i].getClassName()).append('.')
                        .append(stack[i].getMethodName()).append(" <- ");
            }
            log(Log.INFO, TAG, "缩回定时器 #" + sOtherCount + " key=" + key
                    + " delay=" + delay + " 栈: " + frames);
        }
        return chain.proceed();
    }

    /**
     * 找卡片状态那个字段：{@code CARD_STATE_AUTO} / {@code CARD_STATE_MANUAL} 就存在它里面。
     *
     * <p>同样不认名字（旧 {@code g}、新 {@code h}）。结构上它有两个特征：类型是
     * {@code String}，而且**不是 final** —— 因为状态是运行时改的（见
     * {@code capsule.data.repo.g} 里那句 {@code iVar.h = ... "CARD_STATE_MANUAL" : "CARD_STATE_AUTO"}）。
     * 那一串 {@code final String} 是构造函数里定死的常量，排除掉就只剩它。
     *
     * <p>取不到不算致命：{@link #keepCard} 会退化成「只不收回、不改状态」，卡仍然留着，
     * 只是要按按钮才消失。
     */
    private java.lang.reflect.Field findStateField(Class<?> viewModel) {
        for (java.lang.reflect.Field field : viewModel.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(modifiers)) {
                continue;
            }
            if (field.getType() == String.class && !java.lang.reflect.Modifier.isFinal(modifiers)) {
                return field;
            }
        }
        log(Log.ERROR, TAG, viewModel.getName() + " 里没有可变的 String 字段，状态改不动");
        return null;
    }

    /**
     * 自家的卡到期不收；别家的照旧。
     *
     * <p>为什么要把状态从 {@code CARD_STATE_AUTO} 改成 {@code CARD_STATE_MANUAL}：这两个状态在
     * 系统里是有分工的 —— {@code AUTO} 是「系统替你摊开的」，所以三秒后系统替你收回去；
     * {@code MANUAL} 是「人长按胶囊摊开的」，系统不收回，只等人点空白处或按钮才收。
     * 我们做的事本质上是替系统摊开，但想让它的下场跟手动摊开一样，所以两个动作都要做：
     *
     * <ol>
     *   <li>不发 {@code EVENT_CARD_TIME_OUT}（{@code return null} 就是不再往下走，
     *       这方法本来就是 {@code void}）；</li>
     *   <li>把状态改成 {@code MANUAL}，好让「点空白处收起」那条路径认得它 ——
     *       否则卡会挂在那里没有任何手动关闭的办法。</li>
     * </ol>
     *
     * <p>别家的卡一个字节都不动：靠参数认。{@code l(String)} 的第一个参数就是卡片 key
     * （形如 {@code 0|com.oshare.airdrop|2816|null|10456}），包名就在里面 —— 这比旧版去翻
     * 卡列表里的 {@code a} 字段稳得多，那个字段名同样是混淆出来的，而且列表字段本身也已经
     * 从 {@code d} 挪到了 {@code e}。能只读一个参数就不碰任何反射出来的字段。
     */
    /**
     * 「卡片展开后约 3 秒缩回胶囊」这一刀：拦**动作终点** `LiveAlertModel.onHide-card(key, …)`。
     *
     * <p>2026-11-27 的真机日志把链路钉死了：
     *
     * <pre>
     * 14:18:40.277 PluginSeedling--StatusBar: LiveAlertModel-->onHide-card 0|com.oshare.airdrop|2816|null|10449, true, true
     * 14:18:40.277 PluginSeedling--Track: TrackManager-->mapKey: … keepTime: 3978
     * </pre>
     *
     * <p>以前拦的是上游定时器（`handleDataTimeout`，后来换成 `com.heytap.log.collect.auto.d.I`），
     * 系统一更新就 0 命中——这就是「有时好、有时失效」的真实原因。`onHide-card` 是**唯一出口**，
     * 不管上游计时器叫什么，卡片要收起都得经过这里。按 key 判断，只对我们自己的包生效。
     */
    private void hookSeedlingHide(ClassLoader loader) {
        // 日志只给了类名（LiveAlertModel）与包前缀（com.oplus.systemui.plugins.seedling.*），
        // 具体包没露出来，所以按候选全名逐个试——这是不依赖「插件 APK 在哪」的做法。
        String[] candidates = {
                "com.oplus.systemui.plugins.seedling.statusbar.model.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.statusbar.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.statusbar.viewmodel.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.card.ui.model.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.card.ui.viewmodel.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.card.model.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.engine.model.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.engine.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.capsule.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.capsule.model.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.model.LiveAlertModel",
                "com.oplus.systemui.plugins.seedling.LiveAlertModel",
        };
        for (String name : candidates) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (Throwable missing) {
                continue;
            }
            Method target = null;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().startsWith("onHide") && method.getParameterCount() >= 1) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                log(Log.ERROR, TAG, "seedling: " + name + " 里没有 onHide*，换下一个候选");
                continue;
            }
            try {
                hook(target)
                        .setPriority(PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .setId("opairdrop-seedling-keep")
                        .intercept(this::keepSeedlingCard);
                log(Log.INFO, TAG, "seedling hook: 已钩住 " + target
                        + "（卡片不再 3 秒缩回）");
                return;
            } catch (Throwable failed) {
                log(Log.ERROR, TAG, "seedling hook 装不上 " + target + "：" + failed);
            }
        }
        log(Log.ERROR, TAG, "seedling hook: MISS——候选类都没命中，卡片可能仍会 3 秒缩回"
                + "（把这一行发出来，按新类名再加候选）");
        // 2026-11-27：`LiveAlertModel` 的候选全 MISS（它住在动态加载的插件里，SystemUI.apk
        // 与几个插件 apk 里都没有这个符号）。改钩缩回动作的**起点**：
        // `SeedlingContext.setScene(CARD_TO_CAPSULE)`——真机日志里它紧挨着 modelState 变 5，
        // 是整条缩回链的第一步；拦掉它，后面的 CARD_TO_CAPSULE 与 onHide-card 都不会发生。
        // 类名同样按简名 + 日志里的包前缀推测，候选都试一遍。
        String[] sceneClasses = {
                "com.oplus.systemui.plugins.seedling.engine.SeedlingContext",
                "com.oplus.systemui.plugins.seedling.SeedlingContext",
                "com.oplus.systemui.plugins.seedling.engine.SeedlingContextImpl",
                "com.oplus.systemui.plugins.seedling.statusbar.SeedlingContext",
                "com.oplus.systemui.plugins.seedling.card.SeedlingContext",
        };
        for (String name : sceneClasses) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (Throwable missing) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!"setScene".equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                hook(method)
                        .setPriority(PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .setId("opairdrop-seedling-scene")
                        .intercept(this::keepScene);
                log(Log.INFO, TAG, "seedling scene hook: 已钩住 " + method + "（卡片不再缩回）");
                return;
            }
        }
        log(Log.ERROR, TAG, "seedling scene hook: 也 MISS——类名还得再找");

        // 2026-11-27：从真机调用栈里拿到的缩回决策者（不是猜的）：
        //   com.oplus.systemui.plugins.seedling.capsule.strategy.d.p
        //     <- ...capsule.strategy.d.l <- ...capsule.ui.viewmodel.F.<init>
        // `I(key, delay, …)` 是被它调用的（诊断日志里那条 delay=600000 的栈就是它），
        // 而 3 秒缩回有时根本不经过 I()——所以直接拦这颗策略入口 `d.l`。
        try {
            Class<?> strategy = Class.forName(
                    "com.oplus.systemui.plugins.seedling.capsule.strategy.d", false, loader);
            for (Method method : strategy.getDeclaredMethods()) {
                if (!"l".equals(method.getName())) {
                    continue;
                }
                hook(method)
                        .setPriority(PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .setId("opairdrop-shrink-strategy")
                        .intercept(chain -> {
                            log(Log.INFO, TAG, "缩回策略被跳过（卡片保持展开）");
                            return null;
                        });
                log(Log.INFO, TAG, "已钩住缩回策略 " + method + "（卡片不再缩回）");
                return;
            }
            log(Log.ERROR, TAG, "缩回策略类里没有 l()");
        } catch (Throwable missing) {
            log(Log.ERROR, TAG, "缩回策略类没找到：" + missing);
        }
    }

    /** `setScene(CARD_TO_CAPSULE)` 直接吞掉：不切场景，卡片就不会变成胶囊。 */
    private Object keepScene(XposedInterface.Chain chain) throws Throwable {
        Object arg = chain.getArg(0);
        String text = String.valueOf(arg);
        if (text.contains("CARD_TO_CAPSULE")) {
            log(Log.INFO, TAG, "seedling scene: 跳过 CARD_TO_CAPSULE");
            return null;
        }
        return chain.proceed();
    }

    /** 我们的 key 形如 {@code 0|com.oshare.airdrop|2816|null|10449}；命中就不让卡片隐藏。 */
    private Object keepSeedlingCard(XposedInterface.Chain chain) throws Throwable {
        for (int i = 0; i < 3; i++) {
            Object arg;
            try {
                arg = chain.getArg(i);
            } catch (Throwable outOfRange) {
                break;
            }
            if (arg instanceof String && ((String) arg).contains(SELF)) {
                log(Log.INFO, TAG, "seedling keep: onHide 被忽略 " + arg);
                return null;
            }
        }
        return chain.proceed();
    }

    private Object keepCard(XposedInterface.Chain chain) throws Throwable {
        Object self = chain.getThisObject();
        Object key = chain.getArg(0);
        if (self == null || !(key instanceof String) || !((String) key).contains(SELF)) {
            return chain.proceed();
        }
        try {
            if (sCardState != null && !"CARD_STATE_MANUAL".equals(sCardState.get(self))) {
                sCardState.set(self, "CARD_STATE_MANUAL");
            }
        } catch (Throwable ignored) {
            // 改不动状态就只做到「不收回」这一半，卡仍然留着，只是要点按钮才消失。
        }
        log(Log.INFO, TAG, "这张卡到期也不收回，改成手动展开态");
        return null;
    }

    // 这里原本还有第三刀：把「我们自己在前台就不弹卡」那个前置抑制绕过去。2026-11-19
    // 01:43 实测它有害——装上之后状态机反而判 REFUSED（cardLiveSize=1 / capsuleLiveSize=1 /
    // transientReminderSize=0 / cardsLevel=-1），比不装还差；而 01:37 那轮证明
    // remindType=11 本身就已经越过那道闸。取证留在 Plan 的 2026-11-19 条目里，代码删掉：
    // 一段没有调用点的钩子不是证据，它是每次读这个文件都要重新判断一次的噪声。
    private Object lift(XposedInterface.Chain chain) throws Throwable {
        Object original = chain.proceed();
        if (!(original instanceof Integer) || (Integer) original != 0) {
            return original;
        }
        if (!SELF.equals(packageOf(chain.getArg(1)))) {
            return original;
        }
        log(Log.INFO, TAG, "提醒级别 0 → " + REMIND_TYPE_FORCED + "，让卡片自己展开");
        return REMIND_TYPE_FORCED;
    }

    private static String packageOf(Object entry) {
        if (entry == null || sbnGetter == null) {
            return null;
        }
        try {
            Object sbn = sbnGetter.invoke(entry);
            return sbn instanceof StatusBarNotification
                    ? ((StatusBarNotification) sbn).getPackageName()
                    : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
