package dev.hryshyn.remanence.index

import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import java.security.MessageDigest

/**
 * The only plaintext shape A12a permits to enter the local sender index.
 * Arrays are copied on input and output so this object cannot alias the A11b
 * verified payload or expose mutable material through a capability.
 */
class SenderIndexBundlePlaintext internal constructor(
    val localFormatVersion: Int,
    val capsuleId: CapsuleId,
    val senderHandleSnapshot: String,
    val createdAtEpochSeconds: Long,
    val placeLabel: String?,
    frontFingerprint: ByteArray,
    backFingerprint: ByteArray,
) {
    private val frontFingerprintSnapshot = frontFingerprint.copyOf()
    private val backFingerprintSnapshot = backFingerprint.copyOf()

    val frontFingerprint: ByteArray
        get() = frontFingerprintSnapshot.copyOf()

    val backFingerprint: ByteArray
        get() = backFingerprintSnapshot.copyOf()

    /** Clears only the private mutable byte material owned by this value. */
    internal fun wipe() {
        frontFingerprintSnapshot.fill(0)
        backFingerprintSnapshot.fill(0)
    }

    override fun toString(): String = "SenderIndexBundlePlaintext(<redacted>)"

    internal fun semanticallyEquals(other: SenderIndexBundlePlaintext): Boolean =
        localFormatVersion == other.localFormatVersion &&
            capsuleId == other.capsuleId &&
            senderHandleSnapshot == other.senderHandleSnapshot &&
            createdAtEpochSeconds == other.createdAtEpochSeconds &&
            placeLabel == other.placeLabel &&
            MessageDigest.isEqual(frontFingerprintSnapshot, other.frontFingerprintSnapshot) &&
            MessageDigest.isEqual(backFingerprintSnapshot, other.backFingerprintSnapshot)

    internal fun encodeBytes(codec: SenderIndexBundleCodec): ByteArray = codec.encode(this)

    companion object {
        /** Builds the local shape only from the already verified A11b output. */
        fun fromVerifiedRecognition(
            expectedCapsuleId: CapsuleId,
            recognition: RecognitionManifestContent,
        ): SenderIndexBundlePlaintext {
            require(recognition.protocolVersion == ProtocolV1Limits.PROTOCOL_VERSION) {
                "unsupported recognition protocol"
            }
            require(recognition.capsuleIdRaw.size == UUID_BYTES) {
                "recognition capsule id is malformed"
            }
            val recognitionCapsule = CapsuleId.fromProtoBytes(
                ByteString.copyFrom(recognition.capsuleIdRaw),
            )
            require(recognitionCapsule == expectedCapsuleId) {
                "recognition capsule id does not match request"
            }
            require(recognition.createdAtEpochSeconds >= 0L) {
                "recognition timestamp is invalid"
            }
            require(NormalizedHandle.parse(recognition.senderHandleSnapshot).value == recognition.senderHandleSnapshot) {
                "recognition handle is not canonical"
            }
            recognition.placeLabel?.let { label ->
                require(label.isNotEmpty()) { "place label is empty" }
                require(label.toByteArray(Charsets.UTF_8).size <= ProtocolV1Limits.PLACE_LABEL_MAX_UTF8_BYTES) {
                    "place label exceeds protocol limit"
                }
            }

            validateFingerprint(recognition.frontFingerprint, FingerprintSide.FRONT)
            validateFingerprint(recognition.backFingerprint, FingerprintSide.BACK)

            return SenderIndexBundlePlaintext(
                localFormatVersion = SenderIndexBundleCodec.FORMAT_VERSION,
                capsuleId = expectedCapsuleId,
                senderHandleSnapshot = recognition.senderHandleSnapshot,
                createdAtEpochSeconds = recognition.createdAtEpochSeconds,
                placeLabel = recognition.placeLabel,
                frontFingerprint = recognition.frontFingerprint,
                backFingerprint = recognition.backFingerprint,
            )
        }

        internal fun validateFingerprint(bytes: ByteArray, expectedSide: FingerprintSide) {
            require(bytes.isNotEmpty()) { "fingerprint is empty" }
            require(bytes.size <= ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES) {
                "fingerprint exceeds bounded recognition payload size"
            }
            val parsed = FingerprintCodec.parse(bytes)
            require(parsed.side == expectedSide) { "fingerprint side does not match" }
            require(parsed.profileId == RecognitionProfile.MVP_ORB_V1_ID) {
                "unsupported recognition profile"
            }
            // Unknown protobuf fields are not part of the canonical local
            // representation and must not survive into the sealed bundle.
            require(FingerprintCodec.serialize(parsed).contentEquals(bytes)) {
                "fingerprint encoding is not canonical"
            }
        }

        private const val UUID_BYTES = 16
    }
}

