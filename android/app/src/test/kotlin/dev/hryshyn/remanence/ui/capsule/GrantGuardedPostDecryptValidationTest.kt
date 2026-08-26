package dev.hryshyn.remanence.ui.capsule

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-REVIEW3-02 regression: the grant is validated AFTER the suspended
 * decrypt as well as before. Plaintext that materialized into a grant that
 * died mid-operation is refused - photo bytes are zeroed first - and never
 * returned. Deterministic gates, no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrantGuardedPostDecryptValidationTest {

    private class CountingReader : CapsuleContentReader {
        val calls = AtomicInteger(0)
        var photoBytes = "plaintext-photo".toByteArray()
        var noteValue: String? = "secret-note"

        override suspend fun photoCount(capsuleId: String): Int {
            calls.incrementAndGet()
            return 3
        }

        override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
            calls.incrementAndGet()
            return DecryptedPhoto(ordinal, photoBytes)
        }

        override suspend fun noteText(capsuleId: String): String? {
            calls.incrementAndGet()
            return noteValue
        }
    }

    @Test
    fun photoDecryptedIntoADeadGrantIsZeroedAndRefused() = runTest {
        val reader = CountingReader()
        var alive = true
        // The grant dies in the middle of the operation: after the guard's
        // pre-check, while the delegate "decrypts", before it returns.
        val dyingReader = object : CapsuleContentReader by reader {
            override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
                reader.calls.incrementAndGet()
                alive = false
                return DecryptedPhoto(ordinal, reader.photoBytes)
            }
        }
        val guarded = GrantGuardedCapsuleContentSource(dyingReader) {
            if (!alive) throw IllegalStateException("scan grant is no longer live")
        }

        val outcome = CompletableDeferred<Result<DecryptedPhoto>>()
        launch { outcome.complete(runCatching { guarded.loadPhoto("capsule", 1) }) }
        val result = outcome.await()

        assertTrue("post-decrypt refusal is mandatory", result.isFailure)
        assertTrue(
            "the decrypted plaintext must be zeroed before refusing",
            reader.photoBytes.contentEquals(ByteArray(reader.photoBytes.size)),
        )
        assertEquals(1, reader.calls.get())
    }

    @Test
    fun liveGrantStillServesThePhoto() = runTest {
        val reader = CountingReader()
        val guarded = GrantGuardedCapsuleContentSource(reader) { /* alive */ }

        val photo = guarded.loadPhoto("capsule", 0)

        assertEquals("plaintext-photo", String(photo.jpegBytes))
        assertEquals(1, reader.calls.get())
    }

    @Test
    fun noteDecryptedIntoADeadGrantIsRefusedWithoutDelivery() = runTest {
        val reader = CountingReader()
        var alive = true
        // The grant dies after the guard's pre-check, inside the operation.
        val dyingReader = object : CapsuleContentReader by reader {
            override suspend fun noteText(capsuleId: String): String? {
                reader.calls.incrementAndGet()
                alive = false
                return reader.noteValue
            }
        }
        val guarded = GrantGuardedCapsuleContentSource(dyingReader) {
            if (!alive) throw IllegalStateException("scan grant is no longer live")
        }

        val outcome = CompletableDeferred<Result<String?>>()
        launch { outcome.complete(runCatching { guarded.noteText("capsule") }) }

        assertTrue(outcome.await().isFailure)
        assertEquals(1, reader.calls.get())
    }

    @Test
    fun countOperationIsAlsoValidatedOnBothSides() = runTest {
        val reader = CountingReader()
        var validations = 0
        val guarded = GrantGuardedCapsuleContentSource(reader) { validations += 1 }

        assertEquals(3, guarded.photoCount("capsule"))
        assertEquals("one check before and one after the operation", 2, validations)
    }
}
