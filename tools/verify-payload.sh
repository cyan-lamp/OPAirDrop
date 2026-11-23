#!/usr/bin/env bash
# 评审 P1-22/P1-23：随包内核载荷与登记哈希的一致性，做成 CI 门禁。
# 哈希登记在 kernel/PAYLOAD.sha256（sha256sum 标准格式）；README 的表是给人读的
# 叙述，机器校验以这份为准——两者不一致时先改表再改文件，顺序相反就是漂移。
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cd "$here/../kernel"
exec sha256sum -c PAYLOAD.sha256
