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

package com.moneat.workflows.services

import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowAuditEventResponse
import com.moneat.workflows.models.WorkflowExportResponse
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowImportRequest
import com.moneat.workflows.models.WorkflowOverviewResponse
import com.moneat.workflows.models.WorkflowOverviewTopWorkflow
import com.moneat.workflows.models.WorkflowResponse
import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowUsageResponse
import com.moneat.workflows.models.Workflows
import com.moneat.workflows.models.workflowJson
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Read/operate facade for workflow governance: blueprint instantiation, the
 * overview summary, usage summary, and export/import. Heavy persistence is
 * delegated to [WorkflowService]; this service only orchestrates and records
 * audit events.
 */
class WorkflowGovernanceService(
    private val workflowService: WorkflowService
) {
    private val json = workflowJson

    fun instantiateBlueprint(
        organizationId: Int,
        key: String,
        name: String?,
        actorUserId: Int?
    ): WorkflowResponse {
        val blueprint = WorkflowBlueprintCatalog.get(key)
            ?: throw IllegalArgumentException("Unknown workflow blueprint $key")
        val workflow =
            workflowService.createWorkflow(
                organizationId,
                CreateWorkflowRequest(
                    name = name?.takeIf { it.isNotBlank() } ?: blueprint.name,
                    triggerName = blueprint.triggerName,
                    enabled = false,
                    conditions = blueprint.conditions,
                    steps = blueprint.steps,
                    onceForTemplate = blueprint.onceForTemplate
                )
            )
        WorkflowAudit.record(
            organizationId = organizationId,
            action = WorkflowAudit.ACTION_INSTANTIATED_BLUEPRINT,
            workflowId = numericWorkflowId(organizationId, workflow),
            actorUserId = actorUserId,
            detail = mapOf("blueprint" to key)
        )
        return workflow
    }

    fun overview(
        organizationId: Int,
        now: Instant = Clock.System.now()
    ): WorkflowOverviewResponse {
        val windowStart = now - OVERVIEW_WINDOW_DAYS.days
        val workflows = workflowService.listWorkflows(organizationId)
        val runs = recentRuns(organizationId, windowStart)
        val completed = runs.count { it.status == STATUS_COMPLETE }
        val failed = runs.count { it.status == STATUS_FAILED }
        val terminal = completed + failed
        val successRate = if (terminal == 0) 0.0 else completed.toDouble() / terminal.toDouble()
        return WorkflowOverviewResponse(
            totalWorkflows = workflows.size.toLong(),
            enabledWorkflows = workflows.count { it.enabled }.toLong(),
            publishedWorkflows = workflows.count { it.published }.toLong(),
            runsLast30d = runs.size.toLong(),
            successRate = successRate,
            failedLast30d = failed.toLong(),
            topWorkflows = topWorkflows(workflows, runs)
        )
    }

    fun usage(
        organizationId: Int,
        now: Instant = Clock.System.now()
    ): WorkflowUsageResponse =
        WorkflowUsage.summary(organizationId, now)

    fun listAudit(
        organizationId: Int,
        workflowId: Int?,
        limit: Int
    ): List<WorkflowAuditEventResponse> =
        WorkflowAudit.list(organizationId, workflowId, limit)

    fun listAudit(
        organizationId: Int,
        workflowId: String,
        limit: Int
    ): List<WorkflowAuditEventResponse> {
        val numericWorkflowId = resolveWorkflowResourceId(organizationId, workflowId)
        return if (numericWorkflowId != null) {
            WorkflowAudit.list(organizationId, numericWorkflowId, limit)
        } else {
            emptyList()
        }
    }

    fun export(
        organizationId: Int,
        workflowId: Int,
        actorUserId: Int?
    ): WorkflowExportResponse? {
        val workflow = workflowService.getWorkflow(organizationId, workflowId) ?: return null
        WorkflowAudit.record(
            organizationId = organizationId,
            action = WorkflowAudit.ACTION_EXPORTED,
            workflowId = workflowId,
            actorUserId = actorUserId
        )
        return WorkflowExport.toExport(workflow)
    }

    fun export(
        organizationId: Int,
        workflowId: String,
        actorUserId: Int?
    ): WorkflowExportResponse? {
        val numericWorkflowId = resolveWorkflowResourceId(organizationId, workflowId) ?: return null
        return export(organizationId, numericWorkflowId, actorUserId)
    }

    fun import(
        organizationId: Int,
        request: WorkflowImportRequest,
        actorUserId: Int?
    ): WorkflowResponse {
        val trimmedName = request.name.trim()
        require(trimmedName.isNotBlank()) { "Workflow name is required" }
        val existing = findByName(organizationId, trimmedName)
        return if (existing == null) {
            createImported(organizationId, trimmedName, request, actorUserId)
        } else {
            // A workflow's trigger is fixed at creation; updates never change it. Reject an
            // import that reuses a name under a different trigger rather than silently treating
            // it as unchanged or dropping the trigger change.
            require(existing.triggerName == request.triggerName) {
                "A workflow named \"$trimmedName\" already exists with a different trigger"
            }
            upsertImported(organizationId, existing, request, actorUserId)
        }
    }

    private fun createImported(
        organizationId: Int,
        name: String,
        request: WorkflowImportRequest,
        actorUserId: Int?
    ): WorkflowResponse {
        val created =
            workflowService.createWorkflow(
                organizationId,
                CreateWorkflowRequest(
                    name = name,
                    triggerName = request.triggerName,
                    enabled = request.enabled,
                    graph = request.graph,
                    onceForTemplate = request.onceForTemplate
                )
            )
        recordImport(
            organizationId = organizationId,
            workflowId = numericWorkflowId(organizationId, created),
            actorUserId = actorUserId,
            created = true,
            changed = true,
        )
        return created
    }

    private fun upsertImported(
        organizationId: Int,
        existing: WorkflowResponse,
        request: WorkflowImportRequest,
        actorUserId: Int?
    ): WorkflowResponse {
        val unchanged =
            graphsEqual(existing.graph, request.graph) &&
                existing.enabled == request.enabled &&
                existing.onceForTemplate == request.onceForTemplate
        if (unchanged) {
            recordImport(
                organizationId,
                numericWorkflowId(organizationId, existing),
                actorUserId,
                created = false,
                changed = false
            )
            return existing
        }
        val workflowId = numericWorkflowId(organizationId, existing)
        val updated =
            workflowService.updateWorkflow(
                organizationId,
                workflowId,
                UpdateWorkflowRequest(
                    enabled = request.enabled,
                    graph = request.graph,
                    onceForTemplate = request.onceForTemplate
                )
            ) ?: existing
        recordImport(organizationId, workflowId, actorUserId, created = false, changed = true)
        return updated
    }

    private fun numericWorkflowId(
        organizationId: Int,
        workflow: WorkflowResponse
    ): Int =
        workflowService.resolveWorkflowId(organizationId, Uuid.parse(workflow.id))
            ?: error("Workflow ${workflow.id} no longer exists")

    private fun resolveWorkflowResourceId(organizationId: Int, workflowId: String): Int? =
        workflowId.toUuidOrNull()
            ?.let { workflowService.resolveWorkflowId(organizationId, it) }

    private fun recordImport(
        organizationId: Int,
        workflowId: Int,
        actorUserId: Int?,
        created: Boolean,
        changed: Boolean
    ) {
        WorkflowAudit.record(
            organizationId = organizationId,
            action = WorkflowAudit.ACTION_IMPORTED,
            workflowId = workflowId,
            actorUserId = actorUserId,
            detail = mapOf(
                "created" to created.toString(),
                "changed" to changed.toString()
            )
        )
    }

    private fun findByName(
        organizationId: Int,
        name: String
    ): WorkflowResponse? {
        val workflowId =
            transaction {
                Workflows
                    .selectAll()
                    .where { (Workflows.organizationId eq organizationId) and (Workflows.name eq name) }
                    .firstOrNull()
                    ?.get(Workflows.id)
                    ?.value
            } ?: return null
        return workflowService.getWorkflow(organizationId, workflowId)
    }

    private fun recentRuns(
        organizationId: Int,
        windowStart: Instant
    ): List<RunStatusRow> =
        transaction {
            val rows = WorkflowRuns
                .selectAll()
                .where {
                    (WorkflowRuns.organizationId eq organizationId) and
                        (WorkflowRuns.createdAt greaterEq windowStart)
                }
                .toList()
            val workflowResourceIds = workflowResourceIds(rows.map { row -> row[WorkflowRuns.workflowId] })
            rows.map { row ->
                RunStatusRow(
                    workflowId = workflowResourceIds.requireWorkflowResourceId(
                        row[WorkflowRuns.workflowId],
                        "workflow"
                    ),
                    status = row[WorkflowRuns.status]
                )
            }
        }

    // Ranks workflows by their run volume within the same 30-day window the rest of the
    // overview uses, rather than by cumulative lifetime run count.
    private fun topWorkflows(
        workflows: List<WorkflowResponse>,
        runs: List<RunStatusRow>
    ): List<WorkflowOverviewTopWorkflow> {
        val namesById = workflows.associate { it.id to it.name }
        return runs
            .groupingBy { it.workflowId }
            .eachCount()
            .entries
            .mapNotNull { (workflowId, count) ->
                val name = namesById[workflowId] ?: return@mapNotNull null
                WorkflowOverviewTopWorkflow(workflowId = workflowId, name = name, runCount = count.toLong())
            }
            .sortedWith(compareByDescending<WorkflowOverviewTopWorkflow> { it.runCount }.thenBy { it.workflowId })
            .take(TOP_WORKFLOW_LIMIT)
    }

    private fun graphsEqual(
        left: WorkflowGraphConfig,
        right: WorkflowGraphConfig
    ): Boolean =
        json.encodeToString(left) == json.encodeToString(right)

    private companion object {
        private const val OVERVIEW_WINDOW_DAYS = 30
        private const val TOP_WORKFLOW_LIMIT = 5
        private const val STATUS_COMPLETE = "complete"
        private const val STATUS_FAILED = "failed"
    }
}

private data class RunStatusRow(
    val workflowId: String,
    val status: String
)
