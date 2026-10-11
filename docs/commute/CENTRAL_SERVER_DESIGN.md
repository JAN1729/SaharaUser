# Sahara central server and first Android build

10 October 2026. User direction: multimodal public mobility, scraping public websites only for external transport data, central computer runs the collectors and server, Kotlin application is the interface. Complete the existing logic and minimal interface before expanding production backend implementation.

## Selected boundary

Android → central FastAPI server → public HTML/rendered DOM collectors → normalized observations → deterministic connection planner → SQLite. The original travel bot is a reference for collection and station matching, not a module to import: its Telegram/global state and credentials do not belong in the passenger app. Browser site scripts may make their ordinary requests; Sahara reads DOM and does not call transport APIs or consume intercepted JSON.

Keep the existing chronological planner, provider broker, search ownership, cache, revalidation and watch worker. Fix observed defects at their boundaries. Add no invented services or road geometry. Sample results remain explicitly sample. Timetable evidence does not certify real-time vehicle position or ticket availability.

## First build scope

- Repair KSRTC split time/place markup and enable regular ConfirmTkt browser rendering when configured.
- Include provider statuses, terminal names, collection progress and warnings in Android's response contract. Provide owner-bound polling of the same search.
- Cancel in-flight work when clearing/switching connections; reset processing state and surface server failures.
- Persist a random server session and runtime server address/key in encrypted preferences; exclude preferences from backups.
- Minimal Compose Home and Results; a working connection screen, sample/live choice, health check, structured journey cards, transfer margins, evidence timestamps and safe source links. Manual queries work without location permission. Preserve black/white/orange branding, use native type, left alignment and generous spacing; orange is reserved for primary actions.
- Central-server launcher supports LAN binding, installed Chrome, persistent storage and access key. Document local phone connectivity and deployment limits.

## Supabase decision

Read-only inspection found the supplied SaharaDriver project healthy, with no vehicles or route stops, 76 location records, and permissive passenger policies. No remote changes in this phase. A new passenger Supabase project is recommended for later production authentication and account-owned persistence. SQLite is the authoritative store for this local server build; do not imply cloud synchronization. Driver telemetry can remain a separate opt-in source. It cannot be inferred from scraped train/bus pages.

## Next backend phase

Verified metro/local timetable sources, broader verified station mappings, a real source geometry dataset, production authentication, HTTPS, account-owned cloud storage, retention and operations. An optional server-only OpenRouter request interpreter has now been added with a default DeepSeek V4 Flash model; the supplied key authenticated and a live extraction check passed. It is encrypted locally and never packaged into Android. The current central server is a development/pilot system, not an authenticated public service. OpenRouter is language interpretation only; transport inventory remains scraped and planner-validated.

## Latest user decisions

The product is a chatbot/concierge, with progress summaries, fare/route comparisons and full journey map guidance. Public-source collection is scraper-only; no ticket booking in this phase. The user explicitly declined building a driver version; new driver endpoints and their implementation were removed. Driver telemetry remains a separate possible source when available. The user approved `sahara-ui-concept-v1.png` on 10 October 2026 and authorized Kotlin implementation. The approved chat, progress, compact comparison and OSM map layout is now implemented; example prices and fake illustration shapes are not imported into live results. See `APP_UI_VERIFICATION_2026-10-10.md` for current build/device evidence.

Map backend now supports source graphs and service-specific Dijkstra paths, includes access/transfer/egress and preserves sample/live provenance. Without a verified configured dataset it reports missing geometry and returns no fabricated polyline.

## Execution and checks

1. Reproduce existing backend/Android checks and targeted boundary regressions.
2. Repair server collection/response boundaries; retain source provenance.
3. Connect persistent Android transport and partial-search polling.
4. Build the minimal interface and debug APK.
5. Run backend suite, Android unit checks, lint, actual HTTP smoke and bounded live-source probes. Record separately whether a device/emulator was available.
