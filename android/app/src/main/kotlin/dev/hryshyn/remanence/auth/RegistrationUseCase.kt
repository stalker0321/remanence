package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.wiring.PreparedIdentity
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.network.RegisterRequestDto
import dev.hryshyn.remanence.core.data.network.RegistrationUserDto
import dev.hryshyn.remanence.core.model.UserId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Port over the local identity bundle. Implementations must durably wrap the
 * private keysets before any network traffic happens (protocol.md section 5).
 */
fun interface RegistrationIdentityPort {
    /**
     * Returns the stable identity snapshot to register with, creating and
     * wrapping it on first use. Repeated calls reuse the same identity.
     */
    fun prepareIdentity(): PreparedIdentity
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

    sealed interface Outcome {
        data class Registered(
            val userId: String,
            val handle: String,
            val activeKeyBundleId: String,
        ) : Outcome

        /** Server answered with a redacted rejection (e.g. EMAIL_UNAVAILABLE). */
        data class Rejected(val httpStatus: Int) : Outcome

        data object NetworkUnreachable : Outcome

        data object InvalidResponse : Outcome

        /** Local identity exists but cannot be opened; no replacement is generated silently. */
        data object RecoveryRequired : Outcome
    }

    suspend fun register(email: String, password: String, handle: String): Outcome {
        val prepared = try {
            identity.prepareIdentity()
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
                Outcome.Registered(
                    userId = response.user.userId,
                    handle = response.user.handle,
                    activeKeyBundleId = response.activeKeyBundleId,
                )
            }
            is AuthResult.Failure -> when (result.reason) {
                dev.hryshyn.remanence.core.data.network.AuthFailure.HTTP -> Outcome.Rejected(result.httpStatus ?: 0)
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
