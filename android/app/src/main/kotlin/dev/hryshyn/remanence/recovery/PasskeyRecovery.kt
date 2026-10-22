package dev.hryshyn.remanence.recovery

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.NoCredentialException
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto as Crypto
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.data.db.LocalAccountEntity
import dev.hryshyn.remanence.core.data.network.LoginResponseDto
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val RecoveryJson = Json { ignoreUnknownKeys = false; isLenient = false }

interface RecoveryCredentialPort {
    suspend fun create(options: JsonObject): JsonObject
    suspend fun get(options: JsonObject): JsonObject
    /**
     * Immediate-only get variant for the single fresh-create verification
     * get. The default keeps source compatibility by delegating to normal
     * discovery; only the real Android adapter overrides it. Test fakes
     * override it to record the exact request mode.
     */
    suspend fun getImmediate(options: JsonObject): JsonObject = get(options)
}

/**
 * Real request builder seam (pinned credentials 1.6.0). Ordinary
 * discovery is the default; `immediate = true` sets
 * preferImmediatelyAvailableCredentials for the single fresh-create
 * verification get only. Never used for PENDING retries, STORED checks,
 * sign-in, or restore. No allow-list guessing here.
 */
internal fun getCredentialRequest(optionsJson: String, immediate: Boolean): GetCredentialRequest =
    GetCredentialRequest(
        listOf(GetPublicKeyCredentialOption(optionsJson)),
        preferImmediatelyAvailableCredentials = immediate,
    )

class AndroidRecoveryCredentials(private val activity: Activity) : RecoveryCredentialPort {
    private val manager = CredentialManager.create(activity)
    override suspend fun create(options: JsonObject): JsonObject {
        val result = manager.createCredential(activity, CreatePublicKeyCredentialRequest(options.toString())) as CreatePublicKeyCredentialResponse
        return RecoveryJson.decodeFromString(result.registrationResponseJson)
    }
    override suspend fun get(options: JsonObject): JsonObject {
        val result = manager.getCredential(activity, getCredentialRequest(options.toString(), immediate = false))
        return RecoveryJson.decodeFromString((result.credential as PublicKeyCredential).authenticationResponseJson)
    }
    override suspend fun getImmediate(options: JsonObject): JsonObject {
        val result = manager.getCredential(activity, getCredentialRequest(options.toString(), immediate = true))
        return RecoveryJson.decodeFromString((result.credential as PublicKeyCredential).authenticationResponseJson)
    }
}

/** The extension results stay client-side even when the provider adds fields. */
internal fun publicCredential(c: JsonObject): JsonObject = buildJsonObject {
    put("id", c.getValue("id")); put("rawId", c.getValue("rawId")); put("type", JsonPrimitive("public-key"))
    put("clientExtensionResults", JsonObject(emptyMap()))
    val response = c.getValue("response").jsonObject
    put("response", JsonObject(response.filterKeys { it in setOf("clientDataJSON", "attestationObject", "transports", "authenticatorData", "signature", "userHandle") }))
}

class PasskeyRecovery(private val container: AppContainer) {
    private val api = container.apiStack.recoveryRepository
    private val ceremony = ProtectCeremony()
    val hasUnconfirmedCreation: Boolean get() = ceremony.uncertain
    private fun enabled() = check(BuildConfig.REMANENCE_RECOVERY_ENABLED)

    /** Returns the code only after a verified complete upload. UI owns its short lifetime. */
    suspend fun protect(credentials: RecoveryCredentialPort, chooseAnotherManager: Boolean = false, verify: Boolean = false): ByteArray? {
        enabled()
        return protectWith(ProtectEnvironment(container), credentials, chooseAnotherManager, ceremony, verify)
    }

