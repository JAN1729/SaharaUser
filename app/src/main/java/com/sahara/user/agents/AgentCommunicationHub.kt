package com.sahara.user.agents

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent Communication Hub - Enhanced with Full Context Sharing
 * 
 * Central hub for multi-agent communication and coordination.
 * 
 * KEY FEATURES:
 * - Full context sharing between all agents
 * - Shared data store (vehicles, stops, locations)
 * - Algorithm results storage
 * - Agent result analysis
 * - Message routing and priority handling
 * 
 * AGENTS COMMUNICATE, ALGORITHMS CALCULATE:
 * - Agents analyze and interpret algorithm results
 * - Agents delegate tasks to other agents
 * - Agents share insights and findings
 * - All calculations done by algorithm layer
 */
@Singleton
class AgentCommunicationHub @Inject constructor() {
    
    companion object {
        private const val TAG = "AgentCommHub"
    }
    
    // ==================== MESSAGE LOG ====================
    
    private val _messageLog = MutableStateFlow<List<AgentMessage>>(emptyList())
    val messageLog: StateFlow<List<AgentMessage>> = _messageLog.asStateFlow()
    
    // ==================== TASK CONTEXT ====================
    
    private val _taskContext = MutableStateFlow<TaskContext?>(null)
    val taskContext: StateFlow<TaskContext?> = _taskContext.asStateFlow()
    
    // ==================== SHARED DATA STORE ====================
    // All agents can access this data
    
    private val _sharedData = MutableStateFlow<SharedDataStore>(SharedDataStore())
    val sharedData: StateFlow<SharedDataStore> = _sharedData.asStateFlow()
    
    // ==================== ALGORITHM RESULTS ====================
    // Results from calculations stored here for agent analysis
    
    private val _algorithmResults = MutableStateFlow<MutableMap<String, Any>>(mutableMapOf())
    val algorithmResults: StateFlow<Map<String, Any>> = _algorithmResults.asStateFlow()
    
    // ==================== REGISTERED AGENTS ====================
    
    private val registeredAgents = mutableMapOf<String, AgentInfo>()
    
    // ==================== AGENT CALL HISTORY ====================
    // Track which agents called which for proper coordination
    
    private val _agentCallChain = MutableStateFlow<List<AgentCall>>(emptyList())
    val agentCallChain: StateFlow<List<AgentCall>> = _agentCallChain.asStateFlow()
    
    // ==================== REGISTRATION ====================
    
    fun registerAgent(agentName: String, persona: AgentPersona) {
        registeredAgents[agentName] = AgentInfo(
            name = agentName,
            persona = persona,
            isAvailable = true
        )
        android.util.Log.d(TAG, "Registered: $agentName (${persona.role})")
    }
    
    fun getRegisteredAgents(): List<String> = registeredAgents.keys.toList()
    
    fun getAgentPersona(agentName: String): AgentPersona? = 
        registeredAgents[agentName]?.persona
    
    // ==================== TASK MANAGEMENT ====================
    
    fun startNewTask(query: String, userId: String) {
        val taskId = UUID.randomUUID().toString()
        _taskContext.value = TaskContext(
            taskId = taskId,
            originalQuery = query,
            userId = userId,
            startTime = System.currentTimeMillis(),
            agentResults = mutableMapOf()
        )
        _messageLog.value = emptyList()
        _algorithmResults.value = mutableMapOf()
        _agentCallChain.value = emptyList()
        _sharedData.value = SharedDataStore()
        
        android.util.Log.d(TAG, "Task $taskId started: ${query.take(50)}...")
    }
    
    // ==================== SHARED DATA OPERATIONS ====================
    
    /**
     * Store shared data that all agents can access
     */
    fun storeSharedData(
        vehicles: List<Any>? = null,
        stops: List<Any>? = null,
        liveLocations: List<Any>? = null,
        userLocation: Pair<Double, Double>? = null,
        parsedQuery: Map<String, Any>? = null
    ) {
        val current = _sharedData.value
        _sharedData.value = current.copy(
            vehicles = vehicles ?: current.vehicles,
            stops = stops ?: current.stops,
            liveLocations = liveLocations ?: current.liveLocations,
            userLocation = userLocation ?: current.userLocation,
            parsedQuery = parsedQuery ?: current.parsedQuery
        )
        android.util.Log.d(TAG, "Shared data updated")
    }
    
    fun getSharedData(): SharedDataStore = _sharedData.value
    
    // ==================== ALGORITHM RESULTS ====================
    
