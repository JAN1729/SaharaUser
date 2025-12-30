/**
 * Process Query Edge Function - SIMPLIFIED 5-Layer Filtering Pipeline
 * 
 * Architecture: 6 Core Agents with Layered Filtering
 * 1. IntentAgent - AI-driven query analysis with subdividing
 * 2. DataAgent - Fetch LATEST data only (optimized)
 * 3. MemoryAgent - RAG context retrieval
 * 4. RouteAgent - Path finding + ETA (merged PathAgent + TimeAgent)
 * 5. TrackerAgent - Bus tracking
 * 6. GeocodingAgent - Place name enrichment
 * 
 * Filtering Pipeline:
 * Layer 1: AI Intent Analysis → subdivide user query
 * Layer 2: Location Filter → 3km radius from user's LATEST location
 * Layer 3: Nearest Stop Selection → KNN + alternatives
 * Layer 4: Bus Filtering → LATEST coordinates only
 * Layer 5: AI Response → Grounded, no hallucination
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient, SupabaseClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// INLINE DEFINITIONS (avoiding _shared/ import issues with Supabase bundler)
// ============================================================================

// Minimal communication hub stub (full implementation not needed for core functionality)
interface IAgentCommunicationHub {
    publish: (msg: Record<string, unknown>) => void;
    reset: () => void;
}
function createCommunicationHub(): IAgentCommunicationHub {
    return {
        publish: () => { },
        reset: () => { }
    };
}

// Message types for agent communication (inlined from agent-communication-hub.ts)
enum MessageType {
    DATA_READY = 'DATA_READY',
    DATA_UPDATED = 'DATA_UPDATED',
    INTENT_ANALYZED = 'INTENT_ANALYZED',
    ROUTE_FOUND = 'ROUTE_FOUND',
    ROUTE_NOT_FOUND = 'ROUTE_NOT_FOUND',
    LOCATION_UPDATE = 'LOCATION_UPDATE',
    BUS_TRACKED = 'BUS_TRACKED',
    MEMORY_RETRIEVED = 'MEMORY_RETRIEVED',
    PLACE_GEOCODED = 'PLACE_GEOCODED',
    WALKING_DIRECTIONS_READY = 'WALKING_DIRECTIONS_READY',
    REQUEST = 'REQUEST',
    RESPONSE = 'RESPONSE',
    ERROR = 'ERROR',
    AGENT_STARTED = 'AGENT_STARTED',
    AGENT_COMPLETED = 'AGENT_COMPLETED'
}

enum MessagePriority {
    CRITICAL = 1,
    HIGH = 2,
    NORMAL = 3,
    LOW = 4
}

// Intent router types and functions (inlined from intent-router.ts)
enum EndpointType {
    FIND_ROUTE = 'find-route',
    TRACK_BUS = 'track-bus',
    NEARBY_STOPS = 'get-nearby-stops',
    PROCESS_QUERY = 'process-query'
}

interface ExtractedParams {
    origin?: string;
    destination?: string;
    busName?: string;
    busNumber?: string;
    radiusKm?: number;
}

interface RouteDecision {
    endpoint: EndpointType;
    confidence: number;
    extractedParams: ExtractedParams;
    reasoning: string;
}

const ROUTING_PATTERNS = [
    { pattern: /(?:where|track|find|locate)\s+(?:is\s+)?(?:the\s+)?(?:bus\s+)?(.+)/i, endpoint: EndpointType.TRACK_BUS, confidence: 0.9 },
    { pattern: /nearby|near\s*me|closest|around\s*here|nearest\s*stop/i, endpoint: EndpointType.NEARBY_STOPS, confidence: 0.9 },
    { pattern: /(?:how|can|do)\s+(?:i|we)\s+(?:get|go|reach|travel)\s+(?:to\s+)?(.+)/i, endpoint: EndpointType.FIND_ROUTE, confidence: 0.85 },
    { pattern: /(.+?)\s+to\s+(.+)/i, endpoint: EndpointType.FIND_ROUTE, confidence: 0.8 },
];

function routeByIntent(query: string, userLocation?: { latitude: number; longitude: number }): RouteDecision {
    const q = query.toLowerCase().trim();
    for (const { pattern, endpoint, confidence } of ROUTING_PATTERNS) {
        const match = q.match(pattern);
        if (match) {
            const params: ExtractedParams = {};
            if (endpoint === EndpointType.TRACK_BUS && match[1]) {
                params.busName = match[1].trim();
            } else if (endpoint === EndpointType.FIND_ROUTE) {
                if (match.length >= 3) {
                    params.origin = match[1]?.trim();
                    params.destination = match[2]?.trim();
                } else if (match[1]) {
                    params.destination = match[1].trim();
                }
            }
            return { endpoint, confidence, extractedParams: params, reasoning: `Pattern matched: ${endpoint}` };
        }
    }
    return { endpoint: EndpointType.PROCESS_QUERY, confidence: 0.5, extractedParams: {}, reasoning: 'No specific pattern matched' };
}

function shouldUseSpecializedEndpoint(decision: RouteDecision): boolean {
    // DISABLED: Force all queries through full pipeline to use OpenRouter reasoning
    // The specialized endpoints don't support OpenRouter reasoning_details
    // To re-enable, uncomment the line below:
    // return decision.endpoint !== EndpointType.PROCESS_QUERY && decision.confidence >= 0.75;
    return false;
}

// Confidence tier (inlined from agent-priority.ts)
enum ConfidenceTier { HIGH = 'HIGH', MEDIUM = 'MEDIUM', LOW = 'LOW', FALLBACK = 'FALLBACK' }
function getConfidenceTier(confidence: number): ConfidenceTier {
    if (confidence >= 0.8) return ConfidenceTier.HIGH;
    if (confidence >= 0.6) return ConfidenceTier.MEDIUM;
    if (confidence >= 0.4) return ConfidenceTier.LOW;
    return ConfidenceTier.FALLBACK;
}

function shouldRetryIntentWithLLM(confidence: number, source: 'pattern' | 'llm', hasApiKey: boolean): { shouldRetry: boolean; reason: string } {
    if (source === 'llm') return { shouldRetry: false, reason: 'Already from LLM' };
    if (!hasApiKey) return { shouldRetry: false, reason: 'No API key' };
    const tier = getConfidenceTier(confidence);
    if (tier === ConfidenceTier.LOW || tier === ConfidenceTier.FALLBACK) {
        return { shouldRetry: true, reason: `Confidence is ${tier}` };
    }
    return { shouldRetry: false, reason: `Confidence is ${tier}` };
}

// ============================================================================
// CORE PRINCIPLES (User Requirements)
// ============================================================================
// 1. LATEST Data Only - Always use most recent user location & bus coordinates
// 2. 3km Radius Filtering - Standard filter from user's last known location
// 3. No Hallucination - AI must only use grounded data, never make up info
// 4. Comprehensive Intent Analysis - AI-driven query understanding
// 5. Keep RAG Memory - MemoryAgent for context retrieval
// 6. Keep Geocoding - GeocodingAgent for place names

// ============================================================================
// CONSTANTS & CONFIGURATION
// ============================================================================

const FILTER_RADIUS_KM = 3; // Standard 3km radius for stop filtering
const EARTH_RADIUS_KM = 6371;
const WALKING_SPEED_KMH = 4.5;
const DEFAULT_BUS_SPEED_KMH = 18;

// LLM Configuration - OpenRouter API with Reasoning
const OPENROUTER_API_URL = 'https://openrouter.ai/api/v1/chat/completions';
const OPENROUTER_MODEL = 'openai/gpt-oss-120b';
const OPENROUTER_HEADERS = {
    'HTTP-Referer': 'https://sahara-bus.app', // For OpenRouter leaderboard
    'X-Title': 'Sahara Bus AI Assistant'      // App name for leaderboard
};
const GEMINI_API_URL = 'https://generativelanguage.googleapis.com/v1beta/models';
const GEMINI_MODEL = 'gemini-2.5-flash';

// OpenRouter Reasoning Types - matches actual API response format
interface OpenRouterReasoningDetail {
    type: string;  // Can be 'reasoning.text', 'thought', 'summary', etc.
    text?: string;  // OpenRouter uses 'text' not 'content'
    content?: string;  // Some formats use 'content'
    format?: string;
    index?: number;
}

interface OpenRouterMessage {
    role: 'user' | 'assistant' | 'system';
    content: string;
    reasoning_details?: OpenRouterReasoningDetail[];
}

// ============================================================================
// AGENT AI HELPER - Lightweight AI for sub-agent decisions
// ============================================================================

/**
 * Lightweight AI call for sub-agent analysis.
 * Uses a faster model for quick decisions without full reasoning overhead.
 * Returns structured JSON analysis that agents can use for smarter outputs.
 */
