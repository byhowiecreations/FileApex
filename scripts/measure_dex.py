#!/usr/bin/env python3
"""Measures a release build: DEX sections, code bytes by library family, FileApex code by package, APK contents.

Usage:
  python3 -I scripts/measure_dex.py --apk app-release.apk --mapping mapping.txt
  python3 -I scripts/measure_dex.py --dex classes.dex --dex classes2.dex --mapping mapping.txt

Code bytes are instruction bytes (insns_size * 2) of every method, grouped by the original class name
that the R8 mapping.txt gives back. Without a mapping, names are the (possibly renamed) descriptors.
"""
import argparse
import os
import re
import struct
import sys
import zipfile
from collections import defaultdict

SECTION_NAMES = {
    0x0000: "header",
    0x0001: "string_ids",
    0x0002: "type_ids",
    0x0003: "proto_ids",
    0x0004: "field_ids",
    0x0005: "method_ids",
    0x0006: "class_defs",
    0x0007: "call_site_ids",
    0x0008: "method_handles",
    0x1000: "map_list",
    0x1001: "type_lists",
    0x1002: "annotation_set_ref_lists",
    0x1003: "annotation_sets",
    0x2000: "class_data",
    0x2001: "code",
    0x2002: "string_data",
    0x2003: "debug_info",
    0x2004: "annotations",
    0x2005: "encoded_arrays",
    0x2006: "annotation_directories",
    0xF000: "hiddenapi_class_data",
}

FAMILY_PREFIXES = [
    ("com.fileapex", "FileApex"),
    ("androidx.compose", "Compose"),
    ("org.jetbrains.compose", "Compose"),
    ("androidx", "androidx other"),
    ("com.google.android.gms", "Play services"),
    ("com.google.firebase", "Firebase"),
    ("com.google.zxing", "zxing"),
    ("com.journeyapps", "zxing"),
    ("com.google", "Google other"),
    ("org.bouncycastle", "BouncyCastle"),
    ("io.ktor", "Ktor"),
    ("computer.iroh", "Iroh"),
    ("uniffi", "Iroh"),
    ("io.grpc", "gRPC"),
    ("kotlinx", "kotlinx"),
    ("kotlin", "kotlin"),
    ("okhttp3", "OkHttp"),
    ("okio", "OkHttp"),
]

FILEAPEX_PREFIX = "com.fileapex."
CLASS_LINE = re.compile(r"^(\S+) -> (\S+):$")


class DexInfo:
    def __init__(self, size, classes, methods, sections, class_code):
        self.size = size
        self.classes = classes
        self.methods = methods
        self.sections = sections
        self.class_code = class_code


def parse_mapping(text):
    """Obfuscated class name to original class name. Member lines and comments are skipped."""
    result = {}
    for line in text.splitlines():
        if not line or line[0] in " #\t":
            continue
        match = CLASS_LINE.match(line)
        if match:
            result[match.group(2)] = match.group(1)
    return result


def original_name(descriptor, mapping):
    name = descriptor[1:-1].replace("/", ".") if descriptor.startswith("L") and descriptor.endswith(";") else descriptor
    return mapping.get(name, name)


def family(name):
    for prefix, label in FAMILY_PREFIXES:
        if name == prefix or name.startswith(prefix + "."):
            return label
    return "other"


def _uleb(data, pos):
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def _u32(data, pos):
    return struct.unpack_from("<I", data, pos)[0]


def parse_dex(data):
    if len(data) < 0x70 or data[:4] != b"dex\n":
        raise ValueError("not a DEX file")
    file_size = _u32(data, 32)
    map_off = _u32(data, 52)
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 64)
    method_ids_size = _u32(data, 88)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 96)

    entries = []
    count = _u32(data, map_off)
    for i in range(count):
        kind, _unused, _size, offset = struct.unpack_from("<HHII", data, map_off + 4 + 12 * i)
        entries.append((offset, kind))
    entries.sort()
    sections = defaultdict(int)
    for i, (offset, kind) in enumerate(entries):
        end = entries[i + 1][0] if i + 1 < len(entries) else file_size
        sections[SECTION_NAMES.get(kind, "unknown_%04x" % kind)] += end - offset

    def descriptor(type_idx):
        string_idx = _u32(data, type_ids_off + 4 * type_idx)
        pos = _u32(data, string_ids_off + 4 * string_idx)
        length, pos = _uleb(data, pos)
        end = data.index(b"\x00", pos)
        return data[pos:end].decode("utf-8", "replace")

    class_code = {}
    for i in range(class_defs_size):
        base = class_defs_off + 32 * i
        class_idx = _u32(data, base)
        class_data_off = _u32(data, base + 24)
        total = 0
        if class_data_off:
            pos = class_data_off
            static_fields, pos = _uleb(data, pos)
            instance_fields, pos = _uleb(data, pos)
            direct_methods, pos = _uleb(data, pos)
            virtual_methods, pos = _uleb(data, pos)
            for _ in range(static_fields + instance_fields):
                _diff, pos = _uleb(data, pos)
                _flags, pos = _uleb(data, pos)
            for _ in range(direct_methods + virtual_methods):
                _diff, pos = _uleb(data, pos)
                _flags, pos = _uleb(data, pos)
                code_off, pos = _uleb(data, pos)
                if code_off:
                    total += _u32(data, code_off + 12) * 2
        class_code[descriptor(class_idx)] = total
    return DexInfo(len(data), class_defs_size, method_ids_size, dict(sections), class_code)


