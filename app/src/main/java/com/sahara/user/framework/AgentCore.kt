package com.sahara.user.framework

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * SaharaUser Multi-Agent Framework
 * 
 * Production-ready, Kotlin-native agent framework inspired by:
 * - Google ADK (Agent Development Kit)
 * - JetBrains Koog
 * - LangChain patterns
 * 
 * KEY PRINCIPLES:
 * 1. Agents ANALYZE, Algorithms CALCULATE
 * 2. Tools are first-class citizens
 * 3. Parallel execution when possible
 * 4. Full observability and tracing
 * 5. Supabase-backed persistence
 */

// ============================================================================
// CORE INTERFACES
// ============================================================================

/**
 * Base interface for all agents in the system.
 * 
 * Agents are autonomous units that can:
 * - Execute tasks with tool access
 * - Communicate with other agents
 * - Store and retrieve memory
 * - Report their progress
 */
interface Agent {
    /** Unique identifier for this agent type */
    val name: String
    
    /** Human-readable description of what this agent does */
    val description: String
    
    /** List of tools this agent can use */
    val tools: List<Tool>
    
    /** System prompt that defines agent behavior */
    val systemPrompt: String
    
    /**
     * Execute the agent's main task.
     * 
     * @param context Shared execution context with data and memory
     * @param input Input data for this execution
     * @return Result of the execution
     */
    suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput
    
    /**
     * Called before agent starts execution.
     * Use for initialization, fetching required data, etc.
     */
    suspend fun onStart(context: AgentContext) {}
    
    /**
     * Called after agent completes execution.
     * Use for cleanup, storing results, etc.
     */
    suspend fun onComplete(context: AgentContext, output: AgentOutput) {}
}

/**
 * A Tool is a function that an agent can call to interact with external systems.
 * 
 * Tools are the primary way agents interact with:
 * - Databases (Supabase)
 * - APIs (external services)
 * - Algorithms (route matching, ETA calculation)
 * - LLMs (Perplexity, Gemini)
 */
interface Tool {
    /** Unique identifier for this tool */
    val name: String
    
    /** Detailed description for LLM to understand when to use this tool */
    val description: String
    
    /** JSON schema describing the input parameters */
    val inputSchema: ToolSchema
    
    /** JSON schema describing the output format */
    val outputSchema: ToolSchema
    
    /**
     * Execute the tool with given parameters.
     * 
     * @param params Input parameters as key-value map
     * @param context Agent context for accessing shared resources
     * @return Result of tool execution
     */
    suspend fun execute(params: Map<String, Any?>, context: AgentContext): ToolResult
}

/**
 * Schema definition for tool inputs/outputs.
 * Used for validation and LLM function calling.
 */
@Serializable
data class ToolSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList()
)

@Serializable
data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val default: String? = null
)

// ============================================================================
// EXECUTION CONTEXT
// ============================================================================

/**
 * Shared context for agent execution.
 * 
 * Contains:
 * - Task information (query, user, etc.)
 * - Shared data between agents
 * - Memory access
 * - Execution trace for observability
 */
