package dev.hryshyn.remanence.sync

import android.util.Log
import dev.hryshyn.remanence.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Debug-build-only, redacted visibility into the background acceptance boundary. */
internal object IncomingAcceptanceDiagnostics {
    private const val TAG = "RemanenceIncomingAcceptance"
    private val mutableState = MutableStateFlow("not run")
    private val mutableSchedulingState = MutableStateFlow("not run")
    private val mutableWorkerProgress = MutableStateFlow("not run")
    /**
     * Publication and invalidation share one short, synchronous fence.  The
     * guard callback is deliberately evaluated while holding it, but it must
     * remain a pure in-memory check: no suspend or network work may enter this
     * monitor.
     */
    private val publicationMonitor = Any()
    val state: StateFlow<String> = mutableState
    val schedulingState: StateFlow<String> = mutableSchedulingState
    val workerProgress: StateFlow<String> = mutableWorkerProgress

    internal enum class Channel {
        SCHEDULING,
        WORKER,
    }

    /** Runs a short lifecycle invalidation transaction on the same fence. */
    internal fun <T> withPublicationFence(block: () -> T): T =
        synchronized(publicationMonitor) { block() }

    /**
     * A reporter is valid only for one owner/session/attempt operation. Its
     * guard is supplied by that operation, and close() makes late callbacks
     * inert even when the account happens to be unchanged.
     */
    internal class Reporter internal constructor(
        private val channel: Channel,
        private val isCurrent: () -> Boolean,
        private val beforePublishForTests: (() -> Unit)? = null,
    ) {
        private var open = true

        fun report(value: String) {
            synchronized(IncomingAcceptanceDiagnostics.publicationMonitor) {
                if (!open || !isCurrent()) return
                // Test-only barrier used to prove that reset cannot interleave
                // between eligibility and the actual publication.
                beforePublishForTests?.invoke()
                IncomingAcceptanceDiagnostics.publish(channel, value)
            }
        }

        fun report(outcome: IncomingSyncSchedulingOutcome) = report(outcome.safeStatus)

        fun report(stage: IncomingSyncWorkerStage) = report(stage.safeStatus)

        fun report(diagnostic: IncomingAcceptanceDownloadDiagnostic) {
            report(
                if (BuildConfig.DEBUG) diagnostic.safeSummary()
                else "acceptance download failure",
            )
        }

        fun report(diagnostic: IncomingAcceptancePersistenceDiagnostic) {
            report(
                if (BuildConfig.DEBUG) diagnostic.safeSummary()
                else "acceptance persistence failure",
            )
        }

        fun reportPersistenceRetry(
            reason: IncomingCapsuleAcceptanceRetryReason,
            diagnostic: IncomingAcceptancePersistenceDiagnostic,
        ) {
            report(
                if (BuildConfig.DEBUG) {
                    "acceptance retry: ${reason.name} ${diagnostic.safeSummary()}"
                } else {
                    "acceptance retry: ${reason.name}"
                },
            )
        }

        fun close() {
            synchronized(IncomingAcceptanceDiagnostics.publicationMonitor) {
                open = false
            }
        }
    }

    fun schedulingReporter(
        isCurrent: () -> Boolean,
    ): Reporter = Reporter(Channel.SCHEDULING, isCurrent)

    internal fun schedulingReporter(
        isCurrent: () -> Boolean,
        beforePublishForTests: () -> Unit,
    ): Reporter = Reporter(Channel.SCHEDULING, isCurrent, beforePublishForTests)

    fun workerReporter(
        isCurrent: () -> Boolean,
    ): Reporter = Reporter(Channel.WORKER, isCurrent)

    internal fun workerReporter(
        isCurrent: () -> Boolean,
        beforePublishForTests: () -> Unit,
    ): Reporter = Reporter(Channel.WORKER, isCurrent, beforePublishForTests)

    /** Drops a previous owner's process-global summary at an account boundary. */
    fun reset() {
        synchronized(publicationMonitor) {
            resetUnsafe()
        }
    }

    fun report(value: String) {
        synchronized(publicationMonitor) {
            if (value == "not run") {
                resetUnsafe()
            } else {
                publish(Channel.WORKER, value)
            }
        }
    }

    fun report(outcome: IncomingSyncSchedulingOutcome) {
        synchronized(publicationMonitor) {
            publish(Channel.SCHEDULING, outcome.safeStatus)
        }
    }

    fun report(stage: IncomingSyncWorkerStage) {
        synchronized(publicationMonitor) {
            publish(Channel.WORKER, stage.safeStatus)
        }
    }

    fun report(diagnostic: IncomingAcceptanceDownloadDiagnostic) {
        Reporter(Channel.WORKER, isCurrent = { true }).report(diagnostic)
    }

    fun report(diagnostic: IncomingAcceptancePersistenceDiagnostic) {
        Reporter(Channel.WORKER, isCurrent = { true }).report(diagnostic)
    }

    fun reportPersistenceRetry(
        reason: IncomingCapsuleAcceptanceRetryReason,
        diagnostic: IncomingAcceptancePersistenceDiagnostic,
    ) {
        Reporter(Channel.WORKER, isCurrent = { true }).reportPersistenceRetry(reason, diagnostic)
    }

    private fun publish(channel: Channel, value: String) {
        when (channel) {
            Channel.SCHEDULING -> mutableSchedulingState.value = value
            Channel.WORKER -> mutableWorkerProgress.value = value
        }
        // Keep the old test/debug seam, but production UI consumes the two
        // channel-specific flows above so worker progress cannot overwrite
        // scheduling status.
        mutableState.value = value
        if (BuildConfig.DEBUG) {
            // Local JVM tests do not provide Android's Log implementation;
            // diagnostic logging must never affect acceptance behavior.
            runCatching { Log.d(TAG, value) }
        }
    }

    private fun resetUnsafe() {
        mutableSchedulingState.value = "not run"
        mutableWorkerProgress.value = "not run"
        mutableState.value = "not run"
    }
}

/** Safe lifecycle markers emitted before and during one incoming worker run. */
enum class IncomingSyncWorkerStage(val safeStatus: String) {
    SESSION_RESTORE("sync stage=session restore"),
    TOMBSTONE_SYNC("sync stage=tombstone sync"),
    INDEX_SYNC("sync stage=index sync"),
    ACCEPTANCE("sync stage=acceptance"),
    PREFETCH("sync stage=prefetch"),
    ACK("sync stage=ack"),
}

/** The bounded persistence stages exposed by the debug acceptance diagnostic. */
enum class IncomingAcceptancePersistenceStage {
    SEAL,
    PART_CREATE,
    PART_WRITE,
    FILE_FORCE,
    DIRECTORY_FORCE,
    PUBLICATION,
    DESTINATION_VERIFY,
    REPLAY_READ,
    REPLAY_UNSEAL,
}

/** No path, exception, secret, or payload is carried across this boundary. */
data class IncomingAcceptancePersistenceDiagnostic(
    val stage: IncomingAcceptancePersistenceStage,
) {
    fun safeSummary(): String = "acceptance persistence stage=${stage.name}"

    override fun toString(): String = "IncomingAcceptancePersistenceDiagnostic(<redacted>)"
}
