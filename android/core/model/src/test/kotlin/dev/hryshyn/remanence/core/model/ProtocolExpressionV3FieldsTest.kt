package dev.hryshyn.remanence.core.model

import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.v1.ContentManifest
import dev.hryshyn.remanence.protocol.v1.ExpressionArtwork
import dev.hryshyn.remanence.protocol.v1.ExpressionCrop
import dev.hryshyn.remanence.protocol.v1.ExpressionMusic
import dev.hryshyn.remanence.protocol.v1.ExpressionPlacement
import dev.hryshyn.remanence.protocol.v1.ExpressionSource
import dev.hryshyn.remanence.protocol.v1.ExpressionStyle
import dev.hryshyn.remanence.protocol.v1.ExpressionTextType
import dev.hryshyn.remanence.protocol.v1.ExpressionV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V5 proto slice T1: field-number pins + model <-> proto mapping shape.
 *
 * Scope is deliberately narrow (V5-P1): proto schema numbers, proto3
 * presence, and the [GeneratorExpression.ArtworkRef] <-> [ExpressionArtwork]
 * six-field correspondence. No codec, no admission, no fetch, no bytes.
 * Image BYTES never enter the proto: the only `bytes` field on the artwork
 * message is the 32-byte sha256 descriptor digest.
 */
class ProtocolExpressionV3FieldsTest {

    @Test
    fun v1ArtifactFieldNumbersAreFrozen() {
        assertEquals(1, ExpressionV1.EXPRESSION_VERSION_FIELD_NUMBER)
        assertEquals(2, ExpressionV1.CANDIDATE_ID_FIELD_NUMBER)
        assertEquals(18, ExpressionV1.STYLE_FIELD_NUMBER)
        assertEquals(19, ExpressionV1.NOTE_TYPE_FIELD_NUMBER)
        assertEquals(20, ExpressionV1.MUSIC_FIELD_NUMBER)
        assertEquals(6, ContentManifest.EXPRESSION_FIELD_NUMBER)
        assertEquals(7, ContentManifest.TRACK_SNAPSHOT_FIELD_NUMBER)
    }

    @Test
    fun placementAndSourceFieldNumbersUnchanged() {
        assertEquals(11, ExpressionPlacement.CROP_FIELD_NUMBER)
        assertEquals(12, ExpressionPlacement.MASK_ID_FIELD_NUMBER)
        assertEquals(1, ExpressionSource.ORDINAL_FIELD_NUMBER)
        assertEquals(2, ExpressionSource.BLOB_ID_FIELD_NUMBER)
        assertEquals(3, ExpressionSource.CONTENT_ID_FIELD_NUMBER)
        assertEquals(4, ExpressionSource.CONTENT_HASH_FIELD_NUMBER)
        assertEquals(5, ExpressionSource.WIDTH_PX_FIELD_NUMBER)
        assertEquals(6, ExpressionSource.HEIGHT_PX_FIELD_NUMBER)
    }

    @Test
    fun musicAndArtworkFieldNumbers() {
        assertEquals(1, ExpressionMusic.X_FIELD_NUMBER)
        assertEquals(2, ExpressionMusic.Y_FIELD_NUMBER)
        assertEquals(3, ExpressionMusic.WIDTH_FIELD_NUMBER)
        assertEquals(4, ExpressionMusic.HEIGHT_FIELD_NUMBER)
        assertEquals(5, ExpressionMusic.MODE_FIELD_NUMBER)
        assertEquals(6, ExpressionMusic.TYPE_FIELD_NUMBER)
        assertEquals(7, ExpressionMusic.ARTWORK_FIELD_NUMBER)

        assertEquals(1, ExpressionArtwork.PROVIDER_FIELD_NUMBER)
        assertEquals(2, ExpressionArtwork.URL_FIELD_NUMBER)
        assertEquals(3, ExpressionArtwork.SHA256_FIELD_NUMBER)
        assertEquals(4, ExpressionArtwork.MIME_TYPE_FIELD_NUMBER)
        assertEquals(5, ExpressionArtwork.WIDTH_FIELD_NUMBER)
        assertEquals(6, ExpressionArtwork.HEIGHT_FIELD_NUMBER)
    }

