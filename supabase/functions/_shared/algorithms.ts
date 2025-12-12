/**
 * Algorithm Implementations for Multi-Agent System
 * 
 * Pure functions for route calculations - no external dependencies
 */

import type { ETAResult, RouteMatchResult, RouteStop, LiveLocation, Vehicle } from './types.ts';

// ============================================================================
// HAVERSINE DISTANCE
// ============================================================================

/**
 * Calculate distance between two coordinates using Haversine formula.
 * @returns Distance in kilometers
 */
export function haversineDistance(
    lat1: number,
    lng1: number,
    lat2: number,
    lng2: number
): number {
    const R = 6371; // Earth's radius in kilometers
    const dLat = toRadians(lat2 - lat1);
    const dLng = toRadians(lng2 - lng1);

    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2);

    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return R * c;
}

function toRadians(degrees: number): number {
    return degrees * (Math.PI / 180);
}

// ============================================================================
// ETA CALCULATOR
// ============================================================================

/**
 * Calculate estimated time of arrival considering distance, stops, and traffic.
 */
export function calculateETA(
    distanceKm: number,
    numStops: number,
    currentSpeed?: number
): ETAResult {
    // Base speed (km/h) - adjust based on traffic conditions
    const hour = new Date().getHours();
    let trafficFactor = 1.0;

    // Peak hours: more traffic
    if ((hour >= 8 && hour <= 10) || (hour >= 17 && hour <= 20)) {
        trafficFactor = 1.4; // 40% slower during peak
    } else if (hour >= 22 || hour <= 6) {
        trafficFactor = 0.85; // 15% faster at night
    }

    // Average bus speed
    const baseSpeed = currentSpeed && currentSpeed > 0 ? currentSpeed : 18; // km/h
    const effectiveSpeed = baseSpeed / trafficFactor;

    // Travel time calculation
    const travelTimeMin = (distanceKm / effectiveSpeed) * 60;

    // Stop time: 30 seconds per stop
    const stopTimeMin = numStops * 0.5;

    // Total ETA
    const etaMinutes = travelTimeMin + stopTimeMin;

    // Range for uncertainty
    const etaRangeMin = etaMinutes * 0.85;
    const etaRangeMax = etaMinutes * 1.25;

    return {
        etaMinutes: Math.round(etaMinutes),
        etaRangeMin: Math.round(etaRangeMin),
        etaRangeMax: Math.round(etaRangeMax),
        trafficFactor
    };
}

// ============================================================================
// ROUTE MATCHER
// ============================================================================

/**
 * Find routes that connect origin and destination stops.
 */
export function findMatchingRoutes(
    origin: string,
    destination: string,
    vehicles: Vehicle[],
    stops: RouteStop[]
): RouteMatchResult[] {
    const results: RouteMatchResult[] = [];
    const originLower = origin.toLowerCase();
    const destLower = destination.toLowerCase();

    // Group stops by vehicle
    const vehicleStops = new Map<string, RouteStop[]>();
    for (const stop of stops) {
        const existing = vehicleStops.get(stop.vehicle_id) || [];
        existing.push(stop);
        vehicleStops.set(stop.vehicle_id, existing);
    }

    // Sort stops by order for each vehicle
    for (const [vehicleId, vehicleStopList] of vehicleStops) {
        vehicleStopList.sort((a, b) => a.stop_order - b.stop_order);

        // Find origin stop
        const originStop = vehicleStopList.find(s =>
            s.name.toLowerCase().includes(originLower)
        );

        // Find destination stop
        const destStop = vehicleStopList.find(s =>
            s.name.toLowerCase().includes(destLower)
        );

        // Only valid if origin comes before destination
        if (originStop && destStop && originStop.stop_order < destStop.stop_order) {
            const vehicle = vehicles.find(v => v.id === vehicleId);

            // Get stops in between
            const stopsInBetween = vehicleStopList
                .filter(s => s.stop_order > originStop.stop_order && s.stop_order < destStop.stop_order)
                .map(s => s.name);

            results.push({
                vehicleId,
                vehicleName: vehicle?.name || vehicleId,
                originStop: originStop.name,
                originOrder: originStop.stop_order,
                destinationStop: destStop.name,
                destinationOrder: destStop.stop_order,
                stopsInBetween
            });
        }
    }

    return results;
}

// ============================================================================
// ROUTE RANKER
// ============================================================================

interface RouteCandidate {
    vehicleId: string;
    vehicleName: string;
    vehicleNumber: string;
    distanceKm: number;
    etaMinutes: number;
    numStops: number;
    isLive: boolean;
    busStopsAway?: number;
    stopsInPath: string[];
}

interface RankedRoute extends RouteCandidate {
    score: number;
}

/**
 * Rank route candidates by multiple factors.
 */
