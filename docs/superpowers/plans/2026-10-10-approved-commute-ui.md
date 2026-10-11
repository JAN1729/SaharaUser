# Approved Sahara commute UI Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement the approved Kotlin chat, progress and journey-map MVP.

**Architecture:** The central server owns inventory, planning and route geometry. Compose owns conversation presentation and selection; cancellation-scoped map requests use the existing session authentication. A lifecycle-managed osmdroid view renders validated server shapes and approximate terminal pins.

**Tech Stack:** Kotlin, Compose Material 3, Ktor, osmdroid, existing FastAPI server.

**Spec:** docs/commute/sahara-ui-concept-v1.png, approved by the user on 2026-10-10.

## Global Constraints

- White, black, orange #C84316, soft gray #F4F5F7, peach #FCEBDE; native sans-serif type.
- Chat-first layout with bottom pill composer, source progress, map and compact recommended card.
- No booking or driver application; sample fares remain explicitly illustrative.
- No fabricated polylines or transport inventory APIs.
- OSM attribution, identifiable user agent, cache and lifecycle management.

## Review Focus

- Invalid coordinates or unavailable shapes must never become misleading map lines.
- Switching routes, clearing conversation or leaving the screen must cancel obsolete map work.
- Unknown fares must remain incomplete rather than become zero-cost journeys.
- Late source updates must preserve valid route selection or recover when a route disappears.
- Long text, keyboard and large font must leave the composer and route details usable.

### Task 1: Map contract and request boundary

Files: data/api/JourneyMap.kt, AgentApiClient.kt, AgentRepository.kt, ChatViewModel.kt; test JourneyMapTest.kt.
- [x] Write and run failing map contract/coordinate tests.
- [x] Decode segments/pins/provenance, validate renderable geometry and fetch owner-bound maps.
- [x] Verify contract tests pass.

### Task 2: Approved chat interface and OSM map

Files: ui/components/CommuteChatComponents.kt, JourneyMapPanel.kt, JourneyResults.kt; HomeScreen.kt, ThinkingScreen.kt.
- [x] Implement greeting, suggestion chips, accessible composer and conversation bubbles.
- [x] Render real progress, compact route timeline, expandable comparison and evidence.
- [x] Render valid pins and mode-colored shapes with lifecycle cleanup, refit and attribution.
- [x] Build and run Android unit tests; inspect emulator if available.

### Task 3: Review and delivery

- [x] Obtain a bounded independent review of changed UI/map code and fix substantive findings.
- [x] Record verified APK, tests, screenshots and remaining source coverage limits.

## Execution ledger

Ruling: Preserve the existing dirty workspace in place — the approved MVP and backend are uncommitted here; creating a clean checkout would lose their integrated state. No commit, deployment or remote changes are requested.
Ruling: Do not add tests that only mirror styling — build and visual inspection cover reversible presentation; contract tests cover map correctness.

Map contract RED: missing types failed compilation; initial GREEN: nine tests passed. Full endpoints RED: missing connection/endpoints failed compilation. Review regressions RED: 14 tests, missing provenance and missing fare failed; GREEN: all 14 passed with full endpoints/transfer metadata retained.
Backend UI query RED: lowest fare interpreted as balanced; GREEN: 113 tests passed and HTTP sample ranked complete fare74000 paise first. Owner-bound map GET returned sample provenance; another session received404.
Ruling: The composer remains editable to draft the next message while searching; canSend controls only submission. This preserves useful drafting and accurate accessibility state.
Ruling: Map single-finger vertical gestures belong to conversation scrolling; two fingers or horizontal gestures belong to the map. A visible hint explains two-finger map interaction.
