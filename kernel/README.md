# kernel：随包内核载荷与驱动源码

## 这个目录是干什么的（先读这一段）

OPAirDrop 要在骁龙 8 Elite（sm8750）上跑 Apple AWDL 链路，标准 Android Wi‑Fi 驱动不提供
passthru / monitor 混杂收帧、NDI vdev 身份、AWDL 目标过滤这些能力。本目录解决的就是这件事：
**带一组打过补丁的 WLAN 驱动，由 APK 在设备上用 `insmod` 顶掉原厂同门模块**，从而拿到一条
可控的 AWDL 数据面。

- **载荷**：下面 5 个文件（4 个 `.ko.gz` + `wonder-ven`）是真正打进 APK、在设备上加载的字节。
- **源码**：`source/` 是这些字节的可复现来源——覆盖层（最终全文）+ 编号补丁（对官方树的改动）
  + 构建脚本。**2026-11-22 起，这颗 qca 由我们自己从源码编出并维护**（`passthru_aux_wide`），
  不再只是"第三方给的字节"。
- **回滚保证**：`/vendor/lib/modules` 下的原厂模块分毫未动，`insmod` 顶替、重启即回原厂；
  链路起不来时 `awdl-stack.sh stop` 也能恢复。

载荷与源码必须一起看，否则这些二进制只是一堆黑盒。目录位于**仓库根目录**
`repo-src/kernel/`（2026-11-22 从 `android/kernel/` 移上来）；Gradle 的
`stageKernelAssets` 任务从这里读取，编译期 gunzip 进 APK 的 `assets/kernel/`。

## 载荷

| 文件 | 原始字节 | gz | sha256（原始字节） | 是什么 |
| --- | --- | --- | --- | --- |
| `qca_cld3_peach_v2.ko.gz` | 20 037 200 | 5 280 202 | `449d6915af6a540176f9e29b2b88c786fe19e5dd368874e377819b0f68496c3a` | qcacld-3.0，**自建修复版（裸 ko sha256 `eac00ca0…`，11-22 09:41 编译）**——BA 响应里宣告 A-MSDU Supported（`passthru_awdl_ba_amsdu`），是**接收 4.25 MB/s 的那颗**。`152b93ad…`（旧随包字节，`.gz.bak`）bit0 恒 0 → 对端永不聚合 → 只有 443 kB/s。详见下方演进链 |
| `mac80211.ko.gz` | 17 659 760 | 5 932 779 | `5fb77cc711c94b344be691a5f06d8dc0d382df7aff9b37d42beb024382da9645` | mac80211，把 minstrel_ht 编进模块本身 |
| `wonder.ko.gz` | 78 432 | 21 694 | `f06bf8f1db32226a6f7be79af035c097cdace372bf77097daef7a03de8a6184f` | `wonder0` 的驱动，一个 mac80211 驱动 |
| `opairshare_wonder_provider.ko.gz` | 271 472 | 95 401 | `b13eb6e1497611cec6f0c8724e7aef2b19ea2e151b9b202d06f3da94fdb6d6f2` | 把上面两颗绑到一起的 provider |
| `wonder-ven` | 12 072 | — | `05addc366703d8885ac2ddfeec9a2cb576bee622edb215bdfb7f35b17f53d677` | vendor 命令小工具，`wonder-ven <if> feed <freq> <bssid>` |

仓库根目录的 `tools/make-kernel-assets.sh` 从一棵编好的平台树重新生成前四行并打印这张表的 sha256 列；
两边不一致时，说明载荷不是那棵树出的，脚本会指出是哪一个文件。

## 相对原厂改了什么

原厂模块取自设备 `/vendor/lib/modules`（同一天用 `su -c cat` 取回，`sha256` 见下），
四颗的 `vermagic` 逐字符相同：`6.6.118-android15-8-o-4k SMP preempt mod_unload modversions aarch64`。
所以下面比的不是版本，是内容。

**`qca_cld3_peach_v2`**（原厂 19 736 456 B → 现役 20 039 576 B）

