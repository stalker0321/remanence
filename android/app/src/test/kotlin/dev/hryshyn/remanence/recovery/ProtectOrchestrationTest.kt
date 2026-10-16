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

    private class Fixture(
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
        var registerVerifyId: String = "cred-1"
        var setupOptions: JsonObject =
            buildJsonObject { put("flow", "setup-flow"); put("options", buildJsonObject {}) }
        var setupVerify: JsonObject = buildJsonObject { put("flow", "verify-flow") }
        var uploaded: JsonObject? = null
        var storedRecord: JsonObject? = null
        var leaseLive: Boolean = true
        val verified = mutableListOf<Pair<String, String>>()
        val lease = SessionRequestLease(UserId.parseRest(ownerA), 1L)
        val row = LocalAccountEntity(ownerA, "mykola", bundleA, 0L, 0L)

        fun env() = ProtectEnvironment(
            captureLease = { lease },
            loadAccount = { row },
            stores = stores,
            requestAuthenticated = { _, path, body ->
                apiCalls += path
                when (path) {
                    "status" -> status
                    "register/options" -> registerOptions
                    "register/verify" -> buildJsonObject { put("credential_id", registerVerifyId) }
                    "setup/options" -> {
                        setupOptionBodies += body ?: buildJsonObject {}
                        setupOptions
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
        var createCalls = 0

        override suspend fun create(options: JsonObject): JsonObject {
            createCalls++
            return created
        }

        override suspend fun get(options: JsonObject): JsonObject {
            modes += "get"
            getError?.let { throw it }
            return getResult ?: error("no get result scripted")
        }

        override suspend fun getImmediate(options: JsonObject): JsonObject {
            modes += "getImmediate"
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

    @Test
    fun freshCreateMissingGetBecomesTypedPendingWithoutSideEffects() = runTest {
        val f = fixture()
        val credentials = FakeCredentials(
            created = credentialJson("cred-1", prfB64),
            immediateError = NoCredentialException("not indexed yet"),
        )

        var pending: RecoveryVerificationPending? = null
        try {
            protectWith(f.env(), credentials)
            fail("expected RecoveryVerificationPending")
        } catch (e: RecoveryVerificationPending) {
            pending = e
        }
        assertEquals("cred-1", pending?.credentialId)
        // One create+register, immediate get, then the typed continuation:
        // no setup/verify, no upload, no readiness.
        assertEquals(
            listOf("status", "register/options", "register/verify", "setup/options"),
            f.apiCalls,
        )
        assertEquals(listOf("getImmediate"), credentials.modes)
        assertEquals(1, credentials.createCalls)
        assertTrue(f.verified.isEmpty())
        assertNull(f.uploaded)
    }

    @Test
    fun pendingRetryUsesNormalGetWithSameIdAndCompletesAllGates() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", "cred-1")
        }
        val credentials = FakeCredentials(getResult = credentialJson("cred-1", prfB64))

        val code = protectWith(f.env(), credentials)

        assertNotNull(code)
        assertEquals(32, code?.size)
        // Exact same-key retry: normal discovery, server id echoed back.
        assertEquals(listOf("get"), credentials.modes)
        assertEquals(0, credentials.createCalls)
        assertEquals("cred-1", f.setupOptionBodies.single().getValue("credential_id").jsonPrimitive.content)
        assertEquals(
            listOf("status", "setup/options", "setup/verify", "record", "record"),
            f.apiCalls,
        )
        assertNotNull(f.uploaded)
        assertEquals("cred-1", f.uploaded?.getValue("credential_id")?.jsonPrimitive?.content)
        // Readiness marked exactly once, at the very end.
        assertEquals(listOf(ownerA to f.bundleA), f.verified)
    }

    @Test
    fun storedBranchReturnsNullWithoutReplacementCode() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "STORED")
            put("credential_id", "cred-1")
        }
        // STORED never uploads: serve a genuinely prepared record built
        // from the same real identity the orchestration resolves.
        val credentials = FakeCredentials(getResult = credentialJson("cred-1", prfB64))
        f.storedRecord = buildJsonObject {
            put("record", AccountRecoveryCrypto.prepare(ownerA, f.bundleA, "cred-1", prfBytes, f.identity).record.let {
                buildJsonObject {
                    put("version", it.version)
                    put("generation", it.generation)
                    put("account_id", it.accountId)
                    put("bundle_id", it.bundleId)
                    put("credential_id", it.credentialId)
                    put("salt", it.salt)
                    put("package_sealed", it.packageSealed)
                    put("prf_ark", it.prfArk)
                    put("code_ark", it.codeArk)
                    put("signature", it.signature)
                }
            })
            put("encryption_public_keyset", f.encB64)
            put("signing_public_keyset", f.sigB64)
        }

        assertNull(protectWith(f.env(), credentials))

        assertEquals(listOf("get"), credentials.modes)
        assertEquals(0, credentials.createCalls)
        assertNull(f.uploaded)
        assertEquals(listOf(ownerA to f.bundleA), f.verified)
    }

    @Test
    fun pendingNormalGetNoCredentialPropagatesUntyped() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "PENDING")
            put("credential_id", "cred-1")
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
            put("credential_id", "cred-1")
        }
        f.registerVerifyId = "cred-2"
        val credentials = FakeCredentials(
            created = credentialJson("cred-2", prfB64),
            immediateError = NoCredentialException("not indexed yet"),
        )

        var pending: RecoveryVerificationPending? = null
        try {
            protectWith(f.env(), credentials, chooseAnotherManager = true)
            fail("expected RecoveryVerificationPending")
        } catch (e: RecoveryVerificationPending) {
            pending = e
        }
        assertEquals("cred-2", pending?.credentialId)
        assertEquals(listOf("getImmediate"), credentials.modes)
        assertEquals(1, credentials.createCalls)
        assertTrue(f.verified.isEmpty())
    }

    @Test
    fun anotherManagerOnStoredRefusesImmutably() = runTest {
        val f = fixture()
        f.status = buildJsonObject {
            put("state", "STORED")
            put("credential_id", "cred-1")
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
            put("credential_id", "cred-1")
        }
        val credentials = FakeCredentials(getResult = credentialJson("cred-X", prfB64))

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
            put("credential_id", "cred-1")
        }
        val shortB64 = AccountRecoveryCrypto.encode(ByteArray(16) { 1 })
        val credentials = FakeCredentials(getResult = credentialJson("cred-1", shortB64))

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
            put("credential_id", "cred-1")
        }
        val withoutPrf = buildJsonObject {
            put("id", "cred-1")
            put("rawId", "cred-1")
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
            put("credential_id", "cred-1")
        }
        f.leaseLive = false
        val credentials = FakeCredentials(getResult = credentialJson("cred-1", prfB64))

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
            put("credential_id", "cred-1")
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
            put("credential_id", "cred-1")
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
