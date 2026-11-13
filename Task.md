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

AirDrop 用自签证书，TXT 记录里有 `phash`（证书指纹）。**我们目前接受任何证书**，把「是不是
那台设备」交给对面手机上的一次人工确认。按 `phash` 校验还没做；写在这里是为了不被当成已完成。

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

## 9. 当前进度（2026-11-14 02:45）

### 已完成并验证

| 项 | 证据 |
| --- | --- |
| Rust 七 crate | 7456 行，`cargo test --workspace` **260 passed**，clippy 零 warning |
| 接收端在真机上应答 | `POST /Discover` → `HTTP/1.1 200` + `Server: AirDrop/1.0` + 合法 bplist（`ReceiverComputerName=PJZ110`、`IsAirDropable`、`{"Version":1}`） |
| 前台服务 | `oshare_state=1` → `ReceiveService` 以 `isForeground=true types=0x10` 起来、8770 在听；`=0` → 服务与端口都归零 |
| 分享面板注入 | 真机确认：一加互传分享面板的设备列表里出现「发送到 iPhone」 |
| 发送链路 | `Peers`（mDNS 浏览）+ `ShareIntake`（URI→cache）+ `SendActivity`（TLS + 三请求 + **真实进度**） |
| 接收确认 | `/Ask` 读完后问一次（通知两键，30 秒超时按拒绝）；拒绝回 503 且不弹确认 |
| 六项 UI 修改 | 首页开场白卡删除、底栏三只图标重画、刷新按钮删除、设置页跟手动画、回弹以被拉边为轴、震动权限 |

### 明确未完成

- **驱动打包**（7.2 形态）。不做这条，真机永远只到「shell 打本机」。
- **流体云卡片**：已定为「承载真实传输」。信道：offer 走广播（我们 → 互传进程），决定走
  `Settings.System`（互传进程 → 我们，因为普通应用写不了它）。卡片本体未写。
- **自有发送面板**：要仿 `IOSShareActivity`（全屏、圆形返回键 + 「与 iPhone 互传」+「本设备：×」、
  中部雷达喻体、底部说明卡），浅/深两套、弹出动画、**点头像才出**蓝色「等待对方接受」、
  接受后进度绕头像圆环走。现有 `SendActivity` 只是临时形态。
- **通知栏与流体云显示发送进度**。
- **`phash` 证书校验**。
- **设置表里 8 行未接到读值处**（`receive.directory`、`device.name`、各超时、`http.response_limit` 等）。
- `git` 已初始化并有提交，但首个提交晚于事故，历史不含更早状态。

---

## 10. 下一步（按顺序，每条可验收）

| # | 任务 | 验收 |
| --- | --- | --- |
| 1 | 驱动打包：`assets/kernel/` + `su -c insmod` + boot_id 标记 | 冷启动后 `wonder0` up、iPhone 能看到本机 |
| 2 | 自有发送面板（仿 `IOSShareActivity`） | 与参照同构；浅/深；弹出动画；环状进度随真实字节走 |
| 3 | 通知栏 + 流体云显示发送进度 | 与面板同一份进度，不各算一套 |
| 4 | 流体云确认卡（接收） | 有文件发过来时卡片出现，接受/拒绝回写，结束自毁 |
| 5 | `phash` 校验 | 指纹不符时不发，且给出可读原因 |
| 6 | 8 行设置接线 | 每接一行删一个 `DEFAULT_*` 常量；键名只出现在 `settings.rs` |

---

## 11. 度量与验收基线

| 指标 | 值 | 说明 |
| --- | --- | --- |
| 单元 + 集成测试 | **260 passed** | 无设备；clippy 零 warning |
| JVM 断言 | **179 条** | `bash crates/ffi/jvm/check.sh`，桌面 JVM，不是真机 |
| Rust 行数 | 7456 | 七 crate 合计 |
| Java 行数 | 8089 | 20 个类：三页 + 发送 + 确认 + 服务 + 钩子 |
| 真机库 | `libopairdrop_ffi.so` **652 616 字节** | `aarch64-linux-android`，linker 35 |
| APK | **885 949 字节**（clean） | 增量打包会虚高到 1.5 MB，见 6.4 |
| 接收吞吐 | 80–88 KB/s | 受对端 31.2% 占空比限制，**不是 KPI** |
| 发送吞吐 | 1.06–1.13 MB/s | 同上 |

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
