-- ============================================================================
-- Multi-Agent System Performance Optimization
-- Created: 2025-12-15
-- Purpose: Optimize DataAgent queries with indexes, RPC functions, and DISTINCT ON
-- ============================================================================

-- ============================================================================
-- INDEXES FOR MULTI-AGENT QUERIES
-- ============================================================================

-- Latest location per bus (CRITICAL for DISTINCT ON)
CREATE INDEX IF NOT EXISTS idx_locations_bus_timestamp_desc
ON locations (bus_id, timestamp DESC);

-- Freshness filter (30-40 second window)
CREATE INDEX IF NOT EXISTS idx_locations_timestamp_desc
ON locations (timestamp DESC)
WHERE timestamp > EXTRACT(EPOCH FROM NOW() - INTERVAL '2 minutes') * 1000;

-- Spatial index for route stops (3km radius queries)
CREATE INDEX IF NOT EXISTS idx_route_stops_location
ON route_stops USING GIST (ll_to_earth(latitude, longitude));

-- Stops by vehicle with order
CREATE INDEX IF NOT EXISTS idx_route_stops_vehicle_order
ON route_stops (vehicle_id, stop_order);

-- Active vehicles only
CREATE INDEX IF NOT EXISTS idx_vehicles_active
ON vehicles (is_active)
WHERE is_active = true;

-- Vehicle lookup by name/number
CREATE INDEX IF NOT EXISTS idx_vehicles_name_number
ON vehicles (name, vehicle_number)
WHERE is_active = true;

-- Trip status filter
CREATE INDEX IF NOT EXISTS idx_locations_trip_status
ON locations (trip_status, timestamp DESC)
WHERE trip_status = 'started';

-- Composite index for common query pattern
CREATE INDEX IF NOT EXISTS idx_locations_composite
ON locations (bus_id, trip_status, timestamp DESC);

-- ============================================================================
-- RPC FUNCTION: Get Latest Locations (DISTINCT ON)
-- ============================================================================

CREATE OR REPLACE FUNCTION get_latest_bus_locations(
    freshness_seconds INTEGER DEFAULT 40
)
RETURNS TABLE (
    bus_id TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    speed DOUBLE PRECISION,
    heading DOUBLE PRECISION,
    timestamp BIGINT,
    trip_status TEXT,
    accuracy DOUBLE PRECISION
) 
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    RETURN QUERY
    SELECT DISTINCT ON (l.bus_id)
        l.bus_id,
        l.latitude,
        l.longitude,
        l.speed,
        l.heading,
        l.timestamp,
        l.trip_status,
        l.accuracy
    FROM locations l
    INNER JOIN vehicles v ON l.bus_id = v.id
    WHERE v.is_active = true
      AND l.timestamp > (EXTRACT(EPOCH FROM NOW()) * 1000) - (freshness_seconds * 1000)
    ORDER BY l.bus_id, l.timestamp DESC;
END;
$$;

-- ============================================================================
-- RPC FUNCTION: Get Nearby Route Stops (3km radius)
-- ============================================================================

CREATE OR REPLACE FUNCTION get_nearby_route_stops(
    user_lat DOUBLE PRECISION,
    user_lng DOUBLE PRECISION,
    radius_km DOUBLE PRECISION DEFAULT 3.0
)
RETURNS TABLE (
    id TEXT,
    vehicle_id TEXT,
    name TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    stop_order INTEGER,
    distance_km DOUBLE PRECISION
)
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    RETURN QUERY
    SELECT 
        rs.id,
        rs.vehicle_id,
        rs.name,
        rs.latitude,
        rs.longitude,
        rs.stop_order,
        earth_distance(
            ll_to_earth(user_lat, user_lng),
            ll_to_earth(rs.latitude, rs.longitude)
        ) / 1000.0 AS distance_km
    FROM route_stops rs
    INNER JOIN vehicles v ON rs.vehicle_id = v.id
    WHERE v.is_active = true
      AND earth_box(ll_to_earth(user_lat, user_lng), radius_km * 1000) @> ll_to_earth(rs.latitude, rs.longitude)
      AND earth_distance(
          ll_to_earth(user_lat, user_lng),
          ll_to_earth(rs.latitude, rs.longitude)
      ) <= radius_km * 1000
    ORDER BY distance_km;
