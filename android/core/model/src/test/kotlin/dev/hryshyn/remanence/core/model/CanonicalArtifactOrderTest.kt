package dev.hryshyn.remanence.core.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalArtifactOrderTest {

    @Test
    fun comparesKindThenOrdinalThenUnsignedBlobBytes() {
        val recognition = slot(CapsuleArtifactKind.RECOGNITION_MANIFEST, -1, "00000000-0000-4000-8000-000000000001")
        val content = slot(CapsuleArtifactKind.CONTENT_MANIFEST, -1, "00000000-0000-4000-8000-000000000002")
        val photoZero = slot(CapsuleArtifactKind.PHOTO, 0, "00000000-0000-4000-8000-000000000003")
        val photoOne = slot(CapsuleArtifactKind.PHOTO, 1, "00000000-0000-4000-8000-000000000004")
        val unsignedLow = slot(CapsuleArtifactKind.PHOTO, 2, UUID(0x7f00000000000000L, 0L).toString())
        val unsignedHigh = slot(CapsuleArtifactKind.PHOTO, 2, UUID(Long.MIN_VALUE, 0L).toString())

        assertTrue(CanonicalArtifactOrder.compare(recognition, content) < 0)
        assertTrue(CanonicalArtifactOrder.compare(content, photoZero) < 0)
        assertTrue(CanonicalArtifactOrder.compare(photoZero, photoOne) < 0)
        assertTrue(CanonicalArtifactOrder.compare(unsignedLow, unsignedHigh) < 0)
        assertTrue(CanonicalArtifactOrder.isCanonical(listOf(recognition, content, photoZero, photoOne, unsignedLow, unsignedHigh)))
        assertFalse(CanonicalArtifactOrder.isCanonical(listOf(unsignedHigh, unsignedLow)))
    }

    private fun slot(kind: CapsuleArtifactKind, ordinal: Int, blobId: String): ArtifactSlot =
        ArtifactSlot(BlobId.parseRest(blobId), kind, ordinal)
}
