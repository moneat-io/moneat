// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.config

import com.moneat.enterprise.incidents.IncidentTestDatabase
import com.moneat.enterprise.incidents.SeededMember
import com.moneat.enterprise.incidents.models.IncidentCustomFieldValueType
import com.moneat.enterprise.incidents.models.IncidentFormStage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncidentConfigurationServiceTest {
    private lateinit var member: SeededMember
    private lateinit var service: IncidentConfigurationService

    @BeforeEach
    fun setUp() {
        IncidentTestDatabase.reset()
        member = IncidentTestDatabase.seedMember()
        service = IncidentConfigurationService()
    }

    @AfterEach
    fun tearDown() {
        IncidentTestDatabase.clearReference()
    }

    @Test
    fun `versions incident types and snapshots validated declaration forms`() {
        val firstType =
            service.createIncidentType(
                organizationId = member.organizationId,
                actorUserId = member.userId,
                request = CreateIncidentType(
                    stableKey = "customer-impacting",
                    name = "Customer impacting",
                    description = "Customer-facing incidents",
                    enabled = true,
                ),
            )
        val currentType =
            service.createIncidentType(
                organizationId = member.organizationId,
                actorUserId = member.userId,
                request = CreateIncidentType(
                    stableKey = "customer-impacting",
                    name = "Customer impact",
                    description = "Updated definition",
                    enabled = true,
                ),
            )
        assertEquals(1, firstType.version)
        assertEquals(2, currentType.version)
        assertEquals(listOf(currentType), service.listIncidentTypes(member.organizationId))

        val impactField =
            service.createCustomField(
                member.organizationId,
                member.userId,
                CreateIncidentCustomField(
                    stableKey = "customer-impact",
                    name = "Customer impact",
                    description = null,
                    valueType = IncidentCustomFieldValueType.SELECT,
                    catalogResourceType = null,
                    options = listOf(
                        IncidentCustomFieldOptionInput("none", "None", 0),
                        IncidentCustomFieldOptionInput("degraded", "Degraded", 1),
                    ),
                ),
            )
        val form =
            service.createForm(
                organizationId = member.organizationId,
                actorUserId = member.userId,
                request = CreateIncidentForm(
                    incidentTypeResourceId = currentType.id,
                    stage = IncidentFormStage.DECLARATION,
                    name = "Declare customer incident",
                    fields = listOf(
                        IncidentFormFieldInput(
                            fieldId = impactField.id,
                            position = 0,
                            required = true,
                            defaultValue = JsonPrimitive("none"),
                            helpText = "Choose the observed impact.",
                        ),
                    ),
                ),
            )
        val resolved =
            service.resolveForm(
                member.organizationId,
                currentType.id,
                IncidentFormStage.DECLARATION,
                mapOf(impactField.key to JsonPrimitive("degraded")),
            )

        assertEquals(form.id, resolved.definitionSnapshot["formId"]?.let { (it as JsonPrimitive).content })
        assertEquals(JsonPrimitive("degraded"), resolved.values[impactField.key])
        assertTrue(resolved.formDefinitionId != null)
        assertEquals("Customer impact", resolved.incidentTypeName)
        val snapshottedField =
            ((resolved.definitionSnapshot.getValue("fields") as JsonArray).single() as JsonObject)
        assertEquals(JsonPrimitive(impactField.version), snapshottedField["version"])
        assertEquals(JsonPrimitive("none"), snapshottedField["defaultValue"])
        val snapshottedOptions = snapshottedField["options"] as JsonArray
        assertEquals(
            listOf("None", "Degraded"),
            snapshottedOptions.map { option ->
                ((option as JsonObject).getValue("label") as JsonPrimitive).content
            },
        )
    }

    @Test
    fun `rejects unknown options and fields`() {
        val field =
            service.createCustomField(
                member.organizationId,
                member.userId,
                CreateIncidentCustomField(
                    stableKey = "impact",
                    name = "Impact",
                    description = null,
                    valueType = IncidentCustomFieldValueType.SELECT,
                    catalogResourceType = null,
                    options = listOf(IncidentCustomFieldOptionInput("minor", "Minor", 0)),
                ),
            )
        service.createForm(
            member.organizationId,
            member.userId,
            CreateIncidentForm(
                incidentTypeResourceId = null,
                stage = IncidentFormStage.DECLARATION,
                name = "Default declaration",
                fields = listOf(IncidentFormFieldInput(field.id, 0, required = true)),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            service.resolveForm(
                member.organizationId,
                null,
                IncidentFormStage.DECLARATION,
                mapOf("impact" to JsonPrimitive("major")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.resolveForm(
                member.organizationId,
                null,
                IncidentFormStage.DECLARATION,
                mapOf("unknown" to JsonPrimitive("value")),
            )
        }
    }
}
