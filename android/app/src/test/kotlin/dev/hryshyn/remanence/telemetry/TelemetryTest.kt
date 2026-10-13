package dev.hryshyn.remanence.telemetry

import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.ui.collage.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class MemoryConsent(var value: TelemetryConsent = TelemetryConsent()) : TelemetryConsentStorage {
    var saves = 0
    var canSave = true
    override fun load() = value
    override fun save(value: TelemetryConsent): Boolean { saves++; if (canSave) this.value = value; return canSave }
}

internal class RecordingTelemetryTransport : TelemetryTransport {
    val reports = mutableListOf<JsonObject>()
    var cancellations = 0
    override fun send(bytes: ByteArray): TelemetryPendingSend {
        reports += Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        bytes.fill(0)
        return TelemetryPendingSend { cancellations++ }
    }
}

class TelemetryTest {
    private val choices = listOf(TelemetryChoice("g5", "press"), TelemetryChoice("g5", "blue-letter"))
    private val original = TelemetryBox(0, 0, 10000, 10000)
    private val final = TelemetryBox(1000, 2000, 8000, 7000)
    private val config = TelemetryConfig(3, "short", true, false)
    private fun crop(slot: Int = 0, box: TelemetryBox = original) =
        TelemetryCropInput(slot, box, "square", listOf(TelemetryBox(4, 4, 3, 3)), List(64) { it % 16 })
    private data class Fixture(val storage: MemoryConsent, val transport: RecordingTelemetryTransport,
        val owner: TelemetryCoordinator, val session: TelemetrySession)
    private fun fixture(design: Boolean = true, crops: Boolean = true): Fixture {
        val storage = MemoryConsent(TelemetryConsent(design, crops))
        val transport = RecordingTelemetryTransport()
        val owner = TelemetryCoordinator(storage, transport)
        return Fixture(storage, transport, owner, owner.newSession())
    }
    private fun Fixture.shown(index: Int = 0, build: Long = 1, starts: List<List<TelemetryBox?>> = List(2) { listOf(original) }) =
        session.shown(owner.admission(), build, index, choices, config, starts)
    private fun Fixture.select(index: Int = 0, build: Long = 1, crops: List<TelemetryCropInput> = listOf(crop())) =
        session.selected(owner.admission(), build, index, crops)

    @Test fun defaultsOffCollectNothingAndIntentionalChoicesPersistIndependently() {
        val f = fixture(false, false)
        f.shown(); f.select(); assertTrue(f.transport.reports.isEmpty()); assertEquals(0, f.storage.saves)
        f.owner.setDesign(true); assertEquals(TelemetryConsent(true, false), f.owner.consent.value)
        f.select(); assertTrue("No retrospective pre-consent exposure", f.transport.reports.isEmpty())
        f.shown(); f.select(); assertTrue(f.transport.reports.single().containsKey("design"))
        assertFalse(f.transport.reports.single().containsKey("crops"))
        f.owner.setCrops(true); assertEquals(TelemetryConsent(true, true), f.storage.value)
        f.owner.setDesign(false); assertEquals(TelemetryConsent(false, true), f.storage.value)
        assertEquals(TelemetryConsent(false, true), TelemetryCoordinator(f.storage, f.transport).consent.value)
    }

    @Test fun unreadablePreferencesAndFailedEnablingFailClosed() {
        val owner = TelemetryCoordinator(object : TelemetryConsentStorage {
            override fun load(): TelemetryConsent = error("bad disk")
            override fun save(value: TelemetryConsent): Boolean = false
        }, RecordingTelemetryTransport())
        assertEquals(TelemetryConsent(), owner.consent.value)
        owner.setDesign(true); owner.setCrops(true)
        assertEquals(TelemetryConsent(), owner.consent.value)
        assertTrue(owner.saveFailed.value)
    }

    @Test fun withdrawalCancelsActiveAndDropsAllCohorts() {
        val f = fixture(); val second = f.owner.newSession()
        f.shown(); f.select()
        second.shown(f.owner.admission(), 2, 0, choices, config, List(2) { listOf(original) })
        f.owner.setCrops(false)
        assertEquals(1, f.transport.cancellations)
        f.select(); second.selected(f.owner.admission(), 2, 0, listOf(crop()))
        assertEquals(1, f.transport.reports.size)
        f.shown(); f.select(); assertFalse(f.transport.reports.last().containsKey("crops"))
    }

