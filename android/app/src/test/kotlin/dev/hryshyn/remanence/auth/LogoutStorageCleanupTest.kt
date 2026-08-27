package dev.hryshyn.remanence.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.core.data.network.AuthResult
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.data.storage.AccountStorageCleanupException
import dev.hryshyn.remanence.core.data.storage.AccountStorageRetention
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M2-P04: production logout ordering with account-scoped storage retention.
 * The owner is snapshotted from the live `local_account` BEFORE it clears;
 * normal logout purges ONLY that account's temp root while every durable
 * root of the same account and EVERYTHING of another account survives;
 * offline logout and cleanup failures must both complete teardown, with the
 * failure observable on [LogoutOutcome], and no call may ever target an
 * account whose ownership was not proved at snapshot time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogoutStorageCleanupTest {

    private val ownerA = UserId(UUID.fromString("5108f0a0-0000-7000-8000-00000000aa01"))
    private val ownerB = UserId(UUID.fromString("5108f0a0-0000-7000-8000-00000000bb02"))

    private lateinit var context: Context
    private lateinit var roots: AccountScopedFileRoots
    private lateinit var retention: AccountStorageRetention

    /** The single shared provider value standing in for `local_account`. */
    private lateinit var currentAccountRow: AtomicReference<UserId?>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        roots = AccountScopedFileRoots(context.filesDir)
        retention = AccountStorageRetention(roots)
        currentAccountRow = AtomicReference(ownerA)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "accounts").deleteRecursively()
    }

    private fun child(owner: UserId, root: AccountScopedFileRoots.ChildRoot): File =
        roots.child(owner, root)

    /** Materializes durable + temp bytes for BOTH accounts. */
    private fun seedAccountsMaterial() {
        listOf(
            ownerA to AccountScopedFileRoots.ChildRoot.TEMP,
            ownerA to AccountScopedFileRoots.ChildRoot.FINGERPRINTS,
            ownerA to AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT,
            ownerB to AccountScopedFileRoots.ChildRoot.TEMP,
            ownerB to AccountScopedFileRoots.ChildRoot.FINGERPRINTS,
        ).forEach { (owner, root) ->
            val dir = child(owner, root)
            dir.mkdirs()
            File(dir, "seed.bin").writeBytes(byteArrayOf(1))
        }
    }

    private fun useCase(
        trace: MutableList<String> = mutableListOf(),
        liveOwner: () -> UserId? = { currentAccountRow.get() },
        serverFails: Boolean = false,
        cleanupBehavior: ((UserId) -> Unit)? = { owner ->
            // Production-shaped cleanup: real retention against the root.
            retention.onLogout(owner)
            trace += "cleanup"
        },
    ): LogoutUseCase {
        val sortedTrace = trace
        return LogoutUseCase(
            serverLogout = ServerLogoutPort {
                sortedTrace += "server"
                if (serverFails) throw java.io.IOException("backend unreachable")
                AuthResult.Success(Unit, 204)
            },
            accessToken = CurrentAccessTokenPort { "bearer" },
            tokens = object : dev.hryshyn.remanence.session.SessionTokenPort {
                override fun readToken(): String? = null
                override fun saveToken(refreshToken: String) = Unit
                override fun clearToken() {
                    sortedTrace += "tokens"
                }
            },
            credentialSink = object : dev.hryshyn.remanence.core.data.network.SessionRotationSink {
                override fun rotate(accessToken: String, refreshToken: String) = Unit
                override fun clear() {
                    sortedTrace += "credentials"
                }
            },
            accounts = { sortedTrace += "accounts" },
            grants = { sortedTrace += "grants" },
            logoutOwnerSnapshot = LogoutOwnerSnapshotPort {
                val snap = liveOwner()
                    ?: error("logout without authenticated local account")
                sortedTrace += "snapshot:${snap.toRestString().take(13)}"
                snap
            },
            // The port receives exactly ONE owner - proven by the snapshot -
            // never a re-read from live state that could have flipped.
            tempStorageCleanup = LogoutTempCleanupPort { owner -> cleanupBehavior?.invoke(owner) },

        )
    }

    @Test
    fun logoutRunsSnapshotServerCredentialsCleanupThenAccountsAndGrants() = runBlocking {
        seedAccountsMaterial()
        val trace = mutableListOf<String>()
        val outcome = useCase(trace).logout()

        assertNull(outcome.tempStorageCleanupFailure)
        assertEquals(
            listOf(
                "snapshot:${ownerA.toRestString().take(13)}",
                "server",
                "credentials",
                "tokens",
                "cleanup",
                "accounts",
                "grants",
            ),
            trace,
        )
    }

    @Test
    fun normalLogoutRemovesOnlyThatOwnersTempDirectory() = runBlocking {
        seedAccountsMaterial()

        val outcome = useCase().logout()
        assertNull(outcome.tempStorageCleanupFailure)

        // A's temp purged...
        assertFalse(child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP).exists())
        // ...while every durable root of A and ALL of B survive intact.
        assertTrue(File(child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS), "seed.bin").exists())
        assertTrue(File(child(ownerA, AccountScopedFileRoots.ChildRoot.OUTBOX_CIPHERTEXT), "seed.bin").exists())
        assertTrue(child(ownerB, AccountScopedFileRoots.ChildRoot.TEMP).resolve("seed.bin").exists())
        assertTrue(File(child(ownerB, AccountScopedFileRoots.ChildRoot.FINGERPRINTS), "seed.bin").exists())
    }

    @Test
    fun offlineLogoutStillCompletesCleanupAccountsAndGrants() = runBlocking {
        seedAccountsMaterial()
        val trace = mutableListOf<String>()

        val outcome = useCase(trace, serverFails = true).logout()

        assertNull(outcome.tempStorageCleanupFailure)
        assertFalse(child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP).exists())
        assertEquals(listOf("server", "credentials", "tokens", "cleanup", "accounts", "grants"), trace.drop(1))
    }

    @Test
    fun cleanupFailureIsObservableButNeverBlocksTeardown() = runBlocking {
        seedAccountsMaterial()
        val trace = mutableListOf<String>()
        val boom = AccountStorageCleanupException("temp entry wedged")

        val outcome = useCase(trace, cleanupBehavior = { throw boom }).logout()

        assertEquals(boom, outcome.tempStorageCleanupFailure)
        assertEquals(listOf("accounts", "grants"), trace.takeLast(2))
    }

    @Test
    fun ownerChangeDuringLogoutNeverRedirectsTheCleanupTarget() = runBlocking {
        seedAccountsMaterial()
        val cleanedOwners = mutableListOf<UserId>()

        val useCase = useCase(
            cleanupBehavior = { owner ->
                cleanedOwners += owner
                // Simulate login-as-B landing mid-call: the live row flips and
                // even the whole root layout is reshaped - but the immutable
                // snapshot already fixed A as THE cleanup target.
                currentAccountRow.set(ownerB)
                child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS).deleteRecursively()
                retention.onLogout(owner)
            },
        )

        val outcome = useCase.logout()

        assertNull(outcome.tempStorageCleanupFailure)
        assertEquals(listOf(ownerA), cleanedOwners)
        // A's temp purged exactly once; B untouched everywhere.
        assertFalse(child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP).exists())
        assertTrue(child(ownerB, AccountScopedFileRoots.ChildRoot.TEMP).resolve("seed.bin").exists())
    }

    @Test
    fun unattributableOwnerSkipsCleanupInsteadOfGuessing() = runBlocking {
        seedAccountsMaterial()
        currentAccountRow.set(null)
        val cleaned = mutableListOf<UserId>()

        val outcome = useCase(
            cleanupBehavior = { owner ->
                cleaned += owner
                retention.onLogout(owner)
            },
        ).logout()

        assertNull(outcome.tempStorageCleanupFailure)
        assertTrue(cleaned.isEmpty())
        // Nothing anywhere was deleted on a guess.
        assertTrue(child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP).resolve("seed.bin").exists())
    }
}
