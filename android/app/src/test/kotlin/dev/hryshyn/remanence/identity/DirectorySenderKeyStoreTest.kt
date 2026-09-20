package dev.hryshyn.remanence.identity

import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IP-01: the trusted sender display identity must come only from the
 * authenticated directory, must be bound to the exact verified user id, and
 * must degrade to null (unverified) for missing/reused/unavailable lookups.
 */
class DirectorySenderKeyStoreTest {

    private val userA = UserId.parseRest("0198f0a0-0000-7000-8000-0000000000a1")
    private val userB = UserId.parseRest("0198f0a0-0000-7000-8000-0000000000b1")

    @Test
    fun resolvesDirectoryHandleForRequestedUser() = runBlocking {
        val store = store { requested ->
            assertEquals(userA, requested)
            DirectorySenderKeyStore.ResolvedDisplayIdentity(userA, NormalizedHandle.parse("alice"))
        }

        assertEquals("alice", store.senderDisplayHandle(userA)?.value)
    }

    @Test
    fun missingLookupOrMissingIdentityYieldsNullUnverified() = runBlocking {
        assertNull(store(null).senderDisplayHandle(userA))
        assertNull(store { null }.senderDisplayHandle(userA))
    }

    @Test
    fun reusedHandleBoundToAnotherUserIsRejected() = runBlocking {
        // The directory now associates the requested handle with a different
        // user id: it must never be returned for the requested verified sender.
        val store = store {
            DirectorySenderKeyStore.ResolvedDisplayIdentity(userB, NormalizedHandle.parse("alice"))
        }

        assertNull(store.senderDisplayHandle(userA))
    }

    @Test
    fun lookupFailureNeverFailsTheCallerAndStaysUnverified() = runBlocking {
        val store = store { error("directory offline") }

        assertNull(store.senderDisplayHandle(userA))
    }

    private fun store(
        lookup: (suspend (UserId) -> DirectorySenderKeyStore.ResolvedDisplayIdentity?)?,
    ): DirectorySenderKeyStore = DirectorySenderKeyStore(
        directoryFetch = { error("display lookup must not fetch key bundles") },
        ownAccount = { null },
        userHandleLookup = lookup,
    )
}
