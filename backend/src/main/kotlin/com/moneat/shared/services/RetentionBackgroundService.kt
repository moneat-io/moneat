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
import com.moneat.shared.models.Projects
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MAX
import com.moneat.utils.HttpConstants.HTTP_SUCCESS_MIN
import com.moneat.utils.TimeConstants.MILLIS_PER_SECOND_LONG
import com.moneat.utils.suspendRunCatching
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

class RetentionBackgroundService(
    private val retentionPolicyService: RetentionPolicyService = RetentionPolicyService()
) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val config = ApplicationConfig("application.conf")
    private val enabled =
        config
            .propertyOrNull("retention.backgroundJobsEnabled")
            ?.getString()
            ?.toBooleanStrictOrNull() ?: true
    private val sweepIntervalSeconds =
        config
            .propertyOrNull("retention.sweepIntervalSeconds")
            ?.getString()
            ?.toLongOrNull() ?: 3600L
    private val idChunkSize =
        config
            .propertyOrNull("retention.idChunkSize")
            ?.getString()
            ?.toIntOrNull() ?: 500

    private var sweepJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (!enabled) {
            logger.info { "Retention background job is disabled by config" }
            return
        }

        sweepJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    suspendRunCatching {
                        runSweep()
                    }.onFailure { e ->
                        logger.error(e) { "Retention sweep failed" }
                    }
                    delay(sweepIntervalSeconds * MILLIS_PER_SECOND_LONG)
                }
            }
    }

    fun stop() {
        sweepJob?.cancel()
    }

    private suspend fun runSweep() {
        if (!ClickHouseClient.isInitialized()) {
            logger.debug { "Retention sweep skipped: ClickHouse is not initialized" }
            return
        }

        val retentionByOrg = retentionPolicyService.getRetentionDaysByOrganization()
        val logRetentionByOrg = retentionPolicyService.getLogRetentionDaysByOrganization()
        val replayRetentionByOrg = retentionPolicyService.getReplayRetentionDaysByOrganization()
        val llmRetentionByOrg = retentionPolicyService.getLlmRetentionDaysByOrganization()
        val apmTraceRetentionByOrg = retentionPolicyService.getApmTraceRetentionDaysByOrganization()
        val analyticsRetentionByOrg = retentionPolicyService.getAnalyticsRetentionDaysByOrganization()

        if (retentionByOrg.isEmpty()) {
            logger.debug { "Retention sweep skipped: no organizations found" }
            return
        }

        val projectsByOrg =
            transaction {
                Projects
                    .selectAll()
                    .groupBy { it[Projects.organization_id] }
                    .mapValues { (_, rows) -> rows.map { it[Projects.id] } }
            }

        var tableMutationCount = 0

        // Core retention (events, spans, sessions, user_feedback, issues)
        val groupedOrgIds = retentionByOrg.entries.groupBy({ it.value }, { it.key })
        for ((retentionDays, orgIds) in groupedOrgIds) {
            val projectIds = orgIds.flatMap { projectsByOrg[it].orEmpty() }
            tableMutationCount += submitCoreDeletes(projectIds, retentionDays)
            tableMutationCount += submitOrgScopedDeletes(orgIds, retentionDays)
        }

        // Replay retention (per-surface)
        val groupedReplayOrgIds = replayRetentionByOrg.entries.groupBy({ it.value }, { it.key })
        for ((replayRetentionDays, orgIds) in groupedReplayOrgIds) {
            val projectIds = orgIds.flatMap { projectsByOrg[it].orEmpty() }
            tableMutationCount += submitReplayDeletes(projectIds, replayRetentionDays)
        }

        // LLM retention (per-surface)
        val groupedLlmOrgIds = llmRetentionByOrg.entries.groupBy({ it.value }, { it.key })
        for ((llmRetentionDays, orgIds) in groupedLlmOrgIds) {
            val projectIds = orgIds.flatMap { projectsByOrg[it].orEmpty() }
            tableMutationCount += submitLlmDeletes(projectIds, llmRetentionDays)
        }

        // APM trace retention (per-surface, org-scoped)
        val groupedApmTraceOrgIds = apmTraceRetentionByOrg.entries.groupBy({ it.value }, { it.key })
        for ((apmTraceRetentionDays, orgIds) in groupedApmTraceOrgIds) {
            tableMutationCount += submitApmTraceDeletes(orgIds, apmTraceRetentionDays)
        }

        // Analytics retention (per-surface)
        val groupedAnalyticsOrgIds = analyticsRetentionByOrg.entries.groupBy({ it.value }, { it.key })
        for ((analyticsRetentionDays, orgIds) in groupedAnalyticsOrgIds) {
            val projectIds = orgIds.flatMap { projectsByOrg[it].orEmpty() }
            tableMutationCount += submitAnalyticsDeletes(projectIds, analyticsRetentionDays)
        }

        // Log retention
        val groupedLogOrgIds = logRetentionByOrg.entries.groupBy({ it.value }, { it.key })
        for ((logRetentionDays, orgIds) in groupedLogOrgIds) {
            val projectIds = orgIds.flatMap { projectsByOrg[it].orEmpty() }
            tableMutationCount += submitLogDeletes(projectIds, logRetentionDays)
        }

        logger.info {
            "Retention sweep submitted $tableMutationCount delete mutation(s) " +
                "across ${retentionByOrg.size} organization(s)"
        }
    }

    private suspend fun submitCoreDeletes(
        projectIds: List<Long>,
        retentionDays: Int
    ): Int {
        if (projectIds.isEmpty()) return 0
        val tables =
            listOf(
                "events" to "timestamp",
                "spans" to "start_timestamp",
                "sessions" to "started",
                "user_feedback" to "timestamp",
                "issues" to "last_seen"
            )
        return submitProjectScopedDeletes(projectIds, tables, retentionDays)
    }

    private suspend fun submitReplayDeletes(
        projectIds: List<Long>,
        replayRetentionDays: Int
    ): Int {
        if (projectIds.isEmpty()) return 0
        val tables = listOf(
            "replay_events" to "timestamp",
            "replay_segments" to "timestamp"
        )
        return submitProjectScopedDeletes(projectIds, tables, replayRetentionDays)
    }

    private suspend fun submitLlmDeletes(
        projectIds: List<Long>,
        llmRetentionDays: Int
    ): Int {
        if (projectIds.isEmpty()) return 0
        val tables = listOf(
            "llm_generations" to "timestamp",
            "llm_generations_hourly_mv" to "hour"
        )
        return submitProjectScopedDeletes(projectIds, tables, llmRetentionDays)
    }

    private suspend fun submitAnalyticsDeletes(
        projectIds: List<Long>,
        analyticsRetentionDays: Int
    ): Int {
        if (projectIds.isEmpty()) return 0
        val tables = listOf(
            "analytics_events" to "timestamp",
            "analytics_sessions_hourly" to "hour"
        )
        return submitProjectScopedDeletes(projectIds, tables, analyticsRetentionDays)
    }

    private suspend fun submitApmTraceDeletes(
        orgIds: List<Int>,
        apmTraceRetentionDays: Int
    ): Int {
        if (orgIds.isEmpty()) return 0
        val tables = listOf(
            "apm_spans" to "start",
            "trace_stats" to "start"
        )
        return submitOrgScopedDeletes(orgIds, tables, apmTraceRetentionDays, "apm-traces")
    }

    private suspend fun submitProjectScopedDeletes(
        projectIds: List<Long>,
        tables: List<Pair<String, String>>,
        retentionDays: Int
    ): Int {
        var mutations = 0
        for (chunk in projectIds.chunked(idChunkSize)) {
            val projectList = chunk.joinToString(",")
            for ((table, timeColumn) in tables) {
                val query =
                    """
                    ALTER TABLE `$clickhouseDb`.`$table`
                    DELETE WHERE project_id IN ($projectList)
                        AND $timeColumn < now() - INTERVAL $retentionDays DAY
                    """.trimIndent()
                if (submitMutation(query, "$table(project)")) {
                    mutations++
                }
            }
        }
        return mutations
    }

    private suspend fun submitOrgScopedDeletes(
        orgIds: List<Int>,
        retentionDays: Int
    ): Int {
        val tables =
            listOf(
                "metrics" to "timestamp",
                "containers" to "timestamp"
            )
        return submitOrgScopedDeletes(orgIds, tables, retentionDays, "org")
    }

    private suspend fun submitOrgScopedDeletes(
        orgIds: List<Int>,
        tables: List<Pair<String, String>>,
        retentionDays: Int,
        labelSuffix: String
    ): Int {
        if (orgIds.isEmpty()) return 0

        var mutations = 0
        for (chunk in orgIds.chunked(idChunkSize)) {
            val orgList = chunk.joinToString(",")
            for ((table, timeColumn) in tables) {
                val query =
                    """
                    ALTER TABLE `$clickhouseDb`.`$table`
                    DELETE WHERE organization_id IN ($orgList)
                        AND $timeColumn < now64(3) - INTERVAL $retentionDays DAY
                    """.trimIndent()
                if (submitMutation(query, "$table($labelSuffix)")) {
                    mutations++
                }
            }
        }
        return mutations
    }

    private suspend fun submitLogDeletes(
        projectIds: List<Long>,
        logRetentionDays: Int
    ): Int {
        if (projectIds.isEmpty()) return 0

        var mutations = 0
        for (chunk in projectIds.chunked(idChunkSize)) {
            val projectList = chunk.joinToString(",")
            val query =
                """
                ALTER TABLE `$clickhouseDb`.logs
                DELETE WHERE project_id IN ($projectList)
                    AND timestamp < now() - INTERVAL $logRetentionDays DAY
                """.trimIndent()
            if (submitMutation(query, "logs(project)")) {
                mutations++
            }
        }
        return mutations
    }

    private suspend fun submitMutation(
        query: String,
        label: String
    ): Boolean {
        if (!ClickHouseClient.isInitialized()) {
            logger.debug { "Retention mutation skipped for $label: ClickHouse is not initialized" }
            return false
        }
        return suspendRunCatching {
            val response = ClickHouseClient.execute(query)
            if (response.status.value !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                logger.error { "Retention mutation failed for $label (status=${response.status})" }
                false
            } else {
                true
            }
        }.getOrElse { e ->
            logger.error(e) { "Retention mutation exception for $label" }
            false
        }
    }
}
