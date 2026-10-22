package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto as Crypto
import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.recovery.RecoveryCredentialPort
import dev.hryshyn.remanence.recovery.RecoveryCapabilityUnavailable
import dev.hryshyn.remanence.recovery.publicCredential
import dev.hryshyn.remanence.ui.auth.credentialFailure
import dev.hryshyn.remanence.ui.auth.isCredentialUserCancellation
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

internal enum class AtomicPhase { IDLE, GOOGLE, HANDLE, CREATE_REQUIRED, CREATING, VERIFY_REQUIRED, VERIFYING,
    CREDENTIAL_UNKNOWN, SAVING, HANDLE_CONFLICT, ASSERTION_REQUIRED,
    UNKNOWN, REAUTHENTICATE, TERMINAL, RESTORE_REQUIRED, READY, EXISTING }
internal data class AtomicView(val phase: AtomicPhase = AtomicPhase.IDLE, val busy: Boolean = false,
    val error: String? = null, val sessionEstablished: Boolean = false, val handle: String = "")

/** No suspension inside local key installation; serialized with this controller's leave. */
internal class AtomicPublication(val isCurrent: () -> Boolean, val install: (() -> Unit) -> Unit)

/** Existing production collaborators, substituted only by Kotlin tests. */
internal class AtomicEnvironment(
    val api: AtomicRegistrationPort,
    val capture: () -> Long,
    val isCurrent: (Long) -> Boolean,
    val read: suspend (token: String, path: String) -> JsonObject,
    val publish: suspend (Long, LoginResponseDto, Crypto.Restored?, AtomicPublication) -> Unit,
    val isPublished: (LoginResponseDto) -> Boolean,
    val revoke: suspend (token: String) -> Unit,
    val generate: () -> AccountIdentityGenerator.AccountIdentity = { AccountIdentityGenerator().generate() },
    val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
)

/** One UI owner, RAM-only candidate, explicit retry. No saved-state private identity. */
internal class AtomicRegistrationController(private val env: AtomicEnvironment) {
    private class Owner(val lease: Long) {
        var setup: AtomicSetup? = null
        var credential: String? = null
        // Public attestation only. Retained until the server acknowledges this exact creation.
        var creation: JsonObject? = null
        var creationUncertain = false
        var candidate: AtomicCandidate? = null
        var grant: String? = null
        var intent: JsonObject? = null
        var job: Job? = null
        val buffers = mutableSetOf<ByteArray>()
        fun wipe() {
            buffers.forEach { it.fill(0) }; buffers.clear(); candidate?.wipe()
            candidate = null; intent = null; grant = null; creation = null; creationUncertain = false
        }
    }
    private val ownerFence = Any()
    @Volatile private var owner: Owner? = null
    private val mutable = MutableStateFlow(AtomicView())
    val state = mutable.asStateFlow()

    suspend fun begin(google: GoogleCredentialPort) {
        if (state.value.busy) return
        owner?.wipe()
        val next = Owner(env.capture()); owner = next
        mutable.value = AtomicView()
        run(next) {
            show(AtomicPhase.GOOGLE)
            val options = env.api.request(AtomicAction.GOOGLE_OPTIONS, null, null, JsonObject(emptyMap()))
            checkOwner(next)
            val flow = AtomicWire.uuid(AtomicWire.text(options, "flow", 36))
            val nonce = AtomicWire.binary(AtomicWire.text(options, "nonce", 43), 43)
            require(nonce.length == 43)
            val token = google.token(AtomicWire.text(options, "client_id", 200), nonce)
            checkOwner(next)
            val reply = env.api.request(AtomicAction.GOOGLE_VERIFY, null, null, buildJsonObject {
                put("flow", flow); put("nonce", nonce); put("id_token", token); put("onboarding_version", 1)
            })
            checkOwner(next)
            if (AtomicWire.text(reply, "status", 32) == "SIGNED_IN") {
                val login = AtomicWire.login(reply.getValue("login").jsonObject)
                withIssuedLogin(login) { publish(next, login, null, AtomicPhase.EXISTING) }
            } else {
                next.setup = AtomicWire.setup(reply)
                next.credential = next.setup!!.reservation.credentialIds.firstOrNull()
                displayReservation(next)
            }
        }
    }

