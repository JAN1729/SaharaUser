/**
 * Process Query Edge Function - ENHANCED with LLM Multi-Agent Framework
 * 
 * This is the main entry point for the Sahara Bus AI agent system.
 * Features:
 * - LLM-powered intent analysis (Groq API)
 * - Parallel agent execution
 * - Result validation and synthesis
 * - Deterministic fallback when LLM fails
 * 
 * Paste this ENTIRE file into the Supabase Dashboard.
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import { createClient, SupabaseClient } from 'https://esm.sh/@supabase/supabase-js@2';

// ============================================================================
// TYPES
// ============================================================================

interface Vehicle {
    id: string;
    name: string;
    vehicle_number: string;
    owner_id: string;
    is_active: boolean;
    created_at: string;
}

interface RouteStop {
    id: string;
    vehicle_id: string;
    name: string;
    latitude: number;
    longitude: number;
    stop_order: number;
    created_at: string;
}

interface LiveLocation {
    id: string;
    bus_id: string;
    operator_id: string;
    latitude: number;
    longitude: number;
    speed: number | null;
    heading: number | null;
    timestamp: string;
}

interface AgentContext {
    taskId: string;
    userId: string;
    originalQuery: string;
    userLocation?: { latitude: number; longitude: number };
    sharedData: Map<string, unknown>;
    agentResults: Map<string, AgentOutput>;
    executionTrace: TraceEvent[];
    startTime: number;
}

interface AgentInput {
    intent: string;
    parameters: Record<string, unknown>;
    previousResults?: Record<string, AgentOutput>;
}

interface AgentOutput {
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

interface TraceEvent {
    type: string;
    message: string;
    data?: Record<string, unknown>;
    timestamp: number;
}

interface Agent {
    name: string;
    description: string;
    execute: (context: AgentContext, input: AgentInput) => Promise<AgentOutput>;
}

interface ProcessQueryRequest {
    userId: string;
    query: string;
    userLocation?: { latitude: number; longitude: number };
    useLLM?: boolean;  // Optional: disable LLM for testing
}

interface ProcessQueryResponse {
    success: boolean;
    response: string;
    intent: string;
    confidence: number;
    routeData?: unknown;
    agentTrace: TraceEvent[];
    executionTimeMs: number;
    llmUsage?: {
        intentAnalysis: boolean;
        resultSynthesis: boolean;
    };
    error?: string;
}

interface AnalyzedIntent {
    intent: string;
    confidence: number;
    entities: {
        origin?: string;
        destination?: string;
        busId?: string;
        busName?: string;
        stopName?: string;
    };
    requiredAgents: string[];
    executionMode: 'sequential' | 'parallel';
    reasoning: string;
    clarificationNeeded?: string;
}

// Fleet status for rich AI context
interface ActiveBusInfo {
    bus_id: string;
    bus_name: string;
    vehicle_number: string | undefined;
    current_lat: number;
    current_lng: number;
    speed_kmh: number;
    is_moving: boolean;
    heading: number | null;
    nearest_stop: string | null;
    stops_on_route: number;
    last_update: string;
    age_seconds: number;
}

interface FleetStatus {
    total_vehicles: number;
    active_buses: number;
    offline_buses: number;
    buses_moving: number;
    buses_stopped: number;
    active_bus_list: ActiveBusInfo[];
}

// ============================================================================
// SUPABASE CLIENT
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

// ============================================================================
// CONTEXT HELPERS
// ============================================================================

function createContext(
    userId: string,
    query: string,
    userLocation?: { latitude: number; longitude: number }
): AgentContext {
    return {
        taskId: crypto.randomUUID(),
        userId,
        originalQuery: query,
        userLocation,
        sharedData: new Map(),
        agentResults: new Map(),
        executionTrace: [],
        startTime: Date.now()
    };
}

function trace(context: AgentContext, type: string, message: string): void {
    context.executionTrace.push({ type, message, timestamp: Date.now() });
    console.log(`[${type}] ${message}`);
}

function setData(context: AgentContext, key: string, value: unknown): void {
    context.sharedData.set(key, value);
}

function getData<T>(context: AgentContext, key: string): T | undefined {
    return context.sharedData.get(key) as T | undefined;
}

// ============================================================================
// LLM CLIENTS - Multi-Provider AI Pipeline
// ============================================================================

// Groq API (for fast operations)
const GROQ_API_URL = 'https://api.groq.com/openai/v1/chat/completions';
const GROQ_MODEL_FAST = 'llama-3.1-8b-instant';           // ~150 tokens/sec, quick routing
const GROQ_MODEL_QUALITY = 'llama-3.3-70b-versatile';     // For natural responses

// Gemini API (for main reasoning - MOST POWERFUL MODELS)
const GEMINI_API_URL = 'https://generativelanguage.googleapis.com/v1beta/models';
const GEMINI_MODEL_THINKING = 'gemini-2.5-pro';  // Most powerful thinking model
const GEMINI_MODEL_FAST = 'gemini-2.5-flash';   // Fast with thinking
const GEMINI_MODEL_PRO = 'gemini-2.5-flash';              // Production stable

// Model Selection Strategy:
// - GEMINI THINKING: Main orchestrator reasoning, complex analysis, decision making
// - GEMINI PRO: Agent analysis, synthesis
// - GROQ FAST: Quick intent classification (lowest latency)
// - GROQ QUALITY: Final user-facing response generation

// ============================================================================
// GEMINI API CLIENT - Main Reasoning Engine
// ============================================================================

interface GeminiContent {
    parts: Array<{ text: string }>;
    role?: string;
}

interface GeminiRequest {
    contents: GeminiContent[];
    generationConfig?: {
        temperature?: number;
        maxOutputTokens?: number;
        responseMimeType?: string;
    };
}

// Call Gemini for complex reasoning (MAIN AI - thinking model)
async function callGeminiThinking(systemPrompt: string, userMessage: string): Promise<string> {
    const startTime = Date.now();
    const apiKey = Deno.env.get('GEMINI_API_KEY');

    if (!apiKey) {
        console.error('[GEMINI ERROR] GEMINI_API_KEY is not set!');
        throw new Error('GEMINI_API_KEY not set');
    }

    console.log(`[GEMINI] Starting callGeminiThinking with ${GEMINI_MODEL_THINKING}...`);

    const url = `${GEMINI_API_URL}/${GEMINI_MODEL_THINKING}:generateContent`;

    const payload: GeminiRequest = {
        contents: [
            {
                parts: [{ text: `${systemPrompt}\n\n${userMessage}` }],
                role: 'user'
            }
        ],
        generationConfig: {
            temperature: 0.2,      // Low for logical consistency
            maxOutputTokens: 2048  // More tokens for detailed reasoning
        }
    };

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'x-goog-api-key': apiKey
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Gemini API error: ${response.status} - ${errorText}`);
        }

        const data = await response.json();
        const content = data.candidates?.[0]?.content?.parts?.[0]?.text || '';
        const elapsed = Date.now() - startTime;
        console.log(`[GEMINI SUCCESS] callGeminiThinking completed in ${elapsed}ms (${content.length} chars)`);
        return content;
    } catch (error) {
        const elapsed = Date.now() - startTime;
        console.error(`[GEMINI FAILED] callGeminiThinking failed after ${elapsed}ms:`, error);
        throw error;
    }
}

// Call Gemini for JSON responses
async function callGeminiJSON(systemPrompt: string, userMessage: string): Promise<string> {
    const startTime = Date.now();
    const apiKey = Deno.env.get('GEMINI_API_KEY');

    if (!apiKey) {
        console.error('[GEMINI ERROR] GEMINI_API_KEY is not set!');
        throw new Error('GEMINI_API_KEY not set');
    }

    console.log(`[GEMINI] Starting callGeminiJSON with ${GEMINI_MODEL_PRO}...`);

    const url = `${GEMINI_API_URL}/${GEMINI_MODEL_PRO}:generateContent`;

    const payload: GeminiRequest = {
        contents: [
            {
                parts: [{ text: `${systemPrompt}\n\nRespond ONLY with valid JSON.\n\n${userMessage}` }],
                role: 'user'
            }
        ],
        generationConfig: {
            temperature: 0.1,
            maxOutputTokens: 1024,
            responseMimeType: 'application/json'
        }
    };

    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'x-goog-api-key': apiKey
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Gemini API error: ${response.status} - ${errorText}`);
        }

        const data = await response.json();
        const content = data.candidates?.[0]?.content?.parts?.[0]?.text || '';
        const elapsed = Date.now() - startTime;
        console.log(`[GEMINI SUCCESS] callGeminiJSON completed in ${elapsed}ms`);
        return content;
    } catch (error) {
        const elapsed = Date.now() - startTime;
        console.error(`[GEMINI FAILED] callGeminiJSON failed after ${elapsed}ms:`, error);
        throw error;
    }
}

// ============================================================================
// RESILIENT AI CALLS - With Automatic Fallback
// ============================================================================

/**
 * Call AI for reasoning with fallback: Gemini Thinking → Groq 70B
 */
async function callAIReasoning(systemPrompt: string, userMessage: string): Promise<string> {
    // Try Gemini first (main AI)
    try {
        const geminiKey = Deno.env.get('GEMINI_API_KEY');
        if (geminiKey) {
            console.log('[AI] Using Gemini Thinking for reasoning...');
            return await callGeminiThinking(systemPrompt, userMessage);
        }
    } catch (error) {
        console.warn('[AI FALLBACK] Gemini failed, falling back to Groq:', error);
    }

    // Fallback to Groq
    console.log('[AI] Using Groq 70B for reasoning (fallback)...');
    return await callLLMReasoning(systemPrompt, userMessage);
}

/**
 * Call AI for JSON responses with fallback: Gemini JSON → Groq
 */
async function callAIJSON(systemPrompt: string, userMessage: string): Promise<string> {
    // Try Gemini first
    try {
        const geminiKey = Deno.env.get('GEMINI_API_KEY');
        if (geminiKey) {
            console.log('[AI] Using Gemini for JSON response...');
            return await callGeminiJSON(systemPrompt, userMessage);
        }
    } catch (error) {
        console.warn('[AI FALLBACK] Gemini failed, falling back to Groq:', error);
    }

    // Fallback to Groq
    console.log('[AI] Using Groq for JSON response (fallback)...');
    return await callLLM(systemPrompt, userMessage);
}

/**
 * Call AI for natural language response with fallback: Gemini → Groq
 */
async function callAIText(systemPrompt: string, userMessage: string): Promise<string> {
    // Try Gemini first
    try {
        const geminiKey = Deno.env.get('GEMINI_API_KEY');
        if (geminiKey) {
            console.log('[AI] Using Gemini for text response...');
            return await callGeminiThinking(systemPrompt, userMessage);
        }
    } catch (error) {
        console.warn('[AI FALLBACK] Gemini failed, falling back to Groq:', error);
    }

    // Fallback to Groq
    console.log('[AI] Using Groq for text response (fallback)...');
    return await callLLMText(systemPrompt, userMessage);
}

// ============================================================================
// GROQ API CLIENT - Fast Operations
// ============================================================================

// Call LLM expecting JSON response
async function callLLM(systemPrompt: string, userMessage: string): Promise<string> {
    const startTime = Date.now();
    const apiKey = Deno.env.get('GROQ_API_KEY');
    if (!apiKey) {
        console.error('[LLM ERROR] GROQ_API_KEY is not set!');
        throw new Error('GROQ_API_KEY not set');
    }

    console.log(`[LLM] Starting callLLM (JSON) with ${GROQ_MODEL_FAST}...`);

    try {
        const response = await fetch(GROQ_API_URL, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${apiKey}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                model: GROQ_MODEL_FAST,
                messages: [
                    { role: 'system', content: systemPrompt },
                    { role: 'user', content: userMessage }
                ],
                temperature: 0.1,
                max_tokens: 400,
                response_format: { type: 'json_object' }
            })
        });

        if (!response.ok) {
            throw new Error(`Groq API error: ${response.status}`);
        }

        const data = await response.json();
        const content = data.choices[0]?.message?.content || '';
        const elapsed = Date.now() - startTime;
        console.log(`[LLM SUCCESS] callLLM completed in ${elapsed}ms (${content.length} chars)`);
        return content;
    } catch (error) {
        const elapsed = Date.now() - startTime;
        console.error(`[LLM FAILED] callLLM failed after ${elapsed}ms:`, error);
        throw error;
    }
}

// Call LLM expecting natural language response (for final output)
async function callLLMText(systemPrompt: string, userMessage: string): Promise<string> {
    const startTime = Date.now();
    const apiKey = Deno.env.get('GROQ_API_KEY');
    if (!apiKey) {
        console.error('[LLM ERROR] GROQ_API_KEY is not set!');
        throw new Error('GROQ_API_KEY not set');
    }

    console.log(`[LLM] Starting callLLMText (Natural Language) with ${GROQ_MODEL_QUALITY}...`);

    try {
        const response = await fetch(GROQ_API_URL, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${apiKey}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                model: GROQ_MODEL_QUALITY,  // Use quality model for final response
                messages: [
                    { role: 'system', content: systemPrompt },
                    { role: 'user', content: userMessage }
                ],
                temperature: 0.3,  // Slightly more creative for natural responses
                max_tokens: 500
            })
        });

        if (!response.ok) {
            throw new Error(`Groq API error: ${response.status}`);
        }

        const data = await response.json();
        const content = data.choices[0]?.message?.content || '';
        const elapsed = Date.now() - startTime;
        console.log(`[LLM SUCCESS] callLLMText completed in ${elapsed}ms (${content.length} chars)`);
        return content;
    } catch (error) {
        const elapsed = Date.now() - startTime;
        console.error(`[LLM FAILED] callLLMText failed after ${elapsed}ms:`, error);
        throw error;
    }
}

