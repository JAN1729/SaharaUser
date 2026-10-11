# Sahara central-server delivery evidence — 10 October 2026

Historical snapshot before the approved UI and companion extension. Current status: [11 October delivery](DELIVERY_2026-10-11.md) and [current build verification](APP_UI_VERIFICATION_2026-10-10.md).

Current scope: Kotlin passenger chatbot interface, central computer performs public webpage/DOM collection and deterministic journey planning. Optional server-only DeepSeek/OpenRouter request interpretation is connected. The user explicitly declined building a driver version. The user requires image approval before implementing the proposed chat/progress/map screen design.

## Confirmed checks

| Check | Observed result |
|---|---|
| Backend full suite | **111 tests passed**, final run 21.706s, zero failures/errors |
| Android unit checks and assembly | **BUILD SUCCESSFUL**, final run 2m20s, six unit checks across three suites, zero failures/errors |
| Actual local HTTP demo flow | Search200, five illustrative journeys, invalidated delayed connection, three recovery alternatives, successful stream final response, unkeyed live401, watch stop and diagnostic200; final sequence0.318s |
| OpenRouter | Supplied key authentication200; live DeepSeek V4 Flash extraction preserved two passengers, bus-only mode and150000 paise budget |
| Public browser bus probe |17 parsed KSRTC legs across checked Kozhikode–Bengaluru dates; cached evidence, unknown upstream age, pickup/drop and fees unverified |
| Live server bus search | Scraped observations retained; no bookable journey for the checked window after sold-out, booking-not-open and date/window exclusions. This is not a live-trip success claim |
| Map routing | Fixture graph tests verify measured distances, directed traversal, mode filtering, service-key requirements and missing/invalid geometry rejection. Map API carries sample/live provenance and access/transfer/egress segments |
| Repository whitespace | `git diff --check` passed |

The independent review was read-only. Confirmed corrections include the Playwright timeout class, redirected-host validation, orphaned search recovery only on API lifespan startup, actual live-key checking, sample/live map provenance, complete connection mapping, service-specific graph filtering and measured shape distance. Client clear/switch cancels display work; shared server collections intentionally finish under bounded deadlines so one client cannot cancel another client's shared inventory fetch. No external notifications or ticket transactions occur.

## Runnable artifacts

- APK: `C:\Users\Admin\Sahara\app\build\outputs\apk\debug\app-debug.apk`
- Application ID `com.sahara.user`, version1.0.0/code1, minSdk26, debug signed.
- Size22,193,879 bytes.
- SHA256 `12132526BF5C5BD5B7E73ACE6F7FF3F6FBF49CD265966995D52BD5B5D87400C5`.
- Server launcher: `C:\Users\Admin\Sahara\backend\start-central-server.ps1`.
- Current server started by this work: process15820, listens on0.0.0.0:8787. Local HTTP smoke passed after final restart; phone/LAN reachability can still depend on firewall/network.
- Persistent local SQLite and encrypted server credential are in ignored `backend\data`; keys are never included in Android.
- Design image: [sahara-ui-concept-v1.png](sahara-ui-concept-v1.png). It is a proposal using illustrative fares, times and map geometry, not a verified live journey or a screenshot of the built APK.

## Open gates

1. **Lint incomplete:** Android lint could not download five analysis artifacts from Google's official repository. Online Gradle downloads timed out; bounded direct transfer attempts also timed out. Compilation, assembly and unit checks completed separately; lint is not claimed passing.
2. **No device interaction:** no phone or emulator was attached. No actual UI interaction, GPS, accessibility/device install or release verification is claimed.
3. **Live coverage limited:** train queries timed out; metro, ferry, broad local/private mobility sources and live train positions are not connected. Supported mode types do not mean working source coverage. Source tests do not establish exhaustive India-wide or Bengaluru–Mumbai inventory.
4. **Real map dataset absent:** no route geometry graph has been installed. The engine will return missing geometry instead of a fabricated line. Actual service route keys, source shapes and boarding points must be verified before showing a route as mapped.
5. **Cloud production integration pending:** no remote Supabase writes, migration/deployment or project creation occurred. Existing `SaharaDriver` was inspected read-only; permissive passenger and fleet policies make it unsuitable as a blind new passenger production baseline. Production authentication, HTTPS/account-owned cloud storage, retention and deployment checks are pending.
6. **New UI design approval pending:** chat/progress/map layout in the image will not be implemented until the user confirms it. No new driver application or driver endpoints remain.

The core is a tested pilot foundation, not a claim that every real-time source, possible commute, map path or production flow works perfectly.
