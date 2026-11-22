#!/usr/bin/env bash
# Cold-verify that what we publish reproduces the shipped kernel module.
#
#   1. export a pristine tree straight out of git HEAD (no working-tree edits,
#      no untracked files) into a scratch directory
#   2. run tools/kernel/apply-source.sh against it — the same script a third
#      party runs, not a reimplementation
#   3. sha256-compare every file the build tree changed against the real tree
#
# A clean run prints `same == total` and exits 0. Anything else is a gap in
# kernel-assets/source, not a flaw in this script.
#
# Note the two-repo shape: the root workspace repo covers vendor/ and
# kernel_platform/oplus/, while kernel_platform/msm-kernel/ is its own git
# repository. `git archive` cannot span both, so export them separately.
set -euo pipefail

ROOT=${OPAIRSHARE_BUILD_ROOT:-/mnt/opairshare-build}
TREE=$ROOT/opairshare
V=$ROOT/verify-clean
HERE=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
# HERE is repo-src/tools/kernel; the repo is two levels up.
REPO=$(cd "$HERE/../.." && pwd)
SRC=$REPO/kernel-assets/source
# The 114-file list ships in the repo so a third party can run this without our scratch
# directory. OPAIRSHARE_EXPECT_LIST overrides it for local experiments.
LIST=${OPAIRSHARE_EXPECT_LIST:-$SRC/expected-files.txt}

[ -d "$TREE/.repo" ] || { echo "no repo checkout at $TREE" >&2; exit 1; }
[ -f "$LIST" ]        || { echo "no expected-changes list at $LIST" >&2; exit 1; }

echo "== 1. export pristine HEAD -> $V"
rm -rf "$V"
mkdir -p "$V"

# Root repo: vendor/ + kernel_platform/oplus/ (kernel_platform/msm-kernel is separate).
git -C "$TREE" archive HEAD vendor kernel_platform/oplus | tar -x -C "$V"
# msm-kernel repo: only the two files our patches touch. It is a separate repo, so its
# archive has paths relative to its own root — extract into kernel_platform/msm-kernel/
# so 0002's `a/kernel_platform/msm-kernel/build_with_bazel.py` path resolves.
mkdir -p "$V/kernel_platform/msm-kernel"
git -C "$TREE/kernel_platform/msm-kernel" archive HEAD \
    build_with_bazel.py arch/arm64/configs/vendor/sun_perf.config \
    | tar -x -C "$V/kernel_platform/msm-kernel"

n=$(find "$V" -type f | wc -l)
echo "   $n files"

echo "== 2. apply overlay + patches with the published script"
# apply-source.sh keys off OPAIRSHARE_BUILD_ROOT/$TREE, so point it at the scratch
# tree by making a ROOT whose opairshare/ is the scratch tree.
SCRATCH=$ROOT/.verify-root
rm -rf "$SCRATCH"; mkdir -p "$SCRATCH"
ln -s "$V" "$SCRATCH/opairshare"
OPAIRSHARE_BUILD_ROOT=$SCRATCH bash "$REPO/tools/kernel/apply-source.sh"
rm -f "$SCRATCH/opairshare"; rmdir "$SCRATCH"

echo "== 3. compare against $TREE"
python3 - "$V" "$TREE" "$LIST" "$SRC/expected-absent.txt" <<'PY'
import hashlib, os, sys

recon, real, listing, absent_list = sys.argv[1:5]
files = [l.strip() for l in open(listing) if l.strip()]

# path -> sha256 of artefacts that are present in the build tree on purpose-to-be-ignored
absent = {}
if os.path.exists(absent_list):
    for line in open(absent_list):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        path, digest = line.split()[0], line.split()[1]
        absent[path] = digest

def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()

same, diff, missing, excused, bad_excuse = [], [], [], [], []
for f in files:
    a, b = os.path.join(recon, f), os.path.join(real, f)
    if not os.path.exists(a):
        # Only excused when the real tree's copy still matches the recorded hash: a
        # changed artefact means the situation moved and deserves a fresh look.
        if f in absent:
            if os.path.exists(b) and sha(b) == absent[f]:
                excused.append(f)
            else:
                bad_excuse.append(f)
        else:
            missing.append(f)
    elif sha(a) == sha(b):
        same.append(f)
    else:
        diff.append(f)

total = len(files)
print(f'total {total}  same {len(same)}  diff {len(diff)}  '
      f'missing {len(missing)}  expected-absent {len(excused)}')
for label, rows in (('DIFF', diff),
                    ('MISSING IN RECONSTRUCTION', missing),
                    ('EXPECTED-ABSENT BUT HASH CHANGED', bad_excuse)):
    if rows:
        print(f'\n=== {label} ===')
        for f in rows:
            print(' ', f)
if excused:
    print('\n=== expected absent (documented dead files, not reproduced) ===')
    for f in excused:
        print(' ', f)
sys.exit(0 if not diff and not missing and not bad_excuse else 1)
PY
