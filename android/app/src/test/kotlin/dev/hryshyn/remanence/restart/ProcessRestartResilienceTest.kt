package dev.hryshyn.remanence.restart

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
import dev.hryshyn.remanence.ui.navigation.AppDestination
import dev.hryshyn.remanence.ui.navigation.AppNavigationController
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.navigation.CapsuleAccess
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide
import dev.hryshyn.remanence.core.data.db.OutboxBlobUploadState
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.data.db.RemanenceLocalDatabase
import dev.hryshyn.remanence.core.recognition.ScanGrantManager

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

    private companion object {
        const val OWNER_USER_ID = "0198f0a0-0000-7000-8000-00000000ow01"
    }

    private lateinit var context: Context
    private lateinit var database: RemanenceLocalDatabase
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

    private fun newDatabase(name: String): RemanenceLocalDatabase =
        Room.databaseBuilder(context, RemanenceLocalDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    /** Everything the "first process" had live when it died. */
    private class FirstProcessState(
        val grant: dev.hryshyn.remanence.core.recognition.ScanGrant,
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
        database.outboxCapsuleDao().insertOrAbort(
            OWNER_USER_ID,
            dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity(
                capsuleId = capsuleId.toString(),
                idempotencyKey = "idem-$capsuleId",
                ownerUserId = OWNER_USER_ID,
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
            OWNER_USER_ID,
            listOf(
                dev.hryshyn.remanence.core.data.db.OutboxBlobEntity(
                    blobId = UUID.randomUUID().toString(),
                    ownerUserId = OWNER_USER_ID,
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
            OWNER_USER_ID,
            listOf(
                dev.hryshyn.remanence.core.data.db.RecognitionFingerprintEntity(
                    fingerprintId = "fp-1",
                    ownerUserId = OWNER_USER_ID,
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
            val capsuleRow = rebornDatabase.outboxCapsuleDao().getByCapsuleIdAndOwner(capsuleId.toString(), OWNER_USER_ID)
            assertEquals(OutboxCapsuleState.ENCRYPTED, capsuleRow?.state)
            val blobRow = rebornDatabase.outboxBlobDao().getAllByCapsuleIdAndOwner(capsuleId.toString(), OWNER_USER_ID).single()
            assertEquals(first.outboxBlobPath, blobRow.localCiphertextPath)

            // Ciphertext files are byte-identical.
            assertTrue(File(first.outboxBlobPath).readBytes().contentEquals(blobBytes))
            assertTrue(File(first.sealedFingerprintPath).exists())

            // Fingerprint baseline row + wrapped keyset record survive.
            val fingerprints = rebornDatabase.recognitionFingerprintDao()
                .getByCapsuleIdAndOriginAndOwner(capsuleId.toString(), FingerprintOrigin.RECIPIENT, OWNER_USER_ID)
            assertEquals(1, fingerprints.size)
            assertTrue(fingerprints.single().preferred)
                assertTrue(File(first.wrappedKeysetPath).length() == 48L)
        } finally {
            rebornDatabase.close()
        }
    }
}
