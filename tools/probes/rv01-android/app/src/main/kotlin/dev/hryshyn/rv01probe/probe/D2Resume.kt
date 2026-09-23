package dev.hryshyn.rv01probe.probe

/**
 * Fresh-install D2 operator resume for the first A/G1 rehearsal. The operator
 * pastes the exact D1 handoff line and selects the P_D2 file through SAF. The
 * trusted context (A, G1, D2_TARGET) is built from the pasted fields and
 * never derived from P; K_U never enters P. Only exact-key retrieveU and a
 * SAF read of the selected P_D2 run, followed by one authenticated open of
 * exactly 32 bytes with immediate zeroization. No canary comparison exists on
 * D2, and there is no store, prepare, regenerate, or fallback path.
 *
 * Success wording is limited to U_DURABILITY_PLUS_HARNESS_P and evidence is
 * always LOCAL_DRY_RUN_NOT_EVIDENCE. This is not a physical/cloud PASS and
 * never touches Remanence. P1/P2/P3 paths are untouched.
 */
enum class D2ResumeStatus {
    IDLE,
    RUNNING,
    PASS,
    FAIL,
    BLOCKED,
}

enum class D2ResumeReason {
    NONE,
    INVALID_HANDOFF,
    PROVIDER_UNAVAILABLE,
    RETRYABLE_UNAVAILABLE,
    INCOMPLETE,
    P_MISSING,
    FAIL_CLOSED,
    INDETERMINATE,
    CANCELLED,
}

data class D2ResumeState(
    val status: D2ResumeStatus = D2ResumeStatus.IDLE,
    val reason: D2ResumeReason = D2ResumeReason.NONE,
    val successLabel: TwoArtifactSuccessLabel? = null,
    val evidenceClass: EvidenceClass = EvidenceClass.LOCAL_DRY_RUN_NOT_EVIDENCE,
) {
    init {
        require((status == D2ResumeStatus.PASS) == (successLabel != null))
    }
}

/** Parsed operator handoff; owns the trusted context bytes until wiped. */
class ParsedD2Handoff private constructor(
    val key: String,
    val context: ExpectedContext,
    val fixture: ExpectedContextFixture,
) : AutoCloseable {
    override fun close() {
        context.wipeRunId()
    }

    companion object {
        /**
         * Strictly parses one RV01-D2-HANDOFF-V1 line. Any deviation in tag,
         * fields, K_U shape, runId hex, or A/G1/D2_TARGET binding fails
         * closed with null. The returned context is independent of P.
         */
        fun parse(text: String): ParsedD2Handoff? {
            val tokens = text.trim().split(Regex("\\s+"))
            if (tokens.size != 8 || tokens[0] != D2HandoffDisplay.SCHEMA_TAG) return null
            val fields = linkedMapOf<String, String>()
            for (token in tokens.drop(1)) {
                val separator = token.indexOf('=')
                if (separator < 0) return null
                val name = token.substring(0, separator)
                val value = token.substring(separator + 1)
                if (name.isEmpty() || value.isEmpty() || fields.containsKey(name)) return null
                fields[name] = value
            }
            if (fields.keys != setOf(
                    "key",
                    "runIdHex",
                    "account",
                    "generation",
                    "role",
                    "profile",
                    "purpose",
                )
            ) {
                return null
            }
            if (fields["account"] != "A" ||
                fields["generation"] != "G1" ||
                fields["role"] != "D2_TARGET" ||
                fields["profile"] != "BLOCKSTORE_U_PLUS_P" ||
                fields["purpose"] != "BLOCKSTORE_REC01"
            ) {
                return null
            }
            val key = checkNotNull(fields["key"])
            if (!ProbeKey.isValid(key)) return null
            val runIdHex = checkNotNull(fields["runIdHex"])
            if (runIdHex.length != ExpectedContext.RUN_ID_BYTES * 2) return null
            val runId = runIdHex.hexToBytesOrNull() ?: return null
            if (runId.size != ExpectedContext.RUN_ID_BYTES) {
                runId.fill(0)
                return null
            }
            val context = try {
                ExpectedContext(
                    accountBindingClass = AccountBindingClass.A,
                    runId = runId,
                    generation = ContextGeneration.G1,
                    targetRole = ContextTargetRole.D2_TARGET,
                )
            } catch (_: RuntimeException) {
                runId.fill(0)
                return null
            }
            val fixture = ExpectedContextFixture.fromExternal(key, context)
            if (fixture == null) {
                context.wipeRunId()
                return null
            }
            return ParsedD2Handoff(key, context, fixture)
        }
    }
}

