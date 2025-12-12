/**
 * Query Parser for Multi-Agent System
 * 
 * Parses natural language queries into structured intents and parameters.
 * Uses pattern matching and keyword extraction.
 */

import type { ParsedQuery, QueryIntent } from './types.ts';

// ============================================================================
// INTENT PATTERNS
// ============================================================================

interface IntentPattern {
    intent: QueryIntent;
    patterns: RegExp[];
    requiredAgents: string[];
}

const INTENT_PATTERNS: IntentPattern[] = [
    {
        intent: 'FIND_ROUTE',
        patterns: [
            /(?:how|route|way|go|get|reach|travel).*(?:from|to)/i,
            /(?:from)\s+(.+?)\s+(?:to)\s+(.+)/i,
            /(.+?)\s+(?:to)\s+(.+)/i,
            /(?:take me|navigate|directions?)/i,
        ],
        requiredAgents: ['DataAgent', 'PathAgent', 'TimeAgent', 'BusTrackerAgent']
    },
    {
        intent: 'FIND_BEST_OPTION',
        patterns: [
            /(?:best|fastest|quickest|shortest|optimal)/i,
            /(?:which bus|what bus|best bus)/i,
        ],
        requiredAgents: ['DataAgent', 'PathAgent', 'TimeAgent', 'BusTrackerAgent']
    },
    {
        intent: 'TRACK_BUS',
        patterns: [
            /(?:where|track|locate|find|position).*(?:bus|vehicle)/i,
            /(?:bus|vehicle).*(?:where|location|position)/i,
            /(?:live|real-?time).*(?:location|position|track)/i,
        ],
        requiredAgents: ['DataAgent', 'BusTrackerAgent']
    },
    {
        intent: 'CALCULATE_ETA',
        patterns: [
            /(?:when|eta|arrival|arrive|time|how long)/i,
            /(?:how many|next|coming|arriving)/i,
            /(?:minutes?|hours?)\s+(?:away|to|until)/i,
        ],
        requiredAgents: ['DataAgent', 'PathAgent', 'TimeAgent', 'BusTrackerAgent']
    },
    {
        intent: 'FIND_NEARBY',
        patterns: [
            /(?:near|nearby|closest|nearest|around)/i,
            /(?:stops?|buses?).*(?:near|close|around)/i,
            /(?:what|which).*(?:stops?|buses?).*(?:here|nearby)/i,
        ],
        requiredAgents: ['DataAgent']
    },
    {
        intent: 'GET_SCHEDULE',
        patterns: [
            /(?:schedule|timing|timetable)/i,
            /(?:first|last).*(?:bus|vehicle)/i,
        ],
        requiredAgents: ['DataAgent']
    },
];

// ============================================================================
// LOCATION EXTRACTION
// ============================================================================

// Common location patterns
const FROM_TO_PATTERN = /(?:from)\s+(.+?)\s+(?:to)\s+(.+?)(?:\s*[.?!]|$)/i;
const TO_FROM_PATTERN = /(?:to)\s+(.+?)\s+(?:from)\s+(.+?)(?:\s*[.?!]|$)/i;
const SIMPLE_TO_PATTERN = /^(.+?)\s+(?:to)\s+(.+?)(?:\s*[.?!]|$)/i;

