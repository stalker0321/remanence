package dev.hryshyn.remanence.recovery

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import dev.hryshyn.remanence.auth.SoftwareKekBoundary
import dev.hryshyn.remanence.core.crypto.AccountRecoveryCrypto
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.crypto.KeysetKekWrapper
import dev.hryshyn.remanence.core.data.db.LocalAccountEntity
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.wiring.OwnerScopedIdentityStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Real `protect` orchestration through the bounded [ProtectEnvironment]
 * seam: fake credentials/API/session/account/readiness, REAL
 * owner-scoped stores so `Crypto.prepare`/readback/`restore` are genuine.
 * Every timeline below is asserted call-by-call: request modes, API
 * order, readiness marks, and fail-closed gates.
 */
class ProtectOrchestrationTest {

    private val ownerA = "0198f0a0-0000-7000-8000-00000000a001"
    private val kekAlias = "t211-protect-test-kek"
    private val prfBytes = ByteArray(32) { it.toByte() }
    private val prfB64 = AccountRecoveryCrypto.encode(prfBytes)

    /**
     * Genuinely canonical deterministic credential IDs.
     * `Crypto.context` requires canonical base64url (decode→re-encode
     * equality), which synthetic literals fail — the fixture bug
     * behind the JVM failures, not a production defect. Companion
     * scope (not instance): the nested Fixture reads the default ID,
     * and instance properties are unresolved there.
     */
    private companion object {
        val credId1 = AccountRecoveryCrypto.encode(ByteArray(32) { (it + 1).toByte() })
        val credId2 = AccountRecoveryCrypto.encode(ByteArray(32) { (it + 101).toByte() })
        val credIdX = AccountRecoveryCrypto.encode(ByteArray(32) { (it + 201).toByte() })
    }

    private class Fixture(
        val owner: String,
        val stores: OwnerScopedIdentityStore,
        val bundleA: String,
        val encB64: String,
        val sigB64: String,
        val identity: IdentityBundleRepository.LoadResult.Available,
    ) {
        val apiCalls = mutableListOf<String>()
        val setupOptionBodies = mutableListOf<JsonObject>()
        var status: JsonObject = buildJsonObject { put("state", "NOT_STARTED") }
        var registerOptions: JsonObject =
            buildJsonObject { put("flow", "reg-flow"); put("options", buildJsonObject {}) }
        var registerVerifyId: String = credId1
        /** Set by register/verify: the fake server remembers the enrollment,
         * so a later status read in the SAME fixture reports PENDING. */
        var registeredId: String? = null
        var setupVerify: JsonObject = buildJsonObject { put("flow", "verify-flow") }
        var uploaded: JsonObject? = null
        var storedRecord: JsonObject? = null
        var leaseLive: Boolean = true
        val verified = mutableListOf<Pair<String, String>>()
        val lease = SessionRequestLease(UserId.parseRest(owner), 1L)
        val row = LocalAccountEntity(owner, "mykola", bundleA, 0L, 0L)

        /** Realistic single-id allowCredentials options for one enrollment. */
        fun setupOptionsFor(id: String): JsonObject = buildJsonObject {
            put("flow", "setup-flow")
            put("options", buildJsonObject {
                put("rpId", "example.test")
                put("challenge", "setup-challenge")
                put("allowCredentials", buildJsonArray {
                    addJsonObject {
                        put("type", "public-key")
                        put("id", id)
                    }
                })
                put("userVerification", "required")
            })
        }

        fun env() = ProtectEnvironment(
            captureLease = { lease },
            loadAccount = { row },
            stores = stores,
            requestAuthenticated = { _, path, body ->
                apiCalls += path
                when (path) {
                    "status" -> registeredId?.let {
                        buildJsonObject {
                            put("state", "PENDING")
                            put("credential_id", it)
                        }
                    } ?: status
                    "register/options" -> registerOptions
                    "register/verify" -> {
                        registeredId = registerVerifyId
                        buildJsonObject { put("credential_id", registerVerifyId) }
                    }
                    "setup/options" -> {
                        setupOptionBodies += body ?: buildJsonObject {}
                        setupOptionsFor(
                            body?.getValue("credential_id")?.jsonPrimitive?.content
                                ?: error("setup/options missing credential_id"),
                        )
                    }
                    "setup/verify" -> setupVerify
                    "record" -> if (body == null) {
                        storedRecord ?: buildJsonObject {
                            put("record", uploaded ?: error("record read before upload"))
                            put("encryption_public_keyset", encB64)
                            put("signing_public_keyset", sigB64)
                        }
                    } else {
                        uploaded = body.getValue("record").jsonObject
                        buildJsonObject {}
                    }
                    else -> error("unexpected path $path")
                }
            },
            isLeaseLive = { leaseLive },
            markVerified = { owner, bundle -> verified += owner to bundle },
        )
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("protect-stores").toFile()
        val kek = SoftwareKekBoundary()
        if (!kek.hasKey(kekAlias)) kek.createAes256GcmKey(kekAlias)
        val wrapper = KeysetKekWrapper(kek)
        val stores = OwnerScopedIdentityStore(
            File(root, "identity"),
            File(root, "identity-staging"),
            wrapper,
        )
        IdentityBundleRepository(File(root, "identity"), wrapper).createFresh(kekAlias)
        val exports = IdentityBundleRepository(File(root, "identity"), wrapper).loadPublicExports()
            as IdentityBundleRepository.PublicExportsResult.Available
        val bundleA = java.util.UUID.nameUUIDFromBytes(exports.encryptionPublicKeyset).toString()
        assertTrue(stores.claimLegacyIfMatching(ownerA, bundleA))
        val identity = IdentityBundleRepository(File(root, "identity"), wrapper).load()
            as IdentityBundleRepository.LoadResult.Available
        return Fixture(
            ownerA,
            stores,
            bundleA,
            AccountRecoveryCrypto.encode(exports.encryptionPublicKeyset),
            AccountRecoveryCrypto.encode(exports.signingPublicKeyset),
            identity,
        )
    }