// Call LLM for complex reasoning (uses most powerful model)
async function callLLMReasoning(systemPrompt: string, userMessage: string): Promise<string> {
    const startTime = Date.now();
    const apiKey = Deno.env.get('GROQ_API_KEY');
    if (!apiKey) {
        console.error('[LLM ERROR] GROQ_API_KEY is not set!');
        throw new Error('GROQ_API_KEY not set');
    }

    console.log(`[LLM] Starting callLLMReasoning (Deep Analysis) with ${GROQ_MODEL_QUALITY}...`);

    try {
        const response = await fetch(GROQ_API_URL, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${apiKey}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                model: GROQ_MODEL_QUALITY,  // Use 70B model for complex reasoning
                messages: [
                    { role: 'system', content: systemPrompt },
                    { role: 'user', content: userMessage }
                ],
                temperature: 0.2,  // Low temp for logical consistency
                max_tokens: 800,   // More tokens for detailed reasoning
                response_format: { type: 'json_object' }
            })
        });

        if (!response.ok) {
            throw new Error(`Groq API error: ${response.status}`);
        }

        const data = await response.json();
        const content = data.choices[0]?.message?.content || '';
        const elapsed = Date.now() - startTime;
        console.log(`[LLM SUCCESS] callLLMReasoning completed in ${elapsed}ms (${content.length} chars)`);
        return content;
    } catch (error) {
        const elapsed = Date.now() - startTime;
        console.error(`[LLM FAILED] callLLMReasoning failed after ${elapsed}ms:`, error);
        throw error;
    }
}

function parseJSON<T>(content: string): T | null {
    try {
        return JSON.parse(content) as T;
    } catch {
        const match = content.match(/\{[\s\S]*\}/);
        if (match) {
            try { return JSON.parse(match[0]) as T; } catch { /* ignore */ }
        }
        return null;
    }
}

// ============================================================================
// AI ANALYSIS PROMPTS - For True AI-Driven Responses
// ============================================================================

const AGENT_ANALYSIS_PROMPT = `You are an AI analyst reviewing the output of a transportation agent.

Your job is to:
1. Evaluate if the agent succeeded or failed
2. Extract the KEY INFORMATION from the agent's data
3. Identify what ADDITIONAL INFORMATION might be needed
4. Suggest the NEXT BEST ACTION

Respond in JSON:
{
  "evaluation": "success" | "partial" | "failed",
  "key_findings": ["list of important facts"],
  "missing_info": ["what's still needed"],
  "next_action": "recommendation for next step",
  "confidence": 0.0-1.0
}`;

const REASONING_PROMPT = `You are the FINAL DECISION MAKER for a bus route AI system.

You have received analyzed outputs from multiple agents. Your job is to:
1. SYNTHESIZE all the information into a coherent understanding
2. REASON about the best answer to the user's question
3. IDENTIFY any gaps or uncertainties
4. MAKE A DECISION on the best response

Think step by step:
- What did the user ask?
- What information did the agents find?
- What is the BEST answer based on this data?
- Are there any caveats or alternatives?

Respond in JSON:
{
  "reasoning_steps": ["step 1 of thought process", "step 2", ...],
  "main_finding": "the primary answer to user's question",
  "alternatives": ["other options if applicable"],
  "confidence": 0.0-1.0,
  "caveats": ["any warnings or limitations"]
}`;

const FINAL_RESPONSE_PROMPT = `You are a helpful bus route assistant for Sahara Bus in India.

Generate a NATURAL, CONVERSATIONAL response based on the AI's reasoning.

RULES:
1. Be friendly and helpful, like a local guide
2. Use emojis sparingly: 🚌 bus, 📍 location, ⏱️ time, ✅ success, ❌ issue
3. Highlight key info with **bold** markdown
4. Keep response under 80 words
5. Be specific - use actual bus names, stop names, and times from the data
6. If there are alternatives, mention them briefly
7. If there's uncertainty, acknowledge it honestly

DO NOT use generic phrases. Every response should be SPECIFIC to the data provided.`;

// ============================================================================
// AI PIPELINE FUNCTIONS - Sequential Agent Analysis
// ============================================================================

interface AgentAnalysis {
    agentName: string;
    evaluation: string;
    keyFindings: string[];
    missingInfo: string[];
    nextAction: string;
    confidence: number;
}

interface AIReasoning {
    reasoningSteps: string[];
    mainFinding: string;
    alternatives: string[];
    confidence: number;
    caveats: string[];
}

// Analyze a single agent's output using GEMINI
async function analyzeAgentOutput(agentName: string, output: AgentOutput, query: string): Promise<AgentAnalysis> {
    const userMessage = `User Query: "${query}"

Agent: ${agentName}
Success: ${output.success}
Message: ${output.message || 'N/A'}
Data: ${JSON.stringify(output.data, null, 2)}
Error: ${output.error || 'None'}

Analyze this agent's output and respond with JSON containing: evaluation, key_findings, missing_info, next_action, confidence`;

    try {
        // Use resilient AI call (Gemini → Groq fallback)
        const response = await callAIJSON(AGENT_ANALYSIS_PROMPT, userMessage);
        const parsed = parseJSON<{
            evaluation: string;
            key_findings: string[];
            missing_info: string[];
            next_action: string;
            confidence: number;
        }>(response);

        if (parsed) {
            return {
                agentName,
                evaluation: parsed.evaluation,
                keyFindings: parsed.key_findings || [],
                missingInfo: parsed.missing_info || [],
                nextAction: parsed.next_action || '',
                confidence: parsed.confidence || 0.5
            };
        }
    } catch (error) {
        console.error(`LLM analysis for ${agentName} failed:`, error);
    }

    // Minimal fallback
    return {
        agentName,
        evaluation: output.success ? 'success' : 'failed',
        keyFindings: output.message ? [output.message] : [],
        missingInfo: [],
        nextAction: '',
        confidence: output.confidence
    };
}

// Perform multi-agent reasoning using GEMINI THINKING MODEL (main AI)
async function performAIReasoning(
    query: string,
    analyses: AgentAnalysis[],
    routeData?: Record<string, unknown>
): Promise<AIReasoning> {
    const analysisText = analyses.map(a =>
        `[${a.agentName}] ${a.evaluation.toUpperCase()}\n  Findings: ${a.keyFindings.join('; ')}\n  Missing: ${a.missingInfo.join('; ')}`
    ).join('\n\n');

    const routeInfo = routeData
        ? `\nBest Route Found:\n  Bus: ${routeData.vehicleName}\n  Distance: ${routeData.distanceKm} km\n  ETA: ${routeData.etaMinutes} min\n  Live: ${routeData.isLive ? 'Yes' : 'No'}`
        : '';

    const userMessage = `User Query: "${query}"

Agent Analyses:
${analysisText}
${routeInfo}

Now reason step by step about the best response. Think deeply about:
1. What the user really needs
2. Which agent findings are most relevant
3. What's the best answer based on the data
4. Any alternatives or caveats

Respond in JSON format with: reasoning_steps, main_finding, alternatives, confidence (0-1), caveats`;

    try {
        // Use resilient AI call (Gemini → Groq fallback)
        const response = await callAIReasoning(REASONING_PROMPT, userMessage);
        const parsed = parseJSON<{
            reasoning_steps: string[];
            main_finding: string;
            alternatives: string[];
            confidence: number;
            caveats: string[];
        }>(response);

        if (parsed) {
            return {
                reasoningSteps: parsed.reasoning_steps || [],
                mainFinding: parsed.main_finding || '',
                alternatives: parsed.alternatives || [],
                confidence: parsed.confidence || 0.5,
                caveats: parsed.caveats || []
            };
        }
    } catch (error) {
        console.error('AI reasoning failed:', error);
    }

    // Minimal fallback
    return {
        reasoningSteps: ['Direct analysis of agent outputs'],
        mainFinding: analyses.find(a => a.evaluation === 'success')?.keyFindings[0] || 'No clear finding',
        alternatives: [],
        confidence: 0.5,
        caveats: ['AI reasoning encountered an issue']
    };
}

// Generate final natural language response using LLM
async function generateAIResponse(
    query: string,
    reasoning: AIReasoning,
    routeData?: Record<string, unknown>
): Promise<string> {
    const routeInfo = routeData
        ? `Route Data: Bus "${routeData.vehicleName}", ${routeData.distanceKm}km, ETA ${routeData.etaMinutes}min, Live=${routeData.isLive}`
        : 'No specific route data.';

    const userMessage = `User Query: "${query}"

AI Reasoning:
${reasoning.reasoningSteps.map((s, i) => `${i + 1}. ${s}`).join('\n')}

Main Finding: ${reasoning.mainFinding}
Alternatives: ${reasoning.alternatives.join(', ') || 'None'}
Caveats: ${reasoning.caveats.join(', ') || 'None'}
Confidence: ${Math.round(reasoning.confidence * 100)}%

${routeInfo}

Generate a natural, helpful response for the user.`;

    try {
        // Use resilient AI call for natural response (Gemini → Groq fallback)
        const response = await callAIText(FINAL_RESPONSE_PROMPT, userMessage);
        return response.trim();
    } catch (error) {
        console.error('Final response generation failed:', error);
        // Last resort - use main finding
        return `Based on my analysis: ${reasoning.mainFinding}`;
    }
}

// ============================================================================
// INTENT ANALYZER (LLM-Powered) - IMPROVED
// ============================================================================

const INTENT_PROMPT = `You are the Sahara Bus System intent analyzer. Your job is to understand what transportation help the user needs.

IMPORTANT: Most queries are about TRAVEL. Be AGGRESSIVE about detecting travel intents.

INTENTS (in priority order - check higher ones first):
1. FIND_ROUTE: User wants to travel somewhere. Keywords: "to", "from", "go", "reach", "get to", "travel", "how do I", "take me", "I want to go", "bus to", any two location names
2. TRACK_BUS: User wants to find/track a specific bus. Keywords: "where is", "track", "find bus", "locate", bus name/number mentioned
3. FIND_NEARBY: User wants nearby stops. Keywords: "nearby", "near me", "closest", "around here"
4. FLEET_STATUS: User wants status of all buses. Keywords: "all buses", "fleet", "how many buses"
5. GENERAL: ONLY for pure greetings like "hi", "hello", "hey" with nothing else, or "what can you do"

RULES:
- If query contains ANY location name or travel-related word → NOT GENERAL
- If query mentions "bus", "route", "stop", "travel", "go", "reach" → it's likely FIND_ROUTE or TRACK_BUS
- When in doubt, prefer FIND_ROUTE over GENERAL
- GENERAL should ONLY be used for pure greetings with no travel context

AGENTS:
- DataAgent: Fetches data (always first)
- PathAgent: Finds routes between locations
- TimeAgent: Calculates ETA
- TrackerAgent: Tracks specific buses
- AnalyticsAgent: Fleet analysis

Respond in JSON format:
{"intent":"...","confidence":0.0-1.0,"entities":{"origin":"...","destination":"...","busId":"..."},"requiredAgents":["DataAgent",...],"executionMode":"sequential","reasoning":"..."}`;

