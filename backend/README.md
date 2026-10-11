# Sahara commute backend

For the current Windows launcher, Android setup, server-only OpenRouter configuration and Supabase decision, use [the central-server runbook](../docs/commute/CENTRAL_SERVER_RUNBOOK.md). External transport data collection is webpage/DOM only; flight API collection is disabled.

India-first journey planning core for the Sahara Android application. The web page at `/` is a **development diagnostic harness**, not the application or an approved product design. Application screens and product flow remain the owner's decision.

The backend runs independently of the old Supabase project. It combines normalized transport observations, validates complete connections, compares costs and arrival times, stores selected journeys, and rechecks them for changes. It does not book tickets or send external messages.

## Start locally

Python 3.12+ is required. From `C:\Users\Admin\Sahara\backend`:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .
.\.venv\Scripts\python.exe -m uvicorn sahara.api:app --host 127.0.0.1 --port 8787
```

Open `http://127.0.0.1:8787/docs` for the typed API, or `/` for diagnostics. Sample mode needs no provider credentials. It always labels its services, seats and prices as illustrative.

Configuration comes from process environment variables. `.env.example` is a reference; it is **not automatically loaded**. SQLite creates `data/sahara.sqlite3`; use `SAHARA_DATABASE` to choose an absolute durable path. Keep provider tokens on the server.

Live mode requires `SAHARA_API_KEY` in the server environment and `Authorization: Bearer <key>` on requests. For regular browser rendering:

```powershell
.\.venv\Scripts\python.exe -m pip install -e '.[browser]'
.\.venv\Scripts\python.exe -m playwright install chromium
$env:SAHARA_BROWSER_ENABLED='true'
```

Alternatively set `SAHARA_BROWSER_EXECUTABLE` to an installed Chrome executable. The central pooled browser has a concurrency cap of two pages; HTTP collection has a cap of three. An access challenge fails explicitly. No stealth or challenge-bypass code is used.

For a physical Android device, bind the local server to `0.0.0.0` and use this computer's LAN address. For the Android emulator, use `http://10.0.2.2:8787`. This is a local development setup. Public deployment needs HTTPS and user authentication.

## Backend flow

```text
Android typed request / chat compatibility request
  -> place resolution + request validation
  -> bounded provider discovery
  -> parallel collector broker + short cache + duplicate-request sharing
  -> normalized Leg / Observation records
  -> deterministic chronological journey search
  -> explicit transfers, airport processing, availability and fare checks
  -> alternatives + rejection reasons + source warnings
  -> owner-bound SQLite search and journey records
  -> revalidation / opt-in watch worker / change events
```

The planning rules live in `sahara/planner.py`; they do not depend on an LLM. A language model should interpret preferences or explain verified results, while this core remains responsible for time, money and connection validity. The current `/process-query` path uses an optional server-only OpenRouter request interpreter in authorized live mode, with basic route/date/budget parsing as fallback. The model cannot supply inventory or change server ownership; a real key-backed check remains pending.

## Coverage and evidence

The initial catalogue has 17 terminals: rail and bus terminals around Kottayam, Kochi, Thiruvananthapuram, Coimbatore and Bengaluru, plus COK, BLR, TRV, MAA, DEL, BOM and HYD airports. These are seed terminals, not nationwide coverage. Airport-to-city transfers currently exist only where listed in `places.py`. Other airports can be queried as terminals directly.

Discovery considers the endpoints, reachable airports and bounded Kochi/Coimbatore hubs. Each rail/bus adapter checks at most four terminal pairs over at most two service dates. The flight adapter checks at most two airport pairs over at most two dates. Longer windows return an explicit partial-date-coverage status. Results therefore mean **best in checked coverage**, not an exhaustive national optimum. Search limits and provider failures remain visible.

| Source | Implemented behavior | Current boundary |
|---|---|---|
| ConfirmTkt | Public page collector, class fares/seats, observation age; RailYatri fallback | HTML selectors need ongoing source verification; no train position feed |
| KSRTC Swift | Public page collector, route labels, explicit overnight dates, fares and seat state | Kerala KSRTC coverage only; upstream update time often unavailable; exact boarding stop not verified |
| Flight web | Disabled until a verified webpage collector exists | No external flight API calls; old offer parsing fixtures are regression references only |
| Local feed | Server-owned JSON with validated normalized legs | Requires an actual feed; no installed GTFS/GTFS-RT service or street-routing engine |
| Sample | Clearly labelled train/bus/flight/local fixture scenario | Demonstrates planning rules only; never mixed into live searches |

