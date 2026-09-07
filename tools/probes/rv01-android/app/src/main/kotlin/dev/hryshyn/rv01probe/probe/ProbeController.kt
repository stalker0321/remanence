package dev.hryshyn.rv01probe.probe

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit operator-visible state for the pre-wipe RV-01 harness only. */
enum class ProbeControllerStatus {
    NOT_RUN,
    RUNNING,
    PASS,
    FAIL,
    BLOCKED,
}

enum class ProbeControllerPhase {
    NOT_RUN,
    CHECK_ENVIRONMENT,
    STORE_U,
    WAITING_FOR_SAF_EXPORT,
    EXPORT_P,
    READY_TO_VERIFY,
    RETRIEVE_U,
    IMPORT_P,
    VERIFY_BEFORE_WIPE,
    READY_FOR_CLEANUP,
    CLEANUP,
    TERMINAL,
}

enum class ProbeControllerReason {
    NONE,
    PLAY_SERVICES_UNAVAILABLE,
    LOCK_NOT_QUALIFIED,
    E2EE_UNAVAILABLE,
    BACKUP_NOT_ELIGIBLE,
    PROVIDER_UNAVAILABLE,
    RETRYABLE_UNAVAILABLE,
    INCOMPLETE,
    SAF_NOT_SELECTED,
    SAF_INVALID,
    FAIL_CLOSED,
    INDETERMINATE,
    CANCELLED,
    PROCESS_RECREATED_INCOMPLETE,
    PROVIDER_ENTRY_MAY_REMAIN,
    ILLEGAL_TRANSITION,
    CLEANUP_COMPLETE,
}

/** Slice-5 evidence result; no exception or provider payload crosses this enum. */
enum class ProbeEvidenceResult {
    PASS,
    FAIL_CLOSED,
    UNAVAILABLE,
    RETRYABLE_UNAVAILABLE,
    INCOMPLETE,
    INDETERMINATE,
}

data class ProbeEligibility(
    val capability: CapabilityStatus,
    val placement: CapabilityPlacement,
    val backupEligibility: BackupEligibility,
    val screenLock: ScreenLockState,
    val e2ee: E2eeState,
    val restorePath: RestorePath,
) {
    val cloudEligible: Boolean
        get() = capability == CapabilityStatus.AVAILABLE &&
            backupEligibility == BackupEligibility.ELIGIBLE &&
            e2ee == E2eeState.AVAILABLE &&
            screenLock in setOf(
                ScreenLockState.PIN,
                ScreenLockState.PATTERN,
                ScreenLockState.PASSWORD,
            )

    fun asDetection(): CapabilityDetection = CapabilityDetection(
        status = capability,
        placement = placement,
        backupEligibility = backupEligibility,
        screenLock = screenLock,
        e2ee = e2ee,
        restorePath = restorePath,
    )

    companion object {
        val UNKNOWN = ProbeEligibility(
            capability = CapabilityStatus.INDETERMINATE,
            placement = CapabilityPlacement.UNKNOWN,
            backupEligibility = BackupEligibility.UNKNOWN,
            screenLock = ScreenLockState.UNKNOWN,
            e2ee = E2eeState.UNKNOWN,
            restorePath = RestorePath.BLOCK_STORE_CLOUD,
        )
    }
}

/** Probe-owned operation handle. Implementations must settle only once. */
interface ProbeControllerOperation {
    fun timeout(): Boolean

    fun cancel(): Boolean
}

fun interface ProbeScheduledHandle {
    fun cancel()
}

fun interface ProbeScheduler {
    fun schedule(delayMs: Long, callback: () -> Unit): ProbeScheduledHandle
}

fun interface ProbeClock {
    fun nowMs(): Long
}

fun interface ProbeRandomSource {
    fun nextBytes(size: Int): ByteArray
}

interface ProbeEligibilityPort {
    fun detect(onSettled: (TaskResult<ProbeEligibility>) -> Unit): ProbeControllerOperation
}

interface ProbeUStorePort {
    fun storeU(
        key: String,
        value: ByteArray,
        onSettled: (TaskResult<Unit>) -> Unit,
    ): ProbeControllerOperation

    fun retrieveU(
        key: String,
        onSettled: (TaskResult<ByteArray>) -> Unit,
    ): ProbeControllerOperation

    fun deleteU(
        key: String,
        onSettled: (TaskResult<Unit>) -> Unit,
    ): ProbeControllerOperation
}

/** P-only port: U, canary, keys, and provider identity cannot enter it. */
interface ProbePTransportPort {
    fun exportP(
        opaqueP: ByteArray,
        expectedContext: ExpectedContext,
        onSettled: (TaskResult<Unit>) -> Unit,
    ): ProbeControllerOperation

    fun importP(
        expectedContext: ExpectedContext,
        onSettled: (TaskResult<ByteArray>) -> Unit,
    ): ProbeControllerOperation
}

data class ProbeEvidenceEvent(
    val phase: ProbeControllerPhase,
    val result: ProbeEvidenceResult,
    val durationMs: Long,
)

