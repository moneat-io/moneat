// Moneat - Mobile-First Error Monitoring Platform
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

package com.moneat.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.util.UUID

// Exposed Table Definition
object UptimeMonitors : Table("uptime_monitors") {
    val id = uuid("id")
    val organizationId = integer("organization_id").references(Organizations.id)
    val name = varchar("name", 255)
    val type = varchar("type", 50)
    val active = bool("active").default(true)
    
    // Connection target
    val url = text("url").nullable()
    val hostname = varchar("hostname", 255).nullable()
    val port = integer("port").nullable()
    
    // HTTP options
    val method = varchar("method", 10).default("GET")
    val headers = text("headers").nullable() // JSON stored as text
    val body = text("body").nullable()
    val authMethod = varchar("auth_method", 20).nullable()
    val authUser = varchar("auth_user", 255).nullable()
    val authPass = varchar("auth_pass", 255).nullable()
    val expectedStatusCodes = text("expected_status_codes").nullable()
    val maxRedirects = integer("max_redirects").default(10)
    val ignoreTls = bool("ignore_tls").default(false)
    
    // Keyword monitor
    val keyword = varchar("keyword", 500).nullable()
    val keywordInverse = bool("keyword_inverse").default(false)
    
    // JSON Query monitor
    val jsonPath = varchar("json_path", 500).nullable()
    val jsonExpectedValue = text("json_expected_value").nullable()
    
    // DNS options
    val dnsRecordType = varchar("dns_record_type", 10).nullable()
    val dnsExpectedValue = text("dns_expected_value").nullable()
    val dnsServer = varchar("dns_server", 255).nullable()
    
    // SSL options
    val sslExpiryWarnDays = integer("ssl_expiry_warn_days").default(30)
    
    // Database options
    val dbConnectionString = text("db_connection_string").nullable()
    val dbQuery = text("db_query").nullable()
    
    // Docker options
    val dockerContainerName = varchar("docker_container_name", 255).nullable()
    val dockerHost = varchar("docker_host", 255).nullable()
    
    // Check config
    val intervalSeconds = integer("interval_seconds").default(60)
    val timeoutSeconds = integer("timeout_seconds").default(30)
    val retries = integer("retries").default(0)
    val retryIntervalSeconds = integer("retry_interval_seconds").default(60)
    
    // Status tracking
    val status = varchar("status", 20).default("pending")
    val lastCheckAt = timestamp("last_check_at").nullable()
    val lastStatusChangeAt = timestamp("last_status_change_at").nullable()
    val consecutiveFailures = integer("consecutive_failures").default(0)
    
    // Push monitor token
    val pushToken = varchar("push_token", 64).nullable()
    
    // Incident severity override
    val incidentSeverity = varchar("incident_severity", 20).nullable()
    
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    
    override val primaryKey = PrimaryKey(id)
}

// Request/Response Models

@Serializable
data class CreateUptimeMonitorRequest(
    val name: String,
    val type: String,
    
    // Connection
    val url: String? = null,
    val hostname: String? = null,
    val port: Int? = null,
    
    // HTTP
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val expectedStatusCodes: String? = null,
    val maxRedirects: Int = 10,
    val ignoreTls: Boolean = false,
    
    // Keyword
    val keyword: String? = null,
    val keywordInverse: Boolean = false,
    
    // JSON Query
    val jsonPath: String? = null,
    val jsonExpectedValue: String? = null,
    
    // DNS
    val dnsRecordType: String? = null,
    val dnsExpectedValue: String? = null,
    val dnsServer: String? = null,
    
    // SSL
    val sslExpiryWarnDays: Int = 30,
    
    // Database
    val dbConnectionString: String? = null,
    val dbQuery: String? = null,
    
    // Docker
    val dockerContainerName: String? = null,
    val dockerHost: String? = null,
    
    // Check config
    val intervalSeconds: Int = 60,
    val timeoutSeconds: Int = 30,
    val retries: Int = 0,
    val retryIntervalSeconds: Int = 60
)

