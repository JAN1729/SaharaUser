/**
 * Agent Priority & Intent Confidence System
 * 
 * Phase 3: Tiered Intent Confidence Thresholds
 * - HIGH (0.8-1.0): Direct routing, skip LLM retry
 * - MEDIUM (0.6-0.79): Use result but consider retry for complex queries
 * - LOW (0.4-0.59): Retry with LLM if available
 * - FALLBACK (<0.4): Must use LLM or return clarification request
 * 
 * Phase 4: Dynamic Agent Priority Weights
 * - Query-type affinity scoring
 * - Agent selection based on intent + history
 */

// ============================================================================
// CONFIDENCE TIERS
// ============================================================================

export enum ConfidenceTier {
    HIGH = 'HIGH',         // 0.8-1.0: Trust completely
    MEDIUM = 'MEDIUM',     // 0.6-0.79: Use but may verify
    LOW = 'LOW',           // 0.4-0.59: Needs LLM retry
    FALLBACK = 'FALLBACK'  // <0.4: Must clarify
}

export interface ConfidenceThresholds {
    high: number;    // >= this = HIGH
    medium: number;  // >= this = MEDIUM
    low: number;     // >= this = LOW
    // Below low = FALLBACK
}

export const DEFAULT_THRESHOLDS: ConfidenceThresholds = {
    high: 0.8,
    medium: 0.6,
    low: 0.4
};

export function getConfidenceTier(
    confidence: number,
    thresholds: ConfidenceThresholds = DEFAULT_THRESHOLDS
): ConfidenceTier {
    if (confidence >= thresholds.high) return ConfidenceTier.HIGH;
    if (confidence >= thresholds.medium) return ConfidenceTier.MEDIUM;
    if (confidence >= thresholds.low) return ConfidenceTier.LOW;
    return ConfidenceTier.FALLBACK;
}

// ============================================================================
// AGENT DEFINITIONS
// ============================================================================

export enum AgentType {
    DATA_AGENT = 'DataAgent',
    PATH_AGENT = 'PathAgent',
    TIME_AGENT = 'TimeAgent',
    TRACKER_AGENT = 'TrackerAgent',
    TRANSFER_AGENT = 'TransferAgent',
    MEMORY_AGENT = 'MemoryAgent',
    GEOCODING_AGENT = 'GeocodingAgent'
}

export type IntentType = 'FIND_ROUTE' | 'TRACK_BUS' | 'FIND_NEARBY' | 'GENERAL';

// ============================================================================
// AGENT PRIORITY WEIGHTS
// ============================================================================

/**
 * Base affinity scores for each agent per intent type.
 * Higher score = more relevant agent for this intent.
 * Scores are 0-100.
 */
export const AGENT_INTENT_AFFINITY: Record<IntentType, Record<AgentType, number>> = {
    FIND_ROUTE: {
        [AgentType.DATA_AGENT]: 100,      // Essential - must fetch data
        [AgentType.PATH_AGENT]: 95,       // Core - compute routes
        [AgentType.TIME_AGENT]: 85,       // Important - ETA calculation
        [AgentType.TRACKER_AGENT]: 60,    // Useful - live bus info
        [AgentType.TRANSFER_AGENT]: 50,   // Sometimes - multi-hop routes
        [AgentType.MEMORY_AGENT]: 30,     // Low - context helps
        [AgentType.GEOCODING_AGENT]: 70   // Medium - place name resolution
    },
    TRACK_BUS: {
        [AgentType.DATA_AGENT]: 100,      // Essential - fetch vehicle data
        [AgentType.TRACKER_AGENT]: 95,    // Core - bus position
        [AgentType.PATH_AGENT]: 40,       // Low - route info
        [AgentType.TIME_AGENT]: 60,       // Medium - arrival time
        [AgentType.TRANSFER_AGENT]: 10,   // Not needed
        [AgentType.MEMORY_AGENT]: 20,     // Low
        [AgentType.GEOCODING_AGENT]: 50   // Medium - stop names
    },
    FIND_NEARBY: {
        [AgentType.DATA_AGENT]: 100,      // Essential - fetch stops
        [AgentType.GEOCODING_AGENT]: 85,  // Important - location context
        [AgentType.PATH_AGENT]: 60,       // Medium - distance calc
        [AgentType.TRACKER_AGENT]: 50,    // Low - live bus at stops
        [AgentType.TIME_AGENT]: 40,       // Low
        [AgentType.TRANSFER_AGENT]: 10,   // Not needed
        [AgentType.MEMORY_AGENT]: 30      // Low
    },
    GENERAL: {
        [AgentType.MEMORY_AGENT]: 80,     // Important - context
        [AgentType.DATA_AGENT]: 50,       // May need
        [AgentType.PATH_AGENT]: 20,       // Low
        [AgentType.TIME_AGENT]: 20,       // Low
        [AgentType.TRACKER_AGENT]: 20,    // Low
        [AgentType.TRANSFER_AGENT]: 10,   // Not needed
        [AgentType.GEOCODING_AGENT]: 40   // Medium
    }
};

