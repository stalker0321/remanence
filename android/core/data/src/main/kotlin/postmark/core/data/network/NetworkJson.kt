package postmark.core.data.network

import kotlinx.serialization.json.Json

internal val NetworkJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
    coerceInputValues = false
    encodeDefaults = true
}