@Serializable
data class UpdateUptimeMonitorRequest(
    val name: String? = null,
    val active: Boolean? = null,
    
    // Connection
    val url: String? = null,
    val hostname: String? = null,
    val port: Int? = null,
    
    // HTTP
    val method: String? = null,
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val expectedStatusCodes: String? = null,
    val maxRedirects: Int? = null,
    val ignoreTls: Boolean? = null,
    
    // Keyword
    val keyword: String? = null,
    val keywordInverse: Boolean? = null,
    
    // JSON Query
    val jsonPath: String? = null,
    val jsonExpectedValue: String? = null,
    
    // DNS
    val dnsRecordType: String? = null,
    val dnsExpectedValue: String? = null,
    val dnsServer: String? = null,
    
    // SSL
    val sslExpiryWarnDays: Int? = null,
    
    // Database
    val dbConnectionString: String? = null,
    val dbQuery: String? = null,
    
    // Docker
    val dockerContainerName: String? = null,
    val dockerHost: String? = null,
    
    // Check config
    val intervalSeconds: Int? = null,
    val timeoutSeconds: Int? = null,
    val retries: Int? = null,
    val retryIntervalSeconds: Int? = null
)

@Serializable
data class UptimeMonitorResponse(
    val id: String,
    val organizationId: Int,
    val name: String,
    val type: String,
    val active: Boolean,
    
    // Connection
    val url: String? = null,
    val hostname: String? = null,
    val port: Int? = null,
    
    // HTTP
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val expectedStatusCodes: String? = null,
    val maxRedirects: Int = 10,
    val ignoreTls: Boolean = false,
    
    // Keyword
    val keyword: String? = null,
    val keywordInverse: Boolean = false,
    
    // JSON Query
    val jsonPath: String? = null,
    val jsonExpectedValue: String? = null,
    
    // DNS
    val dnsRecordType: String? = null,
    val dnsExpectedValue: String? = null,
    val dnsServer: String? = null,
    
    // SSL
    val sslExpiryWarnDays: Int = 30,
    
    // Database
    val dbConnectionString: String? = null,
    val dbQuery: String? = null,
    
    // Docker
    val dockerContainerName: String? = null,
    val dockerHost: String? = null,
    
    // Check config
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val retries: Int,
    val retryIntervalSeconds: Int,
    
    // Status
    val status: String,
    val lastCheckAt: Long? = null,
    val lastStatusChangeAt: Long? = null,
    val consecutiveFailures: Int = 0,
    
    // Push token (only for push monitors)
    val pushToken: String? = null,
    
    // Stats
    val uptime24h: Float? = null,
    val uptime7d: Float? = null,
    val uptime30d: Float? = null,
    val avgResponseTime: Int? = null,
    
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class UptimeHeartbeatResponse(
    val timestamp: Long,
    val status: Int,
    val responseTimeMs: Int,
    val statusCode: Int,
    val message: String,
    val pingMs: Float? = null
)

@Serializable
data class UptimeSummaryResponse(
    val uptime24h: Float,
    val uptime7d: Float,
    val uptime30d: Float,
    val avgResponseTime24h: Int,
    val avgResponseTime7d: Int,
    val avgResponseTime30d: Int,
    val totalChecks: Long,
    val recentHeartbeats: List<UptimeHeartbeatResponse>
)

// Internal data class for check results
data class CheckResult(
    val status: Int, // 1=up, 0=down, 2=pending
    val responseTimeMs: Int,
    val statusCode: Int = 0,
    val message: String = "",
    val pingMs: Float = -1f
)

// Monitor data class for internal use
data class UptimeMonitorData(
    val id: UUID,
    val organizationId: Int,
    val name: String,
    val type: String,
    val active: Boolean,
    
    // Connection
    val url: String? = null,
    val hostname: String? = null,
    val port: Int? = null,
    
    // HTTP
    val method: String = "GET",
    val headers: String? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val expectedStatusCodes: String? = null,
    val maxRedirects: Int = 10,
    val ignoreTls: Boolean = false,
    
    // Keyword
    val keyword: String? = null,
    val keywordInverse: Boolean = false,
    
    // JSON Query
    val jsonPath: String? = null,
    val jsonExpectedValue: String? = null,
    
    // DNS
    val dnsRecordType: String? = null,
    val dnsExpectedValue: String? = null,
    val dnsServer: String? = null,
    
    // SSL
    val sslExpiryWarnDays: Int = 30,
    
    // Database
    val dbConnectionString: String? = null,
    val dbQuery: String? = null,
    
    // Docker
    val dockerContainerName: String? = null,
    val dockerHost: String? = null,
    
    // Check config
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val retries: Int,
    val retryIntervalSeconds: Int,
    
    // Status
    val status: String,
    val lastCheckAt: kotlinx.datetime.Instant? = null,
    val lastStatusChangeAt: kotlinx.datetime.Instant? = null,
    val consecutiveFailures: Int = 0,
    
    val pushToken: String? = null,
    val incidentSeverity: String? = null,
    val createdAt: kotlinx.datetime.Instant,
    val updatedAt: kotlinx.datetime.Instant
)
