import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

// ============================================================================
// AGENTIC MAP DATA ENDPOINT (MCP-Style Tool Orchestrator)
// ============================================================================
// This Edge Function is the single source of truth for all map visualizations.
// It orchestrates calls to get-route-stops and applies A* pathfinding.
// ============================================================================

interface MapRequest {
    vehicleId?: string;
    vehicleIds?: string[];
    userLocation?: { lat: number; lng: number };
    busLocation?: { lat: number; lng: number; heading?: number };
    intent?: 'FIND_ROUTE' | 'TRACK_BUS' | 'FIND_NEARBY';
    origin?: string;
    destination?: string;
}

interface GeoJSONPoint {
    type: 'Point';
    coordinates: [number, number]; // [lng, lat]
}

interface GeoJSONLineString {
    type: 'LineString';
    coordinates: [number, number][]; // [[lng, lat], ...]
}

interface GeoJSONFeature {
    type: 'Feature';
    geometry: GeoJSONPoint | GeoJSONLineString;
    properties: Record<string, unknown>;
}

interface GeoJSONFeatureCollection {
    type: 'FeatureCollection';
    features: GeoJSONFeature[];
}

interface MapStop {
    id: string;
    name: string;
    lat: number;
    lng: number;
    order: number;
    isOrigin: boolean;
    isDestination: boolean;
}

interface MapPayload {
    success: boolean;
    mapType: 'route' | 'tracking' | 'nearby' | 'user_only';
    stops: MapStop[];
    polyline: { lat: number; lng: number }[];
    geoJSON: GeoJSONFeatureCollection;
    bounds: { north: number; south: number; east: number; west: number };
    userLocation?: { lat: number; lng: number };
    busLocation?: { lat: number; lng: number; heading?: number };
    vehicleInfo?: { name: string; number: string };
    message?: string;
}

const EARTH_RADIUS_KM = 6371;

// Haversine distance for A* heuristic
function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const φ1 = lat1 * Math.PI / 180;
    const φ2 = lat2 * Math.PI / 180;
    const Δφ = (lat2 - lat1) * Math.PI / 180;
    const Δλ = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(Δφ / 2) ** 2 + Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2) ** 2;
    return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ============================================================================
// A* PATHFINDING FOR POLYLINE GENERATION
// ============================================================================
// f(n) = g(n) + h(n)
// g(n) = actual distance from start to n
// h(n) = heuristic (Haversine) from n to goal
// ============================================================================

interface AStarNode {
    stop: MapStop;
    g: number; // actual distance from start
    h: number; // heuristic to goal
    f: number; // g + h
    parent: AStarNode | null;
}

function aStarPathfind(stops: MapStop[], origin: MapStop, destination: MapStop): MapStop[] {
    if (stops.length === 0) return [];
    if (origin.id === destination.id) return [origin];

    // For ordered route stops, we follow the sequence, but optimize the visual path
    // Create adjacency based on stop_order (each stop connects to the next)
    const orderedStops = [...stops].sort((a, b) => a.order - b.order);

    const openSet: AStarNode[] = [];
    const closedSet = new Set<string>();

    const startNode: AStarNode = {
        stop: origin,
        g: 0,
        h: haversineDistance(origin.lat, origin.lng, destination.lat, destination.lng),
        f: 0,
        parent: null
    };
    startNode.f = startNode.g + startNode.h;
    openSet.push(startNode);

    while (openSet.length > 0) {
        // Get node with lowest f
        openSet.sort((a, b) => a.f - b.f);
        const current = openSet.shift()!;

        if (current.stop.id === destination.id) {
            // Reconstruct path
            const path: MapStop[] = [];
            let node: AStarNode | null = current;
            while (node) {
                path.unshift(node.stop);
                node = node.parent;
            }
            return path;
        }

        closedSet.add(current.stop.id);

        // Get neighbors (next stop in order)
        const currentIndex = orderedStops.findIndex(s => s.id === current.stop.id);
        const neighbors: MapStop[] = [];

        if (currentIndex < orderedStops.length - 1) {
            neighbors.push(orderedStops[currentIndex + 1]);
        }

        for (const neighbor of neighbors) {
            if (closedSet.has(neighbor.id)) continue;

            const tentativeG = current.g + haversineDistance(
                current.stop.lat, current.stop.lng,
                neighbor.lat, neighbor.lng
            );

            const existingNode = openSet.find(n => n.stop.id === neighbor.id);

            if (!existingNode) {
                const newNode: AStarNode = {
                    stop: neighbor,
                    g: tentativeG,
                    h: haversineDistance(neighbor.lat, neighbor.lng, destination.lat, destination.lng),
                    f: 0,
                    parent: current
                };
                newNode.f = newNode.g + newNode.h;
                openSet.push(newNode);
            } else if (tentativeG < existingNode.g) {
                existingNode.g = tentativeG;
                existingNode.f = existingNode.g + existingNode.h;
                existingNode.parent = current;
            }
        }
    }

    // Fallback: return all stops in order if A* doesn't find a path
    return orderedStops;
}

