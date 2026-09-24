package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side admission tests for the isolated Stage1-C BER1 renderer seam.
 * Pure JVM: no ImageBitmap, no Compose runtime, no pixels. The Compose text
 * measure/draw and its visual result remain a device gate (see the renderer
 * KDoc); here we pin the pure photo admission and the pure note-fit predicate.
 */
class GeneratorBer1PreviewTest {

    private val defaultIds = listOf("p1", "p2", "p3")

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
        ids: List<String> = defaultIds,
        note: String? = null,
        dims: (String) -> Pair<Int, Int> = { 1000 to 1000 },
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

    private val fitsRegion = GeneratorEditorialRows.NoteMeasurementPort {
        GeneratorEditorialRows.NoteMeasurement.Fits(GeneratorEditorialRows.NOTE_REGION)
    }

    private fun planned(input: GeneratorExpression.GeneratorInput): GeneratorExpression.ResolvedExpression {
        val result = GeneratorEditorialRows.plan(input)
        assertTrue("expected Planned, got $result", result is GeneratorEditorialRows.PlanResult.Planned)
        return (result as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun expression(
        note: String? = null,
        port: GeneratorEditorialRows.NoteMeasurementPort? = null,
    ): GeneratorExpression.ResolvedExpression {
        val result = GeneratorEditorialRows.plan(input(note = note), port)
        assertTrue("expected Planned, got $result", result is GeneratorEditorialRows.PlanResult.Planned)
        return (result as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun dimensions(ids: List<String> = defaultIds) = ids.associateWith { 1000 to 1000 }

    private fun scene(result: GeneratorBer1Renderer.SceneResult): GeneratorBer1Renderer.Scene {
        assertTrue("expected Ready, got $result", result is GeneratorBer1Renderer.SceneResult.Ready)
        return (result as GeneratorBer1Renderer.SceneResult.Ready).scene
    }

    // ---- happy path: frozen geometry consumed verbatim ----

    @Test
    fun readySceneUsesFrozenGeometryInAuthoredOrder() {
        val resolved = expression()
        val ready = scene(GeneratorBer1Renderer.prepare(resolved, defaultIds.toSet(), dimensions()))
        assertEquals(360, ready.canvasWidth)
        assertEquals(640, ready.canvasHeight)
        assertEquals(defaultIds, ready.photos.map { it.contentId })
        assertEquals(resolved.placements.map { it.x }, ready.photos.map { it.cell.x })
        assertEquals(resolved.placements.map { it.y }, ready.photos.map { it.cell.y })
        assertEquals(
            resolved.placements.map { it.contentRect!!.width },
            ready.photos.map { it.contentRect.width },
        )
        assertEquals(
            resolved.placements.map { it.contentRect!!.height },
            ready.photos.map { it.contentRect.height },
        )
        assertNull(ready.note)
        assertNull(ready.noteRegion)
        assertEquals(GeneratorEditorialRows.FONT_VERSION, ready.fontVersion)
    }

    @Test
    fun emptyNoteYieldsReadyWithoutRegion() {
        val ready = scene(GeneratorBer1Renderer.prepare(expression(note = ""), defaultIds.toSet(), dimensions()))
        assertNull(ready.note)
        assertNull(ready.noteRegion)
    }

    // ---- note gate: a non-empty note is never a pure Ready ----

    @Test
    fun nonEmptyNotePreparesPhotosButIsNeverReady() {
        val resolved = expression(note = "hi", port = fitsRegion)
        val result = GeneratorBer1Renderer.prepare(resolved, defaultIds.toSet(), dimensions())
        assertTrue("expected NeedsNoteMeasure, got $result", result is GeneratorBer1Renderer.SceneResult.NeedsNoteMeasure)
        val needs = result as GeneratorBer1Renderer.SceneResult.NeedsNoteMeasure
        assertEquals(2, needs.noteUtf8Bytes)
        assertEquals("hi", needs.scene.note)
        assertEquals(GeneratorEditorialRows.NOTE_REGION, needs.scene.noteRegion)
        assertEquals(defaultIds, needs.scene.photos.map { it.contentId })
    }

    @Test
    fun noteFitPredicateRejectsOverflowAndAcceptsExactRegion() {
        val region = GeneratorEditorialRows.NOTE_REGION
        assertTrue(GeneratorBer1Renderer.NoteFit.fits(GeneratorBer1Renderer.NoteFit.Metrics(328, 128, false), region))
        assertTrue(GeneratorBer1Renderer.NoteFit.fits(GeneratorBer1Renderer.NoteFit.Metrics(100, 50, false), region))
        assertTrue(!GeneratorBer1Renderer.NoteFit.fits(GeneratorBer1Renderer.NoteFit.Metrics(328, 129, false), region))
        assertTrue(!GeneratorBer1Renderer.NoteFit.fits(GeneratorBer1Renderer.NoteFit.Metrics(329, 128, false), region))
        assertTrue(!GeneratorBer1Renderer.NoteFit.fits(GeneratorBer1Renderer.NoteFit.Metrics(0, 0, false), region))
        assertTrue(!GeneratorBer1Renderer.NoteFit.fits(GeneratorBer1Renderer.NoteFit.Metrics(10, 10, true), region))
    }

    // ---- strict source-id / dimension rejection ----

    @Test
    fun missingAndExtraSourceIdsAreRejected() {
        val resolved = expression()
        assertTrue(
            GeneratorBer1Renderer.prepare(resolved, setOf("p1", "p2"), dimensions())
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
        assertTrue(
            GeneratorBer1Renderer.prepare(resolved, setOf("p1", "p2", "p3", "p4"), dimensions())
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
    }

    @Test
    fun sourceDimensionIdMismatchAndNonPositiveDimsAreRejected() {
        val resolved = expression()
        val wrongIds: Map<String, Pair<Int, Int>> =
            mapOf("p1" to (1000 to 1000), "p2" to (1000 to 1000))
        assertTrue(
            GeneratorBer1Renderer.prepare(resolved, defaultIds.toSet(), wrongIds)
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
        val zeroWidth = dimensions().toMutableMap().apply { put("p1", 0 to 1000) }
        assertTrue(
            GeneratorBer1Renderer.prepare(resolved, defaultIds.toSet(), zeroWidth)
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
    }

    // ---- strict version rejection ----

    @Test
    fun unsupportedExpressionContractGrammarFontAndPaletteAreRejected() {
        val resolved = expression()
        val v1 = resolved.copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V1,
            placements = resolved.placements.map { it.copy(contentRect = null) },
        )
        assertTrue(
            GeneratorBer1Renderer.prepare(v1, defaultIds.toSet(), dimensions())
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
        assertTrue(
            GeneratorBer1Renderer.prepare(resolved.copy(grammarId = "other"), defaultIds.toSet(), dimensions())
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
        assertTrue(
            GeneratorBer1Renderer.prepare(resolved.copy(fontVersion = 2), defaultIds.toSet(), dimensions())
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
        assertTrue(
            GeneratorBer1Renderer.prepare(resolved.copy(paletteVersion = 0), defaultIds.toSet(), dimensions())
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
        assertTrue(
            GeneratorBer1Renderer.prepare(resolved.copy(paletteVersion = 2), defaultIds.toSet(), dimensions())
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
    }

    @Test
    fun missingContentRectIsRejected() {
        val resolved = expression()
        val broken = resolved.copy(
            placements = resolved.placements.map { it.copy(contentRect = null) },
        )
        assertTrue(
            GeneratorBer1Renderer.prepare(broken, defaultIds.toSet(), dimensions())
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
    }

    @Test
    fun bitmapAspectMustMatchAuthoredGeometry() {
        val authored = planned(input(dims = { id -> if (id == "p1") 1080 to 1920 else 1000 to 1000 }))
        val sameAspectScaled =
            mapOf("p1" to (540 to 960), "p2" to (1000 to 1000), "p3" to (1000 to 1000))
        assertTrue(
            GeneratorBer1Renderer.prepare(authored, defaultIds.toSet(), sameAspectScaled)
                is GeneratorBer1Renderer.SceneResult.Ready,
        )
        val rotated =
            mapOf("p1" to (960 to 540), "p2" to (1000 to 1000), "p3" to (1000 to 1000))
        assertTrue(
            GeneratorBer1Renderer.prepare(authored, defaultIds.toSet(), rotated)
                is GeneratorBer1Renderer.SceneResult.Rejected,
        )
    }

    @Test
    fun isDrawableFailsClosedWhenAnySourceMissing() {
        val ready = scene(GeneratorBer1Renderer.prepare(expression(), defaultIds.toSet(), dimensions()))
        assertTrue(GeneratorBer1Renderer.isDrawable(ready, defaultIds.toSet()))
        assertTrue(!GeneratorBer1Renderer.isDrawable(ready, setOf("p1", "p2")))
    }

    @Test
    fun noteAdmissionFailsClosedWithoutMatchingLayoutAndRegion() {
        val noNote = scene(GeneratorBer1Renderer.prepare(expression(), defaultIds.toSet(), dimensions()))
        val stray = GeneratorBer1Renderer.NoteRender(
            "x",
            GeneratorEditorialRows.NOTE_REGION,
            GeneratorBer1Renderer.NoteFit.Metrics(10, 10, false),
        )
        assertTrue(GeneratorBer1Renderer.noteDrawable(noNote, null, false))
        assertTrue(!GeneratorBer1Renderer.noteDrawable(noNote, stray, true))

        val needs = GeneratorBer1Renderer.prepare(
            expression(note = "hi", port = fitsRegion),
            defaultIds.toSet(),
            dimensions(),
        )
        assertTrue(needs is GeneratorBer1Renderer.SceneResult.NeedsNoteMeasure)
        val noteScene = (needs as GeneratorBer1Renderer.SceneResult.NeedsNoteMeasure).scene
        val region = GeneratorEditorialRows.NOTE_REGION
        val fits = GeneratorBer1Renderer.NoteRender(
            "hi",
            region,
            GeneratorBer1Renderer.NoteFit.Metrics(100, 50, false),
        )
        assertTrue(!GeneratorBer1Renderer.noteDrawable(noteScene, null, false))
        assertTrue(!GeneratorBer1Renderer.noteDrawable(noteScene, null, true))
        assertTrue(!GeneratorBer1Renderer.noteDrawable(noteScene, fits, false))
        assertTrue(GeneratorBer1Renderer.noteDrawable(noteScene, fits, true))
        assertTrue(!GeneratorBer1Renderer.noteDrawable(noteScene, fits.copy(note = "other"), true))
        assertTrue(
            !GeneratorBer1Renderer.noteDrawable(
                noteScene,
                fits.copy(region = GeneratorExpression.NoteRegion(0, 0, 10, 10)),
                true,
            ),
        )
        assertTrue(
            !GeneratorBer1Renderer.noteDrawable(
                noteScene,
                fits.copy(metrics = GeneratorBer1Renderer.NoteFit.Metrics(328, 129, false)),
                true,
            ),
        )
        assertTrue(
            !GeneratorBer1Renderer.noteDrawable(
                noteScene,
                fits.copy(metrics = GeneratorBer1Renderer.NoteFit.Metrics(10, 10, true)),
                true,
            ),
        )
    }
}
