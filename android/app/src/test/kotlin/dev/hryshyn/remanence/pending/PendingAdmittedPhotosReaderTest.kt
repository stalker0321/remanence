package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.crypto.ContentExpression
import dev.hryshyn.remanence.core.crypto.ContentManifestContent
import dev.hryshyn.remanence.core.crypto.ManifestPhoto
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission
import dev.hryshyn.remanence.ui.capsule.testV2Expression
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

    /**
     * T186 M14d-scan: a supported v2 expression + track snapshot
     * presents through the existing BER1 path with the snapshot
     * carried (same admission object the v1 source maps — no
     * restyle, no music redesign).
     */
    @Test
    fun v2SupportedExpressionPresentsBer1WithTrack() = runTest {
        val expression = testV2Expression()
        val track = CapsuleTrackSnapshotV1.parse(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            "Title",
            "Artist",
            null,
            null,
        )
        val manifestPhotos = expression.input.photos.mapIndexed { index, photo ->
            ManifestPhoto(
                blobId = UUID.nameUUIDFromBytes("blob-$index".toByteArray()),
                ordinal = index,
                width = photo.widthPx,
                height = photo.heightPx,
            )
        }
        val reader = PendingAdmittedPhotosReader(
            capsule,
            PendingAdmittedContent.OpenedPhotos(
                photosByOrdinal = photos(3),
                note = null,
                manifest = ContentManifestContent(
                    protocolVersion = 2,
                    photos = manifestPhotos,
                    note = null,
                    expression = ContentExpression(
                        candidateId = "cand-1",
                        projectionHash = "a".repeat(64),
                        expression = expression,
                    ),
                    trackSnapshot = track,
                ),
            ),
        )
        val admission = reader.presentationAdmission(capsule)
        assertTrue(admission is CapsulePresentationAdmission.Ber1)
        assertEquals(
            expression,
            (admission as CapsulePresentationAdmission.Ber1).expression,
        )
        assertEquals(track, reader.trackSnapshot(capsule))
        assertEquals(3, reader.photoCount(capsule))
        assertNull(reader.noteText(capsule))
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
