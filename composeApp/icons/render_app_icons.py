#!/usr/bin/env python3
"""Generate desktop + tray icons from icon/icon.png (project launcher source)."""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE_PNG = ROOT / "icon" / "icon.png"
COMPOSE_ICONS = ROOT / "composeApp" / "icons"
ANDROID_LAUNCHER = ROOT / "composeApp" / "src" / "androidMain" / "res" / "drawable" / "ic_launcher.png"
TRAY_PNG = ROOT / "shared" / "src" / "desktopMain" / "resources" / "icons" / "fileapex-tray.png"
PNG_1024 = COMPOSE_ICONS / "FileApex-1024.png"
ICNS = COMPOSE_ICONS / "FileApex.icns"
ICO = COMPOSE_ICONS / "FileApex.ico"


def load_source() -> Image.Image:
    if not SOURCE_PNG.is_file():
        raise FileNotFoundError(f"Missing launcher source: {SOURCE_PNG}")
    image = Image.open(SOURCE_PNG).convert("RGBA")
    return image


def save_square_png(image: Image.Image, size: int, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    resized = image.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(path, format="PNG", optimize=True)


def write_ico(path: Path, base: Image.Image) -> None:
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = [base.resize((s, s), Image.Resampling.LANCZOS) for s in sizes]
    images[0].save(
        path,
        format="ICO",
        sizes=[(img.width, img.height) for img in images],
        append_images=images[1:],
    )


def write_icns(path: Path, base: Image.Image) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        iconset = Path(tmp) / "FileApex.iconset"
        iconset.mkdir()
        entries = [
            ("icon_16x16.png", 16),
            ("icon_16x16@2x.png", 32),
            ("icon_32x32.png", 32),
            ("icon_32x32@2x.png", 64),
            ("icon_128x128.png", 128),
            ("icon_128x128@2x.png", 256),
            ("icon_256x256.png", 256),
            ("icon_256x256@2x.png", 512),
            ("icon_512x512.png", 512),
            ("icon_512x512@2x.png", 1024),
        ]
        for name, size in entries:
            save_square_png(base, size, iconset / name)
        subprocess.run(
            ["iconutil", "-c", "icns", str(iconset), "-o", str(path)],
            check=True,
        )


def main() -> int:
    source = load_source()
    COMPOSE_ICONS.mkdir(parents=True, exist_ok=True)
    save_square_png(source, 1024, PNG_1024)
    save_square_png(source, 512, ANDROID_LAUNCHER)
    save_square_png(source, 256, TRAY_PNG)
    write_ico(ICO, source)
    if sys.platform == "darwin":
        write_icns(ICNS, source)
    else:
        print("Skipping ICNS generation (requires macOS iconutil)")
    print(f"Source: {SOURCE_PNG}")
    print(f"Wrote {PNG_1024}")
    print(f"Wrote {ANDROID_LAUNCHER}")
    print(f"Wrote {TRAY_PNG}")
    print(f"Wrote {ICO}")
    if ICNS.exists():
        print(f"Wrote {ICNS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
