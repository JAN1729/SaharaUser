-- ============================================================
-- SUPABASE USER SCHEMA FOR SAHARAUSER APP
-- Run this in your Supabase SQL Editor
-- ============================================================

-- ============================================================
-- TABLE: users
-- End users (passengers) who use the SaharaUser app
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,                    -- USR-XXXXXXXX (unique user ID)
    phone TEXT NOT NULL UNIQUE,             -- 10 digits
    first_name TEXT,
    last_name TEXT,
    email TEXT,
    photo_url TEXT,
    
    -- Preferences
    preferred_language TEXT DEFAULT 'en',
    preferred_route_type TEXT DEFAULT 'fastest', -- 'fastest', 'shortest', 'cheapest'
    accessibility_needs JSONB DEFAULT '{}',
    
    -- Saved locations
    home_location JSONB,                    -- {"name": "Home", "lat": 12.9, "lng": 77.5}
    work_location JSONB,
    saved_locations JSONB DEFAULT '[]',     -- Array of saved places
    
    -- Stats
    total_trips INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    last_active TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- TABLE: user_trips
-- Trip history for each user
-- ============================================================
CREATE TABLE IF NOT EXISTS user_trips (
    id TEXT PRIMARY KEY,                    -- TRIP-XXXXXXXX
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- Trip details
    origin_name TEXT NOT NULL,
    origin_lat DOUBLE PRECISION NOT NULL,
    origin_lng DOUBLE PRECISION NOT NULL,
    destination_name TEXT NOT NULL,
    destination_lat DOUBLE PRECISION NOT NULL,
    destination_lng DOUBLE PRECISION NOT NULL,
    
    -- Route taken
    route_data JSONB,                       -- Full route information
    vehicles_used JSONB,                    -- Array of vehicle IDs used
    
    -- Timing
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    estimated_duration_min INTEGER,
    actual_duration_min INTEGER,
    
    -- Status
    status TEXT DEFAULT 'planned',          -- 'planned', 'in_progress', 'completed', 'cancelled'
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- TABLE: user_search_history
-- Store user's search queries for personalization
-- ============================================================
CREATE TABLE IF NOT EXISTS user_search_history (
    id SERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    origin_name TEXT,
    destination_name TEXT,
    searched_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- INDEXES
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_users_phone ON users(phone);
CREATE INDEX IF NOT EXISTS idx_user_trips_user ON user_trips(user_id);
CREATE INDEX IF NOT EXISTS idx_user_trips_status ON user_trips(user_id, status);
CREATE INDEX IF NOT EXISTS idx_search_history_user ON user_search_history(user_id);
CREATE INDEX IF NOT EXISTS idx_search_history_time ON user_search_history(user_id, searched_at DESC);

-- ============================================================
-- RLS Policies for Users
-- ============================================================
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_trips ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_search_history ENABLE ROW LEVEL SECURITY;

-- Allow anon access for now (tighten with proper auth later)
CREATE POLICY "Allow anon insert on users" ON users 
    FOR INSERT TO anon WITH CHECK (true);

CREATE POLICY "Allow anon update on users" ON users 
    FOR UPDATE TO anon USING (true) WITH CHECK (true);

CREATE POLICY "Allow anon select on users" ON users 
    FOR SELECT TO anon USING (true);

CREATE POLICY "Allow anon all on user_trips" ON user_trips 
    FOR ALL TO anon USING (true) WITH CHECK (true);

CREATE POLICY "Allow anon all on user_search_history" ON user_search_history 
    FOR ALL TO anon USING (true) WITH CHECK (true);

-- ============================================================
-- Updated_at trigger (reuse if exists)
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

DROP TRIGGER IF EXISTS update_users_updated_at ON users;
CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- VERIFICATION
-- ============================================================
-- Run these to verify tables were created:
-- SELECT * FROM users LIMIT 1;
-- SELECT * FROM user_trips LIMIT 1;
-- SELECT * FROM user_search_history LIMIT 1;
