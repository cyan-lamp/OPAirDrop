# OPAirDrop

为 Android 手机接入 Apple AirDrop 的独立应用。以普通 APK 安装，不改系统分区：需要的只有 root、一次驱动安装，以及随包 LSPosed 模块所要求的框架环境。

协议栈自行实现：mDNS 发现、BLE 广播唤起、TLS 1.3 握手与证书指纹钉住、`/Discover` `/Ask` `/Upload` 三个端点、cpio 归档与 DVZip 分块，全部在手机本地完成，不经过一加互传、Quick Share、Mosey，也不依赖任何 Google 服务。

## 组成

三层各管一段：Java 负责入口与界面，Rust 负责协议、编码与 AWDL 调度，内核侧以 `insmod` 顶替原厂 WLAN 驱动取得 AWDL 数据面。三层之间只传字节与状态码，Java 侧没有任何 bplist、cpio 或 HTTP 的字节操作。

**LSPosed 是必要项。** 流体云卡片的自动展开由包内模块（作用域 `com.android.systemui` 与 `system`）完成：它把系统算出的 `remindType` 从 0 抬到 11，并放行「本应用在前台」这道拦截器。未启用模块时收发链路仍然通，但卡片停在胶囊上，且应用在前台时邀请卡会被系统整张丢弃——这不是受支持的形态。按设计形态使用须安装 LSPosed、启用本应用模块，并授予上述两个作用域；首页「环境」一栏会报出框架版本与作用域是否齐全。

## 功能

- **发送文件**：从系统分享面板选「发送到 iPhone」，或在首页点「发送文件」直接选文件（可多选）。面板列出附近 Apple 设备，点一下即发；对方确认后进度环按真实字节走，卡上那颗【取消发送】按下即断，页面上有同一颗。
- **接收文件**：打开后前台常驻，8770 端口在听、mDNS 与 BLE 广播同时在场，不必停在任一页面。iPhone 发起时通知上给「接受 / 拒绝」两个键，60 秒无人应答按放弃处理（`files/settings.conf` 的 `receive.decision_timeout_ms` 可改，5 秒到 5 分钟）；接受之后进度卡上那颗键变成【取消接收】。
- **控制中心磁贴**：磁贴上的【AirDrop】就是接收开关，与首页「接收文件」那一行同一份状态。开则服务在跑、端口在听、广播在发；关则三样一起停，没有到期时间。
- **实时通知**：Android 16 起引入的实时通知跟着真实字节走，ColorOS 把它画在流体云的位置——形态是胶囊，点开是通知栏里那张卡。收完切成结果卡（可直接打开落盘目录），失败停在真实百分比而不是拉到满格，邀请给「接受 / 拒绝」两键。卡片走 `live_card_2` 渠道并申请提升为常驻，应用退到后台也不会消失。
- **内核驱动随包**：APK 自带四个内核模块、`wonder-ven` 与拉起脚本，功能页一键安装／卸载；装载前逐个校验 SHA-256，装完把脚本回执写在卡片上，卸载即恢复原厂驱动与网络环境。
- **按类型落盘**：照片与视频进 `DCIM/AirDrop`（相册直接扫得到），其余进 `Download/AirDrop`；也可指定单一目录。同名不覆盖，默认加编号另存。
- **失败有话说**：对方拒绝、邀请过期、身份指纹不符、文件存不下，各是一句话，而不是笼统的「传输失败」。
- **三页界面**：首页给状态（传输、环境、设备信息），功能页给动作（驱动、射频、保存位置、设备显示），设置页只剩诊断与关于。超时与上限不在界面上，改 `files/settings.conf` 即生效。设备名留空时用系统设备名对外自我介绍。

## 适配基线

| 项目 | 当前配置 |
| --- | --- |
| 验证机型 | 一加 13（PJZ110），ColorOS V17.0.0 |
| Android | 17（API 37）；`minSdk 35`、`targetSdk 37`、`compileSdk 37` |
| 架构 | `arm64-v8a` |
| Root | 必需。KernelSU、KernelSU Next、APatch、Magisk 均可识别，首页报出管理器与版本 |
| 框架 | 必需。LSPosed（libxposed API 102）加本应用模块，作用域 `com.android.systemui`、`system` |
| 无线 | Qualcomm WLAN 平台；AWDL 固定信道 149（5745 MHz），20 MHz、HT20、双空间流 |
| 蓝牙 | 需要 BLE 广播：iPhone 先要听到这条广播，才会把 AWDL 上的 AirDrop 服务拉起来 |
| 版本 | 1.0.0（versionCode 10000） |