async function callAgentAI<T>(
    agentName: string,
    systemPrompt: string,
    dataPrompt: string,
    parseAs: 'json' | 'text' = 'json'
): Promise<{ success: boolean; result: T | null; error?: string }> {
    const apiKey = Deno.env.get('OPENROUTER_API_KEY');

    if (!apiKey) {
        console.log(`[${agentName}] No API key, using rule-based logic`);
        return { success: false, result: null, error: 'No API key' };
    }

    try {
        const response = await fetch(OPENROUTER_API_URL, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${apiKey}`,
                'Content-Type': 'application/json',
                ...OPENROUTER_HEADERS
            },
            body: JSON.stringify({
                model: OPENROUTER_MODEL,
                messages: [
                    { role: 'system', content: systemPrompt },
                    { role: 'user', content: dataPrompt }
                ],
                temperature: 0.1,  // Low temperature for consistent analysis
                max_tokens: 300   // Keep responses concise for speed
            })
        });

        if (!response.ok) {
            const errText = await response.text();
            console.warn(`[${agentName}] AI call failed: ${response.status}`);
            return { success: false, result: null, error: errText.substring(0, 200) };
        }

        const data = await response.json();
        const content = data.choices[0]?.message?.content?.trim();

        if (!content) {
            return { success: false, result: null, error: 'Empty response' };
        }

        if (parseAs === 'json') {
            // Extract JSON from possible markdown code blocks
            const jsonMatch = content.match(/```(?:json)?\s*([\s\S]*?)```/) || [null, content];
            const jsonStr = jsonMatch[1] || content;
            const parsed = JSON.parse(jsonStr) as T;
            console.log(`[${agentName}] AI analysis complete`);
            return { success: true, result: parsed };
        } else {
            return { success: true, result: content as T };
        }
    } catch (e) {
        console.warn(`[${agentName}] AI error:`, e);
        return { success: false, result: null, error: e instanceof Error ? e.message : 'AI call failed' };
    }
}

// ============================================================================
// TYPES
// ============================================================================

interface Vehicle {
    id: string;
    name: string;
    vehicle_number: string;
    is_active: boolean;
}

interface RouteStop {
    id: string;
    vehicle_id: string;
    name: string;
    latitude: number;
    longitude: number;
    stop_order: number;
    distance_km?: number;
    match_rank?: number;
    match_source?: string;
}

interface LiveLocation {
    bus_id: string;
    latitude: number;
    longitude: number;
    speed: number | null;
    heading: number | null;
    timestamp: number;
    location_ts?: number; // Added to handle DB RPC response
    trip_status?: 'started' | 'stopped';
    accuracy?: number;
}

interface LocationFreshness {
    freshnessScore: number;
    status: 'live' | 'recent' | 'stale' | 'offline';
    ageSeconds: number;
}

interface UserLocation {
    latitude: number;
    longitude: number;
}

// Analyzed Intent with subdividing (Layer 1 output)
interface AnalyzedIntent {
    primaryIntent: 'FIND_ROUTE' | 'TRACK_BUS' | 'FIND_NEARBY' | 'GENERAL' | 'FIND_KSRTC_ROUTE';
    subIntents: string[];
    entities: {
        origin?: string;
        destination?: string;
        busName?: string;
        busNumber?: string;
        preferences?: string[];
    };
    confidence: number;
    reasoning: string;
}

// ========================
// KSRTC API Integration
// ========================
interface KSRTCCity {
    id: string;
    name: string;
    toCities: Map<string, string>; // destinationId -> destinationName
}

interface KSRTCRoute {
    routeId: string;
    departureTime: string;   // ISO format
    arrivalTime: string;     // ISO format
    serviceType: string;     // "SUPER FAST", "AC MULTI AXLE", etc.
    busType: string;         // "Deluxe" or empty
    routeName: string;       // "Trivandrum - Ernakulam"
    tripCode: string;        // "1430TVMPLNI"
    availableSeats: number;
    hasAC: boolean;
}

// Raw API response types
interface KSRTCCityRaw {
    ToCities: Record<string, string>;
    Name: string;
}

interface KSRTCRouteRaw {
    RouteScheduleId: string;
    DepartureTime: string;
    ArrivalTime: string;
    ServiceType: string;
    BusType: string;
    RouteName: string;
    TripCode: string;
    AvailableSeats: number;
    HasAC: number;
}

// Filtered stop within 3km (Layer 2/3 output)
interface FilteredStop {
    stop: RouteStop;
    distanceKm: number;
    distance_km?: number; // Unified with DB field
    walkTimeMinutes: number;
    vehicleName: string;
    vehicleId: string;
}

// Filtered bus with LATEST location (Layer 4 output)
interface FilteredBus {
    vehicle: Vehicle;
    latestLocation: LiveLocation;
    nearestStop: RouteStop | null;
    stopsAwayFromUser: number;
    etaMinutes: number;
    isMoving: boolean;
    ageSeconds: number;
    tripStatus?: 'started' | 'stopped' | 'unknown';
    headingDirection?: string;
    freshness?: LocationFreshness;
}

// Route result
interface RouteResult {
    vehicleId: string;
    vehicleName: string;
    vehicleNumber: string;
    originStop: string;
    destinationStop: string;
    distanceKm: number;
    etaMinutes: number;
    stopsInPath: string[];
    isLive: boolean;
    busStopsAway?: number;
    walkTimeMinutes?: number;
    walkDistanceMeters?: number;
    routeScore?: number; // Composite score for sorting (live + ETA + freshness)
    isDirectRoute?: boolean; // True if no transfers needed
}

// ============================================================================
// MULTI-HOP ROUTE TYPES (Routes with transfers)
// ============================================================================

// Single leg of a multi-hop route
interface RouteLeg {
    type: 'bus' | 'walk';
    vehicleId?: string;
    vehicleName?: string;
    vehicleNumber?: string;
    originStop: string;
    destinationStop: string;
    etaMinutes: number;
    distanceKm: number;
    stopsInPath?: string[];
    isLive?: boolean;
}

// Multi-hop route with one or more transfers
interface MultiHopRoute {
    legs: RouteLeg[];
    totalEtaMinutes: number;
    totalDistanceKm: number;
    transferCount: number;
    routeScore: number;
    summary: string; // e.g., "Bus 335E → Walk → Bus 500C"
}

// ============================================================================
// AGENTIC MAP TOOL TYPES
// ============================================================================

// Stop coordinate for map marking
interface MapStopCoordinate {
    name: string;
    lat: number;
    lng: number;
    order: number;
    isOrigin?: boolean;
    isDestination?: boolean;
}

// Bus location marker
interface MapBusLocation {
    lat: number;
    lng: number;
    heading?: number;
    isMoving?: boolean;
}

// Map bounding box for auto-zoom
interface MapBounds {
    north: number;
    south: number;
    east: number;
    west: number;
}

// Map-Action Pipeline: Viewport state sent from Android client
interface ViewportState {
    boundingBox: {
        north: number;
        south: number;
        east: number;
        west: number;
    };
    zoomLevel: number;
    centerLat: number;
    centerLng: number;
}

// Map-Action Pipeline: AI-driven map commands
interface MapCommand {
    action: 'HIGHLIGHT_PATH' | 'ZOOM_TO' | 'ADD_MARKER' | 'FOCUS_AND_HIGHLIGHT' | 'SHOW_HEATMAP' | 'CLEAR_OVERLAYS';
    target_bus_id?: string;
    target_stop_id?: string;
    coordinates?: { lat: number; lng: number };
    geoJSON?: unknown;
    reasoning_color?: string;
    message?: string;
    zoom_level?: number;
}

// Route map tool data - AI invokes this when visualization helps
interface RouteMapToolData {
    vehicleName: string;
    vehicleId: string;
    stopsToMark: MapStopCoordinate[];
    // Route polyline - ordered coordinates for drawing the bus route
    routePolyline: { lat: number; lng: number }[];
    userLocation?: { lat: number; lng: number };
    busLocation?: MapBusLocation;
    walkingPath?: { lat: number; lng: number }[];
    walkingDistanceM?: number;
    walkingTimeMin?: number;
    mapBounds: MapBounds;
    etaMinutes: number;
    distanceKm: number;
    // Include GeoJSON for advanced rendering
    geoJSON?: any;
}

// Agent tool call structure
interface AgentToolCall {
    tool: 'show_route_map' | 'show_bus_location' | 'show_nearby_stops';
    data: RouteMapToolData | Record<string, unknown>;
}

// Agent output

interface AgentOutput {
    success: boolean;
    agentName: string;
    data: Record<string, unknown>;
    message?: string;
    error?: string;
    confidence: number;
    executionTimeMs: number;
}

// Pipeline context
interface PipelineContext {
    userId: string;
    query: string;
    userLocation?: UserLocation;
    startTime: number;

    // Agent Communication Hub for inter-agent messaging
    hub: IAgentCommunicationHub;

    // Streaming callback for real-time reasoning display
    streamReasoning?: (text: string) => Promise<void>;

    // Agent Trace - lightweight A2A visibility
    agentTrace: AgentTraceEntry[];

    // Layer outputs
    intent?: AnalyzedIntent;
    filteredStops?: FilteredStop[];
    nearestStops?: FilteredStop[];
    filteredBuses?: FilteredBus[];
    bestRoute?: RouteResult;
    alternatives?: RouteResult[];

    // ============================================
    // PARALLEL ROUTE DISCOVERY OUTPUTS
    // ============================================
    allRoutes?: RouteResult[];        // ALL matching direct routes
    topRoutes?: RouteResult[];        // Top 3 recommendations
    multiHopRoutes?: MultiHopRoute[]; // Routes with transfers

    // Walking directions to nearest stop
    walkingDirections?: {
        destinationStop: string;
        distanceMeters: number;
        durationMinutes: number;
        steps: Array<{ instruction: string; distance: number }>;
        summary: string;
    };

    // Agent outputs
    agentResults: Map<string, AgentOutput>;

    // Shared data
    vehicles?: Vehicle[];
    allStops?: RouteStop[];
    latestLocations?: LiveLocation[];
    memoryContext?: MemoryContext;
    geocodedPlaces?: Record<string, string>;

    // Map-Action Pipeline: Viewport state for spatial awareness
    viewportState?: ViewportState;
    // Map-Action Pipeline: AI-generated map commands
    mapCommands?: MapCommand[];
    // Entities visible in viewport (from get-map-entities)
    visibleEntities?: {
        buses: Array<{ bus_id: string; vehicle_name: string; latitude: number; longitude: number }>;
        stops: Array<{ id: string; name: string; latitude: number; longitude: number }>;
    };

    // Geocoded origin location - when user specifies a "from" location in query
    // This takes priority over userLocation for all layer calculations
    geocodedOrigin?: {
        latitude: number;
        longitude: number;
        placeName: string;
    };

    // KSRTC Specific Data
    ksrtcRoutes?: KSRTCRoute[];
    ksrtcOrigin?: string;
    ksrtcDestination?: string;
}

// Agent Trace Entry - lightweight A2A visibility
interface AgentTraceEntry {
    agent: string;
    action: string;
    durationMs: number;
    success: boolean;
    timestamp: number;
}

// Helper to add an agent trace entry
function addAgentTrace(ctx: PipelineContext, agent: string, action: string, durationMs: number, success: boolean): void {
    ctx.agentTrace.push({
        agent,
        action,
        durationMs,
        success,
        timestamp: Date.now()
    });
}

interface MemoryContext {
    similarQueries: Array<{ query: string; intent: string; response: string; similarity: number }>;
}

// ============================================================================
// SUPABASE CLIENT
// ============================================================================

let supabase: SupabaseClient | null = null;

function getSupabase(): SupabaseClient {
    if (!supabase) {
        const url = Deno.env.get('SUPABASE_URL');
        const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
        if (!url || !key) throw new Error('Missing Supabase environment variables');
        supabase = createClient(url, key);
    }
    return supabase;
}

// ============================================================================
// MATHEMATICAL FORMULATIONS
// ============================================================================

/**
 * Haversine Distance Formula
 * d = 2R × arcsin(√(sin²((φ₂-φ₁)/2) + cos(φ₁)×cos(φ₂)×sin²((λ₂-λ₁)/2)))
 * Where: R = Earth radius, φ = latitude, λ = longitude
 */
function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const φ1 = lat1 * Math.PI / 180;
    const φ2 = lat2 * Math.PI / 180;
    const Δφ = (lat2 - lat1) * Math.PI / 180;
    const Δλ = (lng2 - lng1) * Math.PI / 180;

    const a = Math.sin(Δφ / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) ** 2;
    return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ============================================================================
// GEOCODING: Convert place names to coordinates
// ============================================================================

/**
 * Geocode a place name to coordinates using Mapbox Geocoding API.
 * Used to find the "from" location when user specifies an origin in their query.
 * 
 * @param placeName - The place name to geocode (e.g., "MG Road", "Majestic")
 * @returns Coordinates or null if geocoding fails
 */
async function geocodePlaceName(placeName: string): Promise<{ latitude: number; longitude: number } | null> {
    const accessToken = Deno.env.get('MAPBOX_ACCESS_TOKEN');
    if (!accessToken) {
        console.warn('[Geocoding] MAPBOX_ACCESS_TOKEN not configured');
        return null;
    }

    try {
        // Bias search to Karnataka/Kerala/India region for better accuracy
        // Southwest to Northeast bounding box
        const bbox = '74.0,8.0,78.5,14.0';
        const url = `https://api.mapbox.com/geocoding/v5/mapbox.places/${encodeURIComponent(placeName)}.json`;
        const params = new URLSearchParams({
            access_token: accessToken,
            country: 'IN',
            bbox: bbox,
            limit: '1',
            types: 'locality,neighborhood,place,address,poi'
        });

        console.log(`[Geocoding] Looking up: "${placeName}"`);
        const response = await fetch(`${url}?${params}`);

        if (!response.ok) {
            console.warn(`[Geocoding] API error: ${response.status}`);
            return null;
        }

        const data = await response.json();
        if (data.features && data.features.length > 0) {
            const feature = data.features[0];
            const [lng, lat] = feature.center;
            console.log(`[Geocoding] Found: "${feature.place_name}" at (${lat}, ${lng})`);
            return { latitude: lat, longitude: lng };
        }

        console.log(`[Geocoding] No results for "${placeName}"`);
        return null;
    } catch (error) {
        console.error('[Geocoding] Error:', error);
        return null;
    }
}

// ============================================================================
// KSRTC API HELPERS & CACHE
// ============================================================================

// KSRTC City Cache - 24 hour TTL
let ksrtcCityCache: Map<string, KSRTCCity> = new Map();
let ksrtcCityCacheTime = 0;
const KSRTC_CACHE_TTL = 24 * 60 * 60 * 1000; // 24 hours

// Fetch and cache KSRTC cities
async function getKSRTCCities(): Promise<Map<string, KSRTCCity>> {
    const now = Date.now();
    if (ksrtcCityCache.size > 0 && (now - ksrtcCityCacheTime) < KSRTC_CACHE_TTL) {
        return ksrtcCityCache;
    }

    try {
        const response = await fetch('https://assets.onlineksrtcswift.com/api/resource/getCities');
        if (!response.ok) throw new Error(`KSRTC Cities API error: ${response.status}`);

        const data: Record<string, KSRTCCityRaw> = await response.json();
        const cities = new Map<string, KSRTCCity>();

        for (const [id, city] of Object.entries(data)) {
            cities.set(id, {
                id,
                name: city.Name,
                toCities: new Map(Object.entries(city.ToCities))
            });
        }

        ksrtcCityCache = cities;
        ksrtcCityCacheTime = now;
        console.log(`[KSRTC] Cached ${cities.size} cities`);
        return cities;
    } catch (error) {
        console.error('[KSRTC] Failed to fetch cities:', error);
        return ksrtcCityCache; // Return stale cache if available
    }
}

// Find city by name (fuzzy match)
function findKSRTCCity(cities: Map<string, KSRTCCity>, name: string): KSRTCCity | null {
    if (!name) return null;
    const normalized = name.toLowerCase().trim();

    for (const city of cities.values()) {
        if (city.name.toLowerCase() === normalized) return city;
        // Common aliases
        if (normalized === 'kochi' && city.name.toLowerCase() === 'ernakulam') return city;
        if (normalized === 'tvm' && city.name.toLowerCase() === 'trivandrum') return city;
        if (normalized === 'ekm' && city.name.toLowerCase() === 'ernakulam') return city;
        if (normalized === 'kozhikkode' && city.name.toLowerCase() === 'kozhikode') return city;
    }

    // Partial match
    for (const city of cities.values()) {
        if (city.name.toLowerCase().includes(normalized) ||
            normalized.includes(city.name.toLowerCase())) {
            return city;
        }
    }

    return null;
}

// Search KSRTC routes
async function searchKSRTCRoutes(
    fromCityId: string,
    toCityId: string,
    fromCityName: string,
    toCityName: string,
    journeyDate?: string
): Promise<KSRTCRoute[]> {
    const date = journeyDate || new Date().toISOString().split('T')[0];

    const url = new URL('https://onlineksrtcswift.com/api/resource/searchRoutesV4');
    url.searchParams.set('fromCityID', fromCityId);
    url.searchParams.set('toCityID', toCityId);
    url.searchParams.set('fromCityName', fromCityName);
    url.searchParams.set('toCityName', toCityName);
    url.searchParams.set('journeyDate', date);
    url.searchParams.set('mode', 'oneway');

    console.log(`[KSRTC] Searching routes: ${fromCityName} to ${toCityName} on ${date}`);

    try {
        const response = await fetch(url.toString());
        if (!response.ok) throw new Error(`KSRTC Routes API error: ${response.status}`);

        const rawRoutes: KSRTCRouteRaw[] = await response.json();

        if (!Array.isArray(rawRoutes)) {
            console.warn('[KSRTC] Unexpected response format (not an array)');
            return [];
        }

        // Transform and filter - only extract needed fields
        return rawRoutes.map(r => ({
            routeId: r.RouteScheduleId,
            departureTime: r.DepartureTime,
            arrivalTime: r.ArrivalTime,
            serviceType: r.ServiceType,
            busType: r.BusType || '',
            routeName: r.RouteName,
            tripCode: r.TripCode,
            availableSeats: r.AvailableSeats,
            hasAC: r.HasAC === 1
        })).sort((a, b) =>
            new Date(a.departureTime).getTime() - new Date(b.departureTime).getTime()
        );
    } catch (error) {
        console.error('[KSRTC] Failed to search routes:', error);
        return [];
    }
}

// KSRTC Agent Function
async function findKSRTCRoutes(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();

    const origin = ctx.intent?.entities?.origin;
    const destination = ctx.intent?.entities?.destination;

    if (!origin || !destination) {
        return {
            success: false,
            agentName: 'KSRTCAgent',
            executionTimeMs: Date.now() - startTime,
            error: 'Missing origin or destination for KSRTC route search',
            data: {},
            confidence: 0
        };
    }

    try {
        const cities = await getKSRTCCities();
        const originCity = findKSRTCCity(cities, origin);
        const destCity = findKSRTCCity(cities, destination);

        if (!originCity || !destCity) {
            return {
                success: false,
                agentName: 'KSRTCAgent',
                executionTimeMs: Date.now() - startTime,
                error: `Could not find KSRTC city: ${!originCity ? origin : destination}`,
                data: {},
                confidence: 0.1
            };
        }

        // Check if destination is valid from origin
        if (!originCity.toCities.has(destCity.id)) {
            console.log(`[KSRTCAgent] No direct direct KSRTC route recorded from ${originCity.name} to ${destCity.name} in city's ToCities list, but searching anyway as fallback.`);
        }

        const routes = await searchKSRTCRoutes(
            originCity.id,
            destCity.id,
            originCity.name,
            destCity.name
        );

        ctx.ksrtcRoutes = routes;
        ctx.ksrtcOrigin = originCity.name;
        ctx.ksrtcDestination = destCity.name;

        return {
            success: true,
            agentName: 'KSRTCAgent',
            executionTimeMs: Date.now() - startTime,
            data: { routeCount: routes.length },
            confidence: routes.length > 0 ? 0.9 : 0.5
        };
    } catch (error) {
        return {
            success: false,
            agentName: 'KSRTCAgent',
            executionTimeMs: Date.now() - startTime,
            error: `KSRTC API error: ${error}`,
            data: {},
            confidence: 0
        };
    }
}

/**
 * 3km Radius Filter Predicate
 * P(stop) = { stop | d(user, stop) ≤ 3km }
 */
function isWithin3kmRadius(userLat: number, userLng: number, stopLat: number, stopLng: number): boolean {
    return haversineDistance(userLat, userLng, stopLat, stopLng) <= FILTER_RADIUS_KM;
}

/**
 * Walk Time Calculation
 * t_walk = d / v_walk (where v_walk = 4.5 km/h)
 */
function calculateWalkTime(distanceKm: number): number {
    return Math.round((distanceKm / WALKING_SPEED_KMH) * 60);
}

/**
 * ETA Calculation with Traffic Factor
 * ETA = (d / v) × 60 × f_traffic + n_stops × t_stop
 * Where: f_traffic = 1.3 during rush hours, t_stop = 0.5 min
 */
function calculateETA(distanceKm: number, numStops: number, busSpeed?: number): number {
    const speed = (busSpeed && busSpeed > 0) ? busSpeed : DEFAULT_BUS_SPEED_KMH;
    const hour = new Date().getHours();
    const isRushHour = (hour >= 8 && hour <= 10) || (hour >= 17 && hour <= 20);
    const trafficFactor = isRushHour ? 1.3 : 1.0;

    const travelTime = (distanceKm / speed) * 60 * trafficFactor;
    const stopTime = numStops * 0.5;

    return Math.round(travelTime + stopTime);
}

/**
 * Cardinal Direction from User to Target
 * θ = atan2(Δλ, Δφ) converted to 8-point compass
 */
function getDirection(fromLat: number, fromLng: number, toLat: number, toLng: number): string {
    const Δφ = toLat - fromLat;
    const Δλ = toLng - fromLng;
    const θ = Math.atan2(Δλ, Δφ) * 180 / Math.PI;
    const normalized = (θ + 360) % 360;

    if (normalized >= 337.5 || normalized < 22.5) return 'north';
    if (normalized >= 22.5 && normalized < 67.5) return 'northeast';
    if (normalized >= 67.5 && normalized < 112.5) return 'east';
    if (normalized >= 112.5 && normalized < 157.5) return 'southeast';
    if (normalized >= 157.5 && normalized < 202.5) return 'south';
    if (normalized >= 202.5 && normalized < 247.5) return 'southwest';
    if (normalized >= 247.5 && normalized < 292.5) return 'west';
    return 'northwest';
}

/**
 * Calculate location data freshness for quality assessment
 */
function calculateLocationFreshness(location: LiveLocation): LocationFreshness {
    const ageSeconds = Math.floor((Date.now() - location.timestamp) / 1000);

    if (ageSeconds < 30) return { freshnessScore: 1.0, status: 'live', ageSeconds };
    if (ageSeconds < 120) return { freshnessScore: 0.8, status: 'recent', ageSeconds };
    if (ageSeconds < 300) return { freshnessScore: 0.5, status: 'stale', ageSeconds };
    return { freshnessScore: 0.2, status: 'offline', ageSeconds };
}

/**
 * Get heading direction from degrees (0-360)
 */
function getHeadingDirection(heading: number | null): string | null {
    if (heading === null || heading === undefined) return null;
    const directions = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
    const index = Math.round(((heading % 360) + 360) % 360 / 45) % 8;
    return directions[index];
}

/**
 * Vehicle name/number pattern extraction
 */
const VEHICLE_PATTERNS = [
    /bus\s+(?:number\s+)?([A-Z]{2}\s?\d{2}\s?[A-Z]{1,2}\s?\d{4})/i,  // TN 01 AB 1234
    /(?:where\s+is\s+)?(?:bus\s+)?([A-Z]+\s*\d+)/i,  // Route 12, Bus 45
    /track\s+(?:bus\s+)?(.+?)(?:\s+bus)?$/i,  // track xyz bus
];

function extractVehicleReference(query: string): string | null {
    for (const pattern of VEHICLE_PATTERNS) {
        const match = query.match(pattern);
        if (match && match[1]) {
            return match[1].trim();
        }
    }
    return null;
}

// ============================================================================
// LAYER 1: AI INTENT ANALYSIS (Comprehensive Subdividing)
// ============================================================================

const INTENT_ANALYSIS_PROMPT = `You are the Sahara Bus AI intent analyzer. Analyze the user's query comprehensively.

INTENTS (in order of priority):
1. FIND_KSRTC_ROUTE - User specifically asks for KSRTC or Kerala state buses
   Keywords: "KSRTC", "Kerala Swift", "Kerala State bus", "Swift bus"
2. FIND_ROUTE - User wants to travel from A to B (General)
   Keywords: "to", "from", "go", "reach", "get to", "travel", "how do I", "bus to", two locations
3. TRACK_BUS - User wants to find/track a specific bus
   Keywords: "where is", "track", "find bus", "locate", bus name/number
4. FIND_NEARBY - User wants stops/buses near their location
   Keywords: "nearby", "near me", "closest", "around here"
5. GENERAL - Pure greetings only ("hi", "hello", "help")

SPATIAL GROUNDING (Map-Action Pipeline):
- If the user says "What is that?", "that bus", "the red one", "this stop" → they are referring to something ON THEIR SCREEN
- Set "requiresVisualContext": true for queries that need map entity resolution
- Deictic references like "that", "this", "there", "here", "the one" require viewport context
- Questions about visible entities: "What route is that?", "Is that bus coming?", "What's at that stop?"

SUBDIVIDING RULES:
- Extract ALL entities: origin, destination, bus name, preferences
- Identify sub-intents: shortest_route, alternative_options, fastest, cheapest
- If query mentions "shortest" or "fastest" → add to preferences
- If query mentions "alternative" or "other" → add 'alternative_options' to subIntents

OUTPUT (JSON only):
{
  "primaryIntent": "FIND_ROUTE" | "TRACK_BUS" | "FIND_NEARBY" | "GENERAL",
  "subIntents": ["shortest_route", "alternative_options", "show_map"],
  "entities": {
    "origin": "extracted origin or null",
    "destination": "extracted destination or null",
    "busName": "bus name if mentioned or null",
    "preferences": ["fastest", "shortest"]
  },
  "confidence": 0.0-1.0,
  "reasoning": "brief explanation",
  "showMap": boolean, // Set to true if query implies a visual need like "show", "map", "locate", "where"
  "requiresVisualContext": boolean // Set to true if query references visible map entities ("that", "this", etc.)
} Setting showMap to true is CRITICAL for map-worthy queries.
`;

/**
 * Safely extract and parse JSON from AI response
 * Handles: markdown code blocks, preamble text, trailing garbage
 * @returns Parsed object or null if extraction fails
 */
function safeExtractJSON<T>(content: string): { success: boolean; data?: T; error?: string } {
    if (!content || content.trim().length === 0) {
        return { success: false, error: 'Empty content' };
    }

    // Strategy 1: Try direct parse (content is already pure JSON)
    try {
        const parsed = JSON.parse(content.trim()) as T;
        return { success: true, data: parsed };
    } catch { /* Continue to other strategies */ }

    // Strategy 2: Extract from markdown code blocks (```json ... ``` or ``` ... ```)
    const codeBlockMatch = content.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (codeBlockMatch && codeBlockMatch[1]) {
        try {
            const parsed = JSON.parse(codeBlockMatch[1].trim()) as T;
            return { success: true, data: parsed };
        } catch { /* Continue */ }
    }

    // Strategy 3: Find JSON object by matching { ... } with balanced braces
    const firstBrace = content.indexOf('{');
    const lastBrace = content.lastIndexOf('}');
    if (firstBrace !== -1 && lastBrace > firstBrace) {
        const jsonCandidate = content.substring(firstBrace, lastBrace + 1);
        try {
            const parsed = JSON.parse(jsonCandidate) as T;
            return { success: true, data: parsed };
        } catch { /* Continue */ }
    }

    // Strategy 4: Try to fix common JSON issues (unescaped quotes, trailing commas)
    let cleaned = content
        .replace(/,\s*}/g, '}')  // Remove trailing commas before }
        .replace(/,\s*]/g, ']')  // Remove trailing commas before ]
        .replace(/[\x00-\x1F\x7F]/g, ' '); // Remove control characters

    // Try extracting JSON object from cleaned content
    const cleanedFirstBrace = cleaned.indexOf('{');
    const cleanedLastBrace = cleaned.lastIndexOf('}');
    if (cleanedFirstBrace !== -1 && cleanedLastBrace > cleanedFirstBrace) {
        try {
            const parsed = JSON.parse(cleaned.substring(cleanedFirstBrace, cleanedLastBrace + 1)) as T;
            return { success: true, data: parsed };
        } catch { /* Final failure */ }
    }

    return { success: false, error: `Could not extract valid JSON from: ${content.substring(0, 100)}...` };
}

// Fallback patterns for deterministic intent analysis
const INTENT_PATTERNS = [
    { pattern: /ksrtc|kerala\s*state.*bus/i, intent: 'FIND_KSRTC_ROUTE' as const, priority: 11 },
    { pattern: /(?:from|at)\s+(.+?)\s+(?:to|towards?)\s+(.+)/i, intent: 'FIND_ROUTE' as const, priority: 10 },
    { pattern: /(.+?)\s+to\s+(.+)/i, intent: 'FIND_ROUTE' as const, priority: 9 },
    { pattern: /(?:how|can|do)\s+(?:i|we)\s+(?:get|go|reach|travel)/i, intent: 'FIND_ROUTE' as const, priority: 8 },
    { pattern: /(?:bus|route|way)\s+to\s+(.+)/i, intent: 'FIND_ROUTE' as const, priority: 7 },
    { pattern: /(?:where|track|find|locate)\s+(?:is\s+)?(?:the\s+)?(?:bus\s+)?(.+)/i, intent: 'TRACK_BUS' as const, priority: 6 },
    { pattern: /nearby|near\s+me|closest|around\s+here|nearest/i, intent: 'FIND_NEARBY' as const, priority: 5 },
    { pattern: /^(?:hi|hello|hey|help)[!?.\s]*$/i, intent: 'GENERAL' as const, priority: 1 },
];

async function analyzeIntentWithAI(query: string): Promise<AnalyzedIntent> {
    const apiKey = Deno.env.get('OPENROUTER_API_KEY');

    // ========================================
    // PHASE 3: Tiered Intent Analysis with OpenRouter
    // 1. Try LLM first if available (with reasoning)
    // 2. Fall back to patterns
    // 3. Retry with LLM if pattern confidence is LOW
    // ========================================

    if (apiKey) {
        try {
            const response = await fetch(OPENROUTER_API_URL, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${apiKey}`,
                    'Content-Type': 'application/json',
                    ...OPENROUTER_HEADERS
                },
                body: JSON.stringify({
                    model: OPENROUTER_MODEL,
                    messages: [
                        { role: 'system', content: INTENT_ANALYSIS_PROMPT },
                        { role: 'user', content: `Query: "${query}"` }
                    ],
                    temperature: 0.1,
                    max_tokens: 300,
                    reasoning: { enabled: true }
                })
            });

            if (response.ok) {
                const data = await response.json();
                const message = data.choices[0]?.message;
                const content = message?.content || '';

                // Log reasoning if available
                if (message?.reasoning_details) {
                    console.log('[Layer1] AI reasoning:', message.reasoning_details.length, 'steps');
                }

                const parsed = JSON.parse(content);

                const result: AnalyzedIntent = {
                    primaryIntent: parsed.primaryIntent || 'GENERAL',
                    subIntents: parsed.subIntents || [],
                    entities: parsed.entities || {},
                    confidence: parsed.confidence || 0.7,
                    reasoning: `[AI] ${parsed.reasoning || ''}`
                };

                // Store map trigger decision in subIntents for simple propagation
                if (parsed.showMap === true && !result.subIntents.includes('show_map')) {
                    result.subIntents.push('show_map');
                }

                // Log confidence tier
                const tier = getConfidenceTier(result.confidence);
                console.log(`[Layer1] AI intent: ${result.primaryIntent} (${tier}, ${result.confidence.toFixed(2)})`);

                return result;
            }
        } catch (error) {
            console.warn('[Layer1] AI intent analysis failed, using fallback:', error);
        }
    }

    // Deterministic fallback with pattern matching
    const patternResult = analyzeIntentWithPatterns(query);
    const patternTier = getConfidenceTier(patternResult.confidence);

    console.log(`[Layer1] Pattern intent: ${patternResult.primaryIntent} (${patternTier}, ${patternResult.confidence.toFixed(2)})`);

    // Check if we should retry with LLM for low-confidence patterns
    const retryDecision = shouldRetryIntentWithLLM(
        patternResult.confidence,
        'pattern',
        !!apiKey
    );

    if (retryDecision.shouldRetry && apiKey) {
        console.log(`[Layer1] ${retryDecision.reason} - retrying with LLM`);
        try {
            // Retry with more explicit prompt for ambiguous queries
            const retryResponse = await fetch(OPENROUTER_API_URL, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${apiKey}`,
                    'Content-Type': 'application/json',
                    ...OPENROUTER_HEADERS
                },
                body: JSON.stringify({
                    model: OPENROUTER_MODEL,
                    messages: [
                        { role: 'system', content: INTENT_ANALYSIS_PROMPT + '\n\nNote: This is a retry for an ambiguous query. Be more careful with entity extraction.' },
                        { role: 'user', content: `Query: "${query}"` }
                    ],
                    temperature: 0.2,
                    max_tokens: 400,
                    reasoning: { enabled: true }
                })
            });

            if (retryResponse.ok) {
                const data = await retryResponse.json();
                const content = data.choices[0]?.message?.content || '';
                const parsed = JSON.parse(content);

                const retryResult: AnalyzedIntent = {
                    primaryIntent: parsed.primaryIntent || patternResult.primaryIntent,
                    subIntents: parsed.subIntents || patternResult.subIntents,
                    entities: parsed.entities || patternResult.entities,
                    confidence: Math.max(parsed.confidence || 0.6, patternResult.confidence),
                    reasoning: `[AI-Retry] ${parsed.reasoning || patternResult.reasoning}`
                };

                console.log(`[Layer1] AI retry improved confidence: ${retryResult.confidence.toFixed(2)}`);
                return retryResult;
            }
        } catch (retryError) {
            console.warn('[Layer1] AI retry failed, using pattern result');
        }
    }

    return patternResult;
}

function analyzeIntentWithPatterns(query: string): AnalyzedIntent {
    const q = query.toLowerCase().trim();

    // Check for vehicle reference first (high priority for tracking)
    const vehicleRef = extractVehicleReference(query);
    if (vehicleRef && /where|track|find|locate|status/i.test(q)) {
        return {
            primaryIntent: 'TRACK_BUS',
            subIntents: [],
            entities: { busName: vehicleRef },
            confidence: 0.9,
            reasoning: '[Pattern] Vehicle reference detected with tracking intent'
        };
    }

    for (const { pattern, intent, priority } of INTENT_PATTERNS.sort((a, b) => b.priority - a.priority)) {
        const match = q.match(pattern);
        if (match) {
            const entities: AnalyzedIntent['entities'] = {};

            if (intent === 'FIND_ROUTE' && match.length >= 3) {
                entities.origin = match[1]?.trim();
                entities.destination = match[2]?.replace(/[?.!]$/g, '').trim();
            } else if (intent === 'TRACK_BUS' && match[1]) {
                entities.busName = match[1]?.trim();
            }

            // Extract preferences
            if (/shortest|quickest|fastest/i.test(q)) {
                entities.preferences = ['fastest'];
            }

            // Higher confidence for specific patterns
            const confidence = priority >= 8 ? 0.85 : priority >= 5 ? 0.7 : 0.55;

            // Trigger map for most patterns automatically
            const subIntents = /alternative|other|different/i.test(q) ? ['alternative_options'] : [];
            if (/show|map|where|track|locate|find/i.test(q)) {
                subIntents.push('show_map');
            }

            return {
                primaryIntent: intent,
                subIntents,
                entities,
                confidence,
                reasoning: `[Pattern] Priority ${priority} match`
            };
        }
    }

    // Default: try to extract destination from any remaining words
    const words = q.split(/\s+/).filter(w => w.length > 2 && !/^(how|can|i|do|the|a|to|from|get|go|is)$/i.test(w));
    if (words.length > 0) {
        return {
            primaryIntent: 'FIND_ROUTE',
            subIntents: [],
            entities: { destination: words.join(' ') },
            confidence: 0.35,  // Below LOW threshold - will trigger LLM retry
            reasoning: '[Fallback] Word extraction - low confidence'
        };
    }

    return {
        primaryIntent: 'GENERAL',
        subIntents: [],
        entities: {},
        confidence: 0.5,
        reasoning: '[Fallback] Default'
    };
}

