package app.postmark.memory.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.postmark.memory.auth.SoftwareKekBoundary
import app.postmark.memory.create.SameAccountCapsulePublisher
import app.postmark.memory.create.SameAccountCapsuleRequest
import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AppNavigationController
import app.postmark.memory.ui.navigation.AuthUiState
import app.postmark.memory.ui.navigation.CapsuleAccess
import app.postmark.memory.wiring.KekBoundSecretSealer
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import postmark.core.crypto.CapsuleKeysetParser
import postmark.core.crypto.RecipientEnvelopeCryptor
import postmark.core.recognition.CapsuleVerifier
import app.postmark.protocol.v1.RecipientEnvelopePlaintext
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.recognition.FingerprintSide as RecognitionSide
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.data.fingerprints.EncryptedFingerprintStore
import postmark.core.data.outbox.CapsuleOutboxStager
import postmark.core.model.BlobId
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.UserId
import postmark.core.recognition.IndexedCandidate
import postmark.core.recognition.LocalMatchEngine
import postmark.core.recognition.RecognitionProfile
import postmark.core.recognition.ScanFlowResult

/**
 * FIX-M1-007-14: the HONEST connected narrative on production paths only -
 * real Tink identity, real AEAD-sealed baselines (no XOR stand-in), the real
 * ciphertext publisher + durable outbox, the real local hierarchy, and a
 * verifier that performs the actual envelope/signature/ID/hash/AEAD gate.
 * A grant exists ONLY after that verification passes; tampering yields none.
 * A final canary scans every produced byte for plaintext markers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateRescanOpenFlowTest {

    private val frontMarker = "plaintext-FRONT-jpeg-marker"
    private val backMarker = "plaintext-BACK-jpeg-marker"
    private val noteMarker = "plaintext-NOTE-dear-mama"
    private val photoPayloadMarker = "e2e-plaintext-photo-payload"

    private lateinit var context: Context
    private lateinit var database: PostmarkLocalDatabase
    private lateinit var filesRoot: File
    private lateinit var outboxDir: File

    private val identity = AccountIdentityGenerator().generate()
    private val capsuleUuid = UUID.fromString("5e111111-2222-4333-8444-555555555555")
    private val userUuid = UUID.fromString("5e222222-3333-4444-8555-666666666666")
    private val bundleUuid = UUID.fromString("5e333333-4444-4555-8666-777777777777")

    private fun newDb(name: String) =
        Room.databaseBuilder(context, PostmarkLocalDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    /** REAL sealer: AES-GCM under a software KEK boundary - no XOR anywhere. */
    private fun store() = EncryptedFingerprintStore(
        File(filesRoot, "fingerprints"),
        KekBoundSecretSealer(SoftwareKekBoundary(), KekBoundSecretSealer.FINGERPRINT_SEALING_ALIAS),
        database.recognitionFingerprintDao(),
    )

    private fun syntheticFingerprint(seed: Int, side: RecognitionSide): ByteArray {
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
        val fp = postmark.core.recognition.PostcardFingerprint(
            profileId = profile.profileId,
            side = side,
            canonicalWidthPx = profile.capture.canonicalLongEdgePx,
            canonicalHeightPx = 1000,
            coarseHash64 = seed.toLong(),
            keypoints = keypoints,
            descriptors = List(64) { i -> ByteArray(32) { ((it * 7 + i * 13 + seed * 29) and 0xFF).toByte() } },
            quality = postmark.core.recognition.ExtractionQuality(200.0, 90.0, 0.01, 0.85),
        )
        return postmark.core.recognition.FingerprintCodec.serialize(fp)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = newDb("e2e-flow.db")
        filesRoot = File(context.filesDir, "e2e-artifacts").apply { mkdirs() }
        outboxDir = File(filesRoot, "outbox").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.getDatabasePath("e2e-flow.db").parentFile?.listFiles()
            ?.filter { it.name.startsWith("e2e-flow.db") }
            ?.forEach { it.delete() }
        filesRoot.deleteRecursively()
    }

    private suspend fun stagePublishedCapsule(): File {
        store().persist(
            capsuleUuid.toString(), FingerprintSide.FRONT, FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId,
            syntheticFingerprint(11, RecognitionSide.FRONT),
        )
        store().persist(
            capsuleUuid.toString(), FingerprintSide.BACK, FingerprintOrigin.SENDER,
            RecognitionProfile.mvpOrbV1().profileId,
            syntheticFingerprint(22, RecognitionSide.BACK),
        )
        val prepared = SameAccountCapsulePublisher().publish(
            SameAccountCapsuleRequest(
                capsuleId = CapsuleId(capsuleUuid),
                senderUserId = UserId(userUuid),
                senderKeyBundleId = KeyBundleId(bundleUuid),
                senderHandleSnapshot = "mykola",
                createdAtEpochSeconds = 1_700_000_000L,
                photoJpegs = (0 until 3).map { "$photoPayloadMarker-$it".toByteArray() },
                photoWidthsPx = listOf(800, 800, 800),
                photoHeightsPx = listOf(600, 600, 600),
                noteUtf8 = noteMarker,
                frontFingerprintBytes = syntheticFingerprint(11, RecognitionSide.FRONT),
                backFingerprintBytes = syntheticFingerprint(22, RecognitionSide.BACK),
                signingKeyset = identity.signingPrivateHandle,
                recipientEncryptionPublicKeyset =
                    TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionPublicKeyset),
            ),
        )
        CapsuleOutboxStager(database, outboxDir).stage(prepared)
        return outboxDir
    }

    /**
     * THE REAL GATE, mirroring ScanViewModel.verifyCapsuleCrypto: open our own
     * envelope with the HPKE private handle, then run CapsuleAcceptanceGate
     * over statement/signature/IDs/hashes from the durable outbox.
     */
    private fun realVerifier(
        database: PostmarkLocalDatabase,
        encryptionPrivate: com.google.crypto.tink.KeysetHandle,
    ): CapsuleVerifier {
        return CapsuleVerifier { candidateId: UUID ->
            runCatching {
                val row = database.outboxCapsuleDao().getByCapsuleId(candidateId.toString()) ?: return@runCatching false
                val blobs = database.outboxBlobDao().getAllByCapsuleId(candidateId.toString())
                val openedEnvelope = RecipientEnvelopeCryptor().open(
                    encryptionPrivate,
                    postmark.core.model.RecipientEnvelopeContextInput(
                        CapsuleId(candidateId),
                        UserId(userUuid),
                        UserId(userUuid),
                        KeyBundleId(UUID.fromString(row.recipientKeyBundleId)),
                    ),
                    File(requireNotNull(row.envelopePath)).readBytes(),
                )
                val result = CapsuleAcceptanceGate().verify(
                    CapsuleAcceptanceInput(
                        expectedCapsuleId = CapsuleId(candidateId),
                        authenticatedUserId = UserId(userUuid),
                        senderVerifyingKeyset = TinkProtoKeysetFormat.parseKeysetWithoutSecret(
                            identity.signingPublicKeyset,
                        ),
                        expectedSenderKeyBundleId = KeyBundleId(bundleUuid),
                        envelopePlaintextBytes = openedEnvelope,
                        statementBytes = File(requireNotNull(row.publishStatementPath)).readBytes(),
                        signature = File(requireNotNull(row.publishStatementSignaturePath)).readBytes(),
                        deliveredBlobs = blobs.map { blobRow ->
                            val file = File(blobRow.localCiphertextPath)
                            postmark.core.crypto.DeliveredBlob(
                                blobId = BlobId(UUID.fromString(blobRow.blobId)),
                                ciphertextSize = file.length(),
                                ciphertextSha256 =
                                    MessageDigest.getInstance("SHA-256").digest(file.readBytes()),
                            )
                        },
                    ),
                )
                result is CapsuleAcceptanceResult.Accepted
            }.getOrDefault(false)
        }
        }

    @Test
    fun createCloseReopenScanOpenWithCleanPlaintextCanary() = runBlocking {
        // ---------- PHASE 1: CREATE (production publisher + stager) ----------
        stagePublishedCapsule()

        // ---------- CLOSE / REOPEN ----------
        database.close()
        database = newDb("e2e-flow.db")

        // ---------- SCAN through the REAL hierarchy and REAL verifier ----------
        val reopenedStore = store()
        val frontRow = reopenedDbFingerprint(FingerprintSide.FRONT)
        val backRow = reopenedDbFingerprint(FingerprintSide.BACK)
        val decryptedFront = reopenedStore.decrypt(frontRow.fingerprintId)
        val decryptedBack = reopenedStore.decrypt(backRow.fingerprintId)

        val engine = LocalMatchEngine(
            profile = RecognitionProfile.mvpOrbV1(),
            verifier = { id ->
                val gate = realVerifier(database, identity.encryptionPrivateHandle)
                gate.verify(id)
            },
            grantIssuer = { id -> "grant-for-$id" },
        )
        val result = engine.run(
            postmark.core.recognition.FingerprintCodec.parse(decryptedFront),
            postmark.core.recognition.FingerprintCodec.parse(decryptedBack),
            listOf(
                IndexedCandidate(
                    capsuleId = capsuleUuid,
                    front = postmark.core.recognition.FingerprintCodec.parse(decryptedFront),
                    back = postmark.core.recognition.FingerprintCodec.parse(decryptedBack),
                    recipientPreferred = backRow.origin == FingerprintOrigin.RECIPIENT &&
                        backRow.preferred,
                ),
            ),
        )
        val granted = result as ScanFlowResult.Granted
        assertTrue("composite must clear the auto gate", granted.compositeScore >= 0.70)

        // ---------- OPEN through the verified gate ----------
        var now = 1_000L
        val grants = postmark.core.recognition.ScanGrantManager({ now })
        val controller = AppNavigationController(AuthUiState.SignedOut)
        controller.updateAuth(AuthUiState.Authenticated("u", "mykola"))
        controller.navigate(AppDestination.Home)

        val grant = grants.issue(granted.capsuleId)
        controller.grantCapsuleAccess(grant.grantId.toString(), granted.capsuleId.toString())
        controller.navigate(AppDestination.Capsule(grant.grantId.toString()))
        assertEquals(AppDestination.Capsule(grant.grantId.toString()), controller.current)

        assertNotNull(database.outboxCapsuleDao().getByCapsuleId(capsuleUuid.toString()))
        assertEquals(5, database.outboxBlobDao().getAllByCapsuleId(capsuleUuid.toString()).size)

        assertTrue(grants.consume(grant.grantId))
        controller.consumeCapsuleAccess()
        assertNull(grants.resolveCapsuleId(grant.grantId))
        assertEquals(CapsuleAccess.None, controller.capsuleAccess)

        // ---------- PLAINTEXT CANARY ----------
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val dbFile = context.getDatabasePath("e2e-flow.db").parentFile!!
        val scannedFiles = filesRoot.walk().filter { it.isFile }.toList() +
            dbDirFiles(dbFile).filter { it.name.startsWith("e2e-flow.db") }
        val markers = listOf(noteMarker, photoPayloadMarker, frontMarker, backMarker)
        scannedFiles.forEach { file ->
            val bytes = file.readBytes()
            markers.forEach { marker ->
                assertFalse(
                    "plaintext marker '$marker' leaked into ${file.name}",
                    indexOf(bytes, marker.toByteArray()),
                )
            }
        }
    }

    @Test
    fun tamperedCiphertextNeverPassesTheRealGateAndNeverIssuesAGrant() = runBlocking {
        stagePublishedCapsule()
        database.close()
        database = newDb("e2e-flow.db")

        // Corrupt one stored ciphertext byte AFTER staging: hash check fails.
        val blobRow = database.outboxBlobDao().getAllByCapsuleId(capsuleUuid.toString()).first()
        val target = File(blobRow.localCiphertextPath)
        val corrupted = target.readBytes().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        target.writeBytes(corrupted)

        val issued = mutableListOf<UUID>()
        val engine = LocalMatchEngine(
            profile = RecognitionProfile.mvpOrbV1(),
            verifier = { id ->
                val gate = realVerifier(database, identity.encryptionPrivateHandle)
                gate.verify(id)
            },
            grantIssuer = { id -> issued += id; "grant-for-$id" },
        )
        val fp = postmark.core.recognition.FingerprintCodec.parse(syntheticFingerprint(11, RecognitionSide.FRONT))
        val bp = postmark.core.recognition.FingerprintCodec.parse(syntheticFingerprint(22, RecognitionSide.BACK))

        val result = engine.run(fp, bp, listOf(IndexedCandidate(capsuleUuid, fp, bp, false)))

        assertEquals(ScanFlowResult.RecaptureRequired, result)
        assertTrue(issued.isEmpty())
    }

    @Test
    fun authenticatedRootReachesCreateAndScanButNothingElse() {
        val controller = AppNavigationController(AuthUiState.SignedOut)
        controller.updateAuth(AuthUiState.Authenticated("user-1", "mykola"))

        controller.navigate(AppDestination.Create)
        assertEquals(AppDestination.Create, controller.current)
        controller.navigate(AppDestination.Home)
        controller.navigate(AppDestination.Scan)
        assertEquals(AppDestination.Scan, controller.current)

        // No gallery/history route exists to navigate to at all.
        RouteInventoryProbe.assertNoGalleryRoutes()
    }

    private object RouteInventoryProbe {
        fun assertNoGalleryRoutes() {
            val names = app.postmark.memory.ui.navigation.RouteGuard.allDestinations()
                .map { it.javaClass.simpleName }
            assertFalse("Gallery" in names || "History" in names || "Inbox" in names)
        }
    }

    private fun dbDirFiles(dbDir: File): List<File> = dbDir.listFiles()?.toList() ?: emptyList()

    private suspend fun reopenedDbFingerprint(side: FingerprintSide) =
        database.recognitionFingerprintDao()
            .getByCapsuleIdAndOrigin(capsuleUuid.toString(), FingerprintOrigin.SENDER)
            .single { it.side == side }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
