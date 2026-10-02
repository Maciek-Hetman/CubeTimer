package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.maciekhetman.cubetimer.ui.dialogs.OpenSourceLicensesDialog
import com.maciekhetman.cubetimer.ui.dialogs.ReleaseNotesDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings' "About" section: version, outbound links and the open-source licenses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAboutSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val openedUrls = mutableListOf<String>()
    private var licensesClicks = 0
    private var releaseNotesClicks = 0

    private fun render() {
        composeTestRule.setContent {
            MaterialTheme {
                // Taller than the test window; on the screen it scrolls with the rest of Settings.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AboutSection(
                        versionName = "1.2.3",
                        onOpenUrl = { openedUrls += it },
                        onReleaseNotesClick = { releaseNotesClicks++ },
                        onLicensesClick = { licensesClicks++ }
                    )
                }
            }
        }
    }

    @Test
    fun showsTheAppVersion() {
        render()

        composeTestRule.onNodeWithText("Version").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("1.2.3").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sourceCodeRowOpensTheRepository() {
        render()

        composeTestRule.onNodeWithText("Source code").performScrollTo().performClick()

        assertEquals(listOf(SOURCE_CODE_URL), openedUrls)
    }

    @Test
    fun reportAProblemRowOpensTheIssueTracker() {
        render()

        composeTestRule.onNodeWithText("Report a problem").performScrollTo().performClick()

        assertEquals(listOf(ISSUES_URL), openedUrls)
    }

    @Test
    fun websiteRowOpensTheAboutPage() {
        render()

        composeTestRule.onNodeWithText("Website").performScrollTo().performClick()

        assertEquals(listOf("https://cubetimer.cc/about"), openedUrls)
    }

    @Test
    fun privacyPolicyRowOpensThePolicy() {
        render()

        composeTestRule.onNodeWithText("Privacy policy").performScrollTo().performClick()

        assertEquals(listOf("https://cubetimer.cc/privacy"), openedUrls)
    }

    @Test
    fun deleteAccountRowOpensTheWebAccountPage() {
        render()

        composeTestRule.onNodeWithText("Delete account on the web").performScrollTo().performClick()

        assertEquals(listOf("https://cubetimer.cc/account"), openedUrls)
    }

    @Test
    fun licensesRowInvokesOnLicensesClickWithoutOpeningALink() {
        render()

        composeTestRule.onNodeWithText("Open-source licenses").performScrollTo().performClick()

        assertEquals(1, licensesClicks)
        assertEquals(emptyList<String>(), openedUrls)
    }

    @Test
    fun releaseNotesRowInvokesOnReleaseNotesClickWithoutOpeningALink() {
        render()

        composeTestRule.onNodeWithText("Release notes").performScrollTo().performClick()

        assertEquals(1, releaseNotesClicks)
        assertEquals(emptyList<String>(), openedUrls)
    }

    @Test
    fun releaseNotesDialogListsTheCurrentVersionsChangesAndCloses() {
        var dismissed = 0
        composeTestRule.setContent {
            MaterialTheme {
                ReleaseNotesDialog(onDismiss = { dismissed++ })
            }
        }

        composeTestRule.onNodeWithText("Version 2.0.0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Optional WCA inspection", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Close").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun licensesDialogNamesTheAppLicenseAndOffersItsFullText() {
        var viewLicenseClicks = 0
        composeTestRule.setContent {
            MaterialTheme {
                OpenSourceLicensesDialog(onViewLicense = { viewLicenseClicks++ }, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("TNoodle scrambles").assertIsDisplayed()
        composeTestRule.onNodeWithText("View GPL-3.0").performClick()

        assertEquals(1, viewLicenseClicks)
    }
}
