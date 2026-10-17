"""Writes the trailer's music and the beat map the video is cut to.

Usage: nix shell --impure --expr 'with import (builtins.getFlake "nixpkgs") {}; python3.withPackages (p: [ p.numpy p.scipy ])' \
         -c python3 -I video/music.py   (writes video/out/music.wav and video/out/beats.json)

Everything is synthesised here, so the track is original: a kick, a clap, hats,
a sub bass, a plucked chord riff, a key click and a hexagon pop, and for the
history at the start the sound of each era's typing: a typewriter's clacks and
bell, a buckling-spring keyboard's clicks, a phone keypad's beeps and a
touchscreen's ticks, each era adding its sound to the ones before. At BPM beats a minute in 4/4, the
sections run as SECTIONS lists them, in bars. beats.json holds the time of
every beat and bar and where each section starts, which the video's cuts
follow.
"""

import json
import math
import pathlib
import wave

import numpy as np
from scipy import signal

RATE = 44100
BPM = 140
BEAT = 60 / BPM
BAR = 4 * BEAT
OUT = pathlib.Path(__file__).resolve().parent / "out"

# Name and length in bars, in order; the video's acts follow them.
SECTIONS = [
    ("history", 8),   # two bars each: typewriter, buckling spring, keypad, touchscreen
    ("hexagons", 4),  # pad and pops, a roll and a rising sweep in the last bar
    ("drop", 12),     # everything: the gestures
    ("voice", 4),     # pad and pops, the drums out
    ("drop2", 8),     # everything, the riff an octave up: the montage
    ("end", 2),       # one last hit ringing out
]

# A minor: the riff's chords, a bar each, as semitones over A2.
CHORDS = [[0, 3, 7, 12], [-4, 0, 3, 8], [-9, -5, 0, 3], [-2, 2, 5, 10]]
A2 = 110.0


def hz(semitones: float, base: float = A2) -> float:
    return base * 2 ** (semitones / 12)


def envelope(n: int, attack: float, decay: float) -> np.ndarray:
    t = np.arange(n) / RATE
    rise = np.clip(t / max(attack, 1e-4), 0, 1)
    return rise * np.exp(-t / decay)


def kick(n=int(0.45 * RATE)) -> np.ndarray:
    t = np.arange(n) / RATE
    pitch = 52 + 120 * np.exp(-t / 0.03)
    phase = 2 * np.pi * np.cumsum(pitch) / RATE
    body = np.sin(phase) * np.exp(-t / 0.09)
    click = np.random.default_rng(1).standard_normal(n) * np.exp(-t / 0.004) * 0.3
    return np.tanh(1.8 * (body + click))


def clap(n=int(0.3 * RATE)) -> np.ndarray:
    rng = np.random.default_rng(2)
    t = np.arange(n) / RATE
    noise = rng.standard_normal(n)
    # Three quick bursts then a tail, as hands do.
    env = sum(np.exp(-np.clip(t - d, 0, None) / 0.008) * (t >= d) for d in (0, 0.011, 0.022)) + 0.6 * np.exp(-t / 0.09)
    return bandpass(noise * env, 1200, 0.9) * 0.9


def hat(n=int(0.06 * RATE), open_=False) -> np.ndarray:
    if open_:
        n = int(0.25 * RATE)
    t = np.arange(n) / RATE
    noise = np.random.default_rng(3).standard_normal(n)
    return highpass(noise, 7000) * np.exp(-t / (0.08 if open_ else 0.015)) * 0.5


def key_click(n=int(0.03 * RATE)) -> np.ndarray:
    """A key being tapped: a very short bright tick."""
    t = np.arange(n) / RATE
    noise = np.random.default_rng(4).standard_normal(n)
    return bandpass(noise, 3500, 1.5) * np.exp(-t / 0.004) * 0.8


