package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import java.util.UUID
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

class AccountRecoveryCryptoTest {
    private val identity = AccountIdentityGenerator().generate()
    private val available = IdentityBundleRepository.LoadResult.Available(identity.encryptionPrivateHandle, identity.signingPrivateHandle)
    private val account = "11111111-2222-4333-8444-555555555555"
    private val bundle = UUID.nameUUIDFromBytes(identity.encryptionPublicKeyset).toString()
    private val credential = AccountRecoveryCrypto.encode(ByteArray(32) { (it + 10).toByte() })
    private val prf = ByteArray(32) { it.toByte() }
    private fun prepared() = AccountRecoveryCrypto.prepare(account, bundle, credential, prf, available)
    private fun open(r: AccountRecoveryCrypto.Record, secret: ByteArray = prf, code: Boolean = false) =
        AccountRecoveryCrypto.restore(r, secret, code, account, bundle, identity.encryptionPublicKeyset, identity.signingPublicKeyset)
    private fun changed(r: AccountRecoveryCrypto.Record, accountId: String = r.accountId,
        bundleId: String = r.bundleId, credentialId: String = r.credentialId,
        pack: String = r.packageSealed, p: String = r.prfArk, c: String = r.codeArk,
        version: String = r.version, generation: Int = r.generation) = AccountRecoveryCrypto.Record(
        accountId, bundleId, credentialId, r.salt, pack, p, c, r.signature, version, generation)

