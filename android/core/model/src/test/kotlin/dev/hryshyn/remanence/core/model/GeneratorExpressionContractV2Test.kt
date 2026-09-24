package dev.hryshyn.remanence.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stage1-B G1 schema tests: version-dispatched expression encoding.
 *
 * The v1 golden below is hand-built (independent writer, never via
 * [GeneratorExpression] internals), so it pins the legacy bytes rather than
 * echoing the implementation. v2 cases use real model hashing/validation
 * only. Plain JVM, following [GeneratorExpressionTest] conventions.
 */
class GeneratorExpressionContractV2Test {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val hashC = "c".repeat(64)

    private fun photo(id: String, ordinal: Int, w: Int, h: Int, hash: String) =
        GeneratorExpression.PhotoRef(id, ordinal, w, h, hash)

    private fun input(note: String? = "hello") = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = listOf(
            photo("p1", 0, 1080, 1920, hashA),
            photo("p2", 1, 1920, 1080, hashB),
            photo("p3", 2, 1080, 1080, hashC),
        ),
        note = note,
        music = null,
    )

    /** Legacy constructor shape: no new fields supplied. */
    private fun expressionV1(
        input: GeneratorExpression.GeneratorInput = input(),
    ) = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = input,
        placements = input.photos.map { placementV1(it.contentId) },
        noteTreatment = "regular-region",
        diagnostics = listOf("ok"),
        fontVersion = 2,
        paletteVersion = 4,
    )

    private fun placementV1(id: String) =
        GeneratorExpression.Placement(id, 0, 0, 120, 213, null, null)

    private fun expressionV2(
        input: GeneratorExpression.GeneratorInput = input(),
        region: GeneratorExpression.NoteRegion? =
            GeneratorExpression.NoteRegion(16, 512, 328, 128),
    ) = expressionV1(input).copy(
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2,
        noteRegion = region,
        placements = input.photos.map {
            placementV1(it.contentId).copy(
                contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213),
            )
        },
    )

    // ---- independent golden writer (hand-built v1 layout) ----

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

    private fun goldenV1ExpressionBytes(): ByteArray {
        val g = Golden()
        g.ascii("GENEX01")
        g.int(1)
        g.int(1); g.str("g-hold"); g.int(3); g.str("b-rows")
        g.str("owner-1"); g.long(7L); g.int(3)
        g.str("p1"); g.int(0); g.int(1080); g.int(1920); g.str(hashA)
        g.str("p2"); g.int(1); g.int(1920); g.int(1080); g.str(hashB)
        g.str("p3"); g.int(2); g.int(1080); g.int(1080); g.str(hashC)
        g.byte(1); g.str("hello")
        g.byte(0)
        g.int(3)
        for (id in listOf("p1", "p2", "p3")) {
            g.str(id); g.int(0); g.int(0); g.int(120); g.int(213); g.byte(0); g.byte(0)
        }
        g.str("regular-region"); g.int(1); g.str("ok")
        g.int(2); g.int(4)
        return g.bytes()
    }

    @Test
    fun v1BytesMatchIndependentGolden() {
        assertTrue(
            goldenV1ExpressionBytes().contentEquals(
                GeneratorExpression.canonicalBytes(expressionV1()),
            ),
        )
    }

    @Test
    fun v1DefaultsPreservedByOldConstructors() {
        val expression = expressionV1()
        assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V1, expression.expressionContractVersion)
        assertEquals(null, expression.noteRegion)
        assertTrue(expression.placements.all { it.contentRect == null })
        assertEquals(1, GeneratorExpression.CONTRACT_VERSION)
        assertEquals(64, GeneratorExpression.canonicalHash(expression).length)
    }

    @Test
    fun v2FieldsChangeHashAndStayDeterministic() {
        val v1 = expressionV1()
        val v2 = expressionV2()
        assertNotEquals(GeneratorExpression.canonicalHash(v1), GeneratorExpression.canonicalHash(v2))
        assertTrue(
            GeneratorExpression.canonicalBytes(v2).contentEquals(
                GeneratorExpression.canonicalBytes(expressionV2()),
            ),
        )
        assertEquals(
            GeneratorExpression.canonicalHash(v2),
            GeneratorExpression.canonicalHash(expressionV2()),
        )
    }

    @Test
    fun v2VersionMarkerAtDocumentedOffset() {
        val bytes = GeneratorExpression.canonicalBytes(expressionV2())
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(7).also { buf.get(it) }
        assertEquals("GENEX02", magic.toString(Charsets.US_ASCII))
        assertEquals(1, buf.int)
        assertEquals(2, buf.int)
    }

    @Test
    fun v1MagicUnchanged() {
        val bytes = GeneratorExpression.canonicalBytes(expressionV1())
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(7).also { buf.get(it) }
        assertEquals("GENEX01", magic.toString(Charsets.US_ASCII))
    }

    @Test
    fun v2ValidatesWithAndWithoutNoteBand() {
        assertIs<GeneratorExpression.InputValidation.Valid>(
            GeneratorExpression.validateResolved(expressionV2()),
        )
        val absentNote = expressionV2(input(note = null), region = null)
        assertIs<GeneratorExpression.InputValidation.Valid>(
            GeneratorExpression.validateResolved(absentNote),
        )
        val emptyNote = expressionV2(input(note = ""), region = null)
        assertIs<GeneratorExpression.InputValidation.Valid>(
            GeneratorExpression.validateResolved(emptyNote),
        )
    }

    @Test
    fun v2InvalidRegionsFailClosed() {
        val base = expressionV2()
        // Missing contentRect on one placement.
        val missingRect = base.copy(
            placements = base.placements.mapIndexed { i, p ->
                if (i == 0) p.copy(contentRect = null) else p
            },
        )
        // Rect outside its placement cell.
        val outside = base.copy(
            placements = base.placements.mapIndexed { i, p ->
                if (i == 0) p.copy(contentRect = GeneratorExpression.ContentRect(100, 200, 120, 213))
                else p
            },
        )
        // Degenerate rect.
        val flat = base.copy(
            placements = base.placements.mapIndexed { i, p ->
                if (i == 0) p.copy(contentRect = GeneratorExpression.ContentRect(0, 0, 0, 10))
                else p
            },
        )
        // Band present but note absent / empty.
        val bandWithoutNote = expressionV2(input(note = null))
        val bandWithEmptyNote = expressionV2(input(note = ""))
        // Non-empty note without band.
        val noteWithoutBand = expressionV2(region = null)
        // Band outside the canvas.
        val bandOffCanvas = expressionV2(
            region = GeneratorExpression.NoteRegion(300, 600, 100, 100),
        )
        for (bad in listOf(missingRect, outside, flat, bandWithoutNote, bandWithEmptyNote, noteWithoutBand, bandOffCanvas)) {
            assertIs<GeneratorExpression.InputValidation.Invalid>(
                GeneratorExpression.validateResolved(bad),
                "expected Invalid for $bad",
            )
        }
    }

    @Test
    fun unknownVersionsThrowOnHashAndFreeze() {
        for (version in listOf(0, 3, -1)) {
            val bad = expressionV1().copy(expressionContractVersion = version)
            assertIs<GeneratorExpression.InputValidation.Invalid>(
                GeneratorExpression.validateResolved(bad),
            )
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(bad) }
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalHash(bad) }
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.freeze(bad, 0L, 7L) }
        }
    }

    @Test
    fun v1ForbiddenFieldsCannotAliasLegalEncoding() {
        val withRegion = expressionV1().copy(
            noteRegion = GeneratorExpression.NoteRegion(16, 512, 328, 128),
        )
        val withRect = expressionV1().copy(
            placements = expressionV1().placements.map {
                it.copy(contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213))
            },
        )
        for (bad in listOf(withRegion, withRect)) {
            // No hash is produced at all: aliasing a legal v1 encoding is
            // impossible, not merely unlikely.
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(bad) }
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalHash(bad) }
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.freeze(bad, 0L, 7L) }
        }
    }

    @Test
    fun overflowingBoundsAreInvalid() {
        // Each sum overflows Int and would wrap past the canvas check under
        // 32-bit arithmetic; Long arithmetic must still reject.
        val placementOverflow = expressionV1().copy(
            placements = listOf(
                GeneratorExpression.Placement("p1", Int.MAX_VALUE - 5, 0, 20, 10, null, null),
                placementV1("p2"),
                placementV1("p3"),
            ),
        )
        val cropOverflow = expressionV1().copy(
            placements = listOf(
                GeneratorExpression.Placement(
                    "p1", 0, 0, 120, 213,
                    GeneratorExpression.CropWindow(100, 0, Int.MAX_VALUE, 10),
                    null,
                ),
                placementV1("p2"),
                placementV1("p3"),
            ),
        )
        val baseV2 = expressionV2()
        val rectOverflow = baseV2.copy(
            placements = baseV2.placements.mapIndexed { i, p ->
                if (i == 0) p.copy(contentRect = GeneratorExpression.ContentRect(100, 0, Int.MAX_VALUE, 10))
                else p
            },
        )
        val regionOverflow = expressionV2(
            region = GeneratorExpression.NoteRegion(0, 0, Int.MAX_VALUE, 10),
        )
        for (bad in listOf(placementOverflow, cropOverflow, rectOverflow, regionOverflow)) {
            assertIs<GeneratorExpression.InputValidation.Invalid>(
                GeneratorExpression.validateResolved(bad),
            )
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(bad) }
        }
    }

    @Test
    fun v1RejectsUnexpectedNewFields() {
        val withRegion = expressionV1().copy(
            noteRegion = GeneratorExpression.NoteRegion(16, 512, 328, 128),
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(
            GeneratorExpression.validateResolved(withRegion),
        )
        val withRect = expressionV1().copy(
            placements = expressionV1().placements.map {
                it.copy(contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(
            GeneratorExpression.validateResolved(withRect),
        )
    }
}
