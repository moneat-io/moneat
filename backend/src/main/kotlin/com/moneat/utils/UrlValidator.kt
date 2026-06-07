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

import com.moneat.config.EnvConfig
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

object UrlValidator {

    private const val IPV6_ADDRESS_BYTE_LENGTH = 16
    private const val IPV4_MAPPED_ZERO_RANGE_END = 9
    private const val IPV4_MAPPED_FF_FIRST_IDX = 10
    private const val IPV4_MAPPED_FF_SECOND_IDX = 11
    private const val IPV4_IN_IPV6_START_IDX = 12
    private const val IPV4_IN_IPV6_END_IDX = 15
    private const val IPV4_BYTE_LENGTH = 4
    private const val IPV4_LAST_BYTE_IDX = 3
    private const val BYTE_MASK_UNSIGNED = 0xFF
    private const val IPV6_ULA_FC_PREFIX = 0xFC
    private const val IPV6_ULA_FD_PREFIX = 0xFD

    class SsrfException(message: String) : IllegalArgumentException(message)

    /**
     * Validates that a URL does not target blocked network addresses.
     *
     * Blocks: loopback, link-local, cloud metadata (169.254.169.254),
     * RFC 1918 private ranges (10/8, 172.16/12, 192.168/16), and
     * IPv6 ULA (fc00::/7). IPv4-mapped IPv6 addresses are unwrapped
     * before checking.
     *
     * When SELF_HOSTED is enabled, loopback and private ranges are
     * allowed so operators can monitor co-located services.
     */
    fun validateExternalUrl(url: String) {
        validateAndResolve(url)
    }

    /**
     * Validates a non-URL hostname target such as TCP, DNS, ping, or TLS checks.
     */
    fun validateExternalHost(host: String): List<InetAddress> {
        val normalizedHost = normalizeHostTarget(host)
            ?: throw SsrfException("Target host is empty")

        val addresses = try {
            InetAddress.getAllByName(normalizedHost)
        } catch (e: UnknownHostException) {
            throw SsrfException("Cannot resolve host: $normalizedHost")
        }

        validateResolvedAddresses(normalizedHost, addresses)
        return addresses.toList()
    }

    /**
     * Validates JDBC URLs that include a network host. In-memory and local file JDBC URLs do not
     * perform network egress and are left to the JDBC driver to validate.
     */
    fun validateExternalJdbcUrl(jdbcUrl: String) {
        val host = extractJdbcHost(jdbcUrl) ?: return
        validateExternalHost(host)
    }

    /**
     * Validates that a URL does not target blocked addresses and
     * returns the validated [InetAddress] list so callers can pin
     * connections to those addresses, avoiding DNS-rebinding.
     */
    fun validateAndResolve(url: String): List<InetAddress> {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw SsrfException("Invalid URL: $url")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || (scheme != "http" && scheme != "https")) {
            throw SsrfException("Disallowed scheme: $url")
        }

        val host = uri.host
            ?: throw SsrfException("URL has no host: $url")

        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            throw SsrfException("Cannot resolve host: $host")
        }

        validateResolvedAddresses(host, addresses)
        return addresses.toList()
    }

    private fun validateResolvedAddresses(
        host: String,
        addresses: Array<InetAddress>
    ) {
        val allowInternal = EnvConfig.SelfHost.enabled
        for (addr in addresses) {
            val normalized = unwrapMappedIPv4(addr)
            if (isBlockedAddress(normalized, allowInternal)) {
                throw SsrfException(
                    "Target resolves to a blocked address: $host"
                )
            }
        }
    }

    /**
     * Validates that an already-resolved [InetAddress] is safe.
     * Use this to re-check addresses during redirects or when
     * a custom DNS resolver re-resolves a hostname.
     */
    fun validateAddress(addr: InetAddress) {
        val normalized = unwrapMappedIPv4(addr)
        val allowInternal = EnvConfig.SelfHost.enabled
        if (isBlockedAddress(normalized, allowInternal)) {
            throw SsrfException(
                "Resolved to a blocked address: ${addr.hostAddress}"
            )
        }
    }

    /**
     * Unwrap IPv4-mapped IPv6 addresses (::ffff:x.x.x.x) to their
     * underlying IPv4 address for consistent checking.
     */
    internal fun unwrapMappedIPv4(addr: InetAddress): InetAddress {
        if (addr is Inet6Address) {
            val bytes = addr.address
            // Check for ::ffff:x.x.x.x pattern (bytes 0-9 = 0, 10-11 = 0xff)
            val isMapped = bytes.size == IPV6_ADDRESS_BYTE_LENGTH &&
                bytes.slice(0..IPV4_MAPPED_ZERO_RANGE_END).all { it == 0.toByte() } &&
                bytes[IPV4_MAPPED_FF_FIRST_IDX] == 0xFF.toByte() &&
                bytes[IPV4_MAPPED_FF_SECOND_IDX] == 0xFF.toByte()
            if (isMapped) {
                val ipv4Bytes = bytes.sliceArray(IPV4_IN_IPV6_START_IDX..IPV4_IN_IPV6_END_IDX)
                return Inet4Address.getByAddress(ipv4Bytes)
            }
        }
        return addr
    }

    internal fun isBlockedAddress(
        addr: InetAddress,
        allowInternal: Boolean
    ): Boolean {
        // Always block metadata and link-local
        if (isMetadataAddress(addr)) return true
        if (addr.isLinkLocalAddress) return true
        if (addr.isAnyLocalAddress) return true

        // Block loopback unless self-hosted
        if (addr.isLoopbackAddress && !allowInternal) return true

        // Block private/site-local ranges unless self-hosted
        if (!allowInternal) {
            if (addr.isSiteLocalAddress) return true
            if (isIPv6UniqueLocal(addr)) return true
        }

        return false
    }

    private fun isMetadataAddress(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != IPV4_BYTE_LENGTH) return false
        return bytes[0] == 169.toByte() &&
            bytes[1] == 254.toByte() &&
            bytes[2] == 169.toByte() &&
            bytes[IPV4_LAST_BYTE_IDX] == 254.toByte()
    }

    private fun isIPv6UniqueLocal(addr: InetAddress): Boolean {
        if (addr !is Inet6Address) return false
        val firstByte = addr.address[0].toInt() and BYTE_MASK_UNSIGNED
        // fc00::/7 means first byte is 0xFC or 0xFD
        return firstByte == IPV6_ULA_FC_PREFIX || firstByte == IPV6_ULA_FD_PREFIX
    }

    private fun normalizeHostTarget(host: String): String? {
        val trimmed = host.trim()
        if (trimmed.isBlank()) return null
        return authorityHost(trimmed)
    }

    private fun extractJdbcHost(jdbcUrl: String): String? {
        val withoutJdbc = jdbcUrl.trim().removePrefix("jdbc:")
        val authorityStart = withoutJdbc.indexOf("://")
        if (authorityStart < 0) return null

        val authority = withoutJdbc
            .substring(authorityStart + "://".length)
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore(";")
        return authorityHost(authority)
    }

    private fun authorityHost(authority: String): String? {
        val withoutUserInfo = authority.substringAfterLast("@")
        if (withoutUserInfo.isBlank()) return null
        if (withoutUserInfo.startsWith("[")) {
            return withoutUserInfo.substringAfter("[").substringBefore("]").takeIf { it.isNotBlank() }
        }

        val colonCount = withoutUserInfo.count { it == ':' }
        if (colonCount == 1) {
            return withoutUserInfo.substringBefore(":").takeIf { it.isNotBlank() }
        }
        return withoutUserInfo.takeIf { it.isNotBlank() }
    }
}
