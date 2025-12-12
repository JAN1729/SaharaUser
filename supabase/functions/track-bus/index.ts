/**
 * Track Bus Edge Function
 * 
 * Real-time bus position tracking endpoint.
 * 
 * POST /functions/v1/track-bus
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

import type { TrackBusRequest, TrackBusResponse, Vehicle, LiveLocation, RouteStop } from '../_shared/types.ts';
import { haversineDistance } from '../_shared/algorithms.ts';

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
        const request: TrackBusRequest = await req.json();
        const { busId, busName } = request;

        if (!busId && !busName) {
            return new Response(
                JSON.stringify({ success: false, error: 'busId or busName is required' }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        const url = Deno.env.get('SUPABASE_URL');
        const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

        if (!url || !key) {
            throw new Error('Missing Supabase configuration');
        }

        const supabase = createClient(url, key);

        // Fetch vehicles
        const { data: vehicles } = await supabase.from('vehicles').select('*');

        // Fetch live locations
        const { data: locations } = await supabase
            .from('locations')
            .select('*')
            .order('timestamp', { ascending: false });

        // Get latest per bus
        const latestLocations = new Map<string, LiveLocation>();
        for (const loc of (locations || []) as LiveLocation[]) {
            if (!latestLocations.has(loc.bus_id)) {
                latestLocations.set(loc.bus_id, loc);
            }
        }

        // Find target bus
        let targetLocation: LiveLocation | undefined;
        let targetVehicle: Vehicle | undefined;

        if (busId) {
            targetLocation = latestLocations.get(busId);
            targetVehicle = (vehicles as Vehicle[])?.find(v => v.id === busId);
        }

        if (!targetLocation && busName) {
            for (const [bId, loc] of latestLocations) {
                const vehicle = (vehicles as Vehicle[])?.find(v => v.id === bId);
                if (vehicle?.name.toLowerCase().includes(busName.toLowerCase()) ||
                    vehicle?.vehicle_number.toLowerCase().includes(busName.toLowerCase())) {
                    targetLocation = loc;
                    targetVehicle = vehicle;
                    break;
                }
            }
        }

        if (!targetLocation) {
            const response: TrackBusResponse = {
                success: false,
                busId: busId || '',
                busName: busName || '',
                position: { latitude: 0, longitude: 0 },
                speed: 0,
                isMoving: false,
                nearestStop: '',
                nearestStopDistance: 0,
                freshness: 'stale',
                timestamp: '',
                error: 'Bus not currently sharing location'
            };

            return new Response(
                JSON.stringify(response),
                { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        // Fetch route stops for this bus
        const { data: stops } = await supabase
            .from('route_stops')
            .select('*')
            .eq('vehicle_id', targetLocation.bus_id)
            .order('stop_order');

        // Find nearest stop
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

        // Calculate freshness
        const timestamp = new Date(targetLocation.timestamp).getTime();
        const age = Date.now() - timestamp;
        const freshness = age < 2 * 60 * 1000 ? 'fresh' : age < 10 * 60 * 1000 ? 'recent' : 'stale';

        const response: TrackBusResponse = {
            success: true,
            busId: targetLocation.bus_id,
            busName: targetVehicle?.name || targetLocation.bus_id,
            position: {
                latitude: targetLocation.latitude,
                longitude: targetLocation.longitude
            },
            speed: targetLocation.speed || 0,
            isMoving: (targetLocation.speed || 0) > 2,
            nearestStop,
            nearestStopDistance: Math.round(nearestDistance * 1000),
            freshness,
            timestamp: targetLocation.timestamp
        };

        return new Response(
            JSON.stringify(response),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );

    } catch (error) {
        console.error('Error tracking bus:', error);

        return new Response(
            JSON.stringify({
                success: false,
                error: error instanceof Error ? error.message : 'Unknown error'
            }),
            { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }
});
