package dev.hryshyn.remanence.ui.collage

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Record-only interchange contract test for `tools/a4b2/cases/case-001.json`
 * (schema `a4b2-interchange-v1`). Format/contract only: strict schema keys
 * (any unknown key or schema value fails — no silent renames), 2304 shapes
 * with finite values, test-local parse/serialize determinism, an independent
 * brightness recompute from the documented synthetic-pixel formula, and
 * feeding the parsed inputs through the frozen [FieldAggregator] to the
 * exact expected outputs plus the `a4b2-canonical-v1` integrity hash.
 *
 * This is NOT numeric parity and must never be named PASS for parity.
 * Explicitly OPEN: any Python/cv2/device numeric tolerance,
 * `OpenCvFieldStage` JVM execution, real-photo/detector/crop parity,
 * physical arm64-v8a plus second-ABI execution, and
 * `AndroidCollageCropSource`/Create/capability wiring. Parsing and canonical
 * serialization stay test-local (`org.json`, already used by unit tests under
 * Robolectric) so no production dependency changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A4b2InterchangeTest {

    private val cells = CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE

    private fun caseFile(): File {
        var directory: File? = File("").absoluteFile
        repeat(8) {
            val base = directory
            if (base != null) {
                val candidate = File(base, "tools/a4b2/cases/case-001.json")
                if (candidate.exists()) return candidate
            }
            directory = directory?.parentFile
        }
        error("case-001.json not found")
    }

    private fun assertKeys(obj: JSONObject, expected: Set<String>, where: String) {
        val actual = mutableSetOf<String>()
        val keys = obj.keys()
        while (keys.hasNext()) actual += keys.next()
        assertEquals("unexpected keys at $where", expected, actual)
    }

    private fun doubles(array: JSONArray, where: String): DoubleArray {
        assertEquals("$where must hold $cells values", cells, array.length())
        return DoubleArray(array.length()) { i ->
            val v = array.getDouble(i)
            assertTrue("$where[$i] must be finite", v.isFinite())
            v
        }
    }

    private fun escape(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun emitDouble(value: Double): String = value.toString()

    private fun emitArray(array: JSONArray): String = buildString {
        append('[')
        for (i in 0 until array.length()) {
            if (i > 0) append(',')
            val v = array.get(i)
            append(if (v is Number) emitDouble(v.toDouble()) else emitJson(v))
        }
        append(']')
    }

    private fun emitJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> escape(value)
        is Boolean -> value.toString()
        is Number -> emitDouble(value.toDouble())
        is JSONArray -> emitArray(value)
        is JSONObject -> error("nested objects must use fixed-order emitters")
        else -> error("unsupported JSON value")
    }

    private fun emitFace(face: JSONObject): String {
        assertKeys(face, setOf("box", "confidence"), "inputs.faces[]")
        return "{\"box\":" + emitArray(face.getJSONArray("box")) +
            ",\"confidence\":" + emitDouble(face.getDouble("confidence")) + "}"
    }

    private fun emitObject(obj: JSONObject): String {
        assertKeys(obj, setOf("box", "confidence", "classId", "label"), "inputs.objects[]")
        return "{\"box\":" + emitArray(obj.getJSONArray("box")) +
            ",\"confidence\":" + emitDouble(obj.getDouble("confidence")) +
            ",\"classId\":" + obj.getInt("classId") +
            ",\"label\":" + escape(obj.getString("label")) + "}"
    }

    private fun emitTipBox(box: JSONObject): String {
        assertKeys(box, setOf("x", "y", "width", "height"), "inputs.tipBoxes[]")
        return "{\"x\":" + emitDouble(box.getDouble("x")) +
            ",\"y\":" + emitDouble(box.getDouble("y")) +
            ",\"width\":" + emitDouble(box.getDouble("width")) +
            ",\"height\":" + emitDouble(box.getDouble("height")) + "}"
    }

    private fun emitRegion(region: JSONObject): String {
        val keys = mutableSetOf<String>()
        val it = region.keys()
        while (it.hasNext()) keys += it.next()
        assertTrue("region keys", keys == setOf("kind", "box", "confidence", "critical") ||
            keys == setOf("kind", "box", "confidence", "critical", "classId", "label"))
        val sb = StringBuilder("{\"kind\":")
        sb.append(escape(region.getString("kind")))
        sb.append(",\"box\":").append(emitArray(region.getJSONArray("box")))
        sb.append(",\"confidence\":").append(emitDouble(region.getDouble("confidence")))
        sb.append(",\"critical\":").append(region.getBoolean("critical"))
        if (region.has("classId")) {
            sb.append(",\"classId\":").append(region.getInt("classId"))
            sb.append(",\"label\":").append(escape(region.getString("label")))
        }
        return sb.append('}').toString()
    }

    private fun joinObjects(array: JSONArray, emit: (JSONObject) -> String): String = buildString {
        append('[')
        for (i in 0 until array.length()) {
            if (i > 0) append(',')
            append(emit(array.getJSONObject(i)))
        }
        append(']')
    }

    /** Full fixed-order canonical JSON text of the record (test-local). */
    private fun canonicalJson(root: JSONObject): String {
        assertKeys(root, setOf("schema", "provenance", "inputs", "expected", "integrity"), "top")
        assertEquals("a4b2-interchange-v1", root.getString("schema"))
        val provenance = root.getJSONObject("provenance")
        assertKeys(
            provenance,
            setOf(
                "freezeArchiveSha256", "legacyFeaturesSha256", "aggregatorCommit",
                "python", "numpy", "cv2", "pil", "onnxruntime", "toolchainNote",
                "androidOpenCv", "cvThreads", "generator", "pixelNote",
            ),
            "provenance",
        )
        val inputs = root.getJSONObject("inputs")
        assertKeys(
            inputs,
            setOf(
                "sourceWidth", "sourceHeight", "faces", "objects", "saliency",
                "edgeEnergy", "contrast", "structureField", "tips", "tipBoxes",
                "syntheticPixels",
            ),
            "inputs",
        )
        val expected = root.getJSONObject("expected")
        assertKeys(
            expected,
            setOf(
                "gridSize", "brightness", "importance", "baselineField",
                "objectField", "saliency", "edgeEnergy", "structureField",
                "protectedRegions", "crop",
            ),
            "expected",
        )
        val integrity = root.getJSONObject("integrity")
        assertKeys(integrity, setOf("algorithm", "canonical", "sha256"), "integrity")

        val sb = StringBuilder("{\"schema\":")
        sb.append(escape(root.getString("schema")))
        sb.append(",\"provenance\":{\"freezeArchiveSha256\":")
        sb.append(escape(provenance.getString("freezeArchiveSha256")))
        sb.append(",\"legacyFeaturesSha256\":")
        sb.append(escape(provenance.getString("legacyFeaturesSha256")))
        sb.append(",\"aggregatorCommit\":")
        sb.append(escape(provenance.getString("aggregatorCommit")))
        sb.append(",\"python\":").append(escape(provenance.getString("python")))
        for (tool in listOf("numpy", "cv2", "pil", "onnxruntime")) {
            sb.append(",\"").append(tool).append("\":")
            assertTrue("$tool must be explicit null", provenance.isNull(tool))
            sb.append("null")
        }
        sb.append(",\"toolchainNote\":").append(escape(provenance.getString("toolchainNote")))
        sb.append(",\"androidOpenCv\":").append(escape(provenance.getString("androidOpenCv")))
        sb.append(",\"cvThreads\":").append(provenance.getInt("cvThreads"))
        sb.append(",\"generator\":").append(escape(provenance.getString("generator")))
        sb.append(",\"pixelNote\":").append(escape(provenance.getString("pixelNote")))
        sb.append("},\"inputs\":{\"sourceWidth\":").append(inputs.getInt("sourceWidth"))
        sb.append(",\"sourceHeight\":").append(inputs.getInt("sourceHeight"))
        sb.append(",\"faces\":").append(joinObjects(inputs.getJSONArray("faces"), ::emitFace))
        sb.append(",\"objects\":").append(joinObjects(inputs.getJSONArray("objects"), ::emitObject))
        for (name in listOf("saliency", "edgeEnergy", "contrast", "structureField", "tips")) {
            sb.append(",\"").append(name).append("\":").append(emitArray(inputs.getJSONArray(name)))
        }
        sb.append(",\"tipBoxes\":").append(joinObjects(inputs.getJSONArray("tipBoxes"), ::emitTipBox))
        val pixels = inputs.getJSONObject("syntheticPixels")
        assertKeys(pixels, setOf("kind", "width", "height", "r", "g", "b", "grey"), "syntheticPixels")
        sb.append(",\"syntheticPixels\":{\"kind\":").append(escape(pixels.getString("kind")))
        sb.append(",\"width\":").append(pixels.getInt("width"))
        sb.append(",\"height\":").append(pixels.getInt("height"))
        for (channel in listOf("r", "g", "b", "grey")) {
            sb.append(",\"").append(channel).append("\":").append(escape(pixels.getString(channel)))
        }
        sb.append("}},\"expected\":{\"gridSize\":").append(expected.getInt("gridSize"))
        sb.append(",\"brightness\":").append(emitDouble(expected.getDouble("brightness")))
        for (name in listOf(
            "importance", "baselineField", "objectField", "saliency",
            "edgeEnergy", "structureField",
        )) {
            sb.append(",\"").append(name).append("\":").append(emitArray(expected.getJSONArray(name)))
        }
        sb.append(",\"protectedRegions\":")
        sb.append(joinObjects(expected.getJSONArray("protectedRegions"), ::emitRegion))
        val crop = expected.getJSONObject("crop")
        assertKeys(crop, setOf("oracle", "window", "pythonEmitsCrop", "note"), "expected.crop")
        sb.append(",\"crop\":{\"oracle\":").append(escape(crop.getString("oracle")))
        assertTrue("crop window must stay null", crop.isNull("window"))
        sb.append(",\"window\":null")
        sb.append(",\"pythonEmitsCrop\":").append(crop.getBoolean("pythonEmitsCrop"))
        sb.append(",\"note\":").append(escape(crop.getString("note")))
        sb.append("}},\"integrity\":{\"algorithm\":")
        sb.append(escape(integrity.getString("algorithm")))
        sb.append(",\"canonical\":").append(escape(integrity.getString("canonical")))
        sb.append(",\"sha256\":").append(escape(integrity.getString("sha256")))
        return sb.append("}}").toString()
    }

    /** `a4b2-canonical-v1` over six arrays: `key=v,v,...\n` per sorted key. */
    private fun canonicalHashed(fields: Map<String, DoubleArray>): String {
        val sb = StringBuilder()
        for (key in listOf(
            "baselineField", "edgeEnergy", "importance",
            "objectField", "saliency", "structureField",
        )) {
            val values = fields.getValue(key)
            sb.append(key).append('=')
            for (i in values.indices) {
                if (i > 0) sb.append(',')
                sb.append(String.format(Locale.US, "%.4f", values[i]))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun boxOf(box: JSONArray): NormBox {
        assertEquals(4, box.length())
        return NormBox(box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3))
    }

    @Test
    fun interchangeRecordParsesDeterministicallyAndMatchesFrozenAggregation() {
        val text = caseFile().readText(Charsets.UTF_8)
        val root = JSONObject(text)

        // Provenance pins (contract, not just presence).
        val provenance = root.getJSONObject("provenance")
        assertEquals(
            "43a8d54ee3b1e681d0d15d375d0787043d4b23bb770d7902915b34891a285a16",
            provenance.getString("freezeArchiveSha256"),
        )
        assertEquals(
            "ef7f5bfde86398beef358a9db195b6fd48b2b3a0b91a0a2d820357a662c84219",
            provenance.getString("legacyFeaturesSha256"),
        )
        assertEquals("4.10.0", provenance.getString("androidOpenCv"))
        assertEquals(2, provenance.getInt("cvThreads"))

        // Parse/serialize determinism through the test-local canonical form.
        val first = canonicalJson(root)
        val second = canonicalJson(JSONObject(first))
        assertEquals(first, second)

        val inputs = root.getJSONObject("inputs")
        val expected = root.getJSONObject("expected")
        assertEquals(48, expected.getInt("gridSize"))

        // The freeze/A4a pair knows exactly one tips map: inputs.structureField
        // echoes the merged tips map under its A4a input name and must equal
        // inputs.tips element-wise; tips feeds ImportFieldPrimitives.structureField.
        val tipsIn = doubles(inputs.getJSONArray("tips"), "inputs.tips")
        val structureEcho = doubles(inputs.getJSONArray("structureField"), "inputs.structureField")
        assertEquals(tipsIn.size, structureEcho.size)
        for (i in tipsIn.indices) assertEquals("structureField echo[$i]", tipsIn[i], structureEcho[i], 0.0)

        val faces = inputs.getJSONArray("faces")
        val objects = inputs.getJSONArray("objects")
        val primitives = ImportFieldPrimitives(
            sourceWidth = inputs.getInt("sourceWidth"),
            sourceHeight = inputs.getInt("sourceHeight"),
            faces = List(faces.length()) { i ->
                val face = faces.getJSONObject(i)
                AnalysisFace(boxOf(face.getJSONArray("box")), face.getDouble("confidence"))
            },
            objects = List(objects.length()) { i ->
                val obj = objects.getJSONObject(i)
                AnalysisObject(boxOf(obj.getJSONArray("box")), obj.getDouble("confidence"), obj.getInt("classId"))
            },
            saliency = doubles(inputs.getJSONArray("saliency"), "inputs.saliency"),
            edgeEnergy = doubles(inputs.getJSONArray("edgeEnergy"), "inputs.edgeEnergy"),
            contrast = doubles(inputs.getJSONArray("contrast"), "inputs.contrast"),
            structureField = tipsIn,
            tipBoxes = List(inputs.getJSONArray("tipBoxes").length()) { i ->
                val box = inputs.getJSONArray("tipBoxes").getJSONObject(i)
                NormBox(box.getDouble("x"), box.getDouble("y"), box.getDouble("width"), box.getDouble("height"))
            },
        )

        // Frozen aggregation reproduces the record's expected outputs exactly.
        val analysis = FieldAggregator.aggregate(primitives)
        assertEquals(640, analysis.width)
        assertEquals(480, analysis.height)
        val names = listOf(
            "importance" to analysis.importance,
            "baselineField" to analysis.baselineField,
            "objectField" to analysis.objectField,
            "saliency" to analysis.saliency,
            "edgeEnergy" to analysis.edgeEnergy,
            "structureField" to analysis.structureField,
        )
        for ((name, actual) in names) {
            val want = doubles(expected.getJSONArray(name), "expected.$name")
            assertEquals("$name size", want.size, actual.size)
            for (i in want.indices) assertEquals("$name[$i]", want[i], actual[i], 0.0)
        }

        // Integrity hash: over the parsed record and over the computed arrays.
        val integrity = root.getJSONObject("integrity")
        assertEquals("SHA-256", integrity.getString("algorithm"))
        assertEquals("a4b2-canonical-v1", integrity.getString("canonical"))
        val parsedFields = names.associate { (name, _) ->
            name to doubles(expected.getJSONArray(name), "expected.$name")
        }
        val computedFields = names.toMap()
        assertEquals(integrity.getString("sha256"), sha256Hex(canonicalHashed(parsedFields)))
        assertEquals(integrity.getString("sha256"), sha256Hex(canonicalHashed(computedFields)))

        // Protected regions: kinds/order/boxes/confidences/criticals, with
        // object classId/label echoing the inputs.
        val regions = expected.getJSONArray("protectedRegions")
        assertEquals(analysis.protectedRegions.size, regions.length())
        for (i in 0 until regions.length()) {
            val want = regions.getJSONObject(i)
            val got = analysis.protectedRegions[i]
            assertEquals("region[$i].kind", want.getString("kind"), got.kind)
            val wantBox = boxOf(want.getJSONArray("box"))
            assertEquals("region[$i].box.x", wantBox.x, got.box.x, 1e-12)
            assertEquals("region[$i].box.y", wantBox.y, got.box.y, 1e-12)
            assertEquals("region[$i].box.width", wantBox.width, got.box.width, 1e-12)
            assertEquals("region[$i].box.height", wantBox.height, got.box.height, 1e-12)
            assertEquals("region[$i].confidence", want.getDouble("confidence"), got.confidence, 1e-12)
            assertEquals("region[$i].critical", want.getBoolean("critical"), got.critical)
        }
        val wantObjects = regions.let { list ->
            (0 until list.length()).map { list.getJSONObject(it) }.filter { it.getString("kind") == "object" }
        }
        assertEquals(objects.length(), wantObjects.size)
        for (i in wantObjects.indices) {
            assertEquals(objects.getJSONObject(i).getInt("classId"), wantObjects[i].getInt("classId"))
            assertEquals(objects.getJSONObject(i).getString("label"), wantObjects[i].getString("label"))
        }

        // Independent brightness recompute from the documented synthetic
        // gradient (integer floor division, BT.601, half-even 3 decimals).
        var total = 0.0
        for (y in 0 until 96) {
            for (x in 0 until 96) {
                val r = (x * 255) / 95 / 255.0
                val g = (y * 255) / 95 / 255.0
                val b = ((x + y) * 255) / 190 / 255.0
                total += 0.299 * r + 0.587 * g + 0.114 * b
            }
        }
        val brightness = Math.rint(total / (96 * 96) * 1000.0) / 1000.0
        assertEquals(expected.getDouble("brightness"), brightness, 1e-12)

        // Crop stays explicitly separate: JS-oracle attribution only, Python
        // emits no crop, and it never enters the aggregator inputs.
        val crop = expected.getJSONObject("crop")
        assertEquals("frozen-JS-envelope-50", crop.getString("oracle"))
        assertFalse(crop.getBoolean("pythonEmitsCrop"))
        assertTrue(crop.isNull("window"))
    }
}
