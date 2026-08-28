#!/usr/bin/env python3
"""
build_speed_db.py — Transport Victoria Speed Zones GeoJSON → SQLite R-tree

Streams a large GeoJSON file (454MB+) without loading it entirely into memory.
Produces a compact SQLite database with an R-tree spatial index for sub-millisecond
point queries on Android.

Usage:
    python build_speed_db.py --output speed_zones_vic.sqlite3
    python build_speed_db.py --input speed_zones_july_2026.geojson --output speed_zones_vic.sqlite3
    python build_speed_db.py --bbox 144.6,-38.2,145.1,-37.7 --output metro.sqlite3
"""

import argparse
import json
import os
import sqlite3
import sys
import urllib.request
from datetime import date
from typing import Iterator, Optional

DEFAULT_GEOJSON_URL = (
    "https://opendata.transport.vic.gov.au/dataset/975b80b9-e530-46e2-80a5-54002765e81a/"
    "resource/96d4309f-30a2-4ed9-ba66-5dfbd3a959c7/download/speed_zones_july_2026.geojson"
)

# R-tree node: (id, min_lon, max_lon, min_lat, max_lat)
RTreeEntry = tuple[int, float, float, float, float]


def parse_bbox(s: str) -> Optional[tuple[float, float, float, float]]:
    """Parse 'min_lon,min_lat,max_lon,max_lat' → tuple, or None."""
    if not s:
        return None
    parts = [float(x) for x in s.split(",")]
    if len(parts) != 4:
        raise ValueError(f"bbox needs 4 values, got {len(parts)}: {s}")
    return tuple(parts)  # type: ignore


def in_bbox(
    lon: float, lat: float, bbox: Optional[tuple[float, float, float, float]]
) -> bool:
    if bbox is None:
        return True
    return bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3]


def feature_bbox_in(
    coords: list, bbox: Optional[tuple[float, float, float, float]]
) -> bool:
    """Check if any coordinate in the nested structure falls within bbox."""
    for point in iter_points(coords):
        if in_bbox(point[0], point[1], bbox):
            return True
    return False


def iter_points(coords: list) -> Iterator[tuple[float, float]]:
    """Yield (lon, lat) from arbitrarily nested coordinate arrays."""
    if (
        len(coords) >= 2
        and isinstance(coords[0], (int, float))
        and isinstance(coords[1], (int, float))
    ):
        yield (float(coords[0]), float(coords[1]))
    else:
        for sub in coords:
            if isinstance(sub, list):
                yield from iter_points(sub)


def compute_bbox(coords: list) -> tuple[float, float, float, float]:
    """Return (min_lon, max_lon, min_lat, max_lat) for a coordinate structure."""
    lons: list[float] = []
    lats: list[float] = []
    for lon, lat in iter_points(coords):
        lons.append(lon)
        lats.append(lat)
    if not lons:
        return (0.0, 0.0, 0.0, 0.0)
    return (min(lons), max(lons), min(lats), max(lats))


def stream_features(
    input_path: Optional[str], url: str
) -> Iterator[dict]:
    """
    Stream GeoJSON features from a file or URL without loading the whole file.

    GeoJSON FeatureCollections have a predictable structure:
    {"type":"FeatureCollection","features":[{...},{...},...]}

    We stream the file and parse each feature object as we encounter it.
    Uses a simple brace-depth scanner to delimit top-level objects in the
    features array.
    """
    if input_path and os.path.exists(input_path):
        f = open(input_path, "r", encoding="utf-8")  # noqa: SIM115
    else:
        if input_path:
            url = f"file://{os.path.abspath(input_path)}"
        print(f"Downloading from {url} ...", file=sys.stderr)
        f = urllib.request.urlopen(url, timeout=300)  # noqa: S310

    # We need a streaming JSON parser. ijson would be ideal, but to avoid
    # external dependencies we use a targeted approach: read in chunks,
    # find the "features" array, then extract individual feature objects
    # by brace matching.
    #
    # For a 454MB file this is the difference between 4GB RAM and ~10MB.

    buffer = ""
    in_features = False
    depth = 0
    feature_start = -1
    chunk_size = 65536
    total_read = 0

    while True:
        chunk = f.read(chunk_size)
        if not chunk:
            break
        total_read += len(chunk)
        if total_read % (10 * 1024 * 1024) < chunk_size:
            print(f"  ...{total_read // (1024*1024)}MB read", file=sys.stderr)

        buffer += chunk

        if not in_features:
            # Find the start of the features array
            idx = buffer.find('"features"')
            if idx == -1:
                # Keep last 200 chars to avoid splitting the key across chunks
                buffer = buffer[-200:]
                continue
            # Find the opening bracket of the array
            bracket_idx = buffer.find("[", idx)
            if bracket_idx == -1:
                continue
            buffer = buffer[bracket_idx + 1 :]
            in_features = True
            depth = 0
            feature_start = -1

        # Now scan for individual feature objects
        i = 0
        while i < len(buffer):
            c = buffer[i]
            if c == "{":
                if depth == 0:
                    feature_start = i
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0 and feature_start >= 0:
                    feature_json = buffer[feature_start : i + 1]
                    try:
                        yield json.loads(feature_json)
                    except json.JSONDecodeError:
                        pass  # skip malformed features
                    feature_start = -1
                    # Check if next non-whitespace is ']' (end of array)
                    rest = buffer[i + 1 :].lstrip()
                    if rest.startswith("]"):
                        f.close()
                        return
            i += 1

        # Keep the remainder (from the last potential feature start or current depth)
        if feature_start >= 0:
            buffer = buffer[feature_start:]
            feature_start = 0  # will be 0 in next iteration
        else:
            # Keep enough to not split a brace
            buffer = buffer[-200:] if buffer else ""

    f.close()


