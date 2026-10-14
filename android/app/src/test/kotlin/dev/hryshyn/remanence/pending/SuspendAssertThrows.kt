package dev.hryshyn.remanence.pending

import org.junit.Assert.fail
import kotlin.reflect.KClass

/**
 * T186 test helper: JUnit-compatible suspension-aware exception
 * assertion. Java SAMs (`assertThrows(..., ThrowingRunnable)`) cannot
 * contain suspend calls even inside `runTest`; this helper awaits the
 * block directly. Only the expected type is caught — assertion errors,
 * cancellations and foreign throwables propagate, and a clean return
 * fails explicitly (never a swallowed gate).
 */
internal suspend fun assertThrowsSuspend(
    expected: KClass<out Throwable>,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (thrown: Throwable) {
        if (expected.isInstance(thrown)) return
        throw thrown
    }
    fail("expected ${expected.simpleName ?: expected} but nothing was thrown")
}
