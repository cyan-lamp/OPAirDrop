package com.oshare.airdrop;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

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
 * <p># 接收端的第二处钩子：借用它的接收弹窗与流体云
 *
 * <p>传输本身仍然是纯协议——8770 端口上自己应答 {@code /Discover} 与 {@code /Ask}，一个字节
 * 都不经过一加互传。借用的是它的 <em>表面</em>：{@link OShareReceiveBridge} 钩住互传自己的
 * 接收 Activity，用一个带标记的 Intent 把它变成一个只听我们指挥的窗口，{@link OShareNativeTransferPresenter}
 * 再把同一次接收推进系统流体云卡片。钩子与传输之间只有 {@link BridgeMessenger} 一条线，所以
 * 互传升级换代时坏掉的是那张卡片，不是收文件这件事。
 *
 * <p>装上这组钩子需要一个时机：必须在互传的主进程起来之后、在它的接收 Activity 被创建之前。
 * {@code OShareApplication.onCreate} 是唯一一个两者都成立的点。
 *
 * <p># 失败要静默
 *
 * <p>一加互传会随系统更新换代，类名和字段名都可能变。钩子装不上时这里只记一行日志：分享面板
 * 退回它本来的样子，人照样能从系统分享面板里选到本应用（{@code SendActivity} 自己声明了
 * SEND 过滤器）；通知栏那个面板少了我们那一行，也仍然是它本来的三行。一个装不上的钩子不该让
 * 别人的程序少一个功能。
 */
public final class Entry extends XposedModule {

    /**
     * 日志标签。包内可见而不是私有：接收那组类（{@code OShareReceiveBridge} 等）在自己的
     * 进程里写日志，用的是同一个标签——两条日志流在 logcat 里必须能被一条 grep 串起来。
     */
    static final String TAG = "OPAirDrop";

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
            // 接收弹窗与流体云：两者都挂在互传自己的接收 Activity 上，与那条面板行互不相干，
            // 所以分开 try——一条装不上不该带走另一条。
            try {
                hookOShareApplication(param.getDefaultClassLoader());
            } catch (Throwable unavailable) {
                log(Log.WARN, TAG, "receive ui unavailable", unavailable);
            }
        }
    }

    /**
     * 在互传的 Application 起来之后装接收那组钩子。
     *
     * <p>为什么是这个时机：{@link OShareReceiveBridge} 要钩的是 Activity 的生命周期方法，
     * 而 {@code hook} 拿到的是 {@code Method}——方法所在的类得已经能被加载。互传的 Application
     * 是它进程里第一个被创建的对象，此时它的 ClassLoader 已经就绪，而接收 Activity 还没有被
     * 任何人创建过。
     *
     * <p>为什么先 {@code proceed()} 再装：这里只借一个「进程起来了」的时刻，不要替代互传自己的
     * 初始化。装钩子是往已经跑完的初始化后面追加，不需要抢在它前面。
     */
    private void hookOShareApplication(ClassLoader loader) throws Exception {
        Method onCreate = loader.loadClass("com.oplus.oshare.OShareApplication")
                .getDeclaredMethod("onCreate");
        hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Context context = (Context) chain.getThisObject();
                Context application = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                // 接收那组类要一个 Context 来建通知、读 UPK、开缓存文件。给的必须是
                // Application 而不是这个 Activity 化的 this：互传的 Activity 会反复创建销毁，
                // 而这几样东西活得比它长。
                HostContext.set(application);
                ShareModeRuntime.initialize(context);
                OShareReceiveBridge.install(this, context, loader);
            } catch (Throwable failure) {
                // 装不上就退回我们自己的确认通知（{@link Confirmation}）。互传随系统更新换代，
                // 类名和方法都可能变，一个装不上的钩子不该让收文件这件事少一个环节。
                log(Log.ERROR, TAG, "OShare receive capability unavailable", failure);
            }
            return result;
        });
        log(Log.INFO, TAG, "OShare receive capability hooked");
    }
}