> 演进链（每一版都在真机上跑过）：
> - `0555d6c4…`（原厂 19 736 456 B）→ `eac00ca0…`（20 037 200 B）：打通 passthru/AWDL 通路。
> - → `20 038 648 B`：多修一处 `dev_hold` 泄漏。SSR 状态下流控那份 `dev_hold` 的归还函数
>   `opairshare_flow_forget_locked()` 两道闸必然失败（`accepting` 仍为真、
>   `flow_control_locked()` 对已死固件回 `-EIO`），`dev_put` 永不执行，于是
>   `unregister_netdevice: waiting for wondertap0 to become free. Usage count = 2` 永远刷。
>   新增的 `opairshare_flow_force_forget_locked()` 是它的无条件对应物，由
>   `opairshare_pas_mgmt_abandon()` 在拆除路径上调用。**已在真机验证** `Usage count` 归零
>   （判据见 `Plan.md` §3.6.11）。
> - → **现役 `20 039 576 B`（我们自建，2026-11-22）**：新增 `passthru_aux_wide`（默认 1、运行时
>   可回退）。wide profile 下把 aux monitor vdev 从写死的 20 MHz/11n 抬到与主 vdev 一致的
>   **EHT80**（chanmode 26），消除接收侧"20 MHz monitor 只听到 80 MHz 帧切片"的结构瓶颈。
>   真机验证：`dmesg OPAIRSHAREAUXWIDE: mon vdev at 80 MHz EHT`、vdev0/vdev1 均为 chanmode 26、
>   `hang=0` 不崩固件。源码改动在 `source/driver/wlan_hdd_opairshare_aux_rx.h`。

- 符号 CRC：`__versions` 588 条，**588/588 逐条相同，0 条不一致，0 条只存在于原厂**。
  这是这颗能 `insmod` 的真正原因——`MODVERSIONS=y` 时 `same_magic()` 只比第一段之后的部分，
  release 串不同也照装，卡的是符号 CRC。
- 新增 12 条依赖：`cnss_set_vendor_wonder_priv_data`、`dev_add_pack`、`dev_close`、`dev_open`、
  `dev_remove_pack`、`get_random_u32`、`kstrtobool`、`module_put`、`param_get_bool`、
  `param_get_uint`、`schedule_timeout`、`try_module_get`。全是标准 KMI 里的导出，补丁新增的数据面
  与模块参数用的就是这些。
- 导出符号 0 → 2：`oneplus13_passthru_tx_count`、`opairshare_wondertap_test_priv`。provider 绑的就是它们。
- 模块参数 6 → 62 条 `passthru_*`（在线时 `/sys/module/qca_cld3_peach_v2/parameters` 共 117 条）。
  这些开关就是这条链路的手动挡：`passthru_awdl_phy`（0 = legacy OFDM，2 = HT，3 = VHT）、
  `passthru_awdl_mcs`、`passthru_awdl_amsdu*`、`passthru_awdl_target_mac`、`passthru_sta_coexist` 等。

**`mac80211`**（1 963 888 B → 17 659 760 B；装载后 1 093 632 → 1 118 208 B）

- 原厂 `sun_perf.config` 根本没开速率控制（`CONFIG_MAC80211_RC_MINSTREL` 未设、`RC_DEFAULT` 空）。
  这块 tap 之前没有任何东西用 mac80211，所以没人发现。`wonder.ko` 是 mac80211 驱动，
  `ieee80211_register_hw()` 没有可用算法就拒绝注册，于是 `Failed to select rate control algorithm` /
  `Ret: -2`。补丁把 minstrel_ht 编成 `=y`，现在模块里带 `ieee80211_default_rc_algo` 与
  `minstrel_vht_only` 两个参数。
