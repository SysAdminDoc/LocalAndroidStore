package com.sysadmin.lasstore.data

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Locale

data class Socks5ProxyConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
)

fun validateSocks5Proxy(enabled: Boolean, rawHost: String, port: Int): String? {
    if (!enabled) return null
    val host = rawHost.trim()
    if (host.isBlank()) return "Enter the SOCKS5 host or Orbot address."
    if (host.length > 253 || host.contains('/') || host.contains(':') && host.count { it == ':' } > 1) {
        return "Enter a hostname or IPv4 address, not a URL."
    }
    if (port !in 1..65535) return "Enter a SOCKS5 port from 1 to 65535."
    return null
}

fun AppSettings.socks5ProxyConfig(): Socks5ProxyConfig? =
    Socks5ProxyConfig(
        enabled = socks5ProxyEnabled,
        host = socks5ProxyHost.trim(),
        port = socks5ProxyPort,
    ).takeIf { validateSocks5Proxy(it.enabled, it.host, it.port) == null && it.enabled }

/** A dynamic selector keeps existing OkHttp clients usable after Settings changes. */
class DynamicSocks5ProxySelector(
    private val provider: () -> Socks5ProxyConfig?,
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        requireNotNull(uri) { "URI must not be null" }
        val config = provider() ?: return listOf(Proxy.NO_PROXY)
        if (!config.enabled || uri.scheme?.lowercase(Locale.US) !in setOf("http", "https")) {
            return listOf(Proxy.NO_PROXY)
        }
        return listOf(
            Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(config.host, config.port),
            ),
        )
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
}
