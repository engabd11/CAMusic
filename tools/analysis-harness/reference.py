#!/usr/bin/env python3
"""An independent second opinion on tempo, beats and key, from librosa.

The point of this file is that it shares no code with the app. `ScanHarnessTest`
can tell you what CAMusic's analyser thinks; it cannot tell you whether that is
right, because the only other implementation of the same idea in this repository
is the live analyzer, which was ported from the same source and would agree with
the offline one about a shared mistake. librosa is a different implementation of
different papers by different people, so where the two agree the answer is
probably the music, and where they disagree there is something to look at.

It is a reference, not an oracle. librosa's own key estimate is a template
correlation over a CQT chroma and is wrong often enough that a disagreement is a
prompt to listen, not a verdict. Tempo is the sturdier of the two, and octave
disagreements are reported as such rather than as errors, because both tools are
choosing between metrical levels that are all defensible readings of the music.

    python tools/analysis-harness/reference.py <album-dir> [--out ref.tsv]

Needs numpy, librosa and soundfile. Reads the original audio files, not the
decoded .f32 the JVM harness uses, so a bug in decode.sh cannot make the two
agree for the wrong reason.
"""

import argparse
import pathlib
import sys

import numpy as np

PITCH_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# Krumhansl-Kessler, the same profiles KeyDetection.kt starts from. Deliberately
# the same: this script is a check on the *chroma* and the tuning, which is where
# the app's key path was weakest, so holding the profile constant between the two
# keeps the comparison about the part under test.
MAJOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MINOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


def estimate_key(y, sr):
    """Best-fitting tonic and mode from a tuning-corrected CQT chroma.

    `chroma_cqt` with librosa's estimated tuning is the part worth borrowing:
    a constant-Q transform has constant resolution *per semitone*, so unlike the
    app's fixed-size STFT it resolves the bass as well as it resolves the mids.
    """
    tuning = librosa.estimate_tuning(y=y, sr=sr)
    chroma = librosa.feature.chroma_cqt(y=y, sr=sr, tuning=tuning)
    # Median over frames, not mean: one loud sustained chord should not decide a
    # whole track's key, which is exactly the failure the app's raw sum has.
    vec = np.median(chroma, axis=1)
    best = (-2.0, 0, "MAJOR")
    for tonic in range(12):
        for name, profile in (("MAJOR", MAJOR), ("MINOR", MINOR)):
            rolled = np.roll(profile, tonic)
            score = np.corrcoef(vec, rolled)[0, 1]
            if score > best[0]:
                best = (score, tonic, name)
    return best[1], best[2], float(best[0]), float(tuning * 100.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("album")
    ap.add_argument("--out", default=None)
    ap.add_argument("--limit", type=int, default=None)
    args = ap.parse_args()

    files = sorted(pathlib.Path(args.album).glob("*.flac"))
    if args.limit:
        files = files[: args.limit]
    if not files:
        print(f"no .flac in {args.album}", file=sys.stderr)
        return 1

    rows = ["\t".join(["track", "bpm", "beats", "medianIbi", "tonic", "mode", "corr", "tuningCents"])]
    for f in files:
        # 22050 mono, matching the app's analysis rate so neither tool gets an
        # advantage from bandwidth the other did not have.
        y, sr = librosa.load(str(f), sr=22050, mono=True)
        tempo, beat_frames = librosa.beat.beat_track(y=y, sr=sr, units="frames")
        beats = librosa.frames_to_time(beat_frames, sr=sr)
        ibi = float(np.median(np.diff(beats))) if len(beats) > 1 else 0.0
        tonic, mode, corr, cents = estimate_key(y, sr)
        bpm = float(np.atleast_1d(tempo)[0])
        rows.append(
            "\t".join(
                [
                    f.stem,
                    f"{bpm:.3f}",
                    str(len(beats)),
                    f"{ibi:.4f}",
                    str(tonic),
                    mode,
                    f"{corr:.4f}",
                    f"{cents:.1f}",
                ]
            )
        )
        print(f"[ref] {f.stem}  bpm={bpm:.1f} beats={len(beats)} "
              f"key={PITCH_NAMES[tonic]} {mode.lower()} tuning={cents:+.1f}c", flush=True)

    text = "\n".join(rows) + "\n"
    if args.out:
        pathlib.Path(args.out).write_text(text)
        print(f"[ref] wrote {args.out}")
    return 0


if __name__ == "__main__":
    import librosa  # noqa: E402  — imported late so --help does not pay for it

    sys.exit(main())
