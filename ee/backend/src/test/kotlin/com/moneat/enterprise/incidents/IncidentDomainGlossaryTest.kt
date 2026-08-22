// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents

import com.moneat.enterprise.incidents.models.IncidentStateOwner
import com.moneat.enterprise.incidents.models.NativeIncidentMode
import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.enterprise.oncall.models.OnCallIncident
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncidentDomainGlossaryTest {
    @Test
    fun `canonical names keep native and forwarded incident state distinct`() {
        assertEquals(
            IncidentDomainObject.NATIVE_INCIDENT,
            IncidentDomainGlossary.requireCanonicalApiName("native_incident"),
        )
        assertEquals(
            IncidentDomainObject.FORWARDED_PROVIDER_INCIDENT,
            IncidentDomainGlossary.requireCanonicalApiName("forwarded_provider_incident"),
        )
        assertFailsWith<IllegalArgumentException> {
            IncidentDomainGlossary.requireCanonicalApiName("incident")
        }
    }

    @Test
    fun `canonical lifecycle mode visibility and ownership values are explicit`() {
        assertEquals(
            setOf("TRIAGE", "ACTIVE", "RESOLVED", "POST_INCIDENT", "CLOSED", "CANCELLED", "DECLINED"),
            NativeIncidentStatus.entries.mapTo(mutableSetOf(), NativeIncidentStatus::wire),
        )
        assertEquals(
            setOf("LIVE", "RETROSPECTIVE", "TEST"),
            NativeIncidentMode.entries.mapTo(mutableSetOf(), NativeIncidentMode::wire),
        )
        assertEquals(
            setOf("ORGANIZATION", "PRIVATE", "PUBLIC"),
            NativeIncidentVisibility.entries.mapTo(mutableSetOf(), NativeIncidentVisibility::wire),
        )
        assertEquals(
            setOf("INCIDENT", "ALERT", "ALERT_EPISODE"),
            IncidentStateOwner.entries.mapTo(mutableSetOf(), IncidentStateOwner::wire),
        )
    }

    @Test
    fun `native incident response serializes opaque public ids only`() {
        val encoded = Json.encodeToString(
            OnCallIncident(
                id = "8d62e71c-ab52-4d99-9f34-16404187d7ce",
                organizationResourceId = "8eb53cb5-c34c-49af-827d-956abf96394c",
                title = "Checkout unavailable",
                severity = "SEV-1",
                status = "ACTIVE",
                declaredByResourceId = "b0c4fec4-7257-42d7-83aa-a32329cbec6f",
                declaredAt = "2026-08-21T20:00:00Z",
                createdAt = "2026-08-21T20:00:00Z",
                updatedAt = "2026-08-21T20:00:00Z",
            ).apply {
                internalId = 42
                organizationId = 7
                declaredBy = 11
            },
        )

        assertTrue(encoded.contains("\"id\":\"8d62e71c-ab52-4d99-9f34-16404187d7ce\""))
        assertTrue(encoded.contains("\"organizationId\":\"8eb53cb5-c34c-49af-827d-956abf96394c\""))
        assertFalse(encoded.contains("internalId"))
        assertFalse(encoded.contains("\"organizationId\":7"))
        assertFalse(encoded.contains("\"declaredBy\":11"))
    }
}
