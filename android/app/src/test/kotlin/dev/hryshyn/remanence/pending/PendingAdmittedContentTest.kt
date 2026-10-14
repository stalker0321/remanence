package dev.hryshyn.remanence.pending

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.PendingArtifactCryptor
import dev.hryshyn.remanence.core.crypto.PendingCapsulePublisher
import dev.hryshyn.remanence.core.crypto.PendingPublishCrypto
import dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult
import dev.hryshyn.remanence.core.data.network.PendingMaterialFailure
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.ui.capsule.testV2Expression
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M14c: admitted photo opening with REAL sealed bytes (v2
 * publisher output) — owner fence, sender-signature re-verification,
 * declaration-bound blob download, target-AAD decrypt. Manifest/note
 * decoding is the next slice.
 */
class PendingAdmittedContentTest {

    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val sender = "01010101-0202-0303-0404-050505050505"
    private val senderBundle = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val serviceKey = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b"
    private val delivery = "0198f0a0-0000-7000-8000-00000000d401"
    private val owner = "33333333-4444-5555-6666-777777777777"

    private data class Sealed(
        val publication: PendingCapsulePublisher.Publication,
        val signing: KeysetHandle,
        val capsuleKey: KeysetHandle,
        val jpeg: ByteArray,
    )

    private fun sealed(): Sealed {
        dev.hryshyn.remanence.core.crypto.TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val capsuleKey = CapsuleKeysetGenerator().generate()
        val jpeg = "photo-bytes".toByteArray()
        val publication = PendingCapsulePublisher.publishFull(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            serviceKeyId = serviceKey,
            channel = PendingPublishCrypto.CHANNEL_EMAIL,
            normalizedDestination = "a42@example.test",
            salt32 = ByteArray(32).also { SecureRandom().nextBytes(it) },
            claimExpiresAt = 1814317432L,
            createdAtEpochSeconds = 1798765432L,
            signingHandle = signing,
            capsuleKeyset = capsuleKey,
            senderHandleSnapshot = "mykola",
            frontFingerprintBytes = ByteArray(96) { (it * 3).toByte() },
            photos = listOf(
                PendingCapsulePublisher.PhotoInput(jpeg, 1000, 1000),
                PendingCapsulePublisher.PhotoInput("photo-two".toByteArray(), 1200, 800),
                PendingCapsulePublisher.PhotoInput("photo-three".toByteArray(), 800, 1200),
            ),
            noteUtf8 = "hello",
        )
        return Sealed(publication, signing, capsuleKey, jpeg)
    }

    private fun rowOf(sealed: Sealed) = rowOfStatement(
        sealed.publication.statement,
        sealed.publication.signature,
    )

    private fun rowOfStatement(statement: ByteArray, signature: ByteArray) =
        PendingAdmittedGiftStore.AdmittedGift(
        ownerUserId = owner,
        capsuleId = capsule,
        deliveryId = delivery,
        deliveryTargetId = target,
        claimId = "55555555-6666-7777-8888-999999999999",
        availableAt = 1798765432L,
        envelopeB64 = "RW52",
        envelopeSha256Hex = "ab".repeat(32),
        statementB64 = Base64.getEncoder().encodeToString(statement),
        statementSha256Hex = MessageDigest.getInstance("SHA-256")
            .digest(statement).joinToString("") { "%02x".format(it) },
        publishSignature69B64 = Base64.getEncoder()
            .encodeToString(signature),
        // Claim material is unread by the open path (the capsule key
        // arrives via the envelope opener; admission is covered by the
        // ingest/claim/readmission tests) — blanks keep this sender-side
        // fixture compiling with no behavior change.
        claimBindingB64 = "",
        claimSignature69B64 = "",
        recipientKeyBundleId = "",
        envelopeRef = "/v2/pending-deliveries/$delivery/claim",
        manifestRef = "/v2/pending-deliveries/$delivery/manifest",
        blobRefs = emptyList(),
        completedAt = null,
        admittedAtMillis = 1L,
    )

