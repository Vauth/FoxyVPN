package com.vauth.foxyvpn.data.model

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class VpnProtocol(val name: String, val host: String = "", val port: Int = 0)

data class VpnServerNode(
    val hostname: String,
    val port: Int = 0,
    val quarantined: Boolean = false,
    val protocols: List<VpnProtocol> = emptyList(),
)

data class VpnCity(
    val name: String,
    val code: String,
    val servers: List<VpnServerNode> = emptyList(),
)

data class VpnCountry(val name: String, val code: String, val cities: List<VpnCity> = emptyList())

data class ProxyCandidate(
    val host: String,
    val port: Int,
    val countryCode: String,
    val countryName: String,
    val cityCode: String = "",
) {
    val authority: String
        get() = "${if (':' in host) "[$host]" else host}:$port"
}

data class Entitlement(
    val maxBytes: Long?,
    val limitedBandwidth: Boolean,
    val quotaRemaining: Long? = null,
)

data class RuntimeAuth(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString() = "RuntimeAuth(expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

const val RECOMMENDED_COUNTRY_CODE = "REC"
const val RECOMMENDED_COUNTRY_NAME = "Recommended"

fun normalizeCountryName(code: String, rawName: String): String {
    return if (code.equals(RECOMMENDED_COUNTRY_CODE, ignoreCase = true) ||
        rawName.equals("Recomended", ignoreCase = true) ||
        rawName.equals(RECOMMENDED_COUNTRY_NAME, ignoreCase = true)
    ) {
        RECOMMENDED_COUNTRY_NAME
    } else {
        rawName
    }
}

val VpnCountry.displayName: String
    get() = normalizeCountryName(code, name)

val ProxyCandidate.displayName: String
    get() = normalizeCountryName(countryCode, countryName)

fun countryCodeToEmojiFlag(countryCode: String): String {
    if (countryCode.equals(RECOMMENDED_COUNTRY_CODE, ignoreCase = true) || countryCode.length != 2) return ""
    val upper = countryCode.uppercase()
    val first = upper[0]
    val second = upper[1]
    if (first !in 'A'..'Z' || second !in 'A'..'Z') return ""

    val firstCodePoint = 0x1F1E6 + (first - 'A')
    val secondCodePoint = 0x1F1E6 + (second - 'A')

    return String(Character.toChars(firstCodePoint)) + String(Character.toChars(secondCodePoint))
}

val VpnCountry.emojiFlag: String
    get() = countryCodeToEmojiFlag(code)
val ProxyCandidate.emojiFlag: String
    get() = countryCodeToEmojiFlag(countryCode)

