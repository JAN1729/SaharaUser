package com.sahara.user.agents

import com.sahara.user.data.api.PerplexityClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Communication Agent - The Response Formatter
 * 
 * PERSONA: I am the user communication specialist. I make responses
 * clear, friendly, and well-formatted. I ask clarifying questions
 * when needed and format technical data for humans.
 * 
 * MY ROLE:
 * - Format agent results into user-friendly responses
 * - Ask clarifying questions when info is missing
 * - Generate welcome messages
 * - Format error messages helpfully
 * - Use appropriate emojis and structure
 * 
 * I COMMUNICATE WITH:
 * - MainOrchestrator: Takes raw results, returns formatted response
 */
@Singleton
class CommunicationAgent @Inject constructor(
    perplexityClient: PerplexityClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "CommunicationAgent"
    
    override val persona = AgentPersona(
        role = "Response Formatter",
        capabilities = listOf(
            "Format technical data for users",
            "Ask clarifying questions",
            "Generate friendly responses",
            "Use appropriate emojis",
            "Handle error messages gracefully"
        ),
        dataAccess = emptyList(),  // I don't access databases
        canDelegateTo = emptyList()
    )
    
    override val systemPrompt = """
        I am the Communication Agent - the user response specialist.
        
        MY PERSONALITY:
        - I am friendly and helpful
        - I make complex data simple
        - I use emojis appropriately
        - I keep responses concise
        
        FORMATTING RULES:
        - 🚌 for bus/vehicle info
        - 📍 for locations/stops
        - ⏱️ for time/ETA
        - 🔄 for transfers
        - ✅ for success, ❌ for errors
        - Use bullet points for lists
        - Highlight key info (distance, time, vehicle)
        
        I OUTPUT:
        - Well-formatted responses
        - Clear clarifying questions
        - Friendly error messages
    """.trimIndent()
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        val action = input["action"]?.toString() ?: "format"
        
        return when (action) {
            "clarify" -> askClarification(input)
            "format_route" -> formatRouteResponse(input)
            "format_location" -> formatLocationResponse(input)
            "error" -> formatError(input)
            "welcome" -> generateWelcome()
            else -> formatGeneric(input)
        }
    }
    
    private suspend fun askClarification(input: Map<String, Any>): AgentResult {
        val missing = input["missing"]?.toString() ?: "details"
        
        val question = when (missing) {
            "origin" -> "📍 Where would you like to start your journey from?"
            "destination" -> "📍 Where would you like to go?"
            "both" -> "Could you tell me your journey? For example:\n\"Take me from MG Road to Electronic City\""
            "bus_id" -> "🚌 Which bus are you asking about? You can tell me the name or number."
            "time" -> "⏱️ When are you planning to travel?"
            else -> "Could you provide more details about your request?"
        }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf("question" to question, "type" to "clarification"),
            message = question,
            confidence = 1.0f
        )
    }
    
    private suspend fun formatRouteResponse(input: Map<String, Any>): AgentResult {
        val pathData = input["path_result"] as? Map<*, *> ?: emptyMap<String, Any>()
        val timeData = input["time_result"] as? Map<*, *> ?: emptyMap<String, Any>()
        val transferData = input["transfer_result"] as? Map<*, *>
        
        val formatted = if (transferData != null && transferData.isNotEmpty()) {
            formatTransferRoute(transferData)
        } else {
            formatDirectRoute(pathData, timeData)
        }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf("formatted_response" to formatted),
            message = formatted,
            confidence = 0.95f
        )
    }
    
    private fun formatDirectRoute(pathData: Map<*, *>, timeData: Map<*, *>): String {
        val bestPath = pathData["best_path"] as? Map<*, *> ?: return "Route information not available"
        
        val vehicleName = bestPath["vehicle_name"] ?: "Bus"
        val vehicleNumber = bestPath["vehicle_number"] ?: ""
        val route = (bestPath["route"] as? List<*>)?.joinToString(" → ") ?: ""
        val distance = bestPath["distance_km"] ?: "?"
        val stops = bestPath["stops_count"] ?: "?"
        val isLive = bestPath["bus_is_live"] == true
        val stopsAway = bestPath["bus_stops_away"]
        
        val eta = timeData["adjusted_eta_min"] ?: timeData["base_eta_min"] ?: "?"
        val etaRange = if (timeData["eta_range_min"] != null) {
            "${timeData["eta_range_min"]}-${timeData["eta_range_max"]} min"
        } else "$eta min"
        
        return buildString {
            appendLine("🚌 **$vehicleName** ($vehicleNumber)")
            appendLine()
            appendLine("📍 Route: $route")
            appendLine("📏 Distance: $distance km | 🚏 $stops stops")
            appendLine("⏱️ ETA: **$etaRange**")
            if (isLive && stopsAway != null && stopsAway.toString().toIntOrNull() ?: -1 >= 0) {
                appendLine()
                appendLine("✅ Bus is live - $stopsAway stops away from your boarding point")
            }
        }
    }
    
    private fun formatTransferRoute(transferData: Map<*, *>): String {
        val bestTransfer = transferData["best_transfer"] as? Map<*, *> 
            ?: return "Transfer information not available"
        
        val firstBus = bestTransfer["first_bus"] ?: "Bus 1"
        val transferFrom = bestTransfer["transfer_from"] ?: "?"
        val walkingM = bestTransfer["walking_m"] ?: 0
        val transferTo = bestTransfer["transfer_to"] ?: transferFrom
        val secondBus = bestTransfer["second_bus"] ?: "Bus 2"
        val totalKm = bestTransfer["total_km"] ?: "?"
        
        return buildString {
            appendLine("🔄 **Transfer Required**")
            appendLine()
            appendLine("1️⃣ Take **$firstBus** to $transferFrom")
            if (walkingM.toString().toIntOrNull() ?: 0 > 0) {
                appendLine("🚶 Walk ${walkingM}m to $transferTo")
            }
            appendLine("2️⃣ Take **$secondBus** to destination")
            appendLine()
            appendLine("📏 Total: $totalKm km")
        }
    }
    
    private suspend fun formatLocationResponse(input: Map<String, Any>): AgentResult {
        val busData = input["bus_result"] as? Map<*, *> ?: emptyMap<String, Any>()
        
        val busId = busData["bus_id"] ?: "Bus"
        val isMoving = busData["is_moving"] == true
        val nearestStop = busData["nearest_stop"] ?: "Unknown"
        val freshness = busData["freshness"] ?: "UNKNOWN"
        val interpretation = busData["interpretation"]?.toString()
        
        val formatted = interpretation ?: buildString {
            appendLine("🚌 **$busId**")
            appendLine()
            if (isMoving) {
                appendLine("✅ Moving towards $nearestStop")
            } else {
                appendLine("📍 Stopped near $nearestStop")
            }
            if (freshness == "STALE") {
                appendLine("⚠️ Position data may be outdated")
            }
        }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf("formatted_response" to formatted),
            message = formatted,
            confidence = 0.9f
        )
    }
    
    private fun formatError(input: Map<String, Any>): AgentResult {
        val errorType = input["error_type"]?.toString() ?: "general"
        val details = input["details"]?.toString() ?: ""
        
        val message = when (errorType) {
            "no_route" -> "😔 I couldn't find a direct route for that journey.\n\n" +
                         "Try:\n• Check the stop names\n• Break into smaller segments"
            "no_bus" -> "🚌 No buses are currently active on that route.\n\n" +
                       "They might resume service shortly."
            "no_transfer" -> "🔄 Couldn't find a viable transfer between those locations.\n\n" +
                           "The stops might not be connected by our routes."
            "network" -> "📶 Having trouble connecting to the server.\n\n" +
                        "Please check your internet and try again."
            "timeout" -> "⏱️ The request took too long.\n\n" +
                        "Try a more specific search."
            else -> "😅 Something went wrong.\n\nPlease try again or rephrase your request."
        }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf("error_message" to message, "error_type" to errorType),
            message = message,
            confidence = 1.0f
        )
    }
    
    private fun generateWelcome(): AgentResult {
        val welcome = """
            👋 **Hello! I'm Sahara**
            
            Your AI-powered bus assistant. I can help you with:
            
            🚌 Finding the best bus routes
            📍 Tracking live buses
            ⏱️ Checking arrival times
            🔄 Planning multi-bus journeys
            
            Just tell me where you want to go!
            
            _Try: "Take me from MG Road to Electronic City"_
        """.trimIndent()
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf("welcome" to welcome),
            message = welcome,
            confidence = 1.0f
        )
    }
    
    private suspend fun formatGeneric(input: Map<String, Any>): AgentResult {
        val content = input["content"]?.toString() ?: ""
        
        val formatted = think("""
            Format this response for a user:
            $content
            
            Make it friendly, use emojis, keep it concise.
        """.trimIndent()).getOrElse { content }
        
        return AgentResult(
            success = true,
            agentName = agentName,
            data = mapOf("formatted_response" to formatted),
            message = formatted,
            confidence = 0.85f
        )
    }
}
