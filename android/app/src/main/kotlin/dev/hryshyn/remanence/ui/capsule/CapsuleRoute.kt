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
import androidx.compose.ui.unit.dp
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException
import dev.hryshyn.remanence.core.model.ProtocolV1Limits

/**
 * FIX-STATE-07: THE terminal-state contract of the capsule route. The route
 * is either [Loading], [Ready] with an open presentation, or [Failed] with a
 * display-safe message - it can NEVER spin forever on an identity, photoCount,
 * note, or decrypt error, and every state exposes a working Close/Back.
 */
sealed interface CapsuleRouteState {
    data object Loading : CapsuleRouteState

    data class Ready(val presentation: CapsulePresentationState) : CapsuleRouteState

    data class Failed(val message: String) : CapsuleRouteState
}

/** Port resolving the local identity for decryption; null means unavailable. */
fun interface CapsuleIdentityLoader {
    suspend fun load(): KeysetHandle?
}

/**
 * FIX-M1-007-13 / FIX-STATE-07: the live capsule presentation bound to the
 * memory-only grant lifecycle. Photos decrypt on demand from ciphertext via
 * the grant-guarded source; leaving through ANY path runs [onClose], which
 * consumes the grant and releases every decrypted byte.
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
    modifier: Modifier = Modifier,
) {
    var state by remember(grantId) {
        mutableStateOf<CapsuleRouteState>(CapsuleRouteState.Loading)
    }
    // Retry re-runs the whole load exactly once per tap.
    var loadEpoch by remember(grantId) { mutableIntStateOf(0) }

    // The route owns the presentation state while it is mounted. System Back
    // must release it before delegating the navigation/authority revocation.
    BackHandler {
        (state as? CapsuleRouteState.Ready)?.presentation?.close()
        onClose()
    }

    // Grant replacement or route removal must close the prior Ready state;
    // CapsuleScreen's bitmap-only disposal is not sufficient for plaintext.
    val stateOwnedByDisposalEffect = state
    DisposableEffect(grantId, stateOwnedByDisposalEffect) {
        onDispose {
            (stateOwnedByDisposalEffect as? CapsuleRouteState.Ready)?.presentation?.close()
        }
    }

    // Authoritative revocation releases every decrypted reference immediately.
    LaunchedEffect(grantId) {
        revocations.collect { revoked ->
            if (revoked == grantId) {
                (state as? CapsuleRouteState.Ready)?.presentation?.close()
                state = CapsuleRouteState.Failed("This capsule was closed automatically.")
            }
        }
    }

    LaunchedEffect(grantId, loadEpoch) {
        state = CapsuleRouteState.Loading
        try {
            val binding = contentFactory()
            if (binding.capsuleId.isEmpty()) throw IllegalStateException("no capsule binding")
            val source = GrantGuardedCapsuleContentSource(
                delegate = binding.reader,
                validateLiveGrant = validateLiveGrant,
            )
            val declaredCount = source.photoCount(binding.capsuleId)
            if (declaredCount !in ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX) {
                throw IllegalStateException("capsule photo layout is invalid")
            }
            val note = source.noteText(binding.capsuleId)
            val presentation = CapsulePresentationState(
                photoLoader = { ordinal -> source.loadPhoto(binding.capsuleId, ordinal) },
            )
            presentation.open(declaredCount, note)
            state = CapsuleRouteState.Ready(presentation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            (state as? CapsuleRouteState.Ready)?.presentation?.close()
            onFailure()
            state = CapsuleRouteState.Failed("The capsule could not be opened.")
        }
    }

    when (val current = state) {
        CapsuleRouteState.Loading -> RouteBusy(onClose = onClose)

        is CapsuleRouteState.Failed -> RouteFailure(
            message = current.message,
            onRetry = { loadEpoch += 1 },
            onClose = onClose,
        )

        is CapsuleRouteState.Ready ->
            CapsuleScreen(state = current.presentation, onClose = onClose, modifier = modifier)
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
        Text("Decrypting the capsule…", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.testTag("capsule_route_close")) {
            Text("Close")
        }
    }
}

@Composable
private fun RouteFailure(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "The capsule could not be opened:",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("capsule_route_failed_header"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("capsule_route_failed_message"),
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = onRetry, modifier = Modifier.testTag("capsule_route_retry")) {
                Text("Try again")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onClose, modifier = Modifier.testTag("capsule_route_close")) {
                Text("Close")
            }
        }
    }
}