    suspend fun signIn(credentials: RecoveryCredentialPort) {
        enabled(); val lease = container.sessionReplacement.acquireLease()
        val begin = api.request("login/options", JsonObject(emptyMap()))
        val credential = credentials.get(begin.getValue("options").jsonObject)
        val secret = prf(credential) // No usable PRF -> no login session request/publication.
        var issuedLogin: LoginResponseDto? = null
        var committed = false
        try {
            val response = api.request("login/verify", verifyBody(begin.text("flow"), credential))
            val login = RecoveryJson.decodeFromJsonElement<LoginResponseDto>(response.getValue("login"))
            issuedLogin = login
            if (response.getValue("record").jsonObject.text("credential_id") != credential.text("id")) error("wrong credential")
            val keys = withContext(Dispatchers.IO) { restore(response, secret, false, login.user.userId, login.activeKeyBundle.keyBundleId) }
            withContext(Dispatchers.IO) {
                check(container.apiStack.sessionRefreshCoordinator.commitLocalRecovery(lease) {
                    if (!container.kekBoundary.hasKey(container.identityKekAlias)) container.kekBoundary.createAes256GcmKey(container.identityKekAlias)
                    container.ownerIdentityStores.installRecovered(login.user.userId, container.identityKekAlias, keys)
                })
            }
            container.sessionReplacement.replace(lease, UserId.parseRest(login.user.userId), login.accessToken, login.refreshToken) {
                container.currentAccountStore.record(login.user.userId, login.user.handle, login.activeKeyBundle.keyBundleId)
                container.recoveryReadiness.markVerified(login.user.userId, login.activeKeyBundle.keyBundleId)
            }
            // T186 M7c: fenced session-id for R11 intents (owner-bound).
            runCatching {
                container.sessionIdHolder.set(UserId.parseRest(login.user.userId), login.sessionId)
            }
            committed = true
        } finally {
            secret.fill(0)
            if (!committed) issuedLogin?.let { withContext(kotlinx.coroutines.NonCancellable) { container.bareAuthRepository.logout(it.accessToken) } }
        }
    }

    /** Signed-in account recovery when the passkey provider is unavailable. */
    suspend fun restoreWithCode(code: ByteArray) {
        enabled(); require(code.size == 32)
        val accountLease = container.sessionReplacement.acquireLease()
        val lease = container.apiStack.captureSessionRequestLease() ?: error("sign in first")
        val account = withContext(Dispatchers.IO) { container.currentAccountStore.loadEntity() } ?: error("no account")
        val response = api.requestAuthenticated(lease, "record")
        val keys = withContext(Dispatchers.IO) { restore(response, code, true, account.userId, account.activeKeyBundleId) }
        withContext(Dispatchers.IO) {
            check(container.apiStack.isSessionRequestLeaseLive(lease))
            check(container.apiStack.sessionRefreshCoordinator.commitLocalRecovery(accountLease) {
                if (!container.kekBoundary.hasKey(container.identityKekAlias)) container.kekBoundary.createAes256GcmKey(container.identityKekAlias)
                container.ownerIdentityStores.installRecovered(account.userId, container.identityKekAlias, keys)
            })
        }
        container.recoveryReadiness.markVerified(account.userId, account.activeKeyBundleId)
    }

    /** Restore ONLY the signed-in account, independently of its login provider. */
    suspend fun restoreSignedIn(credentials: RecoveryCredentialPort) {
        enabled()
        val replacement = container.sessionReplacement.acquireLease()
        val lease = container.apiStack.captureSessionRequestLease() ?: error("sign in first")
        val account = withContext(Dispatchers.IO) { container.currentAccountStore.loadEntity() } ?: error("no account")
        val record = api.requestAuthenticated(lease, "record")
        val id = record.getValue("record").jsonObject.text("credential_id")
        val begin = api.requestAuthenticated(lease, "setup/options", buildJsonObject { put("credential_id", id) })
        val credential = credentials.get(begin.getValue("options").jsonObject)
        val secret = prf(credential)
        try {
            check(credential.text("id") == id)
            api.requestAuthenticated(lease, "setup/verify", verifyBody(begin.text("flow"), credential))
            val keys = withContext(Dispatchers.IO) { restore(record, secret, false, account.userId, account.activeKeyBundleId) }
            withContext(Dispatchers.IO) {
                check(container.apiStack.isSessionRequestLeaseLive(lease))
                check(container.apiStack.sessionRefreshCoordinator.commitLocalRecovery(replacement) {
                    if (!container.kekBoundary.hasKey(container.identityKekAlias)) container.kekBoundary.createAes256GcmKey(container.identityKekAlias)
                    container.ownerIdentityStores.installRecovered(account.userId, container.identityKekAlias, keys)
                    container.recoveryReadiness.markVerified(account.userId, account.activeKeyBundleId)
                })
            }
        } finally { secret.fill(0) }
    }
}

