# OPAirDrop 自研任务书

> 这份文件是本项目唯一的长期记忆。每一轮结束都要更新第 9 节（进度）与第 10 节（下一步），
> 新踩的坑进第 6 节，新量到的数字进第 11 节。**2026-11-14 凌晨它被我的一次脚本事故清空过，
> 本版是重建版**：结构照旧，事实以当前仓库、记忆日志与真机实测为准；个别早期细节若与当初
> 措辞不同，以这里的数字与代码为准。

---

## 1. 目标与边界

在 `D:\Downloads\project\repo-OPAirDrop` 里，对 `repo-v2` / `legacy/` 那套 Java 实现做**全盘
Rust 重构**，参考 [t4t5/owly](https://github.com/t4t5/owly) 的 Rust 化路线。要求：

1. **不再依赖 KernelSU 模块形态**，改由「LSPosed 模块 + 一次 root 动作」承担；
2. 起点是 `repo-v2` 的源码，先复制过来再做减法；
3. **剔除所有 QuickShare / Mosey 相关源码**，重写一套干净实现；
4. 优化全部代码，去掉重复与多余；
5. 每一个构思、问题、解决方法都记在这里，避免下次忘掉。

「可用」的定义是三条同时成立：**能被 iPhone 找到、能收下它发的文件、能把文件发给它**。
性能不是目标（见 5.3 的定论）。

---

## 2. 起点：两条互斥的技术路线

`repo-v2` 里同时存在两套东西，必须先分清，否则重构会两线作战：

| | 路线 A：Mosey / QuickShare | 路线 B：自研 AirDrop 栈 |
| --- | --- | --- |
| 传输面 | 谷歌 Nearby / QuickShare，经 GMS | Apple AirDrop 协议（HTTP + bplist + cpio） |
| 链路 | Wi-Fi Direct / BLE | AWDL（`wonder0`） |
| 谁在做 | 一加互传（`com.coloros.oshare`）本身 | 我们 |
| 结论 | **全部剔除**（第 8 节） | 本项目的全部内容 |

所以「一加互传」在本项目里只有两个用途：**它的接收开关是我们跟随的信号**、**它的两个面板是
我们放入口的地方**。传输一行都不走它。

---

## 3. Rust 架构

七个 crate，依赖方向单向，越靠下越纯：

| crate | 行数 | 职责 | 测试 |
| --- | --- | --- | --- |
| `bplist` | 863 | Apple 二进制 plist（`bplist00`）读写 | 16 |
| `cpio` | 1422 | cpio `odc`/`newc` 读写 + DVZip 块编解码 + gzip 识别 | 28 |
| `mdns` | 957 | DNS 报文编解码（名字压缩、`\.` 转义、TXT） | 18 |
| `http` | 1985 | 请求/应答双向流式解析、`Content-Length` 与 chunked 分帧、`100-continue` | 62 |
| `awdl` | 4477 | AWDL 数据面：16 槽调度、PSF/MIF、选举、对端信道表、帧编解码、`/dev/tun` 与 `AF_PACKET`、`bin/awdld` | 83 |
| `airdrop` | 3542 | 三端点语义、客户端、服务端、收件箱、loopback 测试 | 79 |
| `settings` | 845 | 参数表：常量 + 区间 + 文案三合一，界面从表里画 | 17 |
| `ffi` | 4199 | C ABI + JNI，Android 唯一入口 | 38 |

合计 `cargo test --workspace` **260 passed / 0 failed**，`clippy --all-targets` 零 warning。

三条硬规矩：

- **协议知识只在 Rust 里**：Java 侧没有一处 bplist / cpio / HTTP 字节操作；
- **跨边界只传字节与标量**：不传 `Map`、不传 `String[]`（512 条本地引用就会炸）；
- **失败要带原因**：失败时把人类可读的原因放进同一个 out-buffer，状态码只决定异常类型。

---

## 4. 怎么和苹果设备通信（核心章节）

### 4.1 全链路顺序

```
① mDNS 发现        _airdrop._tcp.local 的 PTR/SRV/TXT   （主动发查询，或被动收广播）
② TCP 连接         peer:port（SRV 里的端口，通常 8770）
③ TLS 握手         对端用自签证书；证书里的名字就是它自报的身份
④ POST /Discover   问「你收不收文件」，答 {名字, 能力, IsAirDropable}
⑤ POST /Ask        报价：发什么、多少件；对端据此弹出「要不要接收」
⑥ POST /Upload     发送 cpio 归档（DVZip 块序列 + chunked 传输）
⑦ 连接关闭         一次会话一条连接，归档是最后一句话
```

**接收方向**是我们做服务端：①③不变，②反过来监听 8770，④⑤⑥由我们应答。

### 4.2 三个端点（线上契约，不可动）

| 请求 | 请求体 | 应答体 |
| --- | --- | --- |
| `POST /Discover` | plist `{SenderComputerName, SenderModelName, SenderID}` | plist `{ReceiverComputerName, ReceiverMediaCapabilities, IsAirDropable}` |
| `POST /Ask` | plist 九键：三个 Sender 字段 + `BundleID`、`ConvertMediaFormats`、`Files[]`（或 `Items[]`）、可选 `SenderRecordData`/`FileIcon` | plist `{ReceiverComputerName, ReceiverModelName}` |
| `POST /Upload` | `Content-Type: application/x-cpio`（我们发 `x-dvzip`），`Transfer-Encoding: chunked`，体是 cpio（常被 gzip 或 DVZip 包一层） | `200` + `Content-Length: 0` + `Connection: close` |

`Files[]` 每项：`FileName`、`FileType`（Apple UTI，如 `public.jpeg`）、`FileBomPath`
（`./名字`）、`FileIsDirectory`、`ConvertMediaFormats`（整数 0）。

### 4.3 几个反直觉的事实

1. **载荷不是 OPACK，是 Apple 二进制 plist（`bplist00`）。** OPACK 只出现在更老系统的
   `ReceiverMediaCapabilities` 里；现代实现把 **JSON 字节**塞进 plist 的 data 字段
   （`{"Version": 1}`）。所以永远不需要实现 OPACK。
2. **`/Discover` 必须排在 `/Ask` 之前。** 它是唯一一个「对面不收」会**先于**「对面弹确认框」
   到达的请求。合成一个请求的代价是：锁着屏的 iPhone 会先弹出确认，然后传输在后面失败。
3. **`/Discover` 的应答不依赖请求体**，所以我们能在读完 body 之前就答完——这也是「发送端
   卡住不会拖住接收端」的原因。
4. **对端名字有两个来源，只有一个是给人看的。** mDNS 的实例名是随机轮换的哈希、主机名是
   随机 UUID；真名在 `/Discover` 的 `SenderComputerName` 里。所以面板里**先显示什么**是一个
   协议问题，不是一个 UI 问题：名字要等连上才有。
5. **UTI 影响对端图标与预览**，猜错只是图标错；`public.content` 是「不认识」的诚实答案。

### 4.4 拒绝与超时

- 拒收：`/Ask` 回 `503` 并断连（照 OpenDrop 对齐；**iPhone 在对面拒绝时收到什么没有抓包
  证据，属未定论**，日后改一行）。
- 我们的接收端有两种答法：**先答后读**（默认，抗卡死）与**读完再答**（要问人时）。后者是
  `answers_ask_early() == false`，只在真的会弹确认框时才付这个代价。
- `/Upload` 的 `406` 必须在 `100 Continue` **之前**发；先说「请继续」再拒绝体，对端会白传。

### 4.5 证书与信任（当前缺口）

AirDrop 用自签证书，TXT 记录里有指纹（我们发布的名字是 `h`，见 `Responder.txt()`）。**我们目前接受
任何证书**，把「是不是那台设备」交给对面手机上的一次人工确认。按指纹校验还没做；写在这里是为了
不被当成已完成。

分工要说清：**接收端不需要校验对方的证书**——它连证书都不索取（`setNeedClientAuth(false)`，理由见
`Listener` 类注释：TLS 1.3 下被索要证书的 iPhone 不是出示，而是直接放弃）。所以这一节讲的缺口
只在**发送端**：我们发起连接时要按对方公告的指纹拒绝不匹配的手握。

### 4.6 mDNS 的四条 legacy 约定（都踩过，别再推导）

这一节的存在理由是：这四条每一条都能靠「讲道理」得出**错误**答案，而且得出之后代码会安静地
不对——不报错，只是对面看不见我们。

1. **`unicast` 只看问题里的 QU 位，不看查询的源端口。** 应答来自临时端口（比如 54270）时，
   直觉会认为该单播回给来源；legacy 不是这样做的：

   ```
   ask.unicast |= question.unicast;                                        // :742
   int id = ask.unicast ? message.id() : 0;                                // :748
   transmit(reply.packet, reply.unicast ? incoming.getSocketAddress() : null);  // :688 / :839
   ```

   只有带 QU 的问题才回给来源，其余一律组播。**别把 `:603–608` 那段注释当反例**——那段讲的是
   我们**自己发问**（`Peers` 浏览侧）该带 QU，因为 bridge 只转发单播帧。

2. **实例名里的转义反斜杠要原样上 wire。** 名字里的空格和点在 mDNS 里写作 `\` 转义；如果解析时
   剥掉、发送时忘了加回，我们公告的名字与自己应答的名字就不是同一个字符串，peer 会看到两个设备。

3. **`Record::target()` 只对 SRV 有意义。** 在 PTR/TXT/AAAA 上取它拿到的是「碰巧位于同一偏移
   的那几个字节」，不是名字。用它统一处理四条记录，PTR 会指到一个不存在的实例。

4. **legacy 的 `close()` 撤回是死代码。** `AirDropLink.close()` 调 `advertising.withdraw(current)`，
   而 `withdraw()` 第一句是 `oldInstance.equals(instance) → return true`——名字永远相等，所以永远
   直接返回。这不是抄漏，是那份代码本来就没在关的时候撤回。我们要撤回（崩溃半写留下的旧名字会
   在别人缓存里活 75 分钟），就得自己写 `withdraw(held, name)`，不能顺着这条调用链走。

---

## 5. 链路层事实（真机实测，不要重新发明）

### 5.1 三定律

1. **接口名必须是 `wonder0`**：`wonder_start()` 里 `dev_get_by_name(virtual_name)` 是唯一
   能拿到虚拟网卡的路径（mac80211 先 `drv_start` 后 `drv_add_interface`，此刻 `wonder->vdev`
   还是 NULL）。
2. **接口类型必须 `ADHOC`（IBSS），不能 `NAN`**：`wonder_rx_handler()` 只把 ADHOC 的帧交给
   mac80211，`NAN`/`STATION` 走 `RX_HANDLER_PASS`，帧留在物理网卡上。
3. **IBSS 必须 `fixed-freq`**：不加的话每 30 秒重扫一次试图合并同 SSID 的 IBSS，每次把射频
   带走；加上之后 carrier 稳定为 1。

另两条：`accept_dad=0`（默认 DAD 会让 link-local 在 `up` 后约 1 秒处于 tentative，服务端
此时 bind 会失败）；`wondertap0` 由 vendor init 创建，MAC 每会话随机。

### 5.2 内核侧

- `wonder.ko` **不在** `/vendor/lib/modules/`，由模块 payload 提供，加载参数形如
  `insmod ... physical_name=wondertap0 virtual_name=wonder0`。
- 驱动交换必须做：stock `qca_cld3_peach_v2.ko` 先 `rmmod`，再装
  `opairshare_wonder_provider.ko` → 打过补丁的 `qca_cld3_peach_v2.ko`（30+ 个 `passthru_*`
  参数）→ `mac80211.ko` → `wonder.ko`。**这一步只能 root 做，LSPosed 做不到。**

### 5.3 吞吐上限的定论（影响速度的参数全在这）

| 口径 | 数值 |
| --- | --- |
| iPhone→本机 | 80.0 / 82.4 / 81.6 / 88.3 KB/s |
| 本机→iPhone | 1.06–1.13 MB/s（15.3 槽/s × 10.9 帧/槽 × 6796 B） |
| 对端通告的占空比 | 5/16 槽 = **31.2%**（超帧 1048.6 ms） |
| 窗口内帧率 | 198–206 fps（4.97 ms/帧，窗口利用率 98%） |
| 模型校验 | 206 fps × 1386 B × 0.319 = 87.2 KB/s，实测 88.3（误差 1.3%） |

**结论：接收方向的上限由 iPhone 的 AWDL 调度决定，不在这份代码里。** 逐条试过的参数：

| 参数 | 结果 |
| --- | --- |
| `idle_ms` 3→1 | 只买到 **+5.6%** |
| `rcvbuf` | 在噪声内 |
| `RELAY_COPIES` | 在噪声内 |
| `SACK` | 在噪声内 |
| 抬 metric 抢 master | **无效**：实测 iPhone 不认我们的选举（其 MIF 仍是 `master=self/h0/m520`） |
| WMI 提交开销 | 均值 **6.86 ms/帧**，`wmi_prepare` 占 99.9%，`call(send)` 仅 **67 µs**；串行上限 146 帧/s |

所以：**再往上必须动占空比，而那是对端的调度决定权**。Rust 版的目标是「正确的实现」，
任何以吞吐为 KPI 的重写都会重演 6.5 节的循环。

### 5.4 复测（2026-11-15 15:40）：24.8 ms 一批、2.04 帧、批内 1.69 ms/帧

整批 **49.3 MB / 487.4 s = 98.8 KB/s**（`cap-w1.txt`，tun 侧逐帧）。带 `wait=` 的 `awdld`
同机运行，四个口径一起给：

| 口径 | 实测 |
| --- | --- |
| 我方 ACK 延迟（最后一段到达 → ACK 写进 tun） | 中位 **0.16 ms**，p90 1.26 ms |
| 我方 ACK 空口等待（`wait=`：入队 → 首份上空气） | 中位 **<0.5 ms**（82% 的包），均值 4.5 ms；尾巴 15.9% ≥8 ms |
| 我方 ACK 写进 tun → iPhone 下一批到达 | 中位 **22.25 ms**（p10 0.53 / p90 26.93） |
| 批次数 / 每批帧数 | 17498 批，**2.04 帧**（97.5% 恰好 2 × 1386 B） |
| 批周期 | 中位 **24.82 ms** |
| 批内两帧间隔 | 均值 **1.69 ms** |
| 我方通告窗口 | 上限 4.19 MB（65535 × 64），中位空闲 1.26 MB |
| 重传 / 丢包 / ACK 窗口收缩 | 0.02% / 0 |

**判据**：24.8 ms 的批周期里我方只占 **0.16 ms（0.6%）**。从我们的 ACK 进 tun 起算 22.25 ms，
扣掉空口等待 4.5 ms，iPhone 侧还剩 ~17.5 ms ≈ **1.07 个超帧（16.384 ms）**——它每个超帧
只给自己一次发送机会，一次 2 帧。

**1.69 ms 发 1386 B = 6.6 Mbps（按空口时间算）**；而反方向实测 1.06 MB/s = 36 Mbps、~11 帧/槽。
同一条链路、同一对设备：去程 36 Mbps，回程 6.6 Mbps。所以「比 iPhone 互传慢 200 倍」既不是带宽、
也不是丢包、窗口或我们的 ACK——是**对端在回程上每超帧 2 帧、每帧 6.6 Mbps** 这个发送决定。

**待定一个证据**：1.69 ms 可能是空口时间（→ 6.6 Mbps），也可能是 iPhone 自己的帧间节拍。
分辨只需空口抓包里那两帧的 radiotap MCS/rate 字段（`wondertap0` 在设备上，`tools/tap.sh` 现成）。

### 5.5 进度条差 15% 是缓冲，不是丢数据

现象（2026-11-15）：iPhone 进度 100% 时本机还在 85%。iPhone 的进度是「写进 socket 的字节」，
本机的是「解密后落盘的字节」，中间隔着 iPhone 发送缓冲 + 我方通告窗口 + 在途。
49.3 MB × 15% = 7.4 MB ÷ 98.8 KB/s ≈ **75 秒尾巴**，正好是「到 100% 之后还要等一分多钟」。
这条链路的 BDP 只有 25 ms × 98.8 KB/s ≈ **2.5 KB**，窗口开到 4.19 MB 是它的 1700 倍——买不到
吞吐，只买到进度滞后。**实验**：`SO_RCVBUF` 收到 512 KB（仍是 BDP 的 200 倍）后再传一批，
若滞后按比例缩小，机制即坐实；窗口收缩对吞吐无影响（ACK 从不因窗口而停）。

### 5.6 自测环路（`adb forward` → 8770）的上限，以及「1.8 MB/s」是怎么来的

自测不经过空口：`ask.py` → PC `127.0.0.1:8770` →（`adb forward`）→ 设备 `127.0.0.1:8770` → `Listener`。
三段各自的上限（同一台设备、同一条 forward、同一份 app）：

| 环节 | 实测 | 判据 |
| --- | --- | --- |
| `adb push` 48 MB | **660.3 MB/s** | USB 物理通道不是瓶颈 |
| `blast.py plain` → 设备 `nc`（64 KiB / 4 MiB sndbuf） | **186.5 / 192.4 MB/s** | `adb forward` 不是瓶颈 |
| `blast.py tls` → 本机 `tlsd.py` | **146.0 MB/s**（cpu 0.41 s） | Python TLS、`sendall` 循环不是瓶颈 |
| `askfast.py` 24 MB，循环内零采样 | **25.6 MB/s**（0.94 s，PC cpu 0.05 s = 5%） | 经 app 自身的上限 |
| `askfast.py` 24 MB，每 4 MiB 采一次 | 11.6 MB/s（2.07 s） | 6 次采样 ≈ +1.1 s |
| `askfast.py` 24 MB，每 512 KiB 采一次（= `ask.py` 的节奏） | **2.3 MB/s**（10.51 s） | 48 次采样 ≈ +9.6 s |
| 设备 CPU / `wchar` / `recv-q`（快速传输下重采） | ≤11% / 25.3 MB 正常 / 峰值 359 KB、84% 为 0 | 手机不在忙，读侧无积压 |

**根因：`ask.py` 的进度行每 512 KiB 调一次 `drive.describe(drive.ours("transfer"))`，它内部是
`adb shell dumpsys notification`——单次 340–364 ms、1.1 MB 输出。** 24 MB = 48 次 ≈ 9.6 s，与
`10.51 − 0.94 = 9.57 s` 的差额精确闭合。更早那个 1.77 MB/s 还要再慢一截，是因为当时
`ratecheck.sh` 每秒在同一条 adbd 上采 `/proc`，两笔采样费叠在一起。

**两条方法学（都是这一轮撞出来的）**：

- **采样器不能待在被测链路的通道上。** adbd 是单通道，`dumpsys` 既吃 CPU（1.1 MB 文本要 Python 解析）
  又是一个完整往返。要采就采到设备上跑、写设备文件、事后拉回（`sampler.sh` / `wsamp.sh`）。
- **`recv-q` 恒 0 什么也证明不了。** 它只说明「没有滞留」：既不能推出「发送端没在发」，也不能推出
  「读侧很快」。在 24.6 MB/s 的真快传输里它同样 84% 为 0。这一项只能用来排除「读侧堵塞」。

**线程形态（快速传输下重采，56 轮 / 1349 行）**：`sk_wait_data` 一次都没出现；`opairdrop-liste` 在
`do_sys_poll`（21）与 `futex_wait_queue`（17）之间来回，`OShare-BridgePe` 停在 `wait_woken`。
这个 app 的 socket 等待走 Java 的 poll 路径（`sock_poll`），所以形态是 `do_sys_poll` 而不是
`sk_wait_data`——早先那条「传输中没有任何线程在等数据」的异常，是**判据选错了字段**，不是链路空转。

**结论：自测环路上限 25.6 MB/s，是真实接收（91.6–98.8 KB/s）的约 260 倍。它不构成产品瓶颈，也不能
用来推断接收端能力；真实接收速率仍按 5.3/5.4 归因于 iPhone 的 AWDL 占空比。** 探针在 `tools-probe/`
（`askfast.py` `runfast.sh` `blast.py` `tlsd.py` `sampler.sh` `fastq.sh` `wsamp.sh` `fastw.sh`，未入库）。

---

## 6. 问题场景与解决方法（总账）

### 6.1 内核与驱动

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| `RTNETLINK answers: No such device` | 虚拟名不是 `wonder0` | 用 `wonder0` |
| 帧到不了虚拟网卡 | 接口类型是 `NAN` | 改 `ADHOC`（IBSS） |
| 每 30 秒射频被带走 | IBSS 未固定频率 | `fixed-freq` |
| `up` 后 1 秒内 bind 失败 | DAD 让 link-local 处于 tentative | `accept_dad=0` |
| `ksud` 拒绝规则 | 只认 4 段式 sepolicy | 补全段数 |

### 6.2 协议与编码

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| bplist 整数往返变形 | 写侧对 `0x80..0xff` 用 1 字节，读侧 `sign_extend` 读成 −128 | 改为「≤8 字节一律无符号读」，新增测试锁死 |
| 每个 cpio 归档以 `Truncated` 结束 | `finish()` 手写 trailer 头，漏了名字 `TRAILER!!!` | `finish()` 复用 `write_head(TRAILER, 0, 0)` |
| 对端收到「短文件名」 | cpio 头声明 11 字节名却只写 1 个 NUL | 同上，结构上不可能再漏 |
| `406` 后连接被当解析错误 | 拒绝时没关连接，剩下的 body 被当下一个请求头 | 拒绝即 `Connection: close` |
| 空字典 `/Discover` 被对端秒断 | 该实现要求三个 Sender 字段 | 三个字段必填 |
| 加了 `DeviceSupportFlags` 后应答更短且 `/Ask` 沉默 | 对端按「Mac 能力」重排行为 | 去掉，保持与首次成功一致的 body |
| 发送端把 `200`+非空 body 当成功 | UP-5：空 body 才是成功，body 是错误报告 | `Client::upload` 两半都查 |

### 6.3 Android 运行时

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| 关掉 Activity 约 90 秒后接收端停 | app idle 主动停服务 | `connectedDevice` 前台服务 |
| 前台服务抢占首次启动 | 通知权限对话框排在服务之前 | 先起服务再要权限 |
| 后台起前台服务被拒（Android 12+） | `ForegroundServiceStartNotAllowed` | 由有可见窗口的一方拉起，或用户可见时再起 |
| 「接收端开关」拨了没反应 | 设置层只认 `"true"/"false"`，原来按 `"on"/"off"` 读写 | 全部改成布尔字面量 |
| 底栏图标按下**完全不震** | 清单没声明 `VIBRATE`，而 `tapVibrate` 故意吞掉 `SecurityException` | 补权限 |
| 平台 `Switch` 圆点溢出轨道 | 系统控件尺寸与设计不符 | 自绘 `Toggle`（50×30dp、点 24dp） |
| 设置行第三行副标题被裁 | 行高定死 66dp | `setMinimumHeight(66)` + `WRAP_CONTENT` + 上下 8dp |
| `.so` 推了不生效 | App 从 APK 里抽 `.so` 到 files，按 size 比对 | 必须走 `install`，不能只 push |
| 钩子装进 SystemUI 报 `ClassNotFoundException: OShareSysUiInlineViewService` | 那个类是**互传的**，视图在互传进程里展开 | 钩子跟着类走：装进互传进程；高度钩子留在 SystemUI |
| 用 logcat 判断钩子没装 | **libxposed 的 `log()` 写 `/data/adb/lspd/log/modules_*.log`，不进 logcat** | 读那个文件 |
| 窗口过期后接收端不停 | 「线程在跑」被当成「该收」，窗口自我续命 | 手动开关另立凭据（SharedPreferences），判断看凭据不看线程 |
| 发送页一打开就闪退 | `setBackgroundBlurRadius` 在 `setContentView` 之前调用，平台直接对**惰性创建**的 `DecorView` 下手 → NPE | 放到 `setContentView` 之后（`applyGlass()`） |
| 发送页窗口有 surface 却一片空白 | `onPreDraw` 里 `return false` 取消首帧，动画的 invalidate 被一起吞掉 | `INVISIBLE` + `post`（窗口本就透明，先不可见一帧看不出） |
| 拖动面板时**字在抖**、整个窗口上下摆 | ① `setTranslationY` 让整棵子树重绘（全屏圆角描边 + 全部文字每帧重栅格化）；② 层里的雷达每 32ms invalidate 逼整块纹理重画；③ 上一次回弹动画还在跑，和手指抢着写 `translationY`；④ 位移取整制造 1 像素台阶 | 移动期 `setLayerType(HARDWARE)`、拖动时停雷达呼吸、`ACTION_DOWN` 里 `animate().cancel()`、位移保留小数 |
| 面板里的模糊要么盖住整屏，要么一动就卡 | 用了 `Window.setBackgroundBlurRadius`——**整窗**模糊，成本在合成线程 | 改成 View 级：`BackgroundBlurDrawable` 设成面板 background（见 13 节的 11:00 条） |
| `gfxinfo` 报 0 janky、5ms，用户却说卡 | 跨窗口模糊的开销在 SurfaceFlinger 合成，`gfxinfo` 只统计应用线程 | 判据换人：这类问题看合成帧率（`dumpsys SurfaceFlinger --latency`）或直接上手 |

### 6.4 构建与工具链

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| `gradlew` 在 Git Bash 下 classpath 异常 | MSYS 路径转换 | 直接用 `tools/gradle-8.13/bin/gradle`，并 `export MSYS_NO_PATHCONV=1` |
| `cargo` 找不到 | PATH 未含 `~/.cargo/bin` | 每个会话 `export PATH="$HOME/.cargo/bin:$PATH"` |
| `cargo test` 不产 `cdylib` | 测试只建 rlib | 先 `cargo build -p opairdrop-ffi` 再 `loadLibrary` |
| `io.github.libxposed:api:100` 解析失败 | 这个坐标不存在 | 用 `102.0.0`，与 `module.prop` 的 `minApiVersion=102` 对齐 |
| 加 androidx 后连 `:app:dependencies` 都失败 | 缺 `android.useAndroidX=true` | 写进 `gradle.properties`（依赖是 `compileOnly`，不进 APK） |
| APK 体积虚高约 640 KB | 增量打包残留的空对齐条目 | `gradle clean assembleDebug` 后才是真值 |
| `adb pull /sdcard/x .` 路径错乱 | 远端路径也被转换 | 远端保留 `/sdcard/...`，本地用相对路径 |
| 预览脚本 `round_poly()` 抛 `TypeError` | 元组少一层括号，只 append 了最后一个 float | 只影响离线预览，坐标已手抄进 Java |

### 6.5 方法论（最贵的一节）

**症状**：连续多轮「新结论推翻上一轮结论」，每轮都有数据支撑，合起来净进展为零。典型三次：
簇内 2 ms（实为日志处理时刻）→ 13 段窗口（实为每轮装帧数 `wmax`）→ `retry=Y` 提升 95%
（顺序采样假阳性）。

**根因不是数据不足，是把「间接自述计数器」当成独立事实轮流采信。**

**纪律**：
1. 结论分级：**不可动摇的事实**（单一来源、直接计数）/ **未定论** / **下一步单一实验**；
2. 只有「端到端墙钟时间」和「落盘字节 SHA-256」可以当判据；中间计数器只用于定位；
3. 引用源码解释现场数字前，先确认**设备上跑的版本与源码一致**（曾出现模块比仓库旧）；
4. 同一个问题最多采一轮被动数据；没有可证伪的 A/B 就停手，写进未定论。

**2026-11-14 追加一条**：**禁止用「读全文 → 批量 replace → 整篇写回」的脚本改源码或文档。**
当天这个手法连闯三次祸：`crates/ffi/src/send.rs`、`crates/ffi/src/jni.rs` 被清空（靠 git
救回），`Task.md` 被清空（git 里也是空的，只能重建）。改代码一律逐处精确替换、一次一处、
改完立刻编译；文档同样。**仓库必须始终有可回滚的提交。**

**2026-11-15 追加一条**：**`dumpsys` 的输出里，「还活着」和「曾经有过」常常挨在一起，中间不给
分隔线。** `dumpsys notification` 的 `Notification List:` 段是活的、`mArchive=` 段是历史归档；
在那 5 条 id 2593–2597 上，`grep "想给你发文件"` 匹配的全是归档里的死记录，于是一整夜的轮询都在
追一个早已被取消的东西。同类还有 `dumpsys activity` 的 recent 与 running。**判据要么来自我们
自己的日志**（`confirm N posted as M` 这种），要么必须先把输出按段截断再 grep：

```bash
dumpsys notification | awk '/Notification List:/,/^  mArchive=/'
```

推论比这条具体命令重要：**能被归档的东西不能当「当前状态」的证据**，而任何靠文案匹配系统 UI 的
判断，都要先问一句「这段文字是谁写的、什么时候写的」。

---

## 7. KernelSU → LSPosed：结论

- 「完全不用 root」在当前内核形态下**不成立**：AWDL 依赖 `wonder.ko` + 打过补丁的
  `qca_cld3_peach_v2.ko`，这一步只能 root 做。
- 「不用 KSU 模块 zip」**成立且值得做**：形态是 `assets/kernel/` + 一次 `su -c insmod` +
  boot_id 标记（同一 boot 内不重复加载）。
- 其余全部交给 LSPosed 模块：分享面板入口、通知栏与流体云集成。

### 7.2 当前仓库的链路层状态

**没有。** 仓库里没有 `assets/` 内核产物，`android/` 与 `crates/` 里 `insmod` / `wonder.ko` /
`su -c` 零命中；`legacy/kernel` 只有 3.3 MB 驱动**源码补丁**，`.ko` 当年在 KSU 模块 payload 里。
所以现在这个 APK 只完成了应用侧：接收端会 bind 8770（真机验过 `/Discover` 有应答），但
`wonder0` 没人拉，iPhone 那边连端口都不可达。

### 7.3 驱动加载的**确切序列**（从旧模块 `bin/control.sh` 抄下来，别凭记忆写）

`.ko` 在 `repo-v2/build/package/module/payload/kernel/`：`mac80211.ko` 17.6 MB、
`qca_cld3_peach_v2.ko` 20.0 MB、`opairshare_wonder_provider.ko` 271 KB、`wonder.ko` 78 KB。
**合计 37.4 MB**——全塞进 APK 会让包从 890 KB 变成约 38 MB，这是「完全摆脱 KSU」的真实代价，
要先认下来再动手。

顺序（每一步之间旧脚本都会 `boot_id` 打标记，中断后能续）：

```sh
am force-stop com.coloros.oshare
svc wifi disable                 # 必须等 Wi-Fi 真正关掉，再停 HAL
rmmod qca_cld3_peach_v2
insmod opairshare_wonder_provider.ko
insmod qca_cld3_peach_v2.ko \
  passthru_awdl_addba=1 passthru_awdl_native_rx=1 passthru_ht=1 passthru_fwtype=7 \
  passthru_mon_rings=0 passthru_dry_run=0 passthru_mon_chan_sync=1 passthru_ndi_peer=1 \
  passthru_ndi_no_up=1 passthru_mgmt_bridge=1 passthru_aux_monitor=1 passthru_tx_audit=1 \
  passthru_ndi_fw_authorize=0 passthru_awdl_wmi_raw=1 passthru_awdl_target_mac="" \
  passthru_awdl_target_auto=1 passthru_awdl_target_enable=1 passthru_awdl_target_auth=2 \
  passthru_native_data_rx=1 passthru_awdl_backpressure=1 passthru_awdl_raw_rx=1 \
  passthru_awdl_rate_mbps=54 passthru_awdl_host_seq=1 passthru_awdl_rx_dedup=1 \
  passthru_awdl_attempts=3 passthru_awdl_rx_auto=1 passthru_awdl_rx_rearm=1 \
  passthru_sta_coexist=3 passthru_sta_regular_rx=1 passthru_sta_action_rx=1 \
  passthru_pas_discovery_actions=1 $turbo_args
rmmod mac80211
insmod mac80211.ko
insmod wonder.ko physical_name=wondertap0 virtual_name=wonder0
# 之后恢复 HAL 与 Wi-Fi，再 iw 建 IBSS（awdl_link_up），最后才启动接收端
```

`turbo_args` 来自旧脚本的 `turbo_module_args`（按机型/温度拼的可选参数），要另抄。
**风险点两条，动手前必须明确**：① `rmmod` 掉的是 Wi-Fi 驱动，失败会让人没有 Wi-Fi，所以
必须像旧脚本那样先关 Wi-Fi、留退路（boot 内重试 + 失败回滚检测）；② 从应用目录 `insmod`
会被 SELinux 拦，必须先复制到 `/data/local/tmp` 再加载。

---

## 8. QuickShare / Mosey 剔除清单

82 个文件，按 `legacy/` 里 `AirDropServer`、`Mosey*`、`Nearby*`、`ShareMode.QUICKSHARE` 的
引用闭包确定。**新仓库从零写，天然不含**；`legacy/` 只作只读参考，不进构建。

---

## 9. 当前进度（2026-11-15 18:45）

### 已完成并验证

| 项 | 证据 |
| --- | --- |
| Rust 八 crate | `src/` 合计 21300 行，`cargo test --workspace` **377 passed**（1 ignored），clippy 零 warning |
| 接收端 TLS | 真机 `openssl s_client`：`TLSv1.3` / `TLS_AES_256_GCM_SHA384`，握手 **10 ms**（七次实测 9–33 ms）；证书 `CN=PJZ110`，2036 年到期 |
| 发布的指纹就是出示的指纹 | `tcpdump -i awdltun0` 抓到公告 TXT `h=D07F4814…C7E8CA`；同刻 `openssl x509 -fingerprint -sha256` 得到**同一串**。这条不成立时，每个发送端都会在对方屏幕上被拒且无从解释 |
| 接收端在真机上应答 | 经 TLS 发 `POST /Discover` → `HTTP/1.1 200` + `Server: AirDrop/1.0` + `Content-Length: 138` + 合法 bplist（`ReceiverComputerName=青`、`IsAirDropable=True`、`{"Version":1}`），`Connection: keep-alive` 且连接保持 |
| 死对端不再打死进程 | 强 RST 三型（空转 / 写中 / 半截 body）+ 优雅断开后 `pidof` 均存活；桌面 `-Xcheck:jni` 对照：拿掉防线即 2 条 `JNI call made with exception pending` 并让 `check.sh` 退出 1，恢复后零警告 |
| 前台服务 | `oshare_state=1` → `ReceiveService` 以 `isForeground=true types=0x10` 起来、8770 在听；`=0` → 服务与端口都归零 |
| 分享面板注入 | 真机确认：一加互传分享面板的设备列表里出现「发送到 iPhone」 |
| 发送链路 | `Peers`（mDNS 浏览）+ `ShareIntake`（URI→cache）+ `SendActivity`（TLS + 三请求 + **真实进度**） |
| 接收确认（`/Ask` 三支齐） | 读完 offer 后问一次人（通知两键，30 秒超时按拒绝）。真机新包实测：**接受** → `200` + 106 字节 `AskAnswer`（`ReceiverComputerName=青`、`ReceiverModelName=iPhone 18`）；**拒绝** → `503`，日志 `confirm 3 declined by the person` / `declined after 1448 ms`；**超时** → 同样 `503`，但日志里是等满 30 秒而非早应答。三种结局在 wire 上只用 `200`/`503` 两个码，所以**判据必须是日志，不能只看状态码** |
| 射频共享开关 | 功能页一张卡，读写 `files/radio-share`/`radio-state`；真机四态（共享/独占/正在开/无回执）逐个拍过；`RadioCheck` **48 项** |
| 驱动随包 | `assets/kernel/` 五个二进制 + `SHA256SUMS` 进 APK；`DriverSetup` 按 §7.3 序列加载，`boot_id` 标记防重复；真机报 `AWDL_LINK_UP iface=wonder0 freq=5745`、`tun=up`、`stack already swapped this boot` |
| mDNS 应答器 | 真机抓包 `hlim 255`、3.000 s 节奏、`0*- [0q] 4/0/0` 四条记录、TXT `"h" "fl=137" "flags=137"`；自造 `(QM)` 查询后 0.9 ms 四条应答 |
| 六项 UI 修改 | 首页开场白卡删除、底栏三只图标重画、刷新按钮删除、设置页跟手动画、回弹以被拉边为轴、震动权限 |
| 接收进度通路（Rust + JNI） | `crates/ffi/src/serve.rs` 新增 `LandingCallback`/`Watcher` 与第三个导出 `opairdrop_serve_watched`，`jni.rs` 新增 `Java_..._serveWatched` + `report_landing`。`cargo check --lib` 通过；`cargo test --workspace` **380 passed**（1 ignored），含两条新测试：端到端那条断言「只有 `/Upload`、没有前置 `/Ask`」时全程 `total == 0`（诚实的不声明），单元那条断言未声明尺寸的成员贡献 0 而非把总和拉小（7+11+0 = 18） |
| 接收进度通知栏 | `ReceiveService` 新增固定 id `0x0A40` 的传输通知（`0x0A1D` 是服务、`0x0A20–0x0A3F` 是确认框）；`Listener` 改用 `Native.serveWatched`，`Meter` 按 200 ms 节流投递。`gradle :app:compileDebugJavaWithJavac` **BUILD SUCCESSFUL**。⏳ 未上真机 |
| 流体云接收弹窗（互传进程内钩子） | 三段复验连续三轮全绿：`ReceiveDialogActivity` 前台弹出并点拒绝 → `503`（**8.8 s**，不是 30 s 超时）；同一张卡退到后台仍在 `Hist #0`；再次邀请弹出并点接受 → `200 OK`（**8.7 s**）+ `/Upload` `200`（**9.2 s**）。判据日志串成一条：`bridge event out` → `bridge line` → `receive event incoming_transfer … phase=pending installed=true` → `invitation … present=true`，**同一毫秒**。**前置条件是 `cached_apps_freezer=disabled`**，否则钩子进程被 `cgroup.freeze` 冻住、事件永远读不出来（见 §13） |

### 明确未完成

- **iPhone 分享面板里出现本机**：本侧该做的都做了、都抓包看过了（见 §13 本轮记录），这一步只能由
  人手拿一台 iPhone 看一眼，**没有第二台苹果设备可自证**。
- **接收第三档（真机端到端）**：**机制已全部跑通，只差对面那台真 iPhone**。`/Ask` 三支（接受 /
  拒绝 / 超时）与 `/Upload` 逐字节落盘都在真机上验过（证据见 §13）；探针发的就是 iPhone 的 wire 形状
  （bplist、`Files`+`Items`、`TransferType` 两种）。仍缺的一步是「iPhone 发一张图、文件真的落到
  `receive.directory`」——这一步只能人拿 iPhone 做，且需要本机 root 在手（临时 root，冷重启后要
  手动 `su`）。
- **流体云卡片**：已定为「承载真实传输」。信道：offer 走广播（我们 → 互传进程），决定走
  `Settings.System`（互传进程 → 我们，因为普通应用写不了它）。卡片本体未写。
- **自有发送面板**：要仿 `IOSShareActivity`（全屏、圆形返回键 + 「与 iPhone 互传」+「本设备：×」、
  中部雷达喻体、底部说明卡），浅/深两套、弹出动画、**点头像才出**蓝色「等待对方接受」、
  接受后进度绕头像圆环走。现有 `SendActivity` 只是临时形态。
- **通知栏与流体云显示发送进度**。
- **`phash` 证书校验**（发送端侧；接收端侧的 `h` 已发布且与出示证书一致）。
- **设置表里 15 行未接到读值处**（`receive.directory`、`device.name`、`device.model`、各超时、
  `http.response_limit` 等）。
- `git` 已初始化并有提交，但首个提交晚于事故，历史不含更早状态。

---

## 10. 下一步（按顺序，每条可验收）

**互通阶段的顺序是用户定的：先接收，后发送。** 接收有三档，一档不成立就别看下一档——
「找不到」和「发不过来」和「发过来了但没提示」是三件不同的事，混在一起修只会浪费时间。

| # | 任务 | 验收 | 状态 |
| --- | --- | --- | --- |
| 1 | 驱动打包：`assets/kernel/` + `su -c insmod` + boot_id 标记 | 冷启动后 `wonder0` up | ✅ 已随包，`DriverSetup` 按 §7.3 序列 |
| 2 | mDNS 应答器（本侧发得出、答得上） | 抓包里 3 s 节奏、`hlim 255`、四条记录、TXT 三键 | ✅ 真机实测，见 §13 |
| 3 | **接收第一档：iPhone 分享面板里能找到本机** | 人眼在 iPhone 上看见本机名字，点得进去 | ⏳ 待实测（本侧无法自证）。**已有的抓包线索**：02:25:07 一条 `handshake from fe80::84a8:a1ff:fe43:3eb8%awdltun0 failed after 496 ms: Connection reset` —— 对面**已经连上来过**，说明公告被发现了、TXT/SRV 被读走了。下一步只差在它再来的那一刻看 `offer from …` 那一行 |
| 4 | 接收第二档：接收端 TLS（`Identity` + `h=` + `SSLServerSocket`） | `openssl s_client` 能握手；`/Discover` 有应答 | ✅ 真机：`TLSv1.3`/`AES_256_GCM`、10 ms；`/Discover` → `200` + 138 字节 bplist；TXT `h` 与出示证书指纹逐位相同 |
| 5 | 接收第三档：文件真的落到 `receive.directory` | iPhone 发一张图，`Download/` 里出现同字节文件 | ✅ **机制已验证**：`/Ask` 三支 + `/Upload` 逐字节（含 `photos/`、`假期/` 两级中文路径）。⏳ 待真实 iPhone 复验 |
| 6 | 接收三处提示：确认弹窗 + 通知栏 + 流体云 | 三个入口同一份状态，接受/拒绝都回写 | 弹窗 ✅ 真机三支已验；**流体云 ✅** 走互传进程内钩子（`ReceiveDialogActivity`），三段复验三轮全绿，前置是 `cached_apps_freezer=disabled`（见 §13）；通知栏进度 ✅ 通路打通、编译通过（`0x0A40`，200 ms 节流）。⏳ 未上真机 |
| 7 | 自有发送面板（仿 `IOSShareActivity`） | 与参照同构；浅/深；弹出动画；环状进度随真实字节走 | `SendActivity` 是临时形态 |
| 8 | 发送端接入 `SendActivity`（用户点名的第二刀） | 从我们自己的面板发起一次完整发送 | 未开始 |
| 9 | `phash` 校验 | 指纹不符时不发，且给出可读原因 | 未开始 |
| 10 | 15 行设置接线 | 每接一行删一个 `DEFAULT_*` 常量；键名只出现在 `settings.rs` | 未开始 |

第 3 档不成立时的排查方向，**不是改参数猜**：先 `tcpdump` 同时抓 `awdltun0` 上我们的公告与 iPhone
实际发问的时刻分布。如果 iPhone 从不发问，问题在链路（Apple 的 AWDL 只在它自己要发时才醒）；
如果它问了而我们没出现在结果里，问题在记录内容（这种情况才回去看 TXT/SRV）。

---

## 11. 度量与验收基线

| 指标 | 值 | 说明 |
| --- | --- | --- |
| 单元 + 集成测试 | **384 passed**（1 ignored） | 无设备 |
| JVM 断言 | **368 条** | `bash crates/ffi/jvm/check.sh`，桌面 JVM + `-Xcheck:jni`，不是真机 |
| Rust 行数 | 21561 | 八 crate；`src/` 合计 |
| Java 行数 | 12133 | 29 个类：三页 + 发送 + 确认 + 服务 + 钩子 + 身份/射频/链路 |
| 真机库 | `libopairdrop_ffi.so` **685 344 字节** | `aarch64-linux-android`，linker 35（含 mDNS 三个入口） |
| APK | **13 728 472 字节**（debug，含 `assets/kernel/`） | 内核资产合计约 38 MB 未压缩，压后约 13 MB；不含资产的 clean 包仍是 88 万级 |
| 接收吞吐 | 80–88 KB/s | 受对端 31.2% 占空比限制，**不是 KPI** |
| 发送吞吐 | 1.06–1.13 MB/s | 同上 |
| clippy | **2 warning**（`too_many_arguments`） | `jni::serve_for_java` 8 参、`respond::announce` 8 参，都是上一轮加参数（`landing` / `ttl_override`）留下的；纯签名整理，等一次专门的重构收掉 |

---

## 12. 设备与工具坐标

- 设备：一加 13 / PJZ110，`646e00bd`，1440×3168，ColorOS V17.0.0。
- 源码根：`D:\Downloads\project\repo-OPAirDrop`；反编译产物：`.tmp/oshare-17.2.36/`。
- 工具链：`JAVA_HOME=tools/jdk-17.0.20.1+1`、`ANDROID_HOME=tools/android-sdk`、
  `tools/gradle-8.13/bin/gradle`，`export MSYS_NO_PATHCONV=1`。
- 出包：先 `cargo build --release --target aarch64-linux-android -p opairdrop-ffi`，
  再 `cd android && gradle clean assembleDebug`。
- LSPosed 日志：`/data/adb/lspd/log/modules_*.log`（root 可读）。

---

## 13. 变更日志（新的在上）

- **2026-11-15 19:22**：**「自己给自己传只有 1.8 MB/s」结案：慢的是量它的那把尺，不是链路。**
  `ask.py` 的进度行每 512 KiB 调 `drive.describe(drive.ours("transfer"))`，而它内部是
  `adb shell dumpsys notification`——单次 **340–364 ms、1.1 MB 输出**。24 MB 上传 = 48 次 ≈ 9.6 s；
  把采样摘掉、其余一字不改（`askfast.py`）同一份代码同一条 forward 得到 **25.6 MB/s / 0.94 s**，
  差额 9.57 s 与 48 × 0.20 s 闭合。分段实测：`adb push` 660 MB/s、`blast.py → 设备 nc` 186–192 MB/s、
  Python TLS 146 MB/s、设备 CPU ≤11%、`recv-q` 84% 为 0（峰值 359 KB）——**六条都不是瓶颈**。
  顺带修掉两个错判据：①`recv-q` 恒 0 不能拿来判「谁在拖」，它只说明没有滞留；
  ②「传输中没有线程在 `sk_wait_data`」是字段选错——本 app 的读走 Java poll，形态是 `do_sys_poll`
  （`opairdrop-liste` 在 `do_sys_poll` 与 `futex_wait_queue` 间交替）。详见 5.6。
- **2026-11-15 18:45**：**流体云接收弹窗打通。卡了一整天的那一跳不是代码，是 `cgroup.freeze`。**
  钩子进程 `com.coloros.oshare` 由 `am startservice` 拉起后一秒内就掉到 `oom_score_adj=905`，
  而这台 ROM 的 `freezer_cutoff_adj` 是 **900** —— 于是它被冻进
  `/sys/fs/cgroup/apps/uid_10163/pid_<pid>/cgroup.freeze`。被冻结的进程**不会因为自己的 socket
  上有数据而被调度**：宿主 `write+flush` 成功、`/proc/net/tcp6` 里 `rx_queue` 压着 838 字节、
  读者线程就停在 `recvfrom`，一次也不返回。对发送端这三种断法长得一模一样（30 s 后 503），
  所以前几轮排查一直在代码里绕圈。

  **判据链（同一台设备、同一轮）**：
  ① 冻结位 `cgroup.freeze=1`，41 个线程全在、一个都不跑；
  ② `echo 0 > …/cgroup.freeze` 那一瞬间，18:21 积压的两条 `incoming_transfer` 立刻被读出 ——
  但彼时已是 `status=1004 phase=rejected`，邀请早作废，这正是「点了发送什么都没弹」；
  ③ `settings put global cached_apps_freezer disabled` 之后，同一份代码同一毫秒投递：
  `18:33:12.849 宿主写 → 18:33:12.849 钩子 bridge line → 18:33:12.855 invitation`；
  ④ 三段复验从「恒定 30.0 s 超时」变成 A 段 **8.8 s** `503`（人点拒绝）、C 段 **8.7 s** `200 OK`
  + **9.2 s** `upload → 200`，`dumpsys activity activities` 里能看到后台那张 `ReceiveDialogActivity`。

  **`cmd deviceidle whitelist +com.coloros.oshare` 在这台 ROM 上无效**（白名单里有、照样被冻），
  所以修法只能落在 `cached_apps_freezer` 这个全局开关上。它不是调优，是和 `svc wifi disable`
  同一类前置条件：分享会话期间必须借走、结束必须归还。因此写进 `awdl-stack.sh`：
  `freezer_off` 在冷热两条路径都执行（幂等，首写者把原值存进 `state/freezer-before`），
  `freezer_restore` 在 `cmd_stop` 与 `boot` 的三条失败分支里还原。
  已验：`boot` 打 `FREEZER_OFF`、`settings` 从 `null` 变 `disabled`；`stop` 打 `FREEZER_RESTORED`、
  回到 `null` 且 `freezer-before` 被删；冷路径二次 `boot` 再打 `FREEZER_OFF`，回执
  `BOOT_RESULT stack up boot=d81a34b5 iface=wonder0 freq=5745 daemon=32710 tun=up`。

  **另记一条环境判据**：`am startservice com.oplus.oshare.OShareServer` 返回后 1 秒内
  `dumpsys activity services` 就查不到它了 —— 指望它撑住进程优先级这条路不通，别再试。

- **2026-11-15 15:40**：**整批 49.3 MB / 487 s = 98.8 KB/s；24.8 ms 的批周期里我方只占 0.16 ms。**
  带 `wait=` 的 `awdld` 上机跑完整批，`cap-w1.txt` 逐帧分解：我方 ACK 中位 0.16 ms（p90 1.26），
  空口等待中位 <0.5 ms（82% 的包）、均值 4.5 ms、15.9% 的包 ≥8 ms；ACK 进 tun 之后 iPhone 平均
  22.25 ms 才发下一批；17498 批、97.5% 恰好 2 × 1386 B、批周期 24.82 ms、批内帧间隔 1.69 ms。
  `burst=1` 与 `burst=4` 差 0.4%（103.2 vs 102.8 KB/s）已在上一轮否掉「拷贝数抬高 RTT」，
  本轮数据把 24 ms 的归属彻底钉在：**17.5 ms ≈ 1.07 超帧的对端发送节拍**。另记两件新事：
  ① 1.69 ms/帧 ≈ 6.6 Mbps（去程我们能跑 36 Mbps，回程它只给 6.6），待空口 radiotap 佐证；
  ② 进度条 15% 差是缓冲（窗口 4.19 MB vs BDP 2.5 KB），不是丢数据，见 5.4 / 5.5。

- **2026-11-15 09:30**：**「几 MB 就炸」是一个字节校验：`bad field width 3`。同时把「弹窗慢」量成了
  丢包，不是 RTT，也不是 Nagle。** 真机上一句报错拆成三件事，逐个收口。

  **一、`bad field width 3` 的因果链（已修）**。`/Ask` 的 body 里带 `FileIcon`（几 MB 的 PNG），
  body 变大之后 iOS 的对象表/引用超过了 0xffff，于是把 trailer 里两个宽度字段写成 **3 字节**。
  `crates/bplist/src/lib.rs` 的 `Reader::new` 原本只认 `1 | 2 | 4 | 8`，直接 `Err(BadWidth(3))`，
  `Ask::parse` 上浮成 `Error::Plist`，`server.rs` 回 `400` 并 `Err(Error::Protocol(..))`，
  `ffi::classify` 把它一律映射成 `status::TRANSPORT`，到页面就只剩一句
  `接收失败：protocol: plist: bad field width 3`。
  **三条判据说明缺陷在我们这边，不在对端**：① 写侧 `width_for()` 只会产出 1/2/4/8，我方**不可能**
  写 3，所以这个 body 一定来自对端；② `legacy/.../Bplist.java` 的 `validWidth()` 是同一个限制——
  这是从 legacy **平等继承**的历史缺陷，不是新引入的；③ Apple 自己的读侧（`_getSizedInt`）接受
  **任意 1..8 字节**宽度，宽度是「字节数」不是位宽，我们的检查比格式更严。
  修法一行：`(1..=8).contains(&width)`，注释写清上面三条；读循环本来就没有限制（`read_unsigned`
  逐字节移位），不需要动。补两条测试：`accepts_three_byte_field_widths`（手工搭三段式 payload，
  两个宽度都写 3、引用也 3 字节）与 `still_rejects_impossible_field_widths`（0 / 9 / 255 仍拒）。
  `cargo test -p opairdrop-bplist` → **18 passed**。新 APK 已装、8770 在听，**判据是重发几 MB 时
  页面不再出现那句话、`Download/` 里落下同字节文件**。

  **二、「点发送到弹窗慢」不是握手快慢，是三次握手之后的第一批数据被丢了**。抓包
  `/data/local/tmp/e2e3.txt`（1697 行、`wonder0`、09:08–09:21、修好锚点之后）分两种连接形状，
  泾渭分明：

  | 形状 | ClientHello 段数 | 握手耗时 | 出现在 |
  | --- | --- | --- | --- |
  | A：`P. seg1:518 len517` → 我方 `seg1:1243` → 对端 `seg518:582 len64` | **1 段** | **2–10 ms** | 52166/52167/52169/52171/52174/52175/60847… |
  | B：`P. seg1429:1497 len68` → 静默 3.5 s → 对端重传 `seg1:1189`、`seg1189:1429` | **3 段** | **3.5–3.7 s** | 56597、49832、59458、53402、49190、58245、60298、63426 |

  形状 B 里，三段的**前两段一个都没到**，到的只有尾巴 68 字节；我方只发得出 1 个 dup ACK
  （够不到 3 个），iOS 只能等 RTO，于是**每次多花 3.5 s**。这正是 `handshake from … in 4359 ms`
  那条日志的来源。**RTT 只有 58 ms**（SYN 40.852643 → 对端 ACK 40.910468），所以既不是链路 RTT、
  也不是 Nagle、也不是 TLS 版本——是**一趟飞行里丢头部**，而头部丢失无法被 dup ACK 发现，只能等 RTO。
  `ip -s link show wondertap0` 与 `wonder0` 两侧 `dropped 0 / overrun 0`，说明**不是内核环丢的**：
  要么对端在它自己的 AWDL 队列里丢了这两段，要么我们这颗 radio 的第一批没收到。两种可能已经用
  **两路抓包**分开测（见四）。

  **三、上一轮的「锚点冻结」修复在真机确认生效。** 换二进制 → `awdl-stack.sh stop/start` →
  `boot`（pid 20257）。`anchored` 86908（冻结 5.4 h）→ 现在 7721 一路爬到 7809；`released=5`
  （释放路径真的走过了，不再是 0）；`peer` 会跟着「当前在房间里的那台」换（`ee:17:…` → `6e:fc:…`）；
  `err` 在 `2tu/3tu` 间抖动而不是钉死；`drop=0`。

  **四、两个抓包脚本进仓库**：`tools/capture.sh`（`wonder0` 上 `port 8770 or port 5353`）、
  `tools/tap.sh`（`wondertap0` 上 `ip6`，raw 802.11 + radiotap）。写这两个脚本踩到三件事：
  ① `tcpdump -i any` 在这台机器的 tcpdump 上**一条都收不到**，且不报错；② 抓错接口名
  （`awdltun0` vs `wonder0`，隧道会随「App 自己持有 / 脚本拉起」换名字）得到的是**空文件**，
  读起来像「什么都没发生」；③ **成功也会写 stderr**——`listening on wonder0, link-type EN10MB`
  是它的启动横幅，所以「`.err` 非空 = 失败」这个一开始写进脚本的判据是错的，它会把两个正常
  起着的抓包报成失败。已改成 `grep -q '^listening on'`：横幅在就是起好了，其余内容
  （`No such device` / `Permission denied`）才是真失败。

  **五、还没收口的两条**，判据已经架好，等下一次真机测试一次性取：
  - 「第二台 iPhone 较晚才看到本机」：先看两路抓包里**两台 iPhone 各自的 mDNS 查询时刻**与我们的
    应答是否成对（形状 B 的丢头同样可能吃掉第一条查询的应答）。
  - 「弹窗慢」的剩余半段：有确认弹窗时 `answers_ask_early() == false`，必须先读完整个 offer 才
    回 `/Ask`（`server.rs` 的 `ask()`），几 MB 的 `FileIcon` 要在这个 RTT 上走完。是否需要改成
    「先答后读」由抓包里的 body 传输时长决定，不先改。

- **2026-11-15 03:30**：**接收进度从写盘循环一路接到通知栏。** Rust 侧新增第三个导出与一个
  `Landing` 回调，Java 侧 `Listener` 换用它、`ReceiveService` 多一条随字节走的通知。按约定
  **流体云仍未动，单独一轮**。

  **一、为什么是第三个导出，而不是给第二个加参数。** `opairdrop_serve_decided` 当初就是「不改
  `opairdrop_serve` 的签名」才存在的，`opairdrop_serve_watched` 同理：`dlsym` 找不到符号比函数
  指针签名变了更难诊断。三个导出并存，旧调用方一行不改。JNI 侧对应 `serveDecided` /
  `serveWatched` 两个 native 方法，共享一个 `serve_for_java`，两者只差最后一个参数（`landing`
  可为 null）。

  **二、`serve()` 改成 `pub(crate)`。** JNI 层手上已经有 `Decide` / `Watcher` 这样的成对句柄，
  没必要再拆成原始参数表绕一圈 C ABI。为此补了 `Decide::new` / `Watcher::new` 两个
  `pub(crate)` 构造器，两个结构体也一并放开可见性——否则 `jni.rs` 报 `E0603: struct is private`。

  **三、接收侧的进度类型叫 `LandingCallback`，不叫 `ProgressCallback`。** `send.rs` 里已经有一个
  `ProgressCallback(context, phase, sent, total)`（发送侧四阶段）。接收侧没有阶段可言：要么在读
  offer，要么在落字节，只有后者产生数字。同名不同签名会被读者当成同一个东西。

  **四、分母（`declared`）必须在 `accept()` 里取。** 字节开始落地时 offer 已被消费，
  `Store::upload()` 拿不到它。所以 `accept(&mut self, offer: &Ask)` 顺手把
  `offer.files.iter().filter_map(|f| f.size).sum()` 存进 `self.declared`。未声明尺寸的成员贡献
  **0**，而不是把总和拉小——这条有单元测试（7 + 11 + 未声明 = 18）。

  **五、`total == 0` 是一个语义，不是一个错误。** 对端可以什么都不声明。此时通知栏走不确定态
  （`setProgress(0, 0, true)`），而不是一个分母为零的假比例。端到端测试专门验了这一支：只有
  `/Upload`、没有前置 `/Ask` 时全程 `total == 0`。

  **六、`done` 只数 payload，`total` 是对端声明的**，所以两者能直接比；`done` 可以超过 `total`
  （对端多发），显示时按 `total` 封顶——一条 100% 的进度条比一条 112% 的有用。

  **七、Java 侧的节流是必要条件，不是优化。** 回调按落地块调用，一次传输成千上万次，每次
  `notify` 都是一次 binder 事务，全落在正在读字节的那条线程上。`Meter.INTERVAL_MS = 200`；
  第一条立刻投出去，否则前两百毫秒里一个小文件已经收完了，人什么都没看见。

  **八、收尾不在回调里，在 native 调用返回之后。** 回调不知道哪一块是最后一块——`total` 是可以
  少发的声明。`meter.finished()` 在 `serveWatched` 返回且 `report` 非空时调用；`finally` 里
  `meter.abandon()` 收掉走到一半的进度条。`abandon` 只在「自己画过 且 没有结果」时动手：一次
  握手失败或者一次空探测，不该把上一个传输留下的「接收完成」抹掉。

  **九、`failed` 标志：一次抛异常的监听器不能被回叫成千上万次。** 长传输上那就是成千上万个挂起
  异常。置 `failed` 后停止上报，但**不中断接收**——进度条不是丢传输的理由。异常本身保持 pending，
  那是 Java 调用方该看到的诚实报告。

  **十、通知 id 从 `0x0A40` 起。** `0x0A1D` 是服务的身份，整个生命周期都是 ongoing；进度条只跟
  一次传输同生共死。合成一条的话，「服务还在这儿」的凭据就要看一次传输有没有发生过，而人把收完
  的那条结果划掉，就等于划掉了前台服务的证明。固定值不分槽，因为接收端一次只服务一个对端
  （`Listener` 类注释「Why one thread and not a pool」），同一时刻最多一条传输。

  **十一、`jni.rs` 是 `w/crlf`，同目录其余 8 个是 `w/lf`。** `git ls-files --eol` 显示这是
  `core.autocrlf=true` 造成的既有不一致。任何用 LF 写的锚点在 `jni.rs` 上匹配 **0 次**。改法：
  patch 脚本先探测目标是否含 `\r\n`，含则把锚点与替换文本整体转成 CRLF。

  **十二、`Edit` 工具这一轮连续失败**（`ModifyBackup failed … sandbox.backup.modify_backup`），
  连两行探针都写不进，而同一路径 `Bash` 的 `printf >>` 完全正常——是编辑器工具的备份通道问题，
  不是文件权限。绕法是「唯一锚点 + 命中次数必须等于 1 + `newline=''` 不翻译行尾」的 Python 精确
  替换，锚点与替换文本放 sidecar 文件里（避开 shell 引号与多行字符串）。**没有用「读全文 →
  批量 replace → 整篇写回」**，§6.5 的纪律不变。

  **十三、JVM 断言必须为新的 native 出口加一条。** `cargo test` 链的是 rlib，符号名写错在那里
  编译得过，只有在真机第一次连接时才炸成 `UnsatisfiedLinkError`——而那行日志没有人读。
  `RoundTrip` 里因此加了 `serveWatchedReportsWhatItLands`（5 条断言）：调用一发生，符号当场解析；
  顺便把 Rust 那两条测试的结论在 JVM 边界上重述一遍（最后一数等于真正落地的字节数、不倒退、
  `total` 全程为 0 因为这次没有前置 `/Ask`）。断言总数 **363 → 368**。

  **十四、`check.sh` 在 GBK 宿主机上根本编不过。** 这台机器的 JDK 17 默认源码编码是平台编码
  （GBK），而这些源文件是 UTF-8 且一半带中文，`javac` 报 196 处「编码GBK的不可映射字符」——
  看起来像代码错误，其实不是。脚本里的 `java` 早就带了 `-Dfile.encoding=UTF-8`，`javac` 漏了
  `-encoding UTF-8`，补上即可。

  **验证**：`cargo check -p opairdrop-ffi --lib` 通过；`cargo test --workspace` **380 passed**
  （1 ignored），含 `a_watched_endpoint_counts_what_it_lands_and_says_nothing_was_declared` 与
  `an_offer_s_sizes_are_the_denominator_a_watcher_is_given`；
  `crates/ffi/jvm/check.sh` **368 条全过**（含新加的 5 条）；
  `gradle :app:compileDebugJavaWithJavac` **BUILD SUCCESSFUL**。⏳ 全部未上真机。

- **2026-11-15 03:10**：**第 3 档收口——`/Ask` 三支与 `/Upload` 在真机上全部走通；同时查清上一轮为什么
  白跑一整夜（`grep` 命中的是通知历史归档），并顺手修掉真实设备那条「0 个文件」的通知。**

  **一、为什么上一轮白跑。** `dumpsys notification` 的输出里，`Notification List:` 之后是**活着的**
  通知，`mArchive=` 之后是**历史归档**。上一轮那 5 条 id 2593–2597 全落在
  `mArchive=Archive (49 notifications)` 段里，`grep "想给你发文件"` 因此永远在匹配过去——每条都已经
  被取消了，脚本却把它们当成「还没决定」。正确的截断方式：

  ```bash
  dumpsys notification | awk '/Notification List:/,/^  mArchive=/'
  ```

  教训写在 §6.5 之外，也写在这里：**通知的判据不要靠文案 grep，要靠我们自己的日志**。改法见下一条。

  **二、`id` 与通知 id 的换算（现在唯一可靠的定位方式）。** `Confirmation` 里
  `notification = NOTIFICATION_BASE + (NEXT.get() % 32)`，`NOTIFICATION_BASE = 0x0A20 = 2592`，
  而 `id = Integer.toString(NEXT.incrementAndGet())`，于是

  ```
  id = 通知id − 2592
  ```

  日志行就是这条关系的现场：`confirm 1 posted as 2593`、`confirm 2 posted as 2594`、
  `confirm 3 posted as 2595`。所以流程变成「先读日志拿精确 id，再单发广播」，
  **不再盲扫一段 id**——上一轮盲扫时把**并发存在的别人的 waiter 一起答掉了**，那才是「真实设备那条
  通知被莫名接受」的真正原因。`answer()` 对未知 id 是无害空操作（`if (waiter == null) return;`），
  这一点让盲扫「看起来能用」，但它的代价是别人的 waiter。

  **三、`/Ask` 三支，原文如下（真机、新包）。**

  接受 —— 无人值守可复现，`200` + `AskAnswer`：

  ```
  命中：confirm 2 posted as 2594
  Broadcast completed: result=0
  recv     HTTP/1.1 200 OK
  recv     Content-Length: 106
  plist    {'ReceiverComputerName': '青', 'ReceiverModelName': 'iPhone 18'}
  ```

  拒绝 —— `503`：

  ```
  命中：confirm 3 posted as 2595
  recv     HTTP/1.1 503 Error
  11-15 02:35:35.762 I OPAirDrop: offer from 探针 1 0
  11-15 02:35:37.210 I OPAirDrop: confirm 3 declined by the person
  11-15 02:35:37.210 I OPAirDrop: confirm 3 declined after 1448 ms
  ```

  超时 —— 也是 `503`，但日志是 `declined after …` 里等满 30 秒，**而 `503` 有两个来源**：
  `answers_ask_early()` 为真时的早应答（不产生 503）之外，产生 503 的只有 `accept(&parsed)`
  返回 false（人拒绝 / 超时 / `decide` 异常）。所以「得到 503」本身证明链路走到了问人那一步；
  反过来，**`200` 不能单独当作接受分支的证据**——早应答（`decide.is_none()`）与「问了人、人同意」
  都回 `200`。本轮就是靠「拒绝广播应得 503 却得到 200」这个反常，反过来发现当时前端根本没问人。

  **四、`DecisionReceiver` 是 `exported=false`，`am broadcast -p` 进不去。** 可行路径只有一条：

  ```bash
  adb shell su -c 'am broadcast -n com.oshare.airdrop/.DecisionReceiver \
      -a com.oshare.airdrop.DECISION --es id 3 --ez accepted false'
  ```

  **五、「0 个文件」的成因与修法。** 真实设备那条通知正文写的是「0 个文件」，可它发的是一次链接
  分享（`BundleID: com.apple.mobilesafari`、`Files: []`、`Items: ["https://…"]`）。根因在
  `crates/ffi/src/serve.rs` 的 `summary_of`：它只数 `files`，纯链接分享必然算出 0。而这句话出现在
  **人正在决定收不收的那一刻**，「0 个文件」读起来就是「没东西可收」。修法是把摘要从三字段扩到四
  字段 `sender\tfiles\tbytes\tlinks`——`payload::Ask::summary()` 本来就区分 files/links，只是
  `summary_of` 没用它。实测第四字段一路走到通知正文：

  ```
  11-15 02:41:42.785 I OPAirDrop: offer from 链接探针 0 0 2
  android.text=String (2 个链接，30 秒内不回答就当作拒绝。)
  ```

  `Confirmation.detail()` 里用 `if (files != 0 || links == 0)` 守住「纯链接时别提文件数」，所以
  「0 个文件」被整个省掉了。

  **六、`Confirmation` 补了三条日志，把两个原本静默的出口照亮。** 这不是顺手加的：
  - `post()` 里 `manager == null` 原本是静默 `return`，`notify()` 失败则抛 `RuntimeException`
    被 `ask()` 的 `catch (RuntimeException)` 吃掉且不留痕——两条路的共同结果是**每一次传输都被
    拒收**，而对端只看到 503，本机屏幕上什么都不显示。
  - `InterruptedException` 分支会 `Thread.currentThread().interrupt()` 把标志还回去，而
    `Listener` 只有一条长期复用的线程 `opairdrop-listener`，标志一旦置上没人清，之后每次
    `latch.await` 都立刻抛——症状同样是「每个传输都被拒收」，原因却在这一行里。顺带确认
    `Listener.stop()` 只 `closeQuietly(open)` 关 socket、**不 `interrupt()`**，所以「线程被打断」
    在这个仓里没有来源（全仓只有 `Confirmation` 自己与 `Responder.java:843` 调 `interrupt()`）。

  **七、`Listener` 加了一行 `offer from …`。** 第 3 档能不能成，唯一能留下的证据就是这一刻的
  `SenderComputerName` / 件数 / 字节数 / 链接数——连接一结束，这份信息在仓里任何地方都不再存在。

  **八、`/Upload` 逐字节复验。** 清空下载目录后重跑 843 字节 ODC 归档，三级路径（含中文目录）全部
  同字节：

  ```
  /sdcard/Android/data/com.oshare.airdrop/files/Download/hello.txt
  /sdcard/Android/data/com.oshare.airdrop/files/Download/photos/one.jpg
  /sdcard/Android/data/com.oshare.airdrop/files/Download/假期/照片.jpg
  e77174a4729d21ec6e202718385b6cde  hello.txt
  b31c7cec2ef6a2c572c48e509613df2e  photos/one.jpg
  d03d864b7f43db9ce34df5f720509d0e  假期/照片.jpg
  ```

  落点不需要存储权限——`/sdcard/Android/data/<pkg>/files/` 是应用自己的外部目录。

  **九、还没解释的一件事。** 老进程上出现过一次「约 4 秒就回 503、且通知没出现在任何地方」。中断
  来源已排除（见上），新包会带堆栈把这条路径记下来，等它再现。

  **十、`Confirmation` 类注释自相矛盾。** 注释写「三十分钟」，而 `TIMEOUT_MS = 30_000` 是三十秒。
  已改，并说明为什么这个数要比发送端自己的超时早一点：好让框在对面放弃之前消失，否则只剩「按了
  接受、那边早走了」。

- **2026-11-15 00:45**：**接收端握手通过、`/Discover` 有应答；同时推翻上一轮那条「TLS 握手失败」的
  结论——它从来不是握手的问题。**

  **一、错在哪里。** 上一轮记的是「客户端写 1549 字节、服务端读 0、`error:0A000126`」，据此判为握手
  参数不当。实际是两件事叠在一起：第一次尝试时接收端根本没在听（`pidof` 为空，进程已被系统回收），
  `adb forward` 在 connect 那一刻才去连设备端口，连不上就直接关掉这条连接，客户端看到的就是「写满
  了、读 0、EOF」；而之后每一次真正的握手都成功，只是**握手成功后进程被 tick 掉了**。两种成因在
  客户端侧长得一模一样，所以判据只有一条：**握手之后立刻查设备侧进程是否还活着**，再谈参数。

  **二、进程怎么死的。** `adb logcat -b crash`：

  ```
  Abort message: 'JNI DETECTED ERROR IN APPLICATION: JNI NewByteArray called with pending exception
      java.net.SocketException: Connection reset
      in call to NewByteArray'
  #00 libc  Fatal signal 6 (SIGABRT) in tid (opairdrop-liste)
  #03 CheckJNI::NewPrimitiveArray
  #04 Java_com_oshare_airdrop_Native_serveDecided+1112
  ```

  CheckJNI 的规则是硬的：任何一次跨 JNI 的调用（`NewByteArray`、`SetByteArrayRegion`、
  `CallVoidMethod`…）**在已有 pending exception 时被调用，abort 整个进程**，不是抛异常。而「对端
  半路消失」这个场景必然走到该状态：`/Discover` 先应答再读 body，`read_control` 把读失败**吞成**
  `(空, false)`（`crates/airdrop/src/server.rs:426`），`drain` 也吞（`:491`），于是端点返回**成功**、
  内容为空，而读回调把 `SocketException` 原样留在 JVM 里——正好撞在 `new_bytes` 的 `NewByteArray` 上。

  **三、修法：同一条规则写五遍。** `crates/ffi/src/jni.rs` 里每个「即将跨 JNI」的入口都先看
  `pending(env)`——`new_bytes`（:1684，返回 null，把这个异常留给 Java 自己抛）、`read_stream`
  （:1075）、`write_sink`、`report_progress`、`decide_java`（人要做的决定不能替异常做；同时把
  `DeleteLocalRef` 提到提前返回之前，否则漏掉局部引用）。`finish()` 的 OK 分支只补了一段解释：
  「成功」与「有异常」并不矛盾——对端中途关闭时，「什么都没存」和「I/O 失败」两句话同时为真。

  **四、怎么证明修好了——这一节的方法比结论值钱。** adb 上的试探不可靠：`timeout 3 openssl
  s_client` 发的是 `close_notify`，服务端走 EOF 而不是 reset；用 `SO_LINGER(1,0)` 造的真 RST 三种
  形态（空转、写中、半截 body）也只换来「进程存活」，进不了异常分支。于是把案子搬回桌面：
  `RoundTrip.aVanishingPeerIsOneConnectionAndNotTheProcess` 用一条声明 `Content-Length: 200` 却只给
  12 字节、随后抛 `SocketException` 的流，确定性复现同一状态。**关键在于 `check.sh` 加了
  `-Xcheck:jni`**：Android 的 CheckJNI 默认开着，HotSpot 默认根本不看，所以这类 bug 在桌面上从来
  不可见。对照实验：

  | 构建 | 结果 |
  | --- | --- |
  | 拿掉两处防线 | 2 条 `WARNING in native method: JNI call made with exception pending`，`check.sh` 退出 1 |
  | 恢复防线 | 零警告，全部通过 |

  两个 JVM 对「事实」一致、对「罚则」不一致（警告 vs abort），所以脚本把这个警告**提升为失败**。
  一个坑：这行警告走的是 **stdout**，只 grep stderr 会漏掉——第一版就是这么漏的。

  **五、真机三项证据。**
  - `openssl s_client`：`TLSv1.3` / `TLS_AES_256_GCM_SHA384`，握手 **10 ms**（七次 9–33 ms）。
  - 经 TLS 发 `POST /Discover`：`HTTP/1.1 200` + `Server: AirDrop/1.0` + `Content-Length: 138`，
    bplist 为 `{ReceiverComputerName: 青, ReceiverMediaCapabilities: {"Version": 1},
    IsAirDropable: True}`，`Connection: keep-alive` 且连接保持。
  - `tcpdump -i awdltun0` 抓到公告 TXT `h=D07F48146C5A2D215427D04C29DC62AC158ED0B493E25F52D47C3123D8C7E8CA`
    `fl=137` `flags=137`，间隔 3.004 s；同刻 `openssl x509 -fingerprint -sha256` 得到**同一串**。
    「发布一份证书、出示另一份」这个一直在文档里被担心的失败模式，现在有实测反证。

  **六、日志。** `Listener` 的 tag 与 legacy 同名（`OPAirDrop`），两代可以一起 grep；握手成功/失败、
  连接结束各一行，页面上什么都不加——页是给人看「有没有问题」的，日志是给「没人能复现」用的。
  `routine()` 顺 cause 链找 `SocketException`（比 legacy 的字符串匹配稳：`SSLException` 包着
  `SocketException` 也认得出），命中就只记日志、不上报。

- **2026-11-14 22:40**：**本机在 AWDL 上开始说话了，而且说的是 legacy 的方言。** 驱动打包之后一直
  卡在「iPhone 能不能看见我们」——我们连一句 mDNS 都没发过。这一节补上应答器：`crates/ffi/src/respond.rs`
  的 `mdnsRecords`/`mdnsAnnounce`/`mdnsReply` 三个入口、`MulticastHop`（把 `IP_MULTICAST_TTL` 换成
  `hlim 255`，legacy 的路由需要的不是 TTL 而是跳数）、`ServiceId`（12 位 hex 名字 + 最多 8 个旧名字）、
  `Responder`（公告 3 s 一次、应答走 `mdnsReply`）、以及 `Listener` 的接线。

  **一、抓包原文（`tcpdump -i awdltun0`，真机）。** 这一段是这一节的全部价值，参数不是猜的：

  ```
  22:28:24.036332 IP6 (flowlabel 0x6a11d, hlim 255, next-hop UDP) fe80::6410:37ff:fe63:44fb.5353 > ff02::fb.5353:
     0*- [0q] 4/0/0 _airdrop._tcp.local. PTR 75bdacd4227c._airdrop._tcp.local.,
              75bdacd4227c._airdrop._tcp.local. SRV pjz110.local.:8770 0 0,
              75bdacd4227c._airdrop._tcp.local. TXT "h" "fl=137" "flags=137",
              pjz110.local. AAAA fe80::6410:37ff:fe63:44fb (244)
  22:28:27.039 / 22:28:30.048 / 22:28:33.052 / 22:28:36.058 …（同一形态）
  ```

  四个判据一次读出来：节奏是**严格的 3.000 s**（不是 60 s，也不是 8 s）；`hlim 255` 证明
  `MulticastHop.raise` 真的改了跳数；`0*-`、`[0q]`、id 0 是**组播应答**的正确形态；TXT 三键
  `h`/`fl`/`flags` 与 legacy 一字不差。日志另一头对应 `announcing 75bdacd4227c at pjz110.local:8770 on awdltun0`。

  **二、应答路径也实测过，不只是公告。** 自己造一条 PTR 查询（`ff02::fb%awdltun0:5353`，源端口
  54270），0.9 ms 后收到四条应答：

  ```
  22:29:54.961677 ... 54270 > ff02::fb.5353: 0 PTR (QM)? _airdrop._tcp.local. (37)
  22:29:54.962564 ... .5353 > ff02::fb.5353: 0*- [0q] 4/0/0 …（四条）
  ```

  `54.962` 不落在 `54.184 + 3 s` 的公告网格上，所以它只能是应答，不是顺带的公告。

  **三、「回组播还是回单播」的判据是 QU 位，不是源端口。** 这一段差点写错：查询来自 54270（临时端口），
  直觉会认为该回给来源。回 legacy 核对（`legacy/.../core/MdnsResponder.java`）：

  ```
  :742  ask.unicast |= question.unicast;
  :748  int id = ask.unicast ? message.id() : 0;
  :688  transmit(reply.packet, reply.unicast ? incoming.getSocketAddress() : null);
  ```

  只由问题里的 QU 位决定。本次查询是 `(QM)`（无 QU），所以回组播**与 legacy 一致**。`:603–608` 那段
  注释（「the bridge forwards unicast frames only … RFC 6762 §6.7 … the question is asked from an
  ephemeral port」）讲的是**我们自己发问**时该带 QU（`Peers` 浏览侧），与本端应答无关。这条别再推导一次。

  **四、`mdnsAnnounce` 没有 `throws`，`mdnsReply` 有。** `Native.java:232` 与 `:259` 的差别让
  `catch (IOException | RuntimeException)` 在同一份代码里一处能过、一处编译不过。公告/撤回两处因此
  只捕 `RuntimeException`——地址写错是我们自己的 bug，不是值得等待的条件。

  **五、`Responder` 归 `Listener` 所有，不归三个开关点。** 「能接受连接」和「别人看得见」是同一句话，
  所以 `beginAnnouncing()` 放在 `bind()` **成功之后**，`endAnnouncing()` 放在 `finally` 的**第一句**
  （撤回必须从即将消失的那个 socket 发出去）。`FeaturePage`/`HomePage`/`ReceiveService` 三个入口
  于是自动全覆盖，没有第二处会漂移。

  **六、绑定失败要说出来，而且要能再拨一次。** 本机最常见的失败是「AWDL 链路还没建」，而人正是修好
  之后会再拨一次。所以 `open()` 的失败路径必须 `opener = null`，否则守卫看见一个已结束的 opener，
  第二次拨动会以沉默作答。失败本身不阻止接收端继续跑（Mac 用旧地址或 `nc` 仍能连），但必须经
  `aside()` 拼进功能页那一行——「在收听但没人找得到我」是只有这一行能说的错。

  **七、`ServiceId.stale` 必须有人撤回。** 它记得最多 8 个旧名字，正是为了「崩溃半写 → 新 ID →
  旧 ID 还留在别人缓存里 75 分钟」这一刻。撤回只能在 socket 起来后发，所以 `ghosts` 在第一次公告
  **之前**清空。legacy 的 `AirDropLink.close() → advertising.withdraw(current)` 是**死代码**
  （`withdraw()` 第一句 `oldInstance.equals(instance) → return true`），所以这里绕开它，`withdraw(held, name)`
  是另一个重载：一个按 3 s 节奏发，一个只在某件事结束时发。

  **八、`socket` 是 `volatile`，`open()` 的交接在一把锁里。** receive / tick / transmit 三条路径读它时
  不持锁（锁只保护状态迁移）；`opener = null` 与 `socket = opened` 分两次写会留一个两者皆 null 的瞬间，
  此时 `start()` 会去 bind 一个已被占用的端口。

  **尚未自证的最后一步**：iPhone 分享面板里真的出现本机。本侧能做的（公告形态、应答、TXT、hlim、
  节奏）全部实测过了，剩下的只能由人手拿一台 iPhone 看一眼。

- **2026-11-14 20:20**：**开关落到了功能页，而且它不是一条设置。** 上一节说「UI 不能承诺百分比」，
  这一节把那句话变成代码：`crates/ffi/jvm/com/oshare/airdrop/RadioShare.java`（纯 Java、零 Android
  类型，App 与桌面检查共用）、功能页一张专用卡、`RadioCheck` 48 项、真机四态各拍一次。

  **一、没有进 `CATALOG`，这是刻意的。** 原定的下一步是「在 `crates/settings` 的表里加一条
  `radio.share_wifi`（`Kind::Switch`）」。读过三条既有事实之后否掉了：

  - `settings.conf` 是 App 自己拥有的值；射频开关是**对另一个进程的请求**，回执由别人写回。存进
    表里，同一件事就有两个家，而无线电只认 `files/radio-share` → 页面可以显示「共享中」而守护
    进程正在独占。
  - `sectionOf()` 没有 `radio` 前缀的归口，会落进 `SettingsScreen.UNSORTED`，而 `SettingsCheck`
    断言那一段为空 → 加一行就让 JVM 检查变红。这是设计好的闸，不是巧合。
  - `SettingsPage` 本来就 `skip(Control.SWITCH)`（「开关在功能页，因为拨一下就生效」），加进表也
    只是为了让它在功能页出现；而功能页的开关循环按 `true`/`false` 写 `settings.conf`，对这个开关
    是错的写入路径。

  所以这个开关只读写那两个文件，别处一概不碰。

  **二、失败不把开关拨回去，和设置类开关正好相反。** `addSwitch()` 失败时 `setChecked(before)`，
  因为「留着它拨过去的样子，等于让人以为设置生效了」。射频开关失败**不**拨回：拨回等于悄悄把人
  的偏好改成相反的，下一次 `start` 会照那个相反的来做（脚本与 daemon 都读那个文件）。失败是射频
  没做到，不是人没要求 → 开关留在原处，回执行变红并说出原因，要重试就再拨一次。只有**写不进
  文件**才弹回——那是磁盘问题，不是射频问题，第二行写「写不进请求文件：…」。

  **三、原子改名是承重的。** daemon 每 250ms 读一次那个文件并照着做，截断的写会被看见 → 读到空
  文件 → `share failed unknown request ""`，那是一条描述竞态的回执，不是描述无线电的回执。所以
  写同级 `radio-share.new` 再 `ATOMIC_MOVE`；桌面 Windows 不允许 rename 到已存在的文件，退回
  `REPLACE_EXISTING` 的 move，注释写明手机永远走不到那条路。

  **四、回执分两层，陈旧的不采用。** 上面一句人话（`State.subtitle()`），下面一行原始记录
  （`receipt().line()`，灰的、失败转红）。人话不能改写原始记录：落在哪个信道、失败原文都在下面。
  陈旧回执**引用**而不**采用**——`已请求独占，回执还停在「share confirmed sta=5220」`——并且
  `staMhz()` 此时返回 0，否则页面会为一个不在那个信道上的射频印出一个信道。`asked` 判的是
  **文件在不在**，不是文件非空：空文件是「有人写了但没写成」，当成「从没人问过」会把坏文件藏进
  默认值。

  **五、真机四态**（一加 13 / PJZ110，`awdld --uid 10415 --name AirBox --burst 4 --ctl
  /data/data/com.oshare.airdrop/files` 在跑）：

  | 拨动 | 盘上 | 页面第二行 | Wi-Fi |
  |---|---|---|---|
  | 冷开（文件已存在） | `share` / `share confirmed sta=5745` | 共享中：Wi-Fi 在 5745 MHz，与 AWDL 共用射频 | 开 |
  | 关 | `exclusive` / `exclusive confirmed` | 独占中：Wi-Fi 已关闭，手机没有 Wi-Fi 网络 | 关 |
  | 开（抓到了过渡态） | `share pending` | 正在开 Wi-Fi，等 STA 拿到信道 | 开 |
  | 删掉 `radio-state` 再进页 | `share` / 无回执 | 已请求共享，还没有回执——守护进程可能没在运行 | 开 |

  App 写进去的那份是 `u0_a415:u0_a415 0600`（原子改名把 root 组的旧文件换掉了），daemon 以 root
  读，不受影响。两次共享都落在 5745——信道有粘性，又一次印证。

  **六、设备上抓到两个真 bug，都不是编译能发现的。**

  1. **`pending` 被当成了答案，页面冻在「正在开 Wi-Fi」。** 拨回共享后 daemon 在一个轮询内写了
     `share pending`；`followAnswer` 的退出条件当时是 `answered()`，而「回执在谈论盘上这次请求」
     对 `pending` 也为真 → 循环当场停。四十秒后盘上是 `share confirmed sta=5745`、Wi-Fi 早开了，
     页面还停在 pending。修法：加 `State.settled()`（答上了**而且**是 `confirmed`/`failed`），
     `followAnswer` 改用它。关的那一向没暴露，是因为 `svc wifi disable` 比 250ms 快，第一次重试
     就已经是终态——**只有慢的那一向会露**。
  2. **回执消失时旧原文留在屏幕上。** `followAnswer` 原来只在 `receipt().present()` 时才写那一行，
     于是「已请求共享，还没有回执」下面还挂着上一次的 `share confirmed sta=5745`，两句里有一句是
     假的。改成每次都照盘面重写（含空串 + `GONE`）。

  **七、检查 45 → 48 项**（`bash crates/ffi/jvm/check.sh` → `ok, 48 checks`，连带 `RoundTrip` 与
  `SettingsCheck` 全绿）。新增三项都针对第 6 条那类「句子会不会撒谎」：
  `pending is answered but not settled` / `and that is a settled answer, so the page stops asking` /
  `a failure is settled`。`RadioCheck.java` 与 `SettingsCheck.java` 同样从 APK 里排除
  （`build.gradle` 的 `java.exclude`）。

  **八、`awdl-stack.sh` 头部那段 45% 的归因改了。** 原来写「45% 是 149 与 5220 之间那道缝的宽度；
  碰上正好在 149 的 AP，同一个测试量到 10%」——后来同一测试 10/10 落在 149（`sta=5745`），本轮两次
  也是 5745。信道是 AP 定的、有粘性，所以改成「45% 不是共享的属性，是一对信道的属性」，并写明 UI
  里不印百分比、只印 `sta=` 的 MHz。

- **2026-11-14 19:15**：**「WiFi 与 AWDL 共存开关」可以做，但提案里「开启后自动拆-改频-重建」这半句
  是假的。** 起因是想在 App 功能页加一个「WiFi 和 AWDL 同时可用 开/关」，默认关，开启后自动跟随 STA
  拆建。先验证后半句，结论是**能力问题，不是代价问题**。

  **一、驱动只认 5745，而且是硬编码。** `legacy/kernel/driver/wma_opairshare_pas_mgmt.h:580`
  `opairshare_pas_rx_fixed_channel()` 开头就是：

  ```c
  if (freq != 5745) {
      pr_info("OPAIRSHARERXAUTO: unsupported request freq=%u bandwidth_enum=%u\n", freq, bandwidth);
      return -EOPNOTSUPP;
  }
  ```

  它自己的注释就写死了性质：`/* Fixed149 profile: this does not establish general hopping support. */`
  拒绝点在 `wonder_start` 内部，即 `wonder-ven feed` 已经返回 ok **之后**——所以「feed 成功」和
  「`ip link set wonder0 up` 报 Operation not supported」会同时出现，dmesg 里是
  `OPAIRSHARERXAUTO: unsupported request freq=5220 ... bandwidth_enum=0` 紧跟 `Vendor init failed: -95`。

  **二、运行中换频三条路全部失败**（`awdld` 存活、链路正常时实测，2026-11-14）：

  | 走法 | 结果 |
  |---|---|
  | 原地 retune 5745→5220 | `up` 失败 -95，`wondertap0` 消失、`awdld` 退出、重建后 MAC 变化 |
  | `iw dev del` + `interface add` 到 5220 | 同上 |
  | `rmmod wonder` + `insmod wonder.ko physical_name=wondertap0 virtual_name=wonder0` | 新 vif 上 5220 仍 -95（排除「加载时锁频」） |

  由此**重解 18:47 的「72%」**：那不是「跟随了但丢包」，而是「根本没跟随」——tap 一直待 149，STA 走了，
  两者之间从来就没有能移动 tap 的代码路径。同理 18:47 那句「跟随 = 每次漫游拆了重入」也不成立，
  拆了也回不来，只会更糟。

  **三、热开 WiFi 是可行的，代价完全取决于 STA 落在哪个信道。** 同一二进制、同一 iPhone，
  `ping6 -c 12..20 -i 0.2 %awdltun0`，`awdld` 全程不重启：

  | 阶段 | 丢包 | min RTT | tap ifindex / awdld | STAT |
  |---|---|---|---|---|
  | 独占（WiFi 关） | 0% | 38 ms | 141 / 不变 | `phy=149` |
  | `svc wifi enable`，STA 落 **5220** | **45%** | 27 ms | 141 / 不变 | `phy=149`（无 `!`） |
  | 再 `disable` | 0% | 6.4 ms | 141 / 不变 | `phy=149` |
  | 独占（另一轮基线） | 0% | 88 ms | 141 / 30396 | `CHANNEL own=149` |
  | `share`，STA 落 **5745**（同信道） | **0%** | 7.8 ms | 141 / 30396 / MAC 不变 | 同上 |
  | 再 `exclusive` | 0% | 7.3 ms | 141 / 30396 / MAC 不变 | 同上 |

  两条结论叠起来：**开关成立，而且比提案简单得多**——它就是个 `svc wifi enable/disable` 的热切换，
  不重建链路、不重启 daemon、不改 MAC。但**代价不由我们决定**：STA 自己漫游到 44 就是 45% 丢包，
  落到 149 就是 0%。所以 UI 不能叫「WiFi 和 AWDL 同时可用」，那是承诺一件我们控制不了的事；
  应该叫「WiFi 共享射频」，并在开的时候明说「丢包取决于手机连的 AP 在哪个信道」。

  **四、`awdl-stack.sh` 本轮改动**（`sh -n` OK，28625 字节 / 620 行）：

  1. `pick_freq()` 不再跟随 STA，永远返回 `SOCIAL_FREQ`；`<source>` 降级为纯报告字段
     （`no-sta` / `sta` / `sta-on-<mhz>`），只为让 `status` 和 `STAT` 的 `phy=` 能对读。
  2. `link_up()` 的 retune 段由「拆了重设」改为**拒绝并保持**：`refuse retune <here> -> <freq>`。
     活着的错频链路还能过帧，死链路一帧都过不了，还会带走 App 的 TUN。
  3. **新增 `share` / `exclusive` 两个子命令**——就是给功能页那个开关用的接口。不动驱动、不
     `force-stop` App、幂等、把最后一次请求写进 `$STATE/radio-share`（`stop` 时清掉，`status` 里
     以 `radio=` 打印）。实测这两条命令跑完 tap ifindex 141 / awdld 30396 / MAC 全部不变。
  4. **修 `cmd_start` 的守卫顺序 bug**：`for m in wonder opairshare_wonder_provider; do loaded && die`
     原本跑在 `same_boot` 幂等分支**之前**，于是同一 boot 里第二次 `start` 必然
     `ERROR: unexpected live module wonder`——而「模块已加载」正是第二次 start 的定义。现场被它挡过
     两次，两次都是绕道 `link`，等于跳过了这个函数存在的意义（WiFi 记账）。现在幂等分支在前，
     守卫只作用于冷路径。
  5. 删掉 `WONDER_FREQS`（已无调用者）；重写文件头与 `AWDL_FREQ` / `KEEP_WIFI` 三处注释块，
     把上面这些实测证据和「72% 的真正含义」写进去。

  **顺手记一条待验**：`WifiNetworkSuggestion.Builder.setBssid()` 可以把 STA 钉在
  `f8:ce:21:60:00:94`（5745）。若成立，共享模式就能从 45% 回到接近 0%——价值不在「让 tap 追 STA」，
  而在「让 STA 迁就 tap」，方向正好相反。半小时可验。

- **2026-11-14 18:47**：**「连不上」是我自己造的污染态；但「跟随 STA」当默认值是错的。** 这一轮把
  18:31 那批改动上机验证完，结论分两半。

  **第一半：`REASON_1X_AUTH_FAILURE` 与驱动无关。** 之前为了测密码我用 `connect-network` 传过一个
  猜的 `Lingbai@2024`，把 `百灵青年公寓13楼-5G` 写成坏 PMK，随后连续四次握手失败让系统把它置成
  `NETWORK_SELECTION_TEMPORARY_DISABLED`（配置库里 PSK 后来虽已是正确的 `12345678`，这个状态仍在）。
  `forget-network 59` + 重新保存**不足以**清掉：dmesg 里仍是 `Assoc rsp RX` → `Connect ... is SUCCESS`
  → 4 s 后 `reason:23`。真正清掉它的是 `awdl-stack.sh stop`（停 HAL → 换回原厂驱动 → `svc wifi enable`），
  之后一次连上：`192.168.13.69`、5220 MHz、286 Mbps。
  由此否掉 18:15 的猜测：**wonder.ko 不会打断 4-way 握手**——在原厂驱动连上后再 `start` 加载 wonder 并把
  `wonder0` 拉起来，STA 在 5745 稳定保持 24 s 以上（`-34 dBm`），无一次重连。

  **第二半：「跟随 STA」不能当默认值。** 三组测量，同一二进制、同一对端（iPhone `2a:d8:dc:5d:8c:5a`），
  每组 `ping6 -c 40 -i 0.2`：

  | 配置 | 丢包 | 佐证 |
  |---|---|---|
  | STA 在 5745、tap 在 5745（同信道） | 10%（18/20 那组 min RTT 6.6 ms） | `phy=149` |
  | 跑到一半 STA 漫游到 5220，tap 仍在 5745 | **72%**（11/40） | `phy=44!5220MHz` |
  | WiFi 关闭、射频独占 | **0%**（40/40，min RTT 5.8 ms） | `phy=149` |

  中间那行是关键：这栋楼把多个 AP 放在同一 SSID 上（`f8:ce:21:60:00:94`=5745、`...:97`=5220），手机按
  信号自己漫游；而 **tap 一旦 up 就不能改频**——18:22 逐个试 `WONDER_FREQS` 时，除当前所在频率外全部
  返回 `-95 EOPNOTSUPP`。所以「跟随」的真实含义是「每次漫游都把 IBSS 拆了重入」，每次拆都要让对端丢掉
  我们的 map 好几秒（`STAT` 里 `anchored` 直接重置就是证据）。跟随只在 STA 被钉死在一个信道上时才成立，
  而我们没有钉死它的手段。顺便：daemon 侧的 `phy=44!` 与 `CHANNEL_MISMATCH` 这次**如实报出了漂移**，
  这条观测手段本身是有效的。

  因此 `awdl-stack.sh` 三处改动（`sh -n` OK，22088 字节）：
  1. 新增 `KEEP_WIFI=${KEEP_WIFI:-0}`。`cmd_start` 不再无条件把 WiFi 恢复：默认保持关闭，射频独占；
     `KEEP_WIFI=1 awdl-stack.sh start` 才恢复共享（并在 `link_up` 之前恢复，好让 `pick_freq` 看得到 STA）。
     注释里写进了上面三行数据作为选择依据。
  2. `cmd_stop` 补齐「把 WiFi 还回来」：按 `$STATE/wifi-before` 恢复。之前 `stop` 之后手机一直是离线的，
     得手动去点快捷开关（实测 `wifi=0` → 修后 `wifi=1`）。
  3. `wait_sta 15` 收进 `if [ "$KEEP_WIFI" = 1 ]`。原来 wifi 本来就没开时白等 15 秒去问一个已知答案；
     `link` 的耗时从 15 s+ 降到 **0.36 s**。

  **当前可复现参考态**（默认 `start` → `link` → `awdld` 不带 `--channel`）：
  `link_freq=5745 source=no-sta`、`CHANNEL own=149 src=link-freq`、`phy=149`（无 `!`）、
  `rx` 持续增长、`peer=2a:d8:dc:5d:8c:5a`、`ping6 -c 40` **0% 丢包**。
  注意 `avg RTT 271 ms` / `+114 duplicates` 是 `--burst 4` 的四份副本各自被回了一次，不是链路时延；
  真实时延看 `min`。

- **2026-11-14 18:31**：**5745 不是驱动钉死的，是脚本选的。** 这一条把 17:53 的「我们没有选信道的
  权力」再修一半：权力有，只是**一处**——`wonder-ven feed <freq>`。回 `legacy/kernel/` 正查 `5745`，
  命中项全部与 wonder 无关：
  `wonder/band_config.c:105` 只是 5 GHz 信道表里的一条（同表还有 2437/5180/5200/5220/5240/5745/
  5765/5785/5805）；`driver/wlan_hdd_wondertap.c` 里所有频率都来自 `params->freq`，经
  `__wlan_hdd_set_wondertap_channel()` → `hdd_validate_channel_and_bandwidth()` →
  `req.target_chan_freq = params->freq` 原样下发；唯一那句 `if (freq != 5745 || ...)`（第 166 行）在
  `opairshare_prepare_sta_dbs()` 里，且被首行 `if (!passthru_sta_force_dbs) return 0;` 挡着（默认关），
  与建链无关。**所以 5745 是 `awdl-stack.sh` 那行常量的一个选择，不是设备属性。**
- **2026-11-14 18:31**：**建链脚本改成「跟随 STA」，并把「谁决定频率」写进文件。** 常量的注释原本
  写的是「wonder.ko 把射频钉在 5745」——不成立，改成三条来源：
  ① 显式 `AWDL_FREQ`（`override`）；② `wlan0` 实际频率（`sta`）；③ 社会信道兜底（`no-sta`）。
  另加 `WONDER_FREQS` 白名单，因为喂一个 wiphy 不列的信道会让 `hdd_validate_channel_and_bandwidth`
  直接拒绝、**tap 永远停在 DOWN**（比 join 失败难查得多）。四种来源都会明说，实测确实触发过
  `source=not-in-wiphy(2462)`——`wlan0` 连在 ch11，而 wonder 的 2.4 G 表里只有 2437（ch6）。
  **`link-freq` 便条**：由建链脚本写、daemon 从 `current_exe().parent()/link-freq` 读回。理由是
  「`--channel` 由启动进程的人拼，射频由喂 tap 的人决定」，两个进程各自凭记忆拼同一个数字就是
  宣告与射频分家的机制；让决定的那一方写下来。`--channel` 仍可覆盖（`channel_given` 区分
  「输入 149」与「默认恰好是 149」），`CHANNEL own= src=` 打出 `flag`/`link-freq`/`social-guess`，
  最后一档额外往 stderr 喊一句。**实测**（`run11.sh`，故意不带 `--channel`）：
  `CHANNEL own=149 src=link-freq`，便条这条路通了。
- **2026-11-14 18:31**：**`wait_sta` 是必需品，不是保险。** `cmd_start` 是
  `svc wifi disable → 暂停 HAL → 换驱动 → 恢复 HAL → svc wifi enable` 后立刻 `link_up`，此刻
  supplicant 还在扫描认证，`iw dev wlan0 info` 里**根本没有 `channel` 行**，`sed` 输出空串，解析
  等于「没有 STA」，链路被钉回社会信道——要消灭的行为只是从「常量写死」变成了「时序写死」。
  上机第一次 `start` 就命中了（`source=no-sta`，而同一次 `status` 已打出 `sta_freq=5745`，STA 只是
  晚了一步）。修法是 `wait_sta 15`，超时才说
  `wlan0 has no channel after 15s; the link cannot follow it`。
- **2026-11-14 18:31**：**跑起来之后射频漂走，要看得见。** 启动时一次性 `CHANNEL_MISMATCH`
  （只打一次，每 5 秒一条没人读的日志等于没有），加上每 5 秒 `STAT ... phy=` 的 `!` 记号，覆盖
  「启动时就已不一致」和「跑起来之后才漂走」两种情形。`STAT` 里的 `own=` 与 `phy=` 是同一件事的
  两个说法，摆在一起才看得出分家。
- **2026-11-14 18:31**：**推翻我自己 18:0x 的一个假设：`REASON_1X_AUTH_FAILURE` 与 wonder/驱动无关。**
  现场是 `Assoc rsp RX` → `Connect ... is SUCCESS` → 4 ms 后 `[wonder][mac80211] RX filter configured`
  → 4 s 后 `reason:23 REASON_1X_AUTH_FAILURE`，太像 wonder 抢了 EAPOL。做了对照：`iw dev wonder0 del`
  之后（dmesg 有 `wonder_flush()` / `Removed virtual interface` / `HW stopped`），重连**仍然是**
  `reason:23`，且不再出现任何 `RX filter` 行。真因在配置库里：
  `WifiConfigStore.xml` 只有 `百灵青年公寓12楼-2.4G`（PSK `12345678`），**没有 `13楼-5G` 这条**，
  supplicant 拿不到 PSK，4-way 握手必然失败（`WPA: 4-Way Handshake failed - pre-shared key may be
  incorrect`）。现在 `wlan0` 实际连的是 `12楼-2.4G`（ch11 / 2462 MHz / 192.168.12.15）。
  **教训**：`REASON_1X_AUTH_FAILURE` 先查「这条网络有没有 PSK」，再怀疑驱动。
- **2026-11-14 18:31**：**`filter` 是 BSSID 过滤器，`foreign=0` 不能用来判断收包路径。**
  `wonder-ven` 的用法是 `<ifname> feed [freq] [bssid] [cc]`，脚本喂的是 Apple 固定 BSSID
  `00:25:00:ff:94:73`，所以非 AWDL 流量在固件层就被滤掉了——`other=0 / foreign=0` 只说明
  「没有 AWDL 帧到达」，不说明收不到包。18:08 那次 `other=2965 anchored=2715 peer=2a:d8:dc:5d:8c:5a`
  已经证明收包是通的；本次 `rx=0 phy=? peer=none` 的原因是 iPhone 的 AWDL 没在发（分享面板没开）。
  **验收时的前提要写清**：判 `rx=` 之前必须先确认对端 AWDL 是活的。
  度量：`cargo test -p opairdrop-awdl` 92 passed；`clippy --target aarch64-linux-android
  --all-targets` 零 warning；`awdld` **399 744 字节**；`awdl-stack.sh` 17 495 字节。
- **2026-11-14 17:53**：**`--channel` 从来没进过射频。收音机上真正坐着哪个频率，驱动写在 radiotap 里，读出来是 5745。**
  这一条把 17:30 的「信道选错」再往下钉了一层：不是我们选错了信道，是**我们根本没有选信道的权力**。

  **判据的来历。** `wondertap0` 抓到的是 linktype 127（radiotap），头里 `IEEE80211_RADIOTAP_CHANNEL`
  字段是驱动在收帧那一刻写的，不是我们伪造的。`rtchan.py` 一开始输出「320 MHz」，那不是信道，是我把
  `struct ieee80211_radiotap_channel { u16 freq; u16 flags; }` 的两个 u16 读反了——`71 16 40 01` 里
  `0x1671` = **5745**（ch 149），`0x0140` 才是 flags（OFDM + 20 MHz）。改正后的两个抓包：

  | 抓包 | 5745 MHz（ch 149） | 5220 MHz（ch 44） |
  | --- | --- | --- |
  | `c44.pcap`（`awdld --channel 44` 期间） | **10 帧** | 4 帧 |
  | `cap9-149.pcap`（`--channel 149` 期间） | 11 帧 | **23 帧** |

  **`--channel 44` 跑着的时候，空口进来的帧绝大多数仍在 5745。** 同时两组都出现两种频率，说明 phy
  在抓包过程中**来回跳**。跳的方向跟着 `wlan0`——本机连着 AP 的那个 STA vif。`iw dev wonder0 info`
  报 149 是对的，`--channel` 只是个打印用的标签：`args.channel` 只被 `NodeInfo::master_of` 与
  `LINK_UP` 拿去显示，`Radio` trait 里连 `set_channel` 都没有。`iw dev wonder0 set channel 44` 在
  IBSS 下直接 `-16 busy`。

  **顺带把「哪些帧带 radiotap 频率」也分类清楚了**，否则「14/120 帧有信道字段」会被误读成抓包残缺：

  | radiotap 长度 | present | FC | 帧数 | 是什么 |
  | --- | --- | --- | --- | --- |
  | 48 | `0x4000086f` | `0x00d0` | 14 | **空口来的**，驱动全头，带频率 |
  | 9 | `0x00000004` | `0x00d0` | 95 | 我们自己注入的 announce，只有 RATE（`0x48` = 36 Mb/s） |
  | 9 | `0x00000004` | `0x0088` | 4 | 我们自己的数据帧 |
  | 9 | `0x00000000` | `0x0008` | 7 | 无字段 |

  所以「带信道的帧少」不等于抓包残缺，等于**大部分记录是我们自己的回环**。以后看频率只看 48 字节那批。

  **legacy 为什么没这个问题——它把 STA 拆了。** `Entry.java:566-577` 那段注释写得毫不含糊：

  > mosey's network bring-up hard-depends on tearing the STA down first
  > (**single radio, dbs=0**)

  单射频、没有双频同时工作，所以 legacy 在开 AWDL 期间**主动断掉 `wlan0` 的关联**，phy 独占，信道由
  mosey 自己设，不存在两套上下文打架；会话结束再由 `hookWifiRestore` 把 STA 拉回来。配套的
  `DiscoveryChannels.java:8-14` 只在 STA 连着 5 GHz 时才把发现信道钉成 `149`——这是给部署版的
  `fixed149` 兜底，不是让它去改射频。

  **我们现在的处境**：`wlan0` 保持连着，`wonder0` 挂在同一个 phy 上，mac80211 只能让两边轮流用
  射频。于是 announce 里写的 16 槽时间表在物理上是假的：一半的窗口 phy 待在 AP 的信道上。44 那组
  18/20 之所以好看，是 `wlan0` 当时恰好在 44，两边同信道不需要切换；换成 149 而 `wlan0` 在 44，
  就变成 11/20。

  **两条出路，二选一，别再靠巧合：**
  1. **跟随**：启动时读 `wlan0` 的实际频率，`--channel` 默认取它，STAT 里把 `phy=`（真实）与
     `own=`（宣告）并排打出来。phy 不动就不丢帧，代价是 AWDL 只能待在 AP 的信道上。
  2. **独占**：照 legacy 断 STA，再显式 `set_channel`。链路质量最好，代价是会话期间没有网。

  先做第 1 条，因为它不需要动内核也不打断用户的网，而且它能让「信道」这个变量立刻从噪声变成常量；
  第 2 条等 TCP 数据面通了再作为可选开关。

- **2026-11-14 17:30**：**抓包抓到 iPhone 自己 announce 的信道序列，「槽选错」这条结论要改口径——真正大一级的是「信道选错」。**

  **判据不是我们的解析器，是空中的字节。** `tcpdump_odm -i wondertap0 -c 400 -w cap9-149.pcap` 抓下
  284 帧动作帧，按发送者 MAC 分组（`ana-cap9b.py`），直接从 TLV 里读 `0f 03` 之后那 32 个
  (channel, class) 对。iPhone（`2a:d8:dc:5d:8c:5a`）的 34 帧里出现两种形状：

  ```
  23x  44 44 149 0 0 0 0 44 6 44 149 44 0 0 0 44    class 128 128 128 0 0 0 0 128 81 128 128 128 0 0 0 128
  11x  44  0 149 0 0 0 0  0 6  0 149  0 0 0 0  0    class 128   0 128 0 0 0 0   0 81   0 128   0 0 0 0   0
  ```

  **它在 44 上有 6 个槽，在 149 上只有 2 个，在 6 上有 1 个。** 而我们整轮都坐在 149 上——理论上限
  就是 2/16 = 12.5%，实测 7/20 = 35% 已经超过这个上限（接收端并不严格按这张表听），但量级已经
  注定。16:40 那条记的 `map=149,149,149,0,0,0,0,0,6,149,149,0,0,0,0,0`「五个槽在 149」只在某一种
  轮转姿态下成立：两种形状在同一个抓包里交替出现，2 个 44 的那 11 帧正是被我们当成「全表」的那一态。

  **同信道 A/B，同一份二进制、同一个 iPhone，只改 `--channel`：**

  | 我们的信道 | ping | 平均 RTT | `rx` | duplicates |
  | --- | --- | --- | --- | --- |
  | 149 | 7/20（65% loss） | 491 ms（`min` 213 ms） | 8p / 832 B | +1 |
  | **44** | **15/20（25% loss）** | 410 ms（**`min` 6.5 ms**） | **46p / 4784 B** | **+31** |

  收包量差 **5.75 倍**，`+31 duplicates` 是「对端在 44 上发得比 149 上多得多」的直接指纹；
  `min=6.5 ms` 说明链路本身通得极快，卡的是槽对不上而不是射频不通。**这一条改了优先级：信道是
  一级变量，槽内份数是二级变量。**

  **顺带读到 legacy 真正在做什么（`AwdlFrame.java:606-629` `offerSequence`）。** 我们把「采纳对端
  信道表」实现成了整表照抄（`if theirs.contains(&ours) { self.schedule.set_slots(theirs) }`），legacy
  是逐槽判断、**只采纳对端落在「本信道」上的槽，其余槽保留本机信道**：

  ```java
  for (int i = 0; i < SLOTS; i++) {
      if (seq[i] == channel) { if (slots[i] != channel) { slots[i] = channel; adopted++; } }
      else { divergent++; }   // 对端在别的信道上，但我们不是；只能宣布我们在哪
  }
  ```

  外加三道闸：`isMaster()` 不采纳、`from != sync` 不采纳（`"not-sync"`）、`SEQ_CONFIRM = 3` 帧一致
  才信、`SEQ_HOLD_US = 1_100_000`（两改之间至少隔一个完整序列）。**整表照抄的后果是把可发槽从
  15/16 砍到 5/16 甚至 1/16**，等于自己把自己静音。这是已定位、未修的缺陷，下一步先修它。

  **结论：`map=` 不是我们自己的回读，是对端真实序列。** 以后凡「槽对不对」的判断一律以抓包为准，
  不接受解析器自证。

- **2026-11-14 16:40**：**AWDL 数据面进了 Rust，并且「为什么只有 3/20」被查成了两个互不替代的原因。**

  **结果**：新增 `crates/awdl`（4477 行，`cargo test -p opairdrop-awdl` **83 passed**，clippy 零
  warning）。`bin/awdld` 一个进程把 `/dev/tun` 与 `wondertap0` 接起来：`link.rs` 管队列、窗口与
  副本，`schedule.rs` 管 16 槽 / AW / PSF / 跟随，`action.rs` 管 PSF/MIF/选举/对端信道表，
  `frame.rs` + `mac.rs` 管字节，`linux/{packet,tun}` 管两端 socket。真机判据从基线 **3/20** 提到
  **12/20**。

  **这条路是用 legacy 的注释一条条对出来的，不是猜的。** `AwdlData.java` 里每处「这么做，因为
  某次实测如此」都带了数字，我们此前把它们全丢了，于是把老实现已经踩过的坑重踩了一遍。四条：

  | 发现 | legacy 出处 | 我们原来的状态 | 补上之后的量级 |
  | --- | --- | --- | --- |
  | 小包要在**一个槽的四个 AW 里各发一份**（`awBurst = 4`） | `:112-148`、`:169`（`burstMaxBytes=496`） | 无队列，单发 | 0/20 → 4/20 |
  | 副本必须**各带新序号**（同序号副本在 MAC 之上被当重复丢掉） | `:2812-2820` | —— | 每份现建帧 |
  | 发送前有**信道闸门**（对端那张 16 槽表的当前槽不是本信道就不发） | `:2505-2509` | 16 槽恒为 149，等价于永久可发 | 4/20 → **12/20** |
  | 窗口是**瞄准而非许可**（`edgeWaitUs`） | `:4088-4152` | 无 | 见下 |

  **结论一：丢包主因是槽选错，不是重复不够。** 这是本轮最有价值的一条。把对端 PSF 里的那张表
  解出来之后，iPhone 实际的 16 槽长这样：

  ```
  map=149,149,149,0,0,0,0,0,6,149,149,0,0,0,0,0
  ```

  **只有 5 个槽在 149，1 个在 6，另外 10 个是 0（空槽）。** legacy 的注释写的是「它自己序列里空着的
  那七个槽」，实测是十个。**（17:30 ⚠️ 此读数已作废：抓包显示同一台 iPhone 在 44 上有 6 槽、149 上
  只有 2 槽，这张「五个 149」只是它的两态之一。见上一条。）** 我们此前 16 槽全发，等于 **11/16 的时间在对着空气喊**——而且这一半的
  损失用重发是补不回来的：同一个槽里发四份、八份，四份八份共享这一个槽的命运。这正是为什么
  「每槽几份」扫不出峰值、「跨过几个槽」才有翻倍的实测形状：

  | 副本数（闸门未接） | 结果 | 帧数 |
  | --- | --- | --- |
  | 1 | 0/20 | 19 |
  | 2 | 0/20 | 48 |
  | 4 | 1/20 | 96 |
  | 8 | 2/20 | 192 |

  八份 = 128 TU = 跨两个槽，成果正好是四份的两倍。**成功率跟着「跨了几个槽」走，不跟着「每槽几
  份」走**——这就是闸门缺失的指纹。

  **结论二：槽对了之后，槽内四份又变成主要贡献。** 同一套代码接上闸门再扫：`burst=1` → 1/20，
  `burst=4` → **12/20**。两条缺一不可，而且顺序不能反：先保证槽在 149，再保证槽内四个 AW 各一份。
  另外那张表**在两次运行之间会变**（另一次读到 `44,0,149,…`），说明 iPhone 的社交槽在轮转——所以
  它必须每帧重读，不能缓存一次用到底。

  **结论三：一份突发必须整个装进一个窗口，装不下就整包推迟。** `burst=6` 比 4 更差（丢包回到
  60%，平均 RTT 4.7 s，另有 11 个重复包）：六份 = 80 TU，而窗口只有 53 TU 宽（`WINDOW_LAST_TU=56`
  减 `UNICAST_GUARD_TU=3`），尾巴必然越界落进下一个槽，而下一个槽可能不在 149 上——那份副本花的
  是射频时间，买的是零。所以 `flush` 现在会先算「第一份能发、最后一份会不会越界」，会越界就把整包
  推到下一个本机槽的 guard 边，并计入新计数器 `tx_deferred`。两个边界条件写死：
  **`burst_fits` 为假时一律不推迟**（宽到任何时刻都装不下，推迟就是队列永不排空；多播 guard 16 TU
  + 3×16 = 64 > 53，天生如此）；**推迟只发生在第一份之前**，已经发出的副本不回收。

  **结论四（已排除）：`elect=self` / `err=-3~-4tu` 不是瓶颈，不要再往这里查。** 曾怀疑
  「听到了帧却从不跟随时钟」。三条证据把它否掉了：`anchored` 每秒涨 3–4，说明帧在收、跟随在写；
  `error_tu` 与 `follow` 严格互逆（测试里跟随后立刻测误差为 0），所以 -3/-4 TU 是**固定偏置**而不是
  「没跟上」；窗口宽 53 TU，偏 4 TU（≈4 ms）在窗口之内。真正吃包的是槽选错，量级差了一个数量级。

  **两条被实测否掉的错法，记在这里免得再试一次：**
  ① 不要「等到 guard 就发」（只等 1–3 TU）：9.43 s → 10.22 s，客户端卡顿 1304 → 2090 ms；
  ② 不要用 `ping6 -I <ifname>` 判链路：IPv6 链接本地必须写作用域 `fe80::…%awdltun0`，`-I` 走的是
  `IPV6_PKTINFO` 另一条路径，直接 `EINVAL`，会让人以为「连不上」。同理
  `opairsharerx0` 只覆盖约 13% 的空中帧，「抓包里没有我们」不能当判据。

  **设备侧工具**：`run7.sh` / `run8.sh`（`--burst N` 扫副本数，`ping6 -c 20 …%awdltun0`，前后各打一次
  `STAT`）。`--burst` 是有意留成运行期开关的：副本数该不该跟对端的槽表联动，是个只能问射频的问题，
  答案要能一次问完而不重编。

  **下一步**：先把 TCP 跑通（8770 服务端 + `nc`），再补 mDNS 应答器——这两层不补，§10 第 1 条的
  「iPhone 能看到本机」仍然不成立，理由见 13:10 那条。

- **2026-11-14 13:10**：**手动拉起链路成功，但「iPhone 能看到本机」这个验收前提被推翻了。**
  照 §7.3 的序列把整条栈拉起来了（`tools/awdl-stack.sh`，见下），并且在本机把业务面也验到了：
  `grep -i ':2242' /proc/net/tcp6` 命中 `state 0A`（8770 绑在 `::`，注意 **8770 十进制 = 0x2242**，
  上一轮 grep 成 `:2402` 得到的「空输出」是错的），随后
  `curl --interface wonder0 "http://[fe80::…%25wonder0]:8770/Discover"` 回
  `HTTP/1.1 200` + `Server: AirDrop/1.0` + 142 字节合法 `bplist00`
  （`ReceiverComputerName=PJZ110N`、`ReceiverMediaCapabilities={"Version": 1}`、`IsAirDropable`）。
  **但这只证明应用层和本机协议栈是通的，不证明空中往返**——那次 POST 的源地址和目标地址都在
  wonder0 上，包根本没出射频。实测计数把这一点说得很直白：`wonder0 rx_packets=0 / rx_bytes=0`，
  同期 `tx_packets=370`。所以「链路通」目前只到「网卡 up + 端口应答」为止。
  **真正的阻塞是两处结构性缺口，都不是驱动问题：**
  **① 没有 mDNS 应答器。** 全仓 `Builder::response()` 只出现在 `crates/ffi/src/browse.rs` 的
  `#[cfg(test)]` 里，用途是**解析**别人发来的应答；`Peers` 只发查询；没有 `NsdManager`、
  没有 `registerService`。AirDrop 的对端是靠「向 5353 查 `_airdrop._tcp.local`、接收方回答」
  才在设备列表里出现的，我们从不回答，于是 iPhone 那一侧永远是空的。§4.1 写的
  「主动发查询，或被动收广播」只覆盖了「我们找 iPhone」，漏了「iPhone 找我们」这一半。
  **② 没有 AWDL 数据面。** `AwdlNode` / `AwdlData` / `AwdlFrame` / `AwdlInj` / `AwdlProbe` /
  `AwdlTx`（AF_PACKET 与 `/dev/tun` 那一整套）**全都在 `legacy/` 下，当前仓库一份没有**。
  而 `legacy/.../core/MdnsResponder.java` 的类注释记着一条同型号机器上的实测：
  「35 秒里无线电收 9295 帧，`wonder0` 的接收计数只动了 8」——因为 `wonder0` 是 ethernet vif，
  驱动几乎不往里回东西，往外发的也只是普通 802.11 数据帧，对端在 MAC 之上就丢了。
  旧版因此把接收改成 tun：`AwdlNode` 用 `app_process` 开 `/dev/tun` 建 `awdltun0`，
  `ip -6 addr flush dev wonder0` 再用 /128 路由把本机 link-local 流量导进 tun，
  `MdnsResponder.DEFAULT_INTERFACE` 就是 `awdltun0`，注释明写「**永不是 wonder0**」。
  **教训**：§10 第 1 条把「驱动打包」和「iPhone 能看到本机」写成一个验收，但它们根本不是一件事。
  驱动只解决「无线电能收能发 AWDL 帧」，中间还隔着「帧要送进一个能收的接口」和「要有人回答 mDNS」
  两层。打包 37.4 MB 之前就该把这两层补齐，否则包变大了，iPhone 仍然看不见。
  本次新增 `tools/awdl-stack.sh`：从 `legacy/module/bin/control.sh` 裁出的最小加载器
  （`start|stop|link|status`），去掉 `mosey_server`、QuickShare/provider plumbing、supervisor 与
  callback bootstrap，把 `awdl-link.sh` 内联进来；保留 `boot_id` 标记（中断可续）与
  「`swap_done` 之后失败禁止回滚到出厂驱动」这条禁令。
- **2026-11-14 12:50**：三条，都是「看起来在生效、其实一直被静默丢掉」。
  **① 模糊的圆角一直是 0 —— 因为参数发给了错的对象。** `PanelGlass` 一直把
  `setBlurRadius` / `setCornerRadius` 反射调用在**系统造出来的那个 Drawable** 上；这台机器给的是
  AOSP 的 `com.android.internal.graphics.drawable.BackgroundBlurDrawable`，它**自有的公开方法只有
  `draw` / `getOpacity` / `toString`**，一个模糊 setter 都没有。真正的 setter 全在
  `com.oplus.view.ViewRootManager`（`setBlurRadius(int)`、`setCornerRadius(float,float,float,float)`、
  `setBlurParams(OplusBlurParam)`）。`invoke` 的设计是「方法不存在就返回 false 而不是抛」，于是
  六条参数全部静默失败了一整天。模糊看着还在，是因为系统给了默认半径——**只有圆角这一项是肉眼
  可见的**，症状和原因就这么隔了一层。修法：`PanelGlass` 引入 `target` 字段，把「控制对象」
  （ColorOS 是 manager，AOSP 是 Drawable 自己）和「接收 background 的对象」分开；`method()` 增加
  翻继承链 `getDeclaredMethods()` 的兜底（隐藏 API 不一定标 public，而「没标」和「不存在」在调用
  方看来都是静默失败）；`radius()` / `corners()` 改成返回 boolean，`installGlass()` 据此打
  `(rejected)` 后缀。现在日志是 `panel glass: coloros radius=112 corner=112`，两个参数都被接受。
  **教训**：反射配静默失败，等于把「参数生效了」和「方法名打错了」变成同一件事。要么打日志，
  要么第一次就把方法表 dump 出来看。
  **② 状态栏那条 160px 实心黑带。** 主题里写了 `statusBarColor=transparent` 一直是空转：
  **从 Android 15 起状态栏一律走边到边，`statusBarColor` 被平台忽略**（这台是 SDK 37）。
  真正决定这条带子画不画的是 `WindowManager.LayoutParams` 的 `DRAWS_SYSTEM_BAR_BACKGROUNDS`。
  `dumpsys window` 对照很清楚：桌面窗口 `fl=` 里有它；我们 Dialog 窗口也有；而 Activity 自己的
  窗口（`ty=BASE_APPLICATION`）**没有** → SystemUI 按默认值补黑。修法：两套 `SendPanelTheme`
  都加 `android:windowDrawsSystemBarBackgrounds=true`（样式整条选中，`values` 与 `values-night`
  必须各写一份）。验证：`dumpsys` 里该窗口 `fl=` 已含该 flag；按住面板下拖后截图，第 0–60 行
  均值约 180（壁纸蓝）、60–120 行 min 11–13（深色图标），黑带消失。
  **量化的坑**：之前那张「看起来已修好」的截图是面板顶到 0、把黑带盖住了——必须**按住面板拖开**
  再截才看得见。`input swipe` 不行，松手会回弹；要用 `input motionevent DOWN/MOVE`…截图…`UP`。
  **③ 滑动关闭的阈值从 0.18 提到 0.28。** 0.18（不到五分之一）在真机上太浅：手指往下带一下、
  想够到下面的内容，越过这条线、抬手时又带一点速度，面板就没了。改成命名常量
  `LEAVE_FRACTION = 0.28f`。甩动（速度过 `fling`）**不看**这条——短促、方向明确，没有「想够
  内容」这种解释。真机验证：慢拖 +600px 抬手 → 回弹、面板还在；慢拖 +1000px → 关闭；
  快甩 350px → 仍然关闭（`topResumedActivity` 从 `SendActivity` 变回 `launcher`）。
- **2026-11-14 11:40**：三处修正，都来自真机反馈。
  **① 跟手的平滑整个撤掉。** 先后试过固定 0.6 和自适应（>8dp 用 0.92、否则 0.45），两种都
  被否：任何平滑都让面板落后手指一截，而人对手感的第一判断就是「它跟不跟得上」。回到 1:1：
  `setTranslationY(startY + 手指位移)`。**教训**：滞后不是抖动的解药，只是把抖动换成了延迟。
  （**后续**：这一条又被推翻了。`setTranslationY` 只改渲染层，`top` 没变 → 模糊按视图 bounds
  采样的还是原来那块，背景和内容每帧都对不上；而且死区一旦去掉、位移又靠 `getY()` 读，会形成
  「面板走一像素、手指读数少一像素」的反馈回路。真正的解法是 `offsetTopAndBottom` 改真实 `top`
  + `getRawY()` + 0.5 的连续阻尼，见下方 12:xx 各条。）
  **② 抖动回归的根因**：为了不让第一帧移动卡顿，把 `setLayerType(HARDWARE)` 提前到了**按下**
  那一刻，但「停雷达呼吸」还留在第一次 MOVE 里。于是从按下到移动之间，层已经存在而雷达仍在
  每 32ms invalidate —— 层里每一次 invalidate 都要重画整块 1440×3168 的纹理，等于每秒三十次
  全屏重绘，与拖动叠加起来就是「整个界面在抖」。修法：`layer(true)` 里顺手把 `dragging` 也置
  位，按下即停雷达，还层时再唤醒。实测 89 帧、0 janky、50th 5ms / 90th 7ms。
  **③ 圆角只留一个来源。** 之前模糊 Drawable、染色层、面板背景各设了一套圆角，而
  `BackgroundBlurDrawable.setCornerRadius` 只有单一半径（这台机器上四参数那版调不通），面板要
  的却是「上圆下方」——于是顶部两个角出现两条半径不同的弧。改成 `sheet.setClipToOutline(true)`
  + 自定义 `OutlineProvider`（`Path` 上圆下方、`setConvexPath`），模糊层与染色层都不再管圆角，
  从结构上不可能再对不上。放大截图确认：一条弧、外侧壁纸清晰、无模糊溢出。
  **④ 小窗**：加 `android:resizeableActivity="false"`。从相册分享时系统会把这个页面放进自由
  窗口，那是 `resizeableActivity` 的行为，不是「因为它是 Activity」——一加互传那个页面
  （`IOSShareActivity`）**本身也是 Activity**，只是内部用 `COUIBottomSheetDialog` 承载面板；
  而那个 Dialog 的窗口是 `window.setLayout(-1,-1)` 全屏，所以它的「窗口外不模糊」靠的也不是
  窗口小，是模糊绑在视图上。
- **2026-11-14 11:00**：**模糊的作用域改对了 —— 从「窗口」挪到「面板视图」。** 之前一直在调
  `Window.setBackgroundBlurRadius`，那是整窗模糊：窗口是全屏的，所以面板以外整片屏幕一起糊，
  而且每一帧都要重采整屏。表现是「不跟手、拖动时抖」，而 `gfxinfo` 一直报 0 janky、5ms ——
  **因为那一项花的是合成线程的时间，应用线程测不到**。这是判据选错了，不是参数没调好。
  **反编译证据**（一加互传 17.2.36）：
  · `ShareActivity.V0()` 造的是 `COUIBottomSheetDialog`，随后一行
    `dialog.setBackgroundBlur(utils.s.a())`；
  · `utils.s.a()` 只回答「这个 ROM 支不支持」（`COUIMaterialStateManager.t() && !p() && n()`）；
  · `COUIBottomSheetDialog.setBackgroundBlur` 交给 `BackgroundBlurBuilder`（混淆名 `t4.b`），
    后者做的是 `ViewRootManager.getBackgroundBlurDrawable()` → 设成**某个 View 的 background**，
    再 `setBlurParams(OplusBlurParam)` / `setBlurRadius` / `setCornerRadius`。
  也就是说：模糊的范围是**那个 View 的边界**，不是窗口。于是面板之外一个像素都不糊，面板
  移动时模糊跟着走（所以它能做到「拖动也实时模糊」），面积只有面板那么大。
  对应到 AOSP：`View.getViewRootImpl().createBackgroundBlurDrawable()`（Android 12 起，
  ColorOS 只是包了一层材质参数）。
  **我们的实现**：新增 `PanelGlass`（反射，两条路）——① `com.oplus.view.ViewRootManager` 的
  `getBackgroundBlurDrawable()`（一加互传用的那条，这台机器上一定有）；② AOSP 的
  `ViewRootImpl.createBackgroundBlurDrawable()`。拿到 Drawable 后 `setBlurRadius(28dp)`、
  `setColor(页底 192/255)`、`setCornerRadius(上两角 28dp、下两角 0)`，再
  `sheet.setBackground(drawable)`。窗口级模糊整个删掉，窗口只保留透明（让圆角外透出背后）。
  拿不到就退回不透明页底，并打一行 `OPAirDrop: panel glass: unavailable`。
  **真机验证（已通过）**：日志 `panel glass: coloros radius=112 corner=112`；把面板向下拖到
  一半截图，屏幕上方露出的壁纸**清晰**（云纹可见），面板内部是模糊的深色玻璃，圆角在，
  文字清晰。拖动段 `gfxinfo`：51 帧、0 janky、50th 5ms / 90th 8ms。这就是「窗口内模糊、
  窗口外不模糊、拖动也实时」。
  **两个必须自己做的细节**：
  ① `BackgroundBlurDrawable.setColor` 在这台机器上**没有任何效果**（模糊出来了，叠色没上，
  深色主题的白字直接落在浅色壁纸上，读不清）。改成自己盖一层 `tint` View（页底 208/255，
  上圆下方形），模糊只负责「看到背后」，可读性由它保证。
  ② 跟手平滑改成自适应：手指一次移动超过 8dp 视为在拖，系数 0.92（几乎 1:1，滞后约 8%）；
  不到 8dp 视为手抖，系数 0.45。固定系数做不到两件事——大了抖，小了黏。
  **教训**：判据要选对测点。跨窗口模糊的成本在合成线程，`gfxinfo` 只量应用线程；「0 janky
  但用户觉得卡」这个矛盾本身就说明测错了地方。
- **2026-11-14 03:30**：发送面板改成**从底边上滑的一块玻璃**，并修掉三个只有真机才看得出的问题。
  **形态**：`SendPanelTheme`（`windowIsTranslucent` + 透明窗口背景 + `@anim/none` 的零时长
  窗口动画）+ `Window.setBackgroundBlurRadius(28dp)` —— 跨窗口实时模糊，因为这一页背后是
  **别人的**窗口（一加互传的分享面板或主屏），`RealtimeBlurView` 拿不到它的 `RenderNode`。
  底色 `(S.bg & 0x00FFFFFF) | alpha`，浅色 224/255、深色 230/255（比导航胶囊的 240 更透：
  那一块背后是同色的卡片内容，240 还看得出层次；这一块背后是壁纸，遮到 240 就只剩一块白板）。
  **上滑**：`post` 后把 `translationY` 设成面板高度再动画回 0（340ms，`DecelerateInterpolator(1.6f)`）。
  **下滑**：`Sheet extends FrameLayout` 重写 `onInterceptTouchEvent`，`dy > slop && dy > dx`
  才接管；`onTouchEvent` 里 1:1 跟手，松手按「位移 > 18% 屏高或速度 > 3× 最小甩速」决定
  滑出（220ms）还是回弹（240ms）。返回键走同一条滑出。
  **深浅色不重建**：`configChanges` 带 `uiMode`，`onConfigurationChanged` 里换配色表 + `rebuild()`
  重画视图树；进度存在 `progressOf` 字段里，视图只是投影，所以重组后环还在原处。
  **中部**：`stage.setGravity(Gravity.CENTER)`，雷达与它下面那行字作为一个整体居中。
  三个真机坑（都记进 6.4）：
  ① `setBackgroundBlurRadius` 在 `setContentView` **之前**调用会 NPE —— 平台上那条路径直接对
  `DecorView` 调用同名方法，而 DecorView 是惰性创建的；先是整个 Activity 起不来。
  ② `onPreDraw` 里 `return false`（想取消首帧、下一帧才是动画第一帧）会把页面停在**一帧都没有**
  的状态：窗口有 surface、`HAS_DRAWN`，屏幕上只有背后的桌面，动画的 invalidate 被吞掉。改用
  `INVISIBLE` + `post`：窗口本来就透明，先不可见一帧看不出任何东西。
  ③ **文字抖动与掉帧的真因不是模糊**：`setTranslationY` 会让整棵子树重绘，1440×3168 上的全屏
  圆角描边路径 + 全部文字每帧重新栅格化，文字落在不同的亚像素位置上，抗锯齿随之变化——读起来
  就是「字在抖」。三个修法叠起来：
  · 移动期提升为硬件层（`setLayerType(HARDWARE)`，停手即还 `NONE`，常驻会与窗口模糊抢
  GPU 内存）；层建好之后位移**故意不取整**——小数位移只是把纹理放到下一个十六分之一像素上，
  由合成器插值；取整反而制造 1 像素一级的台阶，手指每帧移动不到一像素时那就是「整个窗口在抖」；
  · 拖动期间**停掉雷达的 32ms 呼吸**：层里的每一次 invalidate 都要把整块纹理重画一遍，
  一秒三十次全屏重绘，一边还在跟手；
  · `ACTION_DOWN` 里 `animate().cancel()`：上一次的回弹/上滑若还在跑，它每帧写的
  `translationY` 会和手指写的那个互相覆盖——同一帧先被拉回再被推开，读起来就是上下摆。
  实测：`dumpsys gfxinfo` 拖行 145 帧、**0 janky**、50th 5ms / 90th 7ms / 99th 7ms、
  GPU 5–6ms。**模糊全程保持开启**（用户要求），没有为此付掉帧。
- **2026-11-14 02:50**：进度回调补上**阶段**。只报字节数不够用：`/Ask` 发出到它 `200` 回来
  这一段可能十几秒，而进度始终 0%——那不是「慢」，那是「对面正在决定」。现在
  `ProgressCallback` 带 `phase`：`READY`（连上、对面收）/ `WAITING`（报价已发、等对面点）/
  `SENDING` / `DONE`，JNI 侧签名 `(IJJ)V`，`SendActivity` 按阶段说话。260 测试、clippy 零
  warning、clean APK **889 653 字节**。
- **2026-11-14 02:45**：**重建这份任务书**。它和两个源文件在同一天被「整篇写回」的脚本清空过；
  代码靠 git 救回，文档只能重建。本版把「怎么和苹果设备通信」独立成第 4 节、把影响速度的参数
  收进 5.3、把三次事故写进 6.5 作为禁令。
- **2026-11-14 02:20**：发送进度接通——`opairdrop_send_with_progress` 按「512 KiB 或到总数」
  回调，计数挂在**读文件**那一层（DVZip 会压缩，按输出字节算的环会乱跳）。JNI 侧
  `Native.sendWithProgress` + `Progress` 接口，`SendActivity` 现在显示「正在发送 N%」。
- **2026-11-14 01:00**：真机验证四件事（APK 起得来、窗口→前台服务成立、8770 上 `/Discover`
  有合法应答、分享面板出现「发送到 iPhone」）。修掉两个只有真机才暴露的问题：钩子装错进程、
  窗口过期后服务自我续命。
- **2026-11-14 00:45**：按用户纠正**删除「iPhone（10 分钟）」第 4 项**——原本就定的是跟随
  系统「所有人」，我把「通知栏面板也要接入」误解成「加一行」。同时删掉 `PanelRow`、
  `PanelHeight`、`ReceiveWindow`、`WindowActivity` 与 `opairdrop_receive_until`。
- **2026-11-13 深夜**：发送链路落地（`Peers` / `ShareIntake` / `SendActivity` / `PanelHook`），
  协议层补上「拒绝」（`Receiver::accept` + 503）。
- **2026-11-13 晚**：Android 应用从零建起并首次出包；设置页改为遍历 Rust 表渲染。
