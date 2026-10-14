package dev.hryshyn.remanence.pending

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.PendingClaimChallenge
import dev.hryshyn.remanence.core.crypto.PendingClaimIntent
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.ClaimOptionsResult
import dev.hryshyn.remanence.core.data.network.ContinueResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.data.network.VerificationResult
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.delivery_v2.AddressOpening
import dev.hryshyn.remanence.protocol.delivery_v2.ArtifactBindingV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimChallengeV1
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimIntentV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopeContextV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopePlaintextV2
import dev.hryshyn.remanence.protocol.delivery_v2.KeyCommitmentInput
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

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

    private fun challengeSetup(
        nowSeconds: Long = 1_798_765_400L,
        challengeCapsule: String = capsule,
        challengeTarget: String = target,
    ): ChallengeSetup {
        TinkPrimitives.ensureRegistered()
        val recipient = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val challengeId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val context = ClaimChallengeV1.newBuilder()
            .setChallengeId(ByteString.copyFrom(uuidBytes(challengeId)))
            .setCapsuleId(ByteString.copyFrom(uuidBytes(challengeCapsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(challengeTarget)))
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

    private fun leaseFor(owner: String, epoch: Long = 1L): SessionRequestLease = SessionRequestLease(
        ownerUserId = UserId.parseRest(owner),
        incarnation = epoch,
    )

    /** Fake held-lease gate: token queue + liveness + owner binding. */
    private inner class FakeGate(
        var owner: String,
        val tokens: MutableList<String?>,
        var live: Boolean = true,
        /** Same-owner incarnation rotation point (liveness is owner-bound). */
        var epoch: Long = 1L,
    ) : PendingRetirementSessionGate {
        var captures = 0

        override fun capture(): SessionRequestLease? {
            captures += 1
            return leaseFor(owner, epoch)
        }

        override fun accessTokenFor(lease: SessionRequestLease): String? =
            if (tokens.isEmpty()) null else tokens.removeAt(0)

        override fun isLive(lease: SessionRequestLease): Boolean =
            live && lease.ownerUserId == UserId.parseRest(owner) &&
                lease.incarnation == epoch
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
        hpkeProvider: (suspend () -> KeysetHandle?)? = null,
        gateTap: ((FakeGate) -> Unit)? = null,
        session: String? = sessionId,
        senderKey: ByteArray? = frozenSenderKey(),
        accountProvider: (suspend () -> ProofAccount?)? = null,
        senderKeyProvider: (suspend (String, String) -> ByteArray?)? = null,
        claim: ProofClaimPort? = null,
        onRequest: ((Boolean) -> Unit)? = null,
        checkBehavior: (() -> VerificationResult)? = null,
        // R11-flow bearer budget: R7, R8, R9, R10 options and R11 submit
        // each capture one bearer (FIFTH call); the positive fake stays
        // live for all five. Intentional exhaustion negatives pass an
        // explicit short list instead.
        ownBundleKey: ByteArray? = null,
        servicePin: PendingServicePin? = null,
        admittedStore: PendingAdmittedGiftStore? = null,
        recognitionBlobPort: PendingRecognitionStage.BlobFetchPort? = null,
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ): PendingProofViewModel {
        TinkPrimitives.ensureRegistered()
        val signingKeys = signing
            ?: KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        // Unconfined by default: launched flow steps run eagerly under
        // advanceUntilIdle. Deferred scopes (StandardTestDispatcher) pause
        // launches for genuine pre-dispatch switch windows.
        val scope = scope
            ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val gate = FakeGate(gateOwner ?: account?.userId ?: ownerless(), tokens, live)
        gateTap?.invoke(gate)
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
            sessionGate = gate,
            accountProvider = accountProvider ?: { account },
            signingKeyProvider = { signingKeys },
            hpkeKeyProvider = hpkeProvider ?: { hpke },
            sessionIdProvider = { session },
            senderKeyProvider = senderKeyProvider ?: { _, _ -> senderKey },
            ownBundleKeyProvider = { ownBundleKey },
            servicePinProvider = { servicePin },
            admittedStore = admittedStore,
            recognitionBlobPort = recognitionBlobPort,
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

    private val otherCapsule = "99999999-2222-3333-4444-555555555555"
    private val otherTarget = "ffffffff-1111-2222-3333-444444444444"
    private val otherClaimId = "77777777-8888-9999-0000-111111111111"

    private data class AcceptedVectors(
        val statement: ByteArray,
        val senderSig: ByteArray,
        val senderPublicBytes: ByteArray,
        val claim: ByteArray,
        val claimSig: ByteArray,
        val sealed: ByteArray,
        val sealedShaHex: String,
        val claimId: String,
        val recipientPrivate: KeysetHandle,
        val recipientPublicBytes: ByteArray,
        val servicePin: PendingServicePin,
        val challenge: ChallengeSetup,
        val proof: ContinueResult.Proof,
        val recognitionBlob: ByteArray,
        val recognitionBlobId: String,
    )

    /**
     * Self-built, internally consistent Accepted vectors (own
     * sender/service keys sign S and R; the envelope seals to the test
     * recipient). Echo/challenge ids default to the real gift; the
     * substitution negatives override them so the Continue echo names a
     * sibling gift while S/R stay genuinely signed.
     */
    private fun acceptedVectors(
        echoCapsule: String = capsule,
        echoTarget: String = target,
    ): AcceptedVectors {
        TinkPrimitives.ensureRegistered()
        val now = System.currentTimeMillis() / 1000L
        val senderKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val senderPublicBytes =
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(senderKeys.publicKeysetHandle)
        val serviceKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val serviceId = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
        val servicePublicB64 = Base64.getEncoder().encodeToString(
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(serviceKeys.publicKeysetHandle),
        )
        val setup = challengeSetup(challengeCapsule = echoCapsule, challengeTarget = echoTarget)
        val recipientPublicBytes =
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(setup.recipientPrivate.publicKeysetHandle)
        val opening = AddressOpening.newBuilder()
            .setNormalizationVersion(1)
            .setChannel(1)
            .setNormalizedDestination("a42@example.test")
            .setSalt(ByteString.copyFrom(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            .build()
        val openingBytes = opening.toByteArray()
        val d = sha256(
            "postmark/delivery-address/v1".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + openingBytes,
        )
        val capsuleKey = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val rawKey = TinkProtoKeysetFormat.serializeKeyset(capsuleKey, InsecureSecretKeyAccess.get())
        val kcInput = KeyCommitmentInput.newBuilder()
            .setVersion(1)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setCapsuleAeadKeyset(ByteString.copyFrom(rawKey))
            .build().toByteArray()
        val kc = sha256(
            "postmark/delivery-key/v1".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + kcInput,
        )
        // Staged kind-1 ciphertext (sealed FRONT stand-in): sized above
        // the staging floor so selection succeeds.
        val recognitionBlobId = "cccccccc-dddd-eeee-ffff-000000000001"
        val recognitionBytes =
            "recognition-front-ciphertext-0123456789-abcdef-0123456789-abcdef".toByteArray()
        val statement = PendingPublishStatementV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setSenderUserId(ByteString.copyFrom(uuidBytes("01010101-0202-0303-0404-050505050505")))
            .setSenderKeyBundleId(ByteString.copyFrom(uuidBytes(bundleId)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setAddressCommitment(ByteString.copyFrom(d))
            .setKeyCommitment(ByteString.copyFrom(kc))
            .setCreatedAtEpochSeconds(now - 3600L)
            .setClaimExpiresAtEpochSeconds(now + 86400L)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(uuidBytes(serviceId)))
            // T186 M14d-scan: single kind-1 recognition binding so the
            // staging tests exercise the real S declaration path.
            .addArtifacts(
                ArtifactBindingV2.newBuilder()
                    .setBlobId(ByteString.copyFrom(uuidBytes(recognitionBlobId)))
                    .setKind(1)
                    .setOrdinal(-1)
                    .setCiphertextSize(recognitionBytes.size.toLong())
                    .setCiphertextSha256(ByteString.copyFrom(sha256(recognitionBytes)))
                    .build(),
            )
            .build().toByteArray()
        val senderSig = senderKeys.getPrimitive(PublicKeySign::class.java).sign(
            "postmark/pending-publish/v2".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + statement,
        )
        val claimId = "55555555-6666-7777-8888-999999999999"
        val claim = ClaimBindingV1.newBuilder()
            .setBindingVersion(1)
            .setCapsuleProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
            .setAddressCommitment(ByteString.copyFrom(d))
            .setRecipientUserId(ByteString.copyFrom(uuidBytes(accountId)))
            .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundleId)))
            .setRecipientEncryptionPublicKeysetSha256(
                ByteString.copyFrom(sha256(recipientPublicBytes)),
            )
            .setClaimId(ByteString.copyFrom(uuidBytes(claimId)))
            .setClaimedAtEpochSeconds(now)
            .setClaimPolicyVersion(1)
            .setClaimSigningKeyId(ByteString.copyFrom(uuidBytes(serviceId)))
            .build().toByteArray()
        val contextRaw = ClaimedEnvelopeContextV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
            .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
            .setSenderUserId(ByteString.copyFrom(uuidBytes("01010101-0202-0303-0404-050505050505")))
            .setRecipientUserId(ByteString.copyFrom(uuidBytes(accountId)))
            .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundleId)))
            .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
            .setClaimBindingSha256(ByteString.copyFrom(sha256(claim)))
            .build().toByteArray()
        val plaintext = ClaimedEnvelopePlaintextV2.newBuilder()
            .setBinding(
                ClaimedEnvelopeContextV2.newBuilder()
                    .setProtocolVersion(2)
                    .setCapsuleId(ByteString.copyFrom(uuidBytes(capsule)))
                    .setDeliveryTargetId(ByteString.copyFrom(uuidBytes(target)))
                    .setSenderUserId(ByteString.copyFrom(uuidBytes("01010101-0202-0303-0404-050505050505")))
                    .setRecipientUserId(ByteString.copyFrom(uuidBytes(accountId)))
                    .setRecipientKeyBundleId(ByteString.copyFrom(uuidBytes(bundleId)))
                    .setPublishStatementSha256(ByteString.copyFrom(sha256(statement)))
                    .setClaimBindingSha256(ByteString.copyFrom(sha256(claim)))
                    .build(),
            )
            .setCapsuleAeadKeyset(ByteString.copyFrom(rawKey))
            .setAddressOpening(opening)
            .build().toByteArray()
        val info = "postmark/claimed-envelope/v2".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + contextRaw
        val sealed = setup.recipientPrivate.publicKeysetHandle
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt(plaintext, info)
        val claimSig = serviceKeys.getPrimitive(PublicKeySign::class.java).sign(
            "postmark/claim-binding/v1".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00) + claim,
        )
        val proof = proofBranch().copy(
            capsuleId = echoCapsule,
            deliveryTargetId = echoTarget,
            publishStatementB64 = Base64.getEncoder().encodeToString(statement),
            publishSignature69B64 = Base64.getEncoder().encodeToString(senderSig),
        )
        return AcceptedVectors(
            statement = statement,
            senderSig = senderSig,
            senderPublicBytes = senderPublicBytes,
            claim = claim,
            claimSig = claimSig,
            sealed = sealed,
            sealedShaHex = sha256(sealed).joinToString("") { "%02x".format(it) },
            claimId = claimId,
            recipientPrivate = setup.recipientPrivate,
            recipientPublicBytes = recipientPublicBytes,
            servicePin = PendingServicePin(serviceId, servicePublicB64),
            challenge = setup,
            proof = proof,
            recognitionBlob = recognitionBytes,
            recognitionBlobId = recognitionBlobId,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.driveToIntentReady(
        viewModel: PendingProofViewModel,
    ) {
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        viewModel.onDestinationChange("a42@example.test")
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        viewModel.onCodeChange("12345678")
        viewModel.checkCode()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.IntentReady)
    }

    /**
     * T186 M14d-claim: the full Accepted path with self-built,
     * internally consistent vectors (own sender/service keys sign S
     * and R; the envelope seals to the test recipient) — trusted
     * acceptance persists the admitted row (ids + sealed bytes, no
     * keys) with serviceVerified=true. Partial (no pin) writes no row.
     */
    @Test
    fun acceptedClaimPersistsAdmittedRow() = runTest {
        val vectors = acceptedVectors()
        val dir = java.nio.file.Files.createTempDirectory("pending-claim-row").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = vm(
                continueResult = vectors.proof,
                options = {
                    ClaimOptionsResult.Options(
                        vectors.challenge.challengeId, 1_798_765_432L,
                        Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                        Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
                    )
                },
                hpke = vectors.recipientPrivate,
                senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
                ownBundleKey = vectors.recipientPublicBytes,
                servicePin = vectors.servicePin,
                admittedStore = store,
                claim = ProofClaimPort { _, _, _, _, _ ->
                    dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                        vectors.claimId,
                        Base64.getEncoder().encodeToString(vectors.claim),
                        Base64.getEncoder().encodeToString(vectors.claimSig),
                        Base64.getEncoder().encodeToString(vectors.sealed),
                        vectors.sealedShaHex,
                    )
                },
            )
            driveToIntentReady(viewModel)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Claimed(deliveryId, serviceVerified = true),
                viewModel.state.value,
            )
            // The admitted row is durable with ids + sealed bytes only.
            val row = store.get(accountId, capsule)
            assertEquals(deliveryId, row?.deliveryId)
            assertEquals(
                Base64.getEncoder().encodeToString(vectors.sealed),
                row?.envelopeB64,
            )
            assertEquals(
                Base64.getEncoder().encodeToString(vectors.statement),
                row?.statementB64,
            )
            // No blob port: staging skipped, row still admits.
            assertEquals("", row?.recognitionCiphertextB64)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 M14d-scan: with a blob port the Accepted path stages the
     * S-declared kind-1 ciphertext into the row for offline scan
     * enumeration.
     */
    @Test
    fun acceptedClaimStagesRecognitionCiphertext() = runTest {
        val vectors = acceptedVectors()
        val dir = java.nio.file.Files.createTempDirectory("pending-claim-staged").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = vm(
                continueResult = vectors.proof,
                options = {
                    ClaimOptionsResult.Options(
                        vectors.challenge.challengeId, 1_798_765_432L,
                        Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                        Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
                    )
                },
                hpke = vectors.recipientPrivate,
                senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
                ownBundleKey = vectors.recipientPublicBytes,
                servicePin = vectors.servicePin,
                admittedStore = store,
                recognitionBlobPort = PendingRecognitionStage.BlobFetchPort { deliveryId, blobId, _ ->
                    assertEquals(vectors.proof.deliveryId, deliveryId)
                    assertEquals(vectors.recognitionBlobId, blobId)
                    dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult.Bytes(
                        blobId, vectors.recognitionBlob,
                    )
                },
                claim = ProofClaimPort { _, _, _, _, _ ->
                    dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                        vectors.claimId,
                        Base64.getEncoder().encodeToString(vectors.claim),
                        Base64.getEncoder().encodeToString(vectors.claimSig),
                        Base64.getEncoder().encodeToString(vectors.sealed),
                        vectors.sealedShaHex,
                    )
                },
            )
            driveToIntentReady(viewModel)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Claimed(deliveryId, serviceVerified = true),
                viewModel.state.value,
            )
            assertEquals(
                Base64.getEncoder().encodeToString(vectors.recognitionBlob),
                store.get(accountId, capsule)?.recognitionCiphertextB64,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 M14d-scan: a tampered kind-1 fetch (digest mismatch) stages
     * nothing — the claim still lands truthful with an unstaged row.
     */
    @Test
    fun tamperedRecognitionBlobStagesNothing() = runTest {
        val vectors = acceptedVectors()
        val dir = java.nio.file.Files.createTempDirectory("pending-claim-untampered").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = vm(
                continueResult = vectors.proof,
                options = {
                    ClaimOptionsResult.Options(
                        vectors.challenge.challengeId, 1_798_765_432L,
                        Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                        Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
                    )
                },
                hpke = vectors.recipientPrivate,
                senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
                ownBundleKey = vectors.recipientPublicBytes,
                servicePin = vectors.servicePin,
                admittedStore = store,
                recognitionBlobPort = PendingRecognitionStage.BlobFetchPort { _, blobId, _ ->
                    dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult.Bytes(
                        blobId, vectors.recognitionBlob.copyOf().also { it[0] = (it[0] + 1).toByte() },
                    )
                },
                claim = ProofClaimPort { _, _, _, _, _ ->
                    dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                        vectors.claimId,
                        Base64.getEncoder().encodeToString(vectors.claim),
                        Base64.getEncoder().encodeToString(vectors.claimSig),
                        Base64.getEncoder().encodeToString(vectors.sealed),
                        vectors.sealedShaHex,
                    )
                },
            )
            driveToIntentReady(viewModel)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Claimed(deliveryId, serviceVerified = true),
                viewModel.state.value,
            )
            assertEquals("", store.get(accountId, capsule)?.recognitionCiphertextB64)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 M14d-scan: no-blank-overwrite — a previously staged cipher
     * survives a claim whose fresh fetch fails, while it still verifies
     * against the current S. The claim still lands truthful.
     */
    @Test
    fun failedFetchKeepsPriorStagedCipher() = runTest {
        val vectors = acceptedVectors()
        val dir = java.nio.file.Files.createTempDirectory("pending-claim-healed").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            assertTrue(
                store.upsert(
                    PendingAdmittedGiftStore.AdmittedGift(
                        ownerUserId = accountId,
                        capsuleId = capsule,
                        deliveryId = deliveryId,
                        deliveryTargetId = target,
                        claimId = vectors.claimId,
                        availableAt = 1_798_765_200L,
                        envelopeB64 = Base64.getEncoder().encodeToString(vectors.sealed),
                        envelopeSha256Hex = vectors.sealedShaHex,
                        statementB64 = Base64.getEncoder().encodeToString(vectors.statement),
                        statementSha256Hex = sha256(vectors.statement).joinToString("") { "%02x".format(it) },
                        publishSignature69B64 = Base64.getEncoder().encodeToString(vectors.senderSig),
                        claimBindingB64 = Base64.getEncoder().encodeToString(vectors.claim),
                        claimSignature69B64 = Base64.getEncoder().encodeToString(vectors.claimSig),
                        recipientKeyBundleId = bundleId,
                        envelopeRef = "/v2/pending-deliveries/$deliveryId/claim",
                        manifestRef = "/v2/pending-deliveries/$deliveryId/manifest",
                        blobRefs = emptyList(),
                        completedAt = null,
                        admittedAtMillis = 1L,
                        recognitionCiphertextB64 = Base64.getEncoder()
                            .encodeToString(vectors.recognitionBlob),
                    ),
                ),
            )
            val viewModel = vm(
                continueResult = vectors.proof,
                options = {
                    ClaimOptionsResult.Options(
                        vectors.challenge.challengeId, 1_798_765_432L,
                        Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                        Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
                    )
                },
                hpke = vectors.recipientPrivate,
                senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
                ownBundleKey = vectors.recipientPublicBytes,
                servicePin = vectors.servicePin,
                admittedStore = store,
                recognitionBlobPort = PendingRecognitionStage.BlobFetchPort { _, _, _ ->
                    dev.hryshyn.remanence.core.data.network.PendingBlobBytesResult.Failure(
                        dev.hryshyn.remanence.core.data.network.PendingMaterialFailure.NETWORK,
                        500, true,
                    )
                },
                claim = ProofClaimPort { _, _, _, _, _ ->
                    dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                        vectors.claimId,
                        Base64.getEncoder().encodeToString(vectors.claim),
                        Base64.getEncoder().encodeToString(vectors.claimSig),
                        Base64.getEncoder().encodeToString(vectors.sealed),
                        vectors.sealedShaHex,
                    )
                },
            )
            driveToIntentReady(viewModel)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Claimed(deliveryId, serviceVerified = true),
                viewModel.state.value,
            )
            assertEquals(
                Base64.getEncoder().encodeToString(vectors.recognitionBlob),
                store.get(accountId, capsule)?.recognitionCiphertextB64,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 dd46 binding gate: the Continue echo names a sibling gift
     * while S/R/envelope stay genuinely signed for the real one.
     * accept() still passes (S/R internally consistent) but the row
     * gate must reject — verified capsule/target != echo — with no row
     * under either id and no Claimed promotion.
     */
    @Test
    fun substitutedContinueEchoWritesNoRow() = runTest {
        val vectors = acceptedVectors(echoCapsule = otherCapsule, echoTarget = otherTarget)
        val dir = java.nio.file.Files.createTempDirectory("pending-claim-substituted").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = vm(
                continueResult = vectors.proof,
                options = {
                    ClaimOptionsResult.Options(
                        vectors.challenge.challengeId, 1_798_765_432L,
                        Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                        Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
                    )
                },
                hpke = vectors.recipientPrivate,
                senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
                ownBundleKey = vectors.recipientPublicBytes,
                servicePin = vectors.servicePin,
                admittedStore = store,
                claim = ProofClaimPort { _, _, _, _, _ ->
                    dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                        vectors.claimId,
                        Base64.getEncoder().encodeToString(vectors.claim),
                        Base64.getEncoder().encodeToString(vectors.claimSig),
                        Base64.getEncoder().encodeToString(vectors.sealed),
                        vectors.sealedShaHex,
                    )
                },
            )
            driveToIntentReady(viewModel)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.GENERIC),
                viewModel.state.value,
            )
            assertEquals(null, store.get(accountId, capsule))
            assertEquals(null, store.get(accountId, otherCapsule))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 dd46 binding gate: the submit echo carries a different claim
     * id than the verified R. accept() still passes but the row gate
     * must reject — no row, no Claimed promotion.
     */
    @Test
    fun mismatchedSubmitClaimIdWritesNoRow() = runTest {
        val vectors = acceptedVectors()
        val dir = java.nio.file.Files.createTempDirectory("pending-claim-id-echo").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = vm(
                continueResult = vectors.proof,
                options = {
                    ClaimOptionsResult.Options(
                        vectors.challenge.challengeId, 1_798_765_432L,
                        Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                        Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
                    )
                },
                hpke = vectors.recipientPrivate,
                senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
                ownBundleKey = vectors.recipientPublicBytes,
                servicePin = vectors.servicePin,
                admittedStore = store,
                claim = ProofClaimPort { _, _, _, _, _ ->
                    dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                        otherClaimId,
                        Base64.getEncoder().encodeToString(vectors.claim),
                        Base64.getEncoder().encodeToString(vectors.claimSig),
                        Base64.getEncoder().encodeToString(vectors.sealed),
                        vectors.sealedShaHex,
                    )
                },
            )
            driveToIntentReady(viewModel)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.GENERIC),
                viewModel.state.value,
            )
            assertEquals(null, store.get(accountId, capsule))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 dd46 effect fence: the held lease dies mid-await (after R11
     * submit, during the key-provider awaits). No row is written and
     * the flow fails SESSION_CHANGED — never Claimed, never
     * serviceVerified promotion.
     */
    @Test
    fun midAwaitSessionDeathWritesNoRow() = runTest {
        val vectors = acceptedVectors()
        val dir = java.nio.file.Files.createTempDirectory("pending-claim-midawait").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            var gate: FakeGate? = null
            // R10 options consumes the first hpke fetch; the submit-path
            // fetch (second call) kills the lease — after the R11 submit
            // fence, before the new post-await/upsert fences.
            var hpkeCalls = 0
            val viewModel = vm(
                continueResult = vectors.proof,
                options = {
                    ClaimOptionsResult.Options(
                        vectors.challenge.challengeId, 1_798_765_432L,
                        Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                        Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
                    )
                },
                hpkeProvider = {
                    hpkeCalls += 1
                    if (hpkeCalls == 2) gate?.live = false
                    vectors.recipientPrivate
                },
                gateTap = { gate = it },
                senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
                ownBundleKey = vectors.recipientPublicBytes,
                servicePin = vectors.servicePin,
                admittedStore = store,
                claim = ProofClaimPort { _, _, _, _, _ ->
                    dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                        vectors.claimId,
                        Base64.getEncoder().encodeToString(vectors.claim),
                        Base64.getEncoder().encodeToString(vectors.claimSig),
                        Base64.getEncoder().encodeToString(vectors.sealed),
                        vectors.sealedShaHex,
                    )
                },
            )
            driveToIntentReady(viewModel)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Failed(PendingProofViewModel.PendingProofMessage.SESSION_CHANGED),
                viewModel.state.value,
            )
            assertEquals(null, store.get(accountId, capsule))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 alias-bind: shared wiring for the signed-intent capture
     * tests — the full Accepted flow with self-built vectors, the
     * caller-supplied signing key, and the R11-submitted intent bytes
     * captured for parse + real signature verification.
     */
    private fun kotlinx.coroutines.test.TestScope.aliasFlowVm(
        vectors: AcceptedVectors,
        signingKeys: KeysetHandle,
        submitted: Array<ByteArray?>,
        store: PendingAdmittedGiftStore,
    ): PendingProofViewModel = vm(
        continueResult = vectors.proof,
        options = {
            ClaimOptionsResult.Options(
                vectors.challenge.challengeId, 1_798_765_432L,
                Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
            )
        },
        hpke = vectors.recipientPrivate,
        signing = signingKeys,
        senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
        ownBundleKey = vectors.recipientPublicBytes,
        servicePin = vectors.servicePin,
        admittedStore = store,
        claim = ProofClaimPort { _, intentB64, sigB64, _, _ ->
            submitted[0] = Base64.getDecoder().decode(intentB64)
            submitted[1] = Base64.getDecoder().decode(sigB64)
            dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                vectors.claimId,
                Base64.getEncoder().encodeToString(vectors.claim),
                Base64.getEncoder().encodeToString(vectors.claimSig),
                Base64.getEncoder().encodeToString(vectors.sealed),
                vectors.sealedShaHex,
            )
        },
    )

    /** Real Ed25519 verification of a submitted intent under the test key. */
    private fun verifySubmittedIntent(
        signingKeys: KeysetHandle,
        intent: ByteArray,
        signature: ByteArray,
    ) {
        val input = PendingClaimIntent.CLAIM_INTENT_DOMAIN.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + intent
        val publicBytes = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
            signingKeys.publicKeysetHandle,
        )
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(publicBytes)
            .getPrimitive(PublicKeyVerify::class.java)
            .verify(signature, input)
    }

    private fun uuidString(bytes: com.google.protobuf.ByteString): String {
        val raw = bytes.toByteArray()
        require(raw.size == 16) { "uuid must be 16 bytes" }
        val buffer = java.nio.ByteBuffer.wrap(raw)
        return java.util.UUID(buffer.long, buffer.long).toString()
    }

    /**
     * T186 alias-bind: default unchecked, and the submitted signed
     * intent carries bindAlias=false (parsed + really verified).
     */
    @Test
    fun aliasDefaultsUncheckedAndStaysOutOfIntent() = runTest {
        TinkPrimitives.ensureRegistered()
        val vectors = acceptedVectors()
        val signingKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val submitted = arrayOfNulls<ByteArray>(2)
        val dir = java.nio.file.Files.createTempDirectory("pending-alias-default").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = aliasFlowVm(vectors, signingKeys, submitted, store)
            assertFalse(viewModel.aliasConsent.value)
            driveToIntentReady(viewModel)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Claimed(deliveryId, serviceVerified = true),
                viewModel.state.value,
            )
            val intent = submitted[0] ?: throw AssertionError("no submitted intent")
            val signature = submitted[1] ?: throw AssertionError("no submitted signature")
            assertFalse(ClaimIntentV2.parseFrom(intent).bindAlias)
            verifySubmittedIntent(signingKeys, intent, signature)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 alias-bind: an explicit opt-in before the request travels
     * into the canonical signed intent as bindAlias=true (parsed +
     * really verified); the frozen choice is visible at IntentReady.
     */
    @Test
    fun aliasAffirmedTravelsIntoSignedIntent() = runTest {
        TinkPrimitives.ensureRegistered()
        val vectors = acceptedVectors()
        val signingKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val submitted = arrayOfNulls<ByteArray>(2)
        val dir = java.nio.file.Files.createTempDirectory("pending-alias-true").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = aliasFlowVm(vectors, signingKeys, submitted, store)
            viewModel.start("ref")
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.ProofRequired)
            viewModel.onDestinationChange("a42@example.test")
            viewModel.onAliasConsentChange(true)
            assertTrue(viewModel.aliasConsent.value)
            viewModel.requestAttempt()
            testScheduler.advanceUntilIdle()
            viewModel.onCodeChange("12345678")
            viewModel.checkCode()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.IntentReady)
            assertTrue(viewModel.aliasConsent.value)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Claimed(deliveryId, serviceVerified = true),
                viewModel.state.value,
            )
            val intent = submitted[0] ?: throw AssertionError("no submitted intent")
            val signature = submitted[1] ?: throw AssertionError("no submitted signature")
            assertTrue(ClaimIntentV2.parseFrom(intent).bindAlias)
            verifySubmittedIntent(signingKeys, intent, signature)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 alias-bind: editing the destination clears the opt-in (and
     * the frozen pair) — a new address needs fresh explicit consent.
     */
    @Test
    fun destinationEditClearsAlias() = runTest {
        val viewModel = vm()
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.ProofRequired)
        viewModel.onAliasConsentChange(true)
        assertTrue(viewModel.aliasConsent.value)
        viewModel.onDestinationChange("other@example.test")
        assertFalse(viewModel.aliasConsent.value)
    }

    /**
     * T186 alias-bind: the toggle is refused outside ProofRequired —
     * a midflight attempt at CodeRequired and at IntentReady changes
     * nothing, and the submitted signed intent keeps the frozen false
     * (parsed + really verified).
     */
    @Test
    fun midflightToggleRefused() = runTest {
        TinkPrimitives.ensureRegistered()
        val vectors = acceptedVectors()
        val signingKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val submitted = arrayOfNulls<ByteArray>(2)
        val dir = java.nio.file.Files.createTempDirectory("pending-alias-midflight").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = aliasFlowVm(vectors, signingKeys, submitted, store)
            viewModel.start("ref")
            testScheduler.advanceUntilIdle()
            viewModel.onDestinationChange("a42@example.test")
            viewModel.requestAttempt()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.CodeRequired)
            viewModel.onAliasConsentChange(true)
            assertFalse(viewModel.aliasConsent.value)
            viewModel.onCodeChange("12345678")
            viewModel.checkCode()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.IntentReady)
            viewModel.onAliasConsentChange(true)
            assertFalse(viewModel.aliasConsent.value)
            viewModel.submitClaim()
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Claimed(deliveryId, serviceVerified = true),
                viewModel.state.value,
            )
            val intent = submitted[0] ?: throw AssertionError("no submitted intent")
            val signature = submitted[1] ?: throw AssertionError("no submitted signature")
            assertFalse(ClaimIntentV2.parseFrom(intent).bindAlias)
            verifySubmittedIntent(signingKeys, intent, signature)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 alias-bind: frozen setters are refused at IntentReady and
     * while Claiming — destination, bit, submitted intent bytes and
     * operation id stay exactly as frozen (proved by parsing the
     * submitted intent against the vectors + real signature check).
     * The deferred claim port holds the flow in Claiming so the
     * refusal window is actually observed.
     */
    @Test
    fun claimingToggleRefused() = runTest {
        TinkPrimitives.ensureRegistered()
        val vectors = acceptedVectors()
        val signingKeys = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val submitted = arrayOfNulls<ByteArray>(2)
        var submittedOpId: String? = null
        val release = CompletableDeferred<Unit>()
        val dir = java.nio.file.Files.createTempDirectory("pending-alias-claiming").toFile()
        try {
            val store = PendingAdmittedGiftStore(dir)
            val viewModel = vm(
                continueResult = vectors.proof,
                options = {
                    ClaimOptionsResult.Options(
                        vectors.challenge.challengeId, 1_798_765_432L,
                        Base64.getEncoder().encodeToString(vectors.challenge.sealed),
                        Base64.getEncoder().encodeToString(vectors.challenge.contextRaw),
                    )
                },
                hpke = vectors.recipientPrivate,
                signing = signingKeys,
                senderKeyProvider = { _, _ -> vectors.senderPublicBytes },
                ownBundleKey = vectors.recipientPublicBytes,
                servicePin = vectors.servicePin,
                admittedStore = store,
                claim = ProofClaimPort { _, intentB64, sigB64, opId, _ ->
                    submitted[0] = Base64.getDecoder().decode(intentB64)
                    submitted[1] = Base64.getDecoder().decode(sigB64)
                    submittedOpId = opId
                    release.await()
                    dev.hryshyn.remanence.core.data.network.ClaimSubmitResult.Claimed(
                        vectors.claimId,
                        Base64.getEncoder().encodeToString(vectors.claim),
                        Base64.getEncoder().encodeToString(vectors.claimSig),
                        Base64.getEncoder().encodeToString(vectors.sealed),
                        vectors.sealedShaHex,
                    )
                },
            )
            viewModel.start("ref")
            testScheduler.advanceUntilIdle()
            viewModel.onDestinationChange("a42@example.test")
            viewModel.onAliasConsentChange(true)
            viewModel.requestAttempt()
            testScheduler.advanceUntilIdle()
            viewModel.onCodeChange("12345678")
            viewModel.checkCode()
            testScheduler.advanceUntilIdle()
            assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.IntentReady)
            assertTrue(viewModel.aliasConsent.value)
            // Frozen setters refused at IntentReady: destination, bit
            // and the pending submit stay exactly as frozen.
            viewModel.onDestinationChange("other@example.test")
            assertEquals("a42@example.test", viewModel.destination.value)
            viewModel.onAliasConsentChange(false)
            assertTrue(viewModel.aliasConsent.value)
            viewModel.submitClaim()
            assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.Claiming)
            // ... and while Claiming: the in-flight signed intent/digest
            // cannot change under the same operation id.
            viewModel.onDestinationChange("other2@example.test")
            assertEquals("a42@example.test", viewModel.destination.value)
            viewModel.onAliasConsentChange(false)
            assertTrue(viewModel.aliasConsent.value)
            release.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertEquals(
                PendingProofViewModel.ProofState.Claimed(deliveryId, serviceVerified = true),
                viewModel.state.value,
            )
            val intent = submitted[0] ?: throw AssertionError("no submitted intent")
            val signature = submitted[1] ?: throw AssertionError("no submitted signature")
            val parsed = ClaimIntentV2.parseFrom(intent)
            assertTrue(parsed.bindAlias)
            assertEquals(capsule, uuidString(parsed.capsuleId))
            assertEquals(target, uuidString(parsed.deliveryTargetId))
            assertEquals(vectors.challenge.challengeId, uuidString(parsed.challengeId))
            // Single-assignment operation id rides along, well-formed.
            java.util.UUID.fromString(submittedOpId ?: throw AssertionError("no submitted opId"))
            verifySubmittedIntent(signingKeys, intent, signature)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T186 alias-bind: a session/owner death clears the opt-in — the
     * next request needs fresh explicit consent even for the same
     * gift and address.
     */
    @Test
    fun sessionDeathClearsAlias() = runTest {
        var gate: FakeGate? = null
        val viewModel = vm(gateTap = { gate = it })
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.ProofRequired)
        viewModel.onDestinationChange("a42@example.test")
        viewModel.onAliasConsentChange(true)
        assertTrue(viewModel.aliasConsent.value)
        gate?.live = false
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
        assertFalse(viewModel.aliasConsent.value)
    }

    /**
     * T186 alias-bind: a genuine checkbox-to-R8 owner switch — tap
     * under A, then BOTH the account and the capture move to another
     * valid owner inside the deferred launch window. The tap-time
     * origin no longer matches the held R8 credentials, so the new
     * pre-R8 validation fails closed with ZERO R8 calls (the old entry
     * fence passes: everything is B-consistent).
     */
    @Test
    fun ownerSwitchPreDispatchClearsAlias() = runTest {
        val deferred = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + deferred)
        var gate: FakeGate? = null
        var currentAccount: ProofAccount? = ProofAccount(accountId, bundleId)
        var r8calls = 0
        val viewModel = vm(
            gateTap = { gate = it },
            accountProvider = { currentAccount },
            onRequest = { r8calls += 1 },
            scope = scope,
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is PendingProofViewModel.ProofState.ProofRequired)
        viewModel.onDestinationChange("a42@example.test")
        viewModel.onAliasConsentChange(true)
        assertTrue(viewModel.aliasConsent.value)
        viewModel.requestAttempt()
        // Deferred window between the tap and R8 dispatch: the whole
        // scope moves to another valid owner.
        currentAccount = ProofAccount("99999999-2222-3333-4444-555555555555", bundleId)
        gate?.owner = "99999999-2222-3333-4444-555555555555"
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
        assertFalse(viewModel.aliasConsent.value)
        assertEquals(0, r8calls)
        viewModel.submitClaim()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
    }

    /**
     * T186 alias-bind: a same-owner incarnation rotation inside the
     * deferred pre-R8 window fails the tap-time origin check — every
     * older fence still passes (owner + liveness hold), ZERO R8 calls,
     * no intent.
     */
    @Test
    fun incarnationSwitchPreDispatchClearsAlias() = runTest {
        val deferred = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + deferred)
        var gate: FakeGate? = null
        var r8calls = 0
        val viewModel = vm(
            gateTap = { gate = it },
            onRequest = { r8calls += 1 },
            scope = scope,
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        viewModel.onDestinationChange("a42@example.test")
        viewModel.onAliasConsentChange(true)
        viewModel.requestAttempt()
        // Deferred window between the tap and R8 dispatch: same owner,
        // new incarnation. Older fences still pass; only the tap-time
        // origin check trips.
        gate?.epoch = 2L
        testScheduler.advanceUntilIdle()
        // This flow never reached R8 (pre-R8 validation tripped), so
        // there is no CodeRequired state — only the cleared failure.
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
        assertFalse(viewModel.aliasConsent.value)
        assertEquals(0, r8calls)
    }

    /**
     * T186 alias-bind: an owner flap suspended inside the options
     * await fails the frozen context closed at intent-build time.
     * Older post-await fences still pass (the held lease never died),
     * so only the new validation — fresh account versus tap origin —
     * trips. The claim port is armed to fail loudly if anything ever
     * dispatches an intent.
     */
    @Test
    fun accountFlapSuspendedAwaitClearsAlias() = runTest {
        var currentAccount: ProofAccount? = ProofAccount(accountId, bundleId)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val setup = challengeSetup()
        val viewModel = vm(
            accountProvider = { currentAccount },
            options = {
                entered.complete(Unit)
                release.await()
                ClaimOptionsResult.Options(
                    setup.challengeId, 1_798_765_432L,
                    Base64.getEncoder().encodeToString(setup.sealed),
                    Base64.getEncoder().encodeToString(setup.contextRaw),
                )
            },
            hpke = setup.recipientPrivate,
            senderKeyProvider = { _, _ -> frozenSenderKey() },
            ownBundleKey = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                setup.recipientPrivate.publicKeysetHandle,
            ),
            claim = ProofClaimPort { _, _, _, _, _ ->
                throw AssertionError("no intent may dispatch after a mid-await flap")
            },
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        viewModel.onDestinationChange("a42@example.test")
        viewModel.onAliasConsentChange(true)
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        viewModel.onCodeChange("12345678")
        viewModel.checkCode()
        assertTrue(entered.isCompleted)
        // Owner flap while the options await is suspended.
        currentAccount = ProofAccount("99999999-2222-3333-4444-555555555555", bundleId)
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
        assertFalse(viewModel.aliasConsent.value)
    }

    /**
     * T186 alias-bind: a same-owner incarnation rotation suspended
     * inside the options await fails at the post-await lease fence
     * (incarnation-aware liveness) — distinct trip point from the
     * account flap above, same closed outcome: no intent, cleared
     * consent, no dispatch.
     */
    @Test
    fun incarnationFlapSuspendedAwaitFailsLeaseFence() = runTest {
        var gate: FakeGate? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val setup = challengeSetup()
        val viewModel = vm(
            gateTap = { gate = it },
            options = {
                entered.complete(Unit)
                release.await()
                ClaimOptionsResult.Options(
                    setup.challengeId, 1_798_765_432L,
                    Base64.getEncoder().encodeToString(setup.sealed),
                    Base64.getEncoder().encodeToString(setup.contextRaw),
                )
            },
            hpke = setup.recipientPrivate,
            senderKeyProvider = { _, _ -> frozenSenderKey() },
            ownBundleKey = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                setup.recipientPrivate.publicKeysetHandle,
            ),
            claim = ProofClaimPort { _, _, _, _, _ ->
                throw AssertionError("no intent may dispatch after a mid-await rotation")
            },
        )
        viewModel.start("ref")
        testScheduler.advanceUntilIdle()
        viewModel.onDestinationChange("a42@example.test")
        viewModel.onAliasConsentChange(true)
        viewModel.requestAttempt()
        testScheduler.advanceUntilIdle()
        viewModel.onCodeChange("12345678")
        viewModel.checkCode()
        assertTrue(entered.isCompleted)
        // Same owner, new incarnation, while suspended.
        gate?.epoch = 2L
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingProofViewModel.ProofState.Failed(PendingProofMessage.SESSION_CHANGED),
            viewModel.state.value,
        )
        assertFalse(viewModel.aliasConsent.value)
    }
}
