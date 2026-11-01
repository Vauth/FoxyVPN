package com.vauth.foxyvpn.data

import com.vauth.foxyvpn.data.model.ProxyCandidate

internal fun preferredConnectionCandidates(
    candidates: List<ProxyCandidate>,
    saved: ProxyCandidate?,
): List<ProxyCandidate> {
    if (saved == null) {
        val recommended =
            candidates.filter { it.countryCode.equals(RECOMMENDED_COUNTRY_CODE, true) }
        if (recommended.isNotEmpty()) return recommended
        val firstCountry = candidates.firstOrNull()?.countryCode ?: return emptyList()
        return candidates.filter { it.countryCode.equals(firstCountry, true) }
    }
    val country = candidates.filter { it.countryCode.equals(saved.countryCode, true) }
    country
        .firstOrNull { it.host.equals(saved.host, true) && it.port == saved.port }
        ?.let {
            return listOf(it)
        }
    val city = country.filter { it.cityCode.equals(saved.cityCode, true) }
    return city.ifEmpty { country }
}
