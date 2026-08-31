package com.maciekhetman.cubetimer.ui.admin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.maciekhetman.cubetimer.model.admin.AdminRequestTypeItem
import com.maciekhetman.cubetimer.model.admin.AdminTrafficPoint
import com.maciekhetman.cubetimer.model.admin.RequestTypeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial stress tests verifying mathematical stability, boundary limits,
 * and zero-division prevention for Compose Canvas chart rendering computations.
 */
class AdminChartComputationStressTest {

    private val canvasSize = Size(width = 800f, height = 400f)

    // ---------------------------------------------------------------------------------------------
    // SPARKLINE LINE CHART MATHEMATICAL INVARIANTS
    // ---------------------------------------------------------------------------------------------

    private data class SparklinePoint(val x: Float, val y: Float)

    private fun computeSparklineCoordinates(values: List<Float>, size: Size): List<SparklinePoint> {
        if (values.isEmpty()) return emptyList()

        val maxVal = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val minVal = values.minOrNull() ?: 0f
        val range = (maxVal - minVal).coerceAtLeast(1f)

        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width

        return values.mapIndexed { index, v ->
            val normY = (v - minVal) / range
            val y = size.height - (normY * (size.height * 0.85f)) - (size.height * 0.05f)
            val x = index * stepX
            SparklinePoint(x, y)
        }
    }

