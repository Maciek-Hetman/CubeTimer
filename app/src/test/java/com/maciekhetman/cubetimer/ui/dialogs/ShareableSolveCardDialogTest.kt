package com.maciekhetman.cubetimer.ui.dialogs

import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareableSolveCardDialogTest {

    @Test
    fun `formatShareText produces correct output for clean PB solve`() {
        val solve = SolveTime(
            id = "solve-123",
            timeInMillis = 11550L,
            penalty = Penalty.NONE,
            timestamp = 1788687000000L,
            scramble = "R U R' U' R' F R2 U' R' U' R U R' F'",
            mode = Mode.CUBE_3x3
        )

        val shareText = SolveShareHelper.formatShareText(
            solve = solve,
            isPb = true,
            pbDeltaText = "PB (-0.85s vs 12.40s)"
        )

        assertTrue(shareText.contains("CubeTimer - 3x3 Solve"))
        assertTrue(shareText.contains("Time: 11.55s [PB (-0.85s vs 12.40s)]"))
        assertTrue(shareText.contains("Scramble: R U R' U' R' F R2 U' R' U' R U R' F'"))
        assertTrue(shareText.contains("Date: "))
        assertTrue(shareText.contains("Timer: Screen / Touch"))
    }

    @Test
    fun `formatShareText produces correct output for plus two non-PB solve`() {
        val solve = SolveTime(
            id = "solve-456",
            timeInMillis = 10500L,
            penalty = Penalty.PLUS_TWO,
            timestamp = 1788687000000L,
            scramble = "U2 R2 F2 U",
            mode = Mode.CUBE_2x2
        )

        val shareText = SolveShareHelper.formatShareText(
            solve = solve,
            isPb = false,
            pbDeltaText = null
        )

        assertTrue(shareText.contains("CubeTimer - 2x2 Solve"))
        assertTrue(shareText.contains("Time: 12.50s (+2)"))
        assertFalse(shareText.contains("[PB"))
        assertTrue(shareText.contains("Scramble: U2 R2 F2 U"))
        assertTrue(shareText.contains("Timer: Screen / Touch"))
    }

    @Test
    fun `formatShareText produces correct output for DNF solve`() {
        val solve = SolveTime(
            id = "solve-789",
            timeInMillis = 9500L,
            penalty = Penalty.DNF,
            timestamp = 1788687000000L,
            scramble = "R U R'",
            mode = Mode.PYRAMINX
        )

        val shareText = SolveShareHelper.formatShareText(
            solve = solve,
            isPb = false,
            pbDeltaText = null
        )

        assertTrue(shareText.contains("CubeTimer - Pyraminx Solve"))
        assertTrue(shareText.contains("Time: DNF"))
        assertFalse(shareText.contains("[PB"))
        assertTrue(shareText.contains("Scramble: R U R'"))
    }

    @Test
    fun `saveBitmapToShareCache creates PNG and generates valid FileProvider URI`() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)

        val file = SolveShareHelper.saveBitmapToShareCache(context, bitmap, "test_solve_abc")

        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        assertTrue(file.name.startsWith("solve_testsolv"))
        assertTrue(file.name.endsWith(".png"))

        // Verify PNG magic bytes
        val magicBytes = ByteArray(4)
        FileInputStream(file).use { it.read(magicBytes) }
        assertEquals(0x89.toByte(), magicBytes[0])
        assertEquals(0x50.toByte(), magicBytes[1]) // 'P'
        assertEquals(0x4E.toByte(), magicBytes[2]) // 'N'
        assertEquals(0x47.toByte(), magicBytes[3]) // 'G'

        // Verify FileProvider URI resolution for share_images path
        val authority = "${context.packageName}.fileprovider"
        try {
            val sCacheField = FileProvider::class.java.getDeclaredField("sCache")
            sCacheField.isAccessible = true
            (sCacheField.get(null) as? java.util.Map<*, *>)?.clear()
        } catch (_: Throwable) {}
        val contentUri = FileProvider.getUriForFile(context, authority, file)

        assertNotNull(contentUri)
        assertEquals("content", contentUri.scheme)
        assertEquals(authority, contentUri.authority)
        assertTrue(
            "Uri path must contain share_images",
            contentUri.path?.contains("share_images") == true
        )
    }

    @Test
    fun `pictureCaptureState returns null when dimensions are zero`() {
        val state = PictureCaptureState()
        assertNull(state.captureBitmap())
    }

    @Test
    fun `pictureCaptureState returns bitmap when dimensions are set`() {
        val state = PictureCaptureState()
        state.widthPx = 100
        state.heightPx = 100

        val canvas = state.picture.beginRecording(100, 100)
        canvas.drawColor(android.graphics.Color.BLUE)
        state.picture.endRecording()

        val bitmap = state.captureBitmap()
        assertNotNull(bitmap)
        assertEquals(100, bitmap!!.width)
        assertEquals(100, bitmap.height)
    }

    @Test
    fun `launchShareIntent starts chooser with image and permissions`() {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = File(context.cacheDir, "test.png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val dummyUri = android.net.Uri.fromFile(dummyFile)
        val shareText = "CubeTimer Solve Details"

        SolveShareHelper.launchShareIntent(context, dummyUri, shareText)

        val nextStartedActivity = shadowOf(context).nextStartedActivity
        assertNotNull(nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, nextStartedActivity.action)

        val targetIntent = nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(targetIntent)
        assertEquals(Intent.ACTION_SEND, targetIntent!!.action)
        assertEquals("image/png", targetIntent.type)
        assertEquals(dummyUri, targetIntent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertEquals(shareText, targetIntent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(
            targetIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
    }

    @Test
    fun `launchTextShareIntent starts chooser with text`() {
        val context = RuntimeEnvironment.getApplication()
        val shareText = "CubeTimer Text Solve"

        SolveShareHelper.launchTextShareIntent(context, shareText)

        val nextStartedActivity = shadowOf(context).nextStartedActivity
        assertNotNull(nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, nextStartedActivity.action)

        val targetIntent = nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(targetIntent)
        assertEquals(Intent.ACTION_SEND, targetIntent!!.action)
        assertEquals("text/plain", targetIntent.type)
        assertEquals(shareText, targetIntent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
