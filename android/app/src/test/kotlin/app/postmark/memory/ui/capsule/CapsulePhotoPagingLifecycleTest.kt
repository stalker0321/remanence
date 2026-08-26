package app.postmark.memory.ui.capsule

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private fun pagingBitmap(ordinal: Int): android.graphics.Bitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(
        8 + ordinal,
        6,
        android.graphics.Bitmap.Config.ARGB_8888,
    )
    bitmap.eraseColor((0xFF204080L + ordinal).toInt())
    return bitmap
}

/**
 * FIX-PAGING-01 regression through the REAL screen surface: rapid
 * Next/Previous with a delayed page load must (a) never recycle any decoded
 * bitmap - asserted directly on the handed-out instances, because
 * Robolectric cannot and does not prove GPU Canvas scheduling, (b) never let
 * the stale superseded page corrupt the newer page's state, observable as a
 * spurious page error or as a forced re-decrypt of the newer cached page,
 * and (c) keep grant-guarded decryption, error UI, bounds and plaintext
 * cleanup intact end to end.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsulePhotoPagingLifecycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rapidNextWithDelayedLoadKeepsBitmapsAlivePagesFreshAndErrorsSilent() {
        val decoderProduced = ConcurrentHashMap<Int, android.graphics.Bitmap>()
        val loadCounts = ConcurrentHashMap<Int, AtomicInteger>()
        val page1DecryptStarted = AtomicBoolean(false)
        val releasePage1 = CompletableDeferred<Unit>()

        val loader = CapsulePhotoLoader { ordinal ->
            loadCounts.computeIfAbsent(ordinal) { AtomicInteger(0) }.incrementAndGet()
            if (ordinal == 1) {
                page1DecryptStarted.set(true)
                releasePage1.await()
            }
            DecryptedPhoto(ordinal, "jpeg-$ordinal".toByteArray())
        }
        val trackedDecoder = CapsulePageDecoder { bytes ->
            val ordinal = String(bytes).removePrefix("jpeg-").toInt()
            decoderProduced.computeIfAbsent(ordinal) { pagingBitmap(it) }
        }

        val presentation = CapsulePresentationState(loader).also { it.open(3, note = null) }
        var closedCallback = false

        composeRule.setContent {
            MaterialTheme {
                CapsuleScreen(
                    state = presentation,
                    onClose = { closedCallback = true },
                    decoder = trackedDecoder,
                )
            }
        }

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("capsule_page_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capsule_page_0").assertIsDisplayed()

        // Next onto a DELAYED page: the decrypt hangs in flight.
        composeRule.onNodeWithTag("capsule_next_button").performClick()
        composeRule.waitUntil(10_000) { page1DecryptStarted.get() }

        // Next again before page 1 arrives: effect 1 is cancelled mid-load.
        composeRule.onNodeWithTag("capsule_next_button").performClick()
        // Release the hung decrypt; whichever interleaving happens (cancel
        // first, or stale completion first) must stay safe.
        releasePage1.complete(Unit)

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("capsule_page_2").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capsule_page_2").assertIsDisplayed()
        composeRule.waitForIdle()

        // No spurious page error may exist after the stale page settles.
        assertEquals(
            0,
            composeRule.onAllNodesWithTag("capsule_page_error", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )

        // Bounce all the way back and forward again. If the stale load had
        // evicted the freshly cached newer page (the old swallowed-
        // cancellation bug), this forces a SECOND decrypt of ordinal 2.
        composeRule.onNodeWithTag("capsule_previous_button").performClick()
        composeRule.onNodeWithTag("capsule_previous_button").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("capsule_page_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("capsule_next_button").performClick()
        composeRule.onNodeWithTag("capsule_next_button").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("capsule_page_2").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        assertEquals(
            "each ordinal must decrypt exactly once across the whole bounce",
            mapOf(0 to 1, 1 to 1, 2 to 1),
            loadCounts.mapValues { it.value.get() },
        )

        // THE ownership invariant, checked on the real objects the screen
        // received: nothing was ever recycled, so no frame can ever draw a
        // recycled bitmap.
        assertEquals(3, decoderProduced.size)
        decoderProduced.values.forEach { bitmap ->
            assertFalse("screen must never recycle a decoded page", bitmap.isRecycled)
        }

        // Grant-guarded close still releases every decrypted byte reference.
        composeRule.onNodeWithTag("capsule_close_button").performClick()
        assertTrue(closedCallback)
        assertFalse(presentation.isOpen)
        assertTrue(presentation.loadedPages.isEmpty())
    }
}
