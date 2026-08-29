package dev.hryshyn.remanence.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.ControlIndexAcceptanceGate
import dev.hryshyn.remanence.core.crypto.PublishStatementSigner
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.BlobCacheEntity
import dev.hryshyn.remanence.core.data.db.BlobCacheState
import dev.hryshyn.remanence.core.data.db.IncomingCapsuleEntity
import dev.hryshyn.remanence.core.data.db.IncomingEnvelopeEntity
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.model.ArtifactSlot
import dev.hryshyn.remanence.core.model.CapsuleArtifactKind
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.LocalMaterialState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.PublishArtifact
import dev.hryshyn.remanence.core.model.PublishStatementBuildResult
import dev.hryshyn.remanence.core.model.PublishStatementBuilder
import dev.hryshyn.remanence.core.model.PublishStatementInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.identity.SenderKeyResolution as TrustedSenderResolution
import dev.hryshyn.remanence.identity.TrustedSenderKeyStore
import dev.hryshyn.remanence.protocol.v1.RecipientEnvelopePlaintext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingControlIndexAcceptanceCoordinatorTest {

    private lateinit var database: RemanenceLocalDatabase
    private lateinit var tempDirectory: File
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tempDirectory = Files.createTempDirectory("a11b-coordinator").toFile()
        fixture = buildFixture()
        installFixture()
    }

    @After
    fun tearDown() {
        database.close()
        tempDirectory.deleteRecursively()
    }

    @Test
    fun validEnvelopeAndRecognitionAreAcceptedByTheExistingGate() = runBlocking {
        val result = coordinator().accept(request())

        val verified = result as? IncomingControlIndexAcceptanceResult.Verified
            ?: error("expected verified result, got $result")
        assertEquals(fixture.capsuleId.toProtoBytes(), verified.statement.capsuleId)
        assertArrayEquals(fixture.frontFingerprint, verified.recognition.frontFingerprint)
        assertArrayEquals(fixture.backFingerprint, verified.recognition.backFingerprint)
    }

    @Test
    fun wrongOwnerFailsBeforeReadingOrCallingTrustedSenderBoundary() = runBlocking {
        var senderLookups = 0
        val result = coordinator(
            trustedSenderKeys = fakeTrustedSender { _, _ ->
                senderLookups += 1
                TrustedSenderResolution.Trusted(fixture.senderPublicKeyset)
            },
        ).accept(request(owner = OTHER_OWNER))

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.OWNER_MISMATCH,
            ),
            result,
        )
        assertEquals(0, senderLookups)
    }

    @Test
    fun persistedRecipientBundleMustMatchCurrentActiveBundle() = runBlocking {
        val result = coordinator(
            currentIdentityProvider = {
                CurrentRecipientEncryptionIdentity(
                    ownerUserId = OWNER,
                    activeKeyBundleId = OTHER_BUNDLE,
                    encryptionPrivateKeyset = fixture.recipientIdentity.encryptionPrivateHandle,
                )
            },
        ).accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.RECIPIENT_KEY_BUNDLE_MISMATCH,
            ),
            result,
        )
    }

    @Test
    fun envelopeContextIsDerivedFromPersistedIdentityAndFailsOnMismatch() = runBlocking {
        installFixture(senderUserId = OTHER_SENDER.toRestString())

        val result = coordinator().accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.ENVELOPE_OPEN_FAILED,
            ),
            result,
        )
    }

    @Test
    fun envelopeTransportHashMismatchIsRejectedBeforeOpen() = runBlocking {
        installFixture(envelopeSha256 = ByteArray(32) { 7 })

        val result = coordinator().accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.ENVELOPE_TRANSPORT_INTEGRITY,
            ),
            result,
        )
    }

    @Test
    fun untrustedSenderKeyIsTerminalWithoutFallback() = runBlocking {
        val result = coordinator(
            trustedSenderKeys = fakeTrustedSender { _, _ ->
                TrustedSenderResolution.Untrusted(
                    dev.hryshyn.remanence.identity.SenderKeyUntrustedReason.REVOKED,
                )
            },
        ).accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.SENDER_KEY_REJECTED,
            ),
            result,
        )
    }

    @Test
    fun unavailableSenderKeyIsRetryableWithoutFallback() = runBlocking {
        val result = coordinator(
            trustedSenderKeys = fakeTrustedSender { _, _ ->
                TrustedSenderResolution.Unavailable(
                    dev.hryshyn.remanence.identity.SenderKeyUnavailableReason.DIRECTORY_UNAVAILABLE,
                )
            },
        ).accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Retryable(
                IncomingAcceptanceRetryReason.SENDER_KEY_UNAVAILABLE,
            ),
            result,
        )
    }

    @Test
    fun senderLookupExceptionIsRetryable() = runBlocking {
        val result = coordinator(
            trustedSenderKeys = fakeTrustedSender {
                _, _ -> throw IOException("transport unavailable")
            },
        ).accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Retryable(
                IncomingAcceptanceRetryReason.SENDER_KEY_UNAVAILABLE,
            ),
            result,
        )
    }

    @Test
    fun wrongSenderVerificationKeyIsAnIntegrityRejection() = runBlocking {
        val otherSender = AccountIdentityGenerator().generate()
        val otherPublic = TinkProtoKeysetFormat.parseKeysetWithoutSecret(otherSender.signingPublicKeyset)

        val result = coordinator(
            trustedSenderKeys = fakeTrustedSender { _, _ ->
                TrustedSenderResolution.Trusted(otherPublic)
            },
        ).accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.SIGNATURE_INVALID,
            ),
            result,
        )
    }

    @Test
    fun invalidSignatureIsRejectedByTheGate() = runBlocking {
        val badSignature = fixture.signature.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        installFixture(signature = badSignature)

        val result = coordinator().accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.SIGNATURE_INVALID,
            ),
            result,
        )
    }

    @Test
    fun recognitionBlobBindingAndTransportHashAreCheckedAgainstActualBytes() = runBlocking {
        installFixture(recognitionBlobId = OTHER_BLOB)
        val bindingResult = coordinator().accept(request())
        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.RECOGNITION_BINDING_REJECTED,
            ),
            bindingResult,
        )

        installFixture(recognitionBlobId = fixture.recognitionBlobId.toRestString(), recognitionSha256 = ByteArray(32) { 3 })
        val hashResult = coordinator().accept(request())
        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.RECOGNITION_TRANSPORT_INTEGRITY,
            ),
            hashResult,
        )
    }

    @Test
    fun tamperedRecognitionCiphertextIsRejectedWithoutPlaintextOutput() = runBlocking {
        val tampered = fixture.recognitionCiphertext.copyOf().also {
            it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte()
        }
        val file = tempDirectory.resolve("recognition-tampered.bin")
        file.writeBytes(tampered)
        installFixture(recognitionSha256 = sha256(tampered), recognitionSize = tampered.size.toLong())

        val result = coordinator().accept(request(file))

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.RECOGNITION_BINDING_REJECTED,
            ),
            result,
        )
    }

    @Test
    fun oversizedAndTruncatedRecognitionFilesFailClosed() = runBlocking {
        installFixture(
            recognitionSize = ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES + 1,
            recognitionSha256 = ByteArray(32),
        )
        val oversized = tempDirectory.resolve("recognition-oversized.bin")
        oversized.writeBytes(ByteArray(ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES.toInt() + 1))
        val oversizedResult = coordinator().accept(request(oversized))
        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
            ),
            oversizedResult,
        )

        installFixture(recognitionSize = fixture.recognitionCiphertext.size.toLong() + 1)
        val truncatedResult = coordinator().accept(request())
        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.RECOGNITION_CIPHERTEXT_INVALID,
            ),
            truncatedResult,
        )
    }

    @Test
    fun unavailableRecognitionFileIsRetryableAndCancellationPropagates() = runBlocking {
        val missing = tempDirectory.resolve("missing.bin")
        val missingResult = coordinator().accept(request(missing))
        assertEquals(
            IncomingControlIndexAcceptanceResult.Retryable(
                IncomingAcceptanceRetryReason.LOCAL_STORAGE_UNAVAILABLE,
            ),
            missingResult,
        )

        val cancellation = CancellationException("cancelled")
        var cancelled = false
        try {
            coordinator(currentIdentityProvider = { throw cancellation }).accept(request())
        } catch (thrown: CancellationException) {
            cancelled = thrown === cancellation
        }
        assertTrue(cancelled)
    }

    @Test
    fun recognitionReadFailureWipesPartiallyReadBuffer() = runBlocking {
        var readBuffer: ByteArray? = null
        var firstRead = true
        val failingStream = object : InputStream() {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readBuffer = buffer
                if (firstRead) {
                    firstRead = false
                    fixture.recognitionCiphertext.copyInto(buffer, offset)
                    return fixture.recognitionCiphertext.size
                }
                throw IOException("simulated storage read failure")
            }

            override fun read(): Int = throw IOException("simulated storage read failure")
        }
        val result = coordinator(
            recognitionInputStreamFactory = { failingStream },
        ).accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Retryable(
                IncomingAcceptanceRetryReason.LOCAL_STORAGE_UNAVAILABLE,
            ),
            result,
        )
        assertTrue(readBuffer != null && readBuffer!!.all { it == 0.toByte() })
    }

    @Test
    fun missingMetadataRetainsDistinctRejectionCodes() = runBlocking {
        database.incomingCapsuleDao().clearForOwner(OWNER.toRestString())
        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.CAPSULE_METADATA_MISSING,
            ),
            coordinator().accept(request()),
        )

        installFixture()
        database.incomingEnvelopeDao().clearForOwner(OWNER.toRestString())
        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.ENVELOPE_METADATA_MISSING,
            ),
            coordinator().accept(request()),
        )

        installFixture()
        database.blobCacheDao().clearForOwner(OWNER.toRestString())
        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.RECOGNITION_METADATA_INVALID,
            ),
            coordinator().accept(request()),
        )
    }

    @Test
    fun accountSwitchAfterCryptoAcceptanceFailsClosed() = runBlocking {
        var calls = 0
        val otherIdentity = CurrentRecipientEncryptionIdentity(
            ownerUserId = OTHER_OWNER,
            activeKeyBundleId = fixture.recipientKeyBundleId,
            encryptionPrivateKeyset = fixture.recipientIdentity.encryptionPrivateHandle,
        )
        val result = coordinator(
            currentIdentityProvider = {
                calls += 1
                if (calls < 3) currentIdentity() else otherIdentity
            },
        ).accept(request())

        assertEquals(
            IncomingControlIndexAcceptanceResult.Rejected(
                IncomingAcceptanceRejectionReason.ACCOUNT_CHANGED,
            ),
            result,
        )
    }

    @Test
    fun redactedTypesNeverIncludeFilePathOrCryptoDetails() {
        val path = tempDirectory.resolve("private-recognition-path.bin").path
        assertFalse(request().toString().contains(path))
        assertFalse(
            CurrentRecipientEncryptionIdentity(
                OWNER,
                fixture.recipientKeyBundleId,
                fixture.recipientIdentity.encryptionPrivateHandle,
            ).toString().contains("KeysetHandle"),
        )
        assertEquals(
            "IncomingControlIndexAcceptanceResult.Verified(<redacted>)",
            IncomingControlIndexAcceptanceResult.Verified(
                fixture.statement,
                fixture.recognition,
            ).toString(),
        )
    }

    private fun coordinator(
        currentIdentityProvider: suspend () -> CurrentRecipientEncryptionIdentity? = { this.currentIdentity() },
        trustedSenderKeys: TrustedSenderKeyStore = fakeTrustedSender { _, _ ->
            TrustedSenderResolution.Trusted(fixture.senderPublicKeyset)
        },
        recognitionInputStreamFactory: (File) -> InputStream = { file -> file.inputStream() },
    ) = IncomingControlIndexAcceptanceCoordinator(
        incomingCapsuleDao = database.incomingCapsuleDao(),
        incomingEnvelopeDao = database.incomingEnvelopeDao(),
        blobCacheDao = database.blobCacheDao(),
        currentRecipientIdentity = currentIdentityProvider,
        trustedSenderKeys = trustedSenderKeys,
        acceptanceGate = ControlIndexAcceptanceGate(),
        envelopeCryptor = RecipientEnvelopeCryptor(),
        recognitionInputStreamFactory = recognitionInputStreamFactory,
    )

    private fun currentIdentity() = CurrentRecipientEncryptionIdentity(
        ownerUserId = OWNER,
        activeKeyBundleId = fixture.recipientKeyBundleId,
        encryptionPrivateKeyset = fixture.recipientIdentity.encryptionPrivateHandle,
    )

    private fun request(file: File = fixture.recognitionFile, owner: UserId = OWNER) =
        IncomingControlIndexAcceptanceRequest(owner, fixture.capsuleId, file)

    private fun installFixture(
        senderUserId: String = fixture.senderUserId.toRestString(),
        signature: ByteArray = fixture.signature,
        envelopeSha256: ByteArray = fixture.envelopeSha256,
        recognitionBlobId: String = fixture.recognitionBlobId.toRestString(),
        recognitionSha256: ByteArray = fixture.recognitionSha256,
        recognitionSize: Long = fixture.recognitionCiphertext.size.toLong(),
    ) = runBlocking {
        // Each variant replaces immutable routed metadata as a fresh test
        // record; the production replay path intentionally preserves those
        // fields and is exercised by the A09 tests.
        database.clearAllTables()
        database.incomingCapsuleDao().upsertAllForOwner(
            OWNER.toRestString(),
            listOf(
                IncomingCapsuleEntity(
                    capsuleId = fixture.capsuleId.toRestString(),
                    ownerUserId = OWNER.toRestString(),
                    senderUserId = senderUserId,
                    recipientUserId = OWNER.toRestString(),
                    senderSigningKeyBundleId = fixture.senderKeyBundleId.toRestString(),
                    recipientEncryptionKeyBundleId = fixture.recipientKeyBundleId.toRestString(),
                    protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
                    serverStatus = "READY",
                    readyAtEpochMs = 1_700_000_000_000,
                    signedStatementBytes = fixture.statementBytes,
                    signedStatementSha256 = sha256(fixture.statementBytes),
                    publishSignatureBytes = signature,
                    materialState = LocalMaterialState.DISCOVERED,
                ),
            ),
        )
        database.incomingEnvelopeDao().upsertForOwner(
            OWNER.toRestString(),
            IncomingEnvelopeEntity(
                capsuleId = fixture.capsuleId.toRestString(),
                ownerUserId = OWNER.toRestString(),
                recipientKeyBundleId = fixture.recipientKeyBundleId.toRestString(),
                hpkeCiphertext = fixture.envelopeCiphertext,
                transportSha256 = envelopeSha256,
                receivedAtEpochMs = 1_700_000_000_000,
            ),
        )
        database.blobCacheDao().upsertForOwner(
            OWNER.toRestString(),
            BlobCacheEntity(
                blobId = recognitionBlobId,
                ownerUserId = OWNER.toRestString(),
                capsuleId = fixture.capsuleId.toRestString(),
                kind = CapsuleArtifactKind.RECOGNITION_MANIFEST.name,
                ordinal = null,
                expectedSizeBytes = recognitionSize,
                expectedSha256 = recognitionSha256,
                localPath = tempDirectory.resolve("db-recognition-path").path,
                cacheState = BlobCacheState.DOWNLOADING,
            ),
        )
    }

    private fun fakeTrustedSender(
        resolution: suspend (UserId, KeyBundleId) -> TrustedSenderResolution,
    ) = object : TrustedSenderKeyStore {
        override suspend fun senderVerifyingKeyset(
            senderUserId: UserId,
            senderKeyBundleId: KeyBundleId,
        ): TrustedSenderResolution = resolution(senderUserId, senderKeyBundleId)
    }

    private fun buildFixture(): Fixture {
        val senderIdentity = AccountIdentityGenerator().generate()
        val recipientIdentity = AccountIdentityGenerator().generate()
        val capsuleKeyset = CapsuleKeysetGenerator().generate()
        val recognitionBlobId = BlobIdValue(UUID.fromString("a1000000-0000-4000-8000-000000000001"))
        val contentBlobId = BlobIdValue(UUID.fromString("a1000000-0000-4000-8000-000000000002"))
        val photoIds = listOf(
            BlobIdValue(UUID.fromString("a1000000-0000-4000-8000-000000000003")),
            BlobIdValue(UUID.fromString("a1000000-0000-4000-8000-000000000004")),
            BlobIdValue(UUID.fromString("a1000000-0000-4000-8000-000000000005")),
        )
        val front = ByteArray(96) { (it * 3).toByte() }
        val back = ByteArray(96) { (it * 7 + 1).toByte() }
        val codec = RecognitionManifestCodec()
        val recognitionCiphertext = codec.buildAndEncrypt(
            capsuleKeyset = capsuleKeyset,
            routingContext = RecognitionManifestCodec.RoutingContext(
                capsuleId = CAPSULE,
                blobId = recognitionBlobId,
                senderUserId = SENDER,
                recipientUserId = OWNER,
            ),
            senderHandleSnapshot = "mykola",
            createdAtEpochSeconds = 1_700_000_000L,
            placeLabel = null,
            frontFingerprint = front,
            backFingerprint = back,
        )
        val artifacts = listOf(
            PublishArtifact(
                ArtifactSlot(recognitionBlobId, CapsuleArtifactKind.RECOGNITION_MANIFEST, -1),
                recognitionCiphertext.size.toLong(),
                ByteString.copyFrom(sha256(recognitionCiphertext)),
            ),
            PublishArtifact(
                ArtifactSlot(contentBlobId, CapsuleArtifactKind.CONTENT_MANIFEST, -1),
                200L,
                ByteString.copyFrom(sha256("content".toByteArray())),
            ),
        ) + photoIds.mapIndexed { index, id ->
            PublishArtifact(
                ArtifactSlot(id, CapsuleArtifactKind.PHOTO, index),
                300L + index,
                ByteString.copyFrom(sha256("photo-$index".toByteArray())),
            )
        }
        val built = PublishStatementBuilder.build(
            PublishStatementInput(
                capsuleId = CAPSULE,
                senderUserId = SENDER,
                recipientUserId = OWNER,
                senderKeyBundleId = SENDER_BUNDLE,
                recipientKeyBundleId = RECIPIENT_BUNDLE,
                createdAtEpochSeconds = 1_700_000_000L,
                artifacts = artifacts,
            ),
        ) as PublishStatementBuildResult.Success
        val statementBytes = built.deterministicBytes.toByteArray()
        val signature = PublishStatementSigner().sign(senderIdentity.signingPrivateHandle, statementBytes).signature
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
        val recognitionFile = tempDirectory.resolve("recognition.bin").apply {
            writeBytes(recognitionCiphertext)
        }
        return Fixture(
            capsuleId = CAPSULE,
            senderUserId = SENDER,
            senderKeyBundleId = SENDER_BUNDLE,
            recipientKeyBundleId = RECIPIENT_BUNDLE,
            recognitionBlobId = recognitionBlobId,
            senderPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderIdentity.signingPublicKeyset),
            recipientIdentity = recipientIdentity,
            statement = built.statement,
            statementBytes = statementBytes,
            signature = signature,
            envelopeCiphertext = envelopeCiphertext,
            envelopeSha256 = sha256(envelopeCiphertext),
            recognitionCiphertext = recognitionCiphertext,
            recognitionSha256 = sha256(recognitionCiphertext),
            recognitionFile = recognitionFile,
            recognition = RecognitionManifestCodec().decryptAndParse(
                capsuleKeyset,
                RecognitionManifestCodec.RoutingContext(CAPSULE, recognitionBlobId, SENDER, OWNER),
                recognitionCiphertext,
            ),
            frontFingerprint = front,
            backFingerprint = back,
        )
    }

    private data class Fixture(
        val capsuleId: CapsuleId,
        val senderUserId: UserId,
        val senderKeyBundleId: KeyBundleId,
        val recipientKeyBundleId: KeyBundleId,
        val recognitionBlobId: BlobIdValue,
        val senderPublicKeyset: KeysetHandle,
        val recipientIdentity: AccountIdentityGenerator.AccountIdentity,
        val statement: dev.hryshyn.remanence.protocol.v1.PublishStatement,
        val statementBytes: ByteArray,
        val signature: ByteArray,
        val envelopeCiphertext: ByteArray,
        val envelopeSha256: ByteArray,
        val recognitionCiphertext: ByteArray,
        val recognitionSha256: ByteArray,
        val recognitionFile: File,
        val recognition: dev.hryshyn.remanence.core.crypto.RecognitionManifestContent,
        val frontFingerprint: ByteArray,
        val backFingerprint: ByteArray,
    )

    private typealias BlobIdValue = dev.hryshyn.remanence.core.model.BlobId

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        val CAPSULE = CapsuleId(UUID.fromString("1a111111-2222-4333-8444-555555555555"))
        val OWNER = UserId(UUID.fromString("3a333333-4444-4555-8666-777777777777"))
        val OTHER_OWNER = UserId(UUID.fromString("8a888888-8888-4888-8888-888888888888"))
        val SENDER = UserId(UUID.fromString("2a222222-3333-4444-8555-666666666666"))
        val OTHER_SENDER = UserId(UUID.fromString("9a999999-9999-4999-8999-999999999999"))
        val SENDER_BUNDLE = KeyBundleId(UUID.fromString("4a444444-5555-4666-8777-888888888888"))
        val RECIPIENT_BUNDLE = KeyBundleId(UUID.fromString("5a555555-6666-4777-8888-999999999999"))
        val OTHER_BUNDLE = KeyBundleId(UUID.fromString("6a666666-7777-4888-8999-aaaaaaaaaaaa"))
        val OTHER_BLOB = "b1000000-0000-4000-8000-000000000001"
    }
}
