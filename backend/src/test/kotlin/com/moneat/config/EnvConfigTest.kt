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

package com.moneat.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EnvConfigTest {

    @Test
    fun `get returns system env variable when set`() {
        // PATH is always set in system environment
        val path = EnvConfig.get("PATH")
        assertNotNull(path)
        assertTrue(path.isNotBlank())
    }

    @Test
    fun `get returns null for non-existent variable`() {
        val value = EnvConfig.get("MONEAT_TEST_NONEXISTENT_VAR_12345")
        assertEquals(null, value)
    }

    @Test
    fun `get with default returns default for non-existent variable`() {
        val value = EnvConfig.get("MONEAT_TEST_NONEXISTENT_VAR_12345", "fallback")
        assertEquals("fallback", value)
    }

    @Test
    fun `get with default returns actual value when variable exists`() {
        // PATH always exists
        val value = EnvConfig.get("PATH", "fallback")
        assertTrue(value != "fallback")
        assertTrue(value.isNotBlank())
    }

    @Test
    fun `Demo object has correct constant values`() {
        assertEquals(-1L, EnvConfig.Demo.ORG_ID)
        assertEquals(-1L, EnvConfig.Demo.PROJECT_ID)
        assertEquals(-1L, EnvConfig.Demo.USER_ID)
        assertEquals("demo@moneat.dev", EnvConfig.Demo.USER_EMAIL)
    }

    @Test
    fun `Demo epochMs returns current time when env var not set`() {
        val before = System.currentTimeMillis()
        val epochMs = EnvConfig.Demo.epochMs
        val after = System.currentTimeMillis()
        // Should be approximately current time (within 1 second)
        assertTrue(epochMs in before..after, "Demo epochMs should be current time when DEMO_EPOCH_MS not set")
    }

    @Test
    fun `initialize does not throw`() {
        // Should not throw even without .env file
        EnvConfig.initialize()
    }
}