    fun chooseHandle(rawHandle: String) {
        val current = owner ?: return
        if (state.value.busy || state.value.phase !in setOf(AtomicPhase.HANDLE, AtomicPhase.HANDLE_CONFLICT)) return
        val handle = try { NormalizedHandle.parse(rawHandle).value }
        catch (_: IllegalArgumentException) { show(state.value.phase, "HANDLE_INVALID"); return }
        mutable.value = state.value.copy(handle = handle, error = null)
        show(if (current.credential != null || current.creation != null) AtomicPhase.VERIFY_REQUIRED else AtomicPhase.CREATE_REQUIRED)
    }

    /** Back before creation is ordinary editing; after creation it must never return to Create. */
    fun backToHandle(): Boolean {
        if (state.value.busy || state.value.phase != AtomicPhase.CREATE_REQUIRED) return false
        show(AtomicPhase.HANDLE)
        return true
    }

    /** One provider CREATE only. A successful return always stops at an explicit Verify action. */
    suspend fun save(rawHandle: String, credentials: RecoveryCredentialPort) {
        if (state.value.phase == AtomicPhase.HANDLE) chooseHandle(rawHandle)
        val current = owner ?: return
        if (state.value.phase != AtomicPhase.CREATE_REQUIRED) return
        run(current) {
            requireReserved(current); show(AtomicPhase.CREATING)
            reconcileCredential(current)
            if (current.credential == null) {
                check(!current.creationUncertain && current.creation == null)
                val options = post(current, AtomicAction.CREDENTIAL_OPTIONS, epoch(current))
                val publicOptions = options.getValue("options").jsonObject
                val id = UUID.fromString(current.setup!!.reservation.accountId)
                val expected = Crypto.encode(ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array())
                require(AtomicWire.text(publicOptions.getValue("user").jsonObject, "id", 24) == expected)
                current.creationUncertain = true
                val created = try { credentials.create(publicOptions) }
                catch (failure: Exception) {
                    if (isCredentialUserCancellation(failure)) current.creationUncertain = false
                    throw failure
                }
                checkOwner(current)
                current.creation = verifyBody(current, options, checkedCredential(created))
                acknowledgeCreation(current)
            }
            show(AtomicPhase.VERIFY_REQUIRED)
        }
    }

    /** User initiated normal GET, bound to the saved credential. Never creates a credential. */
    suspend fun verify(rawHandle: String, credentials: RecoveryCredentialPort) {
        val current = owner ?: return
        if (state.value.phase !in setOf(AtomicPhase.VERIFY_REQUIRED, AtomicPhase.HANDLE_CONFLICT, AtomicPhase.ASSERTION_REQUIRED)) return
        run(current) {
            val handle = try { NormalizedHandle.parse(rawHandle).value }
            catch (_: IllegalArgumentException) { throw AtomicRegistrationRejected(0, "HANDLE_INVALID") }
            requireReserved(current); show(AtomicPhase.VERIFYING)
            if (current.grant == null) authorize(current, credentials)
            checkOwner(current)
            val candidate = checkNotNull(current.candidate)
            current.intent = buildJsonObject {
                put("epoch", current.setup!!.reservation.epoch); put("operation_id", UUID.randomUUID().toString())
                put("final_authorization", checkNotNull(current.grant)); put("handle", handle)
                put("key_bundle", candidate.bundle); put("record", candidate.record)
            }
            finalize(current)
        }
    }

