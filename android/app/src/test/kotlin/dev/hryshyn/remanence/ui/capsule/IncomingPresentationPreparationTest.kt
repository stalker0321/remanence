package dev.hryshyn.remanence.ui.capsule

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.ContentManifestCodec
import dev.hryshyn.remanence.core.crypto.ManifestPhoto
import dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor
import dev.hryshyn.remanence.core.crypto.PresentationAcceptanceGate
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.data.db.BlobCacheEntity
import dev.hryshyn.remanence.core.data.db.BlobCacheState
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeEntity
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.PublishArtifact
import dev.hryshyn.remanence.core.model.PublishStatementBuildResult
import dev.hryshyn.remanence.core.model.PublishStatementBuilder
import dev.hryshyn.remanence.core.model.PublishStatementInput
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.ExtractionQuality
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintKeypoint
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.index.SenderIndexBundleAad
import dev.hryshyn.remanence.index.SenderIndexBundleCodec
import dev.hryshyn.remanence.index.SenderIndexBundlePlaintext
import dev.hryshyn.remanence.index.SenderIndexBundleReadResult
import dev.hryshyn.remanence.index.SenderIndexBundleReadRequest
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import dev.hryshyn.remanence.index.SenderIndexBundleSenderVerification
import dev.hryshyn.remanence.index.SenderIndexBundleStageRequest
import dev.hryshyn.remanence.index.SenderIndexBundleStageResult
import dev.hryshyn.remanence.index.SenderIndexBundleStager
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import dev.hryshyn.remanence.sync.CurrentRecipientEncryptionIdentity
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingPresentationPreparationTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        val context = ApplicationProvider.getApplicationContext<Context>()
        filesDir = File(context.cacheDir, "incoming-presentation-${System.nanoTime()}").apply {
            check(mkdirs())
        }
        roots = AccountScopedFileRoots(filesDir)
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fixture = buildFixture()
        installFixture(fixture)
    }

    @After
    fun tearDown() {
        database.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun validCachedMaterialPreparesAfterProcessStyleReconstructionAndClosesSnapshot() = runBlocking {
        val result = preparation().prepare(OWNER, CAPSULE)
        val prepared = requireType<IncomingPresentationPreparationResult.Prepared>(result).presentation
        assertEquals(3, prepared.photoCount)
        assertEquals("offline note", prepared.noteText())
        val photo = prepared.loadPhoto(1)
        try {
            assertArrayEquals(ByteArray(1024) { (37 + it).toByte() }, photo)
        } finally {
            photo.fill(0)
        }
        prepared.close()
        prepared.close()
        var closed = false
        try {
            prepared.loadPhoto(0)
        } catch (_: IllegalStateException) {
            closed = true
        }
        assertTrue(closed)
    }

    @Test
    fun legacyV1BundleRemainsReadableForRecognitionButFailsPresentationClosed() = runBlocking {
        val recognition = fixture.recognition
        val legacy = SenderIndexBundlePlaintext(
            localFormatVersion = SenderIndexBundleCodec.LEGACY_FORMAT_VERSION,
            capsuleId = CAPSULE,
            senderHandleSnapshot = recognition.senderHandleSnapshot,
            createdAtEpochSeconds = recognition.createdAtEpochSeconds,
            placeLabel = recognition.placeLabel,
            frontFingerprint = recognition.frontFingerprint,
            backFingerprint = recognition.backFingerprint,
            senderVerification = null,
        )
        val codec = SenderIndexBundleCodec()
        val encoded = codec.encode(legacy)
        val encrypted = fixture.sealer.seal(
            encoded,
            SenderIndexBundleAad.encode(
                OWNER,
                CAPSULE,
                SenderIndexBundleCodec.LEGACY_FORMAT_VERSION,
            ),
        )
        writeIndex(encrypted)
        val recognitionRead = SenderIndexBundleReader(roots, fixture.sealer).inspect(
            SenderIndexBundleReadRequest(OWNER, OWNER, CAPSULE),
        )
        val snapshot = requireType<SenderIndexBundleReadResult.Available>(recognitionRead).snapshot
        assertEquals(SenderIndexBundleCodec.LEGACY_FORMAT_VERSION, snapshot.localFormatVersion)
        snapshot.close()

        val result = preparation().prepare(OWNER, CAPSULE)
        assertEquals(
            IncomingPresentationPreparationRejection.SENDER_INDEX_INVALID,
            requireType<IncomingPresentationPreparationResult.Rejected>(result).reason,
        )
        encoded.fill(0)
        encrypted.fill(0)
        legacy.wipe()
    }

    @Test
    fun missingCachedFileFailsClosedWithoutNetworkOrPlaintextFallback() = runBlocking {
        val missing = fixture.artifacts.last().path(roots)
        check(missing.delete())
        val result = preparation().prepare(OWNER, CAPSULE)
        assertEquals(
            IncomingPresentationPreparationRejection.MATERIAL_INVALID,
            requireType<IncomingPresentationPreparationResult.Rejected>(result).reason,
        )
    }

    @Test
    fun accountChangeBeforePublicationDropsPreparedMaterial() = runBlocking {
        var calls = 0
        val otherIdentity = CurrentRecipientEncryptionIdentity(
            ownerUserId = OTHER_OWNER,
            activeKeyBundleId = RECIPIENT_BUNDLE,
            encryptionPrivateKeyset = fixture.recipientIdentity.encryptionPrivateHandle,
        )
        val result = preparation {
            calls += 1
            if (calls == 1) currentIdentity() else otherIdentity
        }.prepare(OWNER, CAPSULE)
        assertEquals(
            IncomingPresentationPreparationRejection.ACCOUNT_CHANGED,
            requireType<IncomingPresentationPreparationResult.Rejected>(result).reason,
        )
    }

    @Test
    fun wrongOwnerStopsBeforeOwnerScopedRoomRead() = runBlocking {
        val result = preparation().prepare(OTHER_OWNER, CAPSULE)
        assertEquals(
            IncomingPresentationPreparationRejection.OWNER_MISMATCH,
            requireType<IncomingPresentationPreparationResult.Rejected>(result).reason,
        )
    }

    private fun preparation(
        identity: suspend () -> CurrentRecipientEncryptionIdentity? = { currentIdentity() },
    ) = IncomingPresentationPreparation(
        incomingCapsuleDao = database.incomingCapsuleDao(),
        incomingEnvelopeDao = database.incomingEnvelopeDao(),
        blobCacheDao = database.blobCacheDao(),
        roots = roots,
        senderIndexBundleReader = SenderIndexBundleReader(roots, fixture.sealer),
        currentRecipientIdentity = identity,
        acceptanceGate = PresentationAcceptanceGate(),
        envelopeCryptor = RecipientEnvelopeCryptor(),
    )

    private fun currentIdentity() = CurrentRecipientEncryptionIdentity(
        ownerUserId = OWNER,
        activeKeyBundleId = RECIPIENT_BUNDLE,
        encryptionPrivateKeyset = fixture.recipientIdentity.encryptionPrivateHandle,
    )

    private fun installFixture(fixture: Fixture) = runBlocking {
        SenderIndexBundleStager(roots, fixture.sealer).stage(
            SenderIndexBundleStageRequest(
                authenticatedOwnerUserId = OWNER,
                ownerUserId = OWNER,
                capsuleId = CAPSULE,
                verifiedRecognition = fixture.recognition,
                senderVerification = fixture.senderVerification,
            ),
        ) as SenderIndexBundleStageResult.Staged

        database.incomingCapsuleDao().upsertAllForOwner(
            OWNER.toRestString(),
            listOf(
                IncomingCapsuleEntity(
                    capsuleId = CAPSULE.toRestString(),
                    ownerUserId = OWNER.toRestString(),
                    senderUserId = SENDER.toRestString(),
                    recipientUserId = OWNER.toRestString(),
                    senderSigningKeyBundleId = SENDER_BUNDLE.toRestString(),
                    recipientEncryptionKeyBundleId = RECIPIENT_BUNDLE.toRestString(),
                    protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
                    serverStatus = "READY",
                    readyAtEpochMs = 1_700_000_000_000,
                    signedStatementBytes = fixture.statementBytes,
                    signedStatementSha256 = sha256(fixture.statementBytes),
                    publishSignatureBytes = fixture.signature,
                    materialState = LocalMaterialState.DISCOVERED,
                ),
            ),
        )
        check(
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                OWNER.toRestString(), CAPSULE.toRestString(), LocalMaterialState.INDEX_CACHED,
            ) is dev.hryshyn.remanence.core.data.db.LocalMaterialTransitionResult.Accepted,
        )
        check(
            database.incomingCapsuleDao().transitionMaterialStateForOwner(
                OWNER.toRestString(), CAPSULE.toRestString(), LocalMaterialState.MATERIAL_CACHED,
            ) is dev.hryshyn.remanence.core.data.db.LocalMaterialTransitionResult.Accepted,
        )
        database.incomingEnvelopeDao().upsertForOwner(
            OWNER.toRestString(),
            IncomingEnvelopeEntity(
                capsuleId = CAPSULE.toRestString(),
                ownerUserId = OWNER.toRestString(),
                recipientKeyBundleId = RECIPIENT_BUNDLE.toRestString(),
                hpkeCiphertext = fixture.envelopeCiphertext,
                transportSha256 = sha256(fixture.envelopeCiphertext),
                receivedAtEpochMs = 1_700_000_000_001,
            ),
        )
        fixture.artifacts.forEach { artifact ->
            val path = artifact.path(roots)
            check(path.parentFile!!.mkdirs() || path.parentFile!!.isDirectory)
            path.writeBytes(artifact.ciphertext)
            database.blobCacheDao().upsertForOwner(
                OWNER.toRestString(),
                BlobCacheEntity(
                    blobId = artifact.blobId.toRestString(),
                    ownerUserId = OWNER.toRestString(),
                    capsuleId = CAPSULE.toRestString(),
                    kind = artifact.kind.name,
                    ordinal = artifact.ordinal,
                    expectedSizeBytes = artifact.ciphertext.size.toLong(),
                    expectedSha256 = sha256(artifact.ciphertext),
                    localPath = path.path,
                    cacheState = BlobCacheState.CACHED,
                ),
            )
        }
    }

    private fun buildFixture(): Fixture {
        val senderIdentity = AccountIdentityGenerator().generate()
        val recipientIdentity = AccountIdentityGenerator().generate()
        val capsuleKeyset = CapsuleKeysetGenerator().generate()
        val recognition = RecognitionManifestContent(
            protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
            capsuleIdRaw = CAPSULE.toProtoBytes().toByteArray(),
            senderHandleSnapshot = "sender_1",
            createdAtEpochSeconds = 1_700_000_000L,
            placeLabel = "Paris",
            frontFingerprint = fingerprint(FingerprintSide.FRONT),
            backFingerprint = fingerprint(FingerprintSide.BACK),
        )
        val recognitionCiphertext = RecognitionManifestCodec().buildAndEncrypt(
            capsuleKeyset = capsuleKeyset,
            routingContext = RecognitionManifestCodec.RoutingContext(
                CAPSULE,
                RECOGNITION_BLOB,
                SENDER,
                OWNER,
            ),
            senderHandleSnapshot = recognition.senderHandleSnapshot,
            createdAtEpochSeconds = recognition.createdAtEpochSeconds,
            placeLabel = recognition.placeLabel,
            frontFingerprint = recognition.frontFingerprint,
            backFingerprint = recognition.backFingerprint,
        )
        val photoIds = listOf(PHOTO_0, PHOTO_1, PHOTO_2)
        val encryptedPhotos = photoIds.mapIndexed { ordinal, blobId ->
            PhotoArtifactEncryptor().encryptPhoto(
                capsuleKeyset = capsuleKeyset,
                routingContext = RecognitionManifestCodec.RoutingContext(
                    CAPSULE,
                    blobId,
                    SENDER,
                    OWNER,
                ),
                ordinal = ordinal,
                normalizedJpeg = ByteArray(1024) { (ordinal * 37 + it).toByte() },
            )
        }
        val contentCiphertext = ContentManifestCodec().buildAndEncrypt(
            capsuleKeyset = capsuleKeyset,
            routingContext = RecognitionManifestCodec.RoutingContext(
                CAPSULE,
                CONTENT_BLOB,
                SENDER,
                OWNER,
            ),
            photos = photoIds.mapIndexed { ordinal, blobId ->
                ManifestPhoto(blobId.value, ordinal, 2560, 1600)
            },
            note = "offline note",
        )
        val artifacts = listOf(
            Artifact(RECOGNITION_BLOB, CapsuleArtifactKind.RECOGNITION_MANIFEST, null, recognitionCiphertext),
            Artifact(CONTENT_BLOB, CapsuleArtifactKind.CONTENT_MANIFEST, null, contentCiphertext),
        ) + encryptedPhotos.mapIndexed { ordinal, encrypted ->
            Artifact(photoIds[ordinal], CapsuleArtifactKind.PHOTO, ordinal, encrypted.ciphertext)
        }
        val statement = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId = CAPSULE,
                senderUserId = SENDER,
                recipientUserId = OWNER,
                senderKeyBundleId = SENDER_BUNDLE,
                recipientKeyBundleId = RECIPIENT_BUNDLE,
                createdAtEpochSeconds = 1_700_000_000L,
                artifacts = artifacts.map { artifact ->
                    PublishArtifact(
                        slot = ArtifactSlot(artifact.blobId, artifact.kind, artifact.ordinal ?: -1),
                        ciphertextSize = artifact.ciphertext.size.toLong(),
                        ciphertextSha256 = ByteString.copyFrom(sha256(artifact.ciphertext)),
                    )
                },
            ),
        ) as PublishStatementBuildResult.Success
        val statementBytes = statement.deterministicBytes.toByteArray()
        val envelopePlaintext = RecipientEnvelopePlaintext.newBuilder()
            .setProtocolVersion(ProtocolV1Limits.PROTOCOL_VERSION)
            .setCapsuleId(CAPSULE.toProtoBytes())
            .setSenderUserId(SENDER.toProtoBytes())
            .setRecipientUserId(OWNER.toProtoBytes())
            .setSenderKeyBundleId(SENDER_BUNDLE.toProtoBytes())
            .setRecipientKeyBundleId(RECIPIENT_BUNDLE.toProtoBytes())
            .setCapsuleAeadKeyset(
                ByteString.copyFrom(
                    TinkProtoKeysetFormat.serializeKeyset(
                        capsuleKeyset,
                        InsecureSecretKeyAccess.get(),
                    ),
                ),
            )
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statementBytes)))
            .build()
            .toByteArray()
        val envelopeCiphertext = RecipientEnvelopeCryptor().seal(
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(recipientIdentity.encryptionPublicKeyset),
            RecipientEnvelopeContextInput(CAPSULE, SENDER, OWNER, RECIPIENT_BUNDLE),
            envelopePlaintext,
        )
        envelopePlaintext.fill(0)
        return Fixture(
            senderIdentity = senderIdentity,
            recipientIdentity = recipientIdentity,
            sealer = AesGcmSealer(),
            recognition = recognition,
            senderVerification = SenderIndexBundleSenderVerification.fromTrusted(
                SENDER,
                SENDER_BUNDLE,
                TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderIdentity.signingPublicKeyset),
            ),
            statementBytes = statementBytes,
            signature = dev.hryshyn.remanence.core.crypto.PublishStatementSigner()
                .sign(senderIdentity.signingPrivateHandle, statementBytes).signature,
            envelopeCiphertext = envelopeCiphertext,
            artifacts = artifacts,
        )
    }

    private fun writeIndex(bytes: ByteArray) {
        val path = File(
            roots.child(OWNER, AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
            "capsules/${CAPSULE.toRestString()}.index.bundle",
        )
        check(path.parentFile!!.mkdirs() || path.parentFile!!.isDirectory)
        path.writeBytes(bytes)
    }

    private fun fingerprint(side: FingerprintSide): ByteArray = FingerprintCodec.serialize(
        PostcardFingerprint(
            profileId = RecognitionProfile.MVP_ORB_V1_ID,
            side = side,
            canonicalWidthPx = 1200,
            canonicalHeightPx = 800,
            coarseHash64 = 17L,
            keypoints = listOf(
                FingerprintKeypoint(
                    xNormalized = 0.5,
                    yNormalized = 0.5,
                    scaleNormalized = 1.0,
                    angleCentiDegrees = 9000,
                    responseQuantized = 2,
                    octave = 0,
                ),
            ),
            descriptors = listOf(ByteArray(FingerprintCodec.DESCRIPTOR_BYTES) { 3 }),
            quality = ExtractionQuality(1.0, 1.0, 0.1, 0.5),
        ),
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private inline fun <reified T> requireType(value: Any): T {
        assertTrue(value is T)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private data class Artifact(
        val blobId: BlobId,
        val kind: CapsuleArtifactKind,
        val ordinal: Int?,
        val ciphertext: ByteArray,
    ) {
        fun path(roots: AccountScopedFileRoots): File = File(
            roots.child(OWNER, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT),
            "capsules/${CAPSULE.toRestString()}/blobs/${blobId.toRestString()}.ciphertext",
        )
    }

    private data class Fixture(
        val senderIdentity: AccountIdentityGenerator.AccountIdentity,
        val recipientIdentity: AccountIdentityGenerator.AccountIdentity,
        val sealer: SecretSealer,
        val recognition: RecognitionManifestContent,
        val senderVerification: SenderIndexBundleSenderVerification,
        val statementBytes: ByteArray,
        val signature: ByteArray,
        val envelopeCiphertext: ByteArray,
        val artifacts: List<Artifact>,
    )

    private class AesGcmSealer : SecretSealer {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        private val random = SecureRandom()

        override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return iv + cipher.doFinal(plaintext)
        }

        override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray {
            require(ciphertext.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOf(12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        }
    }

    private companion object {
        val OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000e801")
        val OTHER_OWNER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000e802")
        val SENDER = UserId.parseRest("0198f0a0-0000-7000-8000-00000000e803")
        val CAPSULE = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000e804")
        val SENDER_BUNDLE = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000e805")
        val RECIPIENT_BUNDLE = KeyBundleId.parseRest("0198f0a0-0000-7000-8000-00000000e806")
        val RECOGNITION_BLOB = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000e807")
        val CONTENT_BLOB = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000e808")
        val PHOTO_0 = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000e809")
        val PHOTO_1 = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000e80a")
        val PHOTO_2 = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000e80b")
    }
}
