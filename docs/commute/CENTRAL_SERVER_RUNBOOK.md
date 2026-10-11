# Run Sahara on a central computer

This build connects the Kotlin app to a central Windows computer. The server collects public HTML or rendered DOM, normalizes observations, validates connections and persists searches/journeys/watches in SQLite. It never calls transport inventory APIs. It can call OpenRouter separately to interpret language when its server key is configured.

Jev now uses the same protected OpenRouter key for narrow structured decisions. See [Jev integration and evaluation](JEV_2026-10-11.md) for matching, notice-ingestion endpoints, conservative rollout controls and measured limitations. No new Android installation or TypeSafe credential is required. Semantic-only matches default to review; vehicle assignments, fares, routing and geometry remain controlled by existing evidence and algorithms.

## Start the computer server

Use Python 3.12+ and installed Chrome or Edge. From `C:\Users\Admin\Sahara\backend`, install dependencies once if needed:

```powershell
python -m pip install -e '.[browser,scrapling]'
```

Start for an emulator on this computer:

```powershell
.\start-central-server.ps1 -Background
```

Start for phones on the same reachable LAN:

```powershell
.\start-central-server.ps1 -ListenAddress 0.0.0.0 -Background
```

Run only one server per port. If a server is already running, inspect the process ID in `backend\data\server-process.txt` and the command in Task Manager before stopping that specific server. Restart after source changes or server environment changes; the launcher does not auto-reload.

- Health: `http://127.0.0.1:8787/health`. `status: ok` confirms the server is reachable, not that every source works.
- Diagnostics: `http://127.0.0.1:8787/`. This is a development page, not the passenger product.
- Database: `C:\Users\Admin\Sahara\backend\data\sahara.sqlite3`.
- Access key: `C:\Users\Admin\Sahara\backend\data\server-access-key.txt`. The launcher generates this once; it does not print it. Keep it private and enter it in the app’s Server connection screen. This is a pilot access key, not an OpenRouter key.
- Logs: `backend\data\server.stdout.log` and `server.stderr.log`.
- Process ID: `backend\data\server-process.txt`.

Keep the computer awake. For LAN access, permit the server through Windows Firewall on the intended private network; no firewall settings were changed by this work. A phone cannot reach the computer using `127.0.0.1` or `10.0.2.2`. Public internet operation needs an HTTPS endpoint and proper account authentication before it is a production service.

## Scrapling and running without the passenger app

The passenger APK performs no web collection. The Python server runs separately in the background, so closing Sahara on the phone does not stop server collection. The computer hosting the server must remain awake/running. If both phone and computer should be off, host this backend on an always-running remote server; a scraping library alone does not provide hosting.

`SAHARA_HTTP_ENGINE=auto` selects the installed Scrapling0.4.8 HTTP session, otherwise falls back to httpx. Set `httpx` to retain the previous static transport or `scrapling` to require Scrapling explicitly. `/health` reports `http_collector` and `browser_collector`. The optional HTTP extra avoids Scrapling's full fetchers bundle, which pins Playwright1.59 and conflicts with Sahara's1.60 requirement.

Scrapling reuses asynchronous HTTP connections for static HTML. Existing browser-enabled train/bus/flight collectors still use pooled Playwright rendering; this change does not claim those JavaScript journeys became faster. Source host checks, size limits, challenge detection and route/date/fare validation remain. No provider inventory API, CAPTCHA solver, stealth browser or adaptive fare guessing was enabled.