def pop(semitones: float, n=int(0.25 * RATE)) -> np.ndarray:
    """A hexagon popping into place: a sine dropping into its pitch."""
    t = np.arange(n) / RATE
    f = hz(semitones, 440) * (1 + 0.6 * np.exp(-t / 0.012))
    phase = 2 * np.pi * np.cumsum(f) / RATE
    return np.sin(phase) * envelope(n, 0.002, 0.07) * 0.5


def typewriter(n=int(0.09 * RATE)) -> np.ndarray:
    """A typebar striking the platen: a hard knock with a metal ring."""
    t = np.arange(n) / RATE
    noise = np.random.default_rng(6).standard_normal(n)
    knock = bandpass(noise, 1800, 1.2) * np.exp(-t / 0.012)
    ring = np.sin(2 * np.pi * 3100 * t) * np.exp(-t / 0.03) * 0.25
    return (knock + ring) * 0.9


def bell(n=int(1.2 * RATE)) -> np.ndarray:
    """The typewriter's end-of-line bell: inharmonic partials ringing out."""
    t = np.arange(n) / RATE
    partials = [(2400, 1.0), (2400 * 2.76, 0.5), (2400 * 5.4, 0.25), (2400 * 0.5, 0.3)]
    return sum(a * np.sin(2 * np.pi * f * t) * np.exp(-t / (0.5 / (1 + i))) for i, (f, a) in enumerate(partials)) * 0.3


def spring_click(n=int(0.05 * RATE)) -> np.ndarray:
    """A buckling spring giving way: a click and a short spring buzz."""
    t = np.arange(n) / RATE
    noise = np.random.default_rng(8).standard_normal(n)
    click = highpass(noise, 2500) * np.exp(-t / 0.003)
    buzz = np.sin(2 * np.pi * 900 * t) * np.exp(-t / 0.012) * 0.3
    return (click + buzz) * 0.8


def beep(semitones: float, length: float) -> np.ndarray:
    """A phone keypad's square-wave beep."""
    n = int(length * RATE)
    t = np.arange(n) / RATE
    square = np.sign(np.sin(2 * np.pi * hz(semitones, 440) * t))
    return lowpass(square, 6000) * np.minimum(1, np.minimum(t / 0.002, (length - t) / 0.005)) * 0.18


def pluck(semitones: float, length: float) -> np.ndarray:
    n = int(length * RATE)
    t = np.arange(n) / RATE
    f = hz(semitones, 220)
    saw = sum(np.sin(2 * np.pi * f * k * t + 0.3 * k) / k for k in range(1, 9))
    tone = saw * envelope(n, 0.003, 0.18)
    return lowpass_sweep(tone, 6000, 1200, 0.12) * 0.5


def bass(semitones: float, length: float) -> np.ndarray:
    n = int(length * RATE)
    t = np.arange(n) / RATE
    f = hz(semitones)
    tone = np.sin(2 * np.pi * f * t) + 0.25 * np.sin(4 * np.pi * f * t)
    gate = np.minimum(1, np.minimum(t / 0.005, (length - t) / 0.02))
    return np.tanh(1.5 * tone) * gate * 0.55


def pad(chord: list[int], length: float) -> np.ndarray:
    n = int(length * RATE)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for s in chord:
        for detune in (-0.08, 0.08):
            f = hz(s + detune, 220)
            out += np.sin(2 * np.pi * f * t + np.sin(2 * np.pi * 0.3 * t))
    swell = np.minimum(1, t / 0.4) * np.minimum(1, (length - t) / 0.3)
    return out / (2 * len(chord)) * swell * 0.25


# Butterworth filters; a sweep is cut into short blocks, each with its own cutoff.

def lowpass(x: np.ndarray, cutoff: float) -> np.ndarray:
    b, a = signal.butter(2, min(cutoff, RATE / 2.2), "low", fs=RATE)
    return signal.lfilter(b, a, x)


