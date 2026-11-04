#!/usr/bin/env python3
"""Build Reev's language assets: word frequencies (English + Hinglish), word-pair counts, contractions.

Inputs (downloaded by `--fetch` into ./.lexicon-cache, not committed):
  * Tatoeba English sentences (CC-BY 2.0 FR) — everyday English with contractions intact
  * hermitdave/FrequencyWords en_50k (OpenSubtitles 2018, CC-BY-SA-4.0) — broad English coverage
  * Abhishekcr448/Hinglish-Everyday-Conversations-1M (MIT) — romanised Hindi/English chat

Outputs (app/src/main/assets/lexicon/):
  words.tsv         word \\t frequency-per-billion \\t tags (e = English, h = Hinglish)
  bigrams.bin       "RVB1", V, N, offsets[V+1], then N × (nextId:int32, count:uint16), each run sorted by count desc
  contractions.tsv  plain \\t contraction \\t contraction_count \\t plain_count \\t ambiguous(0/1)

Run:  ~/.hermes/venvs/lexicon/bin/python tools/build_lexicon.py --fetch
"""
import argparse
import bz2
import collections
import os
import re
import struct
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, ".lexicon-cache")
OUT = os.path.join(ROOT, "app/src/main/assets/lexicon")

SOURCES = {
    "tatoeba": ("eng_sentences.tsv.bz2", "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2"),
    "subs": ("en_50k.txt", "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_50k.txt"),
    "hinglish": ("hinglish.parquet", "https://huggingface.co/api/datasets/Abhishekcr448/Hinglish-Everyday-Conversations-1M/parquet/default/train/0.parquet"),
}

TOKEN = re.compile(r"[a-z]+(?:'[a-z]+)?")
CLAUSE = re.compile(r"[.!?,;:\"()\[\]…]+")
MAX_WORDS = 80000
MAX_BIGRAMS = 400000


def fetch():
    os.makedirs(CACHE, exist_ok=True)
    for name, (fn, url) in SOURCES.items():
        p = os.path.join(CACHE, fn)
        if not os.path.exists(p):
            print("fetching", name)
            subprocess.run(["curl", "-fsSL", "-o", p, url], check=True)


def clauses(text):
    text = text.lower().replace("\u2019", "'").replace("\u2018", "'")
    for part in CLAUSE.split(text):
        toks = TOKEN.findall(part)
        if toks:
            yield toks


def count_corpus(lines):
    uni, bi = collections.Counter(), collections.Counter()
    for line in lines:
        for toks in clauses(line):
            uni.update(toks)
            bi.update(zip(toks, toks[1:]))
    return uni, bi


def tatoeba_lines():
    """Every 50th sentence is held out (written to .lexicon-cache/heldout_en.txt) for honest evaluation."""
    held = open(os.path.join(CACHE, "heldout_en.txt"), "w", encoding="utf-8")
    with bz2.open(os.path.join(CACHE, SOURCES["tatoeba"][0]), "rt", encoding="utf-8") as f:
        for i, line in enumerate(f):
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                if i % 50 == 0:
                    held.write(parts[2] + "\n")
                    continue
                yield parts[2]
    held.close()


def hinglish_lines():
    import pyarrow.parquet as pq
    t = pq.read_table(os.path.join(CACHE, SOURCES["hinglish"][0]))
    for col in ("input", "output"):
        for v in t.column(col).to_pylist():
            if v:
                yield v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fetch", action="store_true")
    a = ap.parse_args()
    if a.fetch:
        fetch()

    t_uni, t_bi = count_corpus(tatoeba_lines())
    h_uni, h_bi = count_corpus(hinglish_lines())
    s_uni = collections.Counter()
    for line in open(os.path.join(CACHE, SOURCES["subs"][0]), encoding="utf-8"):
        w, c = line.split()
        if TOKEN.fullmatch(w):
            s_uni[w] = int(c)
    print(f"tatoeba {sum(t_uni.values()):,} tok, hinglish {sum(h_uni.values()):,} tok, subs {len(s_uni):,} types")

    def rate(counter, minc):
        tot = sum(counter.values())
        return {w: c / tot * 1e9 for w, c in counter.items() if c >= minc}

    rt, rh, rs = rate(t_uni, 3), rate(h_uni, 8), rate(s_uni, 1)
    english = set(rt) | set(rs)
    score = {}
    for w in english:
        if len(w) == 1 and w not in ("a", "i"):
            continue
        score[w] = 0.5 * rt.get(w, 0) + 0.5 * rs.get(w, 0)
    hing_only = set()
    for w, r in rh.items():
        if len(w) == 1:
            continue
        if w not in score:
            hing_only.add(w)
            score[w] = 0.5 * r
        else:
            score[w] += 0.15 * r
    words = sorted(score, key=lambda w: -score[w])[:MAX_WORDS]
    idx = {w: i for i, w in enumerate(words)}

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "words.tsv"), "w", encoding="utf-8") as f:
        for w in words:
            tags = ("e" if w in english else "") + ("h" if w in rh else "")
            f.write(f"{w}\t{max(1, int(round(score[w])))}\t{tags}\n")

    # word pairs: Tatoeba + Hinglish, both conversational
    bi = collections.Counter()
    for (x, y), c in t_bi.items():
        if x in idx and y in idx:
            bi[(idx[x], idx[y])] += c
    # Hinglish corpus is ~2x larger than Tatoeba: halve it so neither language dominates predictions
    for (x, y), c in h_bi.items():
        if x in idx and y in idx:
            bi[(idx[x], idx[y])] += c // 2
    pairs = [(k, c) for k, c in bi.items() if c >= 3]
    pairs.sort(key=lambda kc: -kc[1])
    pairs = pairs[:MAX_BIGRAMS]
    runs = collections.defaultdict(list)
    for (x, y), c in pairs:
        runs[x].append((c, y))
    V, N = len(words), len(pairs)
    with open(os.path.join(OUT, "bigrams.bin"), "wb") as f:
        f.write(b"RVB1" + struct.pack(">ii", V, N))
        off = 0
        offsets = []
        for x in range(V):
            offsets.append(off)
            off += len(runs.get(x, ()))
        offsets.append(off)
        f.write(struct.pack(f">{V + 1}i", *offsets))
        for x in range(V):
            for c, y in sorted(runs.get(x, ()), reverse=True):
                f.write(struct.pack(">iH", y, min(c, 65535)))

    # contractions: apostrophe forms seen in everyday English, keyed by the apostrophe-less spelling
    cons = []
    for w, c in t_uni.items():
        if "'" in w and c >= 20 and w in idx:
            plain = w.replace("'", "")
            pc = t_uni.get(plain, 0)
            amb = 1 if pc >= 0.01 * c and pc >= 20 else 0
            cons.append((plain, w, c, pc, amb))
    cons.sort(key=lambda r: -r[2])
    with open(os.path.join(OUT, "contractions.tsv"), "w", encoding="utf-8") as f:
        for r in cons:
            f.write("\t".join(map(str, r)) + "\n")

    sz = sum(os.path.getsize(os.path.join(OUT, x)) for x in os.listdir(OUT))
    print(f"words {V:,} (hinglish-only {len(hing_only & set(words)):,}), bigrams {N:,}, contractions {len(cons)}, assets {sz/1e6:.1f} MB")


if __name__ == "__main__":
    sys.exit(main())
