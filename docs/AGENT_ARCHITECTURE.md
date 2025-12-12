# SaharaUser Agent Architecture

## Overview

The SaharaUser app uses a **multi-agent AI system** where specialized agents collaborate to answer user queries. Each agent has a defined **persona**, **capabilities**, and can **communicate with other agents**.

## System Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         USER INTERFACE (ChatScreen)                       │
│                     Shows: Agent Status, Thinking Steps                   │
└─────────────────────────────────┬────────────────────────────────────────┘
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                          CHAT VIEW MODEL                                  │
│                     Manages UI state, triggers orchestrator               │
└─────────────────────────────────┬────────────────────────────────────────┘
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                                                                          │
│                      ┌─────────────────────────────┐                     │
│                      │    MAIN ORCHESTRATOR        │                     │
│                      │    ────────────────────     │                     │
│                      │    • Understands intent     │                     │
│                      │    • Routes to agents       │                     │
│                      │    • Aggregates results     │                     │
│                      │    • Generates response     │                     │
│                      └───────────┬─────────────────┘                     │
│                                  │                                        │
│     ┌────────────────────────────┼────────────────────────────┐          │
│     │                            │                            │          │
│     ▼                            ▼                            ▼          │
│ ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌─────────┐ │
│ │ SQLAgent  │  │ PathAgent │  │ TimeAgent │  │ BusTracker│  │Transfer │ │
│ │ ───────── │  │ ─────────│  │ ─────────│  │ ─────────│  │ ─────── │ │
│ │ Data      │  │ Routes    │  │ ETAs     │  │ Live Pos  │  │ Multi-  │ │
│ │ Retriever │  │ Optimizer │  │ Calculator│  │ Tracker  │  │ Bus     │ │
│ └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └────┬────┘ │
│       │              │              │              │               │      │
│       └──────────────┴──────────────┴──────────────┴───────────────┘      │
│                                  │                                        │
│                                  ▼                                        │
│                   ┌──────────────────────────────┐                       │
│                   │  AGENT COMMUNICATION HUB     │                       │
│                   │  ────────────────────────    │                       │
│                   │  • Message passing           │                       │
│                   │  • Data sharing              │                       │
│                   │  • Task context              │                       │
│                   │  • Result storage            │                       │
│                   └──────────────────────────────┘                       │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                              SUPABASE                                     │
│            vehicles | route_stops | locations | users                     │
└──────────────────────────────────────────────────────────────────────────┘
```

## Agent Personas

### 1. Main Orchestrator
**Role**: Central Brain  
**Personality**: Strategic, analytical, coordinating  
**Responsibilities**:
- Parse user intent using Perplexity
- Determine which agents to call
- Execute agent pipeline in proper order
- Aggregate results from all agents
- Generate final user-friendly response

### 2. SQL Agent
**Role**: Data Retriever  
**Personality**: Precise, factual, organized  
**Capabilities**:
- Fetch vehicles, route stops, live locations
- Find stops matching search terms
- Identify vehicles covering origin → destination
- Provide structured data for other agents

**Communicates With**:
- PathAgent → Sends route stops data
- TimeAgent → Sends stop sequences
- BusTrackerAgent → Shares vehicle info

### 3. Path Agent
**Role**: Route Optimizer  
**Personality**: Analytical, algorithm-focused  
**Capabilities**:
- Find vehicles covering origin → destination (in order)
- Calculate route distances using Haversine
- Match live bus GPS to nearest stop
- Rank routes by distance + bus proximity

**Depends On**: SQLAgent  
**Communicates With**: TimeAgent (sends distance data)

### 4. Time Agent
**Role**: ETA Calculator  
**Personality**: Realistic, practical  
**Capabilities**:
- Calculate travel time from distance
- Factor in stop dwell times (1.5 min/stop)
- Apply rush hour adjustments (1.5x factor)
- Estimate bus-to-boarding arrival

**Depends On**: PathAgent

### 5. Bus Tracker Agent
**Role**: Live Position Specialist  
**Personality**: Vigilant, real-time focused  
**Capabilities**:
- Fetch real-time bus GPS
- Detect movement (moving vs stopped)
- Identify stale data (> 10 min old)
- Match GPS to route stops

### 6. User Context Agent
**Role**: Personalization Specialist  
**Personality**: Understanding, memory-focused  
**Capabilities**:
- Fetch user preferences
- Retrieve search/trip history
- Identify frequent routes
- Apply preferences to recommendations

### 7. Transfer Agent
**Role**: Multi-Bus Journey Specialist  
**Personality**: Problem-solver  
**Capabilities**:
- Find transfer points between routes
- Minimize walking (< 500m)
- Calculate multi-leg journeys
- Limit to 2 transfers max

**Called When**: PathAgent finds no direct route

### 8. Analytics Agent
**Role**: Pattern Analyst  
**Personality**: Data-driven  
**Capabilities**:
- Analyze travel patterns
- Track route popularity
- Identify time-based trends
- Provide optimization insights

### 9. Communication Agent
**Role**: Response Formatter  
**Personality**: Friendly, clear  
**Capabilities**:
- Format technical data for users
- Ask clarifying questions
- Generate welcome messages
- Handle errors gracefully

## Agent Pipeline Flows

### Route Search Query
```
User: "Take me from MG Road to Electronic City"
          │
          ▼
    Main Orchestrator
    ├─► Intent: route_search
    │   Extracted: origin="MG Road", destination="Electronic City"
    │
    ├─► [1] SQLAgent.execute()
    │       ↳ Fetches vehicles, all route stops
    │       ↳ Finds matching vehicles
    │       ↳ Stores result in hub
    │
    ├─► [2] PathAgent.execute()
    │       ↳ Gets data from SQLAgent result
    │       ↳ Calculates distances with Haversine
    │       ↳ Matches live bus to nearest stop
    │       ↳ Ranks paths, selects best
    │       ↳ Notifies TimeAgent
    │
    ├─► [3] TimeAgent.execute()
    │       ↳ Gets distance from PathAgent result
    │       ↳ Calculates base ETA
    │       ↳ Applies rush hour factor
    │       ↳ Returns ETA range
    │
    ├─► [4] BusTrackerAgent.execute()
    │       ↳ Gets live bus positions
    │       ↳ Analyzes freshness
    │       ↳ Returns position data
    │
    ├─► Aggregate all results
    │
    ├─► Generate response with Perplexity
    │
    └─► Return to user:
        "🚌 Blue Line Express (KA-01-1234)
         📍 MG Road → Silk Board → Electronic City
         📏 15.2 km | ⏱️ 45-55 min
         ✅ Bus is 3 stops away"
