package com.oshare.airdrop;

import android.content.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对方真名的持久缓存，键是它的链路本地 IPv6 地址。
 *
 * <p># 为什么不能用 mDNS 实例名做键
 *
 * <p> iPhone 广播的实例名是一串每几分钟就变的 hash（rotating identifier），SRV 里的主机名也是
 * 一个会变的 UUID——两者都认不出「还是那台机器」。真正稳定的是 {@code fe80::} 地址：它由网卡
 * MAC 派生，只要对方还在这条 AWDL 链路上就不变。抓包验证过同一台 iPhone 三次广播，hash 与
 * UUID 每次不同，唯有这个地址始终是 {@code fe80::5c55:2bff:feec:646}。
 *
 * <p># 为什么值得缓存
 *
 * <p>真名只存在于 {@code /Discover} 应答里，而那个请求要连上 TLS 才发得出。设备列表在点击之前
 * 拿不到它。缓存把「连一次问到的名字」留到下一次：同一台设备以后一出现，列表立刻显示真名，
 * 不必等预查询跑完那一两秒。预查询仍然对未缓存的设备跑，负责第一次。
 */
final class PeerNames {

    private static final String PREFS = "peer_names";

    /** 进程内的镜像：读多写少，UI 线程和预查询线程都会碰，用并发表。 */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

    private static android.content.SharedPreferences prefs;

    private PeerNames() {}

    /** 进程启动时调一次。 */
    static void load(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        NAMES.clear();
        NAMES.putAll((Map) prefs.getAll());
    }

    /** 缓存里有名字吗。有就直接用，不必连 TLS。 */
    static String get(Peers.Peer peer) {
        String key = key(peer);
        return key == null ? null : NAMES.get(key);
    }

    /** 记下这台设备的真名。空名字不存——那不是答案，是对方没填。 */
    static void put(Peers.Peer peer, String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String key = key(peer);
        if (key == null) {
            return;
        }
        NAMES.put(key, name);
        if (prefs != null) {
            prefs.edit().putString(key, name).apply();
        }
    }

    /**
     * 稳定键：链路本地 IPv6 的主机部分，去掉前导零压缩带来的差异。
     *
     * <p>地址已经由 {@link Peers#address} 归一成不带 scope 的形式。IPv4 的局域网地址也接受
     * （同一局域网的 Mac 走那条路），只是 DHCP 会换——那种情况下缓存过期只导致多查一次，不会
     * 认错人。
     */
    static String key(Peers.Peer peer) {
        String address = peer.address;
        if (address == null || address.isEmpty()) {
            return null;
        }
        // 去掉可能残留的 %scope，链路本地 IPv6 的 zone id 不是设备身份的一部分。
        int percent = address.indexOf('%');
        return (percent < 0 ? address : address.substring(0, percent)).toLowerCase();
    }
}