驱动与射频是硬前提：接收要 `wonder0` 与 `awdltun0` 都在、8770 在听；发送要 `awdltun0` 上的组播出得去（mDNS 走这条隧道，不是 `wonder0`）。首页「环境」给出 root 状态，功能页给出驱动状态（未安装 / 已就绪 / 一半 / 失败），两处都不靠猜。

三组权限各管一件事。**首次启动一次问全**，只问从没问过的：问过又被拒的，系统不再弹框，缺口交给首页与磁贴上的状态胶囊和副标题说明。

- **通知**：接收状态、邀请确认、进度与结果。未授时接收端仍在听，但没人能应答邀请，等满 60 秒按放弃处理。
- **附近的设备**（蓝牙广播与连接）：用 BLE 广播叫醒 iPhone。`BLUETOOTH_ADVERTISE` 决定能否外发，`BLUETOOTH_CONNECT` 决定能否先看自己的适配器，缺一条广播一次都不会注册。
- **网络与组播**：发现与传输，安装即授予。

## 驱动

随包的四个内核模块不是原厂的，也不是另找一个版本替换：**相对原厂只动该动的地方，其余字节保持原样**。每次装载前都验一遍，不依赖「看着能跑」。

| 模块 | 改了什么 | 硬读数 |
| --- | --- | --- |
| `qca_cld3_peach_v2` | 开出一条 passthru 通路，把 802.11 帧的收发从固件交回主机 | 符号 CRC 588/588 与原厂逐条相同；新增 12 条依赖全在 KMI 白名单；导出 2 个符号给 provider；62 个 `passthru_*` 参数（原厂 6 个参数，无一 passthru） |
| `mac80211` | 把 minstrel_ht 编进模块本身 | 原厂 `sun_perf.config` 未开速率控制，`wonder.ko` 因此注册不上（`Failed to select rate control algorithm` / `Ret: -2`）；RC 符号 317→319，导出 136 条不变 |
| `wonder` | 新模块，`wonder0` 的驱动 | 装载后创建 IBSS `wonder0`：ch149 / 20 MHz / BSSID `00:25:00:ff:94:73` |
| `opairshare_wonder_provider` | 新模块，把 qca 导出的两个符号接到 wonder 上 | 缺它 `wonder_probe` 找不到 wiphy，`insmod wonder` 报「registered no wiphy」 |

四颗的 `vermagic` 与原厂逐字符相同（`6.6.118-android15-8-o-4k SMP preempt mod_unload modversions aarch64`）。顶掉原厂而**不是**替换文件，靠 `insmod` 加符号 CRC 一致：`/vendor` 分毫未动，`rmmod` 或重启即回原厂，这是这条路线唯一的回滚保证。

驱动字节的源码就在本仓库 `kernel/source/`：上游覆盖层（46 个文件，`source-map.json` 逐条给出平台树落点）、11 个编号补丁，以及构建脚本。载荷清单、每个文件的 sha256、重建进度写在 [kernel/README.md](kernel/README.md)。现状一句话：**未打补丁的基线已从官方源码复现**（1359/1359 actions 全绿，`__versions` 588/588 与原厂一致，真机上 `rmmod` 原厂 → `insmod` 自建零报错）；**打过补丁的这一颗也已逐字节复现**——`pristine HEAD + 覆盖层 + 11 个补丁` 与实际构建树的 115 个改动文件比，113 个逐字节相同、0 个不同，余下 2 个是归档为「预期不复现」的死文件。判据命令是 `bash tools/kernel/verify-reproduce.sh`。

吞吐参数跟着脚本走，不留在手输历史里。`tools/awdl-stack.sh` 把整组吞吐调优直接拼进 `insmod`，冷启动后第一帧即按这些数字发送；链路起来后再写一遍并逐项读回，覆盖「本 boot 已换过栈」「只跑 `link`」「旧脚本留下的模块」三种路径。读回不符打印 `TUNING_MISMATCH`，而不是安静地跑慢。其中 `nss=2` 是关键项：双流实测 3.2 MB/s，脚本此前从未设过它，更早的实验都在单流上半速空口。

## 使用入口

