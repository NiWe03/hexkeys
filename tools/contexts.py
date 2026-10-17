"""Builds the context counts autocorrect uses to tell apart words that differ only in
apostrophes, accents and capitals ("its" and "it's", "weg" and "Weg").

Usage: python3 -I tools/contexts.py CACHE [LANGUAGE...]

Writes each language's counts to target/contexts/<language>.tsv, then packages every
language built so far: English, built in like its dictionary, is copied to the app's
assets as contexts/en.tsv; the others are gzipped to target/contexts/<language>.tsv.gz,
the files the app downloads along with a dictionary of their language from the GitHub
release named in Dictionaries.kt, and listed with their SHA-256 in the assets'
contexts.tsv, which the app checks each download against.

Each language's text is the Leipzig Corpora Collection's largest web, news or mixed
corpus of it, up to a million sentences, downloaded into CACHE once; Wikipedia only
where it has nothing else. Languages it has no corpus of in the dictionary's script
(Kabyle, Serbian in Latin script, Tamazight, Toki Pona, Hinglish) get no counts, and
neither does Vietnamese, where nearly every word has several tone spellings and its
neighbours do not tell them apart well enough: "viet nam" came out "viết nam". A confusion set is a word written plainly, in
lower case without apostrophes or accents, together with the other spellings it folds
to, each written at least MIN_COUNT times away from a sentence's start (the first word,
and any after a full stop, question or exclamation mark, or colon). All-capital
spellings are left out, and so are capitalised ones written less than a tenth as
often as in lower case, which are words in titles and names ("The", "New").

For each spelling the file holds how often it was written and how often each word came
right before and right after it, lowercased, where that happened at least twice; a
sentence's start and end and the punctuation marks count as words. A word starting a
sentence counts with its first letter lowered where its set has no capitalised spelling,
and where it has, for both by how often each is written elsewhere. A line per
spelling: the plain word, the spelling, its count, then the words before and the words
after as space-separated word:count pairs, all separated by tabs.

Before writing, a tenth of the sentences is held back and each plain use in it is
judged against its set as autocorrect judges it (see src/context.rs) with the rest's
counts, and each use of another spelling with the plain word put in its place. A set
whose plain uses get changed more than a quarter as often as the other spellings get
put back is left out. The counts written come from all sentences.
"""

import collections
import gzip
import hashlib
import math
import pathlib
import random
import re
import sys
import tarfile
import unicodedata
import urllib.request

CORPORA = {
    "af": "afr_mixed_2019_1M",
    "az": "aze_newscrawl_2013_1M",
    "bs": "bos_news_2020_300K",
    "ca": "cat_newscrawl_2016_1M",
    "ceb": "ceb_wikipedia_2021_1M",
    "cs": "ces_news_2024_1M",
    "da": "dan_mixed_2014_1M",
    "de": "deu-de_web-public_2019_1M",
    "en": "eng-com_web-public_2018_1M",
    "eo": "epo_mixed_2012_1M",
    "es": "spa_news_2024_1M",
    "et": "est_newscrawl_2017_1M",
    "eu": "eus_newscrawl_2012_300K",
    "fi": "fin_news_2022_1M",
    "fr": "fra_news_2024_1M",
    "ga": "gle_newscrawl_2014_300K",
    "gl": "glg_wikipedia_2021_300K",
    "hr": "hrv_news_2020_1M",
    "hu": "hun_mixed_2012_1M",
    "id": "ind_mixed_2013_1M",
    "is": "isl_newscrawl_2011_1M",
    "it": "ita_news_2024_1M",
    "la": "lat_wikipedia_2021_100K",
    "lb": "ltz_newscrawl_2016_300K",
    "lt": "lit_news_2020_1M",
    "lv": "lav_newscrawl_2016_1M",
    "mg": "mlg_web_2012_30K",
    "mwl": "mwl_wikipedia_2021_30K",
    "nb": "nob_news_2013_1M",
    "nl": "nld_mixed_2012_1M",
    "nn": "nno_wikipedia_2021_300K",
    "pl": "pol_news_2024_1M",
    "pms": "pms_wikipedia_2021_30K",
    "pt": "por_news_2024_1M",
    "ro": "ron_news_2024_1M",
    "sc": "srd_wikipedia_2021_10K",
    "sk": "slk_newscrawl_2016_1M",
    "sl": "slv_news_2020_1M",
    "sv": "swe_news_2023_1M",
    "tl": "tgl_wikipedia_2021_100K",
    "tr": "tur_mixed_2014_1M",
}
URL = "https://downloads.wortschatz-leipzig.de/corpora/{}.tar.gz"
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "target/contexts"
ASSETS = ROOT / "android/app/src/main/assets"
BUILT_IN = "en"

