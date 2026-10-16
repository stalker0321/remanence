package dev.hryshyn.remanence.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.data.network.GoogleAuthRepository
import dev.hryshyn.remanence.core.data.network.GoogleAuthRejected
import dev.hryshyn.remanence.core.data.network.LoginResponseDto
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.wiring.PreparedIdentity
import dev.hryshyn.remanence.ui.auth.AuthMessage
import dev.hryshyn.remanence.ui.auth.isCredentialUserCancellation
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

fun interface GoogleCredentialPort {
    suspend fun token(clientId: String, nonce: String): String
}

class AndroidGoogleCredentials(private val activity: Activity) : GoogleCredentialPort {
    override suspend fun token(clientId: String, nonce: String): String {
        val option = GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build()
        val result = CredentialManager.create(activity).getCredential(activity,
            GetCredentialRequest(listOf(option))).credential as? CustomCredential
            ?: error("unexpected credential")
        check(result.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
        return GoogleIdTokenCredential.createFrom(result.data).idToken
    }
}

sealed interface GoogleAuthState {
    data object Idle : GoogleAuthState
    data object Working : GoogleAuthState
    data object ChooseHandle : GoogleAuthState
    data object SignedIn : GoogleAuthState
    data class Failed(val conflict: Boolean,
        val message: AuthMessage = if (conflict) AuthMessage.GOOGLE_CONFLICT else AuthMessage.GOOGLE_FAILED) : GoogleAuthState
}

/** Tickets live in this retained VM only; no ID token, ticket or password in saved state. */
class GoogleAuthViewModel(private val container: AppContainer) : ViewModel() {
    private val api = GoogleAuthRepository(container.apiBaseUrl)
    private val json = Json { ignoreUnknownKeys = false }
    private val mutableState = MutableStateFlow<GoogleAuthState>(GoogleAuthState.Idle)
    val state = mutableState.asStateFlow()
    private var ticket: JsonObject? = null
    private var replacementLease: Long? = null

    fun start(credentials: GoogleCredentialPort) {
        if (!BuildConfig.REMANENCE_GOOGLE_SIGN_IN_ENABLED || state.value == GoogleAuthState.Working) return
        ticket = null
        mutableState.value = GoogleAuthState.Working
        viewModelScope.launch {
            try {
                val lease = container.sessionReplacement.acquireLease()
                replacementLease = lease
                val options = api.request("options", JsonObject(emptyMap()))
                val token = credentials.token(options.text("client_id"), options.text("nonce"))
                val result = api.request("verify", buildJsonObject {
                    put("flow", options.getValue("flow")); put("nonce", options.getValue("nonce")); put("id_token", token)
                })
                when (result.text("status")) {
                    "NEEDS_HANDLE" -> { ticket = result; mutableState.value = GoogleAuthState.ChooseHandle }
                    "SIGNED_IN" -> commit(null, result, lease)
                    else -> error("invalid response")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.value = googleFailureState(e)
            }
        }
    }

    fun register(handle: String) {
        val pending = ticket ?: return
        val lease = replacementLease ?: return
        if (state.value == GoogleAuthState.Working) return
        mutableState.value = GoogleAuthState.Working
        viewModelScope.launch {
            try {
                // Flow identity is this ticket's server flow: retries on the
                // same ticket reuse its staged candidate, while a fresh
                // ticket (new start()) mints independent keys. Nothing is
                // retained across flows — staging on disk is the only
                // continuity, so logout/process death cannot leak candidates.
                val flowKey = "google:" + pending.getValue("flow").jsonPrimitive.content
                val identity = withContext(Dispatchers.IO) { container.registrationIdentityAdapter.prepareProvisional(flowKey) }
                val result = api.request("register", buildJsonObject {
                    put("flow", pending.getValue("flow")); put("registration_token", pending.getValue("registration_token"))
                    put("handle", handle)
                    put("key_bundle", buildJsonObject {
                        put("key_bundle_id", identity.keyBundleId); put("suite", PreparedIdentity.SUITE)
                        put("protocol_version", PreparedIdentity.PROTOCOL_VERSION)
                        put("encryption_public_keyset", identity.encryptionPublicKeysetB64Url)
                        put("signing_public_keyset", identity.signingPublicKeysetB64Url)
                    })
                })
                commit(identity, result, lease)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.value = googleFailureState(e) }
        }
    }

    val hasTicket: Boolean get() = ticket != null

    private suspend fun commit(
        identity: dev.hryshyn.remanence.wiring.PreparedIdentity?,
        result: JsonObject,
        lease: Long,
    ) {
        check(result.text("status") == "SIGNED_IN")
        val login = json.decodeFromJsonElement<LoginResponseDto>(result.getValue("login"))
        var committed = false
        try {
            // T186 receive intent: a SIGNED_IN summary with a null handle
            // records a receive-only account (deferred handle, no ChooseHandle
            // — there is nothing to choose yet). Bootstrap maps it to a
            // handleless Active state; first send prompts for the handle.
            container.sessionReplacement.replace(lease, UserId.parseRest(login.user.userId), login.accessToken, login.refreshToken) {
                container.currentAccountStore.record(login.user.userId, login.user.handle, login.activeKeyBundle.keyBundleId)
            }
            // T186 M7c: fenced session-id for R11 intents (owner-bound).
            runCatching {
                container.sessionIdHolder.set(UserId.parseRest(login.user.userId), login.sessionId)
            }
            // Adopt the matching staged candidate into the new owner's
            // archive, then claim a matching legacy home. Both best-effort:
            // staged material is retained either way and resolution fails
            // closed on mismatch instead of overwriting anything.
            // Registration carries its prepared identity; an existing-account
            // login binds any retained staged candidate by the assigned
            // bundle instead (lost-ACK path) — never fabricated keys.
            val assignedBundle = login.activeKeyBundle.keyBundleId
            if (identity != null && identity.keyBundleId == assignedBundle) {
                try {
                    container.registrationIdentityAdapter.bindStagedToOwner(identity.keyBundleId, login.user.userId)
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            } else if (identity == null) {
                // Existing-account login without prepared keys: bind a
                // retained staged candidate matching the assigned bundle
                // (interrupted registration recovered at login), if any.
                // Never generates or fabricates keys.
                try {
                    val staged = container.ownerIdentityStores.resolveStaged(assignedBundle)
                    if (staged != null) {
                        container.registrationIdentityAdapter.bindStagedToOwner(assignedBundle, login.user.userId)
                    }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
            try {
                container.ownerIdentityStores.claimLegacyIfMatching(login.user.userId, login.activeKeyBundle.keyBundleId)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            committed = true; ticket = null; mutableState.value = GoogleAuthState.SignedIn
        } finally {
            if (!committed) withContext(NonCancellable) { container.bareAuthRepository.logout(login.accessToken) }
        }
    }
}
/** User dismissals never look like network/server failure; provider text remains private. */
internal fun googleFailureState(failure: Exception): GoogleAuthState = when {
    isCredentialUserCancellation(failure) -> GoogleAuthState.Idle
    failure is IOException -> GoogleAuthState.Failed(false, AuthMessage.NETWORK)
    else -> GoogleAuthState.Failed(failure is GoogleAuthRejected && failure.status == 409)
}
private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
