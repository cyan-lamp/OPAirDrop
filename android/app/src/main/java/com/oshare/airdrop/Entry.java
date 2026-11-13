package com.oshare.airdrop;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口：两处面板，各一行。
 *
 * <p>一加互传的分享面板里加「发送到 iPhone」（{@link PanelHook}），通知栏那张接收范围面板里
 * （没有第二个：通知栏那张面板不再注入任何行，见第 9 节的决定。）
 *
 * <p># 为什么作用域正好是两个包
 *
 * <p>{@code scope.list} 里只写 {@code com.coloros.oshare} 与 {@code com.android.systemui}。
 * 作用域写宽一点看起来无害，实际后果是那个进程每次启动都要加载一次这个模块：成本由系统付，
 * 收益是零。列进来的每一个包都该是有人在这里写了一个钩子——这两个包各有一个。
 *
 * <p># 为什么接收端不需要钩子
 *
 * <p>接收是纯协议：8770 端口上自己应答 {@code /Discover} 与 {@code /Ask}，不碰一加互传的
 * 任何代码。通知栏那一行也只是一个开关的入口——它把「接下来十分钟接收」写进
 * 接收端只跟随互传的「所有人」，由 {@link ReceiveService} 负责挂前台身份。
 *
 * <p># 失败要静默
 *
 * <p>一加互传会随系统更新换代，类名和字段名都可能变。钩子装不上时这里只记一行日志：分享面板
 * 退回它本来的样子，人照样能从系统分享面板里选到本应用（{@code SendActivity} 自己声明了
 * SEND 过滤器）；通知栏那个面板少了我们那一行，也仍然是它本来的三行。一个装不上的钩子不该让
 * 别人的程序少一个功能。
 */
public final class Entry extends XposedModule {

    private static final String TAG = "OPAirDrop";

    /** 一加互传的包名。面板类在 {@code com.oplus.oshare.ui} 下，包名却是这个。 */
    static final String OSHARE = "com.coloros.oshare";

    /** SystemUI 的包名：通知栏那个面板由它画，所以挂钩子的那一半跑在它的进程里。 */
    static final String SYSTEM_UI = "com.android.systemui";

    /**
     * 这一份模块实例。
     *
     * <p>钩子里到处要写日志，而 {@code log} 是实例方法。把实例放在这里，是为了让
     * 帮助类不必各自再存一份指针——两份指针就有两份可能忘记赋值。
     */
    static volatile Entry instance;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        instance = this;
        log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()) {
            return;
        }
        String packageName = param.getPackageName();

        // 分享面板：往设备列表里加一行「发送到 iPhone」。
        if (OSHARE.equals(packageName)) {
            try {
                PanelHook.install(this, param.getDefaultClassLoader());
            } catch (Throwable unavailable) {
                log(Log.WARN, TAG, "share panel row unavailable", unavailable);
            }
        }
    }
}
