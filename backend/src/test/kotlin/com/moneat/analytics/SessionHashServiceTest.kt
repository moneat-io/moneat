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

package com.moneat.analytics

import com.moneat.analytics.services.SessionHashService
import com.moneat.config.RedisConfig
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionHashServiceTest {

    private val mockRedis = mockk<RedisCommands<String, String>>(relaxed = true)

    /** Deterministic daily salt so session IDs are stable across calls in tests. */
    private val testSalt = "dGVzdHNhbHR0ZXN0c2FsdHRlc3RzYWx0dGVzdHNhbHR0ZXN0c2FsdA=="

    @BeforeTest
    fun setup() {
        mockkObject(RedisConfig)
        every { RedisConfig.sync() } returns mockRedis
        every { mockRedis.get(match { it.startsWith("moneat:analytics:salt:") }) } returns testSalt
    }

    @AfterTest
    fun teardown() {
        unmockkObject(RedisConfig)
    }

    @Test
    fun `generateSessionId returns non-empty hex string`() {
        val svc = SessionHashService()
        val id = svc.generateSessionId("example.com", "127.0.0.1", "Mozilla/5.0")
        assertTrue(id.isNotBlank())
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `generateSessionId returns 64 char SHA-256 hex`() {
        val svc = SessionHashService()
        val id = svc.generateSessionId("a.com", "10.0.0.1", "UA")
        assertEquals(64, id.length)
    }

    @Test
    fun `generateSessionId is consistent for same inputs with stable daily salt`() {
        val svc = SessionHashService()
        val a = svc.generateSessionId("mysite.io", "192.168.1.10", "Chrome/120")
        val b = svc.generateSessionId("mysite.io", "192.168.1.10", "Chrome/120")
        assertEquals(a, b)
    }

    @Test
    fun `generateSessionId differs when domain changes`() {
        val svc = SessionHashService()
        val a = svc.generateSessionId("a.com", "1.1.1.1", "UA")
        val b = svc.generateSessionId("b.com", "1.1.1.1", "UA")
        assertNotEquals(a, b)
    }

    @Test
    fun `generateSessionId differs when IP changes`() {
        val svc = SessionHashService()
        val a = svc.generateSessionId("x.com", "10.0.0.1", "UA")
        val b = svc.generateSessionId("x.com", "10.0.0.2", "UA")
        assertNotEquals(a, b)
    }

    @Test
    fun `generateSessionId differs when user agent changes`() {
        val svc = SessionHashService()
        val a = svc.generateSessionId("x.com", "10.0.0.1", "A")
        val b = svc.generateSessionId("x.com", "10.0.0.1", "B")
        assertNotEquals(a, b)
    }
}