class RecoveryCapabilityUnavailable : Exception("PRF unavailable")

/**
 * Fresh-created credential was saved and server-registered, but the
 * immediate verification get found nothing discoverable yet: the user
 * retries explicitly and the retry continues the SAME credential
 * (normal discovery, same status id). Never auto-retried, never a
 * readiness mark, never another manager, never a new key. The id is
 * exposed for tests/orchestration only — UI copy carries no internals.
 */
class RecoveryVerificationPending(val credentialId: String) :
    Exception("saved credential pending verification")

/**
 * The exact collaborators `protect` orchestration touches, as a small
 * explicit seam: production builds it from the container with zero
 * behavior change; JVM tests substitute fakes plus the REAL
 * owner-scoped stores (Crypto.prepare/readback stay real).
 */
internal class ProtectEnvironment(
    val captureLease: () -> SessionRequestLease?,
    val loadAccount: suspend () -> LocalAccountEntity?,
    val stores: OwnerScopedIdentityStore,
    val requestAuthenticated: suspend (lease: SessionRequestLease, path: String, body: JsonObject?) -> JsonObject,
    val isLeaseLive: (SessionRequestLease) -> Boolean,
    val markVerified: (owner: String, bundle: String) -> Unit,
) {
    internal constructor(container: AppContainer) : this(
        captureLease = { container.apiStack.captureSessionRequestLease() },
        loadAccount = { container.currentAccountStore.loadEntity() },
        stores = container.ownerIdentityStores,
        requestAuthenticated = { lease, path, body ->
            container.apiStack.recoveryRepository.requestAuthenticated(lease, path, body)
        },
        isLeaseLive = { lease -> container.apiStack.isSessionRequestLeaseLive(lease) },
        markVerified = { owner, bundle -> container.recoveryReadiness.markVerified(owner, bundle) },
    )
}

private fun verifyBody(flow: String, credential: JsonObject) = buildJsonObject {
    put("flow", flow); put("response", publicCredential(credential))
}

private fun prf(c: JsonObject): ByteArray {
    val value = c["clientExtensionResults"]?.jsonObject?.get("prf")?.jsonObject
        ?.get("results")?.jsonObject?.get("first")?.jsonPrimitive?.content
        ?: throw RecoveryCapabilityUnavailable()
    return Crypto.decode(value).also { if (it.size != 32) { it.fill(0); throw RecoveryCapabilityUnavailable() } }
}

private fun restore(out: JsonObject, secret: ByteArray, code: Boolean, account: String, bundle: String) =
    Crypto.restore(out.getValue("record").jsonObject.record(), secret, code, account, bundle,
        Crypto.decode(out.text("encryption_public_keyset")), Crypto.decode(out.text("signing_public_keyset")))

/** Public proof retained across acknowledgement loss; never contains PRF or private key material. */
internal class ProtectCeremony {
    var owner: String? = null
    var proof: JsonObject? = null
    var uncertain = false
}

internal class RecoveryCreationUncertain : IllegalStateException("creation confirmation incomplete")

