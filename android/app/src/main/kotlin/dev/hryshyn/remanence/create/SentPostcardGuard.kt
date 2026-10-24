package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.data.db.FingerprintOrigin
import dev.hryshyn.remanence.core.data.db.RecognitionFingerprintEntity
import dev.hryshyn.remanence.core.model.FrontFingerprintCodecs
import dev.hryshyn.remanence.core.recognition.FrozenMatcherS3
import dev.hryshyn.remanence.core.recognition.FrozenMatcherS3Fingerprint

/**
 * One postcard per sender and recipient. Compares a new FRONT with the FRONTs
 * of the sender's earlier capsules to the same person; a strict frozen-matcher
 * match means the same postcard. Only S3 fingerprints take part.
 */
object SentPostcardGuard {
    suspend fun alreadySent(
        capsuleIds: Set<String>,
        rows: List<RecognitionFingerprintEntity>,
        decrypt: suspend (String) -> ByteArray,
        vocabulary: () -> FrozenMatcherS3.Vocabulary?,
        front: ByteArray,
    ): Boolean {
        val earlier = rows.filter { it.origin == FingerprintOrigin.SENDER && it.capsuleId in capsuleIds }
        if (earlier.isEmpty()) return false
        val fresh = FrontFingerprintCodecs.parse(front)
        if (!FrozenMatcherS3Fingerprint.isS3(fresh)) return false.also { fresh.wipe() }
        val words = vocabulary() ?: return false.also { fresh.wipe() }
        val candidate = FrozenMatcherS3Fingerprint.features(fresh)
        fresh.wipe()
        return earlier.any { row ->
            val bytes = runCatching { decrypt(row.fingerprintId) }.getOrNull() ?: return@any false
            try {
                val old = runCatching { FrontFingerprintCodecs.parse(bytes) }.getOrNull() ?: return@any false
                try {
                    FrozenMatcherS3Fingerprint.isS3(old) &&
                        FrozenMatcherS3.sameCard(candidate, FrozenMatcherS3Fingerprint.features(old), words)
                } finally {
                    old.wipe()
                }
            } finally {
                bytes.fill(0)
            }
        }
    }
}
