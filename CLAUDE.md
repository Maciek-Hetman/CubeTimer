# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android speedcubing timer (Kotlin, Jetpack Compose, single `:app` module) that is being extended into an
offline-first client for a "CubeSync" backend (github.com/Maciek-Hetman/cubesync — `api/openapi.yaml` and
`docs/sync-protocol.md` are the wire contract; the server decodes bodies with `DisallowUnknownFields`, so
never send fields it doesn't declare). Supports GAN and QiYi Bluetooth timers.

## Commands

```bash
./gradlew assembleDebug                 # build
./gradlew testDebugUnitTest             # all unit tests (the project's gate — must stay green)
./gradlew lint                          # Android lint
./gradlew installDebug                  # install on a connected device/emulator

# single test class / method
./gradlew testDebugUnitTest --tests "com.maciekhetman.cubetimer.data.local.SolveDaoTest"
./gradlew testDebugUnitTest --tests "*.SolveDaoTest.testInsertAndGetSolveById"

# single package
./gradlew testDebugUnitTest --tests "com.maciekhetman.cubetimer.data.sync.*"
```

Toolchain: JDK 21 (Kotlin `jvmToolchain(21)`), AGP 9.x, compileSdk/targetSdk 37, minSdk 24.
AGP's built-in Kotlin brings an older KGP; the `kotlin` version in `libs.versions.toml` (compose/serialization
plugins) is what actually pins the compiler. `java.time` is used everywhere, so core library desugaring is on —
don't drop it while minSdk < 26 (lint's `NewApi` fails the build without it).
Unit tests get a 2 GB heap (configured in `app/build.gradle.kts`) — the suite is large and Robolectric-heavy,
so a full run takes several minutes.

## Testing conventions

- Everything lives in `app/src/test` (JVM/Robolectric). There is **no** `androidTest` source set, so
  Compose UI tests (`createComposeRule`) and Room DAO tests also run under `RobolectricTestRunner`.
- Robolectric SDK is pinned to 34 (`app/src/test/resources/robolectric.properties`); Compose UI tests
  additionally carry `@Config(sdk = [34])`.
- **No mocking library** (no MockK/Mockito). Collaborators are hand-written `Fake*` classes inside the test
  file; HTTP is tested with OkHttp `MockWebServer`; flows with Turbine; coroutines with
  `kotlinx-coroutines-test` (`runTest`, `StandardTestDispatcher`, `Dispatchers.setMain`).
- Room tests use `CubeDatabase.createInMemory(context)` (allows main-thread queries, optional injected
  executors for concurrency tests) and `database.close()` in `@After`.
- Test names encode provenance: `*Test` (spec), `*StressTest` / `*ChallengeTest` (adversarial suites written
  against a milestone). Treat the challenge/stress tests as behavioural contracts — they pin down edge cases
  that are not obvious from the production code.

## Architecture

### Dependency wiring
No DI framework. `CubeTimerApplication` is the manual singleton graph (`database`, `tokenStorage`,
`apiClient`, `authManager`, `syncEngine`, `sessionRepository`, `sessionManager`, `solvesRepository`,
`syncStateManager`, `bluetoothTimerManager`), all `by lazy`. ViewModels are constructed by an anonymous
`ViewModelProvider.Factory` in `MainActivity.onCreate`. Adding a dependency to a ViewModel means editing
both files. `SyncWorker` is built by a custom `WorkerFactory` in `workManagerConfiguration`.

### Layers
- `data/local` — Room (`CubeDatabase`, v2, `exportSchema` to `app/schemas/`, no destructive-migration fallback,
  WAL, `PRAGMA foreign_keys = ON`). Tables: `solves`, `sessions`, `sync_outbox`, `sync_metadata`, `sync_conflicts`.
  Every version bump needs a real `Migration` (v1→v2 adds `solves.timing_device`); there is deliberately no
  destructive fallback, so a missing one fails loudly instead of wiping never-synced guest data.
  `CubeDatabaseMigrationTest` replays `1.json`.
- `data/remote` — Retrofit + OkHttp + kotlinx.serialization; `AuthInterceptor` attaches the access token,
  `TokenAuthenticator` refreshes on 401 and notifies `AuthManager` via `SessionExpirationListener`. Every
  refresh (the authenticator's and `AuthManagerImpl`'s) goes through the one `TokenRefresher`: the server
  rotates refresh tokens with reuse detection, so two concurrent refreshes log the user out. A 401 whose body code
  is `invalid_credentials` (wrong current password on `PUT /v1/me/password`) is neither refreshed nor retried.
