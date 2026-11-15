package com.oshare.airdrop;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one channel between the receive UI (which runs inside OShare's process) and the
 * transfer itself (which runs in ours).
 *
 * <p>两边传的东西和以前一模一样：一个 JSON 一条消息，{@link #MSG_COMMAND} 往上、
 * {@link #MSG_EVENT} 往下。变的是走哪条路——原来走 Binder（{@code bindService} 到
 * {@link BridgeServer} 那个 exported 服务），现在走回环 TCP，一行一个 JSON。
 *
 * <p># 为什么不用 Binder 了
 *
 * <p>不是嫌它慢，是这台 ROM 上它根本不通。互传的 {@code targetSdk} 是 37，它的
 * {@code <queries>} 名单里只有它自己认得的那十几个包，没有 {@code com.oshare.airdrop}。
 * 于是它 {@code bindService} 过来时 {@code resolveService} 按调用方 UID 过滤成空，AMS 只
 * 记一句 {@code "Unable to start service ... U=0: not found"}——组件确实在清单里（aapt2
 * 能读出来、{@code exported=true}）、dex 里也确实有，是「看不见」而不是「没装」。{@code <queries>}
 * 只能写在调用方清单里，我们改不了互传那一份；给本包加 {@code QUERY_ALL_PACKAGES} 解决的是
 * 「我们看得见别人」，对被别人看见无效（实测：{@code granted=true} 之后那句 not found 一字
 * 未变）；给互传加则被系统拒绝，因为它不是可变权限。反方向也封着——互传所有 exported 服务
 * 都带 OEM 签名权限（{@code com.oplus.oshare.permission.OSHARE_SWITCH} 之类），我们签不了。
 *
 * <p>抽象 unix socket 也不行：互传跑在 {@code u:r:priv_app:s0:c512,c768}，带自己的 MLS
 * 类别，{@code getenforce} 是 Enforcing，对端类别不同的进程连不上我们的 socket。TCP 端口
 * 不按对端标签裁决，所以只剩这一条。两端都有 {@code INTERNET}（互传有、我们也有），回环
 * 不经过任何网络策略。
 *
 * <p># 为什么还是 Messenger
 *
 * <p>换了线，没换接口。两个进程内部仍然用 {@link Messenger} 说话：一条到套接字的
 * {@link #outbound} 就是「发出去的每一条消息写成一行」，读线程把每一行还原成一条
 * {@link #MSG_EVENT} 投给对面。这样 {@link ReceiveHost} 的状态机、
 * {@link OShareReceiveBridge} 的事件处理一行都不用改——变的只有这条线的两端。
 *
 * <p># 这道门守在哪
 *
 * <p>原来那一侧守门的是 {@code BridgeService.trusted(uid)}：按调用方 UID 认人。TCP 上拿
 * 不到对端 UID，所以握手改成一句暗号（{@link #TOKEN}），对不上就立刻关连接。诚实地说，这比按 UID 认人
 * 弱一档：暗号在 APK 里，能被读出来。剩下的两道护栏是「只听 127.0.0.1」（设备外连不上）
 * 和「指令本身没有危害」——最坏的情况是有人伪造一次「接受」，那也只是让一个本来就要发生
 * 的接收发生。
 */
public final class BridgeMessenger {

    public static final int MSG_HELLO = 1;
    public static final int MSG_COMMAND = 2;
    public static final int MSG_EVENT = 3;

    public static final String EXTRA_JSON = "json";

    /**
     * 两边都认这个端口。
     *
     * <p>固定值而不是写进某个文件：写文件要一个两边都读得到的地方，而两个不同 UID 的应用
     * 之间没有这种地方（各自的数据目录互相不可读，{@code /data/local/tmp} 不属于应用）。
     * 端口落在 32768–60999 之外也无所谓——SELinux 只对 1024 以下的端口按标签裁决
     * {@code name_bind}/{@code name_connect}。
     */
    static final int PORT = 47183;

    /** 握手暗号。见类注释最后一节，知道它比按 UID 认人弱在哪。 */
    static final String TOKEN = "opairdrop-bridge/2";

    private static final String LOOPBACK = "127.0.0.1";

    /** 连不上就是「对面还没起来」，两秒足够了，回环上没有慢这一说。 */
    private static final int CONNECT_TIMEOUT_MS = 2000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 写套接字的那一条线程。
     *
     * <p>单线程而不是每条消息一条：两个 JSON 的先后在这条通道上是有意义的
     * （{@code hello} 必须排在 {@code receive_snapshot} 前面），而单线程队列天然保序。
     * 也不在调用线程上直接写：{@code accept}/{@code deny} 是从互传的主线程上发出来的，
     * 让一次可能阻塞的写去占那条线程不划算。
     */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "OShare-BridgeOut");
        thread.setDaemon(true);
        return thread;
    });

    /** 钩子侧：当前这条连接。另一端是 {@link #out}，两个一起换。 */
    private static Socket socket;
    private static BufferedWriter out;

    /** 正在连。挡住同一个时刻起两条连接。 */
    private static final AtomicBoolean connecting = new AtomicBoolean();

    private BridgeMessenger() {}

    /**
     * 钩子侧：连上宿主。断了之后可以再调，退避由 {@link BridgeReconnect} 负责。
     *
     * <p>整件事在一条后台线程上做，因为这个方法是从互传的主线程被叫起来的，而
     * {@code connect} 会等——哪怕只等两秒。
     *
     * @param onLost 连接断开（含对面还没起来）时回调一次，在 {@link Looper#getMainLooper()} 上
     */
    static void connect(Context context, Messenger client, Runnable onLost) {
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                open(client, onLost);
            } finally {
                connecting.set(false);
            }
        }, "OShare-BridgeIn");
        thread.setDaemon(true);
        thread.start();
    }

    /** 钩子侧：这条连接还在不在。判断「能不能发指令」用它，不再问 Binder。 */
    static boolean linked() {
        synchronized (BridgeMessenger.class) {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }
    }

    /**
     * 钩子侧：{@code onReady} 立刻跑一次。
     *
     * <p>留着这个名字是因为调用点在 {@link OShareReceiveBridge#install}，而那里要说的话
     * 仍然是「钩子装好了，可以开始连了」——它不是「连上了」，连上是
     * {@link OShareReceiveBridge#receive} 里那一下。
     */
    static void observeReadiness(Context context, Runnable onReady) {
        if (onReady != null) {
            MAIN.post(onReady);
        }
    }

    /** UI 侧：一条指令，或一次快照请求。 */
    public static void send(Messenger target, JSONObject json, Messenger client) throws RemoteException {
        if (target == null) {
            throw new IllegalStateException("接收通道尚未连接");
        }
        Message message = Message.obtain(null, MSG_COMMAND);
        Bundle data = new Bundle();
        data.putString(EXTRA_JSON, json.toString());
        message.setData(data);
        message.replyTo = client;
        target.send(message);
    }

    /** 宿主侧：一条事件，往连着的那一头送。 */
    static void sendEvent(Messenger client, JSONObject event) {
        if (client == null) {
            return;
        }
        Message message = Message.obtain(null, MSG_EVENT);
        Bundle data = new Bundle();
        data.putString(EXTRA_JSON, event.toString());
        message.setData(data);
        try {
            client.send(message);
        } catch (RemoteException gone) {
            Log.w(Entry.TAG, "receive bridge peer gone", gone);
        }
    }

    /**
     * 把一个套接字的出站口包成一条 {@link Messenger}。
     *
     * <p>发到它上面的每条消息都被取成 JSON、排进 {@link #WRITER} 写成一行。{@code MSG_HELLO}
     * 这类没有载荷的消息直接丢掉——线上只有 JSON，没有「什么也不说」这种消息的位置。
     *
     * <p>{@code name} 只进日志，用来分辨「这条是钩子侧的出站口还是宿主侧某条连接的出站口」。
     */
    static Messenger outbound(BufferedWriter target, String name) {
        return new Messenger(new Handler(Looper.getMainLooper(), message -> {
            Bundle data = message.getData();
            String json = data == null ? "" : data.getString(EXTRA_JSON, "");
            if (json.isEmpty()) {
                return true;
            }
            try {
                push(target, new JSONObject(json), name);
            } catch (org.json.JSONException malformed) {
                Log.w(Entry.TAG, "receive bridge outbound is not json", malformed);
            }
            return true;
        }));
    }

    /**
     * 排一行出去。
     *
     * <p>写失败不在这里上报：对端走了的话，读那条线程会读到 EOF 并走断开那条路，一处报
     * 一次就够了。这里只留一句日志，因为「写不出去」和「读不到」在同一条连接上几乎是同一
     * 件事的两个瞬间。
     */
    private static void push(BufferedWriter target, JSONObject json, String name) {
        String line = json.toString();
        WRITER.execute(() -> {
            try {
                target.write(line);
                target.write("\n");
                target.flush();
                // 只记「往钩子送」这一向：这一句是「宿主确实写出去了」的唯一证据，
                // 没有它，写成功和根本没调用在日志里长得一模一样。带上字数，好和对面
                // `bridge chunk` 的字节数对账。
                if ("host".equals(name)) {
                    Log.i(Entry.TAG, "bridge event out " + json.optString("ev") + " "
                            + json.optString("session") + " len=" + line.length());
                }
            } catch (Throwable gone) {
                Log.w(Entry.TAG, "receive bridge write failed on " + name, gone);
            }
        });
    }

    /** 连上、握手、报到，然后一直读到对面关掉。 */
    private static void open(Messenger client, Runnable onLost) {
        Socket opened = new Socket();
        BufferedWriter writer;
        try {
            opened.connect(new InetSocketAddress(InetAddress.getByName(LOOPBACK), PORT), CONNECT_TIMEOUT_MS);
            opened.setTcpNoDelay(true);
            writer = new BufferedWriter(new OutputStreamWriter(opened.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(new JSONObject().put("cmd", "hello").put("bridge", TOKEN).toString());
            writer.write("\n");
            writer.flush();
        } catch (Throwable failure) {
            // 最常见的不是错误：接收端还没起来（应用没跑过，或者刚被系统回收）。退避重试
            // 会把这件事接上，所以这句只写 info。
            Log.i(Entry.TAG, "receive bridge not up yet: " + failure);
            shut(opened);
            if (onLost != null) {
                MAIN.post(onLost);
            }
            return;
        }
        synchronized (BridgeMessenger.class) {
            socket = opened;
            out = writer;
        }
        // 报到：钩子侧只认这一条消息，所有「连上了要做什么」都在
        // OShareReceiveBridge.receive 里，那条路和 Binder 时代是同一条。
        Message hello = Message.obtain(null, MSG_HELLO);
        hello.replyTo = outbound(writer, "hook");
        try {
            client.send(hello);
        } catch (RemoteException local) {
            Log.w(Entry.TAG, "receive bridge hello failed", local);
        }
        read(opened, client, onLost);
    }

    /**
     * 一行一条事件，读到 EOF 为止。
     *
     * <p>切行是手写的，不用 {@link BufferedReader#readLine}。差别只在出问题时才显出来：
     * 数据只要没有以换行结尾，{@code readLine} 就会把已经读到的字节留在自己的字符缓冲里
     * 继续阻塞——从外面看是「socket 的 rx_queue 已经归零、线程却还停在 recvfrom」，
     * 也就是「字节到了」和「读到了」在这条路上分不开。按字节切之后，每一批的字节数和首尾
     * 字节都能打出来，「宿主到底有没有把那个换行写出去」变成一个可以直接看的事实。
     */
    private static void read(Socket opened, Messenger client, Runnable onLost) {
        try (InputStream in = opened.getInputStream()) {
            ByteArrayOutputStream pending = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                for (int i = 0; i < n; i++) {
                    if (chunk[i] != '\n') {
                        pending.write(chunk[i]);
                        continue;
                    }
                    byte[] whole = pending.toByteArray();
                    pending.reset();
                    String line = new String(whole, StandardCharsets.UTF_8);
                    Log.i(Entry.TAG, "bridge line " + line.length() + " "
                            + (line.length() > 200 ? line.substring(0, 200) : line));
                    JSONObject event;
                    try {
                        event = new JSONObject(line);
                    } catch (Exception malformed) {
                        Log.w(Entry.TAG, "receive bridge event is not json", malformed);
                        continue;
                    }
                    deliver(client, event);
                }
                // 这一行是本轮唯一能回答「换行到底有没有到」的地方：`pending` 是还没凑成
                // 一行的字节数，`head`/`tail` 是这一批的首尾。上一轮出现的形态是
                // 「rx_queue 归零、线程还在 recvfrom、连一行都没切出来」，只有这里能定性。
                Log.i(Entry.TAG, "bridge chunk n=" + n + " pending=" + pending.size()
                        + " head=" + edges(chunk, n, true) + " tail=" + edges(chunk, n, false));
            }
        } catch (Throwable failure) {
            Log.w(Entry.TAG, "receive bridge read failed", failure);
        } finally {
            synchronized (BridgeMessenger.class) {
                // 只清自己那一条：断开之后可能已经有一条新连接顶上来了。
                if (socket == opened) {
                    socket = null;
                    out = null;
                }
            }
            shut(opened);
            if (onLost != null) {
                MAIN.post(onLost);
            }
        }
    }

    private static void deliver(Messenger client, JSONObject event) {
        if (client == null) {
            return;
        }
        Message message = Message.obtain(null, MSG_EVENT);
        Bundle data = new Bundle();
        data.putString(EXTRA_JSON, event.toString());
        message.setData(data);
        try {
            client.send(message);
        } catch (RemoteException local) {
            Log.w(Entry.TAG, "receive bridge deliver failed", local);
        }
        // 投出去之后的两段：`delivered` 只证明 send 返回了，`drained` 才证明主线程把这条
        // 消息真取出来跑了。中间那一段如果断（主线程队列被同步屏障卡住时 `next()` 会跳过
        // 所有非 async 消息、队列看起来是空的），外面看到的现象和「根本没送到」一模一样。
        Log.i(Entry.TAG, "bridge delivered " + event.optString("ev") + " " + event.optString("session"));
        MAIN.post(() -> Log.i(Entry.TAG, "bridge drained " + event.optString("ev")));
    }

    /** 一批字节的头或尾，不可见字节写成 {@code \xNN}，好让换行和截断都看得见。 */
    private static String edges(byte[] chunk, int n, boolean head) {
        int from = head ? 0 : Math.max(0, n - 24);
        int to = head ? Math.min(n, 24) : n;
        StringBuilder text = new StringBuilder();
        for (int i = from; i < to; i++) {
            int value = chunk[i] & 0xff;
            if (value >= 0x20 && value < 0x7f) {
                text.append((char) value);
            } else {
                text.append(String.format("\\x%02x", value));
            }
        }
        return text.toString();
    }

    private static void shut(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Throwable ignored) {
            // 已经关了。
        }
    }
}
