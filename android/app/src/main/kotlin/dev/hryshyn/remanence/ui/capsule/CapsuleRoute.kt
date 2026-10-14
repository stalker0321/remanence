package dev.hryshyn.remanence.ui.capsule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.data.network.ArtworkCache
import dev.hryshyn.remanence.core.data.network.ArtworkFetcher
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.ProtocolV1Limits

/**
 * FIX-STATE-07: THE terminal-state contract of the capsule route. The route
 * is either [Loading], [Ready] with an open presentation, or [Failed] with a
 * display-safe message - it can NEVER spin forever on an identity, photoCount,
 * note, or decrypt error, and every state exposes a working Close/Back.
 *
 * ADR-018: [Ready] carries exactly one of three presentations - the unchanged
 * v1 photo pager, the exact sealed BER1 expression, or a typed unsupported
 * notice that renders NOTHING.
 */
sealed interface CapsulePresentation : AutoCloseable {
    class Photos(val state: CapsulePresentationState) : CapsulePresentation {
        override fun close() = state.close()
    }

    class Ber1(val state: Ber1PresentationState) : CapsulePresentation {
        override fun close() = state.close()
    }

    /** V5 finished collage (contract V3); shares the same close/release contract. */
    class Collage(val state: CollagePresentationState) : CapsulePresentation {
        override fun close() = state.close()
    }

    data class Unsupported(val reason: String) : CapsulePresentation {
        override fun close() = Unit
    }
}

sealed interface CapsuleRouteState {
    data object Loading : CapsuleRouteState

    data class Ready(val presentation: CapsulePresentation) : CapsuleRouteState

    data class Failed(val reason: CapsuleRouteFailure) : CapsuleRouteState
}

enum class CapsuleRouteFailure { CLOSED, CANNOT_OPEN }

/** Port resolving the local identity for decryption; null means unavailable. */
fun interface CapsuleIdentityLoader {
    suspend fun load(): KeysetHandle?
}

/**
 * FIX-M1-007-13 / FIX-STATE-07: the live capsule presentation bound to the
 * memory-only grant lifecycle. Photos decrypt on demand from ciphertext via
 * the grant-guarded source; leaving through ANY path runs [onClose], which
 * consumes the grant and releases every decrypted byte. Every site that
 * closes an open presentation additionally runs [onReleaseContent] (which
 * also covers disposal/revocation paths that never reach [onClose]);
 * failure-before-Ready is covered by [onFailure]. All three must be
 * idempotent.
 *
 * Failure policy (fail closed):
 * - a missing identity, unknown capsule, or decrypt error becomes [CapsuleRouteState.Failed];
 * - photoCount outside 3..5 fails closed - it is NEVER coerced into range;
 * - Retry re-runs the load; Close consumes the grant from any state;
 * - an authoritative revocation closes the presentation immediately.
 */
