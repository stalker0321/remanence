package dev.hryshyn.remanence.probe

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.BuildConfig
import dev.hryshyn.remanence.RemanenceApplication
import dev.hryshyn.remanence.capture.CaptureAttemptPhase
import dev.hryshyn.remanence.capture.CaptureDisplayDecoder
import dev.hryshyn.remanence.capture.CapturePermissionStep
import dev.hryshyn.remanence.capture.ProcessedStill
import dev.hryshyn.remanence.capture.StillProcessor
import dev.hryshyn.remanence.core.crypto.IdentityBundleRepository
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.pending.PendingAdmittedContent
import dev.hryshyn.remanence.pending.PendingAdmittedPhotosReader
import dev.hryshyn.remanence.pending.PendingCodeNormalizer
import dev.hryshyn.remanence.pending.PendingFeatureGate
import dev.hryshyn.remanence.session.RootViewModel
import dev.hryshyn.remanence.session.SessionState
import dev.hryshyn.remanence.ui.capsule.CapsuleContentBinding
import dev.hryshyn.remanence.ui.capsule.CapsuleRoute
import dev.hryshyn.remanence.ui.create.SenderIdentitySnapshot
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.navigation.AuthUiState
import dev.hryshyn.remanence.ui.scan.ScanTerminalState
import dev.hryshyn.remanence.ui.scan.ScanViewModel
import dev.hryshyn.remanence.ui.scan.plus
import dev.hryshyn.remanence.wiring.RemanenceViewModelFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * T199 bounded presentation driver for the SAME already admitted gift
 * (R42 reader PASS): real ScanViewModel (approved input seam only) ->
 * real RootViewModel binding -> real CapsuleRoute, then the real
 * background notify pipeline proceeds normally (never awaited).
 *
 * No receiver/proof/claim/OTP rerun: no R6/proof/mailbox ports are
 * touched here; the existing receiver test is left read-only. Fixed
 * test-only marker `pendingtest-receipt/receipt-r43-ui.json` (+ own
 * tmp) records coarse attempted/rendered phases only, after the R42
 * `opened` marker is validated read-only. All three older receipts
 * stay byte for byte.
 *
 * Production background notify/WorkManager behavior is preserved: the
 * route's onSupportedReady calls the real
 * container.notifySupportedPresentation (which persists + schedules
 * the real drain on its own scope); display never awaits any HTTP
 * acknowledgement, and no outbox/retirement state is asserted (a
 * transient snapshot may race a successful drain).
 */
