/**
 * Agent Communication Hub - Unit Tests
 * 
 * Run with: deno test --allow-all agent-communication-hub.test.ts
 */

import {
    assertEquals,
    assertExists,
    assert
} from 'https://deno.land/std@0.168.0/testing/asserts.ts';

import {
    createCommunicationHub,
    MessageType,
    MessagePriority,
    createDataReadyMessage,
    createIntentAnalyzedMessage,
    createRouteFoundMessage,
    createErrorMessage,
    waitForMessage,
    type AgentMessage,
    type IAgentCommunicationHub
} from './agent-communication-hub.ts';

// ============================================================================
// TEST: Basic Publish/Subscribe
// ============================================================================

Deno.test('Hub - Basic publish/subscribe works', async () => {
    const hub = createCommunicationHub();
    let receivedMessage: AgentMessage | null = null;

    // Subscribe
    const unsubscribe = hub.subscribe(MessageType.DATA_READY, (msg) => {
        receivedMessage = msg;
    });

    // Publish
    hub.publish({
        type: MessageType.DATA_READY,
        source: 'TestAgent',
        payload: { test: 'data' },
        priority: MessagePriority.NORMAL
    });

    // Wait for async processing
    await new Promise(resolve => setTimeout(resolve, 10));

    // Assert
    assertExists(receivedMessage);
    assertEquals(receivedMessage!.source, 'TestAgent');
    assertEquals(receivedMessage!.type, MessageType.DATA_READY);

    // Cleanup
    unsubscribe();
    hub.reset();
});

// ============================================================================
// TEST: Unsubscribe
// ============================================================================

Deno.test('Hub - Unsubscribe stops receiving messages', async () => {
    const hub = createCommunicationHub();
    let messageCount = 0;

    // Subscribe
    const unsubscribe = hub.subscribe(MessageType.DATA_READY, () => {
        messageCount++;
    });

    // Publish first message
    hub.publish({
        type: MessageType.DATA_READY,
        source: 'TestAgent',
        payload: {},
        priority: MessagePriority.NORMAL
    });

    await new Promise(resolve => setTimeout(resolve, 10));
    assertEquals(messageCount, 1);

    // Unsubscribe
    unsubscribe();

    // Publish second message
    hub.publish({
        type: MessageType.DATA_READY,
        source: 'TestAgent',
        payload: {},
        priority: MessagePriority.NORMAL
    });

    await new Promise(resolve => setTimeout(resolve, 10));

    // Should still be 1 (not 2)
    assertEquals(messageCount, 1);

    hub.reset();
});

// ============================================================================
// TEST: Multiple Subscribers
// ============================================================================

Deno.test('Hub - Multiple subscribers all receive messages', async () => {
    const hub = createCommunicationHub();
    let count1 = 0;
    let count2 = 0;

    const unsub1 = hub.subscribe(MessageType.INTENT_ANALYZED, () => { count1++; });
    const unsub2 = hub.subscribe(MessageType.INTENT_ANALYZED, () => { count2++; });

    hub.publish({
        type: MessageType.INTENT_ANALYZED,
        source: 'IntentAgent',
        payload: { primaryIntent: 'FIND_ROUTE' },
        priority: MessagePriority.HIGH
    });

    await new Promise(resolve => setTimeout(resolve, 10));

    assertEquals(count1, 1);
    assertEquals(count2, 1);

    unsub1();
    unsub2();
    hub.reset();
});

// ============================================================================
// TEST: Message Priority Order
// ============================================================================

Deno.test('Hub - Priority order is respected', async () => {
    const hub = createCommunicationHub();
    const receivedOrder: MessagePriority[] = [];

    // Subscribe to all message types
    hub.subscribe(MessageType.ERROR, (msg) => {
        receivedOrder.push(msg.priority);
    });
    hub.subscribe(MessageType.DATA_READY, (msg) => {
        receivedOrder.push(msg.priority);
    });
    hub.subscribe(MessageType.AGENT_COMPLETED, (msg) => {
        receivedOrder.push(msg.priority);
    });

    // Publish in reverse priority order
    hub.publish({
        type: MessageType.AGENT_COMPLETED,
        source: 'Test',
        payload: {},
        priority: MessagePriority.LOW // 4
    });
    hub.publish({
        type: MessageType.DATA_READY,
        source: 'Test',
        payload: {},
        priority: MessagePriority.NORMAL // 3
    });
    hub.publish({
        type: MessageType.ERROR,
        source: 'Test',
        payload: {},
        priority: MessagePriority.CRITICAL // 1
    });

    await new Promise(resolve => setTimeout(resolve, 50));

    // CRITICAL should be processed first
    assertEquals(receivedOrder[0], MessagePriority.CRITICAL);

    hub.reset();
});

