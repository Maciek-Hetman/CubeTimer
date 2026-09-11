package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistorySolveCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createSolve(
        timeInMillis: Long = 12340L,
        penalty: Penalty = Penalty.NONE,
        scramble: String = "R U R' U'"
    ): SolveTime {
        return SolveTime(
            id = UUID.randomUUID().toString(),
            timeInMillis = timeInMillis,
            penalty = penalty,
            timestamp = System.currentTimeMillis(),
            scramble = scramble,
            mode = Mode.CUBE_3x3,
            sessionId = "test_session"
        )
    }

    @Test
    fun testClickPlusTwo_triggersCallback() {
        var plusTwoToggled = false
        val solve = createSolve(penalty = Penalty.NONE)

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = solve,
                    solveNumber = 1,
                    onTogglePlusTwo = { plusTwoToggled = true },
                    onToggleDnf = {},
                    onDelete = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("history_action_plus_two").performClick()
        assertTrue("onTogglePlusTwo should be invoked when +2 chip is clicked", plusTwoToggled)
    }

    @Test
    fun testClickDnf_triggersCallback() {
        var dnfToggled = false
        val solve = createSolve(penalty = Penalty.NONE)

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = solve,
                    solveNumber = 1,
                    onTogglePlusTwo = {},
                    onToggleDnf = { dnfToggled = true },
                    onDelete = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("history_action_dnf").performClick()
        assertTrue("onToggleDnf should be invoked when DNF chip is clicked", dnfToggled)
    }

    @Test
    fun testClickDelete_triggersCallback() {
        var deleted = false
        val solve = createSolve(penalty = Penalty.NONE)

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = solve,
                    solveNumber = 1,
                    onTogglePlusTwo = {},
                    onToggleDnf = {},
                    onDelete = { deleted = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("history_action_delete").performClick()
        assertTrue("onDelete should be invoked when delete icon is clicked", deleted)
    }

    @Test
    fun testChipsShowSelectedState() {
        val plusTwoSolve = createSolve(penalty = Penalty.PLUS_TWO)

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = plusTwoSolve,
                    solveNumber = 1,
                    onTogglePlusTwo = {},
                    onToggleDnf = {},
                    onDelete = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("history_action_plus_two").assertIsSelected()
        composeTestRule.onNodeWithTag("history_action_dnf").assertIsNotSelected()
    }

    @Test
    fun testDnfChipShowsSelectedState() {
        val dnfSolve = createSolve(penalty = Penalty.DNF)

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = dnfSolve,
                    solveNumber = 1,
                    onTogglePlusTwo = {},
                    onToggleDnf = {},
                    onDelete = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("history_action_dnf").assertIsSelected()
        composeTestRule.onNodeWithTag("history_action_plus_two").assertIsNotSelected()
    }

    @Test
    fun testActionsDoNotTriggerCardOnClick() {
        var cardClicked = false
        var plusTwoClicked = false
        var dnfClicked = false
        var deleteClicked = false
        val solve = createSolve(penalty = Penalty.NONE)

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = solve,
                    solveNumber = 1,
                    onClick = { cardClicked = true },
                    onTogglePlusTwo = { plusTwoClicked = true },
                    onToggleDnf = { dnfClicked = true },
                    onDelete = { deleteClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("history_action_plus_two").performClick()
        assertTrue(plusTwoClicked)
        org.junit.Assert.assertFalse("Clicking +2 chip should not invoke card onClick", cardClicked)

        composeTestRule.onNodeWithTag("history_action_dnf").performClick()
        assertTrue(dnfClicked)
        org.junit.Assert.assertFalse("Clicking DNF chip should not invoke card onClick", cardClicked)

        composeTestRule.onNodeWithTag("history_action_delete").performClick()
        assertTrue(deleteClicked)
        org.junit.Assert.assertFalse("Clicking delete icon should not invoke card onClick", cardClicked)
    }

    @Test
    fun testNarrowWidthLayout_320dp_rendersWithoutOverflow() {
        val solve = createSolve(penalty = Penalty.PLUS_TWO)

        composeTestRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    HistorySolveCard(
                        solve = solve,
                        solveNumber = 1,
                        onTogglePlusTwo = {},
                        onToggleDnf = {},
                        onDelete = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("history_action_plus_two").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history_action_dnf").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history_action_delete").assertIsDisplayed()
    }

    @Test
    fun testExtremeFontScaleAndNarrowWidth_280dp_rendersAndOperatesCleanly() {
        var plusTwoToggled = false
        var dnfToggled = false
        var deleteClicked = false
        val solve = createSolve(penalty = Penalty.NONE)

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.5f)
            ) {
                MaterialTheme {
                    Box(modifier = Modifier.width(280.dp)) {
                        HistorySolveCard(
                            solve = solve,
                            solveNumber = 999,
                            onTogglePlusTwo = { plusTwoToggled = true },
                            onToggleDnf = { dnfToggled = true },
                            onDelete = { deleteClicked = true }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("history_action_plus_two").assertIsDisplayed().performClick()
        assertTrue(plusTwoToggled)

        composeTestRule.onNodeWithTag("history_action_dnf").assertIsDisplayed().performClick()
        assertTrue(dnfToggled)

        composeTestRule.onNodeWithTag("history_action_delete").assertIsDisplayed().performClick()
        assertTrue(deleteClicked)
    }

    @Test
    fun testCrossPenaltyToggling_whenDnf_canTriggerPlusTwo() {
        var plusTwoToggled = false
        val solve = createSolve(penalty = Penalty.DNF)

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = solve,
                    solveNumber = 1,
                    onTogglePlusTwo = { plusTwoToggled = true },
                    onToggleDnf = {},
                    onDelete = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("history_action_dnf").assertIsSelected()
        composeTestRule.onNodeWithTag("history_action_plus_two").assertIsNotSelected()
        composeTestRule.onNodeWithTag("history_action_plus_two").performClick()
        assertTrue(plusTwoToggled)
    }

    @Test
    fun testMoreVertOptionsRemovedForCompactCard() {
        val solve = createSolve()

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = solve,
                    solveNumber = 1,
                    onTogglePlusTwo = {},
                    onToggleDnf = {},
                    onDelete = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Solve options").assertDoesNotExist()
    }

    @Test
    fun testClickableIndicatorDisplayedAndScrambleRemoved() {
        val solve = createSolve(scramble = "D2 R2 F2 U2 R' B2")

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = solve,
                    solveNumber = 1,
                    onTogglePlusTwo = {},
                    onToggleDnf = {},
                    onDelete = {}
                )
            }
        }

        // Verify clickable indicator icon is displayed
        composeTestRule.onNodeWithContentDescription("View solve details").assertIsDisplayed()

        // Verify scramble is not shown on the compact card
        composeTestRule.onNodeWithText("D2 R2 F2 U2 R' B2").assertDoesNotExist()
    }
}

