#!/usr/bin/env bash
# Builds the trailer into video/out/trailer.mp4: the key outlines from the
# layout, the track cut to song bars 12 to 23 and its chorus (bars 32 to 35),
# the picture timed to the sung words' onsets, then both together.
#
# Needs, in video/out/track (ignored by git): the track as technologic.wav
# and its word timings as verse1.json, written by video/align.py. Arguments
# are passed to technologic.py (--from S, --to S, --sheet FILE, --at S,S).
set -euo pipefail
cd "$(dirname "$0")/.."
out=video/out
track=$out/track
mkdir -p "$out/fonts"

env='with import (builtins.getFlake "nixpkgs") {}; buildEnv { name = "hexkeys-video"; paths = [ (python3.withPackages (p: [ p.numpy p.scipy p.pycairo p.shapely p.fonttools ])) ffmpeg ]; }'
run() { nix shell --impure --expr "$env" -c "$@"; }

font() { find "$(nix build --no-link --print-out-paths "nixpkgs#$1")" -name "$2" | head -1; }
[ -e "$out/fonts/Nunito.ttf" ] || cp "$(font nunito 'Nunito\[wght\].ttf')" "$out/fonts/Nunito.ttf"
[ -e "$out/fonts/Inter.ttf" ] || cp "$(font inter 'InterVariable.ttf')" "$out/fonts/Inter.ttf"
[ -e "$out/fonts/Mono.otf" ] || cp "$(font jetbrains-mono 'JetBrainsMono-Regular.otf')" "$out/fonts/Mono.otf"
[ -e "$out/fonts/Emoji.ttf" ] || cp "$(font noto-fonts-monochrome-emoji NotoEmoji.ttf)" "$out/fonts/Emoji.ttf"

cargo run -q --release --example outlines > "$out/outlines.tsv"
run python3 -I video/technologic.py "$out/outlines.tsv" "$track/verse1.json" "$out/fonts" "$out/picture.mp4" "$@"
case " $* " in *" --sheet "*) exit 0 ;; esac

# The music: bars 12 to 23, then the chorus, joined on its downbeat and faded out.
read -r a0 a1 c0 c1 < <(python3 -c "
import json; b = json.load(open('$track/verse1.json'))['beats']
print(b[48], b[96], b[128], b[144] + 1.5)")
fade=$(python3 -c "print($c1 - $c0 - 1.6)")
run ffmpeg -loglevel error -y -i "$track/technologic.wav" -filter_complex \
  "[0]atrim=$a0:$a1,asetpts=PTS-STARTPTS[a];[0]atrim=$c0:$c1,asetpts=PTS-STARTPTS,afade=t=out:st=$fade:d=1.6[c];[a][c]acrossfade=d=0.03:c1=tri:c2=tri" \
  "$out/music.wav"
run ffmpeg -loglevel error -y -i "$out/picture.mp4" -i "$out/music.wav" -c:v copy -c:a aac -b:a 192k -shortest "$out/trailer.mp4"
echo "$out/trailer.mp4"
