package dev.hryshyn.remanence.sync

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.crypto.ControlIndexAcceptanceGate
import dev.hryshyn.remanence.core.crypto.ControlIndexAcceptanceInput
import dev.hryshyn.remanence.core.crypto.ControlIndexAcceptanceResult
import dev.hryshyn.remanence.core.crypto.RejectionReason
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.data.db.BlobCacheDao
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeDao
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeEntity
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.identity.SenderKeyResolution
import dev.hryshyn.remanence.identity.TrustedSenderKeyStore
import dev.hryshyn.remanence.index.SenderIndexBundleSenderVerification
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Exact one-capsule input for the A11b acceptance boundary. The file is an
 * already transport-verified recognition ciphertext temporary file; this
 * coordinator never downloads it and never writes plaintext.
 */
class IncomingControlIndexAcceptanceRequest(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    val recognitionCiphertextFile: File,
) {
    override fun toString(): String = "IncomingControlIndexAcceptanceRequest(<redacted>)"
}

/** The current authenticated recipient identity and its private HPKE key. */
class CurrentRecipientEncryptionIdentity(
    val ownerUserId: UserId,
    val activeKeyBundleId: KeyBundleId,
    val encryptionPrivateKeyset: KeysetHandle,
) {
    override fun toString(): String = "CurrentRecipientEncryptionIdentity(<redacted>)"
}

enum class IncomingAcceptanceRetryReason {
    RECIPIENT_KEY_UNAVAILABLE,
    SENDER_KEY_UNAVAILABLE,
    LOCAL_STORAGE_UNAVAILABLE,
}

enum class IncomingAcceptanceRejectionReason {
    NO_AUTHENTICATED_OWNER,
    OWNER_MISMATCH,
    CAPSULE_METADATA_MISSING,
    ENVELOPE_METADATA_MISSING,
    RECOGNITION_METADATA_INVALID,
    MALFORMED_METADATA,
    CAPSULE_STATE_INVALID,
    RECIPIENT_KEY_BUNDLE_MISMATCH,
    ENVELOPE_TRANSPORT_INTEGRITY,
    ENVELOPE_OPEN_FAILED,
    RECOGNITION_TRANSPORT_INTEGRITY,
    RECOGNITION_CIPHERTEXT_INVALID,
    SENDER_KEY_REJECTED,
    ACCEPTANCE_GATE_REJECTED,
    SIGNATURE_INVALID,
    IDENTITY_REJECTED,
    STATEMENT_REJECTED,
    RECOGNITION_BINDING_REJECTED,
    RECOGNITION_CRYPTO_REJECTED,
    RECOGNITION_PAYLOAD_REJECTED,
    ACCOUNT_CHANGED,
}

/**
 * Redacted A11b result. [Verified] contains only the exact outputs already
 * approved by [ControlIndexAcceptanceGate] for the later A11c persistence
 * checkpoint; no path, ciphertext, keyset, token, or server detail crosses
 * this boundary.
 */
sealed interface IncomingControlIndexAcceptanceResult {

    class Verified(
        val statement: dev.hryshyn.remanence.protocol.v1.PublishStatement,
        val recognition: RecognitionManifestContent,
        internal val senderVerification: SenderIndexBundleSenderVerification,
    ) : IncomingControlIndexAcceptanceResult {
        override fun toString(): String = "IncomingControlIndexAcceptanceResult.Verified(<redacted>)"
    }

    data class Retryable(val reason: IncomingAcceptanceRetryReason) :
        IncomingControlIndexAcceptanceResult

    data class Rejected(val reason: IncomingAcceptanceRejectionReason) :
        IncomingControlIndexAcceptanceResult
}

/**
 * One owner/capsule-scoped control/index acceptance coordinator. It performs
 * only the local A11b cryptographic boundary; Room state adoption is A11c.
 */
