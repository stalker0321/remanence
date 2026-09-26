package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Oracle fixtures for [FieldAggregator], the pure `import-field-3.3`
 * aggregation slice (`runtime/tools/legacy_features.py:74-99`,
 * `gaussian_field` lines 17-19, `center` line 73).
 *
 * Oracle provenance: numpy/cv2 are not installed on this host and downloads
 * are disallowed, so the frozen module is not executed. A line-for-line
 * stdlib-only Python transcription of lines 17-19, 73-74 and 76-99 (float64
 * `math.exp`, element-wise `max` folds, the face-context box of lines 82-83,
 * tip filtering/ordering/criticality of lines 90-92, the
 * `importance`/`baseline`/`objectField` formulas of lines 93 and 97, and the
 * half-even 4-decimal pack of line 95 via exact-binary `Decimal` rint)
 * produced the fingerprints pinned below from constant 48x48 maps and fixed
 * detector boxes. Freeze archive SHA-256
 * `43a8d54ee3b1e681d0d15d375d0787043d4b23bb770d7902915b34891a285a16`;
 * oracle `runtime/tools/legacy_features.py` SHA-256
 * `ef7f5bfde86398beef358a9db195b6fd48b2b3a0b91a0a2d820357a662c84219`.
 *
 * Scenarios: (1) empty detectors, pure base plus center prior; (2) one small
 * face with `person_focus`, two tip candidates kept non-critical; (3) one
 * object plus two faces with an out-of-range tip filtered out of candidacy;
 * (4) a two-tip `box[1]` tie proving stable insertion order with exactly two
 * criticals and no faces/objects. A repeat call pins determinism.
 *
 * Declared tolerances: packed cells/min/max 1e-9, field sums 1e-6 (Python
 * `fsum` vs JVM accumulation order), region boxes 1e-9, confidences 1e-12,
 * region kinds/order/critical flags and counts exact. Faces, objects, and
 * region boxes are never rounded. The pixel/OpenCV stage, detector runners,
 * EXIF decode, and production wiring are explicitly out of scope.
 */
class FieldAggregatorTest {

    private val grid = CollageAnalysis.GRID_SIZE

    private fun field(value: Double) = DoubleArray(grid * grid) { value }

    private fun primitives(
        width: Int,
        height: Int,
        faces: List<AnalysisFace> = emptyList(),
        objects: List<AnalysisObject> = emptyList(),
        saliency: Double = 0.0,
        edge: Double = 0.0,
        contrast: Double = 0.0,
        tips: Double = 0.0,
        tipBoxes: List<NormBox> = emptyList(),
    ) = ImportFieldPrimitives(
        sourceWidth = width,
        sourceHeight = height,
        faces = faces,
        objects = objects,
        saliency = field(saliency),
        edgeEnergy = field(edge),
        contrast = field(contrast),
        structureField = field(tips),
        tipBoxes = tipBoxes,
    )

    private fun box(x: Double, y: Double, w: Double, h: Double) = NormBox(x, y, w, h)

    private fun probs(values: DoubleArray): List<Double> =
        listOf(values[0], values[47], values[47 * 48], values[47 * 48 + 47], values[24 * 48 + 24])

    private fun assertField(
        values: DoubleArray,
        min: Double,
        max: Double,
        sum: Double,
        probes: List<Double>,
    ) {
        assertEquals(grid * grid, values.size)
        assertEquals(min, values.minOrNull()!!, 1e-9)
        assertEquals(max, values.maxOrNull()!!, 1e-9)
        assertEquals(sum, values.sum(), 1e-6)
        val actual = probs(values)
        assertEquals(probes.size, actual.size)
        for (i in probes.indices) assertEquals(probes[i], actual[i], 1e-9)
    }

    private fun assertRegion(
        region: AnalysisProtectedRegion,
        kind: String,
        x: Double, y: Double, w: Double, h: Double,
        confidence: Double,
        critical: Boolean,
    ) {
        assertEquals(kind, region.kind)
        assertEquals(x, region.box.x, 1e-9)
        assertEquals(y, region.box.y, 1e-9)
        assertEquals(w, region.box.width, 1e-9)
        assertEquals(h, region.box.height, 1e-9)
        assertEquals(confidence, region.confidence, 1e-12)
        assertEquals(critical, region.critical)
    }

