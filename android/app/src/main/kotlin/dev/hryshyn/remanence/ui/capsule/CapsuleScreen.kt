package dev.hryshyn.remanence.ui.capsule

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

/**
 * Fullscreen capsule presentation bound to an already-open
 * [CapsulePresentationState]. Pages are decoded on demand into [ImageBitmap]s
 * with a bounded JPEG decode and released when the state closes. No
 * thumbnails, no gallery grid, no save/share: only sequential fullscreen
 * pages plus the optional note.
 */
@Composable
fun CapsuleScreen(
    state: CapsulePresentationState,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // FIX-STATE-07: bumped by the Retry action to re-run a failed page load.
    var pageRetryEpoch by remember { mutableIntStateOf(0) }

    // Dispose the current page bitmap whenever it changes or the screen leaves.
    DisposableEffect(currentIndex, state.isOpen, pageRetryEpoch) {
        onDispose {
            pageBitmap?.recycle()
            pageBitmap = null
        }
    }

    LaunchedEffect(currentIndex, state.isOpen, pageRetryEpoch) {
        pageError = null
        if (!state.isOpen) return@LaunchedEffect
        try {
            val photo = state.pageAt(currentIndex)
            // Bounded decode: bytes are already capped by the artifact budget.
            pageBitmap = withContext(Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeByteArray(
                    photo.jpegBytes,
                    0,
                    photo.jpegBytes.size,
                )
            } ?: run {
                // A failed decode must not keep serving poisoned bytes.
                state.loadedPages.remove(currentIndex)
                pageError = "this page could not be decoded"
                null
            }
        } catch (failure: Exception) {
            state.loadedPages.remove(currentIndex)
            pageError = failure.message ?: "page unavailable"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Photo ${currentIndex + 1} of ${state.photoCount}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("capsule_page_indicator"),
        )
        Spacer(Modifier.height(8.dp))
        val bitmap = pageBitmap
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Capsule photo ${currentIndex + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .testTag("capsule_page_${currentIndex}"),
            )
            pageError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    pageError!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("capsule_page_error"),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { pageRetryEpoch += 1 },
                    modifier = Modifier.testTag("capsule_page_retry"),
                ) { Text("Try again") }
            }
            else -> CircularProgressIndicator(modifier = Modifier.testTag("capsule_page_loading"))
        }
        Spacer(Modifier.height(12.dp))
        state.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("capsule_note_text"),
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (state.canRewind(currentIndex)) currentIndex -= 1
                },
                enabled = state.canRewind(currentIndex),
                modifier = Modifier.testTag("capsule_previous_button"),
            ) {
                Text("Previous")
            }
            Button(
                onClick = {
                    if (state.canAdvance(currentIndex)) currentIndex += 1
                },
                enabled = state.canAdvance(currentIndex),
                modifier = Modifier.testTag("capsule_next_button"),
            ) {
                Text("Next")
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                state.close()
                onClose()
            },
            modifier = Modifier.testTag("capsule_close_button"),
        ) {
            Text("Close")
        }
    }
}
