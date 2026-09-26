package dev.hryshyn.remanence.ui.collage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.round

/**
 * Deterministic V5 collage style registry.
 *
 * Values are copied verbatim from the capsule-engine V5 freeze
 * (`remanence_capsule_engine_v5_freeze_2026-09-25`, `runtime/styles.js`,
 * grammar group 5). Only `press`, `blue-letter`, and `poster` are bundled;
 * every other freeze style stays absent (fail-closed lookup).
 *
 * The canonical style record is the accepted STYD01 v1 order (authoritative
 * `V5 contract` section 7, "STYD01 STYLE-RECORD DIGEST — CANONICAL BYTE
 * ORDER"). It is built with GENEX-style canonical primitives (big-endian i32;
 * strings as i32 UTF-8 byte length + bytes):
 *
 * ```
 * magic        "STYD01" (6 bytes, US-ASCII; the 01 is the format version)
 * styleVersion str
 * styleId      str
 * grammar      i32
 * palette      six key/value string pairs, in paper, ink, sound, soundInk,
 *              surface, edge order; keys are the palette() names and values
 *              are the lower-cased "#rrggbb" strings
 * textRoles    brand, sender, note, music; each: font:str size:i32
 *              weight:i32 italic:byte(0/1)
 * tracking     i32 = round(tracking * 1000)   // -.065 -> -65
 * surface      str enum: flat | soft | edge | raised
 * ```
 *
 * `digest` is SHA-256 over exactly those bytes (32 raw bytes). Editorial keys
 * `name`, `idea`, and `baseline` are deliberately excluded so a copy edit
 * cannot invalidate a frozen candidate.
 */
object StyleRegistry {

    /** Canonical STYD01 v1 magic, exactly six US-ASCII bytes. */
    const val MAGIC = "STYD01"

    /** STYD01 `grammar` key: the bundled styles all live in grammar group 5. */
    const val GRAMMAR = 5

    /** Palette entry names, in canonical byte order. */
    val PALETTE_KEYS: List<String> = listOf("paper", "ink", "sound", "soundInk", "surface", "edge")

    /** One resolved text role (font, canvas-pixel size, weight, italic). */
    data class TextStyle(
        val font: String,
        val size: Int,
        val weight: Int,
        val italic: Boolean,
    )

    /**
     * The six palette values as lower-case `#rrggbb` strings. [surface]
     * defaults to [paper] and [edge] defaults to [ink] (`styles.js:5`).
     */
    data class Palette(
        val paper: String,
        val ink: String,
        val sound: String,
        val soundInk: String,
        val surface: String,
        val edge: String,
    ) {
        fun valuesInCanonicalOrder(): List<String> =
            listOf(paper, ink, sound, soundInk, surface, edge)
    }

    /** One bundled style, exactly the freeze `styles.js` record minus name/idea. */
    data class Style(
        val id: String,
        val version: String,
        val grammar: Int,
        val palette: Palette,
        val brand: TextStyle,
        val sender: TextStyle,
        val note: TextStyle,
        val music: TextStyle,
        val tracking: Double,
        /** Top-level surface enum string (`style.surface`), not the palette colour. */
        val surfaceMode: String,
    ) {
        val rolesInCanonicalOrder: List<TextStyle> get() = listOf(brand, sender, note, music)
    }

    private val stylesById: Map<String, Style> = listOf(
        Style(
            id = "press",
            version = "style-study-1",
            grammar = GRAMMAR,
            palette = Palette(
                paper = "#f2f3b0",
                ink = "#191b19",
                sound = "#191b19",
                soundInk = "#f2f3b0",
                surface = "#f2f3b0",
                edge = "#191b19",
            ),
            brand = TextStyle("StudySans", 59, 700, false),
            sender = TextStyle("StudyMono", 16, 400, false),
            note = TextStyle("StudySans", 22, 400, false),
            music = TextStyle("StudySans", 20, 400, false),
            tracking = -0.065,
            surfaceMode = "flat",
        ),
        Style(
            id = "blue-letter",
            version = "style-study-1",
            grammar = GRAMMAR,
            palette = Palette(
                paper = "#1525a0",
                ink = "#f9edd8",
                sound = "#f9edd8",
                soundInk = "#1525a0",
                surface = "#1525a0",
                edge = "#f9edd8",
            ),
            brand = TextStyle("StudySerif", 67, 400, false),
            sender = TextStyle("StudySerif", 20, 400, true),
            note = TextStyle("StudySerif", 26, 400, false),
            music = TextStyle("StudySerif", 22, 400, true),
            tracking = -0.045,
            surfaceMode = "flat",
        ),
        Style(
            id = "poster",
            version = "style-study-1",
            grammar = GRAMMAR,
            palette = Palette(
                paper = "#f4c4c2",
                ink = "#521719",
                sound = "#b82025",
                soundInk = "#fff6de",
                surface = "#f4c4c2",
                edge = "#521719",
            ),
            brand = TextStyle("Narrow", 78, 700, false),
            sender = TextStyle("StudySans", 17, 700, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("Narrow", 24, 700, false),
            tracking = -0.01,
            surfaceMode = "flat",
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
        out.putString(style.version)
        out.putString(style.id)
        out.putInt(style.grammar)
        val paletteValues = style.palette.valuesInCanonicalOrder()
        for (index in PALETTE_KEYS.indices) {
            out.putString(PALETTE_KEYS[index])
            out.putString(paletteValues[index])
        }
        for (role in style.rolesInCanonicalOrder) {
            out.putString(role.font)
            out.putInt(role.size)
            out.putInt(role.weight)
            out.putByte(if (role.italic) 1 else 0)
        }
        out.putInt(round(style.tracking * 1000.0).toInt())
        out.putString(style.surfaceMode)
        return out.toByteArray()
    }

    private class CanonicalWriter {
        private val parts = mutableListOf<ByteArray>()

        fun putAscii(value: String) {
            parts += value.toByteArray(Charsets.US_ASCII)
        }

        fun putByte(value: Int) {
            parts += byteArrayOf(value.toByte())
        }

        fun putInt(value: Int) {
            parts += ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
        }

        fun putString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            putInt(bytes.size)
            parts += bytes
        }

        fun toByteArray(): ByteArray {
            val total = parts.sumOf { it.size }
            val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            for (part in parts) buffer.put(part)
            return buffer.array()
        }
    }
}
