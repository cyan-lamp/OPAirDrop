# 甩掉 Mosey：v2 自研协议栈

本目录记录 v2 的路线：用自研用户态协议栈替代 `com.google.android.mosey`，只保留内核侧（
`wonder` / `qca_cld3` 适配）与模块生命周期脚本。分支 `v2-mosey-free`，工作副本独立于
v1.2.0（`../repo-src`），互不影响。

## 1. 边界：哪些是我们的，哪些是 Mosey 的

| 层 | 归属 | v2 处理 |
| --- | --- | --- |
| 802.11 RAW 收发（PAS / NDI、monitor、RX matcher、ADDBA、A-MSDU） | 我们（`kernel/`） | 原样保留 |
| `wonder` mac80211 驱动 + nl80211 vendor 命令 | 我们（`kernel/wonder/`） | 原样保留 |
| AWDL 会话接口创建（`mosey0`） | **Mosey**（nl80211 NAN vif） | 自研 `AwdlLink` |
| IPv6 链路本地、mDNS 发现、TCP 传输 | **Mosey** | 自研 |
| AirDrop 传输协议（`/Discover` `/Ask` `/Upload`） | **Mosey** | 自研（二进制 plist + cpio） |
| 身份证书、TXT 广播字段 | **Mosey** | 自研 |
| 系统互传 UI 接入（分享面板、接收卡片） | 我们（`com.oshare.airdrop`） | 复用现有 hook，去掉 mosey 作用域 |

判断依据来自代码事实，不是猜测：

- `native/socket-buffer.c` 匹配「`AF_INET6` + 双端 link-local + `scope_id == if_nametoindex("mosey0")`」的
  TCP 连接 —— 说明传输面是**标准内核 TCP/IP**，不是私有 ioctl。
- `kernel/wonder/core.h`：`PDEV_NAME "wondertap0"`、`VDEV_NAME "wonder0"`；`mac80211.c` 注册
  `NL80211_IFTYPE_NAN`。`mosey0` 只会在会话期间出现 —— 由 Mosey 用 nl80211 建 NAN vif。
- `docs/SM8750-AWDL.md` 第 26 行明确写着协议层「复用 Mosey 原生服务与 FFI」，即协议栈是唯一要替换的部分。

结论：**内核与驱动不动，替换的是用户态一整套 AirDrop 实现**。

## 2. v2 进程模型

```
kernel: wonder.ko / wondertap0（PAS）  ← 不变
            │  awdl 数据面 netdev
            ▼
      wonder0 (IBSS vif, IPv6 link-local)   ← 由 awdl-link.sh 创建（详见第 4 节）
            │  标准 socket
            ▼
  com.oshare.airdrop 进程内的 AirdropStack  ← 新增，纯 Java
      ├── MdnsResponder   :5353 组播（_airdrop._tcp）
      ├── AirDropServer   :8770 TLS，请求分派与连接生命周期
      ├── Bplist          二进制 plist 编解码（请求/响应载体）
      ├── Cpio            /Upload 的 cpio 归档流式解包
      ├── Inbox           接收目录：重名、穿越、配额
      └── Identity        自签证书（手写 DER）+ TXT h= 指纹
```

选择纯 Java 的理由：传输面已经是内核 socket，不需要 RAW socket 或 DPDK 式高性能路径；
APK 现有构建链即可承载，避免引入 NDK 与第二套打包流程。吞吐上限由 802.11 侧决定，
不在用户态。

## 3. 线上协议契约（已对 OpenDrop master 源码核对）

**重要修正**：AirDrop 的 HTTP 载荷**不是 OPACK**，而是 Apple 二进制 plist（`bplist00`）——
与 Mosey dex 里唯一的 `bplist` 字面量线索一致。OPACK 只存在于更老系统的
`ReceiverMediaCapabilities` 中，现代实现（含 OpenDrop）已改为把 **JSON 字节**放进 plist 的
data 字段。因此 v2 不需要实现 OPACK，只需要 plist + cpio 两个编解码器。

