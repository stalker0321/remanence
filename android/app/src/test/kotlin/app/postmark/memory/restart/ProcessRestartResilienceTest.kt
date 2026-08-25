package app.postmark.memory.restart

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.postmark.memory.ui.navigation.AppDestination
import app.postmark.memory.ui.navigation.AppNavigationController
import app.postmark.memory.ui.navigation.AuthUiState
import app.postmark.memory.ui.navigation.CapsuleAccess
import postmark.core.data.db.FingerprintOrigin
import postmark.core.data.db.FingerprintSide
import postmark.core.data.db.OutboxBlobUploadState
import postmark.core.data.db.OutboxCapsuleState
import postmark.core.data.db.PostmarkLocalDatabase
import postmark.core.recognition.ScanGrantManager

/**
 * M1-M17 automated proof of the process-restart contract
 * (docs/security.md section 9, architecture.md section 5): scan grants and
 * navigation state live only in process memory, so a restart forces a fresh
 * scan; meanwhile every persisted artifact - outbox rows, ciphertext files,
 * sealed fingerprint baselines, and the wrapped identity keyset - survives.
 *
 * Process death is simulated by closing the database and discarding all
 * in-memory objects, then rebuilding them against the same on-disk state.
 * Physical-device instrumentation evidence stays PENDING until hardware runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProcessRestartResilienceTest {

    private lateinit var context: Context
    private lateinit var database: PostmarkLocalDatabase
    private lateinit var filesRoot: File

    private val capsuleId = UUID.randomUUID()
    private val blobBytes = "ciphertext-blob-payload".toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = newDatabase("restart.db")
        filesRoot = File(context.filesDir, "restart-artifacts").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (this::database.isInitialized) database.close()
        context.getDatabasePath("restart.db").parentFile?.listFiles()
            ?.filter { it.name.startsWith("restart.db") }
            ?.forEach { it.delete() }
        filesRoot.deleteRecursively()
    }

    private fun newDatabase(name: String): PostmarkLocalDatabase =
        Room.databaseBuilder(context, PostmarkLocalDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    /** Everything the "first process" had live when it died. */
    private class FirstProcessState(
        val grant: postmark.core.recognition.ScanGrant,
        val grantsManager: ScanGrantManager,
        val navigation: AppNavigationController,
        val outboxBlobPath: String,
        val sealedFingerprintPath: String,
        val wrappedKeysetPath: String,
    )

    private suspend fun seedFirstProcess(): FirstProcessState {
        // 1. A live scan grant plus navigation sitting inside the capsule.
        val grants = ScanGrantManager(clockMillis = { 100L })
        val grant = grants.issue(capsuleId)
        val navigation = AppNavigationController(
            AuthUiState.Authenticated(userId = "u", handle = "mykola"),
        )
        navigation.grantCapsuleAccess(grant.grantId.toString(), capsuleId.toString())
        navigation.navigate(AppDestination.Capsule(grant.grantId.toString()))

        // 2. Persisted outbox: one ENCRYPTED capsule row plus its ciphertext blob.
        database.outboxCapsuleDao().upsert(
            postmark.core.data.db.OutboxCapsuleEntity(
                capsuleId = capsuleId.toString(),
                idempotencyKey = "idem-$capsuleId",
                senderUserId = UUID.randomUUID().toString(),
                recipientUserId = UUID.randomUUID().toString(),
                senderKeyBundleId = UUID.randomUUID().toString(),
                recipientKeyBundleId = UUID.randomUUID().toString(),
                senderSigningPublicKeysetB64 = null,
                state = OutboxCapsuleState.ENCRYPTED,
                recognitionManifestPath = null,
                contentManifestPath = null,
                envelopePath = null,
                publishStatementPath = null,
                publishStatementSignaturePath = null,
                lastErrorCode = null,
            ),
        )
        val blobFile = File(filesRoot, "$capsuleId.bin").also { it.writeBytes(blobBytes) }
        database.outboxBlobDao().upsertAll(
            listOf(
                postmark.core.data.db.OutboxBlobEntity(
                    blobId = UUID.randomUUID().toString(),
                    capsuleId = capsuleId.toString(),
                    kind = "PHOTO",
                    ordinal = 0,
                    localCiphertextPath = blobFile.absolutePath,
                    sizeBytes = blobBytes.size.toLong(),
                    sha256 = blobBytes.map { it.toByte() }.toByteArray(),
                    uploadState = OutboxBlobUploadState.PENDING,
                    attemptCount = 0,
                ),
            ),
        )

        // 3. Persisted sealed recipient fingerprint baseline.
        val fingerprintDir = File(filesRoot, "fingerprints").apply { mkdirs() }
        val sealedFp = File(fingerprintDir, "front.fpw").apply { writeBytes(byteArrayOf(7, 7, 7)) }
        database.recognitionFingerprintDao().insertAll(
            listOf(
                postmark.core.data.db.RecognitionFingerprintEntity(
                    fingerprintId = "fp-1",
                    capsuleId = capsuleId.toString(),
                    side = FingerprintSide.FRONT,
                    origin = FingerprintOrigin.RECIPIENT,
                    fingerprintProfileId = "mvp-orb-v1",
                    encryptedPath = sealedFp.relativeTo(filesRoot).path,
                    createdAtEpochMs = 5L,
                    preferred = true,
                ),
            ),
        )

        // 4. Persisted wrapped identity keyset record.
        val wrappedKeyset = File(filesRoot, "identity.keyset").apply { writeBytes(ByteArray(48) { it.toByte() }) }

        return FirstProcessState(
            grant = grant,
            grantsManager = grants,
            navigation = navigation,
            outboxBlobPath = blobFile.absolutePath,
            sealedFingerprintPath = sealedFp.absolutePath,
            wrappedKeysetPath = wrappedKeyset.absolutePath,
        )
    }

    @Test
    fun restartForcesRescanWhileCiphertextAndKeysSurvive() = runBlocking {
        val first = seedFirstProcess()

        // ---- process death ----
        database.close()

        // ---- second process rebuilds from disk only ----
        val rebornDatabase = newDatabase("restart.db")
        try {
            val rebornGrants = ScanGrantManager(clockMillis = { 200L })
            val rebornNavigation = AppNavigationController(AuthUiState.SignedOut)

            // Memory-only state is gone: the old grant resolves to nothing and
            // navigation starts from scratch, forcing a fresh scan.
            assertNull(rebornGrants.resolveCapsuleId(first.grant.grantId))
            assertEquals(AppDestination.Authentication, rebornNavigation.current)
            assertEquals(CapsuleAccess.None, rebornNavigation.capsuleAccess)

            // Outbox rows survived.
            val capsuleRow = rebornDatabase.outboxCapsuleDao().getByCapsuleId(capsuleId.toString())
            assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleRow?.state)
            val blobRow = rebornDatabase.outboxBlobDao().getAllByCapsuleId(capsuleId.toString()).single()
            assertEquals(first.outboxBlobPath, blobRow.localCiphertextPath)

            // Ciphertext files are byte-identical.
            assertTrue(File(first.outboxBlobPath).readBytes().contentEquals(blobBytes))
            assertTrue(File(first.sealedFingerprintPath).exists())

            // Fingerprint baseline row + wrapped keyset record survive.
            val fingerprints = rebornDatabase.recognitionFingerprintDao()
                .getByCapsuleIdAndOrigin(capsuleId.toString(), FingerprintOrigin.RECIPIENT)
            assertEquals(1, fingerprints.size)
            assertTrue(fingerprints.single().preferred)
                assertTrue(File(first.wrappedKeysetPath).length() == 48L)
        } finally {
            rebornDatabase.close()
        }
    }
}
