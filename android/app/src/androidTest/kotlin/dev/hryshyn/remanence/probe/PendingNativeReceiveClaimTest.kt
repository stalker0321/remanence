package dev.hryshyn.remanence.probe

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprintCodec
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.LocalMatchEngine
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.core.recognition.ScanFlowResult
import dev.hryshyn.remanence.core.recognition.VisualScanResult
import dev.hryshyn.remanence.pending.PendingAdmittedPhotosReader
import dev.hryshyn.remanence.pending.PendingCodeNormalizer
import dev.hryshyn.remanence.pending.PendingProofViewModel
import dev.hryshyn.remanence.session.SessionState
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * T194 one real native receipt/claim/admission/scan/photo-reader driver
 * (device only — root runs it, no JVM claim). Genuine isolated receiver
 * leg over real HTTP against the isolated dev epoch, using the
 * production container wiring end to end:
 *
 * isolated-build guard → expected owner/bundle/capsule/delivery/locator
 * args → persisted expected account/bundle/handle + `Available` wrapped
 * identity → ordinary bootstrap (exact `Active`) → durable one-shot
 * attempt marker (test-owned app-private filesDir, BEFORE any receiver
 * network effect; a pre-existing marker refuses replay) → real
 * `newPendingProofViewModel` flow (`start` locator reference →
 * `ProofRequired` with the expected delivery → exact reconstructed
 * destination → explicit alias opt-in → `requestAttempt` →
 * `CodeRequired`) → fixed quiet synthetic mailbox client (frozen
 * single-field OTP wire) → `onCodeChange` → `checkCode` →
 * `IntentReady` (code input cleared) → `submitClaim` →
 * `Claimed(serviceVerified=true)` → admitted row with staged FRONT →
 * real bridge load/verify → real `LocalMatchEngine` over the published
 * FRONT → real `PresentationGrantAuthority` grant (verified first,
 * resolved live) → real `openPendingAdmittedPhotos` +
 * `PendingAdmittedPhotosReader` (decodable photos, note/admission).
 *
 * READER-LEVEL evidence ONLY: no `CapsuleRoute` render, no R15/retire
 * notification here; the locator stays active for a later supported UI
 * presentation. Honest limits: no OTP/proof bypass (the bridge only
 * returns the stored code; the REAL R9 verifies), no fake verifier or
 * grant, no admitted-row fabrication. Failures name only coarse phases
 * (never tokens, codes, keys, bodies, or URLs). Each run uses the
 * root-supplied expected ids; a pre-existing local marker or account
 * mismatch is an explicit missing prerequisite, never overwritten.
 */
