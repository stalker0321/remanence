package dev.hryshyn.remanence.core.data.network

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
internal suspend fun <T> OkHttpClient.executeResponseWithCallLifetime(
    request: Request,
    consume: suspend (Response) -> T,
): T = withContext(Dispatchers.IO) {
    val call = newCall(request)
    val responseRef = AtomicReference<Response?>()
    val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            call.cancel()
            responseRef.get()?.close()
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
    } finally {
        cancellationHandle?.dispose()
        responseRef.getAndSet(null)?.close()
    }
}
