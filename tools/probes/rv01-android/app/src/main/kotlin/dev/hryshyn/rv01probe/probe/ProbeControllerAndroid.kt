package dev.hryshyn.rv01probe.probe

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real Block Store eligibility gate; no client is created before Play passes. */
class AndroidProbeEligibilityPort(
    context: Context,
) : ProbeEligibilityPort {
    private val appContext = context.applicationContext
    private val clientFactory = GoogleBlockStoreClientFactory(appContext)
    private val lockProvider = AndroidQualifyingLockStateProvider(appContext)

    override fun detect(onSettled: (TaskResult<ProbeEligibility>) -> Unit): ProbeControllerOperation {
        val playAvailable = try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
                ConnectionResult.SUCCESS
        } catch (_: RuntimeException) {
            false
        }
        if (!playAvailable) {
            onSettled(TaskResult.Completed(unknownEligibility(CapabilityStatus.UNAVAILABLE)))
            return NoopProbeControllerOperation
        }

        val lockState = try {
            lockProvider.current()
        } catch (_: RuntimeException) {
            BlockStoreLockState.UNKNOWN
        }
        if (lockState != BlockStoreLockState.QUALIFIED) {
            onSettled(
                TaskResult.Completed(
                    unknownEligibility(
                        capability = if (lockState == BlockStoreLockState.INSECURE) {
                            CapabilityStatus.UNAVAILABLE
                        } else {
                            CapabilityStatus.INDETERMINATE
                        },
                        screenLock = if (lockState == BlockStoreLockState.INSECURE) {
                            ScreenLockState.ABSENT
                        } else {
                            ScreenLockState.UNKNOWN
                        },
                    ),
                ),
            )
            return NoopProbeControllerOperation
        }

        val client = try {
            clientFactory.create()
        } catch (_: RuntimeException) {
            onSettled(TaskResult.Indeterminate)
            return NoopProbeControllerOperation
        }
        val task = try {
            client.isEndToEndEncryptionAvailable()
        } catch (_: RuntimeException) {
            onSettled(TaskResult.Indeterminate)
            return NoopProbeControllerOperation
        }
        val owner = BlockStoreTaskOwner(task)
        try {
            owner.start { result ->
                val mapped = when (result.outcome) {
                    BlockStoreOutcome.COMPLETED -> if (result.value == true) {
                        TaskResult.Completed(
                            ProbeEligibility(
                                capability = CapabilityStatus.AVAILABLE,
                                placement = CapabilityPlacement.SYNCED_PROVIDER,
                                // E2EE availability does not independently
                                // prove cloud-backup eligibility.
                                backupEligibility = BackupEligibility.UNKNOWN,
                                // The current Android lock probe deliberately
                                // cannot identify PIN/pattern/password.
                                screenLock = ScreenLockState.UNKNOWN,
                                e2ee = E2eeState.AVAILABLE,
                                restorePath = RestorePath.BLOCK_STORE_CLOUD,
                            ),
                        )
                    } else {
                        TaskResult.Completed(
                            unknownEligibility(
                                capability = CapabilityStatus.UNAVAILABLE,
                                e2ee = E2eeState.UNAVAILABLE,
                            ),
                        )
                    }
                    else -> result.toEligibilityTaskResult()
                }
                onSettled(mapped)
            }
        } catch (_: RuntimeException) {
            onSettled(TaskResult.Indeterminate)
            return NoopProbeControllerOperation
        }
        return BlockStoreTaskControllerOperation(owner)
    }

    private fun unknownEligibility(
        capability: CapabilityStatus,
        screenLock: ScreenLockState = ScreenLockState.UNKNOWN,
        e2ee: E2eeState = E2eeState.UNKNOWN,
    ) = ProbeEligibility(
        capability = capability,
        placement = CapabilityPlacement.SYNCED_PROVIDER,
        backupEligibility = BackupEligibility.UNKNOWN,
        screenLock = screenLock,
        e2ee = e2ee,
        restorePath = RestorePath.BLOCK_STORE_CLOUD,
    )
}

/** Adapts the reviewed exact-key Block Store bridge to the controller port. */
class AndroidProbeUStorePort(context: Context) : ProbeUStorePort {
    private val delegate = GoogleBlockStoreUStore(context.applicationContext)

    override fun storeU(
        key: String,
        value: ByteArray,
        onSettled: (TaskResult<Unit>) -> Unit,
    ): ProbeControllerOperation {
        val owned = value.copyOf()
        value.fill(0)
        return try {
            val operation = delegate.storeU(key, owned) { onSettled(it.toTaskResult()) }
            BlockStoreControllerOperation(operation)
        } catch (_: RuntimeException) {
            onSettled(TaskResult.Indeterminate)
            NoopProbeControllerOperation
        } finally {
            owned.fill(0)
        }
    }

