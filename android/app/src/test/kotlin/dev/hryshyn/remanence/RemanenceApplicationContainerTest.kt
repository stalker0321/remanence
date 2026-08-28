package dev.hryshyn.remanence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.testing.WorkManagerTestInitHelper
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.sync.CapsuleUploadWorker
import dev.hryshyn.remanence.sync.NoOpWorker
import dev.hryshyn.remanence.ui.create.CreateViewModel
import dev.hryshyn.remanence.wiring.RemanenceViewModelFactory

/**
 * I01: the application container builds one coherent object graph - Tink,
 * Keystore boundary, database, sealed persistence, identity and token stores.
 * Tests inject a software KEK boundary; the real container uses Android
 * Keystore lazily so plain unit contexts never touch hardware keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemanenceApplicationContainerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "accounts").deleteRecursively()
        File(context.filesDir, "identity").deleteRecursively()
        File(context.filesDir, "session").deleteRecursively()
        File(context.filesDir, "create-staging").deleteRecursively()
        File(context.getDatabasePath(AppContainer.DATABASE_NAME).parentFile, "container-test.db")
            .let { file -> listOf(file, File(file.parentFile, "container-test.db-wal"), File(file.parentFile, "container-test.db-shm")) }
            .forEach { it.delete() }
    }

    private fun container(): AppContainer = AppContainer(
        ApplicationProvider.getApplicationContext(),
        kekBoundaryOverride = SoftwareKekBoundary(),
    )

    @Test
    fun containerBuildsACoherentGraphOverDatabasePersistenceAndTokenStore() = runBlocking {
        val container = AppContainer(
            context,
            kekBoundaryOverride = SoftwareKekBoundary(),
            // Database is built against the real filesystem path; force-open it
            // through the fingerprint store below.
        )

        container.database.openHelper.writableDatabase // force open

        // Persistence flows are auth-gated: the fingerprint store resolves a
        // canonical owner through local_account before any file is touched.
        val authenticatedOwner = "9db5c67a-3a4e-45d1-8b0f-2f14a9bb1001"
        container.currentAccountStore.record(
            userId = authenticatedOwner,
            handle = "mykola",
            activeKeyBundleId = "00000000-0000-4000-8000-000000000001",
        )
        assertEquals(
            "00000000-0000-4000-8000-000000000001",
            container.currentAccountStore.load()?.activeKeyBundleId,
        )
        container.fingerprintPersistence.persist(
            capsuleId = "capsule-1",
            side = FingerprintSide.FRONT,
            origin = FingerprintOrigin.SENDER,
            profileId = "mvp-orb-v1",
            plaintextBytes = "fp".toByteArray(),
        )
        assertTrue(container.fingerprintPersistence.hasBaseline("capsule-1", FingerprintSide.FRONT, FingerprintOrigin.SENDER))

        container.sessionTokenStore.save("token-value")
        assertEquals("token-value", container.sessionTokenStore.load())
        assertFalse(container.identityRepository.exists())

        container.database.close()
    }

    @Test
    fun identityRepositoryStartsRecoveryRequiredWithoutSilentRegeneration() {
        val appContainer = AppContainer(context, kekBoundaryOverride = SoftwareKekBoundary())

        val result = appContainer.identityRepository.load()

        assertEquals(IdentityBundleRepository.LoadResult.RecoveryRequired, result)
        assertFalse(appContainer.identityRepository.exists())
    }

    @Test
    fun coldStartIdentityAvailabilityRequiresTheExactDerivedBundleId() {
        val appContainer = AppContainer(context, kekBoundaryOverride = SoftwareKekBoundary())
        if (!appContainer.kekBoundary.hasKey(appContainer.identityKekAlias)) {
            appContainer.kekBoundary.createAes256GcmKey(appContainer.identityKekAlias)
        }
        appContainer.identityRepository.createFresh(appContainer.identityKekAlias)

        val exports = appContainer.identityRepository.loadPublicExports()
            as IdentityBundleRepository.PublicExportsResult.Available
        val derivedBundleId = UUID.nameUUIDFromBytes(exports.encryptionPublicKeyset).toString()

        assertTrue(appContainer.identityAvailability.hasIdentityFor(derivedBundleId))
        assertFalse(
            appContainer.identityAvailability.hasIdentityFor(
                "00000000-0000-4000-8000-000000000002",
            ),
        )
    }

    @Test
    fun defaultContainerUsesAndroidKeystoreBoundary() {
        val appContainer = AppContainer(context)
        assertTrue(appContainer.kekBoundary is dev.hryshyn.remanence.core.crypto.AndroidKeystoreKekBoundary)
    }

    @Test
    fun databaseNameIsTheSingleConfiguredFile() {
        assertEquals("remanence.db", AppContainer.DATABASE_NAME)
    }

    @Test
    fun productionCreateWiringNeverSweepsTheGlobalStagingDirectory() {
        val globalStaging = File(context.filesDir, "create-staging")
        val globalLeftover = File(
            globalStaging,
            "11111111-2222-4333-8444-555555555555",
        ).apply { mkdirs() }

        val container = AppContainer(context, kekBoundaryOverride = SoftwareKekBoundary())
        val createViewModel = RemanenceViewModelFactory(container)
            .create(CreateViewModel::class.java)

        createViewModel.beginSession(1L, "9db5c67a-3a4e-45d1-8b0f-2f14a9bb1001")

        assertTrue("legacy global staging must be ignored", globalLeftover.isDirectory)
        createViewModel.endSession()
    }

    @Test
    fun capsuleUploadResumerWiringUsesCurrentOwnerAndUniqueWorkerBoundary() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ): ListenableWorker? = if (workerClassName == CapsuleUploadWorker::class.java.name) {
                        // The production request is inspected without running
                        // its Android application/Keystore dependencies.
                        NoOpWorker(appContext, workerParameters)
                    } else {
                        null
                    }
                })
                .build(),
        )
        val container = AppContainer(context, kekBoundaryOverride = SoftwareKekBoundary())
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c501")
        val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c502")
        val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000c511")
        val otherCapsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000c512")

        try {
            container.currentAccountStore.record(owner.toRestString(), "mykola", "0198f0a0-0000-7000-8000-00000000c521")
            val row = outboxCapsule(capsule, owner)
            val foreignRow = outboxCapsule(otherCapsule, otherOwner)
            container.database.outboxCapsuleDao().insertOrAbort(owner.toRestString(), row)
            container.database.outboxCapsuleDao().insertOrAbort(otherOwner.toRestString(), foreignRow)

            val result = container.capsuleUploadResumer.resume(owner)

            assertEquals(dev.hryshyn.remanence.sync.CapsuleUploadResumeStatus.COMPLETED, result.status)
            assertEquals(1, result.discoveredCount)
            assertEquals(1, result.enqueuedCount)
            val uniqueName = dev.hryshyn.remanence.sync.AccountWorkIdentity.outbox(owner, capsule).uniqueName
            val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().single()
            assertTrue(info.tags.containsAll(dev.hryshyn.remanence.sync.AccountWorkIdentity.outbox(owner, capsule).tags))
            assertTrue(
                info.tags.none { it.contains("path") || it.contains("token") || it.contains("key") },
            )
        } finally {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            container.database.outboxCapsuleDao().clearForOwner(owner.toRestString())
            container.database.outboxCapsuleDao().clearForOwner(otherOwner.toRestString())
            container.currentAccountStore.clear()
            container.database.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    private fun outboxCapsule(capsule: CapsuleId, owner: UserId) = OutboxCapsuleEntity(
        capsuleId = capsule.toRestString(),
        idempotencyKey = "idem-${capsule.toRestString()}",
        ownerUserId = owner.toRestString(),
        senderUserId = owner.toRestString(),
        recipientUserId = "0198f0a0-0000-7000-8000-00000000c531",
        senderKeyBundleId = null,
        recipientKeyBundleId = "0198f0a0-0000-7000-8000-00000000c532",
        senderSigningPublicKeysetB64 = null,
        state = OutboxCapsuleState.ENCRYPTED,
        recognitionManifestPath = null,
        contentManifestPath = null,
        envelopePath = null,
        publishStatementPath = null,
        publishStatementSignaturePath = null,
        senderRetryKeysetPath = null,
        lastErrorCode = null,
    )
}
