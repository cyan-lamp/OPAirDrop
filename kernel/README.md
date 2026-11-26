# kernel：随包内核载荷与驱动源码

## 本目录的职责

OPAirDrop 需要在骁龙 8 Elite（sm8750）上运行 Apple AWDL 链路。标准 Android Wi‑Fi 驱动不提供 passthru / monitor 混杂收帧、NDI vdev 身份与 AWDL 目标过滤能力。本目录提供一组打过补丁的 WLAN 驱动，由 APK 在设备上用 `insmod` 顶掉原厂同门模块，从而得到可控的 AWDL 数据面。

- **载荷**：下表 5 个文件（4 个 `.ko.gz` + `wonder-ven`）是真正打进 APK、在设备上加载的字节；Gradle 的 `stageKernelAssets` 任务从本目录读取，编译期 gunzip 进 APK 的 `assets/kernel/`。
- **源码**：`source/` 是上游驱动的覆盖层、编号补丁与构建脚本，可复现构建基线。**现役载荷字节是随包供应商版**，源码中的 aux monitor EHT80 改动待将来重编（见「重建到哪一步了」）。
- **回滚保证**：`/vendor/lib/modules` 下的原厂模块分毫未动，`insmod` 顶替、重启即回原厂；链路起不来时 `tools/awdl-stack.sh stop` 也能恢复。

## 载荷

| 文件 | 原始字节 | gz | sha256（原始字节） | 是什么 |
| --- | --- | --- | --- | --- |
| `qca_cld3_peach_v2.ko.gz` | 20 050 688 | 5 282 983 | `152b93ad51c093be8ea54154121c4070573277f1711bb7d85deeb132b750c7a4` | qcacld-3.0，**随包供应商驱动（现役，2026-11-22 定案回装）**。TX 3.24 MB/s 稳态；RX 309 KB/s 均值 / 3.68 MB/s 峰值（设备期实测，待核），峰值来自 `ratewatch` 两阶段速率切换（VHT80/2SS/MCS9），非驱动本身。曾自建 A-MSDU 实验 ko（裸 sha `eac00ca0…`）已退役：受控 A/B 三轮实测其 RX 仅为随包的 1/12–1/20（待核），自建 ko 的 RX 数字不作数。gz sha256 `d773d67660b0fe74c5224f902a0d35043803c90ff489227ecd4d2fbf515ffd07` |
| `mac80211.ko.gz` | 17 659 760 | 5 932 779 | `5fb77cc711c94b344be691a5f06d8dc0d382df7aff9b37d42beb024382da9645` | mac80211，将 minstrel_ht 编入模块本身 |
| `wonder.ko.gz` | 78 432 | 21 694 | `f06bf8f1db32226a6f7be79af035c097cdace372bf77097daef7a03de8a6184f` | `wonder0` 的驱动，一个 mac80211 驱动 |
| `opairshare_wonder_provider.ko.gz` | 271 472 | 95 401 | `b13eb6e1497611cec6f0c8724e7aef2b19ea2e151b9b202d06f3da94fdb6d6f2` | 将上述两颗模块绑定的 provider |
| `wonder-ven` | 12 072 | — | `05addc366703d8885ac2ddfeec9a2cb576bee622edb215bdfb7f35b17f53d677` | vendor 命令小工具，用法 `wonder-ven <if> feed <freq> <bssid>` |

注：`tools/make-kernel-assets.sh` 从编好的平台树重新生成前四行并打印 sha256 列，与表不一致即说明载荷并非该树所出；`kernel/PAYLOAD.sha256` 登记 gz 字节的 sha256，`tools/verify-payload.sh` 据此做仓库门禁。
表中速率、耗时等设备或构建机记录类数值无法在本仓库离线复核，统一标注「待核」；字节数、sha256 与文件计数均按仓库文件核算。

## 相对原厂改了什么

原厂模块取自设备 `/vendor/lib/modules`。随包四颗的 `vermagic` 逐字符相同：`6.6.118-android15-8-o-4k SMP preempt mod_unload modversions aarch64`，因此与原厂比较的是内容而非版本。

**`qca_cld3_peach_v2`**（原厂 19 736 456 B → 现役随包 20 050 688 B）

版本关系：原厂 `0555d6c4…`（19 736 456 B，待核）→ 自建 `eac00ca0…`（20 037 200 B，打通 passthru / AWDL 通路）→ 20 038 648 B（补 `dev_hold` 泄漏的强制归还路径 `opairshare_flow_force_forget_locked()`，SSR 状态下 `Usage count` 可归零，判据见开发机的本地任务书 `Plan.md`（不入库）§3.6.11）→ 20 039 576 B（自建 `passthru_aux_wide`，已退役）。现役为随包供应商版 20 050 688 B，与载荷表第一行一致。

