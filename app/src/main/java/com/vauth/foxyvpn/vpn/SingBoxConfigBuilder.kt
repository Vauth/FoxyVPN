package com.vauth.foxyvpn.vpn

import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.TunnelSettings
import com.vauth.foxyvpn.data.model.ProxyCandidate
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigBuilder {

    private fun proxyUsers(settings: TunnelSettings) =
        JSONArray()
            .put(
                JSONObject().apply {
                    put("username", settings.localProxyUsername)
                    put("password", settings.localProxyPassword)
                }
            )

    private val localNetworkAddressesIpv4 =
        listOf(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
        )

    private val localNetworkAddressesIpv6 =
        listOf(
            "fc00::/7",
            "fe80::/10",
        )

    private const val DEFAULT_TUN_IPV4 = "172.19.0.1/30"
    private const val DEFAULT_TUN_IPV6 = "fdfe:dcba:9876::1/126"
    private const val DEFAULT_TUN_DNS_IPV4 = "172.19.0.2"
    private const val DEFAULT_TUN_DNS_IPV6 = "fdfe:dcba:9876::2"

    fun build(
        primaryCandidate: ProxyCandidate,
        settings: TunnelSettings,
        bearerToken: String = "",
        appPackageName: String? = null,
        controllerSecret: String,
    ): String {
        require(
            primaryCandidate.port in 1..65_535 &&
                SettingsStore.isValidEdgeHost(primaryCandidate.host)
        ) {
            "Invalid MASQUE server address"
        }
        require(
            settings.socksPort in 1..65_535 && settings.socksPort != SettingsStore.CONTROLLER_PORT
        ) {
            "Local proxy port must be valid and must not use controller port ${SettingsStore.CONTROLLER_PORT}"
        }
        require(
            !settings.upstreamProxyEnabled ||
                (SettingsStore.isValidProxyHost(settings.upstreamProxyHost) &&
                    settings.upstreamProxyPort in 1..65_535)
        ) {
            "Set the upstream proxy address before connecting"
        }
        require(
            !settings.upstreamProxyEnabled ||
                SettingsStore.isValidUpstreamProxyCredentials(
                    settings.upstreamProxyType,
                    settings.upstreamProxyUsername,
                    settings.upstreamProxyPassword,
                )
        ) {
            "Invalid upstream proxy credentials"
        }
        require(
            !settings.upstreamProxyEnabled ||
                !SettingsStore.isLocalProxyLoop(
                    settings.upstreamProxyHost,
                    settings.upstreamProxyPort,
                    settings.socksPort,
                )
        ) {
            "Upstream proxy must not point to a FoxyVPN listener"
        }
        require(
            !settings.upstreamProxyEnabled ||
                settings.upstreamProxyType != SettingsStore.UpstreamProxyType.HTTP ||
                settings.httpVersion != SettingsStore.HttpVersion.HTTP_3
        ) {
            "HTTP/3 (QUIC) is not supported over an upstream HTTP proxy"
        }
        require(!settings.ipMode.hasIpv6 || settings.mtu >= 1280) {
            "IPv6 requires a tunnel MTU of at least 1280"
        }
        require(
            settings.dnsProvider == SettingsStore.DnsProvider.DIRECT_SYSTEM ||
                SettingsStore.isValidDohUrl(settings.effectiveDohUrl)
        ) {
            "DoH requires an HTTPS URL without credentials, query or fragment"
        }
        require(
            !settings.localProxyAuthEnabled ||
                SettingsStore.isValidLocalProxyCredentials(
                    settings.localProxyUsername,
                    settings.localProxyPassword,
                )
        ) {
            "Local proxy credentials require 1–255 UTF-8 bytes each and no colon in the username"
        }
        require(controllerSecret.isNotBlank()) { "Controller authentication is required" }
        require(
            settings.socksBindAddress == SettingsStore.DEFAULT_SOCKS_BIND_ADDRESS ||
                settings.socksBindAddress == SettingsStore.ALL_INTERFACES_BIND_ADDRESS,
        ) {
            "Local proxy listen address must be ${SettingsStore.DEFAULT_SOCKS_BIND_ADDRESS} or ${SettingsStore.ALL_INTERFACES_BIND_ADDRESS}"
        }
        require(bearerToken.isNotBlank()) { "A proxy pass is required" }
        val localNetworkAddresses = buildList {
            if (settings.ipMode.hasIpv4) addAll(localNetworkAddressesIpv4)
            if (settings.ipMode.hasIpv6) addAll(localNetworkAddressesIpv6)
        }
        val root = JSONObject()

        val log =
            JSONObject().apply {
                put("level", "info")
                put("timestamp", true)
            }
        root.put("log", log)

        val certificate = JSONObject().apply { put("store", "mozilla") }
        root.put("certificate", certificate)

        val dns = JSONObject()
        val dnsServers = JSONArray()

        dnsServers.put(
            JSONObject().apply {
                put("type", "local")
                put("tag", "local-dns")
            }
        )

        val hasBootstrapDns =
            settings.bootstrapDnsProvider != SettingsStore.DnsProvider.DIRECT_SYSTEM &&
                settings.effectiveBootstrapDohUrl.isNotBlank()
        val bootstrapDnsTag = "bootstrap-dns"

        if (hasBootstrapDns) {
            val bootstrapDohUri = URI(settings.effectiveBootstrapDohUrl)
            val bootstrapDnsServer = bootstrapDohUri.host.removeSurrounding("[", "]")
            val bootstrapDnsPath = bootstrapDohUri.rawPath.takeIf { it.isNotBlank() }
            val bootstrapDnsPort = bootstrapDohUri.port.takeIf { it > 0 }

            dnsServers.put(
                JSONObject().apply {
                    put("type", "https")
                    put("tag", bootstrapDnsTag)
                    put("server", bootstrapDnsServer)
                    if (bootstrapDnsPort != null && bootstrapDnsPort != 443) {
                        put("server_port", bootstrapDnsPort)
                    }
                    if (bootstrapDnsPath != null && bootstrapDnsPath != "/dns-query") {
                        put("path", bootstrapDnsPath)
                    }
                    if (bootstrapDnsServer.any { it.isLetter() }) {
                        put("domain_resolver", "local-dns")
                    }
                }
            )
        }

        val activeBootstrapDnsTag = if (hasBootstrapDns) bootstrapDnsTag else "local-dns"

        val finalDnsTag: String =
            when (settings.dnsProvider) {
                SettingsStore.DnsProvider.DIRECT_SYSTEM -> {
                    "local-dns"
                }
                else -> {
                    val remoteDohUri = URI(settings.effectiveDohUrl)
                    val remoteDnsServer = remoteDohUri.host.removeSurrounding("[", "]")
                    val remoteDnsPath = remoteDohUri.rawPath.takeIf { it.isNotBlank() }
                    val remoteDnsPort = remoteDohUri.port.takeIf { it > 0 }

                    dnsServers.put(
                        JSONObject().apply {
                            put("type", "https")
                            put("tag", "remote-dns")
                            put("server", remoteDnsServer)
                            if (remoteDnsPort != null && remoteDnsPort != 443) {
                                put("server_port", remoteDnsPort)
                            }
                            if (remoteDnsPath != null && remoteDnsPath != "/dns-query") {
                                put("path", remoteDnsPath)
                            }
                            if (remoteDnsServer.any { it.isLetter() }) {
                                put("domain_resolver", activeBootstrapDnsTag)
                            }
                            put("detour", "proxy-out")
                        }
                    )
                    "remote-dns"
                }
            }

        dns.put("servers", dnsServers)
        dns.put("final", finalDnsTag)
        settings.ipMode.dnsStrategy?.let {
            dns.put("strategy", it)
        }
        dns.put("disable_cache", false)
        root.put("dns", dns)

        val utlsFingerprint = settings.utlsFingerprint.identifier
        val httpClients =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("tag", "default-http-client")
                        put("engine", "go")
                        put("version", 2)
                        put(
                            "tls",
                            JSONObject().apply {
                                put(
                                    "utls",
                                    JSONObject().apply {
                                        put("enabled", true)
                                        put("fingerprint", utlsFingerprint)
                                    },
                                )
                            },
                        )
                    }
                )
            }
        root.put("http_clients", httpClients)

        val inbounds = JSONArray()

        if (!settings.proxyOnlyMode) {
            val hasTunIpv4 = settings.ipMode.hasIpv4
            val hasTunIpv6 = settings.ipMode.hasIpv6
            val tunInbound =
                JSONObject().apply {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("mtu", settings.mtu)
                    put(
                        "address",
                        JSONArray().apply {
                            if (hasTunIpv4) {
                                put(DEFAULT_TUN_IPV4)
                            }
                            if (hasTunIpv6) {
                                put(DEFAULT_TUN_IPV6)
                            }
                        },
                    )
                    put(
                        "dns_address",
                        JSONArray().apply {
                            if (hasTunIpv4) {
                                put(DEFAULT_TUN_DNS_IPV4)
                            }
                            if (hasTunIpv6) {
                                put(DEFAULT_TUN_DNS_IPV6)
                            }
                        },
                    )
                    put("auto_route", true)
                    put("strict_route", true)
                    if (settings.bypassLocalNetwork && localNetworkAddresses.isNotEmpty()) {
                        put("route_exclude_address", JSONArray(localNetworkAddresses))
                    }
                    if (settings.splitTunnelMode == SettingsStore.SplitTunnelMode.INCLUDE) {
                        val included =
                            settings.includedApps.filter { it.isNotBlank() && it != appPackageName }
                        require(included.isNotEmpty()) {
                            "Select at least one app to include in the VPN"
                        }
                        put("include_package", JSONArray(included.sorted()))
                    } else {
                        val excluded =
                            settings.excludedApps.filter { it.isNotBlank() }.toMutableSet()
                        if (!appPackageName.isNullOrBlank()) excluded.add(appPackageName)
                        if (excluded.isNotEmpty())
                            put("exclude_package", JSONArray(excluded.sorted()))
                    }
                    put(
                        "platform",
                        JSONObject().apply {
                            put("http_proxy", JSONObject().apply { put("enabled", false) })
                        },
                    )
                }
            inbounds.put(tunInbound)
        }

        val mixedInbound =
            JSONObject().apply {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", settings.socksBindAddress)
                put("listen_port", settings.socksPort)
                put("reuse_addr", true)
                if (settings.localProxyAuthEnabled) put("users", proxyUsers(settings))
            }
        inbounds.put(mixedInbound)

        root.put("inbounds", inbounds)

        val outbounds = JSONArray()

        val direct =
            JSONObject().apply {
                put("type", "direct")
                put("tag", "direct-out")
            }
        outbounds.put(direct)

        val hasUpstreamDetour =
            settings.upstreamProxyEnabled && settings.upstreamProxyHost.isNotBlank()
        val targetVersion = settings.httpVersion.versionNumber

        run {
            val candidate = primaryCandidate
            val tag = "proxy-out"

            val customEdge = settings.effectiveCustomEdgeAddress
            val serverAddress = customEdge ?: candidate.host

            val nodeOutbound =
                JSONObject().apply {
                    put("type", "http")
                    put("tag", tag)
                    put("server", serverAddress)
                    put("server_port", candidate.port)
                    put("domain_resolver", activeBootstrapDnsTag)
                    put("dynamic_bearer_token", true)
                    put("version", targetVersion)
                    put("disable_version_fallback", true)
                    if (targetVersion == 2) {
                        put("max_concurrent_streams", settings.maxConcurrentStreams)
                        put("stream_receive_window", "16 MB")
                        put("connection_receive_window", "32 MB")
                        put("idle_timeout", "30s")
                        put("keep_alive_period", "15s")
                    }

                    val tls =
                        JSONObject().apply {
                            put("enabled", true)
                            put("server_name", candidate.host)
                            if (targetVersion != 3) {
                                put(
                                    "utls",
                                    JSONObject().apply {
                                        put("enabled", true)
                                        put("fingerprint", settings.utlsFingerprint.identifier)
                                    },
                                )
                                when (settings.tlsFragmentMode) {
                                    SettingsStore.TlsFragmentMode.OFF -> {}
                                    SettingsStore.TlsFragmentMode.RECORD -> {
                                        put("record_fragment", true)
                                    }
                                    SettingsStore.TlsFragmentMode.PACKET -> {
                                        put("fragment", true)
                                        put(
                                            "fragment_fallback_delay",
                                            "${settings.fragmentFallbackDelay}ms",
                                        )
                                    }
                                    SettingsStore.TlsFragmentMode.COMBINED -> {
                                        put("fragment", true)
                                        put("record_fragment", true)
                                        put(
                                            "fragment_fallback_delay",
                                            "${settings.fragmentFallbackDelay}ms",
                                        )
                                    }
                                }
                            }
                        }
                    put("tls", tls)

                    if (hasUpstreamDetour) {
                        put("detour", "upstream-detour")
                    }
                }
            outbounds.put(nodeOutbound)
        }

        if (hasUpstreamDetour) {
            val detour =
                JSONObject().apply {
                    if (settings.upstreamProxyType == SettingsStore.UpstreamProxyType.HTTP) {
                        put("dynamic_bearer_token", false)

                        put("version", 1)
                    }
                    put(
                        "type",
                        if (settings.upstreamProxyType == SettingsStore.UpstreamProxyType.HTTP)
                            "http"
                        else "socks",
                    )
                    put("tag", "upstream-detour")
                    put("server", settings.upstreamProxyHost)
                    put("server_port", settings.upstreamProxyPort)
                    put("domain_resolver", activeBootstrapDnsTag)
                    if (settings.upstreamProxyType == SettingsStore.UpstreamProxyType.SOCKS5)
                        put("version", "5")
                    if (settings.upstreamProxyUsername.isNotEmpty()) {
                        put("username", settings.upstreamProxyUsername)
                        put("password", settings.upstreamProxyPassword)
                    }
                }
            outbounds.put(detour)
        }

        root.put("outbounds", outbounds)

        val route =
            JSONObject().apply {
                put(
                    "rules",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("protocol", "dns")
                                put("action", "hijack-dns")
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("port", 53)
                                put("action", "hijack-dns")
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("port", 853)
                                put("action", "reject")
                            }
                        )
                        if (settings.bypassLocalNetwork && localNetworkAddresses.isNotEmpty()) {
                            put(
                                JSONObject().apply {
                                    put("ip_cidr", JSONArray(localNetworkAddresses))
                                    put("outbound", "direct-out")
                                }
                            )
                        }
                        if (!settings.ipMode.hasIpv6) {
                            put(
                                JSONObject().apply {
                                    put("ip_version", 6)
                                    put("action", "reject")
                                }
                            )
                        }
                        if (!settings.ipMode.hasIpv4) {
                            put(
                                JSONObject().apply {
                                    put("ip_version", 4)
                                    put("action", "reject")
                                }
                            )
                        }
                        put(
                            JSONObject().apply {
                                put(
                                    "domain",
                                    JSONArray().apply {
                                        put("mtalk.google.com")
                                        for (i in 1..8) {
                                            put("alt$i-mtalk.google.com")
                                        }
                                    },
                                )
                                put("override_port", 443)
                                put("outbound", "proxy-out")
                            }
                        )
                        put(
                            JSONObject().apply {
                                put(
                                    "port",
                                    JSONArray().apply {
                                        put(5228)
                                        put(5229)
                                        put(5230)
                                    },
                                )
                                put("override_port", 443)
                                put("outbound", "proxy-out")
                            }
                        )
                        put(JSONObject().apply { put("outbound", "proxy-out") })
                    },
                )
                put("final", "proxy-out")
                put("auto_detect_interface", true)
                put("find_process", false)
                put("default_domain_resolver", activeBootstrapDnsTag)
                put("default_http_client", "default-http-client")
            }
        root.put("route", route)

        val experimental =
            JSONObject().apply {
                put("cache_file", JSONObject().apply { put("enabled", false) })
                put(
                    "clash_api",
                    JSONObject().apply {
                        put(
                            "external_controller",
                            "${SettingsStore.DEFAULT_SOCKS_BIND_ADDRESS}:${SettingsStore.CONTROLLER_PORT}",
                        )
                        put("secret", controllerSecret)
                        put("default_mode", "Rule")
                    },
                )
            }
        root.put("experimental", experimental)

        return root.toString(2)
    }
}
