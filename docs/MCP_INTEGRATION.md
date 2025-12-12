# MCP Integration Guide for SaharaUser

This document explains how Model Context Protocol (MCP) servers can be used to enhance the SaharaUser AI agent system.

## Available MCP Servers

### 1. Perplexity Ask (`perplexity-ask`)

**Purpose**: Advanced reasoning and natural language understanding

**Use Cases in SaharaUser**:
- Main Orchestrator query understanding
- Complex route analysis and recommendations  
- Natural language response generation
- Multi-factor decision making

**Integration Point**: Already integrated via `PerplexityClient.kt`

---

### 2. Sequential Thinking (`sequential-thinking`)

**Purpose**: Step-by-step reasoning for complex problems

**Use Cases in SaharaUser**:
- Breaking down multi-leg journeys
- Analyzing transfer options
- Debugging route finding issues
- Complex ETA calculations with multiple factors

**How to Use**:
```kotlin
// In MainOrchestrator for complex queries
val thinkingResult = mcpSequentialThinking.process(
    thought = "User wants to go from A to C, but no direct route. Need to find transfer at B",
    thoughtNumber = 1,
    totalThoughts = 5,
    nextThoughtNeeded = true
)
```

**Agent Integration**:
- Path Agent: Use for multi-hop route analysis
- Transfer Agent: Use for optimizing connection points
- Time Agent: Use for complex delay calculations

---

### 3. Supabase MCP Server (`supabase-mcp-server`)

**Purpose**: Direct database operations via MCP

**Available Tools**:

| Tool | Use Case |
|------|----------|
| `execute_sql` | Query routes, stops, live locations |
| `apply_migration` | Update schema for new features |
| `list_tables` | Discover available data |
| `get_logs` | Debug API/database issues |
| `search_docs` | Find Supabase best practices |

**How to Use**:
```kotlin
// Instead of SupabaseApiClient, use MCP for more flexible queries
val routes = mcpSupabase.executeSql(
    projectId = "wjivmumfwgmpjxfsfvqe",
    query = """
        SELECT v.*, rs.*
        FROM vehicles v
        JOIN route_stops rs ON rs.vehicle_id = v.id
        WHERE v.is_active = true
        ORDER BY v.id, rs.stop_order
    """
)
```

**Benefits Over Direct Client**:
- Complex JOIN queries
- Real-time schema exploration
- Migration management
- Logs for debugging

---

## Recommended MCP Usage Per Agent

| Agent | MCP Server | Purpose |
|-------|------------|---------|
| Main Orchestrator | `perplexity-ask` | Intent understanding, response synthesis |
| SQL Agent | `supabase-mcp-server` | Flexible SQL queries |
| Path Agent | `sequential-thinking` | Multi-hop route analysis |
| Time Agent | `perplexity-ask` | Factor analysis for ETA adjustments |
| Bus Tracker | `supabase-mcp-server` | Real-time location queries |
| User Context | `supabase-mcp-server` | Preference queries |

---

## MCP Connection Setup

### User Configuration Required

Add these to your MCP configuration:

```json
{
  "mcpServers": {
    "perplexity-ask": {
      "command": "npx",
      "args": ["-y", "@anthropics/perplexity-ask-mcp"],
      "env": {
        "PERPLEXITY_API_KEY": "your-perplexity-key"
      }
    },
    "sequential-thinking": {
      "command": "npx",
      "args": ["-y", "@anthropics/sequential-thinking-mcp"]
    },
    "supabase-mcp-server": {
      "command": "npx",
      "args": ["-y", "supabase-mcp-server"],
      "env": {
        "SUPABASE_ACCESS_TOKEN": "your-supabase-access-token"
      }
    }
  }
}
```

---

## Example: Using MCP for Complex Route Query

```
User: "I need to go from MG Road to Airport, but I want to avoid traffic"

Sequential Thinking Process:
1. Thought: User wants route from MG Road to Airport avoiding traffic
2. Thought: Query Supabase for all paths covering these stops
3. Thought: Get live bus positions to see which routes have active buses
4. Thought: Check time - if rush hour, prefer routes on main roads
5. Thought: Combine results and recommend the least congested route

Perplexity Analysis:
- Synthesize findings into user-friendly response
- Consider user's past preferences
- Format with emojis and clear structure
```

---

## Key Benefits of MCP Integration

1. **Flexible SQL**: Write complex queries without predefined functions
2. **Step-by-Step Reasoning**: Debug and explain complex decisions
3. **Better Responses**: Perplexity provides natural, contextual answers
4. **Real-time Schema**: Explore and adapt to database changes
5. **Logging & Debug**: Access Supabase logs for troubleshooting

---

## Implementation Priority

1. **Already Done**: Perplexity API (via `PerplexityClient.kt`)
2. **High Value**: Supabase MCP for flexible queries
3. **Nice to Have**: Sequential Thinking for complex multi-leg routes

The current implementation uses direct API calls which work well. MCP can be added incrementally for enhanced capabilities when needed.
