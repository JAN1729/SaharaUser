/**
 * Find Route Edge Function - Specialized Route Finding Endpoint
 * 
 * Optimized endpoint for route queries that fetches ONLY the specific data
 * needed for accurate route finding:
 * - Vehicles that service the route
 * - Stops matching origin/destination
 * - Live locations for ETA calculation
 * 
 * POST /functions/v1/find-route
 * { origin: "MG Road", destination: "Electronic City", userLocation?: {...} }
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient, SupabaseClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// TYPES
// ============================================================================

interface FindRouteRequest {
    origin?: string;
    destination?: string;
    userLocation?: {
        latitude: number;
        longitude: number;
    };
}

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
    timestamp: number;
}

// Extended stop info with coordinates for AI pathfinding
interface RouteStopInfo {
    name: string;
    latitude: number;
    longitude: number;
    stopOrder: number;
}

interface RouteResult {
    vehicleId: string;
    vehicleName: string;
    vehicleNumber: string;
    originStop: string;
    destinationStop: string;
    stopsInPath: string[];
    // NEW: Full stop sequence with coordinates for AI agent pathfinding
    routeSequence: RouteStopInfo[];
    // NEW: Origin and destination coordinates
    originStopLat: number;
    originStopLng: number;
    destinationStopLat: number;
    destinationStopLng: number;
    distanceKm: number;
    etaMinutes: number;
    isLive: boolean;
    busStopsAway?: number;
}

interface FindRouteResponse {
    success: boolean;
    bestRoute?: RouteResult;
    alternatives: RouteResult[];
    message: string;
    executionTimeMs: number;
    error?: string;
}

// ============================================================================
// CORS HEADERS
// ============================================================================

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

// ============================================================================
// HELPER FUNCTIONS
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

function calculateETA(distanceKm: number, numStops: number, currentSpeed?: number): number {
    const hour = new Date().getHours();
    let trafficFactor = 1.0;
    if ((hour >= 8 && hour <= 10) || (hour >= 17 && hour <= 20)) {
        trafficFactor = 1.4;
    }
    const baseSpeed = currentSpeed && currentSpeed > 0 ? currentSpeed : 18;
    const effectiveSpeed = baseSpeed / trafficFactor;
    const travelTime = (distanceKm / effectiveSpeed) * 60;
    const stopTime = numStops * 0.5;
    return Math.round(travelTime + stopTime);
}

// ============================================================================
// ROUTE QUERY HELPERS - For AI Agent Pathfinding
// ============================================================================

/**
 * Get ordered route stops for a specific vehicle.
 * Returns stops sorted by stop_order for proper sequence navigation.
 */
async function getOrderedRouteStops(
    supabase: SupabaseClient,
    vehicleId: string
): Promise<RouteStop[]> {
    const { data, error } = await supabase
        .from('route_stops')
        .select('id, vehicle_id, name, latitude, longitude, stop_order')
        .eq('vehicle_id', vehicleId)
        .order('stop_order', { ascending: true });

    if (error) {
        console.error(`[getOrderedRouteStops] Error for vehicle ${vehicleId}:`, error);
        return [];
    }
    return (data || []) as RouteStop[];
}

/**
 * Find all vehicles/buses passing through a stop matching the name.
 * Returns deduplicated vehicle IDs.
 */
async function findBusesByStopName(
    supabase: SupabaseClient,
    stopName: string
): Promise<string[]> {
    const { data, error } = await supabase
        .from('route_stops')
        .select('vehicle_id')
        .ilike('name', `%${stopName}%`);

    if (error) {
        console.error(`[findBusesByStopName] Error for stop "${stopName}":`, error);
        return [];
    }
    return [...new Set((data || []).map((s: { vehicle_id: string }) => s.vehicle_id))] as string[];
}

/**
 * Get the next stop in sequence after a given stop order.
 * Useful for "What's the next stop?" queries.
 */
