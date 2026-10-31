package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.protobuf.ByteString
import dev.hryshyn.remanence.protocol.delivery_v2.AddressOpening
import dev.hryshyn.remanence.protocol.delivery_v2.CardKdfV1
import dev.hryshyn.remanence.protocol.delivery_v2.CardSealAadV1
import dev.hryshyn.remanence.protocol.delivery_v2.CardSealPlaintextV1
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimBindingV1
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopeContextV2
import dev.hryshyn.remanence.protocol.delivery_v2.ClaimedEnvelopePlaintextV2
import dev.hryshyn.remanence.protocol.delivery_v2.PendingCardSealV1
import dev.hryshyn.remanence.protocol.delivery_v2.PendingPublishStatementV2
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * ADR-020: the first gift is keyed by the card. The code from the card plus a
 * random server half unlock the capsule key, and neither side holds both.
 * Byte-for-byte equal to `protocol/fixtures/card-seal-v1.json`.
 */
object PendingCardSeal {

    const val ALPHABET: String = "0123456789ACDEFHJKMNPQRTVWXY"
    const val SECRET_LENGTH: Int = 7
    const val KDF_TIME_COST: Int = 3
    const val KDF_MEMORY_KIB: Int = 65536
    const val KDF_PARALLELISM: Int = 1

    private const val AAD_DOMAIN = "remanence/card-seal/aad/v1"
    private const val KEK_DOMAIN = "remanence/card-seal/kek/v1"
    private const val PROOF_DOMAIN = "remanence/card-seal/claim-proof/v1"
    private const val SENDER_CODE_DOMAIN = "remanence/card-seal/sender-code/v1"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** Handwritten look-alikes map on input and are never emitted. */
    private val INPUT_MAP = mapOf(
        'O' to '0', 'I' to '1', 'L' to '1', 'B' to '8', 'G' to '6', 'S' to '5', 'Z' to '2', 'U' to 'V',
    )

    data class ParsedCode(val secret: String, val hintDigits: String)

    data class Derived(val kek: ByteArray, val proof: ByteArray)

    data class Opened(
        val capsuleAeadKeyset: ByteArray,
        val addressOpening: AddressOpening,
        val proof: ByteArray,
    )

