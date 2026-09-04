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


## 2026-09-04T11:44:59Z

<USER_REQUEST>
This is a single self-contained fix; keep it small and focused.

Comprehensive UI/UX overhaul across CubeTimer to establish a consistent, expressive Google product look and feel (inspired by Google Clock and Material You Expressive design): cohesive Material 3 tonal surface container hierarchies (eliminating harsh dividers), unified 24dp rounded corner card geometry, matched pill action buttons, fluid spring animations, a distraction-free Timer header, and an animated floating pill navbar, completely free of AI-styled buttons or chips.

Working directory: /Users/maciek/AndroidStudioProjects/CubeTimer
Integrity mode: development

## Requirements

### R1. Unified Material 3 Expressive Foundations & Surfaces
Establish a cohesive Material Design 3 token and shape hierarchy across all theme configurations (Dynamic Color on Android 12+, AMOLED dark, standard dark/light).
- Standardize corner radii: 24dp for cards, summary panels, and dialog surfaces; uniform 20dp/pill geometry for action and modal buttons; 16dp/pill for chips and badges.
- Replace ad-hoc alpha color tints and harsh line dividers with canonical Material 3 surface containers (`surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`) and clean whitespace grouping.
- Ensure no AI-branded buttons, sparkle icons, or generative AI chips are introduced anywhere in the interface.

### R2. Distraction-Free Header & TopBar Hierarchy
Unify top bar architecture across all screens according to their functional intent:
- TimerScreen features an uncluttered, distraction-free top header with compact pill selectors for Mode and Session alongside subtle cloud sync and profile status badges.
- StatsScreen and SettingsScreen share a standardized Material 3 Medium TopAppBar with matching typography, padding, and smooth scroll collapse behavior.

### R3. Animated Floating Pill Navbar & M3 Screen Transitions
Preserve the floating bottom navigation bar while elevating it to Google Material You standards:
- Introduce an animated Material You indicator pill (smooth sliding and morphing container behind the active destination icon) with refined haptic cues.
- Replace lateral horizontal sliding transitions between top-level tabs (Timer, Stats, Settings) with silky Material 3 fade-through / shared-axis transitions.

### R4. Screen-by-Screen Component Harmonization
Apply the unified design system across every screen and modal in the application:
- **TimerScreen**: Large clean timer typography with balanced vertical breathing room; unified 24dp scramble container with smooth refresh micro-interaction; solve resolution buttons (Save Time, +2, DNF, Discard) sharing identical height and corner geometry; clean floating pill averages and recent solve chips.
- **StatsScreen**: Harmonized 24dp stat cards and large session average cards; unified M3 filter chips for session filtering; PB progress and solve distribution charts mapped to semantic Material 3 theme colors (primary, secondary, tertiary) rather than hardcoded hex colors; polished solve history items.
- **SettingsScreen**: Unified rounded grouped setting sections with cohesive toggles, sliders, and picker menus, eliminating raw horizontal dividers.
- **Modals, Sheets & Admin**: Restyle `SessionManagementSheet`, `AuthDialog`, and `AdminDashboardScreen` to seamlessly adopt the identical M3 card, surface container, chip, and button tokens so no visual discontinuities exist.

## Acceptance Criteria

### Design System & Theme Foundations
- [ ] All primary cards and section containers across Timer, Stats, Settings, and Admin consistently use 24dp rounded corners.
- [ ] All action buttons on the finished solve state (+2, DNF, Discard, Save) share identical height and uniform corner radius without mismatched corner geometry.
- [ ] Surface hierarchy strictly uses M3 tokens (`surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`) without arbitrary alpha tints or repetitive `HorizontalDivider` lines separating cards.
- [ ] The app renders consistently in Light mode, Dark mode, AMOLED dark mode, and Dynamic Color mode (Android 12+).
- [ ] No AI-styled sparkle or star action buttons exist in the application.

### Navigation & Motion
- [ ] Floating navbar displays an animated active indicator pill behind the selected destination.
- [ ] Transitions between Timer, Stats, and Settings use Material 3 fade-through / shared-axis animations without abrupt lateral sliding.
- [ ] Timer screen features an uncluttered top header, while Stats and Settings use unified Medium TopAppBars with consistent scroll behavior.

### Verification & Build Integrity
- [ ] `./gradlew testDebugUnitTest` runs with 100% passing tests (0 failures, 0 errors).
- [ ] `./gradlew assembleDebug` compiles cleanly with 0 build errors.

</USER_REQUEST>
