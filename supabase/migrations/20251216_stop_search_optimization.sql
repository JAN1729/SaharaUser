-- ============================================================================
-- Full-Text Search Optimization for Route Stops
-- Created: 2025-12-16
-- Purpose: Enable fuzzy matching for stop names with tsvector and aliases
-- ============================================================================

-- ============================================================================
-- STEP 1: Add search_vector column with GIN index
-- ============================================================================

-- Add tsvector column for full-text search (generated from name)
ALTER TABLE route_stops 
ADD COLUMN IF NOT EXISTS search_vector tsvector 
GENERATED ALWAYS AS (to_tsvector('simple', COALESCE(name, ''))) STORED;

-- Create GIN index for fast full-text search
CREATE INDEX IF NOT EXISTS idx_route_stops_search 
ON route_stops USING GIN(search_vector);

-- Add normalized_name for consistent matching (lowercase, no special chars)
ALTER TABLE route_stops 
ADD COLUMN IF NOT EXISTS normalized_name TEXT 
GENERATED ALWAYS AS (
    LOWER(REGEXP_REPLACE(COALESCE(name, ''), '[^a-zA-Z0-9\s]', '', 'g'))
) STORED;

-- Index on normalized_name for LIKE queries
CREATE INDEX IF NOT EXISTS idx_route_stops_normalized_name 
ON route_stops (normalized_name);

-- ============================================================================
-- STEP 2: Create stop_aliases table for abbreviations/local names
-- ============================================================================

CREATE TABLE IF NOT EXISTS stop_aliases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stop_id TEXT NOT NULL,  -- Matches route_stops.id type (TEXT)
    alias TEXT NOT NULL,
    alias_type TEXT DEFAULT 'abbreviation', -- 'abbreviation', 'local_name', 'alternate_spelling'
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', COALESCE(alias, ''))) STORED,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT fk_stop_aliases_route_stops 
        FOREIGN KEY (stop_id) REFERENCES route_stops(id) ON DELETE CASCADE
);

-- Index on stop_aliases for fast lookups
CREATE INDEX IF NOT EXISTS idx_stop_aliases_stop_id ON stop_aliases(stop_id);
CREATE INDEX IF NOT EXISTS idx_stop_aliases_search ON stop_aliases USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS idx_stop_aliases_alias ON stop_aliases(LOWER(alias));

-- ============================================================================
-- STEP 3: RPC Function for fuzzy stop search
-- ============================================================================

CREATE OR REPLACE FUNCTION search_stops_fuzzy(
    search_query TEXT,
    max_results INTEGER DEFAULT 50
)
RETURNS TABLE (
    id TEXT,
    vehicle_id TEXT,
    name TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    stop_order INTEGER,
    match_rank REAL,
    match_source TEXT
)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    tsquery_val tsquery;
    normalized_query TEXT;
BEGIN
    -- Normalize the search query
    normalized_query := LOWER(REGEXP_REPLACE(COALESCE(search_query, ''), '[^a-zA-Z0-9\s]', '', 'g'));
    
    -- Create tsquery from NORMALIZED search terms (handles punctuation like "mg-road!!!")
    tsquery_val := plainto_tsquery('simple', normalized_query);
    
    RETURN QUERY
    WITH ranked_stops AS (
        -- Full-text search on route_stops.name
        SELECT 
            rs.id,
            rs.vehicle_id,
            rs.name,
            rs.latitude,
            rs.longitude,
            rs.stop_order,
            ts_rank(rs.search_vector, tsquery_val) * 1.0 AS match_rank,  -- Name matches: highest weight
            'name'::TEXT AS match_source
        FROM route_stops rs
        INNER JOIN vehicles v ON rs.vehicle_id = v.id
        WHERE v.is_active = true
          AND rs.search_vector @@ tsquery_val
        
        UNION ALL  -- UNION ALL is faster, DISTINCT ON handles deduplication
        
        -- Full-text search on stop_aliases
        SELECT 
            rs.id,
            rs.vehicle_id,
            rs.name,
            rs.latitude,
            rs.longitude,
            rs.stop_order,
            ts_rank(sa.search_vector, tsquery_val) * 0.8 AS match_rank,  -- Alias matches: slightly lower weight
            'alias'::TEXT AS match_source
        FROM stop_aliases sa
        INNER JOIN route_stops rs ON sa.stop_id = rs.id
        INNER JOIN vehicles v ON rs.vehicle_id = v.id
        WHERE v.is_active = true
          AND sa.search_vector @@ tsquery_val
        
        UNION ALL  -- UNION ALL is faster, DISTINCT ON handles deduplication
        
        -- Fallback: ILIKE on normalized_name for partial matches
        SELECT 
            rs.id,
            rs.vehicle_id,
            rs.name,
            rs.latitude,
            rs.longitude,
            rs.stop_order,
            0.1::REAL AS match_rank, -- Lower rank for ILIKE matches
            'partial'::TEXT AS match_source
        FROM route_stops rs
        INNER JOIN vehicles v ON rs.vehicle_id = v.id
        WHERE v.is_active = true
          AND rs.normalized_name LIKE '%' || normalized_query || '%'
    )
    SELECT DISTINCT ON (ranked_stops.id)
        ranked_stops.id,
        ranked_stops.vehicle_id,
        ranked_stops.name,
        ranked_stops.latitude,
        ranked_stops.longitude,
        ranked_stops.stop_order,
        ranked_stops.match_rank,
        ranked_stops.match_source
    FROM ranked_stops
    ORDER BY ranked_stops.id, ranked_stops.match_rank DESC
    LIMIT max_results;
END;
$$;

-- ============================================================================
-- STEP 4: Grant permissions
-- ============================================================================

GRANT EXECUTE ON FUNCTION search_stops_fuzzy(TEXT, INTEGER) TO authenticated, anon;

-- RLS for stop_aliases (same as route_stops)
ALTER TABLE stop_aliases ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow read access to stop_aliases" ON stop_aliases
    FOR SELECT USING (true);

CREATE POLICY "Allow insert for authenticated users" ON stop_aliases
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "Allow update for authenticated users" ON stop_aliases
    FOR UPDATE USING (auth.role() = 'authenticated');

CREATE POLICY "Allow delete for authenticated users" ON stop_aliases
    FOR DELETE USING (auth.role() = 'authenticated');

-- ============================================================================
-- STEP 5: Common abbreviations for Bangalore (seed data)
-- ============================================================================

-- Note: Run this AFTER the table is created and you have some stop IDs
-- INSERT INTO stop_aliases (stop_id, alias, alias_type) VALUES
-- ('your-stop-id', 'MG Road', 'abbreviation'),
-- ('your-stop-id', 'Mahatma Gandhi Road', 'full_name'),
-- ('your-stop-id', 'e-city', 'abbreviation'),
-- ('your-stop-id', 'Electronic City', 'full_name');

-- ============================================================================
-- COMMENTS
-- ============================================================================

COMMENT ON FUNCTION search_stops_fuzzy IS 'Fuzzy search for bus stops using full-text search with tsvector, aliases, and ILIKE fallback';
COMMENT ON TABLE stop_aliases IS 'Stores alternative names, abbreviations, and local names for bus stops';
COMMENT ON INDEX idx_route_stops_search IS 'GIN index for full-text search on stop names';
COMMENT ON INDEX idx_route_stops_normalized_name IS 'Index for normalized name LIKE queries';