| 路径 | 请求 | 响应 |
| --- | --- | --- |
| `POST /Discover` | plist `{}` 或 `{SenderRecordData}` | plist `{ReceiverMediaCapabilities, ReceiverComputerName, ReceiverModelName}` |
| `POST /Ask` | plist `{SenderComputerName, SenderModelName, SenderID, BundleID, ConvertMediaFormats, Files[] 或 Items[], SenderRecordData?, FileIcon?}` | plist `{ReceiverComputerName, ReceiverModelName}` |
| `POST /Upload` | `Content-Type: application/x-cpio`、`Transfer-Encoding: chunked`，body 是 cpio 归档（通常 gzip 包裹） | 200 + `Content-Length: 0` + `Connection: close` |

`/Ask` 的 `Files[]` 每项：`FileName`、`FileType`（UTI）、`FileBomPath`（形如 `./名字`）、
`FileIsDirectory`、`ConvertMediaFormats`（整数 0）。
`ReceiverMediaCapabilities` 是 UTF-8 JSON 字节，v2 采用与 OpenDrop 现场一致的
`{"Version": 1}`（Python `json.JSONEncoder` 冒号后带空格）。声明不支持任何编解码器反而会收到
传统格式（JPEG 而非 HEIF），对我们有利。

请求通用头：`User-Agent: AirDrop/1.0`、`Content-Type: application/octet-stream`、
`Accept-Encoding: br, gzip, deflate`、`Connection: keep-alive`；
`/Upload` 另带 `Expect: 100-continue`，服务端必须先回 `100` 再读 body。

`/Upload` 的两条拒绝规则（照 OpenDrop `handle_upload` 对齐，避免对端行为分叉）：

| 条件 | 响应 |
| --- | --- |
| `Content-Type` 不是 `application/x-cpio` | `406`，不读 body，直接关闭 |
| `Transfer-Encoding` 不是 `chunked` | `400`，关闭 |

顺序上先校验 `Content-Type`，再回 `100 Continue`，最后校验 chunked —— 与 OpenDrop 一致。
`/Ask` 的 body 解析失败同样直接 `400` 并关闭连接。

发现面：

| 项 | 值 |
| --- | --- |
| 服务类型 | `_airdrop._tcp.local` |
| 监听端口 | 8770 |
| 发现 | mDNS 组播 `224.0.0.251:5353` / `ff02::fb` |
| TXT 关键字段 | `h=`（证书 SHA-256 十六进制）、`fl=`（能力位）、`rp`/`rpH` |
| 传输安全 | TLS，接收端必须提供与 `h=` 匹配的自签证书 |

`fl=` 能力位含义需要真机取样确认，不预先硬编码。

### 3.1 已落地的编解码

| 类 | 职责 | 自测 |
| --- | --- | --- |
| `v2/DnsMessage` | mDNS 线格式：名字压缩解析、未压缩写出 | `V2MdnsTest` 26 项 |
| `v2/AirDropPeer` | 对端模型，PTR/SRV/TXT/AAAA 的组装与解析 | 同上 |
| `v2/Bplist` | `bplist00` 编解码，含 UTF-16 字符串与扩展长度计数 | `V2PlistTest` 29 项 |
| `v2/Cpio` | newc(`070701`) / odc(`070707`) 流式解包、gzip 自动识别、写出 newc | `V2CpioTest` 37 项 |
| `v2/HttpRequest` | 请求行/头逐字节解析、`Content-Length` 与 chunked 分帧 | `V2HttpTest` 46 项 |
| `v2/HttpResponse` | 响应写出：`100 Continue`、200 + plist、`Content-Length: 0` + close | 同上 |
| `v2/AirDropProtocol` | 三端点的载荷语义：`Ask` 模型、应答与请求构造 | `V2ProtocolTest` 54 项 |
| `v2/Der` | 手写 DER 编码原语（TLV、OID、时间、BIT STRING） | 由 `V2IdentityTest` 间接覆盖 |
| `v2/Identity` | 自签 X.509、`h=` 指纹、TLS 服务端/指纹固定客户端上下文 | `V2IdentityTest` 24 项 |
| `v2/Inbox` | 落盘策略：重名加 ` (n)`、拒绝穿越与符号链接、字节配额 | `V2InboxTest` 31 项 |
| `v2/AirDropServer` | TLS 监听、连接循环、路径分派、错误码 | `V2ServerTest` 26 项 |

`Bplist` 已与 Python `plistlib` 双向交叉验证：plistlib 的规范字节能被 Java 解析，Java 编码出的
`/Ask`、`/Discover` 载荷能被 plistlib 原样读回（含中日文 UTF-16 字段）。

