package dev.hryshyn.remanence.pending

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.core.crypto.CapsuleKeysetGenerator
import dev.hryshyn.remanence.core.crypto.PendingCapsulePublisher
import dev.hryshyn.remanence.core.crypto.PendingCardSeal
import dev.hryshyn.remanence.core.crypto.PendingClaimChallenge
import dev.hryshyn.remanence.core.crypto.PendingPublishCrypto
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.CardGift
import dev.hryshyn.remanence.core.data.network.ClaimOptionsResult
import dev.hryshyn.remanence.core.data.network.ClaimSubmitResult
import dev.hryshyn.remanence.core.data.network.ContinueResult
import dev.hryshyn.remanence.core.data.network.PhoneProofCheck
import dev.hryshyn.remanence.core.data.network.PhoneProofFailure
import dev.hryshyn.remanence.core.data.network.PhoneProofStart
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.data.network.VerificationResult
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimChallengeV1
import dev.hryshyn.remanence.protocol.delivery_v2.PendingCardSealV1
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-020 on the recipient's phone: real crypto, fake network. */
class PendingCardFlowViewModelTest {
    private val capsule = UUID.randomUUID().toString()
    private val target = UUID.randomUUID().toString()
    private val sender = UUID.randomUUID().toString()
    private val senderBundle = UUID.randomUUID().toString()
    private val serviceKeyId = UUID.randomUUID().toString()
    private val account = UUID.randomUUID().toString()
    private val bundle = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()
    private val delivery = UUID.randomUUID().toString()
    private val proofId = UUID.randomUUID().toString()
    private val now = System.currentTimeMillis() / 1000L

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun id16(value: String): ByteString {
        val id = UUID.fromString(value)
        return ByteString.copyFrom(
            java.nio.ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array(),
        )
    }

