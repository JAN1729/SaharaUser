package com.sahara.user.data.api

import com.sahara.user.BuildConfig
import android.content.Context
import android.content.SharedPreferences
import com.sahara.user.config.SecureConfig
import java.security.MessageDigest
import java.util.UUID

object CommuteBackend {
    private var preferences: SharedPreferences? = null
    fun initialize(context: Context) { preferences = SecureConfig.getEncryptedPrefs(context) }
    val enabled: Boolean get() = url.isNotBlank()
    val url: String get() {
        val configured = preferences?.getString("server_url", null)
        if (configured != null && configured.contains("12.0.0.1")) {
            return configured.replace("12.0.0.1", "127.0.0.1")
        }
        return configured ?: BuildConfig.COMMUTE_BACKEND_URL.trimEnd('/')
    }
    val key: String get() = preferences?.getString("server_key", null) ?: BuildConfig.COMMUTE_API_KEY
    val dataMode: String get() = preferences?.getString("server_mode", null) ?: BuildConfig.COMMUTE_DATA_MODE

    fun save(address: String, accessKey: String, mode: String) {
        val normalized = normalizeServerAddress(address, BuildConfig.DEBUG)
        require(mode in listOf("sample", "live")) { "Choose demo or live data" }
        require(mode != "live" || accessKey.isNotBlank()) { "Enter your server access key for live searches" }
        checkNotNull(preferences).edit().putString("server_url", normalized)
            .putString("server_key", accessKey.trim()).putString("server_mode", mode).apply()
    }

    @Synchronized fun session(): String {
        val name = "server_session_" + MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val prefs = checkNotNull(preferences)
        return prefs.getString(name, null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString(name, it).apply()
        }
    }
}
