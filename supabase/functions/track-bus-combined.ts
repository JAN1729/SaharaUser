/**
 * Track Bus Edge Function - COMBINED (All-in-One)
 * Paste this ENTIRE file into the Supabase Dashboard.
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient, SupabaseClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// TYPES & HELPERS
// ============================================================================

interface Vehicle { id: string; name: string; vehicle_number: string; }
interface RouteStop { id: string; vehicle_id: string; name: string; latitude: number; longitude: number; stop_order: number; }
interface LiveLocation { id: string; bus_id: string; latitude: number; longitude: number; speed: number | null; timestamp: string; }

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
        const { busId, busName } = await req.json();
        if (!busId && !busName) {
            return new Response(JSON.stringify({ success: false, error: 'busId or busName required' }), { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        const client = getSupabase();

        // Fetch data
        const { data: vehicles } = await client.from('vehicles').select('*');
        const { data: stops } = await client.from('route_stops').select('*');
        const { data: locations } = await client.from('locations').select('*').order('timestamp', { ascending: false });

        // Get latest per bus
        const latestLocations = new Map<string, LiveLocation>();
        for (const loc of (locations || []) as LiveLocation[]) {
            if (!latestLocations.has(loc.bus_id)) latestLocations.set(loc.bus_id, loc);
        }

        // Find target bus
        let liveLocation: LiveLocation | undefined;
        if (busId) {
            liveLocation = latestLocations.get(busId);
        }
        if (!liveLocation && busName) {
            for (const [id, loc] of latestLocations) {
                const v = (vehicles as Vehicle[])?.find(v => v.id === id);
                if (v?.name.toLowerCase().includes(busName.toLowerCase()) || v?.vehicle_number.toLowerCase().includes(busName.toLowerCase())) {
                    liveLocation = loc;
                    break;
                }
            }
        }

        if (!liveLocation) {
            return new Response(JSON.stringify({ success: false, error: 'Bus not broadcasting location' }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        const vehicle = (vehicles as Vehicle[])?.find(v => v.id === liveLocation!.bus_id);
        const routeStops = ((stops as RouteStop[]) || []).filter(s => s.vehicle_id === liveLocation!.bus_id);

        // Find nearest stop
        let nearestStop = routeStops[0];
        let minDist = Infinity;
        for (const s of routeStops) {
            const d = haversineDistance(liveLocation.latitude, liveLocation.longitude, s.latitude, s.longitude);
            if (d < minDist) { minDist = d; nearestStop = s; }
        }

        const timestamp = new Date(liveLocation.timestamp).getTime();
        const age = Date.now() - timestamp;
        const freshness = age < 2 * 60 * 1000 ? 'fresh' : age < 10 * 60 * 1000 ? 'recent' : 'stale';

        return new Response(JSON.stringify({
            success: true,
            busId: liveLocation.bus_id,
            busName: vehicle?.name || liveLocation.bus_id,
            position: { latitude: liveLocation.latitude, longitude: liveLocation.longitude },
            speed: liveLocation.speed || 0,
            isMoving: (liveLocation.speed || 0) > 2,
            nearestStop: nearestStop?.name || 'Unknown',
            nearestStopDistance: Math.round(minDist * 1000),
            freshness,
            timestamp: liveLocation.timestamp
        }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

    } catch (error) {
        console.error('Error:', error);
        return new Response(JSON.stringify({ success: false, error: error instanceof Error ? error.message : 'Unknown error' }), { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }
});
