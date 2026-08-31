#!/usr/bin/env python3
"""
Build the offline speed-limit database CAMusic ships as an app asset.

Input is Transport Victoria's open "Speed Zones" GeoJSON export (~454 MB, a
FeatureCollection of MultiLineString features). Output is the SQLite file that
service/SpeedLimitDatabase.kt queries, plus a gzip of it for the APK.

    python tools/build_speed_db.py <input.geojson> <output.sqlite3> [--version july_2026]

No third-party dependencies, deliberately: the JSON is streamed by a small
brace scanner rather than by ijson, so anyone rebuilding the data needs only a
Python install. The file is far too large to hand to json.load().

Three reductions keep the result shippable. None of them loses anything the
reader's 30 m match threshold could detect:

  * zone_length and zone_conditions are dropped - the query reads only
    speed_limit, and direction is kept as a single character because the
    schema's own doc names it.
  * All of a zone's polylines go in ONE row, as one blob of little-endian
    int32 microdegree pairs with a uint16 point count in front of each - about
    0.11 m of precision, down from the 17 significant figures the export
    carries. As JSON text in a row per polyline the gzipped result was 56 MB,
    which is a lot of APK for a Victoria-only driving feature. One row per zone
    keyed WITHOUT ROWID also removes the covering index that cost as much again.
  * Each polyline is simplified with Douglas-Peucker at SIMPLIFY_M. Road
    centrelines in this export are densely sampled and mostly straight.
"""

import gzip
import json
import math
import os
import sqlite3
import struct
import sys
import time

CHUNK = 4 << 20          # 4 MB reads
SIMPLIFY_M = 4.0         # Douglas-Peucker tolerance, metres
COORD_DP = 6             # ~0.11 m
M_PER_DEG_LAT = 111_320.0
BATCH = 20_000           # zones held in memory between flushes


def features(path):
    """Yield each Feature object's raw JSON text, streaming the file.

    Scans for the features array and then walks it counting braces, aware of
    strings and escapes so a brace inside a property value cannot desync it.
    """
    with open(path, "r", encoding="utf-8") as fh:
        buf = fh.read(CHUNK)
        start = buf.find('"features"')
        if start < 0:
            raise SystemExit("no features key in the first chunk")
        i = buf.index("[", start) + 1

        depth = 0
        in_str = False
        esc = False
        obj_start = None
        while True:
            while i < len(buf):
                c = buf[i]
                if in_str:
                    if esc:
                        esc = False
                    elif c == "\\":
                        esc = True
                    elif c == '"':
                        in_str = False
                elif c == '"':
                    in_str = True
                elif c == "{":
                    if depth == 0:
                        obj_start = i
                    depth += 1
                elif c == "}":
                    depth -= 1
                    if depth == 0:
                        yield buf[obj_start:i + 1]
                        obj_start = None
                elif c == "]" and depth == 0:
                    return
                i += 1
            # Keep only the object under construction, then top up.
            if obj_start is not None:
                buf = buf[obj_start:]
                i -= obj_start
                obj_start = 0
            else:
                buf = ""
                i = 0
            more = fh.read(CHUNK)
            if not more:
                return
            buf += more


def perpendicular_m(p, a, b, mlon):
    """Distance in metres from p to segment a-b, in a local flat projection."""
    px, py = (p[0] - a[0]) * mlon, (p[1] - a[1]) * M_PER_DEG_LAT
    bx, by = (b[0] - a[0]) * mlon, (b[1] - a[1]) * M_PER_DEG_LAT
    den = bx * bx + by * by
    if den <= 0:
        return math.hypot(px, py)
    t = max(0.0, min(1.0, (px * bx + py * by) / den))
    return math.hypot(px - t * bx, py - t * by)


def simplify(points, mlon, tol=SIMPLIFY_M):
    """Douglas-Peucker, iterative so a long line cannot blow the stack."""
    n = len(points)
    if n <= 2:
        return points
    keep = [False] * n
    keep[0] = keep[n - 1] = True
    stack = [(0, n - 1)]
    while stack:
        lo, hi = stack.pop()
        if hi <= lo + 1:
            continue
        worst, worst_i = tol, -1
        a, b = points[lo], points[hi]
        for k in range(lo + 1, hi):
            d = perpendicular_m(points[k], a, b, mlon)
            if d > worst:
                worst, worst_i = d, k
        if worst_i >= 0:
            keep[worst_i] = True
            stack.append((lo, worst_i))
            stack.append((worst_i, hi))
    return [p for p, k in zip(points, keep) if k]


SCHEMA = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;
CREATE TABLE speed_zones (
  id          INTEGER PRIMARY KEY,
  speed_limit INTEGER NOT NULL,
  direction   TEXT
);
CREATE TABLE segment_coords (
  zone_id     INTEGER PRIMARY KEY,
  coords      BLOB NOT NULL
) WITHOUT ROWID;
CREATE VIRTUAL TABLE speed_zones_rtree USING rtree(
  id, min_lon, max_lon, min_lat, max_lat
);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


