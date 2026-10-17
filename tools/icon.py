"""Writes the app icon as Android vector drawables from the keyboard's own key
outlines: three hexagon keys of the space-at-the-bottom layout, one over two,
merged into one rounded outline with seams where the keys meet, drawn in a
blue, green and purple flow, with e on top and h and x below leaning towards
the middle, each with its baseline along the seam beside it; and the same outline without letters as the
monochrome layer Android tints for themed icons.

Usage:
  cargo run -q --release --example outlines > /tmp/outlines.tsv
  nix shell --impure --expr 'with import (builtins.getFlake "nixpkgs") {}; python3.withPackages (p: [ p.shapely p.fonttools ])' \\
    -c python3 -I tools/icon.py /tmp/outlines.tsv FONT.ttf
with FONT.ttf Roboto Regular. Writes res/drawable/ic_launcher_foreground.xml,
res/drawable/ic_launcher_monochrome.xml, res/mipmap-anydpi/ic_launcher.xml and
the background colour in res/values/ic_launcher.xml.
"""

import math
import pathlib
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from shapely.geometry import Polygon
from shapely.ops import unary_union

RES = pathlib.Path(__file__).resolve().parent.parent / "android/app/src/main/res"

# The keys the icon is made of, by their label in the layout, the letter each
# shows, and how far that letter turns clockwise, in degrees: the seams
# between the top key and the two below run 30 degrees from level.
KEYS = [("r", "e", 0), ("d", "h", 30), ("f", "x", -30)]
BACKGROUND = "#FF1B1B1F"
FLOW = ["#FF5F72D9", "#FF3FA393", "#FFA465B8"]

# Adaptive icons are 108 units across, of which a launcher's mask shows the
# middle 72 at most and a circle of 66 for sure; the keys take 60 % of the 72.
VIEW = 108
SIZE = 72 * 0.6

# In key widths: the outline's corner radius and stroke, the seams' stroke,
# and the letters' size.
CORNER, OUTLINE, SEAM, LETTER = 0.25, 0.1, 0.06, 0.5


def outlines(path: pathlib.Path) -> tuple[float, dict[str, Polygon]]:
    lines = path.read_text().splitlines()
    shapes = {}
    for line in lines[1:]:
        label, corners = line.split("\t")
        shapes.setdefault(label, Polygon([tuple(map(float, c.split(","))) for c in corners.split()]))
    return float(lines[0].split()[0]), shapes


def path_data(coords, scale, ox, oy, close=True) -> str:
    points = [f"{x * scale + ox:.2f},{y * scale + oy:.2f}" for x, y in coords]
    return "M" + " L".join(points) + (" Z" if close else "")


def glyph(font: TTFont, letter: str, cx: float, cy: float, size: float, angle: float) -> str:
    """The letter's outline centred on cx with its x-height centred on cy,
    turned clockwise by `angle` degrees about that centre."""
    glyphs = font.getGlyphSet()
    name = font.getBestCmap()[ord(letter)]
    scale = size / font["head"].unitsPerEm
    width = glyphs[name].width * scale
    x_height = font["OS/2"].sxHeight * scale
    pen = SVGPathPen(glyphs)
    turn = math.radians(angle)
    cos, sin = math.cos(turn), math.sin(turn)
    # Font units to the centre, flipped to y down, then turned about the centre.
    dx, dy = -width / 2, x_height / 2
    glyphs[name].draw(TransformPen(pen, (
        scale * cos, scale * sin, scale * sin, -scale * cos,
        cx + dx * cos - dy * sin, cy + dx * sin + dy * cos,
    )))
    return pen.getCommands()


def gradient(x1, y1, x2, y2) -> str:
    items = "".join(
        f'\n                <item android:offset="{offset}" android:color="{color}" />'
        for offset, color in zip(("0", "0.55", "1"), FLOW)
    )
    return (
        f'<gradient android:type="linear" android:startX="{x1:.2f}" android:startY="{y1:.2f}" '
        f'android:endX="{x2:.2f}" android:endY="{y2:.2f}">{items}\n            </gradient>'
    )


def drawable(paths: list[str]) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Written by tools/icon.py. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        f'    android:width="{VIEW}dp" android:height="{VIEW}dp"\n'
        f'    android:viewportWidth="{VIEW}" android:viewportHeight="{VIEW}">\n'
        + "".join(paths)
        + "</vector>\n"
    )


def stroked(data: str, width: float, color: str | None, flow: str) -> str:
    paint = f'android:strokeColor="{color}"' if color else ""
    inner = "" if color else f'\n        <aapt:attr name="android:strokeColor">\n            {flow}\n        </aapt:attr>'
    return (
        f'    <path android:pathData="{data}" android:strokeWidth="{width:.2f}" {paint}\n'
        f'        android:strokeLineJoin="round" android:strokeLineCap="round">{inner}\n    </path>\n'
    )


def filled(data: str, flow: str) -> str:
    return (
        f'    <path android:pathData="{data}">\n'
        f'        <aapt:attr name="android:fillColor">\n            {flow}\n        </aapt:attr>\n    </path>\n'
    )


def main() -> None:
    cell, shapes = outlines(pathlib.Path(sys.argv[1]))
    font = TTFont(sys.argv[2])
    polys = [shapes[key] for key, _, _ in KEYS]
    whole = unary_union(polys)
    minx, miny, maxx, maxy = whole.bounds
    scale = SIZE / max(maxx - minx, maxy - miny)
    ox = VIEW / 2 - (minx + maxx) / 2 * scale
    oy = VIEW / 2 - (miny + maxy) / 2 * scale
    radius = cell * CORNER
    rounded = whole.buffer(1).buffer(-(1 + radius), join_style=2).buffer(radius, join_style=1, resolution=16)
    outline = path_data(rounded.exterior.coords, scale, ox, oy)
    seams = []
    for i in range(len(polys)):
        for j in range(i + 1, len(polys)):
            shared = polys[i].boundary.intersection(polys[j].boundary)
            for line in getattr(shared, "geoms", [shared]):
                if line.length > 1:
                    seams.append(path_data(line.coords, scale, ox, oy, close=False))
    flow = gradient(minx * scale + ox, miny * scale + oy, maxx * scale + ox, maxy * scale + oy)
    letters = [glyph(font, text, poly.centroid.x * scale + ox, poly.centroid.y * scale + oy, cell * LETTER * scale, angle)
               for (_, text, angle), poly in zip(KEYS, polys)]

    colour = [stroked(outline, cell * OUTLINE * scale, None, flow)]
    colour += [stroked(seam, cell * SEAM * scale, None, flow) for seam in seams]
    colour += [filled(letter, flow) for letter in letters]
    (RES / "drawable/ic_launcher_foreground.xml").write_text(drawable(colour))
    mono = [stroked(outline, cell * OUTLINE * scale, "#FFFFFFFF", flow)]
    mono += [stroked(seam, cell * SEAM * scale, "#FFFFFFFF", flow) for seam in seams]
    (RES / "drawable/ic_launcher_monochrome.xml").write_text(drawable(mono))
    (RES / "mipmap-anydpi").mkdir(exist_ok=True)
    (RES / "mipmap-anydpi/ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Written by tools/icon.py. -->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    (RES / "values").mkdir(exist_ok=True)
    (RES / "values/ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Written by tools/icon.py. -->\n"
        f'<resources>\n    <color name="ic_launcher_background">{BACKGROUND}</color>\n</resources>\n'
    )


if __name__ == "__main__":
    main()
