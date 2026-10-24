package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assume.assumeTrue

/**
 * Opt-in benchmark: the production FRONT pipeline (localization, warp,
 * SIFT/RootSIFT fingerprint, LocalMatchEngine visual decision) over a dataset
 * manifest of sender enrollment photos and recipient queries.
 *
 * REMANENCE_BENCH_DIR holds manifest.json + img/<id>.jpg (upright, working
 * size). REMANENCE_BENCH_V2=true runs the v2 line locator first, as the
 * debug switch does. REMANENCE_BENCH_CANDIDATES=N matches each query against
 * the true card plus N-1 other cards (0 = whole gallery).
 */
class DatasetMatchBenchmarkTest {
    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    private class Shot(val id: String, val fingerprint: SiftRootSiftFingerprint?, val source: String)

    @Test
    fun benchmarkDataset() {
        val dir = System.getenv("REMANENCE_BENCH_DIR")
        assumeTrue("set REMANENCE_BENCH_DIR", dir != null)
        val useV2 = System.getenv("REMANENCE_BENCH_V2") == "true"
        val candidateCount = System.getenv("REMANENCE_BENCH_CANDIDATES")?.toInt() ?: 5
        val threads = System.getenv("REMANENCE_BENCH_THREADS")?.toInt() ?: 3
        val tag = System.getenv("REMANENCE_BENCH_TAG") ?: "run"
        val root = File(dir!!)
        val manifest = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject
        val gallery = manifest["gallery"]!!.jsonArray.map { it.jsonObject }
        val queries = manifest["queries"]!!.jsonArray.map { it.jsonObject }
        val profile = RecognitionProfile.postcardSiftRootSiftV1()
        val pool = Executors.newFixedThreadPool(threads)

        fun fingerprint(id: String): Shot {
            val image = ImageIO.read(File(root, "img/$id.jpg"))
            val w = image.width
            val h = image.height
            val pixels = image.getRGB(0, 0, w, h, null, 0, w)
            val primary = if (useV2) runCatching { V2LinePostcardLocator(profile).detect(pixels, w, h) }.getOrNull() else null
            val attempts = LocalizationProposalSelector(PostcardCropSelector(profile))
                .orderedAttempts(primary, PostcardContourDetector(profile).detect(pixels, w, h), w, h)
            val chosen = attempts.firstNotNullOfOrNull { selection ->
                runCatching { selection to PerspectiveWarper(profile).warp(pixels, w, h, selection.candidate.corners) }.getOrNull()
            } ?: return Shot(id, null, "NONE")
            val (selection, warped) = chosen
            val fp = runCatching {
                SiftRootSiftFingerprintExtractor(profile.sift).extract(warped.pixels, warped.width, warped.height)
            }.getOrNull()?.takeIf { it.keypoints.isNotEmpty() }
            return Shot(id, fp, selection.proposalSource.name)
        }

        val started = System.nanoTime()
        val enrolled = gallery.map { g -> pool.submit<Pair<String, Shot>> { g.str("card_id")!! to fingerprint(g.str("id")!!) } }
            .map { it.get() }.toMap()
        val shots = queries.map { q -> pool.submit<Shot> { fingerprint(q.str("id")!!) } }.map { it.get() }
        val extractSeconds = (System.nanoTime() - started) / 1e9
        val capsuleOf = enrolled.keys.associateWith { UUID.nameUUIDFromBytes(it.toByteArray()) }
        val cardOf = capsuleOf.entries.associate { (k, v) -> v to k }
        val cards = enrolled.keys.sorted()

        val rows = queries.indices.map { i ->
            pool.submit<String> {
                val q = queries[i]
                val expected = q.str("expected")
                val shot = shots[i]
                val random = Random(i * 7919L + 17)
                val pickedCards = if (candidateCount <= 0) {
                    cards
                } else {
                    val others = cards.filter { it != expected }.shuffled(random).take(if (expected != null) candidateCount - 1 else candidateCount)
                    (listOfNotNull(expected) + others)
                }
                val candidates = pickedCards.mapNotNull { card ->
                    enrolled[card]?.fingerprint?.let { IndexedCandidate(capsuleOf.getValue(card), it) }
                }
                val outcome: String
                val predicted: String?
                if (shot.fingerprint == null) {
                    outcome = "capture_rejected"; predicted = null
                } else {
                    val engine = LocalMatchEngine(profile, verifier = { true }, grantIssuer = { "g" })
                    when (val r = runBlocking { engine.evaluateVisual(shot.fingerprint, candidates) }) {
                        is VisualScanResult.Accepted -> {
                            predicted = cardOf[r.capsuleId]
                            outcome = when {
                                expected == null -> "false_accept"
                                predicted == expected -> "correct"
                                else -> "wrong"
                            }
                        }
                        is VisualScanResult.Ambiguous -> {
                            predicted = r.rows.joinToString("|") { cardOf[it.first].orEmpty() }
                            outcome = when {
                                expected == null -> "unknown_chooser"
                                r.rows.any { cardOf[it.first] == expected } -> "chooser_has_truth"
                                else -> "chooser_without_truth"
                            }
                        }
                        is VisualScanResult.RecaptureRequired -> {
                            predicted = null
                            outcome = if (expected == null) "unknown_rejected" else "recapture"
                        }
                    }
                }
                listOf(
                    q.str("id"), q.str("set"), q.str("batch").orEmpty(), q.str("category").orEmpty(), expected.orEmpty(),
                    shot.source, outcome, predicted.orEmpty(),
                    q["python"]?.jsonObject?.let { py -> py.entries.joinToString(";") { "${it.key}=${it.value.jsonPrimitive.contentOrNull}" } }.orEmpty(),
                ).joinToString(",")
            }
        }.map { it.get() }
        pool.shutdown()
        val out = File(root, "results-$tag.csv")
        out.writeText("id,set,batch,category,expected,source,outcome,predicted,python\n" + rows.joinToString("\n") + "\n")
        val gallerySources = enrolled.values.groupingBy { it.source }.eachCount()
        File(root, "results-$tag.txt").writeText(
            "v2=$useV2 candidates=$candidateCount extract_s=%.0f total_s=%.0f gallery_sources=%s\n".format(
                extractSeconds, (System.nanoTime() - started) / 1e9, gallerySources,
            ),
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
