/**
 * Get Nearby Stops Edge Function - COMBINED (All-in-One)
 * Paste this ENTIRE file into the Supabase Dashboard.
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient, SupabaseClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// TYPES & HELPERS
// ============================================================================

interface Vehicle { id: string; name: string; vehicle_number: string; }
interface RouteStop { id: string; vehicle_id: string; name: string; latitude: number; longitude: number; stop_order: number; }

let supabase: SupabaseClient | null = null;
function getSupabase(): SupabaseClient {
    if (!supabase) {
        const url = Deno.env.get('SUPABASE_URL');
        const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
        if (!url || !key) throw new Error('Missing Supabase env vars');
        supabase = createClient(url, key);
    }
    return supabase;
}

function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const R = 6371;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLng / 2) ** 2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

// ============================================================================
// MAIN HANDLER
// ============================================================================

serve(async (req: Request) => {
    if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });
    if (req.method !== 'POST') return new Response(JSON.stringify({ error: 'Method not allowed' }), { status: 405, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

    try {
        const { latitude, longitude, radiusKm = 1.0 } = await req.json();

        if (typeof latitude !== 'number' || typeof longitude !== 'number') {
            return new Response(JSON.stringify({ success: false, error: 'latitude and longitude required' }), { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        const client = getSupabase();

        const { data: vehicles } = await client.from('vehicles').select('*');
        const { data: stops } = await client.from('route_stops').select('*');

        const vehicleMap = new Map((vehicles as Vehicle[] || []).map(v => [v.id, v]));
        const nearbyStops: any[] = [];

        for (const stop of (stops as RouteStop[] || [])) {
            const dist = haversineDistance(latitude, longitude, stop.latitude, stop.longitude);
            if (dist <= radiusKm) {
                const vehicle = vehicleMap.get(stop.vehicle_id);
                nearbyStops.push({
                    name: stop.name,
                    latitude: stop.latitude,
                    longitude: stop.longitude,
                    distanceKm: Math.round(dist * 1000) / 1000,
                    vehicleId: stop.vehicle_id,
                    vehicleName: vehicle?.name || stop.vehicle_id
                });
            }
        }

        nearbyStops.sort((a, b) => a.distanceKm - b.distanceKm);

        return new Response(JSON.stringify({
            success: true,
            stops: nearbyStops.slice(0, 10),
            count: nearbyStops.length
        }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

    } catch (error) {
        console.error('Error:', error);
        return new Response(JSON.stringify({ success: false, error: error instanceof Error ? error.message : 'Unknown error' }), { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }
});
