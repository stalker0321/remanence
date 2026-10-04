package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.ui.collage.CropTransform
import dev.hryshyn.remanence.ui.collage.NormalizedCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropEditorGeometryTest {
    private val base = NormalizedCrop(.2, .1, .4, .6)
    private val identity = CropTransform(1.0, 0.0, 0.0)

    @Test
    fun draggingAtOriginalZoomMovesFrameInFingerDirection() {
        val transform = requireNotNull(CropEditorGeometry.gesture(base, identity, .15, .1, 1.0, .4, .4))
        val window = requireNotNull(CropEditorGeometry.window(base, transform))
        assertEquals(.35, window.x, 1e-9)
        assertEquals(.2, window.y, 1e-9)
        assertEquals(.4, window.width, 1e-9)
        assertEquals(.6, window.height, 1e-9)
    }

    @Test
    fun dragCanReachBothSourceEdgesButCannotEscapeThem() {
        for (delta in listOf(-100.0, 100.0)) {
            val transform = requireNotNull(CropEditorGeometry.gesture(base, identity, delta, delta, 1.0, .4, .4))
            val window = requireNotNull(CropEditorGeometry.window(base, transform))
            assertEquals(if (delta < 0) 0.0 else .6, window.x, 1e-9)
            assertEquals(if (delta < 0) 0.0 else .4, window.y, 1e-9)
        }
    }

    @Test
    fun pinchPreservesTheSourcePointUnderTheCentroid() {
        val transform = requireNotNull(CropEditorGeometry.gesture(base, identity, 0.0, 0.0, 1.2, .3, .25))
        val window = requireNotNull(CropEditorGeometry.window(base, transform))
        // The point 25% along either crop axis must stay under the pinch centroid.
        assertEquals(.3, window.x + window.width * .25, 1e-9)
        assertEquals(.25, window.y + window.height * .25, 1e-9)
        assertEquals(base.width / base.height, window.width / window.height, 1e-9)
        assertEquals(base.width / 1.2, window.width, 1e-9)
    }

    @Test
    fun pinchInRecoversAreaOutsideInitialCropAndStopsAtSourceBounds() {
        val transform = requireNotNull(CropEditorGeometry.gesture(base, identity, 0.0, 0.0, .1, .4, .4))
        val window = requireNotNull(CropEditorGeometry.window(base, transform))
        assertEquals(.6, transform.z, 1e-9)
        assertEquals(1.0, window.height, 1e-9)
        assertEquals(0.0, window.y, 1e-9)
        assertTrue(window.width > base.width)
    }

    @Test
    fun repeatedGesturesAccumulateWithoutChangingAspect() {
        var transform = identity
        repeat(10) {
            transform = requireNotNull(CropEditorGeometry.gesture(base, transform, .01, .005, 1.01, .4, .4))
        }
        val window = requireNotNull(CropEditorGeometry.window(base, transform))
        assertEquals(Math.pow(1.01, 10.0), transform.z, 1e-9)
        assertTrue(window.x > base.x)
        assertEquals(base.width / base.height, window.width / window.height, 1e-9)
    }

    @Test
    fun invalidInputsAreRejectedAndExtremeFiniteValuesStayBounded() {
        assertNull(CropEditorGeometry.clamp(base, CropTransform(Double.NaN, 0.0, 0.0)))
        assertNull(CropEditorGeometry.gesture(base, identity, 0.0, 0.0, 0.0, .5, .5))
        assertNull(CropEditorGeometry.clamp(base.copy(width = -1.0), identity))
        val transform = requireNotNull(CropEditorGeometry.clamp(base, CropTransform(500.0, 1e6, -1e6)))
        val window = requireNotNull(CropEditorGeometry.window(base, transform))
        assertEquals(1.25, transform.z, 1e-9)
        assertTrue(window.x >= -1e-9 && window.y >= -1e-9)
        assertTrue(window.x + window.width <= 1.000000001)
        assertTrue(window.y + window.height <= 1.000000001)
    }
}
