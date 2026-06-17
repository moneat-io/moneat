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

import com.moneat.shared.services.PulsePayload
import com.moneat.shared.services.PulseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * PulseService collects telemetry for self-hosted deployments.
 * getStatus() returns configuration and optionally metrics.
 * In typical test env SELF_HOSTED is false, so enabled=false and metrics=null.
 */
class PulseServiceTest {

    @Test
    fun `PulsePayload serializes and deserializes correctly`() {
        val payload = PulsePayload(
            deploymentId = "test-id-123",
            version = "v1.2.3",
            cpuCount = 4,
            memTotalBytes = 8_000_000_000,
            memUsedBytes = 4_000_000_000,
            osName = "Linux",
            osArch = "amd64",
            jvmVersion = "21",
            projectCount = 10,
            userCount = 5,
            eventCount = 1000,
            issueCount = 50,
            selfHost = true,
            sslEnabled = true
        )

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(PulsePayload.serializer(), payload)
        val decoded = json.decodeFromString(PulsePayload.serializer(), encoded)

        assertEquals(payload.deploymentId, decoded.deploymentId)
        assertEquals(payload.version, decoded.version)
        assertEquals(payload.cpuCount, decoded.cpuCount)
        assertEquals(payload.memTotalBytes, decoded.memTotalBytes)
        assertEquals(payload.memUsedBytes, decoded.memUsedBytes)
        assertEquals(payload.osName, decoded.osName)
        assertEquals(payload.projectCount, decoded.projectCount)
        assertEquals(payload.sslEnabled, decoded.sslEnabled)
    }

    @Test
    fun `PulsePayload defaults are applied`() {
        val payload = PulsePayload(deploymentId = "minimal")

        assertEquals(0, payload.cpuCount)
        assertEquals("", payload.version)
        assertEquals(0L, payload.memTotalBytes)
        assertEquals("", payload.osName)
        assertEquals(0L, payload.projectCount)
        assertTrue(payload.selfHost)
        assertFalse(payload.sslEnabled)
    }

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

        // In test env SELF_HOSTED typically false, so enabled should be false
        assertFalse(enabled)
    }

    @Test
    fun `getStatus respects enabled flag`() =
        runBlocking {
            val status = PulseService.getStatus()

            // When SELF_HOSTED=false (typical in tests), enabled=false
            assertEquals(PulseService.isEnabled(), status.enabled)
        }

    @Test
    fun `getStatus has null metrics when disabled`() =
        runBlocking {
            val status = PulseService.getStatus()

            if (!status.enabled) {
                assertNull(status.metrics)
            }
        }

    @Test
    fun `start and stop does not throw`() {
        val service = PulseService(
            interval = 1.hours,
            endpoint = "http://localhost:1/noop"
        )
        val scope = CoroutineScope(Dispatchers.Default)

        service.start(scope)
        service.stop()
        scope.cancel()
    }
}
