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
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.McpToolRegistry
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.mcp.protocol.ToolContent
import com.moneat.mcp.tools.GetContainerMetricsTool
import com.moneat.mcp.tools.GetHostMetricsTool
import com.moneat.shared.models.Hosts
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.testsupport.TestDatabaseHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

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
            args = JsonObject(mapOf("project_id" to JsonPrimitive(projectId))),
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

    private fun seedOrganization(id: Int) {
        Organizations.insert {
            it[Organizations.id] = id
            it[name] = "Org $id"
            it[slug] = "org-$id"
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