`Identity` 不引入 BouncyCastle，也不用 `KeyStore`：`Der` 直接拼 TBSCertificate，用
`SHA256withRSA` 签名，证书自验证（`X509Certificate.verify`）与篡改拒绝都在测试里断言；
TLS 侧用自定义 `X509ExtendedKeyManager` 出示这一张证书，用自定义 TrustManager 做指纹固定。
这样既没有新依赖，也绕开了 Android 上 `KeyStore` provider 的类型差异。

`V2ServerTest` 是本轮的收口验证：loopback 上真实 TLS 握手，一条连接依次走
`/Discover → /Ask → /Upload`（`Expect: 100-continue` + 两段 chunked + gzip cpio），
断言响应状态、`Connection` 语义、文件落到目录，并覆盖 406 / 400 / 404 / 405 四条拒绝路径。

## 4. M0 链路实测（真机，无 Mosey）

在 wonder.ko 已加载的设备上（`iw phy` 里可见名为 `wonder` 的 wiphy）跑
`awdl-link.sh up`，冷启动约 8 秒后拿到可用链路。三条结论都是实测撞出来的，
写进脚本注释以免后人重踩：

1. **接口名必须是 `wonder0`。** `wonder_start()` 里 `dev_get_by_name(virtual_name)`
   是唯一能拿到虚拟网卡的路径，因为 mac80211 在同一个 `ieee80211_do_open` 里
   **先调 `drv_start` 再调 `drv_add_interface`**，此刻 `wonder->vdev` 还是 NULL。
   用别的名字（例如 `mosey0`）会得到 `ip: RTNETLINK answers: No such device`，
   dmesg 是 `Failed to get virtual device wonder0`。为此 v2 全面改用 `wonder0`，
   `mosey0` 这个 Mosey 遗留名在脚本里已替换（`settings.sh`、`speed-history.sh`、
   `ui-lifecycle.sh` 的统计口径一并跟过来）。
2. **接口类型必须是 ADHOC（IBSS），不能是 NAN。** `wonder_rx_handler()` 只把
   `NL80211_IFTYPE_ADHOC` 的帧交给 mac80211（`ieee80211_rx_ni`）；
   `NAN`/`STATION` 走 `RX_HANDLER_PASS`，802.11 帧会留在物理网卡的协议栈上，
   永远到不了 `wonder0`。`interface_modes` 里 IBSS 也在列，且 `wonder_tx()`
   对 ADHOC 有专门的 probe request 修正。
3. **IBSS 要 `fixed-freq`。** 不加这个标志，mac80211 每 30 秒重扫一次试图和
   同 SSID 的其它 IBSS 合并（`No active IBSS STAs - trying to scan for other
   IBSS networks with same SSID`），每次都会把射频带走。加上之后 40 秒内零重扫，
   carrier 稳定为 1。

另外两点实测细节：

- `wondertap0` 由 vendor init（peach_v2 的 `wlan_hdd_wondertap_init`）创建，
  不是我们建的；它的 MAC 每次会话随机（`SET_IEEE80211_PERM_ADDR` +
  `wonder_force_set_mac`），BSSID 固定为 Apple OUI `00:25:00:`。链路本地地址
  因此每次会话都会变，这对证书指纹寻址的 AirDrop 无影响。
- 地址要 `accept_dad=0`。默认 DAD 会让 link-local 在 `up` 后约 1 秒内处于
  `tentative`，服务端若此时 bind 就会失败；关掉后地址直接 `preferred`。
  内核的 `addrgenmode eui64` 已自动生成我们算出的同一个 EUI-64 地址，
  脚本只在缺失时补加，避免 `replace` 重新触发 DAD。

实测链路状态：

```
awdl-link: ifindex=60 mac=e4:b1:1b:67:78:05
awdl-link: operstate=up carrier=1
  ssid awdl0  type IBSS  channel 149 (5745 MHz), width: 20 MHz
  60: wonder0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP
      inet6 fe80::e4b1:1bff:fe67:7805/64 scope link
      inet6 ff02::fb
```

