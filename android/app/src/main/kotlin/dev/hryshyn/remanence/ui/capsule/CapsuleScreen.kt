package dev.hryshyn.remanence.ui.capsule

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.hryshyn.remanence.core.model.ProtocolV1Limits

/** One decrypted photo page held ONLY in memory while the screen is open. */
data class DecryptedPhoto(
    val ordinal: Int,
    val jpegBytes: ByteArray,
)

/** Loads one decrypted photo on demand for the open capsule screen. */
fun interface CapsulePhotoLoader {
    suspend fun load(ordinal: Int): DecryptedPhoto
}

/**
 * M1-M15 state holder: presents the scanned capsule's 3-5 photos fullscreen
 * plus the optional note, decoding strictly on demand while a live scan grant
 * exists. [close] releases every decrypted byte reference immediately - the
 * caller must invoke it when the screen is left, matching the memory-only
 * grant lifecycle (docs/architecture.md sections 5 and 10).
 *
 * FIX-REVIEW3-01: page loads carry a session generation. A load that was in
 * flight while [close] ran (expiry/revocation) can neither return its
 * plaintext to the caller nor re-enter [loadedPages]; the rejected bytes are
 * zeroed first. Concurrent loads for the same page are serialized, so one
 * decrypt is ever in flight per request chain.
 *
 * FIX-REVIEW3-03: the plaintext note is OWNED by this state (handed over at
 * [open]) instead of being captured by an unclosable caller lambda - [close]
 * and revocation drop the last controlled strong reference immediately.
 */
class CapsulePresentationState(
    private val photoLoader: CapsulePhotoLoader,
) {

    var photoCount: Int by mutableIntStateOf(0)
        private set

    /** Decrypted page cache keyed by ordinal; empty after [close]. */
    val loadedPages: MutableMap<Int, DecryptedPhoto> = java.util.concurrent.ConcurrentHashMap()

    var isOpen: Boolean by mutableStateOf(false)
        private set

    /**
     * FIX-REVIEW3-01: bumped by [open] and [close]; any in-flight load whose
     * captured generation differs afterwards is stale and must be rejected.
     */
    private var loadGeneration: Int by mutableIntStateOf(0)

    /** Serializes page loads so concurrent requests share one decrypt path. */
    private val pageLoadMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * FIX-REVIEW3-03: the decrypted note lives HERE so [close] can drop the
     * reference; nothing outside holds it for the presentation's lifetime.
     */
    private var decryptedNote: String? = null

    /** Test-only probe proving the strong reference is really dropped. */
    internal val holdsDecryptedNoteForTests: Boolean
        get() = decryptedNote != null

    val note: String? get() = if (isOpen) decryptedNote else null

    fun open(expectedCount: Int, note: String?) {
        check(!isOpen) { "presentation already open" }
        require(expectedCount in ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX) {
            "capsule must contain ${ProtocolV1Limits.PHOTO_COUNT_MIN}..${ProtocolV1Limits.PHOTO_COUNT_MAX} photos"
        }
        loadGeneration += 1
        decryptedNote = note
        photoCount = expectedCount
        isOpen = true
    }

    /** Fullscreen navigation stays inside the declared page bounds. */
    fun canAdvance(currentOrdinal: Int): Boolean = currentOrdinal + 1 < photoCount

    fun canRewind(currentOrdinal: Int): Boolean = currentOrdinal > 0

    /**
     * Loads one page on demand; repeats are served from the memory cache.
     * FIX-REVIEW3-01: AFTER the suspend load returns, the presentation must
     * still be open within the SAME generation - a close/expiry during the
     * load rejects the result (zeroing the plaintext bytes) instead of
     * caching or returning it.
     */
    suspend fun pageAt(ordinal: Int): DecryptedPhoto = pageLoadMutex.withLock {
        val session = loadGeneration
        check(isOpen && loadGeneration == session) { "presentation closed" }
        require(ordinal in 0 until photoCount) { "ordinal out of bounds" }
        loadedPages[ordinal]?.let { return@withLock it }
        val photo = photoLoader.load(ordinal)
        if (!isOpen || loadGeneration != session) {
            // Closed/expired/reopened mid-flight: the plaintext must die here.
            photo.jpegBytes.fill(0)
            throw IllegalStateException("presentation closed during page load")
        }
        loadedPages.putIfAbsent(ordinal, photo) ?: photo
    }

    /** Releases every decrypted byte reference; nothing survives leaving. */
    fun close() {
        // Invalidate every in-flight load BEFORE dropping the references.
        loadGeneration += 1
        loadedPages.clear()
        // Kotlin cannot scrub String contents; dropping the last controlled
        // strong reference immediately is the strongest available guarantee.
        decryptedNote = null
        photoCount = 0
        isOpen = false
    }
}

