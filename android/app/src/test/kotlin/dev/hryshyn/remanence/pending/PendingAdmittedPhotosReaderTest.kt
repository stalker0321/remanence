package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.crypto.ContentManifestContent
import dev.hryshyn.remanence.core.crypto.ManifestPhoto
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M14d-scan: pending presentation carries the ACTUAL validated
 * content manifest — v1 frames with a pager-compatible count present
 * as LegacyV1; v2 frames run the shared expression gate (missing
 * expression here → typed Unsupported, never a silent v1 photo
 * layout); out-of-range counts are typed Unsupported. Unsupported
 * enqueues no retirement.
 */
class PendingAdmittedPhotosReaderTest {

    private val capsule = "11111111-2222-3333-4444-555555555555"

    private fun photos(count: Int): Map<Int, ByteArray> =
        (0 until count).associateWith { "photo-$it".toByteArray() }

    private fun manifestPhotos(count: Int): List<ManifestPhoto> =
        (0 until count).map {
            ManifestPhoto(
                blobId = UUID.fromString("cccccccc-dddd-eeee-ffff-00000000000$it"),
                ordinal = it,
                width = 800,
                height = 600,
            )
        }

    private fun opened(
        count: Int,
        protocolVersion: Int = 1,
        expression: dev.hryshyn.remanence.core.crypto.ContentExpression? = null,
        note: String? = "hello",
    ) = PendingAdmittedContent.OpenedPhotos(
        photosByOrdinal = photos(count),
        note = note,
        manifest = ContentManifestContent(
            protocolVersion = protocolVersion,
            photos = manifestPhotos(count),
            note = note,
            expression = expression,
        ),
    )

    @Test
    fun v1WithPagerCountPresentsLegacyV1() = runTest {
        for (count in 3..5) {
            val reader = PendingAdmittedPhotosReader(capsule, opened(count))
            assertTrue(
                reader.presentationAdmission(capsule) is CapsulePresentationAdmission.LegacyV1,
            )
            assertEquals(count, reader.photoCount(capsule))
            assertEquals("hello", reader.noteText(capsule))
            assertArrayEquals("photo-0".toByteArray(), reader.loadPhoto(capsule, 0).jpegBytes)
            assertNull(reader.trackSnapshot(capsule))
        }
    }

    @Test
    fun v1WithShortLayoutIsUnsupported() = runTest {
        for (count in 0..2) {
            val reader = PendingAdmittedPhotosReader(capsule, opened(count))
            val admission = reader.presentationAdmission(capsule)
            assertTrue(admission is CapsulePresentationAdmission.Unsupported)
        }
    }

    @Test
    fun v2WithoutExpressionIsUnsupportedNeverLegacyV1() = runTest {
        // A v2-intended gift must never render the v1 photo layout:
        // the missing expression gates to a typed notice (no retire).
        val reader = PendingAdmittedPhotosReader(capsule, opened(3, protocolVersion = 2))
        val admission = reader.presentationAdmission(capsule)
        assertTrue(admission is CapsulePresentationAdmission.Unsupported)
        assertEquals("hello", reader.noteText(capsule))
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
