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
     * 手上有可连的对端时，多久重问一次。对端掉线时 ARP/AWDL 都不会通知，只能靠反复问。
     *
     * <p>1 秒（原 2 秒）：抓包确认 iPhone 是应答查询的（查询后 0.5–0.8 秒回），所以问得勤一点，
     * 打开 AirDrop 后发现自己快一倍。耗电代价很小——一条 40 字节的组播，每秒一次。
     */
    private static final long ASK_EVERY_MS = 1000;

    /**
     * 一台可连的都没有时，多久重问一次。
     *
     * <p>这一档专为「一开就看见」：手上空的，最坏情况是「它刚开、我刚问过」，白等一整秒。300
     * 毫秒把这段空白压到感觉不出来。代价是空列表期间每秒三条约 40 字节的组播——只有在面板开着
     * 且什么都没找到时才是这个节奏，一旦有人立刻退回一秒。
     */
    private static final long ASK_EAGER_MS = 300;

    /**
     * 一台上一包之后多久算消失——**凑不齐地址端口时的兜底值**，不是唯一判据。
     *
     * <p># 「快速消失靠 goodbye」这个说法被真机推翻了
     *
     * <p>这里原来写着「iOS 关掉 AirDrop 时会发 TTL=0 的记录」，于是这一档只当兜底，
     * 6 秒也心安理得。2026-11-18 21:10 的真机把前提否掉了：iPhone 关掉 AirDrop 之后
     * **一包都没再发**，{@link #absorb} 的 {@code gone} 分支一次都没走到，日志里只有
     * {@code after 6065 ms of silence (threshold 6000)}。也就是说对它而言，
     * 这个常数就是全部——六秒的延迟直接落在人眼里。能连的那一档因此有了自己的
     * {@link #CONNECTABLE_MS}。
     *
     * <p>goodbye 那条路仍然在，仍然比任何计时器都快；只是不能指望它一定会来。
     *
     * <p># 为什么整体从 2.5 秒放宽到 6 秒，以及后来为什么又能分开
     *
     * <p>2.5 秒是拍的，真机把它否掉了。08:00 那一轮抓到的是这个：
     * <pre>
     * dropped e9bed2a41703 after 2567 ms of silence (threshold 2500, rhythm 1320 ms)
     * 1 connectable — e9bed2a41703          ← 一秒后同一台又回来了
     * dropped e9bed2a41703 after 1379 ms of silence (threshold 1200, rhythm 528 ms)
     * </pre>
     * 设备一直在，是我们删了又加。原因是 mDNS 的重复应答抑制：同一份记录不会答得太勤，所以
     * 一台**活着的** iPhone 合法地安静过 2.6 秒。而 50 秒的采样里（{@code probe:} 那些行）
     * 健康时的安静最多 752 毫秒——两者相差三倍，说明 2.5 秒正好落在「健康」和「真走了」之间
     * 那条模糊带里，谁划在那都会闪。
     *
     * <p>6 秒是「比健康时长出一个数量级」的位置：健康时安静不到 1 秒，6 秒还没声音就真的
     * 不是抑制了。慢的这 3.5 秒只影响「对方一声不响地消失」这种本来就没有时间点的情况。
     *
     * <p>**2026-11-18 起这一档只留给「还不完整」的对端**：能连的那一档曾经有自己的、短得多的
     * {@link #CONNECTABLE_MS}（2.2 秒）。上面那两次闪烁都发生在没有可连对端、查询提到 300 毫秒
     * 一次的时候，这个条件现在被写进了判断里，而不是靠一个对所有人生效的大数来回避。
     *
     * <p>**2026-11-19 起两档曾短暂同值（都是 6 秒），当天 17:16 之后又分开了**：能连那一档继续
     * 往上走到 20 秒（理由见 {@link #CONNECTABLE_MS} 最后一段——对面是成段休眠，六秒必闪），
     * 这一档留在 6 秒，只负责「还不完整」的对端。合并的方向从一开始就是反的：不是把这一档压短，
     * 是把能连那一档放长。短阈值要换来的「关掉后快两秒消失」根本不值得——对端不讲 goodbye
     * （见上），那两秒也只能靠猜。
     */
    private static final long EXPIRE_MS = 6_000;

    /**
     * 一台**能连**的对端上一包之后多久算消失。
     *
     * <p># 这个数被真机改了三回，最后一次和 {@link #EXPIRE_MS} 合流
     *
     * <p>第一版 2.2 秒，理由很硬：列表里只要有一台能连的，我们就以 1 秒一次问它，而它是
     * **一问一答**的（21:10 那轮 {@code q=0 a=14}，十四个应答全是我们问出来的），所以沉默
     * 满两个周期就是它真的不答了。
     *
     * <p>2026-11-19 这个理由被推翻两次。先是一台一直在说话的 iPhone 被反复删掉（间隔
     * 879–1638 ms，丢一包就静 2.2–3.3 秒，而死阈值是 2.2）；于是阈值改成由实测节奏算
     * （见 {@link #timeout}）。改完 15:57 和 16:16 又各删了一次，这两行是关键：
     * <pre>
     * dropped 3fa87792be9a@… after 2727 ms of silence (threshold 2608, gaps 29,  max 1054)
     * dropped 9ba49b54ddbb@… after 4134 ms of silence (threshold 4080, gaps 242, max 1640)
     * </pre>
     * 下面这台已经**稳定说了 242 个间隔**——好几分钟里一包没丢过——然后突然静了四秒多。
     *
     * <p># 那「只差 54 毫秒」是误读
     *
     * <p>2727 对 2608、4134 对 4080，看着都像「差一点点就救回去了」，于是很容易得出「把余量
     * 再加两百毫秒就好」。不对。{@link #expire} 每 250 毫秒才跑一次，所以删除时刻必然落在
     * {@code [阈值, 阈值+250]} 里——这两个数正好都在这个区间，它们只证明检查粒度是 250 毫秒，
     * 不证明设备是踩线走的。真相是：**它到底会静多久我们不知道，因为我们在第一个检查点就把它
     * 删了。**
     *
     * <p># 于是这个判据的毛病不在数值，在它想回答的问题
     *
     * <p>「它静了 N 秒」在我们这边分不清两件事：它走了，和这条链路抖了一下（AWDL 的时隙调度
     * 在两端不同步时会周期性丢包，四秒足够连丢三四个包）。而 242 个间隔里它一次都没丢过，说明
     * 后者随时可能发生。判据必须偏向前者，也就是**容得下抖动**，而不是贴着理论周期。
     *
     * <p>所以这一档的下限抬到 6 秒，和 {@link #EXPIRE_MS} 同值。两个数同值不是巧合，是同一个
     * 结论抵达了两次：**在我们能拿到的信号里，「沉默」的最小可信单位就是六秒**。代价是对方
     * 关掉 AirDrop 之后要等六秒才消失——比 2.2 秒慢，但那一次等待换来的是「活着的设备不再每秒
     * 消失一次」，这个交换每次都是划算的。
     *
     * <p>真正的解法不在这里，在 {@link #prompt}：把「它静了」这件事本身变少，而不是在事后
     * 猜它静得够不够久。
     *
     * <p># 2026-11-19 17:16：六秒也不够，因为对面是**成段休眠**，不是丢包
     *
     * <p>那一轮旁边一直有一台 iPad 在说话（{@code foreign} 每半秒涨），所以链路是活的、
     * {@link #LINK_QUIET_MS} 那道门根本没关上。iPhone 的轨迹是这样的：
     * <pre>
     * 17:16:09.642 ready   ac064e658d14 after 1394 ms of browsing
     * 17:16:12.521 prompted … after 1949 ms of silence      ← 单播叫了一次
     * 17:16:16.632 dropped … after 6060 ms (threshold 6000, rhythm 420 ms, gaps 2)
     * 17:16:30.683 ready   … after 2197 ms                  ← 十四秒后原样回来
     * 17:16:32.901 prompted … after 1828 ms                 ← 连续三次单播，全无应答
     * 17:16:36.298 prompted … after 2078 ms
     * 17:16:41.881 prompted … after 1760 ms
     * 17:16:46.240 dropped … after 6119 ms (threshold 6000, rhythm 1216 ms, gaps 2)
     * </pre>
     * 两次删除都是踩在第一个检查点上，说明它真实的安静比六秒更长；而三次单播一次都没回来，
     * 说明这段时间它不是「漏听了一个包」——它的应答器整段没在工作。同一时刻
     * {@code iw dev wonder0 station dump} 里它的站点条目 {@code connected time} 一路涨、
     * {@code signal −26 dBm}，**802.11 是通的，只有 AirDrop 那层在睡**。它答几包、睡六到八秒、
     * 再答几包，任何小于八秒的门槛都必然闪。
     *
     * <p>所以下限抬到 **20 秒**，也就是 {@link #LINK_DEAD_MS} 那条天花板。两个数合成一个不是
     * 省事：它们回答的是同一个问题——「沉默多久之后我们才有资格说它走了」。在我们能拿到的
     * 信号里，答案已经明确是「很久，而且我们分不清它是在睡还是走了」，那就只有一条路：**容得
     * 下，直到容不下为止**。代价照旧写在明处：对方真关掉 AirDrop，这个格子会多留十几秒。
     * 那比「活着的设备每六秒消失一次」划算，因为后者会让人以为功能坏了。
     */
    private static final long CONNECTABLE_MS = 20_000;

    /**
     * 能连的对端，在「丢两包要多久」之上再留多少毫秒。
     *
     * <p>两段最长间隔是丢一包的理论上限，余量留给取整、平滑和收到包的时间戳粒度。
     *
     * <p># 为什么从 500 抬到 800
     *
     * <p>2026-11-19 15:57 的真机，一台节奏稳定的 iPhone 被删掉了：
     * <pre>
     * browse: rhythm: 3fa87792be9a speaks every 1021 ms (10 gaps, 464–1054 ms, q=0 a=11)
     * browse: dropped 3fa87792be9a@… after 2727 ms of silence (threshold 2608, gaps 29, max 1054)
     * </pre>
     * 同一窗口 19 个间隔用了 22 秒，平均 1.16 秒——它一直在说话，最后安静了三秒多。500 的余量
     * 只够「两段都落在高端」，不够真实链路上多丢一包，所以抬到 800。
     *
     * <p>**注意别把这个数当成「只差一点点」的补丁**：{@link #expire} 的检查粒度是 250 毫秒，
     * 删除时刻永远落在阈值过后的第一个检查点上，所以「2727 对 2608」说明不了余量差 119
     * 毫秒。抬高的真正理由是「余量要容得下多丢一包」，不是「把那条线往右挪一点点就能救回来」。
     * 兜底的量级由 {@link #CONNECTABLE_MS} 负责，这里只调抖动那一段。
     */
    private static final long CONNECTABLE_SLACK_MS = 800;

    /**
     * 攒够几个间隔，才敢用它们去放宽阈值。
     *
     * <p>少于这个数时量到的可能只有两帧「正好一样快」的样本，也可能一帧都没有（新实例）。
     * 那种时候用 {@link #CONNECTABLE_MS} 兜底——它本身就是从几千帧真机数据里挑出来的位置。
     */
    private static final int CONNECTABLE_GAPS = 3;

    /**
     * 静默超过多久，就对这台对端单独问一句（单播）。
     *
     * <p>1.5 秒：它健康时约每秒说一次话，1.5 秒还没声音说明至少漏了一包，这时候叫它一声最
     * 划算；再短就变成每秒额外一次单播，白占空口——而 AWDL 的空口时间正是丢包的原因之一。
     * 见 {@link #prompt}。
     */
    private static final long PROMPT_AFTER_MS = 1_500;

    /**
     * 整条链路安静超过多久，就认为「我们现在什么都不知道」。
     *
     * <p>2026-11-19 16:50 抓到的现场：{@code prompted … after 1509 ms} → {@code dropped … after
     * 6079 ms (threshold 6000, rhythm 1049 ms, gaps 262, max 1745 ms)} → 紧接着
     * {@code pulse: 0 peer(s), foreign 270 (last 7844 ms ago)} → **4.5 秒后同一个实例名原样回来**
     * （{@code ready 38de3d7e83f4 … after 276735 ms of browsing}）。也就是说那段沉默不属于这台
     * 设备，属于链路：**7.8 秒里我们一个外部包都没收到**，连别的地址记录都没有。
     *
     * <p>这两种沉默必须分开，因为它们要求相反的处置：
     * <ul>
     *   <li>链路活着、只有它在安静 → 它是真走了，删掉（{@link #timeout}）；</li>
     *   <li>链路自己也安静了 → **我们聋了**，这时候「它沉默多久」量的是我们自己的听筒，
     *       拿它判设备走没走等于用「没听见」冒充「它没说话」。</li>
     * </ul>
     *
     * <p>4 秒。两个实测数夹出来的：
     * <ul>
     *   <li>健康链路的最大间隔 **3144 ms**（{@code rhythm 1281 ms, gaps 10, max 3144}）——
     *       {@link #LINK_QUIET_MS} 必须在它之上，否则健康时也会把自己判成聋子，6 秒那条路
     *       就永远轮不到；</li>
     *   <li>真停顿是 **7844 ms**、真离开是 **20000 ms 以上**。</li>
     * </ul>
     *
     * <p>它原来还必须落在 {@link #CONNECTABLE_MS} 之下（「4 秒对 6 秒，留了两秒」），因为那时
     * 「聋了」是唯一能救一台即将被 6 秒删掉的设备的理由。现在 {@link #CONNECTABLE_MS} 自己就是
     * 20 秒，比它长得多，这层关系没了：能连的对端两个分支拿到同一个阈值（{@link #timeout}
     * 兜出来也是 20 秒）。剩下真正靠它的只有「还不完整」的对端，它们仍按 {@link #EXPIRE_MS}
     * 的 6 秒走。
     *
     * <p>**这条规矩解决不了的情况，说清楚**：链路如果「静 3 秒、响半秒、再静 3 秒」地闪，
     * 每次响都把它重置回「活着」，而设备的沉默是一路涨上去的。要挡住那种情况得按「链路活着的
     * 时间」累计而不是看瞬时状态，但那会引入第二套时钟（`pulse` 里的 `quiet=` 和 `prompt()`
     * 用的都是墙上时间），把日志搅乱。等真抓到闪的场景再说。
     *
     * <p>**现在只有 {@link #expire} 用它**。浏览循环曾经也拿它决定「该不该重开 socket」，那样
     * 是错的：同一个误差，「先别删」的代价是零，「拆掉重开」的代价是把路上的应答全丢掉。重开
     * 那一侧已经分开成 {@link #DEAF_SILENCE_MS}（20 秒），理由和 17:20 的现场都在那里。
     */
    private static final long LINK_QUIET_MS = 4_000;

    /**
     * 链路一直不回来时，最多留它多久。
     *
     * <p>{@link #LINK_QUIET_MS} 那条规矩是「不知道就先别删」，但没有上限就成了「永远不删」：
     * 对端真的走了、链路跟着死掉，两者在计时器眼里和一次 8 秒停顿长得一模一样。所以给一个
     * 天花板——20 秒。取这个数的理由：真机上量到的停顿是 7.8 秒，取两倍多一点留出二次停顿的
     * 余量；而人对着一个空了 20 秒的格子，早就自己重开面板了，再早删也只是把同一件事做早。
     *
     * <p>**这不是「把阈值调大」**。正常路径仍然是 6 秒；只有在我们一个字都听不见、根本没法
     * 判断的时候，才退到这条线。
     *
     * <p>**20 秒会误删一种情况，说清楚**：2026-11-19 17:10 这一轮，同一台 iPhone 静了 **130 秒**
     * 才回来，而这 130 秒不是「我们聋了」——`/proc/net/dev` 上 `awdltun0` 的 RX 字节与包数
     * 20 秒里一个没动、TX 涨了 55 个包，说明对面真的一个字都没发。同一时刻
     * `iw dev wonder0 station dump` 里它的站点条目已经没了，回来时 `connected time` 从头计
     * （{@code 4 seconds}，而信号是 −26 dBm，不是距离问题）。把天花板抬到 130 秒以上能保证
     * 「不误删」，代价是留一个点下去连不上的格子两分多钟——比空着更糟。所以这条线是**取舍**，
     * 不是测量结果；真凶在 802.11 那条关联上，不在这张表里。
     */
    private static final long LINK_DEAD_MS = 20_000;

    /**
     * 量节奏时，短于这个的间隔不采样。
     *
     * <p>同一轮应答常被拆成 PTR/SRV/TXT 几个包，前后只差几毫秒；把它当「它的说话周期」记下来
     * 只是噪音。200 毫秒以下的一律看作同一次说话。
     */
    private static final long QUIET_MIN_MS = 200;

    /**
     * 节奏的平滑系数：新的间隔占多少。
     *
     * <p>0.3 大约是「记住最近三四帧」。取 1 会被一次丢包带到沟里，取 0.05 又跟不上真实变化。
     */
    private static final double RHYTHM_ALPHA = 0.3;

    /**
     * 量节奏时，长于这个的间隔不采样。
     *
     * <p>超过四秒的空白不是它的节奏，是它真的安静过（或者我们自己的查询丢了）。算进平均值只
     * 会让这个数失去意义。
     */
    private static final long RHYTHM_CEIL_MS = 4_000;

    /**
     * 攒够几个间隔就写一行统计。
     *
     * <p>这一行答的是一个靠猜猜不出来的问题：**对端到底隔多久说一次话**。08:05 那一轮的答案
     * 是「答，约每秒一次，从不在问」（{@code q=0 a=5} 每 5 秒），健康时最长安静 752 毫秒。
     * 阈值就是据此定的，见 {@link #EXPIRE_MS}。
     */
    private static final int RHYTHM_SAMPLES = 10;

    /**
     * 诊断态：先不删任何对端，每 {@link #DIAG_EVERY_MS} 打一行问答节奏。
     *
     * <p>**临时开关，调好阈值就关掉。** 开着是因为阈值必须用真机上的分布来定，而一删就换一个
     * 新 {@code Peer}、统计归零——被闪烁打断的样本永远攒不够十次，看到的全是半截数据。
     */
    private static final boolean DIAG = false;

    /**
     * 每 {@link #PULSE_MS} 打一行「还看得见谁、它安静了多久」。
     *
     * <p>和为 {@link #DIAG} 分开，因为目的相反：{@code DIAG} 要求「不许删」（否则攒不够样本），
     * 而这里要看的恰恰是**删掉之前和之后**各是什么样，所以行为一点都不能改。
     *
     * <p>**临时开关，量完分布就关。** 10 秒一行，开着的时候日志里每 10 秒多一行。
     *
     * <p>2026-11-19 17:30 关掉：`CONNECTABLE_MS` 定到 20 秒之后的最后一轮（round15，3 分 20 秒）
     * 里它显示 `1 peer(s), foreign 135→207, quiet 2–1195 ms`，一次删除都没有，要看的分布已经
     * 拿到了。留着只会每 10 秒多一行，把真出事时的上下文挤走。
     */
    private static final boolean PULSE = false;

    /** {@link #PULSE} 的节奏。10 秒：一屏能放下三五分钟的历史，又不至于把别的行挤走。 */
    private static final long PULSE_MS = 10_000;

    /** 诊断态下的「永不删」，只为把分布采样完整。 */
    private static final long DIAG_HOLD_MS = 30_000;

    /** 诊断态下多久打一行。5 秒 = 一屏日志里能看出节奏，又不至于淹掉别的行。 */
    private static final long DIAG_EVERY_MS = 5_000;

    /**
     * 把 5353 上收到的每一包**原样**打进日志：源地址、长度、解析行，外加整包的十六进制。
     *
     * <p>为什么不复用 {@link #DIAG}：那个开关会把 {@link #expire} 改成「永不删」，而这一轮要
     * 量的正是「多久删」——两个目的在同一次运行里互斥。
     *
     * <p>为什么要有十六进制：{@link Native#mdnsRecords} 只交出它认得的那几种记录（srv / addr /
     * txt / gone），而「iPhone 关掉 AirDrop 时到底发了什么」这个问题的答案很可能落在它**不认**
     * 的东西上——收起一个服务的 goodbye 是 TTL=0，可 TTL 从来不在这几行里；一条只带 flags 的
     * 空应答、一次 probe、一次冲突改名的 announce，解析行里全都看不见。抓包工具没有（这台机器
     * 上没有 tcpdump，也没有 root 之外的注入手段），那就让唯一握着 socket 的人把字节抄下来。
     *
     * <p>**临时开关，取完证就关。** 每包一行十六进制，开着的时候日志是不好看的。
     */
    private static final boolean WIRE = false;

    /** 一台对端。字段可变，因为它是被几条记录先后填满的。 */
    static final class Peer {
        /**
         * 实例名，去掉 {@link #SUFFIX}。
         *
         * <p>**这不是给人看的名字**。iPhone 广播的实例名是一串每分钟换一次的不透明 hash，把它
         * 画到界面上就是乱码——早先发送面板正是这么做的，于是先看见一串十六进制，一两秒后它才
         * 变成真名。真名要连上之后问 {@code /Discover} 才有，缓存见 {@link PeerNames}；在那之前
         * 用 {@link #kind()} 顶上。这个字段还剩两个用处：做 {@code peers} 表的键，以及进日志。
         */
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
        /**
         * 最后一次听到它的时刻，{@link System#currentTimeMillis()}。
         *
         * <p>语义是「关于它的任何一条记录到了」，不是「它的字段变了」。这两者以前混在一起：
         * 地址那条记录只在地址变得「更好」时才刷新这个时刻，于是同一台设备反复答同一个地址时，
         * 它明明在应答，却被算成沉默——{@link #expire} 于是把它删掉。
         */
        long seenAt;

        /**
         * 最后一次「从这个地址收到任意一个包」的时刻。
         *
         * <p>和 {@link #seenAt} 的差别是「答了」与「还在说话」：iPhone 在分享页里会**主动**不停地
         * 问 {@code _airdrop._tcp.local}，那些查询包里一条记录都没有，{@link #absorb} 看不见它们，
         * 于是以前它明明在链路上喊着，我们却按沉默算，两秒半就把它删了——这正是「关了 AirDrop
         * 之后反复闪」和「开着却偶尔消失」同一处病根的两种表现。
         */
        long heardFrom;

        /**
         * 实测出来的「它多久说一次话」，毫秒。0 表示还没测出来。
         *
         * <p>**平滑值，用来读，不用来判。** 判定要的是「最坏情况下它安静多久」，那是
         * {@link #gapMax}，不是平均值：一台每秒说一次、偶尔隔 1.6 秒的设备，平均值看着很健康，
         * 丢一包就安静三秒。所以 {@link #timeout} 拿 {@code gapMax} 算，这里只留给日志和
         * {@code probe:} 那一行——「对端到底是什么行为」的唯一实测记录。
         */
        long period;

        /** 量到过的间隔里最小、最大的那个，只为日志里那一行统计。0 表示还没量过。 */
        long gapMin;
        long gapMax;

        /** 已经量了几个间隔。 */
        int gaps;

        /**
         * 最后一次「它主动问了一句」的时刻；{@code questions} 是问到几包。
         *
         * <p>查询包和应答包是**两种不同的信号**，这里是它们的分界线：应答受 mDNS 的重复抑制
         * 约束（同一份记录不会答得太勤），所以一台活着的设备可以合法地安静两三秒；而「问」是
         * 它自己在发起，没有抑制，节奏稳得多——iPhone 在分享页里就一直问。
         *
         * <p>所以「它走了没有」这件事要分开判：问答两条流，哪条都不该拿另一条的节奏去套。
         */
        long askedAt;
        int questions;

        /** 收到几包应答（含记录的那些）。只为日志里那一行统计。 */
        int replies;

        /** 「它凑齐地址和端口」这一刻是否已经记过日志。见 {@link #absorb}。 */
        boolean reported;

        /**
         * 这一轮静默里是否已经单播叫过它一声。
         *
         * <p>只为让日志别刷屏：一次静默叫一次，它一说话就重置。见 {@link #prompt}。
         */
        boolean prompted;

        /**
         * 它**第一次**凑齐地址和端口的时刻，也就是它在列表里第一次可能出现的那一刻。
         *
         * <p>页面拿它当「还可以等一下名字」的起点：真名要连上去问 {@code /Discover} 才有，
         * 而先画一个占位名再改成真名，人看到的是名字在跳。0 表示还不知道能不能连。
         */
        long readyAt;

        Peer(String name) {
            this.name = name;
        }

        /** 能不能连：地址和端口都有了才算。 */
        boolean connectable() {
            return port > 0 && !address.isEmpty();
        }

        /**
         * 还不知道真名时，这台设备该叫什么。
         *
         * <p>取 TXT 里的型号而不是 {@link #name}：型号是「它是什么」，实例名是「它这一次叫
         * 什么」——后者对人多半没有意义，对 iPhone 则是一串每分钟换一次的 hash。
         *
         * <p>回落到「附近设备」而不是空着：一个没有名字的格子看着像坏了。
         */
        String kind() {
            String model = this.model == null ? "" : this.model.trim();
            if (model.startsWith("iPhone")) {
                return "iPhone";
            }
            if (model.startsWith("iPad")) {
                return "iPad";
            }
            if (model.startsWith("iPod")) {
                return "iPod";
            }
            if (model.startsWith("Watch")) {
                return "Apple Watch";
            }
            if (model.startsWith("Mac")) {
                return "Mac";
            }
            if (model.startsWith("Android")) {
                return "Android";
            }
            return "附近设备";
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
    /**
     * 上一包把表改到了没有。浏览线程自己写自己读；{@link #receive} 置位，{@link #loop} 清位。
     */
    private boolean dirty;
    /** 上一次记日志时那一串「谁在、在哪」。 */
    private volatile String lastSeen;
    /** 上一次打诊断行是什么时候，只给 {@link #DIAG} 用。 */
    private long lastDigest;
    /** 这一次浏览是什么时候开始的，用来量「对端多久出现在列表里」。 */
    private long startedAt;
    private Thread thread;
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;
    /** 组播是从哪块网卡进来的，同时也是连对端时该从哪块出去。0 表示系统默认。 */
    private volatile int nicIndex;

    /**
     * 现在绑在哪块网卡上，名字；没有走系统默认路由时为 {@code null}。
     *
     * <p>只在 {@link #rebind} 里读，答案不同结论就不同：本来就在 {@code awdltun0} 上，它一消失
     * 就只能**等**——换到旁边任何一块都是换到一条 iPhone 不在的路上；本来在 Wi-Fi 或
     * {@code wonder0} 上，{@code awdltun0} 一出现就该**搬**过去。
     */
    private volatile String bound;

    /**
     * 还在不在原来那块网卡上——每 {@link #REBIND_CHECK_MS} 核一次。
     *
     * <p>2026-11-18 抓包时撞上的真实现场：把这台的 AirDrop 关掉再打开，`awdltun0` 被拆了
     * 重建，index 变了，而 socket 绑的还是旧那块。于是 {@code send} 静默失败（异常被下面的
     * 「一次发不出去就等下一轮」吞掉）、{@code receive} 一直超时——**一个字的日志都没有**，
     * 列表停在上一次的内容上再也不动，直到人退出这一页再进来。浏览线程本身是活的，只是聋了。
     */
    private int sendFails;

    /**
     * 自 {@link #open()} 起，读到过几条**别人**的地址记录。
     *
     * <p>这是「我们到底听不听得见」唯一可靠的判据，{@code heard} 那一类计数不行：自己的宣告
     * 会绕回来，一台没开 AirDrop 的手机旁边也照样有别人的组播，两个都让「收到过包」为真，而
     * 真正要问的是「别人说的话进来了没有」。
     *
     * <p>0 加上问过几次，就是 {@link #DEAF_QUERIES} 说的那种哑：发得出去，收不到。
     */
    private int foreign;

    /**
     * 最后一次读到**别人**地址记录的时刻，{@link System#currentTimeMillis()}。
     *
     * <p>{@link #foreign} 是累计数，一旦不为零就永远不为零，于是「我刚才还听得见吗」这个问题
     * 它答不了——而这正是「40 秒里一台设备都没出现」时最需要回答的：我们是听不见，还是对面
     * 没说话。两个原因的处理完全不同（一个要重开 socket，一个只能等）。
     */
    private long lastForeign;

    /**
     * 本次浏览里，听到过别人的地址记录没有。**不随 socket 重开清零。**
     *
     * <p>它只回答一件事：我们还有没有「对端多久说一次话」的底。有底的时候，沉默才有意义，
     * 才能拿去和 {@link #DEAF_SILENCE_MS} 比；没底的时候（刚打开发送面板），沉默什么都说明
     * 不了，只能数自己开过几次口。
     *
     * <p>不能用 {@link #foreign} 代劳：那个是**每块 socket 自己**的账，{@link #open} 会清零，
     * 于是「刚重开」和「刚启动」长得一模一样。2026-11-19 17:20 那一轮的抖动正是这么来的——
     * 重开之后 {@code lastForeign} 是 0，程序以为又从零开始了，而实际上它已经听了九十秒。
     */
    private boolean heardEver;

    /** 当前这块 socket 是什么时候开的，{@link System#currentTimeMillis()}。 */
    private long openedAt;

    /**
     * 当前这块 socket 开出之后，组播问过几次。
     *
     * <p>和 {@code loop()} 里那个 {@code asked} 不是一回事：那个跨 socket 累计，只用来打
     * 「问了多少次一包未收」那一行。判「哑」必须用**这块 socket** 的数——冷启动那一档说的是
     * 「我刚开、按 300 毫秒一次问了四声、一声都没回」，它在重开之后也该从零开始。
     */
    private int askedSinceOpen;

    /** 下一次「哑了」检查允许重开 socket 的时刻。 */
    private long nextDeaf;

    /** 下一次打 {@link #PULSE} 那一行的时刻。 */
    private long nextPulse;

    /** 下次重开要等多久。见 {@link #DEAF_RETRY_MS}。 */
    private long deafWait = DEAF_RETRY_MS;

    /** 下一次核网卡的时间。 */
    private long nextRebind;

    /**
     * 每隔多久核一次网卡。
     *
     * <p>1 秒：AirDrop 开关是人的动作，列表在两秒内跟上就够了；再密就是白烧电。这一条不负责
     * 「对端走了多久算走」（那是 {@link #expire} 的事），只负责「我是不是还在听」。
     */
    private static final long REBIND_CHECK_MS = 1_000;

    /** 连续几次发不出去就认定这块 socket 已经不属于这条链路。 */
    private static final int REBIND_FAILS = 3;

    /**
     * 至少问过几次，才允许认定「我们在发、但听不见」。
     *
     * <p>4 次：冷启动时按 {@link #ASK_EAGER_MS} 问是 4 × 300 = 1.2 秒，够长，不至于把一台答得
     * 慢的对端误判成聋；够短，人还站在分享面板前面。
     *
     * <p>**它只管冷启动那一半**，不是通用时间门槛：一次都没听见过时没有「它多久说一次」的底，
     * 只能数自己开过几次口（4 × 300 = 1.2 秒）。听见过之后换成 {@link #DEAF_SILENCE_MS}，
     * 因为那时候要问的是「安静了多久」，而这个数是按次计的不是按时间计的。
     */
    private static final int DEAF_QUERIES = 4;

    /**
     * 链路安静了多久，才允许认定「我们聋了、该重开 socket」。**20 秒，不是 4 秒。**
     *
     * <p># 4 秒是错的，17:20 那一轮把它打穿了
     *
     * <p>原来这里直接用 {@link #LINK_QUIET_MS}（4 秒），理由是「和 {@code expire()} 问的是
     * 同一件事，就用同一个数」。问的事确实相同，但**要做的动作不同**：那边只是「先别删」，
     * 猜错一次没有任何代价；这边要拆掉 socket 再建一块，猜错一次就把正在路上的一串应答全丢了。
     * 同一个误差，两种代价，就不该共用一个数。
     *
     * <p>2026-11-19 17:20（{@code CONNECTABLE_MS} 抬到 20 秒之后的第一轮）：
     * <pre>
     * 17:20:38.892 deaf: 16 queries sent, nothing from anyone for 4082 ms — reopening
     * 17:20:43.647 deaf: 21 queries … for 20374 ms
     * 17:20:53.938 deaf: 30 queries … for 30415 ms
     * 17:21:13.997 deaf: 57 queries … for 5769 ms
     * 17:21:21.526 deaf: 65 queries … for 4047 ms
     * 17:21:26.780 deaf: 70 queries … for 4054 ms
     * 17:21:32.049 deaf: 75 queries … for 4085 ms
     * 17:21:46.087 deaf: 87 queries … for 82564 ms
     * </pre>
     * 九十秒里重开了八次。旁边只有一台 iPhone，而它的应答器是成段休眠的（答几包、睡六到八秒，
     * 见 {@link #CONNECTABLE_MS}）——**于是「四秒没人说话」在这条链路上是常态**，我们把这个
     * 常态当成了自己的故障，一圈一圈地拆 socket。这是自己给自己制造的丢包。
     *
     * <p># 20 秒是和 {@link #LINK_DEAD_MS} 同一个数，但理由不同
     *
     * <p>那个数是「不知道就先别删」的天花板，这个数是「再不动手就是装死」的下限，两个都落在
     * 「沉默到我们已经没有信息了」这一刻——真到了这里，删掉它和重开一次赌一把是同一件事，
     * 做哪个都不比另一个更武断。
     *
     * <p># 另外，中途变聋从来没被观测到过
     *
     * <p>这条路修的是一类**假想**故障。唯一像它的现场是 17:10 那次静默 130 秒，后来用
     * {@code /proc/net/dev} 量清楚了：{@code awdltun0} 的 RX 字节和包数 20 秒里一个没动、
     * TX 涨了 55 个包——对面一个字都没发，不是我们聋了，重开 socket 也救不了（那一轮重开过，
     * 没有用）。所以这条路径只剩兜底意义，**宁可慢，不要吵**。
     */
    private static final long DEAF_SILENCE_MS = 20_000;

    /** 第一次重开 socket 之前等多久；之后每哑一次翻倍，直到 {@link #DEAF_CEIL_MS}。 */
    private static final long DEAF_RETRY_MS = 5_000;

    /**
     * 重开间隔的上限。
     *
     * <p>重开会放掉组播锁再拿回来，而「一个人都没有」是这一页最正常的状态之一。一分钟一次是
     * 「还醒着、但不慌」的位置：边上真来了一台，最多一分钟之后就有一位新的赢家。
     */
    private static final long DEAF_CEIL_MS = 60_000;

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

    /**
     * 现在有几台能连的。
     *
     * <p>查询节奏按它分档，见 {@link #ASK_EAGER_MS}。数「能连」而不是数「听过」：只到了一条
     * SRV、地址还没回来的那些还上不了列表，人在屏幕上看到的仍然是空的，那时问得勤才有意义。
     */
    private int ready() {
        synchronized (peers) {
            int ready = 0;
            for (Peer peer : peers.values()) {
                if (peer.connectable()) {
                    ready++;
                }
            }
            return ready;
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
            startedAt = System.currentTimeMillis();
            // 一次浏览一套账。这两个是字段而不是局部变量，因为它们要跨 socket 活下来；但跨
            // 「关掉面板再打开」就没有意义了——那时候链路、对端、我们绑的 socket 全是新的。
            heardEver = false;
            deafWait = DEAF_RETRY_MS;
            while (running) {
                long now = System.currentTimeMillis();
                if (now >= nextAsk) {
                    send(query);
                    asked++;
                    askedSinceOpen++;
                    // 一台能连的都没有时问得勤一些：那是人正盯着空列表等它出现的时刻。
                    nextAsk = now + (ready() == 0 ? ASK_EAGER_MS : ASK_EVERY_MS);
                    // 组播那一份可能谁也没收到。对已经静了一会儿的对端再单独叫一声，见
                    // {@link #prompt}——这一步是为了让「沉默」这件事本身变少，而不是让阈值更耐沉默。
                    prompt(query);
                    // 问了半分钟仍然一包未收。这和「答了但记录凑不齐」是两回事：后者会走
                    // announce 的 partial records 分支，前者连一个字节都没回来，通常意味着
                    // 组播没出得去这块网卡，或者对端不在这条链路上。
                    if (heard == 0 && asked % 15 == 0) {
                        Log.w(TAG, "browse: " + asked + " queries sent, nothing came back");
                    }
                }
                dirty = false;
                if (receive()) {
                    heard++;
                }
                // 过期检查放在这里，而不是只放在收包超时里。
                //
                // 5353 上从来不是只有我们在说话：打印机、投屏、别人的 AirPlay 一直在广播。
                // 只要它们来得比 250 毫秒密，socket.receive() 就一直不超时，挂在超时上的
                // 过期检查就一次都不会跑——关掉 AirDrop 的那台于是永远挂着，要重进面板才消失。
                // 这是「关了还显示」的真正原因，和 EXPIRE_MS 是几秒无关。
                if (expire() > 0) {
                    dirty = true;
                }
                // 还在不在原来那块网卡上。AirDrop 一开一关就把 awdltun0 换了一个，而 socket
                // 不会自己跟过去——它只是安静地不再收发。检查放在过期检查之后：先按「没有新
                // 记录」把该走的走掉，再换网卡重新问，两步互不遮挡。
                if (now >= nextRebind) {
                    nextRebind = now + REBIND_CHECK_MS;
                    rebind();
                }
                // 「在发、但听不见」——和「没人应答」是两件事，必须分开。
                //
                // 2026-11-18 冷启动的现场：从相册分享第一次打开发送面板，查询每 300 毫秒发一次、
                // `ip -s link show awdltun0` 上 TX 一路涨，RX 一动不动，30 秒里一台设备都没出现；
                // 下滑关掉面板再分享打开，537 毫秒就 ready。原因是 5353 上不止我们一个 socket——
                // 接收端（Responder）在我们之后 28 毫秒也绑了同一个端口，而内核把**单播**报文只
                // 交给最后绑的那一个。查询带着 QU 位（见 `crates/ffi/src/browse.rs`），对端的应答
                // 于是全是单播，一条条都进了接收端，浏览这一路一个字节也看不到。
                //
                // 不试图指定谁先绑——那要看两条线程谁先跑完 bind，而 bind 之后还会被 AirDrop 的
                // 开关重来一次。做法是：听不见就把自己的 socket 重开一遍，重开的那个一定是最新的，
                // 单播于是回到我们这边。重开之后接收端会失去单播，但它在 AWDL 上只靠组播活着
                // （实测 iPhone 在浏览链路上只答不问，见 {@link #CONNECTABLE_MS}），而面板一关
                // 我们就让出端口，它下一个包照样收得到。
                // 判据必须是「**最近**还听得见吗」，不能是 `foreign == 0`。`foreign` 是累计数
                // （见 {@link #foreign}）：冷启动时它是 0，但只要听见过一次就永远不为 0，于是
                // 中途变聋永远触发不了重开。2026-11-19 17:10 这一轮就是这样：`foreign` 冻在 34
                // 一动不动、空了 130 秒，而自愈条件一次都没成立——`lastForeign` 本来是专为回答
                // 这个问题加的，`expire()` 已经在用它，这里漏了。
                //
                // 两条路分开判，因为能用的量不同，而**分界线是「本次浏览听见过没有」**
                // （{@link #heardEver}），不是「这块 socket 听见过没有」。两块的区别是重开：
                // 重开会把 `lastForeign` 清零，于是「刚重开」和「刚启动」长得一模一样，而 17:20
                // 那一轮的抖动就是被这个假象点着的——重开完立刻走冷启动那条、`asked` 又早过百、
                // 条件恒真，五秒后接着重开。
                //   · 一次都没听见过（冷启动）→ 没有「它多久说一次」的底，只能数**这块 socket**
                //     问了几次，4 × {@link #ASK_EAGER_MS} = 1.2 秒。用时间门槛会把这条路从
                //     1.2 秒拖到 20 秒，而冷启动正是人站在面板前面干等的那一段。
                //   · 听见过之后 → 用安静了多久，门槛是 {@link #DEAF_SILENCE_MS}（20 秒）。
                //     重开之后计时从 {@link #openedAt} 重新起，不然 `lastForeign == 0` 会被算成
                //     「从纪元开始就没声音」。
                long quiet = now - (lastForeign == 0 ? openedAt : lastForeign);
                boolean deaf = heardEver ? quiet > DEAF_SILENCE_MS : askedSinceOpen >= DEAF_QUERIES;
                if (deaf && now >= nextDeaf) {
                    nextDeaf = now + deafWait;
                    deafWait = Math.min(deafWait * 2, DEAF_CEIL_MS);
                    trace("deaf: " + askedSinceOpen + " queries on this socket, nothing from"
                            + " anyone for " + quiet + " ms — reopening the socket (someone"
                            + " else may be bound to 5353, or the link is down)");
                    sendFails = 0;
                    reopen();
                } else if (!deaf && heardEver && deafWait != DEAF_RETRY_MS) {
                    // 听见了。下一次万一又哑，还是从最短的那一档开始等。
                    deafWait = DEAF_RETRY_MS;
                }
                if (DIAG && now - lastDigest >= DIAG_EVERY_MS) {
                    lastDigest = now;
                    digest();
                }
                if (PULSE && now >= nextPulse) {
                    nextPulse = now + PULSE_MS;
                    pulse();
                }
                if (dirty) {
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
        // connectable（地址+端口）越快，开 AirDrop 后显示得越及时。这一档同时是过期检查的
        // 节奏——每轮循环都查一次 expire()，所以 2.5 秒的阈值真正精确到 2.5 秒。
        socket.setSoTimeout(250);
        // 绑 IPv4 通配的 socket 收不到 ff02::fb，绑 IPv6 通配的又发不出 224.0.0.251：
        // 地址族要跟着上面选的组走，而不是跟着习惯走。
        socket.bind(new InetSocketAddress(
                InetAddress.getByName(v6 ? "::" : "0.0.0.0"), PORT));
        if (nic != null) {
            socket.setNetworkInterface(nic);
        }
        // 0 = 系统默认。写成 0 而不是留着上一次的值：网卡真的没了的时候，下一条有效网卡
        // 一定与它不同，{@link #rebind} 就会把我们搬过去。
        nicIndex = nic == null ? 0 : nic.getIndex();
        bound = nic == null ? null : nic.getName();
        // 这一块 socket 得自己证明听得见。上一次听到的别人的地址不算数——那可能来自上一个 socket。
        foreign = 0;
        lastForeign = 0;
        // 但「本次浏览听见过没有」不能跟着清零，见 {@link #heardEver}：重开之后的沉默和启动时的
        // 沉默是两件事，前者有底、后者没有。
        openedAt = System.currentTimeMillis();
        askedSinceOpen = 0;
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
        return link(true);
    }

    /**
     * 同上，但可以拒绝兜底。
     *
     * <p>{@code fallback=false} 时只认 AWDL 那两块，没有就返回 {@code null}——把「这条路不在」
     * 当成一个正当答案交给调用方，而不是硬塞一块能开却不通的网卡。
     */
    private NetworkInterface link(boolean fallback) {
        try {
            for (String name : new String[]{AWDL, AWDL_RADIO}) {
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
    private static NetworkInterface named(String name) {
        try {
            NetworkInterface nic = NetworkInterface.getByName(name);
            return nic != null && nic.isUp() ? nic : null;
        } catch (Exception gone) {
            return null;
        }
    }

    /**
     * 网卡换了就把 socket 搬到新那块上。
     *
     * <p>两种触发，说的都是同一件事——**这个 socket 已经不在听了**，而不是「我在听，只是没人
     * 说话」。后者归 {@link #expire}，前者归这里；混在一处就会把「对端走了」误判成「我聋了」。
     *
     * <p>搬完不清空表：对端的地址是它自己的，换条链路也还成立；真正过期的会由 {@link #expire}
     * 按沉默时长走掉。清空反而会让列表闪一下再回来。
     */
    private void rebind() {
        // 分两档，因为「该搬去哪」和「该不该等」是同一个问题的两面。
        //
        // 2026-11-18 21:57 实测的现场：杀掉 awdld，awdltun0 没了。那时按「换一块能用的」
        // 来理解这件事，就会搬到 wonder0 上——它也在 AWDL 名单里，所以兜底那一条拦不住它。
        // 搬过去之后每 250 毫秒发一次查询、每次 sendto 都 ENETUNREACH（那条路没有 IPv6 默认
        // 路由），攒够三次又不通，再触发一次 rebind，再开一次 socket：一分钟里把自己开关了
        // 几十遍，而 iPhone 根本不在那条路上。
        //
        // 所以绑在 awdltun0 上时就只认 awdltun0，它没了就原地等（链路回来时 index 一定是新的，
        // 下一次检查自然会搬过去——实测 1.3 秒）；绑在别处时才贪心，见到 awdltun0 就搬。
        NetworkInterface nic = AWDL.equals(bound) ? named(AWDL) : link(true);
        if (nic == null && AWDL.equals(bound)) {
            // 不是 socket 哑了，是整条链路不在。sendFails 一起归零：留着它只会在链路回来时
            // 白白多触发一次重开。
            sendFails = 0;
            return;
        }
        int index = nic == null ? 0 : nic.getIndex();
        boolean moved = index != nicIndex;
        boolean deaf = sendFails >= REBIND_FAILS;
        if (!moved && !deaf) {
            return;
        }
        trace("rebinding: " + (moved
                ? "link moved to " + nic.getName() + " (" + nicIndex + " → " + index + ")"
                : sendFails + " sends in a row went nowhere"));
        sendFails = 0;
        reopen();
    }

    /**
     * 关掉重开一块 socket，其余什么都不动。
     *
     * <p>两种场合用它：网卡换了（{@link #rebind}），和「在发但听不见」（{@link #loop}）。
     * 后者的道理是内核的一条规矩：几个 socket 用 {@code SO_REUSEADDR} 绑同一个端口时，**单播**
     * 报文只交给最后绑的那一个。重开就是把我们放回那一位——不需要知道谁先谁后，也不用和接收端
     * 商量，重开的那一个一定是最新的。
     *
     * <p>重开失败只记一行，线程活着：这一页还开着，人随时会把 AirDrop 再打开一次，而下一轮
     * 检查（{@link #REBIND_CHECK_MS}）会再试。
     */
    private void reopen() {
        close();
        try {
            open();
        } catch (Exception failed) {
            trace("reopen failed: " + failed);
        }
    }

    /** 记下本机地址，用来把自己从对端列表里认出来。 */
    private void collectOwnAddresses() {        try {
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
            socket.send(new DatagramPacket(query, query.length, group, PORT));
            sendFails = 0;
        } catch (Exception ignored) {
            // 一次发不出去就等下一轮：组播本来就不保证送达，把一次失败当失败会误报。
            // 但连着几次就不是运气了——见 {@link #sendFails} 与 {@link #rebind}。
            sendFails++;
        }
    }

    /**
     * 对静了一会儿的、能连的对端，单独问一句（单播）。
     *
     * <p># 为什么光有组播查询不够
     *
     * <p>组播那一份每秒发一次，但它有两个地方会**无声地**丢：链路本身（AWDL 的时隙调度在两端
     * 不同步时会周期性丢包），和对端的重复应答抑制（同一份记录不会答得太勤）。两处都不报错，
     * 我们能看到的只有「它静了」——而「它静了」正是 {@link #expire} 用来判死的输入。也就是说：
     * 判死的输入本身是个不可靠信号。
     *
     * <p># 单播为什么不一样
     *
     * <p>mDNS 的规矩是收到单播查询就要单播应答（RFC 6762 §6.7），没有「刚答过所以先不说话」的
     * 余地——重复抑制针对的是组播。所以这一句发出去，它还在，就一定会回。盲区于是从「等它自己
     * 开口」变成「一问一答」，后者只要几百毫秒。
     *
     * <p># 不记 {@link #sendFails}
     *
     * <p>那个计数判的是「这块网卡还在不在」。一台刚走掉的设备的单播失败会把它推到
     * {@link #REBIND_FAILS}，触发一次毫无必要的重开——而重开会放掉组播锁再拿回来。
     */
    private void prompt(byte[] query) {
        long now = System.currentTimeMillis();
        for (Peer peer : snapshot()) {
            if (!peer.connectable() || now - lastHeard(peer) < PROMPT_AFTER_MS) {
                // 它刚说过话，下一次静默重新算起。
                peer.prompted = false;
                continue;
            }
            try {
                socket.send(new DatagramPacket(query, query.length, address(peer), PORT));
                if (!peer.prompted) {
                    peer.prompted = true;
                    // 这一行是「单播到底管不管用」唯一的证据：它之后要紧跟一条 quiet 归零的
                    // pulse，或者紧跟一个 dropped——两者都说明这次叫醒了没有。
                    trace("prompted " + peer.name + " by unicast after "
                            + (now - lastHeard(peer)) + " ms of silence");
                }
            } catch (Exception unreachable) {
                // 它可能真的走了，也可能这个地址暂时不可用。等下一轮再叫，这里不是判断的地方。
            }
        }
    }

    /**
     * 收一个报文。
     *
     * @return 收到了一包（不管里面有没有我们认得的东西）。表有没有被改到另记在 {@link #dirty}
     *         上——两者必须分开：别的服务的广播会不停地把「收到」置真，而它一次也不该引起重画。
     */
    private boolean receive() {
        byte[] buffer = new byte[1500];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
        } catch (java.net.SocketTimeoutException idle) {
            // 超时不再是过期检查的唯一入口，见 {@link #loop}。
            return false;
        } catch (Exception closed) {
            return false;
        }
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, data.length);
        // 先认人，再看包里写了什么。
        //
        // 顺序很重要：iPhone 在分享页里不停地问 _airdrop._tcp.local，那些查询包里一条记录都
        // 没有，mdnsRecords 返回空，absorb 什么也不会做——可这一包本身恰恰是「它还在」最硬的
        // 证据。以前只在解析出记录时才算「听到了」，于是它明明在链路上喊着，我们却按沉默算。
        //
        // 空包 = 它在问，非空 = 它在答。这两种节奏不一样，过期判据也不一样，见 {@link #heard}。
        boolean question;
        String lines = "";
        try {
            lines = new String(Native.mdnsRecords(data), java.nio.charset.StandardCharsets.UTF_8);
            question = lines.trim().isEmpty();
            if (absorb(lines)) {
                dirty = true;
            }
        } catch (Exception notForUs) {
            // 5353 上还有别的服务在广播：打印机、投屏、别人的 AirPlay。不是错误。
            question = true;
            lines = "";
        }
        if (WIRE) {
            wire(packet.getAddress(), data, question, lines);
        }
        heard(packet.getAddress(), question);
        return true;
    }

    /** 十六进制表。手工查表只因为 `String.format("%02x")` 在热路径上要造 1500 个字符串。 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** {@link #WIRE} 的那一行：谁、多大、是问是答、解析出来的行、整包字节。 */
    private static void wire(InetAddress from, byte[] data, boolean question, String lines) {
        StringBuilder hex = new StringBuilder(data.length * 2);
        for (byte b : data) {
            hex.append(HEX[(b >> 4) & 0x0f]).append(HEX[b & 0x0f]);
        }
        String rows = lines.trim().replace('\n', '|');
        trace("rx " + data.length + "B " + (question ? "Q" : "A") + " from "
                + (from == null ? "?" : bare(from.getHostAddress()))
                + (rows.isEmpty() ? "" : " :: " + rows)
                + " :: " + hex);
    }

    /**
     * 某一台在说话——按源地址认人，顺手量一下它的节奏。
     *
     * <p># 为什么按地址认，不按包里写的名字
     *
     * <p>查询包里没有名字。要把它归到某一台设备头上，唯一的线索就是「这一包从谁的地址发
     * 过来」，而 {@link Peer#address} 正是我们知道的那个地址。
     *
     * <p># 为什么顺手量节奏
     *
     * <p>{@link #EXPIRE_MS} 原来是个常数，可「沉默多久算走」本来就该跟着对方的说话频率走：
     * iPhone 在分享页里约每秒问一次，退出分享页就彻底安静。量出来的节奏让阈值自动贴合——它
     * 话多，阈值就短，关掉 AirDrop 后消失得快；它话少，阈值就跟着放宽，不会把一台只是答得慢
     * 的设备误删。见 {@link #timeout}。
     *
     * <p>实例名每分钟换一次（Apple 的隐私设计），换一次就是一条新记录、一个新 {@code Peer}，
     * 量到的节奏也跟着从头开始——这没关系：新实例建出来一两秒内就会收到它下一帧查询，节奏很
     * 快就能重新量到，而那段时间用的 {@link #EXPIRE_MS} 兜底并不比原来差。
     */
    private void heard(InetAddress source, boolean question) {
        if (source == null) {
            return;
        }
        String from = bare(source.getHostAddress());
        long now = System.currentTimeMillis();
        synchronized (peers) {
            for (Peer peer : peers.values()) {
                // 地址还没到（只收到 SRV）的这台认不出来，它会走 absorb 里 seenAt 那条路。
                if (peer.address.isEmpty() || !bare(peer.address).equals(from)) {
                    continue;
                }
                if (question) {
                    peer.questions++;
                    peer.askedAt = now;
                } else {
                    peer.replies++;
                }
                long gap = peer.heardFrom == 0 ? 0 : now - peer.heardFrom;
                peer.heardFrom = now;
                // 太短：同一次说话被拆成了几个包。太长：它中间真的安静过，那不叫节奏。
                if (gap < QUIET_MIN_MS || gap > RHYTHM_CEIL_MS) {
                    continue;
                }
                peer.period = peer.period <= 0
                        ? gap
                        : Math.round(peer.period * (1 - RHYTHM_ALPHA) + gap * RHYTHM_ALPHA);
                peer.gapMin = peer.gapMin == 0 ? gap : Math.min(peer.gapMin, gap);
                peer.gapMax = Math.max(peer.gapMax, gap);
                if (++peer.gaps == RHYTHM_SAMPLES) {
                    // 一行统计，答的是「它到底隔多久说一次话」——EXPIRE_FLOOR_MS 的依据。
                    trace("rhythm: " + peer.name + " speaks every " + peer.period + " ms ("
                            + peer.gaps + " gaps, " + peer.gapMin + "–" + peer.gapMax + " ms, q="
                            + peer.questions + " a=" + peer.replies + ")");
                }
            }
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
                        boolean removed = peers.remove(instance) != null;
                        // 有些实现把 goodbye 挂在主机名上（A/AAAA 的 TTL=0），不是实例名。
                        // 那种记录带 service suffix 时上面已覆盖；这里再按主机名兜一次。
                        if (peers.values().removeIf(peer -> field[1].equals(peer.host))) {
                            removed = true;
                        }
                        if (removed) {
                            // 这一行是「快」的来源：有 goodbye 就不必等计时器，消失是即时的。
                            // 真机上要确认它到底会不会来——不来，才只能退回阈值。
                            trace("goodbye " + field[1] + " — removed now, without waiting for the timer");
                            changed = true;
                        }
                    }
                    case "srv" -> {
                        Peer peer = peer(field[1]);
                        peer.host = field[2];
                        peer.port = parseInt(field[3]);
                        touch(peer);
                        changed = true;
                    }
                    case "addr" -> {
                        if (own.contains(bare(field[2]))) {
                            // 自己的宣告绕回来了。这里原来只做「不填地址」，于是这个实例会一直
                            // 挂在表里：地址永远空（所以永远 partial）、记录又一直刷新（所以永远
                            // 不过期），最后变成一行常驻的
                            // `nothing connectable yet (1 instance(s) with partial records)`——
                            // 表里明明空了却写着 1，把「对面真的走了」这件事盖住了。
                            //
                            // 地址是自己的，就证明**这个 host 就是我们**。实例名每分钟换一次，
                            // 所以按 host 整个收掉，而不是只收这一条。
                            if (peers.values().removeIf(peer -> field[1].equals(peer.host))) {
                                changed = true;
                            }
                            continue;
                        }
                        // 别人的地址进来了——不论这条记录最后有没有用上，它都证明了一件事：
                        // 这条 socket 听得见。{@link #foreign} 只记这个，不记「谁说的」。
                        foreign++;
                        lastForeign = System.currentTimeMillis();
                        heardEver = true;
                        // 地址行属于主机名，回填给所有指向这台主机的实例。
                        for (Peer peer : peers.values()) {
                            if (peer.host.equals(field[1])) {
                                // 先记「听到了」，再看地址有没有变——两件事。
                                //
                                // 以前这一句在 if 里面，于是同一台设备反复答同一个地址时，
                                // 它答得再勤也算沉默：地址没「变好」，时刻就不刷新。
                                // 这不是理论问题——应答本来就是重复的，所以正确实现的
                                // responder 永远刷新不了它，{@link #expire} 于是把一台明明
                                // 在应答的设备删掉，过一秒又被下一条 SRV 加回来。
                                touch(peer);
                                String address = field[2];
                                if (betterAddress(address, peer.address)) {
                                    peer.address = address;
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
                            Peer peer = peer(field[1]);
                            touch(peer);
                            peer.model = field[3];
                            changed = true;
                        } else if ("h".equals(field[2])) {
                            Peer peer = peer(field[1]);
                            touch(peer);
                            peer.fingerprint = field[3].trim();
                            changed = true;
                        }
                    }
                    default -> { }
                }
            }
            // 换实例名的那个新对端，先把地址从旧的上面接过来，再往下判「能不能连」。
            // 顺序压在这里而不是循环开头：同一条报文里常常带着新实例的 SRV 和旧实例的 goodbye，
            // 先把这一轮读完，接过来的地址才不会立刻被下一行抹掉。
            changed |= inherit();
            // 一台设备凑齐「地址 + 端口」的那一刻记一笔。
            //
            // 这是人眼在列表里看见它的时刻，也是「对方一开 AirDrop，我们多久显示」唯一的可测
            // 证据。没有这一行，这件事只能靠感觉形容（「有时候要等一会儿」），也就没法回答
            // 「还能不能再快」。
            long now = System.currentTimeMillis();
            for (Peer peer : peers.values()) {
                if (peer.connectable() && !peer.reported) {
                    peer.reported = true;
                    peer.readyAt = now;
                    trace("ready " + peer.name + "@" + peer.address + ":" + peer.port
                            + " after " + (now - startedAt) + " ms of browsing");
                }
            }
        }
        return changed;
    }

    /**
     * 让还没拿到地址的新实例，从同一台主机的旧实例那里把地址接过来。
     *
     * <p># 为什么必须有这一步
     *
     * <p>iPhone 的实例名每分钟换一次（Apple 的隐私设计），换的时候它先发新实例的 SRV，再撤掉旧的。
     * 而「能连」要求地址和端口都在，<b>地址挂在主机名上、要等另一条 addr 记录</b>——于是换名字的
     * 那一段里，表里同时躺着：一个还在沉默的旧实例，和一个只有端口没有地址的新实例。旧实例被
     * {@link #expire} 判死删掉，新实例又还上不了列表，{@code ready() == 0}。人看到的就是
     * 「设备自己消失了，过几秒又回来」——而它从头到尾都是同一台手机，地址一次都没变。
     *
     * <p>2026-11-19 15:57 的日志正是这一刻：{@code dropped … after 2727 ms} 之后紧跟一行
     * {@code nothing connectable yet (1 instance(s) with partial records)}——被删的是旧名字，
     * 留下的那一个就是新名字，它缺的只是地址。
     *
     * <p># 凭什么敢把地址抄过去
     *
     * <p>SRV 里的主机名（{@code xxx.local}）属于机器，不属于实例。同一个主机名下的所有实例，
     * 地址就是同一个，所以这不是猜，是读出来的结论。抄的只有地址和指纹——节奏统计不抄：那是
     * 旧实例的样本，新实例自己会重新量。
     *
     * <p># 交完就把旧的删掉
     *
     * <p>留着等 {@link #expire} 的 {@code retired} 那一圈再删，表里会有最多 250 毫秒同时躺着
     * 两个同地址的实例，而 {@link #announce} 与界面的重画都按实例名去重——那 250 毫秒里屏上是
     * 两格一模一样的设备。既然地址已经交接完，旧的这一格就没有存在理由了。
     *
     * @return 表有没有被改动过（交给了谁、或者删掉了谁）
     */
    private boolean inherit() {
        boolean changed = false;
        // 先在快照上找、再写回 peer，避免边遍历边改表。
        List<Peer> all = new ArrayList<>(peers.values());
        for (Peer peer : all) {
            if (!peer.address.isEmpty() || peer.host.isEmpty()) {
                continue;
            }
            for (Peer other : all) {
                if (other == peer || !peer.host.equals(other.host) || other.address.isEmpty()) {
                    continue;
                }
                peer.address = other.address;
                peer.scope = other.scope;
                if (peer.fingerprint.isEmpty()) {
                    peer.fingerprint = other.fingerprint;
                }
                trace("inherited " + peer.address + " for " + peer.name + " from " + other.name
                        + " — same host, so it is the same device under a new instance name");
                peers.remove(other.name);
                changed = true;
                break;
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
            touch(peer);
            peer.scope = nicIndex;
            peers.put(name, peer);
        }
        return peer;
    }

    /**
     * 记一笔「关于它的记录到了」。
     *
     * <p>**只刷 {@link Peer#seenAt}，不碰 {@link Peer#heardFrom}**：后者是「收到过几个包」的
     * 计时器，由 {@link #receive} 那一侧独占。这里也刷的话，间隔永远是 0（absorb 先跑，heard
     * 后跑，瞬间就被填成当前时刻），节奏量不出来——真机上第一次跑就是 `gaps=0`，一行样本都
     * 没攒到。两个时刻记的是两件事，就该由两条路径各自维护。
     */
    private static void touch(Peer peer) {
        peer.seenAt = System.currentTimeMillis();
    }

    /**
     * 丢掉太久没再出现的对端，返回丢掉的个数。
     *
     * <p>每轮循环都调一次（收包 250 毫秒超时，所以大约每 250 毫秒一次），不只在收包超时时调
     * ——理由见 {@link #loop}，那是「关了还显示」的根因。
     */
    private int expire() {
        long now = System.currentTimeMillis();
        // 我们这侧还听得见吗？`lastForeign` 是「最后一条**别人的**地址记录」的时刻，自己的宣告
        // 绕回来不算（那一条在上面被 own 挡掉了）。它一停，表里每一台的「沉默」就同时开始涨，
        // 因为它们量的是同一件事：这条 socket 上还有没有声音。
        boolean deaf = lastForeign == 0 || now - lastForeign > LINK_QUIET_MS;
        synchronized (peers) {
            int before = peers.size();
            // 「还在」的证据有两个来源：它答了（seenAt，记录解析出来了），或者它只是在说话
            // （heardFrom，查询包）。后者以前根本不算数，于是一台明明在链路上不停发查询的
            // iPhone，会因为「没答出我们能解析的记录」被删掉——手机就在手边、AirDrop 开着，
            // 列表里却是空的，过一秒它又被下一帧查询带回列表。这就是「闪烁」。
            peers.values().removeIf(peer -> {
                long silence = now - lastHeard(peer);
                // 链路自己也安静的时候不用 timeout()，见 LINK_QUIET_MS：那一段沉默量的是我们
                // 自己的听筒，不是这台设备。只留一个天花板，免得「不知道就先别删」变成「永不删」。
                long threshold = deaf ? LINK_DEAD_MS : timeout(peer);
                if (silence < threshold) {
                    return false;
                }
                // 删掉的那一刻必须写清楚：谁、沉默多久、按什么阈值、节奏是多少、样本几个。
                // 只看到「列表空了」是调不动阈值的——而这条路径的每个数都只能用真机上的行为来定。
                // 后两个数（gaps / max）是阈值里唯一可变的那个来源，不写下来就分不清「按 2.2 秒
                // 兜底删的」和「按实测的丢包宽度删的」。
                trace("dropped " + peer.name + "@" + peer.address + " after " + silence
                        + " ms of silence (threshold " + threshold + ", rhythm " + peer.period
                        + " ms, gaps " + peer.gaps + ", max " + peer.gapMax + " ms"
                        + (deaf ? ", link silent " + (lastForeign == 0
                                ? "since open" : "for " + (now - lastForeign) + " ms") : "") + ")");
                return true;
            });
            // 一台设备只有一个当前实例，但实例名每分钟换一次（Apple 的隐私设计）。
            //
            // 以前旧的靠「再也收不到它的 SRV」自然过期；现在 {@link #heard} 按地址续命，
            // 旧名字会被新名字的包一起喂活，于是在列表里出现一模一样的两格——这正是「先看见
            // 一个名字、一瞬间又变成另一个」的另一半真相。地址没变、名字换了，收掉旧的。
            List<Peer> alive = new ArrayList<>(peers.values());
            for (Peer older : alive) {
                if (older.address.isEmpty()) {
                    continue;
                }
                for (Peer newer : alive) {
                    if (newer == older || !bare(newer.address).equals(bare(older.address))) {
                        continue;
                    }
                    if (newer.seenAt > older.seenAt) {
                        peers.remove(older.name);
                        trace("retired " + older.name + " — same address as " + newer.name);
                        break;
                    }
                }
            }
            return before - peers.size();
        }
    }

    /** 最后一次「有证据说明它还在」的时刻：答了记录，或者发了任意一包。 */
    private static long lastHeard(Peer peer) {
        return Math.max(peer.seenAt, peer.heardFrom);
    }

    /**
     * 诊断：把这台的问答节奏原样打出来。
     *
     * <p>要回答的是三个数：它多久说一次话（gaps 的平均/最小/最大）、那些话里几包是问、几包
     * 是答、现在安静了多久。阈值只能从这三样推出来——「沉默多久算它走了」的正确答案是「比它
     * 活着时的最长沉默再宽一点」，不是任何一个人能凭直觉拍出来的常数。真机上量到的第一个样本
     * 就已经推翻了原先的 2.5 秒：一台活着的 iPhone 合法地安静过 2.6 秒。
     */
    private void digest() {
        long now = System.currentTimeMillis();
        for (Peer peer : snapshot()) {
            if (peer.questions + peer.replies == 0) {
                continue;
            }
            trace("probe: " + peer.name + "@" + peer.address
                    + " q=" + peer.questions + " a=" + peer.replies
                    + " gaps=" + peer.gaps + " avg=" + peer.period
                    + " min=" + peer.gapMin + " max=" + peer.gapMax
                    + " quiet=" + (now - lastHeard(peer)) + " ms");
        }
    }

    /**
     * 打一行「现在看得见什么」。
     *
     * <p>要回答的是「设备自己消失」这个问题里从没被问清的那一半：到底是**它不说话了**，还是
     * **我们听不见了**。一行里三个数就能分开：
     * <ul>
     *   <li>{@code quiet=} 表里那台安静了多久——它在说、还是已经很久没吭声；</li>
     *   <li>{@code foreign last … ms ago} 最近一次听见别人的地址记录是什么时候——几百毫秒说明
     *       这条 socket 好好的，几十秒说明我们聋了，而这两种情况要做的事完全不同；</li>
     *   <li>{@code q=}/{@code a=} 它是在答还是在自己问。</li>
     * </ul>
     *
     * <p>**必须每 {@link #PULSE_MS} 无条件打，不能只打变化。** {@code announce} 只在列表变了
     * 时写，于是「一切正常」在日志里和「什么都没在发生」长得一模一样——前面几轮正是被这一点
     * 引向「抓 150 秒一个字都没有 = 没在跑」的错误结论。
     */
    private void pulse() {
        long now = System.currentTimeMillis();
        List<Peer> all = snapshot();
        StringBuilder line = new StringBuilder();
        for (Peer peer : all) {
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(peer.name)
                    .append(peer.connectable() ? "" : "(partial)")
                    .append(" q=").append(peer.questions)
                    .append(" a=").append(peer.replies)
                    .append(" quiet=").append(now - lastHeard(peer));
        }
        trace("pulse: " + all.size() + " peer(s), foreign " + foreign
                + (lastForeign == 0 ? " (never)" : " (last " + (now - lastForeign) + " ms ago)")
                + (line.length() == 0 ? "" : " — " + line));
    }

    /**
     * 这台设备沉默多久算走了。
     *
     * <p>两档，分界线是「能连」：能连的那一档知道我们正以 1 秒一次的节奏问它（见
     * {@link #CONNECTABLE_MS}）；凑不齐地址端口的那些还在 {@link #ASK_EAGER_MS} 的 300 毫秒档上，
     * 那时 Apple 会压应答，只能退回 {@link #EXPIRE_MS}。
     *
     * <p># 为什么这里现在看节奏，而 2026-11-18 那一次不看
     *
     * <p>那一次「按每台设备自己的节奏算」被否掉，否的是**平均值**：{@code period} 是所有间隔的
     * 平滑，一台每秒一帧的设备它就是 1 秒，两倍才 2 秒——比健康时最长的沉默还短，跟着它算只会
     * 更会闪。那个结论没错，错在把「平均」当成了「最坏」。
     *
     * <p>要判的是「它是不是丢了一包」，那么最坏情况是**连续两段都落在抖动的高端**，上限是
     * {@code 2 × gapMax}（{@link #CONNECTABLE_GAPS} 挡着样本太少的情况）。2026-11-19 的现场就是
     * 这个数：间隔 879–1638，丢一包安静 2.2–3.3 秒，而死阈值是 2.2 秒——一半的丢包都会删掉一台
     * 活着的设备。这段仍然在说「我们问了一次而它没答」，只是把「没答」算成两段而不是一段。
     *
     * <p>真正的「快」仍然在别处：{@link #absorb} 的 {@code gone} 分支。只是 2026-11-18 的
     * 真机证明 iPhone 关 AirDrop 时并不发那个告别，所以这一档不能只当兜底。
     */
    private long timeout(Peer peer) {
        if (DIAG) {
            return DIAG_HOLD_MS;
        }
        if (!peer.connectable()) {
            return EXPIRE_MS;
        }
        // 量到的节奏只往上放宽，不往下收：CONNECTABLE_MS 是真机样本给出的下限。
        return Math.max(CONNECTABLE_MS, lost(peer) + CONNECTABLE_SLACK_MS);
    }

    /**
     * 它丢一包的时候会安静多久，毫秒；样本不够时返回 0（也就是不必放宽）。
     *
     * <p>取 {@link Peer#gapMax} 而不是 {@link Peer#period}：同一个窗口里，平均回答的是「它通常
     * 多快说话」，最大值才回答「它最慢能拖到多久」，而丢包这件事只跟后者有关。
     */
    private static long lost(Peer peer) {
        if (peer.gaps < CONNECTABLE_GAPS || peer.gapMax <= 0) {
            return 0;
        }
        return 2 * peer.gapMax;
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
