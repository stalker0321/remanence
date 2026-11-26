package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.model.GeneratorExpression
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An unwrapped mark's layout is only as wide as its text, so the sealed
 * alignment places it in the box; the site paints marks the same way
 * (`web/test/paint-marks.test.ts`).
 */
class MarkLeadTest {

    private fun mark(
        align: GeneratorExpression.MarkAlign,
        orientation: GeneratorExpression.MarkOrientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
    ) = CollageRenderer.SceneProvenance(
        kind = GeneratorExpression.ProvenanceKind.SENDER,
        text = "From ada",
        region = CollageRenderer.RectI(20, 600, 200, 24),
        family = "StudySans",
        size = 20,
        weight = 400,
        italic = false,
        line = 21,
        orientation = orientation,
        align = align,
        fontResourceId = 0,
        color = 0,
        tracking = 0.0,
    )

    @Test
    fun horizontalMarksSitInTheFreeWidth() {
        assertEquals(0f, markLead(mark(GeneratorExpression.MarkAlign.LEFT), 40))
        assertEquals(80f, markLead(mark(GeneratorExpression.MarkAlign.CENTER), 40))
        assertEquals(160f, markLead(mark(GeneratorExpression.MarkAlign.RIGHT), 40))
    }

    @Test
    fun verticalMarksSitInTheFreeHeight() {
        val down = mark(GeneratorExpression.MarkAlign.RIGHT, GeneratorExpression.MarkOrientation.VERTICAL_DOWN)
        assertEquals(0f, markLead(down, 24))
        assertEquals(0f, markLead(down.copy(region = CollageRenderer.RectI(2, 8, 23, 300)), 300))
        assertEquals(260f, markLead(down.copy(region = CollageRenderer.RectI(2, 8, 23, 300)), 40))
    }

    @Test
    fun aTooWideMarkStartsAtTheEdge() {
        assertEquals(0f, markLead(mark(GeneratorExpression.MarkAlign.RIGHT), 260))
    }
}