    private inner class World {
        val senderSigning: KeysetHandle
        val service: KeysetHandle
        val hpke: KeysetHandle
        val signing: KeysetHandle
        val publication: PendingCapsulePublisher.Publication
        val code: String
        var claimedProof: ByteArray? = null
        var claimedEnvelope: ByteArray? = null
        var smsAsked = 0

        init {
            TinkPrimitives.ensureRegistered()
            senderSigning = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            service = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            hpke = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE))
            signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            publication = PendingCapsulePublisher.publish(
                capsuleId = capsule, senderUserId = sender, senderKeyBundleId = senderBundle,
                deliveryTargetId = target, serviceKeyId = serviceKeyId, channel = PendingPublishCrypto.CHANNEL_SMS,
                normalizedDestination = "+31612345612", salt32 = ByteArray(32) { 3 },
                claimExpiresAt = now + 86_400, createdAtEpochSeconds = now, signingHandle = senderSigning,
                capsuleKeyset = CapsuleKeysetGenerator().generate(), photos = listOf(ByteArray(64) { 1 }),
                cardSenderPublic = KeysetHandle.generateNew(KeyTemplates.get(TinkPrimitives.HPKE_TEMPLATE)).publicKeysetHandle,
            )
            code = publication.card!!.code
        }

        private val publicKeyset by lazy { TinkProtoKeysetFormat.serializeKeysetWithoutSecret(hpke.publicKeysetHandle) }
        private val binding by lazy {
            ClaimBindingV1.newBuilder()
                .setBindingVersion(1).setCapsuleProtocolVersion(2)
                .setCapsuleId(id16(capsule)).setDeliveryTargetId(id16(target))
                .setPublishStatementSha256(ByteString.copyFrom(sha(publication.statement)))
                .setAddressCommitment(PendingCardSealV1.parseFrom(publication.card!!.seal).binding.addressCommitment)
                .setRecipientUserId(id16(account)).setRecipientKeyBundleId(id16(bundle))
                .setRecipientEncryptionPublicKeysetSha256(ByteString.copyFrom(sha(publicKeyset)))
                .setClaimId(id16(UUID.randomUUID().toString())).setClaimedAtEpochSeconds(now)
                .setClaimPolicyVersion(1).setClaimSigningKeyId(id16(serviceKeyId))
                .build().toByteArray()
        }

        private fun options(): ClaimOptionsResult.Options {
            val nonce = ByteArray(32) { 9 }
            val challengeId = UUID.randomUUID().toString()
            val context = ClaimChallengeV1.newBuilder()
                .setChallengeId(id16(challengeId)).setCapsuleId(id16(capsule)).setDeliveryTargetId(id16(target))
                .setRecipientUserId(id16(account)).setRecipientKeyBundleId(id16(bundle)).setSessionId(id16(session))
                .setNonceSha256(ByteString.copyFrom(sha(nonce))).setExpiresAtEpochSeconds(now + 300)
                .build().toByteArray()
            val sealed = hpke.publicKeysetHandle.getPrimitive(HybridEncrypt::class.java).encrypt(
                nonce, PendingClaimChallenge.CLAIM_CHALLENGE_DOMAIN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + context,
            )
            return ClaimOptionsResult.Options(challengeId, now + 300, b64(sealed), b64(context), b64(binding))
        }

        fun vm(): PendingProofViewModel {
            val owner = UserId.parseRest(account)
            return PendingProofViewModel(
                continuePort = ProofContinuePort { _, _ -> ContinueResult.Failure(dev.hryshyn.remanence.core.data.network.GiftLookupFailure.GIFT_UNAVAILABLE, 404, false) },
                verifyPort = object : ProofVerifyPort {
                    override suspend fun request(deliveryId: String, destination: String, smsConsent: Boolean, nowSeconds: Long, idempotencyKey: String, token: String): VerificationResult = error("not used")
                    override suspend fun check(deliveryId: String, attemptId: String, code: String, token: String): VerificationResult = error("not used")
                },
                optionsPort = ProofOptionsPort { _, _ -> options() },
                claimPort = ProofClaimPort { _, _, _, _, _, envelope, proof ->
                    claimedEnvelope = Base64.getDecoder().decode(envelope)
                    claimedProof = Base64.getDecoder().decode(proof)
                    ClaimSubmitResult.Claimed(
                        ClaimBindingV1.parseFrom(binding).claimId.toByteArray().let {
                            val buffer = java.nio.ByteBuffer.wrap(it); UUID(buffer.long, buffer.long).toString()
                        },
                        b64(binding),
                        b64(service.getPrimitive(PublicKeySign::class.java).sign("postmark/claim-binding/v1".toByteArray() + byteArrayOf(0) + binding)),
                        b64(claimedEnvelope!!),
                        sha(claimedEnvelope!!).joinToString("") { "%02x".format(it) },
                    )
                },
                sessionGate = object : PendingRetirementSessionGate {
                    private val lease = SessionRequestLease(ownerUserId = owner, incarnation = 1L)
                    override fun capture() = lease
                    override fun accessTokenFor(lease: SessionRequestLease) = "token"
                    override fun isLive(lease: SessionRequestLease) = true
                },
                accountProvider = { ProofAccount(account, bundle) },
                signingKeyProvider = { signing },
                hpkeKeyProvider = { hpke },
                senderKeyProvider = { _, _ -> TinkProtoKeysetFormat.serializeKeysetWithoutSecret(senderSigning.publicKeysetHandle) },
                ownBundleKeyProvider = { publicKeyset },
                sessionIdProvider = { session },
                servicePinProvider = {
                    PendingServicePin(serviceKeyId, b64(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(service.publicKeysetHandle)))
                },
                scope = CoroutineScope(Dispatchers.Unconfined),
                phoneProofPort = object : PhoneProofPort {
                    override suspend fun start(phoneE164: String, token: String): PhoneProofStart {
                        smsAsked += 1
                        return PhoneProofStart.Started(proofId)
                    }
                    override suspend fun check(proofId: String, code: String, token: String): PhoneProofCheck =
                        if (code != "123456") PhoneProofCheck.Failed(PhoneProofFailure.WRONG_CODE)
                        else PhoneProofCheck.Gifts(
                            listOf(
                                CardGift(
                                    delivery, capsule, target, proofId, now + 86_400, b64(publication.statement),
                                    b64(publication.signature), b64(publication.card!!.seal),
                                ),
                            ),
                        )
                },
            )
        }

        fun proofVerifies(): Boolean = sha(claimedProof!!).contentEquals(
            PendingCardSealV1.parseFrom(publication.card!!.seal).claimProofVerifier.toByteArray(),
        )
    }

    private suspend fun PendingProofViewModel.waitFor(check: (PendingProofViewModel.ProofState) -> Boolean) =
        withTimeout(30_000) { state.first(check) }

    @Test
    fun cardAndPhoneTogetherOpenTheGift() = runBlocking {
        val world = World()
        val vm = world.vm()
        val parsed = PendingCardSeal.parseCode(world.code)!!
        vm.startCard(parsed)
        assertEquals("12", (vm.state.value as PendingProofViewModel.ProofState.PhoneRequired).hintDigits)
        vm.requestPhoneCode("+31612345612")
        vm.waitFor { it is PendingProofViewModel.ProofState.CodeRequired }
        assertEquals(1, world.smsAsked)
        vm.onCodeChange("123456")
        vm.checkCode()
        val done = vm.waitFor { it is PendingProofViewModel.ProofState.Claimed || it is PendingProofViewModel.ProofState.Failed }
        assertEquals(PendingProofViewModel.ProofState.Claimed(delivery, serviceVerified = true), done)
        assertTrue("the proof must pass the sender's verifier", world.proofVerifies())
    }

    @Test
    fun wrongCardCodeNeedsNoNewSmsAndNothingIsClaimed() = runBlocking {
        val world = World()
        val vm = world.vm()
        val right = PendingCardSeal.parseCode(world.code)!!
        vm.startCard(PendingCardSeal.ParsedCode(if (right.secret == "0000000") "1111111" else "0000000", right.hintDigits))
        vm.requestPhoneCode("+31612345612")
        vm.waitFor { it is PendingProofViewModel.ProofState.CodeRequired }
        vm.onCodeChange("123456")
        vm.checkCode()
        vm.waitFor { it === PendingProofViewModel.ProofState.WrongCardCode }
        assertNull(world.claimedProof)
        vm.startCard(right)
        val done = vm.waitFor { it is PendingProofViewModel.ProofState.Claimed || it is PendingProofViewModel.ProofState.Failed }
        assertEquals(PendingProofViewModel.ProofState.Claimed(delivery, serviceVerified = true), done)
        assertEquals(1, world.smsAsked)
    }

    @Test
    fun wrongSmsCodeKeepsTheProofAndRetries() = runBlocking {
        val world = World()
        val vm = world.vm()
        vm.startCard(PendingCardSeal.parseCode(world.code)!!)
        vm.requestPhoneCode("+31612345612")
        vm.waitFor { it is PendingProofViewModel.ProofState.CodeRequired }
        vm.onCodeChange("000000")
        vm.checkCode()
        vm.waitFor { it is PendingProofViewModel.ProofState.Failed }
        vm.retry()
        assertTrue(vm.state.value is PendingProofViewModel.ProofState.CodeRequired)
        assertNull(world.claimedProof)
    }
}
