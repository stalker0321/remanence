package dev.hryshyn.remanence.wiring

import dev.hryshyn.remanence.core.crypto.KekBoundary

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
}
