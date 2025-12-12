/**
 * Result Synthesizer - "Small Brain" for Response Generation
 * 
 * Uses LLM to:
 * - Validate agent results for completeness
 * - Generate natural language responses
 * - Suggest follow-up actions
 */

import { callLLMQuality, parseJSONResponse } from './llm-client.ts';
import type { AgentOutput } from './types.ts';

// ============================================================================
// TYPES
// ============================================================================

export interface ValidationResult {
    isValid: boolean;
    issues: string[];
    suggestions: string[];
    shouldRetryWithAgent?: string;
}

export interface SynthesizedResponse {
    message: string;
    highlights: string[];
    followUpSuggestion?: string;
    confidence: number;
}

// ============================================================================
// VALIDATION PROMPT
// ============================================================================

const VALIDATOR_PROMPT = `You are validating bus route search results for a user query.

Check if the results are:
1. COMPLETE: All information the user asked for is present
2. LOGICAL: Data makes sense (origin before destination, reasonable ETA)
3. ACTIONABLE: User can act on this information

Common issues to check:
- Missing route when one should exist
- ETA seems too long or too short
- No live bus data when it might be available
- Transfer suggested when direct route exists

Respond in JSON:
{
  "isValid": true/false,
  "issues": ["list of problems found"],
  "suggestions": ["what could be done to fix"],
  "shouldRetryWithAgent": "AgentName or null"
}`;

// ============================================================================
// SYNTHESIS PROMPT
// ============================================================================

const SYNTHESIZER_PROMPT = `You are a helpful bus route assistant for the Sahara Bus app in India.

Generate a CONCISE, HELPFUL response for the user based on the agent results provided.

RULES:
1. Be direct and actionable - tell user exactly what to do
2. Use relevant emojis sparingly (🚌 for bus, 📍 for location, ⏱️ for time)
3. Highlight key information: bus name, ETA, live status
4. If bus is live tracking, mention how many stops away
5. Keep response under 80 words - users are on mobile
6. If no route found, be helpful about alternatives
7. Use markdown **bold** for emphasis on key details

Format response naturally, not as a list unless showing alternatives.`;

// ============================================================================
// FALLBACK SYNTHESIS
// ============================================================================

/**
 * Deterministic response synthesis when LLM fails.
 */
function fallbackSynthesis(
    intent: string,
    agentResults: Map<string, AgentOutput>,
    routeData?: Record<string, unknown>
): SynthesizedResponse {
    // Check for route data
    if (routeData && intent === 'FIND_ROUTE') {
        const vehicle = routeData.vehicleName || 'Bus';
        const eta = routeData.etaMinutes || '?';
        const distance = routeData.distanceKm || '?';
        const isLive = routeData.isLive;
        const stopsAway = routeData.busStopsAway;

        let message = `🚌 **${vehicle}** • ${distance} km • ~${eta} min`;

        if (isLive && stopsAway !== undefined && stopsAway >= 0) {
            message += ` • Bus is ${stopsAway} stops away`;
        }

        return {
            message,
            highlights: [String(vehicle), `${eta} min`],
            confidence: 0.8
        };
    }

    // Check for tracker data
    const trackerResult = agentResults.get('TrackerAgent');
    if (trackerResult?.success && intent === 'TRACK_BUS') {
        const data = trackerResult.data;
        const busName = data.bus_name || 'Bus';
        const nearestStop = data.nearest_stop || 'Unknown';
        const isMoving = data.is_moving;

        return {
            message: `📍 **${busName}** is ${isMoving ? 'moving' : 'stopped'} near **${nearestStop}**`,
            highlights: [String(busName), String(nearestStop)],
            confidence: 0.8
        };
    }

    // Check for nearby stops
    const dataResult = agentResults.get('DataAgent');
    if (intent === 'FIND_NEARBY' && dataResult?.success) {
        return {
            message: '📍 Found nearby stops. Check the map for details.',
            highlights: [],
            confidence: 0.6
        };
    }

    // No route found
    const pathResult = agentResults.get('PathAgent');
    if (pathResult && !pathResult.success) {
        return {
            message: '❌ No direct route found between these stops. Try checking for transfer options.',
            highlights: [],
            followUpSuggestion: 'Would you like me to find a route with transfers?',
            confidence: 0.7
        };
    }

    // Generic fallback
    return {
        message: 'I processed your request. Please check the results above.',
        highlights: [],
        confidence: 0.4
    };
}

// ============================================================================
// MAIN FUNCTIONS
// ============================================================================

/**
 * Validate agent results for completeness and logic.
 */
export async function validateResults(
    query: string,
    intent: string,
    agentResults: Map<string, AgentOutput>
): Promise<ValidationResult> {
    // Convert results to readable format
    const resultsText = Array.from(agentResults.entries())
        .map(([name, result]) => `${name}: ${result.success ? 'SUCCESS' : 'FAILED'} - ${result.message || result.error || JSON.stringify(result.data)}`)
        .join('\n');

    const userMessage = `Original query: "${query}"
Intent detected: ${intent}

Agent results:
${resultsText}

Validate these results.`;

    try {
        const response = await callLLMQuality(VALIDATOR_PROMPT, userMessage);
        const parsed = parseJSONResponse<ValidationResult>(response.content);

        if (parsed) {
            return parsed;
        }
    } catch (error) {
        console.error('Validation LLM error:', error);
    }

    // Fallback: basic validation
    const hasSuccess = Array.from(agentResults.values()).some(r => r.success);
    return {
        isValid: hasSuccess,
        issues: hasSuccess ? [] : ['No agent succeeded'],
        suggestions: []
    };
}

/**
 * Synthesize a natural language response from agent results.
 */
export async function synthesizeResponse(
    query: string,
    intent: string,
    agentResults: Map<string, AgentOutput>,
    routeData?: Record<string, unknown>
): Promise<SynthesizedResponse> {
    // Build consolidated results for LLM
    const consolidatedResults: Record<string, unknown> = {};

    for (const [name, result] of agentResults) {
        consolidatedResults[name] = {
            success: result.success,
            message: result.message,
            data: result.data
        };
    }

    if (routeData) {
        consolidatedResults['bestRoute'] = routeData;
    }

    const userMessage = `User query: "${query}"

Available data from agents:
${JSON.stringify(consolidatedResults, null, 2)}

Generate a helpful response for the user.`;

    try {
        const response = await callLLMQuality(SYNTHESIZER_PROMPT, userMessage);

        // The response is natural text, not JSON
        const message = response.content.trim();

        // Extract highlights (bolded text)
        const highlights = (message.match(/\*\*([^*]+)\*\*/g) || [])
            .map(h => h.replace(/\*\*/g, ''));

        return {
            message,
            highlights,
            confidence: 0.9
        };

    } catch (error) {
        console.error('Synthesis LLM error:', error);
        return fallbackSynthesis(intent, agentResults, routeData);
    }
}

/**
 * Quick synthesis without LLM (for simple responses).
 */
export function quickSynthesis(
    intent: string,
    agentResults: Map<string, AgentOutput>,
    routeData?: Record<string, unknown>
): SynthesizedResponse {
    return fallbackSynthesis(intent, agentResults, routeData);
}
