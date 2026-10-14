package dev.hryshyn.remanence.pending

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.crypto.PendingArtifactCryptor
import dev.hryshyn.remanence.core.crypto.PendingRecognitionDecode
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import dev.hryshyn.remanence.ui.scan.ScanCandidateIndex
import dev.hryshyn.remanence.ui.scan.ScanCandidateLoadDiagnostics
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * T186 M14d-scan: the REAL scan-side consumer of admitted pending rows
 * (the third caller of trusted admission alongside claim-time and
 * sync-time). Enumerates one owner's staged kind-1 FRONTs as
 * recognition candidates for [LocalMatchEngine], and re-admits single
 * rows for the scan crypto verifier — fully offline (no token, no
 * network): the FRONT ciphertext rode the row at reception, and every
 * key is live.
 *
 * Rows without staged FRONT (photos-only publications, failed staging)
 * never enumerate — fail closed, never guessed. Partially-admitted
 * rows (service trust pending) yield no key — matching waits for full
 * admission. Chooser hints are intentionally absent (v2 hint decrypt
 * is deferred): ambiguous matches render hint-less rows and manual
 * choice still passes the full crypto gate.
 */
internal class PendingAdmittedCandidateProvider(
    private val admittedStore: PendingAdmittedGiftStore,
    private val directoryPort: PendingClaimIngest.DirectoryPort,
    private val recipientKeysPort: PendingClaimIngest.RecipientKeysPort,
    private val servicePinPort: () -> PendingServicePin?,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    private data class LiveKeys(
        val recipient: PendingClaimIngest.RecipientKeys,
        val serviceVerify: KeysetHandle?,
        val serviceKeyId: String?,
    )

    private suspend fun liveKeys(ownerUserId: String): LiveKeys? {
        val recipient = try {
            recipientKeysPort.keys()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        if (recipient.userId.isBlank() || recipient.userId != ownerUserId) return null
        val pin = try {
            servicePinPort()
        } catch (_: Exception) {
            null
        }
        val serviceVerify = pin?.let {
            try {
                PendingServicePinConfig.parseVerificationKeyset(it.publicKeysetB64)
            } catch (_: Exception) {
                null
            }
        }
        return LiveKeys(recipient, serviceVerify, serviceVerify?.let { pin?.deploymentUuid })
    }

    private suspend fun senderVerify(
        statementBytes: ByteArray,
    ): Pair<KeysetHandle, String>? {
        val statement = try {
            dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
                .parseFrom(statementBytes)
        } catch (_: Exception) {
            return null
        }
        val senderId = try {
            uuidString(statement.senderUserId.toByteArray())
        } catch (_: Exception) {
            return null
        }
        val bundleId = try {
            uuidString(statement.senderKeyBundleId.toByteArray())
        } catch (_: Exception) {
            return null
        }
        // Trusted pair (same gate as open-time): the directory must
        // attest THIS bundle id for THIS sender — bare key bytes are
        // never enough.
        val directory = try {
            directoryPort.bundle(bundleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        if (directory.ownerUserId.isBlank() || directory.ownerUserId != senderId) return null
        if (directory.signingPublicKeyset.isEmpty()) return null
        val handle = try {
            com.google.crypto.tink.TinkProtoKeysetFormat
                .parseKeysetWithoutSecret(directory.signingPublicKeyset)
        } catch (_: Exception) {
            return null
        }
        return handle to directory.ownerUserId
    }

    /**
     * Re-opens the capsule key for one owned row (caller-owned bytes —
     * zeroize after use). Throws when the row is absent, foreign, or
     * fails re-admission. Cancellation propagates.
     */
    suspend fun openKey(ownerUserId: String, capsuleId: UUID): ByteArray {
        if (ownerUserId.isBlank()) throw IllegalStateException("pending owner required")
        val row = try {
            admittedStore.get(ownerUserId, capsuleId.toString())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: throw IllegalStateException("pending admitted row unknown")
        if (row.ownerUserId != ownerUserId || row.capsuleId != capsuleId.toString()) {
            throw IllegalStateException("pending admitted row owner mismatch")
        }
        val keys = liveKeys(ownerUserId)
            ?: throw IllegalStateException("pending recipient keys unavailable")
        val statementBytes = try {
            Base64.getDecoder().decode(row.statementB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached statement malformed")
        }
        val (senderVerify, directoryOwner) = senderVerify(statementBytes)
            ?: throw IllegalStateException("pending sender bundle unknown")
        return PendingRowReadmission.openKey(
            row = row,
            ownerUserId = ownerUserId,
            directoryOwnerUserId = directoryOwner,
            senderVerifyHandle = senderVerify,
            recipientPrivateHandle = keys.recipient.privateHandle,
            recipientPublicKeyset = keys.recipient.publicKeysetBytes,
            serviceVerifyHandle = keys.serviceVerify,
            authorizedServiceKeyId = keys.serviceKeyId,
            nowEpochSeconds = clockSeconds(),
        )
    }

    /**
     * Re-admits one owned row without opening anything (the scan crypto
     * verifier: true only after the full admission gate passes on live
     * keys). No FRONT, no blobs, no network. Cancellation propagates.
     */
    suspend fun verify(ownerUserId: String, capsuleId: UUID): Boolean {
        return try {
            openKey(ownerUserId, capsuleId).fill(0)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Enumerates the owner's staged FRONTs as scan candidates (caller
     * owns the parsed models for this scan invocation — same lifecycle
     * as the Room/v1 halves). Unreadable rows are skipped and counted,
     * never fatal. Cancellation propagates (already-emitted models are
     * wiped by the caller via [ScanCandidateIndex.wipeFingerprints]).
     */
    suspend fun load(ownerUserId: String): ScanCandidateIndex {
        if (ownerUserId.isBlank()) return ScanCandidateIndex.EMPTY
        val rows = try {
            admittedStore.list(ownerUserId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ScanCandidateIndex.EMPTY
        }
        val candidates = ArrayList<IndexedCandidate>(rows.size)
        val presentationSources = LinkedHashMap<UUID, CapsulePresentationSource>(rows.size)
        var invalidCandidateCount = 0
        try {
            val keys = liveKeys(ownerUserId)
            if (keys == null) {
                invalidCandidateCount = rows.size
            } else {
                for (row in rows) {
                    val candidate = readCandidate(row, ownerUserId, keys)
                    if (candidate == null) {
                        invalidCandidateCount++
                        continue
                    }
                    try {
                        candidates += candidate
                        presentationSources[candidate.capsuleId] =
                            CapsulePresentationSource.PENDING
                    } catch (failure: Throwable) {
                        candidate.front.wipe()
                        throw failure
                    }
                }
            }
            // Owner fence after the awaits (mirrors the v1 provider's
            // final check): the fetched keys are immutable, so a switch
            // mid-load cannot mix identities — but a dead or rotated
            // holder still fails the whole load closed here.
            if (candidates.isNotEmpty()) {
                val fresh = try {
                    recipientKeysPort.keys()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (fresh == null || fresh.userId != ownerUserId) {
                    candidates.forEach { it.front.wipe() }
                    return ScanCandidateIndex.EMPTY
                }
            }
            return ScanCandidateIndex(
                candidates = candidates,
                presentationSources = presentationSources,
                diagnostics = ScanCandidateLoadDiagnostics(
                    rawCandidateCount = rows.size,
                    validCandidateCount = candidates.size,
                    invalidCandidateCount = invalidCandidateCount,
                ),
            )
        } catch (failure: Throwable) {
            candidates.forEach { it.front.wipe() }
            throw failure
        }
    }

    private suspend fun readCandidate(
        row: PendingAdmittedGiftStore.AdmittedGift,
        ownerUserId: String,
        keys: LiveKeys,
    ): IndexedCandidate? {
        if (row.ownerUserId != ownerUserId) return null
        if (row.capsuleId.isBlank() || row.deliveryTargetId.isBlank()) return null
        val capsuleUuid = try {
            UUID.fromString(row.capsuleId)
        } catch (_: Exception) {
            return null
        }
        val staged = try {
            Base64.getDecoder().decode(row.recognitionCiphertextB64)
        } catch (_: Exception) {
            return null
        }
        if (staged.isEmpty()) return null
        val statementBytes = try {
            Base64.getDecoder().decode(row.statementB64)
        } catch (_: Exception) {
            return null
        }
        // The staged bytes must still match the row's S declaration
        // (blob/size/digest) — a healed or hand-edited row never
        // enumerates on stale bytes.
        val binding = PendingRecognitionStage.selectKind1(statementBytes) ?: return null
        if (staged.size.toLong() != binding.ciphertextSize) return null
        val stagedSha = try {
            java.security.MessageDigest.getInstance("SHA-256").digest(staged)
        } catch (_: Exception) {
            return null
        }
        if (!stagedSha.contentEquals(binding.ciphertextSha256)) return null
        val (senderVerify, directoryOwner) = try {
            senderVerify(statementBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val capsuleKey = try {
            PendingRowReadmission.openKey(
                row = row,
                ownerUserId = ownerUserId,
                directoryOwnerUserId = directoryOwner,
                senderVerifyHandle = senderVerify,
                recipientPrivateHandle = keys.recipient.privateHandle,
                recipientPublicKeyset = keys.recipient.publicKeysetBytes,
                serviceVerifyHandle = keys.serviceVerify,
                authorizedServiceKeyId = keys.serviceKeyId,
                nowEpochSeconds = clockSeconds(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        try {
            val statement = dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
                .parseFrom(statementBytes)
            val senderId = uuidString(statement.senderUserId.toByteArray())
            val targetId = uuidString(statement.deliveryTargetId.toByteArray())
            val keyset = try {
                com.google.crypto.tink.TinkProtoKeysetFormat
                    .parseKeysetWithoutSecret(capsuleKey)
            } catch (_: Exception) {
                return null
            }
            // Genuine delivery-target AAD — the same domain the sender
            // sealed under. A wrong-target ciphertext fails closed here.
            val plaintext = try {
                PendingArtifactCryptor.decrypt(
                    keyset, row.capsuleId, binding.blobId,
                    1, -1, senderId, targetId, staged,
                )
            } catch (_: Exception) {
                return null
            }
            try {
                val frontBytes = try {
                    PendingRecognitionDecode.frontFingerprint(
                        plaintext,
                        uuidBytes(row.capsuleId),
                    )
                } catch (_: Exception) {
                    return null
                }
                try {
                    var parsed: SiftRootSiftFingerprint? = null
                    try {
                        parsed = SiftRootSiftFingerprintCodec.parse(frontBytes)
                        return IndexedCandidate(
                            capsuleId = capsuleUuid,
                            front = parsed,
                        ).also { parsed = null }
                    } finally {
                        parsed?.wipe()
                        frontBytes.fill(0)
                    }
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                capsuleKey.fill(0)
            }
        } catch (cancelled: CancellationException) {
            capsuleKey.fill(0)
            throw cancelled
        } catch (_: Exception) {
            capsuleKey.fill(0)
            return null
        }
    }

    private fun uuidString(raw16: ByteArray): String {
        require(raw16.size == 16) { "pending id malformed" }
        val buffer = java.nio.ByteBuffer.wrap(raw16)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun uuidBytes(rest: String): ByteArray {
        val uuid = try {
            UUID.fromString(rest)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("pending id malformed")
        }
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }
}
