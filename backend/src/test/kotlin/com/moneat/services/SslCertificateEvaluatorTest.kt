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

package com.moneat.services

import com.moneat.uptime.services.SslCertificateEvaluator
import io.mockk.every
import io.mockk.mockk
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Date
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SslCertificateEvaluatorTest {
    private val sslCertificateEvaluator = SslCertificateEvaluator()

    @Test
    fun `ssl certificate evaluator reports valid warning and expired certificates`() {
        val valid = sslCertificateEvaluator.evaluateCertificate(
            certificateExpiringInDays(90),
            responseTime = 25,
            warnDays = 30,
        )
        assertEquals(1, valid.status)
        assertTrue(valid.message.contains("valid"), valid.message)

        val warning = sslCertificateEvaluator.evaluateCertificate(
            certificateExpiringInDays(3),
            responseTime = 25,
            warnDays = 30,
        )
        assertEquals(0, warning.status)
        assertTrue(warning.message.contains("warning threshold"), warning.message)

        val expired = sslCertificateEvaluator.evaluateCertificate(
            certificateExpiringInDays(-2),
            responseTime = 25,
            warnDays = 30,
        )
        assertEquals(0, expired.status)
        assertTrue(expired.message.contains("expired"), expired.message)
    }

    @Test
    fun `ssl certificate result handles missing peer certificate`() {
        val socket = mockk<SSLSocket>()
        val session = mockk<SSLSession>()
        every { socket.session } returns session
        every { session.peerCertificates } returns emptyArray()

        val result = sslCertificateEvaluator.evaluateSocket(socket, responseTime = 25, warnDays = 30)

        assertEquals(0, result.status)
        assertTrue(result.message.contains("No SSL certificate"), result.message)
    }

    @Test
    fun `ssl certificate result handles unverified peer certificate`() {
        val socket = mockk<SSLSocket>()
        val session = mockk<SSLSession>()
        every { socket.session } returns session
        every { session.peerCertificates } throws SSLPeerUnverifiedException("unverified")

        val result = sslCertificateEvaluator.evaluateSocket(socket, responseTime = 25, warnDays = 30)

        assertEquals(0, result.status)
        assertTrue(result.message.contains("No SSL certificate"), result.message)
    }

    @Test
    fun `ssl certificate result evaluates peer certificate`() {
        val socket = mockk<SSLSocket>()
        val session = mockk<SSLSession>()
        every { socket.session } returns session
        every { session.peerCertificates } returns arrayOf(certificateExpiringInDays(90))

        val result = sslCertificateEvaluator.evaluateSocket(socket, responseTime = 25, warnDays = 30)

        assertEquals(1, result.status)
        assertTrue(result.message.contains("valid"), result.message)
    }

    @Test
    fun `ssl certificate evaluator reports recently expired certificate as expired`() {
        val result = sslCertificateEvaluator.evaluateCertificate(
            certificateExpiringIn(Duration.ofHours(-1)),
            responseTime = 25,
            warnDays = 30,
        )

        assertEquals(0, result.status)
        assertTrue(result.message.contains("expired"), result.message)
    }

    private fun certificateExpiringInDays(days: Long): X509Certificate {
        return certificateExpiringIn(Duration.ofDays(days))
    }

    private fun certificateExpiringIn(duration: Duration): X509Certificate {
        val cert = mockk<X509Certificate>()
        every { cert.notAfter } returns Date.from(java.time.Instant.now().plus(duration))
        return cert
    }
}
