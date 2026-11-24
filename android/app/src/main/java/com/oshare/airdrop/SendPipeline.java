package com.oshare.airdrop;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Set;

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

    /**
     * 这一趟发送走到了哪儿。
     *
     * <p>只回答两个问题，而它们在失败时说的不是同一件事：{@link #moving}——对面点了接受没有；
     * {@link #sentBytes()}——有多少字节真的写进了那几条 socket。传输线程里那几个进度回调填，
     * 同一个线程的 catch 读，所以**单流时**不需要锁。
     *
     * <p># 多流之后为什么多了原子量
     *
     * <p>N 条流各在自己的线程上填同一块板子，「同一个线程」这条前提没有了；而下面那个
     * {@code catch} 又只在其中一条线程上读它。所以逐条流的计数用 {@link AtomicLongArray}
     * （一条流一个格子，谁也不写别人的），几条汇总量用原子布尔/原子整数。取读侧总和时
     * 各格子各取一次加起来——中途可能读到半新半旧的一组和，而这是进度条，不是账本。
     */
    static final class Flight {
        /** 有几条流在跑。{@link #sent} 就是按它开的。 */
        final int lanes;

        /** 每条流最近一次报的「已经写下多少」。各流各算自己的分母，所以不能合成一个数。 */
        final java.util.concurrent.atomic.AtomicLongArray sent;

        /** 进度相位进过 SENDING：对面已经在手机上点了接受。任意一条流进过就算。 */
        final java.util.concurrent.atomic.AtomicBoolean moving = new java.util.concurrent.atomic.AtomicBoolean();

        /**
         * 一条流倒下之后置位：其余几条没有继续的意义。
         *
         * <p>与人按下的取消分开记，因为两件事在界面上的说法完全不同——取消是「已取消」，
         * 而这里要如实说原因。{@code cancelling} 只由人点的那一下置位。
         */
        final java.util.concurrent.atomic.AtomicBoolean abort = new java.util.concurrent.atomic.AtomicBoolean();

        /** 第一个倒下的原因。谁先到谁算，后面的不再覆盖。 */
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();

        /** 已经跑完的流数：{@code DONE} 每条流各来一次，界面只该在最后一条上说一次。 */
        final java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();

        /** 日志去重：上一次记下来的整体十分位。整件事一条曲线，不是每条流一条。 */
        final java.util.concurrent.atomic.AtomicInteger decile = new java.util.concurrent.atomic.AtomicInteger(-1);

        Flight(int lanes) {
            this.lanes = lanes;
            this.sent = new java.util.concurrent.atomic.AtomicLongArray(lanes);
        }

        /** 几条流加起来写了多少字节。 */
        long sentBytes() {
            long total = 0;
            for (int i = 0; i < lanes; i++) {
                total += sent.get(i);
            }
            return total;
        }
    }

    /** Rust 收尾时给的那张回执。目前只有一个字段有用：实际落地的字节数。 */
    static final class Report {
        long bytes;

        static Report parse(String text) {
            Report report = new Report();
            for (String line : text.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0 && line.substring(0, tab).equals("bytes")) {
                    try {
                        report.bytes = Long.parseLong(line.substring(tab + 1).trim());
                    } catch (NumberFormatException notANumber) {
                        report.bytes = 0;
                    }
                }
            }
            return report;
        }
    }

    /**
     * 问一台设备「你叫什么」：一条连接、一次 /Discover，问完就关。
     *
     * <p>与真正发送用的是同一套连接与钉住（{@link #rawSocket}/{@link #connectTls}），但
     * **绝不登记 live**——一次后台查询不能把正在传的那条连接顶掉。问完即关：对面不会弹窗。
     *
     * @return 对端报来的名字；空白行原样返回，由调用方决定要不要采纳。
     */
    static String discoverName(Peers.Peer peer, int connectMs, int readMs,
                               String settings, String sender, String model, String id)
            throws Exception {
        Socket raw = null;
        SSLSocket tls = null;
        try {
            raw = rawSocket(peer, connectMs, readMs);
            tls = connectTls(peer, raw);
            byte[] answer = Native.discoverName(
                    peer.host.isEmpty() ? peer.address : peer.host,
                    settings, sender, model, id,
                    tls.getInputStream(), tls.getOutputStream());
            return new String(answer, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            closeQuietly(tls != null ? tls : raw);
        }
    }

    /** 一条流的界面回执：pipeline 只报事实（相位与字节），说什么话是调用方的事。 */
    interface LaneWatch {

        /** /Discover 带回了真名。 */
        void name(String peerName);

        /**
         * 一个相位到了。`phase` 是 {@code Native.Progress} 的四个常量；`done`/`whole` 是
         * **整件事**的字节数（多流共用一个分母）；`last` 表示这是不是最后一条流的 DONE。
         */
        void phase(int phase, int lane, long done, long whole, boolean last);
    }

    /** 开一条可以收发的连接；live 登记与取消检查是调用方的账目。 */
    interface Opener {

        SSLSocket open(Peers.Peer peer, int connectMs, int readMs, Flight flight) throws IOException;
    }

    /**
     * 一条流：一趟完整的「发现、请求、发档案」，两条连接。
     *
     * <p>协议侧全部收在这里：取消检查、连接的建立与更换（{@code /Discover} 一条、
     * {@code /Ask}+{@code /Upload} 另一条——iPhone 一条连接只读一个请求，不换线的话写进
     * 同一条连接的 /Ask 换回来的不是 401 不是 reset，是**什么都没有**，本仓为此白等过
     * 整整一个读超时）、字节的记账、进度的上报。界面说什么（状态行、卡片、百分比）由
     * {@link LaneWatch} 决定。
     *
     * @param cancelled 两个取消源（人按的停、另一条流先倒下）合成的当前判定
     * @param whole    这一次投送的总字节数（点发送那一刻就定死了），进度分母
     */
    static byte[] transfer(Peers.Peer peer, List<String> paths, int lane, Flight flight,
            Set<Socket> live, java.util.function.BooleanSupplier cancelled,
            int connectMs, int readMs, int uploadReadMs,
            String settings, String sender, String model, String id, long whole,
            Opener opener, LaneWatch watch) throws Exception {
        if (cancelled.getAsBoolean()) {
            throw new IOException("cancelled before connecting");
        }

        // 第一条：/Discover 走这里，答完就作废。
        SSLSocket[] held = {opener.open(peer, connectMs, readMs, flight)};

        // 第二条：/Ask 和 /Upload 走这里。谁来换、什么时候换由 Rust 定——它在 /Discover
        // 与 /Ask 之间调一次 connect——这里只负责「再连一次」这件事本身。
        Native.Link second = () -> {
            if (cancelled.getAsBoolean()) {
                // 人已经点了取消。再连一条只会让取消慢一步，所以让传输就地从这里失败。
                throw new IOException("cancelled before the second connection");
            }
            Log.i(TAG, "lane " + lane + ": second connection for /Ask");
            closeQuietly(held[0]);
            live.remove(held[0]);
            held[0] = null;
            SSLSocket next = opener.open(peer, connectMs, readMs, flight);
            held[0] = next;
            return next;
        };

        try {
            return Native.sendWithProgress(
                    peer.host.isEmpty() ? peer.address : peer.host,
                    settings,
                    sender,
                    model,
                    id,
                    java.util.UUID.randomUUID().toString(),
                    paths.toArray(new String[0]),
                    held[0].getInputStream(),
                    held[0].getOutputStream(),
                    second,
                    new Native.Progress() {
                        @Override public void name(String peerName) {
                            watch.name(peerName);
                        }

                        @Override public void report(int phaseSent, long sent, long total) {
                            if (phaseSent == Native.Progress.SENDING) {
                                // 从这一刻起等的不再是「对方要不要」，而是「那个档案收完没有」。
                                // 这一句必须**每条流各做一次**，不能拿 moving 当门闩：那是四块
                                // 格子共用的一面旗，只让第一条流改自己那条 socket 的话，另外
                                // 三条会拿着 15 秒的短超时去等接受，然后准时炸掉整趟发送
                                // （2026-11-19 23:29，lane 1 在 15.005 s 报 Read timed out）。
                                // 超时是每条连接各自一份的，改的也是这条流自己的 held[0]。
                                flight.moving.set(true);
                                try {
                                    held[0].setSoTimeout(uploadReadMs);
                                } catch (Exception noTimeout) {
                                    Log.w(TAG, "cannot lengthen the read timeout for the archive",
                                            noTimeout);
                                }
                            }
                            // 只往后两个相位记字节数：等待相位送进来的 0 会把已经走过的量抹掉。
                            // 各流记各流的格子，分母是整件事的总字节，后面几条流陆续起跑时
                            // 百分比不会往回跳。
                            if (phaseSent == Native.Progress.SENDING || phaseSent == Native.Progress.DONE) {
                                flight.sent.set(lane, sent);
                            }
                            // DONE 每条流各来一次，只有最后一条该让界面说「等待确认」。
                            boolean last = phaseSent == Native.Progress.DONE
                                    && flight.finished.incrementAndGet() >= flight.lanes;
                            long done = flight.sentBytes();
                            // 回调每秒来几十上百次。只在一成、两成……这些整点上留痕，
                            // 否则一个 200 MB 的文件能把整段日志冲掉。这条十分位是**整件事**的，
                            // 不是某条流的：多流并行的加速度就要靠它读。
                            int step = whole == 0 ? -1 : (int) (done * 10 / whole);
                            if (step != flight.decile.getAndSet(step)) {
                                Log.i(TAG, "phase sending "
                                        + (whole == 0 ? "?" : done * 100 / whole + "%")
                                        + " (" + done + "/" + whole + " bytes, lane "
                                        + lane + " at " + sent + ")");
                            }
                            watch.phase(phaseSent, lane, done, whole, last);
                        }
                    });
        } finally {
            // 关最后那一条。中途换过连接的话，前一条已经在 second 里关掉了——那条连接上
            // 已经答过一个请求，留着它没有任何用处。
            closeQuietly(held[0]);
            live.remove(held[0]);
            held[0] = null;
        }
    }
}
