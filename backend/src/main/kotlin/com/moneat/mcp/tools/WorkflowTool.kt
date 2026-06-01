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

package com.moneat.mcp.tools

import com.moneat.mcp.auth.McpScopes
import com.moneat.mcp.models.McpContext
import com.moneat.mcp.protocol.InputSchema
import com.moneat.mcp.protocol.McpTool
import com.moneat.mcp.protocol.ToolCallResult
import com.moneat.workflows.models.CreateWorkflowRequest
import com.moneat.workflows.models.ManualWorkflowRunRequest
import com.moneat.workflows.models.UpdateWorkflowRequest
import com.moneat.workflows.models.WorkflowConditionConfig
import com.moneat.workflows.models.WorkflowGraphConfig
import com.moneat.workflows.models.WorkflowRunInstanceRequest
import com.moneat.workflows.models.WorkflowStepConfig
import com.moneat.workflows.models.workflowJson
import com.moneat.workflows.services.WorkflowBlueprintCatalog
import com.moneat.workflows.services.WorkflowGovernanceService
import com.moneat.workflows.services.WorkflowService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

private const val WORKFLOW_ID_FIELD = "workflow_id"
private const val RUN_ID_FIELD = "run_id"
private const val GRAPH_FIELD = "graph"
private const val GRAPH_JSON_FIELD = "graph_json"
private const val CONDITIONS_FIELD = "conditions"
private const val CONDITIONS_JSON_FIELD = "conditions_json"
private const val STEPS_FIELD = "steps"
private const val STEPS_JSON_FIELD = "steps_json"
private const val ONCE_FOR_TEMPLATE_FIELD = "once_for_template"
private const val ONCE_FOR_TEMPLATE_JSON_FIELD = "once_for_template_json"
private const val SCOPE_FIELD = "scope"
private const val SCOPE_JSON_FIELD = "scope_json"
private const val LIMIT_FIELD = "limit"
private const val DEFAULT_WORKFLOW_AUDIT_LIMIT = 100
private const val DEFAULT_WORKFLOW_RUN_LIMIT = 50
private const val MAX_WORKFLOW_AUDIT_LIMIT = 500
private const val MAX_WORKFLOW_RUN_LIMIT = 100

private val workflowMcpService = WorkflowService()
private val workflowMcpGovernanceService = WorkflowGovernanceService(workflowMcpService)

class ListWorkflowsTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "list_workflows"
    override val description = "List workflows for the organization"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult =
        jsonResult(mapOf("workflows" to service.listWorkflows(context.organizationId)))
}

class GetWorkflowTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "get_workflow"
    override val description = "Get a workflow by ID"
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(WORKFLOW_ID_FIELD to schemaInteger("Workflow ID"))),
        required = listOf(WORKFLOW_ID_FIELD),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        val workflow = service.getWorkflow(context.organizationId, workflowId)
            ?: return errorResult("Workflow not found: $workflowId")
        return jsonResult(workflow)
    }
}

class CreateWorkflowTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "create_workflow"
    override val description =
        "Create a workflow draft. Supports graph or graph_json for graph-based workflow definitions."
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "name" to schemaString("Workflow name"),
                "trigger_name" to schemaString("Trigger name from the workflow catalog"),
                "enabled" to schemaBoolean("Whether the workflow is enabled"),
                CONDITIONS_FIELD to schemaJsonArray("Optional legacy condition array"),
                CONDITIONS_JSON_FIELD to schemaString("Optional JSON-encoded legacy condition array"),
                STEPS_FIELD to schemaJsonArray("Optional legacy step array"),
                STEPS_JSON_FIELD to schemaString("Optional JSON-encoded legacy step array"),
                GRAPH_FIELD to schemaObject("Optional graph object with nodes and edges"),
                GRAPH_JSON_FIELD to schemaString("Optional JSON-encoded graph object with nodes and edges"),
                ONCE_FOR_TEMPLATE_FIELD to schemaStringArray("Optional once-for template references"),
                ONCE_FOR_TEMPLATE_JSON_FIELD to schemaString("Optional JSON-encoded once-for template array"),
            )
        ),
        required = listOf("name", "trigger_name"),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val parsed = when (val result = parseCreateWorkflowRequest(args)) {
            is WorkflowParseResult.Failure -> return errorResult(result.message)
            is WorkflowParseResult.Success -> result.value
        }
        return try {
            jsonResult(service.createWorkflow(context.organizationId, parsed))
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid workflow arguments")
        }
    }
}

class UpdateWorkflowTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "update_workflow"
    override val description =
        "Update workflow metadata or draft configuration. Supports graph or graph_json."
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                WORKFLOW_ID_FIELD to schemaInteger("Workflow ID"),
                "name" to schemaString("Updated workflow name"),
                "enabled" to schemaBoolean("Whether the workflow is enabled"),
                CONDITIONS_FIELD to schemaJsonArray("Replacement legacy condition array"),
                CONDITIONS_JSON_FIELD to schemaString("JSON-encoded replacement legacy condition array"),
                STEPS_FIELD to schemaJsonArray("Replacement legacy step array"),
                STEPS_JSON_FIELD to schemaString("JSON-encoded replacement legacy step array"),
                GRAPH_FIELD to schemaObject("Replacement graph object with nodes and edges"),
                GRAPH_JSON_FIELD to schemaString("JSON-encoded replacement graph object with nodes and edges"),
                ONCE_FOR_TEMPLATE_FIELD to schemaStringArray("Replacement once-for template references"),
                ONCE_FOR_TEMPLATE_JSON_FIELD to schemaString("JSON-encoded replacement once-for template array"),
            )
        ),
        required = listOf(WORKFLOW_ID_FIELD),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        val request = when (val result = parseUpdateWorkflowRequest(args)) {
            is WorkflowParseResult.Failure -> return errorResult(result.message)
            is WorkflowParseResult.Success -> result.value
        }
        return try {
            val workflow = service.updateWorkflow(context.organizationId, workflowId, request)
                ?: return errorResult("Workflow not found: $workflowId")
            jsonResult(workflow)
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid workflow arguments")
        }
    }
}

class DeleteWorkflowTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "delete_workflow"
    override val description = "Delete a workflow"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf(WORKFLOW_ID_FIELD to schemaInteger("Workflow ID"))),
        required = listOf(WORKFLOW_ID_FIELD),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        return try {
            if (service.deleteWorkflow(context.organizationId, workflowId)) {
                textResult("Workflow $workflowId deleted")
            } else {
                errorResult("Workflow not found: $workflowId")
            }
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Invalid workflow arguments")
        }
    }
}

class PublishWorkflowTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "publish_workflow"
    override val description = "Publish the latest workflow version so it can run for matching triggers"
    override val readOnly = false
    override val inputSchema = workflowIdInputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult =
        publishStateResult(args, context, service::publishWorkflow, "published")
}

class UnpublishWorkflowTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "unpublish_workflow"
    override val description = "Unpublish the latest workflow version so event triggers no longer run it"
    override val readOnly = false
    override val inputSchema = workflowIdInputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult =
        publishStateResult(args, context, service::unpublishWorkflow, "unpublished")
}

class RunWorkflowTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "run_workflow"
    override val description = "Start a manual workflow run with optional scope or scope_json"
    override val readOnly = false
    override val inputSchema = workflowRunInputSchema()
    override val requiredScopes = setOf(McpScopes.WORKFLOW_RUN)

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        val scope = when (val result = args.optionalScopeArg()) {
            is WorkflowParseResult.Failure -> return errorResult(result.message)
            is WorkflowParseResult.Success -> result.value
        }
        val run = service.runWorkflow(
            organizationId = context.organizationId,
            workflowId = workflowId,
            request = ManualWorkflowRunRequest(scope),
            actorUserId = context.userId,
        ) ?: return errorResult("Workflow not found or could not be run: $workflowId")
        return jsonResult(run)
    }
}

class CreateWorkflowInstanceTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "create_workflow_instance"
    override val description = "Create an API-triggered workflow instance with optional scope or scope_json"
    override val readOnly = false
    override val inputSchema = workflowRunInputSchema()
    override val requiredScopes = setOf(McpScopes.WORKFLOW_RUN)

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        val scope = when (val result = args.optionalScopeArg()) {
            is WorkflowParseResult.Failure -> return errorResult(result.message)
            is WorkflowParseResult.Success -> result.value
        }
        val run = service.createWorkflowInstance(
            organizationId = context.organizationId,
            workflowId = workflowId,
            request = WorkflowRunInstanceRequest(scope),
            callerUserId = context.userId,
        ) ?: return errorResult("Workflow not found or is not API-triggered: $workflowId")
        return jsonResult(run)
    }
}

class CancelWorkflowRunTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "cancel_workflow_run"
    override val description = "Cancel a workflow run"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                WORKFLOW_ID_FIELD to schemaInteger("Workflow ID"),
                RUN_ID_FIELD to schemaInteger("Workflow run ID"),
            )
        ),
        required = listOf(WORKFLOW_ID_FIELD, RUN_ID_FIELD),
    )
    override val requiredScopes = setOf(McpScopes.WORKFLOW_RUN)

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        val runId = args.requiredIntArg(RUN_ID_FIELD) ?: return errorResult("$RUN_ID_FIELD is required")
        val canceled = service.cancelRun(context.organizationId, workflowId, runId)
            ?: return errorResult("Workflow run not found: $runId")
        return jsonResult(canceled)
    }
}

class ListWorkflowRunsTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "list_workflow_runs"
    override val description = "List recent workflow runs for a workflow"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                WORKFLOW_ID_FIELD to schemaInteger("Workflow ID"),
                LIMIT_FIELD to schemaInteger("Maximum runs to return"),
            )
        ),
        required = listOf(WORKFLOW_ID_FIELD),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        val limit = args.optionalLimit(DEFAULT_WORKFLOW_RUN_LIMIT, MAX_WORKFLOW_RUN_LIMIT)
            ?: return errorResult("$LIMIT_FIELD must be a valid integer")
        return jsonResult(mapOf("runs" to service.listRuns(context.organizationId, workflowId, limit)))
    }
}

class GetWorkflowRunTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "get_workflow_run"
    override val description = "Get a workflow run by ID"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                WORKFLOW_ID_FIELD to schemaInteger("Workflow ID"),
                RUN_ID_FIELD to schemaInteger("Workflow run ID"),
            )
        ),
        required = listOf(WORKFLOW_ID_FIELD, RUN_ID_FIELD),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        val runId = args.requiredIntArg(RUN_ID_FIELD) ?: return errorResult("$RUN_ID_FIELD is required")
        val run = service.getRun(context.organizationId, workflowId, runId)
            ?: return errorResult("Workflow run not found: $runId")
        return jsonResult(run)
    }
}

class GetWorkflowCatalogTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "get_workflow_catalog"
    override val description = "Get workflow triggers, resources, actions, and graph node types"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult =
        jsonResult(service.catalog())
}

class ListWorkflowBlueprintsTool : McpTool {
    override val name = "list_workflow_blueprints"
    override val description = "List curated workflow blueprints"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult =
        jsonResult(mapOf("blueprints" to WorkflowBlueprintCatalog.list()))
}

class GetWorkflowBlueprintTool : McpTool {
    override val name = "get_workflow_blueprint"
    override val description = "Get a workflow blueprint with its graph"
    override val inputSchema = InputSchema(
        properties = JsonObject(mapOf("key" to schemaString("Workflow blueprint key"))),
        required = listOf("key"),
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val key = args.stringArg("key") ?: return errorResult("key is required")
        val blueprint = WorkflowBlueprintCatalog.get(key)
            ?: return errorResult("Workflow blueprint not found: $key")
        return jsonResult(WorkflowBlueprintCatalog.detail(blueprint))
    }
}

class ListWorkflowAuditTool(
    private val governanceService: WorkflowGovernanceService = workflowMcpGovernanceService,
) : McpTool {
    override val name = "list_workflow_audit"
    override val description = "List workflow audit events, optionally for one workflow"
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                WORKFLOW_ID_FIELD to schemaInteger("Optional workflow ID"),
                LIMIT_FIELD to schemaInteger("Maximum audit events to return"),
            )
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = if (args.containsKey(WORKFLOW_ID_FIELD)) {
            args.optionalIntArg(WORKFLOW_ID_FIELD)
                ?: return errorResult("$WORKFLOW_ID_FIELD must be a valid integer")
        } else {
            null
        }
        val limit = args.optionalLimit(DEFAULT_WORKFLOW_AUDIT_LIMIT, MAX_WORKFLOW_AUDIT_LIMIT)
            ?: return errorResult("$LIMIT_FIELD must be a valid integer")
        return jsonResult(
            mapOf("audit_events" to governanceService.listAudit(context.organizationId, workflowId, limit))
        )
    }
}

class GetWorkflowWebhookSigningTool(
    private val service: WorkflowService = workflowMcpService,
) : McpTool {
    override val name = "get_workflow_webhook_signing"
    override val description = "Get signed webhook ingress URL and signing secret for a webhook-triggered workflow"
    override val readOnly = false
    override val inputSchema = workflowIdInputSchema()
    override val requiredScopes = setOf(McpScopes.WORKFLOW_WRITE)

    override suspend fun execute(
        args: JsonObject,
        context: McpContext,
    ): ToolCallResult {
        val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
            ?: return errorResult("$WORKFLOW_ID_FIELD is required")
        return try {
            val signing = service.webhookSigningInfo(context.organizationId, workflowId)
                ?: return errorResult("Webhook signing is only available for webhook-triggered workflows")
            jsonResult(signing)
        } catch (e: IllegalStateException) {
            errorResult(e.message ?: "Workflow webhook signing is not configured")
        }
    }
}

