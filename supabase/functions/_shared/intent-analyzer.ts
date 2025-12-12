/**
 * Intent Analyzer - "Big Brain" for Query Understanding
 * 
 * Uses LLM to analyze user queries and determine:
 * - Primary intent (route search, bus tracking, etc.)
 * - Extracted entities (locations, bus IDs)
 * - Required agents to fulfill the request
 * - Execution strategy (parallel vs sequential)
 */

import { callLLMFast, parseJSONResponse } from './llm-client.ts';

// ============================================================================
// TYPES
// ============================================================================

export type IntentType =
    | 'FIND_ROUTE'      // User wants to travel from A to B
    | 'TRACK_BUS'       // User wants to know where a specific bus is
    | 'FIND_NEARBY'     // User wants nearby stops/buses
    | 'CALCULATE_ETA'   // User wants to know arrival time
    | 'TRANSFER_ROUTE'  // Complex route requiring transfers
    | 'FLEET_STATUS'    // Status of all buses
    | 'GENERAL'         // Greeting, help, or unknown
    | 'CLARIFICATION';  // Need more info from user

export interface ExtractedEntities {
    origin?: string;
    destination?: string;
    busId?: string;
    busName?: string;
    stopName?: string;
    timeConstraint?: string;  // "now", "in 10 minutes", "at 5pm"
}

export interface AnalyzedIntent {
    intent: IntentType;
    confidence: number;
    entities: ExtractedEntities;
    requiredAgents: string[];
    executionMode: 'sequential' | 'parallel';
    reasoning: string;
    clarificationNeeded?: string;
}

// ============================================================================
// SYSTEM PROMPT
// ============================================================================

const INTENT_ANALYZER_PROMPT = `You are the Sahara Bus System intent analyzer. Your job is to understand user queries about bus transportation and determine what they need.

AVAILABLE INTENTS:
- FIND_ROUTE: User wants to travel from one place to another
- TRACK_BUS: User wants to know a specific bus's current location
- FIND_NEARBY: User wants to find stops or buses near them
- CALCULATE_ETA: User wants to know when a bus will arrive
- TRANSFER_ROUTE: Complex journey needing bus changes (usually from failed direct route)
- FLEET_STATUS: User wants status of all buses
- GENERAL: Greeting, help request, or casual conversation
- CLARIFICATION: Query is unclear, need to ask user for more info

AVAILABLE AGENTS:
- DataAgent: Fetches vehicles, stops, live locations from database (always first)
- PathAgent: Finds optimal bus routes using algorithms
- TimeAgent: Calculates ETA with traffic adjustments
- TrackerAgent: Tracks real-time bus positions
- TransferAgent: Finds multi-bus routes with transfer points
- UserContextAgent: Gets user preferences and history
- AnalyticsAgent: Analyzes patterns and fleet status

RULES:
1. DataAgent is ALWAYS required first if any data is needed
2. PathAgent requires DataAgent to run first (sequential)
3. TimeAgent, TrackerAgent can run in parallel after DataAgent
4. TransferAgent is only for when direct route fails
5. Extract location names exactly as user writes them
6. Be liberal in intent detection - prefer action over GENERAL

Respond in JSON format only:
{
  "intent": "INTENT_TYPE",
  "confidence": 0.0-1.0,
  "entities": {
    "origin": "extracted origin or null",
    "destination": "extracted destination or null",
    "busId": "bus identifier or null",
    "busName": "bus name mention or null",
    "stopName": "stop name or null"
  },
  "requiredAgents": ["DataAgent", ...],
  "executionMode": "sequential" or "parallel",
  "reasoning": "brief explanation",
  "clarificationNeeded": "question to ask if intent unclear, or null"
}`;

// ============================================================================
// FALLBACK PATTERNS (deterministic backup)
// ============================================================================

