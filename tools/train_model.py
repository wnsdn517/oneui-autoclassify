"""
Builds the on-device app category data from the Google Play Store dataset
(https://github.com/gauthamp10/Google-Playstore-Dataset, MIT, ~2.3M apps, June 2021).

Outputs (into app/src/main/assets):
  play_lookup.bin  exact package -> Play category for popular apps
  play_model.bin   multinomial Naive Bayes over package-name/label tokens for everything else

Usage:  python -I tools/train_model.py <dir with Part1.csv Part2.csv Part3.csv> <assets dir>
"""
import csv
import math
import random
import re
import struct
import sys
import zlib
from collections import Counter, defaultdict

import numpy as np

LOOKUP_MIN_INSTALLS = 100_000
FEATURE_MIN_DF = 15
MAX_FEATURES = 40_000
HOLDOUT = 0.05

# Play category names as they appear in the dataset; index = id stored in the binaries.
CATEGORIES = [
    "Action", "Adventure", "Arcade", "Board", "Card", "Casino", "Casual", "Educational", "Music", "Puzzle",
    "Racing", "Role Playing", "Simulation", "Strategy", "Trivia", "Word",
    "Art & Design", "Auto & Vehicles", "Beauty", "Books & Reference", "Business", "Comics", "Communication",
    "Dating", "Education", "Entertainment", "Events", "Finance", "Food & Drink", "Health & Fitness",
    "House & Home", "Libraries & Demo", "Lifestyle", "Maps & Navigation", "Medical", "Music & Audio",
    "News & Magazines", "Parenting", "Personalization", "Photography", "Productivity", "Shopping", "Social",
    "Sports", "Tools", "Travel & Local", "Video Players & Editors", "Weather",
]
# Play has a "Sports" game genre and a "Sports" app category with the same name; the dataset can't
# tell them apart, so it is kept once as the app category.
GAME_CATS = set(CATEGORIES[:16])
CAT_INDEX = {}
for i, c in enumerate(CATEGORIES):
    CAT_INDEX.setdefault(c, i)  # first occurrence wins for duplicate names

STOP = {"com", "org", "net", "io", "app", "apps", "android", "mobile", "www", "co", "kr", "jp", "de", "fr",
        "free", "pro", "lite", "the", "and", "for", "of", "a", "an", "my", "release", "prod", "v2"}
TOKEN = re.compile(r"[a-z][a-z0-9]{1,}")
CAMEL = re.compile(r"(?<=[a-z])(?=[A-Z])")


def tokens(pkg, label):
    """Features shared with the Java side (core/PlayModel.java); keep in sync."""
    feats = set()
    parts = pkg.split(".")
    if len(parts) >= 2:
        feats.add("ns:" + parts[1].lower())  # developer namespace, e.g. com.kakao.* -> kakao
    for part in parts:
        for t in TOKEN.findall(CAMEL.sub(" ", part).lower().replace("_", " ")):
            if t not in STOP:
                feats.add("p:" + t)
    for t in TOKEN.findall((label or "").lower()):
        if t not in STOP:
            feats.add("l:" + t)
    return feats


def read_rows(folder):
    header = ["App Name", "App Id", "Category", "Rating", "Rating Count", "Installs", "Minimum Installs"]
    for part in ("Part1.csv", "Part2.csv", "Part3.csv"):
        with open(f"{folder}/{part}", encoding="utf-8", newline="") as f:
            for row in csv.reader(f):
                if not row or row[0] == "App Name" or len(row) < 7:
                    continue
                name, pkg, cat = row[0], row[1], row[2]
                try:
                    installs = int(float(row[6] or 0))
                except ValueError:
                    installs = 0
                if cat in CAT_INDEX and pkg:
                    yield name, pkg, CAT_INDEX[cat], installs


def write_lookup(path, entries):
    """Format: magic 'ACL1', count u32, then zlib(sorted 'pkg\\0' bytes) and cat bytes, both length-prefixed."""
    entries.sort()
    names = b"".join(p.encode("utf-8") + b"\0" for p, _ in entries)
    cats = bytes(c for _, c in entries)
    zn = zlib.compress(names, 9)
    with open(path, "wb") as f:
        f.write(b"ACL1")
        f.write(struct.pack(">I", len(entries)))
        f.write(struct.pack(">I", len(zn)))
        f.write(zn)
        f.write(cats)
    return len(zn) + len(cats) + 12


