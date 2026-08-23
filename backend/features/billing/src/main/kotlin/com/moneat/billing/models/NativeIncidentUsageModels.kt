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

package com.moneat.billing.models

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.jsonb
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock

object NativeIncidentPlanEntitlements : Table("native_incident_plan_entitlements") {
    val tierName = varchar("tier_name", 50)
    val enabled = bool("enabled").default(false)
    val quotas = jsonb("quotas")
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    override val primaryKey = PrimaryKey(tierName)
}

object NativeIncidentUsageEvents : Table("native_incident_usage_events") {
    val id = long("id").autoIncrement()
    val organizationId = integer("organization_id").references(Organizations.id)
    val quotaKey = varchar("quota_key", 80)
    val windowKey = varchar("window_key", 20)
    val idempotencyKey = varchar("idempotency_key", 200)
    val quantity = long("quantity")
    val recordedAt = timestamp("recorded_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(organizationId, quotaKey, windowKey, idempotencyKey)
    }

    override val primaryKey = PrimaryKey(id)
}

object NativeIncidentUsageCounters : Table("native_incident_usage_counters") {
    val id = long("id").autoIncrement()
    val organizationId = integer("organization_id").references(Organizations.id)
    val quotaKey = varchar("quota_key", 80)
    val windowKey = varchar("window_key", 20)
    val used = long("used").default(0)
    val limitSnapshot = long("limit_snapshot")
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }

    init {
        uniqueIndex(organizationId, quotaKey, windowKey)
    }

    override val primaryKey = PrimaryKey(id)
}
