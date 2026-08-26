package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.wiring.TinkRegistrationIdentityAdapter
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves the production identity adapter reuses one stable, wrapped identity
 * across repeated registration attempts and derives a stable bundle ID.
 */
class TinkRegistrationIdentityAdapterTest {

    private val kekAlias = "remanence.kek.v1"

    @Test
    fun preparesStableIdentityAcrossRetries() {
        val directory = Files.createTempDirectory("identity-adapter").toFile()
        val kek = SoftwareKekBoundary()
        val repository = dev.hryshyn.remanence.core.crypto.IdentityBundleRepository(
            directory,
            dev.hryshyn.remanence.core.crypto.KeysetKekWrapper(kek),
        )
        val adapter = TinkRegistrationIdentityAdapter(repository, kek, kekAlias)

        val first = adapter.prepareIdentity()
        val second = adapter.prepareIdentity()

        assertEquals(first.keyBundleId, second.keyBundleId)
        assertEquals(first.encryptionPublicKeysetB64Url, second.encryptionPublicKeysetB64Url)
        assertEquals(first.signingPublicKeysetB64Url, second.signingPublicKeysetB64Url)
        assertEquals(first.encryptionPublicKeysetB64Url, first.encryptionPublicKeysetB64Url)
        // Bundle ID is a canonical client-generated UUID string.
        assert(first.keyBundleId.matches(UUID_REGEX))
    }

    @Test
    fun refusesToReplaceExistingUnopenableIdentity(): Unit {
        val directory = Files.createTempDirectory("identity-adapter-corrupt").toFile()
        val kek = SoftwareKekBoundary()
        val wrapper = dev.hryshyn.remanence.core.crypto.KeysetKekWrapper(kek)
        val repository = dev.hryshyn.remanence.core.crypto.IdentityBundleRepository(directory, wrapper)
        val adapter = TinkRegistrationIdentityAdapter(repository, kek, kekAlias)

        // Create a valid bundle, then corrupt the signing record on disk.
        adapter.prepareIdentity()
        val signingFile = directory.resolve("identity.signing.pwks")
        val bytes = signingFile.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        signingFile.writeBytes(bytes)

        assertThrows(IdentityRecoveryRequiredException::class.java) { adapter.prepareIdentity() }
    }

    private companion object {
        val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
