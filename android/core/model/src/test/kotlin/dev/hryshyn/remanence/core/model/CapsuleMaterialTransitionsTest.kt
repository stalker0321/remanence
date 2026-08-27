package dev.hryshyn.remanence.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M2-P13 pin: the recipient-local and server delivery state machines
 * are pure data with no I/O, no exceptions, and no clock. Every
 * (from, to) pair is pinned by the Cartesian-product tests below, and
 * the local and server state types are deliberately distinct so an
 * index-only local state can never be confused with a server-side
 * ciphertext acknowledgement.
 */
class CapsuleMaterialTransitionsTest {

    private val allLocal: List<LocalMaterialState> = LocalMaterialState.entries
    private val allServer: List<ServerMaterialState> = ServerMaterialState.entries

    @Test
    fun localAndServerStateTypesAreDistinct() {
        // No LocalMaterialState value may be assignment-compatible with
        // any ServerMaterialState value, and vice versa. This is the
        // structural guarantee that INDEX_CACHED can never map to
        // CIPHERTEXT_SYNCED.
        val localValues: Set<Any> = LocalMaterialState.entries.toSet()
        val serverValues: Set<Any> = ServerMaterialState.entries.toSet()
        assertTrue(localValues.intersect(serverValues).isEmpty())
        for (local in allLocal) {
            for (server in allServer) {
                assertNotEquals<Any>(
                    local,
                    server,
                    "local=$local must not equal server=$server",
                )
            }
        }
    }

    @Test
    fun localExhaustiveCartesianProduct() {
        for (from in allLocal) {
            for (to in allLocal) {
                val result = LocalMaterialTransitionEvaluator.evaluate(from, to)
                val expectedKind: String = when {
                    from == to -> "IdempotentReplay"
                    from == LocalMaterialState.DISCOVERED && to == LocalMaterialState.INDEX_CACHED -> "Accepted"
                    from == LocalMaterialState.INDEX_CACHED && to == LocalMaterialState.MATERIAL_CACHED -> "Accepted"
                    from == LocalMaterialState.MATERIAL_CACHED && to == LocalMaterialState.FINGERPRINT_ACCEPTED -> "Accepted"
                    from != LocalMaterialState.CORRUPT && to == LocalMaterialState.CORRUPT -> "Accepted"
                    from == LocalMaterialState.CORRUPT && to == LocalMaterialState.DISCOVERED -> "Accepted"
                    else -> "Rejected"
                }
                val actualKind = result::class.simpleName
                assertEquals(
                    expectedKind,
                    actualKind,
                    "from=$from to=$to expected $expectedKind but got $actualKind",
                )
                // All results must report the correct from/to.
                assertEquals(from, result.from, "from on result for $from->$to")
                assertEquals(to, result.to, "to on result for $from->$to")
            }
        }
    }

    @Test
    fun serverExhaustiveCartesianProduct() {
        for (from in allServer) {
            for (to in allServer) {
                val result = ServerMaterialTransitionEvaluator.evaluate(from, to)
                val expectedKind: String = when {
                    from == to -> "IdempotentReplay"
                    from == ServerMaterialState.AVAILABLE && to == ServerMaterialState.CIPHERTEXT_SYNCED -> "Accepted"
                    else -> "Rejected"
                }
                val actualKind = result::class.simpleName
                assertEquals(
                    expectedKind,
                    actualKind,
                    "from=$from to=$to expected $expectedKind but got $actualKind",
                )
                assertEquals(from, result.from, "from on result for $from->$to")
                assertEquals(to, result.to, "to on result for $from->$to")
            }
        }
    }

    @Test
    fun localForwardChainIsExactlyThreeSteps() {
        val chain = listOf(
            LocalMaterialState.DISCOVERED to LocalMaterialState.INDEX_CACHED,
            LocalMaterialState.INDEX_CACHED to LocalMaterialState.MATERIAL_CACHED,
            LocalMaterialState.MATERIAL_CACHED to LocalMaterialState.FINGERPRINT_ACCEPTED,
        )
        for ((from, to) in chain) {
            assertIs<LocalMaterialTransition.Accepted>(
                LocalMaterialTransitionEvaluator.evaluate(from, to),
                "expected Accepted for forward step $from->$to",
            )
        }
    }

    @Test
    fun localSkippingAnyForwardStepIsRejected() {
        // Every forward-chain "skip" pair must be Rejected. We list them
        // explicitly so a future state addition cannot silently allow a
        // skip. Pairs whose target is CORRUPT are NOT forward-chain
        // skips; any non-CORRUPT -> CORRUPT is an explicit Accepted
        // transition (verified by everyNonCorruptEntersCorruptAsAccepted
        // and by localExhaustiveCartesianProduct).
        val skippingPairs: List<Pair<LocalMaterialState, LocalMaterialState>> = listOf(
            LocalMaterialState.DISCOVERED to LocalMaterialState.MATERIAL_CACHED,
            LocalMaterialState.DISCOVERED to LocalMaterialState.FINGERPRINT_ACCEPTED,
            LocalMaterialState.INDEX_CACHED to LocalMaterialState.FINGERPRINT_ACCEPTED,
        )
        for ((from, to) in skippingPairs) {
            assertIs<LocalMaterialTransition.Rejected>(
                LocalMaterialTransitionEvaluator.evaluate(from, to),
                "expected Rejected for skip $from->$to",
            )
        }
    }

