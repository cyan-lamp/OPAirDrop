#!/bin/sh
# The POSIX form of a path -- for anything that has to be deleted.
#
# With TMPDIR set the Windows way, `mktemp -d` hands back C:\Users\...\Temp/tmp.x: one
# path with two kinds of separator in it. Nothing can unlink that. rm takes the
# backslashes literally and looks for a file whose name contains them, and the
# safe-delete wrapper refuses the path outright with SAFE_DELETE_INVALID_PATH -- a line
# printed after the script has already said "ok", which reads like a build failure and
# is really a scratch directory leaking on every run.
#
# cygpath is the only thing that knows the mapping. On Linux and macOS it does not exist
# and the path is already POSIX, so the fallback returns it unchanged.
#
# Kept in one file because this is the third time the same four lines were about to be
# copied into a script: the copies drift, and one of them ends up deleting the wrong
# thing.
unixpath() {
    if command -v cygpath >/dev/null 2>&1; then
        _mapped=$(cygpath -u "$1" 2>/dev/null) || _mapped=
        if [ -n "$_mapped" ]; then
            printf '%s' "$_mapped"
            return 0
        fi
    fi
    printf '%s' "$1"
}
