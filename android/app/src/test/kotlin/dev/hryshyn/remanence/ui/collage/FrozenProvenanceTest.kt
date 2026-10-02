package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T154: frozen provenance seam — static JS-verbatim boxes, sender identity,
 * mount carrier, and the crop-correction bounds check (prepare stays
 * unwired by design).
 */
class FrozenProvenanceTest {

    @Test
    fun staticFamiliesReturnVerbatimBoxes() {
        val g5 = BranchProvenance.marksFor(5, "editorial-1+2-equal-1", "alice")
        assertNotNull(g5)
        assertEquals(2, g5!!.size)
        val brand = g5.first { it.kind.name == "BRAND" }
        assertEquals("Remanence", brand.text)
        assertEquals(12, brand.x)
        assertEquals(4, brand.y)
        assertEquals(340, brand.width)
        assertEquals(75, brand.height)
        assertEquals(64, brand.size)
        assertEquals(70, brand.line)
        val sender = g5.first { it.kind.name == "SENDER" }
        assertEquals("From alice", sender.text)
        assertEquals(16, sender.x)
        assertEquals(613, sender.y)

        val g12 = BranchProvenance.marksFor(12, "score-3-equal", "alice")
        assertNotNull(g12)
        assertEquals("VERTICAL_DOWN", g12!!.first { it.kind.name == "BRAND" }.orientation.name)
        assertEquals("RIGHT", g12.first { it.kind.name == "SENDER" }.align.name)

        val g17 = BranchProvenance.marksFor(17, "mass-1+2-1.2-0", "alice")
        assertNotNull(g17)
        assertEquals("Narrow", g17!!.first { it.kind.name == "BRAND" }.family)

        val g18 = BranchProvenance.marksFor(18, "mount-1+2-0.4-1.2", "alice")
        assertNotNull(g18)
        assertEquals("VERTICAL_UP", g18!!.first { it.kind.name == "BRAND" }.orientation.name)

        val crown = BranchProvenance.marksFor(21, "letter-full-height-counterpoint", "alice")
        assertNotNull(crown)
        assertEquals(2, crown!!.size)
    }

    @Test
    fun dynamicInactiveAndUnknownBranchesReturnNull() {
        // Dynamic boxes belong to the T151 enumerator.
        assertNull(BranchProvenance.marksFor(6, "hinge-1+2-0.52-equal-paired", "alice"))
        assertNull(BranchProvenance.marksFor(11, "interlock-1/2+music-0.57-0.55-paired", "alice"))
        assertNull(BranchProvenance.marksFor(21, "letter-opening-205-0", "alice"))
        // Spliced grammars never produce.
        assertNull(BranchProvenance.marksFor(16, "equal-apertures-3-ratio-0.74", "alice"))
        assertNull(BranchProvenance.marksFor(19, "lenses-1+2-0", "alice"))
        assertNull(BranchProvenance.marksFor(99, "nope", "alice"))
    }

    @Test
    fun blankHandleOmitsSenderNeverFabricates() {
        for (handle in listOf(null, "", "   ")) {
            val marks = BranchProvenance.marksFor(5, "editorial-1+2-equal-1", handle)
            assertNotNull(marks)
            assertEquals(1, marks!!.size)
            assertEquals("BRAND", marks.single().kind.name)
            assertEquals("Remanence", marks.single().text)
        }
    }

    @Test
    fun mountCarrierDecorIsVerbatim() {
        val decor = BranchProvenance.mountCarrierDecor()
        assertEquals(1, decor.size)
        assertEquals("CARRIER", decor.single().kind.name)
        assertEquals(6, decor.single().x)
        assertEquals(5, decor.single().y)
        assertEquals(349, decor.single().width)
        assertEquals(627, decor.single().height)
    }

    @Test
    fun cropCorrectionBoundsCheck() {
        val sealed = dev.hryshyn.remanence.core.model.GeneratorExpression.CropWindow(100, 100, 500, 500)
        assertNull(
            validateCropCorrection(sealed, 1000, 1000, CropCorrection("c0", 10, -20)),
        )
        assertNotNull(
            validateCropCorrection(sealed, 1000, 1000, CropCorrection("c0", -101, 0)),
        )
        assertNotNull(
            validateCropCorrection(sealed, 1000, 1000, CropCorrection("c0", 401, 0)),
        )
        assertNotNull(
            validateCropCorrection(sealed, 1000, 1000, CropCorrection("", 0, 0)),
        )
    }
}
