/**
 * Get Walking Directions Edge Function
 * 
 * Provides turn-by-turn walking directions from user's location to a bus stop
 * using the Mapbox Directions API.
 * 
 * POST /functions/v1/get-walking-directions
 * 
 * This function can:
 * 1. Accept specific origin/destination coordinates
 * 2. Automatically find the nearest bus stop if only origin is provided
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// TYPES
// ============================================================================

interface WalkingDirectionsRequest {
    // User's current location
    originLat: number;
    originLng: number;
    // Optional: specific destination (if not provided, finds nearest stop)
    destLat?: number;
    destLng?: number;
    destStopName?: string;
    // Options
    language?: string;  // Default: 'en'
    findNearestStop?: boolean;  // If true and no dest, auto-find nearest
}

interface WalkingStep {
    instruction: string;
    distance: number;      // meters
    duration: number;      // seconds
    maneuver?: string;     // e.g., 'turn-right', 'straight'
}

interface WalkingRoute {
    distanceMeters: number;
    durationMinutes: number;
    durationSeconds: number;
    steps: WalkingStep[];
    summary: string;
    geometry?: string;     // Encoded polyline for map display
}

interface WalkingDirectionsResponse {
    success: boolean;
    walkingRoute?: WalkingRoute;
    destinationStop?: {
        name: string;
        latitude: number;
        longitude: number;
        vehicleName: string;
        distanceKm: number;
    };
    error?: string;
    message?: string;
}

interface RouteStop {
    id: string;
    vehicle_id: string;
    name: string;
    latitude: number;
    longitude: number;
    stop_order: number;
}

interface Vehicle {
    id: string;
    name: string;
    vehicle_number: string;
    is_active: boolean;
}

// ============================================================================
// CONSTANTS
// ============================================================================

const MAPBOX_API_URL = 'https://api.mapbox.com/directions/v5/mapbox/walking';
const EARTH_RADIUS_KM = 6371;

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Haversine distance calculation
 */
function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const φ1 = lat1 * Math.PI / 180;
    const φ2 = lat2 * Math.PI / 180;
    const Δφ = (lat2 - lat1) * Math.PI / 180;
    const Δλ = (lng2 - lng1) * Math.PI / 180;

    const a = Math.sin(Δφ / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) ** 2;
    return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * Find nearest bus stop to given coordinates
 */
function findNearestStop(
    lat: number,
    lng: number,
    stops: RouteStop[],
    vehicles: Vehicle[]
): { stop: RouteStop; vehicle: Vehicle | undefined; distanceKm: number } | null {
    const vehicleMap = new Map(vehicles.map(v => [v.id, v]));
    let nearest: { stop: RouteStop; vehicle: Vehicle | undefined; distanceKm: number } | null = null;
    let minDistance = Infinity;

    for (const stop of stops) {
        const distance = haversineDistance(lat, lng, stop.latitude, stop.longitude);
        if (distance < minDistance) {
            minDistance = distance;
            nearest = {
                stop,
                vehicle: vehicleMap.get(stop.vehicle_id),
                distanceKm: Math.round(distance * 1000) / 1000
            };
        }
    }

    return nearest;
}

/**
 * Call Mapbox Directions API for walking directions
 */
async function getMapboxWalkingDirections(
    originLng: number,
    originLat: number,
    destLng: number,
    destLat: number,
    language: string = 'en'
): Promise<WalkingRoute | null> {
    const accessToken = Deno.env.get('MAPBOX_ACCESS_TOKEN');

    if (!accessToken) {
        console.error('[WalkingDirections] MAPBOX_ACCESS_TOKEN not configured');
        throw new Error('Mapbox access token not configured');
    }

    // Mapbox uses longitude,latitude format
    const coordinates = `${originLng},${originLat};${destLng},${destLat}`;
    const url = `${MAPBOX_API_URL}/${coordinates}?` + new URLSearchParams({
        access_token: accessToken,
        steps: 'true',
        overview: 'full',
        geometries: 'polyline',
        language: language
    });

    console.log(`[WalkingDirections] Calling Mapbox API for route: ${originLat},${originLng} → ${destLat},${destLng}`);

    const response = await fetch(url);

    if (!response.ok) {
        const errorText = await response.text();
        console.error('[WalkingDirections] Mapbox API error:', response.status, errorText);
        throw new Error(`Mapbox API error: ${response.status}`);
    }

    const data = await response.json();

    if (!data.routes || data.routes.length === 0) {
        console.log('[WalkingDirections] No walking route found');
        return null;
    }

    const route = data.routes[0];
    const legs = route.legs[0];

    // Extract steps
    const steps: WalkingStep[] = (legs.steps || []).map((step: {
        maneuver?: { instruction?: string; type?: string };
        distance?: number;
        duration?: number;
    }) => ({
        instruction: step.maneuver?.instruction || 'Continue',
        distance: Math.round(step.distance || 0),
        duration: Math.round(step.duration || 0),
        maneuver: step.maneuver?.type
    }));

    const durationSeconds = Math.round(route.duration);
    const durationMinutes = Math.ceil(durationSeconds / 60);
    const distanceMeters = Math.round(route.distance);

    return {
        distanceMeters,
        durationMinutes,
        durationSeconds,
        steps,
        summary: `${durationMinutes} min walk (${distanceMeters}m)`,
        geometry: route.geometry
    };
}

