# 随包内核载荷

这五个文件不是我们编的，也不打算在每台机器上重编一遍：它们是被打过补丁的第三方驱动，
由 APK 带到设备上，用 `insmod` 顶掉原厂的同门模块。原厂模块留在 `/vendor/lib/modules`
不动，重启即回到原厂状态——这是这条路线唯一的回滚保证。

同一目录下的 `source/` 是这些字节的源码：上游的驱动覆盖层与补丁，`source-map.json`
给出每个覆盖文件在平台树里的位置。载荷与源码必须一起看，否则这些二进制只是一堆黑盒。

## 载荷

| 文件 | 原始字节 | gz | sha256（原始字节） | 是什么 |
| --- | --- | --- | --- | --- |
| `qca_cld3_peach_v2.ko.gz` | 20 050 688 | 5 282 983 | `152b93ad51c093be8ea54154121c4070573277f1711bb7d85deeb132b750c7a4` | qcacld-3.0，打开 passthru/AWDL 通路的那一颗 |
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

**`qca_cld3_peach_v2`**（19 736 456 B，`0555d6c4…` → 20 050 688 B）

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
| `source/driver/` | 44 个覆盖文件，`source-map.json` 逐条给出它们在平台树里的落点 |
| `source/wonder/` | wonder 驱动与它要的 mac80211 接口（22 个文件） |
| `source/provider/` | provider 与绑定探针（4 个文件） |
| `source/patches/` | 7 个编号补丁 + 1 个候选版 `0007-session-dp-candidate.patch`，都是对官方树做的**改动**，没有新增或删除文件——新增的头文件靠覆盖层拷进去 |
| `source/source-map.json` | 覆盖文件 → `kernel_path` 的映射，附每个文件的 sha256 |
| `source/tools/` | 上游那七个脚本：`sync-source.sh`、`build-baseline.sh`、`build-wlan-baseline.sh`、`build-wlan-feature-check.sh`、`build-route-b-provider.sh`、`module_abi.py`、`module_exports.py` |
| `source/README.md` | 上游原文，保留不动；它引用的 `../docs/*` 与 `ROOT-MOUNT-COMPATIBILITY.md` 不随本仓库，看这份就够 |

平台基线是 OnePlusOSS `oneplus/sm8750` 分支 + `oneplus_13_b.xml` 清单，三个 commit 由上游
`tools/kernel/sync-source.sh` 钉死；`build-wlan-baseline.sh` 单独编 qcacld，因为 WLAN 不在
`kernel_platform` 的 Kleaf workspace 里（借 `kernel_platform/vendor -> ../vendor` 软链引进来）。

## 重建到哪一步了

2026-11-17 在 WSL 里把这条路走过一遍，**只走到「未打补丁的基线」**：

- `build-baseline.sh` 全绿（1359/1359 actions，8523 s），4K 与 16K 两套 `Image` 都落盘。
- `build-wlan-baseline.sh` 出一条 `qca_cld3_peach_v2.ko`（dist 目标只含它，不是四条）。
  原始 511 908 416 B（几乎全是 debug_info）→ `llvm-strip --strip-debug` → 19 718 512 B。
- 与原厂比：`__versions` 588/588 逐条相同，`undefined` 587 集合相同，导出皆空，均无签名尾。
- 装到设备上：`rmmod` 原厂 → `insmod` 自建，零报错，Wi-Fi 起来，网关与外网各零丢包。

**没走到的一步**：`source/` 覆盖层 + `patches/` 全量应用之后重编。这条链上现在跑的这颗
（20 050 688 B、600 条 `__versions`、带 62 个 `passthru_*`）仍然是上游给的字节，我们没有从源码
复现过它。要做这件事，顺序是：覆盖层按 `source-map.json` 拷进平台树 → 补丁逐个 `git apply` →
`build-wlan-baseline.sh` → 用下面这张校验清单验。

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
