package dev.hryshyn.remanence.ui.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference-vs-Kotlin differential test for the T151 stage-2 all-family pure
 * plan geometry.
 *
 * The expected geometry in `src/test/resources/collage/v5_plans_reference.txt`
 * is produced by `tasks/T151/evidence/ref_plans.js`, which loads the
 * authoritative freeze runtime unmodified and calls the frozen
 * `V4_ENGINE.plans(d, g, V4_ENGINE.noteState(d, g))` and
 * `V4_ENGINE.applicability(d, g)` for every active family. Note text uses the
 * T153 `textMetricModelled` line-height stub, so note box heights are the stub
 * model, not browser layout.
 */
class FrozenGrammarPlansTest {

    private val tol = 1e-5
    private val stub = FrozenGrammarPlans.LINE_HEIGHT_STUB

    // ---------------------------------------------------------------- parsing

    private data class CaseSpec(
        val id: String,
        val n: Int,
        val musicMode: String,
        val note: Boolean,
        val faces: Boolean,
        val aspects: List<Double>,
    )

    private data class AppSpec(val ok: Boolean, val reasons: List<String>)

    private data class Ref(
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
    )

    private data class RefTile(val photoId: String, val shape: String, val rect: Ref, val zooms: List<Double>)

    private data class RefNote(val state: String, val rect: Ref)

    private data class RefMusic(val mode: String, val rect: Ref)

    private data class RefMark(
        val role: String,
        val rect: Ref,
        val size: Double,
        val orientation: String,
        val align: String,
        val italic: Boolean,
        val weight: Int,
        val font: String,
    )

    private data class RefDecor(val kind: String, val rect: Ref)

    private data class PlanSpec(
        val branch: String,
        val tiles: List<RefTile>,
        val note: RefNote?,
        val music: RefMusic?,
        val prov: List<RefMark>,
        val decor: List<RefDecor>,
    )

    private fun double(value: String): Double = value.toDouble()

    private fun rect(value: String): Ref {
        val p = value.split(',')
        return Ref(double(p[0]), double(p[1]), double(p[2]), double(p[3]))
    }

    private fun parseTiles(value: String): List<RefTile> {
        if (value.isEmpty()) return emptyList()
        return value.split(';').map { entry ->
            val f = entry.split(':')
            RefTile(
                photoId = f[0],
                shape = f[1],
                rect = rect(f[2]),
                zooms = if (f.size > 3 && f[3].isNotEmpty()) f[3].split('|').map { double(it) } else listOf(1.0),
            )
        }
    }

    private fun parseProv(value: String): List<RefMark> {
        if (value.isEmpty()) return emptyList()
        return value.split(';').map { entry ->
            val f = entry.split(':')
            RefMark(
                role = f[0],
                rect = rect(f[1]),
                size = double(f[2]),
                orientation = f[3],
                align = f[4],
                italic = f[5] == "1",
                weight = f[6].toInt(),
                font = f[7],
            )
        }
    }

    private fun parseDecor(value: String): List<RefDecor> {
        if (value.isEmpty()) return emptyList()
        return value.split(';').map { entry ->
            val f = entry.split(':')
            RefDecor(f[0], rect(f[1]))
        }
    }

    private class Fixture(
        val caseOrder: List<String>,
        val cases: Map<String, CaseSpec>,
        val apps: Map<Pair<String, Int>, AppSpec>,
        val plans: Map<Pair<String, Int>, MutableList<PlanSpec>>,
    )

    private fun fixture(): Fixture {
        val text = requireNotNull(javaClass.getResource("/collage/v5_plans_reference.txt")) {
            "missing all-family plans reference fixture"
        }.readText()
        val caseOrder = ArrayList<String>()
        val cases = HashMap<String, CaseSpec>()
        val apps = HashMap<Pair<String, Int>, AppSpec>()
        val plans = HashMap<Pair<String, Int>, MutableList<PlanSpec>>()
        var current: String? = null
        for (raw in text.lineSequence()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val f = raw.trimEnd('\r').split('\t')
            when (f[0]) {
                "CASE" -> {
                    current = f[1]
                    caseOrder += f[1]
                    cases[f[1]] = CaseSpec(
                        f[1], f[2].toInt(), f[3], f[4] == "1", f[5] == "1",
                        f[6].split(',').map { it.toDouble() },
                    )
                }
                "APP" -> {
                    val id = requireNotNull(current)
                    apps[id to f[1].toInt()] = AppSpec(f[2] == "1", if (f[3] == "-") emptyList() else f[3].split(" | "))
                }
                "PLAN" -> {
                    val id = requireNotNull(current)
                    val note = if (f.getOrElse(6) { "-" } == "-") null else {
                        val parts = f[6].split(':')
                        RefNote(parts[0], rect(parts[1]))
                    }
                    val music = if (f.getOrElse(8) { "-" } == "-") null else {
                        val parts = f[8].split(':')
                        RefMusic(parts[0], rect(parts[1]))
                    }
                    plans.getOrPut(id to f[1].toInt()) { ArrayList() } += PlanSpec(
                        branch = f[2],
                        tiles = parseTiles(f.getOrElse(4) { "" }),
                        note = note,
                        music = music,
                        prov = parseProv(f.getOrElse(10) { "" }),
                        decor = parseDecor(f.getOrElse(12) { "" }),
                    )
                }
            }
        }
        return Fixture(caseOrder, cases, apps, plans)
    }

