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

package com.moneat.dashboards

import com.moneat.config.RedisConfig
import com.moneat.dashboards.models.AggFunction
import com.moneat.dashboards.models.CreateDashboardAlertRequest
import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.DashboardWidgetAlerts
import com.moneat.dashboards.models.DashboardWidgets
import com.moneat.dashboards.models.Dashboards
import com.moneat.dashboards.models.MetricDef
import com.moneat.dashboards.models.NotificationChannels
import com.moneat.dashboards.models.QueryDsl
import com.moneat.dashboards.models.UpdateDashboardAlertRequest
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DataSourceCredentials
import com.moneat.dashboards.services.DashboardAlertService
import com.moneat.dashboards.services.DashboardQueryEngine
import com.moneat.incident.models.AlertSource
import com.moneat.incident.services.IncidentService
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.notifications.services.DiscordService
import com.moneat.notifications.services.EmailService
import com.moneat.notifications.services.SlackService
import com.moneat.shared.services.RetentionPolicyService
import com.moneat.testsupport.TestDatabaseHelper
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DashboardAlertServiceTest {

    private val emailService: EmailService = mockk(relaxed = true)
    private val slackService: SlackService = mockk(relaxed = true)
    private val discordService: DiscordService = mockk(relaxed = true)
    private val incidentService: IncidentService = mockk(relaxed = true)
    private val prefsService: AlertNotificationPreferencesService = mockk(relaxed = true)
    private val queryEngine: DashboardQueryEngine = mockk(relaxed = true)
    private val retentionPolicyService: RetentionPolicyService = mockk(relaxed = true)
    private val dataSourceService: CustomDataSourceService = mockk(relaxed = true)
    private val dataSourceExecutor: CustomDataSourceExecutor = mockk(relaxed = true)
    private var redisConfigMocked = false

    private val service = DashboardAlertService(
        emailService = emailService,
        slackService = slackService,
        discordService = discordService,
        incidentService = incidentService,
        prefsService = prefsService,
        queryEngine = queryEngine,
        retentionPolicyService = retentionPolicyService,
        dataSourceService = dataSourceService,
        dataSourceExecutor = dataSourceExecutor,
    )

    companion object {
        private var db: Database? = null
        private const val ORG_ID = 1L
        private const val CREATED_BY = 100L
        private const val DEFAULT_PROJECT_ID = 1L
        private const val RECOVERY_RETENTION_DAYS = 90
        private const val RECOVERED_TOTAL = 50.0
        private const val RECOVERY_THRESHOLD = 100.0
        private const val REDIS_DELETE_COUNT = 1L
    }

    @BeforeTest
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_dashboard_alert_service;" +
                    "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db

        TestDatabaseHelper.dropAndPatchJsonb(
            Dashboards,
            DashboardWidgets,
            DashboardWidgetAlerts
        )
        transaction {
            exec(
                """
                CREATE TABLE IF NOT EXISTS dashboard_folders (
                    id BIGSERIAL PRIMARY KEY,
                    org_id BIGINT NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    color VARCHAR(7),
                    sort_order INT DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE IF NOT EXISTS dashboards (
                    id BIGSERIAL PRIMARY KEY,
                    org_id BIGINT NOT NULL,
                    project_id BIGINT,
                    folder_id BIGINT,
                    title VARCHAR(255) NOT NULL,
                    description TEXT,
                    layout_type VARCHAR(20) DEFAULT 'grid' NOT NULL,
                    is_default BOOLEAN DEFAULT FALSE NOT NULL,
                    variables TEXT DEFAULT '[]' NOT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_dashboards_folder_id FOREIGN KEY (folder_id)
                        REFERENCES dashboard_folders(id)
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE IF NOT EXISTS dashboard_widgets (
                    id BIGSERIAL PRIMARY KEY,
                    dashboard_id BIGINT NOT NULL,
                    title VARCHAR(255),
                    widget_type VARCHAR(50) NOT NULL,
                    grid_x INT DEFAULT 0 NOT NULL,
                    grid_y INT DEFAULT 0 NOT NULL,
                    grid_w INT DEFAULT 6 NOT NULL,
                    grid_h INT DEFAULT 4 NOT NULL,
                    query_config TEXT NOT NULL,
                    query_configs TEXT NOT NULL,
                    display_config TEXT NOT NULL,
                    sort_order INT DEFAULT 0 NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    CONSTRAINT fk_widgets_dashboard_id FOREIGN KEY (dashboard_id)
                        REFERENCES dashboards(id)
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE IF NOT EXISTS dashboard_widget_alerts (
                    id BIGSERIAL PRIMARY KEY,
                    widget_id BIGINT NOT NULL,
                    dashboard_id BIGINT NOT NULL,
                    org_id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    condition VARCHAR(5) NOT NULL,
                    threshold DOUBLE PRECISION NOT NULL,
                    metric_index INT DEFAULT 0 NOT NULL,
                    duration_seconds INT DEFAULT 0 NOT NULL,
                    incident_severity VARCHAR(20),
                    enabled BOOLEAN DEFAULT TRUE NOT NULL,
                    notification_channels TEXT NOT NULL, -- H2: JSONB unsupported; production uses JSONB
                    last_triggered_at TIMESTAMP,
                    last_value DOUBLE PRECISION,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    CONSTRAINT fk_alerts_widget_id FOREIGN KEY (widget_id)
                        REFERENCES dashboard_widgets(id) ON DELETE CASCADE,
                    CONSTRAINT fk_alerts_dashboard_id FOREIGN KEY (dashboard_id)
                        REFERENCES dashboards(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (redisConfigMocked) {
            unmockkObject(RedisConfig)
            redisConfigMocked = false
        }
    }

    private suspend fun callPrivateSuspend(name: String, vararg args: Any?): Any? {
        val fn =
            DashboardAlertService::class.declaredFunctions.single { f ->
                f.name == name && f.parameters.drop(1).size == args.size
            }
        fn.isAccessible = true
        return fn.callSuspend(service, *args)
    }

    private fun callPrivate(name: String, vararg args: Any?): Any? {
        val fn =
            DashboardAlertService::class.declaredFunctions.single { f ->
                f.name == name && f.parameters.drop(1).size == args.size
            }
        fn.isAccessible = true
        return fn.call(service, *args)
    }

    private fun seedDashboard(
        title: String = "Test Dashboard",
        projectId: Long? = DEFAULT_PROJECT_ID
    ): Long =
        transaction {
            val now = Clock.System.now()
            Dashboards.insert {
                it[orgId] = ORG_ID
                it[Dashboards.projectId] = projectId
                it[Dashboards.title] = title
                it[createdBy] = CREATED_BY
                it[createdAt] = now
                it[updatedAt] = now
            } get Dashboards.id
        }

    private fun seedWidget(
        dashboardId: Long,
        title: String? = "Test Widget",
        queryConfigs: String = "[]"
    ): Long =
        transaction {
            val now = Clock.System.now()
            DashboardWidgets.insert {
                it[DashboardWidgets.dashboardId] = dashboardId
                it[DashboardWidgets.title] = title
                it[widgetType] = "timeseries"
                it[queryConfig] = "{}"
                it[DashboardWidgets.queryConfigs] = queryConfigs
                it[displayConfig] = "{}"
                it[createdAt] = now
                it[updatedAt] = now
            } get DashboardWidgets.id
        }

    private data class AlertRequestOverrides(
        val name: String = "High Error Rate",
        val condition: String = ">",
        val threshold: Double = 100.0,
        val metricIndex: Int = 0,
        val durationSeconds: Int = 0,
        val incidentSeverity: String? = null,
        val enabled: Boolean = true,
        val notificationChannels: NotificationChannels = NotificationChannels(),
    )

    private fun buildCreateRequest(
        widgetId: Long,
        overrides: AlertRequestOverrides = AlertRequestOverrides(),
    ): CreateDashboardAlertRequest = CreateDashboardAlertRequest(
        widgetId = widgetId,
        name = overrides.name,
        condition = overrides.condition,
        threshold = overrides.threshold,
        metricIndex = overrides.metricIndex,
        durationSeconds = overrides.durationSeconds,
        incidentSeverity = overrides.incidentSeverity,
        enabled = overrides.enabled,
        notificationChannels = overrides.notificationChannels,
    )

    private fun customDataSource(
        id: Long = 10,
        sourceType: String = CustomDataSourceType.PROMETHEUS.name.lowercase(),
        enabled: Boolean = true,
        hasCredentials: Boolean = false,
    ): CustomDataSourceResponse = CustomDataSourceResponse(
        id = id,
        orgId = ORG_ID,
        name = "Prometheus",
        sourceType = sourceType,
        host = "https://prometheus.example.com",
        port = null,
        databaseName = null,
        enabled = enabled,
        createdBy = CREATED_BY,
        createdAt = Clock.System.now().toString(),
        updatedAt = Clock.System.now().toString(),
        hasCredentials = hasCredentials,
    )

    // ──── createAlert tests ────

    @Test
    fun `createAlert returns response with correct fields`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val response = service.createAlert(
            dashboardId = dashboardId,
            orgId = ORG_ID,
            createdBy = CREATED_BY,
            request = buildCreateRequest(widgetId),
        )

        assertEquals("High Error Rate", response.name)
        assertEquals(">", response.condition)
        assertEquals(100.0, response.threshold)
        assertEquals(widgetId, response.widgetId)
        assertEquals(dashboardId, response.dashboardId)
        assertTrue(response.enabled)
        assertEquals(0, response.metricIndex)
        assertEquals(0, response.durationSeconds)
        assertNull(response.incidentSeverity)
        assertNull(response.lastTriggeredAt)
        assertNull(response.lastValue)
        assertNotNull(response.createdAt)
        assertNotNull(response.updatedAt)
    }

    @Test
    fun `createAlert persists notification channels`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)
        val channels = NotificationChannels(email = true, slack = false, discord = true)

        val response = service.createAlert(
            dashboardId = dashboardId,
            orgId = ORG_ID,
            createdBy = CREATED_BY,
            request = buildCreateRequest(widgetId, AlertRequestOverrides(notificationChannels = channels)),
        )

        assertTrue(response.notificationChannels.email)
        assertFalse(response.notificationChannels.slack)
        assertTrue(response.notificationChannels.discord)
    }

    @Test
    fun `createAlert with custom metric index and duration`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val response = service.createAlert(
            dashboardId = dashboardId,
            orgId = ORG_ID,
            createdBy = CREATED_BY,
            request = buildCreateRequest(
                widgetId,
                AlertRequestOverrides(metricIndex = 2, durationSeconds = 300, incidentSeverity = "CRITICAL"),
            ),
        )

        assertEquals(2, response.metricIndex)
        assertEquals(300, response.durationSeconds)
        assertEquals("CRITICAL", response.incidentSeverity)
    }

    @Test
    fun `createAlert rejects invalid condition`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        assertFailsWith<IllegalArgumentException> {
            service.createAlert(
                dashboardId = dashboardId,
                orgId = ORG_ID,
                createdBy = CREATED_BY,
                request = buildCreateRequest(widgetId, AlertRequestOverrides(condition = "INVALID")),
            )
        }
    }

    @Test
    fun `createAlert fails when widget does not belong to dashboard`() {
        val dashboardId1 = seedDashboard(title = "Dashboard 1")
        val dashboardId2 = seedDashboard(title = "Dashboard 2")
        val widgetId = seedWidget(dashboardId1)

        assertFailsWith<IllegalArgumentException> {
            service.createAlert(
                dashboardId = dashboardId2,
                orgId = ORG_ID,
                createdBy = CREATED_BY,
                request = buildCreateRequest(widgetId),
            )
        }
    }

    // ──── listAlerts tests ────

    @Test
    fun `listAlerts returns alerts for dashboard and org`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId, AlertRequestOverrides(name = "Alert A")),
        )
        service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId, AlertRequestOverrides(name = "Alert B")),
        )

        val alerts = service.listAlerts(dashboardId, ORG_ID)
        assertEquals(2, alerts.size)
    }

    @Test
    fun `listAlerts does not return alerts from other orgs`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)
        val otherOrgId = 999L

        service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId),
        )

        val alerts = service.listAlerts(dashboardId, otherOrgId)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `listAlerts returns empty list when no alerts exist`() {
        val dashboardId = seedDashboard()
        val alerts = service.listAlerts(dashboardId, ORG_ID)
        assertTrue(alerts.isEmpty())
    }

    // ──── updateAlert tests ────

    @Test
    fun `updateAlert modifies specified fields only`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId),
        )

        val updated = service.updateAlert(
            alertId = created.id,
            dashboardId = dashboardId,
            orgId = ORG_ID,
            request = UpdateDashboardAlertRequest(name = "Renamed Alert"),
        )

        assertNotNull(updated)
        assertEquals("Renamed Alert", updated.name)
        assertEquals(created.condition, updated.condition)
        assertEquals(created.threshold, updated.threshold)
    }

    @Test
    fun `updateAlert can change threshold and condition`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId, AlertRequestOverrides(condition = ">", threshold = 100.0)),
        )

        val updated = service.updateAlert(
            alertId = created.id,
            dashboardId = dashboardId,
            orgId = ORG_ID,
            request = UpdateDashboardAlertRequest(condition = "<", threshold = 50.0),
        )

        assertNotNull(updated)
        assertEquals("<", updated.condition)
        assertEquals(50.0, updated.threshold)
    }

    @Test
    fun `updateAlert can disable and re-enable alert`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId),
        )

        val disabled = service.updateAlert(
            created.id,
            dashboardId,
            ORG_ID,
            UpdateDashboardAlertRequest(enabled = false),
        )
        assertNotNull(disabled)
        assertFalse(disabled.enabled)

        val reenabled = service.updateAlert(
            created.id,
            dashboardId,
            ORG_ID,
            UpdateDashboardAlertRequest(enabled = true),
        )
        assertNotNull(reenabled)
        assertTrue(reenabled.enabled)
    }

    @Test
    fun `updateAlert returns null for non-existent alert`() {
        val dashboardId = seedDashboard()

        val result = service.updateAlert(
            alertId = 9999L,
            dashboardId = dashboardId,
            orgId = ORG_ID,
            request = UpdateDashboardAlertRequest(name = "Ghost"),
        )

        assertNull(result)
    }

    @Test
    fun `updateAlert returns null for wrong org`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId),
        )

        val result = service.updateAlert(
            alertId = created.id,
            dashboardId = dashboardId,
            orgId = 999L,
            request = UpdateDashboardAlertRequest(name = "Hacked"),
        )

        assertNull(result)
    }

    @Test
    fun `updateAlert rejects invalid condition`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId),
        )

        assertFailsWith<IllegalArgumentException> {
            service.updateAlert(
                created.id,
                dashboardId,
                ORG_ID,
                UpdateDashboardAlertRequest(condition = "NOPE"),
            )
        }
    }

    @Test
    fun `updateAlert can change notification channels`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId),
        )

        val newChannels = NotificationChannels(email = false, slack = true, discord = false)
        val updated = service.updateAlert(
            created.id,
            dashboardId,
            ORG_ID,
            UpdateDashboardAlertRequest(notificationChannels = newChannels),
        )

        assertNotNull(updated)
        assertFalse(updated.notificationChannels.email)
        assertTrue(updated.notificationChannels.slack)
        assertFalse(updated.notificationChannels.discord)
    }

    // ──── deleteAlert tests ────

    @Test
    fun `deleteAlert removes existing alert`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId),
        )

        assertTrue(service.deleteAlert(created.id, dashboardId, ORG_ID))

        val alerts = service.listAlerts(dashboardId, ORG_ID)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `deleteAlert returns false for non-existent alert`() {
        val dashboardId = seedDashboard()
        assertFalse(service.deleteAlert(9999L, dashboardId, ORG_ID))
    }

    @Test
    fun `deleteAlert returns false for wrong org`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId),
        )

        assertFalse(service.deleteAlert(created.id, dashboardId, 999L))

        val alerts = service.listAlerts(dashboardId, ORG_ID)
        assertEquals(1, alerts.size)
    }

    @Test
    fun `evaluateAlerts auto resolves recovered dashboard alert`() =
        runBlocking {
            mockkObject(RedisConfig)
            redisConfigMocked = true
            val redis = mockk<RedisCommands<String, String>>(relaxed = true)
            every { RedisConfig.isConnected() } returns true
            every { RedisConfig.sync() } returns redis
            coEvery {
                retentionPolicyService.getRetentionDaysForProject(any())
            } returns RECOVERY_RETENTION_DAYS
            coEvery {
                queryEngine.executeQuery(any(), any(), any(), any())
            } returns listOf(mapOf("total" to JsonPrimitive(RECOVERED_TOTAL)))

            val dashboardId = seedDashboard()
            val widgetId =
                seedWidget(
                    dashboardId,
                    queryConfigs = """[{"dataSource":"events"}]"""
                )
            val created =
                service.createAlert(
                    dashboardId,
                    ORG_ID,
                    CREATED_BY,
                    buildCreateRequest(
                        widgetId,
                        AlertRequestOverrides(condition = ">", threshold = RECOVERY_THRESHOLD),
                    ),
                )
            every { redis.get("dashboard_alert_state:${created.id}") } returns "TRIGGERED"
            every { redis.del(any<String>()) } returns REDIS_DELETE_COUNT

            callPrivateSuspend("evaluateAlerts")

            coVerify(exactly = 1) {
                incidentService.autoResolveAlert(
                    organizationId = ORG_ID.toInt(),
                    source = AlertSource.DASHBOARD_ALERT,
                    deduplicationKey = "moneat-dashboard-alert-${created.id}"
                )
            }
        }

    @Test
    fun `executeQueryForAlert executes custom datasource query without project`() =
        runBlocking {
            val source = customDataSource(id = 10)
            every { dataSourceService.getDataSource(10, ORG_ID) } returns source
            every { dataSourceService.getDecryptedCredentials(10, ORG_ID) } returns DataSourceCredentials()
            coEvery {
                dataSourceExecutor.executeQuery(
                    sourceId = 10,
                    sourceType = CustomDataSourceType.PROMETHEUS,
                    host = source.host,
                    port = source.port,
                    databaseName = source.databaseName,
                    credentials = any(),
                    query = "up",
                    limit = 25,
                    timeRange = any(),
                )
            } returns listOf(mapOf("value" to JsonPrimitive(1.0)))

            val result = service.executeQueryForAlert(
                orgId = ORG_ID,
                projectId = null,
                queryDsl = QueryDsl(
                    dataSource = "custom:10",
                    rawQuery = "up",
                    limit = 25,
                ),
            )

            assertEquals(1.0, result.single()["value"]?.jsonPrimitive?.content?.toDouble())
        }

    @Test
    fun `executeQueryForAlert resolves prometheus alias to custom datasource`() =
        runBlocking {
            val source = customDataSource(id = 11)
            every { dataSourceService.listDataSources(ORG_ID) } returns listOf(source)
            every { dataSourceService.getDecryptedCredentials(11, ORG_ID) } returns null
            coEvery {
                dataSourceExecutor.executeQuery(
                    sourceId = 11,
                    sourceType = CustomDataSourceType.PROMETHEUS,
                    host = source.host,
                    port = source.port,
                    databaseName = source.databaseName,
                    credentials = any(),
                    query = "process_cpu_usage",
                    limit = any(),
                    timeRange = any(),
                )
            } returns listOf(mapOf("value" to JsonPrimitive(0.25)))

            val result = service.executeQueryForAlert(
                orgId = ORG_ID,
                projectId = null,
                queryDsl = QueryDsl(
                    dataSource = "__prometheus",
                    rawQuery = "process_cpu_usage",
                ),
            )

            assertEquals(0.25, result.single()["value"]?.jsonPrimitive?.content?.toDouble())
        }

    @Test
    fun `executeQueryForAlert skips built in datasource without project`() =
        runBlocking {
            val result = service.executeQueryForAlert(
                orgId = ORG_ID,
                projectId = null,
                queryDsl = QueryDsl(dataSource = "events"),
            )

            assertTrue(result.isEmpty())
            coVerify(exactly = 0) {
                queryEngine.executeQuery(any(), any(), any(), any())
            }
        }

    @Test
    fun `executeQueryForAlert executes built in datasource with default retention`() =
        runBlocking {
            val query = QueryDsl(dataSource = "events")
            val rows = listOf(mapOf("total" to JsonPrimitive(7.0)))
            coEvery {
                retentionPolicyService.getRetentionDaysForProject(DEFAULT_PROJECT_ID)
            } returns null
            coEvery {
                queryEngine.executeQuery(query, DEFAULT_PROJECT_ID, null, RECOVERY_RETENTION_DAYS)
            } returns rows

            val result = service.executeQueryForAlert(
                orgId = ORG_ID,
                projectId = DEFAULT_PROJECT_ID,
                queryDsl = query,
            )

            assertEquals(rows, result)
        }

    @Test
    fun `executeQueryForAlert rejects prometheus alias without enabled source`() =
        runBlocking {
            every { dataSourceService.listDataSources(ORG_ID) } returns emptyList()

            assertFailsWith<IllegalStateException> {
                service.executeQueryForAlert(
                    orgId = ORG_ID,
                    projectId = null,
                    queryDsl = QueryDsl(
                        dataSource = "__prometheus",
                        rawQuery = "up",
                    ),
                )
            }
        }

    @Test
    fun `executeQueryForAlert selects first enabled prometheus datasource`() =
        runBlocking {
            val disabledPrometheus = customDataSource(id = 10, enabled = false)
            val postgres = customDataSource(
                id = 11,
                sourceType = CustomDataSourceType.POSTGRESQL.name.lowercase(),
            )
            val prometheus = customDataSource(id = 12)
            every { dataSourceService.listDataSources(ORG_ID) } returns listOf(
                disabledPrometheus,
                postgres,
                prometheus,
            )
            every { dataSourceService.getDecryptedCredentials(12, ORG_ID) } returns null
            coEvery {
                dataSourceExecutor.executeQuery(
                    sourceId = 12,
                    sourceType = CustomDataSourceType.PROMETHEUS,
                    host = prometheus.host,
                    port = prometheus.port,
                    databaseName = prometheus.databaseName,
                    credentials = any(),
                    query = "up",
                    limit = any(),
                    timeRange = any(),
                )
            } returns listOf(mapOf("value" to JsonPrimitive(1.0)))

            val result = service.executeQueryForAlert(
                orgId = ORG_ID,
                projectId = null,
                queryDsl = QueryDsl(dataSource = "__prometheus", rawQuery = "up"),
            )

            assertEquals(1.0, result.single()["value"]?.jsonPrimitive?.content?.toDouble())
        }

    @Test
    fun `executeQueryForAlert rejects invalid custom datasource references`() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                service.executeQueryForAlert(
                    orgId = ORG_ID,
                    projectId = null,
                    queryDsl = QueryDsl(
                        dataSource = "custom:not-a-number",
                        rawQuery = "up",
                    ),
                )
            }
        }

    @Test
    fun `executeQueryForAlert rejects missing custom datasource`() =
        runBlocking {
            every { dataSourceService.getDataSource(404, ORG_ID) } returns null

            assertFailsWith<IllegalStateException> {
                service.executeQueryForAlert(
                    orgId = ORG_ID,
                    projectId = null,
                    queryDsl = QueryDsl(
                        dataSource = "custom:404",
                        rawQuery = "up",
                    ),
                )
            }
        }

    @Test
    fun `executeQueryForAlert rejects disabled custom datasource`() =
        runBlocking {
            every { dataSourceService.getDataSource(10, ORG_ID) } returns customDataSource(
                id = 10,
                enabled = false,
            )

            assertFailsWith<IllegalStateException> {
                service.executeQueryForAlert(
                    orgId = ORG_ID,
                    projectId = null,
                    queryDsl = QueryDsl(
                        dataSource = "custom:10",
                        rawQuery = "up",
                    ),
                )
            }
        }

    @Test
    fun `executeQueryForAlert rejects custom datasource query without raw query`() =
        runBlocking {
            every { dataSourceService.getDataSource(10, ORG_ID) } returns customDataSource(id = 10)

            assertFailsWith<IllegalArgumentException> {
                service.executeQueryForAlert(
                    orgId = ORG_ID,
                    projectId = null,
                    queryDsl = QueryDsl(dataSource = "custom:10"),
                )
            }
        }

    @Test
    fun `executeQueryForAlert rejects custom datasource query with blank raw query`() =
        runBlocking {
            every { dataSourceService.getDataSource(10, ORG_ID) } returns customDataSource(id = 10)

            assertFailsWith<IllegalArgumentException> {
                service.executeQueryForAlert(
                    orgId = ORG_ID,
                    projectId = null,
                    queryDsl = QueryDsl(
                        dataSource = "custom:10",
                        rawQuery = "   ",
                    ),
                )
            }
        }

    @Test
    fun `executeQueryForAlert rejects unsupported custom datasource type`() =
        runBlocking {
            every { dataSourceService.getDataSource(10, ORG_ID) } returns customDataSource(
                id = 10,
                sourceType = "unsupported",
            )

            assertFailsWith<IllegalStateException> {
                service.executeQueryForAlert(
                    orgId = ORG_ID,
                    projectId = null,
                    queryDsl = QueryDsl(
                        dataSource = "custom:10",
                        rawQuery = "up",
                    ),
                )
            }
        }

    @Test
    fun `executeQueryForAlert rejects missing credentials for credentialed datasource`() =
        runBlocking {
            every { dataSourceService.getDataSource(10, ORG_ID) } returns customDataSource(
                id = 10,
                hasCredentials = true,
            )
            every { dataSourceService.getDecryptedCredentials(10, ORG_ID) } returns null

            assertFailsWith<IllegalStateException> {
                service.executeQueryForAlert(
                    orgId = ORG_ID,
                    projectId = null,
                    queryDsl = QueryDsl(
                        dataSource = "custom:10",
                        rawQuery = "up",
                    ),
                )
            }
        }

    @Test
    fun `executeQueryForAlert uses resolved credentials for credentialed datasource`() =
        runBlocking {
            val source = customDataSource(id = 10, hasCredentials = true)
            val credentials = DataSourceCredentials(apiKey = "secret")
            every { dataSourceService.getDataSource(10, ORG_ID) } returns source
            every { dataSourceService.getDecryptedCredentials(10, ORG_ID) } returns credentials
            coEvery {
                dataSourceExecutor.executeQuery(
                    sourceId = 10,
                    sourceType = CustomDataSourceType.PROMETHEUS,
                    host = source.host,
                    port = source.port,
                    databaseName = source.databaseName,
                    credentials = credentials,
                    query = "up",
                    limit = any(),
                    timeRange = any(),
                )
            } returns listOf(mapOf("value" to JsonPrimitive(1.0)))

            val result = service.executeQueryForAlert(
                orgId = ORG_ID,
                projectId = null,
                queryDsl = QueryDsl(
                    dataSource = "custom:10",
                    rawQuery = "up",
                ),
            )

            assertEquals(1.0, result.single()["value"]?.jsonPrimitive?.content?.toDouble())
        }

    @Test
    fun `extractMetricValue scans fallback row fields for numeric values`() {
        val result = callPrivate(
            "extractMetricValue",
            listOf(
                mapOf(
                    "worker" to JsonPrimitive("primary"),
                    "value" to JsonPrimitive(0.25),
                )
            ),
            QueryDsl(dataSource = "custom:10"),
            0,
        )

        assertEquals(0.25, result)
    }

    @Test
    fun `extractMetricValue prefers metric alias when present`() {
        val result = callPrivate(
            "extractMetricValue",
            listOf(mapOf("cpu_avg" to JsonPrimitive(0.5))),
            QueryDsl(
                dataSource = "custom:10",
                metrics = listOf(MetricDef(AggFunction.AVG, field = "cpu", alias = "cpu_avg")),
            ),
            0,
        )

        assertEquals(0.5, result)
    }

    @Test
    fun `extractMetricValue continues to older rows until a numeric value is found`() {
        val result = callPrivate(
            "extractMetricValue",
            listOf(
                mapOf("value" to JsonPrimitive(2.0)),
                mapOf(
                    "time" to JsonPrimitive("2026-05-25T00:00:00Z"),
                    "host" to JsonPrimitive("api-1"),
                ),
            ),
            QueryDsl(dataSource = "custom:10"),
            0,
        )

        assertEquals(2.0, result)
    }

    // ──── CRUD round-trip ────

    @Test
    fun `full CRUD lifecycle create list update delete`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val created = service.createAlert(
            dashboardId,
            ORG_ID,
            CREATED_BY,
            buildCreateRequest(widgetId, AlertRequestOverrides(name = "Lifecycle Alert", threshold = 50.0)),
        )
        assertEquals("Lifecycle Alert", created.name)

        val listed = service.listAlerts(dashboardId, ORG_ID)
        assertEquals(1, listed.size)
        assertEquals(created.id, listed.first().id)

        val updated = service.updateAlert(
            created.id,
            dashboardId,
            ORG_ID,
            UpdateDashboardAlertRequest(threshold = 75.0),
        )
        assertNotNull(updated)
        assertEquals(75.0, updated.threshold)

        assertTrue(service.deleteAlert(created.id, dashboardId, ORG_ID))
        assertTrue(service.listAlerts(dashboardId, ORG_ID).isEmpty())
    }

    // ──── Condition validation ────

    @Test
    fun `createAlert accepts all valid conditions`() {
        val dashboardId = seedDashboard()
        val widgetId = seedWidget(dashboardId)

        val validConditions = listOf(">", "<", ">=", "<=", "==")
        for (cond in validConditions) {
            val response = service.createAlert(
                dashboardId,
                ORG_ID,
                CREATED_BY,
                buildCreateRequest(widgetId, AlertRequestOverrides(name = "Alert $cond", condition = cond)),
            )
            assertEquals(cond, response.condition)
        }
    }
}