    /**
     * Store algorithm calculation result for agent analysis
     */
    fun storeAlgorithmResult(algorithmName: String, result: Any) {
        _algorithmResults.value[algorithmName] = result
        android.util.Log.d(TAG, "Algorithm result stored: $algorithmName")
        
        // Broadcast to all agents
        broadcast(
            fromAgent = "AlgorithmEngine",
            messageType = MessageType.RESULT_UPDATE,
            content = mapOf(
                "algorithm" to algorithmName,
                "result_available" to true
            )
        )
    }
    
    fun getAlgorithmResult(algorithmName: String): Any? = 
        _algorithmResults.value[algorithmName]
    
    // ==================== MESSAGING ====================
    
    fun sendMessage(
        fromAgent: String,
        toAgent: String,
        messageType: MessageType,
        content: Map<String, Any>,
        priority: MessagePriority = MessagePriority.NORMAL
    ): String {
        val message = AgentMessage(
            id = UUID.randomUUID().toString(),
            fromAgent = fromAgent,
            toAgent = toAgent,
            type = messageType,
            content = content,
            priority = priority,
            timestamp = System.currentTimeMillis()
        )
        
        _messageLog.value = _messageLog.value + message
        android.util.Log.d(TAG, "$fromAgent → $toAgent: ${messageType.name}")
        return message.id
    }
    
    fun broadcast(
        fromAgent: String,
        messageType: MessageType,
        content: Map<String, Any>
    ) {
        registeredAgents.keys.forEach { agentName ->
            if (agentName != fromAgent) {
                sendMessage(fromAgent, agentName, messageType, content)
            }
        }
    }
    
    fun requestData(
        fromAgent: String,
        toAgent: String,
        dataType: String,
        parameters: Map<String, Any> = emptyMap()
    ): String {
        return sendMessage(
            fromAgent = fromAgent,
            toAgent = toAgent,
            messageType = MessageType.DATA_REQUEST,
            content = mapOf(
                "data_type" to dataType,
                "parameters" to parameters
            ),
            priority = MessagePriority.HIGH
        )
    }
    
    // ==================== AGENT RESULTS ====================
    
    fun storeResult(agentName: String, result: AgentResult) {
        _taskContext.value?.let { context ->
            context.agentResults[agentName] = result
            android.util.Log.d(TAG, "Result from $agentName: success=${result.success}")
        }
    }
    
    fun getAgentResult(agentName: String): AgentResult? {
        return _taskContext.value?.agentResults?.get(agentName)
    }
    
    fun getAllResults(): Map<String, AgentResult> {
        return _taskContext.value?.agentResults?.toMap() ?: emptyMap()
    }
    
    // ==================== AGENT CALL TRACKING ====================
    
    /**
     * Record an agent being called - for coordination analysis
     */
    fun recordAgentCall(
        calledBy: String,
        agentName: String,
        purpose: String,
        inputSummary: String
    ) {
        val call = AgentCall(
            id = UUID.randomUUID().toString(),
            calledBy = calledBy,
            agentName = agentName,
            purpose = purpose,
            inputSummary = inputSummary,
            timestamp = System.currentTimeMillis(),
            status = AgentCallStatus.IN_PROGRESS
        )
        _agentCallChain.value = _agentCallChain.value + call
        android.util.Log.d(TAG, "Agent call: $calledBy → $agentName ($purpose)")
    }
    
    /**
     * Mark agent call as completed
     */
    fun completeAgentCall(agentName: String, success: Boolean, resultSummary: String) {
        _agentCallChain.value = _agentCallChain.value.map { call ->
            if (call.agentName == agentName && call.status == AgentCallStatus.IN_PROGRESS) {
                call.copy(
                    status = if (success) AgentCallStatus.COMPLETED else AgentCallStatus.FAILED,
                    resultSummary = resultSummary,
                    completedAt = System.currentTimeMillis()
                )
            } else call
        }
    }
    
    /**
     * Get the call chain for analysis
     */
    fun getAgentCallChain(): List<AgentCall> = _agentCallChain.value
    
    // ==================== AGENT RECOMMENDATION ====================
    
    /**
     * Suggest next agent to call based on current context
     */
    fun suggestNextAgent(currentAgent: String): String? {
        val currentPersona = registeredAgents[currentAgent]?.persona ?: return null
        val delegateOptions = currentPersona.canDelegateTo
        
        // Find which delegates haven't been called yet
        val calledAgents = _agentCallChain.value
            .filter { it.status == AgentCallStatus.COMPLETED }
            .map { it.agentName }
            .toSet()
        
        return delegateOptions.firstOrNull { it !in calledAgents }
    }
    
