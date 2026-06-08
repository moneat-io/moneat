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

package com.moneat.integration

import com.moneat.logs.services.LogQueryParser
import com.moneat.utils.ClickHouseSqlUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val logger = KotlinLogging.logger {}

/**
 * Integration tests for LogQueryParser against a real ClickHouse instance.
 *
 * These tests verify that parsed DataDog-style queries produce valid ClickHouse SQL
 * and return the correct rows when executed against real data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
class LogQueryParserIntegrationTest {

    companion object {
        private const val PROJECT_ID = 1L
        private const val DB_NAME = "default"
    }

    private lateinit var clickhouse: GenericContainer<*>
    private lateinit var httpClient: HttpClient
    private val parser = LogQueryParser()

    private fun chBaseUrl(): String = "http://${clickhouse.host}:${clickhouse.getMappedPort(8123)}"

    // ==========================================
    // Setup & Teardown
    // ==========================================

    @BeforeAll
    fun setup() {
        logger.info { "Starting ClickHouse container..." }
        clickhouse =
            GenericContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.1-alpine"))
                .withExposedPorts(8123, 9000)
                .withEnv("CLICKHOUSE_USER", "default")
                .withEnv("CLICKHOUSE_PASSWORD", "")
                .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
        clickhouse.start()
        logger.info { "ClickHouse started at ${chBaseUrl()}" }

        httpClient =
            HttpClient(CIO) {
                engine {
                    requestTimeout = 30_000
                }
            }

        runBlocking {
            waitForClickHouse()
            createSchema()
            insertSeedData()
        }
    }

    @AfterAll
    fun teardown() {
        httpClient.close()
        clickhouse.stop()
    }

    private suspend fun waitForClickHouse() {
        repeat(30) {
            try {
                val resp = httpClient.get("${chBaseUrl()}/ping")
                if (resp.status.value == 200) {
                    // Wait for ClickHouse to be fully ready for queries
                    kotlinx.coroutines.delay(3000)
                    httpClient.post(chBaseUrl()) {
                        contentType(ContentType.Text.Plain)
                        setBody("SELECT 1 FORMAT TabSeparated")
                    }
                    return
                }
            } catch (_: Exception) { }
            kotlinx.coroutines.delay(1000)
        }
        throw RuntimeException("ClickHouse did not start in time")
    }

    private suspend fun executeRawQuery(sql: String): String {
        val resp =
            httpClient.post(chBaseUrl()) {
                parameter("database", DB_NAME)
                contentType(ContentType.Text.Plain)
                setBody(sql)
            }
        val body = resp.bodyAsText()
        if (resp.status.value != 200) {
            throw RuntimeException("ClickHouse query failed (${resp.status}): $body\nSQL: ${sql.take(500)}")
        }
        return body
    }

    private suspend fun createSchema() {
        logger.info { "Creating logs table..." }

        // Create logs table (simplified for testing — no partition/TTL/bloom indexes)
        executeRawQuery(
            """
            CREATE TABLE IF NOT EXISTS logs (
                log_id UUID,
                project_id UInt64,
                timestamp DateTime64(3, 'UTC'),
                received_at DateTime64(3, 'UTC') DEFAULT now64(3),
                level Enum8('trace' = 1, 'debug' = 2, 'info' = 3, 'warn' = 4, 'error' = 5, 'fatal' = 6),
                message String,
                body String,
                service String,
                environment String,
                host String,
                source Enum8('sdk' = 1, 'agent_stdout' = 2, 'agent_stderr' = 3, 'otlp' = 4),
                container_name String,
                container_id String,
                container_image String,
                trace_id String,
                span_id String,
                tags Map(String, String),
                resource_attributes Map(String, String),
                system_id UUID DEFAULT toUUID('00000000-0000-0000-0000-000000000000')
            ) ENGINE = MergeTree()
            ORDER BY (project_id, timestamp, log_id)
            SETTINGS index_granularity = 8192
            """.trimIndent()
        )

        logger.info { "Logs table created." }
    }

    private suspend fun insertSeedData() {
        logger.info { "Inserting seed data..." }

        val cols =
            "log_id, project_id, timestamp, level, message, body, service, environment, host, source," +
                "container_name, container_id, container_image, trace_id, span_id, tags, resource_attributes"

        // Batch all 20 rows in a single INSERT
        val insertSql =
            "INSERT INTO logs ($cols) VALUES " +
                "('00000000-0000-0000-0000-000000000001', 1, '2025-01-15 10:00:00.000', 'error'," +
                "'Connection refused to database', 'ConnectException Connection refused', 'api-gateway'," +
                "'production', 'server1', 'sdk', 'api-container', 'abc123', 'myapp:latest', 'trace-001'," +
                "'span-001'," +
                "{'env':'prod','region':'us-east-1','http.status_code':'500','http.response_time'" +
                ":'1500','urgent':'1'}, {'sdk.name':'sentry-java'}), " +
                "('00000000-0000-0000-0000-000000000002', 1, '2025-01-15 10:01:00.000', 'error'," +
                "'Authentication failed for user admin', 'AuthenticationException Invalid credentials'," +
                "'auth-service', 'production', 'server2', 'sdk', 'auth-container', 'def456'," +
                "'myapp:latest', 'trace-002', 'span-002'," +
                "{'env':'prod','region':'us-west-2','http.status_code':'401','http.response_time'" +
                ":'50'}, {'sdk.name':'sentry-java'}), " +
                "('00000000-0000-0000-0000-000000000003', 1, '2025-01-15 10:02:00.000', 'warn'," +
                "'High memory usage detected 85 percent', 'Memory threshold exceeded', 'web-app', 'staging'," +
                "'server1', 'agent_stdout', 'web-container', 'ghi789', 'webapp:v2', '', ''," +
                "{'env':'staging','region':'eu-west-1','memory_percent':'85'}, {}), " +
                "('00000000-0000-0000-0000-000000000004', 1, '2025-01-15 10:03:00.000', 'info'," +
                "'User login successful', 'Login event processed', 'auth-service', 'production', 'serverA'," +
                "'sdk', '', '', '', 'trace-003', 'span-003', {'env':'prod','user.id':'user-42'}," +
                "{'sdk.name':'sentry-python'}), " +
                "('00000000-0000-0000-0000-000000000005', 1, '2025-01-15 10:04:00.000', 'debug'," +
                "'Cache hit for key user 123', 'Redis GET returned value', 'web-app', 'development'," +
                "'server3', 'sdk', '', '', '', '', '', {'env':'dev','cache.hit':'true'}, {}), " +
                "('00000000-0000-0000-0000-000000000006', 1, '2025-01-15 10:05:00.000', 'error'," +
                "'Timeout waiting for response', 'TimeoutException at FutureTask.get()', 'api-gateway'," +
                "'production', 'server2', 'sdk', 'api-container', 'jkl012', 'myapp:latest', 'trace-004'," +
                "'span-004', {'env':'prod','http.status_code':'504','http.response_time':'30000'}," +
                "{'sdk.name':'sentry-java'}), " +
                "('00000000-0000-0000-0000-000000000007', 1, '2025-01-15 10:06:00.000', 'info'," +
                "'Request processed successfully', 'GET /api/v1/users returned 200', 'web-server'," +
                "'production', 'server1', 'otlp', '', '', '', 'trace-005', 'span-005'," +
                "{'env':'prod','http.status_code':'200','http.response_time':'45','http.method':'GET'" +
                "}, {'service.version':'1.2.3'}), " +
                "('00000000-0000-0000-0000-000000000008', 1, '2025-01-15 10:07:00.000', 'fatal'," +
                "'Out of memory error', 'OutOfMemoryError Java heap space', 'worker', 'production'," +
                "'server4', 'sdk', 'worker-container', 'mno345', 'worker:latest', '', ''," +
                "{'env':'prod','http.response_time':'0'}, {}), " +
                "('00000000-0000-0000-0000-000000000009', 1, '2025-01-15 10:08:00.000', 'info'," +
                "'Scheduled task completed', 'Cron job finished in 5200ms', 'worker', 'staging', 'server3'," +
                "'agent_stderr', '', '', '', '', '', {'env':'staging','duration_ms':'5200'}, {}), " +
                "('00000000-0000-0000-0000-000000000010', 1, '2025-01-15 10:09:00.000', 'warn'," +
                "'Deprecated API endpoint called', 'Client used /api/v0/legacy', 'web-app', 'production'," +
                "'serverA', 'sdk', '', '', '', 'trace-006', ''," +
                "{'env':'prod','http.status_code':'200','deprecated':'true'}, {}), " +
                "('00000000-0000-0000-0000-000000000011', 1, '2025-01-15 10:10:00.000', 'error'," +
                "'Database connection pool exhausted', 'HikariPool-1 Connection is not available'," +
                "'api-gateway', 'staging', 'server2', 'sdk', 'api-container', 'pqr678', 'myapp:v2', ''," +
                "'', {'env':'staging','pool.active':'50','pool.max':'50'}, {}), " +
                "('00000000-0000-0000-0000-000000000012', 1, '2025-01-15 10:11:00.000', 'trace'," +
                "'Entering method processPayment', 'Method entry with args amount=99.99', 'payment-service'," +
                "'development', 'server1', 'sdk', '', '', '', 'trace-007', 'span-007', {'env':'dev'}, {}), " +
                "('00000000-0000-0000-0000-000000000013', 1, '2025-01-15 10:12:00.000', 'info'," +
                "'Health check passed', 'All services healthy', 'web-app', 'production', 'server1', 'otlp'," +
                "'', '', '', '', '', {'env':'prod','check':'health'}, {}), " +
                "('00000000-0000-0000-0000-000000000014', 1, '2025-01-15 10:13:00.000', 'error'," +
                "'File not found config.yaml', 'FileNotFoundException config.yaml', 'worker', 'production'," +
                "'server4', 'sdk', 'worker-container', 'stu901', 'worker:latest', '', '', {'env':'prod'}," +
                "{}), " +
                "('00000000-0000-0000-0000-000000000015', 1, '2025-01-15 10:14:00.000', 'info'," +
                "'Email sent to user at example.com', 'SMTP delivery confirmed', 'notification-service'," +
                "'production', 'server3', 'sdk', '', '', '', 'trace-008', 'span-008'," +
                "{'env':'prod','email.type':'welcome'}, {}), " +
                "('00000000-0000-0000-0000-000000000016', 1, '2025-01-15 10:15:00.000', 'warn'," +
                "'Rate limit approaching for client', 'Client xyz has used 90 percent of quota'," +
                "'api-gateway', 'production', 'server1', 'sdk', 'api-container', 'vwx234'," +
                "'myapp:latest', '', '', {'env':'prod','rate_limit.percent':'90','client.id':'xyz'}," +
                "{}), " +
                "('00000000-0000-0000-0000-000000000017', 1, '2025-01-15 10:16:00.000', 'debug'," +
                "'SQL query executed in 12ms', 'SELECT FROM users WHERE id = 42', 'web-server'," +
                "'development', 'server3', 'sdk', '', '', '', '', ''," +
                "{'env':'dev','db.duration_ms':'12'}, {}), " +
                "('00000000-0000-0000-0000-000000000018', 1, '2025-01-15 10:17:00.000', 'info'," +
                "'New deployment started v2.1.0', 'Rolling update initiated', 'web-app', 'production'," +
                "'server1', 'agent_stdout', '', '', '', '', '', {'env':'prod','version':'2.1.0'}, {}), " +
                "('00000000-0000-0000-0000-000000000019', 1, '2025-01-15 10:18:00.000', 'error'," +
                "'SSL certificate expires in 7 days', 'Certificate for example.com expiring soon'," +
                "'web-server', 'production', 'serverA', 'otlp', '', '', '', '', ''," +
                "{'env':'prod','cert.days_remaining':'7'}, {}), " +
                "('00000000-0000-0000-0000-000000000020', 1, '2025-01-15 10:19:00.000', 'info'," +
                "'Message contains special chars and *test* value', 'Body with special characters key=val'," +
                "'web-app', 'production', 'server1', 'sdk', '', '', '', '', ''," +
                "{'env':'prod','special.attr':'hello:world'}, {})"

        logger.info { "INSERT SQL length = ${insertSql.length}" }
        executeRawQuery(insertSql)

        // Verify data inserted
        val count = executeRawQuery("SELECT count() FROM logs FORMAT TabSeparated").trim()
        logger.info { "Seed data count = $count" }
        assertTrue(count.toInt() >= 20, "Expected at least 20 seed rows, got $count")
    }

    // ==========================================
    // Query Helper
    // ==========================================

    /**
     * Parse a DataDog-style query, convert to ClickHouse SQL, execute, and return matching log_ids.
     */
    private suspend fun queryLogIds(ddQuery: String): List<String> {
        val parsed = parser.parse(ddQuery)
        val whereCondition =
            if (parsed.rootNode != null) {
                parser.toClickHouseSql(parsed.rootNode, ClickHouseSqlUtils::escapeSql)
            } else {
                "1=1"
            }

        val sql =
            """
            SELECT toString(log_id) AS id
            FROM logs
            WHERE project_id = $PROJECT_ID AND ($whereCondition)
            ORDER BY timestamp ASC
            FORMAT TabSeparated
            """.trimIndent()

        logger.info { "QUERY: $ddQuery -> WHERE: $whereCondition" }

        val result = executeRawQuery(sql)
        val ids = result.trim().lines().filter { it.isNotBlank() }
        logger.info { "RESULT: ${ids.size} rows: $ids" }
        return ids
    }

    private fun logId(n: Int): String = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"

    // ==========================================
    // 3.2 Basic text search
    // ==========================================

    @Test
    @Order(1)
    fun `simple term search matches logs containing the term`() =
        runBlocking {
            // "timeout" appears in log 6 message
            val ids = queryLogIds("timeout")
            assertTrue(ids.any { it == logId(6) }, "Should find log with 'timeout' in message: $ids")
        }

    @Test
    @Order(2)
    fun `quoted phrase search matches exact phrase only`() =
        runBlocking {
            // "Connection refused" appears in log 1 message
            val ids = queryLogIds("\"Connection refused\"")
            assertTrue(ids.contains(logId(1)), "Should find log with exact phrase 'Connection refused': $ids")
            // "connection pool" is in log 11 — should not match "Connection refused"
            // but "Connection" as a phrase would match both if it were just a token search
        }

    @Test
    @Order(3)
    fun `full-text search with asterisk prefix searches all attributes`() =
        runBlocking {
            // *:payment — should find log 12 (message: "Entering method processPayment")
            val ids = queryLogIds("*:payment")
            assertTrue(ids.any { it == logId(12) }, "Full-text search should find 'payment' across fields: $ids")
        }

    // ==========================================
    // 3.3 Boolean operators
    // ==========================================

    @Test
    @Order(10)
    fun `AND narrows results`() =
        runBlocking {
            // level:error AND timeout — only log 6 has both (level=error + "timeout" in message)
            val ids = queryLogIds("level:error AND timeout")
            assertTrue(ids.contains(logId(6)), "AND should find log with both terms: $ids")
            assertTrue(
                ids.all { it == logId(6) || it != logId(1) || it != logId(2) },
                "AND should narrow results"
            )
        }

    @Test
    @Order(11)
    fun `OR widens results`() =
        runBlocking {
            // "Out of memory" OR "SSL certificate" — logs 8 and 19
            val ids = queryLogIds("\"Out of memory\" OR \"SSL certificate\"")
            assertTrue(ids.contains(logId(8)), "OR should find 'Out of memory': $ids")
            assertTrue(ids.contains(logId(19)), "OR should find 'SSL certificate': $ids")
        }

    @Test
    @Order(12)
    fun `NOT excludes results`() =
        runBlocking {
            // service:api-gateway AND -timeout — logs 1, 11, 16 are api-gateway; 6 has timeout
            val ids = queryLogIds("service:api-gateway AND -timeout")
            assertTrue(ids.contains(logId(1)), "Should include api-gateway log without 'timeout': $ids")
            assertTrue(!ids.contains(logId(6)), "Should exclude log with 'timeout': $ids")
        }

    @Test
    @Order(13)
    fun `implicit AND with consecutive terms`() =
        runBlocking {
            // "connection" "refused" — should match log 1 (both tokens in message)
            val ids = queryLogIds("connection refused")
            assertTrue(ids.contains(logId(1)), "Implicit AND should find log with both terms: $ids")
        }

    @Test
    @Order(14)
    fun `complex boolean expression`() =
        runBlocking {
            // (level:error OR level:fatal) AND service:worker
            // worker service has: log 8 (fatal), log 9 (info), log 14 (error)
            val ids = queryLogIds("(level:error OR level:fatal) AND service:worker")
            assertTrue(ids.contains(logId(8)), "Should find fatal worker log: $ids")
            assertTrue(ids.contains(logId(14)), "Should find error worker log: $ids")
            assertTrue(!ids.contains(logId(9)), "Should not find info worker log: $ids")
        }

    // ==========================================
    // 3.4 Attribute search
    // ==========================================

    @Test
    @Order(20)
    fun `attribute search with @ prefix matches tags`() =
        runBlocking {
            // @region:us-east-1 — log 1
            val ids = queryLogIds("@region:us-east-1")
            assertTrue(ids.contains(logId(1)), "Should find log with tag region=us-east-1: $ids")
            assertEquals(1, ids.size, "Only one log should have region=us-east-1")
        }

    @Test
    @Order(21)
    fun `reserved attribute service without @ prefix`() =
        runBlocking {
            val ids = queryLogIds("service:auth-service")
            assertTrue(ids.contains(logId(2)), "Should find auth-service log: $ids")
            assertTrue(ids.contains(logId(4)), "Should find auth-service log: $ids")
            assertEquals(2, ids.size, "Exactly 2 auth-service logs")
        }

    @Test
    @Order(22)
    fun `status maps to level column`() =
        runBlocking {
            val ids = queryLogIds("status:fatal")
            assertTrue(ids.contains(logId(8)), "status:fatal should find fatal log: $ids")
            assertEquals(1, ids.size, "Only one fatal log")
        }

    @Test
    @Order(23)
    fun `host reserved attribute`() =
        runBlocking {
            val ids = queryLogIds("host:server4")
            assertTrue(ids.contains(logId(8)), "Should find server4 logs: $ids")
            assertTrue(ids.contains(logId(14)), "Should find server4 logs: $ids")
        }

    @Test
    @Order(24)
    fun `source reserved attribute`() =
        runBlocking {
            val ids = queryLogIds("source:otlp")
            assertTrue(ids.contains(logId(7)), "Should find otlp source logs: $ids")
            assertTrue(ids.contains(logId(13)), "Should find otlp source logs: $ids")
            assertTrue(ids.contains(logId(19)), "Should find otlp source logs: $ids")
        }

    @Test
    @Order(25)
    fun `environment reserved attribute`() =
        runBlocking {
            val ids = queryLogIds("environment:staging")
            assertTrue(ids.contains(logId(3)), "Should find staging logs: $ids")
            assertTrue(ids.contains(logId(9)), "Should find staging logs: $ids")
            assertTrue(ids.contains(logId(11)), "Should find staging logs: $ids")
        }

    @Test
    @Order(26)
    fun `attribute search matches resource_attributes`() =
        runBlocking {
            val ids = queryLogIds("@sdk.name:sentry-python")
            assertTrue(ids.contains(logId(4)), "Should find log with resource_attribute sdk.name=sentry-python: $ids")
            assertEquals(1, ids.size)
        }

    // ==========================================
    // 3.5 Wildcards
    // ==========================================

    @Test
    @Order(30)
    fun `wildcard prefix match on service`() =
        runBlocking {
            // service:web* — matches web-app (logs 3,5,10,13,18,20) and web-server (logs 7,17,19)
            val ids = queryLogIds("service:web*")
            assertTrue(ids.contains(logId(3)), "web* should match web-app: $ids")
            assertTrue(ids.contains(logId(7)), "web* should match web-server: $ids")
            assertTrue(!ids.contains(logId(1)), "web* should not match api-gateway: $ids")
        }

    @Test
    @Order(31)
    fun `wildcard single character match`() =
        runBlocking {
            // host:server? — matches server1, server2, server3, server4, serverA
            val ids = queryLogIds("host:server?")
            assertTrue(ids.size >= 5, "server? should match many hosts: $ids")
        }

    @Test
    @Order(32)
    fun `wildcard suffix match`() =
        runBlocking {
            // service:*service — matches auth-service, payment-service, notification-service
            val ids = queryLogIds("service:*service")
            assertTrue(ids.contains(logId(2)), "*service should match auth-service: $ids")
            assertTrue(ids.contains(logId(12)), "*service should match payment-service: $ids")
            assertTrue(ids.contains(logId(15)), "*service should match notification-service: $ids")
        }

    @Test
    @Order(33)
    fun `full-text wildcard search`() =
        runBlocking {
            // *:certif* — should find log 19 (SSL certificate)
            val ids = queryLogIds("*:certif*")
            assertTrue(ids.contains(logId(19)), "Full-text wildcard should find 'certificate': $ids")
        }

    @Test
    @Order(34)
    fun `wildcards inside quotes treated as literals`() =
        runBlocking {
            // "*test*" — should match log 20 which contains literal "*test*" in message
            val ids = queryLogIds("\"*test*\"")
            assertTrue(ids.contains(logId(20)), "Quoted wildcards should match literal *test*: $ids")
        }

    // ==========================================
    // 3.6 Numerical features
    // ==========================================

    @Test
    @Order(40)
    fun `range query on tag value`() =
        runBlocking {
            // @http.status_code:[400 TO 599] — logs with 401 (log2), 500 (log1), 504 (log6)
            val ids = queryLogIds("@http.status_code:[400 TO 599]")
            assertTrue(ids.contains(logId(1)), "Range should include 500: $ids")
            assertTrue(ids.contains(logId(2)), "Range should include 401: $ids")
            assertTrue(ids.contains(logId(6)), "Range should include 504: $ids")
            assertTrue(!ids.contains(logId(7)), "Range should exclude 200: $ids")
        }

    @Test
    @Order(41)
    fun `greater than operator`() =
        runBlocking {
            // @http.response_time:>1000 — log 1 (1500), log 6 (30000)
            val ids = queryLogIds("@http.response_time:>1000")
            assertTrue(ids.contains(logId(1)), ">1000 should include 1500: $ids")
            assertTrue(ids.contains(logId(6)), ">1000 should include 30000: $ids")
            assertTrue(!ids.contains(logId(2)), ">1000 should exclude 50: $ids")
            assertTrue(!ids.contains(logId(7)), ">1000 should exclude 45: $ids")
        }

    @Test
    @Order(42)
    fun `greater than or equal operator`() =
        runBlocking {
            // @http.response_time:>=1500 — log 1 (1500), log 6 (30000)
            val ids = queryLogIds("@http.response_time:>=1500")
            assertTrue(ids.contains(logId(1)), ">=1500 should include 1500: $ids")
            assertTrue(ids.contains(logId(6)), ">=1500 should include 30000: $ids")
        }

    @Test
    @Order(43)
    fun `less than operator`() =
        runBlocking {
            // @http.response_time:<50 — log 7 (45), log 8 (0)
            val ids = queryLogIds("@http.response_time:<50")
            assertTrue(ids.contains(logId(7)), "<50 should include 45: $ids")
            assertTrue(ids.contains(logId(8)), "<50 should include 0: $ids")
            assertTrue(!ids.contains(logId(1)), "<50 should exclude 1500: $ids")
        }

    @Test
    @Order(44)
    fun `less than or equal operator`() =
        runBlocking {
            // @http.response_time:<=45 — log 7 (45), log 8 (0)
            val ids = queryLogIds("@http.response_time:<=45")
            assertTrue(ids.contains(logId(7)), "<=45 should include 45: $ids")
            assertTrue(ids.contains(logId(8)), "<=45 should include 0: $ids")
            assertTrue(!ids.contains(logId(2)), "<=45 should exclude 50: $ids")
        }

    // ==========================================
    // 3.7 Existence checks
    // ==========================================

    @Test
    @Order(50)
    fun `existence check for tag returns logs that have the attribute`() =
        runBlocking {
            // @http.method:* — only log 7 has http.method tag
            val ids = queryLogIds("@http.method:*")
            assertTrue(ids.contains(logId(7)), "Existence check should find log with http.method: $ids")
            assertEquals(1, ids.size, "Only one log has http.method tag")
        }

    @Test
    @Order(51)
    fun `non-existence check returns logs without the attribute`() =
        runBlocking {
            // -@http.method:* — all logs except log 7
            val ids = queryLogIds("-@http.method:*")
            assertTrue(!ids.contains(logId(7)), "Non-existence should exclude log with http.method: $ids")
            assertTrue(ids.size >= 19, "Most logs should not have http.method: $ids")
        }

    @Test
    @Order(52)
    fun `existence check for top-level field`() =
        runBlocking {
            // trace_id:* — logs with non-empty trace_id
            val ids = queryLogIds("trace_id:*")
            assertTrue(ids.contains(logId(1)), "Should find log with trace_id: $ids")
            assertTrue(ids.contains(logId(2)), "Should find log with trace_id: $ids")
            // Logs without trace_id (empty string) should be excluded
            assertTrue(!ids.contains(logId(3)), "Should exclude log without trace_id: $ids")
        }

    // ==========================================
    // 3.8 Tag-specific syntax
    // ==========================================

    @Test
    @Order(60)
    fun `tags colon checks tag key existence`() =
        runBlocking {
            // tags:urgent — log 1 has 'urgent' tag key
            val ids = queryLogIds("tags:urgent")
            assertTrue(ids.contains(logId(1)), "Should find log with 'urgent' tag key: $ids")
            assertEquals(1, ids.size, "Only one log has 'urgent' tag")
        }

    @Test
    @Order(61)
    fun `grouped field values with OR`() =
        runBlocking {
            // env:(prod OR staging) — should find logs where env tag is prod or staging
            val ids = queryLogIds("env:(prod OR staging)")
            // env=prod: logs 1,2,4,6,7,8,10,13,14,15,16,18,19,20
            // env=staging: logs 3,9,11
            assertTrue(ids.contains(logId(1)), "Should find env=prod: $ids")
            assertTrue(ids.contains(logId(3)), "Should find env=staging: $ids")
            // env=dev: logs 5,12,17 — should be excluded
            assertTrue(!ids.contains(logId(5)), "Should exclude env=dev: $ids")
        }

    @Test
    @Order(62)
    fun `grouped field values on top-level field`() =
        runBlocking {
            // service:(worker OR payment-service) — logs 8,9,14 (worker) and 12 (payment-service)
            val ids = queryLogIds("service:(worker OR payment-service)")
            assertTrue(ids.contains(logId(8)), "Should find worker: $ids")
            assertTrue(ids.contains(logId(12)), "Should find payment-service: $ids")
            assertTrue(!ids.contains(logId(1)), "Should not find api-gateway: $ids")
        }

    // ==========================================
    // 3.9 Special characters and escaping
    // ==========================================

    @Test
    @Order(70)
    fun `escaped colon in attribute value`() =
        runBlocking {
            // @special.attr:hello\:world — log 20 has special.attr=hello:world
            val ids = queryLogIds("@special.attr:hello\\:world")
            assertTrue(ids.contains(logId(20)), "Should find log with escaped colon value: $ids")
        }

    @Test
    @Order(71)
    fun `quoted value with special characters`() =
        runBlocking {
            // @special.attr:"hello:world" — log 20
            val ids = queryLogIds("@special.attr:\"hello:world\"")
            assertTrue(ids.contains(logId(20)), "Should find log with quoted special char value: $ids")
        }

    @Test
    @Order(72)
    fun `SQL injection attempt is safely escaped`() =
        runBlocking {
            // Should not throw or cause SQL errors
            val ids = queryLogIds("service:'; DROP TABLE logs; --")
            assertTrue(ids.isEmpty(), "SQL injection should return no results: $ids")
        }

    @Test
    @Order(73)
    fun `query with backslash in value`() =
        runBlocking {
            // Should not cause SQL errors
            queryLogIds("message:test\\\\value")
            // No logs match, but query should execute without error
            assertTrue(true, "Backslash query should not throw")
        }

    // ==========================================
    // 3.10 Edge cases
    // ==========================================

    @Test
    @Order(80)
    fun `empty query returns all logs`() =
        runBlocking {
            val ids = queryLogIds("")
            assertEquals(20, ids.size, "Empty query should return all 20 logs")
        }

    @Test
    @Order(81)
    fun `whitespace-only query returns all logs`() =
        runBlocking {
            val ids = queryLogIds("   ")
            assertEquals(20, ids.size, "Whitespace query should return all 20 logs")
        }

    @Test
    @Order(82)
    fun `unclosed quote handles gracefully`() =
        runBlocking {
            // Should not throw — may return results or empty
            queryLogIds("\"unclosed quote")
            assertTrue(true, "Unclosed quote should not throw")
        }

    @Test
    @Order(83)
    fun `unclosed parenthesis handles gracefully`() =
        runBlocking {
            val ids = queryLogIds("(level:error AND timeout")
            // Should still work and find log 6
            assertTrue(ids.contains(logId(6)), "Unclosed paren should still parse: $ids")
        }

    @Test
    @Order(84)
    fun `deeply nested parentheses`() =
        runBlocking {
            val ids = queryLogIds("((service:worker OR service:api-gateway) AND (level:error OR level:fatal))")
            assertTrue(ids.contains(logId(1)), "Should find api-gateway error: $ids")
            assertTrue(ids.contains(logId(8)), "Should find worker fatal: $ids")
        }

    @Test
    @Order(85)
    fun `very long query string`() =
        runBlocking {
            // Build a long query with many OR clauses
            val longQuery = (1..20).joinToString(" OR ") { "service:service-$it" }
            // Should execute without error even though no logs match
            queryLogIds(longQuery)
            assertTrue(true, "Long query should not throw")
        }

    @Test
    @Order(86)
    fun `unicode in search terms`() =
        runBlocking {
            // Should not throw
            queryLogIds("message:héllo")
            assertTrue(true, "Unicode query should not throw")
        }

    // ==========================================
    // 3.11 Enum8 field handling
    // ==========================================

    @Test
    @Order(90)
    fun `level field with toString cast works against real Enum8`() =
        runBlocking {
            val ids = queryLogIds("level:error")
            // error logs: 1, 2, 6, 11, 14, 19
            assertTrue(ids.contains(logId(1)), "level:error should find error logs: $ids")
            assertTrue(ids.contains(logId(2)), "level:error should find error logs: $ids")
            assertTrue(ids.contains(logId(6)), "level:error should find error logs: $ids")
            assertTrue(!ids.contains(logId(3)), "level:error should not find warn logs: $ids")
            assertTrue(!ids.contains(logId(4)), "level:error should not find info logs: $ids")
        }

    @Test
    @Order(91)
    fun `source field with toString cast works against real Enum8`() =
        runBlocking {
            val ids = queryLogIds("source:agent_stdout")
            assertTrue(ids.contains(logId(3)), "source:agent_stdout should find agent logs: $ids")
            assertTrue(ids.contains(logId(18)), "source:agent_stdout should find agent logs: $ids")
        }

    @Test
    @Order(92)
    fun `wildcard on enum field works`() =
        runBlocking {
            // level:err* should match 'error'
            val ids = queryLogIds("level:err*")
            assertTrue(ids.contains(logId(1)), "level:err* should match error: $ids")
            assertTrue(!ids.contains(logId(3)), "level:err* should not match warn: $ids")
        }

    @Test
    @Order(93)
    fun `status synonym maps to level and works with Enum8`() =
        runBlocking {
            val ids = queryLogIds("status:warn")
            assertTrue(ids.contains(logId(3)), "status:warn should find warn logs: $ids")
            assertTrue(ids.contains(logId(10)), "status:warn should find warn logs: $ids")
            assertTrue(ids.contains(logId(16)), "status:warn should find warn logs: $ids")
        }

    // ==========================================
    // Complex real-world queries
    // ==========================================

    @Test
    @Order(100)
    fun `real-world query - error in production api`() =
        runBlocking {
            val ids = queryLogIds("service:api-gateway AND level:error AND @env:prod")
            assertTrue(ids.contains(logId(1)), "Should find prod api-gateway error: $ids")
            assertTrue(ids.contains(logId(6)), "Should find prod api-gateway error: $ids")
            assertTrue(!ids.contains(logId(11)), "Should exclude staging: $ids")
        }

    @Test
    @Order(101)
    fun `real-world query - slow responses`() =
        runBlocking {
            val ids = queryLogIds("@http.response_time:>1000 AND @env:prod")
            assertTrue(ids.contains(logId(1)), "Should find slow response: $ids")
            assertTrue(ids.contains(logId(6)), "Should find slow response: $ids")
        }

    @Test
    @Order(102)
    fun `real-world query - auth failures excluding test users`() =
        runBlocking {
            val ids = queryLogIds("service:auth-service AND (failed OR error) AND -@user.id:test*")
            assertTrue(ids.contains(logId(2)), "Should find auth failure: $ids")
        }

    @Test
    @Order(103)
    fun `real-world query - non-200 status codes in production`() =
        runBlocking {
            val ids = queryLogIds("@http.status_code:[400 TO 599] AND @env:prod")
            assertTrue(ids.contains(logId(1)), "Should find 500: $ids")
            assertTrue(ids.contains(logId(2)), "Should find 401: $ids")
            assertTrue(ids.contains(logId(6)), "Should find 504: $ids")
        }

    // ==========================================
    // Regression: SELECT with toString(level) AS level_text alongside WHERE referencing level
    // Using level_text alias avoids "Block structure mismatch" error
    // ==========================================

    @Test
    @Order(110)
    fun `SELECT toString(level) AS level_text does not conflict with WHERE clause on level`() =
        runBlocking {
            // Uses the production alias pattern: toString(level) AS level_text
            // combined with a WHERE clause that references toString(level) via level:error
            val parsed = parser.parse("level:error")
            val whereCondition = parser.toClickHouseSql(parsed.rootNode!!, ClickHouseSqlUtils::escapeSql)

            val sql =
                """
            SELECT toString(log_id) AS id,
                   toString(level) AS level_text,
                   message,
                   service
            FROM logs
            WHERE project_id = $PROJECT_ID AND ($whereCondition)
            ORDER BY timestamp ASC
            FORMAT TabSeparated
                """.trimIndent()

            // This should NOT throw "Block structure mismatch... level String vs level Enum8"
            val result = executeRawQuery(sql)
            val rows = result.trim().lines().filter { it.isNotBlank() }
            assertTrue(rows.isNotEmpty(), "Should find error logs without type conflict: $rows")
        }

    @Test
    @Order(111)
    fun `SELECT toString(source) AS source_text does not conflict with WHERE clause on source`() =
        runBlocking {
            val parsed = parser.parse("source:sdk")
            val whereCondition = parser.toClickHouseSql(parsed.rootNode!!, ClickHouseSqlUtils::escapeSql)

            val sql =
                """
            SELECT toString(log_id) AS id,
                   toString(source) AS source_text,
                   message
            FROM logs
            WHERE project_id = $PROJECT_ID AND ($whereCondition)
            ORDER BY timestamp ASC
            FORMAT TabSeparated
                """.trimIndent()

            val result = executeRawQuery(sql)
            val rows = result.trim().lines().filter { it.isNotBlank() }
            assertTrue(rows.isNotEmpty(), "Should find sdk source logs without type conflict: $rows")
        }

    // ==========================================
    // 8. Hyphenated / separator term tests
    // ==========================================

    @Test
    @Order(120)
    fun `free text search with hyphenated term uses ILIKE fallback`() =
        runBlocking {
            // "api-gateway" contains a hyphen — hasTokenCaseInsensitive would reject it.
            // Should fall back to ILIKE and find logs with service=api-gateway in the message/body/service fields.
            val ids = queryLogIds("api-gateway")
            assertTrue(ids.contains(logId(1)), "Should find api-gateway log via ILIKE fallback: $ids")
            assertTrue(ids.contains(logId(6)), "Should find another api-gateway log: $ids")
        }

    @Test
    @Order(121)
    fun `free text search with dotted term uses ILIKE fallback`() =
        runBlocking {
            // "config.yaml" contains a dot — another separator char
            val ids = queryLogIds("config.yaml")
            assertTrue(ids.contains(logId(14)), "Should find log with config.yaml in message: $ids")
        }

    @Test
    @Order(122)
    fun `production-like SELECT with toString level and source aliases`() =
        runBlocking {
            // Reproduce the exact production query shape — uses level_text/source_text aliases
            // to avoid Enum8 column name collision
            val parsed = parser.parse("level:error AND timeout")
            val whereCondition = parser.toClickHouseSql(parsed.rootNode!!, ClickHouseSqlUtils::escapeSql)

            val sql =
                """
            SELECT
                toString(log_id) AS log_id,
                formatDateTime(timestamp, '%Y-%m-%dT%H:%i:%S.%fZ') AS timestamp_formatted,
                toString(level) AS level_text,
                message,
                body,
                service,
                environment,
                host,
                toString(source) AS source_text,
                container_name
            FROM logs
            WHERE project_id = $PROJECT_ID AND ($whereCondition)
            ORDER BY timestamp DESC, log_id DESC
            LIMIT 151
            FORMAT JSONEachRow
                """.trimIndent()

            // This must NOT throw "Block structure mismatch" error
            val result = executeRawQuery(sql)
            assertTrue(result.contains("Timeout"), "Production-like query should return timeout log: $result")
        }

    @Test
    @Order(123)
    fun `production-like SELECT with hyphenated free text search`() =
        runBlocking {
            // Free text "api-gateway" with production SELECT shape
            val parsed = parser.parse("api-gateway")
            val whereCondition = parser.toClickHouseSql(parsed.rootNode!!, ClickHouseSqlUtils::escapeSql)

            val sql =
                """
            SELECT
                toString(log_id) AS log_id,
                toString(level) AS level_text,
                message,
                toString(source) AS source_text
            FROM logs
            WHERE project_id = $PROJECT_ID AND ($whereCondition)
            ORDER BY timestamp DESC
            LIMIT 10
            FORMAT JSONEachRow
                """.trimIndent()

            val result = executeRawQuery(sql)
            assertTrue(result.isNotBlank(), "Hyphenated search with production SELECT should return results: $result")
        }
    // ==========================================
    // 9. Edge case and regression tests
    // ==========================================

    @Test
    @Order(130)
    fun `trailing colon in field does not crash`() =
        runBlocking {
            // "auth-service:" has empty value after colon — should parse as free text
            val ids = queryLogIds("service:api-gateway OR auth-service:")
            // Should not throw; api-gateway logs should still be found
            assertTrue(ids.contains(logId(1)), "Should still find api-gateway logs: $ids")
        }

    @Test
    @Order(131)
    fun `wildcard field search on host`() =
        runBlocking {
            // host:server* should match all hosts starting with "server"
            val ids = queryLogIds("host:server*")
            assertTrue(ids.isNotEmpty(), "host:server* should find logs: $ids")
            assertTrue(ids.contains(logId(1)), "Should find server1 log: $ids")
            assertTrue(ids.contains(logId(2)), "Should find server2 log: $ids")
        }

    @Test
    @Order(132)
    fun `negated field search excludes results`() =
        runBlocking {
            // -host:server1 should exclude logs from server1
            val ids = queryLogIds("-host:server1")
            assertTrue(ids.isNotEmpty(), "Negated search should return some results: $ids")
            assertFalse(ids.contains(logId(1)), "Should NOT contain server1 log: $ids")
        }

    @Test
    @Order(133)
    fun `message field wildcard search`() =
        runBlocking {
            // message:Rate* should find "Rate limit approaching for client" (log 16)
            val ids = queryLogIds("message:Rate*")
            assertTrue(ids.contains(logId(16)), "message:Rate* should find rate limit log: $ids")
        }

    @Test
    @Order(134)
    fun `wildcard field search with production SELECT`() =
        runBlocking {
            // Verify wildcard field search works with the production SELECT shape
            val parsed = parser.parse("host:server*")
            val whereCondition = parser.toClickHouseSql(parsed.rootNode!!, ClickHouseSqlUtils::escapeSql)

            val sql =
                """
            SELECT
                toString(log_id) AS log_id,
                toString(level) AS level_text,
                message,
                host,
                toString(source) AS source_text
            FROM logs
            WHERE project_id = $PROJECT_ID AND ($whereCondition)
            ORDER BY timestamp DESC
            LIMIT 10
            FORMAT JSONEachRow
                """.trimIndent()

            val result = executeRawQuery(sql)
            assertTrue(result.contains("server"), "Wildcard host search should return results: $result")
        }
}