class AgentContext(
    val taskId: String = UUID.randomUUID().toString(),
    val userId: String,
    val originalQuery: String,
    val userLocation: Pair<Double, Double>? = null
) {
    private val _sharedData = mutableMapOf<String, Any?>()
    private val _agentResults = mutableMapOf<String, AgentOutput>()
    private val _toolResults = mutableMapOf<String, ToolResult>()
    private val _executionTrace = mutableListOf<TraceEvent>()
    
    // Observable state for UI
    private val _currentAgent = MutableStateFlow<String?>(null)
    val currentAgent: StateFlow<String?> = _currentAgent.asStateFlow()
    
    private val _status = MutableStateFlow<String>("Initializing...")
    val status: StateFlow<String> = _status.asStateFlow()
    
    val startTime: Long = System.currentTimeMillis()
    
    // ==================== SHARED DATA ====================
    
    fun setData(key: String, value: Any?) {
        _sharedData[key] = value
        trace(TraceEvent.Type.DATA_STORED, "Shared data: $key")
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T> getData(key: String): T? = _sharedData[key] as? T
    
    fun hasData(key: String): Boolean = _sharedData.containsKey(key)
    
    // ==================== AGENT RESULTS ====================
    
    fun storeAgentResult(agentName: String, output: AgentOutput) {
        _agentResults[agentName] = output
        trace(TraceEvent.Type.AGENT_COMPLETED, "$agentName completed: ${output.success}")
    }
    
    fun getAgentResult(agentName: String): AgentOutput? = _agentResults[agentName]
    
    fun getAllAgentResults(): Map<String, AgentOutput> = _agentResults.toMap()
    
    // ==================== TOOL RESULTS ====================
    
    fun storeToolResult(toolName: String, result: ToolResult) {
        _toolResults[toolName] = result
        trace(TraceEvent.Type.TOOL_COMPLETED, "$toolName: ${result.success}")
    }
    
    fun getToolResult(toolName: String): ToolResult? = _toolResults[toolName]
    
    // ==================== STATUS & TRACING ====================
    
    fun updateStatus(status: String) {
        _status.value = status
        trace(TraceEvent.Type.STATUS_UPDATE, status)
    }
    
    fun setCurrentAgent(agentName: String?) {
        _currentAgent.value = agentName
        if (agentName != null) {
            trace(TraceEvent.Type.AGENT_STARTED, "Starting $agentName")
        }
    }
    
    fun trace(type: TraceEvent.Type, message: String, data: Map<String, Any?>? = null) {
        val event = TraceEvent(
            type = type,
            message = message,
            data = data,
            timestamp = System.currentTimeMillis()
        )
        _executionTrace.add(event)
        android.util.Log.d("AgentContext", "[${type.name}] $message")
    }
    
    fun getExecutionTrace(): List<TraceEvent> = _executionTrace.toList()
    
    fun getExecutionDuration(): Long = System.currentTimeMillis() - startTime
}

/**
 * Trace event for observability
 */
data class TraceEvent(
    val type: Type,
    val message: String,
    val data: Map<String, Any?>? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Type {
        AGENT_STARTED,
        AGENT_COMPLETED,
        TOOL_STARTED,
        TOOL_COMPLETED,
        DATA_STORED,
        ERROR,
        STATUS_UPDATE,
        LLM_CALL,
        PARALLEL_START,
        PARALLEL_END
    }
}

// ============================================================================
// INPUT / OUTPUT TYPES
// ============================================================================

/**
 * Input to an agent execution
 */
data class AgentInput(
    val intent: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val previousResults: Map<String, AgentOutput> = emptyMap(),
    val metadata: Map<String, Any?> = emptyMap()
)

/**
 * Output from an agent execution
 */
data class AgentOutput(
    val success: Boolean,
    val agentName: String,
    val data: Map<String, Any?> = emptyMap(),
    val message: String? = null,
    val error: String? = null,
    val confidence: Float = 1.0f,
    val suggestedNextAgent: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val executionTimeMs: Long = 0
) {
    companion object {
        fun success(
            agentName: String,
            data: Map<String, Any?> = emptyMap(),
            message: String? = null,
            confidence: Float = 1.0f,
            suggestedNextAgent: String? = null,
            toolsUsed: List<String> = emptyList()
        ) = AgentOutput(
            success = true,
            agentName = agentName,
            data = data,
            message = message,
            confidence = confidence,
            suggestedNextAgent = suggestedNextAgent,
            toolsUsed = toolsUsed
        )
        
        fun failure(
            agentName: String,
            error: String,
            data: Map<String, Any?> = emptyMap()
        ) = AgentOutput(
            success = false,
            agentName = agentName,
            error = error,
            data = data,
            confidence = 0f
        )
    }
}

/**
 * Result from a tool execution
 */
data class ToolResult(
    val success: Boolean,
    val toolName: String,
    val data: Map<String, Any?> = emptyMap(),
    val error: String? = null,
    val executionTimeMs: Long = 0
) {
    companion object {
        fun success(toolName: String, data: Map<String, Any?>) =
            ToolResult(success = true, toolName = toolName, data = data)
        
        fun failure(toolName: String, error: String) =
            ToolResult(success = false, toolName = toolName, error = error)
    }
}

// ============================================================================
// AGENT ERROR TYPES
// ============================================================================

sealed class AgentException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ToolExecutionFailed(toolName: String, reason: String) : 
        AgentException("Tool '$toolName' failed: $reason")
    
    class AgentExecutionFailed(agentName: String, reason: String) : 
        AgentException("Agent '$agentName' failed: $reason")
    
    class ContextMissingData(key: String) : 
        AgentException("Required context data missing: $key")
    
    class MaxRetriesExceeded(attempts: Int) : 
        AgentException("Max retries exceeded after $attempts attempts")
    
    class TimeoutException(durationMs: Long) : 
        AgentException("Operation timed out after ${durationMs}ms")
}
