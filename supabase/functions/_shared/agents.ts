/**
 * Specialized Agents for Multi-Agent System
 * 
 * Each agent has a specific role:
 * - DataAgent: Fetch from Supabase
 * - PathAgent: Find routes
 * - TimeAgent: Calculate ETA
 * - TrackerAgent: Track bus positions
 * - TransferAgent: Find transfer routes
 */

import { createClient, SupabaseClient } from 'https://esm.sh/@supabase/supabase-js@2';
import type {
    AgentContext,
    AgentInput,
    AgentOutput,
    Vehicle,
    RouteStop,
    LiveLocation
} from './types.ts';
import {
    computeRoute,
    calculateETA,
    haversineDistance,
    findNearbyStops,
    findMatchingRoutes
} from './algorithms.ts';
import {
    Agent,
    trace,
    setData,
    getData,
    getAgentResult
} from './orchestrator.ts';

// ============================================================================
// SUPABASE CLIENT
// ============================================================================

let supabase: SupabaseClient | null = null;

function getSupabase(): SupabaseClient {
    if (!supabase) {
        const url = Deno.env.get('SUPABASE_URL');
        const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');

        if (!url || !key) {
            throw new Error('Missing Supabase environment variables');
        }

        supabase = createClient(url, key);
    }
    return supabase;
}

// ============================================================================
// DATA AGENT
// ============================================================================