data class ProbeEvidenceSnapshot(
    val events: List<ProbeEvidenceEvent> = emptyList(),
    val legacyRecords: List<EvidenceRecord> = emptyList(),
    val uRetrievedBeforeWipe: Boolean = false,
    val pAuthenticatedBeforeWipe: Boolean = false,
    val canaryMatchedBeforeWipe: Boolean = false,
    val cleanupAttempted: Boolean = false,
    val cleanupCompleted: Boolean = false,
    val providerEntryMayRemain: Boolean = false,
) {
    companion object {
        const val MAX_EVENTS = 64
        const val MAX_LEGACY_RECORDS = 64
    }

    fun json(
        status: ProbeControllerStatus,
        phase: ProbeControllerPhase,
        reason: ProbeControllerReason,
        eligibility: ProbeEligibility,
    ): String = ProbeEvidenceJson.encode(
        status = status,
        phase = phase,
        reason = reason,
        eligibility = eligibility,
        evidence = this,
    )
}

data class ProbeControllerState(
    val status: ProbeControllerStatus = ProbeControllerStatus.NOT_RUN,
    val phase: ProbeControllerPhase = ProbeControllerPhase.NOT_RUN,
    val reason: ProbeControllerReason = ProbeControllerReason.NONE,
    val eligibility: ProbeEligibility = ProbeEligibility.UNKNOWN,
    val evidence: ProbeEvidenceSnapshot = ProbeEvidenceSnapshot(),
    val canExport: Boolean = false,
    val canVerify: Boolean = false,
    val canCleanup: Boolean = false,
    val canRetry: Boolean = false,
    val canCancel: Boolean = false,
) {
    val evidenceJson: String
        get() = evidence.json(status, phase, reason, eligibility)
}

/**
 * Enum-only evidence serializer for Slice 5. The string literals are schema
 * labels; all run-specific values are enums, booleans, bounded counts, or
 * durations. It never accepts a free-form diagnostic field.
 */
object ProbeEvidenceJson {
    private const val SCHEMA = "RV01-SLICE5-EVIDENCE-V1"

    fun encode(
        status: ProbeControllerStatus,
        phase: ProbeControllerPhase,
        reason: ProbeControllerReason,
        eligibility: ProbeEligibility,
        evidence: ProbeEvidenceSnapshot,
    ): String {
        val events = evidence.events.joinToString(prefix = "[", postfix = ",]", separator = ",") { event ->
            "{\"phase\":\"${event.phase.name}\",\"result\":\"${event.result.name}\",\"durationMs\":${event.durationMs}}"
        }.replace(",]", "]")
        return "{" +
            "\"schema\":\"$SCHEMA\"," +
            "\"status\":\"${status.name}\"," +
            "\"phase\":\"${phase.name}\"," +
            "\"reason\":\"${reason.name}\"," +
            "\"candidate\":\"${CandidateFamily.BLOCK_STORE.name}\"," +
            "\"capability\":\"${eligibility.capability.name}\"," +
            "\"placement\":\"${eligibility.placement.name}\"," +
            "\"backupEligibility\":\"${eligibility.backupEligibility.name}\"," +
            "\"screenLock\":\"${eligibility.screenLock.name}\"," +
            "\"e2ee\":\"${eligibility.e2ee.name}\"," +
            "\"restorePath\":\"${eligibility.restorePath.name}\"," +
            "\"evidenceClass\":\"${EvidenceClass.LOCAL_DRY_RUN_NOT_EVIDENCE.name}\"," +
            "\"uRetrievedBeforeWipe\":${evidence.uRetrievedBeforeWipe}," +
            "\"pAuthenticatedBeforeWipe\":${evidence.pAuthenticatedBeforeWipe}," +
            "\"canaryMatchedBeforeWipe\":${evidence.canaryMatchedBeforeWipe}," +
            "\"cleanupAttempted\":${evidence.cleanupAttempted}," +
            "\"cleanupCompleted\":${evidence.cleanupCompleted}," +
            "\"providerEntryMayRemain\":${evidence.providerEntryMayRemain}," +
            "\"operationCount\":${evidence.events.size}," +
            "\"events\":$events," +
            "\"legacyRecords\":${EvidenceJson.encode(evidence.legacyRecords)}" +
            "}"
    }
}

private class LiveProbeCase(
    val key: String,
    val canary: ByteArray,
    val unwrapMaterial: ByteArray,
    val expectedContext: ExpectedContext,
    val sidecar: ByteArray,
) : AutoCloseable {
    var providerEntryMayRemain = false

    override fun close() {
        canary.fill(0)
        unwrapMaterial.fill(0)
        sidecar.fill(0)
        expectedContext.wipeRunId()
    }
}

private enum class RetryAction {
    STORE,
    EXPORT,
    VERIFY,
    CLEANUP;

    fun phase(): ProbeControllerPhase = when (this) {
        STORE -> ProbeControllerPhase.STORE_U
        EXPORT -> ProbeControllerPhase.EXPORT_P
        VERIFY -> ProbeControllerPhase.RETRIEVE_U
        CLEANUP -> ProbeControllerPhase.CLEANUP
    }
}

private data class ReservedCaseAction(
    val case: LiveProbeCase,
    val token: Long,
)

