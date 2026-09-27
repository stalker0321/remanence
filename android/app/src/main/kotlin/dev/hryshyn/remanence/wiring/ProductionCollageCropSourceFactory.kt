package dev.hryshyn.remanence.wiring

import android.content.Context
import dev.hryshyn.remanence.ui.collage.AndroidCollageCropSource
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRunner
import dev.hryshyn.remanence.ui.collage.analysis.FieldStageRuntime
import dev.hryshyn.remanence.ui.collage.analysis.FieldUprightDecoder
import dev.hryshyn.remanence.ui.collage.analysis.ModelAssetSource
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRunner
import dev.hryshyn.remanence.ui.collage.analysis.NanoDetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.OpenCvFieldStage
import dev.hryshyn.remanence.ui.collage.analysis.OpenCvNanoDetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.OpenCvYuNetRuntime
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRunner
import dev.hryshyn.remanence.ui.collage.analysis.YuNetRuntime
import dev.hryshyn.remanence.ui.create.CollageCropSourceFactory

/**
 * The single installed-app construction path for authoritative collage
 * analysis. Runtime providers are injectable only so Robolectric can exercise
 * this exact holder/decoder/bundled-asset/cache wiring without loading Android
 * native libraries in the host JVM.
 */
internal fun productionCollageCropSourceFactory(
    appContext: Context,
    fieldRuntime: () -> FieldStageRuntime = { OpenCvFieldStage() },
    nanoDetRuntime: () -> NanoDetRuntime = { OpenCvNanoDetRuntime() },
    yuNetRuntime: () -> YuNetRuntime = { OpenCvYuNetRuntime() },
): CollageCropSourceFactory = CollageCropSourceFactory { holder ->
    val cacheBase = java.io.File(appContext.cacheDir, "collage")
    AndroidCollageCropSource(
        bytesSource = holder.asUprightBytesSource(),
        imageDecoder = FieldUprightDecoder::decodeUpright,
        fieldRunner = FieldStageRunner(fieldRuntime()),
        nanoDetRunner = NanoDetRunner(
            collageModelAsset(appContext, "collage/nanodet.onnx", NANODET_BYTES, NANODET_SHA256),
            nanoDetRuntime(),
            java.io.File(cacheBase, "nano"),
        ),
        yuNetRunner = YuNetRunner(
            collageModelAsset(appContext, "collage/yunet.onnx", YUNET_BYTES, YUNET_SHA256),
            yuNetRuntime(),
            java.io.File(cacheBase, "yunet"),
        ),
    )
}

/** Bundled collage model opened lazily and verified before native load. */
private fun collageModelAsset(
    appContext: Context,
    assetPath: String,
    byteSize: Long,
    sha256: String,
): ModelAssetSource = object : ModelAssetSource {
    override val sha256: String = sha256
    override val byteSize: Long = byteSize
    override val maxBytes: Long = MAX_COLLAGE_ASSET_BYTES
    override fun open(): java.io.InputStream = appContext.assets.open(assetPath)
}

private const val MAX_COLLAGE_ASSET_BYTES = 8L * 1024 * 1024
private const val NANODET_BYTES = 3_800_954L
private const val NANODET_SHA256 =
    "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"
private const val YUNET_BYTES = 232_589L
private const val YUNET_SHA256 =
    "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
