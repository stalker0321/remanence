package dev.hryshyn.remanence.ui.capsule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.data.network.ArtworkCache
import dev.hryshyn.remanence.core.data.network.ArtworkFetchResult
import dev.hryshyn.remanence.core.data.network.ArtworkFetcher
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageCanvas
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.rememberCollageTextMeasurer
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.capsule.music.rememberTrackServiceAction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Direct-testable recipient presentation for one already-parsed contract-V3
 * collage. It owns the decoded bitmaps for exactly the sealed content ids and
 * nothing else: the caller keeps the grant/material and hands one owned JPEG
 * buffer per content id in [loadPhoto], which is zeroized immediately after
 * decode. [Status] is exactly Loading -> Ready | Unsupported.
 *
 * Fail-closed: a decode miss or failure releases every decoded reference and
 * becomes [Status.Unsupported]; the host never paints a partial sheet, a v1
 * pager, or a track card. Cancellation releases the partial decode and
 * rethrows. [close] is idempotent and drops every owned reference.
 *
 * Resource-release limitation: decoded image pixels are native/handed out and
 * cannot be securely scrubbed in place, so release is reference-drop (the same
 * convention as [Ber1PresentationState]); only the owned JPEG buffers are
 * zeroized (in a `finally`, on every path).
 */
