package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingSliderRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testDisplaysTitleAndFormattedValue() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingSliderRow(
                    title = "Scramble size",
                    value = 100,
                    valueRange = 70f..140f,
                    steps = 13,
                    valueFormatter = { "$it%" },
                    onValueChangeFinished = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Scramble size").assertIsDisplayed()
        composeTestRule.onNodeWithText("100%").assertIsDisplayed()
    }

    @Test
    fun testClampsOutOfBoundsValuesToRange() {
        // Value 50 below min 70
        composeTestRule.setContent {
            MaterialTheme {
                SettingSliderRow(
                    title = "Scramble size",
                    value = 50,
                    valueRange = 70f..140f,
                    steps = 13,
                    valueFormatter = { "$it%" },
                    onValueChangeFinished = {}
                )
            }
        }

        composeTestRule.onNodeWithText("70%").assertIsDisplayed()
    }

    @Test
    fun testClampsOverBoundsValuesToRange() {
        // Value 200 above max 140
        composeTestRule.setContent {
            MaterialTheme {
                SettingSliderRow(
                    title = "Scramble size",
                    value = 200,
                    valueRange = 70f..140f,
                    steps = 13,
                    valueFormatter = { "$it%" },
                    onValueChangeFinished = {}
                )
            }
        }

        composeTestRule.onNodeWithText("140%").assertIsDisplayed()
    }

    @Test
    fun testAppliesCustomModifier() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingSliderRow(
                    title = "Scramble size",
                    value = 100,
                    valueRange = 70f..140f,
                    steps = 13,
                    modifier = Modifier.testTag("test_slider_row"),
                    valueFormatter = { "$it%" },
                    onValueChangeFinished = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("test_slider_row").assertIsDisplayed()
    }

    @Test
    fun testDefaultFormatterFormatsMilliseconds() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingSliderRow(
                    title = "Start delay",
                    value = 300,
                    valueRange = 200f..1000f,
                    steps = 7,
                    onValueChangeFinished = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Start delay").assertIsDisplayed()
        composeTestRule.onNodeWithText("300ms").assertIsDisplayed()
    }

    @Test
    fun testSliderNodeHasExpectedTestTagAndOperatesProgressAction() {
        var finishedValue: Int? = null
        composeTestRule.setContent {
            MaterialTheme {
                SettingSliderRow(
                    title = "Scramble size",
                    value = 100,
                    valueRange = 70f..140f,
                    steps = 13,
                    valueFormatter = { "$it%" },
                    onValueChangeFinished = { finishedValue = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("scramble_size_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scramble_size_slider")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {
                it(125f)
            }
        composeTestRule.waitForIdle()

        assertEquals(125, finishedValue)
        composeTestRule.onNodeWithText("125%").assertIsDisplayed()
    }
}
