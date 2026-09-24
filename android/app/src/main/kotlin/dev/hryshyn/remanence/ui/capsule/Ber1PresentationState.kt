package dev.hryshyn.remanence.ui.capsule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.create.GeneratorBer1Preview
import dev.hryshyn.remanence.ui.create.GeneratorBer1Renderer
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/** Decodes one decrypted photo page into a bitmap (host-testable seam). */
fun interface Ber1PhotoDecoder {
    fun decode(jpegBytes: ByteArray): ImageBitmap?
}

/** Production decoder: one bounded decode of an already-budgeted artifact. */
val DefaultBer1PhotoDecoder = Ber1PhotoDecoder { jpegBytes ->
    android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)?.asImageBitmap()
}

/**
 * ADR-018 receiver presentation state: owns the decrypted BER1 composition for
 * one open capsule. The whole frozen layout needs every authored photo at
 * once, so all pages are decrypted and decoded once, keyed by the authored
 * content id. [close] drops every decoded reference; each transient decrypted
 * page buffer is zeroized immediately after decode.
 */
class Ber1PresentationState(
    val expression: GeneratorExpression.ResolvedExpression,
    private val loadPhoto: suspend (ordinal: Int) -> ByteArray,
    private val decoder: Ber1PhotoDecoder = DefaultBer1PhotoDecoder,
    /**
     * S2b-receiver: the sealed v2-only track snapshot, or null when the
     * manifest carries none. Null renders exactly the pre-S2 layout.
     */
    val track: CapsuleTrackSnapshotV1? = null,
) : AutoCloseable {

    var bitmaps: Map<String, ImageBitmap> by mutableStateOf(emptyMap())
        private set

    var isOpen: Boolean by mutableStateOf(false)
        private set

    /** Decrypts + decodes every authored source; fails closed on any miss. */
    suspend fun load() {
        check(!isOpen) { "presentation already open" }
        val decoded = LinkedHashMap<String, ImageBitmap>(expression.input.photos.size)
        for (photo in expression.input.photos) {
            val bytes = loadPhoto(photo.ordinal)
            try {
                val bitmap = decoder.decode(bytes)
                    ?: throw IllegalStateException("a capsule photo could not be decoded")
                decoded[photo.contentId] = bitmap
            } finally {
                bytes.fill(0)
            }
        }
        bitmaps = decoded
        isOpen = true
    }

    override fun close() {
        bitmaps = emptyMap()
        isOpen = false
    }
}

/**
 * Renders the sealed exact BER1 expression with the shared renderer seam.
 * Nothing is drawn unless the renderer admits the complete layout; a
 * non-ready layout renders only a typed notice, never a partial postcard.
 */
@Composable
internal fun Ber1PresentationHost(
    state: Ber1PresentationState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isOpen) return
    Box(modifier.fillMaxSize()) {
        val result = GeneratorBer1Preview(
            expression = state.expression,
            sources = state.bitmaps,
            modifier = Modifier.fillMaxSize().testTag("capsule_ber1_canvas"),
        )
        if (result !is GeneratorBer1Renderer.SceneResult.Ready) {
            Text(
                text = "This capsule's layout can't be shown.",
                modifier = Modifier.align(Alignment.Center).testTag("capsule_ber1_blocked"),
            )
        }
        state.track?.let { track ->
            TrackSnapshotCard(
                track = track,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
        HoldTextButton(
            onClick = { state.close(); onClose() },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .testTag("capsule_ber1_close"),
        ) { Text(stringResource(R.string.hold_close)) }
    }
}

/**
 * S2b-receiver: compact offline card for the sealed track snapshot.
 * Pure text from the already-decrypted manifest — no network, no links,
 * no streaming (S3 later). Hidden entirely when the manifest carries no
 * snapshot.
 */
@Composable
internal fun TrackSnapshotCard(
    track: CapsuleTrackSnapshotV1,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag("capsule_track_card")) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("capsule_track_title"),
        )
        Text(
            text = track.artistDisplay,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("capsule_track_artist"),
        )
        track.version?.let { version ->
            Text(
                text = version,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("capsule_track_version"),
            )
        }
    }
}

/** Renders NOTHING but a typed notice for an unadmittable v2 capsule. */
@Composable
internal fun Ber1UnsupportedPresentation(
    reason: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "This capsule uses a layout this version can't show.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("capsule_unsupported_notice"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("capsule_unsupported_reason"),
        )
        Spacer(Modifier.height(16.dp))
        HoldTextButton(onClick = onClose, modifier = Modifier.testTag("capsule_unsupported_close")) {
            Text(stringResource(R.string.hold_close))
        }
    }
}
