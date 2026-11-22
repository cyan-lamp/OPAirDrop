#!/usr/bin/env bash
# Apply the OPAirShare kernel overlay + platform patches onto a pristine OnePlusOSS tree.
#
# Why this script exists: the overlay alone is not a build. `source-map.json` says where each
# overlay file lands, `wonder/` and `provider/` land at fixed paths, and the numbered patches
# carry the edits we make to files that upstream already ships. Nothing in the tree applies
# these for you, and the order matters — see the "order" note below.
#
# Usage:
#   tools/kernel/apply-source.sh [--check] [--only N] [--overlay-only]
#
#   default        apply every patch, then lay the overlay on top
#   --check        rehearse the whole sequence on a throwaway pristine copy and report;
#                  never writes to the real tree. Use this to validate a fresh checkout.
#   --only N       apply a single patch by number (e.g. --only 0007) for bisecting a build
#   --overlay-only skip patching, copy the overlay only (for a tree that is already patched)
#
# Environment (same names the other scripts use):
#   OPAIRSHARE_BUILD_ROOT  ext4 build volume; the source tree is $ROOT/opairshare
set -euo pipefail

ROOT=${OPAIRSHARE_BUILD_ROOT:-/mnt/opairshare-build}
TREE=$ROOT/opairshare
HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SRC=$(cd "$HERE/../../kernel/source" && pwd)

CHECK_ONLY=0
OVERLAY_ONLY=0
ONLY=""
while [ $# -gt 0 ]; do
    case $1 in
        --check)        CHECK_ONLY=1; shift ;;
        --overlay-only) OVERLAY_ONLY=1; shift ;;
        --only)         ONLY=$2; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

[ -d "$TREE" ] || { echo "no source tree at $TREE — run sync-source.sh first" >&2; exit 1; }
[ -d "$TREE/kernel_platform" ] || { echo "$TREE/kernel_platform is missing" >&2; exit 1; }

# --check: rehearse on a throwaway copy instead of dry-running each patch.
#
# `git apply --check` is useless here because the patches are not independent: 0006
# CREATES wlan_hdd_wondertap.c/.h and 0007 then EDITS them. A dry run never materialises
# 0006's files, so 0007 reports "No such file or directory" on a tree where the real
# sequence applies fine — exactly the false alarm seen on 2026-11-21. Copy the tree,
# run for real, throw the copy away.
if [ "$CHECK_ONLY" = 1 ]; then
    REHEARSE=$ROOT/.apply-check
    echo "check mode: rehearsing on a copy at $REHEARSE"
    rm -rf "$REHEARSE"
    mkdir -p "$REHEARSE"
    git -C "$TREE" archive HEAD vendor kernel_platform/oplus 2>/dev/null | tar -x -C "$REHEARSE"
    mkdir -p "$REHEARSE/kernel_platform/msm-kernel"
    git -C "$TREE/kernel_platform/msm-kernel" archive HEAD \
        build_with_bazel.py arch/arm64/configs/vendor/sun_perf.config 2>/dev/null \
        | tar -x -C "$REHEARSE/kernel_platform/msm-kernel"
    # apply-source.sh reads $ROOT/opairshare, so hand it a root whose opairshare is the copy.
    WREAL=$ROOT
    ROOT=$ROOT/.apply-check-root
    mkdir -p "$ROOT"
    ln -sfn "$REHEARSE" "$ROOT/opairshare"
    TREE=$ROOT/opairshare
    trap 'rm -rf "$REHEARSE" "$ROOT"' EXIT
    echo "check mode: pristine copy has $(find "$REHEARSE" -type f | wc -l) files"
fi

# 0. Puzzle out the real vendor root.
#
# `kernel_platform/vendor` is a SYMLINK to `../vendor`. Patching a vendor path from inside
# `kernel_platform` silently does nothing: `git apply --check` exits 0 and the file is not
# touched. That cost a whole build cycle on 2026-11-20. Always patch vendor paths from $TREE.
if [ -L "$TREE/kernel_platform/vendor" ]; then
    echo "vendor root: $TREE (follows symlink kernel_platform/vendor -> ../vendor)"
else
    echo "warning: kernel_platform/vendor is not a symlink; patching from $TREE anyway" >&2
fi

# 1. Patches first, overlay second. This ordering is load-bearing.
#
# The patches are diffs against UPSTREAM text: their context lines are the pristine file.
# The overlay carries FINAL file contents. Laying the overlay down first means every patch
# that touches an overlaid file fails with "patch does not apply" or "already exists in
# working directory" — 0005, 0006 and 0007 all hit that in cold verification on 2026-11-21.
# Patch the pristine text, then let the overlay overwrite whatever it owns.
#
# Roots differ, and this is not cosmetic:
#
#    0001 0003 0005 0006 0007 0008 0009 0010   from $TREE
#    0004                                     from $TREE/kernel_platform/msm-kernel
#    0002                                     from $TREE (touches kernel_platform/msm-kernel/...)
#
# 0001 and 0003 both edit oplus_local_modules.bzl and 0003 already contains 0001's hunks —
# applying both in order fails. 0003 supersedes 0001; skip 0001 when 0003 is present.
#
# 0007 has two variants on disk. `0007-session-dp-and-hardware-guards.patch` is the one the
# shipped module was built from (it adds the radiotap return-value guard and the exported
# opairshare_passthru_tx_count). `0007-session-dp-candidate.patch` is kept only for diffing
# against it — do not apply it.
#
# 0010 is not optional. The guards version of 0007 *declares* qdf_nbuf_get_radiotap_len()
# in qdf_nbuf.h and calls it three times from dp_tx.c, but the body of that function lived
# only in the abandoned candidate patch. Skip 0010 and qca_cld3 links with an undefined
# symbol. It is a separate patch rather than an 0007 edit so the shipped binary's 0007
# stays byte-identical.
apply_one() {
    local patch=$1 root=$2
    shift 2
    ( cd "$root" && git apply "$@" "$patch" )
}

