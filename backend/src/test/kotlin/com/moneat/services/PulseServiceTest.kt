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

import com.moneat.shared.services.PulseService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PulseService collects telemetry for self-hosted deployments.
 * getStatus() returns configuration and optionally metrics.
 * In typical test env SELF_HOST is false, so enabled=false and metrics=null.
 */
class PulseServiceTest {

    @Test
    fun `getStatus returns valid PulseStatus structure`(): Unit =
        runBlocking {
            val status = PulseService.getStatus()

            assertNotNull(status)
            assertNotNull(status.endpoint)
        }

    @Test
    fun `getStatus endpoint is non-empty`() =
        runBlocking {
            val status = PulseService.getStatus()

            assertTrue(status.endpoint.isNotBlank())
        }

    @Test
    fun `isEnabled returns boolean`() {
        val enabled = PulseService.isEnabled()

        // In test env SELF_HOST typically false, so enabled should be false
        assertFalse(enabled)
    }

    @Test
    fun `getStatus respects enabled flag`() =
        runBlocking {
            val status = PulseService.getStatus()

            // When SELF_HOST=false (typical in tests), enabled=false
            assertEquals(PulseService.isEnabled(), status.enabled)
        }
}
