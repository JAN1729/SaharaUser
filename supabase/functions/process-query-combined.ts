/**
 * Process Query Edge Function - COMBINED (All-in-One)
 * 
 * This file contains ALL code needed for the multi-agent system.
 * Paste this ENTIRE file into the Supabase Dashboard.
 * 
 * No external imports from _shared - everything is inline.
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient, SupabaseClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// TYPES
// ============================================================================

interface Vehicle {
    id: string;
    name: string;
    vehicle_number: string;
    owner_id: string;
    is_active: boolean;
    created_at: string;
}

interface RouteStop {
    id: string;
    vehicle_id: string;
    name: string;
    latitude: number;
    longitude: number;
    stop_order: number;
    created_at: string;
}

interface LiveLocation {
    id: string;
    bus_id: string;
    operator_id: string;
    latitude: number;
    longitude: number;
    speed: number | null;
    heading: number | null;
    timestamp: string;
}

interface AgentContext {
    taskId: string;
    userId: string;
    originalQuery: string;
    userLocation?: { latitude: number; longitude: number };
    sharedData: Map<string, unknown>;
    agentResults: Map<string, AgentOutput>;
    executionTrace: TraceEvent[];
    startTime: number;
}

interface AgentInput {
    intent: string;
    parameters: Record<string, unknown>;
    previousResults?: Record<string, AgentOutput>;
}

interface AgentOutput {
    success: boolean;
    agentName: string;
    data: Record<string, unknown>;
    message?: string;
    error?: string;
    confidence: number;
    suggestedNextAgent?: string;
    toolsUsed: string[];
    executionTimeMs: number;
}

interface TraceEvent {
    type: string;
    message: string;
    data?: Record<string, unknown>;
    timestamp: number;
}

interface Agent {
    name: string;
    description: string;
    execute: (context: AgentContext, input: AgentInput) => Promise<AgentOutput>;
}

interface ParsedQuery {
    intent: string;
    confidence: number;
    parameters: {
        origin?: string;
        destination?: string;
        busId?: string;
        stopName?: string;
    };
    requiredAgents: string[];
}

interface ProcessQueryRequest {
    userId: string;
    query: string;
    userLocation?: { latitude: number; longitude: number };
}

interface ProcessQueryResponse {
    success: boolean;
    response: string;
    intent: string;
    routeData?: any;
    agentTrace: TraceEvent[];
    executionTimeMs: number;
    error?: string;
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
// CONTEXT HELPERS
// ============================================================================

function createContext(
    userId: string,
    query: string,
    userLocation?: { latitude: number; longitude: number }
): AgentContext {
    return {
        taskId: crypto.randomUUID(),
        userId,
        originalQuery: query,
        userLocation,
        sharedData: new Map(),
        agentResults: new Map(),
        executionTrace: [],
        startTime: Date.now()
    };
}

function trace(context: AgentContext, type: string, message: string): void {
    context.executionTrace.push({ type, message, timestamp: Date.now() });
    console.log(`[${type}] ${message}`);
}

function setData(context: AgentContext, key: string, value: unknown): void {
    context.sharedData.set(key, value);
}

function getData<T>(context: AgentContext, key: string): T | undefined {
    return context.sharedData.get(key) as T | undefined;
}

// ============================================================================
// ALGORITHMS
// ============================================================================

function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const R = 6371;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function calculateETA(distanceKm: number, numStops: number, busSpeed?: number) {
    const avgSpeed = busSpeed && busSpeed > 0 ? busSpeed : 18;
    const hour = new Date().getHours();
    const isRush = (hour >= 8 && hour <= 10) || (hour >= 17 && hour <= 20);
    const trafficFactor = isRush ? 1.3 : 1.0;

    const travelTime = (distanceKm / avgSpeed) * 60 * trafficFactor;
    const stopTime = numStops * 0.5;
    const eta = Math.round(travelTime + stopTime);

    return {
        etaMinutes: eta,
        etaRangeMin: Math.round(eta * 0.85),
        etaRangeMax: Math.round(eta * 1.25),
        trafficFactor
    };
}

function findNearbyStops(
    userLat: number,
    userLng: number,
    stops: RouteStop[],
    vehicles: Vehicle[],
    radiusKm: number
) {
    const vehicleMap = new Map(vehicles.map(v => [v.id, v]));
    const nearby: any[] = [];

    for (const stop of stops) {
        const dist = haversineDistance(userLat, userLng, stop.latitude, stop.longitude);
        if (dist <= radiusKm) {
            const vehicle = vehicleMap.get(stop.vehicle_id);
            nearby.push({
                name: stop.name,
                latitude: stop.latitude,
                longitude: stop.longitude,
                distanceKm: Math.round(dist * 1000) / 1000,
                vehicleId: stop.vehicle_id,
                vehicleName: vehicle?.name || stop.vehicle_id
            });
        }
    }

    return nearby.sort((a, b) => a.distanceKm - b.distanceKm).slice(0, 10);
}

// ============================================================================
// QUERY PARSER
// ============================================================================

function parseQuery(query: string, userLat?: number, userLng?: number): ParsedQuery {
    const q = query.toLowerCase().trim();

    // Extract locations
    let origin: string | undefined;
    let destination: string | undefined;
    let busId: string | undefined;

    // Pattern: from X to Y
    const fromToMatch = q.match(/(?:from|at|near)\s+([a-z\s]+?)\s+(?:to|towards?)\s+([a-z\s]+)/i);
    if (fromToMatch) {
        origin = fromToMatch[1].trim();
        destination = fromToMatch[2].trim();
    }

    // Pattern: X to Y
    if (!origin && !destination) {
        const simpleMatch = q.match(/([a-z\s]+?)\s+to\s+([a-z\s]+)/i);
        if (simpleMatch) {
            origin = simpleMatch[1].replace(/^(how|can|i|get|go|take|me|bus|route)\s+/gi, '').trim();
            destination = simpleMatch[2].replace(/[?.!]$/g, '').trim();
        }
    }

    // Detect bus tracking
    const busMatch = q.match(/(?:where\s+is|track|find)\s+(?:bus\s+)?([a-z0-9]+)/i);
    if (busMatch) {
        busId = busMatch[1];
    }

    // Determine intent
    let intent = 'UNKNOWN';
    let confidence = 0.5;
    const requiredAgents: string[] = [];

    if (origin && destination) {
        intent = 'FIND_ROUTE';
        confidence = 0.9;
        requiredAgents.push('DataAgent', 'PathAgent', 'TimeAgent');
    } else if (busId || q.includes('where') && q.includes('bus')) {
        intent = 'TRACK_BUS';
        confidence = 0.85;
        requiredAgents.push('DataAgent', 'TrackerAgent');
    } else if (q.includes('eta') || q.includes('how long') || q.includes('time')) {
        intent = 'CALCULATE_ETA';
        confidence = 0.8;
        requiredAgents.push('DataAgent', 'TimeAgent');
    } else if (q.includes('nearby') || q.includes('near me') || q.includes('closest')) {
        intent = 'FIND_NEARBY';
        confidence = 0.85;
        requiredAgents.push('DataAgent');
    } else if (q.includes('hi') || q.includes('hello') || q.includes('help')) {
        intent = 'GENERAL';
        confidence = 0.9;
    }

    return {
        intent,
        confidence,
        parameters: { origin, destination, busId },
        requiredAgents
    };
}

// ============================================================================
// AGENTS
// ============================================================================

const DataAgent: Agent = {
    name: 'DataAgent',
    description: 'Fetches data from Supabase',
    async execute(context: AgentContext, _input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Fetching data from Supabase');

        try {
            const client = getSupabase();

            const { data: vehicles } = await client.from('vehicles').select('*');
            const { data: stops } = await client.from('route_stops').select('*').order('vehicle_id').order('stop_order');
            const { data: locations } = await client.from('locations').select('*').order('timestamp', { ascending: false });

            // Latest location per bus
            const latestLocations = new Map<string, LiveLocation>();
            for (const loc of (locations || []) as LiveLocation[]) {
                if (!latestLocations.has(loc.bus_id)) {
                    latestLocations.set(loc.bus_id, loc);
                }
            }

            setData(context, 'vehicles', vehicles || []);
            setData(context, 'stops', stops || []);
            setData(context, 'live_locations', Array.from(latestLocations.values()));

            return {
                success: true,
                agentName: 'DataAgent',
                data: { vehicles_count: vehicles?.length, stops_count: stops?.length, live_count: latestLocations.size },
                message: `Loaded ${vehicles?.length} vehicles, ${stops?.length} stops`,
                confidence: 1.0,
                toolsUsed: ['supabase_query'],
                executionTimeMs: Date.now() - startTime
            };
        } catch (error) {
            return {
                success: false, agentName: 'DataAgent', data: {},
                error: error instanceof Error ? error.message : 'Data fetch failed',
                confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime
            };
        }
    }
};

const PathAgent: Agent = {
    name: 'PathAgent',
    description: 'Finds routes',
    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Finding routes');

        const origin = input.parameters.origin as string;
        const destination = input.parameters.destination as string;

        if (!origin || !destination) {
            return { success: false, agentName: 'PathAgent', data: {}, error: 'Origin and destination required', confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime };
        }

        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];
        const locations = getData<LiveLocation[]>(context, 'live_locations') || [];

        // Find matching routes
        const matchedRoutes: any[] = [];

        for (const vehicle of vehicles) {
            const vehicleStops = stops.filter(s => s.vehicle_id === vehicle.id).sort((a, b) => a.stop_order - b.stop_order);

            const originStop = vehicleStops.find(s => s.name.toLowerCase().includes(origin.toLowerCase()));
            const destStop = vehicleStops.find(s => s.name.toLowerCase().includes(destination.toLowerCase()));

            if (originStop && destStop && originStop.stop_order < destStop.stop_order) {
                const stopsInPath = vehicleStops.filter(s => s.stop_order >= originStop.stop_order && s.stop_order <= destStop.stop_order);

                // Calculate distance
                let distance = 0;
                for (let i = 0; i < stopsInPath.length - 1; i++) {
                    distance += haversineDistance(
                        stopsInPath[i].latitude, stopsInPath[i].longitude,
                        stopsInPath[i + 1].latitude, stopsInPath[i + 1].longitude
                    );
                }

                const liveLocation = locations.find(l => l.bus_id === vehicle.id);
                const eta = calculateETA(distance, stopsInPath.length - 1, liveLocation?.speed || undefined);

                let busStopsAway: number | undefined;
                if (liveLocation) {
                    let nearestStopOrder = 0;
                    let minDist = Infinity;
                    for (const s of vehicleStops) {
                        const d = haversineDistance(liveLocation.latitude, liveLocation.longitude, s.latitude, s.longitude);
                        if (d < minDist) { minDist = d; nearestStopOrder = s.stop_order; }
                    }
                    busStopsAway = originStop.stop_order - nearestStopOrder;
                }

                matchedRoutes.push({
                    vehicleId: vehicle.id,
                    vehicleName: vehicle.name,
                    vehicleNumber: vehicle.vehicle_number,
                    distanceKm: Math.round(distance * 10) / 10,
                    numStops: stopsInPath.length - 1,
                    etaMinutes: eta.etaMinutes,
                    isLive: !!liveLocation,
                    busStopsAway,
                    stopsInPath: stopsInPath.map(s => s.name)
                });
            }
        }

        if (matchedRoutes.length === 0) {
            return { success: false, agentName: 'PathAgent', data: { route_found: false }, error: 'No direct route found', suggestedNextAgent: 'TransferAgent', confidence: 0, toolsUsed: ['compute_route'], executionTimeMs: Date.now() - startTime };
        }

        // Sort by ETA
        matchedRoutes.sort((a, b) => a.etaMinutes - b.etaMinutes);
        const bestRoute = matchedRoutes[0];

        setData(context, 'best_route', bestRoute);
        setData(context, 'alternatives', matchedRoutes.slice(1));
        setData(context, 'eta_minutes', bestRoute.etaMinutes);

        return {
            success: true,
            agentName: 'PathAgent',
            data: { best_route: bestRoute, alternatives: matchedRoutes.slice(1), route_found: true },
            message: `Found route via ${bestRoute.vehicleName}`,
            confidence: 0.9,
            toolsUsed: ['compute_route'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

const TrackerAgent: Agent = {
    name: 'TrackerAgent',
    description: 'Tracks bus position',
    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Tracking bus');

        const targetBusId = input.parameters.bus_id as string;
        const targetBusName = input.parameters.vehicle_name as string;

        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];
        const locations = getData<LiveLocation[]>(context, 'live_locations') || [];

        let liveLocation: LiveLocation | undefined;

        if (targetBusId) {
            liveLocation = locations.find(l => l.bus_id === targetBusId);
        }
        if (!liveLocation && targetBusName) {
            liveLocation = locations.find(loc => {
                const v = vehicles.find(v => v.id === loc.bus_id);
                return v?.name.toLowerCase().includes(targetBusName.toLowerCase());
            });
        }

        if (!liveLocation) {
            return { success: false, agentName: 'TrackerAgent', data: {}, error: 'Bus not broadcasting', confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime };
        }

        const vehicle = vehicles.find(v => v.id === liveLocation!.bus_id);
        const routeStops = stops.filter(s => s.vehicle_id === liveLocation!.bus_id);

        let nearestStop = routeStops[0];
        let minDist = Infinity;
        for (const s of routeStops) {
            const d = haversineDistance(liveLocation.latitude, liveLocation.longitude, s.latitude, s.longitude);
            if (d < minDist) { minDist = d; nearestStop = s; }
        }

        return {
            success: true,
            agentName: 'TrackerAgent',
            data: {
                bus_id: liveLocation.bus_id,
                bus_name: vehicle?.name,
                latitude: liveLocation.latitude,
                longitude: liveLocation.longitude,
                speed: liveLocation.speed || 0,
                is_moving: (liveLocation.speed || 0) > 2,
                nearest_stop: nearestStop?.name || 'Unknown'
            },
            message: `${vehicle?.name} near ${nearestStop?.name}`,
            confidence: 0.9,
            toolsUsed: ['haversine'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// CORS
// ============================================================================

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

// ============================================================================
// MAIN HANDLER
// ============================================================================

serve(async (req: Request) => {
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    if (req.method !== 'POST') {
        return new Response(JSON.stringify({ error: 'Method not allowed' }), { status: 405, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    try {
        const request: ProcessQueryRequest = await req.json();
        const { userId, query, userLocation } = request;

        if (!userId || !query) {
            return new Response(JSON.stringify({ error: 'userId and query are required' }), { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        console.log(`Processing: ${query}`);

        const context = createContext(userId, query, userLocation);
        const parsedQuery = parseQuery(query, userLocation?.latitude, userLocation?.longitude);

        console.log(`Intent: ${parsedQuery.intent}`);

        // Handle general queries
        if (parsedQuery.intent === 'GENERAL' || parsedQuery.confidence < 0.5) {
            return new Response(JSON.stringify({
                success: true,
                response: '👋 Hello! I can help you find bus routes, track buses, or get ETAs. Where would you like to go?',
                intent: parsedQuery.intent,
                agentTrace: context.executionTrace,
                executionTimeMs: Date.now() - context.startTime
            }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        // Execute agents
        let result: AgentOutput;

        // Step 1: Always fetch data first
        const dataResult = await DataAgent.execute(context, { intent: parsedQuery.intent, parameters: parsedQuery.parameters });
        if (!dataResult.success) {
            return new Response(JSON.stringify({
                success: false,
                response: 'Could not fetch data. Please try again.',
                intent: parsedQuery.intent,
                agentTrace: context.executionTrace,
                executionTimeMs: Date.now() - context.startTime,
                error: dataResult.error
            }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        // Step 2: Execute based on intent
        switch (parsedQuery.intent) {
            case 'FIND_ROUTE':
                result = await PathAgent.execute(context, { intent: parsedQuery.intent, parameters: parsedQuery.parameters });
                break;

            case 'TRACK_BUS':
                result = await TrackerAgent.execute(context, { intent: parsedQuery.intent, parameters: { bus_id: parsedQuery.parameters.busId, vehicle_name: parsedQuery.parameters.busId } });
                break;

            case 'FIND_NEARBY':
                if (userLocation) {
                    const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
                    const stops = getData<RouteStop[]>(context, 'stops') || [];
                    const nearby = findNearbyStops(userLocation.latitude, userLocation.longitude, stops, vehicles, 1.0);
                    result = {
                        success: nearby.length > 0,
                        agentName: 'NearbyAgent',
                        data: { stops: nearby },
                        message: nearby.length > 0 ? `Found ${nearby.length} stops nearby` : 'No stops within 1km',
                        confidence: 1.0, toolsUsed: [], executionTimeMs: 0
                    };
                } else {
                    result = { success: false, agentName: 'NearbyAgent', data: {}, error: 'Location required', confidence: 0, toolsUsed: [], executionTimeMs: 0 };
                }
                break;

            default:
                result = { success: false, agentName: 'Unknown', data: {}, error: 'Intent not supported', confidence: 0, toolsUsed: [], executionTimeMs: 0 };
        }

        // Generate response
        let responseText = '';
        let routeData: any;

        if (result.success) {
            const bestRoute = getData<any>(context, 'best_route');
            if (bestRoute) {
                responseText = `🚌 **${bestRoute.vehicleName}** (${bestRoute.vehicleNumber}) • ${bestRoute.distanceKm} km • ~${bestRoute.etaMinutes} min`;
                if (bestRoute.isLive && bestRoute.busStopsAway !== undefined && bestRoute.busStopsAway >= 0) {
                    responseText += ` • Bus is ${bestRoute.busStopsAway} stops away`;
                }
                routeData = bestRoute;
            } else if (result.data.stops) {
                const stops = result.data.stops as any[];
                responseText = `📍 Nearby: ${stops.slice(0, 3).map((s: any) => s.name).join(', ')}`;
            } else if (result.data.bus_name) {
                responseText = `📍 **${result.data.bus_name}** is near **${result.data.nearest_stop}**`;
            } else {
                responseText = result.message || 'Done!';
            }
        } else {
            responseText = result.error || 'Could not process request.';
        }

        // Save search history
        if (parsedQuery.parameters.origin || parsedQuery.parameters.destination) {
            try {
                await getSupabase().from('user_search_history').insert({
                    user_id: userId,
                    query,
                    origin_name: parsedQuery.parameters.origin,
                    destination_name: parsedQuery.parameters.destination
                });
            } catch (e) { console.error('Failed to save history:', e); }
        }

        return new Response(JSON.stringify({
            success: result.success,
            response: responseText,
            intent: parsedQuery.intent,
            routeData,
            agentTrace: context.executionTrace,
            executionTimeMs: Date.now() - context.startTime
        }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

    } catch (error) {
        console.error('Error:', error);
        return new Response(JSON.stringify({
            success: false,
            response: 'Something went wrong. Please try again.',
            intent: 'error',
            agentTrace: [],
            executionTimeMs: 0,
            error: error instanceof Error ? error.message : 'Unknown error'
        }), { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }
});
