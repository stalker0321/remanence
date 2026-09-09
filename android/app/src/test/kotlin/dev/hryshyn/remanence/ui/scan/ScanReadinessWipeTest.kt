package dev.hryshyn.remanence.ui.scan

import dev.hryshyn.remanence.core.model.SiftRootSiftFingerprint
import dev.hryshyn.remanence.core.recognition.IndexedCandidate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScanReadinessWipeTest {

    @Test
    fun cancellationWhileReadinessOwnsIndexWipesBeforeDispatcherReturn() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val indexBuilt = CompletableDeferred<Unit>()
        val holdAfterBuild = CompletableDeferred<Unit>()
        lateinit var fingerprint: SiftRootSiftFingerprint

        val job = launch {
            loadIndexReadiness(
                ioDispatcher = ioDispatcher,
                build = {
                    fingerprint = SiftRootSiftFingerprint(
                        profileId = "test-profile",
                        canonicalWidthPx = 10,
                        canonicalHeightPx = 10,
                        coarseHash64 = 1L,
                        keypoints = emptyList(),
                        quantizedSiftDescriptors = listOf(byteArrayOf(7, 8, 9)),
                    )
                    indexBuilt.complete(Unit)
                    ScanCandidateIndex(
                        candidates = listOf(
                            IndexedCandidate(
                                capsuleId = UUID.fromString("0198f0a0-0000-7000-8000-00000000f001"),
                                front = fingerprint,
                                recipientPreferred = false,
                            ),
                        ),
                    )
                },
                afterIndexBuilt = { holdAfterBuild.await() },
            )
        }

        runCurrent()
        indexBuilt.await()
        assertTrue(fingerprint.quantizedSiftDescriptors.single().any { it.toInt() != 0 })

        job.cancelAndJoin()

        assertTrue(fingerprint.quantizedSiftDescriptors.single().all { it.toInt() == 0 })
    }
}