    /**
     * Check if an agent should be called based on context
     */
    fun shouldCallAgent(agentName: String, context: Map<String, Any>): Boolean {
        val persona = registeredAgents[agentName]?.persona ?: return false
        
        // Check if agent's capabilities match the current need
        return when (agentName) {
            "PathAgent" -> context.containsKey("origin") && context.containsKey("destination")
            "BusTrackerAgent" -> context.containsKey("bus_id") || context.containsKey("need_live_data")
            "TimeAgent" -> context.containsKey("distance") || context.containsKey("route")
            "TransferAgent" -> context["no_direct_route"] == true
            "UserContextAgent" -> context.containsKey("user_id")
            else -> true
        }
    }
    
    // ==================== TASK COMPLETION ====================
    
    fun getCommunicationSummary(): String {
        val messages = _messageLog.value
        val calls = _agentCallChain.value
        
        return buildString {
            appendLine("=== Multi-Agent Coordination Summary ===")
            appendLine()
            appendLine("Agent Calls (${calls.size}):")
            calls.forEach { call ->
                appendLine("  ${call.calledBy} → ${call.agentName}: ${call.purpose} [${call.status}]")
            }
            appendLine()
            appendLine("Messages (${messages.size}):")
            messages.take(10).forEach { msg ->
                appendLine("  ${msg.fromAgent} → ${msg.toAgent}: ${msg.type}")
            }
            if (messages.size > 10) {
                appendLine("  ... and ${messages.size - 10} more")
            }
        }
    }
    
    fun endTask(): TaskSummary {
        val context = _taskContext.value ?: return TaskSummary(
            taskId = "",
            totalDurationMs = 0,
            agentsCalled = emptyList(),
            messagesExchanged = 0,
            success = false
        )
        
        val duration = System.currentTimeMillis() - context.startTime
        val agentsCalled = _agentCallChain.value.map { it.agentName }.distinct()
        
        val summary = TaskSummary(
            taskId = context.taskId,
            totalDurationMs = duration,
            agentsCalled = agentsCalled,
            messagesExchanged = _messageLog.value.size,
            success = context.agentResults.values.any { it.success }
        )
        
        android.util.Log.d(TAG, "Task completed in ${duration}ms. Agents: $agentsCalled")
        return summary
    }
}

// ==================== Data Classes ====================

data class AgentMessage(
    val id: String,
    val fromAgent: String,
    val toAgent: String,
    val type: MessageType,
    val content: Map<String, Any>,
    val priority: MessagePriority,
    val timestamp: Long
)

enum class MessageType {
    DATA_REQUEST,
    DATA_RESPONSE,
    TASK_DELEGATION,
    RESULT_UPDATE,
    STATUS_UPDATE,
    ERROR_REPORT,
    CLARIFICATION_NEEDED,
    ALGORITHM_RESULT,      // New: algorithm completed
    ANALYSIS_REQUEST,      // New: request analysis
    RECOMMENDATION         // New: suggest next action
}

enum class MessagePriority {
    LOW, NORMAL, HIGH, CRITICAL
}

data class AgentPersona(
    val role: String,
    val capabilities: List<String>,
    val dataAccess: List<String>,
    val canDelegateTo: List<String>
)

data class AgentInfo(
    val name: String,
    val persona: AgentPersona,
    val isAvailable: Boolean
)

/**
 * Shared data store accessible by all agents
 */
data class SharedDataStore(
    val vehicles: List<Any> = emptyList(),
    val stops: List<Any> = emptyList(),
    val liveLocations: List<Any> = emptyList(),
    val userLocation: Pair<Double, Double>? = null,
    val parsedQuery: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Agent call tracking
 */
data class AgentCall(
    val id: String,
    val calledBy: String,
    val agentName: String,
    val purpose: String,
    val inputSummary: String,
    val timestamp: Long,
    val status: AgentCallStatus,
    val resultSummary: String? = null,
    val completedAt: Long? = null
)

enum class AgentCallStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED
}

data class TaskContext(
    val taskId: String,
    val originalQuery: String,
    val userId: String,
    val startTime: Long,
    val agentResults: MutableMap<String, AgentResult>
)

data class TaskSummary(
    val taskId: String,
    val totalDurationMs: Long,
    val agentsCalled: List<String>,
    val messagesExchanged: Int,
    val success: Boolean
)
