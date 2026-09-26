package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Incoming-path seam: [PreparedPresentationMaterial.expressionAdmission] with a
 * capability. This is the exact call the incoming reader reaches after
 * threading, so the capability-aware V3 behavior is pinned here without an app
 * dependency. The default overload still returns the blanket V3 refusal.
 */
class PreparedExpressionAdmissionCapabilityTest {

    private val contentIds = listOf("p0", "p1", "p2")
    private val keyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))

    private fun manifestPhotos() =
        contentIds.mapIndexed { index, _ -> ManifestPhoto(UUID.randomUUID(), index, 1000, 1000) }

    private fun capability(digest: ByteArray? = ByteArray(32) { 7 }) = object : CollageRenderCapability {
        override val enabled: Boolean = true
        override fun styleDigest(styleId: String, styleVersion: String): ByteArray? =
            if (styleId == "press" && styleVersion == "style-study-1") digest else null
        override fun fontResource(family: String, weight: Int, italic: Boolean): Int? = 1
        override val fontVersion: Int = GeneratorEditorialRows.FONT_VERSION
        override val paletteVersion: Int = GeneratorEditorialRows.PALETTE_VERSION
        override val maskSupported: Boolean = false
        override val artworkSupported: Boolean = false
    }

    private fun v3Expression() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = null,
            music = null,
        ),
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
        fontVersion = GeneratorEditorialRows.FONT_VERSION,
        paletteVersion = GeneratorEditorialRows.PALETTE_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        noteRegion = null,
        style = GeneratorExpression.StyleBinding("press", "style-study-1", ByteArray(32) { 7 }),
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        musicRegion = null,
    )

    private fun v2Expression(): GeneratorExpression.ResolvedExpression {
        val input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 7L,
            photos = contentIds.mapIndexed { index, id ->
                GeneratorExpression.PhotoRef(
                    contentId = id, ordinal = index, widthPx = 1000, heightPx = 1000,
                    contentHash = ('a' + index).toString().repeat(64),
                )
            },
            note = null,
            music = null,
        )
        val plan = GeneratorEditorialRows.plan(input)
        check(plan is GeneratorEditorialRows.PlanResult.Planned) { "expected Planned, got $plan" }
        return (plan as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun material(expression: GeneratorExpression.ResolvedExpression) = PreparedPresentationMaterial(
        statement = PublishStatement.newBuilder().build(),
        capsuleKeyset = keyset,
        manifest = ContentManifestContent(
            protocolVersion = 2,
            photos = manifestPhotos(),
            note = null,
            expression = ContentExpression("cand-1", "a".repeat(64), expression),
            trackSnapshot = null,
        ),
        deliveredCiphertexts = emptyList(),
    )

    @Test
    fun defaultIncomingAdmissionStillBlanketForV3() {
        val result = material(v3Expression()).expressionAdmission()
        assertTrue(result is ExpressionReceiverAdmission.Result.Unsupported, "got $result")
        assertEquals(
            "finished collage rendering is not supported",
            (result as ExpressionReceiverAdmission.Result.Unsupported).reason,
        )
    }

    @Test
    fun capabilityAwareIncomingAdmissionSupportsValidV3() {
        val result = material(v3Expression()).expressionAdmission(capability())
        assertTrue(result is ExpressionReceiverAdmission.Result.Supported, "got $result")
    }

    @Test
    fun capabilityAwareIncomingAdmissionRejectsWrongDigest() {
        val result = material(v3Expression()).expressionAdmission(capability(ByteArray(32) { 9 }))
        assertTrue(result is ExpressionReceiverAdmission.Result.Unsupported, "got $result")
        assertEquals(
            "expression style is not supported",
            (result as ExpressionReceiverAdmission.Result.Unsupported).reason,
        )
    }

    @Test
    fun v2IncomingAdmissionUnaffected() {
        assertTrue(
            material(v2Expression()).expressionAdmission() is ExpressionReceiverAdmission.Result.Supported,
        )
    }
}