async function getNextStopAfter(
    supabase: SupabaseClient,
    vehicleId: string,
    currentStopOrder: number
): Promise<RouteStop | null> {
    const { data, error } = await supabase
        .from('route_stops')
        .select('id, vehicle_id, name, latitude, longitude, stop_order')
        .eq('vehicle_id', vehicleId)
        .gt('stop_order', currentStopOrder)
        .order('stop_order', { ascending: true })
        .limit(1)
        .single();

    if (error || !data) return null;
    return data as RouteStop;
}

/**
 * Get all routes grouped by vehicle ID with stop sequence info.
 * Returns map of vehicleId -> ordered stops array.
 */
async function getAllRoutesGrouped(
    supabase: SupabaseClient,
    vehicleIds?: string[]
): Promise<Map<string, RouteStop[]>> {
    let query = supabase
        .from('route_stops')
        .select('id, vehicle_id, name, latitude, longitude, stop_order')
        .order('vehicle_id')
        .order('stop_order', { ascending: true });

    if (vehicleIds && vehicleIds.length > 0) {
        query = query.in('vehicle_id', vehicleIds);
    }

    const { data, error } = await query;

    if (error) {
        console.error('[getAllRoutesGrouped] Error:', error);
        return new Map();
    }

    const grouped = new Map<string, RouteStop[]>();
    for (const stop of (data || []) as RouteStop[]) {
        const existing = grouped.get(stop.vehicle_id) || [];
        existing.push(stop);
        grouped.set(stop.vehicle_id, existing);
    }
    return grouped;
}

// ============================================================================
// MAIN HANDLER
// ============================================================================