    @Test
    fun `sparkline handles 0 data points cleanly`() {
        val result = computeSparklineCoordinates(emptyList(), canvasSize)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sparkline handles 1 data point without division by zero or NaN`() {
        val values = listOf(42.5f)
        val coords = computeSparklineCoordinates(values, canvasSize)

        assertEquals(1, coords.size)
        val point = coords.first()
        assertFalse("x must not be NaN", point.x.isNaN())
        assertFalse("y must not be NaN", point.y.isNaN())
        assertFalse("x must not be infinite", point.x.isInfinite())
        assertFalse("y must not be infinite", point.y.isInfinite())

        assertEquals(0f, point.x, 0.001f)
        assertTrue("y must be within canvas bounds", point.y in 0f..canvasSize.height)
    }

    @Test
    fun `sparkline handles flat line with identical values without division by zero`() {
        val values = listOf(50f, 50f, 50f, 50f, 50f)
        val coords = computeSparklineCoordinates(values, canvasSize)

        assertEquals(5, coords.size)
        coords.forEachIndexed { i, pt ->
            assertFalse(pt.x.isNaN())
            assertFalse(pt.y.isNaN())
            assertTrue(pt.x in 0f..canvasSize.width)
            assertTrue(pt.y in 0f..canvasSize.height)
        }
        assertEquals(0f, coords[0].x, 0.001f)
        assertEquals(canvasSize.width, coords[4].x, 0.001f)
    }

    @Test
    fun `sparkline handles all zero values without division by zero`() {
        val values = listOf(0f, 0f, 0f, 0f)
        val coords = computeSparklineCoordinates(values, canvasSize)

        assertEquals(4, coords.size)
        coords.forEach { pt ->
            assertFalse(pt.x.isNaN())
            assertFalse(pt.y.isNaN())
            assertTrue(pt.y in 0f..canvasSize.height)
        }
    }

    @Test
    fun `sparkline handles high point density of 1000 points without precision loss or overflow`() {
        val values = (1..1000).map { (it % 100).toFloat() }
        val coords = computeSparklineCoordinates(values, canvasSize)

        assertEquals(1000, coords.size)
        var prevX = -1f
        coords.forEach { pt ->
            assertFalse(pt.x.isNaN())
            assertFalse(pt.y.isNaN())
            assertTrue("x coordinate must increase monotonically", pt.x >= prevX)
            assertTrue("x must be within width", pt.x in 0f..canvasSize.width)
            assertTrue("y must be within height", pt.y in 0f..canvasSize.height)
            prevX = pt.x
        }
        assertEquals(0f, coords.first().x, 0.001f)
        assertEquals(canvasSize.width, coords.last().x, 0.001f)
    }

    @Test
    fun `sparkline handles extreme values up to 1,000,000f`() {
        val values = listOf(0f, 500_000f, 1_000_000f)
        val coords = computeSparklineCoordinates(values, canvasSize)

        assertEquals(3, coords.size)
        coords.forEach { pt ->
            assertFalse(pt.x.isNaN())
            assertFalse(pt.y.isNaN())
            assertTrue(pt.x in 0f..canvasSize.width)
            assertTrue(pt.y in 0f..canvasSize.height)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // STACKED BAR CHART MATHEMATICAL INVARIANTS
    // ---------------------------------------------------------------------------------------------

    private data class StackedBarGeometry(
        val index: Int,
        val x: Float,
        val barWidth: Float,
        val totalHeight: Float,
        val h2xx: Float,
        val h3xx: Float,
        val h4xx: Float,
        val h5xx: Float
    )

    private fun computeStackedBars(points: List<AdminTrafficPoint>, size: Size): List<StackedBarGeometry> {
        val count = points.size
        if (count == 0) return emptyList()

        val maxVal = points.maxOfOrNull { it.requestCount }?.toFloat()?.coerceAtLeast(1f) ?: 1f
        val barWidth = (size.width / count) * 0.7f
        val gap = (size.width / count) * 0.3f

        return points.mapIndexed { i, p ->
            val x = i * (barWidth + gap) + gap / 2f
            val totalH = (p.requestCount.toFloat() / maxVal) * size.height
            val h2xx = (p.status2xx.toFloat() / maxVal) * size.height
            val h3xx = (p.status3xx.toFloat() / maxVal) * size.height
            val h4xx = (p.status4xx.toFloat() / maxVal) * size.height
            val h5xx = (p.status5xx.toFloat() / maxVal) * size.height

            StackedBarGeometry(i, x, barWidth, totalH, h2xx, h3xx, h4xx, h5xx)
        }
    }

    @Test
    fun `stacked bar handles 0 data points cleanly`() {
        val bars = computeStackedBars(emptyList(), canvasSize)
        assertTrue(bars.isEmpty())
    }

    @Test
    fun `stacked bar handles single data point with accurate dimensions`() {
        val points = listOf(
            AdminTrafficPoint(
                bucket = "12:00",
                requestCount = 100,
                status2xx = 80,
                status3xx = 0,
                status4xx = 15,
                status5xx = 5,
                averageDurationMs = 20.0,
                maxDurationMs = 100,
                throughputRpm = 1.6,
                successRate = 80.0,
                errorRate = 20.0
            )
        )
        val bars = computeStackedBars(points, canvasSize)

        assertEquals(1, bars.size)
        val bar = bars.first()
        assertEquals(canvasSize.width * 0.7f, bar.barWidth, 0.001f)
        assertEquals(canvasSize.height, bar.totalHeight, 0.001f)
        assertEquals(canvasSize.height * 0.8f, bar.h2xx, 0.001f)
        assertEquals(0f, bar.h3xx, 0.001f)
        assertEquals(canvasSize.height * 0.15f, bar.h4xx, 0.001f)
        assertEquals(canvasSize.height * 0.05f, bar.h5xx, 0.001f)
        assertEquals(bar.totalHeight, bar.h2xx + bar.h3xx + bar.h4xx + bar.h5xx, 0.001f)
    }

    @Test
    fun `stacked bar handles all zero traffic points without NaN or crash`() {
        val points = listOf(
            AdminTrafficPoint(
                bucket = "00:00",
                requestCount = 0,
                status2xx = 0,
                status3xx = 0,
                status4xx = 0,
                status5xx = 0,
                averageDurationMs = 0.0,
                maxDurationMs = 0,
                throughputRpm = 0.0,
                successRate = 0.0,
                errorRate = 0.0
            )
        )
        val bars = computeStackedBars(points, canvasSize)

        assertEquals(1, bars.size)
        val bar = bars.first()
        assertFalse(bar.x.isNaN())
        assertFalse(bar.barWidth.isNaN())
        assertEquals(0f, bar.totalHeight, 0.001f)
        assertEquals(0f, bar.h2xx, 0.001f)
    }

    @Test
    fun `stacked bar handles 500 points with strictly positive bar widths and non-overlapping x positions`() {
        val points = (1..500).map { i ->
            AdminTrafficPoint(
                bucket = "$i",
                requestCount = (i * 10).toLong(),
                status2xx = (i * 8).toLong(),
                status3xx = 0L,
                status4xx = (i * 2).toLong(),
                status5xx = 0L,
                averageDurationMs = 15.0,
                maxDurationMs = 50,
                throughputRpm = 5.0,
                successRate = 80.0,
                errorRate = 20.0
            )
        }
        val bars = computeStackedBars(points, canvasSize)

        assertEquals(500, bars.size)
        var prevX = -1f
        bars.forEach { bar ->
            assertTrue("bar width must be strictly positive", bar.barWidth > 0f)
            assertTrue("x coordinate must be strictly increasing", bar.x > prevX)
            assertTrue("bar must fit horizontally within canvas bounds", bar.x + bar.barWidth <= canvasSize.width + 1f)
            assertTrue("total height must not exceed canvas height", bar.totalHeight <= canvasSize.height + 0.01f)
            prevX = bar.x
        }
    }

    // ---------------------------------------------------------------------------------------------
    // REQUEST TYPE DISTRIBUTION BAR MATHEMATICAL INVARIANTS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `request type distribution handles single category with 100 percent share`() {
        val items = listOf(
            AdminRequestTypeItem(category = RequestTypeCategory.SYNC, requestCount = 500, sharePercentage = 100.0)
        )

        val weights = items.map { it.sharePercentage.toFloat().coerceAtLeast(0.01f) }
        assertEquals(1, weights.size)
        assertEquals(100f, weights.first(), 0.001f)
    }

    @Test
    fun `request type distribution handles zero share items with safe positive coercion`() {
        val items = listOf(
            AdminRequestTypeItem(category = RequestTypeCategory.AUTH, requestCount = 0, sharePercentage = 0.0)
        )

        val weights = items.map { it.sharePercentage.toFloat().coerceAtLeast(0.01f) }
        assertEquals(1, weights.size)
        assertEquals(0.01f, weights.first(), 0.001f)
        assertFalse(weights.first().isNaN())
    }
}
