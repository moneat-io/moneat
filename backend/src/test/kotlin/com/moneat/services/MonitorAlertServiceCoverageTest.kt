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
import com.moneat.alerts.models.AlertSource
import com.moneat.alerts.models.AlertStatus
import com.moneat.alerts.services.AlertFanoutArm
import com.moneat.alerts.services.AlertFanoutOutcome
import com.moneat.alerts.services.AlertFanoutPlan
import com.moneat.alerts.services.AlertFanoutState
import com.moneat.alerts.services.AlertLifecycleOrchestrator
import com.moneat.incident.services.IncidentService
import com.moneat.monitor.models.AlertData
import com.moneat.monitor.models.CreateSilencePeriodRequest
import com.moneat.monitor.services.CurrentMetricReading
import com.moneat.monitor.services.MonitorAlertService
import com.moneat.monitor.services.MonitorService
import com.moneat.monitor.services.diskResourceLabel
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
import kotlin.test.assertFalse
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
    private lateinit var alertOrchestrator: AlertLifecycleOrchestrator
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
        alertOrchestrator = mockk(relaxed = true)
        coEvery { workflowService.publishAlertTriggered(any()) } returns true
        service =
            MonitorAlertService(
                incidentService = incidentService,
                workflowService = workflowService,
                alertOrchestrator = alertOrchestrator,
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

    private fun createHostAlertFixture(alertPriority: String? = "HIGH"): HostAlertFixture {
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
                    it[alert_priority] = alertPriority
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

            val v = service.getCurrentMetricValue(1, 99, "cpu_percent")
            assertEquals(42.25, v!!, 0.001)
            assertTrue(
                queries.single().contains("timestamp >= now64(3) - INTERVAL 10 MINUTE")
            )
            assertTrue(queries.single().contains("metrics_latest_by_host"))
            assertTrue(queries.single().contains("host_id = 1"))
        }

    @Test
    fun `currentMetricValueQuery calculates disk percent per disk identity`() {
        val query = service.currentMetricValueQuery(153, 1, "disk_percent")

        assertNotNull(query)
        assertTrue(query.contains("metric_name IN ('system.disk.percent','system.disk.used','system.disk.total')"))
        assertTrue(query.contains("metric_identity != ''"))
        assertTrue(query.contains("GROUP BY disk_identity"))
        assertTrue(query.contains("tags['device_name']"))
        assertTrue(query.contains("tags['mountpoint']"))
        assertTrue(query.contains("pct AS value"))
        assertTrue(query.contains("disk_identity"))
        assertTrue(query.contains("ORDER BY pct DESC, disk_identity ASC"))
        assertTrue(query.contains("LIMIT 1"))
        assertTrue(query.contains("argMaxIf(value, timestamp, metric_name = 'system.disk.percent')"))
        assertTrue(query.contains("argMaxIf(value, timestamp, metric_name = 'system.disk.used')"))
        assertTrue(query.contains("argMaxIf(value, timestamp, metric_name = 'system.disk.total')"))
        assertFalse(query.contains("system.disk.in_use"))
        assertFalse(query.contains("CASE WHEN metric_name='system.disk.used'"))
    }

    @Test
    fun `currentMetricValueQuery calculates memory percent with normalized fallback metrics`() {
        val query = service.currentMetricValueQuery(153, 1, "mem_percent")

        assertNotNull(query)
        assertFalse(query.contains("system.mem.pct_usable"))
        assertTrue(query.contains("system.mem.available"))
        assertTrue(query.contains("system.mem.used"))
        assertTrue(query.contains("system.mem.total"))
        assertTrue(query.indexOf("system.mem.available") < query.indexOf("system.mem.used"))
        assertTrue(query.contains("countIf(metric_name = 'system.mem.available') > 0"))
        assertTrue(query.contains("argMaxIf(value, timestamp, metric_name = 'system.mem.available')"))
        assertTrue(query.contains("argMaxIf(value, timestamp, metric_name = 'system.mem.used')"))
    }

    @Test
    fun `checkSustainedCondition calculates memory percent with normalized fallback metrics`() =
        runBlocking {
            val body = """{"data":[["1"]]}"""
            val http = mockk<HttpResponse>()
            val queries = mutableListOf<String>()
            every { http.status } returns HttpStatusCode.OK
            coEvery { http.bodyAsText(any()) } returns body
            coEvery { ClickHouseClient.execute(any()) } coAnswers {
                queries.add(firstArg())
                http
            }
            val alert =
                AlertData(
                    id = 1,
                    hostId = 153,
                    organizationId = 1,
                    metric = "mem_percent",
                    condition = ">",
                    threshold = 80.0,
                    durationSeconds = 60,
                    enabled = true,
                    lastTriggeredAt = null,
                    createdAt = Clock.System.now(),
                    scope = MonitorService.ALERT_SCOPE_HOST,
                    templateAlertId = null,
                )

            val result = callPrivateSuspend("checkSustainedCondition", alert) as Boolean

            assertTrue(result)
            val query = queries.single()
            assertTrue(query.contains("metrics_rollup_1m"))
            assertFalse(query.contains("system.mem.pct_usable"))
            assertTrue(query.contains("sumIf(value_count, metric_name = 'system.mem.available') > 0"))
            assertTrue(query.indexOf("system.mem.available") < query.indexOf("system.mem.used"))
        }

    @Test
    fun `checkSustainedCondition calculates disk percent with normalized fallback metrics`() =
        runBlocking {
            val body = """{"data":[["2"]]}"""
            val http = mockk<HttpResponse>()
            val queries = mutableListOf<String>()
            every { http.status } returns HttpStatusCode.OK
            coEvery { http.bodyAsText(any()) } returns body
            coEvery { ClickHouseClient.execute(any()) } coAnswers {
                queries.add(firstArg())
                http
            }
            val alert =
                AlertData(
                    id = 1,
                    hostId = 153,
                    organizationId = 1,
                    metric = "disk_percent",
                    condition = "<=",
                    threshold = 90.0,
                    durationSeconds = 120,
                    enabled = true,
                    lastTriggeredAt = null,
                    createdAt = Clock.System.now(),
                    scope = MonitorService.ALERT_SCOPE_HOST,
                    templateAlertId = null,
                )

            val result = callPrivateSuspend("checkSustainedCondition", alert) as Boolean

            assertTrue(result)
            val query = queries.single()
            assertTrue(query.contains("metrics_rollup_1m"))
            assertTrue(query.contains("metric_name = 'system.disk.percent'"))
            assertTrue(query.contains("metric_name = 'system.disk.used'"))
            assertTrue(query.contains("metric_name = 'system.disk.total'"))
            assertTrue(query.contains("GROUP BY bucket_start, metric_identity"))
            assertTrue(query.contains("SELECT bucket_start, max(pct) as pct"))
            assertTrue(query.contains("HAVING pct <= 90.0"))
            assertFalse(query.contains("system.disk.in_use"))
        }

    @Test
    fun `checkSustainedCondition builds scalar sustained condition query`() =
        runBlocking {
            val body = """{"data":[["1"]]}"""
            val http = mockk<HttpResponse>()
            val queries = mutableListOf<String>()
            every { http.status } returns HttpStatusCode.OK
            coEvery { http.bodyAsText(any()) } returns body
            coEvery { ClickHouseClient.execute(any()) } coAnswers {
                queries.add(firstArg())
                http
            }
            val alert =
                AlertData(
                    id = 1,
                    hostId = 153,
                    organizationId = 1,
                    metric = "load_1",
                    condition = ">=",
                    threshold = 1.0,
                    durationSeconds = 60,
                    enabled = true,
                    lastTriggeredAt = null,
                    createdAt = Clock.System.now(),
                    scope = MonitorService.ALERT_SCOPE_HOST,
                    templateAlertId = null,
                )

            val result = callPrivateSuspend("checkSustainedCondition", alert) as Boolean

            assertTrue(result)
            val query = queries.single()
            assertTrue(query.contains("metric_name = 'system.load.1'"))
            assertTrue(query.contains("HAVING value >= 1.0"))
        }

    @Test
    fun `checkSustainedCondition returns false for invalid condition without querying`() =
        runBlocking {
            val alert =
                AlertData(
                    id = 1,
                    hostId = 153,
                    organizationId = 1,
                    metric = "mem_percent",
                    condition = "gt",
                    threshold = 80.0,
                    durationSeconds = 60,
                    enabled = true,
                    lastTriggeredAt = null,
                    createdAt = Clock.System.now(),
                    scope = MonitorService.ALERT_SCOPE_HOST,
                    templateAlertId = null,
                )

            val result = callPrivateSuspend("checkSustainedCondition", alert) as Boolean

            assertFalse(result)
            coVerify(exactly = 0) { ClickHouseClient.execute(any()) }
        }

    @Test
    fun `currentMetricValueQuery builds latest value queries for scalar metrics`() {
        val metrics =
            mapOf(
                "cpu_percent" to "system.cpu.percent",
                "load_1" to "system.load.1",
                "load_5" to "system.load.5",
                "load_15" to "system.load.15",
                "temp_max" to "system.temp.max",
                "gpu_percent" to "system.gpu.percent",
                "battery_percent" to "system.battery.percent",
            )

        metrics.forEach { (metric, metricName) ->
            val query = service.currentMetricValueQuery(153, 1, metric)

            assertNotNull(query)
            assertTrue(query.contains("argMax(value, timestamp)"))
            assertTrue(query.contains("metric_name = '$metricName'"))
        }
    }

    @Test
    fun `getCurrentMetricValue parses disk_percent JSONCompact response`() =
        runBlocking {
            val body = """{"data":[["87.5"]]}"""
            val http = mockk<HttpResponse>()
            val queries = mutableListOf<String>()
            every { http.status } returns HttpStatusCode.OK
            coEvery { http.bodyAsText(any()) } returns body
            coEvery { ClickHouseClient.execute(any()) } coAnswers {
                queries.add(firstArg())
                http
            }

            val v = service.getCurrentMetricValue(153, 1, "disk_percent")

            assertEquals(87.5, v!!, 0.001)
            assertFalse(queries.single().contains("system.disk.in_use"))
            assertTrue(queries.single().contains("GROUP BY disk_identity"))
            assertTrue(queries.single().contains("system.disk.percent"))
        }

    @Test
    fun `getCurrentMetricReading preserves the fullest disk identity`() =
        runBlocking {
            val body =
                """{"data":[["87.5","device_name=sda|mount_point=/mnt/volume_nyc3_01"]]}"""
            val http = mockk<HttpResponse>()
            every { http.status } returns HttpStatusCode.OK
            coEvery { http.bodyAsText(any()) } returns body
            coEvery { ClickHouseClient.execute(any()) } returns http

            val reading = service.getCurrentMetricReading(153, 1, "disk_percent")

            assertEquals(87.5, reading?.value)
            assertEquals("/dev/sda at /mnt/volume_nyc3_01", reading?.resourceLabel)
        }

    @Test
    fun `diskResourceLabel supports device mount and legacy identities`() {
        assertEquals(
            "/dev/sda at /mnt/data",
            diskResourceLabel("device_name=sda|mountpoint=/mnt/data"),
        )
        assertEquals("/dev/vda1", diskResourceLabel("vda1"))
        assertNull(diskResourceLabel("default"))
    }

    @Test
    fun `getCurrentMetricValue returns null for unsuccessful ClickHouse response`() =
        runBlocking {
            val http = mockk<HttpResponse>()
            every { http.status } returns HttpStatusCode.InternalServerError
            coEvery { ClickHouseClient.execute(any()) } returns http

            val v = service.getCurrentMetricValue(1, 99, "cpu_percent")

            assertNull(v)
        }

    @Test
    fun `getCurrentMetricValue returns null when ClickHouse execution fails`() =
        runBlocking {
            coEvery { ClickHouseClient.execute(any()) } throws IllegalStateException("boom")

            val v = service.getCurrentMetricValue(1, 99, "cpu_percent")

            assertNull(v)
        }

    @Test
    fun `getCurrentMetricValue returns null for blank ClickHouse body`() =
        runBlocking {
            val http = mockk<HttpResponse>()
            every { http.status } returns HttpStatusCode.OK
            coEvery { http.bodyAsText(any()) } returns ""
            coEvery { ClickHouseClient.execute(any()) } returns http

            val v = service.getCurrentMetricValue(1, 99, "cpu_percent")

            assertNull(v)
        }

    @Test
    fun `getCurrentMetricValue returns null for missing ClickHouse data`() =
        runBlocking {
            val http = mockk<HttpResponse>()
            every { http.status } returns HttpStatusCode.OK
            coEvery { http.bodyAsText(any()) } returns """{"data":[]}"""
            coEvery { ClickHouseClient.execute(any()) } returns http

            val v = service.getCurrentMetricValue(1, 99, "cpu_percent")

            assertNull(v)
        }

    @Test
    fun `getCurrentMetricValue returns null for unknown metric`() =
        runBlocking {
            val v = service.getCurrentMetricValue(1, 1, "not_a_metric")
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
                alertOrchestrator.process(any(), AlertFanoutPlan.WORKFLOW_ONLY)
            }
        }

    @Test
    fun `disk alert notification identifies the fullest filesystem`() =
        runBlocking {
            val alert =
                AlertData(
                    id = 1,
                    hostId = 1,
                    organizationId = 1,
                    metric = "disk_percent",
                    condition = ">",
                    threshold = 75.0,
                    durationSeconds = 300,
                    enabled = true,
                    lastTriggeredAt = null,
                    createdAt = Clock.System.now(),
                    scope = MonitorService.ALERT_SCOPE_HOST,
                    templateAlertId = null,
                )

            service.sendAlertNotificationWithContext(
                alert,
                "host-a",
                1,
                CurrentMetricReading(81.2, "/dev/sda at /mnt/volume_nyc3_01"),
            )

            coVerify(exactly = 1) {
                alertOrchestrator.process(
                    match {
                        it.title ==
                            "host-a - Disk Usage (/dev/sda at /mnt/volume_nyc3_01) > 75.0" &&
                            it.description.contains("Filesystem: /dev/sda at /mnt/volume_nyc3_01") &&
                            it.metadata["resource_label"]?.toString() ==
                            "\"/dev/sda at /mnt/volume_nyc3_01\""
                    },
                    AlertFanoutPlan.WORKFLOW_ONLY,
                )
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
                alertOrchestrator.process(
                    match {
                        it.status == AlertStatus.RESOLVED &&
                            it.source == AlertSource.HOST_ALERT &&
                            it.deduplicationKey == deduplicationKey
                    },
                    AlertFanoutPlan.FULL,
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
                alertOrchestrator.process(
                    match {
                        it.status == AlertStatus.RESOLVED && it.deduplicationKey == deduplicationKey
                    },
                    AlertFanoutPlan.FULL,
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
                            it[alert_priority] = null
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
                alertOrchestrator.process(
                    match {
                        it.status == AlertStatus.RESOLVED && it.deduplicationKey == deduplicationKey
                    },
                    AlertFanoutPlan.WORKFLOW_ONLY,
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
                alertOrchestrator.process(
                    match {
                        it.source == AlertSource.HOST_ALERT &&
                            it.deduplicationKey == deduplicationKey &&
                            it.organizationId == fixture.orgId
                    },
                    AlertFanoutPlan.FULL,
                )
            }
        }

    @Test
    fun `triggerAlert uses workflow only fanout without an incident priority`() =
        runBlocking {
            val fixture = createHostAlertFixture(alertPriority = null)
            val alertKey = "alert_state:${fixture.hostId}:id_${fixture.alert.id}"
            every { RedisConfig.isConnected() } returns true
            val redis = mockk<RedisCommands<String, String>>()
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

            coVerify(exactly = 1) {
                alertOrchestrator.process(any(), AlertFanoutPlan.WORKFLOW_ONLY)
            }
        }

    @Test
    fun `triggerAlert sends the host episode through full fanout`() =
        runBlocking {
            val fixture = createHostAlertFixture()
            val alertKey = "alert_state:${fixture.hostId}:id_${fixture.alert.id}"
            every { RedisConfig.isConnected() } returns true
            val redis = mockk<RedisCommands<String, String>>()
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

            coVerify(exactly = 1) {
                alertOrchestrator.process(
                    match {
                        it.status == AlertStatus.FIRING &&
                            it.source == AlertSource.HOST_ALERT &&
                            it.organizationId == fixture.orgId
                    },
                    AlertFanoutPlan.FULL,
                )
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
                alertOrchestrator.process(
                    match {
                        it.status == AlertStatus.RESOLVED &&
                            it.source == AlertSource.HOST_DOWN &&
                            it.deduplicationKey == "moneat-host-down-$hostId"
                    },
                    AlertFanoutPlan.FULL,
                )
            }

            callPrivateSuspend("checkHostStatuses")

            coVerify(exactly = 1) {
                alertOrchestrator.process(any(), AlertFanoutPlan.FULL)
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
                alertOrchestrator.process(
                    match {
                        it.status == AlertStatus.RESOLVED &&
                            it.source == AlertSource.HOST_DOWN &&
                            it.deduplicationKey == "moneat-host-down-$hostId"
                    },
                    AlertFanoutPlan.FULL,
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
            val failedResult = mockk<com.moneat.alerts.services.AlertOrchestrationResult>()
            every { failedResult.outcomes } returns listOf(
                AlertFanoutOutcome(AlertFanoutArm.INCIDENT_PROVIDERS, AlertFanoutState.FAILED, "resolve failed"),
            )
            val succeededResult = mockk<com.moneat.alerts.services.AlertOrchestrationResult>(relaxed = true)
            coEvery {
                alertOrchestrator.process(
                    match { it.status == AlertStatus.RESOLVED && it.deduplicationKey == deduplicationKey },
                    AlertFanoutPlan.FULL,
                )
            } returnsMany listOf(failedResult, succeededResult)

            callPrivateSuspend("checkHostStatuses")

            val failedStatus =
                transaction {
                    Hosts.selectAll().where { Hosts.id eq hostId }.first()[Hosts.status]
                }
            assertEquals("down", failedStatus)

            callPrivateSuspend("checkHostStatuses")

            val recoveredStatus =
                transaction {
                    Hosts.selectAll().where { Hosts.id eq hostId }.first()[Hosts.status]
                }
            assertEquals("up", recoveredStatus)
            coVerify(exactly = 2) {
                alertOrchestrator.process(
                    match { it.status == AlertStatus.RESOLVED && it.deduplicationKey == deduplicationKey },
                    AlertFanoutPlan.FULL,
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
