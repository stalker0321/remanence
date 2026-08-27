package dev.hryshyn.remanence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
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
}