- 符号 CRC：`__versions` 600 条，其中 588 条与原厂逐条相同，0 条不一致，0 条只存在于原厂，其余 12 条为新增依赖。这是本模块可 `insmod` 的根本原因：`MODVERSIONS=y` 时 `same_magic()` 只比较第一段之后的部分，release 串不同也照装，真正的门槛是符号 CRC。
- 新增 12 条依赖：`cnss_set_vendor_wonder_priv_data`、`dev_add_pack`、`dev_close`、`dev_open`、`dev_remove_pack`、`get_random_u32`、`kstrtobool`、`module_put`、`param_get_bool`、`param_get_uint`、`schedule_timeout`、`try_module_get`，均为标准 KMI 导出。
- 导出符号 0 → 2：`oneplus13_passthru_tx_count`、`opairshare_wondertap_test_priv`。
- 模块参数 6 → 62 条 `passthru_*`（在线时 `/sys/module/qca_cld3_peach_v2/parameters` 共 117 条）。这些开关即该链路的运行时控制面：`passthru_awdl_phy`（0 = legacy OFDM，2 = HT，3 = VHT）、`passthru_awdl_mcs`、`passthru_awdl_amsdu*`、`passthru_awdl_target_mac`、`passthru_sta_coexist` 等。

**`mac80211`**（原厂 1 963 888 B → 17 659 760 B；装载后 1 093 632 → 1 118 208 B，待核）

- 原厂 `sun_perf.config` 未开启速率控制（`CONFIG_MAC80211_RC_MINSTREL` 未设、`RC_DEFAULT` 为空）。此前的 tap 不经过 mac80211，故无人察觉。`wonder.ko` 是 mac80211 驱动，`ieee80211_register_hw()` 无可用算法即拒绝注册，报 `Failed to select rate control algorithm` / `Ret: -2`。补丁把 minstrel_ht 编入模块本身，现带 `ieee80211_default_rc_algo` 与 `minstrel_vht_only` 两个参数。
- 符号 CRC：317 → 319（新增 `kmalloc_large`、`param_ops_bool`），0 条不一致；导出 136 条前后相同。
- 文件从 1.9 MB 增至 17.6 MB 并非代码增长：8.36 MB 为 `.debug_*`、7.8 MB 为 `.rela.*`，原厂同文件中的调试与重定位段占比很小。这是已知债务：gz 后 APK 仍多背约 5 MB，而 strip 调试段不影响装载（`llvm-strip --strip-debug` 已在自制 qca 上验过一次：511 MB → 19.7 MB，`insmod` 照常，待核）。本颗未 strip，理由是不在无关改动中顺手更换字节。

**`wonder` / `opairshare_wonder_provider`**：原厂无此二模块，均为全新。`wonder.ko` 有 4 个参数，装载后由它创建 `wonder0`（IBSS、ch149、20 MHz、BSSID `00:25:00:ff:94:73`）；provider 负责将 qca 与 wonder 绑定，缺少它 `wonder_probe` 找不到 wiphy。

## 源码目录

`source/` 是上游 `kernel/` 目录的覆盖层与补丁，取自 `Weverses/OPAirDrop`；各子目录的用法见
[`source/README.md`](source/README.md)。载荷与源码须一起阅读，否则这些字节只是黑盒。

| 路径 | 内容 |
| --- | --- |
| `source/driver/` | 46 个覆盖文件；`source-map.json` 逐条给出平台树落点与 sha256 |
| `source/wonder/` | wonder 驱动与所需 mac80211 接口，23 个文件 |
| `source/provider/` | provider 与绑定探针，4 个文件 |
| `source/patches/` | 11 个编号补丁（`0001`–`0011`）+ 1 个废弃候选 `0007-session-dp-candidate.patch`（仅供 diff，脚本自动跳过）；新增头文件靠覆盖层拷入 |
| `source/expected-files.txt` | 构建树相对上游 HEAD 的 115 个改动文件清单，`verify-reproduce.sh` 的比对基准 |
| `source/expected-absent.txt` | 构建树中存在、但**故意不复现**的 2 个死文件（附 sha256），冷验证据此区分「预期缺失」与真缺口 |
| `source/tools/` | 上游 7 个脚本：`sync-source.sh`、`build-baseline.sh`、`build-wlan-baseline.sh`、`build-wlan-feature-check.sh`、`build-route-b-provider.sh`、`module_abi.py`、`module_exports.py` |