- `data/auth` — `AuthManagerImpl` owns `AuthState` (Guest / Authenticated / Admin), `adoptGuestData` and
  `deleteAccount` (on success the user's rows are re-owned to `"guest"`; their outbox/conflicts/cursor are
  dropped). `EncryptedTokenStorage` never falls back to plaintext: if Keystore is unusable it resets the file,
  then keeps tokens in memory only. The device id lives in `cubetimer_device_prefs` (excluded from backup).
  `changePassword` (`PUT /v1/me/password`) makes the server revoke **every** refresh token of the user, this
  device's included, so on success `AuthManagerImpl` immediately signs in again with the new password
  (`isNewLogin = false`: no guest adoption, no sync trigger); if that sign-in fails it ends as Guest like an
  expired session but still returns `Success`.
- `data/session` — `SessionRepositoryImpl` (persistence) + `SessionManagerImpl` (automatic-session policy; no
  persisted state of its own).
- `data/sync` — `SyncEngineImpl`, `ConflictResolverImpl`, `SyncStateManager` (UI-facing sync status), `work/SyncWorker`.
- `data/bluetooth` — `BluetoothTimerManager` / `AndroidBluetoothTimerManager` (BLE scan, GATT connect,
  serialised GATT writes, hot `events` flow) and the per-model `SmartTimerDriver`s (`GanTimerDriver`,
  `QiyiTimerDriver`) + `SmartTimerDetector`. Drivers hold no Android types and are tested with a fake writer.