    // -------------------------------------------------------------- comparison

    private fun near(message: String, expected: Double, actual: Double) {
        assertTrue("$message expected=$expected actual=$actual", Math.abs(expected - actual) <= tol)
    }

    private fun nearRect(message: String, expected: Ref, actual: FrozenGrammarPlans.Rect) {
        near("$message.x", expected.x, actual.x)
        near("$message.y", expected.y, actual.y)
        near("$message.w", expected.w, actual.w)
        near("$message.h", expected.h, actual.h)
    }

    private fun input(spec: CaseSpec): FrozenGrammarPlans.Input {
        val photos = spec.aspects.mapIndexed { i, a ->
            FrozenGrammarPlans.Photo("p$i", a * 1000.0, 1000.0, spec.faces)
        }
        val music = when (spec.musicMode) {
            "text" -> FrozenGrammarPlans.MusicInput(artwork = false)
            "cover" -> FrozenGrammarPlans.MusicInput(artwork = true)
            else -> null
        }
        return FrozenGrammarPlans.Input(spec.id, "T151", photos, if (spec.note) "Probe note text." else null, music)
    }

    private fun comparePlan(key: Pair<String, Int>, index: Int, exp: PlanSpec, act: FrozenGrammarPlans.Plan) {
        val label = "$key #$index"
        assertEquals("branch ($label)", exp.branch, act.branch)

        assertEquals("tile count ($label)", exp.tiles.size, act.tiles.size)
        exp.tiles.forEachIndexed { j, tile ->
            val a = act.tiles[j]
            assertEquals("tile[$j].photoId ($label)", tile.photoId, a.photoId)
            assertEquals("tile[$j].shape ($label)", tile.shape, a.shape)
            nearRect("tile[$j] ($label)", tile.rect, a.rect)
            assertEquals("tile[$j].zooms.size ($label)", tile.zooms.size, a.zooms.size)
            tile.zooms.forEachIndexed { z, v -> near("tile[$j].zoom[$z] ($label)", v, a.zooms[z]) }
        }

        if (exp.note == null) {
            assertTrue("note must be null ($label)", act.note == null)
        } else {
            val a = requireNotNull(act.note) { "note missing ($label)" }
            assertEquals("note.state ($label)", exp.note.state, a.state)
            nearRect("note ($label)", exp.note.rect, a.rect)
        }

        if (exp.music == null) {
            assertTrue("music must be null ($label)", act.music == null)
        } else {
            val a = requireNotNull(act.music) { "music missing ($label)" }
            assertEquals("music.mode ($label)", exp.music.mode, a.mode)
            nearRect("music ($label)", exp.music.rect, a.rect)
        }

        assertEquals("prov count ($label)", exp.prov.size, act.provenance.size)
        exp.prov.forEachIndexed { j, mark ->
            val a = act.provenance[j]
            assertEquals("prov[$j].role ($label)", mark.role, a.role)
            nearRect("prov[$j] ($label)", mark.rect, a.rect)
            near("prov[$j].size ($label)", mark.size, a.size)
            assertEquals("prov[$j].orientation ($label)", mark.orientation, a.orientation)
            assertEquals("prov[$j].align ($label)", mark.align, a.align)
            assertEquals("prov[$j].italic ($label)", mark.italic, a.italic)
            assertEquals("prov[$j].weight ($label)", mark.weight, a.weight)
            assertEquals("prov[$j].font ($label)", mark.font, a.font)
        }

        assertEquals("decor count ($label)", exp.decor.size, act.decor.size)
        exp.decor.forEachIndexed { j, decor ->
            val a = act.decor[j]
            assertEquals("decor[$j].kind ($label)", decor.kind, a.kind)
            nearRect("decor[$j] ($label)", decor.rect, a.rect)
        }
    }

    // -------------------------------------------------------------------- tests

    @Test
    fun activeFamiliesMatchFrozenEnumerationOrder() {
        assertEquals(
            listOf(5, 6, 11, 12, 17, 18, 20, 21, 22, 23, 24, 25, 26, 27),
            FrozenGrammarPlans.ACTIVE_GRAMMAR_IDS,
        )
        assertEquals(FrozenGrammarPlans.ACTIVE_GRAMMAR_IDS, FrozenGrammarPlans.DEFS.map { it.id })
    }

