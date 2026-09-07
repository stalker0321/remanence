package dev.hryshyn.rv01probe.probe

import android.app.KeyguardManager
import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.BlockstoreClient
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.OnCanceledListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Fixed, redaction-safe outcomes for one Block Store bridge operation. */
enum class BlockStoreOutcome {
    COMPLETED,
    UNAVAILABLE,
    RETRYABLE_UNAVAILABLE,
    INCOMPLETE,
    FAIL_CLOSED,
    INDETERMINATE,
}

/** Probe-owned result; it retains no provider exception, subject, or raw error. */
class BlockStoreResult<T> private constructor(
    val outcome: BlockStoreOutcome,
    val value: T?,
) {
    init {
        require((outcome == BlockStoreOutcome.COMPLETED) == (value != null))
    }

    companion object {
        fun <T> completed(value: T): BlockStoreResult<T> =
            BlockStoreResult(BlockStoreOutcome.COMPLETED, value)

        fun <T> failure(outcome: BlockStoreOutcome): BlockStoreResult<T> {
            require(outcome != BlockStoreOutcome.COMPLETED)
            return BlockStoreResult(outcome, null)
        }
    }
}

/** Only these bounded failure classes cross the injected Task port. */
enum class BlockStoreTaskFailure {
    UNAVAILABLE,
    RETRYABLE_UNAVAILABLE,
    INDETERMINATE,
}

/** Small testable view of a Google Task; no exception object crosses this port. */
interface BlockStoreTaskPort<T> {
    fun addOnSuccessListener(listener: (T) -> Unit)

    fun addOnFailureListener(listener: (BlockStoreTaskFailure) -> Unit)

    fun addOnCanceledListener(listener: () -> Unit)
}

/** Exact one-key request models used by the policy layer and deterministic fakes. */
data class BlockStoreStoreRequest(
    val key: String,
    val value: ByteArray,
    val shouldBackupToCloud: Boolean,
)

data class BlockStoreRetrieveRequest(
    val keys: List<String>,
)

data class BlockStoreDeleteRequest(
    val keys: List<String>,
)

/**
 * Provider response holder. The byte arrays are borrowed until this holder is
 * wiped; the policy layer copies only an exactly-sized accepted value.
 */
class BlockStoreRetrieveResponse(
    entries: Map<String, ByteArray?>,
) {
    private val lock = Any()
    private var retainedEntries: Map<String, ByteArray?> = entries.toMap()

    val entries: Map<String, ByteArray?>
        get() = synchronized(lock) { retainedEntries }

    fun wipe() {
        val toWipe = synchronized(lock) {
            val current = retainedEntries
            retainedEntries = emptyMap()
            current
        }
        toWipe.values.filterNotNull().toSet().forEach { it.fill(0) }
    }
}

/** Injected client port; the real implementation below is the only SDK bridge. */
interface BlockStoreClientPort {
    fun isEndToEndEncryptionAvailable(): BlockStoreTaskPort<Boolean>

    fun storeBytes(request: BlockStoreStoreRequest): BlockStoreTaskPort<Int>

    fun retrieveBytes(request: BlockStoreRetrieveRequest): BlockStoreTaskPort<BlockStoreRetrieveResponse>

    fun deleteBytes(request: BlockStoreDeleteRequest): BlockStoreTaskPort<Boolean>
}

/** Lazily creates an SDK client after the Play-services gate has passed. */
fun interface BlockStoreClientFactory {
    fun create(): BlockStoreClientPort
}

/** The real adapter is conservative: secure-lock presence alone is not proof of a qualifying PIN. */
enum class BlockStoreLockState {
    QUALIFIED,
    INSECURE,
    UNKNOWN,
}

class AndroidQualifyingLockStateProvider(
    private val context: Context,
) {
    fun current(): BlockStoreLockState = try {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        when {
            keyguard == null -> BlockStoreLockState.UNKNOWN
            !keyguard.isDeviceSecure -> BlockStoreLockState.INSECURE
            else -> BlockStoreLockState.UNKNOWN
        }
    } catch (_: RuntimeException) {
        BlockStoreLockState.UNKNOWN
    }
}

/**
 * First-settlement-wins owner for one provider Task. A Google Task is not
 * assumed cancellable; timeout/cancel closes this owner and late callbacks are
 * ignored. Late successful values can be handed to a caller-owned scrubber.
 */
