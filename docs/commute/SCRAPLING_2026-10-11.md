# Server-side Scrapling integration

Purpose: reuse the existing central scraping/planning pipeline while evaluating the user's proposed Scrapling library. No Kotlin, driver-app, map layout or Android artifact change was needed.

Implemented an optional Scrapling0.4.8 static HTML transport with reusable asynchronous sessions, at most three simultaneous HTTP requests,12-second deadlines, one attempt per request and lifecycle cleanup. The original HTTP transport remains selectable/fallback. Existing browser DOM rendering remains Playwright; changing HTTP libraries alone cannot remove JavaScript rendering costs. Transport returns the original HTML contract to the existing route/date/availability/fare parsers.

Actual public homepage measurements, two requests per transport, sequential order:

| Source | httpx median | Scrapling median | Result |
|---|---:|---:|---|
| ConfirmTkt |0.852s |0.808s |Both returned118,545 HTML characters |
| KSRTC Swift |4.324s |2.023s |Both returned151,846 HTML characters |

ConfirmTkt's warm second Scrapling request was0.196s; its cold request was1.420s. Network/server warmth and response variability affect these results. This is not a benchmark of complete multimodal journey searches, or proof of available fares/seats. No transport inventory API was called.

Verification:169-test mobility suite passed in17.922s after the change; seven targeted collector/configuration checks passed in0.185s (five overlap the full suite). Dependency dry-run resolved without changing the installed environment or downgrading Playwright. Tests cover session reuse/cleanup, pre-network host rejection, redirects/challenges, hidden script markers, response size, missing optional package fallback and invalid configuration.

During validation, an existing search-enrichment timing race reproduced: provider callbacks could finish just before the aggregate task, allowing an incomplete flag with no pending providers. The server now resolves that aggregate before saving final flags; the previously failing enrichment suite then passed7/7. Other dirty workspace changes were preserved.

The phone application may be closed while server work continues. The hosting computer/server must remain on. This change neither hosts the backend in the cloud nor creates background watches/push alerts. Existing APKs remain valid; restart the local server to load the new transport. Its health reports the chosen static and browser engines.

The local server was restarted and its health confirmed `http_collector: scrapling` and `browser_collector: playwright`. The API smoke check passed: five sample journeys, three recovery alternatives, complete chat response, watch cancellation and rejection of an unkeyed live request. This smoke check uses sample transport data; the separate public-page measurements above verify actual HTTP collection.

Sources: [Scrapling project](https://github.com/D4Vinci/Scrapling), [official session documentation](https://scrapling.readthedocs.io/en/latest/fetching/static.html). The installed package API and actual page responses were checked in this workspace.
