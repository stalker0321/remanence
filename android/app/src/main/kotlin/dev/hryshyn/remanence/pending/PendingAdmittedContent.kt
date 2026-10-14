package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.crypto.PendingArtifactCryptor
import dev.hryshyn.remanence.core.crypto.PendingPublishVerifier
import dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * T186 M14c: admitted-gift photo opening for the scan/display path.
 *
 * Opens one durable admitted row with caller-supplied live keys:
 * re-verifies the cached sender signature over the cached S (defense
 * in depth — admission already verified once), re-opens the sealed
 * envelope bytes via the provided opener, downloads every PHOTO
 * binding declared in S (size + SHA-256 checked against the
 * declaration before decrypt), and decrypts each under the
 * delivery-target AAD. Manifest/note decoding is the next slice;
 * photos render the gift. No secrets are persisted here — the
 * caller-owned key copy is zeroized in `finally`.
 *
 * Runs under the scan grant lifecycle (the grant is the fence);
 * additionally requires the row owner to match the caller.
 */
class PendingAdmittedContent(
    private val blobPort: BlobPort,
    private val senderVerifyPort: SenderVerifyPort,
    /**
     * T186 M14d-scan (T188 B4): the ORIGINAL held lease for one open
     * (ingest-txn pattern — never fresh recapture per effect). The
     * same bearer is used only while this lease stays live; pinned
     * owner + liveness are re-checked before/after every blob download
     * and around the sender-key and envelope awaits. Null disables
     * fencing (unit surfaces).
     */
    private val session: OpenSession? = null,
) {

    /**
     * Held original-lease fence for one open: the lease captured at
     * entry, the owner pinned at capture, and a liveness probe. The
     * bearer travels with the lease — it is never re-captured.
     */
    data class OpenSession(
        val lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
        val ownerUserId: String,
        val isLive: () -> Boolean,
    )

    fun interface BlobPort {
        suspend fun download(
            deliveryId: String,
            blobId: String,
            token: String,
        ): PendingBlobBytesResult
    }

    /** Directory sender bundle: owner + verify keyset bytes (public only). */
    data class DirectoryBundle(
        val ownerUserId: String,
        val signingPublicKeyset: ByteArray,
    )

    /**
     * T186 (T187 qualifier): returns the directory bundle WITHOUT
     * trusting it — [openPhotos] requires
     * `bundle.ownerUserId == S.senderUserId` (and the requested bundle
     * id) before any byte is used. Bare key bytes never cross here.
     */
    fun interface SenderVerifyPort {
        suspend fun senderBundle(senderKeyBundleId: String): DirectoryBundle?
    }

    /** Capsule key bytes opened by the caller (R12 + live keys). */
    fun interface EnvelopeOpener {
        suspend fun openCapsuleKey(): ByteArray?
    }

    data class OpenedPhotos(
        val photosByOrdinal: Map<Int, ByteArray>,
        /** Authored note from the v2 content manifest (null when absent). */
        val note: String?,
        /**
         * The full validated content manifest (version, photo map,
         * expression, track snapshot) — carried, never re-derived, so
         * presentation admits the actual authored layout instead of a
         * photo-count fallback.
         */
        val manifest: dev.hryshyn.remanence.core.crypto.ContentManifestContent,
    )

    suspend fun openPhotos(
        row: PendingAdmittedGiftStore.AdmittedGift,
        ownerUserId: String,
        token: String,
        envelopeOpener: EnvelopeOpener,
    ): OpenedPhotos {
        require(row.ownerUserId.isNotBlank() && row.ownerUserId == ownerUserId) {
            "pending admitted row owner mismatch"
        }
        require(token.isNotBlank()) { "pending open bearer required" }
        checkSession(ownerUserId)
        val statementBytes = try {
            Base64.getDecoder().decode(row.statementB64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached statement malformed")
        }
        val statement = try {
            PendingPublishVerifier.verifyStructure(statementBytes)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached statement rejected")
        }
        val senderBundleId = uuidString(
            statement.senderKeyBundleId.toByteArray(),
            "pending sender bundle id malformed",
        )
        val senderId = uuidString(
            statement.senderUserId.toByteArray(), "pending sender id malformed",
        )
        val directory = try {
            checkSession(ownerUserId)
            senderVerifyPort.senderBundle(senderBundleId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: throw IllegalStateException("pending sender bundle unknown")
        checkSession(ownerUserId)
        // Trusted pair (T187 qualifier): the directory must attest THIS
        // bundle id for THIS sender — bare key bytes are never enough.
        if (directory.ownerUserId.isBlank() || directory.ownerUserId != senderId) {
            throw IllegalStateException("pending sender owner mismatch")
        }
        val senderKeyBytes = directory.signingPublicKeyset
        if (senderKeyBytes.isEmpty()) {
            throw IllegalStateException("pending sender key unavailable")
        }
        val senderSig = try {
            Base64.getDecoder().decode(row.publishSignature69B64)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached signature malformed")
        }
        try {
            PendingPublishVerifier.verifyPublish(senderKeyBytes, statementBytes, senderSig)
        } catch (_: Exception) {
            throw IllegalStateException("pending cached statement signature rejected")
        }
        // Row/S agreement: the cached bytes must describe this row.
        if (uuidString(statement.capsuleId.toByteArray(), "pending capsule id malformed") != row.capsuleId ||
            uuidString(statement.deliveryTargetId.toByteArray(), "pending target id malformed") != row.deliveryTargetId
        ) {
            throw IllegalStateException("pending row binding mismatch")
        }
        val capsuleKey = try {
            checkSession(ownerUserId)
            envelopeOpener.openCapsuleKey()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        checkSession(ownerUserId)
        if (capsuleKey == null || capsuleKey.isEmpty()) {
            throw IllegalStateException("pending capsule key unavailable")
        }
        try {
            // Receiver hygiene (T187 qualifier): every decrypted PHOTO
            // buffer is tracked so a LATER failure wipes previously opened
            // photos — partial buffers never gain caller ownership. The
            // content-manifest plaintext is wiped immediately after its
            // parse (note survives as an immutable copy) and never enters
            // the tracked set. On success the photo map takes ownership.
            val wiped = ArrayList<ByteArray>()
            try {
                val photos = LinkedHashMap<Int, ByteArray>()
                var note: String? = null
                var manifests = 0
                var recognitions = 0
                val declaredPhotos = LinkedHashMap<Int, String>()
                var manifestContent: dev.hryshyn.remanence.core.crypto.ContentManifestContent? = null
                for (binding in statement.artifactsList) {
                    if (binding.kind == 1) {
                        // Recognition manifest declaration (bytes stream
                        // through staging/scan, never downloaded here).
                        // Both manifests are required for supported
                        // presentation — photo-only gifts never present.
                        recognitions++
                        continue
                    }
                    if (binding.kind != 2 && binding.kind != 3) continue
                    val blobId = uuidString(binding.blobId.toByteArray(), "pending blob id malformed")
                    checkSession(ownerUserId)
                    val ciphertext = when (
                        val downloaded = try {
                            blobPort.download(row.deliveryId, blobId, token)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            throw IllegalStateException("pending blob download failed")
                        }
                    ) {
                        is PendingBlobBytesResult.Bytes -> downloaded.bytes
                        is PendingBlobBytesResult.Failure ->
                            throw IllegalStateException("pending blob download failed")
                    }
                    checkSession(ownerUserId)
                    if (ciphertext.size.toLong() != binding.ciphertextSize) {
                        throw IllegalStateException("pending blob size mismatch")
                    }
                    val actualSha = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(ciphertext).joinToString("") { "%02x".format(it) }
                    val expectedSha = binding.ciphertextSha256.toByteArray()
                        .joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(expectedSha, ignoreCase = false)) {
                        throw IllegalStateException("pending blob hash mismatch")
                    }
                    val targetId = uuidString(
                        statement.deliveryTargetId.toByteArray(), "pending target id malformed",
                    )
                    val opened = try {
                        PendingArtifactCryptor.decrypt(
                            capsuleKeysetFrom(capsuleKey), row.capsuleId, blobId,
                            binding.kind, binding.ordinal, senderId, targetId, ciphertext,
                        )
                    } catch (_: Exception) {
                        throw IllegalStateException("pending photo open failed")
                    }
                    if (binding.kind == 2) {
                        // Content manifest: same framed validation as v1
                        // (byte-identical inner semantics), then the note.
                        // The manifest plaintext is wiped immediately after
                        // the parse in `finally` and never joins `wiped`:
                        // transferred ownership is photo buffers only, so a
                        // success `wiped.clear()` cannot drop manifest bytes
                        // unwiped. Failures/cancel still wipe previously
                        // opened photos through the outer handlers below.
                        try {
                            val parsed = try {
                                dev.hryshyn.remanence.core.crypto.ContentManifestCodec().parseAndValidate(
                                    opened,
                                    uuidBytes(row.capsuleId),
                                )
                            } catch (_: Exception) {
                                throw IllegalStateException("pending content manifest rejected")
                            }
                            if (manifests++ > 0) throw IllegalStateException("pending content manifest duplicated")
                            note = parsed.note
                            manifestContent = parsed
                        } finally {
                            opened.fill(0)
                        }
                    } else {
                        wiped += opened
                        if (photos.put(binding.ordinal, opened) != null) {
                            throw IllegalStateException("pending photo ordinal duplicated")
                        }
                        declaredPhotos[binding.ordinal] = blobId
                    }
                }
                if (manifests == 0) throw IllegalStateException("pending content manifest missing")
                if (recognitions == 0) throw IllegalStateException("pending recognition manifest missing")
                val manifest = manifestContent
                    ?: throw IllegalStateException("pending content manifest missing")
                // Signed declaration/photo-map agreement: the authored
                // display map must describe EXACTLY the S-declared photo
                // set (blob id + ordinal) — a manifest pointing outside
                // the signed declaration (or omitting declared photos)
                // renders nothing.
                val manifestPairs = manifest.photos.map { it.ordinal to it.blobId.toString() }.toSet()
                val declaredPairs = declaredPhotos.map { (ordinal, blob) -> ordinal to blob }.toSet()
                if (manifestPairs != declaredPairs) {
                    throw IllegalStateException("pending photo map mismatch")
                }
                if (photos.isEmpty()) throw IllegalStateException("pending gift has no photos")
                val result = OpenedPhotos(photos.toMap(), note, manifest)
                wiped.clear()
                return result
            } catch (cancelled: CancellationException) {
                wiped.forEach { it.fill(0) }
                throw cancelled
            } catch (failure: Exception) {
                wiped.forEach { it.fill(0) }
                throw failure
            }
        } finally {
            capsuleKey.fill(0)
        }
    }

    /**
     * T188 B4 fence: the pinned owner must still own the caller AND
     * the ORIGINAL lease (same owner string — a same-owner replacement
     * carries a new incarnation, which fails the liveness probe), and
     * the lease must still be live. Null session skips (unit
     * surfaces). Throws — never a boolean a caller could ignore.
     */
    private fun checkSession(ownerUserId: String) {
        val s = session ?: return
        val live = try {
            s.isLive()
        } catch (_: Exception) {
            false
        }
        val leaseOwner = try {
            s.lease.ownerUserId.toRestString()
        } catch (_: Exception) {
            ""
        }
        if (!live || leaseOwner.isBlank() || leaseOwner != s.ownerUserId ||
            s.ownerUserId != ownerUserId
        ) {
            throw IllegalStateException("pending session changed")
        }
    }

    private fun uuidString(raw16: ByteArray, what: String): String {
        require(raw16.size == 16) { what }
        val buffer = java.nio.ByteBuffer.wrap(raw16)
        return java.util.UUID(buffer.long, buffer.long).toString()
    }

    private fun uuidBytes(rest: String): ByteArray {
        val uuid = try {
            java.util.UUID.fromString(rest)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("pending id malformed")
        }
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun capsuleKeysetFrom(keysetBytes: ByteArray): com.google.crypto.tink.KeysetHandle =
        pendingCapsuleKeysetFrom(keysetBytes)
}

/**
 * T186 M14d-scan: the one verified capsule-key re-parse for the
 * pending plane (shared by open-time and scan-time callers — same
 * exact K, no drift). Public-shape bytes parse secretless; private
 * handle bytes (exact K as sealed) parse with secret. Anything else
 * fails closed.
 */
internal fun pendingCapsuleKeysetFrom(keysetBytes: ByteArray): com.google.crypto.tink.KeysetHandle {
    // Exact-K shape is enforced by admission; re-parse defensively.
    return try {
        com.google.crypto.tink.TinkProtoKeysetFormat.parseKeysetWithoutSecret(keysetBytes)
    } catch (_: Exception) {
        // Private handle bytes (exact K as sealed) parse with secret.
        try {
            com.google.crypto.tink.TinkProtoKeysetFormat.parseKeyset(
                keysetBytes,
                com.google.crypto.tink.InsecureSecretKeyAccess.get(),
            )
        } catch (_: Exception) {
            throw IllegalStateException("pending capsule key malformed")
        }
    }
}
