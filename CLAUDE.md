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
  WAL, `PRAGMA foreign_keys = ON`). Entities: `solves`, `sessions`, `sync_outbox`, `sync_metadata`, `conflicts`.
  Every version bump needs a real `Migration` (v1→v2 adds `solves.timing_device`); there is deliberately no
  destructive fallback, so a missing one fails loudly instead of wiping never-synced guest data.
  `CubeDatabaseMigrationTest` replays `1.json`.
- `data/remote` — Retrofit + OkHttp + kotlinx.serialization; `AuthInterceptor` attaches the access token,
  `TokenAuthenticator` refreshes on 401 and notifies `AuthManager` via `SessionExpirationListener`.
- `data/auth` — `AuthManagerImpl` owns `AuthState` (Guest / Authenticated / Admin) and `adoptGuestData`.
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
as `ConflictEntity` and resolved by `ConflictResolver` (or by the user via keep-local / keep-server).
The client pins `X-Sync-Protocol: 1`: v2 slims a conflict's `current` to an `{id, version, updated_at}` stub,
which would leave keep-server with nothing to apply. `CubeTimerApplication.BASE_URL` is still a placeholder host.

### Sessions
Every solve belongs to a session. Automatic sessions are named `"${day} ${month} ${year} ${dayPart}"`
(e.g. `30 aug 2026 morning`, lowercase 3-letter English month), de-duplicated by appending `" 2"`, `" 3"`, …,
and an open automatic session is reused only if the gap since the last solve is within
`AutomaticSessionHelper.DEFAULT_INACTIVITY_GAP_MILLIS` (60 min) — otherwise a new one is opened. Sessions are
automatic only: the app never creates, selects, renames or archives manual sessions. `kind = "manual"` still
exists because the backend syncs it (other clients, older builds); such sessions are listed in History but
never become the active session. CSV import recreates missing sessions as closed automatic sessions.

### UI
- Navigation is **not** `NavHost`-based despite the navigation-compose dependency: `MainActivity` keeps a
  `rememberSaveable` `AppDestinations` enum and swaps screens inside an `AnimatedContent`, with a custom bottom
  pill nav and a `BackHandler`. The bottom bar shows TIMER / STATS / HISTORY / SETTINGS; back from any of them
  returns to TIMER. There is no admin dashboard — `AuthState.Admin` only drives the account badge.
- The cloud sync status and account/admin indicators live in Settings' "Account" section
  (`SettingsScreen.kt`), not the shared top bar — `TopBar.kt`'s `TimerTopHeader`/`CollapsingTopBar` no
  longer take `syncUiState`/`authState`/click-handler params. Tapping the rows opens the same
  `SyncStatusDialog` / `AuthDialog` (`UserProfileDialog`) as before.
- The top bar only carries the mode picker (plus screen-specific actions via `extraActions`); there is no
  session picker.
- Timing input is a setting (`SettingsRepository.timingDeviceFlow`), switchable from Settings → "Timing device"
  or the `TimingDeviceToggle` next to the mode picker on the timer screen (hidden without BLE support,
  disabled mid-solve; tapping the selected Bluetooth segment reopens `BluetoothTimerDialog`). With
  `TimingDevice.EXTERNAL_TIMER` the timer screen ignores touches and `TimerViewModel.onSmartTimerEvent` drives
  the same `TimerState` machine; `Stopped` carries the timer's own time and `TimerState.Finished.timingDevice`
  is saved with the solve. `BluetoothTimerDialog` handles permissions, enabling Bluetooth, scanning and connecting.
- Preferences live in two DataStores (`AppDataStore.kt`): `solves` (legacy, source of the one-time
  Room migration in `DataStoreMigration`) and `settings` (`SettingsRepository`).
- Theming: `CubeTimerTheme(dynamicColor, amoled)`; haptics are globally disabled by overriding
  `LocalHapticFeedback` with a no-op.

## Gotchas

- TNoodle needs a `SHA1PRNG` `SecureRandom` that Android lacks; `AndroidSha1PrngProvider.install()` registers a
  shim in `Application.onCreate` and again in `ScrambleGenerator.generateScramble`. Don't remove either call.
- `SolvesRepository` has several secondary constructors kept purely for older tests/ViewModel call sites —
  when changing its primary constructor, keep them compiling.
- Room schema JSON (`app/schemas/.../<version>.json`) is committed; regenerate it whenever entities change.
- Room doesn't split `IN (:ids)` list parameters, and SQLite on API ≤ 30 caps a statement at 999 variables. Bulk
  id operations go through the chunked helpers in `data/local/dao/ChunkedQueries.kt`.
- BLE: Android allows one outstanding GATT operation per connection — route every write through the
  connection's op lock. QiYi timers ignore everything until they get a hello carrying their MAC (advertised in
  manufacturer data `0x0504`, else the device address) and re-send recorded solves until acknowledged.
- `.agents/` is a gitignored scratch directory used by a multi-agent workflow (briefings, handoffs). It is not
  part of the app.
