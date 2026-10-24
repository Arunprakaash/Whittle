#!/usr/bin/env python3
"""Draws the landing page's link-preview picture (og:image) into the file site.json's ogImage names.

The picture is the page's look in one frame: graph paper, the title and tagline in Figtree with a marker under the
key phrase, and the app's beaver from its sprite. Text is shaped with HarfBuzz and drawn as outlines from the font
file, then rasterised to PNG, since link previews don't show SVG. The PNG is committed and build.mjs only copies it,
so run this again after changing ogImage or the beaver sprite.

Needs Python 3 with fontTools, HarfBuzz's hb-shape and librsvg's rsvg-convert
(macOS: brew install harfbuzz librsvg && pip3 install fonttools).

    python3 site/og-image.py
"""
import json
import subprocess
import tempfile
from functools import lru_cache
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

SITE_DIR = Path(__file__).resolve().parent


class Typeface:
    """One variable font instanced per weight, shaped with hb-shape and drawn as SVG path data."""

    def __init__(self, font_file, cache_dir):
        self.font_file = font_file
        self.cache_dir = Path(cache_dir)

    @lru_cache(maxsize=None)
    def instance(self, weight):
        out = self.cache_dir / f"{self.font_file.stem}-{weight}.ttf"
        instancer.instantiateVariableFont(TTFont(self.font_file), {"wght": weight}).save(out)
        return out, TTFont(out)

    @lru_cache(maxsize=None)
    def shape(self, weight, text):
        file, _ = self.instance(weight)
        result = subprocess.run(["hb-shape", "--output-format=json", "--no-glyph-names", f"--font-file={file}", text],
                                capture_output=True, text=True, check=True)
        return tuple((g["g"], g["dx"], g["dy"], g["ax"]) for g in json.loads(result.stdout))

    def width(self, style, text):
        _, font = self.instance(style["weight"])
        glyphs = self.shape(style["weight"], text)
        scale = style["size"] / font["head"].unitsPerEm
        return sum(ax for *_, ax in glyphs) * scale + style.get("tracking", 0) * max(len(glyphs) - 1, 0)

    def path(self, style, text, x, y):
        """SVG path data for text whose baseline starts at (x, y)."""
        _, font = self.instance(style["weight"])
        scale = style["size"] / font["head"].unitsPerEm
        order, glyph_set = font.getGlyphOrder(), font.getGlyphSet()
        pen = SVGPathPen(glyph_set, ntos=lambda v: f"{v:.1f}".rstrip("0").rstrip("."))
        for gid, dx, dy, ax in self.shape(style["weight"], text):
            glyph_set[order[gid]].draw(TransformPen(pen, (scale, 0, 0, -scale, x + dx * scale, y - dy * scale)))
            x += ax * scale + style.get("tracking", 0)
        return pen.getCommands()


def sprite_rects(sprite, frame, pixel, center_x, center_y):
    """The sprite frame as crisp rects, merging runs of one colour, centred on (center_x, center_y)."""
    rows = sprite["frames"][frame]
    left, top = center_x - sprite["width"] * pixel / 2, center_y - sprite["height"] * pixel / 2
    rects = []
    for r, row in enumerate(rows):
        c = 0
        while c < len(row):
            end = c + 1
            while end < len(row) and row[end] == row[c]:
                end += 1
            if row[c] != ".":
                rects.append(f'<rect x="{left + c * pixel:g}" y="{top + r * pixel:g}" width="{(end - c) * pixel:g}" '
                             f'height="{pixel:g}" fill="{sprite["palette"][row[c]]}"/>')
            c = end
    return left, top, "".join(rects)


def render(config, sprite, face):
    og, colors, type_ = config["ogImage"], config["ogImage"]["colors"], config["ogImage"]["type"]
    width, height, layout, beaver = og["width"], og["height"], og["layout"], og["beaver"]
    body = [f'<rect width="{width}" height="{height}" fill="{colors["paper"]}"/>']

    step = og["gridSize"]
    lines = [f"M{x} 0V{height}" for x in range(step, width, step)] + [f"M0 {y}H{width}" for y in range(step, height, step)]
    body.append(f'<path stroke="{colors["grid"]}" stroke-width="1.5" fill="none" d="{"".join(lines)}"/>')

    pixel = beaver["pixel"]
    left, top, rects = sprite_rects(sprite, beaver["frame"], pixel, beaver["centerX"], beaver["centerY"])
    shadow_width = sprite["width"] * pixel * beaver["shadowWidth"]
    body.append(f'<rect x="{beaver["centerX"] - shadow_width / 2:g}" y="{top + sprite["height"] * pixel + pixel * 0.4:g}" '
                f'width="{shadow_width:g}" height="{pixel:g}" fill="{colors["shadow"]}"/>')
    body.append(f'<g shape-rendering="crispEdges">{rects}</g>')
    for dx, dy, size, color in beaver["chips"]:
        body.append(f'<rect x="{beaver["centerX"] + dx}" y="{beaver["centerY"] + dy}" width="{size}" height="{size}" fill="{color}"/>')

    kicker, title, tagline, foot = type_["kicker"], type_["title"], type_["tagline"], type_["foot"]
    x = layout["left"]
    cap = title["size"] * layout["capHeight"]
    block = kicker["size"] + layout["kickerGap"] + cap + layout["titleGap"] + tagline["lineHeight"] * len(og["tagline"])
    y = (height - block) / 2 + kicker["size"]
    dot = kicker["size"] * 0.55
    body.append(f'<rect x="{x}" y="{y - kicker["size"] * 0.68:g}" width="{dot:g}" height="{dot:g}" fill="{colors["accent"]}"/>')
    body.append(f'<path fill="{colors["accent"]}" d="{face.path(kicker, og["kicker"], x + dot + 14, y)}"/>')

    y += layout["kickerGap"] + cap
    body.append(f'<path fill="{colors["ink"]}" d="{face.path(title, og["title"], x - title["size"] * 0.04, y)}"/>')

    y += layout["titleGap"]
    for line in og["tagline"]:
        y += tagline["lineHeight"]
        if og["mark"] in line:
            start = x + face.width(tagline, line[: line.index(og["mark"])])
            pad = tagline["size"] * 0.12
            body.append(f'<rect x="{start - pad:g}" y="{y - tagline["size"] * 0.24:g}" '
                        f'width="{face.width(tagline, og["mark"]) + 2 * pad:g}" height="{tagline["size"] * 0.38:g}" '
                        f'rx="3" fill="{colors["marker"]}"/>')
        body.append(f'<path fill="{colors["ink2"]}" d="{face.path(tagline, line, x, y)}"/>')

    body.append(f'<path fill="{colors["muted"]}" d="{face.path(foot, og["foot"], x, height - layout["footBottom"])}"/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">{"".join(body)}</svg>'


def main():
    config = json.loads((SITE_DIR / "site.json").read_text())
    sprite = json.loads((SITE_DIR / config["sprites"]["beaver"]).read_text())
    og = config["ogImage"]
    with tempfile.TemporaryDirectory() as cache_dir:
        svg = render(config, sprite, Typeface((SITE_DIR / og["font"]).resolve(), cache_dir))
    out = SITE_DIR / og["file"]
    subprocess.run(["rsvg-convert", "--format=png", f"--output={out}"], input=svg.encode(), check=True)
    print(f"Drew {out.relative_to(SITE_DIR.parent)} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