class BlockStoreTaskOwner<T>(
    private val task: BlockStoreTaskPort<T>,
    private val onDefinitiveSettlement: (() -> Unit)? = null,
    private val onLateSuccess: ((T) -> Unit)? = null,
) {
    private val lock = Any()
    private var settled = false
    private var callback: ((BlockStoreResult<T>) -> Unit)? = null

    fun start(onSettled: (BlockStoreResult<T>) -> Unit) {
        callback = onSettled
        task.addOnSuccessListener { value ->
            publish(BlockStoreResult.completed(value))
        }
        task.addOnFailureListener { failure ->
            publish(
                BlockStoreResult.failure(
                    when (failure) {
                        BlockStoreTaskFailure.UNAVAILABLE -> BlockStoreOutcome.UNAVAILABLE
                        BlockStoreTaskFailure.RETRYABLE_UNAVAILABLE ->
                            BlockStoreOutcome.RETRYABLE_UNAVAILABLE
                        BlockStoreTaskFailure.INDETERMINATE -> BlockStoreOutcome.INDETERMINATE
                    },
                ),
            )
        }
        task.addOnCanceledListener {
            publish(BlockStoreResult.failure(BlockStoreOutcome.RETRYABLE_UNAVAILABLE))
        }
    }

    fun timeout(): Boolean = publish(
        BlockStoreResult.failure(BlockStoreOutcome.RETRYABLE_UNAVAILABLE),
    )

    fun cancel(): Boolean = publish(
        BlockStoreResult.failure(BlockStoreOutcome.RETRYABLE_UNAVAILABLE),
    )

    fun isSettled(): Boolean = synchronized(lock) { settled }

    private fun publish(result: BlockStoreResult<T>): Boolean {
        val accepted = synchronized(lock) {
            if (settled) {
                false
            } else {
                settled = true
                true
            }
        }
        if (!accepted) {
            result.value?.let { onLateSuccess?.invoke(it) }
            return false
        }
        onDefinitiveSettlement?.invoke()
        callback?.invoke(result)
        return true
    }
}

/**
 * Parent operation for a chained E2EE-check/store or one direct operation.
 * reserveAndLaunch holds the operation lock while the child request is issued,
 * so timeout/cancel cannot settle in the gap between reservation and launch.
 */
class BlockStoreOperation<T> internal constructor(
    private val onSettled: (BlockStoreResult<T>) -> Unit,
) {
    private val lock = Any()
    private var settled: BlockStoreResult<T>? = null
    private var onUncertain: (() -> Unit)? = null
    private var childReserved = false

    fun timeout(): Boolean = settle(BlockStoreResult.failure(BlockStoreOutcome.RETRYABLE_UNAVAILABLE))

    fun cancel(): Boolean = settle(BlockStoreResult.failure(BlockStoreOutcome.RETRYABLE_UNAVAILABLE))

    fun snapshot(): BlockStoreResult<T>? = synchronized(lock) { settled }

    internal fun isOpen(): Boolean = synchronized(lock) { settled == null }

    /**
     * Atomically reserves and launches a provider child. The launch closure is
     * deliberately invoked while the operation lock is held. A synchronous
     * fake can therefore request timeout/cancel, but it cannot pass the point
     * without the provider call already having been made.
    */
    internal fun <R> reserveAndLaunch(
        onUncertain: (() -> Unit)? = null,
        launch: () -> R,
    ): R? = synchronized(lock) {
        if (settled != null || childReserved) return@synchronized null
        childReserved = true
        this.onUncertain = onUncertain
        launch()
    }

    internal fun settle(result: BlockStoreResult<T>): Boolean {
        val actions = synchronized(lock) {
            if (settled != null) {
                null
            } else {
                settled = result
                Pair(onUncertain, result)
            }
        } ?: return false

        if (actions.second.outcome == BlockStoreOutcome.UNAVAILABLE ||
            actions.second.outcome == BlockStoreOutcome.RETRYABLE_UNAVAILABLE ||
            actions.second.outcome == BlockStoreOutcome.INDETERMINATE
        ) {
            actions.first?.invoke()
        }
        onSettled(actions.second)
        return true
    }
}

/**
 * Permanent local tombstone for a real uncertain delete. There is no clear or
 * reconcile operation: Block Store has no proof/token fence for a late delete.
 */
class BlockStoreDeleteTombstone(
    private val registry: AbandonedKeyRegistry,
) {
    fun abandon(key: String) {
        registry.abandon(key)
    }

    fun isTombstoned(key: String): Boolean = registry.isTombstoned(key)

    companion object {
        /** Explicitly test-only in-memory state; real construction is persistent. */
        fun inMemoryForTest(): BlockStoreDeleteTombstone =
            BlockStoreDeleteTombstone(InMemoryAbandonedKeyRegistry())
    }
}

/** Probe-local persistence port; it stores only bounded exact K_U tombstones. */
interface AbandonedKeyRegistry {
    fun abandon(key: String): Boolean

    fun isTombstoned(key: String): Boolean
}