    override fun retrieveU(
        key: String,
        onSettled: (TaskResult<ByteArray>) -> Unit,
    ): ProbeControllerOperation = try {
        BlockStoreControllerOperation(
            delegate.retrieveU(key) { onSettled(it.toTaskResult()) },
        )
    } catch (_: RuntimeException) {
        onSettled(TaskResult.Indeterminate)
        NoopProbeControllerOperation
    }

    override fun deleteU(
        key: String,
        onSettled: (TaskResult<Unit>) -> Unit,
    ): ProbeControllerOperation = try {
        BlockStoreControllerOperation(
            delegate.deleteU(key) { onSettled(it.toTaskResult()) },
        )
    } catch (_: RuntimeException) {
        onSettled(TaskResult.Indeterminate)
        NoopProbeControllerOperation
    }
}

private class BlockStoreControllerOperation<T>(
    private val delegate: BlockStoreOperation<T>,
) : ProbeControllerOperation {
    override fun timeout(): Boolean = delegate.timeout()

    override fun cancel(): Boolean = delegate.cancel()
}

private class BlockStoreTaskControllerOperation<T>(
    private val delegate: BlockStoreTaskOwner<T>,
) : ProbeControllerOperation {
    override fun timeout(): Boolean = delegate.timeout()

    override fun cancel(): Boolean = delegate.cancel()
}

private object NoopProbeControllerOperation : ProbeControllerOperation {
    override fun timeout(): Boolean = false

    override fun cancel(): Boolean = false
}

private fun <T> BlockStoreResult<T>.toTaskResult(): TaskResult<T> = when (outcome) {
    BlockStoreOutcome.COMPLETED -> TaskResult.Completed(checkNotNull(value))
    BlockStoreOutcome.UNAVAILABLE -> TaskResult.Unavailable
    BlockStoreOutcome.RETRYABLE_UNAVAILABLE -> TaskResult.RetryableUnavailable
    BlockStoreOutcome.FAIL_CLOSED -> TaskResult.Indeterminate
    BlockStoreOutcome.INDETERMINATE -> TaskResult.Indeterminate
    BlockStoreOutcome.INCOMPLETE -> TaskResult.Incomplete
}

internal fun BlockStoreResult<Boolean>.toEligibilityTaskResult(): TaskResult<ProbeEligibility> = when (outcome) {
    BlockStoreOutcome.COMPLETED -> TaskResult.Completed(
        ProbeEligibility(
            capability = if (value == true) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            placement = CapabilityPlacement.SYNCED_PROVIDER,
            backupEligibility = BackupEligibility.UNKNOWN,
            screenLock = ScreenLockState.UNKNOWN,
            e2ee = if (value == true) E2eeState.AVAILABLE else E2eeState.UNAVAILABLE,
            restorePath = RestorePath.BLOCK_STORE_CLOUD,
        ),
    )
    BlockStoreOutcome.UNAVAILABLE -> TaskResult.Unavailable
    BlockStoreOutcome.RETRYABLE_UNAVAILABLE -> TaskResult.RetryableUnavailable
    BlockStoreOutcome.FAIL_CLOSED -> TaskResult.Indeterminate
    BlockStoreOutcome.INDETERMINATE -> TaskResult.Indeterminate
    BlockStoreOutcome.INCOMPLETE -> TaskResult.Incomplete
}

/** Operator-selected SAF bridge. It never persists grants or reports URI metadata. */
class AndroidProbePTransportPort(
    context: Context,
    private val executor: Executor,
) : ProbePTransportPort {
    private val resolver = context.applicationContext.contentResolver
    @Volatile private var selectedUri: Uri? = null

    fun select(uri: Uri) {
        selectedUri = uri
    }

    fun clearSelection() {
        selectedUri = null
    }

    override fun exportP(
        opaqueP: ByteArray,
        expectedContext: ExpectedContext,
        onSettled: (TaskResult<Unit>) -> Unit,
    ): ProbeControllerOperation {
        val owned = OwnedSafExportBytes(opaqueP)
        return runSaf(
            work = { control ->
                try {
                    SafSidecarPTransport(selection()).storePExact(
                        owned.bytes,
                        expectedContext,
                        control,
                    )
                } finally {
                    owned.wipe()
                }
            },
            onAbandonBeforeStart = owned::wipe,
        ) then onSettled
    }

    override fun importP(
        expectedContext: ExpectedContext,
        onSettled: (TaskResult<ByteArray>) -> Unit,
    ): ProbeControllerOperation = runSaf(
        work = { control ->
            SafSidecarPTransport(selection()).readPExact(expectedContext, control)
        },
    ) then onSettled

    private fun selection(): SafDocumentSelection? = selectedUri?.let {
        SafDocumentSelection(
            document = AndroidSafSelectedDocument(resolver, it),
            grantSelection = SafGrantSelection.ONE_SHOT,
        )
    }

    private fun <T> runSaf(
        work: (SafOperationControl) -> SafTransportResult<T>,
        onAbandonBeforeStart: () -> Unit = {},
    ): AsyncSafOperation<T> = AsyncSafOperation(executor, work, onAbandonBeforeStart)
}

