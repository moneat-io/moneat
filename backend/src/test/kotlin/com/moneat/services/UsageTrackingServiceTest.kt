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

import com.moneat.billing.models.OrgUsageCounters
import com.moneat.billing.models.PricingTierConfigs
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Subscriptions
import com.moneat.shared.models.UsageRecords
import com.moneat.shared.models.Users
import com.moneat.shared.services.UsageTrackingService
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class UsageTrackingServiceTest {

    companion object {
        private var db: org.jetbrains.exposed.v1.jdbc.Database? = null
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_usage_tracking;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = db

        // Ensure schema exists (idempotent in H2) and clean between tests
        transaction {
            SchemaUtils.drop(OrgUsageCounters, PricingTierConfigs, Subscriptions, UsageRecords, Projects, Organizations, Users)
            SchemaUtils.create(Users, Organizations, Projects, UsageRecords, Subscriptions, PricingTierConfigs, OrgUsageCounters)
        }
    }

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedProject(orgId: Int, name: String = "Test Project"): Long =
        transaction {
            Projects.insert {
                it[organization_id] = orgId
                it[Projects.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Projects.id
        }

    private fun seedFreeTier(): Int =
        transaction {
            PricingTierConfigs.insert {
                it[tier_name] = "FREE"
                it[version] = 1
                it[monthly_unit_limit] = 5000
                it[monthly_error_limit] = 5000
                it[retention_days] = 3
                it[log_retention_days] = 3
                it[max_systems] = 3
                it[monitor_interval_seconds] = 60
                it[monthly_price_cents] = 0
                it[is_current] = true
            } get PricingTierConfigs.id
        }

    private fun seedUsageRecord(
        orgId: Int,
        projectId: Long,
        eventType: String = "error",
        eventCount: Int = 10,
        bytesIngested: Long = 1024L,
        daysAgo: Int = 0
    ) {
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val date = today.plus(DatePeriod(days = -daysAgo))
        transaction {
            UsageRecords.insert {
                it[organization_id] = orgId
                it[project_id] = projectId.toInt()
                it[event_type] = eventType
                it[event_count] = eventCount
                it[bytes_ingested] = bytesIngested
                it[recordDate] = date
            }
        }
    }

    // ==================== getUsageForOrg ====================

    @Test
    fun `getUsageForOrg returns empty when no records`() {
        val orgId = seedOrg()
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        val service = UsageTrackingService()
        val result = service.getUsageForOrg(orgId, startDate, today)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getUsageForOrg returns records within date range`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        seedUsageRecord(orgId, projectId, eventCount = 50, bytesIngested = 2048L, daysAgo = 2)

        val service = UsageTrackingService()
        val result = service.getUsageForOrg(orgId, startDate, today)
        assertEquals(1, result.size)
        assertEquals(50, result.first().eventCount)
        assertEquals(2048L, result.first().bytesIngested)
    }

    @Test
    fun `getUsageForOrg excludes records outside date range`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        seedUsageRecord(orgId, projectId, eventCount = 100, daysAgo = 10) // Outside range

        val service = UsageTrackingService()
        val result = service.getUsageForOrg(orgId, startDate, today)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getUsageForOrg returns only records for the specified org`() {
        val org1 = seedOrg("Org 1")
        val org2 = seedOrg("Org 2")
        val project1 = seedProject(org1)
        val project2 = seedProject(org2)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        seedUsageRecord(org1, project1, eventCount = 30)
        seedUsageRecord(org2, project2, eventCount = 70)

        val service = UsageTrackingService()
        val result = service.getUsageForOrg(org1, startDate, today)
        assertEquals(1, result.size)
        assertEquals(30, result.first().eventCount)
    }

    @Test
    fun `getUsageForOrg groups by date and eventType`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        seedUsageRecord(orgId, projectId, eventType = "error", eventCount = 20)
        seedUsageRecord(orgId, projectId, eventType = "transaction", eventCount = 40)

        val service = UsageTrackingService()
        val result = service.getUsageForOrg(orgId, startDate, today)
        assertEquals(2, result.size)
        val types = result.map { it.eventType }.toSet()
        assertTrue(types.contains("error"))
        assertTrue(types.contains("transaction"))
    }

    // ==================== getTotalBytesForOrg ====================

    @Test
    fun `getTotalBytesForOrg returns 0 when no records`() {
        val orgId = seedOrg()
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        val service = UsageTrackingService()
        val result = service.getTotalBytesForOrg(orgId, startDate, today)
        assertEquals(0L, result)
    }

    @Test
    fun `getTotalBytesForOrg sums all bytes in date range`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        seedUsageRecord(orgId, projectId, bytesIngested = 1000L, daysAgo = 0)
        seedUsageRecord(orgId, projectId, bytesIngested = 2000L, daysAgo = 3)
        seedUsageRecord(orgId, projectId, bytesIngested = 5000L, daysAgo = 10) // Outside range

        val service = UsageTrackingService()
        val result = service.getTotalBytesForOrg(orgId, startDate, today)
        assertEquals(3000L, result)
    }

    // ==================== getEventCountForOrg ====================

    @Test
    fun `getEventCountForOrg returns 0 when no records`() {
        val orgId = seedOrg()
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        val service = UsageTrackingService()
        val result = service.getEventCountForOrg(orgId, startDate, today)
        assertEquals(0L, result)
    }

    @Test
    fun `getEventCountForOrg sums all events when no type filter`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        seedUsageRecord(orgId, projectId, eventType = "error", eventCount = 30)
        seedUsageRecord(orgId, projectId, eventType = "transaction", eventCount = 70)

        val service = UsageTrackingService()
        val result = service.getEventCountForOrg(orgId, startDate, today)
        assertEquals(100L, result)
    }

    @Test
    fun `getEventCountForOrg filters by event type`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        seedUsageRecord(orgId, projectId, eventType = "error", eventCount = 30)
        seedUsageRecord(orgId, projectId, eventType = "transaction", eventCount = 70)

        val service = UsageTrackingService()
        val result = service.getEventCountForOrg(orgId, startDate, today, listOf("error"))
        assertEquals(30L, result)
    }

    @Test
    fun `getEventCountForOrg filters by multiple event types`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -6))

        seedUsageRecord(orgId, projectId, eventType = "error", eventCount = 30)
        seedUsageRecord(orgId, projectId, eventType = "transaction", eventCount = 70)
        seedUsageRecord(orgId, projectId, eventType = "log", eventCount = 100)

        val service = UsageTrackingService()
        val result = service.getEventCountForOrg(orgId, startDate, today, listOf("error", "transaction"))
        assertEquals(100L, result)
    }

    // ==================== flushBuffer ====================

    @Test
    fun `flushBuffer does nothing when buffer is empty`() {
        val service = UsageTrackingService()
        // Should not throw
        service.flushBuffer()
    }

    @Test
    fun `recordUsage increments buffer and flushes to db`() {
        val orgId = seedOrg()
        val projectId = seedProject(orgId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
        val startDate = today.plus(DatePeriod(days = -1))

        val service = UsageTrackingService()
        service.recordUsage(projectId, "error", 100)
        service.flushBuffer()

        val count = service.getEventCountForOrg(orgId, startDate, today)
        assertEquals(1L, count)
    }

    @Test
    fun `recordUsage for unknown project does not flush`() {
        val service = UsageTrackingService()
        // Should not throw even if project doesn't exist
        service.recordUsage(99999L, "error", 100)
        service.flushBuffer()
    }

    // ==================== checkQuota ====================

    @Test
    fun `checkQuota returns quota status for org`() {
        seedFreeTier()
        val orgId = seedOrg()

        val service = UsageTrackingService()
        val status = service.checkQuota(orgId)
        assertNotNull(status)
        assertTrue(status.withinQuota)
        assertEquals("free", status.plan)
    }
}
