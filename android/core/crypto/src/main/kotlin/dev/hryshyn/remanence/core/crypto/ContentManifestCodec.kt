package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.protocol.v1.ContentManifest
import dev.hryshyn.remanence.protocol.v1.ExpressionPlacement
import dev.hryshyn.remanence.protocol.v1.ExpressionSource
import dev.hryshyn.remanence.protocol.v1.ExpressionV1
import dev.hryshyn.remanence.protocol.v1.PhotoEntry
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import dev.hryshyn.remanence.core.model.ArtifactAadInput
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CryptoContextEncoder
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorExpressionProjection

/** One decrypted photo descriptor in manifest order (sorted ordinal). */
data class ManifestPhoto(
    val blobId: UUID,
    val ordinal: Int,
    val width: Int,
    val height: Int,
)

/** ADR-018 v2: decrypted authenticated expression artifact. */
data class ContentExpression(
    val candidateId: String,
    val projectionHash: String,
    val expression: GeneratorExpression.ResolvedExpression,
)

/** Locally decrypted view of the content manifest (v1 or v2). */
data class ContentManifestContent(
    val protocolVersion: Int,
    val photos: List<ManifestPhoto>,
    val note: String?,
    val expression: ContentExpression? = null,
)

/**
 * Builds and encrypts the content manifest (docs/security.md section 6.2).
 * v1 output is byte-frozen; [buildAndEncryptV2] adds the ADR-018 expression
 * artifact as an optional field inside the same AEAD-sealed manifest. A present
 * `TrackAttachment` always fails closed.
 */
class ContentManifestCodec {

    fun buildAndEncrypt(
        capsuleKeyset: KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        photos: List<ManifestPhoto>,
        note: String?,
    ): ByteArray = build(capsuleKeyset, routingContext, photos, note, null, null)

    /**
     * ADR-018 v2: same envelope, protocol_version = 2, plus the authenticated
     * expression artifact. [blobIdByContentId] maps each G1 photo content id to
     * its exact encrypted photo blob id; the projected hash is computed here.
     */
    fun buildAndEncryptV2(
        capsuleKeyset: KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        photos: List<ManifestPhoto>,
        note: String?,
        candidateId: String,
        expression: GeneratorExpression.ResolvedExpression,
        blobIdByContentId: Map<String, ByteArray>,
    ): ByteArray {
        require(expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V2) {
            "expression artifact requires a v2 expression"
        }
        require(candidateId.isNotBlank() && candidateId.length <= MAX_CANDIDATE_ID_CHARS) {
            "expression candidate id invalid"
        }
        require(
            GeneratorExpression.validateResolved(expression) is GeneratorExpression.InputValidation.Valid,
        ) { "expression artifact requires a structurally valid expression" }
        val projectionHash = GeneratorExpressionProjection.hash(expression, candidateId, blobIdByContentId)
        return build(
            capsuleKeyset, routingContext, photos, note,
            Triple(candidateId, projectionHash, expression), blobIdByContentId,
        )
    }

    private fun build(
        capsuleKeyset: KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        photos: List<ManifestPhoto>,
        note: String?,
        expression: Triple<String, String, GeneratorExpression.ResolvedExpression>?,
        blobIdByContentId: Map<String, ByteArray>?,
    ): ByteArray {
        validatePhotos(photos)
        validateNote(note)

        val builder = ContentManifest.newBuilder()
            .setProtocolVersion(if (expression == null) PROTOCOL_VERSION else PROTOCOL_VERSION_V2)
            .setCapsuleId(routingContext.capsuleId.toProtoBytes())
        photos.sortedBy { it.ordinal }.forEach { photo ->
            builder.addPhotos(
                PhotoEntry.newBuilder()
                    .setBlobId(com.google.protobuf.ByteString.copyFrom(longToBytes(photo.blobId)))
                    .setOrdinal(photo.ordinal)
                    .setMediaType(MEDIA_TYPE_JPEG)
                    .setWidth(photo.width)
                    .setHeight(photo.height),
            )
        }
        note?.takeIf { it.isNotEmpty() }?.let(builder::setNote)
        expression?.let { (candidateId, projectionHash, resolved) ->
            builder.setExpression(
                toProtoExpression(
                    candidateId,
                    projectionHash,
                    resolved,
                    blobIdByContentId ?: emptyMap(),
                ),
            )
        }
        // TrackAttachment is deliberately left unset in MVP.

        return CapsuleArtifactCryptor().encrypt(
            capsuleKeyset = capsuleKeyset,
            context = contentContext(routingContext),
            plaintext = builder.build().toByteArray(),
        )
    }

