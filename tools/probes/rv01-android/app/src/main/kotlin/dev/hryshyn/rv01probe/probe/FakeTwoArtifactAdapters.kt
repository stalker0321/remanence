package dev.hryshyn.rv01probe.probe

/** Deterministic in-memory U adapter. It exposes no bulk or history operation. */
class FakeUStore : UStore {
    private val values = linkedMapOf<String, ByteArray>()
    private val deleted = mutableListOf<String>()
    private val pendingDeletes = linkedMapOf<String, PendingDeleteToken>()
    private var nextStore: TaskResult<Unit>? = null
    private var nextRetrieve: TaskResult<ByteArray>? = null
    private var nextDelete: TaskResult<Unit>? = null
    private var nextDeleteSequence = 1L

    val deletedKeys: List<String>
        get() = deleted.toList()

    val storedKeys: Set<String>
        get() = values.keys.toSet()

    fun respondToNextStore(result: TaskResult<Unit>) {
        nextStore = result
    }

    fun respondToNextRetrieve(result: TaskResult<ByteArray>) {
        nextRetrieve = result
    }

    fun respondToNextDelete(result: TaskResult<Unit>) {
        nextDelete = result
    }

    override fun storeU(key: String, value: ByteArray): TaskResult<Unit> {
        if (!ProbeKey.isValid(key) || value.size != ProbeSidecar.KEY_BYTES) {
            return TaskResult.Incomplete
        }
        if (pendingDeletes.containsKey(key)) return TaskResult.RetryableUnavailable

        val response = nextStore.also { nextStore = null }
        if (response != null && response !is TaskResult.Completed<*>) return response
        values[key]?.fill(0)
        values[key] = value.copyOf()
        return TaskResult.Completed(Unit)
    }

    override fun retrieveU(key: String): TaskResult<ByteArray> {
        if (!ProbeKey.isValid(key)) return TaskResult.Incomplete
        val response = nextRetrieve.also { nextRetrieve = null }
        if (response != null) {
            return when (response) {
                is TaskResult.Completed -> TaskResult.Completed(response.value.copyOf())
                TaskResult.Unavailable -> TaskResult.Unavailable
                TaskResult.RetryableUnavailable -> TaskResult.RetryableUnavailable
                TaskResult.Incomplete -> TaskResult.Incomplete
                TaskResult.Indeterminate -> TaskResult.Indeterminate
            }
        }
        return values[key]?.copyOf()?.let { TaskResult.Completed(it) } ?: TaskResult.Incomplete
    }

    override fun deleteU(key: String): TaskResult<Unit> {
        if (!ProbeKey.isValid(key)) return TaskResult.Incomplete
        if (pendingDeletes.containsKey(key)) return TaskResult.RetryableUnavailable

        val response = nextDelete.also { nextDelete = null }
        when (response) {
            null, is TaskResult.Completed -> {
                values.remove(key)?.fill(0)
                deleted += key
                return TaskResult.Completed(Unit)
            }

            TaskResult.Incomplete -> return TaskResult.Incomplete
            TaskResult.Unavailable,
            TaskResult.RetryableUnavailable,
            TaskResult.Indeterminate,
            -> {
                pendingDeletes[key] = PendingDeleteToken(key, nextDeleteSequence++)
                return response
            }
        }
    }

    /** Returns the unresolved operation token without exposing U or P. */
    fun pendingDeleteToken(key: String): PendingDeleteToken? = pendingDeletes[key]

    fun isDeleteQuarantined(key: String): Boolean = pendingDeletes.containsKey(key)

    /**
     * Resolves only the matching old token. A late token is ignored after
     * resolution, so it cannot remove a later value stored under the key.
     */
    fun reconcileDelete(token: PendingDeleteToken, resolution: DeleteResolution): Boolean {
        if (pendingDeletes[token.key] != token) return false
        pendingDeletes.remove(token.key)
        when (resolution) {
            DeleteResolution.DELETED,
            DeleteResolution.ABSENT,
            -> values.remove(token.key)?.fill(0)
        }
        return true
    }

    /** Test-only disappearance simulation; it does not clear quarantine state. */
    fun discardValueForTest(key: String) {
        values.remove(key)?.fill(0)
    }
}

/** Deterministic opaque-P adapter. It never receives U, canary, or plaintext. */
class FakePTransport : PTransport {
    private var storedP: ByteArray? = null
    private var nextStore: TaskResult<Unit>? = null
    private var nextRead: TaskResult<ByteArray>? = null

    fun respondToNextStore(result: TaskResult<Unit>) {
        nextStore = result
    }

    fun respondToNextRead(result: TaskResult<ByteArray>) {
        nextRead = result
    }

    fun timeoutNextRead() {
        respondToNextRead(TaskResult.RetryableUnavailable)
    }

    fun cancelNextRead() {
        respondToNextRead(TaskResult.RetryableUnavailable)
    }

    override fun storeP(opaqueP: ByteArray, expectedContext: ExpectedContext): TaskResult<Unit> {
        if (opaqueP.size > ProbeSidecar.MAX_SIZE) return TaskResult.Incomplete
        val response = nextStore.also { nextStore = null }
        if (response != null && response !is TaskResult.Completed<*>) return response
        storedP?.fill(0)
        storedP = opaqueP.copyOf()
        return TaskResult.Completed(Unit)
    }

    override fun readP(expectedContext: ExpectedContext): TaskResult<ByteArray> {
        val response = nextRead.also { nextRead = null }
        if (response != null) {
            return when (response) {
                is TaskResult.Completed -> TaskResult.Completed(response.value.copyOf())
                TaskResult.Unavailable -> TaskResult.Unavailable
                TaskResult.RetryableUnavailable -> TaskResult.RetryableUnavailable
                TaskResult.Incomplete -> TaskResult.Incomplete
                TaskResult.Indeterminate -> TaskResult.Indeterminate
            }
        }
        return storedP?.copyOf()?.let { TaskResult.Completed(it) } ?: TaskResult.Incomplete
    }

    /** Test-only disappearance simulation for a previously supplied sidecar. */
    fun discardForTest() {
        storedP?.fill(0)
        storedP = null
    }
}
