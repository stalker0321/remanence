package dev.hryshyn.remanence.ui.create

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** ADR-021 sending: the voice tab's recorded row and the peaks the band draws. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoiceDraftTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sound = byteArrayOf(0, 0, 0, 16) + "ftypM4A ".toByteArray() + ByteArray(32)

    @Test
    fun peaksAreTheLoudestOfEachSliceWithTheLoudestAt255() {
        val levels = IntArray(960) { if (it < 480) 4000 else 16000 }
        val peaks = voicePeaks(levels)
        assertEquals(48, peaks.size)
        assertEquals(255, peaks.max())
        assertEquals(64, peaks.first())
        assertArrayEquals(IntArray(48), voicePeaks(IntArray(960) { 10 }))
        assertEquals(48, voicePeaks(intArrayOf(9000, 100)).size)
        assertArrayEquals(IntArray(48), voicePeaks(IntArray(0)))
    }

    @Test
    fun aRecordingIsListenedToRecordedAgainOrDeleted() {
        val voice = RecordedVoice(sound.copyOf(), 4200, IntArray(48) { it * 5 })
        var after: RecordedVoice? = voice
        composeRule.setContent { VoiceDraft(voice, onVoice = { after = it }, songChosen = false, phoneGift = false, enabled = true) }
        composeRule.onNodeWithTag("voice_listen").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_length").assertTextEquals("0:04")
        composeRule.onNodeWithTag("voice_rerecord").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_delete").performClick()
        assertNull(after)
        assertEquals(64, voice.sha256Hex.length)
        assertTrue(voice.note().mp4.contentEquals(sound))
    }

    @Test
    fun aSongIsReplacedAndAPhoneGiftCannotCarryAVoiceNote() {
        composeRule.setContent { VoiceDraft(null, onVoice = {}, songChosen = true, phoneGift = false, enabled = true) }
        composeRule.onNodeWithText("a card holds one sound: the voice note takes the song's place").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_record").assertIsDisplayed()
    }

    @Test
    fun aPhoneGiftOffersNoRecording() {
        composeRule.setContent { VoiceDraft(null, onVoice = {}, songChosen = false, phoneGift = true, enabled = true) }
        composeRule.onNodeWithTag("voice_pending_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_record").assertDoesNotExist()
    }
}