@Composable
internal fun CapsuleRoute(
    grantId: String,
    contentFactory: suspend () -> CapsuleContentBinding,
    validateLiveGrant: () -> Unit,
    revocations: Flow<String>,
    onClose: () -> Unit,
    onFailure: () -> Unit = {},
    /**
     * T186 M14d-scan: released exactly when the route closes an open
     * presentation (back/close, disposal, revocation, superseded load)
     * — the pending plane wipes caller-owned photo bytes here. Runs in
     * addition to [onClose]/[onFailure] (which also cover
     * failure-before-Ready, where no presentation exists); all of them
     * must be idempotent. v1 sources are unaffected (default no-op).
     */
    onReleaseContent: () -> Unit = {},
    /**
     * T186: fired once with the capsule id when the route publishes Ready
     * with a SUPPORTED presentation (Photos/Ber1/Collage). Never fired for
     * Loading/Failed/Unsupported. The receiver (application container)
     * re-validates the live grant + owner before enqueueing retirement;
     * display never waits for it.
     */
    onSupportedReady: (capsuleId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var state by remember(grantId) {
        mutableStateOf<CapsuleRouteState>(CapsuleRouteState.Loading)
    }
    // Retry re-runs the whole load exactly once per tap.
    var loadEpoch by remember(grantId) { mutableIntStateOf(0) }
    // Authoritative revocation is terminal for this grant. It is set BEFORE
    // any published state is closed/overwritten, so a loader suspended in
    // decrypt/decode can never publish Ready afterwards.
    val revoked = remember(grantId) { mutableStateOf(false) }

    // T186: capsule id of the published Ready state, for the retirement
    // callback. Set only on successful load; cleared on revoke/failure.
    var readyCapsuleId by remember(grantId) { mutableStateOf<String?>(null) }
    // Bounded in-memory verified-cover cache shared across this route's opens.
    // Never on disk, keyed by the sealed digest, never logged.
    val artworkCache = remember { ArtworkCache() }

    // The route owns the presentation state while it is mounted. System Back
    // must release it before delegating the navigation/authority revocation.
    BackHandler {
        (state as? CapsuleRouteState.Ready)?.presentation?.close()
        onReleaseContent()
        onClose()
    }

    // Grant replacement or route removal must close the prior Ready state;
    // CapsuleScreen's bitmap-only disposal is not sufficient for plaintext.
    // Release is Ready-guarded like the close: transitions that never held
    // a presentation (e.g. Loading→Ready) must not release content that a
    // newer effect just opened.
    val stateOwnedByDisposalEffect = state
    DisposableEffect(grantId, stateOwnedByDisposalEffect) {
        onDispose {
            val owned = stateOwnedByDisposalEffect as? CapsuleRouteState.Ready
            owned?.presentation?.close()
            if (owned != null) onReleaseContent()
        }
    }

    // Authoritative revocation releases every decrypted reference immediately.
    LaunchedEffect(grantId) {
        revocations.collect { revokedId ->
            if (revokedId == grantId) {
                revoked.value = true
                readyCapsuleId = null
                (state as? CapsuleRouteState.Ready)?.presentation?.close()
                onReleaseContent()
                state = CapsuleRouteState.Failed(CapsuleRouteFailure.CLOSED)
            }
        }
    }

    LaunchedEffect(grantId, loadEpoch) {
        if (revoked.value) {
            state = CapsuleRouteState.Failed(CapsuleRouteFailure.CLOSED)
            return@LaunchedEffect
        }
        state = CapsuleRouteState.Loading
        try {
            val binding = contentFactory()
            if (binding.capsuleId.isEmpty()) throw IllegalStateException("no capsule binding")
            val source = GrantGuardedCapsuleContentSource(
                delegate = binding.reader,
                validateLiveGrant = validateLiveGrant,
            )
            val loaded: CapsuleRouteState = when (val admission = source.presentationAdmission(binding.capsuleId)) {
                CapsulePresentationAdmission.LegacyV1 -> {
                    val declaredCount = source.photoCount(binding.capsuleId)
                    if (declaredCount !in ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX) {
                        throw IllegalStateException("capsule photo layout is invalid")
                    }
                    val note = source.noteText(binding.capsuleId)
                    val presentation = CapsulePresentationState(
                        photoLoader = { ordinal -> source.loadPhoto(binding.capsuleId, ordinal) },
                    )
                    presentation.open(declaredCount, note)
                    CapsuleRouteState.Ready(CapsulePresentation.Photos(presentation))
                }
                is CapsulePresentationAdmission.Ber1 -> {
                    val expression = admission.expression
                    // R7 (tasks/T154/gate-patches.md): contract V3 and V4
                    // share the one collage presentation path. V4 must never
                    // fall through to the BER1 arm — that would silently drop
                    // provenance/decor/shape/music-mode slots (R8: the BER1
                    // fallback is NOT safe). Admission already ran in front,
                    // so an Unsupported expression renders the typed notice.
                    if (expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V3 ||
                        expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V4
                    ) {
                        // V5 finished collage: the sealed contract decides the
                        // arm. A decode/prepare failure renders the shared
                        // typed notice, never the BER1 host, v1 pager, or a
                        // track card.
                        val collage = CollagePresentationState(
                            expression = expression,
                            loadPhoto = { contentId ->
                                val ordinal = expression.input.photos
                                    .first { it.contentId == contentId }.ordinal
                                source.loadPhoto(binding.capsuleId, ordinal).jpegBytes
                            },
                            // Authenticated manifest note and sealed snapshot
                            // ride the same grant-guarded source.
                            note = source.noteText(binding.capsuleId),
                            track = source.trackSnapshot(binding.capsuleId),
                            // Client-only verified cover fetch; failure degrades
                            // to the text/no-art layout.
                            artworkFetcher = ArtworkFetcher.Default,
                            artworkCache = artworkCache,
                        )
                        collage.load()
                        CapsuleRouteState.Ready(CapsulePresentation.Collage(collage))
                    } else {
                        val ber1 = Ber1PresentationState(
                            expression = expression,
                            loadPhoto = { ordinal ->
                                source.loadPhoto(binding.capsuleId, ordinal).jpegBytes
                            },
                            // S2b-receiver: sealed snapshot (or null) rides the
                            // same grant-guarded source; LegacyV1 never has one.
                            track = source.trackSnapshot(binding.capsuleId),
                        )
                        ber1.load()
                        CapsuleRouteState.Ready(CapsulePresentation.Ber1(ber1))
                    }
                }
                is CapsulePresentationAdmission.Unsupported ->
                    CapsuleRouteState.Ready(CapsulePresentation.Unsupported(admission.reason))
            }
            // Revocation or route disposal won while decrypt/decode was
            // suspended: release the newly loaded state and never publish it.
            if (revoked.value || !currentCoroutineContext().isActive) {
                (loaded as? CapsuleRouteState.Ready)?.presentation?.close()
                onReleaseContent()
                return@LaunchedEffect
            }
            state = loaded
            readyCapsuleId = if (loaded is CapsuleRouteState.Ready) binding.capsuleId else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            (state as? CapsuleRouteState.Ready)?.presentation?.close()
            onReleaseContent()
            readyCapsuleId = null
            onFailure()
            state = CapsuleRouteState.Failed(CapsuleRouteFailure.CANNOT_OPEN)
        }
    }

    // T186: genuine supported-presentation signal. Fires once per grant when
    // Ready carries a supported presentation; the container re-validates the
    // live grant + owner account before enqueueing. Unsupported never fires.
    val supportedReadyId = (state as? CapsuleRouteState.Ready)?.let { ready ->
        if (ready.presentation is CapsulePresentation.Unsupported) null else readyCapsuleId
    }
    LaunchedEffect(grantId, supportedReadyId) {
        if (supportedReadyId != null) {
            try {
                validateLiveGrant()
            } catch (_: Exception) {
                return@LaunchedEffect
            }
            onSupportedReady(supportedReadyId)
        }
    }

    when (val current = state) {
        CapsuleRouteState.Loading -> RouteBusy(onClose = onClose)

        is CapsuleRouteState.Failed -> RouteFailure(
            reason = current.reason,
            onRetry = { loadEpoch += 1 },
            onClose = onClose,
        )

        is CapsuleRouteState.Ready -> when (val presentation = current.presentation) {
            is CapsulePresentation.Photos ->
                CapsuleScreen(state = presentation.state, onClose = onClose, modifier = modifier,
                    validateExport = validateLiveGrant)
            is CapsulePresentation.Ber1 ->
                Ber1PresentationHost(state = presentation.state, onClose = onClose, modifier = modifier,
                    validateExport = validateLiveGrant)
            is CapsulePresentation.Collage ->
                CollagePresentationHost(
                    state = presentation.state,
                    contentDescription = stringResource(R.string.hold_collage_content_description),
                    onClose = onClose,
                    modifier = modifier,
                    validateExport = validateLiveGrant,
                )
            is CapsulePresentation.Unsupported ->
                Ber1UnsupportedPresentation(
                    reason = presentation.reason,
                    onClose = onClose,
                    modifier = modifier,
                )
        }
    }
}

@Composable
private fun RouteBusy(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(modifier = Modifier.testTag("capsule_route_loading"))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.capsule_route_loading), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.testTag("capsule_route_close")) {
            Text(stringResource(R.string.hold_close))
        }
    }
}

@Composable
private fun RouteFailure(reason: CapsuleRouteFailure, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.capsule_route_failure),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("capsule_route_failed_header"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (reason == CapsuleRouteFailure.CLOSED)
                R.string.capsule_route_closed else R.string.capsule_route_failure),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("capsule_route_failed_message"),
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = onRetry, modifier = Modifier.testTag("capsule_route_retry")) {
                Text(stringResource(R.string.hold_retry))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onClose, modifier = Modifier.testTag("capsule_route_close")) {
                Text(stringResource(R.string.hold_close))
            }
        }
    }
}
