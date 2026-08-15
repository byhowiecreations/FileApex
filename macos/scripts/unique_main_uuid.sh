#!/usr/bin/env bash
# Give FileApex.app a unique main-executable Mach-O UUID.
# macOS Local Network privacy keys off that UUID and cannot be reset with tccutil.
set -euo pipefail

APP="${1:?Usage: unique_main_uuid.sh FileApex.app}"
BIN="$APP/Contents/MacOS/FileApex"
if [[ ! -f "$BIN" ]]; then
  echo "Missing launcher $BIN" >&2
  exit 1
fi

/usr/bin/python3 - "$BIN" <<'PY'
import struct, sys, uuid

LC_UUID = 0x1B
MH_MAGIC_64 = 0xFEEDFACF
FAT_MAGIC = 0xCAFEBABE
FAT_MAGIC_64 = 0xCAFEBABF

path = sys.argv[1]
data = bytearray(open(path, "rb").read())
new_id = uuid.uuid4()
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
            buf[pos + 8:pos + 24] = new_id.bytes
            replaced.append((str(old), str(new_id)))
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
echo "Re-signed $APP with unique launcher UUID"