1. 安装 APK 并打开，一次问全三条权限：通知、附近的设备（蓝牙广播与连接）。
2. 看首页「环境 → root 权限」：显示「已获取」，副标题是授权管理器与版本；显示「未获取」则先去管理器授权。
3. 进功能页 → 驱动 →【安装】。约半分钟，中途 Wi-Fi 会断一下（驱动不能在 Wi-Fi 活着时替换）；装好右边胶囊变「已就绪」。
4. 在 LSPosed 里启用本应用模块，作用域勾选「系统界面」与「系统」；回首页确认「环境 → 框架」报出 LSPosed 版本且作用域齐全，缺失时点那一行按提示申请。
5. 在控制中心编辑页把【AirDrop】拖进快捷开关并点亮，或回首页点「接收文件」那一行的胶囊。
6. iPhone 侧把 AirDrop 设为「所有人」（控制中心里那项，10 分钟有效），屏幕保持解锁亮着；从相册或文件分享，在列表里选本机，本机通知上按「接受」。

### 接收

接收端一次服务一台设备，第二台排队，不会两路同时往同一个目录写。接受之后进度同时出现在通知与实时通知上；收完的结果卡写清文件名、大小与落盘目录。拒绝与超时对发送端都是失败，在本机是两句话：拒绝是「已拒绝」，超时是「邀请已过期」——没人看见这条邀请，不等于他拒绝。

### 发送

两条入口、一条路径：系统分享面板，或首页「发送文件」的文件选择器，选完都交给同一个发送页。面板先显示「正在搜索附近设备……」，设备出现后点头像，等对方在 iPhone 上确认，环走完写「发送完成」。发送不需要本机处于接收状态，也不需要打开接收开关。

### 保存位置

| 收到的东西 | 落在哪 |
| --- | --- |
| 照片、视频 | `DCIM/AirDrop` |
| 其余：文档、压缩包、安装包、无扩展名文件 | `Download/AirDrop` |
| 功能页「接收文件保存位置」填了目录 | 全部进那一个目录，不再分类型 |

同名文件默认并排留两份（`IMG_0001.jpg` 与 `IMG_0001 (1).jpg`）。卡片上报的是落盘后回查到的真名，不是提交时的名字。

## 已知限制

- **只在一加 13 上验证过。** 换机型要换随包驱动与补丁，改一个设置不够。
- **Wi-Fi 共享射频只在 AP 位于 149 信道时能收发。** AWDL 的 tap 被驱动钉在 149（`opairshare_pas_rx_fixed_channel()` 对其他频率直接 `-EOPNOTSUPP`，该数字在驱动里硬编码四处），而 iPhone 的 AWDL 跟随它自己连着的 AP。AP 在别的 5G 信道时两边永远不同频：Wi-Fi 上得去，AirDrop 看不见人。根治需让 tap 跟随 STA 信道，属驱动改动，尚未实现；临时办法是把路由器 5G 定到 149。
- **iPhone 侧有两件事要做**：控制中心把 AirDrop 设为「所有人」（10 分钟有效），屏幕保持解锁亮着。实测从点开分享面板到本机出现在列表约 1 秒。
- **速度由对端决定，不是本机的上限。** 发送稳态 **3.24 MB/s**（24.7 MB 视频后 6 秒；峰值 3.48，全程 2.59）；单流天花板 5.05 MB/s，算式是 iPhone 通告的 89 KB 接收窗口 ÷ 每聚合往返 17.2 ms，实测 3.24 MB/s 只用掉其中 64%，差额在 TCP 慢启动与 1.5% 重传，不在空口。接收随对端节拍波动，两阶段速率切换后量到 2.09 MB/s 均值 / 3.68 MB/s 峰值。
- **10 MB/s 到不了，也不是靠改信道宽。** AWDL 的社交信道固定 20 MHz @ 5745，加宽会掉出对端协商的信道；瓶颈是对端的通告窗口，不是带宽。这条路线现实顶在 8~9 MB/s 一带，且必须多流摊开。
- **每次重启后要重装一次驱动**（约半分钟）。应用没有开机自启，重启后需重新打开接收；重装会把随包吞吐参数一并带回来。
- **证书指纹「相符也照常发」那一支还没在真实 iPhone 上走过。**「不符就拒」已用错误指纹的握手验过，能给出可读原因。
- 多文件、超大文件、锁屏、传输中断电等场景尚未全覆盖；尚未发布正式 Release，也未上架任何应用商店。

## 项目结构

