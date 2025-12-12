package com.sahara.user.framework

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Workflow Agents - Orchestration Patterns
 * 
 * Inspired by Google ADK's workflow agents:
 * - SequentialAgent: Execute agents in strict order
 * - ParallelAgent: Execute independent agents simultaneously
 * - LoopAgent: Retry with conditions until success or max attempts
 * - ConditionalAgent: Execute different agents based on conditions
 * 
 * These are meta-agents that coordinate other agents.
 */

// ============================================================================
// SEQUENTIAL AGENT
// ============================================================================

/**
 * Executes a list of agents in strict sequential order.
 * Each agent receives the results of all previous agents.
 * 
 * Use when: Order matters, agents depend on previous results
 * Example: DataAgent → PathAgent → TimeAgent
 */
class SequentialAgent(
    override val name: String = "SequentialAgent",
    override val description: String = "Executes agents in order",
    private val agents: List<Agent>,
    private val stopOnFailure: Boolean = true
) : Agent {
    
    override val tools: List<Tool> = emptyList()
    override val systemPrompt: String = "Orchestrate agents sequentially"
    
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        val results = mutableMapOf<String, AgentOutput>()
        var lastOutput: AgentOutput? = null
        
        context.trace(TraceEvent.Type.AGENT_STARTED, "Starting sequential execution of ${agents.size} agents")
        
        for ((index, agent) in agents.withIndex()) {
            _progress.value = ((index.toFloat() / agents.size) * 100).toInt()
            context.setCurrentAgent(agent.name)
            context.updateStatus("Running ${agent.name}...")
            
            try {
                // Build input with previous results
                val agentInput = input.copy(previousResults = results.toMap())
                
                // Execute agent
                agent.onStart(context)
                val output = agent.execute(context, agentInput)
                agent.onComplete(context, output)
                
                results[agent.name] = output
                context.storeAgentResult(agent.name, output)
                lastOutput = output
                
                if (!output.success && stopOnFailure) {
                    context.trace(TraceEvent.Type.ERROR, "${agent.name} failed, stopping sequence")
                    break
                }
                
            } catch (e: Exception) {
                val failureOutput = AgentOutput.failure(agent.name, e.message ?: "Unknown error")
                results[agent.name] = failureOutput
                context.storeAgentResult(agent.name, failureOutput)
                
                if (stopOnFailure) {
                    return AgentOutput.failure(
                        agentName = name,
                        error = "Sequential execution failed at ${agent.name}: ${e.message}",
                        data = mapOf("completedAgents" to results.keys.toList())
                    )
                }
            }
        }
        
        _progress.value = 100
        context.setCurrentAgent(null)
        
        val successCount = results.values.count { it.success }
        val totalTime = System.currentTimeMillis() - startTime
        
        return AgentOutput.success(
            agentName = name,
            data = mapOf(
                "agentResults" to results,
                "successCount" to successCount,
                "totalCount" to agents.size
            ),
            message = "Completed $successCount/${agents.size} agents",
            confidence = successCount.toFloat() / agents.size,
            toolsUsed = results.values.flatMap { it.toolsUsed }
        ).copy(executionTimeMs = totalTime)
    }
}

// ============================================================================
// PARALLEL AGENT
// ============================================================================

/**
 * Executes multiple agents simultaneously.
 * All agents run at the same time and results are collected when all complete.
 * 
 * Use when: Agents are independent and can run concurrently
 * Example: Running PathAgent, TimeAgent, BusTrackerAgent together
 */