def parse_speed_limit(value) -> Optional[int]:
    """Parse speed_limit which may be string or int, handle edge cases."""
    if value is None:
        return None
    s = str(value).strip()
    if not s:
        return None
    # Some values might be like "80" or 80
    try:
        return int(s)
    except ValueError:
        return None


def flatten_multilinestring(
    geometry: dict,
) -> list[list[tuple[float, float]]]:
    """
    Extract line segments from a geometry as a list of point-lists.
    Each point-list is [(lon, lat), (lon, lat), ...] forming a line.
    """
    coords = geometry.get("coordinates", [])
    gtype = geometry.get("type", "")

    segments: list[list[tuple[float, float]]] = []

    if gtype == "LineString":
        segments.append([(float(c[0]), float(c[1])) for c in coords])
    elif gtype == "MultiLineString":
        for line in coords:
            segments.append([(float(c[0]), float(c[1])) for c in line])
    elif gtype == "MultiPolygon":
        for polygon in coords:
            for ring in polygon:
                segments.append([(float(c[0]), float(c[1])) for c in ring])
    elif gtype == "Polygon":
        for ring in coords:
            segments.append([(float(c[0]), float(c[1])) for c in ring])

    return segments


def create_database(output_path: str) -> sqlite3.Connection:
    """Create the SQLite database with the required schema."""
    if os.path.exists(output_path):
        os.remove(output_path)

    conn = sqlite3.connect(output_path)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")

    conn.execute("""
        CREATE TABLE meta (
            key   TEXT PRIMARY KEY,
            value TEXT
        )
    """)

    conn.execute("""
        CREATE TABLE speed_zones (
            id             INTEGER PRIMARY KEY,
            speed_limit    INTEGER NOT NULL,
            direction      TEXT,
            road_name      TEXT,
            road_type      TEXT,
            zone_conditions TEXT,
            zone_length    REAL
        )
    """)

    conn.execute("""
        CREATE VIRTUAL TABLE speed_zones_rtree USING rtree(
            id,
            min_lon, max_lon,
            min_lat, max_lat
        )
    """)

    conn.execute("""
        CREATE TABLE segment_coords (
            zone_id     INTEGER NOT NULL,
            segment_idx INTEGER NOT NULL,
            coords      TEXT NOT NULL,
            FOREIGN KEY (zone_id) REFERENCES speed_zones(id)
        )
    """)

    return conn