export const DataAgent: Agent = {
    name: 'DataAgent',
    description: 'Fetches vehicles, stops, and live locations from Supabase',

    async execute(context: AgentContext, _input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Fetching data from Supabase');

        try {
            const client = getSupabase();

            // Fetch vehicles
            const { data: vehicles, error: vehiclesError } = await client
                .from('vehicles')
                .select('*');

            if (vehiclesError) throw vehiclesError;

            // Fetch route stops
            const { data: stops, error: stopsError } = await client
                .from('route_stops')
                .select('*')
                .order('vehicle_id')
                .order('stop_order');

            if (stopsError) throw stopsError;

            // Fetch live locations
            const { data: locations, error: locationsError } = await client
                .from('locations')
                .select('*')
                .order('timestamp', { ascending: false });

            // Locations are optional - don't fail if missing
            const liveLocations = locationsError ? [] : (locations || []);

            // Get latest location per bus
            const latestLocations = new Map<string, LiveLocation>();
            for (const loc of liveLocations as LiveLocation[]) {
                if (!latestLocations.has(loc.bus_id)) {
                    latestLocations.set(loc.bus_id, loc);
                }
            }

            // Store in context
            setData(context, 'vehicles', vehicles || []);
            setData(context, 'stops', stops || []);
            setData(context, 'live_locations', Array.from(latestLocations.values()));

            return {
                success: true,
                agentName: 'DataAgent',
                data: {
                    vehicles_count: (vehicles || []).length,
                    stops_count: (stops || []).length,
                    live_locations_count: latestLocations.size
                },
                message: `Loaded ${vehicles?.length} vehicles, ${stops?.length} stops, ${latestLocations.size} live`,
                confidence: 1.0,
                toolsUsed: ['supabase_query'],
                executionTimeMs: Date.now() - startTime
            };

        } catch (error) {
            return {
                success: false,
                agentName: 'DataAgent',
                data: {},
                error: error instanceof Error ? error.message : 'Data fetch failed',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }
    }
};

// ============================================================================
// PATH AGENT
// ============================================================================

export const PathAgent: Agent = {
    name: 'PathAgent',
    description: 'Finds optimal bus routes between origin and destination',

    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Finding routes');

        const origin = input.parameters.origin as string | undefined;
        const destination = input.parameters.destination as string | undefined;
        const userLat = input.parameters.user_lat as number | undefined;
        const userLng = input.parameters.user_lng as number | undefined;

        if (!origin || !destination) {
            return {
                success: false,
                agentName: 'PathAgent',
                data: {},
                error: 'Origin and destination are required',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Get data from context
        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];
        const locations = getData<LiveLocation[]>(context, 'live_locations') || [];

        if (vehicles.length === 0 || stops.length === 0) {
            return {
                success: false,
                agentName: 'PathAgent',
                data: { needs_data: true },
                error: 'No data available. DataAgent must run first.',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Compute route
        const result = computeRoute(origin, destination, vehicles, stops, locations, userLat, userLng);

        if (!result.success || !result.bestRoute) {
            return {
                success: false,
                agentName: 'PathAgent',
                data: {
                    route_found: false,
                    origin,
                    destination,
                    suggestions: ['Check for transfer options']
                },
                error: result.error || 'No direct route found',
                suggestedNextAgent: 'TransferAgent',
                confidence: 0,
                toolsUsed: ['compute_route'],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Store best route in context
        setData(context, 'best_route', result.bestRoute);
        setData(context, 'alternatives', result.alternatives);

        return {
            success: true,
            agentName: 'PathAgent',
            data: {
                best_route: result.bestRoute,
                alternatives: result.alternatives,
                route_found: true
            },
            message: `Found route via ${result.bestRoute.vehicleName}`,
            confidence: 0.9,
            toolsUsed: ['compute_route', 'route_matcher', 'route_ranker'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// TIME AGENT
// ============================================================================

export const TimeAgent: Agent = {
    name: 'TimeAgent',
    description: 'Calculates ETA with traffic adjustments',

    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Calculating ETA');

        // Get route data from context or input
        const bestRoute = getData<Record<string, unknown>>(context, 'best_route');

        let distanceKm = bestRoute?.distanceKm as number | undefined;
        let numStops = bestRoute?.numStops as number | undefined;
        let busSpeed = bestRoute?.speed as number | undefined;

        // Fallback to input parameters
        if (!distanceKm) {
            distanceKm = input.parameters.distance_km as number | undefined;
        }
        if (!numStops) {
            numStops = (input.parameters.num_stops as number | undefined) || 5;
        }
        if (!busSpeed) {
            busSpeed = input.parameters.bus_speed as number | undefined;
        }

        if (!distanceKm) {
            // Try to get from PathAgent result
            const pathResult = getAgentResult(context, 'PathAgent');
            const pathRoute = pathResult?.data.best_route as Record<string, unknown> | undefined;
            distanceKm = pathRoute?.distanceKm as number | undefined;
            numStops = pathRoute?.numStops as number | undefined;
        }

        if (!distanceKm) {
            return {
                success: false,
                agentName: 'TimeAgent',
                data: {},
                error: 'Cannot calculate ETA without distance',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        const eta = calculateETA(distanceKm, numStops || 5, busSpeed);

        setData(context, 'eta_minutes', eta.etaMinutes);
        setData(context, 'eta_range', { min: eta.etaRangeMin, max: eta.etaRangeMax });

        const hasLiveSpeed = busSpeed !== undefined && busSpeed > 0;

        return {
            success: true,
            agentName: 'TimeAgent',
            data: {
                eta_minutes: eta.etaMinutes,
                eta_range_min: eta.etaRangeMin,
                eta_range_max: eta.etaRangeMax,
                traffic_factor: eta.trafficFactor,
                has_live_data: hasLiveSpeed
            },
            message: `ETA: ${eta.etaRangeMin}-${eta.etaRangeMax} min${eta.trafficFactor > 1.2 ? ' (heavy traffic)' : ''}`,
            confidence: hasLiveSpeed ? 0.9 : 0.7,
            toolsUsed: ['calculate_eta'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// BUS TRACKER AGENT
// ============================================================================

export const TrackerAgent: Agent = {
    name: 'TrackerAgent',
    description: 'Tracks real-time bus positions',

    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Tracking bus position');

        // Get target bus from input or context
        let targetBusId = input.parameters.bus_id as string | undefined;
        let targetBusName = input.parameters.vehicle_name as string | undefined;

        if (!targetBusId && !targetBusName) {
            const bestRoute = getData<Record<string, unknown>>(context, 'best_route');
            targetBusId = bestRoute?.vehicleId as string | undefined;
            targetBusName = bestRoute?.vehicleName as string | undefined;
        }

        // Get data from context
        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];
        const locations = getData<LiveLocation[]>(context, 'live_locations') || [];

        if (locations.length === 0) {
            return {
                success: false,
                agentName: 'TrackerAgent',
                data: { target_bus: targetBusName || targetBusId || 'unknown' },
                error: 'No live bus data available',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Find target bus
        let liveLocation: LiveLocation | undefined;

        if (targetBusId) {
            liveLocation = locations.find(l => l.bus_id === targetBusId);
        }

        if (!liveLocation && targetBusName) {
            liveLocation = locations.find(loc => {
                const vehicle = vehicles.find(v => v.id === loc.bus_id);
                return vehicle?.name.toLowerCase().includes(targetBusName!.toLowerCase()) ||
                    vehicle?.vehicle_number.toLowerCase().includes(targetBusName!.toLowerCase());
            });
        }

        if (!liveLocation) {
            return {
                success: false,
                agentName: 'TrackerAgent',
                data: {
                    target_bus: targetBusName || targetBusId || 'unknown',
                    total_live_buses: locations.length
                },
                error: 'Bus not currently sharing location',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Find nearest stop
        const routeStops = stops.filter(s => s.vehicle_id === liveLocation!.bus_id)
            .sort((a, b) => a.stop_order - b.stop_order);

        let nearestStop: RouteStop | undefined;
        let nearestDistance = Infinity;

        for (const stop of routeStops) {
            const dist = haversineDistance(
                liveLocation.latitude, liveLocation.longitude,
                stop.latitude, stop.longitude
            );
            if (dist < nearestDistance) {
                nearestDistance = dist;
                nearestStop = stop;
            }
        }

        // Determine status
        const isMoving = (liveLocation.speed || 0) > 2;
        const timestamp = new Date(liveLocation.timestamp).getTime();
        const age = Date.now() - timestamp;
        const freshness = age < 2 * 60 * 1000 ? 'fresh' : age < 10 * 60 * 1000 ? 'recent' : 'stale';

        const vehicle = vehicles.find(v => v.id === liveLocation.bus_id);

        const busPosition = {
            bus_id: liveLocation.bus_id,
            bus_name: vehicle?.name || liveLocation.bus_id,
            vehicle_number: vehicle?.vehicle_number || '',
            latitude: liveLocation.latitude,
            longitude: liveLocation.longitude,
            speed: liveLocation.speed || 0,
            is_moving: isMoving,
            nearest_stop: nearestStop?.name || 'Unknown',
            nearest_stop_order: nearestStop?.stop_order || -1,
            nearest_stop_distance_m: Math.round(nearestDistance * 1000),
            freshness,
            timestamp: liveLocation.timestamp
        };

        setData(context, 'bus_position', busPosition);

        return {
            success: true,
            agentName: 'TrackerAgent',
            data: busPosition,
            message: `${vehicle?.name || 'Bus'} is ${isMoving ? 'moving' : 'stopped'} near ${nearestStop?.name || 'Unknown'}`,
            confidence: freshness === 'fresh' ? 0.95 : freshness === 'recent' ? 0.7 : 0.4,
            toolsUsed: ['haversine'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// TRANSFER AGENT
// ============================================================================

export const TransferAgent: Agent = {
    name: 'TransferAgent',
    description: 'Finds multi-bus routes with transfer points',

    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Searching for transfer routes');

        const origin = input.parameters.origin as string | undefined;
        const destination = input.parameters.destination as string | undefined;
        const MAX_WALKING_KM = 0.5;

        if (!origin || !destination) {
            return {
                success: false,
                agentName: 'TransferAgent',
                data: {},
                error: 'Origin and destination are required',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];

        if (vehicles.length === 0 || stops.length === 0) {
            return {
                success: false,
                agentName: 'TransferAgent',
                data: {},
                error: 'No data available',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Find routes containing origin
        const originRoutes = findMatchingRoutes(origin, origin, vehicles, stops);

        // Find routes containing destination
        const destRoutes = findMatchingRoutes(destination, destination, vehicles, stops);

        if (originRoutes.length === 0 || destRoutes.length === 0) {
            return {
                success: false,
                agentName: 'TransferAgent',
                data: {
                    origin_routes_count: originRoutes.length,
                    dest_routes_count: destRoutes.length
                },
                error: `No routes serving ${originRoutes.length === 0 ? origin : destination}`,
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Group stops by vehicle
        const vehicleStops = new Map<string, RouteStop[]>();
        for (const stop of stops) {
            const existing = vehicleStops.get(stop.vehicle_id) || [];
            existing.push(stop);
            vehicleStops.set(stop.vehicle_id, existing);
        }

        // Find transfer points
        interface TransferOption {
            firstBus: string;
            firstBusId: string;
            transferStop: string;
            walkingKm: number;
            secondBus: string;
            secondBusId: string;
            totalTime: number;
        }

        const transferOptions: TransferOption[] = [];

        for (const originRoute of originRoutes) {
            const originVehicleStops = vehicleStops.get(originRoute.vehicleId) || [];
            originVehicleStops.sort((a, b) => a.stop_order - b.stop_order);

            // Find the origin stop on this route
            const originStopOnRoute = originVehicleStops.find(s =>
                s.name.toLowerCase().includes(origin.toLowerCase())
            );
            if (!originStopOnRoute) continue;

            // Get stops AFTER origin
            const stopsAfterOrigin = originVehicleStops.filter(
                s => s.stop_order > originStopOnRoute.stop_order
            );

            for (const destRoute of destRoutes) {
                if (destRoute.vehicleId === originRoute.vehicleId) continue; // Skip same route

                const destVehicleStops = vehicleStops.get(destRoute.vehicleId) || [];
                destVehicleStops.sort((a, b) => a.stop_order - b.stop_order);

                // Find the destination stop on this route
                const destStopOnRoute = destVehicleStops.find(s =>
                    s.name.toLowerCase().includes(destination.toLowerCase())
                );
                if (!destStopOnRoute) continue;

                // Get stops BEFORE destination
                const stopsBeforeDest = destVehicleStops.filter(
                    s => s.stop_order < destStopOnRoute.stop_order
                );

                // Find transfer points
                for (const fromStop of stopsAfterOrigin) {
                    for (const toStop of stopsBeforeDest) {
                        const walkingDist = haversineDistance(
                            fromStop.latitude, fromStop.longitude,
                            toStop.latitude, toStop.longitude
                        );

                        if (walkingDist <= MAX_WALKING_KM) {
                            // Calculate rough total time
                            const firstLegStops = stopsAfterOrigin.filter(
                                s => s.stop_order <= fromStop.stop_order
                            ).length;
                            const secondLegStops = stopsBeforeDest.filter(
                                s => s.stop_order >= toStop.stop_order
                            ).length;

                            const firstEta = calculateETA(firstLegStops * 0.5, firstLegStops);
                            const secondEta = calculateETA(secondLegStops * 0.5, secondLegStops);
                            const walkTime = (walkingDist / 5) * 60; // 5 km/h
                            const waitTime = 5; // Assume 5 min wait

                            const totalTime = firstEta.etaMinutes + walkTime + waitTime + secondEta.etaMinutes;

                            transferOptions.push({
                                firstBus: originRoute.vehicleName,
                                firstBusId: originRoute.vehicleId,
                                transferStop: fromStop.name,
                                walkingKm: Math.round(walkingDist * 1000) / 1000,
                                secondBus: destRoute.vehicleName,
                                secondBusId: destRoute.vehicleId,
                                totalTime: Math.round(totalTime)
                            });
                        }
                    }
                }
            }
        }

        if (transferOptions.length === 0) {
            return {
                success: false,
                agentName: 'TransferAgent',
                data: {
                    origin_routes_count: originRoutes.length,
                    dest_routes_count: destRoutes.length
                },
                error: 'No transfer route found within walking distance',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Sort by total time
        transferOptions.sort((a, b) => a.totalTime - b.totalTime);
        const best = transferOptions[0];

        return {
            success: true,
            agentName: 'TransferAgent',
            data: {
                transfer_route: best,
                alternatives: transferOptions.slice(1, 4),
                first_bus: best.firstBus,
                transfer_point: best.transferStop,
                second_bus: best.secondBus,
                walking_distance_m: Math.round(best.walkingKm * 1000),
                total_time_min: best.totalTime
            },
            message: `🔄 Transfer: ${best.firstBus} → walk ${Math.round(best.walkingKm * 1000)}m → ${best.secondBus} (~${best.totalTime} min)`,
            confidence: 0.8,
            toolsUsed: ['haversine', 'calculate_eta'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// SQL AGENT - Direct SQL Query Agent
// ============================================================================

export const SQLAgent: Agent = {
    name: 'SQLAgent',
    description: 'Executes direct SQL-style queries against Supabase',

    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Executing SQL query');

        try {
            const client = getSupabase();
            const queryType = input.parameters.query_type as string || 'all_data';

            switch (queryType) {
                case 'route_match': {
                    const origin = input.parameters.origin as string;
                    const destination = input.parameters.destination as string;

                    if (!origin || !destination) {
                        return {
                            success: false, agentName: 'SQLAgent', data: {},
                            error: 'origin and destination required',
                            confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime
                        };
                    }

                    const { data: originStops } = await client
                        .from('route_stops').select('vehicle_id, name, stop_order')
                        .ilike('name', `%${origin}%`);

                    const { data: destStops } = await client
                        .from('route_stops').select('vehicle_id, name, stop_order')
                        .ilike('name', `%${destination}%`);

                    const originVehicles = new Set(originStops?.map(s => s.vehicle_id));
                    const validRoutes = (destStops || []).filter(d => {
                        if (!originVehicles.has(d.vehicle_id)) return false;
                        const o = originStops?.find(x => x.vehicle_id === d.vehicle_id);
                        return o && o.stop_order < d.stop_order;
                    });

                    return {
                        success: true, agentName: 'SQLAgent',
                        data: { valid_routes: validRoutes.map(d => d.vehicle_id) },
                        message: `Found ${validRoutes.length} matching routes`,
                        confidence: 1.0, toolsUsed: ['supabase_sql'],
                        executionTimeMs: Date.now() - startTime
                    };
                }

                case 'bus_location': {
                    const busId = input.parameters.bus_id as string;
                    const { data } = await client
                        .from('locations').select('*')
                        .eq('bus_id', busId)
                        .order('timestamp', { ascending: false })
                        .limit(1).single();

                    return {
                        success: !!data, agentName: 'SQLAgent',
                        data: { location: data, found: !!data },
                        confidence: 1.0, toolsUsed: ['supabase_sql'],
                        executionTimeMs: Date.now() - startTime
                    };
                }

                default:
                    // Fetch all data (same as DataAgent)
                    return DataAgent.execute(context, input);
            }

        } catch (error) {
            return {
                success: false, agentName: 'SQLAgent', data: {},
                error: error instanceof Error ? error.message : 'SQL query failed',
                confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime
            };
        }
    }
};

// ============================================================================
// USER CONTEXT AGENT
// ============================================================================

export const UserContextAgent: Agent = {
    name: 'UserContextAgent',
    description: 'Provides user preferences, location, and personalization context',

    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Getting user context');

        try {
            const client = getSupabase();
            const userId = context.userId;

            const resultData: Record<string, unknown> = {
                has_location: !!context.userLocation,
                user_lat: context.userLocation?.latitude,
                user_lng: context.userLocation?.longitude,
                time_of_day: new Date().getHours() < 12 ? 'morning' :
                    new Date().getHours() < 17 ? 'afternoon' : 'evening',
                is_rush_hour: [8, 9, 10, 17, 18, 19, 20].includes(new Date().getHours())
            };

            if (userId) {
                const { data: history } = await client
                    .from('user_search_history')
                    .select('origin_name, destination_name')
                    .eq('user_id', userId)
                    .order('created_at', { ascending: false })
                    .limit(10);

                const origins = new Map<string, number>();
                for (const h of history || []) {
                    if (h.origin_name) origins.set(h.origin_name, (origins.get(h.origin_name) || 0) + 1);
                }

                resultData.search_count = history?.length || 0;
                resultData.frequent_origin = Array.from(origins.entries()).sort((a, b) => b[1] - a[1])[0]?.[0];
            }

            return {
                success: true, agentName: 'UserContextAgent', data: resultData,
                message: `Context: ${resultData.has_location ? 'GPS ✓' : 'No GPS'}`,
                confidence: resultData.has_location ? 0.9 : 0.7,
                toolsUsed: userId ? ['supabase_query'] : [],
                executionTimeMs: Date.now() - startTime
            };

        } catch (error) {
            return {
                success: false, agentName: 'UserContextAgent', data: {},
                error: error instanceof Error ? error.message : 'Context fetch failed',
                confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime
            };
        }
    }
};

// ============================================================================
// ANALYTICS AGENT
// ============================================================================

export const AnalyticsAgent: Agent = {
    name: 'AnalyticsAgent',
    description: 'Analyzes travel patterns and provides traffic insights',

    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'Running analytics');

        const analysisType = input.parameters.analysis_type as string || 'time_analysis';

        if (analysisType === 'fleet_status') {
            const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
            const locations = getData<LiveLocation[]>(context, 'live_locations') || [];
            const liveIds = new Set(locations.map(l => l.bus_id));
            const now = Date.now();
            const fresh = locations.filter(l => (now - new Date(l.timestamp).getTime()) < 120000);

            return {
                success: true, agentName: 'AnalyticsAgent',
                data: { total: vehicles.length, live: locations.length, fresh: fresh.length },
                message: `Fleet: ${fresh.length} live, ${vehicles.length - locations.length} offline`,
                confidence: 0.95, toolsUsed: [], executionTimeMs: Date.now() - startTime
            };
        }

        // Time analysis (default)
        const hour = new Date().getHours();
        const isWeekend = [0, 6].includes(new Date().getDay());
        const isRush = (hour >= 8 && hour <= 10) || (hour >= 17 && hour <= 20);
        const trafficFactor = isWeekend ? 1.0 : isRush ? 1.4 : 1.0;

        return {
            success: true, agentName: 'AnalyticsAgent',
            data: { hour, is_weekend: isWeekend, is_rush: isRush, traffic_factor: trafficFactor },
            message: isRush && !isWeekend ? 'Rush hour - expect delays' : 'Normal traffic',
            confidence: 0.9, toolsUsed: [], executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// COMMUNICATION AGENT
// ============================================================================

export const CommunicationAgent: Agent = {
    name: 'CommunicationAgent',
    description: 'Formats agent results into user-friendly responses',

    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        const action = input.parameters.action as string || 'format';

        if (action === 'clarify') {
            const missing = input.parameters.missing as string || 'details';
            const questions: Record<string, string> = {
                origin: '📍 Where would you like to start?',
                destination: '📍 Where would you like to go?',
                both: 'Tell me your journey, e.g. "From MG Road to Electronic City"',
                bus_id: '🚌 Which bus are you asking about?'
            };
            return {
                success: true, agentName: 'CommunicationAgent',
                data: { question: questions[missing] || 'Please provide more details' },
                message: questions[missing] || 'Please provide more details',
                confidence: 1.0, toolsUsed: [], executionTimeMs: Date.now() - startTime
            };
        }

        if (action === 'welcome') {
            return {
                success: true, agentName: 'CommunicationAgent', data: {},
                message: '👋 Hello! I can help you find bus routes, track buses, or get ETAs. Where would you like to go?',
                confidence: 1.0, toolsUsed: [], executionTimeMs: Date.now() - startTime
            };
        }

        if (action === 'error') {
            const errorType = input.parameters.error_type as string;
            const msgs: Record<string, string> = {
                no_route: "😔 Couldn't find a direct route. Try checking stop names.",
                no_bus: '🚌 No buses active on that route right now.',
                network: '📶 Connection issue. Please try again.'
            };
            return {
                success: true, agentName: 'CommunicationAgent', data: {},
                message: msgs[errorType] || '😅 Something went wrong. Please try again.',
                confidence: 1.0, toolsUsed: [], executionTimeMs: Date.now() - startTime
            };
        }

        return {
            success: true, agentName: 'CommunicationAgent', data: {},
            message: 'I can help with routes, tracking, or ETAs.',
            confidence: 0.5, toolsUsed: [], executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// EXPORT ALL AGENTS
// ============================================================================

export const agents = {
    DataAgent,
    SQLAgent,
    PathAgent,
    TimeAgent,
    TrackerAgent,
    TransferAgent,
    UserContextAgent,
    AnalyticsAgent,
    CommunicationAgent
};
