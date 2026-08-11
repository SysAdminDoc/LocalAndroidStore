package com.sysadmin.lasstore.data

import java.net.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5ProxyTest {
    @Test
    fun disabledProxyUsesDirectConnectionAndEnabledProxyUsesUnresolvedSocksAddress() {
        val disabled = DynamicSocks5ProxySelector { null }
        assertEquals(Proxy.NO_PROXY, disabled.select(URI("https://github.com")).single())

        val enabled = DynamicSocks5ProxySelector {
            Socks5ProxyConfig(enabled = true, host = "127.0.0.1", port = 9050)
        }
        val proxy = enabled.select(URI("https://api.github.com")).single()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as java.net.InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(9050, address.port)
        assertTrue(address.isUnresolved)
    }

    @Test
    fun validationRejectsUrlsAndInvalidPortsButAllowsDisabledDefaults() {
        assertNull(validateSocks5Proxy(false, "", -1))
        assertEquals(
            "Enter a SOCKS5 port from 1 to 65535.",
            validateSocks5Proxy(true, "127.0.0.1", 0),
        )
        assertEquals(
            "Enter a hostname or IPv4 address, not a URL.",
            validateSocks5Proxy(true, "socks5://127.0.0.1", 9050),
        )
    }
}
