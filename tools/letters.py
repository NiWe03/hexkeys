"""Writes the letters keys type, for every letter the keys do not have: the
plain letters it is written with in ASCII, from anyascii's transliteration.

Usage: uv run --with anyascii==0.3.3 python3 -I tools/letters.py   (writes src/letters.rs)

Covers the lower-case letters of the Latin blocks and of Greek, whose ε and γ
some Latin orthographies (Kabyle) write for ɛ and ɣ. A letter is kept where
its transliteration is one or more ASCII letters: "ə" is "e", "þ" is "th",
"æ" is "ae". Unicode's decompositions alone leave out the letters without a
mark on a base letter (ð þ ə ɣ ø ł), which whole languages use.
"""

import pathlib
import unicodedata

from anyascii import anyascii

BLOCKS = [
    (0x00C0, 0x024F),  # Latin-1 Supplement, Latin Extended-A and -B
    (0x0250, 0x02AF),  # IPA Extensions
    (0x02B0, 0x02FF),  # Spacing Modifier Letters
    (0x0370, 0x03FF),  # Greek
    (0x1D00, 0x1DBF),  # Phonetic Extensions
    (0x1E00, 0x1EFF),  # Latin Extended Additional
    (0x2C60, 0x2C7F),  # Latin Extended-C
    (0xA720, 0xA7FF),  # Latin Extended-D
    (0xAB30, 0xAB6F),  # Latin Extended-E
]
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/letters.rs"


def main() -> None:
    entries = []
    for first, last in BLOCKS:
        for code in range(first, last + 1):
            c = chr(code)
            if not unicodedata.category(c).startswith("L") or c.lower() != c:
                continue
            plain = anyascii(c).lower()
            if plain.isascii() and plain.isalpha():
                entries.append((c, plain))
    lines = [
        "//! Written by tools/letters.py from anyascii's transliteration: every",
        "//! lower-case letter the keys do not have, with the plain letters it is",
        "//! written with, sorted for binary search.",
        "",
        "pub(crate) const LETTERS: &[(char, &str)] = &[",
    ]
    for c, plain in sorted(entries):
        lines.append(f"    ('\\u{{{ord(c):04x}}}', \"{plain}\"), // {c} {unicodedata.name(c, '')}")
    lines.append("];")
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(entries)} letters")


if __name__ == "__main__":
    main()
