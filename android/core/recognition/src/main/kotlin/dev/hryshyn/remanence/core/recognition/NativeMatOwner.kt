package dev.hryshyn.remanence.core.recognition

import org.opencv.core.Mat

/**
 * Owns every native Mat allocated during one recognition operation. Allocation
 * is routed through [allocator] so tests can fail deterministically between
 * native allocations and prove already-created Mats are released.
 */
internal class NativeMatOwner(
    private val allocator: NativeMatAllocator = NativeMatAllocator.DEFAULT,
) {
    private val mats = ArrayList<Mat>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Mat> allocate(factory: () -> T): T {
        val mat = allocator.allocate(factory as () -> Mat) as T
        mats += mat
        return mat
    }

    fun releaseAll() {
        mats.asReversed().forEach { it.release() }
        mats.clear()
    }
}

internal fun interface NativeMatAllocator {
    fun allocate(factory: () -> Mat): Mat

    companion object {
        val DEFAULT = NativeMatAllocator { factory -> factory() }
    }
}

/**
 * Test-only operation seam for failures after native allocation has started.
 * Production callers use [NONE], so this cannot alter recognition behavior.
 */
internal fun interface NativeOperationFault {
    fun check(stage: String)

    companion object {
        val NONE = NativeOperationFault { }
    }
}
