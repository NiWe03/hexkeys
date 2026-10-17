"""Builds the catalog of downloadable dictionaries from the aosp-dictionaries README.

Usage: python3 tools/dictionaries.py > android/app/src/main/assets/dictionaries.tsv

Reads the table of the README at COMMIT, keeps one main dictionary per
language, and only languages written in Latin script, the only letters the
layout has. Per language it prefers a dictionary with next-word data, then
one not marked legacy, then the one with more words. The app downloads the
files from the same commit, so catalog and files always match.
"""

import re
import sys
import urllib.request

COMMIT = "ff0cd52be8d9530200fefe8b296c53d4f662091e"
README = f"https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/{COMMIT}/README.md"

# Languages whose dictionaries are not in Latin script; the *_zz variants
# (Serbian, Hindi and Tamazight in Latin letters) are kept.
NOT_LATIN = {
    "ar", "as", "be", "bg", "bn", "el", "fa", "gom", "gu", "he", "hi", "hy", "iw", "ka", "kk", "km", "kn",
    "ks", "mai", "mk", "ml", "mr", "ne", "or", "pa", "ru", "sa", "sat", "sd", "sr", "ta", "tcy",
    "te", "th", "uk", "ur", "zgh",
}

# The dictionary built into the app.
BUILT_IN = "main_en_us.dict"

ROW = re.compile(
    r"^\| (?P<language>[^|]+?) \| \[main\]\(\S+/dictionaries/(?P<file>main_[^)]+\.dict)\) \| (?P<kind>[^|]*?) \| "
    r"(?P<next>yes|no) \| (?P<words>\d+) \| [^|]* \| (?P<source>.*) \|$"
)


def code(file: str) -> str:
    """The locale in a file name: main_de_at__legacy.dict is de_at."""
    return file.removeprefix("main_").removesuffix(".dict").split("__")[0]


def main() -> None:
    readme = urllib.request.urlopen(README).read().decode()
    best: dict[str, tuple] = {}
    for line in readme.splitlines():
        match = ROW.match(line)
        if not match:
            continue
        locale = code(match["file"])
        if locale.split("_")[0] in NOT_LATIN and not locale.endswith("_zz"):
            continue
        rank = (match["next"] == "yes", match["kind"] != "legacy", int(match["words"]))
        if match["file"] == BUILT_IN:
            rank = (True, True, sys.maxsize)
        license = re.sub(r"\[([^]]*)\]\([^)]*\)", r"\1", match["source"]).removeprefix("source, ").strip()
        entry = (rank, locale, match["file"], license)
        language = match["language"].strip()
        if language not in best or rank > best[language][0]:
            best[language] = entry
    print("# language\tlocale\tfile\tlicense")
    for language, (_, locale, file, license) in sorted(best.items()):
        print(f"{language}\t{locale}\t{file}\t{license}")


main()
