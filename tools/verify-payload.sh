#!/usr/bin/env bash
# 评审 P1-22/P1-23：随包内核载荷与登记哈希的一致性，做成 CI 门禁。
# 哈希登记在 kernel/PAYLOAD.sha256（sha256sum 标准格式）；README 的表是给人读的
# 叙述，机器校验以这份为准——两者不一致时先改表再改文件，顺序相反就是漂移。
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cd "$here/../kernel"
sha256sum -c PAYLOAD.sha256

# 评审 4.2：kernel/source/tools/ 与 tools/kernel/ 的七份脚本逐字节相同——
# 权威份是 tools/kernel/，这里只校验不删（内核构建流程还引用着这份拷贝）。
for f in build-baseline.sh build-route-b-provider.sh build-wlan-baseline.sh build-wlan-feature-check.sh module_abi.py module_exports.py sync-source.sh; do
    if ! diff -q "source/tools/$f" "../tools/kernel/$f" > /dev/null 2>&1; then
        echo "MISMATCH: kernel/source/tools/$f 与 tools/kernel/$f 不一致" >&2
        exit 1
    fi
done
echo "kernel tools: 7 files consistent"
