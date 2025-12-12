package com.sahara.user.framework

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for all tools available to agents.
 * 
 * Features:
 * - Register/unregister tools dynamically
 * - Tool discovery by name or category
 * - Tool execution with timing and tracing
 * - Tool scoping (limit which agents can use which tools)
 */
@Singleton
class ToolRegistry @Inject constructor() {
    
    companion object {
        private const val TAG = "ToolRegistry"
    }
    
    private val tools = mutableMapOf<String, Tool>()
    private val toolCategories = mutableMapOf<String, MutableSet<String>>()
    private val mutex = Mutex()
    
    // ==================== REGISTRATION ====================
    
    /**
     * Register a tool in the registry.
     * 
     * @param tool The tool to register
     * @param categories Optional categories for grouping
     */
    suspend fun register(tool: Tool, vararg categories: String) {
        mutex.withLock {
            tools[tool.name] = tool
            categories.forEach { category ->
                toolCategories.getOrPut(category) { mutableSetOf() }.add(tool.name)
            }
            android.util.Log.d(TAG, "Registered tool: ${tool.name}")
        }
    }
    
    /**
     * Register multiple tools at once.
     */
    suspend fun registerAll(vararg toolsToRegister: Tool) {
        toolsToRegister.forEach { register(it) }
    }
    
    /**
     * Unregister a tool from the registry.
     */
    suspend fun unregister(toolName: String) {
        mutex.withLock {
            tools.remove(toolName)
            toolCategories.values.forEach { it.remove(toolName) }
            android.util.Log.d(TAG, "Unregistered tool: $toolName")
        }
    }
    
    // ==================== DISCOVERY ====================
    
    /**
     * Get a tool by name.
     */
    fun getTool(name: String): Tool? = tools[name]
    
    /**
     * Get all registered tools.
     */
    fun getAllTools(): List<Tool> = tools.values.toList()
    
    /**
     * Get tools by category.
     */
    fun getToolsByCategory(category: String): List<Tool> {
        val toolNames = toolCategories[category] ?: return emptyList()
        return toolNames.mapNotNull { tools[it] }
    }
    
    /**
     * Get all categories.
     */
    fun getCategories(): Set<String> = toolCategories.keys.toSet()
    
    /**
     * Check if a tool exists.
     */
    fun hasTool(name: String): Boolean = tools.containsKey(name)
    
    /**
     * Get tool names only (for LLM function listing).
     */
    fun getToolNames(): List<String> = tools.keys.toList()
    
    /**
     * Get tool descriptions for LLM (name -> description).
     */
    fun getToolDescriptions(): Map<String, String> = 
        tools.mapValues { it.value.description }
    
    // ==================== EXECUTION ====================
    
    /**
     * Execute a tool by name.
     * 
     * @param toolName Name of the tool to execute
     * @param params Input parameters
     * @param context Agent context
     * @return Tool execution result
     */
    suspend fun executeTool(
        toolName: String,
        params: Map<String, Any?>,
        context: AgentContext
    ): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult.failure(toolName, "Tool not found: $toolName")
        
        val startTime = System.currentTimeMillis()
        context.trace(TraceEvent.Type.TOOL_STARTED, "Executing tool: $toolName")
        
        return try {
            val result = tool.execute(params, context)
            val executionTime = System.currentTimeMillis() - startTime
            
            context.storeToolResult(toolName, result)
            context.trace(
                TraceEvent.Type.TOOL_COMPLETED, 
                "$toolName completed in ${executionTime}ms"
            )
            
            result.copy(executionTimeMs = executionTime)
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            context.trace(TraceEvent.Type.ERROR, "$toolName failed: ${e.message}")
            
            ToolResult(
                success = false,
                toolName = toolName,
                error = e.message ?: "Unknown error",
                executionTimeMs = executionTime
            )
        }
    }
    
    /**
     * Execute multiple tools in parallel.
     */
    suspend fun executeToolsParallel(
        toolCalls: List<Pair<String, Map<String, Any?>>>,
        context: AgentContext
    ): Map<String, ToolResult> {
        val results = mutableMapOf<String, ToolResult>()
        
        coroutineScope {
            // Launch all async tasks
            val deferreds = toolCalls.map { (toolName, params) ->
                async {
                    toolName to executeTool(toolName, params, context)
                }
            }
            
            // Await results using a for loop (suspend-safe)
            for (deferred in deferreds) {
                val (name, result) = deferred.await()
                results[name] = result
            }
        }
        
        return results
    }
    
    // ==================== SCHEMA GENERATION ====================
    
    /**
     * Generate OpenAI-compatible function definitions for all tools.
     * Used for LLM function calling.
     */
    fun generateFunctionDefinitions(): List<Map<String, Any>> {
        return tools.values.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to mapOf(
                    "type" to tool.inputSchema.type,
                    "properties" to tool.inputSchema.properties.mapValues { (_, prop) ->
                        mapOf(
                            "type" to prop.type,
                            "description" to prop.description
                        ).let { base ->
                            if (prop.enum != null) base + ("enum" to prop.enum) else base
                        }
                    },
                    "required" to tool.inputSchema.required
                )
            )
        }
    }
    
    /**
     * Get a summary of the registry for debugging.
     */
    fun getSummary(): String {
        return buildString {
            appendLine("=== Tool Registry ===")
            appendLine("Total tools: ${tools.size}")
            appendLine("Categories: ${toolCategories.keys.joinToString()}")
            appendLine()
            appendLine("Tools:")
            tools.forEach { (name, tool) ->
                appendLine("  - $name: ${tool.description.take(50)}...")
            }
        }
    }
}

// ============================================================================
// ABSTRACT TOOL BASE
// ============================================================================

/**
 * Abstract base class for creating tools with common functionality.
 */
abstract class BaseTool : Tool {
    
    /**
     * Validate input parameters against schema.
     * Override for custom validation.
     */
    protected open fun validate(params: Map<String, Any?>): Result<Unit> {
        val missing = inputSchema.required.filter { !params.containsKey(it) || params[it] == null }
        return if (missing.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalArgumentException("Missing required parameters: $missing"))
        }
    }
    
    /**
     * Template method for tool execution.
     */
    override suspend fun execute(params: Map<String, Any?>, context: AgentContext): ToolResult {
        // Validate
        val validation = validate(params)
        if (validation.isFailure) {
            return ToolResult.failure(name, validation.exceptionOrNull()?.message ?: "Validation failed")
        }
        
        // Execute
        return try {
            doExecute(params, context)
        } catch (e: Exception) {
            ToolResult.failure(name, e.message ?: "Execution failed")
        }
    }
    
    /**
     * Actual execution logic - implement in subclasses.
     */
    protected abstract suspend fun doExecute(
        params: Map<String, Any?>,
        context: AgentContext
    ): ToolResult
}

// ============================================================================
// SIMPLE FUNCTION TOOL
// ============================================================================

/**
 * Create a tool from a simple suspend function.
 * Useful for quick tool creation without full class.
 */
class FunctionTool(
    override val name: String,
    override val description: String,
    override val inputSchema: ToolSchema = ToolSchema(),
    override val outputSchema: ToolSchema = ToolSchema(),
    private val function: suspend (Map<String, Any?>, AgentContext) -> Map<String, Any?>
) : Tool {
    
    override suspend fun execute(params: Map<String, Any?>, context: AgentContext): ToolResult {
        return try {
            val result = function(params, context)
            ToolResult.success(name, result)
        } catch (e: Exception) {
            ToolResult.failure(name, e.message ?: "Function execution failed")
        }
    }
}
