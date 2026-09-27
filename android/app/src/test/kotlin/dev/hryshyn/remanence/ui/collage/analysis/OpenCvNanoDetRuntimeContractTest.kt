package dev.hryshyn.remanence.ui.collage.analysis

import dev.hryshyn.remanence.ui.collage.NanoDetDecoder
import dev.hryshyn.remanence.ui.collage.NanoDetHead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCvNanoDetRuntimeContractTest {

    @Test
    fun bundledThreeScaleOutputsArePairedInStrideOrder() {
        val outputs = listOf(
            head("reg13", 13 * 13, NanoDetDecoder.REG_CHANNELS),
            head("cls26", 26 * 26, NanoDetDecoder.CLASS_COUNT),
            head("cls13", 13 * 13, NanoDetDecoder.CLASS_COUNT),
            head("reg52", 52 * 52, NanoDetDecoder.REG_CHANNELS),
            head("cls52", 52 * 52, NanoDetDecoder.CLASS_COUNT),
            head("reg26", 26 * 26, NanoDetDecoder.REG_CHANNELS),
        )

        val ordered = orderBundledNanoDetHeads(outputs)

        assertEquals(
            listOf("cls52", "reg52", "cls26", "reg26", "cls13", "reg13"),
            ordered.map { it.name },
        )
        assertTrue(
            NanoDetDecoder.decode(ordered, NanoDetDecoder.letterbox(1200, 800))
                is NanoDetDecoder.Result.Detections,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun missingBundledScaleFailsClosed() {
        orderBundledNanoDetHeads(
            listOf(
                head("cls52", 52 * 52, NanoDetDecoder.CLASS_COUNT),
                head("reg52", 52 * 52, NanoDetDecoder.REG_CHANNELS),
                head("cls26", 26 * 26, NanoDetDecoder.CLASS_COUNT),
                head("reg26", 26 * 26, NanoDetDecoder.REG_CHANNELS),
            ),
        )
    }

    private fun head(name: String, spatial: Int, channels: Int) =
        NanoDetHead(name, intArrayOf(1, spatial, channels), FloatArray(spatial * channels))
}