/** Explicit path/read/write states keep missing distinct from ambiguity. */
internal sealed interface AbandonedKeyReadResult {
    data object Absent : AbandonedKeyReadResult
    data class Present(val bytes: ByteArray) : AbandonedKeyReadResult
    data object Ambiguous : AbandonedKeyReadResult
}

/** Injectable only for deterministic probe tests; real file operations are below. */
internal interface AbandonedKeyFileOps {
    fun read(file: File): AbandonedKeyReadResult

    fun persist(file: File, bytes: ByteArray): Boolean
}

private enum class AbandonedKeyPathState {
    ABSENT,
    PRESENT,
    AMBIGUOUS,
}

/**
 * Checks the complete fixed-child path without following any component. A
 * missing leaf is safe only when the trusted base and every ancestor are
 * already present, regular directories. This is validation, not a TOCTOU
 * proof; callers remain fail-closed when a later operation cannot establish
 * the same state.
 */
private fun abandonedKeyPathState(file: File, trustedBase: File): AbandonedKeyPathState {
    return try {
        val base = trustedBase.toPath().toAbsolutePath().normalize()
        val leaf = file.toPath().toAbsolutePath().normalize()
        if (leaf.parent != base || !regularDirectoryChain(base)) {
            return AbandonedKeyPathState.AMBIGUOUS
        }
        if (Files.isSymbolicLink(leaf)) return AbandonedKeyPathState.AMBIGUOUS
        val absent = Files.notExists(leaf, LinkOption.NOFOLLOW_LINKS)
        val present = Files.exists(leaf, LinkOption.NOFOLLOW_LINKS)
        when {
            absent && !present -> AbandonedKeyPathState.ABSENT
            present && !absent && Files.isRegularFile(leaf, LinkOption.NOFOLLOW_LINKS) ->
                AbandonedKeyPathState.PRESENT
            else -> AbandonedKeyPathState.AMBIGUOUS
        }
    } catch (_: Exception) {
        AbandonedKeyPathState.AMBIGUOUS
    }
}