class IncomingControlIndexAcceptanceCoordinator(
    private val incomingCapsuleDao: IncomingCapsuleDao,
    private val incomingEnvelopeDao: IncomingEnvelopeDao,
    private val blobCacheDao: BlobCacheDao,
    private val currentRecipientIdentity: suspend () -> CurrentRecipientEncryptionIdentity?,
    private val trustedSenderKeys: TrustedSenderKeyStore,
    private val acceptanceGate: ControlIndexAcceptanceGate = ControlIndexAcceptanceGate(),
    private val envelopeCryptor: RecipientEnvelopeCryptor = RecipientEnvelopeCryptor(),
    private val recognitionInputStreamFactory: (File) -> InputStream = { FileInputStream(it) },
) {

    suspend fun accept(
        request: IncomingControlIndexAcceptanceRequest,
    ): IncomingControlIndexAcceptanceResult {
        val initialIdentity = try {
            currentRecipientIdentity()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return IncomingControlIndexAcceptanceResult.Retryable(
                IncomingAcceptanceRetryReason.RECIPIENT_KEY_UNAVAILABLE,
            )
        } ?: return IncomingControlIndexAcceptanceResult.Rejected(
            IncomingAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER,
        )

        if (initialIdentity.ownerUserId != request.ownerUserId) {
            return rejected(IncomingAcceptanceRejectionReason.OWNER_MISMATCH)
        }

        val metadata = try {
            when (val loaded = loadMetadata(request)) {
                MetadataLoadResult.CapsuleMissing -> return rejected(
                    IncomingAcceptanceRejectionReason.CAPSULE_METADATA_MISSING,
                )
                MetadataLoadResult.EnvelopeMissing -> return rejected(
                    IncomingAcceptanceRejectionReason.ENVELOPE_METADATA_MISSING,
                )
                MetadataLoadResult.RecognitionInvalid -> return rejected(
                    IncomingAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
                )
                is MetadataLoadResult.Found -> loaded.metadata
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return retryable(IncomingAcceptanceRetryReason.LOCAL_STORAGE_UNAVAILABLE)
        }

        val parsed = try {
            parseAndValidateMetadata(request, metadata)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return rejected(IncomingAcceptanceRejectionReason.MALFORMED_METADATA)
        }
        if (parsed is ParsedMetadataFailure) return rejected(parsed.reason)
        parsed as ParsedMetadata

        if (initialIdentity.activeKeyBundleId != parsed.recipientKeyBundleId) {
            return rejected(IncomingAcceptanceRejectionReason.RECIPIENT_KEY_BUNDLE_MISMATCH)
        }

        val beforeCrypto = when (val current = readCurrentIdentity()) {
            CurrentIdentityRead.Unavailable -> return retryable(
                IncomingAcceptanceRetryReason.RECIPIENT_KEY_UNAVAILABLE,
            )
            CurrentIdentityRead.SignedOut -> return rejected(
                IncomingAcceptanceRejectionReason.ACCOUNT_CHANGED,
            )
            is CurrentIdentityRead.Available -> current.identity
        }
        if (!beforeCrypto.matches(initialIdentity) ||
            beforeCrypto.ownerUserId != request.ownerUserId ||
            beforeCrypto.activeKeyBundleId != parsed.recipientKeyBundleId
        ) {
            return rejected(IncomingAcceptanceRejectionReason.ACCOUNT_CHANGED)
        }

        val recognitionCiphertext = when (
            val read = readRecognitionCiphertext(parsed, request.recognitionCiphertextFile)
        ) {
            is RecognitionRead.Bytes -> read.bytes
            RecognitionRead.HashMismatch -> return rejected(
                IncomingAcceptanceRejectionReason.RECOGNITION_TRANSPORT_INTEGRITY,
            )
            RecognitionRead.Invalid -> return rejected(
                IncomingAcceptanceRejectionReason.RECOGNITION_CIPHERTEXT_INVALID,
            )
            RecognitionRead.Unavailable -> return retryable(
                IncomingAcceptanceRetryReason.LOCAL_STORAGE_UNAVAILABLE,
            )
        }

        var envelopePlaintext: ByteArray? = null
        var senderVerification: SenderIndexBundleSenderVerification? = null
        return try {
            if (!MessageDigest.isEqual(
                    MessageDigest.getInstance(SHA256).digest(parsed.envelope.hpkeCiphertext),
                    parsed.envelope.transportSha256,
                )
            ) {
                rejected(IncomingAcceptanceRejectionReason.ENVELOPE_TRANSPORT_INTEGRITY)
            } else {
                val openedEnvelope = try {
                    envelopeCryptor.open(
                        recipientEncryptionPrivateKeyset = beforeCrypto.encryptionPrivateKeyset,
                        context = RecipientEnvelopeContextInput(
                            capsuleId = request.capsuleId,
                            senderUserId = parsed.senderUserId,
                            recipientUserId = parsed.recipientUserId,
                            recipientKeyBundleId = parsed.recipientKeyBundleId,
                        ),
                        ciphertext = parsed.envelope.hpkeCiphertext,
                    )
                } catch (_: GeneralSecurityException) {
                    return rejected(IncomingAcceptanceRejectionReason.ENVELOPE_OPEN_FAILED)
                } catch (_: IllegalArgumentException) {
                    return rejected(IncomingAcceptanceRejectionReason.ENVELOPE_OPEN_FAILED)
                }
                envelopePlaintext = openedEnvelope
                val senderKeyset = try {
                    when (val resolution = trustedSenderKeys.senderVerifyingKeyset(
                        parsed.senderUserId,
                        parsed.senderKeyBundleId,
                    )) {
                        is SenderKeyResolution.Trusted -> resolution.verifyingKeyset
                        is SenderKeyResolution.Untrusted -> return rejected(
                            IncomingAcceptanceRejectionReason.SENDER_KEY_REJECTED,
                        )
                        is SenderKeyResolution.Unavailable -> return retryable(
                            IncomingAcceptanceRetryReason.SENDER_KEY_UNAVAILABLE,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return retryable(IncomingAcceptanceRetryReason.SENDER_KEY_UNAVAILABLE)
                }
                senderVerification = try {
                    SenderIndexBundleSenderVerification.fromTrusted(
                        senderUserId = parsed.senderUserId,
                        senderKeyBundleId = parsed.senderKeyBundleId,
                        verifyingKeyset = senderKeyset,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return retryable(IncomingAcceptanceRetryReason.SENDER_KEY_UNAVAILABLE)
                }

                val gateResult = try {
                    acceptanceGate.verify(
                        ControlIndexAcceptanceInput(
                            expectedCapsuleId = request.capsuleId,
                            authenticatedUserId = request.ownerUserId,
                            senderVerifyingKeyset = senderKeyset,
                            expectedSenderKeyBundleId = parsed.senderKeyBundleId,
                            envelopePlaintextBytes = openedEnvelope,
                            statementBytes = parsed.capsule.signedStatementBytes,
                            signature = parsed.capsule.publishSignatureBytes,
                            recognitionBlobId = parsed.recognitionBlobId,
                            recognitionCiphertext = recognitionCiphertext,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return rejected(IncomingAcceptanceRejectionReason.ACCEPTANCE_GATE_REJECTED)
                }

                val finalIdentity = when (val current = readCurrentIdentity()) {
                    CurrentIdentityRead.Unavailable -> return retryable(
                        IncomingAcceptanceRetryReason.RECIPIENT_KEY_UNAVAILABLE,
                    )
                    CurrentIdentityRead.SignedOut -> return rejected(
                        IncomingAcceptanceRejectionReason.ACCOUNT_CHANGED,
                    )
                    is CurrentIdentityRead.Available -> current.identity
                }
                if (!finalIdentity.matches(initialIdentity) ||
                    finalIdentity.ownerUserId != request.ownerUserId ||
                    finalIdentity.activeKeyBundleId != parsed.recipientKeyBundleId
                ) {
                    return rejected(IncomingAcceptanceRejectionReason.ACCOUNT_CHANGED)
                }

                when (gateResult) {
                    is ControlIndexAcceptanceResult.Verified ->
                        IncomingControlIndexAcceptanceResult.Verified(
                            statement = gateResult.statement,
                            recognition = gateResult.recognition,
                            senderVerification = senderVerification!!.copyForHandoff(),
                        )
                    is ControlIndexAcceptanceResult.Rejected ->
                        rejected(gateResult.reason)
                }
            }
        } finally {
            envelopePlaintext?.fill(0)
            recognitionCiphertext.fill(0)
            senderVerification?.wipe()
        }
    }

    private suspend fun readCurrentIdentity(): CurrentIdentityRead = try {
        val identity = currentRecipientIdentity()
        if (identity == null) CurrentIdentityRead.SignedOut else CurrentIdentityRead.Available(identity)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CurrentIdentityRead.Unavailable
    }

    private suspend fun loadMetadata(
        request: IncomingControlIndexAcceptanceRequest,
    ): MetadataLoadResult {
        val capsuleId = request.capsuleId.toRestString()
        val owner = request.ownerUserId.toRestString()
        val capsule = incomingCapsuleDao.getByCapsuleIdAndOwner(capsuleId, owner)
            ?: return MetadataLoadResult.CapsuleMissing
        val envelope = incomingEnvelopeDao.getByCapsuleIdAndOwner(capsuleId, owner)
            ?: return MetadataLoadResult.EnvelopeMissing
        val recognition = blobCacheDao.getAllByCapsuleIdAndOwner(capsuleId, owner)
            .filter { it.kind == CapsuleArtifactKind.RECOGNITION_MANIFEST.name }
        if (recognition.size != 1) return MetadataLoadResult.RecognitionInvalid
        return MetadataLoadResult.Found(RawMetadata(capsule, envelope, recognition.single()))
    }

    private fun parseAndValidateMetadata(
        request: IncomingControlIndexAcceptanceRequest,
        raw: RawMetadata,
    ): ParsedMetadataOrFailure {
        val capsule = raw.capsule
        val envelope = raw.envelope
        val recognition = raw.recognition
        require(capsule.ownerUserId == request.ownerUserId.toRestString())
        require(envelope.ownerUserId == request.ownerUserId.toRestString())
        require(recognition.ownerUserId == request.ownerUserId.toRestString())
        require(capsule.capsuleId == request.capsuleId.toRestString())
        require(envelope.capsuleId == request.capsuleId.toRestString())
        require(recognition.capsuleId == request.capsuleId.toRestString())
        if (capsule.recipientUserId != request.ownerUserId.toRestString()) {
            return ParsedMetadataFailure(IncomingAcceptanceRejectionReason.IDENTITY_REJECTED)
        }
        require(capsule.protocolVersion == ProtocolV1Limits.PROTOCOL_VERSION)
        if (capsule.materialState != LocalMaterialState.DISCOVERED ||
            capsule.serverStatus != READY_STATUS
        ) {
            return ParsedMetadataFailure(IncomingAcceptanceRejectionReason.CAPSULE_STATE_INVALID)
        }

        val senderUserId = UserId.parseRest(capsule.senderUserId)
        val recipientUserId = UserId.parseRest(capsule.recipientUserId)
        val senderKeyBundleId = KeyBundleId.parseRest(capsule.senderSigningKeyBundleId)
        val recipientKeyBundleId = KeyBundleId.parseRest(capsule.recipientEncryptionKeyBundleId)
        val envelopeKeyBundleId = KeyBundleId.parseRest(envelope.recipientKeyBundleId)
        val recognitionBlobId = try {
            dev.hryshyn.remanence.core.model.BlobId.parseRest(recognition.blobId)
        } catch (_: IllegalArgumentException) {
            return ParsedMetadataFailure(IncomingAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID)
        }
        if (envelopeKeyBundleId != recipientKeyBundleId) {
            return ParsedMetadataFailure(IncomingAcceptanceRejectionReason.RECIPIENT_KEY_BUNDLE_MISMATCH)
        }
        try {
            require(recognition.ordinal == null)
            require(recognition.expectedSizeBytes in 1L..ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES)
            require(recognition.expectedSha256.size == SHA256_BYTES)
        } catch (_: IllegalArgumentException) {
            return ParsedMetadataFailure(IncomingAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID)
        }
        require(envelope.hpkeCiphertext.size in 1..ProtocolV1Limits.RECIPIENT_ENVELOPE_MAX_CIPHERTEXT_BYTES)
        require(envelope.transportSha256.size == SHA256_BYTES)

        return ParsedMetadata(
            capsule = capsule,
            envelope = envelope,
            recognition = recognition,
            senderUserId = senderUserId,
            recipientUserId = recipientUserId,
            senderKeyBundleId = senderKeyBundleId,
            recipientKeyBundleId = recipientKeyBundleId,
            recognitionBlobId = recognitionBlobId,
        )
    }

    private fun readRecognitionCiphertext(
        parsed: ParsedMetadata,
        file: File,
    ): RecognitionRead {
        var bytes: ByteArray? = null
        var transferred = false
        return try {
            if (!file.isFile) return RecognitionRead.Unavailable
            val expectedSize = parsed.recognition.expectedSizeBytes
            if (file.length() != expectedSize) return RecognitionRead.Invalid
            if (expectedSize !in 1L..ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES) {
                return RecognitionRead.Invalid
            }
            val allocated = ByteArray(expectedSize.toInt())
            bytes = allocated
            recognitionInputStreamFactory(file).use { input ->
                var offset = 0
                while (offset < allocated.size) {
                    val read = input.read(allocated, offset, allocated.size - offset)
                    if (read < 0) {
                        return RecognitionRead.Invalid
                    }
                    if (read == 0) continue
                    offset += read
                }
                if (input.read() != -1) {
                    return RecognitionRead.Invalid
                }
            }
            if (!MessageDigest.isEqual(
                    MessageDigest.getInstance(SHA256).digest(allocated),
                    parsed.recognition.expectedSha256,
                )
            ) {
                RecognitionRead.HashMismatch
            } else {
                transferred = true
                RecognitionRead.Bytes(allocated)
            }
        } catch (_: IOException) {
            RecognitionRead.Unavailable
        } catch (_: SecurityException) {
            RecognitionRead.Unavailable
        } catch (_: OutOfMemoryError) {
            RecognitionRead.Invalid
        } finally {
            if (!transferred) bytes?.fill(0)
        }
    }

    private fun CurrentRecipientEncryptionIdentity.matches(
        other: CurrentRecipientEncryptionIdentity,
    ): Boolean = ownerUserId == other.ownerUserId && activeKeyBundleId == other.activeKeyBundleId

    private fun rejected(reason: IncomingAcceptanceRejectionReason) =
        IncomingControlIndexAcceptanceResult.Rejected(reason)

    private fun rejected(reason: RejectionReason) =
        IncomingControlIndexAcceptanceResult.Rejected(
            reason.toSafeRejectionReason(),
        )

    private fun RejectionReason.toSafeRejectionReason(): IncomingAcceptanceRejectionReason = when (this) {
        RejectionReason.SIGNATURE_INVALID -> IncomingAcceptanceRejectionReason.SIGNATURE_INVALID
        RejectionReason.ID_MISMATCH -> IncomingAcceptanceRejectionReason.IDENTITY_REJECTED
        RejectionReason.STATEMENT_HASH_MISMATCH,
        RejectionReason.MALFORMED_STATEMENT,
        RejectionReason.NON_CANONICAL_BYTES,
        RejectionReason.LAYOUT_INVALID,
        -> IncomingAcceptanceRejectionReason.STATEMENT_REJECTED
        RejectionReason.RECOGNITION_BINDING_INVALID ->
            IncomingAcceptanceRejectionReason.RECOGNITION_BINDING_REJECTED
        RejectionReason.RECOGNITION_AEAD_INVALID ->
            IncomingAcceptanceRejectionReason.RECOGNITION_CRYPTO_REJECTED
        RejectionReason.RECOGNITION_PAYLOAD_INVALID ->
            IncomingAcceptanceRejectionReason.RECOGNITION_PAYLOAD_REJECTED
        else -> IncomingAcceptanceRejectionReason.ACCEPTANCE_GATE_REJECTED
    }

    private fun retryable(reason: IncomingAcceptanceRetryReason) =
        IncomingControlIndexAcceptanceResult.Retryable(reason)

    private data class RawMetadata(
        val capsule: IncomingCapsuleEntity,
        val envelope: IncomingEnvelopeEntity,
        val recognition: dev.hryshyn.remanence.core.data.db.BlobCacheEntity,
    )

    private sealed interface ParsedMetadataOrFailure

    private data class ParsedMetadata(
        val capsule: IncomingCapsuleEntity,
        val envelope: IncomingEnvelopeEntity,
        val recognition: dev.hryshyn.remanence.core.data.db.BlobCacheEntity,
        val senderUserId: UserId,
        val recipientUserId: UserId,
        val senderKeyBundleId: KeyBundleId,
        val recipientKeyBundleId: KeyBundleId,
        val recognitionBlobId: dev.hryshyn.remanence.core.model.BlobId,
    ) : ParsedMetadataOrFailure

    private data class ParsedMetadataFailure(
        val reason: IncomingAcceptanceRejectionReason,
    ) : ParsedMetadataOrFailure

    private sealed interface RecognitionRead {
        data class Bytes(val bytes: ByteArray) : RecognitionRead
        data object HashMismatch : RecognitionRead
        data object Invalid : RecognitionRead
        data object Unavailable : RecognitionRead
    }

    private sealed interface MetadataLoadResult {
        data class Found(val metadata: RawMetadata) : MetadataLoadResult
        data object CapsuleMissing : MetadataLoadResult
        data object EnvelopeMissing : MetadataLoadResult
        data object RecognitionInvalid : MetadataLoadResult
    }

    private sealed interface CurrentIdentityRead {
        data class Available(val identity: CurrentRecipientEncryptionIdentity) : CurrentIdentityRead
        data object SignedOut : CurrentIdentityRead
        data object Unavailable : CurrentIdentityRead
    }

    private companion object {
        const val READY_STATUS = "READY"
        const val SHA256 = "SHA-256"
        const val SHA256_BYTES = 32
    }
}
