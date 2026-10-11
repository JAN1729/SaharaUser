# Core backend delivery — 2026-10-09

Historical snapshot. Current status: [11 October delivery](DELIVERY_2026-10-11.md) and [current build verification](APP_UI_VERIFICATION_2026-10-10.md). Earlier flight API recommendations in this file are superseded.

The product remains the Sahara application. The owner controls application design, screens and user flow. The browser page is labelled **backend diagnostics / development only**. Work in this change is the backend core and Android connection plumbing.

## What exists in code

| Area | Delivered |
|---|---|
| Data contracts | Typed terminals, source observations, dated service legs, party/per-person fares, seat states, requests, transfers, journeys and provider outcomes |
| Planning | Bounded chronological search, explicit station/bus/airport transfers, airport processing, complete deadlines, confirmed availability, group seats, known-cost budgets, mode/accessibility constraints and multiple useful alternatives |
| Collection | Isolated ConfirmTkt/RailYatri, Kerala KSRTC Swift, live domestic Duffel, normalized server-owned local feed, and strictly separate illustrative sample adapters |
| Performance | Limited provider fan-out/dates, HTTP/browser concurrency caps, provider deadlines, shared duplicate requests, short cache respecting offer expiry, partial/error/timeout statuses |
| Durable API | Search, stored journeys, revalidation, place/provider configuration endpoints, local SQLite WAL persistence and session-bound reads |
| Recovery | Refreshed future alternatives; explicit completed/onboard progress with a locked travelled prefix; no assumption that the traveller boarded or arrived; originally selected prefix fares retained |
| Monitoring | Opt-in watches, due claims with crash-recovery lease, atomic event/finish writes, meaningful-change deduplication, concurrent stop preservation; no external messages |
| Existing Android | New backend URL/data mode, incremental event frames, optional structured journey fields, legacy Supabase initialization skipped in new mode, debug-only local HTTP; no new screen/layout/navigation |
| Packaging | Python package, environment template, schema reference, container recipe, source/API probes and setup/API documentation |

Entry points: `backend/sahara/api.py`, `planner.py`, `service.py`, `providers/`, `worker.py`. Setup and the mobile contract are in `backend/README.md`. The SQLite bootstrap mirrors `backend/schema.sql`.

## Verification evidence

Observed checks (final results below):

- Backend: 61 tests passed; subsequent review fixes passed 70 tests, with two further onboard regressions observed failing and then passing. Final aggregate count is recorded below when completed.
- Android: `:app:testDebugUnitTest` passed, three stream-frame tests with zero failures; `:app:assembleDebug` passed. Final build after the last transport/config fixes is recorded below when completed.
- Actual HTTP sample flow: search200, five alternatives for two passengers, chosen connection invalid after a180-minute simulated delay, three recovery alternatives, four compatible chat frames ending in a successful final response, and unkeyed live401. Combined local request sequence took0.618 seconds in that run; this is a sample-mode smoke measurement, not a live-provider latency benchmark.
- Wheel built successfully and contains20 Python modules plus packaged diagnostic HTML.
- Python compilation and tracked diff whitespace check passed.
- Browser confirmed diagnostic-only wording, usable terminal inputs and hidden live-key field in sample mode. Earlier sample search rendered journey alternatives; no product design approval is implied.

## Live source boundary

Credential-free public probes on2026-10-09, including ordinary browser rendering, produced **zero verified inventory rows**. HTTP-only rail and bus results reported unknown inventory; browser rail probes hit bounded search timeouts and bus probes parsed no verified cards. The original train-monitor selectors/date conventions were checked and corrected, but passing parser tests do not prove current source operation. A browser timeout also emitted a Playwright target-closed diagnostic during cleanup.

No production flight token was supplied or used. Flight tests use isolated documented-shaped offers and reject test-mode, expired, non-INR, non-domestic or disconnected offers. No ticket purchase, booking, cancellation, deployment, external notification or paid API request occurred.

Local transit is a normalized feed adapter plus estimated access/egress links. No actual local feed, GTFS import, GTFS-RT service, live traffic, train running-position source, exact platform/boarding point, baggage calculation or nationwide inventory is connected.

## Review and corrections

A separate read-only whole-change reviewer found and the fix pass addressed:

