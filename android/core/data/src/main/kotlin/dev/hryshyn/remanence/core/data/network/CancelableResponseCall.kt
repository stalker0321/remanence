package dev.hryshyn.remanence.core.data.network

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync

/**
 * Executes one request while the exact OkHttp Call remains owned by the whole
 * response lifecycle. [executeAsync] cancels while waiting for headers, but
 * its continuation is complete once headers arrive; this boundary keeps
 * cancellation wired through bounded body consumption and parsing as well.
 */
@OptIn(InternalCoroutinesApi::class) // Pinned coroutines: notification at cancellation, not completion after blocked IO.
internal suspend fun <T> OkHttpClient.executeResponseWithCallLifetime(
    request: Request,
    consume: suspend (Response) -> T,
): T = withContext(Dispatchers.IO) {
    val call = newCall(request)
    val responseRef = AtomicReference<Response?>()
    val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause is CancellationException) {
            call.cancel()
            // The consuming IO owner closes the response in finally. Do not
            // concurrently close/read the same Okio source in this callback.
        }
    }

    try {
        val response = call.executeAsync()
        responseRef.set(response)
        currentCoroutineContext().ensureActive()
        consume(response)
    } catch (cancelled: CancellationException) {
        call.cancel()
        responseRef.get()?.close()
        throw cancelled
    } catch (failure: Exception) {
        // Socket abort may surface as IOException; cancellation still owns the
        // coroutine outcome and must not fail a newer sibling/parent operation.
        currentCoroutineContext().ensureActive()
        throw failure
    } finally {
        cancellationHandle?.dispose()
        responseRef.getAndSet(null)?.close()
    }
}