    @Test
    fun emptyDetectorsYieldPureBasePlusCenter() {
        val analysis = FieldAggregator.aggregate(
            primitives(1000, 1000, saliency = 0.5, edge = 0.25, contrast = 0.75, tips = 0.0),
        )
        assertEquals(1000, analysis.width)
        assertEquals(1000, analysis.height)
        assertEquals(48, analysis.gridSize)
        assertTrue(analysis.faces.isEmpty())
        assertTrue(analysis.objects.isEmpty())
        assertTrue(analysis.protectedRegions.isEmpty())
        // No faces/objects: importance == baseline == base; object field is zero.
        assertField(
            analysis.importance, 0.3392, 0.3625, 804.246,
            listOf(0.3392, 0.3392, 0.3392, 0.3392, 0.3625),
        )
        assertField(
            analysis.baselineField, 0.3392, 0.3625, 804.246,
            listOf(0.3392, 0.3392, 0.3392, 0.3392, 0.3625),
        )
        assertField(
            analysis.objectField, 0.0, 0.0, 0.0,
            listOf(0.0, 0.0, 0.0, 0.0, 0.0),
        )
        assertField(
            analysis.saliency, 0.5, 0.5, 1152.0,
            listOf(0.5, 0.5, 0.5, 0.5, 0.5),
        )
        assertField(
            analysis.edgeEnergy, 0.25, 0.25, 576.0,
            listOf(0.25, 0.25, 0.25, 0.25, 0.25),
        )
        assertField(
            analysis.structureField, 0.0, 0.0, 0.0,
            listOf(0.0, 0.0, 0.0, 0.0, 0.0),
        )
    }

    @Test
    fun smallFaceKeepsTipsNonCriticalViaPersonFocus() {
        val faces = listOf(AnalysisFace(box(0.4, 0.3, 0.1, 0.1), 0.9))
        val analysis = FieldAggregator.aggregate(
            primitives(
                1000, 800, faces = faces,
                saliency = 0.1, edge = 0.9, contrast = 0.2, tips = 0.6,
                tipBoxes = listOf(box(0.2, 0.6, 0.03, 0.04), box(0.5, 0.3, 0.03, 0.04)),
            ),
        )
        // 0.1 * 0.1 = 0.01 > 0.005, so person_focus holds and tips stay soft.
        assertField(
            analysis.importance, 0.3717, 1.5397, 997.5802,
            listOf(0.3718, 0.3717, 0.3718, 0.3717, 0.5415),
        )
        assertField(
            analysis.baselineField, 0.3717, 1.5397, 997.5802,
            listOf(0.3718, 0.3717, 0.3718, 0.3717, 0.5415),
        )
        assertField(
            analysis.objectField, 0.0, 0.0, 0.0,
            listOf(0.0, 0.0, 0.0, 0.0, 0.0),
        )
        assertField(
            analysis.structureField, 0.6, 0.6, 1382.4,
            listOf(0.6, 0.6, 0.6, 0.6, 0.6),
        )
        assertEquals(1, analysis.faces.size)
        assertEquals(0.9, analysis.faces[0].confidence, 1e-12)
        assertEquals(4, analysis.protectedRegions.size)
        assertRegion(analysis.protectedRegions[0], "structure-tip", 0.2, 0.6, 0.03, 0.04, 0.4, false)
        assertRegion(analysis.protectedRegions[1], "structure-tip", 0.5, 0.3, 0.03, 0.04, 0.4, false)
        assertRegion(analysis.protectedRegions[2], "face", 0.4, 0.3, 0.1, 0.1, 0.9, false)
        assertRegion(analysis.protectedRegions[3], "face-context", 0.33, 0.25, 0.24, 0.37, 0.3, false)
    }

