package dev.hryshyn.remanence.ui.collage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.round

/**
 * Deterministic V5 collage style registry.
 *
 * Values are copied verbatim from the capsule-engine V5 freeze
 * (`remanence_capsule_engine_v5_freeze_2026-09-25`, `runtime/styles.js` plus
 * `runtime/styles-extension.js`): all 14 active grammar groups (5, 6, 11,
 * 12, 17, 18, 20, 21, 22, 23, 24, 25, 26, 27) with 33 coordinated
 * treatments. Groups from `styles.js` carry version `style-study-1`;
 * groups from `styles-extension.js` carry version `pilot-styles-1`.
 * Grammars 16 and 19 are spliced out of the freeze and have no styles.
 * Every other freeze style stays absent (fail-closed lookup).
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

    /** STYD01 `grammar` key of the original slice-1 bundle (grammar group 5). */
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
            grammar = 5,
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
            grammar = 5,
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
            grammar = 5,
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
        Style(
            id = "signal",
            version = "style-study-1",
            grammar = 6,
            palette = Palette(
                paper = "#f34e33",
                ink = "#291d19",
                sound = "#291d19",
                soundInk = "#ffd794",
                surface = "#f34e33",
                edge = "#291d19",
            ),
            brand = TextStyle("Narrow", 22, 700, false),
            sender = TextStyle("StudySans", 18, 700, false),
            note = TextStyle("StudySans", 24, 400, false),
            music = TextStyle("Narrow", 24, 700, false),
            tracking = 0.0,
            surfaceMode = "flat",
        ),
        Style(
            id = "night-air",
            version = "style-study-1",
            grammar = 6,
            palette = Palette(
                paper = "#d6cce9",
                ink = "#342343",
                sound = "#342343",
                soundInk = "#f4e8c7",
                surface = "#d6cce9",
                edge = "#342343",
            ),
            brand = TextStyle("Gothic", 20, 400, false),
            sender = TextStyle("Gothic", 18, 400, false),
            note = TextStyle("Gothic", 23, 400, false),
            music = TextStyle("StudySerif", 23, 400, true),
            tracking = -0.025,
            surfaceMode = "flat",
        ),
        Style(
            id = "plum",
            version = "style-study-1",
            grammar = 11,
            palette = Palette(
                paper = "#5d2441",
                ink = "#fff1cf",
                sound = "#efe1b3",
                soundInk = "#54203b",
                surface = "#5d2441",
                edge = "#fff1cf",
            ),
            brand = TextStyle("StudySerif", 48, 400, false),
            sender = TextStyle("StudySerif", 18, 400, true),
            note = TextStyle("StudySerif", 25, 400, false),
            music = TextStyle("StudySerif", 22, 400, false),
            tracking = -0.045,
            surfaceMode = "soft",
        ),
        Style(
            id = "construction",
            version = "style-study-1",
            grammar = 11,
            palette = Palette(
                paper = "#f4ecd9",
                ink = "#292820",
                sound = "#b62421",
                soundInk = "#fff4d8",
                surface = "#f4ecd9",
                edge = "#292820",
            ),
            brand = TextStyle("Narrow", 53, 700, false),
            sender = TextStyle("StudyMono", 15, 400, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("Narrow", 24, 700, false),
            tracking = 0.0,
            surfaceMode = "edge",
        ),
        Style(
            id = "pool",
            version = "style-study-1",
            grammar = 11,
            palette = Palette(
                paper = "#b9d4d2",
                ink = "#17383b",
                sound = "#17383b",
                soundInk = "#d5ef73",
                surface = "#b9d4d2",
                edge = "#17383b",
            ),
            brand = TextStyle("Gothic", 45, 700, false),
            sender = TextStyle("Gothic", 17, 400, false),
            note = TextStyle("Gothic", 23, 400, false),
            music = TextStyle("Gothic", 19, 700, false),
            tracking = -0.065,
            surfaceMode = "soft",
        ),
        Style(
            id = "proof",
            version = "style-study-1",
            grammar = 18,
            palette = Palette(
                paper = "#333633",
                ink = "#202621",
                sound = "#e3e5df",
                soundInk = "#202621",
                surface = "#f8f7ef",
                edge = "#c4c8bf",
            ),
            brand = TextStyle("StudyMono", 17, 400, false),
            sender = TextStyle("StudyMono", 16, 400, false),
            note = TextStyle("StudySans", 22, 400, false),
            music = TextStyle("StudySans", 18, 400, false),
            tracking = 0.0,
            surfaceMode = "edge",
        ),
        Style(
            id = "ochre",
            version = "style-study-1",
            grammar = 18,
            palette = Palette(
                paper = "#bd6b35",
                ink = "#5a321e",
                sound = "#ead3a1",
                soundInk = "#5a321e",
                surface = "#fff0cd",
                edge = "#e7b879",
            ),
            brand = TextStyle("StudySerif", 21, 400, false),
            sender = TextStyle("StudySerif", 19, 400, true),
            note = TextStyle("StudySerif", 25, 400, false),
            music = TextStyle("StudySerif", 20, 400, true),
            tracking = 0.0,
            surfaceMode = "raised",
        ),
        Style(
            id = "nocturne",
            version = "style-study-1",
            grammar = 27,
            palette = Palette(
                paper = "#1f2424",
                ink = "#f5e17d",
                sound = "#f5e17d",
                soundInk = "#1f2424",
                surface = "#1f2424",
                edge = "#f5e17d",
            ),
            brand = TextStyle("Narrow", 25, 700, false),
            sender = TextStyle("StudySans", 18, 700, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("Narrow", 24, 700, false),
            tracking = 0.01,
            surfaceMode = "flat",
        ),
        Style(
            id = "porcelain",
            version = "style-study-1",
            grammar = 27,
            palette = Palette(
                paper = "#dce6ec",
                ink = "#173b76",
                sound = "#173b76",
                soundInk = "#f1f0df",
                surface = "#dce6ec",
                edge = "#173b76",
            ),
            brand = TextStyle("Gothic", 21, 400, false),
            sender = TextStyle("Gothic", 18, 400, false),
            note = TextStyle("StudySerif", 26, 400, false),
            music = TextStyle("StudySerif", 22, 400, true),
            tracking = -0.03,
            surfaceMode = "flat",
        ),
        Style(
            id = "telemetry",
            version = "pilot-styles-1",
            grammar = 12,
            palette = Palette(
                paper = "#102e35",
                ink = "#a4ece2",
                sound = "#a4ece2",
                soundInk = "#102e35",
                surface = "#102e35",
                edge = "#a4ece2",
            ),
            brand = TextStyle("StudyMono", 19, 400, false),
            sender = TextStyle("StudyMono", 16, 400, false),
            note = TextStyle("StudySans", 20, 400, false),
            music = TextStyle("StudyMono", 16, 400, false),
            tracking = 0.0,
            surfaceMode = "flat",
        ),
        Style(
            id = "folio",
            version = "pilot-styles-1",
            grammar = 12,
            palette = Palette(
                paper = "#f4eee4",
                ink = "#683521",
                sound = "#d0aa80",
                soundInk = "#432416",
                surface = "#f4eee4",
                edge = "#683521",
            ),
            brand = TextStyle("StudySerif", 23, 400, true),
            sender = TextStyle("StudySerif", 19, 400, false),
            note = TextStyle("StudySerif", 23, 400, false),
            music = TextStyle("StudySerif", 19, 400, true),
            tracking = -0.015,
            surfaceMode = "flat",
        ),
        Style(
            id = "espresso",
            version = "pilot-styles-1",
            grammar = 17,
            palette = Palette(
                paper = "#352521",
                ink = "#faf1dd",
                sound = "#cf9d72",
                soundInk = "#352521",
                surface = "#352521",
                edge = "#faf1dd",
            ),
            brand = TextStyle("StudySans", 55, 700, false),
            sender = TextStyle("StudyMono", 15, 400, false),
            note = TextStyle("StudySans", 22, 400, false),
            music = TextStyle("StudySans", 20, 400, false),
            tracking = -0.065,
            surfaceMode = "flat",
        ),
        Style(
            id = "aubergine",
            version = "pilot-styles-1",
            grammar = 17,
            palette = Palette(
                paper = "#302637",
                ink = "#f1d2dc",
                sound = "#f1d2dc",
                soundInk = "#302637",
                surface = "#302637",
                edge = "#f1d2dc",
            ),
            brand = TextStyle("StudySerif", 58, 700, false),
            sender = TextStyle("StudySerif", 18, 400, true),
            note = TextStyle("StudySerif", 24, 400, false),
            music = TextStyle("StudySerif", 22, 400, false),
            tracking = -0.055,
            surfaceMode = "flat",
        ),
        Style(
            id = "lacquer",
            version = "pilot-styles-1",
            grammar = 17,
            palette = Palette(
                paper = "#f5f2e9",
                ink = "#b32b24",
                sound = "#b32b24",
                soundInk = "#fff4e0",
                surface = "#f5f2e9",
                edge = "#b32b24",
            ),
            brand = TextStyle("Gothic", 54, 700, false),
            sender = TextStyle("StudySans", 16, 400, false),
            note = TextStyle("StudySans", 22, 400, false),
            music = TextStyle("Gothic", 20, 700, false),
            tracking = -0.065,
            surfaceMode = "flat",
        ),
        Style(
            id = "evergreen",
            version = "pilot-styles-1",
            grammar = 20,
            palette = Palette(
                paper = "#173e34",
                ink = "#f5d7bc",
                sound = "#f5d7bc",
                soundInk = "#173e34",
                surface = "#173e34",
                edge = "#f5d7bc",
            ),
            brand = TextStyle("StudySerif", 24, 400, false),
            sender = TextStyle("StudySans", 18, 700, false),
            note = TextStyle("StudySerif", 24, 400, false),
            music = TextStyle("StudySerif", 24, 400, true),
            tracking = 0.0,
            surfaceMode = "flat",
        ),
        Style(
            id = "studio",
            version = "pilot-styles-1",
            grammar = 20,
            palette = Palette(
                paper = "#f3edb8",
                ink = "#34265f",
                sound = "#34265f",
                soundInk = "#f3edb8",
                surface = "#f3edb8",
                edge = "#34265f",
            ),
            brand = TextStyle("StudyMono", 20, 400, false),
            sender = TextStyle("StudyMono", 16, 400, false),
            note = TextStyle("Gothic", 23, 400, false),
            music = TextStyle("Gothic", 23, 700, false),
            tracking = -0.02,
            surfaceMode = "flat",
        ),
        Style(
            id = "ink-letter",
            version = "pilot-styles-1",
            grammar = 21,
            palette = Palette(
                paper = "#273037",
                ink = "#f7eedb",
                sound = "#f7eedb",
                soundInk = "#273037",
                surface = "#273037",
                edge = "#f7eedb",
            ),
            brand = TextStyle("StudySerif", 18, 400, false),
            sender = TextStyle("StudySerif", 32, 400, true),
            note = TextStyle("StudySerif", 27, 400, false),
            music = TextStyle("StudySerif", 21, 400, true),
            tracking = -0.02,
            surfaceMode = "flat",
        ),
        Style(
            id = "correspondence",
            version = "pilot-styles-1",
            grammar = 21,
            palette = Palette(
                paper = "#eee4d9",
                ink = "#783f32",
                sound = "#783f32",
                soundInk = "#fff2df",
                surface = "#eee4d9",
                edge = "#783f32",
            ),
            brand = TextStyle("StudyMono", 16, 400, false),
            sender = TextStyle("Gothic", 29, 700, false),
            note = TextStyle("StudySans", 25, 400, false),
            music = TextStyle("StudySans", 20, 400, false),
            tracking = -0.025,
            surfaceMode = "flat",
        ),
        Style(
            id = "apricot",
            version = "pilot-styles-1",
            grammar = 22,
            palette = Palette(
                paper = "#eecba1",
                ink = "#542c39",
                sound = "#542c39",
                soundInk = "#f6dfbf",
                surface = "#eecba1",
                edge = "#542c39",
            ),
            brand = TextStyle("StudySerif", 22, 400, false),
            sender = TextStyle("StudySerif", 35, 700, false),
            note = TextStyle("StudySerif", 25, 400, false),
            music = TextStyle("StudySerif", 22, 400, true),
            tracking = -0.035,
            surfaceMode = "flat",
        ),
        Style(
            id = "mineral",
            version = "pilot-styles-1",
            grammar = 22,
            palette = Palette(
                paper = "#d2d9c7",
                ink = "#254b4d",
                sound = "#254b4d",
                soundInk = "#f1edca",
                surface = "#d2d9c7",
                edge = "#254b4d",
            ),
            brand = TextStyle("Gothic", 20, 400, false),
            sender = TextStyle("Gothic", 32, 700, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("Gothic", 21, 400, false),
            tracking = -0.035,
            surfaceMode = "flat",
        ),
        Style(
            id = "brass",
            version = "pilot-styles-1",
            grammar = 22,
            palette = Palette(
                paper = "#323b42",
                ink = "#edcc8d",
                sound = "#edcc8d",
                soundInk = "#323b42",
                surface = "#323b42",
                edge = "#edcc8d",
            ),
            brand = TextStyle("StudyMono", 18, 400, false),
            sender = TextStyle("Narrow", 38, 700, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("Narrow", 24, 700, false),
            tracking = 0.005,
            surfaceMode = "flat",
        ),
        Style(
            id = "glacier",
            version = "pilot-styles-1",
            grammar = 23,
            palette = Palette(
                paper = "#bfe7eb",
                ink = "#3d2a29",
                sound = "#3d2a29",
                soundInk = "#bfe7eb",
                surface = "#bfe7eb",
                edge = "#3d2a29",
            ),
            brand = TextStyle("Narrow", 25, 700, false),
            sender = TextStyle("StudySans", 17, 700, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("Narrow", 23, 700, false),
            tracking = 0.005,
            surfaceMode = "flat",
        ),
        Style(
            id = "ultraviolet",
            version = "pilot-styles-1",
            grammar = 23,
            palette = Palette(
                paper = "#f0eee8",
                ink = "#4a29a0",
                sound = "#4a29a0",
                soundInk = "#f5f0df",
                surface = "#f0eee8",
                edge = "#4a29a0",
            ),
            brand = TextStyle("Gothic", 23, 700, false),
            sender = TextStyle("Gothic", 18, 400, false),
            note = TextStyle("Gothic", 23, 400, false),
            music = TextStyle("Gothic", 20, 700, false),
            tracking = -0.035,
            surfaceMode = "flat",
        ),
        Style(
            id = "orchid",
            version = "pilot-styles-1",
            grammar = 24,
            palette = Palette(
                paper = "#18392f",
                ink = "#f5e8cf",
                sound = "#e6a6da",
                soundInk = "#29332c",
                surface = "#18392f",
                edge = "#f5e8cf",
            ),
            brand = TextStyle("Narrow", 36, 700, false),
            sender = TextStyle("StudySans", 18, 400, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("Narrow", 25, 700, false),
            tracking = 0.0,
            surfaceMode = "flat",
        ),
        Style(
            id = "ember",
            version = "pilot-styles-1",
            grammar = 24,
            palette = Palette(
                paper = "#39293d",
                ink = "#f6e0c5",
                sound = "#efae72",
                soundInk = "#39293d",
                surface = "#39293d",
                edge = "#f6e0c5",
            ),
            brand = TextStyle("Gothic", 32, 700, false),
            sender = TextStyle("StudyMono", 16, 400, false),
            note = TextStyle("StudySerif", 24, 400, false),
            music = TextStyle("Gothic", 22, 700, false),
            tracking = -0.045,
            surfaceMode = "flat",
        ),
        Style(
            id = "darkroom",
            version = "pilot-styles-1",
            grammar = 25,
            palette = Palette(
                paper = "#193c3b",
                ink = "#e6eadf",
                sound = "#e6eadf",
                soundInk = "#193c3b",
                surface = "#faf8ef",
                edge = "#d2d5c8",
            ),
            brand = TextStyle("StudyMono", 20, 400, false),
            sender = TextStyle("StudyMono", 16, 400, false),
            note = TextStyle("StudySans", 22, 400, false),
            music = TextStyle("StudySans", 19, 400, false),
            tracking = 0.0,
            surfaceMode = "print",
        ),
        Style(
            id = "rose-stock",
            version = "pilot-styles-1",
            grammar = 25,
            palette = Palette(
                paper = "#d6aab1",
                ink = "#502d44",
                sound = "#502d44",
                soundInk = "#f4e4d4",
                surface = "#f6e4da",
                edge = "#b9899a",
            ),
            brand = TextStyle("StudySerif", 23, 400, false),
            sender = TextStyle("StudySerif", 20, 400, true),
            note = TextStyle("StudySerif", 25, 400, false),
            music = TextStyle("StudySerif", 21, 400, true),
            tracking = -0.02,
            surfaceMode = "print",
        ),
        Style(
            id = "registration",
            version = "pilot-styles-1",
            grammar = 25,
            palette = Palette(
                paper = "#deded5",
                ink = "#202c27",
                sound = "#b4d775",
                soundInk = "#263522",
                surface = "#eff0e5",
                edge = "#202c27",
            ),
            brand = TextStyle("StudySans", 22, 700, false),
            sender = TextStyle("StudyMono", 15, 400, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("StudySans", 20, 400, false),
            tracking = -0.04,
            surfaceMode = "edge",
        ),
        Style(
            id = "saffron",
            version = "pilot-styles-1",
            grammar = 26,
            palette = Palette(
                paper = "#e6bb4b",
                ink = "#23384c",
                sound = "#23384c",
                soundInk = "#f6d77b",
                surface = "#e6bb4b",
                edge = "#23384c",
            ),
            brand = TextStyle("Narrow", 26, 700, false),
            sender = TextStyle("StudySans", 18, 700, false),
            note = TextStyle("StudySans", 23, 400, false),
            music = TextStyle("Narrow", 23, 700, false),
            tracking = 0.005,
            surfaceMode = "flat",
        ),
        Style(
            id = "blue-hour",
            version = "pilot-styles-1",
            grammar = 26,
            palette = Palette(
                paper = "#465b70",
                ink = "#edf0df",
                sound = "#cbdcde",
                soundInk = "#293f52",
                surface = "#465b70",
                edge = "#edf0df",
            ),
            brand = TextStyle("StudySerif", 25, 400, false),
            sender = TextStyle("StudySerif", 20, 400, true),
            note = TextStyle("StudySerif", 25, 400, false),
            music = TextStyle("StudySerif", 22, 400, true),
            tracking = -0.02,
            surfaceMode = "flat",
        ),
    ).associateBy { it.id }

    /** Bundled style ids: all 33 frozen treatments across the 14 active groups. */
    val ids: Set<String> get() = stylesById.keys

    /** Fail-closed lookup: `null` when [id] is not a bundled frozen style. */
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
