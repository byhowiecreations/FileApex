#!/usr/bin/env python3
"""Fail if a Play bundle (.aab) would be refused or flagged by Google Play.

Run automatically by the bundlePlayRelease step (movePlayReleaseAabToCurrent) and by hand:
    python3 scripts/check_play_bundle.py path/to/FileApex-v0.19.1a-release.aab [--last-uploaded CODE]

Checks
  1. 16 KB page size: every 64-bit native library (arm64-v8a, x86_64) has all PT_LOAD segments aligned to at least
     16384 bytes. A third-party library built with 4 KB alignment makes Play report "Your app does not support
     16 KB memory page sizes".
  2. Restricted permissions that the Play flavor must not request.
  3. The version code is a positive number and, when --last-uploaded is given (or scripts/play-last-versioncode.txt
     exists), is greater than the last version code uploaded to Play. A used code is rejected by Play.
Exit code 0 means compliant, 1 means a problem (printed), 2 means the check itself could not run.
"""
import argparse
import os
import struct
import sys
import zipfile

ALIGN_MIN = 16384
SIXTY_FOUR_BIT_ABIS = ("arm64-v8a", "x86_64")
FORBIDDEN_PERMISSIONS = {
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.READ_PHONE_STATE",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "moe.shizuku.manager.permission.API_V23",
}


def load_alignments(data):
    """Return the p_align of every PT_LOAD segment, or None if this is not an ELF file."""
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    e = "<" if data[5] == 1 else ">"
    out = []
    if is64:
        phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from(e + "HH", data, 0x36)
        for i in range(phnum):
            o = phoff + i * phentsize
            if struct.unpack_from(e + "I", data, o)[0] == 1:
                out.append(struct.unpack_from(e + "Q", data, o + 0x30)[0])
    else:
        phoff = struct.unpack_from(e + "I", data, 0x1C)[0]
        phentsize, phnum = struct.unpack_from(e + "HH", data, 0x2A)
        for i in range(phnum):
            o = phoff + i * phentsize
            if struct.unpack_from(e + "I", data, o)[0] == 1:
                out.append(struct.unpack_from(e + "I", data, o + 0x1C)[0])
    return out


def check_alignment(aab):
    problems = []
    checked = 0
    with zipfile.ZipFile(aab) as z:
        for name in z.namelist():
            if not name.endswith(".so"):
                continue
            parts = name.split("/")
            # <module>/lib/<abi>/<file>.so
            if len(parts) < 4 or parts[1] != "lib" or parts[2] not in SIXTY_FOUR_BIT_ABIS:
                continue
            aligns = load_alignments(z.read(name))
            if not aligns:
                continue
            checked += 1
            if min(aligns) < ALIGN_MIN:
                problems.append(f"{name} is aligned to {min(aligns)} bytes, Play needs {ALIGN_MIN} or more")
    return checked, problems


def _varint(buf, pos):
    shift = result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def _fields(buf):
    """Yield (field number, wire type, value) of one protobuf message; value is int or bytes."""
    pos = 0
    while pos < len(buf):
        key, pos = _varint(buf, pos)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, pos = _varint(buf, pos)
        elif wire == 2:
            length, pos = _varint(buf, pos)
            value, pos = buf[pos:pos + length], pos + length
        elif wire == 1:
            value, pos = buf[pos:pos + 8], pos + 8
        elif wire == 5:
            value, pos = buf[pos:pos + 4], pos + 4
        else:
            raise RuntimeError(f"unsupported protobuf wire type {wire}")
        yield field, wire, value


def _walk(node, found):
    """Collect (element name, {attribute: value}) for every element of a compiled (proto) XmlNode."""
    for field, wire, value in _fields(node):
        if field != 1 or wire != 2:
            continue
        name, attrs, children = "", {}, []
        for f, w, v in _fields(value):
            if f == 3 and w == 2:
                name = v.decode("utf-8", "replace")
            elif f == 4 and w == 2:
                attr = {fa: va for fa, wa, va in _fields(v) if wa == 2}
                attrs[attr.get(2, b"").decode("utf-8", "replace")] = attr.get(3, b"").decode("utf-8", "replace")
            elif f == 5 and w == 2:
                children.append(v)
        found.append((name, attrs))
        for child in children:
            _walk(child, found)


def read_manifest(aab):
    """Permissions and version code from the bundle's base manifest (a compiled proto XML file)."""
    with zipfile.ZipFile(aab) as z:
        try:
            data = z.read("base/manifest/AndroidManifest.xml")
        except KeyError:
            raise RuntimeError("base/manifest/AndroidManifest.xml is missing from the bundle")
    elements = []
    _walk(data, elements)
    permissions = {a.get("name", "") for n, a in elements if n in ("uses-permission", "uses-permission-sdk-23")}
    code = 0
    for n, a in elements:
        if n == "manifest" and a.get("versionCode", "").isdigit():
            code = int(a["versionCode"])
            break
    if not elements:
        raise RuntimeError("the bundle manifest could not be read")
    return permissions, code


def last_uploaded(args):
    if args.last_uploaded is not None:
        return args.last_uploaded
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "play-last-versioncode.txt")
    if os.path.isfile(path):
        try:
            return int(open(path).read().strip())
        except ValueError:
            return None
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("aab")
    parser.add_argument("--last-uploaded", type=int, default=None)
    args = parser.parse_args()
    if not os.path.isfile(args.aab):
        print(f"check_play_bundle: {args.aab} does not exist")
        return 2

    problems = []
    checked, align_problems = check_alignment(args.aab)
    problems += align_problems
    print(f"16 KB alignment: {checked} 64-bit native libraries checked")

    try:
        permissions, code = read_manifest(args.aab)
    except (RuntimeError, IndexError, zipfile.BadZipFile) as error:
        print(f"check_play_bundle: {error}")
        return 2
    for perm in sorted(permissions & FORBIDDEN_PERMISSIONS):
        problems.append(f"the bundle requests {perm}, which the Play build must not")
    print(f"permissions: {len(permissions)} requested")

    if code <= 0:
        problems.append("the bundle has no version code")
    previous = last_uploaded(args)
    if previous is not None and code <= previous:
        problems.append(f"version code {code} was already uploaded to Play (last uploaded {previous}); raise it in version.md")
    print(f"version code: {code}" + (f" (last uploaded {previous})" if previous is not None else ""))

    if problems:
        print("\nNOT PLAY READY:")
        for p in problems:
            print("  - " + p)
        return 1
    print("\nPlay ready: 16 KB alignment, permissions and version code are fine.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
