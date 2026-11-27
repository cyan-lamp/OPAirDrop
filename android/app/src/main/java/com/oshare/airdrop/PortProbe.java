package com.oshare.airdrop;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 端口探测的唯一执行器（评审拆分路线的第 4 刀：从 {@code Peers} 里切出来的执行层）。
 *
 * <p>全工程每一处「TCP 连一次它的 8770」都必须从这里走：敲门、守望、墓碑复核用的是
 * 同一条 connect 实现。集中在一处，超时语义、失败分类和「我们的路由问题不算对端的账」
 * 这条规矩才不会被某条路径悄悄绕开。
 */
final class PortProbe {

    private PortProbe() {
    }

    /**
     * 一次敲门等多久。
     *
     * <p>同一条链路上 {@code ping6} 实测 RTT min 9.6 / avg 213.9 / max 302.1 ms，900 是最大值的
     * 三倍，够容一次重传。再长就只是让「走了」这件事晚一点被发现。
     */
    static final int KNOCK_TIMEOUT_MS = 2_500;

    /**
     * 守望探测一次允许多久，和 {@link PortProbe#KNOCK_TIMEOUT_MS} 分开。
     *
     * <p>守望问的是「**现在**通不通」，敲门问的是「它到底在不在」，两件事对时间的要求相反：
     * 前者一晚一拍就晚一次显示，后者要顶得住 AWDL 时隙调度吃掉一个 SYN。实测这一侧的端口
     * 应答只要 9–13 毫秒（{@code ECONNREFUSED} 也是 9 毫秒就回来），400 毫秒是三十倍余量。
     *
     * <p>把失败当失败仍然安全：守望**从不记账**。它探测失败只是转给 {@link #knockNow}，由
     * {@link #knock} 用满 {@link PortProbe#KNOCK_TIMEOUT_MS} 再量一次——所以短超时换来的误判，代价
     * 只是一次多余的敲门，不会是误删。
     */
    static final int WATCH_TIMEOUT_MS = 400;

    /**
     * 探一次它的服务端口。
     *
     * @return 连上了返回 {@code null}；否则返回「为什么没连上」，原样带异常名和消息——
     *         调用方要靠 {@link #ourFault} 把「它不接」和「我们发不出去」分开。
     *         地址解析失败（{@code address(peer)} 抛的）也走这一条出口：连地址都组不出来，
     *         和连不上是一回事。
     */
    static String probe(Peers.Peer peer, int timeoutMs) {
        Socket probe = new Socket();
        try {
            probe.connect(new InetSocketAddress(Peers.address(peer), peer.port), timeoutMs);
            return null;
        } catch (Exception failure) {
            String message = failure.getMessage();
            return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        } finally {
            try {
                probe.close();
            } catch (Exception ignored) {
                // 已经废了。
            }
        }
    }

    /** 一参版：用 {@link #KNOCK_TIMEOUT_MS}——敲门要顶得住 AWDL 时隙调度吃掉一个 SYN。 */
    static String probe(Peers.Peer peer) {
        return probe(peer, KNOCK_TIMEOUT_MS);
    }

    /**
     * 这次失败说的是「我们发不出去」，还是「它不接」。
     *
     * <p>{@code Network is unreachable} / {@code No route to host} 属于前者：曾经实测过
     * {@code awdltun0} 上一条 IPv6 路由都没有，于是 {@code ip -6 route get} 把 {@code fe80::}
     * 送到 {@code rmnet_data3} 去了。拿这种失败去删设备，就是把自己家的路由故障记在对面头上。
     */
    static boolean ourFault(String why) {
        return why.contains("unreachable") || why.contains("No route");
    }

    /**
     * 这次失败是不是「它还在、只是不接」——也就是收到了 RST（{@code ECONNREFUSED}）。
     *
     * <p><b>RST 是对方的 TCP 栈发回来的，所以它本身就是「对方在这条链路上活着」的证据。</b>
     * 只有「连 SYN 都没人答」（超时）才是「可能不在了」。把这两件事混成一种失败，是
     * 2026-11-27 那次「AirDrop 一直开着、设备却从列表消失」的根因：iPhone 的隔空投送接收端
     * 不是常开的，它关着的时候我们收到的是 RST，于是被判成了离场。
     *
     * <p>用户的判据说得很直接：**看对方在不在链路上，而不是等它发 mDNS**。RST 正好是可随时
     * 主动发起、又不需要 ICMP 权限的那种「在不在」探测。
     */
    static boolean refused(String why) {
        return why.contains("ECONNREFUSED") || why.contains("Connection refused");
    }
}
