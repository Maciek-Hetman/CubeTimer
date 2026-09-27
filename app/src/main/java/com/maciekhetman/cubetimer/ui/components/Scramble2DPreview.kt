package com.maciekhetman.cubetimer.ui.components

import android.graphics.Paint
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExtensionOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.domain.AndroidSha1PrngProvider
import com.maciekhetman.cubetimer.model.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.worldcubeassociation.tnoodle.scrambles.PuzzleRegistry
import org.worldcubeassociation.tnoodle.svglite.Element
import org.worldcubeassociation.tnoodle.svglite.Group
import org.worldcubeassociation.tnoodle.svglite.PathIterator
import org.worldcubeassociation.tnoodle.svglite.Rectangle
import org.worldcubeassociation.tnoodle.svglite.Svg
import org.worldcubeassociation.tnoodle.svglite.Text as SvgText
import kotlin.math.abs

/**
 * Immutable vector drawing primitive extracted from TNoodle Svg.
 */
sealed interface PuzzleDrawingElement {
    data class RectElement(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val fillColor: Color?,
        val strokeColor: Color?,
        val strokeWidth: Float = 1f
    ) : PuzzleDrawingElement

    data class PathElement(
        val path: Path,
        val fillColor: Color?,
        val strokeColor: Color?,
        val strokeWidth: Float = 1f
    ) : PuzzleDrawingElement

    data class TextElement(
        val text: String,
        val x: Float,
        val y: Float,
        val color: Color = Color.Black,
        val fontSize: Float = 12f
    ) : PuzzleDrawingElement
}

/**
 * Complete puzzle 2D net model ready for Compose Canvas rendering.
 */
data class PuzzleImageModel(
    val width: Float,
    val height: Float,
    val elements: List<PuzzleDrawingElement>
)

/**
 * 2D Affine Transformation matrix:
 * x' = a * x + c * y + e
 * y' = b * x + d * y + f
 */
data class Affine2D(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f
) {
    fun concatenate(other: Affine2D): Affine2D {
        val newA = a * other.a + c * other.b
        val newB = b * other.a + d * other.b
        val newC = a * other.c + c * other.d
        val newD = b * other.c + d * other.d
        val newE = a * other.e + c * other.f + e
        val newF = b * other.e + d * other.f + f
        return Affine2D(newA, newB, newC, newD, newE, newF)
    }

    fun transformX(x: Float, y: Float): Float = a * x + c * y + e
    fun transformY(x: Float, y: Float): Float = b * x + d * y + f
}

sealed interface ScramblePreviewResult {
    data class Success(val model: PuzzleImageModel) : ScramblePreviewResult
    data object Empty : ScramblePreviewResult
    data class Error(val message: String) : ScramblePreviewResult
}

/**
 * High-performance parser translating TNoodle in-memory SVG AST directly into Compose vector primitives.
 */
object ScramblePreviewParser {
    private val modelCache = LruCache<String, PuzzleImageModel>(64)

    private val scramblersByMode = mapOf(
        Mode.CUBE_2x2 to PuzzleRegistry.TWO,
        Mode.CUBE_3x3 to PuzzleRegistry.THREE,
        Mode.CUBE_4x4 to PuzzleRegistry.FOUR,
        Mode.CUBE_5x5 to PuzzleRegistry.FIVE,
        Mode.MEGAMINX to PuzzleRegistry.MEGA,
        Mode.PYRAMINX to PuzzleRegistry.PYRA
    )

    fun clearCache() {
        modelCache.evictAll()
    }

