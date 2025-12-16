-- ============================================================================ 
-- Optimized Indexes for Spatial & Temporal Filtering
-- Run this in Supabase SQL Editor
-- ============================================================================

-- NOTE: recorded_at is stored as BIGINT (Unix timestamp in milliseconds)

CREATE INDEX IF NOT EXISTS locations_recorded_at_idx 
    ON locations(recorded_at DESC);

CREATE INDEX IF NOT EXISTS locations_bus_recorded_at_idx 
    ON locations(bus_id, recorded_at DESC);

CREATE INDEX IF NOT EXISTS route_stops_lat_idx ON route_stops(latitude);
CREATE INDEX IF NOT EXISTS route_stops_lng_idx ON route_stops(longitude);

-- ============================================================================ 
-- RPC: Fetch latest locations within time window (returns latest per bus)
-- Handles recorded_at as BIGINT (milliseconds since epoch)
-- ============================================================================

CREATE OR REPLACE FUNCTION get_fresh_locations(
    time_window_seconds INT DEFAULT 120
)
RETURNS TABLE (
    bus_id TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    speed DOUBLE PRECISION,
    heading DOUBLE PRECISION,
    recorded_at BIGINT
)
LANGUAGE sql STABLE
AS $$
    SELECT DISTINCT ON (bus_id)
        bus_id, latitude, longitude, speed, heading, recorded_at
    FROM locations
    WHERE recorded_at > (EXTRACT(EPOCH FROM NOW()) * 1000 - time_window_seconds * 1000)::BIGINT
    ORDER BY bus_id, recorded_at DESC;
$$;

-- ============================================================================ 
-- RPC: Stops within bounding box
-- ============================================================================

CREATE OR REPLACE FUNCTION get_stops_in_bbox(
    lat_min DOUBLE PRECISION,
    lat_max DOUBLE PRECISION,
    lng_min DOUBLE PRECISION,
    lng_max DOUBLE PRECISION
)
RETURNS TABLE (
    id TEXT,
    vehicle_id TEXT,
    name TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    stop_order INT
)
LANGUAGE sql STABLE
AS $$
    SELECT id, vehicle_id, name, latitude, longitude, stop_order
    FROM route_stops
    WHERE latitude BETWEEN lat_min AND lat_max
      AND longitude BETWEEN lng_min AND lng_max
    ORDER BY vehicle_id, stop_order;
$$;

-- ============================================================================ 
-- RPC: Smart similar query search with recency boost
-- ============================================================================

CREATE OR REPLACE FUNCTION find_similar_queries_optimized(
    query_embedding vector(1536),
    target_user_id TEXT,
    match_count INT DEFAULT 3,
    recency_days INT DEFAULT 7
)
RETURNS TABLE (
    query TEXT,
    intent TEXT,
    response TEXT,
    similarity FLOAT,
    recency_score FLOAT
)
LANGUAGE sql STABLE
AS $$
    SELECT
        sh.query,
        sh.intent,
        sh.response,
        1 - (sh.query_embedding <=> query_embedding) AS similarity,
        EXP(-EXTRACT(EPOCH FROM (NOW() - sh.created_at)) / (recency_days * 86400)) AS recency_score
    FROM search_history sh
    WHERE sh.user_id = target_user_id
        AND sh.query_embedding IS NOT NULL
        AND sh.success = true
        AND sh.created_at > NOW() - (recency_days || ' days')::INTERVAL
    ORDER BY 
        (1 - (sh.query_embedding <=> query_embedding)) * 0.7 + 
        EXP(-EXTRACT(EPOCH FROM (NOW() - sh.created_at)) / (recency_days * 86400)) * 0.3 
        DESC
    LIMIT match_count;
$$;

-- ============================================================================ 
-- RPC: Get vehicles matching route
-- ============================================================================

CREATE OR REPLACE FUNCTION get_vehicles_for_route(
    origin_pattern TEXT,
    destination_pattern TEXT
)
RETURNS TABLE (
    vehicle_id TEXT,
    vehicle_name TEXT,
    vehicle_number TEXT,
    origin_stop TEXT,
    origin_order INT,
    destination_stop TEXT,
    destination_order INT,
    stops_between INT
)
LANGUAGE sql STABLE
AS $$
    SELECT DISTINCT ON (v.id)
        v.id as vehicle_id,
        v.name as vehicle_name,
        v.vehicle_number,
        origin_s.name as origin_stop,
        origin_s.stop_order as origin_order,
        dest_s.name as destination_stop,
        dest_s.stop_order as destination_order,
        dest_s.stop_order - origin_s.stop_order as stops_between
    FROM vehicles v
    JOIN route_stops origin_s ON origin_s.vehicle_id = v.id
    JOIN route_stops dest_s ON dest_s.vehicle_id = v.id
    WHERE v.is_active = true
        AND origin_s.name ILIKE '%' || origin_pattern || '%'
        AND dest_s.name ILIKE '%' || destination_pattern || '%'
        AND dest_s.stop_order > origin_s.stop_order
    ORDER BY v.id, dest_s.stop_order - origin_s.stop_order;
$$;
