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

import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.services.SslCertificateEvaluator
import com.moneat.uptime.services.UptimeCheckExecutor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Date
import java.util.UUID
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class UptimeCheckExecutorTest {
    private val executor = UptimeCheckExecutor()
    private val sslCertificateEvaluator = SslCertificateEvaluator()

    private fun monitor(
        type: String,
        url: String? = null,
        hostname: String? = null,
        port: Int? = null,
        dbConnectionString: String? = null
    ): UptimeMonitorData {
        val now = Clock.System.now()
        return UptimeMonitorData(
            id = UUID.randomUUID(),
            organizationId = 1,
            organizationResourceId = "11111111-1111-4111-8111-111111111111",
            name = "test-$type",
            type = type,
            active = true,
            url = url,
            hostname = hostname,
            port = port,
            dbConnectionString = dbConnectionString,
            intervalSeconds = 60,
            timeoutSeconds = 1,
            retries = 0,
            retryIntervalSeconds = 1,
            status = "pending",
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `executeCheck returns pending for push monitors`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "push"))
            assertEquals(2, result.status)
            assertTrue(result.message.contains("don't perform active checks"))
        }

    @Test
    fun `executeCheck returns unknown type error for unsupported monitor types`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "unsupported"))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("Unknown monitor type"))
        }

    @Test
    fun `executeCheck fails http monitor without url`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "http", url = null))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No URL configured"))
        }

    @Test
    fun `executeCheck fails tcp monitor without hostname`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "tcp", hostname = null, port = 443))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No hostname configured"))
        }

    @Test
    fun `executeCheck fails database monitor without connection string`() =
        runBlocking {
            val result = executor.executeCheck(monitor(type = "database", dbConnectionString = null))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No connection string configured"))
        }

    @Test
    fun `executeCheck blocks tcp monitor for internal hostname`() =
        runBlocking {
            withSelfHosted("false") {
                val result = executor.executeCheck(monitor(type = "tcp", hostname = "127.0.0.1", port = 443))
                assertEquals(0, result.status)
                assertTrue(result.message.contains("Blocked"), result.message)
            }
        }

    @Test
    fun `executeCheck blocks ping monitor for internal hostname`() =
        runBlocking {
            withSelfHosted("false") {
                val result = executor.executeCheck(monitor(type = "ping", hostname = "localhost"))
                assertEquals(0, result.status)
                assertTrue(result.message.contains("Blocked"), result.message)
            }
        }

    @Test
    fun `executeCheck blocks dns monitor for internal hostname`() =
        runBlocking {
            withSelfHosted("false") {
                val result = executor.executeCheck(monitor(type = "dns", hostname = "localhost"))
                assertEquals(0, result.status)
                assertTrue(result.message.contains("Blocked"), result.message)
            }
        }

    @Test
    fun `executeCheck blocks ssl monitor for internal hostname`() =
        runBlocking {
            withSelfHosted("false") {
                val result = executor.executeCheck(monitor(type = "ssl", hostname = "127.0.0.1", port = 443))
                assertEquals(0, result.status)
                assertTrue(result.message.contains("Blocked"), result.message)
            }
        }

    @Test
    fun `executeCheck blocks database monitor for internal hostname`() =
        runBlocking {
            withSelfHosted("false") {
                val result = executor.executeCheck(
                    monitor(type = "database", dbConnectionString = "jdbc:postgresql://127.0.0.1:5432/postgres")
                )
                assertEquals(0, result.status)
                assertTrue(result.message.contains("Blocked"), result.message)
            }
        }

    @Test
    fun `executeCheck blocks oracle thin database monitor for internal hostname`() =
        runBlocking {
            withSelfHosted("false") {
                val result = executor.executeCheck(
                    monitor(type = "database", dbConnectionString = "jdbc:oracle:thin:@127.0.0.1:1521:orcl")
                )
                assertEquals(0, result.status)
                assertTrue(result.message.contains("Blocked"), result.message)
            }
        }

    @Test
    fun `executeCheck blocks dns monitor with internal DNS server`() =
        runBlocking {
            withSelfHosted("false") {
                val result = executor.executeCheck(
                    monitor(type = "dns", hostname = "example.com").copy(dnsServer = "127.0.0.1")
                )
                assertEquals(0, result.status)
                assertTrue(result.message.contains("Blocked"), result.message)
            }
        }

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

    @Test
    fun `executeCheck reports SSL connection failure when host is allowed`() =
        runBlocking {
            withSelfHosted("true") {
                val result = executor.executeCheck(monitor(type = "ssl", hostname = "127.0.0.1", port = 1))
                assertEquals(0, result.status)
                assertTrue(result.message.contains("SSL check failed"), result.message)
            }
        }

    private fun certificateExpiringInDays(days: Long): X509Certificate {
        return certificateExpiringIn(Duration.ofDays(days))
    }

    private fun certificateExpiringIn(duration: Duration): X509Certificate {
        val cert = mockk<X509Certificate>()
        every { cert.notAfter } returns Date.from(java.time.Instant.now().plus(duration))
        return cert
    }

    private suspend fun <T> withSelfHosted(value: String, block: suspend () -> T): T {
        val previous = System.getProperty("SELF_HOSTED")
        System.setProperty("SELF_HOSTED", value)
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("SELF_HOSTED")
            } else {
                System.setProperty("SELF_HOSTED", previous)
            }
        }
    }
}
