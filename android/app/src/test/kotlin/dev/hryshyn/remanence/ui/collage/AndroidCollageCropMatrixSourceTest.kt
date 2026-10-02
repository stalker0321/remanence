package dev.hryshyn.remanence.ui.collage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T151 production mounting — the real Android feasibility/cost matrix.
 *
 * Builds synthetic [CollageAnalysis] the same way `Envelope50CropSolverTest`
 * does (constant fields + injected faces/objects) and checks the frozen
 * `assignPhotos` cell semantics over the live solver: baseline feasibility,
 * finite frozen cost, the face/role gates and the dataset-wide object gate.
 */
class AndroidCollageCropMatrixSourceTest {

    private val grid = CollageAnalysis.GRID_SIZE
    private val source = AndroidCollageCropMatrixSource()

    private fun field(value: Double) = DoubleArray(grid * grid) { value }

    private fun box(x: Double, y: Double, w: Double, h: Double) = NormBox(x, y, w, h)

    private fun analysis(
        width: Int = 1000,
        height: Int = 1000,
        faces: List<AnalysisFace> = emptyList(),
        objects: List<AnalysisObject> = emptyList(),
    ) = CollageAnalysis(
        width = width,
        height = height,
        faces = faces,
        objects = objects,
        protectedRegions = emptyList(),
        importance = field(0.7),
        baselineField = field(0.5),
        objectField = field(0.0),
        saliency = field(0.6),
        edgeEnergy = field(0.6),
        structureField = field(0.5),
    )

    @Test
    fun feasibleCellCarriesTheFrozenCostAndRespectsTheFaceGate() {
        val face = AnalysisFace(box(0.4, 0.4, 0.2, 0.2), 0.9)
        val photos = listOf(CropMatrixPhoto("c0", 1000, 1000, analysis(faces = listOf(face))))
        val plain = CropMatrixRequest("c0", 340.0, 248.0)
        val hero = CropMatrixRequest("c0", 340.0, 248.0, assignmentRole = "human-anchor")

        val cells = runBlocking { source.matrix(photos, listOf(plain, hero)) }
        assertNotNull(cells)
        val plainCell = cells!!.getValue(plain)
        val heroCell = cells.getValue(hero)
        assertTrue("plain slot must be feasible", plainCell.feasible)
        assertNotNull("feasible cell must carry a cost", plainCell.cost)
        assertTrue("cost must be finite", plainCell.cost!!.isFinite())
        assertTrue("human-anchor with a face must be feasible", heroCell.feasible)

        val noFace = runBlocking {
            source.matrix(
                listOf(CropMatrixPhoto("c0", 1000, 1000, analysis())),
                listOf(CropMatrixRequest("c0", 340.0, 248.0, assignmentRole = "human-anchor")),
            )
        }
        assertFalse("human-anchor without a face must be infeasible", noFace!!.values.single().feasible)
    }

    @Test
    fun objectAnchorGateIsDatasetWide() {
        val objectPhoto = analysis(objects = listOf(AnalysisObject(box(0.3, 0.3, 0.3, 0.3), 0.7, 39)))
        val plain = analysis()
        val photos = listOf(
            CropMatrixPhoto("obj", 1000, 1000, objectPhoto),
            CropMatrixPhoto("plain", 1000, 1000, plain),
        )
        val reqObj = CropMatrixRequest("obj", 340.0, 248.0, assignmentRole = "object-anchor")
        val reqPlain = CropMatrixRequest("plain", 340.0, 248.0, assignmentRole = "object-anchor")
        val cells = runBlocking { source.matrix(photos, listOf(reqObj, reqPlain)) }!!
        assertTrue("object-bearing photo is the object anchor", cells.getValue(reqObj).feasible)
        assertFalse("non-object photo cannot take the object anchor", cells.getValue(reqPlain).feasible)
    }

    @Test
    fun impossibleSlotIsInfeasibleWithATypedFrozenReason() {
        val photos = listOf(CropMatrixPhoto("c0", 1000, 1000, analysis()))
        val tiny = CropMatrixRequest("c0", 10_000.0, 10_000.0)
        val cells = runBlocking { source.matrix(photos, listOf(tiny)) }!!
        val cell = cells.getValue(tiny)
        assertFalse(cell.feasible)
        assertNotNull("an infeasible cell must carry a typed frozen cause", cell.reason)
    }

    @Test
    fun missingAnalysisFailsClosed() {
        val photos = listOf(CropMatrixPhoto("c0", 1000, 1000, null))
        val result = runBlocking { source.matrix(photos, listOf(CropMatrixRequest("c0", 340.0, 248.0))) }
        assertNull("absent authoritative analysis must not be fabricated", result)
    }

    @Test
    fun mismatchedAnalysisDimensionsFailClosed() {
        // Analysis says 1000x1000 but the verified photo is 800x600: reading its
        // normalized boxes would be in the wrong space, so the matrix fails closed.
        val photos = listOf(CropMatrixPhoto("c0", 800, 600, analysis(1000, 1000)))
        val result = runBlocking { source.matrix(photos, listOf(CropMatrixRequest("c0", 340.0, 248.0))) }
        assertNull("dimension mismatch must fail closed", result)
    }
}
