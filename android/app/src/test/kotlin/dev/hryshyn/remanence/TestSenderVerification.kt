package dev.hryshyn.remanence

import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.index.SenderIndexBundleSenderVerification
import java.util.UUID

/** Test-only public Ed25519 material used to satisfy the production handoff contract. */
object TestSenderVerification {
    private val sender = UserId(UUID.fromString("0198f0a0-0000-7000-8000-00000000e101"))
    private val bundle = KeyBundleId(UUID.fromString("0198f0a0-0000-7000-8000-00000000e102"))
    private val publicKeyset by lazy {
        TinkPrimitives.ensureRegistered()
        val identity = AccountIdentityGenerator().generate()
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.signingPublicKeyset)
    }

    fun forCapsule(
        capsuleId: CapsuleId,
        wipeBytes: (ByteArray) -> Unit = { it.fill(0) },
    ): SenderIndexBundleSenderVerification =
        SenderIndexBundleSenderVerification.fromTrusted(sender, bundle, publicKeyset, wipeBytes)
}
