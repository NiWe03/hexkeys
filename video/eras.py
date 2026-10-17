"""The keyboards of the trailer's history, as shapes that morph into each other.

Every era's layout gives each of the 26 letters a key outline and a centre in
screen coordinates, in one box in the middle of the frame, so a letter can
travel from its 1873 typewriter key to its hexagon without a cut. Outlines
are resampled to the same number of points, starting from the top and
running clockwise, so any two of them interpolate cleanly.
"""

import math

from shapely.geometry import Polygon

POINTS = 64
LETTERS = "abcdefghijklmnopqrstuvwxyz"


def resample(points, n=POINTS):
    """`points` as `n` points evenly spaced along the outline, clockwise on
    screen (y down), starting at the one straight above the centre."""
    pts = list(points)
    if pts[0] == pts[-1]:
        pts = pts[:-1]
    area = sum(pts[i][0] * pts[(i + 1) % len(pts)][1] - pts[(i + 1) % len(pts)][0] * pts[i][1] for i in range(len(pts)))
    if area < 0:
        pts = pts[::-1]
    cx = sum(p[0] for p in pts) / len(pts)
    cy = sum(p[1] for p in pts) / len(pts)
    edges = [(pts[i], pts[(i + 1) % len(pts)]) for i in range(len(pts))]
    lengths = [math.dist(a, b) for a, b in edges]
    total = sum(lengths)
    out = []
    for k in range(n):
        d = total * k / n
        for (a, b), length in zip(edges, lengths):
            if d <= length or length == 0:
                f = d / length if length else 0
                out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
                break
            d -= length
    # Start at the point most nearly straight above the centre.
    start = min(range(n), key=lambda i: abs(math.atan2(out[i][0] - cx, -(out[i][1] - cy))))
    return out[start:] + out[:start]


def circle(cx, cy, r):
    return [(cx + r * math.cos(2 * math.pi * i / POINTS - math.pi / 2), cy + r * math.sin(2 * math.pi * i / POINTS - math.pi / 2)) for i in range(POINTS)]


def rounded_rect(cx, cy, w, h, r):
    return resample(list(Polygon([(cx - w / 2, cy - h / 2), (cx + w / 2, cy - h / 2), (cx + w / 2, cy + h / 2), (cx - w / 2, cy + h / 2)])
                         .buffer(-r, join_style=2).buffer(r, resolution=8).exterior.coords))


def space_bar(keys, units, height):
    """A space bar `units` keys wide and `height` keys high, centred under
    the keys' last row."""
    xs = [p[0] for _, o in keys for p in o]
    last = max(centre(o)[1] for _, o in keys)
    unit = math.dist(centre(keys[0][1]), centre(keys[1][1]))
    cx = (min(xs) + max(xs)) / 2
    cy = last + unit * (0.5 + height / 2) + unit * 0.12
    return rounded_rect(cx, cy, unit * units, unit * height, unit * min(height, 1) * 0.2)


def centre(points):
    return sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points)


def morph(a, b, x):
    return [(pa[0] + (pb[0] - pa[0]) * x, pa[1] + (pb[1] - pa[1]) * x) for pa, pb in zip(a, b)]


class Layout:
    """An era's keyboard: its letter keys, its other keys, and how it looks."""

    def __init__(self, name, year, keys, extras, look):
        self.name, self.year = name, year
        self.keys = keys          # letter -> outline
        self.extras = extras      # [(label, outline)]
        self.look = look          # colours and sizes


def grid(rows, offsets, unit, gap, shape, box):
    """Keys of `rows` (strings), each row shifted by its offset in key units;
    `shape(cx, cy, size)` draws one; the whole fitted into `box` (x, y, w)."""
    raw = []
    for r, (row, off) in enumerate(zip(rows, offsets)):
        for c, label in enumerate(row):
            raw.append((label, (off + c) * unit, r * unit))
    width = max(x for _, x, _ in raw) + unit
    bx, by, bw = box
    k = bw / width
    out = []
    for label, x, y in raw:
        out.append((label, shape(bx + (x + unit / 2) * k, by + (y + unit / 2) * k, (unit - gap) * k)))
    return out