def write_model(path, priors, feats, logp, scale, offset):
    """Format: magic 'ACM1', n_cat u16, n_feat u32, scale f32, offset f32, priors f32[n_cat],
    zlib(feature strings '\\0'-separated), int8[n_feat][n_cat] quantized log P(f|c)."""
    blob = b"".join(f.encode("utf-8") + b"\0" for f in feats)
    zb = zlib.compress(blob, 9)
    with open(path, "wb") as f:
        f.write(b"ACM1")
        f.write(struct.pack(">HIff", len(priors), len(feats), scale, offset))
        f.write(struct.pack(f">{len(priors)}f", *priors))
        f.write(struct.pack(">I", len(zb)))
        f.write(zb)
        f.write(logp.astype(np.int8).tobytes())


def main():
    folder, assets = sys.argv[1], sys.argv[2]
    random.seed(7)
    lookup = {}
    train, test = [], []
    for name, pkg, cat, installs in read_rows(folder):
        if installs >= LOOKUP_MIN_INSTALLS:
            lookup[pkg] = cat
        # Evaluate on the apps the lookup can't answer, which is what the model is for.
        if installs < LOOKUP_MIN_INSTALLS and random.random() < HOLDOUT:
            test.append((pkg, name, cat))
        else:
            train.append((pkg, name, cat))
    print(f"rows: train {len(train)} test {len(test)} lookup {len(lookup)}")

    n_cat = len(CATEGORIES)
    df = Counter()
    for pkg, name, _ in train:
        df.update(tokens(pkg, name))
    vocab = [f for f, c in df.items() if c >= FEATURE_MIN_DF]
    print(f"features with df>={FEATURE_MIN_DF}: {len(vocab)}")

    # Rank by mutual information with the category, keep the best MAX_FEATURES.
    fidx = {f: i for i, f in enumerate(vocab)}
    counts = np.zeros((len(vocab), n_cat), dtype=np.float64)
    cat_n = np.zeros(n_cat)
    for pkg, name, cat in train:
        cat_n[cat] += 1
        for f in tokens(pkg, name):
            i = fidx.get(f)
            if i is not None:
                counts[i, cat] += 1
    N = cat_n.sum()
    pc = cat_n / N
    pf = counts.sum(1) / N
    with np.errstate(divide="ignore", invalid="ignore"):
        pfc = counts / N
        mi = np.nansum(pfc * np.log(pfc / (pf[:, None] * pc[None, :])), axis=1)
    keep = np.argsort(-mi)[:MAX_FEATURES]
    keep.sort()
    feats = [vocab[i] for i in keep]
    counts = counts[keep]
    print(f"kept {len(feats)} features")

    # Bernoulli-style NB scores on present features only: log P(f|c) with Laplace smoothing.
    logp = np.log((counts + 1.0) / (cat_n[None, :] + 2.0))
    lo, hi = float(logp.min()), float(logp.max())
    scale = (hi - lo) / 254.0
    q = np.round((logp - lo) / scale - 127).clip(-127, 127)
    priors = np.log(cat_n / N).tolist()

    # Evaluate with the quantized weights, exactly as the phone will.
    deq = (q + 127) * scale + lo
    correct = correct_group = 0
    group = lambda c: "game" if CATEGORIES[c] in GAME_CATS else CATEGORIES[c]
    fpos = {f: i for i, f in enumerate(feats)}
    conf = []
    for pkg, name, cat in test:
        idx = [fpos[f] for f in tokens(pkg, name) if f in fpos]
        score = np.array(priors) + (deq[idx].sum(0) if idx else 0)
        pred = int(np.argmax(score))
        p = np.exp(score - score.max())
        p /= p.sum()
        correct += pred == cat
        correct_group += group(pred) == group(cat)
        conf.append((float(p[pred]), group(pred) == group(cat)))
    print(f"holdout top-1: {correct / len(test):.3f}  grouped (games merged): {correct_group / len(test):.3f}")
    for th in (0.5, 0.7, 0.8, 0.9, 0.95):
        sel = [ok for c, ok in conf if c >= th]
        if sel:
            print(f"  confidence>={th}: coverage {len(sel) / len(conf):.2f}  grouped accuracy {sum(sel) / len(sel):.3f}")

    lsz = write_lookup(f"{assets}/play_lookup.bin", list(lookup.items()))
    write_model(f"{assets}/play_model.bin", priors, feats, q, scale, lo)
    print(f"lookup {lsz / 1e6:.2f} MB, model written")


if __name__ == "__main__":
    main()
