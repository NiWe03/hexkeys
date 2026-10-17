"""Builds the emoji palette from Unicode's emoji-test.txt.

Usage: python3 tools/emoji.py > android/app/src/main/assets/emoji.tsv

Keeps the fully-qualified emoji of each group in Unicode's own order, which
it recommends for keyboard palettes, leaves out the skin-tone and hair
components and the variants built from them, and writes one line per group:
its name, a tab, and its emoji separated by spaces.
"""

import re
import sys
import urllib.request

VERSION = "16.0"
URL = f"https://unicode.org/Public/emoji/{VERSION}/emoji-test.txt"

# Skin tones; emoji carrying one are variants of the plain emoji.
TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}

groups: list[tuple[str, list[str]]] = []
with urllib.request.urlopen(URL) as response:
    for line in response.read().decode("utf-8").splitlines():
        if line.startswith("# group: "):
            groups.append((line[len("# group: "):], []))
            continue
        match = re.match(r"^([0-9A-F ]+);\s*fully-qualified\s*#", line)
        if not match or not groups:
            continue
        emoji = "".join(chr(int(code, 16)) for code in match.group(1).split())
        if any(c in TONES for c in emoji):
            continue
        groups[-1][1].append(emoji)

for name, emoji in groups:
    if name == "Component" or not emoji:
        continue
    sys.stdout.write(f"{name}\t{' '.join(emoji)}\n")