private fun regularDirectoryChain(base: Path): Boolean {
    var current: Path? = base
    while (current != null) {
        val path = current
        if (Files.isSymbolicLink(path)) return false
        val absent = Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
        val present = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        if (!present || absent || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        current = path.parent
    }
    return true
}

internal fun interface AbandonedKeyAtomicReplacer {
    fun replace(temporary: File, target: File)
}

private val defaultAbandonedKeyAtomicReplacer = AbandonedKeyAtomicReplacer { temporary, target ->
    Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun syncAbandonedKeyParentDirectory(directory: File): Boolean = try {
    // Directory force is platform-dependent; failure means durability is not
    // established and the registry must remain fail-closed.
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
        channel.force(true)
    }
    true
} catch (_: Exception) {
    false
}

private class DefaultAbandonedKeyFileOps(
    private val atomicReplacer: AbandonedKeyAtomicReplacer = defaultAbandonedKeyAtomicReplacer,
    private val syncParentDirectory: (File) -> Boolean = ::syncAbandonedKeyParentDirectory,
) : AbandonedKeyFileOps {
    override fun read(file: File): AbandonedKeyReadResult {
        val path = file.toPath()
        return try {
            when (abandonedKeyPathState(file, file.parentFile ?: File("."))) {
                AbandonedKeyPathState.ABSENT -> return AbandonedKeyReadResult.Absent
                AbandonedKeyPathState.PRESENT -> Unit
                AbandonedKeyPathState.AMBIGUOUS -> return AbandonedKeyReadResult.Ambiguous
            }
            // NOFOLLOW is required so a dangling or live symlink is never
            // mistaken for a healthy missing registry.
            if (Files.isSymbolicLink(path)) return AbandonedKeyReadResult.Ambiguous
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return AbandonedKeyReadResult.Absent
            }
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                return AbandonedKeyReadResult.Ambiguous
            }
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val bytes = ByteArray(PersistentAbandonedKeyRegistry.MAX_BYTES + 1)
                var total = 0
                while (true) {
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count < 0) break
                    total += count
                    if (total > PersistentAbandonedKeyRegistry.MAX_BYTES ||
                        total == bytes.size
                    ) {
                        return AbandonedKeyReadResult.Ambiguous
                    }
                }
                AbandonedKeyReadResult.Present(bytes.copyOf(total))
            }
        } catch (_: Exception) {
            AbandonedKeyReadResult.Ambiguous
        }
    }

    override fun persist(file: File, bytes: ByteArray): Boolean {
        val path = file.toPath()
        return try {
            if (abandonedKeyPathState(file, file.parentFile ?: File(".")) ==
                AbandonedKeyPathState.AMBIGUOUS
            ) {
                return false
            }
            if (bytes.size > PersistentAbandonedKeyRegistry.MAX_BYTES ||
                Files.isSymbolicLink(path)
            ) {
                return false
            }
            val targetAbsent = Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
            val targetPresent = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            // Both false means metadata could not establish either state;
            // never replace an ambiguous target.
            if (!targetAbsent && !targetPresent) return false
            if (targetPresent && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return false
            }
            val parent = file.parentFile ?: return false
            val parentPath = parent.toPath()
            if (Files.isSymbolicLink(parentPath)) return false
            val parentAbsent = Files.notExists(parentPath, LinkOption.NOFOLLOW_LINKS)
            val parentPresent = Files.exists(parentPath, LinkOption.NOFOLLOW_LINKS)
            if (!parentAbsent && !parentPresent) return false
            if (parentPresent && !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)) {
                return false
            }
            if (!parent.exists() && !parent.mkdirs()) return false
            if (Files.isSymbolicLink(parentPath) ||
                !Files.exists(parentPath, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)
            ) {
                return false
            }
            val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                atomicReplacer.replace(temporary, file)
                if (!syncParentDirectory(parent)) return false
                abandonedKeyPathState(file, file.parentFile ?: File(".")) ==
                    AbandonedKeyPathState.PRESENT
            } finally {
                temporary.delete()
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** Test-only seam at the default algorithm's atomic-replace boundary. */
internal fun defaultAbandonedKeyFileOpsForTest(
    atomicReplacer: AbandonedKeyAtomicReplacer,
): AbandonedKeyFileOps = DefaultAbandonedKeyFileOps(atomicReplacer = atomicReplacer)

private class InMemoryAbandonedKeyRegistry : AbandonedKeyRegistry {
    private val lock = Any()
    private val abandoned = mutableSetOf<String>()

    override fun abandon(key: String): Boolean = synchronized(lock) {
        if (!ProbeKey.isValid(key)) return@synchronized false
        abandoned += key
        true
    }

    override fun isTombstoned(key: String): Boolean = synchronized(lock) { key in abandoned }
}

/**
 * Bounded atomic registry for real probe use. A missing file means no
 * abandoned keys; malformed, oversized, duplicate, noncanonical, or
 * unreadable state blocks every key fail-closed. No U, P, account, or provider
 * data is persisted.
 */
class PersistentAbandonedKeyRegistry private constructor(
    private val file: File,
    private val trustedBase: File,
    private val fileOps: AbandonedKeyFileOps,
) : AbandonedKeyRegistry {
    internal constructor(
        file: File,
        fileOps: AbandonedKeyFileOps = DefaultAbandonedKeyFileOps(),
    ) : this(file, file.parentFile ?: File("."), fileOps)

    private sealed interface ParsedState {
        data object Blocked : ParsedState
        data class Keys(val values: Set<String>) : ParsedState
    }

    private val lock = Any()
    private val abandoned = linkedSetOf<String>()
    private var healthy = true
    private var globallyBlocked = false

    init {
        load()
    }

    override fun abandon(key: String): Boolean = synchronized(lock) {
        if (abandonedKeyPathState(file, trustedBase) == AbandonedKeyPathState.AMBIGUOUS) {
            healthy = false
        }
        if (!ProbeKey.isValid(key) || !healthy || globallyBlocked) return@synchronized false
        if (key in abandoned) return@synchronized true
        if (abandoned.size >= MAX_ENTRIES) {
            globallyBlocked = fileOps.persist(file, BLOCKED_BYTES)
            healthy = globallyBlocked
            return@synchronized false
        }
        val next = (abandoned + key).toSortedSet()
        val encoded = serialize(next)
        if (encoded.size > MAX_BYTES || !fileOps.persist(file, encoded)) {
            healthy = false
            return@synchronized false
        }
        abandoned += key
        true
    }

    override fun isTombstoned(key: String): Boolean = synchronized(lock) {
        if (healthy &&
            abandonedKeyPathState(file, trustedBase) == AbandonedKeyPathState.AMBIGUOUS
        ) {
            healthy = false
        }
        !healthy || globallyBlocked || key in abandoned
    }

    private fun load() {
        synchronized(lock) {
            if (abandonedKeyPathState(file, trustedBase) == AbandonedKeyPathState.AMBIGUOUS) {
                healthy = false
                return
            }
            when (val read = fileOps.read(file)) {
                AbandonedKeyReadResult.Absent -> Unit
                AbandonedKeyReadResult.Ambiguous -> healthy = false
                is AbandonedKeyReadResult.Present -> when (val parsed = parse(read.bytes)) {
                    null -> healthy = false
                    ParsedState.Blocked -> globallyBlocked = true
                    is ParsedState.Keys -> abandoned += parsed.values
                }
            }
        }
    }

    private fun parse(bytes: ByteArray): ParsedState? {
        if (bytes.contentEquals(BLOCKED_BYTES)) return ParsedState.Blocked
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        if (!text.startsWith(MAGIC) || !text.endsWith("\n") ||
            !text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)
        ) {
            return null
        }
        val body = text.removePrefix(MAGIC).removeSuffix("\n")
        val keys = if (body.isEmpty()) emptyList() else body.split("\n")
        if (keys.size > MAX_ENTRIES || keys != keys.distinct().sorted()) return null
        if (keys.any { !ProbeKey.isValid(it) }) return null
        return ParsedState.Keys(keys.toSet())
    }

    private fun serialize(keys: Set<String>): ByteArray =
        (MAGIC + keys.sorted().joinToString(separator = "", postfix = "") { "$it\n" })
            .toByteArray(StandardCharsets.UTF_8)

    internal companion object {
        const val MAX_ENTRIES = 16
        const val MAX_BYTES = 1024
        const val FILE_NAME = "rv01-abandoned-ku-v1"
        const val MAGIC = "RV01-ABANDONED-KU-V1\n"
        val BLOCKED_BYTES = "RV01-ABANDONED-KU-V1-BLOCKED\n".toByteArray(StandardCharsets.UTF_8)

        fun forFilesDir(filesDir: File): PersistentAbandonedKeyRegistry =
            PersistentAbandonedKeyRegistry(
                file = File(filesDir, FILE_NAME),
                trustedBase = filesDir,
                fileOps = DefaultAbandonedKeyFileOps(),
            )
    }
}