private enum class D2Phase {
    RETRIEVE_U,
    IMPORT_P,
}

private class D2Flight(
    val token: Long,
    val phase: D2Phase,
    val startedMs: Long,
    val lifecycleGeneration: Long,
    val parsed: ParsedD2Handoff,
) {
    var operation: ProbeControllerOperation? = null
    var deadline: ProbeScheduledHandle? = null
    var operationInstalled = false
    var pendingCompletion: (() -> Unit)? = null
    var pendingCleanup: (() -> Unit)? = null
    var abandonmentCleanup: (() -> Unit)? = null
    var retrievedU: ByteArray? = null
}

/**
 * Bounded single-flight D2 resume. Exactly one retrieveU plus one SAF import
 * per run; timeout/cancel close the flight and late callbacks are ignored
 * with their bytes wiped. Listeners observe enum-only redacted state.
 */
class D2ResumeController(
    private val uStore: ProbeUStorePort,
    private val pTransport: ProbePTransportPort,
    private val scheduler: ProbeScheduler,
    private val clock: ProbeClock = ProbeClock { System.nanoTime() / 1_000_000L },
    private val timeoutMs: Long = TASK_TIMEOUT_MS,
) : AutoCloseable {
    companion object {
        const val TASK_TIMEOUT_MS = 10_000L
    }

    private val lock = Any()
    private val listeners = linkedSetOf<(D2ResumeState) -> Unit>()
    private var currentState = D2ResumeState()
    private var activeFlight: D2Flight? = null
    private var nextToken = 1L
    private var lifecycleGeneration = 0L

    val state: D2ResumeState
        get() = synchronized(lock) { currentState }

    fun addListener(listener: (D2ResumeState) -> Unit): () -> Unit {
        synchronized(lock) {
            listeners += listener
        }
        listener(state)
        return { synchronized(lock) { listeners -= listener } }
    }

    /**
     * Starts one resume from pasted handoff text. Bad input settles FAIL
     * without any provider call. Returns false only while a run is active.
     */
    fun resume(handoffText: String): Boolean {
        val parsed = ParsedD2Handoff.parse(handoffText)
        if (parsed == null) {
            synchronized(lock) {
                if (activeFlight != null) return false
                currentState = D2ResumeState(
                    status = D2ResumeStatus.FAIL,
                    reason = D2ResumeReason.INVALID_HANDOFF,
                )
            }
            emit()
            return true
        }
        val token: Long
        synchronized(lock) {
            if (activeFlight != null) {
                parsed.close()
                return false
            }
            lifecycleGeneration += 1
            token = nextToken++
            activeFlight = D2Flight(
                token,
                D2Phase.RETRIEVE_U,
                clock.nowMs(),
                lifecycleGeneration,
                parsed,
            )
            currentState = D2ResumeState(
                status = D2ResumeStatus.RUNNING,
                reason = D2ResumeReason.NONE,
            )
        }
        emit()
        launchRetrieve(token)
        return true
    }

    fun cancel(): Boolean {
        val teardown = synchronized(lock) {
            val found = activeFlight ?: return false
            activeFlight = null
            found.deadline?.cancel()
            val cleanup = found.pendingCleanup
            val abandonment = found.abandonmentCleanup
            found.pendingCompletion = null
            found.pendingCleanup = null
            found.abandonmentCleanup = null
            found
        }
        teardown.pendingCleanup?.invoke()
        teardown.abandonmentCleanup?.invoke()
        teardown.operation?.cancel()
        finishFlight(teardown, D2ResumeStatus.BLOCKED, D2ResumeReason.CANCELLED)
        return true
    }

    override fun close() {
        val teardown = synchronized(lock) {
            val found = activeFlight
            lifecycleGeneration += 1
            activeFlight = null
            found?.deadline?.cancel()
            val cleanup = found?.pendingCleanup
            val abandonment = found?.abandonmentCleanup
            found?.pendingCompletion = null
            found?.pendingCleanup = null
            found?.abandonmentCleanup = null
            if (found != null) Triple(found, cleanup, abandonment) else null
        }
        if (teardown != null) {
            teardown.second?.invoke()
            teardown.third?.invoke()
            teardown.first.operation?.cancel()
            wipeFlight(teardown.first)
        }
    }

    private fun launchRetrieve(token: Long) {
        invokeAndInstall(token) {
            val flight = synchronized(lock) { activeFlight } ?: return@invokeAndInstall NoopD2Operation
            uStore.retrieveU(flight.parsed.key) { result ->
                settleOrDefer(token, onIgnored = { wipeCompletedBytes(result) }) { settled ->
                    handleRetrieve(settled, result)
                }
            }
        }
    }

    private fun handleRetrieve(flight: D2Flight, result: TaskResult<ByteArray>) {
        val retrieved = (result as? TaskResult.Completed)?.value
        if (retrieved == null) {
            when (result) {
                is TaskResult.Completed -> failFlight(flight, D2ResumeReason.FAIL_CLOSED)
                TaskResult.Unavailable ->
                    finishFlight(flight, D2ResumeStatus.BLOCKED, D2ResumeReason.PROVIDER_UNAVAILABLE)
                TaskResult.RetryableUnavailable ->
                    finishFlight(flight, D2ResumeStatus.BLOCKED, D2ResumeReason.RETRYABLE_UNAVAILABLE)
                TaskResult.Incomplete ->
                    finishFlight(flight, D2ResumeStatus.BLOCKED, D2ResumeReason.INCOMPLETE)
                TaskResult.Indeterminate ->
                    finishFlight(flight, D2ResumeStatus.FAIL, D2ResumeReason.INDETERMINATE)
            }
            return
        }
        if (retrieved.size != ProbeSidecar.KEY_BYTES) {
            retrieved.fill(0)
            failFlight(flight, D2ResumeReason.FAIL_CLOSED)
            return
        }
        // settleOrDefer consumed the retrieve flight; open a fresh IMPORT_P
        // flight carrying the retained U forward in the same generation, as
        // the main controller does between phases.
        val importFlight = synchronized(lock) {
            if (lifecycleGeneration != flight.lifecycleGeneration) {
                null
            } else {
                val token = nextToken++
                D2Flight(
                    token,
                    D2Phase.IMPORT_P,
                    clock.nowMs(),
                    lifecycleGeneration,
                    flight.parsed,
                ).also {
                    it.retrievedU = retrieved
                    activeFlight = it
                }
            }
        }
        if (importFlight == null) {
            retrieved.fill(0)
            return
        }
        emit()
        launchImport(importFlight)
    }

    private fun launchImport(flight: D2Flight) {
        val token = flight.token
        val retrievedCleanup: () -> Unit = {
            synchronized(lock) { flight.retrievedU }?.fill(0)
        }
        if (!registerAbandonmentCleanup(token, retrievedCleanup)) return
        invokeAndInstall(token) {
            pTransport.importP(flight.parsed.context) { result ->
                settleOrDefer(
                    token,
                    onIgnored = {
                        retrievedCleanup()
                        wipeCompletedBytes(result)
                    },
                ) { settled ->
                    handleImported(settled, result)
                }
            }
        }
        if (!isGenerationCurrent(token)) {
            retrievedCleanup()
        }
    }

    private fun handleImported(flight: D2Flight, result: TaskResult<ByteArray>) {
        val sidecar = (result as? TaskResult.Completed)?.value
        val retrieved = synchronized(lock) { flight.retrievedU }
        if (sidecar == null || retrieved == null) {
            sidecar?.fill(0)
            when (result) {
                is TaskResult.Completed -> failFlight(flight, D2ResumeReason.FAIL_CLOSED)
                TaskResult.Unavailable ->
                    finishFlight(flight, D2ResumeStatus.BLOCKED, D2ResumeReason.P_MISSING)
                TaskResult.RetryableUnavailable ->
                    finishFlight(flight, D2ResumeStatus.BLOCKED, D2ResumeReason.RETRYABLE_UNAVAILABLE)
                TaskResult.Incomplete ->
                    finishFlight(flight, D2ResumeStatus.BLOCKED, D2ResumeReason.INCOMPLETE)
                TaskResult.Indeterminate ->
                    finishFlight(flight, D2ResumeStatus.FAIL, D2ResumeReason.INDETERMINATE)
            }
            return
        }
        var plaintext: ByteArray? = null
        var handedOff = false
        try {
            val opened = ProbeSidecar.open(sidecar, retrieved, flight.parsed.context)
            if (opened != null && opened.size == ProbeSidecar.CANARY_BYTES) {
                plaintext = opened
                handedOff = true
            }
        } catch (_: RuntimeException) {
            // Fail closed below.
        } finally {
            sidecar.fill(0)
            retrieved.fill(0)
            flight.parsed.close()
            if (!handedOff) plaintext?.fill(0)
        }
        if (plaintext == null) {
            failFlight(flight, D2ResumeReason.FAIL_CLOSED)
            return
        }
        plaintext.fill(0)
        synchronized(lock) {
            activeFlight = null
            flight.deadline?.cancel()
            currentState = D2ResumeState(
                status = D2ResumeStatus.PASS,
                reason = D2ResumeReason.NONE,
                successLabel = TwoArtifactSuccessLabel.U_DURABILITY_PLUS_HARNESS_P,
            )
        }
        emit()
    }

    private fun failFlight(flight: D2Flight, reason: D2ResumeReason) {
        wipeFlight(flight)
        finishFlight(flight, D2ResumeStatus.FAIL, reason)
    }

    private fun finishFlight(flight: D2Flight, status: D2ResumeStatus, reason: D2ResumeReason) {
        wipeFlight(flight)
        synchronized(lock) {
            if (activeFlight === flight) {
                activeFlight = null
            }
            // A late winner must not overwrite a newer settled state; only
            // settle when no newer flight exists.
            if (activeFlight == null) {
                currentState = D2ResumeState(status = status, reason = reason)
            }
        }
        emit()
    }

    private fun wipeFlight(flight: D2Flight) {
        synchronized(lock) { flight.retrievedU }?.fill(0)
        synchronized(lock) { flight.retrievedU = null }
        flight.parsed.close()
    }

    private fun timeoutFlight(token: Long) {
        val found = synchronized(lock) {
            val current = activeFlight
            if (current == null || current.token != token) return
            activeFlight = null
            current.deadline?.cancel()
            val cleanup = current.pendingCleanup
            val abandonment = current.abandonmentCleanup
            current.pendingCompletion = null
            current.pendingCleanup = null
            current.abandonmentCleanup = null
            Triple(current, cleanup, abandonment)
        }
        found.second?.invoke()
        found.third?.invoke()
        found.first.operation?.timeout()
        finishFlight(found.first, D2ResumeStatus.BLOCKED, D2ResumeReason.RETRYABLE_UNAVAILABLE)
    }

    private fun isGenerationCurrent(token: Long): Boolean = synchronized(lock) {
        activeFlight?.token == token && activeFlight?.lifecycleGeneration == lifecycleGeneration
    }

    private fun invokeAndInstall(token: Long, invocation: () -> ProbeControllerOperation) {
        try {
            synchronized(lock) {
                val flight = activeFlight
                if (flight == null ||
                    flight.token != token ||
                    flight.lifecycleGeneration != lifecycleGeneration
                ) {
                    return
                }
                installOperation(token, invocation())
            }
        } catch (error: Error) {
            abortErroredFlight(token)
            throw error
        } catch (_: RuntimeException) {
            synchronized(lock) {
                acceptedFlight(token)?.let {
                    finishFlight(it, D2ResumeStatus.FAIL, D2ResumeReason.INDETERMINATE)
                }
            }
        }
    }

    private fun installOperation(token: Long, operation: ProbeControllerOperation) {
        var pendingCompletion: (() -> Unit)? = null
        var pendingCleanup: (() -> Unit)? = null
        val installed = synchronized(lock) {
            val flight = activeFlight
            if (flight == null || flight.token != token) {
                false
            } else {
                flight.operation = operation
                flight.operationInstalled = true
                pendingCompletion = flight.pendingCompletion
                pendingCleanup = flight.pendingCleanup
                flight.pendingCompletion = null
                flight.pendingCleanup = null
                true
            }
        }
        if (!installed) {
            pendingCleanup?.invoke()
            operation.cancel()
            return
        }
        if (pendingCompletion != null) {
            pendingCompletion()
            return
        }
        synchronized(lock) {
            val flight = activeFlight
            if (flight == null || flight.token != token) return
            flight.deadline = scheduler.schedule(timeoutMs) {
                timeoutFlight(token)
            }
        }
    }

    private fun settleOrDefer(
        token: Long,
        onIgnored: () -> Unit = {},
        handler: (D2Flight) -> Unit,
    ) {
        var ignored = false
        var normalCleanup: (() -> Unit)? = null
        synchronized(lock) {
            val flight = activeFlight
            if (flight == null ||
                flight.token != token ||
                flight.lifecycleGeneration != lifecycleGeneration
            ) {
                ignored = true
            } else if (!flight.operationInstalled) {
                if (flight.pendingCompletion != null) {
                    ignored = true
                } else {
                    flight.pendingCompletion = { settleOrDefer(token, onIgnored, handler) }
                    flight.pendingCleanup = onIgnored
                }
            } else {
                activeFlight = null
                flight.deadline?.cancel()
                normalCleanup = flight.abandonmentCleanup
                flight.abandonmentCleanup = null
                try {
                    handler(flight)
                } finally {
                    normalCleanup?.invoke()
                    normalCleanup = null
                }
            }
        }
        if (ignored) {
            onIgnored()
        }
        normalCleanup?.invoke()
    }

    private fun abortErroredFlight(token: Long) {
        val found = synchronized(lock) {
            val flight = activeFlight
            if (flight == null ||
                flight.token != token ||
                flight.lifecycleGeneration != lifecycleGeneration
            ) {
                null
            } else {
                activeFlight = null
                flight.deadline?.cancel()
                val cleanup = flight.pendingCleanup
                val abandonment = flight.abandonmentCleanup
                flight.pendingCompletion = null
                flight.pendingCleanup = null
                flight.abandonmentCleanup = null
                Triple(flight, cleanup, abandonment)
            }
        }
        found ?: return
        found.second?.invoke()
        found.third?.invoke()
        found.first.operation?.cancel()
        finishFlight(found.first, D2ResumeStatus.FAIL, D2ResumeReason.INDETERMINATE)
    }

    private fun acceptedFlight(token: Long): D2Flight? = synchronized(lock) {
        val flight = activeFlight
        if (flight == null ||
            flight.token != token ||
            flight.lifecycleGeneration != lifecycleGeneration
        ) {
            null
        } else {
            activeFlight = null
            flight.deadline?.cancel()
            flight.pendingCompletion = null
            flight.pendingCleanup?.invoke()
            flight.pendingCleanup = null
            flight.abandonmentCleanup?.invoke()
            flight.abandonmentCleanup = null
            flight
        }
    }

    private fun registerAbandonmentCleanup(token: Long, cleanup: () -> Unit): Boolean {
        val registered = synchronized(lock) {
            val flight = activeFlight
            if (flight == null ||
                flight.token != token ||
                flight.lifecycleGeneration != lifecycleGeneration
            ) {
                false
            } else {
                check(flight.abandonmentCleanup == null)
                flight.abandonmentCleanup = cleanup
                true
            }
        }
        if (!registered) cleanup()
        return registered
    }

    private fun wipeCompletedBytes(result: TaskResult<ByteArray>) {
        if (result is TaskResult.Completed) result.value.fill(0)
    }

    private fun emit() {
        val snapshot = state
        val targets = synchronized(lock) { listeners.toList() }
        targets.forEach { it(snapshot) }
    }
}

private object NoopD2Operation : ProbeControllerOperation {
    override fun timeout(): Boolean = false
    override fun cancel(): Boolean = false
}