// ============================================================================
// GEOJSON BUILDER
// ============================================================================

function buildGeoJSON(
    stops: MapStop[],
    polyline: { lat: number; lng: number }[],
    userLocation?: { lat: number; lng: number },
    busLocation?: { lat: number; lng: number; heading?: number }
): GeoJSONFeatureCollection {
    const features: GeoJSONFeature[] = [];

    // Route polyline as LineString
    if (polyline.length > 0) {
        features.push({
            type: 'Feature',
            geometry: {
                type: 'LineString',
                coordinates: polyline.map(p => [p.lng, p.lat])
            },
            properties: {
                type: 'route',
                stroke: '#4285F4',
                'stroke-width': 4
            }
        });
    }

    // Stops as Points
    for (const stop of stops) {
        features.push({
            type: 'Feature',
            geometry: {
                type: 'Point',
                coordinates: [stop.lng, stop.lat]
            },
            properties: {
                type: 'stop',
                name: stop.name,
                order: stop.order,
                isOrigin: stop.isOrigin,
                isDestination: stop.isDestination,
                'marker-color': stop.isOrigin ? '#00C853' : stop.isDestination ? '#D50000' : '#FFC107'
            }
        });
    }

    // User location
    if (userLocation) {
        features.push({
            type: 'Feature',
            geometry: {
                type: 'Point',
                coordinates: [userLocation.lng, userLocation.lat]
            },
            properties: {
                type: 'user',
                'marker-color': '#2196F3',
                'marker-symbol': 'circle'
            }
        });
    }

    // Bus location
    if (busLocation) {
        features.push({
            type: 'Feature',
            geometry: {
                type: 'Point',
                coordinates: [busLocation.lng, busLocation.lat]
            },
            properties: {
                type: 'bus',
                heading: busLocation.heading,
                'marker-color': '#FF5722',
                'marker-symbol': 'bus'
            }
        });
    }

    return {
        type: 'FeatureCollection',
        features
    };
}

// ============================================================================
// TOOL: CALL GET-ROUTE-STOPS (Internal MCP-style invocation)
// ============================================================================

async function callGetRouteStops(vehicleIds: string[]): Promise<{
    success: boolean;
    routes: Record<string, Array<{
        id: string;
        name: string;
        latitude: number;
        longitude: number;
        stop_order: number;
    }>>;
    vehicles?: Record<string, { name: string; vehicle_number: string }>;
}> {
    const supabaseUrl = Deno.env.get('SUPABASE_URL');
    const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

    if (!supabaseUrl || !serviceKey) {
        return { success: false, routes: {} };
    }

    const url = new URL(`${supabaseUrl}/functions/v1/get-route-stops`);
    url.searchParams.set('vehicle_ids', vehicleIds.join(','));
    url.searchParams.set('include_vehicle_info', 'true');

    try {
        console.log('[MapAgent] Calling get-route-stops tool...');
        const res = await fetch(url.toString(), {
            headers: {
                'apikey': serviceKey,
                'Authorization': `Bearer ${serviceKey}`
            }
        });

        if (res.ok) {
            const data = await res.json();
            console.log('[MapAgent] get-route-stops returned', Object.keys(data.routes || {}).length, 'routes');
            return data;
        }
    } catch (e) {
        console.error('[MapAgent] get-route-stops call failed:', e);
    }

    return { success: false, routes: {} };
}

// ============================================================================
// MAIN HANDLER
// ============================================================================

