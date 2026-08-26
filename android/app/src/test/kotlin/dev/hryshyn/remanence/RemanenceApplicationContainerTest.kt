package dev.hryshyn.remanence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import java.io.File
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
        File(context.filesDir, "fingerprints").deleteRecursively()
        File(context.filesDir, "identity").deleteRecursively()
        File(context.filesDir, "session").deleteRecursively()
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
    fun defaultContainerUsesAndroidKeystoreBoundary() {
        val appContainer = AppContainer(context)
        assertTrue(appContainer.kekBoundary is dev.hryshyn.remanence.core.crypto.AndroidKeystoreKekBoundary)
    }

    @Test
    fun databaseNameIsTheSingleConfiguredFile() {
        assertEquals("remanence.db", AppContainer.DATABASE_NAME)
    }
}
