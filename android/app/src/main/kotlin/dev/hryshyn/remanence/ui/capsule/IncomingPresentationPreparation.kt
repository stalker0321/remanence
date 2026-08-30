package dev.hryshyn.remanence.ui.capsule

import dev.hryshyn.remanence.core.crypto.DeliveredCiphertext
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceGate
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceInput
import dev.hryshyn.remanence.core.crypto.PresentationAcceptancePreparationResult
import dev.hryshyn.remanence.core.crypto.PreparedPresentationMaterial
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.data.db.BlobCacheDao
import dev.hryshyn.remanence.core.data.db.BlobCacheEntity
import dev.hryshyn.remanence.core.data.db.BlobCacheState
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleDao
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeDao
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.index.SenderIndexBundleReadResult
import dev.hryshyn.remanence.index.SenderIndexBundleReadRequest
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.protocol.v1.ArtifactKind
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import dev.hryshyn.remanence.sync.CurrentRecipientEncryptionIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Redacted reasons for refusing one local incoming presentation preparation. */
internal enum class IncomingPresentationPreparationRejection {
    NO_AUTHENTICATED_OWNER,
    OWNER_MISMATCH,
    CAPSULE_NOT_PRESENT,
    CAPSULE_STATE_INVALID,
    METADATA_INVALID,
    SENDER_INDEX_INVALID,
    MATERIAL_MISSING,
    MATERIAL_INVALID,
    PRESENTATION_REJECTED,
    ACCOUNT_CHANGED,
}

/** Redacted local availability failures; no network fallback exists here. */
internal enum class IncomingPresentationPreparationUnavailable {
    IDENTITY_UNAVAILABLE,
    DATABASE_UNAVAILABLE,
    LOCAL_STORAGE_UNAVAILABLE,
    SENDER_INDEX_UNAVAILABLE,
}

internal sealed interface IncomingPresentationPreparationResult {
    class Prepared internal constructor(
        val presentation: PreparedIncomingPresentation,
    ) : IncomingPresentationPreparationResult {
        override fun toString(): String =
            "IncomingPresentationPreparationResult.Prepared(<redacted>)"
    }

    data class Rejected(
        val reason: IncomingPresentationPreparationRejection,
    ) : IncomingPresentationPreparationResult

    data class Unavailable(
        val reason: IncomingPresentationPreparationUnavailable,
    ) : IncomingPresentationPreparationResult
}

/** Non-resource marker returned from the IO phase after the outer holder owns the material. */
private class PreparedIncomingPresentationReady : IncomingPresentationPreparationResult {
    override fun toString(): String =
        "IncomingPresentationPreparationResult.PreparedReady(<redacted>)"
}

/**
 * Closeable, scan/grant-independent handle for the next rendering checkpoint.
 * It owns the exact verified ciphertext snapshot and never exposes a path,
 * Room entity, recipient private key, or network/session capability.
 */
internal class PreparedIncomingPresentation internal constructor(
    val ownerUserId: UserId,
    val capsuleId: CapsuleId,
    private val material: PreparedPresentationMaterial,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal val photoCount: Int
        get() = material.photoCount

    internal fun noteText(): String? = material.noteText()

    internal fun loadPhoto(ordinal: Int): ByteArray = material.loadPhoto(ordinal)

    override fun close() {
        if (closed.compareAndSet(false, true)) material.close()
    }

    override fun toString(): String = "PreparedIncomingPresentation(<redacted>)"
}

