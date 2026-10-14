package dev.hryshyn.remanence.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.recovery.PasskeyRecovery
import dev.hryshyn.remanence.recovery.RecoveryCredentialPort
import dev.hryshyn.remanence.session.SessionState
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * T191 actual key-readiness setup through a virtual-credential test
 * client (device only — root runs it, no JVM claim). Establishes REAL
 * verified readiness on the SAME saved R33 account/keybundle via the
 * existing [PasskeyRecovery.protect] path and the ordinary session
 * bootstrap — no signup/login/account reset/new identity, no direct
 * `markVerified`, no seeded record/keys:
 *
 * isolated-build guard (pending + recovery flags, exact package,
 * exact loopback epoch) → expected owner/bundle UUID args → persisted
 * account/bundle/handle + Available wrapped identity → ordinary
 * bootstrap (Active, or exactly-matching RecoverySetupRequired as the
 * legitimate setup-screen state only) → held live owner/session lease
 * + rechecked account/identity → quiet local-shim credential port
 * (REAL Chromium virtual authenticator through the localhost client
 * shim at 127.0.0.1:18044 — NOT Credential Manager/GPM, no native/
 * physical/provider-sync claim) → real protect (verified credential,
 * local wrapping, signed record, upload, server readback+unwrap;
 * returned recovery code wiped immediately) → ordinary `isReady`
 * receipt check (nonsecret) → ordinary bootstrap MUST yield exact
 * Active.
 *
 * No send/proof/claim/alias/scan/render/retire, no admitted rows, no
 * key/credential private/PRF/code files. Interruptions preserve
 * ordinary enrollment/account with no delete/reset/retry loop.
 * Failures name only coarse phases (never bodies, secrets, URLs, or
 * exception text).
 */
