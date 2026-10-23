package dev.hryshyn.remanence.core.recognition

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Assume.assumeTrue

/**
 * Opt-in probe: runs the production front localization (v2 line locator,
 * legacy contours, guide fallback, warp) over a folder of upright working
 * frames and writes the chosen quad per frame plus the warped crop.
 */
class DatasetLocalizationProbeTest {
    @BeforeTest
    fun loadNative() {
        runCatching { System.loadLibrary("opencv_java4100") }
            .onFailure { assumeTrue("desktop OpenCV natives unavailable: $it", false) }
    }

    @Test
    fun localizeDatasetFrames() {
        val input = System.getenv("REMANENCE_LOCALIZATION_IN")
        val output = System.getenv("REMANENCE_LOCALIZATION_OUT")
        assumeTrue("set REMANENCE_LOCALIZATION_IN/OUT", input != null && output != null)
        val profile = RecognitionProfile.postcardSiftRootSiftV1()
        val v2 = V2LinePostcardLocator(profile)
        val legacy = PostcardContourDetector(profile)
        val selector = LocalizationProposalSelector(PostcardCropSelector(profile))
        val warper = PerspectiveWarper(profile)
        val outDir = File(output!!).apply { mkdirs() }
        val raws = StringBuilder("frame,source,x0,y0,x1,y1,x2,y2,x3,y3,area,rect,edge\n")
        val rows = StringBuilder("frame,width,height,source,x0,y0,x1,y1,x2,y2,x3,y3,area,rect,edge\n")
        File(input!!).listFiles { f -> f.extension == "png" }!!.sorted().forEach { file ->
            val image = ImageIO.read(file)
            val w = image.width
            val h = image.height
            val pixels = image.getRGB(0, 0, w, h, null, 0, w)
            val primary = runCatching { v2.detect(pixels, w, h) }.getOrNull()
            val legacyCandidates = legacy.detect(pixels, w, h)
            val attempts = selector.orderedAttempts(primary, legacyCandidates, w, h)
            fun raw(tag: String, c: QuadCandidate?) {
                if (c == null) return
                raws.append("${file.nameWithoutExtension},$tag,")
                    .append(c.corners.joinToString(",") { "%.1f,%.1f".format(it.x, it.y) })
                    .append(",%.3f,%.3f,%s\n".format(c.areaRatio, c.rectangularity, c.edgeSupport?.let { "%.3f".format(it) } ?: ""))
            }
            primary?.take(3)?.forEach { raw("V2_RAW", it) }
            legacyCandidates.take(3).forEach { raw("LEGACY_RAW", it) }
            val chosen = attempts.firstNotNullOfOrNull { selection ->
                runCatching { selection to warper.warp(pixels, w, h, selection.candidate.corners) }.getOrNull()
            }
            if (chosen == null) {
                rows.append("${file.nameWithoutExtension},$w,$h,NONE,,,,,,,,,,,\n")
                return@forEach
            }
            val (selection, warped) = chosen
            val c = selection.candidate
            val corners = c.corners.joinToString(",") { "%.1f,%.1f".format(it.x, it.y) }
            rows.append("${file.nameWithoutExtension},$w,$h,${selection.proposalSource},$corners,")
                .append("%.3f,%.3f,%s\n".format(c.areaRatio, c.rectangularity, c.edgeSupport?.let { "%.3f".format(it) } ?: ""))
            val crop = BufferedImage(warped.width, warped.height, BufferedImage.TYPE_INT_RGB)
            crop.setRGB(0, 0, warped.width, warped.height, warped.pixels, 0, warped.width)
            ImageIO.write(crop, "jpg", File(outDir, "${file.nameWithoutExtension}.jpg"))
        }
        File(outDir, "quads.csv").writeText(rows.toString())
        File(outDir, "raw.csv").writeText(raws.toString())
    }
}