MIN_COUNT = 20
MIN_CONTEXT = 2
# Kept equal to src/context.rs.
SMOOTHING = 0.1
CONTEXTS = 1000.0
MARGIN = 4.0

TOKEN = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*|[.,!?;:]")


def fold(word: str) -> str:
    word = word.lower().replace("'", "").replace("ß", "ss")
    return "".join(c for c in unicodedata.normalize("NFD", word) if not unicodedata.combining(c))


def tokens(line: str) -> list[str]:
    text = line.split("\t", 1)[-1].replace("’", "'").replace("‘", "'")
    return ["<s>"] + TOKEN.findall(text) + ["</s>"]


def starts(words: list[str], i: int) -> bool:
    """Whether the word at `i` starts a sentence, where it takes a capital."""
    return words[i - 1] in ("<s>", ".", "!", "?", ":")


def sentences(cache: pathlib.Path, corpus: str) -> list[str]:
    archive = cache / f"{corpus}.tar.gz"
    if not archive.exists():
        urllib.request.urlretrieve(URL.format(corpus), archive)
    with tarfile.open(archive) as tar:
        # Older archives name their folder and files differently from the corpus.
        member = next(m for m in tar.getmembers() if m.name.endswith("-sentences.txt"))
        return tar.extractfile(member).read().decode("utf-8").splitlines()


class Counts:
    def __init__(self, lines: list[str]):
        self.words = collections.Counter()
        for line in lines:
            words = tokens(line)
            for i in range(1, len(words) - 1):
                if not starts(words, i):
                    self.words[words[i]] += 1
        groups = collections.defaultdict(list)
        for word, count in self.words.items():
            if count >= MIN_COUNT and word[0].isalpha():
                groups[fold(word)].append(word)
        self.sets = {}
        for plain, spellings in groups.items():
            spellings = [w for w in spellings if self.keep(w, plain)]
            if plain in spellings and len(spellings) > 1:
                self.sets[plain] = [plain] + sorted(w for w in spellings if w != plain)
        self.plain = {w: plain for plain, spellings in self.sets.items() for w in spellings}
        self.total = collections.Counter()
        self.left = collections.Counter()
        self.right = collections.Counter()
        for line in lines:
            words = tokens(line)
            for i in range(1, len(words) - 1):
                for word, share in self.shares(words, i):
                    self.total[word] += share
                    self.left[(words[i - 1].lower(), word)] += share
                    self.right[(word, words[i + 1].lower())] += share

    def shares(self, words: list[str], i: int) -> list[tuple[str, float]]:
        """The spellings of a set the word at `i` counts for, with their
        shares. A capital starting a sentence can stand for the capitalised
        spelling and the lower-case one alike; it counts for both by how
        often each is written elsewhere, which tells neither apart."""
        word = words[i]
        if starts(words, i):
            lowered = word[:1].lower() + word[1:]
            if word != lowered and word in self.plain and lowered in self.plain:
                capital, lower = self.words[word], self.words[lowered]
                return [(word, capital / (capital + lower)), (lowered, lower / (capital + lower))]
            if word not in self.plain:
                word = lowered
        return [(word, 1.0)] if word in self.plain else []

    def spelling(self, words: list[str], i: int) -> str | None:
        """The spelling of a set the word at `i` was meant as, or None where a
        capital starting a sentence leaves that open."""
        word = words[i]
        if starts(words, i):
            lowered = word[:1].lower() + word[1:]
            if word != lowered and word in self.plain and lowered in self.plain:
                return None
            if word not in self.plain:
                word = lowered
        return word if word in self.plain else None

    def keep(self, word: str, plain: str) -> bool:
        if len(word) > 1 and word.isupper():
            return False
        lower = word.lower()
        if word != lower:
            return self.words[word] * 10 >= self.words[lower]
        return True

    def score(self, word: str, before: str, after: str) -> float:
        count = self.total[word]
        side = count + SMOOTHING * CONTEXTS
        return (
            math.log(count)
            + math.log((self.left[(before, word)] + SMOOTHING) / side)
            + math.log((self.right[(word, after)] + SMOOTHING) / side)
        )

    def judge(self, plain: str, before: str, after: str) -> str:
        scores = {w: self.score(w, before, after) for w in self.sets[plain]}
        best = max(scores, key=scores.get)
        return best if scores[best] - scores[plain] > MARGIN else plain


