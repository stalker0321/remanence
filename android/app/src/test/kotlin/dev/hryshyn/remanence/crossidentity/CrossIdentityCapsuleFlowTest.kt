package dev.hryshyn.remanence.create

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
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceGate
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceInput
import dev.hryshyn.remanence.core.crypto.CapsuleAcceptanceResult
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.RecipientEnvelopeCryptor
import dev.hryshyn.remanence.core.crypto.SenderRetryKeysetWrapper
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.network.HistoricalKeyBundle
import dev.hryshyn.remanence.core.data.network.KeyBundleFailure
import dev.hryshyn.remanence.core.data.network.KeyBundleByIdResult
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.RecipientEnvelopeContextInput
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.data.storage.SenderRetryMaterialStore

/**
 * FIX-REVIEW-04 / FIX-REVIEW2-04 regression: persisted/authenticated capsule
 * material carries SEPARATE sender and recipient identities, and the
 * acceptance gate verifies ONLY through the TRUSTED sender-key boundary - an
 * authenticated directory fixture here. A storage writer that replaces the
 * row-carried public key cannot influence the trust decision; wrong owner,
 * revoked status, and missing bundles fail closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossIdentityCapsuleFlowTest {

    private companion object {
        const val SUITE = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
    }

    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var outboxDir: File

    private val senderIdentity = AccountIdentityGenerator().generate()
    private val recipientIdentity = AccountIdentityGenerator().generate()
    private val capsuleUuid = UUID.fromString("8c111111-2222-4333-8444-555555555555")
    private val senderUuid = UUID.fromString("8c222222-3333-4444-8555-666666666666")
    private val recipientUuid = UUID.fromString("8c333333-4444-4555-8666-777777777777")
    private val senderBundleUuid = UUID.fromString("8c444444-5555-4666-8777-888888888888")
    private val recipientBundleUuid = UUID.fromString("8c555555-6666-4777-8888-999999999999")
    private val testKekBoundary = SoftwareKekBoundary()
    private val testAlias = "test-sender-retry-${java.util.UUID.randomUUID()}"
    private lateinit var testWrapper: SenderRetryKeysetWrapper

    /** Authenticated directory contents the tests control explicitly. */
    private val directory = mutableMapOf<String, KeyBundleByIdResult>()
    private var directoryFetches = 0

    /** The REAL production boundary over the in-test authenticated directory. */
    private fun trustedStore(
        ownAccount: dev.hryshyn.remanence.identity.DirectorySenderKeyStore.OwnAccount? = null,
    ): dev.hryshyn.remanence.identity.DirectorySenderKeyStore =
        dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
            directoryFetch = { bundleId ->
                directoryFetches += 1
                directory[bundleId]
            },
            ownAccount = { ownAccount },
        )

    @Before
    fun setUp() {
        testKekBoundary.createAes256GcmKey(testAlias)
        testWrapper = SenderRetryKeysetWrapper(testKekBoundary)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RemanenceLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outboxDir = File(context.filesDir, "cross-identity-outbox").apply { mkdirs() }
        directory.clear()
        directoryFetches = 0
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
            dev.hryshyn.remanence.core.recognition.FingerprintKeypoint(
                xNormalized = (it % 8) / 8.0,
                yNormalized = (it / 8) / 8.0,
                scaleNormalized = 1.0,
                angleCentiDegrees = 0,
                responseQuantized = it,
                octave = 0,
            )
        }
        return dev.hryshyn.remanence.core.recognition.FingerprintCodec.serialize(
            dev.hryshyn.remanence.core.recognition.PostcardFingerprint(
                profileId = profile.profileId,
                side = side,
                canonicalWidthPx = profile.capture.canonicalLongEdgePx,
                canonicalHeightPx = 1000,
                coarseHash64 = seed.toLong(),
                keypoints = keypoints,
                descriptors = List(64) { i ->
                    ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() }
                },
                quality = dev.hryshyn.remanence.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
            ),
        )
    }

    private suspend fun publishAndStageCrossIdentity() {
        val prepared = CapsulePublisher(testWrapper, testAlias).publish(
            CapsulePublishRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(senderUuid),
                recipientUserId = UserId(recipientUuid),
                senderKeyBundleId = KeyBundleId(senderBundleUuid),
                recipientKeyBundleId = KeyBundleId(recipientBundleUuid),
                ownerUserId = senderUuid.toString(),
                senderHandleSnapshot = "sender_handle",
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
        CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir), SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir))).stage(prepared)
    }

    @Suppress("SameParameterValue")
    private suspend fun runAcceptanceGate(
        verifier: com.google.crypto.tink.KeysetHandle,
        expectedSenderBundle: KeyBundleId,
        envelopePlaintext: ByteArray,
    ): CapsuleAcceptanceResult {
        val row = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleUuid.toString(), senderUuid.toString())!!
        val blobs = database.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleUuid.toString(), senderUuid.toString())
        return CapsuleAcceptanceGate().verify(
            CapsuleAcceptanceInput(
                expectedCapsuleId = CapsuleId(capsuleUuid),
                authenticatedUserId = UserId(recipientUuid),
                senderVerifyingKeyset = verifier,
                expectedSenderKeyBundleId = expectedSenderBundle,
                envelopePlaintextBytes = envelopePlaintext,
                statementBytes = File(requireNotNull(row.publishStatementPath)).readBytes(),
                signature = File(requireNotNull(row.publishStatementSignaturePath)).readBytes(),
                deliveredBlobs = blobs.map { blobRow ->
                    val file = File(blobRow.localCiphertextPath)
                    dev.hryshyn.remanence.core.crypto.DeliveredBlob(
                        blobId = dev.hryshyn.remanence.core.model.BlobId(UUID.fromString(blobRow.blobId)),
                        ciphertextSize = file.length(),
                        ciphertextSha256 =
                            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()),
                    )
                },
            ),
        )
    }

    @Test
    fun persistedMaterialCarriesSeparateIdentitiesAndTrustComesOnlyFromTheDirectoryBoundary() = runBlocking {
        // The authenticated directory knows ONLY the true sender bundle.
        directory[senderBundleUuid.toString()] = KeyBundleByIdResult.Found(
            HistoricalKeyBundle(
                keyBundleId = KeyBundleId(senderBundleUuid),
                ownerUserId = UserId(senderUuid),
                suite = SUITE,
                protocolVersion = 1,
                encryptionPublicKeysetB64Url = "",
                signingPublicKeysetB64Url =
                    Base64.urlSafeEncode(senderIdentity.signingPublicKeyset),
                status = "ACTIVE",
            ),
        )
        val store = trustedStore()

        // ---- PUBLISH: sender S -> recipient R, two real identities ----
        publishAndStageCrossIdentity()

        // ---- PERSISTED routing identities are separate and complete ----
        val row = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleUuid.toString(), senderUuid.toString())!!
        assertEquals(senderUuid.toString(), row.senderUserId)
        assertEquals(recipientUuid.toString(), row.recipientUserId)
        assertNotEquals(row.senderUserId, row.recipientUserId)
        assertEquals(senderBundleUuid.toString(), row.senderKeyBundleId)
        assertEquals(recipientBundleUuid.toString(), row.recipientKeyBundleId)
        assertNotNull(row.senderSigningPublicKeysetB64)

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

        // ---- ACCEPTANCE GATE verifies ONLY through the trusted boundary ----
        val trusted = store.senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid))
        assertTrue("directory-proven material must resolve", trusted is dev.hryshyn.remanence.identity.SenderKeyResolution.Trusted)
        val accepted = runAcceptanceGate(
            verifier = (trusted as dev.hryshyn.remanence.identity.SenderKeyResolution.Trusted).verifyingKeyset,
            expectedSenderBundle = KeyBundleId(senderBundleUuid),
            envelopePlaintext = openedForRecipient,
        )
        assertTrue(
            "the real gate must accept with directory-proven sender material",
            accepted is CapsuleAcceptanceResult.Accepted,
        )

        // A FORGED replacement of the row-carried export is inert: trust is
        // decided by the boundary, so the authentic capsule still verifies.
        // (Storage-level row replacement: strict-insert refuses an UPDATE-
        // by-collision, so the tamper first removes its own account's row.)
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM outbox_capsule WHERE capsule_id = ? AND owner_user_id = ?",
            arrayOf(capsuleUuid.toString(), senderUuid.toString()),
        )
        database.outboxCapsuleDao().insertOrAbort(
            senderUuid.toString(),
            row.copy(senderSigningPublicKeysetB64 = Base64.urlSafeEncode(attackerPublicExport())),
        )
        val afterTamper = runAcceptanceGate(
            verifier = (
                store.senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid))
                    as dev.hryshyn.remanence.identity.SenderKeyResolution.Trusted
                ).verifyingKeyset,
            expectedSenderBundle = KeyBundleId(senderBundleUuid),
            envelopePlaintext = openedForRecipient,
        )
        assertTrue(
            "storage-adjacent key substitution must not decide verification",
            afterTamper is CapsuleAcceptanceResult.Accepted,
        )

        // And refusing the claimed bundle as the sender's stays rejected.
        val conflated = runCatching {
            runAcceptanceGate(
                verifier = TinkProtoKeysetFormat.parseKeysetWithoutSecret(senderIdentity.signingPublicKeyset),
                expectedSenderBundle = KeyBundleId(recipientBundleUuid),
                envelopePlaintext = openedForRecipient,
            )
        }
        assertTrue(conflated.isFailure || conflated.getOrNull() is CapsuleAcceptanceResult.Rejected)
    }

    @Test
    fun directorySenderResolutionClassifiesTerminalAndUnavailableOutcomes() = runBlocking {
        val attacker = AccountIdentityGenerator().generate()

        // Wrong OWNER: the bundle exists but belongs to someone else.
        directory[senderBundleUuid.toString()] = activeEntry(UserId(recipientUuid), Base64.urlSafeEncode(attacker.signingPublicKeyset))
        assertEquals(
            dev.hryshyn.remanence.identity.SenderKeyResolution.Untrusted(
                dev.hryshyn.remanence.identity.SenderKeyUntrustedReason.OWNER_MISMATCH,
            ),
            trustedStore().senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid)),
        )

        // REVOKED: present and well-formed but no longer trustworthy.
        directory[senderBundleUuid.toString()] = KeyBundleByIdResult.Found(
            HistoricalKeyBundle(
                keyBundleId = KeyBundleId(senderBundleUuid),
                ownerUserId = UserId(senderUuid),
                suite = SUITE,
                protocolVersion = 1,
                encryptionPublicKeysetB64Url = "",
                signingPublicKeysetB64Url = Base64.urlSafeEncode(senderIdentity.signingPublicKeyset),
                status = "REVOKED",
            ),
        )
        assertEquals(
            dev.hryshyn.remanence.identity.SenderKeyResolution.Untrusted(
                dev.hryshyn.remanence.identity.SenderKeyUntrustedReason.REVOKED,
            ),
            trustedStore().senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid)),
        )

        // UNKNOWN: the directory explicitly proves that this bundle is absent.
        directory[senderBundleUuid.toString()] = KeyBundleByIdResult.NotFound
        assertEquals(
            dev.hryshyn.remanence.identity.SenderKeyResolution.Untrusted(
                dev.hryshyn.remanence.identity.SenderKeyUntrustedReason.UNKNOWN_BUNDLE,
            ),
            trustedStore().senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid)),
        )

        // No session/source is operationally unavailable, not an identity rejection.
        directory.remove(senderBundleUuid.toString())
        assertEquals(
            dev.hryshyn.remanence.identity.SenderKeyResolution.Unavailable(
                dev.hryshyn.remanence.identity.SenderKeyUnavailableReason.NO_SESSION_OR_SOURCE,
            ),
            trustedStore().senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid)),
        )

        // Transport/server failures remain operationally unavailable; the
        // authenticator owns refresh and this boundary never guesses trust.
        for (failure in listOf(
            KeyBundleByIdResult.Failure(KeyBundleFailure.NETWORK),
            KeyBundleByIdResult.Failure(KeyBundleFailure.HTTP, 401),
            KeyBundleByIdResult.Failure(KeyBundleFailure.INVALID_RESPONSE),
        )) {
            directory[senderBundleUuid.toString()] = failure
            assertEquals(
                dev.hryshyn.remanence.identity.SenderKeyResolution.Unavailable(
                    dev.hryshyn.remanence.identity.SenderKeyUnavailableReason.DIRECTORY_UNAVAILABLE,
                ),
                trustedStore().senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid)),
            )
        }

        // MALFORMED directory keyset material also refuses.
        directory[senderBundleUuid.toString()] = activeEntry(
            UserId(senderUuid),
            Base64.urlSafeEncode("not-a-keyset".toByteArray()),
        )
        assertEquals(
            dev.hryshyn.remanence.identity.SenderKeyResolution.Untrusted(
                dev.hryshyn.remanence.identity.SenderKeyUntrustedReason.MALFORMED_KEY,
            ),
            trustedStore().senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid)),
        )

        val malformedOwn = trustedStore(
            ownAccount = dev.hryshyn.remanence.identity.DirectorySenderKeyStore.OwnAccount(
                userId = UserId(senderUuid),
                activeKeyBundleId = KeyBundleId(senderBundleUuid),
                publicSigningExportB64Url = Base64.urlSafeEncode("not-a-keyset".toByteArray()),
            ),
        )
        assertEquals(
            dev.hryshyn.remanence.identity.SenderKeyResolution.Untrusted(
                dev.hryshyn.remanence.identity.SenderKeyUntrustedReason.MALFORMED_KEY,
            ),
            malformedOwn.senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid)),
        )
    }

    @Test
    fun selfSendRemainsNaturalThroughTheProvablyOwnAccountShortcut() = runBlocking {
        // M1 self-send: explicit same VALUES, resolved through the OWN-account
        // shortcut without ever touching the network.
        publishSelfSend()

        val fetchesBefore = directoryFetches
        val store = trustedStore(
            ownAccount = dev.hryshyn.remanence.identity.DirectorySenderKeyStore.OwnAccount(
                userId = UserId(senderUuid),
                activeKeyBundleId = KeyBundleId(senderBundleUuid),
                publicSigningExportB64Url = Base64.urlSafeEncode(senderIdentity.signingPublicKeyset),
            ),
        )

        val resolution = store.senderVerifyingKeyset(UserId(senderUuid), KeyBundleId(senderBundleUuid))
        assertTrue(resolution is dev.hryshyn.remanence.identity.SenderKeyResolution.Trusted)
        assertEquals("self-send must not require a network lookup", fetchesBefore, directoryFetches)

        // The persisted row still separates the columns with equal VALUES.
        val stagedRow = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleUuid.toString(), senderUuid.toString())!!
        assertEquals(senderUuid.toString(), stagedRow.senderUserId)
        assertEquals(senderUuid.toString(), stagedRow.recipientUserId)
        assertEquals(senderBundleUuid.toString(), stagedRow.senderKeyBundleId)
        assertEquals(senderBundleUuid.toString(), stagedRow.recipientKeyBundleId)

        // And the envelope opens naturally with our own private half.
        val envelopeBytes = File(requireNotNull(stagedRow.envelopePath)).readBytes()
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

    private suspend fun publishSelfSend() {
        val prepared = CapsulePublisher(testWrapper, testAlias).publish(
            CapsulePublishRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(senderUuid),
                recipientUserId = UserId(senderUuid),
                senderKeyBundleId = KeyBundleId(senderBundleUuid),
                recipientKeyBundleId = KeyBundleId(senderBundleUuid),
                ownerUserId = senderUuid.toString(),
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
        CapsuleOutboxStager(database, dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir), SenderRetryMaterialStore(dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots(outboxDir))).stage(prepared)
    }

    private fun attackerPublicExport(): ByteArray =
        AccountIdentityGenerator().generate().signingPublicKeyset

    private fun activeEntry(owner: UserId, signingExportB64Url: String): KeyBundleByIdResult.Found =
        KeyBundleByIdResult.Found(
            HistoricalKeyBundle(
                keyBundleId = KeyBundleId(senderBundleUuid),
                ownerUserId = owner,
                suite = SUITE,
                protocolVersion = 1,
                encryptionPublicKeysetB64Url = "",
                signingPublicKeysetB64Url = signingExportB64Url,
                status = "ACTIVE",
            ),
        )
}