- 符号 CRC：317 → 319，`+kmalloc_large`、`+param_ops_bool`，0 条不一致；导出 136 条前后相同。
- 文件从 1.9 MB 涨到 17.6 MB 不是代码涨了：其中 8.36 MB 是 `.debug_*`、7.8 MB 是 `.rela.*`，
  原厂同文件两者都是 1.96 MB 里的零头。**这是已知债务**——压成 gz 之后 APK 仍多背约 5 MB，
  而 strip 掉调试段不影响装载（`llvm-strip --strip-debug` 已在自制 qca 上验过一次：
  511 MB → 19.7 MB，`insmod` 照常）。这一颗没有 strip，因为没有理由在改别的东西时顺手换字节。

**`wonder` / `opairshare_wonder_provider`**：原厂没有这两个模块，全新。`wonder.ko` 4 个参数，
装载后由它创建 `wonder0`（IBSS、ch149、20 MHz、BSSID `00:25:00:ff:94:73`）；provider 把 qca 导出的
两个符号接到 wonder 上，缺它 `wonder_probe` 找不到 wiphy。

## 源码

`source/` 是上游 `kernel/` 目录的逐字节拷贝，取自 `Weverses/OPAirDrop`：

| 路径 | 内容 |
| --- | --- |
| `source/driver/` | 46 个覆盖文件，`source-map.json` 逐条给出它们在平台树里的落点；另有 `driver/legacy/` 两个不参与编译的证据文件（见下） |
| `source/wonder/` | wonder 驱动与它要的 mac80211 接口（22 个文件） |
| `source/provider/` | provider 与绑定探针（4 个文件） |
| `source/patches/` | 9 个编号补丁 + 1 个候选版 `0007-session-dp-candidate.patch`，都是对官方树做的**改动**；新增的头文件靠覆盖层拷进去 |
| `source/source-map.json` | 覆盖文件 → `kernel_path` 的映射，附每个文件的 sha256 |
| `source/expected-absent.txt` | 构建树里有、但我们**故意不复现**的死文件清单（附 sha256），冷验证据此区分「预期缺失」与真缺口 |
| `source/expected-files.txt` | 构建树相对上游 HEAD 的 114 个改动文件清单，`verify-reproduce.sh` 的比对基准 |
| `source/tools/` | 上游那七个脚本：`sync-source.sh`、`build-baseline.sh`、`build-wlan-baseline.sh`、`build-wlan-feature-check.sh`、`build-route-b-provider.sh`、`module_abi.py`、`module_exports.py` |
| `source/README.md` | 上游原文，保留不动；它引用的 `../docs/*` 与 `ROOT-MOUNT-COMPATIBILITY.md` 不随本仓库，看这份就够 |

`source/driver/legacy/` 里两个文件是 2026-11-20 那次映射事故的物证，**不参与编译、不进
`source-map.json`**，留着只为让后来人能对着字节确认「构建树里那两个多余文件确实是它们」：

| 文件 | sha256 | 是什么 |
| --- | --- | --- |
| `target_if_p2p.c.cmn-dead` | `b78fcbd0…ea09` | `qca-wifi-host-cmn/target_if/p2p/src/` 那份死拷贝，BZL 未登记，就是 `-110` 的根因 |
| `wondertap.h.hdd-inc-dup` | `42832fe1…0546` | 与 `wonder/include/wonder/wondertap.h` 同 md5 的重复件，全树无 `#include` 指向它 |

## 上游源码从哪拉、怎么编（完整流程）

### 拉哪个仓库

平台基线是 **OnePlusOSS** 的 `oneplus/sm8750` 分支，清单 `oneplus_13_b.xml`：
`https://github.com/OnePlusOSS/kernel_manifest`（`tools/kernel/sync-source.sh` 里写死）。
三个子项目的 commit 也被钉死在那个脚本的 local_manifest 里：

| 项目 | 钉死的 commit |
| --- | --- |
| `android_kernel_common_oneplus_sm8750` | `e1b346b6b4f4096eb342ae3684838a942fd6f6c4` |
| `android_kernel_oneplus_sm8750` | `6028f47faddaa27700f8dd3a1d83906ea8f27170` |
| `android_kernel_modules_and_devicetree_oneplus_sm8750` | `d50b305f7da9e14715a25120a4ac7b1a4b8b97c3` |