PATCH_DIR=$SRC/patches
APPLIED=0
FAILED=0

if [ "$OVERLAY_ONLY" = 0 ]; then
    for patch in "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch; do
        base=$(basename "$patch")
        num=${base%%-*}

        case $base in
            0007-session-dp-candidate.patch)
                echo "skip  $base (superseded by 0007-session-dp-and-hardware-guards.patch)"
                continue ;;
            0001-wonder-sun-bazel.patch)
                if [ -e "$PATCH_DIR/0003-stage2a-qdf-enums-policy-buildchain.patch" ]; then
                    echo "skip  $base (its hunks are contained in 0003)"
                    continue
                fi ;;
        esac

        if [ -n "$ONLY" ] && [ "$num" != "$ONLY" ]; then
            continue
        fi

        case $base in
            0004-*) root=$TREE/kernel_platform/msm-kernel ;;
            *)      root=$TREE ;;
        esac

        if apply_one "$patch" "$root"; then
            echo "ok    $base  (from ${root#$TREE/})"
            APPLIED=$((APPLIED + 1))
        else
            echo "FAIL  $base  (from ${root#$TREE/})" >&2
            FAILED=$((FAILED + 1))
        fi
    done
else
    echo "overlay-only: patch step skipped"
fi

# 2. Overlay: driver/ via source-map.json, wonder/ and provider/ at fixed paths.
python3 - "$SRC" "$TREE" <<'PY'
import json, os, shutil, sys

src, tree = sys.argv[1], sys.argv[2]
mapping = json.load(open(os.path.join(src, 'source-map.json')))

copied = skipped = made = 0
for key, entry in mapping.items():
    kernel_path = entry.get('kernel_path')
    if not kernel_path:
        # agg-core.h / collect-core.h: host-test helpers, already inlined into bridge headers.
        skipped += 1
        continue
    # key is `kernel/driver/x.c`; stripping `kernel/` yields `driver/x.c`, which is
    # already relative to source/. Joining onto source/driver/ instead would look for
    # source/driver/driver/x.c.
    rel = key[len('kernel/'):] if key.startswith('kernel/') else key
    origin = os.path.join(src, rel)
    target = os.path.join(tree, kernel_path)
    if not os.path.exists(origin):
        raise SystemExit(f'overlay file missing: {origin}')
    # Some overlay files are NEW — upstream ships no such header, so the directory
    # itself does not exist in a pristine checkout (e.g. qca-wifi-host-cmn/target_if/p2p/src,
    # qcacld-3.0/core/hdd/inc/wonder). Create it rather than aborting.
    parent = os.path.dirname(target)
    if not os.path.isdir(parent):
        if not os.path.isdir(os.path.dirname(parent)):
            raise SystemExit(f'target directory missing (wrong tree?): {parent}')
        os.makedirs(parent)
        made += 1
    shutil.copyfile(origin, target)
    copied += 1

for name, dest in (('wonder', 'vendor/oplus/kernel/wifi/wonder'),
                   ('provider', 'kernel_platform/opairshare-route-b-provider')):
    origin = os.path.join(src, name)
    target = os.path.join(tree, dest)
    os.makedirs(target, exist_ok=True)
    for entry in sorted(os.listdir(origin)):
        path = os.path.join(origin, entry)
        if os.path.isfile(path):
            shutil.copyfile(path, os.path.join(target, entry))
            copied += 1

print(f'overlay: {copied} files copied ({made} new directories), {skipped} skipped')
PY

# 3. A vendor path patched from the wrong root is the silent killer: exit 0, no change.
#    Prove the load-bearing edits actually landed. Runs in check mode too, against the
#    rehearsal copy — that is the whole point of rehearsing.
verify_marker() {
    local file=$1 pattern=$2 what=$3
    if grep -q "$pattern" "$TREE/$file" 2>/dev/null; then
        echo "mark  $what"
    else
        echo "MISS  $what  ($file has no '$pattern')" >&2
        FAILED=$((FAILED + 1))
    fi
}

verify_marker "vendor/qcom/opensource/wlan/qcacld-3.0/core/wma/src/wma_data.c" \
    'wma_opairshare_pas_mgmt.h' 'wma_data.c includes pas_mgmt'
verify_marker "vendor/qcom/opensource/wlan/qcacld-3.0/components/target_if/p2p/src/target_if_p2p.c" \
    'OPAIRSHAREEVT' 'target_if_p2p.c carries the rx-filter reply hook'
verify_marker "kernel_platform/oplus/bazel/oplus_modules_variant.bzl" \
    '"sun"\]' 'oplus variant list is sun-only'
verify_marker "vendor/qcom/opensource/wlan/qca-wifi-host-cmn/qdf/linux/src/qdf_nbuf.c" \
    'qdf_nbuf_get_radiotap_len(qdf_nbuf_t nbuf)$' 'qdf_nbuf.c defines the radiotap length helper'

echo
echo "applied=$APPLIED failed=$FAILED"
[ "$FAILED" = 0 ] || exit 1
[ "$CHECK_ONLY" = 1 ] && echo 'CHECK_OK; the real tree was not touched.'
[ "$CHECK_ONLY" = 1 ] || echo 'SOURCE_APPLY_COMPLETE; run build-wlan-baseline.sh next.'
