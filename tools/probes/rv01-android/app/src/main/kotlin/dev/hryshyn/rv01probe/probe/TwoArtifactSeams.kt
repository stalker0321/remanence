package dev.hryshyn.rv01probe.probe

/** Fixed, redaction-safe settlement categories for one owned async operation. */
enum class TaskResultKind {
    COMPLETED,
    UNAVAILABLE,
    RETRYABLE_UNAVAILABLE,
    INCOMPLETE,
    INDETERMINATE,
}

sealed interface TaskResult<out T> {
    val kind: TaskResultKind

    data class Completed<T>(val value: T) : TaskResult<T> {
        override val kind: TaskResultKind = TaskResultKind.COMPLETED
    }

    data object Unavailable : TaskResult<Nothing> {
        override val kind: TaskResultKind = TaskResultKind.UNAVAILABLE
    }

    data object RetryableUnavailable : TaskResult<Nothing> {
        override val kind: TaskResultKind = TaskResultKind.RETRYABLE_UNAVAILABLE
    }

    data object Incomplete : TaskResult<Nothing> {
        override val kind: TaskResultKind = TaskResultKind.INCOMPLETE
    }

    data object Indeterminate : TaskResult<Nothing> {
        override val kind: TaskResultKind = TaskResultKind.INDETERMINATE
    }
}

/** Fixed outcomes for the fake two-artifact runner; no capability-GO state exists. */
enum class TwoArtifactOutcome {
    PASS,
    REJECTED,
    FAIL_CLOSED,
    UNAVAILABLE,
    RETRYABLE_UNAVAILABLE,
    INCOMPLETE,
    INDETERMINATE,
}

enum class TwoArtifactSuccessLabel {
    U_DURABILITY_PLUS_HARNESS_P,
}

data class TwoArtifactRunResult(
    val outcome: TwoArtifactOutcome,
    val successLabel: TwoArtifactSuccessLabel? = null,
) {
    init {
        if (outcome == TwoArtifactOutcome.PASS) {
            require(successLabel == TwoArtifactSuccessLabel.U_DURABILITY_PLUS_HARNESS_P)
        } else {
            require(successLabel == null)
        }
    }
}

/**
 * A probe-owned settlement guard. It models timeout/cancellation and ignores
 * duplicate or late callbacks without retaining exception text or raw values.
 */
class OwnedTaskSettlement<T> {
    private val lock = Any()
    private var settled: TaskResult<T>? = null

    fun complete(value: T): Boolean = settle(TaskResult.Completed(value))

    fun unavailable(): Boolean = settle(TaskResult.Unavailable)

    fun retryableUnavailable(): Boolean = settle(TaskResult.RetryableUnavailable)

    fun timeout(): Boolean = retryableUnavailable()

    fun cancel(): Boolean = retryableUnavailable()

    fun incomplete(): Boolean = settle(TaskResult.Incomplete)

    fun indeterminate(): Boolean = settle(TaskResult.Indeterminate)

    fun snapshot(): TaskResult<T> = synchronized(lock) {
        settled ?: TaskResult.Incomplete
    }

    private fun settle(value: TaskResult<T>): Boolean = synchronized(lock) {
        if (settled != null) return false
        settled = value
        true
    }
}

/** The exact opaque provider slot key; it is not a secret or account namespace. */
object ProbeKey {
    const val LENGTH = 22
    private val BASE64_URL_KEY = Regex("[A-Za-z0-9_-]{22}")

    fun isValid(value: String): Boolean = value.length == LENGTH && BASE64_URL_KEY.matches(value)
}

/** A token returned by the fake for an unresolved exact-key delete. */
data class PendingDeleteToken(
    val key: String,
    val sequence: Long,
)

/** Only an operator/provider reconciliation can clear a pending delete. */
enum class DeleteResolution {
    DELETED,
    ABSENT,
}

interface UStore {
    /** The only value accepted here is exactly 32-byte U under exact K_U. */
    fun storeU(key: String, value: ByteArray): TaskResult<Unit>

    /** Returns only the exact provider value for exact K_U. */
    fun retrieveU(key: String): TaskResult<ByteArray>

    /**
     * Deletes only exact K_U; no bulk/history operation exists. Implementations
     * must quarantine a key after an unresolved delete until reconciliation.
     */
    fun deleteU(key: String): TaskResult<Unit>
}

interface PTransport {
    /** Carries only opaque P and non-secret trusted context metadata. */
    fun storeP(opaqueP: ByteArray, expectedContext: ExpectedContext): TaskResult<Unit>

    /** Returns only opaque P; parsing and authority remain with ProbeSidecar. */
    fun readP(expectedContext: ExpectedContext): TaskResult<ByteArray>
}