serve(async (req) => {
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    try {
        const request: MapRequest = await req.json();
        console.log('[MapAgent] Request:', JSON.stringify(request));

        const {
            vehicleId,
            vehicleIds,
            userLocation,
            busLocation,
            intent,
            origin,
            destination
        } = request;

        // Determine which vehicles to query
        const targetVehicles: string[] = [];
        if (vehicleId) targetVehicles.push(vehicleId);
        if (vehicleIds) targetVehicles.push(...vehicleIds);

        // ========================================
        // CASE 1: No vehicles - User location only
        // ========================================
        if (targetVehicles.length === 0 && userLocation) {
            console.log('[MapAgent] No vehicles specified, returning user location only');

            const payload: MapPayload = {
                success: true,
                mapType: 'user_only',
                stops: [],
                polyline: [],
                geoJSON: buildGeoJSON([], [], userLocation),
                bounds: {
                    north: userLocation.lat + 0.01,
                    south: userLocation.lat - 0.01,
                    east: userLocation.lng + 0.01,
                    west: userLocation.lng - 0.01
                },
                userLocation,
                message: 'No route available. Showing your current location.'
            };

            return new Response(JSON.stringify(payload), {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' }
            });
        }

        // ========================================
        // CASE 2: Fetch route stops for vehicles
        // ========================================
        const routeData = await callGetRouteStops(targetVehicles);

        if (!routeData.success || Object.keys(routeData.routes).length === 0) {
            console.log('[MapAgent] No routes found for vehicles');

            const payload: MapPayload = {
                success: true,
                mapType: 'user_only',
                stops: [],
                polyline: [],
                geoJSON: buildGeoJSON([], [], userLocation),
                bounds: userLocation ? {
                    north: userLocation.lat + 0.01,
                    south: userLocation.lat - 0.01,
                    east: userLocation.lng + 0.01,
                    west: userLocation.lng - 0.01
                } : { north: 0, south: 0, east: 0, west: 0 },
                userLocation,
                message: 'No route data found for the specified vehicle(s).'
            };

            return new Response(JSON.stringify(payload), {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' }
            });
        }

        // Process the first vehicle's route (or merge for multi-vehicle)
        const primaryVehicleId = targetVehicles[0];
        const stopsRaw = routeData.routes[primaryVehicleId] || [];
        const vehicleInfo = routeData.vehicles?.[primaryVehicleId];

        // Convert to MapStop format
        const stops: MapStop[] = stopsRaw
            .sort((a, b) => a.stop_order - b.stop_order)
            .map((s, index, arr) => ({
                id: s.id,
                name: s.name,
                lat: s.latitude,
                lng: s.longitude,
                order: s.stop_order,
                isOrigin: index === 0 || (origin ? s.name.toLowerCase().includes(origin.toLowerCase()) : false),
                isDestination: index === arr.length - 1 || (destination ? s.name.toLowerCase().includes(destination.toLowerCase()) : false)
            }));

        if (stops.length === 0) {
            const payload: MapPayload = {
                success: true,
                mapType: 'user_only',
                stops: [],
                polyline: [],
                geoJSON: buildGeoJSON([], [], userLocation),
                bounds: userLocation ? {
                    north: userLocation.lat + 0.01,
                    south: userLocation.lat - 0.01,
                    east: userLocation.lng + 0.01,
                    west: userLocation.lng - 0.01
                } : { north: 0, south: 0, east: 0, west: 0 },
                userLocation,
                message: 'No stops found for this route.'
            };

            return new Response(JSON.stringify(payload), {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' }
            });
        }

        // ========================================
        // A* PATHFINDING: Generate optimized polyline
        // ========================================
        const originStop = stops.find(s => s.isOrigin) || stops[0];
        const destStop = stops.find(s => s.isDestination) || stops[stops.length - 1];

        console.log(`[MapAgent] Running A* from "${originStop.name}" to "${destStop.name}"`);
        const pathStops = aStarPathfind(stops, originStop, destStop);

        // Generate polyline from A* path
        const polyline = pathStops.map(s => ({ lat: s.lat, lng: s.lng }));
        console.log(`[MapAgent] A* generated polyline with ${polyline.length} points`);

        // ========================================
        // Calculate bounds
        // ========================================
        const allLats = stops.map(s => s.lat);
        const allLngs = stops.map(s => s.lng);

        if (userLocation) {
            allLats.push(userLocation.lat);
            allLngs.push(userLocation.lng);
        }
        if (busLocation) {
            allLats.push(busLocation.lat);
            allLngs.push(busLocation.lng);
        }

        const bounds = {
            north: Math.max(...allLats) + 0.005,
            south: Math.min(...allLats) - 0.005,
            east: Math.max(...allLngs) + 0.005,
            west: Math.min(...allLngs) - 0.005
        };

        // ========================================
        // Build GeoJSON
        // ========================================
        const geoJSON = buildGeoJSON(stops, polyline, userLocation, busLocation);

        // Determine map type
        let mapType: 'route' | 'tracking' | 'nearby' | 'user_only' = 'route';
        if (intent === 'TRACK_BUS' && busLocation) {
            mapType = 'tracking';
        } else if (intent === 'FIND_NEARBY') {
            mapType = 'nearby';
        }

        const payload: MapPayload = {
            success: true,
            mapType,
            stops,
            polyline,
            geoJSON,
            bounds,
            userLocation,
            busLocation,
            vehicleInfo: vehicleInfo ? {
                name: vehicleInfo.name,
                number: vehicleInfo.vehicle_number
            } : undefined,
            message: `Route with ${stops.length} stops ready for visualization.`
        };

        console.log(`[MapAgent] Returning ${mapType} map with ${stops.length} stops`);

        return new Response(JSON.stringify(payload), {
            headers: { ...corsHeaders, 'Content-Type': 'application/json' }
        });

    } catch (error: unknown) {
        console.error('[MapAgent] Error:', error);
        const errorMessage = error instanceof Error ? error.message : String(error);

        return new Response(
            JSON.stringify({
                success: false,
                mapType: 'user_only',
                stops: [],
                polyline: [],
                geoJSON: { type: 'FeatureCollection', features: [] },
                bounds: { north: 0, south: 0, east: 0, west: 0 },
                message: `Map generation failed: ${errorMessage}`
            }),
            {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
                status: 400
            }
        );
    }
});
