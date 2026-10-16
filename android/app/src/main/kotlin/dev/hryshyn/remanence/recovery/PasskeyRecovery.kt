package dev.hryshyn.remanence.recovery

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import dev.hryshyn.remanence.AppContainer
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto as Crypto
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.data.network.LoginResponseDto
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val RecoveryJson = Json { ignoreUnknownKeys = false; isLenient = false }

interface RecoveryCredentialPort {
    suspend fun create(options: JsonObject): JsonObject
    suspend fun get(options: JsonObject): JsonObject
}

class AndroidRecoveryCredentials(private val activity: Activity) : RecoveryCredentialPort {
    private val manager = CredentialManager.create(activity)
    override suspend fun create(options: JsonObject): JsonObject {
        val result = manager.createCredential(activity, CreatePublicKeyCredentialRequest(options.toString())) as CreatePublicKeyCredentialResponse
        return RecoveryJson.decodeFromString(result.registrationResponseJson)
    }
    override suspend fun get(options: JsonObject): JsonObject {
        val result = manager.getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options.toString()))))
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
    private fun enabled() = check(BuildConfig.REMANENCE_RECOVERY_ENABLED)
    private fun verifyBody(flow: String, credential: JsonObject) = buildJsonObject {
        put("flow", flow); put("response", publicCredential(credential))
    }
    private fun prf(c: JsonObject): ByteArray {
        val value = c["clientExtensionResults"]?.jsonObject?.get("prf")?.jsonObject
            ?.get("results")?.jsonObject?.get("first")?.jsonPrimitive?.content
            ?: throw RecoveryCapabilityUnavailable()
        return Crypto.decode(value).also { if (it.size != 32) { it.fill(0); throw RecoveryCapabilityUnavailable() } }
    }

    /** Returns the code only after a verified complete upload. UI owns its short lifetime. */
    suspend fun protect(credentials: RecoveryCredentialPort, chooseAnotherManager: Boolean = false): ByteArray? {
        enabled()
        val lease = container.apiStack.captureSessionRequestLease() ?: error("no session")
        val account = withContext(Dispatchers.IO) { container.currentAccountStore.loadEntity() } ?: error("no account")
        container.ownerIdentityStores.claimLegacyIfMatching(account.userId, account.activeKeyBundleId)
        val identity = withContext(Dispatchers.IO) {
            container.ownerIdentityStores.resolve(account.userId, account.activeKeyBundleId)
        } as? IdentityBundleRepository.LoadResult.Available ?: error("identity missing")
        val status = api.requestAuthenticated(lease, "status")
        val stored = status.text("state") == "STORED"
        check(!chooseAnotherManager || !stored) { "existing recovery is immutable" }
        val id = if (status.text("state") == "NOT_STARTED" || chooseAnotherManager) {
            val start = api.requestAuthenticated(lease, "register/options", JsonObject(emptyMap()))
            val created = credentials.create(start.getValue("options").jsonObject)
            api.requestAuthenticated(lease, "register/verify", verifyBody(start.text("flow"), created)).text("credential_id")
        } else status.text("credential_id")
        val begin = api.requestAuthenticated(lease, "setup/options", buildJsonObject { put("credential_id", id) })
        val got = credentials.get(begin.getValue("options").jsonObject)
        val secret = prf(got)
        var prepared: Crypto.Prepared? = null
        try {
            if (got.text("id") != id) error("wrong credential")
            val verified = api.requestAuthenticated(lease, "setup/verify", verifyBody(begin.text("flow"), got))
            if (!container.apiStack.isSessionRequestLeaseLive(lease)) error("session changed")
            if (stored) {
                val readback = api.requestAuthenticated(lease, "record")
                withContext(Dispatchers.IO) { restore(readback, secret, false, account.userId, account.activeKeyBundleId) }
                check(container.apiStack.isSessionRequestLeaseLive(lease))
                container.recoveryReadiness.markVerified(account.userId, account.activeKeyBundleId)
                return null // Existing code is never reissued or replaced.
            }
            val pending = withContext(Dispatchers.IO) { Crypto.prepare(account.userId, account.activeKeyBundleId, id, secret, identity) }
            prepared = pending
            api.requestAuthenticated(lease, "record", buildJsonObject { put("flow", verified.text("flow")); put("record", pending.record.json()) })
            if (!container.apiStack.isSessionRequestLeaseLive(lease)) error("session changed")
            // Read back and unwrap the actual durable server record, not only our input.
            val readback = api.requestAuthenticated(lease, "record")
            withContext(Dispatchers.IO) { restore(readback, secret, false, account.userId, account.activeKeyBundleId) }
            check(container.apiStack.isSessionRequestLeaseLive(lease))
            container.recoveryReadiness.markVerified(account.userId, account.activeKeyBundleId)
            return pending.recoveryCode
        } catch (e: Throwable) { prepared?.recoveryCode?.fill(0); throw e }
        finally { secret.fill(0) }
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

    private fun restore(out: JsonObject, secret: ByteArray, code: Boolean, account: String, bundle: String) =
        Crypto.restore(out.getValue("record").jsonObject.record(), secret, code, account, bundle,
            Crypto.decode(out.text("encryption_public_keyset")), Crypto.decode(out.text("signing_public_keyset")))
}

class RecoveryCapabilityUnavailable : Exception("PRF unavailable")
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
