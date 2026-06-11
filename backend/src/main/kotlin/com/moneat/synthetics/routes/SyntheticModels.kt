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

package com.moneat.synthetics.routes

import com.moneat.shared.models.Organizations
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import java.util.UUID
import kotlin.uuid.Uuid

const val RETRY_COUNT_DEFAULT = 0
const val RETRY_INTERVAL_MS_DEFAULT = 300
const val ALERT_ON_FAILURE_DEFAULT = false

// Exposed Table Definition
object SyntheticTests : Table("synthetic_tests") {
    val id = javaUUID("id")
    val organizationId = integer("organization_id").references(Organizations.id)
    val name = varchar("name", 255)
    val testType = varchar("test_type", 20).default("api")
    val active = bool("active").default(true)
    val intervalSeconds = integer("interval_seconds").default(300)
    val timeoutSeconds = integer("timeout_seconds").default(30)
    val url = text("url").nullable()
    val method = varchar("method", 10).default("GET")
    val headers = text("headers").nullable()
    val body = text("body").nullable()
    val authMethod = varchar("auth_method", 20).nullable()
    val authUser = varchar("auth_user", 255).nullable()
    val authPass = varchar("auth_pass", 255).nullable()
    val assertions = text("assertions").default("[]")
    val steps = text("steps").nullable()
    val status = varchar("status", 20).default("pending")
    val lastRunAt = timestamp("last_run_at").nullable()
    val lastStatus = varchar("last_status", 20).nullable()
    val tags = text("tags").default("[]")
    val retryCount = integer("retry_count").default(RETRY_COUNT_DEFAULT)
    val retryIntervalMs = integer("retry_interval_ms").default(RETRY_INTERVAL_MS_DEFAULT)
    val alertOnFailure = bool("alert_on_failure").default(ALERT_ON_FAILURE_DEFAULT)
    val alertChannels = text("alert_channels").default("[]")
    val config = text("config").nullable()
    val service = varchar("service", 255).nullable()
    val environment = varchar("environment", 255).nullable()
    val locations = text("locations").default("[]")
    val alertConfig = text("alert_config").nullable()
    val alertRecipients = text("alert_recipients").default("[]")
    val browserSteps = text("browser_steps").nullable()
    val previousStatus = varchar("previous_status", 20).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** Managed (platform-global, org NULL) + private (per-org) probe locations. */
object SyntheticLocations : Table("synthetic_locations") {
    val id = javaUUID("id")
    val organizationId = integer("organization_id").references(Organizations.id).nullable()
    val code = varchar("code", 64)
    val name = varchar("name", 128)
    val region = varchar("region", 128).default("")
    val locationType = varchar("location_type", 16).default("managed")
    val active = bool("active").default(true)
    val keyHash = varchar("key_hash", 255).nullable()
    val workerCount = integer("worker_count").default(0)
    val lastSeenAt = timestamp("last_seen_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object SyntheticVariables : Table("synthetic_variables") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id)
    val name = varchar("name", 255)
    val value = text("value").default("")
    val isSecret = bool("is_secret").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class SyntheticAssertion(
    val type: String,
    val target: String = "",
    val operator: String = "equals",
    val value: String = ""
)

@Serializable
data class VariableExtraction(
    val name: String,
    val source: String,
    val path: String = ""
)

@Serializable
data class SyntheticStep(
    val name: String = "",
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val assertions: List<SyntheticAssertion> = emptyList(),
    val extractVariables: List<VariableExtraction> = emptyList()
)

@Serializable
data class SyntheticTestConfig(
    val dnsServer: String? = null,
    val port: Int? = null,
    val protocol: String? = null,
    val hostname: String? = null
)

/** Structured alert trigger condition; reduces flapping from a single noisy location. */
@Serializable
data class AlertConfig(
    val consecutiveChecks: Int = 1,
    val minLocations: Int = 1,
    val totalLocations: Int = 1,
    val retestCount: Int = 0,
    val renotifyMinutes: Int? = null,
    val notifyOnRecovery: Boolean = true,
    val slowResponseMs: Int? = null,
    val slowResponseWindowMin: Int = 10
)

/** A single alert destination: slack / email / pagerduty / webhook. */
@Serializable
data class AlertRecipient(
    val type: String,
    val target: String,
    val label: String = ""
)

/** One recorded browser-journey step (navigate / click / type / assert / wait). */
@Serializable
data class BrowserStep(
    val action: String,
    val label: String = "",
    val selector: String = "",
    val value: String = "",
    val assertType: String = ""
)

@Serializable
data class CreateSyntheticTestRequest(
    val name: String,
    val testType: String = "api",
    val intervalSeconds: Int = 300,
    val timeoutSeconds: Int = 30,
    val url: String? = null,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val assertions: List<SyntheticAssertion> = emptyList(),
    val steps: List<SyntheticStep> = emptyList(),
    val tags: List<String> = emptyList(),
    val retryCount: Int = RETRY_COUNT_DEFAULT,
    val retryIntervalMs: Int = RETRY_INTERVAL_MS_DEFAULT,
    val alertOnFailure: Boolean = ALERT_ON_FAILURE_DEFAULT,
    val alertChannels: List<String> = emptyList(),
    val config: SyntheticTestConfig? = null,
    val service: String? = null,
    val environment: String? = null,
    val locations: List<String> = emptyList(),
    val alertConfig: AlertConfig? = null,
    val alertRecipients: List<AlertRecipient> = emptyList(),
    val browserSteps: List<BrowserStep> = emptyList()
)

@Serializable
data class UpdateSyntheticTestRequest(
    val name: String? = null,
    val active: Boolean? = null,
    val intervalSeconds: Int? = null,
    val timeoutSeconds: Int? = null,
    val url: String? = null,
    val method: String? = null,
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val assertions: List<SyntheticAssertion>? = null,
    val steps: List<SyntheticStep>? = null,
    val tags: List<String>? = null,
    val retryCount: Int? = null,
    val retryIntervalMs: Int? = null,
    val alertOnFailure: Boolean? = null,
    val alertChannels: List<String>? = null,
    val config: SyntheticTestConfig? = null,
    val service: String? = null,
    val environment: String? = null,
    val locations: List<String>? = null,
    val alertConfig: AlertConfig? = null,
    val alertRecipients: List<AlertRecipient>? = null,
    val browserSteps: List<BrowserStep>? = null
)

@Serializable
data class SyntheticTestResponse(
    val id: String,
    @Transient val organizationId: Int = 0,
    val name: String,
    val testType: String,
    val active: Boolean,
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val url: String? = null,
    val method: String,
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val assertions: List<SyntheticAssertion>,
    val steps: List<SyntheticStep>,
    val status: String,
    val lastRunAt: Long? = null,
    val lastStatus: String? = null,
    val tags: List<String> = emptyList(),
    val retryCount: Int = RETRY_COUNT_DEFAULT,
    val retryIntervalMs: Int = RETRY_INTERVAL_MS_DEFAULT,
    val alertOnFailure: Boolean = ALERT_ON_FAILURE_DEFAULT,
    val alertChannels: List<String> = emptyList(),
    val config: SyntheticTestConfig? = null,
    val service: String? = null,
    val environment: String? = null,
    val locations: List<String> = emptyList(),
    val alertConfig: AlertConfig? = null,
    val alertRecipients: List<AlertRecipient> = emptyList(),
    val browserSteps: List<BrowserStep> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

data class SyntheticTestData(
    val id: UUID,
    val organizationId: Int,
    val name: String,
    val testType: String,
    val active: Boolean,
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val url: String? = null,
    val method: String,
    val headers: String? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val assertions: String,
    val steps: String? = null,
    val status: String,
    val lastRunAt: kotlin.time.Instant? = null,
    val lastStatus: String? = null,
    val tags: List<String> = emptyList(),
    val retryCount: Int = RETRY_COUNT_DEFAULT,
    val retryIntervalMs: Int = RETRY_INTERVAL_MS_DEFAULT,
    val alertOnFailure: Boolean = ALERT_ON_FAILURE_DEFAULT,
    val alertChannels: List<String> = emptyList(),
    val config: String? = null,
    val service: String? = null,
    val environment: String? = null,
    val locations: List<String> = emptyList(),
    val alertConfig: AlertConfig? = null,
    val alertRecipients: List<AlertRecipient> = emptyList(),
    val browserSteps: String? = null,
    val previousStatus: String? = null,
    val createdAt: kotlin.time.Instant,
    val updatedAt: kotlin.time.Instant
)

@Serializable
data class SyntheticTestSummary(
    val testId: String,
    val uptimePercent: Double,
    val avgResponseMs: Double,
    val p95ResponseMs: Double,
    val totalRuns: Long,
    val failureCount: Long
)

@Serializable
data class SyntheticVariableRequest(
    val name: String,
    val value: String,
    val isSecret: Boolean = false
)

@Serializable
data class SyntheticVariableResponse(
    val id: String,
    @Transient val organizationId: Int = 0,
    val name: String,
    val value: String,
    val isSecret: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
