package dev.hryshyn.remanence.auth

import dev.hryshyn.remanence.core.crypto.AccountIdentityGenerator
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto as Crypto
import dev.hryshyn.remanence.core.data.network.*
import dev.hryshyn.remanence.recovery.RecoveryCredentialPort
import dev.hryshyn.remanence.wiring.PreparedIdentity
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/** Real controller + ADR011 preparation/unwrap, synthetic API/provider ports. NOT native/API acceptance. */
class AtomicRegistrationControllerTest {
    @Test fun normalCommitReadsPermanentBytesAndOnlyThenPublishesReadbackKeys(): Unit = runBlocking {
        val f = Fixture(); val c = f.controller()
        c.begin(f.google); assertEquals(0, f.generations); assertNull(f.published)
        c.save("@My.Name", f)
        assertEquals(AtomicPhase.READY, c.state.value.phase)
        assertEquals(1, f.generations); assertEquals(1, f.creates); assertEquals(1, f.immediateGets)
        assertEquals(listOf("status", "record", "publish-keys"), f.reads)
        val keys = checkNotNull(f.installed)
        assertEquals(f.saved!!.getValue("key_bundle").jsonObject["encryption_public_keyset"], JsonPrimitive(Crypto.encode(Crypto.public(keys.encryption))))
        assertEquals("my.name", f.published!!.user.handle)
        assertFalse(f.requests.any { it.second?.toString()?.contains("providerPrivate") == true })
        assertFalse(f.requests.any { it.second?.toString()?.contains(Crypto.encode(f.prf)) == true })
    }

    @Test fun missingPrfOrRejectedAssertionCannotGenerateOrFinalize(): Unit = runBlocking {
        for (mode in listOf("no-prf", "rejected-proof")) {
            val f = Fixture(); f.providerMode = mode; val c = f.controller()
            c.begin(f.google); c.save("valid", f)
            assertEquals(0, f.generations); assertNull(f.saved); assertNull(f.installed)
            assertFalse(c.state.value.sessionEstablished)
        }
    }

    @Test fun missingImmediateCredentialRetriesSameIdWithOrdinaryDiscovery(): Unit = runBlocking {
        val f = Fixture(); f.providerMode = "pending"; val c = f.controller()
        c.begin(f.google); c.save("valid", f)
        assertEquals("CREDENTIAL_PENDING", c.state.value.error); assertEquals(0, f.generations)
        f.providerMode = "ok"; c.save("valid", f)
        assertEquals(1, f.creates); assertEquals(1, f.immediateGets); assertEquals(1, f.discoveryGets)
        assertEquals(AtomicPhase.READY, c.state.value.phase)
    }

    @Test fun lostCredentialVerificationAckReconcilesStatusBeforeAnotherCreate(): Unit = runBlocking {
        val f = Fixture(); f.loseCredentialAck = true; val c = f.controller()
        c.begin(f.google); c.save("valid", f)
        assertEquals(0, f.generations)
        c.save("valid", f)
        assertEquals(1, f.creates); assertEquals(1, f.discoveryGets)
        assertEquals(AtomicPhase.READY, c.state.value.phase)
    }

    @Test fun handleConflictRetainsEveryCandidateByteAndLiveGrant(): Unit = runBlocking {
        val f = Fixture(); f.finalMode = "handle"; val c = f.controller()
        c.begin(f.google); c.save("taken", f)
        val first = f.finals.single()
        assertEquals(AtomicPhase.HANDLE_CONFLICT, c.state.value.phase)
        f.finalMode = "ok"; c.save("other", f)
        val second = f.finals.last()
        assertEquals(first["key_bundle"], second["key_bundle"]); assertEquals(first["record"], second["record"])
        assertEquals(first["final_authorization"], second["final_authorization"])
        assertNotEquals(first["operation_id"], second["operation_id"])
        assertEquals(1, f.generations); assertEquals(1, f.assertions)
    }

