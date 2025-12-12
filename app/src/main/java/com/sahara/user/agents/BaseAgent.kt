package com.sahara.user.agents

import com.sahara.user.data.api.PerplexityClient

/**
 * Base Agent - Foundation for all sub-agents
 * 
 * Every sub-agent has:
 * - A unique persona (role, capabilities, data access)
 * - Access to Perplexity for reasoning
 * - Ability to communicate with other agents via hub
 * - Standardized result format
 */
abstract class BaseAgent(
    protected val perplexityClient: PerplexityClient
) {
    
    /**
     * Unique name of this agent
     */
    abstract val agentName: String
    
    /**
     * System prompt defining agent's persona and behavior
     */
    abstract val systemPrompt: String
    
    /**
     * Agent's persona - role, capabilities, data access
     */
    abstract val persona: AgentPersona
    
    /**
     * Communication hub reference (injected by orchestrator)
     */
    var communicationHub: AgentCommunicationHub? = null
    
    /**
     * Register this agent with the communication hub
     */
    fun register(hub: AgentCommunicationHub) {
        this.communicationHub = hub
        hub.registerAgent(agentName, persona)
    }
    
    /**
     * Execute the agent's main task
     */
    abstract suspend fun execute(input: Map<String, Any>): AgentResult
    
    /**
     * Use Perplexity AI for reasoning/analysis
     */
    protected suspend fun think(prompt: String): Result<String> {
        return perplexityClient.chat(
            systemPrompt = systemPrompt,
            userMessage = prompt
        )
    }
    
    /**
     * Request data from another agent
     */
    protected fun requestFromAgent(
        targetAgent: String,
        dataType: String,
        parameters: Map<String, Any> = emptyMap()
    ): String? {
        return communicationHub?.requestData(
            fromAgent = agentName,
            toAgent = targetAgent,
            dataType = dataType,
            parameters = parameters
        )
    }
    
    /**
     * Get result from another agent (if already executed)
     */
    protected fun getResultFrom(agentName: String): AgentResult? {
        return communicationHub?.getAgentResult(agentName)
    }
    
    /**
     * Notify other agents of an update
     */
    protected fun notifyAgents(update: Map<String, Any>) {
        communicationHub?.broadcast(
            fromAgent = agentName,
            messageType = MessageType.RESULT_UPDATE,
            content = update
        )
    }
    
    /**
     * Report an error to the hub
     */
    protected fun reportError(error: String) {
        communicationHub?.broadcast(
            fromAgent = agentName,
            messageType = MessageType.ERROR_REPORT,
            content = mapOf("error" to error)
        )
    }
    
    /**
     * Store this agent's result in the hub
     */
    protected fun storeResult(result: AgentResult) {
        communicationHub?.storeResult(agentName, result)
    }
}

/**
 * Standardized result from any agent
 */
data class AgentResult(
    val success: Boolean,
    val agentName: String,
    val data: Map<String, Any> = emptyMap(),
    val message: String? = null,
    val error: String? = null,
    val confidence: Float = 1.0f,  // How confident the agent is in its result
    val needsMoreData: Boolean = false,  // Does this agent need input from another?
    val suggestedNextAgent: String? = null  // Optional suggestion for next agent
)
