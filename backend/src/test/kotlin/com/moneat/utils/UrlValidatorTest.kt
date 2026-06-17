// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package com.moneat.utils

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlValidatorTest {

    private inline fun withSelfHosted(
        value: String?,
        block: () -> Unit
    ) {
        val prev = System.getProperty("SELF_HOSTED")
        if (value != null) {
            System.setProperty("SELF_HOSTED", value)
        } else {
            System.clearProperty("SELF_HOSTED")
        }
        try {
            block()
        } finally {
            if (prev != null) {
                System.setProperty("SELF_HOSTED", prev)
            } else {
                System.clearProperty("SELF_HOSTED")
            }
        }
    }

    @Test
    fun `public URL passes validation`() {
        // Use IP-based URL to avoid external DNS lookups in tests
        UrlValidator.validateExternalUrl("https://93.184.216.34/api")
    }

    @Test
    fun `loopback is blocked when not self-hosted`() = withSelfHosted(null) {
        assertFailsWith<UrlValidator.SsrfException> {
            UrlValidator.validateExternalUrl("http://127.0.0.1/test")
        }
    }

    @Test
    fun `loopback is allowed when self-hosted`() = withSelfHosted("true") {
        UrlValidator.validateExternalUrl("http://127.0.0.1:9090/test")
    }

    @Test
    fun `metadata address 169_254_169_254 is always blocked`() = withSelfHosted("true") {
        assertFailsWith<UrlValidator.SsrfException> {
            UrlValidator.validateExternalUrl("http://169.254.169.254/latest/meta-data/")
        }
    }

    @Test
    fun `RFC1918 10-x is blocked when not self-hosted`() {
        val addr = InetAddress.getByName("10.0.0.1") // NOSONAR - must use real RFC1918 IP to test blocking
        assertTrue(UrlValidator.isBlockedAddress(addr, false))
    }

    @Test
    fun `RFC1918 172_16 is blocked when not self-hosted`() {
        val addr = InetAddress.getByName("172.16.0.1") // NOSONAR - must use real RFC1918 IP to test blocking
        assertTrue(UrlValidator.isBlockedAddress(addr, false))
    }

    @Test
    fun `RFC1918 192_168 is blocked when not self-hosted`() {
        val addr = InetAddress.getByName("192.168.1.1") // NOSONAR - must use real RFC1918 IP to test blocking
        assertTrue(UrlValidator.isBlockedAddress(addr, false))
    }

    @Test
    fun `RFC1918 ranges allowed when self-hosted`() {
        val addr10 = InetAddress.getByName("10.0.0.1") // NOSONAR - must use real RFC1918 IP to test allow
        val addr172 = InetAddress.getByName("172.16.0.1") // NOSONAR - must use real RFC1918 IP to test allow
        val addr192 = InetAddress.getByName("192.168.1.1") // NOSONAR - must use real RFC1918 IP to test allow
        assertFalse(UrlValidator.isBlockedAddress(addr10, true))
        assertFalse(UrlValidator.isBlockedAddress(addr172, true))
        assertFalse(UrlValidator.isBlockedAddress(addr192, true))
    }

    @Test
    fun `IPv6 ULA fc00 is blocked when not self-hosted`() {
        val addr = InetAddress.getByName("fd12:3456:789a::1")
        assertTrue(UrlValidator.isBlockedAddress(addr, false))
    }

    @Test
    fun `IPv6 ULA allowed when self-hosted`() {
        val addr = InetAddress.getByName("fd12:3456:789a::1")
        assertFalse(UrlValidator.isBlockedAddress(addr, true))
    }

    @Test
    fun `unwrapMappedIPv4 converts mapped address`() {
        // Build a true Inet6Address for ::ffff:192.168.1.1 so the unwrap
        // branch is actually exercised (InetAddress.getByName returns Inet4Address).
        val bytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(),
            192.toByte(), 168.toByte(), 1, 1
        )
        val mapped = Inet6Address.getByAddress(null, bytes, 0)
        val unwrapped = UrlValidator.unwrapMappedIPv4(mapped)
        assertTrue(unwrapped is Inet4Address)
        assertEquals("192.168.1.1", unwrapped.hostAddress) // NOSONAR - expected result of unwrapping ::ffff:192.168.1.1
    }

    @Test
    fun `unwrapMappedIPv4 returns native IPv6 unchanged`() {
        val native6 = InetAddress.getByName("2001:db8::1")
        val result = UrlValidator.unwrapMappedIPv4(native6)
        assertTrue(result is Inet6Address)
    }

    @Test
    fun `invalid URL throws SsrfException`() {
        assertFailsWith<UrlValidator.SsrfException> {
            UrlValidator.validateExternalUrl("not a url")
        }
    }

    @Test
    fun `URL with no host throws SsrfException`() {
        assertFailsWith<UrlValidator.SsrfException> {
            UrlValidator.validateExternalUrl("file:///etc/passwd")
        }
    }

    @Test
    fun `JDBC URL without verifiable host fails closed`() {
        assertFailsWith<UrlValidator.SsrfException> {
            UrlValidator.validateExternalJdbcUrl("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=example.com)))")
        }
    }

    @Test
    fun `Oracle thin JDBC host is validated`() = withSelfHosted(null) {
        assertFailsWith<UrlValidator.SsrfException> {
            UrlValidator.validateExternalJdbcUrl("jdbc:oracle:thin:@127.0.0.1:1521:orcl")
        }
    }

    @Test
    fun `public JDBC host returns pinned connection string`() {
        val jdbcUrl = "jdbc:oracle:thin:@93.184.216.34:1521:orcl"
        assertEquals(jdbcUrl, UrlValidator.validatedExternalJdbcUrl(jdbcUrl))
    }

    @Test
    fun `local JDBC URLs remain allowed`() {
        val jdbcUrl = "jdbc:h2:mem:test_db"
        assertEquals(jdbcUrl, UrlValidator.validatedExternalJdbcUrl(jdbcUrl))
    }

    @Test
    fun `link-local address is always blocked`() {
        val addr = InetAddress.getByName("169.254.1.1")
        assertTrue(UrlValidator.isBlockedAddress(addr, true))
    }

    @Test
    fun `public address is never blocked`() {
        val addr = InetAddress.getByName("8.8.8.8")
        assertFalse(UrlValidator.isBlockedAddress(addr, false))
    }

    @Test
    fun `blank host target fails closed`() {
        assertFailsWith<UrlValidator.SsrfException> {
            UrlValidator.validateExternalHost("   ")
        }
    }

    @Test
    fun `host target accepts bracketed and port-qualified addresses`() {
        assertEquals("93.184.216.34", UrlValidator.validateExternalHost("[93.184.216.34]:443").first().hostAddress)
        assertEquals("93.184.216.34", UrlValidator.validateExternalHost("93.184.216.34:443").first().hostAddress)
    }

    @Test
    fun `JDBC validation wrapper accepts public host`() {
        UrlValidator.validateExternalJdbcUrl("jdbc:postgresql://93.184.216.34:5432/app")
    }

    @Test
    fun `JDBC parser handles double slash oracle host`() {
        val jdbcUrl = "jdbc:oracle:thin:@//93.184.216.34:1521/service"
        assertEquals(jdbcUrl, UrlValidator.validatedExternalJdbcUrl(jdbcUrl))
    }

    @Test
    fun `JDBC parser handles nested protocol host`() {
        val jdbcUrl = "jdbc:custom:@tcp://93.184.216.34:5432/app"
        assertEquals(jdbcUrl, UrlValidator.validatedExternalJdbcUrl(jdbcUrl))
    }

    @Test
    fun `JDBC parser rejects empty bracketed host`() {
        assertFailsWith<UrlValidator.SsrfException> {
            UrlValidator.validatedExternalJdbcUrl("jdbc:postgresql://[]:5432/app")
        }
    }

    @Test
    fun `JDBC parser brackets unbracketed IPv6 host when pinning`() {
        val pinned = UrlValidator.validatedExternalJdbcUrl("jdbc:postgresql://2606:4700:4700::1111/app")
        assertTrue(pinned.startsWith("jdbc:postgresql://["))
        assertTrue(pinned.contains("]/app"))
    }
}