    suspend fun retry() {
        val current = owner ?: return
        if (state.value.phase == AtomicPhase.UNKNOWN && current.intent != null) run(current) { finalize(current) }
    }
    suspend fun resolve() {
        val current = owner ?: return
        if (current.setup == null) return
        run(current) {
            val setup = current.setup!!
            val reply = env.api.request(AtomicAction.STATUS, setup.reservation.id, setup.token, null)
            checkOwner(current)
            updateReservation(current, AtomicWire.reservation(reply.getValue("reservation").jsonObject))
            reconcileStagedId(current)
            if (current.setup!!.reservation.state == AtomicReservationState.RESERVED && current.intent != null) show(AtomicPhase.UNKNOWN)
            else if (current.setup!!.reservation.state == AtomicReservationState.RESERVED && current.creation != null) {
                if (current.credential == null) acknowledgeCreation(current)
                show(AtomicPhase.VERIFY_REQUIRED)
            }
            else displayReservation(current)
        }
    }
    suspend fun cancelReservation() {
        val current = owner ?: return
        if (current.setup == null) return
        run(current) {
            val reply = post(current, AtomicAction.CANCEL, epoch(current))
            val next = AtomicWire.reservation(reply.getValue("reservation").jsonObject)
            require(next.state == AtomicReservationState.CANCELLED)
            updateReservation(current, next); current.wipe(); show(AtomicPhase.REAUTHENTICATE)
        }
    }
    suspend fun restart() {
        val current = owner ?: return
        if (state.value.phase != AtomicPhase.TERMINAL) return
        run(current) {
            val old = current.setup!!.reservation
            val reply = post(current, AtomicAction.RESTART, epoch(current))
            if (AtomicWire.text(reply, "status", 32) == "RESTARTED") {
                current.wipe(); show(AtomicPhase.REAUTHENTICATE)
            } else {
                val setup = AtomicWire.setup(reply)
                require(setup.reservation.id != old.id && setup.reservation.accountId != old.accountId)
                require(setup.reservation.state == AtomicReservationState.RESERVED && setup.reservation.credentialIds.isEmpty())
                current.wipe(); current.setup = setup; current.credential = null
                mutable.value = AtomicView(AtomicPhase.HANDLE, busy = true)
            }
        }
    }

    /** Synchronous local fence before UI navigation. Caller may best-effort revoke captured setup. */
    fun leave(): AtomicSetup? = synchronized(ownerFence) {
        val previous = owner; owner = null
        previous?.job?.cancel(); previous?.wipe()
        mutable.value = AtomicView()
        previous?.setup.also { previous?.setup = null; previous?.credential = null }
    }

    private suspend fun reconcileCredential(current: Owner) {
        val setup = current.setup!!
        val status = env.api.request(AtomicAction.STATUS, setup.reservation.id, setup.token, null)
        checkOwner(current)
        updateReservation(current, AtomicWire.reservation(status.getValue("reservation").jsonObject))
        requireReserved(current) // Reconcile lost credential verify ACK BEFORE another create.
        reconcileStagedId(current)
    }

    private fun reconcileStagedId(current: Owner) {
        if (current.credential != null) return
        val ids = current.setup!!.reservation.credentialIds
        val createdId = current.creation?.getValue("response")?.jsonObject?.let { AtomicWire.text(it, "id", 2048) }
        current.credential = if (createdId != null) ids.firstOrNull { it == createdId }
            else if (!current.creationUncertain) ids.firstOrNull() else null
    }

    private suspend fun acknowledgeCreation(current: Owner) {
        val body = checkNotNull(current.creation)
        val verified = post(current, AtomicAction.CREDENTIAL_VERIFY, body)
        AtomicWire.flag(verified, "registered", true); AtomicWire.flag(verified, "recoverable", false)
        val credentialId = AtomicWire.binary(AtomicWire.text(verified, "credential_id", 2048))
        require(credentialId == AtomicWire.text(body.getValue("response").jsonObject, "id", 2048))
        current.credential = credentialId; current.creation = null; current.creationUncertain = false
    }

    private suspend fun authorize(current: Owner, credentials: RecoveryCredentialPort) {
        reconcileCredential(current)
        checkNotNull(current.credential)
        val options = post(current, AtomicAction.SETUP_OPTIONS, buildJsonObject {
            put("epoch", current.setup!!.reservation.epoch); put("credential_id", current.credential)
        })
        val publicOptions = options.getValue("options").jsonObject
        val allowed = publicOptions.getValue("allowCredentials").jsonArray
        require(allowed.size == 1 && AtomicWire.text(allowed.single().jsonObject, "id", 2048) == current.credential)
        val got = credentials.get(publicOptions)
        // Own the PRF before checking a possibly retired callback, so finally always wipes it.
        val secret = atomicPrf(got); current.buffers.add(secret)
        var retained = false
        try {
            checkOwner(current)
            val response = checkedCredential(got)
            require(AtomicWire.text(response, "id", 2048) == current.credential)
            val verified = post(current, AtomicAction.SETUP_VERIFY, verifyBody(current, options, response))
            AtomicWire.flag(verified, "authentication_verified", true)
            require(AtomicWire.text(verified, "credential_id", 2048) == current.credential)
            val grant = AtomicWire.capability(AtomicWire.text(verified, "final_authorization", 47), "rgf_")
            val existing = current.candidate
            if (existing != null) {
                withContext(env.cryptoDispatcher) { unwrapAtomic(existing, existing.localReadback(), secret) }
                checkOwner(current); existing.prf.fill(0); current.buffers.remove(existing.prf); existing.prf = secret
            } else {
                val candidate = withContext(env.cryptoDispatcher) {
                    prepareAtomicCandidate(current.setup!!.reservation.accountId, current.credential!!, secret, env.generate)
                }
                checkOwner(current); current.candidate = candidate
            }
            current.grant = grant; retained = true
        } finally { if (!retained) { secret.fill(0); current.buffers.remove(secret) } }
    }

