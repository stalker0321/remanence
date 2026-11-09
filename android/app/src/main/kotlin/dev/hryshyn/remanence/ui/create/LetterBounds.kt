package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.CapsuleLetter

/** Typing past the letter bound keeps what fits instead of refusing the keystroke. */
internal object LetterBounds {
    fun clip(text: String): String {
        if (text.toByteArray(Charsets.UTF_8).size <= CapsuleLetter.MAX_UTF8_BYTES) return text
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > CapsuleLetter.MAX_UTF8_BYTES) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }
}