/** Single-owner holder spanning the IO phase and its dispatch back to the caller. */
private class PreparedIncomingPresentationHolder(
    private val onMaterialClosed: () -> Unit,
) {
    private sealed interface State {
        data object Empty : State
        class Owned(val presentation: PreparedIncomingPresentation) : State
        data object Transferred : State
        data object Closed : State
    }

    private val state = AtomicReference<State>(State.Empty)

    fun store(presentation: PreparedIncomingPresentation): Boolean =
        state.compareAndSet(State.Empty, State.Owned(presentation))

    fun takeForDelivery(): PreparedIncomingPresentation? {
        while (true) {
            when (val current = state.get()) {
                is State.Owned -> if (state.compareAndSet(current, State.Transferred)) {
                    return current.presentation
                }
                else -> return null
            }
        }
    }

    fun closeOwned() {
        val previous = state.getAndSet(State.Closed)
        if (previous is State.Owned) closeValue(previous.presentation)
    }

    fun closeTransferred(presentation: PreparedIncomingPresentation) = closeValue(presentation)

    fun closeDetached(presentation: PreparedIncomingPresentation) = closeValue(presentation)

    private fun closeValue(presentation: PreparedIncomingPresentation) {
        try {
            presentation.close()
        } catch (_: Exception) {
            // Cleanup cannot replace cancellation or the producer result.
        }
        try {
            onMaterialClosed()
        } catch (_: Exception) {
            // Test/diagnostic callbacks cannot affect the producer outcome.
        }
    }
}

/**
 * Owner-scoped offline presentation preparation. This is deliberately
 * independent from [dev.hryshyn.remanence.core.data.db.IncomingSyncSession]:
 * no access token or network repository is needed after local acceptance.
 */
