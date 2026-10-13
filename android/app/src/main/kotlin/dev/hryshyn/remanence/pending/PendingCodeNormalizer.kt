package dev.hryshyn.remanence.pending

/**
 * T186: public gift-locator code normalization.
 * Source: T185 `api-contract.md` §6 (frozen, STAGE 1 scaffold) + T184
 * contract + T183 rev2. The code is a PUBLIC 5-character
 * Crockford-compatible locator used only to pick a gift on the server. It is
 * never a secret, key, proof, or OTP.
 *
 * Frozen rule (§6): ASCII uppercase, strip spaces/hyphens, map `O→0` and
 * `I/L→1`, reject other Unicode/confusables/wrong length. Never fuzzy-match.
 * Errors stay generic: callers must never echo whether a code exists.
 */
object PendingCodeNormalizer {

    /** Crockford base-32 without I/L/O/U (case-insensitive input). */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 5

    private val alphabetSet = ALPHABET.toSet()

    /**
     * Normalizes raw user input to the canonical 5-char form, or null when
     * the input is not well-formed. Strips surrounding whitespace, inner
     * spaces and hyphens; uppercases; maps Crockford aliases `O→0`, `I/L→1`
     * per the frozen contract; rejects anything else outside [ALPHABET].
     */
    fun normalize(raw: String): String? {
        val stripped = raw.trim().replace(" ", "").replace("-", "").uppercase()
        if (stripped.length != CODE_LENGTH) return null
        val mapped = stripped.map { ch ->
            when (ch) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> ch
            }
        }.joinToString("")
        if (mapped.any { it !in alphabetSet }) return null
        return mapped
    }

    fun isWellFormed(raw: String): Boolean = normalize(raw) != null
}