    fun decryptAndParse(
        capsuleKeyset: KeysetHandle,
        routingContext: RecognitionManifestCodec.RoutingContext,
        ciphertext: ByteArray,
    ): ContentManifestContent = try {
        val bytes = CapsuleArtifactCryptor().decrypt(
            capsuleKeyset,
            contentContext(routingContext),
            ciphertext,
        )
        val manifest = ContentManifest.parseFrom(bytes)
        if (manifest.protocolVersion != PROTOCOL_VERSION &&
            manifest.protocolVersion != PROTOCOL_VERSION_V2
        ) {
            throw GeneralSecurityException("unsupported content manifest version")
        }
        val expectedCapsuleId = routingContext.capsuleId.toProtoBytes()
        if (manifest.capsuleId.size() != expectedCapsuleId.size() ||
            !MessageDigest.isEqual(manifest.capsuleId.toByteArray(), expectedCapsuleId.toByteArray())
        ) {
            throw GeneralSecurityException("content manifest capsule id does not match routing")
        }
        if (manifest.hasTrack()) throw GeneralSecurityException("track attachment forbidden in v1")
        if (manifest.hasNote() &&
            manifest.note.toByteArray(Charsets.UTF_8).size > MAX_NOTE_BYTES
        ) {
            throw GeneralSecurityException("content manifest note exceeds byte limit")
        }

        val parsed = manifest.photosList.map { entry ->
            if (entry.mediaType != MEDIA_TYPE_JPEG) {
                throw GeneralSecurityException("content manifest photo media type is not JPEG")
            }
            val photoBytes = entry.blobId.toByteArray()
            if (photoBytes.size != PHOTO_BLOB_ID_BYTES) {
                throw GeneralSecurityException("content manifest photo blob id has invalid length")
            }
            val buffer = java.nio.ByteBuffer.wrap(photoBytes)
            ManifestPhoto(
                blobId = UUID(buffer.long, buffer.long),
                ordinal = entry.ordinal,
                width = entry.width,
                height = entry.height,
            )
        }
        if (parsed.size !in MIN_PHOTOS..MAX_PHOTOS) {
            throw GeneralSecurityException("content manifest photo count out of v1 range")
        }
        val ordinals = parsed.map { it.ordinal }
        if (ordinals != (0 until parsed.size).toList()) {
            throw GeneralSecurityException("content manifest photo ordinals are not a 0-based sequence")
        }
        if (parsed.map { it.blobId }.toSet().size != parsed.size) {
            throw GeneralSecurityException("content manifest photo blob ids are not unique")
        }
        parsed.forEach {
            if (it.width !in 1..MAX_DIMENSION_PX || it.height !in 1..MAX_DIMENSION_PX) {
                throw GeneralSecurityException("content manifest photo dimensions out of range")
            }
        }

        val note = if (manifest.hasNote()) manifest.note else null
        if (manifest.protocolVersion == PROTOCOL_VERSION) {
            // ADR-018 contract hygiene: a v1 manifest must NOT silently drop a
            // present v2 expression artifact (field 6). Unknown/extra v2-only
            // fields on a v1 frame are a version-confusion attempt, so reject
            // rather than render a v1 layout over a v2-intended capsule.
            if (manifest.hasExpression()) {
                throw GeneralSecurityException("v1 content manifest must not carry an expression artifact")
            }
            ContentManifestContent(protocolVersion = manifest.protocolVersion, photos = parsed, note = note)
        } else {
            if (!manifest.hasExpression()) {
                throw GeneralSecurityException("v2 content manifest is missing its expression artifact")
            }
            ContentManifestContent(
                protocolVersion = manifest.protocolVersion,
                photos = parsed,
                note = note,
                expression = parseExpression(manifest.expression, parsed, note),
            )
        }
    } catch (failure: GeneralSecurityException) {
        throw failure
    } catch (failure: Exception) {
        throw GeneralSecurityException("content manifest failed structural validation").apply {
            initCause(failure)
        }
    }

