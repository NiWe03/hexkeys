#!/usr/bin/env bash
# Builds the trailer into video/out: the key outlines from the layout, the
# music and its beat map, the picture, then both together as trailer.mp4.
# Arguments after the first are passed to render.py (--preview, --from S,
# --to S, --sheet FILE).
set -euo pipefail
cd "$(dirname "$0")/.."
out=video/out
mkdir -p "$out/fonts"

env='with import (builtins.getFlake "nixpkgs") {}; buildEnv { name = "hexkeys-video"; paths = [ (python3.withPackages (p: [ p.numpy p.scipy p.pycairo p.shapely p.fonttools ])) ffmpeg ]; }'
run() { nix shell --impure --expr "$env" -c "$@"; }

font() { find "$(nix build --no-link --print-out-paths "nixpkgs#$1")" -name "$2" | head -1; }
[ -e "$out/fonts/Nunito.ttf" ] || cp "$(font nunito 'Nunito\[wght\].ttf')" "$out/fonts/Nunito.ttf"
[ -e "$out/fonts/Inter.ttf" ] || cp "$(font inter 'InterVariable.ttf')" "$out/fonts/Inter.ttf"
[ -e "$out/fonts/Mono.otf" ] || cp "$(font jetbrains-mono 'JetBrainsMono-Regular.otf')" "$out/fonts/Mono.otf"
[ -e "$out/fonts/Emoji.ttf" ] || cp "$(font noto-fonts-monochrome-emoji NotoEmoji.ttf)" "$out/fonts/Emoji.ttf"

cargo run -q --release --example outlines > "$out/outlines.tsv"
run python3 -I video/music.py
run python3 -I video/render.py "$out/outlines.tsv" "$out/beats.json" "$out/fonts" "$out/picture.mp4" "$@"
case " $* " in *" --sheet "*) exit 0 ;; esac
run ffmpeg -loglevel error -y -i "$out/picture.mp4" -i "$out/music.wav" -c:v copy -c:a aac -b:a 192k -shortest "$out/trailer.mp4"
echo "$out/trailer.mp4"