发送面已用 `ping6 -I wonder0 ff02::1` 验证：`wonder0` 的 `tx_packets` 随包递增，
帧经 `wonder_tx()` → `wondertap0` → 射频。接收面计数为 0 —— 驱动把 BSSID 定在
Apple OUI 且射频钉在 5745，附近没有同频发射源，空口收包要等真 Apple 对端（第 5 节
用组播回环把用户态接收链单独验证掉了）。

## 5. M1 发现实测（真机，无 Mosey）

`MdnsResponder` 在 `wonder0` 上占 `[::]:5353`，只对这一张网卡
`joinGroup(ff02::fb)` —— 组成员关系决定「谁能收到谁」：系统解析器在 `wlan0` 上
即使加入同一组也收不到我们的报文，反之亦然，所以 AirDrop 的发现流量不会漏到
WLAN 上。收发循环只做三件事：别人的 announce 进 `peers()`，问到我们记录的就答，
其余丢弃。发布内容每次发送时从 `Source` 重读，因此换证书或换 link-local 地址后
下一次应答立即生效，不会把启动时的旧值钉死。

真机上用 `tools/mdns-probe.sh` 验证（`javac --release 11` + `d8` + `app_process`，
不装 APK、不接 Service，先证明协议栈本身）。两个实例同时在链路上跑，靠组播回环
互相看见：

```
=== A ===
mdns-probe: identity 37493E11081F9DCD… in 60 ms
mdns-probe: wonder0 [fe80::50ec:e7ff:fed3:f5ec]
mdns-probe: listening on wonder0:5353 as 'OPAirDrop-A'
mdns-probe: peer OPAirDrop-B @ fe80::50ec:e7ff:fed3:f5ec:8770 h=5B4F95D66942…
mdns-probe: done peers=1 errors=0
=== B ===
mdns-probe: peer OPAirDrop-A @ fe80::50ec:e7ff:fed3:f5ec:8770 h=58A51B3DDD2E…
mdns-probe: done peers=1 errors=0
```

两侧 `h=` 指纹互换一致 —— 收包 → 解析 → harvest 整链在真机通了，不需要 Apple
硬件。同时 `wonder0` 的 `tx_packets` 从 28 涨到 64（9 个 mDNS 报文经 `wonder0`
→ `wondertap0` → 射频），零 socket 异常。`rx_packets` 仍为 0：设备级计数器只统计
空口收到的帧，组播回环不进这个数；空口侧仍必须有同频 Apple 对端才有输入，这是 M1
剩下的唯一未验证项。

顺带定下的两处协议细节（都写进了代码注释）：

- **实例名里的点必须转义。** `DnsMessage.writeName` 只按「未转义的 `.`」切标签，
  `\.` 原样留在标签内（DNS-SD 的写法），`AirDropPeer.unescape` 在读侧还原。发布时
  `announce()` 先做 `escapeLabel(instance)`，因此 `Ma.koto's 13` 不会被切成两个
  标签 —— 否则对端看到的设备名会截断。
- **QU 位要回单播并回显 id。** `DnsMessage.Question` 保留 `CLASS_UNICAST` 位；
  查询带 QU 时应答直接发给来源并回显查询 id，其余情况发组播、id 留 0（mDNS 惯例）。
  点对点链路上省掉不必要的组播复制。

## 6. M2 接收实测（真机，无 Mosey）

M2 把 M1 的发现能力接上真正的接收端。四个新类，全部不依赖 Android，因此既能跑在
JVM 单测里，也能用 `app_process` 直接在机器上跑：

| 类 | 职责 |
| --- | --- |
| `IdentityStore` | 证书与私钥落盘，损坏时自愈 |
| `ReceiveHandler` | `/Discover`、`/Ask`、`/Upload` 的应答与落盘决策 |
| `AirDropLink` | 按依赖顺序装配「身份 → TLS → mDNS」，失败回滚 |
| `AirDropRuntime`（`com.oshare.airdrop`） | 把 `AirDropLink` 挂到 Service 生命周期上 |

### 6.1 身份必须落盘

iOS 侧把我们钉在证书指纹上，并按设备名缓存。每次启动重新生成密钥的后果不是「连不上」，
而是**每次都得在对端重新确认一台「新设备」**——用户看到的是反复弹出的陌生设备提示。
所以私钥（PKCS#8）与证书（DER）写到 `files/airdrop/identity.{key,der}`，目录 `0700`、
私钥 `0600`，写临时文件再 `ATOMIC_MOVE`，避免掉电留下半个文件。

