package dev.hryshyn.remanence.ui.capsule

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class CapsuleSheetSizeTest {
    @Test fun boundsPreserveFullSheetInPortraitAndLandscape() {
        for ((width, height) in listOf(320.dp to 700.dp, 700.dp to 320.dp, 1.dp to 1.dp)) {
            val (w, h) = capsuleSheetSize(width, height, 360f / 640f)
            assertTrue(w > 0.dp && h > 0.dp)
            assertTrue(w <= width && h <= height)
            assertEquals(360f / 640f, w.value / h.value, 0.0001f)
        }
    }
    @Test fun invalidBoundsFailInsteadOfRecordingAnEmptyImage() {
        assertThrows(IllegalArgumentException::class.java) { capsuleSheetSize(0.dp, 10.dp, 1f) }
        assertThrows(IllegalArgumentException::class.java) { capsuleSheetSize(10.dp, 10.dp, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { capsuleSheetSize(10.dp, 10.dp, 0f) }
    }
}