def pack(segments):
    """All of a zone's polylines as one blob.

    Per segment: a uint16 point count, then that many (lon, lat) pairs of
    little-endian int32 microdegrees - eight bytes a point, against the
    twenty-three the same point cost as JSON text.

    The segments cannot simply be concatenated: they are separate polylines, and
    running them together would invent a straight line joining the end of one to
    the start of the next, which the reader would then measure the driver
    against. Hence the count in front of each.

    SpeedLimitDatabase.parseSegments reads exactly this back; the two must change
    together. Microdegrees are ~0.11 m, and Victoria's longitudes reach 150e6,
    comfortably inside int32.
    """
    out = bytearray()
    for points in segments:
        out += struct.pack("<H", len(points))
        for lon, lat in points:
            out += struct.pack("<ii", round(lon * 1e6), round(lat * 1e6))
    return bytes(out)


def flush(db, zones, segs, boxes):
    if zones:
        db.executemany("INSERT INTO speed_zones VALUES (?,?,?)", zones)
    if segs:
        db.executemany("INSERT INTO segment_coords VALUES (?,?)", segs)
    if boxes:
        db.executemany("INSERT INTO speed_zones_rtree VALUES (?,?,?,?,?)", boxes)
    db.commit()
    zones.clear()
    segs.clear()
    boxes.clear()


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    version = "unknown"
    if "--version" in sys.argv:
        version = sys.argv[sys.argv.index("--version") + 1]

    if os.path.exists(dst):
        os.remove(dst)
    db = sqlite3.connect(dst)
    db.executescript(SCHEMA)

    zones, segs, boxes = [], [], []
    zone_id = 0
    skipped = 0
    points_in = points_out = 0
    t0 = time.time()

    for raw in features(src):
        feat = json.loads(raw)
        props = feat.get("properties") or {}
        try:
            limit = int(str(props.get("speed_limit", "")).strip())
        except ValueError:
            skipped += 1
            continue
        if limit <= 0:
            skipped += 1
            continue

        geom = feat.get("geometry") or {}
        coords = geom.get("coordinates") or []
        kind = geom.get("type")
        if kind == "MultiLineString":
            lines = coords
        elif kind == "LineString":
            lines = [coords]
        else:
            lines = []
        if not lines:
            skipped += 1
            continue

        zone_id += 1
        direction = (str(props.get("direction") or "")[:1] or None)
        lo_lon = lo_lat = float("inf")
        hi_lon = hi_lat = float("-inf")
        shape = []

        for line in lines:
            if len(line) < 2:
                continue
            points_in += len(line)
            mean_lat = sum(p[1] for p in line) / len(line)
            mlon = M_PER_DEG_LAT * max(math.cos(math.radians(mean_lat)), 1e-6)
            pts = simplify([(p[0], p[1]) for p in line], mlon)
            points_out += len(pts)
            rounded = [(round(x, COORD_DP), round(y, COORD_DP)) for x, y in pts]
            for x, y in rounded:
                if x < lo_lon:
                    lo_lon = x
                if x > hi_lon:
                    hi_lon = x
                if y < lo_lat:
                    lo_lat = y
                if y > hi_lat:
                    hi_lat = y
            shape.append(rounded)

        if not shape:
            zone_id -= 1
            skipped += 1
            continue

        segs.append((zone_id, pack(shape)))
        zones.append((zone_id, limit, direction))
        boxes.append((zone_id, lo_lon, hi_lon, lo_lat, hi_lat))

        if len(zones) >= BATCH:
            flush(db, zones, segs, boxes)
            print("  {:,} zones  {:.0f}s".format(zone_id, time.time() - t0), flush=True)

    flush(db, zones, segs, boxes)
    db.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", [
        ("version", version),
        ("source", "Transport Victoria - Speed Zones (open data)"),
        ("generated", time.strftime("%Y-%m-%d")),
        ("simplify_m", str(SIMPLIFY_M)),
        ("coord_dp", str(COORD_DP)),
    ])
    db.commit()
    # No index on segment_coords: it is WITHOUT ROWID keyed on zone_id, so the
    # table *is* the index and a second copy of it would cost as much as the data.
    print("vacuum...", flush=True)
    db.execute("VACUUM")
    db.close()

    raw_mb = os.path.getsize(dst) / 1e6
    with open(dst, "rb") as fin:
        with gzip.open(dst + ".gz", "wb", compresslevel=9) as fout:
            while True:
                block = fin.read(1 << 20)
                if not block:
                    break
                fout.write(block)
    gz_mb = os.path.getsize(dst + ".gz") / 1e6

    kept = 100.0 * points_out / max(points_in, 1)
    print("")
    print("zones      {:,}   (skipped {:,})".format(zone_id, skipped))
    print("points     {:,} -> {:,}  ({:.0f}% kept)".format(points_in, points_out, kept))
    print("sqlite     {:,.1f} MB".format(raw_mb))
    print("gzip       {:,.1f} MB   <- the APK cost".format(gz_mb))
    print("elapsed    {:.0f}s".format(time.time() - t0))


if __name__ == "__main__":
    main()
