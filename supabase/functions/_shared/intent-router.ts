/**
 * Intent Router for Multi-Agent System
 * 
 * Routes user queries to specialized endpoints based on detected intent.
 * Key principle: Each endpoint fetches ONLY the specific data it needs
 * for accurate, grounded responses.
 * 
 * Routing Strategy:
 * 1. Pattern matching for high-confidence intents (no LLM needed)
 * 2. Entity extraction (bus name, origin, destination)
 * 3. Fallback to full pipeline for ambiguous queries
 */

// ============================================================================
// ENDPOINT TYPES
// ============================================================================

export enum EndpointType {
    FIND_ROUTE = 'find-route',
    TRACK_BUS = 'track-bus',
    NEARBY_STOPS = 'get-nearby-stops',
    PROCESS_QUERY = 'process-query'  // Fallback for complex queries
}

// ============================================================================
// ROUTING INTERFACES
// ============================================================================

export interface RouteDecision {
    endpoint: EndpointType;
    confidence: number;
    extractedParams: ExtractedParams;
    reasoning: string;
}

export interface ExtractedParams {
    // For FIND_ROUTE
    origin?: string;
    destination?: string;

    // For TRACK_BUS
    busName?: string;
    busNumber?: string;
    busId?: string;

    // For NEARBY_STOPS
    latitude?: number;
    longitude?: number;
    radiusKm?: number;

    // Common
    userLocation?: { latitude: number; longitude: number };
}

// ============================================================================
// INTENT PATTERNS (Priority-ordered)
// ============================================================================

interface IntentPattern {
    endpoint: EndpointType;
    patterns: RegExp[];
    entityExtractors: EntityExtractor[];
    confidence: number;  // Base confidence for this pattern type
}

type EntityExtractor = (query: string) => Partial<ExtractedParams>;

// Bus tracking patterns - highest specificity
const TRACK_BUS_PATTERNS: RegExp[] = [
    /where\s+is\s+(bus\s+)?(\w+[\s\-]?\w*)/i,
    /track\s+(bus\s+)?(\w+[\s\-]?\w*)/i,
    /locate\s+(bus\s+)?(\w+[\s\-]?\w*)/i,
    /find\s+(bus\s+)?(\w+[\s\-]?\w*)\s+location/i,
    /(bus\s+)?(\d{2,4}[A-Za-z]?)\s+(location|position|status)/i,
    /(\w+)\s+bus\s+(kaha|kidhar|kahaan)/i,  // Hindi: where is X bus
    /(\w+)\s+bus\s+எங்கே/i,  // Tamil: where is X bus
    /bus\s+(\d{2,4}[A-Za-z]?)\s+(is\s+)?where/i,
];

// Route finding patterns
const FIND_ROUTE_PATTERNS: RegExp[] = [
    /how\s+(?:do\s+i\s+|to\s+)?(?:get|go|travel|reach)\s+(?:to\s+)?(.+)/i,
    /(?:route|way|path)\s+(?:from\s+)?(.+?)\s+to\s+(.+)/i,
    /(.+?)\s+(?:to|से)\s+(.+?)\s+(?:how|kaise|எப்படி)/i,
    /from\s+(.+?)\s+to\s+(.+)/i,
    /(.+?)\s+to\s+(.+?)\s+(?:bus|route)/i,
    /take\s+me\s+(?:from\s+)?(.+?)\s+to\s+(.+)/i,
    /which\s+bus\s+(?:goes?\s+)?(?:from\s+)?(.+?)\s+to\s+(.+)/i,
    /bus\s+(?:from\s+)?(.+?)\s+to\s+(.+)/i,
];

// Nearby stops patterns
const NEARBY_STOPS_PATTERNS: RegExp[] = [
    /(?:nearby|near|closest|nearest)\s+(?:bus\s+)?(?:stops?|station)/i,
    /(?:bus\s+)?(?:stops?|station)\s+(?:near|nearby|around)\s+(?:me|here)/i,
    /(?:stops?|station)\s+(?:within|in)\s+(\d+)\s*(?:km|meters?|m)/i,
    /what\s+(?:bus\s+)?(?:stops?|station)\s+(?:are|is)\s+(?:near|nearby)/i,
    /show\s+(?:me\s+)?(?:nearby|closest)\s+(?:bus\s+)?(?:stops?|station)/i,
];

// ============================================================================
// ENTITY EXTRACTORS
// ============================================================================

