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

## 2026-09-05T13:33:44Z

<USER_REQUEST>
Split the current unified Statistics screen into two dedicated screens: a condensed, hierarchically organized Stats screen and a dedicated, high-performance History screen for past solves. Tapping a solve in History opens an interactive pop-up with a shareable card displaying time, scramble, 2D scramble preview for all puzzles, timing device placeholder, and historical PB difference when applicable.

Working directory: /Users/maciek/AndroidStudioProjects/CubeTimer
Integrity mode: development

## Requirements

### R1. App Navigation & Destination Expansion
Add a dedicated `History` destination alongside `Timer`, `Stats`, and `Settings` in the app's floating bottom navigation bar (`FloatingNavigationBar` in `MainActivity.kt`). Update the animated pill indicator geometry, icon mapping, and predictive back navigation to seamlessly route between all four primary destinations.

### R2. Condensed & Hierarchical Stats Dashboard
Rework `StatsScreen.kt` to present a clean, high-hierarchy overview:
- Top hero card highlighting the all-time Personal Best single and current session averages (Ao5, Ao12).
- Compact summary grid displaying standard averages (Ao5, Ao12, Ao50, Ao100).
- Expandable / collapsible sections for large averages (Ao500, Ao1000, Ao2000), session metrics, and penalty distributions (DNF, +2).
- Retain the session filter chips bar (`SessionFilterBar`), activity heatmap tracker, and interactive performance charts.
- Remove the past solve history list from this screen so it focuses purely on aggregated statistics and insights.

### R3. Dedicated High-Performance History Screen
Create `HistoryScreen.kt` specifically optimized for browsing, filtering, and managing past solves:
- Offline-first architecture reading directly from Room local persistence with chunked / infinite-scroll pagination (batches of 50–100 solves) to guarantee 60fps scrolling performance even with hundreds or thousands of solves.
- Non-blocking architecture ensuring background server synchronization (`CubeSyncApiClient` / `SyncEngine`) never stalls local UI reads or list interactions.
- Session filtering matching the selected session filter (active session, all solves, or specific custom sessions).
- Direct solve actions: penalty adjustment (DNF, +2, None), solve deletion with undo snackbar, and session/history clear dialog.

### R4. Interactive Shareable Solve Card Pop-up
Tapping any solve card in the history list launches a modal dialog displaying a styled, shareable solve card:
- Large formatted solve duration with appropriate penalty badge and solve date/timestamp.
- Full scramble string accompanied by an accurate 2D scramble preview supporting all puzzle modes (3x3, 2x2, 4x4, 5x5, Pyraminx, Megaminx) rendered via TNoodle SVG/canvas.
- Timing device metadata chip/row displaying `Timer: Screen / Touch` (designed for future Bluetooth/Stackmat timer expansion).
- Historical PB indicator: checks whether the solve was a Personal Best single at the time it occurred (compared to the fastest solve prior to its timestamp) and displays the delta (e.g. `PB (-0.85s vs 12.40s)`).
- Share action: invokes the Android system share sheet with a bitmap snapshot of the card alongside formatted solve text details.

## Acceptance Criteria

### Navigation & Routing
- [ ] Bottom navigation bar displays 4 items (`Timer`, `Stats`, `History`, `Settings`) with fluid pill animation and touch targets.
- [ ] Navigating back from History routes predictably to Timer without backstack loops.

### Stats Screen Hierarchy
- [ ] Stats screen displays hero PB & current averages, compact standard averages grid, and collapsible sections for large averages and penalties.
- [ ] Past solve list is completely removed from Stats screen, and no layout clipping or unbounded height errors occur.

### History Screen Performance & Data
- [ ] History screen loads solves incrementally using infinite scrolling / chunked pagination from local Room DB.
- [ ] Fast scrolling through hundreds of solves produces no noticeable frame drops or memory bloat.
- [ ] Background network sync operates independently without blocking or freezing history list updates.
- [ ] Penalties and deletions applied in History persist to Room and enqueue sync outbox records correctly.

