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

package com.moneat.mcp.auth

import com.moneat.config.ClickHouseClient
import com.moneat.dashboards.models.Dashboards
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.protocol.ToolContent
import com.moneat.mcp.tools.GetContainerMetricsTool
import com.moneat.mcp.tools.GetHostMetricsTool
import com.moneat.security.detection.DetectionRules
import com.moneat.security.signals.SecuritySignals
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.statuspage.models.StatusPages
import com.moneat.synthetics.routes.SyntheticTests
import com.moneat.testsupport.TestDatabaseHelper
import com.moneat.uptime.models.UptimeMonitors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID

class McpAuthorizationTest {
    companion object {
        private var db: Database? = null
    }

    private var clickHouseServer: HttpServer? = null

    private val context = McpContext(
        organizationId = 1,
        userId = 1,
        tokenId = 1,
        scopes = setOf("project:read", "event:read"),
        sessionId = "test",
    )

    @BeforeEach
    fun setupDatabase() {
        if (db == null) {
            db = Database.connect(
                url = "jdbc:h2:mem:moneat_mcp_authorization;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
        }
        TransactionManager.defaultDatabase = db
        TestDatabaseHelper.resetSchema(Users, Organizations, Projects, Hosts)
        createObjectAccessTables()
    }

    @AfterEach
    fun tearDown() {
        clickHouseServer?.stop(0)
        clickHouseServer = null
        ClickHouseClient.close()
    }

    @Test
    fun `project id from another org returns authorization error`() = runBlocking {
        val projectId = seedProject(organizationId = 2)
        val result = registryWithNoopTool().callTool(
            name = "read_project",
            args = JsonObject(mapOf("project_id" to JsonPrimitive(projectResourceId(projectId)))),
            context = context,
        )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("project not found"))
    }

