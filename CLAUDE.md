# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android speedcubing timer (Kotlin, Jetpack Compose, single `:app` module) that is being extended into an
offline-first client for a "CubeSync" backend. `PROJECT.md` holds the milestone plan (M1–M6) and the
sync/auth API contract; keep its milestone status table in sync when finishing work on a milestone.

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
`syncStateManager`, `adminRepository`), all `by lazy`. ViewModels are constructed by an anonymous
`ViewModelProvider.Factory` in `MainActivity.onCreate`. Adding a dependency to a ViewModel means editing
both files. `SyncWorker` is built by a custom `WorkerFactory` in `workManagerConfiguration`.

### Layers
- `data/local` — Room (`CubeDatabase`, v1, `exportSchema` to `app/schemas/`, `fallbackToDestructiveMigration`,
  WAL, `PRAGMA foreign_keys = ON`). Entities: `solves`, `sessions`, `sync_outbox`, `sync_metadata`, `conflicts`.
- `data/remote` — Retrofit + OkHttp + kotlinx.serialization; `AuthInterceptor` attaches the access token,
  `TokenAuthenticator` refreshes on 401 and notifies `AuthManager` via `SessionExpirationListener`.
- `data/auth` — `AuthManagerImpl` owns `AuthState` (Guest / Authenticated / Admin) and `adoptGuestData`.
- `data/session` — `SessionRepositoryImpl` (persistence) + `SessionManagerImpl` (active-session policy, DataStore-backed per-`Mode` overrides).
- `data/sync` — `SyncEngineImpl`, `ConflictResolverImpl`, `SyncStateManager` (UI-facing sync status), `work/SyncWorker`.
- `domain` — pure logic, unit-testable: `AverageCalculator`, `HistoricalPbCalculator`, `ScrambleGenerator`,
  `TimeFormatter`, `session/AutomaticSessionHelper`, `csv/*`.
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

### Sync flow
Local write → repository writes Room row **and** a `sync_outbox` mutation in one `withTransaction` → `syncTrigger`
schedules an immediate `SyncWorker` (periodic 15 min otherwise) → `SyncEngineImpl.sync()` posts batches to
`POST /v1/sync`, applies `outcomes` (accepted/rejected/conflict) and remote `changes` transactionally, advances
the cursor in `sync_metadata`, and loops while `has_more`. A 409 `cursor_expired` falls back to
`runSnapshotBootstrap` against `POST /v1/snapshot`. Conflicts are persisted as `ConflictEntity` and resolved by
`ConflictResolver` (or by the user via keep-local / keep-server). `CubeTimerApplication.BASE_URL` is still a
placeholder host.

### Sessions
Every solve belongs to a session. Automatic sessions are named `"${day} ${month} ${year} ${dayPart}"`
(e.g. `30 aug 2026 morning`, lowercase 3-letter English month), de-duplicated by appending `" 2"`, `" 3"`, …,
and an open automatic session is reused only if the gap since the last solve is within
`AutomaticSessionHelper.DEFAULT_INACTIVITY_GAP_MILLIS` (60 min) — otherwise a new one is opened. Manual sessions
are user-created and selected per `Mode`; the automatic/manual choice and the active manual session id are stored
in DataStore keyed by `Mode.name`.

### UI
- Navigation is **not** `NavHost`-based despite the navigation-compose dependency: `MainActivity` keeps a
  `rememberSaveable` `AppDestinations` enum and swaps screens inside an `AnimatedContent`, with a custom bottom
  pill nav and a `BackHandler`. The bottom bar shows TIMER / STATS / HISTORY / SETTINGS; ADMIN has no bar entry
  and is reached from Settings only when `authState is AuthState.Admin`, and hides the bottom bar while open.
- The cloud sync status and account/admin indicators live in Settings' "Account" section
  (`SettingsScreen.kt`), not the shared top bar — `TopBar.kt`'s `TimerTopHeader`/`CollapsingTopBar` no
  longer take `syncUiState`/`authState`/click-handler params. Tapping the rows opens the same
  `SyncStatusDialog` / `AuthDialog` (`UserProfileDialog`) as before.
- Preferences live in two DataStores (`AppDataStore.kt`): `solves` (legacy, source of the one-time
  Room migration in `DataStoreMigration`) and `settings` (`SettingsRepository`).
- Theming: `CubeTimerTheme(dynamicColor, amoled)`; haptics are globally disabled by overriding
  `LocalHapticFeedback` with a no-op.

## Gotchas

- TNoodle needs a `SHA1PRNG` `SecureRandom` that Android lacks; `AndroidSha1PrngProvider.install()` registers a
  shim in `Application.onCreate` and again in `ScrambleGenerator.generateScramble`. Don't remove either call.
- `SolvesRepository` has several secondary constructors kept purely for older tests/ViewModel call sites —
  when changing its primary constructor, keep them compiling.
- Room schema JSON (`app/schemas/.../1.json`) is committed; regenerate it whenever entities change.
- `.agents/` is a gitignored scratch directory used by a multi-agent workflow (briefings, handoffs). It is not
  part of the app, but `.agents/PROJECT.md` / `ORIGINAL_REQUEST.md` mirror the root-level planning docs.