    @Test fun sameCredentialReassertionMustOpenOriginalCandidateBeforeRenewedGrant(): Unit = runBlocking {
        val f = Fixture(); f.finalMode = "grant"; val c = f.controller()
        c.begin(f.google); c.save("valid", f)
        assertEquals(AtomicPhase.ASSERTION_REQUIRED, c.state.value.phase)
        f.finalMode = "ok"; f.prf = ByteArray(32) { 9 }; c.save("valid", f)
        assertEquals(1, f.finals.size); assertEquals(1, f.generations); assertNull(f.published)
        f.prf = ByteArray(32) { 7 }; c.save("valid", f)
        assertEquals(2, f.finals.size); assertEquals(1, f.generations)
        assertEquals(f.finals.first()["record"], f.finals.last()["record"])
        assertEquals(AtomicPhase.READY, c.state.value.phase)
    }

    @Test fun reservedStatusIsNotFenceAndUnknownRetryIsByteExact(): Unit = runBlocking {
        val f = Fixture(); f.finalMode = "lost-before"; val c = f.controller()
        c.begin(f.google); c.save("valid", f)
        assertEquals(AtomicPhase.UNKNOWN, c.state.value.phase)
        val original = f.finals.single()
        c.resolve(); assertEquals(AtomicPhase.UNKNOWN, c.state.value.phase)
        c.save("replacement", f); assertEquals(1, f.finals.size)
        f.finalMode = "ok"; c.retry()
        assertEquals(original, f.finals.last()); assertEquals(1, f.generations)
        assertEquals(AtomicPhase.READY, c.state.value.phase)
    }

    @Test fun committedReplyLossReplayAndFreshOwnerKeepOriginalIdentity(): Unit = runBlocking {
        val f = Fixture(); f.finalMode = "lost-after"; val c = f.controller()
        c.begin(f.google); c.save("original", f)
        val original = checkNotNull(f.saved)
        assertEquals(AtomicPhase.UNKNOWN, c.state.value.phase); assertNull(f.published)
        c.retry(); assertEquals(AtomicPhase.RESTORE_REQUIRED, c.state.value.phase)
        assertFalse(c.state.value.sessionEstablished); assertNull(f.installed)
        c.begin(f.google)
        assertEquals(AtomicPhase.EXISTING, c.state.value.phase)
        assertEquals(original.getValue("record").jsonObject["bundle_id"], JsonPrimitive(f.published!!.activeKeyBundle.keyBundleId))
        assertEquals(1, f.generations); assertEquals(1, f.creates)
        // Existing ADR011 restore opens the exact stored original, not a replacement tuple.
        val candidate = AtomicCandidate(original.getValue("key_bundle").jsonObject, original.getValue("record").jsonObject, f.prf.copyOf())
        try { assertEquals(candidate.bundle["encryption_public_keyset"], JsonPrimitive(Crypto.encode(Crypto.public(unwrapAtomic(candidate, candidate.localReadback()).encryption)))) }
        finally { candidate.wipe() }
    }

    @Test fun corruptOrUnavailableReadbackPublishesOnlyRestoreRequiredNeverReady(): Unit = runBlocking {
        for (mode in listOf("corrupt", "unavailable")) {
            val f = Fixture(); f.readMode = mode; val c = f.controller()
            c.begin(f.google); c.save("valid", f)
            assertEquals(AtomicPhase.RESTORE_REQUIRED, c.state.value.phase)
            assertTrue(c.state.value.sessionEstablished); assertNull(f.installed)
            assertEquals(1, f.generations); assertEquals("publish-locked", f.reads.last())
        }
    }

    @Test fun mismatchedAcceptedIntentStaysUnknownAndNeverInstalls(): Unit = runBlocking {
        val f = Fixture(); f.finalMode = "wrong-outcome"; val c = f.controller()
        c.begin(f.google); c.save("valid", f)
        assertEquals(AtomicPhase.UNKNOWN, c.state.value.phase)
        assertNull(f.installed); assertNull(f.published); assertTrue(f.reads.isEmpty())
        c.save("replacement", f); assertEquals(1, f.finals.size); assertEquals(1, f.generations)
    }

    @Test fun restartOutcomeOnlyReplayRequiresFreshProofNotAnotherSuccessor(): Unit = runBlocking {
        val f = Fixture(); f.reservationState = "EXPIRED"; f.restartReplay = true; val c = f.controller()
        c.begin(f.google); c.restart()
        assertEquals(AtomicPhase.REAUTHENTICATE, c.state.value.phase)
        c.restart(); assertEquals(1, f.restarts); assertEquals(0, f.generations); assertEquals(0, f.creates)
    }

