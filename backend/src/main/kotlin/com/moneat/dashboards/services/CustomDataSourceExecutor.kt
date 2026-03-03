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

package com.moneat.dashboards.services

import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.TestConnectionRequest
import com.moneat.dashboards.models.TestConnectionResult
import com.moneat.dashboards.models.TimeRangeDef
import com.moneat.dashboards.services.handlers.BigQueryHandler
import com.moneat.dashboards.services.handlers.CloudWatchHandler
import com.moneat.dashboards.services.handlers.ClickHouseHandler
import com.moneat.dashboards.services.handlers.CockroachHandler
import com.moneat.dashboards.services.handlers.DataSourceHandler
import com.moneat.dashboards.services.handlers.ElasticsearchHandler
import com.moneat.dashboards.services.handlers.GraphiteHandler
import com.moneat.dashboards.services.handlers.InfluxDBHandler
import com.moneat.dashboards.services.handlers.LokiHandler
import com.moneat.dashboards.services.handlers.MariaDBHandler
import com.moneat.dashboards.services.handlers.MongoDBHandler
import com.moneat.dashboards.services.handlers.MSSQLHandler
import com.moneat.dashboards.services.handlers.MySQLHandler
import com.moneat.dashboards.services.handlers.PostgresHandler
import com.moneat.dashboards.services.handlers.PrometheusHandler
import com.moneat.dashboards.services.handlers.RedisHandler
import com.moneat.dashboards.services.handlers.SnowflakeHandler
import com.moneat.dashboards.services.handlers.SQLiteHandler
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

class CustomDataSourceExecutor {

    private val jdbcPools = ConcurrentHashMap<Long, HikariDataSource>()
    private val postgresHandler = PostgresHandler(jdbcPools)
    private val prometheusHandler = PrometheusHandler()
    private val mysqlHandler = MySQLHandler(jdbcPools)
    private val mariadbHandler = MariaDBHandler(jdbcPools)
    private val mssqlHandler = MSSQLHandler(jdbcPools)
    private val clickhouseHandler = ClickHouseHandler(jdbcPools)
    private val sqliteHandler = SQLiteHandler(jdbcPools)
    private val cockroachHandler = CockroachHandler(jdbcPools)
    private val bigQueryHandler = BigQueryHandler()
    private val snowflakeHandler = SnowflakeHandler(jdbcPools)
    private val influxDBHandler = InfluxDBHandler()
    private val elasticsearchHandler = ElasticsearchHandler()
    private val graphiteHandler = GraphiteHandler()
    private val lokiHandler = LokiHandler()
    private val cloudWatchHandler = CloudWatchHandler()
    private val mongoDBHandler = MongoDBHandler()
    private val redisHandler = RedisHandler()

    private val handlers: Map<CustomDataSourceType, DataSourceHandler> = mapOf(
        CustomDataSourceType.POSTGRESQL to postgresHandler,
        CustomDataSourceType.PROMETHEUS to prometheusHandler,
        CustomDataSourceType.MYSQL to mysqlHandler,
        CustomDataSourceType.MARIADB to mariadbHandler,
        CustomDataSourceType.MSSQL to mssqlHandler,
        CustomDataSourceType.CLICKHOUSE to clickhouseHandler,
        CustomDataSourceType.SQLITE to sqliteHandler,
        CustomDataSourceType.COCKROACHDB to cockroachHandler,
        CustomDataSourceType.BIGQUERY to bigQueryHandler,
        CustomDataSourceType.SNOWFLAKE to snowflakeHandler,
        CustomDataSourceType.INFLUXDB to influxDBHandler,
        CustomDataSourceType.ELASTICSEARCH to elasticsearchHandler,
        CustomDataSourceType.GRAPHITE to graphiteHandler,
        CustomDataSourceType.LOKI to lokiHandler,
        CustomDataSourceType.CLOUDWATCH to cloudWatchHandler,
        CustomDataSourceType.MONGODB to mongoDBHandler,
        CustomDataSourceType.REDIS to redisHandler,
    )

    /**
     * Test connectivity to a data source without persisting it.
     */
    suspend fun testConnection(request: TestConnectionRequest): TestConnectionResult {
        val sourceType = CustomDataSourceType.fromString(request.sourceType)
            ?: return TestConnectionResult(false, "Unsupported source type: ${request.sourceType}")

        val handler = handlers[sourceType]
            ?: return TestConnectionResult(false, "Unsupported source type: ${request.sourceType}")

        return handler.testConnection(request)
    }

    /**
     * Execute a query against a custom data source.
     */
    suspend fun executeQuery(
        sourceId: Long,
        sourceType: CustomDataSourceType,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
        query: String,
        limit: Int = 100,
        timeRange: TimeRangeDef? = null,
    ): List<Map<String, JsonElement>> {
        val handler = handlers[sourceType]
            ?: return emptyList()

        return handler.executeQuery(
            sourceId,
            host,
            port,
            databaseName,
            credentials,
            query,
            limit,
            timeRange,
        )
    }

    /**
     * Get schema info (tables/metrics) for a custom data source.
     */
    suspend fun getSchema(
        sourceType: CustomDataSourceType,
        host: String,
        port: Int?,
        databaseName: String?,
        credentials: DataSourceCredentials,
    ) = handlers[sourceType]?.getSchema(host, port, databaseName, credentials) ?: emptyList()

    /**
     * Execute a Grafana-style label_values() query (Prometheus, Loki, etc.).
     */
    suspend fun executeLabelValuesQuery(
        sourceType: CustomDataSourceType,
        host: String,
        port: Int?,
        credentials: DataSourceCredentials,
        query: String,
    ): List<String> {
        val handler = when (sourceType) {
            CustomDataSourceType.PROMETHEUS -> prometheusHandler
            CustomDataSourceType.LOKI -> lokiHandler
            else -> prometheusHandler
        }
        return handler.executeLabelValuesQuery(host, port, credentials, query)
    }

    fun closePool(sourceId: Long) {
        postgresHandler.closePool(sourceId)
    }

    fun closeAllPools() {
        jdbcPools.values.forEach { it.close() }
        jdbcPools.clear()
    }
}