def typewriter(box):
    """Sholes & Glidden, 1873: the first QWERTY, four staggered rows of round
    keys, with the full stop where R is today and R down in the bottom row."""
    rows = ["23456789-,", "qwe.tyiuop", "zsdfghjklm", "ax&cvbn?;r"]
    keys = grid(rows, [0, 0.5, 1.0, 1.5], 1.0, 0.22, lambda x, y, s: circle(x, y, s / 2), box)
    keys.append(("space", space_bar(keys, 5.0, 0.45)))
    look = {"bg": (0.91, 0.87, 0.79), "key": (0.10, 0.09, 0.08), "ring": (0.75, 0.72, 0.66), "label": (0.96, 0.94, 0.90), "size": 0.42}
    return Layout("SHOLES & GLIDDEN TYPE-WRITER", 1873, {l: o for l, o in keys if l in LETTERS},
                  [(l, o) for l, o in keys if l not in LETTERS], look)


def buckling(box):
    """A 1984 buckling-spring keyboard: square beige caps on a stepped grid."""
    rows = ["1234567890", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./"]
    keys = grid(rows, [0, 0.5, 0.75, 1.25], 1.0, 0.1, lambda x, y, s: rounded_rect(x, y, s, s, s * 0.08), box)
    keys.append(("space", space_bar(keys, 6.0, 0.9)))
    look = {"bg": (0.72, 0.69, 0.63), "key": (0.89, 0.86, 0.79), "ring": (0.62, 0.59, 0.53), "label": (0.20, 0.19, 0.17), "size": 0.34}
    return Layout("BUCKLING-SPRING KEYBOARD", 1984, {l: o for l, o in keys if l in LETTERS},
                  [(l, o) for l, o in keys if l not in LETTERS], look)


def keypad(box):
    """A 1997 phone keypad: twelve keys, three or four letters on each, T9
    picking the word: 4-3-9 is "hex"."""
    digits = ["1", "2abc", "3def", "4ghi", "5jkl", "6mno", "7pqrs", "8tuv", "9wxyz", "*", "0", "#"]
    bx, by, bw = box
    kw, kh = bw / 3 * 0.42, bw / 3 * 0.26
    left = bx + bw / 2 - 1.5 * kw * 1.18
    keys, extras = {}, []
    for i, d in enumerate(digits):
        cx = left + (i % 3 + 0.5) * kw * 1.18
        cy = by + (i // 3 + 0.5) * kh * 1.3
        extras.append((d[0], rounded_rect(cx, cy, kw, kh, kh * 0.45)))
        letters = d[1:]
        for j, l in enumerate(letters):
            lx = cx + (j - (len(letters) - 1) / 2) * kw * 0.2
            keys[l] = rounded_rect(lx, cy + kh * 0.22, kw * 0.17, kh * 0.32, kh * 0.08)
    look = {"bg": (0.62, 0.69, 0.52), "key": (0.17, 0.19, 0.17), "ring": (0.30, 0.33, 0.30), "label": (0.80, 0.86, 0.70), "size": 0.9}
    return Layout("PHONE KEYPAD · T9", 1997, keys, extras, look)


def touch(box):
    """A 2007 touchscreen QWERTY: small rounded keys under glass."""
    rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
    keys = grid(rows, [0, 0.5, 1.5], 1.0, 0.14, lambda x, y, s: rounded_rect(x, y, s, s * 1.35, s * 0.16), box)
    keys.append(("space", space_bar(keys, 5.0, 1.35)))
    look = {"bg": (0.82, 0.84, 0.87), "key": (0.99, 0.99, 1.0), "ring": (0.70, 0.72, 0.76), "label": (0.08, 0.08, 0.10), "size": 0.5}
    return Layout("TOUCHSCREEN", 2007, {l: o for l, o in keys if l in LETTERS},
                  [(l, o) for l, o in keys if l not in LETTERS], look)


def hexkeys(keyboard, box):
    """Our keyboard, from the app's outlines, fitted into the same box."""
    bx, by, bw = box
    k = bw / keyboard.width
    def place(outline):
        return resample([(bx + x * k, by + y * k) for x, y in outline])
    keys = {key["label"]: place(key["outline"]) for key in keyboard.keys if key["label"] in LETTERS}
    extras = [(key["label"], place(key["outline"])) for key in keyboard.keys if key["label"] not in LETTERS]
    look = {"bg": (0.043, 0.043, 0.051), "key": None, "ring": None, "label": (0.91, 0.91, 0.93), "size": 0.42}
    return Layout("HEXKEYS", 2026, keys, extras, look)