/** Decodes one page's decrypted JPEG bytes into the fullscreen bitmap. */
fun interface CapsulePageDecoder {
    fun decode(jpegBytes: ByteArray): android.graphics.Bitmap?
}

/**
 * Production decoder: a single bounded decode of bytes already capped by the
 * artifact budget. A null result marks the artifact as poisoned.
 */
val DefaultCapsulePageDecoder = CapsulePageDecoder { jpegBytes ->
    android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}

/**
 * FIX-PAGING-01: owns the decoded page shown by [CapsuleScreen]. The pager
 * NEVER recycles bitmaps - on minSdk 26 pixel memory is GC-managed, and an
 * eager recycle raced the render thread drawing the outgoing Image ("trying
 * to use a recycled bitmap"). Only reference drops happen, here and at the
 * composable's lifecycle boundaries. Retention stays bounded by design: at
 * most the current decoded bitmap plus the presentation's existing 3..5-page
 * decrypted cache.
 *
 * Stale-load safety: every request is keyed by its requested ordinal. The
 * mutex in [CapsulePresentationState.pageAt] serializes DECRYPTS but is
 * released before DECODE runs, so an older request can finish decoding while
 * a newer ordinal is already displayed. Any result whose ordinal no longer
 * matches [latestRequestOrdinal] - including its error path - is dropped
 * without ever touching state. Cancellation is rethrown, never converted to
 * a fake page error (which previously also evicted a NEWER cached page via
 * the live-captured index).
 */
