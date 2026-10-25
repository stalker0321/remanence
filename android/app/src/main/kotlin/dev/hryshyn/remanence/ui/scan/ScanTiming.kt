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

    @Synchronized
    fun start() {
        if (!BuildConfig.DEBUG) return
        startedNanos = System.nanoTime()
        marks.clear()
    }

    /** @return all marks so far, or null when no scan is being timed. */
    @Synchronized
    fun mark(label: String): String? {
        if (!BuildConfig.DEBUG || startedNanos == 0L) return null
        marks += "$label ${(System.nanoTime() - startedNanos) / 1_000_000}"
        val line = marks.joinToString(" · ") + " ms"
        runCatching { android.util.Log.i("RemanenceTiming", line) }
        return line
    }

    /** Ends the run with [label] and shows the whole line over whatever screen is up. */
    fun finish(context: Context, label: String) {
        val line = mark(label) ?: return
        synchronized(this) { startedNanos = 0L }
        Toast.makeText(context.applicationContext, line, Toast.LENGTH_LONG).show()
    }
}
