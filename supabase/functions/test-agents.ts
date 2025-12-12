/**
 * Multi-Agent System Test Script
 * 
 * This script simulates the agent orchestration flow with mock data.
 * Run with: deno run --allow-all test-agents.ts
 * 
 * Or use curl after deploying to Supabase.
 */

// ============================================================================
// MOCK DATA (simulates what would come from Supabase)
// ============================================================================

const mockVehicles = [
    { id: 'bus-001', name: 'Bus 335E', vehicle_number: 'KA01AB1234', is_active: true },
    { id: 'bus-002', name: 'Bus 500C', vehicle_number: 'KA01CD5678', is_active: true },
    { id: 'bus-003', name: 'Bus 201D', vehicle_number: 'KA01EF9012', is_active: true }
];

const mockStops = [
    // Bus 335E Route: MG Road → Silk Board → Electronic City
    { id: 's1', vehicle_id: 'bus-001', name: 'MG Road', latitude: 12.9758, longitude: 77.6066, stop_order: 1 },
    { id: 's2', vehicle_id: 'bus-001', name: 'Koramangala', latitude: 12.9352, longitude: 77.6245, stop_order: 2 },
    { id: 's3', vehicle_id: 'bus-001', name: 'Silk Board', latitude: 12.9172, longitude: 77.6227, stop_order: 3 },
    { id: 's4', vehicle_id: 'bus-001', name: 'HSR Layout', latitude: 12.9116, longitude: 77.6389, stop_order: 4 },
    { id: 's5', vehicle_id: 'bus-001', name: 'Electronic City', latitude: 12.8456, longitude: 77.6603, stop_order: 5 },

    // Bus 500C Route: Majestic → Indiranagar → Whitefield
    { id: 's6', vehicle_id: 'bus-002', name: 'Majestic', latitude: 12.9767, longitude: 77.5713, stop_order: 1 },
    { id: 's7', vehicle_id: 'bus-002', name: 'MG Road', latitude: 12.9758, longitude: 77.6066, stop_order: 2 },
    { id: 's8', vehicle_id: 'bus-002', name: 'Indiranagar', latitude: 12.9784, longitude: 77.6408, stop_order: 3 },
    { id: 's9', vehicle_id: 'bus-002', name: 'Whitefield', latitude: 12.9698, longitude: 77.7500, stop_order: 4 },
];

const mockLiveLocations = [
    { bus_id: 'bus-001', latitude: 12.9352, longitude: 77.6245, speed: 25, timestamp: new Date().toISOString() },
    { bus_id: 'bus-002', latitude: 12.9784, longitude: 77.6408, speed: 0, timestamp: new Date().toISOString() }
];

// ============================================================================
// ALGORITHM FUNCTIONS
// ============================================================================

function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const R = 6371;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function calculateETA(distanceKm: number, numStops: number): { eta: number, range: string } {
    const travelTime = (distanceKm / 18) * 60; // 18 km/h average
    const stopTime = numStops * 0.5;
    const eta = Math.round(travelTime + stopTime);
    return { eta, range: `${Math.round(eta * 0.85)}-${Math.round(eta * 1.25)}` };
}

// ============================================================================
// AGENT SIMULATION
// ============================================================================

interface AgentResult {
    agent: string;
    success: boolean;
    data: Record<string, unknown>;
    message: string;
    timeMs: number;
}

async function simulateDataAgent(): Promise<AgentResult> {
    const start = Date.now();
    console.log('\n[DataAgent] Fetching data from Supabase...');

    // Simulate network delay
    await new Promise(r => setTimeout(r, 100));

    return {
        agent: 'DataAgent',
        success: true,
        data: {
            vehicles: mockVehicles,
            stops: mockStops,
            live_locations: mockLiveLocations,
            vehicles_count: mockVehicles.length,
            stops_count: mockStops.length,
            live_count: mockLiveLocations.length
        },
        message: `Loaded ${mockVehicles.length} vehicles, ${mockStops.length} stops, ${mockLiveLocations.length} live`,
        timeMs: Date.now() - start
    };
}