Every leg includes provider, evidence type, fetch time, optional upstream observation time, expiry, source URL and parser version. Evidence is one of `live`, `cached`, `scheduled`, `estimated`, or `fixture`. Fetching a web page now does not prove its underlying seat count was updated now. Unknown upstream age produces a warning.

Money is integer paise. `null` means unknown, never free. Per-person fares multiply by passenger count; flight offer totals already cover the requested party. Airport road transfer costs are currently unknown, so such a journey's complete fare is `null`. A hard budget excludes any itinerary whose complete price is unknown. Currency conversion is not implemented.

## Planner rules

- Timezone-aware service dates; overnight arrival uses source duration and must match the clock.
- Available/confirmed seats by default. Waitlist, RAC, unknown availability, cancelled services, expired offers and insufficient group seats are excluded.
- Only explicit terminal transfer links; sharing a city name does not create a zero-minute connection.
- Transfer travel time, configurable onward buffer (default 15 minutes), departure-airport processing (default 120 minutes) and flight arrival exit time (45 minutes).
- Deadline check includes the final transfer. Initial airport processing is included in journey duration.
- Mode, passenger, walk-distance, accessibility and transfer-count constraints. Unknown accessibility is flagged; it is not certified accessible.
- No repeating the same service or returning to a visited terminal. Search is capped at 4,000 labels / 8,000 queued paths / 12 returned alternatives.
- Earliest arrival, lowest complete known cost and a heuristic option with fewer warnings/transfers and more connection margin. `robust_option` is not a calibrated reliability probability.

Walking and road transfers are estimates from the seed catalogue. There is no live traffic routing, verified station exit, platform, pickup instruction, last metro schedule or baggage fee calculation. The Android compatibility map uses straight lines between seed terminal coordinates; it is not a street navigation route.

## API contract

Send a cryptographically random `X-Sahara-Session` (24–100 URL-safe characters), keep it for the user's development session, and reuse it when reading/revalidating a journey. Browser clients can instead retain the issued HttpOnly session cookie. The supplied `userId` on the legacy chat request does not grant ownership. A session is a bearer capability, so do not log or share it.

| Endpoint | Purpose |
|---|---|
| `GET /health` | Process/storage type and live gateway configuration; not upstream uptime |
| `GET /v1/places` | Canonical terminals, aliases, provider codes and coordinate caveat |
| `GET /v1/providers/health` | Configuration status; does not call a provider |
| `POST /v1/journeys/search` | Typed multimodal search |
| `GET /v1/searches/{id}` | Owner-bound stored search |
| `POST /v1/journeys/{id}/revalidate` | Refresh chosen services and alternatives |
| `POST /v1/journeys/{id}/watch` | Explicit opt-in watch |
| `GET /v1/watches/{id}/events` | Owner-bound durable changes for app polling |
| `DELETE /v1/watches/{id}` | Stop a watch |
| `POST /process-query` | Existing Android chat compatibility stream |

Example typed request (use a future date):

```json
{
  "origin": "kty",
  "destination": "sbc",
  "depart_after": "2026-10-10T06:00:00+05:30",
  "arrive_by": "2026-10-11T09:00:00+05:30",
  "passengers": 2,
  "budget_minor": 400000,
  "modes": ["train", "bus", "flight", "local"],
  "max_transfers": 2,
  "preference": "balanced",
  "data_mode": "sample"
}
```

The response contains `id`, normalized `request`, `plan.journeys`, `plan.rejected`, `plan.warnings`, `providers`, `created_at` and `coverage`. Each journey includes ordered legs, access/transfer/egress connections, full departure/arrival, total and known fare, warnings, recommendations and minimum onward spare time. The full schema is generated at `/openapi.json` and `/docs`.

Use `{ "sample_delay_minutes": 180 }` on a sample journey's revalidation endpoint to test disruption recovery. Live journeys reject this control. Revalidation returns `original_valid`, `current_journey`, refreshed `alternatives`, provider outcomes and check time. This is feasibility in checked data, not a guaranteed reservation.

After departure, revalidation requires explicit traveller progress rather than assuming they boarded. For example:

```json
{
  "completed_leg_ids": [],
  "onboard_leg_id": "id-of-first-leg-in-selected-journey"
}
```

