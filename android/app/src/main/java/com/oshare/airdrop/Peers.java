package com.oshare.airdrop;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 找附近能收 AirDrop 的对端。
 *
 * <p># 为什么用 Java 的 socket，而报文交给 Rust
 *
 * <p>「在哪块网卡上找」是这一层唯一知道、而协议层无法知道的事：对端可能在 {@code wonder0}
 * 上（AWDL，iPhone 唯一认的那条链路），也可能在 {@code wlan0} 上（同一局域网内的 Mac）。
 * 组播组的加入、网卡的挑选、组播锁，全是 Android 的活。反过来，DNS 报文的名字压缩和各种
 * 越界，{@code crates/mdns} 已经带着测试做过一遍，所以这里只把字节交给
 * {@link Native#mdnsRecords(byte[])} 去读。
 *
 * <p># 为什么不返回「设备」，而是返回「记录」
 *
 * <p>一台设备不在一个报文里。PTR 说有这么个实例，SRV 说它监听哪个端口、主机名是什么，
 * A/AAAA 才给出地址，TXT 里才有型号——这四样常常分两三个报文到，顺序也不定。谁要是按
 * 「一个报文一台设备」去拼，就会漏掉大半；按「凑齐了才算」去拼，就会永远凑不齐。所以
 * Rust 只报它看见的记录，这里的 {@link #absorb} 负责把它们按实例名归拢。
 *
 * <p># 生命周期
 *
 * <p>一次浏览一个线程，{@link #stop} 之后线程在 1 秒内退出（接收超时就是这么久）。页面关掉
 * 就停：没人看着的组播订阅是耗电，也是「为什么这个应用在后台还在发包」的答案。
 */
final class Peers {

    private static final String TAG = "OPAirDrop";

    /** 组播地址与端口，mDNS 规定的两个数。 */
    private static final String GROUP = "224.0.0.251";
    private static final int PORT = 5353;

    /** 服务类型后缀：实例名去掉它才是给人看的名字。 */
    private static final String SUFFIX = "._airdrop._tcp.local";

    /** 多久重问一次。对端掉线时 ARP/AWDL 都不会通知，只能靠反复问。 */
    private static final long ASK_EVERY_MS = 2000;

    /** 一台上一次见到后多久算消失。mDNS 的 PTR 记录本身是 120 秒。 */
    private static final long EXPIRE_MS = 30_000;

    /** 一台对端。字段可变，因为它是被几条记录先后填满的。 */
    static final class Peer {
        /** 实例名，去掉 {@link #SUFFIX}，也就是对端自己起的名字。 */
        final String name;
        /** SRV 指向的主机名，同时用作 {@code Host} 头。 */
        String host = "";
        /** SRV 的端口。0 表示还没收到 SRV。 */
        int port;
        /** 最合适的那个地址：优先链路本地 IPv6（AWDL 只跑这个）。 */
        String address = "";
        /** TXT 里的型号，用来在列表里区分「这是台 iPhone 还是 Mac」。 */
        String model = "";
        /** 最后一次见到它的时刻，{@link System#currentTimeMillis()}。 */
        long seenAt;

        Peer(String name) {
            this.name = name;
        }

        /** 能不能连：地址和端口都有了才算。 */
        boolean connectable() {
            return port > 0 && !address.isEmpty();
        }
    }

    /** 有变化时回调。{@code problem} 非空表示这一轮出过事，列表可能因此是空的。 */
    interface Watcher {
        void onPeers(List<Peer> peers, String problem);
    }

    private final Context context;
    private final Watcher watcher;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 按实例名归拢，插入顺序就是它们被发现的顺序——列表不该每次刷新都重排。 */
    private final Map<String, Peer> peers = new LinkedHashMap<>();

    private volatile boolean running;
    private Thread thread;
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;

    Peers(Context context, Watcher watcher) {
        this.context = context.getApplicationContext();
        this.watcher = watcher;
    }

    /** 开始浏览。重复调用没有副作用。 */
    void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "opairdrop-browse");
        thread.setDaemon(true);
        thread.start();
    }

    /** 停止浏览并放掉组播锁。可以在任何线程调用。 */
    void stop() {
        running = false;
        MulticastSocket held = socket;
        if (held != null) {
            held.close();
        }
    }

    /** 当前快照，供页面的第一次绘制用。 */
    List<Peer> snapshot() {
        synchronized (peers) {
            return new ArrayList<>(peers.values());
        }
    }

    private void loop() {
        String problem = null;
        try {
            open();
            byte[] query = Native.mdnsQuery();
            long nextAsk = 0;
            while (running) {
                long now = System.currentTimeMillis();
                if (now >= nextAsk) {
                    send(query);
                    nextAsk = now + ASK_EVERY_MS;
                }
                if (receive()) {
                    publish(null);
                }
            }
        } catch (Throwable failure) {
            // 找不到对端不是异常情况：手机上没开 Wi-Fi、没有 wonder0、或者对端就是不在，
            // 都会走到这里一次。页面要能说出是哪一种，所以把原因带出去。
            problem = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            Log.w(TAG, "browse stopped: " + problem, failure);
        } finally {
            close();
            publish(problem);
        }
    }

    /**
     * 挑一块网卡，加入组播组。
     *
     * <p>{@code wonder0} 优先：它是 iPhone 唯一认得的那条链路，而 {@code wlan0} 上的多播
     * 在一台连着 Wi-Fi 6 路由的手机上会被省电策略滤掉大半。两块都没有时用系统默认——比
     * 直接失败好，因为一块都没有通常意味着 Wi-Fi 关着，那条路本来也没戏。
     */
    private void open() throws Exception {
        NetworkInterface nic = wonder();
        socket = new MulticastSocket(PORT);
        socket.setReuseAddress(true);
        socket.setTimeToLive(255);
        socket.setSoTimeout(1000);
        if (nic != null) {
            socket.setNetworkInterface(nic);
        }
        InetAddress group = InetAddress.getByName(GROUP);
        socket.joinGroup(new InetSocketAddress(group, PORT), nic);

        // 没有这把锁，屏幕一黑，内核就把组播包丢了：系统的省电策略认为没人要。
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            lock = wifi.createMulticastLock("opairdrop-browse");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
    }

    /** 名字叫 wonder0 的那块网卡，没有就退回正在用的 Wi-Fi，再没有就用默认。 */
    private NetworkInterface wonder() {
        try {
            NetworkInterface wonder = NetworkInterface.getByName("wonder0");
            if (wonder != null && wonder.isUp()) {
                return wonder;
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

    private void send(byte[] query) {
        try {
            DatagramPacket packet = new DatagramPacket(
                    query, query.length, InetAddress.getByName(GROUP), PORT);
            socket.send(packet);
        } catch (Exception ignored) {
            // 一次发不出去就等下一轮：组播本来就不保证送达，把一次失败当失败会误报。
        }
    }

    /** 收一个报文，并在有变化时返回 true。 */
    private boolean receive() {
        byte[] buffer = new byte[1500];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
        } catch (java.net.SocketTimeoutException idle) {
            return expire() > 0;
        } catch (Exception closed) {
            return false;
        }
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, data.length);
        try {
            String lines = new String(Native.mdnsRecords(data), java.nio.charset.StandardCharsets.UTF_8);
            return absorb(lines);
        } catch (Exception notForUs) {
            // 5353 上还有别的服务在广播：打印机、投屏、别人的 AirPlay。不是错误。
            return false;
        }
    }

    /**
     * 把记录行并进表里，返回是否有变化。
     *
     * <p>三种行：{@code srv 实例 主机 端口}、{@code addr 主机 地址}、
     * {@code txt 实例 键 值}。地址记录挂在主机名上，不是实例名上——所以一台还没收到 SRV 的
     * 主机的地址先存着，等 SRV 到了自然就对上。
     */
    private boolean absorb(String lines) {
        boolean changed = false;
        synchronized (peers) {
            String[] rows = lines.split("\n");
            for (String row : rows) {
                if (row.isEmpty()) {
                    continue;
                }
                String[] field = row.split("\t", -1);
                if (field.length < 3) {
                    continue;
                }
                // srv 与 txt 各有四段，addr 有三段；缺一段就跳过这一行，让后面的行照常读。
                if ((field[0].equals("srv") || field[0].equals("txt")) && field.length < 4) {
                    continue;
                }
                switch (field[0]) {
                    case "srv" -> {
                        Peer peer = peer(field[1]);
                        peer.host = field[2];
                        peer.port = parseInt(field[3]);
                        peer.seenAt = System.currentTimeMillis();
                        changed = true;
                    }
                    case "addr" -> {
                        // 地址行属于主机名，回填给所有指向这台主机的实例。
                        for (Peer peer : peers.values()) {
                            if (peer.host.equals(field[1])) {
                                String address = field[2];
                                if (betterAddress(address, peer.address)) {
                                    peer.address = address;
                                    peer.seenAt = System.currentTimeMillis();
                                    changed = true;
                                }
                            }
                        }
                    }
                    case "txt" -> {
                        if (!"model".equals(field[2])) {
                            continue;
                        }
                        Peer peer = peer(field[1]);
                        peer.model = field[3];
                        changed = true;
                    }
                    default -> { }
                }
            }
        }
        return changed;
    }

    /**
     * 这台主机的新地址是否比手上的更该用。
     *
     * <p>链路本地 IPv6 优先，因为 AWDL 只有它；其次是任何 IPv6；最后才是 IPv4。地址越「具体」
     * 越好用：{@code fe80::} 上连着的那台 iPhone 是唯一能收文件的路径，而 {@code 192.168}
     * 上那台多半是另一个人。
     */
    private static boolean betterAddress(String candidate, String current) {
        if (current.isEmpty()) {
            return true;
        }
        return rank(candidate) > rank(current);
    }

    private static int rank(String address) {
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

    /** 取出（或建出）一台对端。实例名去掉服务后缀才是名字。 */
    private Peer peer(String instance) {
        String name = instance.endsWith(SUFFIX)
                ? instance.substring(0, instance.length() - SUFFIX.length())
                : instance;
        Peer peer = peers.get(name);
        if (peer == null) {
            peer = new Peer(name);
            peer.seenAt = System.currentTimeMillis();
            peers.put(name, peer);
        }
        return peer;
    }

    /** 丢掉太久没再出现的对端，返回丢掉的个数。 */
    private int expire() {
        long cutoff = System.currentTimeMillis() - EXPIRE_MS;
        int before;
        synchronized (peers) {
            before = peers.size();
            peers.values().removeIf(peer -> peer.seenAt < cutoff);
        }
        return before - peers.size();
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** 把当前列表送到主线程。合并成一次回调，避免一屏几十次刷新。 */
    private void publish(String problem) {
        List<Peer> copy = snapshot();
        main.post(() -> watcher.onPeers(copy, problem));
    }

    private void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 已经关了。
            }
            socket = null;
        }
        if (lock != null) {
            try {
                lock.release();
            } catch (Exception ignored) {
                // 没拿到或者已经放了。
            }
            lock = null;
        }
    }
}
