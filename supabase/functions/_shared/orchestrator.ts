/**
 * Agent Orchestrator for Multi-Agent System
 * 
 * Coordinates agent execution with:
 * - Sequential execution
 * - Parallel execution
 * - Error handling and fallbacks
 * - Full execution tracing
 */

import type {
    AgentContext,
    AgentInput,
    AgentOutput,
    TraceEvent,
    TraceEventType
} from './types.ts';

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
// WORKFLOW AGENTS
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

            // Build input with previous results
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
    timeoutMs: number = 30000
): Promise<AgentOutput> {
    const startTime = Date.now();

    trace(context, 'PARALLEL_START', `Parallel: ${agents.map(a => a.name).join(', ')}`);

    const results = new Map<string, AgentOutput>();

    try {
        // Create promise for each agent with timeout
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

        // Wait for all agents
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
    maxAttempts: number = 3,
    delayMs: number = 1000
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