class ParallelAgent(
    override val name: String = "ParallelAgent",
    override val description: String = "Executes agents in parallel",
    private val agents: List<Agent>,
    private val requireAllSuccess: Boolean = false,
    private val timeoutMs: Long = 30_000L
) : Agent {
    
    override val tools: List<Tool> = emptyList()
    override val systemPrompt: String = "Orchestrate agents in parallel"
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        
        context.trace(TraceEvent.Type.PARALLEL_START, "Starting parallel execution of ${agents.size} agents")
        context.updateStatus("Running ${agents.size} agents in parallel...")
        
        val results = mutableMapOf<String, AgentOutput>()
        
        try {
            withTimeout(timeoutMs) {
                coroutineScope {
                    val deferredResults = agents.map { agent ->
                        async(Dispatchers.Default) {
                            try {
                                agent.onStart(context)
                                val output = agent.execute(context, input)
                                agent.onComplete(context, output)
                                agent.name to output
                            } catch (e: Exception) {
                                agent.name to AgentOutput.failure(
                                    agentName = agent.name,
                                    error = e.message ?: "Execution failed"
                                )
                            }
                        }
                    }
                    
                    deferredResults.forEach { deferred ->
                        val (agentName, output) = deferred.await()
                        results[agentName] = output
                        context.storeAgentResult(agentName, output)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            context.trace(TraceEvent.Type.ERROR, "Parallel execution timed out after ${timeoutMs}ms")
            
            // Store whatever results we have
            return AgentOutput.failure(
                agentName = name,
                error = "Parallel execution timed out after ${timeoutMs}ms",
                data = mapOf("partialResults" to results)
            )
        }
        
        context.trace(TraceEvent.Type.PARALLEL_END, "Parallel execution complete")
        
        val successCount = results.values.count { it.success }
        val allSuccess = successCount == agents.size
        val totalTime = System.currentTimeMillis() - startTime
        
        return if (requireAllSuccess && !allSuccess) {
            val failedAgents = results.filter { !it.value.success }.keys
            AgentOutput.failure(
                agentName = name,
                error = "Required all agents to succeed, but ${failedAgents.size} failed: $failedAgents",
                data = mapOf("agentResults" to results)
            )
        } else {
            AgentOutput.success(
                agentName = name,
                data = mapOf(
                    "agentResults" to results,
                    "successCount" to successCount,
                    "totalCount" to agents.size
                ),
                message = "Parallel: $successCount/${agents.size} succeeded",
                confidence = successCount.toFloat() / agents.size,
                toolsUsed = results.values.flatMap { it.toolsUsed }
            ).copy(executionTimeMs = totalTime)
        }
    }
}

// ============================================================================
// LOOP AGENT
// ============================================================================

/**
 * Executes an agent repeatedly until a condition is met or max attempts reached.
 * 
 * Use when: Need retry logic, refinement loops, or iterative processing
 * Example: Retry failed API calls, refine search results
 */
class LoopAgent(
    override val name: String = "LoopAgent",
    override val description: String = "Executes agent with retry logic",
    private val agent: Agent,
    private val maxAttempts: Int = 3,
    private val delayBetweenAttempts: Long = 1000L,
    private val shouldContinue: (AgentOutput, Int) -> Boolean = { output, _ -> !output.success },
    private val onRetry: ((Int, AgentOutput) -> Unit)? = null
) : Agent {
    
    override val tools: List<Tool> = emptyList()
    override val systemPrompt: String = "Orchestrate agent with retry logic"
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        var attempt = 0
        var lastOutput: AgentOutput? = null
        
        context.trace(TraceEvent.Type.AGENT_STARTED, "Starting loop execution, max attempts: $maxAttempts")
        
        while (attempt < maxAttempts) {
            attempt++
            context.updateStatus("Attempt $attempt/$maxAttempts: ${agent.name}")
            context.setCurrentAgent(agent.name)
            
            try {
                agent.onStart(context)
                val output = agent.execute(context, input)
                agent.onComplete(context, output)
                
                lastOutput = output
                context.storeAgentResult("${agent.name}_attempt_$attempt", output)
                
                if (!shouldContinue(output, attempt)) {
                    context.trace(TraceEvent.Type.AGENT_COMPLETED, "Loop completed on attempt $attempt")
                    
                    return AgentOutput.success(
                        agentName = name,
                        data = output.data + mapOf("attempts" to attempt),
                        message = "Succeeded on attempt $attempt",
                        confidence = output.confidence,
                        toolsUsed = output.toolsUsed
                    ).copy(executionTimeMs = System.currentTimeMillis() - startTime)
                }
                
                // Notify retry callback
                onRetry?.invoke(attempt, output)
                
                if (attempt < maxAttempts) {
                    context.trace(TraceEvent.Type.STATUS_UPDATE, "Retrying in ${delayBetweenAttempts}ms...")
                    delay(delayBetweenAttempts)
                }
                
            } catch (e: Exception) {
                lastOutput = AgentOutput.failure(agent.name, e.message ?: "Unknown error")
                
                if (attempt < maxAttempts) {
                    context.trace(TraceEvent.Type.ERROR, "Attempt $attempt failed: ${e.message}, retrying...")
                    delay(delayBetweenAttempts)
                }
            }
        }
        
        context.setCurrentAgent(null)
        
        return AgentOutput.failure(
            agentName = name,
            error = "Max attempts ($maxAttempts) exceeded",
            data = mapOf(
                "attempts" to attempt,
                "lastResult" to lastOutput?.data
            )
        ).copy(executionTimeMs = System.currentTimeMillis() - startTime)
    }
}

// ============================================================================
// CONDITIONAL AGENT
// ============================================================================

/**
 * Selects and executes an agent based on a condition.
 * 
 * Use when: Different paths based on input or context
 * Example: Direct route vs Transfer route based on availability
 */
class ConditionalAgent(
    override val name: String = "ConditionalAgent",
    override val description: String = "Selects agent based on condition",
    private val condition: suspend (AgentContext, AgentInput) -> String,
    private val branches: Map<String, Agent>,
    private val defaultBranch: Agent? = null
) : Agent {
    
    override val tools: List<Tool> = emptyList()
    override val systemPrompt: String = "Select and execute agent based on conditions"
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        
        // Evaluate condition
        val branchKey = condition(context, input)
        val selectedAgent = branches[branchKey] ?: defaultBranch
        
        if (selectedAgent == null) {
            return AgentOutput.failure(
                agentName = name,
                error = "No agent found for branch '$branchKey' and no default provided"
            )
        }
        
        context.trace(TraceEvent.Type.AGENT_STARTED, "Selected branch: $branchKey -> ${selectedAgent.name}")
        context.setCurrentAgent(selectedAgent.name)
        
        try {
            selectedAgent.onStart(context)
            val output = selectedAgent.execute(context, input)
            selectedAgent.onComplete(context, output)
            
            return AgentOutput.success(
                agentName = name,
                data = output.data + mapOf("selectedBranch" to branchKey),
                message = output.message,
                confidence = output.confidence,
                suggestedNextAgent = output.suggestedNextAgent,
                toolsUsed = output.toolsUsed
            ).copy(executionTimeMs = System.currentTimeMillis() - startTime)
            
        } catch (e: Exception) {
            return AgentOutput.failure(
                agentName = name,
                error = "Branch '$branchKey' failed: ${e.message}"
            )
        } finally {
            context.setCurrentAgent(null)
        }
    }
}