### Shareable Solve Card & 2D Preview
- [ ] Tapping any solve opens the modal detail card.
- [ ] 2D scramble visualization renders valid unfolded state nets for 3x3, 2x2, 4x4, 5x5, Pyraminx, and Megaminx.
- [ ] Timing device indicator chip `Timer: Screen / Touch` is clearly visible.
- [ ] Historical PB delta correctly identifies if a solve was a PB when performed and displays the time difference from the previous best.
- [ ] Share button triggers Android system share sheet with an image snapshot of the card and textual details.

### Verification & Quality
- [ ] `./gradlew testDebugUnitTest` compiles and passes all unit and Robolectric tests cleanly.
</USER_REQUEST>

## 2026-09-11T11:48:24Z

<USER_REQUEST>
This is a single self-contained fix; keep it small and focused. Use a small focused team.

Perform comprehensive UI adjustments across the Stats, History, and Settings screens in the CubeTimer Android application to reduce clutter, expand graph widths, increase activity padding, make collapsible card transitions responsive, add direct +2/DNF/delete solve actions, add a setting to hide top bar session management, and convert scramble size to a slider.

Working directory: /Users/maciek/AndroidStudioProjects/CubeTimer
Integrity mode: development

## Requirements

### R1. Stats Screen Layout & Spacing
- Increase graph card widths by removing redundant horizontal padding from `PersonalBestsChart`, `SolveTimesChart`, and `AveragesChart` so their outer edges align consistently with other content cards.
- Increase internal padding of the `ActivityTracker` card to give the heat map grid and legend comfortable breathing room.
- Remove both the collapsible section header subtitles (under "Large Averages", "Session & Detailed Metrics", and "Penalty Distribution") and the intermediate section subtitles ("Session Stats (Solves within 1h gaps)" and "Detailed & Aggregate Metrics") to reduce visual clutter.

### R2. Responsive Card Collapse/Expand Animation
- Redesign the expand/collapse animation for bottom cards (`CollapsibleSectionCard`) to eliminate lag, delayed shrinking, and animation stutter, ensuring immediate, snappy transitions.

### R3. History Screen Direct Solve Actions
- Add a dedicated action row at the bottom of each `HistorySolveCard` featuring compact toggle chips for `+2` and `DNF` (with visual active states), plus a direct trash icon button for `Delete` (with undo snackbar support).

### R4. Settings Screen Controls
- In the "Defaults" section of Settings, add a persistent toggle "Hide session menu in top bar" which hides the session pill from the top bar across all screens and locks session handling into automatic mode.
- In the "Scramble" section of Settings, replace the dropdown menu with a slider control spanning 70% to 140% in 5% discrete steps with live percentage readout and haptic tick feedback.

## Acceptance Criteria

### Verification & Robustness
- [ ] `./gradlew testDebugUnitTest` compiles cleanly and passes without regressions.
- [ ] Existing `StatsScreenRobustnessChallengeTest` assertions remain valid and passing.
- [ ] Unit tests are added or updated to verify:
  - The new session visibility preference default, mutation, and persistence.
  - The scramble slider scale flow and persistence across the 70%..140% range.
  - History solve card direct actions trigger +2, DNF, and Delete events properly.

### Visual & Behavioral
- [ ] Graph cards have equal horizontal width and alignment as other content cards in `StatsScreen`.
- [ ] `ActivityTracker` card has increased internal padding.
- [ ] Bottom cards in `StatsScreen` collapse and expand without delayed shrinkage or sluggish spring lag.
- [ ] Section header subtitles and intermediate subtitles are removed from `StatsScreen`.
- [ ] Solve cards on `HistoryScreen` include a bottom row with `+2` and `DNF` toggle chips and a Delete icon button.
- [ ] The "Defaults" section in `SettingsScreen` includes a toggle to hide the session management menu in the top bar.
- [ ] When the session toggle is enabled, the session pill in the top bar is hidden and automatic session mode remains active.
- [ ] Scramble size in `SettingsScreen` is controlled via a slider ranging from 70% to 140% in 5% increments.
</USER_REQUEST>


