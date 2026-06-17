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

package com.moneat.dashboards.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

// Exposed table definition

object CustomDataSources : Table("custom_data_sources") {
    val id = long("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val orgId = long("org_id")
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val sourceType = varchar("source_type", 50)
    val host = varchar("host", 512)
    val port = integer("port").nullable()
    val databaseName = varchar("database_name", 255).nullable()
    val encryptedCredentials = text("encrypted_credentials")
    val extraConfig = jsonb("extra_config")
    val enabled = bool("enabled").default(true)
    val createdBy = long("created_by")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// API data classes

@Serializable
enum class CustomDataSourceType {
    @SerialName("postgresql")
    POSTGRESQL,

    @SerialName("prometheus")
    PROMETHEUS,

    @SerialName("mysql")
    MYSQL,

    @SerialName("mariadb")
    MARIADB,

    @SerialName("mssql")
    MSSQL,

    @SerialName("clickhouse")
    CLICKHOUSE,

    @SerialName("sqlite")
    SQLITE,

    @SerialName("cockroachdb")
    COCKROACHDB,

    @SerialName("bigquery")
    BIGQUERY,

    @SerialName("snowflake")
    SNOWFLAKE,

    @SerialName("influxdb")
    INFLUXDB,

    @SerialName("elasticsearch")
    ELASTICSEARCH,

    @SerialName("graphite")
    GRAPHITE,

    @SerialName("loki")
    LOKI,

    @SerialName("cloudwatch")
    CLOUDWATCH,

    @SerialName("mongodb")
    MONGODB,

    @SerialName("redis")
    REDIS;

    companion object {
        fun fromString(value: String): CustomDataSourceType? =
            entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

@Serializable
data class CustomDataSourceResponse(
    val id: String,
    @SerialName("org_id") val orgId: String,
    val name: String,
    val description: String? = null,
    @SerialName("source_type") val sourceType: String,
    val host: String,
    val port: Int? = null,
    @SerialName("database_name") val databaseName: String? = null,
    @SerialName("extra_config") val extraConfig: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("has_credentials") val hasCredentials: Boolean = true,
    @SerialName("used_by_dashboard_count") val usedByDashboardCount: Int = 0,
    @Transient val numericId: Long = 0,
    // Credentials are NEVER returned
)

@Serializable
data class CreateCustomDataSourceRequest(
    val name: String,
    val description: String? = null,
    @SerialName("source_type") val sourceType: String,
    val host: String,
    val port: Int? = null,
    @SerialName("database_name") val databaseName: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("header_value") val headerValue: String? = null,
    @SerialName("extra_config") val extraConfig: Map<String, String> = emptyMap(),
    @SerialName("access_key_id") val accessKeyId: String? = null,
    @SerialName("secret_access_key") val secretAccessKey: String? = null,
    @SerialName("service_account_json") val serviceAccountJson: String? = null,
    @SerialName("account_identifier") val accountIdentifier: String? = null,
    @SerialName("connection_string") val connectionString: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val region: String? = null,
)

@Serializable
data class UpdateCustomDataSourceRequest(
    val name: String? = null,
    val description: String? = null,
    val host: String? = null,
    val port: Int? = null,
    // A null `port` means "leave unchanged" (partial update); this flag is the explicit
    // opt-in to erase a stored port, e.g. an http source moved behind a reverse proxy.
    @SerialName("clear_port") val clearPort: Boolean = false,
    @SerialName("database_name") val databaseName: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("header_value") val headerValue: String? = null,
    @SerialName("extra_config") val extraConfig: Map<String, String>? = null,
    val enabled: Boolean? = null,
    @SerialName("access_key_id") val accessKeyId: String? = null,
    @SerialName("secret_access_key") val secretAccessKey: String? = null,
    @SerialName("service_account_json") val serviceAccountJson: String? = null,
    @SerialName("account_identifier") val accountIdentifier: String? = null,
    @SerialName("connection_string") val connectionString: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val region: String? = null,
)

@Serializable
data class TestConnectionRequest(
    @SerialName("source_type") val sourceType: String,
    val host: String,
    val port: Int? = null,
    @SerialName("database_name") val databaseName: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("api_key") val apiKey: String? = null,
    @SerialName("header_value") val headerValue: String? = null,
    @SerialName("access_key_id") val accessKeyId: String? = null,
    @SerialName("secret_access_key") val secretAccessKey: String? = null,
    @SerialName("service_account_json") val serviceAccountJson: String? = null,
    @SerialName("account_identifier") val accountIdentifier: String? = null,
    @SerialName("connection_string") val connectionString: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val region: String? = null,
    @SerialName("extra_config") val extraConfig: Map<String, String> = emptyMap(),
)

@Serializable
data class TestConnectionResult(
    val success: Boolean,
    val message: String,
    val tables: List<String>? = null, // For JDBC: available tables
    val metrics: List<String>? = null, // For Prometheus: sample metric names
    val databases: List<String>? = null, // For MongoDB: available databases
    val keys: List<String>? = null, // For Redis: sample keys
)

@Serializable
data class CustomDataSourceQueryRequest(
    @SerialName("data_source_id") val dataSourceId: String,
    val query: String,
    val limit: Int = 100,
    @SerialName("time_range") val timeRange: TimeRangeDef? = null,
)