@RunWith(AndroidJUnit4::class)
class PendingNativePresentationTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

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
            throw AssertionError("presentation session changed")
        }
    }

    private fun receiptDir(): File {
        val dir = File(context.filesDir, "pendingtest-receipt")
        if (!dir.isDirectory) {
            if (!dir.mkdirs() || !dir.isDirectory) {
                throw AssertionError("presentation marker not durable")
            }
        }
        return dir
    }

    /**
     * Durable marker write (explicit file sync + directory-entry sync
     * around an atomic tmp+rename). Fixed name only, PUBLIC opaque
     * fields (capsule/delivery/phase) — never codes, contacts,
     * tokens, keys, ciphertext, photo bytes, or grant IDs.
     */
    private fun writeR43Marker(capsuleId: String, deliveryId: String, stage: String) {
        val json = JSONObject()
            .put("capsuleId", capsuleId)
            .put("deliveryId", deliveryId)
            .put("stage", stage)
        val dir = receiptDir()
        val marker = File(dir, "receipt-r43-ui.json")
        val tmp = File(dir, marker.name + ".tmp")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            syncDir(dir)
            if (!tmp.renameTo(marker)) {
                throw AssertionError("presentation marker not durable")
            }
            syncDir(dir)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AssertionError("presentation marker not durable")
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
            throw AssertionError("presentation marker not durable")
        }
    }

    /**
     * R42 gate (READ-ONLY): the R42 marker must be a regular file whose
     * JSON holds exactly the expected PUBLIC capsule/delivery and stage
     * `opened`. Missing/malformed/wrong/earlier stages fail BEFORE any
     * scan or presentation. Never written or deleted — preserved byte
     * for byte with its tmp.
     */
    private fun requireR42OpenedMarker(expectedCapsule: String, expectedDelivery: String) {
        val marker = File(receiptDir(), "receipt-r42.json")
        if (!marker.isFile) {
            throw AssertionError("presentation needs the R42 opened marker")
        }
        val text = try {
            marker.readText(Charsets.UTF_8)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AssertionError("presentation needs the R42 opened marker")
        }
        val root = try {
            JSONObject(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw AssertionError("R42 marker is not the expected opened attempt")
        }
        if (root.optString("capsuleId") != expectedCapsule ||
            root.optString("deliveryId") != expectedDelivery ||
            root.optString("stage") != "opened"
        ) {
            throw AssertionError("R42 marker is not the expected opened attempt")
        }
    }

    /**
     * The ONE approved input seam (analogous to ScanGrantRoutingTest
     * FixedProcessor): fixed published FRONT bytes in, accepted framed
     * fingerprint out; the delivered bytes are ignored. The real SIFT
     * matcher, real pending verifier, and real grant path are untouched.
     */
    private class FixedFrontBytes : StillProcessor {
        override fun process(jpegBytes: ByteArray): ProcessedStill {
            val delivered = CanonicalFrontFixture.distinctFront(11)
            return ProcessedStill.Accepted(
                RecognitionProfile.postcardSiftRootSiftV1().profileId,
                delivered,
            )
        }
    }

    @Test
    fun admittedGiftRendersBoundCapsuleRoute(): Unit = runBlocking {
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
            // Real constructed-API check in addition to BuildConfig: the
            // container itself is built against the loopback epoch
            // (fail-closed in its own init before any client exists).
            if (container.apiBaseUrl.httpUrl.toString() != "http://127.0.0.1:18042/") {
                throw AssertionError(
                    "missing prerequisite: container is not built against the isolated epoch",
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
            try {
                UUID.fromString(expectedOwner)
                UUID.fromString(expectedBundle)
                UUID.fromString(expectedCapsule)
                UUID.fromString(expectedDelivery)
            } catch (_: Exception) {
                throw AssertionError("missing prerequisite: expected arguments not UUIDs")
            }
            // The locator is bound and shape-validated here but never
            // sent anywhere: T199 performs no receiver/proof/claim/OTP
            // rerun, so no port consumes it.
            if (expectedLocator.length != PendingCodeNormalizer.CODE_LENGTH ||
                !expectedLocator.all { it in PendingCodeNormalizer.ALPHABET }
            ) {
                throw AssertionError("missing prerequisite: expected locator malformed")
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
                is IdentityBundleRepository.LoadResult.Available ->
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
            if (!active.hasEncryptionKeyset || !active.hasSigningKeyset) {
                throw AssertionError("missing prerequisite: bootstrapped session keys unusable")
            }
            val lease = active.requestLease
                ?: throw AssertionError("missing prerequisite: bootstrapped session has no lease")
            checkLease(lease, expectedOwner)

            // 3. R42 gate + fresh R43 one-shot marker, BEFORE any scan or
            // presentation. All three older receipts stay byte for byte.
            requireR42OpenedMarker(expectedCapsule, expectedDelivery)
            val r43dir = receiptDir()
            val r43marker = File(r43dir, "receipt-r43-ui.json")
            if (r43marker.exists() || File(r43dir, r43marker.name + ".tmp").exists()) {
                throw AssertionError("presentation marker already present or partial; refusing replay")
            }

            // 4. Entry readiness: the SAME admitted gift must already be
            // present with staged FRONT (sealed bytes only — never
            // plaintext or keys). No receiver/proof/claim/OTP rerun.
            val row = try {
                container.pendingAdmittedGiftStore.get(expectedOwner, expectedCapsule)
            } catch (_: Exception) {
                null
            } ?: throw AssertionError("admitted row missing")
            assertEquals(expectedDelivery, row.deliveryId)
            assertTrue(row.recognitionCiphertextB64.isNotBlank())
            checkLease(lease, expectedOwner)

            // 5. Real RootViewModel through the production factory, with
            // a test-owned store so scopes cancel orderly. Auth arrives
            // via its actual lifecycle (init refresh -> bootstrap).
            val factory = RemanenceViewModelFactory(container)
            var vmStore: ViewModelStore? = null
            var scanStore: ViewModelStore? = null
            var rootVm: RootViewModel? = null
            var scanVm: ScanViewModel? = null
            // Caller-owned pending photos for the route lifetime (grant-
            // keyed single ref like MainActivity: recomposition can
            // neither reset nor leak the wipe; idempotent across close,
            // failure, disposal, and the test teardown below).
            val pendingOpened = AtomicReference<PendingAdmittedContent.OpenedPhotos?>(null)
            // Closed fence: once teardown starts disposing the route, an
            // in-flight open that returns late must wipe instead of adopt.
            val routeClosed = AtomicBoolean(false)
            val wipePendingPhotos = {
                pendingOpened.getAndSet(null)?.let { opened ->
                    opened.photosByOrdinal.values.forEach { it.fill(0) }
                }
                Unit
            }
            // Real background notify is observed, never awaited: display
            // must not wait for any retirement HTTP acknowledgement.
            val supportedNotified = AtomicBoolean(false)
            try {
                vmStore = ViewModelStore()
                val provider = ViewModelProvider(vmStore, factory)
                // Creation on Main like viewModel(): init refresh and
                // controller ownership start on the owner context.
                val root = withContext(Dispatchers.Main) {
                    provider.get(RootViewModel::class.java)
                }
                rootVm = root
                val authenticated = try {
                    withTimeout(120_000) {
                        root.authState.first { it is AuthUiState.Authenticated }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("root authentication unavailable")
                }
                val authed = authenticated as? AuthUiState.Authenticated
                    ?: throw AssertionError("root authentication unavailable")
                assertEquals(expectedOwner, authed.userId)
                checkLease(lease, expectedOwner)
                writeR43Marker(expectedCapsule, expectedDelivery, "attempted")

                // 6. Production-shaped ScanViewModel on the EXISTING
                // internal constructor: shared authority, production
                // identity/session boundary, real pending+incoming index
                // providers and trusted keys, real pending verifier,
                // default real SIFT matcher. ONLY the approved fixed-
                // FRONT input seam differs from the factory wiring.
                val cvReady = try {
                    OpenCVLoader.initDebug()
                } catch (_: Exception) {
                    false
                }
                if (!cvReady) {
                    throw AssertionError("opencv unavailable")
                }
                fun buildScanViewModel(): ScanViewModel {
                    return ScanViewModel(
                        persistence = container.fingerprintPersistence,
                    database = container.database,
                    profile = RecognitionProfile.postcardSiftRootSiftV1(),
                    identityProvider = {
                        val rowEntity = container.currentAccountStore.loadEntity()
                        val loadedKeys = try {
                            container.identityRepository.load()
                        } catch (_: Exception) {
                            null
                        }
                        if (rowEntity == null ||
                            loadedKeys !is IdentityBundleRepository.LoadResult.Available
                        ) {
                            null
                        } else {
                            SenderIdentitySnapshot(
                                userId = rowEntity.userId,
                                handle = rowEntity.handleNormalized,
                                activeKeyBundleId = rowEntity.activeKeyBundleId,
                                encryptionPrivateHandle = loadedKeys.encryptionHandle,
                                signingPrivateHandle = loadedKeys.signingHandle,
                            )
                        }
                    },
                    trustedSenderKeys = container.trustedSenderKeys,
                    presentationGrants = container.presentationGrants,
                    incomingPresentationPreparation = container.incomingPresentationPreparation,
                    candidateIndexProvider = { owner ->
                        val incoming = container.incomingSenderIndexCandidateProvider.load(owner)
                        if (!PendingFeatureGate.isEnabled()) {
                            incoming
                        } else {
                            incoming + container.pendingScanBridge.load(owner.toRestString())
                        }
                    },
                    observeIncomingIndexReadiness = { owner ->
                        container.database.incomingCapsuleDao()
                            .observeSenderIndexCandidateCountForOwner(owner.toRestString())
                    },
                    scheduleIncomingSync = { owner ->
                        container.scheduleIncomingSync(owner)
                    },
                    // Mirrors the factory connectivity wiring exactly
                    // (never the omitted always-true default).
                    networkConnected = {
                        val manager = container.appContext.getSystemService(
                            android.net.ConnectivityManager::class.java,
                        )
                        val network = manager?.activeNetwork
                        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
                        capabilities?.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET,
                        ) == true
                    },
                    sessionBoundary = container.sessionBoundary,
                    captureDisplayDecoder = CaptureDisplayDecoder { null },
                    frontProcessor = FixedFrontBytes(),
                    pendingVerifyCapsule = { userId, capsuleId ->
                        if (!PendingFeatureGate.isEnabled()) {
                            false
                        } else {
                            val current = try {
                                container.currentAccountStore.loadEntity()?.userId
                            } catch (_: Exception) {
                                null
                            }
                            if (current.isNullOrBlank() || current != userId) {
                                false
                            } else {
                                try {
                                    container.pendingScanBridge.verify(userId, capsuleId)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        }
                    },
                    )
                }
                // Test-owned lifecycle store via the existing provider
                // factory API (production ViewModels stay untouched):
                // clearing it runs ScanViewModel.onCleared (cancels work,
                // unregisters the session-boundary listener).
                val scanFactory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        buildScanViewModel() as T
                }
                val freshScanStore = ViewModelStore()
                scanStore = freshScanStore
                val scan = withContext(Dispatchers.Main) {
                    ViewModelProvider(freshScanStore, scanFactory).get(ScanViewModel::class.java)
                }
                scanVm = scan
                // Fresh scan-session epoch mirroring the root scan entry
                // bump; driven on the owner/UI context like production.
                val scanEpoch = root.scanSessionEpoch.value + 1
                withContext(Dispatchers.Main) {
                    scan.beginSession(scanEpoch)
                    val attemptCtl = scan.frontAttempt
                    if (attemptCtl.permission != CapturePermissionStep.Granted) {
                        attemptCtl.onPermissionResult(granted = true, canAskAgain = false)
                    }
                    assertEquals(CaptureAttemptPhase.Binding, attemptCtl.phase)
                    assertTrue(attemptCtl.onPreviewBound())
                    if (!scan.beginFrontCapture()) {
                        throw AssertionError("scan capture not ready")
                    }
                    // Published FRONT bytes in; the approved seam ignores
                    // the input and returns the fixed framed fingerprint.
                    // Ownership of this array transfers to the pipeline.
                    scan.deliverFrontJpeg(CanonicalFrontFixture.distinctFront(11))
                }
                checkLease(lease, expectedOwner)
                val terminal = try {
                    withTimeout(180_000) {
                        scan.terminal.first { it is ScanTerminalState.Granted }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw AssertionError("scan grant unavailable")
                }
                val granted = terminal as? ScanTerminalState.Granted
                    ?: throw AssertionError("scan grant unavailable")
                assertEquals(expectedCapsule, granted.capsuleId)
                assertTrue(granted.grantId.isNotBlank())
                checkLease(lease, expectedOwner)

                // 7. REAL root binder (the only binder): no direct
                // authority.issue, no hand-set destination, no fake
                // CapsuleAccess, no always-true validator, no stale
                // grant reuse.
                withContext(Dispatchers.Main) {
                    val bound = try {
                        root.openCapsuleWithGrant(granted.grantId, null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("root binding unavailable")
                    }
                    assertTrue(bound)
                    try {
                        root.requireLivePresentationGrant(granted.grantId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw AssertionError("presentation grant not live")
                    }
                }
                checkLease(lease, expectedOwner)

                // 8. Mount the REAL CapsuleRoute with the production PENDING
                // contentFactory shape from MainActivity: grant binding ->
                // openPendingAdmittedPhotos -> reader; live-grant
                // validation; revocations; idempotent close/failure/
                // release wipes; real notify on supported-ready.
                val grantId = granted.grantId
                rule.setContent {
                    HoldTheme {
                        CapsuleRoute(
                            grantId = grantId,
                            contentFactory = {
                                val binding = root.presentationGrantFor(grantId)
                                    ?: error("presentation grant is no longer live")
                                val capsuleId = binding.capsuleId.toString()
                                check(capsuleId == expectedCapsule) {
                                    "unexpected capsule binding"
                                }
                                val owner = (root.authState.value as? AuthUiState.Authenticated)?.userId
                                    ?: error("no authenticated local account")
                                val opened = try {
                                    container.openPendingAdmittedPhotos(owner, capsuleId)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error("pending capsule is no longer available")
                                }
                                // Closed fence: a late return landing after
                                // teardown started disposing must wipe
                                // instead of being adopted.
                                if (routeClosed.get()) {
                                    try {
                                        opened.photosByOrdinal.values.forEach { it.fill(0) }
                                    } catch (_: Exception) {
                                    }
                                    error("presentation closed during open")
                                }
                                pendingOpened.set(opened)
                                CapsuleContentBinding(
                                    capsuleId = capsuleId,
                                    reader = PendingAdmittedPhotosReader(capsuleId, opened),
                                )
                            },
                            validateLiveGrant = { root.requireLivePresentationGrant(grantId) },
                            revocations = root.capsuleRevocations,
                            onClose = {
                                wipePendingPhotos()
                                root.closeCapsule()
                            },
                            onFailure = {
                                wipePendingPhotos()
                                root.revokePresentationForRouteFailure(grantId)
                            },
                            onReleaseContent = {
                                wipePendingPhotos()
                            },
                            // Genuine supported-presentation hook, mirroring
                            // MainActivity: re-validates grant + owner, then
                            // the REAL container pipeline persists +
                            // schedules on its own scope. Display never
                            // waits for it; nothing here asserts outbox or
                            // HTTP state (a transient snapshot may race a
                            // successful drain).
                            onSupportedReady = { capsuleId ->
                                val owner = (root.authState.value as? AuthUiState.Authenticated)?.userId
                                if (owner != null) {
                                    var grantLive = false
                                    try {
                                        root.requireLivePresentationGrant(grantId)
                                        grantLive = true
                                    } catch (_: Exception) {
                                        grantLive = false
                                    }
                                    val pendingHash = when (
                                        root.presentationGrantFor(grantId)?.source
                                    ) {
                                        dev.hryshyn.remanence.ui.capsule.CapsulePresentationSource.PENDING ->
                                            try {
                                                container.pendingAdmittedGiftStore.get(owner, capsuleId)
                                                    ?.statementSha256Hex?.takeIf { it.isNotBlank() }
                                            } catch (_: Exception) {
                                                null
                                            }
                                        else -> null
                                    }
                                    container.notifySupportedPresentation(
                                        ownerUserId = owner,
                                        capsuleId = capsuleId,
                                        grantLive = grantLive,
                                        routeReady = true,
                                        supported = true,
                                        publicationHash = pendingHash,
                                    )
                                    supportedNotified.set(true)
                                }
                            },
                        )
                    }
                }
                // Actual manifest/render branch: Ber1 canvas or finished-
                // collage close (contract decides), then the shared photo
                // region. Tags assert visible content, not callbacks.
                try {
                    rule.waitUntil(120_000) {
                        val branchReady =
                            rule.onAllNodesWithTag("capsule_ber1_canvas").fetchSemanticsNodes().isNotEmpty() ||
                                rule.onAllNodesWithTag("capsule_collage_close").fetchSemanticsNodes().isNotEmpty()
                        branchReady &&
                            rule.onAllNodesWithTag("capsule_photo_region_0").fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (_: Exception) {
                    throw AssertionError("capsule route not ready")
                }
                rule.onNodeWithTag("capsule_photo_region_0").assertIsDisplayed()
                rule.runOnIdle {
                    assertTrue(supportedNotified.get())
                }
                checkLease(lease, expectedOwner)
                writeR43Marker(expectedCapsule, expectedDelivery, "rendered")

                // 9. Bounded capture window: the rendered route stays
                // mounted for root screenshot/UI-hierarchy capture using
                // the existing test facilities, then orderly teardown.
                delay(30_000)
            } finally {
                // Cancellation-safe orderly teardown on the owner
                // context: mark closed and dispose the mounted route
                // FIRST (its disposal effect closes the presentation and
                // releases content), then close/revoke, reset the scan
                // session, clear BOTH lifecycle stores
                // (ScanViewModel.onCleared cancels work and unregisters
                // its session-boundary listener), and sweep remaining
                // photo ownership last. Idempotent on every path,
                // including timeout/cancellation.
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        try {
                            routeClosed.set(true)
                        } catch (_: Exception) {
                        }
                        try {
                            rule.setContent { }
                        } catch (_: Exception) {
                        }
                        try {
                            rootVm?.closeCapsule()
                        } catch (_: Exception) {
                        }
                        try {
                            scanVm?.resetSession()
                        } catch (_: Exception) {
                        }
                        try {
                            vmStore?.clear()
                        } catch (_: Exception) {
                        }
                        try {
                            scanStore?.clear()
                        } catch (_: Exception) {
                        }
                        try {
                            wipePendingPhotos()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }
}