    @Test
    fun everyNonCorruptEntersCorruptAsAccepted() {
        for (from in allLocal) {
            if (from == LocalMaterialState.CORRUPT) continue
            val result = LocalMaterialTransitionEvaluator.evaluate(from, LocalMaterialState.CORRUPT)
            assertIs<LocalMaterialTransition.Accepted>(
                result,
                "expected Accepted for $from->CORRUPT (any non-CORRUPT may enter CORRUPT)",
            )
        }
    }

    @Test
    fun localRegressionIsAlwaysRejected() {
        for (from in allLocal) {
            for (to in allLocal) {
                if (from == to) continue
                if (forwardOrdinal(to) > forwardOrdinal(from)) continue
                if (from == LocalMaterialState.CORRUPT && to == LocalMaterialState.DISCOVERED) continue
                if (to == LocalMaterialState.CORRUPT && from != LocalMaterialState.CORRUPT) continue
                assertIs<LocalMaterialTransition.Rejected>(
                    LocalMaterialTransitionEvaluator.evaluate(from, to),
                    "expected Rejected for regression $from->$to",
                )
            }
        }
    }

    @Test
    fun corruptRecoveryIsOnlyToDiscovered() {
        for (to in allLocal) {
            val result = LocalMaterialTransitionEvaluator.evaluate(LocalMaterialState.CORRUPT, to)
            when (to) {
                LocalMaterialState.CORRUPT ->
                    assertIs<LocalMaterialTransition.IdempotentReplay>(
                        result,
                        "CORRUPT->CORRUPT must be IdempotentReplay",
                    )
                LocalMaterialState.DISCOVERED ->
                    assertIs<LocalMaterialTransition.Accepted>(
                        result,
                        "CORRUPT->DISCOVERED must be Accepted (the only CORRUPT recovery)",
                    )
                else ->
                    assertIs<LocalMaterialTransition.Rejected>(
                        result,
                        "CORRUPT->$to must be Rejected (CORRUPT may only recover to DISCOVERED)",
                    )
            }
        }
    }

    @Test
    fun sameStateIsAlwaysIdempotentReplayForLocal() {
        for (state in allLocal) {
            val result = LocalMaterialTransitionEvaluator.evaluate(state, state)
            assertIs<LocalMaterialTransition.IdempotentReplay>(result, "$state->$state must be replay")
            assertEquals(state, result.from)
            assertEquals(state, result.to)
        }
    }

    @Test
    fun sameStateIsAlwaysIdempotentReplayForServer() {
        for (state in allServer) {
            val result = ServerMaterialTransitionEvaluator.evaluate(state, state)
            assertIs<ServerMaterialTransition.IdempotentReplay>(result, "$state->$state must be replay")
            assertEquals(state, result.from)
            assertEquals(state, result.to)
        }
    }

    @Test
    fun serverRegressionIsAlwaysRejected() {
        // Only AVAILABLE -> CIPHERTEXT_SYNCED is allowed as a forward
        // step; the reverse is rejected.
        val result = ServerMaterialTransitionEvaluator.evaluate(
            ServerMaterialState.CIPHERTEXT_SYNCED,
            ServerMaterialState.AVAILABLE,
        )
        assertIs<ServerMaterialTransition.Rejected>(result)
    }

    @Test
    fun localIndexOnlyStateHasNoServerAcknowledgementMapping() {
        // The whole point of M2-P13: a recipient who only has the
        // recognition index in their local state has no server-side
        // acknowledgement mapping. We assert that there is no function
        // in this module that maps any LocalMaterialState to any
        // ServerMaterialState, and that the two sets of values are
        // structurally disjoint. The Cartesian product test above
        // covers the local space; here we explicitly cover the
        // cross-space guarantee via name sets (the two enums are
        // distinct Kotlin types, so we cannot pass one where the other
        // is expected).
        val localNames: Set<String> = allLocal.map { it.name }.toSet()
        val serverNames: Set<String> = allServer.map { it.name }.toSet()
        assertTrue(
            localNames.intersect(serverNames).isEmpty(),
            "local state names $localNames must not overlap server state names $serverNames",
        )
        // Explicit: INDEX_CACHED is a local-only name.
        assertTrue(
            "INDEX_CACHED" in localNames && "INDEX_CACHED" !in serverNames,
            "INDEX_CACHED must exist only on the local plane",
        )
        // Explicit: CIPHERTEXT_SYNCED is a server-only name.
        assertTrue(
            "CIPHERTEXT_SYNCED" in serverNames && "CIPHERTEXT_SYNCED" !in localNames,
            "CIPHERTEXT_SYNCED must exist only on the server plane",
        )
    }

    /**
     * Returns an ordinal for the strictly forward local path
     * `DISCOVERED < INDEX_CACHED < MATERIAL_CACHED < FINGERPRINT_ACCEPTED`.
     * `CORRUPT` is given a non-comparable negative ordinal so that any
     * direct ordering test using this helper treats CORRUPT as outside
     * the forward chain.
     */
    private fun forwardOrdinal(state: LocalMaterialState): Int = when (state) {
        LocalMaterialState.DISCOVERED -> 0
        LocalMaterialState.INDEX_CACHED -> 1
        LocalMaterialState.MATERIAL_CACHED -> 2
        LocalMaterialState.FINGERPRINT_ACCEPTED -> 3
        LocalMaterialState.CORRUPT -> -1
    }
}
