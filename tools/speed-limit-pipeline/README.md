# Speed Limit Pipeline

Converts Transport Victoria's Speed Zones GeoJSON (~454MB) into a compact
SQLite database with an R-tree spatial index for fast on-device lookups.

## Usage

```bash
# Full dataset (download + process)
python build_speed_db.py --output speed_zones_vic.sqlite3

# Process an already-downloaded GeoJSON
python build_speed_db.py --input speed_zones_july_2026.geojson --output speed_zones_vic.sqlite3

# Limit to a bounding box (for testing / metro-only builds)
python build_speed_db.py --bbox 144.6,-38.2,145.1,-37.7 --output metro.sqlite3
```

## Output Schema

```sql
-- Metadata table
CREATE TABLE meta (
    key TEXT PRIMARY KEY,
    value TEXT
);
-- e.g. ('version', 'july_2026'), ('source', 'Transport Victoria'),
--      ('generated_at', '2026-08-28'), ('feature_count', '123456')

-- Main data table (one row per speed-zone segment)
CREATE TABLE speed_zones (
    id INTEGER PRIMARY KEY,       -- rowid for R-tree linkage
    speed_limit INTEGER NOT NULL,  -- km/h
    direction TEXT,                -- 'North', 'South', 'Both', etc.
    road_name TEXT,
    road_type TEXT,
    zone_conditions TEXT,          -- JSON array
    zone_length REAL               -- metres
);

-- R-tree virtual table for fast bounding-box queries
CREATE VIRTUAL TABLE speed_zones_rtree USING rtree(
    id,
    min_lon, max_lon,
    min_lat, max_lat
);

-- Segment coordinates stored as JSON for point-to-line distance
CREATE TABLE segment_coords (
    zone_id INTEGER NOT NULL,
    segment_idx INTEGER NOT NULL,
    coords TEXT NOT NULL,           -- JSON: [[lon, lat], [lon, lat], ...]
    FOREIGN KEY (zone_id) REFERENCES speed_zones(id)
);
```

## Query Strategy (Android side)

1. Query R-tree for all zones whose bounding box contains the GPS point
2. For each candidate, compute point-to-segment distance (segment_coords)
3. Return the speed_limit of the nearest segment within a threshold (e.g. 30m)
4. If direction matters, filter by heading vs zone direction

## License

Input data: Creative Commons Attribution 4.0 (Transport Victoria)
Pipeline code: MIT (CAMusic project)