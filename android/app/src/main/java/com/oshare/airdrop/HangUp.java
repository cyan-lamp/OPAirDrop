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

    /**
     * 中途取消：先告诉对方「剩下的我不收了」，再关。
     *
     * <p>为什么不再只用 RST：实测本机 2 ms 就把 RST 交给内核，而 iPhone 那边要等 6-7 秒
     * 才显示失败——那个量级正是它自己的重传超时。RST 是一句话都不说的断线，对端没有
     * 「响应」可对应，只能等。
     *
     * <p>HTTP 允许在请求体没读完时就回答，所以这里写一段完整的 400。用 400 而不是 401：
     * 401 在这个协议里是「人拒绝了邀请」，而这里说的是另一件事——邀请被接受了、我不打算
     * 收完。
     *
     * <p>写完**不能立刻关**：接收缓冲里只要还有对端没被读走的字节，close 就是 RST，而 RST
     * 会把对端还没读走的字节一起丢掉，包括刚写出去的这段。所以留一小段时间让它自己关；
     * 卡片早就由 {@link ReceiveHost.Attempt#cancel} 撤掉了，这里的等待不会让人等。
     */
    static void refuse(Socket peer) {
        if (peer == null) {
            return;
        }
        Thread cutting = new Thread(() -> {
            long asked = android.os.SystemClock.elapsedRealtime();
            try {
                java.io.OutputStream out = peer.getOutputStream();
                out.write(RESPONSE.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(1500);
            } catch (Throwable ignored) {
                // 对面已经走了、或者流已经关了：那就只剩"关掉"这一个动作。
            }
            try {
                peer.close();
            } catch (IOException ignored) {
                // 幂等。
            }
            Log.i("OPAirDrop", "refuse: 400 handed to the kernel after "
                    + (android.os.SystemClock.elapsedRealtime() - asked) + " ms");
        }, "opairdrop-refuse");
        cutting.setDaemon(true);
        cutting.start();
    }

    private static final String RESPONSE =
            "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

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
            long asked = android.os.SystemClock.elapsedRealtime();
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
            // 这一行是为了回答「我们是不是发得太慢」：它量的是从按下取消到 RST 交给内核的
            // 墙钟时间，不包含任何等待。若这一行总是个位数毫秒，而对面过几秒才说失败，
            // 那延迟就不在我们这侧——别再往这里加逻辑。
            Log.i("OPAirDrop", "hangup: RST handed to the kernel after "
                    + (android.os.SystemClock.elapsedRealtime() - asked) + " ms");
        }, "opairdrop-hangup");
        cutting.setDaemon(true);
        cutting.start();
    }
}