    fun parse(scramble: String, mode: Mode, showSolvedIfEmpty: Boolean = true): ScramblePreviewResult {
        val trimmedScramble = scramble.trim()
        if (trimmedScramble.isEmpty() && !showSolvedIfEmpty) {
            return ScramblePreviewResult.Empty
        }

        val cacheKey = "${mode.name}:$trimmedScramble"
        modelCache.get(cacheKey)?.let { return ScramblePreviewResult.Success(it) }

        return try {
            AndroidSha1PrngProvider.install()
            val registry = scramblersByMode[mode]
                ?: return ScramblePreviewResult.Error("Unsupported mode: $mode")
            val puzzle = registry.getScrambler()

            // TNoodle drawScramble validates the scramble, applies moves to solved state,
            // and returns an in-memory SVG AST.
            val svg = puzzle.drawScramble(trimmedScramble, null)
            val model = extractModelFromSvg(svg)

            modelCache.put(cacheKey, model)
            ScramblePreviewResult.Success(model)
        } catch (e: Exception) {
            ScramblePreviewResult.Error(e.message ?: "Failed to generate scramble preview")
        }
    }

    private fun extractModelFromSvg(svg: Svg): PuzzleImageModel {
        val width = svg.size?.width?.toFloat()?.takeIf { it > 0f } ?: 100f
        val height = svg.size?.height?.toFloat()?.takeIf { it > 0f } ?: 100f
        val elements = mutableListOf<PuzzleDrawingElement>()

        for (child in svg.children) {
            traverseElement(child, Affine2D(), elements)
        }

        return PuzzleImageModel(width = width, height = height, elements = elements)
    }

    private fun traverseElement(
        element: Element,
        currentTransform: Affine2D,
        outElements: MutableList<PuzzleDrawingElement>
    ) {
        var elementTransform = currentTransform
        val rawTransform = element.transform
        if (rawTransform != null) {
            val parsed = parseSvgTransform(rawTransform.toSvgTransform())
            if (parsed != null) {
                elementTransform = currentTransform.concatenate(parsed)
            }
        }

        when (element) {
            is Group -> {
                for (child in element.children) {
                    traverseElement(child, elementTransform, outElements)
                }
            }
            is Rectangle -> {
                val rawX = element.getAttribute("x")?.toFloatOrNull() ?: 0f
                val rawY = element.getAttribute("y")?.toFloatOrNull() ?: 0f
                val rawW = element.getAttribute("width")?.toFloatOrNull() ?: 0f
                val rawH = element.getAttribute("height")?.toFloatOrNull() ?: 0f
                val strokeWidth = element.getAttribute("stroke-width")?.toFloatOrNull() ?: 1f
                val fillColor = parseHexColor(element.getAttribute("fill"))
                val strokeColor = parseHexColor(element.getAttribute("stroke"))

                if (elementTransform.b == 0f && elementTransform.c == 0f) {
                    // Axis-aligned rectangle
                    val tx = elementTransform.transformX(rawX, rawY)
                    val ty = elementTransform.transformY(rawX, rawY)
                    val tw = rawW * elementTransform.a
                    val th = rawH * elementTransform.d
                    outElements.add(
                        PuzzleDrawingElement.RectElement(
                            x = minOf(tx, tx + tw),
                            y = minOf(ty, ty + th),
                            width = abs(tw),
                            height = abs(th),
                            fillColor = fillColor,
                            strokeColor = strokeColor,
                            strokeWidth = strokeWidth
                        )
                    )
                } else {
                    // Rotated rectangle -> convert to Path
                    val p = Path()
                    val p0x = elementTransform.transformX(rawX, rawY)
                    val p0y = elementTransform.transformY(rawX, rawY)
                    val p1x = elementTransform.transformX(rawX + rawW, rawY)
                    val p1y = elementTransform.transformY(rawX + rawW, rawY)
                    val p2x = elementTransform.transformX(rawX + rawW, rawY + rawH)
                    val p2y = elementTransform.transformY(rawX + rawW, rawY + rawH)
                    val p3x = elementTransform.transformX(rawX, rawY + rawH)
                    val p3y = elementTransform.transformY(rawX, rawY + rawH)
                    p.moveTo(p0x, p0y)
                    p.lineTo(p1x, p1y)
                    p.lineTo(p2x, p2y)
                    p.lineTo(p3x, p3y)
                    p.close()
                    outElements.add(
                        PuzzleDrawingElement.PathElement(
                            path = p,
                            fillColor = fillColor,
                            strokeColor = strokeColor,
                            strokeWidth = strokeWidth
                        )
                    )
                }
            }
            is org.worldcubeassociation.tnoodle.svglite.Path -> {
                val composePath = parseSvgPath(element.d, elementTransform)
                val strokeWidth = element.getAttribute("stroke-width")?.toFloatOrNull() ?: 1f
                val fillColor = parseHexColor(element.getAttribute("fill"))
                val strokeColor = parseHexColor(element.getAttribute("stroke"))

                outElements.add(
                    PuzzleDrawingElement.PathElement(
                        path = composePath,
                        fillColor = fillColor,
                        strokeColor = strokeColor,
                        strokeWidth = strokeWidth
                    )
                )
            }
            is SvgText -> {
                val text = element.content ?: ""
                if (text.isNotEmpty()) {
                    val rawX = element.getAttribute("x")?.toFloatOrNull() ?: 0f
                    val rawY = element.getAttribute("y")?.toFloatOrNull() ?: 0f
                    val tx = elementTransform.transformX(rawX, rawY)
                    val ty = elementTransform.transformY(rawX, rawY)
                    val fontSize = element.getAttribute("font-size")?.toFloatOrNull() ?: 10f
                    val color = parseHexColor(element.getAttribute("fill")) ?: Color.Black
                    outElements.add(
                        PuzzleDrawingElement.TextElement(
                            text = text,
                            x = tx,
                            y = ty,
                            color = color,
                            fontSize = fontSize
                        )
                    )
                }
            }
            else -> {
                for (child in element.children) {
                    traverseElement(child, elementTransform, outElements)
                }
            }
        }
    }

