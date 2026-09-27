package com.maciekhetman.cubetimer.ui.components

import androidx.compose.ui.graphics.Color
import com.maciekhetman.cubetimer.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Scramble2DPreviewTest {

    @Before
    fun setUp() {
        ScramblePreviewParser.clearCache()
    }

    @Test
    fun `parse 3x3 cube scramble generates 54 rectangle elements`() {
        val scramble = "R' D2 F2 R' D2 B2 L' F2 R' B' D' F' U2 R2 U L' B2 D2 U"
        val result = ScramblePreviewParser.parse(scramble, Mode.CUBE_3x3)

        assertTrue(result is ScramblePreviewResult.Success)
        val model = (result as ScramblePreviewResult.Success).model
        assertTrue(model.width > 0)
        assertTrue(model.height > 0)
        assertEquals(54, model.elements.filterIsInstance<PuzzleDrawingElement.RectElement>().size)
    }

    @Test
    fun `parse 2x2 cube scramble generates 24 rectangle elements`() {
        val scramble = "R' U F' R U2 R' U' F' R2 U'"
        val result = ScramblePreviewParser.parse(scramble, Mode.CUBE_2x2)

        assertTrue(result is ScramblePreviewResult.Success)
        val model = (result as ScramblePreviewResult.Success).model
        assertEquals(24, model.elements.filterIsInstance<PuzzleDrawingElement.RectElement>().size)
    }

    @Test
    fun `parse 4x4 cube scramble generates 96 rectangle elements`() {
        val scramble = "Rw2 Fw2 U D' Rw Fw' L' Fw2 R Fw' Rw Fw' U2 Fw Rw2 F2 D2 Fw2"
        val result = ScramblePreviewParser.parse(scramble, Mode.CUBE_4x4)

        assertTrue(result is ScramblePreviewResult.Success)
        val model = (result as ScramblePreviewResult.Success).model
        assertEquals(96, model.elements.filterIsInstance<PuzzleDrawingElement.RectElement>().size)
    }

    @Test
    fun `parse 5x5 cube scramble generates 150 rectangle elements`() {
        val scramble = "B' Lw2 D2 Bw' L' Dw2 F' B' Lw' R2 B' Dw2 B' Fw D B' Lw2 Bw'"
        val result = ScramblePreviewParser.parse(scramble, Mode.CUBE_5x5)

        assertTrue(result is ScramblePreviewResult.Success)
        val model = (result as ScramblePreviewResult.Success).model
        assertEquals(150, model.elements.filterIsInstance<PuzzleDrawingElement.RectElement>().size)
    }

    @Test
    fun `parse Pyraminx scramble generates 36 path elements`() {
        val scramble = "U' L' B' U R B' L' R' u l' b"
        val result = ScramblePreviewParser.parse(scramble, Mode.PYRAMINX)
        assertTrue(result is ScramblePreviewResult.Success)

        val model = (result as ScramblePreviewResult.Success).model
        assertEquals(36, model.elements.filterIsInstance<PuzzleDrawingElement.PathElement>().size)
    }

    @Test
    fun `parse Megaminx scramble generates 132 path elements and 2 text elements`() {
        val scramble = "R++ D++ R-- D++ R++ D-- R-- D++ R++ D-- U\n" +
            "R++ D-- R-- D++ R-- D-- R++ D-- R-- D++ U\n" +
            "R-- D++ R++ D-- R-- D-- R++ D++ R++ D++ U\n" +
            "R++ D++ R-- D++ R-- D++ R-- D++ R++ D-- U'\n" +
            "R-- D-- R-- D-- R++ D++ R-- D++ R++ D-- U'\n" +
            "R-- D++ R-- D-- R-- D++ R++ D-- R++ D-- U'\n" +
            "R-- D-- R++ D-- R-- D-- R++ D-- R++ D-- U'"
        val result = ScramblePreviewParser.parse(scramble, Mode.MEGAMINX)

        assertTrue(result is ScramblePreviewResult.Success)
        val model = (result as ScramblePreviewResult.Success).model
        assertEquals(132, model.elements.filterIsInstance<PuzzleDrawingElement.PathElement>().size)
        val textElements = model.elements.filterIsInstance<PuzzleDrawingElement.TextElement>()
        assertEquals(2, textElements.size)
        assertEquals(setOf("U", "F"), textElements.map { it.text }.toSet())
    }

    @Test
    fun `empty scramble with showSolvedIfEmpty renders solved state net`() {
        val result = ScramblePreviewParser.parse("", Mode.CUBE_3x3, showSolvedIfEmpty = true)
        assertTrue(result is ScramblePreviewResult.Success)
        val model = (result as ScramblePreviewResult.Success).model
        assertEquals(54, model.elements.size)
    }

    @Test
    fun `empty scramble with showSolvedIfEmpty false returns Empty`() {
        val result = ScramblePreviewParser.parse("", Mode.CUBE_3x3, showSolvedIfEmpty = false)
        assertTrue(result is ScramblePreviewResult.Empty)
    }

    @Test
    fun `invalid scramble returns Error result without throwing exception`() {
        val result = ScramblePreviewParser.parse("INVALID SCRAMBLE MOVES", Mode.CUBE_3x3)
        assertTrue(result is ScramblePreviewResult.Error)
        val errorMsg = (result as ScramblePreviewResult.Error).message
        assertTrue(errorMsg.isNotEmpty())
    }

    @Test
    fun `caching returns identical model on repeated parse calls`() {
        val scramble = "R U R' U'"
        val result1 = ScramblePreviewParser.parse(scramble, Mode.CUBE_3x3)
        val result2 = ScramblePreviewParser.parse(scramble, Mode.CUBE_3x3)

        assertTrue(result1 is ScramblePreviewResult.Success)
        assertTrue(result2 is ScramblePreviewResult.Success)
        assertSame(
            (result1 as ScramblePreviewResult.Success).model,
            (result2 as ScramblePreviewResult.Success).model
        )
    }

    @Test
    fun `parseHexColor handles 6-digit 8-digit and named colors correctly`() {
        assertEquals(Color(0xFFFFFFFF), ScramblePreviewParser.parseHexColor("#FFFFFF"))
        assertEquals(Color(0xFFFF0000), ScramblePreviewParser.parseHexColor("#FF0000"))
        assertEquals(Color(0xFF00FF00), ScramblePreviewParser.parseHexColor("#00FF00"))
        assertEquals(Color(0xFF0000FF), ScramblePreviewParser.parseHexColor("#0000FF"))
        assertEquals(Color.White, ScramblePreviewParser.parseHexColor("white"))
        assertEquals(Color.Black, ScramblePreviewParser.parseHexColor("black"))
        assertEquals(Color.Transparent, ScramblePreviewParser.parseHexColor("transparent"))
        assertNull(ScramblePreviewParser.parseHexColor("none"))
        assertNull(ScramblePreviewParser.parseHexColor(null))
        assertNull(ScramblePreviewParser.parseHexColor("invalid-color"))
    }

    @Test
    fun `affine transform calculates transformed points correctly`() {
        val transform = Affine2D(e = 5f, f = 10f)
        assertEquals(15f, transform.transformX(10f, 20f))
        assertEquals(30f, transform.transformY(10f, 20f))

        val child = Affine2D(a = 2f, d = 3f)
        val combined = transform.concatenate(child)
        assertEquals(25f, combined.transformX(10f, 20f))
        assertEquals(70f, combined.transformY(10f, 20f))
    }
}
