# Approved UI and companion verification — current delivery 11 October 2026

The image was approved on 10 October. This record covers the subsequent Kotlin implementation and the Kerala/intercity companion extension. Feature-by-feature behavior and remaining data gates are in [the delivery report](DELIVERY_2026-10-11.md).

## Automated and local-server checks

| Check | Verified evidence |
|---|---|
| Current mobility backend suite | **164 passed**, zero failures/errors, 38.387s. `python -m scripts.test_mobility` excludes unrelated legacy visa API probes |
| Android contracts and build | **25 passed**, zero failures/errors,2m29s; debug assembly successful. Covers streaming frames, full endpoints, unknown fares/provenance, validated map coordinates/shapes, server addresses, gesture latch, guidance, explicit/reconciled progress, transport failures and unknown connection margins |
| Actual local HTTP smoke | Final server: Search200/five demo journeys, disrupted original invalid/three recovery choices, stream final response, unkeyed Live401, watch stop and diagnostics200;0.589s after tracking-order protection |
| Existing driver connection | Actual authenticated read-only endpoint returned `empty`, no vehicles. Source schema contains no registered vehicles/route stops. No driver records were created |
| Existing driver APK | Supplied repository built successfully in12m29s; debug artifact `com.sahara.driver`1.0.0/code1,22,719,937bytes, SHA256 `194E7A14F76EABEED32E73602AC19608AE69DD367203D6D222C2D7A1B0433BF5` |
| Public KSRTC probe | Earlier rendered probe parsed17 legs for Kozhikode–Bengaluru across checked dates; evidence cached, upstream update time/exact pickup/final fees unverified |
| Public flight probe | One earlier rendered COK–BLR probe parsed7 dated INR cards. Subsequent bounded probes timed out or parsed0 dated INR cards for the next travel date. Flight coverage is experimental and intermittent, not guaranteed live inventory |
| OpenRouter | Earlier authorized live extraction verified DeepSeek V4 Flash with passenger count, bus-only mode and budget preserved. Paid calls are server-side and disabled for unkeyed Demo requests |
| Geometry | Directed measured graph/mode/service-key and corrupt-shape regressions pass. No real service graph has been installed; unmapped legs remain approximate terminal pins |
| Review | Substantive findings fixed: missing fares/provenance, map gesture ownership, progress regression and atomic compare/write, explicit invalid-only repair, interrupted confirmation reconciliation, expiry, single active followed route, out-of-order tracking, refreshed-map consistency, completed boarding, missing transfer evidence and elapsed verified ETA |

The earlier broad discover run passed159 tests before final review additions; it included unrelated legacy visa probes. The current164-test mobility result above is the relevant final backend gate.

## Actual UI evidence

The approved UI was previously exercised in the API36 emulator: clean Home, sample lowest-fare query, actual streamed results, correct full endpoint title, OSM tiles/custom terminal pins/attribution, comparison and selecting a second route. Files: `sahara-home-implemented.png`, `sahara-journey-implemented.png`, `sahara-comparison-implemented.png`.

The first keyboard capture revealed window panning. The activity now uses `adjustResize`; final keyboard/companion inspection is recorded below. The previous `sahara-keyboard-implemented.png` is historical until replaced by that check.

Final installed APK interaction passed: Home suggestion populated the composer; submitting produced the correctly labelled demo740-INR train/bus journey with egress, real OSM tiles, custom pins and attribution. Software keyboard opened with the header pinned and composer immediately above the keyboard; the former panning/gap issue is resolved. Follow this journey loaded owner-bound planning guidance, approximate boarding terminal, departure, source warnings and the current revalidation message. Scrolling retained the active companion and its checked time advanced. Boarding, refresh and Stop following controls were visible. Exact GPS/arrival claims were not substituted into the demo.

Final screenshots: `sahara-home-final.png`, `sahara-journey-final.png`, `sahara-keyboard-final.png`, `sahara-companion-final.png`. These are actual emulator screenshots, not the design image. Earlier screenshots remain historical evidence.

Emulator installation initially failed because its data partition had only327MB free. Only the task's Sahara test installation was removed to free space. No unrelated app data or physical device was cleared.

The emulator also showed a System UI startup ANR during cold boot; after waiting, Sahara's actual interaction was responsive. Temporary display, software-keyboard and storage-reserve settings were restored afterward, and the owned emulator was stopped. This is not a physical-device performance claim.

Companion work is owned above the virtualized conversation so scrolling does not cancel it. Progress transport failures preserve pending intent; definite rejected responses clear it. Explicit confirmation already refreshes sources, so the app avoids an immediate duplicate source refresh.

## Final artifact identity and emulator result

Final artifact: `C:\Users\Admin\Sahara\builds\sahara-passenger-mvp-debug.apk` (same bytes as `app/build/outputs/apk/debug/app-debug.apk`), package `com.sahara.user`,1.0.0/code1,minSdk26,debug signed. Size22,467,530bytes; SHA256 `AEB22CC389A4C82BDDF5E6F7DD3080FDFDB65753C613C6EC51609257A2139FA6`. Functional tests25passed; final assembly after text-encoding normalization succeeded in2m50s. The final map-refresh failure notice was verified inside the packaged DEX, and ADB installation succeeded.

The existing driver build is also copied to `C:\Users\Admin\Sahara\builds\sahara-driver-reference-debug.apk`. Its hash matches the source build noted above.

## Separate open gates

Android lint had official analysis-dependency download failures and is not claimed passing. No physical-phone/LAN or driver GPS/background/owner-login verification is claimed. Source completeness, exact boarding, reliable live ETA, private/auto/metro/ferry inventory, live traffic, real service geometry, guaranteed Android push and production authentication/HTTPS/cloud/Play release remain separate. No cloud migration/deployment, booking, purchase, commit or push occurred.
