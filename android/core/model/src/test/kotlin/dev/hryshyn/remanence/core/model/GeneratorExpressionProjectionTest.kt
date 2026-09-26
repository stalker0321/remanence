package dev.hryshyn.remanence.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR-018 BEXPR01 projection hash tests (host-side, model only). */
class GeneratorExpressionProjectionTest {

    private fun photo(id: String, ordinal: Int, hash: String) =
        GeneratorExpression.PhotoRef(id, ordinal, 1000, 1000, hash)

    private fun input(owner: String = "owner-1", epoch: Long = 7L) = GeneratorExpression.GeneratorInput(
        ownerId = owner,
        epoch = epoch,
        photos = listOf(
            photo("p1", 0, "a".repeat(64)),
            photo("p2", 1, "b".repeat(64)),
            photo("p3", 2, "c".repeat(64)),
        ),
        note = null,
        music = null,
    )

    private fun expression(input: GeneratorExpression.GeneratorInput): GeneratorExpression.ResolvedExpression {
        val result = GeneratorEditorialRows.plan(input)
        assertTrue(result is GeneratorEditorialRows.PlanResult.Planned, "expected Planned, got $result")
        return (result as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun blobs(expression: GeneratorExpression.ResolvedExpression): Map<String, ByteArray> =
        expression.input.photos.associate { it.contentId to ByteArray(16) { 1 } }

    @Test
    fun projectionIsCanonicalHexAndDeterministic() {
        val expression = expression(input())
        val first = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        val second = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")), first)
    }

    @Test
    fun projectionExcludesOwnerAndEpoch() {
        val a = expression(input(owner = "owner-1", epoch = 7L))
        val b = expression(input(owner = "owner-2", epoch = 99L))
        assertEquals(
            GeneratorExpressionProjection.hash(a, "cand-1", blobs(a)),
            GeneratorExpressionProjection.hash(b, "cand-1", blobs(b)),
            "projection must not expose sender owner/epoch",
        )
    }

    @Test
    fun projectionBindsCandidateGeometryAndSourceBlobs() {
        val expression = expression(input())
        val base = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        assertNotEquals(
            base,
            GeneratorExpressionProjection.hash(expression, "cand-2", blobs(expression)),
        )
        val moved = expression.copy(
            placements = expression.placements.mapIndexed { index, p ->
                if (index == 0) p.copy(x = p.x + 1) else p
            },
        )
        assertNotEquals(base, GeneratorExpressionProjection.hash(moved, "cand-1", blobs(expression)))
        val changedBlob = blobs(expression).toMutableMap().apply { put("p1", ByteArray(16) { 2 }) }
        assertNotEquals(base, GeneratorExpressionProjection.hash(expression, "cand-1", changedBlob))
    }

    @Test
    fun projectionFailsClosedOnMissingOrWrongSizedBlob() {
        val expression = expression(input())
        val missing = blobs(expression).toMutableMap().apply { remove("p2") }
        assertFailsWith<IllegalArgumentException> {
            GeneratorExpressionProjection.hash(expression, "cand-1", missing)
        }
    }

    // ---- BEXPR02 (finished collage, contract v3) ----

    private fun v3expression(): GeneratorExpression.ResolvedExpression {
        val photos = listOf(
            photo("p1", 0, "a".repeat(64)),
            photo("p2", 1, "b".repeat(64)),
            photo("p3", 2, "c".repeat(64)),
        )
        val input = GeneratorExpression.GeneratorInput(
            ownerId = "owner-1",
            epoch = 7L,
            photos = photos,
            note = "hello",
            music = null,
        )
        return GeneratorExpression.ResolvedExpression(
            canvasVersion = 1,
            grammarId = "g-collage",
            grammarVersion = 5,
            branchId = "b-sheet",
            input = input,
            placements = photos.map {
                GeneratorExpression.Placement(
                    contentId = it.contentId,
                    x = 0,
                    y = 0,
                    width = 120,
                    height = 213,
                    crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000),
                    maskId = null,
                    contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213),
                )
            },
            noteTreatment = "regular-region",
            diagnostics = listOf("ok"),
            fontVersion = 1,
            paletteVersion = 1,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
            noteRegion = null,
            style = GeneratorExpression.StyleBinding(
                styleId = "press",
                styleVersion = "style-study-1",
                digest = ByteArray(32) { 7 },
            ),
            noteType = GeneratorExpression.TextType(
                family = "StudySans",
                size = 16,
                weight = 400,
                italic = false,
                line = 18,
            ),
            musicRegion = null,
        )
    }

    private fun magicOf(bytes: ByteArray): String {
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        return ByteArray(7).also { buf.get(it) }.toString(Charsets.US_ASCII)
    }

    @Test
    fun bexpr02MagicAndVersion() {
        val expression = v3expression()
        assertIsValid(expression)
        val bytes = GeneratorExpressionProjection.canonicalBytes(expression, "cand-1", blobs(expression))
        assertEquals("BEXPR02", magicOf(bytes))
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.position(7)
        assertEquals(GeneratorExpressionProjection.PROJECTION_VERSION_2, buf.int)
    }

    @Test
    fun bexpr02IsDeterministicHexAndExcludesOwner() {
        val expression = v3expression()
        val first = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        val second = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        assertEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")), first)
        val moved = expression.copy(
            input = expression.input.copy(ownerId = "owner-2", epoch = 99L),
        )
        assertEquals(
            first,
            GeneratorExpressionProjection.hash(moved, "cand-1", blobs(moved)),
            "projection must not expose sender owner/epoch",
        )
    }

    @Test
    fun bexpr02BindsCropMusicAndStyle() {
        val expression = v3expression()
        val base = GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        val shiftedCrop = expression.copy(
            placements = expression.placements.mapIndexed { index, p ->
                if (index == 0) p.copy(crop = GeneratorExpression.CropWindow(101, 200, 5000, 6000)) else p
            },
        )
        val withMusic = expression.copy(
            musicRegion = GeneratorExpression.MusicRegion(
                16, 480, 328, 64, "text",
                GeneratorExpression.TextType("StudySans", 14, 400, false, 16),
            ),
        )
        val restyled = expression.copy(
            style = expression.style!!.copy(styleId = "blue-letter"),
        )
        for (variant in listOf(shiftedCrop, withMusic, restyled)) {
            assertIsValid(variant)
            assertNotEquals(
                base,
                GeneratorExpressionProjection.hash(variant, "cand-1", blobs(variant)),
            )
        }
    }

    @Test
    fun bexpr02FailsClosedWithoutStyle() {
        val expression = v3expression().copy(style = null)
        assertFailsWith<IllegalArgumentException> {
            GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression))
        }
    }

    @Test
    fun v1FixtureStaysBexpr01() {
        val expression = expression(input())
        val bytes = GeneratorExpressionProjection.canonicalBytes(expression, "cand-1", blobs(expression))
        assertEquals("BEXPR01", magicOf(bytes))
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.position(7)
        assertEquals(GeneratorExpressionProjection.PROJECTION_VERSION, buf.int)
        assertNotEquals(
            GeneratorExpressionProjection.hash(expression, "cand-1", blobs(expression)),
            GeneratorExpressionProjection.hash(v3expression(), "cand-1", blobs(v3expression())),
        )
    }

    private fun assertIsValid(expression: GeneratorExpression.ResolvedExpression) {
        assertTrue(
            GeneratorExpression.validateResolved(expression) is GeneratorExpression.InputValidation.Valid,
            "expected Valid, got ${GeneratorExpression.validateResolved(expression)}",
        )
    }
}
