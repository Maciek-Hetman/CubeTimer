package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.data.local.mapper.toDomain
import com.maciekhetman.cubetimer.domain.csv.CsvFormat
import com.maciekhetman.cubetimer.domain.csv.CsvImportStatus
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Adversarial Empirical Challenge Test Suite for HistoryViewModel:
 *
 * Vector 1: Combinatorial Session Filter/Sort Matrix (5 SessionSortOrder x 2 PuzzleScope x 3 SessionKindFilter = 30 permutations)
 * Vector 2: Combinatorial Solve Filter/Sort Matrix (SolveSortOrder x TimeRangeFilter x PenaltyFilter x DateRangeFilter)
 * Vector 3: Inverted Time Ranges, Pathological Boundaries & DNF/Penalty Display Time Semantics
 * Vector 4: Date Range Boundary Transitions, Midnight Offsets & Local Timezones
 * Vector 5: CSV SAF Operations (Empty sessions, Scoped/Selected export, SAF ContentResolver error handling, Corrupt/Empty Import)
 * Vector 6: resetAllFilters(), resetSessionFilters(), and resetSolveFilters() Default Restoration
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryFilterAndCsvStressChallengeTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var solvesRepository: SolvesRepository
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var sessionManager: SessionManagerImpl
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        val directExecutor = java.util.concurrent.Executor { it.run() }
        database = CubeDatabase.createInMemory(
            context = application,
            queryExecutor = directExecutor,
            transactionExecutor = directExecutor
        )

        solvesRepository = SolvesRepository(
            context = application,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = testDispatcher
        )
        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
        fakeAuthManager = FakeAuthManager()
        sessionManager = SessionManagerImpl(
            context = application,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HistoryViewModel {
        return HistoryViewModel(
            application = application,
            solvesRepository = solvesRepository,
            sessionManager = sessionManager,
            sessionRepository = sessionRepository,
            authManager = fakeAuthManager,
            database = database,
            sessionDao = database.sessionDao(),
            solveDao = database.solveDao(),
            syncOutboxDao = database.syncOutboxDao(),
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    private suspend fun insertSessionEntity(
        id: String = UUID.randomUUID().toString(),
        name: String,
        mode: Mode = Mode.CUBE_3x3,
        kind: SessionKind = SessionKind.MANUAL,
        startedAt: String = Instant.now().toString(),
        ownerId: String = "guest"
    ): SessionEntity {
        val entity = SessionEntity(
            id = id,
            name = name,
            ownerId = ownerId,
            event = CubeTypeConverters.fromMode(mode),
            kind = if (kind == SessionKind.MANUAL) "manual" else "automatic",
            startedAt = startedAt,
            endedAt = null,
            archived = false,
            version = 0L,
            updatedAt = startedAt,
            deletedAt = null
        )
        database.sessionDao().insert(entity)
        return entity
    }

    private suspend fun insertSolvesForSession(
        session: SessionEntity,
        durationsMs: List<Long>,
        penalties: List<String> = emptyList(),
        baseTime: Instant = Instant.parse("2026-08-30T10:00:00.000Z"),
        ownerId: String = "guest"
    ): List<SolveEntity> {
        val entities = durationsMs.mapIndexed { index, duration ->
            val penalty = if (index < penalties.size) penalties[index] else "none"
            SolveEntity(
                id = "${session.id}-solve-$index",
                ownerId = ownerId,
                sessionId = session.id,
                event = session.event,
                durationMs = duration,
                penalty = penalty,
                solvedAt = baseTime.plus(index.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U' m-$index",
                version = 0L
            )
        }
        database.solveDao().insertAll(entities)
        return entities
    }

    // =========================================================================
    // Vector 1: Combinatorial Session Filter & Sort Matrix (30 Permutations)
    // =========================================================================
    @Test
    fun testSessionTabCombinatorialMatrixAll30Permutations() = runTest(testDispatcher) {
        // Prepare a rich matrix of 4 sessions:
        // S1: 3x3, Manual, Name "Alpha", 1 solve, started 2026-08-01
        // S2: 3x3, Automatic, Name "Bravo", 3 solves, started 2026-08-02
        // S3: 2x2, Manual, Name "Charlie", 2 solves, started 2026-08-03
        // S4: 2x2, Automatic, Name "Delta", 4 solves, started 2026-08-04
        val s1 = insertSessionEntity(name = "Alpha", mode = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-01T10:00:00Z")
        insertSolvesForSession(s1, listOf(12000L))

        val s2 = insertSessionEntity(name = "Bravo", mode = Mode.CUBE_3x3, kind = SessionKind.AUTOMATIC, startedAt = "2026-08-02T10:00:00Z")
        insertSolvesForSession(s2, listOf(10000L, 11000L, 12000L))

        val s3 = insertSessionEntity(name = "Charlie", mode = Mode.CUBE_2x2, kind = SessionKind.MANUAL, startedAt = "2026-08-03T10:00:00Z")
        insertSolvesForSession(s3, listOf(4000L, 5000L))

        val s4 = insertSessionEntity(name = "Delta", mode = Mode.CUBE_2x2, kind = SessionKind.AUTOMATIC, startedAt = "2026-08-04T10:00:00Z")
        insertSolvesForSession(s4, listOf(3000L, 3500L, 4000L, 4500L))

        viewModel = createViewModel()
        viewModel.setMode(Mode.CUBE_3x3) // Active mode is 3x3
        advanceUntilIdle()

        var combinationsTested = 0

        for (sort in SessionSortOrder.values()) {
            for (scope in PuzzleScope.values()) {
                for (kind in SessionKindFilter.values()) {
                    viewModel.setSessionSort(sort)
                    viewModel.setPuzzleScope(scope)
                    viewModel.setSessionKindFilter(kind)
                    advanceUntilIdle()

                    val state = viewModel.uiState.value
                    val groups = state.sessionGroups
                    combinationsTested++

                    // 1. Verify Scope constraint
                    if (scope == PuzzleScope.ACTIVE_PUZZLE) {
                        assertTrue("All groups must match 3x3 active puzzle for scope $scope",
                            groups.all { it.session.event == Mode.CUBE_3x3 })
                    } else {
                        // ALL_PUZZLES: Should contain modes from both 3x3 and 2x2 if kind permits
                        val modes = groups.map { it.session.event }.toSet()
                        if (groups.isNotEmpty()) {
                            assertTrue("Scope $scope must allow multiple puzzle modes", modes.contains(Mode.CUBE_3x3) || modes.contains(Mode.CUBE_2x2))
                        }
                    }

                    // 2. Verify Kind constraint
                    when (kind) {
                        SessionKindFilter.ALL -> { /* Any kind allowed */ }
                        SessionKindFilter.MANUAL_ONLY -> {
                            assertTrue("All groups must be manual for filter $kind",
                                groups.all { it.session.kind == SessionKind.MANUAL })
                        }
                        SessionKindFilter.AUTOMATIC_ONLY -> {
                            assertTrue("All groups must be automatic for filter $kind",
                                groups.all { it.session.kind == SessionKind.AUTOMATIC })
                        }
                    }

                    // 3. Verify Sort constraint
                    when (sort) {
                        SessionSortOrder.MOST_RECENT -> {
                            for (i in 0 until groups.size - 1) {
                                assertTrue("Session startedAt must be descending",
                                    groups[i].session.startedAt >= groups[i + 1].session.startedAt)
                            }
                        }
                        SessionSortOrder.OLDEST -> {
                            for (i in 0 until groups.size - 1) {
                                assertTrue("Session startedAt must be ascending",
                                    groups[i].session.startedAt <= groups[i + 1].session.startedAt)
                            }
                        }
                        SessionSortOrder.NAME_ASC -> {
                            for (i in 0 until groups.size - 1) {
                                assertTrue("Session name must be A-Z",
                                    groups[i].session.name.compareTo(groups[i + 1].session.name, ignoreCase = true) <= 0)
                            }
                        }
                        SessionSortOrder.NAME_DESC -> {
                            for (i in 0 until groups.size - 1) {
                                assertTrue("Session name must be Z-A",
                                    groups[i].session.name.compareTo(groups[i + 1].session.name, ignoreCase = true) >= 0)
                            }
                        }
                        SessionSortOrder.MOST_SOLVES -> {
                            for (i in 0 until groups.size - 1) {
                                assertTrue("Solve count must be descending",
                                    groups[i].solveCount >= groups[i + 1].solveCount)
                            }
                        }
                    }

                    // 4. Verify active badge count calculation
                    val expectedActiveSessionCount =
                        (if (sort != SessionSortOrder.MOST_RECENT) 1 else 0) +
                        (if (scope != PuzzleScope.ACTIVE_PUZZLE) 1 else 0) +
                        (if (kind != SessionKindFilter.ALL) 1 else 0)
                    assertEquals("Active session filter count mismatch for ($sort, $scope, $kind)",
                        expectedActiveSessionCount, state.activeSessionFilterCount)
                }
            }
        }

        assertEquals(30, combinationsTested)
    }

    // =========================================================================
    // Vector 2: Combinatorial Solve Filter & Sort Matrix
    // =========================================================================
    @Test
    fun testSolveTabCombinatorialFilterAndSortMatrix() = runTest(testDispatcher) {
        val s = insertSessionEntity(name = "MainSession", mode = Mode.CUBE_3x3)
        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()

        // Create diverse solves in session s:
        // Solves at various timestamps, durations, and penalties:
        val solveEntities = listOf(
            // Today solves
            SolveEntity("slv-today-fast", "guest", s.id, "3x3", 7500L, "none", now.toString(), "scramble-1", 0L),
            SolveEntity("slv-today-p2", "guest", s.id, "3x3", 9000L, "plus_two", now.minus(1, ChronoUnit.HOURS).toString(), "scramble-2", 0L), // display: 11000
            SolveEntity("slv-today-dnf", "guest", s.id, "3x3", 8000L, "dnf", now.minus(2, ChronoUnit.HOURS).toString(), "scramble-3", 0L),
            SolveEntity("slv-today-slow", "guest", s.id, "3x3", 25000L, "none", now.minus(3, ChronoUnit.HOURS).toString(), "scramble-4", 0L),
            // 3 days ago
            SolveEntity("slv-3d-clean", "guest", s.id, "3x3", 12000L, "none", now.minus(3, ChronoUnit.DAYS).toString(), "scramble-5", 0L),
            // 15 days ago
            SolveEntity("slv-15d-p2", "guest", s.id, "3x3", 14000L, "plus_two", now.minus(15, ChronoUnit.DAYS).toString(), "scramble-6", 0L), // display: 16000
            // 45 days ago
            SolveEntity("slv-45d-clean", "guest", s.id, "3x3", 18000L, "none", now.minus(45, ChronoUnit.DAYS).toString(), "scramble-7", 0L)
        )
        database.solveDao().insertAll(solveEntities)

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(s.id)
        advanceUntilIdle()

        val sorts = listOf(SolveSortOrder.MOST_RECENT, SolveSortOrder.OLDEST, SolveSortOrder.LOWEST_TIME, SolveSortOrder.HIGHEST_TIME)
        val penalties = listOf(PenaltyFilter.ALL, PenaltyFilter.CLEAN_ONLY, PenaltyFilter.PLUS_TWO_ONLY, PenaltyFilter.DNF_ONLY)
        val timeRanges = listOf(
            TimeRangeFilter(), // unbound
            TimeRangeFilter(minDurationMs = 8000L), // lower bound
            TimeRangeFilter(maxDurationMs = 15000L), // upper bound
            TimeRangeFilter(minDurationMs = 8000L, maxDurationMs = 15000L) // bounded
        )
        val datePresets = listOf(DatePreset.ALL_TIME, DatePreset.TODAY, DatePreset.LAST_7_DAYS, DatePreset.LAST_30_DAYS)

        var solveCombinationsTested = 0

        for (sort in sorts) {
            for (penalty in penalties) {
                for (tr in timeRanges) {
                    for (dp in datePresets) {
                        viewModel.setSolveSort(sort)
                        viewModel.setPenaltyFilter(penalty)
                        viewModel.setTimeRangeFilter(tr)
                        viewModel.setDateRangeFilter(dp)
                        advanceUntilIdle()

                        val state = viewModel.uiState.value
                        val groupSolves = state.sessionGroups.firstOrNull { it.id == s.id }?.solves ?: emptyList()
                        solveCombinationsTested++

                        // 1. Verify Penalty adherence
                        when (penalty) {
                            PenaltyFilter.ALL -> { /* no restriction */ }
                            PenaltyFilter.CLEAN_ONLY -> {
                                assertTrue("All solves must be CLEAN", groupSolves.all { it.penalty == Penalty.NONE })
                            }
                            PenaltyFilter.PLUS_TWO_ONLY -> {
                                assertTrue("All solves must be +2", groupSolves.all { it.penalty == Penalty.PLUS_TWO })
                            }
                            PenaltyFilter.DNF_ONLY -> {
                                assertTrue("All solves must be DNF", groupSolves.all { it.penalty == Penalty.DNF })
                            }
                        }

                        // 2. Verify TimeRange adherence
                        if (tr.isActive) {
                            // When time range is active, DNFs are filtered out
                            assertTrue("DNFs must never match active time range", groupSolves.none { it.penalty == Penalty.DNF })
                            val min = tr.minDurationMs
                            val max = tr.maxDurationMs
                            if (min != null) {
                                assertTrue("All solves displayTime must be >= $min", groupSolves.all { it.displayTime >= min })
                            }
                            if (max != null) {
                                assertTrue("All solves displayTime must be <= $max", groupSolves.all { it.displayTime <= max })
                            }
                        }

                        // 3. Verify Sort adherence
                        when (sort) {
                            SolveSortOrder.MOST_RECENT -> {
                                for (i in 0 until groupSolves.size - 1) {
                                    assertTrue("Timestamps must be descending", groupSolves[i].timestamp >= groupSolves[i + 1].timestamp)
                                }
                            }
                            SolveSortOrder.OLDEST -> {
                                for (i in 0 until groupSolves.size - 1) {
                                    assertTrue("Timestamps must be ascending", groupSolves[i].timestamp <= groupSolves[i + 1].timestamp)
                                }
                            }
                            SolveSortOrder.LOWEST_TIME -> {
                                val nonDnfs = groupSolves.filter { it.penalty != Penalty.DNF }
                                for (i in 0 until nonDnfs.size - 1) {
                                    assertTrue("DisplayTime must be ascending", nonDnfs[i].displayTime <= nonDnfs[i + 1].displayTime)
                                }
                                // DNFs must be at the very end
                                val dnfIndex = groupSolves.indexOfFirst { it.penalty == Penalty.DNF }
                                if (dnfIndex != -1) {
                                    assertTrue("All items after first DNF must be DNF", groupSolves.drop(dnfIndex).all { it.penalty == Penalty.DNF })
                                }
                            }
                            SolveSortOrder.HIGHEST_TIME -> {
                                val nonDnfs = groupSolves.filter { it.penalty != Penalty.DNF }
                                for (i in 0 until nonDnfs.size - 1) {
                                    assertTrue("DisplayTime must be descending", nonDnfs[i].displayTime >= nonDnfs[i + 1].displayTime)
                                }
                                // DNFs must be at the very end
                                val dnfIndex = groupSolves.indexOfFirst { it.penalty == Penalty.DNF }
                                if (dnfIndex != -1) {
                                    assertTrue("All items after first DNF must be DNF", groupSolves.drop(dnfIndex).all { it.penalty == Penalty.DNF })
                                }
                            }
                        }

                        // 4. Verify Active Solve filter badge count
                        val expectedActiveSolveCount =
                            (if (sort != SolveSortOrder.MOST_RECENT) 1 else 0) +
                            (if (penalty != PenaltyFilter.ALL) 1 else 0) +
                            (if (tr.isActive) 1 else 0) +
                            (if (dp != DatePreset.ALL_TIME) 1 else 0)
                        assertEquals(expectedActiveSolveCount, state.activeSolveFilterCount)
                    }
                }
            }
        }

        // 4 * 4 * 4 * 4 = 256 permutations verified!
        assertEquals(256, solveCombinationsTested)
    }

    // =========================================================================
    // Vector 3: Inverted Time Ranges & Pathological Boundaries
    // =========================================================================
    @Test
    fun testInvertedTimeRangeFilterReturnsEmptyWithoutCrashing() = runTest(testDispatcher) {
        val s = insertSessionEntity(name = "InvertedTimeSession")
        insertSolvesForSession(s, listOf(5000L, 10000L, 15000L, 20000L))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(s.id)
        advanceUntilIdle()

        // Inverted range: min 20s > max 10s
        viewModel.setTimeRangeFilter(minDurationMs = 20000L, maxDurationMs = 10000L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val solves = state.sessionGroups.first().solves
        assertTrue("Inverted range must yield empty list without crashing", solves.isEmpty())
        assertTrue(state.timeRangeFilter.isActive)
        assertEquals(1, state.activeSolveFilterCount)
    }

    @Test
    fun testPathologicalTimeRangeBoundariesZeroNegativeAndMaxValues() = runTest(testDispatcher) {
        val s = insertSessionEntity(name = "BoundaryTimeSession")
        insertSolvesForSession(s, listOf(10000L, 12000L, 15000L))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(s.id)
        advanceUntilIdle()

        // 1. Equal bounds: min == max (12000L .. 12000L)
        viewModel.setTimeRangeFilter(12000L, 12000L)
        advanceUntilIdle()
        var solves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(1, solves.size)
        assertEquals(12000L, solves.first().displayTime)

        // 2. Bound 0 to Long.MAX_VALUE: includes all valid non-DNF solves
        viewModel.setTimeRangeFilter(0L, Long.MAX_VALUE)
        advanceUntilIdle()
        solves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(3, solves.size)

        // 3. Negative min: min = -5000L, max = 11000L
        viewModel.setTimeRangeFilter(-5000L, 11000L)
        advanceUntilIdle()
        solves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(1, solves.size)
        assertEquals(10000L, solves.first().displayTime)

        // 4. Extreme bounds near Long.MAX_VALUE
        viewModel.setTimeRangeFilter(Long.MAX_VALUE - 1000L, Long.MAX_VALUE)
        advanceUntilIdle()
        solves = viewModel.uiState.value.sessionGroups.first().solves
        assertTrue(solves.isEmpty())
    }

    @Test
    fun testTimeRangeFilterPlusTwoPenaltyAndDnfSemantics() = runTest(testDispatcher) {
        val s = insertSessionEntity(name = "PenaltySemanticSession")
        // Solve A: 9000L with +2 -> displayTime 11000L
        // Solve B: 11000L with none -> displayTime 11000L
        // Solve C: 11000L with DNF -> displayTime undefined / DNF
        val solves = listOf(
            SolveEntity("s-p2", "guest", s.id, "3x3", 9000L, "plus_two", Instant.now().toString(), "scramble-p2", 0L),
            SolveEntity("s-clean", "guest", s.id, "3x3", 11000L, "none", Instant.now().toString(), "scramble-clean", 0L),
            SolveEntity("s-dnf", "guest", s.id, "3x3", 11000L, "dnf", Instant.now().toString(), "scramble-dnf", 0L)
        )
        database.solveDao().insertAll(solves)

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(s.id)
        advanceUntilIdle()

        // Time range [10000L, 12000L]:
        // MUST include s-p2 (9000 + 2000 = 11000)
        // MUST include s-clean (11000)
        // MUST EXCLUDE s-dnf
        viewModel.setTimeRangeFilter(10000L, 12000L)
        advanceUntilIdle()

        val resultSolves = viewModel.uiState.value.sessionGroups.first().solves
        assertEquals(2, resultSolves.size)
        val ids = resultSolves.map { it.id }.toSet()
        assertTrue(ids.contains("s-p2"))
        assertTrue(ids.contains("s-clean"))
        assertFalse(ids.contains("s-dnf"))

        // Time range [8000L, 9500L]:
        // Raw duration of s-p2 is 9000L, but displayTime is 11000L -> MUST NOT match!
        viewModel.setTimeRangeFilter(8000L, 9500L)
        advanceUntilIdle()
        val narrowSolves = viewModel.uiState.value.sessionGroups.first().solves
        assertTrue("Solve with +2 must evaluate effective displayTime (11s), not raw 9s", narrowSolves.isEmpty())
    }

    // =========================================================================
    // Vector 4: Date Range Boundary Transitions & Timezones
    // =========================================================================
    @Test
    fun testDateRangeBoundaryTransitionsMidnightAndOffsets() = runTest(testDispatcher) {
        val s = insertSessionEntity(name = "DateBoundarySession")
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val todayMidnight = today.atStartOfDay(zone).toInstant()
        val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
        val yesterdayEnd = todayMidnight.minusMillis(1)
        val tomorrowMidnight = today.plusDays(1).atStartOfDay(zone).toInstant()

        val boundarySolves = listOf(
            SolveEntity("s-today-start", "guest", s.id, "3x3", 10000L, "none", todayMidnight.toString(), "scramble-1", 0L),
            SolveEntity("s-today-end", "guest", s.id, "3x3", 11000L, "none", todayEnd.toString(), "scramble-2", 0L),
            SolveEntity("s-yesterday-last-ms", "guest", s.id, "3x3", 12000L, "none", yesterdayEnd.toString(), "scramble-3", 0L),
            SolveEntity("s-tomorrow-first-ms", "guest", s.id, "3x3", 13000L, "none", tomorrowMidnight.toString(), "scramble-4", 0L)
        )
        database.solveDao().insertAll(boundarySolves)

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(s.id)
        advanceUntilIdle()

        // 1. Preset TODAY
        viewModel.setDateRangeFilter(DatePreset.TODAY)
        advanceUntilIdle()

        var solves = viewModel.uiState.value.sessionGroups.first().solves
        val matchedIds = solves.map { it.id }.toSet()
        assertTrue("Midnight today must be included in TODAY", matchedIds.contains("s-today-start"))
        assertTrue("End of day today (23:59:59.999) must be included in TODAY", matchedIds.contains("s-today-end"))
        assertFalse("1ms before midnight today must be excluded", matchedIds.contains("s-yesterday-last-ms"))
        assertFalse("Tomorrow midnight must be excluded", matchedIds.contains("s-tomorrow-first-ms"))
        assertEquals(2, solves.size)

        // 2. Custom Date Range: Inverted start > end
        val startEpoch = todayMidnight.toEpochMilli()
        val endEpoch = yesterdayEnd.toEpochMilli() // start > end
        viewModel.setDateRangeFilter(DatePreset.CUSTOM, customStart = startEpoch, customEnd = endEpoch)
        advanceUntilIdle()

        solves = viewModel.uiState.value.sessionGroups.first().solves
        assertTrue("Inverted custom date bounds must yield empty list without crashing", solves.isEmpty())

        // 3. Custom Date Range: Half-open (start only)
        viewModel.setDateRangeFilter(DatePreset.CUSTOM, customStart = todayMidnight.toEpochMilli(), customEnd = null)
        advanceUntilIdle()

        solves = viewModel.uiState.value.sessionGroups.first().solves
        val halfOpenIds = solves.map { it.id }.toSet()
        assertTrue(halfOpenIds.contains("s-today-start"))
        assertTrue(halfOpenIds.contains("s-today-end"))
        assertTrue(halfOpenIds.contains("s-tomorrow-first-ms"))
        assertFalse(halfOpenIds.contains("s-yesterday-last-ms"))
    }

    // =========================================================================
    // Vector 5: CSV SAF Operations Stress (Empty Sessions, Scopes, SAF Errors, Corrupt Files)
    // =========================================================================
    @Test
    fun testCsvSafExportEmptySessionAndZeroSolvesHandling() = runTest(testDispatcher) {
        val emptySession = insertSessionEntity(name = "EmptySession", mode = Mode.CUBE_3x3)

        viewModel = createViewModel()
        advanceUntilIdle()

        // Stream export of empty session
        val outStream = ByteArrayOutputStream()
        val count = viewModel.exportSessionToStream(emptySession.toDomain(), outStream)
        assertEquals(0, count)
        assertEquals(0, outStream.size())

        // SAF URI export of empty session to real file URI
        val tempFile = File(application.cacheDir, "empty_session_test.csv")
        tempFile.createNewFile()
        val uri = Uri.fromFile(tempFile)

        viewModel.uiEffect.test {
            viewModel.exportSession(application, emptySession.toDomain(), uri)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            val msg = (effect as HistoryUiEffect.ShowMessage).message
            assertTrue(msg.contains("No solves to export for session 'EmptySession'"))
        }
        tempFile.delete()

        // Export all solves when repository has 0 solves
        val tempFileAll = File(application.cacheDir, "empty_all_test.csv")
        tempFileAll.createNewFile()
        val uriAll = Uri.fromFile(tempFileAll)

        viewModel.uiEffect.test {
            viewModel.exportAllSolves(application, uriAll)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            val msg = (effect as HistoryUiEffect.ShowMessage).message
            assertTrue(msg.contains("No solves to export in current scope"))
        }
        tempFileAll.delete()

        // Export selected solves when 0 solves selected (checked before stream opening)
        viewModel.uiEffect.test {
            val dummyUri = Uri.parse("content://dummy/selected.csv")
            viewModel.exportSelectedSolves(application, dummyUri)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            val msg = (effect as HistoryUiEffect.ShowMessage).message
            assertTrue(msg.contains("No solves selected for export"))
        }
    }

    @Test
    fun testCsvSafExportScopedAndSelectedToRealSafFiles() = runTest(testDispatcher) {
        val s = insertSessionEntity(name = "ExportTargetSession", mode = Mode.CUBE_3x3)
        val solves = insertSolvesForSession(s, listOf(12340L, 15670L), penalties = listOf("none", "+2"))

        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.expandSession(s.id)
        advanceUntilIdle()

        // 1. Export All Solves to real file URI
        val tempFileAll = File(application.cacheDir, "export_all_test.csv")
        tempFileAll.createNewFile()
        val uriAll = Uri.fromFile(tempFileAll)

        viewModel.uiEffect.test {
            viewModel.exportAllSolves(application, uriAll)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            assertTrue((effect as HistoryUiEffect.ShowMessage).message.contains("Exported 2 solves to CSV"))
        }

        val allCsv = tempFileAll.readText(StandardCharsets.UTF_8)
        assertTrue(allCsv.startsWith(CsvFormat.COMMENT_LINE))
        assertTrue(allCsv.contains(CsvFormat.HEADER_LINE))
        assertTrue(allCsv.contains("ExportTargetSession"))
        assertTrue(allCsv.contains("12340"))
        assertTrue(allCsv.contains("15670"))
        assertTrue(allCsv.contains("+2"))
        tempFileAll.delete()

        // 2. Export Selected Solves to real file URI
        val tempFileSelected = File(application.cacheDir, "export_selected_test.csv")
        tempFileSelected.createNewFile()
        val uriSelected = Uri.fromFile(tempFileSelected)

        viewModel.startSelection(solves[0].id)
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.exportSelectedSolves(application, uriSelected)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is HistoryUiEffect.ShowMessage)
            assertTrue((effect as HistoryUiEffect.ShowMessage).message.contains("Exported 1 selected solves to CSV"))
        }

        val selCsv = tempFileSelected.readText(StandardCharsets.UTF_8)
        assertTrue(selCsv.contains(solves[0].id))
        assertFalse(selCsv.contains(solves[1].id))
        assertTrue("Selection must be cleared after export", viewModel.uiState.value.selectedSolveIds.isEmpty())
        tempFileSelected.delete()
    }

    @Test
    fun testCsvSafExportFailureHandlingOnInvalidUriDoesNotCrash() = runTest(testDispatcher) {
        val s = insertSessionEntity(name = "FailureSession")
        insertSolvesForSession(s, listOf(10000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        val invalidUri = Uri.parse("invalid://scheme/not/existing/file.csv")

        viewModel.uiEffect.test {
            viewModel.exportAllSolves(application, invalidUri)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue("Expected ShowMessage, got: $effect", effect is HistoryUiEffect.ShowMessage)
            val msg = (effect as HistoryUiEffect.ShowMessage).message
            assertTrue("Expected message starting with 'Failed to export', got: $msg", msg.startsWith("Failed to export solves:"))
        }
    }

    @Test
    fun testCsvSafImportFailureHandlingOnInvalidUriDoesNotCrash() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        val invalidImportUri = Uri.parse("invalid://scheme/not/existing/import.csv")
        viewModel.uiEffect.test {
            viewModel.importSolvesFromUri(application, invalidImportUri)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue("Expected ShowMessage, got: $effect", effect is HistoryUiEffect.ShowMessage)
            val msg = (effect as HistoryUiEffect.ShowMessage).message
            assertTrue(
                "Expected import failure message, got: $msg",
                msg.startsWith("Failed to import solves:") || msg.startsWith("CSV import error:")
            )
        }
    }

    @Test
    fun testCsvSafImportCorruptAndMalformedFilesStress() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        // 1. Empty file (0 bytes)
        val emptyIn = ByteArrayInputStream(ByteArray(0))
        val statusEmpty = viewModel.importSolvesFromStream(emptyIn)
        assertTrue("0 byte stream must return EmptyFile", statusEmpty is CsvImportStatus.EmptyFile)

        // 2. Corrupt / invalid comment header
        val corruptHeaderCsv = """
            # Invalid Non CubeTimer Comment
            foo,bar,baz
        """.trimIndent()
        val corruptIn = ByteArrayInputStream(corruptHeaderCsv.toByteArray(StandardCharsets.UTF_8))
        val statusCorrupt = viewModel.importSolvesFromStream(corruptIn)
        assertTrue("Invalid comment must return InvalidFile, got $statusCorrupt", statusCorrupt is CsvImportStatus.InvalidFile)

        // 3. Mixed valid and malformed rows:
        // Row 1: Valid
        // Row 2: Malformed (missing columns)
        // Row 3: Valid
        val mixedCsv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-valid-1,,AutoCreatedSession,3x3,1788091200000,10500,none,R U R' U'
            malformed-row-here-too-few-columns
            solve-valid-2,,AutoCreatedSession,3x3,1788091260000,11500,none,U R U' R'
        """.trimIndent()
        val mixedIn = ByteArrayInputStream(mixedCsv.toByteArray(StandardCharsets.UTF_8))
        val statusMixed = viewModel.importSolvesFromStream(mixedIn)

        assertTrue(statusMixed is CsvImportStatus.Success)
        val success = statusMixed as CsvImportStatus.Success
        assertEquals(2, success.importedCount)
        assertEquals(1, success.malformedCount)
        assertEquals(0, success.duplicateCount)
        assertEquals(1, success.sessionsCreatedCount)

        // Verify AutoCreatedSession was created in DB
        val sessionsInDb = database.sessionDao().getAllSessionsForOwner("guest")
        val autoCreated = sessionsInDb.find { it.name == "AutoCreatedSession" }
        assertNotNull("Missing session must be auto-recreated", autoCreated)
        assertEquals("manual", autoCreated?.kind)

        // 4. Duplicate Solves Import
        // Re-importing the same stream should skip duplicates
        val duplicateIn = ByteArrayInputStream(mixedCsv.toByteArray(StandardCharsets.UTF_8))
        val statusDuplicate = viewModel.importSolvesFromStream(duplicateIn)
        assertTrue(statusDuplicate is CsvImportStatus.Success)
        val dupSuccess = statusDuplicate as CsvImportStatus.Success
        assertEquals(0, dupSuccess.importedCount)
        assertEquals(2, dupSuccess.duplicateCount)
    }

    // =========================================================================
    // Vector 6: resetAllFilters(), resetSessionFilters() and resetSolveFilters()
    // =========================================================================
    @Test
    fun testResetAllFiltersRestoresDefaultsAcrossAllDimensionsSimultaneously() = runTest(testDispatcher) {
        val s = insertSessionEntity(name = "ResetSession")
        insertSolvesForSession(s, listOf(9000L, 12000L, 15000L))

        viewModel = createViewModel()
        advanceUntilIdle()

        // Dirty all 7 filter/sort properties across both tabs
        viewModel.setSessionSort(SessionSortOrder.NAME_DESC)
        viewModel.setPuzzleScope(PuzzleScope.ALL_PUZZLES)
        viewModel.setSessionKindFilter(SessionKindFilter.AUTOMATIC_ONLY)
        viewModel.setSolveSort(SolveSortOrder.LOWEST_TIME)
        viewModel.setPenaltyFilter(PenaltyFilter.DNF_ONLY)
        viewModel.setTimeRangeFilter(10000L, 20000L)
        viewModel.setDateRangeFilter(DatePreset.CUSTOM, 1000L, 2000L)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(3, state.activeSessionFilterCount)
        assertEquals(4, state.activeSolveFilterCount)
        assertEquals(7, state.totalActiveFilterCount)

        // Reset all filters simultaneously
        viewModel.resetAllFilters()
        advanceUntilIdle()

        state = viewModel.uiState.value
        // Verify exact default restoration
        assertEquals(SessionSortOrder.MOST_RECENT, state.sessionSort)
        assertEquals(PuzzleScope.ACTIVE_PUZZLE, state.puzzleScope)
        assertEquals(SessionKindFilter.ALL, state.sessionKindFilter)
        assertEquals(SolveSortOrder.MOST_RECENT, state.solveSort)
        assertEquals(PenaltyFilter.ALL, state.penaltyFilter)
        assertEquals(TimeRangeFilter(), state.timeRangeFilter)
        assertEquals(DateRangeFilter(), state.dateRangeFilter)

        assertFalse(state.timeRangeFilter.isActive)
        assertFalse(state.dateRangeFilter.isActive)

        assertEquals(0, state.activeSessionFilterCount)
        assertEquals(0, state.activeSolveFilterCount)
        assertEquals(0, state.totalActiveFilterCount)
    }

    @Test
    fun testResetSessionAndSolveFiltersIndependently() = runTest(testDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()

        // Set non-defaults across both tabs
        viewModel.setSessionSort(SessionSortOrder.OLDEST)
        viewModel.setPuzzleScope(PuzzleScope.ALL_PUZZLES)
        viewModel.setSolveSort(SolveSortOrder.HIGHEST_TIME)
        viewModel.setPenaltyFilter(PenaltyFilter.PLUS_TWO_ONLY)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.activeSessionFilterCount)
        assertEquals(2, viewModel.uiState.value.activeSolveFilterCount)
        assertEquals(4, viewModel.uiState.value.totalActiveFilterCount)

        // Reset ONLY Session filters
        viewModel.resetSessionFilters()
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(0, state.activeSessionFilterCount)
        assertEquals(2, state.activeSolveFilterCount)
        assertEquals(2, state.totalActiveFilterCount)
        assertEquals(SessionSortOrder.MOST_RECENT, state.sessionSort)
        assertEquals(PuzzleScope.ACTIVE_PUZZLE, state.puzzleScope)
        // Solve filters untouched
        assertEquals(SolveSortOrder.HIGHEST_TIME, state.solveSort)
        assertEquals(PenaltyFilter.PLUS_TWO_ONLY, state.penaltyFilter)

        // Reset ONLY Solve filters
        viewModel.resetSolveFilters()
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals(0, state.activeSessionFilterCount)
        assertEquals(0, state.activeSolveFilterCount)
        assertEquals(0, state.totalActiveFilterCount)
        assertEquals(SolveSortOrder.MOST_RECENT, state.solveSort)
        assertEquals(PenaltyFilter.ALL, state.penaltyFilter)
    }

    // =========================================================================
    // Test Doubles & Helpers
    // =========================================================================

    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun loginWithGoogle(idToken: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