internal class OwnedSafExportBytes(source: ByteArray) {
    val bytes = source.copyOf()
    private val wiped = AtomicBoolean(false)

    init {
        source.fill(0)
    }

    fun wipe() {
        if (wiped.compareAndSet(false, true)) bytes.fill(0)
    }
}

internal class AsyncSafOperation<T>(
    private val executor: Executor,
    private val work: (SafOperationControl) -> SafTransportResult<T>,
    private val onAbandonBeforeStart: () -> Unit = {},
) : ProbeControllerOperation {
    private val settled = AtomicBoolean(false)
    private val signal = AtomicReference(SafControlSignal.CONTINUE)
    private val execution = AtomicReference(SafExecutionState.QUEUED)
    private var callback: ((TaskResult<T>) -> Unit)? = null

    infix fun then(onSettled: (TaskResult<T>) -> Unit): ProbeControllerOperation {
        callback = onSettled
        try {
            executor.execute {
                if (!execution.compareAndSet(SafExecutionState.QUEUED, SafExecutionState.STARTED)) {
                    return@execute
                }
                val result = try {
                    work(SafOperationControl { signal.get() }).asTaskResult()
                } catch (_: RuntimeException) {
                    TaskResult.Indeterminate
                }
                settle(result)
            }
        } catch (_: RuntimeException) {
            abandonBeforeStart()
            settle(TaskResult.Indeterminate)
        }
        return this
    }

    override fun timeout(): Boolean {
        signal.set(SafControlSignal.TIMEOUT)
        abandonBeforeStart()
        return settle(TaskResult.RetryableUnavailable)
    }

    override fun cancel(): Boolean {
        signal.set(SafControlSignal.CANCELLED)
        abandonBeforeStart()
        return settle(TaskResult.RetryableUnavailable)
    }

    private fun abandonBeforeStart() {
        if (execution.compareAndSet(SafExecutionState.QUEUED, SafExecutionState.ABANDONED)) {
            onAbandonBeforeStart()
        }
    }

    private fun settle(result: TaskResult<T>): Boolean {
        if (!settled.compareAndSet(false, true)) {
            discard(result)
            return false
        }
        callback?.invoke(result)
        return true
    }

    private fun discard(result: TaskResult<T>) {
        if (result is TaskResult.Completed<*> && result.value is ByteArray) {
            result.value.fill(0)
        }
    }
}

private enum class SafExecutionState {
    QUEUED,
    STARTED,
    ABANDONED,
}

private class AndroidSafSelectedDocument(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : SafSelectedDocument {
    override val providerLengthHintBytes: Long? = null

    override fun openInput(): SafTransportResult<InputStream> = try {
        resolver.openInputStream(uri)?.let { SafTransportResult.completed<InputStream>(it) }
            ?: SafTransportResult.failure(SafTransportOutcome.MISSING)
    } catch (_: SecurityException) {
        SafTransportResult.failure(SafTransportOutcome.REVOKED)
    } catch (_: FileNotFoundException) {
        SafTransportResult.failure(SafTransportOutcome.MISSING)
    } catch (_: IOException) {
        SafTransportResult.failure(SafTransportOutcome.IO_ERROR)
    } catch (_: RuntimeException) {
        SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
    }

    override fun openOutput(): SafTransportResult<OutputStream> = try {
        resolver.openOutputStream(uri, "w")?.let { SafTransportResult.completed<OutputStream>(it) }
            ?: SafTransportResult.failure(SafTransportOutcome.MISSING)
    } catch (_: SecurityException) {
        SafTransportResult.failure(SafTransportOutcome.REVOKED)
    } catch (_: FileNotFoundException) {
        SafTransportResult.failure(SafTransportOutcome.MISSING)
    } catch (_: IOException) {
        SafTransportResult.failure(SafTransportOutcome.IO_ERROR)
    } catch (_: RuntimeException) {
        SafTransportResult.failure(SafTransportOutcome.INDETERMINATE)
    }
}

private fun <T> SafTransportResult<T>.asTaskResult(): TaskResult<T> = when (outcome) {
    SafTransportOutcome.COMPLETED -> TaskResult.Completed(checkNotNull(value))
    SafTransportOutcome.MISSING,
    SafTransportOutcome.REVOKED,
    -> TaskResult.Unavailable
    SafTransportOutcome.IO_ERROR,
    SafTransportOutcome.TIMEOUT,
    SafTransportOutcome.CANCELLED,
    -> TaskResult.RetryableUnavailable
    SafTransportOutcome.SECURITY_REJECTED,
    SafTransportOutcome.INDETERMINATE,
    -> TaskResult.Indeterminate
}

/** Handler-backed deadline source; cancellation removes only the controller timer. */
class AndroidProbeScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : ProbeScheduler {
    override fun schedule(delayMs: Long, callback: () -> Unit): ProbeScheduledHandle {
        val runnable = Runnable(callback)
        handler.postDelayed(runnable, delayMs)
        return ProbeScheduledHandle { handler.removeCallbacks(runnable) }
    }
}
