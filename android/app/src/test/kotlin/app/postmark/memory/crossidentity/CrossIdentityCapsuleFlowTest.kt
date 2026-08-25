package app.postmark.memory.create

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.Base64
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import postmark.core.crypto.AccountIdentityGenerator
import postmark.core.crypto.CapsuleAcceptanceGate
import postmark.core.crypto.CapsuleAcceptanceInput
import postmark.core.crypto.CapsuleAcceptanceResult
import postmark.core.crypto.RecipientEnvelopeCryptor
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.outbox.CapsuleOutboxStager
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.RecipientEnvelopeContextInput
import postmark.core.model.UserId
import postmark.core.recognition.FingerprintSide
import postmark.core.recognition.RecognitionProfile

/**
 * FIX-REVIEW-04 regression: persisted/authenticated capsule material carries
 * SEPARATE sender and recipient identities - immutable user IDs, distinct key
 * bundle IDs, and the sender's public signing keyset. A capsule sealed for a
 * DIFFERENT recipient opens ONLY with that recipient's private key, and the
 * acceptance gate verifies using the ROW-CARRIED sender bundle/keyset rather
 * than assuming the authenticated account is the sender.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossIdentityCapsuleFlowTest {

    private lateinit var context: Context
    private lateinit var database: PostmarkLocalDatabase
    private lateinit var outboxDir: File

    private val senderIdentity = AccountIdentityGenerator().generate()
    private val recipientIdentity = AccountIdentityGenerator().generate()
    private val capsuleUuid = UUID.fromString("8c111111-2222-4333-8444-555555555555")
    private val senderUuid = UUID.fromString("8c222222-3333-4444-8555-666666666666")
    private val recipientUuid = UUID.fromString("8c333333-4444-4555-8666-777777777777")
    private val senderBundleUuid = UUID.fromString("8c444444-5555-4666-8777-888888888888")
    private val recipientBundleUuid = UUID.fromString("8c555555-6666-4777-8888-999999999999")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PostmarkLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outboxDir = File(context.filesDir, "cross-identity-outbox").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.filesDir.listFiles()?.filter { it.name.startsWith("cross-identity") }?.forEach {
            it.deleteRecursively()
        }
    }

    private fun syntheticFingerprint(seed: Int, side: FingerprintSide): ByteArray {
        val profile = RecognitionProfile.mvpOrbV1()
        val keypoints = List(64) {
            postmark.core.recognition.FingerprintKeypoint(
                xNormalized = (it % 8) / 8.0,
                yNormalized = (it / 8) / 8.0,
                scaleNormalized = 1.0,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        return postmark.core.recognition.FingerprintCodec.serialize(
            postmark.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                side = side,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = seed.toLong(),
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() }
                },
                quality = postmark.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        )
    }

    @Test
    fun persistedMaterialCarriesSeparateIdentitiesAndVerifiesWithoutConflation() = runBlocking {
        // ---- PUBLISH: sender S -> recipient R, two real identities ----
        val prepared = SameAccountCapsulePublisher().publish(
            SameAccountCapsuleRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(senderUuid),
                recipientUserId = UserId(recipientUuid),
                senderKeyBundleId = KeyBundleId(senderBundleUuid),
                recipientKeyBundleId = KeyBundleId(recipientBundleUuid),
                senderHandleSnapshot = "sender-handle",
                createdAtEpochSeconds = 1_700_000_000L,
                photoJpegs = (0 until 3).map { "photo-$it".toByteArray() },
                photoWidthsPx = listOf(800, 800, 800),
                photoHeightsPx = listOf(600, 600, 600),
                noteUtf8 = null,
                frontFingerprintBytes = syntheticFingerprint(11, FingerprintSide.FRONT),
                backFingerprintBytes = syntheticFingerprint(22, FingerprintSide.BACK),
                signingKeyset = senderIdentity.signingPrivateHandle,
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    recipientIdentity.encryptionPublicKeyset,
                ),
            ),
        )
        CapsuleOutboxStager(database, outboxDir).stage(prepared)

        // ---- PERSISTED routing identities are separate and complete ----
        val row = database.outboxCapsuleDao().getByCapsuleId(capsuleUuid.toString())!!
        assertEquals(senderUuid.toString(), row.senderUserId)
        assertEquals(recipientUuid.toString(), row.recipientUserId)
        assertNotEquals(row.senderUserId, row.recipientUserId)
        assertEquals(senderBundleUuid.toString(), row.senderKeyBundleId)
        assertEquals(recipientBundleUuid.toString(), row.recipientKeyBundleId)
        assertNotNull(row.senderSigningPublicKeysetB64)

        // The carried public keyset IS the sender's Ed25519 verification key.
        val senderVerifierFromRow = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            Base64.urlSafeDecode(requireNotNull(row.senderSigningPublicKeysetB64)),
        )
        val expectedSenderVerifier = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
            senderIdentity.signingPublicKeyset,
        )
        assertEquals(
            com.google.protobuf.ByteString.copyFrom(senderIdentity.signingPublicKeyset),
            com.google.protobuf.ByteString.copyFrom(
                Base64.urlSafeDecode(requireNotNull(row.senderSigningPublicKeysetB64)),
            ),
        )

        // ---- ENVELOPE opens ONLY for the bound recipient identity ----
        val envelopeBytes = File(requireNotNull(row.envelopePath)).readBytes()
        val openedForRecipient = RecipientEnvelopeCryptor().open(
            recipientIdentity.encryptionPrivateHandle,
            RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid),
                UserId(senderUuid),
                UserId(recipientUuid),
                KeyBundleId(recipientBundleUuid),
            ),
            envelopeBytes,
        )
        assertTrue(openedForRecipient.isNotEmpty())

        // The sender's private key CANNOT open an envelope addressed to R:
        // the identities are genuinely separate, not conflated defaults.
        val wrongOpen = runCatching {
            RecipientEnvelopeCryptor().open(
                senderIdentity.encryptionPrivateHandle,
                RecipientEnvelopeContextInput(
                    CapsuleId(capsuleUuid),
                    UserId(senderUuid),
                    UserId(recipientUuid),
                    KeyBundleId(recipientBundleUuid),
                ),
                envelopeBytes,
            )
        }
        assertTrue(wrongOpen.isFailure)

        // ---- ACCEPTANCE GATE uses the ROW-CARRIED sender verifier/bundle ----
        val blobs = database.outboxBlobDao().getAllByCapsuleId(capsuleUuid.toString())
        val accepted = CapsuleAcceptanceGate().verify(
            CapsuleAcceptanceInput(
                expectedCapsuleId = CapsuleId(capsuleUuid),
                authenticatedUserId = UserId(recipientUuid),
                senderVerifyingKeyset = senderVerifierFromRow,
                expectedSenderKeyBundleId = KeyBundleId(senderBundleUuid),
                envelopePlaintextBytes = openedForRecipient,
                statementBytes = File(requireNotNull(row.publishStatementPath)).readBytes(),
                signature = File(requireNotNull(row.publishStatementSignaturePath)).readBytes(),
                deliveredBlobs = blobs.map { blobRow ->
                    val file = File(blobRow.localCiphertextPath)
                    postmark.core.crypto.DeliveredBlob(
                        blobId = postmark.core.model.BlobId(UUID.fromString(blobRow.blobId)),
                        ciphertextSize = file.length(),
                        ciphertextSha256 =
                            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()),
                    )
                },
            ),
        )
        assertTrue(
            "the real gate must accept with row-carried sender material",
            accepted is CapsuleAcceptanceResult.Accepted,
        )

        // And refuses when the sender bundle is claimed as the recipient's:
        val conflated = runCatching {
            CapsuleAcceptanceGate().verify(
                CapsuleAcceptanceInput(
                    expectedCapsuleId = CapsuleId(capsuleUuid),
                    authenticatedUserId = UserId(recipientUuid),
                    senderVerifyingKeyset = expectedSenderVerifier,
                    expectedSenderKeyBundleId = KeyBundleId(recipientBundleUuid),
                    envelopePlaintextBytes = openedForRecipient,
                    statementBytes = File(requireNotNull(row.publishStatementPath)).readBytes(),
                    signature = File(requireNotNull(row.publishStatementSignaturePath)).readBytes(),
                    deliveredBlobs = blobs.map { blobRow ->
                        val file = File(blobRow.localCiphertextPath)
                        postmark.core.crypto.DeliveredBlob(
                            blobId = postmark.core.model.BlobId(UUID.fromString(blobRow.blobId)),
                            ciphertextSize = file.length(),
                            ciphertextSha256 =
                                java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(file.readBytes()),
                        )
                    },
                ),
            )
        }
        assertTrue(conflated.isFailure || conflated.getOrNull() is CapsuleAcceptanceResult.Rejected)
    }

    @Test
    fun selfSendRemainsNaturalWithDistinctButEqualValues() = runBlocking {
        // M1 self-send: explicit same VALUES, no equality assumption anywhere.
        val prepared = SameAccountCapsulePublisher().publish(
            SameAccountCapsuleRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(senderUuid),
                senderKeyBundleId = KeyBundleId(senderBundleUuid),
                senderHandleSnapshot = "mykola",
                createdAtEpochSeconds = 1_700_000_000L,
                photoJpegs = (0 until 3).map { "photo-$it".toByteArray() },
                photoWidthsPx = listOf(800, 800, 800),
                photoHeightsPx = listOf(600, 600, 600),
                noteUtf8 = null,
                frontFingerprintBytes = syntheticFingerprint(11, FingerprintSide.FRONT),
                backFingerprintBytes = syntheticFingerprint(22, FingerprintSide.BACK),
                signingKeyset = senderIdentity.signingPrivateHandle,
                recipientEncryptionPublicKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                    senderIdentity.encryptionPublicKeyset,
                ),
            ),
        )
        CapsuleOutboxStager(database, outboxDir).stage(prepared)

        val row = database.outboxCapsuleDao().getByCapsuleId(capsuleUuid.toString())!!
        assertEquals(senderUuid.toString(), row.senderUserId)
        assertEquals(senderUuid.toString(), row.recipientUserId)
        assertEquals(senderBundleUuid.toString(), row.senderKeyBundleId)
        assertEquals(senderBundleUuid.toString(), row.recipientKeyBundleId)

        // Opens naturally with our own private half under the same contexts.
        val envelopeBytes = File(requireNotNull(row.envelopePath)).readBytes()
        val opened = RecipientEnvelopeCryptor().open(
            senderIdentity.encryptionPrivateHandle,
            RecipientEnvelopeContextInput(
                CapsuleId(capsuleUuid),
                UserId(senderUuid),
                UserId(senderUuid),
                KeyBundleId(senderBundleUuid),
            ),
            envelopeBytes,
        )
        assertTrue(opened.isNotEmpty())
    }
}
