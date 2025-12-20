-- ============================================================================
-- Spatial Viewport Functions (Map-Action Pipeline)
-- Created: 2025-12-18
-- Purpose: Enable AI to query entities within the user's visible map viewport
-- ============================================================================

-- ============================================================================
-- FUNCTION: Get Entities in Viewport (Bounding Box Query)
-- ============================================================================
-- This function returns all buses, stops within a geographic bounding box.
-- Used by the AI to understand what the user can see on their map.
-- ============================================================================

CREATE OR REPLACE FUNCTION get_entities_in_viewport(
    north DOUBLE PRECISION,
    south DOUBLE PRECISION,
    east DOUBLE PRECISION,
    west DOUBLE PRECISION,
    max_results INTEGER DEFAULT 50,
    freshness_seconds INTEGER DEFAULT 60
)
RETURNS JSON
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    result JSON;
BEGIN
    SELECT json_build_object(
        'buses', COALESCE((
            SELECT json_agg(row_to_json(b))
            FROM (
                SELECT DISTINCT ON (l.bus_id)
                    l.bus_id,
                    v.name AS vehicle_name,
                    v.vehicle_number,
                    l.latitude,
                    l.longitude,
                    l.heading,
                    l.speed,
                    l.trip_status,
                    l.timestamp,
                    -- Calculate freshness status
                    CASE 
                        WHEN l.timestamp > (EXTRACT(EPOCH FROM NOW()) * 1000) - 30000 THEN 'live'
                        WHEN l.timestamp > (EXTRACT(EPOCH FROM NOW()) * 1000) - 120000 THEN 'recent'
                        ELSE 'stale'
                    END AS freshness
                FROM locations l
                INNER JOIN vehicles v ON l.bus_id = v.id
                WHERE v.is_active = true
                  AND l.latitude BETWEEN south AND north
                  AND l.longitude BETWEEN west AND east
                  AND l.timestamp > (EXTRACT(EPOCH FROM NOW()) * 1000) - (freshness_seconds * 1000)
                ORDER BY l.bus_id, l.timestamp DESC
                LIMIT max_results
            ) b
        ), '[]'::json),
        'stops', COALESCE((
            SELECT json_agg(row_to_json(s))
            FROM (
                SELECT 
                    rs.id,
                    rs.name,
                    rs.latitude,
                    rs.longitude,
                    rs.stop_order,
                    rs.vehicle_id,
                    v.name AS vehicle_name,
                    v.vehicle_number
                FROM route_stops rs
                INNER JOIN vehicles v ON rs.vehicle_id = v.id
                WHERE v.is_active = true
                  AND rs.latitude BETWEEN south AND north
                  AND rs.longitude BETWEEN west AND east
                LIMIT max_results
            ) s
        ), '[]'::json),
        'viewport_info', json_build_object(
            'bounds', json_build_object(
                'north', north,
                'south', south,
                'east', east,
                'west', west
            ),
            'query_timestamp', EXTRACT(EPOCH FROM NOW()) * 1000,
            'max_results', max_results
        )
    ) INTO result;
    
    RETURN result;
END;
$$;

-- ============================================================================
-- FUNCTION: Detect Route Deviation (Ghost Bus Detection)
-- ============================================================================
-- Compares live bus position against its expected route path.
-- Returns deviation distance and status.
-- ============================================================================

CREATE OR REPLACE FUNCTION detect_route_deviation(
    p_bus_id TEXT,
    deviation_threshold_km DOUBLE PRECISION DEFAULT 0.5
)
RETURNS JSON
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    bus_lat DOUBLE PRECISION;
    bus_lng DOUBLE PRECISION;
    nearest_stop_dist DOUBLE PRECISION;
    nearest_stop_name TEXT;
    result JSON;
