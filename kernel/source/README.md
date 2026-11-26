# kernel/source：驱动覆盖层与补丁

本目录只存对上游内核的**改动**，不含上游代码；落盘依赖仓库根的 `tools/kernel/apply-source.sh`，载荷与完整构建流程见 [上级 README.md](../README.md)。

## 目录

| 路径 | 内容 |
| --- | --- |
| `driver/` | 46 个 WLAN 覆盖文件，`kernel_path` 逐条给出平台树落点与 sha256；`driver/legacy/` 另有 2 个不参与编译、不进 `source-map.json` 的物证文件 |
| `wonder/` | wonder 驱动与 mac80211 接口，23 个文件 |
| `provider/` | provider 与绑定探针，4 个文件；Bazel 目标 `//opairshare-route-b-provider:opairshare_wonder_provider`、`:opairshare_route_b_probe` |
| `patches/` | 11 个编号补丁（`0001`–`0011`）+ 废弃候选 `0007-session-dp-candidate.patch` |
| `expected-files.txt` / `expected-absent.txt` | 115 个改动文件清单 / 2 个故意不复现的死文件（附 sha256），供 `verify-reproduce.sh` 比对 |
| `tools/` | 上游 7 个构建脚本 |

## 上游基线

官方源码为 OnePlusOSS 的 `oneplus/sm8750` 分支与 `oneplus_13_b.xml` 清单，三个子项目的 commit 由 `tools/kernel/sync-source.sh` 钉死（列表见 [上级 README.md](../README.md)）。
构建卷由 `OPAIRSHARE_BUILD_ROOT` 指定为 ext4 目录，卷内源码目录为 `opairshare/`；须以普通用户构建，不可使用 root。

## 怎么用这份源码

```bash
bash tools/kernel/sync-source.sh              # 拉取官方基线（钉死三个 commit）
bash tools/kernel/apply-source.sh             # 先补丁、后覆盖层
bash tools/kernel/apply-source.sh --check     # 在临时副本上真跑一遍，真实树不动
```

顺序不可颠倒：补丁的上下文行是上游原文，覆盖层是最终全文；顺序相反时 `0005`/`0006`/`0007` 必然 `patch does not apply`。
`apply-source.sh` 另处理三件易踩的事：`kernel_platform/vendor` 是软链，从 `kernel_platform` 根打 vendor 补丁会**静默失效**（退出 0、文件未改）；`0001` 的 hunk 已包含在 `0003` 中（检测到 `0003` 即自动跳过 `0001`）；`0004` 的补丁根是 `kernel_platform/msm-kernel` 而非树根。
落盘后脚本还会 grep 四个关键标记，以防「退出 0 但未改」。

`0007-session-dp-candidate.patch` 为废弃候选，仅供 diff，脚本自动跳过。
`0010-qdf-nbuf-radiotap-len.patch` 不可跳过：生效版 `0007` 声明并调用 `qdf_nbuf_get_radiotap_len()`，函数体只存在于候选版，缺它则链接期报未定义符号。

## 复现判据

```bash
bash tools/kernel/verify-reproduce.sh
# total 115  same 113  diff 0  missing 0  expected-absent 2
```

脚本从 git HEAD 导出干净树 → 运行 `apply-source.sh` → 与实际构建树逐字节比较；`expected-absent` 的 2 个即 `driver/legacy/` 中归档的死文件。
迁移到其他 SoC 或驱动版本时，先在 `source-map.json` 中修改 `kernel_path`，再在 WSL / 构建卷内运行下面命令；判据是构建树中的 `*.bzl` 清单，而非目录名，且修改 `kernel_path` 只影响下次 `apply-source.sh`，不会自动生效：

```bash
python3 kernel/check-map.py
# .c listed: 22   .c dead: 0
```