    @Test
    fun objectPlusFacesFilterOutOfRangeTipAndInterleaveRegions() {
        val faces = listOf(
            AnalysisFace(box(0.1, 0.1, 0.3, 0.3), 0.7),
            AnalysisFace(box(0.7, 0.7, 0.05, 0.05), 0.6),
        )
        val objects = listOf(AnalysisObject(box(0.1, 0.1, 0.3, 0.3), 0.8, 0))
        val analysis = FieldAggregator.aggregate(
            primitives(
                800, 1000, faces = faces, objects = objects,
                saliency = 0.8, edge = 0.1, contrast = 0.4, tips = 0.3,
                tipBoxes = listOf(box(0.05, 0.2, 0.03, 0.04)),
            ),
        )
        assertField(
            analysis.importance, 0.4045, 1.8375, 1715.5814,
            listOf(0.7727, 0.4066, 0.4365, 0.4045, 0.7544),
        )
        assertField(
            analysis.baselineField, 0.4045, 1.3195, 1531.1244,
            listOf(0.7047, 0.4066, 0.4365, 0.4045, 0.7073),
        )
        assertField(
            analysis.objectField, 0.0, 0.7969, 283.7704,
            listOf(0.1047, 0.0, 0.0, 0.0, 0.0724),
        )
        assertField(
            analysis.edgeEnergy, 0.1, 0.1, 230.4,
            listOf(0.1, 0.1, 0.1, 0.1, 0.1),
        )
        // x = 0.05 is outside (0.08, 0.90): not a candidate, never critical.
        assertEquals(6, analysis.protectedRegions.size)
        assertRegion(analysis.protectedRegions[0], "structure-tip", 0.05, 0.2, 0.03, 0.04, 0.4, false)
        assertRegion(analysis.protectedRegions[1], "object", 0.1, 0.1, 0.3, 0.3, 0.8, false)
        assertRegion(analysis.protectedRegions[2], "face", 0.1, 0.1, 0.3, 0.3, 0.7, false)
        assertRegion(analysis.protectedRegions[3], "face-context", 0.0, 0.0, 0.61, 1.0, 0.3, false)
        assertRegion(analysis.protectedRegions[4], "face", 0.7, 0.7, 0.05, 0.05, 0.6, false)
        assertRegion(analysis.protectedRegions[5], "face-context", 0.665, 0.675, 0.12, 0.185, 0.3, false)
        assertEquals(2, analysis.faces.size)
        assertEquals(1, analysis.objects.size)
        assertEquals(0, analysis.objects[0].classId)
    }

    @Test
    fun tiedTipsKeepStableOrderAndBothGoCriticalWithoutFaces() {
        val analysis = FieldAggregator.aggregate(
            primitives(
                1000, 1000, saliency = 0.0, edge = 0.0, contrast = 0.0, tips = 1.0,
                tipBoxes = listOf(box(0.3, 0.4, 0.03, 0.04), box(0.6, 0.4, 0.03, 0.04)),
            ),
        )
        assertField(
            analysis.importance, 0.1367, 0.16, 337.686,
            listOf(0.1367, 0.1367, 0.1367, 0.1367, 0.16),
        )
        assertField(
            analysis.baselineField, 0.1367, 0.16, 337.686,
            listOf(0.1367, 0.1367, 0.1367, 0.1367, 0.16),
        )
        assertField(
            analysis.structureField, 1.0, 1.0, 2304.0,
            listOf(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        // Equal box[1]: stable sort keeps contour insertion order; with no
        // faces person_focus is false, so exactly the first two go critical.
        assertEquals(2, analysis.protectedRegions.size)
        assertRegion(analysis.protectedRegions[0], "structure-tip", 0.3, 0.4, 0.03, 0.04, 0.4, true)
        assertRegion(analysis.protectedRegions[1], "structure-tip", 0.6, 0.4, 0.03, 0.04, 0.4, true)
        assertFalse(analysis.protectedRegions.any { it.kind != "structure-tip" })
    }

    @Test
    fun repeatCallsAreDeterministic() {
        val input = primitives(
            800, 1000,
            faces = listOf(AnalysisFace(box(0.1, 0.1, 0.3, 0.3), 0.7)),
            objects = listOf(AnalysisObject(box(0.1, 0.1, 0.3, 0.3), 0.8, 0)),
            saliency = 0.8, edge = 0.1, contrast = 0.4, tips = 0.3,
            tipBoxes = listOf(box(0.05, 0.2, 0.03, 0.04)),
        )
        val first = FieldAggregator.aggregate(input)
        val second = FieldAggregator.aggregate(input)
        for ((name, a, b) in listOf(
            Triple("importance", first.importance, second.importance),
            Triple("baselineField", first.baselineField, second.baselineField),
            Triple("objectField", first.objectField, second.objectField),
            Triple("saliency", first.saliency, second.saliency),
            Triple("edgeEnergy", first.edgeEnergy, second.edgeEnergy),
            Triple("structureField", first.structureField, second.structureField),
        )) {
            assertEquals("$name size", a.size, b.size)
            for (i in a.indices) assertEquals("$name[$i]", a[i], b[i], 0.0)
        }
        assertEquals(first.protectedRegions, second.protectedRegions)
    }
}
