package dev.hryshyn.remanence.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T154 full frozen V5: expression contract v4 (GENEX04 / BEXPR03).
 *
 * Covers the P1–P4 repairs (sealed `line`, restored `canonicalHash(input)`,
 * v4 validation outside the placement loop with a non-empty rule, v4 crop
 * 0..10000 + required crop), the nine music modes, provenance/decor/shape
 * validation, cross-version rejection, digest sensitivity, and legacy
 * byte preservation.
 */
class GeneratorExpressionV4Test {

    private fun photo(id: String, ordinal: Int) =
        GeneratorExpression.PhotoRef(id, ordinal, 1000, 1000, ('a' + ordinal).toString().repeat(64))

    private fun input(note: String? = "hello") = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = listOf(photo("c0", 0), photo("c1", 1), photo("c2", 2)),
        note = note,
        music = null,
    )

    private fun style() = GeneratorExpression.StyleBinding(
        styleId = "signal",
        styleVersion = "style-study-1",
        digest = ByteArray(32) { 7 },
    )

    private fun noteType() = GeneratorExpression.TextType(
        family = "StudySans",
        size = 16,
        weight = 400,
        italic = false,
        line = 18,
    )

    private fun musicType() = GeneratorExpression.TextType(
        family = "Narrow",
        size = 24,
        weight = 700,
        italic = false,
        line = 26,
    )

    private fun artwork() = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = "https://art.example.org/cover/ab12.webp",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/webp",
        width = 640,
        height = 640,
    )

    private fun brand() = GeneratorExpression.ProvenanceMark(
        kind = GeneratorExpression.ProvenanceKind.BRAND,
        text = GeneratorExpression.BRAND_TEXT,
        x = 12, y = 4, width = 340, height = 75,
        family = "StudySerif", size = 64, weight = 400, italic = false, line = 70,
        orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
        align = GeneratorExpression.MarkAlign.LEFT,
    )

    private fun sender(text: String = "From alice") = GeneratorExpression.ProvenanceMark(
        kind = GeneratorExpression.ProvenanceKind.SENDER,
        text = text,
        x = 16, y = 613, width = 328, height = 23,
        family = "StudySerif", size = 20, weight = 400, italic = true, line = 21,
        orientation = GeneratorExpression.MarkOrientation.HORIZONTAL,
        align = GeneratorExpression.MarkAlign.RIGHT,
    )

    private fun carrier() = GeneratorExpression.SceneDecor(
        kind = GeneratorExpression.DecorKind.CARRIER,
        x = 6, y = 5, width = 349, height = 627,
    )

    private fun placementV4(id: String, x: Int, shape: GeneratorExpression.PhotoShape = GeneratorExpression.PhotoShape.RECT) =
        GeneratorExpression.Placement(
            contentId = id,
            x = x, y = 0, width = 120, height = 213,
            crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000),
            maskId = null,
            contentRect = null,
            shape = shape,
        )

    private fun expressionV4() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "6",
        grammarVersion = 3,
        branchId = "hinge-2/1-0.52-equal-paired",
        input = input(),
        placements = listOf(
            placementV4("c0", 0),
            placementV4("c1", 120, GeneratorExpression.PhotoShape.ROUND),
            placementV4("c2", 240),
        ),
        noteTreatment = "regular-region",
        diagnostics = listOf("ok"),
        fontVersion = 2,
        paletteVersion = 4,
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V4,
        noteRegion = null,
        style = style(),
        noteType = noteType(),
        musicRegion = GeneratorExpression.MusicRegion(
            x = 10, y = 500, width = 340, height = 76,
            mode = "pulse",
            type = musicType(),
            artwork = artwork(),
        ),
        provenance = listOf(brand(), sender()),
        decor = listOf(carrier()),
    )

    private fun invalidReasons(expression: GeneratorExpression.ResolvedExpression): List<String> {
        val valid = GeneratorExpression.validateResolved(expression)
        assertIs<GeneratorExpression.InputValidation.Invalid>(valid, "expected Invalid")
        return valid.reasons
    }

    private fun magicOf(bytes: ByteArray): String {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return ByteArray(7).also { buf.get(it) }.toString(Charsets.US_ASCII)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun v4FixtureIsValid() {
        assertIs<GeneratorExpression.InputValidation.Valid>(
            GeneratorExpression.validateResolved(expressionV4()),
        )
    }

    @Test
    fun v4MagicPrecedesVersionMarker() {
        val bytes = GeneratorExpression.canonicalBytes(expressionV4())
        assertEquals("GENEX04", magicOf(bytes))
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(7)
        assertEquals(GeneratorExpression.CONTRACT_VERSION, buf.int)
        assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V4, buf.int)
    }

    @Test
    fun v4CanonicalBytesCarryNewSlotsInOrder() {
        val bytes = GeneratorExpression.canonicalBytes(expressionV4())
        val text = String(bytes, Charsets.ISO_8859_1)
        // Field order: style, then music (mode "pulse"), then provenance
        // ("brand" + "From alice"), then decor ("carrier"); per-placement
        // trailing shape ("round" for the g11-style slot).
        val styleAt = text.indexOf("signal")
        val pulseAt = text.indexOf("pulse")
        val brandAt = text.indexOf("brand")
        val senderAt = text.indexOf("From alice")
        val carrierAt = text.indexOf("carrier")
        val roundAt = text.indexOf("round")
        assertTrue(styleAt >= 0 && pulseAt > styleAt, "style before music")
        assertTrue(brandAt > pulseAt && senderAt > brandAt, "music before provenance")
        assertTrue(carrierAt > senderAt, "provenance before decor")
        // Shapes ride their placements, which precede the style trailer.
        assertTrue(roundAt in 0..styleAt, "shape rides its placement")
        assertTrue(text.indexOf("GENEX04") == 0, "GENEX04 frame")
    }

    @Test
    fun canonicalHashInputOverloadRestoredOnUnchangedV1Bytes() {
        val first = GeneratorExpression.canonicalBytes(input())
        val second = GeneratorExpression.canonicalBytes(input())
        assertTrue(first.contentEquals(second))
        val hash = GeneratorExpression.canonicalHash(input())
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(hash, GeneratorExpression.canonicalHash(input()))
        // The input hash is independent of any expression version: old cached
        // freezes verify against these same bytes.
        assertEquals(
            hash,
            GeneratorExpression.sha256Hex(GeneratorExpression.canonicalBytes(input())),
        )
    }

    @Test
    fun legacyMagicsUnchanged() {
        val v1 = expressionV4().copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V1,
            placements = listOf(photo("c0", 0), photo("c1", 1), photo("c2", 2)).map {
                GeneratorExpression.Placement(it.contentId, 0, 0, 120, 213, null, null)
            },
            style = null,
            noteType = null,
            musicRegion = null,
            provenance = null,
            decor = null,
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(v1))
        assertEquals("GENEX01", magicOf(GeneratorExpression.canonicalBytes(v1)))
    }

    @Test
    fun v1v2v3RejectV4OnlySlots() {
        val v4 = expressionV4()
        val withProvenanceV3 = v4.copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
            placements = v4.placements.map { it.copy(shape = GeneratorExpression.PhotoShape.RECT) },
        )
        assertTrue(
            invalidReasons(withProvenanceV3).any { it.contains("must not carry provenance") },
            "v3 must reject provenance",
        )
        val v1 = v4.copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V1,
            placements = v4.placements.map {
                it.copy(crop = null, shape = GeneratorExpression.PhotoShape.ROUND)
            },
            style = null, noteType = null, musicRegion = null,
            provenance = null, decor = null,
        )
        val reasons = invalidReasons(v1)
        assertTrue(reasons.any { it.contains("non-rect shape") }, "v1 must reject shape: $reasons")
    }

    @Test
    fun v4RequiresCropOnV3Bound() {
        val noCrop = expressionV4().copy(
            placements = expressionV4().placements.map { it.copy(crop = null) },
        )
        assertTrue(
            invalidReasons(noCrop).any { it.contains("requires crop") },
            "v4 must require crop",
        )
        val envelope = expressionV4().copy(
            placements = expressionV4().placements.map {
                it.copy(crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(envelope))
        val overThousand = expressionV4().copy(
            placements = expressionV4().placements.map {
                it.copy(crop = GeneratorExpression.CropWindow(0, 0, 5000, 5000))
            },
        )
        // 5000 fits the v4 0..10000 envelope (P4: v4 rides the v3 bound).
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(overThousand))
        val overTenThousand = expressionV4().copy(
            placements = expressionV4().placements.map {
                it.copy(crop = GeneratorExpression.CropWindow(0, 0, 10001, 100))
            },
        )
        assertTrue(
            invalidReasons(overTenThousand).any { it.contains("0..10000") },
            "v4 crop must fit 0..10000",
        )
    }

    @Test
    fun v4RequiresNonEmptyPhotosAndPlacements() {
        val empty = expressionV4().copy(
            input = input().copy(photos = emptyList()),
            placements = emptyList(),
        )
        assertTrue(
            invalidReasons(empty).any { it.contains("at least one photo") },
            "empty v4 must fail typed, not die at seal time",
        )
        assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(empty) }
    }

    @Test
    fun v4MusicNineModeMatrix() {
        val base = expressionV4()
        val musicless = base.copy(musicRegion = null)
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(musicless))
        val cases = listOf(
            "text" to false, "thumbnail" to true, "inline" to false, "inline" to true,
            "tile-art" to true, "tile-text" to false, "sleeve" to true,
            "sleeve-empty" to false, "spine" to false, "pulse" to false, "pulse" to true,
        )
        for ((mode, withArtwork) in cases) {
            val region = GeneratorExpression.MusicRegion(
                x = 10, y = 500, width = 340, height = 76,
                mode = mode, type = musicType(),
                artwork = if (withArtwork) artwork() else null,
            )
            val result = GeneratorExpression.validateResolved(base.copy(musicRegion = region))
            assertIs<GeneratorExpression.InputValidation.Valid>(result, "mode=$mode artwork=$withArtwork")
        }
        // Negative matrix: required-artwork modes without artwork, text-only
        // modes with artwork, and an unknown mode.
        for (mode in listOf("thumbnail", "tile-art", "sleeve")) {
            val region = GeneratorExpression.MusicRegion(
                x = 10, y = 500, width = 340, height = 76,
                mode = mode, type = musicType(), artwork = null,
            )
            assertTrue(
                invalidReasons(base.copy(musicRegion = region)).any { it.contains("requires artwork") },
                "mode=$mode must require artwork",
            )
        }
        for (mode in listOf("text", "tile-text", "sleeve-empty", "spine")) {
            val region = GeneratorExpression.MusicRegion(
                x = 10, y = 500, width = 340, height = 76,
                mode = mode, type = musicType(), artwork = artwork(),
            )
            assertTrue(
                invalidReasons(base.copy(musicRegion = region)).any { it.contains("must not carry artwork") },
                "mode=$mode must not carry artwork",
            )
        }
        val bogus = base.copy(
            musicRegion = GeneratorExpression.MusicRegion(
                x = 10, y = 500, width = 340, height = 76,
                mode = "sleeve-2", type = musicType(), artwork = null,
            ),
        )
        assertTrue(invalidReasons(bogus).any { it.contains("must be one of") })
    }

    @Test
    fun v4ProvenanceCorruptMatrix() {
        val base = expressionV4()
        fun withMarks(marks: List<GeneratorExpression.ProvenanceMark>?) =
            invalidReasons(base.copy(provenance = marks))
        assertTrue(withMarks(emptyList()).any { it.contains("must not be empty") })
        assertTrue(withMarks(listOf(brand(), sender(), brand())).any { it.contains("at most brand + sender") })
        assertTrue(withMarks(listOf(sender(), sender())).any { it.contains("must be unique") })
        assertTrue(withMarks(listOf(brand().copy(text = "Remanenc"), sender())).any { it.contains("brand text") })
        assertTrue(withMarks(listOf(brand(), sender("by someone"))).any { it.contains("sender text") })
        assertTrue(withMarks(listOf(brand(), sender("From "))).any { it.contains("sender text") })
        assertTrue(withMarks(listOf(brand().copy(family = "GothicX"), sender())).any { it.contains("family") })
        assertTrue(withMarks(listOf(brand().copy(size = 7, line = 8), sender())).any { it.contains("size must be 8..96") })
        assertTrue(withMarks(listOf(brand().copy(weight = 500), sender())).any { it.contains("weight") })
        assertTrue(
            withMarks(listOf(brand().copy(family = "StudySans", italic = true), sender())).any { it.contains("italic") },
        )
        assertTrue(withMarks(listOf(brand().copy(line = 71), sender())).any { it.contains("line must be") })
        assertTrue(withMarks(listOf(brand().copy(x = -1), sender())).any { it.contains("must fit 360x640") })
        assertTrue(withMarks(listOf(brand().copy(width = 0), sender())).any { it.contains("must fit 360x640") })
        assertTrue(withMarks(listOf(brand().copy(width = 361), sender())).any { it.contains("must fit 360x640") })
        assertTrue(
            withMarks(listOf(brand().copy(x = Int.MAX_VALUE - 1, width = 4), sender())).any {
                it.contains("must fit 360x640")
            },
        )
        assertTrue(
            withMarks(listOf(brand().copy(text = "Remanence".padEnd(129, 'x')), sender())).any {
                it.contains("128 chars")
            },
        )
    }

    @Test
    fun v4DecorCorruptMatrix() {
        val base = expressionV4()
        assertTrue(
            invalidReasons(base.copy(decor = emptyList())).any { it.contains("must not be empty") },
        )
        assertTrue(
            invalidReasons(base.copy(decor = listOf(carrier().copy(x = -1)))).any {
                it.contains("decor[0] must fit")
            },
        )
        assertTrue(
            invalidReasons(
                base.copy(decor = listOf(carrier().copy(x = Int.MAX_VALUE - 1, width = 4))),
            ).any { it.contains("decor[0] must fit") },
        )
        val nine = (1..9).map { carrier().copy(y = it) }
        assertTrue(invalidReasons(base.copy(decor = nine)).any { it.contains("at most") })
    }

    @Test
    fun v4RegionOverflowMatrix() {
        val base = expressionV4()
        val noteOverflow = base.copy(
            noteRegion = GeneratorExpression.NoteRegion(0, 0, 361, 10),
        )
        assertTrue(invalidReasons(noteOverflow).any { it.contains("noteRegion") })
        val musicOverflow = base.copy(
            musicRegion = base.musicRegion!!.copy(x = Int.MAX_VALUE - 1, width = 4),
        )
        assertTrue(invalidReasons(musicOverflow).any { it.contains("musicRegion") })
    }

    @Test
    fun v4DigestSensitivity() {
        val base = expressionV4()
        val baseHash = GeneratorExpression.canonicalHash(base)
        fun moved(mutated: GeneratorExpression.ResolvedExpression, label: String) {
            assertIs<GeneratorExpression.InputValidation.Valid>(
                GeneratorExpression.validateResolved(mutated), label,
            )
            assertNotEquals(baseHash, GeneratorExpression.canonicalHash(mutated), label)
        }
        moved(base.copy(provenance = listOf(brand(), sender("From alicf"))), "sender handle ±1 char")
        moved(
            base.copy(provenance = listOf(brand().copy(x = 13), sender())),
            "mark box ±1",
        )
        moved(
            base.copy(provenance = listOf(brand(), sender().copy(size = 21, line = 22))),
            "mark size ±1",
        )
        moved(
            base.copy(
                provenance = listOf(
                    sender().copy(kind = GeneratorExpression.ProvenanceKind.BRAND, text = "Remanence"),
                    brand().copy(kind = GeneratorExpression.ProvenanceKind.SENDER, text = "From alice"),
                ),
            ),
            "kind flip",
        )
        moved(
            base.copy(decor = listOf(carrier().copy(kind = GeneratorExpression.DecorKind.ACID_RIBBON))),
            "decor kind flip",
        )
        moved(base.copy(decor = listOf(carrier().copy(x = 7))), "decor box ±1")
        moved(
            base.copy(placements = base.placements.map { it.copy(shape = GeneratorExpression.PhotoShape.RECT) }),
            "shape RECT<->ROUND",
        )
        moved(
            base.copy(musicRegion = base.musicRegion!!.copy(mode = "text", artwork = null)),
            "music mode pulse<->text",
        )
        // Stability: independently constructed equal objects hash identically.
        assertEquals(baseHash, GeneratorExpression.canonicalHash(expressionV4()))
    }

    @Test
    fun v4Genex04HexGolden() {
        assertEquals(
                "47454e455830340000000100000004000000010000000136000000030000001b" +
                "68696e67652d322f312d302e35322d657175616c2d706169726564000000076f" +
                "776e65722d3100000000000000070000000300000002633000000000000003e8" +
                "000003e800000040616161616161616161616161616161616161616161616161" +
                "6161616161616161616161616161616161616161616161616161616161616161" +
                "616161616161616100000002633100000001000003e8000003e8000000406262" +
                "6262626262626262626262626262626262626262626262626262626262626262" +
                "6262626262626262626262626262626262626262626262626262626262620000" +
                "0002633200000002000003e8000003e800000040636363636363636363636363" +
                "6363636363636363636363636363636363636363636363636363636363636363" +
                "6363636363636363636363636363636363636363010000000568656c6c6f0000" +
                "000003000000026330000000000000000000000078000000d501000000640000" +
                "00c8000013880000177000000000000472656374000000026331000000780000" +
                "000000000078000000d50100000064000000c800001388000017700000000000" +
                "05726f756e64000000026332000000f00000000000000078000000d501000000" +
                "64000000c8000013880000177000000000000472656374000000067369676e61" +
                "6c0000000d7374796c652d73747564792d310707070707070707070707070707" +
                "07070707070707070707070707070707070700000009537475647953616e7300" +
                "000010000001900000000012010000000a000001f4000001540000004c000000" +
                "0570756c7365010000000e636174616c6f672d73616d706c6500000027687474" +
                "70733a2f2f6172742e6578616d706c652e6f72672f636f7665722f616231322e" +
                "77656270000102030405060708090a0b0c0d0e0f101112131415161718191a1b" +
                "1c1d1e1f0000000a696d6167652f776562700000028000000280000000064e61" +
                "72726f7700000018000002bc000000001a0100000002000000056272616e6400" +
                "00000952656d616e656e63650000000c00000004000001540000004b0000000a" +
                "53747564795365726966000000400000019000000000460000000a686f72697a" +
                "6f6e74616c000000046c6566740000000673656e6465720000000a46726f6d20" +
                "616c696365000000100000026500000148000000170000000a53747564795365" +
                "726966000000140000019001000000150000000a686f72697a6f6e74616c0000" +
                "0005726967687401000000010000000763617272696572000000060000000500" +
                "00015d000002730000000e726567756c61722d726567696f6e00000001000000" +
                "026f6b0000000200000004",
            hex(GeneratorExpression.canonicalBytes(expressionV4())),
        )
    }

    @Test
    fun v4Bexpr03HexGolden() {
        val blobMap = mapOf("c0" to ByteArray(16) { 1 }, "c1" to ByteArray(16) { 2 }, "c2" to ByteArray(16) { 3 })
        assertEquals(
                "4245585052303300000003000000010000000136000000030000001b68696e67" +
                "652d322f312d302e35322d657175616c2d7061697265640000000e726567756c" +
                "61722d726567696f6e0000000200000004000000000663616e642d3100000003" +
                "0000000000000002633000000040616161616161616161616161616161616161" +
                "6161616161616161616161616161616161616161616161616161616161616161" +
                "6161616161616161616161616161000003e8000003e801010101010101010101" +
                "0101010101010000000100000002633100000040626262626262626262626262" +
                "6262626262626262626262626262626262626262626262626262626262626262" +
                "6262626262626262626262626262626262626262000003e8000003e802020202" +
                "0202020202020202020202020000000200000002633200000040636363636363" +
                "6363636363636363636363636363636363636363636363636363636363636363" +
                "6363636363636363636363636363636363636363636363636363000003e80000" +
                "03e8030303030303030303030303030303030000000300000002633000000000" +
                "0000000000000078000000d5000100000064000000c800001388000017700000" +
                "00000472656374000000026331000000780000000000000078000000d5000100" +
                "000064000000c800001388000017700000000005726f756e6400000002633200" +
                "0000f00000000000000078000000d5000100000064000000c800001388000017" +
                "70000000000472656374000000067369676e616c0000000d7374796c652d7374" +
                "7564792d31070707070707070707070707070707070707070707070707070707" +
                "070707070700000009537475647953616e730000001000000190000000001201" +
                "0000000a000001f4000001540000004c0000000570756c7365010000000e6361" +
                "74616c6f672d73616d706c650000002768747470733a2f2f6172742e6578616d" +
                "706c652e6f72672f636f7665722f616231322e77656270000102030405060708" +
                "090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000a696d616765" +
                "2f776562700000028000000280000000064e6172726f7700000018000002bc00" +
                "0000001a0100000002000000056272616e640000000952656d616e656e636500" +
                "00000c00000004000001540000004b0000000a53747564795365726966000000" +
                "400000019000000000460000000a686f72697a6f6e74616c000000046c656674" +
                "0000000673656e6465720000000a46726f6d20616c6963650000001000000265" +
                "00000148000000170000000a5374756479536572696600000014000001900100" +
                "0000150000000a686f72697a6f6e74616c000000057269676874010000000100" +
                "0000076361727269657200000006000000050000015d00000273",
            hex(GeneratorExpressionProjection.canonicalBytes(expressionV4(), "cand-1", blobMap)),
        )
    }

    @Test
    fun senderMarkTextNeverFabricates() {
        assertEquals(null, GeneratorExpression.senderMarkText(null))
        assertEquals(null, GeneratorExpression.senderMarkText(""))
        assertEquals(null, GeneratorExpression.senderMarkText("   "))
        assertEquals("From alice", GeneratorExpression.senderMarkText("alice"))
        assertEquals(GeneratorExpression.BRAND_TEXT, "Remanence")
        assertEquals(74, GeneratorExpression.markLine(72))
        assertEquals(21, GeneratorExpression.markLine(20))
    }
}
