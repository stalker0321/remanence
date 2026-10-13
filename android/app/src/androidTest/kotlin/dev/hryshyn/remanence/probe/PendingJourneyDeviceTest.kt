package dev.hryshyn.remanence.probe

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.ui.capsule.CapsuleContentBinding
import dev.hryshyn.remanence.ui.capsule.CapsuleContentReader
import dev.hryshyn.remanence.ui.capsule.CapsulePresentationAdmission
import dev.hryshyn.remanence.ui.capsule.CapsuleRoute
import dev.hryshyn.remanence.ui.capsule.DecryptedPhoto
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.pending.PendingCodeEntryButton
import dev.hryshyn.remanence.pending.PendingCodeEntrySheet
import dev.hryshyn.remanence.pending.PendingHandleSetupScreen
import dev.hryshyn.remanence.pending.PendingHandleSetupViewModel
import dev.hryshyn.remanence.pending.PendingProofFlow
import dev.hryshyn.remanence.pending.PendingProofViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T186 M5 native journey fixtures (device, root runs them; no JVM claim).
 *
 * Real components throughout: the pending code entry composables, the
 * handle gate notice, and the REAL [CapsuleRoute] state machine with a
 * decodable-JPEG reader. Honest limits: the route fixture uses a FAKE
 * reader plus no-op grant validation, so it proves UI-only behavior
 * (terminal states, callbacks, nonblocking display) — NOT full accepted
 * crypto, session, or production-equivalent proof. No auth session exists
 * in these tests and none is bypassed — the entry surfaces need no session
 * by design, and the route fixture never touches the application grant
 * authority. This is explicitly NOT Google physical proof (`.pendingtest`
 * OAuth/DAL are unregistered); no provider, no sender, no network.
 */