`load()` 额外做一次密钥/证书配对校验：`Identity.load` 自己不查这件事，不匹配只会在
握手时炸，那时已经很难定位到「证书文件被换了」这个原因。任何
`GeneralSecurityException | IOException | RuntimeException` 都当作「没有身份」处理并
重建，而不是让启动失败——一份截断的文件不该让接收端彻底不可用。

真机验证：`install -r` 覆盖安装、`force-stop` 后重起，日志里的指纹始终是
`E79A4B408A47…`，说明复用的确实是盘上那一份。

### 6.2 装配顺序与回滚

`AirDropLink.start()` 的次序是刻意排的：接口存在性 → 身份 → `Inbox` + `ReceiveHandler`
→ `AirDropServer`（8770）→ `MdnsResponder`（5353）。mDNS 放最后，因为**先广播再能连**
是 AirDrop 最坏的失败模式：对端在列表里看得见我们，点下去却连不上。所以 mDNS 起失败时
要把已经起好的 server 关掉再抛异常，宁可完全不可见。

接口缺失的报错直接指向修复动作（`bring the awdl link up first (awdl-link.sh up)`），
因为这是最常见的一种失败。

TXT 每次会话从 `Source` 重读（`h` 取当前证书指纹、`fl` 取能力位），换证书后下一次应答
立即生效，不会把启动时的快照钉死。

### 6.3 真机自检：`StackProbe`

`tools/stack-probe.sh` 在设备上跑**完整**的 `AirDropLink`（身份目录
`/data/local/tmp/stack-probe/{identity,inbox}`），先自检广播报文，再对自己做一次
loopback 传输。loopback 之所以有意义：JVM 与手机之间真正不同的三件事——TLS provider
（SunJSSE vs Conscrypt）、文件系统、绑在 `wonder0` 上的组播套接字——它一次全过一遍，
只有射频没过。

```
stack-probe: started host=opairdrop-probe.local h=4B9FE972A22F5950… mdns=:5353 tls=:8770
stack-probe: startup 12 ms
stack-probe: announce 327 B: ptr=1 srv=1 txt=1 aaaa=1
stack-probe: txt {h=4B9FE972A22F5950D919C6F1CE34438517BFA33334A9BE3885000E22DDFC485C, fl=9e1b}
stack-probe: aaaa fe80::8430:7dff:fe9d:b136
stack-probe: handshake ok peer=4B9FE972A22F5950… cipher=TLS_AES_128_GCM_SHA256
stack-probe: discover 200 body=165 B
stack-probe: ask from probe: probe-1794247100816.txt
stack-probe: ask 200
stack-probe: received probe-1794247100816.txt 22 B
stack-probe: upload 200
stack-probe: landed /data/local/tmp/stack-probe/inbox/probe-1794247100816.txt 22 B -> hello from stack-probe
stack-probe: closed errors=0
```

`startup 12 ms` 是第二次启动（身份已在盘上）；首次要生成 RSA，明显更慢，这也是它必须
离开 UI 线程的原因。`cipher=TLS_AES_128_GCM_SHA256` 确认手机上走的是 Conscrypt 的
TLS 1.3。

### 6.4 装上 APK 后再测一遍：`LinkClientProbe`

`StackProbe` 证明的是「这套代码能跑」，但用户手机上跑的是 App 进程：Service、
目录、设备名都是 App 的事。`tools/link-client.sh` 因此反过来——**它自己什么都不起**，
只当一台 iPhone：组播问 `_airdrop._tcp.local`，用应答里的 `h=` 钉住 TLS，然后走完三段式。
这样每个答案都来自 `com.oshare.airdrop` 自己的进程。

```
client-probe: asking ff02::fb%wonder0:5353
client-probe: answer 267 B ...: ptr=1 srv=1 txt=1 aaaa=1
client-probe: answered by "Air" host=air.local port=8770
client-probe: advertised h=E79A4B408A47CA10… fl=9e1b
client-probe: handshake ok served=E79A4B408A47CA10… cipher=TLS_AES_128_GCM_SHA256 pin held
client-probe: discover 200 keys=[ReceiverComputerName, ReceiverModelName, ReceiverMediaCapabilities] name=Air model=PJZ110
client-probe: ask 200 name=Air
client-probe: upload 200
client-probe: done — the app answered every step
```

