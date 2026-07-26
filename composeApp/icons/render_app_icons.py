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
ANDROID_NOTIFICATION = ROOT / "composeApp" / "src" / "androidMain" / "res" / "drawable" / "ic_notification.png"
SHARED_NOTIFICATION = ROOT / "shared" / "src" / "androidMain" / "res" / "drawable" / "ic_fileapex_notification.png"
SHARED_NOTIFICATION_LARGE = ROOT / "shared" / "src" / "androidMain" / "res" / "drawable" / "ic_fileapex_large.png"
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


def write_notification_icon(source: Image.Image, path: Path, size: int = 96) -> None:
    """White silhouette on transparent — Android tints only opaque pixels in the status bar."""
    path.parent.mkdir(parents=True, exist_ok=True)
    width, height = source.size
    # Drop the launcher tile border (rounded square frame) before extracting the logo.
    inset = int(min(width, height) * 0.17)
    cropped = source.crop((inset, inset, width - inset, height - inset))
    resized = cropped.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    pixels = resized.load()
    corners = [
        pixels[0, 0],
        pixels[size - 1, 0],
        pixels[0, size - 1],
        pixels[size - 1, size - 1],
    ]
    background = tuple(sum(channel[i] for channel in corners) // 4 for i in range(3))

    def luminance(rgb: tuple[int, int, int]) -> float:
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]

    def distance(rgb: tuple[int, int, int]) -> float:
        return sum((rgb[i] - background[i]) ** 2 for i in range(3)) ** 0.5

    silhouette = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out = silhouette.load()
    edge_guard = max(2, int(size * 0.04))
    for y in range(size):
        for x in range(size):
            if (
                x < edge_guard
                or y < edge_guard
                or x >= size - edge_guard
                or y >= size - edge_guard
            ):
                continue
            red, green, blue, _ = pixels[x, y]
            rgb = (red, green, blue)
            lum = luminance(rgb)
            dist = distance(rgb)
            if lum >= 200 or (dist >= 45 and lum >= 120):
                out[x, y] = (255, 255, 255, 255)
            elif lum >= 170 and dist >= 30:
                alpha = min(255, int((lum - 170) * 5))
                if alpha > 0:
                    out[x, y] = (255, 255, 255, alpha)
    silhouette.save(path, format="PNG", optimize=True)


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
    write_notification_icon(source, ANDROID_NOTIFICATION)
    write_notification_icon(source, SHARED_NOTIFICATION)
    save_square_png(source, 256, SHARED_NOTIFICATION_LARGE)
    save_square_png(source, 256, TRAY_PNG)
    write_ico(ICO, source)
    if sys.platform == "darwin":
        write_icns(ICNS, source)
    else:
        print("Skipping ICNS generation (requires macOS iconutil)")
    print(f"Source: {SOURCE_PNG}")
    print(f"Wrote {PNG_1024}")
    print(f"Wrote {ANDROID_LAUNCHER}")
    print(f"Wrote {ANDROID_NOTIFICATION}")
    print(f"Wrote {SHARED_NOTIFICATION}")
    print(f"Wrote {SHARED_NOTIFICATION_LARGE}")
    print(f"Wrote {TRAY_PNG}")
    print(f"Wrote {ICO}")
    if ICNS.exists():
        print(f"Wrote {ICNS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