Measured two-request public homepage samples: ConfirmTkt median0.852s with httpx versus0.808s with Scrapling; KSRTC Swift4.324s versus2.023s. All returned HTML. These small sequential measurements are not a general speed guarantee or verification of dated inventory. Reproduce from backend with `python -m scripts.benchmark_collectors`; see [Scrapling's HTTP/session documentation](https://scrapling.readthedocs.io/en/latest/fetching/static.html).

The current companion UI follows journeys while the screen is open. Server-side background journey watches are a separate opt-in worker; this scraping transport change does not enable Android background/push notifications or automatically create watches.

## Connect Android

Install `C:\Users\Admin\Sahara\app\build\outputs\apk\debug\app-debug.apk` (development build, application ID `com.sahara.user`, version `1.0.0`, code `1`, Android 8/API 26+).

1. Open Sahara → settings icon → Server connection.
2. Emulator address: `http://10.0.2.2:8787`. Phone address: `http://<computer-LAN-IP>:8787`.
3. Choose Demo for illustrative schedules/fares/seats, or Live web data and enter the server access key.
4. Select Save and check connection, return to Home, enter the origin/destination/date.
5. Confirm the result’s data mode, source status, fetched time, unknown upstream update time, fare completeness and transfer margin. Partial sources can update the same search for up to 40 seconds after the initial response.

The app retains a random per-server session in encrypted device preferences, excluded from cloud/device backup. App restart preserves server ownership but the conversation itself is not restored. A new conversation starts without the previous route context. Changing the connection cancels current requests and clears results.

## OpenRouter and DeepSeek

The central server accepts `OPENROUTER_API_KEY` and `OPENROUTER_MODEL` as environment variables inherited at startup. The supplied key has been stored in a Windows-account-encrypted DPAPI file under the ignored backend data folder; the launcher decrypts it into the server process environment without printing it. Default model: `deepseek/deepseek-v4-flash`; change the model variable for another compatible lower-cost model. Confirm the chosen endpoint supports JSON Schema before switching. [OpenRouter structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs) and [DeepSeek V4 Flash](https://openrouter.ai/deepseek/deepseek-v4-flash).

The `.env.example` is a configuration reference; `.env` is not automatically loaded. Set keys privately in the process environment before launching, then restart the server. Never enter an OpenRouter/provider secret in Android or `local.properties`. The Android access key is only the local Sahara gateway credential.

Language calls run only for authorized Live chat requests. Unkeyed Demo requests never consume the paid model. The interpreter can output only known terminal IDs, dates, passengers, modes and preferences. Schema, place mapping and planner validation still apply. Explicit English numeric budgets, party size and only-mode restrictions override model output so these limits cannot be dropped. It cannot output inventory, booking claims or tool actions. On a gateway error, basic route/date parsing remains available. A live key check returned 200 and DeepSeek extraction passed a two-passenger, bus-only, INR1500 constraint check. This proves the language adapter, not source inventory availability.

## Coverage and remaining backend work

The 22 seed terminals cover selected Kerala and intercity rail/bus/airport locations, including Mumbai CSMT/LTT; city aliases do not mean neighborhood or nationwide coverage. No Kerala-only geographic restriction is imposed. Coordinates remain approximate seeds. Unknown places request a supported terminal. Metro, ferry, broad local timetables and live train positions are not connected. Flights now use the shared public-page collector to parse dated Google Flights rendered cards; there are no flight inventory API calls. Sample flights remain clearly illustrative.

The supplied existing driver repository is reused through a read-only Supabase adapter; no replacement driver app/backend is being built. The locally protected `backend/data/driver-connection.dpapi` provides the URL and legacy public read key to the launcher. Alternatively set `SAHARA_DRIVER_URL` and `SAHARA_DRIVER_READ_KEY` privately before starting. The current project returns an empty registered fleet. Only active registered vehicle locations are read; no driver IDs or unrelated personal location records are displayed or copied. Raw GPS does not establish fares, seats, timetable or ETA.

Map guidance is available at `GET /v1/journeys/{id}/map` with the owning server session. It carries sample/live provenance, service legs, access/transfer/egress segments, approximate terminal pins and explicit geometry completeness. Configure `SAHARA_ROUTE_GRAPH` with a server-owned JSON graph containing `source_type`, `source_url`, `revision`, `nodes` and directed `edges`. Edges include actual source polylines and optional `service_routes` keys such as `rail:12677` or `ksrtc:0700KKDBNG`. Dijkstra uses measured polyline length, respects mode/direction and requires matching service keys for service legs. An unrelated shortest road/rail path cannot be attached to a service. No actual geometry dataset is installed yet, so missing paths return no polyline rather than straight endpoint lines. The user approved the image on 10 October 2026. The Kotlin UI now fetches this map contract, renders valid polylines and approximate pins, and labels unmapped legs. See APP_UI_VERIFICATION_2026-10-10.md for current checks.

On 10 October 2026 a bounded rendered-DOM probe parsed 17 KSRTC legs for Kozhikode–Bengaluru across the checked dates. Their evidence is cached; upstream age, exact pickup/drop points and final fees remain unverified. Train pages timed out with incomplete coverage. Neither a parser test nor a successful page fetch proves seats will remain available when booking.

The watch worker is separate (`python -m sahara.worker` with the same database/server environment). No watch worker was started automatically and no external notifications are sent. The Kotlin UI now exposes Follow this journey, owner-bound guidance, explicit boarding/completion, foreground revalidation and selectable recovery alternatives. Updates pause in the background and stop on navigation. Backend watch events are separate from Android push alerts.

## Supabase recommendation

Use a new passenger Supabase project for production account authentication/storage. The supplied `SaharaDriver` project was inspected read-only: healthy, no vehicles/route stops, 76 location rows, permissive passenger profile/history/location policies. Leave driver telemetry separate until its owner/auth model is verified. Do not copy the old anonymous policies into the passenger project.

This computer build stores passenger journeys in SQLite and only reads the existing driver schema. It does not write to or synchronize Supabase. A project URL alone does not implement passenger account authentication or hardened fleet policies. [Supabase RLS documentation](https://supabase.com/docs/guides/database/postgres/row-level-security).

## Verification commands

From backend: `python -m scripts.test_mobility`; with the server running, `python -m scripts.smoke_api`. Broad unittest discovery also runs unrelated legacy visa API probes; the mobility runner excludes those.

Live source probes: `python -m scripts.probe_sources --browser --corridor kozhikode` and `python -m scripts.probe_flights --browser`. Set `SAHARA_BROWSER_EXECUTABLE` to installed Chrome/Edge if running outside the launcher environment.

From project root: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon`.

Before public launch: verified corridor inventory, metro/local sources, live OpenRouter check, authenticated HTTPS/account-owned storage, operations/retention, and actual device tests remain separate gates.

The live OpenRouter gate passed for the bounded extraction above. See [current delivery](DELIVERY_2026-10-11.md) and [current verification](APP_UI_VERIFICATION_2026-10-10.md). Source coverage, real geometry and physical-phone checks remain separate gates.