文件落在 `/storage/emulated/0/Android/data/com.oshare.airdrop/files/incoming/`，内容
逐字节一致（23 B），App 侧同时打出 `offer from probe` 与 `received …` 两行日志——
协议、落盘、回调三段在真实安装态下全部接通。

探针的第一版是**单播**问自己的 link-local 地址，超时失败。原因是这台设备上 5353 由三方
共享（系统 `mdnsd`、Mosey 时代的残留、以及我们的 App），都靠 `SO_REUSEADDR` 复用；
对一个**单播**报文，内核在多个匹配套接字里挑一个，并不保证挑中我们的。改成组播后
`ff02::fb` 会投递给所有加入了组的套接字，App 不可能被跳过。这条也解释了 M1 里
「组成员关系决定谁能收到谁」为什么是必要的而不是优化。

### 6.5 后台存活：`START_STICKY` 不够

第一版把接收端做成普通 started service，`onStartCommand` 返回 `START_STICKY`。结果：
Activity 关掉约 90 秒后，

```
W ActivityManager: Stopping service due to app idle: u0a411 -1m29s929ms com.oshare.airdrop/.AirDropRuntime
```

`START_STICKY` 管的是「进程被杀后重建」，这里进程是被 idle 回收主动停掉服务，标记无效。
而「手机放桌上、拿 iPhone 过来传」正是这个功能唯一的使用场景，所以改成前台服务：

- 类型 `connectedDevice`（`0x10`）而不是 `dataSync`：后者在 Android 15+ 有每日时限，
  一个常驻接收端不适合；
- `connectedDevice` 在运行时要求一组配套权限之一，用 `CHANGE_NETWORK_STATE` 与
  `CHANGE_WIFI_MULTICAST_STATE`（都是 normal 级、自动授予，且是这个 App 真会做的事）；
- `POST_NOTIFICATIONS` 在 Activity 里请求，但**排在启动服务之后**：前台服务没有通知
  权限照样运行，而权限对话框是用户可能放着几小时不管的模态，接收端不该排在它后面。

启动失败会每 15 秒重试一次：最常见的原因是 `wonder0` 还不存在——模块在开机时才把链路
拉起来，可能晚于 App 被打开。重试让接收端自愈，而不是一直躺到下次启动。

## 7. M3 发送实测（真机，无 Apple 对端）

### 7.1 出站的半条链路是空的

M2 结束时 v2 包里只有请求**解析器**（`HttpRequest`）和应答器，没有任何发请求的代码。
接收端能答，发送端不能问。补上的是三个类：

- `HttpCall`：唯一的出站 HTTP/1.1 客户端，跑在已建立的 TLS socket 上。支持
  `Content-Length` 与 chunked、读 100-continue、keep-alive 复用（省掉第二次 RSA 握手）、
  见到 `Connection: close` 就重连。
- `Outgoing`：描述「一个待发文件」——显示名、UTI、归档内路径（`bomPath`）、长度、按次
  打开的流。`FileType` 从 MIME 归一化成 UTI（`image/jpeg` → `public.jpeg`），因为
  `/Ask` 要的是 UTI；未知类型原样透传，宁可类型不准也不编一个。
- `AirDropClient`：`/Discover` → `/Ask` → chunked `/Upload`，与 `AirDropLink` 互为镜像
  （那个对象应答三个请求，这个对象发出三个请求）。

指纹钉死用 `Identity.clientContext(peer.certHash)`，证书来自 mDNS TXT 的 `h=`。对端证书
是自签的、SRV 主机名也可能对不上证书里的名字，所以**指纹即身份**，故意不做主机名校验——
信任强度和发现通道一致。`SenderID` 由 `UUID.nameUUIDFromBytes("oshare-airdrop:" + certHash)`
派生，不占存储且与证书同寿命。

`/Discover` 失败不致命：能答 `/Ask` 的对端已经愿意收，discover 只提供 UI 用的显示名。

### 7.2 沉默不等于拒绝

`Expect: 100-continue` 的语义是双向的，实现在 `HttpCall.readInterim()`：

```
发送头 → 等应答（3 秒）
  ├─ 收到 >= 200（如 406）：对端在消费任何字节前就拒了，照办，body 一个字节都不发
  ├─ 收到 100：发 body
  └─ 超时（SocketTimeoutException）：没人吭声，仍然发 body
```

