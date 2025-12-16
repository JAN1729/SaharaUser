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
}

interface LiveLocation {
    bus_id: string;
    latitude: number;
    longitude: number;
    speed: number | null;
    timestamp: string;
}

interface RouteResult {
    vehicleId: string;
    vehicleName: string;
    vehicleNumber: string;
    originStop: string;
    destinationStop: string;
    stopsInPath: string[];
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

        // 1. First, find stops matching origin and destination (case-insensitive)
        const originSearch = origin || '';
        const destSearch = destination;

        console.log(`[find-route] Searching stops for: origin="${originSearch}", dest="${destSearch}"`);

        // Fetch stops that match origin or destination
        let stopsQuery = supabase.from('route_stops').select('*');

        // If we have both origin and destination, fetch all stops for matching vehicles
        const { data: allStops } = await stopsQuery.order('vehicle_id').order('stop_order');

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

        // 2. Find stops matching origin and destination
        const originLower = originSearch.toLowerCase();
        const destLower = destSearch.toLowerCase();

        const originStops = originSearch
            ? allStops.filter(s => s.name.toLowerCase().includes(originLower))
            : [];
        const destStops = allStops.filter(s => s.name.toLowerCase().includes(destLower));

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

        // 3. Find vehicles that have BOTH origin and destination in correct order
        const vehicleStopsMap = new Map<string, RouteStop[]>();
        for (const stop of allStops as RouteStop[]) {
            const existing = vehicleStopsMap.get(stop.vehicle_id) || [];
            existing.push(stop);
            vehicleStopsMap.set(stop.vehicle_id, existing);
        }

        // Find matching routes
        interface RouteMatch {
            vehicleId: string;
            originStop: RouteStop;
            destStop: RouteStop;
            stopsInPath: RouteStop[];
        }

        const matches: RouteMatch[] = [];

        for (const destStop of destStops) {
            const vehicleStops = vehicleStopsMap.get(destStop.vehicle_id) || [];

            if (originSearch) {
                // Find origin stops BEFORE destination on this route
                const validOrigins = vehicleStops.filter(s =>
                    s.name.toLowerCase().includes(originLower) &&
                    s.stop_order < destStop.stop_order
                );

                for (const originStop of validOrigins) {
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

        // 4. Fetch ONLY the vehicles we need
        const vehicleIds = [...new Set(matches.map(m => m.vehicleId))];
        const { data: vehicles } = await supabase
            .from('vehicles')
            .select('*')
            .in('id', vehicleIds)
            .eq('is_active', true);

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

            return {
                vehicleId: match.vehicleId,
                vehicleName: vehicle?.name || match.vehicleId,
                vehicleNumber: vehicle?.vehicle_number || '',
                originStop: match.originStop.name,
                destinationStop: match.destStop.name,
                stopsInPath: match.stopsInPath.map(s => s.name),
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
