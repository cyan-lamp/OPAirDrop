#!/usr/bin/env bash
# Copies the kernel payload out of a `repo-v2` checkout and gzips it into this repo.
#
# Why a script and not `cp` in a comment: the four modules are 38 MB of bytes nobody here
# compiles, and the only thing that makes them safe to load is that they are the exact bytes
# that ran. Hand-copying is how a rebuild gets picked up half-way and the app loads a driver
# one build behind — which does not fail, it loads and behaves like the older code.
#
# The hash table it prints is the one in `kernel-assets/README.md`; if the two
# disagree, one of them is wrong and the diff says which file changed.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/.." && pwd)
out=$root/kernel-assets

source_root=${1:-}
if [ -z "$source_root" ]; then
    echo "usage: $0 <path to a repo-v2 checkout>" >&2
    exit 2
fi
payload=$source_root/build/package/module/payload/kernel
helper=$source_root/module/bin/wonder-ven

for file in mac80211 qca_cld3_peach_v2 opairshare_wonder_provider wonder; do
    if [ ! -f "$payload/$file.ko" ]; then
        echo "missing $payload/$file.ko" >&2
        exit 1
    fi
done
if [ ! -f "$helper" ]; then
    echo "missing $helper" >&2
    exit 1
fi

mkdir -p "$out"
for file in mac80211 qca_cld3_peach_v2 opairshare_wonder_provider wonder; do
    gzip -9 -c "$payload/$file.ko" >"$out/$file.ko.gz"
    printf '%s\t%s\t%s\n' "$file" \
        "$(wc -c <"$payload/$file.ko" | tr -d ' ')" \
        "$(wc -c <"$out/$file.ko.gz" | tr -d ' ')"
done
cp "$helper" "$out/wonder-ven"

echo
echo "SHA-256 of what went in (compare with kernel-assets/README.md):"
(cd "$out" && sha256sum ./*.gz wonder-ven)
