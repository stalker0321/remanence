package dev.hryshyn.remanence.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PostcardPaper = Color(0xFFEFF1E2)
private val PostcardVermilion = Color(0xFFE95033)
private val PostcardNavy = Color(0xFF25377D)

/**
 * Decorative drawn postcard illustration, matching the final portrait
 * reference (`design/validation/validation.css`
 * `.validation-phone .postcard-portrait{aspect-ratio:3/4}` with the
 * `.postcard` base from `design/motion/shell.css`, rendered via
 * `design/validation/render.mjs:5` `postcard(shape='portrait')`):
 * paper `#eff1e2`, portrait 3:4, upright in waiting
 * (`.wait-presentation .postcard-portrait{width:auto;height:83%;max-height:275px}`,
 * `transform:none`), vermilion disc (45% wide at left 14% / top 11%),
 * navy band (30% tall, 12% from the bottom, 120% wide from -10%,
 * tilted -13deg) with the paper gap and navy echo stripe below it,
 * and the vertical `ICI / HERE` mark.
 *
 * The waiting composition itself is height-led and centered by the caller
 * (`AstraScanWaiting`: `heightIn(180.dp, 275.dp)`, centered); the Canvas
 * mirrors that slot (`fillMaxHeight` + `heightIn(180.dp, 275.dp)`) so the
 * card stays height-led even if reused outside waiting.
 * The fractional artwork (disc/band/gap/echo) is relative to the card
 * box, so it transfers unchanged from the CSS percentages to the tall
 * box — no landscape retune.
 *
 * This is set dressing for scan waiting states — the postcard "carries
 * the wait" (`design/motion/model.mjs:14`) — and it must NEVER render
 * capsule or private content. Real capsule photos render only in
 * `CapsuleScreen` after the crypto grant gate passes. The merged
 * semantics expose a single graphic role, matching the study
 * `role="img" aria-label="graphic postcard front"`
 * (`design/motion/render.mjs:4`).
 */
@Composable
fun AstraPostcard(modifier: Modifier = Modifier) {
    // Waiting portrait is upright (`transform:none`): the old -7deg
    // landscape tilt is deliberately not ported. The Canvas is the
    // sizing child (height-led portrait 3:4 within the 180..275dp
    // waiting slot) so it always measures non-zero instead of
    // collapsing like a matchParentSize child would inside a
    // wrap-content Box. fillMaxHeight prefers the reference max
    // (275dp) inside the caller's height cap; heightIn keeps the
    // 180dp slot minimum and the 275dp max; matchHeightConstraintsFirst
    // derives the width from the height to preserve 3:4 instead of
    // stretching. No fillMaxWidth: forcing full width would derive a
    // 453dp-tall card on a 340dp-wide group and break the portrait
    // aspect under the height cap.
    Box(
        modifier = modifier
            .testTag("astra_postcard")
            .semantics(mergeDescendants = true) {
                contentDescription = "graphic postcard front"
                role = Role.Image
            }
            .shadow(12.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .heightIn(min = 180.dp, max = 275.dp)
                .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                .testTag("astra_postcard_art"),
        ) {
            val width = size.width
            val height = size.height
            // Paper.
            drawRect(color = PostcardPaper, size = size)
            // Vermilion disc: 45% of the width, offset left 14% / top 11%.
            val diameter = width * 0.45f
            drawCircle(
                color = PostcardVermilion,
                radius = diameter / 2f,
                center = Offset(width * 0.14f + diameter / 2f, height * 0.11f + diameter / 2f),
            )
            // Final base `overflow:hidden`: the band is drawn 120% wide from -10%
            // but must clip to the card itself. clipRect keeps the shadow
            // and outer layout untouched.
            clipRect(left = 0f, top = 0f, right = width, bottom = height) {
            // Navy band: 30% tall with its top at 58% (12% bottom margin),
            // 120% wide from -10%, tilted -13deg around the band center.
            // Below it the paper gap and the thin navy echo stripe, in the
            // base's 12px-gap / 6px-line proportion of the band rhythm.
            rotate(degrees = -13f, pivot = Offset(width / 2f, height * 0.73f)) {
                val bandTop = height * 0.58f
                val bandHeight = height * 0.30f
                drawRect(
                    color = PostcardNavy,
                    topLeft = Offset(-width * 0.10f, bandTop),
                    size = Size(width * 1.20f, bandHeight),
                )
                drawRect(
                    color = PostcardPaper,
                    topLeft = Offset(-width * 0.10f, bandTop + bandHeight),
                    size = Size(width * 1.20f, height * 0.03f),
                )
                drawRect(
                    color = PostcardNavy,
                    topLeft = Offset(-width * 0.10f, bandTop + bandHeight + height * 0.03f),
                    size = Size(width * 1.20f, height * 0.02f),
                )
            }
            }
        }
        // Vertical mark, reading top-to-bottom like `writing-mode: vertical-rl`.
        Text(
            text = "ICI / HERE",
            color = PostcardNavy,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 10.dp)
                .rotate(90f)
                .clearAndSetSemantics {},
        )
    }
}