serve(async (req: Request) => {
    const startTime = Date.now();

    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    if (req.method !== 'POST') {
        return new Response(
            JSON.stringify({ success: false, error: 'Method not allowed' }),
            { status: 405, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }

    try {
        const request: FindRouteRequest = await req.json();
        const { origin, destination, userLocation } = request;

        console.log(`[find-route] Query: ${origin} → ${destination}`);

        // Validate input
        if (!destination) {
            return new Response(
                JSON.stringify({
                    success: false,
                    alternatives: [],
                    message: 'Please specify where you want to go.',
                    executionTimeMs: Date.now() - startTime,
                    error: 'Destination is required'
                }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        // Get Supabase client
        const url = Deno.env.get('SUPABASE_URL');
        const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
        if (!url || !key) throw new Error('Missing Supabase configuration');
        const supabase = createClient(url, key);

        // ============================================
        // TARGETED DATA FETCHING - Only what we need!
        // ============================================

        // 1. Use full-text search with tsvector for fuzzy matching
        const originSearch = origin || '';
        const destSearch = destination;

        console.log(`[find-route] Searching stops for: origin="${originSearch}", dest="${destSearch}"`);

        // Helper function to search stops (RPC with ILIKE fallback)
        // FIX Issue 4: Filter for active vehicles EARLY to save CPU
        async function searchStops(query: string): Promise<RouteStop[]> {
            // Try RPC first - now filters active vehicles
            const { data: rpcStops, error: rpcError } = await supabase.rpc('search_stops_fuzzy', {
                search_query: query,
                max_results: 50
            });

            if (rpcError) {
                // Fallback to ILIKE with INNER JOIN on active vehicles
                console.warn('[find-route] RPC fallback for:', query, rpcError.message);
                const { data: fallbackStops, error: fallbackError } = await supabase
                    .from('route_stops')
                    .select(`
                        id, vehicle_id, name, latitude, longitude, stop_order,
                        vehicles!inner(is_active)
                    `)
                    .ilike('name', `%${query}%`)
                    .eq('vehicles.is_active', true);
                if (fallbackError) throw fallbackError;
                // Map back to RouteStop interface (remove nested vehicles object)
                return (fallbackStops || []).map((s: any) => ({
                    id: s.id,
                    vehicle_id: s.vehicle_id,
                    name: s.name,
                    latitude: s.latitude,
                    longitude: s.longitude,
                    stop_order: s.stop_order
                })) as RouteStop[];
            }
            return (rpcStops || []) as RouteStop[];
        }

        // Step 1: Search for DESTINATION stops (required)
        const destStops = await searchStops(destSearch);

        if (destStops.length === 0) {
            return new Response(
                JSON.stringify({
                    success: false,
                    alternatives: [],
                    message: `Sorry, I couldn't find any bus stops matching "${destination}". Please try a different location.`,
                    executionTimeMs: Date.now() - startTime
                }),
                { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        console.log(`[find-route] Found ${destStops.length} destination stops via FTS`);

        // Step 2: Search for ORIGIN stops (optional - if provided)
        let originStops: RouteStop[] = [];
        if (originSearch) {
            originStops = await searchStops(originSearch);
            console.log(`[find-route] Found ${originStops.length} origin stops via FTS`);
        }

        // Step 3: Get common vehicle IDs (routes that have BOTH origin AND destination)
        const destinationVehicleIds = new Set(destStops.map(s => s.vehicle_id));

        // FIX Issue 2: Intersect by vehicle_id if origin provided (mirrors real route engines)
        let commonVehicleIds: string[];
        if (originStops.length > 0) {
            const originVehicleIds = new Set(originStops.map(s => s.vehicle_id));
            commonVehicleIds = [...destinationVehicleIds].filter(id => originVehicleIds.has(id));
            console.log(`[find-route] Intersected: ${commonVehicleIds.length} common vehicles from ${destinationVehicleIds.size} dest / ${originVehicleIds.size} origin`);
        } else {
            commonVehicleIds = [...destinationVehicleIds];
            console.log(`[find-route] Destination stops span ${commonVehicleIds.length} vehicles`);
        }

        if (commonVehicleIds.length === 0) {
            return new Response(
                JSON.stringify({
                    success: false,
                    alternatives: [],
                    message: `Sorry, I couldn't find a direct bus route from "${origin}" to "${destination}". Try checking for transfer options.`,
                    executionTimeMs: Date.now() - startTime
                }),
                { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        // Step 4: Fetch FULL routes for ONLY common vehicles (with early active filter)
        const { data: allStops, error: stopsError } = await supabase
            .from('route_stops')
            .select(`
                id, vehicle_id, name, latitude, longitude, stop_order,
                vehicles!inner(is_active)
            `)
            .in('vehicle_id', commonVehicleIds)
            .eq('vehicles.is_active', true)
            .order('vehicle_id')
            .order('stop_order');

        if (stopsError) {
            console.error('[find-route] Error fetching vehicle stops:', stopsError);
            throw stopsError;
        }

        if (!allStops || allStops.length === 0) {
            return new Response(
                JSON.stringify({
                    success: false,
                    alternatives: [],
                    message: 'No bus stops found in the system.',
                    executionTimeMs: Date.now() - startTime
                }),
                { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        // Step 5: Find vehicles that have BOTH origin and destination in correct order
        const vehicleStopsMap = new Map<string, RouteStop[]>();
        for (const stop of allStops as any[]) {
            // Map back to RouteStop (strip nested vehicles object from join)
            const routeStop: RouteStop = {
                id: stop.id,
                vehicle_id: stop.vehicle_id,
                name: stop.name,
                latitude: stop.latitude,
                longitude: stop.longitude,
                stop_order: stop.stop_order
            };
            const existing = vehicleStopsMap.get(routeStop.vehicle_id) || [];
            existing.push(routeStop);
            vehicleStopsMap.set(routeStop.vehicle_id, existing);
        }

        // Find matching routes
        interface RouteMatch {
            vehicleId: string;
            originStop: RouteStop;
            destStop: RouteStop;
            stopsInPath: RouteStop[];
        }

        const matches: RouteMatch[] = [];

        // FIX Issue 3: Track seen routes to prevent duplicates from alias/name overlap
        const seenRoutes = new Set<string>();

        // Create a Set of origin stop IDs for O(1) lookup (FTS results are ground truth)
        const originStopIds = new Set(originStops.map(s => s.id));

        for (const destStop of destStops) {
            const vehicleStops = vehicleStopsMap.get(destStop.vehicle_id) || [];

            if (originSearch && originStops.length > 0) {
                // Find origin stops BEFORE destination on this route (using FTS stop IDs)
                const validOrigins = vehicleStops.filter(s =>
                    originStopIds.has(s.id) &&
                    s.stop_order < destStop.stop_order
                );

                for (const originStop of validOrigins) {
                    // Create composite key to dedupe: vehicleId + originStopOrder + destStopOrder
                    const routeKey = `${destStop.vehicle_id}:${originStop.stop_order}:${destStop.stop_order}`;
                    if (seenRoutes.has(routeKey)) continue;
                    seenRoutes.add(routeKey);

                    const stopsInPath = vehicleStops
                        .filter(s => s.stop_order >= originStop.stop_order && s.stop_order <= destStop.stop_order)
                        .sort((a, b) => a.stop_order - b.stop_order);

                    matches.push({
                        vehicleId: destStop.vehicle_id,
                        originStop,
                        destStop,
                        stopsInPath
                    });
                }
            } else if (userLocation) {
                // No origin specified - use user's current location
                // Find the closest stop on this route to the user
                let closestStop: RouteStop | null = null;
                let closestDist = Infinity;

                for (const stop of vehicleStops) {
                    if (stop.stop_order < destStop.stop_order) {
                        const dist = haversineDistance(
                            userLocation.latitude, userLocation.longitude,
                            stop.latitude, stop.longitude
                        );
                        if (dist < closestDist) {
                            closestDist = dist;
                            closestStop = stop;
                        }
                    }
                }

                if (closestStop && closestDist < 3) { // Within 3km
                    // Dedupe for GPS-based matching too
                    const routeKey = `${destStop.vehicle_id}:${closestStop.stop_order}:${destStop.stop_order}`;
                    if (seenRoutes.has(routeKey)) continue;
                    seenRoutes.add(routeKey);

                    const stopsInPath = vehicleStops
                        .filter(s => s.stop_order >= closestStop!.stop_order && s.stop_order <= destStop.stop_order)
                        .sort((a, b) => a.stop_order - b.stop_order);

                    matches.push({
                        vehicleId: destStop.vehicle_id,
                        originStop: closestStop,
                        destStop,
                        stopsInPath
                    });
                }
            }
        }

        console.log(`[find-route] Found ${matches.length} unique route matches (deduped from ${seenRoutes.size} keys)`);


        if (matches.length === 0) {
            const noOriginMsg = origin
                ? `Sorry, I couldn't find a direct bus route from "${origin}" to "${destination}".`
                : `Sorry, I couldn't find a bus route to "${destination}" from your location.`;

            return new Response(
                JSON.stringify({
                    success: false,
                    alternatives: [],
                    message: noOriginMsg + ' Try checking for transfer options or alternative stops.',
                    executionTimeMs: Date.now() - startTime
                }),
                { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        // 4. Fetch ONLY the vehicles we need (already pre-filtered for active in earlier steps)
        const vehicleIds = [...new Set(matches.map(m => m.vehicleId))];
        const { data: vehicles } = await supabase
            .from('vehicles')
            .select('*')
            .in('id', vehicleIds);
        // Note: is_active filter already applied during route_stops fetch via INNER JOIN

        const vehicleMap = new Map<string, Vehicle>();
        for (const v of (vehicles || []) as Vehicle[]) {
            vehicleMap.set(v.id, v);
        }

        // 5. Fetch LATEST locations for these vehicles only
        const { data: locations } = await supabase
            .from('locations')
            .select('bus_id, latitude, longitude, speed, timestamp')
            .in('bus_id', vehicleIds)
            .order('timestamp', { ascending: false });

        const latestLocations = new Map<string, LiveLocation>();
        for (const loc of (locations || []) as LiveLocation[]) {
            if (!latestLocations.has(loc.bus_id)) {
                latestLocations.set(loc.bus_id, loc);
            }
        }

        // ============================================
        // BUILD ROUTE RESULTS
        // ============================================

        const results: RouteResult[] = matches.map(match => {
            const vehicle = vehicleMap.get(match.vehicleId);
            const location = latestLocations.get(match.vehicleId);

            // Calculate distance along route
            let distance = 0;
            for (let i = 0; i < match.stopsInPath.length - 1; i++) {
                distance += haversineDistance(
                    match.stopsInPath[i].latitude, match.stopsInPath[i].longitude,
                    match.stopsInPath[i + 1].latitude, match.stopsInPath[i + 1].longitude
                );
            }

            // Calculate bus stops away if live
            let busStopsAway: number | undefined;
            if (location) {
                const vehicleStops = vehicleStopsMap.get(match.vehicleId) || [];
                let nearestBusStop = -1;
                let minDist = Infinity;

                for (const stop of vehicleStops) {
                    const dist = haversineDistance(
                        location.latitude, location.longitude,
                        stop.latitude, stop.longitude
                    );
                    if (dist < minDist) {
                        minDist = dist;
                        nearestBusStop = stop.stop_order;
                    }
                }

                if (nearestBusStop >= 0 && nearestBusStop < match.originStop.stop_order) {
                    busStopsAway = match.originStop.stop_order - nearestBusStop;
                }
            }

            const eta = calculateETA(distance, match.stopsInPath.length - 1, location?.speed ?? undefined);

            // Build full route sequence with coordinates for AI pathfinding
            const routeSequence: RouteStopInfo[] = match.stopsInPath.map(s => ({
                name: s.name,
                latitude: s.latitude,
                longitude: s.longitude,
                stopOrder: s.stop_order
            }));

            return {
                vehicleId: match.vehicleId,
                vehicleName: vehicle?.name || match.vehicleId,
                vehicleNumber: vehicle?.vehicle_number || '',
                originStop: match.originStop.name,
                destinationStop: match.destStop.name,
                stopsInPath: match.stopsInPath.map(s => s.name),
                routeSequence,
                originStopLat: match.originStop.latitude,
                originStopLng: match.originStop.longitude,
                destinationStopLat: match.destStop.latitude,
                destinationStopLng: match.destStop.longitude,
                distanceKm: Math.round(distance * 10) / 10,
                etaMinutes: eta,
                isLive: !!location,
                busStopsAway
            };
        });

        // Sort by ETA (shortest first)
        results.sort((a, b) => a.etaMinutes - b.etaMinutes);

        const bestRoute = results[0];
        const alternatives = results.slice(1, 4); // Top 3 alternatives

        // ============================================
        // GENERATE AI RESPONSE MESSAGE
        // ============================================

        let message = '';
        if (bestRoute) {
            const liveStatus = bestRoute.isLive
                ? bestRoute.busStopsAway !== undefined
                    ? ` (${bestRoute.busStopsAway} stops away)`
                    : ' (live tracking available)'
                : '';

            message = `🚌 Take **${bestRoute.vehicleName}** (${bestRoute.vehicleNumber}) ` +
                `from ${bestRoute.originStop} to ${bestRoute.destinationStop}.\n` +
                `📏 ${bestRoute.distanceKm} km • ${bestRoute.stopsInPath.length - 1} stops\n` +
                `⏱️ ETA: **${bestRoute.etaMinutes} min**${liveStatus}`;

            if (alternatives.length > 0) {
                message += `\n\n📋 Alternatives: ${alternatives.map(a => a.vehicleName).join(', ')}`;
            }
        }

        const response: FindRouteResponse = {
            success: true,
            bestRoute,
            alternatives,
            message,
            executionTimeMs: Date.now() - startTime
        };

        console.log(`[find-route] Found ${results.length} routes in ${response.executionTimeMs}ms`);

        return new Response(
            JSON.stringify(response),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );

    } catch (error) {
        console.error('[find-route] Error:', error);

        return new Response(
            JSON.stringify({
                success: false,
                alternatives: [],
                message: 'Sorry, something went wrong. Please try again.',
                executionTimeMs: Date.now() - startTime,
                error: error instanceof Error ? error.message : 'Unknown error'
            }),
            { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }
});