/**
 * Real probe-only Block Store UStore. It accepts only exact K_U and 32-byte U,
 * issues one-key requests, and has no account, P, canary, plaintext, token,
 * retrieve-all, history, discovery, or bulk surface.
 */
class GoogleBlockStoreUStore(
    private val clientFactory: BlockStoreClientFactory,
    private val playServicesAvailable: () -> Boolean,
    private val lockState: () -> BlockStoreLockState,
    private val tombstone: BlockStoreDeleteTombstone,
) : BlockStoreUStore {
    constructor(
        client: BlockStoreClientPort,
        playServicesAvailable: () -> Boolean,
        tombstone: BlockStoreDeleteTombstone,
        lockState: () -> BlockStoreLockState = { BlockStoreLockState.QUALIFIED },
    ) : this(
        clientFactory = BlockStoreClientFactory { client },
        playServicesAvailable = playServicesAvailable,
        lockState = lockState,
        tombstone = tombstone,
    )

    constructor(
        context: Context,
    ) : this(
        clientFactory = GoogleBlockStoreClientFactory(context),
        playServicesAvailable = {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                ConnectionResult.SUCCESS
        },
        lockState = AndroidQualifyingLockStateProvider(context)::current,
        tombstone = BlockStoreDeleteTombstone(
            PersistentAbandonedKeyRegistry.forFilesDir(context.filesDir),
        ),
    )

    override fun storeU(
        key: String,
        value: ByteArray,
        onSettled: (BlockStoreResult<Unit>) -> Unit,
    ): BlockStoreOperation<Unit> {
        val operation = BlockStoreOperation(onSettled)
        if (!validKeyAndValue(key, value) || tombstone.isTombstoned(key)) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.FAIL_CLOSED))
            return operation
        }
        if (!isPlayServicesAvailable()) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.UNAVAILABLE))
            return operation
        }
        if (lockStateSafely() != BlockStoreLockState.QUALIFIED) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.UNAVAILABLE))
            return operation
        }

        val client = try {
            clientFactory.create()
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
            return operation
        }
        val e2eeTask = try {
            client.isEndToEndEncryptionAvailable()
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
            return operation
        }
        try {
            BlockStoreTaskOwner(e2eeTask).start { e2ee ->
                if (!operation.isOpen()) return@start
                when (e2ee.outcome) {
                    BlockStoreOutcome.COMPLETED -> {
                        if (e2ee.value != true) {
                            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.UNAVAILABLE))
                        } else {
                            startStoreTask(operation, client, key, value)
                        }
                    }
                    else -> operation.settle(BlockStoreResult.failure(e2ee.outcome))
                }
            }
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
        }
        return operation
    }

    override fun retrieveU(
        key: String,
        onSettled: (BlockStoreResult<ByteArray>) -> Unit,
    ): BlockStoreOperation<ByteArray> {
        val operation = BlockStoreOperation(onSettled)
        if (!ProbeKey.isValid(key)) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.FAIL_CLOSED))
            return operation
        }
        if (tombstone.isTombstoned(key)) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.FAIL_CLOSED))
            return operation
        }
        if (!isPlayServicesAvailable()) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.UNAVAILABLE))
            return operation
        }
        val client = try {
            clientFactory.create()
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
            return operation
        }
        val task = try {
            client.retrieveBytes(BlockStoreRetrieveRequest(listOf(key)))
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
            return operation
        }
        try {
            BlockStoreTaskOwner(task, onLateSuccess = { it.wipe() }).start { retrieved ->
                if (!operation.isOpen()) {
                    retrieved.value?.wipe()
                    return@start
                }
                if (retrieved.outcome != BlockStoreOutcome.COMPLETED) {
                    operation.settle(BlockStoreResult.failure(retrieved.outcome))
                    return@start
                }
                val response = retrieved.value
                if (response == null) {
                    operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
                    return@start
                }
                try {
                    val entries = response.entries
                    when {
                        entries.isEmpty() ->
                            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.UNAVAILABLE))
                        entries.size != 1 || entries.keys != setOf(key) ->
                            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
                        else -> {
                            val providerValue = entries[key]
                            if (providerValue == null ||
                                providerValue.isEmpty() ||
                                providerValue.size > MAX_PROVIDER_BYTES ||
                                providerValue.size != U_BYTES
                            ) {
                                operation.settle(BlockStoreResult.failure(BlockStoreOutcome.FAIL_CLOSED))
                            } else {
                                operation.settle(
                                    BlockStoreResult.completed(providerValue.copyOf()),
                                )
                            }
                        }
                    }
                } finally {
                    response.wipe()
                }
            }
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
        }
        return operation
    }

    override fun deleteU(
        key: String,
        onSettled: (BlockStoreResult<Unit>) -> Unit,
    ): BlockStoreOperation<Unit> {
        val operation = BlockStoreOperation(onSettled)
        if (!ProbeKey.isValid(key) || tombstone.isTombstoned(key)) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.FAIL_CLOSED))
            return operation
        }
        if (!isPlayServicesAvailable()) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.UNAVAILABLE))
            return operation
        }
        val client = try {
            clientFactory.create()
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
            return operation
        }
        val task = try {
            operation.reserveAndLaunch(
                onUncertain = { tombstone.abandon(key) },
            ) {
                client.deleteBytes(BlockStoreDeleteRequest(listOf(key)))
            }
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
            return operation
        }
        if (task == null) return operation
        try {
            BlockStoreTaskOwner(task).start { deleted ->
                if (!operation.isOpen()) return@start
                if (deleted.outcome == BlockStoreOutcome.COMPLETED) {
                    operation.settle(BlockStoreResult.completed(Unit))
                } else {
                    operation.settle(BlockStoreResult.failure(deleted.outcome))
                }
            }
        } catch (_: RuntimeException) {
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
        }
        return operation
    }

    private fun startStoreTask(
        operation: BlockStoreOperation<Unit>,
        client: BlockStoreClientPort,
        key: String,
        value: ByteArray,
    ) {
        val material = OwnedStoreBytes(value.copyOf())
        val request = BlockStoreStoreRequest(
            key = key,
            value = material.bytes,
            shouldBackupToCloud = true,
        )
        val task = try {
            operation.reserveAndLaunch {
                client.storeBytes(request)
            }
        } catch (_: RuntimeException) {
            material.wipe()
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
            return
        }
        if (task == null) {
            material.wipe()
            return
        }
        try {
            BlockStoreTaskOwner(
                task,
                onDefinitiveSettlement = material::wipe,
                onLateSuccess = { material.wipe() },
            ).start { stored ->
                if (!operation.isOpen()) return@start
                if (stored.outcome != BlockStoreOutcome.COMPLETED) {
                    operation.settle(BlockStoreResult.failure(stored.outcome))
                    return@start
                }
                operation.settle(
                    if (stored.value == U_BYTES) {
                        BlockStoreResult.completed(Unit)
                    } else {
                        BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE)
                    },
                )
            }
        } catch (_: RuntimeException) {
            material.wipe()
            operation.settle(BlockStoreResult.failure(BlockStoreOutcome.INDETERMINATE))
        }
    }

    private fun isPlayServicesAvailable(): Boolean = try {
        playServicesAvailable()
    } catch (_: RuntimeException) {
        false
    }

    private fun lockStateSafely(): BlockStoreLockState = try {
        lockState()
    } catch (_: RuntimeException) {
        BlockStoreLockState.UNKNOWN
    }

    private fun validKeyAndValue(key: String, value: ByteArray): Boolean =
        ProbeKey.isValid(key) && value.size == U_BYTES

    private companion object {
        const val U_BYTES = ProbeSidecar.KEY_BYTES
        const val MAX_PROVIDER_BYTES = 1024
    }
}

