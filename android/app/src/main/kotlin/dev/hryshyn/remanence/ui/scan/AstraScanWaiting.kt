package dev.hryshyn.remanence.ui.scan

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.motion.AstraDissolveEasing
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.AstraGentleEasing
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec
import dev.hryshyn.remanence.ui.motion.AstraTravelEasing
import dev.hryshyn.remanence.ui.motion.astraTween

/**
 * Post-scan waiting group, adapted from the study waiting screens
 * (`design/motion/render.mjs:42-46` `recognizing/obtaining/verifying`):
 * `.wait-presentation` (the drawn postcard) + two-line `.status-copy` +
 * wait indicator + a stop action.
 *
 * State mapping (production states are product-frozen; study semantics
 * are preserved, not substituted):
 * - Matching ~= recognizing — line 1 is the shipped `hold_recognizing`,
 *   line 2 is the study recognizing body verbatim (`copy.mjs`).
 * - MaterialPending ~= obtaining — line 1 is the shipped online/offline
 *   copy; the online line 2 is the study obtaining body verbatim. The
 *   offline line 1 is already a complete two-sentence instruction, so no
 *   body is added rather than duplicating it.
 * - Accepted ~= verifying completion — line 1 is the shipped
 *   `hold_scan_opening` (the grant issuance in flight); line 2 is the
 *   study verifying body verbatim.
 *
 * Motion mapping (all tokens from [AstraMotionSpec]):
 * - Group arrival (capture -> waiting): routine 340 ms gentle fade via
 *   [AstraEnterTransition].
 * - Postcard entry presence: image-travel 450 ms dissolve + Focus
 *   selection depth (.987 -> 1).
 * - Cross-state carry (recognizing -> obtaining -> verifying): the same
 *   postcard instance stays composed and re-settles with image-travel
 *   450 ms travel easing — 680 ms dissolve once the state is Accepted,
 *   starting the opening beat. No overlay clones, so no ghost remnants
 *   by construction (the study ghost in `motion-original.mjs:37-52` is
 *   deliberately not ported).
 * - REDUCED_SHORT / INSTANT: alpha-only 80 ms fade / snap; no travel,
 *   no scaling, and the wait-loop pulse is replaced by a static track
 *   (`motion-original.mjs:25-30` `pulseWait` is skipped when reduced).
 *
 * TalkBack: the postcard exposes one merged graphic node; the progress
 * indicator and the static track are decorative (`invisibleToUser`, tags
 * kept for tests); status lines and the stop action announce normally.
 * Non-waiting states render nothing instead of crashing composition.
 */
