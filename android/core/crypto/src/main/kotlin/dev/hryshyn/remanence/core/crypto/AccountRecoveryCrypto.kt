package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** ADR-011: no plaintext identity, ARK, code or PRF is a network result. */
object AccountRecoveryCrypto {
    const val VERSION = "REMANENCE-RECOVERY-V1"
    const val MAX_PACKAGE = 32768
    val prfInput: ByteArray get() = sha("remanence/recovery/prf-input/v1".toByteArray())
    class Record(val accountId: String, val bundleId: String, val credentialId: String,
        val salt: String, val packageSealed: String, val prfArk: String,
        val codeArk: String, val signature: String,
        val version: String = VERSION, val generation: Int = 1) {
        override fun toString() = "RecoveryRecord(<redacted>)"
    }
    class Prepared(val record: Record, val recoveryCode: ByteArray) {
        override fun toString() = "PreparedRecovery(<redacted>)"
    }
    class Restored(val encryption: KeysetHandle, val signing: KeysetHandle) {
        override fun toString() = "RestoredIdentity(<redacted>)"
    }
    fun prepare(account: String, bundle: String, credential: String, prf: ByteArray,
        identity: IdentityBundleRepository.LoadResult.Available): Prepared {
        require(prf.size == 32)
        val salt = encode(random(32)); val ctx = context(account, bundle, credential, salt)
        require(UUID.nameUUIDFromBytes(public(identity.encryptionHandle)).toString() == bundle)
        val ark = random(32); val code = random(32)
        var plain: ByteArray? = null; var pk: ByteArray? = null; var ck: ByteArray? = null
        try {
            plain = serialize(identity.encryptionHandle, identity.signingHandle)
            val pack = seal(ark, ctx + "\npackage", plain)
            pk = derive(prf, decode(salt), ctx + "\nprf-ark")
            ck = derive(code, decode(salt), ctx + "\ncode-ark")
            val p = seal(pk, ctx + "\nprf-ark", ark); val c = seal(ck, ctx + "\ncode-ark", ark)
            val sig = identity.signingHandle.getPrimitive(PublicKeySign::class.java).sign(manifest(ctx, pack, p, c))
            val record = Record(account, bundle, credential, salt, encode(pack), encode(p), encode(c), encode(sig))
            // Validate the entire package locally before readiness/upload.
            restore(record, prf, false, account, bundle, public(identity.encryptionHandle), public(identity.signingHandle))
            restore(record, code, true, account, bundle, public(identity.encryptionHandle), public(identity.signingHandle))
            return Prepared(record, code)
        } catch (error: Throwable) { code.fill(0); throw error }
        finally { ark.fill(0); plain?.fill(0); pk?.fill(0); ck?.fill(0) }
    }
    fun restore(record: Record, secret: ByteArray, useCode: Boolean, expectedAccount: String,
        expectedBundle: String, encryptionPublic: ByteArray, signingPublic: ByteArray): Restored {
        require(secret.size == 32)
        if (record.version != VERSION || record.generation != 1 || record.accountId != expectedAccount || record.bundleId != expectedBundle) fail()
        if (UUID.nameUUIDFromBytes(encryptionPublic).toString() != expectedBundle) fail()
        val ctx = context(record.accountId, record.bundleId, record.credentialId, record.salt)
        val pack = decode(record.packageSealed); val p = decode(record.prfArk); val c = decode(record.codeArk)
        if (pack.size !in 29..MAX_PACKAGE + 28 || p.size != 60 || c.size != 60) fail()
        TinkPrimitives.ensureRegistered()
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(signingPublic).getPrimitive(PublicKeyVerify::class.java)
            .verify(decode(record.signature), manifest(ctx, pack, p, c))
        val purpose = if (useCode) "\ncode-ark" else "\nprf-ark"
        val key = derive(secret, decode(record.salt), ctx + purpose)
        var ark: ByteArray? = null; var plain: ByteArray? = null
        try {
            ark = open(key, ctx + purpose, if (useCode) c else p); if (ark.size != 32) fail()
            plain = open(ark, ctx + "\npackage", pack); val keys = deserialize(plain)
            if (!MessageDigest.isEqual(public(keys.encryption), encryptionPublic) || !MessageDigest.isEqual(public(keys.signing), signingPublic)) fail()
            validateKeyCorrespondence(keys)
            return keys
        } finally { key.fill(0); ark?.fill(0); plain?.fill(0) }
    }
    /** Public fields embedded in a private keyset are not proof of possession. */
    internal fun validateKeyCorrespondence(keys: Restored) {
        val challenge = random(32)
        var opened: ByteArray? = null
        try {
            val aad = "remanence/recovery/key-correspondence/v1".toByteArray()
            val encrypted = keys.encryption.publicKeysetHandle.getPrimitive(com.google.crypto.tink.HybridEncrypt::class.java)
                .encrypt(challenge, aad)
            opened = keys.encryption.getPrimitive(com.google.crypto.tink.HybridDecrypt::class.java).decrypt(encrypted, aad)
            if (!MessageDigest.isEqual(challenge, opened)) fail()
            val signed = keys.signing.getPrimitive(PublicKeySign::class.java).sign(challenge)
            keys.signing.publicKeysetHandle.getPrimitive(PublicKeyVerify::class.java).verify(signed, challenge)
        } finally { challenge.fill(0); opened?.fill(0) }
    }
    internal fun serialize(enc: KeysetHandle, sign: KeysetHandle): ByteArray {
        val e = TinkProtoKeysetFormat.serializeKeyset(enc, InsecureSecretKeyAccess.get())
        val s = TinkProtoKeysetFormat.serializeKeyset(sign, InsecureSecretKeyAccess.get())
        try {
            return ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
                it.writeBytes("RPK1"); it.writeInt(e.size); it.write(e); it.writeInt(s.size); it.write(s)
            } }.toByteArray().also { if (it.size > MAX_PACKAGE) fail() }
        } finally { e.fill(0); s.fill(0) }
    }
    internal fun deserialize(bytes: ByteArray): Restored {
        if (bytes.size > MAX_PACKAGE) fail()
        return DataInputStream(ByteArrayInputStream(bytes)).use { stream ->
            if (stream.readInt() != 0x52504b31) fail()
            fun key(): KeysetHandle {
                val n = stream.readInt(); if (n !in 1..MAX_PACKAGE || n > stream.available()) fail()
                val data = ByteArray(n); stream.readFully(data)
                try { return TinkProtoKeysetFormat.parseKeyset(data, InsecureSecretKeyAccess.get()) }
                finally { data.fill(0) }
            }
            val e = key(); val s = key(); if (stream.available() != 0) fail()
            e.getPrimitive(com.google.crypto.tink.HybridDecrypt::class.java); s.getPrimitive(PublicKeySign::class.java)
            Restored(e, s)
        }
    }
    fun public(handle: KeysetHandle): ByteArray = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle)
    fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    fun decode(value: String): ByteArray {
        if (value.length !in 1..45000 || !value.matches(Regex("[A-Za-z0-9_-]+"))) fail()
        return Base64.getUrlDecoder().decode(value).also { if (encode(it) != value) fail() }
    }
    internal fun context(account: String, bundle: String, credential: String, salt: String): String {
        if (UUID.fromString(account).toString() != account || UUID.fromString(bundle).toString() != bundle || decode(credential).size !in 1..1024 || decode(salt).size != 32) fail()
        return "$VERSION\n$account\n$bundle\n1\n$credential\n$salt"
    }
    internal fun manifest(ctx: String, pack: ByteArray, p: ByteArray, c: ByteArray) =
        (ctx + "\nmanifest").toByteArray() + sha(pack) + sha(p) + sha(c)
    internal fun derive(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(salt, "HmacSHA256")); val extract = mac.doFinal(secret)
        try { mac.init(SecretKeySpec(extract, "HmacSHA256")); return mac.doFinal(info.toByteArray() + byteArrayOf(1)) }
        finally { extract.fill(0) }
    }
    private fun seal(key: ByteArray, aad: String, plain: ByteArray): ByteArray {
        val iv = random(12); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); cipher.updateAAD(aad.toByteArray())
        return iv + cipher.doFinal(plain)
    }
    private fun open(key: ByteArray, aad: String, sealed: ByteArray): ByteArray {
        if (sealed.size < 28) fail()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12))); cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun fail(): Nothing = throw GeneralSecurityException("recovery integrity check failed")
}
