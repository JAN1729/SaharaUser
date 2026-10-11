# Implementation ledger — plan: docs/superpowers/plans/2026-10-09-india-commute.md

Ruling: execute inline in the shared Sahara workspace — the user explicitly authorized constructing the backend and app integration after approving the architecture; no repeated permission gate. No commits, deployment, paid requests, external notifications or edits to original/reference train-monitor.

Pre-flight: Tasks 1–4 share normalized Leg/Observation/SearchRequest/Journey contracts; define them once in domain.py and preserve null money, aware times, evidence labels and canonical terminal IDs across providers/API/Android.

Pre-flight: Supabase-free mode requires removing eager Supabase initialization from new-mode ViewModel/realtime paths; legacy mode stays configurable.

Task 1: implemented — domain validation, bounded chronological search, explicit terminal transfers, airport processing, group seats, money completeness, deadline and mode constraints. Planner regressions observed fail before fixes; full backend suite passed 61 tests on 2026-10-09.
Task 2: implemented adapters/broker — rail primary/fallback, KSRTC, live-only domestic Duffel, normalized local feed, isolated sample provider, bounded dates/pairs/concurrency/deadlines and shared request cache. Public HTTP and regular-browser probes yielded no verified inventory; source availability is an open integration gate, not a passing live-data claim.
Task 3: implemented local API, owner-bound SQLite, selected journey revalidation, opt-in watch worker and stored change events. HTTP smoke: search200, five sample journeys, three alternatives after invalidating a chosen connection with180min delay, compatible chat stream successful, unkeyed live401. No external notification channel enabled.
Task 4: complete under user scope ruling — Android testDebugUnitTest passed3/3 and assembleDebug passed, including final transport/config fixes. Streaming transport, backend config, debug networking and lazy legacy Supabase paths. Diagnostic page labelled development-only. No new Android screen/layout/navigation. Physical-device validation remains open.
Task 5: complete for local core handoff — README/env/schema/container recipe, probes and status report. Wheel rebuilt successfully. Fresh whole-change review and follow-up inspection performed; concrete findings fixed with regressions. Final backend79/79 passed, final Android tests/build passed; actual HTTP sample/watch smoke passed. Live source/access and physical-device gates remain explicit. Nothing committed, pushed or deployed.

Ruling: user clarified that the web interface is exclusively a debugging harness and that application design/product flow is their decision. Do not implement new Android journey cards, alter the navigation, or redesign product screens. Limit Android edits to configurable backend transport and streaming; deliver the core backend and mobile API contract. Existing web page is retained only for diagnostics and must be labelled accordingly.

Ruling: retain schema/bootstrap code in a modular Python monolith and SQLite repository rather than depending on the inaccessible old Supabase project. Separate files in the plan were consolidated when their interfaces are covered by API/provider tests. The current session gateway is a local/demo capability mechanism, not production account authentication.

Ruling: keep real source probes separate from fixture/parser verification. None returned verified inventory in this environment; preserve explicit error/timeout outcomes and disclose this gate rather than substituting sample rows into live results.

Ruling: chat currently uses a bounded deterministic interpreter; no unconfigured model is called. General multilingual intelligence, authorized train-running status, GTFS/street routing, exact bus pickup points, nationwide discovery and production auth remain later integrations with real access/data. The core does not claim superiority over existing LLMs or a national optimum.

Final fixes so far (each observed RED then GREEN): corrected Coimbatore KSRTC386 mapping and date format; rejected route-mismatched train cards; stable flight service identity across refreshed offer IDs; rejected disconnected flight slices; restored original rounded-10.border-b train card shape. SQLite connection cleanup was fixed after a Windows file-lock regression exposed it.

Review fix pass: explicit persistent travel progress with locked prefix, preserved prefix fares and conservative stale-arrival handling; route-inclusive identity; retained partial flight offers and date coverage warnings; due-watch claims with lease/token and atomic event finish; Secure HTTPS cookies; Android nonstream data mode and release key exclusion. Backend suite74/74 passed after final sold-out/owner-bound watch-history regressions; Android tests3/3 and debug package passed. See BUILD_STATUS.md for evidence boundaries.

Follow-up review corrections: handle locked final leg and egress as a complete pending journey; anchor future discovery/default recovery horizon to current time, preserve explicit arrival deadline and explain elapsed deadlines; reject refreshed future departure for an onboard service; hash known partial fare and departure changes in watch snapshots. Regression cases observed RED then GREEN; aggregate79/79 passed. No third review cycle or unrelated changes.

Ruling: keep the current workspace changes uncommitted and reviewable as agreed in the execution ruling; no additional branch integration/push/deployment menu is needed for this local backend task. Application design and release/account integration remain user decisions.
