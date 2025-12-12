package com.sahara.user.config

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sahara.user.BuildConfig

/**
 * Secure Configuration Manager
 * 
 * Handles secure storage and retrieval of sensitive configuration.
 * 
 * SECURITY FEATURES:
 * - Uses EncryptedSharedPreferences for runtime storage
 * - API keys loaded from BuildConfig (set at build time)
 * - Never exposes keys in logs
 * - Validates keys before use
 */
object SecureConfig {
    
    private const val TAG = "SecureConfig"
    private const val PREFS_NAME = "sahara_secure_prefs"
    
    // ==================== API KEYS (from BuildConfig) ====================
    
    /**
     * Get Perplexity API Key
     * Loaded from local.properties → BuildConfig at compile time
     */
    fun getPerplexityApiKey(): String {
        val key = BuildConfig.PERPLEXITY_API_KEY
        if (!validatePerplexityKey(key)) {
            android.util.Log.w(TAG, "Invalid or missing Perplexity API key")
        }
        return key
    }
    
    /**
     * Get Supabase URL
     */
    fun getSupabaseUrl(): String {
        return BuildConfig.SUPABASE_URL
    }
    
    /**
     * Get Supabase Anon Key
     */
    fun getSupabaseAnonKey(): String {
        return BuildConfig.SUPABASE_ANON_KEY
    }
    
    // ==================== VALIDATION ====================
    
    /**
     * Validate Perplexity API key format
     * Format: pplx-xxxxxxxxx...
     */
    fun validatePerplexityKey(key: String): Boolean {
        return key.isNotBlank() && 
               key.startsWith("pplx-") && 
               key.length > 10
    }
    
    /**
     * Validate Supabase URL format
     */
    fun validateSupabaseUrl(url: String): Boolean {
        return url.isNotBlank() && 
               url.contains("supabase.co") &&
               url.startsWith("https://")
    }
    
    /**
     * Validate all required configuration
     */
    fun validateConfiguration(): ConfigValidation {
        val perplexityValid = validatePerplexityKey(getPerplexityApiKey())
        val supabaseUrlValid = validateSupabaseUrl(getSupabaseUrl())
        val supabaseKeyValid = getSupabaseAnonKey().isNotBlank()
        
        val issues = mutableListOf<String>()
        if (!perplexityValid) issues.add("Perplexity API key missing or invalid")
        if (!supabaseUrlValid) issues.add("Supabase URL missing or invalid")
        if (!supabaseKeyValid) issues.add("Supabase Anon key missing")
        
        return ConfigValidation(
            isValid = issues.isEmpty(),
            issues = issues
        )
    }
    
    // ==================== ENCRYPTED STORAGE ====================
    
    /**
     * Get encrypted shared preferences for secure storage
     */
    fun getEncryptedPrefs(context: Context): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * Store a sensitive value securely
     */
    fun storeSecurely(context: Context, key: String, value: String) {
        getEncryptedPrefs(context).edit().putString(key, value).apply()
    }
    
    /**
     * Retrieve a securely stored value
     */
    fun retrieveSecurely(context: Context, key: String): String? {
        return getEncryptedPrefs(context).getString(key, null)
    }
    
    /**
     * Clear all secured data
     */
    fun clearSecureData(context: Context) {
        getEncryptedPrefs(context).edit().clear().apply()
    }
}

/**
 * Configuration validation result
 */
data class ConfigValidation(
    val isValid: Boolean,
    val issues: List<String>
)
