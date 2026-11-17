#!/usr/bin/env python3
"""Generate ``android/app/src/main/res/raw/licenses.txt`` from the real dependency graph.

Run from anywhere::

    python android/tools/licenses.py

Why a generator and not a hand-written file: a license list decays. Somebody adds a crate,
the text stays as it was, and the app then ships a legal notice that is *wrong* rather than
merely incomplete — which is worse than shipping none, because it asserts something false
about what is in the APK. The list below is derived from `cargo metadata` for the exact
target the APK is built for, so it cannot drift.

What is collected:

  * every non-workspace crate reachable from a workspace member for
    ``aarch64-linux-android`` (the only ABI in `abiFilters`),
  * the license text files that actually sit next to each crate's `Cargo.toml`,
  * the copyright line(s) at the top of MIT / 0BSD / Zlib texts, kept per crate.

Texts are deduplicated: when twelve crates all ship the same Apache-2.0 body, the body is
printed once and the crates that rely on it are listed above it. The copyright lines are
*not* deduplicated away — that is the part of MIT that names someone, so each crate keeps
its own.

The Java side has no third-party dependencies (`android/app/build.gradle` declares no
`dependencies` block), so there is nothing to add from Gradle. If that ever changes, this
script is where the gap becomes visible, because it prints that fact.
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import textwrap
import unicodedata

TARGET = "aarch64-linux-android"

# How wide the generated page is, in display cells (a CJK glyph counts 2).
#
# This number is not cosmetic. The licence page shows this file in a monospace TextView
# inside a card, and 60 cells is what fits there at 10sp without horizontal scrolling:
# roughly 360dp of text in the ~384dp the card leaves. Anything wider and the reader has to
# drag sideways to finish a line — and worse, one 243-cell paragraph (LICENSE-ZLIB.md really
# is written that way) makes the scrollable content four screens wide, so a sideways drag
# lands on columns where most lines have nothing left and the block goes blank.
#
# Hence: the scaffolding below (frame rules, the crate table, these notes) stays inside the
# budget and is enforced by add(); the licence *bodies* are printed verbatim and are allowed
# to be wider, because they are prose and the TextView wraps them.
WIDTH = 60

RULE = "=" * WIDTH
THIN = "-" * WIDTH

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "android", "app", "src", "main", "res", "raw", "licenses.txt")

LICENSE_FILE = re.compile(r"^(licen[cs]e|copying|unlicen[cs]e)", re.IGNORECASE)

# A license is recognised by what its text says, not by what its file is called: crates name
# these files LICENSE, LICENSE.md, LICENSE-MIT, COPYING and LICENCE interchangeably, and the
# same crate sometimes ships the Apache body under two names.
SIGNS = [
    ("Apache-2.0", "Apache License", "Version 2.0,"),
    ("MIT", "Permission is hereby granted, free of charge", None),
    ("0BSD", "Permission to use, copy, modify, and/or distribute this software", None),
    ("Zlib", "This software is provided 'as-is'", None),
    ("Unicode-3.0", "UNICODE LICENSE V3", None),
]

# 大小写敏感，而且要顶格（允许缩进）以 Copyright 开头。
#
# 这行正则看着朴素，它专门排掉 Apache-2.0 正文里的 "the copyright owner" 和 "copyright
# notice that is included in or attached to the work" —— 那些是条款里的普通名词，不是谁的
# 版权声明。原来带 re.IGNORECASE，于是这些行被当成版权行抓出来，页面上就出现
#   libc 0.2.191
#       copyright notice that is included in or attached to the work
# 这种谁也读不懂的条目（重新生成时真的出现了）。
#
# `(c)` 这种写法后面必须是年份。条款 4(c) 的正文是 "(c) You must retain, in the Source
# form of any Derivative Works…"，只写 `\(c\)` 会把这一整行当成版权行提走，正文里就少了
# 半句条款——那已经不是排版问题，是篡改。
COPYRIGHT = re.compile(r"^\s*(?:Copyright|COPYRIGHT|©|\([cC]\)\s*\d{4})")

# The title line some copies carry and others omit; see canonical().
TITLE = re.compile(
    r"^(?:(?:the\s+)?(?:mit|zlib|bsd|apache)\s+licen[cs]e"
    r"(?:\s+version\s+2\.0)?(?:\s*,?\s*january\s+2004)?\s*"
    r"|mit\s+)",
    re.IGNORECASE,
)


def metadata():
    # Full metadata, not `--no-deps`: only the former carries `resolve`, and the resolve graph
    # is the whole point — it is what says which crates actually reach the target.
    command = ["cargo", "metadata", "--format-version", "1", "--filter-platform", TARGET]
    try:
        output = subprocess.run(
            command, cwd=ROOT, check=True, capture_output=True, text=True
        ).stdout
    except FileNotFoundError:
        sys.exit("cargo is not on PATH; run this where the toolchain that builds the app is")
    except subprocess.CalledProcessError as failed:
        sys.exit("cargo metadata failed:\n" + failed.stderr)
    return json.loads(output)


def reachable(graph):
    """Ids of every package the workspace pulls in, walking the resolved edges."""
    edges = {node["id"]: node["deps"] for node in graph["resolve"]["nodes"]}
    members = set(graph["workspace_members"])
    seen = set()
    pending = list(members)
    while pending:
        current = pending.pop()
        if current in seen:
            continue
        seen.add(current)
        for dep in edges.get(current, []):
            pending.append(dep["pkg"])
    return seen - members


def license_texts(package):
    """(license key, body, copyright lines) for each license file beside the manifest."""
    directory = os.path.dirname(package["manifest_path"])
    found = []
    try:
        names = sorted(os.listdir(directory))
    except OSError:
        return found
    for name in names:
        if not LICENSE_FILE.match(name) or name.endswith(".orig"):
            continue
        path = os.path.join(directory, name)
        if not os.path.isfile(path) or os.path.getsize(path) > 200_000:
            continue
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except OSError:
            continue
        key = None
        # The filename wins for Unicode: its notice also says "Permission is hereby granted,
        # free of charge", so content sniffing alone files it under MIT — which would quote a
        # Unicode license under an MIT heading.
        if "UNICODE" in name.upper():
            key = "Unicode-3.0"
        if key is None:
            for candidate, first, second in SIGNS:
                if first in text and (second is None or second in text):
                    key = candidate
                    break
        if key is None:
            # An unrecognised body is reported rather than dropped: silently omitting a
            # license file is the one failure mode that would make this file a lie.
            key = "unrecognised:" + name
        lines = [
            line.strip()
            for line in text.splitlines()
            if COPYRIGHT.match(line) and len(line.strip()) < 200
        ]
        found.append((key, text, lines))
    return found


def canonical(text):
    """The body reduced to what the license actually *says*.

    Five things are thrown away, each because it differs between copies of the same license
    while being legally inert:

      * the copyright lines — those are per-crate and are kept separately, in full;
      * the title ("MIT License", "Apache License Version 2.0, January 2004") — one crate
        prepends it, the next does not;
      * line wrapping, which ranges from 60 to 120 columns across these crates;
      * the "APPENDIX: How to apply…" tail of Apache-2.0, which is advice to licensors;
      * how the license's own URL is spelled (http/https, with or without
        /LICENSE-2.0), which varies but always names the same page.

    Nothing else is normalised. Two texts that still differ after this genuinely differ, and
    the generator prints both rather than pretending they are one.
    """
    kept = [line for line in text.splitlines() if not COPYRIGHT.match(line)]
    body = re.sub(r"\s+", " ", " ".join(kept)).strip()
    body = TITLE.sub("", body, count=1).strip()
    # "APPENDIX: How to apply the Apache License to your work" is instruction to whoever
    # licenses *their* code, not a term of the license, and copies of Apache-2.0 disagree on
    # whether to carry it. Left in, that one paragraph split the four copies in this tree into
    # four "different" licenses and printed the 11 KB body four times.
    body = re.split(r"\bAPPENDIX:", body)[0].strip()
    # Copies also disagree on how the license's own URL is spelled: http vs https, and
    # ".../licenses/" vs ".../licenses/LICENSE-2.0". All of them point at the same page.
    return re.sub(
        r"https?://www\.apache\.org/licenses/(?:LICENSE-2\.0)?",
        "https://www.apache.org/licenses/LICENSE-2.0",
        body,
    )


def remote_url():
    """``origin`` 的地址，取不到就返回空串。

    这一段以前是写死的中文（「本地工作副本，尚未建立远端」）。写死的状态一定会过期，而这份
    清单唯一的价值就是「说的一定是现在的事实」——所以状态类的东西一律现场查。
    """
    try:
        result = subprocess.run(
            ["git", "-C", ROOT, "remote", "get-url", "origin"],
            capture_output=True, text=True,
        )
        return result.stdout.strip() if result.returncode == 0 else ""
    except OSError:
        return ""


def own_license_lines():
    """本仓库自己声明的许可，从仓库根读出来，返回要写进清单的那几行。

    同样是不写死：以前这几行断言「仓库根目录没有 LICENSE 文件」，后来真加了 GPL-2.0，
    那段话就从「不完整」升级成了「错误」——而错误的法务声明比缺失更糟。
    """
    for name in ("LICENSE", "LICENSE.md", "COPYING", "LICENCE"):
        path = os.path.join(ROOT, name)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as handle:
            head = handle.read(2000)
        if "GNU GENERAL PUBLIC LICENSE" in head and "Version 2" in head:
            spdx = "GPL-2.0"
        elif "Apache License" in head and "Version 2.0" in head:
            spdx = "Apache-2.0"
        elif "Permission is hereby granted, free of charge" in head:
            spdx = "MIT"
        else:
            spdx = "见文件头"
        return [
            "仓库根的 %s，%s。" % (name, spdx),
            "随 APK 分发，全文见「关于 → 开源许可」。",
        ]
    return [
        "仓库根没有 LICENSE 文件，因此本项目尚未声明许可，",
        "按著作权法默认保留所有权利。",
    ]


def main():
    graph = metadata()
    packages = {package["id"]: package for package in graph["packages"]}
    wanted = reachable(graph)

    # crate -> (version, declared license, [license keys])
    crates = []
    bodies = {}          # (key, hash) -> text
    attribution = {}     # (key, hash) -> [(crate, version, [copyright lines])]
    declared_only = []   # crates whose license files are absent

    for identifier in sorted(wanted, key=lambda i: (packages[i]["name"], packages[i]["version"])):
        package = packages[identifier]
        name, version = package["name"], package["version"]
        declared = package.get("license") or "(未声明)"
        texts = license_texts(package)
        if not texts:
            declared_only.append((name, version, declared, package.get("repository") or ""))
            crates.append((name, version, declared, []))
            continue
        keys = []
        seen = set()
        for key, text, lines in texts:
            body = canonical(text)
            # A content digest, not `hash()`: the output has to be byte-identical between runs
            # or every rebuild looks like a change to the licenses file.
            stamp = (key, hashlib.sha1(body.encode("utf-8")).hexdigest()[:12])
            if stamp in seen:
                # miniz_oxide ships both LICENSE and LICENSE-MIT.md with the same text; one
                # crate must not appear twice under one license.
                continue
            seen.add(stamp)
            if len(text) > len(bodies.get(stamp, "")):
                # The fullest copy is printed, which is usually the one that kept its title.
                bodies[stamp] = text.strip()
            attribution.setdefault(stamp, []).append((name, version, lines))
            keys.append(key)
        crates.append((name, version, declared, keys))

    lines = []

    remote = remote_url()
    own = own_license_lines()

    def cells(text):
        """How many columns this line takes, counting a CJK glyph as two."""
        return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)

    def pad(text, width):
        """Pad to `width` columns, not to `width` characters.

        `%-15s` pads by characters, so 名称 (two characters, four columns) would come out two
        columns short of every ASCII name below it and the table would lean.
        """
        return text + " " * max(0, width - cells(text))

    def add(line):
        if cells(line) > WIDTH:
            raise SystemExit(
                "line is %d columns, budget is %d: %r\n"
                "The licence page shows this at 10sp monospace in a card that fits %d columns;"
                " a wider line wraps or scrolls, so re-wrap this one." %
                (cells(line), WIDTH, line[:80], WIDTH))
        lines.append(line)

    def add_raw(text):
        """A verbatim licence body, re-flowed to the same budget.

        Not one character is added, dropped or changed here — only line breaks move, and they
        move at spaces. The original files wrap anywhere between 60 and 120 columns and one
        paragraph in LICENSE-ZLIB.md is a single 243-character line; left alone, the page
        would be four screens wide and the reader would be dragging sideways to finish a
        sentence. Breaking at spaces to the same 60 columns keeps the copy verbatim in the
        only sense that matters (the words) while making it readable in one screen width.
        """
        for line in text.splitlines():
            if not line.strip():
                lines.append("")
                continue
            indent = line[: len(line) - len(line.lstrip(" "))]
            wrapped = textwrap.wrap(
                line,
                width=WIDTH,
                initial_indent=indent,
                subsequent_indent=indent,
                break_long_words=False,
                break_on_hyphens=False,
                drop_whitespace=True,
            )
            lines.extend(wrapped or [""])

    add("OPAirDrop — 第三方许可 / Third-party licenses")
    add("")
    add("本文件由 android/tools/licenses.py 生成，请勿手改。")
    add("生成命令：python android/tools/licenses.py")
    add("生成对象：%s（APK 只装这一个 ABI）" % TARGET)
    add("")
    add(RULE)
    add("一、本应用自身")
    add(RULE)
    add("")
    add("名称：OPAirDrop")
    add("实现：Rust 核心（crates/）+ Java 外壳（android/），")
    add("      双方经 JNI 相连")
    add("源码：本仓库 repo-OPAirDrop")
    if remote:
        # 远端是从 git 里读的，不写死。这句话以前写的是「尚未建立远端」，等远端真的建起
        # 来，整份清单就开始对用户说假话——而它的全部意义就是「说的一定是现在的事实」。
        add("      " + remote)
    add("许可：%s" % own[0])
    for extra in own[1:]:
        add("      " + extra)
    add("")
    add("本应用不包含 Google QuickShare / Nearby 的任何源码或二进制，")
    add("不包含任何 Google Play 服务组件，Java 侧除 Android 框架外")
    add("没有第三方依赖（android/app/build.gradle 里没有")
    add("dependencies 块）。下面列出的全部是 Rust 依赖。")
    add("")
    add(RULE)
    add("二、随包分发的 Rust crate（%d 个）" % len(crates))
    add(RULE)
    add("")
    add(pad("名称", 15) + pad("版本", 9) + "许可")
    add(THIN)
    for name, version, declared, _ in crates:
        add(pad(name, 15) + pad(version, 9) + declared)
    if declared_only:
        add("")
        add("以下 crate 的源码包里没有许可文件，")
        add("只能引述它在 Cargo.toml 里的声明：")
        for name, version, declared, repository in declared_only:
            add("  %s %s — %s" % (name, version, declared))
            if repository:
                add("      " + repository)

    add("")
    add(RULE)
    add("三、许可原文")
    add(RULE)
    add("")
    add("同一份原文只印一次，上面列出的是依赖它的 crate；")
    add("MIT / 0BSD / Zlib 的著作权声明逐条保留，因为那是这些许可")
    add("唯一点名到人的部分。")
    add("")
    add("排印上的差异不算不同版本，比对时已忽略：")
    add("  · 标题行有无（有的副本多一行「MIT License」）；")
    add("  · 行宽（60 列与 120 列混排）；")
    add("  · Apache-2.0 副本之间 http/https 的写法；")
    add("  · 末尾「APPENDIX: How to apply…」附录的有无——")
    add("    那段是写给授权人的填写说明，不是条款。")
    add("原文本身一字未改：下面每一段都是某一份真实文件的逐字副本。")
    add("行宽照原文件，比这一页宽的会自动接着下一行印，字没有增删。")

    order = ["MIT", "Apache-2.0", "0BSD", "Zlib", "Unicode-3.0"]
    by_key = {}
    for stamp in bodies:
        by_key.setdefault(stamp[0], []).append(stamp)
    for key in sorted(by_key, key=lambda k: (order.index(k) if k in order else 99, k)):
        variants = by_key[key]
        for number, stamp in enumerate(variants, 1):
            add("")
            add(THIN)
            if len(variants) == 1:
                add("许可：%s" % key)
            else:
                # Same license, different wording (a clause added, a paragraph dropped).
                # Merging those would misquote one of the two, so each is printed.
                add("许可：%s（第 %d 份，共 %d 份，措辞不同故分别印出）"
                    % (key, number, len(variants)))
            add(THIN)
            for name, version, copyrights in sorted(attribution[stamp]):
                add("  %s %s" % (name, version))
                if not copyrights:
                    add("      原文没有著作权行（署名写在 Cargo.toml 或 README 里）")
                for line in copyrights:
                    # A copyright line is somebody's name, not prose: it is printed whole
                    # even when it runs past the budget. Wrapping it costs nothing anyway,
                    # because it has no columns to line up with.
                    add_raw("      " + line)
            add("")
            add_raw(bodies[stamp])

    add("")
    add(RULE)
    add("四、Android 框架")
    add(RULE)
    add("")
    add("本应用没有 jar 依赖，只用 Android 框架（android.*）")
    add("与 Java 标准库。")
    add("Android SDK 的使用受 Android SDK License Agreement")
    add("约束；该协议约束的是编译与分发行为本身，不对应某个")
    add("随包文件，所以它的文本不在本文件里。")

    text = "\n".join(lines) + "\n"
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print("wrote %s (%d crates, %d bytes)" % (OUT, len(crates), len(text.encode("utf-8"))))


if __name__ == "__main__":
    main()
