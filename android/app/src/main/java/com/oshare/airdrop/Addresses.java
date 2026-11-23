package com.oshare.airdrop;

import java.util.Locale;

/**
 * 一个设备报了多个地址时，选哪一个。
 *
 * <p>从 {@code Peers} 里搬出来的：那是一个 2800 行的类，而这三条判断是纯函数，判错一次
 * 的后果却很直接——选到 `169.254.x.x` 就连不上，选到带 zone 的串则连 {@code InetAddress}
 * 都解析不了。
 *
 * <p>顺序是刻意的，不是按"看起来像"排的：
 *
 * <ol>
 *   <li>{@code fe80:}（链路本地）优先——AWDL 与 Bonjour 都工作在这条链路上，目标设备
 *       报出来的也几乎总是它；</li>
 *   <li>其他 IPv6 次之——能被解析，但可能走的是另一张网卡；</li>
 *   <li>普通 IPv4；</li>
 *   <li>{@code 169.254.x.x} 最后——那是链路本地 IPv4，对方没拿到地址时的兜底，
 *       在这台机器上通常不可达。</li>
 * </ol>
 */
final class Addresses {

    private Addresses() {}

    /**
     * 去掉 zone 后缀并小写。
     *
     * <p>同一个地址在两处日志里可能写成 {@code fe80::1%wlan0} 与 {@code FE80::1}，
     * 带着 zone 的串既不能直接比较也不能直接解析。比较前必须先过这一道。
     */
    static String bare(String address) {
        int percent = address.indexOf('%');
        String bare = percent < 0 ? address : address.substring(0, percent);
        return bare.toLowerCase(Locale.ROOT);
    }

    /** {@code candidate} 是不是比 {@code current} 更该用。{@code current} 为空时总是。 */
    static boolean better(String candidate, String current) {
        if (current.isEmpty()) {
            return true;
        }
        return rank(candidate) > rank(current);
    }

    /** 见类注释里的四条顺序。数字大者优先。 */
    static int rank(String address) {
        if (address.startsWith("fe80:")) {
            return 3;
        }
        if (address.contains(":")) {
            return 2;
        }
        if (address.startsWith("169.254.")) {
            return 0;
        }
        return 1;
    }
}
