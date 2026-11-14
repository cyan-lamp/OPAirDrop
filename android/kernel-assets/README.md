# 内核产物（`assets/kernel/` 的来源）

这四个 `.ko` 不是我们编译的，是 `repo-v2` 那次 KSU 模块里的 payload；`wonder-ven` 是那里的
小工具。**它们与手机上跑通过的二进制逐字节相同**——这一点是校验过的，不是回忆：

| 文件 | 原始大小 | gz 后 | gz 的 SHA-256 |
| --- | --- | --- | --- |
| `mac80211.ko.gz` | 17,659,760 | 5,932,779 | `0be61885b517c7f9850c5da6c02b26439e2a665020ee83cddeaeb74ad699c599` |
| `qca_cld3_peach_v2.ko.gz` | 20,050,688 | 5,282,983 | `d773d67660b0fe74c5224f902a0d35043803c90ff489227ecd4d2fbf515ffd07` |
| `opairshare_wonder_provider.ko.gz` | 271,472 | 95,401 | `ed89b49bbc1dd6d397283c53c95593d7c404a23f3f0068f1dde42582492dec20` |
| `wonder.ko.gz` | 78,432 | 21,694 | `4998fb650484b75c49059beeee3f9b21aba184ee30124cc8a27bdd2a31ce2a41` |
| `wonder-ven`（未压缩） | 12,072 | — | `05addc366703d8885ac2ddfeec9a2cb576bee622edb215bdfb7f35b17f53d677` |

合计 38,060,352 字节原始、11,332,857 字节入仓（`+ wonder-ven` 12,072 = 11,344,929）。

## 为什么是 gz 而不是裸 `.ko`

裸文件合计 37 MB，进 git 就是三个每次克隆都要付的二进制。gz 之后 11 MB，代价是构建期要解一次压：
`android/app/build.gradle` 里的 `stageKernelAssets` 负责解到 `build/kernelAssets/kernel/`，
再把那个目录当 asset 根塞进 APK。APK 里存的是裸 `.ko`（aapt 自己会 deflate），所以
`AssetManager.open()` 拿到的就是原始字节，App 侧不需要任何解压逻辑。

## 来源与重建

原本在 `repo-v2/build/package/module/payload/kernel/`（KSU 模块 payload）和
`repo-v2/module/bin/wonder-ven`。重建：

```sh
tools/make-kernel-assets.sh /path/to/repo-v2
```

脚本只做两件事：gzip -9 四个 `.ko`、复制 `wonder-ven`，然后打印 SHA-256 供与本表对照。

## 一致性

2026-11-14 核对过三处哈希全等：设备上 `/data/local/tmp/opairdrop/*.ko`、`repo-v2` 的 payload、
以及 `wonder-ven` 的源码产物 `repo-v2/oshare/build/ven/wonder-ven`。**设备比仓库旧**这件事在这个
项目里真实发生过（模块比仓库旧，读源码解释现场数字时被误导），所以升级模块后要重新对一次。

`wonder-ven` 的源码在 `repo-v2/oshare/build/ven/wonder-ven.c`（13,802 字节），这个仓库不构建它，
只把产物原样带上——它的作用是给 `wonder0` 喂 vendor 的 reg/freq/rate/filter 四个 subcmd，
不参与数据面。

## 许可

这四个模块是第三方（高通/厂商）二进制，`repo-v2` 里对它们另有说明（`LICENSING.md`、
`THIRD_PARTY_NOTICES.md`）。自用不分发；要分发得先过那里的许可。
