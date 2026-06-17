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

package com.moneat.workflows.engine.temporal

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.common.SearchAttributeKey
import io.temporal.common.SearchAttributes

class TemporalWorkflowExecutionEngine(
    private val clientProvider: TemporalClientProvider
) : WorkflowExecutionEngine {
    override suspend fun start(request: WorkflowStartRequest): WorkflowStartResult {
        clientProvider.ensureSearchAttributes()
        val stub =
            clientProvider.client.newWorkflowStub(
                WorkflowInterpreterWorkflow::class.java,
                workflowOptions(request)
            )
        val execution =
            WorkflowClient.start(
                stub::run,
                WorkflowInterpreterInput(
                    runId = request.runId,
                    workflowId = request.workflowId,
                    workflowVersionId = request.workflowVersionId,
                    organizationId = request.organizationId,
                    triggerName = request.triggerName
                )
            )
        return WorkflowStartResult(execution.workflowId, execution.runId)
    }

    override suspend fun cancel(temporalWorkflowId: String) {
        clientProvider.client.newUntypedWorkflowStub(temporalWorkflowId).cancel()
    }

    private fun workflowOptions(request: WorkflowStartRequest): WorkflowOptions =
        WorkflowOptions
            .newBuilder()
            .setTaskQueue(WORKFLOW_TASK_QUEUE)
            .setWorkflowId(request.temporalWorkflowId)
            .setTypedSearchAttributes(searchAttributes(request))
            .build()

    private fun searchAttributes(request: WorkflowStartRequest): SearchAttributes =
        SearchAttributes
            .newBuilder()
            .set(SearchAttributeKey.forKeyword(ORGANIZATION_SEARCH_ATTRIBUTE), request.organizationId.toString())
            .set(SearchAttributeKey.forKeyword(WORKFLOW_SEARCH_ATTRIBUTE), request.workflowId.toString())
            .build()
}