    @Test fun expired410NeedsFreshProofThenExplicitRestartWithoutNewKeys(): Unit = runBlocking {
        val f = Fixture(); f.finalMode = "handle"; val c = f.controller()
        c.begin(f.google); c.save("taken", f); f.reservationState = "EXPIRED"
        c.cancelReservation()
        assertEquals(AtomicPhase.REAUTHENTICATE, c.state.value.phase); assertEquals("RESERVATION_TERMINAL", c.state.value.error)
        c.restart(); c.retry(); c.save("replacement", f)
        assertEquals(0, f.restarts); assertEquals(1, f.generations)
        val old = f.account
        c.begin(f.google); assertEquals(AtomicPhase.TERMINAL, c.state.value.phase)
        c.restart(); assertEquals(AtomicPhase.HANDLE, c.state.value.phase)
        assertNotEquals(old, f.account); assertEquals(1, f.restarts); assertEquals(1, f.generations)
        assertNull(f.published)
    }

    @Test fun validCancelReplayConvergesAndActive409CannotUndoSavedIdentity(): Unit = runBlocking {
        val f = Fixture(); val c = f.controller()
        c.begin(f.google); c.cancelReservation(); c.cancelReservation()
        assertEquals(AtomicPhase.REAUTHENTICATE, c.state.value.phase); assertEquals(0, f.generations)
        c.begin(f.google); c.restart(); f.finalMode = "lost-after"; c.save("original", f)
        val original = f.saved
        c.cancelReservation()
        assertEquals(AtomicPhase.RESTORE_REQUIRED, c.state.value.phase)
        assertEquals("ALREADY_ACTIVE", c.state.value.error); assertSame(original, f.saved)
        assertEquals(1, f.generations)
    }

    @Test fun lateCancel200And410CannotReplaceNewControllerOwner(): Unit = runBlocking {
        withTimeout(5000) {
            for (expired in listOf(false, true)) {
                val f = Fixture(); val c = f.controller()
                c.begin(f.google); if (expired) f.reservationState = "EXPIRED"
                f.hold = AtomicAction.CANCEL
                val old = launch { c.cancelReservation() }
                f.reached.await()
                assertNotNull(c.leave())
                f.hold = null; c.begin(f.google)
                assertEquals(AtomicPhase.TERMINAL, c.state.value.phase)
                f.release.complete(Unit); old.join()
                assertEquals(AtomicPhase.TERMINAL, c.state.value.phase)
                assertNull(f.published); assertEquals(0, f.generations)
            }
        }
    }

    @Test fun lateHandleFailureAfterLeaveCannotReviveOldForm(): Unit = runBlocking {
        withTimeout(5000) {
            val f = Fixture(); f.finalMode = "handle"; f.hold = AtomicAction.FINALIZE; val c = f.controller()
            c.begin(f.google)
            val old = launch { c.save("taken", f) }
            f.reached.await(); c.leave(); f.hold = null
            c.begin(f.google); assertEquals(AtomicPhase.HANDLE, c.state.value.phase)
            f.release.complete(Unit); old.join()
            assertEquals(AtomicPhase.HANDLE, c.state.value.phase); assertNull(c.state.value.error)
        }
    }

    @Test fun staleExternalSessionBeforePublicationHasNoInstallOrReplacement(): Unit = runBlocking {
        val f = Fixture(); f.retireOnRead = true; val c = f.controller()
        c.begin(f.google)
        // A separate coroutine owns the cancelled operation, not this test's scope.
        val request = launch { c.save("valid", f) }; request.join()
        assertNull(f.installed); assertNull(f.published); assertEquals(AtomicPhase.IDLE, c.state.value.phase)
    }

    @Test fun localCandidateIsExistingCryptoAndExplicitWipeOwnsPrf() {
        val secret = ByteArray(32) { 7 }
        val candidate = prepareAtomicCandidate(Fixture.ACCOUNT, Fixture.CREDENTIAL, secret) { AccountIdentityGenerator().generate() }
        val restored = unwrapAtomic(candidate, candidate.localReadback())
        assertEquals(candidate.bundle["encryption_public_keyset"], JsonPrimitive(Crypto.encode(Crypto.public(restored.encryption))))
        candidate.wipe(); assertTrue(secret.all { it == 0.toByte() })
    }

