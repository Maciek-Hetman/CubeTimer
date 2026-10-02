package com.maciekhetman.cubetimer.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.AppDestinations
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.ui.components.displaySessionName
import com.maciekhetman.cubetimer.ui.screens.AccountSection
import com.maciekhetman.cubetimer.ui.screens.syncConflictHint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app follows the system (or per-app) language: English by default, Polish under `pl`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalizationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources: Resources
        get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun englishIsTheDefault() {
        assertEquals("Settings", resources.getString(AppDestinations.SETTINGS.labelRes))
        assertEquals("2 solves", resources.getQuantityString(R.plurals.solve_count, 2, 2))
    }

    @Test
    @Config(qualifiers = "pl")
    fun polishStringsAreUsedUnderPolishLocale() {
        assertEquals("Ustawienia", resources.getString(AppDestinations.SETTINGS.labelRes))
        assertEquals("Historia", resources.getString(AppDestinations.HISTORY.labelRes))
    }

    @Test
    @Config(qualifiers = "pl")
    fun polishPluralsUseAllFourForms() {
        assertEquals("1 ułożenie", resources.getQuantityString(R.plurals.solve_count, 1, 1))
        assertEquals("3 ułożenia", resources.getQuantityString(R.plurals.solve_count, 3, 3))
        assertEquals("5 ułożeń", resources.getQuantityString(R.plurals.solve_count, 5, 5))
        assertEquals("12 ułożeń", resources.getQuantityString(R.plurals.solve_count, 12, 12))
        assertEquals("22 ułożenia", resources.getQuantityString(R.plurals.solve_count, 22, 22))
        assertEquals("25 ułożeń", resources.getQuantityString(R.plurals.solve_count, 25, 25))

        assertEquals("1 konflikt wymaga uwagi", syncConflictHint(resources, 1))
        assertEquals("2 konflikty wymagają uwagi", syncConflictHint(resources, 2))
        assertEquals("5 konfliktów wymaga uwagi", syncConflictHint(resources, 5))
    }

    @Test
    fun automaticSessionNamesKeepTheirStoredFormInEnglish() {
        assertEquals("30 aug 2026 morning", displaySessionName(resources, "30 aug 2026 morning"))
        assertEquals("1 sep 2026 night 2", displaySessionName(resources, "1 sep 2026 night 2"))
    }

    @Test
    @Config(qualifiers = "pl")
    fun automaticSessionNamesAreShownInPolish() {
        assertEquals("30 sie 2026 rano", displaySessionName(resources, "30 aug 2026 morning"))
        assertEquals("1 wrz 2026 noc 2", displaySessionName(resources, "1 sep 2026 night 2"))
        assertEquals("15 paź 2026 popołudnie", displaySessionName(resources, "15 oct 2026 afternoon"))
        assertEquals("3 maj 2026 wieczór 12", displaySessionName(resources, "3 may 2026 evening 12"))
    }

    @Test
    @Config(qualifiers = "pl")
    fun otherSessionNamesAreLeftAlone() {
        assertEquals("Trening OH", displaySessionName(resources, "Trening OH"))
        // Not the automatic pattern: unknown month, missing day part, trailing text.
        assertEquals("30 abc 2026 morning", displaySessionName(resources, "30 abc 2026 morning"))
        assertEquals("30 aug 2026", displaySessionName(resources, "30 aug 2026"))
        assertEquals("30 aug 2026 morning practice", displaySessionName(resources, "30 aug 2026 morning practice"))
    }

    @Test
    @Config(qualifiers = "pl")
    fun composeScreensRenderPolish() {
        composeTestRule.setContent {
            MaterialTheme {
                AccountSection(
                    syncUiState = SyncUiState(),
                    onSyncClick = {},
                    authState = AuthState.Guest,
                    onAuthClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Zaloguj się").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zachowaj kopię ułożeń i synchronizuj je między urządzeniami").assertIsDisplayed()
    }
}
