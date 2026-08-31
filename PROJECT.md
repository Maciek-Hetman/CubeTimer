# Project: CubeTimer Android Integration with CubeSync Backend

## Architecture
- **Local Persistence Layer (Room)**:
  - `CubeDatabase` containing 5 entities: `SolveEntity`, `SessionEntity`, `SyncOutboxEntity`, `SyncMetadataEntity`, `ConflictEntity`.
  - DAOs: `SolveDao`, `SessionDao`, `SyncOutboxDao`, `SyncMetadataDao`, `ConflictDao`.
  - DataStore Migration: Reads `solves_list` from DataStore on startup, converts to `SolveEntity` (guest ownership, version 0), inserts into Room, sets migration flag.
  - Reactive Streams: Kotlin `Flow` exposing reactive lists of solves and sessions to repositories and ViewModels.
- **Authentication & Token Management Layer**:
  - `CubeSyncApiClient`: HTTP client (OkHttp / Retrofit or Ktor + Kotlinx Serialization) handling `/v1/auth/*`, `/v1/sync`, `/v1/snapshot`, `/v1/admin/stats/*`.
  - `TokenStorage`: Secure storage (EncryptedSharedPreferences or DataStore) for refresh token and device ID; in-memory StateFlow for access token.
  - `AuthManager`: Exposes `AuthState` (Guest, Authenticated, Admin), coordinates Login, Register, Logout, Token Refresh, Password Reset, Email Verification, and Google Sign-in.
  - `GuestAdoption`: On login/registration, adopts local guest sessions and solves, setting `owner_id = user.id`, `version = 0`, and enqueuing create/upsert mutations into `sync_outbox`.
  - `Logout`: Closes active automatic sessions, revokes refresh token via `/v1/auth/logout`, purges user session data, and resets to fresh guest state.
- **Session Management Layer**:
  - `SessionManager`: Manages active session per puzzle event (`Mode`).
  - Supports Manual sessions (user-created, named, switchable, archivable) and Automatic sessions (formula: `${day} ${month} ${year} ${dayPart}`, 60-minute inactivity gap reuse rule).
  - Solve association: Every solve is linked to an active `session_id`.
- **Bidirectional Sync Engine**:
  - `SyncEngine`: Offline-first synchronization loop against `POST /v1/sync`.
  - Flushes batches of pending mutations from `sync_outbox` (up to 500), processes outcomes (`accepted`, `rejected`, `conflict`), applies remote changes transactionally, updates cursor, and loops while `has_more == true`.
  - Error & Conflict handling: Base version optimistic concurrency; conflicts stored and resolved deterministically; on `409 cursor_expired`, runs snapshot bootstrap against `POST /v1/snapshot`.
  - `SyncWorker`: AndroidX WorkManager worker for periodic 15-minute background sync with network constraints and on-demand trigger on outbox mutations.
- **UI Integration & Admin Metrics**:
  - `TopBar`: User profile/auth icon, sync status indicator (Synced, Syncing, Offline, Error), active session dropdown.
  - Auth dialogs/screens: Login, Register, Forgot/Reset Password, Email Verification.
  - Session selector & management dialogs: Switch session, create manual session, rename, archive.
  - StatsScreen update: Filter statistics and solve history by selected session or all sessions.
  - Admin Metrics Dashboard: Dedicated screen accessible when `user_role == "admin"`, visualizing Overview stats, Request traffic/latency, and Error logs (`/v1/admin/stats/*`).
- **Test Suite**:
  - Robolectric / JUnit unit tests for Room DAOs, DataStore migration, AverageCalculator, ScrambleGenerator.
  - MockWebServer tests for Auth API, Sync engine outbox dispatch, Conflict handling, Snapshot recovery, Admin API.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Gradle Dependencies & Plugins | Add Room, KSP, Retrofit/OkHttp/Ktor, Kotlinx Serialization, WorkManager, Security Crypto, MockWebServer, Robolectric | M1 | Survey |
