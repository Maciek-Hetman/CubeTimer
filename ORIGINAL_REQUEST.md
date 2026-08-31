# Original User Request

## 2026-08-30T08:55:57Z

<USER_REQUEST>
Integrate CubeTimer Android with the CubeSync backend (https://github.com/Maciek-Hetman/cubesync) with full feature parity matching the reference web client (https://github.com/Maciek-Hetman/CubeTimer-web), implementing offline-first local persistence, authentication, session management, bidirectional synchronization with conflict resolution, and admin metric views.

Working directory: /Users/maciek/AndroidStudioProjects/CubeTimer
Integrity mode: development

## Reference Materials
- Backend API & Protocol: https://github.com/Maciek-Hetman/cubesync (specifically `docs/sync-protocol.md` and `api/openapi.yaml`)
- Reference Web Client: https://github.com/Maciek-Hetman/CubeTimer-web (architecture, session rules, guest adoption, and sync engine)

## Requirements

### R1. Local Persistence & Migration
Implement offline-first local database storage (using Android Room) for solves, client-managed sessions (manual and automatic), sync outbox mutations, device metadata, and cursor state. Migrate existing user solves from DataStore into the new database without data loss.

### R2. Backend Authentication & Account Management
Support authentication against the CubeSync API: email/password registration, login, token refresh, email verification, password reset, and Google federated authentication. Keep access tokens in memory and store refresh tokens securely. Support guest timing mode with automatic adoption and synchronization of guest data upon user login.

### R3. Session Management
Implement full speedcubing session management supporting both manual and automatic session grouping per event/mode, session switching, session creation, renaming, and archiving, matching the reference web application behavior.

### R4. Bidirectional Offline-First Sync Engine
Implement a robust synchronization engine against `POST /v1/sync` using an idempotent local outbox of mutations, server cursor tracking, conflict detection and handling, pagination (`has_more`), and background synchronization (via AndroidX WorkManager) when connectivity is available.

### R5. UI Integration & Admin Metrics
Update the Jetpack Compose UI to include:
- Account management and sync status indicators in the top bar / navigation.
- Session selector, session management dialogs, and session-filtered statistics.
- Auth screens/modals (Login, Register, Password Reset, Email Verification).
- Admin metrics dashboard screen displaying overview, request, and error metrics for users with `user_role == admin`.

### R6. Verification & Test Suite
Implement automated unit and integration tests covering Room DAOs, API client contracts, outbox mutations queue, conflict resolution, session state transitions, and guest account adoption using MockWebServer and Robolectric/JUnit.

## Acceptance Criteria

### Data & Persistence
- [ ] Existing DataStore solves are cleanly migrated into Room on first launch without loss or duplication.
- [ ] Room schema persists solves, sessions, sync outbox mutations, and device/cursor metadata with indexed queries.

### Authentication & Account Adoption
- [ ] Users can time solves as guests without creating an account.
- [ ] Email/password registration, login, token refresh, logout, and Google sign-in succeed against CubeSync endpoints.
- [ ] Logging into an account adopts all existing local guest solves and sessions, enqueuing them as mutations in the outbox.
- [ ] Logging out closes active automatic sessions, clears user session data, and resets the app into a fresh guest state.

### Sessions & UI
- [ ] Users can create, switch, rename, and archive manual sessions and automatic sessions per puzzle event.
- [ ] Stats and solve history reflect the active session and mode.
- [ ] Users with `user_role == admin` can access the Admin metrics dashboard (`/v1/admin/stats/*`), while non-admin users do not see the entry point.

### Sync Protocol & Offline Operation
- [ ] The app operates completely offline; all solve and session modifications are recorded in the local outbox.
- [ ] When online, `POST /v1/sync` flushes outbox mutations with stable UUIDs and applies incoming changes in transaction order.
- [ ] Cursor advancement occurs only after changes commit locally, and multi-page sync batches (`has_more: true`) continue until up-to-date.
- [ ] Conflicts are handled deterministically without silent clock-based overwrites.

### Automated Verification
- [ ] `./gradlew testDebugUnitTest` runs and passes 100% of automated tests.
- [ ] `./gradlew assembleDebug` completes with zero build errors.
</USER_REQUEST>
