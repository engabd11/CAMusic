#!/usr/bin/env python3
"""Turn a keysweep directory into the two numbers that matter.

**Shift consistency** — for each track, the fraction of the eleven upward
semitone shifts whose detected tonic is exactly the unshifted tonic plus that
many semitones, with the mode unchanged. A detector reading real tonal structure
scores 1.0; one whose chroma is dominated by whichever bins happen to be loud
scores near chance (1/12).

**Detune stability** — whether a ±30-cent offset, which is well inside a
semitone and changes no note in the music, leaves the key alone.

Neither needs anyone to have tagged the files, which is the point: they measure
the detector against arithmetic rather than against an opinion.

    python tools/analysis-harness/score.py <sweep-dir> [--ref reference.tsv]
"""

import argparse
import pathlib
import sys

PITCH_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def read(path):
    rows = {}
    with open(path, encoding="utf-8") as fh:
        header = fh.readline().rstrip("\n").split("\t")
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < len(header):
                continue
            row = dict(zip(header, parts))
            rows[row["track"]] = row
    return rows


def key_of(row):
    if row.get("status") != "OK" or row.get("tonic") in (None, "-"):
        return None
    return int(row["tonic"]), row["mode"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sweep")
    ap.add_argument("--ref", default=None)
    args = ap.parse_args()

    d = pathlib.Path(args.sweep)
    base_path = d / "shift0.tsv"
    if not base_path.exists():
        print(f"no shift0.tsv in {d}", file=sys.stderr)
        return 1
    base = read(base_path)

    shifts = []
    for s in range(1, 12):
        p = d / f"shift{s}.tsv"
        if p.exists():
            shifts.append((s, read(p)))

    detunes = [(t, read(d / f"{t}.tsv")) for t in ("detune-30", "detune+30") if (d / f"{t}.tsv").exists()]

    print(f"{'track':<28} {'key':<9} {'shift-consistent':>17} {'detune-stable':>14}")
    print("-" * 74)
    total_hits = total_tests = 0
    detune_hits = detune_tests = 0
    for track, row in base.items():
        k0 = key_of(row)
        if k0 is None:
            print(f"{track:<28} {'-':<9} {'n/a':>17} {'n/a':>14}")
            continue
        hits = 0
        for s, rows in shifts:
            k = key_of(rows.get(track, {}))
            if k is None:
                continue
            if k[0] == (k0[0] + s) % 12 and k[1] == k0[1]:
                hits += 1
        stable = 0
        for _, rows in detunes:
            k = key_of(rows.get(track, {}))
            if k is not None and k == k0:
                stable += 1
        total_hits += hits
        total_tests += len(shifts)
        detune_hits += stable
        detune_tests += len(detunes)
        name = f"{PITCH_NAMES[k0[0]]} {k0[1].lower()}"
        conf = float(row.get("keyConf") or 0)
        print(f"{track:<28} {name:<9} {hits:>13}/{len(shifts):<3} {stable:>10}/{len(detunes):<3}"
              f"   conf={conf:.2f}")

    print("-" * 74)
    if total_tests:
        print(f"shift consistency : {total_hits}/{total_tests} = {total_hits / total_tests:.1%}  "
              f"(chance is 1/12 = 8.3%)")
    if detune_tests:
        print(f"detune stability  : {detune_hits}/{detune_tests} = {detune_hits / detune_tests:.1%}")

    confs = [float(r["keyConf"]) for r in base.values() if r.get("keyConf") not in (None, "-")]
    if confs:
        print(f"median key conf   : {sorted(confs)[len(confs) // 2]:.2f}")

    if args.ref and pathlib.Path(args.ref).exists():
        ref = read(args.ref)
        agree = octave = both = 0
        for track, row in base.items():
            r = ref.get(track)
            if not r or row.get("status") != "OK":
                continue
            both += 1
            a, b = float(row["bpm"]), float(r["bpm"])
            if abs(a - b) / max(b, 1e-6) < 0.04:
                agree += 1
            elif min(abs(a - 2 * b), abs(a - b / 2)) / max(b, 1e-6) < 0.08:
                octave += 1
        if both:
            print(f"tempo vs librosa  : {agree}/{both} exact, {octave}/{both} octave-related, "
                  f"{both - agree - octave}/{both} disagree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