def highpass(x: np.ndarray, cutoff: float) -> np.ndarray:
    b, a = signal.butter(2, cutoff, "high", fs=RATE)
    return signal.lfilter(b, a, x)


def bandpass(x: np.ndarray, centre: float, width: float) -> np.ndarray:
    low, high = centre / (1 + width), min(centre * (1 + width), RATE / 2.2)
    b, a = signal.butter(2, [low, high], "band", fs=RATE)
    return signal.lfilter(b, a, x)


def lowpass_sweep(x: np.ndarray, start: float, end: float, time: float) -> np.ndarray:
    block = 256
    out = np.empty_like(x)
    state = None
    for i in range(0, len(x), block):
        cutoff = end + (start - end) * math.exp(-i / RATE / time)
        b, a = signal.butter(2, min(cutoff, RATE / 2.2), "low", fs=RATE)
        if state is None:
            state = signal.lfilter_zi(b, a) * 0
        out[i:i + block], state = signal.lfilter(b, a, x[i:i + block], zi=state)
    return out


def main() -> None:
    OUT.mkdir(exist_ok=True)
    bars = sum(length for _, length in SECTIONS)
    total = int((bars * BAR + 2.5) * RATE)
    mix = np.zeros((total, 2))

    def put(sound: np.ndarray, at: float, gain=1.0, pan=0.0):
        i = int(at * RATE)
        j = min(total, i + len(sound))
        left, right = math.cos((pan + 1) * math.pi / 4), math.sin((pan + 1) * math.pi / 4)
        mix[i:j, 0] += sound[: j - i] * gain * left * math.sqrt(2)
        mix[i:j, 1] += sound[: j - i] * gain * right * math.sqrt(2)

    k, c, h, ho, tick = kick(), clap(), hat(), hat(open_=True), key_click()
    knock, ding, spring_ = typewriter(), bell(), spring_click()
    sections = []
    bar = 0
    for name, length in SECTIONS:
        sections.append({"name": name, "bar": bar, "time": round(bar * BAR, 4)})
        for b in range(bar, bar + length):
            t0 = b * BAR
            local = b - bar
            chord = CHORDS[b % len(CHORDS)]
            full = name in ("drop", "drop2")
            if name == "history":
                era = local // 2
                put(pad(chord, BAR), t0, 0.35 + 0.12 * era)
                # Each era keeps the sounds of the ones before, quieter.
                if era >= 0:
                    for s_ in (0, 3, 6, 8, 11, 14):
                        put(knock, t0 + s_ * BEAT / 4, 0.7 if era == 0 else 0.35, pan=-0.3)
                    if local == 1:
                        put(ding, t0 + 3 * BEAT, 0.8, pan=0.3)
                if era >= 1:
                    for s_ in range(0, 16, 2):
                        put(spring_, t0 + s_ * BEAT / 4 + BEAT / 8, 0.7 if era == 1 else 0.3, pan=0.3)
                    put(k, t0, 0.6)
                    put(k, t0 + 2 * BEAT, 0.6)
                if era >= 2:
                    melody = [7, 12, 10, 7, 5, 3, 5, 7]
                    for e, m in enumerate(melody):
                        put(beep(m + chord[0], BEAT * 0.4), t0 + e * BEAT / 2, 0.8 if era == 2 else 0.35)
                    for beat in range(4):
                        put(h, t0 + beat * BEAT + BEAT / 2, 0.6)
                if era >= 3:
                    for s_ in range(16):
                        if s_ % 4 in (0, 3) or (s_ % 4 == 2 and b % 2):
                            put(tick, t0 + s_ * BEAT / 4, 0.6, pan=0.3 * math.sin(s_))
                    for beat in range(4):
                        put(pop(chord[beat % 4] + 12), t0 + beat * BEAT, 0.6, pan=-0.2)
            if name == "hexagons":
                put(pad(chord, BAR), t0, 0.9)
                for beat in range(4):
                    put(pop(chord[beat % 4] + 12 + 12 * (local % 2)), t0 + beat * BEAT, 0.7, pan=-0.2)
                    put(pop(chord[(beat + 2) % 4] + 12), t0 + beat * BEAT + BEAT / 2, 0.4, pan=0.3)
                if local == length - 1:
                    for s_ in range(16):
                        put(c, t0 + s_ * BAR / 16, 0.2 + 0.6 * s_ / 16)
                    sweep_n = int(2 * BAR * RATE)
                    tt = np.arange(sweep_n) / RATE
                    noise = np.random.default_rng(5).standard_normal(sweep_n)
                    put(highpass(noise, 2000) * (tt / tt[-1]) ** 2 * 0.25, t0 - BAR)
                elif local == length - 2:
                    for s_ in range(8):
                        put(c, t0 + s_ * BAR / 8, 0.15 + 0.2 * s_ / 8)
            if full:
                octave = 12 if name == "drop2" else 0
                for beat in range(4):
                    put(k, t0 + beat * BEAT, 0.8)
                    put(bass(chord[0], BEAT * 0.45), t0 + beat * BEAT + BEAT / 2, 0.42)
                    if beat in (1, 3):
                        put(c, t0 + beat * BEAT, 0.8)
                    put(h, t0 + beat * BEAT + BEAT / 2, 0.8, pan=0.4)
                    put(ho, t0 + beat * BEAT + 3 * BEAT / 4, 0.3, pan=-0.4)
                pattern = [0, 2, 1, 3, 2, 1, 3, 0]
                for e, step in enumerate(pattern):
                    if e in (2, 6) and b % 2 == 0:
                        continue
                    at = t0 + e * BEAT / 2
                    put(pluck(chord[step] + octave, BEAT * 0.6), at, 0.9, pan=0.25 if e % 2 else -0.25)
                    put(tick, at, 0.35)
                if b % 2 == 1:
                    put(pop(chord[3] + 24), t0 + 3.5 * BEAT, 0.5)
                # Every second bar of the gestures, the era sounds come back as fills.
                if name == "drop" and local % 2 == 1:
                    put(knock, t0 + 3.75 * BEAT, 0.4)
                    put(spring_, t0 + 3.5 * BEAT, 0.4)
            if name == "voice":
                put(pad(chord, BAR), t0, 1.0)
                for beat in range(4):
                    put(pop(chord[(beat * 2) % 4] + 12), t0 + beat * BEAT + (BEAT / 2 if beat % 2 else 0), 0.6, pan=0.5 * (-1) ** beat)
                if local == length - 1:
                    for s_ in range(8):
                        put(c, t0 + 2 * BEAT + s_ * BEAT / 4, 0.2 + 0.5 * s_ / 8)
            if name == "end" and local == 0:
                put(k, t0, 1.2)
                put(c, t0, 1.0)
                put(pluck(chord[0], 2.0), t0, 1.0)
                put(pad(CHORDS[0], 3.0), t0, 1.2)
                put(ding, t0 + 0.02, 0.5)
                for i, s_ in enumerate((0, 7, 12, 19, 24)):
                    put(pop(s_), t0 + i * 0.06, 0.5)
        bar += length

    # Glue: soft clip, then normalise.
    mix = np.tanh(mix * 0.9)
    mix /= np.max(np.abs(mix)) * 1.05
    pcm = (mix * 32767).astype("<i2")
    with wave.open(str(OUT / "music.wav"), "wb") as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(pcm.tobytes())
    beats = [round(i * BEAT, 4) for i in range(bars * 4 + 1)]
    (OUT / "beats.json").write_text(json.dumps({"bpm": BPM, "beat": BEAT, "bar": BAR, "beats": beats, "sections": sections}, indent=1))
    print(f"{bars} bars, {bars * BAR:.1f} s")


if __name__ == "__main__":
    main()
