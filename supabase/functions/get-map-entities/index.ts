import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

// ============================================================================
// GET-MAP-ENTITIES EDGE FUNCTION (Map-Action Pipeline - Phase 2)
// ============================================================================
// This function queries the database for all entities within a viewport.
// Used by the AI to understand what the user can see on their map screen.
// ============================================================================

interface ViewportRequest {
    boundingBox: {
        north: number;
        south: number;
        east: number;
        west: number;
    };
    zoomLevel?: number;
    maxResults?: number;
    freshnessSeconds?: number;
}

interface VisualQueryRequest {
    type: 'visual_query';
    tapLocation: {
        lat: number;
        lng: number;
    };
    radiusKm?: number;
}

interface DeviationRequest {
    type: 'deviation_check';
    busId: string;
    thresholdKm?: number;
}

type MapEntitiesRequest = ViewportRequest | VisualQueryRequest | DeviationRequest;

interface BusEntity {
    bus_id: string;
    vehicle_name: string;
    vehicle_number: string;
    latitude: number;
    longitude: number;
    heading: number | null;
    speed: number | null;
    trip_status: string | null;
    timestamp: number;
    freshness: 'live' | 'recent' | 'stale';
}

interface StopEntity {
    id: string;
    name: string;
    latitude: number;
    longitude: number;
    stop_order: number;
    vehicle_id: string;
    vehicle_name: string;
    vehicle_number: string;
}

interface MapEntitiesResponse {
    success: boolean;
    buses: BusEntity[];
    stops: StopEntity[];
    summary: string;
    viewportInfo?: {
        bounds: {
            north: number;
            south: number;
            east: number;
            west: number;
        };
        queryTimestamp: number;
    };
    error?: string;
}

serve(async (req) => {
    // Handle CORS preflight
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    try {
        // Initialize Supabase client
        const supabaseUrl = Deno.env.get('SUPABASE_URL');
        const supabaseKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

        if (!supabaseUrl || !supabaseKey) {
            throw new Error('Missing Supabase configuration');
        }

        const supabase = createClient(supabaseUrl, supabaseKey);
        const request: MapEntitiesRequest = await req.json();

        console.log('[MapEntities] Request:', JSON.stringify(request));

        // ========================================
        // CASE 1: Visual Query (tap location)
        // ========================================
        if ('type' in request && request.type === 'visual_query') {
            const { tapLocation, radiusKm = 0.2 } = request as VisualQueryRequest;

            console.log(`[MapEntities] Visual query at (${tapLocation.lat}, ${tapLocation.lng})`);

            const { data, error } = await supabase.rpc('get_nearby_entities_for_visual_query', {
                tap_lat: tapLocation.lat,
                tap_lng: tapLocation.lng,
                radius_km: radiusKm
            });

            if (error) {
                console.error('[MapEntities] Visual query RPC error:', error);
                throw error;
            }

            const buses = data?.buses || [];
            const stops = data?.stops || [];

            return new Response(JSON.stringify({
                success: true,
                buses,
                stops,
                summary: `Found ${buses.length} bus(es) and ${stops.length} stop(s) near tap location`,
                tapLocation
            }), {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' }
            });
        }

        // ========================================
        // CASE 2: Deviation Check (ghost bus)
        // ========================================
        if ('type' in request && request.type === 'deviation_check') {
            const { busId, thresholdKm = 0.5 } = request as DeviationRequest;

            console.log(`[MapEntities] Deviation check for bus: ${busId}`);

            const { data, error } = await supabase.rpc('detect_route_deviation', {
                p_bus_id: busId,
                deviation_threshold_km: thresholdKm
            });

            if (error) {
                console.error('[MapEntities] Deviation check RPC error:', error);
                throw error;
            }

            return new Response(JSON.stringify(data), {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' }
            });
        }

        // ========================================
        // CASE 3: Viewport Query (bounding box)
        // ========================================
        const viewportRequest = request as ViewportRequest;
        const { boundingBox, zoomLevel = 14, maxResults = 50, freshnessSeconds = 60 } = viewportRequest;

        if (!boundingBox || boundingBox.north === undefined) {
            return new Response(JSON.stringify({
                success: false,
                buses: [],
                stops: [],
                summary: 'Invalid request: boundingBox required',
                error: 'Missing boundingBox parameter'
            }), {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
                status: 400
            });
        }

        console.log(`[MapEntities] Viewport query: N=${boundingBox.north}, S=${boundingBox.south}, E=${boundingBox.east}, W=${boundingBox.west}, zoom=${zoomLevel}`);

        // Adjust max results based on zoom level
        // At low zoom (zoomed out), show fewer entities to avoid clutter
        const adjustedMaxResults = zoomLevel < 12 ? Math.min(maxResults, 20) : maxResults;

        // Call the RPC function
        const { data, error } = await supabase.rpc('get_entities_in_viewport', {
            north: boundingBox.north,
            south: boundingBox.south,
            east: boundingBox.east,
            west: boundingBox.west,
            max_results: adjustedMaxResults,
            freshness_seconds: freshnessSeconds
        });

        if (error) {
            console.error('[MapEntities] Viewport RPC error:', error);
            throw error;
        }

        const buses: BusEntity[] = data?.buses || [];
        const stops: StopEntity[] = data?.stops || [];
        const viewportInfo = data?.viewport_info;

        // Build a natural language summary for the AI
        let summary = '';
        if (buses.length === 0 && stops.length === 0) {
            summary = 'No buses or stops visible in the current map view.';
        } else {
            const busNames = buses.slice(0, 3).map((b: BusEntity) => b.vehicle_name).join(', ');
            const stopNames = stops.slice(0, 3).map((s: StopEntity) => s.name).join(', ');

            const parts: string[] = [];
            if (buses.length > 0) {
                parts.push(`${buses.length} bus${buses.length > 1 ? 'es' : ''} (${busNames}${buses.length > 3 ? '...' : ''})`);
            }
            if (stops.length > 0) {
                parts.push(`${stops.length} stop${stops.length > 1 ? 's' : ''} (${stopNames}${stops.length > 3 ? '...' : ''})`);
            }
            summary = `Visible on map: ${parts.join(' and ')}.`;
        }

        const response: MapEntitiesResponse = {
            success: true,
            buses,
            stops,
            summary,
            viewportInfo: viewportInfo ? {
                bounds: viewportInfo.bounds,
                queryTimestamp: viewportInfo.query_timestamp
            } : undefined
        };

        console.log(`[MapEntities] Returning ${buses.length} buses, ${stops.length} stops`);

        return new Response(JSON.stringify(response), {
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });

    } catch (error: unknown) {
        console.error('[MapEntities] Error:', error);
        const errorMessage = error instanceof Error ? error.message : String(error);

        return new Response(JSON.stringify({
            success: false,
            buses: [],
            stops: [],
            summary: 'Failed to query map entities',
            error: errorMessage
        }), {
            headers: { ...corsHeaders, 'Content-Type': 'application/json' },
            status: 500
        });
    }
});
