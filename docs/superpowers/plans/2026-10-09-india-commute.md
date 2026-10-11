# India-first multimodal commute implementation plan

> **For agentic workers:** Execute inline using superpowers:executing-plans. Use a fresh-context whole-change reviewer after verification. Steps use checkbox syntax for tracking.

**Goal:** Build an independently runnable, India-first commute backend and connect the existing Android chat to it, with train, intercity-bus and domestic-flight provider boundaries.

**Architecture:** A Python modular monolith serves planning, revalidation, persistence and a browser application. Isolated provider adapters collect structured observations; a deterministic planner validates complete journeys. SQLite provides durable local operation without the obsolete Supabase project. Production flight data and optional external services activate only when credentials are configured.

**Tech stack:** Python 3.12, FastAPI, Pydantic, httpx, SQLite, unittest, existing Kotlin/Compose Android client. Optional Scrapling browser collector runs outside the API event loop. No import of the Telegram monolith or original secrets.

**Spec:** `docs/commute/MULTIMODAL_BACKEND_PROPOSAL.md`, with the user's 9 October clarification authorizing backend/data/code construction, domestic-flight adapters, bus/train scraping, and application integration before supplying credentials.

## Global constraints

- India-first, including domestic flight provider support; no fictitious live inventory.
- Original train-monitor and the reference clone remain unchanged.
- Product works locally without Supabase or provider keys; explicit sample mode is separate from live mode.
- UTC-aware instants with Asia/Kolkata local presentation; separate terminals and transfer edges.
- Unknown fares are not zero; waitlists/unknown seats are not confirmed availability.
- Provider failure differs from empty inventory; observation age is independent of fetch age.
- No bookings, cancellations, external notifications, deployment or paid requests.
- Preserve existing Sahara appearance; expose hidden map capabilities in the actual chat flow.
- Limit search fan-out and provider concurrency; do not bypass access controls.

## Review focus

- Overnight services and a connection departing before arrival: task 1 tests absolute-date feasibility.
- A whole city maps to different rail, bus and airport terminals: task 1 tests transfer time and mode processing.
- Empty inventory versus timeout/invalid parser output: task 2 tests typed provider status.
- A delayed leg or sold-out replacement affects a persisted journey: task 3 tests revalidation and ownership.
- A stale or sample fare looks live in the app: task 4 tests compatibility response provenance and stream events.

## Task 1: Domain and deterministic journey engine

Files: `backend/sahara/domain.py`, `places.py`, `planner.py`, `tests/test_planner.py`, `pyproject.toml`.

Interfaces: `SearchRequest`, `Leg`, `Observation`, `ProviderResult`, `Journey`; `plan(request, legs, places, now) -> PlanResult`. Money is typed; unknown is null. Terminal IDs are canonical. Leg times are timezone-aware.

- [ ] Write independent fixtures with literals: 18:00 arrival cannot connect to 17:00 departure; next-day 01:00 arrival connects to next-day 03:00; zero/unknown seats cannot satisfy confirmed-only; unknown fare cannot win cheapest; deadlines and airport processing constrain search.
- [ ] Run `python -m unittest discover -s tests -v` and observe missing planner behavior fail.
- [ ] Implement finite bounded label search and explicit transfer checks, rejection reasons, coverage and useful alternative rankings.
- [ ] Run all backend tests; add tests for same-service class variants, request budgets, accessibility uncertainty and stale offers.

Example independent assertion:

```python
result = plan(request, [arrives_1800, departs_1700], places, now)
self.assertEqual([], result.journeys)
```

## Task 2: Acquisition and provider broker

Files: `providers/base.py`, `providers/sample.py`, `providers/rail.py`, `providers/ksrtc.py`, `providers/flights.py`, `providers/local.py`, `broker.py`, `tests/test_providers.py`, `tests/test_broker.py`.

