package dev.hryshyn.remanence.ui.capsule

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.hryshyn.remanence.ui.collage.CollageTextMeasurer
import dev.hryshyn.remanence.ui.collage.toMeasureRequest
import kotlin.math.roundToInt

/**
 * The letter is the back of the card (ADR-021 §2). The front keeps the start
 * of the text and a small turn mark at the end of the note; tapping the note turns the card over
 * to the whole text on the same paper, in the note's own face. Nothing here is
 * sealed: a receiver draws the mark and the back whenever the manifest carries
 * a letter, so older receivers simply see the front. The site's `CardBack.tsx`
 * is the same card.
 */

/** Turns the sheet over in place: the front until halfway, then the back. */
@Composable
internal fun CardSides(
    turned: Boolean,
    modifier: Modifier = Modifier,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val angle by animateFloatAsState(if (turned) 180f else 0f, tween(600), label = "card-turn")
    val density = LocalDensity.current.density
    Box(modifier.graphicsLayer { rotationY = angle; cameraDistance = 16f * density }) {
        // The front stays composed (hidden past halfway) so "save picture" still captures it.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (angle <= 90f) 1f else 0f }) { front() }
        if (angle > 90f) {
            // Taps on the back never reach the hidden front's photos.
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.pointerInput(Unit) { detectTapGestures { } }) { back() }
        }
    }
}

/** The mark's square in canvas px; [backed] when it sits over the end of a full line. */
internal data class LetterMarkPlace(val x: Float, val y: Float, val size: Float, val backed: Boolean)

/**
 * The layouts leave 7 to 17 px between their elements, so the mark has no
 * room of its own: it lives inside the note region, right after the last
 * word ([lastLineWidth] and [lastBaseline] are that row's, the baseline from
 * the region's top; null when the note has no rows). When the last line is
 * full it takes the end of that line on a patch of paper. The site's
 * `letterMarkPlace` is the same rule.
 */
internal fun letterMarkPlace(
    region: CollageRenderer.RectI,
    noteSize: Int,
    lastLineWidth: Float?,
    lastBaseline: Float?,
): LetterMarkPlace {
    val size = (noteSize * 1.1f).roundToInt().coerceIn(12, 18).toFloat()
    val gap = maxOf(4, (noteSize * 0.3f).roundToInt())
    val right = (region.x + region.width).toFloat()
    val lineEnd = if (lastLineWidth == null) right else region.x + lastLineWidth
    val baseline = region.y + (lastBaseline ?: noteSize.toFloat())
    val backed = lineEnd + gap + size > right
    val x = if (backed) right - size else lineEnd + gap
    val centre = baseline - noteSize * 0.3f
    val y = (centre - size / 2).coerceIn(region.y.toFloat(), maxOf(region.y.toFloat(), region.y + region.height - size))
    return LetterMarkPlace(x, y, size, backed)
}

/** The whole note turns the card; the small arrow at its end says so. */
@Composable
internal fun LetterMark(scene: CollageRenderer.Scene, measurer: CollageTextMeasurer, onTurn: () -> Unit) {
    val note = scene.note ?: return
    val label = stringResource(R.string.capsule_letter_mark)
    val place = remember(note, measurer) {
        val layout = measurer.layout(note.toMeasureRequest())
        val last = (layout.lineCount - 1 downTo 0).firstOrNull { layout.getLineEnd(it, visibleEnd = true) > layout.getLineStart(it) }
        letterMarkPlace(note.region, note.size, last?.let { layout.getLineRight(it) }, last?.let { layout.getLineBaseline(it) })
    }
    val paper = Color(scene.paper)
    val ink = Color(note.color)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = minOf(maxWidth.value / scene.canvasWidth, maxHeight.value / scene.canvasHeight)
        val r = note.region
        Box(
            Modifier
                .absoluteOffset((r.x * scale).dp, (r.y * scale).dp)
                .size((r.width * scale).dp, (r.height * scale).dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = label,
                    onClick = onTurn,
                )
                .semantics { contentDescription = label }
                .testTag("capsule_letter_mark"),
        ) {
            Canvas(
                Modifier
                    .absoluteOffset(((place.x - r.x) * scale).dp, ((place.y - r.y) * scale).dp)
                    .size((place.size * scale).dp),
            ) {
                // A 24-unit box: an arc over the top, left to right, and its arrowhead.
                val u = size.width / 24f
                if (place.backed) drawRect(paper, Offset(-8f * u, 0f), Size(size.width + 8f * u, size.height))
                val arrow = Path().apply {
                    arcTo(Rect(2.5f * u, 9f * u, 20.5f * u, 27f * u), 180f, 180f, true)
                    moveTo(15.5f * u, 14f * u)
                    lineTo(20.5f * u, 18f * u)
                    lineTo(23.5f * u, 12.5f * u)
                }
                drawPath(arrow, ink, style = Stroke(2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
internal fun CardBack(scene: CollageRenderer.Scene, letter: String, onTurn: () -> Unit) {
    val note = scene.note
    val ink = Color(note?.color ?: scene.ink)
    val face = note?.let {
        FontFamily(Font(it.fontResourceId, FontWeight(it.weight), if (it.italic) FontStyle.Italic else FontStyle.Normal))
    } ?: FontFamily(Font(R.font.nimbus_roman_regular))
    val frontLabel = stringResource(R.string.capsule_letter_front)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(scene.paper)).testTag("capsule_letter_back")) {
        val unit = maxWidth.value / 100f
        Column(Modifier.fillMaxSize().padding(start = (7 * unit).dp, end = (7 * unit).dp, top = (8 * unit).dp, bottom = (4 * unit).dp)) {
            Text(
                letter,
                style = TextStyle(fontFamily = face, fontSize = (5 * unit).sp, lineHeight = (5 * unit * 1.45f).sp),
                color = ink,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("capsule_letter_text"),
            )
            Text(
                "↺ $frontLabel",
                style = TextStyle(
                    fontFamily = FontFamily(Font(R.font.nimbus_roman_italic, FontWeight.Normal, FontStyle.Italic)),
                    fontSize = (3.6f * unit).sp,
                ),
                color = ink,
                modifier = Modifier.align(Alignment.End).padding(top = (3 * unit).dp)
                    .clickable(role = Role.Button, onClick = onTurn).testTag("capsule_letter_front"),
            )
        }
    }
}
