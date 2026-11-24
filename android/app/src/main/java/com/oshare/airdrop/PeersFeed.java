package com.oshare.airdrop;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * UI 节流推送（评审拆分路线的第 6 刀：从 {@code Peers} 里切出来的出口层）。
 *
 * <p>只管「把一份对端快照送到主线程」这一件事，以及那条把「找不到设备」的三种原因
 * 分开的节流日志。**谁上屏、谁不上屏**（unproven 过滤）是表的活，归 {@code Peers.snapshot}；
 * 这一层拿到的已经是过滤后的名单。
 */
final class PeersFeed {

    private static final String TAG = "OPAirDrop";

    private final Peers.Watcher watcher;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 上一次记日志时那一串「谁在、在哪」。 */
    private volatile String lastSeen;

    PeersFeed(Peers.Watcher watcher) {
        this.watcher = watcher;
    }

    /** 推给主线程。合并成一次回调，避免一屏几十次刷新。 */
    void publish(List<Peers.Peer> copy, String problem) {
        announce(copy, problem);
        main.post(() -> watcher.onPeers(copy, problem));
    }

    /**
     * 发现到的东西变了才写一行。
     *
     * <p>写这一行的理由：「找不到设备」有三种完全不同的原因——组播出不去、对端不答、
     * 答了但记录凑不齐——而这三者在界面上长得一模一样（都是「正在搜索附近设备……」）。
     * 唯一能把它们分开的就是这一条：跳过了这一行，就只剩猜。
     *
     * <p>只在变化时写，是因为浏览每两秒问一次，每问一次都写会把周围的日志冲掉。
     */    private void announce(List<Peers.Peer> copy, String problem) {
        StringBuilder line = new StringBuilder();
        int ready = 0;
        for (Peers.Peer peer : copy) {
            if (!peer.connectable()) {
                continue;
            }
            ready++;
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(peer.name).append('@').append(peer.address).append(':').append(peer.port);
        }
        String now = ready + "|" + line + "|" + problem;
        if (now.equals(lastSeen)) {
            return;
        }
        lastSeen = now;
        if (problem != null) {
            Log.w(TAG, "browse: stopped with " + ready + " connectable: " + problem);
        } else if (ready == 0) {
            trace("nothing connectable yet (" + copy.size() + " instance(s) with partial records)");
        } else {
            trace(ready + " connectable — " + line);
        }
    }

    /** 浏览这一层的状态很少变，变的时候留一行；多了反而看不见。 */
    private static void trace(String line) {
        Log.i(TAG, "browse: " + line);
    }
}