// Improved fallback patterns - more aggressive travel detection
const FALLBACK_PATTERNS: Array<{ pattern: RegExp; intent: string; agents: string[]; priority: number }> = [
    // High priority: explicit from-to patterns
    { pattern: /(?:from|at)\s+(.+?)\s+(?:to|towards?)\s+(.+)/i, intent: 'FIND_ROUTE', agents: ['DataAgent', 'PathAgent', 'TimeAgent'], priority: 10 },
    { pattern: /(.+?)\s+to\s+(.+)/i, intent: 'FIND_ROUTE', agents: ['DataAgent', 'PathAgent', 'TimeAgent'], priority: 9 },

    // Travel intent keywords
    { pattern: /(?:how|can|do)\s+(?:i|we)\s+(?:get|go|reach|travel)/i, intent: 'FIND_ROUTE', agents: ['DataAgent', 'PathAgent', 'TimeAgent'], priority: 8 },
    { pattern: /(?:take|bring)\s+me\s+to/i, intent: 'FIND_ROUTE', agents: ['DataAgent', 'PathAgent', 'TimeAgent'], priority: 8 },
    { pattern: /(?:i\s+want|i\s+need|i'd\s+like)\s+to\s+(?:go|get|reach|travel)/i, intent: 'FIND_ROUTE', agents: ['DataAgent', 'PathAgent', 'TimeAgent'], priority: 8 },
    { pattern: /(?:bus|route|way)\s+to\s+(.+)/i, intent: 'FIND_ROUTE', agents: ['DataAgent', 'PathAgent', 'TimeAgent'], priority: 7 },
    { pattern: /(?:going|headed|heading)\s+to\s+(.+)/i, intent: 'FIND_ROUTE', agents: ['DataAgent', 'PathAgent', 'TimeAgent'], priority: 7 },

    // Bus tracking
    { pattern: /(?:where|track|find|locate)\s+(?:is\s+)?(?:the\s+)?(?:bus\s+)?(.+)/i, intent: 'TRACK_BUS', agents: ['DataAgent', 'TrackerAgent'], priority: 6 },

    // Nearby
    { pattern: /nearby|near\s+me|closest|around\s+here|nearest/i, intent: 'FIND_NEARBY', agents: ['DataAgent'], priority: 5 },

    // Fleet status
    { pattern: /(?:all|how\s+many)\s+bus(?:es)?|fleet|active\s+bus/i, intent: 'FLEET_STATUS', agents: ['DataAgent', 'AnalyticsAgent'], priority: 4 },

    // ONLY pure greetings - very strict
    { pattern: /^(?:hi|hello|hey|help|what\s+can\s+you\s+do)[!?.\s]*$/i, intent: 'GENERAL', agents: [], priority: 1 }
];

// Check if query contains travel-related words (used to prevent false GENERAL)
function containsTravelKeywords(query: string): boolean {
    const travelWords = /\b(to|from|bus|route|stop|travel|go|going|reach|get|take|way|how|where|find|track|nearby|near)\b/i;
    return travelWords.test(query);
}

function fallbackIntentAnalysis(query: string): AnalyzedIntent {
    const q = query.toLowerCase().trim();

    // Sort patterns by priority (highest first)
    const sortedPatterns = [...FALLBACK_PATTERNS].sort((a, b) => b.priority - a.priority);

    for (const { pattern, intent, agents } of sortedPatterns) {
        const match = q.match(pattern);
        if (match) {
            // For GENERAL, double-check it's not actually a travel query
            if (intent === 'GENERAL' && containsTravelKeywords(q) && q.length > 10) {
                continue; // Skip GENERAL, try to find a better match
            }

            const entities: AnalyzedIntent['entities'] = {};
            if (intent === 'FIND_ROUTE' && match.length >= 3) {
                entities.origin = match[1]?.replace(/^(how|can|i|get|go|take|me|bus|do|we)\s+/gi, '').trim();
                entities.destination = match[2]?.replace(/[?.!]$/g, '').trim();
            } else if (intent === 'TRACK_BUS' && match[1]) {
                entities.busId = match[1]?.trim();
                entities.busName = match[1]?.trim();
            }

            return {
                intent,
                confidence: intent === 'GENERAL' ? 0.5 : 0.7,
                entities,
                requiredAgents: agents,
                executionMode: 'sequential',
                reasoning: 'Pattern match (improved fallback)'
            };
        }
    }

    // If query has travel keywords but no pattern matched, assume FIND_ROUTE with the query as destination
    if (containsTravelKeywords(q) && q.length > 5) {
        // Try to extract any location-like words
        const words = q.split(/\s+/).filter(w => w.length > 2 && !/^(how|can|i|do|the|a|to|from|get|go|is)$/i.test(w));
        if (words.length > 0) {
            return {
                intent: 'FIND_ROUTE',
                confidence: 0.5,
                entities: { destination: words.join(' ') },
                requiredAgents: ['DataAgent', 'PathAgent', 'TimeAgent'],
                executionMode: 'sequential',
                reasoning: 'Travel keywords detected, attempting route search'
            };
        }
    }

    // Only return GENERAL if query is very short or truly has no travel context
    return {
        intent: q.length <= 10 && !containsTravelKeywords(q) ? 'GENERAL' : 'FIND_ROUTE',
        confidence: 0.3,
        entities: q.length > 10 ? { destination: q } : {},
        requiredAgents: q.length <= 10 && !containsTravelKeywords(q) ? [] : ['DataAgent', 'PathAgent', 'TimeAgent'],
        executionMode: 'sequential',
        reasoning: 'Fallback - attempting best guess'
    };
}

async function analyzeIntent(query: string, hasLocation: boolean): Promise<AnalyzedIntent> {
    try {
        const contextInfo = hasLocation ? 'User has GPS.' : 'No GPS.';
        const response = await callLLM(INTENT_PROMPT, `${contextInfo}\nQuery: "${query}"`);
        const parsed = parseJSON<AnalyzedIntent>(response);

        if (parsed && parsed.intent) {
            if (parsed.requiredAgents.length > 0 && !parsed.requiredAgents.includes('DataAgent')) {
                parsed.requiredAgents.unshift('DataAgent');
            }
            parsed.reasoning = `[LLM] ${parsed.reasoning || ''}`;
            return parsed;
        }
    } catch (error) {
        console.warn('LLM intent analysis failed:', error);
    }

    return fallbackIntentAnalysis(query);
}

// ============================================================================
// RESPONSE SYNTHESIZER (LLM-Powered)
// ============================================================================

const SYNTHESIS_PROMPT = `You are a bus route assistant

CRITICAL RULES - FOLLOW EXACTLY:
1. ONLY use information from the PROVIDED DATA below. Do NOT invent buses, stops, times, or routes.
2. If data shows "route_found: false" - say NO ROUTE and suggest transfer.
3. If data shows success - extract the EXACT bus name, distance, ETA from the data.
4. Use emojis sparingly: 🚌 bus, 📍 location, ⏱️ time
5. Keep response under 60 words.
6. If asked about something not in data, say "I don't have that information."

RESPONSE FORMAT:
- For routes: "🚌 **[BusName from data]** • [distance] km • ~[eta] min"
- For tracking: "📍 **[BusName]** is near **[StopName from data]**"
- For no route: "❌ No direct route. [Suggest alternative]"

NEVER MAKE UP BUS NAMES OR STOP NAMES. Use ONLY what appears in the data.`;

async function synthesizeResponse(
    query: string,
    agentResults: Map<string, AgentOutput>,
    routeData?: Record<string, unknown>
): Promise<string> {
    // Build EXPLICIT structured context for LLM grounding
    const groundingContext: string[] = [];
    groundingContext.push('=== PROVIDED DATA FROM SUPABASE DATABASE ===');

    // DataAgent results
    const dataResult = agentResults.get('DataAgent');
    if (dataResult?.success) {
        groundingContext.push(`[DataAgent] Database has ${dataResult.data.vehicles_count} vehicles, ${dataResult.data.stops_count} stops, ${dataResult.data.live_count} live buses`);

        // Include fleet status for rich context
        if (dataResult.data.active_buses !== undefined) {
            groundingContext.push(`[Fleet Status] ${dataResult.data.active_buses} buses currently active:`);
            groundingContext.push(`  • Moving: ${dataResult.data.buses_moving}, Stopped: ${dataResult.data.buses_stopped}`);

            // List active buses summary
            const fleetSummary = dataResult.data.fleet_summary as string[] | undefined;
            if (fleetSummary && fleetSummary.length > 0) {
                for (const busInfo of fleetSummary) {
                    groundingContext.push(`  • ${busInfo}`);
                }
            }
        }
    }

    // PathAgent results - explicit route data
    const pathResult = agentResults.get('PathAgent');
    if (pathResult) {
        if (pathResult.success && routeData) {
            groundingContext.push(`[PathAgent] SUCCESS - Route found:`);
            groundingContext.push(`  • Bus Name: "${routeData.vehicleName}"`);
            groundingContext.push(`  • Vehicle Number: "${routeData.vehicleNumber}"`);
            groundingContext.push(`  • Distance: ${routeData.distanceKm} km`);
            groundingContext.push(`  • ETA: ${routeData.etaMinutes} minutes`);
            groundingContext.push(`  • Stops: ${(routeData.stopsInPath as string[] || []).join(' → ')}`);
            groundingContext.push(`  • Live Tracking: ${routeData.isLive ? 'YES' : 'NO'}`);
            if (routeData.isLive && typeof routeData.busStopsAway === 'number') {
                groundingContext.push(`  • Bus is ${routeData.busStopsAway} stops away from pickup`);
            }
        } else {
            groundingContext.push(`[PathAgent] FAILED - No direct route found between requested stops`);
            groundingContext.push(`  Suggestion: User may need a transfer route`);
        }
    }

    // TrackerAgent results - enhanced with timestamps and stops ahead
    const trackerResult = agentResults.get('TrackerAgent');
    if (trackerResult) {
        if (trackerResult.success) {
            groundingContext.push(`[TrackerAgent] Bus Location Found:`);
            groundingContext.push(`  • Bus Name: "${trackerResult.data.bus_name}"`);
            groundingContext.push(`  • Vehicle Number: "${trackerResult.data.vehicle_number || 'N/A'}"`);
            groundingContext.push(`  • Nearest Stop: "${trackerResult.data.nearest_stop}"`);
            groundingContext.push(`  • Speed: ${trackerResult.data.speed} km/h`);
            groundingContext.push(`  • Status: ${trackerResult.data.is_moving ? 'Moving' : 'Stopped'}`);
            groundingContext.push(`  • Last Update: ${trackerResult.data.age_seconds}s ago (${trackerResult.data.is_fresh ? 'FRESH' : 'STALE'})`);
            const stopsAhead = trackerResult.data.stops_ahead as string[] | undefined;
            if (stopsAhead && stopsAhead.length > 0) {
                groundingContext.push(`  • Upcoming Stops: ${stopsAhead.join(' → ')}`);
            }
        } else {
            groundingContext.push(`[TrackerAgent] FAILED - ${trackerResult.error || 'Bus not currently broadcasting location'}`);
            if (trackerResult.data.active_buses) {
                groundingContext.push(`  • Active buses: ${trackerResult.data.active_buses}`);
            }
        }
    }

    // TimeAgent results
    const timeResult = agentResults.get('TimeAgent');
    if (timeResult?.success) {
        groundingContext.push(`[TimeAgent] ETA Calculation:`);
        groundingContext.push(`  • Estimated: ${timeResult.data.eta_minutes} minutes`);
        groundingContext.push(`  • Range: ${timeResult.data.eta_range_min}-${timeResult.data.eta_range_max} minutes`);
    }

    groundingContext.push('=== END OF DATA ===');
    groundingContext.push('');
    groundingContext.push(`User Query: "${query}"`);
    groundingContext.push('');
    groundingContext.push('Generate response using ONLY the data above. Do not invent any information.');

    try {
        const response = await callLLM(
            SYNTHESIS_PROMPT,
            groundingContext.join('\n')
        );

        // Parse response
        const parsed = parseJSON<{ response?: string }>(response);
        return parsed?.response || response.replace(/[{}"\n]/g, '').trim();
    } catch (error) {
        console.warn('LLM synthesis failed:', error);
        return generateFallbackResponse(agentResults, routeData);
    }
}


function generateFallbackResponse(
    agentResults: Map<string, AgentOutput>,
    routeData?: Record<string, unknown>
): string {
    if (routeData) {
        const v = routeData.vehicleName || 'Bus';
        const eta = routeData.etaMinutes || '?';
        const dist = routeData.distanceKm || '?';
        let msg = `🚌 **${v}** • ${dist} km • ~${eta} min`;
        if (routeData.isLive && typeof routeData.busStopsAway === 'number' && routeData.busStopsAway >= 0) {
            msg += ` • ${routeData.busStopsAway} stops away`;
        }
        return msg;
    }

    const tracker = agentResults.get('TrackerAgent');
    if (tracker?.success) {
        return `📍 **${tracker.data.bus_name}** is near **${tracker.data.nearest_stop}**`;
    }

    const path = agentResults.get('PathAgent');
    if (path && !path.success) {
        return '❌ No direct route found. Try asking for a transfer route.';
    }

    return 'Request processed.';
}

// ============================================================================
// ALGORITHMS
// ============================================================================

function haversineDistance(lat1: number, lng1: number, lat2: number, lng2: number): number {
    const R = 6371;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLng / 2) ** 2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function calculateETA(distanceKm: number, numStops: number, busSpeed?: number) {
    const avgSpeed = busSpeed && busSpeed > 0 ? busSpeed : 18;
    const hour = new Date().getHours();
    const isRush = (hour >= 8 && hour <= 10) || (hour >= 17 && hour <= 20);
    const trafficFactor = isRush ? 1.3 : 1.0;
    const travelTime = (distanceKm / avgSpeed) * 60 * trafficFactor;
    const stopTime = numStops * 0.5;
    return { etaMinutes: Math.round(travelTime + stopTime), trafficFactor };
}

function findNearbyStops(userLat: number, userLng: number, stops: RouteStop[], vehicles: Vehicle[], radiusKm: number) {
    const vehicleMap = new Map(vehicles.map(v => [v.id, v]));
    const nearby: Array<{ name: string; distanceKm: number; vehicleName: string }> = [];

    for (const stop of stops) {
        const dist = haversineDistance(userLat, userLng, stop.latitude, stop.longitude);
        if (dist <= radiusKm) {
            nearby.push({
                name: stop.name,
                distanceKm: Math.round(dist * 1000) / 1000,
                vehicleName: vehicleMap.get(stop.vehicle_id)?.name || stop.vehicle_id
            });
        }
    }
    return nearby.sort((a, b) => a.distanceKm - b.distanceKm).slice(0, 10);
}

// ============================================================================
// ALGORITHM AGENT - OR Algorithms Layer
// ============================================================================

/**
 * Algorithm types supported by the AlgorithmAgent
 */
type AlgorithmType = 'KNN' | 'TSP' | 'FIXED_ROUTE_MATCH' | 'MCDA_RANKING' | 'ETA_CALCULATION';

interface AlgorithmSelection {
    algorithm: AlgorithmType;
    reason: string;
    parameters: Record<string, unknown>;
    priority: number;
}

interface AlgorithmResult {
    algorithm: AlgorithmType;
    success: boolean;
    data: Record<string, unknown>;
    executionTimeMs: number;
}

interface NearbyStopResult {
    stopName: string;
    stopId: string;
    vehicleId: string;
    vehicleName: string;
    distanceKm: number;
    walkTimeMinutes: number;
    latitude: number;
    longitude: number;
}

interface TSPResult {
    orderedDestinations: string[];
    totalDistanceKm: number;
    estimatedTimeMinutes: number;
    segments: Array<{
        from: string;
        to: string;
        distanceKm: number;
        vehicleName: string | null;
    }>;
    optimizationSavings: {
        distanceSavedKm: number;
        timeSavedMinutes: number;
    };
}

interface FixedRouteMatch {
    vehicleId: string;
    vehicleName: string;
    vehicleNumber: string;
    originStop: string;
    originStopOrder: number;
    destinationStop: string;
    destinationStopOrder: number;
    stopsInPath: string[];
    pathDistanceKm: number;
    isValidDirection: boolean;
    matchConfidence: number;
}

interface MCDARankedOption {
    vehicleId: string;
    vehicleName: string;
    score: number;
    ranking: number;
    factors: {
        totalTime: number;
        proximity: number;
        directness: number;
        reliability: number;
    };
    recommendation: string;
}

/**
 * K-Nearest Neighbors Algorithm
 * Finds the K nearest bus stops to user's GPS location
 */
function knnNearestStops(
    userLat: number,
    userLng: number,
    stops: RouteStop[],
    vehicles: Vehicle[],
    k: number = 5
): NearbyStopResult[] {
    const vehicleMap = new Map(vehicles.map(v => [v.id, v]));
    const walkSpeedKmh = 4.5; // Average walking speed

    const stopsWithDistance = stops.map(stop => {
        const distanceKm = haversineDistance(userLat, userLng, stop.latitude, stop.longitude);
        const walkTimeMinutes = Math.round((distanceKm / walkSpeedKmh) * 60);
        const vehicle = vehicleMap.get(stop.vehicle_id);

        return {
            stopName: stop.name,
            stopId: stop.id,
            vehicleId: stop.vehicle_id,
            vehicleName: vehicle?.name || 'Unknown',
            distanceKm: Math.round(distanceKm * 1000) / 1000,
            walkTimeMinutes,
            latitude: stop.latitude,
            longitude: stop.longitude
        };
    });

    // Sort by distance and return top K
    return stopsWithDistance
        .sort((a, b) => a.distanceKm - b.distanceKm)
        .slice(0, k);
}

/**
 * Traveling Salesman Problem - Nearest Neighbor Heuristic
 * Optimizes the visit order for multiple destinations
 * Time Complexity: O(n²)
 */
function tspOptimizeOrder(
    startLocation: { lat: number; lng: number } | null,
    destinations: string[],
    stops: RouteStop[],
    vehicles: Vehicle[]
): TSPResult {
    if (destinations.length === 0) {
        return {
            orderedDestinations: [],
            totalDistanceKm: 0,
            estimatedTimeMinutes: 0,
            segments: [],
            optimizationSavings: { distanceSavedKm: 0, timeSavedMinutes: 0 }
        };
    }

    if (destinations.length === 1) {
        return {
            orderedDestinations: destinations,
            totalDistanceKm: 0,
            estimatedTimeMinutes: 0,
            segments: [],
            optimizationSavings: { distanceSavedKm: 0, timeSavedMinutes: 0 }
        };
    }

    // Find stop coordinates for each destination
    const destStops: Map<string, RouteStop> = new Map();
    for (const dest of destinations) {
        const matchingStop = stops.find(s =>
            s.name.toLowerCase().includes(dest.toLowerCase()) ||
            dest.toLowerCase().includes(s.name.toLowerCase())
        );
        if (matchingStop) {
            destStops.set(dest, matchingStop);
        }
    }

    // Nearest Neighbor Heuristic
    const orderedDestinations: string[] = [];
    const remaining = new Set(destinations);
    let currentLat = startLocation?.lat ?? 0;
    let currentLng = startLocation?.lng ?? 0;
    let totalDistance = 0;
    const segments: TSPResult['segments'] = [];

    // If no start location, start from first destination
    if (!startLocation && destStops.size > 0) {
        const firstDest = destinations[0];
        const firstStop = destStops.get(firstDest);
        if (firstStop) {
            currentLat = firstStop.latitude;
            currentLng = firstStop.longitude;
            orderedDestinations.push(firstDest);
            remaining.delete(firstDest);
        }
    }

    while (remaining.size > 0) {
        let nearestDest: string | null = null;
        let nearestDistance = Infinity;

        for (const dest of remaining) {
            const stop = destStops.get(dest);
            if (stop) {
                const dist = haversineDistance(currentLat, currentLng, stop.latitude, stop.longitude);
                if (dist < nearestDistance) {
                    nearestDistance = dist;
                    nearestDest = dest;
                }
            }
        }

        if (nearestDest) {
            const prevDest = orderedDestinations[orderedDestinations.length - 1] || 'Start';
            segments.push({
                from: prevDest,
                to: nearestDest,
                distanceKm: Math.round(nearestDistance * 100) / 100,
                vehicleName: null // Will be filled by route matching
            });

            orderedDestinations.push(nearestDest);
            remaining.delete(nearestDest);
            totalDistance += nearestDistance;

            const nearestStop = destStops.get(nearestDest);
            if (nearestStop) {
                currentLat = nearestStop.latitude;
                currentLng = nearestStop.longitude;
            }
        } else {
            // Fallback: add remaining in original order
            for (const dest of remaining) {
                orderedDestinations.push(dest);
            }
            break;
        }
    }

    // Calculate original (unoptimized) distance for comparison
    let originalDistance = 0;
    let prevStop: RouteStop | null = null;
    for (const dest of destinations) {
        const stop = destStops.get(dest);
        if (stop && prevStop) {
            originalDistance += haversineDistance(prevStop.latitude, prevStop.longitude, stop.latitude, stop.longitude);
        }
        prevStop = stop || null;
    }

    const avgSpeedKmh = 20; // Including traffic and stops
    const estimatedTime = Math.round((totalDistance / avgSpeedKmh) * 60);
    const originalTime = Math.round((originalDistance / avgSpeedKmh) * 60);

    return {
        orderedDestinations,
        totalDistanceKm: Math.round(totalDistance * 100) / 100,
        estimatedTimeMinutes: estimatedTime,
        segments,
        optimizationSavings: {
            distanceSavedKm: Math.round((originalDistance - totalDistance) * 100) / 100,
            timeSavedMinutes: Math.max(0, originalTime - estimatedTime)
        }
    };
}

/**
 * Fixed Route Matcher
 * Matches user's origin/destination to predefined fixed bus routes
 * Validates that stops appear in the correct order on the fixed path
 */
function matchFixedRoutes(
    origin: string,
    destination: string,
    stops: RouteStop[],
    vehicles: Vehicle[]
): FixedRouteMatch[] {
    const vehicleMap = new Map(vehicles.map(v => [v.id, v]));
    const matches: FixedRouteMatch[] = [];

    // Group stops by vehicle
    const vehicleStopsMap = new Map<string, RouteStop[]>();
    for (const stop of stops) {
        if (!vehicleStopsMap.has(stop.vehicle_id)) {
            vehicleStopsMap.set(stop.vehicle_id, []);
        }
        vehicleStopsMap.get(stop.vehicle_id)!.push(stop);
    }

    // Sort each vehicle's stops by stop_order (the fixed path)
    for (const [vehicleId, vehicleStops] of vehicleStopsMap) {
        vehicleStops.sort((a, b) => a.stop_order - b.stop_order);
    }

    // Find matches for each vehicle
    for (const [vehicleId, vehicleStops] of vehicleStopsMap) {
        const vehicle = vehicleMap.get(vehicleId);
        if (!vehicle) continue;

        // Find origin stop
        const originStop = vehicleStops.find(s =>
            s.name.toLowerCase().includes(origin.toLowerCase()) ||
            origin.toLowerCase().includes(s.name.toLowerCase())
        );

        // Find destination stop
        const destStop = vehicleStops.find(s =>
            s.name.toLowerCase().includes(destination.toLowerCase()) ||
            destination.toLowerCase().includes(s.name.toLowerCase())
        );

        if (!originStop || !destStop) continue;

        // Check direction: destination must come AFTER origin on the fixed path
        const isValidDirection = destStop.stop_order > originStop.stop_order;

        // Extract stops in path (following the fixed route order)
        const stopsInPath = vehicleStops
            .filter(s => s.stop_order >= originStop.stop_order && s.stop_order <= destStop.stop_order)
            .map(s => s.name);

        // Calculate path distance along the fixed route
        let pathDistance = 0;
        const pathStops = vehicleStops.filter(s =>
            s.stop_order >= originStop.stop_order && s.stop_order <= destStop.stop_order
        );
        for (let i = 0; i < pathStops.length - 1; i++) {
            pathDistance += haversineDistance(
                pathStops[i].latitude, pathStops[i].longitude,
                pathStops[i + 1].latitude, pathStops[i + 1].longitude
            );
        }

        // Calculate match confidence based on string similarity
        const originSimilarity = calculateStringSimilarity(origin.toLowerCase(), originStop.name.toLowerCase());
        const destSimilarity = calculateStringSimilarity(destination.toLowerCase(), destStop.name.toLowerCase());
        const baseConfidence = (originSimilarity + destSimilarity) / 2;
        const matchConfidence = isValidDirection ? baseConfidence : baseConfidence * 0.3;

        matches.push({
            vehicleId,
            vehicleName: vehicle.name,
            vehicleNumber: vehicle.vehicle_number,
            originStop: originStop.name,
            originStopOrder: originStop.stop_order,
            destinationStop: destStop.name,
            destinationStopOrder: destStop.stop_order,
            stopsInPath,
            pathDistanceKm: Math.round(pathDistance * 100) / 100,
            isValidDirection,
            matchConfidence
        });
    }

    // Return only valid direction matches, sorted by confidence
    return matches
        .filter(m => m.isValidDirection)
        .sort((a, b) => b.matchConfidence - a.matchConfidence);
}

/**
 * String similarity using bigram Jaccard coefficient
 */
function calculateStringSimilarity(s1: string, s2: string): number {
    if (s1 === s2) return 1.0;
    if (s1.length === 0 || s2.length === 0) return 0.0;

    const getBigrams = (s: string): Set<string> => {
        const bigrams = new Set<string>();
        for (let i = 0; i < s.length - 1; i++) {
            bigrams.add(s.substring(i, i + 2));
        }
        return bigrams;
    };

    const bigrams1 = getBigrams(s1);
    const bigrams2 = getBigrams(s2);

    const intersection = new Set([...bigrams1].filter(x => bigrams2.has(x)));
    const union = new Set([...bigrams1, ...bigrams2]);

    return union.size > 0 ? intersection.size / union.size : 0;
}

/**
 * Multi-Criteria Decision Analysis (MCDA)
 * Ranks bus options using weighted criteria
 */
function mcdaRankOptions(
    options: BusOption[],
    liveLocations: LiveLocation[],
    preference: 'FASTEST' | 'LEAST_WAITING' | 'MOST_DIRECT' = 'FASTEST'
): MCDARankedOption[] {
    if (options.length === 0) return [];

    const liveLocationMap = new Map(liveLocations.map(l => [l.bus_id, l]));

    // Define weights based on preference
    const weights = {
        FASTEST: { totalTime: 0.50, proximity: 0.30, directness: 0.15, reliability: 0.05 },
        LEAST_WAITING: { totalTime: 0.20, proximity: 0.50, directness: 0.20, reliability: 0.10 },
        MOST_DIRECT: { totalTime: 0.30, proximity: 0.20, directness: 0.40, reliability: 0.10 }
    }[preference];

    const rankedOptions = options.map(option => {
        const liveLocation = liveLocationMap.get(option.vehicleId);

        // Calculate factors (normalized 0-1)
        const waitTime = option.isLive && option.busStopsAwayFromPickup > 0
            ? option.busStopsAwayFromPickup * 2 // ~2 min per stop
            : 10; // Default wait
        const travelTime = (option.routeDistanceKm / 18) * 60; // 18 km/h avg
        const totalTime = waitTime + travelTime;

        const factors = {
            totalTime: Math.max(0, 1 - (totalTime / 120)), // Lower time = higher score
            proximity: option.isLive ? Math.max(0, 1 - (option.busStopsAwayFromPickup / 20)) : 0,
            directness: Math.max(0, 1 - (option.stopsOnRoute / 30)),
            reliability: option.isLive ? 1.0 : 0.3
        };

        // Calculate weighted score
        const score = (
            factors.totalTime * weights.totalTime +
            factors.proximity * weights.proximity +
            factors.directness * weights.directness +
            factors.reliability * weights.reliability
        ) * 100;

        // Generate recommendation
        let recommendation: string;
        if (option.isLive && option.busStopsAwayFromPickup <= 2) {
            recommendation = `🚌 ${option.vehicleName} arriving soon - ${option.busStopsAwayFromPickup} stops away`;
        } else if (factors.directness > 0.7) {
            recommendation = `🎯 ${option.vehicleName} - Direct route with ${option.stopsOnRoute} stops`;
        } else {
            recommendation = `${option.vehicleName} - ${Math.round(totalTime)} min total`;
        }

        return {
            vehicleId: option.vehicleId,
            vehicleName: option.vehicleName,
            score: Math.round(score * 10) / 10,
            ranking: 0, // Will be set after sorting
            factors,
            recommendation
        };
    });

    // Sort by score and assign rankings
    rankedOptions.sort((a, b) => b.score - a.score);
    rankedOptions.forEach((opt, idx) => { opt.ranking = idx + 1; });

    return rankedOptions;
}

/**
 * Algorithm Selector - Uses intent and context to choose algorithms
 */
function selectAlgorithms(
    intent: string,
    entities: { origin?: string; destination?: string; busId?: string },
    hasUserLocation: boolean,
    destinationCount: number
): AlgorithmSelection[] {
    const selections: AlgorithmSelection[] = [];

    // Always run KNN if user has GPS (for nearby stops context)
    if (hasUserLocation) {
        selections.push({
            algorithm: 'KNN',
            reason: 'User has GPS - find nearby stops for context',
            parameters: { k: 5 },
            priority: 1
        });
    }

    // Multi-destination? Use TSP
    if (destinationCount > 1) {
        selections.push({
            algorithm: 'TSP',
            reason: `Optimizing order for ${destinationCount} destinations`,
            parameters: { destinationCount },
            priority: 2
        });
    }

    // Route finding? Use Fixed Route Matcher
    if (intent === 'FIND_ROUTE' && entities.origin && entities.destination) {
        selections.push({
            algorithm: 'FIXED_ROUTE_MATCH',
            reason: 'Matching journey to predefined bus routes',
            parameters: { origin: entities.origin, destination: entities.destination },
            priority: 3
        });
    }

    // Multiple bus options? Use MCDA to rank
    if (intent === 'FIND_ROUTE') {
        selections.push({
            algorithm: 'MCDA_RANKING',
            reason: 'Ranking bus options by multiple criteria',
            parameters: { preference: 'FASTEST' },
            priority: 4
        });
    }

    // Always include ETA calculation for route queries
    if (intent === 'FIND_ROUTE' || intent === 'TRACK_BUS') {
        selections.push({
            algorithm: 'ETA_CALCULATION',
            reason: 'Computing arrival times',
            parameters: {},
            priority: 5
        });
    }

    return selections.sort((a, b) => a.priority - b.priority);
}

// ============================================================================
// ALGORITHM AGENT
// ============================================================================

const AlgorithmAgent: Agent = {
    name: 'AlgorithmAgent',
    description: 'Executes Operations Research algorithms and shares results with other agents',
    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'AlgorithmAgent: Selecting and executing algorithms');

        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];
        const liveLocations = getData<LiveLocation[]>(context, 'live_locations') || [];

        const origin = input.parameters.origin as string | undefined;
        const destination = input.parameters.destination as string | undefined;
        const userLat = input.parameters.user_lat as number | undefined;
        const userLng = input.parameters.user_lng as number | undefined;
        const destinations = input.parameters.destinations as string[] | undefined;

        // Determine which algorithms to run
        const hasUserLocation = userLat !== undefined && userLng !== undefined;
        const destinationCount = destinations?.length || (origin && destination ? 1 : 0);
        const algorithmSelections = selectAlgorithms(
            input.intent,
            { origin, destination },
            hasUserLocation,
            destinationCount
        );

        trace(context, 'ALGORITHM_SELECTION', `Selected ${algorithmSelections.length} algorithms: ${algorithmSelections.map(s => s.algorithm).join(', ')}`);

        const results: AlgorithmResult[] = [];
        const algorithmData: Record<string, unknown> = {};

        // Execute each selected algorithm
        for (const selection of algorithmSelections) {
            const algoStart = Date.now();

            try {
                switch (selection.algorithm) {
                    case 'KNN': {
                        if (hasUserLocation) {
                            const nearbyStops = knnNearestStops(userLat!, userLng!, stops, vehicles, 5);
                            algorithmData['nearby_stops'] = nearbyStops;
                            setData(context, 'knn_nearby_stops', nearbyStops);
                            results.push({
                                algorithm: 'KNN',
                                success: true,
                                data: { count: nearbyStops.length, nearest: nearbyStops[0]?.stopName },
                                executionTimeMs: Date.now() - algoStart
                            });
                            trace(context, 'ALGORITHM_COMPLETE', `KNN: Found ${nearbyStops.length} nearby stops`);
                        }
                        break;
                    }

                    case 'TSP': {
                        if (destinations && destinations.length > 1) {
                            const startLoc = hasUserLocation ? { lat: userLat!, lng: userLng! } : null;
                            const tspResult = tspOptimizeOrder(startLoc, destinations, stops, vehicles);
                            algorithmData['tsp_result'] = tspResult;
                            setData(context, 'tsp_optimized_order', tspResult);
                            results.push({
                                algorithm: 'TSP',
                                success: true,
                                data: {
                                    order: tspResult.orderedDestinations,
                                    savedKm: tspResult.optimizationSavings.distanceSavedKm
                                },
                                executionTimeMs: Date.now() - algoStart
                            });
                            trace(context, 'ALGORITHM_COMPLETE', `TSP: Optimized ${destinations.length} destinations, saved ${tspResult.optimizationSavings.distanceSavedKm}km`);
                        }
                        break;
                    }

                    case 'FIXED_ROUTE_MATCH': {
                        if (origin && destination) {
                            const matches = matchFixedRoutes(origin, destination, stops, vehicles);
                            algorithmData['route_matches'] = matches;
                            setData(context, 'fixed_route_matches', matches);
                            results.push({
                                algorithm: 'FIXED_ROUTE_MATCH',
                                success: matches.length > 0,
                                data: { matchCount: matches.length, bestMatch: matches[0]?.vehicleName },
                                executionTimeMs: Date.now() - algoStart
                            });
                            trace(context, 'ALGORITHM_COMPLETE', `Fixed Route Match: Found ${matches.length} matching routes`);
                        }
                        break;
                    }

                    case 'MCDA_RANKING': {
                        const routeMatches = getData<FixedRouteMatch[]>(context, 'fixed_route_matches') || [];
                        if (routeMatches.length > 0) {
                            // Convert to BusOption format
                            const busOptions: BusOption[] = routeMatches.map(match => {
                                const liveLocation = liveLocations.find(l => l.bus_id === match.vehicleId);
                                let busStopsAway = 5; // Default

                                if (liveLocation) {
                                    const vehicleStops = stops
                                        .filter(s => s.vehicle_id === match.vehicleId)
                                        .sort((a, b) => a.stop_order - b.stop_order);

                                    let nearestStopOrder = 0;
                                    let minDist = Infinity;
                                    for (const s of vehicleStops) {
                                        const d = haversineDistance(liveLocation.latitude, liveLocation.longitude, s.latitude, s.longitude);
                                        if (d < minDist) { minDist = d; nearestStopOrder = s.stop_order; }
                                    }
                                    busStopsAway = Math.max(0, match.originStopOrder - nearestStopOrder);
                                }

                                return {
                                    vehicleId: match.vehicleId,
                                    vehicleName: match.vehicleName,
                                    vehicleNumber: match.vehicleNumber,
                                    pickupStop: match.originStop,
                                    pickupStopOrder: match.originStopOrder,
                                    destinationStop: match.destinationStop,
                                    destinationStopOrder: match.destinationStopOrder,
                                    stopsOnRoute: match.stopsInPath.length,
                                    routeDistanceKm: match.pathDistanceKm,
                                    isLive: !!liveLocation,
                                    currentLocation: liveLocation ? {
                                        lat: liveLocation.latitude,
                                        lng: liveLocation.longitude,
                                        speed: liveLocation.speed || 0
                                    } : undefined,
                                    busStopsAwayFromPickup: busStopsAway
                                };
                            });

                            const rankedOptions = mcdaRankOptions(busOptions, liveLocations, 'FASTEST');
                            algorithmData['mcda_ranking'] = rankedOptions;
                            setData(context, 'mcda_ranked_buses', rankedOptions);
                            results.push({
                                algorithm: 'MCDA_RANKING',
                                success: true,
                                data: { rankedCount: rankedOptions.length, bestOption: rankedOptions[0]?.vehicleName },
                                executionTimeMs: Date.now() - algoStart
                            });
                            trace(context, 'ALGORITHM_COMPLETE', `MCDA: Ranked ${rankedOptions.length} bus options`);
                        }
                        break;
                    }

                    case 'ETA_CALCULATION': {
                        // ETA is already handled by TimeAgent, just mark as delegated
                        results.push({
                            algorithm: 'ETA_CALCULATION',
                            success: true,
                            data: { delegatedTo: 'TimeAgent' },
                            executionTimeMs: 0
                        });
                        break;
                    }
                }
            } catch (error) {
                console.error(`Algorithm ${selection.algorithm} failed:`, error);
                results.push({
                    algorithm: selection.algorithm,
                    success: false,
                    data: { error: error instanceof Error ? error.message : 'Unknown error' },
                    executionTimeMs: Date.now() - algoStart
                });
            }
        }

        // Broadcast results to other agents via shared data
        setData(context, 'algorithm_results', algorithmData);

        const successCount = results.filter(r => r.success).length;

        return {
            success: successCount > 0,
            agentName: 'AlgorithmAgent',
            data: {
                algorithms_executed: results.map(r => r.algorithm),
                success_count: successCount,
                total_count: results.length,
                results: algorithmData
            },
            message: `Executed ${successCount}/${results.length} algorithms successfully`,
            confidence: successCount / Math.max(1, results.length),
            toolsUsed: results.map(r => r.algorithm),
            executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// SMART ROUTE OPTIMIZATION ALGORITHMS
// ============================================================================

/**
 * Calculate walking time from user's GPS to a bus stop
 * Average walking speed: 4-5 km/h (using 4.5 km/h)
 */
function calculateWalkTime(userLat: number, userLng: number, stopLat: number, stopLng: number): WalkTimeResult {
    const distanceKm = haversineDistance(userLat, userLng, stopLat, stopLng);
    const walkSpeedKmh = 4.5;  // Average walking speed
    const walkTimeMinutes = (distanceKm / walkSpeedKmh) * 60;

    return {
        distanceKm: Math.round(distanceKm * 1000) / 1000,
        distanceMeters: Math.round(distanceKm * 1000),
        walkTimeMinutes: Math.round(walkTimeMinutes),
        isWalkable: distanceKm <= 2.0  // Max 2km walking distance
    };
}

interface WalkTimeResult {
    distanceKm: number;
    distanceMeters: number;
    walkTimeMinutes: number;
    isWalkable: boolean;
}

/**
 * Calculate TOTAL trip time including all phases
 * Total = Walk Time + Wait Time + Travel Time
 */
function calculateTotalTripTime(
    walkTimeMinutes: number,
    busEtaToPickup: number,  // Time for bus to reach pickup stop
    travelTimeMinutes: number  // Time from pickup to destination
): TotalTripResult {
    // Wait time: how long user waits at stop after arriving
    // If bus arrives before user, wait time is 0
    // If user arrives before bus, wait time = busETA - walkTime
    const waitTimeMinutes = Math.max(0, busEtaToPickup - walkTimeMinutes);

    // User should leave now and walk to the stop
    const shouldLeaveIn = Math.max(0, busEtaToPickup - walkTimeMinutes - 2); // 2 min buffer

    const totalMinutes = walkTimeMinutes + waitTimeMinutes + travelTimeMinutes;

    return {
        walkTimeMinutes,
        waitTimeMinutes: Math.round(waitTimeMinutes),
        travelTimeMinutes: Math.round(travelTimeMinutes),
        totalTripMinutes: Math.round(totalMinutes),
        shouldLeaveIn: Math.round(shouldLeaveIn),
        willMissbus: walkTimeMinutes > busEtaToPickup + 2  // Adding 2 min grace period
    };
}

interface TotalTripResult {
    walkTimeMinutes: number;
    waitTimeMinutes: number;
    travelTimeMinutes: number;
    totalTripMinutes: number;
    shouldLeaveIn: number;
    willMissbus: boolean;
}

/**
 * Bus route option for comparison
 */
interface BusOption {
    vehicleId: string;
    vehicleName: string;
    vehicleNumber: string;
    pickupStop: string;
    pickupStopOrder: number;
    destinationStop: string;
    destinationStopOrder: number;
    stopsOnRoute: number;  // Stops between pickup and destination
    routeDistanceKm: number;
    isLive: boolean;
    currentLocation?: { lat: number; lng: number; speed: number };
    busStopsAwayFromPickup: number;
}

/**
 * Find the OPTIMAL bus considering total trip time
 * Handles the case: Bus A (longer route) arrives first vs Bus B (shorter route) arrives later
 */
function findOptimalBus(
    userLat: number,
    userLng: number,
    options: BusOption[],
    stops: RouteStop[],
    liveLocations: LiveLocation[]
): OptimalBusResult | null {
    if (options.length === 0) return null;

    const comparisons: BusComparison[] = [];

    for (const option of options) {
        // 1. Find the pickup stop coordinates
        const pickupStop = stops.find(s =>
            s.vehicle_id === option.vehicleId &&
            s.name.toLowerCase() === option.pickupStop.toLowerCase()
        );

        if (!pickupStop) continue;

        // 2. Calculate WALK TIME from user to pickup stop
        const walkResult = calculateWalkTime(userLat, userLng, pickupStop.latitude, pickupStop.longitude);

        // 3. Calculate BUS ETA to pickup stop
        let busEtaToPickup: number;

        if (option.isLive && option.currentLocation) {
            // Use live location to calculate ETA
            const busDistanceToPickup = haversineDistance(
                option.currentLocation.lat,
                option.currentLocation.lng,
                pickupStop.latitude,
                pickupStop.longitude
            );
            const speed = option.currentLocation.speed > 0 ? option.currentLocation.speed : 18;
            busEtaToPickup = (busDistanceToPickup / speed) * 60 + (option.busStopsAwayFromPickup * 0.5);
        } else {
            // Estimate based on stops away (2 min per stop)
            busEtaToPickup = option.busStopsAwayFromPickup * 2;
        }

        // 4. Calculate TRAVEL TIME from pickup to destination
        const eta = calculateETA(option.routeDistanceKm, option.stopsOnRoute, option.currentLocation?.speed);
        const travelTime = eta.etaMinutes;

        // 5. Calculate TOTAL TRIP TIME
        const tripResult = calculateTotalTripTime(walkResult.walkTimeMinutes, busEtaToPickup, travelTime);

        comparisons.push({
            option,
            walkResult,
            busEtaToPickup: Math.round(busEtaToPickup),
            travelTime,
            tripResult,
            score: tripResult.totalTripMinutes + (tripResult.willMissbus ? 100 : 0)  // Penalize if will miss bus
        });
    }

    // Sort by score (lowest total trip time wins)
    comparisons.sort((a, b) => a.score - b.score);

    if (comparisons.length === 0) return null;

    const best = comparisons[0];
    const alternatives = comparisons.slice(1, 3);  // Top 2 alternatives

    return {
        bestBus: {
            vehicleId: best.option.vehicleId,
            vehicleName: best.option.vehicleName,
            vehicleNumber: best.option.vehicleNumber,
            pickupStop: best.option.pickupStop,
            destinationStop: best.option.destinationStop,
            isLive: best.option.isLive
        },
        walkToStop: best.walkResult,
        busArrivesIn: best.busEtaToPickup,
        waitAtStop: best.tripResult.waitTimeMinutes,
        travelTime: best.travelTime,
        totalTripTime: best.tripResult.totalTripMinutes,
        shouldLeaveIn: best.tripResult.shouldLeaveIn,
        willMissBus: best.tripResult.willMissbus,
        recommendation: generateRecommendation(best),
        alternatives: alternatives.map(alt => ({
            vehicleName: alt.option.vehicleName,
            totalTripTime: alt.tripResult.totalTripMinutes,
            reason: alt.tripResult.totalTripMinutes - best.tripResult.totalTripMinutes > 0
                ? `${alt.tripResult.totalTripMinutes - best.tripResult.totalTripMinutes} min slower`
                : 'Similar time'
        }))
    };
}

interface BusComparison {
    option: BusOption;
    walkResult: WalkTimeResult;
    busEtaToPickup: number;
    travelTime: number;
    tripResult: TotalTripResult;
    score: number;
}

interface OptimalBusResult {
    bestBus: {
        vehicleId: string;
        vehicleName: string;
        vehicleNumber: string;
        pickupStop: string;
        destinationStop: string;
        isLive: boolean;
    };
    walkToStop: WalkTimeResult;
    busArrivesIn: number;
    waitAtStop: number;
    travelTime: number;
    totalTripTime: number;
    shouldLeaveIn: number;
    willMissBus: boolean;
    recommendation: string;
    alternatives: Array<{
        vehicleName: string;
        totalTripTime: number;
        reason: string;
    }>;
}

function generateRecommendation(comparison: BusComparison): string {
    const { walkResult, tripResult, option } = comparison;

    if (tripResult.willMissbus) {
        return `Bus ${option.vehicleName} is too close. Walk faster or wait for next bus.`;
    }

    if (tripResult.shouldLeaveIn <= 0) {
        return `Leave NOW! Walk ${walkResult.distanceMeters}m to ${option.pickupStop}.`;
    }

    if (tripResult.waitTimeMinutes > 10) {
        return `Leave in ${tripResult.shouldLeaveIn} min. You'll wait ${tripResult.waitTimeMinutes} min at the stop.`;
    }

    return `Leave in ${tripResult.shouldLeaveIn} min. ${option.vehicleName} arriving in ${comparison.busEtaToPickup} min.`;
}

// ============================================================================
// AGENTS
// ============================================================================

const DataAgent: Agent = {
    name: 'DataAgent',
    description: 'Fetches data from Supabase and computes fleet status',
    async execute(context: AgentContext, _input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'DataAgent: Fetching comprehensive data');

        try {
            const client = getSupabase();
            const [vehicles, stops, locations] = await Promise.all([
                client.from('vehicles').select('*'),
                client.from('route_stops').select('*').order('vehicle_id').order('stop_order'),
                client.from('locations').select('*').order('timestamp', { ascending: false }).limit(500)
            ]);

            const vehiclesData = (vehicles.data || []) as Vehicle[];
            const stopsData = (stops.data || []) as RouteStop[];
            const locationsData = (locations.data || []) as LiveLocation[];

            // Get latest location per bus
            const latestLocations = new Map<string, LiveLocation>();
            for (const loc of locationsData) {
                if (!latestLocations.has(loc.bus_id)) {
                    latestLocations.set(loc.bus_id, loc);
                }
            }

            // Build vehicle lookup
            const vehicleMap = new Map(vehiclesData.map(v => [v.id, v]));

            // Group stops by vehicle
            const stopsByVehicle = new Map<string, RouteStop[]>();
            for (const stop of stopsData) {
                if (!stopsByVehicle.has(stop.vehicle_id)) {
                    stopsByVehicle.set(stop.vehicle_id, []);
                }
                stopsByVehicle.get(stop.vehicle_id)!.push(stop);
            }

            // Build rich fleet status for AI context
            const now = Date.now();
            const activeBusList: ActiveBusInfo[] = [];
            let busesMoving = 0;
            let busesStopped = 0;

            for (const [busId, loc] of latestLocations) {
                const vehicle = vehicleMap.get(busId);
                const vehicleStops = stopsByVehicle.get(busId) || [];
                const ageSeconds = Math.floor((now - new Date(loc.timestamp).getTime()) / 1000);

                // Only include fresh locations (within 5 minutes)
                if (ageSeconds > 300) continue;

                // Find nearest stop
                let nearestStop: string | null = null;
                let minDist = Infinity;
                for (const stop of vehicleStops) {
                    const dist = haversineDistance(loc.latitude, loc.longitude, stop.latitude, stop.longitude);
                    if (dist < minDist) {
                        minDist = dist;
                        nearestStop = stop.name;
                    }
                }

                const isMoving = (loc.speed || 0) > 2;
                if (isMoving) busesMoving++;
                else busesStopped++;

                activeBusList.push({
                    bus_id: busId,
                    bus_name: vehicle?.name || 'Unknown',
                    vehicle_number: vehicle?.vehicle_number,
                    current_lat: loc.latitude,
                    current_lng: loc.longitude,
                    speed_kmh: loc.speed || 0,
                    is_moving: isMoving,
                    heading: loc.heading,
                    nearest_stop: nearestStop,
                    stops_on_route: vehicleStops.length,
                    last_update: loc.timestamp,
                    age_seconds: ageSeconds
                });
            }

            // Sort by freshness (most recent first)
            activeBusList.sort((a, b) => a.age_seconds - b.age_seconds);

            const fleetStatus: FleetStatus = {
                total_vehicles: vehiclesData.length,
                active_buses: activeBusList.length,
                offline_buses: vehiclesData.length - activeBusList.length,
                buses_moving: busesMoving,
                buses_stopped: busesStopped,
                active_bus_list: activeBusList
            };

            // Store all data in shared context
            setData(context, 'vehicles', vehiclesData);
            setData(context, 'stops', stopsData);
            setData(context, 'live_locations', Array.from(latestLocations.values()));
            setData(context, 'fleet_status', fleetStatus);

            trace(context, 'FLEET_STATUS', `Fleet: ${activeBusList.length} active (${busesMoving} moving, ${busesStopped} stopped)`);

            return {
                success: true,
                agentName: 'DataAgent',
                data: {
                    vehicles_count: vehiclesData.length,
                    stops_count: stopsData.length,
                    live_count: latestLocations.size,
                    active_buses: activeBusList.length,
                    buses_moving: busesMoving,
                    buses_stopped: busesStopped,
                    fleet_summary: activeBusList.slice(0, 5).map(b =>
                        `${b.bus_name}: ${b.is_moving ? 'moving' : 'stopped'} near ${b.nearest_stop || 'unknown'}`
                    )
                },
                message: `Loaded ${vehiclesData.length} vehicles, ${stopsData.length} stops. Fleet: ${activeBusList.length} active buses`,
                confidence: 1.0,
                toolsUsed: ['supabase_query', 'fleet_analysis'],
                executionTimeMs: Date.now() - startTime
            };
        } catch (error) {
            return {
                success: false, agentName: 'DataAgent', data: {},
                error: error instanceof Error ? error.message : 'Data fetch failed',
                confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime
            };
        }
    }
};

const PathAgent: Agent = {
    name: 'PathAgent',
    description: 'Finds routes',
    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'PathAgent: Finding routes');

        const origin = input.parameters.origin as string;
        const destination = input.parameters.destination as string;

        if (!origin || !destination) {
            return { success: false, agentName: 'PathAgent', data: {}, error: 'Origin and destination required', confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime };
        }

        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];
        const locations = getData<LiveLocation[]>(context, 'live_locations') || [];
        const userLat = input.parameters.user_lat as number | undefined;
        const userLng = input.parameters.user_lng as number | undefined;

        // Build bus options for comparison
        const busOptions: BusOption[] = [];
        const matchedRoutes: Array<{
            vehicleId: string;
            vehicleName: string;
            vehicleNumber: string;
            distanceKm: number;
            numStops: number;
            etaMinutes: number;
            isLive: boolean;
            busStopsAway?: number;
            stopsInPath: string[];
        }> = [];

        for (const vehicle of vehicles) {
            const vehicleStops = stops.filter(s => s.vehicle_id === vehicle.id).sort((a, b) => a.stop_order - b.stop_order);
            const originStop = vehicleStops.find(s => s.name.toLowerCase().includes(origin.toLowerCase()));
            const destStop = vehicleStops.find(s => s.name.toLowerCase().includes(destination.toLowerCase()));

            if (originStop && destStop && originStop.stop_order < destStop.stop_order) {
                const stopsInPath = vehicleStops.filter(s => s.stop_order >= originStop.stop_order && s.stop_order <= destStop.stop_order);

                let distance = 0;
                for (let i = 0; i < stopsInPath.length - 1; i++) {
                    distance += haversineDistance(stopsInPath[i].latitude, stopsInPath[i].longitude, stopsInPath[i + 1].latitude, stopsInPath[i + 1].longitude);
                }

                const liveLocation = locations.find(l => l.bus_id === vehicle.id);
                const eta = calculateETA(distance, stopsInPath.length - 1, liveLocation?.speed || undefined);

                let busStopsAway: number | undefined = undefined;
                let currentStopOrder = 0;
                if (liveLocation) {
                    let minDist = Infinity;
                    for (const s of vehicleStops) {
                        const d = haversineDistance(liveLocation.latitude, liveLocation.longitude, s.latitude, s.longitude);
                        if (d < minDist) { minDist = d; currentStopOrder = s.stop_order; }
                    }
                    busStopsAway = Math.max(0, originStop.stop_order - currentStopOrder);
                }

                matchedRoutes.push({
                    vehicleId: vehicle.id,
                    vehicleName: vehicle.name,
                    vehicleNumber: vehicle.vehicle_number,
                    distanceKm: Math.round(distance * 10) / 10,
                    numStops: stopsInPath.length - 1,
                    etaMinutes: eta.etaMinutes,
                    isLive: !!liveLocation,
                    busStopsAway,
                    stopsInPath: stopsInPath.map(s => s.name)
                });

                // Also build BusOption for optimal bus calculation
                busOptions.push({
                    vehicleId: vehicle.id,
                    vehicleName: vehicle.name,
                    vehicleNumber: vehicle.vehicle_number,
                    pickupStop: originStop.name,
                    pickupStopOrder: originStop.stop_order,
                    destinationStop: destStop.name,
                    destinationStopOrder: destStop.stop_order,
                    stopsOnRoute: stopsInPath.length - 1,
                    routeDistanceKm: distance,
                    isLive: !!liveLocation,
                    currentLocation: liveLocation ? {
                        lat: liveLocation.latitude,
                        lng: liveLocation.longitude,
                        speed: liveLocation.speed || 0
                    } : undefined,
                    busStopsAwayFromPickup: busStopsAway || 5  // Default estimate
                });
            }
        }

        if (matchedRoutes.length === 0) {
            return { success: false, agentName: 'PathAgent', data: { route_found: false }, error: 'No direct route found', suggestedNextAgent: 'TransferAgent', confidence: 0, toolsUsed: ['compute_route'], executionTimeMs: Date.now() - startTime };
        }

        // ====================================================================
        // SMART ROUTE OPTIMIZATION: Use findOptimalBus when user has GPS
        // This considers: Walk Time + Wait Time + Travel Time = Total Trip
        // ====================================================================
        let bestRoute = matchedRoutes[0];
        let optimizationResult: OptimalBusResult | null = null;

        if (userLat !== undefined && userLng !== undefined && busOptions.length > 0) {
            trace(context, 'OPTIMIZATION', `Comparing ${busOptions.length} bus options with total trip time...`);

            optimizationResult = findOptimalBus(userLat, userLng, busOptions, stops, locations);

            if (optimizationResult) {
                // Find the matching route for the optimal bus
                const optimalRoute = matchedRoutes.find(r => r.vehicleId === optimizationResult!.bestBus.vehicleId);
                if (optimalRoute) {
                    bestRoute = optimalRoute;
                    trace(context, 'OPTIMIZATION_RESULT', `Best: ${bestRoute.vehicleName} - Total ${optimizationResult.totalTripTime} min (walk ${optimizationResult.walkToStop.walkTimeMinutes}, wait ${optimizationResult.waitAtStop}, travel ${optimizationResult.travelTime})`);
                }
            }
        } else {
            // No GPS - just sort by ETA
            matchedRoutes.sort((a, b) => a.etaMinutes - b.etaMinutes);
            bestRoute = matchedRoutes[0];
        }

        // Store enhanced route data
        const enhancedBestRoute = {
            ...bestRoute,
            // Add optimization data if available
            walkTimeMinutes: optimizationResult?.walkToStop.walkTimeMinutes,
            walkDistanceMeters: optimizationResult?.walkToStop.distanceMeters,
            waitTimeMinutes: optimizationResult?.waitAtStop,
            totalTripMinutes: optimizationResult?.totalTripTime,
            shouldLeaveIn: optimizationResult?.shouldLeaveIn,
            willMissBus: optimizationResult?.willMissBus,
            recommendation: optimizationResult?.recommendation,
            alternatives: optimizationResult?.alternatives
        };

        setData(context, 'best_route', enhancedBestRoute);
        setData(context, 'alternatives', matchedRoutes.filter(r => r.vehicleId !== bestRoute.vehicleId).slice(0, 2));

        // Build message
        let message = `Found route via ${bestRoute.vehicleName}`;
        if (optimizationResult) {
            message = `${bestRoute.vehicleName}: Walk ${optimizationResult.walkToStop.distanceMeters}m → Wait ${optimizationResult.waitAtStop} min → Travel ${optimizationResult.travelTime} min = Total ${optimizationResult.totalTripTime} min`;
        }

        return {
            success: true,
            agentName: 'PathAgent',
            data: {
                best_route: enhancedBestRoute,
                alternatives: matchedRoutes.filter(r => r.vehicleId !== bestRoute.vehicleId).slice(0, 2),
                route_found: true,
                optimization_used: !!optimizationResult
            },
            message,
            confidence: 0.9,
            toolsUsed: ['compute_route', 'find_optimal_bus'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

const TrackerAgent: Agent = {
    name: 'TrackerAgent',
    description: 'Tracks bus position with real-time context',
    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'TrackerAgent: Tracking bus with enhanced context');

        const targetBusId = input.parameters.bus_id as string;
        const targetBusName = input.parameters.vehicle_name as string;

        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];
        const locations = getData<LiveLocation[]>(context, 'live_locations') || [];

        let liveLocation: LiveLocation | undefined;

        // Find by ID first
        if (targetBusId) {
            liveLocation = locations.find(l => l.bus_id === targetBusId);
        }
        // Then try by name
        if (!liveLocation && targetBusName) {
            liveLocation = locations.find(loc => {
                const v = vehicles.find(v => v.id === loc.bus_id);
                return v?.name.toLowerCase().includes(targetBusName.toLowerCase());
            });
        }

        if (!liveLocation) {
            // Provide helpful error with list of active buses
            const fleetStatus = getData<FleetStatus>(context, 'fleet_status');
            const activeBusNames = fleetStatus?.active_bus_list.slice(0, 5).map(b => b.bus_name).join(', ') || 'none';
            return {
                success: false,
                agentName: 'TrackerAgent',
                data: { active_buses: activeBusNames },
                error: `Bus "${targetBusName || targetBusId}" is not currently broadcasting. Active buses: ${activeBusNames}`,
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        const vehicle = vehicles.find(v => v.id === liveLocation!.bus_id);
        const routeStops = stops.filter(s => s.vehicle_id === liveLocation!.bus_id).sort((a, b) => a.stop_order - b.stop_order);

        // Find nearest stop and its position on route
        let nearestStop = routeStops[0];
        let nearestStopOrder = 0;
        let minDist = Infinity;
        for (const s of routeStops) {
            const d = haversineDistance(liveLocation.latitude, liveLocation.longitude, s.latitude, s.longitude);
            if (d < minDist) {
                minDist = d;
                nearestStop = s;
                nearestStopOrder = s.stop_order;
            }
        }

        // Get stops ahead (remaining on route)
        const stopsAhead = routeStops
            .filter(s => s.stop_order > nearestStopOrder)
            .slice(0, 5)
            .map(s => s.name);

        // Calculate update age
        const ageSeconds = Math.floor((Date.now() - new Date(liveLocation.timestamp).getTime()) / 1000);
        const isMoving = (liveLocation.speed || 0) > 2;
        const status = isMoving ? `moving at ${liveLocation.speed} km/h` : 'stopped';

        return {
            success: true,
            agentName: 'TrackerAgent',
            data: {
                bus_id: liveLocation.bus_id,
                bus_name: vehicle?.name,
                vehicle_number: vehicle?.vehicle_number,
                latitude: liveLocation.latitude,
                longitude: liveLocation.longitude,
                speed: liveLocation.speed || 0,
                heading: liveLocation.heading,
                is_moving: isMoving,
                nearest_stop: nearestStop?.name || 'Unknown',
                distance_to_stop_km: Math.round(minDist * 100) / 100,
                stops_ahead: stopsAhead,
                last_update: liveLocation.timestamp,
                age_seconds: ageSeconds,
                is_fresh: ageSeconds < 120
            },
            message: `${vehicle?.name} (${vehicle?.vehicle_number}) is ${status} near ${nearestStop?.name}. Updated ${ageSeconds}s ago. ${stopsAhead.length} stops ahead.`,
            confidence: ageSeconds < 120 ? 0.95 : 0.7,
            toolsUsed: ['haversine', 'bus_tracking'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

const TimeAgent: Agent = {
    name: 'TimeAgent',
    description: 'Calculates ETA',
    async execute(context: AgentContext, _input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'TimeAgent: Calculating ETA');

        const bestRoute = getData<Record<string, unknown>>(context, 'best_route');
        if (!bestRoute) {
            return { success: false, agentName: 'TimeAgent', data: {}, error: 'No route data', confidence: 0, toolsUsed: [], executionTimeMs: Date.now() - startTime };
        }

        const eta = calculateETA(
            bestRoute.distanceKm as number,
            bestRoute.numStops as number,
            undefined
        );

        return {
            success: true,
            agentName: 'TimeAgent',
            data: {
                eta_minutes: eta.etaMinutes,
                eta_range_min: Math.round(eta.etaMinutes * 0.85),
                eta_range_max: Math.round(eta.etaMinutes * 1.25),
                traffic_factor: eta.trafficFactor
            },
            message: `ETA: ${Math.round(eta.etaMinutes * 0.85)}-${Math.round(eta.etaMinutes * 1.25)} min`,
            confidence: 0.8,
            toolsUsed: ['calculate_eta'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

const AnalyticsAgent: Agent = {
    name: 'AnalyticsAgent',
    description: 'Fleet analytics',
    async execute(context: AgentContext, _input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'AnalyticsAgent: Analyzing fleet');

        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const locations = getData<LiveLocation[]>(context, 'live_locations') || [];
        const now = Date.now();
        const fresh = locations.filter(l => (now - new Date(l.timestamp).getTime()) < 120000);

        return {
            success: true,
            agentName: 'AnalyticsAgent',
            data: { total_vehicles: vehicles.length, live_count: locations.length, fresh_count: fresh.length },
            message: `Fleet: ${fresh.length} active, ${vehicles.length - locations.length} offline`,
            confidence: 0.95,
            toolsUsed: [],
            executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// USER CONTEXT AGENT - Handles user location and context queries
// ============================================================================

const UserContextAgent: Agent = {
    name: 'UserContextAgent',
    description: 'Handles user location, context, and nearby information',
    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'UserContextAgent: Processing user context');

        const userLocation = context.userLocation;
        const vehicles = getData<Vehicle[]>(context, 'vehicles') || [];
        const stops = getData<RouteStop[]>(context, 'stops') || [];
        const liveLocations = getData<LiveLocation[]>(context, 'live_locations') || [];

        // Check if we have user's GPS
        if (!userLocation) {
            return {
                success: false,
                agentName: 'UserContextAgent',
                data: { has_gps: false },
                error: 'User location not available. Please enable GPS or share your location.',
                message: 'GPS location required for this query',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }

        // Find nearby stops (within 2km)
        const nearbyStops: Array<{ name: string; distanceKm: number; vehicleName: string }> = [];
        const vehicleMap = new Map(vehicles.map(v => [v.id, v]));

        for (const stop of stops) {
            const dist = haversineDistance(userLocation.latitude, userLocation.longitude, stop.latitude, stop.longitude);
            if (dist <= 2.0) {
                nearbyStops.push({
                    name: stop.name,
                    distanceKm: Math.round(dist * 1000) / 1000,
                    vehicleName: vehicleMap.get(stop.vehicle_id)?.name || 'Unknown'
                });
            }
        }
        nearbyStops.sort((a, b) => a.distanceKm - b.distanceKm);

        // Find nearby live buses (within 3km)
        const nearbyBuses: Array<{ busName: string; distanceKm: number; speed: number; isMoving: boolean }> = [];
        const now = Date.now();

        for (const loc of liveLocations) {
            // Only consider fresh locations (within 2 minutes)
            const locAge = now - new Date(loc.timestamp).getTime();
            if (locAge > 120000) continue;

            const dist = haversineDistance(userLocation.latitude, userLocation.longitude, loc.latitude, loc.longitude);
            if (dist <= 3.0) {
                const vehicle = vehicleMap.get(loc.bus_id);
                nearbyBuses.push({
                    busName: vehicle?.name || loc.bus_id,
                    distanceKm: Math.round(dist * 100) / 100,
                    speed: loc.speed || 0,
                    isMoving: (loc.speed || 0) > 2
                });
            }
        }
        nearbyBuses.sort((a, b) => a.distanceKm - b.distanceKm);

        // Store in context for other agents
        setData(context, 'user_nearby_stops', nearbyStops.slice(0, 5));
        setData(context, 'user_nearby_buses', nearbyBuses.slice(0, 5));
        setData(context, 'user_gps_available', true);

        return {
            success: true,
            agentName: 'UserContextAgent',
            data: {
                has_gps: true,
                user_lat: userLocation.latitude,
                user_lng: userLocation.longitude,
                nearby_stops_count: nearbyStops.length,
                nearby_buses_count: nearbyBuses.length,
                closest_stop: nearbyStops[0]?.name || 'None within 2km',
                closest_stop_distance: nearbyStops[0]?.distanceKm || null,
                closest_bus: nearbyBuses[0]?.busName || 'None within 3km',
                closest_bus_distance: nearbyBuses[0]?.distanceKm || null,
                nearby_stops: nearbyStops.slice(0, 3),
                nearby_buses: nearbyBuses.slice(0, 3)
            },
            message: `Found ${nearbyStops.length} stops and ${nearbyBuses.length} live buses near you`,
            confidence: 0.95,
            toolsUsed: ['haversine', 'gps_context'],
            executionTimeMs: Date.now() - startTime
        };
    }
};

// ============================================================================
// MEMORY AGENT - RAG Context Retrieval
// ============================================================================

const EMBEDDING_DIMENSION = 1536;

/**
 * Generate a deterministic hash-based embedding for RAG
 * (Falls back when OpenAI key is not available)
 */
function generateHashEmbedding(text: string): number[] {
    const embedding: number[] = new Array(EMBEDDING_DIMENSION).fill(0);
    const normalized = text.toLowerCase().trim();

    for (let i = 0; i < normalized.length; i++) {
        const charCode = normalized.charCodeAt(i);
        const idx = (i * 7 + charCode) % EMBEDDING_DIMENSION;
        embedding[idx] += Math.sin(charCode * (i + 1)) * 0.1;
    }

    for (let i = 0; i < normalized.length - 1; i++) {
        const bigram = normalized.substring(i, i + 2);
        const hash = (bigram.charCodeAt(0) * 31 + bigram.charCodeAt(1)) % EMBEDDING_DIMENSION;
        embedding[hash] += 0.05;
    }

    const magnitude = Math.sqrt(embedding.reduce((sum, val) => sum + val * val, 0));
    if (magnitude > 0) {
        for (let i = 0; i < EMBEDDING_DIMENSION; i++) {
            embedding[i] /= magnitude;
        }
    }

    return embedding;
}

/**
 * Generate embedding using OpenAI API if available
 */
async function generateEmbedding(text: string): Promise<number[]> {
    const openaiKey = Deno.env.get('OPENAI_API_KEY');

    if (openaiKey) {
        try {
            const response = await fetch('https://api.openai.com/v1/embeddings', {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${openaiKey}`,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    model: 'text-embedding-3-small',
                    input: text
                })
            });

            if (response.ok) {
                const data = await response.json();
                return data.data[0].embedding;
            }
        } catch (error) {
            console.warn('OpenAI embedding failed, using fallback:', error);
        }
    }

    return generateHashEmbedding(text);
}

interface MemoryContext {
    similarQueries: Array<{
        query: string;
        intent: string;
        response: string;
        similarity: number;
    }>;
    knowledgeContext: Array<{
        content: string;
        source: string;
        similarity: number;
    }>;
    conversationHistory: Array<{
        role: string;
        content: string;
    }>;
}

const MemoryAgent: Agent = {
    name: 'MemoryAgent',
    description: 'Retrieves relevant context from memory using RAG',
    async execute(context: AgentContext, input: AgentInput): Promise<AgentOutput> {
        const startTime = Date.now();
        trace(context, 'AGENT_STARTED', 'MemoryAgent: Retrieving context via RAG');

        const query = context.originalQuery;
        const userId = context.userId;

        try {
            const client = getSupabase();

            // Generate embedding for the query
            const queryEmbedding = await generateEmbedding(query);
            const embeddingStr = `[${queryEmbedding.join(',')}]`;

            // Retrieve similar past queries
            let similarQueries: MemoryContext['similarQueries'] = [];
            try {
                const { data: historyData } = await client.rpc('find_similar_queries', {
                    query_embedding: embeddingStr,
                    target_user_id: userId,
                    match_count: 3
                });

                if (historyData) {
                    similarQueries = historyData.map((row: any) => ({
                        query: row.query,
                        intent: row.intent,
                        response: row.response,
                        similarity: row.similarity
                    }));
                }
            } catch (e) {
                console.warn('Similar queries lookup failed:', e);
            }

            // Retrieve relevant knowledge
            let knowledgeContext: MemoryContext['knowledgeContext'] = [];
            try {
                const { data: knowledgeData } = await client.rpc('match_knowledge', {
                    query_embedding: embeddingStr,
                    match_threshold: 0.6,
                    match_count: 3,
                    filter_namespace: null
                });

                if (knowledgeData) {
                    knowledgeContext = knowledgeData.map((row: any) => ({
                        content: row.content,
                        source: row.source || 'knowledge_base',
                        similarity: row.similarity
                    }));
                }
            } catch (e) {
                console.warn('Knowledge lookup failed:', e);
            }

            // Get recent conversation history
            let conversationHistory: MemoryContext['conversationHistory'] = [];
            try {
                const { data: convData } = await client
                    .from('conversation_memory')
                    .select('role, content')
                    .eq('user_id', userId)
                    .order('created_at', { ascending: false })
                    .limit(5);

                if (convData) {
                    conversationHistory = convData.reverse();
                }
            } catch (e) {
                console.warn('Conversation history lookup failed:', e);
            }

            // Store context for other agents
            const memoryContext: MemoryContext = {
                similarQueries,
                knowledgeContext,
                conversationHistory
            };

            setData(context, 'memory_context', memoryContext);
            setData(context, 'query_embedding', queryEmbedding);

            const totalContext = similarQueries.length + knowledgeContext.length + conversationHistory.length;

            return {
                success: true,
                agentName: 'MemoryAgent',
                data: {
                    similar_queries_count: similarQueries.length,
                    knowledge_count: knowledgeContext.length,
                    conversation_count: conversationHistory.length,
                    has_relevant_context: totalContext > 0
                },
                message: `Retrieved ${totalContext} context items via RAG`,
                confidence: totalContext > 0 ? 0.8 : 0.5,
                toolsUsed: ['vector_search', 'embedding_generation'],
                executionTimeMs: Date.now() - startTime
            };
        } catch (error) {
            console.error('MemoryAgent error:', error);
            return {
                success: false,
                agentName: 'MemoryAgent',
                data: {},
                error: error instanceof Error ? error.message : 'Memory retrieval failed',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: Date.now() - startTime
            };
        }
    }
};

// Agent registry
const AGENTS: Record<string, Agent> = {
    DataAgent,
    AlgorithmAgent,
    PathAgent,
    TrackerAgent,
    TimeAgent,
    AnalyticsAgent,
    UserContextAgent,
    MemoryAgent
};

// ============================================================================
// CORS
// ============================================================================

const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS'
};

// ============================================================================
// MAIN HANDLER
// ============================================================================

serve(async (req: Request) => {
    if (req.method === 'OPTIONS') {
        return new Response('ok', { headers: corsHeaders });
    }

    if (req.method !== 'POST') {
        return new Response(JSON.stringify({ error: 'Method not allowed' }), { status: 405, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    try {
        const request: ProcessQueryRequest = await req.json();
        const { userId, query, userLocation, useLLM = true } = request;

        if (!userId || !query) {
            return new Response(JSON.stringify({ error: 'userId and query required' }), { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        console.log(`[ORCHESTRATOR] Processing: "${query}" | LLM: ${useLLM}`);

        const context = createContext(userId, query, userLocation);
        const llmUsage = { intentAnalysis: false, resultSynthesis: false };

        // Step 1: Analyze Intent (LLM or fallback)
        trace(context, 'LLM_CALL', 'Analyzing intent...');
        let analyzedIntent: AnalyzedIntent;

        if (useLLM) {
            analyzedIntent = await analyzeIntent(query, !!userLocation);
            llmUsage.intentAnalysis = analyzedIntent.reasoning.startsWith('[LLM]');
        } else {
            analyzedIntent = fallbackIntentAnalysis(query);
        }

        trace(context, 'INTENT_DETECTED', `Intent: ${analyzedIntent.intent} (${Math.round(analyzedIntent.confidence * 100)}%)`);

        // Step 2: Handle special intents
        if (analyzedIntent.intent === 'GENERAL') {
            return new Response(JSON.stringify({
                success: true,
                response: '👋 Hello! I can help you find bus routes, track buses, or get ETAs. Where would you like to go?',
                intent: 'GENERAL',
                confidence: 1.0,
                agentTrace: context.executionTrace,
                executionTimeMs: Date.now() - context.startTime,
                llmUsage
            } as ProcessQueryResponse), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        // Handle USER_LOCATION intent - user asking about their location
        if (analyzedIntent.intent === 'USER_LOCATION') {
            // First run DataAgent to get all data
            const dataResult = await DataAgent.execute(context, { intent: 'USER_LOCATION', parameters: {} });
            context.agentResults.set('DataAgent', dataResult);

            // Then run UserContextAgent
            const userContextResult = await UserContextAgent.execute(context, { intent: 'USER_LOCATION', parameters: {} });
            context.agentResults.set('UserContextAgent', userContextResult);

            let response: string;
            if (userContextResult.success) {
                const data = userContextResult.data;
                const nearbyStops = data.nearby_stops as Array<{ name: string; distanceKm: number }> || [];
                const nearbyBuses = data.nearby_buses as Array<{ busName: string; distanceKm: number }> || [];

                let parts: string[] = [];
                parts.push(`📍 **Your Location**: ${(data.user_lat as number)?.toFixed(5)}, ${(data.user_lng as number)?.toFixed(5)}`);

                if (nearbyStops.length > 0) {
                    parts.push(`🚏 **Nearest Stop**: ${nearbyStops[0].name} (${(nearbyStops[0].distanceKm * 1000).toFixed(0)}m)`);
                }
                if (nearbyBuses.length > 0) {
                    parts.push(`🚌 **Nearby Buses**: ${nearbyBuses.map(b => b.busName).join(', ')}`);
                }

                response = parts.join('\n');
            } else {
                response = '❌ Could not determine your location. Please enable GPS and share your location.';
            }

            return new Response(JSON.stringify({
                success: userContextResult.success,
                response,
                intent: 'USER_LOCATION',
                confidence: userContextResult.confidence,
                routeData: userContextResult.data,
                agentTrace: context.executionTrace,
                executionTimeMs: Date.now() - context.startTime,
                llmUsage
            } as ProcessQueryResponse), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        // Step 3: Execute agents
        const agentInput: AgentInput = {
            intent: analyzedIntent.intent,
            parameters: {
                origin: analyzedIntent.entities.origin,
                destination: analyzedIntent.entities.destination,
                bus_id: analyzedIntent.entities.busId,
                vehicle_name: analyzedIntent.entities.busName,
                user_lat: userLocation?.latitude,
                user_lng: userLocation?.longitude
            }
        };

        // Always run DataAgent first
        const dataResult = await DataAgent.execute(context, agentInput);
        context.agentResults.set('DataAgent', dataResult);

        if (!dataResult.success) {
            return new Response(JSON.stringify({
                success: false,
                response: 'Could not fetch data. Please try again.',
                intent: analyzedIntent.intent,
                confidence: 0,
                agentTrace: context.executionTrace,
                executionTimeMs: Date.now() - context.startTime,
                llmUsage
            } as ProcessQueryResponse), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        // ====================================================================
        // LOCATION-AWARE ANALYSIS: Run UserContextAgent if GPS is available
        // This provides proximity-based context to ALL subsequent agents
        // ====================================================================
        if (userLocation) {
            trace(context, 'LOCATION_CONTEXT', `User GPS: ${userLocation.latitude.toFixed(5)}, ${userLocation.longitude.toFixed(5)}`);
            const userContextResult = await UserContextAgent.execute(context, agentInput);
            context.agentResults.set('UserContextAgent', userContextResult);

            if (userContextResult.success) {
                const nearbyData = userContextResult.data;
                trace(context, 'LOCATION_ANALYSIS', `Nearby: ${nearbyData.nearby_stops_count} stops, ${nearbyData.nearby_buses_count} live buses`);

                // Store proximity data for AI analysis
                setData(context, 'user_proximity_context', {
                    hasGPS: true,
                    userLat: userLocation.latitude,
                    userLng: userLocation.longitude,
                    nearestStop: nearbyData.closest_stop,
                    nearestStopDistance: nearbyData.closest_stop_distance,
                    nearbyBuses: nearbyData.nearby_buses,
                    nearbyStops: nearbyData.nearby_stops
                });
            }
        } else {
            trace(context, 'LOCATION_CONTEXT', 'No GPS available - using stop-based routing only');
            setData(context, 'user_proximity_context', { hasGPS: false });
        }

        // ====================================================================
        // MEMORY AGENT: Retrieve relevant context via RAG before processing
        // This provides historical context and knowledge to improve accuracy
        // ====================================================================
        trace(context, 'MEMORY_PIPELINE', 'Running MemoryAgent for RAG context retrieval');
        const memoryResult = await MemoryAgent.execute(context, agentInput);
        context.agentResults.set('MemoryAgent', memoryResult);

        if (memoryResult.success) {
            const memData = memoryResult.data;
            trace(context, 'MEMORY_CONTEXT', `Retrieved: ${memData.similar_queries_count} similar queries, ${memData.knowledge_count} knowledge items`);

            // If we found similar past queries, use them to improve response
            const memoryContext = getData<MemoryContext>(context, 'memory_context');
            if (memoryContext && memoryContext.similarQueries.length > 0) {
                const bestMatch = memoryContext.similarQueries[0];
                if (bestMatch.similarity > 0.85) {
                    trace(context, 'MEMORY_HINT', `High similarity (${(bestMatch.similarity * 100).toFixed(0)}%) to past query: "${bestMatch.query}"`);
                }
            }
        }

        // ====================================================================
        // ALGORITHM AGENT: Run OR algorithms before domain-specific agents
        // This computes KNN, TSP, Fixed Route Matching, MCDA and shares results
        // ====================================================================
        trace(context, 'ALGORITHM_PIPELINE', 'Running AlgorithmAgent to compute OR algorithms');
        const algorithmResult = await AlgorithmAgent.execute(context, agentInput);
        context.agentResults.set('AlgorithmAgent', algorithmResult);

        if (algorithmResult.success) {
            trace(context, 'ALGORITHM_COMPLETE', `AlgorithmAgent: ${algorithmResult.message}`);
        }

        // Run other agents based on intent (now with algorithm results available)
        const otherAgents = analyzedIntent.requiredAgents
            .filter(name => name !== 'DataAgent' && name !== 'UserContextAgent' && name !== 'AlgorithmAgent' && AGENTS[name])
            .map(name => AGENTS[name]);

        // Sequential execution for proper data flow between agents
        trace(context, 'AGENT_PIPELINE', `Running ${otherAgents.length} agents sequentially for data flow`);
        for (const agent of otherAgents) {
            const result = await agent.execute(context, agentInput);
            context.agentResults.set(agent.name, result);
            trace(context, 'AGENT_COMPLETE', `${agent.name}: ${result.success ? 'success' : 'failed'}`);
        }

        // Step 4: Get results
        const routeData = getData<Record<string, unknown>>(context, 'best_route');
        const hasSuccessfulAgent = Array.from(context.agentResults.values()).some(r => r.success);

        // ====================================================================
        // TRUE AI PIPELINE - Sequential Agent Analysis & Reasoning
        // ====================================================================

        trace(context, 'AI_PIPELINE_START', 'Beginning true AI analysis...');
        llmUsage.resultSynthesis = true;

        // Step 5a: Analyze each agent's output with LLM
        const agentAnalyses: AgentAnalysis[] = [];

        for (const [agentName, agentOutput] of context.agentResults) {
            trace(context, 'AI_ANALYSIS', `Analyzing ${agentName} output...`);
            const analysis = await analyzeAgentOutput(agentName, agentOutput, query);
            agentAnalyses.push(analysis);
            trace(context, 'AI_ANALYSIS_DONE', `${agentName}: ${analysis.evaluation} (${analysis.keyFindings.length} findings)`);
        }

        // Step 5b: Perform multi-agent reasoning with LLM (with location context)
        trace(context, 'AI_REASONING', 'Performing cross-agent reasoning with location context...');

        // Include user proximity context in reasoning
        const proximityContext = getData<Record<string, unknown>>(context, 'user_proximity_context');
        const enrichedRouteData = routeData ? {
            ...routeData,
            userProximity: proximityContext
        } : proximityContext ? { userProximity: proximityContext } : undefined;

        const reasoning = await performAIReasoning(query, agentAnalyses, enrichedRouteData);
        trace(context, 'AI_REASONING_DONE', `Confidence: ${Math.round(reasoning.confidence * 100)}%`);

        // Step 5c: Generate final response with LLM (no hardcoded responses!)
        trace(context, 'AI_RESPONSE', 'Generating natural language response...');
        const response = await generateAIResponse(query, reasoning, routeData);
        trace(context, 'AI_RESPONSE_DONE', 'Response generated');

        // Add AI reasoning to response for transparency
        const aiReasoning = {
            analyses: agentAnalyses.map(a => ({
                agent: a.agentName,
                evaluation: a.evaluation,
                findings: a.keyFindings
            })),
            reasoning: reasoning.reasoningSteps,
            mainFinding: reasoning.mainFinding,
            confidence: reasoning.confidence
        };

        // Step 7: Save search history WITH EMBEDDING for future RAG retrieval
        const queryEmbedding = getData<number[]>(context, 'query_embedding');
        const embeddingStr = queryEmbedding ? `[${queryEmbedding.join(',')}]` : null;

        try {
            // Save to search_history with embedding
            await getSupabase().from('search_history').insert({
                user_id: userId,
                query,
                query_embedding: embeddingStr,
                intent: analyzedIntent.intent,
                origin_name: analyzedIntent.entities.origin,
                destination_name: analyzedIntent.entities.destination,
                response,
                success: hasSuccessfulAgent,
                confidence: reasoning.confidence,
                execution_time_ms: Date.now() - context.startTime,
                user_location: userLocation ? {
                    latitude: userLocation.latitude,
                    longitude: userLocation.longitude
                } : null
            });

            // Also save to conversation_memory for multi-turn context
            const sessionId = crypto.randomUUID();
            await getSupabase().from('conversation_memory').insert([
                {
                    user_id: userId,
                    session_id: sessionId,
                    role: 'user',
                    content: query,
                    embedding: embeddingStr
                },
                {
                    user_id: userId,
                    session_id: sessionId,
                    role: 'assistant',
                    content: response,
                    embedding: null  // Could generate embedding for response too
                }
            ]);

            trace(context, 'MEMORY_SAVED', 'Saved query and response to memory');
        } catch (e) {
            console.error('Failed to save history:', e);
        }

        return new Response(JSON.stringify({
            success: hasSuccessfulAgent,
            response,
            intent: analyzedIntent.intent,
            confidence: reasoning.confidence,  // Use AI reasoning confidence
            routeData,
            agentTrace: context.executionTrace,
            aiReasoning,  // Include AI reasoning chain for transparency
            executionTimeMs: Date.now() - context.startTime,
            llmUsage
        } as ProcessQueryResponse), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });

    } catch (error) {
        console.error('Error:', error);
        return new Response(JSON.stringify({
            success: false,
            response: 'Something went wrong. Please try again.',
            intent: 'error',
            confidence: 0,
            agentTrace: [],
            executionTimeMs: 0,
            error: error instanceof Error ? error.message : 'Unknown error'
        } as ProcessQueryResponse), { status: 500, headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }
});
