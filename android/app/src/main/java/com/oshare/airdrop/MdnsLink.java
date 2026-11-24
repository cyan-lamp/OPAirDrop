package com.oshare.airdrop;

import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 浏览用的网卡选择（评审拆分路线的第一刀：从 {@code Peers} 里切出来的纯函数层）。
 *
 * <p>这一层**无状态**：每问一次答一次，不记任何东西。socket 的生命周期、组播锁和重开
 * 的账目仍在 {@code Peers} 里，它们是下一步（{@code MdnsLink} 的有状态部分）的活。
 */
final class MdnsLink {

    /** iPhone 只认的 AWDL 隧道。 */
    static final String AWDL_TUN = "awdltun0";

    /** 同一条链路的无线电那一侧。回包几乎全被驱动吞掉，只当兜底。 */
    static final String AWDL_RADIO = "wonder0";

    private MdnsLink() {
    }

    /**
     * 在哪块网卡上问。
     *
     * <p>{@code awdltun0} 第一，因为 iPhone 只在那条链路上，而它是这一页唯一的对手盘。
     * {@code wonder0} 是同一条链路的无线电侧，驱动几乎不把回包交上来（35 秒里收到 9295 帧，
     * 它的收包计数只动了 8），所以只当兜底。两块都没有才退回 Wi-Fi：那时能找到的是连着同一个
     * 路由器的 Mac，不是手机。
     */
    static NetworkInterface link() {
        return link(true);
    }

    /**
     * 同上，但可以拒绝兜底。
     *
     * <p>{@code fallback=false} 时只认 AWDL 那两块，没有就返回 {@code null}——把「这条路不在」
     * 当成一个正当答案交给调用方，而不是硬塞一块能开却不通的网卡。
     */
    static NetworkInterface link(boolean fallback) {
        try {
            for (String name : new String[]{AWDL_TUN, AWDL_RADIO}) {
                NetworkInterface nic = named(name);
                if (nic != null) {
                    return nic;
                }
            }
            if (!fallback) {
                return null;
            }
            for (NetworkInterface candidate : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = candidate.getName();
                if (candidate.isUp() && (name.startsWith("wlan") || name.startsWith("ap"))) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // 拿不到网卡列表就交给系统默认路由，下面返回 null。
        }
        return null;
    }

    /** 按名字取一块正在用的网卡；不存在或者没起来都算没有。 */
    static NetworkInterface named(String name) {
        try {
            NetworkInterface nic = NetworkInterface.getByName(name);
            return nic != null && nic.isUp() ? nic : null;
        } catch (Exception gone) {
            return null;
        }
    }
}
