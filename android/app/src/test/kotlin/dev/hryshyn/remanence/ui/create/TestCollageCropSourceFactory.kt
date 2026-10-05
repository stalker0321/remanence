package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource
import dev.hryshyn.remanence.ui.collage.CollageAnalysis
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageImage
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageMaps
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRunner
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRuntime
import dev.hryshyn.remanence.ui.collage.analysis.ModelAssetSource
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetImage
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRunner
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.YuNetConfig
import dev.hryshyn.remanence.ui.collage.analysis.YuNetImage
import dev.hryshyn.remanence.ui.collage.analysis.YuNetPass
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRunner
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRuntime
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * T150 integration fixture: a real [AndroidCollageCropSource] wired with the
 * accepted fake detector runtimes, so the production crop/analysis path
 * (shared `analysisSource()` cache, frozen solver, candidate provider) runs in
 * JVM tests instead of a stubbed crop source.
 *
 * The synthetic analysis image is fixed at 1000x1000 like the established
 * `CreateScreenCollageChooserTest`/`CollageCropSourceHostTest` fixture: the
 * frozen `RESOLUTION_TOO_LOW` floor (`analysis.width * rect.w / slot.w >= 1`)
 * cannot pass on the tiny `memoryTestJpegForPhotoId` pixel sizes, and this is
 * a detector fixture, not a photo claim.
 */
internal fun testCollageSourceFactory(
    cornerFace: Boolean = false,
    severableTip: Boolean = false,
): CollageCropSourceFactory =
    CollageCropSourceFactory {
        AndroidCollageCropSource(
            bytesSource = it.asUprightBytesSource(),
            imageDecoder = { _ ->
                FieldStageImage(FIXTURE_SOURCE_PX, FIXTURE_SOURCE_PX, IntArray(FIXTURE_SOURCE_PX * FIXTURE_SOURCE_PX) { 0xFF336699.toInt() })
            },
            fieldRunner = FieldStageRunner(FakeFieldRuntime(severableTip)),
            nanoDetRunner = NanoDetRunner(
                FakeAsset(ByteArray(32) { 1 }),
                FakeNanoRuntime(),
                Files.createTempDirectory("t150-nano").toFile(),
            ),
            yuNetRunner = YuNetRunner(
                FakeAsset(ByteArray(32) { 2 }),
                FakeYuNetRuntime(cornerFace, noFaces = severableTip),
                Files.createTempDirectory("t150-yunet").toFile(),
            ),
        )
    }

/**
 * Same production [AndroidCollageCropSource] seam, but with a caller-supplied
 * analysis image size, so the authoritative `CollageAnalysis.width/height` can
 * differ from the preview dims. One shared read-only image is reused for every
 * content id to keep the fixture cheap.
 */
internal fun testCollageSourceFactoryWithAnalysisDims(
    analysisWidth: Int,
    analysisHeight: Int,
): CollageCropSourceFactory {
    val image = FieldStageImage(
        analysisWidth,
        analysisHeight,
        IntArray(analysisWidth * analysisHeight) { 0xFF336699.toInt() },
    )
    return CollageCropSourceFactory {
        AndroidCollageCropSource(
            bytesSource = it.asUprightBytesSource(),
            imageDecoder = { image },
            fieldRunner = FieldStageRunner(FakeFieldRuntime()),
            nanoDetRunner = NanoDetRunner(
                FakeAsset(ByteArray(32) { 1 }),
                FakeNanoRuntime(),
                Files.createTempDirectory("t150-nano").toFile(),
            ),
            yuNetRunner = YuNetRunner(
                FakeAsset(ByteArray(32) { 2 }),
                FakeYuNetRuntime(false),
                Files.createTempDirectory("t150-yunet").toFile(),
            ),
        )
    }
}

private const val FIXTURE_SOURCE_PX = 1000

private class FakeAsset(val bytes: ByteArray) : ModelAssetSource {
    override val sha256: String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    override val byteSize: Long = bytes.size.toLong()
    override val maxBytes: Long = bytes.size.toLong() + 16
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** Constant protection fields: every window clears the frozen floors. */
private class FakeFieldRuntime(private val severableTip: Boolean = false) : FieldStageRuntime {
    override fun analyze(image: FieldStageImage): FieldStageMaps {
        val cells = CollageAnalysis.GRID_SIZE * CollageAnalysis.GRID_SIZE
        return FieldStageMaps(
            sourceWidth = image.width,
            sourceHeight = image.height,
            saliency = DoubleArray(cells) { 1.0 },
            edgeEnergy = DoubleArray(cells) { 1.0 },
            contrast = DoubleArray(cells) { 1.0 },
            tips = DoubleArray(cells) { 1.0 },
            tipBoxes = if (severableTip) listOf(
                dev.hryshyn.remanence.ui.collage.NormBox(.88, .88, .08, .08),
            ) else emptyList(),
        )
    }

    override fun close() = Unit
}

private class FakeNanoRuntime : NanoDetRuntime {
    override fun load(modelPath: String) = Unit

    override fun forward(image: NanoDetImage): List<dev.hryshyn.remanence.ui.collage.NanoDetHead> {
        val n = 36
        return listOf(
            dev.hryshyn.remanence.ui.collage.NanoDetHead(
                "cls",
                intArrayOf(n, dev.hryshyn.remanence.ui.collage.NanoDetDecoder.CLASS_COUNT),
                FloatArray(n * dev.hryshyn.remanence.ui.collage.NanoDetDecoder.CLASS_COUNT),
            ),
            dev.hryshyn.remanence.ui.collage.NanoDetHead(
                "reg",
                intArrayOf(n, dev.hryshyn.remanence.ui.collage.NanoDetDecoder.REG_CHANNELS),
                FloatArray(n * dev.hryshyn.remanence.ui.collage.NanoDetDecoder.REG_CHANNELS),
            ),
        )
    }

    override fun close() = Unit
}

/**
 * [cornerFace] parks the single detected face in the extreme top-left corner
 * of the 1000x1000 fixture source, so a rounded/elliptic aperture clips it
 * while the rectangular one keeps it (shape-safety regressions).
 */
private class FakeYuNetRuntime(
    private val cornerFace: Boolean = false,
    private val noFaces: Boolean = false,
) : YuNetRuntime {
    override fun load(modelPath: String, config: YuNetConfig) = Unit

    override fun detect(image: YuNetImage, longEdge: Int): YuNetPass {
        if (noFaces) return YuNetPass(image.width, image.height, 15, emptyList())
        val values = FloatArray(15)
        values[0] = if (cornerFace) 0f else 64f
        values[1] = if (cornerFace) 0f else 64f
        values[2] = if (cornerFace) 48f else 128f
        values[3] = if (cornerFace) 48f else 128f
        values[14] = 0.9f
        return YuNetPass(image.width, image.height, 15, listOf(values))
    }

    override fun close() = Unit
}