async function simulatePathAgent(origin: string, destination: string, dataResult: AgentResult): Promise<AgentResult> {
    const start = Date.now();
    console.log(`\n[PathAgent] Finding route: ${origin} → ${destination}`);

    const stops = dataResult.data.stops as typeof mockStops;
    const vehicles = dataResult.data.vehicles as typeof mockVehicles;

    // Find matching routes
    const originStops = stops.filter(s => s.name.toLowerCase().includes(origin.toLowerCase()));
    const destStops = stops.filter(s => s.name.toLowerCase().includes(destination.toLowerCase()));

    console.log(`  Found ${originStops.length} origin matches, ${destStops.length} destination matches`);

    // Find vehicles serving both
    const routes: any[] = [];
    for (const originStop of originStops) {
        const destStop = destStops.find(d =>
            d.vehicle_id === originStop.vehicle_id && d.stop_order > originStop.stop_order
        );

        if (destStop) {
            const vehicle = vehicles.find(v => v.id === originStop.vehicle_id);
            const routeStops = stops.filter(s =>
                s.vehicle_id === originStop.vehicle_id &&
                s.stop_order >= originStop.stop_order &&
                s.stop_order <= destStop.stop_order
            ).sort((a, b) => a.stop_order - b.stop_order);

            // Calculate distance
            let distance = 0;
            for (let i = 0; i < routeStops.length - 1; i++) {
                distance += haversineDistance(
                    routeStops[i].latitude, routeStops[i].longitude,
                    routeStops[i + 1].latitude, routeStops[i + 1].longitude
                );
            }

            routes.push({
                vehicle_id: originStop.vehicle_id,
                vehicle_name: vehicle?.name,
                vehicle_number: vehicle?.vehicle_number,
                origin_stop: originStop.name,
                destination_stop: destStop.name,
                distance_km: Math.round(distance * 10) / 10,
                stops_count: routeStops.length - 1,
                stops_in_path: routeStops.map(s => s.name)
            });
        }
    }

    if (routes.length === 0) {
        return {
            agent: 'PathAgent',
            success: false,
            data: { route_found: false },
            message: 'No direct route found',
            timeMs: Date.now() - start
        };
    }

    // Sort by distance
    routes.sort((a, b) => a.distance_km - b.distance_km);
    const best = routes[0];

    console.log(`  ✅ Found route via ${best.vehicle_name}`);

    return {
        agent: 'PathAgent',
        success: true,
        data: {
            best_route: best,
            alternatives: routes.slice(1),
            route_found: true
        },
        message: `Found route via ${best.vehicle_name}`,
        timeMs: Date.now() - start
    };
}

async function simulateTimeAgent(pathResult: AgentResult): Promise<AgentResult> {
    const start = Date.now();
    console.log('\n[TimeAgent] Calculating ETA...');

    const route = pathResult.data.best_route as any;
    if (!route) {
        return {
            agent: 'TimeAgent',
            success: false,
            data: {},
            message: 'No route data',
            timeMs: Date.now() - start
        };
    }

    const { eta, range } = calculateETA(route.distance_km, route.stops_count);

    console.log(`  ⏱️ ETA: ${range} min`);

    return {
        agent: 'TimeAgent',
        success: true,
        data: {
            eta_minutes: eta,
            eta_range: range,
            traffic_factor: 1.0
        },
        message: `ETA: ${range} min`,
        timeMs: Date.now() - start
    };
}

