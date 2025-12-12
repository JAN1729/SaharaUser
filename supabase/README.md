# Multi-Agent System - Supabase Edge Functions

This directory contains the backend multi-agent orchestration system that runs on Supabase Edge Functions.

## Architecture

```
supabase/
├── functions/
│   ├── _shared/              # Shared utilities
│   │   ├── types.ts          # TypeScript types
│   │   ├── agents/           # Agent implementations
│   │   ├── tools/            # Tool implementations
│   │   └── algorithms/       # Route algorithms
│   │
│   ├── process-query/        # Main orchestrator endpoint
│   │   └── index.ts
│   │
│   ├── track-bus/            # Real-time bus tracking
│   │   └── index.ts
│   │
│   └── get-nearby-stops/     # Location-based stop search
│       └── index.ts
│
└── config.toml               # Supabase configuration
```

## Setup

1. Install Supabase CLI:
   ```bash
   npm install -g supabase
   ```

2. Initialize project (if not done):
   ```bash
   supabase init
   ```

3. Set secrets:
   ```bash
   supabase secrets set PERPLEXITY_API_KEY=your_key_here
   ```

4. Deploy functions:
   ```bash
   supabase functions deploy process-query
   supabase functions deploy track-bus
   supabase functions deploy get-nearby-stops
   ```

## API Endpoints

### POST /functions/v1/process-query
Main agent orchestrator for processing user queries.

**Request:**
```json
{
  "userId": "user_123",
  "query": "How do I get from MG Road to Electronic City?",
  "userLocation": {
    "latitude": 12.9716,
    "longitude": 77.5946
  }
}
```

**Response:**
```json
{
  "success": true,
  "response": "🚌 **Bus 335E** • 15 km • ~45 min",
  "intent": "FIND_ROUTE",
  "routeData": { ... },
  "agentTrace": [ ... ],
  "executionTimeMs": 1250
}
```

### POST /functions/v1/track-bus
Real-time bus position tracking.

### POST /functions/v1/get-nearby-stops
Find stops near a location.

## Environment Variables

| Variable | Description |
|----------|-------------|
| `PERPLEXITY_API_KEY` | API key for Perplexity AI |
| `SUPABASE_URL` | Auto-provided by Supabase |
| `SUPABASE_SERVICE_ROLE_KEY` | Auto-provided by Supabase |
