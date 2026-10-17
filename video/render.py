"""Renders the trailer's picture, frame by frame, cut to the music's beat map.

Usage: video/build.sh, which supplies the arguments:
  python3 -I video/render.py OUTLINES BEATS FONTS_DIR OUT.mp4 [--preview] [--from S] [--to S] [--sheet SHEET.png]

The keyboard is drawn from the app's own key outlines (examples/outlines.rs)
in the app's own colours, so what moves on screen is the real layout; letters
are drawn from the fonts' outlines, so every glyph can move on its own. Every
scene is a function of the time in beats, eased, and every hit in the music
lands on a whole or half beat. Frames go to ffmpeg as raw pixels.
"""

import json
import re
import math
import pathlib
import subprocess
import sys

import cairo
import numpy as np
from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from scipy import ndimage
from shapely.geometry import Polygon

W, H = 1920, 1080
FPS = 30

# Colours: near black, the app's Dusk theme for the keyboard, and the icon's
# blue, green and purple as the only accents.
INK = (0.043, 0.043, 0.051)
PAPER = (0.93, 0.93, 0.95)
DIM = (0.42, 0.42, 0.48)
BLUE = (0.373, 0.447, 0.851)
GREEN = (0.247, 0.639, 0.576)
PURPLE = (0.643, 0.396, 0.722)
FLOW = [BLUE, GREEN, PURPLE]
DUSK_BG = (0.106, 0.106, 0.122)
DUSK_ROWS = [(0.180, 0.231, 0.369), (0.137, 0.282, 0.271), (0.294, 0.180, 0.310)]
DUSK_OTHER = (0.227, 0.227, 0.259)
DUSK_LABEL = (0.910, 0.910, 0.933)
QWERTZ = ["qwertzuiop", "asdfghjkl", "yxcvbnm"]


# ---------------------------------------------------------------- easing

def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def out_expo(x):
    x = clamp(x)
    return 1 if x >= 1 else 1 - 2 ** (-10 * x)


def in_expo(x):
    x = clamp(x)
    return 0 if x <= 0 else 2 ** (10 * x - 10)


def in_out_expo(x):
    x = clamp(x)
    if x in (0, 1):
        return x
    return 2 ** (20 * x - 10) / 2 if x < 0.5 else (2 - 2 ** (-20 * x + 10)) / 2


def out_back(x, s=1.7):
    x = clamp(x) - 1
    return 1 + (s + 1) * x ** 3 + s * x ** 2


def out_cubic(x):
    return 1 - (1 - clamp(x)) ** 3


def in_out_cubic(x):
    x = clamp(x)
    return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2


def spring(x, freq=2.2, damp=5.0):
    """A value that overshoots 1 and settles, like a struck spring."""
    x = max(0.0, x)
    return 1 - math.exp(-damp * x) * math.cos(2 * math.pi * freq * x)


def span(t, a, b):
    """Where t is between a and b, 0 to 1."""
    return clamp((t - a) / (b - a)) if b > a else float(t >= a)


def lerp(a, b, x):
    return a + (b - a) * x


def mix(c1, c2, x):
    return tuple(lerp(a, b, x) for a, b in zip(c1, c2))


def flow(x):
    """The icon's colour flow from blue through green to purple, x from 0 to 1."""
    x = clamp(x) * 2
    return mix(BLUE, GREEN, x) if x < 1 else mix(GREEN, PURPLE, x - 1)


# ---------------------------------------------------------------- type

class CairoPen(BasePen):
    def __init__(self, glyphs, ctx):
        super().__init__(glyphs)
        self.ctx = ctx

    def _moveTo(self, p):
        self.ctx.move_to(*p)

    def _lineTo(self, p):
        self.ctx.line_to(*p)

    def _curveToOne(self, a, b, c):
        self.ctx.curve_to(*a, *b, *c)

    def _closePath(self):
        self.ctx.close_path()


class Font:
    """A font drawn from its outlines, one glyph at a time."""

    def __init__(self, path, weight=None):
        font = TTFont(path)
        if "fvar" in font and weight:
            instancer.instantiateVariableFont(font, {"wght": weight}, inplace=True)
        self.glyphs = font.getGlyphSet()
        self.cmap = font.getBestCmap()
        self.em = font["head"].unitsPerEm
        self.cap = getattr(font["OS/2"], "sCapHeight", 0.7 * self.em) or 0.7 * self.em

    def advance(self, ch, size):
        name = self.cmap.get(ord(ch))
        return (self.glyphs[name].width if name else self.em * 0.3) * size / self.em

    def width(self, text, size, tracking=0.0):
        return sum(self.advance(c, size) for c in text) + tracking * size * max(0, len(text) - 1)

    def glyph(self, ctx, ch, size):
        """Adds the glyph's outline to ctx's path at the origin, baseline at y 0."""
        name = self.cmap.get(ord(ch))
        if not name:
            return
        ctx.save()
        k = size / self.em
        ctx.scale(k, -k)
        self.glyphs[name].draw(CairoPen(self.glyphs, ctx))
        ctx.restore()

    def text(self, ctx, text, x, y, size, color, align=0.0, tracking=0.0, alpha=1.0, each=None):
        """Draws text with its baseline at y; align 0 left, 0.5 centred, 1
        right. `each(i, n)` may give a glyph (dx, dy, scale, angle, alpha)."""
        x -= self.width(text, size, tracking) * align
        n = len(text)
        for i, ch in enumerate(text):
            dx, dy, sc, angle, a = each(i, n) if each else (0, 0, 1, 0, 1)
            adv = self.advance(ch, size)
            if a * alpha > 0.001 and sc > 0.001:
                ctx.save()
                ctx.translate(x + dx + adv / 2, y + dy - self.cap * size / self.em / 2)
                ctx.rotate(angle)
                ctx.scale(sc, sc)
                ctx.translate(-adv / 2, self.cap * size / self.em / 2)
                ctx.new_path()
                self.glyph(ctx, ch, size)
                ctx.set_source_rgba(*color, a * alpha)
                ctx.fill()
                ctx.restore()
            x += adv + tracking * size
        return x


# ---------------------------------------------------------------- keyboard

class Keyboard:
    """The space-at-the-bottom layout as the app draws it, from its outlines."""

    def __init__(self, path):
        lines = pathlib.Path(path).read_text().splitlines()
        self.cell, self.cell_h, self.height = map(float, lines[0].split())
        self.width = 0.0
        self.keys = []
        for line in lines[1:]:
            label, corners = line.split("\t")
            pts = [tuple(map(float, c.split(","))) for c in corners.split()]
            poly = Polygon(pts)
            gap, corner = self.cell * 0.035, self.cell * 0.12
            rounded = poly.buffer(-(gap + corner), join_style=2).buffer(corner, join_style=1, resolution=6)
            c = poly.centroid
            self.keys.append({"label": label, "outline": list(rounded.exterior.coords), "centre": (c.x, c.y),
                              "row": next((i for i, r in enumerate(QWERTZ) if len(label) == 1 and label in r), None)})
            self.width = max(self.width, max(p[0] for p in pts))
        self.by_label = {}
        for k in self.keys:
            self.by_label.setdefault(k["label"], k)

    def key(self, label):
        return self.by_label[label]


def polygon(ctx, pts):
    ctx.move_to(*pts[0])
    for p in pts[1:]:
        ctx.line_to(*p)
    ctx.close_path()


def hexagon(ctx, cx, cy, r, angle=0.0):
    for i in range(6):
        a = math.radians(60 * i - 90) + angle
        (ctx.move_to if i == 0 else ctx.line_to)(cx + r * math.cos(a), cy + r * math.sin(a))
    ctx.close_path()


def rounded_hexagon(ctx, cx, cy, r, corner=0.18, angle=0.0):
    pts = [(cx + r * math.cos(math.radians(60 * i - 90) + angle), cy + r * math.sin(math.radians(60 * i - 90) + angle)) for i in range(6)]
    k = corner
    for i in range(6):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % 6]
        a = (lerp(p1[0], p0[0], k), lerp(p1[1], p0[1], k))
        b = (lerp(p1[0], p2[0], k), lerp(p1[1], p2[1], k))
        (ctx.move_to if i == 0 else ctx.line_to)(*a)
        ctx.curve_to(*p1, *p1, *b)
    ctx.close_path()


# ---------------------------------------------------------------- the film

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import eras  # noqa: E402

BOX = (370, 470, 1180)        # where the history's keyboards sit
HEX_BOX = (610, 410, 700)     # where hexkeys lands at the end of the history
MORPH_AT = [8, 16, 24, 32]    # beats where the next era takes over

# How each era looks: background, the keyboard's case, keys, the rings or
# skirts round them, labels; the font, whether labels are capitals, the
# case's corner radius and how far a key stands up from it.
ERAS = [
    dict(bg=(0.86, 0.82, 0.73), case=(0.12, 0.11, 0.10), key=(0.07, 0.065, 0.06), extra=(0.07, 0.065, 0.06),
         ring=(0.80, 0.78, 0.73), label=(0.97, 0.95, 0.90), accent=(0.70, 0.12, 0.10), font="mono", upper=True,
         radius=26, skirt=7, text=(0.18, 0.15, 0.12)),
    dict(bg=(0.13, 0.14, 0.16), case=(0.74, 0.71, 0.64), key=(0.91, 0.89, 0.83), extra=(0.84, 0.81, 0.74),
         ring=None, label=(0.22, 0.21, 0.19), accent=(0.36, 0.62, 0.40), font="sans_bold", upper=True,
         radius=20, skirt=9, text=(0.80, 0.80, 0.78)),
    dict(bg=(0.07, 0.10, 0.18), case=(0.20, 0.22, 0.26), key=(0.45, 0.48, 0.53), extra=(0.30, 0.32, 0.36),
         ring=None, label=(0.95, 0.96, 0.98), accent=(0.55, 0.80, 0.35), font="sans_bold", upper=True,
         radius=70, skirt=4, text=(0.75, 0.80, 0.90)),
    dict(bg=(0.03, 0.03, 0.04), case=(0.78, 0.79, 0.83), key=(0.99, 0.99, 1.0), extra=(0.68, 0.70, 0.75),
         ring=None, label=(0.07, 0.07, 0.09), accent=(0.20, 0.48, 0.98), font="sans", upper=True,
         radius=40, skirt=3, text=(0.80, 0.80, 0.84)),
    dict(bg=INK, case=DUSK_BG, key=None, extra=DUSK_OTHER,
         ring=None, label=DUSK_LABEL, accent=(0.424, 0.482, 0.847), font="sans", upper=False,
         radius=28, skirt=0, text=PAPER),
]

# What each era's screen above the keys is: x, y, w, h, corner radius, colour.
PANELS = [
    (890, 40, 620, 370, 3, (0.985, 0.975, 0.945)),
    (610, 50, 700, 370, 48, (0.035, 0.07, 0.045)),
    (720, 60, 480, 360, 26, (0.60, 0.68, 0.46)),
    (650, 70, 620, 350, 44, (0.975, 0.975, 0.985)),
    (560, 150, 800, 120, 30, (0.13, 0.13, 0.15)),
]

NAMES = ["SHOLES & GLIDDEN TYPE-WRITER", "BUCKLING SPRING", "PHONE KEYPAD · T9", "TOUCHSCREEN", "HEXKEYS"]
YEARS = ["1873", "1985", "1999", "2007", "2026"]
TRIVIA = [
    ["NO 1 KEY: TYPE A LOWER L", "NO 0 KEY: TYPE A CAPITAL O", "FULL STOP WHERE R IS NOW"],
    ["101 KEYS", "ONE SPRING UNDER EVERY KEY", "IT BUCKLES, THEN IT CLICKS"],
    ["T9: TEXT ON 9 KEYS", "4663 = GOOD HOME GONE HOOD HOOF", "439 = HEY, THEN HEX"],
    ["NO KEYS UNDER THE GLASS", "SLIDE TO UNLOCK", "2010: ONE STROKE PER WORD"],
    [],
]

# Typing in each era, as (beat, key, what it prints).
TYPEWRITER = list(zip([0, 0.75, 1.5, 2, 2.75, 3.5, 4, 4.75, 5.5, 6, 6.75, 7.5], "TYPE WRITER."))
SPRING = list(zip([8.125 + k * 0.5 for k in range(16)], '10 PRINT "HEX"\n '))
KEYPAD = list(zip([16 + e * 0.5 for e in range(13)], "439*04663****"))
T9 = ["i", "he", "hey", "hex", "hex ", "i", "in", "inn", "good", "home", "gone", "hood", "hoof"]
KEYPAD = [(b, k, w) for (b, k), w in zip(KEYPAD, T9)]


# The fact cards of the hexagons act: the key each pops out of, its drawing, two lines.
FACTS = [
    ("b", "bees", "BEES", "WAX WALLS MEET AT 120°"),
    ("h", "hales", "HALES, 1999", "HONEYCOMB CONJECTURE, PROVED"),
    ("s", "saturn", "SATURN'S NORTH POLE", "~14,500 KM A SIDE"),
    ("c", "causeway", "GIANT'S CAUSEWAY", "~40,000 BASALT COLUMNS"),
    ("g", "graphene", "GRAPHENE", "C–C 0.142 NM"),
    ("k", "benzene", "KEKULÉ, 1865", "BENZENE, C6H6"),
    ("i", "snow", "ICE Ih", "SIX-FOLD SNOW"),
    ("x", "hex", "48 45 58", "= H E X"),
]
CARD_R = 160
CARD_AT = [(1070, 300), (1347, 300), (1624, 300), (1208, 540), (1485, 540), (1762, 540), (1070, 780), (1347, 780)]

# The strip the app's own strip() gives while "bestagons" is typed after
# "hexagons are the " (examples/suggest.rs with =TEXT), one per letter;
# "bestago" keeps the one before it.
STRIP_BESTAGONS = [("b", "by", "but"), ("he", "be", "been"), ("new", "bes", "best"), ("beat", "best", "west"),
                   ("besta", "best", "beta"), ("bestag", "gestation", "bests"), ("bestag", "gestation", "bests"),
                   ("bestagon", "Pentagon", "gestation"), ("bestagons", "pentagons", "hexagons")]
# When the typed word, on the left, is tapped to keep it.
KEEP_AT = 45.5


def in_back(x, s=1.7):
    x = clamp(x)
    return (s + 1) * x ** 3 - s * x ** 2


def key_of(ch):
    """The key a printed character is struck with."""
    if ch == " ":
        return "space"
    return ch.lower()


def bbox(points):
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return min(xs), min(ys), max(xs), max(ys)


def rounded_rect_path(ctx, x, y, w, h, r):
    r = max(0.0, min(r, w / 2, h / 2))
    ctx.new_sub_path()
    ctx.arc(x + w - r, y + r, r, -math.pi / 2, 0)
    ctx.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
    ctx.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
    ctx.arc(x + r, y + r, r, math.pi, 3 * math.pi / 2)
    ctx.close_path()


