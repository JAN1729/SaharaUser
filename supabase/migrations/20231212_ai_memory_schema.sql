-- ============================================================================
-- AI Memory & Vector Search Schema for Sahara Bus
-- ============================================================================
-- Run this in your Supabase SQL Editor to enable RAG capabilities

-- Enable the pgvector extension for vector similarity search
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================================
-- AI KNOWLEDGE BASE TABLE
-- Stores embeddings for semantic search (RAG)
-- ============================================================================

CREATE TABLE IF NOT EXISTS ai_knowledge (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,                          -- The text content
    embedding vector(1536),                         -- OpenAI embedding dimension
    metadata JSONB DEFAULT '{}',                    -- Flexible metadata
    namespace TEXT DEFAULT 'default',               -- For organizing content
    source TEXT,                                    -- Where this knowledge came from
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for fast vector similarity search
CREATE INDEX IF NOT EXISTS ai_knowledge_embedding_idx 
ON ai_knowledge 
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);

-- Index for namespace filtering
CREATE INDEX IF NOT EXISTS ai_knowledge_namespace_idx ON ai_knowledge(namespace);

-- ============================================================================
-- SEARCH HISTORY TABLE
-- Stores user queries for context and learning
-- ============================================================================

CREATE TABLE IF NOT EXISTS search_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    query TEXT NOT NULL,
    query_embedding vector(1536),                   -- For finding similar past queries
    intent TEXT,                                    -- Detected intent
    origin_name TEXT,
    destination_name TEXT,
    response TEXT,                                  -- AI's response
    success BOOLEAN DEFAULT true,
    confidence REAL,
    execution_time_ms INTEGER,
    user_location JSONB,                            -- GPS if available
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for user's history lookup
CREATE INDEX IF NOT EXISTS search_history_user_idx ON search_history(user_id, created_at DESC);

-- Index for similar query search
CREATE INDEX IF NOT EXISTS search_history_embedding_idx 
ON search_history 
USING ivfflat (query_embedding vector_cosine_ops)
WITH (lists = 50);

-- ============================================================================
-- CONVERSATION MEMORY TABLE
-- Stores conversation context for multi-turn interactions
-- ============================================================================

CREATE TABLE IF NOT EXISTS conversation_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    session_id UUID NOT NULL,                       -- Groups messages in a session
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    embedding vector(1536),                         -- For context retrieval
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for session retrieval
CREATE INDEX IF NOT EXISTS conversation_memory_session_idx 
ON conversation_memory(session_id, created_at);

-- Index for user's conversation history
CREATE INDEX IF NOT EXISTS conversation_memory_user_idx 
ON conversation_memory(user_id, created_at DESC);

-- ============================================================================
-- VECTOR SIMILARITY SEARCH FUNCTIONS
-- PostgreSQL functions for efficient vector search
-- ============================================================================

-- Function to search ai_knowledge table
CREATE OR REPLACE FUNCTION match_knowledge(
    query_embedding vector(1536),
    match_threshold FLOAT DEFAULT 0.7,
    match_count INT DEFAULT 5,
    filter_namespace TEXT DEFAULT NULL
)
RETURNS TABLE (
    id UUID,
    content TEXT,
    similarity FLOAT,
    metadata JSONB,
    source TEXT
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        ak.id,
        ak.content,
        1 - (ak.embedding <=> query_embedding) AS similarity,
        ak.metadata,
        ak.source
    FROM ai_knowledge ak
    WHERE 
        (filter_namespace IS NULL OR ak.namespace = filter_namespace)
        AND 1 - (ak.embedding <=> query_embedding) > match_threshold
    ORDER BY ak.embedding <=> query_embedding
    LIMIT match_count;
END;
$$;

-- Function to search conversation history for context
CREATE OR REPLACE FUNCTION get_relevant_context(
    query_embedding vector(1536),
    target_user_id TEXT,
    match_count INT DEFAULT 3
)
RETURNS TABLE (
    id UUID,
    role TEXT,
    content TEXT,
    similarity FLOAT,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        cm.id,
        cm.role,
        cm.content,
        1 - (cm.embedding <=> query_embedding) AS similarity,
        cm.created_at
    FROM conversation_memory cm
    WHERE cm.user_id = target_user_id
        AND cm.embedding IS NOT NULL
    ORDER BY cm.embedding <=> query_embedding
    LIMIT match_count;
END;
$$;

-- Function to find similar past queries
CREATE OR REPLACE FUNCTION find_similar_queries(
    query_embedding vector(1536),
    target_user_id TEXT,
    match_count INT DEFAULT 3
)
RETURNS TABLE (
    id UUID,
    query TEXT,
    intent TEXT,
    response TEXT,
    similarity FLOAT,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        sh.id,
        sh.query,
        sh.intent,
        sh.response,
        1 - (sh.query_embedding <=> query_embedding) AS similarity,
        sh.created_at
    FROM search_history sh
    WHERE sh.user_id = target_user_id
        AND sh.query_embedding IS NOT NULL
        AND sh.success = true
    ORDER BY sh.query_embedding <=> query_embedding
    LIMIT match_count;
END;
$$;

-- ============================================================================
-- ROW LEVEL SECURITY
-- ============================================================================

ALTER TABLE ai_knowledge ENABLE ROW LEVEL SECURITY;
ALTER TABLE search_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_memory ENABLE ROW LEVEL SECURITY;

-- Policies for ai_knowledge (readable by all, writable by service role)
CREATE POLICY "ai_knowledge_read_all" ON ai_knowledge
    FOR SELECT USING (true);

CREATE POLICY "ai_knowledge_insert_service" ON ai_knowledge
    FOR INSERT WITH CHECK (true);

-- Policies for search_history (users can only see their own)
CREATE POLICY "search_history_select_own" ON search_history
    FOR SELECT USING (true);  -- Service role can read all for analytics

CREATE POLICY "search_history_insert_all" ON search_history
    FOR INSERT WITH CHECK (true);

-- Policies for conversation_memory (users can only see their own)
CREATE POLICY "conversation_memory_select_own" ON conversation_memory
    FOR SELECT USING (true);

CREATE POLICY "conversation_memory_insert_all" ON conversation_memory
    FOR INSERT WITH CHECK (true);

-- ============================================================================
-- SEED KNOWLEDGE (Optional - Add domain-specific knowledge)
-- ============================================================================

-- Example: Add some base knowledge about the bus system
-- INSERT INTO ai_knowledge (content, namespace, source, metadata) VALUES
-- ('Sahara Bus operates in major cities with real-time GPS tracking.', 'system', 'manual', '{"category": "about"}'),
-- ('To find a bus route, tell me your origin and destination stops.', 'system', 'manual', '{"category": "help"}'),
-- ('Bus ETAs are calculated using real-time traffic and historical data.', 'system', 'manual', '{"category": "features"}');

-- Note: Embeddings for seed data should be generated via the embed function
