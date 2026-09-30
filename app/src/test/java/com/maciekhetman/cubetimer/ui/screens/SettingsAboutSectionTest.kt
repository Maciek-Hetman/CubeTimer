package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.maciekhetman.cubetimer.ui.dialogs.OpenSourceLicensesDialog
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

    private fun render(privacyPolicyUrl: String? = null) {
        composeTestRule.setContent {
            MaterialTheme {
                AboutSection(
                    versionName = "1.2.3",
                    onOpenUrl = { openedUrls += it },
                    onLicensesClick = { licensesClicks++ },
                    privacyPolicyUrl = privacyPolicyUrl
                )
            }
        }
    }

    @Test
    fun showsTheAppVersion() {
        render()

        composeTestRule.onNodeWithText("Version").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.2.3").assertIsDisplayed()
    }

    @Test
    fun sourceCodeRowOpensTheRepository() {
        render()

        composeTestRule.onNodeWithText("Source code").performClick()

        assertEquals(listOf(SOURCE_CODE_URL), openedUrls)
    }

    @Test
    fun reportAProblemRowOpensTheIssueTracker() {
        render()

        composeTestRule.onNodeWithText("Report a problem").performClick()

        assertEquals(listOf(ISSUES_URL), openedUrls)
    }

    @Test
    fun privacyPolicyRowIsHiddenUntilAPolicyIsPublished() {
        render(privacyPolicyUrl = null)

        composeTestRule.onNodeWithText("Privacy policy").assertDoesNotExist()
    }

    @Test
    fun privacyPolicyRowOpensThePublishedPolicy() {
        render(privacyPolicyUrl = "https://example.com/privacy")

        composeTestRule.onNodeWithText("Privacy policy").performClick()

        assertEquals(listOf("https://example.com/privacy"), openedUrls)
    }

    @Test
    fun licensesRowInvokesOnLicensesClickWithoutOpeningALink() {
        render()

        composeTestRule.onNodeWithText("Open-source licenses").performClick()

        assertEquals(1, licensesClicks)
        assertEquals(emptyList<String>(), openedUrls)
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
