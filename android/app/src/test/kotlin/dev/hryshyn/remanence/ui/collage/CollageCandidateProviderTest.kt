package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.GeneratorBer1Provider
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorExpressionProjection
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grammar-5 / text-only V5 provider: fail-closed without authoritative crop
 * analysis, deterministic multi-style candidates, explicit crops, sealed
 * note/music, and existing GENEX03/BEXPR02 identity.
 */
class CollageCandidateProviderTest {

    private val contentIds = listOf("p0", "p1", "p2")

    private fun measurer() = CollageRenderer.TextMeasurer { request ->
        CollageRenderer.TextMeasurement(
            width = (request.text.length * 8).coerceIn(1, request.maxWidth),
            height = request.line.coerceIn(1, request.maxHeight),
            hasVisualOverflow = false,
        )
    }

    private fun cropSource(): CollageCropSource = CollageCropSource { photos ->
        photos.associate { photo ->
            photo.contentId to GeneratorExpression.CropWindow(photo.ordinal * 100, 0, 5000, 10000)
        }
    }

    private fun input(note: String? = null) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 1L,
        photos = contentIds.mapIndexed { index, id ->
            GeneratorExpression.PhotoRef(id, index, 1000, 1000, ('a' + index).toString().repeat(64))
        },
        note = note,
        music = null,
    )

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

    private fun provider(source: CollageCropSource = cropSource()) =
        CollageCandidateProvider(source, measurer())

    @Test
    fun unavailableWithoutAuthoritativeAnalysis() {
        val result = provider(CollageCropSource { null }).candidates(input(), null, null)
        assertTrue("expected Unavailable, got $result", result is CollageProviderResult.Unavailable)
        assertTrue((result as CollageProviderResult.Unavailable).reason.contains("not available"))
    }

    @Test
    fun buildsOneTextOnlyCandidatePerBundledStyleWithExplicitCrops() {
        val result = provider().candidates(input(), null, null)
        assertTrue("expected Candidates, got $result", result is CollageProviderResult.Candidates)
        val candidates = (result as CollageProviderResult.Candidates).candidates
        assertEquals(listOf("press", "blue-letter", "poster"), candidates.map { it.styleId })
        for (candidate in candidates) {
            val expression = candidate.expression
            assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V3, expression.expressionContractVersion)
            assertEquals(
                StyleRegistry.digest(candidate.styleId)?.toList(),
                expression.style?.digest?.toList(),
            )
            assertEquals("style-study-1", expression.style?.styleVersion)
            expression.placements.forEachIndexed { index, placement ->
                assertNull(placement.maskId)
                assertNull(placement.contentRect)
                assertEquals(
                    GeneratorExpression.CropWindow(index * 100, 0, 5000, 10000),
                    placement.crop,
                )
            }
            assertNull(expression.noteRegion)
            assertNull(expression.musicRegion)
            assertEquals(listOf(1, 2), expression.placements.groupingBy { it.y }.eachCount().values.sorted())
        }
    }

    @Test
    fun sealsNoteRegionAndTextModeMusicWhenPresent() {
        val result = provider().candidates(input(note = "dear mama"), "dear mama", snapshot())
        val candidate = (result as CollageProviderResult.Candidates).candidates.first()
        val expression = candidate.expression
        assertEquals("dear mama", expression.input.note)
        val noteRegion = expression.noteRegion
        assertTrue(noteRegion != null)
        val noteType = expression.noteType!!
        assertEquals(ceil(noteType.size * 1.12).toInt(), noteType.line)
        val music = expression.musicRegion
        assertTrue(music != null)
        assertEquals("text", music!!.mode)
        assertNull(music.artwork)
        assertEquals(ceil(music.type.size * 1.12).toInt(), music.type.line)
    }

    @Test
    fun candidateIdentityUsesGenex03AndBexpr02Exactly() {
        val candidate = (provider().candidates(input(), null, null) as CollageProviderResult.Candidates)
            .candidates.first()
        val expression = candidate.expression
        assertEquals(GeneratorBer1Provider.candidateIdFor(expression), candidate.candidateId)
        assertTrue(candidate.candidateId.startsWith("ber1-"))

        val capsuleId = CapsuleId(UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd2001"))
        val projected = CapsulePhotoIdentity.projectedHash(
            expression,
            candidate.candidateId,
            capsuleId,
            expression.input.photos.map { it.contentId },
        )
        assertEquals(64, projected.length)
        assertEquals(
            projected,
            CapsulePhotoIdentity.projectedHash(
                expression,
                candidate.candidateId,
                capsuleId,
                expression.input.photos.map { it.contentId },
            ),
        )

        val blobs = expression.input.photos.associate { it.contentId to ByteArray(16) { 1 } }
        val bytes = GeneratorExpressionProjection.canonicalBytes(expression, candidate.candidateId, blobs)
        assertEquals("BEXPR02", String(bytes.copyOfRange(0, 7), StandardCharsets.US_ASCII))
    }

    @Test
    fun candidateSetIsDeterministicAcrossRuns() {
        val first = (provider().candidates(input(note = "hi"), "hi", snapshot()) as CollageProviderResult.Candidates).candidates
        val second = (provider().candidates(input(note = "hi"), "hi", snapshot()) as CollageProviderResult.Candidates).candidates
        assertEquals(first.map { it.candidateId }, second.map { it.candidateId })
        assertEquals(first.map { it.expression }, second.map { it.expression })
    }
}
