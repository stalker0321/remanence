package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.ui.collage.CollageCandidate
import java.security.MessageDigest

/** A style applied to a grammar is one choice, regardless of its photo assignment. */
internal data class CollageVariantKey(val grammarId: String, val styleId: String)

internal fun CollageCandidate.variantKey(): CollageVariantKey = CollageVariantKey(
    expression.grammarId,
    expression.style?.styleId ?: styleId,
)

/**
 * A draft's random capsule UUID seeds the order. Ranking each variant separately
 * keeps surviving variants in the same relative order when content edits add or
 * remove choices. Branches, photo permutations and crop corrections never affect
 * that rank.
 */
internal class CollageVariantOrder(private val draftSeed: String) {
    /** Must receive only candidates that passed the recipient's real prepare. */
    fun arrangePrepared(candidates: List<CollageCandidate>): List<CollageCandidate> =
        candidates.distinctBy { it.variantKey() }
            .map { candidate -> Ranked(candidate, candidate.variantKey().rank()) }
            .sortedWith(compareBy<Ranked>({ it.rank },
                { it.candidate.variantKey().grammarId }, { it.candidate.variantKey().styleId }))
            .map { it.candidate }

    private fun CollageVariantKey.rank(): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$draftSeed\u0000$grammarId\u0000$styleId".toByteArray(Charsets.UTF_8))
        return digest.take(Long.SIZE_BYTES).fold(0L) { value, byte ->
            (value shl 8) or (byte.toLong() and 0xffL)
        }
    }

    private data class Ranked(val candidate: CollageCandidate, val rank: Long)
}