第三条容易写错。超时在这里不是失败，是「对端不打算先说」——有些接收端要见到字节才开始
读。把它当拒绝处理，表现是不明原因的发不出去。

### 7.3 cpio 必须能流式写

原 `Cpio.write(List<Entry>)` 把整个归档拼在内存里。测试够用，发一个 2 GB 视频必 OOM。
新增 `writeMember(OutputStream, name, mode, size, InputStream, inode)` /
`writeTrailer()` / `memberLength()` / `trailerLength()`，逐成员「头 + 数据 + 补齐」写出去，
原签名与字节输出不变（`V2CpioTest` 37 项 + `V2SendTest` 长度断言双重锁定）。

按声明长度读出**正好** size 字节是硬要求：cpio 靠 `size` 字段定位下一个头，少写或多写都让
接收端把文件内容当成头解析，报 `lost cpio framing`。

### 7.4 离线端到端：`V2SendTest`

不模拟协议，直接把真组件串起来：真 `AirDropServer` + 真 `ReceiveHandler` + 真 `Inbox`，
让真 `AirDropClient` 打过去。181 项断言，覆盖钉死握手 → 三个请求 → chunked 上传 → 流式
cpio → 解档落盘。刻意包含两个真实边界：0 字节成员（cpio 最小合法成员，手写 writer 最常
写错的地方）与 3 MB + 17 字节（超过 chunk 与进度步长）。另有一条负面用例：全 0 指纹必须
连不上、且收件箱必须仍为空。

全量：**11 组 579 项**（`V2SendTest` 181 + 既有 398，零回归）。

### 7.5 真机：`SendProbe` 跑真客户端

`tools/send-probe.sh` 把 `AirDropClient` 本身 dex 进设备跑（不是复述协议），打向本机
App 的接收端，跑完再把收件箱读回来逐个比对长度与 SHA-256：

```
send-probe: peer "Air" host=air.local port=8770
send-probe: advertised h=E79A4B408A47CA10… fl=9e1b addresses=[fe80::8430:7dff:fe9d:b136]
send-probe: sender name=send-probe h=47E1E9B0B42C9377…
send-probe: archive 1049204 B over 3 members:
send-probe:   send-probe-…txt (public.plain-text, 36 B)
send-probe:   send-probe-…bin (application/octet-stream, 1048613 B)
send-probe:   send-probe-…-empty.txt (public.plain-text, 0 B)
send-probe: stage DISCOVERING / ASKING / UPLOADING
send-probe:   25% 262460/1049204 B … 75% 1048892/1049204 B
send-probe: stage DONE
send-probe: done 3 files / 1049204 B in 181 ms, receiver="Air" model=PJZ110
send-probe:   …txt: 36 B sha=423A2B99… ok
send-probe:   …bin: 1048613 B sha=4C3877D9… ok
send-probe:   …-empty.txt: 0 B sha=E3B0C442… ok
send-probe: VERDICT ok — every member landed byte for byte
```

shell 侧用 `sha256sum` 独立复核，三个摘要与探针自报一致（不是同一份代码自证）。

发送耗时 181 ms / 1 MB 走的是 loopback，不代表 AWDL 空口速率；这里验的是**正确性**，
不是吞吐。

### 7.6 还缺 UI 入口

manifest 目前只有 `MAIN`，没有 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 声明，也没有把
用户选中的文件转成 `List<Outgoing>` 的选择器。协议层与传输层已就绪，缺的是接线。

## 8. 分阶段

| 阶段 | 目标 | 验收 |
| --- | --- | --- |
| M0 链路 | 无 Mosey 时创建 `wonder0`、配 IPv6 LL、能收发 mDNS 查询 | **已通过**：`wonder0` UP/LOWER_UP、carrier=1、信道 149、`fe80::…`+`ff02::fb`、TX 计数递增；RX 待同频对端 |
| M1 发现 | 广播自身 `_airdrop._tcp`，能看到 iPhone/Mac 条目并解析出名字与 `h=` | **自检通过**：`MdnsResponder` 在 `wonder0:5353` 收发正常，两实例互见、`h=` 互验（第 5 节）；待真 iPhone 条目 |
| M2 接收 | TLS `/Discover` + `/Ask` + `/Upload`（plist + cpio）落到下载目录 | **链路全通**：`V2WiringTest` 61 项离线 + `StackProbe` 真机自检 + `LinkClientProbe` 对已安装 App 三段式全 200、文件落盘一致（第 6 节）；待真 iPhone |
| M3 发送 | `ACTION_SEND` → 对端 `/Ask` 交互 | **协议层打通**：`V2SendTest` 181 项离线 + `SendProbe` 真机跑真客户端、3 成员 1049204 B 逐字节一致（第 7 节）；**缺 UI 入口**，待真 iPhone/Mac |
| M4 替换 | 移除 mosey APK 依赖、LSPosed 作用域只留 `com.coloros.oshare` | 卸载 Mosey 后功能完整 |

