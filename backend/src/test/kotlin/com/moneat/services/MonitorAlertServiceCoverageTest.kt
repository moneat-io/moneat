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

import com.moneat.config.ClickHouseClient
import com.moneat.config.RedisConfig
import com.moneat.alerts.models.AlertSeverity
import com.moneat.alerts.models.AlertSource
import com.moneat.incident.services.IncidentService
import com.moneat.monitor.models.AlertData
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.shared.models.AlertSilencePeriods
import com.moneat.shared.models.HostAlertSettings
import com.moneat.shared.models.HostAlertTemplateStates
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrganizationAlertTemplates
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.workflows.services.WorkflowService
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MonitorAlertServiceCoverageTest {

    companion object {
        private var db: Database? = null
    }

    private lateinit var incidentService: IncidentService
    private lateinit var workflowService: WorkflowService
    private lateinit var service: MonitorAlertService

    private data class HostAlertFixture(
        val orgId: Int,
        val hostId: Int,
        val alert: AlertData
    )

    @BeforeTest
    fun setup() {
        mockkStatic(HttpResponse::bodyAsText)
        if (db == null) {
            db =
                Database.connect(
                    url = "jdbc:h2:mem:moneat_monitor_alert_cov;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver"
                )
        }
        TransactionManager.defaultDatabase = db

        TestDatabaseHelper.resetSchema(
            Users,
            Organizations,
            Memberships,
            AlertSilencePeriods,
            Hosts,
            HostAlerts,
            HostAlertSettings,
            OrganizationAlertTemplates,
            HostAlertTemplateStates
        )

        incidentService = mockk(relaxed = true)
        workflowService = mockk(relaxed = true)
        service =
            MonitorAlertService(
                incidentService = incidentService,
                workflowService = workflowService,
            )

        mockkObject(ClickHouseClient, RedisConfig)
        every { RedisConfig.isConnected() } returns false
        every { ClickHouseClient.getDatabase() } returns "testdb"
    }

    @AfterTest
    fun teardown() {
        unmockkObject(ClickHouseClient, RedisConfig)
        unmockkStatic(HttpResponse::bodyAsText)
    }

    private suspend fun callPrivateSuspend(name: String, vararg args: Any?): Any? {
        val fn =
            MonitorAlertService::class.declaredFunctions.single { f ->
                f.name == name && f.parameters.drop(1).size == args.size
            }
        fn.isAccessible = true
        return fn.callSuspend(service, *args)
    }

    private fun callPrivate(name: String, vararg args: Any?): Any? {
        val fn =
            MonitorAlertService::class.declaredFunctions.single { f ->
                f.name == name && f.parameters.drop(1).size == args.size
            }
        fn.isAccessible = true
        return fn.call(service, *args)
    }

    private fun createHostAlertFixture(incidentSeverity: String? = "HIGH"): HostAlertFixture {
        val now = Clock.System.now()
        return transaction {
            val orgId =
                Organizations.insert {
                    it[name] = "Host Alert Workflow Org"
                    it[slug] = "host-alert-workflow-org"
                } get Organizations.id
            val hostId =
                Hosts.insert {
                    it[hostname] = "host-alert-workflow"
                    it[organization_id] = orgId
                    it[status] = "up"
                    it[first_seen_at] = now
                    it[last_seen_at] = now
                } get Hosts.id
            val alertId =
                HostAlerts.insert {
                    it[host_id] = hostId
                    it[organization_id] = orgId
                    it[metric] = "cpu_percent"
                    it[condition] = ">"
                    it[threshold] = 80.0
                    it[duration_seconds] = 0
                    it[enabled] = true
                    it[last_triggered_at] = null
                    it[incident_severity] = incidentSeverity
                    it[created_at] = now
                } get HostAlerts.id

            HostAlertFixture(
                orgId = orgId,
                hostId = hostId,
                alert =
                AlertData(
                    id = alertId,
                    hostId = hostId,
                    organizationId = orgId,
                    metric = "cpu_percent",
                    condition = ">",
                    threshold = 80.0,
                    durationSeconds = 0,
                    enabled = true,
                    lastTriggeredAt = null,
                    createdAt = now,
                    scope = MonitorService.ALERT_SCOPE_HOST,
                    templateAlertId = null,
                )
            )
        }
    }

    @Test
    fun `evaluateAlerts completes with no alerts in database`() =
        runBlocking {
            callPrivateSuspend("evaluateAlerts")
        }

    @Test
    fun `getCurrentMetricValue parses cpu_percent JSONCompact response`() =
        runBlocking {
            val body = """{"data":[["42.25"]]}"""
            val http = mockk<HttpResponse>()
            val queries = mutableListOf<String>()
            every { http.status } returns HttpStatusCode.OK
            coEvery { http.bodyAsText(any()) } returns body
            coEvery { ClickHouseClient.execute(any()) } coAnswers {
                queries.add(firstArg())
                http
            }

            val v = callPrivateSuspend("getCurrentMetricValue", 1, 99, "cpu_percent") as Double?
            assertEquals(42.25, v!!, 0.001)
            assertTrue(
                queries.single().contains("timestamp >= now64(3) - INTERVAL 10 MINUTE")
            )
        }

    @Test
    fun `getCurrentMetricValue returns null for unknown metric`() =
        runBlocking {
            val v = callPrivateSuspend("getCurrentMetricValue", 1, 1, "not_a_metric") as Double?
            assertNull(v)
        }

    @Test
    fun `sendAlertNotification runs with no recipients configured`() =
        runBlocking {
            val alert =
                AlertData(
                    id = 1,
                    hostId = 1,
                    organizationId = 1,
                    metric = "cpu_percent",
                    condition = ">",
                    threshold = 80.0,
                    durationSeconds = 0,
                    enabled = true,
                    lastTriggeredAt = null,
                    createdAt = Clock.System.now(),
                    scope = MonitorService.ALERT_SCOPE_HOST,
                    templateAlertId = null,
                )
            callPrivateSuspend("sendAlertNotification", alert, "host-a", 1, 91.0)

            coVerify(exactly = 1) {
                workflowService.publishAlertTriggered(any())
            }
        }

    @Test
    fun `handleRecoveredAlert publishes workflow and resolves configured incident`() =
        runBlocking {
            val fixture = createHostAlertFixture()
            val alertKey = "alert_state:${fixture.hostId}:id_${fixture.alert.id}"
            val deduplicationKey = "moneat-host-alert-${fixture.hostId}-id_${fixture.alert.id}"
            val redis = mockk<RedisCommands<String, String>>()
            every { RedisConfig.isConnected() } returns true
            every { RedisConfig.sync() } returns redis
            every { redis.get(alertKey) } returns "TRIGGERED"
            every { redis.del(alertKey) } returns 1L

            callPrivateSuspend("handleRecoveredAlert", fixture.alert, "host-alert-workflow", fixture.orgId, alertKey)

            verify { redis.get(alertKey) }
            verify { redis.del(alertKey) }
            val clearedLastTriggeredAt =
                transaction {
                    HostAlerts
                        .selectAll()
                        .where { HostAlerts.id eq fixture.alert.id }
                        .first()[HostAlerts.last_triggered_at]
                }
            assertNull(clearedLastTriggeredAt)
            coVerify(exactly = 1) {
                workflowService.publishAlertResolved(
                    fixture.orgId,
                    AlertSource.HOST_ALERT.name,
                    deduplicationKey,
                    any(),
                    any(),
                    any(),
                    AlertSeverity.HIGH,
                )
            }
            coVerify(exactly = 1) {
                incidentService.autoResolveAlert(
                    fixture.orgId,
                    AlertSource.HOST_ALERT,
                    deduplicationKey,
                    any(),
                    any(),
                    any(),
                    false,
                )
            }
        }

    @Test
    fun `handleRecoveredAlert falls back to persisted trigger state when Redis is empty`() =
        runBlocking {
            val fixture = createHostAlertFixture()
            val alertKey = "alert_state:${fixture.hostId}:id_${fixture.alert.id}"
            val deduplicationKey = "moneat-host-alert-${fixture.hostId}-id_${fixture.alert.id}"
            val triggeredAt = Clock.System.now() - 1.minutes
            val triggeredAlert = fixture.alert.copy(lastTriggeredAt = triggeredAt)
            transaction {
                HostAlerts.update({ HostAlerts.id eq fixture.alert.id }) {
                    it[last_triggered_at] = triggeredAt
                }
            }

            callPrivateSuspend("handleRecoveredAlert", triggeredAlert, "host-alert-workflow", fixture.orgId, alertKey)

            coVerify(exactly = 1) {
                workflowService.publishAlertResolved(
                    fixture.orgId,
                    AlertSource.HOST_ALERT.name,
                    deduplicationKey,
                    any(),
                    any(),
                    any(),
                    AlertSeverity.HIGH,
                )
            }
            val clearedLastTriggeredAt =
                transaction {
                    HostAlerts
                        .selectAll()
                        .where { HostAlerts.id eq fixture.alert.id }
                        .first()[HostAlerts.last_triggered_at]
                }
            assertNull(clearedLastTriggeredAt)
        }

    @Test
    fun `handleRecoveredAlert clears global template trigger state`() =
        runBlocking {
            val now = Clock.System.now()
            val triggeredAt = now - 1.minutes
            val (orgId, hostId, templateId) =
                transaction {
                    val organizationId =
                        Organizations.insert {
                            it[name] = "Global Alert Workflow Org"
                            it[slug] = "global-alert-workflow-org"
                        } get Organizations.id
                    val host =
                        Hosts.insert {
                            it[hostname] = "global-alert-workflow"
                            it[organization_id] = organizationId
                            it[status] = "up"
                            it[first_seen_at] = now
                            it[last_seen_at] = now
                        } get Hosts.id
                    val template =
                        OrganizationAlertTemplates.insert {
                            it[organization_id] = organizationId
                            it[metric] = "cpu_percent"
                            it[condition] = ">"
                            it[threshold] = 80.0
                            it[duration_seconds] = 0
                            it[enabled] = true
                            it[incident_severity] = null
                            it[created_at] = now
                            it[updated_at] = now
                        } get OrganizationAlertTemplates.id
                    HostAlertTemplateStates.insert {
                        it[template_alert_id] = template
                        it[host_id] = host
                        it[last_triggered_at] = triggeredAt
                    }
                    Triple(organizationId, host, template)
                }
            val alert =
                AlertData(
                    id = templateId,
                    hostId = hostId,
                    organizationId = orgId,
                    metric = "cpu_percent",
                    condition = ">",
                    threshold = 80.0,
                    durationSeconds = 0,
                    enabled = true,
                    lastTriggeredAt = triggeredAt,
                    createdAt = now,
                    scope = MonitorService.ALERT_SCOPE_GLOBAL,
                    templateAlertId = templateId,
                )
            val alertKey = "alert_state:$hostId:tpl_$templateId"
            val deduplicationKey = "moneat-host-alert-$hostId-tpl_$templateId"

            callPrivateSuspend("handleRecoveredAlert", alert, "global-alert-workflow", orgId, alertKey)

            coVerify(exactly = 1) {
                workflowService.publishAlertResolved(
                    orgId,
                    AlertSource.HOST_ALERT.name,
                    deduplicationKey,
                    any(),
                    any(),
                    any(),
                    AlertSeverity.HIGH,
                )
            }
            val clearedTemplateState =
                transaction {
                    HostAlertTemplateStates
                        .selectAll()
                        .where {
                            (HostAlertTemplateStates.template_alert_id eq templateId) and
                                (HostAlertTemplateStates.host_id eq hostId)
                        }
                        .first()[HostAlertTemplateStates.last_triggered_at]
                }
            assertNull(clearedTemplateState)
        }

    @Test
    fun `triggerAlert records state timestamp and publishes workflow`() =
        runBlocking {
            val fixture = createHostAlertFixture()
            val alertKey = "alert_state:${fixture.hostId}:id_${fixture.alert.id}"
            val deduplicationKey = "moneat-host-alert-${fixture.hostId}-id_${fixture.alert.id}"
            val redis = mockk<RedisCommands<String, String>>()
            every { RedisConfig.isConnected() } returns true
            every { RedisConfig.sync() } returns redis
            every { redis.set(alertKey, "TRIGGERED") } returns "OK"

            callPrivateSuspend(
                "triggerAlert",
                fixture.alert,
                "host-alert-workflow",
                fixture.orgId,
                91.0,
                alertKey,
                Clock.System.now(),
            )

            verify { redis.set(alertKey, "TRIGGERED") }
            val lastTriggeredAt =
                transaction {
                    HostAlerts
                        .selectAll()
                        .where { HostAlerts.id eq fixture.alert.id }
                        .first()[HostAlerts.last_triggered_at]
                }
            assertNotNull(lastTriggeredAt)
            coVerify(exactly = 1) {
                workflowService.publishAlertTriggered(
                    match {
                        it.source == AlertSource.HOST_ALERT &&
                            it.deduplicationKey == deduplicationKey &&
                            it.organizationId == fixture.orgId
                    }
                )
            }
            coVerify(exactly = 1) {
                incidentService.fireAlert(any(), false)
            }
        }

    // ──── Key helpers ────

    @Test
    fun `host alert keys use stable alert identity`() {
        val now = Clock.System.now()
        val directAlert =
            AlertData(
                id = 7,
                hostId = 42,
                organizationId = 1,
                metric = "cpu_percent",
                condition = ">",
                threshold = 80.0,
                durationSeconds = 0,
                enabled = true,
                lastTriggeredAt = null,
                createdAt = now,
                scope = MonitorService.ALERT_SCOPE_HOST,
                templateAlertId = null,
            )
        val templateAlert = directAlert.copy(id = 8, templateAlertId = 17)

        assertEquals("alert_state:42:id_7", callPrivate("hostAlertRedisKey", directAlert))
        assertEquals("moneat-host-alert-42-id_7", callPrivate("hostAlertDedupKey", directAlert))
        assertEquals("alert_state:42:tpl_17", callPrivate("hostAlertRedisKey", templateAlert))
        assertEquals("moneat-host-alert-42-tpl_17", callPrivate("hostAlertDedupKey", templateAlert))
    }

    // ──── Host status recovery ────

    @Test
    fun `checkHostStatuses transitions stale host to down`() =
        runBlocking {
            val orgId =
                transaction {
                    Organizations.insert {
                        it[name] = "Host Org"
                        it[slug] = "host-org"
                    } get Organizations.id
                }
            val old = Clock.System.now() - 10.minutes
            val hostId =
                transaction {
                    Hosts.insert {
                        it[hostname] = "stale"
                        it[organization_id] = orgId
                        it[status] = "up"
                        it[first_seen_at] = old
                        it[last_seen_at] = old
                    } get Hosts.id
                }

            callPrivateSuspend("checkHostStatuses")

            val status =
                transaction {
                    Hosts.selectAll().where { Hosts.id eq hostId }.first()[Hosts.status]
                }
            assertEquals("down", status)
        }

    @Test
    fun `checkHostStatuses resolves host down incident when host recovers`() =
        runBlocking {
            val orgId =
                transaction {
                    Organizations.insert {
                        it[name] = "Recovered Host Org"
                        it[slug] = "recovered-host-org"
                    } get Organizations.id
                }
            val now = Clock.System.now()
            val hostId =
                transaction {
                    Hosts.insert {
                        it[hostname] = "recovered"
                        it[organization_id] = orgId
                        it[status] = "down"
                        it[first_seen_at] = now - 10.minutes
                        it[last_seen_at] = now
                    } get Hosts.id
                }

            callPrivateSuspend("checkHostStatuses")

            val status =
                transaction {
                    Hosts.selectAll().where { Hosts.id eq hostId }.first()[Hosts.status]
                }
            assertEquals("up", status)
            coVerify(exactly = 1) {
                incidentService.autoResolveAlert(
                    orgId,
                    AlertSource.HOST_DOWN,
                    "moneat-host-down-$hostId",
                    any(),
                    any(),
                    any(),
                    true,
                )
            }

            callPrivateSuspend("checkHostStatuses")

            coVerify(exactly = 1) {
                incidentService.autoResolveAlert(
                    orgId,
                    AlertSource.HOST_DOWN,
                    "moneat-host-down-$hostId",
                    any(),
                    any(),
                    any(),
                    true,
                )
            }
        }

    @Test
    fun `checkHostStatuses resolves recovered host while notifications are silenced`() =
        runBlocking {
            val orgId =
                transaction {
                    Organizations.insert {
                        it[name] = "Silenced Recovery Org"
                        it[slug] = "silenced-recovery-org"
                    } get Organizations.id
                }
            val userId =
                transaction {
                    Users.insert {
                        it[email] = "silenced-recovery@test.com"
                        it[password_hash] = "x"
                        it[name] = "Silenced Recovery"
                        it[email_verified] = true
                    } get Users.id
                }
            val now = Clock.System.now()
            val hostId =
                transaction {
                    AlertSilencePeriods.insert {
                        it[organization_id] = orgId
                        it[reason] = "maintenance"
                        it[starts_at] = now - 1.minutes
                        it[ends_at] = now + 1.hours
                        it[created_by] = userId
                        it[created_at] = now
                    }
                    Hosts.insert {
                        it[hostname] = "silenced-recovery"
                        it[organization_id] = orgId
                        it[status] = "down"
                        it[first_seen_at] = now - 10.minutes
                        it[last_seen_at] = now
                    } get Hosts.id
                }

            callPrivateSuspend("checkHostStatuses")

            val status =
                transaction {
                    Hosts.selectAll().where { Hosts.id eq hostId }.first()[Hosts.status]
                }
            assertEquals("up", status)
            coVerify(exactly = 1) {
                incidentService.autoResolveAlert(
                    orgId,
                    AlertSource.HOST_DOWN,
                    "moneat-host-down-$hostId",
                    any(),
                    any(),
                    any(),
                    true,
                )
            }
        }

    @Test
    fun `checkHostStatuses retries host down incident resolution before marking recovered`() =
        runBlocking {
            val orgId =
                transaction {
                    Organizations.insert {
                        it[name] = "Retry Recovery Org"
                        it[slug] = "retry-recovery-org"
                    } get Organizations.id
                }
            val now = Clock.System.now()
            val hostId =
                transaction {
                    Hosts.insert {
                        it[hostname] = "retry-recovered"
                        it[organization_id] = orgId
                        it[status] = "down"
                        it[first_seen_at] = now - 10.minutes
                        it[last_seen_at] = now
                    } get Hosts.id
                }
            val deduplicationKey = "moneat-host-down-$hostId"
            coEvery {
                incidentService.autoResolveAlert(
                    orgId,
                    AlertSource.HOST_DOWN,
                    deduplicationKey,
                    any(),
                    any(),
                    any(),
                    true,
                )
            } throws RuntimeException("resolve failed")

            callPrivateSuspend("checkHostStatuses")

            val failedStatus =
                transaction {
                    Hosts.selectAll().where { Hosts.id eq hostId }.first()[Hosts.status]
                }
            assertEquals("down", failedStatus)

            coEvery {
                incidentService.autoResolveAlert(
                    orgId,
                    AlertSource.HOST_DOWN,
                    deduplicationKey,
                    any(),
                    any(),
                    any(),
                    true,
                )
            } returns Unit

            callPrivateSuspend("checkHostStatuses")

            val recoveredStatus =
                transaction {
                    Hosts.selectAll().where { Hosts.id eq hostId }.first()[Hosts.status]
                }
            assertEquals("up", recoveredStatus)
            coVerify(exactly = 2) {
                incidentService.autoResolveAlert(
                    orgId,
                    AlertSource.HOST_DOWN,
                    deduplicationKey,
                    any(),
                    any(),
                    any(),
                    true,
                )
            }
        }

    // ──── Silence cleanup ────

    @Test
    fun `cleanupExpiredSilencePeriods removes ended rows`() {
        val orgId =
            transaction {
                Organizations.insert {
                    it[name] = "Silence Org"
                    it[slug] = "silence-org"
                } get Organizations.id
            }
        val userId =
            transaction {
                Users.insert {
                    it[email] = "silence@test.com"
                    it[password_hash] = "x"
                    it[name] = "S"
                    it[email_verified] = true
                } get Users.id
            }
        val now = Clock.System.now()
        val standalone = MonitorAlertService()
        standalone.createSilencePeriod(
            organizationId = orgId,
            userId = userId,
            request =
            CreateSilencePeriodRequest(
                reason = "expired",
                startsAt = (now - 2.hours).toEpochMilliseconds(),
                endsAt = (now - 1.hours).toEpochMilliseconds(),
            ),
        )
        assertEquals(1, standalone.listSilencePeriods(orgId).size)

        val m =
            MonitorAlertService::class.java.getDeclaredMethod("cleanupExpiredSilencePeriods").apply {
                isAccessible = true
            }
        m.invoke(standalone)

        assertTrue(standalone.listSilencePeriods(orgId).isEmpty())
    }
}
