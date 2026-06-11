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

package com.moneat.dashboards.services.handlers

import com.moneat.dashboards.services.DataSourceCredentials
import com.zaxxer.hikari.HikariDataSource
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the connection options the Add-data-source dialog persists in
 * extra_config (auth_method, tls_mode, warehouse/role/schema) are actually
 * honored when building auth headers and JDBC URLs.
 */
class ConnectionConfigHonoringTest {

    private val pools = ConcurrentHashMap<Long, HikariDataSource>()
    private fun basic(user: String, pass: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    private fun headersFor(
        credentials: DataSourceCredentials,
        default: HttpApiHandler.HttpAuthDefault = HttpApiHandler.HttpAuthDefault.NONE,
    ) = HttpApiHandler.resolveHttpAuthHeaders(credentials, default)

    // ----- HTTP auth_method honoring ----------------------------------------

    @Test
    fun `basic auth_method emits a base64 Basic header`() {
        val headers = headersFor(
            DataSourceCredentials(username = "u", password = "p", options = ConnectionOptions(authMethod = "basic")),
        )
        assertEquals(listOf("Authorization" to basic("u", "p")), headers)
    }

    @Test
    fun `bearer auth_method emits a Bearer header`() {
        val headers = headersFor(
            DataSourceCredentials(apiKey = "tok", options = ConnectionOptions(authMethod = "bearer")),
        )
        assertEquals(listOf("Authorization" to "Bearer tok"), headers)
    }

    @Test
    fun `custom header auth_method emits the named header`() {
        val headers = headersFor(
            DataSourceCredentials(
                headerValue = "tenant-7",
                options = ConnectionOptions(authMethod = "header", headerName = "X-Scope-OrgID"),
            ),
        )
        assertEquals(listOf("X-Scope-OrgID" to "tenant-7"), headers)
    }

    @Test
    fun `none auth_method emits nothing even when credentials are present`() {
        val headers = headersFor(
            DataSourceCredentials(
                apiKey = "tok",
                username = "u",
                password = "p",
                headerValue = "v",
                options = ConnectionOptions(authMethod = "none"),
            ),
            HttpApiHandler.HttpAuthDefault.BEARER,
        )
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `bearer with a blank token emits nothing`() {
        val headers = headersFor(
            DataSourceCredentials(apiKey = "", options = ConnectionOptions(authMethod = "bearer")),
        )
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `custom header without a value emits nothing`() {
        val headers = headersFor(
            DataSourceCredentials(options = ConnectionOptions(authMethod = "header", headerName = "X-Tenant")),
        )
        assertTrue(headers.isEmpty())
    }

    // ----- legacy vendor defaults (no auth_method stored) -------------------

    @Test
    fun `missing auth_method falls back to the vendor default scheme`() {
        assertEquals(
            listOf("Authorization" to "Bearer tok"),
            headersFor(DataSourceCredentials(apiKey = "tok"), HttpApiHandler.HttpAuthDefault.BEARER),
        )
        assertEquals(
            listOf("Authorization" to "Token tok"),
            headersFor(DataSourceCredentials(apiKey = "tok"), HttpApiHandler.HttpAuthDefault.TOKEN),
        )
        assertEquals(
            listOf("X-Scope-OrgID" to "tenant"),
            headersFor(DataSourceCredentials(apiKey = "tenant"), HttpApiHandler.HttpAuthDefault.ORG_ID),
        )
    }

    @Test
    fun `elasticsearch default prefers ApiKey then basic`() {
        assertEquals(
            listOf("Authorization" to "ApiKey k"),
            headersFor(
                DataSourceCredentials(apiKey = "k", username = "u", password = "p"),
                HttpApiHandler.HttpAuthDefault.ELASTICSEARCH,
            ),
        )
        assertEquals(
            listOf("Authorization" to basic("u", "p")),
            headersFor(
                DataSourceCredentials(username = "u", password = "p"),
                HttpApiHandler.HttpAuthDefault.ELASTICSEARCH,
            ),
        )
    }

    // ----- JDBC tls_mode + per-vendor params --------------------------------

    @Test
    fun `postgres maps tls_mode to sslmode and leaves blank as driver default`() {
        assertEquals(
            "jdbc:postgresql://h:5432/app?sslmode=require",
            PostgresHandler(pools).buildJdbcUrl("h", 5432, "app", ConnectionOptions(tlsMode = "require")),
        )
        assertEquals(
            "jdbc:postgresql://h:5432/app",
            PostgresHandler(pools).buildJdbcUrl("h", 5432, "app", ConnectionOptions()),
        )
    }

    @Test
    fun `postgres combines sslmode and currentSchema`() {
        assertEquals(
            "jdbc:postgresql://h:5432/app?sslmode=verify-full&currentSchema=reporting",
            PostgresHandler(pools).buildJdbcUrl(
                "h",
                5432,
                "app",
                ConnectionOptions(tlsMode = "verify-full", schema = "reporting"),
            ),
        )
    }

    @Test
    fun `cockroach reuses the postgres sslmode suffix`() {
        assertEquals(
            "jdbc:postgresql://h:26257/defaultdb?sslmode=verify-ca",
            CockroachHandler(pools).buildJdbcUrl("h", 26257, "", ConnectionOptions(tlsMode = "verify-ca")),
        )
    }

    @Test
    fun `mysql maps tls_mode to the sslMode enum`() {
        assertEquals(
            "jdbc:mysql://h:3306/app?sslMode=VERIFY_IDENTITY",
            MySQLHandler(pools).buildJdbcUrl("h", 3306, "app", ConnectionOptions(tlsMode = "verify-full")),
        )
        assertEquals(
            "jdbc:mysql://h:3306/app",
            MySQLHandler(pools).buildJdbcUrl("h", 3306, "app", ConnectionOptions()),
        )
    }

    @Test
    fun `mariadb maps require to trust`() {
        assertEquals(
            "jdbc:mariadb://h:3306/app?sslMode=trust",
            MariaDBHandler(pools).buildJdbcUrl("h", 3306, "app", ConnectionOptions(tlsMode = "require")),
        )
    }

    @Test
    fun `mssql maps tls_mode to encrypt and trustServerCertificate`() {
        assertEquals(
            "jdbc:sqlserver://h:1433;databaseName=app;encrypt=true;trustServerCertificate=false",
            MSSQLHandler(pools).buildJdbcUrl("h", 1433, "app", ConnectionOptions(tlsMode = "verify-full")),
        )
        assertEquals(
            "jdbc:sqlserver://h:1433;databaseName=app;encrypt=false",
            MSSQLHandler(pools).buildJdbcUrl("h", 1433, "app", ConnectionOptions(tlsMode = "disable")),
        )
        assertEquals(
            "jdbc:sqlserver://h:1433;databaseName=master",
            MSSQLHandler(pools).buildJdbcUrl("h", 1433, "", ConnectionOptions()),
        )
    }

    @Test
    fun `snowflake appends warehouse role and schema`() {
        assertEquals(
            "jdbc:snowflake://acme.snowflakecomputing.com/?db=ANALYTICS&warehouse=WH&role=REPORTER&schema=PUBLIC",
            SnowflakeHandler(pools).buildJdbcUrl(
                "acme",
                443,
                "ANALYTICS",
                ConnectionOptions(warehouse = "WH", role = "REPORTER", schema = "PUBLIC"),
            ),
        )
        assertEquals(
            "jdbc:snowflake://acme.snowflakecomputing.com/",
            SnowflakeHandler(pools).buildJdbcUrl("acme", 443, "", ConnectionOptions()),
        )
    }
}