@RunWith(AndroidJUnit4::class)
class PendingNativeReceiveClaimTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as RemanenceApplication).container

    private fun stringArgument(name: String): String? = try {
        InstrumentationRegistry.getArguments().getString(name)
    } catch (_: Exception) {
        null
    }

    /**
     * ORIGINAL-lease fence (ingest-txn pattern, never fresh recapture):
     * the lease captured at entry must still be live and still bound to
     * the same owner. Throws — never a boolean a caller could ignore.
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
            throw AssertionError("receiver session changed")
        }
    }

    private fun markerFile(): File {
        val parent = context.filesDir
        val dir = File(parent, "pendingtest-receipt")
        if (!dir.isDirectory) {
            if (!dir.mkdirs() || !dir.isDirectory) {
                throw AssertionError("receipt marker not durable")
            }
            syncDir(parent)
        }
        return File(dir, "receipt.json")
    }

    /**
     * Durable marker write (explicit file sync + directory-entry sync
     * around an atomic tmp+rename): PUBLIC opaque fields only
     * (capsule/delivery/phase). A failed rename leaves the temp file in
     * place as diagnostics (never deleted — no delete-to-rerun) and
     * fails closed. Never codes, contacts, tokens, keys, or OTP.
     */
    private fun writeMarker(capsuleId: String, deliveryId: String, stage: String) {
        val json = JSONObject()
            .put("capsuleId", capsuleId)
            .put("deliveryId", deliveryId)
            .put("stage", stage)
        val marker = markerFile()
        val dir = marker.parentFile
            ?: throw AssertionError("receipt marker not durable")
        val tmp = File(dir, "receipt.json.tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            syncDir(dir)
            if (!tmp.renameTo(marker)) {
                throw AssertionError("receipt marker not durable")
            }
            syncDir(dir)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AssertionError("receipt marker not durable")
        }
    }

    /** Directory-entry durability for the atomic rename above. */
    private fun syncDir(dir: File) {
        try {
            val fd = android.system.Os.open(
                dir.absolutePath,
                android.system.OsConstants.O_RDONLY,
                0,
            )
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AssertionError("receipt marker not durable")
        }
    }

    /**
     * Fixed quiet synthetic mailbox client for the frozen single-field
     * OTP wire (T195 contract): POST `http://127.0.0.1:18045/otp` with
     * `{"attemptId": "<R8-public-UUID>"}` and expect
     * `{"code": "<six ASCII digits>"}` — nothing else is sent or read.
     * The bridge only returns the stored code; the REAL R9 verifies.
     * Dedicated quiet client: bounded timeouts, chunked size-capped
     * body reads, no interceptors, no logging, no auth headers.
     * Cancellation aborts the socket via the call handle; owned
     * request/response/scratch buffers wipe in `finally` (immutable
     * JSON model copies cannot be wiped — stated honestly).
     */
    private class QuietMailboxPort {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        // Narrow test-only opt-in: only the `onCancelling` overload
        // fires during cancellation (the default waits for completion,
        // which a socket-blocked coroutine never reaches promptly).
        @OptIn(InternalCoroutinesApi::class)
        suspend fun fetchCode(attemptId: String): String =
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val requestBytes = buildJsonObject { put("attemptId", attemptId) }
                    .toString().toByteArray(Charsets.UTF_8)
                var responseBytes: ByteArray? = null
                val scratch = ByteArray(8192)
                try {
                    val request = Request.Builder()
                        .url("http://127.0.0.1:18045/otp")
                        .post(requestBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    val call = client.newCall(request)
                    val cancelHook = currentCoroutineContext().job.invokeOnCompletion(
                        onCancelling = true,
                    ) { cause ->
                        if (cause is CancellationException) runCatching { call.cancel() }
                    }
                    try {
                        val response = suspendCancellableCoroutine { cont ->
                            call.enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
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
                                throw AssertionError("mailbox client unavailable")
                            }
                            currentCoroutineContext().ensureActive()
                            val bytes = response.body?.byteStream()?.use { stream ->
                                readBounded(stream, scratch)
                            } ?: throw AssertionError("mailbox client invalid response")
                            responseBytes = bytes
                            val code = try {
                                val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
                                (root as? JsonObject)?.get("code")?.jsonPrimitive?.content
                            } catch (_: Exception) {
                                null
                            }
                            if (code == null || code.length != 6 ||
                                !code.all { it in '0'..'9' }
                            ) {
                                throw AssertionError("mailbox client invalid response")
                            }
                            code
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
                    currentCoroutineContext().ensureActive()
                    throw AssertionError("mailbox client unavailable")
                } finally {
                    requestBytes.fill(0)
                    responseBytes?.fill(0)
                    scratch.fill(0)
                }
            }

        /**
         * Bounded collector over a fixed buffer (no growable backing
         * whose copies escape wiping): partial content is zeroized in
         * `finally` on success, overflow, and read errors alike.
         */
        private fun readBounded(stream: InputStream, scratch: ByteArray): ByteArray {
            val out = ByteArray(MAX_MAILBOX_BODY_BYTES.toInt())
            var total = 0
            try {
                while (true) {
                    val read = stream.read(scratch)
                    if (read == -1) break
                    if (total.toLong() + read > MAX_MAILBOX_BODY_BYTES) {
                        throw AssertionError("mailbox client response too large")
                    }
                    System.arraycopy(scratch, 0, out, total, read)
                    total += read
                }
                return out.copyOf(total)
            } finally {
                out.fill(0)
            }
        }

        companion object {
            private const val MAX_MAILBOX_BODY_BYTES = 65_536L
        }
    }

    @Test
    fun receiverClaimAdmitsMatchOpensReader(): Unit = runBlocking {
        withTimeout(900_000) {
            // 0. Isolated-build guard FIRST (before any container,
            // account, identity, or network touch).
            if (!BuildConfig.REMANENCE_PENDING_ENABLED ||
                context.packageName != "dev.hryshyn.remanence.pendingtest" ||
                BuildConfig.API_BASE_URL != "http://127.0.0.1:18042/"
            ) {
                throw AssertionError(
                    "missing prerequisite: isolated pendingtest build and loopback epoch required",
                )
            }
            val expectedOwner = stringArgument("t186_expected_owner")
            val expectedBundle = stringArgument("t186_expected_bundle")
            val expectedCapsule = stringArgument("t194_capsule_id")
            val expectedDelivery = stringArgument("t194_delivery_id")
            val expectedLocator = stringArgument("t194_locator_code")
            if (expectedOwner.isNullOrBlank() || expectedBundle.isNullOrBlank() ||
                expectedCapsule.isNullOrBlank() || expectedDelivery.isNullOrBlank() ||
                expectedLocator.isNullOrBlank()
            ) {
                throw AssertionError(
                    "missing prerequisite: expected owner/bundle/capsule/delivery/locator arguments required",
                )
            }
            val capsuleUuid: UUID
            try {
                UUID.fromString(expectedOwner)
                UUID.fromString(expectedBundle)
                capsuleUuid = UUID.fromString(expectedCapsule)
                UUID.fromString(expectedDelivery)
            } catch (_: Exception) {
                throw AssertionError("missing prerequisite: expected arguments not UUIDs")
            }
            if (expectedLocator.length != PendingCodeNormalizer.CODE_LENGTH ||
                !expectedLocator.all { it in PendingCodeNormalizer.ALPHABET }
            ) {
                throw AssertionError("missing prerequisite: expected locator malformed")
            }
            val ownerUser = UserId(UUID.fromString(expectedOwner))

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
            // Only an exact Active state proceeds — anything else is a
            // missing prerequisite, never a bypass.
            val bootstrapped = withTimeout(60_000) {
                try {
                    container.sessionBootstrap.bootstrap()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("session bootstrap unavailable")
                }
            }
            val active = when (bootstrapped) {
                is SessionState.Active -> bootstrapped
                else -> throw AssertionError("missing prerequisite: session not active")
            }
            if (active.userId != expectedOwner || active.activeKeyBundleId != expectedBundle) {
                throw AssertionError("missing prerequisite: bootstrapped session is not expected")
            }
            if (active.handle.isNullOrBlank()) {
                throw AssertionError("missing prerequisite: bootstrapped session has no handle")
            }
            if (!active.hasEncryptionKeyset || !active.hasSigningKeyset) {
                throw AssertionError("missing prerequisite: bootstrapped session keys unusable")
            }
            val lease = active.requestLease
                ?: throw AssertionError("missing prerequisite: bootstrapped session has no lease")
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

            // 3. Durable one-shot attempt marker BEFORE any receiver
            // network effect. A pre-existing marker refuses replay (no
            // deletion, no auto-retry — root decides deliberately).
            // PUBLIC opaque fields only: capsule/delivery/phase.
            val marker = markerFile()
            if (marker.isFile) {
                throw AssertionError("prior receipt attempt already recorded; refusing replay")
            }
            val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                writeMarker(expectedCapsule, expectedDelivery, "attempted")
                checkLease(lease, expectedOwner)

                // 4. Real proof flow over ALL production ports: R6
                // reference → ProofRequired (expected delivery) →
                // exact reconstructed destination → explicit alias
                // opt-in → requestAttempt → CodeRequired.
                val vm = container.newPendingProofViewModel(vmScope)
                vm.start(expectedLocator)
                val required = withTimeout(120_000) {
                    try {
                        vm.state.first {
                            it is PendingProofViewModel.ProofState.ProofRequired ||
                                it is PendingProofViewModel.ProofState.Failed
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("receiver lookup unavailable")
                    }
                }
                if (required !is PendingProofViewModel.ProofState.ProofRequired) {
                    throw AssertionError("receiver lookup failed")
                }
                assertEquals(expectedDelivery, required.deliveryId)
                // T192 deterministic recipient (reconstructed, never
                // decrypted from storage).
                val destination = "t186recv" + expectedCapsule.replace("-", "") + "@example.com"
                vm.onDestinationChange(destination)
                vm.onAliasConsentChange(true)
                vm.requestAttempt()
                val codeRequired = withTimeout(120_000) {
                    try {
                        vm.state.first {
                            it is PendingProofViewModel.ProofState.CodeRequired ||
                                it is PendingProofViewModel.ProofState.Failed
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("receiver attempt unavailable")
                    }
                }
                if (codeRequired !is PendingProofViewModel.ProofState.CodeRequired) {
                    throw AssertionError("receiver attempt failed")
                }
                checkLease(lease, expectedOwner)

                // 5. Fixed single-field OTP wire, then the ordinary code
                // events. The bridge only returns the stored code; the
                // REAL R9 verifies. Input cleared once verified.
                var code: String? = null
                try {
                    code = withTimeout(120_000) {
                        try {
                            QuietMailboxPort().fetchCode(codeRequired.attemptId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            throw AssertionError("mailbox client unavailable")
                        }
                    }
                    vm.onCodeChange(code)
                    vm.checkCode()
                    val ready = withTimeout(120_000) {
                        try {
                            vm.state.first {
                                it is PendingProofViewModel.ProofState.IntentReady ||
                                    it is PendingProofViewModel.ProofState.Failed
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            throw AssertionError("receiver code check unavailable")
                        }
                    }
                    if (ready !is PendingProofViewModel.ProofState.IntentReady) {
                        throw AssertionError("receiver code check failed")
                    }
                    vm.onCodeChange("")
                } finally {
                    code = null
                }
                checkLease(lease, expectedOwner)

                // 6. Real R11 submit → Claimed(serviceVerified=true).
                vm.submitClaim()
                val claimed = withTimeout(180_000) {
                    try {
                        vm.state.first {
                            it is PendingProofViewModel.ProofState.Claimed ||
                                it is PendingProofViewModel.ProofState.Failed
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("receiver claim unavailable")
                    }
                }
                if (claimed !is PendingProofViewModel.ProofState.Claimed) {
                    throw AssertionError("receiver claim failed")
                }
                assertEquals(expectedDelivery, claimed.deliveryId)
                assertTrue(claimed.serviceVerified)
                checkLease(lease, expectedOwner)
                writeMarker(expectedCapsule, expectedDelivery, "claimed")

                // 7. Durable admitted row with staged FRONT (sealed bytes
                // only — never plaintext or keys).
                val row = try {
                    container.pendingAdmittedGiftStore.get(expectedOwner, expectedCapsule)
                } catch (_: Exception) {
                    null
                } ?: throw AssertionError("admitted row missing")
                assertEquals(expectedDelivery, row.deliveryId)
                assertTrue(row.recognitionCiphertextB64.isNotBlank())

                // 8. Real scan bridge + real matcher over the published
                // FRONT, then a real verified grant (verified first via
                // the bridge, resolved live — never unconditional, never
                // a fake-true verifier).
                val authority = container.presentationGrants
                val frontBytes = CanonicalFrontFixture.distinctFront(11)
                val query = try {
                    SiftRootSiftFingerprintCodec.parse(frontBytes)
                } catch (_: Exception) {
                    throw AssertionError("receiver FRONT framing invalid")
                }
                try {
                    val candidates = try {
                        container.pendingScanBridge.load(expectedOwner).candidates
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("receiver candidate enumeration unavailable")
                    }
                    assertTrue(candidates.any { it.capsuleId == capsuleUuid })
                    val ownerUser = UserId(UUID.fromString(expectedOwner))
                    val engine = LocalMatchEngine(
                        profile = RecognitionProfile.postcardSiftRootSiftV1(),
                        verifier = { id -> container.pendingScanBridge.verify(expectedOwner, id) },
                        grantIssuer = { id ->
                            authority.issue(ownerUser, id, CapsulePresentationSource.PENDING, 1).grantId.toString()
                        },
                    )
                    val visual = try {
                        engine.evaluateVisual(query, candidates)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("receiver visual match unavailable")
                    }
                    if (visual !is VisualScanResult.Accepted) {
                        throw AssertionError("receiver visual match not accepted")
                    }
                    val decided = try {
                        engine.completeVisual(visual)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("receiver grant unavailable")
                    }
                    val granted = decided as? ScanFlowResult.Granted
                        ?: throw AssertionError("receiver grant not issued")
                    assertEquals(capsuleUuid, granted.capsuleId)
                    val grantId = try {
                        UUID.fromString(granted.grantId)
                    } catch (_: Exception) {
                        throw AssertionError("receiver grant unresolvable")
                    }
                    val binding = try {
                        authority.resolve(grantId, ownerUser)
                    } catch (_: Exception) {
                        null
                    } ?: throw AssertionError("receiver grant not live")
                    assertEquals(capsuleUuid, binding.capsuleId)
                    try {
                        // 9. Real open + reader (READER-LEVEL evidence
                        // only — no CapsuleRoute render, no R15/retire;
                        // the locator stays active).
                        val opened = withTimeout(180_000) {
                            try {
                                container.openPendingAdmittedPhotos(expectedOwner, expectedCapsule)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                throw AssertionError("receiver open unavailable")
                            }
                        }
                        checkLease(lease, expectedOwner)
                        val reader = PendingAdmittedPhotosReader(expectedCapsule, opened)
                        assertEquals(3, reader.photoCount(expectedCapsule))
                        for (ordinal in 0 until 3) {
                            val jpeg = reader.loadPhoto(expectedCapsule, ordinal).jpegBytes
                            var bitmap: android.graphics.Bitmap? = null
                            try {
                                bitmap = BitmapFactory.decodeByteArray(
                                    jpeg, 0, jpeg.size,
                                )
                                assertTrue(
                                    bitmap != null && bitmap.width > 0 && bitmap.height > 0,
                                )
                            } finally {
                                bitmap?.recycle()
                            }
                        }
                        reader.noteText(expectedCapsule)
                        val admission = try {
                            reader.presentationAdmission(expectedCapsule)
                        } catch (_: Exception) {
                            throw AssertionError("receiver admission unavailable")
                        }
                        assertTrue(admission is CapsulePresentationAdmission.Ber1)
                        writeMarker(expectedCapsule, expectedDelivery, "opened")
                    } finally {
                        try {
                            authority.revoke(grantId)
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    try {
                        query.wipe()
                    } catch (_: Exception) {
                    }
                    frontBytes.fill(0)
                }
            } finally {
                try {
                    vmScope.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }
}
