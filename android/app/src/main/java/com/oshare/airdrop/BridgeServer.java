package com.oshare.airdrop;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 接传那一侧的接收界面，和这边的传输，之间的那条通道的宿主一侧。
 *
 * <p># 为什么是两个进程
 *
 * <p>接收本身在 {@link Listener} 里，跑在本应用的进程；接收界面是互传自己的那个弹窗
 * Activity 和流体云卡片，跑在 {@code com.coloros.oshare} 的进程里（见
 * {@link OShareReceiveBridge}）。这两件事没法在一个进程里做：前者要的是套接字和文件，
 * 后者要的是互传的资源和系统卡片框架。既然分了进程，中间就得有一条 IPC，这就是它。
 *
 * <p># 为什么是回环 TCP 而不是 Binder
 *
 * <p>原本它就是那条通道的宿主：一个 {@code exported} 的 {@code Service}，互传那边
 * {@code bindService} 过来。这条路在这台 ROM 上不通，原因与实测过程写在
 * {@link BridgeMessenger} 的类注释里。一句话：互传看不见我们（它的 {@code <queries>} 里
 * 没有这个包），而 {@code <queries>} 只能写在调用方清单里。
 *
 * <p>TCP 拿到的是「一对一端口」，而原来的 Binder 拿到的是「一对一 Binder」。这里把前者
 * 重新包成后者：每条连接有自己的出站 {@link Messenger}，收到的每一行还原成一条
 * {@link Message} 投进 {@link #handle}——和 Binder 时代进的是同一个方法，走的是同一张
 * 指令表。所以 {@link ReceiveHost} 那台状态机一个字节都没改。
 *
 * <p># 它不解析业务
 *
 * <p>指令的名字和字段在这里只是路由，做什么全在 {@link ReceiveHost}：那个类才是这次接收
 * 的状态所在，而一个状态放在两个地方就是两个真相。
 */
final class BridgeServer {

    private static final String TAG = "OPAirDrop";

    /** 同一台设备上不需要第二条。加了锁的那个字段就是它。 */
    private static BridgeServer running;

    /**
     * 指令在哪个线程上执行。
     *
     * <p>主线程，因为这里没有一件东西会等：{@code accept} 是给一个闩松开，{@code snapshot}
     * 是把已经算好的事件重念一遍。会等的那条线程在 {@link Listener} 里，它等的是这次闩，
     * 不是这条通道。
     */
    private final Handler handler = new Handler(Looper.getMainLooper(), this::handle);

    /** 每条连接收到的每一行，最后都从这里进 {@link #handle}。 */
    private final Messenger inbound = new Messenger(handler);

    private BridgeServer() {}

    /**
     * 开始听。进程起来时调一次，重复调无副作用。
     *
     * <p>绑在 {@code App.onCreate} 而不是某个页面里：这条通道要在没人看屏幕时也成立，而
     * 「打开过一次应用之后才有通道」是一个只在重启后才暴露的差别。
     */
    static void start(Context context) {
        synchronized (BridgeServer.class) {
            if (running != null) {
                return;
            }
            BridgeServer started = new BridgeServer();
            if (started.listen()) {
                running = started;
            }
        }
    }

    private boolean listen() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            // 只听回环：这条通道是给同一台设备上的另一个进程用的，绑在 0.0.0.0 上等于把它
            // 交给同一个 WiFi 里的所有设备。
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), BridgeMessenger.PORT), 4);
            Thread thread = new Thread(() -> accept(socket), "OShare-BridgeAccept");
            thread.setDaemon(true);
            thread.start();
            Log.i(TAG, "receive bridge listening on 127.0.0.1:" + BridgeMessenger.PORT);
            return true;
        } catch (Throwable failure) {
            // 端口被占了，或者这条域上不许 bind。不是致命错误：没有这条通道，接收仍然会退回
            // 到本应用自己的通知（见 ReceiveHost.Attempt.decide）。
            Log.w(TAG, "receive bridge could not listen", failure);
            return false;
        }
    }

    private void accept(ServerSocket socket) {
        while (true) {
            Socket peer;
            try {
                peer = socket.accept();
            } catch (Throwable ended) {
                Log.w(TAG, "receive bridge listener stopped", ended);
                return;
            }
            Thread thread = new Thread(() -> serve(peer), "OShare-BridgePeer");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * 一条连接：先握手，然后一行一条指令，直到对面走掉。
     *
     * <p>握手是这条通道上唯一的门。对不上暗号就直接关——不回一句话，因为回话本身也是在
     * 告诉对方「这里有个东西在听」。
     */
    private void serve(Socket peer) {
        Messenger reply = null;
        try {
            peer.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8));
            String opening = in.readLine();
            if (opening == null) {
                return;
            }
            JSONObject hello = new JSONObject(opening);
            if (!BridgeMessenger.TOKEN.equals(hello.optString("bridge"))) {
                Log.w(TAG, "receive bridge rejected " + peer.getRemoteSocketAddress());
                return;
            }
            reply = BridgeMessenger.outbound(out, "host");
            // 报到这一条也走 handle：attach 与日志都在那儿，这里不另开一份。
            deliver(hello, reply);
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject command;
                try {
                    command = new JSONObject(line);
                } catch (JSONException malformed) {
                    Log.w(TAG, "receive bridge command is not json", malformed);
                    continue;
                }
                deliver(command, reply);
            }
        } catch (Throwable gone) {
            Log.w(TAG, "receive bridge peer ended", gone);
        } finally {
            if (reply != null) {
                // 通道没了，就没有谁能画了。留在 ReceiveHost 里的接收还在走，它们会退回到
                // 自己的通知上——那正是「卡片不可用时的退路」这句话的意思。
                ReceiveHost.detach(reply);
            }
            try {
                peer.close();
            } catch (Throwable ignored) {
                // 已经关了。
            }
        }
    }

    /** 把一行 JSON 变成一条指令，投进和 Binder 时代同一个入口。 */
    private void deliver(JSONObject command, Messenger reply) {
        Message message = Message.obtain(null, BridgeMessenger.MSG_COMMAND);
        Bundle data = new Bundle();
        data.putString(BridgeMessenger.EXTRA_JSON, command.toString());
        message.setData(data);
        message.replyTo = reply;
        try {
            inbound.send(message);
        } catch (RemoteException impossible) {
            // 本进程内的 Messenger 不会真的抛，声明上有而已。
            Log.w(TAG, "receive bridge enqueue failed", impossible);
        }
    }

    private boolean handle(Message message) {
        if (message.what != BridgeMessenger.MSG_COMMAND) {
            return false;
        }
        JSONObject command;
        try {
            String json = message.getData() == null
                    ? "" : message.getData().getString(BridgeMessenger.EXTRA_JSON, "");
            command = new JSONObject(json);
        } catch (JSONException malformed) {
            Log.w(TAG, "receive bridge command is not json", malformed);
            return true;
        }
        String name = command.optString("cmd");
        Messenger reply = message.replyTo;

        if ("hello".equals(name)) {
            // 暗号在上面握手时已经对过了，这里只登记「事件往哪儿送」。这一条也是
            // ReceiveHost 从「问自己的通知」切到「问卡片」的那一刻。
            ReceiveHost.attach(reply);
            Log.i(TAG, "receive bridge connected");
            return true;
        }

        String session = command.optString("session");
        boolean ok = true;
        String error = "";
        switch (name) {
            case "receive_ui_ready":
                // 界面起来了。它紧接着会自己要件东西，这里不必再送一遍。
                break;
            case "receive_snapshot":
                ReceiveHost.snapshot();
                break;
            case "accept":
                ok = ReceiveHost.answer(session, true);
                if (!ok) {
                    error = "这项邀请已经不在了";
                }
                break;
            case "deny":
                ok = ReceiveHost.answer(session, false);
                if (!ok) {
                    error = "这项邀请已经不在了";
                }
                break;
            case "cancel_incoming":
                ok = ReceiveHost.cancel(session);
                if (!ok) {
                    error = "这项接收已经结束了";
                }
                break;
            case "ping":
                break;
            case "retry_publish":
                // 收完就落盘，没有「存不下去再试一次」这一步：这条指令在协议里有位置，在这里
                // 没有对应的动作。回一句说得清的拒绝，而不是「未知」——那不是我们不认识它，
                // 是它对这个接收端无事可做。
                ok = false;
                error = "这个接收端不重试发布，收到就已经在目标目录里了";
                break;
            default:
                ok = false;
                error = "未知指令：" + name;
                break;
        }
        answer(reply, command, ok, error);
        return true;
    }

    /** 每条指令都有回执，成功失败都说。对面靠它把「正在提交」那个状态收回去。 */
    private void answer(Messenger reply, JSONObject command, boolean ok, String error) {
        if (reply == null) {
            return;
        }
        try {
            BridgeMessenger.sendEvent(reply, new JSONObject()
                    .put("ev", "command_result")
                    .put("cmd", command.optString("cmd"))
                    .put("requestId", command.optString("requestId"))
                    .put("id", command.optLong("id"))
                    .put("session", command.optString("session"))
                    .put("ok", ok)
                    .put("error", error)
                    .put("message", error));
        } catch (JSONException impossible) {
            Log.w(TAG, "receive bridge result unavailable", impossible);
        }
    }
}