```text
repo-src/
├── crates/            Rust 协议栈，八个 crate，协议知识只在这里
│   ├── bplist/        Apple 二进制 plist 读写
│   ├── cpio/          cpio odc/newc、DVZip 分块、gzip 识别
│   ├── mdns/          DNS 报文编解码
│   ├── http/          双向流式 HTTP/1.1 解析
│   ├── awdl/          AWDL 数据面、/dev/tun 与 AF_PACKET、awdld 守护进程
│   ├── airdrop/       三端点语义、客户端、服务端、收件箱
│   ├── settings/      参数表：键、区间、文案三合一
│   └── ffi/           C ABI 与 JNI，Java 侧唯一入口
├── android/           Android 应用
│   ├── app/           三页界面、发送页、接收服务、磁贴、驱动安装
│   └── build.sh       一条命令出 APK：先 Rust，后 Gradle
├── kernel/            随包内核模块、驱动源码覆盖层与拉起脚本
├── tools/             链路拉起、抓包与真机自测脚本；tools/pcap/ 是读包分析脚本
├── fuzz/              四个解析器的 libFuzzer 入口
├── CHANGELOG.md
└── LICENSE
```

界面没有 XML 布局，全部代码绘制。`dependencies` 只有三条：`compileOnly 'io.github.libxposed:api:102.0.0'`（框架在目标进程注入，不进包）、`implementation 'io.github.libxposed:service:102.0.0'`（读框架版本与作用域，须进包）、`testImplementation junit`。没有 AndroidX、没有 Kotlin，APK 里只有框架类、Rust 库与内核载荷。

## 开发入口

在仓库根目录：

```sh
# 出包：先交叉编译 aarch64 的 Rust，再打 APK
bash android/build.sh assembleDebug
bash android/build.sh assembleRelease
bash android/build.sh clean

# 全量测试
cargo test --workspace          # 464 passed / 0 failed / 1 ignored
bash crates/ffi/jvm/check.sh    # 395 条 ok，桌面 JVM + -Xcheck:jni

# 门禁
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
```

JDK、SDK 与 Gradle 放在 `tools/` 下，脚本按名字取而不查 `PATH`，可用 `JAVA_HOME`、`GRADLE_BIN`、`CARGO_BIN` 覆盖。真机脚本在 `tools/`：`awdl-stack.sh` 拉起链路，`capture.sh` 抓隧道上的包，`tap.sh` 在 802.11 侧再抓一份（两者一比才分得清「对端没发」与「本端收丢」），`awdld-probe.sh` 看守护进程状态，`hans_guard.sh` 是设备侧断网守护。

日志默认按 `debug` 记（`log.level`），一天一个文件写在 `Documents/AirDrop/airdrop-YYYY-MM-DD.log`；嫌吵可在设置页调回 `info`。

## 文档

- [更新日志](CHANGELOG.md)：每个版本做了什么。
- [内核载荷与驱动源码](kernel/README.md)：四个模块的来源、改动与复现方法。
- [许可证](LICENSE)：GPL-2.0 全文。

开发过程中的判定依据、被证伪的结论与实测数字记录在开发机的本地任务书 `Plan.md`（不入版本库）。

## 项目标识

| 用途 | 标识 |
| --- | --- |
| 应用名称 | `OPAirDrop` |
| 包名 | `com.oshare.airdrop` |
| 分享面板条目 | 发送到 iPhone |
| 控制中心磁贴 | AirDrop |
| 原生库 | `libopairdrop_ffi.so` |
| 守护进程 | `awdld` |
| 接收端口 | 8770 |
| 无线接口 | `wonder0`（AWDL）/ `awdltun0`（数据面） |
| 设置文件 | `files/settings.conf` |
| 驱动落点 | `/data/local/tmp/opairdrop` |

## 开源许可

项目采用 **GPL-2.0-only**：允许使用、修改与再分发；分发代码或修改版时保留版权与许可声明，并按协议提供对应源码。全文见 [LICENSE](LICENSE)。

随包的四个内核模块与 `wonder-ven` 来自 Qualcomm、Oplus 原厂固件与社区补丁，版权归各自作者，不在本项目的 GPL 覆盖范围内；分发这些二进制前请自行确认来源与授权。

## 参考

设计路线参考 [t4t5/owly](https://github.com/t4t5/owly) 的 Rust 化实现；端点行为（尤其是拒绝时的应答码）与 [OpenDrop](https://github.com/seemoo-lab/opendrop) 对齐。各项目的贡献归原作者所有。

## 独立项目说明

OPAirDrop 是独立项目，与 **Apple、Google、Oplus（OPPO、一加、realme）不存在隶属、官方合作或背书关系**。

文中涉及的品牌、产品名称与商标归各自权利人所有，仅用于说明兼容对象与运行环境。AirDrop、iPhone、Mac 是 Apple Inc. 的商标；ColorOS、OPPO、一加属 Oplus。项目代码的开源许可与这些品牌、产品及商标的权属分别适用。
