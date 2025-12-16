/**
 * Sync User Location Edge Function
 * 
 * Receives real-time GPS location from the SaharaUser Android app
 * and stores it in the user_locations table for AI agent access.
 * 
 * Endpoint: POST /functions/v1/sync-user-location
 * 
 * Request body:
 * {
 *   user_id: string,       // USR-XXXXXXXX format
 *   tracking_id: string,   // u-XXXX format (4-digit alphanumeric)
 *   latitude: number,
 *   longitude: number,
 *   accuracy?: number,     // GPS accuracy in meters
 *   speed?: number,        // Speed in m/s
 *   heading?: number,      // Direction in degrees
 *   altitude?: number,     // Altitude in meters
 *   timestamp?: string,    // ISO timestamp (defaults to now)
 *   device_info?: object   // Optional device metadata
 * }
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
    timestamp?: string;
    device_info?: Record<string, unknown>;
}

interface UserLocationRow {
    user_id: string;
    tracking_id: string;
    latitude: number;
    longitude: number;
    accuracy: number | null;
    speed: number | null;
    heading: number | null;
    altitude: number | null;
    timestamp: string;
    device_info: Record<string, unknown>;
}

// ============================================================================
// HELPERS
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

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

function validateRequest(body: UserLocationRequest): string | null {
    // Required fields
    if (!body.user_id || typeof body.user_id !== 'string') {
        return 'user_id is required and must be a string';
    }
    if (!body.tracking_id || typeof body.tracking_id !== 'string') {
        return 'tracking_id is required and must be a string';
    }
    if (typeof body.latitude !== 'number' || body.latitude < -90 || body.latitude > 90) {
        return 'latitude must be a number between -90 and 90';
    }
    if (typeof body.longitude !== 'number' || body.longitude < -180 || body.longitude > 180) {
        return 'longitude must be a number between -180 and 180';
    }

    // Validate tracking_id format (u-XXXX) - case insensitive!
    const trackingIdPattern = /^u-[A-Za-z0-9]{4}$/;
    if (!trackingIdPattern.test(body.tracking_id)) {
        return 'tracking_id must be in format u-XXXX (4 alphanumeric characters)';
    }

    return null; // Valid
}

// ============================================================================
// MAIN HANDLER
// ============================================================================

serve(async (req: Request) => {
    // Handle CORS preflight
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    // Only allow POST
    if (req.method !== 'POST') {
        return new Response(
            JSON.stringify({ success: false, error: 'Method not allowed' }),
            { status: 405, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }

    try {
        const body: UserLocationRequest = await req.json();

        console.log('=== SYNC-USER-LOCATION DEBUG ===');
        console.log('Received request:', JSON.stringify(body));

        // Validate input
        const validationError = validateRequest(body);
        if (validationError) {
            console.log('Validation failed:', validationError);
            return new Response(
                JSON.stringify({ success: false, error: validationError }),
                { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        const client = getSupabase();

        // First, ensure user exists (to avoid foreign key constraint error)
        const { data: existingUser } = await client
            .from('users')
            .select('id')
            .eq('id', body.user_id)
            .single();

        if (!existingUser) {
            console.log('User not found, creating:', body.user_id);
            // Create a minimal user record
            const { error: userError } = await client
                .from('users')
                .insert({
                    id: body.user_id,
                    phone: '0000000000', // placeholder
                    tracking_id: body.tracking_id
                });

            if (userError) {
                console.error('Failed to create user:', userError);
                // Continue anyway - maybe it's a race condition
            }
        }

        // Prepare location row
        const locationRow: UserLocationRow = {
            user_id: body.user_id,
            tracking_id: body.tracking_id,
            latitude: body.latitude,
            longitude: body.longitude,
            accuracy: body.accuracy ?? null,
            speed: body.speed ?? null,
            heading: body.heading ?? null,
            altitude: body.altitude ?? null,
            timestamp: body.timestamp ?? new Date().toISOString(),
            device_info: body.device_info ?? {}
        };

        console.log('Inserting location:', JSON.stringify(locationRow));

        // Insert into user_locations
        const { data, error } = await client
            .from('user_locations')
            .insert(locationRow)
            .select('id, timestamp')
            .single();

        if (error) {
            console.error('Database error:', JSON.stringify(error));
            return new Response(
                JSON.stringify({ success: false, error: error.message, details: error }),
                { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
            );
        }

        // Optionally update the user's tracking_id if not set
        await client
            .from('users')
            .update({ tracking_id: body.tracking_id, last_active: new Date().toISOString() })
            .eq('id', body.user_id)
            .is('tracking_id', null);

        console.log(`Location synced: ${body.user_id} (${body.tracking_id}) at ${body.latitude}, ${body.longitude}`);

        return new Response(
            JSON.stringify({
                success: true,
                id: data.id,
                timestamp: data.timestamp,
                message: 'Location synced successfully'
            }),
            { headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );

    } catch (error) {
        console.error('Error:', error);
        return new Response(
            JSON.stringify({
                success: false,
                error: error instanceof Error ? error.message : 'Unknown error'
            }),
            { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
    }
});
