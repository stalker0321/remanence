package dev.hryshyn.remanence.pending

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.rememberAstraMotion
import kotlinx.coroutines.launch

/**
 * T186: localized secondary code entry for the scan/receive surface.
 *
 * - Rendered ONLY when [PendingFeatureGate.isEnabled]; otherwise returns
 *   nothing so beta/release/default builds are byte-identical in behavior.
 * - Secondary path: the ordinary FRONT scan stays primary and untouched.
 * - Code errors are generic ([R.string.pending_code_invalid]); the input is
 *   normalized via [PendingCodeNormalizer] and never logged.
 * - Server lookup/proof/claim journey is NOT fabricated here: on a
 *   well-formed code the UI reports [R.string.pending_code_unavailable]
 *   until the frozen T185 api-contract lands. Mock claims are never labelled
 *   real encrypted opens.
 * - Visuals/motion preserved: Hold buttons + routine [AstraEnterTransition].
 */
@Composable
fun PendingCodeEntryButton(
    onOpenCodeEntry: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Test seam: production passes the build-time gate (default); device
     * tests force it to mount the real component without an auth session.
     */
    enabled: Boolean = PendingFeatureGate.isEnabled(),
) {
    if (!enabled) return
    HoldTextButton(
        onClick = onOpenCodeEntry,
        modifier = modifier.testTag("pending_code_entry"),
    ) {
        Text(stringResource(R.string.pending_code_entry))
    }
}

@Composable
fun PendingCodeEntrySheet(
    onClose: () -> Unit,
    onWellFormedCode: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Test seam, see [PendingCodeEntryButton]. */
    enabled: Boolean = PendingFeatureGate.isEnabled(),
    /**
     * T186 M7b: real R6 lookup (anonymous, budgeted). Null keeps the
     * fail-closed unavailable note. A found gift shows the server's
     * minimal hint; the continue journey (R7) lands on its handoff.
     */
    lookup: (suspend (String) -> dev.hryshyn.remanence.core.data.network.GiftLookupResult)? = null,
    /**
     * T186 M7c: proof-flow factory (flow-scoped VM, caller scope). Null
     * keeps the found hint + deferred note (lookup-only mode). Production
     * builds a container-wired VM via `AppContainer.newPendingProofViewModel`.
     */
    newProofVm: ((kotlinx.coroutines.CoroutineScope) -> PendingProofViewModel)? = null,
) {
    if (!enabled) return
    var raw by remember { mutableStateOf("") }
    var showGenericError by remember { mutableStateOf(false) }
    var showUnavailable by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var foundHint by remember { mutableStateOf<String?>(null) }
    var foundReference by remember { mutableStateOf<String?>(null) }
    var showProof by remember { mutableStateOf(false) }
    var lookupError by remember { mutableStateOf<dev.hryshyn.remanence.pending.PendingLookupError?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AstraEnterTransition(motion = rememberAstraMotion()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("pending_code_sheet"),
        ) {
            Text(
                stringResource(R.string.pending_code_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pending_code_body),
                style = MaterialTheme.typography.bodySmall,
                color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = raw,
                onValueChange = {
                    raw = it
                    showGenericError = false
                    showUnavailable = false
                    foundHint = null
                    foundReference = null
                    showProof = false
                    lookupError = null
                },
                label = { Text(stringResource(R.string.pending_code_hint)) },
                singleLine = true,
                enabled = !working,
                modifier = Modifier.fillMaxWidth().testTag("pending_code_field"),
            )
            if (showGenericError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pending_code_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("pending_code_error"),
                )
            }
            if (showUnavailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pending_code_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("pending_code_unavailable"),
                )
            }
            if (foundHint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    foundHint!!,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("pending_code_found"),
                )
            }
            val proofVmFactory = newProofVm
            val proofReference = foundReference
            if (foundHint != null && proofVmFactory != null && proofReference != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pending_code_found_next),
                    style = MaterialTheme.typography.bodySmall,
                    color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                    modifier = Modifier.testTag("pending_code_found_next"),
                )
                Spacer(Modifier.height(8.dp))
                if (showProof) {
                    val proofScope = scope
                    val proofVm = remember(proofReference) { proofVmFactory(proofScope) }
                    PendingProofFlow(
                        viewModel = proofVm,
                        giftReference = proofReference,
                        onDone = {
                            showProof = false
                            foundHint = null
                            foundReference = null
                        },
                    )
                } else {
                    Button(
                        onClick = { showProof = true },
                        modifier = Modifier.testTag("pending_code_continue_proof"),
                    ) { Text(stringResource(R.string.pending_code_continue_proof)) }
                }
            }
            if (foundHint != null && (proofVmFactory == null || proofReference == null)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.pending_code_found_next_update),
                    style = MaterialTheme.typography.bodySmall,
                    color = dev.hryshyn.remanence.ui.hold.HoldColors.Muted,
                    modifier = Modifier.testTag("pending_code_found_next"),
                )
            }
            if (lookupError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(lookupError!!.stringRes()),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("pending_code_lookup_error"),
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val normalized = PendingCodeNormalizer.normalize(raw)
                    if (normalized == null) {
                        showGenericError = true
                    } else if (lookup == null) {
                        // Fail closed: no lookup path wired.
                        showUnavailable = true
                        onWellFormedCode(normalized)
                    } else {
                        working = true
                        foundHint = null
                        lookupError = null
                        scope.launch {
                            try {
                                when (val result = lookup(normalized)) {
                                    is dev.hryshyn.remanence.core.data.network.GiftLookupResult.Found -> {
                                        foundHint = result.hint.ifBlank { null } ?: result.channel
                                        foundReference = result.giftReference
                                        onWellFormedCode(normalized)
                                    }
                                    is dev.hryshyn.remanence.core.data.network.GiftLookupResult.Failure -> {
                                        lookupError = when (result.reason) {
                                            dev.hryshyn.remanence.core.data.network.GiftLookupFailure.GIFT_UNAVAILABLE ->
                                                dev.hryshyn.remanence.pending.PendingLookupError.NOT_FOUND
                                            dev.hryshyn.remanence.core.data.network.GiftLookupFailure.NETWORK ->
                                                dev.hryshyn.remanence.pending.PendingLookupError.NETWORK
                                            else -> if (result.retryable) {
                                                dev.hryshyn.remanence.pending.PendingLookupError.NETWORK
                                            } else {
                                                dev.hryshyn.remanence.pending.PendingLookupError.GENERIC
                                            }
                                        }
                                        onWellFormedCode(normalized)
                                    }
                                }
                            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                lookupError = dev.hryshyn.remanence.pending.PendingLookupError.NETWORK
                            } finally {
                                working = false
                            }
                        }
                    }
                },
                enabled = !working,
                modifier = Modifier.testTag("pending_code_continue"),
            ) { Text(stringResource(R.string.pending_code_continue)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.testTag("pending_code_cancel"),
            ) { Text(stringResource(R.string.pending_code_cancel)) }
        }
    }
}

/** Lookup outcome kinds; all messages stay generic (no existence oracle). */
enum class PendingLookupError {
    NOT_FOUND,
    NETWORK,
    GENERIC,
}

internal fun PendingLookupError.stringRes(): Int = when (this) {
    PendingLookupError.NOT_FOUND -> dev.hryshyn.remanence.R.string.pending_code_notfound
    PendingLookupError.NETWORK -> dev.hryshyn.remanence.R.string.pending_code_network
    PendingLookupError.GENERIC -> dev.hryshyn.remanence.R.string.pending_code_generic
}
