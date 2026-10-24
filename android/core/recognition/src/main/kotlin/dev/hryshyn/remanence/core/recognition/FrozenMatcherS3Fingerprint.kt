package dev.hryshyn.remanence.core.recognition

import dev.hryshyn.remanence.core.model.FrozenMatcherS3FingerprintCodec
import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.model.SiftRootSiftKeypoint
import kotlin.math.roundToInt

/**
 * [FrozenMatcherS3.Features] in the shared fingerprint container, so S3
 * fingerprints travel and persist exactly like v1 ones.
 *
 * Keypoints are stored relative to the crop (micro units); the matcher reads
 * positions and descriptors only. A recipient query also carries where its ROI
 * sits in the 2:3 frame and the mask component count; those never leave the
 * device and are packed into the otherwise unused `coarseHash64`.
 */
object FrozenMatcherS3Fingerprint {
    data class QueryFrame(
        val originX: Int,
        val originY: Int,
        val frameWidth: Int,
        val frameHeight: Int,
        val components: Int,
    ) {
        init {
            require(listOf(originX, originY, frameWidth, frameHeight).all { it in 0 until FIELD_LIMIT })
            require(components >= 0)
        }

        internal fun pack(): Long =
            (originX.toLong() shl 47) or (originY.toLong() shl 34) or (frameWidth.toLong() shl 21) or
                (frameHeight.toLong() shl 8) or minOf(components, 255).toLong() or QUERY_MARK

        companion object {
            internal fun unpack(value: Long): QueryFrame? {
                if (value and QUERY_MARK == 0L) return null
                val v = value and QUERY_MARK.inv()
                return QueryFrame(
                    originX = ((v ushr 47) and MASK13).toInt(),
                    originY = ((v ushr 34) and MASK13).toInt(),
                    frameWidth = ((v ushr 21) and MASK13).toInt(),
                    frameHeight = ((v ushr 8) and MASK13).toInt(),
                    components = (v and 0xFF).toInt(),
                )
            }
        }
    }

    fun isS3(fingerprint: SiftRootSiftFingerprint): Boolean =
        fingerprint.profileId == FrozenMatcherS3FingerprintCodec.PROFILE_ID

    fun toFingerprint(features: FrozenMatcherS3.Features, query: QueryFrame? = null): SiftRootSiftFingerprint {
        require(features.count >= 1) { "no features" }
        val keypoints = List(features.count) { i ->
            SiftRootSiftKeypoint(
                xMicro = micro(features.xy[2 * i], features.width),
                yMicro = micro(features.xy[2 * i + 1], features.height),
                scaleMicro = 0,
                angleCentiDegrees = 0,
                responseQuantized = 0,
                octave = 0,
            )
        }
        val rows = List(features.count) { i -> features.descriptors.copyOfRange(i * 128, (i + 1) * 128) }
        return SiftRootSiftFingerprint(
            profileId = FrozenMatcherS3FingerprintCodec.PROFILE_ID,
            canonicalWidthPx = features.width,
            canonicalHeightPx = features.height,
            coarseHash64 = query?.pack() ?: 0L,
            keypoints = keypoints,
            quantizedSiftDescriptors = rows,
        )
    }

    fun features(fingerprint: SiftRootSiftFingerprint): FrozenMatcherS3.Features {
        require(isS3(fingerprint)) { "not an S3 fingerprint" }
        val n = fingerprint.keypoints.size
        val w = fingerprint.canonicalWidthPx
        val h = fingerprint.canonicalHeightPx
        val xy = FloatArray(2 * n)
        val descriptors = ByteArray(128 * n)
        fingerprint.keypoints.forEachIndexed { i, k ->
            xy[2 * i] = (k.xMicro.toDouble() / MICRO * w).toFloat()
            xy[2 * i + 1] = (k.yMicro.toDouble() / MICRO * h).toFloat()
        }
        fingerprint.quantizedSiftDescriptors.forEachIndexed { i, row -> row.copyInto(descriptors, i * 128) }
        val frame = queryFrame(fingerprint)
        return if (frame == null) {
            FrozenMatcherS3.Features(xy, descriptors, w, h)
        } else {
            FrozenMatcherS3.Features(xy, descriptors, w, h, frame.originX, frame.originY, frame.frameWidth, frame.frameHeight)
        }
    }

    fun queryFrame(fingerprint: SiftRootSiftFingerprint): QueryFrame? = QueryFrame.unpack(fingerprint.coarseHash64)

    private fun micro(value: Float, size: Int): Int = (value.toDouble() / size * MICRO).roundToInt().coerceIn(0, MICRO.toInt())

    private const val MICRO = 1_000_000.0
    private const val FIELD_LIMIT = 1 shl 13
    private const val MASK13 = (1L shl 13) - 1
    private const val QUERY_MARK = Long.MIN_VALUE
}