// ============================================================================
// FALLBACK AGENT
// ============================================================================

/**
 * Tries agents in order until one succeeds.
 * 
 * Use when: Need fallback options if primary agent fails
 * Example: Try direct route, fallback to transfer route, fallback to suggestions
 */
class FallbackAgent(
    override val name: String = "FallbackAgent",
    override val description: String = "Tries agents until one succeeds",
    private val agents: List<Agent>
) : Agent {
    
    override val tools: List<Tool> = emptyList()
    override val systemPrompt: String = "Try agents in order until success"
    
    override suspend fun execute(context: AgentContext, input: AgentInput): AgentOutput {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        
        context.trace(TraceEvent.Type.AGENT_STARTED, "Starting fallback with ${agents.size} options")
        
        for (agent in agents) {
            context.setCurrentAgent(agent.name)
            context.updateStatus("Trying ${agent.name}...")
            
            try {
                agent.onStart(context)
                val output = agent.execute(context, input)
                agent.onComplete(context, output)
                
                if (output.success) {
                    context.trace(TraceEvent.Type.AGENT_COMPLETED, "Fallback succeeded with ${agent.name}")
                    return AgentOutput.success(
                        agentName = name,
                        data = output.data + mapOf("succeededWith" to agent.name),
                        message = output.message,
                        confidence = output.confidence,
                        toolsUsed = output.toolsUsed
                    ).copy(executionTimeMs = System.currentTimeMillis() - startTime)
                }
                
                errors.add("${agent.name}: ${output.error ?: "Unknown failure"}")
                
            } catch (e: Exception) {
                errors.add("${agent.name}: ${e.message}")
            }
        }
        
        context.setCurrentAgent(null)
        
        return AgentOutput.failure(
            agentName = name,
            error = "All fallback options failed",
            data = mapOf("errors" to errors)
        ).copy(executionTimeMs = System.currentTimeMillis() - startTime)
    }
}
