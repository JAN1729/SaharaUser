# Predefined Routes Architecture

This document explains how SaharaUser works with predefined bus routes from the Driver application.

## Key Concept: Routes Are Predefined

In the Sahara ecosystem:
- **Bus Owner/Driver** defines the route by adding stops in ORDER
- **Each vehicle** has a fixed sequence of stops (stop_order: 1, 2, 3, ...)
- **Bus travels** from first stop (order 1) to last stop, covering ALL intermediate stops
- **SaharaUser** finds the best EXISTING path, does NOT create new paths

## How It Works

### 1. Driver App Creates Route
```
Vehicle: "Blue Line Express" (BUS-001)

Stops added in order:
  stop_order 1: MG Road
  stop_order 2: Brigade Road
  stop_order 3: Koramangala
  stop_order 4: HSR Layout
  stop_order 5: Electronic City
```

### 2. Data in Supabase
```sql
-- route_stops table
vehicle_id | name            | stop_order
-----------+-----------------+-----------
BUS-001    | MG Road         | 1
BUS-001    | Brigade Road    | 2
BUS-001    | Koramangala     | 3
BUS-001    | HSR Layout      | 4
BUS-001    | Electronic City | 5
```

### 3. SaharaUser Finds Path

When user asks: "Take me from Brigade Road to HSR Layout"

```
Step 1: Find stops matching "Brigade Road" → Found at BUS-001, order 2
Step 2: Find stops matching "HSR Layout" → Found at BUS-001, order 4
Step 3: Check order: 4 > 2 ✅ (destination comes AFTER origin)
Step 4: Extract segment: [Brigade Road → Koramangala → HSR Layout]
Step 5: Calculate distance for this segment
Step 6: Check if BUS-001 is live and where it currently is
```

### 4. Live Bus Position Matching

```
Bus BUS-001 current GPS: (12.9716, 77.5946)

Find nearest stop on its predefined route:
  - MG Road: 0.3 km away
  - Brigade Road: 1.2 km away
  - Koramangala: 3.5 km away

Result: Bus is near MG Road (stop_order 1)

If user boards at Brigade Road (stop_order 2):
  Bus is 1 stop away from boarding point
```

## Key Rules

### ✅ DO
- Follow the stop_order defined by driver
- Find paths where destination order > origin order
- Match live GPS to nearest stop on the route
- Calculate distance along the predefined path

### ❌ DON'T
- Create new route combinations
- Suggest going from order 4 to order 2 (wrong direction)
- Ignore the predetermined sequence
- Mix stops from different vehicles without explicit transfer

## Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    DRIVER APP (Sahara Driver)                   │
│                                                                 │
│   Owner adds stops in order:                                    │
│   Stop 1 → Stop 2 → Stop 3 → Stop 4 → Stop 5                   │
│                                                                 │
│   Saves to Supabase: route_stops table with stop_order         │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                         SUPABASE                                │
│                                                                 │
│   route_stops: vehicle_id, name, lat, lng, stop_order          │
│   locations: bus_id, lat, lng, timestamp (live GPS)            │
│   vehicles: id, name, vehicle_number, is_active                │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    USER APP (SaharaUser)                        │
│                                                                 │
│   1. User: "Take me from A to B"                               │
│   2. SQL Agent: Fetch all routes from Supabase                 │
│   3. Path Agent:                                                │
│      - Find vehicles whose route contains A and B              │
│      - Check that B.stop_order > A.stop_order                  │
│      - Extract route segment from A to B                       │
│      - Find bus current position → nearest stop on route       │
│   4. Time Agent: Calculate ETA based on segment distance       │
│   5. Orchestrator: Present best predefined path to user        │
└─────────────────────────────────────────────────────────────────┘
```

## Example Scenarios

### Scenario 1: Direct Route Exists
```
User: "How do I get from Koramangala to Electronic City?"

Found: BUS-001 has both stops
  - Koramangala: order 3
  - Electronic City: order 5
  - Valid: 5 > 3 ✅

Route: Koramangala → HSR Layout → Electronic City
Distance: 8.2 km
Bus currently at: Brigade Road (order 2)
ETA for bus to reach Koramangala: ~15 min
```

### Scenario 2: Wrong Direction
```
User: "Take me from Electronic City to MG Road"

Found: BUS-001 has both stops
  - Electronic City: order 5
  - MG Road: order 1
  - Invalid: 1 < 5 ❌ (would require reverse direction)

Response: "No direct route available. This bus travels in the opposite direction."
```

### Scenario 3: No Direct Route (Future: Transfers)
```
User: "Get me from MG Road to Whitefield"

Search results:
  - BUS-001: Has MG Road, no Whitefield
  - BUS-002: Has Whitefield, no MG Road

Future enhancement: Transfer Agent finds connection point
```

## Code Implementation

See:
- [PathAgent.kt](../app/src/main/java/com/sahara/user/agents/PathAgent.kt) - Core logic
- [MainOrchestrator.kt](../app/src/main/java/com/sahara/user/agents/MainOrchestrator.kt) - Coordination
- [TimeAgent.kt](../app/src/main/java/com/sahara/user/agents/TimeAgent.kt) - ETA calculation