END;
$$;

-- ============================================================================
-- RPC FUNCTION: Get Multi-Agent Context (All-in-One)
-- ============================================================================

CREATE OR REPLACE FUNCTION get_multiagent_context(
    user_lat DOUBLE PRECISION DEFAULT NULL,
    user_lng DOUBLE PRECISION DEFAULT NULL,
    freshness_seconds INTEGER DEFAULT 40,
    radius_km DOUBLE PRECISION DEFAULT 3.0
)
RETURNS JSON
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    result JSON;
BEGIN
    SELECT json_build_object(
        'vehicles', (
            SELECT json_agg(row_to_json(v))
            FROM (
                SELECT id, name, vehicle_number, is_active
                FROM vehicles
                WHERE is_active = true
            ) v
        ),
        'locations', (
            SELECT json_agg(row_to_json(l))
            FROM (
                SELECT DISTINCT ON (bus_id)
                    bus_id, latitude, longitude, speed, heading, timestamp, trip_status, accuracy
                FROM locations
                INNER JOIN vehicles ON locations.bus_id = vehicles.id
                WHERE vehicles.is_active = true
                  AND locations.timestamp > (EXTRACT(EPOCH FROM NOW()) * 1000) - (freshness_seconds * 1000)
                ORDER BY bus_id, timestamp DESC
            ) l
        ),
        'route_stops', (
            CASE 
                WHEN user_lat IS NOT NULL AND user_lng IS NOT NULL THEN
                    (
                        SELECT json_agg(row_to_json(rs))
                        FROM (
                            SELECT 
                                id, vehicle_id, name, latitude, longitude, stop_order,
                                earth_distance(
                                    ll_to_earth(user_lat, user_lng),
                                    ll_to_earth(latitude, longitude)
                                ) / 1000.0 AS distance_km
                            FROM route_stops
                            INNER JOIN vehicles ON route_stops.vehicle_id = vehicles.id
                            WHERE vehicles.is_active = true
                              AND earth_box(ll_to_earth(user_lat, user_lng), radius_km * 1000) @> ll_to_earth(latitude, longitude)
                              AND earth_distance(
                                  ll_to_earth(user_lat, user_lng),
                                  ll_to_earth(latitude, longitude)
                              ) <= radius_km * 1000
                            ORDER BY earth_distance(
                                ll_to_earth(user_lat, user_lng),
                                ll_to_earth(latitude, longitude)
                            )
                        ) rs
                    )
                ELSE
                    (
                        SELECT json_agg(row_to_json(rs))
                        FROM (
                            SELECT id, vehicle_id, name, latitude, longitude, stop_order
                            FROM route_stops
                            INNER JOIN vehicles ON route_stops.vehicle_id = vehicles.id
                            WHERE vehicles.is_active = true
                            ORDER BY vehicle_id, stop_order
                        ) rs
                    )
            END
        ),
        'metadata', json_build_object(
            'freshness_seconds', freshness_seconds,
            'radius_km', radius_km,
            'timestamp', EXTRACT(EPOCH FROM NOW()) * 1000
        )
    ) INTO result;
    
    RETURN result;
END;
$$;

-- ============================================================================
-- GRANT PERMISSIONS
-- ============================================================================

GRANT EXECUTE ON FUNCTION get_latest_bus_locations(INTEGER) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION get_nearby_route_stops(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION get_multiagent_context(DOUBLE PRECISION, DOUBLE PRECISION, INTEGER, DOUBLE PRECISION) TO authenticated, anon;

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON FUNCTION get_latest_bus_locations IS 'Get the latest location per bus using DISTINCT ON for optimal performance';
COMMENT ON FUNCTION get_nearby_route_stops IS 'Get route stops within specified radius using PostGIS earth functions';
COMMENT ON FUNCTION get_multiagent_context IS 'Get all data needed for multi-agent system in a single RPC call';

COMMENT ON INDEX idx_locations_bus_timestamp_desc IS 'Optimizes DISTINCT ON queries for latest location per bus';
COMMENT ON INDEX idx_locations_timestamp_desc IS 'Partial index for freshness filtering (2 min window)';
COMMENT ON INDEX idx_route_stops_location IS 'Spatial index for 3km radius queries';
