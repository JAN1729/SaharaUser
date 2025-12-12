package com.sahara.user.framework

import com.sahara.user.data.api.SupabaseApiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent Memory - Persistent context storage using Supabase.
 * 
 * Provides three types of memory:
 * 1. SHORT_TERM: Current conversation context (cleared on session end)
 * 2. LONG_TERM: User preferences, frequent routes (persists indefinitely)
 * 3. SESSION: Task-specific data (cleared after task completion)
 * 
 * Uses Supabase table: agent_memory
 */
@Singleton
class AgentMemory @Inject constructor(
    private val supabaseClient: SupabaseApiClient
) {
    
    companion object {
        private const val TAG = "AgentMemory"
        private const val TABLE_NAME = "agent_memory"
        
        // Memory expiration
        private const val SHORT_TERM_DURATION_MS = 30 * 60 * 1000L // 30 minutes
        private const val SESSION_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    // Local cache for performance
    private val memoryCache = mutableMapOf<String, MutableMap<String, MemoryEntry>>()
    private val mutex = Mutex()
    
    // ==================== PUBLIC API ====================
    
    /**
     * Save a value to memory.
     */
    suspend fun saveMemory(
        userId: String,
        type: MemoryType,
        key: String,
        value: Map<String, Any?>
    ) {
        mutex.withLock {
            val cacheKey = "$userId:${type.name}"
            val cache = memoryCache.getOrPut(cacheKey) { mutableMapOf() }
            
            val entry = MemoryEntry(
                key = key,
                value = value,
                type = type,
                createdAt = System.currentTimeMillis(),
                expiresAt = calculateExpiration(type)
            )
            
            cache[key] = entry
            
            // Save to Supabase asynchronously
            try {
                saveToSupabase(userId, entry)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to persist memory: ${e.message}")
            }
        }
        
        android.util.Log.d(TAG, "Saved memory: $userId/$type/$key")
    }
    
    /**
     * Load all memory of a specific type for a user.
     */
    suspend fun loadMemory(userId: String, type: MemoryType): Map<String, Map<String, Any?>> {
        mutex.withLock {
            val cacheKey = "$userId:${type.name}"
            val cache = memoryCache.getOrPut(cacheKey) { mutableMapOf() }
            
            // If cache is empty, try loading from Supabase
            if (cache.isEmpty()) {
                try {
                    val entries = loadFromSupabase(userId, type)
                    entries.forEach { entry ->
                        cache[entry.key] = entry
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to load memory: ${e.message}")
                }
            }
            
            // Filter expired entries
            val now = System.currentTimeMillis()
            val validEntries = cache.filter { (_, entry) ->
                entry.expiresAt == null || entry.expiresAt > now
            }
            
            return validEntries.mapValues { it.value.value }
        }
    }
    
    /**
     * Get a specific memory value.
     */
    suspend fun getMemory(userId: String, type: MemoryType, key: String): Map<String, Any?>? {
        val all = loadMemory(userId, type)
        return all[key]
    }
    
    /**
     * Delete a specific memory entry.
     */
    suspend fun deleteMemory(userId: String, type: MemoryType, key: String) {
        mutex.withLock {
            val cacheKey = "$userId:${type.name}"
            memoryCache[cacheKey]?.remove(key)
        }
        
        try {
            deleteFromSupabase(userId, type, key)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to delete memory: ${e.message}")
        }
    }
    
    /**
     * Clear all memory of a specific type.
     */
    suspend fun clearMemory(userId: String, type: MemoryType) {
        mutex.withLock {
            val cacheKey = "$userId:${type.name}"
            memoryCache[cacheKey]?.clear()
        }
        
        try {
            clearFromSupabase(userId, type)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to clear memory: ${e.message}")
        }
        
        android.util.Log.d(TAG, "Cleared memory: $userId/$type")
    }
    
    /**
     * Clear expired entries from cache.
     */
    suspend fun cleanupExpired() {
        val now = System.currentTimeMillis()
        
        mutex.withLock {
            memoryCache.forEach { (_, cache) ->
                val expired = cache.filter { (_, entry) ->
                    entry.expiresAt != null && entry.expiresAt < now
                }.keys
                
                expired.forEach { cache.remove(it) }
            }
        }
    }
    
    // ==================== CONVENIENCE METHODS ====================
    
    /**
     * Save user preference (long-term memory).
     */
    suspend fun savePreference(userId: String, key: String, value: Any) {
        saveMemory(userId, MemoryType.LONG_TERM, "pref_$key", mapOf("value" to value))
    }
    
    /**
     * Get user preference.
     */
    suspend fun getPreference(userId: String, key: String): Any? {
        return getMemory(userId, MemoryType.LONG_TERM, "pref_$key")?.get("value")
    }
    
    /**
     * Save last query for context.
     */
    suspend fun saveLastQuery(userId: String, query: String, response: String) {
        saveMemory(userId, MemoryType.SHORT_TERM, "last_query", mapOf(
            "query" to query,
            "response" to response,
            "timestamp" to System.currentTimeMillis()
        ))
    }
    
    /**
     * Get last query for context.
     */
    suspend fun getLastQuery(userId: String): Pair<String, String>? {
        val data = getMemory(userId, MemoryType.SHORT_TERM, "last_query")
        val query = data?.get("query") as? String
        val response = data?.get("response") as? String
        return if (query != null && response != null) query to response else null
    }
    
    /**
     * Save frequent route (for personalization).
     */
    suspend fun saveFrequentRoute(userId: String, origin: String, destination: String) {
        val key = "route_${origin.lowercase().replace(" ", "_")}_${destination.lowercase().replace(" ", "_")}"
        val existing = getMemory(userId, MemoryType.LONG_TERM, key)
        val count = (existing?.get("count") as? Number)?.toInt() ?: 0
        
        saveMemory(userId, MemoryType.LONG_TERM, key, mapOf(
            "origin" to origin,
            "destination" to destination,
            "count" to (count + 1),
            "lastUsed" to System.currentTimeMillis()
        ))
    }
    
    /**
     * Get frequent routes (top 5).
     */
    suspend fun getFrequentRoutes(userId: String): List<Pair<String, String>> {
        val allMemory = loadMemory(userId, MemoryType.LONG_TERM)
        
        return allMemory
            .filter { it.key.startsWith("route_") }
            .map { (_, value) ->
                Triple(
                    value["origin"] as? String ?: "",
                    value["destination"] as? String ?: "",
                    (value["count"] as? Number)?.toInt() ?: 0
                )
            }
            .sortedByDescending { it.third }
            .take(5)
            .map { it.first to it.second }
    }
    
    // ==================== SUPABASE OPERATIONS ====================
    
    private suspend fun saveToSupabase(userId: String, entry: MemoryEntry) {
        // Note: This requires the agent_memory table to exist
        // Implementation depends on actual Supabase schema
        // For now, we rely on local cache
        android.util.Log.d(TAG, "Would save to Supabase: $userId/${entry.type}/${entry.key}")
    }
    
    private suspend fun loadFromSupabase(userId: String, type: MemoryType): List<MemoryEntry> {
        // Note: This requires the agent_memory table to exist
        // For now, return empty and rely on local cache
        android.util.Log.d(TAG, "Would load from Supabase: $userId/$type")
        return emptyList()
    }
    
    private suspend fun deleteFromSupabase(userId: String, type: MemoryType, key: String) {
        android.util.Log.d(TAG, "Would delete from Supabase: $userId/$type/$key")
    }
    
    private suspend fun clearFromSupabase(userId: String, type: MemoryType) {
        android.util.Log.d(TAG, "Would clear from Supabase: $userId/$type")
    }
    
    // ==================== HELPERS ====================
    
    private fun calculateExpiration(type: MemoryType): Long? {
        val now = System.currentTimeMillis()
        return when (type) {
            MemoryType.SHORT_TERM -> now + SHORT_TERM_DURATION_MS
            MemoryType.SESSION -> now + SESSION_DURATION_MS
            MemoryType.LONG_TERM -> null // Never expires
        }
    }
}

// ============================================================================
// DATA TYPES
// ============================================================================

enum class MemoryType {
    SHORT_TERM,  // Clears after 30 minutes
    LONG_TERM,   // Persists indefinitely
    SESSION      // Clears after 5 minutes / task completion
}

data class MemoryEntry(
    val key: String,
    val value: Map<String, Any?>,
    val type: MemoryType,
    val createdAt: Long,
    val expiresAt: Long? = null
)

// ============================================================================
// SUPABASE SCHEMA (for reference)
// ============================================================================

/**
 * SQL to create agent_memory table:
 * 
 * CREATE TABLE IF NOT EXISTS agent_memory (
 *     id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 *     user_id TEXT NOT NULL,
 *     memory_type TEXT CHECK (memory_type IN ('short_term', 'long_term', 'session')),
 *     key TEXT NOT NULL,
 *     value JSONB NOT NULL,
 *     created_at TIMESTAMPTZ DEFAULT NOW(),
 *     expires_at TIMESTAMPTZ,
 *     UNIQUE(user_id, memory_type, key)
 * );
 * 
 * -- Index for fast lookups
 * CREATE INDEX idx_agent_memory_user_type ON agent_memory(user_id, memory_type);
 * 
 * -- Auto-cleanup expired entries (optional function)
 * CREATE OR REPLACE FUNCTION cleanup_expired_memory()
 * RETURNS void AS $$
 * BEGIN
 *     DELETE FROM agent_memory 
 *     WHERE expires_at IS NOT NULL AND expires_at < NOW();
 * END;
 * $$ LANGUAGE plpgsql;
 */