BEGIN
    -- Get latest bus location
    SELECT latitude, longitude INTO bus_lat, bus_lng
    FROM locations
    WHERE bus_id = p_bus_id
    ORDER BY timestamp DESC
    LIMIT 1;
    
    IF bus_lat IS NULL THEN
        RETURN json_build_object(
            'success', false,
            'error', 'Bus not found or no location data'
        );
    END IF;
    
    -- Find nearest stop on the bus's route
    SELECT 
        rs.name,
        earth_distance(
            ll_to_earth(bus_lat, bus_lng),
            ll_to_earth(rs.latitude, rs.longitude)
        ) / 1000.0
    INTO nearest_stop_name, nearest_stop_dist
    FROM route_stops rs
    WHERE rs.vehicle_id = p_bus_id
    ORDER BY earth_distance(
        ll_to_earth(bus_lat, bus_lng),
        ll_to_earth(rs.latitude, rs.longitude)
    )
    LIMIT 1;
    
    -- Build result
    SELECT json_build_object(
        'success', true,
        'bus_id', p_bus_id,
        'bus_position', json_build_object('lat', bus_lat, 'lng', bus_lng),
        'nearest_stop', nearest_stop_name,
        'distance_km', ROUND(nearest_stop_dist::numeric, 3),
        'is_deviated', (nearest_stop_dist > deviation_threshold_km),
        'deviation_status', CASE
            WHEN nearest_stop_dist <= 0.1 THEN 'on_route'
            WHEN nearest_stop_dist <= deviation_threshold_km THEN 'minor_deviation'
            ELSE 'significant_deviation'
        END
    ) INTO result;
    
    RETURN result;
END;
$$;

-- ============================================================================
-- FUNCTION: Get Nearby Entities for Visual Query
-- ============================================================================
-- When user asks "What is that?" - find entities near a specific point
-- ============================================================================

CREATE OR REPLACE FUNCTION get_nearby_entities_for_visual_query(
    tap_lat DOUBLE PRECISION,
    tap_lng DOUBLE PRECISION,
    radius_km DOUBLE PRECISION DEFAULT 0.2
)
RETURNS JSON
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    result JSON;
BEGIN
    SELECT json_build_object(
        'buses', COALESCE((
            SELECT json_agg(row_to_json(b) ORDER BY b.distance_km)
            FROM (
                SELECT DISTINCT ON (l.bus_id)
                    l.bus_id,
                    v.name AS vehicle_name,
                    v.vehicle_number,
                    l.latitude,
                    l.longitude,
                    l.heading,
                    l.speed,
                    earth_distance(
                        ll_to_earth(tap_lat, tap_lng),
                        ll_to_earth(l.latitude, l.longitude)
                    ) / 1000.0 AS distance_km
                FROM locations l
                INNER JOIN vehicles v ON l.bus_id = v.id
                WHERE v.is_active = true
                  AND l.timestamp > (EXTRACT(EPOCH FROM NOW()) * 1000) - 60000
                  AND earth_box(ll_to_earth(tap_lat, tap_lng), radius_km * 1000) @> ll_to_earth(l.latitude, l.longitude)
                  AND earth_distance(ll_to_earth(tap_lat, tap_lng), ll_to_earth(l.latitude, l.longitude)) <= radius_km * 1000
                ORDER BY l.bus_id, l.timestamp DESC
            ) b
        ), '[]'::json),
        'stops', COALESCE((
            SELECT json_agg(row_to_json(s) ORDER BY s.distance_km)
            FROM (
                SELECT 
                    rs.id,
                    rs.name,
                    rs.latitude,
                    rs.longitude,
                    rs.vehicle_id,
                    v.name AS vehicle_name,
                    earth_distance(
                        ll_to_earth(tap_lat, tap_lng),
                        ll_to_earth(rs.latitude, rs.longitude)
                    ) / 1000.0 AS distance_km
                FROM route_stops rs
                INNER JOIN vehicles v ON rs.vehicle_id = v.id
                WHERE v.is_active = true
                  AND earth_box(ll_to_earth(tap_lat, tap_lng), radius_km * 1000) @> ll_to_earth(rs.latitude, rs.longitude)
                  AND earth_distance(ll_to_earth(tap_lat, tap_lng), ll_to_earth(rs.latitude, rs.longitude)) <= radius_km * 1000
                LIMIT 5
            ) s
        ), '[]'::json),
        'tap_location', json_build_object('lat', tap_lat, 'lng', tap_lng),
        'search_radius_km', radius_km
    ) INTO result;
    
    RETURN result;
END;
$$;

-- ============================================================================
-- GRANT PERMISSIONS
-- ============================================================================

GRANT EXECUTE ON FUNCTION get_entities_in_viewport(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, INTEGER, INTEGER) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION detect_route_deviation(TEXT, DOUBLE PRECISION) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION get_nearby_entities_for_visual_query(DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION) TO authenticated, anon;

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON FUNCTION get_entities_in_viewport IS 'Query all buses and stops within a geographic bounding box for Map-Action Pipeline';
COMMENT ON FUNCTION detect_route_deviation IS 'Detect if a bus has deviated from its expected route (Ghost Bus detection)';
COMMENT ON FUNCTION get_nearby_entities_for_visual_query IS 'Find entities near a tap point for visual query resolution';