class CollagePresentationState(
    val expression: GeneratorExpression.ResolvedExpression,
    private val loadPhoto: suspend (contentId: String) -> ByteArray,
    val note: String? = null,
    val track: CapsuleTrackSnapshotV1? = null,
    private val decoder: Ber1PhotoDecoder = DefaultBer1PhotoDecoder,
    /**
     * Direct client-only cover fetcher. Null (or a fetch failure) degrades a
     * sealed thumbnail to the text/no-art layout; it never blocks capsule open
     * and never paints unverified bytes.
     */
    private val artworkFetcher: ArtworkFetcher? = null,
    /** Optional bounded in-memory verified-artwork cache. */
    private val artworkCache: ArtworkCache? = null,
    /** Hard ceiling on the cover fetch; a slower provider degrades to no art. */
    private val artworkTimeoutMs: Long = DEFAULT_ARTWORK_TIMEOUT_MS,
) : AutoCloseable {

    sealed interface Status {
        data object Loading : Status
        data class Ready(
            val bitmaps: Map<String, ImageBitmap>,
            /** Verified cover bitmap for a sealed thumbnail, else null. */
            val artwork: ImageBitmap? = null,
        ) : Status

        data class Unsupported(val reason: String) : Status
    }

    var status: Status by mutableStateOf(Status.Loading)
        private set

    /** Decoded bitmaps while [Status.Ready], otherwise empty. */
    val bitmaps: Map<String, ImageBitmap>
        get() = (status as? Status.Ready)?.bitmaps ?: emptyMap()

    /**
     * Single lock guarding [closed], the load-start/duplicate guard, every
     * iteration-start decision, Ready/Unsupported publication, the
     * owned-resource swap/clear, and [close]. Never held across the suspending
     * [loadPhoto] or the decode.
     */
    private val lock = Any()
    private var closed = false
    private var started = false

    /**
     * Decrypts (via [loadPhoto]) and decodes every sealed content id exactly
     * once. Never throws on decode failure: it releases the partial decode and
     * publishes [Status.Unsupported]. Cancellation rethrows after release.
     *
     * If [close] wins while a load is in flight, the loader zeroizes its
     * current buffer, drops every local reference, invokes no further content
     * id, publishes nothing, and returns; after [close] returns, [status]
     * cannot become Ready or Unsupported.
     */
    suspend fun load() {
        synchronized(lock) {
            check(!closed) { "collage presentation is closed" }
            check(!started) { "collage presentation already loaded" }
            started = true
        }
        val decoded = LinkedHashMap<String, ImageBitmap>(expression.input.photos.size)
        try {
            for (photo in expression.input.photos) {
                synchronized(lock) {
                    if (closed) {
                        decoded.clear()
                        return
                    }
                }
                val bytes = loadPhoto(photo.contentId)
                val bitmap: ImageBitmap?
                try {
                    bitmap = decoder.decode(bytes)
                } finally {
                    bytes.fill(0)
                }
                synchronized(lock) {
                    if (closed) {
                        decoded.clear()
                        return
                    }
                    if (bitmap == null) {
                        decoded.clear()
                        status = Status.Unsupported(DECODE_FAILURE_REASON)
                        return
                    }
                    decoded[photo.contentId] = bitmap
                }
            }
            val artworkBitmap = loadArtwork()
            synchronized(lock) {
                if (closed) {
                    decoded.clear()
                    return
                }
                status = Status.Ready(decoded, artworkBitmap)
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) { decoded.clear() }
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) {
                decoded.clear()
                if (!closed) status = Status.Unsupported(DECODE_FAILURE_REASON)
            }
        }
    }

    /**
     * Fetches and decodes the sealed thumbnail cover after the photos are
     * decoded (contract T8: after admission, off the open path). A cache hit by
     * the sealed digest skips the fetch. Any fetch/verify/decode failure
     * returns null so the host renders the text/no-art layout; the verified
     * bytes are zeroized on every path. Cancellation propagates and cancels the
     * fetcher's OkHttp call with this coroutine.
     */
    private suspend fun loadArtwork(): ImageBitmap? {
        val descriptor = expression.musicRegion?.artwork ?: return null
        val fetcher = artworkFetcher ?: return null
        val bytes = artworkCache?.get(descriptor.sha256) ?: run {
            val result = withTimeoutOrNull(artworkTimeoutMs) { fetcher.fetch(descriptor) }
            when (result) {
                is ArtworkFetchResult.Verified -> result.bytes
                else -> return null
            }
        }
        return try {
            val bitmap = decoder.decode(bytes)
            if (bitmap != null) artworkCache?.put(descriptor.sha256, bytes)
            bitmap
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Whole-sheet refusal (e.g. a [CollageRenderer.prepare] rejection): drops
     * every decoded reference and publishes the reason. A no-op once closed, so
     * no publication can follow [close].
     */
    fun unsupported(reason: String) {
        synchronized(lock) {
            if (closed) return
            status = Status.Unsupported(reason)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            status = Status.Loading
        }
    }

    companion object {
        const val DECODE_FAILURE_REASON = "the collage photos could not be decoded"

        /** Bounded cover-fetch budget; a slower provider degrades to no art. */
        const val DEFAULT_ARTWORK_TIMEOUT_MS = 5_000L
    }
}

/**
 * Renders the sealed collage for one open capsule. Uses the exact same
 * [rememberCollageTextMeasurer] instance for [CollageRenderer.prepare] and for
 * [CollageCanvas], so the layout that was accepted is the layout drawn.
 *
 * Any decode failure ([CollagePresentationState.Status.Unsupported]) or any
 * prepare/painter prerequisite failure ([CollageRenderer.SceneResult.Rejected])
 * renders only the shared unsupported notice: no partial sheet, no v1 pager,
 * no track card. [contentDescription] is caller-supplied localized copy.
 */
@Composable
internal fun CollagePresentationHost(
    state: CollagePresentationState,
    contentDescription: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    openTrackUrl: ((String) -> Boolean)? = null,
) {
    when (val status = state.status) {
        CollagePresentationState.Status.Loading -> Unit

        is CollagePresentationState.Status.Unsupported ->
            Ber1UnsupportedPresentation(status.reason, onClose, modifier)

        is CollagePresentationState.Status.Ready -> {
            val measurer = rememberCollageTextMeasurer()
            val images = status.bitmaps
            val artwork = status.artwork
            val sizes = remember(images) {
                images.mapValues { CollageRenderer.SourcePhoto(it.value.width, it.value.height) }
            }
            val artworkSize = remember(artwork) {
                artwork?.let { CollageRenderer.SourcePhoto(it.width, it.height) }
            }
            val result = remember(state.expression, state.note, state.track, sizes, measurer, artworkSize) {
                CollageRenderer.prepare(
                    state.expression, state.note, state.track, sizes, measurer, artworkSize,
                )
            }
            LaunchedEffect(result) {
                if (result is CollageRenderer.SceneResult.Rejected) state.unsupported(result.reason)
            }
            when (result) {
                is CollageRenderer.SceneResult.Ready -> Box(modifier.fillMaxSize()) {
                    val musicAction = rememberTrackServiceAction(state.track, openTrackUrl)
                    val musicLabel = state.track?.let { stringResource(R.string.track_open_action, it.title, it.artistDisplay) }
                    CollageCanvas(
                        scene = result.scene,
                        images = images,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        textMeasurer = measurer,
                        artwork = artwork,
                        onMusicClick = musicAction,
                        musicActionLabel = musicLabel,
                    )
                    HoldTextButton(
                        onClick = { state.close(); onClose() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .testTag("capsule_collage_close"),
                    ) { Text(stringResource(R.string.hold_close)) }
                }

                is CollageRenderer.SceneResult.Rejected ->
                    Ber1UnsupportedPresentation(result.reason, onClose, modifier)
            }
        }
    }
}
