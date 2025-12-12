/**
 * Enhanced Agent Orchestrator with LLM Integration
 * 
 * The "Big Brain" that coordinates the multi-agent system:
 * - Uses LLM to analyze intent and route to appropriate agents
 * - Executes agents in optimal order (parallel when possible)
 * - Validates results and retries if needed
 * - Synthesizes final response
 */

import type {
    AgentContext,
    AgentInput,
    AgentOutput,
    TraceEvent,
    TraceEventType
} from './types.ts';
import { analyzeIntent, type AnalyzedIntent } from './intent-analyzer.ts';
import { synthesizeResponse, validateResults } from './result-synthesizer.ts';

// ============================================================================
// AGENT INTERFACE
// ============================================================================

export interface Agent {
    name: string;
    description: string;
    execute: (context: AgentContext, input: AgentInput) => Promise<AgentOutput>;
}

// ============================================================================
// CONTEXT HELPERS
// ============================================================================

export function createContext(
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

export function trace(
    context: AgentContext,
    type: TraceEventType,
    message: string,
    data?: Record<string, unknown>
): void {
    const event: TraceEvent = {
        type,
        message,
        data,
        timestamp: Date.now()
    };
    context.executionTrace.push(event);
    console.log(`[${type}] ${message}`);
}

export function setData(
    context: AgentContext,
    key: string,
    value: unknown
): void {
    context.sharedData.set(key, value);
    trace(context, 'DATA_STORED', `Stored: ${key}`);
}

export function getData<T>(context: AgentContext, key: string): T | undefined {
    return context.sharedData.get(key) as T | undefined;
}

export function storeAgentResult(
    context: AgentContext,
    agentName: string,
    output: AgentOutput
): void {
    context.agentResults.set(agentName, output);
    trace(context, 'AGENT_COMPLETED', `${agentName}: ${output.success ? 'success' : 'failed'}`);
}

export function getAgentResult(
    context: AgentContext,
    agentName: string
): AgentOutput | undefined {
    return context.agentResults.get(agentName);
}

// ============================================================================
// AGENT EXECUTION
// ============================================================================

/**
 * Execute agents sequentially, passing results between them.
 */
export async function executeSequential(
    agents: Agent[],
    context: AgentContext,
    input: AgentInput,
    stopOnFailure: boolean = true
): Promise<AgentOutput> {
    const startTime = Date.now();
    const results = new Map<string, AgentOutput>();
    let lastOutput: AgentOutput | null = null;

    trace(context, 'AGENT_STARTED', `Sequential: ${agents.map(a => a.name).join(' → ')}`);

    for (const agent of agents) {
        try {
            trace(context, 'AGENT_STARTED', `Starting ${agent.name}`);

            const agentInput: AgentInput = {
                ...input,
                previousResults: Object.fromEntries(results)
            };

            const output = await agent.execute(context, agentInput);
            results.set(agent.name, output);
            storeAgentResult(context, agent.name, output);
            lastOutput = output;

            if (!output.success && stopOnFailure) {
                trace(context, 'ERROR', `${agent.name} failed, stopping sequence`);
                break;
            }

        } catch (error) {
            const errorOutput: AgentOutput = {
                success: false,
                agentName: agent.name,
                data: {},
                error: error instanceof Error ? error.message : 'Unknown error',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: 0
            };
            results.set(agent.name, errorOutput);
            storeAgentResult(context, agent.name, errorOutput);

            if (stopOnFailure) {
                return {
                    success: false,
                    agentName: 'SequentialAgent',
                    data: { completedAgents: Array.from(results.keys()) },
                    error: `Failed at ${agent.name}: ${errorOutput.error}`,
                    confidence: 0,
                    toolsUsed: [],
                    executionTimeMs: Date.now() - startTime
                };
            }
        }
    }

    const successCount = Array.from(results.values()).filter(r => r.success).length;

    return {
        success: successCount > 0,
        agentName: 'SequentialAgent',
        data: {
            agentResults: Object.fromEntries(results),
            successCount,
            totalCount: agents.length
        },
        message: lastOutput?.message,
        confidence: successCount / agents.length,
        toolsUsed: Array.from(results.values()).flatMap(r => r.toolsUsed),
        executionTimeMs: Date.now() - startTime
    };
}

/**
 * Execute agents in parallel and collect all results.
 */
export async function executeParallel(
    agents: Agent[],
    context: AgentContext,
    input: AgentInput,
    timeoutMs: number = 25000
): Promise<AgentOutput> {
    const startTime = Date.now();

    trace(context, 'PARALLEL_START', `Parallel: ${agents.map(a => a.name).join(', ')}`);

    const results = new Map<string, AgentOutput>();

    try {
        const agentPromises = agents.map(async (agent) => {
            try {
                const output = await Promise.race([
                    agent.execute(context, input),
                    new Promise<AgentOutput>((_, reject) =>
                        setTimeout(() => reject(new Error('Timeout')), timeoutMs)
                    )
                ]);
                return { name: agent.name, output };
            } catch (error) {
                return {
                    name: agent.name,
                    output: {
                        success: false,
                        agentName: agent.name,
                        data: {},
                        error: error instanceof Error ? error.message : 'Unknown error',
                        confidence: 0,
                        toolsUsed: [],
                        executionTimeMs: 0
                    } as AgentOutput
                };
            }
        });

        const agentResults = await Promise.all(agentPromises);

        for (const { name, output } of agentResults) {
            results.set(name, output);
            storeAgentResult(context, name, output);
        }

    } catch (error) {
        trace(context, 'ERROR', `Parallel execution error: ${error}`);
    }

    trace(context, 'PARALLEL_END', `Completed ${results.size} agents`);

    const successCount = Array.from(results.values()).filter(r => r.success).length;

    return {
        success: successCount > 0,
        agentName: 'ParallelAgent',
        data: {
            agentResults: Object.fromEntries(results),
            successCount,
            totalCount: agents.length
        },
        confidence: successCount / agents.length,
        toolsUsed: Array.from(results.values()).flatMap(r => r.toolsUsed),
        executionTimeMs: Date.now() - startTime
    };
}

/**
 * Execute agent with retry logic.
 */
export async function executeWithRetry(
    agent: Agent,
    context: AgentContext,
    input: AgentInput,
    maxAttempts: number = 2,
    delayMs: number = 500
): Promise<AgentOutput> {
    const startTime = Date.now();
    let lastOutput: AgentOutput | null = null;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        trace(context, 'AGENT_STARTED', `${agent.name} attempt ${attempt}/${maxAttempts}`);

        try {
            const output = await agent.execute(context, input);
            lastOutput = output;

            if (output.success) {
                return {
                    ...output,
                    data: { ...output.data, attempts: attempt },
                    executionTimeMs: Date.now() - startTime
                };
            }

            if (attempt < maxAttempts) {
                trace(context, 'STATUS_UPDATE', `Retrying in ${delayMs}ms...`);
                await new Promise(resolve => setTimeout(resolve, delayMs));
            }

        } catch (error) {
            lastOutput = {
                success: false,
                agentName: agent.name,
                data: {},
                error: error instanceof Error ? error.message : 'Unknown error',
                confidence: 0,
                toolsUsed: [],
                executionTimeMs: 0
            };

            if (attempt < maxAttempts) {
                await new Promise(resolve => setTimeout(resolve, delayMs));
            }
        }
    }

    return {
        success: false,
        agentName: 'RetryAgent',
        data: { attempts: maxAttempts, lastResult: lastOutput?.data },
        error: `Max attempts (${maxAttempts}) exceeded`,
        confidence: 0,
        toolsUsed: lastOutput?.toolsUsed || [],
        executionTimeMs: Date.now() - startTime
    };
}