/**
 * Deterministic manual protobuf wire encoding for the local bundle. It is a
 * private, versioned schema rather than a new wire-protocol message or Room
 * serialization. Tags 1..7, their wire types, fixed order, required fields,
 * and bounded values are the canonical contract; unknown, duplicate, wrong
 * wire-type, and non-canonical/trailing fields are rejected on decode.
 */
class SenderIndexBundleCodec {

    fun encode(bundle: SenderIndexBundlePlaintext): ByteArray {
        require(bundle.localFormatVersion == FORMAT_VERSION) { "unsupported local bundle version" }
        val handleBytes = bundle.senderHandleSnapshot.toByteArray(Charsets.UTF_8)
        val placeBytes = bundle.placeLabel?.toByteArray(Charsets.UTF_8)
        val front = bundle.frontFingerprint
        val back = bundle.backFingerprint
        try {
            require(handleBytes.isNotEmpty()) { "sender handle is empty" }
            require(handleBytes.size <= ProtocolV1Limits.HANDLE_MAX_ASCII_CHARS) {
                "sender handle exceeds protocol limit"
            }
            require(placeBytes == null || (placeBytes.isNotEmpty() &&
                placeBytes.size <= ProtocolV1Limits.PLACE_LABEL_MAX_UTF8_BYTES)) {
                "place label exceeds protocol limit"
            }
            require(bundle.createdAtEpochSeconds >= 0L) { "timestamp is invalid" }
            require(front.isNotEmpty() && back.isNotEmpty()) { "fingerprints are incomplete" }
            require(front.size <= MAX_FINGERPRINT_BYTES && back.size <= MAX_FINGERPRINT_BYTES) {
                "fingerprint exceeds local bundle limit"
            }

            val size = com.google.protobuf.CodedOutputStream.computeUInt32Size(1, FORMAT_VERSION) +
                com.google.protobuf.CodedOutputStream.computeBytesSize(2, bundle.capsuleId.toProtoBytes()) +
                com.google.protobuf.CodedOutputStream.computeStringSize(3, bundle.senderHandleSnapshot) +
                com.google.protobuf.CodedOutputStream.computeInt64Size(4, bundle.createdAtEpochSeconds) +
                (placeBytes?.let { com.google.protobuf.CodedOutputStream.computeStringSize(5, bundle.placeLabel!!) } ?: 0) +
                com.google.protobuf.CodedOutputStream.computeByteArraySize(6, front) +
                com.google.protobuf.CodedOutputStream.computeByteArraySize(7, back)
            require(size <= MAX_PLAINTEXT_BYTES) { "local bundle exceeds bounded size" }

            val output = ByteArray(size)
            val coded = com.google.protobuf.CodedOutputStream.newInstance(output)
            coded.writeUInt32(1, FORMAT_VERSION)
            coded.writeBytes(2, bundle.capsuleId.toProtoBytes())
            coded.writeString(3, bundle.senderHandleSnapshot)
            coded.writeInt64(4, bundle.createdAtEpochSeconds)
            if (placeBytes != null) coded.writeString(5, bundle.placeLabel!!)
            coded.writeByteArray(6, front)
            coded.writeByteArray(7, back)
            coded.flush()
            return output
        } finally {
            front.fill(0)
            back.fill(0)
            handleBytes.fill(0)
            placeBytes?.fill(0)
        }
    }

