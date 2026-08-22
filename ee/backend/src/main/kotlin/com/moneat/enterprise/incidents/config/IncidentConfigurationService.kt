// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.config

import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import com.moneat.enterprise.incidents.models.IncidentCustomFieldValueType
import com.moneat.enterprise.incidents.models.IncidentFormStage
import com.moneat.enterprise.incidents.models.NativeIncidentCustomFieldOptions
import com.moneat.enterprise.incidents.models.NativeIncidentCustomFields
import com.moneat.enterprise.incidents.models.NativeIncidentFormFields
import com.moneat.enterprise.incidents.models.NativeIncidentForms
import com.moneat.enterprise.incidents.models.NativeIncidentTypes
import com.moneat.shared.models.Memberships
import com.moneat.shared.services.toUuidOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Serializable
data class IncidentTypeDefinition(
    val id: String,
    val key: String,
    val version: Int,
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
)

@Serializable
data class IncidentCustomFieldOption(
    val id: String,
    val value: String,
    val label: String,
    val position: Int,
    val color: String? = null,
)

@Serializable
data class IncidentCustomFieldDefinition(
    val id: String,
    val key: String,
    val version: Int,
    val name: String,
    val description: String? = null,
    val valueType: IncidentCustomFieldValueType,
    val catalogResourceType: String? = null,
    val options: List<IncidentCustomFieldOption> = emptyList(),
)

