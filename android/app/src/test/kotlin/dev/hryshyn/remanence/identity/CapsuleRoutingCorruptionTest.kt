package dev.hryshyn.remanence.identity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.create.SameAccountCapsulePublisher
import dev.hryshyn.remanence.create.SameAccountCapsuleRequest
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.ui.scan.ScanMatchUiState
import dev.hryshyn.remanence.ui.scan.ScanTerminalState
import dev.hryshyn.remanence.ui.scan.ScanViewModel
import dev.hryshyn.remanence.wiring.KekBoundSecretSealer
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.Base64
import java.io.File
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide as DbFingerprintSide
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.data.fingerprints.EncryptedFingerprintStore
import dev.hryshyn.remanence.core.data.outbox.CapsuleOutboxStager
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.RecognitionProfile

/**
 * FIX-REVIEW2-01 regression: persisted routing identity material is parsed
 * STRICTLY. Malformed non-null sender/recipient IDs, bundles, or signing
 * exports fail closed - never repaired into a fake self-send, never replaced
 * by the authenticated account, never falling back to the own signing key
 * after an error. Only genuinely NULL v3 columns resolve through the
 * documented legacy self-send fallback, and a REAL scan over a corrupted row
 * issues no grant and persists no plaintext baseline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapsuleRoutingCorruptionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
    private lateinit var filesRoot: File
    private lateinit var roots: AccountScopedFileRoots

    private val identity = AccountIdentityGenerator().generate()
    private val capsuleUuid = UUID.fromString("9d111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("9d222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("9d333333-4444-4555-8666-777777777777")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TinkPrimitives.ensureRegistered()
        context = ApplicationProvider.getApplicationContext()
        database = Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, "routing-corruption.db")
            .allowMainThreadQueries()
            .build()
        filesRoot = File(context.filesDir, "routing-corruption").apply { mkdirs() }
        roots = AccountScopedFileRoots(filesRoot)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::database.isInitialized) database.close()
        context.getDatabasePath("routing-corruption.db").parentFile?.listFiles()
            ?.filter { it.name.startsWith("routing-corruption.db") }
            ?.forEach { it.delete() }
        filesRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Pure strict-parser matrix.
    // ------------------------------------------------------------------

    private fun validCrossRow(
        senderUserId: String? = "11111111-2222-4333-8444-555555555555",
        recipientUserId: String = "22222222-3333-4444-8555-666666666666",
        senderBundleId: String? = "33333333-4444-4555-8666-777777777777",
        recipientBundleId: String = "44444444-5555-4666-8777-888888888888",
        signingExport: String? = senderPublicExport(),
    ) = OutboxCapsuleEntity(
        capsuleId = capsuleUuid.toString(),
        idempotencyKey = "idem",
        ownerUserId = "0198f0a0-0000-7000-8000-00000000ow01",
        senderUserId = senderUserId,
        recipientUserId = recipientUserId,
        senderKeyBundleId = senderBundleId,
        recipientKeyBundleId = recipientBundleId,
        senderSigningPublicKeysetB64 = signingExport,
        state = OutboxCapsuleState.ENCRYPTED,
        recognitionManifestPath = null,
        contentManifestPath = null,
        envelopePath = "/tmp/env",
        publishStatementPath = null,
        publishStatementSignaturePath = null,
        lastErrorCode = null,
    )

    @Test
    fun malformedSenderUserIdFailsClosedWithoutOwnFallback() {
        val result = CapsuleRoutingPolicy.resolve(validCrossRow(senderUserId = "not-a-uuid"))
        assertEquals(CapsuleRoutingResolution.Corrupt("sender_user_id"), result)
    }

    @Test
    fun malformedRecipientUserIdFailsClosedWithoutOwnFallback() {
        val result = CapsuleRoutingPolicy.resolve(validCrossRow(recipientUserId = "garbage"))
        assertEquals(CapsuleRoutingResolution.Corrupt("recipient_user_id"), result)
    }

    @Test
    fun malformedSenderBundleIdFailsClosed() {
        val result = CapsuleRoutingPolicy.resolve(validCrossRow(senderBundleId = "zzz"))
        assertEquals(CapsuleRoutingResolution.Corrupt("sender_key_bundle_id"), result)
    }

    @Test
    fun malformedRecipientBundleIdFailsClosed() {
        val result = CapsuleRoutingPolicy.resolve(validCrossRow(recipientBundleId = "zzz"))
        assertEquals(CapsuleRoutingResolution.Corrupt("recipient_key_bundle_id"), result)
    }

    @Test
    fun invalidBase64SigningExportFailsClosed() {
        val result = CapsuleRoutingPolicy.resolve(validCrossRow(signingExport = "!!!not-base64url!!!"))
        assertEquals(CapsuleRoutingResolution.Corrupt("sender_signing_public_keyset_b64"), result)
    }

    @Test
    fun base64OfMalformedBytesFailsClosed() {
        val garbage = Base64.urlSafeEncode("definitely not a tink keyset".toByteArray())
        val result = CapsuleRoutingPolicy.resolve(validCrossRow(signingExport = garbage))
        assertEquals(CapsuleRoutingResolution.Corrupt("sender_signing_public_keyset_b64"), result)
    }

    @Test
    fun wellFormedButWrongAlgorithmOrSecretKeysetFailsClosed() {
        // Valid base64 of a SECRET AES-GCM keyset: parseKeysetWithoutSecret
        // refuses secret material, so a carried secret export fails closed.
        val secretAes = TinkProtoKeysetFormat.serializeKeyset(
            dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator().generate(),
            InsecureSecretKeyAccess.get(),
        )
        val result = CapsuleRoutingPolicy.resolve(
            validCrossRow(signingExport = Base64.urlSafeEncode(secretAes)),
        )
        assertEquals(CapsuleRoutingResolution.Corrupt("sender_signing_public_keyset_b64"), result)

        // And a truncated-but-valid-base64 Ed25519 export also fails closed.
        val truncated = identity.signingPublicKeyset.copyOfRange(0, 20)
        val resultTruncated = CapsuleRoutingPolicy.resolve(
            validCrossRow(signingExport = Base64.urlSafeEncode(truncated)),
        )
        assertEquals(CapsuleRoutingResolution.Corrupt("sender_signing_public_keyset_b64"), resultTruncated)
    }

    @Test
    fun genuineLegacyNullRowResolvesThroughDocumentedSelfSendFallback() {
        val resolved = CapsuleRoutingPolicy.resolve(
            validCrossRow(senderUserId = null, senderBundleId = null, signingExport = null),
        ) as CapsuleRoutingResolution.Resolved

        // Sender VALUES equal the recipient VALUES because v2 rows were
        // same-account by construction; the columns themselves stay NULL.
        assertEquals(resolved.recipientUserId, resolved.senderUserId)
        assertEquals(resolved.recipientKeyBundleId, resolved.senderKeyBundleId)
        assertNull(resolved.senderSigningPublicKeysetB64Url)
    }

    @Test
    fun normalCrossIdentityRowResolvesWithDistinctEndsAndCarriedKeyset() {
        val resolved = CapsuleRoutingPolicy.resolve(validCrossRow()) as CapsuleRoutingResolution.Resolved

        assertEquals(UserId(UUID.fromString("11111111-2222-4333-8444-555555555555")), resolved.senderUserId)
        assertEquals(UserId(UUID.fromString("22222222-3333-4444-8555-666666666666")), resolved.recipientUserId)
        assertEquals(KeyBundleId(UUID.fromString("33333333-4444-4555-8666-777777777777")), resolved.senderKeyBundleId)
        assertEquals(KeyBundleId(UUID.fromString("44444444-5555-4666-8777-888888888888")), resolved.recipientKeyBundleId)
        assertNotNull(resolved.senderSigningPublicKeysetB64Url)
    }

    // ------------------------------------------------------------------
    // Real scan-flow corruption integration.
    // ------------------------------------------------------------------

    private fun senderPublicExport(): String =
        Base64.urlSafeEncode(identity.signingPublicKeyset)

    private fun store() = EncryptedFingerprintStore(
        roots,
        KekBoundSecretSealer(SoftwareKekBoundary(), KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS),
        database.recognitionFingerprintDao(),
        ownerUserIdProvider = { userUuid.toString() },
    )

    private class MatchingProcessor(private val bytes: ByteArray) : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill =
            ProcessedStill.Accepted(profileId = RecognitionProfile.mvpOrbV1().profileId, serializedBytes = bytes)
    }

    private suspend fun stagePublishedSelfSendCapsule() {
        store().persist(
            capsuleUuid.toString(), DbFingerprintSide.FRONT, FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId,
            syntheticFingerprint(11, FingerprintSide.FRONT),
        )
        store().persist(
            capsuleUuid.toString(), DbFingerprintSide.BACK, FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId,
            syntheticFingerprint(22, FingerprintSide.BACK),
        )
        val prepared = SameAccountCapsulePublisher().publish(
            SameAccountCapsuleRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(userUuid),
                senderKeyBundleId = KeyBundleId(bundleUuid),
                senderHandleSnapshot = "mykola",
                createdAtEpochSeconds = 1_700_000_000L,
                photoJpegs = (0 until 3).map { "photo-$it".toByteArray() },
                photoWidthsPx = listOf(800, 800, 800),
                photoHeightsPx = listOf(600, 600, 600),
                noteUtf8 = null,
                frontFingerprintBytes = syntheticFingerprint(11, FingerprintSide.FRONT),
                backFingerprintBytes = syntheticFingerprint(22, FingerprintSide.BACK),
                signingKeyset = identity.signingPrivateHandle,
                recipientEncryptionPublicKeyset =
                    TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionPublicKeyset),
            ),
        )
        CapsuleOutboxStager(database, roots).stage(prepared)
    }

    private suspend fun tamperRow(transform: (OutboxCapsuleEntity) -> OutboxCapsuleEntity) {
        val row = database.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleUuid.toString(), userUuid.toString())!!
        // Storage-level tamper of the test's OWN account row: strict insert
        // refuses update-by-collision, so replace via scoped delete + insert.
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM outbox_capsule WHERE capsule_id = ? AND owner_user_id = ?",
            arrayOf(row.capsuleId, userUuid.toString()),
        )
        database.outboxCapsuleDao().insertOrAbort(transform(row))
    }

    private fun scanViewModel(): ScanViewModel = ScanViewModel(
        persistence = store(),
        database = database,
        profile = RecognitionProfile.mvpOrbV1(),
        identityProvider = {
            SenderIdentitySnapshot(
                userId = userUuid.toString(),
                handle = "mykola",
                activeKeyBundleId = bundleUuid.toString(),
                encryptionPrivateHandle = identity.encryptionPrivateHandle,
                signingPrivateHandle = identity.signingPrivateHandle,
            )
        },
            // FIX-REVIEW2-04: trusted boundary wired to the provable self
            // account; corrupt rows below fail before this is ever consulted.
            trustedSenderKeys = dev.hryshyn.remanence.identity.DirectorySenderKeyStore(
                directoryFetch = { error("self-send verification must not touch the network") },
                ownAccount = {
                    dev.hryshyn.remanence.identity.DirectorySenderKeyStore.OwnAccount(
                        userId = UserId(userUuid),
                        activeKeyBundleId = KeyBundleId(bundleUuid),
                        publicSigningExportB64Url =
                            com.google.crypto.tink.subtle.Base64.urlSafeEncode(identity.signingPublicKeyset),
                    )
                },
            ),
        grantsClockMillis = { 0L },
        frontProcessor = MatchingProcessor(syntheticFingerprint(11, FingerprintSide.FRONT)),
        backProcessor = MatchingProcessor(syntheticFingerprint(22, FingerprintSide.BACK)),
        cpuDispatcher = testDispatcher,
        ioDispatcher = testDispatcher,
    )

    /** FIX-STATE-01: drives the authoritative controllers exactly as the UI does. */
    private fun capturePair(vm: ScanViewModel) {
        listOf(vm.frontAttempt, vm.backAttempt).forEach {
            it.onPermissionResult(granted = true, canAskAgain = false)
            it.onPreviewBound()
        }
        assertTrue(vm.beginFrontCapture())
        vm.deliverFrontJpeg("front".toByteArray())
        awaitCondition { vm.captureSession.state == dev.hryshyn.remanence.scan.ScanSessionState.AWAITING_BACK }
        assertTrue(vm.beginBackCapture())
        vm.deliverBackJpeg("back".toByteArray())
    }

    private fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition not reached in time")
            Thread.sleep(20)
        }
    }

    /** Runs one full scan attempt and asserts the corrupt row yields nothing. */
    private suspend fun assertScanYieldsNoGrantAndNoBaseline(vm: ScanViewModel) {
        capturePair(vm)
        awaitCondition { vm.matchState.value !is ScanMatchUiState.Matching }
        assertTrue("corrupt row must never grant", vm.terminal.value !is ScanTerminalState.Granted)
        assertTrue(vm.matchState.value is ScanMatchUiState.RecaptureGuidance)
        assertTrue(
            "no plaintext recipient baseline may persist for a corrupt row",
            database.recognitionFingerprintDao()
                .getByCapsuleIdAndOriginAndOwner(capsuleUuid.toString(), FingerprintOrigin.RECIPIENT, "0198f0a0-0000-7000-8000-00000000ow01")
                .isEmpty(),
        )
        database.close()
    }

    @Test
    fun corruptedPersistedSenderUserIdNeverGrants() = runBlocking {
        stagePublishedSelfSendCapsule()
        tamperRow { it.copy(senderUserId = "not-a-uuid") }
        assertScanYieldsNoGrantAndNoBaseline(scanViewModel())
    }

    @Test
    fun corruptedPersistedRecipientUserIdNeverGrants() = runBlocking {
        stagePublishedSelfSendCapsule()
        tamperRow { it.copy(recipientUserId = "also-not-a-uuid") }
        assertScanYieldsNoGrantAndNoBaseline(scanViewModel())
    }

    @Test
    fun corruptedPersistedSenderBundleNeverGrants() = runBlocking {
        stagePublishedSelfSendCapsule()
        tamperRow { it.copy(senderKeyBundleId = "broken-bundle") }
        assertScanYieldsNoGrantAndNoBaseline(scanViewModel())
    }

    @Test
    fun corruptedPersistedRecipientBundleNeverGrants() = runBlocking {
        stagePublishedSelfSendCapsule()
        tamperRow { it.copy(recipientKeyBundleId = "broken-bundle") }
        assertScanYieldsNoGrantAndNoBaseline(scanViewModel())
    }

    @Test
    fun malformedCarriedSigningExportNeverGrantsAndNeverFallsBackToOwnKey() = runBlocking {
        stagePublishedSelfSendCapsule()
        // Invalid encoding: fail closed even though our OWN valid export exists.
        tamperRow { it.copy(senderSigningPublicKeysetB64 = "!!!not-base64!!!") }
        assertScanYieldsNoGrantAndNoBaseline(scanViewModel())
    }

    @Test
    fun tamperedCarriedSigningExportIsInertBecauseTrustComesOnlyFromTheBoundary() = runBlocking {
        stagePublishedSelfSendCapsule()
        // A DIFFERENT well-formed Ed25519 public keyset parses cleanly into
        // the row, but the row-carried export is a transport/cache candidate
        // only: verification still runs against the trusted boundary's
        // material, so this storage tamper neither helps nor breaks the
        // authentic capsule.
        val attacker = AccountIdentityGenerator().generate()
        tamperRow {
            it.copy(senderSigningPublicKeysetB64 = Base64.urlSafeEncode(attacker.signingPublicKeyset))
        }
        val vm = scanViewModel()
        capturePair(vm)
        awaitCondition { vm.terminal.value is ScanTerminalState.Granted }
        assertTrue(
            "row-carried key substitution must not influence the trust decision",
            vm.terminal.value is ScanTerminalState.Granted,
        )
        database.close()
    }

    @Test
    fun genuineLegacyNullColumnsStillVerifyThroughProvenSelfSendFallback() = runBlocking {
        stagePublishedSelfSendCapsule()
        // Simulate an honestly migrated v2 self-send row: all three sender
        // columns genuinely NULL while recipient routing is our own account.
        tamperRow {
            it.copy(senderUserId = null, senderKeyBundleId = null, senderSigningPublicKeysetB64 = null)
        }
        val vm = scanViewModel()
        capturePair(vm)
        awaitCondition { vm.terminal.value !is ScanTerminalState.Idle }
        assertTrue(
            "legacy NULL self-send row keeps working through the documented fallback",
            vm.terminal.value is ScanTerminalState.Granted,
        )
        database.close()
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
}
