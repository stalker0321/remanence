package dev.hryshyn.remanence.wiring

import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.crypto.KeysetKekWrapper
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner/bundle-scoped identity resolution, flow-staged provisional
 * creation, and idempotent archival over real sealed files and real
 * crypto (temp dirs + software KEK; no mocks of store behavior).
 * Mirrors `IdentityBundleRepositoryTest` conventions 1:1.
 */
class OwnerScopedIdentityStoreTest {

    private val kekAlias = "t210-owner-test-kek"

    private data class Fixture(
        val root: java.io.File,
        val stores: OwnerScopedIdentityStore,
        val kek: SoftwareKekBoundary,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("owner-identity").toFile()
        val kek = SoftwareKekBoundary()
        if (!kek.hasKey(kekAlias)) kek.createAes256GcmKey(kekAlias)
        val stores = OwnerScopedIdentityStore(
            identityRoot = java.io.File(root, "identity"),
            stagingRoot = java.io.File(root, "identity-staging"),
            wrapper = KeysetKekWrapper(kek),
        )
        return Fixture(root, stores, kek)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun publicBundleOf(identityRoot: java.io.File): String {
        val repository = IdentityBundleRepository(identityRoot, KeysetKekWrapper(SoftwareKekBoundary()))
        val exports = repository.loadPublicExports()
        assertTrue(exports is IdentityBundleRepository.PublicExportsResult.Available)
        exports as IdentityBundleRepository.PublicExportsResult.Available
        return java.util.UUID.nameUUIDFromBytes(exports.encryptionPublicKeyset).toString()
    }

    private fun fileHashes(directory: java.io.File): Map<String, String> =
        directory.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(directory).path to sha256Hex(file.readBytes())
        }

    /** Sealed legacy files only (direct children): owner.json claims and
     * staged material live in subdirectories and never pollute this. */
    private fun legacyFileHashes(identityDir: java.io.File): Map<String, String> =
        (identityDir.listFiles { file -> file.isFile }?.toList() ?: emptyList())
            .associate { file -> file.name to sha256Hex(file.readBytes()) }

    private val ownerA = "0198f0a0-0000-7000-8000-00000000a001"
    private val ownerB = "0198f0a0-0000-7000-8000-00000000a002"

    @Test
    fun legacyFilesStayByteIdenticalAcrossBRegisterAndRecover() {
        val (root, stores, kek) = fixture()
        IdentityBundleRepository(java.io.File(root, "identity"), KeysetKekWrapper(kek))
            .createFresh(kekAlias)
        val before = legacyFileHashes(java.io.File(root, "identity"))

        // B registers through a staged flow, then archives.
        val prepared = stores.prepareProvisional("b-flow", kekAlias)
        assertTrue(stores.adoptStaged(prepared.keyBundleId, ownerB))
        // B recovers through the owner-scoped path.
        val resolved = stores.resolve(ownerB, prepared.keyBundleId)
        assertTrue(resolved is IdentityBundleRepository.LoadResult.Available)

        assertEquals(before, legacyFileHashes(java.io.File(root, "identity")))
    }