```

### No Direct Route (Transfer)
```
User: "Take me from Whitefield to Banashankari"
          │
          ▼
    [1-4] Standard pipeline... PathAgent returns:
          success: false
          suggestedNextAgent: "TransferAgent"
          │
          ▼
    [5] TransferAgent.execute()
        ↳ Finds routes with origin
        ↳ Finds routes with destination
        ↳ Finds transfer points (< 500m walk)
        ↳ Returns best transfer plan
          │
          ▼
    Generate response:
    "🔄 Transfer Required
     1️⃣ Take Purple Line to Majestic
     🚶 Walk 200m to City Bus Stand
     2️⃣ Take Route 201 to Banashankari"
```

## Inter-Agent Communication

### AgentCommunicationHub

Central messaging system for all agents:

```kotlin
// Register agent with hub
hub.registerAgent("PathAgent", persona)

// Send message to another agent
hub.sendMessage(
    fromAgent = "PathAgent",
    toAgent = "TimeAgent",
    messageType = MessageType.DATA_RESPONSE,
    content = mapOf("distance_km" to 15.2)
)

// Store result for other agents
hub.storeResult("PathAgent", result)

// Get result from another agent
val sqlData = hub.getAgentResult("SQLAgent")
```

### Message Types

| Type | Description |
|------|-------------|
| DATA_REQUEST | Request data from another agent |
| DATA_RESPONSE | Response with requested data |
| TASK_DELEGATION | Delegating a sub-task |
| RESULT_UPDATE | Sharing intermediate results |
| STATUS_UPDATE | Agent status change |
| ERROR_REPORT | Error encountered |
| CLARIFICATION_NEEDED | Need more info |

## Cursor-like UI

The ChatScreen shows:

1. **Thinking Steps**
   - Understanding your request ✓
   - Identifying relevant agents ✓
   - Querying databases •
   - Calculating estimates
   - Generating response

2. **Current Agent Status**
   ```
   ┌──────────────────────────────┐
   │ 🤖 Path Agent               │
   │    Finding optimal routes... │
   └──────────────────────────────┘
   ```

3. **Agent Pipeline** (optional)
   ```
   [SQL] → [Path] → [Time] → [Bus]
     ✓       •
   ```

## Files Structure

```
agents/
├── AgentCommunicationHub.kt   # Inter-agent messaging
├── BaseAgent.kt               # Base class with persona support
├── MainOrchestrator.kt        # Central coordinator
├── SQLAgent.kt                # Data retrieval
├── PathAgent.kt               # Route optimization
├── TimeAgent.kt               # ETA calculation
├── BusTrackerAgent.kt         # Live tracking
├── UserContextAgent.kt        # Personalization
├── TransferAgent.kt           # Multi-bus journeys
├── AnalyticsAgent.kt          # Pattern analysis
└── CommunicationAgent.kt      # Response formatting
```

## Adding a New Agent

1. Create agent class extending `BaseAgent`
2. Define `persona` with role, capabilities, data access
3. Implement `execute()` method
4. Register in `AppModule.kt`
5. Add to MainOrchestrator's routing logic

```kotlin
class NewAgent @Inject constructor(
    perplexityClient: PerplexityClient,
    private val supabaseClient: SupabaseApiClient
) : BaseAgent(perplexityClient) {
    
    override val agentName = "NewAgent"
    
    override val persona = AgentPersona(
        role = "Description",
        capabilities = listOf("capability1", "capability2"),
        dataAccess = listOf("table1"),
        canDelegateTo = listOf("OtherAgent")
    )
    
    override val systemPrompt = "You are..."
    
    override suspend fun execute(input: Map<String, Any>): AgentResult {
        // Implementation
    }
}
```