设备是 OnePlus 13（sm8750）、内核 `6.6.118-android15`，WLAN 芯片 qca_cld3_peach_v2
（WCN785x / QCA5332 一系）。驱动覆盖层的原始出处是 `Weverses/OPAirDrop` 的 `kernel/` 目录，
但**现役这颗 qca 是我们基于其上维护的**（见上节），不要以为拉上游就能拿到字节相同的模块。

### 构建环境

在 WSL / Ubuntu 的 **ext4** 卷上建构建树（默认 `/mnt/opairshare-build`， ext4 是硬要求——
`sync-source.sh` 会检查 FSTYPE）。**必须以普通用户构建，不能用 root**；`build-wlan-baseline.sh`
开头就拒绝 `$EUID == 0`。工具链随源码树下载（clang-r510928），不需要系统装 clang。

```bash
# 0. 建卷、拉源（一次性；HTTP/2 传输失败被限制在单次 job，不改全局 git config）
bash tools/kernel/sync-source.sh

# 1. 把我们的覆盖层 + 补丁铺到 pristine 树上（先补丁后覆盖层，顺序不可换）
bash tools/kernel/apply-source.sh            # 应用到真实树
bash tools/kernel/apply-source.sh --check    # 在临时副本彩排，真实树不碰
#   期望 applied=10 failed=0；--check 结尾 CHECK_OK

# 2. 单独编 qcacld（WLAN 不在 kernel_platform 的 Kleaf workspace 里，
#    借 kernel_platform/vendor -> ../vendor 软链引进来）
bash tools/kernel/build-wlan-baseline.sh
#   产物 /mnt/opairshare-build/baseline-wlan/qca_cld3_peach_v2.ko（约 513 MB，几乎全是 debug_info）

# 3. strip + gzip 成载荷
#   用源码树自带的 llvm-strip（绝对路径，避免 wsl.exe bash -lc 吞 $VAR）：
STRIP=/mnt/opairshare-build/opairshare/kernel_platform/prebuilts/clang/host/linux-x86/clang-r510928/bin/llvm-strip
"$STRIP" --strip-debug qca_cld3_peach_v2.ko -o stripped.ko   # → 约 20 MB
gzip -9 -n -c stripped.ko > kernel/qca_cld3_peach_v2.ko.gz   # -n 保证可复现
#   同步更新本 README 载荷表的字节数与 sha256（tools/make-kernel-assets.sh 会打印这张表）
```

### 编译流程里踩过的坑（都是真机/冷验证换来的）

1. **顺序：先补丁、后覆盖层。** 补丁的上下文是上游原文，覆盖层是最终全文；反过来铺
   `0005/0006/0007` 必 `patch does not apply`。
2. **`vendor` 路径必须从 `$TREE` 打，不能从 `kernel_platform/` 打。** `kernel_platform/vendor`
   是软链，从里面打补丁会**静默失效**（退出 0、文件没改）。
3. **`--check` 不是 `git apply --check`。** 补丁之间不独立（`0006` 创建文件、`0007` 再改），
   dry-run 看不到前序产物必报假警。脚本改成导出 pristine 到临时副本真跑一遍。
4. **`source-map.json` 的 key 是 `kernel/driver/x.c`**，剥掉 `kernel/` 得到 `driver/x.c`
   已相对 `source/`；再拼一层 `driver/` 就错（旧脚本 overlay 从没成功过）。
5. **`qdf_nbuf_get_radiotap_len()` 函数体只在废弃的 candidate 版 `0007` 里**，生效版只有声明
   和调用；补成独立 `0010`，不动生效版一个字节。
6. **`opairshare_passthru_monitor_active` 必须有 out-of-line 定义**（`wlan_hdd_wondertap.c` 里
   `return false;`），否则 modpost 报 undefined。
7. **改头文件会触发全内核重编**（`/mnt/opairshare-build` 上约 99 分钟；关键路径 LTO 链接占
   ~16 分钟），不是只编 WLAN 模块。增量构建会快很多。
