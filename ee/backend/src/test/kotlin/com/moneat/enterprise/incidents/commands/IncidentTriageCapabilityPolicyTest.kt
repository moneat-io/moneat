// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.commands

import com.moneat.enterprise.incidents.models.NativeIncidentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncidentTriageCapabilityPolicyTest {
    @Test
    fun `triage permits investigation roles participation and escalation`() {
        listOf(
            IncidentCapability.LIFECYCLE,
            IncidentCapability.INVESTIGATION,
            IncidentCapability.ROLES,
            IncidentCapability.PARTICIPATION,
            IncidentCapability.ESCALATION,
        ).forEach { capability ->
            assertTrue(IncidentTriageCapabilityPolicy.permits(capability), capability.wire)
        }
    }

    @Test
    fun `triage withholds actions follow-ups and status-page communication`() {
        listOf(
            IncidentCapability.ACTIONS,
            IncidentCapability.FOLLOW_UPS,
            IncidentCapability.STATUS_PAGE_COMMUNICATION,
        ).forEach { capability ->
            assertFalse(IncidentTriageCapabilityPolicy.permits(capability), capability.wire)
        }
    }

    @Test
    fun `command types map onto their capability group`() {
        assertEquals(
            IncidentCapability.ACTIONS,
            IncidentTriageCapabilityPolicy.capabilityOf(IncidentCommandType.ADD_ACTION),
        )
        assertEquals(
            IncidentCapability.INVESTIGATION,
            IncidentTriageCapabilityPolicy.capabilityOf(IncidentCommandType.LINK_SOURCE),
        )
        assertEquals(
            IncidentCapability.ROLES,
            IncidentTriageCapabilityPolicy.capabilityOf(IncidentCommandType.CLAIM_ROLE),
        )
        assertEquals(
            IncidentCapability.PARTICIPATION,
            IncidentTriageCapabilityPolicy.capabilityOf(IncidentCommandType.OBSERVE),
        )
        assertEquals(
            IncidentCapability.LIFECYCLE,
            IncidentTriageCapabilityPolicy.capabilityOf(IncidentCommandType.DECLINE),
        )
    }

    @Test
    fun `restrictions apply only while the incident is in triage`() {
        assertFailsWith<IncidentCommandDeniedException> {
            IncidentTriageCapabilityPolicy.requireAllowed(
                NativeIncidentStatus.TRIAGE,
                IncidentCommandType.ADD_ACTION,
            )
        }
        NativeIncidentStatus.entries
            .filter { it != NativeIncidentStatus.TRIAGE }
            .forEach { status ->
                IncidentTriageCapabilityPolicy.requireAllowed(status, IncidentCommandType.ADD_ACTION)
            }
        IncidentTriageCapabilityPolicy.requireAllowed(
            NativeIncidentStatus.TRIAGE,
            IncidentCommandType.ASSIGN_ROLE,
        )
    }
}
