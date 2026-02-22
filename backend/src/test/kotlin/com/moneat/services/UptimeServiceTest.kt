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

import com.moneat.uptime.models.CheckResult
import com.moneat.uptime.models.CreateUptimeMonitorRequest
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Subscriptions
import com.moneat.uptime.models.UptimeMonitors
import com.moneat.shared.models.Users
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class UptimeServiceTest {
    private val service = UptimeService()

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_uptime_service;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            transaction(db!!) {
                SchemaUtils.create(
                    Organizations,
                    Users,
                    UptimeMonitors
                )
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
                        pending_meter_batch_id VARCHAR(255),
                        pending_meter_batch_units BIGINT DEFAULT 0 NOT NULL,
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

        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db
        transaction {
            UptimeMonitors.deleteAll()
            Subscriptions.deleteAll()
            Users.deleteAll()
            Organizations.deleteAll()
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
                it[url] = "https://example.com/health"
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
                    com.moneat.models.CreateUptimeMonitorRequest(
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
}
