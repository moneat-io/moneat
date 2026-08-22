// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.models

import com.moneat.enterprise.oncall.models.OnCallIncidents
import com.moneat.enterprise.oncall.models.jsonb
import com.moneat.enterprise.oncall.models.requiredJsonb
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

@Serializable
enum class IncidentCustomFieldValueType(val wire: String) {
    SELECT("SELECT"),
    MULTI_SELECT("MULTI_SELECT"),
    TEXT("TEXT"),
    NUMBER("NUMBER"),
    LINK("LINK"),
    USER("USER"),
    TEAM("TEAM"),
    SERVICE("SERVICE"),
    CATALOG_RESOURCE("CATALOG_RESOURCE"),
}

@Serializable
enum class IncidentFormStage(val wire: String) {
    DECLARATION("DECLARATION"),
    ACCEPTANCE("ACCEPTANCE"),
    UPDATE("UPDATE"),
    RESOLUTION("RESOLUTION"),
    ESCALATION("ESCALATION"),
}

object NativeIncidentTypes : IntIdTable("native_incident_types") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val stableKey = varchar("stable_key", 100)
    val version = integer("version")
    val isCurrent = bool("is_current").default(true)
    val name = varchar("name", 120)
    val description = text("description").nullable()
    val enabled = bool("enabled").default(true)
    val createdBy = integer("created_by").references(Users.id)
    val createdAt = timestamp("created_at")
    val supersededAt = timestamp("superseded_at").nullable()

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, stableKey, version)
    }
}

object NativeIncidentCustomFields : IntIdTable("native_incident_custom_fields") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val stableKey = varchar("stable_key", 100)
    val version = integer("version")
    val isCurrent = bool("is_current").default(true)
    val name = varchar("name", 160)
    val description = text("description").nullable()
    val valueType = varchar("value_type", 32)
    val catalogResourceType = varchar("catalog_resource_type", 120).nullable()
    val createdBy = integer("created_by").references(Users.id)
    val createdAt = timestamp("created_at")
    val supersededAt = timestamp("superseded_at").nullable()

    init {
        uniqueIndex(organizationId, resourceId)
        uniqueIndex(organizationId, stableKey, version)
    }
}

object NativeIncidentCustomFieldOptions : IntIdTable("native_incident_custom_field_options") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val customFieldId =
        integer("custom_field_id").references(NativeIncidentCustomFields.id, onDelete = ReferenceOption.CASCADE)
    val value = varchar("value", 160)
    val label = varchar("label", 160)
    val position = integer("position")
    val color = varchar("color", 32).nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(customFieldId, resourceId)
        uniqueIndex(customFieldId, value)
        uniqueIndex(customFieldId, position)
    }
}

object NativeIncidentForms : IntIdTable("native_incident_forms") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentTypeId = integer("incident_type_id").references(NativeIncidentTypes.id).nullable()
    val stage = varchar("stage", 32)
    val version = integer("version")
    val isCurrent = bool("is_current").default(true)
    val name = varchar("name", 160)
    val createdBy = integer("created_by").references(Users.id)
    val createdAt = timestamp("created_at")
    val supersededAt = timestamp("superseded_at").nullable()

    init {
        uniqueIndex(organizationId, resourceId)
    }
}

object NativeIncidentFormFields : IntIdTable("native_incident_form_fields") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val formId = integer("form_id").references(NativeIncidentForms.id, onDelete = ReferenceOption.CASCADE)
    val customFieldId = integer("custom_field_id").references(NativeIncidentCustomFields.id)
    val position = integer("position")
    val isVisible = bool("is_visible").default(true)
    val isRequired = bool("is_required").default(false)
    val defaultValue = jsonb("default_value")
    val helpText = text("help_text").nullable()
    val conditionExpression = requiredJsonb("condition_expression").clientDefault { emptyMap() }
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex(formId, resourceId)
        uniqueIndex(formId, customFieldId)
        uniqueIndex(formId, position)
    }
}

object NativeIncidentFormSubmissions : IntIdTable("native_incident_form_submissions") {
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val incidentId = integer("incident_id").references(OnCallIncidents.id, onDelete = ReferenceOption.CASCADE)
    val formId = integer("form_id").references(NativeIncidentForms.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val stage = varchar("stage", 32)
    val definitionSnapshot = requiredJsonb("definition_snapshot")
    val valuesSnapshot = requiredJsonb("values_snapshot")
    val submittedBy = integer("submitted_by").references(Users.id)
    val submittedAt = timestamp("submitted_at")

    init {
        uniqueIndex(organizationId, resourceId)
    }
}

