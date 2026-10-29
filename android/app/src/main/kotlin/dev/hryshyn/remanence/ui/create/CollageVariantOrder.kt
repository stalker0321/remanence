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
 * A draft's random capsule UUID seeds the order. Styles of one grammar differ mostly
 * by palette, so the chooser shows layouts first: one card per grammar in a seeded
 * style, then the remaining styles, grammar by grammar in the same order.
 *
 * Grammars are in two tiers. The first holds the reliable core and the grammars that
 * only assemble when the content suits them (strips for wide photos, sleeves for music),
 * so a fitting specialist is not buried behind palette repeats. Inside a tier the order
 * is a seeded rank per grammar, so surviving grammars keep their relative order when
 * content edits add or remove choices. Branches, photo permutations and crop
 * corrections never affect the rank.
 */
internal class CollageVariantOrder(private val draftSeed: String) {
    /** Must receive only candidates that passed the recipient's real prepare. */
    fun arrangePrepared(candidates: List<CollageCandidate>): List<CollageCandidate> {
        val byGrammar = candidates.distinctBy { it.variantKey() }
            .groupBy { it.variantKey().grammarId }
            .mapValues { (grammar, styles) ->
                styles.sortedWith(compareBy({ rank(grammar, it.variantKey().styleId) }, { it.variantKey().styleId }))
            }
        val grammars = byGrammar.keys.sortedWith(compareBy({ if (it in FIRST_TIER) 0 else 1 }, { rank(it, null) }, { it }))
        val depth = byGrammar.values.maxOfOrNull { it.size } ?: 0
        return (0 until depth).flatMap { round -> grammars.mapNotNull { byGrammar.getValue(it).getOrNull(round) } }
    }

    private fun rank(grammarId: String, styleId: String?): Long {
        val key = if (styleId == null) "$draftSeed\u0000$grammarId" else "$draftSeed\u0000$grammarId\u0000$styleId"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.take(Long.SIZE_BYTES).fold(0L) { value, byte ->
            (value shl 8) or (byte.toLong() and 0xffL)
        }
    }

    internal companion object {
        /** Reliable and well liked in the playground study. */
        val CORE = setOf("g5", "g18", "g23", "g26", "g27")
        /** Assemble only when the content suits them: 12 needs wide photos, 6 and 20 need music (20 a cover). */
        val CONTENT_FIT = setOf("g6", "g12", "g20")
        val FIRST_TIER = CORE + CONTENT_FIT
    }
}