8. **`wsl.exe bash -lc` 会吞 `$VAR`**，脚本里 `$S --strip-debug …` 会变成
   `--strip-debug: command not found`（exit 127）；必须用全绝对路径。

### 复现判据（不是"编出来能跑"，而是字节对上）

```bash
bash tools/kernel/verify-reproduce.sh
# total 115  same 113  diff 0  missing 0  expected-absent 2   （EXIT=0 才算过）
python3 kernel/check-map.py
# .c listed: 22   .c dead: 0
```

## 重建到哪一步了

2026-11-17 在 WSL 里把这条路走过一遍，**只走到「未打补丁的基线」**：

- `build-baseline.sh` 全绿（1359/1359 actions，8523 s），4K 与 16K 两套 `Image` 都落盘。
- `build-wlan-baseline.sh` 出一条 `qca_cld3_peach_v2.ko`（dist 目标只含它，不是四条）。
  原始 511 908 416 B（几乎全是 debug_info）→ `llvm-strip --strip-debug` → 19 718 512 B。
- 与原厂比：`__versions` 588/588 逐条相同，`undefined` 587 集合相同，导出皆空，均无签名尾。
- 装到设备上：`rmmod` 原厂 → `insmod` 自建，零报错，Wi-Fi 起来，网关与外网各零丢包。

**没走到的一步**（2026-11-17 时的状态）：`source/` 覆盖层 + `patches/` 全量应用之后重编。当时这条链上跑的这颗
（20 050 688 B、600 条 `__versions`、带 62 个 `passthru_*`）仍是上游给的字节，我们没有从源码
复现过它。

**2026-11-21：这一步补上了，而且是逐字节意义上的补上。** 判据不是「编出来了能跑」，而是

```text
pristine git HEAD  +  overlay  +  9 个补丁   ==   实际构建树   逐字节
total 114  same 112  diff 0  missing 0  expected-absent 2
```

114 是构建树相对 HEAD 的全部改动文件数。112 个逐字节一致；剩 2 个是上表 `driver/legacy/`
里那两个死文件，按设计不复现。任何第三方拉这个仓库跑 `tools/kernel/verify-reproduce.sh`
都应该看到同一行数——这就是「跟着官方源码 + 我们的 `source/` 能构建出同一颗驱动」的证明。

这条冷验证顺手揭出三个此前没人发现的坑，都已修在脚本里：

1. **顺序必须「先补丁、后覆盖层」。** 补丁的上下文行是**上游原文**，覆盖层给的是**最终全文**。
   先铺覆盖层再打补丁，凡是两者都碰的文件（`0005`/`0006`/`0007`）必然
   `patch does not apply` 或 `already exists in working directory`。
2. **`apply-source.sh` 的 overlay 路径曾经是错的。** `source-map.json` 的 key 是
   `kernel/driver/x.c`，剥掉 `kernel/` 得到 `driver/x.c` ——它**已经**相对 `source/` 了。
   原脚本又往 `source/driver/` 上拼，等于找 `source/driver/driver/x.c`，一次都没成功过。
3. **`qdf_nbuf.c` 缺一个函数体。** 生效版 `0007` 在 `qdf_nbuf.h` 里**声明**
   `qdf_nbuf_get_radiotap_len()`、又从 `dp_tx.c` 调它三次，但函数体只存在于废弃的
   candidate 版里。不补就是链接期 `undefined symbol`。现补成独立的 `0010`，
   不动 `0007` 一个字节（随包那颗是按 `0007` 编的）。

注册进 APK 前的验法、以及 `wonder.ko` 那个 order-8 的坑，见下面「校验清单」。

### 2026-11-22：现役这颗是我们自己维护的（aux monitor EHT80）

