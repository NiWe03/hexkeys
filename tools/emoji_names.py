"""Builds the emoji search names from Unicode CLDR's annotations.

Usage: python3 -I tools/emoji_names.py   (writes android/app/src/main/assets/emoji-names/<language>.tsv)

Takes English and every language of the dictionary catalog (assets/dictionaries.tsv)
that CLDR has annotations for, keeps the emoji of assets/emoji.tsv, and writes one
file per language with a line per emoji: the emoji exactly as emoji.tsv spells it, a
tab, and its CLDR name followed by its keywords, lowercased and joined by "|".

A file is named by the catalog locale with the country variant dropped (de_at and
de_ch share de.tsv, en_au and en_gb share en.tsv, pt_br is pt), except pt_pt, which
CLDR annotates apart from Brazilian Portuguese, and the _zz script entries (hi_zz,
sr_zz), which are the Latin-script locales. The base annotations come first and the
derived ones (sequences, skin tones) only fill emoji the base lacks. Languages naming
fewer than a tenth of the emoji are left out.
"""

import json
import pathlib
import sys
import urllib.error
import urllib.request

TAG = "48.2.3"
BASE = f"https://raw.githubusercontent.com/unicode-org/cldr-json/{TAG}/cldr-json/"
ASSETS = pathlib.Path(__file__).resolve().parent.parent / "android/app/src/main/assets"
OUT = ASSETS / "emoji-names"

# Catalog locales whose CLDR locale is not the locale itself.
CLDR = {"nb": "no", "tl": "fil", "hi_zz": "hi-Latn", "sr_zz": "sr-Latn", "pt_pt": "pt-PT"}
KEEP = {"pt_pt", "hi_zz", "sr_zz", "zgh_zz"}
VS16 = "️"


def fetch(kind: str, locale: str) -> dict | None:
    url = f"{BASE}cldr-annotations-{'derived-' if kind == 'annotationsDerived' else ''}full/{kind}/{locale}/annotations.json"
    try:
        with urllib.request.urlopen(url) as response:
            return json.load(response)[kind]["annotations"]
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def clean(text: str) -> str:
    return text.replace("|", " ").replace("\t", " ").replace("\n", " ").strip().lower()


# File name -> CLDR locale
targets: dict[str, str] = {"en": "en"}
skipped = []
for line in (ASSETS / "dictionaries.tsv").read_text(encoding="utf-8").splitlines():
    if not line.strip() or line.startswith("#"):
        continue
    locale = line.split("\t")[1]
    name = "pt" if locale == "pt_br" else locale if locale in KEEP else locale.split("_")[0]
    targets.setdefault(name, CLDR.get(locale, CLDR.get(name, name)))

wanted = {}
for row in (ASSETS / "emoji.tsv").read_text(encoding="utf-8").splitlines():
    for emoji in row.split("\t")[1].split():
        wanted[emoji.replace(VS16, "")] = emoji

OUT.mkdir(exist_ok=True)
for old in OUT.glob("*.tsv"):
    old.unlink()
sizes = {}
for name, locale in sorted(targets.items()):
    base = fetch("annotations", locale)
    if base is None:
        skipped.append(f"{name} (CLDR {locale})")
        continue
    derived = fetch("annotationsDerived", locale) or {}
    base_index = {k.replace(VS16, ""): v for k, v in base.items()}
    derived_index = {k.replace(VS16, ""): v for k, v in derived.items()}
    out = []
    missing = 0
    for key, emoji in wanted.items():
        entry = base_index.get(key) or derived_index.get(key)
        if not entry:
            missing += 1
            continue
        words: list[str] = []
        for word in entry.get("tts", []) + entry.get("default", []):
            word = clean(word)
            if word and word not in words:
                words.append(word)
        out.append(f"{emoji}\t{'|'.join(words)}\n")
    # A language naming hardly any emoji adds nothing English does not.
    if len(out) < len(wanted) / 10:
        skipped.append(f"{name} (CLDR {locale}, {len(out)} names)")
        continue
    path = OUT / f"{name}.tsv"
    path.write_text("".join(out), encoding="utf-8")
    sizes[name] = path.stat().st_size
    print(f"{name}.tsv from CLDR {locale}: {len(out)} emoji, {missing} without a name, {sizes[name]} bytes", file=sys.stderr)

print(f"total {sum(sizes.values())} bytes; no CLDR annotations: {', '.join(skipped) or 'none'}", file=sys.stderr)
