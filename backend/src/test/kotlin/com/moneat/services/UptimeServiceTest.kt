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

import com.moneat.billing.services.BillingQuotaService
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.uptime.models.UpdateUptimeMonitorRequest
import com.moneat.uptime.models.UptimeMonitorData
import com.moneat.uptime.models.UptimeMonitors
import com.moneat.uptime.repositories.UptimeMonitorRepositoryImpl
import com.moneat.uptime.services.UptimeCheckExecutor
import com.moneat.uptime.services.UptimeService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class UptimeServiceTest {
    private val service = UptimeService(BillingQuotaService(), UptimeMonitorRepositoryImpl())
    private val executor = UptimeCheckExecutor()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
        private const val EXAMPLE_COM_URL = "https://example.com"
        private const val NO_HOSTNAME_CONFIGURED = "No hostname configured"
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_uptime_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Drop and recreate schema for clean state
        TestDatabaseHelper.resetSchema(Organizations, Users, UptimeMonitors)
        transaction {
            exec(
                """
                CREATE TABLE IF NOT EXISTS subscriptions (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    organization_id INT NOT NULL,
                    stripe_subscription_id VARCHAR(255),
                    stripe_customer_id VARCHAR(255),
                    plan VARCHAR(50) NOT NULL,
                    status VARCHAR(50) NOT NULL,
                    billing_interval VARCHAR(20) DEFAULT 'monthly' NOT NULL,
                    current_period_start TIMESTAMP,
                    current_period_end TIMESTAMP,
                    pricing_tier_config_id INT,
                    payg_budget_cents INT DEFAULT 0 NOT NULL,
                    payg_used_units BIGINT DEFAULT 0 NOT NULL,
                    payg_used_micros BIGINT DEFAULT 0 NOT NULL,
                    pending_meter_units BIGINT DEFAULT 0 NOT NULL,
                    pending_overage_bytes BIGINT DEFAULT 0 NOT NULL,
                    pending_meter_batch_id VARCHAR(255),
                    pending_meter_batch_units BIGINT DEFAULT 0 NOT NULL,
                    pending_apm_span_overage_units BIGINT DEFAULT 0 NOT NULL,
                    pending_apm_span_batch_id VARCHAR(255),
                    pending_apm_span_batch_units BIGINT DEFAULT 0 NOT NULL,
                    pending_custom_metric_overage_units BIGINT DEFAULT 0 NOT NULL,
                    pending_custom_metric_batch_id VARCHAR(255),
                    pending_custom_metric_batch_units BIGINT DEFAULT 0 NOT NULL,
                    stripe_base_item_id VARCHAR(255),
                    stripe_overage_item_id VARCHAR(255),
                    stripe_oncall_item_id VARCHAR(255),
                    oncall_seats INT DEFAULT 0 NOT NULL,
                    billing_grace_until TIMESTAMP,
                    bonus_gb_bytes BIGINT DEFAULT 0 NOT NULL,
                    bonus_units BIGINT DEFAULT 0 NOT NULL,
                    bonus_granted_at TIMESTAMP,
                    bonus_granted_by INT,
                    bonus_reason VARCHAR(500)
                )
                """.trimIndent()
            )
        }
    }

    private fun seedOrg(name: String = "Uptime Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedMonitor(
        organizationId: Int,
        status: String = "pending",
        active: Boolean = true,
        intervalSeconds: Int = 60,
        lastCheckAt: kotlin.time.Instant? = null
    ): UUID {
        val monitorId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[UptimeMonitors.organizationId] = organizationId
                it[name] = "monitor-$monitorId"
                it[type] = "http"
                it[UptimeMonitors.active] = active
                it[url] = "$EXAMPLE_COM_URL/health"
                it[method] = "GET"
                it[maxRedirects] = 10
                it[ignoreTls] = false
                it[keywordInverse] = false
                it[sslExpiryWarnDays] = 30
                it[UptimeMonitors.intervalSeconds] = intervalSeconds
                it[timeoutSeconds] = 30
                it[retries] = 0
                it[retryIntervalSeconds] = 60
                it[UptimeMonitors.status] = status
                it[UptimeMonitors.lastCheckAt] = lastCheckAt
                it[lastStatusChangeAt] = now
                it[consecutiveFailures] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return monitorId
    }

    @Test
    fun `createMonitor generates push token for push monitors`() =
        runBlocking {
            val orgId = seedOrg()

            val monitor =
                service.createMonitor(
                    organizationId = orgId,
                    request =
                    CreateUptimeMonitorRequest(
                        name = "Push Health",
                        type = "push",
                        intervalSeconds = 60,
                        timeoutSeconds = 10
                    )
                )

            assertEquals("push", monitor.type)
            assertNotNull(monitor.pushToken)
            assertEquals(64, monitor.pushToken.length)
        }

    @Test
    fun `getMonitorsDueForCheck returns only due active monitors`() {
        val orgId = seedOrg()
        val now = Clock.System.now()

        val neverCheckedId = seedMonitor(orgId, lastCheckAt = null)
        val dueId = seedMonitor(orgId, lastCheckAt = now - 120.seconds, intervalSeconds = 60)
        seedMonitor(orgId, lastCheckAt = now - 20.seconds, intervalSeconds = 60)
        seedMonitor(orgId, active = false, lastCheckAt = null)

        val due = service.getMonitorsDueForCheck().map { it.id }.toSet()

        assertTrue(neverCheckedId in due)
        assertTrue(dueId in due)
        assertEquals(2, due.size)
    }

    @Test
    fun `updateMonitorStatus tracks transitions and consecutive failures`() {
        val orgId = seedOrg()
        val monitorId = seedMonitor(orgId, status = "up")

        service.updateMonitorStatus(monitorId, CheckResult(status = 0, responseTimeMs = 100, message = "down"))
        var row =
            transaction {
                UptimeMonitors.selectAll().first { it[UptimeMonitors.id] == monitorId }
            }
        assertEquals("down", row[UptimeMonitors.status])
        assertEquals(1, row[UptimeMonitors.consecutiveFailures])
        val downTransitionAt = row[UptimeMonitors.lastStatusChangeAt]

        service.updateMonitorStatus(monitorId, CheckResult(status = 0, responseTimeMs = 90, message = "still down"))
        row =
            transaction {
                UptimeMonitors.selectAll().first { it[UptimeMonitors.id] == monitorId }
            }
        assertEquals("down", row[UptimeMonitors.status])
        assertEquals(2, row[UptimeMonitors.consecutiveFailures])
        assertEquals(downTransitionAt, row[UptimeMonitors.lastStatusChangeAt])

        service.updateMonitorStatus(monitorId, CheckResult(status = 1, responseTimeMs = 40, message = "recovered"))
        row =
            transaction {
                UptimeMonitors.selectAll().first { it[UptimeMonitors.id] == monitorId }
            }
        assertEquals("up", row[UptimeMonitors.status])
        assertEquals(0, row[UptimeMonitors.consecutiveFailures])
    }

    @Test
    fun `checkUptimeMonitorQuota enforces free tier limit`() {
        val orgId = seedOrg()
        repeat(5) {
            seedMonitor(orgId)
        }

        assertFailsWith<IllegalStateException> {
            service.checkUptimeMonitorQuota(orgId)
        }
    }

    @Test
    fun `getMonitorByPushToken returns matching monitor`() {
        val orgId = seedOrg()
        val token = "abcd".repeat(16)
        val monitorId = UUID.randomUUID()
        val now = Clock.System.now()
        transaction {
            UptimeMonitors.insert {
                it[id] = monitorId
                it[UptimeMonitors.organizationId] = orgId
                it[name] = "push-monitor"
                it[type] = "push"
                it[UptimeMonitors.active] = true
                it[method] = "GET"
                it[maxRedirects] = 10
                it[ignoreTls] = false
                it[keywordInverse] = false
                it[sslExpiryWarnDays] = 30
                it[UptimeMonitors.intervalSeconds] = 60
                it[timeoutSeconds] = 30
                it[retries] = 0
                it[retryIntervalSeconds] = 60
                it[UptimeMonitors.status] = "pending"
                it[lastStatusChangeAt] = now
                it[consecutiveFailures] = 0
                it[pushToken] = token
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        val found = service.getMonitorByPushToken(token)
        val missing = service.getMonitorByPushToken("missing")

        assertNotNull(found)
        assertEquals(monitorId, found.id)
        assertEquals("push", found.type)
        assertNull(missing)
    }

    // ──── UptimeService CRUD ────

    @Test
    fun `createMonitor does not generate push token for http monitors`() =
        runBlocking {
            val orgId = seedOrg()

            val monitor =
                service.createMonitor(
                    organizationId = orgId,
                    request =
                    CreateUptimeMonitorRequest(
                        name = "HTTP Health",
                        type = "http",
                        url = EXAMPLE_COM_URL,
                        intervalSeconds = 60,
                        timeoutSeconds = 10
                    )
                )

            assertEquals("http", monitor.type)
            assertNull(monitor.pushToken)
        }

    @Test
    fun `updateMonitor returns updated monitor with new name`() =
        runBlocking {
            val orgId = seedOrg()
            val created =
                service.createMonitor(
                    organizationId = orgId,
                    request =
                    CreateUptimeMonitorRequest(
                        name = "Original",
                        type = "http",
                        url = EXAMPLE_COM_URL,
                        intervalSeconds = 60,
                        timeoutSeconds = 10
                    )
                )

            val updated =
                service.updateMonitor(
                    monitorId = UUID.fromString(created.id),
                    organizationId = orgId,
                    request = UpdateUptimeMonitorRequest(name = "Renamed")
                )

            assertNotNull(updated)
            assertEquals("Renamed", updated.name)
        }

    @Test
    fun `updateMonitor returns null for non-existent monitor`() {
        val orgId = seedOrg()

        val result =
            service.updateMonitor(
                monitorId = UUID.randomUUID(),
                organizationId = orgId,
                request = UpdateUptimeMonitorRequest(name = "Ghost")
            )

        assertNull(result)
    }

    @Test
    fun `deleteMonitor removes existing monitor`() =
        runBlocking {
            val orgId = seedOrg()
            val created =
                service.createMonitor(
                    organizationId = orgId,
                    request =
                    CreateUptimeMonitorRequest(
                        name = "Deletable",
                        type = "http",
                        url = EXAMPLE_COM_URL,
                        intervalSeconds = 60,
                        timeoutSeconds = 10
                    )
                )

            val deleted = service.deleteMonitor(UUID.fromString(created.id), orgId)
            assertTrue(deleted)
            assertNull(service.getMonitor(UUID.fromString(created.id), orgId))
        }

    @Test
    fun `deleteMonitor returns false for non-existent monitor`() {
        val orgId = seedOrg()
        assertFalse(service.deleteMonitor(UUID.randomUUID(), orgId))
    }

    @Test
    fun `listMonitors returns all monitors for organization`() {
        val orgId = seedOrg()
        seedMonitor(orgId)
        seedMonitor(orgId)

        val list = service.listMonitors(orgId)
        assertEquals(2, list.size)
    }

    @Test
    fun `listMonitors returns empty for organization with no monitors`() {
        val orgId = seedOrg("Empty Org")

        val list = service.listMonitors(orgId)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `getMonitor returns null for unknown id`() =
        runBlocking {
            val orgId = seedOrg()
            assertNull(service.getMonitor(UUID.randomUUID(), orgId))
        }

    @Test
    fun `pauseMonitor sets active to false`() {
        val orgId = seedOrg()
        val id = seedMonitor(orgId, active = true)

        assertTrue(service.pauseMonitor(id, orgId))

        val row =
            transaction {
                UptimeMonitors.selectAll().first { it[UptimeMonitors.id] == id }
            }
        assertFalse(row[UptimeMonitors.active])
    }

    @Test
    fun `resumeMonitor sets active to true`() {
        val orgId = seedOrg()
        val id = seedMonitor(orgId, active = false)

        assertTrue(service.resumeMonitor(id, orgId))

        val row =
            transaction {
                UptimeMonitors.selectAll().first { it[UptimeMonitors.id] == id }
            }
        assertTrue(row[UptimeMonitors.active])
    }

    @Test
    fun `checkUptimeMonitorQuota allows when under limit`() {
        val orgId = seedOrg()
        seedMonitor(orgId)

        // Should not throw — free tier limit is 3 and we only have 1
        service.checkUptimeMonitorQuota(orgId)
    }

    // ──── UptimeCheckExecutor ────

    private fun executorMonitor(
        type: String,
        url: String? = null,
        hostname: String? = null,
        port: Int? = null,
        dbConnectionString: String? = null,
        dbQuery: String? = null,
        dockerContainerName: String? = null
    ): UptimeMonitorData {
        val now = Clock.System.now()
        return UptimeMonitorData(
            id = UUID.randomUUID(),
            organizationId = 1,
            name = "test-$type",
            type = type,
            active = true,
            url = url,
            hostname = hostname,
            port = port,
            dbConnectionString = dbConnectionString,
            dbQuery = dbQuery,
            dockerContainerName = dockerContainerName,
            intervalSeconds = 60,
            timeoutSeconds = 5,
            retries = 0,
            retryIntervalSeconds = 1,
            status = "pending",
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `executor fails tcp monitor without port`() =
        runBlocking {
            val result = executor.executeCheck(
                executorMonitor(type = "tcp", hostname = "localhost", port = null)
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No port configured"))
        }

    @Test
    fun `executor fails ping monitor without hostname`() =
        runBlocking {
            val result = executor.executeCheck(executorMonitor(type = "ping"))
            assertEquals(0, result.status)
            assertTrue(result.message.contains(NO_HOSTNAME_CONFIGURED))
        }

    @Test
    fun `executor fails dns monitor without hostname`() =
        runBlocking {
            val result = executor.executeCheck(executorMonitor(type = "dns"))
            assertEquals(0, result.status)
            assertTrue(result.message.contains(NO_HOSTNAME_CONFIGURED))
        }

    @Test
    fun `executor fails ssl monitor without hostname`() =
        runBlocking {
            val result = executor.executeCheck(executorMonitor(type = "ssl"))
            assertEquals(0, result.status)
            assertTrue(result.message.contains(NO_HOSTNAME_CONFIGURED))
        }

    @Test
    fun `executor fails docker monitor without container name`() =
        runBlocking {
            val result = executor.executeCheck(executorMonitor(type = "docker"))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No container name configured"))
        }

    @Test
    fun `executor fails websocket monitor without url`() =
        runBlocking {
            val result = executor.executeCheck(executorMonitor(type = "websocket"))
            assertEquals(0, result.status)
            assertTrue(result.message.contains("No URL configured"))
        }

    @Test
    fun `executor database check succeeds with valid h2 connection`() =
        runBlocking {
            val connStr = "jdbc:h2:mem:uptime_exec_db;DB_CLOSE_DELAY=-1"
            val result = executor.executeCheck(
                executorMonitor(type = "database", dbConnectionString = connStr)
            )
            assertEquals(1, result.status)
            assertTrue(result.message.contains("Database connection successful"))
        }

    @Test
    fun `executor database check rejects non-SELECT queries`() =
        runBlocking {
            val connStr = "jdbc:h2:mem:uptime_exec_db2;DB_CLOSE_DELAY=-1"
            val result = executor.executeCheck(
                executorMonitor(
                    type = "database",
                    dbConnectionString = connStr,
                    dbQuery = "DROP TABLE foo"
                )
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("Only SELECT queries"))
        }

    @Test
    fun `executor docker check rejects unix socket`() =
        runBlocking {
            val result = executor.executeCheck(
                executorMonitor(type = "docker", dockerContainerName = "myapp")
            )
            assertEquals(0, result.status)
            assertTrue(result.message.contains("Unix socket not supported"))
        }
}
