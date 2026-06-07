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

package com.moneat.uptime.services

import com.moneat.uptime.models.CheckResult
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

internal class SslCertificateEvaluator {
    fun evaluateSocket(
        socket: SSLSocket,
        responseTime: Int,
        warnDays: Long,
    ): CheckResult {
        val cert = try {
            socket.session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
            ?: return CheckResult(0, responseTime, 0, "No SSL certificate found")
        return evaluateCertificate(cert, responseTime, warnDays)
    }

    fun evaluateCertificate(
        cert: X509Certificate,
        responseTime: Int,
        warnDays: Long,
    ): CheckResult {
        val expiryDate = cert.notAfter.toInstant()
        val now = Instant.now()
        if (!expiryDate.isAfter(now)) {
            val daysExpired = ChronoUnit.DAYS.between(expiryDate, now).coerceAtLeast(1)
            return CheckResult(0, responseTime, 0, "SSL certificate expired $daysExpired days ago")
        }

        val daysUntilExpiry = ChronoUnit.DAYS.between(now, expiryDate)

        return when {
            daysUntilExpiry < warnDays ->
                CheckResult(
                    0,
                    responseTime,
                    0,
                    "SSL certificate expires in $daysUntilExpiry days (warning threshold: $warnDays)"
                )

            else ->
                CheckResult(1, responseTime, 0, "SSL certificate valid (expires in $daysUntilExpiry days)")
        }
    }
}
