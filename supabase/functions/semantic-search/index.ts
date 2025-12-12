/**
 * Semantic Search Edge Function - RAG Retrieval
 * 
 * Performs vector similarity search for Retrieval Augmented Generation.
 * Supports:
 * - Query embedding generation
 * - Vector similarity search in pgvector
 * - Context retrieval for AI responses
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// CONFIGURATION
// ============================================================================

const EMBEDDING_DIMENSION = 1536;
const DEFAULT_TOP_K = 5;
const DEFAULT_SIMILARITY_THRESHOLD = 0.7;

// ============================================================================
// TYPES
// ============================================================================

interface SearchRequest {
    query: string;                      // Search query text
    tableName: string;                  // Table to search in
    topK?: number;                      // Number of results to return
    similarityThreshold?: number;       // Minimum similarity score (0-1)
    namespace?: string;                 // Optional namespace filter
    filters?: Record<string, unknown>;  // Additional metadata filters
}

interface SearchResult {
    id: string;
    content: string;
    similarity: number;
    metadata?: Record<string, unknown>;
}

interface SearchResponse {
    success: boolean;
    results?: SearchResult[];
    query?: string;
    error?: string;
    processingTimeMs: number;
}

// ============================================================================
// CORS HEADERS
// ============================================================================

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

// ============================================================================
// EMBEDDING GENERATION (Same as embed function)
// ============================================================================

async function generateEmbedding(text: string): Promise<number[]> {
    const openaiKey = Deno.env.get('OPENAI_API_KEY');

    if (openaiKey) {
        try {
            const response = await fetch('https://api.openai.com/v1/embeddings', {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${openaiKey}`,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    model: 'text-embedding-3-small',
                    input: text
                })
            });

            if (!response.ok) {
                throw new Error(`OpenAI API error: ${response.status}`);
            }

            const data = await response.json();
            return data.data[0].embedding;
        } catch (error) {
            console.error('OpenAI embedding failed, using fallback:', error);
        }
    }

    return generateHashEmbedding(text, EMBEDDING_DIMENSION);
}

function generateHashEmbedding(text: string, dimension: number): number[] {
    const embedding: number[] = new Array(dimension).fill(0);
    const normalized = text.toLowerCase().trim();

    for (let i = 0; i < normalized.length; i++) {
        const charCode = normalized.charCodeAt(i);
        const idx = (i * 7 + charCode) % dimension;
        embedding[idx] += Math.sin(charCode * (i + 1)) * 0.1;
    }

    for (let i = 0; i < normalized.length - 1; i++) {
        const bigram = normalized.substring(i, i + 2);
        const hash = (bigram.charCodeAt(0) * 31 + bigram.charCodeAt(1)) % dimension;
        embedding[hash] += 0.05;
    }

    const magnitude = Math.sqrt(embedding.reduce((sum, val) => sum + val * val, 0));
    if (magnitude > 0) {
        for (let i = 0; i < dimension; i++) {
            embedding[i] /= magnitude;
        }
    }

    return embedding;
}

// ============================================================================
// VECTOR SEARCH
// ============================================================================

/**
 * Perform vector similarity search using pgvector
 * Uses cosine similarity for matching
 */
async function vectorSearch(
    tableName: string,
    queryEmbedding: number[],
    topK: number,
    similarityThreshold: number,
    namespace?: string,
    filters?: Record<string, unknown>
): Promise<SearchResult[]> {
    const supabaseUrl = Deno.env.get('SUPABASE_URL');
    const supabaseKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

    if (!supabaseUrl || !supabaseKey) {
        throw new Error('Missing Supabase credentials');
    }

    const supabase = createClient(supabaseUrl, supabaseKey);

    // Build the RPC call for vector similarity search
    // This requires a PostgreSQL function to be set up
    const { data, error } = await supabase.rpc('match_documents', {
        query_embedding: queryEmbedding,
        match_threshold: similarityThreshold,
        match_count: topK,
        table_name: tableName,
        filter_namespace: namespace || null
    });

    if (error) {
        // Fallback: Try direct query if RPC doesn't exist
        console.warn('RPC match_documents not found, using fallback search');
        return await fallbackSearch(supabase, tableName, queryEmbedding, topK, namespace);
    }

    return (data || []).map((row: any) => ({
        id: row.id,
        content: row.content,
        similarity: row.similarity,
        metadata: row.metadata
    }));
}

