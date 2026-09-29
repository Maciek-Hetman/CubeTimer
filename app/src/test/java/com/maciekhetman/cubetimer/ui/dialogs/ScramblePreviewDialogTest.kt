package com.maciekhetman.cubetimer.ui.dialogs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScramblePreviewDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val scramble = "R U R' U' R' F R2 U' R' U' R U R' F'"

    @Test
    fun copyButton_putsTheScrambleOnTheComposeClipboard() {
        val clipboard = RecordingClipboard(ApplicationProvider.getApplicationContext())

        composeTestRule.setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    ScramblePreviewDialog(
                        scramble = scramble,
                        mode = Mode.CUBE_3x3,
                        onDismiss = {},
                        onGenerateNewScramble = {}
                    )
                }
            }
        }
        assertNull(clipboard.entry)

        composeTestRule.onNodeWithContentDescription("Copy scramble").performClick()
        composeTestRule.waitForIdle()

        assertEquals(scramble, clipboard.entry?.clipData?.getItemAt(0)?.text?.toString())
    }

    private class RecordingClipboard(private val context: Context) : Clipboard {
        var entry: ClipEntry? = null

        override suspend fun getClipEntry(): ClipEntry? = entry

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override val nativeClipboard: ClipboardManager
            get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
}
