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

import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.DashboardVariable
import com.moneat.dashboards.services.CustomDataSourceExecutor
import com.moneat.dashboards.services.CustomDataSourceService
import com.moneat.dashboards.services.DashboardVariableResolver
import com.moneat.dashboards.services.DataSourceCredentials
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DashboardVariableResolverTest {
    private val dataSourceService = mockk<CustomDataSourceService>()
    private val dataSourceExecutor = mockk<CustomDataSourceExecutor>()
    private val resolver = DashboardVariableResolver(dataSourceService, dataSourceExecutor)

    @Test
    fun `resolve returns label values from matching source`() = runBlocking {
        val source = makeDataSource(
            id = 10L,
            sourceType = "prometheus",
            host = "prometheus.local",
            port = 9090,
        )
        every { dataSourceService.listDataSources(ORG_ID) } returns listOf(source)
        every { dataSourceService.getDecryptedCredentials(source.numericId, ORG_ID) } returns
            DataSourceCredentials(apiKey = "token")
        coEvery {
            dataSourceExecutor.executeLabelValuesQuery(
                CustomDataSourceType.PROMETHEUS,
                source.host,
                source.port,
                any(),
                "label_values(up{job=\"api\"}, namespace)",
            )
        } returns listOf("default")

        val result = resolver.resolve(
            listOf(
                DashboardVariable(
                    name = "namespace",
                    query = "label_values(up{job=\"\$job\"}, namespace)",
                    datasource = "__prometheus",
                )
            ),
            mapOf("job" to "api"),
            ORG_ID,
        )

        assertEquals(mapOf("namespace" to listOf("default")), result)
    }

    @Test
    fun `resolve returns first-column options from SQL variable query`() = runBlocking {
        val source = makeDataSource(id = 11L, sourceType = "postgresql")
        every { dataSourceService.listDataSources(ORG_ID) } returns listOf(source)
        every { dataSourceService.getDecryptedCredentials(source.numericId, ORG_ID) } returns
            DataSourceCredentials(username = "user", password = "pass")
        coEvery {
            dataSourceExecutor.executeQuery(
                source.numericId,
                CustomDataSourceType.POSTGRESQL,
                source.host,
                source.port,
                source.databaseName,
                any(),
                "select dag_id from public.dag where owner = 'analytics'",
                500,
                null,
            )
        } returns listOf(
            mapOf("dag_id" to JsonPrimitive("etl_daily")),
            mapOf("dag_id" to JsonPrimitive("sync_hourly")),
            mapOf("dag_id" to JsonPrimitive("etl_daily")),
            mapOf("dag_id" to JsonPrimitive("")),
        )

        val result = resolver.resolve(
            listOf(
                DashboardVariable(
                    name = "Dags",
                    query = "select dag_id from public.dag where owner = '\$owner'",
                    datasource = "__postgresql",
                )
            ),
            mapOf("owner" to "analytics"),
            ORG_ID,
        )

        assertEquals(mapOf("Dags" to listOf("etl_daily", "sync_hourly")), result)
    }

    @Test
    fun `resolve escapes SQL variables and preserves overlapping names`() = runBlocking {
        val source = makeDataSource(id = 12L, sourceType = "postgresql")
        every { dataSourceService.listDataSources(ORG_ID) } returns listOf(source)
        every { dataSourceService.getDecryptedCredentials(source.numericId, ORG_ID) } returns
            DataSourceCredentials(username = "user", password = "pass")
        coEvery {
            dataSourceExecutor.executeQuery(
                source.numericId,
                CustomDataSourceType.POSTGRESQL,
                source.host,
                source.port,
                source.databaseName,
                any(),
                "select dag_id from public.dag where owner = 'O''Brien' and owner_id = '42' " +
                    "and note like '%O''Brien%'",
                500,
                null,
            )
        } returns listOf(mapOf("dag_id" to JsonPrimitive("etl_daily")))

        val result = resolver.resolve(
            listOf(
                DashboardVariable(
                    name = "Dags",
                    query = "select dag_id from public.dag where owner = '${'$'}owner' " +
                        "and owner_id = ${'$'}owner_id and note like '%${'$'}{owner}%'",
                    datasource = "__postgresql",
                )
            ),
            mapOf("owner" to "O'Brien", "owner_id" to "42"),
            ORG_ID,
        )

        assertEquals(mapOf("Dags" to listOf("etl_daily")), result)
    }

    @Test
    fun `resolve propagates failed SQL variable query`() = runBlocking {
        val source = makeDataSource(id = 13L, sourceType = "postgresql")
        every { dataSourceService.listDataSources(ORG_ID) } returns listOf(source)
        every { dataSourceService.getDecryptedCredentials(source.numericId, ORG_ID) } returns
            DataSourceCredentials(username = "user", password = "pass")
        coEvery {
            dataSourceExecutor.executeQuery(
                source.numericId,
                CustomDataSourceType.POSTGRESQL,
                source.host,
                source.port,
                source.databaseName,
                any(),
                "select missing from public.dag",
                500,
                null,
            )
        } throws SQLException("column missing does not exist")

        val ex = assertFailsWith<SQLException> {
            resolver.resolve(
                listOf(
                    DashboardVariable(
                        name = "Dags",
                        query = "select missing from public.dag",
                        datasource = "__postgresql",
                    )
                ),
                emptyMap(),
                ORG_ID,
            )
        }

        assertEquals("column missing does not exist", ex.message)
    }

    private fun makeDataSource(
        id: Long,
        sourceType: String,
        host: String = "localhost",
        port: Int = 5432,
    ) = CustomDataSourceResponse(
        id = resourceId(id),
        orgId = resourceId(ORG_ID),
        name = "Source $id",
        description = null,
        sourceType = sourceType,
        host = host,
        port = port,
        databaseName = "testdb",
        extraConfig = emptyMap(),
        enabled = true,
        createdBy = resourceId(1L),
        createdAt = DEFAULT_TIMESTAMP,
        updatedAt = DEFAULT_TIMESTAMP,
        numericId = id,
        hasCredentials = true,
        usedByDashboardCount = 0,
    )

    private fun resourceId(id: Long): String =
        "00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"

    private companion object {
        private const val ORG_ID = 1L
        private const val DEFAULT_TIMESTAMP = "2024-01-01T00:00:00"
    }
}
