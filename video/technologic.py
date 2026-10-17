"""The trailer cut to a song's first 36 bars: one area of focus that the
camera follows, every hit on a sung word's onset, words of our own set at
the focus instead of in a corner.

Usage: video/build.sh, which supplies the arguments:
  python3 -I video/technologic.py OUTLINES TIMINGS FONTS_DIR OUT.mp4 [--from S] [--to S] [--sheet SHEET.png] [--at S,S,...]

TIMINGS is the word timing file the alignment wrote (video/out/track/
verse1.json): the song's beats, and per bar the onset of every sung word.
Only the onsets are used; the words themselves never reach the screen.

The cut is 16 bars, about 30 seconds: song bars 12 to 15 the keyboards of
the past take a bar each (1873 typewriter, 1985 buckling spring, 1999 keypad,
2007 touchscreen swiping), bar 16 everything morphs into hexkeys as the beat
comes in, bars 16 to 23 show one thing it does per bar, and then the song
jumps on a downbeat to its chorus, bars 32 to 35, which builds the icon in
four hits. Video time maps onto song time through CUT.
"""

import json
import math
import pathlib
import subprocess
import sys

import cairo
import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import eras  # noqa: E402
import render  # noqa: E402
from render import (  # noqa: E402
    BLUE, DIM, DUSK_BG, DUSK_LABEL, DUSK_OTHER, DUSK_ROWS, ERAS, FLOW, FPS, GREEN, H, INK, PANELS, PAPER, PURPLE, W,
    along, bbox, clamp, flow, hexagon, in_expo, in_out_cubic, in_out_expo, lerp, luminance, mix, out_back,
    out_cubic, out_expo, polygon, rounded_hexagon, rounded_rect_path, smooth_path, span, spring, turn,
)

# Bars where the next keyboard takes over: typewriter, buckling spring,
# keypad, touchscreen, hexkeys. Bar 12 keeps the touchscreen and turns it to
# swipe typing.
ERA_BARS = [25, 26, 27, 28]
FIRST_BAR = 24
HEX_BAR = 28
CHORUS_BAR = 32
DROP_BAR = 36
END_BAR = 38
# What each hexkeys bar shows; the chorus bars carry one sung word each, so
# their hits fall on the beats.
FEATURES = {28: "arrival", 29: "slide", 30: "swipe", 31: "hold", 32: "flick", 33: "glide", 34: "languages", 35: "speak"}

YEARS = ["1873", "1985", "1999", "2007", "2026"]
NAMES = ["TYPE-WRITER", "BUCKLING SPRING", "T9 KEYPAD", "TOUCHSCREEN", "HEXKEYS"]

# What each era types, one character per sung word: its own text. In BASIC
# ? is short for PRINT; on the keypad 4-3-9 is "hey" before * makes it "hex".
SCRIPTS = [
    "HEXAGON.",
    '?"HEX"\n',
    "439*0439",
]
T9 = ["i", "he", "hey", "hex", "hex ", "i", "he", "hey"]


