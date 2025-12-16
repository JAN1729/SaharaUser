/**
 * Agent Communication Hub
 * 
 * Enables real-time inter-agent messaging with:
 * - Typed message channels (DATA_READY, INTENT_ANALYZED, etc.)
 * - Pub/Sub pattern with priority queue
 * - Request-Response with timeout
 * - Full message tracing for debugging
 * 
 * Usage:
 *   const hub = createCommunicationHub();
 *   hub.subscribe(MessageType.DATA_READY, (msg) => { ... });
 *   hub.publish({ type: MessageType.DATA_READY, ... });
 */

// ============================================================================
// MESSAGE TYPES
// ============================================================================

export enum MessageType {
    // Data lifecycle
    DATA_READY = 'DATA_READY',
    DATA_UPDATED = 'DATA_UPDATED',

    // Intent analysis
    INTENT_ANALYZED = 'INTENT_ANALYZED',

    // Route finding
    ROUTE_FOUND = 'ROUTE_FOUND',
    ROUTE_NOT_FOUND = 'ROUTE_NOT_FOUND',

    // Bus tracking
    LOCATION_UPDATE = 'LOCATION_UPDATE',
    BUS_TRACKED = 'BUS_TRACKED',

    // Memory/context
    MEMORY_RETRIEVED = 'MEMORY_RETRIEVED',

    // Geocoding
    PLACE_GEOCODED = 'PLACE_GEOCODED',

    // Walking directions
    WALKING_DIRECTIONS_READY = 'WALKING_DIRECTIONS_READY',

    // Request-response pattern
    REQUEST = 'REQUEST',
    RESPONSE = 'RESPONSE',

    // Errors
    ERROR = 'ERROR',

    // Agent lifecycle
    AGENT_STARTED = 'AGENT_STARTED',
    AGENT_COMPLETED = 'AGENT_COMPLETED'
}

export enum MessagePriority {
    CRITICAL = 1,   // Errors, location updates
    HIGH = 2,       // Intent analysis, data ready
    NORMAL = 3,     // Route/tracker results
    LOW = 4         // Analytics, logging
}

// ============================================================================
// MESSAGE INTERFACES
// ============================================================================

export interface AgentMessage<T = unknown> {
    id: string;
    type: MessageType;
    source: string;           // Agent name that sent the message
    target?: string;          // Optional target agent (for direct messages)
    payload: T;
    priority: MessagePriority;
    timestamp: number;
    correlationId?: string;   // For request-response pairing
}

export type MessageHandler<T = unknown> = (message: AgentMessage<T>) => void | Promise<void>;

// ============================================================================
// TYPED PAYLOADS
// ============================================================================

export interface DataReadyPayload {
    vehiclesCount: number;
    stopsCount: number;
    liveLocationsCount: number;
}

export interface IntentAnalyzedPayload {
    primaryIntent: string;
    subIntents: string[];
    entities: Record<string, unknown>;
    confidence: number;
    reasoning: string;
}

export interface RouteFoundPayload {
    vehicleId: string;
    vehicleName: string;
    originStop: string;
    destinationStop: string;
    etaMinutes: number;
    alternativesCount: number;
}

export interface LocationUpdatePayload {
    busId: string;
    latitude: number;
    longitude: number;
    speed: number;
    nearestStop: string;
    ageSeconds: number;
}

export interface WalkingDirectionsPayload {
    destinationStop: string;
    distanceMeters: number;
    durationMinutes: number;
    steps: Array<{ instruction: string; distance: number }>;
    summary: string;
}

export interface RequestPayload {
    action: string;
    params: Record<string, unknown>;
}

export interface ResponsePayload<T = unknown> {
    success: boolean;
    data?: T;
    error?: string;
}

// ============================================================================
// COMMUNICATION HUB INTERFACE
// ============================================================================

export interface IAgentCommunicationHub {
    /** Publish a message to all subscribers of the message type */
    publish<T>(message: Omit<AgentMessage<T>, 'id' | 'timestamp'>): void;

    /** Subscribe to messages of a specific type. Returns unsubscribe function. */
    subscribe<T>(type: MessageType, handler: MessageHandler<T>): () => void;

    /** Subscribe to messages from a specific agent */
    subscribeToAgent<T>(agentName: string, handler: MessageHandler<T>): () => void;

    /** Send a request to a target agent and wait for response */
    request<TReq, TRes>(
        source: string,
        target: string,
        payload: TReq,
        timeoutMs?: number
    ): Promise<TRes>;

    /** Respond to a request message */
    respond<T>(originalMessage: AgentMessage, payload: ResponsePayload<T>): void;

    /** Get message history (for debugging) */
    getMessageHistory(type?: MessageType): AgentMessage[];

    /** Get pending messages count by priority */
    getPendingCount(): Record<MessagePriority, number>;

    /** Clear all subscriptions and history (call at end of request) */
    reset(): void;
}

// ============================================================================
// PRIORITY QUEUE IMPLEMENTATION
// ============================================================================

class PriorityMessageQueue {
    private messages: AgentMessage[] = [];

