package dev.hryshyn.rv01probe.probe

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.ConnectionResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BlockStoreProviderBackendTest {
    @Test
    fun `true E2EE gates one-key store and retained request bytes live until settlement`() {
        val key = validKey()
        val value = bytes(32)
        val client = FakeClient()
        val results = mutableListOf<BlockStoreResult<Unit>>()
        val bridge = bridge(client)

        bridge.storeU(key, value) { results += it }
        assertEquals(1, client.e2eeCalls)
        assertTrue(client.storeRequests.isEmpty())

        client.e2eeTask.succeed(true)
        val request = client.storeRequests.single()
        assertEquals(listOf(key), listOf(request.key))
        assertTrue(request.shouldBackupToCloud)
        assertArrayEquals(value, request.value)
        assertTrue(request.value.any { it.toInt() != 0 })

        client.storeTask.succeed(value.size)
        assertEquals(BlockStoreOutcome.COMPLETED, results.single().outcome)
        assertTrue(request.value.all { it.toInt() == 0 })
    }

    @Test
    fun `store failure timeout and cancel wipe the retained request reference`() {
        listOf(BlockStoreTaskFailure.INDETERMINATE, null, null).forEachIndexed { index, failure ->
            val client = FakeClient()
            val results = mutableListOf<BlockStoreResult<Unit>>()
            val operation = bridge(client).storeU(validKey(), bytes(32)) { results += it }
            client.e2eeTask.succeed(true)
            val request = client.storeRequests.single()

            when (index) {
                0 -> client.storeTask.fail(checkNotNull(failure))
                1 -> operation.timeout()
                else -> operation.cancel()
            }
            assertEquals(1, results.size)
            if (index == 0) {
                assertTrue(request.value.all { it.toInt() == 0 })
            } else {
                assertTrue(request.value.any { it.toInt() != 0 })
            }

            // A late success is still scrubbed by the late-callback ownership path.
            client.storeTask.succeed(32)
            assertTrue(request.value.all { it.toInt() == 0 })
            assertEquals(1, results.size)
        }
    }

    @Test
    fun `false E2EE and unavailable Play services never invoke cloud store`() {
        val key = validKey()
        val value = bytes(32)
        val noE2eeClient = FakeClient()
        val noE2eeResults = mutableListOf<BlockStoreResult<Unit>>()
        bridge(noE2eeClient).storeU(key, value) { noE2eeResults += it }
        noE2eeClient.e2eeTask.succeed(false)
        assertEquals(BlockStoreOutcome.UNAVAILABLE, noE2eeResults.single().outcome)
        assertTrue(noE2eeClient.storeRequests.isEmpty())

        val unavailableClient = FakeClient()
        val unavailableResults = mutableListOf<BlockStoreResult<Unit>>()
        bridge(unavailableClient, playAvailable = { false }).storeU(key, value) {
            unavailableResults += it
        }
        assertEquals(BlockStoreOutcome.UNAVAILABLE, unavailableResults.single().outcome)
        assertEquals(0, unavailableClient.e2eeCalls)
        assertTrue(unavailableClient.storeRequests.isEmpty())
    }

    @Test
    fun `client factory is lazy and is never called before Play availability succeeds`() {
        val client = FakeClient()
        var factoryCalls = 0
            val bridge = GoogleBlockStoreUStore(
                BlockStoreClientFactory {
                    factoryCalls += 1
                    client
                },
                { false },
                { BlockStoreLockState.QUALIFIED },
                BlockStoreDeleteTombstone.inMemoryForTest(),
        )

        val results = mutableListOf<BlockStoreResult<Unit>>()
        bridge.storeU(validKey(), bytes(32)) { results += it }
        assertEquals(BlockStoreOutcome.UNAVAILABLE, results.single().outcome)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `unknown and insecure lock states do not request E2EE or create a client`() {
        listOf(BlockStoreLockState.UNKNOWN, BlockStoreLockState.INSECURE).forEach { state ->
            val client = FakeClient()
            var factoryCalls = 0
            val bridge = GoogleBlockStoreUStore(
                BlockStoreClientFactory {
                    factoryCalls += 1
                    client
                },
                { true },
                { state },
                BlockStoreDeleteTombstone.inMemoryForTest(),
            )
            val results = mutableListOf<BlockStoreResult<Unit>>()

            bridge.storeU(validKey(), bytes(32)) { results += it }
            assertEquals(BlockStoreOutcome.UNAVAILABLE, results.single().outcome)
            assertEquals(0, factoryCalls)
            assertEquals(0, client.e2eeCalls)
            assertTrue(client.storeRequests.isEmpty())
        }
    }

    @Test
    fun `invalid key and malformed U fail closed before any client task`() {
        val client = FakeClient()
        val bridge = bridge(client)
        val outcomes = mutableListOf<BlockStoreOutcome>()

        listOf("short", "!" + validKey().drop(1), validKey() + "x").forEach { key ->
            bridge.storeU(key, bytes(32)) { outcomes += it.outcome }
            bridge.retrieveU(key) { outcomes += it.outcome }
            bridge.deleteU(key) { outcomes += it.outcome }
        }
        listOf(31, 33).forEach { size ->
            bridge.storeU(validKey(), bytes(size)) { outcomes += it.outcome }
        }

        assertEquals(List(11) { BlockStoreOutcome.FAIL_CLOSED }, outcomes)
        assertEquals(0, client.e2eeCalls)
        assertEquals(0, client.retrieveCalls)
        assertEquals(0, client.deleteCalls)
    }

    @Test
    fun `retrieve validates cardinality key and provider length before copying`() {
        val key = validKey()
        val otherKey = "Bbcdefghijklmnopqrstuv"
        val client = FakeClient()
        val bridge = bridge(client)

        val goodProviderBytes = bytes(32)
        val success = retrieve(bridge, client, key, BlockStoreRetrieveResponse(mapOf(key to goodProviderBytes)))
        assertEquals(BlockStoreOutcome.COMPLETED, success.outcome)
        assertArrayEquals(bytes(32), checkNotNull(success.value))
        assertTrue(goodProviderBytes.all { it.toInt() == 0 })

        val secondProviderBytes = bytes(32)
        val multi = retrieve(
            bridge,
            client,
            key,
            BlockStoreRetrieveResponse(mapOf(key to bytes(32), otherKey to secondProviderBytes)),
        )
        assertEquals(BlockStoreOutcome.INDETERMINATE, multi.outcome)
        assertNull(multi.value)
        assertTrue(secondProviderBytes.all { it.toInt() == 0 })

        val wrongProviderBytes = bytes(32)
        val wrong = retrieve(
            bridge,
            client,
            key,
            BlockStoreRetrieveResponse(mapOf(otherKey to wrongProviderBytes)),
        )
        assertEquals(BlockStoreOutcome.INDETERMINATE, wrong.outcome)
        assertTrue(wrongProviderBytes.all { it.toInt() == 0 })

        val nullEntry = retrieve(
            bridge,
            client,
            key,
            BlockStoreRetrieveResponse(mapOf(key to null)),
        )
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, nullEntry.outcome)

        val emptyProviderBytes = ByteArray(0)
        val empty = retrieve(
            bridge,
            client,
            key,
            BlockStoreRetrieveResponse(mapOf(key to emptyProviderBytes)),
        )
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, empty.outcome)
        assertTrue(emptyProviderBytes.all { it.toInt() == 0 })

        val oversizeProviderBytes = bytes(1025)
        val oversize = retrieve(
            bridge,
            client,
            key,
            BlockStoreRetrieveResponse(mapOf(key to oversizeProviderBytes)),
        )
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, oversize.outcome)
        assertTrue(oversizeProviderBytes.all { it.toInt() == 0 })
        assertEquals(6, client.retrieveCalls)
    }

    @Test
    fun `retrieve missing is unavailable and late response is wiped without redelivery`() {
        val key = validKey()
        val client = FakeClient()
        val bridge = bridge(client)
        val missing = mutableListOf<BlockStoreResult<ByteArray>>()
        bridge.retrieveU(key) { missing += it }
        client.retrieveTask.succeed(BlockStoreRetrieveResponse(emptyMap()))
        assertEquals(BlockStoreOutcome.UNAVAILABLE, missing.single().outcome)

        client.retrieveTask = FakeTask()
        val lateResults = mutableListOf<BlockStoreResult<ByteArray>>()
        val operation = bridge.retrieveU(key) { lateResults += it }
        val providerBytes = bytes(32)
        operation.timeout()
        client.retrieveTask.succeed(BlockStoreRetrieveResponse(mapOf(key to providerBytes)))
        assertEquals(BlockStoreOutcome.RETRYABLE_UNAVAILABLE, operation.snapshot()?.outcome)
        assertEquals(1, lateResults.size)
        assertTrue(providerBytes.all { it.toInt() == 0 })
    }

    @Test
    fun `delete is exact-key and false provider result is definitive completion`() {
        val key = validKey()
        val client = FakeClient()
        val bridge = bridge(client)
        val results = mutableListOf<BlockStoreResult<Unit>>()

        bridge.deleteU(key) { results += it }
        assertEquals(BlockStoreDeleteRequest(listOf(key)), client.deleteRequests.single())
        client.deleteTask.succeed(false)

        assertEquals(BlockStoreOutcome.COMPLETED, results.single().outcome)
        assertEquals(1, client.deleteCalls)
    }

    @Test
    fun `issued uncertain delete permanently tombstones key and allows no successor`() {
        val key = validKey()
        val value = bytes(32)
        val client = FakeClient()
        val tombstone = BlockStoreDeleteTombstone.inMemoryForTest()
        val bridge = bridge(client, tombstone = tombstone)
        val results = mutableListOf<BlockStoreResult<Unit>>()

        val delete = bridge.deleteU(key) { results += it }
        assertTrue(delete.timeout())
        assertEquals(BlockStoreOutcome.RETRYABLE_UNAVAILABLE, results.single().outcome)
        assertTrue(tombstone.isTombstoned(key))

        client.deleteTask.succeed(true)
        assertEquals(1, results.size)
        assertEquals(1, client.deleteCalls)

        bridge.deleteU(key) { results += it }
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, results.last().outcome)
        assertEquals(1, client.deleteCalls)

        val blocked = mutableListOf<BlockStoreResult<Unit>>()
        bridge.storeU(key, value) { blocked += it }
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, blocked.single().outcome)
        assertEquals(0, client.e2eeCalls)
        assertTrue(tombstone.isTombstoned(key))
    }

    @Test
    fun `Play-unavailable delete before launch does not tombstone key`() {
        val key = validKey()
        val directory = Files.createTempDirectory("rv01-abandoned-prelaunch").toFile()
        try {
            val registry = PersistentAbandonedKeyRegistry(File(directory, "abandoned"))
            val client = FakeClient()
            val tombstone = BlockStoreDeleteTombstone(registry)
            val results = mutableListOf<BlockStoreResult<Unit>>()

            bridge(client, playAvailable = { false }, tombstone = tombstone).deleteU(key) {
                results += it
            }
            assertEquals(BlockStoreOutcome.UNAVAILABLE, results.single().outcome)
            assertFalse(tombstone.isTombstoned(key))
            assertFalse(PersistentAbandonedKeyRegistry(File(directory, "abandoned"))
                .isTombstoned(key))
            assertEquals(0, client.deleteCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `delete unknown failure permanently tombstones and invalid delete does not`() {
        val key = validKey()
        val client = FakeClient()
        val tombstone = BlockStoreDeleteTombstone.inMemoryForTest()
        val bridge = bridge(client, tombstone = tombstone)
        val unknown = mutableListOf<BlockStoreResult<Unit>>()

        bridge.deleteU(key) { unknown += it }
        client.deleteTask.fail(BlockStoreTaskFailure.INDETERMINATE)
        assertEquals(BlockStoreOutcome.INDETERMINATE, unknown.single().outcome)
        assertTrue(tombstone.isTombstoned(key))

        val invalid = mutableListOf<BlockStoreResult<Unit>>()
        bridge.deleteU("short") { invalid += it }
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, invalid.single().outcome)
        assertFalse(tombstone.isTombstoned("short"))
    }

    @Test
    fun `uncertain delete survives a fresh registry and bridge reopen`() {
        val key = validKey()
        val directory = Files.createTempDirectory("rv01-abandoned-reopen").toFile()
        try {
            val file = File(directory, "abandoned")
            val firstClient = FakeClient()
            val first = bridge(
                firstClient,
                tombstone = BlockStoreDeleteTombstone(PersistentAbandonedKeyRegistry(file)),
            )
            assertFalse(
                "a genuinely absent normal registry is healthy-empty",
                PersistentAbandonedKeyRegistry(file).isTombstoned(key),
            )
            val firstResults = mutableListOf<BlockStoreResult<Unit>>()
            first.deleteU(key) { firstResults += it }.timeout()
            assertEquals(BlockStoreOutcome.RETRYABLE_UNAVAILABLE, firstResults.single().outcome)

            val secondClient = FakeClient()
            val secondTombstone = BlockStoreDeleteTombstone(
                PersistentAbandonedKeyRegistry(file),
            )
            val second = bridge(secondClient, tombstone = secondTombstone)
            val storeResults = mutableListOf<BlockStoreResult<Unit>>()
            second.storeU(key, bytes(32)) { storeResults += it }
            assertEquals(BlockStoreOutcome.FAIL_CLOSED, storeResults.single().outcome)
            assertEquals(0, secondClient.e2eeCalls)

            val deleteResults = mutableListOf<BlockStoreResult<Unit>>()
            second.deleteU(key) { deleteResults += it }
            assertEquals(BlockStoreOutcome.FAIL_CLOSED, deleteResults.single().outcome)
            assertEquals(0, secondClient.deleteCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `corrupt or oversized abandoned registry blocks all keys fail closed`() {
        listOf(byteArrayOf(0x01), ByteArray(1025) { 0x02 }).forEach { corruptBytes ->
            val directory = Files.createTempDirectory("rv01-abandoned-corrupt").toFile()
            try {
                val file = File(directory, "abandoned")
                Files.write(file.toPath(), corruptBytes)
                val tombstone = BlockStoreDeleteTombstone(PersistentAbandonedKeyRegistry(file))
                assertTrue(tombstone.isTombstoned(validKey()))

                val client = FakeClient()
                val bridge = bridge(client, tombstone = tombstone)
                val storeResults = mutableListOf<BlockStoreResult<Unit>>()
                bridge.storeU(validKey(), bytes(32)) { storeResults += it }
                assertEquals(BlockStoreOutcome.FAIL_CLOSED, storeResults.single().outcome)
                assertEquals(0, client.e2eeCalls)
                val deleteResults = mutableListOf<BlockStoreResult<Unit>>()
                bridge.deleteU(validKey()) { deleteResults += it }
                assertEquals(BlockStoreOutcome.FAIL_CLOSED, deleteResults.single().outcome)
                assertEquals(0, client.deleteCalls)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `abandoned registry persists bounded count and blocks on the seventeenth key`() {
        val directory = Files.createTempDirectory("rv01-abandoned-bounds").toFile()
        try {
            val file = File(directory, "abandoned")
            val registry = PersistentAbandonedKeyRegistry(file)
            (0 until 16).forEach { assertTrue(registry.abandon(keyForIndex(it))) }
            assertFalse(registry.isTombstoned(keyForIndex(16)))
            assertFalse(registry.abandon(keyForIndex(16)))
            assertTrue(registry.isTombstoned(keyForIndex(16)))
            assertTrue(
                PersistentAbandonedKeyRegistry(file).isTombstoned(keyForIndex(0)),
            )
            assertTrue(
                PersistentAbandonedKeyRegistry(file).isTombstoned(keyForIndex(16)),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `directory registry path is unhealthy and makes every provider operation fail closed`() {
        val parent = Files.createTempDirectory("rv01-abandoned-directory").toFile()
        try {
            val path = File(parent, "abandoned")
            assertTrue(path.mkdir())
            assertUnhealthyRegistryBlocks(PersistentAbandonedKeyRegistry(path))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `dangling symlink registry path is unhealthy when symlinks are supported`() {
        val parent = Files.createTempDirectory("rv01-abandoned-symlink").toFile()
        val link = File(parent, "abandoned")
        try {
            try {
                Files.createSymbolicLink(link.toPath(), File(parent, "missing").toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: SecurityException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }
            assertUnhealthyRegistryBlocks(PersistentAbandonedKeyRegistry(link))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `symlink parent registry path is unhealthy when symlinks are supported`() {
        val parent = Files.createTempDirectory("rv01-abandoned-parent-link").toFile()
        val realParent = File(parent, "real").also { assertTrue(it.mkdir()) }
        val linkParent = File(parent, "link")
        try {
            try {
                Files.createSymbolicLink(linkParent.toPath(), realParent.toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: SecurityException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }
            assertUnhealthyRegistryBlocks(
                PersistentAbandonedKeyRegistry(File(linkParent, "abandoned")),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `non-directory registry ancestor is unhealthy`() {
        val parent = Files.createTempDirectory("rv01-abandoned-nondirectory").toFile()
        try {
            val ancestor = File(parent, "not-a-directory")
            Files.write(ancestor.toPath(), byteArrayOf(1))
            assertUnhealthyRegistryBlocks(
                PersistentAbandonedKeyRegistry(File(ancestor, "abandoned")),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `live leaf symlink is unhealthy when symlinks are supported`() {
        val parent = Files.createTempDirectory("rv01-abandoned-live-link").toFile()
        val target = File(parent, "target")
        val link = File(parent, "abandoned")
        try {
            Files.write(target.toPath(), byteArrayOf(1))
            try {
                Files.createSymbolicLink(link.toPath(), target.toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: SecurityException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }
            assertUnhealthyRegistryBlocks(PersistentAbandonedKeyRegistry(link))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `normal absent child under regular directory is healthy-empty`() {
        val parent = Files.createTempDirectory("rv01-abandoned-absent").toFile()
        try {
            val file = File(parent, "abandoned")
            val key = validKey()
            val registry = PersistentAbandonedKeyRegistry(file)
            assertFalse(registry.isTombstoned(key))
            assertTrue(registry.abandon(key))
            assertTrue(PersistentAbandonedKeyRegistry(file).isTombstoned(key))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `injected read failure is ambiguous and blocks all provider calls`() {
        val fileOps = object : AbandonedKeyFileOps {
            override fun read(file: File): AbandonedKeyReadResult =
                AbandonedKeyReadResult.Ambiguous

            override fun persist(file: File, bytes: ByteArray): Boolean = false
        }
        assertUnhealthyRegistryBlocks(
            PersistentAbandonedKeyRegistry(File("unreadable"), fileOps),
        )
    }

    @Test
    fun `injected atomic replace failure makes abandonment fail closed`() {
        val fileOps = object : AbandonedKeyFileOps {
            override fun read(file: File): AbandonedKeyReadResult =
                AbandonedKeyReadResult.Absent

            override fun persist(file: File, bytes: ByteArray): Boolean = false
        }
        val registry = PersistentAbandonedKeyRegistry(File("write-failure"), fileOps)
        assertFalse(registry.abandon(validKey()))
        assertUnhealthyRegistryBlocks(registry)
    }

    @Test
    fun `atomic move failure preserves valid registry bytes and blocks providers`() {
        val parent = Files.createTempDirectory("rv01-abandoned-atomic-move").toFile()
        try {
            val file = File(parent, "abandoned")
            val priorKey = keyForIndex(0)
            val nextKey = keyForIndex(1)
            val initial = PersistentAbandonedKeyRegistry(file)
            assertTrue(initial.abandon(priorKey))
            val before = Files.readAllBytes(file.toPath())

            val failingDefaultOps = defaultAbandonedKeyFileOpsForTest(
                AbandonedKeyAtomicReplacer { _, _ ->
                    throw IllegalStateException("test-only atomic move failure")
                },
            )
            val failed = PersistentAbandonedKeyRegistry(file, failingDefaultOps)
            assertFalse(failed.abandon(nextKey))
            assertArrayEquals(before, Files.readAllBytes(file.toPath()))
            assertTrue(
                PersistentAbandonedKeyRegistry(file).isTombstoned(priorKey),
            )
            assertUnhealthyRegistryBlocks(failed)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `duplicate unsorted and trailing registry records are noncanonical`() {
        val key0 = keyForIndex(0)
        val key1 = keyForIndex(1)
        val header = "RV01-ABANDONED-KU-V1\n"
        listOf(
            "$header$key0\n$key0\n",
            "$header$key1\n$key0\n",
            "$header$key0\n\n",
            "$header$key0\ntrailing",
        ).forEach { noncanonical ->
            val parent = Files.createTempDirectory("rv01-abandoned-format").toFile()
            try {
                val file = File(parent, "abandoned")
                Files.write(file.toPath(), noncanonical.toByteArray(Charsets.UTF_8))
                assertUnhealthyRegistryBlocks(PersistentAbandonedKeyRegistry(file))
            } finally {
                parent.deleteRecursively()
            }
        }
    }

    @Test
    fun `E2EE timeout cancellation and unknown failure never start store`() {
        val key = validKey()
        val value = bytes(32)

        val timeoutClient = FakeClient()
        val timeoutResults = mutableListOf<BlockStoreResult<Unit>>()
        val timeout = bridge(timeoutClient).storeU(key, value) { timeoutResults += it }
        assertTrue(timeout.timeout())
        timeoutClient.e2eeTask.succeed(true)
        assertEquals(BlockStoreOutcome.RETRYABLE_UNAVAILABLE, timeoutResults.single().outcome)
        assertTrue(timeoutClient.storeRequests.isEmpty())

        val cancelClient = FakeClient()
        val cancelResults = mutableListOf<BlockStoreResult<Unit>>()
        bridge(cancelClient).storeU(key, value) { cancelResults += it }
        cancelClient.e2eeTask.cancel()
        assertEquals(BlockStoreOutcome.RETRYABLE_UNAVAILABLE, cancelResults.single().outcome)
        assertTrue(cancelClient.storeRequests.isEmpty())

        val unknownClient = FakeClient()
        val unknownResults = mutableListOf<BlockStoreResult<Unit>>()
        bridge(unknownClient).storeU(key, value) { unknownResults += it }
        unknownClient.e2eeTask.fail(BlockStoreTaskFailure.INDETERMINATE)
        assertEquals(BlockStoreOutcome.INDETERMINATE, unknownResults.single().outcome)
        assertTrue(unknownClient.storeRequests.isEmpty())
    }

    @Test
    fun `timeout during store launch cannot prevent issued child or produce later success`() {
        val client = FakeClient()
        val results = mutableListOf<BlockStoreResult<Unit>>()
        var operation: BlockStoreOperation<Unit>? = null
        client.storeLaunchHook = { request ->
            assertTrue(request.value.any { it.toInt() != 0 })
            checkNotNull(operation).timeout()
            assertTrue(request.value.any { it.toInt() != 0 })
        }
        operation = bridge(client).storeU(validKey(), bytes(32)) { results += it }

        client.e2eeTask.succeed(true)
        assertEquals(1, client.storeRequests.size)
        assertEquals(BlockStoreOutcome.RETRYABLE_UNAVAILABLE, results.single().outcome)
        val request = client.storeRequests.single()
        assertTrue(request.value.any { it.toInt() != 0 })

        client.storeTask.succeed(32)
        assertEquals(1, results.size)
        assertTrue(request.value.all { it.toInt() == 0 })
    }

    @Test
    fun `cancel during store launch has the same atomic reservation behavior`() {
        val client = FakeClient()
        val results = mutableListOf<BlockStoreResult<Unit>>()
        var operation: BlockStoreOperation<Unit>? = null
        client.storeLaunchHook = { request ->
            assertTrue(request.value.any { it.toInt() != 0 })
            checkNotNull(operation).cancel()
            assertTrue(request.value.any { it.toInt() != 0 })
        }
        operation = bridge(client).storeU(validKey(), bytes(32)) { results += it }

        client.e2eeTask.succeed(true)
        assertEquals(1, client.storeRequests.size)
        assertEquals(BlockStoreOutcome.RETRYABLE_UNAVAILABLE, results.single().outcome)
        assertTrue(client.storeRequests.single().value.any { it.toInt() != 0 })
        client.storeTask.succeed(32)
        assertEquals(1, results.size)
        assertTrue(client.storeRequests.single().value.all { it.toInt() == 0 })
    }

    @Test
    fun `Google failure classifier allowlists only known status classes`() {
        assertEquals(
            BlockStoreTaskFailure.RETRYABLE_UNAVAILABLE,
            classifyGoogleTaskFailure(ApiException(Status(CommonStatusCodes.NETWORK_ERROR))),
        )
        assertEquals(
            BlockStoreTaskFailure.RETRYABLE_UNAVAILABLE,
            classifyGoogleTaskFailure(ApiException(Status(CommonStatusCodes.TIMEOUT))),
        )
        assertEquals(
            BlockStoreTaskFailure.UNAVAILABLE,
            classifyGoogleTaskFailure(ApiException(Status(ConnectionResult.SERVICE_DISABLED))),
        )
        assertEquals(
            BlockStoreTaskFailure.INDETERMINATE,
            classifyGoogleTaskFailure(ApiException(Status(987654))),
        )
        assertEquals(
            BlockStoreTaskFailure.INDETERMINATE,
            classifyGoogleTaskFailure(IllegalStateException("not retained")),
        )
    }

    @Test
    fun `task owner is first settlement wins and late store result is ignored`() {
        val task = FakeTask<Int>()
        val owner = BlockStoreTaskOwner(task)
        val results = mutableListOf<BlockStoreResult<Int>>()
        owner.start { results += it }

        task.succeed(32)
        task.fail(BlockStoreTaskFailure.INDETERMINATE)
        task.cancel()
        assertFalse(owner.timeout())
        assertEquals(1, results.size)
        assertEquals(BlockStoreOutcome.COMPLETED, results.single().outcome)
        assertEquals(32, results.single().value)
    }

    @Test
    fun `synchronous store throw wipes retained request before one terminal result`() {
        val client = FakeClient()
        client.throwSynchronouslyInStore = true
        val results = mutableListOf<BlockStoreResult<Unit>>()
        var callbackSawWiped = false
        bridge(client).storeU(validKey(), bytes(32)) {
            callbackSawWiped = client.retainedStoreValues.single().all { it.toInt() == 0 }
            results += it
        }

        client.e2eeTask.succeed(true)
        assertTrue(callbackSawWiped)
        assertEquals(1, results.size)
        assertEquals(BlockStoreOutcome.INDETERMINATE, results.single().outcome)
        assertTrue(client.retainedStoreValues.single().all { it.toInt() == 0 })

        // There is no returned child Task, but a retained fake task must not
        // resurrect a result if a test/provider later signals it.
        client.storeTask.succeed(32)
        client.storeTask.fail(BlockStoreTaskFailure.INDETERMINATE)
        client.storeTask.cancel()
        assertEquals(1, results.size)
        assertTrue(client.retainedStoreValues.single().all { it.toInt() == 0 })
    }

    private fun retrieve(
        bridge: GoogleBlockStoreUStore,
        client: FakeClient,
        key: String,
        response: BlockStoreRetrieveResponse,
    ): BlockStoreResult<ByteArray> {
        client.retrieveTask = FakeTask()
        val results = mutableListOf<BlockStoreResult<ByteArray>>()
        bridge.retrieveU(key) { results += it }
        client.retrieveTask.succeed(response)
        return results.single()
    }

    private fun assertUnhealthyRegistryBlocks(registry: AbandonedKeyRegistry) {
        assertTrue(registry.isTombstoned(validKey()))
        val client = FakeClient()
        val bridge = bridge(client, tombstone = BlockStoreDeleteTombstone(registry))

        val storeResults = mutableListOf<BlockStoreResult<Unit>>()
        bridge.storeU(validKey(), bytes(32)) { storeResults += it }
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, storeResults.single().outcome)

        val retrieveResults = mutableListOf<BlockStoreResult<ByteArray>>()
        bridge.retrieveU(validKey()) { retrieveResults += it }
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, retrieveResults.single().outcome)

        val deleteResults = mutableListOf<BlockStoreResult<Unit>>()
        bridge.deleteU(validKey()) { deleteResults += it }
        assertEquals(BlockStoreOutcome.FAIL_CLOSED, deleteResults.single().outcome)
        assertEquals(0, client.e2eeCalls)
        assertEquals(0, client.retrieveCalls)
        assertEquals(0, client.deleteCalls)
    }

    private fun bridge(
        client: FakeClient,
        playAvailable: () -> Boolean = { true },
        tombstone: BlockStoreDeleteTombstone = BlockStoreDeleteTombstone.inMemoryForTest(),
        lockState: () -> BlockStoreLockState = { BlockStoreLockState.QUALIFIED },
    ) = GoogleBlockStoreUStore(client, playAvailable, tombstone, lockState)

    private fun validKey() = "Abcdefghijklmnopqrstuv"

    private fun keyForIndex(index: Int): String =
        "Abcdefghijklmnopqrst" + index.toString(36).padStart(2, '0')

    private fun bytes(size: Int) = ByteArray(size) { (it + 1).toByte() }

    private class FakeClient : BlockStoreClientPort {
        val e2eeTask = FakeTask<Boolean>()
        var storeTask = FakeTask<Int>()
        var retrieveTask = FakeTask<BlockStoreRetrieveResponse>()
        val deleteTask = FakeTask<Boolean>()
        var e2eeCalls = 0
        var retrieveCalls = 0
        var deleteCalls = 0
        val storeRequests = mutableListOf<BlockStoreStoreRequest>()
        val retainedStoreValues = mutableListOf<ByteArray>()
        val retrieveRequests = mutableListOf<BlockStoreRetrieveRequest>()
        val deleteRequests = mutableListOf<BlockStoreDeleteRequest>()
        var storeLaunchHook: ((BlockStoreStoreRequest) -> Unit)? = null
        var throwSynchronouslyInStore = false

        override fun isEndToEndEncryptionAvailable(): BlockStoreTaskPort<Boolean> {
            e2eeCalls += 1
            return e2eeTask
        }

        override fun storeBytes(request: BlockStoreStoreRequest): BlockStoreTaskPort<Int> {
            // Deliberately retain the original array reference, as an SDK may.
            storeRequests += request
            retainedStoreValues += request.value
            storeLaunchHook?.invoke(request)
            if (throwSynchronouslyInStore) throw IllegalStateException("test-only synchronous failure")
            return storeTask
        }

        override fun retrieveBytes(request: BlockStoreRetrieveRequest): BlockStoreTaskPort<BlockStoreRetrieveResponse> {
            retrieveCalls += 1
            retrieveRequests += request
            return retrieveTask
        }

        override fun deleteBytes(request: BlockStoreDeleteRequest): BlockStoreTaskPort<Boolean> {
            deleteCalls += 1
            deleteRequests += request
            return deleteTask
        }
    }

    private class FakeTask<T> : BlockStoreTaskPort<T> {
        private var success: ((T) -> Unit)? = null
        private var failure: ((BlockStoreTaskFailure) -> Unit)? = null
        private var cancelled: (() -> Unit)? = null

        override fun addOnSuccessListener(listener: (T) -> Unit) {
            success = listener
        }

        override fun addOnFailureListener(listener: (BlockStoreTaskFailure) -> Unit) {
            failure = listener
        }

        override fun addOnCanceledListener(listener: () -> Unit) {
            cancelled = listener
        }

        fun succeed(value: T) {
            success?.invoke(value)
        }

        fun fail(value: BlockStoreTaskFailure) {
            failure?.invoke(value)
        }

        fun cancel() {
            cancelled?.invoke()
        }
    }
}