// ============================================================================
// LAYER 2: LOCATION FILTER (3km Radius)
// ============================================================================

/**
 * Filter all stops to only those within 3km of user's LATEST location
 * Formula: FilteredStops = { s ∈ AllStops | d(user, s) ≤ 3km }
 */
function filterStopsBy3kmRadius(
    userLat: number,
    userLng: number,
    stops: RouteStop[],
    vehicles: Vehicle[]
): FilteredStop[] {
    const vehicleMap = new Map(vehicles.map(v => [v.id, v]));
    const filtered: FilteredStop[] = [];

    for (const stop of stops) {
        const distanceKm = haversineDistance(userLat, userLng, stop.latitude, stop.longitude);

        if (distanceKm <= FILTER_RADIUS_KM) {
            const vehicle = vehicleMap.get(stop.vehicle_id);
            filtered.push({
                stop,
                distanceKm: Math.round(distanceKm * 1000) / 1000,
                walkTimeMinutes: calculateWalkTime(distanceKm),
                vehicleName: vehicle?.name || 'Unknown',
                vehicleId: stop.vehicle_id
            });
        }
    }

    return filtered;
}

// ============================================================================
// LAYER 3: NEAREST STOP SELECTION (KNN)
// ============================================================================

/**
 * K-Nearest Neighbors Algorithm
 * Find K nearest stops to user, sorted by distance
 */
function knnNearestStops(filteredStops: FilteredStop[], k: number = 5): FilteredStop[] {
    return [...filteredStops]
        .sort((a, b) => a.distanceKm - b.distanceKm)
        .slice(0, k);
}

/**
 * Find alternative stops based on intent
 * If user wants alternatives, return stops serving different routes
 */
function findAlternativeStops(
    nearestStops: FilteredStop[],
    intent: AnalyzedIntent
): FilteredStop[] {
    if (!intent.subIntents.includes('alternative_options')) {
        return [];
    }

    // Get unique vehicle IDs from top 3 nearest
    const topVehicleIds = new Set(nearestStops.slice(0, 3).map(s => s.vehicleId));

    // Find stops from DIFFERENT vehicles as alternatives
    return nearestStops
        .filter(s => !topVehicleIds.has(s.vehicleId))
        .slice(0, 3);
}

// ============================================================================
// LAYER 4: BUS FILTERING (LATEST Coordinates Only)
// ============================================================================

/**
 * Get ONLY the LATEST location for each bus
 * Uses timestamp to ensure freshness
 */
function getLatestBusLocations(locations: LiveLocation[]): Map<string, LiveLocation> {
    const latestMap = new Map<string, LiveLocation>();

    for (const loc of locations) {
        const existing = latestMap.get(loc.bus_id);
        if (!existing || loc.timestamp > existing.timestamp) {
            latestMap.set(loc.bus_id, loc);
        }
    }

    return latestMap;
}

/**
 * Filter buses that pass through the selected stops
 * Only uses LATEST coordinates at time of asking
 */
function filterBusesByStops(
    nearestStops: FilteredStop[],
    vehicles: Vehicle[],
    latestLocations: Map<string, LiveLocation>,
    allStops: RouteStop[]
): FilteredBus[] {
    const now = Date.now();
    const vehicleIdsNeeded = new Set(nearestStops.map(s => s.vehicleId));
    const filtered: FilteredBus[] = [];

    for (const vehicle of vehicles) {
        if (!vehicleIdsNeeded.has(vehicle.id)) continue;

        const latestLocation = latestLocations.get(vehicle.id);
        if (!latestLocation) continue;

        // Find nearest stop to current bus position
        const vehicleStops = allStops
            .filter(s => s.vehicle_id === vehicle.id)
            .sort((a, b) => a.stop_order - b.stop_order);

        let nearestStop: RouteStop | null = null;
        let minDist = Infinity;

        for (const stop of vehicleStops) {
            const dist = haversineDistance(
                latestLocation.latitude, latestLocation.longitude,
                stop.latitude, stop.longitude
            );
            if (dist < minDist) {
                minDist = dist;
                nearestStop = stop;
            }
        }

        // Calculate stops away from user's nearest stop for this vehicle
        const userStop = nearestStops.find(ns => ns.vehicleId === vehicle.id);
        const stopsAway = nearestStop && userStop
            ? Math.abs(userStop.stop.stop_order - nearestStop.stop_order)
            : 0;

        const ageSeconds = Math.floor((now - latestLocation.timestamp) / 1000);
        const isMoving = (latestLocation.speed || 0) > 2;

        filtered.push({
            vehicle,
            latestLocation,
            nearestStop,
            stopsAwayFromUser: stopsAway,
            etaMinutes: calculateETA(minDist, stopsAway, latestLocation.speed || undefined),
            isMoving,
            ageSeconds
        });
    }

    // Sort by ETA (closest arriving first)
    return filtered.sort((a, b) => a.etaMinutes - b.etaMinutes);
}

// ============================================================================
// LAYER 5: AI RESPONSE (Grounded, No Hallucination)
// ============================================================================

const GROUNDED_RESPONSE_PROMPT = `You are **Sahara** - a friendly, conversational bus travel assistant.

## YOUR PERSONA
- Warm and patient - like a helpful local friend who knows the bus system
- Conversational but focused on helping the user travel
- Reassuring about timing, walking distances, and connections

## ABSOLUTE GROUNDING RULES (CRITICAL - NEVER VIOLATE)
1. **ONLY speak from PROVIDED DATA** - NEVER invent routes, buses, stops, or times
2. **If no route exists in the data, say so clearly** - "I don't have a direct route for that in my database"
3. **Do NOT hallucinate or assume** - if information is missing, honestly say "I don't have that information"
4. **Present ONLY what is available** - every bus name, stop, and time must come from the data below
5. **When data is stale or uncertain, say so** - "This data is about X minutes old"

## RESPONSE STRUCTURE
Start with a warm acknowledgment, then present routes:

**🎯 Top Recommendations** (if routes found):
1️⃣ **[Bus Name]** - [Why it's good: fastest/fewest stops/live tracking]
   📍 [Origin] → [Destination] | ⏱️ ~[ETA] min | 🚌 [Live/Offline status]

2️⃣ **[Bus Name]** (if available)
   📍 [Origin] → [Destination] | ⏱️ ~[ETA] min

3️⃣ **[Bus A → Bus B]** (if transfer route available)
   📍 [Origin] → [Transfer] → [Destination] | ⏱️ ~[Total] min

**📋 All Available Options:**
List any additional routes briefly.

## WHEN NO ROUTES FOUND
- Say clearly: "I don't have a route between [A] and [B] in my database."
- Do NOT make up alternatives
- Can suggest checking nearby stops IF they are in the data

## INDICATORS
🚌 = bus | 📍 = stop | ⏱️ = time | 🚶 = walk | 🟢 = live | 🔴 = offline | 🌊 = KSRTC Swift

Remember: If it's NOT in the PROVIDED DATA below, you CANNOT mention it. Highlight KSRTC Swift (Kerala State RTC) buses clearly as they are premium inter-city options.`;


