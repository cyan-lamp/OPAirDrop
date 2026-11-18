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
 */
public final class FluidModule extends XposedModule {

    private static final String TAG = "OPAirDrop";

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String SELF = "com.oshare.airdrop";

    private static final String REPOSITORY =
            "com.oplus.systemui.statusbar.notification.livealert.data.repository"
                    + ".OplusLiveAlertNotificationsRepository";
    private static final String ENTRY_TO_REMIND_TYPE = "access$entryToReminderType";

    /** {@code z} 为真、没有全屏意图、也不是 sticky 时系统自己就会给的值：胶囊 + 立即提醒。 */
    private static final int REMIND_TYPE_FORCED = 11;

    /**
     * 参数表第二个是 {@code NotificationEntry}，装钩子时从它身上取出来的 {@code getSbn}。
     * 装不上就是 null —— 那时 {@link #packageOf} 返回 null，钩子等于什么都没做。
     */
    private static Method sbnGetter;

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

    private void install(ClassLoader loader) throws Exception {
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
