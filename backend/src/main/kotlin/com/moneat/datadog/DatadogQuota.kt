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

package com.moneat.datadog

import com.moneat.billing.services.BillingQuotaService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

internal suspend fun reserveDatadogQuota(
    call: ApplicationCall,
    quotaService: BillingQuotaService,
    organizationId: Int,
    requestedUnits: Int,
    eventType: String,
    requestedBytes: Long,
): Boolean {
    if (!quotaService.isEnforcementEnabled()) {
        return true
    }

    val reservation = quotaService.reserveUnits(
        organizationId = organizationId,
        requestedUnits = requestedUnits,
        eventType = eventType,
        requestedBytes = requestedBytes,
    )
    if (reservation.allowed) {
        return true
    }

    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "quota_exceeded"))
    return false
}
