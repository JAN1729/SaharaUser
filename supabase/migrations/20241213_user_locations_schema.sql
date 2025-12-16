-- ============================================================================
-- User Real-Time Location Tracking Schema
-- Run this in Supabase SQL Editor
-- ============================================================================
-- Purpose: Enable AI agents to access real-time user locations for:
--   - Navigation assistance to nearest bus stops
--   - Multi-agent coordination and trip planning
--   - Proximity-based recommendations

-- ============================================================================
-- TRACKING ID GENERATOR FUNCTION
-- Generates unique 4-digit alphanumeric ID with 'u-' prefix (e.g., u-A1B2)
-- ============================================================================

CREATE OR REPLACE FUNCTION generate_user_tracking_id()
RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    chars TEXT := 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    result TEXT := 'u-';
    i INT;
BEGIN
    FOR i IN 1..4 LOOP
        result := result || substr(chars, floor(random() * length(chars) + 1)::INT, 1);
    END LOOP;
    RETURN result;
END;
$$;

-- ============================================================================
-- ADD TRACKING_ID TO USERS TABLE
-- ============================================================================

-- Add tracking_id column to users table if it doesn't exist
DO $$ 
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'users' AND column_name = 'tracking_id'
    ) THEN
        ALTER TABLE users ADD COLUMN tracking_id TEXT UNIQUE;
    END IF;
END $$;

-- Create index for tracking_id lookups
CREATE INDEX IF NOT EXISTS idx_users_tracking_id ON users(tracking_id);

-- ============================================================================
-- TABLE: user_locations
-- Stores real-time GPS positions for each user (10-second intervals)
-- ============================================================================

CREATE TABLE IF NOT EXISTS user_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tracking_id TEXT NOT NULL,                   -- u-XXXX format for quick lookups
    
    -- GPS Data
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy REAL,                               -- GPS accuracy in meters
    speed REAL,                                  -- Speed in m/s (nullable)
    heading REAL,                                -- Direction in degrees (nullable)
    altitude REAL,                               -- Altitude in meters (nullable)
    
    -- Metadata
    recorded_at TIMESTAMPTZ DEFAULT NOW(),       -- When location was captured
    synced_at TIMESTAMPTZ DEFAULT NOW(),         -- When synced to server
    
    -- Device info (optional)
    device_info JSONB DEFAULT '{}'               -- Battery level, network type, etc.
);

-- ============================================================================
-- OPTIMIZED INDEXES FOR USER LOCATION QUERIES
-- ============================================================================

-- Fast lookup by user_id (most common query pattern)
CREATE INDEX IF NOT EXISTS idx_user_locations_user_id 
    ON user_locations(user_id, recorded_at DESC);

-- Fast lookup by tracking_id (for AI agents)
CREATE INDEX IF NOT EXISTS idx_user_locations_tracking_id 
    ON user_locations(tracking_id, recorded_at DESC);

-- Temporal index for recent locations
CREATE INDEX IF NOT EXISTS idx_user_locations_recorded_at 
    ON user_locations(recorded_at DESC);

-- Spatial indexes for proximity queries
CREATE INDEX IF NOT EXISTS idx_user_locations_lat ON user_locations(latitude);
CREATE INDEX IF NOT EXISTS idx_user_locations_lng ON user_locations(longitude);

-- ============================================================================
-- RPC: Get latest location for a user
-- ============================================================================

CREATE OR REPLACE FUNCTION get_user_latest_location(target_user_id TEXT)
RETURNS TABLE (
    user_id TEXT,
    tracking_id TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    accuracy REAL,
    speed REAL,
    heading REAL,
    recorded_at TIMESTAMPTZ
)
LANGUAGE sql STABLE
AS $$
    SELECT 
        ul.user_id,
        ul.tracking_id,
        ul.latitude,
        ul.longitude,
        ul.accuracy,
        ul.speed,
        ul.heading,
        ul.recorded_at
    FROM user_locations ul
    WHERE ul.user_id = target_user_id
    ORDER BY ul.recorded_at DESC
    LIMIT 1;
$$;

-- ============================================================================
-- RPC: Get latest location by tracking ID (for AI agents)
-- ============================================================================

CREATE OR REPLACE FUNCTION get_location_by_tracking_id(target_tracking_id TEXT)
RETURNS TABLE (
    user_id TEXT,
    tracking_id TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    accuracy REAL,
    speed REAL,
    heading REAL,
    recorded_at TIMESTAMPTZ,
    age_seconds INTEGER
)
LANGUAGE sql STABLE
AS $$
    SELECT 
        ul.user_id,
        ul.tracking_id,
        ul.latitude,
        ul.longitude,
        ul.accuracy,
        ul.speed,
        ul.heading,
        ul.recorded_at,
        EXTRACT(EPOCH FROM (NOW() - ul.recorded_at))::INTEGER as age_seconds
    FROM user_locations ul
    WHERE ul.tracking_id = target_tracking_id
    ORDER BY ul.recorded_at DESC
    LIMIT 1;
$$;

-- ============================================================================
-- RPC: Get all fresh user locations (within time window)
-- For multi-agent coordination
-- ============================================================================

CREATE OR REPLACE FUNCTION get_fresh_user_locations(time_window_seconds INT DEFAULT 60)
RETURNS TABLE (
    user_id TEXT,
    tracking_id TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    accuracy REAL,
    speed REAL,
    recorded_at TIMESTAMPTZ
)
LANGUAGE sql STABLE
AS $$
    SELECT DISTINCT ON (user_id)
        ul.user_id,
        ul.tracking_id,
        ul.latitude,
        ul.longitude,
        ul.accuracy,
        ul.speed,
        ul.recorded_at
    FROM user_locations ul
    WHERE ul.recorded_at > NOW() - (time_window_seconds || ' seconds')::INTERVAL
    ORDER BY ul.user_id, ul.recorded_at DESC;
$$;

-- ============================================================================
-- ROW LEVEL SECURITY
-- ============================================================================

ALTER TABLE user_locations ENABLE ROW LEVEL SECURITY;

-- Allow anon access for edge functions (tighten with proper auth later)
CREATE POLICY "user_locations_insert_all" ON user_locations
    FOR INSERT WITH CHECK (true);

CREATE POLICY "user_locations_select_all" ON user_locations
    FOR SELECT USING (true);

-- ============================================================================
-- CLEANUP: Remove old locations (optional, run periodically)
-- Keeps only last 24 hours of location history per user
-- ============================================================================

CREATE OR REPLACE FUNCTION cleanup_old_user_locations()
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    WITH deleted AS (
        DELETE FROM user_locations
        WHERE recorded_at < NOW() - INTERVAL '24 hours'
        RETURNING id
    )
    SELECT COUNT(*) INTO deleted_count FROM deleted;
    
    RETURN deleted_count;
END;
$$;

-- ============================================================================
-- VERIFICATION QUERIES (run after migration)
-- ============================================================================
-- SELECT * FROM user_locations LIMIT 5;
-- SELECT tracking_id FROM users WHERE tracking_id IS NOT NULL LIMIT 5;
-- SELECT * FROM get_fresh_user_locations(60);
