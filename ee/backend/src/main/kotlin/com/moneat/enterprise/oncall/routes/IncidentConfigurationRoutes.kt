// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.config.CreateIncidentCustomField
import com.moneat.enterprise.incidents.config.CreateIncidentForm
import com.moneat.enterprise.incidents.config.CreateIncidentType
import com.moneat.enterprise.incidents.config.IncidentConfigurationService
import com.moneat.enterprise.incidents.config.IncidentCustomFieldOptionInput
import com.moneat.enterprise.incidents.config.IncidentFormFieldInput
import com.moneat.enterprise.incidents.models.IncidentCustomFieldValueType
import com.moneat.enterprise.incidents.models.IncidentFormStage
import com.moneat.enterprise.incidents.responders.CreateIncidentRole
import com.moneat.enterprise.incidents.responders.IncidentResponderService
import com.moneat.utils.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class CreateIncidentTypeRequest(
    val key: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class CreateIncidentCustomFieldRequest(
    val key: String,
    val name: String,
    val description: String? = null,
    val valueType: String,
    val catalogResourceType: String? = null,
    val options: List<CreateIncidentCustomFieldOptionRequest> = emptyList(),
)

@Serializable
data class CreateIncidentCustomFieldOptionRequest(
    val value: String,
    val label: String,
    val position: Int,
    val color: String? = null,
)

@Serializable
data class CreateIncidentFormRequest(
    val incidentTypeId: String? = null,
    val stage: String,
    val name: String,
    val fields: List<CreateIncidentFormFieldRequest>,
)

@Serializable
data class CreateIncidentFormFieldRequest(
    val fieldId: String,
    val position: Int,
    val visible: Boolean = true,
    val required: Boolean = false,
    val defaultValue: JsonElement? = null,
    val helpText: String? = null,
    val condition: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class CreateIncidentRoleRequest(
    val key: String,
    val name: String,
    val description: String? = null,
    val responsibilities: List<String>,
    val privateInstructions: String? = null,
    val required: Boolean = false,
    val default: Boolean = false,
)

internal fun Route.registerIncidentConfigurationRoutes(
    service: IncidentConfigurationService,
    responderService: IncidentResponderService,
) {
    route("/v1/on-call/incident-configuration") {
        authenticate("auth-jwt") {
            route("/types") {
                get {
                    val context = call.requireUserContext() ?: return@get
                    call.respond(service.listIncidentTypes(context.organizationId))
                }
                post {
                    val context = call.requireIncidentAdminContext() ?: return@post
                    val request = call.receive<CreateIncidentTypeRequest>()
                    call.respondConfigurationFailure {
                        call.respond(
                            HttpStatusCode.Created,
                            service.createIncidentType(
                                organizationId = context.organizationId,
                                actorUserId = context.userId,
                                request = CreateIncidentType(
                                    stableKey = request.key,
                                    name = request.name,
                                    description = request.description,
                                    enabled = request.enabled,
                                ),
                            ),
                        )
                    }
                }
            }
            route("/fields") {
                get {
                    val context = call.requireUserContext() ?: return@get
                    call.respond(service.listCustomFields(context.organizationId))
                }
                post {
                    val context = call.requireIncidentAdminContext() ?: return@post
                    val request = call.receive<CreateIncidentCustomFieldRequest>()
                    call.respondConfigurationFailure {
                        val valueType = enumValue<IncidentCustomFieldValueType>(request.valueType, "custom field type")
                        val options =
                            request.options.map {
                                IncidentCustomFieldOptionInput(
                                    value = it.value,
                                    label = it.label,
                                    position = it.position,
                                    color = it.color,
                                )
                            }
                        call.respond(
                            HttpStatusCode.Created,
                            service.createCustomField(
                                context.organizationId,
                                context.userId,
                                CreateIncidentCustomField(
                                    stableKey = request.key,
                                    name = request.name,
                                    description = request.description,
                                    valueType = valueType,
                                    catalogResourceType = request.catalogResourceType,
                                    options = options,
                                ),
                            ),
                        )
                    }
                }
            }
            route("/forms") {
                get {
                    val context = call.requireUserContext() ?: return@get
                    call.respondConfigurationFailure {
                        val stage = call.request.queryParameters["stage"]?.let {
                            enumValue<IncidentFormStage>(it, "incident form stage")
                        }
                        call.respond(service.listForms(context.organizationId, stage))
                    }
                }
                post {
                    val context = call.requireIncidentAdminContext() ?: return@post
                    val request = call.receive<CreateIncidentFormRequest>()
                    call.respondConfigurationFailure {
                        val fields =
                            request.fields.map {
                                IncidentFormFieldInput(
                                    fieldId = it.fieldId,
                                    position = it.position,
                                    visible = it.visible,
                                    required = it.required,
                                    defaultValue = it.defaultValue,
                                    helpText = it.helpText,
                                    condition = it.condition,
                                )
                            }
                        call.respond(
                            HttpStatusCode.Created,
                            service.createForm(
                                organizationId = context.organizationId,
                                actorUserId = context.userId,
                                request = CreateIncidentForm(
                                    incidentTypeResourceId = request.incidentTypeId,
                                    stage = enumValue(request.stage, "incident form stage"),
                                    name = request.name,
                                    fields = fields,
                                ),
                            ),
                        )
                    }
                }
            }
            route("/roles") {
                get {
                    val context = call.requireUserContext() ?: return@get
                    val includePrivateInstructions =
                        call.request.queryParameters["includePrivateInstructions"]?.toBooleanStrictOrNull() ?: false
                    if (includePrivateInstructions && !call.requireIncidentAdmin(context)) return@get
                    call.respond(
                        responderService.listRoles(
                            context.organizationId,
                            context.userId,
                            includePrivateInstructions,
                        ),
                    )
                }
                post {
                    val context = call.requireIncidentAdminContext() ?: return@post
                    val request = call.receive<CreateIncidentRoleRequest>()
                    call.respondConfigurationFailure {
                        call.respond(
                            HttpStatusCode.Created,
                            responderService.createRole(
                                context.organizationId,
                                context.userId,
                                CreateIncidentRole(
                                    stableKey = request.key,
                                    name = request.name,
                                    description = request.description,
                                    responsibilities = request.responsibilities,
                                    privateInstructions = request.privateInstructions,
                                    required = request.required,
                                    default = request.default,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondConfigurationFailure(
    action: suspend () -> Unit,
) {
    try {
        action()
    } catch (error: IncidentCommandException) {
        respondIncidentCommandFailure(error)
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(error.message))
    }
}

private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
    enumValues<T>().firstOrNull { it.name == value.trim().uppercase() }
        ?: throw IllegalArgumentException("Invalid $label")