// ============================================================================
// MAIN HANDLER
// ============================================================================

serve(async (req: Request) => {
    // Handle CORS preflight
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
        const request: WalkingDirectionsRequest = await req.json();
        const { originLat, originLng, destLat, destLng, language = 'en', findNearestStop: shouldFindNearest = true } = request;

        // Validate origin coordinates
        if (originLat === undefined || originLng === undefined) {
            return new Response(
                JSON.stringify({
                    success: false,
                    error: 'Origin coordinates (originLat, originLng) are required'
                }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        let finalDestLat = destLat;
        let finalDestLng = destLng;
        let destinationStopInfo: WalkingDirectionsResponse['destinationStop'] = undefined;

        // If no destination provided, find nearest bus stop
        if (finalDestLat === undefined || finalDestLng === undefined) {
            if (!shouldFindNearest) {
                return new Response(
                    JSON.stringify({
                        success: false,
                        error: 'Destination coordinates required when findNearestStop is false'
                    }),
                    { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
                );
            }

            console.log('[WalkingDirections] No destination provided, finding nearest stop...');

            // Initialize Supabase
            const url = Deno.env.get('SUPABASE_URL');
            const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

            if (!url || !key) {
                throw new Error('Missing Supabase configuration');
            }

            const supabase = createClient(url, key);

            // Fetch stops and vehicles
            const [stopsResult, vehiclesResult] = await Promise.all([
                supabase.from('route_stops').select('*'),
                supabase.from('vehicles').select('*').eq('is_active', true)
            ]);

            const stops = (stopsResult.data || []) as RouteStop[];
            const vehicles = (vehiclesResult.data || []) as Vehicle[];

            if (stops.length === 0) {
                return new Response(
                    JSON.stringify({
                        success: false,
                        error: 'No bus stops found in the system'
                    }),
                    { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
                );
            }

            // Find nearest stop
            const nearest = findNearestStop(originLat, originLng, stops, vehicles);

            if (!nearest) {
                return new Response(
                    JSON.stringify({
                        success: false,
                        error: 'Could not find a nearby bus stop'
                    }),
                    { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
                );
            }

            finalDestLat = nearest.stop.latitude;
            finalDestLng = nearest.stop.longitude;
            destinationStopInfo = {
                name: nearest.stop.name,
                latitude: nearest.stop.latitude,
                longitude: nearest.stop.longitude,
                vehicleName: nearest.vehicle?.name || 'Unknown',
                distanceKm: nearest.distanceKm
            };

            console.log(`[WalkingDirections] Found nearest stop: ${nearest.stop.name} (${nearest.distanceKm} km)`);
        }

        // Get walking directions from Mapbox
        const walkingRoute = await getMapboxWalkingDirections(
            originLng,
            originLat,
            finalDestLng,
            finalDestLat,
            language
        );

        if (!walkingRoute) {
            return new Response(
                JSON.stringify({
                    success: false,
                    error: 'Could not calculate walking route',
                    destinationStop: destinationStopInfo
                }),
                { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        // Build AI-friendly message
        let message = `🚶 **Walking Directions**\n`;
        if (destinationStopInfo) {
            message += `📍 To: **${destinationStopInfo.name}** (${destinationStopInfo.vehicleName})\n`;
        }
        message += `⏱️ ${walkingRoute.summary}\n\n`;
        message += `**Steps:**\n`;
        walkingRoute.steps.slice(0, 5).forEach((step, i) => {
            message += `${i + 1}. ${step.instruction} (${step.distance}m)\n`;
        });
        if (walkingRoute.steps.length > 5) {
            message += `... and ${walkingRoute.steps.length - 5} more steps`;
        }

        const response: WalkingDirectionsResponse = {
            success: true,
            walkingRoute,
            destinationStop: destinationStopInfo,
            message
        };

        return new Response(
            JSON.stringify(response),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );

    } catch (error) {
        console.error('[WalkingDirections] Error:', error);

        return new Response(
            JSON.stringify({
                success: false,
                error: error instanceof Error ? error.message : 'Unknown error'
            }),
            { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }
});
