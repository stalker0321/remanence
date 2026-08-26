package dev.hryshyn.remanence.core.model

@JvmInline
value class NormalizedHandle private constructor(val value: String) {
    fun toDisplayString(): String = "@$value"

    companion object {
        private val allowed = Regex("^[a-z0-9_.]{3,30}$")

        fun parse(raw: String): NormalizedHandle {
            val withoutPrefix = if (raw.startsWith('@')) raw.substring(1) else raw
            val normalized = asciiFold(withoutPrefix)
            if (!allowed.matches(normalized)) {
                throw IllegalArgumentException("invalid handle")
            }
            return NormalizedHandle(normalized)
        }

        private fun asciiFold(raw: String): String {
            val chars = raw.toCharArray()
            var changed = false
            for (index in chars.indices) {
                val char = chars[index]
                if (char in 'A'..'Z') {
                    chars[index] = char + ('a' - 'A')
                    changed = true
                }
            }
            return if (changed) String(chars) else raw
        }
    }
}
