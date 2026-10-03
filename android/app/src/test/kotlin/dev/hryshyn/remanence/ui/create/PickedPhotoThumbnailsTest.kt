package dev.hryshyn.remanence.ui.create

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Loader-scope pins for [PickedPhotoThumbnails]: the power-of-two sampler
 * never emits an over-bound long edge (the old `longest / (sample * 2)`
 * loop decoded 1000 px at sample 2 = 500 px > 256), and the legacy bounded
 * decode path honors the bound on real decoder output. No picker, VM, or
 * UI scope is under test here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickedPhotoThumbnailsTest {

    @Test
    fun samplerHonorsBoundAtAdversarialEdges() {
        // Exact hand-checked pins, including the reported 1000 px violation.
        assertEquals(1, PickedPhotoThumbnails.sampleSizeFor(1))
        assertEquals(1, PickedPhotoThumbnails.sampleSizeFor(256))
        assertEquals(2, PickedPhotoThumbnails.sampleSizeFor(257))
        assertEquals(2, PickedPhotoThumbnails.sampleSizeFor(500))
        assertEquals(2, PickedPhotoThumbnails.sampleSizeFor(511))
        assertEquals(2, PickedPhotoThumbnails.sampleSizeFor(512))
        assertEquals(4, PickedPhotoThumbnails.sampleSizeFor(513))
        assertEquals(4, PickedPhotoThumbnails.sampleSizeFor(1000))
        assertEquals(4, PickedPhotoThumbnails.sampleSizeFor(1001))
        assertEquals(16, PickedPhotoThumbnails.sampleSizeFor(4096))
        assertEquals(1, PickedPhotoThumbnails.sampleSizeFor(0))
        assertEquals(1, PickedPhotoThumbnails.sampleSizeFor(-7))
    }

    @Test
    fun samplerIsMinimalPowerOfTwoAcrossSweep() {
        for (longest in 1..5000) {
            val sample = PickedPhotoThumbnails.sampleSizeFor(longest)
            val emitted = (longest + sample - 1) / sample
            assertTrue("sample $sample not power of two for $longest", sample and (sample - 1) == 0)
            assertTrue(
                "long edge $emitted over bound for $longest",
                emitted <= PickedPhotoThumbnails.MAX_TILE_PX,
            )
            if (sample > 1) {
                val looser = (longest + sample / 2 - 1) / (sample / 2)
                assertTrue(
                    "sample $sample not minimal for $longest",
                    looser > PickedPhotoThumbnails.MAX_TILE_PX,
                )
            }
        }
    }

    @Test
    fun legacyDecodeHonorsBoundOnRealDecoderOutput() {
        val png = pngBytes(1000, 700)
        val bitmap = PickedPhotoThumbnails.decodeBounded(
            openStream = { ByteArrayInputStream(png) },
        )
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width <= PickedPhotoThumbnails.MAX_TILE_PX)
        assertTrue(bitmap.height <= PickedPhotoThumbnails.MAX_TILE_PX)
        // 1000 px at sample 4 → 250 px preserved (not an over-decimated 125).
        assertEquals(250, bitmap.width)
    }

    @Test
    fun legacyDecodeRejectsEmptyAndCorrupt() {
        assertNull(
            PickedPhotoThumbnails.decodeBounded(openStream = { ByteArrayInputStream(ByteArray(0)) }),
        )
        assertNull(
            PickedPhotoThumbnails.decodeBounded(
                openStream = { ByteArrayInputStream(ByteArray(64) { 0x7F }) },
            ),
        )
        assertNull(PickedPhotoThumbnails.decodeBounded(openStream = { null }))
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3A5F9E.toInt())
        val out = ByteArrayOutputStream()
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) { "encode failed" }
        bitmap.recycle()
        return out.toByteArray()
    }
}
