package postmark.core.data.network

import kotlinx.serialization.Serializable

@Serializable
internal data class HealthResponseDto(val status: String)
