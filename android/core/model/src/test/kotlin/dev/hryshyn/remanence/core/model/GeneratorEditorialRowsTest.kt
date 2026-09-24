package dev.hryshyn.remanence.core.model

import dev.hryshyn.remanence.core.model.GeneratorEditorialRows.PlanResult
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows.NoteMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BER1 geometry planner tests (ADR-017 + G1 v2). Pure model only: no G2
 * provider, no renderer, no UI, no bytes. The note path is pinned to the
 * explicit measure seam, so no test fabricates note fit from a char count.
 */
class GeneratorEditorialRowsTest {

    private fun photo(id: String, ordinal: Int, w: Int, h: Int) =
        GeneratorExpression.PhotoRef(
            contentId = id,
            ordinal = ordinal,
            widthPx = w,
            heightPx = h,
            contentHash = id.map { it.code.toString(16).padStart(2, '0') }
                .joinToString("").padEnd(64, '0').take(64),
        )

    private fun input(
        ids: List<String> = listOf("p1", "p2", "p3"),
        dims: (String) -> Pair<Int, Int> = { 1000 to 1000 },
        note: String? = null,
    ) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = ids.mapIndexed { index, id ->
            val (w, h) = dims(id)
            photo(id, index, w, h)
        },
        note = note,
        music = null,
    )

    private fun planned(
        input: GeneratorExpression.GeneratorInput,
        port: GeneratorEditorialRows.NoteMeasurementPort? = null,
    ): GeneratorExpression.ResolvedExpression {
        val result = GeneratorEditorialRows.plan(input, port)
        assertIs<PlanResult.Planned>(result, "expected Planned, got $result")
        val expression = result.expression
        assertIs<GeneratorExpression.InputValidation.Valid>(
            GeneratorExpression.validateResolved(expression),
        )
        return expression
    }

    private fun cells(expression: GeneratorExpression.ResolvedExpression): List<GeneratorEditorialRows.Cell> =
        expression.placements.map { GeneratorEditorialRows.Cell(it.x, it.y, it.width, it.height) }

    private fun rects(expression: GeneratorExpression.ResolvedExpression): List<GeneratorExpression.ContentRect> =
        expression.placements.map { requireNotNull(it.contentRect) }

    // ---- exact tilings ----

    @Test
    fun absentNoteGeometryFor3_4_5MatchesAdr() {
        val expected3 = listOf(
            GeneratorEditorialRows.Cell(16, 16, 158, 298),
            GeneratorEditorialRows.Cell(186, 16, 158, 298),
            GeneratorEditorialRows.Cell(16, 326, 328, 298),
        )
        val expected4 = listOf(
            GeneratorEditorialRows.Cell(16, 16, 158, 298),
            GeneratorEditorialRows.Cell(186, 16, 158, 298),
            GeneratorEditorialRows.Cell(16, 326, 158, 298),
            GeneratorEditorialRows.Cell(186, 326, 158, 298),
        )
        val expected5 = listOf(
            GeneratorEditorialRows.Cell(16, 16, 158, 195),
            GeneratorEditorialRows.Cell(186, 16, 158, 195),
            GeneratorEditorialRows.Cell(16, 223, 158, 195),
            GeneratorEditorialRows.Cell(186, 223, 158, 195),
            GeneratorEditorialRows.Cell(16, 430, 328, 194),
        )
        assertEquals(expected3, cells(planned(input(ids = listOf("p1", "p2", "p3")))))
        assertEquals(expected4, cells(planned(input(ids = listOf("p1", "p2", "p3", "p4")))))
        assertEquals(
            expected5,
            cells(planned(input(ids = listOf("p1", "p2", "p3", "p4", "p5")))),
        )
    }

    @Test
    fun measuredBandGeometryFor5MatchesAdr() {
        val port = GeneratorEditorialRows.NoteMeasurementPort {
            NoteMeasurement.Fits(GeneratorEditorialRows.NOTE_REGION)
        }
        val expression = planned(input(ids = listOf("p1", "p2", "p3", "p4", "p5"), note = "hi"), port)
        assertEquals(GeneratorEditorialRows.NOTE_REGION, expression.noteRegion)
        assertEquals("note:measured", expression.noteTreatment)
        assertEquals(
            listOf(
                GeneratorEditorialRows.Cell(16, 16, 158, 154),
                GeneratorEditorialRows.Cell(186, 16, 158, 154),
                GeneratorEditorialRows.Cell(16, 182, 158, 153),
                GeneratorEditorialRows.Cell(186, 182, 158, 153),
                GeneratorEditorialRows.Cell(16, 347, 328, 153),
            ),
            cells(expression),
        )
    }

    @Test
    fun absentAndEmptyNotesAreDistinctAndBandFree() {
        val absent = planned(input(note = null))
        val empty = planned(input(note = ""))
        assertEquals(null, absent.noteRegion)
        assertEquals(null, empty.noteRegion)
        assertEquals("note:absent", absent.noteTreatment)
        assertEquals("note:empty", empty.noteTreatment)
        assertNotEquals(
            GeneratorExpression.canonicalHash(absent),
            GeneratorExpression.canonicalHash(empty),
        )
    }

    // ---- aspect letterbox ----

    @Test
    fun mixedAspectsLetterboxExactWithinCells() {
        val expression = planned(
            input(
                ids = listOf("p1", "p2", "p3"),
                dims = { id ->
                    when (id) {
                        "p1" -> 1080 to 1920 // 9:16
                        "p2" -> 1920 to 1080 // 16:9
                        else -> 1000 to 1000 // 1:1
                    }
                },
            ),
        )
        assertEquals(
            listOf(
                GeneratorExpression.ContentRect(16, 25, 158, 280),
                GeneratorExpression.ContentRect(186, 121, 158, 88),
                GeneratorExpression.ContentRect(31, 326, 298, 298),
            ),
            rects(expression),
        )
        // Every contentRect sits inside its placement cell.
        expression.placements.forEach { placement ->
            val rect = requireNotNull(placement.contentRect)
            assertTrue(rect.x >= placement.x && rect.y >= placement.y)
            assertTrue(rect.x + rect.width <= placement.x + placement.width)
            assertTrue(rect.y + rect.height <= placement.y + placement.height)
        }
    }

    @Test
    fun extremeAspectsAreTypedIncompatible() {
        val panorama = GeneratorEditorialRows.plan(
            input(ids = listOf("p1", "p2", "p3"), dims = { if (it == "p1") 2000 to 100 else 1000 to 1000 }),
        )
        assertIs<PlanResult.Incompatible>(panorama)
        assertTrue(panorama.reason.contains("short side"), panorama.reason)

        val tall = GeneratorEditorialRows.plan(
            input(ids = listOf("p1", "p2", "p3"), dims = { if (it == "p1") 100 to 2000 else 1000 to 1000 }),
        )
        assertIs<PlanResult.Incompatible>(tall)
    }

    @Test
    fun minShortSideBoundaryPassesAt48AndFailsAt47() {
        val pass = GeneratorEditorialRows.plan(
            input(ids = listOf("p1", "p2", "p3"), dims = { if (it == "p1") 1580 to 480 else 1000 to 1000 }),
        )
        assertIs<PlanResult.Planned>(pass)
        assertEquals(
            GeneratorExpression.ContentRect(16, 141, 158, 48),
            rects(pass.expression).first(),
        )
        val fail = GeneratorEditorialRows.plan(
            input(ids = listOf("p1", "p2", "p3"), dims = { if (it == "p1") 1580 to 470 else 1000 to 1000 }),
        )
        assertIs<PlanResult.Incompatible>(fail)
        assertTrue(fail.reason.contains("47"), fail.reason)
    }

    // ---- note measure seam ----

    @Test
    fun nonEmptyNoteWithoutPortRequiresMeasurement() {
        assertIs<PlanResult.NeedsNoteMeasure>(GeneratorEditorialRows.plan(input(note = "hello")))
        val hello = GeneratorEditorialRows.plan(input(note = "hello")) as PlanResult.NeedsNoteMeasure
        assertEquals(5, hello.noteUtf8Bytes)
        val multibyte = GeneratorEditorialRows.plan(input(note = "Кино")) as PlanResult.NeedsNoteMeasure
        assertEquals(8, multibyte.noteUtf8Bytes)
    }

    @Test
    fun nonEmptyNoteRejectingPortIsIncompatible() {
        val port = GeneratorEditorialRows.NoteMeasurementPort {
            NoteMeasurement.DoesNotFit("does not fit $it")
        }
        val result = GeneratorEditorialRows.plan(input(note = "a long note"), port)
        assertIs<PlanResult.Incompatible>(result)
        assertTrue(result.reason.contains("does not fit"), result.reason)
    }

    @Test
    fun noteMeasurementMustUseFixedAdrRegion() {
        val port = GeneratorEditorialRows.NoteMeasurementPort {
            NoteMeasurement.Fits(GeneratorExpression.NoteRegion(0, 0, 100, 100))
        }
        val result = GeneratorEditorialRows.plan(input(note = "hi"), port)
        assertIs<PlanResult.Incompatible>(result)
        assertTrue(result.reason.contains("fixed ADR-017 region"), result.reason)
    }

    @Test
    fun noteRegionIsAbsentWithoutMeasurementAndPresentWithFits() {
        val without = planned(input(note = null))
        assertEquals(null, without.noteRegion)
        val fitting = GeneratorEditorialRows.NoteMeasurementPort {
            NoteMeasurement.Fits(GeneratorEditorialRows.NOTE_REGION)
        }
        val withBand = planned(input(note = "hi"), fitting)
        assertEquals(GeneratorEditorialRows.NOTE_REGION, withBand.noteRegion)
    }

    // ---- determinism / validity / order ----

    @Test
    fun plannedExpressionIsValidV2AndDeterministic() {
        val first = planned(input())
        val second = planned(input())
        assertEquals(first, second)
        assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V2, first.expressionContractVersion)
        val hash = GeneratorExpression.canonicalHash(first)
        assertEquals(hash, GeneratorExpression.canonicalHash(second))
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")), hash)
    }

    @Test
    fun authoredOrderAndContentRectsArePreserved() {
        val ids = listOf("a", "b", "c", "d", "e")
        val expression = planned(input(ids = ids))
        assertEquals(ids, expression.placements.map { it.contentId })
        assertTrue(expression.placements.all { it.contentRect != null })
    }

    @Test
    fun invalidInputFailsClosedWithoutExpression() {
        assertIs<PlanResult.Invalid>(GeneratorEditorialRows.plan(input(ids = listOf("p1", "p2"))))
        val badOrdinal = input().copy(
            photos = input().photos.mapIndexed { index, p -> p.copy(ordinal = index + 1) },
        )
        assertIs<PlanResult.Invalid>(GeneratorEditorialRows.plan(badOrdinal))
        val duplicate = input(ids = listOf("p1", "p1", "p2"))
        assertIs<PlanResult.Invalid>(GeneratorEditorialRows.plan(duplicate))
    }

    @Test
    fun extremeDeclaredDimensionsDoNotOverflow() {
        val veryWide = GeneratorEditorialRows.plan(
            input(ids = listOf("p1", "p2", "p3"), dims = { if (it == "p1") Int.MAX_VALUE to 1 else 1000 to 1000 }),
        )
        assertIs<PlanResult.Incompatible>(veryWide)
        val veryTall = GeneratorEditorialRows.plan(
            input(ids = listOf("p1", "p2", "p3"), dims = { if (it == "p1") 1 to Int.MAX_VALUE else 1000 to 1000 }),
        )
        assertIs<PlanResult.Incompatible>(veryTall)
    }

    @Test
    fun rowPatternsArePinned() {
        assertEquals(listOf(2, 1), GeneratorEditorialRows.rowPattern(3))
        assertEquals(listOf(2, 2), GeneratorEditorialRows.rowPattern(4))
        assertEquals(listOf(2, 2, 1), GeneratorEditorialRows.rowPattern(5))
        assertEquals(GeneratorExpression.NoteRegion(16, 512, 328, 128), GeneratorEditorialRows.NOTE_REGION)
    }
}