function extractBusName(query: string): Partial<ExtractedParams> {
    const result: Partial<ExtractedParams> = {};

    // Match patterns like "bus 335E", "335E", "Bus-500C"
    const busPatterns = [
        /(?:bus\s+)?(\d{2,4}[A-Za-z]?(?:[\s\-]?\w+)?)/i,
        /where\s+is\s+(?:bus\s+)?(\w+[\s\-]?\w*)/i,
        /track\s+(?:bus\s+)?(\w+[\s\-]?\w*)/i,
        /locate\s+(?:bus\s+)?(\w+[\s\-]?\w*)/i,
    ];

    for (const pattern of busPatterns) {
        const match = query.match(pattern);
        if (match && match[1]) {
            const busRef = match[1].trim();
            // Check if it looks like a bus number (contains digits)
            if (/\d/.test(busRef)) {
                result.busNumber = busRef;
                result.busName = busRef;
            } else {
                result.busName = busRef;
            }
            break;
        }
    }

    return result;
}

function extractOriginDestination(query: string): Partial<ExtractedParams> {
    const result: Partial<ExtractedParams> = {};

    // Pattern: "from X to Y"
    const fromToMatch = query.match(/(?:from\s+)?(.+?)\s+to\s+(.+?)(?:\s+(?:bus|route|how|kaise))?$/i);
    if (fromToMatch) {
        result.origin = cleanPlaceName(fromToMatch[1]);
        result.destination = cleanPlaceName(fromToMatch[2]);
        return result;
    }

    // Pattern: "how to get to X" (destination only)
    const getToMatch = query.match(/how\s+(?:do\s+i\s+|to\s+)?(?:get|go|travel|reach)\s+to\s+(.+)/i);
    if (getToMatch) {
        result.destination = cleanPlaceName(getToMatch[1]);
        return result;
    }

    // Pattern: "route to X"
    const routeToMatch = query.match(/(?:route|way|path)\s+to\s+(.+)/i);
    if (routeToMatch) {
        result.destination = cleanPlaceName(routeToMatch[1]);
        return result;
    }

    return result;
}

function extractRadius(query: string): Partial<ExtractedParams> {
    const result: Partial<ExtractedParams> = {};

    const radiusMatch = query.match(/(\d+(?:\.\d+)?)\s*(?:km|kilometer)/i);
    if (radiusMatch) {
        result.radiusKm = parseFloat(radiusMatch[1]);
    }

    const meterMatch = query.match(/(\d+)\s*(?:m|meter)/i);
    if (meterMatch) {
        result.radiusKm = parseInt(meterMatch[1]) / 1000;
    }

    return result;
}

function cleanPlaceName(name: string): string {
    return name
        .trim()
        .replace(/[?!.,]+$/, '')  // Remove trailing punctuation
        .replace(/^\s*(the|a|an)\s+/i, '')  // Remove articles
        .trim();
}

// ============================================================================
// MAIN ROUTING FUNCTION
// ============================================================================

/**
 * Route a user query to the appropriate specialized endpoint.
 * Uses pattern matching for fast, accurate routing without LLM calls.
 * 
 * @param query - The user's natural language query
 * @param userLocation - Optional user location for nearby stops
 * @returns RouteDecision with endpoint, confidence, and extracted params
 */
