package com.maciekhetman.cubetimer.ui.components

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeepScreenOnTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun keepsScreenOnOnlyWhileInComposition() {
        var onTimerScreen by mutableStateOf(true)
        lateinit var view: View

        composeTestRule.setContent {
            view = LocalView.current
            if (onTimerScreen) KeepScreenOn()
        }
        composeTestRule.waitForIdle()
        assertTrue(view.keepScreenOn)

        // e.g. navigating from Timer to Stats/History/Settings
        onTimerScreen = false
        composeTestRule.waitForIdle()
        assertFalse(view.keepScreenOn)
    }
}
