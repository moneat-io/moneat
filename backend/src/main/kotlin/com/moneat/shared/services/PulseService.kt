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

package com.moneat.shared.services

import com.moneat.config.ClickHouseClient
import com.moneat.config.EnvConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import com.moneat.utils.suspendRunCatching
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.management.ManagementFactory
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/**
 * PulseService collects anonymous, aggregated telemetry from self-hosted Moneat
 * deployments. This helps us understand how Moneat is used in the wild so we can
 * prioritise features, resource requirements, and compatibility.
 *
 * - Only active when SELF_HOSTED=true and TELEMETRY_ENABLED=true (the default).
 * - No personally-identifiable information is ever collected.
 * - Set TELEMETRY_ENABLED=false to disable.
 *
 * @see <a href="https://moneat.io/docs/self-hosting/telemetry">Telemetry docs</a>
 */
class PulseService(
    private val interval: Duration = 4.hours,
    private val endpoint: String =
        EnvConfig.get("TELEMETRY_ENDPOINT", "https://telemetry.moneat.io/v1/pulse")
) {
    private var pulseJob: Job? = null
    private val json = Json { encodeDefaults = true }

    private val httpClient by lazy {
        HttpClient(CIO) {
            engine {
                requestTimeout = 30_000
            }
        }
    }

    fun start(scope: CoroutineScope) {
        logger.info("Starting telemetry pulse service (interval=$interval)")

        pulseJob = scope.launch {
            // Delay the first pulse to let the application fully initialise
            delay(PULSE_INTERVAL_MS)

            while (true) {
                suspendRunCatching {
                    val metrics = collectMetrics()
                    sendPulse(metrics)
                }.onFailure { e ->
                    logger.debug(e) { "Telemetry pulse failed — will retry next interval" }
                }
                delay(interval)
            }
        }
    }

    fun stop() {
        logger.info { "Stopping telemetry pulse service" }
        pulseJob?.cancel()
        httpClient.close()
    }

    internal suspend fun collectMetrics(): PulsePayload {
        val runtime = Runtime.getRuntime()
        val osBean = ManagementFactory.getOperatingSystemMXBean()

        val usageCounts = collectUsageCounts()

        return PulsePayload(
            deploymentId = getOrCreateDeploymentId(),
            // System
            cpuCount = runtime.availableProcessors(),
            memTotalBytes = runtime.maxMemory(),
            memUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            osName = osBean.name,
            osArch = osBean.arch,
            jvmVersion = System.getProperty("java.version") ?: "unknown",
            // Usage
            projectCount = usageCounts.projectCount,
            userCount = usageCounts.userCount,
            eventCount = usageCounts.eventCount,
            issueCount = usageCounts.issueCount,
            // Deployment
            selfHost = true,
            sslEnabled = detectSsl()
        )
    }

    private suspend fun sendPulse(payload: PulsePayload) {
        val body = json.encodeToString(PulsePayload.serializer(), payload)

        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        if (response.status.isSuccess()) {
            logger.debug { "Telemetry pulse sent successfully" }
        } else {
            logger.debug { "Telemetry pulse returned ${response.status}" }
        }
    }

    private suspend fun collectUsageCounts(): UsageCounts {
        // PostgreSQL counts
        val (projectCount, userCount) = suspendRunCatching {
            transaction {
                val projects = exec("SELECT COUNT(*) FROM projects") { rs: java.sql.ResultSet ->
                    if (rs.next()) rs.getLong(1) else 0L
                } ?: 0L

                val users = exec("SELECT COUNT(*) FROM users") { rs: java.sql.ResultSet ->
                    if (rs.next()) rs.getLong(1) else 0L
                } ?: 0L

                Pair(projects, users)
            }
        }.getOrElse { e ->
            logger.debug(e) { "Failed to collect PostgreSQL counts" }
            Pair(0L, 0L)
        }

        // ClickHouse counts are best-effort; failures don't block the pulse
        val eventCount = suspendRunCatching {
            ClickHouseClient.execute("SELECT COUNT(*) FROM events")
                .bodyAsText().trim().toLongOrNull() ?: 0L
        }.getOrElse { _ ->
            0L
        }

        val issueCount = suspendRunCatching {
            ClickHouseClient.execute("SELECT COUNT(*) FROM issues")
                .bodyAsText().trim().toLongOrNull() ?: 0L
        }.getOrElse { _ ->
            0L
        }

        return UsageCounts(
            projectCount = projectCount,
            userCount = userCount,
            eventCount = eventCount,
            issueCount = issueCount
        )
    }

    private fun getOrCreateDeploymentId(): String {
        return suspendRunCatching {
            transaction {
                val existing = exec(
                    "SELECT value FROM deployment_settings WHERE key = 'deployment_id'"
                ) { rs: java.sql.ResultSet ->
                    if (rs.next()) rs.getString(1) else null
                }

                if (existing != null) {
                    existing
                } else {
                    val newId = UUID.randomUUID().toString()
                    exec(
                        "INSERT INTO deployment_settings (key, value) " +
                            "VALUES ('deployment_id', '$newId') ON CONFLICT (key) DO NOTHING"
                    )
                    newId
                }
            }
        }.getOrElse { _ ->
            // Table may not exist yet — generate a transient ID
            "transient-${UUID.randomUUID()}"
        }
    }

    private fun detectSsl(): Boolean {
        val backendUrl = EnvConfig.get("BACKEND_URL", "")
        val frontendUrl = EnvConfig.get("FRONTEND_URL", "")
        return backendUrl.startsWith("https://") || frontendUrl.startsWith("https://")
    }

    companion object {
        private const val PULSE_INTERVAL_MS = 60_000L

        fun isEnabled(): Boolean {
            val selfHost = EnvConfig.SelfHost.enabled
            val telemetryEnabled = EnvConfig.get("TELEMETRY_ENABLED", "true").toBoolean()
            return selfHost && telemetryEnabled
        }

        /**
         * Returns the current telemetry configuration and a live snapshot of collected metrics.
         * Used by the admin dashboard to display telemetry status.
         */
        suspend fun getStatus(): PulseStatus {
            val enabled = isEnabled()
            val selfHost = EnvConfig.SelfHost.enabled
            val telemetryEnabled = EnvConfig.get("TELEMETRY_ENABLED", "true").toBoolean()
            val endpoint = EnvConfig.get("TELEMETRY_ENDPOINT", "https://telemetry.moneat.io/v1/pulse")

            val snapshot = if (enabled) {
                suspendRunCatching {
                    PulseService().collectMetrics()
                }.getOrElse { _ ->
                    null
                }
            } else {
                null
            }

            return PulseStatus(
                enabled = enabled,
                selfHostMode = selfHost,
                telemetryConfigEnabled = telemetryEnabled,
                endpoint = endpoint,
                metrics = snapshot
            )
        }
    }
}

@Serializable
data class PulsePayload(
    val deploymentId: String,
    // System metrics
    val cpuCount: Int = 0,
    val memTotalBytes: Long = 0,
    val memUsedBytes: Long = 0,
    val osName: String = "",
    val osArch: String = "",
    val jvmVersion: String = "",
    // Usage counts
    val projectCount: Long = 0,
    val userCount: Long = 0,
    val eventCount: Long = 0,
    val issueCount: Long = 0,
    // Deployment info
    val selfHost: Boolean = true,
    val sslEnabled: Boolean = false
)

private data class UsageCounts(
    val projectCount: Long = 0,
    val userCount: Long = 0,
    val eventCount: Long = 0,
    val issueCount: Long = 0
)

@Serializable
data class PulseStatus(
    val enabled: Boolean,
    val selfHostMode: Boolean,
    val telemetryConfigEnabled: Boolean,
    val endpoint: String,
    val metrics: PulsePayload?
)
