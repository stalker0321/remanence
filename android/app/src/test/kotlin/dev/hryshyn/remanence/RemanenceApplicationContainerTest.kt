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
import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleEntity
import dev.hryshyn.remanence.core.data.db.OutboxCapsuleState
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.recognition.ExtractionQuality
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintKeypoint
import dev.hryshyn.remanence.core.recognition.FingerprintSide as RecognitionFingerprintSide
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.index.SenderIndexBundleReadRequest
import dev.hryshyn.remanence.index.SenderIndexBundleReadResult
import dev.hryshyn.remanence.index.SenderIndexBundleStageRequest
import dev.hryshyn.remanence.index.SenderIndexBundleStageResult
import dev.hryshyn.remanence.sync.IncomingCapsuleAcceptanceRequest
import dev.hryshyn.remanence.sync.IncomingCapsuleAcceptanceRejectionReason
import dev.hryshyn.remanence.sync.IncomingCapsuleAcceptanceResult
import dev.hryshyn.remanence.sync.IncomingAcceptanceRejectionReason
import dev.hryshyn.remanence.sync.IncomingControlIndexAcceptanceRequest
import dev.hryshyn.remanence.sync.IncomingControlIndexAcceptanceResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.FingerprintSide as StoredFingerprintSide
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.sync.CapsuleUploadWorker
import dev.hryshyn.remanence.sync.IncomingCapsuleSyncWorker
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
            side = StoredFingerprintSide.FRONT,
            origin = FingerprintOrigin.SENDER,
            profileId = "mvp-orb-v1",
            plaintextBytes = "fp".toByteArray(),
        )
        assertTrue(container.fingerprintPersistence.hasBaseline("capsule-1", StoredFingerprintSide.FRONT, FingerprintOrigin.SENDER))

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
    fun incomingAcceptanceSessionRejectsClearedOrRotatedTokenBeforePublication() = runBlocking {
        val appContainer = container()
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000d401")
        try {
            appContainer.currentAccountStore.record(
                owner.toRestString(),
                "alice",
                "0198f0a0-0000-7000-8000-00000000d402",
            )

            listOf(false, true).forEach { rotate ->
                appContainer.authTokenHolder.updateTokens("old-access", "old-refresh")
                val hasSession = appContainer.hasIncomingAcceptanceSessionForTesting {
                    if (rotate) {
                        appContainer.authTokenHolder.updateTokens("new-access", "new-refresh")
                    } else {
                        appContainer.authTokenHolder.clearSession()
                    }
                }
                assertFalse("credential changed during session publication", hasSession)
            }
        } finally {
            appContainer.authTokenHolder.clearSession()
            appContainer.currentAccountStore.clear()
            appContainer.database.close()
        }
    }

    @Test
    fun recipientIdentityRejectsClearedOrRotatedTokenBeforePublication() = runBlocking {
        val appContainer = container()
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000d411")
        try {
            val prepared = appContainer.registrationIdentityAdapter.prepareIdentity()
            appContainer.currentAccountStore.record(
                owner.toRestString(),
                "alice",
                prepared.keyBundleId,
            )

            listOf(false, true).forEach { rotate ->
                appContainer.authTokenHolder.updateTokens("old-access", "old-refresh")
                val hasIdentity = appContainer.hasCurrentRecipientEncryptionIdentityForTesting {
                    if (rotate) {
                        appContainer.authTokenHolder.updateTokens("new-access", "new-refresh")
                    } else {
                        appContainer.authTokenHolder.clearSession()
                    }
                }
                assertFalse("credential changed during identity publication", hasIdentity)
            }
        } finally {
            appContainer.authTokenHolder.clearSession()
            appContainer.currentAccountStore.clear()
            appContainer.database.close()
        }
    }

    @Test
    fun incomingAcceptanceCoordinatorIsOneLazyConcreteInstanceAndHasNoFallback() = runBlocking {
        val appContainer = container()
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000d421")
        val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000d422")
        try {
            val first = appContainer.incomingCapsuleAcceptanceCoordinator
            assertSame(first, appContainer.incomingCapsuleAcceptanceCoordinator)

            val result = first.accept(IncomingCapsuleAcceptanceRequest(owner, capsule))

            assertEquals(
                IncomingCapsuleAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER,
                assertIs<IncomingCapsuleAcceptanceResult.Rejected>(result).reason,
            )

            appContainer.currentAccountStore.record(
                owner.toRestString(),
                "alice",
                "0198f0a0-0000-7000-8000-00000000d423",
            )
            val noToken = first.accept(IncomingCapsuleAcceptanceRequest(owner, capsule))
            assertEquals(
                IncomingCapsuleAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER,
                assertIs<IncomingCapsuleAcceptanceResult.Rejected>(noToken).reason,
            )
        } finally {
            appContainer.currentAccountStore.clear()
            appContainer.authTokenHolder.clearSession()
            appContainer.database.close()
        }
    }

    @Test
    fun recipientIdentityBindsExactOwnerAndBundleAcrossAccountChanges() = runBlocking {
        val appContainer = container()
        val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-00000000d431")
        val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-00000000d432")
        val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000d433")
        val sentinel = File(context.filesDir, "a12b4a-identity-sentinel").apply {
            writeBytes(byteArrayOf(7, 8, 9))
        }
        val sentinelBytes = sentinel.readBytes()
        try {
            val prepared = appContainer.registrationIdentityAdapter.prepareIdentity()
            appContainer.currentAccountStore.record(
                ownerA.toRestString(),
                "alice",
                "0198f0a0-0000-7000-8000-00000000d434",
            )
            appContainer.authTokenHolder.updateTokens("access-a", "refresh-a")

            val mismatch = appContainer.incomingControlIndexAcceptanceCoordinator.accept(
                IncomingControlIndexAcceptanceRequest(ownerA, capsule, sentinel),
            )
            assertEquals(
                IncomingAcceptanceRejectionReason.NO_AUTHENTICATED_OWNER,
                assertIs<IncomingControlIndexAcceptanceResult.Rejected>(mismatch).reason,
            )
            assertTrue(sentinel.readBytes().contentEquals(sentinelBytes))

            appContainer.currentAccountStore.record(
                ownerA.toRestString(),
                "alice",
                prepared.keyBundleId,
            )
            val exact = appContainer.incomingControlIndexAcceptanceCoordinator.accept(
                IncomingControlIndexAcceptanceRequest(ownerA, capsule, sentinel),
            )
            assertEquals(
                IncomingAcceptanceRejectionReason.CAPSULE_METADATA_MISSING,
                assertIs<IncomingControlIndexAcceptanceResult.Rejected>(exact).reason,
            )
            assertTrue(sentinel.readBytes().contentEquals(sentinelBytes))

            appContainer.currentAccountStore.record(
                ownerB.toRestString(),
                "bob",
                prepared.keyBundleId,
            )
            val switched = appContainer.incomingControlIndexAcceptanceCoordinator.accept(
                IncomingControlIndexAcceptanceRequest(ownerA, capsule, sentinel),
            )
            assertEquals(
                IncomingAcceptanceRejectionReason.OWNER_MISMATCH,
                assertIs<IncomingControlIndexAcceptanceResult.Rejected>(switched).reason,
            )
            assertTrue(sentinel.readBytes().contentEquals(sentinelBytes))
        } finally {
            sentinel.delete()
            appContainer.authTokenHolder.clearSession()
            appContainer.currentAccountStore.clear()
            appContainer.database.close()
        }
    }

    @Test
    fun sharedSenderIndexSealerStagesAndReadsOwnerCapsuleBundle() = runBlocking {
        val appContainer = container()
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000d441")
        val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000d442")
        try {
            val staged = appContainer.senderIndexBundleStager.stage(
                SenderIndexBundleStageRequest(
                    authenticatedOwnerUserId = owner,
                    ownerUserId = owner,
                    capsuleId = capsule,
                    verifiedRecognition = validRecognition(capsule),
                ),
            )
            assertFalse(assertIs<SenderIndexBundleStageResult.Staged>(staged).replayed)

            val inspected = appContainer.senderIndexBundleReader.inspect(
                SenderIndexBundleReadRequest(
                    authenticatedOwnerUserId = owner,
                    ownerUserId = owner,
                    capsuleId = capsule,
                ),
            )
            val available = assertIs<SenderIndexBundleReadResult.Available>(inspected)
            try {
                assertEquals(capsule, available.snapshot.capsuleId)
                assertEquals("alice_1", available.snapshot.senderHandleSnapshot)
                assertTrue(available.snapshot.frontFingerprint.isNotEmpty())
                assertTrue(available.snapshot.backFingerprint.isNotEmpty())
            } finally {
                available.snapshot.close()
            }
        } finally {
            appContainer.database.close()
        }
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

    @Test
    fun containerSchedulesIncomingOnlyForTheCurrentOwnerWithLiveCredentials() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: androidx.work.WorkerParameters,
                    ): ListenableWorker? = if (workerClassName == IncomingCapsuleSyncWorker::class.java.name) {
                        NoOpWorker(appContext, workerParameters)
                    } else {
                        null
                    }
                })
                .build(),
        )
        val container = AppContainer(context, kekBoundaryOverride = SoftwareKekBoundary())
        val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c601")
        val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000c602")
        val workManager = WorkManager.getInstance(context)

        try {
            val ownerWorkName = dev.hryshyn.remanence.sync.AccountWorkIdentity
                .incomingSync(owner).uniqueName
            val otherWorkName = dev.hryshyn.remanence.sync.AccountWorkIdentity
                .incomingSync(otherOwner).uniqueName

            // No local account or access token means the authenticated-root
            // callback is a no-op, even when handed a typed owner.
            container.scheduleIncomingSync(owner)
            assertTrue(workManager.getWorkInfosForUniqueWork(ownerWorkName).get().isEmpty())

            container.currentAccountStore.record(
                owner.toRestString(),
                "mykola",
                "0198f0a0-0000-7000-8000-00000000c603",
            )
            container.scheduleIncomingSync(owner)
            assertTrue(workManager.getWorkInfosForUniqueWork(ownerWorkName).get().isEmpty())

            container.authTokenHolder.updateTokens("access-token", "refresh-token")
            container.scheduleIncomingSync(owner)
            container.scheduleIncomingSync(owner)
            assertEquals(1, workManager.getWorkInfosForUniqueWork(ownerWorkName).get().size)

            // A valid but different owner cannot reuse the authenticated A
            // container boundary to enqueue B's chain.
            container.scheduleIncomingSync(otherOwner)
            assertTrue(workManager.getWorkInfosForUniqueWork(otherWorkName).get().isEmpty())
        } finally {
            container.currentAccountStore.clear()
            container.authTokenHolder.clearSession()
            container.database.close()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    private fun validRecognition(capsule: CapsuleId) = RecognitionManifestContent(
        protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
        capsuleIdRaw = capsule.toProtoBytes().toByteArray(),
        senderHandleSnapshot = "alice_1",
        createdAtEpochSeconds = 1_700_000_000L,
        placeLabel = "Paris",
        frontFingerprint = fingerprint(RecognitionFingerprintSide.FRONT),
        backFingerprint = fingerprint(RecognitionFingerprintSide.BACK),
    )

    private fun fingerprint(side: RecognitionFingerprintSide): ByteArray = FingerprintCodec.serialize(
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
            quality = ExtractionQuality(
                blurScore = 1.0,
                exposureScore = 1.0,
                glareFraction = 0.1,
                detectedAreaRatio = 0.5,
            ),
        ),
    )

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T)
        @Suppress("UNCHECKED_CAST")
        return value as T
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