async function generateGroundedResponse(
    query: string,
    ctx: PipelineContext
): Promise<string> {
    // Build grounded context from pipeline data
    const groundedData: string[] = ['=== PROVIDED DATA (Use ONLY this) ==='];

    // ========================================
    // ROUTE DATA - All matching routes for FIND_ROUTE intent
    // ========================================
    if (ctx.intent?.primaryIntent === 'FIND_ROUTE') {
        // Top 3 Recommendations
        if (ctx.topRoutes && ctx.topRoutes.length > 0) {
            groundedData.push(`\n🎯 TOP 3 RECOMMENDED ROUTES:`);
            ctx.topRoutes.forEach((route, i) => {
                groundedData.push(`\n  ${i + 1}. "${route.vehicleName}" (${route.vehicleNumber})`);
                groundedData.push(`     Path: ${route.originStop} → ${route.destinationStop}`);
                groundedData.push(`     ETA: ${route.etaMinutes} min | Distance: ${route.distanceKm} km | Stops: ${route.stopsInPath.length}`);
                groundedData.push(`     Status: ${route.isLive ? 'LIVE TRACKING' : 'Scheduled'}`);
                if (route.isLive && route.busStopsAway !== undefined) {
                    groundedData.push(`     Bus Position: ${route.busStopsAway} stops away from pickup`);
                }
                if (route.walkTimeMinutes) {
                    groundedData.push(`     Walk to stop: ${route.walkDistanceMeters}m (${route.walkTimeMinutes} min)`);
                }
            });
        }

        // All other direct routes
        if (ctx.allRoutes && ctx.allRoutes.length > 3) {
            groundedData.push(`\n📋 ALL AVAILABLE DIRECT ROUTES (${ctx.allRoutes.length} total):`);
            ctx.allRoutes.slice(3).forEach(route => {
                groundedData.push(`  - ${route.vehicleName}: ${route.originStop} → ${route.destinationStop}, ${route.etaMinutes} min, ${route.distanceKm} km ${route.isLive ? '🟢' : ''}`);
            });
        }

        // Multi-hop transfer routes
        if (ctx.multiHopRoutes && ctx.multiHopRoutes.length > 0) {
            groundedData.push(`\n🔄 TRANSFER ROUTES (with 1 connection):`);
            ctx.multiHopRoutes.forEach((route, i) => {
                groundedData.push(`  ${i + 1}. ${route.summary}`);
                groundedData.push(`     Total: ${route.totalEtaMinutes} min | ${route.totalDistanceKm} km`);
                route.legs.forEach(leg => {
                    if (leg.type === 'bus') {
                        groundedData.push(`     🚌 ${leg.vehicleName}: ${leg.originStop} → ${leg.destinationStop} (${leg.etaMinutes} min)`);
                    } else {
                        groundedData.push(`     🚶 Walk: ${leg.originStop} → ${leg.destinationStop} (${leg.etaMinutes} min)`);
                    }
                });
            });
        }

        // Summary stats
        const totalDirectRoutes = ctx.allRoutes?.length || 0;
        const totalTransferRoutes = ctx.multiHopRoutes?.length || 0;
        const totalKSRTCRoutes = ctx.ksrtcRoutes?.length || 0;

        if (totalDirectRoutes === 0 && totalTransferRoutes === 0 && totalKSRTCRoutes === 0) {
            groundedData.push(`\n⚠️ NO ROUTES FOUND in database or KSRTC for this journey.`);
        } else {
            groundedData.push(`\n📊 SUMMARY: ${totalDirectRoutes} direct routes, ${totalTransferRoutes} transfer routes, ${totalKSRTCRoutes} KSRTC routes found`);
        }
    }

    // ========================================
    // KSRTC ROUTE DATA (Kerala State RTC)
    // ========================================
    if (ctx.ksrtcRoutes && ctx.ksrtcRoutes.length > 0) {
        groundedData.push(`\n🌊 KSRTC SWIFT ROUTES (Kerala State RTC):`);
        groundedData.push(`   Journey: ${ctx.ksrtcOrigin} → ${ctx.ksrtcDestination}`);
        groundedData.push(`   Total Buses: ${ctx.ksrtcRoutes.length} (showing top options)\n`);

        // Show up to 10 routes for variety
        ctx.ksrtcRoutes.slice(0, 10).forEach((route, i) => {
            const dep = new Date(route.departureTime);
            const arr = new Date(route.arrivalTime);

            // Indian Timezone (IST) formatting
            const timeOptions: Intl.DateTimeFormatOptions = {
                hour: '2-digit',
                minute: '2-digit',
                hour12: true,
                timeZone: 'Asia/Kolkata'
            };

            const depStr = dep.toLocaleTimeString('en-IN', timeOptions);
            const arrStr = arr.toLocaleTimeString('en-IN', timeOptions);

            const durationMs = arr.getTime() - dep.getTime();
            const hours = Math.floor(durationMs / (1000 * 60 * 60));
            const mins = Math.floor((durationMs % (1000 * 60 * 60)) / (1000 * 60));

            groundedData.push(`  ${i + 1}. "${route.serviceType}" ${route.hasAC ? '🟢 AC' : '⚪ NAC'}`);
            groundedData.push(`     Route: ${route.routeName}`);
            groundedData.push(`     Schedule: ${depStr} → ${arrStr} (${hours}h ${mins}m)`);
            groundedData.push(`     Seats Available: ${route.availableSeats}`);
            groundedData.push(`     Trip Code: ${route.tripCode}`);
            if (route.busType) groundedData.push(`     Bus Model: ${route.busType}`);
        });

        if (ctx.ksrtcRoutes.length > 10) {
            groundedData.push(`  ... and ${ctx.ksrtcRoutes.length - 10} more inter-city schedules available.`);
        }
    }

    // Nearby stops - only include for FIND_NEARBY or FIND_ROUTE intents
    if (ctx.nearestStops && ctx.nearestStops.length > 0 &&
        (ctx.intent?.primaryIntent === 'FIND_NEARBY' || ctx.intent?.primaryIntent === 'FIND_ROUTE')) {
        groundedData.push(`\nNEARBY STOPS (within 3km):`);
        for (const ns of ctx.nearestStops.slice(0, 3)) {
            groundedData.push(`  - ${ns.stop.name} (${ns.vehicleName}): ${(ns.distanceKm * 1000).toFixed(0)}m, ${ns.walkTimeMinutes} min walk`);
        }
    }

    // Tracked bus with enhanced data
    if (ctx.filteredBuses && ctx.filteredBuses.length > 0 && ctx.intent?.primaryIntent === 'TRACK_BUS') {
        const bus = ctx.filteredBuses[0];
        const freshness = calculateLocationFreshness(bus.latestLocation);
        const headingDir = getHeadingDirection(bus.latestLocation.heading);

        groundedData.push(`\nTRACKED BUS:`);
        groundedData.push(`  Bus: "${bus.vehicle.name}" (${bus.vehicle.vehicle_number})`);
        groundedData.push(`  Status: ${bus.isMoving ? 'MOVING' : 'STOPPED'} at ${bus.latestLocation.speed || 0} km/h`);
        if (headingDir) {
            groundedData.push(`  Heading: ${headingDir}`);
        }
        groundedData.push(`  Trip Status: ${bus.latestLocation.trip_status || 'unknown'}`);
        groundedData.push(`  Near: ${bus.nearestStop?.name || 'Unknown'}`);
        groundedData.push(`  Data Freshness: ${freshness.status.toUpperCase()} (${freshness.ageSeconds}s ago)`);
        groundedData.push(`  Confidence: ${Math.round(freshness.freshnessScore * 100)}%`);
    }

    // Geocoded places
    if (ctx.geocodedPlaces) {
        if (ctx.geocodedPlaces.userLocation) {
            groundedData.push(`\nUSER LOCATION: ${ctx.geocodedPlaces.userLocation}`);
        }
    }

    // Walking directions - only include for FIND_ROUTE or FIND_NEARBY intents
    if (ctx.walkingDirections &&
        (ctx.intent?.primaryIntent === 'FIND_ROUTE' || ctx.intent?.primaryIntent === 'FIND_NEARBY')) {
        groundedData.push(`\nWALKING DIRECTIONS TO BUS STOP:`);
        groundedData.push(`  Destination: ${ctx.walkingDirections.destinationStop}`);
        groundedData.push(`  Distance: ${ctx.walkingDirections.distanceMeters}m`);
        groundedData.push(`  Duration: ${ctx.walkingDirections.durationMinutes} min walk`);
        groundedData.push(`  Summary: ${ctx.walkingDirections.summary}`);
        if (ctx.walkingDirections.steps && ctx.walkingDirections.steps.length > 0) {
            groundedData.push(`  Steps:`);
            ctx.walkingDirections.steps.slice(0, 5).forEach((step, i) => {
                groundedData.push(`    ${i + 1}. ${step.instruction} (${step.distance}m)`);
            });
            if (ctx.walkingDirections.steps.length > 5) {
                groundedData.push(`    ... and ${ctx.walkingDirections.steps.length - 5} more steps`);
            }
        }
    }

    // Arrival predictions - only include for TRACK_BUS or FIND_ROUTE intents
    const predictions = (ctx as unknown as { arrivalPredictions?: ArrivalPrediction[] }).arrivalPredictions;
    if (predictions && predictions.length > 0 &&
        (ctx.intent?.primaryIntent === 'TRACK_BUS' || ctx.intent?.primaryIntent === 'FIND_ROUTE')) {
        groundedData.push(`\nBUS ARRIVAL PREDICTIONS:`);
        for (const pred of predictions.slice(0, 3)) {
            const statusIcon = pred.isApproaching ? '🟢 APPROACHING' : '';
            groundedData.push(`  - ${pred.vehicleName}: ~${pred.predictedArrivalMinutes} min to ${pred.stopName} ${statusIcon}`);
            groundedData.push(`    Confidence: ${Math.round(pred.confidence * 100)}% (${pred.basedOn})`);
        }
    }

    groundedData.push('\n=== END DATA ===');
    groundedData.push(`\nUser Query: "${query}"`);
    groundedData.push('\nGenerate a helpful, natural response using ONLY the data above. Be conversational but concise. Use bullet points for key facts.');

    // Layer 5: AI Response Generation using OpenRouter exclusively
    const openRouterKey = Deno.env.get('OPENROUTER_API_KEY');

    if (openRouterKey) {
        try {
            const response = await fetch(OPENROUTER_API_URL, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${openRouterKey}`,
                    'Content-Type': 'application/json',
                    ...OPENROUTER_HEADERS
                },
                body: JSON.stringify({
                    model: OPENROUTER_MODEL,
                    messages: [
                        { role: 'system', content: GROUNDED_RESPONSE_PROMPT },
                        { role: 'user', content: groundedData.join('\n') }
                    ],
                    temperature: 0.2,
                    max_tokens: 500,
                    reasoning: { enabled: true }
                })
            });

            if (response.ok) {
                const data = await response.json();
                const message = data.choices[0]?.message;

                console.log('[Layer5] OpenRouter response status:', response.status);
                console.log('[Layer5] OpenRouter message keys:', message ? Object.keys(message) : 'no message');

                // Stream reasoning details in real-time if callback is available
                if (message?.reasoning_details && Array.isArray(message.reasoning_details)) {
                    console.log('[Layer5] OpenRouter reasoning:', message.reasoning_details.length, 'steps');

                    // Stream each reasoning step immediately to the client
                    for (const detail of message.reasoning_details) {
                        const reasoningText = detail.text || detail.content || '';
                        if (reasoningText && reasoningText.length > 5) {
                            // Extract readable portion
                            const shortText = reasoningText.split('.')[0].trim();
                            if (shortText.length > 5 && shortText.length < 200) {
                                console.log('[Layer5] Streaming reasoning:', shortText.substring(0, 100));
                                if (ctx.streamReasoning) {
                                    await ctx.streamReasoning(shortText + '...');
                                }
                            } else if (reasoningText.length < 200) {
                                console.log('[Layer5] Streaming reasoning:', reasoningText.substring(0, 100));
                                if (ctx.streamReasoning) {
                                    await ctx.streamReasoning(reasoningText);
                                }
                            }
                        }
                    }

                    // Also store for final response
                    (ctx as unknown as { reasoningDetails: OpenRouterReasoningDetail[] }).reasoningDetails = message.reasoning_details;
                } else {
                    console.log('[Layer5] No reasoning_details in response');
                }

                return message?.content?.trim() || generateFallbackResponse(ctx);
            } else {
                const errorText = await response.text();
                console.error('[Layer5] OpenRouter error:', response.status, errorText.substring(0, 500));
            }
        } catch (e) {
            console.warn('[Layer5] OpenRouter failed, using fallback:', e);
        }
    } else {
        console.warn('[Layer5] No OPENROUTER_API_KEY configured, using fallback');
    }

    return generateFallbackResponse(ctx);
}

function generateFallbackResponse(ctx: PipelineContext): string {
    if (ctx.bestRoute) {
        const r = ctx.bestRoute;
        let msg = `🚌 **${r.vehicleName}** • ${r.distanceKm} km • ~${r.etaMinutes} min`;
        if (r.walkTimeMinutes) {
            msg += `\n🚶 Walk ${r.walkDistanceMeters}m to ${r.originStop}`;
        }
        if (r.isLive && r.busStopsAway !== undefined) {
            msg += `\n📍 Bus is ${r.busStopsAway} stops away`;
        }
        return msg;
    }

    if (ctx.nearestStops && ctx.nearestStops.length > 0) {
        const nearest = ctx.nearestStops[0];
        let msg = `📍 Nearest stop: **${nearest.stop.name}** (${nearest.vehicleName})\n🚶 ${(nearest.distanceKm * 1000).toFixed(0)}m away, ${nearest.walkTimeMinutes} min walk`;

        // Add walking directions if available
        if (ctx.walkingDirections && ctx.walkingDirections.steps.length > 0) {
            msg += '\n\n**Walking Directions:**';
            ctx.walkingDirections.steps.slice(0, 3).forEach((step, i) => {
                msg += `\n${i + 1}. ${step.instruction} (${step.distance}m)`;
            });
        }
        return msg;
    }
    return 'I could not find the information you requested. Please try again with more details.';
}

// ============================================================================
// AGENTIC MAP TOOL BUILDER (Delegates to get-map-data Edge Function)
// ============================================================================

/**
 * Build map tool call by delegating to the dedicated get-map-data Edge Function.
 * This is the MCP-style approach: process-query orchestrates, get-map-data executes.
 * 
 * Returns null if no visualization is needed.
 */
async function buildMapToolCall(ctx: PipelineContext): Promise<AgentToolCall | null> {
    const intent = ctx.intent?.primaryIntent;

    // Only show map for route/tracking/nearby intents
    if (!intent || intent === 'GENERAL') {
        return null;
    }

    // Check if map is explicitly or implicitly requested
    const isMapRequested = ctx.intent?.subIntents.includes('show_map') ||
        ctx.bestRoute ||
        (ctx.filteredBuses && ctx.filteredBuses.length > 0) ||
        (intent === 'FIND_NEARBY' && ctx.nearestStops && ctx.nearestStops.length > 0);

    if (!isMapRequested) {
        return null;
    }

    try {
        const supabaseUrl = Deno.env.get('SUPABASE_URL');
        const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

        if (!supabaseUrl || !serviceKey) {
            console.warn('[MapTool] Missing Supabase config, falling back to inline logic');
            return buildMapToolCallFallback(ctx);
        }

        // Determine vehicle ID(s) to pass
        const vehicleIds: string[] = [];
        if (ctx.bestRoute?.vehicleId) {
            vehicleIds.push(ctx.bestRoute.vehicleId);
        }
        if (ctx.filteredBuses && ctx.filteredBuses.length > 0) {
            for (const bus of ctx.filteredBuses) {
                if (!vehicleIds.includes(bus.vehicle.id)) {
                    vehicleIds.push(bus.vehicle.id);
                }
            }
        }
        if (ctx.nearestStops && ctx.nearestStops.length > 0) {
            for (const ns of ctx.nearestStops) {
                if (!vehicleIds.includes(ns.vehicleId)) {
                    vehicleIds.push(ns.vehicleId);
                }
            }
        }

        // Build request for get-map-data
        const mapRequest = {
            vehicleIds: vehicleIds.length > 0 ? vehicleIds : undefined,
            userLocation: ctx.userLocation ? {
                lat: ctx.userLocation.latitude,
                lng: ctx.userLocation.longitude
            } : undefined,
            busLocation: ctx.filteredBuses?.[0]?.latestLocation ? {
                lat: ctx.filteredBuses[0].latestLocation.latitude,
                lng: ctx.filteredBuses[0].latestLocation.longitude,
                heading: ctx.filteredBuses[0].latestLocation.heading || undefined
            } : undefined,
            intent,
            origin: ctx.intent?.entities?.origin,
            destination: ctx.intent?.entities?.destination
        };

        console.log('[MapTool] Calling get-map-data with', vehicleIds.length, 'vehicles');

        const response = await fetch(`${supabaseUrl}/functions/v1/get-map-data`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'apikey': serviceKey,
                'Authorization': `Bearer ${serviceKey}`
            },
            body: JSON.stringify(mapRequest)
        });

        if (!response.ok) {
            console.error('[MapTool] get-map-data failed:', response.status);
            return buildMapToolCallFallback(ctx);
        }

        const mapPayload = await response.json();
        console.log('[MapTool] get-map-data returned:', mapPayload.mapType, 'with', mapPayload.stops?.length, 'stops');

        // Determine tool name based on map type
        let toolName: 'show_route_map' | 'show_bus_location' | 'show_nearby_stops' = 'show_route_map';
        if (mapPayload.mapType === 'tracking') {
            toolName = 'show_bus_location';
        } else if (mapPayload.mapType === 'nearby') {
            toolName = 'show_nearby_stops';
        }

        // Transform MapPayload to RouteMapToolData format for compatibility
        const toolData: RouteMapToolData = {
            vehicleName: mapPayload.vehicleInfo?.name || 'Bus',
            vehicleId: mapPayload.vehicleInfo?.number || '',
            stopsToMark: (mapPayload.stops || []).map((s: any) => ({
                name: s.name,
                lat: s.lat,
                lng: s.lng,
                order: s.order,
                isOrigin: s.isOrigin,
                isDestination: s.isDestination
            })),
            routePolyline: mapPayload.polyline || [],
            userLocation: mapPayload.userLocation,
            busLocation: mapPayload.busLocation ? {
                lat: mapPayload.busLocation.lat,
                lng: mapPayload.busLocation.lng,
                heading: mapPayload.busLocation.heading,
                isMoving: true
            } : undefined,
            mapBounds: mapPayload.bounds,
            etaMinutes: 0,
            distanceKm: 0,
            // Include GeoJSON for advanced rendering
            geoJSON: mapPayload.geoJSON
        };

        return { tool: toolName, data: toolData };

    } catch (error) {
        console.error('[MapTool] Error calling get-map-data:', error);
        return buildMapToolCallFallback(ctx);
    }
}

/**
 * Fallback: Build map tool call inline if get-map-data Edge Function is unavailable.
 * This preserves backward compatibility.
 */
function buildMapToolCallFallback(ctx: PipelineContext): AgentToolCall | null {
    const intent = ctx.intent?.primaryIntent;

    // FIND_ROUTE fallback
    if (intent === 'FIND_ROUTE' && ctx.nearestStops && ctx.nearestStops.length > 0) {
        const stops = ctx.nearestStops;
        const vehicleId = stops[0]?.vehicleId || '';
        const vehicleName = stops[0]?.vehicleName || 'Bus';

        const vehicleStops = (ctx.allStops || [])
            .filter(s => s.vehicle_id === vehicleId)
            .sort((a, b) => a.stop_order - b.stop_order);

        if (vehicleStops.length === 0) return null;

        const stopsToMark: MapStopCoordinate[] = vehicleStops.map(s => ({
            name: s.name,
            lat: s.latitude,
            lng: s.longitude,
            order: s.stop_order
        }));

        const routePolyline = stopsToMark.map(s => ({ lat: s.lat, lng: s.lng }));

        const allLats = stopsToMark.map(s => s.lat);
        const allLngs = stopsToMark.map(s => s.lng);
        if (ctx.userLocation) {
            allLats.push(ctx.userLocation.latitude);
            allLngs.push(ctx.userLocation.longitude);
        }

        return {
            tool: 'show_route_map',
            data: {
                vehicleName,
                vehicleId,
                stopsToMark,
                routePolyline,
                userLocation: ctx.userLocation ? { lat: ctx.userLocation.latitude, lng: ctx.userLocation.longitude } : undefined,
                mapBounds: {
                    north: Math.max(...allLats) + 0.002,
                    south: Math.min(...allLats) - 0.002,
                    east: Math.max(...allLngs) + 0.002,
                    west: Math.min(...allLngs) - 0.002
                },
                etaMinutes: 0,
                distanceKm: 0
            }
        };
    }

    // User location only fallback
    if (ctx.userLocation) {
        return {
            tool: 'show_nearby_stops',
            data: {
                vehicleName: 'Your Location',
                vehicleId: '',
                stopsToMark: [],
                routePolyline: [],
                userLocation: { lat: ctx.userLocation.latitude, lng: ctx.userLocation.longitude },
                mapBounds: {
                    north: ctx.userLocation.latitude + 0.01,
                    south: ctx.userLocation.latitude - 0.01,
                    east: ctx.userLocation.longitude + 0.01,
                    west: ctx.userLocation.longitude - 0.01
                },
                etaMinutes: 0,
                distanceKm: 0
            }
        };
    }

    return null;
}

// ============================================================================
// DATA AGENT - Optimized with Caching & RPC (30-40s freshness)
// ============================================================================

// Simple in-memory cache using Map
interface CacheEntry<T> {
    data: T;
    timestamp: number;
    ttl: number;
}

class DataCache {
    private cache = new Map<string, CacheEntry<unknown>>();

    set<T>(key: string, data: T, ttl: number): void {
        this.cache.set(key, {
            data,
            timestamp: Date.now(),
            ttl
        });
    }

    get<T>(key: string): T | null {
        const entry = this.cache.get(key) as CacheEntry<T> | undefined;
        if (!entry) return null;

        // Check if expired
        if (Date.now() - entry.timestamp > entry.ttl) {
            this.cache.delete(key);
            return null;
        }

        return entry.data;
    }

    isFresh(key: string): boolean {
        const entry = this.cache.get(key);
        if (!entry) return false;
        return Date.now() - entry.timestamp <= entry.ttl;
    }
}

const dataCache = new DataCache();

async function fetchLatestData(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();

    try {
        const client = getSupabase();

        // 1. Get vehicles from cache (5 min TTL) or fetch
        if (dataCache.isFresh('vehicles')) {
            ctx.vehicles = dataCache.get('vehicles') || [];
            console.log('[DataAgent] Using cached vehicles:', ctx.vehicles.length);
        } else {
            const vehiclesRes = await client.from('vehicles')
                .select('id, name, vehicle_number, is_active')
                .eq('is_active', true);
            ctx.vehicles = (vehiclesRes.data || []) as Vehicle[];
            dataCache.set('vehicles', ctx.vehicles, 5 * 60 * 1000); // 5 min cache
            console.log('[DataAgent] Fetched fresh vehicles:', ctx.vehicles.length);
        }

        // 2. Get LATEST locations using RPC with DISTINCT ON (40 second freshness)
        const { data: locationsData, error: locError } = await client.rpc(
            'get_latest_bus_locations',
            { freshness_seconds: 40 }
        );

        if (locError) {
            console.warn('[DataAgent] RPC get_latest_bus_locations failed, falling back:', locError);
            // Fallback to manual DISTINCT ON via timestamp
            const fallbackLoc = await client.from('locations')
                .select('bus_id, latitude, longitude, speed, heading, timestamp, trip_status, accuracy')
                .gt('timestamp', Date.now() - 40000) // 40 seconds
                .order('timestamp', { ascending: false });

            const allLocs = (fallbackLoc.data || []) as LiveLocation[];
            ctx.latestLocations = Array.from(getLatestBusLocations(allLocs).values());
        } else {
            // Map location_ts to timestamp for consistent code usage
            ctx.latestLocations = (locationsData || []).map((l: any) => ({
                ...l,
                timestamp: l.location_ts || l.timestamp
            })) as LiveLocation[];
        }

        console.log('[DataAgent] Latest locations (40s freshness):', ctx.latestLocations.length);

        // 3. Get route stops filtered by 3km radius using RPC
        if (ctx.userLocation) {
            const { data: stopsData, error: stopsError } = await client.rpc(
                'get_nearby_stops',
                {
                    user_lat: ctx.userLocation.latitude,
                    user_lng: ctx.userLocation.longitude,
                    radius_km: 3.0
                }
            );

            if (stopsError) {
                console.warn('[DataAgent] RPC get_nearby_stops failed, fetching all:', stopsError);
                // Fallback: fetch all stops (will be filtered in Layer 2)
                const fallbackStops = await client.from('route_stops')
                    .select('id, vehicle_id, name, latitude, longitude, stop_order')
                    .order('vehicle_id')
                    .order('stop_order');
                ctx.allStops = (fallbackStops.data || []) as RouteStop[];
            } else {
                ctx.allStops = (stopsData || []) as RouteStop[];
                console.log(`[DataAgent] Nearby stops (3km radius): ${ctx.allStops.length}`);
            }
        } else {
            // No user location - fetch all stops (less optimal)
            console.log('[DataAgent] No user location, fetching all active stops');
            const allStopsRes = await client.from('route_stops')
                .select('id, vehicle_id, name, latitude, longitude, stop_order')
                .in('vehicle_id', ctx.vehicles.map(v => v.id))
                .order('vehicle_id')
                .order('stop_order');
            ctx.allStops = (allStopsRes.data || []) as RouteStop[];
        }

        // Calculate data health metrics
        const liveLocations = ctx.latestLocations.filter(l => {
            const ageSeconds = (Date.now() - l.timestamp) / 1000;
            return ageSeconds < 40;
        }).length;

        const activeTripsBuses = ctx.latestLocations.filter(l => l.trip_status === 'started').length;

        return {
            success: true,
            agentName: 'DataAgent',
            data: {
                vehiclesCount: ctx.vehicles.length,
                stopsCount: ctx.allStops.length,
                liveLocationsCount: ctx.latestLocations.length,
                liveBusesCount: liveLocations,
                activeTripsBuses,
                dataHealth: liveLocations / Math.max(1, ctx.vehicles.length),
                freshnessWindow: '40 seconds',
                radiusKm: ctx.userLocation ? 3.0 : null,
                cached: dataCache.isFresh('vehicles')
            },
            message: `Fetched ${ctx.vehicles.length} vehicles, ${ctx.latestLocations.length} locations (${liveLocations} live), ${ctx.allStops.length} stops`,
            confidence: liveLocations / Math.max(1, ctx.vehicles.length),
            executionTimeMs: Date.now() - startTime
        };
    } catch (error) {
        console.error('[DataAgent] Error:', error);
        return {
            success: false,
            agentName: 'DataAgent',
            data: { error: error instanceof Error ? error.message : 'Unknown error' },
            error: 'Failed to fetch data',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }
}

// ============================================================================
// MEMORY AGENT - RAG Context Retrieval (Kept as requested)
// ============================================================================

async function retrieveMemoryContext(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();

    try {
        const client = getSupabase();

        // Keyword-based search for similar past queries
        const queryWords = ctx.query.toLowerCase()
            .split(/\s+/)
            .filter(w => w.length > 3 && !['what', 'where', 'when', 'from', 'that', 'this'].includes(w));

        if (queryWords.length > 0) {
            const orFilter = queryWords.map(w => `query.ilike.%${w}%`).join(',');

            const { data: matches } = await client
                .from('search_history')
                .select('query, intent, response')
                .eq('user_id', ctx.userId)
                .eq('success', true)
                .or(orFilter)
                .order('created_at', { ascending: false })
                .limit(3);

            if (matches && matches.length > 0) {
                ctx.memoryContext = {
                    similarQueries: matches.map(m => ({
                        query: m.query,
                        intent: m.intent,
                        response: m.response,
                        similarity: 0.7 // Keyword match estimate
                    }))
                };

                return {
                    success: true,
                    agentName: 'MemoryAgent',
                    data: { similarQueries: matches.length },
                    message: `Found ${matches.length} similar past queries`,
                    confidence: 0.8,
                    executionTimeMs: Date.now() - startTime
                };
            }
        }

        return {
            success: true,
            agentName: 'MemoryAgent',
            data: { similarQueries: 0 },
            message: 'No similar queries found',
            confidence: 0.5,
            executionTimeMs: Date.now() - startTime
        };
    } catch (error) {
        return {
            success: false,
            agentName: 'MemoryAgent',
            data: {},
            error: error instanceof Error ? error.message : 'Memory retrieval failed',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }
}

// ============================================================================
// ROUTE AGENT - Path Finding + ETA (Merged PathAgent + TimeAgent)
// ============================================================================

async function findRoutes(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();

    if (!ctx.intent || !ctx.vehicles || !ctx.allStops) {
        return {
            success: false,
            agentName: 'RouteAgent',
            data: {},
            error: 'Missing required data',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    const { origin, destination } = ctx.intent.entities;

    if (!origin && !destination) {
        return {
            success: false,
            agentName: 'RouteAgent',
            data: {},
            error: 'No origin or destination provided',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    const latestLocations = getLatestBusLocations(ctx.latestLocations || []);
    const matchedRoutes: RouteResult[] = [];

    for (const vehicle of ctx.vehicles) {
        // Fetch ordered stops for this specific vehicle from the new backend endpoint
        const routeResult = await callGetRouteStops([vehicle.id]);
        const vehicleStops: RouteStop[] = (routeResult?.success && routeResult.routes[vehicle.id])
            ? routeResult.routes[vehicle.id]
            : ctx.allStops
                .filter(s => s.vehicle_id === vehicle.id)
                .sort((a, b) => a.stop_order - b.stop_order);

        // Update context cache for the map tool
        if (routeResult?.success) {
            ctx.allStops = [...(ctx.allStops || []), ...vehicleStops];
        }

        // Find origin and destination stops on this route
        const originStop = destination
            ? vehicleStops.find(s => s.name.toLowerCase().includes((origin || '').toLowerCase()))
            : vehicleStops[0]; // If no origin, use first stop

        const destStop = vehicleStops.find(s =>
            s.name.toLowerCase().includes((destination || '').toLowerCase())
        );

        if (!destStop) continue;
        if (originStop && destStop.stop_order <= originStop.stop_order) continue; // Wrong direction

        const stopsInPath = vehicleStops.filter(s =>
            s.stop_order >= (originStop?.stop_order || 0) &&
            s.stop_order <= destStop.stop_order
        );

        // Calculate distance along route
        let distance = 0;
        for (let i = 0; i < stopsInPath.length - 1; i++) {
            distance += haversineDistance(
                stopsInPath[i].latitude, stopsInPath[i].longitude,
                stopsInPath[i + 1].latitude, stopsInPath[i + 1].longitude
            );
        }

        const liveLocation = latestLocations.get(vehicle.id);
        const etaMinutes = calculateETA(distance, stopsInPath.length - 1, liveLocation?.speed || undefined);

        // ISSUE 4 FIX: Freshness-aware isLive (live data can be stale/inaccurate)
        let isLive = false;
        let freshnessScore = 0;
        if (liveLocation) {
            const freshness = calculateLocationFreshness(liveLocation);
            // Only consider "live" if data is fresh AND reasonably accurate
            isLive = freshness.status === 'live' && (liveLocation.accuracy ?? 100) < 50;
            freshnessScore = freshness.freshnessScore;
        }

        // Calculate bus position on route
        let busStopsAway: number | undefined;
        if (liveLocation && originStop) {
            let minDist = Infinity;
            let currentStopOrder = 0;
            for (const s of vehicleStops) {
                const d = haversineDistance(liveLocation.latitude, liveLocation.longitude, s.latitude, s.longitude);
                if (d < minDist) { minDist = d; currentStopOrder = s.stop_order; }
            }
            busStopsAway = Math.max(0, originStop.stop_order - currentStopOrder);
        }

        // Calculate walk time if user has location
        let walkTimeMinutes: number | undefined;
        let walkDistanceMeters: number | undefined;
        if (ctx.userLocation && originStop) {
            const walkDist = haversineDistance(
                ctx.userLocation.latitude, ctx.userLocation.longitude,
                originStop.latitude, originStop.longitude
            );
            walkTimeMinutes = calculateWalkTime(walkDist);
            walkDistanceMeters = Math.round(walkDist * 1000);
        }

        // Route score for smarter sorting (future-ready)
        // Higher score = better route. Live bonus + shorter ETA bonus
        const routeScore = (isLive ? 100 : 0) + Math.max(0, 60 - etaMinutes) + (freshnessScore * 20);

        matchedRoutes.push({
            vehicleId: vehicle.id,
            vehicleName: vehicle.name,
            vehicleNumber: vehicle.vehicle_number,
            originStop: originStop?.name || vehicleStops[0]?.name || 'Start',
            destinationStop: destStop.name,
            distanceKm: Math.round(distance * 10) / 10,
            etaMinutes,
            stopsInPath: stopsInPath.map(s => s.name),
            isLive,
            busStopsAway,
            walkTimeMinutes,
            walkDistanceMeters,
            routeScore // Added for future sorting
        });
    }

    // Sort by routeScore (higher = better) - combines live status, ETA, and freshness
    matchedRoutes.sort((a, b) => (b.routeScore ?? 0) - (a.routeScore ?? 0));

    if (matchedRoutes.length === 0) {
        return {
            success: false,
            agentName: 'RouteAgent',
            data: { routeFound: false },
            error: 'No direct route found',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    // Mark all as direct routes
    matchedRoutes.forEach(r => { (r as RouteResult).isDirectRoute = true; });

    // Store ALL routes and top 3 for conversational response
    ctx.allRoutes = matchedRoutes;
    ctx.topRoutes = matchedRoutes.slice(0, 3);

    // ========================================
    // AI-ENHANCED ROUTE RANKING
    // Ask AI to rank routes considering: live status, ETA, user preferences
    // ========================================
    if (matchedRoutes.length > 1) {
        interface RouteRankingResult {
            rankings: Array<{ vehicleId: string; rank: number; reason: string }>;
        }

        const routeSummary = matchedRoutes.slice(0, 5).map((r, i) =>
            `${i + 1}. ${r.vehicleName}: ${r.originStop}→${r.destinationStop}, ETA ${r.etaMinutes}min, ${r.isLive ? 'LIVE' : 'offline'}, walk ${r.walkTimeMinutes || '?'}min`
        ).join('\n');

        const aiRanking = await callAgentAI<RouteRankingResult>(
            'RouteRankingAgent',
            `You are a route ranking expert. Rank bus routes for a user. Consider:
1. LIVE tracking is better than offline (more reliable ETA)
2. Shorter ETA is better
3. Shorter walking distance is better
Return JSON: { "rankings": [{ "vehicleId": "id", "rank": 1-5, "reason": "brief reason" }] }`,
            `Routes to rank:\n${routeSummary}\nUser wants: ${destination || 'destination'}`
        );

        if (aiRanking.success && aiRanking.result?.rankings) {
            console.log('[RouteAgent] AI ranking applied');
            // Re-order top routes based on AI ranking
            const aiRankMap = new Map(aiRanking.result.rankings.map(r => [r.vehicleId, r]));
            matchedRoutes.sort((a, b) => {
                const aRank = aiRankMap.get(a.vehicleId)?.rank ?? 99;
                const bRank = aiRankMap.get(b.vehicleId)?.rank ?? 99;
                return aRank - bRank;
            });
            ctx.topRoutes = matchedRoutes.slice(0, 3);
            // Store AI reasoning
            ctx.topRoutes.forEach(route => {
                const aiInfo = aiRankMap.get(route.vehicleId);
                if (aiInfo) {
                    (route as RouteResult & { aiReason?: string }).aiReason = aiInfo.reason;
                }
            });
        }
    }

    ctx.bestRoute = matchedRoutes[0];
    ctx.alternatives = matchedRoutes.slice(1, 3);

    // ISSUE 3 FIX: Dynamic confidence based on data quality
    const liveRoutesCount = matchedRoutes.filter(r => r.isLive).length;
    const hasWalkingData = !!ctx.bestRoute.walkTimeMinutes;
    const baseConfidence = 0.5;
    const liveBonus = (liveRoutesCount / matchedRoutes.length) * 0.3; // Up to 0.3 for all live
    const walkBonus = hasWalkingData ? 0.1 : 0;
    const routeCountBonus = Math.min(matchedRoutes.length * 0.05, 0.1); // Up to 0.1 for multiple routes
    const dynamicConfidence = Math.min(baseConfidence + liveBonus + walkBonus + routeCountBonus, 0.95);

    return {
        success: true,
        agentName: 'RouteAgent',
        data: {
            routeFound: true,
            routesCount: matchedRoutes.length,
            liveRoutesCount,
            bestRoute: ctx.bestRoute.vehicleName,
            eta: ctx.bestRoute.etaMinutes,
            routeScore: ctx.bestRoute.routeScore
        },
        message: `Found ${matchedRoutes.length} routes (${liveRoutesCount} live), best: ${ctx.bestRoute.vehicleName} (${ctx.bestRoute.etaMinutes} min)`,
        confidence: dynamicConfidence,
        executionTimeMs: Date.now() - startTime
    };
}

// ============================================================================
// MULTI-HOP ROUTE AGENT - Find routes with one transfer
// ============================================================================

/**
 * Find routes that require one transfer (Bus A → Walk → Bus B)
 * This agent runs in PARALLEL with findRoutes() for comprehensive coverage
 */
async function findMultiHopRoutes(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();

    // Only for FIND_ROUTE intent
    if (ctx.intent?.primaryIntent !== 'FIND_ROUTE') {
        return {
            success: true,
            agentName: 'MultiHopRouteAgent',
            data: { searched: false },
            message: 'Not a route-finding query',
            confidence: 1.0,
            executionTimeMs: Date.now() - startTime
        };
    }

    const origin = ctx.intent.entities.origin;
    const destination = ctx.intent.entities.destination;

    if (!ctx.vehicles || !ctx.allStops || !origin || !destination) {
        return {
            success: false,
            agentName: 'MultiHopRouteAgent',
            data: {},
            error: 'Missing required data for multi-hop search',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    const multiHopRoutes: MultiHopRoute[] = [];
    const latestLocations = getLatestBusLocations(ctx.latestLocations || []);

    // Find all stops that could be origin or destination matches
    const originStops = ctx.allStops.filter(s =>
        s.name.toLowerCase().includes(origin.toLowerCase())
    );
    const destStops = ctx.allStops.filter(s =>
        s.name.toLowerCase().includes(destination.toLowerCase())
    );

    // For each origin bus route, find transfer points to destination bus routes
    const processedPairs = new Set<string>();

    for (const oStop of originStops) {
        for (const dStop of destStops) {
            // Skip if same vehicle (that's a direct route, handled by findRoutes)
            if (oStop.vehicle_id === dStop.vehicle_id) continue;

            const pairKey = `${oStop.vehicle_id}-${dStop.vehicle_id}`;
            if (processedPairs.has(pairKey)) continue;
            processedPairs.add(pairKey);

            // Find transfer points: stops served by BOTH routes
            const route1Stops = ctx.allStops.filter(s => s.vehicle_id === oStop.vehicle_id);
            const route2Stops = ctx.allStops.filter(s => s.vehicle_id === dStop.vehicle_id);

            // Find common stop names (transfer points)
            const route1StopNames = new Set(route1Stops.map(s => s.name.toLowerCase()));
            const transferStops = route2Stops.filter(s =>
                route1StopNames.has(s.name.toLowerCase()) &&
                s.stop_order < dStop.stop_order // Must be before destination
            );

            for (const transferStop of transferStops) {
                // Find corresponding stop on route 1
                const route1Transfer = route1Stops.find(s =>
                    s.name.toLowerCase() === transferStop.name.toLowerCase() &&
                    s.stop_order > oStop.stop_order // Must be after origin
                );

                if (!route1Transfer) continue;

                // Calculate ETAs for each leg
                const vehicle1 = ctx.vehicles.find(v => v.id === oStop.vehicle_id);
                const vehicle2 = ctx.vehicles.find(v => v.id === dStop.vehicle_id);

                if (!vehicle1 || !vehicle2) continue;

                // Leg 1: Origin to transfer
                const leg1Stops = route1Stops.filter(s =>
                    s.stop_order >= oStop.stop_order && s.stop_order <= route1Transfer.stop_order
                );
                let leg1Distance = 0;
                for (let i = 0; i < leg1Stops.length - 1; i++) {
                    leg1Distance += haversineDistance(
                        leg1Stops[i].latitude, leg1Stops[i].longitude,
                        leg1Stops[i + 1].latitude, leg1Stops[i + 1].longitude
                    );
                }
                const leg1Eta = calculateETA(leg1Distance, leg1Stops.length - 1);

                // Walk time at transfer (average 3 min)
                const walkTime = 3;

                // Leg 2: Transfer to destination
                const leg2Stops = route2Stops.filter(s =>
                    s.stop_order >= transferStop.stop_order && s.stop_order <= dStop.stop_order
                );
                let leg2Distance = 0;
                for (let i = 0; i < leg2Stops.length - 1; i++) {
                    leg2Distance += haversineDistance(
                        leg2Stops[i].latitude, leg2Stops[i].longitude,
                        leg2Stops[i + 1].latitude, leg2Stops[i + 1].longitude
                    );
                }
                const leg2Eta = calculateETA(leg2Distance, leg2Stops.length - 1);

                // Check live status
                const loc1 = latestLocations.get(vehicle1.id);
                const loc2 = latestLocations.get(vehicle2.id);
                const isLeg1Live = !!loc1 && (Date.now() - loc1.timestamp) < 120000;
                const isLeg2Live = !!loc2 && (Date.now() - loc2.timestamp) < 120000;

                const totalEta = leg1Eta + walkTime + leg2Eta;
                const totalDistance = Math.round((leg1Distance + leg2Distance) * 10) / 10;

                // Score: penalize transfers, reward live tracking
                const routeScore = (isLeg1Live ? 30 : 0) + (isLeg2Live ? 30 : 0) +
                    Math.max(0, 60 - totalEta) - 20; // -20 penalty for transfer

                multiHopRoutes.push({
                    legs: [
                        {
                            type: 'bus',
                            vehicleId: vehicle1.id,
                            vehicleName: vehicle1.name,
                            vehicleNumber: vehicle1.vehicle_number,
                            originStop: oStop.name,
                            destinationStop: route1Transfer.name,
                            etaMinutes: leg1Eta,
                            distanceKm: Math.round(leg1Distance * 10) / 10,
                            stopsInPath: leg1Stops.map(s => s.name),
                            isLive: isLeg1Live
                        },
                        {
                            type: 'walk',
                            originStop: route1Transfer.name,
                            destinationStop: transferStop.name,
                            etaMinutes: walkTime,
                            distanceKm: 0.2
                        },
                        {
                            type: 'bus',
                            vehicleId: vehicle2.id,
                            vehicleName: vehicle2.name,
                            vehicleNumber: vehicle2.vehicle_number,
                            originStop: transferStop.name,
                            destinationStop: dStop.name,
                            etaMinutes: leg2Eta,
                            distanceKm: Math.round(leg2Distance * 10) / 10,
                            stopsInPath: leg2Stops.map(s => s.name),
                            isLive: isLeg2Live
                        }
                    ],
                    totalEtaMinutes: totalEta,
                    totalDistanceKm: totalDistance,
                    transferCount: 1,
                    routeScore,
                    summary: `${vehicle1.name} → Walk → ${vehicle2.name}`
                });
            }
        }
    }

    // Sort by score and limit to top 5
    multiHopRoutes.sort((a, b) => b.routeScore - a.routeScore);
    ctx.multiHopRoutes = multiHopRoutes.slice(0, 5);

    return {
        success: multiHopRoutes.length > 0,
        agentName: 'MultiHopRouteAgent',
        data: {
            multiHopRoutesFound: multiHopRoutes.length,
            topMultiHop: multiHopRoutes[0]?.summary
        },
        message: multiHopRoutes.length > 0
            ? `Found ${multiHopRoutes.length} transfer routes, best: ${multiHopRoutes[0].summary}`
            : 'No transfer routes found',
        confidence: multiHopRoutes.length > 0 ? 0.7 : 0,
        executionTimeMs: Date.now() - startTime
    };
}

// ============================================================================
// TRACKER AGENT - Bus Tracking (Kept as requested)
// ============================================================================

async function trackBus(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();

    if (!ctx.intent || !ctx.vehicles || !ctx.latestLocations) {
        return {
            success: false,
            agentName: 'TrackerAgent',
            data: {},
            error: 'Missing required data',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    const targetBusName = ctx.intent.entities.busName || ctx.intent.entities.busNumber;
    if (!targetBusName) {
        return {
            success: false,
            agentName: 'TrackerAgent',
            data: {},
            error: 'No bus name or number provided',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    // Find matching vehicle
    const vehicle = ctx.vehicles.find(v =>
        v.name.toLowerCase().includes(targetBusName.toLowerCase()) ||
        v.vehicle_number.toLowerCase().includes(targetBusName.toLowerCase())
    );

    if (!vehicle) {
        return {
            success: false,
            agentName: 'TrackerAgent',
            data: { activeBuses: ctx.vehicles.slice(0, 5).map(v => v.name) },
            error: `Bus "${targetBusName}" not found`,
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    const latestLocation = ctx.latestLocations.find(l => l.bus_id === vehicle.id);
    if (!latestLocation) {
        return {
            success: false,
            agentName: 'TrackerAgent',
            data: { bus: vehicle.name },
            error: `Bus "${vehicle.name}" is not currently broadcasting location`,
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    // Find ordered stops for this vehicle using the new get-route-stops endpoint
    // This ensures stops are in their database-defined order for map plotting
    const routeStopsResult = await callGetRouteStops([vehicle.id]);
    const vehicleStops: RouteStop[] = (routeStopsResult?.success && routeStopsResult.routes[vehicle.id])
        ? routeStopsResult.routes[vehicle.id]
        : (ctx.allStops || []).filter(s => s.vehicle_id === vehicle.id).sort((a, b) => a.stop_order - b.stop_order);

    // Cache the ordered stops back into context for building map commands
    if (routeStopsResult?.success) {
        ctx.allStops = [...(ctx.allStops || []), ...vehicleStops];
    }

    // Find nearest stop to current bus position
    let nearestStop: RouteStop | null = null;
    let minDist = Infinity;
    for (const s of vehicleStops) {
        const d = haversineDistance(latestLocation.latitude, latestLocation.longitude, s.latitude, s.longitude);
        if (d < minDist) { minDist = d; nearestStop = s; }
    }

    // Populate ctx.filteredBuses for generateMapCommands with complete data
    if (!ctx.filteredBuses) ctx.filteredBuses = [];
    const isAlreadyFiltered = ctx.filteredBuses.some(b => b.vehicle.id === vehicle.id);
    if (!isAlreadyFiltered) {
        ctx.filteredBuses.push({
            vehicle,
            latestLocation,
            nearestStop: nearestStop || null,
            stopsAwayFromUser: 0,
            etaMinutes: 0,
            isMoving: (latestLocation.speed || 0) > 2,
            ageSeconds: Math.floor((Date.now() - latestLocation.timestamp) / 1000)
        });
    }

    const ageSeconds = Math.floor((Date.now() - latestLocation.timestamp) / 1000);
    const isMoving = (latestLocation.speed || 0) > 2;
    const freshness = calculateLocationFreshness(latestLocation);
    const headingDir = getHeadingDirection(latestLocation.heading);

    // Find the best stop to catch the bus (ahead of current bus position + near user)
    let bestCatchStop: RouteStop | null = null;
    let userWalkDistanceKm = Infinity;
    let stopsAwayFromBus = 0;

    if (ctx.userLocation && nearestStop) {
        const busStopOrder = nearestStop.stop_order;
        for (const stop of vehicleStops) {
            if (stop.stop_order > busStopOrder) {
                const distToUser = haversineDistance(
                    ctx.userLocation.latitude, ctx.userLocation.longitude,
                    stop.latitude, stop.longitude
                );
                if (distToUser < userWalkDistanceKm && distToUser <= 2) {
                    userWalkDistanceKm = distToUser;
                    bestCatchStop = stop;
                    stopsAwayFromBus = stop.stop_order - busStopOrder;
                }
            }
        }
    }

    // Calculate bus ETA to catch stop
    let busEtaMinutes = 0;
    if (bestCatchStop && nearestStop) {
        let distanceToCatchStop = 0;
        for (let i = 0; i < vehicleStops.length - 1; i++) {
            if (vehicleStops[i].stop_order >= nearestStop.stop_order &&
                vehicleStops[i].stop_order < bestCatchStop.stop_order) {
                distanceToCatchStop += haversineDistance(
                    vehicleStops[i].latitude, vehicleStops[i].longitude,
                    vehicleStops[i + 1].latitude, vehicleStops[i + 1].longitude
                );
            }
        }
        busEtaMinutes = calculateETA(distanceToCatchStop, stopsAwayFromBus, latestLocation.speed || undefined);
    }

    // Get walking directions to catch stop via Edge Function (eliminates MAPBOX_API_URL duplication)
    if (ctx.userLocation && bestCatchStop) {
        try {
            const supabaseUrl = Deno.env.get('SUPABASE_URL');
            const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

            if (supabaseUrl && serviceKey) {
                console.log(`[TrackerAgent] Getting walking directions to ${bestCatchStop.name} via Edge Function`);
                const walkingResponse = await fetch(`${supabaseUrl}/functions/v1/get-walking-directions`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'apikey': serviceKey,
                        'Authorization': `Bearer ${serviceKey}`
                    },
                    body: JSON.stringify({
                        originLat: ctx.userLocation.latitude,
                        originLng: ctx.userLocation.longitude,
                        destLat: bestCatchStop.latitude,
                        destLng: bestCatchStop.longitude,
                        destStopName: bestCatchStop.name,
                        language: 'en'
                    })
                });

                if (walkingResponse.ok) {
                    const walkingResult = await walkingResponse.json();
                    if (walkingResult.success && walkingResult.walkingRoute) {
                        const route = walkingResult.walkingRoute;
                        ctx.walkingDirections = {
                            destinationStop: bestCatchStop.name,
                            distanceMeters: route.distanceMeters,
                            durationMinutes: route.durationMinutes,
                            steps: (route.steps || []).slice(0, 6).map((step: { instruction: string; distance: number }) => ({
                                instruction: step.instruction,
                                distance: step.distance
                            })),
                            summary: route.summary || `${route.durationMinutes} min walk (${route.distanceMeters}m)`
                        };
                        console.log(`[TrackerAgent] Walking: ${ctx.walkingDirections.summary}`);
                    }
                }
            }
        } catch (e) { console.warn('[TrackerAgent] Walking directions failed:', e); }
    }

    // Store in filtered buses for response generation
    ctx.filteredBuses = [{
        vehicle,
        latestLocation,
        nearestStop,
        stopsAwayFromUser: stopsAwayFromBus,
        etaMinutes: busEtaMinutes,
        isMoving,
        ageSeconds,
        tripStatus: latestLocation.trip_status || 'unknown',
        headingDirection: headingDir || undefined,
        freshness
    }];

    // Build enhanced message
    let message = `📍 **${vehicle.name}** is ${isMoving ? 'moving' : 'stopped'}`;
    if (headingDir) message += ` heading ${headingDir}`;
    message += ` near ${nearestStop?.name || 'unknown'}`;

    if (latestLocation.trip_status === 'started') {
        message += ' 🟢 (trip active)';
    } else if (latestLocation.trip_status === 'stopped') {
        message += ' 🔴 (trip ended)';
    }

    if (bestCatchStop && ctx.walkingDirections) {
        message += `\n\n🚶 Walk to **${bestCatchStop.name}** (${ctx.walkingDirections.summary})`;
        message += `\n⏱️ Bus arrives in ~${busEtaMinutes} min (${stopsAwayFromBus} stops away)`;
    }

    return {
        success: true,
        agentName: 'TrackerAgent',
        data: {
            bus: vehicle.name,
            vehicleNumber: vehicle.vehicle_number,
            nearestStop: nearestStop?.name,
            catchStop: bestCatchStop?.name,
            isMoving,
            speed: latestLocation.speed || 0,
            heading: headingDir,
            tripStatus: latestLocation.trip_status,
            ageSeconds,
            freshness: freshness.status,
            busEtaMinutes,
            stopsAway: stopsAwayFromBus,
            walkingDirections: ctx.walkingDirections
        },
        message,
        confidence: freshness.freshnessScore,
        executionTimeMs: Date.now() - startTime
    };
}

// ============================================================================
// GEOCODING AGENT - Place Name Enrichment (Kept as requested)
// ============================================================================

const NOMINATIM_BASE_URL = 'https://nominatim.openstreetmap.org';
const geocodeCache = new Map<string, { name: string; timestamp: number }>();
const CACHE_TTL_MS = 24 * 60 * 60 * 1000;

async function enrichWithPlaceNames(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();
    ctx.geocodedPlaces = {};

    // Skip if no user location
    if (!ctx.userLocation) {
        return {
            success: true,
            agentName: 'GeocodingAgent',
            data: { enriched: 0 },
            message: 'No location to enrich',
            confidence: 1.0,
            executionTimeMs: Date.now() - startTime
        };
    }

    try {
        const cacheKey = `${ctx.userLocation.latitude.toFixed(4)},${ctx.userLocation.longitude.toFixed(4)}`;
        const cached = geocodeCache.get(cacheKey);
        let usedCache = false;
        let hasNeighbourhood = false;

        if (cached && Date.now() - cached.timestamp < CACHE_TTL_MS) {
            ctx.geocodedPlaces.userLocation = cached.name;
            ctx.geocodedPlaces.userLocationFreshness = 'cached';
            usedCache = true;
            // Assume cached values were high quality if we cached them
            hasNeighbourhood = true;
        } else {
            const response = await fetch(
                `${NOMINATIM_BASE_URL}/reverse?lat=${ctx.userLocation.latitude}&lon=${ctx.userLocation.longitude}&format=json&addressdetails=1`,
                { headers: { 'User-Agent': 'SaharaBusApp/1.0' } }
            );

            if (response.ok) {
                const data = await response.json();
                const address = data.address || {};

                // Track if we got specific neighbourhood data
                hasNeighbourhood = !!(address.neighbourhood || address.suburb);

                const name = address.neighbourhood || address.suburb || address.village ||
                    address.town || address.city || data.display_name?.split(',')[0] || 'Unknown';

                ctx.geocodedPlaces.userLocation = name;
                ctx.geocodedPlaces.userLocationFreshness = 'live';
                geocodeCache.set(cacheKey, { name, timestamp: Date.now() });
            }
        }

        // Dynamic confidence: higher if we got specific neighbourhood, lower for fallbacks
        const dynamicConfidence = hasNeighbourhood ? 0.95 : 0.75;

        return {
            success: true,
            agentName: 'GeocodingAgent',
            data: {
                userLocation: ctx.geocodedPlaces.userLocation,
                freshness: usedCache ? 'cached' : 'live',
                isSpecific: hasNeighbourhood
            },
            message: ctx.geocodedPlaces.userLocation ? `User near ${ctx.geocodedPlaces.userLocation}` : 'Could not geocode',
            confidence: dynamicConfidence,
            executionTimeMs: Date.now() - startTime
        };
    } catch (error) {
        return {
            success: false,
            agentName: 'GeocodingAgent',
            data: {},
            error: error instanceof Error ? error.message : 'Geocoding failed',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }
}

// ============================================================================
// WALKING DIRECTIONS AGENT - Delegates to get-walking-directions Edge Function
// ============================================================================

/**
 * Get walking directions by delegating to the get-walking-directions Edge Function.
 * This eliminates code duplication - Mapbox API logic is centralized in the Edge Function.
 */
async function getWalkingDirectionsFromMapbox(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();

    try {
        if (!ctx.userLocation || !ctx.nearestStops || ctx.nearestStops.length === 0) {
            return {
                success: false,
                agentName: 'WalkingDirectionsAgent',
                data: {},
                error: 'No user location or nearby stops available',
                confidence: 0,
                executionTimeMs: Date.now() - startTime
            };
        }

        // Get the nearest stop
        const nearestStop = ctx.nearestStops[0];

        console.log(`[WalkingDirectionsAgent] Delegating to get-walking-directions Edge Function...`);

        // Call the get-walking-directions Edge Function via HTTP
        const supabaseUrl = Deno.env.get('SUPABASE_URL');
        const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

        if (!supabaseUrl || !serviceKey) {
            return {
                success: false,
                agentName: 'WalkingDirectionsAgent',
                data: {},
                error: 'Missing Supabase configuration for Edge Function call',
                confidence: 0,
                executionTimeMs: Date.now() - startTime
            };
        }

        const url = `${supabaseUrl}/functions/v1/get-walking-directions`;
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'apikey': serviceKey,
                'Authorization': `Bearer ${serviceKey}`
            },
            body: JSON.stringify({
                originLat: ctx.userLocation.latitude,
                originLng: ctx.userLocation.longitude,
                destLat: nearestStop.stop.latitude,
                destLng: nearestStop.stop.longitude,
                destStopName: nearestStop.stop.name,
                language: 'en'
            })
        });

        if (!response.ok) {
            const errorText = await response.text();
            console.error('[WalkingDirectionsAgent] Edge Function error:', response.status, errorText);
            return {
                success: false,
                agentName: 'WalkingDirectionsAgent',
                data: {},
                error: `Walking directions Edge Function error: ${response.status}`,
                confidence: 0,
                executionTimeMs: Date.now() - startTime
            };
        }

        const result = await response.json();

        if (!result.success || !result.walkingRoute) {
            return {
                success: false,
                agentName: 'WalkingDirectionsAgent',
                data: {},
                error: result.error || 'No walking route found',
                confidence: 0,
                executionTimeMs: Date.now() - startTime
            };
        }

        const walkingRoute = result.walkingRoute;
        const steps = (walkingRoute.steps || []).slice(0, 6).map((step: { instruction: string; distance: number }) => ({
            instruction: step.instruction,
            distance: step.distance
        }));

        // Store in context
        ctx.walkingDirections = {
            destinationStop: nearestStop.stop.name,
            distanceMeters: walkingRoute.distanceMeters,
            durationMinutes: walkingRoute.durationMinutes,
            steps,
            summary: walkingRoute.summary || `${walkingRoute.durationMinutes} min walk (${walkingRoute.distanceMeters}m)`
        };

        console.log(`[WalkingDirectionsAgent] Got directions via Edge Function: ${ctx.walkingDirections.summary}`);

        // Publish to hub for multi-agent communication
        ctx.hub.publish({
            type: MessageType.WALKING_DIRECTIONS_READY,
            source: 'WalkingDirectionsAgent',
            payload: {
                destinationStop: nearestStop.stop.name,
                distanceMeters: walkingRoute.distanceMeters,
                durationMinutes: walkingRoute.durationMinutes,
                stepsCount: steps.length,
                summary: ctx.walkingDirections.summary
            },
            priority: MessagePriority.NORMAL
        });

        return {
            success: true,
            agentName: 'WalkingDirectionsAgent',
            data: {
                destinationStop: nearestStop.stop.name,
                distanceMeters: walkingRoute.distanceMeters,
                durationMinutes: walkingRoute.durationMinutes,
                stepsCount: steps.length
            },
            message: `Walking directions to ${nearestStop.stop.name}: ${walkingRoute.durationMinutes} min (${walkingRoute.distanceMeters}m)`,
            confidence: 0.95,
            executionTimeMs: Date.now() - startTime
        };

    } catch (error) {
        console.error('[WalkingDirectionsAgent] Error:', error);
        return {
            success: false,
            agentName: 'WalkingDirectionsAgent',
            data: {},
            error: error instanceof Error ? error.message : 'Walking directions failed',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }
}

// ============================================================================
// BUS PREDICTION AGENT - Arrival Time Predictions
// ============================================================================

interface ArrivalPrediction {
    vehicleId: string;
    vehicleName: string;
    stopName: string;
    predictedArrivalMinutes: number;
    confidence: number;
    basedOn: 'live_location' | 'historical' | 'schedule';
    isApproaching: boolean;
}

async function predictBusArrivals(ctx: PipelineContext): Promise<AgentOutput> {
    const startTime = Date.now();

    if (!ctx.nearestStops || ctx.nearestStops.length === 0 || !ctx.latestLocations) {
        return {
            success: false,
            agentName: 'BusPredictionAgent',
            data: {},
            error: 'No stops or locations available for prediction',
            confidence: 0,
            executionTimeMs: Date.now() - startTime
        };
    }

    const predictions: ArrivalPrediction[] = [];
    const latestMap = getLatestBusLocations(ctx.latestLocations);

    for (const nearStop of ctx.nearestStops.slice(0, 3)) {
        const latestLoc = latestMap.get(nearStop.vehicleId);

        if (latestLoc) {
            // Calculate distance from bus to this stop
            const distToStop = haversineDistance(
                latestLoc.latitude, latestLoc.longitude,
                nearStop.stop.latitude, nearStop.stop.longitude
            );

            // Use actual speed if available, fallback to default
            const speed = (latestLoc.speed && latestLoc.speed > 2)
                ? latestLoc.speed
                : DEFAULT_BUS_SPEED_KMH;

            // Base prediction on distance and speed
            let predictedMinutes = Math.ceil((distToStop / speed) * 60);

            // Add buffer for stops in between
            const stopsAway = getStopsAway(nearStop.vehicleId, latestLoc, nearStop.stop, ctx.allStops || []);
            predictedMinutes += Math.max(0, stopsAway) * 0.5; // 30 sec per stop

            // Calculate confidence based on data freshness
            const freshness = calculateLocationFreshness(latestLoc);
            const isApproaching = stopsAway >= 0 && stopsAway <= 3 && (latestLoc.speed || 0) > 2;

            predictions.push({
                vehicleId: nearStop.vehicleId,
                vehicleName: nearStop.vehicleName,
                stopName: nearStop.stop.name,
                predictedArrivalMinutes: Math.round(predictedMinutes),
                confidence: freshness.freshnessScore * 0.9,
                basedOn: freshness.status === 'live' ? 'live_location' : 'historical',
                isApproaching
            });
        }
    }

    // Sort by predicted arrival time
    predictions.sort((a, b) => a.predictedArrivalMinutes - b.predictedArrivalMinutes);

    // Store predictions in context for response generation
    (ctx as unknown as { arrivalPredictions: ArrivalPrediction[] }).arrivalPredictions = predictions;

    return {
        success: predictions.length > 0,
        agentName: 'BusPredictionAgent',
        data: { predictions, count: predictions.length },
        message: predictions.length > 0
            ? `Next bus: ${predictions[0].vehicleName} in ~${predictions[0].predictedArrivalMinutes} min${predictions[0].isApproaching ? ' (approaching!)' : ''}`
            : 'No predictions available',
        confidence: predictions.length > 0 ? predictions[0].confidence : 0,
        executionTimeMs: Date.now() - startTime
    };
}

/**
 * Calculate bearing between two points in degrees (0-360)
 * Formula: θ = atan2(sin(Δλ)⋅cos(φ₂), cos(φ₁)⋅sin(φ₂) − sin(φ₁)⋅cos(φ₂)⋅cos(Δλ))
 */
function calculateBearing(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const φ1 = lat1 * Math.PI / 180;
    const φ2 = lat2 * Math.PI / 180;
    const Δλ = (lng2 - lng1) * Math.PI / 180;

    const y = Math.sin(Δλ) * Math.cos(φ2);
    const x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ);
    const θ = Math.atan2(y, x);

    return (θ * 180 / Math.PI + 360) % 360;
}

/**
 * Calculate shortest distance from point to line segment
 * Uses perpendicular projection formula with segment bounds checking
 */
function pointToSegmentDistance(
    pointLat: number,
    pointLng: number,
    seg1Lat: number,
    seg1Lng: number,
    seg2Lat: number,
    seg2Lng: number
): { distance: number; projection: number } {
    // Convert to approximate cartesian for segment projection (works for small distances)
    const px = pointLng;
    const py = pointLat;
    const x1 = seg1Lng;
    const y1 = seg1Lat;
    const x2 = seg2Lng;
    const y2 = seg2Lat;

    const dx = x2 - x1;
    const dy = y2 - y1;
    const segLengthSq = dx * dx + dy * dy;

    if (segLengthSq === 0) {
        // Degenerate segment - just return distance to point
        return {
            distance: haversineDistance(pointLat, pointLng, seg1Lat, seg1Lng),
            projection: 0
        };
    }

    // Calculate projection parameter t (0 = at seg1, 1 = at seg2)
    const t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / segLengthSq));

    // Find closest point on segment
    const closestLat = y1 + t * dy;
    const closestLng = x1 + t * dx;

    return {
        distance: haversineDistance(pointLat, pointLng, closestLat, closestLng),
        projection: t // 0-1, how far along the segment
    };
}

/**
 * Get stops away using route segment projection and bearing analysis
 * This fixes the issue of approximate nearest-stop calculation
 */
function getStopsAway(
    vehicleId: string,
    currentLoc: LiveLocation,
    targetStop: RouteStop,
    allStops: RouteStop[]
): number {
    const vehicleStops = allStops
        .filter(s => s.vehicle_id === vehicleId)
        .sort((a, b) => a.stop_order - b.stop_order);

    if (vehicleStops.length === 0) return -1;

    // ========================================
    // IMPROVED ALGORITHM: Route Segment Projection
    // ========================================
    // Instead of finding nearest stop by distance,
    // we project the bus onto the route path
    // ========================================

    let bestSegmentIndex = -1;
    let bestProjection = 0;
    let minSegmentDist = Infinity;

    // Find which route segment the bus is on
    for (let i = 0; i < vehicleStops.length - 1; i++) {
        const stop1 = vehicleStops[i];
        const stop2 = vehicleStops[i + 1];

        const { distance, projection } = pointToSegmentDistance(
            currentLoc.latitude,
            currentLoc.longitude,
            stop1.latitude,
            stop1.longitude,
            stop2.latitude,
            stop2.longitude
        );

        if (distance < minSegmentDist) {
            minSegmentDist = distance;
            bestSegmentIndex = i;
            bestProjection = projection;
        }
    }

    // If no segment found, fall back to nearest stop
    if (bestSegmentIndex === -1) {
        let nearestStopIndex = 0;
        let minDist = Infinity;

        for (let i = 0; i < vehicleStops.length; i++) {
            const dist = haversineDistance(
                currentLoc.latitude,
                currentLoc.longitude,
                vehicleStops[i].latitude,
                vehicleStops[i].longitude
            );
            if (dist < minDist) {
                minDist = dist;
                nearestStopIndex = i;
            }
        }

        return targetStop.stop_order - vehicleStops[nearestStopIndex].stop_order;
    }

    // Calculate interpolated stop order based on segment position
    const currentSegmentStop = vehicleStops[bestSegmentIndex];
    const nextSegmentStop = vehicleStops[bestSegmentIndex + 1];

    // Use bearing to determine direction (is bus moving toward next stop?)
    let isMovingForward = true;

    if (currentLoc.heading !== null && currentLoc.heading !== undefined) {
        // Calculate expected bearing from current position to next stop
        const bearingToNext = calculateBearing(
            currentLoc.latitude,
            currentLoc.longitude,
            nextSegmentStop.latitude,
            nextSegmentStop.longitude
        );

        // Calculate bearing difference
        let bearingDiff = Math.abs(currentLoc.heading - bearingToNext);
        if (bearingDiff > 180) bearingDiff = 360 - bearingDiff;

        // If bus heading differs by more than 90°, it might be going backward
        isMovingForward = bearingDiff < 90;
    }

    // Interpolate current stop order position
    const interpolatedOrder = isMovingForward
        ? currentSegmentStop.stop_order + bestProjection
        : currentSegmentStop.stop_order - (1 - bestProjection);

    // Calculate stops away (fractional is ok, will be rounded later)
    const stopsAway = targetStop.stop_order - interpolatedOrder;

    return Math.round(stopsAway);
}

// ============================================================================
// ENDPOINT DELEGATION (Phase 2 - Multi-Agent via HTTP)
// ============================================================================

interface DelegatedResponse {
    success: boolean;
    response: string;
    intent: string;
    routeData?: Record<string, unknown>;
    executionTimeMs: number;
    // Agent trace for A2A visibility
    agentTrace: AgentTraceEntry[];
}

/**
 * Call an Edge Function via HTTP.
 * This enables true multi-agent architecture where each function is a separate service.
 */
async function callEdgeFunction(
    functionName: string,
    payload: Record<string, unknown>
): Promise<Record<string, unknown>> {
    const supabaseUrl = Deno.env.get('SUPABASE_URL');
    const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

    if (!supabaseUrl || !serviceKey) {
        throw new Error('Missing Supabase configuration');
    }

    const url = `${supabaseUrl}/functions/v1/${functionName}`;
    console.log(`[AgentOrchestrator] Calling ${functionName} agent...`);

    const response = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'apikey': serviceKey,
            'Authorization': `Bearer ${serviceKey}`
        },
        body: JSON.stringify(payload)
    });

    if (!response.ok) {
        const errorText = await response.text();
        console.error(`[AgentOrchestrator] ${functionName} failed:`, response.status, errorText);
        throw new Error(`${functionName} agent failed: ${response.status}`);
    }

    return await response.json();
}

/**
 * Call get-route-stops fetcher specifically for ordered map visualizations.
 * Uses GET as required by latest backend code.
 */
async function callGetRouteStops(vehicleIds: string[]): Promise<any> {
    const supabaseUrl = Deno.env.get('SUPABASE_URL');
    const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

    if (!supabaseUrl || !serviceKey) return null;

    const url = new URL(`${supabaseUrl}/functions/v1/get-route-stops`);
    url.searchParams.set('vehicle_ids', vehicleIds.join(','));
    url.searchParams.set('include_vehicle_info', 'true');

    try {
        const res = await fetch(url.toString(), {
            headers: {
                'apikey': serviceKey,
                'Authorization': `Bearer ${serviceKey}`
            }
        });
        if (res.ok) return await res.json();
    } catch (e) {
        console.warn(`[get-route-stops] Call failed:`, e);
    }
    return null;
}

// ============================================================================
// ROUTE QUERY HELPERS - For AI Agent Pathfinding
// ============================================================================

/**
 * Get ordered route stops for a specific vehicle.
 * Critical for AI pathfinding - returns stops in their sequence order.
 * 
 * SQL equivalent:
 * SELECT id, name, latitude, longitude, stop_order
 * FROM route_stops
 * WHERE vehicle_id = ?
 * ORDER BY stop_order ASC;
 */
async function getOrderedRouteStops(vehicleId: string): Promise<RouteStop[]> {
    const client = getSupabase();
    const { data, error } = await client
        .from('route_stops')
        .select('id, name, latitude, longitude, stop_order')
        .eq('vehicle_id', vehicleId)
        .order('stop_order', { ascending: true });

    if (error) {
        console.error(`[getOrderedRouteStops] Error for vehicle ${vehicleId}:`, error);
        throw error;
    }
    return (data || []).map(s => ({ ...s, vehicle_id: vehicleId }));
}

/**
 * Find buses/vehicles passing through a specific stop name.
 * For AI matching when user asks "which buses go through X?"
 * 
 * SQL equivalent:
 * SELECT DISTINCT vehicle_id
 * FROM route_stops
 * WHERE name ILIKE '%stop_name%';
 */
async function findBusesByStopName(stopName: string): Promise<string[]> {
    const client = getSupabase();
    const { data, error } = await client
        .from('route_stops')
        .select('vehicle_id')
        .ilike('name', `%${stopName}%`);

    if (error) {
        console.error(`[findBusesByStopName] Error for stop "${stopName}":`, error);
        throw error;
    }
    // Deduplicate vehicle IDs
    return [...new Set(((data || []) as { vehicle_id: string }[]).map(s => s.vehicle_id))];
}

/**
 * Get the next stop in sequence after a given stop order.
 * For navigation: "What's the next stop after X?"
 * 
 * SQL equivalent:
 * SELECT id, name, latitude, longitude, stop_order
 * FROM route_stops
 * WHERE vehicle_id = ? AND stop_order > ?
 * ORDER BY stop_order ASC
 * LIMIT 1;
 */
async function getNextStopAfter(vehicleId: string, currentStopOrder: number): Promise<RouteStop | null> {
    const client = getSupabase();
    const { data, error } = await client
        .from('route_stops')
        .select('id, name, latitude, longitude, stop_order')
        .eq('vehicle_id', vehicleId)
        .gt('stop_order', currentStopOrder)
        .order('stop_order', { ascending: true })
        .limit(1)
        .single();

    if (error || !data) return null;
    return { ...data, vehicle_id: vehicleId };
}

/**
 * Get all routes grouped by vehicle ID.
 * Useful for bulk operations and route comparison.
 * 
 * SQL equivalent:
 * SELECT id, vehicle_id, name, latitude, longitude, stop_order
 * FROM route_stops
 * ORDER BY vehicle_id, stop_order ASC;
 */
async function getAllRoutesGrouped(): Promise<Record<string, RouteStop[]>> {
    const client = getSupabase();
    const { data, error } = await client
        .from('route_stops')
        .select('id, vehicle_id, name, latitude, longitude, stop_order')
        .order('vehicle_id')
        .order('stop_order', { ascending: true });

    if (error) {
        console.error('[getAllRoutesGrouped] Error:', error);
        throw error;
    }

    const grouped: Record<string, RouteStop[]> = {};
    for (const stop of data || []) {
        if (!grouped[stop.vehicle_id]) grouped[stop.vehicle_id] = [];
        grouped[stop.vehicle_id].push(stop);
    }
    return grouped;
}

/**
 * Delegate query to specialized Edge Function agents.
 * Each agent is a separate Edge Function called via HTTP.
 */
async function delegateToEndpoint(
    decision: RouteDecision,
    userLocation?: { latitude: number; longitude: number },
    userId?: string
): Promise<DelegatedResponse> {
    const startTime = Date.now();
    const params = decision.extractedParams;

    switch (decision.endpoint) {
        case EndpointType.TRACK_BUS: {
            // Call track-bus Edge Function agent
            try {
                const result = await callEdgeFunction('track-bus', {
                    busName: params.busName || params.busNumber || ''
                });

                return {
                    success: result.success === true,
                    response: (result.message as string) || (result.error as string) || 'Could not track bus',
                    intent: 'TRACK_BUS',
                    routeData: result.success ? {
                        busId: result.busId,
                        busName: result.busName,
                        position: result.position,
                        speed: result.speed,
                        nearestStop: result.nearestStop,
                        freshness: result.freshness
                    } : undefined,
                    executionTimeMs: Date.now() - startTime,
                    agentTrace: [{ agent: 'TrackerAgent', action: 'track_bus', durationMs: Date.now() - startTime, success: result.success === true, timestamp: Date.now() }]
                };
            } catch (error) {
                // Fallback to internal if Edge Function fails
                console.warn('[AgentOrchestrator] track-bus agent failed, using internal:', error);
                const result = await trackBusInternal(params.busName || params.busNumber || '');
                return {
                    success: result.success,
                    response: result.message || result.error || 'Could not track bus',
                    intent: 'TRACK_BUS',
                    routeData: result.success ? {
                        busId: result.busId,
                        busName: result.busName,
                        position: result.position,
                        speed: result.speed,
                        nearestStop: result.nearestStop
                    } : undefined,
                    executionTimeMs: Date.now() - startTime,
                    agentTrace: [{ agent: 'TrackerAgent', action: 'track_bus_fallback', durationMs: Date.now() - startTime, success: result.success, timestamp: Date.now() }]
                };
            }
        }

        case EndpointType.FIND_ROUTE: {
            // Call find-route Edge Function agent
            try {
                const result = await callEdgeFunction('find-route', {
                    origin: params.origin,
                    destination: params.destination || '',
                    userLocation
                });

                // Also get walking directions if we have a route and user location
                let walkingInfo = '';
                if (result.success && result.bestRoute && userLocation) {
                    try {
                        const walkResult = await callEdgeFunction('get-walking-directions', {
                            originLat: userLocation.latitude,
                            originLng: userLocation.longitude,
                            destLat: (result.bestRoute as Record<string, unknown>).originStopLat,
                            destLng: (result.bestRoute as Record<string, unknown>).originStopLng,
                            destStopName: (result.bestRoute as Record<string, unknown>).originStop
                        });
                        if (walkResult.success && walkResult.walkingRoute) {
                            const wr = walkResult.walkingRoute as Record<string, unknown>;
                            walkingInfo = `\n\n🚶 Walk to pickup: ${wr.summary}`;
                        }
                    } catch {
                        // Walking directions optional, continue without
                    }
                }

                return {
                    success: result.success === true,
                    response: ((result.message as string) || 'Route found') + walkingInfo,
                    intent: 'FIND_ROUTE',
                    routeData: result.bestRoute as Record<string, unknown>,
                    executionTimeMs: Date.now() - startTime,
                    agentTrace: [{ agent: 'RouteAgent', action: 'find_route', durationMs: Date.now() - startTime, success: result.success === true, timestamp: Date.now() }]
                };
            } catch (error) {
                // Fallback to internal if Edge Function fails
                console.warn('[AgentOrchestrator] find-route agent failed, using internal:', error);
                const result = await findRouteInternal(
                    params.origin,
                    params.destination || '',
                    userLocation
                );
                return {
                    success: result.success,
                    response: result.message,
                    intent: 'FIND_ROUTE',
                    routeData: result.bestRoute as Record<string, unknown>,
                    executionTimeMs: Date.now() - startTime,
                    agentTrace: [{ agent: 'RouteAgent', action: 'find_route_fallback', durationMs: Date.now() - startTime, success: result.success, timestamp: Date.now() }]
                };
            }
        }

        case EndpointType.NEARBY_STOPS: {
            // Call get-nearby-stops Edge Function agent
            if (!userLocation) {
                return {
                    success: false,
                    response: 'Please share your location to find nearby stops.',
                    intent: 'FIND_NEARBY',
                    executionTimeMs: Date.now() - startTime,
                    agentTrace: [{ agent: 'NearbyStopsAgent', action: 'location_required', durationMs: Date.now() - startTime, success: false, timestamp: Date.now() }]
                };
            }

            try {
                const result = await callEdgeFunction('get-nearby-stops', {
                    latitude: userLocation.latitude,
                    longitude: userLocation.longitude,
                    radiusKm: params.radiusKm || 1.0
                });

                // Also get walking directions to nearest stop
                let walkingInfo = '';
                if (result.success && result.stops && (result.stops as unknown[]).length > 0) {
                    try {
                        const walkResult = await callEdgeFunction('get-walking-directions', {
                            originLat: userLocation.latitude,
                            originLng: userLocation.longitude,
                            findNearestStop: true
                        });
                        if (walkResult.success && walkResult.walkingRoute) {
                            const wr = walkResult.walkingRoute as Record<string, unknown>;
                            walkingInfo = `\n\n🚶 Walk to nearest: ${wr.summary}`;
                        }
                    } catch {
                        // Walking directions optional
                    }
                }

                return {
                    success: result.success === true,
                    response: ((result.message as string) || 'Found nearby stops') + walkingInfo,
                    intent: 'FIND_NEARBY',
                    routeData: { stops: result.stops, count: result.count },
                    executionTimeMs: Date.now() - startTime,
                    agentTrace: [{ agent: 'NearbyStopsAgent', action: 'find_nearby', durationMs: Date.now() - startTime, success: result.success === true, timestamp: Date.now() }]
                };
            } catch (error) {
                // Fallback to internal
                console.warn('[AgentOrchestrator] get-nearby-stops agent failed, using internal:', error);
                const result = await findNearbyStopsInternal(
                    userLocation.latitude,
                    userLocation.longitude,
                    params.radiusKm || 1.0
                );
                return {
                    success: result.success,
                    response: result.message || 'Found nearby stops',
                    intent: 'FIND_NEARBY',
                    routeData: { stops: result.stops, count: result.count },
                    executionTimeMs: Date.now() - startTime,
                    agentTrace: [{ agent: 'NearbyStopsAgent', action: 'find_nearby_fallback', durationMs: Date.now() - startTime, success: result.success, timestamp: Date.now() }]
                };
            }
        }

        default:
            throw new Error(`Unsupported endpoint: ${decision.endpoint}`);
    }
}

/**
 * Internal track-bus implementation (reuses existing logic)
 */
async function trackBusInternal(busRef: string): Promise<{
    success: boolean;
    busId?: string;
    busName?: string;
    position?: { latitude: number; longitude: number };
    speed?: number;
    nearestStop?: string;
    message?: string;
    error?: string;
}> {
    try {
        const client = getSupabase();

        // Fetch vehicles
        const { data: vehicles } = await client.from('vehicles').select('*');

        // ========================================
        // OPTIMIZED: Use RPC function for latest locations
        // Performance: DISTINCT ON with index vs N-scan + memory filter
        // ========================================
        const { data: locations, error: locError } = await client
            .rpc('get_latest_bus_locations', { freshness_seconds: 60 });

        // Declare latestLocations outside the if/else blocks so it's accessible later
        const latestLocations = new Map<string, LiveLocation>();

        if (locError) {
            console.warn('[trackBusInternal] RPC fallback to manual query:', locError);
            // Fallback to manual query if RPC not available
            const { data: rawLocations } = await client
                .from('locations')
                .select('*')
                .order('timestamp', { ascending: false })
                .limit(1000); // Limit for performance

            for (const loc of (rawLocations || []) as LiveLocation[]) {
                if (!latestLocations.has(loc.bus_id)) {
                    latestLocations.set(loc.bus_id, loc);
                }
            }
        } else {
            // Convert RPC result to Map
            for (const loc of (locations || []) as LiveLocation[]) {
                latestLocations.set(loc.bus_id, loc);
            }
        }

        // Find target bus
        let targetLocation: LiveLocation | undefined;
        let targetVehicle: Vehicle | undefined;

        const busRefLower = busRef.toLowerCase();
        for (const [busId, loc] of latestLocations) {
            const vehicle = (vehicles as Vehicle[])?.find(v => v.id === busId);
            if ((vehicle?.name?.toLowerCase()?.includes(busRefLower)) ||
                (vehicle?.vehicle_number?.toLowerCase()?.includes(busRefLower)) ||
                busId.toLowerCase().includes(busRefLower)) {
                targetLocation = loc;
                targetVehicle = vehicle;
                break;
            }
        }

        if (!targetLocation) {
            return {
                success: false,
                error: `Could not find bus "${busRef}". It may not be sharing its location right now.`
            };
        }

        // Fetch stops for nearest stop calculation
        const { data: stops } = await client
            .from('route_stops')
            .select('*')
            .eq('vehicle_id', targetLocation.bus_id);

        let nearestStop = '';
        let nearestDistance = Infinity;
        for (const stop of (stops || []) as RouteStop[]) {
            const dist = haversineDistance(
                targetLocation.latitude,
                targetLocation.longitude,
                stop.latitude,
                stop.longitude
            );
            if (dist < nearestDistance) {
                nearestDistance = dist;
                nearestStop = stop.name;
            }
        }

        const movingStatus = (targetLocation.speed || 0) > 2 ? 'moving' : 'stopped';
        const speedInfo = (targetLocation.speed || 0) > 0 ? ` at ${Math.round(targetLocation.speed || 0)} km/h` : '';

        return {
            success: true,
            busId: targetLocation.bus_id,
            busName: targetVehicle?.name || targetLocation.bus_id,
            position: { latitude: targetLocation.latitude, longitude: targetLocation.longitude },
            speed: targetLocation.speed || 0,
            nearestStop,
            message: `📍 **${targetVehicle?.name || busRef}** is currently ${movingStatus}${speedInfo} near ${nearestStop}.`
        };
    } catch (error) {
        return {
            success: false,
            error: error instanceof Error ? error.message : 'Failed to track bus'
        };
    }
}

/**
 * Internal find-route implementation
 * OPTIMIZED: Early active vehicle filtering, deduplication, vehicle_id intersection
 */
async function findRouteInternal(
    origin: string | undefined,
    destination: string,
    userLocation?: { latitude: number; longitude: number }
): Promise<{
    success: boolean;
    message: string;
    bestRoute?: Record<string, unknown>;
    alternatives?: Record<string, unknown>[];
}> {
    try {
        const client = getSupabase();

        // FIX Issue 4: Fetch stops with INNER JOIN on active vehicles EARLY
        const { data: allStopsRaw, error: stopsError } = await client
            .from('route_stops')
            .select(`
                id, vehicle_id, name, latitude, longitude, stop_order,
                vehicles!inner(is_active)
            `)
            .eq('vehicles.is_active', true)
            .order('vehicle_id')
            .order('stop_order');

        if (stopsError) {
            console.error('[findRouteInternal] Error fetching stops:', stopsError);
            throw stopsError;
        }

        if (!allStopsRaw || allStopsRaw.length === 0) {
            return { success: false, message: 'No bus stops found in the system.' };
        }

        // Map to RouteStop type (strip nested vehicles object)
        const allStops: RouteStop[] = (allStopsRaw as any[]).map(s => ({
            id: s.id,
            vehicle_id: s.vehicle_id,
            name: s.name,
            latitude: s.latitude,
            longitude: s.longitude,
            stop_order: s.stop_order
        }));

        const destLower = destination.toLowerCase();
        const originLower = origin?.toLowerCase() || '';

        const destStops = allStops.filter(s => s.name.toLowerCase().includes(destLower));

        if (destStops.length === 0) {
            return { success: false, message: `Sorry, I couldn't find any stops matching "${destination}".` };
        }

        // FIX Issue 2: Search origin stops separately and intersect by vehicle_id
        let originStops: RouteStop[] = [];
        if (origin) {
            originStops = allStops.filter(s => s.name.toLowerCase().includes(originLower));
        }

        // Get common vehicle IDs (routes with BOTH origin AND destination)
        const destVehicleIds = new Set(destStops.map(s => s.vehicle_id));
        let commonVehicleIds: string[];

        if (originStops.length > 0) {
            const originVehicleIds = new Set(originStops.map(s => s.vehicle_id));
            commonVehicleIds = [...destVehicleIds].filter(id => originVehicleIds.has(id));
            console.log(`[findRouteInternal] Intersected: ${commonVehicleIds.length} common vehicles`);
        } else {
            commonVehicleIds = [...destVehicleIds];
        }

        if (commonVehicleIds.length === 0 && origin) {
            return { success: false, message: `Sorry, I couldn't find a direct route from "${origin}" to "${destination}".` };
        }

        // Filter stops to only common vehicles
        const relevantStops = allStops.filter(s => commonVehicleIds.includes(s.vehicle_id));

        // Group stops by vehicle
        const vehicleStopsMap = new Map<string, RouteStop[]>();
        for (const stop of relevantStops) {
            const existing = vehicleStopsMap.get(stop.vehicle_id) || [];
            existing.push(stop);
            vehicleStopsMap.set(stop.vehicle_id, existing);
        }

        // Find matching routes with DEDUPLICATION
        const matches: Array<{ vehicleId: string; originStop: RouteStop; destStop: RouteStop; stopsInPath: RouteStop[]; distance: number }> = [];

        // FIX Issue 3: Track seen routes to prevent duplicates
        const seenRoutes = new Set<string>();

        // Create origin stop IDs set for O(1) lookup
        const originStopIds = new Set(originStops.map(s => s.id));

        for (const destStop of destStops.filter(s => commonVehicleIds.includes(s.vehicle_id))) {
            const vehicleStops = vehicleStopsMap.get(destStop.vehicle_id) || [];
            // Sort by stop_order
            vehicleStops.sort((a, b) => a.stop_order - b.stop_order);

            if (origin && originStops.length > 0) {
                // User provided an origin name - match by ID
                const validOrigins = vehicleStops.filter(s =>
                    originStopIds.has(s.id) && s.stop_order < destStop.stop_order
                );
                for (const originStop of validOrigins) {
                    // Dedupe with composite key
                    const routeKey = `${destStop.vehicle_id}:${originStop.stop_order}:${destStop.stop_order}`;
                    if (seenRoutes.has(routeKey)) continue;
                    seenRoutes.add(routeKey);

                    const stopsInPath = vehicleStops
                        .filter(s => s.stop_order >= originStop.stop_order && s.stop_order <= destStop.stop_order);

                    // Calculate distance for sorting
                    let dist = 0;
                    for (let i = 0; i < stopsInPath.length - 1; i++) {
                        dist += haversineDistance(
                            stopsInPath[i].latitude, stopsInPath[i].longitude,
                            stopsInPath[i + 1].latitude, stopsInPath[i + 1].longitude
                        );
                    }
                    matches.push({ vehicleId: destStop.vehicle_id, originStop, destStop, stopsInPath, distance: dist });
                }
            } else if (userLocation) {
                // Find closest stop to user on this route (before destination)
                let closestStop: RouteStop | null = null;
                let closestDist = Infinity;
                for (const stop of vehicleStops) {
                    if (stop.stop_order < destStop.stop_order) {
                        const dist = haversineDistance(userLocation.latitude, userLocation.longitude, stop.latitude, stop.longitude);
                        if (dist < closestDist) { closestDist = dist; closestStop = stop; }
                    }
                }
                // Increased radius to 5km for better coverage
                if (closestStop && closestDist < 5) {
                    // Dedupe
                    const routeKey = `${destStop.vehicle_id}:${closestStop.stop_order}:${destStop.stop_order}`;
                    if (seenRoutes.has(routeKey)) continue;
                    seenRoutes.add(routeKey);

                    const stopsInPath = vehicleStops
                        .filter(s => s.stop_order >= closestStop!.stop_order && s.stop_order <= destStop.stop_order);

                    let dist = 0;
                    for (let i = 0; i < stopsInPath.length - 1; i++) {
                        dist += haversineDistance(
                            stopsInPath[i].latitude, stopsInPath[i].longitude,
                            stopsInPath[i + 1].latitude, stopsInPath[i + 1].longitude
                        );
                    }
                    matches.push({ vehicleId: destStop.vehicle_id, originStop: closestStop, destStop, stopsInPath, distance: dist });
                }
            } else {
                // No origin or userLocation - use first stop before destination as origin
                const stopsBeforeDest = vehicleStops.filter(s => s.stop_order < destStop.stop_order);
                if (stopsBeforeDest.length > 0) {
                    const originStop = stopsBeforeDest[0]; // First stop on route

                    // Dedupe
                    const routeKey = `${destStop.vehicle_id}:${originStop.stop_order}:${destStop.stop_order}`;
                    if (seenRoutes.has(routeKey)) continue;
                    seenRoutes.add(routeKey);

                    const stopsInPath = vehicleStops
                        .filter(s => s.stop_order >= originStop.stop_order && s.stop_order <= destStop.stop_order);

                    let dist = 0;
                    for (let i = 0; i < stopsInPath.length - 1; i++) {
                        dist += haversineDistance(
                            stopsInPath[i].latitude, stopsInPath[i].longitude,
                            stopsInPath[i + 1].latitude, stopsInPath[i + 1].longitude
                        );
                    }
                    matches.push({ vehicleId: destStop.vehicle_id, originStop, destStop, stopsInPath, distance: dist });
                }
            }
        }

        console.log(`[findRouteInternal] Found ${matches.length} unique routes (deduped)`);

        if (matches.length === 0) {
            return { success: false, message: `Sorry, I couldn't find a direct route to "${destination}".` };
        }

        // Sort matches by distance (shortest first)
        matches.sort((a, b) => a.distance - b.distance);

        // Fetch vehicle details
        const vehicleIds = [...new Set(matches.map(m => m.vehicleId))];
        const { data: vehicles } = await client.from('vehicles').select('*').in('id', vehicleIds);
        const vehicleMap = new Map((vehicles as Vehicle[] || []).map((v: Vehicle) => [v.id, v]));

        // Calculate ETA for best match
        const best = matches[0];
        const vehicle = vehicleMap.get(best.vehicleId) as Vehicle | undefined;
        const eta = calculateETA(best.distance, best.stopsInPath.length - 1);

        // ==========================================
        // MAPBOX INTEGRATION: Get walking directions to pickup stop
        // ==========================================
        let walkingDirections: {
            distanceMeters: number;
            durationMinutes: number;
            steps: Array<{ instruction: string; distance: number }>;
            summary: string;
        } | undefined;

        if (userLocation) {
            // Call get-walking-directions Edge Function instead of direct Mapbox API
            const supabaseUrl = Deno.env.get('SUPABASE_URL');
            const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

            if (supabaseUrl && serviceKey) {
                try {
                    const originStop = best.originStop;
                    console.log(`[WalkingDirections] Delegating to Edge Function for ${originStop.name}...`);

                    const walkResponse = await fetch(`${supabaseUrl}/functions/v1/get-walking-directions`, {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                            'apikey': serviceKey,
                            'Authorization': `Bearer ${serviceKey}`
                        },
                        body: JSON.stringify({
                            originLat: userLocation.latitude,
                            originLng: userLocation.longitude,
                            destLat: originStop.latitude,
                            destLng: originStop.longitude,
                            destStopName: originStop.name,
                            language: 'en'
                        })
                    });

                    if (walkResponse.ok) {
                        const walkData = await walkResponse.json();
                        if (walkData.success && walkData.walkingRoute) {
                            const route = walkData.walkingRoute;
                            walkingDirections = {
                                distanceMeters: route.distanceMeters,
                                durationMinutes: route.durationMinutes,
                                steps: (route.steps || []).map((step: { instruction: string; distance: number }) => ({
                                    instruction: step.instruction,
                                    distance: step.distance
                                })),
                                summary: route.summary || `${route.durationMinutes} min walk (${route.distanceMeters}m)`
                            };
                            console.log(`[WalkingDirections] Got: ${walkingDirections.summary}`);
                        }
                    }
                } catch (walkError) {
                    console.warn('[WalkingDirections] Edge Function call failed:', walkError);
                    // Continue without walking directions - graceful degradation
                }
            }
        }

        // Build message with walking directions if available
        let message = `🚌 Take **${vehicle?.name || best.vehicleId}** from ${best.originStop.name} to ${best.destStop.name}.\n` +
            `📏 ${Math.round(best.distance * 10) / 10} km • ${best.stopsInPath.length - 1} stops\n` +
            `⏱️ ETA: **${eta} min**`;

        if (walkingDirections) {
            message += `\n\n🚶 **Walk to ${best.originStop.name}** (${walkingDirections.summary}):`;
            walkingDirections.steps.slice(0, 3).forEach((step, i) => {
                message += `\n  ${i + 1}. ${step.instruction} (${step.distance}m)`;
            });
            if (walkingDirections.steps.length > 3) {
                message += `\n  ... and ${walkingDirections.steps.length - 3} more steps`;
            }
        }

        // Build alternatives (next 2 routes)
        const alternatives: Record<string, unknown>[] = [];
        for (let i = 1; i < Math.min(3, matches.length); i++) {
            const alt = matches[i];
            const altVehicle = vehicleMap.get(alt.vehicleId) as Vehicle | undefined;
            const altEta = calculateETA(alt.distance, alt.stopsInPath.length - 1);
            alternatives.push({
                vehicleId: alt.vehicleId,
                vehicleName: altVehicle?.name,
                originStop: alt.originStop.name,
                destinationStop: alt.destStop.name,
                distanceKm: Math.round(alt.distance * 10) / 10,
                etaMinutes: altEta,
                stopsInPath: alt.stopsInPath.map(s => s.name)
            });
        }

        return {
            success: true,
            message,
            bestRoute: {
                vehicleId: best.vehicleId,
                vehicleName: vehicle?.name,
                originStop: best.originStop.name,
                destinationStop: best.destStop.name,
                distanceKm: Math.round(best.distance * 10) / 10,
                etaMinutes: eta,
                stopsInPath: best.stopsInPath.map(s => s.name),
                walkingDirections // Include walking directions in route data
            },
            alternatives: alternatives.length > 0 ? alternatives : undefined
        };
    } catch (error) {
        return { success: false, message: error instanceof Error ? error.message : 'Route search failed' };
    }
}

