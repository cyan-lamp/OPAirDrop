# 内核适配

本目录包含 OPAirShare 的 Qualcomm WLAN 源码覆盖层、Wonder 无线接口、独立绑定 provider 和平台基础补丁。构建依托官方 Linux 平台源码树，目标为 `sun perf` / `peach-v2`。

## 目录

| 路径 | 内容 |
| --- | --- |
| `driver/` | 当前 WLAN、WMA、HDD、DP 与共存策略源码 |
| `source-map.json` | 覆盖层在平台源码树中的文件位置 |
| `wonder/` | Wonder 与 mac80211 接口实现 |
| `provider/` | component 绑定桥与绑定探针 |
| `patches/` | 平台构建、枚举、生命周期和数据路径的基础集成补丁 |

当前覆盖层合并了 TX、native RX、共存与会话修复。同名文件以当前覆盖层实现为准，基础补丁按修改区域集成。

`agg-core.h`、`collect-core.h` 是已内联进 bridge 头文件的主机测试助手。其余覆盖层文件的位置由 `source-map.json` 的 `kernel_path` 指定。

## 上游基线

官方源码使用 OnePlusOSS 的 `oneplus/sm8750` 分支与 `oneplus_13_b.xml` 清单。common、msm-kernel 和 modules/devicetree 的版本由 `tools/kernel/sync-source.sh` 固定。

构建工具位于 `tools/kernel/`。`OPAIRSHARE_BUILD_ROOT` 指定 ext4 构建卷，卷内源码目录为 `opairshare/`；`OPAIRSHARE_TOOLS_DIR` 指定构建工具目录。

provider 放入平台树的 `kernel_platform/opairshare-route-b-provider/`，Bazel target 为：

```text
//opairshare-route-b-provider:opairshare_wonder_provider
//opairshare-route-b-provider:opairshare_route_b_probe
```

## 怎么用这份源码

本目录只有「改动」，没有「应用」。落盘靠仓库根的 `tools/kernel/apply-source.sh`：

```bash
# 1. 拉官方基线（钉死三个 commit）
bash tools/kernel/sync-source.sh

# 2. 打补丁 + 铺覆盖层。顺序不能反：补丁的上下文行是上游原文，
#    覆盖层是最终全文；反过来打，0005/0006/0007 必然失败。
bash tools/kernel/apply-source.sh

# 3. 只验不写：在临时副本上把整条序列真跑一遍，真实树不动。
#    不要用逐个 git apply --check —— 补丁之间不独立（0006 创建
#    wlan_hdd_wondertap.c/.h，0007 再改它），dry-run 永远看不到前序产物。
bash tools/kernel/apply-source.sh --check
```

`apply-source.sh` 处理了三件容易踩的事：`kernel_platform/vendor` 是软链（从
`kernel_platform` 根打 vendor 路径补丁会**静默失效**，退 0 但文件没改）；`0001` 的 hunk 已
包含在 `0003` 里（检测到 `0003` 就自动跳过 `0001`）；`0004` 的补丁根是
`kernel_platform/msm-kernel` 而不是树根。落盘后它还会 grep 四个关键标记，防「退出 0 但没改」。

`0007-session-dp-candidate.patch` 是废弃候选，只用于 diff，脚本会自动跳过。
`0010-qdf-nbuf-radiotap-len.patch` 不能跳过：`0007` 声明并调用了
`qdf_nbuf_get_radiotap_len()`，函数体只在 candidate 里，缺它链接期报未定义符号。

### 想确认「能不能复现出一模一样的驱动」

```bash
bash tools/kernel/verify-reproduce.sh
# total 114  same 112  diff 0  missing 0  expected-absent 2
```

它从 git HEAD 导出干净树 → 跑上面的 `apply-source.sh` → 与实际构建树逐字节比。
`expected-absent` 那 2 个是 `driver/legacy/` 里归档的死文件，见 `expected-absent.txt`。

完整流程见仓库根 [README.md](../../README.md)，本目录所属载荷的说明见
[上级 README.md](../README.md)。迁移到其他 SoC 或 WLAN 驱动版本时，
先在 `source-map.json` 里把 `kernel_path` 换到新树的位置，再跑
`python3 kernel-assets/check-map.py` 确认每个覆盖层 `.c` 都被 `*.bzl` 登记
（期望 `.c listed: 22   .c dead: 0`）——判据是 bzl 清单，不是目录名像不像。

注意 `check-map.py` 读的是**构建树里的 bzl**，所以要在 WSL/构建卷里跑，不能只拷仓库。
改了 `kernel_path` 只影响下次 `apply-source.sh`，不会自己生效。