private fun publishStateResult(
    args: JsonObject,
    context: McpContext,
    operation: (Int, Int) -> Any?,
    state: String,
): ToolCallResult {
    val workflowId = args.requiredIntArg(WORKFLOW_ID_FIELD)
        ?: return errorResult("$WORKFLOW_ID_FIELD is required")
    val workflow = operation(context.organizationId, workflowId)
        ?: return errorResult("Workflow not found: $workflowId")
    return jsonResult(mapOf("status" to state, "workflow" to workflow))
}

private fun workflowIdInputSchema(): InputSchema =
    InputSchema(
        properties = JsonObject(mapOf(WORKFLOW_ID_FIELD to schemaInteger("Workflow ID"))),
        required = listOf(WORKFLOW_ID_FIELD),
    )

private fun workflowRunInputSchema(): InputSchema =
    InputSchema(
        properties = JsonObject(
            mapOf(
                WORKFLOW_ID_FIELD to schemaInteger("Workflow ID"),
                SCOPE_FIELD to schemaObject("Optional workflow scope object"),
                SCOPE_JSON_FIELD to schemaString("Optional JSON-encoded workflow scope object"),
            )
        ),
        required = listOf(WORKFLOW_ID_FIELD),
    )

private fun parseCreateWorkflowRequest(args: JsonObject): WorkflowParseResult<CreateWorkflowRequest> {
    val name = args.stringArg("name") ?: return WorkflowParseResult.Failure("name is required")
    val triggerName = args.stringArg("trigger_name") ?: return WorkflowParseResult.Failure("trigger_name is required")
    val enabled = when (val result = args.optionalBooleanArg("enabled")) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value ?: true
    }
    val conditions = when (val result = args.conditionsArg(required = false)) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value ?: emptyList()
    }
    val steps = when (val result = args.stepsArg(required = false)) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value ?: emptyList()
    }
    val graph = when (val result = args.graphArg()) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value
    }
    val onceForTemplate = when (val result = args.onceForTemplateArg(required = false)) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value ?: emptyList()
    }
    return WorkflowParseResult.Success(
        CreateWorkflowRequest(
            name = name,
            triggerName = triggerName,
            enabled = enabled,
            conditions = conditions,
            steps = steps,
            graph = graph,
            onceForTemplate = onceForTemplate,
        )
    )
}

private fun parseUpdateWorkflowRequest(args: JsonObject): WorkflowParseResult<UpdateWorkflowRequest> {
    val enabled = when (val result = args.optionalBooleanArg("enabled")) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value
    }
    val conditions = when (val result = args.conditionsArg(required = false)) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value
    }
    val steps = when (val result = args.stepsArg(required = false)) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value
    }
    val graph = when (val result = args.graphArg()) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value
    }
    val onceForTemplate = when (val result = args.onceForTemplateArg(required = false)) {
        is WorkflowParseResult.Failure -> return result
        is WorkflowParseResult.Success -> result.value
    }
    return WorkflowParseResult.Success(
        UpdateWorkflowRequest(
            name = args.stringArg("name"),
            enabled = enabled,
            conditions = conditions,
            steps = steps,
            graph = graph,
            onceForTemplate = onceForTemplate,
        )
    )
}

private fun JsonObject.conditionsArg(
    required: Boolean,
): WorkflowParseResult<List<WorkflowConditionConfig>?> =
    decodeOptionalJsonArg(CONDITIONS_FIELD, CONDITIONS_JSON_FIELD, required, "conditions")

private fun JsonObject.stepsArg(
    required: Boolean,
): WorkflowParseResult<List<WorkflowStepConfig>?> =
    decodeOptionalJsonArg(STEPS_FIELD, STEPS_JSON_FIELD, required, "steps")

private fun JsonObject.graphArg(): WorkflowParseResult<WorkflowGraphConfig?> =
    decodeOptionalJsonArg(GRAPH_FIELD, GRAPH_JSON_FIELD, required = false, label = "graph")

private fun JsonObject.onceForTemplateArg(
    required: Boolean,
): WorkflowParseResult<List<String>?> =
    decodeOptionalJsonArg(
        ONCE_FOR_TEMPLATE_FIELD,
        ONCE_FOR_TEMPLATE_JSON_FIELD,
        required,
        "once_for_template",
    )