    fun decode(bytes: ByteArray): SenderIndexBundlePlaintext {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PLAINTEXT_BYTES) {
            "local bundle bytes are outside the bounded format"
        }
        var version: Int? = null
        var capsule: CapsuleId? = null
        var handle: String? = null
        var createdAt: Long? = null
        var place: String? = null
        var front: ByteArray? = null
        var back: ByteArray? = null
        val input = com.google.protobuf.CodedInputStream.newInstance(bytes)
        try {
            while (!input.isAtEnd) {
                when (val tag = input.readTag()) {
                0 -> break
                8 -> {
                    require(version == null) { "duplicate local bundle version" }
                    version = input.readUInt32()
                }
                18 -> {
                    require(capsule == null) { "duplicate local bundle capsule" }
                    val raw = input.readBytes()
                    require(raw.size() == 16) { "malformed local bundle capsule" }
                    capsule = CapsuleId.fromProtoBytes(raw)
                }
                26 -> {
                    require(handle == null) { "duplicate local bundle handle" }
                    handle = input.readStringRequireUtf8()
                }
                32 -> {
                    require(createdAt == null) { "duplicate local bundle timestamp" }
                    createdAt = input.readInt64()
                }
                42 -> {
                    require(place == null) { "duplicate local bundle place" }
                    place = input.readStringRequireUtf8()
                }
                50 -> {
                    require(front == null) { "duplicate local bundle front fingerprint" }
                    val value = input.readByteArray()
                    require(value.size <= MAX_FINGERPRINT_BYTES) { "front fingerprint is too large" }
                    front = value
                }
                58 -> {
                    require(back == null) { "duplicate local bundle back fingerprint" }
                    val value = input.readByteArray()
                    require(value.size <= MAX_FINGERPRINT_BYTES) { "back fingerprint is too large" }
                    back = value
                }
                else -> throw IllegalArgumentException("unknown local bundle field $tag")
                }
            }
            require(version == FORMAT_VERSION && capsule != null && handle != null && createdAt != null &&
                front != null && back != null) { "local bundle is incomplete" }
            val decoded = SenderIndexBundlePlaintext(
                localFormatVersion = version!!,
                capsuleId = capsule!!,
                senderHandleSnapshot = handle!!,
                createdAtEpochSeconds = createdAt!!,
                placeLabel = place,
                frontFingerprint = front!!,
                backFingerprint = back!!,
            )
            try {
                require(NormalizedHandle.parse(decoded.senderHandleSnapshot).value == decoded.senderHandleSnapshot) {
                    "local bundle handle is not canonical"
                }
                require(decoded.createdAtEpochSeconds >= 0L) { "local bundle timestamp is invalid" }
                decoded.placeLabel?.let { label ->
                    require(label.isNotEmpty() && label.toByteArray(Charsets.UTF_8).size <= ProtocolV1Limits.PLACE_LABEL_MAX_UTF8_BYTES) {
                        "local bundle place is invalid"
                    }
                }
                SenderIndexBundlePlaintext.validateFingerprint(decoded.frontFingerprint, FingerprintSide.FRONT)
                SenderIndexBundlePlaintext.validateFingerprint(decoded.backFingerprint, FingerprintSide.BACK)
                require(encode(decoded).contentEquals(bytes)) { "local bundle encoding is not canonical" }
                return decoded
            } catch (failure: Exception) {
                decoded.wipe()
                throw failure
            } finally {
                front?.fill(0)
                back?.fill(0)
            }
        } catch (failure: Exception) {
            // A malformed bundle can fail before the decoded holder exists;
            // still clear any raw fingerprint buffers already extracted.
            front?.fill(0)
            back?.fill(0)
            throw failure
        }
    }

    companion object {
        const val FORMAT_VERSION: Int = 1
        const val MAX_FINGERPRINT_BYTES: Int =
            ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES.toInt()
        const val MAX_PLAINTEXT_BYTES: Int = 2 * MAX_FINGERPRINT_BYTES + 16 * 1024
    }
}

/** Domain-separated, length-delimited AAD for the local sealed bundle. */
internal object SenderIndexBundleAad {
    private const val DOMAIN = "Remanence/local-index-bundle"

    fun encode(owner: UserId, capsule: CapsuleId, formatVersion: Int): ByteArray {
        val ownerBytes = owner.toProtoBytes()
        val capsuleBytes = capsule.toProtoBytes()
        val size = com.google.protobuf.CodedOutputStream.computeStringSizeNoTag(DOMAIN) +
            com.google.protobuf.CodedOutputStream.computeUInt32SizeNoTag(formatVersion) +
            com.google.protobuf.CodedOutputStream.computeBytesSizeNoTag(ownerBytes) +
            com.google.protobuf.CodedOutputStream.computeBytesSizeNoTag(capsuleBytes)
        val output = ByteArray(size)
        val coded = com.google.protobuf.CodedOutputStream.newInstance(output)
        coded.writeStringNoTag(DOMAIN)
        coded.writeUInt32NoTag(formatVersion)
        coded.writeBytesNoTag(ownerBytes)
        coded.writeBytesNoTag(capsuleBytes)
        coded.flush()
        return output
    }
}
