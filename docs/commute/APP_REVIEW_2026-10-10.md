# Sahara app review and UI proposal — 10 October 2026

This earlier proposal records an earlier review state. For the implemented central-server UI and current verification, see [the central-server runbook](CENTRAL_SERVER_RUNBOOK.md) and [delivery evidence](CENTRAL_SERVER_VERIFICATION.md).

## Intended outcome

Understand the existing application, correct observed failures, deliver a minimal Kotlin/Compose interface and build it before expanding the backend. Preserve the existing uncommitted work. The first UI pass changes existing screens; it does not introduce a new backend subsystem.

## What actually runs

The Android entry point is `MainActivity` → `SaharaUserNavGraph`. Home submits a message through the shared `ChatViewModel` and navigates to `ThinkingScreen`. That ViewModel calls `AgentRepository` → `AgentApiClient`.

`COMMUTE_BACKEND_URL` selects the Python backend when nonempty; otherwise the client selects Supabase Edge Functions. Current local configuration selects `http://10.0.2.2:8787`, sample mode, with no configured Supabase URL or public key. This address reaches the host from an Android emulator; a physical phone requires another reachable address. A running local server is also required.

The Python service resolves places, collects provider observations through a bounded broker, validates journey connections in a deterministic planner and stores searches/journeys/watches in SQLite. Its API exposes typed search, stored search polling, revalidation and watches, plus a chat compatibility stream. These operations are not equivalent to simply changing a Supabase project URL.

The repository also contains two Android agent implementations (`agents/` and `framework/`), local algorithms, and older Supabase orchestration/functions/migrations. The main screen's query path uses the API repository; presence of these other implementations does not mean they all participate in that path. Retire or isolate unused paths only after their callers have been checked.

## Concrete findings

1. **Observed and corrected parser regression.** The saved KSRTC public-page fixture uses uppercase colon durations such as `7:45 HRS`. The card parser searched for lowercase `h`, passed no duration to chronology validation and rejected all three services. It also inferred the service name from unrelated adjacent text. The fix uses the existing duration normalizer and explicit service-code/type elements, retaining the older markup fallback. The existing regression failed before the fix and passed afterward.
2. **Authentication is unfinished.** `LoginScreen` proceeds after checking phone-number length without verifying OTP. Legacy initialization uses the same `0000000000` phone value for everyone. Neither constitutes per-user authentication.
3. **Legacy SQL is unsuitable as the new production baseline.** `docs/SUPABASE_USER_SCHEMA.sql` grants anonymous users unrestricted profile/history access. This is a source-file finding, not a claim that those policies are deployed remotely. Do not blindly copy these policies into a fresh project.
4. **Structured results are discarded.** `ProcessQueryResponse` accepts JSON journeys/search ID/mode, but `ChatViewModel` stores response text in `ChatMessage`. The main results screen does not render structured alternatives or its active map tool. Collection-completion and pending-provider fields are absent from the Android response model, so later inventory updates cannot currently be followed by the UI.
5. **Navigation and controls need repair.** Results receives a back callback but does not use it. Header actions and profile settings are placeholders. Home's search field advertises Search on the keyboard but handles Done. Sending while another request runs can clear the field even though the ViewModel rejects that message.
6. **Location handling is inconsistent.** Home requests permission on entry and requires both grants in its callback, although the service correctly permits coarse OR fine access. MapView has no explicit screen lifecycle cleanup, and location-driven updates recenter it repeatedly. Results input has no keyboard inset handling.
7. **Clear-chat cancellation is incomplete.** `clearChat()` clears state but does not cancel an in-flight query; a later response can repopulate the conversation. Stream errors are logged and replaced with a generic no-response message. Repository processing state is not reset on every exception path.
8. **Development sessions are temporary.** The new backend session is generated in memory in the API client. App restarts lose access to previous session-owned records. Production identity/session persistence remains work for the backend phase.
9. **Documentation has drifted.** The current flight provider explicitly disables external flight API calls. Older backend documentation describes Duffel collection as enabled. Fixture/parser tests do not establish live provider coverage.

## Proposed first UI pass — awaiting design approval

Keep the existing white, black and orange visual identity and Compose navigation. Simplify the existing Home and Results screens rather than introduce a new navigation system.

- Home: Sahara wordmark, short journey-planning prompt, accessible search field and submit button, optional map/location action. Request location when needed; allow manual queries without granting it. Preserve entered text during loading and configuration changes.
- Results: working back action, conversation, concise loading status, structured journey cards with departure/arrival, transport legs, known versus incomplete fares and visible sample/source warnings. Expand details on demand. Render the returned map only where useful, with its approximation caveat.
- Input: handle keyboard Search/Send, prevent duplicate submission, keep input when submission is rejected, support keyboard insets and clear error/retry states.
- State: retain returned journey metadata, cancel/reset in-flight requests safely, and reset repository processing state on success, error and cancellation.
- Placeholder profile actions: hide inactive actions or explain their availability accurately. Do not present verified account features until authentication exists.

This pass uses the current sample backend for integration. Detailed typed search controls, stored-search polling, trip selection/progress/watches and production authentication require a separate backend/interface design. A fresh project is not necessary to build this UI.

## Backend recommendation

Prefer a separate development Supabase project for the new Sahara account/data model. Keep the old project intact until its data and driver-app dependencies are understood. An isolated project avoids mixing new account-owned journey records with legacy bus/location tables, but it does not automatically fix duplicate code paths or authentication.

Retain the deterministic Python planner. Later replace or extend its storage boundary with Supabase Postgres, validate Supabase user tokens at the API, and authorize each record against the authenticated owner. Keep providers and model credentials server-side. Decide hosting separately: a Supabase project does not host this Python process merely by supplying its URL.

The connected Supabase account lists only another project; `wjivmumfwgmpjxfsfvqe` is not accessible through that connection. Its tables, policies, deployments and data have not been verified or changed. Connect the intended project's organization to the Supabase integration for inspection. For a new project, the project URL and client publishable key are sufficient for client configuration; authentication, schema and server hosting still need implementation. Never put server secret/service-role credentials into the APK.

References: [Supabase API keys](https://supabase.com/docs/guides/getting-started/api-keys), [Realtime authorization](https://supabase.com/docs/guides/realtime/authorization).

## Verification

- Backend before fix: 95 tests, one failure in the public-page bus fixture regression.
- Backend after fix: 95/95 passed, 28.677 seconds. Includes planner, owner isolation, providers, progressive results, recovery and watch regressions; it does not establish current live inventory coverage.
- Android baseline: debug assembly was accepted as up to date; all three Android stream-frame unit tests were freshly rerun and passed. The combined check failed at lint because five required lint/compiler libraries are unavailable in the offline cache. The earlier online attempt stalled on network reads and was stopped. Static analysis is unverified; this is a dependency-access limitation, not a clean lint result.
- Separate Android build/test check: `:app:testDebugUnitTest :app:assembleDebug --offline --no-daemon` completed successfully in 45 seconds. APK: `app/build/outputs/apk/debug/app-debug.apk` (existing baseline UI, not the proposed UI). SHA256: `E4493C15301E45C59E07F7C323F9F9775CA344C74A18A932976842691718371B`.
- New UI: not implemented; design approval is pending.
- Physical device, emulator interaction, live Supabase schema and production release: not verified in this pass.

No remote schema changes, deployment, commits or external messages were performed.
