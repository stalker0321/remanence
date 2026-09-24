package dev.hryshyn.remanence.core.model

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ADR-018 critical fix: shared deterministic opaque photo identity. */
class CapsulePhotoIdentityTest {

    private val capsuleId = CapsuleId(UUID.fromString("1f0a1234-5678-4abc-9def-aabbccdd2001"))

    private fun hash(c: Char) = c.toString().repeat(64)

    private fun expression(input: GeneratorExpression.GeneratorInput): GeneratorExpression.ResolvedExpression {
        val result = GeneratorEditorialRows.plan(input)
        assertTrue(result is GeneratorEditorialRows.PlanResult.Planned, "got $result")
        return (result as GeneratorEditorialRows.PlanResult.Planned).expression
    }

    private fun input(hashes: List<String>) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = hashes.mapIndexed { index, h ->
            GeneratorExpression.PhotoRef(
                contentId = CapsulePhotoIdentity.contentIdFor(h),
                ordinal = index,
                widthPx = 1000,
                heightPx = 1000,
                contentHash = h,
            )
        },
        note = null,
        music = null,
    )

    @Test
    fun contentIdIsOpaqueDeterministicAndUriFree() {
        val id = CapsulePhotoIdentity.contentIdFor(hash('a'))
        assertEquals(id, CapsulePhotoIdentity.contentIdFor(hash('a')))
        assertTrue(id.startsWith("c"))
        assertTrue(!GeneratorExpression.looksLikeUriOrHandle(id))
        assertFailsWith<IllegalArgumentException> { CapsulePhotoIdentity.contentIdFor("not-hex") }
        assertFailsWith<IllegalArgumentException> { CapsulePhotoIdentity.contentIdFor("A".repeat(64)) }
    }

    @Test
    fun photoBlobIdIsDeterministicAndDistinct() {
        val first = CapsulePhotoIdentity.photoBlobId(capsuleId, 0)
        assertEquals(first, CapsulePhotoIdentity.photoBlobId(capsuleId, 0))
        assertNotEquals(first, CapsulePhotoIdentity.photoBlobId(capsuleId, 1))
        assertNotEquals(
            first,
            CapsulePhotoIdentity.photoBlobId(CapsuleId(UUID.randomUUID()), 0),
        )
        assertEquals(CapsulePhotoIdentity.PHOTO_BLOB_TAG_BASE, first.toProtoBytes().byteAt(0).toInt() and 0xFF)
    }

    @Test
    fun projectedHashMatchesForEquivalentPreviewAndSealedInputs() {
        val hashes = listOf(hash('a'), hash('b'), hash('c'))
        val contentIds = hashes.map { CapsulePhotoIdentity.contentIdFor(it) }
        val expression = expression(input(hashes))
        val preview = CapsulePhotoIdentity.projectedHash(expression, "cand-1", capsuleId, contentIds)
        val sealed = CapsulePhotoIdentity.projectedHash(expression, "cand-1", capsuleId, contentIds)
        assertEquals(preview, sealed)
        assertNotEquals(
            preview,
            CapsulePhotoIdentity.projectedHash(
                expression, "cand-1", CapsuleId(UUID.randomUUID()), contentIds,
            ),
        )
        val reordered = listOf(contentIds[1], contentIds[0], contentIds[2])
        assertNotEquals(
            preview,
            CapsulePhotoIdentity.projectedHash(expression, "cand-1", capsuleId, reordered),
        )
    }
}