async function simulateTrackerAgent(pathResult: AgentResult, dataResult: AgentResult): Promise<AgentResult> {
    const start = Date.now();
    console.log('\n[TrackerAgent] Tracking bus position...');

    const route = pathResult.data.best_route as any;
    const locations = dataResult.data.live_locations as typeof mockLiveLocations;
    const stops = dataResult.data.stops as typeof mockStops;

    if (!route) {
        return {
            agent: 'TrackerAgent',
            success: false,
            data: {},
            message: 'No route to track',
            timeMs: Date.now() - start
        };
    }

    const liveLocation = locations.find(l => l.bus_id === route.vehicle_id);

    if (!liveLocation) {
        return {
            agent: 'TrackerAgent',
            success: false,
            data: { is_live: false },
            message: 'Bus not broadcasting',
            timeMs: Date.now() - start
        };
    }

    // Find nearest stop
    const routeStops = stops.filter(s => s.vehicle_id === route.vehicle_id);
    let nearestStop = routeStops[0];
    let minDist = Infinity;

    for (const stop of routeStops) {
        const dist = haversineDistance(
            liveLocation.latitude, liveLocation.longitude,
            stop.latitude, stop.longitude
        );
        if (dist < minDist) {
            minDist = dist;
            nearestStop = stop;
        }
    }

    // Calculate stops away from origin
    const originStop = routeStops.find(s => s.name === route.origin_stop);
    const stopsAway = originStop ? originStop.stop_order - nearestStop.stop_order : null;

    const isMoving = liveLocation.speed > 2;

    console.log(`  📍 Bus is ${isMoving ? 'moving' : 'stopped'} near ${nearestStop.name}`);
    if (stopsAway !== null && stopsAway > 0) {
        console.log(`  ${stopsAway} stops away from boarding point`);
    }

    return {
        agent: 'TrackerAgent',
        success: true,
        data: {
            is_live: true,
            is_moving: isMoving,
            nearest_stop: nearestStop.name,
            stops_away: stopsAway,
            speed: liveLocation.speed
        },
        message: `Bus ${isMoving ? 'moving' : 'stopped'} near ${nearestStop.name}`,
        timeMs: Date.now() - start
    };
}

function formatResponse(pathResult: AgentResult, timeResult: AgentResult, trackerResult: AgentResult): string {
    const route = pathResult.data.best_route as any;
    const eta = timeResult.data.eta_range;
    const tracker = trackerResult.data;

    let response = `🚌 **${route.vehicle_name}** (${route.vehicle_number})\n`;
    response += `📍 ${route.origin_stop} → ${route.destination_stop}\n`;
    response += `📏 ${route.distance_km} km • ${route.stops_count} stops\n`;
    response += `⏱️ ETA: **${eta} min**\n`;

    if (tracker.is_live) {
        response += `\n✅ Live tracking: Bus ${tracker.is_moving ? 'moving' : 'stopped'} near ${tracker.nearest_stop}`;
        if (tracker.stops_away && tracker.stops_away > 0) {
            response += ` (${tracker.stops_away} stops to your boarding point)`;
        }
    }

    return response;
}

// ============================================================================
// MAIN TEST
// ============================================================================

async function runTest() {
    console.log('═══════════════════════════════════════════════════════════════');
    console.log('        MULTI-AGENT SYSTEM TEST');
    console.log('═══════════════════════════════════════════════════════════════');

    const query = 'How do I get from MG Road to Electronic City?';
    console.log(`\n📝 User Query: "${query}"`);
    console.log('\n--- Query Parsing ---');
    console.log('Intent: FIND_ROUTE');
    console.log('Origin: MG Road');
    console.log('Destination: Electronic City');

    console.log('\n--- Agent Execution Pipeline ---');

    const totalStart = Date.now();

    // Step 1: Data Agent
    const dataResult = await simulateDataAgent();

    // Step 2: Path Agent
    const pathResult = await simulatePathAgent('MG Road', 'Electronic City', dataResult);

    if (!pathResult.success) {
        console.log('\n❌ No route found');
        return;
    }

    // Step 3 & 4: Time + Tracker (parallel)
    console.log('\n[PARALLEL] Running TimeAgent and TrackerAgent...');
    const [timeResult, trackerResult] = await Promise.all([
        simulateTimeAgent(pathResult),
        simulateTrackerAgent(pathResult, dataResult)
    ]);

    // Final Response
    console.log('\n═══════════════════════════════════════════════════════════════');
    console.log('        FINAL RESPONSE');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(formatResponse(pathResult, timeResult, trackerResult));

    // Metrics
    console.log('\n--- Execution Metrics ---');
    console.log(`DataAgent: ${dataResult.timeMs}ms`);
    console.log(`PathAgent: ${pathResult.timeMs}ms`);
    console.log(`TimeAgent: ${timeResult.timeMs}ms`);
    console.log(`TrackerAgent: ${trackerResult.timeMs}ms`);
    console.log(`Total: ${Date.now() - totalStart}ms`);
    console.log('═══════════════════════════════════════════════════════════════');
}

// Run the test
runTest();
