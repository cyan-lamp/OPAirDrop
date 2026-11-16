package com.oshare.airdrop;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.DatagramPacket;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 找附近能收 AirDrop 的对端。
 *
 * <p># 为什么用 Java 的 socket，而报文交给 Rust
 *
 * <p>「在哪块网卡上找」是这一层唯一知道、而协议层无法知道的事：对端可能在 {@code awdltun0}
 * 上（AWDL，iPhone 唯一认的那条链路），也可能在 {@code wlan0} 上（同一局域网内的 Mac）。
 * 两条路问的不是同一个组播组——前者是 {@code ff02::fb}，后者是 {@code 224.0.0.251}——
 * 所以地址族跟着网卡走。
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

    /** mDNS 的端口。 */
    private static final int PORT = 5353;

    /** 局域网上的 mDNS 组：连着同一个路由器的 Mac 走这个。 */
    private static final String GROUP4 = "224.0.0.251";

    /** 链路本地 IPv6 的 mDNS 组：AWDL 上只有它。 */
    private static final String GROUP6 = "ff02::fb";

    /** {@code awdld} 建的那张网卡，iPhone 唯一在的那条链路。 */
    private static final String AWDL = "awdltun0";

    /** 同一条链路的无线电那一侧。回包几乎全被驱动吞掉，只当兜底，见 {@link Responder}。 */
    private static final String AWDL_RADIO = "wonder0";

    /** 服务类型后缀：实例名去掉它才是给人看的名字。 */
    private static final String SUFFIX = "._airdrop._tcp.local";

    /**
     * 多久重问一次。对端掉线时 ARP/AWDL 都不会通知，只能靠反复问。
     *
     * <p>1 秒（原 2 秒）：抓包确认 iPhone 是应答查询的（查询后 0.5–0.8 秒回），所以问得勤一点，
     * 打开 AirDrop 后发现自己快一倍。耗电代价很小——一条 40 字节的组播，每秒一次。
     */
    private static final long ASK_EVERY_MS = 1000;

    /**
     * 一台上一次见到后多久算消失——iOS 关 AirDrop 不发 goodbye，全靠这个超时发现。
     *
     * <p>抓包量过 iPhone 在线时的应答节奏：间隔 0.5–2.7 秒（它按自己节奏广播，和我们的查询频率
     * 无关）；关掉 AirDrop 后完全不应答。取 3.5 秒——按用户要求把消失压到最快，代价是只比实测
     * 的最大应答间隔 2.7 秒多 0.8 秒，偶尔丢一两条应答就可能把还开着的设备误删、闪一下。
     *
     * <p>历史上 30 秒太慢、8 秒仍出现过异常长的应答空白被误删；3.5 秒是明确接受这个误删风险的
     * 选择。若真机上出现设备闪烁，把这里调回 6 秒即可。
     */
    private static final long EXPIRE_MS = 3_500;

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
        /**
         * 发现它时用的那块网卡的索引。
         *
         * <p>只对链路本地 IPv6 有用，但那时非它不可：{@code fe80::} 不带「从哪块网卡出去」，
         * 内核不会去尝试，直接回 EINVAL。
         */
        int scope;
        /** TXT 里的型号，用来在列表里区分「这是台 iPhone 还是 Mac」。 */
        String model = "";
        /**
         * TXT 里的证书指纹，键名是 {@code h}，用来钉住握手时它出示的那份证书。
         *
         * <p>键名只有一字符是 Apple 定的，不是缩写失误：TXT 是一串两字符起步的短串，常读的
         * 键都短。这里曾经按 {@code phash} 读——一个自造的名字——对谁都不命中，于是每条连接
         * 都落在下面的「没有可比的值」分支上，校验等于是哑的。
         *
         * <p>空串表示没收到这一项——TXT 分几条记录到达，可能只到了型号；也可能是对端没发。
         * 这两种情况在这一侧分不开，所以处理成同一件事：**没有可比的值就不比**，照常连
         * （见 {@code Identity.clientContext}），但发送日志里会记一行。
         */
        String fingerprint = "";
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
    /** 上一次记日志时那一串「谁在、在哪」。 */
    private volatile String lastSeen;
    private Thread thread;
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;
    /** 组播是从哪块网卡进来的，同时也是连对端时该从哪块出去。0 表示系统默认。 */
    private volatile int nicIndex;
    /** 这一次浏览问的是哪个组。地址族跟着网卡走，见 {@link #open()}。 */
    private volatile InetAddress group;

    /**
     * 本机所有网卡上的地址。
     *
     * <p>自己的接收端在宣告时，组播会绕回来把自己也发现一遍——「本设备：青」和列表里那台
     * 一模一样，点下去就是自己发给自己。这不是理论上的可能：只要接收端开着，它就一定发生。
     */
    private final Set<String> own = new HashSet<>();

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
            int asked = 0;
            int heard = 0;
            // 先把「此刻还没有对端」记一笔。下面只在收到包时才 announce，缺了这一句，
            // 一台身边什么都没有的手机，在日志里和「根本没开始问」长得一模一样。
            publish(null);
            while (running) {
                long now = System.currentTimeMillis();
                if (now >= nextAsk) {
                    send(query);
                    asked++;
                    nextAsk = now + ASK_EVERY_MS;
                    // 问了半分钟仍然一包未收。这和「答了但记录凑不齐」是两回事：后者会走
                    // announce 的 partial records 分支，前者连一个字节都没回来，通常意味着
                    // 组播没出得去这块网卡，或者对端不在这条链路上。
                    if (heard == 0 && asked % 15 == 0) {
                        Log.w(TAG, "browse: " + asked + " queries sent, nothing came back");
                    }
                }
                if (receive()) {
                    heard++;
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
        NetworkInterface nic = link();
        // awdltun0 只带链路本地 IPv6，连 A 记录都不会有；其余网卡按有没有 IPv4 判。
        boolean v6 = nic != null && (!hasIPv4(nic) || AWDL.equals(nic.getName()));
        InetAddress target = InetAddress.getByName(v6 ? GROUP6 : GROUP4);
        if (v6 && nic != null) {
            // ff02::fb 是链路本地组播：不带 scope，内核不知道从哪块网卡发出去。
            target = Inet6Address.getByAddress(null, target.getAddress(), nic.getIndex());
        }
        group = target;

        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        // 250ms 而不是 1s：一次响应常被拆成 PTR/SRV/TXT 几个包，收包越勤，一台设备凑齐
        // connectable（地址+端口）越快，开 AirDrop 后显示得越及时。expire 也挂在收包超时上，
        // 这个间隔同时决定关掉后多久被清掉的上限——250ms 让 6s 阈值真正精确到 6s。
        socket.setSoTimeout(250);
        // 绑 IPv4 通配的 socket 收不到 ff02::fb，绑 IPv6 通配的又发不出 224.0.0.251：
        // 地址族要跟着上面选的组走，而不是跟着习惯走。
        socket.bind(new InetSocketAddress(
                InetAddress.getByName(v6 ? "::" : "0.0.0.0"), PORT));
        if (nic != null) {
            socket.setNetworkInterface(nic);
            nicIndex = nic.getIndex();
        }
        try {
            socket.setTimeToLive(255);
        } catch (Exception ignored) {
            // IPv6 的跳数是另一个字段，下面那句才是这条链路上起作用的那次设置。
        }
        // mDNS 要求跳数 255，iPhone 收到低于这个值的包直接丢——不报错，看起来就是没人应答。
        boolean hops = MulticastHop.raise(socket, MulticastHop.MDNS);
        collectOwnAddresses();
        socket.joinGroup(new InetSocketAddress(target, PORT), nic);

        // 没有这把锁，屏幕一黑，内核就把组播包丢了：系统的省电策略认为没人要。
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            lock = wifi.createMulticastLock("opairdrop-browse");
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        trace("joined " + (v6 ? GROUP6 : GROUP4) + ":" + PORT + " on "
                + (nic == null ? "the default route" : nic.getName())
                + (lock == null ? ", no multicast lock" : ", multicast lock held")
                + (hops ? ", hop limit 255" : ", hop limit NOT raised"));
    }

    /**
     * 在哪块网卡上问。
     *
     * <p>{@code awdltun0} 第一，因为 iPhone 只在那条链路上，而它是这一页唯一的对手盘。
     * {@code wonder0} 是同一条链路的无线电侧，驱动几乎不把回包交上来（35 秒里收到 9295 帧，
     * 它的收包计数只动了 8），所以只当兜底。两块都没有才退回 Wi-Fi：那时能找到的是连着同一个
     * 路由器的 Mac，不是手机。
     */
    private NetworkInterface link() {
        try {
            for (String name : new String[]{AWDL, AWDL_RADIO}) {
                NetworkInterface nic = NetworkInterface.getByName(name);
                if (nic != null && nic.isUp()) {
                    return nic;
                }
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

    /** 记下本机地址，用来把自己从对端列表里认出来。 */
    private void collectOwnAddresses() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    own.add(bare(address.getHostAddress()));
                }
            }
        } catch (Exception ignored) {
            // 拿不到就只是挡不住自己，不影响找人。
        }
    }

    /** 去掉 {@code %wlan0} 这样的 scope 后缀再比：同一条链路本地地址在两块网卡上写法不同。 */
    private static String bare(String address) {
        int percent = address.indexOf('%');
        String bare = percent < 0 ? address : address.substring(0, percent);
        return bare.toLowerCase(java.util.Locale.ROOT);
    }

    /** 这块网卡上有没有 IPv4。只有 IPv6 链路本地时，组播组必须换成 ff02::fb。 */
    private static boolean hasIPv4(NetworkInterface nic) {
        for (InetAddress address : Collections.list(nic.getInetAddresses())) {
            if (address instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    private void send(byte[] query) {
        try {
            DatagramPacket packet = new DatagramPacket(query, query.length, group, PORT);
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
     * <p>四种行：{@code srv 实例 主机 端口}、{@code addr 主机 地址}、
     * {@code txt 实例 键 值}、{@code gone 实例}（TTL=0 的 goodbye）。地址记录挂在主机名上，不是实例名上——所以一台还没收到 SRV 的
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
                // gone 只有两段（实例名里不含 tab），其余至少三段。
                if (field.length < (field.length > 0 && "gone".equals(field[0]) ? 2 : 3)) {
                    continue;
                }
                // srv 与 txt 各有四段，addr 有三段；缺一段就跳过这一行，让后面的行照常读。
                if ((field[0].equals("srv") || field[0].equals("txt")) && field.length < 4) {
                    continue;
                }
                switch (field[0]) {
                    case "gone" -> {
                        // TTL=0：对方主动说它走了（iOS 关 AirDrop 就会发）。立即移除，不必等
                        // 过期计时——这正是用户看到「关了还挂着好几秒」的那段空白。
                        String instance = field[1].endsWith(SUFFIX)
                                ? field[1].substring(0, field[1].length() - SUFFIX.length())
                                : field[1];
                        if (peers.remove(instance) != null) {
                            changed = true;
                        }
                        // 有些实现把 goodbye 挂在主机名上（A/AAAA 的 TTL=0），不是实例名。
                        // 那种记录带 service suffix 时上面已覆盖；这里再按主机名兜一次。
                        if (peers.values().removeIf(peer -> field[1].equals(peer.host))) {
                            changed = true;
                        }
                    }
                    case "srv" -> {
                        Peer peer = peer(field[1]);
                        peer.host = field[2];
                        peer.port = parseInt(field[3]);
                        peer.seenAt = System.currentTimeMillis();
                        changed = true;
                    }
                    case "addr" -> {
                        if (own.contains(bare(field[2]))) {
                            // 自己的宣告绕回来了。地址不填，它就进不了可连接的列表。
                            continue;
                        }
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
                        // 两个键各有去处：型号决定列表里画什么，指纹决定这条连接能连到谁。
                        // 其余键（flags 等）这一侧不读，`browse.rs` 也只报了这三个。
                        // 指纹那一项的键名是 `h`——对端就是这么发的，见 `Responder.java`。
                        if ("model".equals(field[2])) {
                            peer(field[1]).model = field[3];
                            changed = true;
                        } else if ("h".equals(field[2])) {
                            peer(field[1]).fingerprint = field[3].trim();
                            changed = true;
                        }
                    }
                    default -> { }
                }
            }
        }
        return changed;
    }

    /**
     * 把记下来的地址变成能直接交给 socket 的 {@link InetAddress}。
     *
     * <p>链路本地 IPv6 必须带 scope：内核要知道从哪块网卡发出去。不带的话 {@code connect}
     * 既不超时也不拒绝，而是立刻回 {@code EINVAL}——看起来像对端不可达，其实请求本身就不合法。
     * 发现它的时候我们手里正好有那块网卡，所以记在 {@link Peer#scope} 上带过来。
     */
    static InetAddress address(Peer peer) throws IOException {
        InetAddress parsed = InetAddress.getByName(peer.address);
        if (!(parsed instanceof Inet6Address) || !parsed.isLinkLocalAddress()) {
            return parsed;
        }
        Inet6Address v6 = (Inet6Address) parsed;
        int scope = v6.getScopeId();
        if (scope == 0) {
            scope = peer.scope;
        }
        if (scope == 0) {
            // 一条线索都没有时交还给系统：它至少还能在单网卡的情况下猜对。
            return v6;
        }
        return Inet6Address.getByAddress(null, v6.getAddress(), scope);
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
            peer.scope = nicIndex;
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
        announce(copy, problem);
        main.post(() -> watcher.onPeers(copy, problem));
    }

    /**
     * 发现到的东西变了才写一行。
     *
     * <p>写这一行的理由：「找不到设备」有三种完全不同的原因——组播出不去、对端不答、
     * 答了但记录凑不齐——而这三者在界面上长得一模一样（都是「正在搜索附近设备……」）。
     * 唯一能把它们分开的就是这一条：跳过了这一行，就只剩猜。
     *
     * <p>只在变化时写，是因为浏览每两秒问一次，每问一次都写会把周围的日志冲掉。
     */
    private void announce(List<Peer> copy, String problem) {
        StringBuilder line = new StringBuilder();
        int ready = 0;
        for (Peer peer : copy) {
            if (!peer.connectable()) {
                continue;
            }
            ready++;
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(peer.name).append('@').append(peer.address).append(':').append(peer.port);
        }
        String now = ready + "|" + line + "|" + problem;
        if (now.equals(lastSeen)) {
            return;
        }
        lastSeen = now;
        if (problem != null) {
            Log.w(TAG, "browse: stopped with " + ready + " connectable: " + problem);
        } else if (ready == 0) {
            trace("nothing connectable yet (" + copy.size() + " instance(s) with partial records)");
        } else {
            trace(ready + " connectable — " + line);
        }
    }

    /** 浏览这一层的状态很少变，变的时候留一行；多了反而看不见。 */
    private static void trace(String line) {
        Log.i(TAG, "browse: " + line);
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
