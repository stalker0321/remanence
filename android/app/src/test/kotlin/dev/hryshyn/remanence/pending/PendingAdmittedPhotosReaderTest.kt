package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M14d-scan: pending presentation admission mapping — 3..5
 * photos present as LegacyV1 with the note; anything else is an
 * explicit Unsupported typed notice (never a fallback layout, never
 * retirement).
 */
class PendingAdmittedPhotosReaderTest {

    private val capsule = "11111111-2222-3333-4444-555555555555"

    private fun opened(count: Int, note: String? = "hello") =
        PendingAdmittedContent.OpenedPhotos(
            (0 until count).associateWith { "photo-$it".toByteArray() },
            note,
        )

    @Test
    fun threeToFivePhotosPresentLegacyV1() = runTest {
        for (count in 3..5) {
            val reader = PendingAdmittedPhotosReader(capsule, opened(count))
            assertTrue(
                reader.presentationAdmission(capsule) is CapsulePresentationAdmission.LegacyV1,
            )
            assertEquals(count, reader.photoCount(capsule))
            assertEquals("hello", reader.noteText(capsule))
            assertArrayEquals("photo-0".toByteArray(), reader.loadPhoto(capsule, 0).jpegBytes)
        }
    }

    @Test
    fun shortAndLongLayoutsAreUnsupported() = runTest {
        for (count in listOf(0, 1, 2, 6)) {
            val reader = PendingAdmittedPhotosReader(capsule, opened(count))
            val admission = reader.presentationAdmission(capsule)
            assertTrue(admission is CapsulePresentationAdmission.Unsupported)
        }
    }

    @Test
    fun foreignBindingAndUnknownOrdinalFailClosed() = runTest {
        val reader = PendingAdmittedPhotosReader(capsule, opened(3, note = null))
        assertNull(reader.noteText(capsule))
        try {
            reader.photoCount("99999999-2222-3333-4444-555555555555")
            throw AssertionError("expected binding mismatch")
        } catch (_: IllegalStateException) {
        }
        try {
            reader.loadPhoto(capsule, 9)
            throw AssertionError("expected unknown ordinal")
        } catch (_: IllegalStateException) {
        }
    }
}
