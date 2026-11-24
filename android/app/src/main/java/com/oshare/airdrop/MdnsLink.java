package com.oshare.airdrop;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 浏览用的网卡选择与 socket 生命周期（评审拆分路线的一、二刀：从 {@code Peers} 里切出来的）。
 *
 * <p>{@link MdnsLink} 本体仍是纯函数层：每问一次答一次。有状态的 socket/组播锁/绑定账目
 * 收在 {@link Channel} 里——它自己开、自己关。至于「这块 socket 听得见吗」「该不该重开」，
 * 那是 {@code Peers} 的判定账目（foreign/deafWait/nextRebind 那一组）的活，两类状态分开，
 * 重开才不会把「对端走了」误判成「我聋了」。
 */
final class MdnsLink {

    /** iPhone 只认的 AWDL 隧道。 */
    static final String AWDL_TUN = "awdltun0";

    /** 同一条链路的无线电那一侧。回包几乎全被驱动吞掉，只当兜底。 */
    static final String AWDL_RADIO = "wonder0";

    private MdnsLink() {
    }

    /**
     * 在哪块网卡上问。
     *
     * <p>{@code awdltun0} 第一，因为 iPhone 只在那条链路上，而它是这一页唯一的对手盘。
     * {@code wonder0} 是同一条链路的无线电侧，驱动几乎不把回包交上来（35 秒里收到 9295 帧，
     * 它的收包计数只动了 8），所以只当兜底。两块都没有才退回 Wi-Fi：那时能找到的是连着同一个
     * 路由器的 Mac，不是手机。
     */
    static NetworkInterface link() {
        return link(true);
    }

