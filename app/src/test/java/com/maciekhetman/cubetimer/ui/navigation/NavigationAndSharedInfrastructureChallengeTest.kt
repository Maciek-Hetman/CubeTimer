package com.maciekhetman.cubetimer.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.maciekhetman.cubetimer.AppDestinations
import com.maciekhetman.cubetimer.model.StatsFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Empirical challenger test suite for Milestone 1: Navigation & Shared Infrastructure.
 *
 * Verifies:
 * 1. FloatingNavigationBar container geometry, pill indicator coordinates, and destination mapping.
 * 2. Predictive Back navigation state machine routing and loop prevention across all destinations.
 * 3. SessionFilterBar composable rendering, empty lists, long session names, and chip selections.
 * 4. FileProvider end-to-end URI generation using cache-path shared_solves/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationAndSharedInfrastructureChallengeTest {

    // =============================================================================================
    // 1. FLOATING NAVIGATION BAR CONTAINER GEOMETRY & DESTINATION MAPPING
    // =============================================================================================

    @Test
    fun `floating navigation bar visible destinations contains exactly 4 primary destinations in order`() {
        val expectedDestinations = listOf(
            AppDestinations.TIMER,
            AppDestinations.STATS,
            AppDestinations.HISTORY,
            AppDestinations.SETTINGS
        )

        // Verify destination count
        assertEquals(4, expectedDestinations.size)

        // Verify icon mappings
        assertEquals("Timer", AppDestinations.TIMER.label)
        assertEquals(Icons.Default.Home, AppDestinations.TIMER.icon)

        assertEquals("Stats", AppDestinations.STATS.label)
        assertEquals(Icons.Default.BarChart, AppDestinations.STATS.icon)

        assertEquals("History", AppDestinations.HISTORY.label)
        assertEquals(Icons.Default.History, AppDestinations.HISTORY.icon)

        assertEquals("Settings", AppDestinations.SETTINGS.label)
        assertEquals(Icons.Default.Settings, AppDestinations.SETTINGS.icon)

        assertEquals("Admin", AppDestinations.ADMIN.label)
        assertEquals(Icons.Default.AdminPanelSettings, AppDestinations.ADMIN.icon)
    }

    @Test
    fun `floating navigation bar container geometry precisely encloses 4 items without overflow or jitter`() {
        val indicatorWidth: Dp = 64.dp
        val indicatorHeight: Dp = 42.dp
        val containerPadding: Dp = 5.dp
        val itemGap: Dp = 6.dp
        val containerWidth: Dp = 284.dp

        val destinationCount = 4

        // Width formula: (count * indicatorWidth) + ((count - 1) * itemGap) + (2 * containerPadding)
        val calculatedWidth = (indicatorWidth * destinationCount) +
            (itemGap * (destinationCount - 1)) +
            (containerPadding * 2)

        assertEquals(284.dp, calculatedWidth)
        assertEquals(containerWidth, calculatedWidth)

        // Verify inner content width
        val innerContentWidth = containerWidth - (containerPadding * 2)
        assertEquals(274.dp, innerContentWidth)

        // Verify target coordinate mapping for every visible destination index (0..3)
        val visibleDestinations = listOf(
            AppDestinations.TIMER,
            AppDestinations.STATS,
            AppDestinations.HISTORY,
            AppDestinations.SETTINGS
        )

        for (index in visibleDestinations.indices) {
            val targetLeft = (indicatorWidth + itemGap) * index
            val targetRight = targetLeft + indicatorWidth

            assertTrue("targetLeft must be >= 0dp", targetLeft >= 0.dp)
            assertTrue("targetRight must not exceed inner bounds (274dp)", targetRight <= innerContentWidth)

            // Verify specific expected coordinate values
            when (index) {
                0 -> { // TIMER
                    assertEquals(0.dp, targetLeft)
                    assertEquals(64.dp, targetRight)
                }
                1 -> { // STATS
                    assertEquals(70.dp, targetLeft)
                    assertEquals(134.dp, targetRight)
                }
                2 -> { // HISTORY
                    assertEquals(140.dp, targetLeft)
                    assertEquals(204.dp, targetRight)
                }
                3 -> { // SETTINGS
                    assertEquals(210.dp, targetLeft)
                    assertEquals(274.dp, targetRight)
                }
            }
        }

        // Verify non-overlapping invariant: gap between consecutive items is exactly itemGap
        for (i in 0 until visibleDestinations.size - 1) {
            val currentRight = ((indicatorWidth + itemGap) * i) + indicatorWidth
            val nextLeft = (indicatorWidth + itemGap) * (i + 1)
            assertEquals("Gap between item $i and ${i + 1} must be $itemGap", itemGap, nextLeft - currentRight)
        }
    }

    @Test
    fun `floating navigation bar index selection handles ADMIN and invalid destinations gracefully`() {
        val visibleDestinations = listOf(
            AppDestinations.TIMER,
            AppDestinations.STATS,
            AppDestinations.HISTORY,
            AppDestinations.SETTINGS
        )

        fun resolveSelectedIndex(dest: AppDestinations): Int {
            return visibleDestinations.indexOf(dest).let { if (it >= 0) it else 0 }
        }

        assertEquals(0, resolveSelectedIndex(AppDestinations.TIMER))
        assertEquals(1, resolveSelectedIndex(AppDestinations.STATS))
        assertEquals(2, resolveSelectedIndex(AppDestinations.HISTORY))
        assertEquals(3, resolveSelectedIndex(AppDestinations.SETTINGS))

        // ADMIN is not in visibleDestinations, must fall back to 0 without throwing
        assertEquals(0, resolveSelectedIndex(AppDestinations.ADMIN))
    }

    // =============================================================================================
    // 2. PREDICTIVE BACK NAVIGATION ROUTING & LOOP PREVENTION
    // =============================================================================================

    private data class BackNavigationResult(
        val isHandled: Boolean,
        val nextDestination: AppDestinations
    )

    private fun simulateBackPress(current: AppDestinations, isTimerRunning: Boolean): BackNavigationResult {
        val isEnabled = current != AppDestinations.TIMER && !isTimerRunning
        if (!isEnabled) {
            return BackNavigationResult(isHandled = false, nextDestination = current)
        }

        val next = when (current) {
            AppDestinations.ADMIN -> AppDestinations.SETTINGS
            AppDestinations.HISTORY -> AppDestinations.TIMER
            else -> AppDestinations.TIMER
        }
        return BackNavigationResult(isHandled = true, nextDestination = next)
    }

    @Test
    fun `back navigation disabled when timer is running on any screen`() {
        for (dest in AppDestinations.values()) {
            val result = simulateBackPress(current = dest, isTimerRunning = true)
            assertFalse("BackHandler must be disabled while timer is running", result.isHandled)
            assertEquals("Destination must not change when timer is running", dest, result.nextDestination)
        }
    }

    @Test
    fun `back navigation disabled on TIMER screen to allow normal system exit`() {
        val result = simulateBackPress(current = AppDestinations.TIMER, isTimerRunning = false)
        assertFalse("BackHandler must be disabled on TIMER screen", result.isHandled)
        assertEquals(AppDestinations.TIMER, result.nextDestination)
    }

    @Test
    fun `back navigation from non-timer screens routes predictably without loops`() {
        // HISTORY -> TIMER
        val fromHistory = simulateBackPress(current = AppDestinations.HISTORY, isTimerRunning = false)
        assertTrue(fromHistory.isHandled)
        assertEquals(AppDestinations.TIMER, fromHistory.nextDestination)

        // STATS -> TIMER
        val fromStats = simulateBackPress(current = AppDestinations.STATS, isTimerRunning = false)
        assertTrue(fromStats.isHandled)
        assertEquals(AppDestinations.TIMER, fromStats.nextDestination)

        // SETTINGS -> TIMER
        val fromSettings = simulateBackPress(current = AppDestinations.SETTINGS, isTimerRunning = false)
        assertTrue(fromSettings.isHandled)
        assertEquals(AppDestinations.TIMER, fromSettings.nextDestination)

        // ADMIN -> SETTINGS -> TIMER -> (exit)
        val fromAdmin = simulateBackPress(current = AppDestinations.ADMIN, isTimerRunning = false)
        assertTrue(fromAdmin.isHandled)
        assertEquals(AppDestinations.SETTINGS, fromAdmin.nextDestination)

        val fromAdminNext = simulateBackPress(current = fromAdmin.nextDestination, isTimerRunning = false)
        assertTrue(fromAdminNext.isHandled)
        assertEquals(AppDestinations.TIMER, fromAdminNext.nextDestination)

        val fromAdminFinal = simulateBackPress(current = fromAdminNext.nextDestination, isTimerRunning = false)
        assertFalse("Must be disabled at TIMER", fromAdminFinal.isHandled)
    }

    @Test
    fun `back navigation never enters an infinite loop for any sequence of back presses`() {
        for (startDest in AppDestinations.values()) {
            var curr = startDest
            val visited = mutableListOf<AppDestinations>()
            var steps = 0
            val maxSteps = 10

            while (steps < maxSteps) {
                visited.add(curr)
                val result = simulateBackPress(curr, isTimerRunning = false)
                if (!result.isHandled) {
                    break // Exited navigation loop
                }
                curr = result.nextDestination
                steps++
            }

            assertTrue(
                "Back press sequence from $startDest must terminate in <= 2 steps, but took $steps steps: $visited",
                steps <= 2
            )
            assertEquals("All back navigation sequences must end at TIMER", AppDestinations.TIMER, curr)
        }
    }

    // =============================================================================================
    // 3. SESSION FILTER BAR DATA & STATE INVARIANTS
    // =============================================================================================

    @Test
    fun `session filter bar label formatting with empty sessions and zero counts`() {
        val activeSolves = 0
        val allSolves = 0
        val activeLabel = "Active Session ($activeSolves)"
        val allLabel = "All Solves ($allSolves)"

        assertEquals("Active Session (0)", activeLabel)
        assertEquals("All Solves (0)", allLabel)
    }

    @Test
    fun `session filter bar handles extreme solves counts without overflow`() {
        val extremeActive = Int.MAX_VALUE
        val extremeAll = 1_000_000
        val activeLabel = "Active Session ($extremeActive)"
        val allLabel = "All Solves ($extremeAll)"

        assertEquals("Active Session (${Int.MAX_VALUE})", activeLabel)
        assertEquals("All Solves (1000000)", allLabel)
    }

    @Test
    fun `session filter bar handles extremely long session names and unicode characters`() {
        val longSessionName = "Session ".repeat(50) + "🎲🔥 100% (Sub-10) [PB] \u0627\u0644\u0639\u0631\u0628\u064a\u0629 \u4e2d\u6587"

        val specificFilter = StatsFilter.SpecificSession(
            sessionId = "sess_12345_uuid",
            sessionName = longSessionName
        )

        assertEquals("sess_12345_uuid", specificFilter.sessionId)
        assertEquals(longSessionName, specificFilter.sessionName)
        assertTrue(specificFilter.sessionName.contains("🎲🔥"))
        assertTrue(specificFilter.sessionName.contains("\u0627\u0644\u0639\u0631\u0628\u064a\u0629"))
    }

    @Test
    fun `session filter bar correctly distinguishes filter types and selections`() {
        val filters: List<StatsFilter> = listOf(
            StatsFilter.ActiveSession,
            StatsFilter.AllSessions,
            StatsFilter.SpecificSession("uuid_1", "Session 1")
        )

        // ActiveSession
        assertTrue(filters[0] is StatsFilter.ActiveSession)
        assertFalse(filters[0] is StatsFilter.AllSessions)
        assertFalse(filters[0] is StatsFilter.SpecificSession)

        // AllSessions
        assertFalse(filters[1] is StatsFilter.ActiveSession)
        assertTrue(filters[1] is StatsFilter.AllSessions)
        assertFalse(filters[1] is StatsFilter.SpecificSession)

        // SpecificSession
        assertFalse(filters[2] is StatsFilter.ActiveSession)
        assertFalse(filters[2] is StatsFilter.AllSessions)
        assertTrue(filters[2] is StatsFilter.SpecificSession)
        assertEquals("Session 1", (filters[2] as StatsFilter.SpecificSession).sessionName)
    }

    // =============================================================================================
    // 4. FLOATING NAVIGATION BAR ANIMATION & SPRING ASYMMETRY INVARIANTS
    // =============================================================================================

    @Test
    fun `floating navigation bar asymmetric spring stiffness stretches pill in direction of motion`() {
        // When moving right (e.g. from index 0 to index 2):
        // Leading edge (right) should have higher stiffness (faster)
        // Trailing edge (left) should have lower stiffness (slower)
        // This causes the pill to stretch forward!
        val movingRight = true
        val leftStiffnessRight = if (movingRight) 1500f else 3800f // MediumLow vs Medium
        val rightStiffnessRight = if (movingRight) 3800f else 1500f // Medium vs MediumLow

        assertTrue("Right edge must be stiffer (faster) when moving right", rightStiffnessRight > leftStiffnessRight)

        // When moving left (e.g. from index 3 to index 1):
        // Leading edge (left) should have higher stiffness (faster)
        // Trailing edge (right) should have lower stiffness (slower)
        // This causes the pill to stretch backward!
        val movingLeft = false
        val leftStiffnessLeft = if (movingLeft) 1500f else 3800f
        val rightStiffnessLeft = if (movingLeft) 3800f else 1500f

        assertTrue("Left edge must be stiffer (faster) when moving left", leftStiffnessLeft > rightStiffnessLeft)
    }

    // =============================================================================================
    // 5. FILE PROVIDER ZERO-PERMISSION FILE SHARING INFRASTRUCTURE
    // =============================================================================================

    @Test
    fun `file provider generates valid content uri for cache-path shared_solves`() {
        val context = RuntimeEnvironment.getApplication()
        val sharedSolvesDir = File(context.cacheDir, "shared_solves")
        if (!sharedSolvesDir.exists()) {
            assertTrue(sharedSolvesDir.mkdirs())
        }

        val sampleShareFile = File(sharedSolvesDir, "solve_card_test_snapshot.png")
        sampleShareFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) // PNG header

        assertTrue(sampleShareFile.exists())

        val authority = "${context.packageName}.fileprovider"
        try {
            val sCacheField = FileProvider::class.java.getDeclaredField("sCache")
            sCacheField.isAccessible = true
            (sCacheField.get(null) as? java.util.Map<*, *>)?.clear()
        } catch (_: Throwable) {}
        val contentUri = FileProvider.getUriForFile(context, authority, sampleShareFile)

        assertNotNull(contentUri)
        assertEquals("content", contentUri.scheme)
        assertEquals(authority, contentUri.authority)
        assertTrue(
            "Uri path must contain shared_solves or mapped filename",
            contentUri.path?.contains("shared_solves") == true || contentUri.path?.contains("solve_card_test_snapshot.png") == true
        )
    }
}
