package app.postmark.memory.ui.capsule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import postmark.core.model.ProtocolV1Limits

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
 */
class CapsulePresentationState(
    private val photoLoader: CapsulePhotoLoader,
    private val noteText: () -> String?,
) {

    var photoCount: Int by mutableIntStateOf(0)
        private set

    /** Decrypted page cache keyed by ordinal; empty after [close]. */
    val loadedPages: MutableMap<Int, DecryptedPhoto> = mutableMapOf()

    var isOpen: Boolean by mutableStateOf(false)
        private set

    val note: String? get() = if (isOpen) noteText() else null

    fun open(expectedCount: Int) {
        check(!isOpen) { "presentation already open" }
        require(expectedCount in ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX) {
            "capsule must contain ${ProtocolV1Limits.PHOTO_COUNT_MIN}..${ProtocolV1Limits.PHOTO_COUNT_MAX} photos"
        }
        photoCount = expectedCount
        isOpen = true
    }

    /** Fullscreen navigation stays inside the declared page bounds. */
    fun canAdvance(currentOrdinal: Int): Boolean = currentOrdinal + 1 < photoCount

    fun canRewind(currentOrdinal: Int): Boolean = currentOrdinal > 0

    /** Loads one page on demand; repeats are served from the memory cache. */
    suspend fun pageAt(ordinal: Int): DecryptedPhoto {
        check(isOpen) { "presentation closed" }
        require(ordinal in 0 until photoCount) { "ordinal out of bounds" }
        loadedPages[ordinal]?.let { return it }
        return photoLoader.load(ordinal).also { loadedPages[ordinal] = it }
    }

    /** Releases every decrypted byte reference; nothing survives leaving. */
    fun close() {
        loadedPages.clear()
        photoCount = 0
        isOpen = false
    }
}

/**
 * Fullscreen capsule presentation bound to an already-open
 * [CapsulePresentationState]. No thumbnails, no gallery grid, no save/share:
 * only sequential fullscreen pages plus the optional note.
 */
@Composable
fun CapsuleScreen(
    state: CapsulePresentationState,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    var currentIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            "Photo ${currentIndex + 1} of ${state.photoCount}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("capsule_page_indicator"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "decrypted-page-${currentIndex}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("capsule_page_${currentIndex}"),
        )
        Spacer(Modifier.height(12.dp))
        state.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("capsule_note_text"),
            )
            Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                if (state.canRewind(currentIndex)) currentIndex -= 1
            },
            enabled = state.canRewind(currentIndex),
            modifier = Modifier.testTag("capsule_previous_button"),
        ) {
            Text("Previous")
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