    /**
     * 同上，但可以拒绝兜底。
     *
     * <p>{@code fallback=false} 时只认 AWDL 那两块，没有就返回 {@code null}——把「这条路不在」
     * 当成一个正当答案交给调用方，而不是硬塞一块能开却不通的网卡。
     */
    static NetworkInterface link(boolean fallback) {
        try {
            for (String name : new String[]{AWDL_TUN, AWDL_RADIO}) {
                NetworkInterface nic = named(name);
                if (nic != null) {
                    return nic;
                }
            }
            if (!fallback) {
                return null;
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

    /** 按名字取一块正在用的网卡；不存在或者没起来都算没有。 */
    static NetworkInterface named(String name) {
        try {
            NetworkInterface nic = NetworkInterface.getByName(name);
            return nic != null && nic.isUp() ? nic : null;
        } catch (Exception gone) {
            return null;
        }
    }

    /** 这块网卡上有没有 IPv4。只有 IPv6 链路本地时，组播组必须换成 ff02::fb。 */
    static boolean hasIPv4(NetworkInterface nic) {
        for (InetAddress address : Collections.list(nic.getInetAddresses())) {
            if (address instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一块开着组播的 socket，连同它的组播锁和绑定账目。
     *
     * <p>它只管「链路在不在听」：挑网卡、建 socket、加组、拿锁、换网卡时搬走。它**不管**
     * 「听不听得见」「该不该重开」——那需要 foreign/deafWait 那一组判定账目，留在
     * {@code Peers} 里。
     */
    static final class Channel {

        /** mDNS 的端口，也是组播报文的出口端口。 */
        static final int PORT = 5353;

        private static final String GROUP4 = "224.0.0.251";

        private static final String GROUP6 = "ff02::fb";

        private MulticastSocket socket;
        private WifiManager.MulticastLock lock;
        /** 这一次浏览问的是哪个组。地址族跟着网卡走，见 {@link #open}。 */
        private volatile InetAddress group;

        /** 组播从哪块网卡进来，同时也是连对端时该从哪块出去。0 表示系统默认。 */
        private volatile int nicIndex;

        /**
         * 现在绑在哪块网卡上，名字；没有走系统默认路由时为 {@code null}。
         *
         * <p>换网卡时的读法：本来就在 {@code awdltun0} 上，它一消失就只能**等**——换到旁边
         * 任何一块都是换到一条 iPhone 不在的路上；本来在 Wi-Fi 或 {@code wonder0} 上，
         * {@code awdltun0} 一出现就该**搬**过去。
         */
        private volatile String bound;

        /**
         * 挑一块网卡，加入组播组。
         *
         * <p>{@code wonder0} 优先：它是 iPhone 唯一认得的那条链路，而 {@code wlan0} 上的多播
         * 在一台连着 Wi-Fi 6 路由的手机上会被省电策略滤掉大半。两块都没有时用系统默认——比
         * 直接失败好，因为一块都没有通常意味着 Wi-Fi 关着，那条路本来也没戏。
         *
         * @return 一行 join 摘要（组、网卡、锁、跳数），由调用方决定怎么记。
         */
        String open(Context context) throws Exception {
            NetworkInterface nic = MdnsLink.link();
            // awdltun0 只带链路本地 IPv6，连 A 记录都不会有；其余网卡按有没有 IPv4 判。
            boolean v6 = nic != null && (!hasIPv4(nic) || AWDL_TUN.equals(nic.getName()));
            InetAddress target = InetAddress.getByName(v6 ? GROUP6 : GROUP4);
            if (v6 && nic != null) {
                // ff02::fb 是链路本地组播：不带 scope，内核不知道从哪块网卡发出去。
                target = Inet6Address.getByAddress(null, target.getAddress(), nic.getIndex());
            }
            group = target;

            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            // 250ms 而不是 1s：一次响应常被拆成 PTR/SRV/TXT 几个包，收包越勤，一台设备凑齐
            // connectable（地址+端口）越快，开 AirDrop 后显示得越及时。这一档同时是过期检查
            // 的节奏——每轮循环都查一次过期，2.5 秒的阈值才真正精确到 2.5 秒。
            socket.setSoTimeout(250);
            // 绑 IPv4 通配的 socket 收不到 ff02::fb，绑 IPv6 通配的又发不出 224.0.0.251：
            // 地址族要跟着上面选的组走，而不是跟着习惯走。
            socket.bind(new InetSocketAddress(
                    InetAddress.getByName(v6 ? "::" : "0.0.0.0"), PORT));
            if (nic != null) {
                socket.setNetworkInterface(nic);
            }
            // 0 = 系统默认。写成 0 而不是留着上一次的值：网卡真的没了的时候，下一条有效网卡
            // 一定与它不同，下一次核网卡就会把我们搬过去。
            nicIndex = nic == null ? 0 : nic.getIndex();
            bound = nic == null ? null : nic.getName();
            try {
                socket.setTimeToLive(255);
            } catch (Exception ignored) {
                // IPv6 的跳数是另一个字段，下面那句才是这条链路上起作用的那次设置。
            }
            // mDNS 要求跳数 255，iPhone 收到低于这个值的包直接丢——不报错，看起来就是没人应答。
            boolean hops = MulticastHop.raise(socket, MulticastHop.MDNS);
            socket.joinGroup(new InetSocketAddress(target, PORT), nic);

            // 没有这把锁，屏幕一黑，内核就把组播包丢了：系统的省电策略认为没人要。
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("opairdrop-browse");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            return "joined " + (v6 ? GROUP6 : GROUP4) + ":" + PORT + " on "
                    + (nic == null ? "the default route" : nic.getName())
                    + (lock == null ? ", no multicast lock" : ", multicast lock held")
                    + (hops ? ", hop limit 255" : ", hop limit NOT raised");
        }

        /** 关掉 socket、放掉组播锁。重复调用安全。 */
        void close() {
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

        MulticastSocket socket() {
            return socket;
        }

        InetAddress group() {
            return group;
        }

        int nicIndex() {
            return nicIndex;
        }

        String bound() {
            return bound;
        }
    }
}