- `domain` — pure logic, unit-testable: `AverageCalculator`, `HistoricalPbCalculator`, `ScrambleGenerator`,
  `TimeFormatter`, `session/AutomaticSessionHelper`, `csv/*`, `bluetooth/*` (GAN/QiYi wire formats, ported from
  CubeTimer-web's `src/features/timer/bluetooth`; QiYi uses JCA `AES/ECB/NoPadding`).
- `viewmodel` / `ui` — Compose screens fed by `StateFlow`; `HistoryModels.kt` holds the History screen's
  filter/sort enums and UI models.

### Data invariants (get these wrong and sync/tests break)
- **Owner scoping**: every query is scoped by `owner_id`. The literal `"guest"` is the sentinel for the
  logged-out user and is the default argument almost everywhere. Guest writes **never** enqueue outbox
  mutations — repositories branch on `if (ownerId != "guest")`. On login, `AuthManager.adoptGuestData(userId)`
  rewrites guest rows to the user id and enqueues upserts.
- **Soft deletes**: rows carry `deleted_at`; all read queries filter `deleted_at IS NULL`. Never hard-delete
  solves/sessions outside the dedicated cascade/restore paths (`deleteSessionWithSolves` /
  `restoreSessionWithSolves` return a `DeletedSessionSnapshot` used for undo).
- **Timestamps**: entities store ISO-8601 UTC *strings* (`solved_at`, `updated_at`, `started_at`, …) while
  domain models (`SolveTime.timestamp`) use epoch millis. Convert through `CubeTypeConverters`
  (`isoToEpochMillis` / `epochMillisToIso`), not by hand.
- **Enum ↔ column strings**: `Mode` persists as `"3x3"`, `"megaminx"`, … and `Penalty` as
  `"none"/"plus_two"/"dnf"`, via `CubeTypeConverters` and `data/local/mapper/*`. The DB never stores enum names.
  An event string with no `Mode` (e.g. `"skewb"` from another client) reads as 3x3; writes over an existing row
  go through `CubeTypeConverters.eventForRewrite` so the stored string survives.
- **`version`** is the optimistic-concurrency base version used by the sync protocol; bump/propagate it
  through the mappers rather than setting it ad hoc.
- **`timing_device`**: `solves.timing_device` / `SolveTime.timingDevice` (`TimingDevice`: `"keyboard"` = touch,
  `"external_timer"` = Bluetooth timer, `"smart_cube"`) is part of every solve upsert and is read back from
  changes, snapshots and conflicts. Carry it through mappers; it defaults to `"keyboard"`.

### Sync flow
Local write → repository writes Room row **and** a `sync_outbox` mutation in one `withTransaction` → `syncTrigger`
schedules an immediate `SyncWorker` (periodic 15 min otherwise) → `SyncEngineImpl.sync()` posts batches to
`POST /v1/sync`, applies `outcomes` (accepted/rejected/conflict) and remote `changes` transactionally, advances
the cursor in `sync_metadata`, and loops while `has_more`. A 409 `cursor_expired` falls back to
`runSnapshotBootstrap` against `POST /v1/snapshot`, which pages all sessions, then all solves: the last session
page answers `has_more: false, next_entity: "solve"` — that is a hand-over, not the end. Conflicts are persisted
as `ConflictEntity` and resolved by `ConflictResolver` (or by the user via keep-local / keep-server in
`SyncStatusDialog`). A mutation the server rejects on its own is marked `status = 'dead'` and never resent;
a remote solve whose session isn't available locally is skipped rather than failing the page's FK check.
The client pins `X-Sync-Protocol: 1`: v2 slims a conflict's `current` to an `{id, version, updated_at}` stub,
which would leave keep-server with nothing to apply. `CubeTimerApplication.BASE_URL` is `https://api.cubetimer.cc`; the web client (CubeTimer-web, which emailed
links open) lives at `https://cubetimer.cc`.

### Sessions
Every solve belongs to a session. Automatic sessions are named `"${day} ${month} ${year} ${dayPart}"`
(e.g. `30 aug 2026 morning`, lowercase 3-letter English month), de-duplicated by appending `" 2"`, `" 3"`, …,
and an open automatic session is reused only if the gap since the last solve is within
`AutomaticSessionHelper.DEFAULT_INACTIVITY_GAP_MILLIS` (60 min) — otherwise a new one is opened. Sessions are
automatic only: the app never creates, selects, renames or archives manual sessions. `kind = "manual"` still
exists because the backend syncs it (other clients, older builds); such sessions are listed in History but
never become the active session. CSV import recreates missing sessions as closed automatic sessions.
`DataStoreMigration` does the same for legacy DataStore solves, and on every start attaches any non-deleted
*guest* solve left without a session (older builds imported them session-less, which hid them from History);
signed-in owners' session-less solves are left alone, as the server accepts them.

### UI
- Navigation is **not** `NavHost`-based (there is no navigation-compose dependency): `MainActivity` keeps a
  `rememberSaveable` `AppDestinations` enum and swaps screens inside an `AnimatedContent`, with a custom bottom
  pill nav and a `BackHandler`. The bottom bar shows TIMER / STATS / HISTORY / SETTINGS; back from any of them
  returns to TIMER. There is no admin dashboard — `AuthState.Admin` only drives the account badge.
- The cloud sync status and account/admin indicators live in Settings' "Account" section
  (`SettingsScreen.kt`), not the shared top bar — `TopBar.kt`'s `TimerTopHeader`/`CollapsingTopBar` no
  longer take `syncUiState`/`authState`/click-handler params. Tapping the rows opens the same
  `SyncStatusDialog` / `AuthDialog` (`UserProfileDialog`, which also offers "Change password" — the
  `CHANGE_PASSWORD` dialog — and "Delete account") as before. If the post-change sign-in failed, the flow lands
  on `LOGIN` with a "Sign in again" success banner.
- Verification and password-reset emails carry links to the web client (`CLIENT_URL/verify-email?token=…`,
  `/reset-password?token=…`). The app never asks for a pasted token: the `EMAIL_VERIFICATION` dialog points at
  the link and offers "Resend email" (a sign-in refused with `EmailNotVerified` lands there too), and
  `RESET_PASSWORD` is a "Check your email" notice. `MainActivity` also has an `autoVerify` https intent filter for
  those two paths (`AuthLink` parses them): it only captures links once `cubetimer.cc/.well-known/assetlinks.json`
  lists the release signing certificate (not deployed yet; until then the browser/web client completes them). An
  opened link goes through `AuthViewModel.openEmailLink` to `VERIFY_EMAIL_LINK` / `RESET_PASSWORD_LINK`
  (`AuthLinkDialogs.kt`), which ask before acting (completing one signs the device in and adopts guest solves) and
  refuse while someone is signed in.
- Settings ends with an "About" section (`AboutSection` in `SettingsScreen.kt`): version
  (`BuildConfig.VERSION_NAME`), source code / issue tracker links, and `OpenSourceLicensesDialog`, whose
  component list is maintained by hand. Its Website / Privacy policy / "Delete account on the web" rows open
  `cubetimer.cc/about`, `/privacy` and `/account` (constants at the top of `SettingsScreen.kt`).
- The top bar only carries the mode picker (plus screen-specific actions via `extraActions`); there is no
  session picker.
- Timing input is a setting (`SettingsRepository.timingDeviceFlow`), switchable from Settings → "Timing device"
  or the `TimingDeviceToggle` next to the mode picker on the timer screen (hidden without BLE support,
  disabled mid-solve; tapping the selected Bluetooth segment reopens `BluetoothTimerDialog`). With
  `TimingDevice.EXTERNAL_TIMER` the timer screen ignores touches and `TimerViewModel.onSmartTimerEvent` drives
  the same `TimerState` machine; `Stopped` carries the timer's own time and `TimerState.Finished.timingDevice`
  is saved with the solve. `BluetoothTimerDialog` handles permissions, enabling Bluetooth, scanning and connecting.
- Optional WCA inspection for touch timing (Settings → Timer: `inspection_enabled`, `inspection_start_gesture` =
  "Tap and hold" / "Tap"): a press in Idle enters `TimerState.Inspecting` (15 s countdown, "+2" from 15 s, "DNF"
  from 17 s; that press's release is ignored). The solve then starts per the gesture, the penalty is fixed by the
  inspection time at that moment and rides in `Finished.inspectionPenalty`; saving keeps the worse of it and the
  chosen penalty. "Cancel" (`cancelInspection`) returns to Idle. `isTimerBusy` (running or inspecting) gates
  mode/device switching and navigation; `isTimerRunning` is unchanged. Bluetooth timers ignore the setting.
- Preferences live in two DataStores (`AppDataStore.kt`): `solves` (legacy, source of the one-time
  Room migration in `DataStoreMigration`) and `settings` (`SettingsRepository`).
- Theming: `CubeTimerTheme(dynamicColor, amoled)`; haptics follow the `haptics_enabled` setting
  (`OptionalHapticsProvider` in `MainActivity` swaps `LocalHapticFeedback` for a no-op when it's off).
- CSV export/import (`domain/csv`) carries `timing_device` as an optional last column, and quote-prefixes text
  cells that start with `=`, `+`, `-` or `@` (spreadsheet formula injection); the importer strips that prefix.

## Gotchas

- TNoodle needs a `SHA1PRNG` `SecureRandom` that Android lacks; `AndroidSha1PrngProvider.install()` registers a
  shim in `Application.onCreate` and again in `ScrambleGenerator.generateScramble`. Don't remove either call.
- `SolvesRepository` has several secondary constructors kept purely for older tests/ViewModel call sites —
  when changing its primary constructor, keep them compiling.
- Room schema JSON (`app/schemas/.../<version>.json`) is committed; regenerate it whenever entities change.
- Room doesn't split `IN (:ids)` list parameters, and SQLite on API ≤ 30 caps a statement at 999 variables. Bulk
  id operations go through the chunked helpers in `data/local/dao/ChunkedQueries.kt`.
- The manifest removes androidx.startup's `WorkManagerInitializer` so WorkManager initializes on demand from
  `CubeTimerApplication` (a `Configuration.Provider`). Without that, the custom `WorkerFactory` is ignored and
  `SyncWorker` (no `(Context, WorkerParameters)` constructor) can't be created — background sync silently dies.
- Release builds run R8. Anything created by class name needs a keep rule in `app/proguard-rules.pro` (today:
  the SHA1PRNG `SecureRandomSpi`, and TNoodle puzzles, which `PuzzleRegistry` builds reflectively).
- BLE: Android allows one outstanding GATT operation per connection — route every write through the
  connection's op lock. QiYi timers ignore everything until they get a hello carrying their MAC (advertised in
  manufacturer data `0x0504`, else the device address) and re-send recorded solves until acknowledged.
- `.agents/` is a gitignored scratch directory used by a multi-agent workflow (briefings, handoffs). It is not
  part of the app.
