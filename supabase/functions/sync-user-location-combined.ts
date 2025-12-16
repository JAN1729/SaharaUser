/**
 * Sync User Location Edge Function - COMBINED (All-in-One)
 * 
 * Paste this ENTIRE file into the Supabase Dashboard.
 * 
 * Receives real-time GPS location from SaharaUser Android app
 * and stores it for AI agent access.
 * 
 * Request: POST { user_id, tracking_id, latitude, longitude, accuracy?, speed?, heading? }
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient, SupabaseClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// TYPES
// ============================================================================

interface UserLocationRequest {
    user_id: string;
    tracking_id: string;
    latitude: number;
    longitude: number;
    accuracy?: number;
    speed?: number;
    heading?: number;
    altitude?: number;
    recorded_at?: string;
    device_info?: Record<string, unknown>;
}

// ============================================================================
// SETUP
// ============================================================================

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

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

// ============================================================================
// HANDLER
// ============================================================================

serve(async (req: Request) => {
    if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });
    if (req.method !== 'POST') {
        return new Response(JSON.stringify({ success: false, error: 'Method not allowed' }),
            { status: 405, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    try {
        const body: UserLocationRequest = await req.json();

        // Validate
        if (!body.user_id || !body.tracking_id) {
            return new Response(JSON.stringify({ success: false, error: 'user_id and tracking_id required' }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }
        if (typeof body.latitude !== 'number' || typeof body.longitude !== 'number') {
            return new Response(JSON.stringify({ success: false, error: 'latitude and longitude must be numbers' }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }
        if (!/^u-[A-Z0-9]{4}$/.test(body.tracking_id)) {
            return new Response(JSON.stringify({ success: false, error: 'tracking_id must be u-XXXX format' }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        const client = getSupabase();

        // Insert location
        const { data, error } = await client.from('user_locations').insert({
            user_id: body.user_id,
            tracking_id: body.tracking_id,
            latitude: body.latitude,
            longitude: body.longitude,
            accuracy: body.accuracy ?? null,
            speed: body.speed ?? null,
            heading: body.heading ?? null,
            altitude: body.altitude ?? null,
            recorded_at: body.recorded_at ?? new Date().toISOString(),
            device_info: body.device_info ?? {}
        }).select('id, recorded_at').single();

        if (error) {
            console.error('DB error:', error);
            return new Response(JSON.stringify({ success: false, error: error.message }),
                { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        // Update user's tracking_id if not set
        await client.from('users')
            .update({ tracking_id: body.tracking_id, last_active: new Date().toISOString() })
            .eq('id', body.user_id).is('tracking_id', null);

        console.log(`Synced: ${body.tracking_id} @ ${body.latitude},${body.longitude}`);

        return new Response(JSON.stringify({
            success: true, id: data.id, recorded_at: data.recorded_at
        }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

    } catch (error) {
        console.error('Error:', error);
        return new Response(JSON.stringify({
            success: false, error: error instanceof Error ? error.message : 'Unknown error'
        }), { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }
});