    enqueue(message: AgentMessage): void {
        // Insert in priority order (lower priority number = higher priority)
        let inserted = false;
        for (let i = 0; i < this.messages.length; i++) {
            if (message.priority < this.messages[i].priority) {
                this.messages.splice(i, 0, message);
                inserted = true;
                break;
            }
        }
        if (!inserted) {
            this.messages.push(message);
        }
    }

    dequeue(): AgentMessage | undefined {
        return this.messages.shift();
    }

    peek(): AgentMessage | undefined {
        return this.messages[0];
    }

    isEmpty(): boolean {
        return this.messages.length === 0;
    }

    getCountByPriority(): Record<MessagePriority, number> {
        const counts = {
            [MessagePriority.CRITICAL]: 0,
            [MessagePriority.HIGH]: 0,
            [MessagePriority.NORMAL]: 0,
            [MessagePriority.LOW]: 0
        };
        for (const msg of this.messages) {
            counts[msg.priority]++;
        }
        return counts;
    }

    clear(): void {
        this.messages = [];
    }
}

// ============================================================================
// AGENT COMMUNICATION HUB IMPLEMENTATION
// ============================================================================

class AgentCommunicationHub implements IAgentCommunicationHub {
    private subscriptions = new Map<MessageType, Set<MessageHandler>>();
    private agentSubscriptions = new Map<string, Set<MessageHandler>>();
    private messageHistory: AgentMessage[] = [];
    private pendingRequests = new Map<string, {
        resolve: (value: unknown) => void;
        reject: (reason: unknown) => void;
        timeout: number;
    }>();
    private queue = new PriorityMessageQueue();
    private isProcessing = false;

    // Default timeout for request-response (5 seconds as approved)
    private readonly REQUEST_TIMEOUT_MS = 5000;

    // ========================================================================
    // PUBLISH
    // ========================================================================

    publish<T>(message: Omit<AgentMessage<T>, 'id' | 'timestamp'>): void {
        const fullMessage: AgentMessage<T> = {
            ...message,
            id: crypto.randomUUID(),
            timestamp: Date.now()
        };

        // Store in history
        this.messageHistory.push(fullMessage as AgentMessage);

        // Log for debugging
        console.log(`[HUB] ${fullMessage.type} from ${fullMessage.source}${fullMessage.target ? ` → ${fullMessage.target}` : ''}`);

        // Add to priority queue
        this.queue.enqueue(fullMessage as AgentMessage);

        // Process queue
        this.processQueue();
    }

    private async processQueue(): Promise<void> {
        if (this.isProcessing) return;
        this.isProcessing = true;

        try {
            while (!this.queue.isEmpty()) {
                const message = this.queue.dequeue();
                if (!message) break;

                // Notify type subscribers
                const typeHandlers = this.subscriptions.get(message.type);
                if (typeHandlers) {
                    for (const handler of typeHandlers) {
                        try {
                            await handler(message);
                        } catch (error) {
                            console.error(`[HUB] Handler error for ${message.type}:`, error);
                        }
                    }
                }

                // Notify agent subscribers
                const agentHandlers = this.agentSubscriptions.get(message.source);
                if (agentHandlers) {
                    for (const handler of agentHandlers) {
                        try {
                            await handler(message);
                        } catch (error) {
                            console.error(`[HUB] Agent handler error for ${message.source}:`, error);
                        }
                    }
                }

                // Handle response messages for request-response pattern
                if (message.type === MessageType.RESPONSE && message.correlationId) {
                    const pending = this.pendingRequests.get(message.correlationId);
                    if (pending) {
                        clearTimeout(pending.timeout);
                        this.pendingRequests.delete(message.correlationId);
                        const payload = message.payload as ResponsePayload;
                        if (payload.success) {
                            pending.resolve(payload.data);
                        } else {
                            pending.reject(new Error(payload.error || 'Request failed'));
                        }
                    }
                }
            }
        } finally {
            this.isProcessing = false;
        }
    }

    // ========================================================================
    // SUBSCRIBE
    // ========================================================================

    subscribe<T>(type: MessageType, handler: MessageHandler<T>): () => void {
        if (!this.subscriptions.has(type)) {
            this.subscriptions.set(type, new Set());
        }
        this.subscriptions.get(type)!.add(handler as MessageHandler);

        // Return unsubscribe function
        return () => {
            const handlers = this.subscriptions.get(type);
            if (handlers) {
                handlers.delete(handler as MessageHandler);
            }
        };
    }

    subscribeToAgent<T>(agentName: string, handler: MessageHandler<T>): () => void {
        if (!this.agentSubscriptions.has(agentName)) {
            this.agentSubscriptions.set(agentName, new Set());
        }
        this.agentSubscriptions.get(agentName)!.add(handler as MessageHandler);

        // Return unsubscribe function
        return () => {
            const handlers = this.agentSubscriptions.get(agentName);
            if (handlers) {
                handlers.delete(handler as MessageHandler);
            }
        };
    }

    // ========================================================================
    // REQUEST-RESPONSE
    // ========================================================================

