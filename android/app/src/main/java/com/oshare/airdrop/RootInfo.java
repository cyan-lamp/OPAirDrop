package com.oshare.airdrop;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「这台机器给没给 root、是谁给的」——首页那一行的数据来源。
 *
 * <p># 为什么自带检测而不是用 DriverSetup 的
 *
 * <p>{@link DriverSetup} 里的 su 是「装驱动」的附带动作：只有按了装才会跑，而且读的是脚本的回执，
 * 拿不到「哪个管理器、什么版本」。首页这一行要在没人按任何东西的时候就显示答案，还要报出
 * KernelSU / APatch / Magisk 的名字和版本，所以单独做一次探针，结果进程内缓存。
 *
 * <p># 为什么缓存、不每次刷新都测
 *
 * <p>首页每次切回来都重建，而 {@code su -c id -u} 要起一个进程、走一次授权弹窗。每次刷新都测，
 * 人会被反复弹窗烦死，页面也会先显示「未获取」再跳成「已获取」。所以只在第一次测一次，之后用
 * 缓存；人不巧刚授权时，点那一行可以手动重测一次（见 HomePage 的 root 行）。
 *
 * <p># 管理器识别不依赖 root
 *
 * <p>{@link PackageManager#getPackageInfo} 读的是已安装包，普通权限就够。所以哪怕 su 没授权，
 * 也能说出「装的是 KernelSU 0.9.7，但还没给本应用授权」——这比一句「未获取」有用得多。
 */
final class RootInfo {

    /** 已知的根授权管理器。顺序即优先级：先认到的那个算数。 */
    private static final String[][] MANAGERS = {
            {"me.weishu.kernelsu", "KernelSU"},
            {"com.rifsxd.ksunext", "KernelSU Next"},
            {"com.sukisu.ultra", "SukiSU Ultra"},
            {"me.bmax.apatch", "APatch"},
            {"com.topjohnwu.magisk", "Magisk"},
            {"io.github.huskydg.magisk", "Magisk Delta"},
    };

    /** 一次探测的结果。 */
    static final class Snapshot {
        final boolean checked;
        final boolean granted;
        final String manager;
        final String version;

        Snapshot(boolean checked, boolean granted, String manager, String version) {
            this.checked = checked;
            this.granted = granted;
            this.manager = manager;
            this.version = version;
        }

        /** 还没测过时的占位。 */
        static Snapshot pending() {
            return new Snapshot(false, false, "", "");
        }
    }

    private static Snapshot cached = Snapshot.pending();
    private static final AtomicBoolean probing = new AtomicBoolean(false);

    private RootInfo() {}

    /** 当前缓存。没测过时 {@link Snapshot#checked} 为 false。 */
    static Snapshot get() {
        return cached;
    }

    /**
     * 起一次后台探测。并发调用只会真正跑一次（{@link #probing} 把关）。
     *
     * @param done 探测结束后在主线程回调，调用方据此重画那一行；可为 null
     */
    static void probe(Context context, Runnable done) {
        if (!probing.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            boolean granted = suGranted();
            String[] manager = findManager(context);
            cached = new Snapshot(true, granted, manager[0], manager[1]);
            probing.set(false);
            if (done != null) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(done);
            }
        }, "opairdrop-root-probe").start();
    }

    /** su 是否真的授权了：跑一句 id -u，拿到 uid 0 才算。 */
    private static boolean suGranted() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id -u")
                    .redirectErrorStream(true).start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                line = reader.readLine();
            }
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return false;
            }
            return process.exitValue() == 0 && line != null && line.trim().startsWith("0");
        } catch (Exception failed) {
            if (process != null) {
                process.destroy();
            }
            return false;
        }
    }

    /** 认出装了哪个管理器、什么版本；没装就返回空串。 */
    private static String[] findManager(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String[] candidate : MANAGERS) {
            try {
                PackageInfo info = pm.getPackageInfo(candidate[0], 0);
                String label = candidate[1];
                if (info.applicationInfo != null) {
                    CharSequence fromApp = pm.getApplicationLabel(info.applicationInfo);
                    if (fromApp != null && fromApp.length() > 0) {
                        label = fromApp.toString();
                    }
                }
                String version = info.versionName == null ? "" : info.versionName;
                return new String[]{label, version};
            } catch (PackageManager.NameNotFoundException ignored) {
                // 这一个是没装的，试下一个。
            }
        }
        return new String[]{"", ""};
    }
}
