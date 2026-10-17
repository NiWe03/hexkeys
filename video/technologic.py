"""The trailer cut to a song: one area of focus that the
camera follows, every hit on a sung word's onset, words of our own set at
the focus instead of in a corner.

Usage: video/build.sh, which supplies the arguments:
  python3 -I video/technologic.py OUTLINES TIMINGS FONTS_DIR OUT.mp4 [--from S] [--to S] [--sheet SHEET.png] [--at S,S,...]

TIMINGS is the word timing file the alignment wrote (video/out/track/
verse1.json): the song's beats, and per bar the onset of every sung word.
Only the onsets are used; the words themselves never reach the screen.

The cut runs song bars 16 to 36, about 38 seconds: from where the full beat
comes in, one thing hexkeys does per bar for sixteen bars, then the chorus,
whose four sung words build the icon, ending where the drop after it starts.
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
FIRST_BAR = 16
CHORUS_BAR = 32
END_BAR = 36
# What each bar of the full beat shows, one thing hexkeys does per bar.
FEATURES = {16: "arrival", 17: "swipe", 18: "flick", 19: "suggestions", 20: "slide", 21: "glide", 22: "hold", 23: "languages",
            24: "speak", 25: "speak", 26: "clipboard", 27: "passwords", 28: "emoji_search", 29: "punctuation", 30: "themes", 31: "source"}
# Where each bar's gesture happens, for the camera: centre and zoom.
FOCUS = {16: (960, 560, 1.0), 17: (960, 560, 1.06), 18: (960, 520, 1.1), 19: (960, 420, 1.14), 20: (1000, 470, 1.18),
         21: (960, 450, 1.2), 22: (960, 520, 1.12), 23: (960, 620, 1.08), 24: (960, 500, 1.0), 25: (960, 470, 1.06),
         26: (900, 500, 1.06), 27: (960, 500, 1.06), 28: (960, 560, 1.04), 29: (960, 500, 1.08), 30: (960, 560, 1.0), 31: (960, 540, 1.0)}


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
        """The song's time at video time v: one stretch from bar 16 on."""
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
        self.length = song.bar_time(END_BAR) - song.bar_time(FIRST_BAR)
        self.camera = self.shots()

    # ------------------------------------------------ timing

    def shots(self):
        """The camera's path: each bar it starts wide, then leans in on where
        its gesture happens, so the area of focus glides from bar to bar."""
        s = self.song.bar_time
        keys = [(s(FIRST_BAR) - 0.5, 960, 560, 1.0, 0.0)]
        for bar in range(FIRST_BAR, CHORUS_BAR):
            x, y, zoom = FOCUS[bar]
            tilt = 0.006 * (1 if bar % 2 else -1)
            keys.append((s(bar) + 0.05, 960, 560, 1.0, 0.0))
            keys.append((s(bar) + 0.6, x, y, zoom, tilt))
            keys.append((s(bar + 1) - 0.12, x, y, zoom * 1.015, tilt))
        keys += [(s(CHORUS_BAR) + 0.1, 960, 540, 1.0, 0.0), (s(END_BAR), 960, 540, 1.07, 0.0)]
        return Camera(keys)

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
    FIELDS = {16: INK, 17: (0.02, 0.02, 0.03), 18: (0.07, 0.09, 0.22), 19: (0.05, 0.05, 0.07), 20: (0.17, 0.05, 0.09),
              21: (0.03, 0.14, 0.12), 22: (0.13, 0.06, 0.18), 23: (0.93, 0.93, 0.95), 24: (0.04, 0.05, 0.12), 25: (0.04, 0.05, 0.12),
              26: (0.12, 0.40, 0.36), 27: (0.20, 0.24, 0.52), 28: (0.40, 0.22, 0.46), 29: (0.92, 0.92, 0.94), 30: (0.118, 0.102, 0.094), 31: INK}
    OPEN_FROM = {17: (960, 300), 18: (960, 560), 19: (960, 300), 20: (960, 300), 21: (700, 380), 22: (960, 420), 23: (960, 420),
                 24: (900, 330), 25: (960, 560), 26: (960, 600), 27: (960, 400), 28: (960, 400), 29: (960, 560), 30: (960, 500), 31: (960, 540)}

    def field(self, ctx, t, bar):
        """The bar's colour, opening as a hexagon from where the last gesture ended."""
        start = self.song.bar_time(bar)
        prev = self.FIELDS.get(bar - 1, INK)
        ctx.set_source_rgb(*prev)
        ctx.paint()
        k = out_expo(span(t, start - 0.08, start + 0.3)) if self.FIELDS.get(bar) != prev else 1.0
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
        """Speak, over two bars: the mic rises out of the strip and the voice
        rings round it. The dictation is cut at pauses and each stretch is
        written while the next is still being spoken, so the first sentence
        is in while the second is still being said."""
        first, second = self.song.eighths(24), self.song.eighths(25)
        stretches = [(first[0], first[6]), (second[0], second[6])]
        lands = [second[1], second[7] + 0.05]
        said = ["Hey Sam, still on for 8?", "Bring the hexagons!"]
        rise = out_expo(span(t, first[0] - 0.15, first[0] + 0.3))
        sink = in_out_expo(span(t, first[0] - 0.2, first[0] + 0.25))
        if sink < 1:
            self.hex_keyboard(ctx, (960, 680 + sink * 700, 700), t)
        hx, hy, r = 960, lerp(330, 640, rise), lerp(30, 120, rise)
        speaking = any(a <= t <= b for a, b in stretches)
        lv = 0.0
        for at in first[:7] + second[:7]:
            if at <= t:
                lv = max(lv, math.exp(-(t - at) * 9))
        if not speaking:
            lv *= 0.3
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
        # LIVE, its dot pulsing, beside the mic.
        la = rise * (1 - span(t, self.song.bar_time(26) - 0.2, self.song.bar_time(26)))
        if la > 0:
            dot = 0.55 + 0.45 * math.cos(t * 9)
            ctx.arc(hx + 200, hy - 6, 11, 0, 2 * math.pi)
            ctx.set_source_rgba(1, 0.27, 0.32, la * dot)
            ctx.fill()
            self.heavy.text(ctx, "LIVE", hx + 224, hy + 12, 50, PAPER, alpha=la)
        # The sentences, each landing as soon as its stretch is done.
        for i, (line, at) in enumerate(zip(said, lands)):
            k = out_expo(span(t, at, at + 0.3))
            if k <= 0:
                continue
            y = 220 + i * 90
            self.sans_bold.text(ctx, line, 960, y + (1 - k) * 40, 70, PAPER, align=0.5, alpha=k * la)
            if t < at + 0.7:
                self.mono.text(ctx, "WRITTEN WHILE YOU SPEAK", 960, y + 40, 20, GREEN, align=0.5, alpha=k * (1 - span(t, at + 0.45, at + 0.7)))
        self.mono.text(ctx, "SPEAK · ON THE PHONE · 25 LANGUAGES", 960, 880, 22, DIM, align=0.5, alpha=la)

    def strip_words(self, ctx, place, words, bold=1, alpha=1.0, highlight=None):
        """The strip over the keys with its three word slots, the middle one bold."""
        x, y, w, h = self.strip_bar(ctx, place, alpha)
        slot = (w - 120) / 3
        for i, word in enumerate(words):
            sx = x + 60 + slot * (i + 0.5)
            if highlight == i:
                rounded_rect_path(ctx, sx - slot / 2 + 6, y + 8, slot - 12, h - 16, 18)
                ctx.set_source_rgba(*ERAS[4]["accent"], 0.6 * alpha)
                ctx.fill()
            font = self.sans_bold if i == bold else self.sans
            size = min(30, 30 * (slot - 20) / max(1, font.width(word, 30)))
            font.text(ctx, word, sx, y + h / 2 + size * 0.36, size, DUSK_LABEL, align=0.5, alpha=alpha)
        return x, y, w, h

    def suggestions(self, ctx, t, h, o, end, place):
        """Suggestions: letters tapped on the words, the strip answering each,
        as the app's own strip() does; the made-up word kept by tapping it."""
        word = "bestagons"
        taps = list(zip(o, word))
        typed = "".join(c for at, c in taps if at <= t)
        strips = {"b": ("b", "by", "but"), "be": ("he", "be", "been"), "bes": ("new", "bes", "best"), "best": ("beat", "best", "west"),
                  "besta": ("besta", "best", "beta"), "bestag": ("bestag", "gestation", "bests"), "bestago": ("bestag", "gestation", "bests"),
                  "bestagon": ("bestagon", "Pentagon", "gestation")}
        slots = strips.get(typed, ("", "", ""))
        self.hex_keyboard(ctx, place, t, self.press_map(t, taps))
        self.strip_words(ctx, place, slots)
        self.text_line(ctx, "hexagons are the " + typed, 250, 64, caret=None, centre_on="hexagons are the bestagons")
        for at, c in taps:
            if at - 0.06 <= t <= at + 0.1:
                self.finger(ctx, *self.key_xy(c, place), 1, 1 - span(t, at, at + 0.1))
        self.feature_word(ctx, t, "suggest", 960, 160, 60, h[0], end - 0.15, colour=GREEN)

    def clipboard(self, ctx, t, h, o, end, place):
        """Clipboard: what was just copied waits in the strip; one tap puts it in."""
        self.hex_keyboard(ctx, place, t)
        x, y, w, hh = self.strip_bar(ctx, place)
        pill = out_back(span(t, h[0], h[0] + 0.25)) * (1 - span(t, h[2] + 0.05, h[2] + 0.2))
        if pill > 0.01:
            ctx.save()
            ctx.translate(x + w / 2, y + hh / 2)
            ctx.scale(pill, pill)
            rounded_rect_path(ctx, -210, -22, 420, 44, 22)
            ctx.set_source_rgb(*mix(DUSK_OTHER, PAPER, 0.15))
            ctx.fill()
            self.sans.text(ctx, "meet at the hive at 8?", 0, 8, 22, PAPER, align=0.5)
            ctx.restore()
        if h[1] <= t <= h[2] + 0.1:
            self.finger(ctx, x + w / 2, y + hh / 2, 1, span(t, h[1], h[1] + 0.1) * (1 - span(t, h[2], h[2] + 0.1)))
        text = "ok" + (" meet at the hive at 8?" if t >= h[2] else "")
        self.text_line(ctx, text, 250, 60, caret=len(text), centre_on="ok meet at the hive at 8?")
        k = out_expo(span(t, h[3], h[3] + 0.3))
        if k > 0:
            self.mono.text(ctx, "KEPT AN HOUR · OR PINNED", 960, 300, 22, PAPER, align=0.5, alpha=k)
        self.feature_word(ctx, t, "paste", 960, 160, 70, h[0], end - 0.15, colour=(0.55, 0.95, 0.85))

    def passwords(self, ctx, t, h, o, end, place):
        """Passwords: the autofill service's login offered in the strip, filled with a tap."""
        self.hex_keyboard(ctx, place, t)
        x, y, w, hh = self.strip_bar(ctx, place)
        chip = out_back(span(t, h[0], h[0] + 0.25))
        cx_, cy_ = x + w - 190, y + hh / 2
        ctx.save()
        ctx.translate(cx_, cy_)
        ctx.scale(max(chip, 0.01), max(chip, 0.01))
        rounded_rect_path(ctx, -160, -22, 320, 44, 22)
        ctx.set_source_rgb(*mix(DUSK_OTHER, PAPER, 0.15))
        ctx.fill()
        self.sans.text(ctx, "sam@example.com", 0, 8, 22, PAPER, align=0.5)
        ctx.restore()
        if h[1] - 0.1 <= t <= h[1] + 0.12:
            self.finger(ctx, cx_, cy_, 1, 1 - span(t, h[1], h[1] + 0.12))
        for i, (label, value) in enumerate((("Email", "sam@example.com"), ("Password", "•" * 12))):
            fy = 150 + i * 105
            self.sans.text(ctx, label, 600, fy, 24, PAPER, alpha=0.7)
            rounded_rect_path(ctx, 600, fy + 12, 720, 62, 16)
            ctx.set_source_rgba(1, 1, 1, 0.12)
            ctx.fill()
            n = int(span(t, h[1] + 0.05 + i * 0.2, h[2] + i * 0.3) * len(value))
            self.sans.text(ctx, value[:n], 625, fy + 55, 30, PAPER)
        self.feature_word(ctx, t, "fill", cx_, cy_ - 60, 60, h[1], end - 0.15, colour=PAPER)

    def emoji_search(self, ctx, t, h, o, end, place):
        """Emoji: a flick up on @#€ opens them as a honeycomb; a search finds the bees."""
        sym = self.key_xy("@#€", place)
        open_ = out_expo(span(t, h[0], h[0] + 0.4))
        self.hex_keyboard(ctx, place, t, key_fx=lambda key: (0, open_ * 700, 0, 1, 1 - open_))
        if t < h[0] + 0.3:
            m = out_expo(span(t, h[0] - 0.05, h[0] + 0.1))
            self.finger(ctx, sym[0], sym[1] - m * 50, 1, 1 - span(t, h[0] + 0.1, h[0] + 0.3))
        emoji = [e for e in "😀😂🥹😍🤔😎🥳😭👍🙏🔥✨🎉🐝🍯🌍🍕☕🚀🎈🌈🐱🐶🦊🐢🍀⚡🎧📷💡🧩🔑⏰🧡💜💙💚" if e != "️"]
        r = 62
        cells = sorted(((col * r * math.sqrt(3) + (r * math.sqrt(3) / 2 if row % 2 else 0), row * r * 1.5)
                        for row in range(-3, 4) for col in range(-4, 5)), key=lambda c: math.hypot(*c))
        query = "bee"[:int(span(t, h[2], h[2] + 0.35) * 3 + 1e-6)] if t >= h[2] else ""
        found = "🐝🐞🪲"
        for i, ((px, py), e) in enumerate(zip(cells, emoji)):
            d = math.hypot(px, py) / 500
            k = out_back(span(t, h[0] + 0.1 + d * 0.4, h[0] + 0.4 + d * 0.4), 2.2)
            if k <= 0:
                continue
            match = query == "bee" and e in found
            dimmed = 0.25 if query == "bee" and not match else 1
            gx, gy = 960 + px * k, 640 + py * k
            rounded_hexagon(ctx, gx, gy, r * 0.92 * min(k, 1.2) * (1.15 if match else 1), 0.15)
            ctx.set_source_rgba(1, 1, 1, 0.12 * dimmed)
            ctx.fill()
            self.emoji.text(ctx, e, gx, gy + 18, 50 * min(k, 1.2), PAPER, align=0.5, alpha=dimmed)
        if t >= h[2] - 0.1:
            rounded_rect_path(ctx, 700, 210, 520, 76, 38)
            ctx.set_source_rgba(1, 1, 1, 0.16)
            ctx.fill()
            self.sans.text(ctx, query, 740, 260, 34, PAPER)
        self.feature_word(ctx, t, "emoji", 960, 170, 70, h[0], end - 0.15, colour=PAPER)

    def punctuation(self, ctx, t, h, o, end, place):
        """Punctuation: a flick up on . types a comma, on ? an exclamation mark, on ↵ the field's action."""
        flicks = [(h[0], ".", ","), (h[1], "?", "!"), (h[2], "⏎", "send")]
        self.hex_keyboard(ctx, place, t)
        text = "wait"
        for at, key, out in flicks:
            if t >= at + 0.05:
                text += {",": ",", "!": " really!", "send": ""}[out]
            x, y = self.key_xy(key, place)
            k = span(t, at, at + 0.5)
            if 0 < k < 1:
                m = out_expo(span(t, at - 0.05, at + 0.1))
                self.finger(ctx, x, y - m * 50, 1, 1 - span(t, at + 0.1, at + 0.25), colour=(0.2, 0.2, 0.25))
                self.heavy.text(ctx, out, x, y - 60 - out_expo(k) * 110, 90, INK, align=0.5, alpha=1 - span(k, 0.5, 1))
        sent = t >= h[2] + 0.05
        if sent:
            k = out_back(span(t, h[2] + 0.05, h[2] + 0.3))
            fly = in_expo(span(t, h[3], h[3] + 0.4))
            w = self.sans_bold.width(text, 52) + 60
            rounded_rect_path(ctx, 960 - w / 2 + fly * 700, 210 - fly * 200, w * k, 80, 40)
            ctx.set_source_rgb(*ERAS[4]["accent"])
            ctx.fill()
            self.sans_bold.text(ctx, text, 960 + fly * 700, 266 - fly * 200, 52, PAPER, align=0.5, alpha=span(t, h[2] + 0.1, h[2] + 0.25))
        else:
            self.text_line(ctx, text, 270, 64, caret=len(text), colour=INK, centre_on="wait, really!")

    def themes(self, ctx, t, h, o, end, place):
        """Themes: on each main word the keys flip to the next, in a wave from the left."""
        themes = [("Dusk", (0.106, 0.106, 0.122), DUSK_ROWS, DUSK_OTHER, DUSK_LABEL),
                  ("Paper", (0.894, 0.894, 0.918), [(0.780, 0.843, 0.965), (0.769, 0.910, 0.831), (0.957, 0.812, 0.855)], (0.984, 0.984, 0.992), (0.106, 0.106, 0.122)),
                  ("Ember", (0.118, 0.102, 0.094), [(0.353, 0.227, 0.133), (0.306, 0.165, 0.165), (0.247, 0.227, 0.133)], (0.227, 0.204, 0.192), (0.953, 0.925, 0.902)),
                  ("Plain", (0.106, 0.106, 0.122), [(0.227, 0.227, 0.259)] * 3, (0.227, 0.227, 0.259), (0.910, 0.910, 0.933))]
        i = max(0, sum(1 for at in h if at <= t) - 1)
        name, bg, rows, other, label = themes[i]
        ctx.set_source_rgb(*mix(bg, PAPER if luminance(bg) < 0.5 else INK, 0.06))
        ctx.paint()
        at = h[i]
        cx, cy, width = place
        kb = self.kb
        k = width / kb.width
        x0, y0 = cx - width / 2, cy - kb.height * k / 2
        rounded_rect_path(ctx, x0 - 26, y0 - 26, width + 52, kb.height * k + 52, 28)
        ctx.set_source_rgb(*bg)
        ctx.fill()
        prev = themes[max(0, i - 1)]
        for key in kb.keys:
            kx, ky = key["centre"]
            f = span(t, at + kx / kb.width * 0.25, at + kx / kb.width * 0.25 + 0.18)
            _, _, r_, o_, l_ = themes[i] if f >= 0.5 else prev
            ctx.save()
            ctx.translate(x0 + kx * k, y0 + ky * k)
            ctx.scale(k * max(0.05, abs(math.cos(math.pi * f))), k)
            ctx.translate(-kx, -ky)
            polygon(ctx, key["outline"])
            ctx.set_source_rgb(*(r_[key["row"]] if key["row"] is not None else o_))
            ctx.fill()
            text = {"⏎": "↵", "space": "", "123": "@#€"}.get(key["label"], key["label"])
            if text:
                size = kb.cell * (0.36 if len(text) == 1 else 0.22)
                self.sans.text(ctx, text, kx, ky + size * 0.36, size, l_, align=0.5)
            ctx.restore()
        p = out_back(span(t, at, at + 0.25), 2.2)
        self.heavy.text(ctx, name, 960, 280, 150 * max(p, 0.01), label, align=0.5)

    def source(self, ctx, t, h, o, end, place):
        """Open source, the rule a flick follows scrolling past as the code it
        is, and on the following words what the app does without: an account,
        ads, trackers."""
        lines = self.source_lines
        off = span(t, self.song.bar_time(30), end) * 900
        for i, line in enumerate(lines):
            y = 300 + i * 34 - off
            if 120 < y < H - 40:
                hl = "FLICK" in line or "DOWN_TAP" in line or "DELETE_FIRST" in line
                self.mono.text(ctx, line[:84], 960 - 640, y, 24, GREEN if hl else DIM, alpha=0.95 if hl else 0.55)
        k = out_back(span(t, h[0], h[0] + 0.25), 2.2)
        rounded_rect_path(ctx, 960 - 760, 470, 1520, 320, 30)
        ctx.set_source_rgba(*INK, 0.85)
        ctx.fill()
        self.heavy.text(ctx, "open source", 960, 575, 110 * max(k, 0.01), PAPER, align=0.5)
        # And nothing in the way, a line per main word after it.
        for i, (item, at) in enumerate(zip(("no account", "no ads", "no trackers"), h[1:])):
            k = out_back(span(t, at, at + 0.22), 2.0)
            if k > 0:
                self.heavy.text(ctx, item, 960 + (i - 1) * 470, 740, 64 * max(k, 0.01), mix(PAPER, flow(i / 2), 0.45), align=0.5)

    # ------------------------------------------------ the chorus: the icon

    def finale_hits(self):
        return [self.song.bars[b]["onsets"][0] for b in range(CHORUS_BAR, CHORUS_BAR + 4)]

    def finale(self, ctx, t):
        """The chorus, its four sung words four hits: the three keys lock into
        the icon, its letters pop, the name rises, the address types; round it
        everything shown before, a word a beat."""
        ctx.set_source_rgb(*INK)
        ctx.paint()
        hits = self.finale_hits()
        ctx.save()
        self.camera.apply(ctx, t)
        kb = self.kb
        keys = [kb.key("r"), kb.key("d"), kb.key("f")]
        mx = sum(k["centre"][0] for k in keys) / 3
        my = sum(k["centre"][1] for k in keys) / 3
        s = 2.6
        start = self.song.bar_time(CHORUS_BAR)
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
        if bar < CHORUS_BAR:
            self.hexkeys(ctx, t, bar)
            if bar == FIRST_BAR:
                hit = 0.6 * math.exp(-max(0.0, t - self.song.hits(bar)[0]) * 6) if t >= self.song.hits(bar)[0] else 0.0
        else:
            self.finale(ctx, t)
            for at in self.finale_hits():
                if at <= t:
                    hit = max(hit, 0.5 * math.exp(-(t - at) * 7))
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