    fun generateSecret(random: SecureRandom = SecureRandom()): String =
        String(CharArray(SECRET_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** `X4T-P9R-K12`: the last two symbols are the end of the phone number. */
    fun formatCode(secret: String, phoneE164: String): String {
        require(secret.length == SECRET_LENGTH) { "card secret length" }
        require(phoneE164.length >= 2) { "card phone length" }
        val whole = secret + phoneE164.takeLast(2)
        return "${whole.substring(0, 3)}-${whole.substring(3, 6)}-${whole.substring(6)}"
    }

    /** Null for anything that is not a code. Never echoes the input. */
    fun parseCode(typed: String): ParsedCode? {
        val mapped = typed.uppercase(java.util.Locale.ROOT)
            .filterNot { it == ' ' || it == '-' }
            .map { INPUT_MAP[it] ?: it }
            .joinToString("")
        if (mapped.length != SECRET_LENGTH + 2) return null
        if (mapped.any { it !in ALPHABET }) return null
        val hint = mapped.substring(SECRET_LENGTH)
        if (!hint.all { it in '0'..'9' }) return null
        return ParsedCode(mapped.substring(0, SECRET_LENGTH), hint)
    }

    fun derive(secret: String, kdf: CardKdfV1, serverHalf: ByteArray, statementSha256: ByteArray): Derived {
        requireExactKdf(kdf)
        if (serverHalf.size != 32 || statementSha256.size != 32) {
            throw GeneralSecurityException("card seal input size")
        }
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(kdf.timeCost)
                .withMemoryAsKB(kdf.memoryKib)
                .withParallelism(kdf.parallelism)
                .withSalt(kdf.salt.toByteArray())
                .build(),
        )
        val cardKey = ByteArray(32)
        generator.generateBytes(secret.toByteArray(Charsets.US_ASCII), cardKey)
        val ikm = cardKey + serverHalf
        try {
            return Derived(
                hkdfSha256(ikm, domain(KEK_DOMAIN) + statementSha256),
                hkdfSha256(ikm, domain(PROOF_DOMAIN) + statementSha256),
            )
        } finally {
            cardKey.fill(0)
            ikm.fill(0)
        }
    }

    fun aadBytes(binding: CardSealAadV1): ByteArray = domain(AAD_DOMAIN) + binding.toByteArray()

    /** Sender side. [salt], [serverHalf] and [nonce] are fixed only by the vector test. */
    fun build(
        secret: String,
        capsuleAeadKeyset: ByteArray,
        openingCanonical: ByteArray,
        binding: CardSealAadV1,
        salt: ByteArray = randomBytes(32),
        serverHalf: ByteArray = randomBytes(32),
        nonce: ByteArray = randomBytes(NONCE_BYTES),
    ): PendingCardSealV1 {
        val kdf = CardKdfV1.newBuilder()
            .setVersion(1)
            .setTimeCost(KDF_TIME_COST)
            .setMemoryKib(KDF_MEMORY_KIB)
            .setParallelism(KDF_PARALLELISM)
            .setSalt(ByteString.copyFrom(salt))
            .build()
        val derived = derive(secret, kdf, serverHalf, binding.publishStatementSha256.toByteArray())
        val plaintext = CardSealPlaintextV1.newBuilder()
            .setVersion(1)
            .setCapsuleAeadKeyset(ByteString.copyFrom(capsuleAeadKeyset))
            .setAddressOpening(AddressOpening.parseFrom(openingCanonical))
            .build()
            .toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived.kek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aadBytes(binding))
            return PendingCardSealV1.newBuilder()
                .setVersion(1)
                .setBinding(binding)
                .setKdf(kdf)
                .setServerHalf(ByteString.copyFrom(serverHalf))
                .setSealedKey(ByteString.copyFrom(nonce + cipher.doFinal(plaintext)))
                .setClaimProofVerifier(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(derived.proof)))
                .build()
        } finally {
            plaintext.fill(0)
            derived.kek.fill(0)
            derived.proof.fill(0)
        }
    }

    /** Recipient side. Null when the code does not open this seal. */
    fun open(secret: String, seal: PendingCardSealV1): Opened? {
        val sealed = seal.sealedKey.toByteArray()
        if (sealed.size <= NONCE_BYTES) return null
        val derived = derive(
            secret, seal.kdf, seal.serverHalf.toByteArray(), seal.binding.publishStatementSha256.toByteArray(),
        )
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(derived.kek, "AES"),
                GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
            )
            cipher.updateAAD(aadBytes(seal.binding))
            val plain = cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
            try {
                val parsed = CardSealPlaintextV1.parseFrom(plain)
                Opened(parsed.capsuleAeadKeyset.toByteArray(), parsed.addressOpening, derived.proof.copyOf())
            } finally {
                plain.fill(0)
            }
        } catch (_: javax.crypto.AEADBadTagException) {
            null
        } finally {
            derived.kek.fill(0)
            derived.proof.fill(0)
        }
    }

    /**
     * The device seals the ordinary claimed envelope to its own account key.
     * Same bytes the service used to produce, so admission does not change.
     */
    fun sealEnvelopeToSelf(
        opened: Opened,
        statementBytes: ByteArray,
        claimBindingBytes: ByteArray,
        recipientPublicHandle: KeysetHandle,
    ): ByteArray {
        val statement = PendingPublishStatementV2.parseFrom(statementBytes)
        val binding = ClaimBindingV1.parseFrom(claimBindingBytes)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val context = ClaimedEnvelopeContextV2.newBuilder()
            .setProtocolVersion(2)
            .setCapsuleId(statement.capsuleId)
            .setDeliveryTargetId(statement.deliveryTargetId)
            .setSenderUserId(statement.senderUserId)
            .setRecipientUserId(binding.recipientUserId)
            .setRecipientKeyBundleId(binding.recipientKeyBundleId)
            .setPublishStatementSha256(ByteString.copyFrom(sha256.digest(statementBytes)))
            .setClaimBindingSha256(ByteString.copyFrom(sha256.digest(claimBindingBytes)))
            .build()
        val plaintext = ClaimedEnvelopePlaintextV2.newBuilder()
            .setBinding(context)
            .setCapsuleAeadKeyset(ByteString.copyFrom(opened.capsuleAeadKeyset))
            .setAddressOpening(opened.addressOpening)
            .build()
            .toByteArray()
        try {
            return recipientPublicHandle.getPrimitive(HybridEncrypt::class.java).encrypt(
                plaintext,
                domain(PendingClaimedEnvelope.CLAIMED_ENVELOPE_DOMAIN) + context.toByteArray(),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    /** The card code sealed to the sender's own key, so History can show it again. */
    fun sealCodeToSelf(code: String, capsuleId: String, senderPublicHandle: KeysetHandle): ByteArray =
        senderPublicHandle.getPrimitive(HybridEncrypt::class.java).encrypt(
            code.toByteArray(Charsets.US_ASCII),
            domain(SENDER_CODE_DOMAIN) + capsuleId.toByteArray(Charsets.US_ASCII),
        )

    fun openCodeFromSelf(sealed: ByteArray, capsuleId: String, senderPrivateHandle: KeysetHandle): String =
        String(
            senderPrivateHandle.getPrimitive(HybridDecrypt::class.java).decrypt(
                sealed,
                domain(SENDER_CODE_DOMAIN) + capsuleId.toByteArray(Charsets.US_ASCII),
            ),
            Charsets.US_ASCII,
        )

    private fun requireExactKdf(kdf: CardKdfV1) {
        if (kdf.version != 1 || kdf.timeCost != KDF_TIME_COST || kdf.memoryKib != KDF_MEMORY_KIB ||
            kdf.parallelism != KDF_PARALLELISM || kdf.salt.size() != 32
        ) {
            throw GeneralSecurityException("card seal parameters refused")
        }
    }

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        expand.update(info)
        expand.update(0x01)
        return expand.doFinal().also { prk.fill(0) }
    }

    private fun domain(name: String): ByteArray = name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
}
