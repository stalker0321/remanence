package dev.hryshyn.remanence.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Decorative drawn postcard illustration, adapted from the design study
 * geometric stand-in (`design/styles.css:8` `.postcard`):
 * paper `#eff1e2`, aspect 1.43, tilt -7deg, `0 12px 16px #0002` card
 * shadow, vermilion disc (45% wide at left 14% / top 11%), navy band
 * (30% tall, 12% from the bottom, 120% wide from -10%, tilted -13deg)
 * with the paper gap and navy echo stripe below it
 * (`box-shadow: 0 12px 0 #eff1e2, 0 18px 0 #25377d`), and the vertical
 * `ICI / HERE` mark.
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
    // The tilt applies to the whole card (artwork and mark), as in the
    // study where the label sits inside the rotated `.postcard`. The
    // Canvas is the sizing child (full width, aspect 1.43) so it always
    // measures non-zero instead of collapsing like a matchParentSize
    // child would inside a wrap-content Box.
    Box(
        modifier = modifier
            .testTag("astra_postcard")
            .semantics(mergeDescendants = true) {
                contentDescription = "graphic postcard front"
                role = Role.Image
            }
            .shadow(12.dp)
            .rotate(-7f),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.43f)
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
            // Study `overflow:hidden`: the band is drawn 120% wide from -10%
            // but must clip to the card itself. clipRect keeps the shadow,
            // rotation and outer layout untouched.
            clipRect(left = 0f, top = 0f, right = width, bottom = height) {
            // Navy band: 30% tall with its top at 58% (12% bottom margin),
            // 120% wide from -10%, tilted -13deg around the band center.
            // Below it the paper gap and the thin navy echo stripe, in the
            // study's 12px-gap / 6px-line proportion of the band rhythm.
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
