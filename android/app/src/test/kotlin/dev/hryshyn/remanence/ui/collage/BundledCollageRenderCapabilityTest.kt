package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.crypto.ContentExpression
import dev.hryshyn.remanence.core.crypto.ContentManifestContent
import dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission
import dev.hryshyn.remanence.core.crypto.ManifestPhoto
import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the app-layer [BundledCollageRenderCapability] to the authoritative
 * [StyleRegistry]/[FontRegistry]: the real adapter supports valid text-only
 * collages for all three bundled styles and rejects wrong digest/version,
 * unbundled faces, mask, and artwork.
 */
class BundledCollageRenderCapabilityTest {

    private val contentIds = listOf("p0", "p1", "p2")

    private fun expression(styleId: String) = GeneratorExpression.ResolvedExpression(
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
        fontVersion = FontRegistry.VERSION,
        paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        noteRegion = null,
        style = GeneratorExpression.StyleBinding(
            styleId = styleId,
            styleVersion = "style-study-1",
            digest = requireNotNull(StyleRegistry.digest(styleId)),
        ),
        noteType = GeneratorExpression.TextType("StudySans", 16, 400, false, 18),
        musicRegion = null,
    )

    private fun artwork() = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = "https://art.example.org/cover/ab12.webp",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/webp",
        width = 640,
        height = 640,
    )

    private fun contentOf(expression: GeneratorExpression.ResolvedExpression) = ContentManifestContent(
        protocolVersion = 2,
        photos = contentIds.mapIndexed { index, _ ->
            ManifestPhoto(blobId = UUID.randomUUID(), ordinal = index, width = 1000, height = 1000)
        },
        note = null,
        expression = ContentExpression("cand-1", "a".repeat(64), expression),
        trackSnapshot = null,
    )

    private fun reason(expression: GeneratorExpression.ResolvedExpression): String {
        val result = ExpressionReceiverAdmission.admit(contentOf(expression), BundledCollageRenderCapability)
        assertTrue("expected Unsupported, got $result", result is ExpressionReceiverAdmission.Result.Unsupported)
        return (result as ExpressionReceiverAdmission.Result.Unsupported).reason
    }

    @Test
    fun adapterPinsMatchTheRegistries() {
        assertTrue(BundledCollageRenderCapability.enabled)
        assertEquals(FontRegistry.VERSION, BundledCollageRenderCapability.fontVersion)
        assertEquals(CollageRenderer.PALETTE_REGISTRY_VERSION, BundledCollageRenderCapability.paletteVersion)
        assertEquals(false, BundledCollageRenderCapability.maskSupported)
        assertEquals(true, BundledCollageRenderCapability.artworkSupported)
        assertEquals(
            StyleRegistry.digest("press")?.toList(),
            BundledCollageRenderCapability.styleDigest("press", "style-study-1")?.toList(),
        )
        assertEquals(null, BundledCollageRenderCapability.styleDigest("signal", "style-study-1"))
        assertEquals(null, BundledCollageRenderCapability.styleDigest("press", "style-study-2"))
        assertEquals(FontRegistry.resourceFor("StudySans", 400, false), BundledCollageRenderCapability.fontResource("StudySans", 400, false))
        assertEquals(null, BundledCollageRenderCapability.fontResource("StudyMono", 700, false))
    }

    @Test
    fun supportsValidTextOnlyCollagesForAllThreeBundledStyles() {
        for (styleId in listOf("press", "blue-letter", "poster")) {
            val result = ExpressionReceiverAdmission.admit(contentOf(expression(styleId)), BundledCollageRenderCapability)
            assertTrue("$styleId must be Supported, got $result", result is ExpressionReceiverAdmission.Result.Supported)
        }
    }

    @Test
    fun thumbnailArtworkIsAdmittedForAllThreeBundledStyles() {
        for (styleId in listOf("press", "blue-letter", "poster")) {
            val thumbnail = expression(styleId).copy(
                musicRegion = GeneratorExpression.MusicRegion(
                    x = 0, y = 220, width = 360, height = 60,
                    mode = "thumbnail",
                    type = GeneratorExpression.TextType("StudySans", 14, 400, false, 16),
                    artwork = artwork(),
                ),
            )
            val result = ExpressionReceiverAdmission.admit(contentOf(thumbnail), BundledCollageRenderCapability)
            assertTrue("$styleId thumbnail must be Supported, got $result", result is ExpressionReceiverAdmission.Result.Supported)
        }
    }

    @Test
    fun wrongDigestIsRejected() {
        val tampered = expression("press").copy(
            style = GeneratorExpression.StyleBinding("press", "style-study-1", ByteArray(32) { 9 }),
        )
        assertEquals("expression style is not supported", reason(tampered))
    }

    @Test
    fun wrongStyleVersionIsRejected() {
        val tampered = expression("press").copy(
            style = GeneratorExpression.StyleBinding(
                "press", "style-study-2", requireNotNull(StyleRegistry.digest("press")),
            ),
        )
        assertEquals("expression style is not supported", reason(tampered))
    }

    @Test
    fun unbundledFaceIsRejected() {
        val tampered = expression("press").copy(
            noteType = GeneratorExpression.TextType("StudyMono", 16, 700, false, 18),
        )
        assertEquals("expression style is not supported", reason(tampered))
    }

    @Test
    fun fontOrPaletteVersionMismatchIsRejected() {
        assertEquals(
            "expression font or palette version is not supported",
            reason(expression("press").copy(fontVersion = 2)),
        )
        assertEquals(
            "expression font or palette version is not supported",
            reason(expression("press").copy(paletteVersion = 2)),
        )
    }

    @Test
    fun maskIsRejected() {
        val tampered = expression("press").copy(
            placements = contentIds.mapIndexed { index, id ->
                GeneratorExpression.Placement(
                    contentId = id, x = index * 120, y = 0, width = 120, height = 213,
                    crop = GeneratorExpression.CropWindow(0, 0, 10000, 10000),
                    maskId = "rect",
                    contentRect = null,
                )
            },
        )
        assertEquals("expression mask is not supported", reason(tampered))
    }

    @Test
    fun thumbnailArtworkIsAdmittedWhenArtworkIsSupported() {
        val thumbnail = expression("press").copy(
            musicRegion = GeneratorExpression.MusicRegion(
                x = 0, y = 220, width = 360, height = 60,
                mode = "thumbnail",
                type = GeneratorExpression.TextType("StudySans", 14, 400, false, 16),
                artwork = artwork(),
            ),
        )
        val result = ExpressionReceiverAdmission.admit(contentOf(thumbnail), BundledCollageRenderCapability)
        assertTrue("expected Supported, got $result", result is ExpressionReceiverAdmission.Result.Supported)
    }
}