| 2 | Room Database & Entities | Implement `CubeDatabase`, `SolveEntity`, `SessionEntity`, `SyncOutboxEntity`, `SyncMetadataEntity`, `ConflictEntity` | M1 | R1 |
| 3 | Room DAOs & Repository | Implement `SolveDao`, `SessionDao`, `SyncOutboxDao`, `SyncMetadataDao`, `ConflictDao`, refactor `SolvesRepository` to Room | M1 | R1 |
| 4 | DataStore Migration | Idempotent migration from DataStore `solves_list` JSON to Room `SolveEntity` on first launch | M1 | R1 |
| 5 | Network Models & API Client | API request/response DTOs, `CubeSyncApiClient`, HTTP interceptors for auth & sync protocol headers | M2 | R2 |
| 6 | Token Storage & Lifecycle | `TokenStorage` (EncryptedSharedPreferences / secure storage + in-memory access token), auto-refresh on 401 | M2 | R2 |
| 7 | Auth Manager & Flows | Register, Login, Refresh, Logout, Email Verify, Password Reset, Google sign-in | M2 | R2 |
| 8 | Guest Timing & Account Adoption | Guest mode timing, atomic adoption of guest solves/sessions upon login, outbox queueing | M2 | R2 |
| 9 | Session Entities & Lifecycle | Manual and Automatic session creation, switching, renaming, archiving, and deletion | M3 | R3 |
| 10 | Automatic Session Grouping | Auto session generation (`${day} ${month} ${year} ${dayPart}`), duplicate suffixing, 60-min inactivity gap reuse | M3 | R3 |
| 11 | Session-Aware Solve Tracking | Association of solves to active session, session stats calculation | M3 | R3 |
| 12 | Sync Outbox & Mutation Queue | Idempotent mutation enqueuing (solve/session create/update/delete) with stable UUIDs | M4 | R4 |
| 13 | Bidirectional Sync Engine | `POST /v1/sync` loop, transaction commit, cursor advancement, pagination (`has_more`), snapshot recovery on 409 | M4 | R4 |
| 14 | Conflict Detection & Resolution | Base version tracking, conflict recording in `ConflictEntity`, deterministic resolution | M4 | R4 |
| 15 | WorkManager Background Sync | `SyncWorker` periodic (15m) + immediate trigger on mutation enqueueing with network constraints | M4 | R4 |
| 16 | TopBar Account & Sync Status | Profile/Login button, sync status indicator (Synced, Syncing, Offline, Error), session selector | M5 | R5 |
| 17 | Auth Screens & Dialogs | Login, Register, Forgot Password, Reset Password, Email Verification screens/sheets | M5 | R5 |
| 18 | Session Management UI | Session dropdown, create session dialog, rename/archive session sheet | M5 | R5 |
| 19 | Admin Metrics Dashboard | Admin-only dashboard screen (`/v1/admin/stats/*`) with overview KPIs, request charts, error logs | M5 | R5 |
| 20 | Room & Migration Tests | Unit tests for Room DAOs, indexes, transactions, and DataStore-to-Room migration | M6 | R6 |
| 21 | Auth & Sync MockWebServer Tests | Contract tests for auth lifecycle, guest adoption, sync outbox, conflict handling, and snapshot recovery | M6 | R6 |
| 22 | Build & Test Verification | 100% passing `./gradlew testDebugUnitTest` and clean `./gradlew assembleDebug` | M6 | R6 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Local Persistence & DataStore Migration | Build dependencies, Room schema, entities, DAOs, DataStore migration, SolvesRepository refactor | None | DONE |
| M2 | Backend Authentication & Account Adoption | API client, Token storage, AuthManager, Auth flows, Guest adoption, Logout cleanup | M1 | IN_PROGRESS |
| M3 | Session Management | SessionManager, automatic session naming/grouping (60-min gap), manual sessions, session lifecycle | M1 | PLANNED |
| M4 | Bidirectional Sync Engine | Outbox mutation queue, SyncEngine against `/v1/sync`, cursor tracking, conflict handling, snapshot bootstrap, WorkManager SyncWorker | M1, M2, M3 | PLANNED |
| M5 | UI Integration & Admin Metrics | TopBar sync/auth/session controls, Auth dialogs, Session manager UI, StatsScreen session filtering, Admin dashboard | M2, M3, M4 | PLANNED |
| M6 | Verification & Test Suite | Room DAO tests, Migration tests, Auth/Sync/Admin MockWebServer tests, Gradle test/build pass | M1, M2, M3, M4, M5 | PLANNED |

## Interface Contracts
### Local Storage ↔ Repository
- `SolveDao`: `getSolvesBySessionFlow(ownerId, sessionId)` -> `Flow<List<SolveEntity>>`, `getSolvesByEventFlow(ownerId, event)` -> `Flow<List<SolveEntity>>`, `insertSolve(entity)`, `updateSolve(entity)`, `deleteSolve(entity)`.
- `SessionDao`: `getSessionsFlow(ownerId, event)` -> `Flow<List<SessionEntity>>`, `getActiveSession(ownerId, event)`, `insertSession(entity)`, `updateSession(entity)`.
- `SyncOutboxDao`: `getPendingMutations(limit)` -> `List<SyncOutboxEntity>`, `markInFlight(ids)`, `deleteMutations(ids)`, `enqueueMutation(entity)`.

### AuthManager ↔ SyncEngine
- `AuthManager.currentUserFlow`: `StateFlow<User?>` (null = guest).
- `AuthManager.onUserLogin(user)`: Triggers `SessionManager.adoptGuestData(user.id)` -> enqueues outbox mutations -> triggers immediate sync.
- `AuthManager.onUserLogout()`: Closes active auto sessions, clears user tokens, triggers reset to guest.

### SyncEngine ↔ Remote API
- `POST /v1/sync`: Request `{ client_time, last_cursor, mutations: [ { mutation_id, entity_type, entity_id, action, base_version, client_time, payload } ] }`
- Response: `{ next_cursor, has_more, server_time, outcomes: [ { mutation_id, status, error, server_version, conflict } ], changes: [ { entity_type, entity_id, action, version, server_time, payload } ] }`
- `POST /v1/snapshot`: Returns `{ cursor, sessions: [...], solves: [...] }` on `409 cursor_expired`.
