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
import com.moneat.billing.services.QuotaExceededResponse
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

    call.respond(
        HttpStatusCode.TooManyRequests,
        QuotaExceededResponse(reason = reservation.reason, usage = reservation.usage)
    )
    return false
}

internal suspend fun reserveDatadogQuotaBatch(
    call: ApplicationCall,
    quotaService: BillingQuotaService,
    organizationId: Int,
    requestedUnitsByType: Map<String, Int>,
    requestedBytesByType: Map<String, Long>,
): Boolean {
    if (!quotaService.isEnforcementEnabled()) {
        return true
    }

    val reservation = quotaService.reserveUnitsBatch(
        organizationId = organizationId,
        requestedUnitsByType = requestedUnitsByType,
        requestedBytesByType = requestedBytesByType,
    )
    if (reservation.allowed) {
        return true
    }

    call.respond(
        HttpStatusCode.TooManyRequests,
        QuotaExceededResponse(reason = reservation.reason, usage = reservation.usage)
    )
    return false
}
