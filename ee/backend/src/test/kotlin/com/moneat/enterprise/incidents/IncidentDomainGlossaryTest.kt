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
            setOf("TRIAGE", "ACTIVE", "RESOLVED", "POST_INCIDENT", "CLOSED", "CANCELLED", "DECLINED", "MERGED"),
            NativeIncidentStatus.entries.mapTo(mutableSetOf(), NativeIncidentStatus::wire),
        )
        assertEquals(
            setOf(NativeIncidentStatus.MERGED),
            NativeIncidentStatus.entries.filterTo(mutableSetOf(), NativeIncidentStatus::terminal),
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
            ),
        )

        assertTrue(encoded.contains("\"id\":\"8d62e71c-ab52-4d99-9f34-16404187d7ce\""))
        assertTrue(encoded.contains("\"organizationId\":\"8eb53cb5-c34c-49af-827d-956abf96394c\""))
        assertFalse(encoded.contains("internalId"))
        assertFalse(encoded.contains("\"organizationId\":7"))
        assertFalse(encoded.contains("\"declaredBy\":11"))
    }

    @Test
    fun `triage incidents serialize without a severity and merged incidents carry their target`() {
        val triage = Json.encodeToString(
            OnCallIncident(
                id = "0f0d6d31-1d3f-4a0b-8a06-2fd1a4c0f9a1",
                organizationResourceId = "8eb53cb5-c34c-49af-827d-956abf96394c",
                title = "Unclassified report",
                status = NativeIncidentStatus.TRIAGE.wire,
                declaredByResourceId = "b0c4fec4-7257-42d7-83aa-a32329cbec6f",
                declaredAt = "2026-08-22T20:00:00Z",
                createdAt = "2026-08-22T20:00:00Z",
                updatedAt = "2026-08-22T20:00:00Z",
            ),
        )
        assertFalse(triage.contains("\"severity\""))

        val merged = Json.encodeToString(
            OnCallIncident(
                id = "0f0d6d31-1d3f-4a0b-8a06-2fd1a4c0f9a1",
                organizationResourceId = "8eb53cb5-c34c-49af-827d-956abf96394c",
                title = "Duplicate report",
                severity = "SEV-2",
                status = NativeIncidentStatus.MERGED.wire,
                declaredByResourceId = "b0c4fec4-7257-42d7-83aa-a32329cbec6f",
                declaredAt = "2026-08-22T20:00:00Z",
                mergedAt = "2026-08-22T21:00:00Z",
                mergedIntoIncidentResourceId = "5f9a0a0c-4d1f-4c1a-9d6f-2c1f8ba4b0d2",
                createdAt = "2026-08-22T20:00:00Z",
                updatedAt = "2026-08-22T21:00:00Z",
            ),
        )
        assertTrue(merged.contains("\"status\":\"MERGED\""))
        assertTrue(merged.contains("\"mergedAt\":\"2026-08-22T21:00:00Z\""))
        assertTrue(merged.contains("\"mergedIntoIncidentId\":\"5f9a0a0c-4d1f-4c1a-9d6f-2c1f8ba4b0d2\""))
    }
}
