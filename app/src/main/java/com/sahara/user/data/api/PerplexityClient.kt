package com.sahara.user.data.api

import com.sahara.user.config.SecureConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Perplexity API Client
 * 
 * SECURITY:
 * - API key loaded from SecureConfig (BuildConfig)
 * - Never logs API key
 * - Validates key before use
 * - Uses HTTPS only
 */
@Singleton
class PerplexityClient @Inject constructor() {
    
    companion object {
        private const val TAG = "PerplexityClient"
        private const val BASE_URL = "https://api.perplexity.ai"
        private const val MODEL = "sonar-pro"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }
    
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    // SECURITY: Filter out API keys from logs
                    val safeMessage = message
                        .replace(Regex("pplx-[a-zA-Z0-9]+"), "pplx-***REDACTED***")
                        .replace(Regex("Bearer [a-zA-Z0-9-]+"), "Bearer ***REDACTED***")
                    android.util.Log.d(TAG, safeMessage)
                }
            }
            level = LogLevel.BODY
        }
        
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
        
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }
    
    /**
     * Send a chat message to Perplexity
     */
    suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        temperature: Float = 0.7f
    ): Result<String> {
        // Validate API key
        val apiKey = SecureConfig.getPerplexityApiKey()
        if (!SecureConfig.validatePerplexityKey(apiKey)) {
            return Result.failure(Exception("Invalid or missing Perplexity API key. Check local.properties."))
        }
        
        return try {
            val response = client.post("$BASE_URL/chat/completions") {
                header("Authorization", "Bearer $apiKey")
                setBody(PerplexityChatRequest(
                    model = MODEL,
                    messages = listOf(
                        PerplexityMessage(role = "system", content = systemPrompt),
                        PerplexityMessage(role = "user", content = userMessage)
                    ),
                    temperature = temperature
                ))
            }
            
            val chatResponse = response.body<PerplexityChatResponse>()
            val content = chatResponse.choices.firstOrNull()?.message?.content
                ?: return Result.failure(Exception("Empty response"))
            
            Result.success(content)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "API error: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Send a chat and get JSON response
     */
    suspend fun chatJson(
        systemPrompt: String,
        userMessage: String
    ): Result<String> {
        val enhancedPrompt = """
            $systemPrompt
            
            IMPORTANT: Output ONLY valid JSON, no markdown, no code blocks.
        """.trimIndent()
        
        return chat(enhancedPrompt, userMessage, temperature = 0.3f)
    }
}

// ==================== Request/Response Models ====================

@Serializable
data class PerplexityChatRequest(
    val model: String,
    val messages: List<PerplexityMessage>,
    val temperature: Float = 0.7f,
    @SerialName("max_tokens") val maxTokens: Int = 2000
)

@Serializable
data class PerplexityMessage(
    val role: String,
    val content: String
)

@Serializable
data class PerplexityChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<PerplexityChoice> = emptyList()
)

@Serializable
data class PerplexityChoice(
    val index: Int = 0,
    val message: PerplexityMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)