/** The exact asynchronous UStore shape used by the real bridge and fake tests. */
interface BlockStoreUStore {
    fun storeU(
        key: String,
        value: ByteArray,
        onSettled: (BlockStoreResult<Unit>) -> Unit,
    ): BlockStoreOperation<Unit>

    fun retrieveU(
        key: String,
        onSettled: (BlockStoreResult<ByteArray>) -> Unit,
    ): BlockStoreOperation<ByteArray>

    fun deleteU(
        key: String,
        onSettled: (BlockStoreResult<Unit>) -> Unit,
    ): BlockStoreOperation<Unit>
}

/** Lazily constructed official Google Block Store client port. */
class GoogleBlockStoreClientFactory(
    private val context: Context,
) : BlockStoreClientFactory {
    override fun create(): BlockStoreClientPort =
        GoogleBlockStoreClientPort(Blockstore.getClient(context))
}

/** Official Google Play services bridge; no other SDK path is used. */
class GoogleBlockStoreClientPort(
    private val client: BlockstoreClient,
) : BlockStoreClientPort {
    override fun isEndToEndEncryptionAvailable(): BlockStoreTaskPort<Boolean> =
        GoogleTaskPort(client.isEndToEndEncryptionAvailable())

    override fun storeBytes(request: BlockStoreStoreRequest): BlockStoreTaskPort<Int> {
        require(ProbeKey.isValid(request.key))
        require(request.value.size == ProbeSidecar.KEY_BYTES)
        require(request.shouldBackupToCloud)
        val data = StoreBytesData.Builder()
            .setBytes(request.value)
            .setKey(request.key)
            .setShouldBackupToCloud(true)
            .build()
        return GoogleTaskPort(client.storeBytes(data))
    }

    override fun retrieveBytes(request: BlockStoreRetrieveRequest): BlockStoreTaskPort<BlockStoreRetrieveResponse> {
        require(request.keys.size == 1)
        require(ProbeKey.isValid(request.keys.single()))
        val requestedKey = request.keys.single()
        val googleRequest = RetrieveBytesRequest.Builder()
            .setKeys(request.keys)
            .build()
        val task = GoogleTaskPort(client.retrieveBytes(googleRequest))
        return MappingTaskPort(task) { response ->
            val map = response.getBlockstoreDataMap()
            if (map.size > 1) {
                // Reject cardinality before copying; wipe provider arrays where
                // the SDK exposes mutable byte ownership to this process.
                map.values.forEach { it.getBytes().fill(0) }
                throw IllegalStateException()
            }
            val returned = map.entries.singleOrNull()
            if (returned == null) {
                BlockStoreRetrieveResponse(emptyMap())
            } else {
                val providerBytes = returned.value.getBytes()
                // Key and length are inspected before any copy. The policy
                // layer makes the only accepted copy and then wipes this ref.
                if (providerBytes.isEmpty() || providerBytes.size > 1024) {
                    providerBytes.fill(0)
                }
                BlockStoreRetrieveResponse(mapOf(returned.key to providerBytes))
            }
        }
    }

    override fun deleteBytes(request: BlockStoreDeleteRequest): BlockStoreTaskPort<Boolean> {
        require(request.keys.size == 1)
        require(ProbeKey.isValid(request.keys.single()))
        val googleRequest = DeleteBytesRequest.Builder()
            .setKeys(request.keys)
            .build()
        return GoogleTaskPort(client.deleteBytes(googleRequest))
    }
}

