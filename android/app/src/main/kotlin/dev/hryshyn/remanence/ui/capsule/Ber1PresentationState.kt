package dev.hryshyn.remanence.ui.capsule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.capsule.music.StreamingService
import dev.hryshyn.remanence.ui.capsule.music.TrackServicePicker
import dev.hryshyn.remanence.ui.capsule.music.rememberTrackServiceAction
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
    validateExport: () -> Unit = {},
    openTrackUrl: ((String) -> Boolean)? = null,
    exportFactory: @Composable (() -> Unit) -> CapsuleImageExport = { rememberCapsuleImageExport(it) },
) {
    if (!state.isOpen) return
    var exportReady by remember(state) { mutableStateOf(false) }
    var originalPhoto by remember(state) { mutableStateOf<String?>(null) }
    val photoLabel = stringResource(R.string.capsule_view_original)
    val export = exportFactory { check(state.isOpen && exportReady); validateExport() }
    val musicAction = rememberTrackServiceAction(state.track, openTrackUrl)
    CapsuleSheetFrame(export, enabled = state.isOpen && exportReady, ratio = 360f / 640f,
        onClose = { state.close(); onClose() }, modifier = modifier, closeTag = "capsule_ber1_close",
        actions = { if (exportReady) CapsuleListenAction(state.track, musicAction) }) { sheetModifier ->
      Column(sheetModifier) {
       Box(Modifier.fillMaxWidth().weight(1f)) {
        val result = GeneratorBer1Preview(
            expression = state.expression,
            sources = state.bitmaps,
            modifier = Modifier.fillMaxSize().testTag("capsule_ber1_canvas"),
        )
        SideEffect { exportReady = result is GeneratorBer1Renderer.SceneResult.Ready }
        if (result is GeneratorBer1Renderer.SceneResult.Ready) BoxWithConstraints(Modifier.fillMaxSize()) {
            val scene = result.scene
            val scale = minOf(maxWidth.value / scene.canvasWidth, maxHeight.value / scene.canvasHeight)
            val dx = (maxWidth.value - scene.canvasWidth * scale) / 2f
            val dy = (maxHeight.value - scene.canvasHeight * scale) / 2f
            scene.photos.forEachIndexed { index, photo ->
                val rect = photo.contentRect
                Box(Modifier.absoluteOffset((dx + rect.x * scale).dp, (dy + rect.y * scale).dp)
                    .size((rect.width * scale).dp, (rect.height * scale).dp)
                    .clickable(role = Role.Button, onClickLabel = photoLabel) {
                        try { check(state.isOpen && exportReady); validateExport(); originalPhoto = photo.contentId }
                        catch (_: Exception) { state.close(); onClose() }
                    }.semantics { contentDescription = photoLabel }.testTag("capsule_photo_region_$index"))
            }
        }
        if (result !is GeneratorBer1Renderer.SceneResult.Ready) {
            Text(
                text = stringResource(R.string.capsule_route_failure),
                modifier = Modifier.align(Alignment.Center).testTag("capsule_ber1_blocked"),
            )
        }
       }
        // Legacy track metadata is supplemental to the frozen BER1 canvas.
        // Give it its own paper area; never cover an authored photo with it.
        state.track?.takeIf { exportReady }?.let { track ->
            TrackSnapshotCard(
                track = track,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
      }
    }
    originalPhoto?.let { id ->
        CapsulePhotoDialog(state.expression.input.photos.sortedBy { it.ordinal }.map { it.contentId }, state.bitmaps, id,
            validateAccess = { check(state.isOpen && exportReady); validateExport() },
            onAccessLost = { originalPhoto = null; state.close(); onClose() }, onDismiss = { originalPhoto = null })
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
    /**
     * S3: null hides the Listen action (pre-S3 card shape). Non-null opens
     * the service picker; the boolean result reports whether an external
     * handler was found.
     */
    onOpenService: ((StreamingService) -> Boolean)? = null,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var noHandler by remember { mutableStateOf(false) }
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
        if (onOpenService != null) {
            HoldTextButton(
                onClick = { pickerOpen = true; noHandler = false },
                modifier = Modifier.testTag("capsule_track_open"),
            ) { Text(stringResource(R.string.track_open_action, track.title, track.artistDisplay)) }
        }
        if (noHandler) {
            Text(
                text = stringResource(R.string.track_no_handler),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("capsule_track_no_browser"),
            )
        }
    }
    if (pickerOpen && onOpenService != null) {
        TrackServicePicker(
            onDismiss = { pickerOpen = false },
            onOpenService = onOpenService,
            onResult = { opened -> pickerOpen = false; noHandler = !opened },
        )
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
            stringResource(R.string.capsule_audit_unsupported),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("capsule_unsupported_notice"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.capsule_audit_unsupported_detail),
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
