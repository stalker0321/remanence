package dev.hryshyn.remanence.create

import android.content.Context
import dev.hryshyn.remanence.core.recognition.FrozenLocalizer
import dev.hryshyn.remanence.core.recognition.FrozenMatcherS3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bundled frozen-matcher assets: the ONNX card localizer and the visual vocabulary. */
object FrozenMatcherS3Assets {
    /** 1024x128 float32 word centres followed by 1024 float32 IDF weights, little-endian. */
    const val VOCABULARY_ASSET = "frozen-matcher/v1/s3_vocabulary.bin"
    private const val WORDS = 1024

    fun model(context: Context): ByteArray? =
        runCatching { context.assets.open(FrozenLocalizer.MODEL_ASSET_PATH).use { it.readBytes() } }.getOrNull()

    /** Needs OpenCV loaded; null when the asset is missing or malformed. */
    fun vocabulary(context: Context): FrozenMatcherS3.Vocabulary? = runCatching {
        val bytes = context.assets.open(VOCABULARY_ASSET).use { it.readBytes() }
        require(bytes.size == (WORDS * 128 + WORDS) * 4)
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val centers = FloatArray(WORDS * 128).also { floats.get(it) }
        val idf = FloatArray(WORDS).also { floats.get(it) }
        FrozenMatcherS3.Vocabulary(centers, idf)
    }.getOrNull()
}
