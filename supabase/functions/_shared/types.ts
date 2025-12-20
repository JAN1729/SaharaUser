/**
 * Shared Types for Multi-Agent System
 * 
 * Core type definitions used across all Edge Functions
 */

// ============================================================================
// DATABASE TYPES (from Supabase tables)
// ============================================================================

export interface Vehicle {
  id: string;
  name: string;
  vehicle_number: string;
  owner_id: string;
  is_active: boolean;
  created_at: string;
}

export interface RouteStop {
  id: string;
  vehicle_id: string;
  name: string;
  latitude: number;
  longitude: number;
  stop_order: number;
  created_at: string;
}

export interface LiveLocation {
  id: string;
  bus_id: string;
  operator_id: string;
  latitude: number;
  longitude: number;
  speed: number | null;
  heading: number | null;
  timestamp: number;
  location_ts?: number;
}

// ============================================================================
// AGENT SYSTEM TYPES
// ============================================================================

// Import hub interface (avoid circular dependency by using import type)
import type { IAgentCommunicationHub } from './agent-communication-hub.ts';

export interface AgentContext {
  taskId: string;
  userId: string;
  originalQuery: string;
  userLocation?: { latitude: number; longitude: number };
  sharedData: Map<string, unknown>;
  agentResults: Map<string, AgentOutput>;
  executionTrace: TraceEvent[];
  startTime: number;
  /** Agent Communication Hub for inter-agent messaging */
  hub?: IAgentCommunicationHub;
}

export interface AgentInput {
  intent: string;
  parameters: Record<string, unknown>;
  previousResults?: Record<string, AgentOutput>;
}

export interface AgentOutput {
  success: boolean;
  agentName: string;
  data: Record<string, unknown>;
  message?: string;
  error?: string;
  confidence: number;
  suggestedNextAgent?: string;
  toolsUsed: string[];
  executionTimeMs: number;
}

export interface TraceEvent {
  type: TraceEventType;
  message: string;
  data?: Record<string, unknown>;
  timestamp: number;
}

export type TraceEventType =
  | 'AGENT_STARTED'
  | 'AGENT_COMPLETED'
  | 'TOOL_STARTED'
  | 'TOOL_COMPLETED'
  | 'DATA_STORED'
  | 'ERROR'
  | 'STATUS_UPDATE'
  | 'LLM_CALL'
  | 'PARALLEL_START'
  | 'PARALLEL_END';

// ============================================================================
// TOOL TYPES
// ============================================================================

export interface Tool {
  name: string;
  description: string;
  execute: (params: Record<string, unknown>, context: AgentContext) => Promise<ToolResult>;
}

export interface ToolResult {
  success: boolean;
  toolName: string;
  data: Record<string, unknown>;
  error?: string;
  executionTimeMs: number;
}

// ============================================================================
// QUERY PARSING TYPES
// ============================================================================

export type QueryIntent =
  | 'FIND_ROUTE'
  | 'FIND_BEST_OPTION'
  | 'TRACK_BUS'
  | 'CALCULATE_ETA'
  | 'FIND_NEARBY'
  | 'GET_SCHEDULE'
  | 'GENERAL'
  | 'UNKNOWN';

export interface ParsedQuery {
  intent: QueryIntent;
  confidence: number;
  parameters: {
    origin?: string;
    destination?: string;
    busId?: string;
    stopName?: string;
  };
  requiredAgents: string[];
}

// ============================================================================
// API REQUEST/RESPONSE TYPES
// ============================================================================

export interface ProcessQueryRequest {
  userId: string;
  query: string;
  userLocation?: {
    latitude: number;
    longitude: number;
  };
}

export interface ProcessQueryResponse {
  success: boolean;
  response: string;
  intent: string;
  routeData?: RouteData;
  agentTrace: TraceEvent[];
  executionTimeMs: number;
  error?: string;
}

export interface RouteData {
  vehicleId: string;
  vehicleName: string;
  vehicleNumber?: string;
  originStop: string;
  destinationStop: string;
  distanceKm: number;
  etaMinutes: number;
  stopsCount: number;
  isLive: boolean;
  busStopsAway?: number;
  alternatives?: RouteData[];
}

export interface TrackBusRequest {
  busId?: string;
  busName?: string;
}

export interface TrackBusResponse {
  success: boolean;
  busId: string;
  busName: string;
  position: {
    latitude: number;
    longitude: number;
  };
  speed: number;
  isMoving: boolean;
  nearestStop: string;
  nearestStopDistance: number;
  freshness: 'fresh' | 'recent' | 'stale';
  timestamp: number;
  message?: string;  // AI-generated human-readable status
  error?: string;
}

export interface NearbyStopsRequest {
  latitude: number;
  longitude: number;
  radiusKm?: number;
}

export interface NearbyStopsResponse {
  success: boolean;
  stops: Array<{
    name: string;
    latitude: number;
    longitude: number;
    distanceKm: number;
    vehicleId: string;
    vehicleName: string;
  }>;
  count: number;
  message?: string;  // AI-generated human-readable summary
}

// ============================================================================
// ALGORITHM TYPES
// ============================================================================

export interface ETAResult {
  etaMinutes: number;
  etaRangeMin: number;
  etaRangeMax: number;
  trafficFactor: number;
}

export interface RouteMatchResult {
  vehicleId: string;
  vehicleName: string;
  originStop: string;
  originOrder: number;
  destinationStop: string;
  destinationOrder: number;
  stopsInBetween: string[];
}

export interface RankedRoute {
  vehicleId: string;
  vehicleName: string;
  vehicleNumber: string;
  distanceKm: number;
  etaMinutes: number;
  score: number;
  isLive: boolean;
  busStopsAway?: number;
  stopsInPath: string[];
}