/**
 * Fallback search using direct SQL through Supabase
 * Less efficient but works without custom RPC functions
 */
async function fallbackSearch(
    supabase: any,
    tableName: string,
    queryEmbedding: number[],
    topK: number,
    namespace?: string
): Promise<SearchResult[]> {
    // Fetch all records and compute similarity in-memory
    // This is less efficient but works without pgvector RPC
    let query = supabase.from(tableName).select('id, content, embedding, metadata, namespace');

    if (namespace) {
        query = query.eq('namespace', namespace);
    }

    const { data, error } = await query.limit(1000);

    if (error) {
        throw new Error(`Failed to fetch documents: ${error.message}`);
    }

    if (!data || data.length === 0) {
        return [];
    }

    // Compute cosine similarity for each document
    const results: SearchResult[] = data.map((doc: any) => {
        let docEmbedding: number[];

        // Parse embedding from different formats
        if (typeof doc.embedding === 'string') {
            // pgvector returns as string like "[0.1, 0.2, ...]"
            docEmbedding = JSON.parse(doc.embedding.replace(/^\[|\]$/g, '').split(',').map(Number));
        } else if (Array.isArray(doc.embedding)) {
            docEmbedding = doc.embedding;
        } else {
            return null;
        }

        const similarity = cosineSimilarity(queryEmbedding, docEmbedding);

        return {
            id: doc.id,
            content: doc.content,
            similarity,
            metadata: doc.metadata
        };
    }).filter((r: any) => r !== null);

    // Sort by similarity and take top K
    results.sort((a, b) => b.similarity - a.similarity);
    return results.slice(0, topK);
}

/**
 * Calculate cosine similarity between two vectors
 */
function cosineSimilarity(a: number[], b: number[]): number {
    if (a.length !== b.length) return 0;

    let dotProduct = 0;
    let normA = 0;
    let normB = 0;

    for (let i = 0; i < a.length; i++) {
        dotProduct += a[i] * b[i];
        normA += a[i] * a[i];
        normB += b[i] * b[i];
    }

    if (normA === 0 || normB === 0) return 0;

    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
}

// ============================================================================
// MAIN HANDLER
// ============================================================================

serve(async (req: Request) => {
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    if (req.method !== 'POST') {
        return new Response(
            JSON.stringify({ error: 'Method not allowed' }),
            { status: 405, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }

    const startTime = Date.now();

    try {
        const request: SearchRequest = await req.json();
        const {
            query,
            tableName,
            topK = DEFAULT_TOP_K,
            similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD,
            namespace,
            filters
        } = request;

        if (!query || !tableName) {
            return new Response(
                JSON.stringify({ success: false, error: 'Query and tableName are required' }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        console.log(`[SEARCH] Searching "${query}" in ${tableName} (top ${topK})...`);

        // Generate query embedding
        const queryEmbedding = await generateEmbedding(query);

        // Perform vector search
        const results = await vectorSearch(
            tableName,
            queryEmbedding,
            topK,
            similarityThreshold,
            namespace,
            filters
        );

        const processingTimeMs = Date.now() - startTime;
        console.log(`[SEARCH] Found ${results.length} results in ${processingTimeMs}ms`);

        const response: SearchResponse = {
            success: true,
            results,
            query,
            processingTimeMs
        };

        return new Response(
            JSON.stringify(response),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );

    } catch (error) {
        console.error('[SEARCH] Error:', error);

        return new Response(
            JSON.stringify({
                success: false,
                error: error instanceof Error ? error.message : 'Unknown error',
                processingTimeMs: Date.now() - startTime
            }),
            { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }
});
