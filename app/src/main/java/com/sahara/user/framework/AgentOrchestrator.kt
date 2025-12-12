package com.sahara.user.framework

import com.sahara.user.compute.QueryParser
import com.sahara.user.data.api.PerplexityClient
import com.sahara.user.data.api.SupabaseApiClient
import com.sahara.user.service.LocationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main Agent Orchestrator
 * 
 * The central brain that coordinates the entire multi-agent system.
 * 
 * RESPONSIBILITIES:
 * 1. Parse user query into structured intent
 * 2. Select appropriate agents based on intent
 * 3. Build execution plan (sequential, parallel, conditional)
 * 4. Execute the plan with full observability
 * 5. Aggregate results and generate response
 * 
 * ARCHITECTURE:
 * - Uses workflow agents for orchestration patterns
 * - All agents access shared context
 * - Tools handle all external interactions
 * - Full tracing for debugging
 */
@Singleton
class AgentOrchestrator @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val agentMemory: AgentMemory,
    private val perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient,
    private val locationService: LocationService
) {
    
    companion object {
        private const val TAG = "AgentOrchestrator"
    }
    
    // Registered specialist agents
    private val agents = mutableMapOf<String, Agent>()
    
    // Observable state
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private val _currentContext = MutableStateFlow<AgentContext?>(null)
    val currentContext: StateFlow<AgentContext?> = _currentContext.asStateFlow()
    
    private val _thinkingSteps = MutableStateFlow<List<ThinkingStep>>(emptyList())
    val thinkingSteps: StateFlow<List<ThinkingStep>> = _thinkingSteps.asStateFlow()
    
    // ==================== AGENT REGISTRATION ====================
    
    /**
     * Register a specialist agent with the orchestrator.
     */
    fun registerAgent(agent: Agent) {
        agents[agent.name] = agent
        android.util.Log.d(TAG, "Registered agent: ${agent.name}")
    }
    
    /**
     * Register multiple agents at once.
     */
    fun registerAgents(vararg agentsToRegister: Agent) {
        agentsToRegister.forEach { registerAgent(it) }
    }
    
    /**
     * Get a registered agent by name.
     */
    fun getAgent(name: String): Agent? = agents[name]
    
    /**
     * Get all registered agent names.
     */
    fun getAgentNames(): List<String> = agents.keys.toList()
    
    // ==================== MAIN PROCESSING ====================
    
    /**
     * Process a user query through the multi-agent system.
     * 
     * @param userId User identifier
     * @param query Natural language query
     * @return Orchestration result with response
     */
    suspend fun processQuery(userId: String, query: String): OrchestratorOutput {
        android.util.Log.d(TAG, "=== Processing Query ===")
        android.util.Log.d(TAG, "Query: ${query.take(50)}...")
        
        _isProcessing.value = true
        _thinkingSteps.value = emptyList()
        
        try {
            // Step 1: Create execution context
            addThinkingStep("Understanding your request...", ThinkingStepStatus.IN_PROGRESS)
            
            val userLocation = locationService.getCurrentLocation()
            val context = AgentContext(
                userId = userId,
                originalQuery = query,
                userLocation = userLocation?.let { it.latitude to it.longitude }
            )
            _currentContext.value = context
            
            // Step 2: Parse query into structured form
            val structuredQuery = QueryParser.parse(
                query = query,
                userLat = userLocation?.latitude,
                userLng = userLocation?.longitude
            )
            
            context.setData("parsed_query", structuredQuery)
            context.setData("intent", structuredQuery.intent.name)
            context.setData("confidence", structuredQuery.confidence)
            context.setData("origin", structuredQuery.parameters.origin)
            context.setData("destination", structuredQuery.parameters.destination)
            context.setData("bus_id", structuredQuery.parameters.busId)
            context.setData("stop_name", structuredQuery.parameters.stopName)
            
            updateThinkingStep(0, "Understood: ${structuredQuery.intent.name}", ThinkingStepStatus.COMPLETED)
            
            android.util.Log.d(TAG, "Intent: ${structuredQuery.intent}, Confidence: ${structuredQuery.confidence}")
            
            // Step 3: Handle low confidence
            if (structuredQuery.confidence < 0.5) {
                return handleClarificationNeeded(context, query, structuredQuery)
            }
            
            // Step 4: Load user memory/context
            addThinkingStep("Loading your preferences...", ThinkingStepStatus.IN_PROGRESS)
            val userMemory = agentMemory.loadMemory(userId, MemoryType.LONG_TERM)
            context.setData("user_memory", userMemory)
            updateThinkingStep(1, "Preferences loaded", ThinkingStepStatus.COMPLETED)
            
            // Step 5: Select and build execution plan
            addThinkingStep("Planning agent execution...", ThinkingStepStatus.IN_PROGRESS)
            val executionPlan = buildExecutionPlan(context, structuredQuery)
            updateThinkingStep(2, "Plan ready: ${executionPlan.description}", ThinkingStepStatus.COMPLETED)
            
            // Step 6: Execute the plan
            addThinkingStep("Executing agents...", ThinkingStepStatus.IN_PROGRESS)
            val input = AgentInput(
                intent = structuredQuery.intent.name,
                parameters = mapOf(
                    "origin" to structuredQuery.parameters.origin,
                    "destination" to structuredQuery.parameters.destination,
                    "bus_id" to structuredQuery.parameters.busId,
                    "stop_name" to structuredQuery.parameters.stopName,
                    "user_lat" to userLocation?.latitude,
                    "user_lng" to userLocation?.longitude
                )
            )
            
            val output = executionPlan.agent.execute(context, input)
            updateThinkingStep(3, "Agents completed", ThinkingStepStatus.COMPLETED)
            
            // Step 7: Generate response
            addThinkingStep("Generating response...", ThinkingStepStatus.IN_PROGRESS)
            val response = generateResponse(context, structuredQuery.intent, output)
            updateThinkingStep(4, "Done!", ThinkingStepStatus.COMPLETED)
            
            // Step 8: Save to memory
            agentMemory.saveMemory(
                userId = userId,
                type = MemoryType.SHORT_TERM,
                key = "last_query",
                value = mapOf(
                    "query" to query,
                    "intent" to structuredQuery.intent.name,
                    "response" to response
                )
            )
            
            // Save search history
            if (structuredQuery.parameters.origin != null || structuredQuery.parameters.destination != null) {
                supabaseClient.saveSearchHistory(
                    userId = userId,
                    query = query,
                    origin = structuredQuery.parameters.origin,
                    destination = structuredQuery.parameters.destination
                )
            }
            
            return OrchestratorOutput(
                query = query,
                success = output.success,
                response = response,
                intent = structuredQuery.intent.name,
                agentsCalled = context.getAllAgentResults().keys.toList(),
                toolsUsed = output.toolsUsed,
                confidence = output.confidence,
                executionTimeMs = context.getExecutionDuration(),
                trace = context.getExecutionTrace()
            )
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error processing query: ${e.message}", e)
            _thinkingSteps.value = _thinkingSteps.value.mapIndexed { index, step ->
                if (step.status == ThinkingStepStatus.IN_PROGRESS) {
                    step.copy(status = ThinkingStepStatus.FAILED)
                } else step
            }
            
            return OrchestratorOutput(
                query = query,
                success = false,
                response = "Something went wrong. Please try again.",
                intent = "error",
                agentsCalled = emptyList(),
                toolsUsed = emptyList(),
                confidence = 0f,
                executionTimeMs = 0,
                trace = emptyList(),
                error = e.message
            )
            
        } finally {
            _isProcessing.value = false
        }
    }
    
    // ==================== EXECUTION PLAN ====================
    
    private data class ExecutionPlan(
        val agent: Agent,
        val description: String
    )
    
    /**
     * Build the execution plan based on intent.
     * Selects and composes appropriate agents.
     */
    private fun buildExecutionPlan(
        context: AgentContext,
        query: QueryParser.StructuredQuery
    ): ExecutionPlan {
        // Get required agents for this intent
        val requiredAgents = query.requiredAgents.mapNotNull { agents[it] }
        
        if (requiredAgents.isEmpty()) {
            // Fallback to a simple response
            return ExecutionPlan(
                agent = SimpleResponseAgent(perplexityClient),
                description = "Direct response"
            )
        }
        
        return when (query.intent) {
            QueryParser.QueryIntent.FIND_ROUTE, 
            QueryParser.QueryIntent.FIND_BEST_OPTION -> {
                // Sequential: Data → Path → Time → BusTracker
                // With fallback to TransferAgent if direct route fails
                val dataAgent = agents["DataAgent"]
                val pathAgent = agents["PathAgent"]
                val timeAgent = agents["TimeAgent"]
                val busTrackerAgent = agents["BusTrackerAgent"]
                val transferAgent = agents["TransferAgent"]
                
                val mainSequence = mutableListOf<Agent>()
                dataAgent?.let { mainSequence.add(it) }
                pathAgent?.let { mainSequence.add(it) }
                
                // Time and BusTracker can run in parallel after path
                val parallelAgents = mutableListOf<Agent>()
                timeAgent?.let { parallelAgents.add(it) }
                busTrackerAgent?.let { parallelAgents.add(it) }
                
                if (parallelAgents.isNotEmpty()) {
                    mainSequence.add(ParallelAgent(
                        name = "TimeAndTracker",
                        description = "ETA and bus position",
                        agents = parallelAgents
                    ))
                }
                
                val sequentialPlan = SequentialAgent(
                    name = "RouteSearch",
                    description = "Find route from origin to destination",
                    agents = mainSequence,
                    stopOnFailure = false
                )
                
                // Add transfer fallback if available
                val finalPlan = if (transferAgent != null) {
                    ConditionalAgent(
                        name = "RouteWithTransfer",
                        description = "Route with transfer fallback",
                        condition = { ctx, _ ->
                            // Check if path agent found a direct route
                            val pathResult = ctx.getAgentResult("PathAgent")
                            if (pathResult?.success == true) "direct" else "transfer"
                        },
                        branches = mapOf(
                            "direct" to sequentialPlan,
                            "transfer" to SequentialAgent(
                                name = "TransferRoute",
                                agents = listOfNotNull(dataAgent, transferAgent)
                            )
                        ),
                        defaultBranch = sequentialPlan
                    )
                } else {
                    sequentialPlan
                }
                
                ExecutionPlan(finalPlan, "Route search with parallel ETA/tracking")
            }
            
            QueryParser.QueryIntent.TRACK_BUS -> {
                val busTrackerAgent = agents["BusTrackerAgent"]
                    ?: return ExecutionPlan(SimpleResponseAgent(perplexityClient), "Direct response")
                
                ExecutionPlan(
                    agent = LoopAgent(
                        name = "BusTrackingWithRetry",
                        agent = busTrackerAgent,
                        maxAttempts = 2,
                        delayBetweenAttempts = 500L
                    ),
                    description = "Track bus with retry"
                )
            }
            
            QueryParser.QueryIntent.CALCULATE_ETA -> {
                val timeAgent = agents["TimeAgent"]
                val busTrackerAgent = agents["BusTrackerAgent"]
                
                val parallelAgents = listOfNotNull(timeAgent, busTrackerAgent)
                if (parallelAgents.isEmpty()) {
                    return ExecutionPlan(SimpleResponseAgent(perplexityClient), "Direct response")
                }
                
                ExecutionPlan(
                    agent = ParallelAgent(
                        name = "ETACalculation",
                        agents = parallelAgents
                    ),
                    description = "Calculate ETA with live tracking"
                )
            }
            
            QueryParser.QueryIntent.FIND_NEARBY -> {
                val dataAgent = agents["DataAgent"]
                    ?: return ExecutionPlan(SimpleResponseAgent(perplexityClient), "Direct response")
                
                ExecutionPlan(agent = dataAgent, description = "Find nearby stops")
            }
            
            else -> {
                ExecutionPlan(
                    agent = SimpleResponseAgent(perplexityClient),
                    description = "General response"
                )
            }
        }
    }
    
    // ==================== RESPONSE GENERATION ====================
    
    /**
     * Generate a user-friendly response from agent outputs.
     */
    private fun generateResponse(
        context: AgentContext,
        intent: QueryParser.QueryIntent,
        output: AgentOutput
    ): String {
        if (!output.success) {
            return output.error ?: "I couldn't process that request. Please try again."
        }
        
        return when (intent) {
            QueryParser.QueryIntent.FIND_ROUTE, 
            QueryParser.QueryIntent.FIND_BEST_OPTION -> {
                generateRouteResponse(context, output)
            }
            
            QueryParser.QueryIntent.TRACK_BUS -> {
                generateBusTrackingResponse(context, output)
            }
            
            QueryParser.QueryIntent.CALCULATE_ETA -> {
                generateETAResponse(context, output)
            }
            
            QueryParser.QueryIntent.FIND_NEARBY -> {
                generateNearbyResponse(context, output)
            }
            
            else -> {
                output.message ?: "I can help you with routes, bus tracking, or ETAs."
            }
        }
    }
    
    private fun generateRouteResponse(context: AgentContext, output: AgentOutput): String {
        val pathResult = context.getAgentResult("PathAgent")
        val route = pathResult?.data?.get("best_route") as? Map<*, *>
        
        if (route == null) {
            // Check for transfer
            val transferResult = context.getAgentResult("TransferAgent")
            if (transferResult?.success == true) {
                return transferResult.message ?: "Transfer route available."
            }
            return "No route found between those stops."
        }
        
        val vehicleName = route["vehicle_name"] ?: "Bus"
        val vehicleNum = route["vehicle_number"] ?: ""
        val distanceKm = route["distance_km"] ?: "?"
        val etaMin = route["eta_minutes"] ?: "?"
        val stopsAway = route["bus_stops_away"] as? Int
        val isLive = route["is_live"] == true
        
        return buildString {
            append("🚌 **$vehicleName")
            if (vehicleNum.toString().isNotBlank()) append(" ($vehicleNum)")
            append("** • $distanceKm km • ~$etaMin min")
            if (isLive && stopsAway != null && stopsAway >= 0) {
                append(" • Bus is $stopsAway stops away")
            }
        }
    }
    
    private fun generateBusTrackingResponse(context: AgentContext, output: AgentOutput): String {
        val busName = output.data["bus_name"] ?: "Bus"
        val nearestStop = output.data["nearest_stop"] ?: "Unknown"
        val isMoving = output.data["is_moving"] == true
        val freshness = output.data["freshness"] as? String ?: "stale"
        
        val status = if (isMoving) "moving" else "stopped"
        val note = if (freshness == "stale") " (last update >10min ago)" else ""
        
        return "📍 **$busName** is $status near **$nearestStop**$note"
    }
    
    private fun generateETAResponse(context: AgentContext, output: AgentOutput): String {
        val timeResult = context.getAgentResult("TimeAgent")
        val arrivalMin = timeResult?.data?.get("arrival_minutes") ?: output.data["eta_minutes"]
        val busName = output.data["bus_name"] ?: "Bus"
        
        return if (arrivalMin != null) {
            "⏱️ **$busName** arriving in ~$arrivalMin min"
        } else {
            "Could not calculate ETA."
        }
    }
    
    private fun generateNearbyResponse(context: AgentContext, output: AgentOutput): String {
        @Suppress("UNCHECKED_CAST")
        val stops = output.data["stops"] as? List<Map<String, Any?>> ?: return "No nearby stops found."
        
        if (stops.isEmpty()) {
            return "No bus stops within 1km of your location."
        }
        
        val stopNames = stops.take(3).mapNotNull { it["name"]?.toString() }
        return "📍 Nearby: ${stopNames.joinToString(", ")}"
    }
    
    // ==================== CLARIFICATION ====================
    
    private fun handleClarificationNeeded(
        context: AgentContext,
        query: String,
        structuredQuery: QueryParser.StructuredQuery
    ): OrchestratorOutput {
        val message = when {
            query.lowercase().contains("hi") || query.lowercase().contains("hello") ->
                "Hello! I can help you find bus routes, track buses, or get ETAs."
            query.lowercase().contains("thank") ->
                "You're welcome! 🚌"
            query.lowercase().contains("help") ->
                "Ask me: \"Route from A to B\", \"Where is bus X?\", or \"Next bus to Y?\""
            else ->
                "Could you be more specific? I can help with routes, bus locations, or arrival times."
        }
        
        return OrchestratorOutput(
            query = query,
            success = true,
            response = message,
            intent = "clarification",
            agentsCalled = emptyList(),
            toolsUsed = emptyList(),
            confidence = 0.5f,
            executionTimeMs = context.getExecutionDuration(),
            trace = context.getExecutionTrace()
        )
    }
    
    // ==================== UI HELPERS ====================
    
    private fun addThinkingStep(message: String, status: ThinkingStepStatus) {
        _thinkingSteps.value = _thinkingSteps.value + ThinkingStep(
            message = message,
            status = status,
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun updateThinkingStep(index: Int, message: String, status: ThinkingStepStatus) {
        _thinkingSteps.value = _thinkingSteps.value.mapIndexed { i, step ->
            if (i == index) step.copy(message = message, status = status) else step
        }
    }
}

// ============================================================================
// OUTPUT TYPES
// ============================================================================

data class OrchestratorOutput(
    val query: String,
    val success: Boolean,
    val response: String,
    val intent: String,
    val agentsCalled: List<String>,
    val toolsUsed: List<String>,
    val confidence: Float,
    val executionTimeMs: Long,
    val trace: List<TraceEvent>,
    val error: String? = null
)

data class ThinkingStep(
    val message: String,
    val status: ThinkingStepStatus,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ThinkingStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

// ============================================================================
// SIMPLE RESPONSE AGENT (Fallback)
// ============================================================================

/**
 * Simple agent for direct LLM responses when no specialized agent is needed.
 */
class SimpleResponseAgent(
    private val perplexityClient: PerplexityClient
) : Agent {
    override val name = "SimpleResponseAgent"
    override val description = "Provides direct responses using LLM"
    override val tools = emptyList<Tool>()
    override val systemPrompt = """
        You are a helpful bus route assistant for the Sahara app.
        Provide brief, friendly responses.
        Keep answers under 2 sentences.
    """.trimIndent()
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        return try {
            val response = perplexityClient.chat(
                systemPrompt = systemPrompt,
                userMessage = context.originalQuery
            ).getOrElse { "I can help you find bus routes, track buses, or get ETAs." }
            
            AgentOutput.success(
                agentName = name,
                data = mapOf("response" to response),
                message = response
            )
        } catch (e: Exception) {
            AgentOutput.failure(name, e.message ?: "Failed to generate response")
        }
    }
}