private inline fun <reified T> JsonObject.decodeOptionalJsonArg(
    objectField: String,
    jsonField: String,
    required: Boolean,
    label: String,
): WorkflowParseResult<T?> {
    val value = this[objectField]
    val jsonValue = this[jsonField]
    if ((value == null || value == JsonNull) && (jsonValue == null || jsonValue == JsonNull)) {
        return if (required) WorkflowParseResult.Failure("$label is required") else WorkflowParseResult.Success(null)
    }
    val jsonElement = when {
        value != null && value != JsonNull -> value
        jsonValue != null && jsonValue != JsonNull -> {
            when (val parsed = jsonValue.parseJsonStringArg(jsonField, label)) {
                is WorkflowParseResult.Failure -> return parsed
                is WorkflowParseResult.Success -> parsed.value
            }
        }
        else -> JsonNull
    }
    return try {
        WorkflowParseResult.Success(workflowJson.decodeFromJsonElement<T>(jsonElement))
    } catch (e: SerializationException) {
        WorkflowParseResult.Failure("$label must be valid workflow JSON")
    } catch (e: IllegalArgumentException) {
        WorkflowParseResult.Failure("$label must be valid workflow JSON")
    }
}

private fun JsonElement.parseJsonStringArg(
    field: String,
    label: String,
): WorkflowParseResult<JsonElement> {
    val primitive = this as? JsonPrimitive
        ?: return WorkflowParseResult.Failure("$field must be a JSON string")
    if (!primitive.isString) return WorkflowParseResult.Failure("$field must be a JSON string")
    return try {
        WorkflowParseResult.Success(workflowJson.parseToJsonElement(primitive.content))
    } catch (e: SerializationException) {
        WorkflowParseResult.Failure("$label must be valid JSON")
    } catch (e: IllegalArgumentException) {
        WorkflowParseResult.Failure("$label must be valid JSON")
    }
}

private fun JsonObject.optionalScopeArg(): WorkflowParseResult<Map<String, JsonElement>> {
    val value = this[SCOPE_FIELD]
    val jsonValue = this[SCOPE_JSON_FIELD]
    val element = when {
        value is JsonObject -> value
        value != null && value != JsonNull -> return WorkflowParseResult.Failure("$SCOPE_FIELD must be a JSON object")
        jsonValue != null && jsonValue != JsonNull -> {
            when (
                val parsed = jsonValue.parseJsonStringArg(
                    SCOPE_JSON_FIELD,
                    "scope",
                )
            ) {
                is WorkflowParseResult.Failure -> return parsed
                is WorkflowParseResult.Success -> parsed.value
            }
        }
        else -> return WorkflowParseResult.Success(emptyMap())
    }
    if (element !is JsonObject) return WorkflowParseResult.Failure("scope must be a JSON object")
    return WorkflowParseResult.Success(element)
}

private fun JsonObject.optionalBooleanArg(name: String): WorkflowParseResult<Boolean?> {
    val value = this[name] ?: return WorkflowParseResult.Success(null)
    if (value == JsonNull) return WorkflowParseResult.Success(null)
    val primitive = value as? JsonPrimitive ?: return WorkflowParseResult.Failure("$name must be true or false")
    return primitive.booleanOrNull?.let { WorkflowParseResult.Success(it) }
        ?: WorkflowParseResult.Failure("$name must be true or false")
}

private fun JsonObject.optionalLimit(default: Int, max: Int): Int? {
    val value = optionalIntArg(LIMIT_FIELD) ?: return if (containsKey(LIMIT_FIELD)) null else default
    return value.coerceIn(1, max)
}

private fun JsonObject.requiredIntArg(name: String): Int? =
    optionalIntArg(name)

private fun JsonObject.optionalIntArg(name: String): Int? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.content.toIntOrNull()
}

private fun JsonObject.stringArg(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.trim().takeIf { it.isNotEmpty() }
}

private sealed class WorkflowParseResult<out T> {
    data class Success<T>(val value: T) : WorkflowParseResult<T>()
    data class Failure(val message: String) : WorkflowParseResult<Nothing>()
}

private fun schemaJsonArray(description: String): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(description),
            "items" to JsonObject(mapOf("type" to JsonPrimitive("object"))),
        )
    )

private fun schemaStringArray(description: String): JsonObject =
    JsonObject(
        mapOf(
            "type" to JsonPrimitive("array"),
            "description" to JsonPrimitive(description),
            "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
        )
    )