/**
 * Minimum affinity score to include an agent in execution.
 * Agents below this threshold are skipped for efficiency.
 */
export const AGENT_INCLUSION_THRESHOLD = 50;

// ============================================================================
// AGENT SELECTION
// ============================================================================

export interface AgentScore {
    agent: AgentType;
    baseAffinity: number;
    adjustedScore: number;
    include: boolean;
    reason: string;
}

export interface AgentSelectionResult {
    requiredAgents: AgentType[];
    optionalAgents: AgentType[];
    skippedAgents: AgentType[];
    scores: AgentScore[];
}

/**
 * Select which agents to run based on intent and confidence.
 * Returns agents sorted by priority (highest first).
 */
export function selectAgentsForIntent(
    intent: IntentType,
    confidence: number,
    options: {
        includeOptional?: boolean;
        forceAgents?: AgentType[];
    } = {}
): AgentSelectionResult {
    const tier = getConfidenceTier(confidence);
    const affinities = AGENT_INTENT_AFFINITY[intent];

    const scores: AgentScore[] = Object.entries(affinities).map(([agent, baseAffinity]) => {
        const agentType = agent as AgentType;

        // Adjust score based on confidence tier
        let adjustedScore = baseAffinity;

        // High confidence: slightly reduce optional agents
        if (tier === ConfidenceTier.HIGH) {
            if (baseAffinity < 60) adjustedScore *= 0.8;
        }
        // Low confidence: boost context agents
        else if (tier === ConfidenceTier.LOW || tier === ConfidenceTier.FALLBACK) {
            if (agentType === AgentType.MEMORY_AGENT) adjustedScore += 20;
            if (agentType === AgentType.GEOCODING_AGENT) adjustedScore += 10;
        }

        // Force inclusion if specified
        const forced = options.forceAgents?.includes(agentType);
        const include = forced || adjustedScore >= AGENT_INCLUSION_THRESHOLD;

        const reason = forced
            ? 'Forced inclusion'
            : include
                ? `Score ${adjustedScore} >= threshold ${AGENT_INCLUSION_THRESHOLD}`
                : `Score ${adjustedScore} < threshold ${AGENT_INCLUSION_THRESHOLD}`;

        return {
            agent: agentType,
            baseAffinity,
            adjustedScore,
            include,
            reason
        };
    });

    // Sort by adjusted score (highest first)
    scores.sort((a, b) => b.adjustedScore - a.adjustedScore);

    const requiredAgents: AgentType[] = [];
    const optionalAgents: AgentType[] = [];
    const skippedAgents: AgentType[] = [];

    for (const score of scores) {
        if (!score.include) {
            skippedAgents.push(score.agent);
        } else if (score.adjustedScore >= 80) {
            requiredAgents.push(score.agent);
        } else {
            if (options.includeOptional !== false) {
                optionalAgents.push(score.agent);
            } else {
                skippedAgents.push(score.agent);
            }
        }
    }

    return { requiredAgents, optionalAgents, skippedAgents, scores };
}