Completed IDs must be an ordered prefix of the selected itinerary; the onboard leg must immediately follow them and have departed. Progress persists for worker refreshes. Recovery locks that travelled prefix, retains its originally selected fares, and checks future connections using refreshed estimated arrival where available. A final onboard leg remains a valid pending arrival, including any required egress transfer. Future discovery dates start from the current time; the default recovery horizon is48 hours, while an explicit arrival deadline is preserved and never silently extended. `recovery_window` discloses that window. Missing or conflicting progress produces `requires_progress: true` and no speculative onward alternatives. An expired onboard arrival estimate or a contradictory future departure also requires a progress update. All legs explicitly marked completed produce `completed: true` and stop the watch. User-reported progress and unverified onboard status remain warnings; this does not create a live running-status feed.

The chat compatibility stream uses blank-line-delimited JSON frames:

```text
{"type":"thinking","data":"Checking complete journeys and connection times…"}

{"type":"reasoning","data":"Comparing train, bus and domestic flight options…"}

{"type":"final_response","data":{"success":true,"response":"...","journeys":[],"searchId":"...","dataMode":"sample","toolCalls":[]}}

```

The Android transport incrementally decodes these frames and also tolerates `data:`-prefixed SSE payloads. Existing chat receives the text summary and compatibility map tool; final structured journeys are available for the owner's future application design. Travel results contain no fabricated vehicle tracker ID.

## Persistence and monitoring

SQLite uses WAL and parameterized queries. `records` has composite key `(kind,id,owner)` and an owner/kind/update index. Record kinds include search, journey, session preferences, revalidation event, watch and watch_event. Connections close after each operation; records survive process restart.

Start **one** watch worker against the same database and environment:

```powershell
.\.venv\Scripts\python.exe -m sahara.worker
```

It polls every 30 seconds, checks due watches approximately every five minutes, and stores an initial snapshot and subsequent changes to services, arrival, price, warnings, progress requirements or source coverage. Individual atomic SQLite claims use a ten-minute crash-recovery lease; finishing checks the lease token and writes the watch/event together. Each pass handles at most 100 due watches, oldest first, so later watches are not silently stranded behind a recent-record limit. A concurrent user stop wins over the worker snapshot. Start one worker for the local build; distributed deployment and production retention policies are not verified. It sends no email, WhatsApp, Telegram or push messages. Android event polling/progress screens are not wired yet. Stop a watch explicitly when no longer needed.

## Android connection plumbing

In root `local.properties` (ignored by Git):

```properties
COMMUTE_BACKEND_URL=http://10.0.2.2:8787
COMMUTE_DATA_MODE=sample
COMMUTE_API_KEY=
```

A nonempty URL selects the new backend and skips eager initialization of the old Supabase paths. Leave it empty to use legacy mode. Debug Android permits local HTTP; release deployment must use HTTPS. No new application screens or navigation were added.

`COMMUTE_API_KEY` is a local demonstration gateway key, not a safe production account credential. Release builds force this field empty; only debug builds may carry the local demonstration key. Production must replace this shared-key gate/session capability arrangement with account authentication and per-user authorization. HTTPS session cookies carry the Secure flag; local HTTP cookies are for development. Duffel and future LLM secrets belong only on the server. The Android session currently lasts for the API-client instance; persistent authenticated account sessions are a later integration.

## Verification

```powershell
python -m unittest discover -s tests -q
```

From the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Read-only public source probe (no flight calls):

```powershell
$env:PYTHONPATH='.'
python scripts/probe_sources.py
python scripts/probe_sources.py --browser
```

Tests cover planning invariants, malformed source cards, fare/seat normalization, cache sharing and timeouts, session ownership, HTTP APIs, chat refinements, revalidation and watch deduplication. They do not prove production inventory, train positions, exact boarding points, a physical-device journey, provider agreements or nationwide optimality. See `../docs/commute/BUILD_STATUS.md` for observed results and remaining gates.

## Next integrations with highest user value

1. Verify a small number of actual corridors and bus pickup points with timestamped source snapshots. Replace generic boarding areas with exact stops and instructions.
2. Provide production domestic-flight access, then test real offers, baggage rules, expiry and refresh without booking.
3. Add authorized train running-status observations and local transit feeds. Monitoring becomes materially useful when estimated arrivals change.
4. Add account authentication and app journey/watch APIs to the owner's chosen screens.
5. Add a constrained language interpreter/explainer using server credentials; all proposed times/fares remain subject to this deterministic core.

Multimodal discovery and AI travel chat already exist. The intended distinction is evidence-backed India corridor planning that exposes transfer costs, separate-ticket exposure, stale information and recovery options. That is a testable product hypothesis, not an established claim of superiority over other assistants.