class Song:
    """The beat grid and the onsets of the sung words, in seconds."""

    def __init__(self, path):
        data = json.loads(pathlib.Path(path).read_text())
        self.beats = data["beats"]
        self.bars = []
        for line in data["lines"]:
            onsets = sorted(w["t"] for w in line["words"] if w.get("t") is not None)
            self.bars.append({"start": line["start"], "end": line["end"], "onsets": onsets})

    def bar_time(self, bar):
        return self.beats[4 * bar]

    def bar_at(self, t):
        for i in range(len(self.beats) // 4 - 1):
            if t < self.beats[4 * (i + 1)]:
                return i, span(t, self.beats[4 * i], self.beats[4 * (i + 1)])
        return len(self.beats) // 4 - 1, 1.0

    def onsets(self, first, last):
        """Every word onset from bar `first` up to, not including, bar `last`."""
        return [t for b in self.bars[first:last] for t in b["onsets"]]

    def hits(self, bar):
        """The onsets of a bar's main words, every second word; its beats
        where it has fewer than four words."""
        onsets = self.bars[bar]["onsets"] if bar < len(self.bars) else []
        return onsets[::2] if len(onsets) >= 8 else self.beats[4 * bar:4 * bar + 4]

    def eighths(self, bar):
        """A bar's word onsets, or its eighth notes where it has fewer than eight words."""
        onsets = self.bars[bar]["onsets"] if bar < len(self.bars) else []
        if len(onsets) >= 8:
            return onsets
        b = self.beats[4 * bar:4 * bar + 5]
        return [v for i in range(4) for v in (b[i], (b[i] + b[i + 1]) / 2)]


class Camera:
    """Where the frame looks: a centre, a zoom and a tilt, eased between
    keyframes so the area of focus glides from scene to scene."""

    def __init__(self, keys):
        self.keys = sorted(keys)

    def at(self, t):
        keys = self.keys
        if t <= keys[0][0]:
            return keys[0][1:]
        for (t0, *a), (t1, *c) in zip(keys, keys[1:]):
            if t < t1:
                x = in_out_cubic(span(t, t0, t1))
                return tuple(lerp(u, v, x) for u, v in zip(a, c))
        return keys[-1][1:]

    def apply(self, ctx, t):
        cx, cy, zoom, tilt = self.at(t)
        ctx.translate(W / 2, H / 2)
        ctx.rotate(tilt)
        ctx.scale(zoom, zoom)
        ctx.translate(-cx, -cy)


class Cut(render.Film):
    def song_time(self, v):
        """The song's time at video time v: one stretch from bar 24 on."""
        return self.song.bar_time(FIRST_BAR) + v

    def frame(self, v, scale):
        return super().frame(self.song_time(v), scale)

    def __init__(self, outlines, timings, fonts):
        song = Song(timings)
        beat = (song.beats[-1] - song.beats[0]) / (len(song.beats) - 1)
        # The parent wants a beat map; give it one from the song's grid.
        beat_map = pathlib.Path("/tmp/hexkeys-beats.json")
        beat_map.write_text(json.dumps({"beat": beat, "sections": [{"name": "song", "bar": 0}]}))
        super().__init__(outlines, beat_map, fonts)
        self.song = song
        self.length = song.bar_time(END_BAR) - song.bar_time(FIRST_BAR) + 0.7
        self.morph_at = [song.bar_time(b) for b in ERA_BARS]
        self.events = self.typing()
        self.camera = self.shots()

    # ------------------------------------------------ timing

    def typing(self):
        """Key strikes of the past, one per sung word, each era typing its script."""
        events = []
        spans = [(24, 25), (25, 26), (26, 27)]
        for era, ((first, last), script) in enumerate(zip(spans, SCRIPTS)):
            for t, ch in zip(self.song.onsets(first, last), script):
                events.append((t, ch, era))
        return events

    def shots(self):
        """The camera's path: per era it settles on the screen and the keys
        together, leans in on the bars that need it, and at hexkeys glides
        down to the keyboard and back up to the icon."""
        s = self.song.bar_time
        keys = [
            (s(24) - 0.5, 960, 520, 1.12, -0.02),
            (s(25) - 0.2, 960, 520, 1.04, 0.0),
            (s(25) + 0.3, 960, 510, 1.1, 0.012),
            (s(26) - 0.2, 960, 540, 1.02, 0.0),
            (s(26) + 0.3, 960, 540, 1.1, -0.01),
            (s(27) - 0.2, 960, 560, 1.04, 0.0),
            (s(27) + 0.4, 960, 600, 1.14, 0.01),
            (s(28) - 0.3, 960, 560, 1.0, 0.0),
            # hexkeys: the camera leans towards each bar's gesture.
            (s(28) + 0.6, 960, 560, 1.0, 0.0),
            (s(29), 1000, 470, 1.16, 0.0),
            (s(30) - 0.1, 1000, 470, 1.2, -0.006),
            (s(30) + 0.3, 960, 600, 1.0, 0.0),
            (s(31) - 0.1, 960, 600, 1.04, 0.0),
            (s(31) + 0.3, 960, 520, 1.12, 0.0),
            (s(32) - 0.1, 960, 540, 1.06, 0.0),
            (s(32) + 1.6, 960, 560, 1.12, 0.006),
            (s(33), 960, 450, 1.2, 0.0),
            (s(34) - 0.1, 960, 470, 1.14, 0.0),
            (s(34) + 0.3, 960, 640, 1.08, -0.008),
            (s(35) - 0.1, 960, 600, 1.0, 0.0),
            (s(35) + 0.4, 960, 500, 1.0, 0.0),
            (s(36), 960, 540, 1.0, 0.0),
            (s(38), 960, 540, 1.07, 0.0),
        ]
        return Camera(keys)

    # ------------------------------------------------ the past

    def era_progress(self, i, nx, t):
        m = self.morph_at[i]
        if i == 3:
            return in_out_cubic(span(t, m - 0.9 + 0.7 * nx, m - 0.9 + 0.7 * nx + 0.8))
        return in_out_cubic(span(t, m - 0.32 + 0.38 * nx, m - 0.32 + 0.38 * nx + 0.42))

    def era_at(self, t):
        """The era and how far it has gone into the next, for backgrounds and panels."""
        for i, m in enumerate(self.morph_at):
            if t < m + 0.25:
                lead = 0.9 if i == 3 else 0.32
                return i, in_out_cubic(span(t, m - lead, m + 0.25))
        return 4, 0.0

    def look(self, i, p, field):
        a, c = ERAS[i][field], ERAS[min(4, i + 1)][field]
        if isinstance(a, (int, float)):
            return lerp(a, c, p)
        return mix(a, c, p)

    def strike(self, label, t):
        q = 0.0
        for at, ch, _ in self.events:
            if at > t:
                break
            if render.key_of(ch) == label:
                q = max(q, math.exp(-(t - at) * 16))
        return q

    def keys_of_past(self, ctx, t, touches=()):
        """Every letter key at time t, morphing from era to era, each era's
        other keys leaving and arriving around them."""
        eras_ = self.eras
        x0, _, bw = render.BOX
        era, ep = self.era_at(t)
        ca, cb = eras_[era].bbox, eras_[min(4, era + 1)].bbox
        box = [lerp(u, v, ep) for u, v in zip(ca, cb)]
        pad = lerp(34, 26, ep)
        ctx.set_source_rgb(*self.look(era, ep, "case"))
        rounded_rect_path(ctx, box[0] - pad, box[1] - pad, box[2] - box[0] + 2 * pad, box[3] - box[1] + 2 * pad, self.look(era, ep, "radius"))
        ctx.fill()
        for i, e in enumerate(eras_):
            gone = out_cubic(span(t, self.morph_at[i] - 0.35, self.morph_at[i] - 0.05)) if i < 4 else 0
            if i > 0 and t < self.morph_at[i - 1] + 0.1:
                continue
            come = out_back(span(t, self.morph_at[i - 1] + 0.1, self.morph_at[i - 1] + 0.5), 2.0) if i > 0 else 1
            s = (1 - gone) * come
            if s <= 0.01:
                continue
            look = ERAS[i]
            for label, outline in e.extras:
                cx, cy = eras.centre(outline)
                q = self.strike(label, t) if i < 4 else 0
                shape = turn(outline, cx, cy, 0, max(0.01, s * (1 - 0.06 * q)), 0, look["skirt"] * q * 0.6)
                self.key_shape(ctx, shape, look, look["extra"], q, 1, i)
                text = "" if label == "space" else label
                if i == 2 and not label.isdigit() and label not in "*#":
                    text = ""
                size = (36 if i == 2 else 24 if len(text) == 1 else 18) * s
                if i == 4:
                    text = {"⏎": "↵", "space": ""}.get(label, label)
                    size = (0.36 if len(text) == 1 else 0.22) * self.kb.cell * render.HEX_BOX[2] / self.kb.width * s
                if text:
                    self.font(look["font"]).text(ctx, text, cx, cy + look["skirt"] * q * 0.6 + size * 0.36 - (14 if i == 2 else 0), size, look["label"], align=0.5)
        for letter in eras.LETTERS:
            outline = eras_[0].keys[letter]
            nx = (eras.centre(outline)[0] - x0) / bw
            shape, look_i, p = None, 4, 0
            for i in range(4):
                p = self.era_progress(i, nx, t)
                if p < 1:
                    shape = eras.morph(eras_[i].keys[letter], eras_[i + 1].keys[letter], p)
                    look_i = i
                    break
            if shape is None:
                shape, look_i, p = eras_[4].keys[letter], 4, 0
            cx, cy = eras.centre(shape)
            jump = math.sin(math.pi * p)
            spin = jump * 0.55 * (1 if letter in "aeiouybdfhkmpt" else -1)
            q = max(self.strike(letter, t), dict(touches).get(letter, 0))
            la, lc = ERAS[look_i], ERAS[min(4, look_i + 1)]
            skirt = lerp(la["skirt"], lc["skirt"], p)
            shape = turn(shape, cx, cy, spin, (1 + 0.22 * jump) * (1 - 0.07 * q), 0, -46 * jump + skirt * q * 0.6)
            cy += -46 * jump + skirt * q * 0.6
            ca_ = la["key"] or self.hex_colour[letter]
            cc_ = lc["key"] or self.hex_colour[letter]
            look = dict(la, skirt=skirt, ring=la["ring"] if p < 0.5 else lc["ring"], accent=mix(la["accent"], lc["accent"], p))
            self.key_shape(ctx, shape, look, mix(ca_, cc_, p), q, 1, look_i, ring_alpha=abs(1 - 2 * p))
            x1, y1, x2, y2 = bbox(shape)
            size = min(x2 - x1, y2 - y1) * lerp(eras_[look_i].look["size"], eras_[min(4, look_i + 1)].look["size"], p)
            for look_k, a_k in ((la, 1 - p), (lc, p)):
                if a_k <= 0.02:
                    continue
                ctx.save()
                ctx.translate(cx, cy)
                ctx.rotate(spin)
                self.font(look_k["font"]).text(ctx, letter.upper() if look_k["upper"] else letter, 0, size * 0.36, size,
                                               mix(la["label"], lc["label"], p), align=0.5, alpha=a_k)
                ctx.restore()

    def screen(self, ctx, t):
        """The era's screen above its keys, morphing with them."""
        era, ep = self.era_at(t)
        a, c = PANELS[era], PANELS[min(4, era + 1)]
        x, y, w, h, r = (lerp(u, v, ep) for u, v in zip(a[:5], c[:5]))
        if era == 0:
            x += self.carriage(t) * (1 - ep)
        ctx.save()
        if era == 0:
            ctx.set_source_rgba(0, 0, 0, 0.18)
            rounded_rect_path(ctx, x + 6, y + 8, w, h, r)
            ctx.fill()
        rounded_rect_path(ctx, x, y, w, h, r)
        ctx.set_source_rgb(*mix(a[5], c[5], ep))
        ctx.fill_preserve()
        ctx.clip()
        m = self.morph_at
        if era == 0:
            self.page(ctx, t, x, y, w, h, 1 - span(ep, 0, 0.3))
        if era == 1 or (era == 0 and ep > 0.6):
            self.green_screen(ctx, t, x, y, w, h, span(t, m[0] - 0.1, m[0] + 0.2) * (1 - span(t, m[1] - 0.3, m[1] - 0.1)))
        if era == 2 or (era == 1 and ep > 0.6):
            self.phone(ctx, t, x, y, w, h, span(t, m[1] - 0.1, m[1] + 0.2) * (1 - span(t, m[2] - 0.3, m[2] - 0.1)))
        if era == 3 or (era == 2 and ep > 0.6):
            self.glass_screen(ctx, t, x, y, w, h, span(t, m[2] - 0.1, m[2] + 0.2) * (1 - span(t, m[3] - 0.6, m[3] - 0.3)))
        ctx.restore()

    def typed_by(self, era, t):
        return "".join(ch for at, ch, e in self.events if e == era and at <= t)

    def carriage(self, t):
        typed = [at for at, ch, e in self.events if e == 0]
        n = sum(out_expo(span(t, at, at + 0.06)) for at in typed)
        ret = in_out_cubic(span(t, self.morph_at[0] - 0.45, self.morph_at[0] - 0.15))
        return -n * self.mono.advance("M", 30) * (1 - ret)

    def page(self, ctx, t, x, y, w, h, alpha):
        """1873: a page in capitals, struck a little off the line, as typebars do."""
        ink = (0.12, 0.11, 0.13)
        rng = np.random.default_rng(3)
        cx = x + 70
        ly = y + 120
        for ch in self.typed_by(0, t):
            ctx.save()
            ctx.translate(cx, ly + rng.normal(0, 1.6))
            ctx.rotate(rng.normal(0, 0.03))
            self.mono.text(ctx, ch, 0, 0, 30, ink, alpha=alpha * (0.75 + 0.25 * rng.random()))
            ctx.restore()
            cx += self.mono.advance(ch, 30)
        # The bell, before the margin, on the last word of the page.
        last = self.song.hits(1)[-1]
        k = span(t, last, last + 0.6)
        if 0 < k < 1:
            self.heavy.text(ctx, "DING!", x + w - 50, y + h - 40 - 20 * out_back(k), 46, ERAS[0]["accent"], align=1, alpha=alpha * (1 - k))

    def green_screen(self, ctx, t, x, y, w, h, alpha):
        """1985: a BASIC program that prints HEX for ever."""
        if alpha <= 0:
            return
        green = (0.45, 1.0, 0.55)
        lines = ["READY."] + self.typed_by(1, t).split("\n")
        run_at = [at for at, ch, e in self.events if e == 1][-1]
        if t >= run_at:
            lines += ["HEX"] * int((t - run_at) * 20)
        lines = lines[-8:]
        for k, line in enumerate(lines):
            self.mono.text(ctx, line, x + 50, y + 70 + k * 40, 30, green, alpha=alpha)
        if t < run_at and int(t * 4) % 2 == 0:
            end = x + 50 + self.mono.width(lines[-1], 30)
            ctx.set_source_rgba(*green, alpha)
            ctx.rectangle(end + 2, y + 70 + (len(lines) - 1) * 40 - 24, 18, 28)
            ctx.fill()
        ctx.set_source_rgba(0, 0, 0, 0.35 * alpha)
        for sy in range(int(y), int(y + h), 4):
            ctx.rectangle(x, sy, w, 1.6)
        ctx.fill()

    def phone(self, ctx, t, x, y, w, h, alpha):
        """1999: T9 guessing the word as the keys go in, a snake at the bottom."""
        if alpha <= 0:
            return
        dark = (0.10, 0.14, 0.08)
        for k in range(4):
            ctx.set_source_rgba(*dark, alpha)
            ctx.rectangle(x + 40 + k * 12, y + 70 - k * 9, 8, 10 + k * 9)
            ctx.fill()
        presses = [at for at, ch, e in self.events if e == 2 and at <= t]
        shown = T9[len(presses) - 1] if presses else ""
        committed = "hex " if len(presses) >= 5 else ""
        current = shown if len(presses) >= 6 or len(presses) < 5 else ""
        wx, wy = x + 40, y + 180
        wx += self.pixels(ctx, committed, wx, wy, 5, dark, alpha)
        if current.strip():
            width = self.pixels(ctx, current.strip(), wx, wy, 5, dark, alpha)
            ctx.set_source_rgba(*dark, alpha)
            ctx.rectangle(wx, wy + 6, width, 6)
            ctx.fill()
        k = max(0.0, (t - self.song.bar_time(7)) * 8)
        for seg in range(8):
            ctx.set_source_rgba(*dark, alpha)
            ctx.rectangle(x + 30 + ((k - seg * 0.5) * 22) % (w - 60), y + h - 50, 18, 18)
            ctx.fill()

    def glass_screen(self, ctx, t, x, y, w, h, alpha):
        """2007: a message typed under glass; 2010: words swiped in."""
        if alpha <= 0:
            return
        dark = (0.07, 0.07, 0.09)
        self.sans.text(ctx, "Messages", x + w / 2, y + 60, 26, dark, align=0.5, alpha=alpha * 0.8)
        text = self.typed_by(3, t)
        swiped = [word for at, word in self.swipes() if at + 0.35 <= t]
        if swiped:
            text = text.rstrip() + " " + " ".join(swiped)
        self.sans.text(ctx, text, x + 40, y + 200, 40, dark, alpha=alpha)
        if int(t * 3) % 2 == 0:
            end = x + 40 + self.sans.width(text, 40)
            ctx.set_source_rgba(*ERAS[3]["accent"], alpha)
            ctx.rectangle(end + 3, y + 166, 4, 44)
            ctx.fill()

    def swipes(self):
        """2010's words, swiped on the first and third main word of the last bar of the past."""
        hits = self.song.hits(HEX_BAR - 1)
        return [(hits[0], "hex"), (hits[2], "keys")]

    def past(self, ctx, t):
        era, ep = self.era_at(t)
        ctx.set_source_rgb(*self.look(era, ep, "bg"))
        ctx.paint()
        ctx.save()
        self.camera.apply(ctx, t)
        # The year, huge behind the keyboard, rolling to the next.
        text = self.look(era, ep, "text")
        years = YEARS[:]
        sw = self.swipes()[0][0]
        if era == 3 and t >= sw:
            years[3] = "2010"
        y_from, y_to = years[era], years[min(4, era + 1)]
        roll = ep
        if era == 3:
            if t < sw + 0.6:
                y_from, y_to, roll = "2007", "2010", in_out_cubic(span(t, sw - 0.2, sw + 0.2))
            else:
                y_from = "2010"
        self.year_behind(ctx, y_from, y_to, roll, mix(text, self.look(era, ep, "bg"), 0.82))
        self.screen(ctx, t)
        self.keys_of_past(ctx, t)
        # 2010: the swipe trails over the glass keys.
        for at, word in self.swipes():
            if at - 0.1 <= t <= at + 0.9:
                path = smooth_path([eras.centre(self.eras[3].keys[c]) for c in word], 18)
                k = in_out_cubic(span(t, at - 0.05, at + 0.35))
                tip, part = along(path, k)
                fade = 1 - span(t, at + 0.5, at + 0.9)
                self.trail(ctx, part, flow, 16, fade)
                if k < 1:
                    self.finger(ctx, *tip, 1, fade, 26, (0.15, 0.15, 0.2))
        # The era's name, at the seam between screen and keys.
        name = NAMES[era] if not (era == 3 and t >= sw) else "SWIPE"
        start = self.morph_at[era - 1] if era > 0 else self.song.bar_time(FIRST_BAR) + 0.05
        if era == 3 and t >= sw:
            start = sw
        end = self.morph_at[era] - 0.3 if era < 4 else 1e9
        self.label_at_seam(ctx, t, name, years[era] if era < 4 else "", start, end, text)
        ctx.restore()

    def year_behind(self, ctx, y_from, y_to, roll, colour):
        size = 520
        x = W / 2 - self.heavy.width("0000", size) / 2
        self.odometer(ctx, y_from, y_to, x, 560 + size * 0.35, size, roll, colour)

    def label_at_seam(self, ctx, t, name, year, start, end, colour):
        """The era's name, slammed in on the line where its screen meets its keys."""
        self.slam(ctx, self.sans_bold, name, W / 2, 452, 30, t / 0.12, start / 0.12, colour, align=0.5, tracking=0.12,
                  stagger=0.25, dur=2.5, exit_at=end / 0.12)

    # ------------------------------------------------ hexkeys

    PLACE = (960, 680, 700)

    def feature_word(self, ctx, t, word, x, y, size, at, until, colour=PAPER, font=None):
        """A bar's word, set right where its gesture happens, rising in glyph by
        glyph on the hit and leaving upwards before the next bar."""
        font = font or self.heavy
        self.slam(ctx, font, word, x, y, size, t / 0.05, at / 0.05, colour, align=0.5, stagger=0.4, dur=4, exit_at=until / 0.05, tracking=-0.01)

    def text_line(self, ctx, text, y, size=58, caret=None, colour=PAPER, alpha=1.0, centre_on=None):
        font = self.sans_bold
        x0 = W / 2 - font.width(centre_on if centre_on is not None else text, size) / 2
        font.text(ctx, text, x0, y, size, colour, alpha=alpha)
        if caret is not None:
            cx = x0 + font.width(text[:caret], size)
            ctx.set_source_rgba(*ERAS[4]["accent"], alpha)
            ctx.rectangle(cx - 2, y - size * 0.8, 5, size)
            ctx.fill()
        return x0

    # Each feature bar's colour field, and where the next one opens from: the
    # spot the previous bar's gesture ended on, in frame coordinates.
    FIELDS = {28: INK, 29: (0.17, 0.05, 0.09), 30: (0.02, 0.02, 0.03), 31: (0.13, 0.06, 0.18),
              32: (0.07, 0.09, 0.22), 33: (0.03, 0.14, 0.12), 34: (0.93, 0.93, 0.95), 35: (0.04, 0.05, 0.12)}
    OPEN_FROM = {29: (960, 300), 30: (700, 300), 31: (960, 300), 32: (960, 330), 33: (1000, 380), 34: (900, 330), 35: (960, 300)}

    def field(self, ctx, t, bar):
        """The bar's colour, opening as a hexagon from where the last gesture ended."""
        start = self.song.bar_time(bar)
        prev = self.FIELDS.get(bar - 1, INK)
        ctx.set_source_rgb(*prev)
        ctx.paint()
        k = out_expo(span(t, start - 0.08, start + 0.3))
        x, y = self.OPEN_FROM.get(bar, (960, 540))
        hexagon(ctx, x, y, 2300 * k, (1 - k) * 0.8)
        ctx.set_source_rgb(*self.FIELDS.get(bar, INK))
        ctx.fill()
        # A rim in the flow colours riding the edge of the opening.
        if 0 < k < 1:
            hexagon(ctx, x, y, 2300 * k, (1 - k) * 0.8)
            ctx.set_source_rgba(*flow((bar % 8) / 7), 0.9 * (1 - k))
            ctx.set_line_width(14 * (1 - k) + 2)
            ctx.stroke()

    def hexkeys(self, ctx, t, bar):
        self.field(ctx, t, bar)
        ctx.save()
        self.camera.apply(ctx, t)
        h = self.song.hits(bar)
        o = self.song.eighths(bar)
        end = self.song.bar_time(bar + 1)
        place = self.PLACE
        presses, fx = {}, None
        getattr(self, FEATURES[bar])(ctx, t, h, o, end, place)
        ctx.restore()

    def press_map(self, t, taps):
        out = {}
        for at, label in taps:
            if at <= t:
                out[label] = max(out.get(label, 0), math.exp(-(t - at) * 12))
        return out

    def arrival(self, ctx, t, h, o, end, place):
        """It arrives: the keys land in a wave, the name lands over them."""
        def key_fx(key):
            d = math.hypot(key["centre"][0] - self.kb.width / 2, key["centre"][1] - self.kb.height / 2) / 700
            p = out_back(span(t, h[0] + d * 0.25, h[0] + d * 0.25 + 0.35), 2.2)
            return (0, 0, 0, max(p, 0.01), 1) if t > h[0] else (0, 0, 0, 1, 1)
        self.hex_keyboard(ctx, place, t, self.press_map(t, [(at, l) for at, l in zip(o[1:], "hexkeys")]), key_fx=key_fx)
        # Rings of hexagons from the middle on every main word.
        for i, at in enumerate(h):
            k = span(t, at, at + 0.9)
            if 0 < k < 1:
                hexagon(ctx, 960, 560, 120 + out_cubic(k) * 900, k * 0.3)
                ctx.set_source_rgba(*flow(i / 3), 0.7 * (1 - k))
                ctx.set_line_width(5 * (1 - k) + 1)
                ctx.stroke()
        self.feature_word(ctx, t, "hexkeys", 960, 300, 170, h[1], end - 0.15)

    def flick(self, ctx, t, h, o, end, place):
        """Flick up: a capital, four times, each big over the key it came from."""
        letters = "hexk"
        taps = list(zip(h, letters))
        self.hex_keyboard(ctx, place, t, self.press_map(t, taps))
        for at, c in taps:
            x, y = self.key_xy(c, place)
            k = span(t, at - 0.05, at + 0.4)
            if 0 < k < 1:
                rise = out_expo(span(t, at - 0.05, at + 0.1))
                self.trail(ctx, [(x, y), (x, y - rise * 60)], GREEN, 18, 1 - span(k, 0.6, 1), fade=False)
                self.finger(ctx, x, y - rise * 60, 1, 1 - span(k, 0.4, 0.7))
            p = out_back(span(t, at, at + 0.25), 2.4)
            q = span(t, at + 0.35, at + 0.5) if at != h[-1] else span(t, end - 0.25, end - 0.05)
            if p > 0 and q < 1:
                size = 260 * max(p, 0.01)
                self.heavy.text(ctx, c.upper(), x, y - 120 - 140 * p, size, flow(letters.index(c) / 3), align=0.5, alpha=1 - q)
        typed = "".join(c.upper() for at, c in taps if at <= t)
        self.text_line(ctx, typed, 250, 64, centre_on="HEXK")
        self.mono.text(ctx, "FLICK UP · CAPITAL", 960, 290, 22, DIM, align=0.5, alpha=span(t, h[0], h[0] + 0.2) * (1 - span(t, end - 0.2, end)))

    def slide(self, ctx, t, h, o, end, place):
        """Slide in from the number row's right: the text goes letter by letter, then words."""
        text = "typed on the wrong keyboard"
        cx, cy, width = place
        x_start = self.key_xy("9", place)[0]
        x_end = self.key_xy("1", place)[0]
        y = self.key_xy("9", place)[1] + 16
        k = in_out_cubic(span(t, o[0] - 0.1, o[-1]))
        fxp = lerp(x_start, x_end, k)
        gone = sum(1 for at in o if at <= t)
        cut = [0, 1, 2, 4, 6, 9, 14, 20, 27][min(8, gone)]
        shown = text[:max(0, len(text) - cut)]
        presses = {}
        for key in self.kb.keys:
            if key["label"].isdigit():
                presses[key["label"]] = math.exp(-abs(self.key_xy(key["label"], place)[0] - fxp) / 40) * 0.9
        self.hex_keyboard(ctx, place, t, presses)
        x0 = self.text_line(ctx, shown, 250, 58, caret=len(shown), centre_on=text)
        # The letters that went, flying off as small hexagons.
        for i, at in enumerate(o):
            if at > t:
                continue
            kk = span(t, at, at + 0.8)
            if kk >= 1:
                continue
            a = [0, 1, 2, 4, 6, 9, 14, 20, 27][i]
            b = [0, 1, 2, 4, 6, 9, 14, 20, 27][i + 1]
            for j in range(len(text) - b, len(text) - a):
                px = x0 + self.sans_bold.width(text[:j], 58)
                seed = (j * 37) % 97 / 97
                ang = -math.pi / 2 + (seed - 0.5) * 2.2
                d = out_expo(kk) * (120 + seed * 260)
                rounded_hexagon(ctx, px + math.cos(ang) * d, 230 + math.sin(ang) * d + kk * kk * 200, 18 * (1 - kk), 0.2)
                ctx.set_source_rgba(*flow(seed), 1 - kk)
                ctx.fill()
        self.trail(ctx, [(x_start, y), (fxp, y)], lambda f: mix(PURPLE, (1, 0.35, 0.45), f), 18, 1 - span(t, end - 0.25, end))
        self.finger(ctx, fxp, y, 1, span(t, o[0] - 0.2, o[0]) * (1 - span(t, end - 0.25, end)))
        self.feature_word(ctx, t, "slide", fxp, y - 70, 64, h[0], end - 0.2, colour=(1, 0.45, 0.52))

    def glide(self, ctx, t, h, o, end, place):
        """Glide along the number row's middle: the caret walks, a step per word."""
        text = "hexagons are the bestagons"
        mid = self.key_xy("5", place)
        y = mid[1] + 16
        steps = sum(1 for at in o if at <= t)
        caret = len(text) - steps * 2
        kw = place[2] / self.kb.width * self.kb.cell
        glide = len(text) - caret
        fxp = mid[0] - glide * 0.3 * kw * 0.5
        presses = {}
        for key in self.kb.keys:
            if key["label"].isdigit():
                presses[key["label"]] = math.exp(-abs(self.key_xy(key["label"], place)[0] - fxp) / 40) * 0.9
        self.hex_keyboard(ctx, place, t, presses)
        x0 = self.text_line(ctx, text, 250, 58, caret=caret)
        # A ruler over the number row, a tick per character.
        top = y - 90
        for i in range(-14, 15):
            x = mid[0] + i * 0.15 * kw
            big = i % 5 == 0
            ctx.set_source_rgba(*(GREEN if big else DIM), 0.9 if big else 0.5)
            ctx.rectangle(x - 1, top - (16 if big else 8), 2, 16 if big else 8)
            ctx.fill()
        self.trail(ctx, [mid[:1] + (y,), (fxp, y)], GREEN, 16, 1 - span(t, end - 0.25, end))
        self.finger(ctx, fxp, y, 1, span(t, o[0] - 0.2, o[0]) * (1 - span(t, end - 0.25, end)))
        cx = x0 + self.sans_bold.width(text[:caret], 58)
        self.feature_word(ctx, t, "glide", cx, 160, 64, h[0], end - 0.2, colour=GREEN)

    def swipe(self, ctx, t, h, o, end, place):
        """Swipe: two words, one stroke each, the strip offering what the path spells."""
        words = [("keyboard", h[0], h[1] + 0.1), ("faster", h[2], h[3] + 0.1)]
        self.hex_keyboard(ctx, place, t)
        text = " ".join(w for w, s0, s1 in words if t >= s1)
        self.text_line(ctx, text, 250, 64, centre_on="keyboard faster")
        for w, s0, s1 in words:
            if s0 - 0.1 <= t <= s1 + 0.5:
                path = smooth_path([self.key_xy(c, place) for c in w], 14)
                path = [(x + 8 * math.sin(i * 0.4), y + 6 * math.cos(i * 0.3)) for i, (x, y) in enumerate(path)]
                k = in_out_cubic(span(t, s0, s1))
                tip, part = along(path, k)
                fade = 1 - span(t, s1 + 0.1, s1 + 0.5)
                self.trail(ctx, part, flow, 18, fade)
                if k < 1:
                    self.finger(ctx, *tip, 1, fade)
                    self.feature_word(ctx, t, "swipe", tip[0], tip[1] - 60, 54, s0, s1 + 0.2, colour=PAPER)

    def hold(self, ctx, t, h, o, end, place):
        """Hold for the letters a key also writes: a honeycomb of them opens."""
        holds = [("e", "éèêëė", h[0], h[1], 0), ("1", "½⅓¼⅕⅙⅛", h[2], h[3], 0)]
        text = "caf"
        for label, choices, t0, t1, pick in holds:
            if t >= t1:
                text += choices[pick] if label == "e" else " " + choices[pick]
        presses = {label: 1.0 for label, _, t0, t1, _ in holds if t0 <= t <= t1 + 0.1}
        self.hex_keyboard(ctx, place, t, presses)
        self.text_line(ctx, text, 250, 72, centre_on="café ½")
        kw = place[2] / self.kb.width * self.kb.cell
        r = 0.72 * kw / math.sqrt(3)
        for label, choices, t0, t1, pick in holds:
            if not t0 - 0.05 <= t <= t1 + 0.3:
                continue
            kx, ky = self.key_xy(label, place)
            open_ = out_back(span(t, t0 + 0.1, t0 + 0.3), 2.0) * (1 - span(t, t1 + 0.05, t1 + 0.3))
            cells = [(kx + (i - (len(choices) - 1) / 2) * r * math.sqrt(3), ky - 1.4 * kw) for i in range(len(choices))]
            for i, (ch, (px, py)) in enumerate(zip(choices, cells)):
                if open_ <= 0.01:
                    continue
                ctx.save()
                ctx.translate(lerp(kx, px, open_), lerp(ky, py, open_))
                ctx.scale(max(open_, 0.01), max(open_, 0.01))
                rounded_hexagon(ctx, 0, 0, r * 0.95, 0.15)
                ctx.set_source_rgb(*(ERAS[4]["accent"] if i == pick and t > t0 + 0.3 else mix(DUSK_OTHER, DUSK_LABEL, 0.12)))
                ctx.fill()
                self.sans.text(ctx, ch, 0, 15, 42, DUSK_LABEL, align=0.5)
                ctx.restore()
            m = in_out_cubic(span(t, t0 + 0.3, t1 - 0.05))
            self.finger(ctx, lerp(kx, cells[pick][0], m), lerp(ky, cells[pick][1], m), 1, 1 - span(t, t1, t1 + 0.12))
            ring = span(t, t0, t0 + 0.2)
            if ring < 1:
                ctx.arc(kx, ky, 56, -math.pi / 2, -math.pi / 2 + ring * 2 * math.pi)
                ctx.set_source_rgb(*GREEN)
                ctx.set_line_width(6)
                ctx.stroke()
            self.feature_word(ctx, t, "hold", kx, ky - 1.4 * kw - 90, 64, t0, t1 + 0.3, colour=PAPER)

    def languages(self, ctx, t, h, o, end, place):
        """A sideways flick on space for the next language: each one's word, big."""
        steps = [("DE", "Haus"), ("VI", "việt"), ("FR", "ça"), ("EN", "I'm")]
        code, word, at = "EN", "", None
        for (c, w), hit in zip(steps, h):
            if t >= hit:
                code, word, at = c, w, hit
        self.paper_keyboard(ctx, place, {}, code, t, [(hit, c) for (c, w), hit in zip(steps, h)])
        if at is not None:
            p = out_back(span(t, at, at + 0.2), 2.4)
            self.heavy.text(ctx, word, 960, 300, 200 * max(p, 0.01), flow(h.index(at) / 3), align=0.5)
        self.mono.text(ctx, "47 LANGUAGES · FLICK SPACE SIDEWAYS", 960, 345, 22, DIM, align=0.5, alpha=span(t, h[0], h[0] + 0.2) * (1 - span(t, end - 0.2, end)))

    def speak(self, ctx, t, h, o, end, place):
        """Speak: the mic rises out of the strip, the voice rings round it, words land."""
        rise = out_expo(span(t, h[0] - 0.15, h[0] + 0.25))
        sink = in_out_expo(span(t, h[0] - 0.2, h[0] + 0.2))
        self.hex_keyboard(ctx, (960, 680 + sink * 700, 700), t)
        hx, hy, r = 960, lerp(330, 600, rise), lerp(30, 130, rise)
        lv = 0
        for at in o:
            if at <= t:
                lv = max(lv, math.exp(-(t - at) * 9))
        n = 72
        for i in range(n):
            a = 2 * math.pi * i / n - math.pi / 2
            seg = (a + math.pi / 2) % (math.pi / 3) - math.pi / 6
            edge = r * 1.25 * math.cos(math.pi / 6) / math.cos(seg)
            amp = lv * (0.4 + 0.6 * abs(math.sin(i * 1.7 + t * 9))) * 150 * rise
            ctx.move_to(hx + math.cos(a) * edge, hy + math.sin(a) * edge)
            ctx.line_to(hx + math.cos(a) * (edge + 6 + amp), hy + math.sin(a) * (edge + 6 + amp))
            ctx.set_source_rgba(*flow(i / n), 0.9 * rise)
            ctx.set_line_width(7)
            ctx.set_line_cap(cairo.LINE_CAP_ROUND)
            ctx.stroke()
        rounded_hexagon(ctx, hx, hy, r * (1 + 0.05 * lv), 0.15)
        ctx.set_source_rgb(*ERAS[4]["accent"])
        ctx.fill()
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
        said = "Bring the hexagons!"
        k = out_expo(span(t, h[2], h[2] + 0.3))
        if k > 0:
            self.sans_bold.text(ctx, said, 960, 330 + (1 - k) * 30, 76, PAPER, align=0.5, alpha=k)
        self.mono.text(ctx, "SPEAK · ON THE PHONE · 25 LANGUAGES", 960, 880, 22, DIM, align=0.5, alpha=rise * (1 - span(t, end - 0.2, end)))

    # ------------------------------------------------ the chorus: the icon

    def finale(self, ctx, t):
        """The drop after the chorus: on its downbeat the three keys lock into
        the icon, its letters pop on the next beat, the name rises on the next
        bar and the address types after it; round it everything shown before,
        a word a beat."""
        ctx.set_source_rgb(*INK)
        ctx.paint()
        d = self.song.beats[4 * DROP_BAR:4 * END_BAR + 1]
        hits = [d[0], d[1], d[4], d[5]]
        ctx.save()
        self.camera.apply(ctx, t)
        kb = self.kb
        keys = [kb.key("r"), kb.key("d"), kb.key("f")]
        mx = sum(k["centre"][0] for k in keys) / 3
        my = sum(k["centre"][1] for k in keys) / 3
        s = 2.6
        start = hits[0] - 0.25
        # Until the first hit the keyboard is still there, its other keys falling away.
        fall = in_expo(span(t, start, hits[0]))
        place = self.PLACE
        if fall < 1:
            def key_fx(key):
                if key["label"] in ("r", "d", "f"):
                    return (0, 0, 0, 1, 1)
                seed = (hash(key["label"]) % 97) / 97
                return (0, fall * (400 + 600 * seed), fall * (seed - 0.5) * 3, 1, 1 - fall)
            self.hex_keyboard(ctx, place, t, key_fx=key_fx, case_alpha=1 - fall)
        # A honeycomb drawing itself outwards behind the icon, pulsing on the beat.
        beats = [bt for bt in self.song.beats if hits[0] <= bt <= self.song.bar_time(END_BAR)]
        pulse = max([math.exp(-(t - bt) * 8) for bt in beats if bt <= t] or [0])
        reach = out_cubic(span(t, hits[0], hits[0] + 3.0)) * 1300
        r = 64
        for row in range(-9, 10):
            for col in range(-11, 12):
                cx = 960 + col * r * math.sqrt(3) + (r * math.sqrt(3) / 2 if row % 2 else 0)
                cy = 500 + row * r * 1.5
                d = math.hypot(cx - 960, cy - 500)
                if d > reach or d < 230:
                    continue
                fade = clamp((reach - d) / 250)
                hexagon(ctx, cx, cy, r * (0.94 - 0.06 * pulse * math.exp(-d / 600)))
                ctx.set_source_rgba(*flow(clamp(d / 1100)), (0.10 + 0.25 * pulse * math.exp(-d / 500)) * fade)
                ctx.set_line_width(1.5)
                ctx.stroke()
        # Everything shown before, a word per beat, in cells round the icon.
        recap = ["FLICK", "SLIDE", "GLIDE", "SWIPE", "HOLD", "47 LANGUAGES", "SPEAK", "PASSWORDS", "CLIPBOARD", "EMOJI", "THEMES", "OPEN SOURCE"]
        spots = [(-560, -260), (560, -260), (-700, 20), (700, 20), (-560, 300), (560, 300), (-330, -420), (330, -420), (-330, 430), (330, 430), (-800, -150), (800, -150)]
        after = [bt for bt in beats if bt >= hits[0] + 0.2]
        for i, (word, (dx, dy)) in enumerate(zip(recap, spots)):
            if i >= len(after) or t < after[i]:
                continue
            p = out_back(span(t, after[i], after[i] + 0.25), 2.2)
            dim = 1 - 0.55 * span(t, after[i] + 0.4, after[i] + 1.0)
            out = 1.0
            self.heavy.text(ctx, word, 960 + dx, 500 + dy + 16, 40 * max(p, 0.01), mix(PAPER, flow(i / 11), 0.5), align=0.5, alpha=dim * out)
        breathe = 1 + 0.035 * pulse
        ctx.translate(960, 500)
        ctx.scale(breathe, breathe)
        ctx.rotate(0.02 * math.sin(t * 1.3))
        ctx.translate(-960, -500)
        settle = out_expo(span(t, hits[0] - 0.15, hits[0] + 0.3))
        for i, (k, letter, angle) in enumerate(zip(keys, "ehx", (0, -30, 30))):
            sx, sy = self.key_xy(k["label"], place)
            tx, ty = 960 + (k["centre"][0] - mx) * s, 500 + (k["centre"][1] - my) * s
            px, py = lerp(sx, tx, settle), lerp(sy, ty, settle)
            sc = lerp(place[2] / kb.width, s, settle)
            ctx.save()
            ctx.translate(px, py)
            ctx.scale(sc, sc)
            ctx.translate(-k["centre"][0], -k["centre"][1])
            polygon(ctx, k["outline"])
            ctx.set_source_rgba(*mix(DUSK_ROWS[k["row"]], flow(i / 2), settle), 1 - 0.84 * settle)
            ctx.fill_preserve()
            ctx.set_line_width(6 / sc)
            ctx.set_line_join(cairo.LINE_JOIN_ROUND)
            ctx.set_source_rgba(*flow(i / 2), settle)
            ctx.stroke()
            ctx.restore()
            p = out_back(span(t, hits[1] + i * 0.06, hits[1] + 0.3 + i * 0.06), 2.5)
            if p > 0:
                ctx.save()
                ctx.translate(px, py)
                ctx.rotate(math.radians(angle))
                size = 0.6 * kb.cell * s
                self.bold.text(ctx, letter, 0, size * 0.36, size * max(p, 0.01), flow(i / 2), align=0.5)
                ctx.restore()
        for k in range(3):
            ring = span(t, hits[0] + k * 0.12, hits[0] + 1.2 + k * 0.12)
            if 0 < ring < 1:
                hexagon(ctx, 960, 500, 200 + out_cubic(ring) * 1000, ring * 0.4)
                ctx.set_source_rgba(*flow(k / 2), 0.7 * (1 - ring))
                ctx.set_line_width(4)
                ctx.stroke()
        self.slam(ctx, self.heavy, "hexkeys", 960, 900, 130, t / 0.05, hits[2] / 0.05, PAPER, align=0.5, stagger=0.6, dur=4)
        self.typed(ctx, self.mono, "github.com/nanbiz/hexkeys", 960, 960, 30, t, hits[3], DIM, rate=0.02, align=0.5)
        ctx.restore()

    # ------------------------------------------------ frames

    def scene(self, ctx, t):
        bar, x = self.song.bar_at(t)
        hit = 0.0
        if bar < HEX_BAR or t < self.song.bar_time(HEX_BAR) + 0.4:
            self.past(ctx, t)
            for m in self.morph_at:
                if m - 0.05 <= t:
                    hit = max(hit, 0.5 * math.exp(-(t - m) * 9))
        elif bar < DROP_BAR:
            self.hexkeys(ctx, t, bar)
            if bar == HEX_BAR:
                hit = max(hit, 0.6 * math.exp(-(t - self.song.bar_time(HEX_BAR)) * 6))
        else:
            self.finale(ctx, t)
            drop = self.song.bar_time(DROP_BAR)
            hit = max(hit, 0.7 * math.exp(-(t - drop) * 6)) if t >= drop else hit
            end = self.song.bar_time(END_BAR)
            fade = span(t, end + 0.15, end + 0.65)
            if fade > 0:
                ctx.set_source_rgba(*INK, fade)
                ctx.paint()
        return t / self.beat, hit


def main():
    args = sys.argv[1:]
    outlines, timings, fonts, out = args[:4]
    film = Cut(outlines, timings, fonts)
    start = float(args[args.index("--from") + 1]) if "--from" in args else 0.0
    end = float(args[args.index("--to") + 1]) if "--to" in args else film.length
    if "--sheet" in args:
        sheet = args[args.index("--sheet") + 1]
        size = float(args[args.index("--size") + 1]) if "--size" in args else 0.25
        if "--at" in args:
            times = [float(v) for v in args[args.index("--at") + 1].split(",")]
            cols = 2 if len(times) > 1 else 1
        else:
            count = int(args[args.index("--count") + 1]) if "--count" in args else 24
            times = list(np.linspace(start, end, count, endpoint=False))
            cols = 6
        tiles = [film.frame(t, size) for t in times]
        while len(tiles) % cols:
            tiles.append(np.zeros_like(tiles[0]))
        image = np.concatenate([np.concatenate(tiles[i:i + cols], 1) for i in range(0, len(tiles), cols)], 0)
        surface = cairo.ImageSurface(cairo.FORMAT_RGB24, image.shape[1], image.shape[0])
        data = np.ndarray((image.shape[0], image.shape[1], 4), np.uint8, surface.get_data())
        data[:, :, 0], data[:, :, 1], data[:, :, 2] = image[:, :, 2], image[:, :, 1], image[:, :, 0]
        surface.mark_dirty()
        surface.write_to_png(sheet)
        return
    ffmpeg = subprocess.Popen(["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
                               "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", "-preset", "medium", out], stdin=subprocess.PIPE)
    for i in range(int(start * FPS), int(end * FPS)):
        ffmpeg.stdin.write(film.frame(i / FPS, 1.0).tobytes())
    ffmpeg.stdin.close()
    ffmpeg.wait()


if __name__ == "__main__":
    main()