internal suspend fun protectWith(
    env: ProtectEnvironment,
    credentials: RecoveryCredentialPort,
    chooseAnotherManager: Boolean = false,
    ceremony: ProtectCeremony = ProtectCeremony(),
    verify: Boolean = false,
): ByteArray? {
    val lease = env.captureLease() ?: error("no session")
    val account = withContext(Dispatchers.IO) { env.loadAccount() } ?: error("no account")
    val owner = account.userId + ":" + account.activeKeyBundleId
    if (ceremony.owner != owner) { ceremony.owner = owner; ceremony.proof = null; ceremony.uncertain = false }
    env.stores.claimLegacyIfMatching(account.userId, account.activeKeyBundleId)
    val identity = withContext(Dispatchers.IO) {
        env.stores.resolve(account.userId, account.activeKeyBundleId)
    } as? IdentityBundleRepository.LoadResult.Available ?: error("identity missing")
    val status = env.requestAuthenticated(lease, "status", null)
    val freshState = status.text("state")
    val stored = freshState == "STORED"
    check(!chooseAnotherManager) { "replacement enrollment requires a separate decision" }
    val id = if (freshState == "NOT_STARTED") {
        if (verify) throw RecoveryCreationUncertain()
        if (ceremony.proof == null) {
            if (ceremony.uncertain) throw RecoveryCreationUncertain()
            val start = env.requestAuthenticated(lease, "register/options", JsonObject(emptyMap()))
            ceremony.uncertain = true
            val created = try { credentials.create(start.getValue("options").jsonObject) }
            catch (failure: Exception) {
                if (dev.hryshyn.remanence.ui.auth.isCredentialUserCancellation(failure)) ceremony.uncertain = false
                throw failure
            }
            check(env.isLeaseLive(lease))
            ceremony.proof = verifyBody(start.text("flow"), created)
        }
        val proof = checkNotNull(ceremony.proof)
        val result = env.requestAuthenticated(lease, "register/verify", proof).text("credential_id")
        check(result == proof.getValue("response").jsonObject.text("id"))
        ceremony.proof = null; ceremony.uncertain = false
        throw RecoveryVerificationPending(result)
    } else status.text("credential_id").also { staged ->
        ceremony.proof?.getValue("response")?.jsonObject?.let { check(it.text("id") == staged) }
    }
    ceremony.proof = null; ceremony.uncertain = false
    if (!stored && !verify) throw RecoveryVerificationPending(id)
    val begin = env.requestAuthenticated(lease, "setup/options", buildJsonObject { put("credential_id", id) })
    val options = begin.getValue("options").jsonObject
    val allowed = options.getValue("allowCredentials").jsonArray
    check(allowed.size == 1 && allowed.single().jsonObject.text("id") == id)
    val got = credentials.get(options)
    val secret = prf(got)
    var prepared: Crypto.Prepared? = null
    try {
        if (got.text("id") != id) error("wrong credential")
        val verified = env.requestAuthenticated(lease, "setup/verify", verifyBody(begin.text("flow"), got))
        if (!env.isLeaseLive(lease)) error("session changed")
        if (stored) {
            val readback = env.requestAuthenticated(lease, "record", null)
            withContext(Dispatchers.IO) { restore(readback, secret, false, account.userId, account.activeKeyBundleId) }
            check(env.isLeaseLive(lease))
            env.markVerified(account.userId, account.activeKeyBundleId)
            return null // Existing code is never reissued or replaced.
        }
        val pending = withContext(Dispatchers.IO) { Crypto.prepare(account.userId, account.activeKeyBundleId, id, secret, identity) }
        prepared = pending
        env.requestAuthenticated(lease, "record", buildJsonObject { put("flow", verified.text("flow")); put("record", pending.record.json()) })
        if (!env.isLeaseLive(lease)) error("session changed")
        // Read back and unwrap the actual durable server record, not only our input.
        val readback = env.requestAuthenticated(lease, "record", null)
        withContext(Dispatchers.IO) { restore(readback, secret, false, account.userId, account.activeKeyBundleId) }
        check(env.isLeaseLive(lease))
        env.markVerified(account.userId, account.activeKeyBundleId)
        return pending.recoveryCode
    } catch (e: Throwable) { prepared?.recoveryCode?.fill(0); throw e }
    finally { secret.fill(0) }
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun Crypto.Record.json() = buildJsonObject {
    put("version", version); put("generation", generation); put("account_id", accountId); put("bundle_id", bundleId)
    put("credential_id", credentialId); put("salt", salt); put("package_sealed", packageSealed)
    put("prf_ark", prfArk); put("code_ark", codeArk); put("signature", signature)
}
private fun JsonObject.record(): Crypto.Record {
    require(keys == setOf("version", "generation", "account_id", "bundle_id", "credential_id", "salt", "package_sealed", "prf_ark", "code_ark", "signature"))
    return Crypto.Record(text("account_id"), text("bundle_id"), text("credential_id"), text("salt"), text("package_sealed"),
        text("prf_ark"), text("code_ark"), text("signature"), text("version"), getValue("generation").jsonPrimitive.int)
}