export function rankRoutes(candidates: RouteCandidate[]): RankedRoute[] {
    if (candidates.length === 0) return [];

    // Normalize values for scoring
    const maxDistance = Math.max(...candidates.map(c => c.distanceKm));
    const maxETA = Math.max(...candidates.map(c => c.etaMinutes));
    const maxStops = Math.max(...candidates.map(c => c.numStops));

    const ranked: RankedRoute[] = candidates.map(candidate => {
        let score = 100;

        // Distance factor (lower is better) - 30% weight
        score -= (candidate.distanceKm / maxDistance) * 30;

        // ETA factor (lower is better) - 35% weight
        score -= (candidate.etaMinutes / maxETA) * 35;

        // Number of stops (fewer is better) - 15% weight
        score -= (candidate.numStops / maxStops) * 15;

        // Live tracking bonus - 10% weight
        if (candidate.isLive) {
            score += 10;
        }

        // Bus proximity bonus - 10% weight
        if (candidate.busStopsAway !== undefined && candidate.busStopsAway >= 0) {
            // Closer bus = higher score (max 10 points if 0 stops away)
            const proximityBonus = Math.max(0, 10 - candidate.busStopsAway);
            score += proximityBonus;
        }

        return {
            ...candidate,
            score: Math.round(score * 10) / 10
        };
    });

    // Sort by score (highest first)
    return ranked.sort((a, b) => b.score - a.score);
}

// ============================================================================
// COMPUTE FULL ROUTE
// ============================================================================

export interface ComputeRouteResult {
    success: boolean;
    bestRoute?: RankedRoute;
    alternatives: RankedRoute[];
    error?: string;
}

/**
 * Compute the best route from origin to destination.
 */
export function computeRoute(
    origin: string,
    destination: string,
    vehicles: Vehicle[],
    stops: RouteStop[],
    liveLocations: LiveLocation[],
    userLat?: number,
    userLng?: number
): ComputeRouteResult {
    // Step 1: Find matching routes
    const matches = findMatchingRoutes(origin, destination, vehicles, stops);

    if (matches.length === 0) {
        return {
            success: false,
            alternatives: [],
            error: 'No direct route found between these stops'
        };
    }

    // Step 2: Build route candidates with full data
    const vehicleStopsMap = new Map<string, RouteStop[]>();
    for (const stop of stops) {
        const existing = vehicleStopsMap.get(stop.vehicle_id) || [];
        existing.push(stop);
        vehicleStopsMap.set(stop.vehicle_id, existing);
    }

    const candidates: RouteCandidate[] = matches.map(match => {
        const vehicleStops = vehicleStopsMap.get(match.vehicleId) || [];
        vehicleStops.sort((a, b) => a.stop_order - b.stop_order);

        // Get segment stops
        const segmentStops = vehicleStops.filter(
            s => s.stop_order >= match.originOrder && s.stop_order <= match.destinationOrder
        );

        // Calculate distance along route
        let distance = 0;
        for (let i = 0; i < segmentStops.length - 1; i++) {
            distance += haversineDistance(
                segmentStops[i].latitude,
                segmentStops[i].longitude,
                segmentStops[i + 1].latitude,
                segmentStops[i + 1].longitude
            );
        }

        // Get live location
        const liveLocation = liveLocations.find(l => l.bus_id === match.vehicleId);
        const isLive = !!liveLocation;

        // Calculate bus stops away
        let busStopsAway: number | undefined;
        if (liveLocation) {
            // Find nearest stop to bus
            let minDist = Infinity;
            let busStopOrder = -1;

            for (const stop of vehicleStops) {
                const dist = haversineDistance(
                    liveLocation.latitude,
                    liveLocation.longitude,
                    stop.latitude,
                    stop.longitude
                );
                if (dist < minDist) {
                    minDist = dist;
                    busStopOrder = stop.stop_order;
                }
            }

            if (busStopOrder >= 0 && busStopOrder < match.originOrder) {
                busStopsAway = match.originOrder - busStopOrder;
            }
        }

        // Calculate ETA
        const eta = calculateETA(distance, segmentStops.length - 1, liveLocation?.speed ?? undefined);

        // Get vehicle details
        const vehicle = vehicles.find(v => v.id === match.vehicleId);

        return {
            vehicleId: match.vehicleId,
            vehicleName: match.vehicleName,
            vehicleNumber: vehicle?.vehicle_number || '',
            distanceKm: Math.round(distance * 10) / 10,
            etaMinutes: eta.etaMinutes,
            numStops: segmentStops.length - 1,
            isLive,
            busStopsAway,
            stopsInPath: segmentStops.map(s => s.name)
        };
    });

    // Step 3: Rank routes
    const ranked = rankRoutes(candidates);

    return {
        success: true,
        bestRoute: ranked[0],
        alternatives: ranked.slice(1)
    };
}

// ============================================================================
// NEARBY STOPS FINDER
// ============================================================================

export interface NearbyStop {
    name: string;
    latitude: number;
    longitude: number;
    distanceKm: number;
    vehicleId: string;
    vehicleName: string;
}

/**
 * Find stops near a given location.
 */
export function findNearbyStops(
    lat: number,
    lng: number,
    stops: RouteStop[],
    vehicles: Vehicle[],
    radiusKm: number = 1.0
): NearbyStop[] {
    const vehicleMap = new Map(vehicles.map(v => [v.id, v]));

    const nearbyStops: NearbyStop[] = [];

    for (const stop of stops) {
        const distance = haversineDistance(lat, lng, stop.latitude, stop.longitude);

        if (distance <= radiusKm) {
            const vehicle = vehicleMap.get(stop.vehicle_id);
            nearbyStops.push({
                name: stop.name,
                latitude: stop.latitude,
                longitude: stop.longitude,
                distanceKm: Math.round(distance * 1000) / 1000,
                vehicleId: stop.vehicle_id,
                vehicleName: vehicle?.name || stop.vehicle_id
            });
        }
    }

    // Sort by distance
    return nearbyStops.sort((a, b) => a.distanceKm - b.distanceKm);
}