// ============================================================================
// TEST: Message History
// ============================================================================

Deno.test('Hub - Message history is recorded', () => {
    const hub = createCommunicationHub();

    hub.publish({
        type: MessageType.DATA_READY,
        source: 'DataAgent',
        payload: {},
        priority: MessagePriority.NORMAL
    });

    hub.publish({
        type: MessageType.ROUTE_FOUND,
        source: 'PathAgent',
        payload: {},
        priority: MessagePriority.NORMAL
    });

    const allHistory = hub.getMessageHistory();
    assertEquals(allHistory.length, 2);

    const dataHistory = hub.getMessageHistory(MessageType.DATA_READY);
    assertEquals(dataHistory.length, 1);

    hub.reset();
});

// ============================================================================
// TEST: Helper Functions
// ============================================================================

Deno.test('Hub - Helper functions create correct message structure', () => {
    const dataReadyMsg = createDataReadyMessage('DataAgent', {
        vehiclesCount: 5,
        stopsCount: 20,
        liveLocationsCount: 3
    });

    assertEquals(dataReadyMsg.type, MessageType.DATA_READY);
    assertEquals(dataReadyMsg.source, 'DataAgent');
    assertEquals(dataReadyMsg.priority, MessagePriority.HIGH);
    assertEquals(dataReadyMsg.payload.vehiclesCount, 5);

    const intentMsg = createIntentAnalyzedMessage('IntentAgent', {
        primaryIntent: 'FIND_ROUTE',
        subIntents: ['eta'],
        entities: { origin: 'MG Road' },
        confidence: 0.95,
        reasoning: 'User wants directions'
    });

    assertEquals(intentMsg.type, MessageType.INTENT_ANALYZED);
    assertEquals(intentMsg.payload.confidence, 0.95);

    const routeMsg = createRouteFoundMessage('PathAgent', {
        vehicleId: 'bus-1',
        vehicleName: 'Bus 335E',
        originStop: 'MG Road',
        destinationStop: 'Electronic City',
        etaMinutes: 25,
        alternativesCount: 2
    });

    assertEquals(routeMsg.type, MessageType.ROUTE_FOUND);
    assertEquals(routeMsg.payload.etaMinutes, 25);

    const errorMsg = createErrorMessage('TestAgent', 'Something went wrong', { code: 500 });

    assertEquals(errorMsg.type, MessageType.ERROR);
    assertEquals(errorMsg.priority, MessagePriority.CRITICAL);
});

// ============================================================================
// TEST: Hub Reset
// ============================================================================

Deno.test('Hub - Reset clears all state', async () => {
    const hub = createCommunicationHub();
    let messageReceived = false;

    hub.subscribe(MessageType.DATA_READY, () => {
        messageReceived = true;
    });

    hub.publish({
        type: MessageType.DATA_READY,
        source: 'Test',
        payload: {},
        priority: MessagePriority.NORMAL
    });

    await new Promise(resolve => setTimeout(resolve, 10));
    assert(messageReceived);

    // Reset
    hub.reset();
    messageReceived = false;

    // Publish again - should NOT be received (subscriptions cleared)
    hub.publish({
        type: MessageType.DATA_READY,
        source: 'Test',
        payload: {},
        priority: MessagePriority.NORMAL
    });

    await new Promise(resolve => setTimeout(resolve, 10));
    assertEquals(messageReceived, false);

    // History should be empty
    assertEquals(hub.getMessageHistory().length, 0);
});

// ============================================================================
// TEST: Agent Subscriptions
// ============================================================================

Deno.test('Hub - Subscribe to specific agent works', async () => {
    const hub = createCommunicationHub();
    let receivedFromDataAgent = false;
    let receivedFromPathAgent = false;

    hub.subscribeToAgent('DataAgent', () => {
        receivedFromDataAgent = true;
    });

    hub.subscribeToAgent('PathAgent', () => {
        receivedFromPathAgent = true;
    });

    // Only publish from DataAgent
    hub.publish({
        type: MessageType.DATA_READY,
        source: 'DataAgent',
        payload: {},
        priority: MessagePriority.NORMAL
    });

    await new Promise(resolve => setTimeout(resolve, 10));

    assert(receivedFromDataAgent);
    assertEquals(receivedFromPathAgent, false);

    hub.reset();
});

console.log('✅ All Agent Communication Hub tests passed!');
