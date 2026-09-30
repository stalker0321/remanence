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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun cropSource(): CollageCropSource = CollageCropSource { requests ->
        requests.mapIndexed { index, request ->
            request.contentId to GeneratorExpression.CropWindow(index * 100, 0, 5000, 10000)
        }.toMap()
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
        val result = runBlocking { provider(CollageCropSource { null }).candidates(input(), null, null) }
        assertTrue("expected Unavailable, got $result", result is CollageProviderResult.Unavailable)
        assertTrue((result as CollageProviderResult.Unavailable).reason.contains("not available"))
    }

    @Test
    fun buildsOneTextOnlyCandidatePerBundledStyleWithExplicitCrops() {
        val result = runBlocking { provider().candidates(input(), null, null) }
        assertTrue("expected Candidates, got $result", result is CollageProviderResult.Candidates)
        val candidates = (result as CollageProviderResult.Candidates).candidates
        assertEquals(listOf("press", "blue-letter", "poster"), candidates.map { it.styleId })
        for (candidate in candidates) {
            val expression = candidate.expression
            assertEquals("editorial-1+2-equal-1", candidate.branchId)
            assertEquals("editorial-1+2-equal-1", expression.branchId)
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
        // Exact grammar-5 cells for the 3-photo equal branch (no closing bands).
        assertEquals(
            listOf(
                GeneratorExpression.Placement(
                    "p0", 10, 101, 340, 248,
                    GeneratorExpression.CropWindow(0, 0, 5000, 10000), null, null,
                ),
                GeneratorExpression.Placement(
                    "p1", 10, 354, 167, 248,
                    GeneratorExpression.CropWindow(100, 0, 5000, 10000), null, null,
                ),
                GeneratorExpression.Placement(
                    "p2", 182, 354, 167, 248,
                    GeneratorExpression.CropWindow(200, 0, 5000, 10000), null, null,
                ),
            ),
            candidates.first().expression.placements,
        )
    }

    @Test
    fun sealsNoteRegionAndTextModeMusicWhenPresent() {
        val result = runBlocking { provider().candidates(input(note = "dear mama"), "dear mama", snapshot()) }
        val candidate = (result as CollageProviderResult.Candidates).candidates.first()
        val expression = candidate.expression
        assertEquals("editorial-1+2-equal-1", candidate.branchId)
        assertEquals("dear mama", expression.input.note)
        // Exact sealed bands: music ends 8px above the photo field and the note
        // sits 10px above the music (press note size 22 -> line 25).
        assertEquals(GeneratorExpression.NoteRegion(16, 578, 328, 25), expression.noteRegion)
        assertEquals(16, expression.musicRegion?.x)
        assertEquals(520, expression.musicRegion?.y)
        assertEquals(328, expression.musicRegion?.width)
        assertEquals(48, expression.musicRegion?.height)
        val noteType = expression.noteType!!
        assertEquals(ceil(noteType.size * 1.12).toInt(), noteType.line)
        val music = expression.musicRegion!!
        assertEquals("text", music.mode)
        assertNull(music.artwork)
        assertEquals(ceil(music.type.size * 1.12).toInt(), music.type.line)
    }

    private fun artworkRef(
        url: String = "https://archive.org/download/mbid-275a9c54-8862-46dd-bcd3-1658a39c310b/cover_thumb500.jpg",
    ) = GeneratorExpression.ArtworkRef(
        provider = "coverartarchive",
        url = url,
        sha256 = ByteArray(32) { index -> index.toByte() },
        mimeType = "image/jpeg",
        width = 500,
        height = 500,
    )

    /**
     * T062 seal: a descriptor flips the music region to `thumbnail` and is
     * written verbatim; its absence keeps the exact pre-T062 text seal.
     * Reaching [CollageProviderResult.Candidates] at all proves
     * `validateResolved` accepted the expression, i.e. rules A1 and A3–A7 hold
     * for the sealed descriptor (a violation would surface as `Rejected`).
     */
    @Test
    fun descriptorSealsThumbnailModeAndItsAbsenceSealsText() {
        val withDescriptor = runBlocking {
            provider().candidates(input(note = "dear mama"), "dear mama", snapshot(), artworkRef())
        }
        val sealed = (withDescriptor as CollageProviderResult.Candidates).candidates.first()
        val music = sealed.expression.musicRegion!!
        assertEquals("thumbnail", music.mode)
        assertEquals("coverartarchive", music.artwork?.provider)
        assertEquals(
            "https://archive.org/download/mbid-275a9c54-8862-46dd-bcd3-1658a39c310b/cover_thumb500.jpg",
            music.artwork?.url,
        )
        assertEquals("image/jpeg", music.artwork?.mimeType)
        assertEquals(500, music.artwork?.width)
        assertEquals(500, music.artwork?.height)
        assertTrue(music.artwork!!.sha256.contentEquals(ByteArray(32) { it.toByte() }))

        val withoutDescriptor = runBlocking {
            provider().candidates(input(note = "dear mama"), "dear mama", snapshot())
        }
        val plain = (withoutDescriptor as CollageProviderResult.Candidates).candidates.first()
        assertEquals("text", plain.expression.musicRegion?.mode)
        assertNull(plain.expression.musicRegion?.artwork)
    }

    @Test
    fun descriptorIsPartOfCandidateIdentityAndIsDeterministic() {
        val none = (runBlocking {
            provider().candidates(input(note = "dear mama"), "dear mama", snapshot())
        } as CollageProviderResult.Candidates).candidates.first()
        val first = (runBlocking {
            provider().candidates(input(note = "dear mama"), "dear mama", snapshot(), artworkRef())
        } as CollageProviderResult.Candidates).candidates.first()
        val second = (runBlocking {
            provider().candidates(input(note = "dear mama"), "dear mama", snapshot(), artworkRef())
        } as CollageProviderResult.Candidates).candidates.first()
        val otherUrl = (runBlocking {
            provider().candidates(
                input(note = "dear mama"),
                "dear mama",
                snapshot(),
                artworkRef(url = "https://archive.org/download/other/file_thumb500.jpg"),
            )
        } as CollageProviderResult.Candidates).candidates.first()

        assertNotEquals(none.candidateId, first.candidateId)
        assertEquals(first.candidateId, second.candidateId)
        assertNotEquals(first.candidateId, otherUrl.candidateId)
        // BEXPR02 projection sees the descriptor too (contract S2): identity
        // must move with it, and must not move for an identical descriptor.
        assertNotEquals(projectedHash(first), projectedHash(otherUrl))
        assertEquals(projectedHash(first), projectedHash(second))
    }

    private fun projectedHash(candidate: CollageCandidate): String =
        CapsulePhotoIdentity.projectedHash(
            candidate.expression,
            candidate.candidateId,
            CapsuleId(UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd2001")),
            candidate.expression.input.photos.map { it.contentId },
        )

    @Test
    fun candidateIdentityUsesGenex03AndBexpr02Exactly() {
        val candidate = runBlocking { provider().candidates(input(), null, null) }.let {
            (it as CollageProviderResult.Candidates).candidates.first()
        }
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
    fun requestsCropsPerPlacementSlotAndNeverMixesCandidates() {
        val requestsSeen = mutableListOf<List<CollageCropRequest>>()
        val source = CollageCropSource { requests ->
            requestsSeen += requests
            requests.mapIndexed { index, request ->
                request.contentId to GeneratorExpression.CropWindow(index * 100, 0, 5000, 10000)
            }.toMap()
        }
        val result = runBlocking { provider(source).candidates(input(note = "hi"), "hi", snapshot()) }
        assertTrue("expected Candidates, got $result", result is CollageProviderResult.Candidates)

        // One aspect-aware request per style candidate.
        assertEquals(3, requestsSeen.size)
        requestsSeen.forEach { requests ->
            assertEquals(contentIds, requests.map { it.contentId })
            assertTrue("slot geometry must be positive", requests.all { it.slotWidth > 0 && it.slotHeight > 0 })
            assertEquals(CollageCropRequest.DEFAULT_SLOT_ZOOMS, requests.first().zooms)
        }
        // The per-style note type changes the grid, so the slots (and therefore
        // the requested crops) must not be a single reused per-photo map.
        val slotGeometry = requestsSeen.map { requests -> requests.map { it.slotWidth to it.slotHeight } }
        assertTrue("different candidate slots must be requested separately", slotGeometry.toSet().size > 1)
    }

    @Test
    fun candidateSetIsDeterministicAcrossRuns() {
        val first = runBlocking {
            (provider().candidates(input(note = "hi"), "hi", snapshot()) as CollageProviderResult.Candidates).candidates
        }
        val second = runBlocking {
            (provider().candidates(input(note = "hi"), "hi", snapshot()) as CollageProviderResult.Candidates).candidates
        }
        assertEquals(first.map { it.candidateId }, second.map { it.candidateId })
        assertEquals(first.map { it.expression }, second.map { it.expression })
    }

    @Test
    fun musicTextFitOmissionIsPerStyleAndFailsClosedWhenNoStyleFits() {
        // A two-line music block that overflows the frozen region for the
        // tallest bundled music type (poster, size 24) but not for
        // press/blue-letter: poster is omitted per style, the others stay.
        val posterOverflow = CollageRenderer.TextMeasurer { request ->
            val overflow = request.text.contains("\n") && request.size >= 24
            CollageRenderer.TextMeasurement(
                width = request.text.length * 8,
                height = if (overflow) request.maxHeight + 1 else request.line,
                hasVisualOverflow = overflow,
            )
        }
        val partial = runBlocking {
            CollageCandidateProvider(cropSource(), posterOverflow).candidates(input(), null, snapshot())
        }
        assertTrue("expected Candidates, got $partial", partial is CollageProviderResult.Candidates)
        assertEquals(
            listOf("press", "blue-letter"),
            (partial as CollageProviderResult.Candidates).candidates.map { it.styleId },
        )

        // When every style's music text overflows, the result stays fail-closed
        // rather than returning an empty candidate set.
        val everyStyleOverflows = CollageRenderer.TextMeasurer { request ->
            val overflow = request.text.contains("\n")
            CollageRenderer.TextMeasurement(
                width = request.text.length * 8,
                height = if (overflow) request.maxHeight + 1 else request.line,
                hasVisualOverflow = overflow,
            )
        }
        val none = runBlocking {
            CollageCandidateProvider(cropSource(), everyStyleOverflows).candidates(input(), null, snapshot())
        }
        assertTrue("expected Rejected, got $none", none is CollageProviderResult.Rejected)
    }

    private class DiagnosticCropSource(
        private val crops: Map<String, GeneratorExpression.CropWindow>,
        override val lastCropRejections: List<CollageSlotRejection>,
    ) : CollageCropSource, CollageCropDiagnostics {
        override suspend fun cropsFor(requests: List<CollageCropRequest>) = crops
    }

    @Test
    fun missingCropRejectionNamesPreciseStylePhotoSlotAndCause() = runBlocking {
        // p1's crop is omitted (fail-closed) and the source reports the exact
        // frozen cause for that slot.
        val source = DiagnosticCropSource(
            crops = mapOf(
                "p0" to GeneratorExpression.CropWindow(0, 0, 5000, 10000),
                "p2" to GeneratorExpression.CropWindow(9000, 0, 1000, 10000),
            ),
            lastCropRejections = listOf(
                CollageSlotRejection("p1", 167, 248, "rect", CropRejectReason.PROTECTION_FIELD_LOW),
            ),
        )
        val result = provider(source).candidates(input(), null, null)
        assertTrue("expected Rejected, got $result", result is CollageProviderResult.Rejected)
        val rejected = result as CollageProviderResult.Rejected
        // The user-facing copy is unchanged.
        assertEquals("the note, music, or crop does not fit the grammar-5 grid", rejected.reason)
        val rejection = requireNotNull(rejected.rejection)
        assertEquals("press", rejection.styleId)
        assertEquals("p1", rejection.contentId)
        assertEquals(1, rejection.slotIndex)
        assertEquals(167, rejection.slotWidth)
        assertEquals(248, rejection.slotHeight)
        assertEquals(CropRejectReason.PROTECTION_FIELD_LOW, rejection.cause)
        assertTrue(rejection.safeSummary().contains("style=press"))
        assertTrue(rejection.safeSummary().contains("cause=PROTECTION_FIELD_LOW"))
        assertTrue(
            rejection.safeSummary().contains(
                "frozen=\"Combined protection field loses more than its permitted mass\"",
            ),
        )
    }

    @Test
    fun missingCropWithoutDiagnosticsFallsBackToNoCandidate() = runBlocking {
        val source = CollageCropSource { requests ->
            requests
                .filter { it.contentId != "p1" }
                .associate { it.contentId to GeneratorExpression.CropWindow(0, 0, 5000, 10000) }
        }
        val result = provider(source).candidates(input(), null, null)
        val rejected = result as CollageProviderResult.Rejected
        val rejection = requireNotNull(rejected.rejection)
        assertEquals("p1", rejection.contentId)
        assertEquals(1, rejection.slotIndex)
        assertEquals(CropRejectReason.NO_CROP_CANDIDATE, rejection.cause)
    }

    @Test
    fun mismatchedDiagnosticSlotFallsBackToNoCandidate() = runBlocking {
        // The source reports a reason for a different slot, so the provider
        // must not mis-attribute it.
        val source = DiagnosticCropSource(
            crops = mapOf(
                "p0" to GeneratorExpression.CropWindow(0, 0, 5000, 10000),
                "p2" to GeneratorExpression.CropWindow(9000, 0, 1000, 10000),
            ),
            lastCropRejections = listOf(
                CollageSlotRejection("p1", 340, 248, "rect", CropRejectReason.FACE_CROSSES_CROP_OR_MASK),
            ),
        )
        val result = provider(source).candidates(input(), null, null)
        val rejection = requireNotNull((result as CollageProviderResult.Rejected).rejection)
        assertEquals(CropRejectReason.NO_CROP_CANDIDATE, rejection.cause)
    }
}
