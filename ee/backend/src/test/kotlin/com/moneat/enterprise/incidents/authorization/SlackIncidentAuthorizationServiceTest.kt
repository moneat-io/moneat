// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.authorization

import com.moneat.enterprise.incidents.models.NativeIncidentVisibility
import com.moneat.notifications.services.SlackIdentityResolution
import com.moneat.notifications.services.SlackIdentityStatus
import com.moneat.org.services.OrgRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackIncidentAuthorizationServiceTest {
    private val service = SlackIncidentAuthorizationService()

    @Test
    fun `mapped organization member can read organization incident`() {
        val decision = service.authorize(
            request(
                status = SlackIdentityStatus.MAPPED,
                role = OrgRole.MEMBER,
                organizationId = 42,
                visibility = NativeIncidentVisibility.ORGANIZATION,
                action = SlackIncidentAction.READ,
            ),
        )

        assertTrue(decision.allowed)
        assertEquals(SlackIncidentAccessStatus.ALLOWED, decision.status)
    }

    @Test
    fun `unmapped member receives linking response without incident access`() {
        val decision = service.authorize(
            request(
                status = SlackIdentityStatus.UNMAPPED,
                role = null,
                organizationId = null,
                visibility = NativeIncidentVisibility.PRIVATE,
                action = SlackIncidentAction.READ,
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(SlackIncidentAccessStatus.LINK_REQUIRED, decision.status)
        assertTrue(decision.message.contains("link", ignoreCase = true))
    }

    @Test
    fun `cross organization identity fails closed without revealing incident state`() {
        val decision = service.authorize(
            request(
                status = SlackIdentityStatus.CROSS_ORGANIZATION,
                role = OrgRole.ADMIN,
                organizationId = 42,
                visibility = NativeIncidentVisibility.PUBLIC,
                action = SlackIncidentAction.READ,
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(SlackIncidentAccessStatus.FORBIDDEN, decision.status)
        assertTrue(decision.message.contains("not authorized", ignoreCase = true))
    }

    @Test
    fun `organization admin can respond to private incident`() {
        val decision = service.authorize(
            request(
                status = SlackIdentityStatus.MAPPED,
                role = OrgRole.ADMIN,
                organizationId = 42,
                visibility = NativeIncidentVisibility.PRIVATE,
                action = SlackIncidentAction.RESPOND,
            ),
        )

        assertTrue(decision.allowed)
    }

    private fun request(
        status: SlackIdentityStatus,
        role: OrgRole?,
        organizationId: Int?,
        visibility: NativeIncidentVisibility,
        action: SlackIncidentAction,
    ): SlackIncidentAccessRequest =
        SlackIncidentAccessRequest(
            identity = SlackIdentityResolution(
                status = status,
                organizationId = organizationId,
                userId = organizationId,
                role = role,
                teamId = "T-test",
                message = "test",
            ),
            organizationId = 42,
            incidentId = 7,
            visibility = visibility,
            action = action,
        )
}
