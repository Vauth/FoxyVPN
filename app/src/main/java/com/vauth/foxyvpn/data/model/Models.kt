package com.vauth.foxyvpn.data.model

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

enum class LoginStepState { CREDENTIALS, TWO_FACTOR }

data class VpnProtocol(
    val name: String,
    val host: String = "",
    val port: Int = 0,
    val scheme: String = "",
    val templateString: String = "",
)

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

data class VpnCountry(
    val name: String,
    val code: String,
    val cities: List<VpnCity> = emptyList(),
)

data class ProxyCandidate(
    val host: String,
    val port: Int,
    val countryCode: String,
    val countryName: String,
    val cityCode: String = "",
) {
    val authority: String get() = "$host:$port"
}

data class Entitlement(
    val subscribed: Boolean,
    val uid: String,
    val maxBytes: Long?,
    val limitedBandwidth: Boolean,
)

data class RuntimeAuth(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
)
