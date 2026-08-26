package dev.hryshyn.remanence.core.crypto

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityBundleRepositoryTest {

    private val kekAlias = "remanence.kek.identity-bundle"

    private fun ensureKek() {
        val boundary = InMemoryKekBoundary()
        if (!boundary.hasKey(kekAlias)) boundary.createAes256GcmKey(kekAlias)
    }

    private fun newRepository(dir: java.nio.file.Path): IdentityBundleRepository =
        IdentityBundleRepository(dir.toFile(), KeysetKekWrapper(InMemoryKekBoundary()))

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun createdBundleLoadsBackWithSameKeysets() {
        ensureKek()
        val repository = newRepository(tempDir)
        val identity = repository.createFresh(kekAlias)
        val loaded = repository.load()
        assertTrue(loaded is IdentityBundleRepository.LoadResult.Available)
        loaded as IdentityBundleRepository.LoadResult.Available
        assertTrue(identity.encryptionPrivateHandle.equalsKeyset(loaded.encryptionHandle))
        assertTrue(identity.signingPrivateHandle.equalsKeyset(loaded.signingHandle))
    }

    @Test
    fun missingBundleReportsRecoveryRequired() {
        ensureKek()
        val repository = newRepository(tempDir)
        assertFalse(repository.exists())
        assertEquals(IdentityBundleRepository.LoadResult.RecoveryRequired, repository.load())
    }

    @Test
    fun secondCreationRefusesSilentReplacement() {
        ensureKek()
        val repository = newRepository(tempDir)
        val original = repository.createFresh(kekAlias)
        assertFailsWith<java.security.GeneralSecurityException> { repository.createFresh(kekAlias) }
        val reloaded = repository.load()
        assertTrue(reloaded is IdentityBundleRepository.LoadResult.Available)
        reloaded as IdentityBundleRepository.LoadResult.Available
        assertTrue(original.encryptionPrivateHandle.equalsKeyset(reloaded.encryptionHandle))
    }

    @Test
    fun partialBundleReportsRecoveryRequired() {
        ensureKek()
        val repository = newRepository(tempDir)
        repository.createFresh(kekAlias)
        Files.delete(tempDir.resolve("identity.signing.pwks"))
        assertEquals(IdentityBundleRepository.LoadResult.RecoveryRequired, repository.load())

        val otherDir = Files.createTempDirectory("partial-enc")
        val other = newRepository(otherDir)
        other.createFresh(kekAlias)
        Files.delete(otherDir.resolve("identity.encryption.pwks"))
        assertEquals(IdentityBundleRepository.LoadResult.RecoveryRequired, other.load())
    }

    @Test
    fun corruptedRecordReportsRecoveryRequiredInsteadOfThrowing() {
        ensureKek()
        val repository = newRepository(tempDir)
        repository.createFresh(kekAlias)
        val signingFile = tempDir.resolve("identity.signing.pwks").toFile()
        val tampered = signingFile.readBytes().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        signingFile.writeBytes(tampered)
        assertEquals(IdentityBundleRepository.LoadResult.RecoveryRequired, repository.load())
    }

    @Test
    fun bundleSurvivesNewRepositoryInstanceLikeProcessRestart() {
        ensureKek()
        val first = newRepository(tempDir)
        val identity = first.createFresh(kekAlias)

        val second = newRepository(tempDir)
        val loaded = second.load()
        assertTrue(loaded is IdentityBundleRepository.LoadResult.Available)
        loaded as IdentityBundleRepository.LoadResult.Available
        assertTrue(identity.signingPrivateHandle.equalsKeyset(loaded.signingHandle))
    }
}