private class ActiveFlight(
    val token: Long,
    val phase: ProbeControllerPhase,
    val startedMs: Long,
    val lifecycleGeneration: Long,
) {
    var operation: ProbeControllerOperation? = null
    var deadline: ProbeScheduledHandle? = null
    var operationInstalled = false
    var pendingCompletion: (() -> Unit)? = null
    var pendingCleanup: (() -> Unit)? = null
    var abandonmentCleanup: (() -> Unit)? = null
}

private class WipeOnce(private val bytes: ByteArray) {
    private val wiped = AtomicBoolean(false)

    fun wipe() {
        if (wiped.compareAndSet(false, true)) bytes.fill(0)
    }
}

private data class FlightTeardown(
    val flight: ActiveFlight?,
    val pendingCleanup: (() -> Unit)?,
    val abandonmentCleanup: (() -> Unit)?,
)

/**
 * Single-flight, pre-wipe controller. It owns sensitive case material only in
 * memory, ignores late callbacks by token, and exposes no capability GO state.
 */
class ProbeController(
    private val eligibilityPort: ProbeEligibilityPort,
    private val uStore: ProbeUStorePort,
    private val pTransport: ProbePTransportPort,
    private val scheduler: ProbeScheduler,
    private val clock: ProbeClock = ProbeClock { System.nanoTime() / 1_000_000L },
    private val random: ProbeRandomSource = ProbeRandomSource {
        ByteArray(it).also(SecureRandom()::nextBytes)
    },
) : AutoCloseable {
    companion object {
        const val TASK_TIMEOUT_MS = 10_000L
    }

    private val lock = Any()
    private val listeners = linkedSetOf<(ProbeControllerState) -> Unit>()
    private var currentState = ProbeControllerState()
    private var liveCase: LiveProbeCase? = null
    private var activeFlight: ActiveFlight? = null
    private var nextToken = 1L
    private var lifecycleGeneration = 0L
    private var retryAction: RetryAction? = null
    private var postCleanupStatus = ProbeControllerStatus.FAIL

    val state: ProbeControllerState
        get() = synchronized(lock) { currentState }

    fun addListener(listener: (ProbeControllerState) -> Unit): () -> Unit {
        synchronized(lock) {
            listeners += listener
        }
        listener(state)
        return { synchronized(lock) { listeners -= listener } }
    }

    fun begin(): Boolean {
        val token: Long
        synchronized(lock) {
            if (activeFlight != null ||
                currentState.phase !in setOf(
                    ProbeControllerPhase.NOT_RUN,
                    ProbeControllerPhase.TERMINAL,
                )
            ) {
                return false
            }
            liveCase?.close()
            liveCase = null
            retryAction = null
            postCleanupStatus = ProbeControllerStatus.FAIL
            lifecycleGeneration += 1
            token = nextToken++
            activeFlight = ActiveFlight(
                token,
                ProbeControllerPhase.CHECK_ENVIRONMENT,
                clock.nowMs(),
                lifecycleGeneration,
            )
            currentState = ProbeControllerState(
                status = ProbeControllerStatus.RUNNING,
                phase = ProbeControllerPhase.CHECK_ENVIRONMENT,
                eligibility = ProbeEligibility.UNKNOWN,
                evidence = ProbeEvidenceSnapshot(),
                canCancel = true,
            )
        }
        emit()
        launchEligibility(token)
        return true
    }

    fun exportSidecar(): Boolean {
        val reserved = reserveCaseAction(
            allowedPhase = ProbeControllerPhase.WAITING_FOR_SAF_EXPORT,
            phase = ProbeControllerPhase.EXPORT_P,
        ) ?: return false
        launchExport(reserved.case, reserved.token)
        return true
    }

    fun verifyBeforeWipe(): Boolean {
        val reserved = reserveCaseAction(
            allowedPhase = ProbeControllerPhase.READY_TO_VERIFY,
            phase = ProbeControllerPhase.RETRIEVE_U,
        ) ?: return false
        launchRetrieve(reserved.case, reserved.token)
        return true
    }

    fun cleanup(): Boolean {
        val reserved = reserveCaseAction(
            allowedPhase = null,
            phase = ProbeControllerPhase.CLEANUP,
            markCleanupAttempted = true,
        ) ?: return false
        launchCleanup(reserved.case, reserved.token)
        return true
    }

    /** Repeats only the same-key/same-sidecar operation permitted by design. */
    fun retry(): Boolean {
        val reserved = synchronized(lock) {
            if (activeFlight != null || !currentState.canRetry) return false
            val action = retryAction ?: return false
            val case = liveCase ?: return false
            val token = reserveFlightLocked(action.phase())
            Triple(action, case, token)
        }
        emit()
        return when (reserved.first) {
            RetryAction.STORE -> launchStore(reserved.second, reserved.third).let { true }
            RetryAction.EXPORT -> launchExport(reserved.second, reserved.third).let { true }
            RetryAction.VERIFY -> launchRetrieve(reserved.second, reserved.third).let { true }
            RetryAction.CLEANUP -> launchCleanup(reserved.second, reserved.third).let { true }
        }
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
            FlightTeardown(found, cleanup, abandonment)
        }
        teardown.pendingCleanup?.invoke()
        teardown.abandonmentCleanup?.invoke()
        checkNotNull(teardown.flight).operation?.cancel()
        finishFlight(
            checkNotNull(teardown.flight),
            TaskResult.RetryableUnavailable,
            ProbeControllerReason.CANCELLED,
        )
        return true
    }

    /** Process death is not resumed from in-app state; all sensitive material is wiped. */
    fun processRecreated() {
        val (teardown, providerEntryMayRemain) = synchronized(lock) {
            val found = activeFlight
            val providerEntryMayRemain = liveCase?.providerEntryMayRemain == true ||
                (found?.phase == ProbeControllerPhase.STORE_U && liveCase != null)
            lifecycleGeneration += 1
            activeFlight = null
            found?.deadline?.cancel()
            val cleanup = found?.pendingCleanup
            val abandonment = found?.abandonmentCleanup
            found?.pendingCompletion = null
            found?.pendingCleanup = null
            found?.abandonmentCleanup = null
            liveCase?.close()
            liveCase = null
            retryAction = null
            FlightTeardown(found, cleanup, abandonment) to providerEntryMayRemain
        }
        teardown.pendingCleanup?.invoke()
        teardown.abandonmentCleanup?.invoke()
        teardown.flight?.operation?.cancel()
        synchronized(lock) {
            currentState = currentState.copy(
                status = ProbeControllerStatus.BLOCKED,
                phase = ProbeControllerPhase.NOT_RUN,
                reason = if (providerEntryMayRemain) {
                    ProbeControllerReason.PROVIDER_ENTRY_MAY_REMAIN
                } else {
                    ProbeControllerReason.PROCESS_RECREATED_INCOMPLETE
                },
                canExport = false,
                canVerify = false,
                canCleanup = false,
                canRetry = false,
                canCancel = false,
                evidence = currentState.evidence.copy(
                    providerEntryMayRemain = providerEntryMayRemain,
                ),
            )
        }
        emit()
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
            liveCase?.close()
            liveCase = null
            retryAction = null
            FlightTeardown(found, cleanup, abandonment)
        }
        teardown.pendingCleanup?.invoke()
        teardown.abandonmentCleanup?.invoke()
        teardown.flight?.operation?.cancel()
    }

    private fun launchEligibility(token: Long) {
        invokeAndInstall(token) {
            eligibilityPort.detect { result ->
                settleOrDefer(token) { flight -> handleEligibility(flight, result) }
            }
        }
    }

    private fun handleEligibility(flight: ActiveFlight, result: TaskResult<ProbeEligibility>) {
        val eligibility = (result as? TaskResult.Completed)?.value
        if (eligibility != null) {
            synchronized(lock) {
                currentState = currentState.copy(eligibility = eligibility)
            }
        }
        val reason = when {
            result !is TaskResult.Completed -> reasonFor(result)
            eligibility == null -> ProbeControllerReason.INDETERMINATE
            eligibility.capability != CapabilityStatus.AVAILABLE &&
                eligibility.e2ee == E2eeState.UNAVAILABLE ->
                ProbeControllerReason.E2EE_UNAVAILABLE
            eligibility.capability == CapabilityStatus.INDETERMINATE &&
                eligibility.screenLock == ScreenLockState.UNKNOWN ->
                ProbeControllerReason.LOCK_NOT_QUALIFIED
            eligibility.capability != CapabilityStatus.AVAILABLE &&
                eligibility.screenLock == ScreenLockState.ABSENT ->
                ProbeControllerReason.LOCK_NOT_QUALIFIED
            eligibility.capability != CapabilityStatus.AVAILABLE ->
                ProbeControllerReason.PLAY_SERVICES_UNAVAILABLE
            eligibility.screenLock !in setOf(
                ScreenLockState.PIN,
                ScreenLockState.PATTERN,
                ScreenLockState.PASSWORD,
            ) -> ProbeControllerReason.LOCK_NOT_QUALIFIED
            eligibility.e2ee != E2eeState.AVAILABLE -> ProbeControllerReason.E2EE_UNAVAILABLE
            eligibility.backupEligibility != BackupEligibility.ELIGIBLE ->
                ProbeControllerReason.BACKUP_NOT_ELIGIBLE
            else -> ProbeControllerReason.INDETERMINATE
        }
        val eventResult = if (eligibility?.cloudEligible == true) {
            ProbeEvidenceResult.PASS
        } else if (result is TaskResult.Completed) {
            ProbeEvidenceResult.INCOMPLETE
        } else {
            evidenceResult(result)
        }
        addEvent(flight, eventResult, ProbeTuple.P1_CAPABILITY)
        if (!isLifecycleGenerationCurrent(flight)) return
        if (eligibility?.cloudEligible != true) {
            setBlockedOrFailed(flight, result, reason, cleanup = false, blockedOverride = true)
            return
        }

        val case = createCaseOrNull()
        if (case == null) {
            setTerminal(flight, ProbeEvidenceResult.FAIL_CLOSED, ProbeControllerReason.FAIL_CLOSED)
            return
        }
        synchronized(lock) {
            liveCase = case
        }
        launchStore(case)
    }

    private fun createCaseOrNull(): LiveProbeCase? {
        val canary = random.nextBytes(ProbeSidecar.CANARY_BYTES)
        val unwrapMaterial = random.nextBytes(ProbeSidecar.KEY_BYTES)
        val runId = random.nextBytes(ExpectedContext.RUN_ID_BYTES)
        val rawKey = random.nextBytes(16)
        val key = try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(rawKey)
        } finally {
            rawKey.fill(0)
        }
        if (canary.size != ProbeSidecar.CANARY_BYTES ||
            unwrapMaterial.size != ProbeSidecar.KEY_BYTES ||
            runId.size != ExpectedContext.RUN_ID_BYTES ||
            !ProbeKey.isValid(key)
        ) {
            canary.fill(0)
            unwrapMaterial.fill(0)
            runId.fill(0)
            return null
        }
        val context = try {
            ExpectedContext(
                accountBindingClass = AccountBindingClass.A,
                runId = runId,
                generation = ContextGeneration.G1,
                targetRole = ContextTargetRole.D1_SOURCE,
            )
        } catch (_: RuntimeException) {
            canary.fill(0)
            unwrapMaterial.fill(0)
            runId.fill(0)
            return null
        }
        val sidecar = ProbeSidecar.seal(canary, unwrapMaterial, context)
        if (sidecar == null) {
            canary.fill(0)
            unwrapMaterial.fill(0)
            context.wipeRunId()
            return null
        }
        return LiveProbeCase(key, canary, unwrapMaterial, context, sidecar)
    }

    private fun launchStore(case: LiveProbeCase, reservedToken: Long? = null) {
        retryAction = RetryAction.STORE
        val token = reservedToken ?: startFlight(ProbeControllerPhase.STORE_U)
        case.providerEntryMayRemain = true
        invokeAndInstall(token) {
            uStore.storeU(case.key, case.unwrapMaterial.copyOf()) { result ->
                settleOrDefer(token) { flight -> handleStore(flight, result) }
            }
        }
    }

    private fun handleStore(flight: ActiveFlight, result: TaskResult<Unit>) {
        if (result is TaskResult.Completed) {
            retryAction = null
            addEvent(flight, ProbeEvidenceResult.PASS, ProbeTuple.P2_WRAP)
            if (!isLifecycleGenerationCurrent(flight)) return
            synchronized(lock) {
                currentState = currentState.copy(
                    status = ProbeControllerStatus.BLOCKED,
                    phase = ProbeControllerPhase.WAITING_FOR_SAF_EXPORT,
                    reason = ProbeControllerReason.SAF_NOT_SELECTED,
                    canExport = true,
                    canVerify = false,
                    canCleanup = true,
                    canRetry = false,
                    canCancel = false,
                )
            }
            emit()
        } else {
            addEvent(flight, evidenceResult(result), ProbeTuple.P2_WRAP)
            if (!isLifecycleGenerationCurrent(flight)) return
            setBlockedOrFailed(flight, result, reasonFor(result), cleanup = true)
        }
    }

    private fun launchExport(case: LiveProbeCase, reservedToken: Long? = null) {
        retryAction = RetryAction.EXPORT
        val token = reservedToken ?: startFlight(ProbeControllerPhase.EXPORT_P)
        invokeAndInstall(token) {
            pTransport.exportP(case.sidecar.copyOf(), case.expectedContext) { result ->
                settleOrDefer(token) { flight -> handleExport(flight, result) }
            }
        }
    }

    private fun handleExport(flight: ActiveFlight, result: TaskResult<Unit>) {
        if (result is TaskResult.Completed) {
            retryAction = null
            addEvent(flight, ProbeEvidenceResult.PASS, null)
            if (!isLifecycleGenerationCurrent(flight)) return
            synchronized(lock) {
                currentState = currentState.copy(
                    status = ProbeControllerStatus.BLOCKED,
                    phase = ProbeControllerPhase.READY_TO_VERIFY,
                    reason = ProbeControllerReason.NONE,
                    canExport = false,
                    canVerify = true,
                    canCleanup = true,
                    canRetry = false,
                    canCancel = false,
                )
            }
            emit()
        } else {
            addEvent(flight, evidenceResult(result), null)
            if (!isLifecycleGenerationCurrent(flight)) return
            setBlockedOrFailed(flight, result, reasonFor(result), cleanup = true)
        }
    }

    private fun launchRetrieve(case: LiveProbeCase, reservedToken: Long? = null) {
        retryAction = RetryAction.VERIFY
        val token = reservedToken ?: startFlight(ProbeControllerPhase.RETRIEVE_U)
        invokeAndInstall(token) {
            uStore.retrieveU(case.key) { result ->
                settleOrDefer(token, onIgnored = { wipeCompletedBytes(result) }) { flight ->
                    handleRetrieve(flight, result, case)
                }
            }
        }
    }

    private fun handleRetrieve(
        flight: ActiveFlight,
        result: TaskResult<ByteArray>,
        case: LiveProbeCase,
    ) {
        val retrieved = (result as? TaskResult.Completed)?.value
        if (retrieved == null) {
            addEvent(flight, evidenceResult(result), null)
            if (!isLifecycleGenerationCurrent(flight)) return
            setBlockedOrFailed(flight, result, reasonFor(result), cleanup = true)
            return
        }
        if (retrieved.size != ProbeSidecar.KEY_BYTES) {
            retrieved.fill(0)
            addEvent(flight, ProbeEvidenceResult.FAIL_CLOSED, null)
            if (!isLifecycleGenerationCurrent(flight)) return
            setBlockedOrFailed(
                flight,
                TaskResult.Indeterminate,
                ProbeControllerReason.FAIL_CLOSED,
                cleanup = true,
                retry = false,
            )
            return
        }
        synchronized(lock) {
            currentState = currentState.copy(
                status = ProbeControllerStatus.RUNNING,
                phase = ProbeControllerPhase.IMPORT_P,
                reason = ProbeControllerReason.NONE,
                canVerify = false,
            )
        }
        emit()
        if (!isLifecycleGenerationCurrent(flight)) {
            retrieved.fill(0)
            return
        }
        launchImport(case, retrieved)
    }

    private fun launchImport(case: LiveProbeCase, retrievedU: ByteArray) {
        val token = startFlight(ProbeControllerPhase.IMPORT_P)
        val retrievedCleanup = WipeOnce(retrievedU)
        if (!registerAbandonmentCleanup(token, retrievedCleanup::wipe)) return
        invokeAndInstall(token) {
            pTransport.importP(case.expectedContext) { pResult ->
                settleOrDefer(token, onIgnored = {
                    retrievedCleanup.wipe()
                    wipeCompletedBytes(pResult)
                }) { flight ->
                    handleImported(flight, pResult, case, retrievedU, retrievedCleanup)
                }
            }
        }
        if (!isGenerationCurrent(token)) {
            retrievedCleanup.wipe()
        }
    }

    private fun handleImported(
        flight: ActiveFlight,
        result: TaskResult<ByteArray>,
        case: LiveProbeCase,
        retrievedU: ByteArray,
        retrievedCleanup: WipeOnce,
    ) {
        val sidecar = (result as? TaskResult.Completed)?.value
        if (sidecar == null) {
            retrievedCleanup.wipe()
            addEvent(flight, evidenceResult(result), null)
            if (!isLifecycleGenerationCurrent(flight)) return
            setBlockedOrFailed(flight, result, reasonFor(result), cleanup = true)
            return
        }
        synchronized(lock) {
            currentState = currentState.copy(
                status = ProbeControllerStatus.RUNNING,
                phase = ProbeControllerPhase.VERIFY_BEFORE_WIPE,
            )
        }
        emit()
        if (!isLifecycleGenerationCurrent(flight)) {
            sidecar.fill(0)
            retrievedCleanup.wipe()
            return
        }
        var matched = false
        var authenticated = false
        try {
            val opened = ProbeSidecar.open(sidecar, retrievedU, case.expectedContext)
            if (opened != null) {
                authenticated = opened.size == ProbeSidecar.CANARY_BYTES
                matched = authenticated && opened.contentEquals(case.canary)
                opened.fill(0)
            }
        } catch (_: RuntimeException) {
            matched = false
        } finally {
            sidecar.fill(0)
            retrievedCleanup.wipe()
        }
        synchronized(lock) {
            currentState = currentState.copy(
                evidence = currentState.evidence.copy(
                    uRetrievedBeforeWipe = true,
                    pAuthenticatedBeforeWipe = authenticated,
                    canaryMatchedBeforeWipe = matched,
                ),
            )
        }
        if (matched) {
            retryAction = null
            addEvent(flight, ProbeEvidenceResult.PASS, ProbeTuple.P3_LOCAL_UNWRAP)
            synchronized(lock) {
                currentState = currentState.copy(
                    status = ProbeControllerStatus.PASS,
                    phase = ProbeControllerPhase.READY_FOR_CLEANUP,
                    reason = ProbeControllerReason.NONE,
                    canVerify = false,
                    canCleanup = true,
                    canRetry = false,
                    canCancel = false,
                )
                postCleanupStatus = ProbeControllerStatus.PASS
            }
            emit()
        } else {
            addEvent(flight, ProbeEvidenceResult.FAIL_CLOSED, ProbeTuple.P3_LOCAL_UNWRAP)
            synchronized(lock) {
                currentState = currentState.copy(
                    status = ProbeControllerStatus.FAIL,
                    phase = ProbeControllerPhase.READY_FOR_CLEANUP,
                    reason = ProbeControllerReason.SAF_INVALID,
                    canVerify = false,
                    canCleanup = true,
                    canRetry = false,
                    canCancel = false,
                )
                postCleanupStatus = ProbeControllerStatus.FAIL
            }
            retryAction = null
            emit()
        }
    }

    private fun launchCleanup(case: LiveProbeCase, reservedToken: Long? = null) {
        retryAction = RetryAction.CLEANUP
        val token = reservedToken ?: startFlight(ProbeControllerPhase.CLEANUP)
        invokeAndInstall(token) {
            uStore.deleteU(case.key) { result ->
                settleOrDefer(token) { flight -> handleCleanup(flight, result) }
            }
        }
    }

    private fun handleCleanup(flight: ActiveFlight, result: TaskResult<Unit>) {
        addEvent(flight, evidenceResult(result), null)
        if (!isLifecycleGenerationCurrent(flight)) return
        if (result is TaskResult.Completed) {
            retryAction = null
            synchronized(lock) {
                liveCase?.providerEntryMayRemain = false
                liveCase?.close()
                liveCase = null
                currentState = currentState.copy(
                    status = postCleanupStatus,
                    phase = ProbeControllerPhase.TERMINAL,
                    reason = ProbeControllerReason.CLEANUP_COMPLETE,
                    canExport = false,
                    canVerify = false,
                    canCleanup = false,
                    canRetry = false,
                    canCancel = false,
                    evidence = currentState.evidence.copy(cleanupCompleted = true),
                )
            }
            emit()
        } else {
            setBlockedOrFailed(flight, result, reasonFor(result), cleanup = true)
        }
    }

    private fun startFlight(phase: ProbeControllerPhase): Long {
        val token: Long
        synchronized(lock) {
            token = reserveFlightLocked(phase)
        }
        emit()
        return token
    }

    private fun reserveFlightLocked(phase: ProbeControllerPhase): Long {
        check(activeFlight == null)
        val token = nextToken++
        activeFlight = ActiveFlight(token, phase, clock.nowMs(), lifecycleGeneration)
        currentState = currentState.copy(
            status = ProbeControllerStatus.RUNNING,
            phase = phase,
            reason = ProbeControllerReason.NONE,
            canExport = false,
            canVerify = false,
            canCleanup = currentState.canCleanup,
            canRetry = false,
            canCancel = true,
        )
        return token
    }

    private fun reserveCaseAction(
        allowedPhase: ProbeControllerPhase?,
        phase: ProbeControllerPhase,
        markCleanupAttempted: Boolean = false,
    ): ReservedCaseAction? {
        val reserved = synchronized(lock) {
            if (activeFlight != null ||
                (allowedPhase != null && currentState.phase != allowedPhase) ||
                (allowedPhase == null && !currentState.canCleanup)
            ) {
                return@synchronized null
            }
            val case = liveCase ?: return@synchronized null
            val token = reserveFlightLocked(phase)
            if (markCleanupAttempted) {
                currentState = currentState.copy(
                    evidence = currentState.evidence.copy(cleanupAttempted = true),
                )
            }
            ReservedCaseAction(case, token)
        }
        if (reserved != null) emit()
        return reserved
    }

    private fun isGenerationCurrent(token: Long): Boolean = synchronized(lock) {
        activeFlight?.token == token && activeFlight?.lifecycleGeneration == lifecycleGeneration
    }

    private fun invokeAndInstall(
        token: Long,
        invocation: () -> ProbeControllerOperation,
    ) {
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
        } catch (_: RuntimeException) {
            synchronized(lock) {
                acceptedFlight(token)?.let {
                    finishFlight(it, TaskResult.Indeterminate, ProbeControllerReason.INDETERMINATE)
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
            flight.deadline = scheduler.schedule(TASK_TIMEOUT_MS) {
                timeoutFlight(token)
            }
        }
    }

    /**
     * A provider may invoke its callback synchronously from the launch call.
     * Keep the flight reserved until the operation handle has been returned;
     * this also preserves sensitive cleanup across a reentrant cancellation.
     */
    private fun settleOrDefer(
        token: Long,
        onIgnored: () -> Unit = {},
        handler: (ActiveFlight) -> Unit,
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

    private fun timeoutFlight(token: Long) {
        val teardown = synchronized(lock) {
            val found = activeFlight
            if (found == null || found.token != token) return
            activeFlight = null
            found.deadline?.cancel()
            val cleanup = found.pendingCleanup
            val abandonment = found.abandonmentCleanup
            found.pendingCompletion = null
            found.pendingCleanup = null
            found.abandonmentCleanup = null
            FlightTeardown(found, cleanup, abandonment)
        }
        teardown.pendingCleanup?.invoke()
        teardown.abandonmentCleanup?.invoke()
        val flight = checkNotNull(teardown.flight)
        flight.operation?.timeout()
        finishFlight(
            flight,
            TaskResult.RetryableUnavailable,
            ProbeControllerReason.RETRYABLE_UNAVAILABLE,
        )
    }

    private fun acceptedFlight(token: Long): ActiveFlight? = synchronized(lock) {
        val flight = activeFlight
        if (flight == null ||
            flight.token != token ||
            flight.lifecycleGeneration != lifecycleGeneration
        ) null else {
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

    private fun finishFlight(
        flight: ActiveFlight,
        result: TaskResult<*>,
        reason: ProbeControllerReason,
    ) {
        val duration = (clock.nowMs() - flight.startedMs).coerceAtLeast(0L)
        addEvent(flight, evidenceResult(result), null, duration)
        if (!isLifecycleGenerationCurrent(flight)) return
        if (flight.phase == ProbeControllerPhase.CLEANUP) {
            setBlockedOrFailed(flight, result, reason, cleanup = true)
        } else {
            setBlockedOrFailed(flight, result, reason, cleanup = liveCase != null)
        }
    }

    private fun setBlockedOrFailed(
        flight: ActiveFlight,
        result: TaskResult<*>,
        reason: ProbeControllerReason,
        cleanup: Boolean,
        retry: Boolean = true,
        blockedOverride: Boolean = false,
    ) {
        val blocked = blockedOverride ||
            result is TaskResult.Unavailable ||
            result is TaskResult.RetryableUnavailable ||
            result is TaskResult.Incomplete
        val status = if (blocked) ProbeControllerStatus.BLOCKED else ProbeControllerStatus.FAIL
        synchronized(lock) {
            currentState = currentState.copy(
                status = status,
                phase = flight.phase,
                reason = reason,
                canExport = flight.phase == ProbeControllerPhase.WAITING_FOR_SAF_EXPORT,
                canVerify = flight.phase == ProbeControllerPhase.READY_TO_VERIFY,
                canCleanup = cleanup && liveCase != null,
                canRetry = retry && liveCase != null,
                canCancel = false,
            )
            if (flight.phase != ProbeControllerPhase.CLEANUP) {
                postCleanupStatus = status
            }
            retryAction = if (retry && liveCase != null) retryAction else null
        }
        emit()
    }

    private fun setTerminal(
        flight: ActiveFlight,
        result: ProbeEvidenceResult,
        reason: ProbeControllerReason,
    ) {
        addEvent(flight, result, null)
        if (!isLifecycleGenerationCurrent(flight)) return
        synchronized(lock) {
            currentState = currentState.copy(
                status = ProbeControllerStatus.FAIL,
                phase = ProbeControllerPhase.TERMINAL,
                reason = reason,
                canExport = false,
                canVerify = false,
                canCleanup = false,
                canRetry = false,
                canCancel = false,
            )
        }
        emit()
    }

    private fun addEvent(
        flight: ActiveFlight,
        result: ProbeEvidenceResult,
        tuple: ProbeTuple?,
        durationOverride: Long? = null,
    ) {
        val duration = durationOverride ?: (clock.nowMs() - flight.startedMs).coerceAtLeast(0L)
        val metadata = synchronized(lock) { currentState.eligibility }
        val legacyResult = when (result) {
            ProbeEvidenceResult.PASS -> ProbeResult.PASS
            ProbeEvidenceResult.FAIL_CLOSED -> ProbeResult.FAIL_CLOSED
            ProbeEvidenceResult.UNAVAILABLE,
            ProbeEvidenceResult.RETRYABLE_UNAVAILABLE,
            -> ProbeResult.UNAVAILABLE
            ProbeEvidenceResult.INCOMPLETE -> ProbeResult.NOT_APPLICABLE
            ProbeEvidenceResult.INDETERMINATE -> ProbeResult.INDETERMINATE
        }
        synchronized(lock) {
            val boundedEvents = (currentState.evidence.events +
                ProbeEvidenceEvent(flight.phase, result, duration)).takeLast(
                ProbeEvidenceSnapshot.MAX_EVENTS,
            )
            val newLegacyRecords = if (tuple == null) {
                currentState.evidence.legacyRecords
            } else {
                currentState.evidence.legacyRecords + EvidenceRecord(
                    tuple = tuple,
                    candidate = CandidateFamily.BLOCK_STORE,
                    result = legacyResult,
                    capability = metadata.capability,
                    placement = metadata.placement,
                    backupEligibility = metadata.backupEligibility,
                    screenLock = metadata.screenLock,
                    e2ee = metadata.e2ee,
                    restorePath = metadata.restorePath,
                )
            }
            currentState = currentState.copy(
                evidence = currentState.evidence.copy(
                    events = boundedEvents,
                    legacyRecords = newLegacyRecords.takeLast(
                        ProbeEvidenceSnapshot.MAX_LEGACY_RECORDS,
                    ),
                ),
            )
        }
        emit()
    }

    private fun isLifecycleGenerationCurrent(flight: ActiveFlight): Boolean = synchronized(lock) {
        lifecycleGeneration == flight.lifecycleGeneration
    }

    private fun evidenceResult(result: TaskResult<*>): ProbeEvidenceResult = when (result) {
        is TaskResult.Completed -> ProbeEvidenceResult.PASS
        TaskResult.Unavailable -> ProbeEvidenceResult.UNAVAILABLE
        TaskResult.RetryableUnavailable -> ProbeEvidenceResult.RETRYABLE_UNAVAILABLE
        TaskResult.Incomplete -> ProbeEvidenceResult.INCOMPLETE
        TaskResult.Indeterminate -> ProbeEvidenceResult.INDETERMINATE
    }

    private fun reasonFor(result: TaskResult<*>): ProbeControllerReason = when (result) {
        is TaskResult.Completed -> ProbeControllerReason.NONE
        TaskResult.Unavailable -> ProbeControllerReason.PROVIDER_UNAVAILABLE
        TaskResult.RetryableUnavailable -> ProbeControllerReason.RETRYABLE_UNAVAILABLE
        TaskResult.Incomplete -> ProbeControllerReason.INCOMPLETE
        TaskResult.Indeterminate -> ProbeControllerReason.INDETERMINATE
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