// ============================================================================
// INTENT RETRY LOGIC
// ============================================================================

export interface IntentRetryDecision {
    shouldRetry: boolean;
    reason: string;
    tier: ConfidenceTier;
}

/**
 * Decide whether to retry intent analysis with LLM.
 */
export function shouldRetryIntentWithLLM(
    confidence: number,
    currentSource: 'pattern' | 'llm',
    hasLLMApiKey: boolean
): IntentRetryDecision {
    const tier = getConfidenceTier(confidence);

    // Already from LLM - don't retry
    if (currentSource === 'llm') {
        return {
            shouldRetry: false,
            reason: 'Already analyzed by LLM',
            tier
        };
    }

    // No API key - can't retry
    if (!hasLLMApiKey) {
        return {
            shouldRetry: false,
            reason: 'No LLM API key available',
            tier
        };
    }

    // HIGH confidence from patterns - trust it
    if (tier === ConfidenceTier.HIGH) {
        return {
            shouldRetry: false,
            reason: 'Pattern match confidence is HIGH',
            tier
        };
    }

    // MEDIUM confidence - retry only for complex queries
    if (tier === ConfidenceTier.MEDIUM) {
        return {
            shouldRetry: false,  // Could be true for complex queries
            reason: 'Pattern match confidence is acceptable',
            tier
        };
    }

    // LOW or FALLBACK - should retry
    return {
        shouldRetry: true,
        reason: `Pattern match confidence is ${tier}, retrying with LLM`,
        tier
    };
}

// ============================================================================
// CLARIFICATION PROMPTS
// ============================================================================

export const CLARIFICATION_PROMPTS: Record<IntentType, string> = {
    FIND_ROUTE: "Where would you like to go? Please tell me your origin and destination.",
    TRACK_BUS: "Which bus would you like to track? Please provide the bus name or number.",
    FIND_NEARBY: "I can help you find nearby stops. Please share your location.",
    GENERAL: "👋 Hello! I can help you find bus routes, track buses, or find nearby stops. What would you like to do?"
};

/**
 * Get clarification prompt for low-confidence intent.
 */
export function getClarificationPrompt(
    intent: IntentType,
    confidence: number,
    missingEntities: string[]
): string {
    const tier = getConfidenceTier(confidence);

    // FALLBACK tier - need full clarification
    if (tier === ConfidenceTier.FALLBACK) {
        return CLARIFICATION_PROMPTS[intent];
    }

    // LOW tier with missing entities - ask specifically
    if (tier === ConfidenceTier.LOW && missingEntities.length > 0) {
        if (intent === 'FIND_ROUTE') {
            if (missingEntities.includes('destination') && missingEntities.includes('origin')) {
                return "Where would you like to travel? Please tell me your starting point and destination.";
            }
            if (missingEntities.includes('destination')) {
                return "Where would you like to go?";
            }
            if (missingEntities.includes('origin')) {
                return "Where will you be starting from?";
            }
        }
        if (intent === 'TRACK_BUS') {
            return "Which bus would you like me to track?";
        }
    }

    return '';  // No clarification needed
}

// ============================================================================
// EXECUTION STRATEGY
// ============================================================================

export type ExecutionStrategy = 'sequential' | 'parallel' | 'adaptive';

/**
 * Determine execution strategy based on intent and agents.
 */
export function getExecutionStrategy(
    intent: IntentType,
    requiredAgents: AgentType[]
): ExecutionStrategy {
    // Track bus - mostly sequential (data → tracker)
    if (intent === 'TRACK_BUS') {
        return 'sequential';
    }

    // Find nearby - simple parallel (data + geocoding)
    if (intent === 'FIND_NEARBY') {
        return 'parallel';
    }

    // Find route - adaptive (some parallel, some sequential)
    if (intent === 'FIND_ROUTE') {
        return 'adaptive';
    }

    return 'sequential';
}
