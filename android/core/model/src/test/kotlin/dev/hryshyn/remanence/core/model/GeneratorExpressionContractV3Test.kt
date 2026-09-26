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
 * V5-collage G1 schema tests: additive expression contract v3.
 *
 * v1 (`GENEX01`) and v2 (`GENEX02`) bytes, hashes, and goldens stay
 * byte-identical: every v3 field is a trailing default, and v1/v2 arms
 * reject v3-only fields fail-closed. `BEXPR01` is untouched; v3 projects
 * to `BEXPR02` (see the projection tests). Plain JVM, following
 * [GeneratorExpressionContractV2Test] conventions.
 */
class GeneratorExpressionContractV3Test {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val hashC = "c".repeat(64)

    private fun photo(id: String, ordinal: Int, w: Int, h: Int, hash: String) =
        GeneratorExpression.PhotoRef(id, ordinal, w, h, hash)

    private fun input(note: String? = "hello") = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = listOf(
            photo("p1", 0, 1080, 1920, hashA),
            photo("p2", 1, 1920, 1080, hashB),
            photo("p3", 2, 1080, 1080, hashC),
        ),
        note = note,
        music = null,
    )

    private fun style() = GeneratorExpression.StyleBinding(
        styleId = "press",
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

    private fun placementV3(id: String) = GeneratorExpression.Placement(
        contentId = id,
        x = 0,
        y = 0,
        width = 120,
        height = 213,
        crop = GeneratorExpression.CropWindow(100, 200, 5000, 6000),
        maskId = null,
        contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213),
    )

    private fun expressionV1() = GeneratorExpression.ResolvedExpression(
        canvasVersion = 1,
        grammarId = "g-hold",
        grammarVersion = 3,
        branchId = "b-rows",
        input = input(),
        placements = input().photos.map {
            GeneratorExpression.Placement(it.contentId, 0, 0, 120, 213, null, null)
        },
        noteTreatment = "regular-region",
        diagnostics = listOf("ok"),
        fontVersion = 2,
        paletteVersion = 4,
    )

    private fun expressionV3() = expressionV1().copy(
        expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V3,
        placements = input().photos.map { placementV3(it.contentId) },
        style = style(),
        noteType = noteType(),
        musicRegion = null,
    )

    private fun magicOf(bytes: ByteArray): String {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return ByteArray(7).also { buf.get(it) }.toString(Charsets.US_ASCII)
    }

    @Test
    fun v3MagicPrecedesVersionMarker() {
        val bytes = GeneratorExpression.canonicalBytes(expressionV3())
        assertEquals("GENEX03", magicOf(bytes))
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(7)
        assertEquals(GeneratorExpression.CONTRACT_VERSION, buf.int)
        assertEquals(GeneratorExpression.EXPRESSION_CONTRACT_V3, buf.int)
    }

    @Test
    fun v1AndV2MagicsUnchanged() {
        assertEquals("GENEX01", magicOf(GeneratorExpression.canonicalBytes(expressionV1())))
        val v2 = expressionV1().copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2,
            noteRegion = GeneratorExpression.NoteRegion(16, 512, 328, 128),
            placements = input().photos.map {
                GeneratorExpression.Placement(it.contentId, 0, 0, 120, 213, null, null)
                    .copy(contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(v2))
        assertEquals("GENEX02", magicOf(GeneratorExpression.canonicalBytes(v2)))
    }

    @Test
    fun v1AndV2RejectV3OnlyFields() {
        val v1Cases = listOf(
            expressionV1().copy(style = style()),
            expressionV1().copy(noteType = noteType()),
            expressionV1().copy(
                musicRegion = GeneratorExpression.MusicRegion(
                    16, 480, 328, 64, "text", noteType(),
                ),
            ),
        )
        val v2Base = expressionV1().copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2,
            noteRegion = GeneratorExpression.NoteRegion(16, 512, 328, 128),
            placements = input().photos.map {
                GeneratorExpression.Placement(it.contentId, 0, 0, 120, 213, null, null)
                    .copy(contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213))
            },
        )
        val v2Cases = listOf(
            v2Base.copy(style = style()),
            v2Base.copy(noteType = noteType()),
            v2Base.copy(
                musicRegion = GeneratorExpression.MusicRegion(
                    16, 480, 328, 64, "text", noteType(),
                ),
            ),
        )
        for (bad in v1Cases + v2Cases) {
            assertIs<GeneratorExpression.InputValidation.Invalid>(
                GeneratorExpression.validateResolved(bad),
                "expected Invalid for $bad",
            )
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(bad) }
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalHash(bad) }
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.freeze(bad, 0L, 7L) }
        }
    }

    @Test
    fun v1KeepsCropAtThousandBound() {
        // Crop/mask are v1 GENEX fields (not v3-only): a 0..1000 crop stays
        // legal on v1, pinning the distinction the rejection test relies on.
        val fine = expressionV1().copy(
            placements = expressionV1().placements.map {
                it.copy(crop = GeneratorExpression.CropWindow(100, 0, 900, 10))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(fine))
        val coarse = expressionV1().copy(
            placements = expressionV1().placements.map {
                it.copy(crop = GeneratorExpression.CropWindow(100, 0, 950, 10))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validateResolved(coarse))
    }

    @Test
    fun cropUnitDispatchesOnContract() {
        // 1001-scale crop: illegal on v1/v2, legal on v3.
        val v1coarse = expressionV1().copy(
            placements = expressionV1().placements.map {
                it.copy(crop = GeneratorExpression.CropWindow(0, 0, 1001, 10))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validateResolved(v1coarse))
        val v2coarse = expressionV1().copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2,
            noteRegion = GeneratorExpression.NoteRegion(16, 512, 328, 128),
            placements = input().photos.map {
                GeneratorExpression.Placement(
                    it.contentId, 0, 0, 120, 213,
                    GeneratorExpression.CropWindow(0, 0, 1001, 10), null,
                    GeneratorExpression.ContentRect(0, 0, 120, 213),
                )
            },
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validateResolved(v2coarse))
        val v3coarse = expressionV3().copy(
            placements = input().photos.map {
                placementV3(it.contentId).copy(crop = GeneratorExpression.CropWindow(0, 0, 1001, 10))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(v3coarse))
        // Past 10000 the v3 crop fails too (Long arithmetic, no wrap).
        val v3past = expressionV3().copy(
            placements = input().photos.map {
                placementV3(it.contentId).copy(crop = GeneratorExpression.CropWindow(0, 0, 10001, 10))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Invalid>(GeneratorExpression.validateResolved(v3past))
        assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(v3past) }
    }

    @Test
    fun v3RequiresStyleNoteTypeAndCrop() {
        val base = expressionV3()
        val noStyle = base.copy(style = null)
        val noNoteType = base.copy(noteType = null)
        val noCrop = base.copy(
            placements = base.placements.mapIndexed { i, p ->
                if (i == 0) p.copy(crop = null) else p
            },
        )
        for (bad in listOf(noStyle, noNoteType, noCrop)) {
            assertIs<GeneratorExpression.InputValidation.Invalid>(
                GeneratorExpression.validateResolved(bad),
                "expected Invalid for $bad",
            )
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(bad) }
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalHash(bad) }
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.freeze(bad, 0L, 7L) }
        }
    }

    @Test
    fun v3MusicTypeAndStyleBoundsFailClosed() {
        val base = expressionV3()
        val offCanvasMusic = base.copy(
            musicRegion = GeneratorExpression.MusicRegion(300, 600, 100, 100, "text", noteType()),
        )
        val badMode = base.copy(
            musicRegion = GeneratorExpression.MusicRegion(16, 480, 328, 64, "cover", noteType()),
        )
        val badType = base.copy(noteType = noteType().copy(family = "StudyMono", italic = true))
        val badWeight = base.copy(noteType = noteType().copy(weight = 500))
        val badSize = base.copy(noteType = noteType().copy(size = 7))
        val badLine = base.copy(noteType = noteType().copy(line = 121))
        val badFamily = base.copy(noteType = noteType().copy(family = "ComicSans"))
        val badDigest = base.copy(style = style().copy(digest = ByteArray(16)))
        val badStyleId = base.copy(style = style().copy(styleId = "Press!"))
        val badStyleVersion = base.copy(style = style().copy(styleVersion = ""))
        for (bad in listOf(
            offCanvasMusic, badMode, badType, badWeight, badSize, badLine,
            badFamily, badDigest, badStyleId, badStyleVersion,
        )) {
            assertIs<GeneratorExpression.InputValidation.Invalid>(
                GeneratorExpression.validateResolved(bad),
                "expected Invalid for $bad",
            )
            assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(bad) }
        }
    }

    @Test
    fun v3FieldChangesYieldDistinctIds() {
        val base = expressionV3()
        val baseHash = GeneratorExpression.canonicalHash(base)
        val styleOnly = base.copy(style = style().copy(styleId = "blue-letter"))
        val musicOnly = base.copy(
            musicRegion = GeneratorExpression.MusicRegion(16, 480, 328, 64, "text", noteType()),
        )
        val cropOne = base.copy(
            placements = base.placements.mapIndexed { i, p ->
                if (i == 0) p.copy(crop = GeneratorExpression.CropWindow(1, 200, 5000, 6000)) else p
            },
        )
        val cropTwo = base.copy(
            placements = base.placements.mapIndexed { i, p ->
                if (i == 0) p.copy(crop = GeneratorExpression.CropWindow(2, 200, 5000, 6000)) else p
            },
        )
        val hashes = listOf(baseHash) + listOf(styleOnly, musicOnly, cropOne, cropTwo).map {
            assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(it))
            GeneratorExpression.canonicalHash(it)
        }
        assertEquals(hashes.size, hashes.toSet().size, "style/music/crop-at-1-vs-2 must be distinct ids")
    }

    @Test
    fun candidateIdGoldenUnchangedForV1Fixture() {
        // GeneratorBer1Provider.candidateIdFor is untouched: the v1 fixture
        // keeps the id pinned from the hand-built GENEX01 golden layout.
        assertEquals(
            "ber1-856eb0da4027abbd29a7e149e7b5dc38",
            GeneratorBer1Provider.candidateIdFor(expressionV1()),
        )
    }

    @Test
    fun contractLegsOfBindingInvariant() {
        // Model legs only (artifact expression_version is codec-owned):
        // contract 3 <=> GENEX03 <=> BEXPR02; contract 1 <=> GENEX01 <=> BEXPR01.
        val v3 = expressionV3()
        assertEquals("GENEX03", magicOf(GeneratorExpression.canonicalBytes(v3)))
        val blobs = v3.input.photos.associate { it.contentId to ByteArray(16) { 1 } }
        val v3projection = GeneratorExpressionProjection.canonicalBytes(v3, "cand-1", blobs)
        assertEquals("BEXPR02", magicOf(v3projection))
        val v3buf = ByteBuffer.wrap(v3projection).order(ByteOrder.BIG_ENDIAN)
        v3buf.position(7)
        assertEquals(GeneratorExpressionProjection.PROJECTION_VERSION_2, v3buf.int)
        val v1 = expressionV1()
        assertEquals("GENEX01", magicOf(GeneratorExpression.canonicalBytes(v1)))
        val v1blobs = v1.input.photos.associate { it.contentId to ByteArray(16) { 1 } }
        val v1projection = GeneratorExpressionProjection.canonicalBytes(v1, "cand-1", v1blobs)
        assertEquals("BEXPR01", magicOf(v1projection))
        val v1buf = ByteBuffer.wrap(v1projection).order(ByteOrder.BIG_ENDIAN)
        v1buf.position(7)
        assertEquals(GeneratorExpressionProjection.PROJECTION_VERSION, v1buf.int)
    }

    @Test
    fun v3FreezesAndStaysDeterministic() {
        val first = expressionV3()
        val frozen = GeneratorExpression.freeze(first, 3L, 7L)
        assertEquals(GeneratorExpression.canonicalHash(first), frozen.expressionHash)
        assertEquals(64, frozen.expressionHash.length)
        assertTrue(
            GeneratorExpression.canonicalBytes(first).contentEquals(
                GeneratorExpression.canonicalBytes(expressionV3()),
            ),
        )
    }

    // ---- Artwork descriptor (V5 cover-art contract, client-only) ----

    private fun artwork() = GeneratorExpression.ArtworkRef(
        provider = "catalog-sample",
        url = "https://art.example.org/cover/ab12.webp",
        sha256 = ByteArray(32) { it.toByte() },
        mimeType = "image/webp",
        width = 640,
        height = 640,
    )

    private fun expressionV3Artwork() = expressionV3().copy(
        musicRegion = GeneratorExpression.MusicRegion(
            x = 16,
            y = 480,
            width = 328,
            height = 64,
            mode = "thumbnail",
            type = noteType(),
            artwork = artwork(),
        ),
    )

    private fun blobsOf(expression: GeneratorExpression.ResolvedExpression) =
        expression.input.photos.associate { it.contentId to ByteArray(16) { 1 } }

    private fun assertInvalid(expression: GeneratorExpression.ResolvedExpression) {
        assertIs<GeneratorExpression.InputValidation.Invalid>(
            GeneratorExpression.validateResolved(expression),
            "expected Invalid for $expression",
        )
        assertFailsWith<IllegalArgumentException> { GeneratorExpression.canonicalBytes(expression) }
    }

    @Test
    fun n4ArtworkBytesAreDeterministic() {
        val first = expressionV3Artwork()
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(first))
        assertTrue(
            GeneratorExpression.canonicalBytes(first).contentEquals(
                GeneratorExpression.canonicalBytes(expressionV3Artwork()),
            ),
        )
        val blobs = blobsOf(first)
        assertTrue(
            GeneratorExpressionProjection.canonicalBytes(first, "cand-1", blobs).contentEquals(
                GeneratorExpressionProjection.canonicalBytes(expressionV3Artwork(), "cand-1", blobs),
            ),
        )
    }

    @Test
    fun n5ThumbnailIffArtwork() {
        val base = expressionV3Artwork()
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(base))
        // Thumbnail without bytes cannot render.
        assertInvalid(base.copy(musicRegion = base.musicRegion!!.copy(artwork = null)))
        // Artwork under mode "text" is dead weight inside a sealed artifact.
        assertInvalid(
            base.copy(
                musicRegion = base.musicRegion!!.copy(mode = "text", artwork = artwork()),
            ),
        )
        // No-art still seals as mode "text" with artwork absent.
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(expressionV3()))
    }

    @Test
    fun n6UnstableUrlsRejected() {
        val base = expressionV3Artwork()
        fun withUrl(url: String) = base.copy(
            musicRegion = base.musicRegion!!.copy(artwork = artwork().copy(url = url)),
        )
        val longUrl = "https://art.example.org/" + "a".repeat(489)
        assertEquals(513, longUrl.length)
        for (bad in listOf(
            withUrl("http://art.example.org/cover/ab12.webp"),
            withUrl("https://art.example.org/cover/ab12.webp?sig=abc"),
            withUrl("https://art.example.org/cover/ab12.webp#frag"),
            withUrl("https://user@art.example.org/cover/ab12.webp"),
            withUrl("https://art.example.org:8080/cover/ab12.webp"),
            withUrl(longUrl),
            // No host allowlist exists at seal time (host gating is the
            // later fetcher slice, T2); a non-allowlisted provider key
            // fails here via A3 on an otherwise well-formed URL.
            base.copy(
                musicRegion = base.musicRegion!!.copy(
                    artwork = artwork().copy(provider = "no-such-provider"),
                ),
            ),
        )) {
            assertInvalid(bad)
        }
        // Port 443 explicitly is the one allowed explicit port.
        assertIs<GeneratorExpression.InputValidation.Valid>(
            GeneratorExpression.validateResolved(withUrl("https://art.example.org:443/cover/ab12.webp")),
        )
    }

    @Test
    fun n7DigestMimeAndDimensionsBounded() {
        val base = expressionV3Artwork()
        fun withArt(art: GeneratorExpression.ArtworkRef) = base.copy(
            musicRegion = base.musicRegion!!.copy(artwork = art),
        )
        for (bad in listOf(
            withArt(artwork().copy(sha256 = ByteArray(31))),
            withArt(artwork().copy(sha256 = ByteArray(33))),
            withArt(artwork().copy(mimeType = "image/gif")),
            withArt(artwork().copy(width = 0)),
            withArt(artwork().copy(width = 4097)),
            withArt(artwork().copy(height = 4097)),
        )) {
            assertInvalid(bad)
        }
    }

    @Test
    fun n8ProviderAllowlist() {
        val base = expressionV3Artwork()
        assertInvalid(
            base.copy(
                musicRegion = base.musicRegion!!.copy(artwork = artwork().copy(provider = "NoSuch")),
            ),
        )
        assertInvalid(
            base.copy(
                musicRegion = base.musicRegion!!.copy(artwork = artwork().copy(provider = "")),
            ),
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(base))
    }

    @Test
    fun n9InputArtworkIdMustBeNullForV3() {
        val withInputArt = expressionV3Artwork().copy(
            input = input().copy(music = GeneratorExpression.MusicRef("track-1", "art-x")),
        )
        assertInvalid(withInputArt)
        // v1/v2 keep the existing artworkId semantics untouched.
        val v2 = expressionV1().copy(
            expressionContractVersion = GeneratorExpression.EXPRESSION_CONTRACT_V2,
            input = input().copy(music = GeneratorExpression.MusicRef("track-1", "art-x")),
            noteRegion = GeneratorExpression.NoteRegion(16, 512, 328, 128),
            placements = input().photos.map {
                GeneratorExpression.Placement(it.contentId, 0, 0, 120, 213, null, null)
                    .copy(contentRect = GeneratorExpression.ContentRect(0, 0, 120, 213))
            },
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(v2))
    }

    @Test
    fun n10ArtworkChangesMoveBothIdentities() {
        val base = expressionV3Artwork()
        val baseCandidate = GeneratorBer1Provider.candidateIdFor(base)
        val baseProjected = GeneratorExpressionProjection.hash(base, "cand-1", blobsOf(base))
        val urlOnly = base.copy(
            musicRegion = base.musicRegion!!.copy(artwork = artwork().copy(url = "https://art.example.org/cover/cd34.webp")),
        )
        val shaOnly = base.copy(
            musicRegion = base.musicRegion!!.copy(artwork = artwork().copy(sha256 = ByteArray(32) { 9 })),
        )
        for (variant in listOf(urlOnly, shaOnly)) {
            assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(variant))
            assertNotEquals(baseCandidate, GeneratorBer1Provider.candidateIdFor(variant))
            assertNotEquals(
                baseProjected,
                GeneratorExpressionProjection.hash(variant, "cand-1", blobsOf(variant)),
            )
        }
        assertNotEquals(
            GeneratorBer1Provider.candidateIdFor(urlOnly),
            GeneratorBer1Provider.candidateIdFor(shaOnly),
        )
        // Identical descriptors reproduce both identities exactly.
        assertEquals(baseCandidate, GeneratorBer1Provider.candidateIdFor(expressionV3Artwork()))
        assertEquals(
            baseProjected,
            GeneratorExpressionProjection.hash(expressionV3Artwork(), "cand-1", blobsOf(base)),
        )
    }

    @Test
    fun n11LegacyContractsUnmovedByArtworkSlot() {
        // The artwork slot exists only on v3: v1/v2 fixtures stay valid,
        // keep their magics and projection, and the v1 candidate golden.
        val v1 = expressionV1()
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(v1))
        assertEquals("GENEX01", magicOf(GeneratorExpression.canonicalBytes(v1)))
        assertEquals(
            "ber1-856eb0da4027abbd29a7e149e7b5dc38",
            GeneratorBer1Provider.candidateIdFor(v1),
        )
        val v1projection = GeneratorExpressionProjection.canonicalBytes(v1, "cand-1", blobsOf(v1))
        assertEquals("BEXPR01", magicOf(v1projection))
        // No-art v3 still seals as mode "text" with the artwork byte absent.
        val noArt = expressionV3()
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(noArt))
        assertEquals("GENEX03", magicOf(GeneratorExpression.canonicalBytes(noArt)))
    }

    private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * N1: hard GENEX03 hex golden for the v3 artwork fixture. Field order
     * was walked byte-by-byte against contract section 5 in an independent
     * decoder (magic, versions, input body, placements with crops, style,
     * note type, music slot with nested artwork, treatment, diagnostics,
     * deps) and the walk ends exactly at the buffer end.
     */
    @Test
    fun n1Genex03ArtworkHexGolden() {
        val expected =
            "47454e4558303300000001000000030000000100000006672d686f6c640000000300000006622d726f7773000000076f776e65722d31000000000000000700000003000000027031000000000000043800000780000000406161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616100000002703200000001000007800000043800000040626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262620000000270330000000200000438000004380000004063636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363010000000568656c6c6f0000000003000000027031000000000000000000000078000000d50100000064000000c800001388000017700001000000000000000000000078000000d5000000027032000000000000000000000078000000d50100000064000000c800001388000017700001000000000000000000000078000000d5000000027033000000000000000000000078000000d50100000064000000c800001388000017700001000000000000000000000078000000d50000000570726573730000000d7374796c652d73747564792d31070707070707070707070707070707070707070707070707070707070707070700000009537475647953616e73000000100000019000000000120100000010000001e00000014800000040000000097468756d626e61696c010000000e636174616c6f672d73616d706c650000002768747470733a2f2f6172742e6578616d706c652e6f72672f636f7665722f616231322e77656270000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000a696d6167652f77656270000002800000028000000009537475647953616e73000000100000019000000000120000000e726567756c61722d726567696f6e00000001000000026f6b0000000200000004"
        assertEquals(1604, expected.length)
        assertEquals(expected, hexOf(GeneratorExpression.canonicalBytes(expressionV3Artwork())))
    }

    /**
     * N2: hard BEXPR02 hex golden for the same artwork fixture. Walked
     * against contract section 6 (BEXPR02 prefix through placements, crop /
     * mask trailers, style, note type, music trailer with nested artwork);
     * ends exactly at the buffer end. Closes flagged gap G1.
     */
    @Test
    fun n2Bexpr02ArtworkHexGolden() {
        val expected =
            "42455850523032000000020000000100000006672d686f6c640000000300000006622d726f77730000000e726567756c61722d726567696f6e0000000200000004000000000663616e642d310000000300000000000000027031000000406161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616100000438000007800101010101010101010101010101010100000001000000027032000000406262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626200000780000004380101010101010101010101010101010100000002000000027033000000406363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636300000438000004380101010101010101010101010101010100000003000000027031000000000000000000000078000000d501000000000000000000000078000000d50100000064000000c8000013880000177000000000027032000000000000000000000078000000d501000000000000000000000078000000d50100000064000000c8000013880000177000000000027033000000000000000000000078000000d501000000000000000000000078000000d50100000064000000c80000138800001770000000000570726573730000000d7374796c652d73747564792d31070707070707070707070707070707070707070707070707070707070707070700000009537475647953616e73000000100000019000000000120100000010000001e00000014800000040000000097468756d626e61696c010000000e636174616c6f672d73616d706c650000002768747470733a2f2f6172742e6578616d706c652e6f72672f636f7665722f616231322e77656270000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000a696d6167652f77656270000002800000028000000009537475647953616e7300000010000001900000000012"
        assertEquals(1634, expected.length)
        val expression = expressionV3Artwork()
        assertEquals(expected, hexOf(GeneratorExpressionProjection.canonicalBytes(expression, "cand-1", blobsOf(expression))))
    }

    /**
     * N3: hard BEXPR02 hex golden for the no-artwork v3 expression
     * (mode "text" music region, artwork absent). Pins the artwork
     * present:byte 0 encoding.
     */
    @Test
    fun n3Bexpr02NoArtHexGolden() {
        val expected =
            "42455850523032000000020000000100000006672d686f6c640000000300000006622d726f77730000000e726567756c61722d726567696f6e0000000200000004000000000663616e642d310000000300000000000000027031000000406161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161616100000438000007800101010101010101010101010101010100000001000000027032000000406262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626262626200000780000004380101010101010101010101010101010100000002000000027033000000406363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636363636300000438000004380101010101010101010101010101010100000003000000027031000000000000000000000078000000d501000000000000000000000078000000d50100000064000000c8000013880000177000000000027032000000000000000000000078000000d501000000000000000000000078000000d50100000064000000c8000013880000177000000000027033000000000000000000000078000000d501000000000000000000000078000000d50100000064000000c80000138800001770000000000570726573730000000d7374796c652d73747564792d31070707070707070707070707070707070707070707070707070707070707070700000009537475647953616e73000000100000019000000000120100000010000001e0000001480000004000000004746578740000000009537475647953616e7300000010000001900000000012"
        assertEquals(1394, expected.length)
        val expression = expressionV3().copy(
            musicRegion = GeneratorExpression.MusicRegion(
                x = 16,
                y = 480,
                width = 328,
                height = 64,
                mode = "text",
                type = noteType(),
                artwork = null,
            ),
        )
        assertIs<GeneratorExpression.InputValidation.Valid>(GeneratorExpression.validateResolved(expression))
        assertEquals(expected, hexOf(GeneratorExpressionProjection.canonicalBytes(expression, "cand-1", blobsOf(expression))))
    }
}