    @Test
    fun resolvesDistinctMatchingIdentitiesAcrossABA() {
        val (root, stores, kek) = fixture()
        IdentityBundleRepository(java.io.File(root, "identity"), KeysetKekWrapper(kek))
            .createFresh(kekAlias)
        val bundleA = publicBundleOf(java.io.File(root, "identity"))
        val preparedB = stores.prepareProvisional("b-flow", kekAlias)
        assertTrue(stores.adoptStaged(preparedB.keyBundleId, ownerB))

        // Unclaimed legacy serves NOBODY's private handles — not even A.
        // Bootstrap availability (public scan) still sees A's bundle.
        assertTrue(stores.resolve(ownerA, bundleA)
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
        assertTrue(stores.hasIdentityFor(bundleA))
        // Claiming with proof binds legacy to A — and only A.
        assertTrue(stores.claimLegacyIfMatching(ownerA, bundleA))
        val loadedA = stores.resolve(ownerA, bundleA)
        assertTrue(loadedA is IdentityBundleRepository.LoadResult.Available)
        val loadedB = stores.resolve(ownerB, preparedB.keyBundleId)
        assertTrue(loadedB is IdentityBundleRepository.LoadResult.Available)
        loadedA as IdentityBundleRepository.LoadResult.Available
        loadedB as IdentityBundleRepository.LoadResult.Available
        // Distinct bundles AND distinct public key bytes per owner.
        assertNotEquals(bundleA, preparedB.keyBundleId)
        val publicB = java.util.Base64.getUrlDecoder().decode(preparedB.encryptionPublicKeysetB64Url)
        val publicA = IdentityBundleRepository(java.io.File(root, "identity"), KeysetKekWrapper(kek))
            .loadPublicExports() as IdentityBundleRepository.PublicExportsResult.Available
        assertNotEquals(publicA.encryptionPublicKeyset.toList(), publicB.toList())
        // Cross-owner requests fail closed both directions, claimed or not.
        assertTrue(stores.resolve(ownerA, preparedB.keyBundleId) is IdentityBundleRepository.LoadResult.RecoveryRequired)
        assertTrue(stores.resolve(ownerB, bundleA) is IdentityBundleRepository.LoadResult.RecoveryRequired)
    }

    @Test
    fun unknownWrongCorruptAndMalformedFailClosedWithoutGenerating() {
        val (root, stores, kek) = fixture()
        IdentityBundleRepository(java.io.File(root, "identity"), KeysetKekWrapper(kek))
            .createFresh(kekAlias)
        val bundleA = publicBundleOf(java.io.File(root, "identity"))
        val before = fileHashes(root)

        assertTrue(stores.resolve("0198f0a0-0000-7000-8000-00000000a099", bundleA)
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
        assertTrue(stores.resolve(ownerA, "0198f0a0-0000-7000-8000-00000000b099")
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
        assertTrue(stores.resolve("../escape", bundleA)
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
        assertTrue(stores.resolve(ownerA, "not-a-uuid")
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
        assertFalse(stores.hasIdentityFor("not-a-uuid"))

        // Corrupt owner dir: garbage files, no usable keys.
        val corrupt = java.io.File(java.io.File(root, "identity"), ownerB)
        corrupt.mkdirs()
        java.io.File(corrupt, "identity.encryption.pwks").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(stores.resolve(ownerB, bundleA)
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
        // Forged marker claiming a bundle the keys do not derive: the
        // availability scan ignores it (unwrap-proven only).
        val forgedBundle = "0198f0a0-0000-7000-8000-00000000f099"
        val forgedJson = """
            {"userId":"$ownerB","keyBundleId":"$forgedBundle"}
        """.trimIndent()
        java.io.File(java.io.File(root, "identity"), "owner.json").writeBytes(
            forgedJson.toByteArray(Charsets.UTF_8),
        )
        assertFalse(stores.hasIdentityFor(forgedBundle))
        assertTrue(stores.resolve(ownerB, forgedBundle)
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
        // Genuine keys still resolve by position+verify, marker ignored.
        assertTrue(stores.hasIdentityFor(bundleA))
        assertEquals(before + mapOf(
            "identity/$ownerB/identity.encryption.pwks" to sha256Hex(byteArrayOf(1, 2, 3)),
            "identity/owner.json" to sha256Hex(forgedJson.toByteArray(Charsets.UTF_8)),
        ), fileHashes(root).filterKeys { !it.startsWith("identity-staging") })
    }

    @Test
    fun stagedIsStablePerFlowAndIndependentAcrossFlows() {
        val (_, stores, _) = fixture()
        val first = stores.prepareProvisional("flow-one", kekAlias)
        val second = stores.prepareProvisional("flow-one", kekAlias)
        assertEquals(first.keyBundleId, second.keyBundleId)
        assertEquals(first.encryptionPublicKeysetB64Url, second.encryptionPublicKeysetB64Url)

        val other = stores.prepareProvisional("flow-two", kekAlias)
        assertNotEquals(first.keyBundleId, other.keyBundleId)
        assertNotEquals(first.encryptionPublicKeysetB64Url, other.encryptionPublicKeysetB64Url)
    }

    @Test
    fun lostAckLoginBindsStagedWithoutReplacement() {
        val (root, stores, _) = fixture()
        // Registration staged keys, then the process died before archival:
        // a reconstructed store instance (like a fresh process) recovers
        // the exact candidate by bundle, and adoption binds it.
        val prepared = stores.prepareProvisional("lost-ack-flow", kekAlias)
        val restarted = OwnerScopedIdentityStore(
            identityRoot = java.io.File(root, "identity"),
            stagingRoot = java.io.File(root, "identity-staging"),
            wrapper = KeysetKekWrapper(SoftwareKekBoundary()),
        )
        val recovered = restarted.resolveStaged(prepared.keyBundleId)
        assertNotNull(recovered)
        assertEquals(prepared.keyBundleId, recovered!!.keyBundleId)
        assertEquals(prepared.encryptionPublicKeysetB64Url, recovered.encryptionPublicKeysetB64Url)
        assertTrue(restarted.adoptStaged(prepared.keyBundleId, ownerB))
        val resolved = restarted.resolve(ownerB, prepared.keyBundleId)
        assertTrue(resolved is IdentityBundleRepository.LoadResult.Available)
        // Same public keys — bound, never regenerated.
        val reread = restarted.resolve(ownerB, prepared.keyBundleId)
            as IdentityBundleRepository.LoadResult.Available
        val rereadPublic = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            reread.encryptionHandle.publicKeysetHandle,
        )
        assertEquals(prepared.encryptionPublicKeysetB64Url, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rereadPublic))
    }

    @Test
    fun adoptionIsIdempotentAndRefusesMismatch() {
        val (_, stores, _) = fixture()
        val prepared = stores.prepareProvisional("adopt-flow", kekAlias)
        assertTrue(stores.adoptStaged(prepared.keyBundleId, ownerB))
        assertTrue(stores.adoptStaged(prepared.keyBundleId, ownerB))

        // A different usable store under the same owner refuses overwrite.
        val other = stores.prepareProvisional("other-flow", kekAlias)
        assertNotEquals(prepared.keyBundleId, other.keyBundleId)
        // Adopt the other bundle for A (fresh owner dir), then prove a
        // conflicting adopt into B refuses and B keeps its keys.
        assertTrue(stores.adoptStaged(other.keyBundleId, ownerA))
        assertFalse(stores.adoptStaged(other.keyBundleId, ownerB))
        assertTrue(stores.resolve(ownerB, prepared.keyBundleId)
            is IdentityBundleRepository.LoadResult.Available)
        assertTrue(stores.resolve(ownerA, other.keyBundleId)
            is IdentityBundleRepository.LoadResult.Available)
    }

    @Test
    fun rejectedAdoptionLeavesDestinationByteIdentical() {
        val (root, stores, kek) = fixture()
        // Owner B dir holds usable keys for another bundle C: crafted
        // directly, exactly as a prior legitimate adoption would.
        val ownerBDir = java.io.File(java.io.File(root, "identity"), ownerB)
        IdentityBundleRepository(ownerBDir, KeysetKekWrapper(kek)).createFresh(kekAlias)
        val bundleC = publicBundleOf(ownerBDir)
        val preparedD = stores.prepareProvisional("foreign-flow", kekAlias)
        assertNotEquals(bundleC, preparedD.keyBundleId)
        val before = fileHashes(ownerBDir)

        // Adopting a different bundle refuses WITHOUT touching B's files.
        assertFalse(stores.adoptStaged(preparedD.keyBundleId, ownerB))
        assertEquals(before, fileHashes(ownerBDir))
        // B's own material still resolves; D never landed anywhere.
        assertTrue(stores.resolve(ownerB, bundleC)
            is IdentityBundleRepository.LoadResult.Available)
        assertFalse(stores.hasIdentityFor(preparedD.keyBundleId))
    }

    @Test
    fun rejectedPartialDestinationWritesNothingBeforeRefusing() {
        val (root, stores, _) = fixture()
        val prepared = stores.prepareProvisional("preflight-flow", kekAlias)
        val dest = java.io.File(java.io.File(root, "identity"), ownerB)
        dest.mkdirs()
        // Missing encryption record + FOREIGN signing record: a
        // copy-while-checking loop would write encryption first and only
        // then reject signing — the tree would change despite rejection.
        // Full preflight must refuse before ANY write.
        java.io.File(dest, "identity.signing.pwks").writeBytes(byteArrayOf(7, 7, 7))
        val before = fileHashes(root)

        assertFalse(stores.adoptStaged(prepared.keyBundleId, ownerB))
        assertEquals(before, fileHashes(root))
        assertFalse(stores.hasIdentityFor(prepared.keyBundleId))
    }

    @Test
    fun staleAdoptCannotAlterActiveAccountState() {
        val (root, stores, kek) = fixture()
        IdentityBundleRepository(java.io.File(root, "identity"), KeysetKekWrapper(kek))
            .createFresh(kekAlias)
        val bundleA = publicBundleOf(java.io.File(root, "identity"))
        val preparedB = stores.prepareProvisional("stale-flow", kekAlias)
        assertTrue(stores.adoptStaged(preparedB.keyBundleId, ownerB))
        val treeBefore = fileHashes(root)
        val legacyBefore = legacyFileHashes(java.io.File(root, "identity"))

        // Hostile replays: re-adopt, unknown bundles, mismatched claims.
        // None may touch A's files, tokens/readiness-adjacent state, or grants
        // (the store has no such APIs — the tree proves containment).
        assertTrue(stores.adoptStaged(preparedB.keyBundleId, ownerB))
        assertFalse(stores.adoptStaged("0198f0a0-0000-7000-8000-00000000b099", ownerB))
        assertFalse(stores.claimLegacyIfMatching(ownerB, preparedB.keyBundleId))
        assertTrue(stores.resolve(ownerB, preparedB.keyBundleId)
            is IdentityBundleRepository.LoadResult.Available)
        // A is untouched and still bootstrap-visible; only expected new
        // paths (B dir + claim, staging) appeared.
        assertEquals(legacyBefore, legacyFileHashes(java.io.File(root, "identity")))
        assertTrue(stores.hasIdentityFor(bundleA))
        val added = fileHashes(root).keys - treeBefore.keys
        assertTrue(added.all { it.startsWith("identity/$ownerB/") || it.startsWith("identity-staging/") })
    }

    @Test
    fun recoveryInstallWritesBDirAndLeavesAByteIdentical() {
        val (root, stores, kek) = fixture()
        val legacyRoot = java.io.File(root, "identity")
        IdentityBundleRepository(legacyRoot, KeysetKekWrapper(kek)).createFresh(kekAlias)
        val bundleA = publicBundleOf(legacyRoot)
        // Snapshot A's sealed files BEFORE the claim marker exists; the
        // claim itself is asserted separately as a permitted path below.
        val sealedBefore = legacyFileHashes(legacyRoot)
        assertTrue(stores.claimLegacyIfMatching(ownerA, bundleA))

        // Real recovery path: the staged candidate's unwrapped handles go
        // through the SAME installRecovered kernel production recovery uses
        // (no adoption involved), writing B's owner directory only.
        val preparedB = stores.prepareProvisional("restore-install-flow", kekAlias)
        val stagedB = IdentityBundleRepository(
            java.io.File(java.io.File(root, "identity-staging"), preparedB.keyBundleId),
            KeysetKekWrapper(kek),
        ).load()
        assertTrue(stagedB is IdentityBundleRepository.LoadResult.Available)
        stagedB as IdentityBundleRepository.LoadResult.Available
        stores.installRecovered(
            ownerB,
            kekAlias,
            dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto.Restored(
                stagedB.encryptionHandle,
                stagedB.signingHandle,
            ),
        )
        assertTrue(stores.resolve(ownerB, preparedB.keyBundleId)
            is IdentityBundleRepository.LoadResult.Available)
        assertTrue(stores.resolve(ownerA, bundleA)
            is IdentityBundleRepository.LoadResult.Available)
        // A's sealed records are byte-identical; only the permitted
        // claim marker was added at the legacy root.
        val sealedAfter = legacyFileHashes(legacyRoot)
        assertEquals(sealedBefore, sealedAfter.filterKeys { it != "owner.json" })
        assertTrue(sealedAfter.containsKey("owner.json"))
        val ownerBDir = java.io.File(legacyRoot, ownerB)
        val recoveredBefore = fileHashes(ownerBDir)

        // A mismatched recovery bundle for B refuses without touching B.
        val preparedC = stores.prepareProvisional("restore-mismatch-flow", kekAlias)
        val stagedC = IdentityBundleRepository(
            java.io.File(java.io.File(root, "identity-staging"), preparedC.keyBundleId),
            KeysetKekWrapper(kek),
        ).load() as IdentityBundleRepository.LoadResult.Available
        try {
            stores.installRecovered(
                ownerB,
                kekAlias,
                dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto.Restored(
                    stagedC.encryptionHandle,
                    stagedC.signingHandle,
                ),
            )
            assertTrue("mismatched recovery install must refuse", false)
        } catch (_: Exception) {
        }
        assertTrue(stores.resolve(ownerB, preparedB.keyBundleId)
            is IdentityBundleRepository.LoadResult.Available)
        assertFalse(stores.hasIdentityFor(preparedC.keyBundleId))
        // The refused install left B's store byte-identical too.
        assertEquals(recoveredBefore, fileHashes(ownerBDir))
    }

    @Test
    fun restoreOntoAInstallationWritesBDirAndKeepsAReadable() {
        val (root, stores, kek) = fixture()
        IdentityBundleRepository(java.io.File(root, "identity"), KeysetKekWrapper(kek))
            .createFresh(kekAlias)
        val bundleA = publicBundleOf(java.io.File(root, "identity"))
        // A's legacy home is claimed with proof, as a verified login would.
        assertTrue(stores.claimLegacyIfMatching(ownerA, bundleA))
        val preparedB = stores.prepareProvisional("restore-flow", kekAlias)
        assertTrue(stores.adoptStaged(preparedB.keyBundleId, ownerB))

        // B's directory holds B's keys; A's claimed legacy home still resolves A.
        assertTrue(stores.resolve(ownerB, preparedB.keyBundleId)
            is IdentityBundleRepository.LoadResult.Available)
        assertTrue(stores.resolve(ownerA, bundleA)
            is IdentityBundleRepository.LoadResult.Available)
        // Owner claim markers record both bindings, publicly.
        val claimB = stores.readClaim(java.io.File(java.io.File(root, "identity"), ownerB))
        assertEquals(ownerB, claimB?.ownerUserId)
        assertEquals(preparedB.keyBundleId, claimB?.keyBundleId)
    }

    @Test
    fun legacyClaimNeedsProofAndRefusesMismatch() {
        val (root, stores, kek) = fixture()
        IdentityBundleRepository(java.io.File(root, "identity"), KeysetKekWrapper(kek))
            .createFresh(kekAlias)
        val bundleA = publicBundleOf(java.io.File(root, "identity"))

        // Unclaimed legacy serves no private handles, even to A.
        assertTrue(stores.resolve(ownerA, bundleA)
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
        // Proof (login's owner+bundle equals the legacy exports): claimed.
        assertTrue(stores.claimLegacyIfMatching(ownerA, bundleA))
        val claim = stores.readClaim(java.io.File(root, "identity"))
        assertEquals(ownerA, claim?.ownerUserId)
        assertEquals(bundleA, claim?.keyBundleId)
        // Claimed legacy now serves its bound owner.
        assertTrue(stores.resolve(ownerA, bundleA)
            is IdentityBundleRepository.LoadResult.Available)
        // Mismatch never claims and never blocks: B proceeds empty, and a
        // claim for B never unlocks A's material for B either.
        assertFalse(stores.claimLegacyIfMatching(ownerB, bundleA))
        assertTrue(stores.resolve(ownerB, bundleA)
            is IdentityBundleRepository.LoadResult.RecoveryRequired)
    }

    @Test
    fun coldBootstrapPerOwnerWithoutGlobalHolder() {
        // Legacy A home plus adopted B: each bundle resolves, unknown does not.
        val legacyRoot = Files.createTempDirectory("identity-bootstrap").toFile()
        val kek = SoftwareKekBoundary()
        if (!kek.hasKey(kekAlias)) kek.createAes256GcmKey(kekAlias)
        IdentityBundleRepository(legacyRoot, KeysetKekWrapper(kek)).createFresh(kekAlias)
        val stores2 = OwnerScopedIdentityStore(
            identityRoot = legacyRoot,
            stagingRoot = Files.createTempDirectory("identity-bootstrap-staging").toFile(),
            wrapper = KeysetKekWrapper(kek),
        )
        val bundleA = publicBundleOf(legacyRoot)
        val preparedB = stores2.prepareProvisional("bootstrap-flow", kekAlias)
        assertTrue(stores2.adoptStaged(preparedB.keyBundleId, ownerB))
        assertTrue(stores2.hasIdentityFor(bundleA))
        assertTrue(stores2.hasIdentityFor(preparedB.keyBundleId))
        assertFalse(stores2.hasIdentityFor("0198f0a0-0000-7000-8000-00000000b099"))
        // Staged-only material is never readiness.
        val stagedOnly = stores2.prepareProvisional("staged-only-flow", kekAlias)
        assertFalse(stores2.hasIdentityFor(stagedOnly.keyBundleId))
    }

    @Test
    fun crashCutsFailClosedAndConverge() {
        val (root, stores, kek) = fixture()
        // (a) Flow binding present but staged signing missing: no regen.
        val doomed = stores.prepareProvisional("crash-flow", kekAlias)
        val doomedDir = java.io.File(java.io.File(root, "identity-staging"), doomed.keyBundleId)
        assertTrue(java.io.File(doomedDir, "identity.signing.pwks").delete())
        try {
            stores.prepareProvisional("crash-flow", kekAlias)
            assertTrue("corrupt staged flow must fail closed", false)
        } catch (_: Exception) {
        }
        // (b) Partial owner dir (enc only, crashed copy): adopt completes it.
        val prepared = stores.prepareProvisional("partial-flow", kekAlias)
        val partial = java.io.File(java.io.File(root, "identity"), ownerB)
        partial.mkdirs()
        java.io.File(
            java.io.File(java.io.File(root, "identity-staging"), prepared.keyBundleId),
            "identity.encryption.pwks",
        ).copyTo(java.io.File(partial, "identity.encryption.pwks"))
        assertTrue(stores.adoptStaged(prepared.keyBundleId, ownerB))
        assertTrue(stores.resolve(ownerB, prepared.keyBundleId)
            is IdentityBundleRepository.LoadResult.Available)
        // (c) Blocked flows dir: prepare fails before any staged write.
        val blocked = OwnerScopedIdentityStore(
            identityRoot = java.io.File(root, "identity-blocked"),
            stagingRoot = java.io.File(root, "identity-staging-blocked"),
            wrapper = KeysetKekWrapper(kek),
        )
        java.io.File(root, "identity-staging-blocked").mkdirs()
        java.io.File(java.io.File(root, "identity-staging-blocked"), "flows").writeBytes(byteArrayOf(0))
        try {
            blocked.prepareProvisional("blocked-flow", kekAlias)
            assertTrue("blocked flow binding must fail closed", false)
        } catch (_: Exception) {
        }
        // Exactly one staged bundle directory exists, and its keys are
        // intact and bundle-addressable (preserved for later recovery),
        // but UNBOUND: no flow file resolves it, so nothing mistakes it
        // for a stable candidate.
        val orphanDirs = java.io.File(root, "identity-staging-blocked").listFiles { file -> file.isDirectory }
        assertEquals(1, orphanDirs?.size ?: 0)
        val orphanBundle = orphanDirs!!.single().name
        val orphan = blocked.resolveStaged(orphanBundle)
        assertNotNull(orphan)
        assertEquals(orphanBundle, orphan!!.keyBundleId)
        // Unrelated helper state untouched by the failure above.
        assertTrue(stores.resolve(ownerB, prepared.keyBundleId)
            is IdentityBundleRepository.LoadResult.Available)
        Unit
    }
}