def code_by_family(infos, mapping):
    result = defaultdict(int)
    for info in infos:
        for descriptor, size in info.class_code.items():
            result[family(original_name(descriptor, mapping))] += size
    return dict(result)


def fileapex_by_package(infos, mapping):
    result = defaultdict(int)
    for info in infos:
        for descriptor, size in info.class_code.items():
            name = original_name(descriptor, mapping)
            if not name.startswith(FILEAPEX_PREFIX):
                continue
            package = name[len(FILEAPEX_PREFIX):].split(".")[:-1]
            result[".".join(package[:2]) if package else "(root)"] += size
    return dict(result)


def _kib(value):
    return "%d KiB" % round(value / 1024)


def _print_table(title, rows):
    print(title)
    for label, value in sorted(rows.items(), key=lambda item: -item[1]):
        print("  %-28s %12d bytes  %s" % (label, value, _kib(value)))


def _apk_sections(path):
    totals = defaultdict(int)
    libs = []
    with zipfile.ZipFile(path) as z:
        for entry in z.infolist():
            name = entry.filename
            if name.startswith("lib/"):
                libs.append((name, entry.file_size, entry.compress_size))
                group = "native libraries"
            elif name.endswith(".dex"):
                group = "dex"
            elif name.startswith("assets/"):
                group = "assets"
            elif name.startswith("res/") or name == "resources.arsc":
                group = "res"
            else:
                group = "other"
            totals[group] += entry.compress_size
    return totals, libs


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk")
    parser.add_argument("--dex", action="append", default=[])
    parser.add_argument("--mapping")
    args = parser.parse_args(argv)
    if not args.apk and not args.dex:
        print("give --apk or at least one --dex", file=sys.stderr)
        return 2

    blobs = []
    for path in args.dex:
        with open(path, "rb") as f:
            blobs.append((os.path.basename(path), f.read()))
    if args.apk:
        with zipfile.ZipFile(args.apk) as z:
            for name in sorted(z.namelist()):
                if re.fullmatch(r"classes\d*\.dex", name):
                    blobs.append((name, z.read(name)))
    mapping = {}
    if args.mapping:
        with open(args.mapping, encoding="utf-8", errors="replace") as f:
            mapping = parse_mapping(f.read())

    infos = []
    for name, blob in blobs:
        info = parse_dex(blob)
        infos.append(info)
        print("%s: %d bytes, %d classes, %d methods" % (name, info.size, info.classes, info.methods))
    print("DEX files: %d" % len(infos))
    print("DEX bytes: %d (%.2f MiB)" % (sum(i.size for i in infos), sum(i.size for i in infos) / 1048576))
    print("classes: %d" % sum(i.classes for i in infos))
    print("methods: %d" % sum(i.methods for i in infos))

    sections = defaultdict(int)
    for info in infos:
        for key, value in info.sections.items():
            sections[key] += value
    _print_table("DEX sections (bytes, padding included):", sections)
    _print_table("Code by family (instruction bytes):", code_by_family(infos, mapping))
    if not mapping:
        print("  (no --mapping: names are the shrunk descriptors, so families are only exact for kept names)")
    _print_table("FileApex code by package (instruction bytes):", fileapex_by_package(infos, mapping))

    if args.apk:
        totals, libs = _apk_sections(args.apk)
        print("APK bytes: %d (%.2f MiB)" % (os.path.getsize(args.apk), os.path.getsize(args.apk) / 1048576))
        _print_table("APK content by group (compressed bytes):", totals)
        print("Native libraries (stored bytes, uncompressed bytes):")
        for name, size, compressed in sorted(libs, key=lambda item: -item[2]):
            print("  %-48s %12d %12d" % (name, compressed, size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