@Serializable
data class IncidentFormFieldDefinition(
    val id: String,
    val field: IncidentCustomFieldDefinition,
    val position: Int,
    val visible: Boolean,
    val required: Boolean,
    val defaultValue: JsonElement? = null,
    val helpText: String? = null,
    val condition: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class IncidentFormDefinition(
    val id: String,
    val incidentTypeId: String? = null,
    val stage: IncidentFormStage,
    val version: Int,
    val name: String,
    val fields: List<IncidentFormFieldDefinition>,
)

data class IncidentCustomFieldOptionInput(
    val value: String,
    val label: String,
    val position: Int,
    val color: String? = null,
)

data class CreateIncidentType(
    val stableKey: String,
    val name: String,
    val description: String?,
    val enabled: Boolean,
)

data class CreateIncidentCustomField(
    val stableKey: String,
    val name: String,
    val description: String?,
    val valueType: IncidentCustomFieldValueType,
    val catalogResourceType: String?,
    val options: List<IncidentCustomFieldOptionInput>,
)

data class IncidentFormFieldInput(
    val fieldId: String,
    val position: Int,
    val visible: Boolean = true,
    val required: Boolean = false,
    val defaultValue: JsonElement? = null,
    val helpText: String? = null,
    val condition: Map<String, JsonElement> = emptyMap(),
)

data class CreateIncidentForm(
    val incidentTypeResourceId: String?,
    val stage: IncidentFormStage,
    val name: String,
    val fields: List<IncidentFormFieldInput>,
)

data class ResolvedIncidentForm(
    val incidentTypeDefinitionId: Int?,
    val incidentTypeName: String?,
    val formDefinitionId: Int?,
    val definitionSnapshot: Map<String, JsonElement>,
    val values: Map<String, JsonElement>,
)

class IncidentConfigurationService {
    fun createIncidentType(
        organizationId: Int,
        actorUserId: Int,
        request: CreateIncidentType,
    ): IncidentTypeDefinition =
        transaction {
            requireMember(organizationId, actorUserId)
            val key = normalizeKey(request.stableKey)
            requireName(request.name, "Incident type name", MAX_INCIDENT_TYPE_NAME_LENGTH)
            val current = currentType(organizationId, key)
            current?.supersede(NativeIncidentTypes.isCurrent, NativeIncidentTypes.supersededAt)
            val now = Clock.System.now()
            val id =
                NativeIncidentTypes.insertAndGetId {
                    it[resourceId] = Uuid.random()
                    it[NativeIncidentTypes.organizationId] = organizationId
                    it[NativeIncidentTypes.stableKey] = key
                    it[version] = (current?.get(NativeIncidentTypes.version) ?: 0) + 1
                    it[isCurrent] = true
                    it[NativeIncidentTypes.name] = request.name.trim()
                    it[NativeIncidentTypes.description] = request.description.cleaned()
                    it[NativeIncidentTypes.enabled] = request.enabled
                    it[createdBy] = actorUserId
                    it[createdAt] = now
                    it[supersededAt] = null
                }
            typeDefinition(NativeIncidentTypes.selectAll().where { NativeIncidentTypes.id eq id }.single())
        }

    fun listIncidentTypes(organizationId: Int): List<IncidentTypeDefinition> =
        transaction {
            NativeIncidentTypes
                .selectAll()
                .where {
                    (NativeIncidentTypes.organizationId eq organizationId) and
                        (NativeIncidentTypes.isCurrent eq true)
                }.orderBy(NativeIncidentTypes.name to SortOrder.ASC)
                .map(::typeDefinition)
        }

    fun createCustomField(
        organizationId: Int,
        actorUserId: Int,
        request: CreateIncidentCustomField,
    ): IncidentCustomFieldDefinition =
        transaction {
            requireMember(organizationId, actorUserId)
            val key = normalizeKey(request.stableKey)
            requireName(request.name, "Custom field name", MAX_CUSTOM_FIELD_NAME_LENGTH)
            validateFieldOptions(request.valueType, request.options)
            val catalogType = request.catalogResourceType.cleaned()
            catalogType?.let {
                require(it.length <= MAX_CATALOG_RESOURCE_TYPE_LENGTH) { "Catalog resource type is too long" }
            }
            require((request.valueType == IncidentCustomFieldValueType.CATALOG_RESOURCE) == (catalogType != null)) {
                "Catalog resource type is required only for catalog-resource fields"
            }
            val current = currentField(organizationId, key)
            current?.supersede(NativeIncidentCustomFields.isCurrent, NativeIncidentCustomFields.supersededAt)
            val now = Clock.System.now()
            val fieldId =
                NativeIncidentCustomFields.insertAndGetId {
                    it[resourceId] = Uuid.random()
                    it[NativeIncidentCustomFields.organizationId] = organizationId
                    it[NativeIncidentCustomFields.stableKey] = key
                    it[version] = (current?.get(NativeIncidentCustomFields.version) ?: 0) + 1
                    it[isCurrent] = true
                    it[NativeIncidentCustomFields.name] = request.name.trim()
                    it[NativeIncidentCustomFields.description] = request.description.cleaned()
                    it[NativeIncidentCustomFields.valueType] = request.valueType.wire
                    it[NativeIncidentCustomFields.catalogResourceType] = catalogType
                    it[createdBy] = actorUserId
                    it[createdAt] = now
                    it[supersededAt] = null
                }.value
            request.options.sortedBy(IncidentCustomFieldOptionInput::position).forEach { option ->
                NativeIncidentCustomFieldOptions.insertAndGetId {
                    it[resourceId] = Uuid.random()
                    it[customFieldId] = fieldId
                    it[value] = option.value.trim()
                    it[label] = option.label.trim()
                    it[position] = option.position
                    it[color] = option.color.cleaned()
                    it[createdAt] = now
                }
            }
            fieldDefinition(requireFieldRow(fieldId))
        }

    fun listCustomFields(organizationId: Int): List<IncidentCustomFieldDefinition> =
        transaction {
            NativeIncidentCustomFields
                .selectAll()
                .where {
                    (NativeIncidentCustomFields.organizationId eq organizationId) and
                        (NativeIncidentCustomFields.isCurrent eq true)
                }.orderBy(NativeIncidentCustomFields.name to SortOrder.ASC)
                .map(::fieldDefinition)
        }

    fun createForm(
        organizationId: Int,
        actorUserId: Int,
        request: CreateIncidentForm,
    ): IncidentFormDefinition =
        transaction {
            requireMember(organizationId, actorUserId)
            requireName(request.name, "Incident form name", MAX_INCIDENT_FORM_NAME_LENGTH)
            require(request.fields.map(IncidentFormFieldInput::fieldId).distinct().size == request.fields.size) {
                "Incident form fields must be unique"
            }
            require(request.fields.map(IncidentFormFieldInput::position).distinct().size == request.fields.size) {
                "Incident form positions must be unique"
            }
            require(request.fields.all { it.position >= 0 && (!it.required || it.visible) }) {
                "Incident form field positions and visibility are invalid"
            }
            val incidentType = request.incidentTypeResourceId?.let {
                requireType(organizationId, it, currentOnly = true)
            }
            val fieldRows = request.fields.associateWith {
                requireField(organizationId, it.fieldId, currentOnly = true)
            }
            val current = currentForm(
                organizationId,
                incidentType?.get(NativeIncidentTypes.id)?.value,
                request.stage,
            )
            current?.supersede(NativeIncidentForms.isCurrent, NativeIncidentForms.supersededAt)
            val now = Clock.System.now()
            val formId =
                NativeIncidentForms.insertAndGetId {
                    it[resourceId] = Uuid.random()
                    it[NativeIncidentForms.organizationId] = organizationId
                    it[incidentTypeId] = incidentType?.get(NativeIncidentTypes.id)?.value
                    it[NativeIncidentForms.stage] = request.stage.wire
                    it[version] = (current?.get(NativeIncidentForms.version) ?: 0) + 1
                    it[isCurrent] = true
                    it[NativeIncidentForms.name] = request.name.trim()
                    it[createdBy] = actorUserId
                    it[createdAt] = now
                    it[supersededAt] = null
                }.value
            request.fields.sortedBy(IncidentFormFieldInput::position).forEach { input ->
                val field = checkNotNull(fieldRows[input])
                input.defaultValue?.let { validateValue(field, it) }
                NativeIncidentFormFields.insertAndGetId {
                    it[resourceId] = Uuid.random()
                    it[NativeIncidentFormFields.formId] = formId
                    it[customFieldId] = field[NativeIncidentCustomFields.id].value
                    it[position] = input.position
                    it[isVisible] = input.visible
                    it[isRequired] = input.required
                    it[defaultValue] = input.defaultValue?.let { value -> mapOf("value" to value) }
                    it[helpText] = input.helpText.cleaned()
                    it[conditionExpression] = input.condition
                    it[createdAt] = now
                }
            }
            formDefinition(requireFormRow(formId))
        }

    fun listForms(
        organizationId: Int,
        stage: IncidentFormStage? = null,
    ): List<IncidentFormDefinition> =
        transaction {
            var query =
                NativeIncidentForms
                    .selectAll()
                    .where {
                        (NativeIncidentForms.organizationId eq organizationId) and
                            (NativeIncidentForms.isCurrent eq true)
                    }
            if (stage != null) query = query.andWhere { NativeIncidentForms.stage eq stage.wire }
            query.orderBy(NativeIncidentForms.name to SortOrder.ASC).map(::formDefinition)
        }

    fun resolveForm(
        organizationId: Int,
        incidentTypeResourceId: String?,
        stage: IncidentFormStage,
        submittedValues: Map<String, JsonElement>,
    ): ResolvedIncidentForm =
        transaction {
            val incidentType = incidentTypeResourceId?.let { requireType(organizationId, it, currentOnly = true) }
            val typeId = incidentType?.get(NativeIncidentTypes.id)?.value
            val form = currentForm(organizationId, typeId, stage) ?: currentForm(organizationId, null, stage)
            if (form == null) {
                require(submittedValues.isEmpty()) { "No $stage form accepts submitted incident fields" }
                return@transaction ResolvedIncidentForm(
                    incidentTypeDefinitionId = typeId,
                    incidentTypeName = incidentType?.get(NativeIncidentTypes.name),
                    formDefinitionId = null,
                    definitionSnapshot = typeSnapshot(incidentType),
                    values = emptyMap(),
                )
            }
            val definition = formDefinition(form)
            val values = validateSubmission(definition, submittedValues)
            ResolvedIncidentForm(
                incidentTypeDefinitionId = typeId,
                incidentTypeName = incidentType?.get(NativeIncidentTypes.name),
                formDefinitionId = form[NativeIncidentForms.id].value,
                definitionSnapshot = formSnapshot(definition),
                values = values,
            )
        }

    private fun validateSubmission(
        form: IncidentFormDefinition,
        submittedValues: Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val knownKeys = form.fields.mapTo(mutableSetOf()) { it.field.key }
        require(submittedValues.keys.all(knownKeys::contains)) { "Unknown incident form field submitted" }
        val resolved = mutableMapOf<String, JsonElement>()
        form.fields.sortedBy(IncidentFormFieldDefinition::position).forEach { formField ->
            if (!formField.visible || !conditionMatches(formField.condition, submittedValues + resolved)) return@forEach
            val value = submittedValues[formField.field.key] ?: formField.defaultValue
            require(!formField.required || value != null) { "${formField.field.name} is required" }
            value?.let {
                validatePublicValue(formField.field, it)
                resolved[formField.field.key] = it
            }
        }
        return resolved
    }

    private fun validatePublicValue(field: IncidentCustomFieldDefinition, value: JsonElement) {
        when (field.valueType) {
            IncidentCustomFieldValueType.SELECT ->
                require(requireString(value) in field.options.map(IncidentCustomFieldOption::value)) {
                    UNKNOWN_OPTION_MESSAGE
                }
            IncidentCustomFieldValueType.MULTI_SELECT -> {
                val values = (value as? JsonArray)?.map(::requireString)
                require(values != null) { "Multi-select incident field requires an array" }
                val allowed = field.options.mapTo(mutableSetOf(), IncidentCustomFieldOption::value)
                require(values.all(allowed::contains)) { UNKNOWN_OPTION_MESSAGE }
            }
            IncidentCustomFieldValueType.NUMBER -> {
                require(value is JsonPrimitive && value.doubleOrNull != null) {
                    "Number incident field requires a number"
                }
            }
            IncidentCustomFieldValueType.TEXT,
            IncidentCustomFieldValueType.LINK,
            IncidentCustomFieldValueType.USER,
            IncidentCustomFieldValueType.TEAM,
            IncidentCustomFieldValueType.SERVICE,
            IncidentCustomFieldValueType.CATALOG_RESOURCE,
            -> requireString(value)
        }
    }

    private fun validateValue(field: ResultRow, value: JsonElement) {
        val type = IncidentCustomFieldValueType.valueOf(field[NativeIncidentCustomFields.valueType])
        when (type) {
            IncidentCustomFieldValueType.SELECT -> validateSelectedOptions(field, listOf(requireString(value)))
            IncidentCustomFieldValueType.MULTI_SELECT -> {
                val values = (value as? JsonArray)?.map(::requireString)
                require(values != null) { "Multi-select incident field requires an array" }
                validateSelectedOptions(field, values)
            }
            IncidentCustomFieldValueType.NUMBER -> {
                require(value is JsonPrimitive && value.doubleOrNull != null) {
                    "Number incident field requires a number"
                }
            }
            IncidentCustomFieldValueType.TEXT,
            IncidentCustomFieldValueType.LINK,
            IncidentCustomFieldValueType.USER,
            IncidentCustomFieldValueType.TEAM,
            IncidentCustomFieldValueType.SERVICE,
            IncidentCustomFieldValueType.CATALOG_RESOURCE,
            -> requireString(value)
        }
    }

    private fun validateSelectedOptions(field: ResultRow, selected: List<String>) {
        val allowed =
            NativeIncidentCustomFieldOptions
                .selectAll()
                .where { NativeIncidentCustomFieldOptions.customFieldId eq field[NativeIncidentCustomFields.id].value }
                .mapTo(mutableSetOf()) { it[NativeIncidentCustomFieldOptions.value] }
        require(selected.all(allowed::contains)) { UNKNOWN_OPTION_MESSAGE }
    }

    private fun conditionMatches(
        condition: Map<String, JsonElement>,
        values: Map<String, JsonElement>,
    ): Boolean {
        if (condition.isEmpty()) return true
        val fieldKey = (condition["fieldKey"] as? JsonPrimitive)?.contentOrNull ?: return false
        val expected = condition["equals"] ?: return false
        return values[fieldKey] == expected
    }

    private fun formDefinition(row: ResultRow): IncidentFormDefinition {
        val fields =
            NativeIncidentFormFields
                .selectAll()
                .where { NativeIncidentFormFields.formId eq row[NativeIncidentForms.id].value }
                .orderBy(NativeIncidentFormFields.position to SortOrder.ASC)
                .map { formField ->
                    IncidentFormFieldDefinition(
                        id = formField[NativeIncidentFormFields.resourceId].toString(),
                        field = fieldDefinition(requireFieldRow(formField[NativeIncidentFormFields.customFieldId])),
                        position = formField[NativeIncidentFormFields.position],
                        visible = formField[NativeIncidentFormFields.isVisible],
                        required = formField[NativeIncidentFormFields.isRequired],
                        defaultValue = formField[NativeIncidentFormFields.defaultValue]?.get("value"),
                        helpText = formField[NativeIncidentFormFields.helpText],
                        condition = formField[NativeIncidentFormFields.conditionExpression],
                    )
                }
        val typeResourceId = row[NativeIncidentForms.incidentTypeId]?.let { typeId ->
            NativeIncidentTypes
                .selectAll()
                .where { NativeIncidentTypes.id eq typeId }
                .single()[NativeIncidentTypes.resourceId]
                .toString()
        }
        return IncidentFormDefinition(
            id = row[NativeIncidentForms.resourceId].toString(),
            incidentTypeId = typeResourceId,
            stage = IncidentFormStage.valueOf(row[NativeIncidentForms.stage]),
            version = row[NativeIncidentForms.version],
            name = row[NativeIncidentForms.name],
            fields = fields,
        )
    }

    private fun fieldDefinition(row: ResultRow): IncidentCustomFieldDefinition {
        val fieldId = row[NativeIncidentCustomFields.id].value
        val options =
            NativeIncidentCustomFieldOptions
                .selectAll()
                .where { NativeIncidentCustomFieldOptions.customFieldId eq fieldId }
                .orderBy(NativeIncidentCustomFieldOptions.position to SortOrder.ASC)
                .map {
                    IncidentCustomFieldOption(
                        id = it[NativeIncidentCustomFieldOptions.resourceId].toString(),
                        value = it[NativeIncidentCustomFieldOptions.value],
                        label = it[NativeIncidentCustomFieldOptions.label],
                        position = it[NativeIncidentCustomFieldOptions.position],
                        color = it[NativeIncidentCustomFieldOptions.color],
                    )
                }
        return IncidentCustomFieldDefinition(
            id = row[NativeIncidentCustomFields.resourceId].toString(),
            key = row[NativeIncidentCustomFields.stableKey],
            version = row[NativeIncidentCustomFields.version],
            name = row[NativeIncidentCustomFields.name],
            description = row[NativeIncidentCustomFields.description],
            valueType = IncidentCustomFieldValueType.valueOf(row[NativeIncidentCustomFields.valueType]),
            catalogResourceType = row[NativeIncidentCustomFields.catalogResourceType],
            options = options,
        )
    }

    private fun typeDefinition(row: ResultRow) =
        IncidentTypeDefinition(
            id = row[NativeIncidentTypes.resourceId].toString(),
            key = row[NativeIncidentTypes.stableKey],
            version = row[NativeIncidentTypes.version],
            name = row[NativeIncidentTypes.name],
            description = row[NativeIncidentTypes.description],
            enabled = row[NativeIncidentTypes.enabled],
        )

    private fun formSnapshot(form: IncidentFormDefinition): Map<String, JsonElement> =
        mapOf(
            "formId" to JsonPrimitive(form.id),
            "formVersion" to JsonPrimitive(form.version),
            "formName" to JsonPrimitive(form.name),
            "stage" to JsonPrimitive(form.stage.wire),
            "fields" to JsonArray(
                form.fields.map { formField ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(formField.field.id),
                            "key" to JsonPrimitive(formField.field.key),
                            "version" to JsonPrimitive(formField.field.version),
                            "name" to JsonPrimitive(formField.field.name),
                            "description" to formField.field.description.toJsonElement(),
                            "type" to JsonPrimitive(formField.field.valueType.wire),
                            "catalogResourceType" to formField.field.catalogResourceType.toJsonElement(),
                            "options" to JsonArray(formField.field.options.map(::optionSnapshot)),
                            "position" to JsonPrimitive(formField.position),
                            "required" to JsonPrimitive(formField.required),
                            "visible" to JsonPrimitive(formField.visible),
                            "defaultValue" to (formField.defaultValue ?: JsonNull),
                            "helpText" to formField.helpText.toJsonElement(),
                            "condition" to JsonObject(formField.condition),
                        ),
                    )
                },
            ),
        )

    private fun optionSnapshot(option: IncidentCustomFieldOption): JsonElement =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(option.id),
                "value" to JsonPrimitive(option.value),
                "label" to JsonPrimitive(option.label),
                "position" to JsonPrimitive(option.position),
                "color" to option.color.toJsonElement(),
            ),
        )

    private fun typeSnapshot(type: ResultRow?): Map<String, JsonElement> =
        if (type == null) {
            emptyMap()
        } else {
            mapOf(
                "incidentTypeId" to JsonPrimitive(type[NativeIncidentTypes.resourceId].toString()),
                "incidentTypeVersion" to JsonPrimitive(type[NativeIncidentTypes.version]),
                "incidentTypeName" to JsonPrimitive(type[NativeIncidentTypes.name]),
            )
        }

    private fun currentType(organizationId: Int, key: String): ResultRow? =
        NativeIncidentTypes
            .selectAll()
            .where {
                (NativeIncidentTypes.organizationId eq organizationId) and
                    (NativeIncidentTypes.stableKey eq key) and
                    (NativeIncidentTypes.isCurrent eq true)
            }.singleOrNull()

    private fun currentField(organizationId: Int, key: String): ResultRow? =
        NativeIncidentCustomFields
            .selectAll()
            .where {
                (NativeIncidentCustomFields.organizationId eq organizationId) and
                    (NativeIncidentCustomFields.stableKey eq key) and
                    (NativeIncidentCustomFields.isCurrent eq true)
            }.singleOrNull()

    private fun currentForm(
        organizationId: Int,
        typeId: Int?,
        stage: IncidentFormStage,
    ): ResultRow? =
        NativeIncidentForms
            .selectAll()
            .where {
                (NativeIncidentForms.organizationId eq organizationId) and
                    typePredicate(typeId) and
                    (NativeIncidentForms.stage eq stage.wire) and
                    (NativeIncidentForms.isCurrent eq true)
            }.singleOrNull()

    private fun typePredicate(typeId: Int?) =
        typeId?.let { NativeIncidentForms.incidentTypeId eq it } ?: NativeIncidentForms.incidentTypeId.isNull()

    private fun requireType(
        organizationId: Int,
        resourceId: String,
        currentOnly: Boolean,
    ): ResultRow {
        val uuid = resourceId.toUuidOrNull() ?: throw IllegalArgumentException("Invalid incident type ID")
        var query =
            NativeIncidentTypes
                .selectAll()
                .where {
                    (NativeIncidentTypes.organizationId eq organizationId) and
                        (NativeIncidentTypes.resourceId eq uuid)
                }
        if (currentOnly) query = query.andWhere { NativeIncidentTypes.isCurrent eq true }
        return query.singleOrNull() ?: throw IncidentCommandNotFoundException("Incident type not found")
    }

    private fun requireField(
        organizationId: Int,
        resourceId: String,
        currentOnly: Boolean,
    ): ResultRow {
        val uuid = resourceId.toUuidOrNull() ?: throw IllegalArgumentException("Invalid custom field ID")
        var query =
            NativeIncidentCustomFields
                .selectAll()
                .where {
                    (NativeIncidentCustomFields.organizationId eq organizationId) and
                        (NativeIncidentCustomFields.resourceId eq uuid)
                }
        if (currentOnly) query = query.andWhere { NativeIncidentCustomFields.isCurrent eq true }
        return query.singleOrNull() ?: throw IncidentCommandNotFoundException("Incident custom field not found")
    }

    private fun requireFieldRow(id: Int): ResultRow =
        NativeIncidentCustomFields
            .selectAll()
            .where { NativeIncidentCustomFields.id eq id }
            .single()

    private fun requireFormRow(id: Int): ResultRow =
        NativeIncidentForms
            .selectAll()
            .where { NativeIncidentForms.id eq id }
            .single()

    private fun requireMember(organizationId: Int, userId: Int) {
        val member =
            Memberships
                .selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and
                        (Memberships.user_id eq userId)
                }.limit(1)
                .singleOrNull()
        require(member != null) { "Actor is not a member of the incident organization" }
    }

    private fun ResultRow.supersede(
        currentColumn: org.jetbrains.exposed.v1.core.Column<Boolean>,
        supersededColumn: org.jetbrains.exposed.v1.core.Column<kotlin.time.Instant?>,
    ) {
        val tableId = when (currentColumn.table) {
            NativeIncidentTypes -> NativeIncidentTypes.id
            NativeIncidentCustomFields -> NativeIncidentCustomFields.id
            NativeIncidentForms -> NativeIncidentForms.id
            else -> error("Unsupported versioned incident definition")
        }
        currentColumn.table.update({ tableId eq this[tableId] }) {
            it[currentColumn] = false
            it[supersededColumn] = Clock.System.now()
        }
    }

    private fun normalizeKey(value: String): String {
        val normalized = value.trim().lowercase().replace(KEY_SEPARATOR_PATTERN, "_").trim('_')
        require(normalized.matches(KEY_PATTERN)) { "Incident definition key is invalid" }
        return normalized
    }

    private fun requireName(value: String, label: String, maxLength: Int) {
        require(value.isNotBlank()) { "$label is required" }
        require(value.trim().length <= maxLength) { "$label is too long" }
    }

    private fun validateFieldOptions(
        valueType: IncidentCustomFieldValueType,
        options: List<IncidentCustomFieldOptionInput>,
    ) {
        val supportsOptions = valueType in OPTION_FIELD_TYPES
        require(supportsOptions || options.isEmpty()) { "Only select incident fields accept options" }
        require(!supportsOptions || options.isNotEmpty()) { "Select incident fields require options" }
        require(options.all { it.value.isNotBlank() && it.label.isNotBlank() && it.position >= 0 }) {
            "Incident field options are invalid"
        }
        require(options.all { it.value.trim().length <= MAX_CUSTOM_FIELD_OPTION_LENGTH }) {
            "Incident field option value is too long"
        }
        require(options.all { it.label.trim().length <= MAX_CUSTOM_FIELD_OPTION_LENGTH }) {
            "Incident field option label is too long"
        }
        require(options.all { (it.color.cleaned()?.length ?: 0) <= MAX_OPTION_COLOR_LENGTH }) {
            "Incident field option color is too long"
        }
        require(options.map { it.value.trim() }.distinct().size == options.size) {
            "Incident field option values must be unique"
        }
        require(options.map(IncidentCustomFieldOptionInput::position).distinct().size == options.size) {
            "Incident field option positions must be unique"
        }
    }

    private fun requireString(value: JsonElement): String {
        val primitive = value as? JsonPrimitive
        require(primitive != null && primitive.isString && primitive.contentOrNull != null) {
            "Incident field requires a string"
        }
        return primitive.content
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    private fun String?.toJsonElement(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    companion object {
        private const val MAX_INCIDENT_TYPE_NAME_LENGTH = 120
        private const val MAX_CUSTOM_FIELD_NAME_LENGTH = 160
        private const val MAX_INCIDENT_FORM_NAME_LENGTH = 160
        private const val MAX_CATALOG_RESOURCE_TYPE_LENGTH = 120
        private const val MAX_CUSTOM_FIELD_OPTION_LENGTH = 160
        private const val MAX_OPTION_COLOR_LENGTH = 32
        private const val UNKNOWN_OPTION_MESSAGE = "Incident field includes an unknown option"
        private val KEY_SEPARATOR_PATTERN = Regex("[^a-z0-9]+")
        private val KEY_PATTERN = Regex("[a-z][a-z0-9_]{0,99}")
        private val OPTION_FIELD_TYPES =
            setOf(IncidentCustomFieldValueType.SELECT, IncidentCustomFieldValueType.MULTI_SELECT)
    }
}