    @Test fun disableEnableRejectsOldObservationAndOldSelectionEvenWhenBothOnAgain() {
        val f = fixture(); val stale = f.owner.admission()
        f.shown(); f.owner.setDesign(false); f.owner.setDesign(true)
        f.session.shown(stale, 1, 0, choices, config, List(2) { listOf(original) })
        f.select(); assertTrue(f.transport.reports.isEmpty())
        f.shown(); f.session.selected(stale, 1, 0, listOf(crop()))
        assertTrue(f.transport.reports.isEmpty())
        f.select(); assertEquals(1, f.transport.reports.size)
    }

    @Test fun sessionBoundaryCancelsAndRetainsPrefsButRejectsOldAdmission() {
        val f = fixture(); val stale = f.owner.admission()
        f.shown(); f.select(); f.owner.clearSessionData()
        assertEquals(TelemetryConsent(true, true), f.storage.value)
        assertEquals(1, f.transport.cancellations)
        f.session.shown(stale, 1, 0, choices, config, List(2) { listOf(original) })
        f.select(); assertEquals(1, f.transport.reports.size)
    }

    @Test fun failedWithdrawalPersistenceStillStopsInMemory() {
        val f = fixture(); f.shown(); f.select(); f.storage.canSave = false
        f.owner.setDesign(false); f.owner.setCrops(false); f.shown(); f.select()
        assertEquals(TelemetryConsent(), f.owner.consent.value)
        assertEquals(1, f.transport.reports.size)
        assertTrue(f.owner.saveFailed.value)
        f.storage.canSave = true; f.owner.setDesign(false) // retry a failed save even if effective in-memory value is unchanged
        assertFalse(f.owner.saveFailed.value); assertEquals(TelemetryConsent(), f.storage.value)
    }