    @Test
    fun allFamilyPlansMatchFrozenReference() {
        val fx = fixture()
        assertTrue("fixture must carry cases", fx.caseOrder.isNotEmpty())
        var comparedPlans = 0
        for (caseId in fx.caseOrder) {
            val spec = requireNotNull(fx.cases[caseId])
            val d = input(spec)
            for (gid in FrozenGrammarPlans.ACTIVE_GRAMMAR_IDS) {
                val key = caseId to gid
                val expectedApp = requireNotNull(fx.apps[key]) { "missing APP for $key" }
                val g = FrozenGrammarPlans.def(gid)
                val actualApp = FrozenGrammarPlans.applicability(d, g)
                assertEquals("applicable ($key)", expectedApp.ok, actualApp.ok)
                assertEquals("reasons ($key)", expectedApp.reasons, actualApp.reasons)
                if (!expectedApp.ok) continue
                val expectedPlans = fx.plans[key] ?: emptyList()
                val actualPlans = FrozenGrammarPlans.plans(
                    d, g, FrozenGrammarPlans.noteState(d, g, stub), stub,
                )
                assertEquals("plan count ($key)", expectedPlans.size, actualPlans.size)
                expectedPlans.forEachIndexed { i, expected -> comparePlan(key, i, expected, actualPlans[i]) }
                comparedPlans += expectedPlans.size
            }
        }
        assertTrue("differential must compare a substantial plan set, got $comparedPlans", comparedPlans > 500)
    }

    @Test
    fun applicabilityEmitsTypedFrozenReasons() {
        val g6 = FrozenGrammarPlans.def(6)
        val noMusic = FrozenGrammarPlans.Input(
            "x", "s", List(3) { FrozenGrammarPlans.Photo("p$it", 1000.0, 1000.0) }, null, null,
        )
        assertEquals(
            listOf("Music required: it is a structural part of this grammar"),
            FrozenGrammarPlans.applicability(noMusic, g6).reasons,
        )
        val g20 = FrozenGrammarPlans.def(20)
        val musicNoArt = noMusic.copy(music = FrozenGrammarPlans.MusicInput(artwork = false))
        assertEquals(
            listOf("Music artwork required for the sleeve"),
            FrozenGrammarPlans.applicability(musicNoArt, g20).reasons,
        )
        assertEquals(
            listOf(
                "Music required: it is a structural part of this grammar",
                "Music artwork required for the sleeve",
            ),
            FrozenGrammarPlans.applicability(noMusic, g20).reasons,
        )
        val g21 = FrozenGrammarPlans.def(21)
        assertEquals(
            listOf("A note is required to organise this composition"),
            FrozenGrammarPlans.applicability(noMusic, g21).reasons,
        )
        val g25n4 = FrozenGrammarPlans.def(25)
        assertEquals(
            listOf("Supports 3 photos; input has 4"),
            FrozenGrammarPlans.applicability(noMusic.copy(photos = List(4) { FrozenGrammarPlans.Photo("p$it", 1000.0, 1000.0) }), g25n4).reasons,
        )
    }

    @Test
    fun selectionPolicyCapsTwoDistinctPerFamilyAndTwelveTotal() {
        val p = List(3) { FrozenGrammarPlans.Photo("p$it", 1000.0, 1000.0) }
        val d = FrozenGrammarPlans.Input("x", "s", p, null, null)
        val branches = ArrayList<FrozenGrammarPlans.Branch>()
        for ((gid, limit) in listOf(5 to 6, 6 to 6, 11 to 6)) {
            val plans = FrozenGrammarPlans.plans(d, FrozenGrammarPlans.def(gid), FrozenGrammarPlans.noteState(d, FrozenGrammarPlans.def(gid), stub), stub)
            plans.take(limit).forEach { branches += FrozenGrammarPlans.Branch(gid, it) }
        }
        val selected = FrozenGrammarPlans.selectExpressions(branches)
        assertTrue("must cap at 12 total", selected.size <= 12)
        selected.groupBy { it.grammarId }.forEach { (_, v) -> assertTrue("max two per family", v.size <= 2) }
    }

    @Test
    fun sourceOrderAssignmentFlagsReadabilityFloor() {
        val tile = FrozenGrammarPlans.Tile("p0", FrozenGrammarPlans.Rect(0.0, 0.0, 100.0, 100.0), "rect", listOf(1.0))
        val ok = FrozenGrammarPlans.Plan("b", listOf(tile), null, null, emptyList(), emptyList())
        assertEquals(FrozenGrammarPlans.Assignment(true, listOf("p0")), FrozenGrammarPlans.sourceOrderAssignment(ok))
        val tiny = ok.copy(tiles = listOf(tile.copy(rect = FrozenGrammarPlans.Rect(0.0, 0.0, 50.0, 50.0))))
        assertEquals(false, FrozenGrammarPlans.sourceOrderAssignment(tiny).feasible)
    }
}
