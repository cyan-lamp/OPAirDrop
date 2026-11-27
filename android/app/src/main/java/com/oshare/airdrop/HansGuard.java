package com.oshare.airdrop;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 常驻的 hans 豁免自检（2026-11-27 加）。
 *
 * <p># 它守的是什么
 *
 * <p>ColorOS 的 netd 会把某些“后台应用”的 uid 写进 BPF 表
 * {@code map_oplus-netd_app_hans_limit_map}，然后 {@code oplus_fw_OUTPUT/INPUT} 上挂的
 * {@code skfilter_*_hans} 程序就会 DROP 掉这个 uid 的 AWDL/组播包——表现是接收中途被本机
 * 内核掐断（`Software caused connection abort`）与发送不稳。删掉 map 里我们那一个 key 即可
 * 双向放行（见 {@code tools/hans_guard.sh} 的完整取证）。
 *
 * <p># 为什么写进 app，而不是继续用 /data/adb/service.d
 *
 * <p>2026-11-27 实测：`/data/adb/service.d/99-opairdrop-hans.sh` 在 APatch 上**从未被执行**
 * （守护进程不在、pidfile 里是早已死掉的 pid）。而 netd 会在熄屏等时机把 key 写回来，
 * 靠“开机脚本 + 常驻 sh”这条路在 APatch 上等于没有。放进前台服务里每 30 秒查一次，
 * 不依赖任何 root 管理器的自启机制。
 *
 * <p># 它现在可能什么都不做
 *
 * <p>2026-11-27 实测 map 里那 6 个 key 都不是本应用 uid（10449），所以这一步当前是空转。
 * 留着是因为它是唯一能兜住“netd 把我们也加进去”这种东西的机制；失败（没有 root）时静默，
 * 不影响任何正常功能。
 */
final class HansGuard {

    private static final String TAG = "OPAirDrop";
    private static final long EVERY_MS = 30_000;
    private static final String MAP = "/sys/fs/bpf/map_oplus-netd_app_hans_limit_map";

    private static boolean started;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            sweep();
            MAIN.postDelayed(this, EVERY_MS);
        }
    };

    private HansGuard() {
    }

    /** 幂等：重复调用只留一条循环。 */
    static void start(Context context) {
        if (started) {
            return;
        }
        started = true;
        MAIN.post(TICK);
        Log.i(TAG, "hans guard: 自检已启动，每 " + (EVERY_MS / 1000) + " 秒查一次 BPF 表");
    }

    /**
     * 查一次：我们 uid 的 key 在就删掉。
     *
     * <p>key 是 4 字节小端（uid &lt; 65536，高两字节恒 0），和 `hans_guard.sh` 里
     * {@code uid_to_key} 同一个算法。
     */
    private static void sweep() {
        int uid = android.os.Process.myUid();
        String low = String.format("0x%02x", uid & 0xff);
        String high = String.format("0x%02x", (uid >> 8) & 0xff);
        String command = "bpftool map delete pinned " + MAP
                + " key " + low + " " + high + " 0x00 0x00";
        try {
            java.lang.Process process = new ProcessBuilder("su", "-c", command).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errors = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String out = reader.readLine();
            String err = errors.readLine();
            boolean done = process.waitFor() == 0;
            process.destroy();
            if (done) {
                // 删成功说明上一轮 netd 把我们加回去了：这一行是唯一能证明它发生过的证据。
                Log.i(TAG, "hans guard: 删掉了本应用 uid=" + uid + " 的 BPF key — AWDL 已放行");
            } else if (err != null && !err.isEmpty() && !err.contains("No such file")) {
                // key 本来就不在是最常见的情况（err 是 No such file / No such key），不记；
                // 其它错误（没有 root、bpftool 不在）记一次，方便排查。
                Log.i(TAG, "hans guard: 跳过（" + err + "）");
            }
        } catch (Throwable failed) {
            // 没 root / 没有 bpftool：这一层就没有了，静默。它只是兜底，不是功能前提。
        }
    }
}
