package dev.hryshyn.remanence.core.recognition

import org.opencv.core.Mat

/** Test-only allocator that fails before the selected native allocation. */
internal class FailingNativeMatAllocator(
    private val failAt: Int,
) : NativeMatAllocator {
    var attempts: Int = 0
        private set
    val allocated: MutableList<Mat> = mutableListOf()

    override fun allocate(factory: () -> Mat): Mat {
        attempts += 1
        if (attempts == failAt) throw AssertionError("deterministic Mat allocation failure at $failAt")
        return factory().also { allocated += it }
    }
}