    private class FakeCredentials(
        var created: JsonObject = buildJsonObject {},
        var getResult: JsonObject? = null,
        var getError: Exception? = null,
        var immediateError: Exception? = null,
    ) : RecoveryCredentialPort {
        val modes = mutableListOf<String>()
        /** Exact option objects each get variant received, in order. */
        val receivedOptions = mutableListOf<JsonObject>()
        var createCalls = 0

        override suspend fun create(options: JsonObject): JsonObject {
            createCalls++
            return created
        }

        override suspend fun get(options: JsonObject): JsonObject {
            modes += "get"
            receivedOptions += options
            getError?.let { throw it }
            return getResult ?: error("no get result scripted")
        }

        override suspend fun getImmediate(options: JsonObject): JsonObject {
            modes += "getImmediate"
            receivedOptions += options
            immediateError?.let { throw it }
            return getResult ?: error("no get result scripted")
        }
    }

    private fun credentialJson(id: String, firstB64: String): JsonObject = buildJsonObject {
        put("id", id)
        put("rawId", id)
        put("type", "public-key")
        put("clientExtensionResults", buildJsonObject {
            put("prf", buildJsonObject {
                put("results", buildJsonObject { put("first", firstB64) })
            })
        })
        put("response", buildJsonObject {
            put("clientDataJSON", "client-data")
            put("authenticatorData", "auth-data")
            put("signature", "sig")
        })
    }

    private fun recordJsonOf(record: AccountRecoveryCrypto.Record): JsonObject = buildJsonObject {
        put("version", record.version)
        put("generation", record.generation)
        put("account_id", record.accountId)
        put("bundle_id", record.bundleId)
        put("credential_id", record.credentialId)
        put("salt", record.salt)
        put("package_sealed", record.packageSealed)
        put("prf_ark", record.prfArk)
        put("code_ark", record.codeArk)
        put("signature", record.signature)
    }

