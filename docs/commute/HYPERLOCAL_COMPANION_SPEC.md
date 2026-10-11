# Kerala mobility companion extension

## Authorized direction

User requested a companion throughout travel, affordable/reliable/convenient choices, boarding guidance, approaching vehicles, connection checks and recovery alternatives. Latest clarification: coverage should span Kerala rather than one locked corridor, include verified intercity rail/bus and webpage flight scraping, and reuse https://github.com/JAN1729/Sahara for the basic driver integration. Do not create another driver backend or driver application.

## Architecture

Keep the local central server, existing SQLite journey storage, OpenRouter constraint extraction, deterministic planner, and approved Kotlin chat/map layout. Add webpage-only flight collection, passenger journey guidance using existing progress/revalidation/watch logic, and a read-only adapter to the existing driver backend when its schema and credentials are confirmed. Public transport inventory comes only from webpage/DOM collectors; the user's own driver backend may supply fleet telemetry directly. Never label timetable data as vehicle GPS.

## Contract boundaries

FlightProvider(enabled=False,browser=False,collector=None) uses the shared public-page collector, never provider inventory APIs. Preserve explicit dates, endpoints, fares/currency, quotation provenance, booking source and unsupported status. Scraping challenges and missing dates/fare classes are visible failures, not invented offers.

build_guidance(record,now,fleet_observations=None) consumes a stored journey and optional observations keyed by leg ID. It returns journey_id/data_mode/status/updated_at, current_leg_index, boarding, next_connection, alerts, vehicles and requires_progress. Unknown or stale vehicle observations cannot produce approaching/live claims. Boarding seed coordinates are approximate; exact boarding instructions require verified source evidence. Connection feasibility includes travel, processing and buffer. No automatic assumption that a passenger boarded because the clock advanced.

The central API supplies owner-bound guidance and trip progress/revalidation. The Android journey card exposes start following, progress confirmation, refresh and recovery choices inside the approved chat layout. Follow only the selected journey; polling stops on navigation/background/cancellation. Backend watches are separate from guaranteed phone push notifications.

Kerala-first means truthful source coverage, not hard geographic rejection of supported intercity journeys. Preserve unsupported-source and geometry disclosures. Comfort/convenience preferences should favor feasible lower-transfer/less-walking journeys using known data; do not fabricate amenities, traffic, vehicle occupancy or exact boarding points.

## Verification

Public DOM snapshots and strict route/date/fare tests; guidance chronology/stale GPS/progress tests; existing API/owner isolation/revalidation suite; Android contracts, maps, keyboard, build; driver repo inspection/build separately. A runnable debug APK is distinct from live source/operator readiness and production release.

## Execution ownership

- Flight collector work: provider/collector and new flight webpage tests only.
- Guidance work: pure backend guidance module and its tests only.
- Main integration: driver reference inspection, existing service/API integration, preference parsing, Android trip following, documentation and final verification.
- Existing dirty files and credentials are preserved; no remote deployment or changes to the driver's backend.
