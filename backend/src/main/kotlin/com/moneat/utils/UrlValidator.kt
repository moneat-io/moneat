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
import java.net.InetAddress
import java.net.URI

object UrlValidator {

    class SsrfException(message: String) : IllegalArgumentException(message)

    /**
     * Validates that a URL does not target the host's own
     * infrastructure. Blocks loopback (localhost / 127.x), the cloud
     * metadata endpoint (169.254.169.254), and link-local ranges.
     *
     * Private-network IPs (10.x, 172.16-31.x, 192.168.x) are
     * intentionally **allowed** so users can monitor services on
     * their own internal networks.
     *
     * When SELF_HOSTED is enabled, loopback addresses are also
     * allowed so operators can monitor co-located services.
     */
    fun validateExternalUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw SsrfException("Invalid URL: $url")
        }

        val host = uri.host
            ?: throw SsrfException("URL has no host: $url")

        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw SsrfException("Cannot resolve host: $host")
        }

        val allowLoopback = EnvConfig.SelfHost.enabled

        for (addr in addresses) {
            if (isBlockedAddress(addr, allowLoopback)) {
                throw SsrfException(
                    "URL resolves to a blocked address: $host"
                )
            }
        }
    }

    private fun isBlockedAddress(
        addr: InetAddress,
        allowLoopback: Boolean
    ): Boolean {
        if (isMetadataAddress(addr)) return true
        if (addr.isLinkLocalAddress) return true
        if (addr.isAnyLocalAddress) return true
        if (!allowLoopback && addr.isLoopbackAddress) return true
        return false
    }

    private fun isMetadataAddress(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 4) return false
        // 169.254.169.254 — cloud metadata endpoint (AWS, GCP, Azure)
        return bytes[0] == 169.toByte() &&
            bytes[1] == 254.toByte() &&
            bytes[2] == 169.toByte() &&
            bytes[3] == 254.toByte()
    }
}
