#!/usr/bin/env python3
"""
Test the speed-limit pipeline with a small synthetic GeoJSON file
that mimics the Transport Victoria schema, then verify the SQLite output.
"""

import json
import os
import sqlite3
import subprocess
import sys
import tempfile

# A minimal GeoJSON with the exact Transport Victoria schema
# Features around Melbourne CBD + a suburban road, to test spatial queries
TEST_GEOJSON = {
    "type": "FeatureCollection",
    "features": [
        # Feature 1: 60 km/h zone near Flinders St
        {
            "type": "Feature",
            "id": "test-001",
            "geometry": {
                "type": "MultiLineString",
                "coordinates": [
                    [[144.9631, -37.8182], [144.9633, -37.8185], [144.9636, -37.8190]],
                    [[144.9636, -37.8190], [144.9640, -37.8195]],
                ],
            },
            "properties": {
                "speed_limit": "60",
                "zone_length": 250.5,
                "zone_conditions": ["Normal Operation"],
                "direction": "North",
                "road_name": "Flinders",
                "road_type": "Street",
                "postcodes": 3000,
                "LGA": ["Melbourne"],
                "Suburbs": "Melbourne",
                "regions": "Metro",
                "state_electorates": "Melbourne",
                "speed_zone_change_request_status": None,
                "speed_zone_change_request_id": None,
                "zone_conditions_other": None,
            },
        },
        # Feature 2: 40 km/h school zone
        {
            "type": "Feature",
            "id": "test-002",
            "geometry": {
                "type": "MultiLineString",
                "coordinates": [
                    [[144.9700, -37.8100], [144.9705, -37.8105]],
                ],
            },
            "properties": {
                "speed_limit": "40",
                "zone_length": 120.0,
                "zone_conditions": ["School Zone"],
                "direction": "Both",
                "road_name": "School",
                "road_type": "Road",
                "postcodes": 3000,
                "LGA": ["Melbourne"],
                "Suburbs": "Melbourne",
                "regions": "Metro",
                "state_electorates": "Melbourne",
                "speed_zone_change_request_status": None,
                "speed_zone_change_request_id": None,
                "zone_conditions_other": None,
            },
        },
        # Feature 3: 100 km/h zone (rural, should be filtered by bbox)
        {
            "type": "Feature",
            "id": "test-003",
            "geometry": {
                "type": "MultiLineString",
                "coordinates": [
                    [[141.5000, -38.3000], [141.5100, -38.3100]],
                ],
            },
            "properties": {
                "speed_limit": "100",
                "zone_length": 1500.0,
                "zone_conditions": ["Normal Operation"],
                "direction": "North",
                "road_name": "Highway",
                "road_type": "Highway",
                "postcodes": 3300,
                "LGA": ["Ararat"],
                "Suburbs": "Ararat",
                "regions": "Grampians",
                "state_electorates": "Ripon",
                "speed_zone_change_request_status": None,
                "speed_zone_change_request_id": None,
                "zone_conditions_other": None,
            },
        },
        # Feature 4: No speed_limit (should be skipped)
        {
            "type": "Feature",
            "id": "test-004",
            "geometry": {
                "type": "MultiLineString",
                "coordinates": [
                    [[145.0000, -37.8000], [145.0100, -37.8100]],
                ],
            },
            "properties": {
                "speed_limit": None,
                "zone_length": 800.0,
                "zone_conditions": ["Normal Operation"],
                "direction": "Both",
                "road_name": "Missing",
                "road_type": "Road",
                "postcodes": 3000,
                "LGA": ["Melbourne"],
                "Suburbs": "Melbourne",
                "regions": "Metro",
                "state_electorates": "Melbourne",
                "speed_zone_change_request_status": None,
                "speed_zone_change_request_id": None,
                "zone_conditions_other": None,
            },
        },
        # Feature 5: speed_limit=0 (should be skipped)
        {
            "type": "Feature",
            "id": "test-005",
            "geometry": {
                "type": "MultiLineString",
                "coordinates": [
                    [[145.0200, -37.8200], [145.0300, -37.8300]],
                ],
            },
            "properties": {
                "speed_limit": "0",
                "zone_length": 300.0,
                "zone_conditions": ["Normal Operation"],
                "direction": "Both",
                "road_name": "Zero",
                "road_type": "Road",
                "postcodes": 3000,
                "LGA": ["Melbourne"],
                "Suburbs": "Melbourne",
                "regions": "Metro",
                "state_electorates": "Melbourne",
                "speed_zone_change_request_status": None,
                "speed_zone_change_request_id": None,
                "zone_conditions_other": None,
            },
        },
    ],
}