/** Strict SDK status mapping. Messages and provider exception text never cross the bridge. */
internal fun classifyGoogleTaskFailure(exception: Exception): BlockStoreTaskFailure =
    if (exception is ApiException) {
        when {
            exception.statusCode in UNAVAILABLE_STATUS_CODES -> BlockStoreTaskFailure.UNAVAILABLE
            exception.statusCode in RETRYABLE_STATUS_CODES ->
                BlockStoreTaskFailure.RETRYABLE_UNAVAILABLE
            else -> BlockStoreTaskFailure.INDETERMINATE
        }
    } else {
        BlockStoreTaskFailure.INDETERMINATE
    }

private val UNAVAILABLE_STATUS_CODES = setOf(
    ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
    ConnectionResult.SERVICE_DISABLED,
    CommonStatusCodes.SIGN_IN_REQUIRED,
    CommonStatusCodes.API_NOT_CONNECTED,
    ConnectionResult.SERVICE_MISSING,
    ConnectionResult.SERVICE_INVALID,
    ConnectionResult.SERVICE_UPDATING,
    ConnectionResult.API_DISABLED,
    ConnectionResult.API_DISABLED_FOR_CONNECTION,
)

private val RETRYABLE_STATUS_CODES = setOf(
    CommonStatusCodes.NETWORK_ERROR,
    CommonStatusCodes.INTERRUPTED,
    CommonStatusCodes.TIMEOUT,
    CommonStatusCodes.RECONNECTION_TIMED_OUT_DURING_UPDATE,
    CommonStatusCodes.RECONNECTION_TIMED_OUT,
)

