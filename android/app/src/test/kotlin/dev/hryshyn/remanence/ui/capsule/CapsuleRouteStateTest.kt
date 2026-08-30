package dev.hryshyn.remanence.ui.capsule

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.KeyTemplates
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.google.crypto.tink.KeysetHandle

/** A genuinely decodable JPEG for the ready path. */
private fun realJpeg(color: Long): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color.toInt())
    val output = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/**
 * FIX-STATE-07 regression: the capsule route is Loading | Ready | Failed -
 * it can never spin forever, photoCount outside 3..5 fails closed WITHOUT
 * coercion, failures expose working Retry + Close from any state, and an
 * authoritative revocation releases the presentation immediately.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapsuleRouteStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeSource : CapsuleContentReader {
        var count = 3
        var failCountLookup = false

        override suspend fun photoCount(capsuleId: String): Int {
            if (failCountLookup) throw IllegalStateException("ciphertext unavailable")
            return count
        }

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
            DecryptedPhoto(ordinal, realJpeg(0xFF336699L + ordinal))

        override suspend fun noteText(capsuleId: String): String? = "hello"
    }

    private fun setContent(
        source: CapsuleContentReader,
        identityHandle: KeysetHandle?,
        revocations: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 8),
        onClose: () -> Unit = {},
    ): MutableSharedFlow<String> {
        composeRule.setContent {
            MaterialTheme {
                CapsuleRoute(
                    grantId = "grant-1",
                    contentFactory = {
                        if (identityHandle == null) error("local keys unavailable")
                        CapsuleContentBinding(capsuleId = "capsule-1", reader = source)
                    },
                    validateLiveGrant = {},
                    revocations = revocations,
                    onClose = onClose,
                )
            }
        }
        return revocations
    }

    @Test
    fun missingIdentityFailsVisiblyAndCloseIsAlwaysAvailable() {
        var closed = false
        setContent(FakeSource(), identityHandle = null, onClose = { closed = true })

        composeRule.onNodeWithTag("capsule_route_failed_header").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_route_failed_message").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_route_close").performClick()

        assertTrue(closed)
    }

    @Test
    fun photoCountOutsideThreeToFiveFailsClosedWithoutCoercion() {
        val source = FakeSource().apply { count = 2 }
        setContent(source, KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")))

        composeRule.onNodeWithTag("capsule_route_failed_message").assertIsDisplayed()
        assertEquals(2, source.count)

        // Above the range fails exactly the same way.
        source.count = 6
        composeRule.onNodeWithTag("capsule_route_retry").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capsule_route_failed_message").assertIsDisplayed()
        assertEquals(6, source.count)
    }

    @Test
    fun transientFailureRecoversThroughVisibleRetryIntoReadyPresentation() {
        val source = FakeSource().apply { failCountLookup = true }
        setContent(source, KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")))

        composeRule.onNodeWithTag("capsule_route_failed_header").assertIsDisplayed()

        source.failCountLookup = false
        composeRule.onNodeWithTag("capsule_route_retry").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_note_text").assertIsDisplayed()
    }

    @Test
    fun loadingExposesCloseAndRevocationClosesReadyPresentationImmediately() {
        val revocations = MutableSharedFlow<String>(extraBufferCapacity = 8)
        var closed = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gatedSource = object : CapsuleContentReader {
            override suspend fun photoCount(capsuleId: String): Int {
                gate.await()
                return 3
            }

            override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
                DecryptedPhoto(ordinal, realJpeg(0xFF336699L + ordinal))

            override suspend fun noteText(capsuleId: String): String? = null
        }
        setContent(gatedSource, KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")), revocations) {
            closed = true
        }

        // Loading exposes Close immediately - never a trapped spinner.
        composeRule.onNodeWithTag("capsule_route_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_route_close").assertIsDisplayed()

        // Release the load: Ready renders the real presentation.
        composeRule.runOnIdle { gate.complete(Unit) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()

        // An authoritative revocation closes the presentation immediately.
        revocations.tryEmit("grant-1")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capsule_route_failed_header").assertIsDisplayed()
        assertFalse(closed) // revocation ejects WITHOUT consuming the grant via close
    }
}
