/**
 * LLM Client for Multi-Agent System
 * 
 * Provides LLM integration using Groq API for:
 * - Intent analysis (orchestrator)
 * - Result validation (analyzer)
 * - Response synthesis (synthesizer)
 */

// ============================================================================
// TYPES
// ============================================================================

export interface LLMMessage {
    role: 'system' | 'user' | 'assistant';
    content: string;
}

export interface LLMOptions {
    model?: string;
    temperature?: number;
    maxTokens?: number;
    responseFormat?: 'text' | 'json';
}

export interface LLMResponse {
    content: string;
    model: string;
    usage: {
        promptTokens: number;
        completionTokens: number;
        totalTokens: number;
    };
    latencyMs: number;
}

export interface LLMError {
    code: string;
    message: string;
    retryable: boolean;
}

// ============================================================================
// CONFIGURATION
// ============================================================================

const GROQ_API_URL = 'https://api.groq.com/openai/v1/chat/completions';

// Available Groq models (fast to slow, capability ascending)
export const MODELS = {
    FAST: 'llama-3.1-8b-instant',           // ~500 tokens/sec, good for routing
    BALANCED: 'llama-3.3-70b-versatile',    // ~250 tokens/sec, good for synthesis
    QUALITY: 'llama-3.3-70b-specdec',       // Best quality, speculative decoding
} as const;

// Default configuration
const DEFAULT_CONFIG: Required<LLMOptions> = {
    model: MODELS.BALANCED,
    temperature: 0.1,      // Low for consistent routing decisions
    maxTokens: 500,
    responseFormat: 'text'
};

// ============================================================================
// MAIN LLM CLIENT
// ============================================================================

/**
 * Call the Groq LLM API with error handling and retry logic.
 */
export async function callLLM(
    systemPrompt: string,
    userMessage: string,
    options: LLMOptions = {}
): Promise<LLMResponse> {
    const startTime = Date.now();
    const config = { ...DEFAULT_CONFIG, ...options };

    const apiKey = Deno.env.get('GROQ_API_KEY');
    if (!apiKey) {
        throw new Error('GROQ_API_KEY environment variable is not set');
    }

    const messages: LLMMessage[] = [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userMessage }
    ];

    const requestBody: Record<string, unknown> = {
        model: config.model,
        messages,
        temperature: config.temperature,
        max_tokens: config.maxTokens,
    };

    // Add JSON mode if requested
    if (config.responseFormat === 'json') {
        requestBody.response_format = { type: 'json_object' };
    }

    try {
        const response = await fetch(GROQ_API_URL, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${apiKey}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(requestBody)
        });

        if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            const error: LLMError = {
                code: `HTTP_${response.status}`,
                message: errorData.error?.message || response.statusText,
                retryable: response.status >= 500 || response.status === 429
            };
            throw error;
        }

        const data = await response.json();
        const latencyMs = Date.now() - startTime;

        return {
            content: data.choices[0]?.message?.content || '',
            model: data.model,
            usage: {
                promptTokens: data.usage?.prompt_tokens || 0,
                completionTokens: data.usage?.completion_tokens || 0,
                totalTokens: data.usage?.total_tokens || 0
            },
            latencyMs
        };

    } catch (error) {
        // Re-throw LLMError as-is
        if ((error as LLMError).code) {
            throw error;
        }
        // Wrap other errors
        throw {
            code: 'NETWORK_ERROR',
            message: error instanceof Error ? error.message : 'Unknown error',
            retryable: true
        } as LLMError;
    }
}

/**
 * Call LLM with automatic retry on transient failures.
 */
export async function callLLMWithRetry(
    systemPrompt: string,
    userMessage: string,
    options: LLMOptions = {},
    maxRetries: number = 2
): Promise<LLMResponse> {
    let lastError: LLMError | null = null;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            return await callLLM(systemPrompt, userMessage, options);
        } catch (error) {
            const llmError = error as LLMError;
            lastError = llmError;

            if (!llmError.retryable || attempt === maxRetries) {
                throw llmError;
            }

            // Exponential backoff: 200ms, 400ms, 800ms
            await new Promise(resolve => setTimeout(resolve, 200 * Math.pow(2, attempt)));
        }
    }

    throw lastError;
}

/**
 * Parse JSON from LLM response, with fallback for malformed JSON.
 */
export function parseJSONResponse<T>(content: string): T | null {
    try {
        // Try direct parse first
        return JSON.parse(content) as T;
    } catch {
        // Try to extract JSON from markdown code block
        const jsonMatch = content.match(/```(?:json)?\s*([\s\S]*?)```/);
        if (jsonMatch) {
            try {
                return JSON.parse(jsonMatch[1].trim()) as T;
            } catch {
                // Fall through
            }
        }

        // Try to find JSON object in response
        const objectMatch = content.match(/\{[\s\S]*\}/);
        if (objectMatch) {
            try {
                return JSON.parse(objectMatch[0]) as T;
            } catch {
                // Fall through
            }
        }

        return null;
    }
}

// ============================================================================
// SPECIALIZED LLM CALLS
// ============================================================================

/**
 * Fast LLM call for routing decisions (uses smaller model).
 */
export async function callLLMFast(
    systemPrompt: string,
    userMessage: string
): Promise<LLMResponse> {
    return callLLMWithRetry(systemPrompt, userMessage, {
        model: MODELS.FAST,
        temperature: 0.0,
        maxTokens: 300,
        responseFormat: 'json'
    });
}

/**
 * Quality LLM call for response synthesis (uses larger model).
 */
export async function callLLMQuality(
    systemPrompt: string,
    userMessage: string
): Promise<LLMResponse> {
    return callLLMWithRetry(systemPrompt, userMessage, {
        model: MODELS.BALANCED,
        temperature: 0.3,
        maxTokens: 600,
        responseFormat: 'text'
    });
}
