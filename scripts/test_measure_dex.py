#!/usr/bin/env python3
"""Tests for measure_dex.py. Run: python3 -I scripts/test_measure_dex.py"""
import contextlib
import io
import os
import struct
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import measure_dex as md  # noqa: E402


def uleb(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def make_dex(classes):
    """A minimal DEX. classes is a list of (descriptor, [instruction units per method])."""
    descriptors = sorted(d for d, _ in classes)
    strings = [d.encode() for d in descriptors]
    method_total = sum(len(m) for _, m in classes)
    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + 4 * len(strings)
    method_ids_off = type_ids_off + 4 * len(descriptors)
    class_defs_off = method_ids_off + 8 * method_total
    data_off = class_defs_off + 32 * len(classes)

    data = bytearray()
    string_offsets = []
    for s in strings:
        string_offsets.append(data_off + len(data))
        data += uleb(len(s)) + s + b"\x00"
    code_offsets = {}
    for descriptor, methods in classes:
        offsets = []
        for units in methods:
            while (data_off + len(data)) % 4:
                data += b"\x00"
            offsets.append(data_off + len(data))
            data += struct.pack("<HHHHII", 1, 0, 0, 0, 0, units) + b"\x00\x00" * units
        code_offsets[descriptor] = offsets
    class_data_offsets = {}
    for descriptor, methods in classes:
        class_data_offsets[descriptor] = data_off + len(data) if methods else 0
        if methods:
            data += uleb(0) + uleb(0) + uleb(len(methods)) + uleb(0)
            for i, off in enumerate(code_offsets[descriptor]):
                data += uleb(1 if i else 0) + uleb(0x9) + uleb(off)
    while (data_off + len(data)) % 4:
        data += b"\x00"
    map_off = data_off + len(data)
    entries = [
        (0x0000, 1, 0),
        (0x0001, len(strings), string_ids_off),
        (0x0002, len(descriptors), type_ids_off),
        (0x0005, method_total, method_ids_off),
        (0x0006, len(classes), class_defs_off),
        (0x2002, len(strings), string_offsets[0]),
        (0x2001, method_total, min(o for v in code_offsets.values() for o in v)),
        (0x2000, len(classes), min(v for v in class_data_offsets.values() if v)),
        (0x1000, 1, map_off),
    ]
    data += struct.pack("<I", len(entries))
    for kind, size, off in entries:
        data += struct.pack("<HHII", kind, 0, size, off)
    file_size = data_off + len(data)

    header = bytearray(header_size)
    header[0:8] = b"dex\n035\x00"
    struct.pack_into("<I", header, 32, file_size)
    struct.pack_into("<I", header, 36, header_size)
    struct.pack_into("<I", header, 40, 0x12345678)
    struct.pack_into("<I", header, 52, map_off)
    struct.pack_into("<II", header, 56, len(strings), string_ids_off)
    struct.pack_into("<II", header, 64, len(descriptors), type_ids_off)
    struct.pack_into("<II", header, 88, method_total, method_ids_off)
    struct.pack_into("<II", header, 96, len(classes), class_defs_off)
    struct.pack_into("<II", header, 104, len(data), data_off)

    body = b"".join(struct.pack("<I", o) for o in string_offsets)
    body += b"".join(struct.pack("<I", i) for i in range(len(descriptors)))
    body += b"\x00" * (8 * method_total)
    for descriptor, _ in classes:
        type_idx = descriptors.index(descriptor)
        body += struct.pack("<IIIIIIII", type_idx, 1, 0xFFFFFFFF, 0, 0, 0, class_data_offsets[descriptor], 0)
    return bytes(header) + body + bytes(data)


MAPPING = """# compiler: R8
# pg_map_id: abc
com.fileapex.ui.explorer.ExplorerScreen -> a.a:
    void render() -> a
com.fileapex.Main -> a.b:
androidx.compose.runtime.Composer -> b.a:
com.google.android.gms.tasks.Task -> c.a:
com.google.zxing.Reader -> d.a:
"""


class MappingTest(unittest.TestCase):
    def test_parses_class_lines_only(self):
        mapping = md.parse_mapping(MAPPING)
        self.assertEqual(mapping["a.a"], "com.fileapex.ui.explorer.ExplorerScreen")
        self.assertEqual(mapping["a.b"], "com.fileapex.Main")
        self.assertEqual(len(mapping), 5)

    def test_original_name_falls_back_to_descriptor(self):
        mapping = md.parse_mapping(MAPPING)
        self.assertEqual(md.original_name("La/a;", mapping), "com.fileapex.ui.explorer.ExplorerScreen")
        self.assertEqual(md.original_name("Lcom/other/Thing;", mapping), "com.other.Thing")


class FamilyTest(unittest.TestCase):
    def test_families(self):
        cases = {
            "com.fileapex.Main": "FileApex",
            "androidx.compose.runtime.Composer": "Compose",
            "org.jetbrains.compose.resources.Res": "Compose",
            "androidx.room.RoomDatabase": "androidx other",
            "com.google.android.gms.tasks.Task": "Play services",
            "com.google.firebase.messaging.FirebaseMessaging": "Firebase",
            "com.google.zxing.Reader": "zxing",
            "com.journeyapps.barcodescanner.CaptureManager": "zxing",
            "com.google.protobuf.Any": "Google other",
            "org.bouncycastle.crypto.Digest": "BouncyCastle",
            "io.ktor.client.HttpClient": "Ktor",
            "computer.iroh.Node": "Iroh",
            "uniffi.iroh_ffi.Thing": "Iroh",
            "io.grpc.Channel": "gRPC",
            "kotlinx.coroutines.Job": "kotlinx",
            "kotlin.collections.List": "kotlin",
            "okhttp3.OkHttpClient": "OkHttp",
            "okio.Buffer": "OkHttp",
            "java.lang.Object": "other",
        }
        for name, expected in cases.items():
            self.assertEqual(md.family(name), expected, name)


class DexParseTest(unittest.TestCase):
    def setUp(self):
        self.dex = make_dex([("La/a;", [10, 5]), ("La/b;", [3]), ("Lb/a;", [])])
        self.info = md.parse_dex(self.dex)

    def test_counts_and_size(self):
        self.assertEqual(self.info.size, len(self.dex))
        self.assertEqual(self.info.classes, 3)
        self.assertEqual(self.info.methods, 3)

    def test_sections_cover_the_file(self):
        self.assertEqual(sum(self.info.sections.values()), len(self.dex))
        for name in ("header", "string_ids", "code", "class_data", "map_list"):
            self.assertIn(name, self.info.sections)

    def test_code_bytes_per_class_are_instruction_bytes(self):
        self.assertEqual(self.info.class_code["La/a;"], (10 + 5) * 2)
        self.assertEqual(self.info.class_code["La/b;"], 3 * 2)
        self.assertEqual(self.info.class_code["Lb/a;"], 0)

    def test_rejects_non_dex(self):
        with self.assertRaises(ValueError):
            md.parse_dex(b"not a dex file at all" * 10)


class ReportTest(unittest.TestCase):
    def test_code_by_family_and_fileapex_packages(self):
        dex = make_dex([("La/a;", [10]), ("La/b;", [20]), ("Lb/a;", [30]), ("Lc/a;", [5])])
        info = md.parse_dex(dex)
        mapping = md.parse_mapping(MAPPING)
        families = md.code_by_family([info], mapping)
        self.assertEqual(families["FileApex"], (10 + 20) * 2)
        self.assertEqual(families["Compose"], 30 * 2)
        self.assertEqual(families["Play services"], 5 * 2)
        packages = md.fileapex_by_package([info], mapping)
        self.assertEqual(packages["ui.explorer"], 10 * 2)
        self.assertEqual(packages["(root)"], 20 * 2)

    def test_two_dex_files_add_up(self):
        one = md.parse_dex(make_dex([("La/a;", [10])]))
        two = md.parse_dex(make_dex([("La/b;", [20])]))
        families = md.code_by_family([one, two], md.parse_mapping(MAPPING))
        self.assertEqual(families["FileApex"], 30 * 2)


class CliTest(unittest.TestCase):
    def run_main(self, args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = md.main(args)
        return code, out.getvalue()

    def test_apk_input_prints_all_sections(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = os.path.join(tmp, "app.apk")
            mapping = os.path.join(tmp, "mapping.txt")
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", make_dex([("La/a;", [10])]))
                z.writestr("classes2.dex", make_dex([("La/b;", [20])]))
                z.writestr("lib/arm64-v8a/libx.so", b"\x00" * 100)
            with open(mapping, "w") as f:
                f.write(MAPPING)
            code, text = self.run_main(["--apk", apk, "--mapping", mapping])
        self.assertEqual(code, 0)
        self.assertIn("DEX files: 2", text)
        self.assertIn("classes: 2", text)
        self.assertIn("methods: 2", text)
        self.assertIn("FileApex", text)
        self.assertIn("ui.explorer", text)
        self.assertIn("lib/arm64-v8a/libx.so", text)
        self.assertIn("APK bytes:", text)

    def test_no_input_is_an_error(self):
        code, _ = self.run_main([])
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