从这一天起载荷表第一行那颗不再只是"第三方给的字节"：我们在覆盖层里改了
`source/driver/wlan_hdd_opairshare_aux_rx.h`，新增 `passthru_aux_wide`（默认 1、0644 可运行时
回退），wide profile 下让 aux monitor vdev 用 `CH_WIDTH_80MHZ` + `eHDD_DOT11_MODE_11be`
（复用主 vdev 的 `opairshare_passthru_want_wide()` 判定），窄带仍保持 20 MHz/11n 逐字节不变。
起因是接收方向慢：20 MHz 的 monitor 只听到 iPhone 80 MHz 帧的一个切片（实测 41% 残缺帧）。
编译走了上面完整流程（99 分钟全内核重编），真机验证：`OPAIRSHAREAUXWIDE` 日志出现、
vdev0/vdev1 均 `chanmode 26` EHT80、`hang=0` 不崩固件。**运行时若某版本崩 `cause=7003`，
`echo 0 > /sys/module/qca_cld3_peach_v2/parameters/passthru_aux_wide` 即回退旧行为、无需重编。**

### 2026-11-20：自建模块第一颗，以及它为什么必崩

按上面那条顺序编出了第一颗能 `insmod` 的自建模块（20 032 464 B），链路在 vdev START 上必崩，
设备重启。现场是从 `/data/vendor/ramdump/wlan_driver_*.elf`（主机侧日志环形缓冲）里读出来的，
判据是两颗模块的 `send_vdev_create_cmd_tlv`：

```text
自建 : send_vdev_create_cmd_tlv: type 9, subtype 0, nss_2g 2, nss_5g 2
       ... 25 ms 后 wlan_hdd_pld_uevent: Received firmware down indication
随包 : send_vdev_create_cmd_tlv: OP13CREATE vdev_id=0 type=7 ...
```

