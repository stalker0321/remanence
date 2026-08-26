package dev.hryshyn.remanence.core.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ArtifactLayoutTest {
    @Test
    fun validThreeAndFivePhotosPreserveShuffledOrder() {
        val three = listOf(
            photo(3, 2),
            recognition(),
            photo(5, 0),
            content(),
            photo(4, 1),
        )
        assertValidPreserving(three)

        val five = listOf(
            photo(7, 4),
            photo(3, 1),
            recognition(),
            photo(5, 0),
            content(),
            photo(6, 3),
            photo(4, 2),
        )
        assertValidPreserving(five)
    }

    @Test
    fun twoAndSixPhotosArePhotoCardinality() {
        assertInvalid(
            ArtifactLayoutError.PHOTO_CARDINALITY,
            recognition(),
            content(),
            photo(3, 0),
            photo(4, 1),
        )
        assertInvalid(
            ArtifactLayoutError.PHOTO_CARDINALITY,
            recognition(),
            content(),
            photo(3, 0),
            photo(4, 1),
            photo(5, 2),
            photo(6, 3),
            photo(7, 4),
            photo(8, 5),
        )
    }

    @Test
    fun duplicateBlobIdWinsOverOtherErrors() {
        val duplicate = blob(3)
        assertInvalid(
            ArtifactLayoutError.DUPLICATE_BLOB_ID,
            ArtifactSlot(duplicate, CapsuleArtifactKind.PHOTO, 0),
            ArtifactSlot(duplicate, CapsuleArtifactKind.PHOTO, 1),
        )
    }

    @Test
    fun missingOrDuplicateManifestsMatchCardinalityReason() {
        assertInvalid(
            ArtifactLayoutError.RECOGNITION_MANIFEST_CARDINALITY,
            content(),
            photo(3, 0),
            photo(4, 1),
            photo(5, 2),
        )
        assertInvalid(
            ArtifactLayoutError.RECOGNITION_MANIFEST_CARDINALITY,
            recognition(),
            recognition(9),
            content(),
            photo(3, 0),
            photo(4, 1),
            photo(5, 2),
        )
        assertInvalid(
            ArtifactLayoutError.CONTENT_MANIFEST_CARDINALITY,
            recognition(),
            photo(3, 0),
            photo(4, 1),
            photo(5, 2),
        )
        assertInvalid(
            ArtifactLayoutError.CONTENT_MANIFEST_CARDINALITY,
            recognition(),
            content(),
            content(9),
            photo(3, 0),
            photo(4, 1),
            photo(5, 2),
        )
    }

    @Test
    fun nonPhotoOrdinalMustBeMinusOne() {
        assertInvalid(
            ArtifactLayoutError.NON_PHOTO_ORDINAL,
            ArtifactSlot(blob(1), CapsuleArtifactKind.RECOGNITION_MANIFEST, 0),
            content(),
            photo(3, 0),
            photo(4, 1),
            photo(5, 2),
        )
        assertInvalid(
            ArtifactLayoutError.NON_PHOTO_ORDINAL,
            recognition(),
            ArtifactSlot(blob(2), CapsuleArtifactKind.CONTENT_MANIFEST, 5),
            photo(3, 0),
            photo(4, 1),
            photo(5, 2),
        )
    }

    @Test
    fun photoOrdinalSequenceRejectsDuplicateGapNegativeAndOutOfCount() {
        assertInvalid(
            ArtifactLayoutError.PHOTO_ORDINAL_SEQUENCE,
            recognition(),
            content(),
            photo(3, 0),
            photo(4, 1),
            photo(5, 1),
        )
        assertInvalid(
            ArtifactLayoutError.PHOTO_ORDINAL_SEQUENCE,
            recognition(),
            content(),
            photo(3, 0),
            photo(4, 1),
            photo(5, 3),
        )
        assertInvalid(
            ArtifactLayoutError.PHOTO_ORDINAL_SEQUENCE,
            recognition(),
            content(),
            photo(3, 0),
            photo(4, 1),
            photo(5, -1),
        )
        assertInvalid(
            ArtifactLayoutError.PHOTO_ORDINAL_SEQUENCE,
            recognition(),
            content(),
            photo(3, 0),
            photo(4, 1),
            photo(5, 4),
        )
        assertInvalid(
            ArtifactLayoutError.PHOTO_ORDINAL_SEQUENCE,
            recognition(),
            content(),
            photo(3, 0),
            photo(4, 3),
            photo(5, 4),
        )
    }

    @Test
    fun validResultIsDefensiveCopy() {
        val mutable = mutableListOf(
            photo(5, 2),
            recognition(),
            photo(3, 0),
            content(),
            photo(4, 1),
        )
        val original = mutable.toList()
        val valid = assertIs<ArtifactLayoutValidation.Valid>(ArtifactLayoutValidator.validate(mutable))
        assertEquals(original, valid.slots)
        assertNotSame(mutable, valid.slots)
        mutable.clear()
        mutable.add(photo(9, 0))
        assertEquals(original, valid.slots)
        assertTrue(valid.slots.none { it.blobId == blob(9) })
    }

    private fun assertValidPreserving(slots: List<ArtifactSlot>) {
        val valid = assertIs<ArtifactLayoutValidation.Valid>(ArtifactLayoutValidator.validate(slots))
        assertEquals(slots, valid.slots)
        assertNotSame(slots, valid.slots)
    }

    private fun assertInvalid(reason: ArtifactLayoutError, vararg slots: ArtifactSlot) {
        val result = ArtifactLayoutValidator.validate(slots.toList())
        assertEquals(ArtifactLayoutValidation.Invalid(reason), result)
    }

    private fun recognition(id: Int = 1): ArtifactSlot =
        ArtifactSlot(blob(id), CapsuleArtifactKind.RECOGNITION_MANIFEST, ProtocolV1Limits.NON_PHOTO_ORDINAL)

    private fun content(id: Int = 2): ArtifactSlot =
        ArtifactSlot(blob(id), CapsuleArtifactKind.CONTENT_MANIFEST, ProtocolV1Limits.NON_PHOTO_ORDINAL)

    private fun photo(id: Int, ordinal: Int): ArtifactSlot =
        ArtifactSlot(blob(id), CapsuleArtifactKind.PHOTO, ordinal)

    private fun blob(id: Int): BlobId {
        val node = id.toString(16).padStart(12, '0')
        return BlobId(UUID.fromString("00112233-4455-6677-8899-$node"))
    }
}
