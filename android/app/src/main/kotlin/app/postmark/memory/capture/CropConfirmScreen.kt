package app.postmark.memory.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import postmark.core.recognition.ManualCropQuad
import postmark.core.recognition.PointD

/**
 * Pure transition rules for the crop-confirm step (docs/recognition.md
 * section 3 steps 4-5): the automatic proposal is shown briefly, the user may
 * adjust any of the four corners manually, confirmation is impossible while
 * the quad is invalid, and recapture stays reachable until confirmed.
 */
class CropConfirmationShell(initialProposal: ManualCropQuad) {

    sealed interface Step {
        /** Automatic proposal shown for confirmation. */
        data class Proposing(val quad: ManualCropQuad) : Step

        /** Manual corner adjustment in progress with live validation. */
        data class Adjusting(val quad: ManualCropQuad, val valid: Boolean, val invalidReason: String?) : Step

        /** Canonical ordered corners accepted for warping; terminal. */
        data class Confirmed(val orderedCorners: List<PointD>) : Step

        /** User asked for a fresh capture instead; terminal. */
        data object Recapture : Step
    }

    var step: Step by mutableStateOf(initialStep(initialProposal))
        private set

    companion object {
        fun initialStep(proposal: ManualCropQuad): Step {
            val validation = proposal.validate()
            return when (validation) {
                is ManualCropQuad.Validation.Valid -> Step.Proposing(proposal.copy(corners = validation.orderedCorners))
                is ManualCropQuad.Validation.Invalid ->
                    throw IllegalArgumentException("proposed crop rejected: ${validation.reason}")
            }
        }
    }

    fun startAdjusting() {
        val quad = when (val current = step) {
            is Step.Proposing -> current.quad
            else -> throw IllegalStateException("adjusting only allowed from proposal, was $step")
        }
        step = validatedAdjusting(quad)
    }

    /**
     * Replaces one dragged corner (0..3) during adjustment; the quad is
     * re-validated so [Step.Adjusting.valid] always reflects the live shape.
     */
    fun updateCorner(index: Int, point: PointD) {
        val quad = when (val current = step) {
            is Step.Adjusting -> current.quad
            else -> throw IllegalStateException("corner updates require adjusting mode, was $step")
        }
        check(index in 0..3) { "corner index must be 0..3" }
        step = validatedAdjusting(quad.copy(corners = quad.corners.mapIndexed { i, old -> if (i == index) point else old }))
    }

    fun confirm(onConfirmed: (List<PointD>) -> Unit = {}) {
        when (val current = step) {
            is Step.Proposing -> {
                // The proposal was validated/canonicalized at shell creation.
                step = Step.Confirmed(current.quad.corners)
                onConfirmed(current.quad.corners)
            }
            is Step.Adjusting -> {
                check(current.valid) { "cannot confirm an invalid crop" }
                val validation = current.quad.validate()
                val ordered = (validation as ManualCropQuad.Validation.Valid).orderedCorners
                step = Step.Confirmed(ordered)
                onConfirmed(ordered)
            }
            else -> throw IllegalStateException("confirm only allowed before confirmation, was $step")
        }
    }

    fun requestRecapture() {
        when (step) {
            is Step.Proposing, is Step.Adjusting -> step = Step.Recapture
            else -> throw IllegalStateException("recapture only reachable before confirmation, was $step")
        }
    }

    private fun validatedAdjusting(quad: ManualCropQuad): Step.Adjusting = when (val v = quad.validate()) {
        is ManualCropQuad.Validation.Valid -> Step.Adjusting(quad.copy(corners = v.orderedCorners), true, null)
        is ManualCropQuad.Validation.Invalid -> Step.Adjusting(quad, false, v.reason.name)
    }
}

/**
 * Crop-confirm/manual-corner surface over the captured postcard still
 * (docs/implementation-plan.md M1-R15). Renders the proposed quad, lets the
 * user enter manual adjustment, and confirms only geometrically valid crops;
 * raw pixels stay outside composition state.
 */
@Composable
fun CropConfirmScreen(
    shell: CropConfirmationShell,
    onConfirmed: (List<PointD>) -> Unit,
    onRecapture: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when (val step = shell.step) {
            is CropConfirmationShell.Step.Proposing -> {
                GuidanceText(
                    text = "Move closer or adjust if this outline does not match your postcard.",
                    tag = "crop_proposed_note",
                )
                QuadPreview(step.quad)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Button(
                        onClick = { shell.confirm(onConfirmed) },
                        modifier = Modifier.testTag("crop_confirm_action"),
                    ) {
                        Text("Use this crop")
                    }
                    OutlinedButton(
                        onClick = { shell.startAdjusting() },
                        modifier = Modifier.testTag("crop_adjust_action"),
                    ) {
                        Text("Adjust corners")
                    }
                    OutlinedButton(
                        onClick = { onRecapture(); shell.requestRecapture() },
                        modifier = Modifier.testTag("crop_recapture_action"),
                    ) {
                        Text("Recapture")
                    }
                }
            }

            is CropConfirmationShell.Step.Adjusting -> {
                GuidanceText(
                    text = "Adjust the four corners so they sit on the postcard edges.",
                    tag = "crop_adjust_note",
                )
                QuadPreview(step.quad)
                if (!step.valid && step.invalidReason != null) {
                    GuidanceText(
                        text = "This outline cannot be used (${step.invalidReason}).",
                        tag = "crop_invalid_reason",
                    )
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Button(
                        onClick = { shell.confirm(onConfirmed) },
                        enabled = step.valid,
                        modifier = Modifier.testTag("crop_confirm_action"),
                    ) {
                        Text("Use this crop")
                    }
                    OutlinedButton(
                        onClick = { onRecapture(); shell.requestRecapture() },
                        modifier = Modifier.testTag("crop_recapture_action"),
                    ) {
                        Text("Recapture")
                    }
                }
            }

            is CropConfirmationShell.Step.Confirmed ->
                GuidanceText(text = "Crop confirmed.", tag = "crop_confirmed_status")

            CropConfirmationShell.Step.Recapture ->
                GuidanceText(text = "Capture again to continue.", tag = "crop_recapture_status")
        }
    }
}

@Composable
private fun QuadPreview(quad: ManualCropQuad) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(vertical = 8.dp)
            .testTag("crop_quad_preview"),
    ) {
        val scaleX = size.width / quad.frameWidth.toFloat()
        val scaleY = size.height / quad.frameHeight.toFloat()
        val mapped: List<Offset> = quad.corners.map { corner ->
            Offset((corner.x * scaleX).toFloat(), (corner.y * scaleY).toFloat())
        }
        if (mapped.size >= 2) {
            val path = Path()
            path.moveTo(mapped[0].x, mapped[0].y)
            for (index in 1 until mapped.size) {
                path.lineTo(mapped[index].x, mapped[index].y)
            }
            path.close()
            drawPath(path, color = Color.White, style = Stroke(width = 3f))
        }
    }
}

@Composable
private fun GuidanceText(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(tag),
    )
}
