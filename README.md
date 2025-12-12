# SaharaUser - AI Bus Route Assistant

An intelligent Android application that helps users find optimal bus routes using a multi-agent AI system powered by Perplexity Sonar Pro.

## ✅ Features

- 🤖 **AI-Powered Chat** - Natural language interface for route queries
- 🚌 **Predefined Routes** - Works with routes created in Sahara Driver app
- 📍 **Live Bus Tracking** - Real-time bus positions
- ⏱️ **ETA Calculations** - Accurate arrival time estimates
- 🔄 **Transfer Optimization** - Multi-bus journey planning
- 👤 **User Profiles** - Personalized recommendations
- 📱 **Cursor-like UI** - Shows which AI agents are working

## 🏗️ Architecture

### Multi-Agent System

```
User Query
    ↓
┌─────────────────────────────────────────────────────┐
│           Main Orchestrator                         │
│           (Perplexity Sonar Pro)                   │
│                                                     │
│   Routes query to appropriate agents:               │
│   • Route Search → SQL + Path + Time + BusTracker  │
│   • Bus Location → BusTracker only                 │
│   • ETA Query → Time + BusTracker                  │
└────────────────┬────────────────────────────────────┘
                 │
    ┌────────────┼────────────┐
    ↓            ↓            ↓
┌───────┐  ┌──────────┐  ┌──────────┐
│ SQL   │  │  Path    │  │  Time    │
│ Agent │  │  Agent   │  │  Agent   │
└───┬───┘  └────┬─────┘  └────┬─────┘
    │           │             │
    ↓           ↓             ↓
Supabase   Algorithms    Formulas
```

### All Agents (9 Total)

| Agent | Purpose |
|-------|---------|
| **Main Orchestrator** | Query routing & result aggregation |
| **SQL Agent** | Database queries (vehicles, stops) |
| **Path Agent** | Find shortest predefined path |
| **Time Agent** | ETA calculations with traffic |
| **Bus Tracker Agent** | Live bus positions |
| **User Context Agent** | Preferences & history |
| **Transfer Agent** | Multi-bus journey optimization |
| **Analytics Agent** | Usage patterns & insights |
| **Communication Agent** | NLU & response formatting |

### Algorithms (Pre-coded, No ML)

- **Haversine** - GPS distance calculation
- **Dijkstra** - Shortest path finding
- **A*** - Heuristic-guided pathfinding

## 📁 Project Structure

```
d:\SaharaUser\
├── build.gradle.kts
├── settings.gradle.kts
├── local.properties.template     ← Copy to local.properties & add API key
├── README.md
│
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/sahara/user/
│           ├── SaharaUserApp.kt
│           ├── MainActivity.kt
│           │
│           ├── agents/              # AI Agents (9 files)
│           │   ├── BaseAgent.kt
│           │   ├── MainOrchestrator.kt
│           │   ├── SQLAgent.kt
│           │   ├── PathAgent.kt
│           │   ├── TimeAgent.kt
│           │   ├── BusTrackerAgent.kt
│           │   ├── UserContextAgent.kt
│           │   ├── TransferAgent.kt
│           │   ├── AnalyticsAgent.kt
│           │   └── CommunicationAgent.kt
│           │
│           ├── algorithms/          # Pre-coded algorithms
│           │   ├── Haversine.kt
│           │   ├── Dijkstra.kt
│           │   └── AStar.kt
│           │
│           ├── data/
│           │   ├── api/
│           │   │   ├── PerplexityClient.kt
│           │   │   └── SupabaseApiClient.kt
│           │   ├── model/
│           │   │   ├── User.kt
│           │   │   └── Route.kt
│           │   └── repository/
│           │       ├── RouteRepository.kt
│           │       └── UserRepository.kt
│           │
│           ├── di/
│           │   └── AppModule.kt     # Hilt DI
│           │
│           └── ui/
│               ├── theme/
│               ├── navigation/
│               ├── screens/
│               │   ├── ChatScreen.kt    # Main Cursor-like UI
│               │   ├── LoginScreen.kt
│               │   └── ProfileScreen.kt
│               └── viewmodel/
│                   └── ChatViewModel.kt
│
└── docs/
    ├── SUPABASE_USER_SCHEMA.sql     # Run in Supabase
    ├── PREDEFINED_ROUTES.md         # How routes work
    └── MCP_INTEGRATION.md           # MCP servers guide
```

## 🚀 Setup Instructions

### Step 1: Configure Perplexity API Key

1. Go to https://www.perplexity.ai/settings/api
2. Create an API key (format: `pplx-xxxx...`)
3. Copy `local.properties.template` to `local.properties`
4. Replace `pplx-YOUR-API-KEY-HERE` with your actual key

```properties
PERPLEXITY_API_KEY=pplx-your-actual-key-here
```

### Step 2: Run SQL Migration (User's Task)

Run this in Supabase SQL Editor:
- File: `docs/SUPABASE_USER_SCHEMA.sql`
- Creates: `users`, `user_trips`, `user_search_history` tables

### Step 3: Open in Android Studio

1. File → Open → `D:\SaharaUser`
2. Wait for Gradle sync
3. If prompted, update Gradle version

### Step 4: Build and Run

1. Build → Make Project
2. Run on device or emulator (API 26+)

## 📝 Usage

### Example Queries

```
"Take me from MG Road to Electronic City"
"Where is the Blue Line bus?"
"When will the next bus arrive at Koramangala?"
"Show me stops near Indiranagar"
```

### What You'll See

1. **Thinking Process** - Shows which agents are working
2. **Step Progress** - Understanding → Searching → Analyzing
3. **Agent Status** - "Calling Path Agent... Finding shortest paths"
4. **Final Response** - Route with vehicle name, stops, distance, ETA

## 🔧 MCP Integration (Optional)

For enhanced capabilities, you can connect these MCP servers:

| Server | Purpose | Priority |
|--------|---------|----------|
| `perplexity-ask` | Already integrated via API | ✅ Done |
| `supabase-mcp-server` | Flexible SQL queries | Optional |
| `sequential-thinking` | Complex multi-leg routes | Optional |

See `docs/MCP_INTEGRATION.md` for setup details.

## 📊 Supabase Tables

### New Tables (created by migration)

| Table | Purpose |
|-------|---------|
| `users` | User profiles (USR-XXXXXXXX IDs) |
| `user_trips` | Trip history |
| `user_search_history` | Personalization data |

### Existing Tables (from Driver app)

| Table | Purpose |
|-------|---------|
| `vehicles` | Bus information |
| `route_stops` | Stop locations with order |
| `locations` | Live bus GPS |

## 📚 Key Documentation

- [PREDEFINED_ROUTES.md](docs/PREDEFINED_ROUTES.md) - How routes work
- [MCP_INTEGRATION.md](docs/MCP_INTEGRATION.md) - MCP setup
- [SUPABASE_USER_SCHEMA.sql](docs/SUPABASE_USER_SCHEMA.sql) - Database schema

## 📜 License

Private - All rights reserved