/**
 * Internal nearby-stops implementation
 */
async function findNearbyStopsInternal(
    latitude: number,
    longitude: number,
    radiusKm: number
): Promise<{
    success: boolean;
    stops: Array<{ name: string; distanceKm: number; vehicleName: string }>;
    count: number;
    message?: string;
}> {
    try {
        const client = getSupabase();

        const [vehiclesResult, stopsResult] = await Promise.all([
            client.from('vehicles').select('*'),
            client.from('route_stops').select('*')
        ]);

        const vehicles = (vehiclesResult.data || []) as Vehicle[];
        const stops = (stopsResult.data || []) as RouteStop[];
        const vehicleMap = new Map(vehicles.map(v => [v.id, v]));

        const nearbyStops: Array<{ name: string; distanceKm: number; vehicleName: string }> = [];

        for (const stop of stops) {
            const dist = haversineDistance(latitude, longitude, stop.latitude, stop.longitude);
            if (dist <= radiusKm) {
                const vehicle = vehicleMap.get(stop.vehicle_id);
                nearbyStops.push({
                    name: stop.name,
                    distanceKm: Math.round(dist * 1000) / 1000,
                    vehicleName: vehicle?.name || stop.vehicle_id
                });
            }
        }

        nearbyStops.sort((a, b) => a.distanceKm - b.distanceKm);

        let message = '';
        if (nearbyStops.length === 0) {
            message = `📍 No bus stops found within ${radiusKm} km.`;
        } else {
            const top3 = nearbyStops.slice(0, 3);
            message = `📍 Found ${nearbyStops.length} stops within ${radiusKm} km:\n` +
                top3.map((s, i) => `${i + 1}. **${s.name}** (${s.vehicleName}) - ${Math.round(s.distanceKm * 1000)}m`).join('\n');
        }

        return { success: true, stops: nearbyStops, count: nearbyStops.length, message };
    } catch (error) {
        return { success: false, stops: [], count: 0, message: 'Failed to find nearby stops' };
    }
}

