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

package com.moneat.datadog.services

import com.moneat.datadog.models.DatadogHostMeta
import com.moneat.datadog.models.DatadogHostMetadata
import com.moneat.datadog.models.DatadogIntakeMeta
import com.moneat.datadog.models.DatadogIntakePayload
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

class DatadogHostServiceTest {

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:test_dd_hosts;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver"
        )
        transaction {
            exec("DROP ALL OBJECTS")
            SchemaUtils.create(DdHostsTable)
        }
    }

    @Test
    fun `upsertFromMetadata inserts new host`() {
        val metadata = DatadogHostMetadata(
            hostname = "web-01",
            gohai = "{}",
            tags = listOf("env:prod"),
            hostMeta = DatadogHostMeta(
                os = "linux",
                platform = "ubuntu",
                processor = "x86_64",
                cpuCores = 4,
                memoryTotalKb = 8000000
            ),
            agentVersion = "7.50.0",
            os = "linux",
            platform = "ubuntu",
            processor = "x86_64"
        )

        DatadogHostService.upsertFromMetadata(1, metadata)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(1, hosts.size)
        assertEquals("web-01", hosts[0].hostname)
        assertEquals("linux", hosts[0].os)
        assertEquals("ubuntu", hosts[0].platform)
        assertEquals("x86_64", hosts[0].processor)
        assertEquals(4, hosts[0].cpuCores)
        assertEquals(8000000, hosts[0].memoryTotalKb)
        assertEquals("7.50.0", hosts[0].agentVersion)
        assertEquals("prod", hosts[0].tags["env"])
    }

    @Test
    fun `resolveHostIds rejects duplicate hostnames`() {
        val now = Clock.System.now()
        transaction {
            repeat(2) {
                DdHostsTable.insert {
                    it[organizationId] = 1
                    it[hostname] = "web-01"
                    it[displayName] = "web-01"
                    it[status] = "up"
                    it[os] = "linux"
                    it[platform] = "ubuntu"
                    it[processor] = "x86_64"
                    it[cpuCores] = 4
                    it[memoryTotalKb] = 8000000
                    it[agentVersion] = "7.50.0"
                    it[gohai] = "{}"
                    it[tags] = "{}"
                    it[firstSeenAt] = now
                    it[lastSeenAt] = now
                }
            }
        }

        val error = assertFailsWith<IllegalStateException> {
            DatadogHostService.resolveHostIds(1, setOf("web-01"))
        }
        assertTrue(error.message.orEmpty().contains("Duplicate Datadog host rows"))
    }

    @Test
    fun `upsertFromMetadata updates existing host`() {
        val metadata1 = DatadogHostMetadata(
            hostname = "web-01",
            gohai = "{}",
            agentVersion = "7.49.0",
            os = "linux"
        )
        DatadogHostService.upsertFromMetadata(1, metadata1)

        val metadata2 = DatadogHostMetadata(
            hostname = "web-01",
            gohai = "{\"cpu\":{}}",
            agentVersion = "7.50.0",
            os = "linux",
            platform = "ubuntu"
        )
        DatadogHostService.upsertFromMetadata(1, metadata2)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(1, hosts.size)
        assertEquals("7.50.0", hosts[0].agentVersion)
        assertEquals("ubuntu", hosts[0].platform)
    }

    @Test
    fun `upsertFromMetadata skips blank hostname`() {
        val metadata = DatadogHostMetadata(hostname = "")
        DatadogHostService.upsertFromMetadata(1, metadata)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(0, hosts.size)
    }

    @Test
    fun `upsertFromIntake inserts new host`() {
        val payload = DatadogIntakePayload(
            gohai = "{}",
            meta = DatadogIntakeMeta(
                hostname = "api-01",
                agentVersionUnderscore = "7.50.0",
                os = "linux",
                platform = "debian"
            )
        )

        DatadogHostService.upsertFromIntake(1, payload)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(1, hosts.size)
        assertEquals("api-01", hosts[0].hostname)
        assertEquals("linux", hosts[0].os)
        assertEquals("debian", hosts[0].platform)
    }

    @Test
    fun `upsertFromIntake updates existing host`() {
        val payload1 = DatadogIntakePayload(
            gohai = "{}",
            meta = DatadogIntakeMeta(
                hostname = "api-01",
                agentVersionUnderscore = "7.49.0",
                os = "linux"
            )
        )
        DatadogHostService.upsertFromIntake(1, payload1)

        val payload2 = DatadogIntakePayload(
            gohai = "{\"cpu\":{}}",
            meta = DatadogIntakeMeta(
                hostname = "api-01",
                agentVersionUnderscore = "7.50.0",
                os = "linux",
                platform = "ubuntu"
            )
        )
        DatadogHostService.upsertFromIntake(1, payload2)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(1, hosts.size)
        assertEquals("7.50.0", hosts[0].agentVersion)
    }

    @Test
    fun `upsertFromIntake skips when meta is null`() {
        val payload = DatadogIntakePayload(gohai = "{}")
        DatadogHostService.upsertFromIntake(1, payload)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(0, hosts.size)
    }

    @Test
    fun `listHosts filters by organization`() {
        val metadata1 = DatadogHostMetadata(
            hostname = "org1-host",
            os = "linux"
        )
        val metadata2 = DatadogHostMetadata(
            hostname = "org2-host",
            os = "linux"
        )
        DatadogHostService.upsertFromMetadata(1, metadata1)
        DatadogHostService.upsertFromMetadata(2, metadata2)

        val org1Hosts = DatadogHostService.listHosts(1)
        assertEquals(1, org1Hosts.size)
        assertEquals("org1-host", org1Hosts[0].hostname)

        val org2Hosts = DatadogHostService.listHosts(2)
        assertEquals(1, org2Hosts.size)
        assertEquals("org2-host", org2Hosts[0].hostname)
    }

    @Test
    fun `upsertFromMetadata falls back to hostMeta fields`() {
        val metadata = DatadogHostMetadata(
            hostname = "host-01",
            os = "",
            platform = "",
            processor = "",
            hostMeta = DatadogHostMeta(
                os = "darwin",
                platform = "macos",
                processor = "arm64",
                cpuCores = 8,
                memoryTotalKb = 16000000
            )
        )

        DatadogHostService.upsertFromMetadata(1, metadata)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(1, hosts.size)
        assertEquals("darwin", hosts[0].os)
        assertEquals("macos", hosts[0].platform)
        assertEquals("arm64", hosts[0].processor)
    }

    @Test
    fun `upsertFromIntake sparse payload does not create host`() {
        val payload = DatadogIntakePayload(
            gohai = "",
            meta = DatadogIntakeMeta(hostname = "sparse-host")
        )

        DatadogHostService.upsertFromIntake(1, payload)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(0, hosts.size)
    }

    @Test
    fun `upsertFromIntake empty JSON gohai does not create host`() {
        val payload = DatadogIntakePayload(
            gohai = "{}",
            meta = DatadogIntakeMeta(hostname = "sparse-host-json")
        )

        DatadogHostService.upsertFromIntake(1, payload)

        assertEquals(0, DatadogHostService.listHosts(1).size)
    }

    @Test
    fun `upsertFromIntake sparse payload does not overwrite rich metadata`() {
        // First: create host via metadata endpoint with full data including tags
        val metadata = DatadogHostMetadata(
            hostname = "rich-host",
            gohai = """{"cpu":{"cpu_logical_processors":"8","model_name":"Apple M2"}, "memory":{"total":"16000000"}}""",
            agentVersion = "7.50.0",
            os = "darwin",
            platform = "macos",
            processor = "arm64",
            tags = listOf("env:production", "role:web"),
            hostMeta = DatadogHostMeta(
                os = "darwin",
                platform = "macos",
                processor = "arm64",
                cpuCores = 8,
                memoryTotalKb = 16000000
            )
        )
        DatadogHostService.upsertFromMetadata(1, metadata)

        // Second: sparse intake arrives with only hostname + empty fields (no hostTags)
        val sparseIntake = DatadogIntakePayload(
            gohai = "",
            meta = DatadogIntakeMeta(hostname = "rich-host")
        )
        DatadogHostService.upsertFromIntake(1, sparseIntake)

        // Verify rich data was preserved
        val hosts = DatadogHostService.listHosts(1)
        assertEquals(1, hosts.size)
        val host = hosts[0]
        assertEquals("darwin", host.os)
        assertEquals("macos", host.platform)
        assertEquals("arm64", host.processor)
        assertEquals(8, host.cpuCores)
        assertEquals(16000000, host.memoryTotalKb)
        assertEquals("7.50.0", host.agentVersion)
        assertEquals(mapOf("env" to "production", "role" to "web"), host.tags)
    }

    @Test
    fun `upsertFromIntake with metadata still inserts host`() {
        val payload = DatadogIntakePayload(
            gohai = """{"cpu":{"cpu_logical_processors":"4"}}""",
            meta = DatadogIntakeMeta(
                hostname = "real-host",
                agentVersionUnderscore = "7.51.0",
                os = "linux",
                platform = "ubuntu"
            )
        )

        DatadogHostService.upsertFromIntake(1, payload)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(1, hosts.size)
        assertEquals("real-host", hosts[0].hostname)
        assertEquals("linux", hosts[0].os)
        assertEquals("ubuntu", hosts[0].platform)
        assertEquals(4, hosts[0].cpuCores)
    }

    @Test
    fun `upsertFromIntake partial update preserves existing fields`() {
        // Create host with full metadata
        val metadata = DatadogHostMetadata(
            hostname = "partial-host",
            os = "linux",
            platform = "ubuntu",
            processor = "x86_64",
            agentVersion = "7.50.0",
            hostMeta = DatadogHostMeta(
                os = "linux",
                platform = "ubuntu",
                processor = "x86_64",
                cpuCores = 4,
                memoryTotalKb = 8000000
            )
        )
        DatadogHostService.upsertFromMetadata(1, metadata)

        // Intake with only os set (platform blank)
        val intake = DatadogIntakePayload(
            gohai = "",
            meta = DatadogIntakeMeta(
                hostname = "partial-host",
                os = "linux"
            )
        )
        DatadogHostService.upsertFromIntake(1, intake)

        val hosts = DatadogHostService.listHosts(1)
        assertEquals(1, hosts.size)
        val host = hosts[0]
        assertEquals("linux", host.os)
        assertEquals("ubuntu", host.platform)
        assertEquals("x86_64", host.processor)
        assertEquals("7.50.0", host.agentVersion)
        assertEquals(4, host.cpuCores)
        assertEquals(8000000, host.memoryTotalKb)
    }

    @Test
    fun `listHosts returns empty for unknown org`() {
        val hosts = DatadogHostService.listHosts(999)
        assertTrue(hosts.isEmpty())
    }
}