// Bus ID patterns
const BUS_ID_PATTERNS = [
    /(?:bus|vehicle)\s*(?:id|#|number|no\.?)?\s*[:\s]*([A-Za-z0-9]+)/i,
    /([A-Za-z]{2,3}\d{2,4}[A-Za-z]?)/i, // e.g., KA01, TN02MN
];

// Stop name patterns
const STOP_PATTERNS = [
    /(?:stop|station|stand)\s+(?:called|named)?\s*[:\s]*(.+?)(?:\s*[.?!]|$)/i,
    /(?:at|near|from|to)\s+(.+?)(?:\s+stop|\s+station|\s*[.?!]|$)/i,
];

function extractLocations(query: string): { origin?: string; destination?: string } {
    // Try from...to pattern
    let match = query.match(FROM_TO_PATTERN);
    if (match) {
        return { origin: cleanLocation(match[1]), destination: cleanLocation(match[2]) };
    }

    // Try to...from pattern (reversed)
    match = query.match(TO_FROM_PATTERN);
    if (match) {
        return { origin: cleanLocation(match[2]), destination: cleanLocation(match[1]) };
    }

    // Try simple X to Y pattern
    match = query.match(SIMPLE_TO_PATTERN);
    if (match) {
        return { origin: cleanLocation(match[1]), destination: cleanLocation(match[2]) };
    }

    return {};
}

function extractBusId(query: string): string | undefined {
    for (const pattern of BUS_ID_PATTERNS) {
        const match = query.match(pattern);
        if (match) {
            return match[1].toUpperCase();
        }
    }
    return undefined;
}

function extractStopName(query: string): string | undefined {
    for (const pattern of STOP_PATTERNS) {
        const match = query.match(pattern);
        if (match) {
            return cleanLocation(match[1]);
        }
    }
    return undefined;
}

function cleanLocation(location: string): string {
    return location
        .trim()
        .replace(/^the\s+/i, '')
        .replace(/[.?!,]+$/, '')
        .trim();
}

// ============================================================================
// MAIN PARSER
// ============================================================================

/**
 * Parse a natural language query into structured form.
 */
export function parseQuery(
    query: string,
    userLat?: number,
    userLng?: number
): ParsedQuery {
    const normalizedQuery = query.trim().toLowerCase();

    // Default result
    let result: ParsedQuery = {
        intent: 'UNKNOWN',
        confidence: 0,
        parameters: {},
        requiredAgents: []
    };

    // Handle greetings
    if (/^(hi|hello|hey|good\s+(morning|afternoon|evening))/.test(normalizedQuery)) {
        return {
            intent: 'GENERAL',
            confidence: 1.0,
            parameters: {},
            requiredAgents: []
        };
    }

    // Match intent
    let maxMatchCount = 0;
    for (const intentPattern of INTENT_PATTERNS) {
        let matchCount = 0;
        for (const pattern of intentPattern.patterns) {
            if (pattern.test(query)) {
                matchCount++;
            }
        }

        if (matchCount > maxMatchCount) {
            maxMatchCount = matchCount;
            result = {
                intent: intentPattern.intent,
                confidence: Math.min(0.5 + matchCount * 0.2, 1.0),
                parameters: {},
                requiredAgents: [...intentPattern.requiredAgents]
            };
        }
    }

    // Extract parameters
    const locations = extractLocations(query);
    result.parameters.origin = locations.origin;
    result.parameters.destination = locations.destination;
    result.parameters.busId = extractBusId(query);
    result.parameters.stopName = extractStopName(query);

    // Boost confidence if we found key parameters
    if (result.parameters.origin && result.parameters.destination) {
        result.confidence = Math.min(result.confidence + 0.2, 1.0);
        if (result.intent === 'UNKNOWN') {
            result.intent = 'FIND_ROUTE';
            result.requiredAgents = ['DataAgent', 'PathAgent', 'TimeAgent', 'BusTrackerAgent'];
        }
    }

    if (result.parameters.busId && result.intent === 'UNKNOWN') {
        result.intent = 'TRACK_BUS';
        result.confidence = 0.7;
        result.requiredAgents = ['DataAgent', 'BusTrackerAgent'];
    }

    // Handle user location context
    if (userLat !== undefined && userLng !== undefined) {
        if (!result.parameters.origin && /(?:here|my location|current)/i.test(query)) {
            result.parameters.origin = 'current_location';
        }

        if (result.intent === 'UNKNOWN' && /(?:near|around|close)/i.test(query)) {
            result.intent = 'FIND_NEARBY';
            result.confidence = 0.8;
            result.requiredAgents = ['DataAgent'];
        }
    }

    return result;
}