const INTENT_PATTERNS: Array<{
    pattern: RegExp;
    intent: IntentType;
    agents: string[];
}> = [
        {
            pattern: /(?:from|at)\s+(.+?)\s+(?:to|towards?)\s+(.+)/i,
            intent: 'FIND_ROUTE',
            agents: ['DataAgent', 'PathAgent', 'TimeAgent']
        },
        {
            pattern: /(.+?)\s+to\s+(.+)/i,
            intent: 'FIND_ROUTE',
            agents: ['DataAgent', 'PathAgent', 'TimeAgent']
        },
        {
            pattern: /(?:where|track|find|locate)\s+(?:is\s+)?(?:the\s+)?(?:bus\s+)?(.+)/i,
            intent: 'TRACK_BUS',
            agents: ['DataAgent', 'TrackerAgent']
        },
        {
            pattern: /nearby|near\s+me|closest|around\s+here/i,
            intent: 'FIND_NEARBY',
            agents: ['DataAgent']
        },
        {
            pattern: /(?:eta|how\s+long|when|time|arrive)/i,
            intent: 'CALCULATE_ETA',
            agents: ['DataAgent', 'TimeAgent']
        },
        {
            pattern: /(?:fleet|all\s+bus|status|active)/i,
            intent: 'FLEET_STATUS',
            agents: ['DataAgent', 'AnalyticsAgent']
        },
        {
            pattern: /(?:hi|hello|hey|help|what\s+can)/i,
            intent: 'GENERAL',
            agents: []
        }
    ];

/**
 * Fallback regex-based intent detection when LLM fails.
 */
function fallbackIntentAnalysis(query: string): AnalyzedIntent {
    const q = query.toLowerCase().trim();

    for (const { pattern, intent, agents } of INTENT_PATTERNS) {
        const match = q.match(pattern);
        if (match) {
            const entities: ExtractedEntities = {};

            // Extract entities based on intent
            if (intent === 'FIND_ROUTE' && match.length >= 3) {
                entities.origin = match[1]?.trim();
                entities.destination = match[2]?.trim();
            } else if (intent === 'TRACK_BUS' && match[1]) {
                entities.busId = match[1]?.trim();
                entities.busName = match[1]?.trim();
            }

            return {
                intent,
                confidence: 0.6,
                entities,
                requiredAgents: agents,
                executionMode: agents.length > 2 ? 'parallel' : 'sequential',
                reasoning: 'Fallback pattern match'
            };
        }
    }

    return {
        intent: 'GENERAL',
        confidence: 0.3,
        entities: {},
        requiredAgents: [],
        executionMode: 'sequential',
        reasoning: 'No pattern matched, defaulting to general'
    };
}

// ============================================================================
// MAIN ANALYZER
// ============================================================================

/**
 * Analyze user query using LLM with fallback to regex patterns.
 */
export async function analyzeIntent(
    query: string,
    hasUserLocation: boolean = false
): Promise<AnalyzedIntent> {
    // Build context-aware prompt
    const contextInfo = hasUserLocation
        ? 'User has shared their GPS location.'
        : 'User has NOT shared their location.';

    const userMessage = `${contextInfo}

User query: "${query}"

Analyze this query and respond with JSON.`;

    try {
        const response = await callLLMFast(INTENT_ANALYZER_PROMPT, userMessage);
        const parsed = parseJSONResponse<AnalyzedIntent>(response.content);

        if (parsed && parsed.intent) {
            // Ensure DataAgent is first if any agents are required
            if (parsed.requiredAgents.length > 0 && !parsed.requiredAgents.includes('DataAgent')) {
                parsed.requiredAgents.unshift('DataAgent');
            }

            // Add LLM metadata
            return {
                ...parsed,
                reasoning: `[LLM] ${parsed.reasoning}`
            };
        }

        // LLM response parsing failed, use fallback
        console.warn('LLM response parsing failed, using fallback');
        return fallbackIntentAnalysis(query);

    } catch (error) {
        console.error('Intent analysis LLM error:', error);
        return fallbackIntentAnalysis(query);
    }
}

/**
 * Quick intent check without LLM (for simple queries).
 */
export function quickIntentCheck(query: string): IntentType | null {
    const q = query.toLowerCase().trim();

    if (/^(hi|hello|hey|help)[\s!?.]*$/i.test(q)) {
        return 'GENERAL';
    }

    if (q.includes(' to ') && q.length > 10) {
        return 'FIND_ROUTE';
    }

    if (/where|track|find/.test(q) && /bus/.test(q)) {
        return 'TRACK_BUS';
    }

    return null;
}
