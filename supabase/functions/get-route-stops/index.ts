import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

/**
 * Get Route Stops Endpoint
 * 
 * Retrieves ordered route stops for one or more vehicles.
 * Critical for AI agent pathfinding in SaharaUser app.
 * 
 * Query params:
 * - vehicle_id: Single vehicle ID
 * - vehicle_ids: Comma-separated list of vehicle IDs
 * - include_vehicle_info: If "true", include vehicle details
 */
serve(async (req) => {
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders })
    }

    try {
        const supabaseClient = createClient(
            Deno.env.get('SUPABASE_URL') ?? '',
            Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
        )

        const url = new URL(req.url)
        const vehicleId = url.searchParams.get('vehicle_id')
        const vehicleIds = url.searchParams.get('vehicle_ids')
        const deviceId = url.searchParams.get('device_id')  // Optional: for bus_stops fallback
        const includeVehicleInfo = url.searchParams.get('include_vehicle_info') === 'true'

        // Determine which vehicles to query
        let vehicleList: string[] = []
        if (vehicleId) {
            vehicleList = [vehicleId]
        } else if (vehicleIds) {
            vehicleList = vehicleIds.split(',').map(id => id.trim())
        }

        // Helper function to get bus_stops fallback for a vehicle
        async function getBusStopsFallback(vehicleIdForFallback: string): Promise<any[]> {
            if (!deviceId) return []

            const { data: busStops, error } = await supabaseClient
                .from('bus_stops')
                .select('id, name, latitude, longitude, stop_order')
                .eq('device_id', deviceId)
                .eq('is_deleted', false)
                .order('stop_order', { ascending: true })

            if (error || !busStops || busStops.length === 0) return []

            return busStops.map((bs: any) => ({
                id: bs.id,
                vehicle_id: vehicleIdForFallback,
                name: bs.name,
                latitude: bs.latitude,
                longitude: bs.longitude,
                stop_order: bs.stop_order,
                created_at: null
            }))
        }

        if (vehicleList.length === 0) {
            // Return all route stops grouped by vehicle if no filter
            const { data: allStops, error } = await supabaseClient
                .from('route_stops')
                .select('*')
                .order('vehicle_id')
                .order('stop_order', { ascending: true })

            if (error) throw error

            // Group by vehicle_id
            const grouped: Record<string, any[]> = {}
            for (const stop of allStops || []) {
                if (!grouped[stop.vehicle_id]) {
                    grouped[stop.vehicle_id] = []
                }
                grouped[stop.vehicle_id].push(stop)
            }

            return new Response(
                JSON.stringify({
                    success: true,
                    routes: grouped,
                    total_vehicles: Object.keys(grouped).length,
                    server_timestamp: new Date().toISOString()
                }),
                {
                    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
                    status: 200
                }
            )
        }

        // Query route stops for specific vehicles
        const { data: stops, error: stopsError } = await supabaseClient
            .from('route_stops')
            .select('*')
            .in('vehicle_id', vehicleList)
            .order('vehicle_id')
            .order('stop_order', { ascending: true })

        if (stopsError) throw stopsError

        // Group stops by vehicle
        const routesByVehicle: Record<string, any[]> = {}
        for (const stop of stops || []) {
            if (!routesByVehicle[stop.vehicle_id]) {
                routesByVehicle[stop.vehicle_id] = []
            }
            routesByVehicle[stop.vehicle_id].push({
                id: stop.id,
                vehicle_id: stop.vehicle_id,
                name: stop.name,
                latitude: stop.latitude,
                longitude: stop.longitude,
                stop_order: stop.stop_order,
                created_at: stop.created_at
            })
        }

        // Apply bus_stops fallback for vehicles with no route_stops
        for (const vehId of vehicleList) {
            if (!routesByVehicle[vehId] || routesByVehicle[vehId].length === 0) {
                const fallbackStops = await getBusStopsFallback(vehId)
                if (fallbackStops.length > 0) {
                    routesByVehicle[vehId] = fallbackStops
                    console.log(`Using ${fallbackStops.length} fallback stops from bus_stops for vehicle ${vehId}`)
                }
            }
        }

        // Optionally include vehicle info
        let vehicleInfo: Record<string, any> = {}
        if (includeVehicleInfo) {
            const { data: vehicles, error: vehiclesError } = await supabaseClient
                .from('vehicles')
                .select('*')
                .in('id', vehicleList)

            if (!vehiclesError && vehicles) {
                for (const vehicle of vehicles) {
                    vehicleInfo[vehicle.id] = {
                        name: vehicle.name,
                        vehicle_number: vehicle.vehicle_number,
                        owner_id: vehicle.owner_id,
                        is_active: vehicle.is_active
                    }
                }
            }
        }

        return new Response(
            JSON.stringify({
                success: true,
                routes: routesByVehicle,
                vehicles: includeVehicleInfo ? vehicleInfo : undefined,
                total_stops: stops?.length || 0,
                server_timestamp: new Date().toISOString()
            }),
            {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
                status: 200
            }
        )

    } catch (error: unknown) {
        const errorMessage = error instanceof Error ? error.message : String(error)
        return new Response(
            JSON.stringify({
                success: false,
                message: errorMessage
            }),
            {
                headers: { ...corsHeaders, 'Content-Type': 'application/json' },
                status: 400
            }
        )
    }
})