    @Test
    fun styleTextTypeCropFieldNumbers() {
        assertEquals(1, ExpressionStyle.STYLE_ID_FIELD_NUMBER)
        assertEquals(2, ExpressionStyle.STYLE_VERSION_FIELD_NUMBER)
        assertEquals(3, ExpressionStyle.STYLE_DIGEST_FIELD_NUMBER)

        assertEquals(1, ExpressionTextType.FAMILY_FIELD_NUMBER)
        assertEquals(2, ExpressionTextType.SIZE_FIELD_NUMBER)
        assertEquals(3, ExpressionTextType.WEIGHT_FIELD_NUMBER)
        assertEquals(4, ExpressionTextType.ITALIC_FIELD_NUMBER)
        assertEquals(5, ExpressionTextType.LINE_FIELD_NUMBER)

        assertEquals(1, ExpressionCrop.X_FIELD_NUMBER)
        assertEquals(2, ExpressionCrop.Y_FIELD_NUMBER)
        assertEquals(3, ExpressionCrop.WIDTH_FIELD_NUMBER)
        assertEquals(4, ExpressionCrop.HEIGHT_FIELD_NUMBER)
    }

    @Test
    fun proto3PresenceForArtworkAndMaskId() {
        val musicWithoutArtwork = ExpressionMusic.newBuilder()
            .setX(0).setY(0).setWidth(10).setHeight(10)
            .setMode("text")
            .setType(ExpressionTextType.newBuilder().setFamily("StudySans").setSize(16).setWeight(400).setLine(18))
            .build()
        assertFalse(musicWithoutArtwork.hasArtwork())

        val musicWithArtwork = musicWithoutArtwork.toBuilder()
            .setArtwork(artworkProto())
            .build()
        assertTrue(musicWithArtwork.hasArtwork())

        val placementWithoutMask = ExpressionPlacement.newBuilder()
            .setContentId("p1")
            .build()
        assertFalse(placementWithoutMask.hasMaskId())
        assertFalse(placementWithoutMask.hasCrop())
        assertTrue(placementWithoutMask.toBuilder().setMaskId("rect").build().hasMaskId())

        val v1WithoutV2Fields = ExpressionV1.newBuilder()
            .setExpressionVersion(1)
            .setCandidateId("ber1-x")
            .build()
        assertFalse(v1WithoutV2Fields.hasStyle())
        assertFalse(v1WithoutV2Fields.hasNoteType())
        assertFalse(v1WithoutV2Fields.hasMusic())
    }

    @Test
    fun artworkRefMapsFieldForFieldToProto() {
        // T8 hermetic fixture shape; NO fetch is performed.
        val model = GeneratorExpression.ArtworkRef(
            provider = "catalog-sample",
            url = "https://art.example.org/cover/ab12.webp",
            sha256 = ByteArray(32) { it.toByte() },
            mimeType = "image/webp",
            width = 640,
            height = 640,
        )
        val proto = ExpressionArtwork.newBuilder()
            .setProvider(model.provider)
            .setUrl(model.url)
            .setSha256(ByteString.copyFrom(model.sha256))
            .setMimeType(model.mimeType)
            .setWidth(model.width)
            .setHeight(model.height)
            .build()
        val roundTripped = ExpressionArtwork.parseFrom(proto.toByteArray())
        assertEquals(model.provider, roundTripped.provider)
        assertEquals(model.url, roundTripped.url)
        assertTrue(model.sha256.contentEquals(roundTripped.sha256.toByteArray()))
        assertEquals(32, roundTripped.sha256.size())
        assertEquals(model.mimeType, roundTripped.mimeType)
        assertEquals(model.width, roundTripped.width)
        assertEquals(model.height, roundTripped.height)
    }

    private fun artworkProto(): ExpressionArtwork = ExpressionArtwork.newBuilder()
        .setProvider("catalog-sample")
        .setUrl("https://art.example.org/cover/ab12.webp")
        .setSha256(ByteString.copyFrom(ByteArray(32) { it.toByte() }))
        .setMimeType("image/webp")
        .setWidth(640)
        .setHeight(640)
        .build()
}