    private fun toProtoExpression(
        candidateId: String,
        projectionHash: String,
        expression: GeneratorExpression.ResolvedExpression,
        blobIdByContentId: Map<String, ByteArray>,
    ): ExpressionV1 {
        val builder = ExpressionV1.newBuilder()
            .setExpressionVersion(EXPRESSION_VERSION)
            .setCandidateId(candidateId)
            .setExpressionHash(projectionHash)
            .setCanvasVersion(expression.canvasVersion)
            .setGrammarId(expression.grammarId)
            .setGrammarVersion(expression.grammarVersion)
            .setBranchId(expression.branchId)
            .setNoteTreatment(expression.noteTreatment)
            .setFontVersion(expression.fontVersion)
            .setPaletteVersion(expression.paletteVersion)
        expression.input.photos.forEach { photo ->
            val blobId = blobIdByContentId[photo.contentId]
                ?: throw GeneralSecurityException("expression source is missing its photo blob id")
            if (blobId.size != PHOTO_BLOB_ID_BYTES) {
                throw GeneralSecurityException("expression source blob id has invalid length")
            }
            builder.addSources(
                ExpressionSource.newBuilder()
                    .setOrdinal(photo.ordinal)
                    .setBlobId(com.google.protobuf.ByteString.copyFrom(blobId))
                    .setContentId(photo.contentId)
                    .setContentHash(photo.contentHash)
                    .setWidthPx(photo.widthPx)
                    .setHeightPx(photo.heightPx),
            )
        }
        expression.noteRegion?.let {
            builder.setHasNoteRegion(true)
                .setNrX(it.x).setNrY(it.y).setNrWidth(it.width).setNrHeight(it.height)
        }
        expression.placements.forEach { placement ->
            val p = ExpressionPlacement.newBuilder()
                .setContentId(placement.contentId)
                .setX(placement.x).setY(placement.y)
                .setWidth(placement.width).setHeight(placement.height)
            placement.contentRect?.let { rect ->
                p.setHasContentRect(true)
                    .setRectX(rect.x).setRectY(rect.y)
                    .setRectWidth(rect.width).setRectHeight(rect.height)
            }
            builder.addPlacements(p)
        }
        return builder.build()
    }

    private fun parseExpression(
        proto: ExpressionV1,
        photos: List<ManifestPhoto>,
        note: String?,
    ): ContentExpression {
        if (proto.expressionVersion != EXPRESSION_VERSION) {
            throw GeneralSecurityException("unsupported expression artifact version")
        }
        val candidateId = proto.candidateId
        if (candidateId.isBlank() || candidateId.length > MAX_CANDIDATE_ID_CHARS) {
            throw GeneralSecurityException("expression candidate id invalid")
        }
        if (!EXPRESSION_HASH_RE.matches(proto.expressionHash)) {
            throw GeneralSecurityException("expression projection hash is not canonical hex")
        }
        if (proto.sourcesCount != photos.size || proto.placementsCount != photos.size) {
            throw GeneralSecurityException("expression sources/placements must match the photo count")
        }
        val blobIdByContentId = HashMap<String, ByteArray>(photos.size)
        val sourcePhotos = ArrayList<GeneratorExpression.PhotoRef>(photos.size)
        proto.sourcesList.forEachIndexed { index, source ->
            val blobId = source.blobId.toByteArray()
            if (blobId.size != PHOTO_BLOB_ID_BYTES) {
                throw GeneralSecurityException("expression source blob id has invalid length")
            }
            if (source.ordinal != index) throw GeneralSecurityException("expression source ordinals are not authored order")
            if (!MessageDigest.isEqual(blobId, longToBytes(photos[index].blobId))) {
                throw GeneralSecurityException("expression source blob id does not match manifest photo")
            }
            if (!CONTENT_HASH_RE.matches(source.contentHash)) {
                throw GeneralSecurityException("expression source content hash is not canonical hex")
            }
            if (source.contentId.isBlank()) throw GeneralSecurityException("expression source content id blank")
            if (source.widthPx !in 1..MAX_DIMENSION_PX || source.heightPx !in 1..MAX_DIMENSION_PX) {
                throw GeneralSecurityException("expression source dimensions out of range")
            }
            if (blobIdByContentId.put(source.contentId, blobId) != null) {
                throw GeneralSecurityException("duplicate expression source content id")
            }
            sourcePhotos += GeneratorExpression.PhotoRef(
                contentId = source.contentId,
                ordinal = source.ordinal,
                widthPx = source.widthPx,
                heightPx = source.heightPx,
                contentHash = source.contentHash,
            )
        }
        val placements = proto.placementsList.map { p ->
            val rect = if (p.hasContentRect) {
                GeneratorExpression.ContentRect(p.rectX, p.rectY, p.rectWidth, p.rectHeight)
            } else {
                null
            }
            GeneratorExpression.Placement(
                contentId = p.contentId,
                x = p.x, y = p.y, width = p.width, height = p.height,
                crop = null, maskId = null, contentRect = rect,
            )
        }
        if (placements.map { it.contentId } != sourcePhotos.map { it.contentId }) {
            throw GeneralSecurityException("expression placements must match source order")
        }
        val input = GeneratorExpression.GeneratorInput(
            ownerId = RECEIVER_PLACEHOLDER_OWNER,
            epoch = 0L,
            photos = sourcePhotos,
            note = note?.takeIf { it.isNotEmpty() },
            music = null,
        )
        val region = if (proto.hasNoteRegion) {
            GeneratorExpression.NoteRegion(proto.nrX, proto.nrY, proto.nrWidth, proto.nrHeight)
        } else {
            null
        }
        val expression = GeneratorExpression.ResolvedExpression(
            canvasVersion = proto.canvasVersion,
            grammarId = proto.grammarId,
            grammarVersion = proto.grammarVersion,
            branchId = proto.branchId,
            input = input,
            placements = placements,
            noteTreatment = proto.noteTreatment,
            diagnostics = emptyList(),
            fontVersion = proto.fontVersion,
            paletteVersion = proto.paletteVersion,
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2,
            noteRegion = region,
        )
        if (GeneratorExpression.validateResolved(expression) !is GeneratorExpression.InputValidation.Valid) {
            throw GeneralSecurityException("expression artifact failed validation")
        }
        val recomputed = GeneratorExpressionProjection.hash(expression, candidateId, blobIdByContentId)
        if (!MessageDigest.isEqual(
                recomputed.toByteArray(Charsets.US_ASCII),
                proto.expressionHash.toByteArray(Charsets.US_ASCII),
            )
        ) {
            throw GeneralSecurityException("expression projection hash mismatch")
        }
        return ContentExpression(candidateId = candidateId, projectionHash = proto.expressionHash, expression = expression)
    }