def luminance(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def turn(points, cx, cy, angle, scale, dx=0.0, dy=0.0):
    c, s = math.cos(angle), math.sin(angle)
    return [(cx + dx + ((x - cx) * c - (y - cy) * s) * scale, cy + dy + ((x - cx) * s + (y - cy) * c) * scale) for x, y in points]


def smooth_path(points, per=16):
    """A Catmull-Rom curve through the points."""
    if len(points) < 2:
        return list(points)
    pts = [points[0]] + list(points) + [points[-1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for k in range(per):
            t = k / per
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * (2 * p1[j] + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t2
                                    + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t3) for j in range(2)))
    out.append(points[-1])
    return out


def along(path, x):
    """The point x of the way along a polyline, 0 to 1, and the part up to it."""
    if x <= 0:
        return path[0], path[:1]
    lengths = [math.dist(path[i], path[i + 1]) for i in range(len(path) - 1)]
    total = sum(lengths) or 1
    d = total * clamp(x)
    for i, length in enumerate(lengths):
        if d <= length:
            f = d / length if length else 0
            p = (lerp(path[i][0], path[i + 1][0], f), lerp(path[i][1], path[i + 1][1], f))
            return p, path[:i + 1] + [p]
        d -= length
    return path[-1], path


class Film:
    def __init__(self, outlines, beats, fonts):
        self.kb = Keyboard(outlines)
        beat_map = json.loads(pathlib.Path(beats).read_text())
        self.beat = beat_map["beat"]
        self.sections = {s["name"]: s["bar"] * 4 for s in beat_map["sections"]}
        self.length = (beat_map["sections"][-1]["bar"] + 2) * 4 * self.beat + 1.5
        fonts = pathlib.Path(fonts)
        self.heavy = Font(fonts / "Nunito.ttf", 900)
        self.bold = Font(fonts / "Nunito.ttf", 800)
        self.sans = Font(fonts / "Inter.ttf", 500)
        self.sans_bold = Font(fonts / "Inter.ttf", 750)
        self.mono = Font(fonts / "Mono.otf")
        self.emoji = Font(fonts / "Emoji.ttf", 600)
        source = (pathlib.Path(__file__).resolve().parent.parent / "src/lib.rs").read_text().splitlines()
        first = next(i for i, line in enumerate(source) if "const FLICK" in line) - 4
        self.source_lines = source[first:first + 80]
        self.rng = np.random.default_rng(7)
        self.grain = [self.rng.normal(0, 1, (H // 2, W // 2)).astype(np.float32) for _ in range(6)]
        self.eras = [eras.typewriter(BOX), eras.buckling(BOX), eras.keypad(BOX), eras.touch(BOX), eras.hexkeys(self.kb, HEX_BOX)]
        for era in self.eras:
            pts = [p for o in era.keys.values() for p in o] + [p for _, o in era.extras for p in o]
            era.bbox = bbox(pts)
        # Every letter the keys lack and the plain letters it is written with (src/letters.rs).
        letters = (pathlib.Path(__file__).resolve().parent.parent / "src/letters.rs").read_text()
        self.letters = [(chr(int(code, 16)), base) for code, base in re.findall(r"\('\\u\{([0-9a-f]+)\}', \"([a-z]+)\"\)", letters)]
        self.letter_count = len(self.letters)
        self.letters = [(ch, base[0]) for ch, base in self.letters if len(base) == 1]
        # Each hexkeys letter's Dusk colour.
        self.hex_colour = {k["label"]: DUSK_ROWS[k["row"]] for k in self.kb.keys if k["row"] is not None}

    def font(self, name):
        return getattr(self, name)

    # ------------------------------------------------ shared pieces

    def hud(self, ctx, b, colour=DIM, alpha=1.0):
        """Corner marks, a bar counter and a time code, ticking with the music."""
        ctx.set_line_width(1.5)
        ctx.set_source_rgba(*colour, 0.55 * alpha)
        m = 44
        for (x, y, sx, sy) in ((m, m, 1, 1), (W - m, m, -1, 1), (m, H - m, 1, -1), (W - m, H - m, -1, -1)):
            ctx.move_to(x, y + sy * 26)
            ctx.line_to(x, y)
            ctx.line_to(x + sx * 26, y)
            ctx.stroke()
        bar, beat = int(b // 4) + 1, int(b % 4) + 1
        self.mono.text(ctx, f"BAR {bar:02d}.{beat}", m + 38, m + 19, 17, colour, alpha=0.8 * alpha)
        seconds = b * self.beat
        self.mono.text(ctx, f"{int(seconds // 60):02d}:{seconds % 60:05.2f}", W - m - 38, m + 19, 17, colour, align=1, alpha=0.8 * alpha)
        # Sixteen beat ticks along the top, the current one lit.
        for i in range(16):
            x = W / 2 - 8 * 14 + i * 14
            on = int(b) % 16 == i
            ctx.set_source_rgba(*(flow(i / 15) if on else colour), (0.95 if on else 0.3) * alpha)
            ctx.rectangle(x, m + 6 + (0 if on else 4), 6, 14 if on else 6)
            ctx.fill()

    def finger(self, ctx, px, py, pressed, alpha=1.0, r=26, colour=PAPER):
        ctx.set_source_rgba(*colour, 0.16 * alpha)
        ctx.arc(px, py, r * (1.6 + 0.4 * pressed), 0, 2 * math.pi)
        ctx.fill()
        ctx.set_source_rgba(*colour, 0.9 * alpha)
        ctx.arc(px, py, r * (0.5 + 0.15 * pressed), 0, 2 * math.pi)
        ctx.fill()

    def trail(self, ctx, points, colour, width, alpha=1.0, fade=True):
        if len(points) < 2:
            return
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        ctx.set_line_join(cairo.LINE_JOIN_ROUND)
        n = len(points)
        for glow, w_mul in ((0.18, 3.2), (1.0, 1.0)):
            for i in range(1, n):
                f = i / (n - 1) if fade else 1
                c = colour(i / (n - 1)) if callable(colour) else colour
                ctx.set_source_rgba(*c, alpha * glow * (0.25 + 0.75 * f))
                ctx.set_line_width(width * w_mul * (0.35 + 0.65 * f))
                ctx.move_to(*points[i - 1])
                ctx.line_to(*points[i])
                ctx.stroke()

    def slam(self, ctx, font, text, x, y, size, b, start, colour, align=0.0, stagger=0.04, dur=0.6, exit_at=None, tracking=0.0, alpha=1.0):
        """Text rising glyph by glyph from behind a mask line, and out again upwards."""
        def each(i, n):
            p = out_expo(span(b, start + i * stagger, start + i * stagger + dur))
            q = in_expo(span(b, exit_at + i * stagger * 0.5, exit_at + i * stagger * 0.5 + 0.4)) if exit_at is not None else 0
            return (0, (1 - p) * size * 1.1 - q * size * 1.1, 1, 0, 1 if 0 < p and q < 1 else 0)
        ctx.save()
        ctx.rectangle(0, y - size * 1.0, W, size * 1.28)
        ctx.clip()
        font.text(ctx, text, x, y, size, colour, align=align, tracking=tracking, each=each, alpha=alpha)
        ctx.restore()

    def typed(self, ctx, font, text, x, y, size, b, start, colour, rate=0.125, align=0.0, alpha=1.0, cursor=None):
        """Text appearing a character every `rate` beats, with a block cursor."""
        n = int(clamp((b - start) / rate, 0, len(text)))
        if b < start:
            return
        x0 = x - font.width(text, size) * align
        end = font.text(ctx, text[:n], x0, y, size, colour, alpha=alpha)
        if cursor and (n < len(text) or int(b * 4) % 2 == 0):
            ctx.set_source_rgba(*cursor, alpha)
            ctx.rectangle(end + 2, y - size * 0.78, size * 0.5, size * 0.95)
            ctx.fill()

    def odometer(self, ctx, digits_from, digits_to, x, y, size, x_p, colour, alpha=1.0):
        """A year rolling digit by digit, right to left, like a counter."""
        font = self.heavy
        ctx.save()
        ctx.rectangle(x - 10, y - size * 0.8, W, size * 0.86)
        ctx.clip()
        cx = x
        for i, (a, c) in enumerate(zip(digits_from, digits_to)):
            p = in_out_cubic(span(x_p, 0.25 * (len(digits_to) - 1 - i) / len(digits_to), 0.25 * (len(digits_to) - 1 - i) / len(digits_to) + 0.75))
            adv = font.advance("0", size)
            if a == c:
                font.text(ctx, c, cx, y, size, colour, alpha=alpha)
            else:
                # Roll through the digits in between, upwards.
                da, dc = int(a), int(c)
                steps = (dc - da) % 10 or 10
                pos = p * steps
                for k in range(steps + 1):
                    off = (k - pos) * size * 1.05
                    if abs(off) < size:
                        font.text(ctx, str((da + k) % 10), cx, y + off, size, colour, alpha=alpha)
            cx += adv
        ctx.restore()

    # ------------------------------------------------ act 1: history

    def morph_progress(self, i, nx, b):
        """How far the morph from era i to i + 1 has gone for a key at nx, 0 to 1 across."""
        m = MORPH_AT[i]
        if i == 3:
            return in_out_cubic(span(b, m - 1.0 + 0.9 * nx, m - 1.0 + 0.9 * nx + 1.1))
        return in_out_cubic(span(b, m - 0.45 + 0.7 * nx, m - 0.45 + 0.7 * nx + 0.75))

    def era_mix(self, b):
        """The era and how far it has gone into the next, for backgrounds and panels."""
        for i, m in enumerate(MORPH_AT):
            if b < m + 1.0:
                return i, in_out_cubic(span(b, m - 0.45, m + 0.6)) if i < 3 else in_out_cubic(span(b, m - 1.0, m + 0.6))
        return 4, 0.0

    def era_look(self, i, p, field):
        a, c = ERAS[i][field], ERAS[min(4, i + 1)][field]
        if isinstance(a, (int, float)):
            return lerp(a, c, p)
        return mix(a, c, p)

    def pressed(self, events, label, b):
        """How hard a key glows from its last strike, 1 just struck, falling off."""
        q = 0.0
        for t, ch, *_ in events:
            if key_of(ch) == label and t <= b:
                q = max(q, math.exp(-(b - t) * 7))
        return q

    def history_events(self, b):
        return TYPEWRITER + SPRING + [(t, k) for t, k, _ in KEYPAD]

    def history_keys(self, ctx, b, events, alpha=1.0, hide=None):
        """Every letter key at beat b, morphing from era to era, and each
        era's other keys appearing and leaving around them."""
        eras_ = self.eras
        x0, _, bw = BOX
        era, ep = self.era_mix(b)
        # The case behind the keys.
        ca, cb = eras_[era].bbox, eras_[min(4, era + 1)].bbox
        box = [lerp(u, v, ep) for u, v in zip(ca, cb)]
        pad = lerp(34, 26, ep)
        ctx.set_source_rgba(*self.era_look(era, ep, "case"), alpha)
        rounded_rect_path(ctx, box[0] - pad, box[1] - pad, box[2] - box[0] + 2 * pad, box[3] - box[1] + 2 * pad, self.era_look(era, ep, "radius"))
        ctx.fill()
        # Other keys, shrinking away as the morph starts and popping in after it.
        for i, e in enumerate(eras_):
            if i < 4:
                gone = out_cubic(span(b, MORPH_AT[i] - 0.5, MORPH_AT[i] - 0.05))
            else:
                gone = 0
            come = out_back(span(b, MORPH_AT[i - 1] + 0.35, MORPH_AT[i - 1] + 0.95), 2.0) if i > 0 else 1
            if i > 0 and b < MORPH_AT[i - 1] + 0.35:
                continue
            s = (1 - gone) * come
            if s <= 0.01:
                continue
            look = ERAS[i]
            for label, outline in e.extras:
                cx, cy = eras.centre(outline)
                q = self.pressed(events, label, b) if i < 4 else 0
                shape = turn(outline, cx, cy, 0, max(0.01, s * (1 - 0.06 * q)), 0, look["skirt"] * q * 0.6)
                self.key_shape(ctx, shape, look, look["extra"], q, alpha, i)
                text = label if label != "space" else ""
                if i == 2 and label.isdigit() is False and label not in "*#":
                    text = ""
                size = (36 if i == 2 else 24 if len(text) == 1 else 18) * s
                if i == 4:
                    text = {"⏎": "↵", "space": ""}.get(label, label)
                    size = (0.36 if len(text) == 1 else 0.22) * self.kb.cell * HEX_BOX[2] / self.kb.width * s
                if text:
                    ky = cy + look["skirt"] * q * 0.6
                    self.font(look["font"]).text(ctx, text, cx, ky + size * 0.36 - (14 if i == 2 else 0), size, look["label"], align=0.5, alpha=alpha)
        # The letters.
        for letter in eras.LETTERS:
            if hide and letter in hide:
                continue
            outline = eras_[0].keys[letter]
            nx = (eras.centre(outline)[0] - x0) / bw
            shape, look_i, p = None, 4, 0
            for i in range(4):
                p = self.morph_progress(i, nx, b)
                if p < 1:
                    shape = eras.morph(eras_[i].keys[letter], eras_[i + 1].keys[letter], p)
                    look_i = i
                    break
            if shape is None:
                shape, look_i, p = eras_[4].keys[letter], 4, 0
            cx, cy = eras.centre(shape)
            jump = math.sin(math.pi * p)
            spin = jump * 0.55 * (1 if letter in "aeiouybdfhkmpt" else -1)
            q = self.pressed(events, letter, b)
            la, lc = ERAS[look_i], ERAS[min(4, look_i + 1)]
            skirt = lerp(la["skirt"], lc["skirt"], p)
            shape = turn(shape, cx, cy, spin, (1 + 0.22 * jump) * (1 - 0.07 * q), 0, -46 * jump + skirt * q * 0.6)
            cy += -46 * jump + skirt * q * 0.6
            colour_a = la["key"] if la["key"] else self.hex_colour[letter]
            colour_c = lc["key"] if lc["key"] else self.hex_colour[letter]
            look = dict(la, skirt=skirt, ring=la["ring"] if p < 0.5 else lc["ring"], accent=mix(la["accent"], lc["accent"], p))
            self.key_shape(ctx, shape, look, mix(colour_a, colour_c, p), q, alpha, look_i, ring_alpha=abs(1 - 2 * p))
            # Its label, the old era's fading into the new one's.
            x1, y1, x2, y2 = bbox(shape)
            size = min(x2 - x1, y2 - y1) * lerp(self.eras[look_i].look["size"], self.eras[min(4, look_i + 1)].look["size"], p)
            for look_k, a_k in ((la, 1 - p), (lc, p)):
                if a_k <= 0.02:
                    continue
                text = letter.upper() if look_k["upper"] else letter
                font = self.font(look_k["font"])
                ctx.save()
                ctx.translate(cx, cy)
                ctx.rotate(spin)
                font.text(ctx, text, 0, size * 0.36, size, mix(la["label"], lc["label"], p), align=0.5, alpha=alpha * a_k)
                ctx.restore()

    def key_shape(self, ctx, shape, look, colour, q, alpha, era, ring_alpha=1.0):
        if look["skirt"] > 0.5:
            polygon(ctx, [(x, y + look["skirt"] * (1 - 0.6 * q)) for x, y in shape])
            ctx.set_source_rgba(*mix(colour, (0, 0, 0), 0.45), alpha)
            ctx.fill()
        polygon(ctx, shape)
        ctx.set_source_rgba(*mix(colour, look["accent"], 0.55 * q), alpha)
        ctx.fill_preserve()
        if look["ring"] and ring_alpha > 0.02:
            ctx.set_line_width(5)
            ctx.set_source_rgba(*look["ring"], alpha * ring_alpha)
            ctx.stroke()
        else:
            ctx.new_path()

    def panel(self, ctx, b):
        era, ep = self.era_mix(b)
        a, c = PANELS[era], PANELS[min(4, era + 1)]
        x, y, w, h, r = (lerp(u, v, ep) for u, v in zip(a[:5], c[:5]))
        colour = mix(a[5], c[5], ep)
        if era == 0:
            # The paper rides on the carriage.
            x += self.carriage(b) * (1 - ep)
        ctx.save()
        if era == 0:
            ctx.set_source_rgba(0, 0, 0, 0.18)
            rounded_rect_path(ctx, x + 6, y + 8, w, h, r)
            ctx.fill()
        rounded_rect_path(ctx, x, y, w, h, r)
        ctx.set_source_rgb(*colour)
        ctx.fill_preserve()
        ctx.clip()
        if era == 0:
            self.paper(ctx, b, x, y, w, h, 1 - span(ep, 0, 0.3))
        if era == 1 or (era == 0 and ep > 0.6):
            self.crt(ctx, b, x, y, w, h, span(b, 8.2, 8.6) * (1 - span(b, 15.4, 15.6)))
        if era == 2 or (era == 1 and ep > 0.6):
            self.lcd(ctx, b, x, y, w, h, span(b, 16.2, 16.6) * (1 - span(b, 23.4, 23.6)))
        if era == 3 or (era == 2 and ep > 0.6):
            self.glass(ctx, b, x, y, w, h, span(b, 24.2, 24.6) * (1 - span(b, 31.0, 31.4)))
        ctx.restore()

    def carriage(self, b):
        """How far the typewriter's carriage has moved the paper, in pixels:
        one step left per character, and back on the return."""
        n = sum(out_expo(span(b, t, t + 0.12)) for t, _ in TYPEWRITER)
        back = in_out_cubic(span(b, 7.6, 8.0))
        return -n * self.mono.advance("M", 30) * (1 - back)

    def paper(self, ctx, b, x, y, w, h, alpha):
        """The 1873 page: the sentence Sholes's machine was first tested with
        already on it, the new line typed in capitals, slightly off the line
        as typebars strike, and the bell before the margin."""
        ink = (0.12, 0.11, 0.13)
        rng = np.random.default_rng(3)
        lines = ["NOW IS THE TIME FOR ALL GOOD", "MEN TO COME TO THE AID OF", "THE PARTY."]
        up = in_out_cubic(span(b, 7.6, 8.0)) * 46
        for k, line in enumerate(lines):
            ly = y + 70 + k * 46 - up
            cx = x + 70
            for ch in line:
                j = rng.normal(0, 1.2)
                self.mono.text(ctx, ch, cx, ly + j, 30, ink, alpha=alpha * (0.55 + 0.25 * rng.random()))
                cx += self.mono.advance(ch, 30)
        cx = x + 70
        ly = y + 70 + 3 * 46 + 20 - up
        for t, ch in TYPEWRITER:
            if b < t:
                break
            j = rng.normal(0, 1.6)
            a = rng.normal(0, 0.03)
            ctx.save()
            ctx.translate(cx, ly + j)
            ctx.rotate(a)
            self.mono.text(ctx, ch, 0, 0, 30, ink, alpha=alpha * (0.75 + 0.25 * rng.random()))
            ctx.restore()
            cx += self.mono.advance(ch, 30)
        # The bell, before the margin.
        if 7 <= b < 8:
            k = span(b, 7, 8)
            self.heavy.text(ctx, "DING!", x + w - 60, y + h - 40 - 20 * out_back(k), 46, ERAS[0]["accent"], align=1, alpha=alpha * (1 - k) * out_expo(k * 6))

    def typebars(self, ctx, b):
        """Each strike's typebar swinging up from the basket to the platen."""
        for t, ch in TYPEWRITER:
            k = span(b, t - 0.05, t + 0.18)
            if 0 < k < 1:
                up = math.sin(math.pi * k)
                sx = 960 + (hash(ch) % 7 - 3) * 60
                ctx.set_line_width(6)
                ctx.set_line_cap(cairo.LINE_CAP_ROUND)
                ctx.set_source_rgba(0.15, 0.14, 0.13, 0.9)
                ctx.move_to(sx, 460)
                ctx.line_to(lerp(sx, 960, up), lerp(460, 262, up))
                ctx.stroke()

    def crt(self, ctx, b, x, y, w, h, alpha):
        """A green screen: a BASIC program that prints HEX forever."""
        if alpha <= 0:
            return
        green = (0.45, 1.0, 0.55)
        size = 30
        lx, ly = x + 50, y + 70
        typed = "".join(ch for t, ch in SPRING if t <= b)
        lines = ["READY.", "20 GOTO 10"] + typed.split("\n")
        run = b >= 15.6
        if run:
            lines[-1] = "RUN"
            flood = int((b - 15.6) * 24)
            lines += ["HEX"] * flood
        lines = lines[-8:]
        for k, line in enumerate(lines):
            self.mono.text(ctx, line, lx, ly + k * 40, size, green, alpha=alpha)
        if not run and int(b * 4) % 2 == 0:
            end = lx + self.mono.width(lines[-1], size)
            ctx.set_source_rgba(*green, alpha)
            ctx.rectangle(end + 2, ly + (len(lines) - 1) * 40 - size * 0.8, size * 0.6, size * 0.95)
            ctx.fill()
        # Scanlines.
        ctx.set_source_rgba(0, 0, 0, 0.35 * alpha)
        for sy in range(int(y), int(y + h), 4):
            ctx.rectangle(x, sy, w, 1.6)
        ctx.fill()

    def pixels(self, ctx, text, x, y, pixel, colour, alpha=1.0, size=12):
        """Text in the blocky pixels of a monochrome phone display."""
        if not text:
            return 0
        width = int(self.mono.width(text, size)) + 4
        surf = cairo.ImageSurface(cairo.FORMAT_A8, width, size + 4)
        c = cairo.Context(surf)
        c.set_antialias(cairo.ANTIALIAS_NONE)
        self.mono.text(c, text, 1, size, size, (0, 0, 0))
        ctx.save()
        ctx.translate(x, y - (size + 1) * pixel)
        ctx.scale(pixel, pixel)
        pattern = cairo.SurfacePattern(surf)
        pattern.set_filter(cairo.FILTER_NEAREST)
        ctx.set_source_rgba(*colour, alpha)
        ctx.mask(pattern)
        ctx.restore()
        return width * pixel

    def lcd(self, ctx, b, x, y, w, h, alpha):
        """A 1999 phone display: signal and battery, T9 guessing the word as
        the keys go in, a snake crossing the bottom."""
        if alpha <= 0:
            return
        dark = (0.10, 0.14, 0.08)
        # Signal bars and battery.
        for k in range(4):
            ctx.set_source_rgba(*dark, alpha)
            ctx.rectangle(x + 40 + k * 12, y + 70 - k * 9, 8, 10 + k * 9)
            ctx.fill()
        for k in range(3):
            ctx.rectangle(x + w - 60, y + 70 - k * 13, 22, 9)
        ctx.fill()
        self.pixels(ctx, "Abc", x + w - 150, y + 72, 4, dark, alpha)
        done = [(t, k, word) for t, k, word in KEYPAD if t <= b]
        shown = done[-1][2] if done else ""
        committed = "hex " if b >= 18 else ""
        current = shown if shown != "hex " else ""
        if b >= 18:
            current = shown if done and done[-1][0] >= 18.5 else ""
        px = 5
        wx = x + 40
        wy = y + 180
        wx += self.pixels(ctx, committed, wx, wy, px, dark, alpha)
        if current:
            width = self.pixels(ctx, current, wx, wy, px, dark, alpha)
            ctx.set_source_rgba(*dark, alpha)
            ctx.rectangle(wx, wy + 6, width, 6)
            ctx.fill()
        # T9's alternatives for 4663.
        if b >= 20.5:
            alts = ["good", "home", "gone", "hood", "hoof"]
            pick = alts.index(current) if current in alts else 0
            for k, word in enumerate(alts):
                self.pixels(ctx, ("> " if k == pick else "  ") + word, x + 40, y + 250 + k * 0, 3, dark, alpha * (1 if k == pick else 0))
            self.pixels(ctx, " ".join(alts), x + 40, y + 250, 3, dark, alpha * 0.55)
        # The snake.
        if b >= 22:
            k = (b - 22) * 6
            for seg in range(8):
                sx = x + 30 + ((k - seg * 0.5) * 22) % (w - 60)
                ctx.set_source_rgba(*dark, alpha)
                ctx.rectangle(sx, y + h - 50, 18, 18)
                ctx.fill()
            ctx.rectangle(x + w - 80, y + h - 50, 12, 12)
            ctx.fill()

    def glass(self, ctx, b, x, y, w, h, alpha):
        """2007's lock screen, slid open, and 2010's swipe writing hex."""
        if alpha <= 0:
            return
        dark = (0.07, 0.07, 0.09)
        slide = in_out_cubic(span(b, 25.5, 26.6))
        unlocked = span(b, 26.6, 27.0)
        # Time and slider.
        a1 = alpha * (1 - unlocked)
        if a1 > 0:
            self.sans.text(ctx, "9:42", x + w / 2, y + 140, 92, dark, align=0.5, alpha=a1)
            self.sans.text(ctx, "Tuesday, January 9", x + w / 2, y + 180, 22, dark, align=0.5, alpha=a1 * 0.7)
            tx, ty, tw, th = x + 60, y + h - 110, w - 120, 70
            ctx.set_source_rgba(0.85, 0.85, 0.88, a1)
            rounded_rect_path(ctx, tx, ty, tw, th, 16)
            ctx.fill()
            # The shimmer running through the words.
            label = "slide to unlock"
            shimmer = ((b * 0.9) % 1.6) - 0.3
            def each(i, n):
                d = abs(i / n - shimmer)
                return (0, 0, 1, 0, 0.35 + 0.65 * math.exp(-d * d * 60))
            self.sans.text(ctx, label, tx + tw / 2 + 30, ty + 46, 30, dark, align=0.5, alpha=a1 * (1 - slide), each=each)
            kx = tx + 6 + slide * (tw - 12 - 90)
            ctx.set_source_rgba(0.98, 0.98, 1.0, a1)
            rounded_rect_path(ctx, kx, ty + 6, 90, th - 12, 12)
            ctx.fill()
            ctx.set_source_rgba(*dark, a1 * 0.6)
            ctx.move_to(kx + 34, ty + 22)
            ctx.line_to(kx + 58, ty + 35)
            ctx.line_to(kx + 34, ty + 48)
            ctx.close_path()
            ctx.fill()
            if 25.4 < b < 26.8:
                self.finger(ctx, kx + 45, ty + 35, 1, alpha * (1 - span(b, 26.6, 26.8)), 24, (0.3, 0.3, 0.35))
        # The message after unlocking.
        a2 = alpha * unlocked
        if a2 > 0:
            self.sans.text(ctx, "Messages", x + w / 2, y + 60, 26, dark, align=0.5, alpha=a2 * 0.8)
            word = "hex" if b >= 30.2 else ""
            bx, by = x + w - 60, y + 180
            if word:
                k = out_back(span(b, 30.2, 30.6))
                bw = self.sans.width(word, 40) + 50
                ctx.save()
                ctx.translate(bx - bw / 2, by)
                ctx.scale(k, k)
                ctx.set_source_rgba(*ERAS[3]["accent"], a2)
                rounded_rect_path(ctx, -bw / 2, -36, bw, 72, 36)
                ctx.fill()
                self.sans.text(ctx, word, 0, 14, 40, (1, 1, 1), align=0.5, alpha=a2)
                ctx.restore()

    def swipe_points(self, era, word):
        return [eras.centre(era.keys[c]) for c in word]

    def history(self, ctx, b):
        era, ep = self.era_mix(b)
        bg = self.era_look(era, ep, "bg")
        ctx.set_source_rgb(*bg)
        ctx.paint()
        text = self.era_look(era, ep, "text")
        # The year, huge in the back, rolling to the next.
        yi = era
        names = list(NAMES)
        years = list(YEARS)
        if era == 3 and b >= 28:
            years[3], names[3] = "2010", "SWIPE"
        roll_p = ep
        y_from = years[yi]
        y_to = years[min(4, yi + 1)]
        if era == 3:
            # 2007 rolls to 2010 at bar 8, then on to 2026.
            if b < 28.6:
                y_from, y_to, roll_p = "2007", "2010", in_out_cubic(span(b, 28, 28.6))
            else:
                y_from = "2010"
        out = 1 - span(b, 32.2, 32.9)
        self.odometer(ctx, y_from, y_to, 56, 250, 200, roll_p, text, alpha=0.9 * out)
        # The era's name and trivia, typed out each era.
        name = names[yi]
        start = MORPH_AT[yi - 1] + 0.6 if yi > 0 else 0.2
        if era == 3 and b >= 28:
            start = 28.2
        self.slam(ctx, self.sans_bold, name, 64, 310, 30, b, start, text, tracking=0.08, exit_at=MORPH_AT[yi] - 0.5 if yi < 4 else 32.3)
        for k, line in enumerate(TRIVIA[yi]):
            self.typed(ctx, self.mono, line, W - 66, 120 + k * 30, 19, b, start + 1 + k * 1.5, text, rate=0.06, align=1, alpha=0.75 * (1 - span(b, MORPH_AT[yi] - 0.6, MORPH_AT[yi] - 0.3)))
        if out > 0:
            ctx.push_group()
            self.panel(ctx, b)
            ctx.pop_group_to_source()
            ctx.paint_with_alpha(out)
        events = self.history_events(b)
        if b < 8:
            self.typebars(ctx, b)
        self.history_keys(ctx, b, events)
        # 2010: a swipe over the glass keys writing hex.
        if 28.4 <= b < 31.6:
            path = smooth_path(self.swipe_points(self.eras[3], "hex"), 18)
            k = in_out_cubic(span(b, 28.6, 30.0))
            tip, part = along(path, k)
            fade = 1 - span(b, 30.6, 31.4)
            self.trail(ctx, part, lambda f: flow(f), 16, fade)
            if b < 30.2:
                self.finger(ctx, *tip, 1, fade, 26, (0.15, 0.15, 0.2))
        self.hud(ctx, b, mix(text, bg, 0.3), alpha=0.9)

    # ------------------------------------------------ act 2: hexagons

    def hex_place(self, b):
        """Where the keyboard sits in the hexagons act: centre and width."""
        k = self.kb
        start = (HEX_BOX[0] + HEX_BOX[2] / 2, HEX_BOX[1] + k.height * HEX_BOX[2] / k.width / 2, HEX_BOX[2])
        left = (490, 560, 700)
        typing = (960, 690, 760)
        p = out_expo(span(b, 33.0, 34.0))
        q = in_out_expo(span(b, 39.9, 40.8))
        return tuple(lerp(lerp(a, c, p), d, q) for a, c, d in zip(start, left, typing))

    def key_xy(self, label, place):
        cx, cy, width = place
        k = width / self.kb.width
        x, y = self.kb.key(label)["centre"]
        return cx - width / 2 + x * k, cy - self.kb.height * k / 2 + y * k

    def hex_keyboard(self, ctx, place, b, presses=None, alpha=1.0, key_fx=None, case_alpha=1.0):
        """The keyboard in Dusk, centred at place's x, y and place's width wide."""
        cx, cy, width = place
        kb = self.kb
        k = width / kb.width
        h = kb.height * k
        x0, y0 = cx - width / 2, cy - h / 2
        if case_alpha > 0:
            ctx.set_source_rgba(*DUSK_BG, alpha * case_alpha)
            rounded_rect_path(ctx, x0 - 26, y0 - 26, width + 52, h + 52, 28)
            ctx.fill()
        for key in kb.keys:
            label = key["label"]
            dx, dy, angle, scale, a = key_fx(key) if key_fx else (0, 0, 0, 1, 1)
            if a <= 0.01 or scale <= 0.01:
                continue
            q = (presses or {}).get(label, 0)
            kx, ky = key["centre"]
            sx, sy = x0 + kx * k + dx, y0 + ky * k + dy
            colour = DUSK_ROWS[key["row"]] if key["row"] is not None else DUSK_OTHER
            colour = mix(colour, mix(DUSK_LABEL, ERAS[4]["accent"], 0.5), 0.6 * q)
            ctx.save()
            ctx.translate(sx, sy)
            ctx.rotate(angle)
            ctx.scale(k * scale * (1 - 0.06 * q), k * scale * (1 - 0.06 * q))
            ctx.translate(-kx, -ky)
            polygon(ctx, key["outline"])
            ctx.set_source_rgba(*colour, alpha * a)
            ctx.fill()
            text = {"⏎": "↵", "space": "", "123": "@#€"}.get(label, label)
            if text:
                size = kb.cell * (0.36 if len(text) == 1 else 0.22)
                self.sans.text(ctx, text, kx, ky + size * 0.36, size, DUSK_LABEL, align=0.5, alpha=alpha * a)
            ctx.restore()

    def giant(self, ctx, text, x, y, size, alpha, colour=PAPER, stroke=2.0, each=None):
        """Huge outlined type in the back."""
        ctx.save()
        self_font = self.heavy
        tx = x - self_font.width(text, size) / 2
        for i, ch in enumerate(text):
            dx, dy, sc, an, a = each(i, len(text)) if each else (0, 0, 1, 0, 1)
            adv = self_font.advance(ch, size)
            ctx.save()
            ctx.translate(tx + dx + adv / 2, y + dy)
            ctx.rotate(an)
            ctx.scale(sc, sc)
            ctx.translate(-adv / 2, 0)
            ctx.new_path()
            self_font.glyph(ctx, ch, size)
            ctx.set_line_width(stroke / max(sc, 0.01))
            ctx.set_source_rgba(*colour, alpha * a)
            ctx.stroke()
            ctx.restore()
            tx += adv
        ctx.restore()

    def fact(self, ctx, kind, x, y, s, t):
        """A fact card's drawing at x, y, s across, t beats after it arrived."""
        ctx.save()
        ctx.translate(x, y)
        ctx.scale(s / 140, s / 140)
        line = (0.85, 0.86, 0.9)
        ctx.set_line_join(cairo.LINE_JOIN_ROUND)
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        if kind == "bees":
            r = 22
            cells = [(0, 0)] + [(math.cos(math.radians(30 + 60 * i)) * r * math.sqrt(3), math.sin(math.radians(30 + 60 * i)) * r * math.sqrt(3)) for i in range(6)]
            for i, (cx, cy) in enumerate(cells):
                grow = out_back(span(t, i * 0.06, i * 0.06 + 0.4))
                hexagon(ctx, cx, cy, r * 0.96 * max(grow, 0.01))
                if i == 0:
                    ctx.set_source_rgba(0.96, 0.70, 0.18, 1)
                    ctx.fill_preserve()
                ctx.set_source_rgba(*line, 0.9)
                ctx.set_line_width(2.5)
                ctx.stroke()
            # The angle where three walls meet.
            vx, vy = 0, -r
            ctx.set_source_rgba(*GREEN, 1)
            ctx.set_line_width(3)
            ctx.arc(vx, vy, 12, math.radians(-30), math.radians(90))
            ctx.stroke()
            self.mono.text(ctx, "120°", 40, -44, 18, GREEN, align=0.5)
        elif kind == "hales":
            r = 15
            for row in range(-2, 3):
                for col in range(-3, 4):
                    cx = col * r * math.sqrt(3) + (r * math.sqrt(3) / 2 if row % 2 else 0)
                    cy = row * r * 1.5
                    if math.hypot(cx, cy) > 62:
                        continue
                    d = math.hypot(cx, cy) / 62
                    a = out_cubic(span(t, d * 0.6, d * 0.6 + 0.3))
                    hexagon(ctx, cx, cy, r * 0.95)
                    ctx.set_source_rgba(*flow(d), a)
                    ctx.set_line_width(2)
                    ctx.stroke()
            ctx.set_source_rgba(*PAPER, out_cubic(span(t, 0.8, 1.0)))
            ctx.rectangle(48, 40, 16, 16)
            ctx.fill()
        elif kind == "saturn":
            ctx.save()
            ctx.rotate(-0.35)
            ctx.save()
            ctx.scale(1, 0.28)
            ctx.arc(0, 0, 78, math.pi, 2 * math.pi)
            ctx.set_source_rgba(0.80, 0.72, 0.55, 0.8)
            ctx.set_line_width(7)
            ctx.stroke()
            ctx.restore()
            ctx.arc(0, 0, 44, 0, 2 * math.pi)
            ctx.set_source_rgb(0.86, 0.76, 0.56)
            ctx.fill()
            ctx.save()
            ctx.translate(0, -30)
            ctx.scale(1, 0.38)
            hexagon(ctx, 0, 0, 22, t * 0.4)
            ctx.set_source_rgb(0.45, 0.62, 0.70)
            ctx.fill()
            ctx.restore()
            ctx.save()
            ctx.scale(1, 0.28)
            ctx.arc(0, 0, 78, 0, math.pi)
            ctx.set_source_rgba(0.80, 0.72, 0.55, 0.9)
            ctx.set_line_width(7)
            ctx.stroke()
            ctx.restore()
            ctx.restore()
        elif kind == "causeway":
            heights = [46, 70, 30, 58, 40, 84, 24]
            xs = [-60, -40, -20, 0, 20, 40, 60]
            for i, (cx, hgt) in enumerate(sorted(zip(xs, heights), key=lambda c: -abs(c[0]))):
                grow = out_expo(span(t, i * 0.05, i * 0.05 + 0.5)) * hgt
                top = 40 - grow
                ctx.set_source_rgb(0.25, 0.26, 0.30)
                ctx.rectangle(cx - 12, top, 24, 40 - top + 8)
                ctx.fill()
                ctx.save()
                ctx.translate(cx, top)
                ctx.scale(1, 0.45)
                hexagon(ctx, 0, 0, 13.5, math.radians(30))
                ctx.restore()
                ctx.set_source_rgb(0.55, 0.57, 0.62)
                ctx.fill()
        elif kind == "graphene":
            r = 18
            pts = []
            for row in range(-3, 4):
                for col in range(-3, 4):
                    cx = col * r * math.sqrt(3) + (r * math.sqrt(3) / 2 if row % 2 else 0)
                    cy = row * r * 1.5
                    if math.hypot(cx, cy) < 56:
                        for i in range(6):
                            a = math.radians(60 * i - 90)
                            pts.append((round(cx + r * math.cos(a), 1), round(cy + r * math.sin(a), 1)))
                        hexagon(ctx, cx, cy, r)
            ctx.set_source_rgba(*line, 0.7)
            ctx.set_line_width(2)
            ctx.stroke()
            for px, py in set(pts):
                wob = math.sin(t * 6 + px * 0.3 + py * 0.2) * 1.2
                ctx.arc(px + wob, py, 4, 0, 2 * math.pi)
                ctx.set_source_rgb(*BLUE)
                ctx.fill()
        elif kind == "benzene":
            r = 44
            pts = [(r * math.cos(math.radians(60 * i - 90)), r * math.sin(math.radians(60 * i - 90))) for i in range(6)]
            polygon(ctx, pts)
            ctx.set_source_rgba(*line, 0.9)
            ctx.set_line_width(3)
            ctx.stroke()
            # The double bonds swap places every beat, as the resonance does.
            flip = int(t + 0.5) % 2
            for i in range(flip, 6, 2):
                a, c = pts[i], pts[(i + 1) % 6]
                ax, ay = a[0] * 0.78, a[1] * 0.78
                cx, cy = c[0] * 0.78, c[1] * 0.78
                ctx.move_to(ax, ay)
                ctx.line_to(cx, cy)
                ctx.set_source_rgba(*PURPLE, 1)
                ctx.stroke()
            for i, (px, py) in enumerate(pts):
                self.mono.text(ctx, "C", px * 1.32, py * 1.32 + 6, 15, DIM, align=0.5)
        elif kind == "snow":
            ctx.rotate(t * 0.15)
            ctx.set_source_rgba(0.80, 0.90, 1.0, 1)
            ctx.set_line_width(3)
            grow = out_expo(span(t, 0, 0.6))
            for i in range(6):
                ctx.save()
                ctx.rotate(math.radians(60 * i))
                ctx.move_to(0, 0)
                ctx.line_to(0, -58 * grow)
                for d, ln in ((24, 16), (40, 11)):
                    ctx.move_to(0, -d * grow)
                    ctx.line_to(ln * grow, -(d + ln) * grow)
                    ctx.move_to(0, -d * grow)
                    ctx.line_to(-ln * grow, -(d + ln) * grow)
                ctx.stroke()
                ctx.restore()
        elif kind == "hex":
            for i, (colour, code) in enumerate(zip(FLOW, ("#5F72D9", "#3FA393", "#A465B8"))):
                cx = (i - 1) * 46
                grow = out_back(span(t, i * 0.1, i * 0.1 + 0.4))
                rounded_hexagon(ctx, cx, -12, 24 * max(grow, 0.01), 0.2)
                ctx.set_source_rgb(*colour)
                ctx.fill()
                self.mono.text(ctx, code, cx, 34, 11, colour, align=0.5)
        ctx.restore()

    def hexagons(self, ctx, b):
        place = self.hex_place(b)
        # Huge HEX in the back, slowly coming closer; at the roll it rushes past.
        rush = in_expo(span(b, 45.5, 48.0))
        word = "HEX" if b < 40.4 else "BESTAGONS"
        gsize = 760 * (1 + 0.04 * (b - 32)) if b < 40.4 else 360 * (1 + 0.02 * (b - 40)) * (1 + rush * 9)
        ga = 0.07 * span(b, 32.4, 33.4) * (1 - span(b, 39.8, 40.4)) if b < 40.4 else 0.06 * span(b, 40.4, 41.2) * (1 - rush)
        if ga > 0:
            self.giant(ctx, word, W / 2, H / 2 + gsize * 0.35, gsize, ga, stroke=3)
        presses = {}
        events = []
        # The words: three swiped, one tapped letter by letter.
        swipes = [("hexagons", 40.7, 41.7), ("are", 42.0, 42.6), ("the", 42.85, 43.35)]
        taps = [(43.75 + j / 6, c) for j, c in enumerate("bestagons")]
        for t0, c in taps:
            if t0 <= b:
                presses[c] = max(presses.get(c, 0), math.exp(-(b - t0) * 8))
        for word_, s0, s1 in swipes:
            if s0 <= b <= s1 + 0.3:
                k = span(b, s0, s1)
                for i, c in enumerate(word_):
                    at = lerp(s0, s1, i / max(1, len(word_) - 1))
                    if at <= b:
                        presses[c] = max(presses.get(c, 0), math.exp(-(b - at) * 5))

        def key_fx(key):
            if b < 45.8:
                return (0, 0, 0, 1, 1)
            cx, cy = key["centre"]
            dx, dy = cx - self.kb.width / 2, cy - self.kb.height / 2
            d = math.hypot(dx, dy) or 1
            e = in_expo(span(b, 45.8 + (1 - d / 700) * 0.4, 47.9))
            return (dx / d * e * 1600, dy / d * e * 1600, e * 4 * (1 if key["row"] in (0, 2) else -1), 1 + e * 1.5, 1 - span(e, 0.7, 1))

        self.hex_keyboard(ctx, place, b, presses, key_fx=key_fx, case_alpha=1 - span(b, 45.8, 46.4))
        # Fact cards popping out of their keys onto a honeycomb.
        for i, (label, kind, top, bottom) in enumerate(FACTS):
            t0 = 33.5 + i * 0.85
            if b < t0:
                continue
            fly = out_expo(span(b, t0, t0 + 0.55))
            pop = spring(max(0.0, b - t0) * 0.9) if b - t0 < 3 else 1
            leave = in_back(span(b, 39.7 + i * 0.05, 40.2 + i * 0.05))
            sx, sy = self.key_xy(label, place)
            tx, ty = CARD_AT[i]
            x, y = lerp(sx, tx, fly), lerp(sy, ty, fly)
            s = max(0.01, lerp(0.15, 1, fly) * pop * (1 - leave))
            if leave >= 1:
                continue
            if fly < 1:
                self.trail(ctx, [(sx, sy), (x, y)], flow(i / 7), 6, 1 - fly)
            r = CARD_R * s
            ctx.save()
            ctx.translate(x, y)
            rounded_hexagon(ctx, 0, 0, r * 0.97, 0.12)
            ctx.set_source_rgb(0.085, 0.085, 0.105)
            ctx.fill_preserve()
            ctx.set_source_rgba(*flow(i / 7), 0.9)
            ctx.set_line_width(3)
            ctx.stroke()
            ctx.restore()
            self.fact(ctx, kind, x, y - 28 * s, 170 * s, b - t0)
            if s > 0.6:
                self.mono.text(ctx, top, x, y + 78 * s, 19 * s, PAPER, align=0.5)
                self.mono.text(ctx, bottom, x, y + 104 * s, 15 * s, mix(DIM, PAPER, 0.4), align=0.5)
            # The key it came from glows as it leaves.
            presses[label] = max(presses.get(label, 0), math.exp(-(b - t0) * 4))
            # A ring on arrival.
            ring = span(b, t0 + 0.4, t0 + 1.2)
            if 0 < ring < 1:
                hexagon(ctx, tx, ty, CARD_R + ring * 90)
                ctx.set_source_rgba(*flow(i / 7), 0.6 * (1 - ring))
                ctx.set_line_width(2)
                ctx.stroke()
        # The text being written, and the strip above the keys.
        if b >= 40.3:
            self.writing(ctx, b, place, swipes, taps)
        # Swipe trails and fingers.
        for word_, s0, s1 in swipes:
            if s0 - 0.1 <= b <= s1 + 0.4:
                path = smooth_path([self.key_xy(c, place) for c in word_], 14)
                k = in_out_cubic(span(b, s0, s1))
                tip, part = along(path, k)
                fade = 1 - span(b, s1, s1 + 0.4)
                self.trail(ctx, part, lambda f: flow(f), 14, fade)
                self.finger(ctx, *tip, 1, fade * span(b, s0 - 0.1, s0))
        for t0, c in taps:
            if t0 - 0.1 <= b <= t0 + 0.12:
                self.finger(ctx, *self.key_xy(c, place), 1 - abs(b - t0) * 5, 1 - span(b, t0, t0 + 0.12))
        # The roll: speed lines, everything rushing into the middle.
        if b > 46.2:
            k = span(b, 46.2, 48)
            for i in range(80):
                a = i * 2.39996
                rad = (150 + (i * 97 + b * 700) % 1000) * (0.5 + k)
                ctx.set_source_rgba(*flow((i % 9) / 8), 0.5 * k)
                ctx.set_line_width(2 + 3 * k)
                ctx.move_to(W / 2 + math.cos(a) * rad, H / 2 + math.sin(a) * rad)
                ctx.line_to(W / 2 + math.cos(a) * rad * (1.15 + k), H / 2 + math.sin(a) * rad * (1.15 + k))
                ctx.stroke()
        self.hud(ctx, b)

    def strip_at(self, b, swipes, taps):
        """The strip's three words at beat b, as the app's strip() gave them."""
        slots = ["", "", ""]
        for (word_, s0, s1), words in zip(swipes, [("the", "of", "to"), ("not", "a", "you"), ("new", "first", "same")]):
            if b >= s1:
                slots = list(words)
        for (t0, _), words in zip(taps, STRIP_BESTAGONS):
            if b >= t0:
                slots = list(words)
        if b >= KEEP_AT:
            slots = ["", "", ""]
        return slots

    def writing(self, ctx, b, place, swipes, taps):
        cx, cy, width = place
        kb = self.kb
        h = kb.height * width / kb.width
        top = cy - h / 2 - 26
        alpha = span(b, 40.3, 40.9) * (1 - span(b, 45.9, 46.4))
        # The strip: three word slots, the middle one bold.
        sh = 64
        sx0, sx1 = cx - width / 2 - 26, cx + width / 2 + 26
        ctx.set_source_rgba(*DUSK_BG, alpha)
        rounded_rect_path(ctx, sx0, top - sh, sx1 - sx0, sh + 30, 28)
        ctx.fill()
        ctx.set_source_rgba(*mix(DUSK_BG, DUSK_LABEL, 0.12), alpha)
        ctx.rectangle(sx0 + 30, top - 1, sx1 - sx0 - 60, 1.5)
        ctx.fill()
        slots = self.strip_at(b, swipes, taps)
        slot_w = (sx1 - sx0 - 120) / 3
        for i, word_ in enumerate(slots):
            x = sx0 + 60 + slot_w * (i + 0.5)
            font = self.sans_bold if i == 1 else self.sans
            size = 30
            wdt = font.width(word_, size)
            if wdt > slot_w - 20:
                size *= (slot_w - 20) / wdt
            hl = span(b, KEEP_AT - 0.15, KEEP_AT) * (1 - span(b, KEEP_AT, KEEP_AT + 0.5)) if i == 0 else 0
            if hl > 0:
                ctx.set_source_rgba(*ERAS[4]["accent"], 0.5 * hl * alpha)
                rounded_rect_path(ctx, x - slot_w / 2 + 6, top - sh + 8, slot_w - 12, sh - 16, 18)
                ctx.fill()
            font.text(ctx, word_, x, top - sh / 2 + size * 0.36, size, DUSK_LABEL, align=0.5, alpha=alpha)
        for i in (1, 2):
            x = sx0 + 60 + slot_w * i
            ctx.set_source_rgba(*DUSK_LABEL, 0.2 * alpha)
            ctx.rectangle(x, top - sh * 0.7, 1.5, sh * 0.4)
            ctx.fill()
        if KEEP_AT - 0.2 <= b <= KEEP_AT + 0.15:
            self.finger(ctx, sx0 + 60 + slot_w * 0.5, top - sh / 2, 1, 1 - span(b, KEEP_AT, KEEP_AT + 0.15))
        # The text.
        text = ""
        for word_, s0, s1 in swipes:
            if b >= s1:
                text += word_ + " "
        for t0, c in taps:
            if b >= t0:
                text += c
        if b >= KEEP_AT:
            text += " "
        size = 76
        ty = top - sh - 70
        apart = in_expo(span(b, 45.9, 47.9))

        def each(i, n):
            d = (i - n / 2) / max(n, 1)
            return (-d * apart * 900, apart * 300, max(0.01, 1 - apart), d * apart * 6, 1 - span(apart, 0.6, 1))
        x0 = W / 2 - self.sans_bold.width("hexagons are the bestagons", size) / 2
        end = self.sans_bold.text(ctx, text, x0, ty, size, PAPER, each=each)
        if apart <= 0 and int(b * 3) % 2 == 0:
            ctx.set_source_rgba(*ERAS[4]["accent"], 1)
            ctx.rectangle(end + 4, ty - size * 0.78, 5, size * 0.95)
            ctx.fill()
        # What the strip function was asked, and said.
        log = []
        moments = [s1 for _, _, s1 in swipes] + [t0 for t0, _ in taps] + [KEEP_AT]
        for m in moments:
            if m <= b:
                tt = ""
                for word_, s0, s1 in swipes:
                    if m >= s1:
                        tt += word_ + " "
                for t0, c in taps:
                    if m >= t0:
                        tt += c
                if m >= KEEP_AT:
                    tt += " "
                log.append(f'strip("{tt}") → ' + " | ".join(w or "·" for w in self.strip_at(m + 1e-6, swipes, taps)))
        for i, line in enumerate(log[-7:]):
            self.mono.text(ctx, line, 70, 110 + i * 22, 15, DIM, alpha=alpha * (0.4 + 0.6 * (i + 1) / len(log[-7:])))

    # ------------------------------------------------ act 3: the gestures

    def title_card(self, ctx, b, start, end, word, line, number, colour=PAPER):
        """A gesture's name slammed in at the top left, what it does typed
        under it, and its number at the top right."""
        self.slam(ctx, self.heavy, word, 70, 190, 118, b, start, colour, stagger=0.05, exit_at=end - 0.45)
        self.typed(ctx, self.mono, line, 74, 240, 22, b, start + 0.5, mix(colour, INK, 0.3), rate=0.04,
                   alpha=1 - span(b, end - 0.4, end - 0.1))
        self.slam(ctx, self.heavy, f"{number:02d}", W - 70, 190, 118, b, start + 0.15, mix(DIM, colour, 0.2), align=1, exit_at=end - 0.4)
        self.mono.text(ctx, "/06", W - 70, 222, 20, DIM, align=1, alpha=1 - span(b, end - 0.4, end - 0.1))

    def blueprint(self, ctx, alpha, step=48, colour=(0.25, 0.35, 0.6), shift=(0, 0)):
        ctx.set_line_width(1)
        for i in range(-1, W // step + 2):
            x = i * step + shift[0] % step
            ctx.set_source_rgba(*colour, alpha * (0.35 if i % 4 else 0.7))
            ctx.move_to(x, 0)
            ctx.line_to(x, H)
            ctx.stroke()
        for j in range(-1, H // step + 2):
            y = j * step + shift[1] % step
            ctx.set_source_rgba(*colour, alpha * (0.35 if j % 4 else 0.7))
            ctx.move_to(0, y)
            ctx.line_to(W, y)
            ctx.stroke()

    def logo(self, ctx, b):
        """The drop: three hexagon keys fly in and lock into the icon on the
        downbeat, its letters pop in, the name rises under it."""
        local = b - 48
        kb = self.kb
        keys = [kb.key("r"), kb.key("d"), kb.key("f")]
        letters = ["e", "h", "x"]
        angles = [0, -30, 30]
        mx = sum(k["centre"][0] for k in keys) / 3
        my = sum(k["centre"][1] for k in keys) / 3
        s = 2.6 * (1 + 0.05 * local)
        settle = out_expo(span(local, 0, 0.9))
        leave = in_expo(span(local, 3.4, 4.0))
        # Rings of hexagons rippling out from the lock-in.
        for k in range(4):
            ring = span(local, k * 0.25, k * 0.25 + 2.0)
            if 0 < ring < 1:
                ctx.set_source_rgba(*flow(k / 3), 0.7 * (1 - ring))
                ctx.set_line_width(4 * (1 - ring) + 1)
                hexagon(ctx, W / 2, H / 2 - 60, 180 + out_cubic(ring) * 1100, ring * 0.4 * (1 if k % 2 else -1))
                ctx.stroke()
        ctx.save()
        ctx.translate(W / 2, H / 2 - 60)
        ctx.scale(1 + leave * 6, 1 + leave * 6)
        for i, (k, letter, angle) in enumerate(zip(keys, letters, angles)):
            start = [(-900, -700), (-1200, 600), (1200, 600)][i]
            px = lerp(start[0], (k["centre"][0] - mx) * s, settle)
            py = lerp(start[1], (k["centre"][1] - my) * s, settle)
            spin = (1 - settle) * (3 - i * 1.5)
            ctx.save()
            ctx.translate(px, py)
            ctx.rotate(spin)
            ctx.scale(s, s)
            ctx.translate(-k["centre"][0], -k["centre"][1])
            polygon(ctx, k["outline"])
            ctx.set_source_rgba(*flow(i / 2), 0.16)
            ctx.fill_preserve()
            ctx.set_line_width(6 / s)
            ctx.set_line_join(cairo.LINE_JOIN_ROUND)
            ctx.set_source_rgba(*flow(i / 2), 1)
            ctx.stroke()
            ctx.restore()
            p = out_back(span(local, 0.5 + i * 0.15, 1.1 + i * 0.15), 2.5)
            ctx.save()
            ctx.translate(px, py)
            ctx.rotate(math.radians(angle) + spin)
            self.bold.text(ctx, letter, 0, 0.6 * kb.cell * s * 0.36, 0.6 * kb.cell * s, flow(i / 2), align=0.5,
                           each=lambda j, n: (0, 0, max(p, 0.01), 0, min(1, max(p, 0))))
            ctx.restore()
        ctx.restore()
        self.slam(ctx, self.heavy, "hexkeys", W / 2, H * 0.88, 130, local, 1.3, PAPER, align=0.5, stagger=0.07, exit_at=3.3)
        self.typed(ctx, self.mono, "A HEXAGONAL KEYBOARD FOR ANDROID", W / 2, H * 0.88 + 50, 22, local, 2.0, DIM, rate=0.03, align=0.5,
                   alpha=1 - span(local, 3.3, 3.6))

    def flick(self, ctx, b):
        """Flick up for a capital: one key as a diagram, then words on the beat."""
        local = b - 52
        self.blueprint(ctx, 0.25 * (1 - span(local, 1.6, 2.4)) * span(local, 0, 0.3))
        self.title_card(ctx, b, 52.1, 60, "FLICK", "UP ON A LETTER: ITS CAPITAL", 1)
        # The diagram: a big h with a finger flicking up from it.
        zoom = 1 - in_out_expo(span(local, 1.7, 2.5))
        if zoom > 0:
            key = self.kb.key("h")
            s = 3.4 * zoom + 0.6
            cx, cy = 900, 620
            ctx.save()
            ctx.translate(cx, cy)
            ctx.scale(s, s)
            ctx.translate(*(-v for v in key["centre"]))
            polygon(ctx, key["outline"])
            ctx.set_source_rgba(*DUSK_ROWS[1], zoom)
            ctx.fill()
            ctx.restore()
            up = out_expo(span(local, 0.7, 1.0))
            cap = out_back(span(local, 0.85, 1.25), 2.5)
            size = self.kb.cell * 0.4 * s
            self.sans.text(ctx, "h", cx, cy + size * 0.36 - cap * 80, size, DUSK_LABEL, align=0.5, alpha=zoom * (1 - cap))
            self.sans.text(ctx, "H", cx, cy + size * 0.36 + (1 - cap) * 60, size, DUSK_LABEL, align=0.5, alpha=zoom * min(1, cap))
            kw = self.kb.cell * s
            # Dimension lines: where a tap ends, a flick starts, and a path begins.
            x = cx + kw * 0.75
            for frac, label, colour in ((0.35, "0.35 KEY · FLICK", GREEN), (1.0, "1 KEY · SWIPE PATH", PURPLE)):
                y = cy - frac * kw
                ctx.set_source_rgba(*colour, 0.9 * zoom)
                ctx.set_line_width(2)
                ctx.move_to(x - 20, y)
                ctx.line_to(x + 30, y)
                ctx.stroke()
                self.mono.text(ctx, label, x + 42, y + 7, 20, colour, alpha=zoom)
            ctx.set_source_rgba(*PAPER, 0.4 * zoom)
            ctx.move_to(x, cy)
            ctx.line_to(x, cy - kw)
            ctx.stroke()
            # The finger and its stroke.
            fy = cy - up * 0.62 * kw
            if 0.3 < local < 1.6:
                self.trail(ctx, [(cx, cy), (cx, fy)], GREEN, 22, 1 - span(local, 1.2, 1.6), fade=False)
                self.finger(ctx, cx, fy, 1 - up, 1 - span(local, 1.0, 1.3), 34)
            dy = up * 0.62
            self.mono.text(ctx, f"Δy = {dy:.2f} KEY   {int(up * 96):3d} MS", x + 42, cy + 7, 20, PAPER, alpha=zoom * span(local, 0.6, 0.8))
            # The stroke's height over time, plotted as it happens.
            gx, gy, gw, gh = 1300, 420, 460, 260
            ctx.set_source_rgba(*DIM, 0.6 * zoom)
            ctx.rectangle(gx, gy, gw, gh)
            ctx.set_line_width(1.2)
            ctx.stroke()
            self.mono.text(ctx, "Δy / t", gx, gy - 14, 16, DIM, alpha=zoom)
            pts = []
            for i in range(60):
                f = i / 59
                tt = lerp(0.3, local, f) if local > 0.3 else 0.3
                pts.append((gx + (tt - 0.3) / 1.3 * gw, gy + gh - out_expo(span(tt, 0.7, 1.0)) * 0.62 * gh))
            if local > 0.3:
                self.trail(ctx, pts, GREEN, 4, zoom, fade=False)
            ctx.set_source_rgba(*GREEN, 0.5 * zoom)
            ctx.move_to(gx, gy + gh - 0.35 * gh)
            ctx.line_to(gx + gw, gy + gh - 0.35 * gh)
            ctx.set_dash([6, 6])
            ctx.stroke()
            ctx.set_dash([])
        # Then the keyboard, and a capital on every beat.
        if local > 1.7:
            place = (960, 720, 700)
            words = ["Oslo", "Lima", "Rome", "Kyiv", "Baku", "Riga"]
            presses, text, flicks = {}, "", []
            for w_i, word_ in enumerate(words):
                t0 = 54 + w_i
                for c_i, c in enumerate(word_):
                    at = t0 + c_i * 0.25
                    if at <= b:
                        text += c
                        presses[c.lower()] = max(presses.get(c.lower(), 0), math.exp(-(b - at) * 9))
                if t0 + 0.75 <= b:
                    text += " "
                flicks.append((t0, word_[0].lower()))
            if b >= 59.75:
                text = text.rstrip() + "!"
            appear = out_expo(span(local, 1.8, 2.6))

            def key_fx(key):
                d = math.hypot(key["centre"][0] - self.kb.width / 2, key["centre"][1] - self.kb.height / 2) / 700
                p = out_back(span(local, 1.8 + d * 0.5, 2.3 + d * 0.5), 1.8)
                return (0, 0, 0, max(p, 0.01), 1)
            self.hex_keyboard(ctx, place, b, presses, key_fx=key_fx, case_alpha=appear)
            # Arrows rising from each flicked key.
            for t0, c in flicks:
                k = span(b, t0, t0 + 0.6)
                if 0 < k < 1:
                    x, y = self.key_xy(c, place)
                    rise = out_expo(k) * 120
                    ctx.set_source_rgba(*GREEN, 1 - k)
                    ctx.move_to(x, y - 30 - rise)
                    ctx.line_to(x - 22, y - 2 - rise)
                    ctx.line_to(x + 22, y - 2 - rise)
                    ctx.close_path()
                    ctx.fill()
                    self.finger(ctx, x, y - out_expo(span(b, t0, t0 + 0.2)) * 50, 1, 1 - span(b, t0 + 0.15, t0 + 0.3))
            size = 72
            x0 = W / 2 - self.sans_bold.width("Oslo Lima Rome Kyiv Baku Riga!", size) / 2

            def each(i, n):
                ch = text[i]
                pop = 0
                return (0, 0, 1, 0, 1)
            end = self.sans_bold.text(ctx, text, x0, 330, size, PAPER)
            # Capitals glow in the flow colour over the white.
            cx = x0
            for i, ch in enumerate(text):
                if ch.isupper():
                    self.sans_bold.text(ctx, ch, cx, 330, size, flow(i / max(1, len(text))))
                cx += self.sans_bold.advance(ch, size)
        self.hud(ctx, b)

    def delete_timeline(self):
        """When each character and word of the delete scene goes, in beats,
        from the app's delete rules: a slide in from the right on the number
        row takes n = (d / 0.7)^1.2 characters for d key widths moved in
        (counted in full after 350 ms); resting on the far side takes a
        character after 150 ms and every 100 ms, whole words from 1000 ms,
        300 ms apart and quickening to 60."""
        ms = self.beat * 1000
        land, arrive = 60.5, 62.6
        events = []
        last = 0
        for i in range(400):
            b = land + (arrive - land) * i / 399
            d = 5.6 * in_out_cubic(span(b, land + 0.1, arrive))
            counted = d * min(1.0, (b - land) * ms / 350)
            n = int((counted / 0.7) ** 1.2) if counted > 0 else 0
            while last < n:
                last += 1
                events.append((b, "char"))
        t = arrive + 150 / ms
        while t < arrive + 1000 / ms:
            events.append((t, "char"))
            t += 100 / ms
        t, gap = arrive + 1000 / ms, 300
        while t < 67.4:
            events.append((t, "word"))
            t += gap / ms
            gap = max(60, gap - 60)
        return land, arrive, events

    def delete(self, ctx, b):
        local = b - 60
        self.title_card(ctx, b, 60.1, 68, "SLIDE", "IN FROM THE NUMBER ROW'S RIGHT: DELETE", 2)
        place = (960, 700, 760)
        land, arrive, events = self.delete_timeline()
        text = "Sorry, I typed all of this on the wrong keyboard again"
        gone = 0
        pieces = []
        current = text
        for t, kind in events:
            if t > b:
                break
            before = current
            if kind == "char":
                current = current[:-1]
            else:
                cut = current.rstrip()
                cut = cut[:cut.rfind(" ") + 1] if " " in cut else ""
                current = cut
            pieces.append((t, before[len(current):], len(current)))
        # Number row keys under the finger light up.
        cx, cy, width = place
        kw = width / self.kb.width * self.kb.cell
        x_start = self.key_xy("9", place)[0]
        fx = x_start - 5.6 * kw * in_out_cubic(span(b, land + 0.1, arrive))
        fy = self.key_xy("9", place)[1] + 18
        presses = {}
        if land <= b <= 67.5:
            for key in self.kb.keys:
                if key["label"].isdigit():
                    x, y = self.key_xy(key["label"], place)
                    presses[key["label"]] = math.exp(-abs(x - fx) / 40) * 0.9
        self.hex_keyboard(ctx, place, b, presses, case_alpha=1)
        # The text, and every piece deleted flying off in small hexagons.
        size = 54
        x0 = W / 2 - self.sans_bold.width(text, size) / 2
        self.sans_bold.text(ctx, current, x0, 330, size, PAPER)
        end = x0 + self.sans_bold.width(current, size)
        if int(b * 3) % 2 == 0:
            ctx.set_source_rgba(*ERAS[4]["accent"], 1)
            ctx.rectangle(end + 3, 330 - size * 0.78, 4, size * 0.95)
            ctx.fill()
        for t, piece, at in pieces[-40:]:
            k = span(b, t, t + 1.2)
            if k >= 1:
                continue
            px = x0 + self.sans_bold.width(text[:at], size)
            for j, ch in enumerate(piece):
                seed = (at * 31 + j * 17) % 97
                ang = -math.pi / 2 + (seed / 97 - 0.5) * 2.4
                dist = out_expo(k) * (120 + seed * 3)
                gx = px + self.sans_bold.width(piece[:j], size) + math.cos(ang) * dist
                gy = 330 - 20 + math.sin(ang) * dist + k * k * 300
                ctx.save()
                ctx.translate(gx, gy)
                ctx.rotate(k * (seed - 48) * 0.1)
                if k < 0.25:
                    self.sans_bold.text(ctx, ch, 0, 0, size, mix(PAPER, flow(seed / 97), k * 4), alpha=1)
                else:
                    rounded_hexagon(ctx, 0, -size * 0.3, size * 0.32 * (1 - k), 0.2)
                    ctx.set_source_rgba(*flow(seed / 97), 1 - k)
                    ctx.fill()
                ctx.restore()
        # The finger.
        if land - 0.2 <= b <= 67.5:
            self.trail(ctx, [(x_start, fy), (fx, fy)], lambda f: mix(PURPLE, (1, 0.35, 0.45), f), 18, 1 - span(b, 67.3, 67.5), fade=True)
            self.finger(ctx, fx, fy, 1, span(b, land - 0.2, land) * (1 - span(b, 67.3, 67.5)))
        # The rule, plotted: characters by how far the finger came in.
        gx, gy, gw, gh = 1440, 760, 360, 200
        a = span(local, 0.4, 0.8) * (1 - span(b, 67.4, 67.8))
        if a > 0:
            ctx.set_source_rgba(*DIM, 0.6 * a)
            ctx.set_line_width(1.2)
            ctx.move_to(gx, gy)
            ctx.line_to(gx, gy + gh)
            ctx.line_to(gx + gw, gy + gh)
            ctx.stroke()
            curve = [(gx + d / 6 * gw, gy + gh - ((d / 0.7) ** 1.2) / 14 * gh) for d in np.linspace(0, 6, 60)]
            self.trail(ctx, curve, mix(PURPLE, PAPER, 0.3), 3, a, fade=False)
            d_now = (x_start - fx) / kw
            n_now = sum(1 for t, kind in events if t <= b and t <= arrive)
            ctx.arc(gx + d_now / 6 * gw, gy + gh - min(1, ((d_now / 0.7) ** 1.2) / 14) * gh, 8, 0, 2 * math.pi)
            ctx.set_source_rgba(*PAPER, a)
            ctx.fill()
            self.mono.text(ctx, "n = (d / 0.7)^1.2", gx, gy - 16, 20, PAPER, alpha=a)
            self.mono.text(ctx, f"d {d_now:4.1f}  n {n_now:2d}", gx + gw, gy + gh + 30, 18, DIM, align=1, alpha=a)
            words = sum(1 for t, kind in events if t <= b and kind == "word")
            if b > arrive:
                self.mono.text(ctx, f"FAR SIDE · WORDS {words}", gx, gy + gh + 30, 18, (1, 0.45, 0.5), alpha=a)
        self.hud(ctx, b)

    def cursor(self, ctx, b):
        local = b - 68
        self.title_card(ctx, b, 68.1, 76, "GLIDE", "ALONG THE NUMBER ROW'S MIDDLE: THE CURSOR", 3)
        place = (960, 700, 760)
        cx, cy, width = place
        kw = width / self.kb.width * self.kb.cell
        text = "hexagons are teh bestagons"
        # Story: glide back to "teh", slide its three letters away, type "the", glide home.
        fixed = b >= 73.0
        typed_fix = "".join(c for t, c in [(72.0, "t"), (72.33, "h"), (72.67, "e")] if t <= b)
        removed = sum(1 for t in (71.1, 71.25, 71.4) if t <= b)
        caret_end = len(text)
        target = len("hexagons are teh")
        mid_x = self.key_xy("5", place)[0]
        row_y = self.key_xy("5", place)[1] + 18
        # The glide back: one character per 0.3 key widths.
        back = in_out_cubic(span(b, 68.6, 70.4))
        steps_back = caret_end - target
        fx = mid_x - back * steps_back * 0.3 * kw
        caret = caret_end - int(back * steps_back + 1e-6)
        home = in_out_cubic(span(b, 73.6, 75.2))
        before = text[:target - removed] + typed_fix
        after = text[target:]
        shown = before + after
        if b >= 71.0:
            caret = len(before)
        if b >= 73.6:
            fx = mid_x + home * len(after) * 0.3 * kw
            caret = len(before) + int(home * len(after) + 1e-6)
        presses = {}
        for t, c in [(72.0, "t"), (72.33, "h"), (72.67, "e")]:
            if t <= b:
                presses[c] = math.exp(-(b - t) * 9)
        gliding = 68.4 <= b <= 70.6 or 73.4 <= b <= 75.4
        if gliding:
            for key in self.kb.keys:
                if key["label"].isdigit():
                    x, _ = self.key_xy(key["label"], place)
                    presses[key["label"]] = math.exp(-abs(x - fx) / 40) * 0.9
        self.hex_keyboard(ctx, place, b, presses)
        # A ruler over the number row: a tick per character step.
        ra = span(b, 68.3, 68.6) * (1 - span(b, 75.4, 75.8))
        top = cy - self.kb.height * width / self.kb.width / 2 - 40
        for i in range(-12, 13):
            x = mid_x + i * 0.3 * kw
            big = i % 5 == 0
            ctx.set_source_rgba(*(GREEN if big else DIM), ra * (0.9 if big else 0.6))
            ctx.rectangle(x - 1, top - (18 if big else 10), 2, 18 if big else 10)
            ctx.fill()
        self.mono.text(ctx, "0.3 KEY = 1 CHARACTER", mid_x, top - 30, 18, GREEN, align=0.5, alpha=ra)
        # The text with its caret, magnified around the caret.
        size = 64
        x0 = W / 2 - self.sans_bold.width(text, size) / 2
        self.sans_bold.text(ctx, shown, x0, 330, size, PAPER)
        caret_x = x0 + self.sans_bold.width(shown[:caret], size)
        ctx.set_source_rgba(*ERAS[4]["accent"], 1)
        ctx.rectangle(caret_x - 2, 330 - size * 0.8, 5, size)
        ctx.fill()
        if 68.5 < b < 71:
            # The wrong word, marked.
            wx = x0 + self.sans_bold.width("hexagons are ", size)
            ctx.set_source_rgba(1, 0.4, 0.45, span(b, 68.3, 68.7) * (1 - span(b, 70.9, 71.1)))
            for i in range(24):
                x = wx + i * self.sans_bold.width("teh", size) / 24
                (ctx.move_to if i == 0 else ctx.line_to)(x, 346 + 4 * math.sin(i * 1.4))
            ctx.set_line_width(3)
            ctx.stroke()
        if gliding:
            self.trail(ctx, [(mid_x, row_y), (fx, row_y)], GREEN, 16, 1)
            self.finger(ctx, fx, row_y, 1)
        # The three letters slid away from the right.
        if 70.9 <= b <= 71.6:
            sx = self.key_xy("9", place)[0]
            k = in_out_cubic(span(b, 71.0, 71.5))
            self.trail(ctx, [(sx, row_y), (sx - k * 2.6 * kw, row_y)], PURPLE, 16, 1)
            self.finger(ctx, sx - k * 2.6 * kw, row_y, 1)
        for t, c in [(72.0, "t"), (72.33, "h"), (72.67, "e")]:
            if t - 0.08 <= b <= t + 0.12:
                self.finger(ctx, *self.key_xy(c, place), 1, 1 - span(b, t, t + 0.12))
        # A loupe over the caret.
        la = span(b, 68.6, 69.0) * (1 - span(b, 75.3, 75.7))
        if la > 0:
            lx, ly, lr = caret_x, 170, 70
            ctx.save()
            ctx.arc(lx, ly, lr, 0, 2 * math.pi)
            ctx.set_source_rgba(0.08, 0.08, 0.1, la)
            ctx.fill_preserve()
            ctx.clip()
            ctx.translate(lx, ly)
            ctx.scale(2.2, 2.2)
            ctx.translate(-caret_x, -330 + size * 0.35)
            self.sans_bold.text(ctx, shown, x0, 330, size, PAPER, alpha=la)
            ctx.set_source_rgba(*ERAS[4]["accent"], la)
            ctx.rectangle(caret_x - 2, 330 - size * 0.8, 5, size)
            ctx.fill()
            ctx.restore()
            ctx.arc(lx, ly, lr, 0, 2 * math.pi)
            ctx.set_source_rgba(*PAPER, 0.7 * la)
            ctx.set_line_width(3)
            ctx.stroke()
            self.mono.text(ctx, f"CHAR {caret:02d}", lx + lr + 14, ly + 6, 18, DIM, alpha=la)
        self.hud(ctx, b)

    def swipe(self, ctx, b):
        """Swipe typing: the drawn path against a word's ideal path, both cut
        into 32 points and paired up to 3 apart, then words on the beat."""
        local = b - 76
        self.title_card(ctx, b, 76.1, 84, "SWIPE", "ONE STROKE PER WORD", 4)
        place = (960, 700, 760)
        cx, cy, width = place
        k = width / self.kb.width
        x0, y0 = cx - width / 2, cy - self.kb.height * k / 2
        # The keyboard as a wireframe: outlines in the flow, centres as dots.
        for key in self.kb.keys:
            pts = [(x0 + x * k, y0 + y * k) for x, y in key["outline"]]
            polygon(ctx, pts)
            ctx.set_line_width(1.6)
            ctx.set_source_rgba(*flow(key["centre"][0] / self.kb.width), 0.55)
            ctx.stroke()
            kx, ky = self.key_xy(key["label"], place)
            if len(key["label"]) == 1 and key["label"].isalpha():
                self.sans.text(ctx, key["label"], kx, ky + 12, 30, DUSK_LABEL, align=0.5, alpha=0.5)
            ctx.arc(kx, ky - 26, 2.5, 0, 2 * math.pi)
            ctx.set_source_rgba(*PAPER, 0.5)
            ctx.fill()
        words = [("keyboard", 76.5, 78.0, ("backward", "keyboard", "keyboards")),
                 ("just", 80.0, 80.6, ("Judd", "just", "joust")),
                 ("type", 81.0, 81.6, ("for", "type", "T-shirt")),
                 ("faster", 82.0, 82.8, ("far", "faster", "fate"))]
        text = ""
        slots = ("", "", "")
        for word_, s0, s1, cands in words:
            ideal = [self.key_xy(c, place) for c in word_]
            # A hand never runs through the centres: cut corners and wobble.
            drawn = smooth_path(ideal, 16)
            drawn = [(x + 9 * math.sin(i * 0.37 + len(word_)), y + 7 * math.cos(i * 0.29)) for i, (x, y) in enumerate(drawn)]
            if b >= s1:
                text += word_ + " "
                slots = cands
            if s0 - 0.15 <= b <= s1 + (3.0 if word_ == "keyboard" else 0.5):
                kk = in_out_cubic(span(b, s0, s1))
                tip, part = along(drawn, kk)
                fade = 1 - span(b, s1 + (2.6 if word_ == "keyboard" else 0.1), s1 + (3.0 if word_ == "keyboard" else 0.5))
                self.trail(ctx, part, lambda f: flow(f), 18, fade)
                if b < s1 + 0.1:
                    self.finger(ctx, *tip, 1, span(b, s0 - 0.15, s0) * (1 - span(b, s1, s1 + 0.1)))
                if word_ == "keyboard":
                    # The ideal path, dashed, and the 32 pairs between them.
                    ia = span(b, s1, s1 + 0.3) * fade
                    if ia > 0:
                        ctx.set_dash([10, 8])
                        polygon_open = ideal
                        ctx.move_to(*polygon_open[0])
                        for p in polygon_open[1:]:
                            ctx.line_to(*p)
                        ctx.set_source_rgba(*PAPER, 0.8 * ia)
                        ctx.set_line_width(3)
                        ctx.stroke()
                        ctx.set_dash([])
                        n = 32
                        A = [along(drawn, i / (n - 1))[0] for i in range(n)]
                        B = [along(ideal, i / (n - 1))[0] for i in range(n)]
                        shown = int(span(b, s1 + 0.3, s1 + 1.3) * n)
                        for i in range(shown):
                            j = min(range(max(0, i - 3), min(n, i + 4)), key=lambda j: math.dist(A[i], B[j]))
                            ctx.move_to(*A[i])
                            ctx.line_to(*B[j])
                            ctx.set_source_rgba(*GREEN, 0.8 * ia)
                            ctx.set_line_width(1.5)
                            ctx.stroke()
                            for p, c in ((A[i], flow(i / n)), (B[j], PAPER)):
                                ctx.arc(*p, 4, 0, 2 * math.pi)
                                ctx.set_source_rgba(*c, ia)
                                ctx.fill()
                        self.mono.text(ctx, f"32 POINTS EACH · PAIRED UP TO 3 APART · {shown:02d}/32", 70, H - 70, 20, GREEN, alpha=ia)
        # The strip with the decoder's words, the committed one in the middle.
        top = cy - self.kb.height * k / 2 - 26
        ctx.set_source_rgb(*DUSK_BG)
        rounded_rect_path(ctx, cx - width / 2 - 26, top - 64, width + 52, 70, 28)
        ctx.fill()
        if any(b >= s1 for _, _, s1, _ in words):
            slot_w = (width + 52 - 120) / 3
            for i, word_ in enumerate(slots):
                x = cx - width / 2 - 26 + 60 + slot_w * (i + 0.5)
                font = self.sans_bold if i == 1 else self.sans
                font.text(ctx, word_, x, top - 22, 30, DUSK_LABEL, align=0.5, alpha=0.9)
        size = 76
        self.sans_bold.text(ctx, text, W / 2 - self.sans_bold.width("keyboard just type faster ", size) / 2, 250, size, PAPER)
        self.hud(ctx, b)

    def hold(self, ctx, b):
        """Hold a key for its other letters: a honeycomb of them opens around
        it, smaller than the keys, and the finger slides to one."""
        local = b - 84
        self.title_card(ctx, b, 84.1, 90, "HOLD", "FOR EVERY OTHER LETTER", 5)
        place = (960, 720, 700)
        # Hold 1 for a half, type "caf", hold e for its acute: "½ café".
        holds = [("1", "½⅓¼⅕⅙⅛", 84.4, 84.9, 85.5, 0), ("e", "éèêëė", 86.6, 87.1, 87.7, 0)]
        taps = [(85.8, " "), (86.0, "c"), (86.15, "a"), (86.3, "f")]
        text = ""
        presses = {}
        for t, c in taps:
            if t <= b:
                text += c
                presses[key_of(c)] = math.exp(-(b - t) * 9)
        for label, choices, t_down, t_open, t_pick, pick in holds:
            if b >= t_pick:
                text = text + choices[pick] if label == "1" else text + choices[pick]
            if t_down <= b <= t_pick + 0.1:
                presses[label] = 1.0
        if b >= 85.5 and not text.startswith("½"):
            text = "½" + text.replace("½", "")
        self.hex_keyboard(ctx, place, b, presses)
        k = place[2] / self.kb.width
        kw = self.kb.cell * k
        for label, choices, t_down, t_open, t_pick, pick in holds:
            if not (t_down - 0.1 <= b <= t_pick + 0.4):
                continue
            kx, ky = self.key_xy(label, place)
            # The hold, a ring filling around the finger.
            fill = span(b, t_down, t_open)
            if fill < 1:
                ctx.arc(kx, ky, 60, -math.pi / 2, -math.pi / 2 + fill * 2 * math.pi)
                ctx.set_source_rgba(*GREEN, 0.9)
                ctx.set_line_width(6)
                ctx.stroke()
            # The popup: cells of 0.72 keys in a row above the key, as a honeycomb.
            o = out_back(span(b, t_open, t_open + 0.35), 2.0) * (1 - span(b, t_pick + 0.1, t_pick + 0.4))
            r = 0.72 * kw / math.sqrt(3)
            cells = []
            n = len(choices)
            for i in range(n):
                row = i // 4
                col = i % 4 - min(3, n - 1 - row * 4) / 2
                cells.append((kx + col * r * math.sqrt(3) + (r * math.sqrt(3) / 2 if row else 0), ky - 1.5 * kw - row * 1.5 * r))
            sel = pick if b >= t_open + 0.3 else 0
            for i, (ch, (px, py)) in enumerate(zip(choices, cells)):
                if o <= 0.01:
                    continue
                ctx.save()
                ctx.translate(kx + (px - kx) * o, ky + (py - ky) * o)
                ctx.scale(max(o, 0.01), max(o, 0.01))
                rounded_hexagon(ctx, 0, 0, r * 0.95, 0.15)
                ctx.set_source_rgb(*(ERAS[4]["accent"] if i == sel else mix(DUSK_OTHER, DUSK_LABEL, 0.12)))
                ctx.fill()
                self.sans.text(ctx, ch, 0, 16, 44, DUSK_LABEL, align=0.5)
                ctx.restore()
            fx, fy = kx, ky
            if b >= t_open + 0.2:
                m = in_out_cubic(span(b, t_open + 0.2, t_pick - 0.1))
                fx, fy = lerp(kx, cells[pick][0], m), lerp(ky, cells[pick][1], m)
            self.finger(ctx, fx, fy, 1, span(b, t_down - 0.1, t_down) * (1 - span(b, t_pick, t_pick + 0.15)))
        size = 80
        self.sans_bold.text(ctx, text, W / 2 - self.sans_bold.width("½ café", size) / 2, 330, size, PAPER)
        # Every letter the keys lack, falling to the key it is written with.
        rain = span(b, 88.4, 90)
        if rain > 0:
            for i, (ch, base) in enumerate(self.letters[::3]):
                if base not in self.kb.by_label:
                    continue
                seed = (i * 7919) % 1000 / 1000
                start = 88.4 + seed * 0.9
                kk = span(b, start, start + 0.7)
                if kk <= 0 or kk >= 1:
                    continue
                tx, ty = self.key_xy(base, place)
                sx, sy = 120 + seed * (W - 240), 120 + ((i * 37) % 200)
                x, y = lerp(sx, tx, in_expo(kk)), lerp(sy, ty, in_expo(kk))
                self.sans.text(ctx, ch, x, y, 34, flow(seed), align=0.5, alpha=1 - span(kk, 0.85, 1))
            self.mono.text(ctx, f"{self.letter_count} LETTERS, EACH HELD UNDER THE KEY IT IS WRITTEN WITH", W / 2, H - 50, 20, PAPER, align=0.5,
                           alpha=span(b, 88.5, 88.9))
        self.hud(ctx, b)

    def languages(self, ctx, b):
        """Languages: a sideways flick on space steps through them; German
        gets its capitals, Vietnamese types Telex."""
        local = b - 90
        self.title_card(ctx, b, 90.1, 96, "47", "LANGUAGES · FLICK SPACE SIDEWAYS", 6, colour=INK)
        place = (960, 720, 700)
        steps = [(90.4, "DE"), (92.0, "VI"), (94.2, "EN")]
        code = "EN"
        for t, c in steps:
            if b >= t:
                code = c
        presses = {}
        text = ""
        lines = []
        # German: "haus" becomes "Haus" in a surely German text.
        de = [(90.8 + i * 0.17, c) for i, c in enumerate("haus")]
        for t, c in de:
            if t <= b:
                presses[c] = max(presses.get(c, 0), math.exp(-(b - t) * 9))
        word_de = "".join(c for t, c in de if t <= b)
        if b >= 91.6:
            word_de = "Haus"
        # Vietnamese: v i e e t j, Telex turning letters into marks.
        vi_keys = [(92.4 + i * 0.25, c) for i, c in enumerate("vieetj")]
        vi_shown = ["v", "vi", "vie", "viê", "viêt", "việt"]
        word_vi = ""
        for (t, c), shown in zip(vi_keys, vi_shown):
            if t <= b:
                word_vi = shown
                presses[c] = max(presses.get(c, 0), math.exp(-(b - t) * 9))
        # English: "im" is "I'm".
        en = [(94.5, "i"), (94.67, "m")]
        word_en = "".join(c for t, c in en if t <= b)
        for t, c in en:
            if t <= b:
                presses[c] = max(presses.get(c, 0), math.exp(-(b - t) * 9))
        if b >= 95.1:
            word_en = "I'm"
        # Paper theme: the keyboard turns light for this one.
        self.paper_keyboard(ctx, place, presses, code, b, steps)
        size = 84
        y = 360
        parts = [p for p in (word_de, word_vi, word_en) if p]
        self.sans_bold.text(ctx, "  ".join(parts), W / 2 - self.sans_bold.width("Haus  việt  I'm", size) / 2, y, size, INK)
        # Telex as a table of what each key did.
        if 92.3 <= b <= 94.2:
            ta = span(b, 92.3, 92.5) * (1 - span(b, 93.9, 94.2))
            for i, ((t, c), shown) in enumerate(zip(vi_keys, vi_shown)):
                if t <= b:
                    self.mono.text(ctx, f"{c} → {shown}", W - 330, 430 + i * 30, 22, (0.29, 0.36, 0.78), alpha=ta)
        # Every language code flipping past at the end.
        if b >= 95.3:
            codes = "af az eu bs ca ceb hr cs da nl en eo et tl fi fr gl de hi hu is id ga it kab la lv lt lb mg mwl nb nn pms pl pt ro sc sr sk sl es zgh sv tok tr vi".split()
            n = int(span(b, 95.3, 96.0) * len(codes))
            ctx.set_source_rgba(0.93, 0.93, 0.95, 0.92 * span(b, 95.3, 95.45))
            ctx.paint()
            for i, c in enumerate(codes[:n]):
                col, row = i % 16, i // 16
                self.heavy.text(ctx, c.upper(), 120 + col * 108, 470 + row * 60, 40, flow(i / len(codes)), alpha=0.95)
        self.hud(ctx, b, colour=(0.45, 0.45, 0.5))

    def paper_keyboard(self, ctx, place, presses, code, b, steps):
        """The keyboard in the Paper theme, with the language on its space bar."""
        rows = [(0.780, 0.843, 0.965), (0.769, 0.910, 0.831), (0.957, 0.812, 0.855)]
        other, label_c, bg = (0.984, 0.984, 0.992), (0.106, 0.106, 0.122), (0.894, 0.894, 0.918)
        cx, cy, width = place
        kb = self.kb
        k = width / kb.width
        h = kb.height * k
        x0, y0 = cx - width / 2, cy - h / 2
        ctx.set_source_rgb(*bg)
        rounded_rect_path(ctx, x0 - 26, y0 - 26, width + 52, h + 52, 28)
        ctx.fill()
        for key in kb.keys:
            label = key["label"]
            q = presses.get(label, 0)
            kx, ky = key["centre"]
            ctx.save()
            ctx.translate(x0 + kx * k, y0 + ky * k)
            ctx.scale(k * (1 - 0.06 * q), k * (1 - 0.06 * q))
            ctx.translate(-kx, -ky)
            polygon(ctx, key["outline"])
            colour = rows[key["row"]] if key["row"] is not None else other
            ctx.set_source_rgb(*mix(colour, label_c, 0.25 * q))
            ctx.fill()
            text = {"⏎": "↵", "space": "", "123": "@#€"}.get(label, label)
            if label == "space":
                # The language on the space bar, flipping on each flick.
                for t, c in steps:
                    f = span(b, t, t + 0.3)
                    if 0 < f < 1:
                        pass
                size = kb.cell * 0.3
                last = max([t for t, _ in steps if t <= b], default=-9)
                f = out_back(span(b, last, last + 0.35), 2.5) if last > 0 else 1
                self.sans_bold.text(ctx, code, kx, ky + size * 0.36, size * max(f, 0.01), (0.29, 0.36, 0.78), align=0.5)
            elif text:
                size = kb.cell * (0.36 if len(text) == 1 else 0.22)
                self.sans.text(ctx, text, kx, ky + size * 0.36, size, label_c, align=0.5)
            ctx.restore()
        # The flick on space.
        sx, sy = self.key_xy("space", place)
        for t, c in steps:
            if t - 0.1 <= b <= t + 0.25:
                m = out_expo(span(b, t - 0.05, t + 0.15))
                self.trail(ctx, [(sx - 60, sy), (sx - 60 + m * 140, sy)], (0.29, 0.36, 0.78), 14, 1)
                self.finger(ctx, sx - 60 + m * 140, sy, 1, 1 - span(b, t + 0.1, t + 0.25), colour=(0.2, 0.2, 0.25))

    # ------------------------------------------------ act 4: voice

    def level(self, b, stretches):
        """A loudness 0 to 1 at beat b: syllables while a stretch is spoken, silence between."""
        for s0, s1 in stretches:
            if s0 <= b <= s1:
                syl = abs(math.sin(b * 9.7)) * (0.55 + 0.45 * math.sin(b * 3.1 + 1)) + 0.15 * math.sin(b * 31)
                return clamp(syl) * span(b, s0, s0 + 0.2) * (1 - span(b, s1 - 0.2, s1))
        return 0.0

    def voice(self, ctx, b):
        local = b - 96
        ctx.set_source_rgb(0.035, 0.035, 0.06)
        ctx.paint()
        stretches = [(98.2, 101.8), (103.0, 106.2)]
        said = [("Hey Sam, are we still on for 8?", 102.6), ("Bring the hexagons!", 107.0)]
        # The keyboard below, the mic in the strip tapped, then sinking away.
        down = in_out_expo(span(b, 97.1, 97.9))
        place = (960, 700 + down * 700, 760)
        if down < 1:
            self.hex_keyboard(ctx, place, b)
            cx, cy, width = place
            top = cy - self.kb.height * width / self.kb.width / 2 - 26
            ctx.set_source_rgb(*DUSK_BG)
            rounded_rect_path(ctx, cx - width / 2 - 26, top - 64, width + 52, 94, 28)
            ctx.fill()
            mx, my = cx + width / 2 - 70, top - 32
            if b < 96.8 + 0.3:
                self.finger(ctx, mx, my, 1, span(b, 96.4, 96.6) * (1 - span(b, 96.9, 97.1)))
        else:
            mx, my = 960, 9999
        # The mic, rising from the strip into a big hexagon in the middle.
        rise = out_expo(span(b, 96.9, 97.9))
        hx, hy = lerp(1270, 960, rise), lerp(330, 660, rise)
        r = lerp(28, 120, rise)
        listening = 97.0 <= b <= 108.0
        lv = self.level(b, stretches)
        # A ring of bars round the hexagon, moving with the voice.
        if rise > 0.3:
            n = 72
            for i in range(n):
                a = 2 * math.pi * i / n - math.pi / 2
                # Distance to a hexagon's edge in direction a.
                seg = (a + math.pi / 2) % (math.pi / 3) - math.pi / 6
                edge = r * 1.25 * math.cos(math.pi / 6) / math.cos(seg)
                amp = lv * (0.4 + 0.6 * abs(math.sin(i * 1.7 + b * 5))) * 140
                x1, y1 = hx + math.cos(a) * edge, hy + math.sin(a) * edge
                x2, y2 = hx + math.cos(a) * (edge + 6 + amp), hy + math.sin(a) * (edge + 6 + amp)
                ctx.move_to(x1, y1)
                ctx.line_to(x2, y2)
                ctx.set_source_rgba(*flow(i / n), 0.9 * span(rise, 0.3, 0.6))
                ctx.set_line_width(7)
                ctx.set_line_cap(cairo.LINE_CAP_ROUND)
                ctx.stroke()
        pulse = 1 + 0.06 * lv
        rounded_hexagon(ctx, hx, hy, r * pulse, 0.15)
        ctx.set_source_rgb(*(ERAS[4]["accent"] if listening else DUSK_OTHER))
        ctx.fill()
        # The mic glyph.
        ctx.save()
        ctx.translate(hx, hy)
        ctx.scale(r / 120, r / 120)
        rounded_rect_path(ctx, -22, -58, 44, 78, 22)
        ctx.set_source_rgb(*PAPER)
        ctx.fill()
        ctx.set_line_width(8)
        ctx.arc(0, -10, 40, 0.15, math.pi - 0.15)
        ctx.stroke()
        ctx.move_to(0, 30)
        ctx.line_to(0, 52)
        ctx.stroke()
        ctx.restore()
        # Each stretch's text lands when it has been recognised.
        size = 64
        for i, (line, at) in enumerate(said):
            if b < at:
                continue
            k = out_expo(span(b, at, at + 0.5))
            y = 330 + i * 86
            self.sans_bold.text(ctx, line, W / 2, y + (1 - k) * 40, size, PAPER, align=0.5, alpha=k * (1 - span(b, 110.6, 111.4)))
        # Pauses cut the stretches; each is recognised while the next is spoken.
        for i, (s0, s1) in enumerate(stretches):
            if b >= s1:
                done = span(b, s1, said[i][1])
                self.mono.text(ctx, f"STRETCH {i + 1} · RECOGNISING {'█' * int(done * 10):<10}" if done < 1 else f"STRETCH {i + 1} · IN", W - 70, 900 + i * 30, 20,
                               GREEN if done >= 1 else DIM, align=1, alpha=1 - span(b, 110.6, 111.2))
        # What runs it.
        facts = ["RUNS ON THE PHONE", "PARAKEET TDT 0.6B V3 · INT8", "25 EUROPEAN LANGUAGES", "PUNCTUATION AND CAPITALS INCLUDED"]
        for i, line in enumerate(facts):
            self.typed(ctx, self.mono, line, 70, 870 + i * 32, 21, b, 98.5 + i * 2.2, PAPER, rate=0.03, alpha=0.8 * (1 - span(b, 110.6, 111.2)))
        self.slam(ctx, self.heavy, "SPEAK", 70, 190, 118, b, 97.4, PAPER, stagger=0.05, exit_at=111.0)
        self.hud(ctx, b)

    # ------------------------------------------------ act 5: everything else

    def strip_bar(self, ctx, place, alpha=1.0):
        cx, cy, width = place
        top = cy - self.kb.height * width / self.kb.width / 2 - 26
        ctx.set_source_rgba(*DUSK_BG, alpha)
        rounded_rect_path(ctx, cx - width / 2 - 26, top - 64, width + 52, 94, 28)
        ctx.fill()
        return cx - width / 2 - 26, top - 64, width + 52, 64

    def poster(self, ctx, b, start, colour, big, line, ink=PAPER):
        ctx.set_source_rgb(*colour)
        ctx.paint()
        self.slam(ctx, self.heavy, big, 70, 200, 132, b, start + 0.05, ink, stagger=0.04, dur=0.45, exit_at=start + 3.6)
        self.typed(ctx, self.mono, line, 74, 252, 22, b, start + 0.3, ink, rate=0.025, alpha=0.85 * (1 - span(b, start + 3.6, start + 3.9)))

    def montage(self, ctx, b):
        bar = int((b - 112) // 4)
        start = 112 + bar * 4
        local = b - start
        place = (1240, 690, 680)
        if bar == 0:
            self.poster(ctx, b, start, (0.20, 0.24, 0.52), "PASSWORDS", "YOUR AUTOFILL SERVICE'S LOGINS, IN THE STRIP")
            self.hex_keyboard(ctx, place, b)
            x, y, w, h = self.strip_bar(ctx, place)
            chip = out_back(span(local, 0.3, 0.7))
            ctx.save()
            ctx.translate(x + w - 170, y + 32)
            ctx.scale(max(chip, 0.01), max(chip, 0.01))
            rounded_rect_path(ctx, -150, -22, 300, 44, 22)
            ctx.set_source_rgb(*mix(DUSK_OTHER, PAPER, 0.15))
            ctx.fill()
            self.sans.text(ctx, "sam@example.com", 0, 8, 22, PAPER, align=0.5)
            ctx.restore()
            # A login form on the left, filled by the chip.
            filled = span(local, 1.4, 1.6)
            for i, (label, value) in enumerate((("Email", "sam@example.com"), ("Password", "•" * 12))):
                fy = 430 + i * 130
                self.sans.text(ctx, label, 120, fy, 26, PAPER, alpha=0.7)
                rounded_rect_path(ctx, 120, fy + 18, 560, 70, 16)
                ctx.set_source_rgba(1, 1, 1, 0.12)
                ctx.fill()
                n = int(span(local, 1.5 + i * 0.3, 2.0 + i * 0.3) * len(value))
                self.sans.text(ctx, value[:n], 145, fy + 66, 30, PAPER)
            if 1.0 < local < 1.6:
                self.finger(ctx, x + w - 170, y + 32, 1, 1 - span(local, 1.45, 1.6))
        elif bar == 1:
            self.poster(ctx, b, start, (0.12, 0.40, 0.36), "CLIPBOARD", "WHAT YOU COPIED, ONE TAP AWAY · KEPT AN HOUR, OR PINNED")
            self.hex_keyboard(ctx, place, b)
            x, y, w, h = self.strip_bar(ctx, place)
            pill = out_back(span(local, 0.3, 0.7))
            if local < 1.6:
                ctx.save()
                ctx.translate(x + w / 2, y + 32)
                ctx.scale(max(pill, 0.01), max(pill, 0.01))
                rounded_rect_path(ctx, -200, -22, 400, 44, 22)
                ctx.set_source_rgb(*mix(DUSK_OTHER, PAPER, 0.15))
                ctx.fill()
                self.sans.text(ctx, "📋  meet at 8?", 0, 8, 22, PAPER, align=0.5)
                ctx.restore()
                if 1.0 < local < 1.6:
                    self.finger(ctx, x + w / 2, y + 32, 1, 1 - span(local, 1.45, 1.6))
            text = "ok" + (" meet at 8?" if local >= 1.5 else "")
            self.sans_bold.text(ctx, text, 120, 480, 64, PAPER)
            # The clipboard panel: two clips, the pinned one stays.
            for i, (clip, pinned) in enumerate((("meet at 8?", False), ("IBAN DE89 3704 …", True))):
                k = out_expo(span(local, 2.0 + i * 0.2, 2.5 + i * 0.2))
                cy_ = 600 + i * 90
                rounded_rect_path(ctx, 120 - (1 - k) * 600, cy_, 560, 70, 16)
                ctx.set_source_rgba(1, 1, 1, 0.14)
                ctx.fill()
                self.sans.text(ctx, clip, 145 - (1 - k) * 600, cy_ + 46, 28, PAPER)
                self.mono.text(ctx, "PINNED" if pinned else "1 H", 660 - (1 - k) * 600, cy_ + 44, 18, PAPER, align=1, alpha=0.8)
        elif bar == 2:
            self.poster(ctx, b, start, (0.40, 0.22, 0.46), "EMOJI", "FLICK UP ON @#€ · SEARCH THEM IN EVERY LANGUAGE")
            emoji = "😀😂🥹😍🤔😎🥳😭👍🙏🔥✨🎉❤️🐝🍯🌍🍕☕🚀🎈🌈🐱🐶🦊🐢🍀⚡🎧📷💡🧩🔑⏰🧡💜💙💚"
            emoji = [e for e in emoji if e not in "️"]
            burst = out_expo(span(local, 0.4, 1.4))
            r = 62
            cells = []
            for row in range(-3, 4):
                for col in range(-4, 5):
                    cells.append((col * r * math.sqrt(3) + (r * math.sqrt(3) / 2 if row % 2 else 0), row * r * 1.5))
            cells.sort(key=lambda c: math.hypot(*c))
            for i, ((px, py), e) in enumerate(zip(cells, emoji)):
                d = math.hypot(px, py) / 500
                k = out_back(span(local, 0.4 + d * 0.6, 0.8 + d * 0.6), 2.2)
                if k <= 0:
                    continue
                gx, gy = 1240 + px * k, 640 + py * k
                rounded_hexagon(ctx, gx, gy, r * 0.92 * min(k, 1.2), 0.15)
                ctx.set_source_rgba(1, 1, 1, 0.12)
                ctx.fill()
                self.emoji.text(ctx, e, gx, gy + 18, 50 * min(k, 1.2), PAPER, align=0.5)
            # The search: "bee".
            q = "bee"[:int(span(local, 2.0, 2.5) * 3)]
            if local > 1.9:
                rounded_rect_path(ctx, 120, 420, 520, 76, 38)
                ctx.set_source_rgba(1, 1, 1, 0.16)
                ctx.fill()
                self.sans.text(ctx, "🔍" if False else q, 160, 470, 34, PAPER)
                if len(q) == 3:
                    for i, e in enumerate("🐝🐞🪲"):
                        k = out_back(span(local, 2.6 + i * 0.12, 3.0 + i * 0.12))
                        self.emoji.text(ctx, e, 190 + i * 140, 640, 96 * max(k, 0.01), PAPER, align=0.5)
        elif bar == 3:
            themes = [("DUSK", (0.106, 0.106, 0.122), DUSK_ROWS, DUSK_OTHER, DUSK_LABEL),
                      ("PAPER", (0.894, 0.894, 0.918), [(0.780, 0.843, 0.965), (0.769, 0.910, 0.831), (0.957, 0.812, 0.855)], (0.984, 0.984, 0.992), (0.106, 0.106, 0.122)),
                      ("EMBER", (0.118, 0.102, 0.094), [(0.353, 0.227, 0.133), (0.306, 0.165, 0.165), (0.247, 0.227, 0.133)], (0.227, 0.204, 0.192), (0.953, 0.925, 0.902)),
                      ("PLAIN", (0.106, 0.106, 0.122), [(0.227, 0.227, 0.259)] * 3, (0.227, 0.227, 0.259), (0.910, 0.910, 0.933))]
            i = min(3, int(local))
            name, bg, rows, other, label = themes[i]
            ctx.set_source_rgb(*mix(bg, PAPER if luminance(bg) < 0.5 else INK, 0.06))
            ctx.paint()
            ink = label
            self.slam(ctx, self.heavy, "THEMES", 70, 200, 132, b, start + 0.05, ink, stagger=0.04, dur=0.45, exit_at=start + 3.6)
            self.giant(ctx, name, W / 2, 980, 300, 0.25, colour=ink, stroke=3)
            self.theme_keyboard(ctx, (960, 620, 640), b, local, themes, i)
        elif bar == 4:
            self.poster(ctx, b, start, (0.92, 0.92, 0.94), "PUNCTUATION", "FLICK UP ON . FOR , · ON ? FOR ! · ON ↵ FOR THE FIELD'S ACTION", ink=INK)
            self.hex_keyboard(ctx, place, b)
            flicks = [(0.5, ".", ","), (1.3, "?", "!"), (2.1, "⏎", "send")]
            text = "wait"
            for t, key, out in flicks:
                if local >= t + 0.15:
                    text += {",": ",", "!": " really!", "send": ""}[out]
                x, y = self.key_xy(key, place)
                k = span(local, t, t + 0.6)
                if 0 < k < 1:
                    m = out_expo(span(local, t, t + 0.2))
                    self.finger(ctx, x, y - m * 50, 1, 1 - span(local, t + 0.15, t + 0.3))
                    self.sans_bold.text(ctx, out, x, y - 40 - out_expo(k) * 90, 54, INK, align=0.5, alpha=1 - k)
            self.sans_bold.text(ctx, text, 120, 520, 72, INK)
            if local >= 2.3:
                k = out_back(span(local, 2.3, 2.7))
                rounded_rect_path(ctx, 120, 600, 280 * k, 80, 40)
                ctx.set_source_rgb(*ERAS[4]["accent"])
                ctx.fill()
                self.sans_bold.text(ctx, "SENT", 260, 652, 34, PAPER, align=0.5, alpha=span(local, 2.5, 2.7))
        elif bar == 5:
            self.poster(ctx, b, start, (0.043, 0.043, 0.051), "OPEN SOURCE", "RUST CORE · KOTLIN SHELL · 83 TESTS · 2 DEPENDENCIES")
            # The rule this whole trailer started with, scrolling past as code.
            code = self.source_lines
            off = local * 160
            for i, line in enumerate(code):
                y = 360 + i * 30 - off
                if 300 < y < H - 60:
                    hl = "FLICK" in line or "DOWN_TAP" in line or "DELETE_FIRST" in line
                    self.mono.text(ctx, line[:90], 120, y, 21, GREEN if hl else DIM, alpha=0.95 if hl else 0.6)
        elif bar == 6:
            self.poster(ctx, b, start, (0.86, 0.44, 0.30), "YOURS", "NO ACCOUNT · NO ADS · NETWORK ONLY TO DOWNLOAD DICTIONARIES AND THE VOICE MODEL", ink=INK)
            items = ["NO ACCOUNT", "NO ADS", "NO TRACKERS"]
            for i, item in enumerate(items):
                k = out_expo(span(local, 0.6 + i * 0.5, 1.0 + i * 0.5))
                self.heavy.text(ctx, item, 120 + (1 - k) * 200, 500 + i * 150, 120, INK, alpha=k)
                # Struck through, on the beat.
                s_ = out_expo(span(local, 0.9 + i * 0.5, 1.1 + i * 0.5))
        elif bar == 7:
            names = ["FLICK", "SLIDE", "GLIDE", "SWIPE", "HOLD", "47", "SPEAK", "HEX"]
            i = min(7, int(local * 2))
            colours = [BLUE, GREEN, PURPLE, (0.86, 0.44, 0.30), BLUE, GREEN, PURPLE, INK]
            ctx.set_source_rgb(*colours[i])
            ctx.paint()
            word = names[i]
            k = out_expo(span(local, i / 2, i / 2 + 0.3))
            size = 420 * (0.85 + 0.15 * k)
            self.heavy.text(ctx, word, W / 2, H / 2 + size * 0.35, size, PAPER, align=0.5, tracking=-0.03)
        self.hud(ctx, b)

    def theme_keyboard(self, ctx, place, b, local, themes, i):
        cx, cy, width = place
        kb = self.kb
        k = width / kb.width
        h = kb.height * k
        x0, y0 = cx - width / 2, cy - h / 2
        _, bg, rows, other, label = themes[i]
        ctx.set_source_rgb(*bg)
        rounded_rect_path(ctx, x0 - 26, y0 - 26, width + 52, h + 52, 28)
        ctx.fill()
        for key in kb.keys:
            kx, ky = key["centre"]
            # Each key flips to the new theme on a wave from the left.
            flip = span(local - int(local), kx / kb.width * 0.4, kx / kb.width * 0.4 + 0.3)
            ctx.save()
            ctx.translate(x0 + kx * k, y0 + ky * k)
            ctx.scale(k * max(0.05, abs(math.cos(math.pi * clamp(flip)))) if local >= 1 else k, k)
            ctx.translate(-kx, -ky)
            polygon(ctx, key["outline"])
            colour = rows[key["row"]] if key["row"] is not None else other
            ctx.set_source_rgb(*colour)
            ctx.fill()
            text = {"⏎": "↵", "space": "", "123": "@#€"}.get(key["label"], key["label"])
            if text:
                size = kb.cell * (0.36 if len(text) == 1 else 0.22)
                self.sans.text(ctx, text, kx, ky + size * 0.36, size, label, align=0.5)
            ctx.restore()

    # ------------------------------------------------ the end

    def end(self, ctx, b):
        local = b - 144
        ctx.set_source_rgb(*INK)
        ctx.paint()
        self.logo(ctx, 48 + min(local, 3.3))
        self.typed(ctx, self.mono, "github.com/nanbiz/hexkeys", W / 2, H * 0.88 + 50, 26, local, 1.8, PAPER, rate=0.06, align=0.5)
        fade = span(b, 150, 152.5)
        if fade > 0:
            ctx.set_source_rgba(*INK, fade)
            ctx.paint()

    def scene(self, ctx, t):
        b = t / self.beat
        ctx.set_source_rgb(*INK)
        ctx.paint()
        hit = 0.0
        if b < 33.0:
            self.history(ctx, b)
            for m in MORPH_AT:
                if m - 0.1 <= b:
                    hit = max(hit, 0.6 * math.exp(-(b - m) * 4))
        elif b < self.sections["drop"]:
            self.hexagons(ctx, b)
            hit = max(0.0, (b - 47.6) / 0.4) if b > 47.6 else 0.0
        elif b < 52:
            self.logo(ctx, b)
            hit = math.exp(-(b - 48) * 3)
            self.hud(ctx, b)
        elif b < 60:
            self.flick(ctx, b)
        elif b < 68:
            self.delete(ctx, b)
        elif b < 76:
            self.cursor(ctx, b)
        elif b < 84:
            self.swipe(ctx, b)
        elif b < 90:
            self.hold(ctx, b)
        elif b < 96:
            ctx.set_source_rgb(0.93, 0.93, 0.95)
            ctx.paint()
            self.languages(ctx, b)
        elif b < 112:
            self.voice(ctx, b)
        elif b < 144:
            self.montage(ctx, b)
            if b < 113:
                hit = math.exp(-(b - 112) * 3)
        else:
            self.end(ctx, b)
            if b < 145:
                hit = math.exp(-(b - 144) * 3)
        self.iris(ctx, b)
        return b, hit

    def iris(self, ctx, b):
        """Between gestures: a hexagon of colour swallows the scene, and the
        next one opens out of a hexagonal hole in it."""
        for i, m in enumerate((52, 60, 68, 76, 84, 90, 96)):
            colour = flow(i / 6)
            if m - 0.3 < b < m:
                r = in_expo(span(b, m - 0.3, m)) * 1250
                hexagon(ctx, W / 2, H / 2, r, (b - m) * 1.5)
                ctx.set_source_rgb(*colour)
                ctx.fill()
            elif m <= b < m + 0.35:
                r = out_expo(span(b, m, m + 0.35)) * 1250
                ctx.rectangle(0, 0, W, H)
                hexagon(ctx, W / 2, H / 2, r, (b - m) * 1.5)
                ctx.set_fill_rule(cairo.FILL_RULE_EVEN_ODD)
                ctx.set_source_rgb(*colour)
                ctx.fill()
                ctx.set_fill_rule(cairo.FILL_RULE_WINDING)

    # ------------------------------------------------ frames

    def frame(self, t, scale):
        surface = cairo.ImageSurface(cairo.FORMAT_RGB24, int(W * scale), int(H * scale))
        ctx = cairo.Context(surface)
        ctx.scale(scale, scale)
        b, hit = self.scene(ctx, t)
        surface.flush()
        rgb = np.ndarray((surface.get_height(), surface.get_width(), 4), np.uint8, surface.get_data())[:, :, [2, 1, 0]].astype(np.float32) / 255
        return self.finish(rgb, b, hit, t)

    def finish(self, rgb, b, hit, t):
        """Glow, a colour split and a flash on the hits, grain and a vignette."""
        h, w, _ = rgb.shape
        small = rgb[::4, ::4]
        bright = np.clip(small - 0.6, 0, None)
        # Bright scenes glow less, or a white keyboard turns into fog.
        glow = ndimage.gaussian_filter(bright, (6, 6, 0)) * (1 - clamp(float(small.mean()) * 1.6))
        glow = np.repeat(np.repeat(glow, 4, 0), 4, 1)[:h, :w]
        out = rgb + glow * 1.2
        if hit > 0.02:
            shift = int(12 * hit * w / W)
            if shift:
                out[:, shift:, 0] = out[:, :-shift, 0]
                out[:, :-shift, 2] = out[:, shift:, 2]
            out = out + hit * 0.25
        grain = self.grain[int(t * FPS) % len(self.grain)]
        grain = np.repeat(np.repeat(grain, 2, 0), 2, 1)[:h, :w] if grain.shape[0] * 2 >= h else ndimage.zoom(grain, h / grain.shape[0])[:h, :w]
        out += grain[:, :, None] * 0.016
        yy, xx = np.mgrid[0:h, 0:w]
        vignette = 1 - 0.3 * (((xx / w - 0.5) ** 2 + (yy / h - 0.5) ** 2) * 2.2)
        out *= vignette[:, :, None]
        return (np.clip(out, 0, 1) * 255).astype(np.uint8)


def main():
    args = sys.argv[1:]
    outlines, beats, fonts, out = args[:4]
    preview = "--preview" in args
    start = float(args[args.index("--from") + 1]) if "--from" in args else 0.0
    film = Film(outlines, beats, fonts)
    end = float(args[args.index("--to") + 1]) if "--to" in args else film.length
    scale = 0.5 if preview else 1.0
    if "--sheet" in args:
        sheet = args[args.index("--sheet") + 1]
        count = int(args[args.index("--count") + 1]) if "--count" in args else 24
        size = float(args[args.index("--size") + 1]) if "--size" in args else 0.25
        cols = 6 if count >= 12 else 3 if count > 4 else 2
        times = [float(x) for x in args[args.index("--at") + 1].split(",")] if "--at" in args else np.linspace(start, end, count, endpoint=False)
        if "--at" in args:
            cols = 2 if len(times) > 1 else 1
        tiles = [film.frame(t, size) for t in times]
        while len(tiles) % cols:
            tiles.append(np.zeros_like(tiles[0]))
        rows = [np.concatenate(tiles[i:i + cols], 1) for i in range(0, len(tiles), cols)]
        image = np.concatenate(rows, 0)
        surface = cairo.ImageSurface(cairo.FORMAT_RGB24, image.shape[1], image.shape[0])
        data = np.ndarray((image.shape[0], image.shape[1], 4), np.uint8, surface.get_data())
        data[:, :, 0], data[:, :, 1], data[:, :, 2] = image[:, :, 2], image[:, :, 1], image[:, :, 0]
        surface.mark_dirty()
        surface.write_to_png(sheet)
        return
    w, h = int(W * scale), int(H * scale)
    ffmpeg = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(FPS),
                               "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", "-preset", "medium", out], stdin=subprocess.PIPE)
    for i in range(int(start * FPS), int(end * FPS)):
        ffmpeg.stdin.write(film.frame(i / FPS, scale).tobytes())
    ffmpeg.stdin.close()
    ffmpeg.wait()


if __name__ == "__main__":
    main()
