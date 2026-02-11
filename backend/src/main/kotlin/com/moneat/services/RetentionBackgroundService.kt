package com.moneat.services

import com.moneat.config.ClickHouseClient
import com.moneat.models.Projects
import io.ktor.server.config.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = KotlinLogging.logger {}

class RetentionBackgroundService(
    private val retentionPolicyService: RetentionPolicyService = RetentionPolicyService()
) {
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()
    private val config = ApplicationConfig("application.conf")
    private val enabled = config.propertyOrNull("retention.backgroundJobsEnabled")
        ?.getString()
        ?.toBooleanStrictOrNull() ?: true
    private val sweepIntervalSeconds = config.propertyOrNull("retention.sweepIntervalSeconds")
        ?.getString()
        ?.toLongOrNull() ?: 3600L
    private val idChunkSize = config.propertyOrNull("retention.idChunkSize")
        ?.getString()
        ?.toIntOrNull() ?: 500

    private var sweepJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (!enabled) {
            logger.info { "Retention background job is disabled by config" }
            return
        }

        sweepJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    runSweep()
                } catch (e: Exception) {
                    logger.error(e) { "Retention sweep failed" }
                }
                delay(sweepIntervalSeconds * 1000L)
            }
        }
    }

    fun stop() {
        sweepJob?.cancel()
    }

    private suspend fun runSweep() {
        val retentionByOrg = retentionPolicyService.getRetentionDaysByOrganization()
        val logRetentionByOrg = retentionPolicyService.getLogRetentionDaysByOrganization()
        
        if (retentionByOrg.isEmpty()) {
            logger.debug { "Retention sweep skipped: no organizations found" }
            return
        }

        val projectsByOrg = transaction {
            Projects.selectAll()
                .groupBy { it[Projects.organization_id] }
                .mapValues { (_, rows) -> rows.map { it[Projects.id] } }
        }

        var tableMutationCount = 0
        var orgGroupCount = 0
        
        // Process event retention
        val groupedOrgIds = retentionByOrg.entries.groupBy({ it.value }, { it.key })
        for ((retentionDays, orgIds) in groupedOrgIds) {
            orgGroupCount++
            val projectIds = orgIds.flatMap { projectsByOrg[it].orEmpty() }

            tableMutationCount += submitProjectScopedDeletes(projectIds, retentionDays)
            tableMutationCount += submitOrgScopedDeletes(orgIds, retentionDays)
        }

        // Process log retention separately
        val groupedLogOrgIds = logRetentionByOrg.entries.groupBy({ it.value }, { it.key })
        for ((logRetentionDays, orgIds) in groupedLogOrgIds) {
            val projectIds = orgIds.flatMap { projectsByOrg[it].orEmpty() }
            tableMutationCount += submitLogDeletes(projectIds, logRetentionDays)
        }

        logger.info {
            "Retention sweep submitted $tableMutationCount delete mutation(s) " +
                "across ${retentionByOrg.size} organization(s) in $orgGroupCount retention group(s)"
        }
    }

    private suspend fun submitProjectScopedDeletes(projectIds: List<Long>, retentionDays: Int): Int {
        if (projectIds.isEmpty()) return 0

        val tables = listOf(
            "events" to "timestamp",
            "spans" to "start_timestamp",
            "sessions" to "started",
            "replay_events" to "timestamp",
            "replay_segments" to "timestamp",
            "user_feedback" to "timestamp",
            "issues" to "last_seen"
        )

        var mutations = 0
        for (chunk in projectIds.chunked(idChunkSize)) {
            val projectList = chunk.joinToString(",")
            for ((table, timeColumn) in tables) {
                val query = """
                    ALTER TABLE $clickhouseDb.$table
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

    private suspend fun submitOrgScopedDeletes(orgIds: List<Int>, retentionDays: Int): Int {
        if (orgIds.isEmpty()) return 0

        val tables = listOf(
            "system_metrics" to "timestamp",
            "container_metrics" to "timestamp"
        )

        var mutations = 0
        for (chunk in orgIds.chunked(idChunkSize)) {
            val orgList = chunk.joinToString(",")
            for ((table, timeColumn) in tables) {
                val query = """
                    ALTER TABLE $clickhouseDb.$table
                    DELETE WHERE org_id IN ($orgList)
                        AND $timeColumn < now() - INTERVAL $retentionDays DAY
                """.trimIndent()
                if (submitMutation(query, "$table(org)")) {
                    mutations++
                }
            }
        }
        return mutations
    }

    private suspend fun submitLogDeletes(projectIds: List<Long>, logRetentionDays: Int): Int {
        if (projectIds.isEmpty()) return 0

        var mutations = 0
        for (chunk in projectIds.chunked(idChunkSize)) {
            val projectList = chunk.joinToString(",")
            val query = """
                ALTER TABLE $clickhouseDb.logs
                DELETE WHERE project_id IN ($projectList)
                    AND timestamp < now() - INTERVAL $logRetentionDays DAY
            """.trimIndent()
            if (submitMutation(query, "logs(project)")) {
                mutations++
            }
        }
        return mutations
    }

    private suspend fun submitMutation(query: String, label: String): Boolean {
        return try {
            val response = ClickHouseClient.execute(query)
            if (response.status.value !in 200..299) {
                logger.error { "Retention mutation failed for $label (status=${response.status})" }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            logger.error(e) { "Retention mutation exception for $label" }
            false
        }
    }
}