Interfaces: `Provider.search(request, pairs, now) -> ProviderResult`; `Provider.refresh(legs, now) -> ProviderResult`. Broker gathers bounded independent adapters with deadlines, cache expiry, deduplication and partial errors. Provider parsing remains testable without network.

- [ ] Test saved sanitized provider-shaped fragments, literal fare/availability/time expectations, malformed pages, overnight day offsets, flight expiry/live mode and local realtime timestamps.
- [ ] Observe those tests fail before implementing parsers and adapters.
- [ ] Build credential-free train/KSRTC collection paths with bounded browser fallback and optional structured feeds; build an authenticated domestic-flight offer adapter activated only by its key.
- [ ] Add provider-specific concurrency, request sharing and typed statuses; verify outage does not become zero services.
- [ ] Run all tests; perform only credential-free public smoke requests, separately documenting reachability versus live inventory.

Example assertion:

```python
parsed = parse_bus_cards(html_with_unknown_seats, service_date)
self.assertEqual("unknown", parsed[0].availability.value)
```

## Task 3: API, persistence and disruption recovery

Files: `api.py`, `service.py`, `storage.py`, `settings.py`, `tests/test_api.py`, `tests/test_storage.py`, `schema.sql`, `.env.example`, `Dockerfile`.

Interfaces: SQLite `Repository.save_search/get_search/save_journey/get_journey/save_watch`, all private reads bound to owner; `PlanningService.search/revalidate`. API `/v1/journeys/search`, searches, revalidation, watch records and provider health.

- [ ] Test a persisted journey survives repository reopen; another principal cannot retrieve it; a delayed updated leg invalidates a downstream connection; searches retain provider coverage.
- [ ] Observe failures, then implement SQLite transactions, conservative revalidation and API input validation.
- [ ] Expose anonymous local sample operation and authenticated live/private operation; restrict origins, secrets and fixture-only mutation controls.
- [ ] Run full suite and launch the server for real HTTP checks. External notifications remain out of scope; watch state/events are persisted and revalidation is callable.

Example assertion:

```python
self.assertIsNone(repository.get_journey(journey_id, other_owner))
```

## Task 4: Runnable app and Android integration

Files: `web/index.html`, `compat.py`, Android `AgentApiClient.kt`, `ChatViewModel.kt`, `ThinkingScreen.kt`, app build configuration and debug networking manifest, `tests/test_compat.py`.

Interfaces: `/process-query` accepts the existing Android request and produces newline-delimited progress plus its `ProcessQueryResponse`, including structured journeys and map tool data. Android config selects the new backend without requiring old Supabase initialization.

- [ ] Test compatibility responses preserve evidence labels and show legs/transfer margins; test stream final payload is parseable and route coordinates retain terminal IDs.
- [ ] Implement browser journey input/results/revalidation against the same backend, preserving the orange/white Sahara style and clear sample/live status.
- [ ] Connect the existing Android flow, render maps and journey alternatives, fix event buffering, and prevent Supabase initialization when independent backend mode is enabled.
- [ ] Run full backend suite, HTTP demo/revalidation and browser checks; run Android compilation where dependency access permits. Report device/live credential gates separately.

## Task 5: Handoff and review

Files: `backend/README.md`, `docs/commute/IMPLEMENTATION_STATUS.md`.

- [ ] Document local startup, emulator/phone backend URLs, provider keys, production access, source coverage and deterministic sample scenarios.
- [ ] Verify all tasks against the approved proposal and record remaining credential/deployment gates precisely.
- [ ] Request a fresh-context whole-change code review under executing-plans; fix concrete important findings with regressions.
- [ ] Leave scoped changes reviewable without committing/pushing reference repositories or unrelated work.

## Execution ruling

The user's explicit instruction to proceed and build the backend, data structure, code and application integration supplies implementation authorization in this turn. Execute this plan in the current workspace without another confirmation. Main-workspace docs/reference changes already belong to this task; use narrowly scoped edits and no automatic commits. This ruling applies to local reversible implementation, not deployment or purchases.
