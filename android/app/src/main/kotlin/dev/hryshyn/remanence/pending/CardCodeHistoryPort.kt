package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.HistoryResult
import dev.hryshyn.remanence.core.data.network.LocatorStatus
import dev.hryshyn.remanence.core.data.network.OutgoingHistoryPort
import dev.hryshyn.remanence.core.data.network.OutgoingPage
import dev.hryshyn.remanence.core.model.UserId

/** ADR-020: History shows the card code again, read with this account's own key. */
class CardCodeHistoryPort(
    private val delegate: OutgoingHistoryPort,
    private val openCode: suspend (sealedB64: String, capsuleId: String) -> String?,
) : OutgoingHistoryPort by delegate {
    override suspend fun outgoing(owner: UserId, cursor: String?): HistoryResult<OutgoingPage> {
        val result = delegate.outgoing(owner, cursor)
        if (result !is HistoryResult.Loaded) return result
        return HistoryResult.Loaded(
            result.value.copy(
                items = result.value.items.map { item ->
                    val sealed = item.cardCodeSealedB64 ?: return@map item
                    val code = openCode(sealed, item.capsuleId) ?: return@map item
                    item.copy(code = code, locatorStatus = LocatorStatus.ACTIVE)
                },
            ),
        )
    }
}