@RunWith(AndroidJUnit4::class)
class PendingNativeRecoverySetupTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as RemanenceApplication).container

    private fun stringArgument(name: String): String? = try {
        InstrumentationRegistry.getArguments().getString(name)
    } catch (_: Exception) {
        null
    }

    /**
     * ORIGINAL-lease fence (ingest-txn pattern, never fresh recapture):
     * the held lease must still be live and still bound to the same
     * owner. Throws — never a boolean a caller could ignore.
     */
    private fun checkLease(lease: SessionRequestLease, ownerUserId: String) {
        val live = try {
            container.pendingSessionGate.isLive(lease)
        } catch (_: Exception) {
            false
        }
        val leaseOwner = try {
            lease.ownerUserId.toRestString()
        } catch (_: Exception) {
            ""
        }
        if (!live || leaseOwner.isBlank() || leaseOwner != ownerUserId) {
            throw AssertionError("recovery session changed")
        }
    }

    /**
     * Test-only credential port speaking the fixed local-shim contract
     * (POST `/create` and `/get` with `{"options": ...}`, expecting
     * `{"credential": ...}`). Dedicated quiet client: bounded
     * timeouts, no interceptors, no logging, no auth headers, no
     * key export. The `credential` object passes through verbatim,
     * including its genuine nested PRF result under
     * `clientExtensionResults.prf.results.first` exactly when the
     * provider returned 32 bytes (T190 corrected contract) — never
     * hardcoded or fabricated here, and never stripped: production
     * `prf()` enforces presence. True cancellation aborts the socket
     * via the call handle; owned request/response/scratch buffers wipe
     * in `finally` (immutable JSON model copies cannot be wiped —
     * stated honestly).
     */
    private class QuietShimPort : RecoveryCredentialPort {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        override suspend fun create(options: JsonObject): JsonObject =
            ceremony("/create", options)

        override suspend fun get(options: JsonObject): JsonObject =
            ceremony("/get", options)

        private suspend fun ceremony(path: String, options: JsonObject): JsonObject =
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val requestBytes = buildJsonObject { put("options", options) }
                    .toString().toByteArray(Charsets.UTF_8)
                var responseBytes: ByteArray? = null
                val scratch = ByteArray(8192)
                try {
                    val request = Request.Builder()
                        .url("http://127.0.0.1:18044$path")
                        .post(requestBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    val call = client.newCall(request)
                    val cancelHook = currentCoroutineContext().job.invokeOnCompletion { cause ->
                        if (cause is CancellationException) runCatching { call.cancel() }
                    }
                    try {
                        val response = suspendCancellableCoroutine { cont ->
                            call.enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    // Cancellation may have already won; never
                                    // crash the callback thread.
                                    runCatching { cont.resumeWithException(e) }
                                }

                                override fun onResponse(call: Call, response: Response) {
                                    cont.resume(response) {
                                        try {
                                            response.close()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            })
                            cont.invokeOnCancellation {
                                runCatching { call.cancel() }
                            }
                        }
                        try {
                            if (!response.isSuccessful) {
                                throw AssertionError("credential shim unavailable")
                            }
                            currentCoroutineContext().ensureActive()
                            val bytes = response.body?.byteStream()?.use { stream ->
                                readBounded(stream, scratch)
                            } ?: throw AssertionError("credential shim invalid response")
                            responseBytes = bytes
                            val credential = try {
                                val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
                                (root as? JsonObject)?.get("credential") as? JsonObject
                            } catch (_: Exception) {
                                null
                            } ?: throw AssertionError("credential shim invalid response")
                            credential
                        } finally {
                            try {
                                response.close()
                            } catch (_: Exception) {
                            }
                        }
                    } finally {
                        cancelHook.dispose()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("credential shim unavailable")
                } finally {
                    requestBytes.fill(0)
                    responseBytes?.fill(0)
                    scratch.fill(0)
                }
            }

        private fun readBounded(stream: InputStream, scratch: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val read = stream.read(scratch)
                if (read == -1) break
                total += read
                if (total > MAX_SHIM_BODY_BYTES) {
                    throw AssertionError("credential shim response too large")
                }
                out.write(scratch, 0, read)
            }
            return out.toByteArray()
        }

        companion object {
            private const val MAX_SHIM_BODY_BYTES = 1_048_576L
        }
    }

    @Test
    fun existingAccountEstablishesVerifiedReadinessOverRealHttp(): Unit = runBlocking {
        withTimeout(300_000) {
            // 0. Isolated-build guard FIRST (before any container,
            // account, identity, or network touch): pending + recovery
            // flags, exact package, exact loopback epoch.
            if (!BuildConfig.REMANENCE_PENDING_ENABLED ||
                !BuildConfig.REMANENCE_RECOVERY_ENABLED ||
                context.packageName != "dev.hryshyn.remanence.pendingtest" ||
                BuildConfig.API_BASE_URL != "http://127.0.0.1:18043/"
            ) {
                throw AssertionError(
                    "missing prerequisite: isolated recovery build and loopback epoch required",
                )
            }
            val expectedOwner = stringArgument("t186_expected_owner")
            val expectedBundle = stringArgument("t186_expected_bundle")
            if (expectedOwner.isNullOrBlank() || expectedBundle.isNullOrBlank()) {
                throw AssertionError(
                    "missing prerequisite: expected owner/bundle arguments required",
                )
            }
            try {
                UUID.fromString(expectedOwner)
                UUID.fromString(expectedBundle)
            } catch (_: Exception) {
                throw AssertionError("missing prerequisite: expected arguments not UUIDs")
            }

            // 1. Persisted-state prerequisites (never signup, login,
            // seed, reset, or adopt — any gap fails closed here).
            val account = try {
                container.currentAccountStore.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw AssertionError("prerequisite account store unreadable")
            } ?: throw AssertionError("missing prerequisite: no persisted account")
            if (account.userId != expectedOwner || account.activeKeyBundleId != expectedBundle) {
                throw AssertionError("missing prerequisite: persisted account is not expected")
            }
            if (account.handle.isNullOrBlank()) {
                throw AssertionError("missing prerequisite: persisted account has no handle")
            }
            when (
                val loaded = try {
                    container.identityRepository.load()
                } catch (_: Exception) {
                    null
                }
            ) {
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                    Unit
                else -> throw AssertionError("missing prerequisite: no available identity")
            }

            // 2. Ordinary cold-start bootstrap (genuine refresh path).
            // Active proceeds; an exactly-matching RecoverySetupRequired
            // is the legitimate setup-screen state (never ready). All
            // other states fail closed — no token injection or bypass.
            val bootstrapped = withTimeout(60_000) {
                try {
                    container.sessionBootstrap.bootstrap()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("session bootstrap unavailable")
                }
            }
            when (bootstrapped) {
                is SessionState.Active -> {
                    if (bootstrapped.userId != expectedOwner ||
                        bootstrapped.activeKeyBundleId != expectedBundle
                    ) {
                        throw AssertionError("missing prerequisite: bootstrapped session is not expected")
                    }
                }
                is SessionState.RecoverySetupRequired -> {
                    if (bootstrapped.userId != expectedOwner ||
                        bootstrapped.bundleId != expectedBundle
                    ) {
                        throw AssertionError("missing prerequisite: setup session is not expected")
                    }
                }
                else -> throw AssertionError("missing prerequisite: session not ready for setup")
            }

            // 3. Held live owner/session lease + rechecked saved
            // account/identity after the suspensions above (no silent
            // recapture on changes — any drift fails closed).
            val lease = try {
                container.pendingSessionGate.capture()
            } catch (_: Exception) {
                null
            } ?: throw AssertionError("missing prerequisite: no live session lease")
            checkLease(lease, expectedOwner)
            val freshAccount = try {
                container.currentAccountStore.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw AssertionError("prerequisite account store unreadable")
            } ?: throw AssertionError("missing prerequisite: no persisted account")
            if (freshAccount.userId != expectedOwner ||
                freshAccount.activeKeyBundleId != expectedBundle
            ) {
                throw AssertionError("missing prerequisite: account changed during bootstrap")
            }
            when (
                val reloaded = try {
                    container.identityRepository.load()
                } catch (_: Exception) {
                    null
                }
            ) {
                is dev.hryshyn.remanence.core.crypto.IdentityBundleRepository.LoadResult.Available ->
                    Unit
                else -> throw AssertionError("missing prerequisite: no available identity")
            }

            // 4. REAL protect() through the virtual-authenticator port
            // (verified credential, local wrapping, signed record,
            // upload, server readback+unwrap). Returned recovery code
            // dies in `finally`; never stored or printed. Existing
            // records resume through production code (readback path
            // returns null — never replacement).
            var code: ByteArray? = null
            try {
                code = withTimeout(180_000) {
                    try {
                        PasskeyRecovery(container).protect(QuietShimPort())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("recovery protection unavailable")
                    }
                }
            } finally {
                code?.fill(0)
                code = null
            }
            checkLease(lease, expectedOwner)
                // 5. Ordinary nonsecret receipt, then ordinary bootstrap
                // MUST yield exact Active (owner/bundle/keysets/lease).
                if (!container.recoveryReadiness.isReady(expectedOwner, expectedBundle)) {
                    throw AssertionError("recovery receipt not recorded")
                }
                val confirmed = withTimeout(60_000) {
                    try {
                        container.sessionBootstrap.bootstrap()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("session bootstrap unavailable")
                    }
                }
                val active = when (confirmed) {
                    is SessionState.Active -> confirmed
                    else -> throw AssertionError("missing prerequisite: session not active")
                }
            if (active.userId != expectedOwner || active.activeKeyBundleId != expectedBundle) {
                throw AssertionError("missing prerequisite: bootstrapped session is not expected")
            }
            if (active.handle.isNullOrBlank()) {
                throw AssertionError("missing prerequisite: bootstrapped session has no handle")
            }
                if (!active.hasEncryptionKeyset || !active.hasSigningKeyset) {
                    throw AssertionError("missing prerequisite: confirmed session keys unusable")
                }
                val heldLease = active.requestLease
                    ?: throw AssertionError("missing prerequisite: confirmed session has no lease")
                checkLease(heldLease, expectedOwner)
                // The ORIGINAL held lease must still be live, and the
                // returned lease must equal it (owner + incarnation) —
                // no fresh-lease rescue after a rotation.
                checkLease(lease, expectedOwner)
                if (heldLease.ownerUserId != lease.ownerUserId ||
                    heldLease.incarnation != lease.incarnation
                ) {
                    throw AssertionError("sender session changed")
                }
            }
        }
    }
}