    request<TReq, TRes>(
        source: string,
        target: string,
        payload: TReq,
        timeoutMs: number = this.REQUEST_TIMEOUT_MS
    ): Promise<TRes> {
        return new Promise((resolve, reject) => {
            const correlationId = crypto.randomUUID();

            // Set timeout
            const timeout = setTimeout(() => {
                this.pendingRequests.delete(correlationId);
                reject(new Error(`Request to ${target} timed out after ${timeoutMs}ms`));
            }, timeoutMs) as unknown as number;

            // Store pending request
            this.pendingRequests.set(correlationId, {
                resolve: resolve as (value: unknown) => void,
                reject,
                timeout
            });

            // Publish request
            this.publish({
                type: MessageType.REQUEST,
                source,
                target,
                payload: { action: 'request', params: payload as Record<string, unknown> },
                priority: MessagePriority.HIGH,
                correlationId
            });
        });
    }

    respond<T>(originalMessage: AgentMessage, payload: ResponsePayload<T>): void {
        if (!originalMessage.correlationId) {
            console.warn('[HUB] Cannot respond to message without correlationId');
            return;
        }

        this.publish({
            type: MessageType.RESPONSE,
            source: originalMessage.target || 'unknown',
            target: originalMessage.source,
            payload,
            priority: MessagePriority.HIGH,
            correlationId: originalMessage.correlationId
        });
    }

    // ========================================================================
    // UTILITIES
    // ========================================================================

    getMessageHistory(type?: MessageType): AgentMessage[] {
        if (type) {
            return this.messageHistory.filter(m => m.type === type);
        }
        return [...this.messageHistory];
    }

    getPendingCount(): Record<MessagePriority, number> {
        return this.queue.getCountByPriority();
    }

    reset(): void {
        // Clear all subscriptions
        this.subscriptions.clear();
        this.agentSubscriptions.clear();

        // Clear message history (resets per query as decided)
        this.messageHistory = [];

        // Clear pending requests
        for (const pending of this.pendingRequests.values()) {
            clearTimeout(pending.timeout);
            pending.reject(new Error('Hub reset'));
        }
        this.pendingRequests.clear();

        // Clear queue
        this.queue.clear();

        console.log('[HUB] Reset complete');
    }

    // ========================================================================
    // DEBUG HELPERS
    // ========================================================================

    getStats(): {
        subscriptionCount: number;
        historyCount: number;
        pendingRequestCount: number;
    } {
        let subscriptionCount = 0;
        for (const handlers of this.subscriptions.values()) {
            subscriptionCount += handlers.size;
        }
        for (const handlers of this.agentSubscriptions.values()) {
            subscriptionCount += handlers.size;
        }

        return {
            subscriptionCount,
            historyCount: this.messageHistory.length,
            pendingRequestCount: this.pendingRequests.size
        };
    }
}

// ============================================================================
// FACTORY FUNCTION
// ============================================================================

/**
 * Create a new AgentCommunicationHub instance.
 * Each request should create its own hub for isolation.
 */
export function createCommunicationHub(): IAgentCommunicationHub {
    return new AgentCommunicationHub();
}

// ============================================================================
// HELPER FUNCTIONS FOR COMMON PATTERNS
// ============================================================================

/**
 * Helper to create a DATA_READY message
 */
export function createDataReadyMessage(
    source: string,
    payload: DataReadyPayload
): Omit<AgentMessage<DataReadyPayload>, 'id' | 'timestamp'> {
    return {
        type: MessageType.DATA_READY,
        source,
        payload,
        priority: MessagePriority.HIGH
    };
}

/**
 * Helper to create an INTENT_ANALYZED message
 */
export function createIntentAnalyzedMessage(
    source: string,
    payload: IntentAnalyzedPayload
): Omit<AgentMessage<IntentAnalyzedPayload>, 'id' | 'timestamp'> {
    return {
        type: MessageType.INTENT_ANALYZED,
        source,
        payload,
        priority: MessagePriority.HIGH
    };
}

/**
 * Helper to create a ROUTE_FOUND message
 */
export function createRouteFoundMessage(
    source: string,
    payload: RouteFoundPayload
): Omit<AgentMessage<RouteFoundPayload>, 'id' | 'timestamp'> {
    return {
        type: MessageType.ROUTE_FOUND,
        source,
        payload,
        priority: MessagePriority.NORMAL
    };
}

/**
 * Helper to create an ERROR message
 */
export function createErrorMessage(
    source: string,
    error: string,
    details?: Record<string, unknown>
): Omit<AgentMessage<{ error: string; details?: Record<string, unknown> }>, 'id' | 'timestamp'> {
    return {
        type: MessageType.ERROR,
        source,
        payload: { error, details },
        priority: MessagePriority.CRITICAL
    };
}

/**
 * Wait for a specific message type with timeout
 */
export function waitForMessage<T>(
    hub: IAgentCommunicationHub,
    type: MessageType,
    timeoutMs: number = 5000
): Promise<AgentMessage<T>> {
    return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
            unsubscribe();
            reject(new Error(`Timeout waiting for ${type}`));
        }, timeoutMs);

        const unsubscribe = hub.subscribe<T>(type, (message) => {
            clearTimeout(timeout);
            unsubscribe();
            resolve(message);
        });
    });
}
