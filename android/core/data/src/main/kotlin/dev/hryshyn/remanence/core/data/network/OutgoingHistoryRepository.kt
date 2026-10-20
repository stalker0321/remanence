package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer

/** Metadata only. No content source, crypto material or presentation grant. */
enum class OutgoingStatus { DRAFT, ABORTED, READY, OPENED, REVOKED, PENDING, CLAIMED, CANCELLED, EXPIRED, UNKNOWN }
enum class LocatorStatus { ACTIVE, RETIRED_OPEN, RETIRED_TERMINAL, UNAVAILABLE }
enum class HistoryCancelAction { REVOKE_V1, CANCEL_PENDING_V2 }
data class OutgoingItem(
    val capsuleId: String,
    val protocolVersion: Int,
    val deliveryId: String?,
    val status: OutgoingStatus,
    val createdAt: String,
    val publishedAt: String?,
    val expiresAt: String?,
    val firstOpenedAt: String?,
    val revokedAt: String?,
    val recipientHandle: String?,
    val recipientHint: String?,
    val isSelf: Boolean?,
    val locatorStatus: LocatorStatus,
    val code: String?,
    val cancelAction: HistoryCancelAction?,
    val cancelDeadline: String?,
    val detailsAvailable: Boolean = true,
) {
    val key: String get() = "$protocolVersion:$capsuleId"
}
data class OutgoingPage(val items: List<OutgoingItem>, val nextCursor: String?)
data class RecentRecipient(val userId: String, val handle: String, val lastSentAt: String)
enum class HistoryFailure { NETWORK, SIGN_IN, UNAVAILABLE, INVALID_RESPONSE, SERVER }
sealed interface HistoryResult<out T> {
    data class Loaded<T>(val value: T) : HistoryResult<T>
    data class Failed(val reason: HistoryFailure) : HistoryResult<Nothing>
}
enum class HistoryCancelResult { SUCCEEDED, CONFLICT, UNAVAILABLE, UNKNOWN, SIGN_IN }

interface OutgoingHistoryPort {
    suspend fun outgoing(owner: UserId, cursor: String? = null): HistoryResult<OutgoingPage>
    suspend fun recent(owner: UserId): HistoryResult<List<RecentRecipient>>
    suspend fun cancel(owner: UserId, item: OutgoingItem): HistoryCancelResult
}

/**
 * T368-history-v1. Uses the existing authenticated stack and exact owner lease.
 * No disk cache, fallback fixtures, endpoint probing, or automatic mutation retry.
 */
class OutgoingHistoryRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
    private val leases: SessionRequestLeaseProvider,
    private val revoke: CapsuleRevokePort,
) : OutgoingHistoryPort {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun outgoing(owner: UserId, cursor: String?): HistoryResult<OutgoingPage> {
        if (cursor != null && !validCursor(cursor)) return HistoryResult.Failed(HistoryFailure.INVALID_RESPONSE)
        val url = baseUrl.resolve("v1/capsules/outgoing").newBuilder().addQueryParameter("limit", "20")
        cursor?.let { url.addQueryParameter("cursor", it) }
        return get(owner, Request.Builder().url(url.build())) { raw ->
            val dto = json.decodeFromString<PageDto>(raw)
            require(dto.items.size <= 20 && dto.has_more == (dto.next_cursor != null))
            require(dto.next_cursor == null || (validCursor(dto.next_cursor) && dto.next_cursor != cursor))
            require(!dto.has_more || dto.items.isNotEmpty())
            val items = dto.items.map(::mapItem)
            require(items.map { it.key }.distinct().size == items.size)
            OutgoingPage(items, dto.next_cursor)
        }
    }

    override suspend fun recent(owner: UserId): HistoryResult<List<RecentRecipient>> =
        get(owner, Request.Builder().url(baseUrl.resolve("v1/recipients/recent").newBuilder()
            .addQueryParameter("limit", "8").build())) { raw ->
            val dto = json.decodeFromString<RecentDto>(raw)
            require(dto.items.size <= 8)
            dto.items.map {
                require(validId(it.user_id) && it.user_id != owner.toRestString())
                require(validHandle(it.handle) && validTime(it.last_sent_at))
                RecentRecipient(it.user_id, it.handle, it.last_sent_at)
            }.also { require(it.map { item -> item.userId }.distinct().size == it.size) }
        }

    private suspend fun <T> get(
        owner: UserId,
        builder: Request.Builder,
        decode: (String) -> T,
    ): HistoryResult<T> {
        val lease = leases.capture()?.takeIf { it.ownerUserId == owner }
            ?: return HistoryResult.Failed(HistoryFailure.SIGN_IN)
        val request = leases.tag(builder.header("Accept", "application/json")
            .header("Cache-Control", "no-store").get().build(), lease)
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                if (!leases.isLive(lease)) return@executeResponseWithCallLifetime HistoryResult.Failed(HistoryFailure.SIGN_IN)
                if (response.code != 200) return@executeResponseWithCallLifetime HistoryResult.Failed(when (response.code) {
                    401, 403 -> HistoryFailure.SIGN_IN
                    404, 501, 503 -> HistoryFailure.UNAVAILABLE
                    else -> HistoryFailure.SERVER
                })
                require(response.body.contentType()?.let { it.type == "application" && it.subtype == "json" } == true)
                val raw = response.body.readHistoryBody() ?: throw IllegalArgumentException()
                val value = decode(raw)
                if (leases.isLive(lease)) HistoryResult.Loaded(value) else HistoryResult.Failed(HistoryFailure.SIGN_IN)
            }
        } catch (_: IOException) {
            HistoryResult.Failed(if (leases.isLive(lease)) HistoryFailure.NETWORK else HistoryFailure.SIGN_IN)
        } catch (_: SerializationException) {
            HistoryResult.Failed(HistoryFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            HistoryResult.Failed(HistoryFailure.INVALID_RESPONSE)
        }
    }

    override suspend fun cancel(owner: UserId, item: OutgoingItem): HistoryCancelResult {
        val lease = leases.capture()?.takeIf { it.ownerUserId == owner } ?: return HistoryCancelResult.SIGN_IN
        val token = leases.accessTokenFor(lease) ?: return HistoryCancelResult.SIGN_IN
        if (!item.detailsAvailable || item.cancelAction == null) return HistoryCancelResult.UNAVAILABLE
        val outcome = when (item.cancelAction) {
            HistoryCancelAction.REVOKE_V1 -> {
                if (item.protocolVersion != 1 || item.status != OutgoingStatus.READY || item.firstOpenedAt != null)
                    return HistoryCancelResult.UNAVAILABLE
                when (val result = revoke.revoke(CapsuleId.parseRest(item.capsuleId), token)) {
                    is CapsuleRevokeResult.Success -> HistoryCancelResult.SUCCEEDED
                    is CapsuleRevokeResult.Failure -> when (result.reason) {
                        CapsuleRevokeFailure.CAPSULE_STATE_INVALID, CapsuleRevokeFailure.WINDOW_EXPIRED -> HistoryCancelResult.CONFLICT
                        CapsuleRevokeFailure.CAPSULE_NOT_FOUND -> HistoryCancelResult.UNAVAILABLE
                        CapsuleRevokeFailure.AUTH_INVALID -> HistoryCancelResult.SIGN_IN
                        else -> HistoryCancelResult.UNKNOWN
                    }
                }
            }
            HistoryCancelAction.CANCEL_PENDING_V2 -> {
                if (item.protocolVersion != 2 || item.deliveryId == null ||
                    item.status !in setOf(OutgoingStatus.DRAFT, OutgoingStatus.PENDING))
                    return HistoryCancelResult.UNAVAILABLE
                val request = leases.tag(Request.Builder()
                    .url(baseUrl.resolve("v2/pending-deliveries/${item.deliveryId}/cancel"))
                    .header("Accept", "application/json").header("Cache-Control", "no-store")
                    .post(ByteArray(0).toRequestBody(null)).build(), lease)
                try {
                    client.executeResponseWithCallLifetime(request) { response ->
                        when (response.code) {
                            200 -> {
                                if (response.body.contentType()?.subtype != "json") HistoryCancelResult.UNKNOWN else {
                                    val raw = response.body.readHistoryBody()
                                    val dto = raw?.let { json.decodeFromString<CancelDto>(it) }
                                    if (dto?.delivery_id == item.deliveryId && dto.state == "CANCELLED")
                                        HistoryCancelResult.SUCCEEDED else HistoryCancelResult.UNKNOWN
                                }
                            }
                            401, 403 -> HistoryCancelResult.SIGN_IN
                            409 -> HistoryCancelResult.CONFLICT
                            404 -> HistoryCancelResult.UNAVAILABLE
                            else -> HistoryCancelResult.UNKNOWN
                        }
                    }
                } catch (_: IOException) { HistoryCancelResult.UNKNOWN }
                catch (_: SerializationException) { HistoryCancelResult.UNKNOWN }
            }
        }
        return if (leases.isLive(lease)) outcome else HistoryCancelResult.SIGN_IN
    }

    private fun mapItem(dto: ItemDto): OutgoingItem {
        require(validId(dto.capsule_id) && dto.protocol_version in 1..2)
        require(validTime(dto.created_at))
        listOf(dto.published_at, dto.expires_at, dto.first_opened_at, dto.revoked_at, dto.cancel_deadline)
            .filterNotNull().forEach { require(validTime(it)) }
        require(dto.recipient.handle == null || validHandle(dto.recipient.handle))
        require(dto.recipient.hint == null || (dto.recipient.hint.length <= 256 && dto.recipient.hint.none { it.isISOControl() }))
        if (dto.protocol_version == 1) {
            require(dto.delivery_id == null && dto.recipient.user_id?.let(::validId) == true && dto.recipient.is_self != null)
        } else {
            require(dto.delivery_id?.let(::validId) == true)
            require(dto.recipient.user_id == null && dto.recipient.handle == null && dto.recipient.is_self == null)
            require(dto.published_at == null && dto.first_opened_at == null && dto.revoked_at == null && dto.cancel_deadline == null)
        }
        val status = OutgoingStatus.entries.find { it.name == dto.status } ?: OutgoingStatus.UNKNOWN
        val action = HistoryCancelAction.entries.find { it.name == dto.cancel_action }
        val locator = LocatorStatus.entries.find { it.name == dto.locator.status }
        val known = status != OutgoingStatus.UNKNOWN && (dto.cancel_action == null || action != null) && locator != null
        val applicable = if (dto.protocol_version == 1)
            status in setOf(OutgoingStatus.DRAFT, OutgoingStatus.ABORTED, OutgoingStatus.READY, OutgoingStatus.OPENED, OutgoingStatus.REVOKED)
        else status in setOf(OutgoingStatus.DRAFT, OutgoingStatus.PENDING, OutgoingStatus.CLAIMED, OutgoingStatus.CANCELLED, OutgoingStatus.EXPIRED)
        val available = known && applicable
        val codeAllowed = available && locator == LocatorStatus.ACTIVE &&
            status in setOf(OutgoingStatus.READY, OutgoingStatus.PENDING, OutgoingStatus.CLAIMED)
        if (codeAllowed) require(dto.locator.code?.matches(Regex("[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{5}")) == true)
        val safeAction = action?.takeIf {
            available && when (it) {
                HistoryCancelAction.REVOKE_V1 -> dto.protocol_version == 1 && status == OutgoingStatus.READY &&
                    dto.first_opened_at == null && dto.cancel_deadline != null
                HistoryCancelAction.CANCEL_PENDING_V2 -> dto.protocol_version == 2 &&
                    status in setOf(OutgoingStatus.DRAFT, OutgoingStatus.PENDING)
            }
        }
        return OutgoingItem(dto.capsule_id, dto.protocol_version, dto.delivery_id,
            if (available) status else OutgoingStatus.UNKNOWN, dto.created_at, dto.published_at,
            dto.expires_at, dto.first_opened_at, dto.revoked_at, dto.recipient.handle, dto.recipient.hint,
            dto.recipient.is_self,
            if (!available || (locator == LocatorStatus.ACTIVE && !codeAllowed)) LocatorStatus.UNAVAILABLE else locator!!,
            dto.locator.code.takeIf { codeAllowed }, safeAction, dto.cancel_deadline, available)
    }

    private fun validId(value: String) = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    private fun validHandle(value: String) = runCatching { NormalizedHandle.parse(value).value == value }.getOrDefault(false)
    private fun validTime(value: String) = value.length <= 40 && runCatching {
        OffsetDateTime.parse(value).offset == ZoneOffset.UTC
    }.getOrDefault(false)
    private fun validCursor(value: String) = value.isNotEmpty() && value.length <= 1024 && value.all { it.code in 0..127 }
}

