package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.core.model.GeneratorExpression.invalidatedBy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * G1 contract tests: provider-independent, pure deterministic, plain JVM.
 *
 * The expected canonical bytes below are built BY HAND in this file
 * (independent writer), never via [GeneratorExpression] internals, so the
 * golden pins the format rather than echoing the implementation.
 */
class GeneratorExpressionTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val hashC = "c".repeat(64)
    private val hashD = "d".repeat(64)

    private fun photo(id: String, ordinal: Int, w: Int = 1080, h: Int = 1920, hash: String = hashA) =
        GeneratorExpression.PhotoRef(id, ordinal, w, h, hash)

    private fun input(
        photos: List<GeneratorExpression.PhotoRef> = listOf(
            photo("p1", 0, 1080, 1920, hashA),
            photo("p2", 1, 1920, 1080, hashB),
            photo("p3", 2, 1080, 1080, hashC),
        ),
        note: String? = "hello",
        music: GeneratorExpression.MusicRef? = null,
        epoch: Long = 7L,
    ) = GeneratorExpression.GeneratorInput("owner-1", epoch, photos, note, music)

    private fun expression(
        input: GeneratorExpression.GeneratorInput = input(),
    ) = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = input,
        placements = input.photos.mapIndexed { _, photo ->
            GeneratorExpression.Placement(photo.contentId, 0, 0, 120, 213, null, null)
        },
        noteTreatment = "regular-region",
        diagnostics = listOf("ok"),
        fontVersion = 2,
        paletteVersion = 4,
    )

    // ---- independent golden writer (hand-built, mirrors the documented format) ----

    private class Golden {
        private val parts = mutableListOf<ByteArray>()
        fun ascii(s: String) {
            parts += s.toByteArray(Charsets.US_ASCII)
        }
        fun byte(v: Int) {
            parts += byteArrayOf(v.toByte())
        }
        fun int(v: Int) {
            parts += ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
        }
        fun long(v: Long) {
            parts += ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()
        }
        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            int(b.size)
            parts += b
        }
        fun bytes(): ByteArray {
            val total = parts.sumOf { it.size }
            val buf = ByteBuffer.allocate(total)
            parts.forEach(buf::put)
            return buf.array()
        }
    }

    private fun goldenInputBytes(): ByteArray {
        val g = Golden()
        g.ascii("GENEX01")
        g.int(1)
        g.str("owner-1")
        g.long(7L)
        g.int(3)
        g.str("p1"); g.int(0); g.int(1080); g.int(1920); g.str(hashA)
        g.str("p2"); g.int(1); g.int(1920); g.int(1080); g.str(hashB)
        g.str("p3"); g.int(2); g.int(1080); g.int(1080); g.str(hashC)
        g.byte(1); g.str("hello")
        g.byte(0)
        return g.bytes()
    }

    private fun sha(hex: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(hex).joinToString("") { "%02x".format(it) }

    @Test
    fun canonicalBytesMatchIndependentGolden() {
        assertTrue(goldenInputBytes().contentEquals(GeneratorExpression.canonicalBytes(input())))
    }

    @Test
    fun canonicalHashIsSha256OfGolden() {
        assertEquals(sha(goldenInputBytes()), GeneratorExpression.canonicalHash(input()))
        assertEquals(64, GeneratorExpression.canonicalHash(input()).length)
    }

    @Test
    fun determinismAcrossConstructions() {
        assertEquals(GeneratorExpression.canonicalHash(input()), GeneratorExpression.canonicalHash(input()))
        assertTrue(
            GeneratorExpression.canonicalBytes(expression()).contentEquals(
                GeneratorExpression.canonicalBytes(expression()),
            ),
        )
    }

    @Test
    fun authoredOrderParticipatesInIdentity() {
        val swapped = input(
            photos = listOf(
                photo("p1", 0, 1080, 1920, hashA),
                photo("p3", 1, 1080, 1080, hashC),
                photo("p2", 2, 1920, 1080, hashB),
            ),
        )
        assertNotEquals(GeneratorExpression.canonicalHash(input()), GeneratorExpression.canonicalHash(swapped))
    }

    @Test
    fun absentNoteAndEmptyNoteAreDistinct() {
        val absent = GeneratorExpression.canonicalHash(input(note = null))
        val empty = GeneratorExpression.canonicalHash(input(note = ""))
        val text = GeneratorExpression.canonicalHash(input(note = "hello"))
        assertNotEquals(absent, empty)
        assertNotEquals(empty, text)
        assertNotEquals(absent, text)
    }

    @Test
    fun mixedAspectsAndNoteAndMusicParticipateInIdentity() {
        val base = GeneratorExpression.canonicalHash(input())
        assertNotEquals(base, GeneratorExpression.canonicalHash(input(note = "other")))
        assertNotEquals(
            base,
            GeneratorExpression.canonicalHash(
                input(music = GeneratorExpression.MusicRef("track-1", null)),
            ),
        )
        assertNotEquals(
            base,
            GeneratorExpression.canonicalHash(
                input(
                    photos = listOf(
                        photo("p1", 0, 1080, 1920, hashA),
                        photo("p2", 1, 1080, 1080, hashB),
                        photo("p3", 2, 1080, 1080, hashC),
                    ),
                ),
            ),
        )
    }

    @Test
    fun noteBoundaryIs1000Utf8Bytes() {
        val ok = input(note = "я".repeat(500))
        assertEquals(1000, "я".repeat(500).toByteArray(Charsets.UTF_8).size)
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validate(ok))
        val over = input(note = "я".repeat(500) + "!")
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validate(over))
    }

    @Test
    fun structuralBoundariesReject() {
        val two = input(photos = listOf(photo("p1", 0, hash = hashA), photo("p2", 1, hash = hashB)))
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validate(two))
        val six = input(
            photos = (1..6).map { photo("p$it", it - 1, hash = "$it".repeat(64).take(64)) },
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validate(six))
        val badOrdinal = input(
            photos = listOf(
                photo("p1", 0, hash = hashA),
                photo("p2", 0, hash = hashB),
                photo("p3", 2, hash = hashC),
            ),
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validate(badOrdinal))
        val badDims = input(
            photos = listOf(
                photo("p1", 0, 0, 1920, hashA),
                photo("p2", 1, 1920, 1080, hashB),
                photo("p3", 2, 1080, 1080, hashC),
            ),
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validate(badDims))
        val badHash = input(
            photos = listOf(
                photo("p1", 0, hash = "NOTHEX"),
                photo("p2", 1, hash = hashB),
                photo("p3", 2, hash = hashC),
            ),
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validate(badHash))
        val dupId = input(
            photos = listOf(
                photo("p1", 0, hash = hashA),
                photo("p1", 1, hash = hashB),
                photo("p3", 2, hash = hashC),
            ),
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validate(dupId))
    }

    @Test
    fun resolvedRequiresOrderedFullCoverageWithinCanvas() {
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(expression()))
        val shuffled = expression().copy(
            placements = expression().placements.reversed(),
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validateResolved(shuffled))
        val overflowing = expression().copy(
            placements = listOf(
                GeneratorExpression.Placement("p1", 300, 600, 120, 213, null, null),
                GeneratorExpression.Placement("p2", 0, 0, 120, 213, null, null),
                GeneratorExpression.Placement("p3", 0, 0, 120, 213, null, null),
            ),
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validateResolved(overflowing))
        val badCrop = expression().copy(
            placements = listOf(
                GeneratorExpression.Placement(
                    "p1", 0, 0, 120, 213,
                    GeneratorExpression.CropWindow(900, 900, 200, 200), null,
                ),
                GeneratorExpression.Placement("p2", 0, 0, 120, 213, null, null),
                GeneratorExpression.Placement("p3", 0, 0, 120, 213, null, null),
            ),
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validateResolved(badCrop))
    }

    @Test
    fun invalidationBoundaryMatrix() {
        val e = expression()
        assertTrue(e.invalidatedBy(GeneratorExpression.ExpressionChange.PhotosChanged))
        assertTrue(e.invalidatedBy(GeneratorExpression.ExpressionChange.PhotoMetadataChanged))
        assertTrue(e.invalidatedBy(GeneratorExpression.ExpressionChange.NoteChanged))
        assertTrue(e.invalidatedBy(GeneratorExpression.ExpressionChange.MusicChanged))
        assertTrue(e.invalidatedBy(GeneratorExpression.ExpressionChange.VersionsChanged))
        assertTrue(e.invalidatedBy(GeneratorExpression.ExpressionChange.OwnerOrEpochChanged))
        assertTrue(!e.invalidatedBy(GeneratorExpression.ExpressionChange.ShellOnlyChange))
    }

    @Test
    fun lateEpochCallbacksRejected() {
        assertTrue(GeneratorExpression.rejectsLateCallback(frozenAtEpoch = 9L, callerEpoch = 8L))
        assertTrue(!GeneratorExpression.rejectsLateCallback(frozenAtEpoch = 9L, callerEpoch = 9L))
        assertTrue(!GeneratorExpression.rejectsLateCallback(frozenAtEpoch = 9L, callerEpoch = 10L))
    }

    @Test
    fun freezeBindsExpressionHashRevisionAndEpoch() {
        val e = expression()
        val frozen = GeneratorExpression.freeze(e, contentRevision = 3L, frozenAtEpoch = 9L)
        assertEquals(GeneratorExpression.canonicalHash(e), frozen.expressionHash)
        assertEquals(3L, frozen.contentRevision)
        assertEquals(9L, frozen.frozenAtEpoch)
        val resettled = e.copy(diagnostics = listOf("ok", "recheck"))
        assertNotEquals(frozen.expressionHash, GeneratorExpression.canonicalHash(resettled))
    }

    @Test
    fun expressionHashDiffersFromInputHash() {
        assertNotEquals(
            GeneratorExpression.canonicalHash(input()),
            GeneratorExpression.canonicalHash(expression()),
        )
    }
}