// ============================================================================
// MAP-ACTION PIPELINE: Entity Resolution & Map Commands
// ============================================================================

/**
 * Resolve visual queries by cross-referencing viewport entities.
 * When user asks "what is that bus?" or uses deictic references like "that", "this",
 * we identify the most likely entity they're referring to.
 */
async function resolveVisualQuery(ctx: PipelineContext): Promise<{
    resolvedBusId?: string;
    resolvedStopId?: string;
    resolvedEntity?: { type: 'bus' | 'stop'; name: string; id: string };
    confidence: number;
}> {
    if (!ctx.viewportState || !ctx.visibleEntities) {
        console.log('[VisualQuery] No viewport state or visible entities');
        return { confidence: 0 };
    }

    const buses = ctx.visibleEntities.buses || [];
    const stops = ctx.visibleEntities.stops || [];

    // Check if query mentions specific bus name/number
    const busNameFromQuery = ctx.intent?.entities?.busName;

    if (busNameFromQuery && buses.length > 0) {
        // Try to match query bus name with visible buses
        const matchedBus = buses.find(b =>
            b.vehicle_name.toLowerCase().includes(busNameFromQuery.toLowerCase())
        );
        if (matchedBus) {
            console.log(`[VisualQuery] Matched bus "${matchedBus.vehicle_name}" from query`);
            return {
                resolvedBusId: matchedBus.bus_id,
                resolvedEntity: { type: 'bus', name: matchedBus.vehicle_name, id: matchedBus.bus_id },
                confidence: 0.9
            };
        }
    }

    // If only one bus is visible, assume user is referring to it
    if (buses.length === 1) {
        const bus = buses[0];
        console.log(`[VisualQuery] Only one bus visible: ${bus.vehicle_name}`);
        return {
            resolvedBusId: bus.bus_id,
            resolvedEntity: { type: 'bus', name: bus.vehicle_name, id: bus.bus_id },
            confidence: 0.8
        };
    }

    // If multiple buses, pick the one closest to viewport center
    if (buses.length > 1 && ctx.viewportState) {
        const centerLat = ctx.viewportState.centerLat;
        const centerLng = ctx.viewportState.centerLng;

        let closestBus = buses[0];
        let minDist = Infinity;

        for (const bus of buses) {
            const dist = haversineDistance(centerLat, centerLng, bus.latitude, bus.longitude);
            if (dist < minDist) {
                minDist = dist;
                closestBus = bus;
            }
        }

        console.log(`[VisualQuery] Closest bus to center: ${closestBus.vehicle_name}`);
        return {
            resolvedBusId: closestBus.bus_id,
            resolvedEntity: { type: 'bus', name: closestBus.vehicle_name, id: closestBus.bus_id },
            confidence: 0.6
        };
    }

    return { confidence: 0 };
}

