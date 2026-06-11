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

import com.moneat.workflows.models.WorkflowRuns
import com.moneat.workflows.models.WorkflowVersions
import com.moneat.workflows.models.Workflows
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

fun workflowResourceId(workflowId: Int): String =
    workflowResourceIds(listOf(workflowId))[workflowId]
        ?: error("Missing resource_id for workflow $workflowId")

fun workflowResourceIds(workflowIds: Collection<Int>): Map<Int, String> {
    if (workflowIds.isEmpty()) return emptyMap()
    return Workflows
        .selectAll()
        .where { Workflows.id inList workflowIds.distinct() }
        .associate { row -> row[Workflows.id].value to row[Workflows.resourceId].toString() }
}

fun workflowRunResourceId(runId: Int): String =
    workflowRunResourceIds(listOf(runId))[runId]
        ?: error("Missing resource_id for workflow run $runId")

fun workflowRunResourceIds(runIds: Collection<Int>): Map<Int, String> {
    if (runIds.isEmpty()) return emptyMap()
    return WorkflowRuns
        .selectAll()
        .where { WorkflowRuns.id inList runIds.distinct() }
        .associate { row -> row[WorkflowRuns.id].value to row[WorkflowRuns.resourceId].toString() }
}

fun workflowVersionResourceId(workflowVersionId: Int): String =
    workflowVersionResourceIds(listOf(workflowVersionId))[workflowVersionId]
        ?: error("Missing resource_id for workflow version $workflowVersionId")

fun workflowVersionResourceIds(workflowVersionIds: Collection<Int>): Map<Int, String> {
    if (workflowVersionIds.isEmpty()) return emptyMap()
    return WorkflowVersions
        .selectAll()
        .where { WorkflowVersions.id inList workflowVersionIds.distinct() }
        .associate { row -> row[WorkflowVersions.id].value to row[WorkflowVersions.resourceId].toString() }
}

fun <T> Map<Int, T>.requireWorkflowResourceId(id: Int, label: String): T =
    this[id] ?: error("Missing resource_id for $label $id")