    private fun validatePhotos(photos: List<ManifestPhoto>) {
        if (photos.size !in MIN_PHOTOS..MAX_PHOTOS) {
            throw IllegalArgumentException("exactly $MIN_PHOTOS..$MAX_PHOTOS photos required")
        }
        val ordinals = photos.map { it.ordinal }.sorted()
        if (ordinals != (0 until photos.size).toList()) {
            throw IllegalArgumentException("photo ordinals must be unique and sequential from 0")
        }
        if (photos.map { it.blobId }.toSet().size != photos.size) {
            throw IllegalArgumentException("photo blob ids must be unique")
        }
        photos.forEach {
            require(it.width in 1..MAX_DIMENSION_PX && it.height in 1..MAX_DIMENSION_PX) {
                "photo dimensions out of range"
            }
        }
    }

    private fun validateNote(note: String?) {
        note?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_BYTES) { "note exceeds byte limit" }
        }
    }

    private fun contentContext(routing: RecognitionManifestCodec.RoutingContext): ArtifactAadInput =
        ArtifactAadInput(
            capsuleId = routing.capsuleId,
            blobId = routing.blobId,
            artifactKind = CapsuleArtifactKind.CONTENT_MANIFEST,
            ordinal = NON_PHOTO_ORDINAL,
            senderUserId = routing.senderUserId,
            recipientUserId = routing.recipientUserId,
        )

    private fun longToBytes(uuid: UUID): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    internal companion object {
        const val PROTOCOL_VERSION = 1
        const val PROTOCOL_VERSION_V2 = 2
        const val EXPRESSION_VERSION = 1
        const val MEDIA_TYPE_JPEG = "image/jpeg"
        const val MIN_PHOTOS = 3
        const val MAX_PHOTOS = 5
        const val MAX_DIMENSION_PX = 10_000
        const val MAX_NOTE_BYTES = 1000
        const val MAX_CANDIDATE_ID_CHARS = 256
        const val NON_PHOTO_ORDINAL = -1
        const val PHOTO_BLOB_ID_BYTES = 16
        const val RECEIVER_PLACEHOLDER_OWNER = "receiver"
        val EXPRESSION_HASH_RE = Regex("[0-9a-f]{64}")
        val CONTENT_HASH_RE = Regex("[0-9a-f]{64}")
    }
}
