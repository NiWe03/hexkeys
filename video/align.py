"""Times the sung words of the trailer's track: the beat grid from librosa,
then each lyric line force-aligned by whisperx's wav2vec2 aligner inside its
own bar, one line to a bar from the first beat on. Writes the beats and every
word's onset; prints only counts and how far onsets sit from the
eighth-note grid.

Usage:
  nix shell --impure --expr 'with import (builtins.getFlake "nixpkgs") {}; python3.withPackages (p: [ p.whisperx p.librosa ])' \\
    -c python3 -I video/align.py TRACK.wav LYRICS.txt BARS OUT.json
with LYRICS.txt one sung line per line and BARS how many lines to time.
The track and the lyrics stay in video/out, which git ignores.
"""

import json
import sys

import librosa
import numpy as np
import whisperx


def main() -> None:
    audio_path, lyrics_path, bars, out_path = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    y, sr = librosa.load(audio_path, sr=22050)
    _, beats = librosa.beat.beat_track(y=y, sr=sr, units="time", tightness=400)
    beats = [float(b) for b in beats]
    lines = open(lyrics_path).read().splitlines()[:bars]
    audio = whisperx.load_audio(audio_path)
    model, meta = whisperx.load_align_model(language_code="en", device="cpu")
    segments = [{"start": max(0.0, beats[4 * k] - 0.12), "end": beats[4 * k + 4] + 0.05, "text": line} for k, line in enumerate(lines)]
    aligned = whisperx.align(segments, model, meta, audio, "cpu", return_char_alignments=False)
    eighths = np.array([v for k in range(4 * (bars + 1)) for v in (beats[k], (beats[k] + beats[k + 1]) / 2)])
    out, offsets = [], []
    for k, segment in enumerate(aligned["segments"]):
        words = []
        for w in segment.get("words", []):
            t = w.get("start")
            if t is not None:
                offsets.append(abs(t - eighths[np.argmin(np.abs(eighths - t))]))
            words.append({"word": w["word"], "t": t, "end": w.get("end"), "score": w.get("score")})
        out.append({"line": k, "bar": k, "start": beats[4 * k], "end": beats[4 * k + 4], "words": words})
    json.dump({"beats": beats, "lines": out}, open(out_path, "w"), indent=1)
    o = np.array(offsets)
    print(f"{len(o)} words timed; from the nearest eighth: median {np.median(o) * 1000:.0f} ms, 90% under {np.percentile(o, 90) * 1000:.0f} ms")


if __name__ == "__main__":
    main()
