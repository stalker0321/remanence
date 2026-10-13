package dev.hryshyn.remanence.pending

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.PendingClaimChallenge
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.ClaimOptionsResult
import dev.hryshyn.remanence.core.data.network.ContinueResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.data.network.VerificationResult
import dev.hryshyn.remanence.core.model.UserId
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
        // Positive fixture: the frozen server-side signature over this
        // exact S (delivery-v2-crypto.json), verifiable under the frozen
        // sender public key. Zero/garbage signatures are covered by the
        // explicit tampered-signature negative below — never by default.
        return ContinueResult.Proof(
            giftReference = "0198f0a0-0000-7000-8000-00000000ab01",
            deliveryId = deliveryId, capsuleId = capsule,
            deliveryTargetId = target, channel = "email",
            claimExpiresAt = 1_798_765_432L,
            publishStatementB64 = sB64,
            publishSignature69B64 = Base64.getEncoder().encodeToString(frozenSSignature()),
        )
    }

    private fun frozenSSignature(): ByteArray {
        val crypto = fixtures.parseToJsonElement(
            javaClass.getResourceAsStream("/pending/delivery-v2-crypto.json")!!
                .bufferedReader().readText(),
        ).jsonObject
        return unhex(str(crypto, "pending_publish_signature_69_hex"))
    }

    private fun frozenSenderKey(): ByteArray {
        val crypto = fixtures.parseToJsonElement(
            javaClass.getResourceAsStream("/pending/delivery-v2-crypto.json")!!
                .bufferedReader().readText(),
        ).jsonObject
        return java.util.Base64.getDecoder().decode(str(crypto, "sender_ed25519_public_keyset_b64"))
    }

    private fun leaseFor(owner: String): SessionRequestLease = SessionRequestLease(
        ownerUserId = UserId.parseRest(owner),
        incarnation = 1L,
    )

    /** Fake held-lease gate: token queue + liveness + owner binding. */
    private inner class FakeGate(
        val owner: String,
        val tokens: MutableList<String?>,
        var live: Boolean = true,
    ) : PendingRetirementSessionGate {
        var captures = 0

        override fun capture(): SessionRequestLease? {
            captures += 1
            return leaseFor(owner)
        }

        override fun accessTokenFor(lease: SessionRequestLease): String? =
            if (tokens.isEmpty()) null else tokens.removeAt(0)

        override fun isLive(lease: SessionRequestLease): Boolean =
            live && lease.ownerUserId == UserId.parseRest(owner)
    }

    private fun kotlinx.coroutines.test.TestScope.vm(
        continueResult: ContinueResult = proofBranch(),
        options: (String) -> ClaimOptionsResult = { ClaimOptionsResult.Failure(
            dev.hryshyn.remanence.core.data.network.ClaimFailure.ADDRESS_PROOF_REQUIRED, 400, false,
        ) },
        tokens: MutableList<String?> = mutableListOf("token", "token", "token", "token", "token"),
        live: Boolean = true,
        account: ProofAccount? = ProofAccount(accountId, bundleId),
        gateOwner: String? = null,
        signing: KeysetHandle? = null,
        hpke: KeysetHandle? = null,
        session: String? = sessionId,
        senderKey: ByteArray? = frozenSenderKey(),
        senderKeyProvider: (suspend (String, String) -> ByteArray?)? = null,
        claim: ProofClaimPort? = null,
        onRequest: ((Boolean) -> Unit)? = null,
        checkBehavior: (() -> VerificationResult)? = null,
        // R11-flow bearer budget: R7, R8, R9, R10 options and R11 submit
        // each capture one bearer (FIFTH call); the positive fake stays
        // live for all five. Intentional exhaustion negatives pass an
        // explicit short list instead.
        ownBundleKey: ByteArray? = null,
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
                ): VerificationResult {
                    onRequest?.invoke(smsConsent)
                    return VerificationResult.Attempt(attemptId, 1_798_765_432L, 60L)
                }

                override suspend fun check(
                    deliveryId: String, attemptId: String, code: String, token: String,
                ): VerificationResult =
                    checkBehavior?.invoke() ?: VerificationResult.Verified(attemptId)
            },
            optionsPort = ProofOptionsPort { _, _ -> options("") },
            sessionGate = FakeGate(gateOwner ?: account?.userId ?: ownerless(), tokens, live),
            accountProvider = { account },
            signingKeyProvider = { signingKeys },
            hpkeKeyProvider = { hpke },
            sessionIdProvider = { session },
            senderKeyProvider = senderKeyProvider ?: { _, _ -> senderKey },
            ownBundleKeyProvider = { ownBundleKey },
            claimPort = claim,
            scope = scope,
        )
    }

    private fun ownerless(): String = "00000000-0000-0000-0000-000000000000"

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
    fun submitConflictMapsToError() = runTest {
        val setup = challengeSetup()
        var submitted = 0
        val viewModel = vm(
            options = {
                ClaimOptionsResult.Options(
                    setup.challengeId, 1_798_765_432L,
                    Base64.getEncoder().encodeToString(setup.sealed),
                    Base64.getEncoder().encodeToString(setup.contextRaw),
                )
            },
            hpke = setup.recipientPrivate,
            claim = ProofClaimPort { _, _, _, _, _ ->
                submitted += 1
                dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Failure(
                    dev.hryshyn.remanence.core.data.network.ClaimFailure.CLAIM_CONFLICT,
                    409, false,
                )
            },
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        viewModel.onDestinationChange("a42@example.test")
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        viewModel.onCodeChange("12345678")
        viewModel.checkCode()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.IntentReady)
        viewModel.submitClaim()
        testScheduler.advanceUntilIdle()
        assertEquals(1, submitted)
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.CONFLICT),
            viewModel.state.value,
        )
    }

    @Test
    fun submitGarbageClaimedRejected() = runTest {
        val setup = challengeSetup()
        var submitted = 0
        val viewModel = vm(
            options = {
                ClaimOptionsResult.Options(
                    setup.challengeId, 1_798_765_432L,
                    Base64.getEncoder().encodeToString(setup.sealed),
                    Base64.getEncoder().encodeToString(setup.contextRaw),
                )
            },
            hpke = setup.recipientPrivate,
            // Genuine recipient public bytes: the rejection must come
            // from the invalid binding parse — never from a null seam.
            ownBundleKey = com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                setup.recipientPrivate.publicKeysetHandle,
            ),
            claim = ProofClaimPort { _, _, _, _, _ ->
                submitted += 1
                dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                    "55555555-6666-7777-8888-999999999999",
                    Base64.getEncoder().encodeToString(ByteArray(10)),
                    Base64.getEncoder().encodeToString(ByteArray(69)),
                    Base64.getEncoder().encodeToString(ByteArray(16)),
                    "ab".repeat(32),
                )
            },
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        viewModel.onDestinationChange("a42@example.test")
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        viewModel.onCodeChange("12345678")
        viewModel.checkCode()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.IntentReady)
        // R11 ran and the structurally invalid binding was rejected:
        // no Claimed state, no grant, never opened.
        viewModel.submitClaim()
        testScheduler.advanceUntilIdle()
        assertEquals(1, submitted)
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.GENERIC),
            viewModel.state.value,
        )
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
    fun tamperedSenderSignatureFailsClosed() = runTest {
        val sig = frozenSSignature().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val bad = proofBranch().copy(
            publishSignature69B64 = Base64.getEncoder().encodeToString(sig),
        )
        val viewModel = vm(continueResult = bad)
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        // Framing intact (69 bytes) but cryptographically wrong: the
        // proof branch never opens, never ProofRequired.
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

    @Test
    fun deadLeaseFailsSessionChanged() = runTest {
        val viewModel = vm(live = false)
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
    }

    @Test
    fun ownerMismatchFailsAtAttempt() = runTest {
        val viewModel = vm(
            account = ProofAccount("99999999-2222-3333-4444-555555555555", bundleId),
            gateOwner = accountId,
        )
        // R7 needs no account: the proof branch still opens...
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.ProofRequired)
        // ...but any account-bound step fences on the lease owner.
        viewModel.onDestinationChange("a42@example.test")
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
    }

    @Test
    fun senderBundleOwnerMismatchFailsClosed() = runTest {
        // Directory bundle owner differs from the statement sender:
        // the provider refuses (OWNER_MISMATCH, null) and the flow
        // fails closed with GENERIC, never ProofRequired/admission.
        val viewModel = vm(
            senderKeyProvider = { _, _ -> null },
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.GENERIC),
            viewModel.state.value,
        )
    }

    @Test
    fun retryRetainsGiftAndAttempt() = runTest {
        var first = true
        val viewModel = vm(
            checkBehavior = {
                if (first) {
                    first = false
                    VerificationResult.Failure(
                        dev.hryshyn.remanence.core.data.network.GiftLookupFailure.NETWORK,
                        null, true,
                    )
                } else {
                    VerificationResult.Verified(attemptId)
                }
            },
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        viewModel.onDestinationChange("a42@example.test")
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.CodeRequired)
        // A network failure keeps gift + attempt: retry restores the step.
        viewModel.onCodeChange("12345678")
        viewModel.checkCode()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.Failed)
        viewModel.retry()
        val retried = viewModel.state.value as PendingProofViewModel.ProofState.CodeRequired
        assertEquals(attemptId, retried.attemptId)
        assertEquals(deliveryId, retried.deliveryId)
    }

    @Test
    fun smsConsentExplicitNotDerived() = runTest {
        // Default: no consent sent even for non-email channels.
        val seenDefault = mutableListOf<Boolean>()
        val plain = vm(onRequest = { seenDefault += it })
        plain.start("ref")
        testScheduler.advanceUntilIdle()
        plain.onDestinationChange("+380501234567")
        plain.requestAttempt()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false), seenDefault)
        // Affirmed: consent travels.
        val seenAffirmed = mutableListOf<Boolean>()
        val consenting = vm(onRequest = { seenAffirmed += it })
        consenting.start("ref")
        testScheduler.advanceUntilIdle()
        consenting.onDestinationChange("+380501234567")
        consenting.onSmsConsentChange(true)
        consenting.requestAttempt()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true), seenAffirmed)
    }
}
