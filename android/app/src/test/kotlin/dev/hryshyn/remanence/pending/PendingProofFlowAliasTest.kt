package dev.hryshyn.remanence.pending

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.ContinueResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T186 alias-bind: the real ProofRequired surface (production
 * composable + real view-model, unit ports) — the opt-in checkbox is
 * displayed unchecked by default next to the destination field and
 * before Request code, toggles affirmatively, and clears when the
 * destination is edited. Narrowly declared UNIT evidence (synthetic
 * ports, frozen fixtures) — never HTTP/native.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PendingProofFlowAliasTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val fixtures = Json { ignoreUnknownKeys = true }
    private val accountId = "33333333-4444-5555-6666-777777777777"
    private val bundleId = "01010101-0202-0303-0404-050505050506"

    private lateinit var scope: CoroutineScope

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
    }

    private fun frozenBytes(resource: String, key: String): ByteArray {
        val root = fixtures.parseToJsonElement(
            javaClass.getResourceAsStream(resource)!!
                .bufferedReader().readText(),
        ).jsonObject
        val hex = root.getValue(key).jsonPrimitive.content
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun viewModel(): PendingProofViewModel {
        TinkPrimitives.ensureRegistered()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val statement = frozenBytes(
            "/pending/delivery-v2-canonical.json",
            "pending_publish_statement_s_hex",
        )
        val signature = frozenBytes(
            "/pending/delivery-v2-crypto.json",
            "pending_publish_signature_69_hex",
        )
        val senderKey = java.util.Base64.getDecoder().decode(
            fixtures.parseToJsonElement(
                javaClass.getResourceAsStream("/pending/delivery-v2-crypto.json")!!
                    .bufferedReader().readText(),
            ).jsonObject.getValue("sender_ed25519_public_keyset_b64").jsonPrimitive.content,
        )
        return PendingProofViewModel(
            continuePort = ProofContinuePort { _, _ ->
                ContinueResult.Proof(
                    giftReference = "0198f0a0-0000-7000-8000-00000000ab01",
                    deliveryId = "0198f0a0-0000-7000-8000-00000000d401",
                    capsuleId = "11111111-2222-3333-4444-555555555555",
                    deliveryTargetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    channel = "email",
                    claimExpiresAt = 1_798_765_432L,
                    publishStatementB64 = java.util.Base64.getEncoder().encodeToString(statement),
                    publishSignature69B64 = java.util.Base64.getEncoder().encodeToString(signature),
                )
            },
            verifyPort = object : ProofVerifyPort {
                override suspend fun request(
                    deliveryId: String,
                    destination: String,
                    smsConsent: Boolean,
                    nowSeconds: Long,
                    idempotencyKey: String,
                    token: String,
                ): dev.hryshyn.remanence.core.data.network.VerificationResult =
                    error("no verification in this fixture")

                override suspend fun check(
                    deliveryId: String,
                    attemptId: String,
                    code: String,
                    token: String,
                ): dev.hryshyn.remanence.core.data.network.VerificationResult =
                    error("no verification in this fixture")
            },
            optionsPort = ProofOptionsPort { _, _ ->
                error("no options in this fixture")
            },
            sessionGate = object : PendingRetirementSessionGate {
                override fun capture(): SessionRequestLease = SessionRequestLease(
                    UserId.parseRest(accountId),
                    1L,
                )

                override fun accessTokenFor(lease: SessionRequestLease): String? = "token"

                override fun isLive(lease: SessionRequestLease): Boolean = true
            },
            accountProvider = { ProofAccount(accountId, bundleId) },
            signingKeyProvider = { null },
            hpkeKeyProvider = { null },
            senderKeyProvider = { _, _ -> senderKey },
            scope = scope,
        )
    }

    @Test
    fun aliasCheckboxDisplayedUncheckedTogglesAndClearsOnEdit() {
        // Hoisted: recomposition must keep observing the same instance.
        val viewModel = viewModel()
        composeRule.setContent {
            MaterialTheme {
                PendingProofFlow(
                    viewModel = viewModel,
                    giftReference = "ref",
                    onDone = {},
                )
            }
        }
        composeRule.waitForIdle()
        // ProofRequired surface: destination field, opt-in, Request code.
        composeRule.onNodeWithTag("pending_proof_destination").assertIsDisplayed()
        composeRule.onNodeWithTag("pending_proof_bind_alias").assertIsDisplayed()
        composeRule.onNodeWithTag("pending_proof_send_code").assertIsDisplayed()
        // Default unchecked (and not affirmable while blank).
        composeRule.onNodeWithTag("pending_proof_bind_alias").assertIsOff()
        // Enter an address first, then the affirmative toggle sticks.
        composeRule.onNodeWithTag("pending_proof_destination").performTextInput("a42@example.test")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pending_proof_bind_alias").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pending_proof_bind_alias").assertIsOn()
        // Editing the destination clears the opt-in.
        composeRule.onNodeWithTag("pending_proof_destination").performTextInput(".other")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pending_proof_bind_alias").assertIsOff()
    }
}