    @Test fun backDuringEitherPermanentReadRevokesOnlyReceivedLogin(): Unit = runBlocking {
        withTimeout(10000) {
            for (path in listOf("status", "record")) {
                val f = Fixture(); f.holdRead = path; val c = f.controller()
                c.begin(f.google)
                val saving = launch { c.save("original", f) }
                f.readReached.await() // Login already parsed; this is NOT a pre-response cancellation.
                assertNotNull(f.saved); assertNull(f.published)
                c.leave(); f.readRelease.complete(Unit); saving.join()
                assertEquals(listOf("pm_at_synthetic"), f.revoked)
                assertNull(f.published); assertNull(f.installed)
                assertEquals(AtomicPhase.IDLE, c.state.value.phase)
                assertEquals("ACTIVE", f.reservationState); assertEquals(1, f.generations)
            }
        }
    }

    @Test fun replacementDuringReadbackCannotRevokeNewOwnersSession(): Unit = runBlocking {
        withTimeout(10000) {
            for (path in listOf("status", "record")) {
                val f = Fixture(); f.holdRead = path; val c = f.controller()
                c.begin(f.google)
                val saving = launch { c.save("original", f) }
                f.readReached.await()
                val newer = f.replaceWithNewOwner()
                f.readRelease.complete(Unit); saving.join()
                assertEquals(listOf("pm_at_synthetic"), f.revoked)
                assertSame(newer, f.published); assertNull(f.installed)
                assertEquals(AtomicPhase.IDLE, c.state.value.phase)
            }
        }
    }

    @Test fun adoptedReadyAndRestoreRequiredSessionsAreNeverRevoked(): Unit = runBlocking {
        for (mode in listOf("ok", "corrupt", "unavailable")) {
            val f = Fixture(); f.readMode = mode; val c = f.controller()
            c.begin(f.google); c.save("original", f)
            assertTrue(c.state.value.sessionEstablished)
            assertEquals(if (mode == "ok") AtomicPhase.READY else AtomicPhase.RESTORE_REQUIRED, c.state.value.phase)
            c.leave() // Leaving after adoption is not logout or ACTIVE rollback.
            assertNotNull(f.published); assertTrue(f.revoked.isEmpty())
        }
    }

    @Test fun failedBestEffortLogoutDoesNotResurrectAbandonedFlow(): Unit = runBlocking {
        withTimeout(10000) {
            val f = Fixture(); f.holdRead = "record"; f.logoutFails = true; val c = f.controller()
            c.begin(f.google)
            val saving = launch { c.save("original", f) }
            f.readReached.await(); c.leave(); f.readRelease.complete(Unit); saving.join()
            assertEquals(listOf("pm_at_synthetic"), f.revoked)
            assertNull(f.published); assertEquals(AtomicPhase.IDLE, c.state.value.phase)
        }
    }

    internal class Fixture : AtomicRegistrationPort, RecoveryCredentialPort {
        var epoch = 0L; var account = ACCOUNT; var reservation = RESERVATION; var reservationState = "RESERVED"
        var registered = false; var freshTerminal = false; var restarts = 0; var restartReplay = false
        var generations = 0; var creates = 0; var assertions = 0; var immediateGets = 0; var discoveryGets = 0
        var finalMode = "ok"; var providerMode = "ok"; var readMode = "ok"; var loseCredentialAck = false; var retireOnRead = false
        var prf = ByteArray(32) { 7 }
        var saved: JsonObject? = null; var published: LoginResponseDto? = null; var installed: Crypto.Restored? = null
        val requests = mutableListOf<Pair<AtomicAction, JsonObject?>>()
        val finals = mutableListOf<JsonObject>(); val reads = mutableListOf<String>()
        var hold: AtomicAction? = null
        val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var holdRead: String? = null; var logoutFails = false
        val readReached = CompletableDeferred<Unit>(); val readRelease = CompletableDeferred<Unit>()
        val revoked = mutableListOf<String>()
        fun replaceWithNewOwner(): LoginResponseDto {
            epoch++
            return AtomicWire.login(login()).copy(accessToken = "pm_at_new_owner").also { published = it }
        }
        val google = GoogleCredentialPort { _, _ -> "synthetic-google-proof" }
        fun controller() = AtomicRegistrationController(AtomicEnvironment(this, { epoch }, { it == epoch && published == null },
            read = { _, path ->
                reads += path
                if (holdRead == path) withContext(NonCancellable) { readReached.complete(Unit); readRelease.await() }
                if (retireOnRead) epoch++
                if (readMode == "unavailable") throw IOException("synthetic")
                if (path == "status") buildJsonObject { put("state", "STORED"); put("credential_id", CREDENTIAL) }
                else {
                    val intent = checkNotNull(saved); val bundle = intent.getValue("key_bundle").jsonObject
                    buildJsonObject {
                        put("record", if (readMode == "corrupt") JsonObject(intent.getValue("record").jsonObject + ("generation" to JsonPrimitive(2))) else intent.getValue("record"))
                        put("encryption_public_keyset", bundle.getValue("encryption_public_keyset")); put("signing_public_keyset", bundle.getValue("signing_public_keyset"))
                    }
                }
            }, publish = { _, login, keys, admission ->
                check(admission.isCurrent())
                admission.install {
                    reads += if (keys == null) "publish-locked" else "publish-keys"
                    installed = keys; published = login
                }
            }, isPublished = { published === it }, revoke = { token ->
                revoked += token
                if (logoutFails) throw IOException("synthetic logout unavailable")
            }, generate = { generations++; AccountIdentityGenerator().generate() }, cryptoDispatcher = Dispatchers.Unconfined))