    private fun parseSvgTransform(str: String?): Affine2D? {
        if (str.isNullOrBlank()) return null
        val trimmed = str.trim()
        if (trimmed.startsWith("matrix(") && trimmed.endsWith(")")) {
            val parts = trimmed.removeSurrounding("matrix(", ")").split(",")
            if (parts.size >= 6) {
                val a = parts[0].trim().toFloatOrNull() ?: 1f
                val b = parts[1].trim().toFloatOrNull() ?: 0f
                val c = parts[2].trim().toFloatOrNull() ?: 0f
                val d = parts[3].trim().toFloatOrNull() ?: 1f
                val e = parts[4].trim().toFloatOrNull() ?: 0f
                val f = parts[5].trim().toFloatOrNull() ?: 0f
                return Affine2D(a, b, c, d, e, f)
            }
        }
        return null
    }

    private val PATH_TOKEN_REGEX = """([a-zA-Z])|([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)""".toRegex()

    private fun parseSvgPath(d: String?, transform: Affine2D): Path {
        val path = Path()
        if (d.isNullOrBlank()) return path

        val matches = PATH_TOKEN_REGEX.findAll(d).toList()
        var i = 0
        var currentCommand = ""

        while (i < matches.size) {
            val match = matches[i]
            val cmd = match.groups[1]?.value
            if (cmd != null) {
                currentCommand = cmd.uppercase()
                i++
                if (currentCommand == "Z") {
                    path.close()
                }
            } else {
                val numStr = match.groups[2]?.value
                when (currentCommand) {
                    "M" -> {
                        val x = numStr?.toFloatOrNull() ?: 0f
                        val y = matches.getOrNull(i + 1)?.groups?.get(2)?.value?.toFloatOrNull() ?: 0f
                        path.moveTo(transform.transformX(x, y), transform.transformY(x, y))
                        i += 2
                        currentCommand = "L"
                    }
                    "L" -> {
                        val x = numStr?.toFloatOrNull() ?: 0f
                        val y = matches.getOrNull(i + 1)?.groups?.get(2)?.value?.toFloatOrNull() ?: 0f
                        path.lineTo(transform.transformX(x, y), transform.transformY(x, y))
                        i += 2
                    }
                    else -> {
                        i++
                    }
                }
            }
        }
        return path
    }

