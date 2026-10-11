# Sahara phone test build

The debug APK is `builds/sahara-passenger-phone-debug.apk`, package `com.sahara.user`. It was updated on the connected Realme phone without clearing app data. The APK contains no saved gateway key; the connection key is stored through the masked Server settings field.

## Current connection

The phone uses `http://127.0.0.1:8787` through USB forwarding to this computer. Keep the USB cable connected and the central server running. After reconnecting USB, restore forwarding:

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" reverse tcp:8787 tcp:8787
```

Start the server from the Sahara folder if it has stopped:

```powershell
.\backend\start-central-server.ps1 -ListenAddress 0.0.0.0 -Background
```

The server loads the locally protected OpenRouter credential. Do not place provider or gateway secrets in the APK, source files or screenshots.

The phone's reported server error was traced to an empty saved gateway-key field: live chat returned HTTP 401. The valid computer-generated key was restored through the masked field. Its authenticated connection check passed, a live KSRTC search displayed options on the phone, and the key survived both a process restart and an APK update. The updated app now explains missing/rejected keys before or after a request, rather than displaying a generic server error. A demo connection check no longer claims that live credentials were verified. All 26 Android tests passed for this update.

## Test the chat

In Server settings, check the connection and choose Live web data. Try `Kozhikode to Bengaluru tomorrow, only buses` or `Kottayam to Bengaluru tomorrow`. These searches depend on the current public websites. A connected server is not evidence that a source has returned usable inventory. Fares, seats and journey options must retain their source and freshness information.

To check the interface when a website is unavailable, explicitly switch to Demo data and use `Kottayam to Bengaluru tomorrow`. Demo fares and services are fixtures, never travel recommendations. Switch back to Live web data afterward.

Choose an option and open Follow journey to inspect boarding guidance, connection checks and alternatives. Tracking is active while the screen is open. Nearby operator GPS is separate from a confirmed vehicle assignment; an empty fleet must not be shown as a moving service.

## KSRTC maps

The map supports operator-specific source geometry and separately labelled calculated road guidance. Calculated KSRTC corridors use dashed orange lines. They do not certify the operator's actual path, exact boarding bay, traffic or vehicle position. Missing geometry shows terminal pins and an unavailable notice rather than an invented line.

The public OSM extract download failed on this computer during this verification. No road dataset is enabled by this build. To prepare a real local graph when the connection permits downloads, run from `backend`:

```powershell
python -m pip install -e '.[maps]'
python -m scripts.fetch_osm_extracts
python -m scripts.build_osm_graph data/osm/kerala.osm.pbf data/osm/karnataka.osm.pbf data/osm/tamil_nadu.osm.pbf
$env:SAHARA_ROUTE_GRAPH = (Resolve-Path data/osm-road-graph.json).Path
.\start-central-server.ps1 -ListenAddress 0.0.0.0 -Background
```

Stop the existing owned server before starting a replacement on the same port. The builder requires matching source hashes, preserves road vertices and one-way restrictions, binds covered terminals within 500 metres, and validates the graph before publication. This is major-road guidance for the downloaded region, not complete nationwide bus geometry. Keep OpenStreetMap attribution and ODbL provenance with the dataset.