    @Test fun actualABAReturnsDifferFromImmediateAAndConsecutiveRedrawDedupes() {
        val browse = fixture(); browse.shown(); browse.shown(); browse.shown(1); browse.shown(1); browse.shown(); browse.select()
        val immediate = fixture(); immediate.shown(); immediate.select()
        assertEquals(listOf(0, 1, 0), browse.transport.reports.single()["design"]!!.jsonObject["shown"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(0), immediate.transport.reports.single()["design"]!!.jsonObject["shown"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test fun repeatSelectionIsDedupedButActualReturnCanSelectAgain() {
        val f = fixture(false, true); f.shown(); f.select(); f.select(); f.shown(); f.select()
        assertEquals(1, f.transport.reports.size)
        f.shown(1); f.shown(); f.select(); assertEquals(2, f.transport.reports.size)
    }

    @Test fun differentPresentedGeometriesWithSamePublicPairStillHaveDifferentIndices() {
        val f = fixture(true, false)
        val repeated = listOf(choices.first(), choices.first())
        for (index in listOf(0, 1, 0)) f.session.shown(f.owner.admission(), 1, index, repeated, config, emptyList())
        f.select()
        val design = f.transport.reports.single()["design"]!!.jsonObject
        assertEquals(2, design["available"]!!.jsonArray.size)
        assertEquals(listOf(0, 1, 0), design["shown"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test fun hiddenOrUnseenSelectionCannotProduceAReport() {
        val f = fixture(); f.select(); assertTrue(f.transport.reports.isEmpty())
        f.shown(); f.select(1); assertTrue(f.transport.reports.isEmpty())
        f.shown(1); f.select(1); assertEquals(1, f.transport.reports.size)
    }

    @Test fun cohortResetErasesEarlierSequenceAndCloseErasesReportEligibility() {
        val f = fixture(); f.shown(); f.shown(1); f.shown(build = 2); f.select(build = 2)
        assertEquals(listOf(0), f.transport.reports.single()["design"]!!.jsonObject["shown"]!!.jsonArray.map { it.jsonPrimitive.int })
        f.session.close(); f.select(build = 2); assertEquals(1, f.transport.reports.size)
    }

    @Test fun boundedHistoryKeepsSelectedIndexAndMarksTruncation() {
        val f = fixture(true, false)
        repeat(260) { f.shown(it % 2) }; f.select(1)
        val design = f.transport.reports.single()["design"]!!.jsonObject
        assertEquals(256, design["shown"]!!.jsonArray.size)
        assertTrue(design["sequence_truncated"]!!.jsonPrimitive.boolean)
        assertEquals(1, design["selected"]!!.jsonPrimitive.int)
    }

    @Test fun initialCropRemainsImmutableWhenCurrentCandidateAndSourceListAreChangedAfterDone() {
        val f = fixture(false, true)
        val mutable = mutableListOf<TelemetryBox?>(original)
        f.shown(starts = listOf(mutable, listOf(original)))
        mutable[0] = final
        f.shown(starts = List(2) { listOf(null) }) // post-Done candidate migrated, do not overwrite original
        f.select(crops = listOf(crop(box = final)))
        val record = f.transport.reports.single()["crops"]!!.jsonArray.single().jsonObject
        assertEquals(original.array(), record["initial"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(final.array(), record["final"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertTrue(record["edited"]!!.jsonPrimitive.boolean)
        assertFalse(f.transport.reports.single().containsKey("design"))
    }

    @Test fun unchangedOrCancelledDraftExportsActualOriginalAndNoEditedFlag() {
        val f = fixture(false, true); f.shown(); f.select()
        val crop = f.transport.reports.single()["crops"]!!.jsonArray.single().jsonObject
        assertEquals(crop["initial"], crop["final"]); assertFalse(crop["edited"]!!.jsonPrimitive.boolean)
    }

    @Test fun enablingCropsAfterPreConsentEditOmitsUnbackfillableSlot() {
        val f = fixture(true, false); f.shown(); f.owner.setCrops(true)
        f.shown(starts = List(2) { listOf(null) }); f.select(crops = listOf(crop(box = final)))
        assertTrue(f.transport.reports.single().containsKey("design")); assertFalse(f.transport.reports.single().containsKey("crops"))
    }

    @Test fun invalidSlotsMapsAndCropGeometryAreOmittedInsteadOfFabricated() {
        val f = fixture(false, true); f.shown()
        val bad = listOf(crop(slot = 12), crop(box = TelemetryBox(9000, 0, 5000, 10000)),
            crop().copy(focus = List(63) { 1 }), crop().copy(focus = List(64) { 16 }),
            crop().copy(faces = listOf(TelemetryBox(16, 0, 1, 1))), crop().copy(aspect = "secret"))
        f.select(crops = bad); assertTrue(f.transport.reports.isEmpty())
        f.select(crops = bad + crop()); assertEquals(1, f.transport.reports.single()["crops"]!!.jsonArray.size)
    }

    @Test fun catalogIsClosedGrammarStylePairsNotFreeText() {
        assertEquals(TelemetryChoice("g5", "press"), TelemetryChoice.normalized("5", "press"))
        assertEquals(TelemetryChoice("g5", "press"), TelemetryChoice.normalized("remanence-editorial-rows", "press"))
        assertEquals(TelemetryChoice("g5", "press"), TelemetryChoice.normalized("ber1-editorial-rows", "press"))
        assertNull(TelemetryChoice.normalized("g6", "press")); assertNull(TelemetryChoice.normalized("5", "some_user_handle"))
        val f = fixture()
        f.session.shown(f.owner.admission(), 1, 0, listOf(TelemetryChoice("g5", "private")), config, listOf(listOf(original)))
        f.select(); assertTrue(f.transport.reports.isEmpty())
    }

    @Test fun cropBoundsCannotBeBypassedWithIntegerOverflow() {
        assertFalse(TelemetryBox(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1).valid(10000))
        assertFalse(TelemetryBox(0, Int.MAX_VALUE, 1, Int.MAX_VALUE).valid(10000))
        assertFalse(TelemetryBox(10000, 0, 1, 1).valid(10000))
        assertTrue(TelemetryBox(9999, 9999, 1, 1).valid(10000))
    }

    @Test fun wireEnvelopeContainsOnlyExplicitAnonymousKeysAndNoSourceModelMetadata() {
        val f = fixture(); f.shown(); f.select()
        val payload = f.transport.reports.single()
        assertEquals(setOf("schema_version", "crop_policy", "analysis_version", "design", "crops"), payload.keys)
        assertEquals(setOf("photo_count", "text_bucket", "music", "cover", "available", "shown", "selected", "sequence_truncated"), payload["design"]!!.jsonObject.keys)
        assertEquals(setOf("grammar", "style", "slot", "aspect_bucket", "faces", "focus_grid", "initial", "final", "edited"), payload["crops"]!!.jsonArray.single().jsonObject.keys)
        assertTrue(payload.toString().encodeToByteArray().size < 32768)
    }

    @Test fun actualSerializedReportEqualsExactClientServerFixture() {
        val f = fixture(); f.shown(); f.shown(1); f.shown(); f.select(crops = listOf(crop(box = final)))
        val expected = requireNotNull(javaClass.getResource("/telemetry/v1-report.json")).readText()
        assertEquals(Json.parseToJsonElement(expected), f.transport.reports.single())
    }

    @Test fun transportFailureNeverEscapesOrTriggersRetry() {
        var calls = 0; var cleared: ByteArray? = null
        val owner = TelemetryCoordinator(MemoryConsent(TelemetryConsent(true, false)), TelemetryTransport { bytes ->
            calls++; cleared = bytes; error("offline")
        })
        val session = owner.newSession()
        session.shown(owner.admission(), 1, 0, choices, config, List(2) { listOf(original) })
        session.selected(owner.admission(), 1, 0, emptyList()); session.selected(owner.admission(), 1, 0, emptyList())
        assertEquals(1, calls); assertTrue(cleared!!.all { it == 0.toByte() })
    }

    @Test fun configUsesOnlyCodepointBucketAndBooleanMusicCover() {
        fun input(note: String?, music: GeneratorExpression.MusicRef? = null) = GeneratorExpression.GeneratorInput(
            "PRIVATE_OWNER", 9123L, listOf(GeneratorExpression.PhotoRef("PRIVATE_PHOTO", 0, 1000, 1000, "a".repeat(64))), note, music)
        assertEquals("none", TelemetryConfig.from(input(null)).textBucket)
        assertEquals("none", TelemetryConfig.from(input("")).textBucket)
        assertEquals("short", TelemetryConfig.from(input("😀".repeat(280))).textBucket)
        assertEquals("long", TelemetryConfig.from(input("😀".repeat(281))).textBucket)
        assertEquals(TelemetryConfig(1, "short", true, true), TelemetryConfig.from(input("PRIVATE_NOTE", GeneratorExpression.MusicRef("PRIVATE_TRACK", "PRIVATE_ART"))))
    }

    private fun analysis(width: Int = 800, height: Int = 600, importance: DoubleArray = DoubleArray(48 * 48) { 1.0 },
        faces: List<AnalysisFace> = emptyList(), grid: Int = 48): CollageAnalysis = CollageAnalysis(width, height, faces,
        listOf(AnalysisObject(NormBox(.1, .1, .2, .2), .9, 9944)), emptyList(), importance,
        DoubleArray(grid * grid) { 999.0 }, DoubleArray(grid * grid) { 999.0 }, DoubleArray(grid * grid) { 999.0 },
        DoubleArray(grid * grid) { 999.0 }, DoubleArray(grid * grid) { 999.0 }, grid)

    @Test fun focusGridPoolsImportanceOnlyAndNormalizesTo64CoarseBins() {
        val field = DoubleArray(48 * 48) { cell -> if (cell % 48 < 6 && cell / 48 < 6) 2.0 else 1.0 }
        val compact = CompactTelemetryAnalysis.from(analysis(importance = field), 800, 600)!!
        assertEquals(64, compact.focus.size); assertEquals(15, compact.focus[0]); assertTrue(compact.focus.drop(1).all { it == 8 })
        assertEquals("landscape", compact.aspect)
        field.fill(9999.0); assertEquals(15, compact.focus[0]) // immutable detached bins
    }

    @Test fun zeroFieldIsAuthoritativeButAbsentMismatchInvalidAndTooSmallAreNot() {
        assertEquals(List(64) { 0 }, CompactTelemetryAnalysis.from(analysis(importance = DoubleArray(48 * 48)), 800, 600)!!.focus)
        assertNull(CompactTelemetryAnalysis.from(null, 800, 600))
        assertNull(CompactTelemetryAnalysis.from(analysis(), 600, 800))
        assertNull(CompactTelemetryAnalysis.from(analysis(importance = DoubleArray(48 * 48) { Double.NaN }), 800, 600))
        assertNull(CompactTelemetryAnalysis.from(analysis(importance = DoubleArray(48 * 48) { -1.0 }), 800, 600))
        assertNull(CompactTelemetryAnalysis.from(analysis(importance = DoubleArray(16), grid = 4), 800, 600))
    }

    @Test fun facesAreClippedQuantizedCappedAndInvalidDroppedWithoutIdentityConfidenceOrLandmarks() {
        val valid = AnalysisFace(NormBox(-.1, .2, .4, .3), .999)
        val invalid = listOf(AnalysisFace(NormBox(Double.NaN, 0.0, .1, .1), .9),
            AnalysisFace(NormBox(1.1, .2, .4, .3), .9), AnalysisFace(NormBox(.2, .2, 0.0, .3), .9))
        val compact = CompactTelemetryAnalysis.from(analysis(faces = invalid + List(20) { valid }), 800, 600)!!
        assertEquals(16, compact.faces.size); assertEquals(TelemetryBox(0, 3, 5, 5), compact.faces.first())
    }

    @Test fun aspectBucketsAreCoarseAndDimensionValidated() {
        for ((size, expected) in listOf((600 to 800) to "portrait", (800 to 800) to "square", (1200 to 800) to "landscape", (2000 to 800) to "panorama")) {
            assertEquals(expected, CompactTelemetryAnalysis.from(analysis(size.first, size.second), size.first, size.second)!!.aspect)
        }
    }
}
