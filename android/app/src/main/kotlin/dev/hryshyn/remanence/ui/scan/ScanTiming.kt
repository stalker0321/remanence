package dev.hryshyn.remanence.ui.scan

import android.content.Context
import android.widget.Toast
import dev.hryshyn.remanence.BuildConfig

/**
 * Debug builds only: where the time of one scan-to-open goes, from the still
 * capture to the opened postcard. Marks are milliseconds since [start]; the
 * scan screen shows them live and the capsule screen reports the whole run.
 */
object ScanTiming {
    private var startedNanos = 0L
    private val marks = ArrayList<String>()
    private val keystoreOps = java.util.concurrent.atomic.AtomicInteger()
    private val keystoreNanos = java.util.concurrent.atomic.AtomicLong()

    /** Debug: one fingerprint seal/unseal through the Android Keystore took [nanos]. */
    fun keystore(nanos: Long) {
        if (!BuildConfig.DEBUG) return
        keystoreOps.incrementAndGet()
        keystoreNanos.addAndGet(nanos)
    }

    @Synchronized
    fun start() {
        if (!BuildConfig.DEBUG) return
        startedNanos = System.nanoTime()
        marks.clear()
        keystoreOps.set(0)
        keystoreNanos.set(0)
        stalls = ""
    }

    /** @return all marks so far, or null when no scan is being timed. */
    @Synchronized
    fun mark(label: String): String? {
        if (!BuildConfig.DEBUG || startedNanos == 0L) return null
        marks += "$label ${(System.nanoTime() - startedNanos) / 1_000_000}"
        val line = marks.joinToString(" · ") + " ms · keystore ${keystoreOps.get()}× ${keystoreNanos.get() / 1_000_000} ms"
        runCatching { android.util.Log.i("RemanenceTiming", line) }
        return line
    }

    @Volatile private var stalls: String = ""
    @Volatile private var watched: Thread? = null

    /**
     * Debug: if [label] has not finished within [afterMs], records what every
     * app thread is doing, so a stall can be traced to whoever holds it up.
     */
    fun watch(label: String, afterMs: Long = 3_000) {
        if (!BuildConfig.DEBUG) return
        val waiting = Thread.currentThread()
        watched = Thread {
            try {
                Thread.sleep(afterMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            stalls += "\n[$label stalled > $afterMs ms]\n" + Thread.getAllStackTraces().entries
                .filter { (thread, stack) -> thread === waiting || stack.any { it.className.startsWith("dev.hryshyn") } }
                .joinToString("\n") { (thread, stack) ->
                    val frames = stack.filter {
                        it.className.startsWith("dev.hryshyn") || it.className.startsWith("android.security") ||
                            it.className.startsWith("com.google.crypto") || it.methodName == "wait" || it.methodName == "park"
                    }.take(8)
                    "${thread.name} ${thread.state}${if (thread === waiting) " (waiting)" else ""}:\n" +
                        frames.joinToString("\n") { "  ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                }
        }.apply { isDaemon = true; start() }
    }

    fun unwatch() {
        watched?.interrupt()
        watched = null
    }

    /** Ends the run with [label], shows it, and keeps it for the history screen. */
    fun finish(context: Context, label: String) {
        val line = mark(label) ?: return
        synchronized(this) { startedNanos = 0L }
        Toast.makeText(context.applicationContext, line, Toast.LENGTH_LONG).show()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST, line + stalls).apply()
        stalls = ""
    }

    /** The last finished run, for copying from the history screen in debug builds. */
    fun last(context: Context): String? =
        if (!BuildConfig.DEBUG) null
        else context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null)

    private const val PREFS = "remanence.debug"
    private const val LAST = "last_scan_timing"
}
