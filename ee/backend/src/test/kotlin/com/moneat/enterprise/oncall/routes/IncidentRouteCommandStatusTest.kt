// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.routes

import com.moneat.enterprise.incidents.commands.IncidentCommandConflictException
import com.moneat.enterprise.incidents.commands.IncidentCommandDeniedException
import com.moneat.enterprise.incidents.commands.IncidentCommandException
import com.moneat.enterprise.incidents.commands.IncidentCommandNotFoundException
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class IncidentRouteCommandStatusTest {
    @Test
    fun `incident command failures preserve transport semantics`() {
        assertEquals(HttpStatusCode.Forbidden, incidentCommandStatus(IncidentCommandDeniedException("denied")))
        assertEquals(HttpStatusCode.NotFound, incidentCommandStatus(IncidentCommandNotFoundException("missing")))
        assertEquals(HttpStatusCode.Conflict, incidentCommandStatus(IncidentCommandConflictException("stale")))
        assertEquals(HttpStatusCode.BadRequest, incidentCommandStatus(IncidentCommandException("invalid")))
    }
}