def build_database(
    input_path: Optional[str],
    url: str,
    output_path: str,
    bbox: Optional[tuple[float, float, float, float]],
    dataset_version: str,
) -> dict:
    """
    Stream the GeoJSON, build the SQLite database.

    Returns statistics dict.
    """
    conn = create_database(output_path)
    cur = conn.cursor()

    # Begin transaction for bulk insert
    cur.execute("BEGIN")

    zone_id = 0
    total_features = 0
    skipped_no_speed = 0
    skipped_bbox = 0
    total_segments = 0

    for feature in stream_features(input_path, url):
        total_features += 1

        props = feature.get("properties", {})
        speed_limit = parse_speed_limit(props.get("speed_limit"))
        if speed_limit is None or speed_limit <= 0:
            skipped_no_speed += 1
            continue

        geometry = feature.get("geometry", {})
        segments = flatten_multilinestring(geometry)
        if not segments:
            continue

        # Bounding-box filter: check if any part of this feature is in range
        all_coords = [pt for seg in segments for pt in seg]
        if bbox is not None:
            if not any(in_bbox(lon, lat, bbox) for lon, lat in all_coords):
                skipped_bbox += 1
                continue

        zone_id += 1

        # Compute bounding box for R-tree
        min_lon = min(pt[0] for pt in all_coords)
        max_lon = max(pt[0] for pt in all_coords)
        min_lat = min(pt[1] for pt in all_coords)
        max_lat = max(pt[1] for pt in all_coords)

        direction = props.get("direction")
        road_name = props.get("road_name")
        road_type = props.get("road_type")
        zone_conditions = json.dumps(props.get("zone_conditions", []))
        zone_length = props.get("zone_length")

        # Insert into speed_zones
        cur.execute(
            "INSERT INTO speed_zones (id, speed_limit, direction, road_name, road_type, zone_conditions, zone_length) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (zone_id, speed_limit, direction, road_name, road_type, zone_conditions, zone_length),
        )

        # Insert into R-tree
        cur.execute(
            "INSERT INTO speed_zones_rtree (id, min_lon, max_lon, min_lat, max_lat) VALUES (?, ?, ?, ?, ?)",
            (zone_id, min_lon, max_lon, min_lat, max_lat),
        )

        # Insert segment coordinates
        for seg_idx, seg in enumerate(segments):
            cur.execute(
                "INSERT INTO segment_coords (zone_id, segment_idx, coords) VALUES (?, ?, ?)",
                (zone_id, seg_idx, json.dumps(seg)),
            )
            total_segments += 1

        if zone_id % 5000 == 0:
            conn.commit()
            cur.execute("BEGIN")
            print(f"  ...{zone_id} zones inserted", file=sys.stderr)

    conn.commit()

    # Create indexes for faster queries
    print("Creating indexes...", file=sys.stderr)
    cur.execute("CREATE INDEX idx_segment_zone ON segment_coords(zone_id)")
    cur.execute("CREATE INDEX idx_speed_limit ON speed_zones(speed_limit)")

    # Insert metadata
    today = date.today().isoformat()
    for key, value in [
        ("version", dataset_version),
        ("source", "Transport Victoria Open Data"),
        ("license", "CC-BY-4.0"),
        ("generated_at", today),
        ("feature_count", str(zone_id)),
        ("total_features_seen", str(total_features)),
        ("skipped_no_speed", str(skipped_no_speed)),
        ("skipped_bbox", str(skipped_bbox)),
        ("total_segments", str(total_segments)),
    ]:
        cur.execute("INSERT INTO meta (key, value) VALUES (?, ?)", (key, value))

    conn.commit()

    # Vacuum to compact the database
    print("Vacuuming...", file=sys.stderr)
    cur.execute("VACUUM")
    conn.commit()

    stats = {
        "zones_inserted": zone_id,
        "total_features": total_features,
        "skipped_no_speed": skipped_no_speed,
        "skipped_bbox": skipped_bbox,
        "total_segments": total_segments,
    }

    conn.close()
    return stats


def main():
    parser = argparse.ArgumentParser(
        description="Convert Transport Victoria Speed Zones GeoJSON → SQLite R-tree"
    )
    parser.add_argument(
        "--input",
        default=None,
        help="Path to the GeoJSON file (if not provided, downloads from URL)",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Output SQLite database path",
    )
    parser.add_argument(
        "--bbox",
        default=None,
        help="Bounding box: min_lon,min_lat,max_lon,max_lat (optional, for subset builds)",
    )
    parser.add_argument(
        "--url",
        default=DEFAULT_GEOJSON_URL,
        help=f"URL to download GeoJSON from (default: Transport Victoria)",
    )
    parser.add_argument(
        "--version",
        default=None,
        help="Dataset version label (default: derived from input filename or 'unknown')",
    )

    args = parser.parse_args()

    bbox = parse_bbox(args.bbox)

    if args.version:
        dataset_version = args.version
    elif args.input:
        dataset_version = os.path.basename(args.input).replace(".geojson", "")
    else:
        # Extract from URL
        dataset_version = args.url.rsplit("/", 1)[-1].replace(".geojson", "")

    print(f"Dataset version: {dataset_version}", file=sys.stderr)
    print(f"Output: {args.output}", file=sys.stderr)
    if bbox:
        print(f"Bounding box: {bbox}", file=sys.stderr)

    stats = build_database(
        input_path=args.input,
        url=args.url,
        output_path=args.output,
        bbox=bbox,
        dataset_version=dataset_version,
    )

    size_mb = os.path.getsize(args.output) / (1024 * 1024)
    print(f"\nDone!", file=sys.stderr)
    print(f"  Zones inserted:   {stats['zones_inserted']:,}", file=sys.stderr)
    print(f"  Features seen:    {stats['total_features']:,}", file=sys.stderr)
    print(f"  Skipped (no speed):{stats['skipped_no_speed']:,}", file=sys.stderr)
    if bbox:
        print(f"  Skipped (bbox):    {stats['skipped_bbox']:,}", file=sys.stderr)
    print(f"  Total segments:   {stats['total_segments']:,}", file=sys.stderr)
    print(f"  Database size:    {size_mb:.1f} MB", file=sys.stderr)


if __name__ == "__main__":
    main()