internal class CapsulePager(
    private val presentation: CapsulePresentationState,
    private val decoder: CapsulePageDecoder,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** The currently displayed decoded page; null while loading or failed. */
    var displayedBitmap: android.graphics.Bitmap? by mutableStateOf(null)
        private set

    /** Visible, retryable failure for the current page request. */
    var pageError: String? by mutableStateOf(null)
        private set

    private var latestRequestOrdinal: Int = -1

    /**
     * Loads and displays [requestedOrdinal]. Superseded requests land
     * nothing: neither bitmap nor error may overwrite a newer ordinal.
     */
    suspend fun show(requestedOrdinal: Int) {
        latestRequestOrdinal = requestedOrdinal
        // Reference-only drop of the outgoing page; never recycle().
        displayedBitmap = null
        pageError = null
        if (!presentation.isOpen) return
        try {
            val photo = presentation.pageAt(requestedOrdinal)
            val decoded = withContext(decodeDispatcher) {
                decoder.decode(photo.jpegBytes)
            }
            if (latestRequestOrdinal != requestedOrdinal) return
            if (decoded == null) {
                // A failed decode must not keep serving poisoned bytes.
                presentation.loadedPages.remove(requestedOrdinal)
                pageError = "this page could not be decoded"
            } else {
                displayedBitmap = decoded
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (latestRequestOrdinal != requestedOrdinal) return
            presentation.loadedPages.remove(requestedOrdinal)
            pageError = failure.message ?: "page unavailable"
        }
    }

    /** Drops the displayed reference at a safe lifecycle boundary. */
    fun dropDisplayed() {
        displayedBitmap = null
    }
}

/**
 * Fullscreen capsule presentation bound to an already-open
 * [CapsulePresentationState]. Pages are decoded on demand into [ImageBitmap]s
 * with a bounded JPEG decode and released when the state closes. No
 * thumbnails, no gallery grid, no save/share: only sequential fullscreen
 * pages plus the optional note.
 *
 * FIX-PAGING-01: bitmap ownership lives in [CapsulePager]; this composable
 * holds no recycle path at all. Navigating pages (and leaving the screen)
 * only DROPS references - the previously displayed bitmap stays valid for
 * any frame still drawing it and is reclaimed by GC afterwards.
 */
@Composable
fun CapsuleScreen(
    state: CapsulePresentationState,
    modifier: Modifier = Modifier,
    decoder: CapsulePageDecoder = DefaultCapsulePageDecoder,
    underlayerEnabled: Boolean = true,
    onClose: () -> Unit,
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    // FIX-STATE-07: bumped by the Retry action to re-run a failed page load.
    var pageRetryEpoch by remember { mutableIntStateOf(0) }
    val pager = remember(state, decoder) { CapsulePager(state, decoder) }

    // Reference-only drop when the page changes or the screen leaves; the
    // old eager recycle() here crashed hardware devices mid-frame.
    DisposableEffect(currentIndex, state.isOpen, pageRetryEpoch) {
        onDispose { pager.dropDisplayed() }
    }

    LaunchedEffect(currentIndex, state.isOpen, pageRetryEpoch) {
        pager.show(currentIndex)
    }

    // Do not keep plaintext visible for an exit transition after close/expiry.
    if (!state.isOpen) return
    var reveal by remember(state) { mutableFloatStateOf(0f) }
    var dragging by remember(state) { mutableStateOf(false) }
    val settled by animateFloatAsState(reveal, tween(280), label = "capsule underlayer")
    val progress = if (dragging) reveal else settled
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().background(HoldColors.Paper)) {
        val footer = 76.dp
        val available = (maxHeight - footer).coerceAtLeast(1.dp)
        val revealDistance = with(density) { (available * .34f).toPx() }.coerceAtLeast(1f)
        val imageHeight = available * (1f - .34f * progress)
        val gesture = if (underlayerEnabled) Modifier.draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { delta -> reveal = (reveal - delta / revealDistance).coerceIn(0f, 1f) },
            onDragStarted = { dragging = true; reveal = progress },
            onDragStopped = { dragging = false; reveal = if (reveal > .3f) 1f else 0f },
        ) else Modifier

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(imageHeight).then(gesture), contentAlignment = Alignment.Center) {
                val bitmap = pager.displayedBitmap
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Capsule photo ${currentIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().testTag("capsule_page_${currentIndex}"),
                    )
                    pager.pageError != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(pager.pageError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("capsule_page_error"))
                        HoldTextButton(onClick = { pageRetryEpoch += 1 }, modifier = Modifier.testTag("capsule_page_retry")) { Text(stringResource(R.string.hold_retry)) }
                    }
                    else -> CircularProgressIndicator(modifier = Modifier.testTag("capsule_page_loading"))
                }
            }
            if (progress > .001f) {
                Column(
                    Modifier.fillMaxWidth().height(available - imageHeight)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)
                        .testTag("capsule_underlayer"),
                ) {
                    // Only actual content is available in the current artifact contract.
                    // Sender/date/share need protocol metadata, never specimen values.
                    Text(stringResource(R.string.hold_inside), style = MaterialTheme.typography.labelMedium, color = HoldColors.Muted)
                    Spacer(Modifier.height(12.dp))
                    Text(state.note ?: stringResource(R.string.hold_photo_summary, state.photoCount),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(if (state.note != null) "capsule_note_text" else "capsule_content_summary"))
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = footer).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoldTextButton(onClick = { if (state.canRewind(currentIndex)) currentIndex-- },
                    enabled = state.canRewind(currentIndex), modifier = Modifier.testTag("capsule_previous_button")) { Text(stringResource(R.string.hold_previous_photo)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (underlayerEnabled) {
                        HoldTextButton(onClick = { reveal = if (reveal < .5f) 1f else 0f }, modifier = Modifier.testTag("capsule_reveal_button")) {
                            Text(if (reveal < .5f) stringResource(R.string.hold_about_short) else stringResource(R.string.hold_collapse))
                        }
                    }
                    Text("${currentIndex + 1} / ${state.photoCount}", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("capsule_page_indicator"))
                }
                HoldTextButton(onClick = { if (state.canAdvance(currentIndex)) currentIndex++ },
                    enabled = state.canAdvance(currentIndex), modifier = Modifier.testTag("capsule_next_button")) { Text(stringResource(R.string.hold_next_photo)) }
            }
        }
        HoldTextButton(
            onClick = { state.close(); onClose() },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(HoldColors.Paper.copy(alpha = .92f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .testTag("capsule_close_button"),
        ) { Text(stringResource(R.string.hold_close)) }
    }
}