private fun ResponseBody.readHistoryBody(): String? {
    val limit = 512 * 1024L
    if (contentLength() > limit) return null
    val buffer = Buffer()
    val source = source()
    while (true) {
        val read = source.read(buffer, limit + 1 - buffer.size)
        if (buffer.size > limit) return null
        if (read == -1L) return buffer.readUtf8()
    }
}

@Serializable private data class PageDto(val items: List<ItemDto>, val has_more: Boolean, val next_cursor: String?)
@Serializable private data class ItemDto(
    val capsule_id: String, val protocol_version: Int, val delivery_id: String?, val status: String,
    val created_at: String, val published_at: String?, val expires_at: String?, val first_opened_at: String?,
    val revoked_at: String?, val recipient: RecipientDto, val locator: LocatorDto,
    val cancel_action: String?, val cancel_deadline: String?,
)
@Serializable private data class RecipientDto(val user_id: String?, val handle: String?, val hint: String?, val is_self: Boolean?)
@Serializable private data class LocatorDto(val status: String, val code: String?)
@Serializable private data class RecentDto(val items: List<RecentDtoItem>)
@Serializable private data class RecentDtoItem(val user_id: String, val handle: String, val last_sent_at: String)
@Serializable private data class CancelDto(val delivery_id: String, val state: String)