/**
 * Detect route deviation (ghost bus) by checking if bus is off its defined route.
 * Calls the detect_route_deviation RPC function.
 */
async function detectGhostBus(busId: string): Promise<{
    isDeviated: boolean;
    deviationStatus: 'on_route' | 'minor_deviation' | 'significant_deviation';
    nearestStop?: string;
    distanceKm?: number;
}> {
    try {
        const supabase = getSupabase();
        const { data, error } = await supabase.rpc('detect_route_deviation', {
            p_bus_id: busId,
            deviation_threshold_km: 0.5
        });

        if (error) {
            console.error('[GhostBus] RPC error:', error);
            return { isDeviated: false, deviationStatus: 'on_route' };
        }

        console.log(`[GhostBus] Bus ${busId}: ${data.deviation_status}`);

        return {
            isDeviated: data.is_deviated || false,
            deviationStatus: data.deviation_status || 'on_route',
            nearestStop: data.nearest_stop,
            distanceKm: data.distance_km
        };
    } catch (e) {
        console.error('[GhostBus] Exception:', e);
        return { isDeviated: false, deviationStatus: 'on_route' };
    }
}

/**
 * Fetch entities visible in the user's viewport.
 * Calls the get_entities_in_viewport RPC function.
 */
async function fetchVisibleEntities(ctx: PipelineContext): Promise<void> {
    if (!ctx.viewportState) {
        console.log('[FetchEntities] No viewport state');
        return;
    }

    try {
        const supabase = getSupabase();
        const { data, error } = await supabase.rpc('get_entities_in_viewport', {
            north: ctx.viewportState.boundingBox.north,
            south: ctx.viewportState.boundingBox.south,
            east: ctx.viewportState.boundingBox.east,
            west: ctx.viewportState.boundingBox.west,
            max_results: 50,
            freshness_seconds: 60
        });

        if (error) {
            console.error('[FetchEntities] RPC error:', error);
            return;
        }

        ctx.visibleEntities = {
            buses: data?.buses || [],
            stops: data?.stops || []
        };

        console.log(`[FetchEntities] Found ${ctx.visibleEntities.buses.length} buses, ${ctx.visibleEntities.stops.length} stops in viewport`);
    } catch (e) {
        console.error('[FetchEntities] Exception:', e);
    }
}

/**
 * Generate map commands based on the pipeline context.
 * The AI uses these commands to control what the user sees on the map.
 */
