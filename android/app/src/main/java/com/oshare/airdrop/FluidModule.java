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
    private static final String SELF = "com.oshare.airdrop";

    private static final String REPOSITORY =
            "com.oplus.systemui.statusbar.notification.livealert.data.repository"
                    + ".OplusLiveAlertNotificationsRepository";
    private static final String ENTRY_TO_REMIND_TYPE = "access$entryToReminderType";

    /** 前置抑制：包名等于前台应用就把整张卡丢掉的那个拦截器。 */
    private static final String FRONT_INTERCEPTOR =
            "com.oplus.systemui.plugins.seedling.data.interceptor.a";
    /** 它动手的那个模型（{@code livealert.lib.m} 的包装，多了一个「要不要丢」的布尔）。 */
    private static final String MODEL =
            "com.oplus.systemui.plugins.seedling.data.interactor.n";

    /**
     * 卡片那一层的 ViewModel：{@code handleDataTimeout} 在里面，也就是「三秒到了，收回去」。
     *
     * <p>它和上面的拦截器都在**流体云插件自己的 {@code DexClassLoader}** 里，用 SystemUI 的
     * loader {@code Class.forName} 找不到（2026-11-19 实测，日志是 {@code ClassNotFoundException}）。
     * 所以要先等到插件加载自己的类，借它那一刻的 loader。
     */
    private static final String CARD_VIEW_MODEL =
            "com.oplus.systemui.plugins.seedling.card.ui.viewmodel.h";

    /** {@code z} 为真、没有全屏意图、也不是 sticky 时系统自己就会给的值：胶囊 + 立即提醒。 */
    private static final int REMIND_TYPE_FORCED = 11;

    /**
     * 参数表第二个是 {@code NotificationEntry}，装钩子时从它身上取出来的 {@code getSbn}。
     * 装不上就是 null —— 那时 {@link #packageOf} 返回 null，钩子等于什么都没做。
     */
    private static Method sbnGetter;

    /** {@code model.e}：拦截器算出来的「要不要丢掉这张卡」。 */
    private static java.lang.reflect.Field sFrontFlag;
    /** {@code model.e()}：通知的包名（转发给里面那个 {@code livealert.lib.m}）。 */
    private static Method sModelPackage;
    /**
     * 拦截器链的下一环 {@code chain.g(model)}。我们替它接管以后必须自己调这一步，
     * 否则后面的拦截器全都不跑了。
     */
    private static Method sNextLink;

    /** 插件的两刀都装完了没有。装完后 {@link #onLoadClass} 立刻让路。 */
    private static volatile boolean sPluginHooked;
    /** 正在装：期间 {@code Class.forName} 会再次穿过 loadClass，必须放行否则自己等自己。 */
    private static volatile boolean sInstallingPlugin;
    /** 等插件加载类的那只临时钩子；装完就摘掉，不给每一次类加载都留一层壳。 */
    private static XposedInterface.HookHandle sLoaderWatch;

    /** {@code CardContainerViewModel.d}：当前屏上那几张卡（{@code List<card.data.model.a>}）。 */
    private static java.lang.reflect.Field sCardList;
    /** {@code CardContainerViewModel.g}：卡片状态字符串，{@code CARD_STATE_AUTO} 之类。 */
    private static java.lang.reflect.Field sCardState;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
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
        if (!CARD_VIEW_MODEL.equals(name) && !FRONT_INTERCEPTOR.equals(name)) {
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
        Class<?> viewModel = Class.forName(CARD_VIEW_MODEL, false, pluginLoader);
        sCardList = viewModel.getField("d");
        sCardState = viewModel.getField("g");

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
            log(Log.ERROR, TAG, CARD_VIEW_MODEL + " 里找不到 l(String)，这一版改了实现");
            return;
        }

        hook(target)
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .setId("opairdrop-card-keep")
                .intercept(this::keepCard);
        log(Log.INFO, TAG, "已钩住卡片超时 " + target);
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
     * <p>别家的卡一个字节都不动：先看 {@code d} 这个列表里有没有 key 带我们包名的元素。
     */
    private Object keepCard(XposedInterface.Chain chain) throws Throwable {
        Object self = chain.getThisObject();
        if (self == null || !hasOurCard(self)) {
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

    /** 这一屏上的卡里有没有我们的（key 形如 {@code 0|com.oshare.airdrop|2816|null|10456}）。 */
    private static boolean hasOurCard(Object viewModel) {
        if (sCardList == null) {
            return false;
        }
        try {
            Object list = sCardList.get(viewModel);
            if (!(list instanceof java.util.List)) {
                return false;
            }
            for (Object card : (java.util.List<?>) list) {
                if (card == null) {
                    continue;
                }
                Object key;
                try {
                    key = card.getClass().getField("a").get(card);
                } catch (Throwable t) {
                    continue;
                }
                if (key instanceof String && ((String) key).contains(SELF)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 读不到就当作不是我们的：宁可让别家的卡按原样超时。
        }
        return false;
    }

    private void hookFrontBypass(ClassLoader loader) throws Exception {
        Class<?> interceptor = Class.forName(FRONT_INTERCEPTOR, false, loader);
        Class<?> model = Class.forName(MODEL, false, loader);
        sFrontFlag = model.getField("e");
        sModelPackage = model.getMethod("e");

        // 同一个类里叫 a 的方法不止一个（都混淆成 a 了），靠「第一个参数就是那个模型」认出来。
        Method target = null;
        for (Method method : interceptor.getDeclaredMethods()) {
            if (!"a".equals(method.getName()) || method.getReturnType() != boolean.class) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0] == model) {
                target = method;
                break;
            }
        }
        if (target == null) {
            log(Log.ERROR, TAG, FRONT_INTERCEPTOR + " 里找不到 (模型, 链) -> boolean，这一版改了实现");
            return;
        }

        hook(target)
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .setId("opairdrop-front-bypass")
                .intercept(this::keepWhenFront);
        log(Log.INFO, TAG, "已钩住前台抑制 " + target);
    }

    /**
     * 我们自己在前台时也把卡留下。
     *
     * <p>原方法体只有两件事：算一个「要不要丢」的布尔写进 {@code model.e}，然后把模型交给下一环
     * {@code chain.g(model)}。对自家的模型我们跳过第一步、直接做第二步——{@code e} 已经是 false
     * 了（拦截器刚构造出来时就是这个初值），下一环读到的就是「没命中前置抑制」。
     *
     * <p>别家的模型原样 {@code proceed()}，一个字节都不改。
     */
    private Object keepWhenFront(XposedInterface.Chain chain) throws Throwable {
        Object model = chain.getArg(0);
        if (model == null || !SELF.equals(packageOfModel(model))) {
            return chain.proceed();
        }
        if (sFrontFlag == null) {
            return chain.proceed();
        }
        Method link = nextLink(chain);
        if (link == null) {
            return chain.proceed();
        }
        sFrontFlag.setBoolean(model, false);
        log(Log.INFO, TAG, "我们自己在前台，这张卡也照常放行");
        return link.invoke(chain.getArg(1), model);
    }

    /**
     * 找到拦截器链的下一环 —— 原方法体最后一句 {@code chain.g(model)}。
     *
     * <p>不按参数类型写死去找：那个位置既可能是模型类 {@code n}，也可能是它实现的接口
     * {@code livealert.lib.m}，反编译看不出来（Kotlin 的桥方法）。按「只有一个参数、并且能收下
     * 这个模型」认，两种都命中。找到一次就缓存，它每个通知都会跑。
     */
    private Method nextLink(XposedInterface.Chain chain) {
        if (sNextLink != null) {
            return sNextLink;
        }
        Object link = chain.getArg(1);
        Object model = chain.getArg(0);
        if (link == null || model == null) {
            return null;
        }
        for (Method method : link.getClass().getMethods()) {
            if (!"g".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            if (!method.getParameterTypes()[0].isInstance(model)) {
                continue;
            }
            if (method.getReturnType() != boolean.class) {
                log(Log.ERROR, TAG, "链的下一环返回值不是布尔，放弃：" + method);
                return null;
            }
            sNextLink = method;
            log(Log.INFO, TAG, "接管前置抑制后往下走的那一环是 " + method);
            return sNextLink;
        }
        log(Log.ERROR, TAG, "链上找不到 g(模型)，这一版改了实现：" + link.getClass().getName());
        return null;
    }

    private static String packageOfModel(Object model) {
        if (sModelPackage == null) {
            return null;
        }
        try {
            Object name = sModelPackage.invoke(model);
            return name instanceof String ? (String) name : null;
        } catch (Throwable t) {
            return null;
        }
    }

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