`source/driver/legacy/` 中 2 个文件是 2026-11-20 映射事故的物证，**不参与编译、不进 `source-map.json`**，保存目的仅为便于后人对照字节确认「构建树里那两个多余文件确实是它们」：

| 文件 | sha256 | 是什么 |
| --- | --- | --- |
| `target_if_p2p.c.cmn-dead` | `b78fcbd0…ea09` | `qca-wifi-host-cmn/target_if/p2p/src/` 中的死拷贝，BZL 未登记，即 `-110` 超时的根因 |
| `wondertap.h.hdd-inc-dup` | `42832fe1…0546` | 与 `wonder/include/wonder/wondertap.h` 同 md5 的重复件，全树无 `#include` 指向 |

## 上游从哪拉、怎么编

平台基线为 **OnePlusOSS** 的 `oneplus/sm8750` 分支，清单 `oneplus_13_b.xml`，仓库 `https://github.com/OnePlusOSS/kernel_manifest`。三个子项目的 commit 钉死在 `tools/kernel/sync-source.sh` 的 local_manifest 中：

| 项目 | 钉死的 commit |
| --- | --- |
| `android_kernel_common_oneplus_sm8750` | `e1b346b6b4f4096eb342ae3684838a942fd6f6c4` |
| `android_kernel_oneplus_sm8750` | `6028f47faddaa27700f8dd3a1d83906ea8f27170` |
| `android_kernel_modules_and_devicetree_oneplus_sm8750` | `d50b305f7da9e14715a25120a4ac7b1a4b8b97c3` |

设备为 OnePlus 13（sm8750）、内核 `6.6.118-android15`，WLAN 芯片 qca_cld3_peach_v2（WCN785x / QCA5332 一系）。构建环境：在 WSL / Ubuntu 的 **ext4** 卷上建树（默认 `/mnt/opairshare-build`，`sync-source.sh` 检查 FSTYPE）；**须以普通用户构建，不能使用 root**（`build-wlan-baseline.sh` 开头即拒绝 `$EUID == 0`）；工具链随源码树下载（clang-r510928），无需系统安装 clang。

```bash
# 0. 建卷、拉源（一次性；HTTP/2 传输失败被限制在单次 job，不改全局 git config）
bash tools/kernel/sync-source.sh
# 1. 将覆盖层与补丁铺到 pristine 树（先补丁后覆盖层，顺序不可换）
bash tools/kernel/apply-source.sh            # 应用到真实树
bash tools/kernel/apply-source.sh --check    # 在临时副本彩排，真实树不碰
#   期望 applied=10 failed=0（0001 的 hunk 已并入 0003，脚本自动跳过）；--check 结尾 CHECK_OK
# 2. 单独编 qcacld（WLAN 不在 kernel_platform 的 Kleaf workspace 内，
#    借 kernel_platform/vendor -> ../vendor 软链引入）
bash tools/kernel/build-wlan-baseline.sh
#   产物 /mnt/opairshare-build/baseline-wlan/qca_cld3_peach_v2.ko（约 513 MB，几乎全是 debug_info）
# 3. strip + gzip 成可装载载荷（该产物用于复现与实验）
#   使用源码树自带的 llvm-strip，以绝对路径避免 wsl.exe bash -lc 吞 $VAR：
STRIP=/mnt/opairshare-build/opairshare/kernel_platform/prebuilts/clang/host/linux-x86/clang-r510928/bin/llvm-strip
"$STRIP" --strip-debug qca_cld3_peach_v2.ko -o stripped.ko   # → 约 20 MB
gzip -9 -n -c stripped.ko > kernel/qca_cld3_peach_v2.ko.gz   # -n 保证可复现
#   同步更新载荷表的字节数与 sha256（tools/make-kernel-assets.sh 会打印这张表）
```

## 编译流程里踩过的坑

