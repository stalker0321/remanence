package dev.hryshyn.remanence.ui.capsule

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.KeyTemplates
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
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
        var admission: CapsulePresentationAdmission = CapsulePresentationAdmission.LegacyV1
        var photoCountCalls = 0
        var loadPhotoCalls = 0
        var noteTextCalls = 0

        override suspend fun photoCount(capsuleId: String): Int {
            photoCountCalls += 1
            if (failCountLookup) throw IllegalStateException("ciphertext unavailable")
            return count
        }

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
            loadPhotoCalls += 1
            return DecryptedPhoto(ordinal, realJpeg(0xFF336699L + ordinal))
        }

        override suspend fun noteText(capsuleId: String): String? {
            noteTextCalls += 1
            return "hello"
        }

        override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission =
            admission
    }

    private val fitsPort = GeneratorEditorialRows.NoteMeasurementPort {
        GeneratorEditorialRows.NoteMeasurement.Fits(GeneratorEditorialRows.NOTE_REGION)
    }

    private fun ber1Expression(note: String? = null): GeneratorExpression.ResolvedExpression {
        val input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 1L,
            photos = listOf("p1", "p2", "p3").mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id,
                    ordinal = index,
                    widthPx = 1000,
                    heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = note,
            music = null,
        )
        val plan = if (note.isNullOrEmpty()) {
            GeneratorEditorialRows.plan(input)
        } else {
            GeneratorEditorialRows.plan(input, fitsPort)
        }
        check(plan is GeneratorEditorialRows.PlanResult.Planned) { "expected Planned, got $plan" }
        return (plan as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    /**
     * Builds the route admission through the REAL receiver admission gate (not
     * a hand-injected `Ber1`), so the route consumes the actual production
     * `ExpressionReceiverAdmission.Result`.
     */
    private fun admissionFor(
        expression: GeneratorExpression.ResolvedExpression,
        manifestNote: String?,
    ): CapsulePresentationAdmission {
        val content = dev.hryshyn.remanence.core.crypto.ContentManifestContent(
            protocolVersion = 2,
            photos = expression.input.photos.mapIndexed { index, photo ->
                dev.hryshyn.remanence.core.crypto.ManifestPhoto(
                    blobId = java.util.UUID.nameUUIDFromBytes("blob-$index".toByteArray()),
                    ordinal = index,
                    width = photo.widthPx,
                    height = photo.heightPx,
                )
            },
            note = manifestNote,
            expression = dev.hryshyn.remanence.core.crypto.ContentExpression(
                candidateId = "cand-1",
                projectionHash = "a".repeat(64),
                expression = expression,
            ),
        )
        return when (val admitted = dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission.admit(content)) {
            is dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission.Result.Supported ->
                CapsulePresentationAdmission.Ber1(admitted.expression)
            is dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission.Result.Unsupported ->
                CapsulePresentationAdmission.Unsupported(admitted.reason)
        }
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
        composeRule.onNodeWithTag("capsule_reveal_button").performClick()
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

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun supportedExpressionRendersTheBer1HostAndNeverThePhotoPager() {
        val source = FakeSource().apply {
            admission = admissionFor(ber1Expression(), manifestNote = null)
        }
        setContent(source, KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")))

        composeRule.onNodeWithTag("capsule_ber1_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_ber1_close").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("capsule_page_indicator").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("capsule_unsupported_notice").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun unsupportedExpressionRendersNothingButATypedNotice() {
        // The REAL admission rejects a measured-note expression whose manifest
        // note is absent (note/region mismatch); the route must render nothing.
        val source = FakeSource().apply {
            admission = admissionFor(ber1Expression("hi"), manifestNote = null)
        }
        setContent(source, KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")))

        composeRule.onNodeWithTag("capsule_unsupported_notice").assertIsDisplayed()
        composeRule.onNodeWithTag("capsule_unsupported_reason").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("capsule_ber1_canvas").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag("capsule_page_indicator").fetchSemanticsNodes().isEmpty())
        // Fail-closed: an unadmittable v2 capsule NEVER falls back to the v1
        // photo pager, so no photo or note read is attempted.
        assertEquals(0, source.photoCountCalls)
        assertEquals(0, source.loadPhotoCalls)
        assertEquals(0, source.noteTextCalls)
    }
}
