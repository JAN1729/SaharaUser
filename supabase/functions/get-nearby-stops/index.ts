/**
 * Get Nearby Stops Edge Function
 * 
 * Find bus stops near a given location.
 * 
 * POST /functions/v1/get-nearby-stops
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

import type { NearbyStopsRequest, NearbyStopsResponse, Vehicle, RouteStop } from '../_shared/types.ts';
import { findNearbyStops } from '../_shared/algorithms.ts';

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

        const response: NearbyStopsResponse = {
            success: true,
            stops: nearbyStops,
            count: nearbyStops.length
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
