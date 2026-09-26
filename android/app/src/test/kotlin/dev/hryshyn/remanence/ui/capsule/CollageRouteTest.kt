package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.FontRegistry
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A genuinely decodable JPEG for the ready path. */
private fun realCollageJpeg(color: Long): ByteArray {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color.toInt())
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
    bitmap.recycle()
    return output.toByteArray()
}

/**
 * Shared-route wiring: the `Ber1` admission arm splits by contract. A V3
 * expression builds/takes the Collage arm and renders only the collage canvas
 * (no v1 pager, no underlayer, no track card); V2 still renders the BER1 host;
 * LegacyV1 still renders the pager; decode failure shows the shared notice;
 * close/revocation release.
 *
 * Honest limit: no real production V3 producer exists yet, so these tests use
 * a synthetic admission (`Ber1(v3Expression)`), the same shape both readers
 * now return for a capability-supported V3.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollageRouteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentIds = listOf("p1", "p2", "p3")

    private class FakeSource(
        var admission: CapsulePresentationAdmission = CapsulePresentationAdmission.LegacyV1,
        var jpegBytes: (Int) -> ByteArray = { realCollageJpeg(0xFF336699L + it) },
        var note: String? = null,
        var snapshot: CapsuleTrackSnapshotV1? = null,
    ) : CapsuleContentReader {
        override suspend fun photoCount(capsuleId: String): Int = 3

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto =
            DecryptedPhoto(ordinal, jpegBytes(ordinal))

        override suspend fun noteText(capsuleId: String): String? = note

        override suspend fun trackSnapshot(capsuleId: String): CapsuleTrackSnapshotV1? = snapshot

        override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission = admission
    }

    private fun photosInput(note: String?) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 1L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(
                contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                contentHash = ('a' + index).toString().repeat(64),
            )
        },
        note = note,
        music = null,
    )

    private fun v3Expression() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = photosInput(null),
        placements = contentIds.mapIndexed { index, id ->
            GeneratorExpression.Placement(
                contentId = id, x = index * 120, y = 0, width = 120, height = 213,
                crop = GeneratorExpression.CropWindow(0, 0, 10000, 10000),
                maskId = null,
                contentRect = null,
            )
        },
        noteTreatment = "regular-region",
        diagnostics = emptyList(),
        fontVersion = FontRegistry.VERSION,
        paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        noteRegion = null,
        style = GeneratorExpression.StyleBinding(
            styleId = "press",
            styleVersion = "style-study-1",
            digest = requireNotNull(StyleRegistry.digest("press")),
        ),
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        musicRegion = null,
    )

    private fun v2Expression(): GeneratorExpression.ResolvedExpression {
        val plan = GeneratorEditorialRows.plan(photosInput(null))
        check(plan is GeneratorEditorialRows.PlanResult.Planned) { "expected Planned, got $plan" }
        return (plan as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun setContent(
        source: CapsuleContentReader,
        revocations: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 8),
        onClose: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                CapsuleRoute(
                    grantId = "grant-1",
                    contentFactory = { CapsuleContentBinding(capsuleId = "capsule-1", reader = source) },
                    validateLiveGrant = {},
                    revocations = revocations,
                    onClose = onClose,
                )
            }
        }
    }

    @Test
    fun v3CollageRendersCanvasWithoutPagerUnderlayerOrCard() {
        setContent(FakeSource(admission = CapsulePresentationAdmission.Ber1(v3Expression())))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collage_canvas").assertExists()
        composeRule.onNodeWithTag("capsule_collage_close").assertExists()
        composeRule.onNodeWithTag("capsule_page_0").assertDoesNotExist()
        composeRule.onNodeWithTag("capsule_underlayer").assertDoesNotExist()
        composeRule.onNodeWithTag("capsule_track_card").assertDoesNotExist()
        composeRule.onNodeWithTag("capsule_ber1_canvas").assertDoesNotExist()
    }

    @Test
    fun v3DecodeFailureShowsSharedNoticeWithoutCanvas() {
        setContent(
            FakeSource(
                admission = CapsulePresentationAdmission.Ber1(v3Expression()),
                jpegBytes = { ByteArray(4) },
            ),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("capsule_unsupported_notice").assertExists()
        composeRule.onNodeWithTag("capsule_unsupported_reason").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
        composeRule.onNodeWithTag("capsule_page_0").assertDoesNotExist()
    }

    @Test
    fun collageCloseInvokesCallbackAndReleases() {
        var closed = false
        setContent(
            FakeSource(admission = CapsulePresentationAdmission.Ber1(v3Expression())),
            onClose = { closed = true },
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("capsule_collage_close").performClick()
        assertTrue("close callback must fire", closed)
    }

    @Test
    fun revocationClosesCollageImmediately() {
        val revocations = MutableSharedFlow<String>(extraBufferCapacity = 8)
        setContent(
            FakeSource(admission = CapsulePresentationAdmission.Ber1(v3Expression())),
            revocations = revocations,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collage_canvas").assertExists()

        revocations.tryEmit("grant-1")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("capsule_route_failed_message").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
    }

    @Test
    fun v2StillRendersTheBer1Host() {
        setContent(FakeSource(admission = CapsulePresentationAdmission.Ber1(v2Expression())))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("capsule_ber1_canvas").assertExists()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
    }

    @Test
    fun legacyV1StillRendersThePager() {
        setContent(FakeSource(admission = CapsulePresentationAdmission.LegacyV1))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        composeRule.onNodeWithTag("collage_canvas").assertDoesNotExist()
    }
}
