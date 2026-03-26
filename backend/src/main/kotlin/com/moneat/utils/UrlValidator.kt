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

        val allowInternal = EnvConfig.SelfHost.enabled

        for (addr in addresses) {
            val normalized = unwrapMappedIPv4(addr)
            if (isBlockedAddress(normalized, allowInternal)) {
                throw SsrfException(
                    "URL resolves to a blocked address: $host"
                )
            }
        }
        return addresses.toList()
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
            val isMapped = bytes.size == 16 &&
                bytes.slice(0..9).all { it == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() &&
                bytes[11] == 0xFF.toByte()
            if (isMapped) {
                val ipv4Bytes = bytes.sliceArray(12..15)
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
        if (bytes.size != 4) return false
        return bytes[0] == 169.toByte() &&
            bytes[1] == 254.toByte() &&
            bytes[2] == 169.toByte() &&
            bytes[3] == 254.toByte()
    }

    private fun isIPv6UniqueLocal(addr: InetAddress): Boolean {
        if (addr !is Inet6Address) return false
        val firstByte = addr.address[0].toInt() and 0xFF
        // fc00::/7 means first byte is 0xFC or 0xFD
        return firstByte == 0xFC || firstByte == 0xFD
    }
}
