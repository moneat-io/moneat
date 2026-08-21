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

package com.moneat.alerts.services

import com.moneat.shared.models.AlertSilencePeriods
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Instant

class AlertSilenceService {
    fun isOrganizationSilenced(
        organizationId: Int,
        now: Instant = Clock.System.now()
    ): Boolean =
        transaction {
            AlertSilencePeriods
                .selectAll()
                .where {
                    (AlertSilencePeriods.organization_id eq organizationId) and
                        (AlertSilencePeriods.starts_at lessEq now) and
                        (AlertSilencePeriods.ends_at greaterEq now)
                }
                .limit(1)
                .any()
        }
}
