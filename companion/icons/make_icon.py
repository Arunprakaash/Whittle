"""Draws the computer app's icon from Whittle's beaver sprite: the idle frame on the launcher's pine, in a rounded square
the size macOS gives app icons. Writes every size into Whittle.icns for the Mac bundle, a PNG the window and Dock use
when the app runs outside it, and the browser version's favicon, with the Mac's own sips and iconutil.

Usage (on a Mac): python3 companion/icons/make_icon.py
"""

import json
import pathlib
import shutil
import struct
import subprocess
import tempfile
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SPRITE = ROOT / "docs/design/sprites/beaver.sprite.json"
COLORS = ROOT / "app/src/main/res/values/brand_colors.xml"
OUTPUT = pathlib.Path(__file__).resolve().parent / "Whittle.icns"
WINDOW_ICON = ROOT / "companion/src/commonMain/composeResources/drawable/whittle_icon.png"
FAVICON = ROOT / "companion/src/wasmJsMain/resources/favicon.png"
WINDOW_ICON_SIZE = 512
FAVICON_SIZE = 64
FRAME = "idle"
SIZE = 1024
# macOS app icons: an 824-point rounded square, 100 points in from each edge, with corners of about 185.
INSET = 100
CORNER = 185
# The beaver fills this much of the square's width, as the launcher's safe zone leaves it.
ART_SHARE = 0.62
ICONSET_SIZES = [16, 32, 64, 128, 256, 512, 1024]


def hex_color(value: str) -> tuple:
    value = value.lstrip("#")[-6:]
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def brand_pine() -> tuple:
    text = COLORS.read_text(encoding="utf-8")
    start = text.index('name="brand_pine"')
    return hex_color(text[text.index(">", start) + 1:text.index("<", start)].strip())


def inside_rounded_square(x: int, y: int) -> bool:
    low, high = INSET, SIZE - INSET
    if not (low <= x < high and low <= y < high):
        return False
    cx = min(max(x, low + CORNER), high - CORNER)
    cy = min(max(y, low + CORNER), high - CORNER)
    return (x - cx) ** 2 + (y - cy) ** 2 <= CORNER ** 2


def draw() -> bytes:
    sprite = json.loads(SPRITE.read_text(encoding="utf-8"))
    palette = {key: hex_color(value) for key, value in sprite["palette"].items()}
    rows = sprite["frames"][FRAME]
    width, height = sprite["width"], sprite["height"]
    pixel = int((SIZE - 2 * INSET) * ART_SHARE // width)
    left = (SIZE - width * pixel) // 2
    top = (SIZE - height * pixel) // 2
    background = brand_pine()
    clear = (0, 0, 0, 0)
    lines = []
    for y in range(SIZE):
        line = bytearray([0])
        for x in range(SIZE):
            color = background if inside_rounded_square(x, y) else clear
            column, row = (x - left) // pixel, (y - top) // pixel
            if 0 <= column < width and 0 <= row < height and x >= left and y >= top:
                color = palette.get(rows[row][column], color)
            line.extend(color)
        lines.append(bytes(line))
    raw = b"".join(lines)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def main() -> None:
    work = pathlib.Path(tempfile.mkdtemp())
    try:
        master = work / "icon.png"
        master.write_bytes(draw())
        iconset = work / "Whittle.iconset"
        iconset.mkdir()
        for size in ICONSET_SIZES:
            for scale, suffix in ((1, ""), (2, "@2x")):
                pixels = size * scale
                if pixels > SIZE or (scale == 2 and size == SIZE):
                    continue
                name = iconset / f"icon_{size}x{size}{suffix}.png"
                subprocess.run(["sips", "-z", str(pixels), str(pixels), str(master), "--out", str(name)], check=True, capture_output=True)
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(OUTPUT)], check=True)
        for path, pixels in ((WINDOW_ICON, WINDOW_ICON_SIZE), (FAVICON, FAVICON_SIZE)):
            path.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["sips", "-z", str(pixels), str(pixels), str(master), "--out", str(path)], check=True, capture_output=True)
        print("wrote", OUTPUT, WINDOW_ICON, FAVICON)
    finally:
        shutil.rmtree(work)


if __name__ == "__main__":
    main()
