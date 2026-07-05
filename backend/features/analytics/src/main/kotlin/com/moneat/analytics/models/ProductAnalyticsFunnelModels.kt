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

package com.moneat.analytics.models

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import com.moneat.shared.models.jsonb
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock
import kotlin.uuid.Uuid

object ProductAnalyticsFunnels : Table("product_analytics_funnels") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id)
    val projectId = long("project_id").references(Projects.id)
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val stepsJson = jsonb("steps_json").default("[]")
    val filtersJson = jsonb("filters_json").default("[]")
    val propFiltersJson = jsonb("prop_filters_json").default("[]")
    val groupBy = varchar("group_by", 32).default("session_id")
    val sourceFilter = varchar("source", 255).nullable()
    val createdBy = integer("created_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    val updatedAt = timestamp("updated_at").clientDefault { Clock.System.now() }
    val archivedAt = timestamp("archived_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class SavedProductFunnel(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String? = null,
    val steps: List<String>,
    val filters: List<AnalyticsFilter> = emptyList(),
    val propFilters: List<EventPropertyFilter> = emptyList(),
    val groupBy: String,
    val source: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

data class SavedProductFunnelCreateRequest(
    val projectId: Long,
    val name: String,
    val description: String?,
    val steps: List<String>,
    val filters: List<AnalyticsFilter>,
    val propFilters: List<EventPropertyFilter>,
    val groupBy: String,
    val source: String?,
)

data class SavedProductFunnelUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val steps: List<String>? = null,
    val filters: List<AnalyticsFilter>? = null,
    val propFilters: List<EventPropertyFilter>? = null,
    val groupBy: String? = null,
    val source: String? = null,
)

@Serializable
data class SavedProductFunnelListResponse(
    val funnels: List<SavedProductFunnel>,
)

@Serializable
data class SavedProductFunnelDeleteResponse(
    val id: String,
    val deleted: Boolean,
)
