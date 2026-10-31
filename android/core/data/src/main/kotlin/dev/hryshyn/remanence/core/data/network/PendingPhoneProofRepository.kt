package dev.hryshyn.remanence.core.data.network

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * ADR-020: gifts are found by a proven phone number. Starting a proof looks the
 * same whether or not the number has a gift; a wrong code and an unknown proof
 * fail the same way.
 */
enum class PhoneProofFailure { NETWORK, RATE_LIMITED, WRONG_CODE, SIGN_IN, INVALID_PHONE, SERVER }

data class CardGift(
    val deliveryId: String,
    val capsuleId: String,
    val deliveryTargetId: String,
    val proofId: String,
    val claimExpiresAt: Long,
    val publishStatementB64: String,
    val publishSignature69B64: String,
    val cardSealB64: String,
)

sealed interface PhoneProofStart {
    data class Started(val proofId: String) : PhoneProofStart
    data class Failed(val reason: PhoneProofFailure) : PhoneProofStart
}

sealed interface PhoneProofCheck {
    data class Gifts(val gifts: List<CardGift>) : PhoneProofCheck
    data class Failed(val reason: PhoneProofFailure) : PhoneProofCheck
}

@Serializable
private data class StartRequestDto(
    val phone: String,
    @SerialName("sms_text_version") val smsTextVersion: String,
)

@Serializable
private data class StartResponseDto(
    @SerialName("proof_id") val proofId: String,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
private data class CheckRequestDto(val code: String)

@Serializable
private data class GiftDto(
    @SerialName("delivery_id") val deliveryId: String,
    @SerialName("capsule_id") val capsuleId: String,
    @SerialName("delivery_target_id") val deliveryTargetId: String,
    @SerialName("proof_id") val proofId: String,
    @SerialName("claim_expires_at") val claimExpiresAt: Long,
    @SerialName("publish_statement_b64") val publishStatementB64: String,
    @SerialName("publish_signature_69_b64") val publishSignature69B64: String,
    @SerialName("card_seal_b64") val cardSealB64: String,
)

@Serializable
private data class CheckResponseDto(val gifts: List<GiftDto>)

class PendingPhoneProofRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun start(phoneE164: String, accessToken: String): PhoneProofStart {
        val body = NetworkJson.encodeToString(StartRequestDto.serializer(), StartRequestDto(phoneE164, SMS_TEXT_VERSION))
        return try {
            client.executeResponseWithCallLifetime(request("v2/phone-proofs", body, accessToken)) { response ->
                when (response.code) {
                    200 -> PhoneProofStart.Started(
                        NetworkJson.decodeFromString<StartResponseDto>(response.text()).proofId,
                    )
                    else -> PhoneProofStart.Failed(failureOf(response.code))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PhoneProofStart.Failed(PhoneProofFailure.NETWORK)
        } catch (_: kotlinx.serialization.SerializationException) {
            PhoneProofStart.Failed(PhoneProofFailure.SERVER)
        }
    }

    suspend fun check(proofId: String, code: String, accessToken: String): PhoneProofCheck {
        val body = NetworkJson.encodeToString(CheckRequestDto.serializer(), CheckRequestDto(code))
        return try {
            client.executeResponseWithCallLifetime(
                request("v2/phone-proofs/$proofId/check", body, accessToken),
            ) { response ->
                when (response.code) {
                    200 -> PhoneProofCheck.Gifts(
                        NetworkJson.decodeFromString<CheckResponseDto>(response.text()).gifts.map {
                            CardGift(
                                it.deliveryId, it.capsuleId, it.deliveryTargetId, it.proofId, it.claimExpiresAt,
                                it.publishStatementB64, it.publishSignature69B64, it.cardSealB64,
                            )
                        },
                    )
                    400 -> PhoneProofCheck.Failed(PhoneProofFailure.WRONG_CODE)
                    else -> PhoneProofCheck.Failed(failureOf(response.code))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            PhoneProofCheck.Failed(PhoneProofFailure.NETWORK)
        } catch (_: kotlinx.serialization.SerializationException) {
            PhoneProofCheck.Failed(PhoneProofFailure.SERVER)
        }
    }

    private fun request(path: String, body: String, accessToken: String): Request = Request.Builder()
        .url(baseUrl.resolve(path))
        .header("Accept", "application/json; charset=utf-8")
        .header("Cache-Control", "no-store")
        .header("Authorization", "Bearer $accessToken")
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    private fun Response.text(): String = body.string()

    private fun failureOf(status: Int): PhoneProofFailure = when (status) {
        400, 422 -> PhoneProofFailure.INVALID_PHONE
        401, 403 -> PhoneProofFailure.SIGN_IN
        429 -> PhoneProofFailure.RATE_LIMITED
        else -> PhoneProofFailure.SERVER
    }

    companion object {
        const val SMS_TEXT_VERSION = "v1"

        fun create(baseUrl: ApiBaseUrl): PendingPhoneProofRepository =
            PendingPhoneProofRepository(HttpClientFactory.create(), baseUrl)
    }
}