M0/M1 不依赖 TLS，可先跑通；M2 是价值拐点。

## 9. 已知不确定项

1. **对端是否接受 IBSS 形态的数据帧**。我们已确定 wonder 侧只有 ADHOC 能收到帧
   （见第 4 节），但 mac80211 会按 IBSS 语义收发：3 地址、ToDS/FromDS 均 0、组播发往
   `33:33:`。AWDL 真机的数据帧格式与此同构，理论上兼容；若 M2 出现在对端可见但收不到
   数据，退路是改用 `MONITOR` 类型走 `wonder_rx_monitor_handler`，在用户态自己拆
   802.11。Mosey 当初用哪种类型仍未可知（其 nl80211 调用未抓到）。
2. **频道策略**：内核侧固定 5745 MHz（信道 149），vendor 命令 `WONDER_VEN_ATTR_CHANNEL_FREQ`
   由 wondertap_init 在 `hw->start` 时下发，实测已生效（`iw` 报 5745 MHz）。
   若需运行时改信道，得走 vendor 命令而不是 `iw dev set channel`。
3. **对端校验**：Apple 侧对证书链、ALPN、TXT `h=` 的校验严格程度未知，M2 真机验证。
4. **`fl=` 位定义**：AirDrop 能力位随系统版本变化，需实机取样。
5. **cpio 变体**：解析器按 POSIX 处理两种变体（newc 4 字节对齐、odc 名字补偶）。odc 的
   名字补齐字节在各实现间偶有出入；若真机归档报 `lost cpio framing`，错误信息会指出位置，
   调整点集中在 `Cpio.extract` 的 `namePad` 一行。
6. **TXT 键名分歧**：OpenDrop 的 `get_properties()` 只广播 `flags`，而 Apple 广播
   `h=`（证书 SHA-256）/`fl=`/`rp`/`rpH`。我们按 Apple 的键名实现，但若对端真的只认
   `flags`，表现为「列表里能看到设备、点了却连不上」。真机取样时优先确认这一点。
7. **TLS 版本与 ALPN**：OpenDrop 的 `SSLContext` 来自 `config.get_ssl_context()`，该文件
   尚未取到，因此 TLS 最低版本、是否设 ALPN、密码套件顺序都未核对。当前实现用 JDK 默认
   协议集（`TLSv1.3`/`TLSv1.2` 协商），若握手被对端拒绝，先查这里。

## 10. 构建

```sh
# APK（在新工作副本内）
cd oshare && "$GRADLE" :app:assembleRelease --offline

# 纯逻辑单测（无需设备，11 组 579 项）
sh tests/run-v2.sh

# M1 真机探针（需先 awdl-link.sh up）
sh tools/mdns-probe.sh wonder0 40 OPAirDrop

# M2 真机自检：整套接收端跑在设备上，对自己 loopback 传一次
sh tools/stack-probe.sh wonder0 30

# M2 对已安装的 App 做客户端视角验证（App 需已启动过）
sh tools/link-client.sh wonder0

# M3 真机发送：跑真 AirDropClient，跑完读回收件箱比摘要
sh tools/send-probe.sh wonder0
```

调试期用属性覆盖版本号，免得和已装版本分不清（`module.prop` 不动，那是模块包身份）：

```sh
"$GRADLE" :app:assembleRelease --offline \
    -PopairshareVersionCode=102003 -PopairshareVersionName=1.2.3-v2
```

本机 `gradlew` 在 Git Bash 下 classpath 解析异常，使用
`D:/Downloads/project/tools/gradle-8.13/bin/gradle` 直接调用。