@RunWith(AndroidJUnit4::class)
class PendingJourneyDeviceTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = File(context.getExternalFilesDir(null), "pendingtest-fixture-evidence").apply { mkdirs() }

    private fun localized(tag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }

    private fun mountEntry(tag: String, content: @Composable () -> Unit) {
        val localized = localized(tag)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides localized) {
                HoldTheme { content() }
            }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun codeEntryEnglishOpensSheetAndBackReturns() {
        var opened = false
        mountEntry("en") {
            PendingCodeEntryButton(onOpenCodeEntry = { opened = true }, enabled = true)
        }
        rule.onNodeWithText("the card has a code").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(opened) }
        capture("pending-code-entry-en")
    }

    @Test fun codeEntrySheetBackNavigates() {
        var closed = false
        mountEntry("en") {
            // Real Compose state: closing recomposes the sheet away.
            var shown by remember { mutableStateOf(true) }
            if (shown) {
                PendingCodeEntrySheet(onClose = { shown = false; closed = true }, enabled = true)
            }
        }
        rule.onNodeWithText("enter the code from the card").assertIsDisplayed()
        capture("pending-code-sheet-en")
        rule.onNodeWithTag("pending_code_cancel").performClick()
        rule.runOnIdle { assertTrue(closed) }
        rule.onNodeWithTag("pending_code_sheet").assertDoesNotExist()
    }

    @Test fun codeEntryRussianLabel() {
        mountEntry("ru") {
            PendingCodeEntryButton(onOpenCodeEntry = {}, enabled = true)
        }
        rule.onNodeWithText("На открытке есть код").assertIsDisplayed()
        rule.onNodeWithText("the card has a code").assertDoesNotExist()
        capture("pending-code-entry-ru")
    }

    @Test fun codeEntryUkrainianLabel() {
        mountEntry("uk") {
            PendingCodeEntryButton(onOpenCodeEntry = {}, enabled = true)
        }
        rule.onNodeWithText("На листівці є код").assertIsDisplayed()
        capture("pending-code-entry-uk")
    }

    @Test fun malformedCodeShowsGenericErrorOnly() {
        mountEntry("en") {
            PendingCodeEntrySheet(onClose = {}, enabled = true)
        }
        rule.onNodeWithTag("pending_code_field").performTextInput("?????")
        rule.onNodeWithTag("pending_code_continue").performClick()
        // Generic error, never an existence oracle; no lookup attempted.
        rule.onNodeWithTag("pending_code_error").assertIsDisplayed()
        rule.onNodeWithTag("pending_code_unavailable").assertDoesNotExist()
        capture("pending-code-error-en")
    }

    @Test fun wellFormedCodeStaysFailClosed() {
        var normalized: String? = null
        mountEntry("en") {
            PendingCodeEntrySheet(onClose = {}, onWellFormedCode = { normalized = it }, enabled = true)
        }
        rule.onNodeWithTag("pending_code_field").performTextInput("a3k9q")
        rule.onNodeWithTag("pending_code_continue").performClick()
        // Normalized per the frozen contract, but no lookup path wired:
        // the unavailable note shows and the sheet stays (no fake opening).
        rule.runOnIdle { assertEquals("A3K9Q", normalized) }
        rule.onNodeWithTag("pending_code_unavailable").assertIsDisplayed()
        rule.onNodeWithTag("pending_code_sheet").assertIsDisplayed()
        capture("pending-code-unavailable-en")
    }

    @Test fun lookupFoundShowsServerHint() {
        mountEntry("en") {
            PendingCodeEntrySheet(
                onClose = {},
                enabled = true,
                lookup = {
                    dev.hryshyn.remanence.core.data.network.GiftLookupResult.Found(
                        giftReference = "0198f0a0-0000-7000-8000-00000000ab01",
                        channel = "email",
                        hint = "a•••@•••",
                    )
                },
            )
        }
        rule.onNodeWithTag("pending_code_field").performTextInput("a3k9q")
        rule.onNodeWithTag("pending_code_continue").performClick()
        // The server's minimal hint shows verbatim; continue journey (R7)
        // is disclosed as pending — never a fake opening.
        rule.onNodeWithText("a•••@•••").assertIsDisplayed()
        rule.onNodeWithTag("pending_code_found_next").assertIsDisplayed()
        rule.onNodeWithTag("pending_code_lookup_error").assertDoesNotExist()
        capture("pending-code-found-en")
    }

    @Test fun lookupFailureStaysGeneric() {
        mountEntry("en") {
            PendingCodeEntrySheet(
                onClose = {},
                enabled = true,
                lookup = {
                    dev.hryshyn.remanence.core.data.network.GiftLookupResult.Failure(
                        dev.hryshyn.remanence.core.data.network.GiftLookupFailure.GIFT_UNAVAILABLE,
                        httpStatus = 404,
                        retryable = false,
                    )
                },
            )
        }
        rule.onNodeWithTag("pending_code_field").performTextInput("a3k9q")
        rule.onNodeWithTag("pending_code_continue").performClick()
        // Generic message, no existence oracle beyond the frozen shape.
        rule.onNodeWithTag("pending_code_lookup_error").assertIsDisplayed()
        rule.onNodeWithTag("pending_code_found").assertDoesNotExist()
        capture("pending-code-notfound-en")
    }

    @Test fun handleSetupBackFires() {
        var backed = false
        val viewModel = PendingHandleSetupViewModel(
            r17 = dev.hryshyn.remanence.core.data.network.HandleCommitPort { _, _ ->
                error("no network in this fixture")
            },
            loadAccount = { null },
            captureLease = { null },
            tokenFor = { null },
            leaseLive = { false },
            recordHandle = { _, _ -> },
        )
        mountEntry("en") {
            PendingHandleSetupScreen(viewModel = viewModel, onBack = { backed = true }, enabled = true)
        }
        rule.onNodeWithTag("pending_handle_setup").assertIsDisplayed()
        capture("pending-handle-setup-en")
        rule.onNodeWithTag("pending_handle_setup_back").performClick()
        rule.runOnIdle { assertTrue(backed) }
    }

    @Test fun handleSetupInvalidShowsGenericError() {
        val viewModel = PendingHandleSetupViewModel(
            r17 = dev.hryshyn.remanence.core.data.network.HandleCommitPort { _, _ ->
                error("no network in this fixture")
            },
            loadAccount = { null },
            captureLease = { null },
            tokenFor = { null },
            leaseLive = { false },
            recordHandle = { _, _ -> },
        )
        mountEntry("en") {
            PendingHandleSetupScreen(viewModel = viewModel, onBack = {}, enabled = true)
        }
        // Client-side validation only: no network touched for bad input.
        rule.onNodeWithTag("pending_handle_setup_field").performTextInput("??")
        rule.onNodeWithTag("pending_handle_setup_continue").performClick()
        rule.onNodeWithTag("pending_handle_setup_error").assertIsDisplayed()
        capture("pending-handle-setup-invalid-en")
    }

    /** Decodable-JPEG reader so the Ready path renders real pixels. */   private class FakePhotos : CapsuleContentReader {
        override suspend fun photoCount(capsuleId: String): Int = 3
        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff336699.toInt() + ordinal)
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.toByteArray()
            }
            bitmap.recycle()
            return DecryptedPhoto(ordinal, bytes)
        }
        override suspend fun noteText(capsuleId: String): String? = "hello"
        override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission =
            CapsulePresentationAdmission.LegacyV1
    }

    @Test fun readyDisplayNeverWaitsForRetirement() {
        var retireSignalled = false
        var closed = false
        rule.setContent {
            HoldTheme {
                // UI-only fixture: FAKE reader + no-op grant validation.
                // Proves terminal display states and callback timing — not
                // crypto, session, or production proof (see class KDoc).
                CapsuleRoute(
                    grantId = "fixture-grant",
                    contentFactory = { CapsuleContentBinding("fixture-capsule", FakePhotos()) },
                    validateLiveGrant = {},
                    revocations = MutableSharedFlow<String>(),
                    onClose = { closed = true },
                    // Production-equivalent hook: the container persists +
                    // schedules here WITHOUT gating the viewer below.
                    onSupportedReady = { retireSignalled = true },
                )
            }
        }
        // Real content is on screen (decodable pixels, indicator)...
        rule.onNodeWithTag("capsule_page_indicator").assertIsDisplayed()
        capture("pending-ready-display")
        // ...and the retirement signal fired in the background without any
        // wait, tap, or second step from the viewer.
        rule.runOnIdle { assertTrue(retireSignalled) }
        assertFalse(closed)
    }

    private fun proofVm(
        continueResult: dev.hryshyn.remanence.core.data.network.ContinueResult,
    ): PendingProofViewModel = PendingProofViewModel(
        continuePort = dev.hryshyn.remanence.pending.ProofContinuePort { _, _ -> continueResult },
        verifyPort = object : dev.hryshyn.remanence.pending.ProofVerifyPort {
            override suspend fun request(
                deliveryId: String, destination: String, smsConsent: Boolean,
                nowSeconds: Long, idempotencyKey: String, token: String,
            ): dev.hryshyn.remanence.core.data.network.VerificationResult =
                error("no verification in this fixture")

            override suspend fun check(
                deliveryId: String, attemptId: String, code: String, token: String,
            ): dev.hryshyn.remanence.core.data.network.VerificationResult =
                error("no verification in this fixture")
        },
        optionsPort = dev.hryshyn.remanence.pending.ProofOptionsPort { _, _ ->
            error("no options in this fixture")
        },
        sessionGate = object : dev.hryshyn.remanence.pending.PendingRetirementSessionGate {
            override fun capture(): dev.hryshyn.remanence.core.data.network.SessionRequestLease =
                dev.hryshyn.remanence.core.data.network.SessionRequestLease(
                    dev.hryshyn.remanence.core.model.UserId.parseRest("00000000-0000-0000-0000-000000000000"),
                    1L,
                )

            override fun accessTokenFor(
                lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
            ): String? = "token"

            override fun isLive(
                lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
            ): Boolean = true
        },
        accountProvider = { null },
        signingKeyProvider = { null },
        hpkeKeyProvider = { null },
    )

    @Test fun proofSyncBranchShowsScanNote() {
        val viewModel = proofVm(
            dev.hryshyn.remanence.core.data.network.ContinueResult.Sync(
                "0198f0a0-0000-7000-8000-00000000ca01",
            ),
        )
        mountEntry("en") {
            PendingProofFlow(viewModel = viewModel, giftReference = "ref", onDone = {})
        }
        // Already owned: ordinary sync/scan note, no proof form.
        rule.onNodeWithTag("pending_proof_sync").assertIsDisplayed()
        rule.onNodeWithTag("pending_proof_done").performClick()
        capture("pending-proof-sync-en")
    }

    @Test fun proofForeignShowsGenericError() {
        val viewModel = proofVm(
            dev.hryshyn.remanence.core.data.network.ContinueResult.Failure(
                dev.hryshyn.remanence.core.data.network.GiftLookupFailure.GIFT_UNAVAILABLE,
                httpStatus = 404,
                retryable = false,
            ),
        )
        mountEntry("en") {
            PendingProofFlow(viewModel = viewModel, giftReference = "ref", onDone = {})
        }
        // Generic failure, never a proof branch for someone else.
        rule.onNodeWithTag("pending_proof_error").assertIsDisplayed()
        capture("pending-proof-error-en")
    }

    /**
     * T186 M11b (T188 F1): owner change across the sheet's IO hop
     * suppresses the publish — the live provider (auth state at publish
     * time) disagrees with the lookup-time owner. Same-owner control
     * publishes normally. Root runs these on device (r18).
     */
    @Test fun ownerChangeAcrossIoHopSuppressesPublish() {
        var liveOwner: String? = "0198f0a0-0000-7000-8000-000000000001"
        var published: String? = null
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = dev.hryshyn.remanence.pending.PendingGiftIntentStore(
            File(context.cacheDir, "pending-owner-race"),
        )
        mountEntry("en") {
            PendingCodeEntrySheet(
                onClose = {},
                onWellFormedCode = { published = it },
                enabled = true,
                lookup = {
                    entered.complete(Unit)
                    release.await()
                    dev.hryshyn.remanence.core.data.network.GiftLookupResult.Found(
                        giftReference = "0198f0a0-0000-7000-8000-00000000ab01",
                        channel = "email",
                        hint = "a•••@•••",
                    )
                },
                ownerUserId = "0198f0a0-0000-7000-8000-000000000001",
                giftIntentStore = store,
                currentOwnerProvider = { liveOwner },
            )
        }
        rule.onNodeWithTag("pending_code_field").performTextInput("a3k9q")
        rule.onNodeWithTag("pending_code_continue").performClick()
        kotlinx.coroutines.runBlocking { entered.await() }
        // Auth switches account while the save is in flight.
        liveOwner = "0198f0a0-0000-7000-8000-000000000002"
        release.complete(Unit)
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(published == null) }
        // The server hint still renders (lookup genuinely happened);
        // only the owner-gated publish is suppressed.
        rule.onNodeWithTag("pending_code_found").assertIsDisplayed()
    }

    @Test fun sameOwnerAcrossIoHopPublishes() {
        var published: String? = null
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = dev.hryshyn.remanence.pending.PendingGiftIntentStore(
            File(context.cacheDir, "pending-owner-stable"),
        )
        mountEntry("en") {
            PendingCodeEntrySheet(
                onClose = {},
                onWellFormedCode = { published = it },
                enabled = true,
                lookup = {
                    release.await()
                    dev.hryshyn.remanence.core.data.network.GiftLookupResult.Found(
                        giftReference = "0198f0a0-0000-7000-8000-00000000ab01",
                        channel = "email",
                        hint = "a•••@•••",
                    )
                },
                ownerUserId = "0198f0a0-0000-7000-8000-000000000001",
                giftIntentStore = store,
                currentOwnerProvider = { "0198f0a0-0000-7000-8000-000000000001" },
            )
        }
        rule.onNodeWithTag("pending_code_field").performTextInput("a3k9q")
        rule.onNodeWithTag("pending_code_continue").performClick()
        release.complete(Unit)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("A3K9Q", published) }
    }

    private fun senderVm(
        scope: kotlinx.coroutines.CoroutineScope,
        freeze: dev.hryshyn.remanence.core.data.network.SendRouteResult,
    ): dev.hryshyn.remanence.pending.PendingSenderViewModel {
        dev.hryshyn.remanence.core.crypto.TinkPrimitives.ensureRegistered()
        val signing = com.google.crypto.tink.KeysetHandle.generateNew(
            com.google.crypto.tink.KeyTemplates.get("ED25519"),
        )
        val gate = object : dev.hryshyn.remanence.pending.PendingRetirementSessionGate {
            override fun capture(): dev.hryshyn.remanence.core.data.network.SessionRequestLease =
                dev.hryshyn.remanence.core.data.network.SessionRequestLease(
                    dev.hryshyn.remanence.core.model.UserId.parseRest("01010101-0202-0303-0404-050505050505"),
                    1L,
                )

            override fun accessTokenFor(
                lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
            ): String? = "token"

            override fun isLive(
                lease: dev.hryshyn.remanence.core.data.network.SessionRequestLease,
            ): Boolean = true
        }
        val account = dev.hryshyn.remanence.pending.ProofAccount(
            "01010101-0202-0303-0404-050505050505",
            "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f",
        )
        return dev.hryshyn.remanence.pending.PendingSenderViewModel(
            freezePort = dev.hryshyn.remanence.pending.PendingSendRouteFlow.SendRoutePort { _, _, _, _, _ -> freeze },
            publishFlowFactory = { materialPort ->
                dev.hryshyn.remanence.pending.PendingPublishFlow(
                    draftPort = dev.hryshyn.remanence.pending.PendingPublishFlow.DraftPort { _, _, _, _, _, _, _, _, _, _ ->
                        dev.hryshyn.remanence.core.data.network.PendingDraftResult.Draft(
                            "0198f0a0-0000-7000-8000-00000000d401",
                            "0f".repeat(32),
                            1798765432L,
                        )
                    },
                    blobPort = dev.hryshyn.remanence.pending.PendingPublishFlow.BlobPort { _, blobId, _, _, _, _ ->
                        dev.hryshyn.remanence.core.data.network.PendingBlobResult.Uploaded(blobId, 11L)
                    },
                    finalizePort = dev.hryshyn.remanence.pending.PendingPublishFlow.FinalizePort { deliveryId, _, _, _, _, _ ->
                        dev.hryshyn.remanence.core.data.network.PendingFinalizeResult.Finalized(
                            deliveryId, "AB3DE", 1814317432L, "a•••@•••",
                        )
                    },
                    materialPort = materialPort,
                    sessionGate = gate,
                    accountProvider = { account },
                    scope = null,
                )
            },
            contentPort = dev.hryshyn.remanence.pending.PendingSenderContentPort {
                dev.hryshyn.remanence.pending.PendingSenderContent(
                    listOf("device-jpeg".toByteArray()),
                )
            },
            identityPort = dev.hryshyn.remanence.pending.PendingSenderIdentityPort {
                dev.hryshyn.remanence.pending.PendingSenderIdentity(
                    "01010101-0202-0303-0404-050505050505",
                    "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f",
                    signing,
                )
            },
            servicePinProvider = {
                dev.hryshyn.remanence.pending.PendingServicePin(
                    "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b",
                    "cGlu",
                )
            },
            sessionGate = gate,
            accountProvider = { account },
            scope = scope,
        )
    }

    /**
     * T186 M12: actual sender sheet — pending route publishes through
     * the real flow ports and displays the handwritten 5-char code.
     * Root runs on device (r19).
     */
    @Test fun senderPendingRouteShowsHandwrittenCode() {
        mountEntry("en") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val viewModel = androidx.compose.runtime.remember {
                senderVm(
                    scope,
                    dev.hryshyn.remanence.core.data.network.SendRouteResult.FrozenPending(
                        routeId = "0198f0a0-0000-7000-8000-00000000b301",
                        deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                        normalizedDestination = "a42@example.test",
                        channel = "email",
                        claimDeadline = 1814317432L,
                        serviceKeyId = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b",
                        expiresAt = 1798765432L,
                    ),
                )
            }
            dev.hryshyn.remanence.pending.PendingSenderSheet(viewModel = viewModel, onClose = {}, enabled = true)
        }
        rule.onNodeWithTag("pending_sender_field").performTextInput("a42@example.test")
        rule.onNodeWithTag("pending_sender_send").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("pending_sender_code").assertIsDisplayed()
        rule.onNodeWithText("AB3DE").assertIsDisplayed()
        rule.onNodeWithTag("pending_sender_hint").assertIsDisplayed()
    }

    @Test fun senderKnownV1StaysZeroEscrow() {
        mountEntry("en") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val viewModel = androidx.compose.runtime.remember {
                senderVm(
                    scope,
                    dev.hryshyn.remanence.core.data.network.SendRouteResult.FrozenV1(
                        routeId = "0198f0a0-0000-7000-8000-00000000b302",
                        userId = "33333333-4444-5555-6666-777777777777",
                        keyBundleId = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f",
                        bindingRevision = 3L,
                        expiresAt = 1798765432L,
                    ),
                )
            }
            dev.hryshyn.remanence.pending.PendingSenderSheet(viewModel = viewModel, onClose = {}, enabled = true)
        }
        rule.onNodeWithTag("pending_sender_field").performTextInput("known@example.test")
        rule.onNodeWithTag("pending_sender_send").performClick()
        rule.waitForIdle()
        // Known bound route: ordinary-send note, never a code, no escrow.
        rule.onNodeWithTag("pending_sender_known_v1").assertIsDisplayed()
        rule.onNodeWithTag("pending_sender_code").assertDoesNotExist()
    }
}
