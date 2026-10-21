package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto as Crypto
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.data.network.AtomicWire
import dev.hryshyn.remanence.recovery.RecoveryCapabilityUnavailable
import dev.hryshyn.remanence.wiring.PreparedIdentity
import java.util.UUID
import kotlinx.serialization.json.*

/** RAM only: no provisional store/KEK/Room write, only existing ADR011 bytes. */
internal class AtomicCandidate(val bundle: JsonObject, val record: JsonObject, var prf: ByteArray) {
    fun wipe() = prf.fill(0)
    override fun toString() = "AtomicCandidate(<redacted>)"
}

internal fun prepareAtomicCandidate(account: String, credential: String, prf: ByteArray,
    generate: () -> AccountIdentityGenerator.AccountIdentity): AtomicCandidate {
    val identity = generate()
    val bundle = UUID.nameUUIDFromBytes(identity.encryptionPublicKeyset).toString()
    val prepared = Crypto.prepare(account, bundle, credential, prf,
        IdentityBundleRepository.LoadResult.Available(identity.encryptionPrivateHandle, identity.signingPrivateHandle))
    try {
        return AtomicCandidate(buildJsonObject {
            put("key_bundle_id", bundle); put("suite", PreparedIdentity.SUITE); put("protocol_version", 1)
            put("encryption_public_keyset", Crypto.encode(identity.encryptionPublicKeyset))
            put("signing_public_keyset", Crypto.encode(identity.signingPublicKeyset))
        }, prepared.record.atomicJson(), prf)
    } finally {
        prepared.recoveryCode.fill(0)
        // Tink handles are not retained after prepare's two real local unwraps.
        // Tink/JVM internal memory erasure is not an application guarantee.
    }
}

internal fun unwrapAtomic(candidate: AtomicCandidate, readback: JsonObject, prf: ByteArray = candidate.prf): Crypto.Restored {
    val record = readback.getValue("record").jsonObject
    require(record == candidate.record) // ALL encrypted/canonical fields, not just IDs.
    val enc = AtomicWire.text(readback, "encryption_public_keyset", 16384)
    val sign = AtomicWire.text(readback, "signing_public_keyset", 16384)
    require(enc == AtomicWire.text(candidate.bundle, "encryption_public_keyset"))
    require(sign == AtomicWire.text(candidate.bundle, "signing_public_keyset"))
    val parsed = record.atomicRecord()
    return Crypto.restore(parsed, prf, false, parsed.accountId, parsed.bundleId, Crypto.decode(enc), Crypto.decode(sign))
}

internal fun AtomicCandidate.localReadback(): JsonObject = buildJsonObject {
    put("record", record)
    put("encryption_public_keyset", bundle.getValue("encryption_public_keyset"))
    put("signing_public_keyset", bundle.getValue("signing_public_keyset"))
}

internal fun atomicPrf(credential: JsonObject): ByteArray {
    val value = credential["clientExtensionResults"]?.jsonObject?.get("prf")?.jsonObject
        ?.get("results")?.jsonObject?.get("first")?.jsonPrimitive?.takeIf { it.isString }?.content
        ?: throw RecoveryCapabilityUnavailable()
    return Crypto.decode(value).also { if (it.size != 32) { it.fill(0); throw RecoveryCapabilityUnavailable() } }
}

private fun Crypto.Record.atomicJson(): JsonObject = buildJsonObject {
    put("version", version); put("generation", generation); put("account_id", accountId); put("bundle_id", bundleId)
    put("credential_id", credentialId); put("salt", salt); put("package_sealed", packageSealed)
    put("prf_ark", prfArk); put("code_ark", codeArk); put("signature", signature)
}
private fun JsonObject.atomicRecord(): Crypto.Record {
    require(keys == setOf("version", "generation", "account_id", "bundle_id", "credential_id", "salt", "package_sealed", "prf_ark", "code_ark", "signature"))
    return Crypto.Record(AtomicWire.uuid(AtomicWire.text(this, "account_id", 36)), AtomicWire.uuid(AtomicWire.text(this, "bundle_id", 36)),
        AtomicWire.binary(AtomicWire.text(this, "credential_id", 2048)), AtomicWire.binary(AtomicWire.text(this, "salt", 128)),
        AtomicWire.binary(AtomicWire.text(this, "package_sealed", 45000)), AtomicWire.binary(AtomicWire.text(this, "prf_ark", 512)),
        AtomicWire.binary(AtomicWire.text(this, "code_ark", 512)), AtomicWire.binary(AtomicWire.text(this, "signature", 256)),
        AtomicWire.text(this, "version", 32), AtomicWire.integer(this, "generation"))
}