@Composable
fun ScanWaitingGroup(
    state: ScanMatchUiState,
    motion: AstraMotionSpec.Resolved,
    onStopScanning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state !is ScanMatchUiState.Matching &&
        state !is ScanMatchUiState.Accepted &&
        state !is ScanMatchUiState.MaterialPending
    ) {
        return
    }

    AstraEnterTransition(motion = motion, modifier = modifier) {
        // The column must fill the waiting width: otherwise it wraps the
        // 76%-width postcard and CenterHorizontally has nothing to center in.
        Column(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val carryShiftPx = with(density) { 8.dp.toPx() }

            // Entry presence: the captured front becomes the wait.
            val presence = remember { Animatable(0f) }
            // Cross-state carry: re-settles on every waiting transition.
            val carry = remember { Animatable(1f) }
            var entered by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                val durationMs = AstraMotionSpec.imageTravelMs(motion)
                if (durationMs <= 0) {
                    presence.snapTo(1f)
                } else if (motion == AstraMotionSpec.Resolved.REDUCED_SHORT) {
                    // Reduced: fade only, no depth change.
                    presence.animateTo(1f, astraTween<Float>(durationMs, AstraGentleEasing))
                } else {
                    presence.animateTo(1f, astraTween<Float>(durationMs, AstraDissolveEasing))
                }
            }
            LaunchedEffect(state::class.simpleName) {
                if (!entered) {
                    entered = true
                } else if (motion == AstraMotionSpec.Resolved.FULL) {
                    val handoffMs = if (state is ScanMatchUiState.Accepted) {
                        AstraMotionSpec.openingMs(motion)
                    } else {
                        AstraMotionSpec.imageTravelMs(motion)
                    }
                    val handoffEasing = if (state is ScanMatchUiState.Accepted) {
                        AstraDissolveEasing
                    } else {
                        AstraTravelEasing
                    }
                    carry.snapTo(0f)
                    carry.animateTo(1f, astraTween<Float>(handoffMs, handoffEasing))
                } else {
                    carry.snapTo(1f)
                }
            }

            val fullMotion = motion == AstraMotionSpec.Resolved.FULL
            AstraPostcard(
                Modifier
                    // Design study styles.css `.postcard{width:76%}`: the
                    // waiting card is 76% of the content width and centered.
                    // The side room absorbs the -7° rotated corners and the
                    // card shadow, so no horizontal inset is needed.
                    .fillMaxWidth(0.76f)
                    .align(Alignment.CenterHorizontally)
                    .graphicsLayer {
                        alpha = presence.value
                        val entryScale = if (fullMotion) {
                            AstraMotionSpec.FOCUS_SELECT_SCALE_FROM +
                                (1f - AstraMotionSpec.FOCUS_SELECT_SCALE_FROM) * presence.value
                        } else {
                            1f
                        }
                        val carryScale = if (fullMotion) {
                            AstraMotionSpec.FOCUS_SELECT_SCALE_FROM +
                                (1f - AstraMotionSpec.FOCUS_SELECT_SCALE_FROM) * carry.value
                        } else {
                            1f
                        }
                        scaleX = entryScale * carryScale
                        scaleY = entryScale * carryScale
                        translationY = if (fullMotion) {
                            (1f - carry.value) * carryShiftPx
                        } else {
                            0f
                        }
                    },
            )
            Spacer(Modifier.height(16.dp))
            // Design motion shell `.status-copy{min-height:105px}`: the copy
            // block reserves its full two-line height even when a state
            // carries only one line (offline pending), so late text can
            // never move the indicator/stop action below it.
            key(state::class.simpleName) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 105.dp)
                        .testTag("scan_status_copy"),
                ) {
                    when (state) {
                    is ScanMatchUiState.Matching -> {
                        Text(
                            stringResource(R.string.hold_recognizing),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("scan_matching"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.hold_scan_recognizing_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("scan_matching_body"),
                        )
                    }
                    is ScanMatchUiState.Accepted -> {
                        Text(
                            stringResource(R.string.hold_scan_opening),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("scan_verified"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.hold_scan_opening_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("scan_verified_body"),
                        )
                    }
                    is ScanMatchUiState.MaterialPending -> {
                        Text(
                            text = if (state.connected) {
                                stringResource(R.string.hold_scan_material_online)
                            } else {
                                stringResource(R.string.hold_scan_material_offline)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag(
                                if (state.connected) {
                                    "scan_material_pending_online"
                                } else {
                                    "scan_material_pending_offline"
                                },
                            ),
                        )
                        if (state.connected) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.hold_scan_material_online_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("scan_material_pending_online_body"),
                            )
                        }
                    }
                    else -> Unit
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (fullMotion) {
            // Decorative: invisible to accessibility services, but kept
            // in the semantics tree so its presence stays testable
            // (clearAndSetSemantics would prune the node entirely,
            // including its test tag).
            CircularProgressIndicator(
                modifier = Modifier
                    .semantics { invisibleToUser() }
                    .testTag("scan_wait_progress"),
            )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(2.dp),
                    )
                    .semantics { invisibleToUser() }
                    .testTag("scan_wait_static"),
                )
            }
            Spacer(Modifier.height(12.dp))
            HoldTextButton(
                onClick = onStopScanning,
                modifier = Modifier.testTag("scan_stop_scanning"),
            ) {
                Text(stringResource(R.string.hold_scan_stop))
            }
        }
    }
}
