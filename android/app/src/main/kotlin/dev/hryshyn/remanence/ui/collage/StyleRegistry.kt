package dev.hryshyn.remanence.ui.collage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Deterministic V5 collage style registry.
 *
 * Values are copied verbatim from the capsule-engine V5 freeze
 * (`remanence_capsule_engine_v5_freeze_2026-09-25`, `runtime/styles.js`,
 * grammar group 5). Only `press`, `blue-letter`, and `poster` are bundled;
 * every other freeze style stays absent (fail-closed lookup).
 *
 * The canonical style record is STYD01 v1 (the freeze itself has no hashing
 * step, so the record format is pinned here):
 *
 * ```
 * magic        "STYD01" (6 bytes, US-ASCII)
 * version      i32 big-endian = 1
 * styleId      str  (i32 big-endian UTF-8 byte length + bytes)
 * styleVersion str
 * colors       paper, ink, sound, soundInk; each one RGB triple (3 bytes)
 * ```
 *
 * [digest] is SHA-256 over exactly those bytes. The registry chooses only the
 * four colors; the sealed `GeneratorExpression.TextType` still chooses glyphs.
 */
object StyleRegistry {

    /** STYD01 record format version. */
    const val RECORD_VERSION = 1

    /** Canonical STYD01 magic, exactly six US-ASCII bytes. */
    const val MAGIC = "STYD01"

    /**
     * One bundled style. [paper], [ink], [sound], [soundInk] are packed
     * `0xAARRGGBB` ints (alpha always `0xFF`), copied from `styles.js`.
     */
    data class Style(
        val id: String,
        val version: String,
        val paper: Int,
        val ink: Int,
        val sound: Int,
        val soundInk: Int,
    )

    private val stylesById: Map<String, Style> = listOf(
        Style(
            id = "press",
            version = "style-study-1",
            paper = 0xFFF2F3B0.toInt(),
            ink = 0xFF191B19.toInt(),
            sound = 0xFF191B19.toInt(),
            soundInk = 0xFFF2F3B0.toInt(),
        ),
        Style(
            id = "blue-letter",
            version = "style-study-1",
            paper = 0xFF1525A0.toInt(),
            ink = 0xFFF9EDD8.toInt(),
            sound = 0xFFF9EDD8.toInt(),
            soundInk = 0xFF1525A0.toInt(),
        ),
        Style(
            id = "poster",
            version = "style-study-1",
            paper = 0xFFF4C4C2.toInt(),
            ink = 0xFF521719.toInt(),
            sound = 0xFFB82025.toInt(),
            soundInk = 0xFFFFF6DE.toInt(),
        ),
    ).associateBy { it.id }

    /** Bundled style ids, exactly `press`, `blue-letter`, `poster`. */
    val ids: Set<String> get() = stylesById.keys

    /** Fail-closed lookup: `null` when [id] is not one of the three bundled styles. */
    fun style(id: String): Style? = stylesById[id]

    /** Canonical STYD01 v1 bytes for [id], or `null` when it is not bundled. */
    fun canonicalBytes(id: String): ByteArray? = style(id)?.let { canonicalBytes(it) }

    /** SHA-256 of the STYD01 v1 bytes for [id], or `null` when it is not bundled. */
    fun digest(id: String): ByteArray? =
        canonicalBytes(id)?.let { MessageDigest.getInstance("SHA-256").digest(it) }

    /** Canonical STYD01 v1 bytes for a bundled [style]. */
    fun canonicalBytes(style: Style): ByteArray {
        val out = CanonicalWriter()
        out.putAscii(MAGIC)
        out.putInt(RECORD_VERSION)
        out.putString(style.id)
        out.putString(style.version)
        out.putRgb(style.paper)
        out.putRgb(style.ink)
        out.putRgb(style.sound)
        out.putRgb(style.soundInk)
        return out.toByteArray()
    }

    private class CanonicalWriter {
        private val parts = mutableListOf<ByteArray>()

        fun putAscii(value: String) {
            parts += value.toByteArray(Charsets.US_ASCII)
        }

        fun putInt(value: Int) {
            parts += ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        }

        fun putString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            putInt(bytes.size)
            parts += bytes
        }

        /** One RGB triple: red, green, blue bytes in order. */
        fun putRgb(color: Int) {
            parts += byteArrayOf(
                ((color shr 16) and 0xFF).toByte(),
                ((color shr 8) and 0xFF).toByte(),
                (color and 0xFF).toByte(),
            )
        }

        fun toByteArray(): ByteArray {
            val total = parts.sumOf { it.size }
            val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            for (part in parts) buffer.put(part)
            return buffer.array()
        }
    }
}