export function routeByIntent(
    query: string,
    userLocation?: { latitude: number; longitude: number }
): RouteDecision {
    const normalizedQuery = query.toLowerCase().trim();

    // Try TRACK_BUS first (most specific patterns)
    for (const pattern of TRACK_BUS_PATTERNS) {
        if (pattern.test(normalizedQuery)) {
            const busParams = extractBusName(query);
            if (busParams.busName || busParams.busNumber) {
                return {
                    endpoint: EndpointType.TRACK_BUS,
                    confidence: 0.9,
                    extractedParams: { ...busParams, userLocation },
                    reasoning: `Matched bus tracking pattern with bus: ${busParams.busName || busParams.busNumber}`
                };
            }
        }
    }

    // Try NEARBY_STOPS (requires user location)
    for (const pattern of NEARBY_STOPS_PATTERNS) {
        if (pattern.test(normalizedQuery)) {
            const radiusParams = extractRadius(query);
            return {
                endpoint: EndpointType.NEARBY_STOPS,
                confidence: userLocation ? 0.9 : 0.5,  // Lower if no location
                extractedParams: {
                    ...radiusParams,
                    latitude: userLocation?.latitude,
                    longitude: userLocation?.longitude,
                    userLocation
                },
                reasoning: userLocation
                    ? 'Matched nearby stops pattern with user location'
                    : 'Matched nearby stops pattern but no user location available'
            };
        }
    }

    // Try FIND_ROUTE
    for (const pattern of FIND_ROUTE_PATTERNS) {
        if (pattern.test(normalizedQuery)) {
            const routeParams = extractOriginDestination(query);
            if (routeParams.destination || routeParams.origin) {
                return {
                    endpoint: EndpointType.FIND_ROUTE,
                    confidence: routeParams.origin && routeParams.destination ? 0.9 : 0.7,
                    extractedParams: { ...routeParams, userLocation },
                    reasoning: `Matched route finding pattern: ${routeParams.origin || 'current'} → ${routeParams.destination}`
                };
            }
        }
    }

    // Check for general greetings/questions → still route to PROCESS_QUERY for AI response
    const generalPatterns = [
        /^(hi|hello|hey|hola|namaste)/i,
        /^(what|how|help|can you)/i,
        /^(thank|thanks)/i,
    ];

    for (const pattern of generalPatterns) {
        if (pattern.test(normalizedQuery)) {
            return {
                endpoint: EndpointType.PROCESS_QUERY,
                confidence: 0.8,
                extractedParams: { userLocation },
                reasoning: 'General query - requires full AI processing'
            };
        }
    }

    // Default: Route to full pipeline for complex/ambiguous queries
    return {
        endpoint: EndpointType.PROCESS_QUERY,
        confidence: 0.5,
        extractedParams: { userLocation },
        reasoning: 'No specific pattern matched - using full pipeline'
    };
}

// ============================================================================
// HELPER: Get targeted DB query hints
// ============================================================================

/**
 * Returns hints for what data to fetch based on the endpoint.
 * This enables each endpoint to fetch ONLY what it needs for accuracy.
 */
export function getDataFetchHints(endpoint: EndpointType, params: ExtractedParams): DataFetchHints {
    switch (endpoint) {
        case EndpointType.TRACK_BUS:
            return {
                needsVehicles: true,
                vehicleFilter: params.busName || params.busNumber,
                needsLocations: true,
                locationFilter: 'latest_only',
                needsStops: true,  // For nearest stop calculation
                stopsFilter: params.busId ? { vehicleId: params.busId } : undefined,
                needsUserLocation: false
            };

        case EndpointType.FIND_ROUTE:
            return {
                needsVehicles: true,
                vehicleFilter: undefined,  // Need all vehicles that service the route
                needsLocations: true,  // For live tracking + ETA
                locationFilter: 'latest_only',
                needsStops: true,
                stopsFilter: {
                    originName: params.origin,
                    destinationName: params.destination
                },
                needsUserLocation: true  // For walking distance to origin
            };

        case EndpointType.NEARBY_STOPS:
            return {
                needsVehicles: true,  // For vehicle names
                vehicleFilter: undefined,
                needsLocations: false,  // Not needed for nearby stops
                locationFilter: undefined,
                needsStops: true,
                stopsFilter: {
                    radius: params.radiusKm || 1.0,
                    center: params.userLocation
                },
                needsUserLocation: true
            };

        default:
            return {
                needsVehicles: true,
                needsLocations: true,
                needsStops: true,
                needsUserLocation: true
            };
    }
}

export interface DataFetchHints {
    needsVehicles: boolean;
    vehicleFilter?: string;
    needsLocations: boolean;
    locationFilter?: 'latest_only' | 'last_5min' | 'all';
    needsStops: boolean;
    stopsFilter?: {
        vehicleId?: string;
        originName?: string;
        destinationName?: string;
        radius?: number;
        center?: { latitude: number; longitude: number };
    };
    needsUserLocation: boolean;
}

// ============================================================================
// CONFIDENCE THRESHOLD
// ============================================================================

/**
 * Minimum confidence to use specialized endpoint.
 * Below this, fall back to full process-query pipeline.
 */
export const ROUTING_CONFIDENCE_THRESHOLD = 0.7;

/**
 * Check if routing decision should be used or fall back to full pipeline.
 */
export function shouldUseSpecializedEndpoint(decision: RouteDecision): boolean {
    return decision.confidence >= ROUTING_CONFIDENCE_THRESHOLD &&
        decision.endpoint !== EndpointType.PROCESS_QUERY;
}
