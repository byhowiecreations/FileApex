#!/usr/bin/env bash
# Stamp FileApex.app with the *stable* launcher Mach-O UUID.
# macOS Local Network privacy keys off that UUID (cannot reset with tccutil).
# Generate once, persist in macos/launcher.uuid, reuse every ship.
set -euo pipefail

APP="${1:?Usage: unique_main_uuid.sh FileApex.app}"
BIN="$APP/Contents/MacOS/FileApex"
if [[ ! -f "$BIN" ]]; then
  echo "Missing launcher $BIN" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UUID_FILE="$SCRIPT_DIR/../launcher.uuid"
APPS_BIN="/Applications/FileApex.app/Contents/MacOS/FileApex"

/usr/bin/python3 - "$BIN" "$UUID_FILE" "$APPS_BIN" <<'PY'
import os
import struct
import sys
import uuid

LC_UUID = 0x1B
MH_MAGIC_64 = 0xFEEDFACF
FAT_MAGIC = 0xCAFEBABE
FAT_MAGIC_64 = 0xCAFEBABF

path, uuid_file, apps_bin = sys.argv[1], sys.argv[2], sys.argv[3]


def read_uuid(buf, offset=0):
    magic = struct.unpack_from("<I", buf, offset)[0]
    if magic != MH_MAGIC_64:
        return None
    ncmds = struct.unpack_from("<I", buf, offset + 16)[0]
    pos = offset + 32
    for _ in range(ncmds):
        cmd, size = struct.unpack_from("<II", buf, pos)
        if cmd == LC_UUID and size >= 24:
            return uuid.UUID(bytes=bytes(buf[pos + 8:pos + 24]))
        if size == 0:
            return None
        pos += size
    return None


def first_uuid(data):
    magic = struct.unpack_from(">I", data, 0)[0]
    if magic in (FAT_MAGIC, FAT_MAGIC_64):
        nfat = struct.unpack_from(">I", data, 4)[0]
        for i in range(nfat):
            rec = 8 + i * 20
            off = struct.unpack_from(">I", data, rec + 8)[0]
            found = read_uuid(data, off)
            if found is not None:
                return found
        return None
    return read_uuid(data, 0)


def parse_uuid(raw):
    cleaned = raw.strip()
    if not cleaned:
        return None
    try:
        return uuid.UUID(cleaned)
    except ValueError:
        return None


def load_persisted():
    if not os.path.isfile(uuid_file):
        return None
    with open(uuid_file, "r", encoding="utf-8") as handle:
        return parse_uuid(handle.read())


def load_from_apps():
    if not os.path.isfile(apps_bin):
        return None
    with open(apps_bin, "rb") as handle:
        return first_uuid(handle.read())


keep = load_persisted() or load_from_apps()
if keep is None:
    keep = uuid.uuid4()
    print(f"FileApex launcher UUID generated {keep}")
else:
    print(f"FileApex launcher UUID keep {keep}")

os.makedirs(os.path.dirname(uuid_file), exist_ok=True)
with open(uuid_file, "w", encoding="utf-8") as handle:
    handle.write(f"{keep}\n")

data = bytearray(open(path, "rb").read())
current = first_uuid(data)
if current == keep:
    print(f"FileApex launcher UUID already {keep}")
    sys.exit(0)

replaced = []


def patch_thin(buf, offset):
    magic = struct.unpack_from("<I", buf, offset)[0]
    if magic != MH_MAGIC_64:
        return
    ncmds = struct.unpack_from("<I", buf, offset + 16)[0]
    pos = offset + 32
    for _ in range(ncmds):
        cmd, size = struct.unpack_from("<II", buf, pos)
        if cmd == LC_UUID and size >= 24:
            old = uuid.UUID(bytes=bytes(buf[pos + 8:pos + 24]))
            buf[pos + 8:pos + 24] = keep.bytes
            replaced.append((str(old), str(keep)))
            return
        if size == 0:
            return
        pos += size


magic = struct.unpack_from(">I", data, 0)[0]
if magic in (FAT_MAGIC, FAT_MAGIC_64):
    nfat = struct.unpack_from(">I", data, 4)[0]
    for i in range(nfat):
        rec = 8 + i * 20
        off = struct.unpack_from(">I", data, rec + 8)[0]
        patch_thin(data, off)
else:
    patch_thin(data, 0)

if not replaced:
    sys.exit("No LC_UUID found in " + path)
open(path, "wb").write(data)
for old, new in replaced:
    print(f"FileApex launcher UUID {old} -> {new}")
PY

chmod +x "$BIN"
codesign --force --sign - --timestamp=none "$BIN"
codesign --force --sign - --timestamp=none "$APP"
echo "Re-signed $APP with stable launcher UUID"