def build(language: str, lines: list[str]) -> tuple[str, int, int]:
    random.seed(1)
    shuffled = lines[:]
    random.shuffle(shuffled)
    held = len(shuffled) // 10
    counts = Counts(shuffled[held:])
    broken = collections.Counter()
    fixed = collections.Counter()
    for line in shuffled[:held]:
        words = tokens(line)
        for i in range(1, len(words) - 1):
            word = counts.spelling(words, i)
            if word is None:
                continue
            plain = counts.plain[word]
            judged = counts.judge(plain, words[i - 1].lower(), words[i + 1].lower())
            if word == plain:
                broken[plain] += judged != plain
            else:
                fixed[plain] += judged == word
    good = {plain for plain in counts.sets if broken[plain] * 4 <= fixed[plain] and fixed[plain] > 0}
    counts = Counts(lines)
    before_of = collections.defaultdict(list)
    after_of = collections.defaultdict(list)
    for (context, word), n in counts.left.items():
        if round(n) >= MIN_CONTEXT:
            before_of[word].append((context, round(n)))
    for (word, context), n in counts.right.items():
        if round(n) >= MIN_CONTEXT:
            after_of[word].append((context, round(n)))
    out = []
    for plain in sorted(good & counts.sets.keys()):
        for word in counts.sets[plain]:
            before = sorted(before_of[word])
            after = sorted(after_of[word])
            out.append("\t".join([
                plain, word, str(round(counts.total[word])),
                " ".join(f"{c}:{n}" for c, n in before),
                " ".join(f"{c}:{n}" for c, n in after),
            ]))
    return "\n".join(out) + "\n", len(good), sum(fixed[p] for p in good)


def main():
    cache = pathlib.Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)
    chosen = sys.argv[2:] or list(CORPORA)
    for language in chosen:
        corpus = CORPORA[language]
        text, sets, fixed = build(language, sentences(cache, corpus))
        (OUT / f"{language}.tsv").write_text(text, encoding="utf-8")
        print(f"{language}: {sets} sets, {len(text) // 1024} KiB, {fixed} held-back uses put back")
    package()


def package():
    (ASSETS / "contexts").mkdir(exist_ok=True)
    lines = ["# language\tsha256 of language.tsv.gz"]
    for tsv in sorted(OUT.glob("*.tsv")):
        language = tsv.stem
        data = tsv.read_bytes()
        if language == BUILT_IN:
            (ASSETS / "contexts" / tsv.name).write_bytes(data)
            continue
        # No name or time in the header, so the same counts always hash the same.
        packed = gzip.compress(data, compresslevel=9, mtime=0)
        (OUT / f"{language}.tsv.gz").write_bytes(packed)
        lines.append(f"{language}\t{hashlib.sha256(packed).hexdigest()}")
    (ASSETS / "contexts.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