internal class IncomingPresentationPreparation(
    private val incomingCapsuleDao: IncomingCapsuleDao,
    private val incomingEnvelopeDao: IncomingEnvelopeDao,
    private val blobCacheDao: BlobCacheDao,
    private val roots: AccountScopedFileRoots,
    private val senderIndexBundleReader: SenderIndexBundleReader,
    private val currentRecipientIdentity: suspend () -> CurrentRecipientEncryptionIdentity?,
    private val acceptanceGate: PresentationAcceptanceGate = PresentationAcceptanceGate(),
    private val envelopeCryptor: RecipientEnvelopeCryptor = RecipientEnvelopeCryptor(),
    private val beforePreparedResultDelivery: suspend () -> Unit = {},
    private val beforePreparedDelivery:
        (CancellableContinuation<PreparedIncomingPresentation>) -> Unit = {},
    private val onPreparedMaterialClosed: () -> Unit = {},
) {

    suspend fun prepare(
        ownerUserId: UserId,
        capsuleId: CapsuleId,
    ): IncomingPresentationPreparationResult {
        val preparedHolder = PreparedIncomingPresentationHolder(onPreparedMaterialClosed)
        return try {
            val ioResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val initialIdentity = try {
            currentRecipientIdentity()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext unavailable(
                IncomingPresentationPreparationUnavailable.IDENTITY_UNAVAILABLE,
            )
        } ?: return@withContext rejected(
            IncomingPresentationPreparationRejection.NO_AUTHENTICATED_OWNER,
        )
        if (initialIdentity.ownerUserId != ownerUserId) {
            return@withContext rejected(IncomingPresentationPreparationRejection.OWNER_MISMATCH)
        }

        val capsule = try {
            incomingCapsuleDao.getByCapsuleIdAndOwner(
                capsuleId = capsuleId.toRestString(),
                ownerUserId = ownerUserId.toRestString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext unavailable(
                IncomingPresentationPreparationUnavailable.DATABASE_UNAVAILABLE,
            )
        } ?: return@withContext rejected(
            IncomingPresentationPreparationRejection.CAPSULE_NOT_PRESENT,
        )

        if (capsule.ownerUserId != ownerUserId.toRestString() ||
            capsule.capsuleId != capsuleId.toRestString() ||
            capsule.recipientUserId != ownerUserId.toRestString()
        ) {
            return@withContext rejected(IncomingPresentationPreparationRejection.OWNER_MISMATCH)
        }
        if (capsule.serverStatus != READY_STATUS ||
            capsule.materialState != LocalMaterialState.MATERIAL_CACHED &&
            capsule.materialState != LocalMaterialState.FINGERPRINT_ACCEPTED ||
            capsule.protocolVersion != ProtocolV1Limits.PROTOCOL_VERSION
        ) {
            return@withContext rejected(IncomingPresentationPreparationRejection.CAPSULE_STATE_INVALID)
        }

        val senderUserId = try {
            UserId.parseRest(capsule.senderUserId)
        } catch (_: IllegalArgumentException) {
            return@withContext rejected(IncomingPresentationPreparationRejection.METADATA_INVALID)
        }
        val senderKeyBundleId = try {
            KeyBundleId.parseRest(capsule.senderSigningKeyBundleId)
        } catch (_: IllegalArgumentException) {
            return@withContext rejected(IncomingPresentationPreparationRejection.METADATA_INVALID)
        }
        val recipientKeyBundleId = try {
            KeyBundleId.parseRest(capsule.recipientEncryptionKeyBundleId)
        } catch (_: IllegalArgumentException) {
            return@withContext rejected(IncomingPresentationPreparationRejection.METADATA_INVALID)
        }
        if (recipientKeyBundleId != initialIdentity.activeKeyBundleId) {
            return@withContext rejected(IncomingPresentationPreparationRejection.METADATA_INVALID)
        }

        val envelope = try {
            incomingEnvelopeDao.getByCapsuleIdAndOwner(
                capsuleId = capsuleId.toRestString(),
                ownerUserId = ownerUserId.toRestString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext unavailable(
                IncomingPresentationPreparationUnavailable.DATABASE_UNAVAILABLE,
            )
        } ?: return@withContext rejected(IncomingPresentationPreparationRejection.MATERIAL_MISSING)

        if (envelope.ownerUserId != ownerUserId.toRestString() ||
            envelope.capsuleId != capsuleId.toRestString() ||
            envelope.recipientKeyBundleId != recipientKeyBundleId.toRestString() ||
            envelope.hpkeCiphertext.size !in 1..ProtocolV1Limits.RECIPIENT_ENVELOPE_MAX_CIPHERTEXT_BYTES ||
            envelope.transportSha256.size != SHA256_BYTES ||
            !MessageDigest.isEqual(sha256(envelope.hpkeCiphertext), envelope.transportSha256)
        ) {
            return@withContext rejected(IncomingPresentationPreparationRejection.METADATA_INVALID)
        }

        if (capsule.signedStatementBytes.size !in 1..MAX_STATEMENT_BYTES ||
            capsule.signedStatementSha256.size != SHA256_BYTES ||
            !MessageDigest.isEqual(
                sha256(capsule.signedStatementBytes),
                capsule.signedStatementSha256,
            )
        ) {
            return@withContext rejected(IncomingPresentationPreparationRejection.METADATA_INVALID)
        }

        var indexSnapshot: dev.hryshyn.remanence.index.SenderIndexBundleInspectionSnapshot? = null
        var envelopePlaintext: ByteArray? = null
        val localCiphertexts = ArrayList<ByteArray>()
        var retainedMaterial: PreparedPresentationMaterial? = null
        try {
            val indexResult = try {
                senderIndexBundleReader.inspect(
                    SenderIndexBundleReadRequest(
                        authenticatedOwnerUserId = ownerUserId,
                        ownerUserId = ownerUserId,
                        capsuleId = capsuleId,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext unavailable(
                    IncomingPresentationPreparationUnavailable.SENDER_INDEX_UNAVAILABLE,
                )
            }
            when (indexResult) {
                SenderIndexBundleReadResult.Missing,
                is SenderIndexBundleReadResult.Corrupt,
                -> return@withContext rejected(
                    IncomingPresentationPreparationRejection.SENDER_INDEX_INVALID,
                )
                is SenderIndexBundleReadResult.Unavailable -> return@withContext unavailable(
                    IncomingPresentationPreparationUnavailable.SENDER_INDEX_UNAVAILABLE,
                )
                is SenderIndexBundleReadResult.Available -> indexSnapshot = indexResult.snapshot
            }

            val senderVerification = indexSnapshot!!.senderVerification
                ?: return@withContext rejected(
                    IncomingPresentationPreparationRejection.SENDER_INDEX_INVALID,
                )
            if (senderVerification.senderUserId != senderUserId ||
                senderVerification.senderKeyBundleId != senderKeyBundleId
            ) {
                senderVerification.wipe()
                return@withContext rejected(IncomingPresentationPreparationRejection.SENDER_INDEX_INVALID)
            }
            val senderVerifyingKeyset = try {
                senderVerification.parsePublicKeyset()
            } catch (_: Exception) {
                senderVerification.wipe()
                return@withContext rejected(IncomingPresentationPreparationRejection.SENDER_INDEX_INVALID)
            } finally {
                senderVerification.wipe()
            }

            val statement = try {
                PublishStatement.parseFrom(capsule.signedStatementBytes)
            } catch (_: Exception) {
                return@withContext rejected(IncomingPresentationPreparationRejection.METADATA_INVALID)
            }
            val blobs = try {
                blobCacheDao.getAllByCapsuleIdAndOwner(
                    capsuleId = capsuleId.toRestString(),
                    ownerUserId = ownerUserId.toRestString(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext unavailable(
                    IncomingPresentationPreparationUnavailable.DATABASE_UNAVAILABLE,
                )
            }
            if (blobs.size != statement.artifactsCount || blobs.isEmpty()) {
                return@withContext rejected(IncomingPresentationPreparationRejection.MATERIAL_MISSING)
            }

            val delivered = ArrayList<DeliveredCiphertext>(blobs.size)
            val seen = HashSet<String>(blobs.size)
            for (blob in blobs) {
                coroutineContext.ensureActive()
                val loaded = validateAndReadBlob(
                    blob = blob,
                    ownerUserId = ownerUserId,
                    capsuleId = capsuleId,
                    statement = statement,
                    seen = seen,
                ) ?: return@withContext rejected(
                    IncomingPresentationPreparationRejection.MATERIAL_INVALID,
                )
                localCiphertexts += loaded
                delivered += DeliveredCiphertext(
                    blobId = CapsuleBlobIds.blobId(blob.blobId),
                    ciphertext = loaded,
                )
            }

            envelopePlaintext = try {
                envelopeCryptor.open(
                    recipientEncryptionPrivateKeyset = initialIdentity.encryptionPrivateKeyset,
                    context = RecipientEnvelopeContextInput(
                        capsuleId = capsuleId,
                        senderUserId = senderUserId,
                        recipientUserId = ownerUserId,
                        recipientKeyBundleId = recipientKeyBundleId,
                    ),
                    ciphertext = envelope.hpkeCiphertext,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext rejected(IncomingPresentationPreparationRejection.METADATA_INVALID)
            }

            val accepted = when (
                val result = acceptanceGate.prepare(
                    PresentationAcceptanceInput(
                        expectedCapsuleId = capsuleId,
                        authenticatedUserId = ownerUserId,
                        senderVerifyingKeyset = senderVerifyingKeyset,
                        expectedSenderKeyBundleId = senderKeyBundleId,
                        envelopePlaintextBytes = envelopePlaintext!!,
                        statementBytes = capsule.signedStatementBytes,
                        signature = capsule.publishSignatureBytes,
                        deliveredCiphertexts = delivered,
                    ),
                )
            ) {
                is PresentationAcceptancePreparationResult.Rejected ->
                    return@withContext rejected(
                        IncomingPresentationPreparationRejection.PRESENTATION_REJECTED,
                    )
                is PresentationAcceptancePreparationResult.Prepared -> result.material
            }
            retainedMaterial = accepted

            val finalIdentity = try {
                currentRecipientIdentity()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext unavailable(
                    IncomingPresentationPreparationUnavailable.IDENTITY_UNAVAILABLE,
                )
            }
            if (finalIdentity == null ||
                finalIdentity.ownerUserId != ownerUserId ||
                finalIdentity.activeKeyBundleId != initialIdentity.activeKeyBundleId
            ) {
                return@withContext rejected(IncomingPresentationPreparationRejection.ACCOUNT_CHANGED)
            }

            val presentation = PreparedIncomingPresentation(
                ownerUserId = ownerUserId,
                capsuleId = capsuleId,
                material = accepted,
            )
            if (!preparedHolder.store(presentation)) {
                preparedHolder.closeDetached(presentation)
                retainedMaterial = null
                return@withContext unavailable(
                    IncomingPresentationPreparationUnavailable.LOCAL_STORAGE_UNAVAILABLE,
                )
            }
            retainedMaterial = null
            return@withContext PreparedIncomingPresentationReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            rejected(IncomingPresentationPreparationRejection.MATERIAL_INVALID)
        } catch (_: IOException) {
            unavailable(IncomingPresentationPreparationUnavailable.LOCAL_STORAGE_UNAVAILABLE)
        } catch (_: SecurityException) {
            unavailable(IncomingPresentationPreparationUnavailable.LOCAL_STORAGE_UNAVAILABLE)
        } finally {
            // Snapshot close is best-effort cleanup. It must never replace a
            // cancellation or the primary rejection/unavailability result.
            try {
                indexSnapshot?.close()
            } catch (_: Exception) {
                // The reader's production snapshot close is non-throwing; a
                // defensive boundary keeps injected/test cleanup failures
                // from changing the caller-visible outcome.
            }
            envelopePlaintext?.fill(0)
            localCiphertexts.forEach { it.fill(0) }
            retainedMaterial?.close()
            }
            }
            return when (ioResult) {
                is PreparedIncomingPresentationReady -> {
                    beforePreparedResultDelivery()
                    IncomingPresentationPreparationResult.Prepared(deliverPrepared(preparedHolder))
                }
                else -> ioResult
            }
        } finally {
            preparedHolder.closeOwned()
        }
    }

    private suspend fun deliverPrepared(
        preparedHolder: PreparedIncomingPresentationHolder,
    ): PreparedIncomingPresentation = suspendCancellableCoroutine { continuation ->
        val presentation = preparedHolder.takeForDelivery()
        if (presentation == null) {
            if (continuation.isActive) {
                continuation.resumeWith(
                    Result.failure(IllegalStateException("prepared presentation handoff unavailable")),
                )
            }
            return@suspendCancellableCoroutine
        }
        try {
            beforePreparedDelivery(continuation)
        } catch (failure: Throwable) {
            preparedHolder.closeTransferred(presentation)
            throw failure
        }
        continuation.resume(presentation) { _, value, _ -> preparedHolder.closeTransferred(value) }
    }

    private suspend fun validateAndReadBlob(
        blob: BlobCacheEntity,
        ownerUserId: UserId,
        capsuleId: CapsuleId,
        statement: PublishStatement,
        seen: MutableSet<String>,
    ): ByteArray? {
        if (blob.ownerUserId != ownerUserId.toRestString() ||
            blob.capsuleId != capsuleId.toRestString() ||
            blob.cacheState != BlobCacheState.CACHED ||
            !seen.add(blob.blobId) ||
            blob.expectedSha256.size != SHA256_BYTES
        ) return null
        val blobId = try {
            CapsuleBlobIds.blobId(blob.blobId)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val binding = statement.artifactsList.firstOrNull { it.blobId == blobId.toProtoBytes() }
            ?: return null
        val kind = when (binding.kind) {
            ArtifactKind.RECOGNITION_MANIFEST -> CapsuleArtifactKind.RECOGNITION_MANIFEST
            ArtifactKind.CONTENT_MANIFEST -> CapsuleArtifactKind.CONTENT_MANIFEST
            ArtifactKind.PHOTO -> CapsuleArtifactKind.PHOTO
            else -> return null
        }
        val expectedOrdinal = if (kind == CapsuleArtifactKind.PHOTO) binding.ordinal else null
        if (blob.kind != kind.name || blob.ordinal != expectedOrdinal ||
            blob.expectedSizeBytes != binding.ciphertextSize ||
            !MessageDigest.isEqual(blob.expectedSha256, binding.ciphertextSha256.toByteArray())
        ) return null
        val maximum = when (kind) {
            CapsuleArtifactKind.RECOGNITION_MANIFEST ->
                ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.CONTENT_MANIFEST -> ProtocolV1Limits.CONTENT_MANIFEST_MAX_CIPHERTEXT_BYTES
            CapsuleArtifactKind.PHOTO -> ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES
        }
        if (blob.expectedSizeBytes !in 1L..maximum) return null

        val expectedPath = expectedBlobPath(ownerUserId, capsuleId, blobId)
        val incomingRoot = roots.child(
            ownerUserId,
            AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT,
        ).toPath().toAbsolutePath().normalize()
        if (blob.localPath != expectedPath.toString() ||
            !safeDirectories(incomingRoot, expectedPath.parent) ||
            !regularNoFollow(expectedPath)
        ) return null
        val attributes = Files.readAttributes(
            expectedPath,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.size() != blob.expectedSizeBytes) return null
        val bytes = readExactNoFollow(expectedPath, blob.expectedSizeBytes) ?: return null
        if (!MessageDigest.isEqual(sha256(bytes), blob.expectedSha256)) {
            bytes.fill(0)
            return null
        }
        return bytes
    }

    private suspend fun readExactNoFollow(path: Path, expectedSize: Long): ByteArray? {
        val bytes = ByteArray(expectedSize.toInt())
        return try {
            Files.newInputStream(
                path,
                java.nio.file.StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    coroutineContext.ensureActive()
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count <= 0) {
                        bytes.fill(0)
                        return null
                    }
                    offset += count
                }
                coroutineContext.ensureActive()
                if (input.read() != -1) {
                    bytes.fill(0)
                    return null
                }
            }
            bytes
        } catch (cancelled: CancellationException) {
            bytes.fill(0)
            throw cancelled
        } catch (failure: Exception) {
            bytes.fill(0)
            throw failure
        }
    }

    private fun expectedBlobPath(owner: UserId, capsule: CapsuleId, blobId: dev.hryshyn.remanence.core.model.BlobId): Path =
        roots.child(owner, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT)
            .toPath().toAbsolutePath().normalize()
            .resolve("capsules").resolve(capsule.toRestString()).resolve("blobs")
            .resolve("${blobId.toRestString()}.ciphertext").normalize()

    private fun safeDirectories(root: Path, parent: Path?): Boolean {
        if (parent == null || parent == root || !parent.startsWith(root)) return false
        var current = root
        return try {
            val rootAttributes = Files.readAttributes(
                root,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (rootAttributes.isSymbolicLink || !rootAttributes.isDirectory) return false
            for (segment in root.relativize(parent)) {
                current = current.resolve(segment)
                val attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (attributes.isSymbolicLink || !attributes.isDirectory) return false
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun regularNoFollow(path: Path): Boolean = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        !attributes.isSymbolicLink && attributes.isRegularFile
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun rejected(reason: IncomingPresentationPreparationRejection) =
        IncomingPresentationPreparationResult.Rejected(reason)

    private fun unavailable(reason: IncomingPresentationPreparationUnavailable) =
        IncomingPresentationPreparationResult.Unavailable(reason)

    private object CapsuleBlobIds {
        fun blobId(raw: String) = dev.hryshyn.remanence.core.model.BlobId.parseRest(raw)
    }

    private companion object {
        const val READY_STATUS = "READY"
        const val MAX_STATEMENT_BYTES = 4096
        const val SHA256_BYTES = 32
    }
}
