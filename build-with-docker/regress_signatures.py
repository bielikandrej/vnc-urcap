#!/usr/bin/env python3
"""
STIMBA VNC URCap — public-API regression smoke test
====================================================

Why this exists
---------------
Between 2026-04-18 and 2026-04-19 we shipped FIVE hotfixes (3.0.0-hotfix1
through 3.0.4) for the same root cause: our hand-rolled URCap API stubs
drifted away from the real `urcap-api-1.3.0` interface surface. Each
release broke a different Polyscope invocation path:

    3.0.0-hotfix1   javax.swing.border / javax.swing.text missing from Import-Package
    3.0.1           DataModel.isSet + Object get(String)      — STILL broken
    3.0.2           DataModel.get(key, primitive default)      — fixed
    3.0.3           InstallationNodeContribution.isDefined()  — AbstractMethodError
    3.0.4           DaemonService 2-method interface clarify  — docs-only

Signature regressions like "we forgot `int get(String, int)` on DataModel"
are catastrophic in a URCap: Polyscope refuses to load the bundle, the
operator sees a blank kiosk, and we burn an on-site visit diagnosing it.

How this works
--------------
Parses the Java class file format directly (no JDK required — pure
Python stdlib `struct`) and extracts every public/protected method
descriptor from every top-level class under `sk.stimba.*`. Diffs against
`wiki/public-api-baseline.txt`. Any missing or extra signature fails CI.

Runs on:
    - sandbox (no JDK)
    - macOS dev (full JDK)
    - GitHub Actions Linux

Usage
-----
    # diff mode (CI):
    python3 regress_signatures.py --diff <classes-dir>

    # regen baseline (after intentional API change):
    python3 regress_signatures.py --write <classes-dir> > baseline.txt
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path
from typing import Iterator


# ---------------------------------------------------------------------------
# Class-file constant-pool tags (JVMS §4.4)
# ---------------------------------------------------------------------------

CP_UTF8               = 1
CP_INTEGER            = 3
CP_FLOAT              = 4
CP_LONG               = 5  # takes two slots
CP_DOUBLE             = 6  # takes two slots
CP_CLASS              = 7
CP_STRING             = 8
CP_FIELDREF           = 9
CP_METHODREF          = 10
CP_INTERFACE_METHODREF = 11
CP_NAMEANDTYPE        = 12
CP_METHODHANDLE       = 15
CP_METHODTYPE         = 16
CP_DYNAMIC            = 17
CP_INVOKEDYNAMIC      = 18
CP_MODULE             = 19
CP_PACKAGE            = 20

# Access flags (JVMS §4.6 / §4.1)
ACC_PUBLIC      = 0x0001
ACC_PRIVATE     = 0x0002
ACC_PROTECTED   = 0x0004
ACC_STATIC      = 0x0008
ACC_FINAL       = 0x0010
ACC_ABSTRACT    = 0x0400
ACC_BRIDGE      = 0x0040
ACC_SYNTHETIC   = 0x1000


class ClassFileParser:
    """Minimal JVMS class-file parser. Extracts only what we need for drift
    detection: method names + descriptors filtered by access-flag policy."""

    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0
        self.cp: list[tuple] = [()]  # 1-indexed

    # ------- primitive readers -------
    def _u1(self) -> int:
        v = self.data[self.pos]
        self.pos += 1
        return v

    def _u2(self) -> int:
        v = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def _u4(self) -> int:
        v = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return v

    def _bytes(self, n: int) -> bytes:
        b = self.data[self.pos:self.pos + n]
        self.pos += n
        return b

    # ------- parse -------
    def parse(self) -> dict:
        magic = self._u4()
        if magic != 0xCAFEBABE:
            raise ValueError(f"not a class file (magic=0x{magic:08x})")
        self._u2()  # minor
        self._u2()  # major
        cp_count = self._u2()
        i = 1
        while i < cp_count:
            tag = self._u1()
            if tag == CP_UTF8:
                length = self._u2()
                raw = self._bytes(length)
                # Java "modified UTF-8" — for plain ASCII it's identical
                try:
                    value = raw.decode("utf-8")
                except UnicodeDecodeError:
                    value = raw.decode("latin-1")
                self.cp.append(("Utf8", value))
            elif tag in (CP_INTEGER, CP_FLOAT):
                self._u4()
                self.cp.append(("Int/Float",))
            elif tag in (CP_LONG, CP_DOUBLE):
                self._u4(); self._u4()
                self.cp.append(("Long/Double",))
                self.cp.append(("(wide slot)",))
                i += 1  # wide entries occupy two slots
            elif tag == CP_CLASS:
                self.cp.append(("Class", self._u2()))
            elif tag == CP_STRING:
                self.cp.append(("String", self._u2()))
            elif tag in (CP_FIELDREF, CP_METHODREF, CP_INTERFACE_METHODREF):
                self.cp.append(("Ref", self._u2(), self._u2()))
            elif tag == CP_NAMEANDTYPE:
                self.cp.append(("NameAndType", self._u2(), self._u2()))
            elif tag == CP_METHODHANDLE:
                self._u1(); self._u2()
                self.cp.append(("MethodHandle",))
            elif tag == CP_METHODTYPE:
                self.cp.append(("MethodType", self._u2()))
            elif tag in (CP_DYNAMIC, CP_INVOKEDYNAMIC):
                self._u2(); self._u2()
                self.cp.append(("Dynamic",))
            elif tag in (CP_MODULE, CP_PACKAGE):
                self.cp.append(("ModulePackage", self._u2()))
            else:
                raise ValueError(f"unknown CP tag {tag} at index {i}")
            i += 1

        class_access = self._u2()
        this_class_idx = self._u2()
        _super_idx = self._u2()

        this_class_name = self._resolve_class_name(this_class_idx)

        interfaces_count = self._u2()
        for _ in range(interfaces_count):
            self._u2()

        # Skip fields
        fields_count = self._u2()
        for _ in range(fields_count):
            self._u2()  # access
            self._u2()  # name
            self._u2()  # desc
            self._skip_attributes()

        # Methods — the actual signal
        methods: list[tuple[int, str, str]] = []
        methods_count = self._u2()
        for _ in range(methods_count):
            acc = self._u2()
            name_idx = self._u2()
            desc_idx = self._u2()
            self._skip_attributes()
            name = self._utf8(name_idx)
            desc = self._utf8(desc_idx)
            methods.append((acc, name, desc))

        return {
            "class_access": class_access,
            "this_class": this_class_name,
            "methods": methods,
        }

    def _skip_attributes(self) -> None:
        n = self._u2()
        for _ in range(n):
            self._u2()  # name_idx
            length = self._u4()
            self.pos += length

    def _utf8(self, idx: int) -> str:
        entry = self.cp[idx]
        if entry[0] != "Utf8":
            raise ValueError(f"cp[{idx}] is {entry[0]}, not Utf8")
        return entry[1]

    def _resolve_class_name(self, idx: int) -> str:
        entry = self.cp[idx]
        if entry[0] != "Class":
            raise ValueError(f"cp[{idx}] is {entry[0]}, not Class")
        return self._utf8(entry[1])


# ---------------------------------------------------------------------------
# Signature extraction / normalisation
# ---------------------------------------------------------------------------

def _parse_type(desc: str, i: int) -> tuple[str, int]:
    """Parse one JVM type descriptor starting at `desc[i]`, return (human, next_i)."""
    ch = desc[i]
    if ch == "B": return "byte", i + 1
    if ch == "C": return "char", i + 1
    if ch == "D": return "double", i + 1
    if ch == "F": return "float", i + 1
    if ch == "I": return "int", i + 1
    if ch == "J": return "long", i + 1
    if ch == "S": return "short", i + 1
    if ch == "Z": return "boolean", i + 1
    if ch == "V": return "void", i + 1
    if ch == "[":
        inner, j = _parse_type(desc, i + 1)
        return inner + "[]", j
    if ch == "L":
        end = desc.index(";", i)
        fqn = desc[i + 1:end].replace("/", ".")
        # drop package prefix for readability BUT keep java.* so we don't collide
        short = fqn.rsplit(".", 1)[-1]
        return short, end + 1
    raise ValueError(f"unknown type char {ch!r} in {desc!r}")


def humanise(desc: str) -> str:
    """(Ljava/lang/String;I)Ljava/lang/Object; -> (String, int) -> Object"""
    if not desc.startswith("("):
        return desc
    close = desc.index(")")
    params = desc[1:close]
    ret = desc[close + 1:]
    pieces: list[str] = []
    i = 0
    while i < len(params):
        t, i = _parse_type(params, i)
        pieces.append(t)
    ret_h, _ = _parse_type(ret, 0)
    return f"({', '.join(pieces)}) -> {ret_h}"


def signatures_for_class(class_file: Path, *, include_package_private: bool = True) -> Iterator[str]:
    """Yield one line per tracked method: `<class> :: <access> <name>(descriptor) -> return`."""
    data = class_file.read_bytes()
    parsed = ClassFileParser(data).parse()

    # Skip nested/anonymous classes — their API is not ours to track
    if "$" in parsed["this_class"]:
        return

    cls_acc = parsed["class_access"]
    if not (cls_acc & ACC_PUBLIC):
        # we only track public classes (matches our OSGi-exported surface)
        return

    cls = parsed["this_class"].replace("/", ".")

    for acc, name, desc in sorted(parsed["methods"], key=lambda m: (m[1], m[2])):
        # skip compiler-generated glue
        if acc & ACC_SYNTHETIC: continue
        if acc & ACC_BRIDGE: continue
        # skip private (not reachable from Polyscope anyway)
        if acc & ACC_PRIVATE: continue
        if not include_package_private:
            if not (acc & (ACC_PUBLIC | ACC_PROTECTED)):
                continue

        vis = (
            "public" if acc & ACC_PUBLIC else
            "protected" if acc & ACC_PROTECTED else
            "package"
        )
        static = " static" if acc & ACC_STATIC else ""
        abstract = " abstract" if acc & ACC_ABSTRACT else ""
        yield f"{cls} :: {vis}{static}{abstract} {name}{humanise(desc)}"


def collect(root: Path) -> list[str]:
    lines: list[str] = []
    for cf in sorted(root.rglob("*.class")):
        # stay under sk/stimba/ so we don't track deps we happen to bundle
        rel = cf.relative_to(root)
        if not str(rel).startswith(("sk/stimba/", "sk\\stimba\\")):
            continue
        lines.extend(signatures_for_class(cf))
    return lines


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description="URCap public-API drift detector")
    ap.add_argument("classes_dir", type=Path,
                    help="Root dir of compiled classes (e.g. an unpacked .urcap)")
    ap.add_argument("--write", action="store_true",
                    help="Write baseline to stdout instead of diffing")
    ap.add_argument("--diff", type=Path,
                    help="Compare against this baseline file; exit 2 on drift")
    args = ap.parse_args()

    if not args.classes_dir.is_dir():
        print(f"error: {args.classes_dir} is not a directory", file=sys.stderr)
        return 1

    current = collect(args.classes_dir)

    if args.write:
        print("# STIMBA VNC URCap — public API baseline")
        print("# Generated by build-with-docker/regress_signatures.py")
        print("# DO NOT EDIT BY HAND — regenerate with `make regress-baseline`.")
        print("# Drift from this file = CI failure = likely AbstractMethodError on Polyscope.")
        for line in current:
            print(line)
        return 0

    if args.diff:
        if not args.diff.is_file():
            print(f"error: baseline {args.diff} not found", file=sys.stderr)
            return 1
        expected = [
            l.rstrip()
            for l in args.diff.read_text().splitlines()
            if l.strip() and not l.startswith("#")
        ]
        cur_set = set(current)
        exp_set = set(expected)
        removed = sorted(exp_set - cur_set)
        added = sorted(cur_set - exp_set)
        if not removed and not added:
            print(f"[regress] OK — {len(current)} signatures match baseline")
            return 0
        if removed:
            print("\n[regress] REMOVED from baseline (DANGER — AbstractMethodError risk):")
            for s in removed:
                print(f"  - {s}")
        if added:
            print("\n[regress] ADDED to baseline (new surface — commit baseline if intentional):")
            for s in added:
                print(f"  + {s}")
        print(f"\n[regress] FAIL — {len(removed)} removed, {len(added)} added")
        print("[regress] If intentional, regenerate baseline with:")
        print("  python3 regress_signatures.py --write <classes-dir> > wiki/public-api-baseline.txt")
        return 2

    # default — just print current
    for line in current:
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
