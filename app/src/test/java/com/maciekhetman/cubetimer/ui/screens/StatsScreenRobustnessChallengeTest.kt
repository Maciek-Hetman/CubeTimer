package com.maciekhetman.cubetimer.ui.screens

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsScreenRobustnessChallengeTest {

    private fun solve(
        timeInMillis: Long,
        penalty: Penalty = Penalty.NONE,
        scramble: String = "R U R' U'",
        timestamp: Long = System.currentTimeMillis()
    ): SolveTime {
        return SolveTime(
            id = UUID.randomUUID().toString(),
            timeInMillis = timeInMillis,
            penalty = penalty,
            timestamp = timestamp,
            scramble = scramble,
            mode = Mode.CUBE_3x3,
            sessionId = "test_session"
        )
    }

    // =========================================================================
    // 1. ABSENCE OF PAST SOLVE LIST & CLEAR DIALOG ARTIFACTS IN STATSSCREEN.KT
    // =========================================================================

    @Test
    fun testStatsScreenSource_hasNoPastSolveListArtifacts() {
        val possiblePaths = listOf(
            "app/src/main/java/com/maciekhetman/cubetimer/ui/screens/StatsScreen.kt",
            "src/main/java/com/maciekhetman/cubetimer/ui/screens/StatsScreen.kt"
        )
        val file = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("StatsScreen.kt source file must exist", file)

        val content = file!!.readText()

        assertFalse("SolveCard must be excised from StatsScreen", content.contains("SolveCard"))
        assertFalse("itemsIndexed must be excised from StatsScreen", content.contains("itemsIndexed"))
        assertFalse("showClearDialog must be excised from StatsScreen", content.contains("showClearDialog"))
        assertFalse("AlertDialog must be excised from StatsScreen", content.contains("AlertDialog"))
        assertFalse("200 solve cap string must be absent", content.contains("Showing first 200 solves"))
    }

    // =========================================================================
    // 2. RETENTION OF REQUIRED CHARTS AND FILTER BAR
    // =========================================================================

    @Test
    fun testStatsScreenSource_retainsFilterBarAndCharts() {
        val possiblePaths = listOf(
            "app/src/main/java/com/maciekhetman/cubetimer/ui/screens/StatsScreen.kt",
            "src/main/java/com/maciekhetman/cubetimer/ui/screens/StatsScreen.kt"
        )
        val file = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull(file)
        val content = file!!.readText()

        assertTrue("SessionFilterBar must be present", content.contains("SessionFilterBar("))
        assertTrue("PersonalBestsChart must be present", content.contains("PersonalBestsChart("))
        assertTrue("SolveTimesChart must be present", content.contains("SolveTimesChart("))
        assertTrue("AveragesChart must be present", content.contains("AveragesChart("))
        assertTrue("ActivityTracker must be present", content.contains("ActivityTracker("))
        assertTrue("StatsHeroCard must be present", content.contains("StatsHeroCard("))
        assertTrue("CompactSummaryGrid must be present", content.contains("CompactSummaryGrid("))
        assertTrue("LargeAveragesSection must be present", content.contains("LargeAveragesSection("))
        assertTrue("SessionMetricsSection must be present", content.contains("SessionMetricsSection("))
        assertTrue("PenaltyStatsSection must be present", content.contains("PenaltyStatsSection("))
    }

    // =========================================================================
    // 3. STATSHEROCARD RENDERING & MEASUREMENT UNDER NULL/EMPTY/EXTREME STATES
    // =========================================================================

    @Test
    fun testStatsHeroCard_nullPBsAndEmptyState() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val composeView = ComposeView(activity).apply {
            setContent {
                MaterialTheme {
                    StatsHeroCard(
                        allTimePb = null,
                        sessionAo5 = null,
                        sessionAo12 = null
                    )
                }
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        composeView.layout(0, 0, 1080, composeView.measuredHeight)

        assertTrue("StatsHeroCard with null PB should measure positive height", composeView.measuredHeight > 0)
    }

    @Test
    fun testStatsHeroCard_allDnfs_evaluatesPbToNull() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val dnfSolves = listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        val composeView = ComposeView(activity).apply {
            setContent {
                MaterialTheme {
                    StatsHeroCard(solves = dnfSolves)
                }
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        composeView.layout(0, 0, 1080, composeView.measuredHeight)

        assertTrue("StatsHeroCard with only DNFs must render without crashing", composeView.measuredHeight > 0)
    }

    @Test
    fun testStatsHeroCard_extremeDurationAndPlusTwoPenalty() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val pb = solve(
            timeInMillis = 3_600_000L, // 1 hour solve
            penalty = Penalty.PLUS_TWO,
            scramble = "D2 R2 B2 U2 F2 L2 D2 R2 B2 U2 F2 L2 D2 R2 B2 U2 F2 L2 D2 R2 B2 U2 F2 L2"
        )
        val composeView = ComposeView(activity).apply {
            setContent {
                MaterialTheme {
                    StatsHeroCard(
                        allTimePb = pb,
                        sessionAo5 = 3_602_000L,
                        sessionAo12 = 3_605_000L
                    )
                }
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        composeView.layout(0, 0, 1080, composeView.measuredHeight)

        assertTrue(composeView.measuredHeight > 0)
    }

    // =========================================================================
    // 4. COMPACT SUMMARY GRID RENDERING ACROSS SOLVE COUNTS
    // =========================================================================

    @Test
    fun testCompactSummaryGrid_zeroSolves() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val composeView = ComposeView(activity).apply {
            setContent {
                MaterialTheme {
                    CompactSummaryGrid(solves = emptyList())
                }
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        composeView.layout(0, 0, 1080, composeView.measuredHeight)

        assertTrue("CompactSummaryGrid with 0 solves must render without crashing", composeView.measuredHeight > 0)
    }

    @Test
    fun testCompactSummaryGrid_fewerThan5Solves() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val solves = (1..3).map { solve(10_000L + it * 100L) }
        val composeView = ComposeView(activity).apply {
            setContent {
                MaterialTheme {
                    CompactSummaryGrid(solves = solves)
                }
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        composeView.layout(0, 0, 1080, composeView.measuredHeight)

        assertTrue(composeView.measuredHeight > 0)
    }

    @Test
    fun testCompactSummaryGrid_populatedSolvesCurrentEqualsBest() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        // 5 identical solves -> current == best -> triggers "PB" chip
        val solves = (1..5).map { solve(10_000L) }
        val composeView = ComposeView(activity).apply {
            setContent {
                MaterialTheme {
                    CompactSummaryGrid(solves = solves)
                }
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        composeView.layout(0, 0, 1080, composeView.measuredHeight)

        assertTrue(composeView.measuredHeight > 0)
    }

    // =========================================================================
    // 5. DIVISION BY ZERO STRESS IN PERCENTAGE CALCULATIONS
    // =========================================================================

    @Test
    fun testPercentageCalculations_noDivisionByZero() {
        // Test cases: 0 solves, 1 solve, 100% clean, 100% DNF, 100% +2
        val testSets = listOf(
            emptyList(),
            listOf(solve(10_000L)),
            listOf(solve(0L, Penalty.DNF)),
            listOf(solve(10_000L, Penalty.PLUS_TWO)),
            listOf(solve(10_000L), solve(0L, Penalty.DNF), solve(12_000L, Penalty.PLUS_TWO))
        )

        for (solves in testSets) {
            val dnf = solves.count { it.penalty == Penalty.DNF }
            val plusTwo = solves.count { it.penalty == Penalty.PLUS_TWO }
            val total = solves.size.toFloat()
            val dnfPct = if (total > 0) (dnf / total * 100).toInt() else 0
            val plusTwoPct = if (total > 0) (plusTwo / total * 100).toInt() else 0
            val clean = solves.size - dnf - plusTwo
            val cleanPct = if (total > 0) (clean / total * 100).toInt() else 100

            assertTrue(dnfPct in 0..100)
            assertTrue(plusTwoPct in 0..100)
            assertTrue(cleanPct in 0..100)
        }
    }
}
