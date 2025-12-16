/**
 * Get Nearby Stops Edge Function
 * 
 * Find bus stops near a given location.
 * 
 * POST /functions/v1/get-nearby-stops
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// INLINE TYPES (avoiding _shared/ import issues with Supabase bundler)
// ============================================================================

interface NearbyStopsRequest {
    latitude: number;
    longitude: number;
    radiusKm?: number;
}

interface NearbyStopsResponse {
    success: boolean;
    stops: Array<{
        name: string;
        latitude: number;
        longitude: number;
        distanceKm: number;
        vehicleId: string;
        vehicleName: string;
    }>;
    count: number;
    message?: string;
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

// Haversine distance calculation (inlined from algorithms.ts)
function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const R = 6371; // Earth's radius in km
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// Find nearby stops (inlined from algorithms.ts)
function findNearbyStops(
    lat: number,
    lng: number,
    stops: RouteStop[],
    vehicles: Vehicle[],
    radiusKm: number = 1.0
): Array<{ name: string; latitude: number; longitude: number; distanceKm: number; vehicleId: string; vehicleName: string }> {
    const vehicleMap = new Map(vehicles.map(v => [v.id, v]));
    const nearbyStops: Array<{ name: string; latitude: number; longitude: number; distanceKm: number; vehicleId: string; vehicleName: string }> = [];

    for (const stop of stops) {
        const distance = haversineDistance(lat, lng, stop.latitude, stop.longitude);
        if (distance <= radiusKm) {
            const vehicle = vehicleMap.get(stop.vehicle_id);
            nearbyStops.push({
                name: stop.name,
                latitude: stop.latitude,
                longitude: stop.longitude,
                distanceKm: Math.round(distance * 1000) / 1000,
                vehicleId: stop.vehicle_id,
                vehicleName: vehicle?.name || stop.vehicle_id
            });
        }
    }

    return nearbyStops.sort((a, b) => a.distanceKm - b.distanceKm);
}

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

serve(async (req: Request) => {
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    if (req.method !== 'POST') {
        return new Response(
            JSON.stringify({ error: 'Method not allowed' }),
            { status: 405, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }

    try {
        const request: NearbyStopsRequest = await req.json();
        const { latitude, longitude, radiusKm = 1.0 } = request;

        if (latitude === undefined || longitude === undefined) {
            return new Response(
                JSON.stringify({ success: false, stops: [], count: 0, error: 'latitude and longitude are required' }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        const url = Deno.env.get('SUPABASE_URL');
        const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

        if (!url || !key) {
            throw new Error('Missing Supabase configuration');
        }

        const supabase = createClient(url, key);

        // Fetch vehicles and stops
        const [vehiclesResult, stopsResult] = await Promise.all([
            supabase.from('vehicles').select('*'),
            supabase.from('route_stops').select('*')
        ]);

        const vehicles = (vehiclesResult.data || []) as Vehicle[];
        const stops = (stopsResult.data || []) as RouteStop[];

        // Find nearby stops
        const nearbyStops = findNearbyStops(
            latitude,
            longitude,
            stops,
            vehicles,
            radiusKm
        );

        // Generate AI-friendly message based on the data
        let message = '';
        if (nearbyStops.length === 0) {
            message = `📍 No bus stops found within ${radiusKm} km of your location. Try increasing the search radius.`;
        } else if (nearbyStops.length === 1) {
            const stop = nearbyStops[0];
            message = `📍 Found 1 bus stop nearby:\n` +
                `🚏 **${stop.name}** (${stop.vehicleName}) - ${Math.round(stop.distanceKm * 1000)}m away`;
        } else {
            const top3 = nearbyStops.slice(0, 3);
            message = `📍 Found ${nearbyStops.length} bus stops within ${radiusKm} km:\n` +
                top3.map((s, i) => `${i + 1}. **${s.name}** (${s.vehicleName}) - ${Math.round(s.distanceKm * 1000)}m`).join('\n');
            if (nearbyStops.length > 3) {
                message += `\n... and ${nearbyStops.length - 3} more`;
            }
        }

        const response: NearbyStopsResponse = {
            success: true,
            stops: nearbyStops,
            count: nearbyStops.length,
            message
        };

        return new Response(
            JSON.stringify(response),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );

    } catch (error) {
        console.error('Error finding nearby stops:', error);

        return new Response(
            JSON.stringify({
                success: false,
                stops: [],
                count: 0,
                error: error instanceof Error ? error.message : 'Unknown error'
            }),
            { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }
});