1. Revalidation stopping after departure: explicit persistent travel progress and locked-prefix recovery; progress updates are required when location/arrival cannot be inferred safely.
2. Shared Android gateway key in releases: release BuildConfig forces the demonstration key empty. Production account authentication is still a separate requirement.
3. Undisclosed date truncation: explicit partial coverage warnings; flight collection now checks up to two service dates.
4. Wrong corridor considered the original service: revalidation identity now includes origin/destination.
5. Successful flight queries lost after another failed: partial results are retained with errors disclosed.
6. Monitoring beyond200 recent watches ignored: individually claimed oldest due watches, bounded fair batches, crash-recovery leases and atomic finish/event writes.
7. Non-streaming Android silently using sample mode: both transports use configured mode.
8. Insecure HTTPS session cookie: Secure flag follows HTTPS; HTTP remains local development only.

Earlier RED-to-GREEN regressions corrected the KSRTC Coimbatore city code, bus date format, mismatched train routes, original train-card selector, flight service identity across offer refreshes, disconnected flight slices, final transfer costs/time, and SQLite connection file locks on Windows.

## Limits and next access

This is a runnable, bounded local planning core. It is not a production release or proof that Sahara outperforms every travel assistant. It does not depend on the inaccessible Supabase instance; no Supabase migration or remote state was changed.

Highest-value next gates:

1. Verify actual train and Kerala KSRTC inventory for selected corridors, including exact boarding stops. If public rendering remains unavailable, obtain an authorized integration rather than disguising fixtures as live data.
2. Supply a production Duffel token and verify real domestic offers/refresh, without booking. Flight provider coverage/commercial access remains unverified.
3. Supply authorized live train-running and local transit feeds, then test real disruptions and missed connections.
4. Define the application screens and connect typed journey/progress/watch operations to them; test on a physical Android device.
5. Add production account authentication, HTTPS hosting, session persistence, per-account limits, retention, telemetry and deployment validation.
6. Integrate a constrained server-side language model interpreter/explainer. The present compatibility parser handles only limited route/date/budget refinements; it is not a general conversational travel agent.

## Distinction to validate

Multimodal discovery, travel chat, boarding guidance and disruption support already exist. The useful hypothesis from the source research is **India-specific complete-journey evidence and recovery**: show why a connection is feasible, what cost is missing, which source is stale, and what onward option remains after a disruption. Reddit reports informed these priorities but do not establish demand size or uniqueness. See the cited evidence/competitor boundaries in `MULTIMODAL_BACKEND_PROPOSAL.md`.

Measure this on real corridor tasks before claiming advantage: feasible-bookable journey rate, complete fare coverage, correct pickup point rate, deadline success under observed delays, false disruption alerts and useful recovery options. Deterministic tests validate calculation rules; they do not measure those product outcomes.

## Final verification

- **Backend: PASS79/79**, `python -m unittest discover -s tests -q`, final run29.059 seconds, zero failures/errors. Final regressions covered sold-out services when tentative options are allowed, watch-specific history beyond other watches' recent events, final-leg/egress recovery, date rollover, explicit deadline preservation, contradictory onboard departure and partial-fare/departure watch changes.
- **Android: PASS**, `:app:testDebugUnitTest :app:assembleDebug --no-daemon`, final build2m39s; stream tests3/3 with zero failures/errors. Existing SDK XML and Gradle deprecation warnings remain. No physical-device or release installation was performed.
- **Actual HTTP: PASS**, final smoke returned search200 with five sample journeys, disrupted connection invalid and three recovery alternatives, successful chat final frame, unkeyed live401, diagnostic page200, and watch creation/polling/stopping successful. Combined sequence1.117 seconds; sample-mode only.
- **Packaging: PASS**, wheel rebuilt with review fixes and packaged diagnostic HTML; container recipe is supplied but no Docker build/deployment was run.
- **Source/provider gates: OPEN**, zero verified live train/bus inventory in public probes, no production flight token, no live local-transit or train-running feed.
- **Review: performed**, original and follow-up concrete findings addressed with regressions observed failing and then passing. Follow-up fixes preserve a pending final arrival/egress, anchor future recovery dates to current time while keeping explicit deadlines, reject contradictory future onboard departures, and include known partial fares/departures in watch signatures.
- **Repository: local reviewable changes only**, no commit, push, purchase, external message or deployment. Original/reference train-monitor code was preserved.
# Historical backend build snapshot

This file records an earlier implementation stage. For the current webpage-only collectors, approved Kotlin UI, existing driver connection and trip companion, see [current delivery](DELIVERY_2026-10-11.md) and [current verification](APP_UI_VERIFICATION_2026-10-10.md). Earlier flight API recommendations below are superseded and do not describe the current runtime.