    /**
     * Exact get-options passthrough for one get call: the provider
     * receives byte-identical server options, and the single-id
     * allowCredentials binds the exact enrollment id.
     */
    private fun assertSameEnrollmentOptions(
        f: Fixture,
        credentials: FakeCredentials,
        id: String,
        callIndex: Int,
    ) {
        val seen = credentials.receivedOptions[callIndex]
        assertEquals(f.setupOptionsFor(id).getValue("options"), seen)
        assertEquals(
            id,
            seen.getValue("allowCredentials").jsonArray.single().jsonObject
                .getValue("id").jsonPrimitive.content,
        )
    }

    @Test
    fun freshCreateMissingGetBecomesTypedPendingWithoutSideEffects() = runTest {
        val f = fixture()
        val credentials = FakeCredentials(
            created = credentialJson(credId1, prfB64),
            immediateError = NoCredentialException("not indexed yet"),
        )

        var pending: RecoveryVerificationPending? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected RecoveryVerificationPending")
        } catch (e: RecoveryVerificationPending) {
            pending = e
        }
        assertEquals(credId1, pending?.credentialId)
        // One create+register, immediate get, then the typed continuation:
        // no setup/verify, no upload, no readiness.
        assertEquals(
            listOf("status", "register/options", "register/verify", "setup/options"),
            f.apiCalls,
        )
        assertEquals(listOf("getImmediate"), credentials.modes)
        assertSameEnrollmentOptions(f, credentials, credId1, 0)
        assertEquals(1, credentials.createCalls)
        assertTrue(f.verified.isEmpty())
        assertNull(f.uploaded)
    }

    @Test
    fun freshPendingThenExplicitRetryCompletesSameEnrollment() = runTest {
        // ONE stateful fixture for both invocations: the server fake flips
        // to PENDING on register/verify, exactly like the real backend.
        val f = fixture()
        val credentials = FakeCredentials(
            created = credentialJson(credId1, prfB64),
            getResult = credentialJson(credId1, prfB64),
            immediateError = NoCredentialException("not indexed yet"),
        )

        // First invocation: create+register, immediate miss → typed pending.
        var pending: RecoveryVerificationPending? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected RecoveryVerificationPending")
        } catch (e: RecoveryVerificationPending) {
            pending = e
        }
        assertEquals(credId1, pending?.credentialId)
        assertTrue(f.verified.isEmpty())

        // Explicit retry on the SAME fixture: PENDING reuses the retained
        // id with normal discovery and runs the full gates to readiness.
        val code = protectWith(f.env(), credentials)

        assertNotNull(code)
        assertEquals(32, code?.size)
        // Exactly one registration across both invocations; the retry
        // never re-creates. Modes flip immediate → ordinary.
        assertEquals(1, f.apiCalls.count { it == "register/verify" })
        assertEquals(1, credentials.createCalls)
        assertEquals(listOf("getImmediate", "get"), credentials.modes)
        // Same enrollment id and byte-identical options on both gets.
        assertSameEnrollmentOptions(f, credentials, credId1, 0)
        assertSameEnrollmentOptions(f, credentials, credId1, 1)
        assertEquals(
            listOf(
                "status", "register/options", "register/verify", "setup/options",
                "status", "setup/options", "setup/verify", "record", "record",
            ),
            f.apiCalls,
        )
        assertEquals(credId1, f.uploaded?.getValue("credential_id")?.jsonPrimitive?.content)
        assertEquals(listOf(ownerA to f.bundleA), f.verified)
    }

    @Test
    fun freshImmediateSuccessCompletesFullGatesWithoutFallback() = runTest {
        val f = fixture()
        val credentials = FakeCredentials(
            created = credentialJson(credId1, prfB64),
            getResult = credentialJson(credId1, prfB64),
        )

        val code = protectWith(f.env(), credentials)

        assertNotNull(code)
        assertEquals(32, code?.size)
        // No fallback to ordinary get, no duplicate registration.
        assertEquals(listOf("getImmediate"), credentials.modes)
        assertSameEnrollmentOptions(f, credentials, credId1, 0)
        assertEquals(1, credentials.createCalls)
        assertEquals(1, f.apiCalls.count { it == "register/verify" })
        assertEquals(
            listOf("status", "register/options", "register/verify", "setup/options", "setup/verify", "record", "record"),
            f.apiCalls,
        )
        assertEquals(listOf(ownerA to f.bundleA), f.verified)
    }

    @Test
    fun corruptDurableReadbackReturnsNoCodeOrReadiness() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        // Upload succeeds; the DURABLE read then serves a record bound to
        // a different bundle. The post-upload gate must refuse it.
        val prepared = AccountRecoveryCrypto.prepare(ownerA, f.bundleA, credId1, prfBytes, f.identity)
        val tampered = JsonObject(
            recordJsonOf(prepared.record) +
                ("bundle_id" to JsonPrimitive("0198f0a0-0000-7000-8000-00000000b099")),
        )
        prepared.recoveryCode.fill(0)
        f.storedRecord = buildJsonObject {
            put("record", tampered)
            put("encryption_public_keyset", f.encB64)
            put("signing_public_keyset", f.sigB64)
        }
        val credentials = FakeCredentials(getResult = credentialJson(credId1, prfB64))

        var thrown: java.security.GeneralSecurityException? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected readback refusal")
        } catch (e: java.security.GeneralSecurityException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertNotNull(f.uploaded)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun freshImmediateCancellationStaysNeutral() = runTest {
        val f = fixture()
        val credentials = FakeCredentials(
            created = credentialJson(credId1, prfB64),
            immediateError = GetCredentialCancellationException(),
        )

        var thrown: GetCredentialCancellationException? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected cancellation")
        } catch (e: GetCredentialCancellationException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(
            listOf("status", "register/options", "register/verify", "setup/options"),
            f.apiCalls,
        )
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun freshImmediateUnknownStaysDistinctFromPending() = runTest {
        val f = fixture()
        val credentials = FakeCredentials(
            created = credentialJson(credId1, prfB64),
            immediateError = GetCredentialUnknownException("fresh provider boom"),
        )

        var thrown: Exception? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected provider error")
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is GetCredentialUnknownException)
        assertTrue(thrown !is RecoveryVerificationPending)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun pendingRetryUsesNormalGetWithSameIdAndCompletesAllGates() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        val credentials = FakeCredentials(getResult = credentialJson(credId1, prfB64))

        val code = protectWith(f.env(), credentials)

        assertNotNull(code)
        assertEquals(32, code?.size)
        // Exact same-key retry: normal discovery, server id echoed back.
        assertEquals(listOf("get"), credentials.modes)
        assertSameEnrollmentOptions(f, credentials, credId1, 0)
        assertEquals(0, credentials.createCalls)
        assertEquals(credId1, f.setupOptionBodies.single().getValue("credential_id").jsonPrimitive.content)
        assertEquals(
            listOf("status", "setup/options", "setup/verify", "record", "record"),
            f.apiCalls,
        )
        assertNotNull(f.uploaded)
        assertEquals(credId1, f.uploaded?.getValue("credential_id")?.jsonPrimitive?.content)
        // Readiness marked exactly once, at the very end.
        assertEquals(listOf(ownerA to f.bundleA), f.verified)
    }

    @Test
    fun storedBranchReturnsNullWithoutReplacementCode() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "STORED")
            put("credential_id", credId1)
        }
        // STORED never uploads: serve a genuinely prepared record built
        // from the same real identity the orchestration resolves.
        val credentials = FakeCredentials(getResult = credentialJson(credId1, prfB64))
        f.storedRecord = buildJsonObject {
            put("record", recordJsonOf(
                AccountRecoveryCrypto.prepare(ownerA, f.bundleA, credId1, prfBytes, f.identity).record,
            ))
            put("encryption_public_keyset", f.encB64)
            put("signing_public_keyset", f.sigB64)
        }

        assertNull(protectWith(f.env(), credentials))

        assertEquals(listOf("get"), credentials.modes)
        assertSameEnrollmentOptions(f, credentials, credId1, 0)
        assertEquals(0, credentials.createCalls)
        assertNull(f.uploaded)
        assertEquals(listOf(ownerA to f.bundleA), f.verified)
    }

    @Test
    fun pendingNormalGetNoCredentialPropagatesUntyped() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        val missing = NoCredentialException("nothing discoverable")
        val credentials = FakeCredentials(getError = missing)

        var thrown: Exception? = null
        try {
            protectWith(f.env(), credentials)
        } catch (e: Exception) {
            thrown = e
        }
        // Non-fresh path: the raw provider error propagates, never typed.
        assertTrue(thrown === missing)
        assertEquals(listOf("status", "setup/options"), f.apiCalls)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun anotherManagerOnPendingCreatesThenUsesImmediate() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        f.registerVerifyId = credId2
        val credentials = FakeCredentials(
            created = credentialJson(credId2, prfB64),
            immediateError = NoCredentialException("not indexed yet"),
        )

        var pending: RecoveryVerificationPending? = null
        try {
            protectWith(f.env(), credentials, chooseAnotherManager = true)
            fail("expected RecoveryVerificationPending")
        } catch (e: RecoveryVerificationPending) {
            pending = e
        }
        assertEquals(credId2, pending?.credentialId)
        assertEquals(listOf("getImmediate"), credentials.modes)
        assertEquals(1, credentials.createCalls)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun anotherManagerOnStoredRefusesImmutably() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "STORED")
            put("credential_id", credId1)
        }
        val credentials = FakeCredentials()

        var thrown: IllegalStateException? = null
        try {
            protectWith(f.env(), credentials, chooseAnotherManager = true)
            fail("expected immutability guard")
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertEquals("existing recovery is immutable", thrown?.message)
        assertEquals(0, credentials.createCalls)
        assertTrue(credentials.modes.isEmpty())
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun wrongCredentialFailsClosedWithoutReadiness() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        val credentials = FakeCredentials(getResult = credentialJson(credIdX, prfB64))

        var thrown: IllegalStateException? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected wrong-credential refusal")
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertEquals("wrong credential", thrown?.message)
        assertEquals(listOf("status", "setup/options"), f.apiCalls)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun shortPrfFailsBeforeVerifyWithoutReadiness() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        val shortB64 = AccountRecoveryCrypto.encode(ByteArray(16) { 1 })
        val credentials = FakeCredentials(getResult = credentialJson(credId1, shortB64))

        var thrown: RecoveryCapabilityUnavailable? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected PRF refusal")
        } catch (e: RecoveryCapabilityUnavailable) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(listOf("status", "setup/options"), f.apiCalls)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun missingPrfFailsBeforeVerifyWithoutReadiness() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        val withoutPrf = buildJsonObject {
            put("id", credId1)
            put("rawId", credId1)
            put("type", "public-key")
            put("clientExtensionResults", buildJsonObject {})
            put("response", buildJsonObject { put("clientDataJSON", "client-data") })
        }
        val credentials = FakeCredentials(getResult = withoutPrf)

        var thrown: RecoveryCapabilityUnavailable? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected PRF refusal")
        } catch (e: RecoveryCapabilityUnavailable) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(listOf("status", "setup/options"), f.apiCalls)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun staleLeaseAbortsBeforeReadbackWithoutReadiness() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        f.leaseLive = false
        val credentials = FakeCredentials(getResult = credentialJson(credId1, prfB64))

        var thrown: IllegalStateException? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected stale-lease refusal")
        } catch (e: IllegalStateException) {
            thrown = e
        }
        assertEquals("session changed", thrown?.message)
        assertEquals(listOf("status", "setup/options", "setup/verify"), f.apiCalls)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun cancellationPropagatesNeutrallyWithoutReadiness() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        val credentials = FakeCredentials(getError = GetCredentialCancellationException())

        var thrown: GetCredentialCancellationException? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected cancellation")
        } catch (e: GetCredentialCancellationException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun unknownProviderErrorStaysDistinctFromPending() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", credId1)
        }
        val credentials = FakeCredentials(getError = GetCredentialUnknownException("provider boom"))

        var thrown: Exception? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected provider error")
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is GetCredentialUnknownException)
        assertEquals("provider boom", (thrown as GetCredentialUnknownException).message)
        assertTrue(thrown !is RecoveryVerificationPending)
        assertTrue(f.verified.isEmpty())
    }
}