    private fun content(
        sealed: Sealed,
        blobs: Map<String, ByteArray> = sealed.publication.artifacts
            .associate { it.blobId to it.ciphertext },
        signing: KeysetHandle = sealed.signing,
        session: PendingAdmittedContent.OpenSession? = null,
    ) = PendingAdmittedContent(
        blobPort = PendingAdmittedContent.BlobPort { _, blobId, _ ->
            val bytes = blobs[blobId]
                ?: return@BlobPort PendingBlobBytesResult.Failure(
                    dev.hryshyn.remanence.core.data.network.PendingMaterialFailure.NOT_FOUND,
                    404, false,
                )
            PendingBlobBytesResult.Bytes(blobId, bytes)
        },
        senderVerifyPort = PendingAdmittedContent.SenderVerifyPort { _ ->
            PendingAdmittedContent.DirectoryBundle(
                "01010101-0202-0303-0404-050505050505",
                TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                    signing.publicKeysetHandle,
                ),
            )
        },
        session = session,
    )

    private fun keyOf(sealed: Sealed): ByteArray = keyBytesOf(sealed.capsuleKey)

    private fun keyBytesOf(handle: KeysetHandle): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(
            handle,
            InsecureSecretKeyAccess.get(),
        )

    @Test
    fun opensPhotosWithTargetAad() = runTest {
        val sealed = sealed()
        val opened = content(sealed).openPhotos(
            rowOf(sealed), owner, "token",
            PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
        )
        assertEquals(3, opened.photosByOrdinal.size)
        assertEquals(
            "photo-bytes",
            opened.photosByOrdinal[0]?.toString(Charsets.UTF_8),
        )
        // The authored note arrives via the validated v2 manifest.
        assertEquals("hello", opened.note)
    }

    @Test
    fun foreignOwnerRejected() = runTest {
        val sealed = sealed()
        assertThrowsSuspend(IllegalArgumentException::class) {
            content(sealed).openPhotos(
                rowOf(sealed), "99999999-2222-3333-4444-555555555555", "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
        }
    }

    @Test
    fun tamperedBlobRejected() = runTest {
        val sealed = sealed()
        val first = sealed.publication.artifacts.first { it.kind == 3 }
        val tampered = first.ciphertext.copyOf()
            .also { it[0] = (it[0] + 1).toByte() }
        val opened = try {
            content(
                sealed,
                sealed.publication.artifacts.associate {
                    it.blobId to if (it.blobId == first.blobId) tampered else it.ciphertext
                },
            ).openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
            null
        } catch (_: IllegalStateException) {
            "rejected"
        }
        assertEquals("rejected", opened)
    }

    @Test
    fun missingPhotoBlobFails() = runTest {
        val sealed = sealed()
        val result = try {
            content(sealed, emptyMap()).openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
            null
        } catch (_: IllegalStateException) {
            "rejected"
        }
        assertEquals("rejected", result)
    }

    @Test
    fun tamperedManifestRejected() = runTest {
        val sealed = sealed()
        val manifest = sealed.publication.artifacts.single { it.kind == 2 }
        val tampered = manifest.ciphertext.copyOf()
            .also { it[0] = (it[0] + 1).toByte() }
        val result = try {
            content(
                sealed,
                sealed.publication.artifacts.associate {
                    it.blobId to if (it.blobId == manifest.blobId) tampered else it.ciphertext
                },
            ).openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
            null
        } catch (_: IllegalStateException) {
            "rejected"
        }
        assertEquals("rejected", result)
    }

    /**
     * T186 M14d-scan: a photos-only publication (no manifests) has no
     * supported presentation — the open fails closed instead of
     * rendering a manifest-less photo fallback.
     */
    @Test
    fun photoOnlyPublicationHasNoSupportedPresentation() = runTest {
        dev.hryshyn.remanence.core.crypto.TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val capsuleKey = CapsuleKeysetGenerator().generate()
        val publication = PendingCapsulePublisher.publish(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            serviceKeyId = serviceKey,
            channel = PendingPublishCrypto.CHANNEL_EMAIL,
            normalizedDestination = "a42@example.test",
            salt32 = ByteArray(32).also { SecureRandom().nextBytes(it) },
            claimExpiresAt = 1814317432L,
            createdAtEpochSeconds = 1798765432L,
            signingHandle = signing,
            capsuleKeyset = capsuleKey,
            photos = listOf(
                "photo-bytes".toByteArray(),
                "photo-two".toByteArray(),
                "photo-three".toByteArray(),
            ),
        )
        val result = try {
            content(
                sealed(),
                publication.artifacts.associate { it.blobId to it.ciphertext },
                signing,
            ).openPhotos(
                rowOfStatement(publication.statement, publication.signature),
                owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyBytesOf(capsuleKey) },
            )
            null
        } catch (failure: IllegalStateException) {
            failure.message
        }
        assertTrue((result ?: "").contains("content manifest missing"))
    }

    /**
     * T186 M14d-scan: signed declaration/photo-map agreement — a
     * manifest photo pointing outside the S-declared kind-3 set
     * renders nothing (re-sealed here with a foreign blob id through
     * the real target-AAD cryptor).
     */
    @Test
    fun foreignManifestPhotoRendersNothing() = runTest {
        val sealed = sealed()
        val manifest = sealed.publication.artifacts.single { it.kind == 2 }
        val plaintext = PendingArtifactCryptor.decrypt(
            sealed.capsuleKey, capsule, manifest.blobId,
            2, -1, sender, target, manifest.ciphertext,
        )
        val foreignBytes = java.nio.ByteBuffer.allocate(16).apply {
            val foreign = java.util.UUID.fromString("ffffffff-1111-2222-3333-444444444444")
            putLong(foreign.mostSignificantBits)
            putLong(foreign.leastSignificantBits)
        }.array()
        val parsed = dev.hryshyn.remanence.protocol.v1.ContentManifest.parseFrom(plaintext)
        val swapped = parsed.getPhotos(0).toBuilder()
            .setBlobId(ByteString.copyFrom(foreignBytes))
            .build()
        val rebuilt = parsed.toBuilder().setPhotos(0, swapped).build().toByteArray()
        val resealed = PendingArtifactCryptor.encrypt(
            sealed.capsuleKey, capsule, manifest.blobId,
            2, -1, sender, target, rebuilt,
        )
        // Genuinely self-consistent S: the modified kind-2 size/hash
        // declaration, re-signed with the fixture sender key (same
        // target AAD throughout) — size/hash checks pass, so the
        // photo-map gate is what must reject.
        val statement =
            dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2.parseFrom(
                sealed.publication.statement,
            )
        val kind2Index = statement.artifactsList.indexOfFirst { it.kind == 2 }
        assertTrue(kind2Index >= 0)
        val updated = statement.artifactsList[kind2Index].toBuilder()
            .setCiphertextSize(resealed.size.toLong())
            .setCiphertextSha256(
                ByteString.copyFrom(
                    MessageDigest.getInstance("SHA-256").digest(resealed),
                ),
            )
            .build()
        val restated = statement.toBuilder().setArtifacts(kind2Index, updated)
            .build().toByteArray()
        val resigned = PendingPublishCrypto.signStatement(sealed.signing, restated)
        val result = try {
            content(
                sealed,
                sealed.publication.artifacts.associate {
                    it.blobId to if (it.blobId == manifest.blobId) resealed else it.ciphertext
                },
            ).openPhotos(
                rowOfStatement(restated, resigned), owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
            null
        } catch (failure: IllegalStateException) {
            failure.message
        }
        assertTrue((result ?: "").contains("photo map mismatch"))
    }

    /**
     * T186 M14d-scan: a genuinely published v2 expression gift (real
     * publisher output, shared validated expression fixture) opens end
     * to end — photo-map agreement over the authored set, note absent,
     * and the reader admits the supported expression to BER1 with the
     * track snapshot carried. No fallback, no restyle.
     */
    @Test
    fun v2ExpressionGiftOpensToBer1Admission() = runTest {
        dev.hryshyn.remanence.core.crypto.TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val capsuleKey = CapsuleKeysetGenerator().generate()
        val expression = testV2Expression()
        val track = CapsuleTrackSnapshotV1.parse(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            "Title",
            "Artist",
            null,
            null,
        )
        val publication = PendingCapsulePublisher.publishFull(
            capsuleId = capsule,
            senderUserId = sender,
            senderKeyBundleId = senderBundle,
            deliveryTargetId = target,
            serviceKeyId = serviceKey,
            channel = PendingPublishCrypto.CHANNEL_EMAIL,
            normalizedDestination = "a42@example.test",
            salt32 = ByteArray(32).also { SecureRandom().nextBytes(it) },
            claimExpiresAt = 1814317432L,
            createdAtEpochSeconds = 1798765432L,
            signingHandle = signing,
            capsuleKeyset = capsuleKey,
            senderHandleSnapshot = "mykola",
            frontFingerprintBytes = ByteArray(96) { (it * 3).toByte() },
            photos = listOf(
                PendingCapsulePublisher.PhotoInput("photo-bytes".toByteArray(), 1000, 1000),
                PendingCapsulePublisher.PhotoInput("photo-two".toByteArray(), 1200, 800),
                PendingCapsulePublisher.PhotoInput("photo-three".toByteArray(), 800, 1200),
            ),
            noteUtf8 = null,
            expression = PendingCapsulePublisher.ExpressionInput(
                "cand-1",
                expression,
                listOf("p1", "p2", "p3"),
                track,
            ),
        )
        val v2 = Sealed(
            publication,
            signing,
            capsuleKey,
            "photo-bytes".toByteArray(),
        )
        val opened = content(v2).openPhotos(
            rowOf(v2), owner, "token",
            PendingAdmittedContent.EnvelopeOpener { keyOf(v2) },
        )
        assertEquals(3, opened.photosByOrdinal.size)
        assertNull(opened.note)
        val reader = dev.hryshyn.remanence.pending.PendingAdmittedPhotosReader(capsule, opened)
        val admission = reader.presentationAdmission(capsule)
        assertTrue(admission is dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission.Ber1)
        // The codec deliberately normalizes sender-memory-only fields
        // on decode (owner/epoch/diagnostics become receiver
        // placeholders) while layout/photo sources/placements must
        // match exactly — normalize ONLY the placeholders, then keep
        // full authored equality.
        val decoded =
            (admission as dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission.Ber1).expression
        val normalized = expression.copy(
            input = expression.input.copy(
                ownerId = decoded.input.ownerId,
                epoch = decoded.input.epoch,
            ),
            diagnostics = decoded.diagnostics,
        )
        assertEquals(normalized, decoded)
        assertEquals(track, reader.trackSnapshot(capsule))
    }

    /**
     * T186 (T187 qualifier): a directory bundle owned by someone
     * other than the statement sender is rejected before any byte is
     * trusted — bare key bytes never suffice.
     */
    @Test
    fun foreignDirectoryOwnerRejected() = runTest {
        val sealed = sealed()
        val content = PendingAdmittedContent(
            blobPort = PendingAdmittedContent.BlobPort { _, blobId, _ ->
                PendingBlobBytesResult.Bytes(
                    blobId,
                    sealed.publication.artifacts.single { it.blobId == blobId }.ciphertext,
                )
            },
            senderVerifyPort = PendingAdmittedContent.SenderVerifyPort { _ ->
                PendingAdmittedContent.DirectoryBundle(
                    "99999999-2222-3333-4444-555555555555",
                    TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                        sealed.signing.publicKeysetHandle,
                    ),
                )
            },
        )
        val result = try {
            content.openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
            null
        } catch (_: IllegalStateException) {
            "rejected"
        }
        assertEquals("rejected", result)
    }

    private fun sessionOf(
        leaseOwner: String = owner,
        live: () -> Boolean = { true },
    ) = PendingAdmittedContent.OpenSession(
        lease = SessionRequestLease(UserId.parseRest(leaseOwner), 1L),
        ownerUserId = owner,
        isLive = live,
    )

    /**
     * T186 M14d-scan (T188 B4): a lease death mid-open fails the whole
     * open closed — the bearer is never used past the original lease,
     * and no further blob is attempted after the fence trips.
     */
    @Test
    fun sessionDeathMidDownloadFailsClosed() = runTest {
        val sealed = sealed()
        var live = true
        var downloads = 0
        val gated = PendingAdmittedContent(
            blobPort = PendingAdmittedContent.BlobPort { _, blobId, _ ->
                downloads += 1
                if (downloads == 1) live = false
                val bytes = sealed.publication.artifacts.single { it.blobId == blobId }.ciphertext
                PendingBlobBytesResult.Bytes(blobId, bytes)
            },
            senderVerifyPort = PendingAdmittedContent.SenderVerifyPort { _ ->
                PendingAdmittedContent.DirectoryBundle(
                    "01010101-0202-0303-0404-050505050505",
                    TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                        sealed.signing.publicKeysetHandle,
                    ),
                )
            },
            session = sessionOf(live = { live }),
        )
        val result = try {
            gated.openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener { keyOf(sealed) },
            )
            null
        } catch (failure: IllegalStateException) {
            failure.message
        }
        assertTrue((result ?: "").contains("session changed"))
        assertEquals(1, downloads)
    }

    /**
     * T186 M14d-scan (T188 B4): a lease captured for another owner
     * never starts an open — rejected at entry before any blob or key
     * await.
     */
    @Test
    fun sessionOwnerMismatchRejectsBeforeAnyDownload() = runTest {
        val sealed = sealed()
        var downloads = 0
        val gated = PendingAdmittedContent(
            blobPort = PendingAdmittedContent.BlobPort { _, _, _ ->
                downloads += 1
                throw AssertionError("fenced open must not download")
            },
            senderVerifyPort = PendingAdmittedContent.SenderVerifyPort { _ ->
                throw AssertionError("fenced open must not resolve keys")
            },
            session = sessionOf(
                leaseOwner = "99999999-2222-3333-4444-555555555555",
            ),
        )
        val result = try {
            gated.openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener {
                    throw AssertionError("fenced open must not open the envelope")
                },
            )
            null
        } catch (failure: IllegalStateException) {
            failure.message
        }
        assertTrue((result ?: "").contains("session changed"))
        assertEquals(0, downloads)
    }

    /**
     * T186 M14d-scan: a lease death across the envelope await still
     * zeroizes the returned key — the post-await fence lives inside
     * wipe ownership, so the throw cannot strand live key bytes. No
     * blob is downloaded and nothing presents.
     */
    @Test
    fun envelopeLeaseDeathWipesReturnedKey() = runTest {
        val sealed = sealed()
        var live = true
        var downloads = 0
        val gated = PendingAdmittedContent(
            blobPort = PendingAdmittedContent.BlobPort { _, _, _ ->
                downloads += 1
                throw AssertionError("fenced open must not download")
            },
            senderVerifyPort = PendingAdmittedContent.SenderVerifyPort { _ ->
                PendingAdmittedContent.DirectoryBundle(
                    "01010101-0202-0303-0404-050505050505",
                    TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                        sealed.signing.publicKeysetHandle,
                    ),
                )
            },
            session = sessionOf(live = { live }),
        )
        var returned: ByteArray? = null
        val result = try {
            gated.openPhotos(
                rowOf(sealed), owner, "token",
                PendingAdmittedContent.EnvelopeOpener {
                    val key = keyOf(sealed)
                    returned = key
                    live = false
                    key
                },
            )
            null
        } catch (failure: IllegalStateException) {
            failure.message
        }
        assertTrue((result ?: "").contains("session changed"))
        assertEquals(0, downloads)
        val key = returned ?: throw AssertionError("opener must have returned a key")
        assertTrue("returned key must be non-empty", key.isNotEmpty())
        assertTrue("returned key bytes must be zeroized", key.all { it == 0.toByte() })
    }
}