// ============================================================================
// LLM-POWERED ORCHESTRATION
// ============================================================================

export interface OrchestratorResult {
    success: boolean;
    response: string;
    intent: string;
    confidence: number;
    routeData?: Record<string, unknown>;
    agentTrace: TraceEvent[];
    executionTimeMs: number;
    llmUsage?: {
        intentAnalysis: boolean;
        resultSynthesis: boolean;
    };
}

export interface AgentRegistry {
    [key: string]: Agent;
}

/**
 * Main orchestrator function with LLM integration.
 * This is the "Big Brain" that coordinates everything.
 */
export async function orchestrate(
    userId: string,
    query: string,
    agentRegistry: AgentRegistry,
    userLocation?: { latitude: number; longitude: number },
    useLLM: boolean = true
): Promise<OrchestratorResult> {
    const context = createContext(userId, query, userLocation);

    trace(context, 'ORCHESTRATOR_START', `Processing: "${query}"`);
    trace(context, 'LLM_CALL', `LLM mode: ${useLLM ? 'enabled' : 'disabled'}`);

    let llmUsage = { intentAnalysis: false, resultSynthesis: false };
    let analyzedIntent: AnalyzedIntent;

    // Step 1: Analyze Intent
    try {
        if (useLLM) {
            trace(context, 'LLM_CALL', 'Analyzing intent with LLM...');
            analyzedIntent = await analyzeIntent(query, !!userLocation);
            llmUsage.intentAnalysis = true;
        } else {
            // Fallback to basic pattern matching
            analyzedIntent = await analyzeIntent(query, !!userLocation);
        }

        trace(context, 'INTENT_DETECTED', `Intent: ${analyzedIntent.intent} (${Math.round(analyzedIntent.confidence * 100)}%)`, {
            entities: analyzedIntent.entities,
            agents: analyzedIntent.requiredAgents
        });
    } catch (error) {
        trace(context, 'ERROR', `Intent analysis failed: ${error}`);
        return {
            success: false,
            response: 'I had trouble understanding your request. Could you rephrase it?',
            intent: 'ERROR',
            confidence: 0,
            agentTrace: context.executionTrace,
            executionTimeMs: Date.now() - context.startTime
        };
    }

    // Step 2: Handle special intents
    if (analyzedIntent.intent === 'GENERAL') {
        return {
            success: true,
            response: '👋 Hello! I can help you find bus routes, track buses, or get ETAs. Where would you like to go?',
            intent: 'GENERAL',
            confidence: 1.0,
            agentTrace: context.executionTrace,
            executionTimeMs: Date.now() - context.startTime,
            llmUsage
        };
    }

    if (analyzedIntent.intent === 'CLARIFICATION' && analyzedIntent.clarificationNeeded) {
        return {
            success: true,
            response: analyzedIntent.clarificationNeeded,
            intent: 'CLARIFICATION',
            confidence: 0.5,
            agentTrace: context.executionTrace,
            executionTimeMs: Date.now() - context.startTime,
            llmUsage
        };
    }

    // Step 3: Execute required agents
    const agentInput: AgentInput = {
        intent: analyzedIntent.intent,
        parameters: {
            origin: analyzedIntent.entities.origin,
            destination: analyzedIntent.entities.destination,
            bus_id: analyzedIntent.entities.busId,
            vehicle_name: analyzedIntent.entities.busName,
            stop_name: analyzedIntent.entities.stopName,
            user_lat: userLocation?.latitude,
            user_lng: userLocation?.longitude
        }
    };

    // Get agents to execute
    const agentsToRun: Agent[] = [];
    for (const agentName of analyzedIntent.requiredAgents) {
        if (agentRegistry[agentName]) {
            agentsToRun.push(agentRegistry[agentName]);
        } else {
            trace(context, 'WARNING', `Agent not found: ${agentName}`);
        }
    }

    if (agentsToRun.length === 0) {
        return {
            success: false,
            response: 'No agents available to handle your request.',
            intent: analyzedIntent.intent,
            confidence: 0,
            agentTrace: context.executionTrace,
            executionTimeMs: Date.now() - context.startTime,
            llmUsage
        };
    }

    // Execute agents based on mode
    let executionResult: AgentOutput;

    if (analyzedIntent.executionMode === 'parallel' && agentsToRun.length > 1) {
        // DataAgent must run first, then others in parallel
        const dataAgent = agentsToRun.find(a => a.name === 'DataAgent');
        const otherAgents = agentsToRun.filter(a => a.name !== 'DataAgent');

        if (dataAgent) {
            const dataResult = await dataAgent.execute(context, agentInput);
            storeAgentResult(context, dataAgent.name, dataResult);

            if (!dataResult.success) {
                return {
                    success: false,
                    response: 'Could not fetch data. Please try again.',
                    intent: analyzedIntent.intent,
                    confidence: 0,
                    agentTrace: context.executionTrace,
                    executionTimeMs: Date.now() - context.startTime,
                    llmUsage
                };
            }
        }

        // Run remaining agents in parallel
        if (otherAgents.length > 0) {
            executionResult = await executeParallel(otherAgents, context, agentInput);
        } else {
            executionResult = {
                success: true,
                agentName: 'Orchestrator',
                data: {},
                confidence: 1,
                toolsUsed: [],
                executionTimeMs: 0
            };
        }
    } else {
        // Sequential execution
        executionResult = await executeSequential(agentsToRun, context, agentInput, false);
    }

    // Step 4: Get route data if available
    const routeData = getData<Record<string, unknown>>(context, 'best_route');

    // Step 5: Validate and synthesize response
    let response: string;

    if (useLLM) {
        try {
            trace(context, 'LLM_CALL', 'Synthesizing response with LLM...');

            // Optional: Validate results
            const validation = await validateResults(query, analyzedIntent.intent, context.agentResults);
            if (!validation.isValid && validation.shouldRetryWithAgent) {
                trace(context, 'VALIDATION_FAILED', `Suggested retry with: ${validation.shouldRetryWithAgent}`);
            }

            // Synthesize response
            const synthesized = await synthesizeResponse(
                query,
                analyzedIntent.intent,
                context.agentResults,
                routeData
            );

            response = synthesized.message;
            llmUsage.resultSynthesis = true;

        } catch (error) {
            trace(context, 'ERROR', `LLM synthesis failed: ${error}`);
            response = generateFallbackResponse(analyzedIntent.intent, context.agentResults, routeData);
        }
    } else {
        response = generateFallbackResponse(analyzedIntent.intent, context.agentResults, routeData);
    }

    return {
        success: executionResult.success,
        response,
        intent: analyzedIntent.intent,
        confidence: analyzedIntent.confidence,
        routeData,
        agentTrace: context.executionTrace,
        executionTimeMs: Date.now() - context.startTime,
        llmUsage
    };
}

/**
 * Fallback response generation without LLM.
 */
function generateFallbackResponse(
    intent: string,
    agentResults: Map<string, AgentOutput>,
    routeData?: Record<string, unknown>
): string {
    if (routeData && intent === 'FIND_ROUTE') {
        const vehicle = routeData.vehicleName || 'Bus';
        const eta = routeData.etaMinutes || '?';
        const distance = routeData.distanceKm || '?';
        const isLive = routeData.isLive;
        const stopsAway = routeData.busStopsAway;

        let msg = `🚌 **${vehicle}** • ${distance} km • ~${eta} min`;
        if (isLive && stopsAway !== undefined && (stopsAway as number) >= 0) {
            msg += ` • Bus is ${stopsAway} stops away`;
        }
        return msg;
    }

    const trackerResult = agentResults.get('TrackerAgent');
    if (trackerResult?.success && intent === 'TRACK_BUS') {
        const data = trackerResult.data;
        return `📍 **${data.bus_name}** is near **${data.nearest_stop}**`;
    }

    const pathResult = agentResults.get('PathAgent');
    if (pathResult && !pathResult.success) {
        return '❌ No direct route found. Try checking for transfer options.';
    }

    return 'Request processed. Please check the results.';
}
