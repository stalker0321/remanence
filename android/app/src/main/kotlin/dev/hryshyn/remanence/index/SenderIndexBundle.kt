package dev.hryshyn.remanence.index

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.Base64
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.identity.CapsuleRoutingPolicy
import dev.hryshyn.remanence.wiring.PreparedIdentity
import java.security.MessageDigest

/** Public-only sender verification material retained by the accepted index. */
class SenderIndexBundleSenderVerification internal constructor(
    val senderUserId: UserId,
    val senderKeyBundleId: KeyBundleId,
    val protocolVersion: Int,
    val suite: String,
    publicKeysetBytes: ByteArray,
) {
    private val publicKeysetBytesSnapshot = publicKeysetBytes.copyOf()

    internal val publicKeysetBytes: ByteArray
        get() = publicKeysetBytesSnapshot.copyOf()

    internal fun copyForHandoff(): SenderIndexBundleSenderVerification =
        SenderIndexBundleSenderVerification(
            senderUserId,
            senderKeyBundleId,
            protocolVersion,
            suite,
            publicKeysetBytesSnapshot,
        )

    internal fun parsePublicKeyset(): KeysetHandle {
        require(protocolVersion == PreparedIdentity.PROTOCOL_VERSION)
        require(suite == PreparedIdentity.SUITE)
        val parsed = CapsuleRoutingPolicy.senderVerifyingKeysetOrNull(
            Base64.urlSafeEncode(publicKeysetBytesSnapshot),
        ) ?: throw IllegalArgumentException("sender verification keyset is invalid")
        val canonicalBytes = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(parsed)
        try {
            require(canonicalBytes.contentEquals(publicKeysetBytesSnapshot)) {
                "sender verification keyset is not canonical"
            }
        } finally {
            canonicalBytes.fill(0)
        }
        return parsed
    }

    internal fun wipe() = publicKeysetBytesSnapshot.fill(0)

    override fun toString(): String = "SenderIndexBundleSenderVerification(<redacted>)"

    companion object {
        internal fun fromTrusted(
            senderUserId: UserId,
            senderKeyBundleId: KeyBundleId,
            verifyingKeyset: KeysetHandle,
        ): SenderIndexBundleSenderVerification = SenderIndexBundleSenderVerification(
            senderUserId = senderUserId,
            senderKeyBundleId = senderKeyBundleId,
            protocolVersion = PreparedIdentity.PROTOCOL_VERSION,
            suite = PreparedIdentity.SUITE,
            publicKeysetBytes = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(verifyingKeyset),
        )
    }
}

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
    senderVerification: SenderIndexBundleSenderVerification?,
) {
    private val frontFingerprintSnapshot = frontFingerprint.copyOf()
    private val senderVerificationSnapshot = senderVerification?.copyForHandoff()

    internal val senderVerification: SenderIndexBundleSenderVerification?
        get() = senderVerificationSnapshot?.copyForHandoff()

    val frontFingerprint: ByteArray
        get() = frontFingerprintSnapshot.copyOf()

    /** Clears only the private mutable byte material owned by this value. */
    internal fun wipe() {
        frontFingerprintSnapshot.fill(0)
        senderVerificationSnapshot?.wipe()
    }

    override fun toString(): String = "SenderIndexBundlePlaintext(<redacted>)"

    internal fun semanticallyEquals(other: SenderIndexBundlePlaintext): Boolean =
        localFormatVersion == other.localFormatVersion &&
            capsuleId == other.capsuleId &&
            senderHandleSnapshot == other.senderHandleSnapshot &&
            createdAtEpochSeconds == other.createdAtEpochSeconds &&
            placeLabel == other.placeLabel &&
            MessageDigest.isEqual(frontFingerprintSnapshot, other.frontFingerprintSnapshot) &&
            verificationEquals(other)

    private fun verificationEquals(other: SenderIndexBundlePlaintext): Boolean {
        val left = senderVerificationSnapshot
        val right = other.senderVerificationSnapshot
        if (left == null || right == null) return left == null && right == null
        val leftBytes = left.publicKeysetBytes
        val rightBytes = right.publicKeysetBytes
        return try {
            left.senderUserId == right.senderUserId &&
                left.senderKeyBundleId == right.senderKeyBundleId &&
                left.protocolVersion == right.protocolVersion &&
                left.suite == right.suite &&
                MessageDigest.isEqual(leftBytes, rightBytes)
        } finally {
            leftBytes.fill(0)
            rightBytes.fill(0)
        }
    }

    internal fun encodeBytes(codec: SenderIndexBundleCodec): ByteArray = codec.encode(this)

    companion object {
        /** Builds the local shape only from the already verified A11b output. */
        internal fun fromVerifiedRecognition(
            expectedCapsuleId: CapsuleId,
            recognition: RecognitionManifestContent,
            senderVerification: SenderIndexBundleSenderVerification,
        ): SenderIndexBundlePlaintext {
            require(recognition.manifestVersion == RecognitionManifestCodec.FORMAT_VERSION) {
                "unsupported recognition manifest format"
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

            validateFingerprint(recognition.frontFingerprint)

            return SenderIndexBundlePlaintext(
                localFormatVersion = SenderIndexBundleCodec.FORMAT_VERSION,
                capsuleId = expectedCapsuleId,
                senderHandleSnapshot = recognition.senderHandleSnapshot,
                createdAtEpochSeconds = recognition.createdAtEpochSeconds,
                placeLabel = recognition.placeLabel,
                frontFingerprint = recognition.frontFingerprint,
                senderVerification = senderVerification,
            )
        }

        internal fun validateFingerprint(bytes: ByteArray) {
            require(bytes.isNotEmpty()) { "fingerprint is empty" }
            require(bytes.size <= ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES) {
                "fingerprint exceeds bounded recognition payload size"
            }
            val parsed = FingerprintCodec.parse(bytes)
            try {
                require(parsed.profileId == RecognitionProfile.MVP_ORB_V1_ID) {
                    "unsupported recognition profile"
                }
                // Unknown protobuf fields are not part of the canonical local
                // representation and must not survive into the sealed bundle.
                val canonicalBytes = FingerprintCodec.serialize(parsed)
                try {
                    require(canonicalBytes.contentEquals(bytes)) {
                        "fingerprint encoding is not canonical"
                    }
                } finally {
                    canonicalBytes.fill(0)
                }
            } finally {
                parsed.wipe()
            }
        }

        private const val UUID_BYTES = 16
    }
}

/**
 * Deterministic manual protobuf wire encoding for the local bundle. It is a
 * private, versioned schema rather than a new wire-protocol message or Room
 * serialization. Tags 1..6 are the required front-only recognition fields;
 * tags 7..11 carry the directory-verified public sender key and its immutable
 * binding metadata. Their wire types, fixed order, required fields, and
 * bounded values are the canonical contract; unknown, duplicate, wrong
 * wire-type, and non-canonical/trailing fields are rejected on decode.
 */
class SenderIndexBundleCodec {

    fun encode(bundle: SenderIndexBundlePlaintext): ByteArray {
        require(bundle.localFormatVersion == FORMAT_VERSION) { "unsupported local bundle version" }
        val handleBytes = bundle.senderHandleSnapshot.toByteArray(Charsets.UTF_8)
        val placeBytes = bundle.placeLabel?.toByteArray(Charsets.UTF_8)
        val front = bundle.frontFingerprint
        val senderVerification = bundle.senderVerification
        val senderPublicKeyset = senderVerification?.publicKeysetBytes
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
            require(front.isNotEmpty()) { "front fingerprint is incomplete" }
            require(front.size <= MAX_FINGERPRINT_BYTES) {
                "fingerprint exceeds local bundle limit"
            }
            require(senderVerification != null) { "sender verification material is missing" }
            val publicKeyset = senderPublicKeyset ?: error("sender verification material is missing")
            require(senderVerification.protocolVersion == PreparedIdentity.PROTOCOL_VERSION)
            require(senderVerification.suite == PreparedIdentity.SUITE)
            senderVerification.parsePublicKeyset()

            val size = com.google.protobuf.CodedOutputStream.computeUInt32Size(
                1,
                bundle.localFormatVersion,
            ) +
                com.google.protobuf.CodedOutputStream.computeBytesSize(2, bundle.capsuleId.toProtoBytes()) +
                com.google.protobuf.CodedOutputStream.computeStringSize(3, bundle.senderHandleSnapshot) +
                com.google.protobuf.CodedOutputStream.computeInt64Size(4, bundle.createdAtEpochSeconds) +
                (placeBytes?.let { com.google.protobuf.CodedOutputStream.computeStringSize(5, bundle.placeLabel!!) } ?: 0) +
                com.google.protobuf.CodedOutputStream.computeByteArraySize(6, front) +
                (senderVerification?.let {
                    com.google.protobuf.CodedOutputStream.computeBytesSize(7, it.senderUserId.toProtoBytes()) +
                        com.google.protobuf.CodedOutputStream.computeBytesSize(8, it.senderKeyBundleId.toProtoBytes()) +
                        com.google.protobuf.CodedOutputStream.computeUInt32Size(9, it.protocolVersion) +
                        com.google.protobuf.CodedOutputStream.computeStringSize(10, it.suite) +
                        com.google.protobuf.CodedOutputStream.computeByteArraySize(11, publicKeyset)
                } ?: 0)
            require(size <= MAX_PLAINTEXT_BYTES) { "local bundle exceeds bounded size" }

            val output = ByteArray(size)
            val coded = com.google.protobuf.CodedOutputStream.newInstance(output)
            coded.writeUInt32(1, bundle.localFormatVersion)
            coded.writeBytes(2, bundle.capsuleId.toProtoBytes())
            coded.writeString(3, bundle.senderHandleSnapshot)
            coded.writeInt64(4, bundle.createdAtEpochSeconds)
            if (placeBytes != null) coded.writeString(5, bundle.placeLabel!!)
            coded.writeByteArray(6, front)
            senderVerification?.let {
                coded.writeBytes(7, it.senderUserId.toProtoBytes())
                coded.writeBytes(8, it.senderKeyBundleId.toProtoBytes())
                coded.writeUInt32(9, it.protocolVersion)
                coded.writeString(10, it.suite)
                coded.writeByteArray(11, publicKeyset)
            }
            coded.flush()
            return output
        } finally {
            front.fill(0)
            handleBytes.fill(0)
            placeBytes?.fill(0)
            senderPublicKeyset?.fill(0)
            senderVerification?.wipe()
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
        var senderUser: UserId? = null
        var senderBundle: KeyBundleId? = null
        var senderProtocol: Int? = null
        var senderSuite: String? = null
        var senderPublicKeyset: ByteArray? = null
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
                    require(senderUser == null) { "duplicate sender user" }
                    val raw = input.readBytes()
                    require(raw.size() == UUID_BYTES) { "malformed sender user" }
                    senderUser = UserId.fromProtoBytes(raw)
                }
                66 -> {
                    require(senderBundle == null) { "duplicate sender bundle" }
                    val raw = input.readBytes()
                    require(raw.size() == UUID_BYTES) { "malformed sender bundle" }
                    senderBundle = KeyBundleId.fromProtoBytes(raw)
                }
                72 -> {
                    require(senderProtocol == null) { "duplicate sender protocol" }
                    senderProtocol = input.readUInt32()
                }
                82 -> {
                    require(senderSuite == null) { "duplicate sender suite" }
                    senderSuite = input.readStringRequireUtf8()
                }
                90 -> {
                    require(senderPublicKeyset == null) { "duplicate sender public keyset" }
                    val value = input.readByteArray()
                    require(value.isNotEmpty() && value.size <= MAX_PUBLIC_KEYSET_BYTES)
                    senderPublicKeyset = value
                }
                else -> throw IllegalArgumentException("unknown local bundle field $tag")
                }
            }
            require(version == FORMAT_VERSION &&
                capsule != null && handle != null && createdAt != null &&
                front != null && senderUser != null && senderBundle != null && senderProtocol != null &&
                senderSuite != null && senderPublicKeyset != null) { "local bundle is incomplete" }
            val senderVerification = SenderIndexBundleSenderVerification(
                senderUser,
                senderBundle,
                senderProtocol,
                senderSuite,
                senderPublicKeyset,
            )
            try {
                senderVerification.parsePublicKeyset()
                val decoded = SenderIndexBundlePlaintext(
                    localFormatVersion = version!!,
                    capsuleId = capsule!!,
                    senderHandleSnapshot = handle!!,
                    createdAtEpochSeconds = createdAt!!,
                    placeLabel = place,
                    frontFingerprint = front!!,
                    senderVerification = senderVerification,
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
                    SenderIndexBundlePlaintext.validateFingerprint(decoded.frontFingerprint)
                    val canonicalBytes = encode(decoded)
                    try {
                        require(canonicalBytes.contentEquals(bytes)) { "local bundle encoding is not canonical" }
                    } finally {
                        canonicalBytes.fill(0)
                    }
                    return decoded
                } catch (failure: Exception) {
                    decoded.wipe()
                    throw failure
                }
            } finally {
                senderVerification.wipe()
                front?.fill(0)
            }
        } catch (failure: Exception) {
            // A malformed bundle can fail before the decoded holder exists;
            // still clear any raw fingerprint buffers already extracted.
            front?.fill(0)
            throw failure
        } finally {
            senderPublicKeyset?.fill(0)
        }
    }

    companion object {
        const val FORMAT_VERSION: Int = 3
        const val MAX_FINGERPRINT_BYTES: Int =
            ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES.toInt()
        const val MAX_PUBLIC_KEYSET_BYTES: Int = 16 * 1024
        const val MAX_PLAINTEXT_BYTES: Int = MAX_FINGERPRINT_BYTES + 32 * 1024
        private const val UUID_BYTES = 16
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
