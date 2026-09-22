package dev.hryshyn.remanence.create

import android.graphics.Bitmap
import dev.hryshyn.remanence.core.model.GeneratorStaging
import dev.hryshyn.remanence.core.model.UserId
import java.io.ByteArrayOutputStream

/**
 * Shared memory-backed C1 bridge seam for CreateViewModel publish tests:
 * one fake-clocked [GeneratorStaging.Manager] per owner plus a shared
 * G4B binder (fake normalizer, production EXIF decoder). Liveness is
 * observed through the public [GeneratorStaging.Manager.sweep] boundary.
 * Same-module tests (e.g. `ui.create`) import this directly — the app
 * test source set is shared across packages.
 */
class MemoryGeneratorBridge(
    var nowMillis: Long = 1_000L,
    normalizer: PhotoNormalizerPort = FakeNormalizer(),
) {

    class FakeStore : GeneratorStaging.BlobStore {
        val data = mutableMapOf<String, ByteArray>()
        override fun put(key: String, bytes: ByteArray) {
            data[key] = bytes.copyOf()
        }
        override fun get(key: String): ByteArray? = data[key]?.copyOf()
        override fun delete(key: String): Boolean = data.remove(key) != null
        override fun keys(): Set<String> = data.keys.toSet()
    }

    class FakeNormalizer : PhotoNormalizerPort {
        override suspend fun normalize(inputJpeg: ByteArray) =
            NormalizedPhotoDto(ByteArray(32) { 5 }, 120, 213)
    }

    val stores = mutableMapOf<String, FakeStore>()
    private val managers = mutableMapOf<String, GeneratorStaging.Manager>()
    private val bridges = mutableMapOf<String, GeneratorCreateBridge.Bridge>()
    val requestedOwners = mutableListOf<UserId>()

    val binder = GeneratorSourceBinding.SourceBinder(normalizer, GeneratorExifDecoder)

    val provider: (UserId) -> GeneratorCreateBridge.Bridge = { owner ->
        requestedOwners += owner
        val key = owner.toRestString()
        val staging = managers.getOrPut(key) {
            val store = FakeStore()
            stores[key] = store
            GeneratorStaging.Manager(store, nowMillis = { nowMillis })
        }
        bridges.getOrPut(key) { GeneratorCreateBridge.Bridge(staging, binder) }
    }

    /**
     * Sweep past TTL summed over every manager. Evictive by G3 design:
     * assert at most once per test leg.
     */
    fun liveSessions(): Int =
        managers.values.sumOf { it.sweep(nowMillis + GeneratorStaging.DEFAULT_SESSION_TTL_MILLIS + 1) }

    /** Sizes of every blob currently held across all owner stores, sorted. */
    fun stagedSizes(): List<Int> = stores.values.flatMap { it.data.values }.map { it.size }.sorted()
}

/**
 * Real JPEG bytes for generator pre-read/bind fixtures. Decoding them
 * requires Robolectric NATIVE graphics mode (legacy stubs BitmapFactory);
 * add `@GraphicsMode(Mode.NATIVE)` at class or method level.
 */
fun memoryTestJpeg(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) { "fixture encode failed" }
        return out.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

/**
 * Distinct decodable bytes per picker id (G1 requires a unique contentHash
 * per original): dimensions derive from the id's trailing digit, so the
 * same id always yields the same bytes across re-opens.
 */
fun memoryTestJpegForPhotoId(id: String): ByteArray {
    val digit = id.lastOrNull()?.digitToIntOrNull() ?: 0
    return memoryTestJpeg(64 + digit * 16, 32 + digit * 8)
}