function generateMapCommands(ctx: PipelineContext): MapCommand[] {
    const commands: MapCommand[] = [];

    // Case 1: Best route found - highlight the route path
    if (ctx.bestRoute) {
        const route = ctx.bestRoute;

        // Build path coordinates from route stops
        const pathCoordinates: { lat: number; lng: number }[] = [];
        if (ctx.allStops) {
            const routeStops = ctx.allStops
                .filter(s => s.vehicle_id === route.vehicleId)
                .sort((a, b) => a.stop_order - b.stop_order);

            routeStops.forEach(stop => {
                pathCoordinates.push({ lat: stop.latitude, lng: stop.longitude });
            });
        }

        if (pathCoordinates.length > 0) {
            const geoJSON: any = {
                type: 'Feature',
                geometry: {
                    type: 'LineString',
                    coordinates: pathCoordinates.map(p => [p.lng, p.lat])
                },
                properties: {
                    type: 'route',
                    vehicleId: route.vehicleId,
                    vehicleName: route.vehicleName,
                    stroke: '#2196F3',
                    'stroke-width': 4
                }
            };

            commands.push({
                action: 'HIGHLIGHT_PATH',
                target_bus_id: route.vehicleId,
                reasoning_color: '#2196F3', // Blue for route
                message: `Route: ${route.originStop} to ${route.destinationStop}`,
                geoJSON
            });
        }
    }

    // Case 2: Tracking a specific bus - focus and highlight (including its path)
    if (ctx.intent?.primaryIntent === 'TRACK_BUS' && ctx.filteredBuses && ctx.filteredBuses.length > 0) {
        const bus = ctx.filteredBuses[0];

        // Build path for the tracked bus as well
        const pathCoordinates: { lat: number; lng: number }[] = [];
        if (ctx.allStops) {
            const busStops = ctx.allStops
                .filter(s => s.vehicle_id === bus.vehicle.id)
                .sort((a, b) => a.stop_order - b.stop_order);

            busStops.forEach(stop => {
                pathCoordinates.push({ lat: stop.latitude, lng: stop.longitude });
            });
        }

        const geoJSON: any = pathCoordinates.length > 0 ? {
            type: 'Feature',
            geometry: {
                type: 'LineString',
                coordinates: pathCoordinates.map(p => [p.lng, p.lat])
            },
            properties: {
                type: 'tracking_path',
                vehicleId: bus.vehicle.id,
                stroke: '#4CAF50',
                'stroke-width': 4
            }
        } : undefined;

        commands.push({
            action: 'FOCUS_AND_HIGHLIGHT',
            target_bus_id: bus.vehicle.id,
            coordinates: bus.latestLocation ? { lat: bus.latestLocation.latitude, lng: bus.latestLocation.longitude } : undefined,
            reasoning_color: '#4CAF50',
            message: `Tracking ${bus.vehicle.name} (${bus.vehicle.vehicle_number})`,
            zoom_level: 16,
            geoJSON
        });
    }

    // Case 3: Visual query resolved - zoom to the identified entity
    if (ctx.visibleEntities) {
        // If we resolved a visual query entity, zoom to it
        const resolvedBus = ctx.visibleEntities.buses.find(b =>
            ctx.intent?.entities?.busName &&
            b.vehicle_name.toLowerCase().includes(ctx.intent.entities.busName.toLowerCase())
        );

        if (resolvedBus) {
            commands.push({
                action: 'ZOOM_TO',
                target_bus_id: resolvedBus.bus_id,
                coordinates: { lat: resolvedBus.latitude, lng: resolvedBus.longitude },
                message: `Focusing on ${resolvedBus.vehicle_name}`,
                zoom_level: 17
            });
        }
    }

    // Case 4: Nearby stops query - add markers for nearest stops
    if (ctx.intent?.primaryIntent === 'FIND_NEARBY' && ctx.nearestStops && ctx.nearestStops.length > 0) {
        const nearestStop = ctx.nearestStops[0];

        commands.push({
            action: 'ADD_MARKER',
            target_stop_id: nearestStop.stop.id,
            coordinates: { lat: nearestStop.stop.latitude, lng: nearestStop.stop.longitude },
            reasoning_color: '#FF9800', // Orange for highlighted stop
            message: `Stop: ${nearestStop.stop.name}`
        });
    }

    console.log(`[MapCommands] Generated ${commands.length} map commands`);
    return commands;
}

// ============================================================================
// MAIN PIPELINE ORCHESTRATOR
// ============================================================================

async function executePipeline(ctx: PipelineContext): Promise<string> {
    console.log(`[Pipeline] Starting for query: "${ctx.query}"`);

    // ========================================
    // LAYER 1: AI Intent Analysis
    // ========================================
    console.log('[Layer1] Analyzing intent...');
    const intentStart = Date.now();
    ctx.intent = await analyzeIntentWithAI(ctx.query);
    const intentDuration = Date.now() - intentStart;
    addAgentTrace(ctx, 'IntentAgent', ctx.intent.primaryIntent, intentDuration, true);
    console.log(`[Layer1] Intent: ${ctx.intent.primaryIntent} (${Math.round(ctx.intent.confidence * 100)}%)`);

    // Publish INTENT_ANALYZED via hub
    ctx.hub.publish({
        type: MessageType.INTENT_ANALYZED,
        source: 'IntentAgent',
        payload: {
            primaryIntent: ctx.intent.primaryIntent,
            subIntents: ctx.intent.subIntents,
            entities: ctx.intent.entities,
            confidence: ctx.intent.confidence,
            reasoning: ctx.intent.reasoning
        },
        priority: MessagePriority.HIGH
    });

    // Handle GENERAL intent immediately
    if (ctx.intent.primaryIntent === 'GENERAL') {
        ctx.hub.reset(); // Clean up hub
        return '👋 Hello! I can help you find bus routes, track buses, or find nearby stops. Where would you like to go?';
    }

    // ========================================
    // DATA AGENT: Fetch LATEST data
    // ========================================
    console.log('[DataAgent] Fetching latest data...');
    const dataStart = Date.now();
    const dataResult = await fetchLatestData(ctx);
    const dataDuration = Date.now() - dataStart;
    addAgentTrace(ctx, 'DataAgent', 'fetch_data', dataDuration, dataResult.success);
    ctx.agentResults.set('DataAgent', dataResult);

    // Publish DATA_READY via hub
    if (dataResult.success) {
        ctx.hub.publish({
            type: MessageType.DATA_READY,
            source: 'DataAgent',
            payload: {
                vehiclesCount: ctx.vehicles?.length || 0,
                stopsCount: ctx.allStops?.length || 0,
                liveLocationsCount: ctx.latestLocations?.length || 0
            },
            priority: MessagePriority.HIGH
        });
    }

    if (!dataResult.success) {
        ctx.hub.reset(); // Clean up hub
        return 'Could not fetch data. Please try again.';
    }

    // ========================================
    // MEMORY AGENT: RAG context retrieval
    // ========================================
    console.log('[MemoryAgent] Retrieving context...');
    const memoryStart = Date.now();
    const memoryResult = await retrieveMemoryContext(ctx);
    const memoryDuration = Date.now() - memoryStart;
    addAgentTrace(ctx, 'MemoryAgent', 'retrieve_context', memoryDuration, memoryResult.success);
    ctx.agentResults.set('MemoryAgent', memoryResult);

    // ========================================
    // GEOCODING: Convert "from" location to coordinates
    // When user specifies an origin (e.g., "from MG Road to..."),
    // geocode it and use those coordinates for all layer calculations
    // ========================================
    if (ctx.intent?.entities?.origin && ctx.intent.primaryIntent === 'FIND_ROUTE') {
        const originName = ctx.intent.entities.origin;
        console.log(`[Geocoding] Attempting to geocode origin: "${originName}"`);

        const geocodingStart = Date.now();
        const geocodedCoords = await geocodePlaceName(originName);
        const geocodeDuration = Date.now() - geocodingStart;

        if (geocodedCoords) {
            ctx.geocodedOrigin = {
                ...geocodedCoords,
                placeName: originName
            };
            console.log(`[Geocoding] Origin "${originName}" → (${geocodedCoords.latitude.toFixed(4)}, ${geocodedCoords.longitude.toFixed(4)}) in ${geocodeDuration}ms`);
            addAgentTrace(ctx, 'GeocodingAgent', 'geocode_origin', geocodeDuration, true);

            // Stream reasoning if available
            if (ctx.streamReasoning) {
                await ctx.streamReasoning(`Found location: ${originName}`);
            }
        } else {
            console.log(`[Geocoding] Could not geocode "${originName}", will use user's GPS location`);
            addAgentTrace(ctx, 'GeocodingAgent', 'geocode_origin', geocodeDuration, false);
        }
    }

    // ========================================
    // LAYER 2: Location Filter (3km Radius)
    // Priority: geocodedOrigin (from query) > userLocation (GPS)
    // ========================================
    // Determine the effective origin location for filtering
    const filterLocation = ctx.geocodedOrigin || ctx.userLocation;

    if (filterLocation && ctx.allStops && ctx.vehicles) {
        const locationSource = ctx.geocodedOrigin
            ? `geocoded origin: "${ctx.geocodedOrigin.placeName}"`
            : 'user GPS location';
        console.log(`[Layer2] Filtering stops within 3km of ${locationSource}...`);

        ctx.filteredStops = filterStopsBy3kmRadius(
            filterLocation.latitude,
            filterLocation.longitude,
            ctx.allStops,
            ctx.vehicles
        );
        console.log(`[Layer2] Found ${ctx.filteredStops.length} stops within 3km`);
    }

    // ========================================
    // LAYER 3: Nearest Stop Selection (KNN)
    // ========================================
    if (ctx.filteredStops && ctx.filteredStops.length > 0) {
        console.log('[Layer3] Selecting nearest stops...');
        ctx.nearestStops = knnNearestStops(ctx.filteredStops, 5);

        // Find alternatives if requested
        if (ctx.intent.subIntents.includes('alternative_options')) {
            const alts = findAlternativeStops(ctx.filteredStops, ctx.intent);
            ctx.nearestStops = [...ctx.nearestStops, ...alts];
        }
        console.log(`[Layer3] Selected ${ctx.nearestStops.length} nearest stops`);
    }

    // ========================================
    // LAYER 4: Bus Filtering (LATEST coordinates)
    // ========================================
    if (ctx.nearestStops && ctx.vehicles && ctx.latestLocations && ctx.allStops) {
        console.log('[Layer4] Filtering buses by stops...');
        const latestMap = getLatestBusLocations(ctx.latestLocations);
        ctx.filteredBuses = filterBusesByStops(ctx.nearestStops, ctx.vehicles, latestMap, ctx.allStops);
        console.log(`[Layer4] Found ${ctx.filteredBuses.length} buses passing through selected stops`);
    }

    // = [NEW] KSRTC AGENT PRE-WARM =
    // Pre-fetch cities in background if not cached
    getKSRTCCities().catch(() => { });

    // ========================================
    // INTENT-SPECIFIC AGENTS (PARALLEL for FIND_ROUTE)
    // ========================================
    switch (ctx.intent.primaryIntent) {
        case 'FIND_KSRTC_ROUTE':
        case 'FIND_ROUTE':
            // ========================================
            // PHASE 2: PARALLEL Route Discovery
            // Run direct routes + multi-hop routes + KSRTC routes simultaneously
            // ========================================
            console.log('[RouteDiscovery] Running parallel route agents...');
            const routeDiscoveryStart = Date.now();

            const discoveries: Promise<AgentOutput>[] = [
                findRoutes(ctx),
                findMultiHopRoutes(ctx),
                findKSRTCRoutes(ctx)
            ];

            const [routeResult, multiHopResult, ksrtcResult] = await Promise.all(discoveries);

            const routeDiscoveryDuration = Date.now() - routeDiscoveryStart;
            addAgentTrace(ctx, 'RouteAgent', 'find_routes', routeResult.executionTimeMs || 0, routeResult.success);
            addAgentTrace(ctx, 'MultiHopRouteAgent', 'find_transfers', multiHopResult.executionTimeMs || 0, multiHopResult.success);
            addAgentTrace(ctx, 'KSRTCAgent', 'search_ksrtc', ksrtcResult.executionTimeMs || 0, ksrtcResult.success);

            ctx.agentResults.set('RouteAgent', routeResult);
            ctx.agentResults.set('MultiHopRouteAgent', multiHopResult);
            ctx.agentResults.set('KSRTCAgent', ksrtcResult);

            console.log(`[RouteDiscovery] Parallel phase complete in ${routeDiscoveryDuration}ms`);
            console.log(`[RouteDiscovery] Direct: ${ctx.allRoutes?.length || 0}, Multi-hop: ${ctx.multiHopRoutes?.length || 0}, KSRTC: ${ctx.ksrtcRoutes?.length || 0}`);
            break;

        case 'TRACK_BUS':
            console.log('[TrackerAgent] Tracking bus...');
            const trackerStart = Date.now();
            const trackerResult = await trackBus(ctx);
            const trackerDuration = Date.now() - trackerStart;
            addAgentTrace(ctx, 'TrackerAgent', 'track_bus', trackerDuration, trackerResult.success);
            ctx.agentResults.set('TrackerAgent', trackerResult);
            break;

        case 'FIND_NEARBY':
            // Already handled by Layer 2/3
            break;
    }

    // ========================================
    // PHASE 3: PARALLEL Enrichment Agents
    // ========================================
    console.log('[Enrichment] Running parallel enrichment agents...');
    const enrichmentStart = Date.now();

    const enrichmentPromises: Promise<AgentOutput>[] = [
        enrichWithPlaceNames(ctx)
    ];

    // Add conditional enrichment agents
    if (ctx.userLocation && ctx.nearestStops && ctx.nearestStops.length > 0) {
        enrichmentPromises.push(getWalkingDirectionsFromMapbox(ctx));
    }

    if (ctx.nearestStops && ctx.nearestStops.length > 0 && ctx.latestLocations && ctx.latestLocations.length > 0) {
        enrichmentPromises.push(predictBusArrivals(ctx));
    }

    const enrichmentResults = await Promise.all(enrichmentPromises);
    enrichmentResults.forEach(result => {
        ctx.agentResults.set(result.agentName, result);
        // Add trace for each enrichment agent
        addAgentTrace(ctx, result.agentName, 'enrich', result.executionTimeMs, result.success);
    });

    const enrichmentDuration = Date.now() - enrichmentStart;
    console.log(`[Enrichment] Parallel phase complete in ${enrichmentDuration}ms`);

    // ========================================
    // MAP-ACTION PIPELINE: Viewport Entity Fetching
    // ========================================
    if (ctx.viewportState) {
        console.log('[MapActionPipeline] Fetching visible entities...');
        await fetchVisibleEntities(ctx);

        // If query requires visual context, resolve the referenced entity
        if (ctx.visibleEntities && ctx.visibleEntities.buses.length > 0) {
            console.log('[MapActionPipeline] Resolving visual query...');
            const resolution = await resolveVisualQuery(ctx);
            if (resolution.resolvedEntity) {
                console.log(`[MapActionPipeline] Resolved to: ${resolution.resolvedEntity.type} "${resolution.resolvedEntity.name}"`);
                // Add resolved entity to intent for use in response
                if (!ctx.intent?.entities?.busName && resolution.resolvedEntity.type === 'bus') {
                    if (ctx.intent?.entities) {
                        ctx.intent.entities.busName = resolution.resolvedEntity.name;
                    }
                }
            }
        }
    }

    // ========================================
    // LAYER 5: AI Response (Grounded)
    // ========================================
    console.log('[Layer5] Generating grounded response...');
    const responseStart = Date.now();
    const response = await generateGroundedResponse(ctx.query, ctx);
    const responseDuration = Date.now() - responseStart;
    addAgentTrace(ctx, 'ResponseAgent', 'generate_response', responseDuration, response.length > 0);

    // ========================================
    // MAP-ACTION PIPELINE: Generate Map Commands
    // ========================================
    console.log('[MapActionPipeline] Generating map commands...');
    ctx.mapCommands = generateMapCommands(ctx);

    console.log('[Pipeline] Complete');

    return response;
}

// ============================================================================
// CORS & HTTP HANDLER
// ============================================================================

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

// ============================================================================
// MAIN HANDLER (STREAMING SUPPORT)
// ============================================================================


Deno.serve(async (req) => {
    // 1. Handle CORS Preflight
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    try {
        // 2. Parse Request (includes viewportState for Map-Action Pipeline)
        const { query, userLocation, userId, viewportState } = await req.json();

        if (viewportState) {
            console.log('[Request] Viewport state received:', JSON.stringify(viewportState));
        }

        // 3. Create Streaming Response
        const encoder = new TextEncoder();
        const stream = new ReadableStream({
            async start(controller) {
                try {
                    await streamResponse(controller, encoder, query, userLocation, userId, viewportState);
                } catch (error) {
                    console.error('Stream error:', error);
                    const errorMsg = JSON.stringify({
                        type: 'error',
                        error: error instanceof Error ? error.message : 'Unknown error'
                    }) + '\n\n';
                    controller.enqueue(encoder.encode(errorMsg));
                    controller.close();
                }
            }
        });

        // 4. Return Stream with SSE-compatible headers
        return new Response(stream, {
            headers: {
                ...corsHeaders,
                'Content-Type': 'text/event-stream',
                'Cache-Control': 'no-cache',
                'Connection': 'keep-alive',
            }
        });

    } catch (error) {
        return new Response(
            JSON.stringify({ error: error instanceof Error ? error.message : 'Internal Server Error' }),
            { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }
});

// ============================================================================
// STREAMING ORCHESTRATOR
// ============================================================================

/**
 * Orchestrates the streaming response with REAL AI Reasoning:
 * 1. Emits initial "thinking" event
 * 2. Calls backend agents (OpenRouter with reasoning enabled)
 * 3. Streams "reasoning" events from OpenRouter's reasoning_details
 * 4. Emits "final_response" event with the result
 */
async function streamResponse(
    controller: ReadableStreamDefaultController,
    encoder: TextEncoder,
    query: string,
    userLocation: any,
    userId: string,
    viewportState?: ViewportState
) {
    const send = (type: string, data: any) => {
        const payload = JSON.stringify({ type, data }) + '\n\n'; // Double newline for SSE/delimiters
        controller.enqueue(encoder.encode(payload));
    };

    // --- STEP 1: INITIAL THINKING ---
    send('thinking', 'Processing your request...');
    await delay(100);

    const startTime = Date.now();
    let result: any;
    let reasoningDetails: OpenRouterReasoningDetail[] = [];

    try {
        // Fast-path routing logic
        const routeDecision = routeByIntent(query, userLocation);

        if (shouldUseSpecializedEndpoint(routeDecision)) {
            // For specialized endpoints, we use simulated reasoning briefly
            send('reasoning', 'Identifying query intent...');
            await delay(150);

            if (routeDecision.endpoint === EndpointType.TRACK_BUS) {
                const paramBus = routeDecision.extractedParams.busName || routeDecision.extractedParams.busNumber || 'bus';
                send('reasoning', `Looking up "${paramBus}" in the database...`);
                await delay(150);
                send('reasoning', 'Connecting to real-time GPS tracking...');
            } else if (routeDecision.endpoint === EndpointType.FIND_ROUTE) {
                send('reasoning', 'Extracting origin and destination...');
                await delay(150);
                send('reasoning', 'Searching for optimal bus routes...');
            } else if (routeDecision.endpoint === EndpointType.NEARBY_STOPS) {
                send('reasoning', 'Getting your current location...');
                await delay(150);
                send('reasoning', 'Searching for nearby bus stops...');
            }

            // Execute Specialized Endpoint
            result = await delegateToEndpoint(routeDecision, userLocation, userId);

        } else {
            // Full Pipeline with REAL OpenRouter Reasoning
            send('reasoning', 'Understanding your question...');

            const ctx: PipelineContext = {
                userId,
                query,
                userLocation,
                startTime: Date.now(),
                hub: createCommunicationHub(),
                agentResults: new Map(),
                agentTrace: [], // Initialize agent trace for A2A visibility
                // Map-Action Pipeline: Include viewport state for spatial awareness
                viewportState,
                // Pass streaming callback so Layer5 can emit reasoning in real-time
                streamReasoning: async (text: string) => {
                    send('reasoning', text);
                    await delay(100); // Small delay for UI animation
                }
            };

            // Execute pipeline (this will call OpenRouter with reasoning enabled)
            const pipelineResponse = await executePipeline(ctx);

            // Extract reasoning details if available from context
            reasoningDetails = (ctx as unknown as { reasoningDetails?: OpenRouterReasoningDetail[] }).reasoningDetails || [];

            // Stream real reasoning steps to client
            if (reasoningDetails.length > 0) {
                console.log('[Streaming] Found', reasoningDetails.length, 'reasoning steps to stream');
                for (const detail of reasoningDetails) {
                    // OpenRouter uses 'reasoning.text' type with 'text' field
                    const reasoningText = detail.text || detail.content || '';
                    if (reasoningText && reasoningText.length > 10) {
                        // Extract first sentence or limit length for readability
                        const shortContent = reasoningText.split('.')[0].trim();
                        if (shortContent.length > 10 && shortContent.length < 200) {
                            send('reasoning', shortContent + '...');
                            await delay(120); // Pace for readable animation
                        } else if (reasoningText.length < 200) {
                            send('reasoning', reasoningText);
                            await delay(120);
                        }
                    }
                }
            } else {
                // Fallback if no reasoning details available
                console.log('[Streaming] No reasoning details, using fallback');
                send('reasoning', 'Analyzing bus routes and schedules...');
                await delay(200);
                send('reasoning', 'Calculating optimal paths...');
            }

            // Format to DelegatedResponse shape
            // Build map tool call if visualization would help (now async, delegates to get-map-data)
            const mapToolCall = await buildMapToolCall(ctx);

            result = {
                success: true,
                response: pipelineResponse,
                intent: ctx.intent?.primaryIntent || 'GENERAL',
                executionTimeMs: Date.now() - startTime,
                routeData: ctx.bestRoute,
                agentTrace: ctx.agentTrace, // Include agent execution trace
                reasoningDetails: reasoningDetails,
                // Agentic tool calls - AI decides when to show map
                toolCalls: mapToolCall ? [mapToolCall] : undefined,
                // Map-Action Pipeline: AI-generated map commands
                map_commands: ctx.mapCommands
            };
        }


    } catch (e) {
        console.error("Pipeline failure", e);
        result = {
            success: false,
            response: "Sorry, I encountered a system error while processing.",
            executionTimeMs: Date.now() - startTime,
            agentTrace: []
        };
    }

    // --- FINAL STEP: Send Result ---
    send('reasoning', 'Finalizing response...');
    await delay(100);
    send('final_response', result);

    // Close stream
    controller.close();
}

// Helper for artificial delay to make reading possible
function delay(ms: number) {
    return new Promise(resolve => setTimeout(resolve, ms));
}
