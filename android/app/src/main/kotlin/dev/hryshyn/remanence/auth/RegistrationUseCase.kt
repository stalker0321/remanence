package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.wiring.PreparedIdentity
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.RegisterRequestDto
import dev.hryshyn.remanence.core.data.network.RegistrationProblemCode
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Port over the local identity bundle. Implementations must durably wrap the
 * private keysets before any network traffic happens (protocol.md section 5).
 * All creation is flow-scoped: the same flow/target retry reuses its staged
 * candidate, while a new flow or target mints independent keys. There is no
 * global prepare path — a caller without a flow key cannot exist.
 */
interface RegistrationIdentityPort {
    /**
     * Returns the stable identity snapshot for this flow, creating and
     * wrapping a FRESH staged candidate on first use for the key. Throws
     * [IdentityRecoveryRequiredException] when the flow's staged material
     * exists but is unusable.
     */
    fun prepareProvisional(flowKey: String): PreparedIdentity

    /**
     * Idempotent verified move of a staged candidate into its owner's
     * archive. Succeeds trivially when already adopted; refuses when the
     * owner dir already holds different usable keys. Never throws on
     * integrity problems (returns false); staged material is retained.
     */
    fun bindStagedToOwner(bundleId: String, ownerId: String): Boolean
}

/** Port over the server registration endpoint. */
interface RegistrationAuthApiPort {
    suspend fun register(request: RegisterRequestDto): AuthResult<dev.hryshyn.remanence.core.data.network.RegisterResponseDto>
}

/** Port persisting the current account after a successful registration. */
interface CurrentAccountPort {
    suspend fun recordCurrentAccount(user: RegistrationUserDto, activeKeyBundleId: String)
}

/**
 * Orchestrates registration with the strict ordering required by
 * protocol.md: wrap local identity first, then call the network, and only
 * after a confirmed 201 record the account locally. A failed registration
 * leaves only orphan wrapped key material and never writes account state.
 */
class RegistrationUseCase(
    private val identity: RegistrationIdentityPort,
    private val authApi: RegistrationAuthApiPort,
    private val accounts: CurrentAccountPort,
    private val sessionReplacement: SessionReplacementPort,
) {

    /**
     * Logical-flow epoch. The flow key is the signup TARGET scoped to
     * this epoch: same-flow retries (e.g. a 409 with an edited handle)
     * reuse the staged candidate, while a NEW logical flow — next
     * screen entry after success/logout, or any [startNewFlow] call —
     * mints independent keys even for the same target. The epoch lives
     * only in memory: process death starts a new epoch, and the
     * previous staged candidate is retained for lost-ACK login binding
     * by exact bundle, never silently reused.
     */
    @Volatile
    private var flowEpoch: String = java.util.UUID.randomUUID().toString()

    /**
     * Starts a new logical registration flow. Called when the
     * registration screen opens (a fresh ViewModel); retries within the
     * open screen keep the epoch. Never deletes staged material.
     */
    fun startNewFlow() {
        flowEpoch = java.util.UUID.randomUUID().toString()
    }

    sealed interface Outcome {
        data class Registered(
            val userId: String,
            /** Null for receive-only accounts (deferred handle). */
            val handle: String?,
            val activeKeyBundleId: String,
        ) : Outcome

        /** Server answered with a redacted rejection (e.g. EMAIL_UNAVAILABLE). */
        data class Rejected(
            val httpStatus: Int,
            val problemCode: RegistrationProblemCode? = null,
        ) : Outcome

        data object NetworkUnreachable : Outcome

        data object InvalidResponse : Outcome

        /** Local identity exists but cannot be opened; no replacement is generated silently. */
        data object RecoveryRequired : Outcome
    }

    suspend fun register(email: String, password: String, handle: String): Outcome {
        // Flow identity is the signup TARGET scoped to the current
        // logical-flow epoch (see [startNewFlow]): a 409 retry with an
        // edited handle reuses the staged candidate, while a different
        // email (independent new signup flow, even with the same handle)
        // or a new epoch mints independent keys. No in-memory retention
        // across flows — the staged directory is the only continuity, so
        // logout/process death cannot leak candidates into unrelated
        // registrations.
        val flowKey = "legacy:" + flowEpoch + ":" + email.trim().lowercase()
        val prepared = try {
            identity.prepareProvisional(flowKey)
        } catch (_: IdentityRecoveryRequiredException) {
            return Outcome.RecoveryRequired
        }
        val request = RegisterRequestDto(
            email = email,
            password = password,
            handle = handle,
            keyBundle = dev.hryshyn.remanence.core.data.network.RegisterKeyBundleDto(
                keyBundleId = prepared.keyBundleId,
                suite = PreparedIdentity.SUITE,
                protocolVersion = PreparedIdentity.PROTOCOL_VERSION,
                encryptionPublicKeyset = prepared.encryptionPublicKeysetB64Url,
                signingPublicKeyset = prepared.signingPublicKeysetB64Url,
            ),
        )
        val lease = sessionReplacement.acquireLease()
        return when (val result = authApi.register(request)) {
            is AuthResult.Success -> {
                val response = result.value
                if (!commitReplacement(
                        lease = lease,
                        user = response.user,
                        activeKeyBundleId = response.activeKeyBundleId,
                        accessToken = response.accessToken,
                        refreshToken = response.refreshToken,
                    )
                ) {
                    return Outcome.InvalidResponse
                }
                // Adopt the matching staged candidate into the new owner's
                // archive. Best-effort by design: the staged copy is retained
                // either way for later recovery, and a mismatch or conflict
                // fails closed downstream instead of overwriting anything.
                if (prepared.keyBundleId == response.activeKeyBundleId) {
                    try {
                        identity.bindStagedToOwner(prepared.keyBundleId, response.user.userId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                }
                Outcome.Registered(
                    userId = response.user.userId,
                    handle = response.user.handle,
                    activeKeyBundleId = response.activeKeyBundleId,
                )
            }
            is AuthResult.Failure -> when (result.reason) {
                dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP -> Outcome.Rejected(
                    httpStatus = result.httpStatus ?: 0,
                    problemCode = result.registrationProblemCode,
                )
                dev.hryshyn.remanence.core.data.network.AuthFailure.NETWORK -> Outcome.NetworkUnreachable
                dev.hryshyn.remanence.core.data.network.AuthFailure.INVALID_RESPONSE -> Outcome.InvalidResponse
            }
        }
    }

    private suspend fun commitReplacement(
        lease: Long,
        user: RegistrationUserDto,
        activeKeyBundleId: String,
        accessToken: String,
        refreshToken: String,
    ): Boolean {
        val owner = try {
            UserId.parseRest(user.userId)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            sessionReplacement.replace(lease, owner, accessToken, refreshToken) {
                accounts.recordCurrentAccount(user, activeKeyBundleId)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}

/** Thrown by identity adapters when existing material cannot be unwrapped. */
class IdentityRecoveryRequiredException : Exception("local identity requires explicit recovery")
