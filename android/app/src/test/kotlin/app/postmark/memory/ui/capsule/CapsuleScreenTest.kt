package app.postmark.memory.ui.capsule

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A genuinely decodable JPEG so the bounded on-screen decode succeeds. */
private fun realJpeg(ordinal: Int): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor((0xFF000000L + ordinal * 0x010101L).toInt())
    val output = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/** Bounded presentation + cleanup proof for M1-M15. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsuleScreenTest {

    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private class RecordingLoader : CapsulePhotoLoader {
        val requestedOrdinals = mutableListOf<Int>()
        override suspend fun load(ordinal: Int): DecryptedPhoto {
            requestedOrdinals += ordinal
            return DecryptedPhoto(ordinal, realJpeg(ordinal))
        }
    }



    private fun state(note: String? = null): Pair<CapsulePresentationState, RecordingLoader> {
        val loader = RecordingLoader()
        return CapsulePresentationState(loader) { note } to loader
    }

    private fun openState(count: Int = 3, note: String? = null) =
        state(note).also { it.first.open(count) }

    @Test
    fun openOutsideThreeToFiveFailsClosed() {
        val (presentationState, _) = state()
        listOf(2, 6).forEach { badCount ->
            try {
                presentationState.open(badCount)
                throw AssertionError("expected failure for count=$badCount")
            } catch (expected: IllegalArgumentException) {
                assertEquals("capsule must contain 3..5 photos", expected.message)
            }
        }
        assertFalse(presentationState.isOpen)
    }

    @Test
    fun pagesLoadOnDemandAndCacheWithoutDuplicateLoads() = runBlocking {
        val (presentationState, loader) = openState()
        presentationState.pageAt(0)
        presentationState.pageAt(0)

        assertEquals(listOf(0), loader.requestedOrdinals)
        assertTrue(presentationState.loadedPages[0]!!.jpegBytes.isNotEmpty())

        presentationState.close()
        assertTrue(presentationState.loadedPages.isEmpty())
    }

    @Test
    fun closeReleasesEveryDecryptedReferenceAndBlocksFurtherPages() = runBlocking {
        val (presentationState, _) = openState(count = 5, note = "hello")
        presentationState.pageAt(4)
        assertTrue(presentationState.loadedPages.containsKey(4))

        presentationState.close()

        assertFalse(presentationState.isOpen)
        assertNull(presentationState.note)
        assertTrue(presentationState.loadedPages.isEmpty())
        try {
            presentationState.pageAt(0)
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("presentation closed", expected.message)
        }
        Unit
    }

    @Test
    fun screenNavigatesBoundedPagesShowsNoteOnceAndCleansUpOnClose() {
        val holder = openState(count = 3, note = "wish you were here")
        val presentationState = holder.first
        var closedCallback = false

        composeRule.setContent {
            CapsuleScreen(presentationState) { closedCallback = true }
        }

        composeRule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_page_0").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_note_text").assertIsDisplayed()

        // Previous is disabled at the first page.
        composeRule.onNodeWithTag("capsule_previous_button").assertIsNotEnabled()

        composeRule.onNodeWithTag("capsule_next_button").performClick()
        composeRule.onNodeWithTag("capsule_next_button").performClick()
        composeRule.onNodeWithTag("capsule_page_2").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_next_button").assertIsNotEnabled()

        composeRule.onAllNodesWithTag("capsule_note_text").assertCountEquals(1)

        composeRule.onNodeWithTag("capsule_close_button").performClick()

        assertTrue(closedCallback)
        assertFalse(presentationState.isOpen)
        assertTrue(presentationState.loadedPages.isEmpty())
    }

    @Test
    fun reopeningAfterCloseWorksFresh() = runBlocking {
        val (presentationState, loader) = openState()
        presentationState.pageAt(1)
        presentationState.close()

        presentationState.open(4)
        presentationState.pageAt(2)

        assertEquals(listOf(1, 2), loader.requestedOrdinals)
        assertEquals(4, presentationState.photoCount)
    }
}
