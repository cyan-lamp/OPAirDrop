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
     * 排空窗口：半关之后给对端读走 FIN、并把它还在灌的数据读完丢掉的时间。
     *
     * <p>close 变不变 RST 的分界线是「接收缓冲空不空」，而缓冲空不空只取决于**有没有人把
     * 字节读走**——睡多久都没用，对面 3 MB/s 的上传几毫秒就把缓冲灌满。所以必须自己读。
     * 实测 iPhone 在收到 FIN 的当秒就停了发送（EOF 自然到来），3 秒只是兜底。
     *
     * <p>时长可调：{@code debug.opairdrop.drain_ms}（仅 debug 构建认，缺省 3000）。
     * 2026-11-24 实测我们这边占 2.7-3.0 s（排空打满窗口）、iPhone 显示失败共 ~9 s——剩下
     * ~6 s 是 iOS 内部传播，动不了。排空时长是否可以再压（甚至为 0：FIN 是否单独就是
     * 失败信号、close 时的 RST 是否会毁掉它）用这个旋钮实测：
     *
     * <pre>
     *   su -c 'setprop debug.opairdrop.drain_ms 1000'    # 或 0
     * </pre>
     */
    private static long drainMs() {
        if (!BuildConfig.DEBUG) {
            return 3000;
        }
        try {
            // SystemProperties 是 @hide API，SDK 编不过，走反射拿。
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String raw = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, "debug.opairdrop.drain_ms", "3000");
            long n = Long.parseLong(raw.trim());
            return (n >= 0 && n <= 10000) ? n : 3000;
        } catch (Throwable bad) {
            return 3000;
        }
    }

    /**
     * 中途取消：半关（FIN）→ 排空对端 → 干净关闭。**不发任何响应字节。**
     *
     * <p>这是 2026-11-24 02:15 四模式 A/B 实测的胜者。同一台 iPhone、同一条连接、四个模式
     * 逐个试（{@code debug.opairdrop.cancel_mode} 开关，取消时现读）：
     *
     * <table>
     *   <tr><th>模式</th><th>做法</th><th>iPhone 反应</th></tr>
     *   <tr><td>0</td><td>写 400 → 睡 1.5 s → close（多半变 RST，2026-11-23 之前的旧行为）</td>
     *       <td>无反应，停在「正在发送」</td></tr>
     *   <tr><td>1</td><td>写 400 → FIN → 排空 → 干净 close</td><td>无反应</td></tr>
     *   <tr><td>3</td><td>写 500 → FIN → 排空 → 干净 close</td><td>无反应</td></tr>
     *   <tr><td>4</td><td>不发响应，FIN → 排空 → 干净 close</td>
     *       <td><b>几秒后显示失败</b></td></tr>
     * </table>
     *
     * <p># 为什么带响应的模式反而全灭
     *
     * <p>HTTP 允许请求体没读完就先回响应，但 iOS 的上传栈不吃这一套：请求体发到一半收到
     * 服务器响应，它不当作「被拒绝」，而是当作一次异常，走它自己的重试/等待——UI 停在
     * 「正在发送」。而纯 FIN（半关）它认得：服务器正式结束了这条连接 → 传输失败。也就是说
     * **问题从来不是「没发应用层信号」，是那个信号本身把正确的断法干扰了**。
     *
     * <p>此前 2026-11-23 的版本（写 400 + 睡 1.5 s + close）有两层错：close 时接收缓冲非空
     * 变 RST（RST 在 iOS 眼里是网络错误，静默重试），就算 400 活着送达 iOS 也不认 early
     * response。两条一起死。
     *
     * <p># 为什么排空之后不再 linger(0)
     *
     * <p>排空的目的就是让缓冲空掉，缓冲空了 close 自然走 FIN；再设 linger(0) 反而把胜利
     * 果实换回 RST。
     *
     * <p>排空期间 {@code Listener} 的读线程还在并发读同一条连接：两边抢字节，谁读到算谁的，
     * 读线程迟早看到 EOF/异常走收尾，不影响结局——取消那一刻它本来就该停了。
     */
    static void refuse(Socket peer) {
        if (peer == null) {
            return;
        }
        Thread cutting = new Thread(() -> {
            long asked = android.os.SystemClock.elapsedRealtime();
            try {
                peer.shutdownOutput();
            } catch (IOException alreadyGone) {
                // 对面先断了。照样走排空和关闭，幂等。
            }
            drain(peer);
            try {
                peer.close();
            } catch (IOException ignored) {
                // 幂等。
            }
            Log.i("OPAirDrop", "refuse: fin+drain+close done in "
                    + (android.os.SystemClock.elapsedRealtime() - asked) + " ms");
        }, "opairdrop-refuse");
        cutting.setDaemon(true);
        cutting.start();
    }

    /** 把对端还在灌的数据读完丢掉，直到 EOF 或超时——缓冲空了 close 才是 FIN 而不是 RST。 */
    private static void drain(Socket peer) {
        long until = android.os.SystemClock.elapsedRealtime() + drainMs();
        try {
            peer.setSoTimeout(250);
            java.io.InputStream in = peer.getInputStream();
            byte[] sink = new byte[8192];
            while (android.os.SystemClock.elapsedRealtime() < until) {
                if (in.read(sink) < 0) {
                    break;
                }
            }
        } catch (IOException done) {
            // EOF、超时、或对面 rst 了：都是"可以关了"。
        }
    }

    /**
     * 现在就断，不等缓冲。
     *
     * <p>这条路只用于**我们自己的发送端**掐自己（{@link SendActivity#cancelSend}）：对面
     * （iPhone）是接收方，苹果的接收实现对断连接的反应是立刻标失败——2026-11-24 实测
     * 「iPhone 中途点终止 → 我们马上显示发送失败」走的就是这条对称性，RST 恰好是对的语言。
     * 接收方向的取消（对面是发送方）走 {@link #refuse}：那边是苹果的发送实现，认 FIN 不认
     * RST——见 {@link #refuse} 的实测表。
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
            Log.i("OPAirDrop", "hangup: RST handed to the kernel after "
                    + (android.os.SystemClock.elapsedRealtime() - asked) + " ms");
        }, "opairdrop-hangup");
        cutting.setDaemon(true);
        cutting.start();
    }
}
