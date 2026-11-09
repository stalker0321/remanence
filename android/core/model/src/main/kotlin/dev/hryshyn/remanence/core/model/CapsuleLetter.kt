package dev.hryshyn.remanence.core.model

/**
 * The long note ("letter") a postcard can carry beside its sheet: content
 * manifest field 8, v2 only, read under the sheet rather than drawn on it.
 */
object CapsuleLetter {
    /** Inclusive UTF-8 byte bound, shared with the web client. */
    const val MAX_UTF8_BYTES = 8000

    fun valid(letter: String): Boolean =
        letter.isNotBlank() && letter.toByteArray(Charsets.UTF_8).size <= MAX_UTF8_BYTES
}
