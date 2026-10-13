package dev.hryshyn.remanence.pending

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.PendingClaimChallenge
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.ClaimOptionsResult
import dev.hryshyn.remanence.core.data.network.ContinueResult
import dev.hryshyn.remanence.core.data.network.VerificationResult
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimChallengeV1
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M7c: proof-flow fencing with REAL challenge crypto (self-sealed
 * Tink hybrid vectors). No Gradle/ADB.
 */
class PendingProofViewModelTest {

    private val fixtures = Json { ignoreUnknownKeys = true }

    private fun str(o: Map<String, kotlinx.serialization.json.JsonElement>, key: String): String =
        o.getValue(key).jsonPrimitive.content

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun uuidBytes(raw: String): ByteArray {
        val uuid = UUID.fromString(raw)
        val buffer = java.nio.ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val accountId = "33333333-4444-5555-6666-777777777777"
    private val bundleId = "01010101-0202-0303-0404-050505050506"
    private val sessionId = "02020202-0303-0404-0505-060606060606"
    private val deliveryId = "0198f0a0-0000-7000-8000-00000000d401"
    private val attemptId = "0198f0a0-0000-7000-8000-00000000a701"

    private fun frozenS(): ByteArray {
        val canonical = fixtures.parseToJsonElement(
            javaClass.getResourceAsStream("/pending/delivery-v2-canonical.json")!!
                .bufferedReader().readText(),
        ).jsonObject
        return unhex(str(canonical, "pending_publish_statement_s_hex"))
    }

    private data class ChallengeSetup(
        val contextRaw: ByteArray,
        val sealed: ByteArray,
        val nonce: ByteArray,
        val recipientPrivate: KeysetHandle,
        val challengeId: String,
    )

    private fun challengeSetup(nowSeconds: Long = 1_798_765_400L): ChallengeSetup {
        TinkPrimitives.ensureRegistered()
        val recipient = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val challengeId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val context = ClaimChallengeV1.newBuilder()
            .setChallengeId(ByteString.copyFrom(uuidBytes(challengeId)))
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setRecipientUserId(ByteString.copyFrom(uuidBytes(accountId)))
            .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundleId)))
            .setSessionId(ByteString.copyFrom(uuidBytes(sessionId)))
            .setNonceSha256(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(nonce)))
            .setExpiresAtEpochSeconds(1_798_765_432L)
            .build()
        val contextRaw = context.toByteArray()
        val info = PendingClaimChallenge.CLAIM_CHALLENGE_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + contextRaw
        val sealed = recipient.publicKeysetHandle
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt(nonce, info)
        return ChallengeSetup(contextRaw, sealed, nonce, recipient, challengeId)
    }

    private fun proofBranch(): ContinueResult.Proof {
        val sB64 = Base64.getEncoder().encodeToString(frozenS())
        return ContinueResult.Proof(
            giftReference = "0198f0a0-0000-7000-8000-00000000ab01",
            deliveryId = deliveryId, capsuleId = capsule,
            deliveryTargetId = target, channel = "email",
            claimExpiresAt = 1_798_765_432L,
            publishStatementB64 = sB64,
            publishSignature69B64 = Base64.getEncoder().encodeToString(ByteArray(69)),
        )
    }

    private fun vm(
        continueResult: ContinueResult = proofBranch(),
        options: (String) -> ClaimOptionsResult = { ClaimOptionsResult.Failure(
            dev.hryshyn.remanence.core.data.network.ClaimFailure.ADDRESS_PROOF_REQUIRED, 400, false,
        ) },
        tokens: MutableList<String?> = mutableListOf("token", "token", "token"),
        account: ProofAccount? = ProofAccount(accountId, bundleId),
        signing: KeysetHandle? = null,
        hpke: KeysetHandle? = null,
        session: String? = sessionId,
    ): PendingProofViewModel {
        TinkPrimitives.ensureRegistered()
        val signingKeys = signing
            ?: KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        // Unconfined: launched flow steps run eagerly under advanceUntilIdle.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        return PendingProofViewModel(
            continuePort = ProofContinuePort { _, _ -> continueResult },
            verifyPort = object : ProofVerifyPort {
                override suspend fun request(
                    deliveryId: String, destination: String, smsConsent: Boolean,
                    nowSeconds: Long, idempotencyKey: String, token: String,
                ) = VerificationResult.Attempt(attemptId, 1_798_765_432L, 60L)

                override suspend fun check(
                    deliveryId: String, attemptId: String, code: String, token: String,
                ) = VerificationResult.Verified(attemptId)
            },
            optionsPort = ProofOptionsPort { _, _ -> options("") },
            tokenProvider = { if (tokens.isEmpty()) null else tokens.removeAt(0) },
            accountProvider = { account },
            signingKeyProvider = { signingKeys },
            hpkeKeyProvider = { hpke },
            sessionIdProvider = { session },
            scope = scope,
        )
    }

    @Test
    fun fullFlowReachesSignedIntent() = runTest {
        val setup = challengeSetup()
        val viewModel = vm(
            options = {
                ClaimOptionsResult.Options(
                    setup.challengeId, 1_798_765_432L,
                    Base64.getEncoder().encodeToString(setup.sealed),
                    Base64.getEncoder().encodeToString(setup.contextRaw),
                )
            },
            hpke = setup.recipientPrivate,
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.ProofRequired)
        viewModel.onDestinationChange("a42@example.test")
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.CodeRequired)
        viewModel.onCodeChange("12345678")
        viewModel.checkCode()
        testScheduler.advanceUntilIdle()
        val ready = viewModel.state.value as PendingProofViewModel.ProofState.IntentReady
        assertEquals(deliveryId, ready.deliveryId)
        assertEquals(Base64.getEncoder().encodeToString(setup.nonce), ready.nonceB64)
    }

    @Test
    fun blockedWithoutSessionId() = runTest {
        val setup = challengeSetup()
        val viewModel = vm(
            options = {
                ClaimOptionsResult.Options(
                    setup.challengeId, 1_798_765_432L,
                    Base64.getEncoder().encodeToString(setup.sealed),
                    Base64.getEncoder().encodeToString(setup.contextRaw),
                )
            },
            hpke = setup.recipientPrivate,
            session = null,
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        viewModel.onDestinationChange("a42@example.test")
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        viewModel.onCodeChange("12345678")
        viewModel.checkCode()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.BLOCKED),
            viewModel.state.value,
        )
    }

    @Test
    fun syncBranchNeedsNoProof() = runTest {
        val viewModel = vm(continueResult = ContinueResult.Sync(capsule))
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.SyncReady(capsule),
            viewModel.state.value,
        )
    }

    @Test
    fun malformedStatementFails() = runTest {
        val bad = proofBranch().copy(publishStatementB64 = Base64.getEncoder().encodeToString(ByteArray(10)))
        val viewModel = vm(continueResult = bad)
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.GENERIC),
            viewModel.state.value,
        )
    }

    @Test
    fun missingTokenFailsSessionChanged() = runTest {
        val viewModel = vm(tokens = mutableListOf())
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
    }
}