def main():
    pipeline_dir = os.path.dirname(os.path.abspath(__file__))
    build_script = os.path.join(pipeline_dir, "build_speed_db.py")
    tmpdir = tempfile.mkdtemp(prefix="speed_limit_test_")

    # Write test GeoJSON
    geojson_path = os.path.join(tmpdir, "test.geojson")
    with open(geojson_path, "w") as f:
        json.dump(TEST_GEOJSON, f)

    db_path = os.path.join(tmpdir, "test.sqlite3")

    print("=== Running pipeline ===")
    result = subprocess.run(
        [
            sys.executable,
            build_script,
            "--input", geojson_path,
            "--output", db_path,
            "--version", "test_v1",
        ],
        capture_output=True,
        text=True,
    )
    print(result.stderr)
    if result.returncode != 0:
        print("STDOUT:", result.stdout)
        print("FAILED: pipeline returned non-zero")
        sys.exit(1)

    assert os.path.exists(db_path), f"Database file not created at {db_path}"
    db_size = os.path.getsize(db_path)
    print(f"Database created: {db_size} bytes")

    print("\n=== Verifying schema ===")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    # Check tables exist
    cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
    tables = {row[0] for row in cur.fetchall()}
    expected = {"meta", "speed_zones", "speed_zones_rtree", "segment_coords"}
    assert expected.issubset(tables), f"Missing tables. Got: {tables}, Expected: {expected}"
    print(f"Tables: {tables}")

    # Check R-tree is a virtual table
    cur.execute("SELECT name, type, sql FROM sqlite_master WHERE name='speed_zones_rtree'")
    row = cur.fetchone()
    assert row and "rtree" in (row[2] or "").lower(), f"R-tree not virtual: {row}"
    print(f"R-tree: type={row[1]}, sql={row[2][:60]}...")

    # Check meta
    cur.execute("SELECT key, value FROM meta")
    meta = dict(cur.fetchall())
    print(f"Meta: {meta}")
    assert meta["version"] == "test_v1"
    assert meta["feature_count"] == "3"  # 3 valid (2 skipped)
    assert meta["skipped_no_speed"] == "2"

    # Check speed_zones
    cur.execute("SELECT id, speed_limit, direction, road_name, road_type FROM speed_zones ORDER BY id")
    zones = cur.fetchall()
    print(f"\nSpeed zones ({len(zones)}):")
    for z in zones:
        print(f"  id={z[0]}, limit={z[1]}, dir={z[2]}, name={z[3]} {z[4]}")
    assert len(zones) == 3, f"Expected 3 zones, got {len(zones)}"

    # Verify speed limits
    limits = {z[0]: z[1] for z in zones}
    assert limits[1] == 60  # Flinders St
    assert limits[2] == 40  # School zone
    assert limits[3] == 100  # Highway (no bbox filter in this test)

    # Check R-tree entries
    cur.execute("SELECT id, min_lon, max_lon, min_lat, max_lat FROM speed_zones_rtree ORDER BY id")
    rtrees = cur.fetchall()
    print(f"\nR-tree entries ({len(rtrees)}):")
    for r in rtrees:
        print(f"  id={r[0]}, lon=[{r[1]:.4f}, {r[2]:.4f}], lat=[{r[3]:.4f}, {r[4]:.4f}]")
    assert len(rtrees) == 3

    # Check segment_coords
    cur.execute("SELECT zone_id, segment_idx, coords FROM segment_coords ORDER BY zone_id, segment_idx")
    segs = cur.fetchall()
    print(f"\nSegment coords ({len(segs)}):")
    for s in segs:
        coords = json.loads(s[2])
        print(f"  zone={s[0]}, seg={s[1]}, points={len(coords)}, first={coords[0]}")
    # Feature 1 has 2 segments, Feature 2 has 1, Feature 3 has 1 = 4 total
    assert len(segs) == 4, f"Expected 4 segments, got {len(segs)}"

    # --- Spatial query test: find zones near a point ---
    print("\n=== Spatial query test ===")
    # Query for a point on Flinders St (should find the 60 km/h zone)
    test_lon, test_lat = 144.9635, -37.8188
    # Add a small tolerance (~50m ≈ 0.0005 degrees)
    tol = 0.005
    cur.execute(
        """
        SELECT sz.id, sz.speed_limit, sz.road_name
        FROM speed_zones_rtree r
        JOIN speed_zones sz ON sz.id = r.id
        WHERE r.min_lon <= ? AND r.max_lon >= ?
          AND r.min_lat <= ? AND r.max_lat >= ?
        """,
        (test_lon + tol, test_lon - tol, test_lat + tol, test_lat - tol),
    )
    candidates = cur.fetchall()
    print(f"Query point ({test_lon}, {test_lat}):")
    print(f"  Candidates from R-tree: {candidates}")
    assert len(candidates) == 1, f"Expected 1 candidate, got {len(candidates)}"
    assert candidates[0][1] == 60, f"Expected 60 km/h, got {candidates[0][1]}"
    print(f"  ✓ Found 60 km/h zone (Flinders St)")

    # Query for a point near the school zone (should find 40 km/h)
    test_lon2, test_lat2 = 144.9702, -37.8102
    cur.execute(
        """
        SELECT sz.id, sz.speed_limit, sz.road_name
        FROM speed_zones_rtree r
        JOIN speed_zones sz ON sz.id = r.id
        WHERE r.min_lon <= ? AND r.max_lon >= ?
          AND r.min_lat <= ? AND r.max_lat >= ?
        """,
        (test_lon2 + tol, test_lon2 - tol, test_lat2 + tol, test_lat2 - tol),
    )
    candidates2 = cur.fetchall()
    print(f"\nQuery point ({test_lon2}, {test_lat2}):")
    print(f"  Candidates from R-tree: {candidates2}")
    assert len(candidates2) == 1, f"Expected 1 candidate, got {len(candidates2)}"
    assert candidates2[0][1] == 40, f"Expected 40 km/h, got {candidates2[0][1]}"
    print(f"  ✓ Found 40 km/h zone (School)")

    # Query a point where no zone exists
    test_lon3, test_lat3 = 146.0, -38.0
    cur.execute(
        """
        SELECT sz.id, sz.speed_limit, sz.road_name
        FROM speed_zones_rtree r
        JOIN speed_zones sz ON sz.id = r.id
        WHERE r.min_lon <= ? AND r.max_lon >= ?
          AND r.min_lat <= ? AND r.max_lat >= ?
        """,
        (test_lon3 + tol, test_lon3 - tol, test_lat3 + tol, test_lat3 - tol),
    )
    candidates3 = cur.fetchall()
    print(f"\nQuery point ({test_lon3}, {test_lat3}) [should be empty]:")
    print(f"  Candidates: {candidates3}")
    assert len(candidates3) == 0
    print(f"  ✓ Correctly returned no zones")

    # --- Test bbox filtering ---
    print("\n=== Bounding-box filter test ===")
    db_bbox_path = os.path.join(tmpdir, "test_bbox.sqlite3")
    result2 = subprocess.run(
        [
            sys.executable,
            build_script,
            "--input", geojson_path,
            "--output", db_bbox_path,
            "--bbox", "144.95,-37.82,145.0,-37.80",
            "--version", "test_bbox",
        ],
        capture_output=True,
        text=True,
    )
    print(result2.stderr)
    if result2.returncode != 0:
        print("STDOUT:", result2.stdout)
        print("FAILED: bbox pipeline returned non-zero")
        sys.exit(1)

    conn2 = sqlite3.connect(db_bbox_path)
    cur2 = conn2.cursor()
    cur2.execute("SELECT COUNT(*) FROM speed_zones")
    bbox_count = cur2.fetchone()[0]
    print(f"Zones with bbox filter: {bbox_count}")
    assert bbox_count == 2, f"Expected 2 zones in bbox, got {bbox_count}"
    cur2.execute("SELECT speed_limit FROM speed_zones ORDER BY id")
    bbox_limits = [r[0] for r in cur2.fetchall()]
    print(f"Speed limits: {bbox_limits}")
    assert 60 in bbox_limits
    assert 40 in bbox_limits
    assert 100 not in bbox_limits  # rural, filtered out
    print("  ✓ Bbox correctly excluded rural 100 km/h zone")

    conn.close()
    conn2.close()

    # Clean up
    import shutil
    shutil.rmtree(tmpdir, ignore_errors=True)

    print("\n=== ALL TESTS PASSED ===")


if __name__ == "__main__":
    main()