1. **顺序：先补丁、后覆盖层。** 补丁的上下文是上游原文，覆盖层是最终全文；顺序相反时 `0005`/`0006`/`0007` 必报 `patch does not apply`。
2. **`vendor` 路径必须从 `$TREE` 打补丁。** `kernel_platform/vendor` 是软链，从 `kernel_platform/` 内打会**静默失效**（退出 0、文件未改）。
3. **`--check` 不是 `git apply --check`。** 补丁之间不独立（`0006` 创建文件、`0007` 再改），dry-run 看不到前序产物而误报；脚本改为导出 pristine 到临时副本真跑一遍。
4. **`source-map.json` 的 key 是 `kernel/driver/x.c`。** 剥掉 `kernel/` 后得到 `driver/x.c`，已相对 `source/`；再拼一层 `driver/` 即错（旧脚本 overlay 从未成功）。
5. **`qdf_nbuf_get_radiotap_len()` 的函数体只存在于废弃候选 `0007`。** 生效版只有声明与调用；补成独立 `0010`，不动生效版一个字节。
6. **`opairshare_passthru_monitor_active` 必须有 out-of-line 定义**（`wlan_hdd_wondertap.c` 内 `return false;`），否则 modpost 报 undefined。
7. **改头文件会触发全内核重编**（`/mnt/opairshare-build` 上约 99 分钟，其中 LTO 链接约 16 分钟；构建机记录，待核），并非仅编 WLAN 模块；增量构建快很多。
8. **`wsl.exe bash -lc` 会吞 `$VAR`**，脚本内 `$S --strip-debug …` 会变成 `--strip-debug: command not found`（exit 127）；必须使用全绝对路径。

## 复现判据

判据是字节对上，而非「编出来能跑」：

```bash
bash tools/kernel/verify-reproduce.sh
# total 115  same 113  diff 0  missing 0  expected-absent 2   （EXIT=0 才算过）
python3 kernel/check-map.py
# .c listed: 22   .c dead: 0
```

`verify-reproduce.sh` 从 git HEAD 导出干净树 → 运行 `apply-source.sh` → 与实际构建树逐字节比较。
构建树由两个 git 仓库组成：根仓库覆盖 `vendor/` 与 `kernel_platform/oplus/`，`kernel_platform/msm-kernel/`
为独立仓库，脚本分别导出后再叠加覆盖层与补丁；任何第三方按仓库内容运行，都应得到同一行数字。
`check-map.py` 以构建树中的 `*.bzl` 清单为判据，核对每个覆盖层 `.c` 是否真的参与编译；清单按路径前缀
重映射（`qca-wifi-host-cmn/<x> -> cmn/<x>`，`qcacld-3.0/<x> -> <x>`）。因其读取构建树，须在 WSL / 构建卷内运行。

## 重建到哪一步了

- **未打补丁的基线已复现**：官方源码树可完整构建，产物能在真机 `insmod` 并正常起 Wi‑Fi。
- **打过补丁的也已逐字节复现（113/115）**：pristine HEAD + 覆盖层 + 11 个补丁与实际构建树比较，115 个改动文件中 113 个逐字节一致、0 个不同、0 个缺失，其余 2 个为 `source/driver/legacy/` 的预期缺失死文件。
- **aux monitor EHT80 改动仍在源码中**（`source/driver/wlan_hdd_opairshare_aux_rx.h` 的 `passthru_aux_wide`），待将来重编；现役载荷字节为随包供应商版（2026-11-22 定案回装）。

## 校验清单

装载前先比对，不以「看起来能跑」为准：

1. **是否同一批字节**：设备侧 `su -c sha256sum /data/local/tmp/opairdrop/*.ko`，与载荷表对照；APK 在装载前亦按 SHA-256 逐个核对，核不过即不装。仓库侧可运行 `bash tools/verify-payload.sh`，按 `kernel/PAYLOAD.sha256` 核五份载荷。
2. **符号 CRC**：`python3 source/tools/module_abi.py check --stock <原厂.ko> --symvers <Module.symvers>`。退出码 `0` 并非「通过」，仅表示「CRC 一致」，对 KCFI、结构体布局、GKI 访问或固件兼容不作结论；`INCOMPLETE` 亦非失败——缺的 104 条为 `cnss_*` / `cld80211_*`，内核 `Module.symvers` 中 `grep -c cnss_` 即为 0，兄弟模块之间的符号不会出现在该文件；判据是 ko↔ko 直比（待核）。
3. **装载后**：`dmesg` 不应出现 `disagrees about version`、`Unknown symbol`、`invalid module format`、`KCFI`、`BUG:`、`Oops` 中任一条；`lsmod` 四行俱在；`iw dev wonder0 info` 报 ch149 / 20 MHz。
4. **回滚**：`/vendor` 全程未动；`rmmod` 四个自建模块，或直接重启，即回原厂。

## `wonder.ko` 的 order-8 内存坑

`wonder.ko` 另有一个与 ABI 无关的约束：`ieee80211_txq_setup_flows()` 需要一次约 1 MB 的连续内存（order 8）。碎片化时 `insmod` 六次全部返回 `-12`，而 `MemAvailable` 尚有 6 GB（设备期记录，待核）。仓库根 `tools/awdl-stack.sh` 的 `defrag_memory()` 即为此而设，判据看 `/proc/buddyinfo` 的最大非零 order，而非可用内存。
