package com.oshare.airdrop;

import java.io.IOException;
import java.net.Socket;

/**
 * 把一条连接掐掉，让对面看见的是「中断」而不是「结束」。
 *
 * <p># 为什么不是就地 {@code close()}
 *
 * <p>{@code SSLSocket} 的 {@code close()} 不是「把描述符还回去」那么安静：Conscrypt 会先
 * {@code drainOutgoingQueue}，写一个 TLS {@code close_notify}，那是**一次真实的网络写**。
 * 在广播接收器或任何主线程调用点上，这一步直接就是
 * {@code NetworkOnMainThreadException} —— 2026-11-19 14:12 那次「一点【取消接收】整个进程就
 * 没了」的崩溃栈，{@code Unable to start receiver} 底下压着的就是它。
 *
 * <p># 为什么还要 {@code SO_LINGER(0)}
 *
 * <p>只把 close 挪到别的线程还不够：正常 close 会等发送缓冲排空、然后发 FIN，对面看到的
 * 是「传完了」。而 {@code setSoLinger(true, 0)} 让内核立刻发 RST，缓冲里剩下的直接丢掉——
 * 这与对面自己中途取消时发给我们的那一个信号是同一种，所以两边对「人为中断」的理解不会
 * 出现一个以为是完成、一个以为是失败的分歧。
 *
 * <p>顺序不能反：先 linger 再 close。反过来的话 close 已经开始走 TLS 收尾，那时再设就晚了。
 *
 * <p># 为什么另起一条线程
 *
 * <p>两个调用点（{@link ReceiveHost.Attempt#cancel}、{@link SendActivity#cancelSend}）都在
 * 主线程上，而 {@code close()} 在这里要做的第一件事就是写网络。用一条短命的 daemon 线程
 * 而不是线程池：它只活几十微秒，且进程要退的时候不该被它拖住。
 */
final class HangUp {

    private HangUp() {}

    /**
     * 现在就断，不等缓冲。
     *
     * @param peer 要掐的那条连接；{@code null} 什么也不做（调用方常常还没连上）
     */
    static void now(Socket peer) {
        if (peer == null) {
            return;
        }
        Thread cutting = new Thread(() -> {
            try {
                // 先设，再关。
                peer.setSoLinger(true, 0);
            } catch (IOException alreadyGone) {
                // 描述符早就没了（没连上就取消、或者对端先断的）。照样往下走，close 幂等。
            }
            try {
                peer.close();
            } catch (IOException alreadyGone) {
                // 已经断了：那条正在跑的线程自己会遇到异常并走到收尾，不用在这里补一句。
            }
        }, "opairdrop-hangup");
        cutting.setDaemon(true);
        cutting.start();
    }
}