    fun parseHexColor(colorStr: String?): Color? {
        if (colorStr.isNullOrBlank() || colorStr.equals("none", ignoreCase = true)) return null
        val clean = colorStr.trim().removePrefix("#")
        return try {
            when {
                colorStr.equals("white", ignoreCase = true) -> Color.White
                colorStr.equals("black", ignoreCase = true) -> Color.Black
                colorStr.equals("transparent", ignoreCase = true) -> Color.Transparent
                clean.length == 6 -> {
                    val rgb = clean.toLong(16)
                    Color((0xFF000000 or rgb).toInt())
                }
                clean.length == 8 -> {
                    val argb = clean.toLong(16)
                    Color(argb.toInt())
                }
                clean.length == 3 -> {
                    val r = clean.substring(0, 1).repeat(2).toInt(16)
                    val g = clean.substring(1, 2).repeat(2).toInt(16)
                    val b = clean.substring(2, 3).repeat(2).toInt(16)
                    Color(r, g, b)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Jetpack Compose 2D Scramble Preview component.
 * Renders official WCA unfolded puzzle state diagrams for 3x3, 2x2, 4x4, 5x5, Pyraminx, and Megaminx.
 */
@Composable
fun Scramble2DPreview(
    scramble: String,
    mode: Mode,
    modifier: Modifier = Modifier,
    showSolvedIfEmpty: Boolean = true
) {
    val resultState by produceState<ScramblePreviewResult?>(
        initialValue = null,
        key1 = scramble,
        key2 = mode,
        key3 = showSolvedIfEmpty
    ) {
        value = withContext(Dispatchers.Default) {
            ScramblePreviewParser.parse(scramble, mode, showSolvedIfEmpty)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp
    ) {
        when (val result = resultState) {
            null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            is ScramblePreviewResult.Success -> {
                val model = result.model

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = "2D scramble preview for ${mode.displayName}"
                            }
                    ) {
                        if (model.width <= 0f || model.height <= 0f) return@Canvas
                        val scaleFactor = minOf(size.width / model.width, size.height / model.height)
                        val scaledW = model.width * scaleFactor
                        val scaledH = model.height * scaleFactor
                        val offsetX = (size.width - scaledW) / 2f
                        val offsetY = (size.height - scaledH) / 2f

                        val textPaint = Paint().apply {
                            isAntiAlias = true
                            textAlign = Paint.Align.CENTER
                            textSize = 10f * scaleFactor
                            color = android.graphics.Color.BLACK
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }

                        withTransform({
                            translate(left = offsetX, top = offsetY)
                            scale(scaleX = scaleFactor, scaleY = scaleFactor, pivot = Offset.Zero)
                        }) {
                            for (element in model.elements) {
                                when (element) {
                                    is PuzzleDrawingElement.RectElement -> {
                                        if (element.fillColor != null) {
                                            drawRect(
                                                color = element.fillColor,
                                                topLeft = Offset(element.x, element.y),
                                                size = Size(element.width, element.height),
                                                style = Fill
                                            )
                                        }
                                        if (element.strokeColor != null) {
                                            drawRect(
                                                color = element.strokeColor,
                                                topLeft = Offset(element.x, element.y),
                                                size = Size(element.width, element.height),
                                                style = Stroke(width = element.strokeWidth)
                                            )
                                        }
                                    }
                                    is PuzzleDrawingElement.PathElement -> {
                                        if (element.fillColor != null) {
                                            drawPath(
                                                path = element.path,
                                                color = element.fillColor,
                                                style = Fill
                                            )
                                        }
                                        if (element.strokeColor != null) {
                                            drawPath(
                                                path = element.path,
                                                color = element.strokeColor,
                                                style = Stroke(width = element.strokeWidth)
                                            )
                                        }
                                    }
                                    is PuzzleDrawingElement.TextElement -> {
                                        drawIntoCanvas { canvas ->
                                            canvas.nativeCanvas.drawText(
                                                element.text,
                                                element.x,
                                                element.y,
                                                textPaint
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            is ScramblePreviewResult.Empty -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No scramble",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is ScramblePreviewResult.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ExtensionOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Preview unavailable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
