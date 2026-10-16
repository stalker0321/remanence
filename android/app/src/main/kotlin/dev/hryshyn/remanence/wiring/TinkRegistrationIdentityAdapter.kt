package dev.hryshyn.remanence.wiring

import dev.hryshyn.remanence.core.crypto.KekBoundary

/** Stable, client-generated identity snapshot used for one registration flow. */
data class PreparedIdentity(
    val keyBundleId: String,
    val encryptionPublicKeysetB64Url: String,
    val signingPublicKeysetB64Url: String,
) {
    companion object {
        const val SUITE: String = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
        const val PROTOCOL_VERSION: Int = 1
    }
}

/**
 * Binds [RegistrationIdentityPort][dev.hryshyn.remanence.auth.RegistrationIdentityPort]
 * to owner-scoped staged identity material. The key bundle ID is derived
 * deterministically from the encryption public keyset so retries reuse the
 * same client-generated UUID without extra storage.
 *
 * Flow keys are caller-derived per registration flow (`google:<ticket-flow>`
 * for a Google ticket, `legacy:<handle>` for a legacy signup target):
 * stable across same-flow retries, independent across flows. The legacy
 * singleton path is gone — every creation is flow-scoped and durable
 * before any network, and nothing is ever deleted on failure.
 */
class TinkRegistrationIdentityAdapter(
    private val ownerStores: OwnerScopedIdentityStore,
    private val kekBoundary: KekBoundary,
    private val kekAlias: String,
) : dev.hryshyn.remanence.auth.RegistrationIdentityPort {

    override fun prepareProvisional(flowKey: String): PreparedIdentity {
        if (!kekBoundary.hasKey(kekAlias)) {
            kekBoundary.createAes256GcmKey(kekAlias)
        }
        return try {
            ownerStores.prepareProvisional(flowKey, kekAlias)
        } catch (_: Exception) {
            throw dev.hryshyn.remanence.auth.IdentityRecoveryRequiredException()
        }
    }

    override fun bindStagedToOwner(bundleId: String, ownerId: String): Boolean =
        ownerStores.adoptStaged(bundleId, ownerId)

    /**
     * Google `SIGNED_IN` binding for a verified login, shared by the
     * ViewModel's commit path (registration and existing-account login
     * alike). Adopts the staged candidate matching the server-assigned
     * bundle into the verified owner's archive — this flow's [prepared]
     * identity when it matches (registration), else a retained earlier
     * candidate recovered by exact bundle (existing-account lost-ACK
     * login) — then claims a matching legacy home. Never generates or
     * fabricates keys: a mismatch or absent candidate binds nothing and
     * resolution fails closed downstream.
     */
    fun bindVerifiedLogin(
        prepared: PreparedIdentity?,
        ownerId: String,
        assignedBundleId: String,
    ): Boolean {
        val bound = if (prepared != null && prepared.keyBundleId == assignedBundleId) {
            ownerStores.adoptStaged(prepared.keyBundleId, ownerId)
        } else if (prepared == null && ownerStores.resolveStaged(assignedBundleId) != null) {
            ownerStores.adoptStaged(assignedBundleId, ownerId)
        } else {
            false
        }
        ownerStores.claimLegacyIfMatching(ownerId, assignedBundleId)
        return bound
    }
}