        override suspend fun request(action: AtomicAction, reservation: String?, token: String?, body: JsonObject?): JsonObject {
            requests += action to body
            if (action !in setOf(AtomicAction.GOOGLE_OPTIONS, AtomicAction.GOOGLE_VERIFY)) {
                check(reservation == this.reservation); check(token == TOKEN)
            }
            val result = runCatching { dispatch(action, body) }
            if (hold == action) withContext(NonCancellable) { reached.complete(Unit); release.await() }
            return result.getOrThrow()
        }
        private fun dispatch(action: AtomicAction, body: JsonObject?): JsonObject = when (action) {
            AtomicAction.GOOGLE_OPTIONS -> buildJsonObject { put("flow", FLOW); put("nonce", "A".repeat(43)); put("client_id", "synthetic") }
            AtomicAction.GOOGLE_VERIFY -> {
                check(body!!["onboarding_version"] == JsonPrimitive(1))
                if (saved != null) buildJsonObject { put("status", "SIGNED_IN"); put("login", login()) }
                else { freshTerminal = reservationState != "RESERVED"; setup() }
            }
            AtomicAction.STATUS -> buildJsonObject { put("reservation", reservationJson()) }
            AtomicAction.CREDENTIAL_OPTIONS -> {
                val id = UUID.fromString(account)
                buildJsonObject { put("flow", FLOW); put("options", buildJsonObject { put("user", buildJsonObject {
                    put("id", Crypto.encode(ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()))
                }) }) }
            }
            AtomicAction.CREDENTIAL_VERIFY -> {
                registered = true
                if (loseCredentialAck) { loseCredentialAck = false; throw IOException("lost ack") }
                buildJsonObject { put("registered", true); put("recoverable", false); put("credential_id", CREDENTIAL) }
            }
            AtomicAction.SETUP_OPTIONS -> buildJsonObject {
                put("flow", FLOW)
                put("options", buildJsonObject {
                    put("allowCredentials", buildJsonArray {
                        add(buildJsonObject { put("id", CREDENTIAL); put("type", "public-key") })
                    })
                })
            }
            AtomicAction.SETUP_VERIFY -> {
                if (providerMode == "rejected-proof") throw AtomicRegistrationRejected(400, "SETUP_REJECTED")
                buildJsonObject { put("authentication_verified", true); put("credential_id", CREDENTIAL); put("final_authorization", "rgf_" + "A".repeat(43)) }
            }
            AtomicAction.FINALIZE -> {
                val intent = checkNotNull(body); finals += intent
                when {
                    saved != null -> accepted(true)
                    finalMode == "handle" -> throw AtomicRegistrationRejected(409, "HANDLE_TAKEN")
                    finalMode == "grant" -> throw AtomicRegistrationRejected(400, "SETUP_REJECTED")
                    finalMode == "lost-before" -> throw IOException("lost before")
                    else -> { saved = intent; reservationState = "ACTIVE"
                        if (finalMode == "lost-after") throw IOException("lost after")
                        accepted(false)
                    }
                }
            }
            AtomicAction.CANCEL -> when (reservationState) {
                "ACTIVE" -> throw AtomicRegistrationRejected(409, "ALREADY_ACTIVE")
                "EXPIRED" -> throw AtomicRegistrationRejected(410, "RESERVATION_TERMINAL")
                else -> { reservationState = "CANCELLED"; registered = false; buildJsonObject { put("reservation", reservationJson()) } }
            }
            AtomicAction.RESTART -> {
                check(freshTerminal); restarts++
                if (restartReplay) buildJsonObject { put("status", "RESTARTED"); put("reauthenticate", true); put("reservation", reservationJson()) }
                else {
                    account = UUID.randomUUID().toString(); reservation = UUID.randomUUID().toString()
                    reservationState = "RESERVED"; registered = false; setup()
                }
            }
            AtomicAction.LEAVE -> buildJsonObject { put("left", true) }
        }
        private fun setup() = buildJsonObject {
            put("status", "SETUP_REQUIRED"); put("reservation", reservationJson()); put("setup_token", TOKEN)
            put("setup_session_id", FLOW); put("setup_expires_at", "2026-10-21T10:10:00Z")
        }
        private fun reservationJson() = buildJsonObject {
            put("id", reservation); put("account_id", account); put("epoch", 1); put("state", reservationState)
            put("expires_at", "2026-10-22T10:00:00Z"); put("credential_ids", buildJsonArray { if (registered) add(CREDENTIAL) })
            put("outcome", if (saved == null) JsonNull else outcome()); put("restarted_into", JsonNull)
        }
        private fun outcome() = buildJsonObject {
            val intent = checkNotNull(saved); val record = intent.getValue("record").jsonObject
            put("operation_id", intent.getValue("operation_id")); put("handle", intent.getValue("handle"))
            put("account_id", record.getValue("account_id")); put("bundle_id", record.getValue("bundle_id"))
            put("credential_id", CREDENTIAL); put("generation", 1)
        }
        private fun accepted(replay: Boolean) = buildJsonObject {
            put("status", "ACTIVE"); put("outcome", if (finalMode == "wrong-outcome") JsonObject(outcome() + ("operation_id" to JsonPrimitive(UUID.randomUUID().toString()))) else outcome())
            if (replay) put("reauthenticate", true) else put("login", login())
        }
        private fun login() = buildJsonObject {
            val intent = checkNotNull(saved)
            put("user", buildJsonObject { put("user_id", account); put("email", "synthetic@example.invalid"); put("handle", intent.getValue("handle")); put("created_at", "2026-10-21T10:00:00Z") })
            put("active_key_bundle", buildJsonObject { put("key_bundle_id", intent.getValue("record").jsonObject.getValue("bundle_id")); put("suite", PreparedIdentity.SUITE); put("protocol_version", 1); put("status", "ACTIVE") })
            put("session_id", FLOW); put("access_token", "pm_at_synthetic"); put("refresh_token", "pm_rt_synthetic")
            put("access_expires_at", "2026-10-21T10:10:00Z"); put("refresh_expires_at", "2026-11-21T10:00:00Z")
        }
        override suspend fun create(options: JsonObject): JsonObject { creates++; return credential(false) }
        override suspend fun get(options: JsonObject): JsonObject { discoveryGets++; assertions++; return credential(true) }
        override suspend fun getImmediate(options: JsonObject): JsonObject {
            immediateGets++
            if (providerMode == "pending") throw NoCredentialException()
            assertions++; return credential(true)
        }
        private fun credential(withPrf: Boolean) = buildJsonObject {
            put("id", CREDENTIAL); put("rawId", CREDENTIAL); put("type", "public-key"); put("providerPrivate", "do-not-send")
            put("response", buildJsonObject { put("clientDataJSON", "e30"); put("signature", "c2ln"); put("providerPrivate", "do-not-send") })
            put("clientExtensionResults", if (withPrf && providerMode != "no-prf") buildJsonObject {
                put("prf", buildJsonObject { put("results", buildJsonObject { put("first", Crypto.encode(prf)) }) })
            } else JsonObject(emptyMap()))
        }
        companion object {
            const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
            const val RESERVATION = "22222222-2222-4222-8222-222222222222"
            const val FLOW = "33333333-3333-4333-8333-333333333333"
            const val CREDENTIAL = "Y3JlZA"
            val TOKEN = "rgs_" + "A".repeat(43)
        }
    }
}