private class GoogleTaskPort<T>(
    private val task: Task<T>,
) : BlockStoreTaskPort<T> {
    override fun addOnSuccessListener(listener: (T) -> Unit) {
        task.addOnSuccessListener(OnSuccessListener { value -> listener(value) })
    }

    override fun addOnFailureListener(listener: (BlockStoreTaskFailure) -> Unit) {
        task.addOnFailureListener(OnFailureListener { exception ->
            listener(classifyGoogleTaskFailure(exception))
        })
    }

    override fun addOnCanceledListener(listener: () -> Unit) {
        task.addOnCanceledListener(OnCanceledListener { listener() })
    }
}

private class MappingTaskPort<A, B>(
    private val source: BlockStoreTaskPort<A>,
    private val map: (A) -> B,
) : BlockStoreTaskPort<B> {
    private sealed interface Event<out T> {
        data class Success<T>(val value: T) : Event<T>
        data class Failure(val value: BlockStoreTaskFailure) : Event<Nothing>
        data object Cancelled : Event<Nothing>
    }

    private val lock = Any()
    private var event: Event<B>? = null
    private var delivered = false
    private var successListener: ((B) -> Unit)? = null
    private var failureListener: ((BlockStoreTaskFailure) -> Unit)? = null
    private var cancelledListener: (() -> Unit)? = null

    init {
        source.addOnSuccessListener { sourceValue ->
            val mapped = try {
                Event.Success(map(sourceValue))
            } catch (_: RuntimeException) {
                Event.Failure(BlockStoreTaskFailure.INDETERMINATE)
            }
            publish(mapped)
        }
        source.addOnFailureListener { publish(Event.Failure(it)) }
        source.addOnCanceledListener { publish(Event.Cancelled) }
    }

    override fun addOnSuccessListener(listener: (B) -> Unit) {
        var pending: Event<B>? = null
        synchronized(lock) {
            successListener = listener
            if (!delivered && event is Event.Success) {
                pending = event
                event = null
                delivered = true
            }
        }
        if (pending is Event.Success) {
            listener(pending.value)
        }
    }

    override fun addOnFailureListener(listener: (BlockStoreTaskFailure) -> Unit) {
        var pending: Event<B>? = null
        synchronized(lock) {
            failureListener = listener
            if (!delivered && event is Event.Failure) {
                pending = event
                event = null
                delivered = true
            }
        }
        if (pending is Event.Failure) {
            listener(pending.value)
        }
    }

    override fun addOnCanceledListener(listener: () -> Unit) {
        var pending: Event<B>? = null
        synchronized(lock) {
            cancelledListener = listener
            if (!delivered && event === Event.Cancelled) {
                pending = event
                event = null
                delivered = true
            }
        }
        if (pending === Event.Cancelled) listener()
    }

    private fun publish(value: Event<B>) {
        var success: ((B) -> Unit)? = null
        var failure: ((BlockStoreTaskFailure) -> Unit)? = null
        var cancelled: (() -> Unit)? = null
        var discard = false
        synchronized(lock) {
            if (delivered || event != null) {
                discard = true
            } else {
                event = value
                when (value) {
                    is Event.Success -> if (successListener != null) {
                        delivered = true
                        event = null
                        success = successListener
                    }
                    is Event.Failure -> if (failureListener != null) {
                        delivered = true
                        event = null
                        failure = failureListener
                    }
                    Event.Cancelled -> if (cancelledListener != null) {
                        delivered = true
                        event = null
                        cancelled = cancelledListener
                    }
                }
            }
        }
        if (discard) discard(value)
        when (value) {
            is Event.Success -> success?.invoke(value.value)
            is Event.Failure -> failure?.invoke(value.value)
            Event.Cancelled -> cancelled?.invoke()
        }
    }

    private fun discard(value: Event<B>) {
        if (value is Event.Success && value.value is BlockStoreRetrieveResponse) {
            value.value.wipe()
        }
    }
}

private class OwnedStoreBytes(
    val bytes: ByteArray,
) {
    private val lock = Any()
    private var wiped = false

    fun wipe() {
        synchronized(lock) {
            if (!wiped) {
                bytes.fill(0)
                wiped = true
            }
        }
    }
}