    @Test
    fun `host id from another org returns authorization error`() = runBlocking {
        val hostId = seedHost(organizationId = 2)
        val result = registryWithNoopTool().callTool(
            name = "read_project",
            args = JsonObject(mapOf("host_id" to JsonPrimitive(hostId))),
            context = context,
        )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("host not found"))
    }

    @Test
    fun `issue id from another org returns authorization error`() = runBlocking {
        val projectId = seedProject(organizationId = 2)
        stubClickHouseProjectId(projectId)

        val result = registryWithNoopTool().callTool(
            name = "read_project",
            args = JsonObject(mapOf("issue_id" to JsonPrimitive("ISSUE-1"))),
            context = context,
        )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("project not found"))
    }

    @Test
    fun `transaction event id from another org returns authorization error`() = runBlocking {
        val projectId = seedProject(organizationId = 2)
        stubClickHouseProjectId(projectId)

        val result = registryWithNoopTool().callTool(
            name = "read_project",
            args = JsonObject(mapOf("event_id" to JsonPrimitive("123e4567-e89b-12d3-a456-426614174000"))),
            context = context,
        )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("project not found"))
    }

    @Test
    fun `host metrics tool cannot leak cross-org data`() = runBlocking {
        val hostId = seedHost(organizationId = 2)
        val result = McpToolRegistry()
            .also { it.register(GetHostMetricsTool()) }
            .callTool(
                name = "get_host_metrics",
                args = JsonObject(mapOf("host_id" to JsonPrimitive(hostId))),
                context = context,
            )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("host not found"))
    }

    @Test
    fun `container metrics tool cannot leak cross-org data`() = runBlocking {
        val hostId = seedHost(organizationId = 2)
        val result = McpToolRegistry()
            .also { it.register(GetContainerMetricsTool()) }
            .callTool(
                name = "get_container_metrics",
                args = JsonObject(mapOf("host_id" to JsonPrimitive(hostId))),
                context = context,
            )

        assertTrue(result.isError)
        assertTrue(result.content[0].text!!.contains("host not found"))
    }

    // ──── Security object access tests ────

    @Test
    fun `owned objects authorize successfully`() = runBlocking {
        val dashboardId = seedDashboard(organizationId = 1)
        val monitorId = seedUptimeMonitor(organizationId = 1)
        val syntheticTestId = seedSyntheticTest(organizationId = 1)
        val pageId = seedStatusPage(organizationId = 1)
        val dataSourceId = seedDataSource(organizationId = 1)
        val securitySignalId = seedSecuritySignal(organizationId = 1)
        val detectionRuleId = seedDetectionRule(organizationId = 1)

        val result = registryWithNoopTool().callTool(
            name = "read_project",
            args = JsonObject(
                mapOf(
                    "dashboard_id" to JsonPrimitive(dashboardId),
                    "monitor_id" to JsonPrimitive(monitorId.toString()),
                    "synthetic_test_id" to JsonPrimitive(syntheticTestId.toString()),
                    "page_id" to JsonPrimitive(pageId.toString()),
                    "status_page_id" to JsonPrimitive(pageId.toString()),
                    "data_source_id" to JsonPrimitive(dataSourceId),
                    "security_signal_id" to JsonPrimitive(securitySignalId),
                    "detection_rule_id" to JsonPrimitive(detectionRuleId),
                )
            ),
            context = context,
        )

        assertTrue(!result.isError)
        assertTrue(result.content[0].text!!.contains("ok"))
    }

    // ──── Cross-org security object tests ────

    @Test
    fun `objects from another org return authorization errors`() = runBlocking {
        val cases = listOf(
            Triple("dashboard_id", JsonPrimitive(seedDashboard(organizationId = 2)), "dashboard not found"),
            Triple(
                "monitor_id",
                JsonPrimitive(seedUptimeMonitor(organizationId = 2).toString()),
                "uptime monitor not found",
            ),
            Triple(
                "synthetic_test_id",
                JsonPrimitive(seedSyntheticTest(organizationId = 2).toString()),
                "synthetic test not found",
            ),
            Triple("page_id", JsonPrimitive(seedStatusPage(organizationId = 2).toString()), "status page not found"),
            Triple("data_source_id", JsonPrimitive(seedDataSource(organizationId = 2)), "data source not found"),
            Triple(
                "security_signal_id",
                JsonPrimitive(seedSecuritySignal(organizationId = 2)),
                "security signal not found",
            ),
            Triple(
                "detection_rule_id",
                JsonPrimitive(seedDetectionRule(organizationId = 2)),
                "detection rule not found",
            ),
        )

        cases.forEach { (key, value, expectedError) ->
            val result = registryWithNoopTool().callTool(
                name = "read_project",
                args = JsonObject(mapOf(key to value)),
                context = context,
            )

            assertTrue(result.isError, "$key should be rejected")
            assertTrue(result.content[0].text!!.contains(expectedError), result.content[0].text)
        }
    }

    private fun registryWithNoopTool(): McpToolRegistry =
        McpToolRegistry().also { registry ->
            registry.register(
                object : McpTool {
                    override val name = "read_project"
                    override val description = "Reads a project"
                    override val inputSchema = InputSchema()
                    override val requiredScopes = setOf(McpScopes.PROJECT_READ)

                    override suspend fun execute(args: JsonObject, context: McpContext): ToolCallResult {
                        return ToolCallResult(content = listOf(ToolContent(text = "ok")))
                    }
                }
            )
        }

    private fun seedProject(organizationId: Int): Long =
        transaction {
            seedOrganization(organizationId)
            Projects.insert {
                it[Projects.organization_id] = organizationId
                it[name] = "Project $organizationId"
                it[slug] = "project-$organizationId"
            }[Projects.id]
        }

    private fun projectResourceId(projectId: Long): String = transaction {
        Projects
            .selectAll()
            .where { Projects.id eq projectId }
            .first()[Projects.resource_id]
            .toString()
    }

    private fun seedHost(organizationId: Int): Int =
        transaction {
            seedOrganization(organizationId)
            Hosts.insert {
                it[Hosts.organization_id] = organizationId
                it[hostname] = "host-$organizationId"
                it[first_seen_at] = Clock.System.now()
                it[last_seen_at] = Clock.System.now()
            }[Hosts.id]
        }

    private fun seedDashboard(organizationId: Int): Long =
        transaction {
            seedOrganization(organizationId)
            Dashboards.insert {
                it[orgId] = organizationId.toLong()
                it[title] = "Dashboard $organizationId"
                it[description] = null
                it[createdBy] = 1
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }[Dashboards.id]
        }

    private fun seedUptimeMonitor(organizationId: Int): UUID =
        transaction {
            seedOrganization(organizationId)
            val monitorId = UUID.randomUUID()
            UptimeMonitors.insert {
                it[id] = monitorId
                it[UptimeMonitors.organizationId] = organizationId
                it[name] = "Monitor $organizationId"
                it[type] = "http"
                it[url] = "https://example.com"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
            monitorId
        }

    private fun seedSyntheticTest(organizationId: Int): UUID =
        transaction {
            seedOrganization(organizationId)
            val testId = UUID.randomUUID()
            SyntheticTests.insert {
                it[id] = testId
                it[SyntheticTests.organizationId] = organizationId
            }
            testId
        }

    private fun seedStatusPage(organizationId: Int): UUID =
        transaction {
            seedOrganization(organizationId)
            val pageId = UUID.randomUUID()
            StatusPages.insert {
                it[id] = pageId
                it[StatusPages.organizationId] = organizationId
                it[name] = "Status $organizationId"
                it[slug] = "status-$organizationId-${pageId.toString().take(8)}"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
            pageId
        }

    private fun seedDataSource(organizationId: Int): Long =
        transaction {
            seedOrganization(organizationId)
            val dataSourceId = organizationId.toLong()
            exec(
                """
                INSERT INTO custom_data_sources (
                    id,
                    org_id,
                    name,
                    source_type,
                    host,
                    encrypted_credentials,
                    extra_config,
                    created_by,
                    created_at,
                    updated_at
                ) VALUES (
                    $dataSourceId,
                    $organizationId,
                    'Warehouse $organizationId',
                    'postgresql',
                    'db.example.com',
                    '{}',
                    '{}',
                    1,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
            dataSourceId
        }

    // ──── Security object helpers ────

    private fun seedSecuritySignal(organizationId: Int): Int =
        transaction {
            seedOrganization(organizationId)
            val now = Clock.System.now()
            SecuritySignals.insertAndGetId {
                it[SecuritySignals.organizationId] = organizationId
                it[SecuritySignals.signalSource] = "detection"
                it[SecuritySignals.ruleId] = "rule-$organizationId"
                it[SecuritySignals.ruleName] = "Rule $organizationId"
                it[SecuritySignals.severity] = "high"
                it[SecuritySignals.status] = "open"
                it[SecuritySignals.dedupKey] = "rule-$organizationId|host=web"
                it[SecuritySignals.entities] = "{}"
                it[SecuritySignals.sampleCount] = 1
                it[SecuritySignals.tags] = "[]"
                it[SecuritySignals.firstSeen] = now
                it[SecuritySignals.lastSeen] = now
                it[SecuritySignals.createdAt] = now
                it[SecuritySignals.updatedAt] = now
            }.value
        }

    private fun seedDetectionRule(organizationId: Int): Int =
        transaction {
            seedOrganization(organizationId)
            val now = Clock.System.now()
            DetectionRules.insertAndGetId {
                it[DetectionRules.organizationId] = organizationId
                it[DetectionRules.name] = "Rule $organizationId"
                it[DetectionRules.description] = ""
                it[DetectionRules.ruleSource] = "logs"
                it[DetectionRules.filter] = "*"
                it[DetectionRules.groupBy] = "[]"
                it[DetectionRules.windowSeconds] = 300
                it[DetectionRules.type] = "threshold"
                it[DetectionRules.thresholdCount] = 1
                it[DetectionRules.severity] = "medium"
                it[DetectionRules.signalTitle] = ""
                it[DetectionRules.signalMessage] = ""
                it[DetectionRules.suppressions] = "[]"
                it[DetectionRules.enabled] = false
                it[DetectionRules.tags] = "[]"
                it[DetectionRules.createdAt] = now
                it[DetectionRules.updatedAt] = now
            }.value
        }

    private fun seedOrganization(id: Int) {
        if (Organizations.selectAll().where { Organizations.id eq id }.count() > 0) {
            return
        }
        Organizations.insert {
            it[Organizations.id] = id
            it[name] = "Org $id"
            it[slug] = "org-$id"
        }
    }

    private fun createObjectAccessTables() {
        transaction {
            listOf(
                "dashboards",
                "custom_data_sources",
                "uptime_monitors",
                "synthetic_tests",
                "status_pages",
                "security_signals",
                "detection_rules",
            ).forEach { tableName ->
                exec("DROP TABLE IF EXISTS $tableName")
            }
            exec(
                """
                CREATE TABLE dashboards (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
                    org_id BIGINT NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    description TEXT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE custom_data_sources (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID DEFAULT RANDOM_UUID() NOT NULL,
                    org_id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    source_type VARCHAR(50) NOT NULL,
                    host VARCHAR(512) NOT NULL,
                    encrypted_credentials TEXT NOT NULL,
                    extra_config TEXT NOT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE uptime_monitors (
                    id UUID PRIMARY KEY,
                    organization_id INT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    type VARCHAR(50) NOT NULL,
                    url TEXT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE synthetic_tests (
                    id UUID PRIMARY KEY,
                    organization_id INT NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE status_pages (
                    id UUID PRIMARY KEY,
                    organization_id INT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    slug VARCHAR(100) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE security_signals (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    organization_id INT NOT NULL,
                    source VARCHAR(32) NOT NULL,
                    rule_id VARCHAR(255) NOT NULL,
                    rule_name VARCHAR(255) NOT NULL,
                    severity VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'open',
                    archive_reason VARCHAR(16),
                    dedup_key TEXT NOT NULL,
                    entities TEXT NOT NULL DEFAULT '{}',
                    sample_count INT NOT NULL DEFAULT 1,
                    assignee_user_id INT,
                    tags TEXT NOT NULL DEFAULT '[]',
                    first_seen TIMESTAMP NOT NULL,
                    last_seen TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE detection_rules (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    organization_id INT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    source VARCHAR(32) NOT NULL DEFAULT 'logs',
                    filter TEXT NOT NULL DEFAULT '',
                    group_by TEXT NOT NULL DEFAULT '[]',
                    window_seconds INT NOT NULL DEFAULT 300,
                    type VARCHAR(32) NOT NULL DEFAULT 'threshold',
                    threshold_count INT,
                    severity VARCHAR(16) NOT NULL DEFAULT 'medium',
                    signal_title TEXT NOT NULL DEFAULT '',
                    signal_message TEXT NOT NULL DEFAULT '',
                    suppressions TEXT NOT NULL DEFAULT '[]',
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    tags TEXT NOT NULL DEFAULT '[]',
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun stubClickHouseProjectId(projectId: Long) {
        val responseBody = """{"project_id":$projectId}""".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { output -> output.write(responseBody) }
        }
        server.start()
        clickHouseServer = server
        ClickHouseClient.close()
        ClickHouseClient.init(
            baseUrl = "http://127.0.0.1:${server.address.port}",
            database = "test_db",
            user = "default",
            password = "",
        )
    }
}
