package com.oshare.airdrop;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

import javax.net.ssl.SSLSocket;

/**
 * 发送管线（评审拆分路线：从 {@code SendActivity} 里切出来的执行层）——这一半是
 * **连接层**：TCP 连接、TLS 握手、证书钉住、安全关闭。
 *
 * <p>全部是纯函数：输入对端的账目字段与超时，输出一条连好的 socket 或一个带原因的
 * 异常。要不要连、什么时候连、连上之后传什么，是 {@code SendActivity} 的传输账目的活。
 */
final class SendPipeline {

    private static final String TAG = "OPAirDrop";

    /**
     * 发送缓冲区的上限，六十四 KB。
     *
     * <p>不设的时候平台会把它长到一兆以上，于是「写完了」这句话就不再是「发出去了」：整份
     * 档案落进内核队列时调用就返回了，界面上那一圈立刻走到 100%，而线路上一块还没走完。
     * 留在这里的数字与参考实现相同（{@code HttpCall.SEND_BUFFER}），它的日志里记着代价：
     * 24 MB 的视频在 50 ms 内报「已写 1.06 MB」，然后是 7.24 秒的静默。
     *
     * <p>这个尺寸仍然远大于这条链路的带宽时延积，所以它限制的是「最多能抢跑多少」，不是
     * 吞吐。必须在下 {@code connect} 之前设：平台在建 socket 的时候就把队列定好，事后再设
     * 只是给一个尺寸已经错了的队列换个标签。
     */
    static final int SEND_BUFFER = 64 * 1024;

    /** TCP 连接：选项在 connect 之前设，地址带 scope。预查询也用这条，不登记 live。 */
    static Socket rawSocket(Peers.Peer peer, int connectMs, int readMs) throws IOException {
        Socket raw = new Socket();
        // 两个选项都要在 connect 之前设，理由见 {@link #SEND_BUFFER}：队列的尺寸是建 socket
        // 时定的。失败不算致命——不设也能传，只是进度会变成「写进队列了多少」。
        try {
            raw.setTcpNoDelay(true);
        } catch (SocketException refused) {
            Log.i(TAG, "tcp nodelay refused: " + refused);
        }
        try {
            raw.setSendBufferSize(SEND_BUFFER);
        } catch (SocketException refused) {
            Log.i(TAG, "send buffer " + SEND_BUFFER + " refused: " + refused);
        }
        // 地址不能直接当字符串用：链路本地 IPv6 少了 scope，connect 会立刻回 EINVAL。
        raw.connect(new InetSocketAddress(Peers.address(peer), peer.port), connectMs);
        raw.setSoTimeout(readMs);
        // 关掉 linger：对端先走的时候，本端已经写下去、还没被确认的那一段不该让 close 卡住。
        try {
            raw.setSoLinger(false, 0);
        } catch (SocketException refused) {
            Log.i(TAG, "so_linger refused: " + refused);
        }
        return raw;
    }

    /**
     * TLS 握手 + 证书钉住。{@code raw} 由调用方拥有，失败时由调用方关。
     *
     * <p>指纹为空时照常连接（接受任意自签链）：评审 P1-18 曾试图在这里拒发，2026-11-24
     * 04:18 实测推翻——真实 iPhone 在「隔空投送=所有人」模式下本来就不带 {@code h=}
     * 指纹（联系人未匹配时 iOS 省略它），硬拒发把正常传输全拒了。指纹在场时钉住
     * 照旧生效；不在场是真实协议的常态，不是攻击信号，记一行日志即可。
     */
    static SSLSocket connectTls(Peers.Peer peer, Socket raw) throws IOException {
        if (peer.fingerprint.isEmpty()) {
            Log.i(TAG, "no fingerprint for " + peer.name + ": the certificate cannot be pinned");
        }
        try {
            SSLSocket tls = (SSLSocket) Identity.clientContext(peer.fingerprint).getSocketFactory()
                    .createSocket(raw, peer.host.isEmpty() ? peer.address : peer.host, peer.port, true);
            tls.setUseClientMode(true);
            tls.startHandshake();
            Log.i(TAG, "connected to " + peer.name + ", sndbuf " + raw.getSendBufferSize()
                    + ", nodelay " + raw.getTcpNoDelay());
            return tls;
        } catch (Exception failed) {
            // 指纹不符是这条链上唯一一种「对方不是它说的那台机器」的失败，而且它到这一层已经
            // 被 JSSE 套了两三层。先把它捞出来换成自己的类型，界面上才不会说成「发送失败」。
            PeerMismatch mismatch = PeerMismatch.of(failed);
            if (mismatch != null) {
                throw mismatch;
            }
            if (failed instanceof IOException) {
                throw (IOException) failed;
            }
            throw new IOException("TLS did not start: " + failed, failed);
        }
    }

    /** 关掉一条连接，关不掉也只是记一笔——它本来就可能是对面先关的。 */
    static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception alreadyGone) {
            Log.i(TAG, "closing a connection that was already gone", alreadyGone);
        }
    }
}
