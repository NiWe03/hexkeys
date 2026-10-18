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
# Where the icon settles in the finale.
ICON_Y = 430
# What each bar of the full beat shows, one thing hexkeys does per bar.
FEATURES = {16: "arrival", 17: "flick", 18: "swipe", 19: "suggestions", 20: "glide", 21: "hold", 22: "clipboard", 23: "speak",
            24: "speak", 25: "slide", 26: "languages", 27: "emoji_search", 28: "send", 29: "themes", 30: "themes", 31: "source"}
# Where each bar's gesture happens, for the camera: centre and zoom.
FOCUS = {16: (960, 560, 1.0), 17: (960, 520, 1.04), 18: (960, 500, 1.06), 19: (960, 420, 1.1), 20: (960, 380, 1.14),
         21: (960, 420, 1.1), 22: (960, 400, 1.08), 23: (960, 460, 1.0), 24: (960, 460, 1.02), 25: (1000, 400, 1.12),
         26: (960, 500, 1.04), 27: (960, 520, 1.02), 28: (960, 460, 1.04), 29: (960, 520, 1.0), 30: (960, 520, 1.0), 31: (960, 540, 1.0)}


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
    def start(self):
        """Where the cut starts in the song: just before the first sung word of
        bar 16, which comes a little ahead of the bar line, so text can land on it."""
        return min(self.song.bar_time(FIRST_BAR), self.song.bars[FIRST_BAR]["onsets"][0]) - 0.06

    def song_time(self, v):
        """The song's time at video time v: one stretch from just before bar 16 on."""
        return self.start() + v

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
        self.length = song.bar_time(END_BAR) - self.start()
        self.camera = self.shots()
        self.write_story()

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

    PLACE = (960, 730, 680)

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
    FIELDS = {16: INK, 17: (0.02, 0.02, 0.03), 18: (0.07, 0.09, 0.22), 19: (0.05, 0.05, 0.07), 20: (0.03, 0.14, 0.12),
              21: (0.13, 0.06, 0.18), 22: (0.12, 0.40, 0.36), 23: (0.04, 0.05, 0.12), 24: (0.04, 0.05, 0.12), 25: (0.17, 0.05, 0.09),
              26: (0.93, 0.93, 0.95), 27: (0.40, 0.22, 0.46), 28: (0.20, 0.24, 0.52), 29: INK, 30: INK, 31: INK}
    OPEN_FROM = {17: (960, 270), 18: (960, 560), 19: (960, 270), 20: (700, 380), 21: (960, 420), 22: (960, 400), 23: (960, 600),
                 25: (960, 270), 26: (900, 420), 27: (700, 900), 28: (960, 300), 29: (1300, 600), 31: (960, 540)}

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
        """It arrives: the keys land in a wave on the first sung word and the
        name is typed on them, a key per letter, each letter landing big over
        the keyboard as its key is struck."""
        name = "hexkeys"
        step = 0.115
        taps = [(h[0] + i * step, c) for i, c in enumerate(name)]

        def key_fx(key):
            d = math.hypot(key["centre"][0] - self.kb.width / 2, key["centre"][1] - self.kb.height / 2) / 700
            p = out_back(span(t, h[0] - 0.1 + d * 0.2, h[0] - 0.1 + d * 0.2 + 0.3), 2.2)
            return (0, 0, 0, max(p, 0.01), 1)
        self.hex_keyboard(ctx, place, t, self.press_map(t, taps), key_fx=key_fx)
        for i, at in enumerate(h):
            k = span(t, at, at + 0.9)
            if 0 < k < 1:
                hexagon(ctx, 960, 560, 120 + out_cubic(k) * 900, k * 0.3)
                ctx.set_source_rgba(*flow(i / 3), 0.7 * (1 - k))
                ctx.set_line_width(5 * (1 - k) + 1)
                ctx.stroke()
        size = 170
        x = 960 - self.heavy.width(name, size) / 2
        leave = in_expo(span(t, end - 0.3, end - 0.05))
        for at, c in taps:
            adv = self.heavy.advance(c, size)
            p = out_back(span(t, at, at + 0.14), 2.6)
            if p > 0:
                ctx.save()
                ctx.translate(x + adv / 2, 300 - size * 0.35 - leave * 200)
                ctx.scale(max(p, 0.01), max(p, 0.01))
                self.heavy.text(ctx, c, -adv / 2, size * 0.35, size, PAPER, alpha=1 - leave)
                ctx.restore()
            x += adv
        # The cursor after the last letter typed.
        typed = sum(1 for at, _ in taps if at <= t)
        if t >= h[0] - 0.05 and leave < 1 and (typed < len(name) or int(t * 3) % 2 == 0):
            cx = 960 - self.heavy.width(name, size) / 2 + self.heavy.width(name[:typed], size)
            ctx.set_source_rgba(*ERAS[4]["accent"], 1 - leave)
            ctx.rectangle(cx + 6, 300 - size * 0.75 - leave * 200, 10, size * 0.85)
            ctx.fill()

    # ------------------------------------------------ the message

    COMPOSER = (520, 60, 880, 250)

    def write_story(self):
        """The one message the trailer writes, as (time, text, caret) states:
        swiped, capitalised, finished from the strip, corrected in the middle,
        given an address, dictated, trimmed, signed off in German, given a bee
        and sent; Sam's reply comes after."""
        song = self.song
        o = song.eighths
        h = song.hits
        b = song.beats
        states = [(0.0, "", 0)]

        def put(t, text, caret=None):
            states.append((t, text, len(text) if caret is None else caret))

        # 17 flick: the sentence starts with a capital, flicked up off s, then e, e
        self.flick_at = h(17)[0]
        text = "S"
        put(self.flick_at + 0.55, text)
        self.flick_taps = [(o(17)[3], "e"), (o(17)[4], "e")]
        for at, c in self.flick_taps:
            text += c
            put(at, text)
        # 18 swipe: the rest of the sentence, a stroke a word
        self.swiped = []
        for i, word in enumerate("you at the hive".split()):
            s0 = o(18)[0] + i * 0.19
            self.swiped.append((word, s0, s0 + 0.16))
            text = text + " " + word
            put(s0 + 0.16, text)
        # 19 suggestions: " tomor", then tomorrow from the strip
        sixteenth = (o(19)[1] - o(19)[0]) / 2
        self.suggest_taps = [(o(19)[0] + i * sixteenth, c) for i, c in enumerate(" tomor")]
        for k, (at, c) in enumerate(self.suggest_taps):
            put(at, text + " tomor"[:k + 1])
        self.suggest_pick = h(19)[2]
        text = text + " tomorrow"
        put(self.suggest_pick, text)
        # 20 glide: the caret walks back to after "hive", a character per step
        back = len(" tomorrow")
        self.glide_steps = []
        for k in range(back):
            at = o(20)[0] + k * (o(20)[5] - o(20)[0]) / back
            self.glide_steps.append(at)
            put(at, text, len(text) - k - 1)
        caret = len(text) - back
        # 21 hold: " caf" typed there, é from a hold, then ? at the end
        sixteenth = (o(21)[1] - o(21)[0]) / 2
        self.hold_taps = [(o(21)[0] + i * sixteenth, c) for i, c in enumerate(" caf")]
        for k, (at, c) in enumerate(self.hold_taps):
            ins = " caf"[:k + 1]
            put(at, text[:caret] + ins + text[caret:], caret + len(ins))
        self.hold_at = (h(21)[1] - 0.05, h(21)[2] - 0.08)
        text = text[:caret] + " café" + text[caret:]
        put(self.hold_at[1], text, caret + len(" café"))
        self.question_at = h(21)[3] - 0.1
        text += "?"
        put(self.question_at, text)
        # 22 clipboard: the address pasted on its own line
        self.paste_at = h(22)[1]
        text += "\nWabengasse 6"
        put(self.paste_at, text)
        # 23, 24 speak: two lines dictated, a word on each sung word's onset
        self.dictated = []
        for bar, line in ((23, "Bring the board game, I'll bring the honey."), (24, "And maybe grab some snacks for us later?")):
            text += "\n"
            for at, word in zip(o(bar), line.split()):
                text += ("" if text.endswith("\n") else " ") + word
                self.dictated.append(at)
                put(at, text)
        # 25 slide: the second line goes again, faster as the finger goes on
        cut = "\nAnd maybe grab some snacks for us later?"
        counts = [1, 3, 6, 10, 16, 23, 31, len(cut)]
        self.slide_cuts = []
        for at, n in zip(o(25), counts):
            self.slide_cuts.append((at, n))
            put(at, text[:len(text) - n])
        text = text[:len(text) - len(cut)]
        # 26 languages: flick to German, "Bis dann" on a new line
        self.language_at = h(26)[0]
        sixteenth = (o(26)[1] - o(26)[0]) / 2
        self.german_taps = [(o(26)[1] + i * sixteenth, c) for i, c in enumerate("\nBis dann")]
        for k, (at, c) in enumerate(self.german_taps):
            put(at, text + "\nBis dann"[:k + 1])
        text += "\nBis dann"
        # 27 emoji: a bee, found by name
        self.bee_at = h(27)[3] - 0.1
        text += " 🐝"
        put(self.bee_at, text)
        # 28 send: a flick up on enter
        self.send_at = h(28)[1]
        self.message = text
        put(self.send_at, "", 0)
        self.reply_at = h(29)[1]
        self.states = states

    def state(self, t):
        best = self.states[0]
        for st in self.states:
            if st[0] <= t:
                best = st
            else:
                break
        return best[1], best[2]

    def draw_text(self, ctx, text, x, y, size, colour, caret=None, line_height=1.25, alpha=1.0, accent=None):
        """Multi-line text, emoji drawn from the emoji font, with an optional
        caret; returns where the caret is."""
        caret_xy = None
        lx, ly = x, y
        for i, ch in enumerate(text):
            if caret == i:
                caret_xy = (lx, ly)
            if ch == "\n":
                lx, ly = x, ly + size * line_height
                continue
            if ord(ch) > 0x2600:
                self.emoji.text(ctx, ch, lx, ly, size, colour, alpha=alpha)
                lx += self.emoji.advance(ch, size)
                continue
            self.sans_bold.text(ctx, ch, lx, ly, size, colour, alpha=alpha)
            lx += self.sans_bold.advance(ch, size)
        if caret is not None and caret >= len(text):
            caret_xy = (lx, ly)
        if caret_xy:
            ctx.set_source_rgba(*(accent or ERAS[4]["accent"]), alpha)
            ctx.rectangle(caret_xy[0] + 1, caret_xy[1] - size * 0.8, 4, size)
            ctx.fill()
        return caret_xy

    def message_layout(self, text, caret, big=64, small=34):
        """Where the message's characters go: the line with the caret big and
        centred, the lines before it small and dim above it, the lines after
        it small below. Returns per line (text, x, y, size, alpha) and the
        caret's point."""
        lines = text.split("\n")
        before = text[:caret].split("\n")
        current = len(before) - 1
        out, caret_xy = [], None
        width = lambda line, size: sum(self.emoji.advance(c, size) if ord(c) > 0x2600 else self.sans_bold.advance(c, size) for c in line)
        y_current = 270
        for i, line in enumerate(lines):
            if i == current:
                size, y, alpha = big, y_current, 1.0
            elif i < current:
                size, y, alpha = small, y_current - big * 0.95 - (current - 1 - i) * small * 1.3, 0.55
            else:
                size, y, alpha = small, y_current + small * 1.4 + (i - current - 1) * small * 1.3, 0.55
            x = 960 - width(line, size) / 2
            out.append((line, x, y, size, alpha))
            if i == current:
                caret_xy = (x + width(before[-1], size), y)
        return out, caret_xy

    def composer(self, ctx, t, alpha=1.0, light=False):
        """The message as it stands, floating at the focus: the line being
        written big, the rest small round it."""
        text, caret = self.state(t)
        rows, caret_xy = self.message_layout(text, caret)
        ink = INK if light else PAPER
        for line, x, y, size, a in rows:
            self.draw_text(ctx, line, x, y, size, ink, alpha=alpha * a)
        blink = int(t * 3) % 2 == 0 or any(abs(t - st[0]) < 0.3 for st in self.states)
        if caret_xy and blink and text:
            ctx.set_source_rgba(*ERAS[4]["accent"], alpha)
            ctx.rectangle(caret_xy[0] + 2, caret_xy[1] - 64 * 0.8, 5, 64)
            ctx.fill()
        return caret_xy

    def caret_point(self, t):
        text, caret = self.state(t)
        return self.message_layout(text, caret)[1] or (960, 270)

    # ------------------------------------------------ the bars

    def swipe(self, ctx, t, h, o, end, place):
        """Swipe: the first words, a stroke each."""
        self.hex_keyboard(ctx, place, t)
        self.composer(ctx, t)
        for word, s0, s1 in self.swiped:
            if s0 - 0.08 <= t <= s1 + 0.35:
                path = smooth_path([self.key_xy(c, place) for c in word], 14)
                path = [(x + 8 * math.sin(i * 0.4), y + 6 * math.cos(i * 0.3)) for i, (x, y) in enumerate(path)]
                k = in_out_cubic(span(t, s0, s1))
                tip, part = along(path, k)
                fade = 1 - span(t, s1, s1 + 0.35)
                self.trail(ctx, part, flow, 18, fade)
                if k < 1:
                    self.finger(ctx, *tip, 1, fade)
        self.feature_word(ctx, t, "swipe", 960, 405, 64, o[0], end - 0.15)

    def flick(self, ctx, t, h, o, end, place):
        """Flick up on s: its capital pops up off the key and flies into the
        sentence, landing on its first letter."""
        at = self.flick_at
        self.hex_keyboard(ctx, place, t, self.press_map(t, [(at, "s")] + self.flick_taps))
        self.composer(ctx, t)
        x, y = self.key_xy("s", place)
        rise = out_expo(span(t, at - 0.08, at + 0.08))
        if t < at + 0.4:
            self.trail(ctx, [(x, y), (x, y - rise * 70)], GREEN, 18, 1 - span(t, at + 0.2, at + 0.4), fade=False)
            self.finger(ctx, x, y - rise * 70, 1, 1 - span(t, at + 0.15, at + 0.3))
        # Where the sentence's first letter sits.
        rows, _ = self.message_layout("S", 1)
        line, lx, ly, size, _ = rows[0]
        pop = out_back(span(t, at, at + 0.18), 2.4)
        fly = in_out_cubic(span(t, at + 0.2, at + 0.55))
        if pop > 0 and fly < 1:
            sx, sy, ss = x, y - 150, 220
            tx, ty, ts = lx + self.sans_bold.advance("S", size) / 2, ly, size
            cx, cy = lerp(sx, tx, fly), lerp(sy, ty, fly)
            sz = lerp(ss, ts, fly) * max(pop, 0.01)
            font = self.heavy if fly < 0.6 else self.sans_bold
            font.text(ctx, "S", cx, cy, sz, mix(GREEN, PAPER, fly), align=0.5)
        self.feature_word(ctx, t, "flick up to capitalize", 960, 405, 56, at - 0.05, end - 0.15, colour=GREEN)

    def suggestions(self, ctx, t, h, o, end, place):
        """Suggestions: the strip answers each letter, as the app's strip() does; tomorrow is tapped."""
        strips = {"t": ("t", "the", "to"), "to": ("town", "to", "too"), "tom": ("to", "tom", "Tom"),
                  "tomo": ("tomo", "too", "tomorrow"), "tomor": ("tomor", "tomorrow", "honor")}
        typed = "".join(c for at, c in self.suggest_taps if at <= t).strip()
        slots = strips.get(typed, ("", "", "")) if t < self.suggest_pick + 0.15 else ("", "", "")
        self.hex_keyboard(ctx, place, t, self.press_map(t, [(at, render.key_of(c)) for at, c in self.suggest_taps]))
        x, y, w, hh = self.strip_words(ctx, place, slots, highlight=1 if self.suggest_pick - 0.12 <= t <= self.suggest_pick + 0.15 else None)
        self.composer(ctx, t)
        for at, c in self.suggest_taps:
            if at - 0.05 <= t <= at + 0.08 and c != " ":
                self.finger(ctx, *self.key_xy(c, place), 1, 1 - span(t, at, at + 0.08))
        if self.suggest_pick - 0.2 <= t <= self.suggest_pick + 0.15:
            self.finger(ctx, x + w / 2, y + hh / 2, 1, span(t, self.suggest_pick - 0.2, self.suggest_pick - 0.1) * (1 - span(t, self.suggest_pick, self.suggest_pick + 0.15)))
        self.feature_word(ctx, t, "suggest", 960, 160, 60, o[0], end - 0.15, colour=GREEN)

    def glide(self, ctx, t, h, o, end, place):
        """Glide along the number row's middle: the caret walks back, a step per 0.3 keys."""
        mid = self.key_xy("5", place)
        y = mid[1] + 16
        kw = place[2] / self.kb.width * self.kb.cell
        steps = sum(1 for at in self.glide_steps if at <= t)
        fxp = mid[0] - steps * 0.3 * kw * 0.55
        presses = {}
        for key in self.kb.keys:
            if key["label"].isdigit():
                presses[key["label"]] = math.exp(-abs(self.key_xy(key["label"], place)[0] - fxp) / 40) * 0.9
        self.hex_keyboard(ctx, place, t, presses)
        self.composer(ctx, t)
        live = o[0] - 0.15 <= t <= end - 0.15
        if live:
            self.trail(ctx, [(mid[0], y), (fxp, y)], GREEN, 16, 1)
            self.finger(ctx, fxp, y, 1, span(t, o[0] - 0.15, o[0]) * (1 - span(t, end - 0.3, end - 0.15)))
        cx, cy = self.caret_point(t)
        self.feature_word(ctx, t, "glide", cx, cy - 60, 54, o[0], end - 0.15, colour=GREEN)

    def hold(self, ctx, t, h, o, end, place):
        """Hold e: the letters it also writes open as a honeycomb; é is picked."""
        t0, t1 = self.hold_at
        taps = [(at, render.key_of(c)) for at, c in self.hold_taps] + [(self.question_at, "?")]
        presses = self.press_map(t, taps)
        if t0 <= t <= t1 + 0.1:
            presses["e"] = 1.0
        self.hex_keyboard(ctx, place, t, presses)
        self.composer(ctx, t)
        kw = place[2] / self.kb.width * self.kb.cell
        r = 0.72 * kw / math.sqrt(3)
        kx, ky = self.key_xy("e", place)
        choices = "éèêëė"
        open_ = out_back(span(t, t0 + 0.08, t0 + 0.28), 2.0) * (1 - span(t, t1 + 0.05, t1 + 0.3))
        cells = [(kx + (i - (len(choices) - 1) / 2) * r * math.sqrt(3), ky - 1.4 * kw) for i in range(len(choices))]
        for i, (ch, (px, py)) in enumerate(zip(choices, cells)):
            if open_ <= 0.01:
                continue
            ctx.save()
            ctx.translate(lerp(kx, px, open_), lerp(ky, py, open_))
            ctx.scale(max(open_, 0.01), max(open_, 0.01))
            rounded_hexagon(ctx, 0, 0, r * 0.95, 0.15)
            ctx.set_source_rgb(*(ERAS[4]["accent"] if i == 0 and t > t0 + 0.25 else mix(DUSK_OTHER, DUSK_LABEL, 0.12)))
            ctx.fill()
            self.sans.text(ctx, ch, 0, 15, 42, DUSK_LABEL, align=0.5)
            ctx.restore()
        if t0 - 0.05 <= t <= t1 + 0.12:
            m = in_out_cubic(span(t, t0 + 0.25, t1 - 0.03))
            self.finger(ctx, lerp(kx, cells[0][0], m), lerp(ky, cells[0][1], m), 1, 1 - span(t, t1, t1 + 0.12))
            ring = span(t, t0, t0 + 0.15)
            if ring < 1:
                ctx.arc(kx, ky, 56, -math.pi / 2, -math.pi / 2 + ring * 2 * math.pi)
                ctx.set_source_rgb(*GREEN)
                ctx.set_line_width(6)
                ctx.stroke()
        for at, c in self.hold_taps:
            if at - 0.05 <= t <= at + 0.08 and c != " ":
                self.finger(ctx, *self.key_xy(c, place), 1, 1 - span(t, at, at + 0.08))
        self.feature_word(ctx, t, "hold", kx, ky - 1.4 * kw - 80, 60, t0, end - 0.15)

    def clipboard(self, ctx, t, h, o, end, place):
        """Clipboard: the address just copied waits in the strip; a tap puts it in."""
        self.hex_keyboard(ctx, place, t)
        x, y, w, hh = self.strip_bar(ctx, place)
        at = self.paste_at
        pill = out_back(span(t, o[0] - 0.1, o[0] + 0.15)) * (1 - span(t, at + 0.05, at + 0.2))
        if pill > 0.01:
            ctx.save()
            ctx.translate(x + w / 2, y + hh / 2)
            ctx.scale(pill, pill)
            rounded_rect_path(ctx, -170, -22, 340, 44, 22)
            ctx.set_source_rgb(*mix(DUSK_OTHER, PAPER, 0.15))
            ctx.fill()
            self.sans.text(ctx, "Wabengasse 6", 0, 8, 24, PAPER, align=0.5)
            ctx.restore()
        if at - 0.25 <= t <= at + 0.1:
            self.finger(ctx, x + w / 2, y + hh / 2, 1, span(t, at - 0.25, at - 0.15) * (1 - span(t, at, at + 0.1)))
        self.composer(ctx, t)
        self.feature_word(ctx, t, "paste", x + w / 2, y - 40, 60, o[0], end - 0.15, colour=(0.55, 0.95, 0.85))

    def speak(self, ctx, t, h, o, end, place):
        """Speak, over two bars: the mic rises out of the strip and rings with
        the voice, a word landing in the text on each sung word."""
        first, second = self.song.eighths(23), self.song.eighths(24)
        rise = out_expo(span(t, first[0] - 0.15, first[0] + 0.3))
        sink = in_out_expo(span(t, first[0] - 0.2, first[0] + 0.25))
        if sink < 1:
            self.hex_keyboard(ctx, (960, place[1] + sink * 700, place[2]), t)
        self.composer(ctx, t)
        hx, hy, r = 960, lerp(330, 640, rise), lerp(30, 120, rise)
        lv = 0.0
        for at in self.dictated:
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
        dot = 0.55 + 0.45 * math.cos(t * 9)
        ctx.arc(hx + 200, hy - 6, 11, 0, 2 * math.pi)
        ctx.set_source_rgba(1, 0.27, 0.32, rise * dot)
        ctx.fill()
        self.heavy.text(ctx, "LIVE", hx + 224, hy + 12, 50, PAPER, alpha=rise)
        self.mono.text(ctx, "ON THE PHONE · 25 LANGUAGES", 960, 880, 22, DIM, align=0.5, alpha=rise)
        # The keyboard comes back up at the end of the second bar.
        back = out_expo(span(t, self.song.bar_time(25) - 0.35, self.song.bar_time(25)))
        if back > 0:
            self.hex_keyboard(ctx, (960, place[1] + (1 - back) * 700, place[2]), t)

    def slide(self, ctx, t, h, o, end, place):
        """Slide in from the number row's right: the end of the line goes, faster as it goes on."""
        x_start = self.key_xy("9", place)[0]
        x_end = self.key_xy("1", place)[0]
        y = self.key_xy("9", place)[1] + 16
        k = in_out_cubic(span(t, o[0] - 0.1, o[-1]))
        fxp = lerp(x_start, x_end, k)
        presses = {}
        for key in self.kb.keys:
            if key["label"].isdigit():
                presses[key["label"]] = math.exp(-abs(self.key_xy(key["label"], place)[0] - fxp) / 40) * 0.9
        self.hex_keyboard(ctx, place, t, presses)
        self.composer(ctx, t)
        live = o[0] - 0.2 <= t <= end - 0.1
        if live:
            self.trail(ctx, [(x_start, y), (fxp, y)], lambda f: mix(PURPLE, (1, 0.35, 0.45), f), 18, 1 - span(t, end - 0.25, end - 0.1))
            self.finger(ctx, fxp, y, 1, span(t, o[0] - 0.2, o[0]) * (1 - span(t, end - 0.25, end - 0.1)))
        # What goes flies off the caret as small hexagons.
        cx, cy = self.caret_point(t)
        for i, (at, n) in enumerate(self.slide_cuts):
            kk = span(t, at, at + 0.7)
            if 0 < kk < 1:
                for j in range(3):
                    seed = ((i * 7 + j * 13) % 29) / 29
                    ang = -math.pi / 2 + (seed - 0.5) * 2.4
                    d = out_expo(kk) * (80 + seed * 220)
                    rounded_hexagon(ctx, cx + math.cos(ang) * d, cy - 15 + math.sin(ang) * d + kk * kk * 160, 14 * (1 - kk), 0.2)
                    ctx.set_source_rgba(*flow(seed), 1 - kk)
                    ctx.fill()
        self.feature_word(ctx, t, "slide", fxp, y - 70, 60, o[0], end - 0.15, colour=(1, 0.45, 0.52))

    def languages(self, ctx, t, h, o, end, place):
        """A sideways flick on space: German, and the sign-off in it."""
        at = self.language_at
        code = "DE" if t >= at else "EN"
        self.paper_keyboard(ctx, place, self.press_map(t, [(a, render.key_of(c)) for a, c in self.german_taps if c != "\n"]), code, t, [(at, "DE")])
        self.composer(ctx, t, light=True)
        p = out_back(span(t, at, at + 0.2), 2.4)
        sx, sy = self.key_xy("space", place)
        if p > 0:
            self.heavy.text(ctx, "Deutsch", 960, 450, 90 * max(p, 0.01), (0.29, 0.36, 0.78), align=0.5, alpha=1 - span(t, end - 0.3, end - 0.1))

    def emoji_search(self, ctx, t, h, o, end, place):
        """Emoji: a flick up on @#€ opens them as a honeycomb; "bee" finds the bee; it goes in."""
        sym = self.key_xy("@#€", place)
        open_ = out_expo(span(t, h[0], h[0] + 0.4)) * (1 - out_expo(span(t, self.bee_at + 0.15, self.bee_at + 0.5)))
        self.hex_keyboard(ctx, place, t, key_fx=lambda key: (0, open_ * 700, 0, 1, 1 - open_), case_alpha=1 - open_)
        self.composer(ctx, t)
        if t < h[0] + 0.3:
            m = out_expo(span(t, h[0] - 0.05, h[0] + 0.1))
            self.finger(ctx, sym[0], sym[1] - m * 50, 1, 1 - span(t, h[0] + 0.1, h[0] + 0.3))
        emoji = [e for e in "😀😍🤔😎🥳😭👍🙏🔥✨🎉🐝🍯🌍🍕☕🚀🎈🌈🐱🐶🦊🐢🍀⚡🎧📷💡🧩🔑⏰🐞🪲😊🤖🌻🍩"]
        r = 54
        cells = sorted(((r * math.sqrt(3) * (q + rr / 2), r * 1.5 * rr) for q in range(-3, 4) for rr in range(-3, 4) if abs(q + rr) <= 3),
                       key=lambda c: (math.hypot(*c), math.atan2(c[1], c[0])))
        centre = place[0], place[1] + 40
        query = "bee"[:int(span(t, h[1], h[1] + 0.3) * 3 + 1e-6)] if t >= h[1] else ""
        found = "🐝🐞🪲"
        bee_xy = None
        for i, ((px, py), e) in enumerate(zip(cells, emoji)):
            d = math.hypot(px, py) / 500
            k = out_back(span(t, h[0] + 0.1 + d * 0.4, h[0] + 0.4 + d * 0.4), 2.2) * (1 - span(t, self.bee_at + 0.1, self.bee_at + 0.4))
            if k <= 0:
                continue
            match = query == "bee" and e in found
            dimmed = 0.25 if query == "bee" and not match else 1
            gx, gy = centre[0] + px * k, centre[1] + py * k
            if e == "🐝":
                bee_xy = (gx, gy)
            rounded_hexagon(ctx, gx, gy, r * 0.92 * min(k, 1.2) * (1.15 if match else 1), 0.15)
            ctx.set_source_rgba(1, 1, 1, 0.12 * dimmed)
            ctx.fill()
            self.emoji.text(ctx, e, gx, gy + 16, 44 * min(k, 1.2), PAPER, align=0.5, alpha=dimmed)
        if bee_xy and self.bee_at - 0.25 <= t <= self.bee_at + 0.1:
            self.finger(ctx, *bee_xy, 1, span(t, self.bee_at - 0.25, self.bee_at - 0.15) * (1 - span(t, self.bee_at, self.bee_at + 0.1)))
        if h[1] - 0.1 <= t <= self.bee_at + 0.3:
            rounded_rect_path(ctx, 760, 380, 400, 64, 32)
            ctx.set_source_rgba(1, 1, 1, 0.16)
            ctx.fill()
            self.sans.text(ctx, query, 790, 424, 32, PAPER)

    def send(self, ctx, t, h, o, end, place):
        """A flick up on enter sends it: the message lifts out of the field as a bubble."""
        at = self.send_at
        x, y = self.key_xy("⏎", place)
        self.hex_keyboard(ctx, place, t, self.press_map(t, [(at, "⏎")]))
        if t < at:
            self.composer(ctx, t)
        else:
            self.composer(ctx, t)
            self.chat(ctx, t, None)
        if at - 0.1 <= t <= at + 0.3:
            m = out_expo(span(t, at - 0.08, at + 0.08))
            self.trail(ctx, [(x, y), (x, y - m * 70)], GREEN, 18, 1 - span(t, at + 0.1, at + 0.3), fade=False)
            self.finger(ctx, x, y - m * 70, 1, 1 - span(t, at + 0.1, at + 0.25))
        self.feature_word(ctx, t, "send", x - 40, y - 140, 60, at - 0.1, end - 0.15, colour=GREEN)

    def chat(self, ctx, t, theme):
        """The conversation above: the message sent as a bubble on the right, Sam's reply on the left."""
        at = self.send_at
        k = out_expo(span(t, at, at + 0.4))
        accent = theme[5] if theme else ERAS[4]["accent"]
        ink = theme[4] if theme else PAPER
        size = 30
        lines = self.message.split("\n")
        w = max(sum(self.emoji.advance(c, size) if ord(c) > 0x2600 else self.sans_bold.advance(c, size) for c in line) for line in lines) + 60
        hh = len(lines) * size * 1.3 + 36
        x1, y1 = self.COMPOSER[0] + 30, self.COMPOSER[1] + 20
        x2, y2 = 1400 - w, 60
        bx, by = lerp(x1, x2, k), lerp(y1, y2, k)
        rounded_rect_path(ctx, bx, by, w, hh, 26)
        ctx.set_source_rgb(*accent)
        ctx.fill()
        self.draw_text(ctx, self.message, bx + 30, by + 46, size, (1, 1, 1))
        rk = out_back(span(t, self.reply_at, self.reply_at + 0.3), 1.8)
        if rk > 0:
            reply = "hexagons are the bestagons"
            rw = self.sans_bold.width(reply, 34) + 60
            ry = by + hh + 26
            ctx.save()
            ctx.translate(520, ry)
            ctx.scale(max(rk, 0.01), max(rk, 0.01))
            rounded_rect_path(ctx, 0, 0, rw, 76, 26)
            ctx.set_source_rgb(*(theme[3] if theme else DUSK_OTHER))
            ctx.fill()
            self.sans_bold.text(ctx, reply, 30, 50, 34, ink)
            ctx.restore()
            self.sans.text(ctx, "Sam", 530, ry - 12, 22, ink, alpha=0.6 * min(1, rk))

    def themes(self, ctx, t, h, o, end, place):
        """Themes, over two bars: the whole chat flips to the next on every main word, keys in a wave from the left."""
        themes = [("Dusk", (0.106, 0.106, 0.122), DUSK_ROWS, DUSK_OTHER, DUSK_LABEL, (0.424, 0.482, 0.847)),
                  ("Paper", (0.894, 0.894, 0.918), [(0.780, 0.843, 0.965), (0.769, 0.910, 0.831), (0.957, 0.812, 0.855)], (0.984, 0.984, 0.992), (0.106, 0.106, 0.122), (0.29, 0.36, 0.78)),
                  ("Ember", (0.118, 0.102, 0.094), [(0.353, 0.227, 0.133), (0.306, 0.165, 0.165), (0.247, 0.227, 0.133)], (0.227, 0.204, 0.192), (0.953, 0.925, 0.902), (0.88, 0.54, 0.29)),
                  ("Plain dark", (0.106, 0.106, 0.122), [(0.227, 0.227, 0.259)] * 3, (0.227, 0.227, 0.259), (0.910, 0.910, 0.933), (0.424, 0.482, 0.847)),
                  ("Plain light", (0.894, 0.894, 0.918), [(0.984, 0.984, 0.992)] * 3, (0.984, 0.984, 0.992), (0.106, 0.106, 0.122), (0.29, 0.36, 0.78))]
        # A new theme on every main word of both bars.
        flips = self.song.hits(29) + self.song.hits(30)
        i = max(0, sum(1 for at in flips if at <= t) - 1)
        theme = themes[i % len(themes)]
        ctx.set_source_rgb(*mix(theme[1], PAPER if luminance(theme[1]) < 0.5 else INK, 0.06))
        ctx.paint()
        at = flips[i]
        prev = themes[(i - 1) % len(themes)] if i > 0 else theme
        cx, cy, width = place
        kb = self.kb
        k = width / kb.width
        x0, y0 = cx - width / 2, cy - kb.height * k / 2
        rounded_rect_path(ctx, x0 - 26, y0 - 26, width + 52, kb.height * k + 52, 28)
        ctx.set_source_rgb(*theme[1])
        ctx.fill()
        for key in kb.keys:
            kx, ky = key["centre"]
            f = span(t, at + kx / kb.width * 0.25, at + kx / kb.width * 0.25 + 0.18)
            _, _, r_, o_, l_, _ = theme if f >= 0.5 else prev
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
        self.chat(ctx, t, theme)
        p = out_back(span(t, at, at + 0.25), 2.2)
        self.heavy.text(ctx, theme[0], 1500, 560, 90 * max(p, 0.01), theme[4], align=0.5, alpha=0.9)

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
        rise = out_expo(span(t, end - 0.55, end))
        if rise > 0:
            self.hex_keyboard(ctx, (place[0], place[1] + (1 - rise) * 800, place[2]), t)
        k = out_back(span(t, h[0], h[0] + 0.25), 2.2) * (1 - span(t, end - 0.5, end - 0.2))
        rounded_rect_path(ctx, 960 - 760, 470, 1520, 320, 30)
        ctx.set_source_rgba(*INK, 0.85)
        ctx.fill()
        self.heavy.text(ctx, "open source", 960, 575, 110 * max(k, 0.01), PAPER, align=0.5)
        # And nothing in the way, a line per main word after it.
        for i, (item, at) in enumerate(zip(("no account", "no ads", "no trackers"), o[1:4])):
            k = out_back(span(t, at, at + 0.22), 2.0)
            if k > 0:
                self.heavy.text(ctx, item, 960 + (i - 1) * 470, 740, 64 * max(k, 0.01), mix(PAPER, flow(i / 2), 0.45), align=0.5)

    # ------------------------------------------------ the chorus: the icon

    def key_hexagon(self, key):
        """A key's outline and a regular hexagon in its middle, a third of a
        key across, both as the same number of points so one morphs into the other."""
        cache = self.__dict__.setdefault("_hexes", {})
        if key["label"] not in cache:
            cx, cy = key["centre"]
            r = self.kb.cell * 0.32
            hexagon_pts = [(cx + r * math.cos(math.radians(60 * i - 90)), cy + r * math.sin(math.radians(60 * i - 90))) for i in range(6)]
            cache[key["label"]] = (eras.resample(key["outline"]), eras.resample(hexagon_pts))
        return cache[key["label"]]

    def finale_hits(self):
        """When each chorus bar's sung word starts: on its third beat, where
        the word's first syllable begins (the forced alignment puts it 0.88 s
        into the bar); the louder onset measured half a beat later is the
        word's second syllable."""
        b = self.song.beats
        return [b[4 * bar + 2] - 0.04 for bar in range(CHORUS_BAR, CHORUS_BAR + 4)]

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
        # The icon a little smaller and higher than the keys it grew from, so
        # the name, the tagline and the address fit under it with room.
        s = 2.2
        start = self.song.bar_time(CHORUS_BAR)
        place = self.PLACE
        # From the chorus's first beat every key glides into the nearest of the
        # icon's three, which grow and travel into the icon's shape; on the
        # first sung word it locks.
        gather = in_out_cubic(span(t, start, hits[0] + 0.12))
        ks = place[2] / kb.width

        def icon_at(k):
            sx, sy = self.key_xy(k["label"], place)
            tx, ty = 960 + (k["centre"][0] - mx) * s, ICON_Y + (k["centre"][1] - my) * s
            return lerp(sx, tx, gather), lerp(sy, ty, gather), lerp(ks, s, gather)

        if gather < 1:
            # The case shrinking round the three keys as it fades.
            cx, cy, width = place
            h = kb.height * ks
            box = (cx - width / 2 - 26, cy - h / 2 - 26, width + 52, h + 52)
            target = (960 - 1.2 * kb.cell * s, ICON_Y - 1.1 * kb.cell * s, 2.4 * kb.cell * s, 2.2 * kb.cell * s)
            bx, by, bw, bh = (lerp(a, b, gather) for a, b in zip(box, target))
            rounded_rect_path(ctx, bx, by, bw, bh, 28)
            ctx.set_source_rgba(*DUSK_BG, 1 - span(gather, 0, 0.5))
            ctx.fill()
            for key in kb.keys:
                if key["label"] in ("r", "d", "f"):
                    continue
                home = min(range(3), key=lambda i: math.dist(key["centre"], keys[i]["centre"]))
                hx, hy, _ = icon_at(keys[home])
                sx, sy = self.key_xy(key["label"], place)
                # Keys farther out start a little later, so the board folds inwards.
                d = math.dist(key["centre"], keys[home]["centre"]) / kb.width
                g = in_out_cubic(span(t, start + d * 0.25, hits[0] + 0.12))
                x, y = lerp(sx, hx, g), lerp(sy, hy, g)
                sc = ks * lerp(1, 0.25, g)
                colour = mix(DUSK_ROWS[key["row"]] if key["row"] is not None else DUSK_OTHER, flow(home / 2), g)
                # The key's own shape turns into a small hexagon on the way.
                shape = self.key_hexagon(key)
                ctx.save()
                ctx.translate(x, y)
                ctx.scale(sc, sc)
                ctx.translate(-key["centre"][0], -key["centre"][1])
                polygon(ctx, eras.morph(shape[0], shape[1], in_out_cubic(span(g, 0.0, 0.5))))
                ctx.set_source_rgba(*colour, 1 - span(g, 0.75, 1))
                ctx.fill()
                text = {"⏎": "↵", "space": "", "123": "@#€"}.get(key["label"], key["label"])
                if text and g < 0.4:
                    size = kb.cell * (0.36 if len(text) == 1 else 0.22)
                    self.sans.text(ctx, text, key["centre"][0], key["centre"][1] + size * 0.36, size, DUSK_LABEL, align=0.5, alpha=1 - g / 0.4)
                ctx.restore()
        # A honeycomb drawing itself outwards behind the icon, pulsing on the beat.
        beats = [bt for bt in self.song.beats if hits[0] <= bt <= self.song.bar_time(END_BAR)]
        # A heartbeat on each sung word of the chorus: a thump and a smaller
        # one after it, then rest until the next.
        pulse = 0.0
        for at in hits:
            if at <= t:
                d = t - at
                pulse = max(pulse, math.exp(-d * 6) * (d >= 0), 0.55 * math.exp(-(d - 0.2) * 6) * (d >= 0.2))
        reach = out_cubic(span(t, hits[0], hits[0] + 3.0)) * 1300
        r = 64
        for row in range(-9, 10):
            for col in range(-11, 12):
                cx = 960 + col * r * math.sqrt(3) + (r * math.sqrt(3) / 2 if row % 2 else 0)
                cy = ICON_Y + row * r * 1.5
                d = math.hypot(cx - 960, cy - ICON_Y)
                if d > reach or d < 230:
                    continue
                fade = clamp((reach - d) / 250)
                hexagon(ctx, cx, cy, r * (0.94 - 0.06 * pulse * math.exp(-d / 600)))
                ctx.set_source_rgba(*flow(clamp(d / 1100)), (0.10 + 0.25 * pulse * math.exp(-d / 500)) * fade)
                ctx.set_line_width(1.5)
                ctx.stroke()
        # Everything shown before, a word per beat, in cells round the icon.
        recap = ["FLICK", "SLIDE", "GLIDE", "SWIPE", "HOLD", "47 LANGUAGES", "SPEAK", "PASSWORDS", "CLIPBOARD", "EMOJI", "THEMES", "OPEN SOURCE"]
        # Two columns at the sides and two spots at the top, clear of the name,
        # the tagline and the address under the icon.
        spots = [(-700, -330), (700, -330), (-700, -170), (700, -170), (-700, -10), (700, -10), (-700, 150), (700, 150),
                 (-700, 310), (700, 310), (-300, -360), (300, -360)]
        after = [bt for bt in beats if bt >= hits[0] + 0.2]
        for i, (word, (dx, dy)) in enumerate(zip(recap, spots)):
            if i >= len(after) or t < after[i]:
                continue
            p = out_back(span(t, after[i], after[i] + 0.25), 2.2)
            dim = 1 - 0.55 * span(t, after[i] + 0.4, after[i] + 1.0)
            out = 1.0
            self.heavy.text(ctx, word, 960 + dx, ICON_Y + dy + 16, 40 * max(p, 0.01), mix(PAPER, flow(i / 11), 0.5), align=0.5, alpha=dim * out)
        breathe = 1 + 0.07 * pulse
        ctx.translate(960, ICON_Y)
        ctx.scale(breathe, breathe)
        ctx.translate(-960, -ICON_Y)
        settle = gather
        for i, (k, letter, angle) in enumerate(zip(keys, "ehx", (0, -30, 30))):
            px, py, sc = icon_at(k)
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
            if settle < 0.5:
                size = kb.cell * 0.36
                self.sans.text(ctx, k["label"], k["centre"][0], k["centre"][1] + size * 0.36, size, DUSK_LABEL, align=0.5, alpha=1 - settle * 2)
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
                hexagon(ctx, 960, ICON_Y, 200 + out_cubic(ring) * 1000, ring * 0.4)
                ctx.set_source_rgba(*flow(k / 2), 0.7 * (1 - ring))
                ctx.set_line_width(4)
                ctx.stroke()
        self.slam(ctx, self.heavy, "hexkeys", 960, 860, 130, t / 0.05, hits[2] / 0.05, PAPER, align=0.5, stagger=0.6, dur=4)
        # The tagline under the name, its (h)ex in the accent.
        tag = "type faster and more (h)exact!"
        k = out_expo(span(t, hits[2] + 0.25, hits[2] + 0.6))
        if k > 0:
            size = 44
            x = 960 - self.sans_bold.width(tag, size) / 2
            y = 930 + (1 - k) * 20
            self.sans_bold.text(ctx, tag[:21], x, y, size, PAPER, alpha=k)
            x += self.sans_bold.width(tag[:21], size)
            self.sans_bold.text(ctx, "(h)ex", x, y, size, GREEN, alpha=k)
            x += self.sans_bold.width("(h)ex", size)
            self.sans_bold.text(ctx, "act!", x, y, size, PAPER, alpha=k)
        self.typed(ctx, self.mono, "github.com/nanbiz/hexkeys", 960, 995, 28, t, hits[3], DIM, rate=0.02, align=0.5)
        ctx.restore()

    # ------------------------------------------------ frames

    def scene(self, ctx, t):
        bar, x = self.song.bar_at(t)
        # The cut starts a moment before bar 16's line, still inside bar 15.
        bar = max(bar, FIRST_BAR)
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