`type 9` 是 `WLAN_VDEV_MLME_TYPE_PASSTHRU`。原厂固件没有这个类型，见
[`docs/SM8750-AWDL.md`](https://github.com/Weverses/OPAirDrop) 第 1 节：直接创建会触发固件断言，
固件身份要用 NDI（`7`）。这个映射由 `mlme_get_vdev_types()` 决定，在
`components/mlme/core/src/wlan_mlme_vdev_mgr_interface.c`——**上游覆盖层里没有这个文件**，
所以自建模块一直拿的是原厂的 `type 9`。现在把它补进覆盖层，并加 `passthru_fwtype` 参数
（默认 `7`；`9` 可回到参考平台的映射）。

随包那颗与**已发布**的覆盖层之间还有两处对不上，别指望逐字节复现：

- 6 个模块参数只存在于随包字节里，`source/` 全树 grep 不到：`passthru_fwtype`、
  `passthru_mon_chan_sync`、`passthru_mon_refresh`、`passthru_mon_rings`、`passthru_tx_audit`、
  `passthru_wmi_mgmt`。未知参数不会被内核拒绝（`insmod` 仍返回 0），只会被静默忽略——
  所以拿自建模块跑 `tools/awdl-stack.sh` 时，`passthru_fwtype=7` 这类开关**是没生效的**。
- 随包字节里有一整族 `OP13*` 日志标签（`OP13CREATE`、`OP13TXQUEUE`、`OP13MGMTCOMP` 等 16 个），
  已发布的 `source/driver/` 里一个都没有；反过来 `OPAIRSHARECOEXLAYOUT`、`OPAIRSHAREAUXREADY`、
  `OPAIRSHARERXFILTER` 这些数据面标签两边都有。也就是说随包那颗出自另一条命名血缘，
  不是这个覆盖层的上一版。

结论：**覆盖层能编出可用的模块，但它是"另一颗"，不是随包那颗的复刻**。任何拿自建模块做的
测量都必须先过上面的 `type=7` 判据，再去比速率。

### 2026-11-20 晚：映射错了文件，hook 是死代码

`type=7` 修好之后下一层卡在 RX filter：命令发出去了，回复没来。

```text
OPAIRSHARERXFILTER: set-submit vdev=0 freq=5745 mac=… status=0
（没有后续）
OPAIRSHARERXAUTO: start vdev=0 ... rc=-110 active=0 used=1
```

`-110` 是 `wait_event_timeout(…, 2000ms)` 超时。查 `#ifdef DRIVER_PASSTHRU_MODE` 确认宏是开的
（`CONFIG_DRIVER_PASSTHRU_MODE=y` + `CONFIG_WONDER_SUPPORT=y` → `config_to_feature.h` 里置 1），
于是问题只剩"回复到底有没有来"。

**真因是映射**：同一个 `target_if_p2p.c` 在平台树里有两份——

| 文件 | 是否编译 |
| --- | --- |
| `qcacld-3.0/components/target_if/p2p/src/target_if_p2p.c` | **是**（`wlan_qcacld3_modules.bzl:575`） |
| `qca-wifi-host-cmn/target_if/p2p/src/target_if_p2p.c` | 否，`cmn/` 下只编 `dp/` 与 `target_if/mlme/` |

`source-map.json` 原来指向 host-cmn 那份，于是 `opairshare_awdl_rx_filter_reply()` 这段 hook
被拷进了一个**不参与链接**的文件。模块照编、照装、照跑，hook 一次都没执行 → 等待队列永远没人唤醒
→ 超时 → `-110`。两份平台文件除了这段 hook 之外**逐字节相同**，所以肉眼比对看不出来。

判据不是"哪个目录名更像"，而是 `*.bzl` 里的清单。`vendor/qcom/opensource/wlan` 下的 bzl 用相对
路径登记源文件，且 `cmn/` 前缀代表 `qca-wifi-host-cmn/`：

```text
qca-wifi-host-cmn/<x>  ->  cmn/<x>
qcacld-3.0/<x>         ->  <x>
```

现在 `check-map.py` 就是照这条规则查的，改完覆盖层或映射都跑一遍（在 WSL 里，因为 bzl 在构建树）：

```bash
python3 android/kernel/check-map.py     # 期望：.c listed: 22   .c dead: 0
```

同一类错误对 `.h` 不适用——头文件跟着包含它的那个 .c 走，单独查没意义，脚本只报数不判定。
另外注意：**改了 `kernel_path` 只影响下次同步**，构建脚本不会自己拷覆盖层，改完必须手动
`cp` 到新的落点，否则编出来还是旧的那份。

## 校验清单

装之前先比一遍，不要靠「看起来能跑」：

1. **是不是同一批字节**：`su -c sha256sum /data/local/tmp/opairdrop/*.ko`，与上面那张表对。
   APK 在装载前自己也会按 SHA-256 逐个核，核不过就不装。
2. **符号 CRC**：`python3 source/tools/module_abi.py check --stock <原厂.ko> --symvers <Module.symvers>`。
   注意退出码：`0` 不是「通过」，是「仅 CRC 一致」，没有 KCFI、结构体布局、GKI 访问或固件兼容的任何结论。
   `INCOMPLETE` 也不是失败——缺的那 104 条是 `cnss_*` / `cld80211_*`，内核 `Module.symvers` 里
   `grep -c cnss_` 就是 0，兄弟模块之间的符号不可能出现在那里。判据是 ko↔ko 直比。
3. **装载后**：`dmesg` 里不应有 `disagrees about version`、`Unknown symbol`、`invalid module format`、
   `KCFI`、`BUG:`、`Oops` 任一条；`lsmod` 四行都在；`iw dev wonder0 info` 报 ch149 / 20 MHz。
4. **回滚**：`/vendor` 全程没动过。`rmmod` 四个自建模块，或直接重启，回到原厂。

`wonder.ko` 还有一个与 ABI 无关的坑：`ieee80211_txq_setup_flows()` 要一次约 1 MB 的连续内存
（order 8），碎片化时 `insmod` 六次全 `-12`，而 `MemAvailable` 还有 6 GB。
仓库根目录 `tools/awdl-stack.sh` 的 `defrag_memory()` 就是为这个写的，判据看 `/proc/buddyinfo` 的最大非零 order，
不是看可用内存。
