package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.wiring.TinkRegistrationIdentityAdapter
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the production identity adapter prepares stable flow-scoped
 * staged identities (same flow reuses, new flows mint independent keys),
 * derives a stable bundle ID, binds staged material to owners, and fails
 * closed on unusable staged material.
 */
class TinkRegistrationIdentityAdapterTest {

    private val kekAlias = "remanence.kek.v1"

    private fun stores(root: java.io.File, kek: SoftwareKekBoundary) =
        dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore(
            identityRoot = java.io.File(root, "identity"),
            stagingRoot = java.io.File(root, "identity-staging"),
            wrapper = dev.hryshyn.remanence.core.crypto.KeysetKekWrapper(kek),
        )

    @Test
    fun preparesStableIdentityAcrossRetries() {
        val directory = Files.createTempDirectory("identity-adapter").toFile()
        val kek = SoftwareKekBoundary()
        val adapter = TinkRegistrationIdentityAdapter(stores(directory, kek), kek, kekAlias)

        val first = adapter.prepareProvisional("test-flow")
        val second = adapter.prepareProvisional("test-flow")

        assertEquals(first.keyBundleId, second.keyBundleId)
        assertEquals(first.encryptionPublicKeysetB64Url, second.encryptionPublicKeysetB64Url)
        assertEquals(first.signingPublicKeysetB64Url, second.signingPublicKeysetB64Url)
        // Bundle ID is a canonical client-generated UUID string.
        assert(first.keyBundleId.matches(UUID_REGEX))
    }

    @Test
    fun newFlowMintsIndependentKeys() {
        val directory = Files.createTempDirectory("identity-adapter-flows").toFile()
        val kek = SoftwareKekBoundary()
        val adapter = TinkRegistrationIdentityAdapter(stores(directory, kek), kek, kekAlias)

        val first = adapter.prepareProvisional("flow-one")
        val second = adapter.prepareProvisional("flow-two")

        assertTrue(first.keyBundleId != second.keyBundleId)
        assertTrue(first.encryptionPublicKeysetB64Url != second.encryptionPublicKeysetB64Url)
    }

    @Test
    fun refusesToReplaceExistingUnopenableIdentity(): Unit {
        val directory = Files.createTempDirectory("identity-adapter-corrupt").toFile()
        val kek = SoftwareKekBoundary()
        val adapter = TinkRegistrationIdentityAdapter(stores(directory, kek), kek, kekAlias)

        // Stage a valid candidate, then corrupt its signing record on disk.
        val prepared = adapter.prepareProvisional("corrupt-flow")
        val signingFile = java.io.File(
            java.io.File(java.io.File(directory, "identity-staging"), prepared.keyBundleId),
            "identity.signing.pwks",
        )
        val bytes = signingFile.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        signingFile.writeBytes(bytes)

        assertThrows(IdentityRecoveryRequiredException::class.java) { adapter.prepareProvisional("corrupt-flow") }
    }

    @Test
    fun bindAdoptsStagedCandidateIntoOwnerDir() {
        val directory = Files.createTempDirectory("identity-adapter-adopt").toFile()
        val kek = SoftwareKekBoundary()
        val ownerStores = stores(directory, kek)
        val adapter = TinkRegistrationIdentityAdapter(ownerStores, kek, kekAlias)
        val owner = "0198f0a0-0000-7000-8000-00000000a001"

        val prepared = adapter.prepareProvisional("adopt-flow")
        assertTrue(adapter.bindStagedToOwner(prepared.keyBundleId, owner))
        val resolved = ownerStores.resolve(owner, prepared.keyBundleId)
        assertTrue(resolved is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available)
        // Idempotent: adopting twice succeeds without duplicating or failing.
        assertTrue(adapter.bindStagedToOwner(prepared.keyBundleId, owner))
    }

    @Test
    fun loginTimeBindAdoptsEarlierStagedCandidateWithoutGenerating() {
        val directory = Files.createTempDirectory("identity-adapter-lostack").toFile()
        val kek = SoftwareKekBoundary()
        val ownerStores = stores(directory, kek)
        val adapter = TinkRegistrationIdentityAdapter(ownerStores, kek, kekAlias)
        val owner = "0198f0a0-0000-7000-8000-00000000a002"

        // "Registration" prepares the candidate; the ACK is then lost and
        // the process dies — only sealed staging survives.
        val prepared = adapter.prepareProvisional("google:lost-ticket-flow")
        val loginAdapter = TinkRegistrationIdentityAdapter(ownerStores, kek, kekAlias)

        // "Login" for the existing account binds the retained candidate by
        // the server-assigned bundle. Binding never generates: the adopted
        // bundle equals the earlier staged one exactly.
        assertTrue(loginAdapter.bindStagedToOwner(prepared.keyBundleId, owner))
        val resolved = ownerStores.resolve(owner, prepared.keyBundleId)
        assertTrue(resolved is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available)
        // An unrelated bundle binds nothing and proves nothing.
        assertTrue(!loginAdapter.bindStagedToOwner("0198f0a0-0000-7000-8000-00000000b099", owner))
    }

    @Test
    fun verifiedLoginSeamBindsExactAssignedBundleWithoutGenerating() {
        val directory = Files.createTempDirectory("identity-adapter-vlogin").toFile()
        val kek = SoftwareKekBoundary()
        val ownerStores = stores(directory, kek)
        val adapter = TinkRegistrationIdentityAdapter(ownerStores, kek, kekAlias)
        val owner = "0198f0a0-0000-7000-8000-00000000a002"
        val stagingRoot = java.io.File(directory, "identity-staging")
        val identityRoot = java.io.File(directory, "identity")
        fun stagedIds(): Set<String> =
            stagingRoot.listFiles { file -> file.isDirectory }?.map { it.name }?.toSet() ?: emptySet()

        // Earlier registration attempt staged R; the ACK was lost.
        val staged = adapter.prepareProvisional("google:vlogin-flow")
        val stagingBefore = stagedIds()

        // Existing-account SIGNED_IN carries NO prepared identity: the
        // shared Google commit seam adopts the retained candidate matching
        // the assigned bundle — and only that bundle.
        assertTrue(adapter.bindVerifiedLogin(null, owner, staged.keyBundleId))
        val resolved = ownerStores.resolve(owner, staged.keyBundleId)
        assertTrue(resolved is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available)
        // Nothing was generated: the staged set is unchanged and no other
        // owner directory appeared.
        assertEquals(stagingBefore, stagedIds())
        assertEquals(
            setOf(owner),
            identityRoot.listFiles { file -> file.isDirectory }?.map { it.name }?.toSet(),
        )

        // Registration-shaped call (prepared matches assigned) also binds.
        val ownerC = "0198f0a0-0000-7000-8000-00000000a003"
        assertTrue(adapter.bindVerifiedLogin(staged, ownerC, staged.keyBundleId))

        // Unrelated assigned bundle binds nothing and creates nothing.
        assertTrue(!adapter.bindVerifiedLogin(null, owner, "0198f0a0-0000-7000-8000-00000000b099"))
        // Mismatched prepared identity binds nothing either.
        val other = adapter.prepareProvisional("google:vlogin-other")
        assertTrue(!adapter.bindVerifiedLogin(other, owner, staged.keyBundleId))
        assertTrue(
            ownerStores.resolve(owner, staged.keyBundleId)
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available,
        )
    }

    private companion object {
        val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
