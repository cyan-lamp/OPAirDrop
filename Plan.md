# OPAirDrop 自研任务书

> **这份文件记录的是「当前成立的结论」，而非按时间顺序的记录。**
> 2026-11-18 做过一次整体重构：逐轮排查的叙述、已作废结论的原文、抓包中间态，全部移到
> `Task-archive-20261118.md`。同一天稍后又做了一次合并：归档里**还有用**的那些（拒绝与超时语义、
> 自测环路的真上限、速率的唯一读法、Android 运行时与构建坑、80 MHz 的三道闸、内核源码构建链的现状）
> 已经并进本文，归档文件随即删除——**本文现在是唯一的任务书**。本文只写三样东西：
> **现在成立的事实、可复现的数字、下一步做什么**。
> 被推翻的旧结论不保留原文，只留一行「已排除 + 为什么」，避免下次又照着过时的结论走。
>
> 最后更新：**2026-11-22 15:5x**

---

## 0. 一分钟状态

| 方向 | 现状 | 数字 | 瓶颈 / 天花板 |
| --- | --- | --- | --- |
| **发送**（本机 → iPhone） | ✅ 可用，已调优（`bbr`+`fq` 固化进脚本） | **3.24 MB/s 稳态**（283 PPDU/s × 11454 B = 3.24，实测吻合；用户最新实测 3.5+） | 空口占用仅 1.7%，不缺带宽 |
| **接收**（iPhone → 本机） | ✅ 两阶段速率切换已固化（2026-11-22 22:2x） | **传输态 2.09 MB/s 均值 / 3.68 MB/s 峰值；用户实测 4 MB/s**（切档前 455 KB/s） | 发现期必须 HT20/1SS/MCS7（对端只持 20 MHz 接收机），传输态 `rate 2 2 1 2 9` = VHT80/2SS/MCS9；见变更日志 22:2x |
| 协议互通 | ✅ 两机互相可见（冷重启后须 `AWDL_PHY=2 AWDL_MCS=7 boot`） | 发现 / 询问 / 上传 + 接收 | — |
| 驱动形态 | ✅ insmod 顶替原厂，不动 `/vendor`；2026-11-22 起 qca 由我们自己从源码编 | 现役 ko 20 039 576 B / sha `3fdf99e9…` | 重启回原厂 |

**一句话（2026-11-22 最新）**：两机已互通、发送 3.5+ MB/s 正常；**接收掉速的根因是
aux monitor vdev1 被源码写死在 20 MHz/11n**——它负责把 iPhone 的帧抓下来转发给 wondertap0，
20 MHz 只听得到 iPhone 80 MHz 帧的一个切片（实测 41% incomplete），而主 vdev0 是 EHT80。
已改 `wlan_hdd_opairshare_aux_rx.h`（新增 `passthru_aux_wide`，wide 时 monitor 用 EHT80），
重编驱动并装进 APK；真机验证 vdev0/vdev1 均 `chanmode 26`、`hang=0` 不崩固件。
**待用户手点启动后复测接收吞吐**（判据：RX 回到 MB/s 量级、`incomplete` 下降）。

**历史结论归档**（2026-11-22 早些时候，仍成立）：四个叠加错误配置——`phy=2` 是 HT 不是 VHT、
BA 响应 A-MSDU Supported 曾写 0、`amsdu_max/bytes` 是 HT 天花板、`wonder-ven feed` 固定速率
锁 HT20/1SS/MCS7——已逐项修；`§3.5` 的「10 MB/s 不可达」作废。**发现阶段必须 20 MHz**（iPhone
非传输态 slot map 是 8×149+1×6、只持 20 MHz；固定速率发 80 MHz PPDU 会两机互不可见），
所以当前配置是**信道 80 MHz（EHT80）+ 固定速率 HT20/1SS/MCS7**——发得保守、听得宽，详见 §3.6。

---

## 1. 目标与边界

在 `D:\Downloads\project\repo-src` 里，对 `repo-v2` / `legacy/` 那套 Java 实现做**全盘
Rust 重构**，参考 [t4t5/owly](https://github.com/t4t5/owly) 的 Rust 化路线。

1. **不再依赖 KernelSU 模块形态**，改由「普通 APK + 一次 root 动作（`insmod`）」承担；
2. 起点是 `repo-v2` 的源码，先复制过来再做减法；
3. **剔除所有 QuickShare / Mosey 相关源码**（共 82 个文件，按 `legacy/` 里 `AirDropServer`、`Mosey*`、
   `Nearby*`、`ShareMode.QUICKSHARE` 的引用闭包确定；新仓库从零写，天然不含，`legacy/` 只作只读参考、
   不进构建）；
4. 优化全部代码，去掉重复与多余；
5. 每一个构思、问题、解决方法都记在这里，避免下次忘掉。

「可用」的定义是三条同时成立：**能被 iPhone 找到、能收下它发的文件、能把文件发给它**。

### 硬约束（不可协商）

- 设备**未解锁**、只有临时 root；**不动 `boot.img`**，只走 `insmod`；`/vendor` 分毫不动；
  `phy=0` 是禁区。
- 所有调优必须能在**重启后自动恢复**——这就是 `tools/awdl-stack.sh` 把参数写在 `insmod`
  行里、并额外加一处运行时回读校验的原因（§3.1）。

---

## 2. 架构：三层，各管一段

```
┌─ Java（App）────────────────── 入口与界面，一行协议代码都没有
├─ Rust（crates/）────────────── 协议、编码、AWDL 调度、awdld 守护进程
└─ 内核（insmod 四个 .ko）────── wonder.ko + 打过补丁的 qca_cld3_peach_v2.ko
```

| crate | 职责 |
| --- | --- |
| `bplist` | Apple 二进制 plist（`bplist00`）读写 |
| `cpio` | cpio `odc`/`newc` + DVZip 块编解码 + gzip 识别 |
| `mdns` | DNS 报文编解码（名字压缩、`\.` 转义、TXT） |
| `http` | 双向流式解析、`Content-Length`/chunked、`100-continue` |
| `awdl` | 16 槽调度、PSF/MIF、选举、帧编解码、`/dev/tun`、`bin/awdld` |
| `airdrop` | 三端点语义、客户端、服务端、收件箱 |
| `settings` | 参数表：常量 + 区间 + 文案三合一 |
| `ffi` | C ABI + JNI，Android 唯一入口 |

三条硬规矩：

- **协议知识只在 Rust 里**：Java 侧没有一处 bplist / cpio / HTTP 字节操作；
- **跨边界只传字节与标量**：不传 `Map`、不传 `String[]`；
- **失败要带原因**：失败时把人类可读原因放进同一个 out-buffer，状态码只决定异常类型。

### 2.1 模块路线：第一次怎么坏的，第二次怎么重来的

**当前形态（2026-11-19 起）**：APK 里多了一个**跑在 SystemUI 进程**的入口类 `FluidModule`，声明在
`src/main/resources/META-INF/xposed/` 下，`dependencies` 里只有一行 `compileOnly 'io.github.libxposed:api:102.0.0'`。
它只做一件事：把 `remindType` 从 `0` 抬到 `11`。下面先说第一次为什么坏，再说第二次和第一次差在哪。

#### 第一次（2026-11-18 05:20–08:40，已裁）：绕前置抑制

**曾经有过什么**：为了让流体云卡片强行摊开，在 SystemUI 进程里钩住流体云插件的 `AppFrontInterceptor`
（混淆名 `com.oplus.systemui.plugins.seedling.data.interceptor.a`），绕开它那条「通知包名等于当前前台
应用就把整条卡丢掉」的判定。为它写过 `FluidModule`、编译期存根 API、以及一个很讲究的钩点：

- libxposed 的 API 类**绝不能打进 APK**——模块自己的 ClassLoader 会先加载 APK 里那份没有实现的存根，
  `hook()` 立刻 `AbstractMethodError`。做法是 `compileOnly` + `compileStubApi`/`stubApiJar` 两个任务编出
  一个只给编译用的 jar，不进 dex。
- 入口类跑在 SystemUI 进程，**那个进程没有本应用的 native 库**，只要它引用到 `App`/`LiveCard` 之类，
  类初始化就会把 Rust 那套拉进来——所以它全部用 JDK 反射，零内部依赖。
- 钩点不能是 `param.getDefaultClassLoader()`：插件由 `PluginSeedling` 用独立 `DexClassLoader` 从
  `/system_ext/app/SystemUIPlugin/SystemUIPlugin.apk` 加载，自己 new 一个读同一个 APK 得到的是
  **另一个 Class 对象**，钩不命中。只能钩 `ClassLoader.loadClass(String)` 的返回值，等插件自己加载出来。

**为什么裁掉**：真机上它确实让卡片展开了（日志 `front-suppress bypassed: com.oshare.airdrop -> false`），
但代价是**通知中心里不显示这条流体云、点空白区域不收缩**——钩子改的是插件的数据模型，不是它的生命周期。
拿两个新 bug 换一个展开，不划算。

**连带清单**（第一次的都已删净）：`src/stubApi/`、`compileStubApi`/`stubApiJar`、`SendNotice`（只服务旧发送侧文案）。
那次是同名 `FluidModule` 被整个删掉，**2026-11-19 这个是重写的，不是把旧的捡回来**。

#### 第二次（2026-11-19 起）：只改一个返回值，不碰生命周期

第一次的教训一句话：**钩子改的是插件的数据模型，改不了它的生命周期**，所以那刀落在拦截器上，
砍掉的是整条卡的入场券。第二次换了个更靠下的位置，只动**入参里那个整数**：

| 项 | 内容 |
| --- | --- |
| 钩点 | `com.oplus.systemui.statusbar.notification.livealert.data.repository.OplusLiveAlertNotificationsRepository` 的 `access$entryToReminderType(?, NotificationEntry, boolean) → int` |
| 改动 | 返回 `0`（`NORMAL`）时改成 `11`（`STRONG_SHORT`）；其他值原样透传 |
| 边界 | 只对 `chain.getArg(1).getSbn().getPackageName() == com.oshare.airdrop` 生效 |
| 为什么是这个函数 | 它就是 `SeedlingCardOptions.remindType` 的来源：`data/interactor/n` 的 `remindLevel` = `origin.d()` = 反序列化出的 JSON `remindType`。钩 0→11 等于**在源头改字段**，不伪造事件、不硬调 `onExpand` |
| 返回 `int.class` 的筛选 | 按方法名 + 返回类型找，不写死三个参数类型名（那是混淆名，升版会变） |

**为什么第二刀敢下**：它不新增任何 `EVENT_*`，不越过状态机，不碰 `AppFrontInterceptor`。系统该不该画卡、
画多大、什么时候收，仍然是它自己的判断——我们只把「这副卡有多急」从「普通」改成「短促强提醒」。
旧版那刀硬调 `onExpand` 的代价（通知中心里那张卡缺席、点空白收不回去）**在这里不会出现**，因为卡的来源没变。

#### 现代 Xposed API（102）的接入方式，逐条实测

官方 wiki 与设备上的管理器行为一致，**旧的 `AndroidManifest` meta-data 一个都不需要了**：

| 文件 | 内容 | 实测表现 |
| --- | --- | --- |
| `META-INF/xposed/module.prop` | `minApiVersion=101` / `targetApiVersion=102` / `staticScope=true`（Java properties 格式） | 管理器列表里显示「API 102」徽标，底部提示「模块声明了静态作用域」 |
| `META-INF/xposed/java_init.list` | 一行一个全限定类名（取代旧 `assets/xposed_init`） | — |
| `META-INF/xposed/scope.list` | `com.android.systemui` | 作用域页直接固定成「系统界面 · com.android.systemui」并已勾选、标「推荐应用」，不用手动加 |

代码侧的三个硬事实：入口类**无参构造**（框架自己 `attachFramework`，不再收 `XposedInterface`）；
`XposedModuleInterface` 的六个回调**全是 `default`**，只重写 `onPackageReady` 即可（拿 `param.getClassLoader()` 与
`param.getPackageName()`）；`compileOnly` 是必须的——API 类真打进 APK 会和框架注入的那份打架。
`getDeclaredMethod` 别写死参数类型（混淆名会变），按名字 + 返回类型扫。

#### 实测到哪一步了

| 环节 | 结果 |
| --- | --- |
| 三个 `META-INF/xposed/*` 声明 | ✅ APK 内逐字核对；`classes2.dex` 里 `FluidModule` 2 处、`libxposed` 只有超类描述符（`compileOnly` 生效） |
| `assembleDebug` + `adb install -r` | ✅ BUILD SUCCESSFUL；Success |
| LSPosed 识别 / 作用域 / 启用 | ✅ 列表显示「OPAirDrop 1.0.0 (10000) · API 102」；`modules_state=(com.oshare.airdrop,0,1,0)`、`scope=(com.oshare.airdrop,com.android.systemui,0)` |
| 重启 SystemUI 后注入 | ✅ lspd verbose：`已钩住 public static final int …access$entryToReminderType(…,NotificationEntry,boolean)` |
| 钩子执行 | ✅ `提醒级别 0 → 11，让卡片自己展开`；SystemUI `addLiveAlert: … remindType=11 shouldReminderAgain=true, entryFlag=7` |
| 模型侧连锁 | ✅ `remindLevel: 11, isForcedReminder: true, InstantDuration: 3000`（原 5000，与 `STRONG_SHORT` 的预期一致） |
| **卡片真的摊开了吗** | ✅ 2026-11-19 01:46 干净场景（解锁 + 桌面 + 通知栏收起）通过，证据链与截图见下面「验收」一节。锁屏那一轮不算数（锁屏本来就会摊开） |

#### 第二道闸：`AppFrontInterceptor` 的前置抑制

上一轮复现是被自己污染的：点管理器右下角那个 FAB 想重启作用域应用，结果打开了 OPAirDrop 主界面，
`MainActivity` 的 task 成了前台，于是

```
PluginSeedling--Base: AppFrontInterceptor-->shouldFilter ret true,
    package (com.oshare.airdrop, 0) frontTaskId [693] vs com.oshare.airdrop 0
PluginSeedling--StateMachine-->CARD_EVENT_DATA: cardLiveSize=0, capsuleLiveSize=0, cardsLevel=-1
→ tryTransferOrDoo EVENT_DATA_CARDS, result=REFUSED
```

`cardLiveSize=0` 说明卡**根本没进列表**——它在 `AppFrontInterceptor` 就被丢了，跟 `remindType` 无关。
所以「应用在前台时要不要强行展开」是个**产品取舍**，不是技术缺口：绕过去等于让人在 OPAirDrop 自己的界面里
被自己的卡挡住。当前实现**不绕**。

2026-11-19 01:43 补了一条反向证据：**试着绕了，结果更差**。把 `interceptor.a` 那一刀装上之后，
`shouldFilter ret true` 确实消失、卡也放行了，但同一轮状态机反而判 `REFUSED`
（`cardLiveSize=1 / capsuleLiveSize=1 / transientReminderSize=0 / cardsLevel=-1`）。原因是替原方法
返回值跳过了它往 `model.e` 写「这是一条瞬时提醒」那一步，标记丢了。而 01:37 那一轮（这刀没装上）
已经 `ACCEPTED` ——**`remindType=11` 本身就越过了这道闸**，不需要动它。代码里 `hookFrontBypass` /
`keepWhenFront` / `nextLink` 仍然留着（取证不丢），但调用点已删。

#### 验收：干净场景下钩子确实把卡摊开了（已通过）

上一轮卡在两处环境上，这一轮全部解开：

1. **锁屏解不掉** → 人工解锁，`mCurrentFocus=com.android.launcher`。
2. **锁屏/后台广播被冻结** → 不再走广播。ColorOS 给 `com.oshare.airdrop` 的 manifest 广播打
   `DEFER_BY_OPLUS / SUB_REASON: FROZEN`，`am unfreeze`、`am unfreeze --sticky`、
   `am set-standby-bucket active`、`am set-inactive false` **全部无效**，只有短暂拉前台能解冻。
   所以新增 debug-only 的 `LiveProbeActivity`（`Theme.NoDisplay`，`finish()` 必须在 `onResume` 之前调，
   然后 `postDelayed(300ms)` 才 `LiveProbe.fire`），用
   `am start -n com.oshare.airdrop/.LiveProbeActivity --es mode invite_fresh` 直接触发，
   **根本不经过广播队列**。

干净场景（解锁 + 桌面 + 通知栏收起）下的完整证据链：

```
01:46:09.624  提醒级别 0 → 11，让卡片自己展开
01:46:09.801  tryTransferOrDoo EVENT_DATA_CARDS, result=ACCEPTED
01:46:09.801  CARD_EVENT_DATA: cardLiveSize=1, capsuleLiveSize=0, cardsLevel=1, transientReminderSize=1
01:46:10.481  SeedlingStateMachine-->AfterEvent:EVENT_DATA_CARDS, Result ACCEPTED, AfterTransfer:CARD_STATE
```

截图上 `卷卷薯 想发给你文件 / 1 个文件 · 2.4 MB / 拒绝 接收` 完整浮在应用界面之上，
**这一轮没有出现 `AppFrontInterceptor shouldFilter ret true`**。

#### 第三刀：3 秒后自动收回 —— 收回触发在哪，怎么让它不收回

展开之后约 3 秒，卡自己缩回胶囊：

```
01:46:09.848  CardContainerRoot-->setTouchableRegion. curState: CARD_STATE_AUTO
01:46:12.802  CardContainerViewModel-->handleDataTimeout      ← 2.95 s
01:46:12.802  try transfer CARD_TO_START_BY_TIME_OUT → START_STATE
```

**先排掉一个假线索**：dump 里 `LiveAlertModel` 有个 `InstantDuration: 3000`，看着像计时器，
其实不是——它是 `interactor/n.toString()` 里现算的展示值：

```java
long j = 5000;
if (iD2 != 0 && iD2 == 11) j = 3000;
```

改它不影响任何时长，只是让 dump 好看。

**真正的收回链**是 `CardContainerViewModel.l(String)`（`handleDataTimeout`）发出 `EVENT_CARD_TIME_OUT`。
所以第三刀钩的就是它（反编译位置 `com_oplus_systemui_plugins_seedling_card_ui_viewmodel_h.java:773`）。

**为什么不硬改成「永远不收回」**：`CARD_STATE_AUTO` 和 `CARD_STATE_MANUAL` 是有分工的。
`capsule/data/repo/g.java:107` 里

```java
EVENT_CAPSULE_LONG_CLICK ? "CARD_STATE_MANUAL" : "CARD_STATE_AUTO"
```

AUTO 是「系统替你摊开」，所以三秒后系统也替你收回；MANUAL 是「人长按摊开」，系统就不收回、
只等人点空白或点按钮。我们做的事是**让这张卡的身份和它的来源对齐**：它是被钩子替你摊开的，
但你要的是它一直待着，那就把它记成手动展开态，收放语义跟着一起换掉，而不是把超时事件吞了就算了。
（只吞事件不改状态，卡会停在一个「系统以为是 AUTO、却永远等不到超时」的中间态，点空白区域的行为不可预期。）

实现要点：`keepCard(Chain)` 先确认这张卡是不是自家的（从 `d` 这个
`List<card.data.model.a>` 里读每张卡的 `a` 字段，key 形如 `0|com.oshare.airdrop|2816|null|10456`，
`contains("com.oshare.airdrop")` 才算），是自家的才改状态并 `return null` 吞掉事件，不是自家的
`chain.proceed()` 原样放行——**别的应用的卡不能受影响**。

实测（2026-11-19 01:46）：

```
01:46:09.848  setTouchableRegion. curState: CARD_STATE_AUTO
01:46:12.802  这张卡到期也不收回，改成手动展开态
01:46:21 / 01:46:28 两张截图，距展开 11 秒 / 19 秒，卡仍然完整展开
```

手动收仍然有效（点【拒绝】）：

```
01:53:09.458  AfterEvent:EVENT_CARD_CLICK, Result ACCEPTED, AfterTransfer:START_STATE
01:53:09.458  AfterEvent:EVENT_DATA_CARDS, Result REFUSED, AfterTransfer:START_STATE
01:53:09.459  animatorSceneType CARD_TO_APP
```

截图确认卡片完全消失、回到应用界面，没有残影。

#### 提醒音：系统那一路对「被提升过的通知」不出声，改由应用自己播

第一版把声音和振动声明在通知渠道上（`live_card_alert_1`，`IMPORTANCE_HIGH` + 默认通知音 +
`{0,220,180,220}`）。渠道本身建得完全正确，`dumpsys notification` 里能看到：

```
mId='live_card_alert_1', mName=收到文件, mImportance=4
mSound=content://settings/system/notification_sound
mVibrationPattern=[0, 220, 180, 220], mVibrationEnabled=true
mAudioAttributes=usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION
```

但 `dumpsys audio` 的播放器事件流里**一个 `USAGE_NOTIFICATION` 的新播放器都没出现**——一声没响。

根因在提升那一步：`NotificationLARuleManager#onLiveNotificationPost` 对这条带 `PROMOTED_ONGOING`
的通知返回 `result=7`，把它交给了流体云。**流体云卡片本身就是那个「提醒」，系统那一路的 alerting
被吞掉了**。不是渠道没声明对，是「被提升过的通知不再走普通提醒」。

所以改成应用侧自己播（`LiveCard.chime`），在 `manager.notify` 之后紧跟一次：

- **声音**：自带的 `res/raw/chime_airdrop.wav`（一段「隔空投送铃声」转成的 PCM，0.55s，
  44.1kHz/16bit 单声道，48KB），用 `MediaPlayer` 播放，`AudioAttributes` 用
  `USAGE_NOTIFICATION` / `CONTENT_TYPE_SONIFICATION`。不走系统默认通知音，换来的是节奏固定、
  能和下面那四下振动对上点；音量仍然走通知音量，只换音色不换礼仪。
- **振动**：四下，起点压在音效的每次敲击上（12 / 130 / 245 / 369ms），振幅跟着音效递减
  （-4.2 / -4.8 / -6.4 / -11.8dB → 255 / 235 / 200 / 120）。
  必须用 `VibrationAttributes.createForUsage(USAGE_NOTIFICATION)`——不写的话系统按 `USAGE_TOUCH`
  记（实测 dumpsys 里就是 `usage: TOUCH`），而触摸反馈走另一套开关：勿扰不管它、
  设置里的「通知振动」也不管它。
- **防抖**：`CHIME_GAP = 1500ms`，同一件事被重投（探针先 cancel 再延迟 700ms 重发）时只响第一次。
- **三道闸**：应用通知被关 → 不响；渠道 `IMPORTANCE_NONE` → 不响；勿扰开着 → 只振动不出声。
  静音模式（`RINGER_MODE_SILENT`）连振动也不给；振动模式（`RINGER_MODE_VIBRATE`）只振不响。

#### 换音色时踩的两个坑（都实测过）

**坑一：`MediaPlayer.create()` 之后设属性会被吞。** 第一版写的是
`MediaPlayer.create(context, R.raw.chime_airdrop)` 再 `setAudioAttributes(...)`——
因为 `create()` 返回之前就已经 `prepare()` 了，属性没赶上，`dumpsys audio` 里落成
`usage=USAGE_UNKNOWN`，声音实际走 `STREAM_MUSIC`：音量键调通知音量管不着它，
上面那三道闸也全成了摆设（我们自己的 `ringer == SILENT` 判断还在，但系统层面的约束没了）。
必须按 `new MediaPlayer()` → `setAudioAttributes` → `setDataSource` → `prepare()` → `start()` 的顺序写。
正确之后 `dumpsys audio` 会多出一行（对比看，错的那版只有 `new player`，没有下面这行）：

```
02:07:12:185  new player piid:298727 ... attr:AudioAttributes: usage=USAGE_UNKNOWN
02:07:12:185  player piid:298727 new AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION
```

**坑二：ColorOS 的 `AudioHardening` 会掐后台播放。** 属性还是 `USAGE_UNKNOWN` 的那一版，
`dumpsys audio` 里留下过

```
AudioHardening background playback would be muted for com.oshare.airdrop (10456), level: full, reason: 2, usage: USAGE_NOTIFICATION
```

改成 `USAGE_NOTIFICATION` 之后这条不再出现，后台播放全程干净：

```
02:07:12:185  new AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION
02:07:12:196  event:started
02:07:12:231  event:device updated deviceIds:[3]
02:07:13:050  event:stopped          ← 854ms，完整播完，无 muted
```

#### 音源：一个 mp3 里有 93% 是标签

用户给的 `隔空投送铃声.mp3` 是 151698 字节，其中 **141529 字节是 ID3v2 标签**
（里面有一张 36899 字节的封面图），真正的音频只有 10159 字节 —— 0.627 秒、44.1kHz 立体声、
128kbps。所以不能直接扔进 `res/raw`：白占 140KB，还多一层 mp3 解码依赖。

`.workbuddy/tmp/make_chime.py` 做的事（PyAV 解码 → numpy 处理 → 写 16bit PCM）：

1. 左右声道取均值转单声道；
2. **裁掉开头 74ms 前导静音**。原文件第一下在 86ms，裁到 74ms 让 onset 落到 12ms，
   这样声音和振动可以共用同一个零点，不用互相补偿启动延迟；
3. **归一化到峰值 0.85（+14.8dB）**。原文件峰值只有 0.155（-16dBFS），做通知音太轻，
   而 `MediaPlayer.setVolume` 只能往下调、不能超 1.0，放大只能在文件里做；
4. 头 2ms / 尾 40ms 斜坡，避免播放前后各来一声「咔」。

输出 `chime_airdrop.wav`，48KB。四下的相对强弱是原来就有的，**没有压缩动态**——那是这个音效的听感所在。

结构测出来是这样（阈值取峰值 -26dB，2ms 帧）：

| onset | end | 峰值 | 间隔 |
|---|---|---|---|
| 0.086 | 0.184 | -19.0dB | |
| 0.204 | 0.303 | -19.8dB | 118ms |
| 0.319 | 0.425 | -21.1dB | 115ms |
| 0.443 | 0.539 | -26.5dB | 124ms |

四下，每下约 100ms，力度一路递减——振动就是照这张表排的。

验证方式是给 `LiveProbeActivity` 加了 `--ei delay <ms>`：`am start` 之后先 `finish()`
把前台还回去，等 4 秒（应用已经在后台）再由 Handler 投卡，复现「人待在别的应用里，
对面发来一张照片」。**这一条才是真实场景**，前台触发那次不算数。

实测（2026-11-19 02:25，振动）：起振点与音效四次敲击逐帧对齐。

```
02:25:51.043  audio:  piid:298839 event:started  (usage=USAGE_NOTIFICATION)
02:25:51.045  vibrator_manager: usage: NOTIFICATION | com.oshare.airdrop (uid=10456)
              duration: 449ms
              reason: From [S12, S45, S73, S45, S70, S45, S79, S45]
              played: [ON 45ms@2400, ON 45ms@2211, ON 45ms@1882, ON 45ms@1129]
              → 起振点 12 / 130 / 245 / 369ms ≡ 音效 onset
02:25:51.732  audio:  piid:298839 event:stopped   (689ms，无 muted)
```

#### 坑三：ColorOS 会把「带振幅的 waveform」压成 45ms

第一版振动每下写 62ms（音效每下约 100ms，取 62 留出能分辨的静默缝隙）。实测 dumpsys 里
被改成了 `OplusPrebakedSegment{mEffectId=69, mWaveformId=6, mDuration=45}`——**每段「震」不管
请求多长都只给 45ms，但「停」的段原样保留**。结果四个起点变成 12 / 113 / 211 / 318ms，
一路前移，到第四下已经比音效早 51ms，听得出来脱拍。

反过来利用这条规则就行：既然「震」恒为 45ms，就按 45ms 写，把空档按
「下一个起振点 − 上一段结束」补满（73 / 70 / 79），四个起点就一毫秒不差。
这个写法在原生 Android 上也成立——那边不压缩，45ms 震 + 更长空档，节奏点一样准。

振幅确认是**按比例映射**的：请求 255/235/200/120 落到 2400/2211/1882/1129
（2400 是 ColorOS 预烘焙脉冲的满量程，不是 `NOTIFICATION` 那档的 1800），
所以递减关系保留下来了，只是整体比默认通知振动更实。


> 顺带一个副作用值得记：因为提醒不再依赖通知渠道，**声音和卡片展开就解耦了**——
> 即使某天钩子失效、卡没摊开，人照样会听到那一声，不会连「有人发东西来了」都错过。

**「展开」的官方旋钮也已经查实了**：字段叫 `requestShowPanel`，时钟倒计时结束就是靠它摊开的；取证与三条替代路见 §2.2。

### 2.2 流体云「自动展开」：官方旋钮叫什么，三条替代路各值多少

用户的要求：**收到 iPhone 文件时胶囊自己展开成卡片**，省掉「先点胶囊、再点接收」这一下。参照样本两个：
时钟 `com.coloros.alarmclock`（倒计时结束自动展开）、应用商店 `com.heytap.market`（装完展开成「安装完成 / 打开」）。

**取证方式**（产物全在 `.workbuddy/tmp/`，不进仓库）：`adb pull` 四个 APK —— `Clock.apk`（`/my_stock/del-app/Clock/Clock.apk`，49 545 882 B）、
`market.apk`（94 168 031 B）、`SystemUIPlugin.apk`（`/system_ext/app/SystemUIPlugin/`，9 114 706 B）、
`ums.apk`（`com.oplus.pantanal.ums`，92 934 211 B）；再用 `tools/JadxOne <apk> <out> <关键字>`
导出 `re/clock/`（关键字 `Seedling`，41 类）与 `re/plugin/`（关键字 `seedling`，818 类）。

#### 官方旋钮就是 `requestShowPanel`，四跳每跳都有代码

| # | 位置 | 证据 |
| --- | --- | --- |
| 1 | App 侧 | `com.oplus.pantanal.seedling.update.SeedlingCardOptions` 有 `private Boolean requestShowPanel;`；时钟 `GarbAlarmSeedlingHelper.java:1027 / :1031` 两处 `seedlingCardOptions.setRequestShowPanel(bool)` |
| 2 | 序列化 | `JsonToSeedlingCardOptionsConvertor.java`：`KEY_REQUEST_SHOW_PANEL = "requestShowPanel"`（:43），读在 :114 / :354，写在 :246 |
| 3 | 传输 | `ums.apk` 的 `classes5/6.dex` 里 `isRequestShowPanel` / `setRequestShowPanel` / `onShowPanel` / `$showPanelFragment bottomSheetDialog=` 全在——这条语义一路走到 UMS 的 UI 落地，不是死字段 |
| 4 | 插件侧 | `SeedlingUIData.isRequestShowPanel`（`@Json(name = "isRequestShowPanel")`）→ `livealert.seedling.origin.v`（`SeedlingDataModel`）在 `K()` 里 `this.n = …isRequestShowPanel()`（`E()` 为真时优先 `SeedlingUIData`，否则回落 `NewSeedlingCardOptions`），再由 `v()`（实现 `livealert.lib.m`）对外暴露 |

顺手把 `SeedlingCardOptions` 的**整张字段表**抄下来，省得下次再反一遍：`pageId`、`dataSourcePkgName`、`isMilestone`、
**`requestShowPanel`**、`requestHideStatusBar`、`grade`（1–5）、`notificationIdList`、`showHostMap`、`lockScreenShowHostMap`、
`cancelPanelActionConfig`（@Deprecated）、`panelActionConfigMap`、`controlAction`、`remindType`（`NORMAL=0` / `STRONG_SHORT=11` /
`STRONG_LONG=12` / `STRONG_ALWAYS=13`）、`shouldFocus`、`focusTimestamp`、`extensibleActionMap`。

#### 展开在状态机里是哪一条路

`AnimatorSceneType`（第二条数字是埋点 rateTypeID，一律 20402）：`CAPSULE_TO_CARD(106)`、`CAPSULE_TO_LARGE_INSTANT_CARD(107)`、
`CAPSULE_TO_SMALL_INSTANT_CARD(108)`、`CARD_APPEAR(113)`、`CARD_TO_CAPSULE_BY_INTERRUPT(116)`、`CARD_UPDATE(123)`、
`SMALL_INSTANT_TO_LARGE_INSTANT(137)`。

进 `CAPSULE_TO_CARD` 的路**三条**，都在 `statemachine_i.java`：

| 事件 | sceneName | 行 |
| --- | --- | --- |
| `EVENT_CAPSULE_CLICK` | `CAPSULE_TO_CARD_BY_CLICK` | :1103 |
| `EVENT_CAPSULE_LONG_CLICK` | `CAPSULE_TO_CARD_BY_LONG_CLICK` | :1114 |
| **`EVENT_DATA_CARDS`** | `CAPSULE_TO_CARD` | :1305–1315 |

前两条是手点的，第三条才是「数据驱动的自动展开」。它全工程只有一个发送点，在 `card_ui_viewmodel_h.java:859`：
`if (r(arrayListI) == 4 && z) { … new event.c("EVENT_DATA_CARDS", j(this, false, null, 15), null) }`——
`r()` 数卡片列表里某一类数据的条数，`z` 是「这次允许自动展开」；紧接一行 `if ("CARD_STATE_AUTO".equals(this.g)) startAllTimer()`，
说明它属于自动态那套计时器，不是常开。

#### 为什么不能直接走官方接口

`requestShowPanel` 是 **UPK / Pantanal DevStudio 工程**的字段：要有 `config.json` 的 `platform-version: 2000000`（API-2.0 / ColorOS 14）、
`seedling-type: live|immediate`、`card-config.json` 的 `support: notification_sm|md|lg`（只有 `lg` 是面板），还要过平台注册。
我们是「一个普通 APK + 一个 `.so`」，一条都不满足；引 SDK 还等于在 §2 那三条硬规矩之外再挂一个外部依赖。

#### 三条替代路，按性价比排

1. **属性白名单（最便宜，先试一次）**：`notification.rule.d` 在构造时把 `debug.systemui.live_alert_pkgs` 按逗号切成白名单（:36–38），
   `SeedlingCardServerProvider` 也在读它。`setprop debug.systemui.live_alert_pkgs com.oshare.airdrop` 之后若我们的常驻卡就能进 LA 通道，
   等于白拿「系统承认的流体云」身份。
2. **把通知补成它认的样子**：`notification.rule.d.e()` 的准入逐条是——extras 里 `oplusLiveAlertAppConfig` 非空、`android.title` 非空、
   `smallIcon` 非空、`a(notification)` 为假、应用 `requestedPermissions` 含 `android.permission.POST_PROMOTED_NOTIFICATIONS`，
   且包名在 `a.b` 的允许列表里；`f()` 另外承认 `MetricStyle` + `isPromotedOngoing`（`b()` = `SDK_INT >= 36 && notification.isPromotedOngoing`）。
   我们的卡本来就是 `PROMOTED_ONGOING`，缺的看起来正是那个 extra 与白名单。
   样式枚举 `NotificationLAStyle` = `{None, StandardStyle, BigTextStyle, CustomStyle, ProgressStyle, CallStyle, MetricStyle}`。
3. **LSPosed API 102 钩一层**（用户设备已装新版框架）——**这条路已经走通了第一层，细节全在 §2.1**。
   实际落的钩点不是 `requestShowPanel`，而是更上游的 `remindType`（`access$entryToReminderType` 返回 `0` 时抬成 `11`）：
   它等价于「在 JSON 反序列化之前就把 `SeedlingCardOptions.remindType` 改掉」，不伪造任何 `EVENT_*`，
   也不越过 `AppFrontInterceptor`。若这条最终不够，备选顺序是 —— ① 钩 `livealert.seedling.origin.v.v()`
   把 `requestShowPanel` 恒置 `true`（最贴官方语义，但见 §2.1 第一次那刀的教训：**改数据模型会连生命周期一起改坏**，
   动手前先弄清 `v()` 有谁在读、会不会把「一次展开」变成「永远展开」）；② 伪造 `EVENT_DATA_CARDS` 的发送条件
   （`card_ui_viewmodel_h:859` 的 `r(arrayListI) == 4 && z`）；③ 钩状态机 `statemachine_i` 的 case 4 过滤器。

插件里 `SystemProperties.get` 的全表（本轮 grep 完）：`debug.systemui.bubble`、`debug.systemui.capsuletimeout`（`SeedlingDataModel`
里 `N = getLong(...)`，就是胶囊超时）、`debug.systemui.live_alert_disable_callstyle`、`debug.systemui.live_alert_pkgs`、
`debug.systemui.priority.score.testdata`、`debug.systemui.remindImmersive.testdata`、`debug.systemui.seamless_transition.enable`、
`debug.systemui.servicefrom.testdata`、`persist.debug.capsule_toggle`、`persist.full_scene_continuous.enable`、
`ro.oplus.display.notch_support`、`ro.oplus.display.screenhole.positon`。

**还没查完**：`market.apk` 只解了 dex，类还没导出——它是第二个样本，能验证「非系统应用 + 通知」这条路上到底需不需要 UPK。

---

## 3. 速度：发送端的完整结论（本文最重要的一节）

这一节取代旧版「接收上限 88 KB/s」和「发送 432 KB/s」两条记录——那两个数来自不同方向、
不同设备、不同时期，别再拿来比。

### 3.1 定版参数（已固化进 `tools/awdl-stack.sh`，开机重注入后自动生效）

这一组是实测最优，不是猜的。同一台手机、同一个 24.7 MB 视频、同一次会话窗口内做 A/B。

| 参数 | 值 | 为什么是这个值 |
| --- | --- | --- |
| `amsdu` | `2` | A-MSDU 聚合模式 |
| `amsdu_max` | **`8`** | 单聚合最大 MPDU 数。原 5 是 HT 时代的硬上限，2026-11-22 放到 8（§3.6.3）；`passthru_awdl_amsdu_max` 0644，可运行期回拨 |
| `amsdu_bytes` | **`11454`** | 单聚合最大字节数。原 7935 是 HT 的 MPDU 上限，VHT 的 A-MSDU 上限是 11454（§3.6.3） |
| `collect_us` | **`1500`** | 聚合定时器。为 0 时 `opairshare_collect_delay_locked()` 第一行就返回，只能在环填满时发包：3.6 msdu/聚合、`deferrals=0`；1500 时变 4.54 msdu/聚合 |
| `phy` | **`3`** | **VHT**。这是 `WMI_RATE_PREAMBLE` 尺度（`0 OFDM, 1 CCK, 2 HT, 3 VHT, 4 HE`），**不是** wondertap 枚举。原值 2 = HT，带宽封 40 MHz、MPDU 封 4095 字节，是 80 MHz 失效与 `followers_dropped` 的共同来源（§3.6.1） |
| `mcs` | **`9`** | 单流 MCS9。原为 7（`mcs=8` 在**共信道 STA 存在时**重传 20.8%，但当前 `allow_concurrent=N` 已独占无线电，等价参考项目的 `pt_exclusive=1`，可上高 MCS） |
| `rate_mbps` | `54` | 固定速率，避免驱动自适应在 AWDL 上乱跳 |
| `nss` | **`2`** | **2 空间流**。3.2 MB/s 那次快照实测就是 `nss=2`，此前脚本从没设过，一直是 1（半空口） |
| `capacity` | **`256`** | 队列深度。**2.25 → 2.59 的唯一变量**，见 §3.3 |
| `lowmark` | **`128`** | 回压起点，取 capacity 的一半 |
| `backpressure` | `Y` | 编译期固定（0444），本来就对 |
| `attempts` | `3` | 单帧固件重试次数 |
| `retry` | **`N`** | 链路层 NO_ACK 重传，**有害**，见 §3.4 |
| `retry_max` / `retry_budget_ms` | `8` / `500` | 只在 `retry=Y` 时有意义，留默认 |
| `ba_window` | `64` | BlockAck 窗口，与对端协商一致 |
| `ack_policy` | `0` | 正常 ACK |
| `ackfilter` | `N` | 不开 ACK 过滤 |
| `tid` | **`-1`** | **不轮转 TID**，轮转有害，见 §3.4 |
| `host_seq` | `Y` | 序号在主机侧生成 |
| `rx_order_ms` | `0` | 不做过期重排 |

链路层另外两条（写在 `awdl-stack.sh` 的 `link_up` 里，不是模块参数）：

- `iw dev wonder0 ibss join awdl0 5745 HT20 fixed-freq 00:25:00:ff:94:73`
  —— **`HT20` 是吞吐的命门**：不加的话 vdev 是 `width: 20 MHz (no HT)`，之后所有 HT 速率
  参数都被固件忽略，空口停在 **24 Mb/s**（5 GHz 只剩 6/12/24 是 mandatory），
  4200 B/聚合要 2.0 ms，这就是 2 MB/s 那堵墙的来源。**`fixed-freq`** 则防止每 30 s 重扫把射频带走。
- `accept_dad=0`：否则 link-local 在 `up` 后约 1 秒是 tentative，服务端 bind 会失败。

TCP 侧（内核，非模块；2026-11-20 起是 `bbr`+`fq`）：

```
cc=bbr   qdisc=fq (awdltun0)   tcp_reordering=12   tcp_slow_start_after_idle=0   MTU=1458
```

这两项**不是** `insmod` 参数，也**不能**写一次就算完：`cc` 是机器级 sysctl，`qdisc` 活在
`awdltun0` 上，而 `awdltun0` 由 `awdld` 创建，daemon 一起来接口就是新的、root qdisc 回到
`pfifo_fast`。所以由 `tune_tcp()` 在**每次** daemon 起来时重挂（`DAEMON_ALREADY` / `TUN_READY` /
`link` 三个入口），`stop` 时 `restore_cc()` 把 `cc` 写回 `$STATE/cc-before` 记的原值。细节与
四条真机验证路径见 §3.3.2。

**持久化机制（模块参数那 22 项）**：写成 `insmod` 参数（第一次构帧之前就生效），`load_stack()` 里
集中在 `TUNE_INSMOD` 一行维护；`link_up()` 末尾调 `apply_tuning()` 再写一遍 sysfs 并**回读校验**，
把被 clamp 或拒绝的项打成 `TUNING_MISMATCH`。所以「开机 → App 解包脚本 → `boot` → 重注入模块」
这条链上，参数只有一处定义、两处保证。

### 3.2 实测（1 秒粒度，`awdltun0/tx_bytes` 差分）

同一个 24.7 MB 视频，只改一个变量：

| # | 配置 | 全程 | 稳态 | 备注 |
| --- | --- | --- | --- | --- |
| 1 | A-MSDU 4×6000, nss=1, cap=32 | 2.08 MB/s | — | 早期基线 |
| 2 | A-MSDU 5×7935, nss=2, cap=**32** | 2.25 MB/s | — | 「照抄 3.2 清单」也只有这个数 |
| 3 | A-MSDU 5×7935, nss=2, cap=**256** | **2.59 MB/s** | **3.24 MB/s** | 定版 |

第 3 行的逐秒曲线（`0.66 → 2.56 → 2.90 → 3.48 → 3.15 → 3.16 → 3.27 → 3.20 → 3.16`）：
前两秒是建链 + TCP 慢启动，**24.7 MB / 9.86 s 的「全程」被它拖到 2.59，而后 6 秒稳定在
3.2 以上，峰值 3.48**。所以「3.2 MB/s」指稳态；要变成全程，得削掉起飞段（§3.5 ③）。

> 这张表是 **24.7 MB 视频 + `cubic` 时代**的记录，只用来解释「cap 32→256 为什么值 +15%」。
> `bbr`+`fq` 之后换了 280 MiB 不可压样本（`/dev/urandom`，线上字节≈磁盘字节），数值是
> 稳态 2.97–3.14 / 全程 2.98–3.11，见 §3.3.2 与 §9。**两套样本不能互减**——短样本被起飞段
> 拖着，天然比长样本的「全程」低。

配套三个数（同一轮，`cubic` 时代）：

| 指标 | 值 |
| --- | --- |
| native 每聚合时延 | **17.2 ms** |
| `status3/status0` 丢包率 | **1.75%** |
| TCP 重传率 | 289 / 19290 = **1.5%** |
| ~~反推 cwnd~~ | ~~38.4 段（≈53 KB）~~ —— **作废**，这是 `rate × RTT` 反算出来的「在飞多少」，不是内核允许的窗口。`ss` 实测 `cwnd:46–56` 段、对端 `wscale:9`（§3.3、§3.3.3） |

### 3.3 吞吐模型：3.2 是谁在限（2026-11-20 用 `ss` 重定）

> 本节在 2026-11-20 00:1x 重写。旧模型——「iPhone 只给 89 KB 接收窗口，所以单流天花板
> 5.05 MB/s，实际 3.0 是因为丢包把 cwnd 砍到 53 KB」——**已被传输中途的 `ss -tin` 证伪**。
> 旧推导留在 §3.3.2 末尾备查，别再引用它。

传输中途连抓 5 次，连接是 `[fe80::e0ed…]%awdltun0 → [fe80::6c17…]:8770`，一次典型读数：

```
bbr  wscale:6,9  rtt:17.9/0.4  mss:1386  cwnd:46–56  ssthresh:32
     bbr:(bw:34–35 Mbps, mrtt:3.4–4.8, cwnd_gain:2)  minrtt:3.413
     pacing_rate 26–34 Mbps   delivery_rate 24–33 Mbps
     retrans:0/1120（共 114244 段发出 → 0.98%）  reordering:21  unacked:45–52
```

四条，按重要性排：

1. **对端窗口不是瓶颈。** `wscale:6,9` 是一条两侧缩放都非 0 的连接，iPhone 的通告窗口
   可以开到 32 MB。真正小的是**我们自己的 `cwnd`：46–56 段 = 62–77 KB**。
   所以「窗口给了多少」这个问题从一开始就问错了，该问的是「我们在飞多少、为什么不再涨」。
2. **瓶颈是排队。** `minrtt 3.413 ms`，实时 `rtt 17.9 ms`——链路上长期堆着 ~45 KB、
   约 14 ms 的东西。这段队列在 `awdltun0 → passthru bridge` 之间，不在空口。
   BBR 自己估这条链路 34–35 Mbps（4.3 MB/s），实际交付 3.05，**只拿到 71%**。
3. **空口和驱动参数都已到顶。** `agg_stats` 平均 4.30 msdu/聚合（上限 5），`p5=136989`
   占 73.5%——A-MSDU 5×7935 打满，没有余量；`opairshare_bridge_pending=0`，`capacity=256`
   此刻**没有**在限流（2026-11-18 那次 32→256 是队列真在节流，不是同一个场景，加到 512
   不会有收益）。
4. **两条流加起来也不动**（§3.3.1）。两条连接各自 `cwnd 46–62`、合计 ~140 KB 在飞，
   吞吐不变，`rtt` 反被推到 19–23 ms。→ **限住的是这条链路共享的交付能力，约 26 Mbps。**

于是一个数就能对上：`26 Mbps ≈ 3.25 MB/s`，实测 2.75–3.14 就是它的 85–97%。10 MB/s 需要把
**交付能力本身**翻三倍，那既不在 TCP 参数里，也不在带宽里（§3.4 闸 d）。

三条推论（第 3 条已改写）：

1. **改信道宽对发送没用**。AWDL 钉在社交信道 20 MHz @ 5745，发送侧加宽会掉出对端协商的
   信道；且 20 MHz 已经有 15.7–16.25 MB/s 的空口能力（驱动峰值 48×5641÷17.2 ms），
   我们只用了 20%。接收侧的机制成立，但那是四件事的总和，最后一件由 iPhone 决定（§3.4）。
2. **改固件上限也没用**。`peak_native_live=48`、5641 B/聚合、17.2 ms/轮 → 15.7 MB/s；
   实测每轮只送 ~9 个聚合。卡住的是「交付链路能持续消化多少」，不是「空口能塞多少」。
3. **并行流已实测否掉**（§3.3.1），**换拥塞控制已实测成立**（§3.3.2，+11%）。两者一个是
   加额度、一个是改用法：额度追加无效，用法改对有效——这正好把「窗口/额度」这一整类
   假说清理干净。

### 3.3.1 多流并行（S4）实测：功能成立，速度不成立

App 侧**曾经**实现过 `client.streams`（1–6，默认 1）：点发送时按「大文件优先贪心装箱、按字节均分」把条目
切成 N 组，每组一条独立连接走完整的 `/Discover`→`/Ask`→`/Upload`（**协议一条连接只承载一个请求**，
所以 N 条流 = N 趟对话 = 对面 N 次确认）。Rust 侧一行未改：发送路径无全局共享状态，N 线程并发安全。

**2026-11-20 这个旋钮已删**，三处一起动：`crates/settings` 的 `CATALOG` 少了那一行（原地留注释），
`SendActivity` 的 `streamCount()` 与 `share()` 删掉（装箱算法写进原地那段块注释备查），调用点改成
`Collections.singletonList(paths)`。`sendLanes()` 这个「N 组」的形状**保留不删**，只是永远收一组——
将来若真要复测，不必把几十行重写一遍。磁盘上的旧值不用迁移：`Values::retired()` 读盘时会把表里
已不存在的键跳过并记一笔，`to_text()` 只写表里有的行，下一次保存自动清掉（设备上实测过：
`settings.conf` 里那条 `client.streams=1` 点一次「保存」就没了）。

**顺带订正一句旧说法**：「`CATALOG` 加一行即进设置页」不成立。`CATALOG` 是**可调项目录**——
范围、默认值、给 `Tuning` 校验和回落用；Android 侧的设置行是 `SettingsPage` 里手写的，不读它。
所以当初加那一行并没有真的在界面上多出一个开关（界面上从头到尾只有「诊断」和「关于」两组），
删除也不影响任何一屏。这条对将来有用：**想给用户一个旋钮，`CATALOG` 只是一半，另一半得去
`SettingsPage` 写行。**

三轮实测（同一部手机、同一台 iPhone、同一个 24 MB 素材集）：

| 配置 | 样本 | 全程 | 稳态 10–90% | 备注 |
| --- | --- | --- | --- | --- |
| 1 流（对照） | 20 文件 / 282.6 MiB | 2.77 MB/s | 2.75 MB/s | 107.12 s，逐十分位 2.54→2.91 极平 |
| 2 流 | 12 文件 / 24.0 MiB | 2.75 MB/s | **3.02 MB/s** | 9.14 s，两流各 12.0 MB |

两流那一轮的逐段曲线（MB/s）：`2.32 → 2.61 → 2.50 → 3.08 → 3.44 → 3.40 → 2.99 → 3.57 → 1.96 → 2.70`。
末段掉到 1.96 是 lane 1 先送完、只剩 lane 0 一条在跑，这恰好又是一次单流对照。

**关键读数**：两条流分别在 `23:41:53.8` 被问到、`23:41:54.8` 同时开跑（`lane 0 at 0`），
各自 12 582 912 B 写在 ~9 秒内完成——**并发是真的，速度没有叠加**：

| 每条流的实际速率 | 值 |
| --- | --- |
| lane 0（54.789 起，03.982 完） | 1.37 MB/s |
| lane 1（~55.9 起，03.153 完） | 1.74 MB/s |
| 合计 | 2.75–3.02 MB/s |

一条流的 2.7 MB/s 被两条流按 ~1:1 分掉了，合计不变。

第二轮（`streams=2` + `bbr`，同素材 280 MiB）把这件事钉死了。两条连接各自 `cwnd:46→62`、
`unacked:46`、`wscale:6,9`，**合计约 140 KB 在飞**，是单流（~64 KB）的 2.2 倍；吞吐只从
3.14 抬到 3.25（峰值 3.47），`rtt` 反而从 17.9 被推到 19–23 ms。若 cwnd 是瓶颈，吞吐本该翻倍。
→ **限住的是这条链路共享的交付能力，约 26 Mbps，与连接数无关**（模型见 §3.3 第 4 条）。

顺带纠正上一轮的误判：第 2、3 轮「只接受一条、其余 15 秒后炸掉」不是 iOS 一次只处理一个请求，
而是**我们自己的超时**。`client.read_timeout_ms` 是 15 s，90 s 的 `upload_read_timeout_ms` 只挂在
SENDING 相位（对面已接受之后），从没被接受的流永远进不了那个相位，读 `/Ask` 答案用的是 15 s。
把它调到 180 s 后，两条流的询问在同一秒内一起被接受、一起开跑——**多流在协议上可行**，
代价是用户要连续点 N 次确认（产品代价，不是技术障碍）。

**产品代价实测**：20 个文件按字节均分成 10+10，iPhone 弹两次确认框。用户只按了一次发送却看到
两次通知（这是 `streams≥2` 的必然结果，不是 bug）。第二框晚点 31 秒 → lane 1 晚 31 秒起跑，
占全程 91.6 s 的三分之一。也就是说这个功能**即使有收益，也会被「用户点得慢」吃掉**。

结论：多流**不是速度手段**。旋钮默认 1，把它当提速开关就是白跑一趟外加 N 个弹窗；按「没人会
打开的旋钮」的标准，2026-11-20 把它**删了**——连同「N 流 = N 个弹窗」这个坑一起消掉。删除的理由
不是「没人用过」，是**测过了不提速**：一个不提速、只在对面多问一句的开关，留在设置页里就是给人
一个坏选择。

### 3.3.2 拥塞控制（S2）：`cc=bbr` 成立，+11%，已固化

单变量 A/B，同一部手机、同一台 iPhone，样本换成刚生成的 20 × 14 MiB `/dev/urandom`
（`gzip` 验过压不动，线上字节≈磁盘字节），速率取应用自己的逐十分位进度行：

| 配置 | 样本 | 全程 | 稳态 10–90% |
| --- | --- | --- | --- |
| `cubic` + `pfifo_fast`（对照） | 282.6 MiB | 2.77 | **2.75** |
| `bbr` + `fq`（第 1 轮） | 280.0 MiB | 3.11 | **3.14** |
| `bbr` + `fq`（第 2 轮） | 280.0 MiB | 2.98 | **2.97** |
| `bbr` + `fq` + `streams=2` | 280.0 MiB | 3.20 | 3.25 |

逐段曲线（MB/s），这才是判定依据：

```
cubic : 2.75 2.81 2.83 2.86 2.76 [2.54] 2.63 2.79 2.83 2.91   ← 有坑
bbr   : 2.93 3.25 3.10 3.12 3.13  3.14  3.12 3.24 3.04 3.06   ← 走平
```

`2.54` 那一格就是诊断：cubic 每掉一个包就砍半 cwnd 再爬回来，锯齿的平均值比曲线低 0.3–0.4。
BBR 不看丢包，锯齿消失，+11% 随之而来。`fq` 不是装饰——BBR 靠 pacing 送出，`fq` 是它驱动的
时钟，`pfifo_fast` 里没有这个时钟，所以「只改 `cc` 不改队列」在这台机上是半件事。

**它没有解决的是上限**：3.05 MB/s × 17.9 ms ≈ 55 KB 在飞，和 cubic 的 ~53 KB 几乎一样，
说明 BBR 修的是「反复退让」，不是「在飞不够」。多出来的 11% 全来自不再跌进 2.5 那一档。

**已固化进 `tools/awdl-stack.sh`**（2026-11-20）：

- `tune_tcp()`：读 `/proc/sys/net/ipv4/tcp_available_congestion_control` 确认 `bbr` 在列
  → 写 `tcp_congestion_control` → 在 `awdltun0`（名字由 `tun_dev()` 探测，不写死）上
  `tc qdisc replace … root fq` → 回读并打一行 `TCP_TUNING cc=bbr qdisc=fq dev=awdltun0`。
- 四个调用点：`start_daemon` 的 `DAEMON_ALREADY` 分支、`TUN_READY` 分支（新 daemon 建好
  tunnel 的第一刻）、`link` 入口（唯一不过 `start_daemon` 的路径）、`cmd_status` 只读回读。
- `restore_cc()`：`stop` 时把原值写回。原值记在 `$STATE/cc-before`，只在**第一次**修改前记，
  否则第二次 `boot` 会把 `bbr` 记成原值。
- 为什么不进 insmod：这两样是 sysctl 和 qdisc，不是模块参数。**都不跨重启**，qdisc 甚至不跨
  daemon 重启（tunnel 是重建的），所以只能在每次 daemon 起来时重新挂，而不是写一次。

四条路径都在真机上验过：`link` 幂等重挂（`pfifo_fast` → `fq`）、`DAEMON_ALREADY`、
冷启动 `TUN_READY`、`stop` → `TCP_RESTORED cc=cubic`。

### 3.3.3 被推翻的旧模型（留作反面教材）

旧推导是：iPhone `ws=0` → 窗口固定 89 KB → `91136 B ÷ 17.2 ms = 5.05 MB/s` → 实际 3.0 是因为
同一条链路上 1.75% 的聚合失败让 cubic 稳定震荡在 38 段（53 KB）。

两处错的：`ws=0` 与事实不符（实测 `wscale:6,9`），而 89 KB 这个数一旦不成立，5.05 这个天花板
和「差的 36% 是丢包定价」这个归因就一起塌了。**教训**：从吞吐反推窗口（`rate × RTT`）只能得到
「在飞了多少」，得不出「允许在飞多少」；后者只有 `ss -tin` 能回答。用前者当后者的证据，会把
`cwnd` 的自限误读成对方的限制，进而把整条优化路线指向「让对方给更多」。好在 2026-11-18 那次
`capacity` 32→256 的收益是真的（+15% 平均 / +30% 稳态），只是原因不是「解锁窗口」，是队列
不再提前回压——同一个动作，两种解释，当时的写法把因果搞反了，结论恰好仍然对。

### 3.4 已排除清单（别再试，除非条件变了）

| 假设 | 做法 | 结果 | 为什么 |
| --- | --- | --- | --- |
| 链路层能靠重传救丢包 | `retry=Y` + `retry_max=8` | ❌ 更慢 | `bridge_retried` 与 `status3` 严格 1:1（+590/+590），`retry_gaveup=0`：它在**同一个 28 ms 时隙里**重发同一帧，救不回来还占时隙 |
| 多 TID 能提高并行 | `tid=100`（QoS 字节 0→7 轮转） | ❌ 灾难 | BlockAck 会话按 TID 建；iPhone 每 TID 一个排序队列 → 乱序 → `ssthresh` 1400→28，**2.08 → 0.93 MB/s** |
| 聚合越大越快 | 4×6000 → 5×7935 | ⚠️ 分情况 | 字节 +36%，但 NOHT 下空口时间 +49%（27.8 → 41.5 ms）。**只有配上 HT20 + nss=2 才划算** |
| cwnd 太小是瓶颈 | `initcwnd 200` | ❌ 无效 | 当时是 1SS + cap32 + 40 ms 的残废配置，B 侧条件已全变；**这条值得在新基线上重测** |
| 换拥塞控制 | BBR 四组合 | ❌ 无效 | 同上，也是旧条件。**新基线上未测**，是 §3.5 ① 的唯一候选 |
| ACK 策略 | `ack_policy=1` | ❌ 无效 | 噪声内 |
| 帧内不做重排 | `rx_order_ms>0` | ❌ 无效 | 噪声内 |
| 队列越深越慢 | cap 32 → 256 | ✅ 反而快 30% | 见 §3.3，它当时是在**限流**不是在缓冲 |
| 接收方向改我们的窗口 | 抬高 metric 抢 master | ❌ 无效 | iPhone 不认我们的选举（MIF 仍 `master=self/h0/m520`） |
| 用 `dumpsys` 文案判状态 | 文案匹配 | ❌ 假阳性 | 「还活着」和「曾经有过」在 dump 里挨着，必须按 `NotificationRecord(` 切段（§7） |
| 让 149 可调 | 改 `opairshare_pas_rx_fixed_channel()` | ❌ 选了也没人跟 | 拒绝点确实在驱动里（`if (freq != 5745) return -EOPNOTSUPP`，自带注释 `Fixed149 profile`），但它不是最硬的一关：**iPhone 在 149 上，我们移走它不跟**。三条换频路径（原地 retune / `iw dev del` + `interface add` / `rmmod` 重装）逐条实测全 `-EOPNOTSUPP`，其中两条还带走了 `wondertap0` 与 `awdld`、MAC 变了——代价是断链，收益是零。方向应该反过来：`WifiNetworkSuggestion.Builder.setBssid()` 把 infra STA 钉到 tap 那里（相邻变量，失败也不断链） |
| 重新编译驱动 | 找源码、改一行、重编 | ✅ 前置已解除 | 「源码不在手上」是错的，正确说法是「**源码不在本仓库**」——上游 `Weverses/OPAirDrop` 有完整覆盖层，`wma_opairshare_pas_mgmt.h` 逐字节就是现在跑的那份。我们发的四个 `.ko` 正是从那条链里出来的，本仓库只是**收货方**。现在的真前置是「基线编译先跑通一遍」（§8.3） |

#### 80 MHz 的三道闸（机制成立，但它是四件事的总和）

为什么值得单开一条：这是**唯一一个不依赖对端配合决策的杠杆**——20 → 80 MHz 是在同一个 MCS 索引上
**直接乘线速率**，只改物理层参数，不需要对端做任何决定。

> **2026-11-20 订正：原判断已被设备读数推翻。** 本节原写「这块 tap 不交付 802.11 ACK、也没有
> BlockAck，对端拿不到速率自适应所需的反馈——『让它提高 MCS』这条路本来就不通」。**BlockAck 是有的**：
> `su -c 'cat …/parameters/opairshare_ba_stats'` 读数为
> `enabled=1 window=64 requests=2 prepared=2 tx_ok=2 tx_failed=0 setup_failed=0 delba=0`——两次 ADDBA
> 全部建立成功、零失败、零拆除。同一时刻 `opairshare_native_raw_ampdu=36959`、
> `agg_stats` 的 `aggregates=48748 msdus=221867`（4.55 msdu/聚合，上限 5）说明 A-MPDU/A-MSDU 也都在跑。
> 所以「MCS 上不去」不是缺反馈，而是**驱动侧的准入判断**把 nss 死锁在 1、mcs 死锁在 7（见闸 (b) 订正）。
> 这一条很重要：它把「提高 MCS」从「对端不配合所以不可能」改回「我们自己放行就能做」。

一份新量的抓包给了这个判断的底：iPhone 13 那份（28039 帧 / 151.6 s）**帧间隔 p50 = 0.93 ms、70.3% 的
帧间隔不到 1 ms**——它已经在背靠背地发，没有空档可挤；而等效线速率只有 **12.9 Mb/s**，落在 MCS1 附近，
离 20 MHz 单流 MCS7 短 GI 的 72.2 Mb/s 差 5.6 倍。**瓶颈是 MCS，不是我们收得慢，也不是它发得懒。**

| 闸 | 内容 | 现状 |
| --- | --- | --- |
| (a) | 驱动放行 `freq≠5745` 与 `bandwidth_enum≠0` | 源码在上游、不在本仓库。注意 `bandwidth` **已经是这个函数的入参**（dmesg 那行 `freq=5220 … bandwidth_enum=0` 里它是实参，只是调用方传 0），所以不是从零写，是「把一个已有参数从 0 改成别的值」——**但改它同样要重编译** |
| (b) | `hdd_validate_channel_and_bandwidth()` 与 `wonder/band_config.c` 的信道表放行（5745 起 80 MHz 是 **ch155 / 中心 5775**） | 5 GHz 表只有 8 条 20 MHz、`.flags = 0`、**没有 ch155/5775**；能力声明里 HT `SUP_WIDTH_20_40`、VHT `SHORT_GI_80`、HE `40MHZ_80MHZ_IN_5G` 全是宽的。~~缺的是信道表条目，不是能力位——这也解释了 `iw` 为什么敢在 VHT 声明为真的接口上写 `width: 20 MHz (no HT)`~~ **2026-11-20 订正：信道表不是放行点。** 上游 `houji-airdrop` 的 `driver/wonder/band_config.c` 信道表**同样只有 36/40/44/48/149/153/157/161，`grep 5775\|155` 零命中**，它照样跑通 VHT80 2SS MCS9 SGI80。真正的闸在 `wlan_hdd_passthru_control.c` 的速率准入：`params->tx_rate.nss != 1 \|\| params->tx_rate.mcs > 7` 把 nss 锁在 1、mcs 锁在 7；放宽成 `nss 1..2 && mcs ≤ 9` 后 VHT80 立刻成立。而 `BW_80 → CH_WIDTH_80MHZ` / `HW_MODE_80_MHZ` 的映射我们补丁链里**已经是完整的**（`0006-stage3-hdd-ops-lifecycle-ddk.patch` 的 `__wlan_hdd_convert_wt_bandwidth_to_phy_ch_width()` 与 `wlan_hdd_wondertap_bw_to_hw_mode_bw()`）——所以这条路是「改一行判断」，不是「补一张表」 |
| (c) | 改完同步 `kernel/source-map.json` 里那条 `sha256` | 否则覆盖层落位校验不过 |
| (d) | **iPhone 也得在 80 MHz 上发** | 这是它决定的，我们无旋钮。AWDL 的社会信道按规格就是 20 MHz |

**关于那 11 字节 HT Capabilities TLV：它是必要条件，而我们现在只写了一半。** presence 帧里唯一能表达
能力的通道就是这组 TLV，但 `action.rs` 里那个常量是 `0x0010`——只有 bit 4（20 MHz 短 GI），**bit 1
（40 MHz）刻意不置**，注释写着「a 40 MHz claim would invite a rate the channel plan cannot carry」。
在接口真是 20 MHz 的前提下宣告更宽，只会招来我们收不下的帧——**这个顾虑本身没错，错的是它依赖的前提**
（见闸 (b)：真正的闸是速率准入，不是信道表）。这条 TLV 确实是有效杠杆（`0x1b`→`0xff` 加短 GI 让
60.38 s → 45.29 s，+33.5%，见 §9 的 857 kB/s）。

**2026-11-20 补：`houji-airdrop` 给出了完整的字节形态。** 它零改 MAC 就协商到 VHT80 2×2 MCS9 SGI80
（866.7 Mbps），条件是 **HT Cap 与 VHT Cap 同时宣告**：

- TLV 7（HT Capabilities，9 字节含 2 字节前缀）`00006f001fffff0000`——HT Cap Info `0x006f`
  = LDPC + 40 MHz + SGI20 + SGI40；A-MPDU Params `0x1f`；RX MCS Mask 2SS 0–15；
- TLV 17（VHT Capabilities Container，14 字节含 `3b 0c` 外层头）`bf0c30000000faff0000faff0000`
  ——Max MPDU 3895、SGI80=1、RX LDPC=1、RX/TX 2SS MCS 0–9（`0xfffa`）。

我们 `action.rs` 里**只有 TLV 7，且是 `0x0010`（20 MHz SGI only）+ 1SS MCS 0–7（`ff ff`）、完全没有
TLV 17**——对端从我们这里读到的能力比硬件低两档。单独改 TLV 不够（对端会按宣告发 VHT80，而驱动准入
会把发出的帧压回 HT20 1SS），但**它是整条链上唯一不需要重编译的一环**，可与 `passthru_awdl_phy` 从
`2`（HT）抬到 `3`（VHT）一起做——`MODULE_PARM_DESC` 写着
`"AWDL data PHY class: 0=legacy OFDM (default), 2=HT, 3=VHT"`，而 `tools/awdl-stack.sh` 的
`TUNE_INSMOD` 现在写的是 `passthru_awdl_phy=2`。**这一档能力是现成的，从来没打开过。**

### 3.5 剩余空间，按性价比排

| # | 方向 | 预期 | 风险 |
| --- | --- | --- | --- |
| ① | `cc=bbr` + `fq` | ✅ **已做**：2.75 → 2.97–3.14（+11%，两轮复现），已固化进脚本（§3.3.2） | 零；`stop` 自动还原 |
| ② | 降低 `status3`（0.98–1.75%）抬高 cwnd 均衡点 | 3.2 → 3.4 级 | 没有干净旋钮，试 mcs 6 属换速率档 |
| ③ | 削慢启动（`initcwnd` / 连接预热） | 全程 2.59 → 3.1 | 只影响开头 2 秒，不动稳态 |
| ④ | ~~多条并行 TCP 流~~ | ❌ **已否**：两条流合计 3.02–3.25，与单流持平（§3.3.1）；旋钮 2026-11-20 已删 | 功能可行（要用户点 N 次确认），但**不提速** |
| ⑤ | 换对端机型 | 未知 | 换机就是换基线（§7） |
| ⑥ | 削 `awdltun0 → bridge` 的那段队列 | 交付能力 26 Mbps 的 29% 缺口 | 在驱动与桥接之间，无用户态旋钮；`capacity` 已不是限制 |

**10 MB/s 的诚实答案**：不是「重编译驱动改信道宽」，也不是并行流。信道宽在 AWDL 上是禁区
（§3.4 闸 d 在 iPhone 手里）；并行流刚被实测否掉；换拥塞控制已在 2026-11-20 做成并固化，
收益 **+11%**，与它同时到手的是一个更硬的结论——**瓶颈是这条链路 ~26 Mbps 的交付能力，
不是窗口、不是 cwnd、不是聚合、不是队列水位**。

所以 10 MB/s 在这套硬件上不可达，不是「还没找到旋钮」，是**交付能力需要翻三倍**，而三个
可信的加粗手段（带宽、并行、窗口）已分别被 §3.4 / §3.3.1 / §3.3 排除。剩下唯一还有 29%
余量的是 ⑥ 那段排队，且它不在用户态。**当前基线 2.97–3.24 稳态就是这套组合的天花板附近。**

> **⚠️ 2026-11-22 追记：上面这段「10 MB/s 不可达」的结论已作废。** 它是在
> `phy=2（HT）` + `BA 响应里 A-MSDU Supported = 0` 这两个错误配置下测出来的，
> 而这两个错误恰好同时把**空口带宽**和**对端是否合包**都锁死在最低档。§3.6 是重写的
> 归因，含参考项目的同类实测对照。旧结论保留在此，因为「26 Mbps 交付能力」这个数字本身
> 没测错——错的是把它当成硬件天花板，它其实是**我们自己的三个 bug 叠加出来的运行点**。

### 3.6 真正的限速点：四个 bug 叠加，不是硬件（2026-11-22，第 4 条同日补入）

**起点是用户给的反证**：同一台 iPhone 和另一台 iPhone 之间传 100 多 MB 只要约 3 秒
（≈ 35 MB/s），所以限制不在对端。参考项目 `ha0xin/houji-airdrop` 对同类 iPhone 的实测
把这件事钉死了——**同一台手机、同一套协议，只切发送档位**：

| 参考项目状态 | 实测 |
| --- | --- |
| 单包模式（保守发送） | **3.42 MB/s** |
| 切入双包 A-MSDU 后 | **13.00 MB/s** 阶段均速，双块窗口 23.56，单块峰值 29.54 |
| `win=64` 基线（`/Upload` 全程 11.970 s / 157.80 MB） | **13.18 MB/s**；后半程稳态 20.35，0.804 s 窗口 26.08，单块突发 41.12 |
| Mac 方向 `win=256` | 64.08 MB/s（0.262 s / 16 MiB）；`win=64` 基准 23.14 |

⇒ **3.42 → 13.00 是 4 倍台阶，不需要换协议，只需要让对端从「单包」切到「A-MSDU」。**
我们当时停在 4.25 MB/s，正好落在参考项目的「单包档」和「双包档」之间——这说明
**我们的发送侧在合包，但对端的发送侧被我们告知「别合包」**。

#### 3.6.1 根因一：`passthru_awdl_phy=2` 是 HT，不是 VHT

`wma_opairshare_pas_mgmt.h:494-514` 的注释是权威：

```
WMI_RATE_PREAMBLE is  OFDM=0 CCK=1 HT=2 VHT=3 HE=4 EHT=5
wondertap's own list is  LEGACY=0 HT=1 VHT=2 HE=3 EHT=4
```

两套尺度**只在 2 和 3 上巧合**。`passthru_awdl_phy` 用的是前一套，所以 `2 = HT(11n)`。
HT 的两个硬约束正好卡住我们的合包：

- 带宽上限 **40 MHz**，而我们 join 的是 80 MHz vdev → 80 MHz 信道被预留、却按窄带发；
- 单 MPDU 上限 **4095 字节**，而 `passthru_awdl_amsdu_bytes=7935` → **超限帧就是
  `followers_dropped=62199` 的全部来源**。

EHT 从这里**够不着**：`preamble_type` 是 5 位字段
（`WMI_TX_SEND_PARAM_PREAMBLE_SET(dword1, 15, 5)`），EHT 需要 `BIT(5)=32`，写进去被掩成 0，
等于「固件自选」，看起来成功其实没设。唯一的 EHT 通道是 `wmi_vdev_param_fixed_rate`
（preamble 在 bit 8）。这条通道**确实是通的、也确实被调用了**（`__wlan_hdd_start_wondertap_intf()`
在 `wlan_hdd_wondertap.c:613` 调 `__wlan_hdd_wondertap_set_fixed_tx_rate()`），
但**它带过去的参数是错的**——见 §3.6.7，那才是最大的一条。

**改动**：`tools/awdl-stack.sh` 里 `phy` 与 `mcs` 跟随 `$STATE/wide`：
宽档 `phy=3`（VHT）+ `mcs=9`，窄档保持 `phy=2`（HT）+ `mcs=7` 不动。
VHT 是第一个既在 80 MHz 上合法、又能携带 11454 字节 MPDU 的 preamble 类；
而 VHT preamble 在 HT20 vdev 上同样非法，所以**不能无条件写 3**，必须跟随 join 的宽度。

#### 3.6.2 根因二：BA 响应里的 A-MSDU Supported 位被写死 0

`wma_opairshare_awdl_ba.h` 原来这一行：

```c
/* Immediate BA; A-MSDU-in-A-MPDU deliberately disabled for this test. */
param = (window << 6) | (e->tid << 2) | BIT(1);
```

bit0 = `amsdusupported`（`cdp_txrx_mob_def.h:515-527` 的
`struct ieee80211_ba_parameterset` 权威布局）。写 0 就是**明确告诉 iPhone「我这边不能收 A-MSDU」**，
对端于是每个 MPDU 只装一个 MSDU —— 这正是参考项目 3.42 MB/s 那一档。
**这条就是我们「发送侧在合包、接收侧却在拒绝」的完整解释。**

**打开它是安全的，因为接收侧根本不需要我们实现拆包**：REO 硬件已经把 A-MSDU 拆成
nbuf chain（每个 nbuf 一个 MSDU）才交给主机。`dp_rx.c:2982-3003` 的判据是

```c
is_not_amsdu = qdf_nbuf_is_rx_chfrag_start(nbuf) & qdf_nbuf_is_rx_chfrag_end(nbuf);
```

`start && end` 同时真 = 单个完整 nbuf = 非 A-MSDU；否则就是 chain = 已拆好的 A-MSDU。

**改动**：新增 `passthru_awdl_ba_amsdu`（0644，可运行期回滚），默认 **1**；
`opairshare_ba_stats` 增加 `amsdu=` 字段做回读。

#### 3.6.3 根因三：`amsdu_max=5` / `amsdu_bytes=7935` 是 HT 时代的天花板

吞吐 ≈ **在途字节 / 单帧延迟**。`opairshare_tx_metrics` 实测 `wmi_prepare` **46.4 ms/帧**
（188564 帧累计 8746 s，`wma_opairshare_metrics.h:180-185` 证明这是「递交 WMI → 完成回调」的等待，
约等于一个 AWDL slot）。在途窗口由 **BA 窗口**决定，`window_eff=64` 来自 iPhone 在 ADDBA Request
里填的 buffer size（`if (param >> 6) window = min_t(..., param >> 6)`）——**这是对端的接收缓存
能力，我们单方面改不了**。

所以唯一能增加在途字节的手段，就是**让每个 MPDU 装更多 MSDU**：

```
在途字节 = window_eff(64) × 每 MPDU 字节
每 MPDU 字节 ≈ 26 + n × (14 + msdu_len)
```

`amsdu_max` 5 → **8**，`amsdu_bytes` 7935 → **11454**（802.11-2016 的 VHT A-MSDU 上限，
`opairshare_amsdu_bytes_set()` 原先把上限硬拒在 7935，现改为 `OPAIRSHARE_AMSDU_BYTES_MAX`）。
两项同样只跟随宽档生效，窄档仍是 5 / 7935。8 × 1200 字节的 MSDU 只需
26 + 8×(14+1200) + 7×2 = **9754 字节**，字节预算先于子帧数触顶，两边都留有余量。

#### 3.6.4 46.4 ms/帧是「帧在途寿命」，不是「准备耗时」——这条会误导人

`opairshare_tx_metrics` 里叫 `wmi_prepare` 的那个直方图**不度量准备**。读
`wma_opairshare_metrics.h:130-144`：`opairshare_metric_wmi()` 在 WMI 递交处给
`r->wmi = ktime_get_ns()` 打一个时间戳，`opairshare_metric_remove()` 在完成回调里算
`now - r->wmi`。所以它量的是**递交 → 完成的整段在途时间**，包含等对端 AWDL 窗口的全部时间。
名字取错了，别照名字推结论。

同一份快照里还有一组更小的数：`event_callback` 均值只有 **7.6 ms**（1436 s / 188564），
说明「完成回调 → 上层看到事件」这段并不慢，慢的是它在等空口。

直方图桶的刻度在 `:40-41`（`100µs / 500µs / 1ms / 5ms / 10ms / 25ms / 50ms / 100ms / 250ms / 1s`），
188564 帧的分布是：

```text
bins = 0,0,0, 29356, 16146, 63365, 13779, 25727, 40019, 172, 0
       ≤5ms  ≤10ms  ≤25ms  ≤50ms  ≤100ms ≤250ms ≤1s
```

三个峰：**≈25 ms（33.6%）**、**≈100–250 ms（21.2%）**、**≤5 ms（15.6%）**。这不是一个固定的
65 ms slot，是「等对端的可用窗口」被切成几种不同长度的等待。均值 46.4 ms 由那条 100–250 ms
的长尾拖着——**长尾才是吞吐的主要损失**，不是均值本身。

#### 3.6.5 用 2026-11-22 01:56 的真机快照把吞吐公式钉死

设备当时跑的是**改前那一颗**（105 个 `passthru_*`，没有 `passthru_awdl_ba_amsdu`，
`amsdu_max` 卡在 5、`amsdu_bytes` 卡在 7935；`phy=3` / `mcs=9` 已在运行时写入）。
`opairshare_tx_metrics` / `opairshare_agg_stats` / `opairshare_ba_stats` 三份读数是：

```text
data_accepted=717297 data_completed=188564
aggregates=116799  msdus=496487  bytes=503905415  followers_dropped=62199
mode=2 max=5 cap=7935 phy=3 mcs=9
p1=2953 p2=2921 p3=4821 p4=10846 p5=52481  deferrals=107452
enabled=1 window=256 window_eff=64 requests=22 prepared=22 tx_ok=20
native  n=188564 sum_ns=8746546240998      → 均值 46.4 ms
queue   n=717297 sum_ns=224255174036       → 均值 0.31 ms
```

从这组数直接算出来的量，一条都不需要猜：

| 量 | 算法 | 值 |
| --- | --- | --- |
| 平均 MSDU | `bytes / msdus` = 503905415 / 496487 | **1015 B**（几乎全是 TCP 满段） |
| 每次聚合实际装几个 | `msdus / aggregates` = 496487 / 116799 | **4.25 个**（上限 5，饱和度 85%） |
| 单帧平均字节 | `bytes / aggregates` | 4314 B |
| 未参与聚合的递交 | `data_completed - aggregates` = 188564 − 116799 | **71765 次（占 38%）**，每次一个 MPDU 只驮一个纯 ACK |
| 观测到的在途上限 | `peak_native_live` | **64** |
| 吞吐 | 64 × 4314 / 46.4 ms | **5.95 MB/s**（实测 4.25，同量级） |

`p1..p5 = 2953 / 2921 / 4821 / 10846 / 52481`（`opairshare_tx_pack_stats`）证实了这一点：
真正装了 5 个子帧的聚合有 52481 次，是主力；而 `p1` 那 2953 次只是恰好凑到 1 个的边角。
**85% 的饱和度说明 `amsdu_max` 5 已经基本用满，把它抬到 8 是能直接兑换成字节的。**

**`peak_native_live=64` 是观测器自己的天花板，不是数据面的。** `OPAIRSHARE_METRIC_SLOTS` 就是 64
（`wma_opairshare_metrics.h:4`），槽满后 `opairshare_metric_wmi()` 找不到空槽就放弃打戳，
`errors=174712` 正是这个计数。所以「在途 64」这个数字只能当下界读。

同时 `window_eff=64` 与它数值相同，来自 iPhone 在 ADDBA Request 里填的 buffer size
（`wma_opairshare_awdl_ba.h:142-158`，`if (param >> 6) window = min_t(u16, window, param >> 6)`）。
**对端的接收缓存我们单方面改不了**；参考项目在同样的 win=64 下能到 13.18 MB/s，
说明窗口从来不是它的瓶颈。于是剩下能动的只有一项：

```text
吞吐 ≈ window_eff × 每 MPDU 字节 / 帧在途寿命
每 MPDU 字节 = 26 + n × (14 + 1015)   （n = 每 A-MSDU 的子帧数）
```

| n（`amsdu_max`） | 每 MPDU 字节 | 吞吐（窗口 64、寿命 46.4 ms，饱和时） |
| --- | --- | --- |
| 5（改前） | 5 197 | 7.2 MB/s |
| **8（改后）** | **8 305** | **11.5 MB/s** |

而 `p5=52481` 说明**上限 5 在 85% 的时间里就已经是紧约束**：队列里能凑到 5 个就停手。
把上限抬到 8 之后，这 52481 次里凡是队列还有余量的，都能再塞 3 个 MSDU 进去。
但另一半工作量在 `p1+p2+p3+p4 = 21541` 这一次群和 71765 次纯 ACK 递交上——那是**队列深度不足**，
要调 `collect_us` 或让生产端一次写多一点，光靠提高上限解决不了。

#### 3.6.6 仍未闭合的一条：硬件 A-MPDU（DP TX）完全没被用到

每帧单独经 `wlan_mgmt_txrx_mgmt_frame_tx()` 递交（`wma_opairshare_pas_mgmt.h:1418`），
走的是 WMI mgmt 通道，`mgmt.tx_param.frame_type = WMA_TX_SEND_DATA_TYPE` 只是让固件按数据帧处理。
这意味着**硬件 A-MPDU（DP TX 路径的能力）根本没被用到**——这正是用户「硬件层聚合肯定还没实现」
的判断落点。参考项目靠 `pt_tx_ampdu=1` 打开这条路径，而 `tx_ampdu` / `rx_msdu_buf` /
`remote_reo` / `pt_exclusive` 四个开关在我们的驱动里 `grep` 为空，**根本不存在**。
这是下一轮的主攻方向，不是本轮能收口的。

**代码级定论（2026-11-22 补）。** 这一条不再是推断，是把 `pas_mgmt.h` 全文按发送出口扫过一遍：

```
$ grep -n "mgmt_frame_tx\|ndo_start_xmit\|passthru_xmit\|opairshare_wt_tx" wma_opairshare_pas_mgmt.h
1418:  status = wlan_mgmt_txrx_mgmt_frame_tx(peer, wma->mac_context, buf,
```

**整个文件只有一个发送出口**，没有任何一条路径进 `ndo_start_xmit` / DP TX。所以：

- 软件 A-MSDU 合包（8 个子帧塞进 1 个 MPDU）**是真的在生效**，`opairshare_tx_pack_stats`
  的 `p1..p8` 直方图就是它的账本；
- 但合出来的那个 MPDU 是**一条 WMI `MGMT_TX`/`TX_SEND` 命令**，固件侧收在 mgmt 通道上、
  没有 TID 队列、没有 `desc_id` 链，**结构上没有地方拼 A-MPDU**。

这就是「我们自己合包了、对端却没有 A-MPDU 可解」的机制解释。要打开硬件 A-MPDU，缺的不是
一个开关值，而是**换发送路径**：建 DP peer → 拿到 TID 队列 → 走 `cdp_txrx` 递交。
`O(500)` 行的改动量，且要与 `Listener` 的流控、`LiveCard` 的取消语义重新对齐，
不能和当前这颗还在验证的模块混在一次编译里。

#### 3.6.7【最大的一条】固定速率缓存把 vdev 锁死在 HT20 / 1SS / MCS7

前三条都在我们自己的 passthru 参数上，这一条不在。它是**驱动启动时被喂进去的默认值**。

**证据是一行 dmesg**，来自一次正常 `boot`：

```
[wonder][ven_cmd] Set fixed TX rate: preamble=1, bw=0, gi=1, nss=1, mcs=7
[wonder][wondertap] wondertap is not active; caching incoming fixed TX rate settings.
[wlan_hdd_wondertap_init: Fixed Tx rate preamble:1 bw:0 gi:1 nss:1 mcs:7
```

按 wonder 自己的尺度（`wonder_ven_cmd.h:135-148`）：
`preamble 1 = HT`、`bw 0 = 20 MHz`、`gi 1 = SGI`、`nss 1 = 单流`、`mcs 7`。
即 **HT20 / 1 SS / MCS7 = 72 Mbps PHY**。而 `iw dev wonder0 info` 同时显示
`channel 149, width: 80 MHz` —— 两个数字互相矛盾，矛盾就是线索。

**链路是这样断的**（`wlan_hdd_wondertap.c:406-455`，`__wlan_hdd_wondertap_set_fixed_tx_rate()`）：

```c
rate_code = hdd_assemble_rate_code(preamble, params->nss - 1, params->mcs);
wma_cli_set_command(vdev_id, wmi_vdev_param_fixed_rate, rate_code, VDEV_CMD);
wma_cli_set_command(vdev_id, wmi_vdev_param_sgi,   gi,             VDEV_CMD);
wma_cli_set_command(vdev_id, wmi_vdev_param_chwidth, params->bw,  VDEV_CMD);  /* ← 0 */
```

最后一行把 vdev 的信道宽度**写回 20 MHz**。而它在启动序列里紧跟
`__wlan_hdd_set_wondertap_channel(..., 80 MHz)`（`:606-613`），于是：

| 谁 | 用的是哪个字段 | 结果 |
| --- | --- | --- |
| 信道设置 | `params->channel.bandwidth`（来自 `wonder-ven freq … 2`） | 80 MHz |
| **vdev 宽度** | **`params->tx_rate.bw`（来自 `feed` 默认 0）** | **20 MHz** ← 覆盖 |
| peer 描述符 `txrx_desc.bw`（`:639-641`） | `params->channel.bandwidth` | 80 MHz |

**vdev 跑 20 MHz，peer 却按 80 MHz 注册**。这既解释了「发送层还是 20 MHz」，
也解释了一直没被解释的几何不一致。

**一个必须先记住的顺序事实**：`hdd_start_adapter()` 在 `:561` 就把 vdev 启动了，
而紧跟在后的 `set_wondertap_channel`（`:606`）与 `set_fixed_tx_rate`（`:613`）
**都是 vdev 启动之后的下发**。所以这不是「启动前把宽度定错」，而是
**启动后连续两次改宽度，后一次把前一次盖掉**：

```
:561  hdd_start_adapter()                      vdev UP（此时宽度取自 vdev 自身 chanmode）
:606  __wlan_hdd_set_wondertap_channel(80)     vdev chwidth := 80
:613  __wlan_hdd_wondertap_set_fixed_tx_rate() vdev chwidth := params->bw  ← 0，盖回 20
```

于是修法的正确表述不是「改对一个数字」，而是**让这两次写入一致**。脚本在 `up` 之前
把 `rate` 缓存成 `bw=2` 之后，`:606` 和 `:613` 写的是同一个 80 MHz，谁后谁前都不再要紧。
反过来说：只要还有人绕开脚本直接 `wonder-ven feed` 起栈，这个 bug 就会原样回来。

**为什么这个 bug 能藏两个月不被怀疑。** 因为**每帧的带宽一直是对的**。
`pas_mgmt.h:1385-1387` 的 `mgmt.tx_param.bw_mask` 取自
`opairshare_passthru_want_wide()`，而这个函数读的是 `opairshare_wt_req_bw`
（`wlan_hdd_wondertap.c:105-108`），后者的赋值点是 `:271`：

```c
/* __wlan_hdd_set_wondertap_channel() */
WRITE_ONCE(opairshare_wt_req_bw, (unsigned int)params->bandwidth);  /* ← 来自 `freq … 2` = 80 */
```

也就是说：**帧级 desc 说 80 MHz、`iw` 说 80 MHz、peer 描述符说 80 MHz，
只有 vdev 自己那条 `fixed_rate` 附带写进去的 chwidth 是 20 MHz。**
三个「80」把一个「20」围住了，所以任何只看帧、只看 `iw`、只看 peer 的验证都会通过。
唯一能暴露它的是 dmesg 那一行回显——而它一直被当成无害的启动日志翻过去了。

这也顺带说明：`phy=3 / mcs=9`（§3.6.1）改的是**同一批每帧 desc 字段**，改得再对，
只要 vdev 挂着 HT20 的固定速率，空口上就还是 20 MHz。**这就是这条必须排在第一位的机制原因**，
不是排期上的偏好。

**根因在 `wonder-ven feed`**：它一次填四个缓存（reg / freq / rate / filter），
`rate` 那一格是硬编码的 `1 0 1 1 7`。`tools/awdl-stack.sh` 里 `grep` 不到任何
`wonder-ven … rate` 调用，所以这一格从来没人改过。

**修法不需要重编驱动**，因为 `wondertap_set_fixed_tx_rate()`（`wondertap.c:203-224`）
**总是先写缓存**，只有 `wondertap_init`（第一次 `ip link set up`）才把缓存推进 vdev：

```bash
# before: feed caches rate = 1/0/1/1/7 (HT20, 1SS, MCS7)
# after : 显式下发，宽档 2/2/1/2/9 = VHT80 / SGI / 2SS / MCS9 = 866 Mbps PHY
"$BASE/wonder-ven" "$AWDL_IF" rate "$VEN_PRE" "$VEN_BW" "$VEN_GI" "$VEN_NSS" "$VEN_MCS"
```

放置点很关键：**在 `feed` 之后、`ip link set up` 之前**（`awdl-stack.sh` 的 `awdl_link_up()` 内，
紧跟 wide 的 `freq … 2`）。放在 `up` 之后不会生效——那时只会走 provider 的
`probe_rate()`，它只打印。

**带宽这一格的取值必须逐档核对过，别信「看起来一样」。** `params->bw` 由 wonder 定义，
而 `wmi_vdev_param_chwidth` 的入参由 WMI 定义，两套枚举**前四档同构、第五档开始分叉**：

```
wonder WONDER_VEN_ATTR_CHANNEL_BW_*  (wonder_ven_cmd.h:48-52)
    20=0   40=1   80=2   160=3   320=4

WMI wmi_channel_width                 (fw-api/fw/wmi_unified.h:11263-11276)
    20=0   40=1   80=2   160=3   80+80=4   5=5   10=6   165=8(错位见义)   320=9
```

前四档同构，所以 `VEN_BW=2` 确实落到 `WMI_CHAN_WIDTH_80` —— 这是这一处能用一个裸数字
直接透传过去的原因，也是它一直没被怀疑的原因。但 **`320 MHz` 不同构**：wonder 的 `4`
在 WMI 是 `80+80`。以后谁想试 320 直接把 `4` 传下去，会静默变成 80+80 双连，
而且 dmesg 只会回显 `bw=4`，看不出错。真要上 320，得在
`__wlan_hdd_wondertap_set_fixed_tx_rate()` 里加一张显式映射表。

**为什么先给 VHT 不给 EHT。** EHT 在这条路上是合法的：`wlan_hdd_convert_wonder_preamble_to_wmi()`
有 EHT 分支，`ASSEMBLE_RATECODE_V1 = (1<<28) | (pream<<8) | (nss<<5) | rate`
把 preamble 放在第 8 位起、rate 占 4 位（够 MCS13），**不是 `preamble_type` 那种被掩掉的 5 位**。
但 vdev 是以 **chanmode VHT80** 建的（`awdl-stack.sh:1061-1063` 记录过
`chanmode 10 (VHT80), center_chan 5775, Tx SS 2`），而高于 vdev 自身模式的固定速率
没有速率表可查。**要让 EHT 真正成立，要抬的是 vdev 模式，不是这个数字。**
脚本留了环境变量口子：`VEN_PRE=4 tools/awdl-stack.sh boot` 可直接试。

**为什么这一条比前三条更靠前。** §3.6.1 的 `phy=2→3` 改的是**每帧的 tx_param**，
而 vdev 上挂着一条 `wmi_vdev_param_fixed_rate` 时，固件按固定速率发，每帧的 desc 优先级更低。
换句话说：**只要这颗默认值不动，前三条改动的收益都上不了空口。**

**真机验证（2026-11-22 03:05，冷启动一次通过）。** dmesg 完整记下了「旧值被纠正」的全过程：

```
[2831.225736] SET_FREQUENCY: freq=5745 MHz, bandwidth=0        ← feed 默认
[2831.225742] Set fixed TX rate: preamble=1, bw=0, ...         ← feed 默认（旧）
[2831.233360] SET_FREQUENCY: freq=5745 MHz, bandwidth=2        ← 脚本 `freq … 2`
[2831.238957] Set fixed TX rate: preamble=2, bw=2, ...         ← 脚本 `rate 2 2 1 2 9`
[2831.245598] wondertap capabilities: 0x1F02
[2831.245600]     Channel: Freq=5745, Bw=2
[2831.245602]     Fixed TxRate:  Pre=2, Mcs=9, Gi=1, Bw=2       ← wondertap 最终缓存
[2831.245626] wlan_hdd_wondertap_init: Fixed Tx rate preamble:2 bw:2 gi:1 nss:2 mcs:9
```

判据只有一条：**`wlan_hdd_wondertap_init` 那一行必须是 `bw:2`**。它是
`params->tx_rate` 进了 `__wlan_hdd_wondertap_set_fixed_tx_rate()` 之后的回显，
也就是最终会写进 `wmi_vdev_param_chwidth` 的那个值。`bw:0` 表示修复没生效。

顺带一个副产物：接收侧首次读到 iPhone 的 16 槽位图，
`peer_map=149,149,149,0,0,0,0,149,6,149,149,149,0,0,0,149`
（8 个在 149、1 个在 6、7 个未分配）——这是对端**未进入传输态**的特征。
对端真正在传的时候应该是 15×149 + 1×6，可以用这一行判断「iPhone 到底有没有在发」。

#### 3.6.8 反例：那条 bug 是承重的——把 `bw=2` 修对之后，两机互相看不见了（2026-11-22 05:30）

**这一幕必须记下来，因为它把 §3.6.7 的结论从「纯收益」改成了「有代价的收益」。**

修好固定速率、冷启动一次通过、`bw:2` 落地、`p1..p8` 齐了之后，用户报：
**「现在彼此不能在自己的分享面板看到对方了」**。首次真机对照给出的分裂非常干净：

| | 旧会话（11-17，能互见、4.25 MB/s） | 修好之后（11-22 05:30） |
| --- | --- | --- |
| daemon `rx` | **127358p / 164 MB** | **0p / 0 B** |
| `psf` / `peer_map` / `other` | 正常涨 | **仍在涨**（+8/s, +40/s） |
| `bad`（`opairshare_aux_dropped`） | 1 | **0** |
| vdev 固定速率 | `1/0/1/1/7`（bug 兜着） | `2/2/1/2/9` |

`psf` 和 `peer_map` 还在涨，说明**接收方向没坏**——iPhone 的 presence 帧我们照收，
它的槽位图照读（`8×149 + 1×6`，正是**非传输态**）。坏的是**反方向**：我们以
VHT80 / 2 SS / MCS9 发射，而 20 MHz 的接收机解不出 80 MHz 的 PPDU，
于是 iPhone 的分享面板里没有我们；它看不见我们，也就不向我们发数据，于是 `rx=0`。

**这不是巧合，是因果链闭合**：`wonder-ven feed` 硬编码 `1/0/1/1/7`，脚本从不下发，
所以 `wmi_vdev_param_chwidth` 一直把「80 MHz」的 vdev 静默压回 20 MHz。
**那个 bug 一直在替发现阶段兜底。** 4.25 MB/s 和「能互见」是同一个原因带来的。

**由此得出的硬约束（下一轮的设计前提）：**

1. **发现阶段必须 20 MHz。** 对端非传输态（槽位图 8×149+1×6）只按 20 MHz 收，
   任何高于它的 PPDU 都收不到。`VEN_PRE_DEF/VEN_BW_DEF/VEN_NSS_DEF/VEN_MCS_DEF`
   在 wide 档里因此固定为 `1/0/1/1/7`，**与窄档相同**；宽档只保留
   `AMSDU_MAX=8` / `AMSDU_BYTES=11454`（软件合包上限，与空口宽度无关）。
2. **宽度要两阶段**：先窄发现，等对端槽位图变成 ~15×149 + 1×6（进入传输态）
   再把 vdev 抬到 80 MHz。**这个切换目前在驱动里不存在**，是 §3.6.7 之后的
   第一优先事项——不实现它，`bw=2` 只是一个能让对方消失的开关。
3. **热改 `freq` 会被拒**：`wonder-ven wonder0 freq 5745 0` 在 vdev 已 up 时返回
   `rc=-16 EBUSY`。所以两阶段切换不能靠重下 `freq`，必须走驱动内的 vdev restart。
4. **`wonder-ven rate` 热改是有效的**：`up` 之后下发会走 provider 的
   `set_fixed_tx_rate`（不是 `probe_rate`），dmesg 回显即证。这条是排查时的救命通道。

#### 3.6.9 用户五个疑问的逐条回答


| 问题 | 答案 | 依据 |
| --- | --- | --- |
| 免预绑定动态多槽位（8 槽）实现了吗？ | **机制不同但等效能力在**：`passthru_awdl_target_*` + `opairshare_target_ensure()` 按对端 MAC 动态建 peer，不需要预绑定；参考项目的 `pt_remote_multi` 是另一套写法 | `wma_opairshare_awdl_target.h` |
| PSF / MIF 时钟同步与主节点选举实现了吗？ | **已实现** | `crates/awdl/src/action.rs`、`link.rs`、`schedule.rs` |
| 接收侧 A-MSDU 拆包要我们写吗？ | **不用**，REO 硬件已拆成 nbuf chain | `dp_rx.c:2982-3003` |
| 16 槽里只有 8 个在 149，是 80 MHz 没生效吗？ | **不是**。对端不在传输态时只用少数槽位；传输态才会铺满。真正的 80 MHz 失效在 `phy=2`（§3.6.1） | 本轮 |
| 4 MB/s 是历史最好成绩吗？ | **是**，但它是一个被三个 bug 压住的运行点，不是天花板 | §3.6 |

#### 3.6.10【新的第一根因，取代 §3.6.8 的结论】每一帧都在 `bind` 处被丢光（2026-11-22）

§3.6.8 把「两机互不可见」归因为「我们以 80 MHz 发射、对端只按 20 MHz 收」。
**那条因果链不是第一因。** 真机计数器给出的硬证据是：**我们一帧都没发出去过**，
与带宽、速率、聚合全部无关。

等值证据（比 §3.6.8 的「+129 对 +130 同比」更硬）：

```
--flow--
enabled=1 paused=0 held=0 pending=0 busy=0 stops=0 wakes=0 releases=0
errors=270936 holds=0 puts=0 high=256 low=128
--bridge--
accepted=0  dropped=270936  pending=0
```

`bridge_dropped == flow.errors == 270936`，而 `accepted == 0`。
`flow.errors` 在 `wma_opairshare_flow_control.h` 里只有四处自增（`bind` / `stop` / `wake` / `forget`），
而 `stops=0 wakes=0 releases=0 puts=0 held=0` 说明后三条路径**一次都没执行过**
（`opairshare_flow_dev` 恒为 NULL），因此 270936 全部来自 `bind`，且每一次都恰好对应一次
`bridge_dropped`——中间没有第二个丢弃来源。这也**反证源 MAC 比对已通过**（否则
`reject_invalid` 涨而 `errors` 不涨，实测两者等值）。

**根因是两行代码互相矛盾。** `wlan_hdd_main.c:7455`（补丁 `0006` 刻意这么写，不是手滑）：

```c
dev = alloc_netdev_mqs(..., (session_type == QDF_PASSTHRU_MODE ? 1 : NUM_TX_QUEUES), NUM_RX_QUEUES);
```

⇒ `wondertap0` 只有 **1** 个 TX 队列（设备侧 `ip -d link show wondertap0` →
`numtxqueues 1 numrxqueues 5`，`/sys/class/net/wondertap0/queues/` 只有 `tx-0`）。

而 `wlan_hdd_tx_rx.c:1120` 要求 **17**：

```c
if (... || dev->real_num_tx_queues != NUM_TX_QUEUES)
	return -EINVAL;
```

`1 != 17`，准入校验**永远不可能通过**。完整链路：

```
hdd_hard_start_xmit_passthru()        [0006 挂的 ndo: wlan_passthrough_ops.ndo_start_xmit]
  → opairshare_pas_mgmt_xmit(skb, vdev, dev)      dev = wondertap0
  → 源 MAC 比对通过
  → opairshare_flow_bind_locked(dev)
  → opairshare_flow_control_locked(dev, 3)
  → opairshare_pas_flow_queue_control(dev, 3, 0)
  → real_num_tx_queues != NUM_TX_QUEUES → -EINVAL
  → goto invalid; dev_kfree_skb_any(skb); return true;   ← skb 被吞，绝不回退普通路径
```

**单变量实验（零重编，只把 `passthru_awdl_backpressure` 置 0 短路 `bind`）：**

| 计数器 | `backpressure=1` | `backpressure=0` |
| --- | --- | --- |
| `tx_metrics.accepted` | **0** | **21** |
| `tx_metrics.reject_invalid` | 每 5 s +129（累计 270936） | **0** |
| `bridge.accepted / dropped` | 0 / 270936 | **21 / 8** |
| `flow.errors` | 270936 | **0** |

⇒ 根因确认无疑。`worker_drop=8 == dropped=8` 说明剩下 8 个是 worker 侧丢弃
（链路随后崩掉所致，见下），不是准入丢弃。

**「把 wondertap0 撑到 17 队列」是错方向**：单队列是 `0006` 的刻意设计——PAS tap 上所有帧
共一条 FIFO，保证 AWDL 单 TID 的严格顺序；且 `:1024` 早有
`qdf_min(dev->num_tx_queues, NUM_TX_QUEUES)` 这种「按小值处理」的既有口径。

**本次修复（都在 `wlan_hdd_tx_rx.c`）：**

1. `:1120` 放宽为 `!real_num_tx_queues || real_num_tx_queues > NUM_TX_QUEUES`（允许 1..17）。
2. 新增 `hdd_tx_subqueue_idx()`：把任意 AC 索引折到真实存在的队列上
   （满 17 队列时是恒等，单队列时全折到 0）。
3. 单队列时 wake 走一次 `netif_wake_queue(dev)`，不再逐 AC `netif_wake_subqueue`
   ——后者在 1 队列 netdev 上会 `netif_wake_subqueue(dev, 8..16)`，**越界写 `dev->_tx[]`**。
   同一夹取也套到 `hdd_wake_queues_for_ac` / `hdd_stop_queues_for_ac` /
   `wlan_hdd_wake_non_priority_queue` / `wlan_hdd_stop_non_priority_queue` 和两处裸写的
   `HDD_LINUX_AC_HI_PRIO * TX_QUEUES_PER_AC`（=16）上。**必须改在 helper 层**，因为原生
   `wlan_hdd_netif_queue_control` 也会在 PAS 适配器上被调用（`wlan_hdd_wondertap.c` 的
   stop 与 force_cleanup 两处）；对 17 队列设备行为零变化。

**顺带修掉的第二个 bug：拆除时 `wondertap0` 引用泄漏。** 单变量实验里 `boot` 之后链路崩成
`iface=absent`，dmesg 无限刷 `unregister_netdevice: waiting for wondertap0 to become free.
Usage count = 2`。根因：`wlan_hdd_opairshare_aux_rx.h:361` 的 `dev_hold(parent->dev)` 持有
`wondertap0` 一个引用，只有 `opairshare_aux_route_stop_locked()` 才还；而
`opairshare_pas_aux_pre_stop()` **定义了却从没被调用过**（注释写着 "Generic teardown removes
routing before either adapter can disappear"）。SSR 的 `force_cleanup` 路径既不调
`__wlan_hdd_stop_wondertap_intf()`（那里有 `opairshare_aux_stop()`），也不调它。修法：在
`__wlan_hdd_destroy_wondertap_intf()` 开头调用（三条拆除路径的唯一汇合点，且幂等），
并在 `wlan_hdd_wondertap_unregister_ops()` 的 `force_cleanup` 分支里提前摘一次。

**重编后判据**：`tx_metrics.accepted>0` 且 `reject_invalid` 停止增长；`bridge.submitted>0`；
`awdl_agg_stats.aggregates>0`；`STAT` 的 rx 从 0 变非 0。

**旧驱动上的活体复现（2026-11-22 09:41，引用泄漏最硬的一条证据）。** 带修复的驱动编好、
但**还没换到设备上**时，对仍然跑着旧驱动的那台机器执行 `sh awdl-stack.sh stop`，脚本 180 s
内没返回（被 `SIGTERM` 掐断），随后：

```
$ lsmod | grep -E "^qca_cld3"
qca_cld3_peach_v2   12673024  -1        ← refcount -1 == MODULE_STATE_GOING，rmmod 已开始但退不出去
$ dmesg | tail
unregister_netdevice: waiting for wondertap0 to become free. Usage count = 2
```

这不是推演：`-1` 只有 `module_put()` 把计数放到 0 之后 lib/module 才写的状态，说明
`delete_module` **已经进门**，卡在 `unregister_netdevice()` 里等那个永不归还的 `dev_hold`。
`Usage count = 2` 也吻合「netdev 自身 1 份 + aux 路由 1 份」。设备此后只能靠重启复活——
所以 §3.6.10 最后那两处 `opairshare_pas_aux_pre_stop()` 是**必须**的，不是顺手。

**载荷重打包坐标（下次只用抄这一段）。**

```
# 1) strip（必须写全绝对路径；wsl.exe bash -lc 会把 $VAR 吞成空，
#    写成 $S --strip-debug 会得到 "bash: --strip-debug: command not found"，exit 127）
/mnt/opairshare-build/opairshare/kernel_platform/prebuilts/clang/host/linux-x86/clang-r510928/bin/llvm-strip \
    --strip-debug /mnt/opairshare-build/baseline-wlan/stripped.ko
# 513 436 648 → 20 037 200（只去 .debug_*，保留 .symtab；--strip-all 会让 insmod 报
# unresolved relocation）
# 2) 压缩（-n 不写时间戳，哈希才可复现）
gzip -9 -n -c stripped.ko > stripped.ko.gz        # 5 280 202
# 3) 落到仓库
cp stripped.ko.gz repo-src/kernel/qca_cld3_peach_v2.ko.gz
```

新载荷：原始 20 037 200 B / gz 5 280 202 B /
`eac00ca06a9bad02a451fb17b363115bb9144e7fa8e925f28d9d7ddca60c14c6`。
**校验方法（比看大小可靠）**：`llvm-size` 比对 `.text`——旧 9 152 927 → 新 9 153 803
（**+876 B**），`.data`/`.bss` 逐字节相同。只涨代码不涨数据，正是「只改了函数体、
没碰任何全局/结构布局」的形态，可以和「喂错文件」区分开。
`vermagic` 两边都是 `6.6.118-android15-8-o-g6028f47fadda-4k SMP preempt mod_unload modversions aarch64`。

**README 的载荷表此前是错的，别照抄历史值。** 表里 qca 那行写的
`20 050 688 / 5 282 983 / 152b93ad…` 与仓库里实际文件（`20 036 152 / 5 279 815 / 6978cc8a…`）
对不上——上一次换载荷没有回头改表。现在表里是这次编的实测值。其余四行（`mac80211`
`5fb77cc7`、`provider b13eb6e1`、`wonder f06bf8f1`、`wonder-ven 05addc36`）与文件相符，
反证只有 qca 这一行是陈的。

**APK 会自己带上新驱动。** `app/build.gradle:176` 的 `stageKernelAssets` 在 `preBuild`
时从 `kernel/*.ko.gz` gunzip 出裸 `.ko`，并**重新生成** `SHA256SUMS`（不签入仓库——
`awdld` 每次 `cargo build` 都变，陈旧哈希会让设备端 `sha256sum -c` 拒掉刚编的载荷）。
所以 `assembleDebug` 之后 APK 里就是新驱动，设备端 `DriverSetup.command()` 的
`sha256sum -c` 一旦不匹配就会用 assets 覆盖设备目录——**只推载荷到设备而不重编 APK，
下次 App 启动会把旧驱动盖回来**。

#### 3.6.11【第三个泄漏：流量控制那份 `dev_hold` 没有强制归还路径】并把固件崩溃夹到 0.9 秒（2026-11-22）

§3.6.10 修掉 aux 路由那一份 `dev_hold` 之后，`Usage count = 2` **仍在刷屏**——因为
`wondertap0` 上还挂着**第二份**引用，所有者是流量控制，不是 aux 路由。两处 `dev_hold`
各有一份、且各自的 `dev_put` 路径完全不同：

| 持有者 | `dev_hold` | 唯一的 `dev_put` | SSR 路径上能不能执行 |
| --- | --- | --- | --- |
| aux 路由 | `wlan_hdd_opairshare_aux_rx.h:360` | `opairshare_aux_route_stop_locked():197` | §3.6.10 已补，能 |
| 流量控制 | `wma_opairshare_flow_control.h:44` | `opairshare_flow_forget_locked():97` | **不能**，见下 |

`opairshare_flow_forget_locked()` 有两道拒绝闸，固件一死就**永久成立**：

```c
:89  if (opairshare_bridge_accepting || atomic64_read(&opairshare_bridge_pending))
:90  	return -EBUSY;
:91  /* Normal Stop has drained all native callbacks. Never wake during teardown. */
:92  if (opairshare_flow_control_locked(opairshare_flow_dev, 2) < 0) {
:93  	opairshare_flow_errors++;
:94  	return -EIO;
```

`accepting` / `pending` 的清理都依赖「活着的 MAC 回执」，固件一死就再也等不到，于是
`-EBUSY` 恒成立 ⇒ `:97 dev_put()` 永不执行 ⇒ `unregister_netdevice()` 每十秒打一次
`Usage count = 2`。

**一个容易误判的点（本轮实测纠正）**：这个等待**并不是**「一直持有 RTNL」。`netdev_run_todo()`
是**先放过 RTNL 再去等引用归零**的，所以设备卡在这一步时 `ip -o link show`、`ls /sys/class/net`
仍然正常返回（实测如此），`wondertap0` 也已经从 sysfs 与 `/proc/net/dev` 里消失——名字释放了。
真正的代价是三样：netdev 结构体永久泄漏、dmesg 每十秒一行、以及 `rmmod` 的 driver exit 路径
会在同一处再撞一次。**因此「`grep -c "waiting for wondertap0"` 归零」这条判据在已经卡过一次的
机器上永远不可能成立**，别拿它当唯一标准（见下面的判据修正）。

**走 `opairshare_pas_mgmt_stop()` 也救不了。** 它第一步就是排水握手：

```c
:1751  ret = opairshare_bridge_pause_locked(id);
:1703      if (!wait_event_timeout(opairshare_bridge_wait,
:1704        !atomic64_read(&opairshare_bridge_pending), msecs_to_jiffies(3000))) {
:1705      pr_err("OPAIRSHAREBRIDGEDRAIN: vdev=%u pending=%lld timeout; retain resources\n", ...);
:1707      return -ETIMEDOUT;
```

`pending` 只会被 `opairshare_bridge_done()` 减，而它只在原生完成回调里跑。固件死了 ⇒
必然 3 秒超时 ⇒ `stop` 整个失败 ⇒ 后面三步（`opairshare_rx_filter_clear_locked`、
`opairshare_target_stop`、`opairshare_flow_forget_locked`）**一步都到不了**。
这正是 §3.6.10 那个活体复现里 `rmmod` 卡住不退的上游之一。

**修法：给拆除补一条不做 WMI 往返、不做状态检查的路径。**

1. `wma_opairshare_flow_control.h:122` 新增 `opairshare_flow_force_forget_locked()`：
   `dev_put` + 置 NULL + `opairshare_flow_puts++`，只留 `if (!opairshare_flow_dev) return;`
   一道闸（幂等）。它和 `forget` 的唯一差别是**故意不调 `opairshare_flow_control_locked()`**
   ——netdev 正在被销毁，`paused` 描述的那套队列状态马上就要不存在了。
2. `wma_opairshare_pas_mgmt.h:1814` 新增 `opairshare_pas_mgmt_abandon(uint8_t id)`：
   `accepting = false` + `force_forget`，然后打一行
   `OPAIRSHAREABANDON: vdev=%u pending=%lld still_held=%u puts=%llu`。
   **故意不 `destroy_workqueue`**——在 RTNL 下 flush 一个还在往「重启中的 MAC」提交的
   worker，停顿是**无界**的，而不只是慢；每次 SSR 之后都跟一次模块重载，workqueue 跟着回收。
3. 两个调用点，都在 `wlan_hdd_wondertap.c`：`force_cleanup` 分支（`:1390`，主修）+
   `__wlan_hdd_destroy_wondertap_intf()`（`:478`，兜底，覆盖 create-failure 那条
   「start 之后、失败之前已经收过帧」的路径；对已 forget/abandon 过的情形是空操作）。

判据（设备上可复核，**前三条是主判据**，第四条只在干净机器上有效）：

```
# 0) ★最锋利的一条：那个 Usage count 本身就是「还欠几份 dev_hold」。
#    修前实测恒为 2（2026-11-22 11:45，`eac00ca0…` 那版驱动）：
#      unregister_netdevice: waiting for wondertap0 to become free. Usage count = 2
#    2 = aux 路由一份 + 流控一份，两份 dev_put 都没执行。修好后这个数字要么不出现
#    （netdev 直接释放、根本进不了这个等待），要么出现也必须是 0→立刻退出循环。
#    它比 grep 计数准得多：条数取决于等了多久，count 取决于欠了几份。
dmesg | grep "waiting for wondertap0" | tail -1
#   期望：无输出（首选），或 Usage count = 0。

# 1) 拆除能走完 —— 最硬的一条。修前 stop 会挂满 180 s 不返回（§3.6.10 的活体复现），
#    修后整个卸载顺序能在几十秒内退干净。
timeout 180 sh tools/awdl-stack.sh stop; echo "rc=$?"
lsmod | grep -c qca_cld3_peach_v2
#   期望 rc=0、计数为 0（不再残留 MODULE_STATE_GOING 的 -1）

# 2) 引用确实归还了
cat /sys/module/qca_cld3_peach_v2/parameters/opairshare_awdl_flow_state
#   期望 held=0 且 puts>=1（修前恒为 holds=1 puts=0 held=1）

# 3) 走的是 abandon 这条新路径
dmesg | grep OPAIRSHAREABANDON
#   期望 OPAIRSHAREABANDON: vdev=0 pending=… still_held=0 puts=1

# 4) 只在「从没卡过」的机器上看
dmesg | grep -c "waiting for wondertap0 to become free"
#   期望 0。⚠️ 已经在旧驱动上卡过一次的机器永远不可能是 0——那个半死 netdev 会一直
#   每 10 秒刷一行，与修没修无关。这种情况下改用「刷屏条数的增速有没有多出新的」判断。
```

**装新载荷之前的设备基线（2026-11-22 11:2x，跑的是只修了 aux 的那版 `eac00ca0…`）。**
这台机器正好停在泄漏的活体状态，可以直接当「修前」对照：

```
$ cat .../opairshare_awdl_flow_state
enabled=1 paused=0 held=1 pending=0 busy=0 stops=0 wakes=0 releases=0 errors=0 holds=1 puts=0 …
                                    ↑ 绑定过一次            ↑ 一次都没归还
$ dmesg | grep -c "waiting for wondertap0"
162                                  ← 且仍在每 10 秒 +1（5849 / 5859 / 5869）
$ ls /sys/class/net/ | grep -E 'wonder|opair'
opairsharerx0                        ← 残留 NO-CARRIER；wondertap0 已从 sysfs 摘除
```

**★ 在已卡死的机器上不要跑 `stop`——它会把模块推进 `GOING`，此后无法再换载荷。**
2026-11-22 11:45 实测（当时跑的还是只修 aux 的旧驱动）：

```
rmmod: failed to unload qca_cld3_peach_v2: Device or resource busy
restore reported rc=11
WIFI_STATE_TIMEOUT want=1 now=0
Wi-Fi did not come back
--- lsmod ---
qca_cld3_peach_v2   12673024  -1          ← -1 = MODULE_STATE_GOING，不是 0
--- flow_state ---
enabled=1 … held=1 … holds=1 puts=0       ← 泄漏原封不动
```

`stop` 自己返回 rc=0（脚本不把 rmmod 的失败当致命），但**代价是 Wi-Fi 直接没了**，
而且同名模块再也 `insmod` 不进去——`delete_module` 已经把状态置成 GOING，卡在
`netdev_wait_allrefs` 里等那个 usage count 归零，而旧驱动没有任何路径能让它归零。

结论：**修好的驱动只有在「干净启动」的机器上才验得出来。** 顺序必须是
「重启 → 手工提权拿回完整 root → 推新载荷 → `AWDL_PHY=2 AWDL_MCS=7 boot`」。
在已经泄漏过的会话里试图热换载荷，只会得到一台 Wi-Fi 关掉、模块 GOING 的手机。


`holds=1 puts=0` 是最干净的一对数字：**bind 走了一次，forget 一次都没成功**。
而 `lsmod` 里 qca 的 refcount 是 **0** 而不是 `-1`，说明这次没人尝试卸载——卡住的只是
`netdev_run_todo()` 里的等待，不是 `delete_module()`。这两者要分开看，别混成同一个故障。

**同轮把固件崩溃的时间线夹到 0.9 秒。** `Received firmware hang event` 全文只出现
1 次（`dmesg.txt:19255`），按毫秒把上下文排开：

```
4210.218723  vdev_id 1 freq 5745 chanmode 24 (11BE_EHT80) center_chan 5745 center_freq2 0
             Tx SS 1, Rx SS 1, ldpc_rx 0
4210.277705  OPAIRSHAREAUXREADY: rx=opairsharerx0 vdev=1 target=wondertap0 freq=5745
4210.277775  OPAIRSHAREBRIDGEDRAIN: vdev=0 pending=0
4210.284843  [wonder][wondertap] Vendor init successful. State set to UP.
4210.307383  wonder0: Created IBSS using preconfigured BSSID 00:25:00:ff:94:73
4210.312014  wma_process_cli_set_cmd: Vdev id is not valid          ← vdev_id 255
4210.312023  wma_process_set_ie_info: vdev_id: 255 is not active     ← 连刷 8 次
4210.670179  init: starting service 'vendor.wifi_hal_legacy'...
4210.829548  OPAIRSHAREBRIDGESEND: vdev=0 freq=5745 fc=00d0 len=291
4210.835869  OPAIRSHAREBRIDGECOMP: status=0 len=305
4210.901582  OPAIRSHAREBRIDGESEND: vdev=0 freq=5745 fc=0088 len=90   ← 最后一条成功
4211.053444  qcom_mhi_qrtr_status_cb: Unblock pending tx             ← MHI 先有动静
4211.053521  wlan_hdd_pld_uevent: Received firmware hang event
4211.054238  mhi: host pm_state:SYS ERROR Detect dev_state:SYS ERROR ee:RAMDUMP DOWNLOAD MODE
4211.365504  mhi_process_sfr: Exception recieved tid=16 inst=1d0be1c cause=7003
4211.390580  [wonder][ssr] Physical device wondertap0 is down. Triggering recovery.
4221.415788  wlan_hdd_wondertap_unregister_ops: wondertap vdev teardown failed:15
```

两条新线索，都指向「起 vdev 时配置不自洽」：

- **辅助 vdev 1（`opairsharerx0`）是 1SS，却挂在 `chanmode 24 = 11BE_EHT80` 上**
  （`Tx SS 1, Rx SS 1`），而主 vdev 0 是 `tx_ss=2 rx_ss=2`。EHT80 的 monitor vdev
  用 1SS 能不能被固件接受，**历史上从没验过**。
- **紧接其后 8 条 `vdev_id 255 is not active`**（255 = 无效 ID），来自
  `wma_process_set_ie_info` 与 `wma_process_cli_set_cmd`。两簇都在 IBSS 起来后 5 ms 内，
  距崩溃 0.74 秒。**已追到根，结论是它不是崩溃原因**，见下。

**`vdev 255` 整簇的因果链（2026-11-22 追完，别再当崩溃嫌疑）。**

三层报错是同一次调用的三个回执，入口只有一个：

```
hdd_set_vdev_phy_mode(adapter, vendor_phy_mode)      <- wlan_hdd_cfg80211.c:9425
  link_info = adapter->deflink                       <- 它的 link_info->vdev_id 就是 255
  ├─ sme_cli_set_command(255, wifi_standard_version) -> wma_process_cli_set_cmd
  │                                                     "Vdev id is not valid"    (×2)
  └─ ucfg_mlme_set_vdev_wifi_std(psoc, 255, std)     -> "vdev 255: vdev not found"
        └─ PE 继续按 255 填 per-vdev 速率集          -> lim_populate_mcs_set_{ht,vht}_per_vdev
                                                        "Got NULL vdev obj, returning"
```

`wmi_vdev_param_wifi_standard_version` 那条在 `wma_process_cli_set_cmd` 里撞上无效
vdev 就 `return` 了，`wma_process_set_ie_info` 也在 `wma_features.c:4989` 直接返回——
**四层全部在 host 侧拒绝，一条都没下发固件**。所以它不可能让固件 assert。

它真正说明的事只有一件：**有一个 adapter 的 `deflink` 上没有有效 vdev，却仍被
`hdd_set_vdev_phy_mode()` 扫到了**。`255` = `WLAN_INVALID_VDEV_ID`。这是一条独立的小
瑕疵（`hdd_restore_info_for_ssr()` 那条路要 SSR 之后才走，时间对不上、已排除），
留作待查，**不要为它改速率档**。


`wondertap vdev teardown failed:15` 的 **15 是 `QDF_STATUS_E_TIMEOUT`，不是 errno**——已确认，
先前「返回值被折过、找不到赋值点」的说法作废。`qdf_status.h:89` 的枚举按序数是
`SUCCESS=0 … E_EMPTY=13, E_EXISTS=14, E_TIMEOUT=15`，所以 15 与 `-ETIMEDOUT` 是**同一个
语义的两种枚举**（errno 的 110 是 Linux 那边，QDF 这边自成一族）。时间也对得上：
`hang event` 在 4211.053，这条在 4221.416，差 **10.36 s**，即
`WLAN_WONDERTAP_VDEV_OP_TIMEOUT_MS`。成因是固件已经死了，`sme_delete_pe_session()`
永远不会回 `wondertap_vdev_event`，于是白等满一个超时周期。

可优化点（暂不动）：SSR 状态下这条等待没有意义，`cds_is_driver_recovering()` 时应当直接跳过，
把拆除从 10 s 压到 0。等链路稳定后再做。

**A/B 开关（零重编）。** `AWDL_PHY` / `AWDL_MCS` 现在可被环境变量接管：

```sh
AWDL_PHY=2 AWDL_MCS=7 tools/awdl-stack.sh boot
# 保留 EHT80 join + A-MSDU 11454，只把数据帧速率类退回 HT
```

于是「VHT preamble 崩固件」与「80 MHz join 崩固件」可以分开验，不必重编驱动。

> **【已更正】** 本节原先「VHT 档 0.855 秒必崩、HT 档稳定」的对照**不成立**，见 §3.6.13：
> 同一次开机三次建链的速率档逐字段相同（`preamble=1` HT / `mcs=7` / `phy=26`），
> 两次挂、一次活，挂死固定落在 `Created IBSS` 后 0.84–0.86 s。
> `AWDL_PHY` / `AWDL_MCS` 的 A/B **暂缓**，别在未明成因上再叠一个变量。
（`awdl-stack.sh:164-202` 里 `AWDL_PHY_DEF` / `AWDL_MCS_DEF` 的原注释记着
**为什么不能干脆删掉宽档那一行**。）

**首启顺序（别反）。** `$STATE/wide` 存在时默认是 `AWDL_PHY_DEF=3` / `AWDL_MCS_DEF=9`，
而这两项恰好是**唯一没被验证过**的一步——2026-11-22 10:53 就是在它上面崩的固件。
用户给的决定性参照（「我们之前都正常用了 802.11be + EHT80 + 2SS + MCS7 + SGI +
capacity256 + phy2」）对应的正是 `AWDL_PHY=2` / `AWDL_MCS=7`。所以：

```sh
# 头一次 boot 新驱动：先用已知可用档，只验泄漏修复
AWDL_PHY=2 AWDL_MCS=7 tools/awdl-stack.sh boot
# 链路活下来、stop 也干净之后，再单独把 rate class 抬回 VHT：
AWDL_PHY=3 AWDL_MCS=9 tools/awdl-stack.sh boot
```

反着做的话，链路 0.9 秒就崩，`stop` 那条判据虽然照样能验（崩了更容易走到
abandon 路径），但「两机互见」这条主线会被固件崩溃盖住，分不清是泄漏没修好
还是速率档不对。

#### 3.6.12【新坑，必须先解】手工 push 的载荷会被应用自己的 assets 盖回去（2026-11-22 12:05 实测）

这是本轮最浪费时间的一条，写下来省下一次重启。

**现象**：11:38 把新驱动推到 `/data/local/tmp/opairdrop/qca_cld3_peach_v2.ko`
（20 038 648 B / `469e70b5…`），11:48 再查变成 **20 035 608 B / `8fe0d6d7…`**，
`awdl-stack.sh` 也从 107 253 B 变回 **101 496 B**——这个 101 496 的版本**本地任何目录里都
不存在**，只可能来自更早某次 `assembleDebug` 打进 APK 的副本。

**为什么**：`android/app/src/main/java/com/oshare/airdrop/DriverSetup.java` 的
`ensurePayload()`（`:324` 起）不是「文件存在就跳过」，而是**按 `SHA256SUMS` 清单逐个比对
哈希，不匹配就重写**（`:347` 注释原文：「Writes every asset whose local copy does not already
match the manifest」）。我们手工覆盖让哈希对不上清单，应用的结论是「这个文件坏了」，
于是下次进程启动就把它重解压一遍。清单本身也是构建期生成的
（`build.gradle:218-222` 遍历输出目录算 sha256），所以清单与字节永远自洽，
**唯一能改的办法是改 APK 里的字节，不是改设备上的字节**。

**因此投放路径只有一条**：

```bash
# 1) 把 strip 好的产物压进仓库载荷（-n 不写时间戳，哈希可复现）
gzip -9 -n -c /mnt/opairshare-build/baseline-wlan/stripped.ko \
    > kernel/qca_cld3_peach_v2.ko.gz
# 2) 同步 README 的字节数与 sha256 两处（脚本 make-kernel-assets.sh 打印的就是这张表）
# 3) 重编 APK —— stageKernelAssets 会解压 gz 并重算 SHA256SUMS
JAVA_HOME=... bash android/build.sh assembleDebug
# 4) 装上去。装完应用第一次启动自己就把新载荷铺到 /data/local/tmp/opairdrop
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
# 5) 只有到这一步才验：设备上的 ko 必须与 stripped.ko 逐字节相同
adb shell "su -c 'md5sum /data/local/tmp/opairdrop/qca_cld3_peach_v2.ko'"
#   期望 469e70b5887035390474ad1b94508749
```

`sha256sum -c` 是脚本自己会跑的一步，它绿了才说明设备上那份真的换掉了——
`DriverSetup` 的设计意图正是「宁可重写也不让旧载荷冒充新载荷」，这一点它做对了，
错的是我绕过它去改设备。

**当前这一版已落进 `kernel/qca_cld3_peach_v2.ko.gz`**
（5 280 156 B / 解压 20 038 648 B / 原始字节 sha256 `86b5f4ec…`）。
旧字节留在 `qca_cld3_peach_v2.ko.gz.prev` 与 `qca_cld3_peach_v2.ko.gz.bak`，回滚用。

**顺带核对过一处「看起来像 bug、其实不是」**：`wma_opairshare_pas_mgmt.h:1392`

```c
mgmt.tx_param.nss_mask = BIT(READ_ONCE(passthru_awdl_nss) == 2 ? 1 : 0);
```

`BIT(1) = 2`，对 `nss_mask` 这种「允许的 NSS 索引位图」正是「2SS」，**不是 1SS**。
同一批 TX 参数里真正没被验证过的是 `preamble = BIT(3)`（VHT）、`mcs = BIT(9)`、
`bw = BIT(4)`（80 MHz）三者的组合——那才是下一步 A/B 的对象，不是 `nss_mask`。

#### 3.6.13【更正 §3.6.11】「VHT 档 0.9 秒必崩」不成立——三次同配置，两次挂一次活（2026-11-22 12:20 实测）

§3.6.11 里写的「VHT 档（`phy=3`）0.855 秒必崩、HT 档（`phy=2 mcs=7`）稳定」是本轮一开始的
印象，**现在看是错的，必须撤回**。同一次开机内一共建链三次，把 dmesg 拉齐对齐看：

```
[  121.756899] wonder0: Created IBSS using preconfigured BSSID 00:25:00:ff:94:73
[  122.611557] Received firmware hang event                                    ← +0.855 s
[  122.913806] mhi_110e_00.01.00: Exception recieved tid=16 inst=1d0be1c cause=7003
[  341.423521] wonder0: Created IBSS using preconfigured BSSID 00:25:00:ff:94:73
                                                                               ← 活了 440 s
[  782.143888] wonder0: Created IBSS using preconfigured BSSID 00:25:00:ff:94:73
[  782.986770] Received firmware hang event                                    ← +0.843 s
[  783.298816] mhi_110e_00.01.00: Exception recieved tid=16 inst=1d0be1c cause=7003
```

三次的速率档**逐字段相同**，都是 HT：

```
[wonder][ven_cmd] Set fixed TX rate: preamble=1, bw=0, gi=1, nss=1, mcs=7     (HT = 1)
target_if_vdev_mgr_start_send: OPAIRSHARE final start: vdev=0 freq=5745 phy=26 tx_ss=2 rx_ss=2
```

所以 `121.76` 那次并不是走了 VHT——它和我后来手动跑的 `AWDL_PHY=2 AWDL_MCS=7 boot`
用的是一个档。**「挂」与「不挂」的差别不在速率档上**，现在能确定的只有两件事：

1. 两次挂死都精确落在 `Created IBSS` 之后 **0.84–0.86 s**，异常字也完全一样
   （`tid=16 inst=0x1d0be1c`）。这是个**固定延迟**，更像建链后 1 秒内某个动作触发，
   不是热噪声。
2. `341.42` 那一次同配置活了 440 秒（其间 `aux_seen` 从 0 涨到 1279、
   `native_raw_seen` 20840、对端 `anchored=472`），说明这套配置本身能跑。

下一步该查的是「建链后 0.85 秒里固件收到了什么」，不是继续换 preamble。
（`AWDL_PHY` / `AWDL_MCS` 的 A/B 因此**暂缓**，避免在同一批未知上叠两个变量。）

**同一次开机里重复跑 `boot` 是破坏性的，这一条比崩溃更该先记住。**

`tools/awdl-stack.sh:1295-1303` 的 `same_boot` 守卫并不是「什么都不做」：

```sh
if same_boot "$STATE/stack-boot"; then
    say 'stack already swapped this boot'
    freezer_off || true
    link_up || true          # <-- 仍然会重建 tap
    cmd_status
    return 0
fi
```

`link_up` 撞上「tap 已经 up」时靠**删掉再加回来**绕 `-EBUSY`（脚本 `:1663` 的注释自己写了
这条路），而 `awdld` 的 `AF_PACKET` 套接字钉在旧 netdev 上，netdev 一销毁就报
`awdld: reading the radio failed: Network is down (os error 100)` 并 exit 1。
12:10 就是这样死的：

```
781.795  dp_rx_mon_check_n_drop_mpdu: 100 callbacks suppressed
781.806  [wonder][ven_cmd] SET_FREQUENCY: freq=5745 MHz, bandwidth=0
781.806  [wonder][ven_cmd] Set fixed TX rate: preamble=1, bw=0, gi=1, nss=1, mcs=7
781.812  [wonder][mac80211] wonder_sta_remove() / Removed virtual interface / HW stopped
781.813  OPAIRSHAREBRIDGEDRAIN: vdev=0 pending=0
781.813  OPAIRSHARERXFILTER: clear-submit vdev=0 freq=5745 status=0
781.821  OPAIRSHARETARGET: unmapped vdev=0 mac=96:5e:d8:84:36:92 peer=7
781.828  OPAIRSHARETARGET: unmapped vdev=0 mac=ba:b4:d4:9e:a5:2f peer=8
781.866  OPAIRSHAREAUXSTOP: seen=3134 forwarded=3134 dropped=0
781.918  OPAIRSHARERXSESSION: vdev=0 real_delete=1 poison=0
781.921  OPAIRSHARERXSESSION: retire vdev=0 rc=0 generation=1 used=0
781.996  OPAIRSHARENDI: create DEFAULT self peer vdev=0        ← 立刻重建
782.143  wonder0: Created IBSS ...
782.986  Received firmware hang event                           ← 0.843 s 后挂
```

`awdld.log` 最后一行就是那句 `Network is down (os error 100)`。
`revive_daemon()` 有 5 次预算，但它在 `same_boot` 与 `[ -e "$PCI/wondertap0" ]` 两个条件里
还要过一关，此时链路已随固件一起没了，revive 救不回来。
**判据：链路要动，先 `stop` 再 `boot`，不要在同一个 boot 里第二次 `boot`。**

**顺带一条正面数据：`vdev=255` 的重试路径没有泄漏。**

```
[  232.572873] OPAIRSHAREABANDON: vdev=255 pending=0 still_held=0 puts=1
[  235.704089] OPAIRSHAREABANDON: vdev=255 pending=0 still_held=0 puts=1
[  238.829212] OPAIRSHAREABANDON: vdev=255 pending=0 still_held=0 puts=1
[  241.970878] OPAIRSHAREABANDON: vdev=255 pending=0 still_held=0 puts=1
[  245.115657] OPAIRSHAREABANDON: vdev=255 pending=0 still_held=0 puts=1
[  248.254077] OPAIRSHAREABANDON: vdev=255 pending=0 still_held=0 puts=1
```

六次重试间隔 **3.14 s**，`puts` 恒为 **1** 而不是 6——`opairshare_flow_force_forget_locked()`
在第二次进来时看到的是空指针，不会重复 put。§3.6.11 修的那三处泄漏在**重试**这种最容易
翻倍计数的路径上也守住了。

修后判据（本轮实测两次开机都成立）：

```
[  122.931145] OPAIRSHAREABANDON: vdev=0 pending=12 still_held=0 puts=1
[  133.097262] OPAIRSHAREABANDON: vdev=255 pending=0 still_held=0 puts=1
dmesg | grep -c "waiting for wondertap0"   →  0        （旧驱动 239）
stop → STOCK_DRIVERS_RESTORED / lsmod qca=12361728 refcount 0 / wifi=1 hal=running
```

**吞吐到底怎么量（这一列以前一直取错）。**

`/proc/net/dev` 的 `awdltun0` 行带冒号，`iface:` 占掉第 1 列，所以

```sh
# 错：-f3,11 拿到的是 rx_packets 与 tx_bytes，不是字节
grep awdltun0 /proc/net/dev | tr -s ' ' | cut -d' ' -f3,11
# 对：先去掉冒号，再取 f2 与 f10
grep awdltun0 /proc/net/dev | tr -s ' ' | tr -d ':' | cut -d' ' -f2,10
```

但 `tun` 的字节只是**用户态看到的结果**，它把 AWDL 开销、重传、聚合全抹掉了，
不能当空口速率。真正的判据在内核侧：

```sh
P=/sys/module/qca_cld3_peach_v2/parameters
cat $P/opairshare_agg_stats        # aggregates=/msdus=/bytes=  ← A-MSDU 真正塞进空口的字节
cat $P/opairshare_aux_forwarded    # 从 monitor 收到的帧数
cat $P/opairshare_ba_stats         # window / window_eff / tx_ok
```

设备上已放好 `/data/local/tmp/opairdrop/tp2.sh`，2 秒一处采样、只在计数变化时打印，
同时打 `air`（聚合字节）、`tun_rx` / `tun_tx`、`fwd` 三项，空闲 3600 s 自动退出：

```
12:17:14 2066ms air+35732 agg+80 msdu+292 => 17295 B/s air | tun_rx+153410 tun_tx+10052 | fwd+30
```

`air` 与 `tun_rx` 差三到四倍是正常的：`agg_stats.bytes` 只记走 A-MSDU 聚合的那部分，
非聚合的单帧从 `dp_rx.c` 直接投递，不进这个计数。**报吞吐时必须写清楚报的是哪一个。**

---

#### 3.6.14 接收侧三个可疑点：`admit` 判死 A-MSDU 整链、逐帧 `pr_err`、`window_eff=64`（2026-11-22 下午）

这一节只写**已测到的数**和**还没排除的可能**，不写结论。因为下面第一点虽然能把
「接收包长只有发送侧 1/3」解释干净，但它的 17.3% 丢帧率**解释不了**从 4 MB/s
塌到 15–60 KB/s 这种量级的掉速——别把它当成已闭合的根因。

**先把方向和"链路是否假死"分清**

```
STAT  tx=73560p/99720159B  → 1356 B/包    正常（用户侧 3–4 MB/s）
      rx=10655p/5363207B   →  503 B/包    只有发送侧的 1/3
```

带宽排除了：`iw dev wonder0 info` → `channel 149 (5745 MHz), width: 80 MHz, center1: 5775 MHz`，
`opairshare_channel_state: cookie=1 busy=0 fault=0 error=0 accepting=1 vdev=0 freq=5745`。

**"接收冻结"是误判，坑在这里**：只看 `rx` 会在对端不发数据时得出"链路又断了"，
而同一行里 `other` 还在涨（7868→12240）说明对端只发动作帧、链路活着。
判活的指标是 `other` 还在增长；判吞吐必须有一次真实传输：

```sh
# 两次采样完全一致 ⇒ 当场没有流量，任何"速率"都是无源之水
cat /proc/net/dev | grep wonder0
#   wonder0: 5363207 10655 ...   ← 两次一样
```

**可疑点 1：`opairshare_pas_raw_admit()` 要求 `start && end` 同时为真**

`dp_rx.c:1247`，由 `dp_be_rx.c:783` 在 REO 主循环里、交付之前调用：

```c
if (qdf_nbuf_is_frag(nbuf) || qdf_nbuf_is_rx_chfrag_cont(nbuf) ||
    qdf_nbuf_is_nonlinear(nbuf) ||
    !qdf_nbuf_is_rx_chfrag_start(nbuf) || !qdf_nbuf_is_rx_chfrag_end(nbuf)) {
        atomic64_inc(&opairshare_native_raw_incomplete);
        return false;
}
```

而「一条 nbuf 链 = 一个已经被 REO 拆开的 A-MSDU」这件事，源码里有三处写死：

1. `dp_rx.c:2996`：`is_not_amsdu = qdf_nbuf_is_rx_chfrag_start(nbuf) & qdf_nbuf_is_rx_chfrag_end(nbuf);`
   ——两标志**同时为真才是"非 A-MSDU"**。
2. `dp_rx_sg_create()`（`dp_rx.c:2084`）：`start && end` → 单 nbuf 直接返回；否则注释原文
   "multiple msdus (A-MSDU) spread across multiple nbufs"，建 fraglist。
3. `wma_opairshare_awdl_ba.h:30` 注释原文：REO 在交给主机之前就把 A-MSDU 拆成
   "one MSDU per buffer" 的链。

链上只有首环置 `start`、尾环置 `end`，中间两标志皆假 ⇒ 按上面那条判据，**整条聚合链会逐环被判 incomplete**。

实测分布（`seen` 恰好被两个分支分完，没有第三类）：

```
seen=16293  admitted=13479  incomplete=2814  wrong_vdev=0  bad_headroom=0
            13479 + 2814 = 16293 ✓
```

**别再怀疑 `admit` 是空转。** `dp_rx_is_raw_frame_dropped()` 在源码里只有
`#ifdef DP_RX_DROP_RAW_FRM` 一处（`dp_rx.c:3293`），overlay 里没有 `#define`，说明宏来自
构建命令行；而 `delivered=13479` 与 `admitted=13479` **完全相等**，证明 `incomplete` 那些帧
既没走正常路径也没被投递，是真的丢了。`dp_be_rx.c:783` 的短路语义是
`!admit && dp_rx_is_raw_frame_dropped(nbuf)`，后者会先 `dp_rx_nbuf_free()` 再返真。

**可疑点 2：每投递一帧就一条 `pr_err`**

`dp_rx.c:1301`（`dp_rx_deliver_raw_passthru()` 入口）：

```c
pr_err_ratelimited("OPAIRSHARERX: passthru deliver vdev=%u len=%lu\n",
       vdev->vdev_id, (unsigned long)qdf_nbuf_len(nbuf));
```

`dmesg | grep -c "OPAIRSHARERX: passthru deliver"` → **970**。发送侧同类日志用的是
`pr_info_ratelimited`（`wma_opairshare_pas_mgmt.h:1399` / `:874`）。`pr_err` 会绕开常规
日志级别过滤，ratelimit 的 burst/interval 在高包速率下本身就可能变成限速点。

**可疑点 3：BA 只协商到 1/4 窗口**

`opairshare_ba_stats: enabled=1 window=256 window_eff=64 amsdu=1 requests=4 prepared=4 tx_ok=4`。
`window_eff=64` 与参考项目里 "win=256 比 win=64 快 2.8 倍"（Mac 向 64.08 vs 23.14 MB/s）
的现象对得上，值得单独作为一条线索。

**`ba_amsdu` 的运行时可写性与它的边界（已实测）**

```
echo 0 > $P/passthru_awdl_ba_amsdu   →  write_rc=0  now=0
```

但它只在 **ADDBA Response 组装时**被读（`wma_opairshare_awdl_ba.h:204` 一带，
`param = (window << 6) | (e->tid << 2) | BIT(1); if (READ_ONCE(passthru_awdl_ba_amsdu)) param |= BIT(0);`），
**已协商的会话不受影响**。所以验证必须 `run-stop.sh` → `run-boot.sh` 重建 BA，
只写参数不重启栈等于没做。

**下一步判据（按顺序，先测再说，别跳）**

1. `ba_amsdu=0` + 重启栈 + 拉一笔真实传输。若接收回到 4 MB/s 档，则"链被判死"是主因；
   若毫无变化，主因在别处（那就往 `window_eff=64` 和 deferral 上查）。
2. 三条判据同时成立才算过，缺一条都不算：
   - `opairshare_native_raw_incomplete` 停止增长；
   - `dmesg | grep -o "passthru deliver vdev=[0-9]* len=[0-9]*" | sed "s/.*len=//" | sort -n | tail -1`
     仍 ≤ 1532（说明对端**确实**退回了单 MSDU，不是我们猜它退了）；
   - `tp2.sh` 的 `tun_rx` 速率回到 MB/s 量级。
3. 若 1 不成立，再单独验 `window_eff`：这条与 A-MSDU 无关，改的是 BA 窗口协商，
   不要和 1 混在一次重启里做，否则分不清谁起作用。

**一个必须承认的空白**：本轮所有接收侧数字都来自"没有真实传输"的时间窗
（`/proc/net/dev` 两次采样完全一致）。上面第 1 步跑完之前，§3.6.14 只算定位到可疑点，
不算定论。

---

## 4. 链路层硬事实（真机实测，不要重新发明）

### 4.1 三定律

1. **接口名必须是 `wonder0`**：`wonder_start()` 里 `dev_get_by_name(virtual_name)` 是唯一能拿到
   虚拟网卡的路径（mac80211 先 `drv_start` 后 `drv_add_interface`，此刻 `wonder->vdev` 还是 NULL）。
2. **接口类型必须 `ADHOC`（IBSS），不能 `NAN`**：`wonder_rx_handler()` 只把 ADHOC 的帧交给
   mac80211，`NAN`/`STATION` 走 `RX_HANDLER_PASS`，帧留在物理网卡上。
3. **IBSS 必须 `fixed-freq`**：不加则每 30 秒重扫一次试图合并同 SSID 的 IBSS，每次把射频带走。

另两条：`accept_dad=0`；`wondertap0` 由 vendor init 创建，MAC 每会话随机。

### 4.2 内核侧装载序列

`wonder.ko` **不在** `/vendor/lib/modules/`，由模块 payload 提供（四个 `.ko` 合计 **37.4 MB**：`mac80211`
17.6、`qca_cld3_peach_v2` 20.0、`opairshare_wonder_provider` 271 KB、`wonder` 78 KB——这是「完全摆脱 KSU
模块」的真实代价，先认下来再动手）。驱动交换必须做：

```sh
am force-stop com.coloros.oshare   # 互传进程持有 master，不停 HAL 会拿不到 1 MB 连续内存
svc wifi disable                   # 必须等 Wi-Fi 真的关掉，再动 qca
rmmod qca_cld3_peach_v2
insmod opairshare_wonder_provider.ko
insmod qca_cld3_peach_v2.ko        # 30+ 个 passthru_* 参数（§3.1），一个都不能少
rmmod mac80211 ; insmod mac80211.ko
insmod wonder.ko physical_name=wondertap0 virtual_name=wonder0
# 之后恢复 HAL 与 Wi-Fi，再 iw 建 IBSS（awdl-stack.sh 的 link_up），最后才起接收端
```

**这一步只能 root 做，除了 root 没有第二条路。** 两条风险动手前必须明确：

1. `rmmod` 掉的是 Wi-Fi 驱动，失败会让人**没有 Wi-Fi**——必须像旧模块脚本那样先关 Wi-Fi、留退路
   （boot 内重试 + 失败回滚检测）。
2. 从应用私有目录 `insmod` 会被 **SELinux** 拦，必须先复制到 `/data/local/tmp` 再加载——这也是驱动落点
   定在 `/data/local/tmp/opairdrop` 的原因。

### 4.3 三个会静默吃掉速率的坑

1. **`HT20` 不在 join 参数里** → vdev 是 NOHT → 所有 HT 速率被忽略 → 空口停在 24 Mb/s。
   现象是「参数都对、`iw` 也显示 `nss:2 mcs:7`、就是只有 2 MB/s」。
2. **非 HT PPDU 不能带 >2346 B 的 MPDU**（`wma_opairshare_pas_mgmt.h:477`）→ `p5` 恒为 0，
   第 5 个聚合槽白设。HT20 之后 `p5` 涨到 5878 包。
3. **`collect_us=0`** → 聚合定时器等于不存在，只能等环自己填满，平均 3.6 msdu/聚合。

### 4.4 计数器的可信度（采样必读）

- `opairshare_agg_stats` **会冻结**：某些轮次停在 `aggregates=22618` 而 `tx_bytes` 涨 27 MB，
  疑似只统计当前活跃 peer / 成功路径。**交叉验证只能靠 `awdltun0/tx_bytes`。**
- `logcat` 环形缓冲默认 256 KB ≈ 10 秒，**测速前先 `logcat -G 16M` + `-c`**，否则事后什么都没有。
- 每秒 fork 一次 `ss -tin` 本身就压速率（每 0.6 s 一次能压掉约 20%）。采样器只读 `/proc`，
  `cwnd/rtt` 每 4 拍才取一次。

### 4.5 速率的唯一读法：突发内帧间隔

三条都踩过，写下来省一轮：

- **数据帧的 radiotap `rate` 字段是 0（没填）。** 那块虚拟驱动在接收方向不填速率，`iw dev wonder0
  station dump` 又是空的；抓包里那 971 个 `12.0 Mb/s` 全是 **Action（presence）帧**，不是数据帧。
  所以**速率不能从抓包直接读**，只能从帧长与帧间隔反推。
- **`Duration/ID` 是 Apple 设的 NAV，不是空口时间。** 它恒为 84 / 60 µs，装不下一个 1504 B 的帧
  （1504 B 在 MCS1 上要 ~930 µs，是它的 11 倍）。按它算占空会得到 0.6% / 1.1%，而 1.1% 与已知的
  857 kB/s 互斥——不存在 1% 占空跑出 6.9 Mb/s 的链路。任何「按 12 Mb/s 折算占空」的做法都只是把字节
  换成时间，不是空口时间。
- **正确读法是「突发内帧间隔」**：它直接就是「两帧之间过了多久」，在无 ACK 的链路上几乎等于前一帧的
  发送时长 + 极小的 DIFS/退避。同一份抓包用它算出的等效线速率与吞吐是自洽的（§3.4 末尾那张表就是
  这么来的）。

> 一条自相矛盾的旧记录在此裁定：**5745 是驱动钉死的**（`wma_opairshare_pas_mgmt.h:580` 的
> `-EOPNOTSUPP`，且三条换频实测全失败）；「5745 是脚本选的」那条只有静态 `grep`，且它引的那句 `if` 在
> **另一条路径**（STA DBS 准备）上、被 `if (!passthru_sta_force_dbs) return 0;` 挡着。**注意**：裁定的是
> 「5745 是不是驱动钉死的」，不是「源码在不在」——那行硬编码所在的源码现在不在本仓库（`legacy/` 已删），
> 所以它作为**事实结论**有效、作为**可查证位置**已失效。

---

## 5. 协议事实（AirDrop）

### 5.1 全链路顺序

```
① mDNS 发现    _airdrop._tcp.local 的 PTR/SRV/TXT
② TCP 连接     peer:port（SRV 里的端口，通常 8770）
③ TLS 握手     对端自签证书；证书里的名字就是它自报的身份
④ POST /Discover   问「你收不收文件」
⑤ POST /Ask        报价：发什么、多少件
⑥ POST /Upload     发 cpio 归档（DVZip 块序列 + chunked）
⑦ 关闭         一次会话一条连接
```

### 5.2 三个端点（线上契约，不可动）

| 请求 | 请求体 | 应答体 |
| --- | --- | --- |
| `POST /Discover` | plist `{SenderComputerName, SenderModelName, SenderID}` | plist `{ReceiverComputerName, ReceiverMediaCapabilities, IsAirDropable}` |
| `POST /Ask` | plist 九键：三个 Sender 字段 + `BundleID`、`ConvertMediaFormats`、`Files[]`、可选 `SenderRecordData`/`FileIcon` | plist `{ReceiverComputerName, ReceiverModelName}` |
| `POST /Upload` | `Content-Type: application/x-cpio`（我们发 `x-dvzip`），chunked | `200` + `Content-Length: 0` + `Connection: close` |

`Files[]` 每项六个键，缺一不可：`FileName`、`FileType`、`FileBomPath`（**`./名字`，前缀必填**）、
`FileIsDirectory`、`FileSize`、`ShouldConvertMediaFormats`（`false`）。

### 5.3 几个反直觉的事实

1. **载荷不是 OPACK，是 Apple 二进制 plist。** OPACK 只出现在更老系统的
   `ReceiverMediaCapabilities` 里；现代实现把 JSON 字节塞进 plist 的 data 字段。
2. **`/Discover` 必须排在 `/Ask` 之前**：它是唯一「对面不收」会**先于**「对面弹确认框」到达的请求。
3. **`/Discover` 的应答不依赖请求体**，所以能在读完 body 前答完——这是「发送端卡住不拖住接收端」的原因。
4. **对端名字有两个来源，只有一个是给人看的**：mDNS 实例名是随机哈希、主机名是随机 UUID，
   真名在 `/Discover` 的 `SenderComputerName` 里。名字要等连上才有。
5. **一条连接只承载一个请求**。iPhone 在已答过请求的连接上读第二个请求时**什么都不答**
   （无状态行、无 RST、75 秒零字节）。所以 `/Discover` 一条，`/Ask`+`/Upload` 另一条。
6. **`/Upload` 的 `406` 必须在 `100 Continue` 之前发**，否则对端白传。
7. **`200` + 空 body 才是成功**，非空 body 是错误报告。

### 5.4 拒绝与超时

- **拒收**：`/Ask` 回 `401` 并断连（`Connection: close`，头与行为一致）。这个码不是从规范里读来的，
  是 2026-11-18 反向测出来的：我方发文件给 iPhone，机主在 iPhone 上点拒绝，抓到
  `/Ask answered 401 [Connection: keep-alive, Content-Length: 0]: (no body)`。改之前试过两个码，
  都在同一台 iPhone 上失败过：
  - `503`（照 OpenDrop 抄的）：`Service Unavailable` 带「暂时不可用、稍后再试」的语义，iPhone 把它
    读成「还在等服务恢复」，界面停在「等待中」，一直等到它自己的 ASK-2a 超时（15 秒以上）。
  - `403`（规范 ASK-2 的原文示例「non-200 status such as `403`」）：iPhone 把它读成「这次交换失败」，
    0.4 秒后**重连重发同一个 `/Ask`**（日志里出现第二个 `airdrop-2`），人被迫回答两次，第一个还在转圈。
  - `401` 才是「人说了不」。它读起来像鉴权失败，实际是 Apple 表达「这次不接」的码。
- **超时（没人应答）也回 `401`**：对发送端它与「人拒绝」是同一个事件（ASK-1：只有 `200` 是接受），
  区别只在**本机**的措辞（「已拒绝」/「邀请已过期」）和日志里的 `answered=` 字段。
- **实收（2026-11-18 21:29，装 401 版之后的第一轮）**：

  ```text
  21:29:25.183 offer from 不辛苦命苦 1 1645816 0 IMG_7873.jpg
  21:29:25.186 invitation airdrop-1 from 不辛苦命苦 files=1 links=0
  21:29:27.470 invitation airdrop-1 refused after 2283 ms asked=nobody answered=notification
  ```

  判据不是「iPhone 说了什么」（那是它屏幕上的事），是**它下一步做了什么**：403 那一版在这行之后
  0.4 秒就出现第二个握手（`airdrop-2`）并重发同一个 `/Ask`；401 这一版到 21:30:41 为止又来三次
  邀请，间隔 12～23 秒，与人手动重新点发送的节奏一致，没有一次机器自发的重连。人那边看到的是
  「已拒绝」，不是「等待中」——Q1 到此收口。
- **`401` 与我们自己的 `Connection` 头**：iPhone 拒绝时自己发的是 `keep-alive`，但随后什么也不再
  发。我方这一侧明说 `Connection: close`（`Response::with_keep_alive(false)`）——头与实际行为一致，
  比模仿对端更不容易骗到下一个读头的人。
- **面板上写「对方拒绝」，不写「对方不收文件」**：`/Ask` 上只有「非 200」这一个事实，人与设置给
  不出区别。标题写人做的那件事（拒绝）。**2026-11-19 03:05 起副行只剩「<谁> 拒绝了这次发送」**：
  原先还并列一句「若对方并没有看到询问，把 iPhone 的隔空投送改成「所有人（10 分钟）」并保持亮屏」，
  那是替「没弹窗」留的第二种解释，但 `401` 是 iPhone 在问过之后自己回的——两句并列会让一句已经
  确定的话读起来像猜测；要改设置、要亮屏的指导仍在发送页底部那张说明卡里，那里才是一份完整的清单。
  代价记在这里：若日后确认「接收全关时也回 `401`」，这句就说过头了，届时要么恢复那半句、要么让
  Rust 侧把两种 `401` 分开。同一轮把发送页那行状态字的颜色也改了——`对方拒绝` 不再用 `accent` 蓝，
  改用 `Scheme.red`；判据是 `RefusedException` 这个类型（`status(peer, line, alarm)` 的第三个参数），
  不比对文案，并且记进 `statusAlarms`，换主题重建之后仍是红的。
- 我方作为发送端时，`/Ask` 上**任何非 `200` 都算被拒**，不再只认某个具体数字——对方换个码不该
  让我方把「人拒绝了」显示成「发送失败」。
- 我们的接收端有两种答法：**先答后读**（默认，抗卡死）与**读完再答**（要问人时）。后者是
  `answers_ask_early() == false`，只在真的会弹确认框时才付这个代价。
- `/Upload` 的 `406` 必须在 `100 Continue` **之前**发；先说「请继续」再拒绝体，对端会白传。
- **拒绝与超时在发送端看起来都是失败，在本机是两句话**：拒绝是「已拒绝」，超时是「邀请已过期」。
  后者刻意不写成拒绝——没人看见这条邀请，不等于他拒绝了你。
- **中途取消（2026-11-19 02:44 实测）：没有状态码，是 TCP RST**。我方发 400 MB 随机文件到 iPhone，
  机主在 `/Ask` 上点了接受、传输走到 6%（25 MB / 400 MB，用时 18.4 秒）时在 iPhone 上点「中断接收」。
  我方在最后一次进度回调之后 **404 ms** 收到 `sun.net.ConnectionResetException: Connection reset`，
  没有状态行、没有头、没有体：

  ```text
  02:44:20.569  phase waiting (the person has been asked)
  02:44:23.208  phase sending 0% (0/419430400 bytes)
  02:44:41.223  live card: 发送到 不辛苦命苦 · 6% · 1 个文件 · 25 MB / 400 MB · 6%
  02:44:41.627  send failed: 7b45800a11bd — sun.net.ConnectionResetException: Connection reset
  ```

  所以 `/Ask` 的拒绝和 `/Upload` 的中断**不在同一个层次**：前者是应用层明确表态（`401` + 头 + 行为一致），
  后者是传输层直接闭嘴。**RST 能送到我方内核，说明那一刻链路是通的**——断的不是网络，是对方的 socket。
  这也是区分「人取消」与「网断了」的唯一可靠依据：真的断网会先走一条写超时（TCP 重传要几十秒），
  而这里是字节仍在流动、四百毫秒内一个干脆的 RST。
- **我方现在把这一支说错了**：`SendActivity` 把它归进「连接被中断」，副行提示「保持两台设备都亮屏、
  隔空投送不切走，再点一次设备重试」——对「人主动取消」这句指错了方向（人已经明确不要了，重试只会再弹一次询问）。
  `SendActivity` 里**已经有** `moving[0]`（字节有没有真的开始走），但只用在了超时那一支；
  同样用在 reset 上就能把这两件事分开，而且不必比对异常类名或文案——这与 `RefusedException`/`PeerMismatch`
  用类型而不是词句判定的做法一致。待办，尚未改。
- **接收侧自己取消（2026-11-19 14:24 实测，我方为接收方）**：与上面那一支是同一件事的两面。
  发送侧取消对我们是「对面 RST 掉了」；我方作接收方时，要**自己发出**这个 RST，形状必须对得上，
  否则 iPhone 会把一次人为中断读成「传输完成」。四条事实：
  - **按钮的归属是进度卡，不是邀请卡**。邀请卡上已经有【拒绝】，再放一颗同样意思的按钮，人会
    以为两者不一样。进度卡本来一颗按钮都没有——人点了「接收」之后在系统这一侧没有任何出口，
    只能等，或者去杀进程。入口是 `LiveCard.updateWithCancel`，走 `post(...)` 新增的
    `stopLabel`/`stop` 两个参数（重载，不改原来那六处调用点的签名）。
  - **`PendingIntent` 的 request code 落在 `0x0C00 + (id & 0x3FF)`**。extras 只按 request code
    区分，撞号会互相覆盖：两台 iPhone 同时发（真实场景）时，共用编号的第二张卡会把第一张的
    session 覆盖掉，人按「取消」取消的是另一次传输。这个段位同时避开了 `0x0B01`/`0x0B02`
    （接受/拒绝）、`0x0B03`（打开文件）、`0x0B05`（打开链接）。
  - **复用 `DecisionReceiver`，靠 action 分岔**（`com.oshare.airdrop.RECEIVE_CANCEL`）。它是
    `exported=false`，只能被本应用自己的 `PendingIntent` 唤起。**不能走 `answer(false)`**——
    那会把这次接收改写成「已拒绝」，而拒绝是另一件事：那时对面还在问，还有一个 `401` 可回；
    取消是「已经在传了，停下」。`cancel()` 内部靠 `decided` 的计数分岔，正是这两件事的边界。
  - **关连接不能在广播线程上做**。`DecisionReceiver.onReceive` 是主线程，而 `SSLSocket.close()`
    不是扔描述符：它先写一个 TLS `close_notify`，那是一次真实的写网络。2026-11-19 14:12 那一版
    就是这么崩的，崩的是整个进程（`Unable to start receiver`，`Caused by: NetworkOnMainThread
    Exception at ReceiveHost$Attempt.cancel`）。修法：另起一条 daemon 线程，**先
    `setSoLinger(true, 0)` 再 `close()`**，RST 立刻出去、不等发送缓冲排空——这正是上面
    /Upload 那一支里对面做的事，两头对称。读线程**必须**被叫醒，而这里能叫醒——**靠的是
    `SO_LINGER(0)`，不是 `close()` 本身**（15:13 三趟实测后更正，原来记的「叫不醒」是漏了前提）：

    - 光 `close()` 一个正阻塞在 `read` 里的 fd，Linux 上确实不保证唤醒那次 syscall（fd 引用还在
      syscall 手里，连接状态没变）——`SoTimeout` 是那时唯一的兜底。
    - 但 `SO_LINGER(0)` 下 `close()` 走的是另一条路：先把连接推进 `TCP_CLOSE` 并发 RST，
      内核随之唤醒这个 socket 上所有等待者，in-flight 的 `read`/`write` 立刻带错误返回。

    所以先 linger 再 close 这个顺序不只是「为了发 RST 给对面」，它同时把本侧那条阻塞的 syscall
    一起收掉。三次实测的延迟分别是 **4 ms / 51 ms / 60 ms**，没有一次是靠超时兜的底。
  - 实测（回环 socket 对 + 真 `decide`/`progress`/`cancelAction`，只有「对面是谁」是假的）：

    ```text
    14:23:23.240  invitation airdrop-1 from 卷卷薯 files=none links=0
    14:23:24.439  invitation airdrop-1 accepted after 1198 ms asked=cloud answered=card
    14:23:24.443  live card: 正在接收 · 45% · 2.2 MiB / 4.8 MiB · 45%
    14:24:21.963  probe reader: java.net.SocketException: Socket closed after 3129344 bytes
    14:24:21.963  probe reader: abandoned, closed=true
    14:24:21.965  probe writer: sun.net.ConnectionResetException: Connection reset
    ```

    `close` 到读线程出来是同一个毫秒；对面拿到的是 `Connection reset`（RST），不是 EOF；
    卡片在同一帧里被撤掉（截图前后对比）。**注意这最后一格不是自动成立的**：撤卡写在
    `end()` 的 `if (cloud)` 里，而 `cloud` 只有 `decide()` 会置真——绕过 `decide` 直接
    `LiveCard.updateWithCancel` 投卡的探针会看到「socket 关了、卡还在」，那是探针的错，
    不是生产代码的错（这一版为此白查了一轮）。
- **同一件「人取消」，两个方向落下来的是两种 errno（2026-11-19 14:55 实测）**。上一支是
  **我方为发送方**（对端拒绝/中断 → 我方收到 RST）。这一支把角色调过来：**iPhone 发、我方接**，
  人点【接收】走进传输，到 14% 时在 iPhone 上按取消：

  ```text
  14:54:58.239  live invite: 卷卷薯 想给你发文件 · 1 个文件 · 16 MB，30 秒内没人应答就放弃。
  14:55:01.544  invitation airdrop-5 accepted after 3326 ms asked=cloud answered=notification
  14:55:02.702  live card: 正在接收 · 0% · 1 个文件 · 64.0 KiB / 15.5 MiB · 0%
  …（每 200 ms 一条，直到）
  14:55:17.916  live card: 正在接收 · 14% · 1 个文件 · 2.3 MiB / 15.5 MiB · 14%
  14:55:18.287 W receive from fe80::704d:92ff:fe08:dd2%awdltun0 failed: Software caused connection abort
  14:55:18.294  live card: 接收失败 · 连接被系统切断，检查后台限网 · 14% (heads-up)
  ```

  **是 `ECONNABORTED`，不是 `ECONNRESET`**，而且卡片上那句话指错了方向。两者的语义边界：
  **Reset 是对端发来的 RST**（对端主动、链路是通的）；**Abort 是本机内核把 fd 收了**。
  在 AWDL 这条隧道上，对端把那头的隧道撤掉之后，我们这一侧的内核跟着收尾，于是我们看到的是
  abort——`Reset` 只出现在**本机是主动收手的那一方**时。所以「哪个方向」决定「哪个 errno」，
  不能拿一边的实测去套另一边。
- **由此修掉一处误译**：`Listener.because()` 原来把**所有** `ECONNABORTED` 一律翻成
  「连接被系统切断，检查后台限网」（依据是 2026-11-17 的 `nwpower` 实测，那次确实零字节）。
  这次走了 2.3 MiB 才断，同一句话就成了甩锅。判据换成**断之前有没有字节真的落地**：
  `Meter` 新记一个 `done`（**写在 200 ms 节流的 `return` 之前**，否则最后几次——恰好是断前那几次
  ——会漏记），传进 `because(Throwable, long)`：
  - 落地 0 字节 → 仍是「连接被系统切断，检查后台限网」（真有可能是限网）。
  - 落地 > 0 → 「传输在中途被中断，已收到 2.3 MiB，没有存下」。**这句刻意不点名是谁停的**：
    从我们这一侧分不出「对面取消」和「对面息屏断网」，说得更具体就是猜。
- **发送侧【取消发送】（2026-11-19 15:0x）**：接收侧那颗按钮（上一支）只解决了「我们收一半
  想停」。反过来，我们发一半想停，此前唯一的路是回发送页点头像——而人那时多半不在那一页，
  卡片才是他盯着的地方。
  - **三个相位都挂**，不像接收侧只挂进度卡：`准备中`、`等待对方接受`、`传输中` 都挂
    `LiveCard.updateWithStop(..., "取消发送", …)`。**「等待对方接受」尤其需要**——发错设备的
    时候，对面手机上已经弹出邀请卡了，多等一秒他就多点一下「接受」。
  - **发送侧没有可复用的【拒绝】**：接收侧的邀请卡上本来就有【拒绝】，进度卡再加一颗会让人以为
    两者不一样（那是同一支里的取舍）；发送侧从头到尾只有这一颗，不存在重复。
  - **`PendingIntent` 用单码 `0x0D00`**，不分段（接收侧分段是因为可以有两台 iPhone 同时发）。
    同一时刻只跑得了一次发送（`SendActivity.sending` 全局唯一），不会撞号。
  - **新落点 `SendCancel`（`exported=false`），不是塞进 `DecisionReceiver`**：那个类接的是
    **收**的事，这个接的是**发**的事，合成一个就要它同时认识 `ReceiveHost` 和 `SendHost`，
    而这两边没有一处共用状态。用 `getBroadcast` 而非 `getActivity`——取消不该弹界面（与
    【重试】的 `getActivity` 正好相反：重试要起页面，取消推面板是最不该做的反应）。
  - **`stopping` 缓存要连 `session` 一起存**（`stoppingSession`）：进度每 200 ms 重画一次，
    每次都 `getBroadcast` 就是白跑一百次 binder；但只认控件不认趟次，会让第二趟的取消去停第一趟
    ——那时早没人在跑了，人按下去就是一颗不响的按钮。
  - **动手的地方在发送线程，不在广播线程**：`SendCancel.onReceive` 只把 `session` 交回
    `SendHost.cancel()`，真正掐连接走 `HangUp.now(live)`（`SO_LINGER(0)` + `close()`，见上支）。
    理由与接收侧一模一样：主线程上 `SSLSocket.close()` 会写 TLS `close_notify`，直接
    `NetworkOnMainThreadException`。
  - **`HangUp` 因此从 `ReceiveHost` 里提取出来成为共用件**：这段知识（为什么不能在就地 close、
    为什么必须 RST、为什么顺序不能反、为什么短命 daemon 线程而不是线程池）原来只写在一个
    `Attempt.abort()` 里，第二个调用点出现时必须搬出来，否则下一个人会照着一半的注释写第三遍。
  - **取消不留终态卡，直接撤（15:07 实测后改）**。第一版给取消留了一张终态卡，实测那一行是
    `已取消 · 已取消 · 保持两台设备都亮屏、隔空投送不切走，再点一次设备重试 · 29% (heads-up)`，
    一句话里三处错：标题已说「已取消」而副行又拼了一遍「已取消」（`detail` 那个三元表达式没给
    `stopped` 留分支，落进了兜底 `else`）；兜底那句「再点一次设备重试」是给**故障**写的——
    人刚说了不要，叫他重试等于问他第二次；而且按失败卡的规格弹了一次 heads-up，在刚按完的那一
    秒把面板顶到人脸上。修法不是改文案而是**不留这张卡**：`SendHost.finished` 见 `CANCELLED`
    直接 `LiveCard.drop()`，与接收侧 `Attempt.end()` 同一个口径（「一张停在中间态、也没人能
    再点的常驻卡，比没有更糟」）。链路那一截本来就是对的：

    ```text
    15:07:49.860  send cancelled by the person
    15:07:49.927 W send failed: ec585e59e6c5 — java.net.SocketException: Socket is closed
    ```

    广播落地 → 置 `cancelling` → `HangUp` 关 socket → 发送线程 **67 ms** 后撞上
    `Socket is closed`。另把这行的 W 按 `stopped` 拆成 I（`send stopped: …`）：日志是拿去读的，
    一个 W 会把「他说不要了」读成一次事故；异常名照留，它是「确实断在这条 socket 上」的证据。

  - **改完连跑三趟，三个阶段各一次，全过**（15:13，`SendCanceled` 撤卡版）：

    ```text
    # ① 等待对方接受相位（15:13:01.208 投卡，6 秒后取消）
    15:13:07.157  send cancelled by the person
    15:13:07.161  send stopped: b975b91403ce — java.net.SocketException: Socket closed   ← 4 ms
    15:13:10.057  send to b975b91403ce …                                                  ← 3 秒后就能重发

    # ② 传输中 25%（15:13:20）
    15:13:20.175  send cancelled by the person
    15:13:20.226  send stopped: b975b91403ce — java.net.SocketException: Socket is closed ← 51 ms

    # ③ 传输中 40%（15:13:45）
    15:13:45.335  send cancelled by the person
    15:13:45.395  send stopped: b975b91403ce — java.net.SocketException: Socket is closed ← 60 ms
    15:13:47.277  browse: dropped b975b91403ce@fe80::… after 2287 ms of silence (threshold 2200)
    ```

    三趟都没有再出现 `live card: 已取消`（撤卡生效的直接证据——`drop()` 本身不打日志，
    但 `finishWithActions`/`failedWithActions` 都会打，没打就是没走那两支）；
    `cmd statusbar expand-notifications` 后截图里也确实没有传输卡，只剩服务那条常驻的
    「正在接收 · 正在监听 8770」。

    两点附带观察：
    1. **①走的是读侧、②③走的是写侧，异常名不一样**（`Socket closed` vs `Socket is closed`，
       前者 Conscrypt 在 TLS 读里抛、后者 `java.net.Socket` 抛），但都在几十毫秒内出来。
    2. **取消之后对端会静默一会儿**：③ 两秒后 `browse` 就把 iPhone 判成掉线（阈值 2200 ms），
       这是它收到 RST 后把这一轮 AWDL 会话停了，不是我们这边的问题；下一次点设备前要等它
       重新广播（①取消后重发中间隔了 3 秒，正好够）。

- **终态之后那颗【取消发送】不该还留在屏上（2026-11-20）**：上上支留下的接口问题，用户实测
  撞到了——「发送成功之后按卡片上的取消没反应」。抓现场日志确认不是手抖，是**屏上那张卡
  根本不是应用投的**：

  ```text
  23:22:32.864  phase sending 100% (296305129/296305129 bytes, lane 0 at 296305129)
  23:22:32.934  lane 0: done (all bytes written, waiting for the reports)
  23:22:32.943  live card: 发送完成 · 20 个文件 · 283 MB · 100% (heads-up)   ← 已终态
  —— 之后 65 秒，应用侧一条卡片日志都没有 ——
  23:23:37.957  cancel from the card: no send is listening for 2f391a32-…
  …5 秒内共 6 次，全部同一 session
  ```

  65 秒里没人投卡，屏上那张却挂着一颗按得到的【取消发送】——那是系统留着的前一帧。由此定位到
  `LiveCard.post()`：它在**同一条通知记录**上换按钮行（进度卡【取消发送】→ 终态卡【完成】/
  【重试】），而 `notify(id, …)` 不保证重建那一行 `PendingIntent` 所在的动作区，可能只把字换了。
  两条闸一起补：

  1. **治成因**：`post()` 里把「挂哪些按钮 + 是否终态」拼成一个**形状**串（`open`/`retry`/`stop`
     + `|end`/`|live`），形状与上次不同就 `manager.cancel(ID)` 再 `notify`——撤了才重建整张卡。
     只在变化时做，是因为进度每 200 ms 一次，每秒五次 binder 往返没必要。判据选「形状」不是
     「是不是终态」，因为邀请卡（拒绝/接收）→ 进度卡（取消接收）也是同一种变化：人在对面点了
     接受，两个答案换成一个出口。邀请卡不走 `post()`（它不发进度条、渠道也不同），所以在那条
     路上自己补记 `lastShape = "invite"`，否则随后那张进度卡的形状恰好与上一次传输留下的相同
     时，屏幕上的「拒绝/接收」就留下来了。
  2. **治症状**：`SendHost.cancel()` 拿到的 `false` 有两种来路——页面已销毁（本来就没有传输），
     和**按在一趟早就结束的发送上**。新增 `settleCard()` 收拾后者：先 `LiveCard.drop()` 再
     `LiveCard.restate()` 按原样重投一张终态卡，日志打 `re-flushing the card`。**刻意不抬头**
     （`heads-up=false`）——人为一件早就结束的事点了一下，得到的回应不该是通知栏再弹一次
     「发送完成」，那会读成「刚才那次又成功了一遍」。

  **`LiveCard.ID` 全机只有一张，接收侧也在用同一个 id**，所以 `settleCard()` 绝不能见到陌生
  session 就撤卡——那会把一次正在进行中的接收连卡一起撤掉。判据只有 `session.equals(settledSession)`，
  而 `CANCELLED` 那一支（撤卡、屏上本来就什么都没有）显式 `settle(null, …)`，让这趟之后不认
  任何 session。终态记录只留一趟：同一个 id 也就一张卡，记多了没地方摆。

- **「弹窗很慢」不在这条链路里（2026-11-21 定量）**：用户报「从 iPhone 发过来，弹窗要等很久」，
  而且两台 iPhone 表现还不一样。落盘的时间轴把这段空转定死在**对端**：`/Discover → /Ask`
  **12.71 s**、`/Ask → /Upload` 2.72 s。我方在 5353 与握手上的动作都在毫秒级（§5.8 量的应答
  间隔 590–1506 ms 是**它**的节奏）。这 12 秒是 iOS 在**构造 `/Ask` 之前**做媒体重编码的窗口
  ——证据是 `/Ask` 里的 `FileSize` 已经是转码后的尺寸、`ConvertMediaFormats` 已经是 `false`
  （详见 §6.2）。所以「优化我们的握手」对这件事无效，别再往这条路上投时间；换一台 iPhone 快
  一点，是因为那台机器转码更快，不是因为它更会连我们。

### 5.5 证书与信任（当前缺口）

AirDrop 用自签证书，TXT 里有指纹（键名是 **`h`**，不是 `phash`）。接收端不索取客户端证书
（`setNeedClientAuth(false)`：TLS 1.3 下被索要证书的 iPhone 不是出示，而是直接放弃）。
发送端按指纹钉住的逻辑已在代码里，但**真机跑不到「相符」这一支**——空口上对端 TXT 27/27
只有 `flags`、没有 `h`。是修实现还是换 TOFU，等一次「仅限联系人」A/B 再定。

### 5.6 mDNS 五条 legacy 约定（都踩过）

> 这一节的存在理由是：五条每一条都能靠「讲道理」得出**错误**答案，而且得出之后代码会安静地不对——
> 不报错，只是对面看不见我们。

1. **`unicast` 只看问题里的 QU 位，不看源端口**；只有带 QU 的问题才单播回来源，其余一律组播。
   别把「我们自己发问该带 QU」那段注释当反例——那是浏览侧，因为 bridge 只转发单播帧。
2. **实例名里的转义反斜杠要原样上 wire**，否则同一台设备在别人眼里出现两个名字。
3. **`Record::target()` 只对 SRV 有意义**，在 PTR/TXT/AAAA 上取到的是「碰巧同偏移的字节」。
4. **legacy 的 `close()` 撤回是死代码**（`withdraw()` 第一句就因名字相等而 return），
   要撤回必须自己写。
5. **`flags` 只能照抄真机，不能靠加位推出来。** 我们原来发 `fl=137`，以为「支持 DVZip(0x002)
   + 支持 Pipelining(0x004)」就是把这两位或进去，于是写了 `2185`。实际 `2185 = 0x889`，
   而 `2185 & 0x002 = 0`、`2185 & 0x004 = 0`——**想要的两个位一个都没置上**，多出来的
   是 `0x800`（真机也没有）。这个错误在日志里没有任何痕迹，那一轮「补上 DVZip/Pipelining」
   的 A/B 因此从未真正执行过。照抄真机 iPhone 的 TXT 得到 **`flags = 13311 = 0x33ff`**。
   判据只能来自抓包原文（`.workbuddy/tmp/air1.pcap`，Apple vendor-specific action frame，
   OUI `00:17:f2` / category `0x7f`）：`0x33ff` 里 `0x040 SupportsIris`、`0x100`、`0x200`、
   `0x1000`、`0x2000` 的含义至今未知，**照抄而不解释**是对的——多解一位就可能让对端把
   我们的应答读成另一种设备。

### 5.7 进度百分比是谁的字节

**收的方向**：iPhone 的进度是「写进 socket 的字节」，我们的是「解密后落盘的字节」，中间隔着它的发送缓冲
+ 我方通告窗口 + 在途。2026-11-15 量到 iPhone 100% 时本机还在 85%，49.3 MB × 15% = 7.4 MB ÷
98.8 KB/s ≈ **75 秒尾巴**，正好是「到 100% 之后还要等一分多钟」。当时那条链路的 BDP 只有
25 ms × 98.8 KB/s ≈ 2.5 KB，窗口却开到 4.19 MB（1700 倍）——**买不到吞吐，只买到进度滞后**。
（数字取自当时的接收基线，后来接收已换到 857 kB/s 那一版；收缩 `SO_RCVBUF` 到 512 KB 再做一次 A/B
这个实验一直没做，仍属未定论。）

**发的方向**：卡上的百分比是我们自己发出去的字节，它是准的。

推论：**任何「进度不动」的怀疑，先问是哪一侧的百分比**；两次传输之间也不要拿百分比比速度。

### 5.8 对端什么时候「算走了」（Q2）

**判据只能建在「我们问了，它没答」上，不能建在对端发帧的节奏上。** 这一条是 2026-11-18 抓包
推翻的：原来 `timeout()` 按对端自己的广播间隔（`rhythm`）推阈值，而实测 iPhone 在我们的浏览
链路上 **`q=0`**——它一包查询都不发，全是应答，14 个包的间隔 590–1506 ms（`speaks every 946 ms`）。
对一条「只答不问」的链路，对端的节奏根本不是它的在场证据，我们的询问周期才是。

- **iPhone 关掉 AirDrop 时不发 goodbye**：整个过程里 `gone` 分支一次都没走到，`absorb()` 里
  TTL=0 的那条路径在真实场景中用不上（留着，别的实现会发）。
- **阈值按「凑不凑得齐」分两档**，因为这两种沉默不是一回事：

  | 手上有对端的什么 | 阈值 | 理由 |
  | --- | --- | --- |
  | 地址 + 端口都齐（`connectable`） | 静默 **1 200 ms 后敲门**，两次不通即删（实测 ≈2.3–2.5 s）；**20 000 ms 只做兜底** | 敲门量的是服务端口，不是沉默，所以它能在「睡着」和「走了」之间划出硬线（2026-11-19 18:25，见下） |
  | 只有半个记录（partial） | 6 000 ms | 名字见过、地址没记住，凭据太薄，等久一点 |

  **为什么不是 6 秒**（2026-11-19 17:16 的现场）：那时旁边一直有一台 iPad 在说话（`foreign` 每半秒涨），
  所以链路是活的、`LINK_QUIET_MS` 那道门根本没关上，走的是正常 6 秒那条路。iPhone 的轨迹：
  ```
  17:16:09.642 ready   ac064e658d14 after 1394 ms of browsing
  17:16:12.521 prompted … after 1949 ms of silence     ← 单播叫了一次，没回
  17:16:16.632 dropped … after 6060 ms (threshold 6000, rhythm 420 ms, gaps 2)
  17:16:30.683 ready   … after 2197 ms                 ← 十四秒后原样回来
  17:16:32.901 prompted … after 1828 ms                ← 连续三次单播，全无应答
  17:16:36.298 prompted … after 2078 ms
  17:16:41.881 prompted … after 1760 ms
  17:16:46.240 dropped … after 6119 ms (threshold 6000, rhythm 1216 ms, gaps 2)
  ```
  **三次单播全部石沉大海**，所以这不是「漏听一个包」，是它的应答器整段没在工作。同一时刻
  `iw dev wonder0 station dump` 里它的站点条目 `connected_time` 从 161 s 一路涨到 320 s、
  `signal −26 dBm`，**802.11 一直在线，两次删除的时刻它都在**。换句话说那两次删除纯粹是我们误判，
  而任何小于 8 秒的门槛都必然闪。

  **20 秒就是 `LINK_DEAD_MS` 那条天花板**，两个数合成一个：它们回答的是同一个问题「沉默多久之后
  我们才有资格说它走了」，答案是「很久，而且我们分不清它是在睡还是走了」——那就只有一条路，容得下。
  代价照旧写在明处：对方真关掉 AirDrop，格子会多留十几秒；这比「活着的设备每六秒消失一次」划算。
  **这笔代价在 18:25 被敲门收走了**（见下一条）：20 秒现在只在「敲不出结论」时才生效，正常情况
  是 2.3–2.5 秒消失。

  早先那档是 **2 200 ms**（实测 `dropped 4708f4c8d371@… after 2440 ms of silence (threshold 2200,
  rhythm 940 ms)`，比原来的 6 065 ms 快一倍半，和 iPhone 那侧的 ~2 s 同量级）。**11-19 两度被真机
  打穿**：`2727 / 2608`、`4134 / 4080`、`6218 / 6000`、`6223 / 6000` 四组全落在
  `[阈值, 阈值+250]` 之间——因为 `expire()` 每 250 ms 跑一次，**删除时刻必然是阈值之后的第一个检查点**，
  所以这些数只证明检查粒度，证明不了「余量差一点」。于是放弃「靠调数值耐沉默」，改两手：
  阈值抬到 6 秒（与 partial 同值），以及见下一条——**把沉默本身变少**。
- **「静了 N 秒」这个判据在我们这侧分不清两件事**：对端走了，还是 AWDL 抖了一下（时隙调度两端不同步
  会周期性丢包）。前者要删、后者不能删，而两者在计时器眼里一模一样。所以补一条 `prompt()`：对静默超过
  `PROMPT_AFTER_MS = 1500` 的、能连的对端**单播**问一句，把「等它自己开口」换成「一问一答」。依据是
  RFC 6762 §6.7——收到**单播**查询必须**单播**应答，重复应答抑制只针对组播。
- **沉默要分成两种，处置相反**（2026-11-19 16:50 抓到现场后加的，代码里是 `LINK_QUIET_MS`）：

  | 谁在安静 | 含义 | 处置 |
  | --- | --- | --- |
  | 链路活着、只有它在安静 | 它真走了 | 按 `timeout()` 删（**能连的 20 秒起**，还不完整的 6 秒起） |
  | 链路自己也安静了 | **我们聋了，没有信息** | 不删，只留 `LINK_DEAD_MS = 20 000` 天花板 |

  **11-19 17:16 之后这两行在「能连」这一档上合流了**：`CONNECTABLE_MS` 抬到 20 秒（因为对面是
  成段休眠，见上一条），和 `LINK_DEAD_MS` 同值，于是两种沉默对能连的对端给出同一个数。表格留住
  的是**语义**，不是两个不同的数：不完整的那一档（6 秒）还在靠它分辨，`deaf` 那一行还负责在日志里
  写明「这次为什么要等 20 秒」。

  现场是 `dropped … after 6079 ms` → `pulse: 0 peer(s), foreign 270 (last 7844 ms ago)` →
  **4.5 秒后同一个实例名原样回来**。也就是说那 7.8 秒的沉默不属于设备、属于链路，而 `expire()`
  一直在拿「链路没声音」冒充「它没说话」。`LINK_QUIET_MS = 4000` 卡在健康链路最大间隔
  **3144 ms** 之上、真停顿 7844 ms 之下。
  ~~且必须小于 `CONNECTABLE_MS`（否则门开得太晚，人已经删了）~~ —— 这句在 11-19 17:16 失效：
  `CONNECTABLE_MS` 已经自己走到 20 秒，比它长五倍，夹逼关系不存在了。现在这个 4 秒**只剩一个
  用处**：`expire()` 决定「删还是留」，猜错一次没有代价（最多多留一台）。
  它**不再**用来决定「该不该重开 socket」——那里换成了 `DEAF_SILENCE_MS = 20 000`，因为重开的
  代价是把路上的应答全丢掉，同一个 4 秒在那边会造成每五秒拆一次 socket（见 §11 的 17:20 记录）。
- **判据换层：不再靠「沉默多久」，改为 TCP 敲它的 8770**（2026-11-19 18:25 落地，代码 `KNOCK_*`）。
  20 秒那条路能站住，是因为「沉默」这个信号只够区分「它在答」和「它没在答」，而后者包含「走了」「睡着」
  「AWDL 丢包」三件事。真正的分界在**服务端口还开不开**——那是内核态的事实，不受应答器打盹影响：
  - **关掉 AirDrop 在 802.11 层没有痕迹**：`iw dev wonder0 station dump` 里 iPhone 的站点条目仍是
    `associated: yes`、`connected_time` 继续涨、`signal −26 dBm`。链路层判不了。
  - **8770 判得了**：开着时 `nc -6 …:8770` 立刻成功；关掉时 iOS 对未监听端口**静默丢弃、不回 RST**，
    探测一直等到超时，之后的请求才变成 `ECONNREFUSED`。两种失败都说明那个端口后面没人。
  - **240 次连敲 100% 成功**（AirDrop 开着的 42 秒里，中位 ~70 ms，最慢 471 ms，中间一次没断）——
    所以「连不上」不会因为应答器睡着而误报，可以直接当硬判据。
  - 于是：静默 **1 200 ms** 后敲第一下，两次都连不上（`KNOCK_FAILS = 2`）就判走，不再等 20 秒。
    实测关 AirDrop 到格子消失 **2.3–2.5 秒**（`18:23:34.948 dropped … (2359 ms of mDNS silence)`）。
  - **自家路由故障不投票**：`Network is unreachable` / `No route to host` 说的是我们没把包送出去
    （曾实测 `awdltun0` 上一条 IPv6 路由都没有，包被送去 `rmnet_data3`），这类失败**不记 miss**，
    退回 20 秒天花板。差点用自家故障删掉用户设备，这一条是给那次踩刹车。
- **删了之后必须先验再显示**（`Peers.buried` / `Peer.unproven`，2026-11-19 18:25）。**AirDrop 关掉以后
  iOS 还会拿缓存继续答 mDNS**——18:16 那一轮，删掉之后它照样答了完整的 SRV/TXT/A，于是四趟回魂：
  `dropped 42.3 → ready 45.3 → dropped 52.6 → ready 55.4 → dropped 63.1 → ready 67.8 → dropped 73.2`。
  记录和端口给出相反的答案时只信端口，所以删的时候给地址立一块 2 分钟的墓碑：这期间它的记录再齐全也只是
  `suspect`，不进列表，先敲一次门——敲通（实测 10–167 ms）当场撤销、立刻显示，敲不通继续当它走了。
  日志里因此多了两行可验：`suspect … records are back but this address was proved gone N s ago` 与
  `knock: … answered at port 8770 in N ms — that was only cached records, it is really here`。
- **同一个敲门，反过来治「打开面板半天看不到对方」**（`Peers.recall`，2026-11-19 18:38）。mDNS 是
  「对方说、我们听」，它那一刻不讲就只能等：18:36 冷启动的实测是 `ready … after 26 838 ms of
  browsing`，而这期间 `nc -6 …:8770` 一直是通的——**端口开着，只是它没开口**。既然是同一个判据，
  把「能连的设备」写进磁盘（`shared_prefs/peers.xml`，最多 6 台），下次启动先按旧名单敲门：
  - 敲通就当场显示，实测 `recall: … answered in 12 ms — showing it after 14 ms of browsing`
    （另一次 276 ms，AWDL 时隙的缘故）。**26 838 ms → 14 ms。**
  - 敲不通就什么都不做：`recall: … did not answer in 198 ms (ECONNREFUSED) — leaving it to mDNS`。
    旧名单不会把一台已经走了的设备留在屏上——名字可能过时（iPhone 每分钟换实例名），地址不会
    （链路本地地址由 MAC 推出来），而**判据仍然是端口开不开**。
  - 名单只在「有新的一台进列表」时重写；跑在敲门线程上，不占浏览线程的查询节奏。

- **重开 socket 的退避以前是个摆设，而且空表时不该勤快**（2026-11-19 18:52 修，代码 `deafWait`）。
  18:39 那轮日志里六行 `deaf: 40 queries on this socket, nothing from anyone for 20 05x ms`——
  间隔精确到 20 秒、**一次 40 秒都没有**。原因是两个「刚重开」的副作用叠在一起：`reopen()` 把
  `lastForeign` 清零、`quiet` 于是改从 `openedAt` 起算，重开那一刻 `quiet ≈ 0` → 判「不聋」→
  `else if (!deaf …) deafWait = DEAF_RETRY_MS` 立刻把退避洗回 5 秒，于是「翻倍到 60 秒」那行
  代码一次也没生效过。两处改：
  - 复位挪到**真正收到别人的地址记录**那一刻（`absorb` 的 `addr` 分支）——那是唯一能证明「不再
    聋了」的证据，重开本身不是证据。
  - **表里一台都没有时，退避直接按 60 秒**：空表的沉默是「这条链路上没人该说话」，不是「我们
    聋了」。为什么敢放这么慢——发现新设备走的是**组播 announce**（我们自己收得到）加 **TCP 敲门**，
    两条路都不经过「查询的单播应答」，而重开 socket 治的恰恰只有后者。
  - 实测（`round23/24`，iPhone AirDrop 关着、表空）：`18:55:32.440 … the list is empty, so this is
    the slow probe` → `18:56:32.638`（60 190 ms）→ `18:57:32.860`（60 213 ms）。同一段场景按旧
    代码是每 20 秒拆一次，**六次变一次**。

- **怎么用免 root 的办法分清「它走了」和「我们聋了」**：读 `/proc/net/dev` 里 `awdltun0` 那一行的
  RX 字节/包数，隔 20 秒读两次。2026-11-19 17:01 那次：**RX `4332327 → 4332327`、包数
  `11315 → 11315` 一个没动，而 TX 涨了 47 个包**——所以我们一直在发、一个字都没收到，是它走了。
  这个方法比 `foreign` 硬：`foreign` 只数「解析成我们认得的地址记录的**别人**的包」，而我们自己的
  组播不会绕回来计进 RX（实测 RX 恒 0 时 TX 一直在涨），所以 RX 是干净的「外面有没有人跟我说话」。
  **为什么必须有这一条**：单播会被 5353 上后绑的 socket 抢走（`/proc/net/udp6` 上现在有 7 个
  `:14E9`），那种「聋」在应用层看和「走了」完全一样，而 RX 能一眼分开。
  `ip -6 neigh show dev awdltun0` 在这台机器上永远是空表，别拿它当判据；`ip link show` 和
  `/sys/class/net/*/statistics/` 在 adb shell 下是 Permission denied。
- **抓包怎么做**（这台机器上没有 tcpdump，`busybox` 也没有抓包工具）：让唯一握着 5353 socket 的
  `Peers.receive()` 把每一包原样打进日志——源地址、长度、Q/A、解析行、整包十六进制，开关是
  `Peers.WIRE`。**为什么不复用 `DIAG`**：那个开关会把 `expire` 改成「永不删」，而这一轮要量的
  正是「多久删」，两个目的在同一次运行里互斥。**为什么非要十六进制**：`mdnsRecords` 只交出它认得
  的那几种记录，而「关掉时到底发了什么」的答案很可能落在它不认的东西上（TTL=0 的撤回、只带
  flags 的空应答、一次 probe）——这几样在解析行里全都看不见。
- **抓「本机开关」期间对端的全部报文，没有得到有用信息**：这段时间里对端发的东西和平时一模一样，
  只有应答。判对端在不在，只能靠我们自己数沉默。

- **出现了不要等记录：端口一通就复活**（2026-11-19 19:45 落地，代码 `Grave.answered` / `resurrect()` /
  `revive()`）。19:28 那一轮把「关 → 等 3–5 秒 → 开」的两端对齐量了一遍：端口 `35.774` 开，`38.853`
  才 `ready`，**3.08 秒**。拆开是两段，而两段都不是「我们慢了」：
  - **端口开到被发现 2.03 秒**。这是自己的 bug：`watch()` 用固定 `Thread.sleep(1000)`，而探测本身
    是阻塞的（最长 `KNOCK_TIMEOUT_MS = 2500`），真实周期变成「1 秒 + 探测耗时」，相位一路往后漂。
    改成绝对节拍（`next += WATCH_EVERY_MS`，超支时 `next = now` 不补课），并把守望的探测超时单独
    降到 `WATCH_TIMEOUT_MS = 400`——守望只负责「该问了」，量得准不准由 `knock()` 用满 2500 ms 再说，
    所以短超时的代价只是多问一次，**不会是误删**。
  - **`ask` 发出到记录回来 0.79 秒**。先怀疑查询没带 QU 位，**查过了，位一直在**：`crates/ffi/src/
    browse.rs` 的 class 就是 `CLASS_IN | CLASS_UNICAST`（`0x8000`），解析侧也认。真正的答案来自两次
    实验落点相同：19:15 端口 `59.84` 开 → 记录 `62.48` 回来（2.64 秒，期间按 300 ms 问了 8–9 次
    无人应）；19:28 端口 `35.774` 开 → 当场单播问 → 记录 `38.600` 回来。**问与不问，记录都在端口开后
    2.6–2.8 秒回来；而这条链路一个来回只要 9–13 毫秒（敲门实测）。所以那 2.6 秒里它答不上来，不是
    没听见，是它还没注册完。** 这一段不受我们控制，于是干脆不等它。
  - 改法：墓碑记**整只 `Peer`** 而不是只记端口。它被证死之前说过自己是谁（名字、型号、指纹、地址、
    端口），端口一开就已经在全表最硬的判据上成立，剩下只是一个名字，晚一两秒对齐不影响任何一件事。
  - **`peers` 这张表只有浏览线程能改**：`absorb()` 遍历它时不加锁，跨线程 `put` 会抛
    `ConcurrentModificationException`，而那个异常会顺着 `loop()` 的 catch 把整条浏览掐掉。所以守望
    只往碑上贴**便条**（`Grave.answered`），由浏览线程在 `expire()` 开头调 `resurrect()` 动手。为了
    早 250 毫秒，不值得冒「面板直接死掉」这个险。
  - 实测（两端对齐，设备侧每 0.25–0.3 秒直探 8770）：出现 **0.72–1.09 秒**（端口 `10.749~11.125` 开，
    `11.841` 复活）与 **0.69–0.96 秒**（`32.898~33.171` 开，`33.856` 复活）；**3.08 → 0.9 秒**。
    消失仍是 **1.57–1.86 秒**（端口 `20.248` 前断，`21.817` 掉出）。`suspect` / `ready` 一条都没再
    出现，两次复活后列表都只有一格，0 崩溃。
  - **剩下的 0.9 秒就是守望节拍本身**，值不值得再压：`WATCH_EVERY_MS` 从 1000 降到 500 只换来约
    0.4 秒，探测流量翻倍，肉眼分不出——**不动**。消失侧那 0.8 秒的「两次敲门间隔」也不动：那是防
    AWDL 时隙调度吃掉单个 SYN 的唯一保护。

### 5.9 iPhone 判「对端还在不在」：三层，各判各的

iPhone 把一个对端画成「在」或「走了」，不是一件事，而是三层各判各的，延迟差两个数量级。分层之后
「它凭什么 2 秒就知道我关了 AirDrop，而我要 2.2 秒」这种问题就不必再猜了。

| 层 | 它看什么 | 量级 | 我们能影响吗 |
| --- | --- | --- | --- |
| AWDL 链路层 | 我们还在不在发帧（帧流的连续性） | **约 2 秒** | 能：只要还在发，它就一直当我们「在」 |
| mDNS 层 | 我们对 `_airdrop._tcp` 还答不答 | **≈ 0** | 几乎不能：它在这条链路上 `q=0`（只答不问），我们不问了，它也不会因此更快判定 |
| 会话层 | `/Ask` 上有没有 `200` | **15 秒起** | 能：明确回 `401` 就立刻结束（§5.4） |

- 「约 2 秒」和我们的 `CONNECTABLE_MS = 2 200` 是同一个数量级，但**不是同一件事**：它是链路层的
  在场判定，我们的是「多久没听到它应答就把它从列表里删掉」。
- **`station dump` 不是那 2 秒的来源**（曾误判过一次）：那是 Wi-Fi 驱动对 STA 的统计，AWDL 上的
  对端根本不以 STA 的身份出现在里面；拿它当「iPhone 多久发现我们走了」的计时器，只会得到一个
  自洽但错的数字。
- 三层的分工别混：链路层决定「它画不画那个圆」，会话层决定「它画的是等待中还是已拒绝」，mDNS 层
  只决定「它手上还有没有我们的地址可以连」。**mDNS 层不给任何可优化的延迟**——我们关掉 AirDrop
  时它当场就空了，所以「我们这边一关，iPhone 反应慢」的答案永远在链路层。
- 反向读一遍这条表：我们自己做对端列表时用的那 2.2 秒，对应的是 iPhone 的**链路层**节奏，而不是
  它的 mDNS 应答节奏（§5.8 量到它的应答间隔 590–1506 ms，那个数字不能当在场判据）。

---

## 6. 问题总账（现象 → 根因 → 修复）

### 6.1 内核与驱动

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| `RTNETLINK answers: No such device` | 虚拟名不是 `wonder0` | 用 `wonder0` |
| 帧到不了虚拟网卡 | 接口类型是 `NAN` | 改 `ADHOC`（IBSS） |
| 每 30 秒射频被带走 | IBSS 未固定频率 | `fixed-freq` |
| `up` 后 1 秒内 bind 失败 | DAD 让 link-local 处于 tentative | `accept_dad=0` |
| `insmod wonder` 报 `-12`（不是 ABI 问题） | 1 MB 连续内存拿不到 / HAL 还持有 master | `defrag_memory` + 停 HAL 后重试一次 |
| 参数全对但只有 2 MB/s | vdev 是 NOHT，速率被钉在 24 Mb/s | join 时带 `HT20`（§4.3） |
| 接口起在 80MHz 却一直 `NO-CARRIER`；`send_vdev_start_cmd_tlv` 打出 `chanmode 5`（`11NG_HT20`）配 `center_chan 5775` | **`passthru_diag` 的 bit0 把 `phy_mode` 钉死**。`opairshare_passthru_diag_flags()` = `passthru_ht?1:0` 加 dry_run 的 bit1，而脚本 insmod 里 `passthru_ht=1` 常开；`target_if_vdev_mgr_tx_ops.c` 的 passthru 分支于是**每次** start 都把 `param->channel.phy_mode` 写成 `WMI_HOST_MODE_11NG_HT20`。而固件是从 `phy_mode` 反推宽度的（`wmi_unified_api.c` 里 `WMI_HOST_MODE_* → WMI_HOST_CHAN_WIDTH_*` 一张表，`ch_info` 就是这么来的），`center_chan 5775` 被**无声丢掉**，`iw dev wonder0 info` 永远报 `width 20 MHz` | 覆盖层加 `opairshare_wt_req_bw`（`__wlan_hdd_set_wondertap_channel` 校验通过后 `WRITE_ONCE`）+ 导出 `opairshare_passthru_want_wide()`（`bw >= WONDERTAP_RATE_BW_80`）；`target_if_vdev_mgr_tx_ops.c` 按它二选一：wide 走 `WMI_HOST_MODE_11AC_VHT80` + `allow_vht=1`，否则维持 HT20。实测 `chanmode 10 ch_info 0xa center_chan 5775`。**`struct mlme_channel_param` 没有 `allow_he` 字段**（第一次编失败就是它）。注意这只是一半：真让吞吐上去的是下一条 |
| 自建模块 `up` 后 25 ms 就 `Received firmware down indication` | `send_vdev_create_cmd_tlv: type 9, subtype 0` = `WLAN_VDEV_MLME_TYPE_PASSTHRU`，**原厂固件没有这个类型，创建即断言**。类型由 `mlme_get_vdev_types()` 决定，而 `components/mlme/core/src/wlan_mlme_vdev_mgr_interface.c` 原先**不在覆盖层里**，自建模块一直走原厂的 `case QDF_PASSTHRU_MODE: *type = PASSTHRU`。随包那颗是 `type=7`（日志带 `OP13CREATE` 前缀），即 NDI | 把该文件补进覆盖层（第 45 个），加 `module_param(passthru_fwtype, uint, 0444)`，默认 `WLAN_VDEV_MLME_TYPE_NDI`(7)，`QDF_PASSTHRU_MODE` 分支改成 `*type = (uint8_t)passthru_fwtype` |
| `OPAIRSHARERXFILTER: set-submit status=0` 之后再无 reply；`OPAIRSHARERXAUTO: start … rc=-110`；`wonder0` 连试 6 次 `I/O error` 起不来 | **`-110` 是 `wait_event_timeout(opairshare_rx_filter_wait, 2000ms)` 超时，根因是一行映射指错了文件**。平台树里有两份同名 `target_if/p2p/src/target_if_p2p.c`：`qca-wifi-host-cmn/` 下那份**只编 `dp/` 与 `target_if/mlme/`**，而 `wlan_qcacld3_modules.bzl:575` 登记、真正进链接的是 `qcacld-3.0/components/` 那份。`source-map.json` 指到了前者，于是 `opairshare_awdl_rx_filter_reply()` 的 hook 被拷进一个**不参与链接**的文件——模块照编照装照跑，hook 一次没执行，等待队列没人 `wake_up_all`。两份文件除这段 hook 外**逐字节相同**，肉眼比对看不出来。`opairshare_rx_filter_used` 一次就置 1，每个会话只有一发，超时不能靠重试救 | 按 `*.bzl` 的清单改指向（bzl 用相对 `wlan/` 的路径：`qca-wifi-host-cmn/<x>` → `cmn/<x>`，`qcacld-3.0/<x>` → `<x>`），并新增 `kernel/check-map.py` 把这条判据变成可执行检查：期望输出 `.c listed: 22   .c dead: 0`。诊断靠 `target_p2p_mac_rx_filter_event_handler` 里两条 `target_if_fatal`（`OPAIRSHAREEVT` 打 `vdev/status/len`），因为 `target_if_debug/err` 的默认级别会被过滤，当不了判据 |
| 自建模块 insmod 带的 `passthru_fwtype/mon_rings/mon_chan_sync/tx_audit` 全部返回 0，行为却不对 | **随包那颗和上游是两条命名血缘，不是「少打几个补丁」**。随包有 16 个 `OP13*` 日志标签（`OP13CREATE/OP13TXQUEUE/OP13MGMTCOMP…`），覆盖层里一个都没有；6 个参数在上游是**被改名重写**掉的：`passthru_tx_audit → passthru_awdl_rx_audit`、`passthru_mon_* → passthru_rx_diag_budget / passthru_flow_trace_budget`，`mon_rings / mon_refresh / mon_chan_sync / wmi_mgmt` 无对应物。**未知参数内核不会拒绝**（insmod 仍 0，只在 dmesg 留一句），于是自建模块是带着默认值跑起来的 | 认随包：`awdl-stack.sh` 在 `$STATE/wide` 存在时传 `passthru_ht=0`（见下条），模块本身用 `kernel/qca_cld3_peach_v2.ko.gz`。补参数补不上——RX 实现本身就不同（`wlan_hdd_opairshare_aux_rx.h` 里没有 `mon_rings` 那一套，只有 `aux_monitor / sta_*_rx / pas_discovery_actions`），那是移植不是调参 |
| 自建模块「建得起来、传不了数据」：同配置 20MHz 下 `rx=0p/0B bad=13128`，随包是 `rx=15p/4304B bad=1` | 上两条的合并后果：自建那颗既缺 NDI 后端的参数语义，RX 又是另一条血脉的实现。`opairsharerx0` 在网卡层有计数，host 侧全是解不出的垃圾 | **任何用自建模块做的速率测量都没有意义**——早先那次 443 kB/s 是随包模块跑出来的。吞吐数字一律标模块 sha256，`kernel/README.md` 里那颗随包字节是 `152b93ad…` |
| 「80MHz 已被固件拒绝」这个结论（曾据 3 组 A/B 写下） | **归因错了，别再引用**。崩点固定在 `OPAIRSHAREVHT:` 打印后 **65 ms**，而那条日志来自 `wmi_vdev_param_nss` + `wmi_vdev_param_ldpc` 两次 VDEV_CMD —— 触发 RAMDUMP 的是这两个命令，不是 chandef；而当时模块是从**工作区**编的（压着前几轮全部残留），candidate 与生效版 `0007` 也混着用 | 完整的 A/B 要先把「崩因」与「基线」分开：以 `git show HEAD:` 为基线重编（§6.5），`passthru_vht80=1` 会让链路起不来（它命中 init 阶段那个「跳过信道切换」的早退，之后 `Vendor init failed: -95`），所以另立 `passthru_chan_11ac` 只改 channel-change 的 dot11 模式、init 与 START 一律不碰；11ac **只许用于运行时 retune**（`start_ap` 传 `runtime_retune=false`） |
| 想开 80MHz，一路改了 phy_mode / nss / ldpc / BA 窗口，全在跟固件较劲 | **随包模块自己就能走到 80MHz，不需要改任何驱动。** `passthru_ht=1` → `chanmode 5 (11NG_HT20)`，中心频率被丢；`passthru_ht=0` → `chanmode 26 (11BE_EHT80)` + `center_chan 5775` + `Tx SS 2` + `ch_info 0x1a` + `bad=0`。随包那段用 `passthru_ht` 决定 dot11 模式的代码等价于覆盖层的 `diag&1` 分支，但它的 0 分支走 11be 而**不是**拒绝宽信道——这就是「我们改了半天不如人家一个默认值」的全部原因 | `awdl-stack.sh`：`$STATE/wide` 存在时 insmod 传 `passthru_ht=0` + `passthru_chan_11ac=1`（后者随包不认，静默忽略，留给将来自建模块）；接口必须**全新创建**，`feed` 之后、首次 `up` **之前**调 `wonder-ven <if> freq 5745 2`——wonder.ko 只在 tap 未激活时缓存 `SET_FREQUENCY`，任何 `down→up` 都会把 IBSS join 弄丢（`wonder0` 恒 `NO-CARRIER`，20MHz 也一样），所以**不能「先起 20 再 retune」**。实测 2 文件 112 MB：`tx=95580p/126784269B`、`bad=0`、约 **1.28 MB/s**（HT20 1SS 基线 443 kB/s 的 2.9×），包均 1326 B 说明还没吃满聚合 |
| 从仓库镜像重编，`modpost` 报 `"opairshare_passthru_monitor_active" undefined` | `wlan_hdd_wondertap.c` **只用不定义**，`kernel/source/` 与 WSL 全树都搜不到定义，git 历史里也从来只有声明。原厂 `.ko` 里它的函数体（`0xaf90`）是 `adrp x8,0xa000; ldrb w0,[x8]; ret`——读一个 `.bss` 字节标志，而树里没有任何代码写它，运行时语义就是「永不活跃」 | 在 `wlan_hdd_wondertap.c` 里补同名 out-of-line 定义 `return false;`，保持模块符号表与原厂一致。**以后再从 HEAD 重编这个定义必须留着**，否则每次都在 modpost 挂 |
| 崩溃重启后 `su` 不见了 | 每次 WLAN 挂都会把 KernelSU 弄挂（`ksud` 不在），不是刷机坏了 | KernelSU 约 2 分钟自己完成 late-load，`su` 会回来，不必重刷。同理每次挂都会落一份 `/data/vendor/ramdump/wlan_driver_<时间>.elf`，**里面是主机侧内核日志的环形缓冲**（`grep -ao "\[ip\]\[0x…\]\[…\]peach_v2: …"` 能整段还原，按时间戳排序去重），这是**没有 root 也能看的现场**——别再靠 `dmesg -w` 撞运气 |

### 6.2 协议与编码

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| bplist 整数往返变形 | 写侧对 `0x80..0xff` 用 1 字节，读侧 `sign_extend` 读成 −128 | 「≤8 字节一律无符号读」，测试锁死 |
| 每个 cpio 归档以 `Truncated` 结束 | `finish()` 手写 trailer 头，漏了名字 `TRAILER!!!` | 复用 `write_head(TRAILER, 0, 0)` |
| 对端收到「短文件名」 | 头声明 11 字节名却只写 1 个 NUL | 同上 |
| `406` 后连接被当解析错误 | 拒绝时没关连接 | 拒绝即 `Connection: close` |
| 空字典 `/Discover` 被秒断 | 对端要求三个 Sender 字段 | 三个字段必填 |
| 发送端把 `200`+非空 body 当成功 | 空 body 才是成功 | `Client::upload` 两半都查 |
| 「`/Discover` 成功然后永远等待」 | 一条连接只读一个请求 | `Client::renew()` + `Dial` |
| 接收端记成一台新设备 | 每次投送换 `SenderID` | 从证书派生稳定值 |
| 弹窗慢（12 秒以上）+ 文件缩水（93 MB → 26.5 MB），连试五轮都没变化 | **两条独立的事，且第一件被第二件盖住了。** ①**弹窗慢是转码窗口**：`/Discover → /Ask` 实测 **12.71 s**，`/Ask → /Upload` 只要 2.72 s。这段空转不发生在我们的链路上（我们的 5353 与握手都在毫秒级），是 iOS 在**构造 `/Ask` 之前**就把视频重编码完了。②**缩水与我们的能力声明无关**：落盘的 `/Ask` 原文（`.sdcard/Download/.ask-last.bin`，55 510 B）里 `FileSize = 27822213` 已经是转码后的尺寸、`ConvertMediaFormats = false`——它**早就决定了**要转，`/Discover` 的 `ReceiverMediaCapabilities` 确实被读到了，但那套字段**无法表达对「已经选定要产出的容器」的反对** | 缩水这条**当前无解**，写清楚比继续试值更值钱。弹窗慢这条已定位到「不是我们的延迟」，不必再优化握手。**能做的只有一件事：让能力声明真的送到对端**（见下条） |
| 上一条的「五轮 A/B 全失效」：换了五种 `ReceiverMediaCapabilities`，iPhone 行为一点没变 | **设置行把代码常量整个遮蔽了。** `receive.media_capabilities` 这行**非空时会覆盖**内置值（`crates/ffi/src/settings.rs:263` 的 `trimmed` 语义：`Some(entered) => entered`，`None => MEDIA_CAPABILITIES`）。设备上连着几轮塞的是上一轮从真机抄来的 1295 B 原文，而那份原文的 `ContainerFormats` 只有 `public.heif-standard`、**没有 `public.mpeg-4`**——我们自己那份 347 B 的常量（含 `public.mpeg-4` + `hvc1`）**从来没有到过 iPhone 手上**。于是五轮「改常量」的实验改的是一个不生效的变量 | 已在设备上清空该行，真正走 `opairdrop_airdrop::MEDIA_CAPABILITIES`（断言在 `lib.rs:321` 起，锁 `Version:3` / `hvc1` / `public.mpeg-4` / `VTMaxDecodeLevel` / `VTIsHardwareAccelerated` / `[1,2,4]` 六项）。教训：**任何「改了没反应」的调试，第一步先确认改的那份东西在运行时真的被读到**——这一条与 §7「症状是先验证探针」是同一类错误 |

### 6.3 速度类

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| `p5` 恒为 0 | 非 HT PPDU 不许 >2346 B 的 MPDU | join 带 `HT20` |
| 聚合数上不去（3.6 msdu/agg） | `collect_us=0`，定时器形同不存在 | `collect_us=1500` |
| 速率掉到 0.93 且 `ssthresh` 崩 | `tid` 轮转让 iPhone 多队列乱序 | `tid=-1` |
| 重传与丢包 1:1、速率不变 | 链路层重传在同一个时隙里重发 | `retry=N` |
| 照抄 3.2 的清单只有 2.25 | `capacity=32` 把 cwnd 锁在 34 | `capacity=256/lowmark=128` |
| 一直是单流 | 脚本从没设 `nss`，默认 1 | `nss=2` |

### 6.4 Android 运行时

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
| 窗口过期后接收端不停 | 「线程在跑」被当成「该收」，窗口自我续命 | 手动开关另立凭据（SharedPreferences），判断看凭据不看线程 |
| 发送页一打开就闪退 | `setBackgroundBlurRadius` 在 `setContentView` 之前调用，平台直接对**惰性创建**的 `DecorView` 下手 → NPE | 放到 `setContentView` 之后（`applyGlass()`） |
| 发送页窗口有 surface 却一片空白 | `onPreDraw` 里 `return false` 取消首帧，动画的 invalidate 被一起吞掉 | `INVISIBLE` + `post`（窗口本就透明，先不可见一帧看不出） |
| 拖动面板时**字在抖**、整个窗口上下摆 | ① `setTranslationY` 让整棵子树重绘（全屏圆角描边 + 全部文字每帧重栅格化）；② 层里的雷达每 32 ms invalidate 逼整块纹理重画；③ 上一次回弹动画还在跑，和手指抢着写 `translationY`；④ 位移取整制造 1 像素台阶 | 移动期 `setLayerType(HARDWARE)`、拖动时停雷达呼吸、`ACTION_DOWN` 里 `animate().cancel()`、位移保留小数 |
| 面板里的模糊要么盖住整屏，要么一动就卡 | 用了 `Window.setBackgroundBlurRadius`——**整窗**模糊，成本在合成线程 | 改成 View 级：`BackgroundBlurDrawable` 设成面板 background |
| `gfxinfo` 报 0 janky、5 ms，用户却说卡 | 跨窗口模糊的开销在 SurfaceFlinger 合成，`gfxinfo` 只统计应用线程 | 判据换人：这类问题看合成帧率（`dumpsys SurfaceFlinger --latency`）或直接上手 |
| AirDrop 关再开之后，对端列表**再也不动**（要退出页面重进） | `awdld` 重启会把 `awdltun0` 拆了重建、**ifindex 变了**，而 `MulticastSocket` 还绑在旧的那块上：`send` 抛异常被「一次发不出去就等下一轮」吞掉、`receive` 一路 `SocketTimeoutException`——**一个字的日志都没有**。浏览线程活着，只是聋了 | `Peers.rebind()`：每秒核一次网卡 index，变了或连续 3 次发不出去就 `close()`+`open()` 搬过去，不清空表（避免列表闪烁）。搬与等分两档：绑在 `awdltun0` 上就只认它，它没了就原地等（搬到 `wonder0` 只会收获一屏 `ENETUNREACH`）；绑在别处时才贪心，见到 `awdltun0` 就搬。实测 kill `awdld` → 8 秒内无任何搬动；重启后 **1.3 s** 搬回（`172 → 173`）并 0.5 s 内重新找到对端 |
| **冷启动**（桌面无后台）从相册分享第一次打开发送面板，对端一直不出现；下滑关掉重开，**立刻**正常 | 5353 上同一个进程里有**两个** socket：`Peers` 先绑、`Responder`（接收端）后绑，间隔 28 ms。`SO_REUSEADDR` 下**组播**报文会分发给每个 join 过组的 socket，**单播**只交给其中一个（Linux 上是最后绑的那个）。而 `browse.rs` 的查询带 **QU 位**，iPhone 的应答全是单播 → 一条条都进了 `Responder`，浏览这一路一个字节也收不到。硬证据：`ip -s link show awdltun0` 上 TX 从 1547 涨到 1558、RX 冻在 `315778 bytes / 1464 packets`；`ss -u -a -p` 显示同 pid 两个 `*:mdns`（`socket:[33083720]`、`socket:[33088788]`），`/proc/net/udp6` 里 5353（`0x14E9`）共 7 个 socket（我们 2 个，`mdnsd` 5 个） | `Peers` 加一条自愈判据：**不试图指定谁先绑**（那要看两条线程谁先跑完 bind，而且 AirDrop 开关一次又会重来），改成「听不见就把自己的 socket 重开一遍」——重开的那个一定是最新的，单播于是回到我们这边。判据是 `foreign`（自 open 起读到过几条**别人的**地址记录）而不是 `heard`：自己的宣告会绕回来、旁边别的组播也会让「收到过包」为真，要问的是「别人说的话进来了没有」。`foreign == 0` 且已问 ≥ 4 次（4×300 ms ≈ 1.2 s）就 `reopen()`，间隔 5 s 起翻倍、封顶 60 s，听见一次就退回 5 s（**「翻倍」这一半到 2026-11-19 18:52 才真的生效，之前被复位逻辑吃掉了；空表时另走 60 秒一档——见 §5.8 末条**）。实测：冷启动 **1.2 s 判定、重开后 0.5 s 内 ready**（1690/1897/2260 ms 三连测，与「关掉重开」那条路的 537 ms 同量级） |
| 设备先以「**附近设备**」出现，随即被真名替换 | 两个事实同时发生：格子一凑齐「地址+端口」就画（`onPeers` → `renderStage`），而真名只能连 TLS 问 `/Discover` 才知道。画的那一刻名字还没有，落到占位 `kind()`；而 iPhone 这几轮报上来的 TXT 里**没有 `model`**，`kind()` 于是退到最坏的那种占位——「附近设备」（它连「这是台 iPhone」都没说出来）。换名字时宽高都可能变，看着像列表在抖 | 把「画格子」推迟到名字确定之后：`SendActivity.settle()` 只把**已知真名**或**等满 `NAME_HOLD_MS = 1500`** 的设备放进上屏列表（1.5 秒是量出来的位置：真机 `ready → probed name` 是 71/195/200/600/718 ms）。等的时候也不能让设备隐形，所以两个刷新口必须都堵上：① 真名到了 **立刻** `settle()`（不能等下一个 mDNS 报文——iPhone 重复答同一个地址时 `absorb()` 只 `touch()` 不置 `changed`，`dirty` 不置位，`publish()` 根本不发生）；② 窗口到期由 `armSettle()` 排一次性 `postDelayed` 兜住「没人再发包」。实测（清掉真名缓存、1.5 秒窗口内探测成功）：`ready` 后 0.6 s 的帧仍是空态「正在搜索附近设备……」，1.7 s 的帧已是**头像 + 真名**，中间**没有任何一帧**写着「附近设备」；再人为 `ip6tables -I OUTPUT -p tcp --dport 8770 -j DROP` 让探测必失败，1.5 秒到期后设备照型号出现（这一轮落到「附近设备」，因为 iPhone 的 TXT 里没有 `model`）——这条兜底是「问了它不答」唯一的出路 |
| 发送界面**刚打开能看到 iPhone，几秒后它自己消失**；重开页面又出现，再等几秒又消失（iPhone 一直开着「所有人」） | 两件事叠在一起。①**阈值用的是死数**：`Peers.timeout()` 只看 `CONNECTABLE_MS = 2200`，而 `Peer` 里明明量着节奏却一眼没用。实测 iPhone 播报间隔 464–1638 ms，丢一包就是 2.2–3.3 秒静默，于是**一次丢包就够删掉一台活着的设备**。②**换实例名的空窗**：iPhone 的 mDNS 实例名每分钟换一次（Apple 的隐私设计），换的时候先发新实例的 SRV、再撤旧的。而「能连」要求地址+端口都在，**地址挂在主机名上、要等另一条 addr 记录**——于是表里同时躺着「一个正在沉默的旧实例」和「一个只有端口没有地址的新实例」，旧的被 `expire` 判死删掉、新的还上不了列表，`ready() == 0`，屏幕空掉。两件事在日志里是挨着的两行：`dropped … after 2727 ms (threshold 2608, gaps 29, max 1054)` + `nothing connectable yet (1 instance(s) with partial records)` | ①阈值改成**下限 + 实测**：`max(2200, 2 × gapMax + 800)`，`gaps < 3` 时不用实测（样本太少）。用 `gapMax` 不用 `period`——平均值回答「通常多快」，最大值才回答「最慢能拖多久」，而丢包只跟后者有关。余量 500→800 是因为那次 2727 只超了 119 ms（同一个 22 秒窗口里 19 个间隔平均 1.16 秒，它一直在说话，最后**连着静了近三包**）。②`Peers.inherit()`：新实例的 host 与旧实例相同时，**直接把地址和指纹接过来**再删旧实例——SRV 的主机名属于机器不属于实例，这不是猜是读出来的结论。接完立刻删而不是等 `retire` 那一圈，否则表里会有最多 250 ms 两个同地址实例，而界面按实例名去重，那 250 ms 屏上是两格一模一样的设备。日志新增两行可验：`inherited <addr> for <新> from <旧> — same host…`、`dropped … (threshold …, gaps …, max …)`。**2026-11-19 16:40 补齐**：①下限后来抬到 **6 000**（`max(6000, 2 × gapMax + 800)`），因为 11-19 两度出现设备稳定说了半分钟以上、然后突然静 6.2 秒的情况，而 `2727/2608`、`4134/4080`、`6218/6000`、`6223/6000` 全落在 `[阈值, 阈值+250]` 里——`expire()` 每 250 ms 检查一次，这个宽度只证明粒度，**不能读成「余量差一点」**；②`SEND` 侧那个「怎么修都还闪」的真正原因一半在别处：本机 Responder 自己的实例被自己的组播绕回来建成 peer，地址被 `own.contains()` 挡住 → 永远 partial、永远不过期 → 常驻 `nothing connectable yet (1 instance(s) with partial records)`，把「对面已走」这件事盖住。已改成认出「地址是自己的」就按 host 整个收掉 |
| 发送**成功后**卡片上的【取消发送】还在，按了没反应，连按好几次 | 同一条通知记录上换了「按钮行」：进度卡挂【取消发送】，终态卡挂【完成】/【重试】，而 `notify(id, …)` 不保证重建那颗 `PendingIntent` 所在的动作区，系统可能只把字换了。屏上留着的前一帧于是有一颗**按得到、但指向已死会话**的按钮。硬证据：`23:22:32.943` 已投「发送完成」，之后 65 秒应用侧零条卡片日志，`23:23:37.957` 起 5 秒内 6 次 `no send is listening for 2f391a32-…` | 两道闸。**治成因**：`LiveCard.post()` 把「挂哪些按钮 + 是否终态」拼成形状串，形状与上次不同就 `manager.cancel(ID)` 再 `notify`（形状变了才撤，进度 200 ms 一次不做无谓 binder 往返；邀请卡不走 `post()`，自己补记 `lastShape = "invite"`）。**治症状**：`SendHost.cancel()` 发现「停不下」时 `settleCard()` 撤掉重投一张终态卡，**不抬头**。边界：`LiveCard.ID` 全机一张、接收侧同用，所以只认 `session.equals(settledSession)`，绝不因陌生 session 撤卡（那会撤掉一次正在进行的接收） |

### 6.5 构建与工具链

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| `gradlew` 在 Git Bash 下 classpath 异常 | MSYS 路径转换 | 直接用 `tools/gradle-9.6.0/bin/gradle`，并 `export MSYS_NO_PATHCONV=1` |
| `cargo` 找不到 | PATH 未含 `~/.cargo/bin` | 每个会话 `export PATH="$HOME/.cargo/bin:$PATH"` |
| `cargo test` 不产 `cdylib` | 测试只建 rlib | 先 `cargo build -p opairdrop-ffi` 再 `loadLibrary` |
| 加 androidx 后连 `:app:dependencies` 都失败 | 缺 `android.useAndroidX=true` | 写进 `gradle.properties`（依赖是 `compileOnly`，不进 APK） |
| APK 体积虚高约 640 KB | 增量打包残留的空对齐条目 | `gradle clean assembleDebug` 后才是真值 |
| `adb pull /sdcard/x .` 路径错乱 | 远端路径也被转换 | 远端保留 `/sdcard/...`，本地用相对路径 |
| 预览脚本 `round_poly()` 抛 `TypeError` | 元组少一层括号，只 append 了最后一个 float | 只影响离线预览，坐标已手抄进 Java |
| 补丁从 `kernel_platform` 根打，`git apply --check` 报 OK、退出 0，**编译却找不到 `CONFIG_WONDER_SUPPORT`** | **`kernel_platform/vendor` 是符号链接**（→ `../vendor`）。`git apply --unsafe-paths` 走软链时静默不落盘：`--check` 通过、退出码 0、`vendor/` 下一个字节都没改，只有 `wlan_qcacld3_modules.bzl` 这类非 vendor 路径生效。于是 Kconfig 里没有那两个宏，DdkConfig 直接失败 | 按补丁各自的应用根分开打：`0002` 从 `$TREE`（它带 `kernel_platform/` 前缀）；`0004` 从 `$TREE/kernel_platform/msm-kernel`；其余从 `$TREE`。**「退出 0」不是判据**，判据是落点文件里有没有那行——`apply-source.sh` 因此内置四个 marker（`wma_opairshare_pas_mgmt.h` 的 include、`target_if_p2p.c` 的 `OPAIRSHAREEVT`、oplus variant 的 `"sun"`、`qdf_nbuf.c` 的函数定义），任一没 mark 就非零退出 |
| `0005/0006/0007` 三个补丁全 `patch does not apply` / `already exists in working directory` | **执行顺序错了**：先铺 overlay（那是**最终全文**）再打补丁（上下文行是**上游原文**）。反过来必然冲突，而且报错看起来像「补丁坏了」 | 顺序固定成**先补丁、后覆盖层**。`apply-source.sh` 里两步之间的注释写明理由，别再调换 |
| `0001` 打了之后 `0003` 失败，或反之 | **两者互斥**：`0003` 已含 `0001` 的 hunk（Kconfig / config_to_feature / QDF 枚举） | 检测到 `0003` 就跳过 `0001`；若工作区已有 `0001`，先 `0001 -R` 再打 `0003` |
| 补丁逐个 `git apply --check` 全部试过，干净树上仍报 `0007 FAIL: wlan_hdd_wondertap.h: No such file or directory` | **补丁之间不独立**：`0006` **创建** `wlan_hdd_wondertap.c/.h`，`0007` 再编辑它们。`--check` 不落盘，前序补丁的产物永远不存在——是假警报 | `apply-source.sh --check` 改成「导出 pristine 到临时副本 → **真跑一遍** → 删掉」，真实树不 touch（`trap` 里清理）。判据 `applied=9 failed=0` + 四个 marker 全 mark |
| `0002` 报 `kernel_platform/msm-kernel/build_with_bazel.py: No such file or directory` | `kernel_platform/msm-kernel` 是**独立 git 仓库**，`git archive` 不能跨仓，且它产出的路径**相对自身根** | 单独 `git -C $TREE/kernel_platform/msm-kernel archive HEAD <文件列表>`，解压到 `kernel_platform/msm-kernel/` **子目录**，不能解到树根 |
| 覆盖层铺完，`overlay file missing: .../source/driver/driver/target_if_psoc_timer_tx_ops.c` | `source-map.json` 的 key 是 `kernel/driver/x.c`，剥掉 `kernel/` 得 `driver/x.c`——**已经**相对 `source/` 了。脚本又拼了一层 `source/driver/`，变成双 `driver/`。这个 bug 从脚本写出来到冷验证**一次都没被触发过**（overlay 一步从未成功） | `origin = os.path.join(src, rel)`，不再拼 `driver/`。教训：**没有端到端验证的脚本等于没写**——退出码第一次变成非零才暴露 |
| 补丁在 `qdf_nbuf.h` 声明 `qdf_nbuf_get_radiotap_len()`、`dp_tx.c` 调三次，**链接期**才报未定义 | 函数体只存在于**废弃的** `0007-session-dp-candidate.patch` 里，生效版 `0007-session-dp-and-hardware-guards` 只有声明。`attribute.py` 因匹配到 candidate 而误判「已覆盖」 | 补成独立 `0010-qdf-nbuf-radiotap-len.patch`，**不动 `0007` 一个字节**（随包那颗模块是照 0007 编的）。两个 0007 并存时脚本自动跳过 candidate（它只用于 diff） |
| 不可读的 `settings.conf` 让整个应用瘫痪（首页红字「异常」、三页全废） | ①`su -c chown u0_a329` 用的是**当时**的 uid，重装后变成 `u0_a456`，`chmod 600` + 旧属主 = 应用自己 `open()` 拿到 `EACCES`；②真正的设计缺陷是「一个文件读不出来 = 接收功能不存在」，`Listener.serve()` 直接拒连接、`SettingsScreen.open` 抛异常 | ①**绝不要手写 `chown u0_aNNN`**：写私有目录只用 `run-as`（属主自动正确），且必须走 stdin（`run-as` 是 app 身份、没有 `sdcard_r`，读不了 `/sdcard`）。②`SettingsScreen.defaults(Path)` 只读 catalog 全走默认值；`serve()` 读不出来时 `Log.i` 一句、`setState("设置读不出来，正在用默认值接收", false)` 并**继续接收**；`open()` 改为 `Files.move` 到 `settings.conf.unreadable` 再按首次启动读一遍（**改名只要目录写权限，不要文件读权限**，这正是它在 `EACCES` 下走得通的原因）。③`recoveredFrom()` **必须看磁盘** `Files.exists(aside(file))`，不能记字段——这个类启动过程中会被读很多次，第一版那个字段被 `Tuning.read()` 先读一遍就用掉了 |
| 测试写了、`check.sh` 还是一条命令之后打印 `ok` | **`SettingsCheck` 数着 `failures`、打印 `FAIL`，但 `main` 从来不读，退出码永远 0。** 另三个 `*Check` 都有 `System.exit(1)`，只有它漏了——失败断言在日志里只是一个 `FAIL` 字样 | 补 `if (!failures.isEmpty()) { … System.exit(1); }`。**加断言时顺手确认新类也会红**：`grep -L "System.exit" crates/ffi/jvm/com/oshare/airdrop/*Check.java` 应为空。同理造不可读文件要用 `Files.createDirectories(...)` 拿目录冒充，**别用 `chmod 000`**——这个检查在 Windows 上也跑，`setPosixFilePermissions` 直接 `UnsupportedOperationException` |
| 文档里的「`check.sh` 355 / 375 / 381 条」三个数**没有一个是真实条数**（真实是 **394**） | **`RoundTrip` 曾经是唯一不打印 `ok, N checks` 的一段**——成功时它只印 `all checks passed`，失败时才印数字。于是「把几段的汇总行相加」这个算法天生漏掉它：`185+54+48+69 = 356`，`grep -c '^  ok  '` 得 394，差额正是 round-trip 那 **38** 条。375 与 381 还分别对应过更早的段落组成，但同一个根子：**用汇总行相加代替数行**。同一个毛病也发生在 `cargo test` 上（文档写过 420 / 427，实测 **431**） | 判据只有一条，别用汇总行：`bash crates/ffi/jvm/check.sh 2>&1 \| grep -c '^  ok  '`。`cargo test` 则数 `^test result:` 行（18 行）分别相加。**源头已修（2026-11-21 15:04）**：`RoundTrip` 现在也印 `ok, 38 checks`，五段合计 `38 + 185 + 54 + 48 + 69 = 394`，与 `grep -c` 同一个数——**两种数法从此互为校验**，这才是这条该有的终态。它当初没印只是因为它先于那个约定写成，不是有意为之；留一段「数不出来」的检查除了制造错误数字没有别的作用 |

### 6.6 ColorOS 断网（hans / netd，2026-11-24 凌晨钉死）

**现象**：应用退后台（甚至划掉后 hook 保活成功）时，iPhone 点发送迟迟不弹流体云；logcat 里 `Responder.announce()` 反复打 `mDNS announcement failed: sendto failed: EPERM`，每约 3 秒一条。

**排查全链（kretprobe 逐层劈，tid 过滤 `common_pid == <announce 线程>`）**：

| 层 | 机制 | 结论 | 证据 |
| --- | --- | --- | --- |
| LSM / SELinux | `selinux_socket_sendmsg` / `bpf_lsm_socket_sendmsg` / `security_socket_sendmsg` | **排除** | ret=0 |
| cgroup BPF | `__cgroup_bpf_run_filter_sock_addr` | **排除** | ret=0 |
| IPv4 iptables | `iptables` 计数器 | **排除**（早前已证伪） | 无增长 |
| **IPv6 netfilter + BPF** | `ip6tables -t filter` 的 `oplus_fw_OUTPUT/INPUT` 上挂的 `skfilter_egress/ingress_hans` | **元凶** | `nf_hook_slow` ret=-1，`ip6_output`/`ip6_finish_output` 零命中（包死在 LOCAL_OUT 钩子，没到路由） |

**因果闭环（`ip6tables-save -c` 计数器差值法）**：8 秒内该规则 pkts +8，同期新增 EPERM 恰 8 条；摘规则后 EPERM=0、`udpv6_sendmsg` ret=0x140（320 B announce 完整发出）、`nf_hook_slow` ret=1。

**数据源**：`/sys/fs/bpf/map_oplus-netd_app_hans_limit_map`（hash 4B:4B，2000 项）里 key `0x28a9`（=10409=uid）被置 1。egress/ingress 两个 prog **共用这张 map**，删一个 key 双向放行；map 里 vending(10121)/gms(10339) 等条目原样保留，副作用为零。**hans 是 netd 原生实现**（oplus-framework/oplus-services/framework/services 四个 jar grep hans 全 0），Java 层 hook 不可行。

**持久性弱点**：**netd 会在熄屏等时机把 key 重写回来**（前后台切换不触发）；0.5 秒轮询的守护可完全兜住。

**修复**：`tools/hans_guard.sh` → 设备 `/data/adb/service.d/99-opairdrop-hans.sh`（开机自启；0.5 s `bpftool map lookup`，出现 key 才删；pidfile 单实例；先等 map 就绪）。装脚本用 `adb shell "su -c '…'"` 双层引号——单层引号只把第一段给 su，其余以 shell 身份跑。

**验收判据**（两条都得过）：
1. 熄屏 15 s 后 map 里 grep 不到 `a9 28`，且 `ip6tables -t filter -v -L oplus_fw_OUTPUT`（及 INPUT）的 bpf DROP 计数器**零增长**；
2. 端到端：iPhone 点发送 → 流体云卡片 **1 秒内**弹出（实测 834 ms：accept → offer 789 ms → live invite 834 ms）。

**分层结论**：hook system_server 的 `forceStop` 保活解决的是"划掉不杀进程"，仍在生效；断网是更下一层（netd BPF），两层都修好才有秒弹。微信/QQ 等能收通知、一加互传能后台弹卡，都是因为它们的 uid 不在 `app_hans_limit_map` 里（互传另走 OPPO 原生 802.11 桥，不经这条 socket）。

---

## 7. 方法论（最贵的一节，别跳）

**症状**：连续多轮「新结论推翻上一轮结论」，每轮都有数据支撑，合起来净进展为零。

**根因不是数据不足，是把「间接自述计数器」当成独立事实轮流采信。**

### 纪律

1. **结论分级**：不可动摇的事实（单一来源、直接计数）/ 未定论 / 下一步单一实验。
2. **只有「端到端墙钟时间」和「落盘字节 SHA-256」可以当判据**；中间计数器只用于定位。
3. **引用源码解释现场数字前，先确认设备上跑的版本与源码一致**（曾出现模块比仓库旧）。
4. **同一个问题最多采一轮被动数据**；没有可证伪的 A/B 就停手，写进未定论。
5. **一次只动一个变量**。2.25 → 2.59 之所以能归因，就是因为其它 21 个参数逐字节没变。
6. **任何吞吐数字必须带机型与 iOS 版本**。跨机型比较不算回归：iPhone 13 / iOS 27 是
   857 kB/s，Xs Max / iOS 18.7 是 0.109 MB/s，两台设备、两个基线。

### 三条踩过的坑

- **禁止「读全文 → 批量 replace → 整篇写回」改源码或文档。** 这个手法一天连闯三次祸
  （两个 `.rs` 被清空、`Task.md` 被清空）。改代码一律逐处精确替换、改完立刻编译。
- **`dumpsys` 里「还活着」和「曾经有过」挨着**：`Notification List:` 是活的、`mArchive=` 是历史。
  按 `NotificationRecord(` 切段，别按空行切（同一份 dump：旧写法 3165 行/8 条，新写法 81 行/1 条）。
- **坐标要算不要抄**：从 dump 的 `bounds` 求中心。手抄的 `(1022,2722)` 落在按钮上沿外 14 px，
  连点四次全空，日志什么都不报——「点了没反应」和「没点中」在应用侧长得一模一样。

---

## 8. 下一步（每条可验收）

### 8.1 速度线（按 §3.5 的顺序）

| # | 动作 | 验收 | 状态 |
| --- | --- | --- | --- |
| S1 | `capacity=256/lowmark=128` + `nss=2` 固化进 insmod，并加运行时回读 | 重启后 `cat` 参数仍是这组，且实测 ≥3.0 MB/s 稳态 | ✅ 2026-11-18 已固化 + 装机 |
| S2 | `cc=bbr` + `fq`（单变量） | 稳态 ≥3.6 MB/s，或明确否掉 | ✅ 2026-11-20 已测：2.75 → 2.97/3.14（+11%，两轮）。**目标 3.6 未达**（瓶颈不在 TCP，§3.3），但 +11% 零副作用，**已固化进脚本**（§3.3.2） |
| S3 | 削慢启动（`initcwnd` / 连接预热） | 全程 2.98 → ≥3.10，稳态不变 | ⏳ 未测（收益只在上手 2 秒；`bbr` 之后全程 2.98–3.11 已接近稳态 2.97–3.14，可削的空间被吃掉了大半） |
| S4 | 多流并行 | ~~5+ MB/s~~ → 实测两流 3.02 vs 单流 2.75（§3.3.1） | ❌ 2026-11-19 已测：功能成立、不提速 → 2026-11-20 **旋钮已删**（§3.3.1） |
| S5 | 每轮记录机型/iOS | 日志里出现 `peer=… ios=…` | ⏳ 待补 |
| S6 | `awdltun0 → bridge` 排队（26 Mbps 的 29% 缺口） | 稳态 ≥3.6 | ⏳ 只在内核/驱动侧，无用户态旋钮（§3.5 ⑥） |

### 8.2 功能线

| # | 任务 | 状态 |
| --- | --- | --- |
| 1 | 驱动打包：`assets/kernel/` + `su -c insmod` + boot_id 标记 | ✅ `DriverSetup` 按 §4.2 序列 |
| 2 | mDNS 应答器 | ✅ 真机：3 s 节奏、`hlim 255`、四条记录、TXT 三键 |
| 3 | 接收第一档：iPhone 面板里能找到本机 | ✅ 真实 iPhone 结案 |
| 4 | 接收第二档：TLS（`Identity` + `h=` + `SSLServerSocket`） | ✅ `TLSv1.3`/`AES_256_GCM` |
| 5 | 接收第三档：文件真的落到该去的地方 | ✅ `/sdcard/DCIM/AirDrop/`，字节完好 |
| 6 | 三处提示：弹窗 + 通知栏 + 流体云 | ✅ 同一轮 48 MB 里同时验到；剩版式 |
| 7 | 自有发送面板 | ✅ 环状进度随真实字节走 |
| 8 | 发送端接入 `SendActivity` | ✅ 真机 `1006 发送完成` |
| 9 | 证书钉住 | ⏳ 只结一半：对端 TXT 无 `h`，「相符」支跑不到（§5.4） |
| 10 | 设置表逐行接线 | ✅ 15/15。`crates/settings` 的 `CATALOG` 就是设置页的行表（`Native.settingsCatalog()` 吐 TSV），加一行 Rust 侧这边自动出现，没有第二处要改 |
| 11 | **收到文件时胶囊自动展开**（省掉「先点胶囊」那一下） | ⏳ 官方旋钮 `requestShowPanel` 已查实（§2.2），但需 UPK 工程；三条替代路待试：属性白名单 → 通知补 extra → LSPosed 钩 `SeedlingDataModel.v()` |

### 8.3 内核线（只在真要动 149 / 80 MHz 时才需要）

构建链是**六步**，不是一条命令（入口都在 `tools/kernel/`，没有单独的 BUILD 文档——曾经指向 `docs/BUILD.md` §7，那个目录至今不存在，别再去搜）：

| 步 | 入口 | 产物 |
| --- | --- | --- |
| 1 | `repo` 同步 + `source-lock.xml` 校验 | 三个 commit 全对（见下） |
| 2 | `tools/kernel/build-baseline.sh` | 只编**内核**：`sun_perf_dist` / `sun16k_perf_dist` / `sun_perf_dtc_dist` 三个目标，含同次构建的 `Module.symvers` |
| 3 | `tools/kernel/build-wlan-baseline.sh` | **四个 `.ko` 出自这里**，落在 `$ROOT/baseline-wlan/`——基线那步里根本没有 WLAN |
| 4 | `tools/kernel/build-wlan-feature-check.sh` | 开 `CONFIG_DRIVER_PASSTHRU_MODE` / `CONFIG_WONDER_SUPPORT` 试编，`trap EXIT` 恢复原 defconfig |
| 5 | `tools/kernel/build-route-b-provider.sh` + 打补丁仓覆盖层 | `opairshare_wonder_provider`（route B 自有模块）+ 打过补丁的 `qca_cld3_peach_v2.ko` / `wonder.ko` |
| 6 | `module_abi.py` / `module_exports.py` | 与设备原生 `.ko` 对 `__versions` CRC |

`tools/kernel/` 上游是**七个**文件（另有 `sync-source.sh`、`module_exports.py`），不是两个。
三个 commit 钉死在脚本里，`source-lock.xml` 逐字符一致：`e1b346b6`（`kernel_platform/common`）、
`6028f47f`（`msm-kernel`）、`d50b305f`（`.`，即 `vendor/` 那份独立部分克隆的 HEAD）。

**三个「上游能跑、照搬跑不了」的地方**：

1. **`kernel_platform/vendor` 软链不存在**。第 3、4 步的 bazel label 写的是 `//vendor/qcom/opensource/wlan/qcacld-3.0:…`，
   而 Kleaf 只认 workspace 内的包（workspace 根是 `kernel_platform`）。`repo sync -l` 不会给你这个软链。
   最窄的补法是只桥接真正需要的子树、不动源码不动脚本、可逆：

   ```bash
   mkdir -p $ROOT/opairshare/kernel_platform/vendor
   ln -s ../../vendor/qcom $ROOT/opairshare/kernel_platform/vendor/qcom
   ```

   `vendor/qcom` 一处就够（`qcacld` 往外引的 label 前缀没越过 `vendor/qcom`），`//soc-repo:*` 那一串是虚惊。
   这也说明 `vendor/` 是一份独立部分克隆、不受 `repo` 管，但仍被 commit 校验覆盖。
2. **`findmnt --mountpoint` 只认真挂载点**，不是「有个 ext4 分区就行」。
3. **`build-route-b-provider.sh` 指向的 `//opairshare-route-b-provider:…` 两边都不存在**——那个包要我们自己写
   （`wonder` 驱动源码不在本仓库），所以这个脚本暂时一定跑不了，与软链无关。

**现状**：4K 侧 `vmlinux` / `Image` 已落盘（327 142 168 B），混合树合并通过，`Module.symvers` 9 355 行；
16K 与 dtc 两个目标还没落地。**唯一该先做的动作是「不改任何源码，先把基线跑通一遍」**——
但验收点要挪到**第 3 步**（四个 `.ko` 与 `kernel/` 里现成的对 ABI），
不是第 2 步。在基线绿之前改任何一行源码都是赌，因为此前没有一次成功的编译可以当对照。

两个读数陷阱：

- `module_abi.py check` 报 `INCOMPLETE`（matched 484 / missing 104）**不是失败**：那 104 条全是 `cnss_*` /
  `cld80211_*` 这类兄弟模块导出的符号，内核 `Module.symvers` 里本来就没有（实测 `grep -c cnss_` = 0）。
  真判据是 `.ko` 对 `.ko` 的 `__versions` 直比，588 条全中、`undefined` 各 587 条完全一致。
- `--config=stamp` 会把 git describe 塞进 localversion，自建 vermagic 是 `6.6.118-android15-8-o-g6028f47fadda-4k`，
  与原厂 `…-o-4k` 不同——**不冲突**，`MODVERSIONS=y` 时 `same_magic()` 只比
  ` SMP preempt mod_unload modversions aarch64` 那段。

`local-inputs/` 不是编译前置：原厂 `.ko` 从设备 `/vendor/lib/modules/` 拉、`Module.symvers` 自己会产出，
两样只影响第 6 步的校验。

#### 8.3.1 「能不能复现」到 2026-11-21 已经有判据了（`112/114` 逐字节）

在这之前，`kernel/README.md` 那句「没走到的一步：覆盖层 + patches 全量应用之后重编」
是所有自建模块翻车的总背景——**源码镜像复现不出设备上真在跑的那颗 `.ko`**，于是任何
「改了驱动再测速率」的结论都建立在一颗血缘不同的模块上（§6.1 三条）。现在判据不是
「编出来能跑」，而是比它更硬的一条：

```text
pristine git HEAD  +  overlay(46 个文件)  +  9 个补丁  ==  实际构建树        逐字节
total 114  same 112  diff 0  missing 0  expected-absent 2
```

一条命令给出（`tools/kernel/verify-reproduce.sh`，可执行、无参数）：

```bash
cd repo-src && OPAIRSHARE_BUILD_ROOT=/mnt/opairshare-build bash tools/kernel/verify-reproduce.sh
```

它做三件事，顺序不能换：

| 步 | 动作 | 关键点 |
| --- | --- | --- |
| 1 | `git archive HEAD` 导出干净树到 `$ROOT/verify-clean` | 根仓库只覆盖 `vendor/` + `kernel_platform/oplus/`；`kernel_platform/msm-kernel` 要**单独** archive 到同名子目录（跨仓、路径相对自身根，§6.5） |
| 2 | 调**已发布的** `apply-source.sh` | 不重写一份逻辑。该脚本只认 `$ROOT/opairshare`，所以用 `$ROOT/.verify-root/opairshare` 符号链接把 scratch tree 递进去 |
| 3 | 按 `source/expected-files.txt` 逐个 sha256 比对 | 114 行，已进仓库（原清单在 `.workbuddy/tmp/`，被 `.gitignore` 忽略，第三方永远拿不到） |

`expected-absent` 不是「允许缺失」，是「**白纸黑字承认不复现，而且要核 sha256**」——
清单在 `source/expected-absent.txt`，两条都含 sha256 和一句理由：

```text
vendor/.../qca-wifi-host-cmn/target_if/p2p/src/target_if_p2p.c  b78fcbd0…  not-in-bzl-so-never-compiled; the -110 root cause
vendor/.../qcacld-3.0/core/hdd/inc/wonder/wondertap.h           42832fe1…  duplicate-of-wonder/include/wonder/wondertap.h
```

比对段只有「真实树那份仍与记录的 sha256 吻合」才豁免（`excused`），货变了就落进
`bad_excuse` 而非静默放过——**否则这份白名单会自己漂走**。

配套的三道检查，按顺序跑：

```bash
# 1. 映射对不对（不看目录名像不像，只认 *.bzl 清单）
python3 kernel/check-map.py      # .c listed: 22  .c dead: 0
# 2. 补丁在自己那份干净树上能不能全打上（真跑一遍再删，不是 git apply --check）
bash tools/kernel/apply-source.sh --check       # applied=9 failed=0 / CHECK_OK
# 3. 逐字节
bash tools/kernel/verify-reproduce.sh           # total 114  same 112  diff 0 …
```

**为什么 `--check` 不能用 `git apply --check`**：`0006` 创建 `wlan_hdd_wondertap.c/.h`、
`0007` 再改它们，dry-run 看不到前序补丁的产物，在干净树上必报 `0007` 失败——假警报。
`--check` 现在是「导出 pristine 到 `$ROOT/.apply-check` → 真跑一遍 → `trap EXIT` 删掉」，
跑完 `ls -d $ROOT/.apply-check*` 应为空、真实树改动数不变。

**这份源码的用法**（第三方视角，也写进了 `kernel/source/README.md`）：
clone 我们仓库 → 自己 `repo sync` 一份官方 OnePlusOSS 源码（三个 commit 钉在脚本里：
`e1b346b6` / `6028f47f` / `d50b305f`）→ 两步作用上去：

1. **先打补丁**（`source/patches/0001`–`0010`，应用根各不相同，见 §6.5）
2. **再铺覆盖层**（`source-map.json` 46 条 + `wonder/`、`provider/` 整目录）

顺序反了必然冲突。真值判据只有一条：和 `expected-files.txt` 里的字节对上。

### 8.4 射频共享线（2026-11-19 取证，结论已定，**暂停执行**）

> 触发：用户报「开启【WiFi 共享射频】后，AP 不在 149 就无法与 iPhone 收发」。
> 已查到底、未动一行代码。**恢复这一节时从「动作」表第一行往下走，不要重做取证。**

**根因（一句话）**：AWDL 的 tap 被钉死在 149，而 iPhone 的 AWDL **跟随它自己关联的 AP 的信道**
（未关联时才在 social channel 6/44/149 之间跳）。两边不同频，碰不上，也就谈不上传。

**取证（2026-11-19 22:00，`su -c 'iw dev'` / `su -c 'iw phy'`，全文存 `.workbuddy/tmp/iwphy.txt`）**

| 事实 | 读数 |
| --- | --- |
| `wonder0` 是独立 wiphy | `phy#46`，IBSS，`channel 149 (5745)`，MAC `e2:ed:53:1e:1b:ef` |
| `wondertap0` 与它同一颗 MAC | `phy#45`，managed，MAC `e2:ed:53:1e:1b:ef` |
| STA 在另一颗 MAC | `wlan0` `phy#45`，`c2:b4:00:79:c6:02` |
| `Wiphy wonder` 的 5G 信道表 | 只列 UNII-1（36/40/44/48）+ UNII-3（149/153/157/161），**无 DFS**；2.4G 只有 ch6 |
| `Wiphy phy45` 的并发声明 | `#{ managed } <= 2, total <= 2, #channels <= 2`（两个 managed、两个信道） |
| `dmesg` 里 `freq_range_caps` / `hw_mode` | **零输出**（`grep -viE "usbss\|wcd_usb"` 后只剩一条 STA+MON）；`/sys/kernel/debug/` 空 → 固件把 5G 分几段，从用户态读不到 |

**四处硬编码 5745**（想让 tap 跟随 AP，这四处就是全部工作面）

| # | 位置 | 行为 |
| --- | --- | --- |
| 1 | `wma_opairshare_pas_mgmt.h:579` | `opairshare_pas_rx_fixed_channel`：`freq != 5745` → `-EOPNOTSUPP` |
| 2 | `wlan_hdd_wondertap.c:226` | `if (ret < 0) return …`，把 1 的错误原样上抛 → **调信道在第一步就被挡，硬件从未被请求过** |
| 3 | `wma_opairshare_awdl_rx_mac_filter.h:146` | 收帧桥接：`opairshare_bridge_freq != 5745` → `-EOPNOTSUPP` |
| 4 | `wlan_policy_mgr_opairshare_sta_coexist.h:80` | 共存判定：`if (pas != 5745) return false;` |

**三个「别再试」的结论**

1. **`passthru_sta_coexist` 由 `3` 改成 `2` 对「5G 非 149」毫无帮助，而且更糟。**
   `policy_mgr_opairshare_sta_freq_allowed`（同文件 69–88 行）里 `sta == pas` 只对 149 成立，
   剩下只放行 `2412..2484`，**没有 5G 分支**。更麻烦的是 `wlan_hdd_scan.c:524` 拿这个函数当
   扫描过滤器：模式一开，那颗非 149 的 5G AP 会从扫描结果里**直接消失**，症状从「传不了」
   变成「连不上」。→ 3→2 绝对不能单独上线。
2. **双 MAC / DBS 救不了这件事。** `wlan0` 与 `wondertap0` 本来就是两颗 MAC，`iw phy` 也允许
   两个 managed + 两个信道，所以「Wi-Fi 在 44、tap 在 149」理论上做得成。但 Wi-Fi 保住了，
   AWDL 还在 149，iPhone 还在 44 —— **照样碰不上**。DBS 解决的是「Wi-Fi 断」，不是「能收发」。
3. **11-14 那次「把 tap 调到 5220 会崩」没测到硬件。** 它死在硬编码 #2 那行，射频一次都没被
   请求过。所以「这颗芯片能不能在非 149 上跑 AWDL」这个实验**至今没做过**——这是整条线唯一
   真正的未知数，也是下一步第一件要测的事。

**正确的形状**：tap 跟随 STA 信道（同频 = SCC，不需要 DBS、不需要 SBS、不需要
`force_dbs`，`passthru_sta_coexist` 那一整套机器是错的方向）。未关联（独占态）时保持
social channel 149 不动 —— 这与苹果的行为一致。

**动作（每步可回退；第 1 步之前不需要任何驱动改动）**

| # | 动作 | 验收 | 状态 |
| --- | --- | --- | --- |
| R0 | 功能页拿到 STA 信道后判一句：不在 149 就在开关下面写「此 WiFi 与 AWDL 不同频，开启后无法与 iPhone 传文件」 | 连非 149 AP 时文案出现；连 149 时不出现 | ⏳ 未做（只动 Java，零风险） |
| R1 | 只放开硬编码 #1，`awdl-stack.sh` 把 tap 调到 5220，看 `ip link set wonder0 up` 成不成 | 成了 = 硬件允许跳频，整条路走通；不成 = `-EOPNOTSUPP` 之外的错误码，据此定下一步 | ⏳ 未做（要重装驱动，断链路几十秒） |
| R2 | R1 通过后放 #3（桥接频率匹配改成跟随）、#4（共存判定去掉 5745 假设），做 tap 跟随 STA | 非 149 AP 下与 iPhone 双向各传一次，速率不低于 149 那档 | ⏳ 阻塞在 R1 |
| R3 | R2 不成则退回「承认边界」：`freq_allowed` 加显式「5G 异频不可共存」出口，界面按 R0 提前说清 | 传文件失败不再被误认为「传输坏了」 | ⏳ 备选 |

**为什么暂停**：用户 2026-11-19 22:14 要求先记录、暂缓。恢复前置是**重装驱动会断当前 AWDL
链路**，且 R1 之后可能暴露 RX filter / `aux_set_freq` / vdev 重建时序上的新依赖，属于
「一次只能验一步」的活，不宜与速度线（§8.1）并行。

### 8.4 未来能力线（用户 2026-11-22 列的 8 项，均未开工）

源码在两处：本仓库 `kernel/`（驱动覆盖层 + 载荷）与 Ubuntu 上从 OnePlusOSS 官方仓库
拉的完整源码树（`/mnt/opairshare-build/opairshare`，见 `kernel/README.md`）。按用户给的
顺序记录，每条带一句"当前认知/切入点"，开工时再展开成 §8.1 那样的可验收步骤：

| # | 能力 | 当前认知 / 切入点 |
| --- | --- | --- |
| F1 | **硬件 TX A-MPDU 多对端聚合 + 接收侧 A-MPDU**；用户态软件 A-MSDU 合包 | TX 侧已有软件 A-MSDU（`passthru_awdl_amsdu=2`、`amsdu_max=8`、`amsdu_bytes=11454`，实测 283×11454=3.24 MB/s），硬件 A-MPDU 待确认；**RX 侧才是当前短板**：`ppdu_consumed_hist: 1:58818` 恒 1 MSDU/PPDU、iPhone 每 PPDU 只装 1 帧，接收侧 A-MPDU 解聚合 + 合包是提 RX 的关键路径 |
| F2 | QAM4096（EHT/HE MCS 11） | `passthru_awdl_phy` 只收 0/2/3/4（OFDM/HT/VHT/HE），MCS 上限 HT7/VHT9/HE11；EHT=5 被拒（5-bit preamble 装不下），QAM4096 要 MCS 10/11 → 需 HE 通道（`phy=4 mcs=11`）；但 iPhone 非传输态只持 20 MHz，发高速率 PPDU 会互不可见，须等两阶段切换 |
| F3 | SGI（短保护间隔） | 属 fixed-rate / vdev 参数，`wonder-ven rate` 的 `gi` 位；当前 `gi=1`，需确认是 0.8 µs 还是 1.6 µs 语义 |
| F4 | 免预绑定动态多槽位（8 槽） | 参考 `houji-airdrop` 的多槽 peer 关联；当前 `PEERS=8` 已是 8 槽上限（接收并发），但"免预绑定动态"是发现/关联层的增强，待设计 |
| F5 | EZIF MSDU-buffer RX 与 REO | 现役已用 REO（`reo_target==reo_raw`、`reo_nonraw=0`）；EZIF buffer 路径待查芯片文档与 bzl 开关 |
| F6 | 虚拟网卡形态与邻居解析（L2 TAP + 用户态 NDP） | 当前 `wondertap0` + `awdltun0`（TUN 承载 IPv6），`Mac::for_ipv6` 由 IPv6 推 MAC；L2 TAP 会把链路层下沉到用户态，改动面大 |
| F7 | mDNS ↔ AWDL 服务发现适配（双向即时触发 + 单播转组播） | mDNS 应答器已 ✅（§8.2-2），但只在进入接收时尝试一次、tunnel 不在则永久放弃；"双向即时触发 + 单播转组播"是发现层的下一步 |
| F8 | （用户留空，待补） | — |

**共同前置**：F1（RX A-MPDU/合包）直接关系当前"接收慢"，优先级最高；F2/F3 受"发现阶段必须 20 MHz"
约束（§0），要等传输态两阶段切换才敢放开。每开工一条，先按 §8.1 的"单变量 + 判据"格式写细。

---

## 9. 度量基线

| 指标 | 值 | 备注 |
| --- | --- | --- |
| 发送吞吐（稳态） | **2.97–3.14 MB/s**（`bbr`+`fq`） | 280 MiB / 20 文件随机样本，两轮；`cubic` 对照 2.75 |
| 发送吞吐（全程） | **2.98–3.11 MB/s** | 同上；24.7 MB 老样本在 `cubic` 下是 2.59 |
| 发送吞吐（2 流并行，S4） | **3.02–3.25 MB/s 稳态** | 12×2 MB 与 280 MiB 两轮；同素材 1 流对照 2.75–3.14——**并行不提速**（§3.3.1） |
| native 每聚合时延 | **17.2 ms** | 5×7935 / HT20 / nss=2 |
| 聚合失败率 `status3/status0` | **1.75%** | 同一轮 93/5329 |
| TCP 重传率 | **0.98%** | 1120/114244 段（`ss` 实测）；cubic 时代 289/19290 = 1.5% |
| 拥塞窗口（`ss` 实测） | **46–56 段 ≈ 62–77 KB** | 对端 `wscale:9`，窗口可到 32 MB——**窗口不是限制** |
| `minrtt` / 实时 `rtt` | **3.413 ms / 17.9 ms** | 差 14 ms ≈ 45 KB 排队，在 `awdltun0 → bridge` |
| BBR 自估链路带宽 | **34–35 Mbps（4.3 MB/s）** | 实交付 3.05 = 71%；差在丢包恢复与 `pacing_gain` 探测 |
| 聚合饱满度 | **4.30 msdu/聚合**（上限 5），`p5` 占 73.5% | A-MSDU 5×7935 已打满，无余量 |
| 接收吞吐（随包 ko，窄档） | **340 KB/s 均值 / 676 KB/s 峰值** | 2026-11-22 20:0x，48.5 MB / 146 活跃秒；判据是 `awdltun0` rx 字节 |
| 接收吞吐（随包 ko，宽档 EHT80） | **455 KB/s**（330–500 平台，每 3 s 一个凹口） | 同日晚，只比窄档 +34%——宽度不是杠杆 |
| 接收「每批帧数」 | **~8 MSDU / 批，每 ~25 ms 一批**（`8 × 1488 ÷ 0.025 = 476 KB/s`） | 与实测 470 KB/s 闭合；BA 窗口允许 64，故真正的杠杆是这条 |
| 接收链路送达率 | **97%**（`wonder0` 88.3 MB/71 825 包 → `awdltun0` 85.9 MB/71 828 包） | 不丢帧、不重排、不拆包失败 |
| 接收吞吐（Xs Max / iOS 18.7） | 0.099–0.109 MB/s | 对端 24.45 ms 固定节拍，不在本仓库 |
| 空口效率 | 98.1% / 100.0% | 接收侧没有可捞的损失 |
| 单元 + 集成测试 | **431 passed / 0 failed（1 ignored）** | `cargo test --workspace`，18 个 test result 行相加，无设备。**2026-11-21 实测**：本文此前记的 420、`README.md` 记的 427 都是旧值 |
| JVM 断言 | **394 条 `ok`** | `crates/ffi/jvm/check.sh`，桌面 JVM + `-Xcheck:jni`。**2026-11-21 实测**：`grep -c '^  ok  '` = 394，分段 round-trip 38 / settings 185 / identity 54 / radio 48 / bring-up 69；五段自报相加也是 394（`RoundTrip` 于 15:04 补上汇总行后两种数法一致）。本文与 README 此前记的 355、375、381 **全部偏小**，原因见下 |
| 代码规模 | Rust **25 007** 行 / Java **42 类 18 563** 行 | 只算 `crates/` 与 `android/app/src/main/java`；`check.sh` 那 15 个 Java 测试类另算 4 828 行 |
| `libopairdrop_ffi.so` | **704 464 B** | `aarch64-linux-android` release |
| `app-debug.apk` | **14 359 002 B** | **只认 `gradle clean assembleDebug` 的值**，增量打包会残留空对齐条目，虚高约 640 KB |

**为什么 355 / 375 / 381 都是错的**：`RoundTrip` 曾经是全流程里**唯一不打印 `ok, N checks`** 的一段
（它成功时只印一句 `all checks passed`，`failures > 0` 时才印数字）。所以任何「把几段的汇总数
加起来」的算法都必然漏掉它那 38 条：`185 + 54 + 48 + 69 = 356`，而真实总数是 394。历史上 375 与
381 还各自对应过更早的段落组成，但**根子是同一个**——用汇总行相加代替数行。

**2026-11-21 15:04 已把源头补上**：`RoundTrip` 现在收尾同样印 `ok, 38 checks`，五段自报
`38 / 185 / 54 / 48 / 69` 相加正好 394，与数行是同一个数。两种数法从此互为校验，任一走样都能当场
看出来。原始判据（永远有效，不依赖任何类的输出格式）是

```bash
bash crates/ffi/jvm/check.sh 2>&1 | grep -c '^  ok  '   # 394
```

**出包后必须做的四件事**（少一件就会出现「代码明明改了却没生效」）：

```bash
adb shell su -c "kill -9 $(adb shell pidof com.coloros.oshare)"   # 只 start-foreground-service 不换进程
adb shell am force-stop com.oshare.airdrop                          # 否则新 .so 不加载
adb shell am start-foreground-service -n com.coloros.oshare/com.oplus.oshare.KeepForeService
adb shell am start -n com.oshare.airdrop/.MainActivity              # 再点一下「接收端」胶囊
```

---

## 10. 设备与工具坐标

- 设备：一加 13 / PJZ110，`646e00bd`，1440×3168，ColorOS V17.0.0。
- 源码根：`D:\Downloads\project\repo-src`；反编译产物：`.tmp/oshare-17.2.36/`。
- 工具链：`JAVA_HOME=tools/jdk-17.0.20.1+1`、`ANDROID_HOME=tools/android-sdk`、
  `tools/gradle-9.6.0/bin/gradle`，`export MSYS_NO_PATHCONV=1`。
- 出包：`bash android/build.sh assembleDebug`（Rust 两包 + Gradle 一步到底）。
- 应用日志：`/sdcard/Documents/AirDrop/airdrop-YYYY-MM-DD.log`（`log.level` 默认 `debug`，一天一个文件）。
- ADB：`tools/android-sdk/platform-tools/adb.exe`（相对仓库根）。

**界面坐标（1440×3168）**：

| 目标 | 坐标 | 备注 |
| --- | --- | --- |
| 底栏 首页 / 功能 / 设置 | `(357,2943)` / `(719,2943)` / `(1082,2943)` | `bounds` 高 71px |
| 首页「接收端」卡片 | `(682,1078)` | 在「已停止」↔「监听中」之间切 |
| 功能页「同名时覆盖」开关 | `(1196,1425)` | 第二屏 |
| 设置页 `receive.directory` 输入框 | `(720,2338)` | `[144,2250][1296,2427]` |
| 设置页「保存」 | `(1034,2616)` | `[740,2528][1328,2704]` |
| 卡片「接受」/「完成」 | `(1024,2824)` | 拒绝在 `(416,2824)`；从 `bounds` 求中心 |

功能页会把底栏吞掉（滚动后 dump 里没有「首页/功能/设置」），点击全部落空——先划回顶部。

**改 `receive.directory` 的快捷路**（先停进程，值在启动时读进内存）：

```bash
adb shell am force-stop com.oshare.airdrop
adb shell "run-as com.oshare.airdrop sed -i 's/^receive.directory=.*/receive.directory=/' files/settings.conf"
adb shell am start -n com.oshare.airdrop/.MainActivity
```

功能页「接收目录」那一行就是判据。

---

## 11. 变更日志（压缩，新的在上）

> 2026-11-18 之前的完整叙述原在 `Task-archive-20261118.md`，**已并入本文并删除**。

| 时间 | 事件 |
| --- | --- |
| **2026-11-24 05:0x** | **评审第 4 轮（一）落地：CI 门禁全绿**（提交 24aef33/f36db8c）。`.github/workflows/ci.yml`：clippy `-D warnings`（0 警告，link.rs:196 改 checked_div）/ cargo test 434 / 交叉 check aarch64-android / 载荷哈希（kernel/PAYLOAD.sha256 + tools/verify-payload.sh）/ JVM 394 / build.sh 组装 APK + .so 在包校验。**P1-21**：local.properties 取消跟踪；`.cargo/config.toml` 不写死 linker（build.sh 按 NDK 位置与宿主系统派生 `CARGO_TARGET_*_LINKER/AR`，Linux/macOS/MSYS 三宿主）。**CI 调试七连的收获**：①Windows 提交丢可执行位（core.filemode=false），用 `git update-index --chmod=+x` 补；②StackCheck 的回执用例在 Linux runner 上拿真实 /proc boot id 对账，不带 boot= 的回执被判 stale——全部改 `parse(text, "")` 桌面语义（本机绿 CI 红差一个操作系统）；③read(dir) 同理加 read(dir, boot) 重载；④gradle 先 PATH 后兜底；⑤**android-37 平台在 sdkmanager 仓库里是 minor 目录**（官方包 `platform-37.2_r02.zip`），CI 解压改名 `android-37` 后必须同步改 package.xml 里的 `<localPackage path>`（AGP 按它对账，本机手工步骤同款）——「本机能编、CI 找不到平台」的完整闭环。评审剩余：P1-16、P1-13、fuzz、拆分、文档自动化。 |
| **2026-11-24 04:5x** | **评审第 3 轮落地：生命周期/并发四项**。①**P1-15 Peers 线程泄漏**：`stop()` 里 `knocker/watchdog.shutdownNow()`；五处 `execute` 全改走新的 `submit()` 助手（`RejectedExecutionException` 安静丢弃——loop 最后一拍可能恰好在关门后还想排一次敲门，普通线程上的未捕获异常就是进程死亡）；②**P1-14 SendHost.detach**：真实实现（清 canceller），onDestroy 无条件调用；**replay 有意保留**——卡片上的【重试】要在页面被回收后仍能工作，清了它重试就是死按钮（评审建议"清 replay"与重试设计冲突，按设计取舍并注释）；③**P1-17 模糊自激**：PreDraw 只在 `dirty || 根尺寸变化` 时重拍+叫醒可见 pane（自激 60Hz 循环就是无条件 invalidate 驱动的）；内容变化的驱动靠显式 `refresh()`——`replacePage` 每次已调 `refreshGlass()`；④**P1-12 Listener 启停竞态**：`end()` 用**线程自证**收口（`worker == Thread.currentThread()` 才清）——第一版用 genWorker 指针对账有缺陷（genWorker 指向最新线程，旧线程收尾照样误清新 worker），改线程自证少一个同步字段且正确；⑤**P1-11 awdld 错误分级**：`survivable()` 分类——瞬时（EINTR/EAGAIN/ENOBUFS）小睡 10ms 继续，接口没了（ENETDOWN/ENODEV/ENETRESET）大睡 2s 等接口回来（脚本 guard 负责重建环境），仅 EPERM/EBADF 退出；poll/down/up 三处全覆盖。**基线**：cargo test 434/0 失败；jvm 394 ok；clippy 仅既有 1 条；APK 装机。评审 P1-16（MainActivity 整页重建拆分）留待后续——纯性能项且涉及滚动位置语义，P1-13（满额显式拒绝）涉及 51c09b3 的并发设计（memory 勿回退区），单独验证后再动。 |
| **2026-11-24 04:4x** | **P1-18 被实测推翻并回退——真实 iPhone 本来就不发指纹**。①装机后用户发 iPhone 全部立刻「发送失败」，日志：`no fingerprint for fc80e1452de9: refusing to carry files on an unpinned connection`；②根因：评审的前提「攻击者只要不发 h= 就绕过钉住」隐含"真实对端都发 h="，实测不成立——真实 iPhone 在隔空投送=所有人、联系人未匹配时 iOS **本来就省略 h=**，指纹为空是真实协议的常态而非攻击信号，硬拒发把正常传输全拒了；③教训一：`git checkout <file>` 恢复到的是 **HEAD（已提交 91e16ea 含闸门）**，第一次"回退"等于没退，第二次"仍失败"是旧进程+未回退双重叠加——回退已提交的改动必须手改而不是 checkout；④教训二：安全建议在落地前先核实"真实对端行为"，评审的威胁模型需要现场数据背书；⑤现状：指纹为空照常连接（记日志），指纹在场时钉住照旧生效；清单里第 2 轮其余 7 项修复全部保留。 |
| **2026-11-24 04:3x** | **评审第 2 轮落地：资源上限三处 + HTTP framing 四处 + 指纹 fail-open**。①**P1-1 DVZip**：`inflate` 加 `take(MAX_INFLATED+1)`（上限=2×MAX_BLOCK），读满即 InvalidData——1000:1 的 zlib 放大被钉在 2 MiB；②**P1-2 mDNS**：questions/records 的 `with_capacity` 按报文长度钳制（`len/5`、`len/11`），计数本身照旧消费、多出的自然走 ShortHeader；③**P1-9 收件箱**：`Limits` 加 `member_limit`（默认 65 536），`open()` 里目录与文件统一计数，超限记 `Error::TooManyMembers`（api.rs classify 归 OVER_BUDGET 状态码，settings.rs limits() 暂用常量不进设置表——它是防滥用上限不是用户偏好）；④**P1-5 走私**：`Headers::content_length_conflicting()`（重复头合并成 "5, 5" 或解析失败 = 不可信），server.rs 请求循环对 POST 命中即 400+close，不读 body；⑤**P1-6 trailer**：chunked trailer 限 MAX_HEADERS 行 / MAX_HEADERS×1024 字节，超限 `Error::TrailerOverrun`；⑥**P1-7 注入**：mDNS `read_name` 拒收控制字符（<0x20/0x7f）的 label——SRV target 进 `Host:` 头的源头掐死，写头处 debug_assert 保留作兜底；⑦**P1-8 token**：`is_chunked` 改逐项逗号切分精确比较（`chunkedNOT` 不再算 chunked）；`contains_token` 保留子串语义（Content-Type 含参数的场景在用，注释里写明）；⑧**P1-18 指纹**：`connectTls` 加 `requirePin` 参数——`open()`（承载文件）传 true，指纹为空直接拒绝发送；`probeName`（/Discover 探测）传 false 显式放行；类注释同步。**基线**：cargo test 434/0 失败；jvm 394 ok；clippy 仅既有 1 条；APK 重编装机。评审第 3 轮（Listener 竞态、泄漏族、UI 重建、awdld 错误分级）待续。 |
| **2026-11-24 04:1x** | **评审第 1 轮落地：三个 P0 全修 + qca 载荷对齐**（评审报告 `OPAirDrop-代码评审-2026-11-23.md`）。①**P0-1 `/Upload` 越权落盘**：`Server` 增加 per-connection `accepted` 标志（/Ask 晚答分支在 `accept()` 通过后置位、早答分支在 `ask(None)` 后置位），`upload()` 对 `!accepted` 一律 401 且不读 body；TransferID 与 offer 点名不符改判 409（旧代码只统计不强制）；更新 5 处测试（两处裸 /Upload 补 /Ask、破坏承诺改判 409、无 offer 测试改为断言拒绝）、loopback 三兆传输与 JVM RoundTrip 两条集成链也补上 /Ask 前置（JVM 侧把 `ask_request` 确定性编码的 477 字节 bplist 嵌成 `ASK_BODY` 常量）；②**P0-2 bplist 偏移表回绕**：`read_unsigned` 改返回 `Option<u64>`（边界检查内聚）、表结束位置全程 checked 算术、`with_capacity` 按表真实跨度钳制、UTF-16 `count*2` checked_mul、加解码节点预算（共享 DAG 2^33 展开改判 `TooManyNodes`）；补 3 条回归（表回绕 u64::MAX / UTF-16 长度回绕 / 33 层共享 DAG）；③**P0-3 JNI 异常挂起**：9 处元组读全部串行化（每步后 `pending(env)` 短路）——mdnsReply 五元组、broadcast 四元组、settings 双读、optional 四连发、六处 `Stream::new/Sink::new` 元组；`optional` 闭包孤儿删除。`entry!` 的 panic 暂留（触发条件=JVM 缺 JNI 1.1 表项，能加载本库即不可能成立，重构涉及全部调用点，记为后续项）；④**P1-22 qca 载荷对齐**：实测仓库 gz=152b93ad/20 050 688 B=设备在跑=APK 内 SHA256SUMS，唯一漂移是 README——重写该行为现役随包驱动（自建 eac00ca0 已退役，且旧行把快慢说反）；README 测试数 431→434。**基线**：cargo test 434（431+3 新增）/0 失败；clippy 仅剩评审前既有的 1 条；jvm/check.sh 394 ok；APK 重编装机。评审 P1-5~P1-20 与 CI/fuzz/拆分留待第 2-4 轮。 |
| **2026-11-24 03:2x** | **小窗之谜终答：一加互传全屏靠的是系统特权身份，不是清单参数**。①拆 OShare 清单（aapt2 dump xmltree）：它的分享目标 `com.oplus.oshare.ui.IOSShareActivity` 确实注册了 SEND/*	*，且 `resizeableActivity=true` + `documentLaunchMode=never` + `relinquishTaskIdentity=true` + `finishOnCloseSystemDialogs=true`；②把这套属性逐项复刻到 SendActivity（保留我们的 theme/excludeFromRecents/singleTop），强停清任务后真机实测——**仍小窗**，参数决定论排除；③补最后一块拼图：OShare `targetSdk=37` 与我们相同，但 `pm path` 显示它装在 **/product/priv-app**、`pkgFlags=[SYSTEM]`——ColorOS 浮窗分享跳过的是特权/系统应用本体（相机、钱包同理，所谓「小窗黑名单」很可能就是这批平台应用），第三方应用无加入通道；④清单保留复刻的那组属性（与系统自家分享页同形，无副作用）；设备侧唯一可靠开关仍是 设置→辅助功能→自由浮窗→浮窗分享。实验注意：改清单后的验证必须先 `am force-stop` 清旧任务（singleTop 会把新分享送进按旧清单创建的活任务）。 |
| **2026-11-24 03:0x** | **分享面板呼出 SendActivity 变小窗：三轮实验 + 定案（app 侧无解，ColorOS 浮窗分享强制框 bounds）**。①抓到现场证据：`mWindowingMode=fullscreen` 但 task bounds 被裁（屏幕 1440×3168 → 任务 1440×2560=16:9 居中），不是 freeform 模式——是 ColorOS「浮窗分享」在 bounds 层面框任务；②三轮清单实验：只有 `resizeableActivity=false`（Android 16+ 默认忽略）→ 被框；加官方豁免属性 `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`（false 恢复效力，任务 nonResizable）→ 系统 letterbox 16:9，仍小窗；③加 `maxAspectRatio=2.22`（豁免后恢复生效，本机原生比例 2.2）→ letterbox 比例跟声明走，**保留在清单里**——将来系统不再框它时任务直接铺满全屏而非掉进 16:9 小窗；④应用侧自救（检测 bounds≠满幅就 setLaunchBounds 重启自己）也失败：原始实例 finish 时 URI 授权随之吊销（grant 绑在收到分享的 ActivityRecord 上），重启后的实例拿不到相册文件，已回退；⑤智能侧边栏点开的应用同样小窗——同一套自由浮窗机制；用户指认相机/钱包在小窗黑名单里，设备上未找到独立黑名单配置文件（/system 等无 freeform 命名文件；与相机对比 resizeMode 无差异）。**剩余可用手段是系统侧开关**：设置 → 辅助功能 → 自由浮窗 → 浮窗分享（关掉后系统分享一律全屏打开，官方文档原话「不开启此功能，分享时使用全屏打开对应应用」）。**坑**：adb `am start` 报「delivered to currently running top-most instance」= 旧任务还活着（singleTop + install -r 不杀运行中任务），新清单对活着的任务不生效——改清单后的验证必须先 `am force-stop`；force-stop 会带走前台服务的常驻通知，重开应用即恢复。 |
| **2026-11-24 02:3x** | **「让 iPhone 立刻显示失败」四模式 A/B 实测定案：纯 FIN 才是它认的语言，任何响应字节都是毒药**。①把取消做成四模式开关（`debug.opairdrop.cancel_mode`，取消时现读）逐个实测同一台 iPhone：模式 0（写 400+睡+close，旧行为）无反应；模式 1（400+FIN+排空+干净关）无反应；模式 3（500+FIN+排空+干净关）无反应；**模式 4（不发响应，FIN→排空→干净关）iPhone 几秒后显示失败**；②反直觉结论：问题从来不是「没发应用层信号」，是 early response 本身——iOS 的上传栈收到「请求体没发完就来了响应」不当作被拒绝，反而当异常走重试/等待；纯 FIN 它认得：服务器正式结束连接 → 标失败。这也解释了 houji 的 400 路线对 iPhone 无效；③固化：`HangUp.refuse` 重写为 shutdownOutput → drain（读到 EOF 或超时）→ 干净 close，不再写任何响应字节；`now()`（RST）只保留给我们自己的发送端掐自己——苹果的**接收**实现认 RST（「iPhone 中途终止→我们秒显失败」的对称性），它的**发送**实现只认 FIN；④时延拆账：我们这边 2.7-3.0 s（排空打满 3 s 窗口），共 ~9 s，剩下 ~6 s 是 iOS 内部传播（动不了）；排空是否可压留给 `debug.opairdrop.drain_ms` 旋钮（0/1000/3000 可实测「FIN 是否单独成立」）；⑤邀请无应答倒计时 25 s→30 s：代码默认本就是 30 s（`Confirmation.TIMEOUT_MS=30_000`），是设备设置里存了 `receive.decision_timeout_ms=25000` 覆盖值——用 run-as+stdin 把 settings.conf 改回 30000，下次邀请即生效（decisionTimeout 每次邀请现读磁盘）。 |
| **2026-11-24 01:4x** | **houji-airdrop 对照 + 一个日志盲区修复**。①克隆细读 `ha0xin/houji-airdrop` 的 owly 协议栈：**接收侧根本没有「传输中途取消」功能**——API 只有 /Ask 阶段的 `Request.accept()/decline()`（注释明说 "An /Ask waiting for the user. Dropping it declines."），接受后只有 Progress/Received/Failed 事件，问题被产品层面绕开；②它确有的相关机制是**应用层早答复**：上传出错不等 body 读完就回 `fail(status)`（413/400/500 + `Connection: close`，`proto/receiver.rs:304`），它自己的 sender 对 Uploading 中收到非 200 立即 `Outcome::Failed("upload refused with NNN")`，对 EOF/断连也立即 Failed/Declined，**全链无重试**（driver/sender.rs grep retry/reconnect 零命中）——houji↔houji 两端都「连接死=失败」，不对称不存在；③与 iPhone 互通时它面对同样的 iOS 行为，没有 magic；④**订正上一轮结论**：我们的取消其实**已经在走 houji 同款方案**——`HangUp.refuse()` 先写 HTTP 400 + `Connection: close` 再等 1.5s 关闭，01:20 那次实测跑了（cancel+1504ms 恰为其 close 时刻，读线程同时刻报 break）；iPhone 收到 400 后仍停在「正在发送」⇒ **iOS 对 PUT 的 early 4xx 也不翻脸**，这是苹果 HTTP 栈的固有行为，任何 AirDrop 实现都绕不开；⑤顺手修掉一个日志盲区：`HangUp.java` 一直 `import android.util.Log` 直写 logcat，绕过了文件日志器——所以「refuse: 400 handed」这行从来没进过 `airdrop-*.log`，排查时误导为「取消路径没走 refuse」。已切回同包 `Log` 包装器（签名一致，免 import），重编装机。 |
| **2026-11-24 01:3x** | **「我们取消接收后 iPhone 仍显示正在发送」定案：协议不对称，非 bug**。①日志实锤（01:20:20 cancel → 01:20:21 RST 生效、2.8 MiB 已落地、正确显示已取消；**57 秒后** iPhone 重连 POST /Ask，同样 4 个文件 9260808 字节分毫不差，弹新邀请 airdrop-11）；②AirDrop C2P 协议**没有「接收方中途取消」的应用层消息**——/Discover、/Ask（401=拒绝）、/Upload 之外无 cancel 端点，接收方取消的唯一信号就是断 TCP；③RST 在苹果发送端只是「网络错误」，与 WiFi 抖动不可区分，NSURLSession 静默重试/等待期间 UI 停在「正在发送」——与 2026-11-15 那条 162.6 MB 记录同形（destroyed is not completed）；④苹果对此的最终表态不是标失败而是**从头重发**（重连重 /Ask，重新征求同意——协议上正确）；⑤接收侧无法迫使它立刻翻失败：重试节奏在它的栈里，传输中途无状态码可回；唯一能让 iPhone 立刻显示拒绝的信号是 /Ask 阶段的 401。**不对称的根源**：我们的发送端是自写的（连接死=立即失败），iPhone 的发送端是苹果的（连接死=可重试）。 |
| **2026-11-24 01:1x** | **「展开后自动缩回」复发根因定案 + 新钩子收口**。①现场时间线（00:56 抓全）：展开 1.8s → **整 3 秒** `onHide-capsule` → `onHide-card` → `CARD_TO_CAPSULE`，随后应用更新通知重建模型再展开——「展开→缩回→又展开」循环；②旧钩子（`viewmodel.i.l(String)` = handleDataTimeout/EVENT_CARD_TIME_OUT）**自启动 0 命中**——11-21 系统更新后收回不走这条了；③反编译当前 SystemUIPlugin（9.2 MB，apktool）钉死现行路径：`data/interactor/m` 里按 remindType 二选一——**11（STRONG_SHORT，正是 lift 强推的「能自动展开」级别）→ 3000 ms，其余 → 5000 ms**，然后 `notification/j` 调通用调度器 `com.heytap.log.collect.auto.d.I(key, delay, int, task)` 挂定时任务，到期执行 `interactor/m.G()`（onHide-capsule）；④`I()` 有 9 个调用方（j / strategy-d / viewmodel A·B·F·r / card-q），全部汇进这一个咽喉，第一参数就是 livealert key；⑤**新钩子 `hookShrinkTimer`**：钩 `I(String,long,int,…)`，key 含 `com.oshare.airdrop` 就不调 proceed（定时任务根本不排），其它应用不受影响；不钩 G() 本身——00:48:36 实测正规撤除路径也复用 G()，跳过它会把正规撤除一起弄死；⑥装机 + 重启 SystemUI（`kill 6313`）+ 真机验收：**展开后稳定不缩回**，日志 `第 1 次跳过自家的缩回定时器（key=0|com.oshare.airdrop|2816|…）`。**为什么反复复发**：不是钩子被改回，是 ColorOS 每次更新换掉收回的执行层（handleDataTimeout → 调度器），钩子留在旧层上就静默失效——修法必须盯「收到的效果」（时间线+0 命中判据），别盯上一次取证的方法名。**另一个坑**：重启后锁屏挡 `am start`（result code=-92），唤醒+上滑即过。 |
| **2026-11-24 00:4x** | **重启验证通过 + 一加互传协议拆包定案**。①`adb reboot` 后 `/data/adb/service.d/99-opairdrop-hans.sh` 以 busybox sh 自启（pid 3191）、pidfile 就位；熄屏 15 s map 里始终没有 uid 10409 的 key、egress 计数器自开机起为 0；应用重新打开后 awdld/awdltun0 自动拉起（栈随应用走），iPhone 端到端 5 个文件 9.4 MiB 接收完成、流体云卡片正常——**hans 守护链路全绿收口**。②锁屏挡启动：`am start` 返回 `result code=-92`（START_CANCELED），唤醒+上滑即过，不是包坏了（`dumpsys package` 显示 `enabled=0 stopped=false` 都正常）。③**一加互传（com.coloros.oshare，27.6 MB priv-app）拆包**：dex+assets 里 `opairshare`/`wondertap`/`nl80211`/`netlink`/`qcacld` **全部 0 命中**——应用完全不碰驱动；`WifiP2p` 20、`p2p` 184、`WIFI_P2P` 3 命中，`BluetoothLeScanner/Gatt/Advertiser` 命中 ⇒ **BLE 发现 + Wi-Fi Direct 传输（互传联盟套路）**；`AWDL`/`AirDrop`/`5745` 零命中 ⇒ **与 iPhone 无任何互操作**。`NAN` 的 130 个命中全是 `nAnimateKitCont`/`Nanos` 等 dex 字符串去重假阳性（`android/net/wifi/aware` API 0 命中）。**订正**：此前把 `5745MHz fc=00d0` 记成"一加互传走的桥"是张冠李戴——那是**我们自己的** AWDL 栈借厂商 opairshare 桥发的帧（`OPAIRSHAREBRIDGESEND vdev=0` 是我们 vdev0 的日志）；`opairshare` 驱动扩展在 oplus-framework/services jar 里也 0 命中，即 Java 框架层同样不直接驱动它。 |
| **2026-11-24 00:5x** | **★★★「iPhone 点发送迟迟不弹卡」最终破案：不是进程被杀，是被 ColorOS 断网——IPv6 BPF DROP，已守护化收口**（详见新增 §6.6，这里只记账）。①kretprobe 劈三层：LSM/SELinux/cgroup-BPF 全 ret=0，死点在 `nf_hook_slow` ret=-1（IPv6 LOCAL_OUT），`ip6_output` 零命中；②`ip6tables-save -c` 计数器差值把元凶钉到 `oplus_fw_OUTPUT` 的 `skfilter_egress_hans -j DROP`（8 秒 pkts+8 恰等于 8 条新 EPERM）；③数据源是 `map_oplus-netd_app_hans_limit_map` 里 uid 10409 的 key，删一个 key 双向放行（egress/ingress 共用 map），vending/gms 限制原样保留；④netd 会在**熄屏**时重写 map（前后台切换不会），故做守护 `tools/hans_guard.sh` → `/data/adb/service.d/99-opairdrop-hans.sh`（0.5 s 轮询、单实例 pidfile、开机等 map）；⑤验收：熄屏 15 s 计数器零增长 + 端到端 834 ms 弹卡；⑥hans 是 netd 原生实现（四个 jar grep=0），Java hook 不可行；`forceStop` hook 是另一层、仍有效；⑦Plan.md 本轮起**移出 .gitignore 进仓库**（用户拍板），.gitignore 第 19 行的整段注释一并删除。**adb 坑**：`adb shell su -c 'a; b'` 单层引号只把 `a` 给 su，必须 `adb shell "su -c 'a; b'"`。 |
| **2026-11-22 23:1x** | **F1 第一刀：A-MSDU 的每一环都被 `admit` 判死，宣告 A-MSDU 反而更慢的代码原因**。①`opairshare_pas_raw_admit()`（`dp_rx.c:1247`）原来要求同一个 buffer 上 `start && end` 同时为真。但 REO 拆 A-MSDU 是「一 buffer 一 MSDU」的链，只给首环置 `start`、尾环置 `end`，中间环两个都没有 ⇒ 首环缺 `end` 被拒、中间环 `chfrag_cont` 被拒、尾环也 `cont` 被拒，**整条聚合全丢**。这解释了 `native_raw_incomplete` 长期 17–41%，也解释了为什么 `ba_amsdu=1` 之后速度不升反降。②**改法**：不再要求 `start && end`，改为「是链元素就接纳」——`dp_be_rx.c` 的调用点本来就逐 nbuf 走（`nbuf = nbuf->next`），而 `dp_rx_deliver_raw_passthru()` 一次正好处理一个 MSDU，所以逐环接纳就是正确投递。仍拒 `frag` / `nonlinear` / 既非首环也非后继的孤儿（截断 PPDU 的样子）。③**产物**：`3b2c5cdc…`（20 039 512 B，2026-11-22 23:15 编译），`source-map` 的 `dp_rx.c` sha 同步为 `0aef7484…`。④**真机验证（2026-11-22 23:2x，未通过）**：装上 `3b2c5cdc` 后接收只有 **58 KB/s**，`ppdu_consumed_hist` 仍 `1:2321`（对端压根没开始聚合），而 `pop=7123 / deliver=2322 / ppdu_ahead=4801 / ppdu_break=4753 / stuck=48` ⇒ **放开 `admit` 只是把丢帧点往后挪了一层**：链元素进来了，但下一层的 PPDU 记账/重排把链打断（`ppdu_break` 几乎等于 `ppdu_ahead`）。所以这条修复本身不成立，要么同时改 `ppdu_ahead/ppdu_break` 那段记账，要么换一条不经 PPDU 记账的投递路径。**注意**：这条只在自建 ko 上生效（随包 ko 没有这段代码），而自建 ko 目前仍不能完成一笔完整传输，所以验证要连着"自建 ko 收帧少"那个未定位问题一起做。设备已退回随包 `152b93ad` + `radio=exclusive`（`radio=share` 会按 §3.5 付 45% 代价，实测 `sta=5745` 共用时明显更慢）。 |
| **2026-11-22 22:2x** | **★★★两阶段速率切换兑现：接收 455 KB/s → 2.09 MB/s 均值 / 3.68 MB/s 峰值（4.6 倍）**。①**做法**：新增 `awdl-stack.sh ratewatch`，每秒读 daemon 的 `peer_map`——`15×149+1×6` 判为传输态，用 `wonder-ven <if> rate 2 2 1 2 9`（VHT / 80 MHz / SGI / 2SS / MCS9）抬档；掉到 ≤8 槽且连续 6 秒退回 `1 0 1 1 7`（feed 默认 HT20/1SS/MCS7）。`boot` 在 `stack up` 时自动拉起，`stop`/接口消失时自行退出。②**实测**（随包 ko `152b93ad`、同一台 iPhone、只多这一个变量）：`dmesg: Set fixed TX rate: preamble=2, bw=2, gi=1, nss=2, mcs=9` 之后逐秒隧道增量 `0.56→1.51→1.58→1.62→1.75→1.92→1.86→2.03→2.07→2.43→2.85→3.68→3.36→2.09` MB/s，**14 秒 29.3 MB，均值 2.09、峰值 3.68**。切档前后同一配置的对照是 455 KB/s。③**为什么必须两阶段**：对端非传输态只持 20 MHz 接收机，80 MHz PPDU 它解不出来（§3.6.8，05:30 两机互不可见的成因）。所以发现期固定 HT20/1SS/MCS7，只在它的槽位图铺满时抬档；掉档自愈——若宽速率不可解码，它的 map 会自己变稀疏，6 秒后退回。④**修掉两个会让它静默失效的坑**：脚本开头 `set -eo pipefail`，而 `grep -c '^149$'` 在**没有 149 槽**时退 1，`n=$(…)` 带失败状态直接把 watcher 杀掉（发现阶段必然发生），必须 `|| true`；`passthru_aux_monitor` 只在建 vdev 时读一次，运行时写无效，A/B 必须走 insmod，已改成 `passthru_aux_monitor=${AUX_MON:-1} passthru_aux_wide=${AUX_WIDE:-1}`。⑤**40 秒内两次 `SWITCH_WIDE` 里有一次 `rc=2`**（tap 正在重建），已加一次重试。⑥**待办**：APK 里的 `stackScript` 还是旧版，App 启动会把设备脚本覆盖回去，需重编 APK 固化。 |
| **2026-11-22 22:1x** | **★自建 ko 的 A/B 定案：它的 RX 只有 aux monitor 一条路，DP 环形缓冲那条没实现**。①`passthru_aux_monitor` 的运行时写**无效**——它只在建 vdev 时读一次；脚本里那行改成 `passthru_aux_monitor=${AUX_MON:-1}` 后按 `AUX_MON=0` 重启，才是真 A/B。②结果：`aux_monitor=N`、`opairsharerx0` 接口数 **0**、STAT `peer=none other=0 phy=?` ⇒ **一帧收不到**（iPhone 分享面板开着）。同一颗 ko 让 monitor 起来（`aux_wide=1`，vdev1 = chanmode 26 EHT80）则能收、能建连、能开始传，但一笔传输在 **174 KB** 处停住（`rx=197p/174592B` 后连接消失，`ss` 已无 8770）。③**结论（22:4x 已收窄，原话过强）**：`AUX_MON=0` 时连 presence 都没有，链路根本没建起来，所以**不能**据此断言"数据面只有 monitor"。真正量过的是一次随包 ko 的四层对比（`pathmon.sh`）：`opairshare_native_raw_to_stack` 18 574 / `opairsharerx0`（monitor）1.9 MB / `wonder0`（tap）30.0 MB / `awdltun0` 29.4 MB（98%）⇒ **数据走的是 `dp_rx_deliver_raw_passthru()` 在常规投递函数里的那条分支（`dp_rx.c:1369`），monitor 只搬 presence**；而那条分支覆盖层里本来就有。所以自建 ko "收得少"不是缺这条分支，具体原因仍未定位，别再把"移植 monitor rings"当成既定方向。④**两个新观测**：我们自己编的 ko 会打印 `window_eff`（随包不打印），这次对端请求 **16**（不是 64 也不是 8）；`ba_stats` 里 `amsdu=1` 已生效（宣告 A-MSDU 的能力我们有了，但受 RX 数据面所限兑不出来）。⑤**构建账**：warm 5:01（纯分析）/ 改一个头文件 48:52（连带重编 kernel image）/ image 缓存后 14:34。**注释里不能出现 `/*` 序列**，`-Werror,-Wcomment` 会直接编译失败（本轮踩过）。⑥设备已退回随包 ko（`other=196 anchored=188` 收帧正常）。 |
| **2026-11-22 20:2x** | **接收天花板定位到「每批帧数」，四条运行期假设全部证否**（★这一条取代 19:0x 的"已定案"）。①**复刻 11-20 那组配置**：用户指认"前天那次发送用 80 MHz、发 3.5、收 4"——11-20 日志原文为 `发送 24 MB / 7.55 s = 3.18 MB/s`、`接收 3.0 MiB / 0.61 s = 3.65 MiB/s`，配置 `phy=2 mcs=7 nss=2 collect_us=1500 capacity=256 ba_window=256`，另 00:40 的诚实口径是「活跃 9 段共 34 s（占空比 12%）、活跃段 2.98 MiB/s」。本轮把 wide 打开（`passthru_ht=0` → dmesg `chanmode 26 / center 5775 / Tx SS 2 Rx SS 2`），其余逐项对齐。②**四条证否**：(a) **ko**：随包 `152b93ad` 窄档 340 KB/s / 宽档 455 KB/s，自建 `eac00ca0` 40–50 KB/s；(b) **信道宽度**：宽档只 +34%（340→455），远不到 3 MB/s；(c) **BA 窗口**：把 `passthru_awdl_ba_window` 从 256 改成 64（正是 iPhone 在 ADDBA Request 里请求的值）并重启清会话，`requests 3→4` 确认重新协商成功，`ppdu_consumed_hist` 仍是 `1:877`；(d) **RX 重排**：`passthru_awdl_rx_order_ms 0→20`，`held=released=timeouts=0`，窗口一次都没兜住东西 ⇒ 帧根本没成批到。③**接收链是干净的**：`wonder0 rx 88 305 029 B / 71 825 包` → `awdltun0 rx 85 863 231 B / 71 828 包`，**97% 的字节、一一对应的帧数**；`invalid_msdu=dup_link=dup_buffer=bad_mpdu_tag=0`。④**量化模型（唯一闭合的那个）**：`ss -tin6` 在传输中读数 `mss:1386 rtt:19.018 cwnd:12 rcv_space:629244 rcv_rtt:1433 minrtt:10.108 app_limited` ⇒ **不是窗口卡死、也不是我们读得慢**（读慢了 `rcv_space` 会归零）。对端实际是「约 8 个 MSDU 一批、每 ~25 ms 一批」：`8 × 1488 B ÷ 0.025 s = 476 KB/s`，实测 470 KB/s。要 4 MB/s 只需每批 64 帧（BA 窗口本来就允许 64）。⑤**我方 ACK 队列确实堵**：`opairshare_awdl_flow_state: held=1 pending=52`——接收时队列里几乎全是纯 ACK，而 `collect_us=1500` 让它们先白等 1.5 ms（纯 ACK 60 B 永远够不到 `msdu_len>=1000` 的合包门槛）。改成 `collect_us=0` + `ackfilter=1` 后 `pending 52→0`，但**实测速率无变化**。⑥**结论**：慢的不是"收不到"，是"对端每批只肯放 8 帧"，而决定批大小的是它对这条 BA 会话的信任度——四条用户态/参数路径都已排除，剩下的在随包 ko 的 RX/TX-ACK 实现里（它的 `passthru_mon_rings / mon_chan_sync / mon_refresh` 在覆盖层源码中无对应物）。下一步是把这条 RX 血统搬进覆盖层，才有可改的 ko；在那之前不要再用参数试这几项。 |
| **2026-11-22 19:0x** | **【结论已被 20:2x 条取代：随包 ko 只回到 340–455 KB/s，不是"已解决"】接收慢的根因最终定案：自建 ko 的血缘问题，换回随包 ko 立即 12–20 倍提升**（把 §0 的"待复测"改写为已解决）。①**三轮受控 A/B**（同 iPhone、同类文件、同 `phy=2 mcs=7 nss=2`，判据是 rxtunmon 的 TUN rx 字节 + `ss -tin6` 的 `data_segs_in`）：**自建 ko + aux EHT80** → 25 KB/s / 18 段每秒；**自建 ko + aux 20MHz**（`passthru_aux_wide=0`）→ 15 KB/s；**随包 ko `152b93ad…`** → **309 KB/s 均值 / 447 KB/s 稳态峰值 / 200–307 段每秒**。54 MB 传 171 s。②**推翻本轮前面两个假设**：aux monitor 带宽（20MHz→EHT80）只把残缺帧从 41% 降到 17%，对速度无影响；ACK/slot 调度的 RTT 86ms、对端 `app_limited`、稀疏 peer_map 都是**自建 ko 收帧不良的下游症状**，不是独立根因。③**根因**：Plan.md 早有记录（11-20 23:5x / §6.1）——随包 ko 与覆盖层是**两条命名血缘**（随包 16 个 `OP13*` 标签、6 个参数被改名重写，覆盖层没有；自建那颗曾 `rx=0p/0B bad=13128`）。我们这颗自建 ko 虽经多次修复"能收帧"，但接收吞吐始终只有随包的 1/12~1/20。**任何用自建 ko 做的 RX 速率数字都不作数**。④**修复**：仓库 `kernel/qca_cld3_peach_v2.ko.gz.bak` 解压即随包裸 ko `152b93ad51c093be8ea54154121c4070573277f1711bb7d85deeb132b750c7a4`（20050688 B）；已恢复为正式载荷（gz sha `d773d676…`），`tools/awdl-stack.sh` 无需改（随包对不认的参数静默忽略、`TUNING ok`），**重编 APK 验内部 ko sha = 152b93ad 并装机 Success**。自建 ko 存为 `.gz.selfbuild-backup`、`aux_rx.h` 的 aux_wide 改动仍留在源码（默认 true，随包不认=静默忽略，将来可从 HEAD 重编再用）。⑤**判据**：`ss -tin6 | grep :8770` 的 `data_segs_in` 速率（>200/s 为正常）+ `rcv_space`（随包 629KB vs 自建 69KB）；真机 RX ≥300 KB/s 稳态。⑥**教训**：改驱动血缘前先确认"这颗 ko 历史上跑过多少"，别在错误的二进制上调参数——本轮在自建 ko 上耗了多轮 ACK/slot/capability 分析，全是它收帧不良的下游症状。 |
| **2026-11-22 18:1x** | **工程整理：kernel 移到仓库根 + 重编装 APK + 文档重写**。①**重编 APK 并安装**：新 ko.gz（`passthru_aux_wide`）打进 APK，解包验 `assets/kernel/qca_cld3_peach_v2.ko` md5 `823935e4…` 与 stripped ko 一致、SHA256SUMS 对得上；`adb install -r` Success。用户已卸载 app，将自己在功能页手点启动。②**移动目录**：`android/kernel/` → 仓库根 `kernel/`（PowerShell Move-Item，git mv 因文件占用报 Permission denied）；修复全部引用点：`android/app/build.gradle`（kernelSrc + 3 处注释）、`tools/kernel/{apply-source,verify-reproduce}.sh`、`tools/make-kernel-assets.sh`、顶层 README（链接+目录树）、`kernel/source/README.md`（2 处）。**移动后验证**：verify-reproduce `applied=10 failed=0`/`same 113 diff 0`、check-map `22/0`、从新路径重编 APK `BUILD SUCCESSFUL` 且新 APK 驱动 md5 正确并重装。③**README 重写**（328 行）：新增「这个目录是干什么的」「上游源码从哪拉、怎么编（完整流程）」——钉死 OnePlusOSS `oneplus/sm8750` + `oneplus_13_b.xml` 与三个 commit、构建环境/命令、8 条踩坑、复现判据；更新 qca 演进链（现役 20 039 576 B 是我们自建的 aux_wide 版，已真机验证不再"尚未验证"）。④**Plan.md 整理**：一分钟状态表重写（RX 根因=aux monitor 20 MHz、已修待复测）；正文 7 处旧路径更新（变更日志历史路径保留忠实记录）。⑤**Ubuntu 源码树确认**：`/mnt/opairshare-build/opairshare` qcacld 在、136 改动文件、dist 有今天新编 ko、manifest commit 与 README 一致。⑥**概念澄清**：发送 `passthru_awdl_phy=2`(HT) 与接收 monitor EHT80 不矛盾——前者是发帧 preamble（iPhone 非传输态只持 20 MHz，发 80 MHz PPDU 会互不可见 + 5-bit 字段装不下 EHT），后者是收帧信道宽度；vdev1 现为 26/EHT80/1SS，monitor 的 NSS 由上游 `wlan_hdd_add_monitor_check` 定、源码无设置点，2SS 是未验组合待需要时再 A/B。 |
| **2026-11-22 15:5x** | **接收慢的第二处结构瓶颈：aux monitor vdev1 写死 20MHz/11n，与 EHT80 主 vdev 不匹配**（改动重编中）。①用户指出主 vdev0 是 EHT80 2SS 而 aux monitor vdev1 不是，问能否一致——方向正确。②源码：主 vdev 走 `target_if_vdev_mgr_tx_ops.c:551` 的 `want_wide()` → `WMI_HOST_MODE_11BE_EHT80`(chanmode 26) 2SS；aux monitor（`opairsharerx0`）在 `wlan_hdd_opairshare_aux_rx.h:343-344` **写死 `CH_WIDTH_20MHZ` + `eHDD_DOT11_MODE_11n`** 1SS，dmesg 印证 `vdev=1 chanmode 24 Tx SS 1 Rx SS 1`。**接收方向正是走这条 monitor→wondertap0 转发路径**。③症状：传输中 `native_raw_seen=3860 ampdu=2278 incomplete=1582`（**41% 残缺帧**），符合「20MHz monitor 只听到 80MHz 帧切片」；RX 0.06–0.13 MB/s vs TX 3–4 MB/s。④**改动**：`aux_rx.h` 新增 `passthru_aux_wide`(bool,0644,默认 true)+`extern want_wide()`，wide 时 monitor 用 `CH_WIDTH_80MHZ`+`eHDD_DOT11_MODE_11be`（打 `OPAIRSHAREAUXWIDE` 日志），窄带保持原样；**运行时 echo 0 可零重编回退**（防 `cause=7003`）。注意 EHT 只在 `mon_ctx->phy_mode`(CSR) 有效，`passthru_awdl_phy` 拒绝 EHT=5。⑤**直接改 source/ 而非 git HEAD**：已 md5 实证设备 ko=仓库 gz=当前 source/ 产物，source/ 即现役基线，按 HEAD 编会丢 ba_amsdu=1 等现役改动。⑥构建链：`verify-reproduce.sh` applied=10 failed=0 / `total 115 same 113 diff 0`（唯一 diff=aux_rx.h）；`--overlay-only` 已同步；source-map sha256→`cf066351…`；`check-map.py` 22/0。 |
| **2026-11-22 15:2x** | **接收掉速根因收束 + 一条重大更正：设备跑的并不是旧驱动**（§3.6.12 待补）。①**更正上一轮结论**：`/vendor/lib/modules/qca_cld3_peach_v2.ko`（19737760 B / 11-13 02:19 / md5 `3477daad…`）里 `OPAIRSHAREBA` / `passthru_awdl_*` 字符串**全是 0**——它是**未打补丁的原厂驱动，根本没被加载**。真正加载的是 `/data/local/tmp/opairdrop/qca_cld3_peach_v2.ko`（20038648 B），md5 `469e70b5…` 与仓库 `kernel/qca_cld3_peach_v2.ko.gz` 解压后**逐字节相同**。ko 内 `MODULE_PARM_DESC` 是 `Advertise A-MSDU Supported in the ADDBA Response (0=never, default 1)`，与当前源码一致 ⇒ **设备上的驱动已是最新构建，`ba_amsdu=1` / `OPAIRSHARE_BA_WINDOW_MAX 256` / `OPAIRSHARE_ORDER_WINDOW 256` 全都在里面，不需要重编**。②**`amsdu=0` 的真实来源**：`opairshare_ba_stats` 的 `amsdu=%u` 打印的是 `READ_ONCE(passthru_awdl_ba_amsdu)` **活值**（`wma_opairshare_awdl_ba.h:69`），不是协商存档；加载时为 0 是旧版加载脚本 insmod 时显式传 0 所致，当前 `awdl-stack.sh:251` 的 `BA_AMSDU=${BA_AMSDU:-1}` 已是 1。运行期写 1 后 stats 立刻变 1。③ **`window_eff=64` 是 iPhone 在 ADDBA Request 里自己请求的窗口**（`if (param >> 6) window = min(window, param >> 6)`），协议正确；当前构建上限已是 256。**正式否决**「去掉 `:173-174` 夹取」这条待办——去掉等于答比 peer 请求更大的窗口，是违规。④**三组精确算术闭合**：TX `283 PPDU/s × 11454 B = 3.24 MB/s`（实测 3.24）；RX `336 PPDU/s × 1492 B = 501 KB/s`（实测 0.49）；`8 × 501 KB/s = 4.01 MB/s` ≈ 历史最好 4.25。`11454/1492 = 7.68 ≈ 8`。⑤**决定性计数器**：`ppdu_consumed_hist: 1:58818` 且 `ppdu_delivered_hist: 1:58818` —— **每一个 PPDU 恒为 1 个 MSDU，从不聚合**；参考实现在「1 MSDU/MPDU」档能跑 3.42 MB/s ≈ 2292 帧/秒，我们只有 336 帧/秒，**差的是帧发射密度**。⑥`aux_dropped=0`、`reo_nonraw=0`、`reorder held=0/skipped=0/timeouts=0` ⇒ 我们这边一帧没丢，不是接收侧丢包。⑦占空比 ≈ 1.7%（1546 B @ 1201 Mbps ≈ 10.3 µs + 前导/ACK ≈ 50 µs × 336/s），射频空闲 98%。⑧**用户态读路径排空**：HTTP 头 `read_line` 逐字节（`lib.rs:523`）是每请求成本；RX 主拷贝 64 KB（`cpio:836`）；进度回调约 7.6 次/秒。⑨`tx_wait_n=166226` 与七桶直方图之和精确相等 ⇒ 是全量普查；65% <500 µs、76% <1 ms，TX 闸门不是瓶颈。⑩链路重建后 `requests 11→12`，新 ADDBA 在 `amsdu=1` 下协商完成，**待一次真实 iPhone 发文件验证吞吐是否跳变**。 |
| **2026-11-22 15:4x** | **冷重启后必须显式 `AWDL_PHY=2 AWDL_MCS=7 boot`：默认 VHT 组合会崩固件**（§3.6.11 复现）。①现象：每次 `boot` 后约 0.8 s 出现 `Received firmware hang event` + mhi `cause=7003`，10 s 后 `cds_trigger_recovery_handler: WLAN recovery already in progress` → `Removed virtual interface` → awdld `reading the radio failed: Network is down` 退出，receipt 永远 `stack partial iface=absent`。②根因：`$STATE/wide` 存在时 `AWDL_PHY_DEF=3`(VHT)/`AWDL_MCS_DEF=9`，insmod 生效值 `passthru_awdl_phy=3 mcs=9 nss=2`；dmesg 主 vdev 0 `chanmode 26`(EHT80) 2SS、aux vdev 1 `chanmode 24`(VHT80) 1SS 混跑，正是已知崩溃形状。③**修法（零重编）**：`AWDL_PHY=2 AWDL_MCS=7 sh awdl-stack.sh boot` → `BOOT_RESULT stack up`、`phy=2 mcs=7 nss=2 ba_amsdu=1`、`hang` 计数不再增长、`requests=2` 在 amsdu=1 下协商完成。**注意 boot 脚本的 `AWDL_PHY_DEF` 与 insmod 的 `TUNE_INSMOD` 是两处赋值，冷路径实际用后者，别只看脚本顶部的默认值。**④教训：重启后 app 不会自动重 boot，只报 `announcer could not start on awdltun0`；必须人工按上面命令拉栈，或让用户在 UI 触发。 |
| **2026-11-22 11:1x** | **第三个泄漏修掉（流量控制的 `dev_hold`），并把固件崩溃夹到 0.9 秒**（新增 §3.6.11）。①**为什么 §3.6.10 修完还刷屏**：`wondertap0` 上有**两份** `dev_hold`，aux 路由一份（§3.6.10 已修）、流量控制一份（`wma_opairshare_flow_control.h:44`），后者唯一的 `dev_put` 在 `opairshare_flow_forget_locked():97`，而该函数有两道闸固件一死就永久成立：`accepting \|\| pending` 判 `-EBUSY`（这两个量只靠活着的 MAC 回执清理）、`flow_control_locked(dev,2)` 判 `-EIO`。②**走 `opairshare_pas_mgmt_stop()` 也救不了**：它第一步 `opairshare_bridge_pause_locked()` 里 `wait_event_timeout(..., 3000)` 等 `pending` 归零，而 `pending` 只在原生完成回调里减——必然 3 秒超时 `-ETIMEDOUT`，后面三步一步都到不了。③**修法**：新增 `opairshare_flow_force_forget_locked()`（只留 `if (!dev) return` 一道幂等闸，**故意不调 `flow_control_locked`**——netdev 正在销毁，`paused` 描述的队列状态即将不存在）+ `opairshare_pas_mgmt_abandon(id)`（`accepting=false` + force forget + 打 `OPAIRSHAREABANDON`；**故意不 `destroy_workqueue`**，RTNL 下 flush 一个往重启中 MAC 提交的 worker 停顿无界，靠随后的模块重载回收）；调用点两处都在 `wlan_hdd_wondertap.c`（`force_cleanup` 主修 + `__wlan_hdd_destroy_wondertap_intf()` 兜底，覆盖 create-failure 那条「start 后、失败前已收过帧」的路径）。④**三条判据**：`flow_state` 的 `held=0 puts>=1`、`dmesg` 出现 `OPAIRSHAREABANDON: … still_held=0 puts=1`、`grep -c "waiting for wondertap0"` 为 **0**。⑤**崩溃时间线夹到 0.9 秒**（全文只 1 次 hang event）：辅助 vdev 1（`opairsharerx0`）以 **1SS** 挂在 `chanmode 24 = 11BE_EHT80` 上（主 vdev 0 是 2SS），随后 5 ms 内 **8 条 `vdev_id 255 is not active`**，`4210.901582` 最后一条成功发送，152 ms 后 MHI `Unblock pending tx` → `Received firmware hang event` → `cause=7003`。⑥**A/B 开关做成脚本级零重编**：`AWDL_PHY` / `AWDL_MCS` 改由 `AWDL_PHY_DEF` / `AWDL_MCS_DEF` 兜底，`AWDL_PHY=2 AWDL_MCS=7 tools/awdl-stack.sh boot` 即可把「VHT preamble 崩固件」与「80 MHz join 崩固件」分离。⑦**核过一处假阳性**：`nss_mask = BIT(nss == 2 ? 1 : 0)` 得的 `BIT(1)=2` 对位图正是 2SS，**不是** 1SS，别改。⑧**未解**：`teardown failed:15` 的 15 不是 `-ETIMEDOUT`（110），返回值在 `unregister_ops` 中间被折过，确切赋值点未追到——不阻塞泄漏修复 |①**活体复现**：对**还没换掉**的旧驱动执行 `awdl-stack.sh stop`，180 s 不返回，`lsmod` 出现 `qca_cld3_peach_v2 … -1`（`MODULE_STATE_GOING`），`dmesg` 刷 `unregister_netdevice: waiting for wondertap0 to become free. Usage count = 2`。`-1` 证明 `delete_module` 已进门、卡在 `unregister_netdevice()` 等那个永不归还的 `dev_hold`——§3.6.10 的两处 `opairshare_pas_aux_pre_stop()` 从「应该加」变成「必须加」。②**重编**：`apply-source.sh --check` → `applied=10 failed=0`；`--overlay-only` → 69 files；`build-wlan-baseline.sh` → `Elapsed 932.838s` / `Build completed successfully`。③**打包**：`llvm-strip --strip-debug` 513 436 648 → 20 037 200，`gzip -9 -n` → 5 280 202，sha256 `eac00ca0…`。**坑**：`wsl.exe bash -lc` 吞 `$VAR`，`$S --strip-debug …` 必报 `--strip-debug: command not found`（exit 127），只能写全绝对路径。④**编进去了的证明**：`llvm-size` 比对 `.text` 旧 9 152 927 → 新 9 153 803（+876 B），`.data`/`.bss` 逐字节相同；`vermagic` 两边一字不差。⑤**README 载荷表订正**：qca 那行原写 `20 050 688/5 282 983/152b93ad…`，与仓库实际文件（`20 036 152/5 279 815/6978cc8a…`）不符——上次换载荷没回头改表；其余四行哈希与实际相符，反证只有这一行是陈的。⑥**APK**：`stageKernelAssets` 在 `preBuild` 从 `*.ko.gz` 解出裸 `.ko` 并**重新生成** `SHA256SUMS`，所以 `assembleDebug` 产物自带新驱动；**只推载荷不重编 APK，App 下次启动会用 assets 把旧驱动盖回来**。`assembleDebug` 成功（58 s，19 571 342 B），`adb install -r` → `Success` |
| **2026-11-22 09:41** | **修复版驱动重编成功并装进 APK；旧驱动上的引用泄漏被活体复现**（§3.6.10 末两段）。①**活体复现**：对**还没换掉**的旧驱动执行 `awdl-stack.sh stop`，180 s 不返回，`lsmod` 出现 `qca_cld3_peach_v2 … -1`（`MODULE_STATE_GOING`），`dmesg` 刷 `unregister_netdevice: waiting for wondertap0 to become free. Usage count = 2`。`-1` 证明 `delete_module` 已进门、卡在 `unregister_netdevice()` 等那个永不归还的 `dev_hold`——§3.6.10 的两处 `opairshare_pas_aux_pre_stop()` 从「应该加」变成「必须加」。②**重编**：`apply-source.sh --check` → `applied=10 failed=0`；`--overlay-only` → 69 files；`build-wlan-baseline.sh` → `Elapsed 932.838s` / `Build completed successfully`。③**打包**：`llvm-strip --strip-debug` 513 436 648 → 20 037 200，`gzip -9 -n` → 5 280 202，sha256 `eac00ca0…`。**坑**：`wsl.exe bash -lc` 吞 `$VAR`，`$S --strip-debug …` 必报 `--strip-debug: command not found`（exit 127），只能写全绝对路径。④**编进去了的证明**：`llvm-size` 比对 `.text` 旧 9 152 927 → 新 9 153 803（+876 B），`.data`/`.bss` 逐字节相同；`vermagic` 两边一字不差。⑤**README 载荷表订正**：qca 那行原写 `20 050 688/5 282 983/152b93ad…`，与仓库实际文件（`20 036 152/5 279 815/6978cc8a…`）不符——上次换载荷没回头改表；其余四行哈希与实际相符，反证只有这一行是陈的。⑥**APK**：`stageKernelAssets` 在 `preBuild` 从 `*.ko.gz` 解出裸 `.ko` 并**重新生成** `SHA256SUMS`，所以 `assembleDebug` 产物自带新驱动；**只推载荷不重编 APK，App 下次启动会用 assets 把旧驱动盖回来**。`assembleDebug` 成功（58 s，19 571 342 B），`adb install -r` → `Success` |
| **2026-11-22 09:1x** | **「两机互不可见」的真正第一根因找到了：每一帧都在 `bind` 处被丢光**（新增 §3.6.10，并取代 §3.6.8 的因果链）。①**等值证据**：`bridge_dropped == flow.errors == 270936` 且 `accepted == 0`；`stops/wakes/releases/puts/held` 全 0 ⇒ `flow.errors` 只能来自 `bind`，且与 `bridge_dropped` 一一对应。②**根因**：补丁 `0006` 刻意给 PAS tap 只分配 **1** 个 TX 队列（`wlan_hdd_main.c:7455` 的 `? 1 : NUM_TX_QUEUES`，单 FIFO 保证 AWDL 单 TID 有序），而 `wlan_hdd_tx_rx.c:1120` 的准入校验要求 `real_num_tx_queues == NUM_TX_QUEUES`（**17**）。`1 != 17`，校验永远不可能通过，`skb` 被 `dev_kfree_skb_any()` 吞掉且不回退普通路径。③**单变量实验确认**（零重编，仅 `passthru_awdl_backpressure=0` 短路 `bind`）：`accepted 0 → 21`、`reject_invalid 每 5 s +129 → 0`、`bridge 0/270936 → 21/8`、`flow.errors 270936 → 0`。④**修复**：放宽判定到 1..17；新增 `hdd_tx_subqueue_idx()` 夹取所有 AC 索引（**1 队列 netdev 上 `netif_wake_subqueue(dev, 8..16)` 会越界写 `dev->_tx[]`**，且原生 `wlan_hdd_netif_queue_control` 也会跑在 PAS 适配器上，所以必须改在 helper 层）；单队列时 wake 走一次 `netif_wake_queue()`。⑤**同轮修掉拆除泄漏**：`opairshare_pas_aux_pre_stop()` 定义了却从未被调用，SSR `force_cleanup` 路径留下 `dev_hold(wondertap0)`，导致 `unregister_netdevice: waiting for wondertap0 to become free. Usage count = 2` 无限刷屏；改在 `__wlan_hdd_destroy_wondertap_intf()`（三条拆除路径唯一汇合点）+ `unregister_ops` 分支提前摘除 |
| **2026-11-21 15:04** | **`RoundTrip` 补上汇总行，五段自报相加从此等于数行**（§6.5 / §9 相应改写）。①**改动 12 行**：`RoundTrip.java` 加 `private static int checks`，`check()` 里 `checks++`，收尾由 `all checks passed` 改为与另外四类同格式的 `ok, N checks`（失败时 `FAILED N of M`）。②**复核**：`bash crates/ffi/jvm/check.sh` → `exit=0`，五段汇总 `38 / 185 / 54 / 48 / 69`，相加 **394**，与 `grep -c '^  ok  '` 的 **394** 完全一致——**两种数法第一次互为校验**，此前差 38 的那个坑（`356` vs `394`）从数据源头上消失了。③**为什么改而不是留作反面教材**：上一轮 §6.5 写的是「这条没改，留着当反面教材」，但留一段「数不出来」的检查除了继续制造错误数字没有别的作用；反面教材已由 §6.5 与 §9 的正文承担，不需要靠一个真缺陷去演示。④**同一根因的旁证仍在**：`cargo test` 那次也是「汇总相加 vs 数行」差出来的（文档 420 / README 427 / 实测 **431**），只是那边没有可修的源头 |
| **2026-11-21 14:5x** | **把三个过期数字实测校准，并补上四条正文缺口**（§5.4 / §5.6 / §6.2 / §6.5 / §9 / §11 各一处）。①**`check.sh` 真实条数 = 394**，此前文档里的 355 / 375 / 381 全部偏小。**根因不是数错了，是数法错了**：`RoundTrip` 是全流程中**唯一不打印 `ok, N checks`** 的一段（成功只印 `all checks passed`），所以「几段汇总行相加」的算法天生漏掉它——`185+54+48+69 = 356`，与 394 差 38，正是 round-trip 那一段。判据改成 `grep -c '^  ok  '`。②**`cargo test --workspace` 实测 431 passed / 0 failed / 1 ignored**（18 个 `test result:` 行相加；文档写过 420、`README.md` 写过 427，都已过期）。③**补 §5.6 第五条**：`flags` 只能照抄真机——`2185 = 0x889` 那版算的是「在 137 上加 DVZip(0x002) + Pipelining(0x004)」，实际两位**一个都没置上**，多出来的是 `0x800`；真机是 `13311 = 0x33ff`。④**补 §6.2 两条**：弹窗慢 = iOS 在构造 `/Ask` 之前转码（`/Discover → /Ask` 12.71 s，而 `FileSize` 已是转码后尺寸）；「五轮 A/B 全失效」= 设置行 `receive.media_capabilities` 非空时**整个遮蔽**代码常量，设备上一直塞的是只有 `public.heif-standard` 的原文，我们那份含 `public.mpeg-4` 的常量从未到过对端。⑤**补 §5.4 一条**：把「弹窗慢不在这条链路里」写进拒绝/超时那一节的末尾，免得下次又去优化握手。⑥同步改 `README.md` 两个数（427→431、355→394） |
| **2026-11-21 14:4x** | **内核源码补齐收官：`pristine HEAD + overlay + 9 个补丁` 逐字节 == 实际构建树，`112/114`**（判据与用法全文进 §8.3.1）。①**判据从「编出来能跑」换成「字节对上」**：`total 114  same 112  diff 0  missing 0  expected-absent 2`。114 是构建树相对 HEAD 的全部改动文件；剩 2 个是 `driver/legacy/` 里归档的死文件，故意不复现，清单 `source/expected-absent.txt` **带 sha256**，冷验证会核——「只有真实树那份仍与记录吻合才豁免」，货变了落 `bad_excuse` 不静默放过。②**顺带揭出三个真 bug**（全在 §6.5）：**(a)** `apply-source.sh` 的 overlay 路径一直是错的（`source-map.json` 的 key 已含 `driver/`，脚本又拼一层成 `source/driver/driver/x.c`）——**这个脚本从写出来到这次冷验证，overlay 一步一次都没成功过**，直到退出码第一次变成非零才暴露；**(b)** 顺序必须**先补丁后覆盖层**（补丁上下文是上游原文、覆盖层是最终全文，反过来 `0005/0006/0007` 必然 `patch does not apply`）；**(c)** `qdf_nbuf.c` 缺 `qdf_nbuf_get_radiotap_len()` 函数体，只在**废弃的 candidate 版** `0007` 里有，`attribute.py` 匹配到 candidate 误判「已覆盖」→ 补成独立 `0010`，不动 `0007` 一个字节。③**`--check` 不能 `git apply --check`**：`0006` 创建 `wlan_hdd_wondertap.c/.h`、`0007` 再改，dry-run 看不到前序产物、干净树上必报假警报；改成「导出 pristine 到临时副本 → 真跑一遍 → `trap EXIT` 删掉」，真实树不 touch（跑完 `ls -d $ROOT/.apply-check*` 为空、150205 个改动不变）。④**可复现性前提是清单进仓库**：`verify-reproduce.sh` 原读 `.workbuddy/tmp/kernel-sync/src114.txt`，而 `.workbuddy/` 被 `.gitignore:11` 忽略，**第三方永远拿不到**——已发布为 `source/expected-files.txt`（114 行）。⑤**新增/改动**：`tools/kernel/verify-reproduce.sh`（新，三步入参 + 白名单核 sha）、`apply-source.sh`（重写：顺序、路径、`--check` 演练、四个落点 marker）、`source/patches/0010-…`（新）、两份清单（新）；文档 `kernel/README.md`（45→46 覆盖文件、7→9 补丁、把「没走到的一步」改写为已完成）、顶层 `README.md`（「打过补丁的这颗还没有从源码复现」**已作废** → 112/114；`check.sh 375` 改回正确的 **355**）、`source/README.md`（删掉指向**不存在**的 `../docs/BUILD.md` 的三个链接，新增用法与复现两节）。⑥**收尾核查**：`source/tools/` 与 `tools/kernel/` 的七个上游脚本逐字节全同（后者多两个我们自己的）；`grep workbuddy\|tmp/kernel-sync` 在发布脚本/清单/README 里零命中 |
| **2026-11-21 13:0x** | **`check.sh` 的真实条数是 355 不是 375/381；`SettingsCheck` 从不检查 `failures`（本轮最值钱的坑）**（进 §6.5）。①**`check()` 数着 `failures`、打印 `FAIL`，`main` 从来不读，退出码永远 0**——`StackCheck`/`RadioCheck`/`IdentityCheck` 都有 `System.exit(1)`，只有 `SettingsCheck` 漏了，于是一条真失败的断言在日志里只是个 `FAIL` 字样，`check.sh` 照样绿。发现方式纯属运气（翻日志 grep 顺手看见）。已补 `System.exit(1)`（184→185），并记下检查命令 `grep -L "System.exit" *Check.java` 应为空。②**条数口径**：`54 + 185 + 54 + 48 + 69 = 355`（round-trip / settings / identity / radio / stack），**375 与 381 都是只数了三段编号汇总**，以 `grep -c "  ok  "` 为准。③**`recoveredFrom()` 必须看磁盘不能记字段**：第一版存 `Path recoveredFrom`，只有亲手改名的那个实例知道，而真机上页面一个字没提——启动时 `Tuning.read()` 先读了一遍把机会用掉了，设置页再来读时文件已不在、字段是 null。改成 `Files.exists(aside(file))` 后谁先读、读几次都不影响结论。④**`SettingsScreen.open` 读不出来就改名挪开**：`Files.move` 到 `settings.conf.unreadable` 再按首次启动读一遍——**改名只要目录写权限、不要文件读权限**，这正是它在 `EACCES` 下走得通的原因；`NotFound` 不触发（`settings.rs:119` 单独返回空表）。真机验过（`chown 10329` 造旧 uid）：文件被改名、977 B 原样，首页「监听中」、功能页完整。⑤踩坑：`cargo` 报 `target/debug/.cargo-lock 拒绝访问 (os error 5)`，ACL 正常、无 cargo 进程、`rm` 也删不掉，**PowerShell 的 `Remove-Item -Force` 能删**（删 `.cargo-lock`/`.cargo-artifact-lock`/`.cargo-build-lock` 三个即恢复） |
| **2026-11-21 12:0x** | **事故：`su chown u0_a329` 把 `settings.conf` 属主写坏，整个应用瘫痪**（进 §6.4/§6.5）。现象：首页「接收文件」右侧红字「异常」+ `设置读不出来，连接被拒：… Permission denied (os error 13)`，功能页只剩一张报错卡、设置页只剩「删除该文件并重试」。①**根因**：`u0_a329` 是**当时**的 uid，而应用 11:36 前后重装过、重启后变成 `10456 (u0_a456)`；`chmod 600` + 旧属主 = 应用自己 `open()` 拿到 `EACCES`（`stat` 复现：文件 `10329:10329 600`、目录 `10456:10456`）。②**教训一（务必记住）：绝不要手写 `chown u0_aNNN`，重装会换 uid。** 写私有目录只用 `run-as`（属主自动正确），且必须走 **stdin 管道**——`run-as` 是 app 身份、没有 `sdcard_r`，`adb push /sdcard/…` 再 `run-as cat` 那条路 `Permission denied`。③**教训二（设计缺陷，已改）**：不是一个 chown 的问题，是「一个文件读不出来 = 接收功能不存在」。`Listener.serve()` 原来 `catch` 到就 `setState(..., true); return;` 直接拒连接，`SettingsScreen.open` 一抛三页全废。现在 `SettingsScreen.defaults(Path)` 只读 catalog 全走默认值；`serve()` 记一句 `Log.i`、`setState("设置读不出来，正在用默认值接收", false)` 并**继续接收**（设置页仍照常报错并给删除按钮，人还能真正修好它）。`SettingsCheck` 加 `anUnreadableFileStillHasAUsableTable`（6 条）：锁住「回落表每行都在」「`receive.directory` 为空（落点回到按扩展名分桶）」「`receive.overwrite` 为 false」「不是 dirty」。④作不可读文件用 `Files.createDirectories(home.resolve("unreadable.conf"))` 拿**目录**冒充，**别用 `chmod 000`**——这个检查在 Windows 上也跑，`setPosixFilePermissions` 直接 `UnsupportedOperationException` |
| **2026-11-21 11:0x** | **`flags` 的参照值挖出来了：`13311 = 0x33ff`，而上一版算的 `2185` 两位一个都没置上**（进 §5.4/§5.9 口径）。①翻 `.workbuddy/tmp/air1.pcap` 的**原始字节**，发现里面有两种 `flags`：我方（`c2:1e:af:43:00:d0`）是 `fl=137`，对端 iPhone（`86:b2:eb:36:35:3d`，pkt#3，Apple vendor-specific action frame，OUI `00:17:f2` / category `0x7f` / `rtlen=48`）是 `flags=13311`，TXT 里只有服务 UUID `fb21a1ba-…` 和 `flags=13311`、**没有 `h=`**。②**关键算术**：`Responder.java` 上一版注释说 `2185 = 0x889` 是在 `137` 上加 `SupportsDVZip(0x002)` 与 `SupportsPipelining(0x004)`，实际 `137\|0x2\|0x4 = 143 = 0x8f` 才是想要的，而 `2185 & 0x002 = 0`、`2185 & 0x004 = 0`、`2185 ^ 137 = 0x800`——**多出来的是 `0x800`，两位想要的一个都没置上**。所以历史上那次「补 DVZip/Pipelining」的实验结论无效，这条路等于从没真正试过。③已改 `FLAGS = "13311"`，设备实测 `tcpdump -i awdltun0 udp port 5353` 确认广播变为 `fl=13311` / `flags=13311` / `h=857B86CE…`。注：`0x33ff` 还含 `0x040 SupportsIris` / `0x100` / `0x200` / `0x1000` / `0x2000`（含义未知，照抄真机），清掉的只有 `0x800`（真机也没有，是 `2185` 那次误加） |
| **2026-11-21 03:0x** | **弹窗慢 / 文件缩水：拿到字节级定性，并挖出「让前五轮实验全失效」的错误**（进 §6.2/§5.5）。①**首次把 `/Ask` 原文落盘**（`.sdcard/Download/.ask-last.bin`，55510 B，bplist）：`Items.Files[0].FileSize = 27822213`、`ConvertMediaFormats = false`、`BundleID = com.apple.mobileslideshow`。时间轴（`.paths-last.txt`，微秒级）：`/Discover → /Ask` **12.71 s**（转码窗口）、`/Ask → /Upload` 2.72 s。**关键推论**：`/Ask` 里的 `FileSize` 已经是转码后的 27.8 MB，即 iOS 在**构造 `/Ask` 之前**就完成了重编码；`ConvertMediaFormats=false` 说明它不是「收到应答才发现要转」而是自己早就决定了——能力声明确实在 `/Discover` 被读到，但它**无法表达对「已经选定要产出的容器」的反对**。局面因此定死：93 MB 那个视频我们没有能力让它原样过来。②**前五轮全失效的真凶**：`receive.media_capabilities` 这行设置非空时**会覆盖代码常量**（`crates/ffi/src/settings.rs:263` 的 `trimmed` 语义）。设备上连续几轮塞的是上一轮抄来的真机 1295 B 原文，而那份原文的 `ContainerFormats` 只有 `public.heif-standard`、**没有 `public.mpeg-4`**——代码常量（347 B，含 `public.mpeg-4`）从未到过 iPhone 手上。已清空该行，现在设备真正走 `opairdrop_airdrop::MEDIA_CAPABILITIES`。③**三处文档纠错**：`lib.rs` 原把「21.9s / 41.5s / 601.9s」写成「已修复的证据」，实际那组数是**症状描述**；`settings/src/lib.rs` 原写「留空用内置值（声明 HEVC，对端不再转码）」与实测相反；`Responder.java` 的「iOS 靠 `model` 字段保留 HEVC」被实测证伪。④**取证代码已全部撤回**（`server.rs` 的时间戳块、`browse.rs` 的 TXT 全键落盘、`send.rs` 的 `.discover-caps.bin`/`.discover-answer.bin`、`Responder.java` 的 `Native.mdnsRecords`、两个 examples），与 HEAD 一致。**注意 `server.rs` 的 revert 让 `.paths-last.txt` 不再产生**，下次要时间戳得重新加回那段 |
| **2026-11-20 23:5x** | **结论翻转：能跑 80MHz 的是「随包模块」，不是自建那颗；而随包模块自己就能 80，不用改驱动**（进 §6.1 四行）。①**两次干净 A/B**（同脚本、同 App、同对端，判据是 daemon 的 STAT 行）：自建 `02eac799` 20MHz → `rx=0p/0B bad=13128`；随包 `152b93ad` 20MHz → `rx=15p/4304B bad=1`。**自建模块连 20MHz 都收不到有效帧**（`opairsharerx0` 在网卡层有计数、host 侧全是垃圾），所以「建得起来、传不了数据」——今天早先那次 443 kB/s 是随包模块跑出来的。→ **任何用自建模块做的速率测量都没有意义。** ②**反转点**：随包模块用 `passthru_ht=0` 建链自己就走到 80MHz，`send_vdev_start_cmd_tlv: chanmode 26 (11BE_EHT80), center_chan 5775, Tx SS 2, ch_info 0x1a, bad=0`；而 `passthru_ht=1` 是 `chanmode 5 (11NG_HT20)`、`center 5775` 被丢。随包这段代码等价于覆盖层的 `diag&1` 分支，但它的 0 分支走 11be 而**不是**拒绝宽信道。③**实测**（App 自己发起，2 文件 112 MB）：`tx=95580p/126784269B`、`bad=0`、`awdltun0` 约 **1.28 MB/s**（HT20 1SS 基线 443 kB/s 的 2.9×），包均 1326 B 说明还没吃满聚合。④**固化**：`tools/awdl-stack.sh` 在 `$STATE/wide` 存在时 insmod 传 `passthru_ht=0` + `passthru_chan_11ac=1`，链路起来后在 `feed` 之后、首次 `up` **之前**调 `wonder-ven <if> freq 5745 2`（wonder.ko 只在 tap 未激活时缓存 `SET_FREQUENCY`；任何 `down→up` 都会把 IBSS join 弄丢，**不能「先起 20 再 retune」**）；`kernel/qca_cld3_peach_v2.ko.gz` 换回随包字节、自建模块不再进 APK。⑤**发现面**：`_airdrop._tcp` 广告是 **Java 的 `Responder`** 做的（每 3 秒一次，绑 `awdltun0`），不是 awdld；而且它只在进入「接收」时尝试**一次**，那一下 tunnel 不在就永久放弃（`announcer could not start on awdltun0 — 没有网卡 awdltun0`）——这是「看不到 air」的真正原因，修法见同日 01:2x 条 |
| **2026-11-20 21:1x** | **`-110` 的真因找到了：映射写到了**不参与链接**的那份 `target_if_p2p.c`**（进 §6.1，并催生 `check-map.py`）。①现象链：`OPAIRSHARERXFILTER: set-submit status=0`（命令发出去了）→ **没有 reply** → `OPAIRSHARERXAUTO: start … rc=-110` → `ip link set wonder0 up` 连试 6 次 `I/O error`。`-110` 是 `wait_event_timeout(opairshare_rx_filter_wait, 2000ms)` 超时；`opairshare_rx_filter_used` 一次就置 1，**每个会话只有一发，超时不能靠重试救**。②先排除宏：`CONFIG_DRIVER_PASSTHRU_MODE=y` + `CONFIG_WONDER_SUPPORT=y` → `config_to_feature.h:3155` 把 `DRIVER_PASSTHRU_MODE` 置 1，`#ifdef` 那段 hook 本该被编进去。③**问题在映射**：平台树里有两份同名文件——`qcacld-3.0/components/target_if/p2p/src/target_if_p2p.c`（`wlan_qcacld3_modules.bzl:575` 登记的就是这份）与 `qca-wifi-host-cmn/target_if/p2p/src/target_if_p2p.c`（`cmn/` 下只编 `dp/` 与 `target_if/mlme/`）。`source-map.json` 指到后者，于是 `opairshare_awdl_rx_filter_reply()` 被拷进一个**不参与链接**的文件：模块照编照装照跑，hook 一次没执行，等待队列没人 `wake_up_all` → 超时。两份文件除这段 hook 外**逐字节相同**，肉眼比对根本看不出来。④**判据不是目录名像不像，而是 `*.bzl` 的清单**（bzl 用相对 `wlan/` 的路径：`qca-wifi-host-cmn/<x>` → `cmn/<x>`，`qcacld-3.0/<x>` → `<x>`）。按这个规则把 45 条全查一遍：**22 个 `.c` 里 21 个对、只有这一个错**。⑤新增 `android/kernel/check-map.py`（`--root` 默认 `/mnt/opairshare-build/opairshare`，期望 `.c listed: 22   .c dead: 0`）。诊断埋点用 `target_if_fatal`（= `QDF_TRACE_FATAL`，一定打）打 `OPAIRSHAREEVT`，因为 `target_if_debug/err` 的默认级别会被过滤、当不了判据。⑥**结果**（第一颗真正打通的模块，sha `6920fc43…`）：`OPAIRSHAREEVT: mac-rx-filter vdev=0 status=1 len=12` → `reply … poison=0` → `RXAUTO … rc=0 active=1` → `WONDER0_UP / AWDL_LINK_UP / HAL_RUNNING`，`opairsharerx0` 计数 12945 持续增长 |
| **2026-11-20 20:3x** | **对齐上游镜像：结论是「本来就对齐」，却顺手挖出崩溃的真因**（进 §6.1）。①`git clone --depth 1` 拉 `Weverses/OPAirDrop`，`kernel/driver/` 44 个文件与我们 `kernel/source/driver/` **逐字节只有 3 个不同**（我们自己改的两个 + 上游 HEAD 比镜像少 661 B 的 `wma_opairshare_metrics.h`）。②**一开始 `cmp` 报 44/44 全不同是骗人的**：上游是 CRLF、镜像是 LF，每个文件大出「行数」那么多字节——用 `diff <(tr -d '\r' <up) <(tr -d '\r' <ours>)` 比才是真结果。**以后比上游先剥 CR。** ③**崩溃真因（比「6 个参数」更硬）**：`send_vdev_create_cmd_tlv` 里自建模块是 `type 9, subtype 0`，25 ms 后就是 `Received firmware down indication`；随包是 `type=7`（带 `OP13CREATE` 前缀）。`type 9` = `WLAN_VDEV_MLME_TYPE_PASSTHRU`，**原厂固件没有这个类型、创建即断言**。类型由 `mlme_get_vdev_types()` 决定，而 `components/mlme/core/src/wlan_mlme_vdev_mgr_interface.c` **不在覆盖层里**，所以自建模块一直拿原厂那句 `case QDF_PASSTHRU_MODE: *type = PASSTHRU`。已把该文件补进覆盖层（第 45 个）+ `module_param(passthru_fwtype, uint, 0444)` 默认 `NDI`(7)。④**效果**：设备不再崩，`OPAIRSHARENDI: create DEFAULT self peer vdev=0` 出现，`wonder-ven` 四个子命令全 `rc=0`——但紧接着卡在 `-110`（见同日 21:1x 条）。⑤**旁证：随包字节出自另一条命名血缘，别再指望「补参数」复现它**：随包有 16 个 `OP13*` 日志标签，覆盖层一个都没有；6 个参数在上游被**改名重写**（`passthru_tx_audit → passthru_awdl_rx_audit`、`passthru_mon_* → passthru_rx_diag_budget / passthru_flow_trace_budget`，`mon_rings/mon_refresh/mon_chan_sync/wmi_mgmt` 无对应物）。**未知参数内核不会拒绝**（insmod 仍 0、只在 dmesg 留一句），所以自建模块是带着默认值跑起来的——`passthru_fwtype=7` 这个决定固件形态的开关根本没生效 |
| **2026-11-20 17:5x** | **两条重编铁律，外加三条只有从零重编才会撞见的坑（用一次设备重启换来的）**（进 §6.1/§6.5）。①**铁律一：重编必须以 `git show HEAD:` 的驱动源码为基线**，不能直接在工作区上叠改动。工作区里压着前几轮 VHT 实验的全部残留（`passthru_vht80/vht20/ht2ss/vht1ss` 四个参数、`diag&4/8/0x20/0x40` 分支、`nss`/`ldpc` 补发、`bw_mask`、BA 256），照它编出来的模块**不等于「现役模块 + 本次改动」**，一上机就在 init 阶段崩，而崩溃会被误记到「本次改动」头上。HEAD 才与现役 `.ko` 对得上（`diag` 只返回 bit0/1、channel-change 用 `passthru_ht ? 11n : 11be`）。②**铁律二：`passthru_vht80=1` 会让链路起不来**——它除了把 channel-change 切到 11ac，还会命中 `__wlan_hdd_set_wondertap_channel` 里「INIT 阶段跳过信道切换」的早退分支，之后 `ip link set wonder0 up` 报 `RTNETLINK answers: Operation not supported`、`wondertap: Vendor init failed: -95`，重试 6 次 `link did not come up`。所以另立 `passthru_chan_11ac`，只改 channel-change 的 dot11 模式、init 与 START 一律不碰。③**`modpost: "opairshare_passthru_monitor_active" undefined`**：`wlan_hdd_wondertap.c` **只用不定义**，`source/` 与 WSL 全树都搜不到定义、git 历史里从来只有声明。原厂 `.ko` 里它的函数体（`0xaf90`）是 `adrp x8,0xa000; ldrb w0,[x8]; ret`——读一个 `.bss` 字节标志，而树里没有任何代码写它，运行时语义就是「永不活跃」。已补同名 out-of-line 定义 `return false;`，保持模块符号表与原厂一致，**以后再从 HEAD 重编这个定义必须留着**，否则每次都在 modpost 挂。④**`source-map.json` 会被 Windows 的文本模式写成 CRLF**：用 `json.dump` 更新后必须 `open(...,'w',newline='\n')`，否则 178 行整篇都算改动、diff 里看不出到底改了哪三个校验和。⑤**11ac 只许用于运行时 retune**：`__wlan_hdd_set_wondertap_channel` 多一个 `runtime_retune` 参数，`start_ap` 传 `false`（保持原厂 11n/11be 路径、与今天能跑通的配置逐字节一致）、`set_freq` 传 `true`；启动永远 20MHz，宽信道只在链路起来之后由 `wonder-ven <if> freq 5745 2` 引入 |
| **2026-11-20 17:2x** | **重要更正：上一轮「固件拒绝任何偏离 HT20/1SS 的 vdev START」这个结论是错的，别再引用**（进 §6.1 末条）。①重读 `dmesg-vht80-2/3.txt` 与 `dmesg-ht2ss.txt`：`send_vdev_start_cmd_tlv` 打的是 `req->channel.phy_mode`（`WMI_HOST_MODE_*`）——**16 = `11AX_HE20`，`WMI_HOST_MODE_11AC_VHT80 = 10`**。②**「80MHz 从没试过」也是错的**：`dmesg-vht80-2.txt` 里有一次 chandef 完全正确的尝试（`freq 5745 chanmode 10 ch_info 0xa center_chan 5775 Tx SS 2 Rx SS 2`），64 ms 后 `MHI SYS ERROR / RAMDUMP`，再 400 ms 才回 `status=6` 并 `[SSR]`。③**但崩因不是 chandef**：崩点固定在 `OPAIRSHAREVHT:` 打印后 **65 ms**，那条日志来自 `wmi_vdev_param_nss` + `wmi_vdev_param_ldpc` 两次 VDEV_CMD；同一份 START 参数在没有该分支的两次运行里完全正常。`dmesg-vht80-3.txt` 是**另一个 build**（`OPAIRSHAREVHT80INIT` 打了但 START 仍是 `chanmode 16`，说明覆盖没生效），两种崩溃并存、别把两份日志混为一谈。④正确 80MHz chandef 的字段名是 **`cfreq1`/`cfreq2`**（不是 `band_center_freq1`）：`mhz=5745`、`cfreq1=5775`、`cfreq2=0`、`phy_mode=WMI_HOST_MODE_11AC_VHT80`(10)。⑤现役模块在更早一层挡着：`wonder-ven wonder0 freq 5745 2` 返回 **-95 EOPNOTSUPP**，来源是 `__wlan_hdd_set_wondertap_channel` 里的 `opairshare_pas_rx_fixed_channel`——aux RX 只认 `bandwidth_enum=0`；`freq 5745 40` 的 -22 是因为带宽参数是**枚举**（0=20/1=40/2=80）不是 MHz。⑥**`android/kernel/source/` 比设备上那个 `.ko` 新得多**（`source/` 里有 `passthru_vht80`/`OPAIRSHAREVHT`/`diag&1` 分支，设备模块一个都没有）——**不能用 `source/` 推断设备模块的行为，只能实测** |
| **2026-11-20 16:5x** | **16:50 的「和 iPhone 互不可见」定案：是我改的 insmod 参数，不是速率**（进 §6.1 首条链）。①现场读数分得很清楚：**听得见**（`peer=86:b2:eb:36:35:3d`、`err` 收敛到个位数 TU、`anchored` 与 `other` 同步涨约 5 帧/秒），但**它一个字都不回**（`rx=0p/0B` 从头到尾没离开 0，`bad=0` 说明不是「收下来解析失败」）。空口抓包（`wondertap0` 8 秒 / 236 帧，按 TA 分类）把责任划死：我方 181 帧含 8 个数据帧（解开是**格式完全正确**的 mDNS 应答：`PTR _airdrop._tcp.local` → `7f5ed39dbd10`、`SRV … port 8770`、TXT `h=857B86CE… fl=137`、`AAAA air.local`），iPhone 54 帧**全是 Action subtype 13 的 PSF/MIF 同步帧、一个数据帧都没有**——它的 AWDL 在线（约 7 帧/秒），但 AirDrop 服务没起来，连自己的 mDNS 通告都没发过。②顺带纠一条：**`a3=BSSID` 不能当「它加入了我们的网络」的证据**，AWDL 的 action 帧按惯例一律带那个固定 BSSID（OWL 同做法）。③**真凶是脚本里两行 insmod 参数**：`passthru_awdl_phy=3 passthru_awdl_mcs=9`（我改的）vs `phy=2 mcs=7`（HEAD 原版）。它们不只是「发送速率」——驱动在 **vdev init 时**就把 VHT 能力摆了出去，对端因此解不出我们的帧。④**关键区别，别再踩**：光用 `wonder-ven wonder0 rate 1 0 1 1 7` 在运行时改是**没用**的（vdev 早已 init 完），必须改回 insmod 那行**并重启链路**。⑤**只改设备上的脚本也不顶用**：`DriverSetup.unpack()` + `sha256sum -c` 会在 App 每次启动时把 APK 资产覆盖回去，而资产就是 `tools/awdl-stack.sh` 本身（`app/build.gradle` 的 `stackScript`）——要还原就得**改仓库脚本 → 重编 APK → 重装**，三步缺一不可。⑥恢复后实测（iPhone→本机，29 文件 / 74.8 MiB）：四段 `446/441/444/441 kB/s`，即 HT20 1SS MCS7 的基线上限约 **443 kB/s ≈ 3.5 Mbps**，稳定不抖 |
| **2026-11-20 12:0x–16:4x** | **VHT80 2SS MCS9 重编译线：一路走到「模块能编出来、但一上机就崩」，最后用纯脚本方案收场**（过程结论进 §6.1）。①**现场先探明可行**：`iw 6.17` 支持 `ibss join ... 80MHz`，ch149 实测 `width: 80 MHz, center1: 5775 MHz`，监管 5735-5835 @80MHz 放行——**不需要移植 houji 的 wonder-init / monitor 路线**。但现役驱动的 `wonder-ven wonder0 freq 5745 <bw>`：`bw=0` rc=0、`bw=1/2` 均 **-95**，确认驱动里有 `bw!=0` 的准入闸。②**第四处根因（比原清单更关键）**：`target_if_vdev_mgr_start_send()` 在 `diag&1`（脚本一直传 `passthru_ht=1`）时把 vdev 钉成 `WMI_HOST_MODE_11NG_HT20` + **1SS** + 禁 VHT + 清 HE/EHT——**所以只改 fixed rate 无效，VHT80 必须同时改 vdev START**。③**落源码**：新增 `passthru_vht80`（diag bit2，优先级高于 `passthru_ht`）、channel change 走 `11ac`、fixed rate 补发 `wmi_vdev_param_nss` + `ldpc=3`、20MHz 并发准入闸在 VHT80 下旁路；`target_if_vdev_mgr_tx_ops.c` 走 `WMI_HOST_MODE_11AC_VHT80` + `allow_vht=1` + 2SS；BA 窗口 64→256；`pas_mgmt` 的 `bw_mask` 从 `BIT(2)` 改 `BIT(4)`；`source-map.json` 五个哈希同步。④**构建期连撞三坑**（全在 §6.5）：`kernel_platform/vendor` 软链让 `git apply --check` **报 OK、退出 0、文件一个没改**（DdkConfig 因此失败）；补丁应用根各不相同且 `0001`/`0003` 互斥；`0005`/`0006`/`0007` 必须按 `--include` 分次打。最终编出 513417320 B 的 `.ko`（strip 后 20029304 B），**ABI 600/600 CRC 全同、vermagic 一致、导出 2**——但⑤**实机一上就崩**：`send_vdev_start_cmd_tlv: chanmode 10 ch_info 0xa center_chan 5775 Tx SS 2 Rx SS 2` → `wma_vdev_start_resp_handler status=6` → MHI `RAMDUMP DOWNLOAD MODE` → SSR/重启。⑥**16:40 定案：纯脚本方案成功。** 一次重编做出带二分开关的模块（`passthru_ht2ss`/`passthru_vht1ss`）做 A/B：VHT20+2SS、HT20+2SS、VHT20+1SS **三者全 RAMDUMP**；BA 窗口 256 不是原因（BA=64 对照已复现）。→ 最终可用方案是**完全不改驱动、不改 vdev START，只发固定速率命令**：`wonder-ven wonder0 rate 2 0 1 2 9` 在原厂模块上 rc=0，dmesg 确认 `preamble=2 bw=0 gi=1 nss=2 mcs=9`，链路稳定不崩。脚本改三处（insmod 的 `phy=2→3`/`mcs=7→9`、`TUNE_RUNTIME`、`link_up` 末尾新增 `apply_fixed_rate()` 并在 join 之后下发、从 dmesg 回读校验——必须同时定义 `AWDL_NSS=2 AWDL_MCS=9`，否则参数为空会 rc=-22）。⑦**已完整回退并固化**：内核模块用原厂（`qca_cld3_peach_v2.ko.gz` 从备份还原），只有脚本变了；验证 `TUNING ok` + `RATE_FIXED ok preamble=2 bw=0 gi=1 nss=2 mcs=9` + `AWDL_LINK_UP`，`wonder0` ch149 / 20 MHz / carrier=1。**保留的改造资产**：源码改动在 `source/driver/*` + `source-map.json`，VHT80 版脚本存 `.workbuddy/tmp/awdl-stack-vht80.sh`。**注意这一整套结论后来被 17:2x 与 23:5x 两次推翻**（崩因不是 vdev START 的 PHY/NSS，是 `wmi_vdev_param_nss`+`ldpc`；而 80MHz 本来用随包模块就够），本条只作过程记录 |
| **2026-11-20 01:1x** | **勘验上游 `ha0xin/houji-airdrop`（同一颗 WCN7850，双向 40–64 MB/s）：它靠硬件 TX A-MPDU，我们只有用户态；但有四条能拿**（只读调研，**本仓库一行未改**；结论写进 §3.4 两处订正）。①**它怎么做到的**：把三星 `ezif` 固件 `amss20.bin` 塞给 `kiwi_v2` 驱动 + 移植 PASSTHRU，`pt_exclusive=1` 会话期独占无线电、`pt_remote_multi=1` 给 8 个对端槽位、`pt_rx_msdu_buf=1` 开 REO 大缓冲，用户态 `owly-awdl` 只管同步与 TLV，上层直接用原版 MIShare。读数 `agg_mpdus=22933 / 98.4%`、`max_ppdu_mpdus=31`——**那 40–64 MB/s 全部来自硬件把 31 个 MPDU 塞进一个 PPDU**，不是协议写得好。②**拿不到的三条**：换固件碰 `/vendor`、`pt_exclusive` 断 Wi-Fi、`pt_remote_*` 参数我们的驱动里根本没有——全在硬约束之外，不复述。③**能拿的四条（按性价比）**：**(i) 反向纯 TCP ACK 跳过合包定时器**（`OWLY_COALESCE_US=1000` 只该拦数据帧；它实测 157.80 MB 接收期间本机回发 8 153 个空口帧只有 101 个触发合包，纯 ACK 白等 1 ms）——纯用户态，**直接对症我们的接收 857 kB/s**；**(ii) `peer_up` 预热 IPv6 邻居缓存**（它 `/Discover` 首次建连 1.76 s 全耗在 NDP 跨 AWDL 窗口、首包 SYN 吃 1 秒重传，预热后 `/Upload` 建连仅 8 ms）——即 §3.5 ③；**(iii) 补 TLV 17 + `phy 2→3`**（字节形态已抄进 §3.4）；**(iv) 放宽 `nss 1→2 / mcs 7→9` 准入**——要重编译，但补丁链与 `BW_80 → CH_WIDTH_80MHZ` 映射都在仓库里，蓝本是它那份 316 行 `2ss-rx-phy.patch`。④**两条订正**：§3.4 原写「这块 tap 没有 BlockAck」→ 设备 `ba_stats` 是 `enabled=1 tx_ok=2 setup_failed=0`，**有**；§3.4 闸 (b) 原写「缺 ch155/5775 所以 80 MHz 落不下来」→ 它的 `band_config.c` **同样没有那条**却跑通 VHT80，真闸在速率准入那一行。⑤**方法论**：它的「不用峰值代表稳定速度 / 64 MiB × 3 加权 / 两端 SHA-256」与 §7 同源，可互相加固；另外 **Mac 的 `awdl0` 只有约 10% 信道驻留占空比**（600 ms / 6 s），测接收绝不能用 Mac 当对端，要用 iPhone。⑥**未做**：上面 (i)(ii) 都还没动代码，是候选不是结论 |
| **2026-11-20 00:43** | **收尾两件：终态卡不再留旧按钮、`client.streams` 删掉**（全文进 §5.4 末条与 §3.3.1）。①**卡片**：用户报「发送成功后按【取消发送】没反应」，查日志**不是手抖**——`23:22:32.943` 已打 `live card: 发送完成`,之后 65 秒应用侧一条卡片日志都没有，而 `23:23:37.957` 起 5 秒内有 **6 次** `cancel from the card: no send is listening for 2f391a32-…`。屏上那张是系统留着的前一帧，按钮还是旧的。根因在 `LiveCard.post()` 用 `notify(id, …)` 在同一条通知记录上换按钮行，系统可能只换字不换那颗 `PendingIntent`。两层修：**治成因**——`post()` 把「挂哪些按钮 + 是否终态」拼成形状串（`open`/`retry`/`stop` + `\|end`/`\|live`），形状变了先 `cancel(ID)` 再 `notify`（只在变化时做，进度 200 ms 一次不做无谓 binder 往返；邀请卡不走 `post()`，自己补记 `lastShape = "invite"`）；**治症状**——`SendHost.cancel()` 拿到「停不下」时 `settleCard()` 撤掉重投一张终态卡并打 `re-flushing the card`，**不抬头**（人为一件早已结束的事点一下，不该再收一次「发送完成」）。**关键边界**：`LiveCard.ID` 全机一张、接收侧同用，所以只认 `session.equals(settledSession)`，绝不因陌生 session 撤卡；`CANCELLED` 支显式 `settle(null, …)`。②**删旋钮**：`crates/settings` 的 `client.streams` 行、`SendActivity.streamCount()` 与 `share()` 一起删（原位留块注释写清装箱算法，将来复测照写即可），调用点改 `Collections.singletonList(paths)`；`sendLanes()` 的「N 组」形状保留、永远收一组。不写迁移：`Values::retired()` 跳过表里没有的键，`to_text()` 只写表里有的行，旧值下次保存自动消失。③**验证**：`cargo test -p opairdrop-settings` 19 过；`assembleDebug` BUILD SUCCESSFUL（`libopairdrop_ffi.so` 710712 B）；`crates/ffi/jvm/check.sh` 378 条 `ok`（54/48/69 三组汇总），`grep -iE "fail\|not ok"` 的 22 行全是测试名里含 failure 的用例，无真实失败。④**待验**：新 APK 还没装设备复测（要看的正是 `nothing to stop for` + `re-flushing the card` 这对日志，以及设置页不再出现「并行连接数」）。 |
| **2026-11-20 00:2x** | **S2 落地：`cc=bbr`+`fq` 写进 `tools/awdl-stack.sh`，并用 `ss -tin` 把吞吐模型整体重写**（全文进 §3.3 / §3.3.2 / §3.3.3）。①**实测**：`cubic`+`pfifo_fast` 2.75 稳态，`bbr`+`fq` 两轮 3.14 / 2.97——**+11% 可复现**；判据是曲线形状，cubic 有 `2.54` 的坑（丢包→cwnd 减半→再爬），bbr 走平。样本换成 20×14 MiB `/dev/urandom`（不可压，线上字节≈磁盘字节）。②**`ss` 推翻旧模型**：传输中途读数 `wscale:6,9`、`cwnd:46–56`、`unacked:45–52`、`minrtt:3.413`、实时 `rtt:17.9`、`bbr:(bw:34–35Mbps)`、`retrans 0.98%`。→ **iPhone 的接收窗口根本不是 89 KB**（`ws=9`，可到 32 MB），§3.3 那个「5.05 MB/s 窗口天花板」作废；真实限制是 `awdltun0 → passthru bridge` 之间 ~45 KB 的排队，链路交付能力约 **26 Mbps**，我们拿到 71%。③**空口与参数全部到顶**：`agg_stats` 4.30 msdu/聚合（上限 5，`p5` 73.5%）、`opairshare_bridge_pending=0`（`capacity=256` 没在限流，加到 512 无意义）。④**双流 + bbr 仍不动**：合计 ~140 KB 在飞（单流的 2.2 倍），吞吐 3.25、`rtt` 反被推到 19–23 ms → 限住的是**共享交付能力**，不是连接窗口。⑤**固化方式**：`tune_tcp()` 探测 `awdltun0` → 确认 `bbr` 在 `tcp_available_congestion_control` → 写 sysctl → `tc qdisc replace … root fq` → 回读打 `TCP_TUNING cc=bbr qdisc=fq dev=awdltun0`；四个调用点（`DAEMON_ALREADY` / `TUN_READY` / `link` 入口 / `cmd_status` 只读）；`restore_cc()` 在 `stop` 时写回 `$STATE/cc-before` 记的原值。四条路径真机验过（含 `stop` → `TCP_RESTORED cc=cubic`、冷启动 `TUN_READY` → `TCP_TUNING`）。⑥**产品代价**：`streams≥2` 会让 iPhone 弹 N 个确认框（20 文件 = 10+10 两次通知），用户只点一次发送却看到两次；第二框晚点 31 秒就白丢三分之一时间窗。 |
| **2026-11-19 23:52** | **S4 多流并行：做出来了，实测不提速，否掉当作速度手段**（全文进 §3.3.1，方向表 §3.5 ④ 划掉）。①**实现**：`client.streams`（1–6，默认 1，`crates/settings` 的 `CATALOG` 加一行即进设置页）→ `SendActivity` 按「大文件优先贪心装箱、按字节均分」切 N 组，每组一条独立连接走完整 `/Discover`→`/Ask`→`/Upload`（协议一条连接只承载一个请求，N 流 = N 次确认）。`live` 从单 `Socket` 改 `Set` + `hangUpAll()`，`Flight` 改逐流格子（`AtomicLongArray`），进度分母用点发送时定死的 `totalBytes`。**Rust 一行未改**（发送路径无全局状态）。②**三轮**：1 流对照 282.6 MiB / 107.12 s = 2.77 全程 / 2.75 稳态；2 流 24 MB / 9.14 s = 2.75 全程 / **3.02 稳态**；逐段 `2.32→3.57` 后在末段掉 1.96（lane 1 先送完、只剩单流）。③**并发是真的、叠加是假的**：两流同一秒被接受、同时开跑，各 12.0 MB，lane 0 1.37 MB/s、lane 1 1.74 MB/s，**合计不动**。窗口按连接给（89 KB/条），两流本该有 178 KB 在飞预算、吃 ~6 MB/s，实测仍是 `53 KB ÷ 17.2 ms ≈ 3.08`——**压住 cwnd 的是 1.75% 丢包反馈，不是窗口额度**，这条负结果把 §3.3 的推论 3 和 §3.5 的路线选择一起改写了。④**纠正上一轮误判**：第 2、3 轮「只接受一条、其余 15 秒整断」不是 iOS 一次只处理一个，而是 `client.read_timeout_ms=15 s`——90 s 的 `upload_read_timeout_ms` 只挂在 SENDING 相位（对面已接受之后），没被接受的流永远进不了该相位。调到 180 s 后两流同秒接受。⑤踩坑两条：`flight.moving` 当门闩导致只有第一条流改了自己的 socket 超时（`moving` 现在只当旗子，超时每条流各自无条件设）；`adb push` 因路径转换把 `settings.conf` 截成 0 字节（push 也要 `MSYS_NO_PATHCONV=1`，且先推 `/data/local/tmp` 再 `su -c cat >` 回填并核对字节数）。⑥**留着的代价**：N 流 = N 个确认框，且成功后卡片仍留【取消发送】（UI 待修）——**2026-11-20 两件都收掉了**：旋钮已删（§3.3.1），卡片见 §5.4 末条。⑦**2026-11-20 订正**：本条里「窗口按连接给（89 KB/条）」「压住 cwnd 的是 1.75% 丢包反馈」两句已被 `ss -tin` 证伪（§3.3.3），当日结论「并行不提速」不变，但原因要换成「共享交付能力约 26 Mbps」。 |
| **2026-11-19 22:14** | **射频共享线取证完毕、按用户要求暂缓**（全文进 §8.4，这里只记结论）。问题：「开【WiFi 共享射频】后，AP 不在 149 就无法与 iPhone 收发」。①**根因定性**：tap 钉死 149，而 iPhone 的 AWDL 跟随它自己关联的 AP 信道（未关联才在 6/44/149 跳），两边不同频就碰不上。②**新取证**：`wonder0` 是独立 wiphy（`phy#46`、IBSS、149）；`wondertap0` 在 `phy#45` 且与 `wonder0` **同一颗 MAC**；`wlan0` 是另一颗 MAC；`Wiphy wonder` 的 5G 只列 UNII-1+UNII-3（无 DFS）；`phy45` 声明 `#{ managed } <= 2, #channels <= 2`；`dmesg` 与 `/sys/kernel/debug/` 都读不到固件的 5G 分段。③**5745 硬编码共四处**（`wma_opairshare_pas_mgmt.h:579`、`wlan_hdd_wondertap.c:226`、`wma_opairshare_awdl_rx_mac_filter.h:146`、`wlan_policy_mgr_opairshare_sta_coexist.h:80`）。④**推翻三条旧判断**：`3`→`2` 不但无助，还会让 `wlan_hdd_scan.c:524` 把非 149 的 5G 信道剔出扫描（「传不了」变「连不上」）；DBS/双 MAC 只保住 Wi-Fi，AWDL 仍在 149，照样碰不到；**11-14 那次「5220 会崩」死在 `wondertap.c:226` 的 `if (ret < 0)`，硬件从未被请求过**——所以「芯片能不能跑非 149」这个实验至今没做过。⑤**正确形状**：tap 跟随 STA 信道（同频 SCC，DBS/SBS 整条线是错的方向）；验收步骤 R0–R3 见 §8.4，R0 只动 Java、零风险，R1 起需要重装驱动 |
| **2026-11-19 19:45** | **出现从 3.08 秒压到 0.9 秒：端口一通就复活，不再等记录**（结论进 §5.8 新增条目）。①先按上一轮的口径做**两端对齐测量**：iPhone 关 → 等 3–5 秒 → 开，同时挂 App 日志与设备侧每 0.25–0.3 秒直探 8770。19:28 那一轮：端口 `35.774` 开 → `38.853` `ready`，**3.08 秒**。②拆开是两段。**(-a) 端口开到被发现 2.03 秒 = 自己的 bug**：`watch()` 用固定 `sleep(1000)`，而探测是阻塞的（最长 2500 ms），真实周期变成「1 秒 + 探测耗时」，相位一路后漂；改成绝对节拍 `next += WATCH_EVERY_MS`（超支时 `next = now` 不补课），守望的探测超时单独降到 `WATCH_TIMEOUT_MS = 400`，敲门仍用满 2500 ms。**(-b) `ask` 到记录回来 0.79 秒 = 不是我们能压的**：先怀疑 QU 位没置，**查证是错的**——`crates/ffi/src/browse.rs` 的 class 就是 `CLASS_IN \| CLASS_UNICAST`（`0x8000`），解析侧也认。真正答案是两次实验落点相同：19:15 端口 `59.84` 开 → 记录 `62.48` 回来（期间按 300 ms 问了 8–9 次无人应）；19:28 现场单播问 → 记录仍在端口开后 2.6–2.8 秒回来。**问与不问都一样，而一个来回只要 9–13 ms——那 2.6 秒里它答不上来，是它还没注册完。**③改法：墓碑改记**整只 `Peer`**；`watch()` 看见端口通了只贴便条 `Grave.answered`，由浏览线程在 `expire()` 开头 `resurrect()` → `revive()` 动手（`absorb()` 遍历 `peers` 不加锁，跨线程 `put` 的 CME 会顺着 `loop()` 的 catch 把整条浏览掐掉，为早 250 ms 不值得）。④实测两轮：出现 **0.72–1.09 秒**（`10.749~11.125` 开 → `11.841` 复活）与 **0.69–0.96 秒**（`32.898~33.171` 开 → `33.856` 复活）；消失 **1.57–1.86 秒**（`20.248` 前断 → `21.817` 掉出）。`suspect` / `ready` 一条未再出现，两次复活后列表均只一格，0 崩溃。⑤**不继续压**：`WATCH_EVERY_MS` 降到 500 只换 0.4 秒、探测翻倍、肉眼分不出；消失侧两次敲门之间的 0.8 秒是防 AWDL 时隙吃掉单个 SYN 的唯一保护 |
| **2026-11-19 18:52** | **「重开 socket」的退避是个摆设，且空表时不该勤快**（结论进 §5.8 末条）。①`round21` 里六行 `deaf: 40 queries …, nothing from anyone for 20 05x ms`，间隔精确 20 秒、一次 40 秒都没有。②根因是重开自身的副作用：`reopen()` 清零 `lastForeign`、`quiet` 改从 `openedAt` 起算 → 重开那一刻「不聋」→ 循环里的 `else if (!deaf …) deafWait = DEAF_RETRY_MS` 把退避洗回 5 秒，**翻倍那行从未生效**。③改法：复位挪进 `absorb` 的 `addr` 分支（只有「真收到别人的记录」才算不聋了）；空表时退避直接按 `DEAF_CEIL_MS`（60 秒），因为空表的沉默是「没人该说话」，而发现新设备走组播 announce + TCP 敲门，都不经过被抢走的单播应答。④最初还想用「自己的宣告绕回来」当听筒自检，**否决了**：组播回环恰好能在「单播被 5353 上另一个 socket 抢走」时照常工作，拿它当「我不聋」的证据正好会关掉唯一的解药。⑤实测同一场景（AirDrop 关、表空）：`18:55:32.440 → 18:56:32.638（60 190 ms）→ 18:57:32.860（60 213 ms）`，六次拆变一次；删除仍是 **2.45 秒**（`18:55:14.885`），0 崩溃 |
| **2026-11-19 18:38** | **冷启动不再等 mDNS：把「敲门」用在启动那一端**（结论进 §5.8）。①先量出病：18:36 用分享意图开面板，`browse` 在 `18:36:36.886` 就开始问，`ready … after 26 838 ms of browsing`——而这期间 `nc -6 …:8770` 一直是通的，即**端口开着、它只是没开口**。②改法：能连的时候把 `名字/地址/端口` 写进 `shared_prefs/peers.xml`（最多 6 台，`Peers.remember()` 只在有新的一台进列表时重写），启动时交给敲门线程按旧名单敲一遍（`Peers.recall()`）。③实测：`18:37:31.040 recall: … answered in 12 ms — showing it after 14 ms of browsing`、第二次 `18:38:50.219 … 276 ms`——**26.8 秒 → 14 毫秒**。④反向验：把名单里的端口改成 9999，`recall: … did not answer in 198 ms (ECONNREFUSED) — leaving it to mDNS`，屏幕上不出现，随后 mDNS 在 1.69 秒找到真端口并回写名单。名字可以过时（iPhone 每分钟换实例名），地址不会（链路本地由 MAC 推出来），判据仍是端口开不开 |
| **2026-11-19 18:25** | **「关 AirDrop 要等 20 秒才消失」收口：判据从「沉默多久」换成「8770 还接不接」**（结论进 §5.8 两条新条目）。①先证**链路层判不了**：关掉 AirDrop 之后 `iw dev wonder0 station dump` 里 iPhone 的条目仍是 `associated: yes`、`connected_time 128 s` 继续涨、`signal −26 dBm`。②再证**端口判得了**：开着时 `nc -6 …:8770` 立即成功，关掉时 iOS 对未监听端口静默丢弃不回 RST（探测只能等到超时，之后才变 `ECONNREFUSED`）；顺手在 AirDrop 开着的 42 秒里连敲 **240 次，240 次全通**（中位 70 ms、最慢 471 ms），说明「连不上」不会被应答器休眠误触发。于是 `KNOCK_AFTER_MS = 1 200`、`KNOCK_FAILS = 2`、`KNOCK_TIMEOUT_MS = 2 500`，实测删除 **2.3–2.5 秒**（`18:23:34.948 dropped … (2359 ms of mDNS silence)`），`CONNECTABLE_MS = 20 000` 降级成「敲不出结论时」的兜底。③**敲门失败拆三类**：拒绝/超时算它没答；`Network is unreachable` / `No route to host` 是**我们自己**没把包送出去（曾实测 `awdltun0` 一条 IPv6 路由都没有、包被送去 `rmnet_data3`），不投票——差一点用自家路由故障删掉用户的设备。④删除之后又冒出**四趟回魂**（`dropped 42.3 → ready 45.3 → dropped 52.6 → ready 55.4 → …`）：AirDrop 关掉后 iOS 仍会拿缓存继续答 SRV/TXT/A 达 31 秒。修法是给地址立 2 分钟墓碑（`Peers.buried` + `Peer.unproven`）：这期间记录再齐全也不进列表，先敲一次门，敲通才显示（实测验证只要 10–167 ms），敲不通继续当它走了。⑤round20 实测（用户连续开关）：每次关掉 2.3–2.5 秒删除，重开 0.1–0.3 秒回来，其间一条误显示都没有 |
| **2026-11-19 17:26** | **「设备显示几秒后又消失」收口：把 4 秒那个门槛从「重开 socket」上摘下来**（结论进 §5.8 两处）。①上一轮把 `CONNECTABLE_MS` 抬到 20 000 装上去后，格子确实不再闪了，但日志里冒出**新的抖动**：九十秒重开八次 socket——`17:20:38 deaf: 16 queries sent, nothing from anyone for 4082 ms` / `17:21:21 … 4047` / `17:21:26 … 4054` / `17:21:32 … 4085`。根因是**两处错叠加**：自愈判据复用了 `LINK_QUIET_MS = 4000`（那是 `expire()` 的门槛，猜错零代价），而 4 秒在「旁边只有一台 iPhone、它应答器成段休眠六到八秒」这条链路上**是常态**——我们把常态当成了自己的故障，一圈圈拆 socket，把正在路上的应答也丢了。②12 小时内第三个 bug：`open()` 会把 `lastForeign` 清零，而「问过几次」用的 `asked` 是 `loop()` 里的局部变量、跨 socket 不清零，于是**每次重开后「冷启动」那条分支立刻成立**（`asked` 早过百），1.2 秒的语义荡然无存，重开变成五秒一次的死循环。修法：新增 `heardEver`（本次浏览听见过没有，**不随重开清零**）分界，冷启动那一半改用 `askedSinceOpen`（每块 socket 自己数），听见过之后换 `DEAF_SILENCE_MS = 20 000`——和 `LINK_DEAD_MS` 同值，理由写在常量上：到了这一刻「删掉它」和「重开赌一把」是同一件事。③**另一条判据上的诚实**：中途变聋从来没被观测到过。唯一像它的 17:10 那次 130 秒静默，用 `/proc/net/dev` 量过 RX 零变化，不是聋；所以这条路只剩兜底意义，**宁可慢，不要吵**。④round15 实测 40 秒：`deaf` 只有 1 条（冷启动 1540 ms，本该有），iPhone `d68d459bc84f` 全程在表里，`foreign` 9 → 21 → 31、`quiet` 0–754 ms，一次 `dropped` 都没有 |
| **2026-11-19 17:10** | **查清静默 130 秒那次到底是什么**（结论进 §5.8 `LINK_DEAD_MS` 那条的末段）。现象：`17:11:00 dropped ac064e658d14 after 20123 ms` → `17:11:52 pulse: 0 peer(s), foreign 34 (last 71273 ms ago)`（`foreign` 冻在 34 一动不动）→ `17:12:22 ready … after 130704 ms of browsing`。①自愈判据原来是累计的 `foreign == 0`，中途变聋永远触发不了——`lastForeign` 本来就是为回答「我刚才还听得见吗」加的，`expire()` 用了，浏览循环漏了；这一轮 `deaf:` 一次没打就是证据。②但**改完之后量出来它根本不是聋**：`/proc/net/dev` 里 `awdltun0` 的 RX 字节与包数 20 秒里一个没动、TX 涨了 55 个包；同时 `iw dev wonder0 station dump`（有 KernelSU，`su -c id` 拿到 `uid=0`）里它的站点条目已经没了，回来时 `connected time` 从头计（`4 seconds`，信号 −26 dBm，不是距离问题）。所以真凶在 **802.11 那条关联断了一次**，不在应用层，重开 socket 也救不了（那一轮重开过，没用）。③顺带确证这条链路的两件事：`wonder0` 是 IBSS（phy#46、ch 149 / 5745 MHz、ssid `awdl0`），`awdl-stack.sh` join 时已带 `HT20 fixed-freq`，mac80211 不会每 30 秒去合并同 SSID 的 IBSS；BLE 只广播不扫描（`grep startScan\|ScanCallback\|BluetoothLeScanner` 零命中），拿不到独立的「在场」证据 |
| **2026-11-19 16:40** | **表里那个「1 instance(s) with partial records」一直是我们自己**（结论进 §6.4 新增那条）。抓 310 秒（round9，`pulse:` 每 10 秒一行），三条：①**幽灵 peer**。`16:21:43 announcing 7f5ed39dbd10 at air.local:8770` 是本机 Responder 自己的实例名；这条组播打到自己的 socket 上、被 `absorb()` 建成一个 peer，接着它的 `addr` 记录被 `own.contains()` 挡住不填地址。于是它**永远是 partial**（没地址就进不了可连接列表）、**永远不过期**（记录一直在刷新，`pulse` 里 `quiet=501/506/503` 几十个样本全是个位数）。后果不是占点内存，而是**把「对面真的走了」盖住了**：`16:35:31.164 dropped 3dc068738e2e …` 之后紧跟的 `nothing connectable yet (1 instance(s) with partial records)` 里那个 1 就是它自己，人看到会以为「还有一台设备只差地址」，实际表里已经空了。修法：`case "addr"` 里认出地址是自己的之后不再只做「跳过」，而是**按 host 整个收掉**（`peers.values().removeIf(peer -> field[1].equals(peer.host))`）——地址是自己的就证明这个 host 就是我们，而实例名每分钟换一次，按实例名收会每换一次漏一次。②**6000 的阈值生效了也被打穿**。`16:35:31.163 dropped 3dc068738e2e@fe80::704d:92ff:fe08:dd2:8770 after 6218 ms of silence (threshold 6000, rhythm 1048 ms, gaps 32, max 1673 ms)`——32 个间隔、节奏 1048 ms，说明这台设备已经稳说半分钟以上，然后突然静了 6.2 秒。和 16:20 那条一样，`6218` 落在 `[6000, 6250]` 里，仍然只是 250 ms 检查粒度，**不能读成「再宽三百毫秒就好了」**。③**单播 `prompt()` 发出去了，但没救回来，而且那一次对方是真走了**。`16:35:27.398 prompted 3dc068738e2e by unicast after 2453 ms of silence` → 3.9 秒后仍然 `dropped`；随后 `pulse` 里的 `foreign last … ago` 从 10 750 ms 一路涨到 **201 647 ms**——三分钟内一条外部地址记录都没进来。所以这一次的沉默是「它走了」，单播没有应答是对的。**代价是这一轮仍然没拿到「活着的设备被叫醒」的正样本**：判 `prompt()` 管不管用，要看 `prompted` 之后紧邻那行 `pulse` 里 `quiet` 有没有归零，而 round9 只此一次样本且已死。 |
| **2026-11-19 16:20** | **纠一个误读：`4134 对 4080` 不是「只差 54 毫秒」，进而推翻 2.2 秒那一档**（结论进 §6.4）。16:16 抓到 `dropped 9ba49b54ddbb after 4134 ms of silence (threshold 4080, rhythm 1115 ms, gaps 242, max 1640 ms)`——**242 个间隔说明这台设备已经稳定说了好几分钟**，然后突然静了四秒多。和 15:57 那次（2727 / 2608 / gaps 29）放在一起看，两个数都精确落在 `[阈值, 阈值+250]` 里：`expire()` 每 250 ms 跑一次，**删除时刻必然是阈值之后的第一个检查点**，所以这两组数只能证明检查粒度是 250 ms，**证明不了「再宽两百毫秒就救回来了」**。真相是我们根本不知道它能静多久——因为在第一个检查点就把它删了。判据的毛病因此不在数值，在它想回答的问题：「静了 N 秒」在我们这侧分不清「它走了」和「AWDL 抖了一下」。修法两条：①`CONNECTABLE_MS` 2200 → **6000**，与 `EXPIRE_MS` 同值（合流方向是「把能连那一档放长」，不是把另一档压短）；`CONNECTABLE_SLACK_MS` 保留 800 只管抖动那一段。②新增 `Peers.prompt()`：对静默超过 `PROMPT_AFTER_MS = 1500` 的、能连的对端**单播**问一句（RFC 6762 §6.7，单播查询必须单播应答，重复抑制只针对组播），把「等它自己开口」变成「一问一答」；失败**不记** `sendFails`，免得一台刚走掉的设备触发一次无谓重开。思路是**让「沉默」变少，而不是让阈值更耐沉默**。③同一窗口反证：16:16:11 删掉之后 78 秒没回来（`1 connectable` 一次没打），说明那一次设备是**真走了**，阈值没有误伤——所以这一轮没拿到「活着的设备会不会被误删」的证据，得再抓一次 |
| **2026-11-19 16:05** | **等待应答 30 s → 60 s + 修「设备显示几秒后自己消失」**（结论进 §6.4 新增那条）。①**60 秒**三处同改：`Confirmation.TIMEOUT_MS`（表读不出来时的回落值）、`crates/settings` 里 `receive.decision_timeout_ms` 的 `default`、`ReceiveHost` 两处注释。**设计前提被现场推翻**：原来写 30 秒的理由是「比发送端自己的超时早一点，好让框在对面放弃之前消失」，可人很多时候不在手机跟前（屏幕朝下扣在桌上、人在厨房），三十秒不够他走过来；代价是发送端多等半分钟，而它自己也有超时。到点仍走拒绝路径（`decide()` 得 `false` → 回 401），卡片文案的秒数是 `seconds(timeoutMs)` 现拼的，不用改字符串。**一个必须记住的坑**：改默认值对**已装机**设备无效——`SettingsFile.of()` 是 `getFilesDir()/settings.conf`，真机上它确实写着 `receive.decision_timeout_ms=30000`，`Tuning.ms` 读到就用它，读不出来才回落常量。得 `run-as com.oshare.airdrop sed -i` 改文件。②**消失**是两件事叠加，见 §6.4：阈值是死数 2200 不看实测节奏（丢一包就 2.2–3.3 s 静默）、以及换实例名时新实例缺地址而旧实例被判死留下的空窗。修法：`timeout()` 改成 `max(2200, 2 × gapMax + 800)`、新增 `Peers.inherit()` 让新实例从同 host 的旧实例继承地址并立刻删旧实例。③顺带给诊断加了 `Listener.Spoken`（把对端发来的**第一行 HTTP** 抄进日志，键是 `first line from <addr> after <N> ms: POST /Discover HTTP/1.1`），已抓到 iPhone 会先开一条连接打 `POST /Discover` 然后立刻 reset——那是探路连接，不是投送。④`StackKeeper` 链路不在时从固定 8 秒改 1.5 秒（那一段正是人干等的一段，而问一次只是一次网卡枚举） |
| **2026-11-19 15:13** | **发送侧补上【取消发送】+ 修「点头像取消」三个真 bug**（结论进 §5.4 两处）。①卡片：`LiveCard.updateWithStop(ctx, title, short, pct, label, stop)` 让按钮名由调用方给，`updateWithCancel` 收成它的重载；`准备中`/`等待对方接受`/`传输中` **三个相位全挂**（发送侧没有对面邀请卡上的【拒绝】可复用，不存在重复；「等待对方接受」尤其需要——发错设备时对面已弹出邀请卡）。②落点：新 `SendCancel`（`exported=false`、`getBroadcast` 不 `getActivity`），`STOP_REQUEST = 0x0D00` 单码不分段（`SendActivity.sending` 保证同一时刻只有一趟），`stopping` 缓存**连 session 一起存**（只认控件不认趟次会让第二趟的取消去停第一趟）。③把 `SO_LINGER(0)+close` 从 `ReceiveHost.Attempt.abort()` 提成共用件 `HangUp.now(Socket)`。④**三个 bug**：`cancelSend()` 开头 `if (live == null) return` 让「点设备→首条连接建立」那 2–5 秒里按取消毫无反应且 `sending` 卡在 true（人再也发不出去）；主线程 `SSLSocket.close()` 写 `close_notify` 会 `NetworkOnMainThreadException`；`onDestroy()` 无条件 `unregister(cardCancel)`，而传输线程握着 page、socket 还开着，撤注册 = 卡片按钮彻底不响（旧注释「页面销毁后只会调到没有传输可停的对象上」是错的）。修法：先置 `cancelling` + `transfer()`/`open()` 两个检查点、统一走 `HangUp`、`pageGone` + `settle()` 延迟摘。⑤**取消不留终态卡**：首版给出 `已取消 · 已取消 · 保持两台设备都亮屏…再点一次设备重试 · 29% (heads-up)`，一句话三个错（副行没给 `stopped` 分支、兜底是给故障写的建议、还弹了 heads-up），改成 `SendHost.finished` 见 `CANCELLED` 直接 `LiveCard.drop()`，与接收侧同口径。⑥**三趟实测全过**（等待相位 4 ms / 传输中 51 ms、60 ms，均在取消后立刻能重发，通知栏确认无残留卡）。⑦顺带更正一条旧知识：`HangUp` 能立刻唤醒阻塞的 `read`，靠的是 `SO_LINGER(0)` 把连接推进 `TCP_CLOSE` 并发 RST，**不是 `close()` 本身**——光 `close()` 才是不保证唤醒的那个 |
| **2026-11-19 14:55** | **反向实测：iPhone 中途取消，落在我们接收侧的是 `ECONNABORTED` 不是 RST**（结论进 §5.4）。用户提议「我点接收、再回 iPhone 点取消发送」。02:44 那次我们是发送方，收到 `Connection reset`；这次我们是接收方，收到 `Software caused connection abort`（14:55:18.287，已走 2.3 MiB 才断）。**方向决定 errno**：`Reset` 是对端发来的 RST，`Abort` 是本机内核把 fd 收了（对端在 AWDL 隧道那头收手，我们这侧跟着收尾）。因此修掉一处甩锅——`Listener.because()` 原把所有 `ECONNABORTED` 一律翻成「连接被系统切断，检查后台限网」（依据 11-17 那次确实零字节），现按「断之前有没有字节落地」分流：`Meter` 新记 `done`（**写在 200 ms 节流的 `return` 之前**，否则最后几次恰好是断前那几次会漏记），落地 > 0 改说「传输在中途被中断，已收到 2.3 MiB，没有存下」，**刻意不点名是谁停的**——从我们这侧分不出「对面取消」和「对面息屏断网」 |
| **2026-11-19 14:24** | **接收侧补上【取消接收】**（结论进 §5.4 新增那条）。进度卡靠 `LiveCard.updateWithCancel` 挂一颗按钮，request code `0x0C00 + (id & 0x3FF)`（extras 只按 request code 区分，撞号会让两台同时发时取消错人），广播复用 `DecisionReceiver`（`exported=false`）按 action 分岔，**不走 `answer(false)`**——那会把这次接收改写成「已拒绝」，而拒绝是「还在问时回 401」，取消是「已经在传了，停下」。**撞出一个真 bug**：`Attempt.cancel()` 里 `peer.close()` 在广播主线程上做 TLS `close_notify` 写，`NetworkOnMainThreadException` → `Unable to start receiver`，崩的是整个进程（用户一按必崩）；改成 daemon 线程 + 先 `setSoLinger(true, 0)` 再 close，立刻发 RST，与 iPhone 中途取消回给我们的信号同一种。实测 14:24:21：读线程 `Socket closed after 3129344 bytes`、对端 `Connection reset`、卡片同帧撤掉。**一个测量坑**：`announced`/`cloud` 只由 `decide()` 置真，而撤卡写在 `end()` 的 `if (cloud)` 里——绕过 `decide` 直接投卡的探针会看到「socket 关了、卡还在」，那是探针的错 |
| **2026-11-19 02:26** | **提醒音换成「隔空投送铃声」+ 振动跟拍**（结论进 §2.1）。①音源：用户给的 mp3 有 141529/151698 字节是 ID3（含 36KB 封面），音频实为 0.627s；`make_chime.py` 解码后裁掉 74ms 前导、归一化 +14.8dB → `chime_airdrop.wav`（48KB），删掉旧的合成 `chime_dong.wav`。②节奏：测出四下敲击在 12 / 130 / 245 / 369ms（间隔 118/115/124），振动四个起点严格压上去，振幅按 -4.2/-4.8/-6.4/-11.8dB 递减为 255/235/200/120。③**新坑**：ColorOS 把带振幅的 waveform 压成 45ms 预烘焙脉冲但保留空档，第一版 62ms/段会累积错位到 51ms，改成 45ms/段 + 补偿空档后 dumpsys 显示 `From [S12, S45, S73, S45, S70, S45, S79, S45]`，起振点分毫不差 |
| **2026-11-19 02:00** | **展开方案收口：干净场景验过 + 加铃声震动 + 不再自动缩回**（结论进 §2.1 三处，这里只记做了什么）。①**验收通过**：新增 debug-only `LiveProbeActivity` 绕开 ColorOS 的广播冻结（`DEFER_BY_OPLUS / SUB_REASON: FROZEN`，`am unfreeze` 全无效），在「解锁 + 桌面 + 通知栏收起」下拿到 `提醒级别 0 → 11` → `tryTransferOrDoo EVENT_DATA_CARDS, result=ACCEPTED` → `cardLiveSize=1, cardsLevel=1` → `AfterTransfer:CARD_STATE`，截图里卡完整浮在应用界面上。②**撤掉前台抑制那一刀**：01:43 装上后卡放行了但状态机反而 `REFUSED`（丢了「瞬时提醒」标记），而 01:37 那轮没装反而 `ACCEPTED`——`remindType=11` 本身就越过这道闸。方法体保留取证，调用点删除。③**不自动缩回**：钩 `CardContainerViewModel.l(String)`（`handleDataTimeout`），对自家的卡吞掉 `EVENT_CARD_TIME_OUT` 并把状态改 `CARD_STATE_MANUAL`（对齐「人长按摊开」的收放语义）。实测展开后 11 秒 / 19 秒两张截图卡仍在，点【拒绝】仍能正常收（`EVENT_CARD_CLICK → START_STATE → CARD_TO_APP`）。顺带否掉假线索：dump 里的 `InstantDuration: 3000` 只是 `toString()` 按 `remindType==11` 现算的展示值，改它不影响时长。④**铃声+震动**：先按常规写在通知渠道上，渠道配得完全正确却一声不响——根因是 `onLiveNotificationPost` 对 `PROMOTED_ONGOING` 返回 `result=7` 交给流体云后，系统那一路 alerting 被吞。改成 `LiveCard.chime` 应用侧自播（默认通知音 + `{0,220,180,220}` 两下 + `VibrationAttributes.USAGE_NOTIFICATION` + 1.5 s 防抖 + 通知关闭/渠道关闭/勿扰三道闸），实测 `chime: ringer=2` 与 `usage: NOTIFICATION \| duration: 651ms` 都在，`dumpsys audio` 首次出现 `USAGE_NOTIFICATION` 播放器 |
| **2026-11-19 01:00** | **LSPosed API 102 模块重写并跑通第一层**（结论进 §2.1，这里只记做了什么）。重写 `FluidModule`（第二次，不是把 08:40 删掉的捡回来），钩点从 `AppFrontInterceptor` 换成 `OplusLiveAlertNotificationsRepository.access$entryToReminderType` 的**返回值**：`0` → `11`（`STRONG_SHORT`），仅对 `com.oshare.airdrop` 的 `NotificationEntry`。装机三件套 `META-INF/xposed/{module.prop(101/102/staticScope), java_init.list, scope.list}` 全部实测生效，管理器显示「OPAirDrop 1.0.0 (10000) · API 102」、作用域自动固定为「系统界面」。日志链：`已钩住 …access$entryToReminderType(…,NotificationEntry,boolean)` → `提醒级别 0 → 11` → `addLiveAlert: … remindType=11` → 模型 `remindLevel: 11, isForcedReminder: true, InstantDuration: 3000`。**两点没做完**：① 上一轮复现被自己污染（点管理器 FAB 误打开主界面 → `frontTaskId [693]` → `AppFrontInterceptor shouldFilter ret true` → `cardLiveSize=0` → `REFUSED`），「应用在前台要不要强绕」是产品取舍、待拍板；② 干净复现被两道环境挡住——锁屏解不掉（`screencap` 出全黑）+ 锁屏下 `am broadcast` 全被 `mBroadcastConsumerDeferApply` 推迟（回 `result=0` 但 `terminalCount=0`），需人工解锁后重做 |
| **2026-11-19 00:03** | **「胶囊自动展开」的官方旋钮查实：`requestShowPanel`**（结论进 §2.2，本节只记做了什么）。反编译四个 APK（Clock 49.5 MB / market 94.2 MB / SystemUIPlugin 9.1 MB / ums 92.9 MB，产物在 `.workbuddy/tmp/`，不进仓库），导出 `re/clock/` 41 类、`re/plugin/` 818 类。四跳链路：`SeedlingCardOptions.requestShowPanel` → JSON key `"requestShowPanel"`（`JsonToSeedlingCardOptionsConvertor:43`）→ UMS 侧 `onShowPanel` / `showPanelFragment` 有 UI 落地 → 插件 `SeedlingUIData.isRequestShowPanel` → `livealert.seedling.origin.v.K()` 存 `this.n`、`v()` 对外暴露。时钟在 `GarbAlarmSeedlingHelper:1027/1031` 用它。展开场景 `AnimatorSceneType.CAPSULE_TO_CARD(106)`，三条入口里第三条 `EVENT_DATA_CARDS`（`statemachine_i:1305`）是「数据驱动自动展开」的唯一一条，发送点在 `card_ui_viewmodel_h:859` 的 `r(arrayListI) == 4 && z`。**结论：官方接口能实现，但前置是 UPK / Pantanal DevStudio，我们不是那条路上的应用**；替代路三条（属性 `debug.systemui.live_alert_pkgs` 白名单、通知补 `oplusLiveAlertAppConfig` + `POST_PROMOTED_NOTIFICATIONS`、LSPosed API 102 钩 `SeedlingDataModel.v()`）记在 §2.2，`market.apk` 类级反编译待做 |
| **2026-11-18 23:46** | **点一下不再掐死雷达 + 纯链接分享收得下也打得开**（提交 `fd0f7f1`，本节补记）：①雷达动画是 `onDraw` 末尾 `postInvalidateDelayed(32)` 的**自续命帧循环**，没有独立定时器——`ACTION_DOWN` 里 `layer(true)` 把 `dragging` 置真，手势被子 View（按钮、设备格）消费后 `Sheet.onTouchEvent` 的 UP 分支永不执行，`dragging` 永久为真、雷达再也不排下一帧；修法必须三处齐备：`dispatchTouchEvent` 在 `super` 前后各看一眼 `moving`（它是唯一看得见所有 UP 的地方）、`layer(false)` 里显式 `Radar.wake()`（`sheet.invalidate()` 只重放显示列表，不会重调 `onDraw`）、`renderStage()` 开头把 `radarView` 置空。实测连点 30 下 + 按住 1.2 s 抬手，前后两帧雷达区差 11 343 / 14 005 像素，仍停在「正在搜索附近设备」。②纯链接 offer 是 `Files` 空、`Items` 塞地址，`TransferType = {links: {}}`，跨边界字段从五个扩到六个（多一条链接表，`\x1e` 分隔）；`Listener.serve` 原先只在 `report` 非空时结账，纯链接一个字节不落地会走 `finally` 的 `abandon` 把成功画成「接收失败」，`Files` 空即不等 `/Upload` 修掉；出口三件：`Confirmation.Offer.urls`、`ReceiveHost.openLink()`、`LiveCard.finishWithLink()`（左【打开链接】右【完成】），`OpenLink` 只放行 `http/https` 且 `exported=false`（`am start` 需 `su -c`）。③`README` 版本号 `16（API 37）` → `17（API 37）` |
| **2026-11-18 22:57** | **冷启动「第一次不显示 iPhone」收口 + 名字不再闪**：①根因是**同一个进程在 5353 上开了两个 socket**——`Peers` 先绑、`Responder` 后绑 28 ms，`SO_REUSEADDR` 下组播分发给所有 socket、**单播只给最后绑的那个**，而我们的查询带 QU 位、iPhone 应答全走单播，于是浏览那一路一个字节也收不到（TX 涨 / RX 冻 / 同 pid 两个 `*:mdns`，三重证据）。修法不是抢绑定顺序，而是**听不见就 `reopen()`**（`foreign == 0` 且已问 ≥4 次，5 s 起翻倍、封顶 60 s）；实测冷启动 1.2 s 判定、重开后 0.5 s 内 ready。②名字：`settle()` 把「还没名字」的设备先挡在上屏之外（`NAME_HOLD_MS = 1500`），真名到了立刻重算、窗口到期由 `armSettle()` 兜住，**头像与真名同现**，全程无「附近设备」那一帧（清缓存 + 连拍 30 帧验证）。详见 §5.9 / §6.4 |
| **2026-11-18 22:06** | **Q1 收口 + Q2 抓包 + 一个真 bug**：①拒绝码定 `401`（`503`→「等待中」、`403`→0.4 s 重连重发），真机双向验完，面板文案统一为「对方拒绝」；②抓到 iPhone 在浏览链路上 **`q=0`**（只答不问，`speaks every 946 ms`），故阈值改按「凑不凑得齐」分档（能连 2 200 ms / 半个记录 6 000 ms），实测 **2 440 ms** 删掉对端，不再闪回；③顺带撞出并修掉 `awdltun0` 换 ifindex 后 socket **静默变聋** 的 bug（`Peers.rebind()`：8 秒不搬、回来 1.3 秒搬回）。详见 §5.4 / §5.8 / §6.4 |
| **2026-11-18 16:20** | **归档并入**：`Task-archive-20261118.md`（6119 行 / 119 个标题）里仍有价值的四块——Android 运行时 13 条、构建与工具链 7 条、内核构建链现状、度量基线——按本文口径重排进 §6.4 / §6.5 / §8.3 / §9，重复段落剔除，归档文件删除 |
| 2026-11-18 16:09 | **README 修正**：`381 条 JVM 断言` → 脚本真实口径 `375 条 ok`；`device.name` 留空时改用系统设备名（蓝牙名）；`log.level` 默认 `debug` + 日志落 `Documents/AirDrop/` 一天一个文件 |
| **2026-11-18 16:00** | **`recv_fail` 复验（真机三重证据）**：三次「接收失败」卡全部停在 **3%**（`0.2 MB / 4.8 MB`），没有一根把进度拉到满格；`dumpsys` 里是 `ONGOING_EVENT` 常驻卡，卡上只挂【完成】，截图与日志 `15:58:34` / `15:59:59` / `16:00:02` 三行对齐 |
| 2026-11-18 09:03 | **重编 + 装机**：`BUILD SUCCESSFUL`，`adb install -r` 成功；`.so` 704 464 B、APK 14 359 002 B（clean 后真值） |
| **2026-11-18 08:40** | **裁掉 hook 路线（用户实测否决）**：卡片确实展开了（`front-suppress bypassed`），但**通知中心里不显示这条流体云、点空白区域不收缩**，还有别的 Bug。连带删除 `FluidModule`、`src/stubApi/`、`compileStubApi`/`stubApiJar`、`META-INF/xposed/{module.prop,scope.list,java_init.list}`、`SendNotice.java`；`dependencies` 回到有意留空。三张收尾卡改成纯应用侧 `finishWithActions` / `failedWithActions` / `invite`。详见 §2.1 |
| 2026-11-18 05:34 | `device.name` 留空不再退成机型，改落系统设备名；`log.level` 默认值改 `debug`；摘掉 `log.preview_bytes`（设置表回到 15 行） |
| **2026-11-18 05:20** | ~~前台抑制绕开（真机通过）~~：`FluidModule` 并入主应用，三份 `META-INF/xposed` 声明 + 编译期存根，钩 `ClassLoader.loadClass` 等插件加载 `interceptor.a`，命中后压 `model.e=false` 再接 `chain.g`。日志 `front-suppress bypassed: com.oshare.airdrop -> false`，插件自己的 `shouldFilter ret true` 消失；前台胶囊与展开卡片均有截图。**——这条路线当天 08:40 被裁掉**，见上 |
| **2026-11-18 02:40** | **参数固化**：`capacity=256/lowmark=128`、`nss=2`、`retry=0`、`tid=-1` 等 22 项写进 `tools/awdl-stack.sh` 的 `TUNE_INSMOD`；新增 `apply_tuning()` 在 `link_up` 末尾回读校验并检查 `bridge_pending` 积压。APK 重编并装机。Task.md 整体重写 |
| 2026-11-18 02:22 | **3.24 MB/s 稳态达成**：唯一变量 `capacity 32→256`（2.25 → 2.59 全程 / 3.24 稳态），native 20.65 → 17.2 ms |
| 2026-11-18 02:00 | 照抄 3.2 快照仍只有 2.25 → 定位到 `capacity=32` 在给 TCP 定速；同时发现快照里 `nss=2` 而脚本一直是 1 |
| 2026-11-18 01:30 | 否掉 `tid=100`（0.93 MB/s）与 `retry=Y`（1:1 重传、零收益），双双回滚 |
| 2026-11-18 01:00 | 纠正统计口径：24.89 MB 出自 11-17 旧日志；真实曲线 11 秒 25.76 MB、稳定 2.6 MB/s、无断流 |
| 2026-11-17 22:52 | `collect_us=1500` 真生效（2.4 → 2.82 MB/s）；抓包推翻「窗口墙」旧结论 |
| 2026-11-17 20:42 | 结掉 P2：接收端「2 份」的第二份是苹果的 `._` 伴生文件 |
| 2026-11-17 20:31 | 空口证据：真机 iPhone 的 TXT 里只有 `flags`，没有 `h` |
| 2026-11-17 20:25 | 自有发送面板向真实 iPhone 发出文件成功 |
| 2026-11-17 19:48 | 真实 iPhone 经 insmod 栈把文件送进相册 |
| 2026-11-17 19:35 | AWDL 整栈首次在「不动 boot.img」路线上跑通，拦路石是 1 MB 连续内存 |
| 2026-11-17 18:28 | 内核基线编完（脚本退出码 0） |
| 2026-11-17 03:24 | HT 能力通告补全：642 → 857 kB/s（接收向唯一正结果） |
| 2026-11-16 | 协议三端点、TLS、落盘、三处提示、发送面板、`SendActivity` 逐项真机跑通 |
| 2026-11-15 | Rust 架构成型；`wonder0` IBSS 三定律定稿；KernelSU → LSPosed 路线确定 |
