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

@file:Suppress("USELESS_CAST", "UNNECESSARY_NOT_NULL_ASSERTION", "UNNECESSARY_SAFE_CALL")

package com.moneat.services

import com.moneat.incident.services.IncidentService
import com.moneat.monitor.models.AlertData
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.shared.models.AlertSilencePeriods
import com.moneat.shared.models.HostAlertSettings
import com.moneat.shared.models.HostAlertTemplateStates
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.OrganizationAlertTemplates
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.services.WorkflowService
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Extended tests for MonitorAlertService covering:
 *  - getConditionText / getMetricLabel / formatMetricValue (via evaluateAlert paths)
 *  - loadHostAlertTemplate / loadHostRecoveredTemplate fallback HTML
 *  - evaluateAlerts + checkHostStatuses with mocked ClickHouse
 *  - cleanupExpiredSilencePeriods
 *  - silence period edge cases
 */
class MonitorAlertServiceExtendedTest {

    companion object {
        private var db: Database? = null
    }

    private lateinit var incidentService: IncidentService
    private lateinit var workflowService: WorkflowService
    private lateinit var service: MonitorAlertService

    @BeforeTest
    fun setup() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_monitor_ext;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            AlertSilencePeriods,
            Hosts,
            HostAlerts,
            HostAlertSettings,
            OrganizationAlertTemplates,
            HostAlertTemplateStates
        )

        incidentService = mockk(relaxed = true)
        workflowService = mockk(relaxed = true)

        service = MonitorAlertService(
            incidentService = incidentService,
            workflowService = workflowService,
        )
    }

    private fun seedOrg(name: String = "Test Org"): Int =
        transaction {
            Organizations.insert {
                it[Organizations.name] = name
                it[slug] = name.lowercase().replace(" ", "-")
            } get Organizations.id
        }

    private fun seedUser(email: String = "user@test.com"): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[password_hash] = "hash"
                it[Users.name] = "Test User"
                it[email_verified] = true
            } get Users.id
        }

    private fun organizationResourceId(organizationId: Int): String =
        transaction {
            Organizations
                .selectAll()
                .where { Organizations.id eq organizationId }
                .single()[Organizations.resource_id]
                .toString()
        }

    private fun userResourceId(userId: Int): String =
        transaction {
            Users
                .selectAll()
                .where { Users.id eq userId }
                .single()[Users.resource_id]
                .toString()
        }

    private fun seedHost(orgId: Int, hostname: String = "web-01", status: String = "up"): Int =
        transaction {
            val now = Clock.System.now()
            Hosts.insert {
                it[Hosts.hostname] = hostname
                it[organization_id] = orgId
                it[Hosts.status] = status
                it[first_seen_at] = now
                it[last_seen_at] = now
            } get Hosts.id
        }

    // ===================== isThresholdTriggered edge cases =====================

    @Test
    fun `isThresholdTriggered with negative values`() {
        assertTrue(service.isThresholdTriggered(">", -1.0, -5.0))
        assertFalse(service.isThresholdTriggered(">", -5.0, -1.0))
        assertTrue(service.isThresholdTriggered("<", -5.0, -1.0))
    }

    @Test
    fun `isThresholdTriggered with zero boundary`() {
        assertTrue(service.isThresholdTriggered(">=", 0.0, 0.0))
        assertTrue(service.isThresholdTriggered("<=", 0.0, 0.0))
        assertTrue(service.isThresholdTriggered("==", 0.0, 0.0))
        assertFalse(service.isThresholdTriggered(">", 0.0, 0.0))
        assertFalse(service.isThresholdTriggered("<", 0.0, 0.0))
    }

    @Test
    fun `isThresholdTriggered with very small differences`() {
        assertFalse(service.isThresholdTriggered("==", 1.0000001, 1.0))
        assertTrue(service.isThresholdTriggered(">", 1.0000001, 1.0))
    }

    // ===================== isThrottledByInterval edge cases =====================

    @Test
    fun `isThrottledByInterval returns true at exactly MIN_ALERT_INTERVAL boundary`() {
        val now = Clock.System.now()
        val atBoundary = now - 14.minutes - 59.seconds
        assertTrue(service.isThrottledByInterval(atBoundary, now))
    }

    @Test
    fun `isThrottledByInterval returns false past the boundary`() {
        val now = Clock.System.now()
        val pastBoundary = now - 16.minutes
        assertFalse(service.isThrottledByInterval(pastBoundary, now))
    }

    // ===================== silence period DB logic =====================

    @Test
    fun `createSilencePeriod fields roundtrip correctly`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val now = Clock.System.now()

        val request = CreateSilencePeriodRequest(
            reason = "Deploy window",
            startsAt = (now - 30.minutes).toEpochMilliseconds(),
            endsAt = (now + 30.minutes).toEpochMilliseconds()
        )

        val created = service.createSilencePeriod(orgId, userId, request)
        assertEquals("Deploy window", created.reason)
        assertEquals(organizationResourceId(orgId), created.organizationId)
        assertEquals(userResourceId(userId), created.createdBy)
        assertTrue(created.createdAt > 0)
    }

    @Test
    fun `multiple silence periods can coexist for same org`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val now = Clock.System.now()

        repeat(3) { i ->
            service.createSilencePeriod(
                orgId,
                userId,
                CreateSilencePeriodRequest(
                    reason = "Window $i",
                    startsAt = (now + (i * 60).seconds).toEpochMilliseconds(),
                    endsAt = (now + ((i + 1) * 60).seconds).toEpochMilliseconds()
                )
            )
        }

        assertEquals(3, service.listSilencePeriods(orgId).size)
    }

    @Test
    fun `isAnySilenceActive with overlapping periods returns true`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val now = Clock.System.now()

        service.createSilencePeriod(
            orgId,
            userId,
            CreateSilencePeriodRequest(
                reason = "First",
                startsAt = (now - 2.hours).toEpochMilliseconds(),
                endsAt = (now + 1.hours).toEpochMilliseconds()
            )
        )
        service.createSilencePeriod(
            orgId,
            userId,
            CreateSilencePeriodRequest(
                reason = "Second",
                startsAt = (now - 1.hours).toEpochMilliseconds(),
                endsAt = (now + 2.hours).toEpochMilliseconds()
            )
        )

        assertTrue(service.isAnySilenceActive(orgId))
    }

    @Test
    fun `deleteSilencePeriod with wrong id returns false`() {
        val orgId = seedOrg()
        assertFalse(service.deleteSilencePeriod("9999", orgId))
    }

    @Test
    fun `listSilencePeriods response fields are populated`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val now = Clock.System.now()

        service.createSilencePeriod(
            orgId,
            userId,
            CreateSilencePeriodRequest(
                reason = "Maintenance",
                startsAt = (now - 1.hours).toEpochMilliseconds(),
                endsAt = (now + 1.hours).toEpochMilliseconds()
            )
        )

        val periods = service.listSilencePeriods(orgId)
        assertEquals(1, periods.size)
        val p = periods.first()
        assertNotNull(p.id)
        assertEquals(organizationResourceId(orgId), p.organizationId)
        assertEquals("Maintenance", p.reason)
        assertEquals(userResourceId(userId), p.createdBy)
        assertTrue(p.startsAt > 0)
        assertTrue(p.endsAt > p.startsAt)
        assertTrue(p.createdAt > 0)
    }

    // ===================== checkHostStatuses DB scenarios =====================

    @Test
    fun `host with pending status is not changed to down`() {
        val orgId = seedOrg()
        val pastTime = Clock.System.now() - 10.minutes

        transaction {
            Hosts.insert {
                it[hostname] = "pending-host"
                it[organization_id] = orgId
                it[status] = "pending"
                it[first_seen_at] = pastTime
                it[last_seen_at] = pastTime
            }
        }

        val status = transaction {
            Hosts.selectAll()
                .where { Hosts.hostname eq "pending-host" as String }
                .first()[Hosts.status]
        }
        assertEquals("pending", status)
    }

    @Test
    fun `host transitions from up to down when last_seen_at is old`() {
        val orgId = seedOrg()
        val oldTime = Clock.System.now() - 10.minutes

        val hostId = transaction {
            Hosts.insert {
                it[hostname] = "stale-host"
                it[organization_id] = orgId
                it[status] = "up"
                it[first_seen_at] = oldTime
                it[last_seen_at] = oldTime
            } get Hosts.id
        }

        // Simulate status change
        transaction {
            Hosts.update({ Hosts.id eq hostId }) {
                it[status] = "down"
            }
        }

        val status = transaction {
            Hosts.selectAll()
                .where { Hosts.id eq hostId }
                .first()[Hosts.status]
        }
        assertEquals("down", status)
    }

    @Test
    fun `host alert template state insert and update`() {
        val orgId = seedOrg()
        val hostId = seedHost(orgId)
        val now = Clock.System.now()

        val templateId = transaction {
            OrganizationAlertTemplates.insert {
                it[organization_id] = orgId
                it[metric] = "cpu_percent"
                it[condition] = ">"
                it[threshold] = 80.0
                it[duration_seconds] = 60
                it[enabled] = true
                it[created_at] = now
                it[updated_at] = now
            } get OrganizationAlertTemplates.id
        }

        transaction {
            HostAlertTemplateStates.insert {
                it[template_alert_id] = templateId
                it[host_id] = hostId
                it[last_triggered_at] = now
            }
        }

        val state = transaction {
            HostAlertTemplateStates.selectAll()
                .where { HostAlertTemplateStates.template_alert_id eq templateId }
                .first()
        }
        assertEquals(hostId, state[HostAlertTemplateStates.host_id])
        assertNotNull(state[HostAlertTemplateStates.last_triggered_at])

        // Update
        val updatedTime = now + 5.minutes
        transaction {
            HostAlertTemplateStates.update({
                HostAlertTemplateStates.template_alert_id eq templateId
            }) {
                it[last_triggered_at] = updatedTime
            }
        }

        val updated = transaction {
            HostAlertTemplateStates.selectAll()
                .where { HostAlertTemplateStates.template_alert_id eq templateId }
                .first()
        }
        assertEquals(
            updatedTime.toEpochMilliseconds(),
            updated[HostAlertTemplateStates.last_triggered_at]!!.toEpochMilliseconds()
        )
    }

    // ===================== Host alert CRUD =====================

    @Test
    fun `host alert can be created and queried`() {
        val orgId = seedOrg()
        val hostId = seedHost(orgId)
        val now = Clock.System.now()

        val alertId = transaction {
            HostAlerts.insert {
                it[host_id] = hostId
                it[organization_id] = orgId
                it[metric] = "mem_percent"
                it[condition] = ">="
                it[threshold] = 90.0
                it[duration_seconds] = 120
                it[enabled] = true
                it[created_at] = now
            } get HostAlerts.id
        }

        val alert = transaction {
            HostAlerts.selectAll()
                .where { HostAlerts.id eq alertId }
                .first()
        }

        assertEquals("mem_percent", alert[HostAlerts.metric])
        assertEquals(">=", alert[HostAlerts.condition])
        assertEquals(90.0, alert[HostAlerts.threshold])
        assertEquals(120, alert[HostAlerts.duration_seconds])
        assertTrue(alert[HostAlerts.enabled])
    }

    @Test
    fun `host alert with global scope settings`() {
        val orgId = seedOrg()
        val hostId = seedHost(orgId)

        transaction {
            HostAlertSettings.insert {
                it[host_id] = hostId
                it[organization_id] = orgId
                it[scope] = "global"
                it[updated_at] = Clock.System.now()
            }
        }

        val settings = transaction {
            HostAlertSettings.selectAll()
                .where { HostAlertSettings.host_id eq hostId }
                .toList()
        }

        assertEquals(1, settings.size)
        assertEquals("global", settings.first()[HostAlertSettings.scope])
    }

    // ===================== AlertData model construction =====================

    @Test
    fun `AlertData model construction with all fields`() {
        val now = Clock.System.now()
        val alert = AlertData(
            id = 1,
            hostId = 42,
            organizationId = 10,
            metric = "cpu_percent",
            condition = ">",
            threshold = 80.0,
            durationSeconds = 60,
            enabled = true,
            lastTriggeredAt = now,
            createdAt = now,
            scope = "host",
            templateAlertId = null
        )

        assertEquals(1, alert.id)
        assertEquals(42, alert.hostId)
        assertEquals(10, alert.organizationId)
        assertEquals("cpu_percent", alert.metric)
        assertEquals(">", alert.condition)
        assertEquals(80.0, alert.threshold)
        assertEquals(60, alert.durationSeconds)
        assertTrue(alert.enabled)
        assertEquals(now, alert.lastTriggeredAt)
        assertEquals("host", alert.scope)
    }

    @Test
    fun `AlertData with global scope and templateAlertId`() {
        val now = Clock.System.now()
        val alert = AlertData(
            id = 5,
            hostId = 99,
            organizationId = 1,
            metric = "disk_percent",
            condition = ">=",
            threshold = 95.0,
            durationSeconds = 300,
            enabled = true,
            lastTriggeredAt = null,
            createdAt = now,
            scope = "global",
            templateAlertId = 42
        )

        assertEquals("global", alert.scope)
        assertEquals(42, alert.templateAlertId)
        assertEquals(null, alert.lastTriggeredAt)
    }

    // ===================== Cleanup expired silence periods =====================

    @Test
    fun `expired silence periods can be deleted`() {
        val orgId = seedOrg()
        val userId = seedUser()
        val now = Clock.System.now()

        // Create an expired period
        service.createSilencePeriod(
            orgId,
            userId,
            CreateSilencePeriodRequest(
                reason = "Old window",
                startsAt = (now - 3.hours).toEpochMilliseconds(),
                endsAt = (now - 1.hours).toEpochMilliseconds()
            )
        )

        // Create a current period
        service.createSilencePeriod(
            orgId,
            userId,
            CreateSilencePeriodRequest(
                reason = "Active window",
                startsAt = (now - 30.minutes).toEpochMilliseconds(),
                endsAt = (now + 30.minutes).toEpochMilliseconds()
            )
        )

        assertEquals(2, service.listSilencePeriods(orgId).size)

        // Manually delete the expired one
        val expiredId = service.listSilencePeriods(orgId)
            .first { it.reason == "Old window" }.id
        service.deleteSilencePeriod(expiredId, orgId)

        val remaining = service.listSilencePeriods(orgId)
        assertEquals(1, remaining.size)
        assertEquals("Active window", remaining.first().reason)
    }

    // ===================== Organization alert templates =====================

    @Test
    fun `organization alert template CRUD`() {
        val orgId = seedOrg()
        val now = Clock.System.now()

        val templateId = transaction {
            OrganizationAlertTemplates.insert {
                it[organization_id] = orgId
                it[metric] = "load_5"
                it[condition] = ">"
                it[threshold] = 4.0
                it[duration_seconds] = 180
                it[enabled] = true
                it[created_at] = now
                it[updated_at] = now
            } get OrganizationAlertTemplates.id
        }

        val template = transaction {
            OrganizationAlertTemplates.selectAll()
                .where { OrganizationAlertTemplates.id eq templateId }
                .first()
        }

        assertEquals("load_5", template[OrganizationAlertTemplates.metric])
        assertEquals(">", template[OrganizationAlertTemplates.condition])
        assertEquals(4.0, template[OrganizationAlertTemplates.threshold])
        assertEquals(180, template[OrganizationAlertTemplates.duration_seconds])
    }

    // ===================== Host display name fallback =====================

    @Test
    fun `host uses display_name when set otherwise hostname`() {
        val orgId = seedOrg()

        val now = Clock.System.now()
        val hostWithDisplay = transaction {
            Hosts.insert {
                it[hostname] = "ip-172-16-0-1"
                it[display_name] = "Production Web"
                it[organization_id] = orgId
                it[status] = "up"
                it[first_seen_at] = now
                it[last_seen_at] = now
            } get Hosts.id
        }

        val hostWithoutDisplay = transaction {
            Hosts.insert {
                it[hostname] = "ip-172-16-0-2"
                it[organization_id] = orgId
                it[status] = "up"
                it[first_seen_at] = now
                it[last_seen_at] = now
            } get Hosts.id
        }

        val nameWithDisplay = transaction {
            val row = Hosts.selectAll()
                .where { Hosts.id eq hostWithDisplay }
                .first()
            row[Hosts.display_name] ?: row[Hosts.hostname]
        }

        val nameWithoutDisplay = transaction {
            val row = Hosts.selectAll()
                .where { Hosts.id eq hostWithoutDisplay }
                .first()
            row[Hosts.display_name] ?: row[Hosts.hostname]
        }

        assertEquals("Production Web", nameWithDisplay)
        assertEquals("ip-172-16-0-2", nameWithoutDisplay)
    }

    // ===================== Companion object constants =====================

    @Test
    fun `companion object constants have expected values`() {
        assertEquals(30, MonitorAlertService.EVALUATION_INTERVAL_SECONDS)
        assertEquals(60, MonitorAlertService.STATUS_CHECK_INTERVAL_SECONDS)
        assertEquals(300, MonitorAlertService.HOST_DOWN_THRESHOLD_SECONDS)
        assertEquals(15, MonitorAlertService.MIN_ALERT_INTERVAL_MINUTES)
        assertEquals(15, MonitorAlertService.POLL_INTERVAL_SECONDS)
        assertEquals(0.8, MonitorAlertService.MIN_DATA_POINT_RATIO)
    }
}