    private suspend fun finalize(current: Owner) {
        val intent = checkNotNull(current.intent)
        show(AtomicPhase.UNKNOWN) // Dispatch may commit even when this call is cancelled/lost.
        val reply = try { post(current, AtomicAction.FINALIZE, intent) }
        catch (failure: AtomicRegistrationRejected) {
            checkOwner(current)
            if (failure.reason == "HANDLE_TAKEN") { show(AtomicPhase.HANDLE_CONFLICT, failure.reason); return }
            if (failure.reason == "SETUP_REJECTED" && failure.status == 400) {
                current.grant = null; show(AtomicPhase.ASSERTION_REQUIRED, failure.reason); return
            }
            throw failure
        }
        val result = reply.getValue("outcome").jsonObject
        val candidate = checkNotNull(current.candidate)
        require(AtomicWire.text(reply, "status", 16) == "ACTIVE")
        require(result["operation_id"] == intent["operation_id"] && result["handle"] == intent["handle"])
        require(result["account_id"] == candidate.record["account_id"] && result["bundle_id"] == candidate.record["bundle_id"])
        require(result["credential_id"] == candidate.record["credential_id"] && AtomicWire.integer(result, "generation") == 1)
        if (reply["reauthenticate"] == JsonPrimitive(true)) { current.wipe(); show(AtomicPhase.RESTORE_REQUIRED); return }
        val login = AtomicWire.login(reply.getValue("login").jsonObject)
        withIssuedLogin(login) {
            require(login.user.userId == AtomicWire.text(result, "account_id", 36))
            require(login.activeKeyBundle.keyBundleId == AtomicWire.text(result, "bundle_id", 36) && login.user.handle == AtomicWire.text(result, "handle", 32))
            show(AtomicPhase.RESTORE_REQUIRED)
            val restored = try {
                val status = env.read(login.accessToken, "status"); checkOwner(current)
                require(AtomicWire.text(status, "state", 16) == "STORED" && AtomicWire.text(status, "credential_id", 2048) == current.credential)
                val read = env.read(login.accessToken, "record"); checkOwner(current)
                withContext(env.cryptoDispatcher) { unwrapAtomic(candidate, read) }.also { checkOwner(current) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                checkOwner(current)
                publish(current, login, null, AtomicPhase.RESTORE_REQUIRED)
                return@withIssuedLogin
            }
            publish(current, login, restored, AtomicPhase.READY)
        }
    }

    /** Own a received ordinary login across readback AND publication. Setup LEAVE
     * cannot revoke it. Never look up a replacement owner's token for cleanup. */
    private suspend fun withIssuedLogin(login: LoginResponseDto, action: suspend () -> Unit) {
        try { action() }
        finally {
            if (!env.isPublished(login)) withContext(NonCancellable) {
                withTimeoutOrNull(5000) { runCatching { env.revoke(login.accessToken) } }
            }
        }
    }

    private suspend fun publish(current: Owner, login: LoginResponseDto, restored: Crypto.Restored?, phase: AtomicPhase) {
        checkOwner(current)
        val admit = { owner === current && env.isCurrent(current.lease) }
        env.publish(current.lease, login, restored, AtomicPublication(admit) { install ->
            synchronized(ownerFence) { check(admit()) { "retired local key owner" }; install() }
        })
        check(owner === current && env.isPublished(login)) { "retired account publication" }
        current.wipe(); owner = null
        mutable.value = AtomicView(phase, sessionEstablished = true)
    }
    private suspend fun post(current: Owner, action: AtomicAction, body: JsonObject): JsonObject {
        checkOwner(current)
        val setup = checkNotNull(current.setup)
        val reply = env.api.request(action, setup.reservation.id, setup.token, body)
        checkOwner(current); return reply
    }
    private fun updateReservation(current: Owner, next: AtomicReservation) {
        val setup = current.setup!!; val old = setup.reservation
        if (old.id != next.id || old.accountId != next.accountId || old.epoch != next.epoch) throw AtomicRegistrationRejected(409, "STALE_SETUP")
        current.setup = AtomicSetup(next, setup.token)
    }
    private fun displayReservation(current: Owner) = when (current.setup!!.reservation.state) {
        AtomicReservationState.RESERVED -> show(when {
            current.credential != null && state.value.handle.isNotEmpty() -> AtomicPhase.VERIFY_REQUIRED
            current.credential != null -> AtomicPhase.HANDLE
            current.creationUncertain -> AtomicPhase.CREDENTIAL_UNKNOWN
            else -> AtomicPhase.HANDLE
        })
        AtomicReservationState.ACTIVE -> { current.wipe(); show(AtomicPhase.RESTORE_REQUIRED) }
        else -> { current.wipe(); show(AtomicPhase.TERMINAL) }
    }
    private suspend fun requireReserved(current: Owner) {
        checkOwner(current)
        when (current.setup!!.reservation.state) {
            AtomicReservationState.RESERVED -> Unit
            AtomicReservationState.ACTIVE -> throw AtomicRegistrationRejected(409, "ALREADY_ACTIVE")
            else -> throw AtomicRegistrationRejected(410, "RESERVATION_TERMINAL")
        }
    }
    private suspend fun checkOwner(current: Owner) {
        currentCoroutineContext().ensureActive()
        check(owner === current && env.isCurrent(current.lease)) { "retired atomic owner" }
    }
    private suspend fun run(current: Owner, action: suspend () -> Unit) {
        if (state.value.busy || owner !== current) return
        mutable.value = state.value.copy(busy = true, error = null)
        current.job = currentCoroutineContext()[Job]
        try { checkOwner(current); action() }
        catch (cancelled: CancellationException) { if (owner === current) leave(); throw cancelled }
        catch (failure: Exception) {
            if (owner !== current) return
            if (!env.isCurrent(current.lease)) { leave(); return }
            val reason = when {
                failure is AtomicRegistrationRejected -> failure.reason
                failure is RecoveryCapabilityUnavailable -> "PRF_UNAVAILABLE"
                else -> credentialFailure(failure)?.reason ?: "SETUP_REJECTED"
            }
            when (reason) {
                "SETUP_AUTH_INVALID", "SETUP_REAUTH_REQUIRED", "STALE_SETUP", "RESERVATION_TERMINAL" -> { current.wipe(); show(AtomicPhase.REAUTHENTICATE, reason) }
                "ALREADY_ACTIVE" -> { current.wipe(); show(AtomicPhase.RESTORE_REQUIRED, reason) }
                else -> show(when (state.value.phase) {
                    AtomicPhase.CREATING -> if (current.creationUncertain) AtomicPhase.CREDENTIAL_UNKNOWN else AtomicPhase.CREATE_REQUIRED
                    AtomicPhase.VERIFYING -> AtomicPhase.VERIFY_REQUIRED
                    AtomicPhase.SAVING -> AtomicPhase.HANDLE
                    AtomicPhase.GOOGLE -> AtomicPhase.IDLE
                    else -> state.value.phase
                }, reason)
            }
        } finally {
            if (owner === current) { current.job = null; mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun show(phase: AtomicPhase, error: String? = null) { mutable.value = state.value.copy(phase = phase, error = error) }
    private fun epoch(current: Owner) = buildJsonObject { put("epoch", current.setup!!.reservation.epoch) }
    private fun verifyBody(current: Owner, options: JsonObject, credential: JsonObject) = buildJsonObject {
        put("epoch", current.setup!!.reservation.epoch); put("flow", AtomicWire.uuid(AtomicWire.text(options, "flow", 36))); put("response", credential)
    }
    private fun checkedCredential(value: JsonObject): JsonObject {
        val id = AtomicWire.binary(AtomicWire.text(value, "id", 2048), 2048)
        require(AtomicWire.text(value, "rawId", 2048) == id && AtomicWire.text(value, "type", 16) == "public-key")
        return publicCredential(value) // Existing explicit public whitelist; extensions stripped.
    }
}