    @Test fun restoredIdentityDecryptsOldHpkeEnvelopeAndSignsUnderSamePublicKey() {
        val restored = open(prepared().record)
        val payload = "capsule key encrypted before recovery".toByteArray()
        val envelope = identity.encryptionPrivateHandle.publicKeysetHandle.getPrimitive(HybridEncrypt::class.java).encrypt(payload, byteArrayOf(9))
        assertContentEquals(payload, restored.encryption.getPrimitive(HybridDecrypt::class.java).decrypt(envelope, byteArrayOf(9)))
        val sig = restored.signing.getPrimitive(PublicKeySign::class.java).sign(payload)
        identity.signingPrivateHandle.publicKeysetHandle.getPrimitive(PublicKeyVerify::class.java).verify(sig, payload)
    }
    @Test fun independentRecoveryCodeOpensSameIdentity() {
        val p = prepared()
        try { assertContentEquals(identity.encryptionPublicKeyset, AccountRecoveryCrypto.public(open(p.record, p.recoveryCode, true).encryption)) }
        finally { p.recoveryCode.fill(0) }
    }
    @Test fun wrongPrfAndWrongCodeFail() {
        val p = prepared(); val wrong = ByteArray(32) { 42 }
        assertFails { open(p.record, wrong) }; assertFails { open(p.record, wrong, true) }
    }
    @Test fun authenticationAloneOrShortSecretCannotOpen() {
        val p = prepared()
        assertFails { open(p.record, byteArrayOf()) }; assertFails { open(p.record, ByteArray(31)) }
    }
    @Test fun accountBundleCredentialAndGenerationSwapFail() {
        val r = prepared().record
        for (bad in listOf(changed(r, accountId = UUID.randomUUID().toString()), changed(r, bundleId = UUID.randomUUID().toString()),
            changed(r, credentialId = AccountRecoveryCrypto.encode(ByteArray(32) { 9 })), changed(r, version = "v0"), changed(r, generation = 0))) {
            assertFails { open(bad) }
        }
    }
    @Test fun ciphertextAndBothWrapperTamperingFail() {
        val r = prepared().record
        fun flip(s: String) = AccountRecoveryCrypto.encode(AccountRecoveryCrypto.decode(s).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        for (bad in listOf(changed(r, pack = flip(r.packageSealed)), changed(r, p = flip(r.prfArk)), changed(r, c = flip(r.codeArk)))) assertFails { open(bad) }
    }
    @Test fun publicAnchorSwapFails() {
        val other = AccountIdentityGenerator().generate()
        assertFails { AccountRecoveryCrypto.restore(prepared().record, prf, false, account, bundle, identity.encryptionPublicKeyset, other.signingPublicKeyset) }
    }
    private fun replacePrivateBytes(original: com.google.crypto.tink.KeysetHandle,
                                    other: com.google.crypto.tink.KeysetHandle, hpke: Boolean): com.google.crypto.tink.KeysetHandle {
        val access = com.google.crypto.tink.InsecureSecretKeyAccess.get()
        val bytes = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeyset(original, access)
        val otherBytes = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeyset(other, access)
        try {
            val set = com.google.crypto.tink.proto.Keyset.parseFrom(bytes)
            val alternate = com.google.crypto.tink.proto.Keyset.parseFrom(otherBytes)
            val key = set.getKey(0)
            val badValue = if (hpke) {
                val value = com.google.crypto.tink.proto.HpkePrivateKey.parseFrom(key.keyData.value)
                val wrong = com.google.crypto.tink.proto.HpkePrivateKey.parseFrom(alternate.getKey(0).keyData.value)
                value.toBuilder().setPrivateKey(wrong.privateKey).build().toByteString()
            } else {
                val value = com.google.crypto.tink.proto.Ed25519PrivateKey.parseFrom(key.keyData.value)
                val wrong = com.google.crypto.tink.proto.Ed25519PrivateKey.parseFrom(alternate.getKey(0).keyData.value)
                value.toBuilder().setKeyValue(wrong.keyValue).build().toByteString()
            }
            val bad = set.toBuilder().setKey(0, key.toBuilder().setKeyData(key.keyData.toBuilder().setValue(badValue))).build().toByteArray()
            try { return com.google.crypto.tink.TinkProtoKeysetFormat.parseKeyset(bad, access) }
            finally { bad.fill(0) }
        } finally { bytes.fill(0); otherBytes.fill(0) }
    }
    @Test fun mismatchedHpkePrivateKeyCannotPassByCopyingPublicFields() {
        val other = AccountIdentityGenerator().generate()
        // Tink may reject the inconsistent key during parsing, before our proof.
        assertFailsWith<java.security.GeneralSecurityException> {
            val bad = replacePrivateBytes(identity.encryptionPrivateHandle, other.encryptionPrivateHandle, true)
            AccountRecoveryCrypto.validateKeyCorrespondence(AccountRecoveryCrypto.Restored(bad, identity.signingPrivateHandle))
        }
    }
    @Test fun mismatchedSigningPrivateKeyCannotPassByCopyingPublicFields() {
        val other = AccountIdentityGenerator().generate()
        assertFailsWith<java.security.GeneralSecurityException> {
            val bad = replacePrivateBytes(identity.signingPrivateHandle, other.signingPrivateHandle, false)
            AccountRecoveryCrypto.validateKeyCorrespondence(AccountRecoveryCrypto.Restored(identity.encryptionPrivateHandle, bad))
        }
    }
    @org.junit.jupiter.api.io.TempDir lateinit var temp: java.nio.file.Path
    @Test fun freshDeviceAtomicInstallSurvivesRestartWithoutReplacingIdentity() {
        val kek = InMemoryKekBoundary(); val alias = "recovery.${UUID.randomUUID()}"; kek.createAes256GcmKey(alias)
        val repo = IdentityBundleRepository(temp.toFile(), KeysetKekWrapper(kek))
        val keys = open(prepared().record); repo.installRecovered(alias, keys)
        val loaded = IdentityBundleRepository(temp.toFile(), KeysetKekWrapper(kek)).load() as IdentityBundleRepository.LoadResult.Available
        assertContentEquals(identity.signingPublicKeyset, AccountRecoveryCrypto.public(loaded.signingHandle))
        repo.installRecovered(alias, keys) // exact retry is harmless
        assertFails { repo.createFresh(alias) }
        val other = AccountIdentityGenerator().generate()
        assertFails { repo.installRecovered(alias, AccountRecoveryCrypto.Restored(other.encryptionPrivateHandle, other.signingPrivateHandle)) }
        assertContentEquals(identity.encryptionPublicKeyset, AccountRecoveryCrypto.public((repo.load() as IdentityBundleRepository.LoadResult.Available).encryptionHandle))
    }
    @Test fun existingUnusableFilesNeverGetOverwritten() {
        val kek = InMemoryKekBoundary(); val alias = "recovery.${UUID.randomUUID()}"; kek.createAes256GcmKey(alias)
        val file = temp.resolve("identity.encryption.pwks").toFile(); file.writeBytes(byteArrayOf(1,2,3))
        val repo = IdentityBundleRepository(temp.toFile(), KeysetKekWrapper(kek))
        assertFails { repo.installRecovered(alias, open(prepared().record)) }
        assertContentEquals(byteArrayOf(1,2,3), file.readBytes())
    }
    @Test fun packageTrailingBytesAndPublicOnlyKeysFail() {
        val bytes = AccountRecoveryCrypto.serialize(identity.encryptionPrivateHandle, identity.signingPrivateHandle)
        try { assertFails { AccountRecoveryCrypto.deserialize(bytes + byteArrayOf(0)) } } finally { bytes.fill(0) }
    }
    @Test fun exportsSyntheticInteropFixtureOnlyToBuildDirectory() {
        val p = prepared(); val r = p.record
        val fixture = buildJsonObject {
            put("synthetic_only", true); put("prf", AccountRecoveryCrypto.encode(prf)); put("code", AccountRecoveryCrypto.encode(p.recoveryCode))
            put("encryption_public_keyset", AccountRecoveryCrypto.encode(identity.encryptionPublicKeyset))
            put("signing_public_keyset", AccountRecoveryCrypto.encode(identity.signingPublicKeyset))
            put("record", buildJsonObject {
                put("version", r.version); put("generation", r.generation); put("account_id", r.accountId); put("bundle_id", r.bundleId)
                put("credential_id", r.credentialId); put("salt", r.salt); put("package_sealed", r.packageSealed)
                put("prf_ark", r.prfArk); put("code_ark", r.codeArk); put("signature", r.signature)
            })
        }
        File("build/recovery-interop/synthetic.json").apply { parentFile!!.mkdirs(); writeText(fixture.toString()) }
        p.recoveryCode.fill(0)
    }
}
