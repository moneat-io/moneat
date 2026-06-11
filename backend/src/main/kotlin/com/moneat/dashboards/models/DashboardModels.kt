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

package com.moneat.dashboards.models

import com.moneat.shared.models.Users
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.postgresql.util.PGobject
import kotlin.uuid.Uuid

// Custom JSONB column type for PostgreSQL
class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: ""
        is String -> value
        else -> value.toString()
    }

    override fun notNullValueToDB(value: String): Any {
        val pgObject = PGobject()
        pgObject.type = "jsonb"
        pgObject.value = value
        return pgObject
    }
}

fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

// Dashboard variable model

@Serializable
data class DashboardVariable(
    val name: String,
    val label: String? = null,
    val type: String = "custom",
    val query: String? = null,
    @SerialName("default_value") val defaultValue: String? = null,
    val current: String? = null,
    val options: List<String> = emptyList(),
    val datasource: String? = null,
    /** When true, the variable accepts several values at once (stored as a comma-joined string). */
    val multi: Boolean = false,
    /** When true, an "All" choice is offered (selected value `${'$'}__all`). */
    @SerialName("include_all") val includeAll: Boolean = false
)

// Exposed table definitions

object DashboardFolders : Table("dashboard_folders") {
    val id = long("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val orgId = long("org_id")
    val name = varchar("name", 100)
    val color = varchar("color", 7).nullable()
    val sortOrder = integer("sort_order").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Dashboards : Table("dashboards") {
    val id = long("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val orgId = long("org_id")
    val projectId = long("project_id").nullable()
    val folderId = long("folder_id").references(DashboardFolders.id).nullable()
    val title = varchar("title", 255)
    val description = text("description").nullable()
    val layoutType = varchar("layout_type", 20).default("grid")
    val isDefault = bool("is_default").default(false)
    val variables = jsonb("variables").default("[]")
    val createdBy = long("created_by")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object DashboardFavorites : Table("dashboard_favorites") {
    val userId = integer("user_id").references(Users.id)
    val dashboardId = long("dashboard_id").references(Dashboards.id)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(userId, dashboardId)
}

object DashboardWidgets : Table("dashboard_widgets") {
    val id = long("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val dashboardId = long("dashboard_id").references(Dashboards.id)
    val title = varchar("title", 255).nullable()
    val widgetType = varchar("widget_type", 50)
    val gridX = integer("grid_x").default(0)
    val gridY = integer("grid_y").default(0)
    val gridW = integer("grid_w").default(6)
    val gridH = integer("grid_h").default(4)
    val queryConfig = jsonb("query_config")
    val queryConfigs = jsonb("query_configs")
    val displayConfig = jsonb("display_config")
    val sortOrder = integer("sort_order").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

// API response/request data classes

@Serializable
data class DashboardResponse(
    val id: String,
    @SerialName("org_id") val orgId: Long,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    val title: String,
    val description: String? = null,
    @SerialName("layout_type") val layoutType: String = "grid",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("is_favorited") val isFavorited: Boolean = false,
    val variables: List<DashboardVariable> = emptyList(),
    @SerialName("created_by") val createdBy: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val widgets: List<WidgetResponse> = emptyList()
)

@Serializable
data class WidgetResponse(
    val id: String,
    @SerialName("dashboard_id") val dashboardId: String,
    val title: String? = null,
    @SerialName("widget_type") val widgetType: String,
    @SerialName("grid_x") val gridX: Int = 0,
    @SerialName("grid_y") val gridY: Int = 0,
    @SerialName("grid_w") val gridW: Int = 6,
    @SerialName("grid_h") val gridH: Int = 4,
    @SerialName("query_configs") val queryConfigs: List<QueryDsl> = emptyList(),
    @SerialName("display_config") val displayConfig: Map<String, String> = emptyMap(),
    @SerialName("sort_order") val sortOrder: Int = 0
)

@Serializable
data class CreateDashboardRequest(
    val title: String,
    val description: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("layout_type") val layoutType: String = "grid",
    @SerialName("is_default") val isDefault: Boolean = false,
    val variables: List<DashboardVariable> = emptyList(),
    val widgets: List<CreateWidgetRequest> = emptyList()
)

@Serializable
data class UpdateDashboardRequest(
    val title: String? = null,
    val description: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("layout_type") val layoutType: String? = null,
    @SerialName("is_default") val isDefault: Boolean? = null,
    val variables: List<DashboardVariable>? = null,
    val widgets: List<UpdateWidgetRequest>? = null
)

@Serializable
data class FolderResponse(
    val id: String,
    @SerialName("org_id") val orgId: Long,
    val name: String,
    val color: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateFolderRequest(
    val name: String,
    val color: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0
)

@Serializable
data class UpdateFolderRequest(
    val name: String? = null,
    val color: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null
)

@Serializable
data class SearchResponse(
    val dashboards: List<DashboardResponse> = emptyList(),
    val projects: List<SearchProjectResponse> = emptyList()
)

@Serializable
data class SearchProjectResponse(
    val id: String,
    val name: String
)

@Serializable
data class MoveToFolderRequest(
    @SerialName("folder_id") val folderId: String? = null
)

@Serializable
data class CreateWidgetRequest(
    val title: String? = null,
    @SerialName("widget_type") val widgetType: String,
    @SerialName("grid_x") val gridX: Int = 0,
    @SerialName("grid_y") val gridY: Int = 0,
    @SerialName("grid_w") val gridW: Int = 6,
    @SerialName("grid_h") val gridH: Int = 4,
    @SerialName("query_configs") val queryConfigs: List<QueryDsl> = emptyList(),
    @SerialName("display_config") val displayConfig: Map<String, String> = emptyMap(),
    @SerialName("sort_order") val sortOrder: Int = 0
)

@Serializable
data class UpdateWidgetRequest(
    val id: String? = null,
    val title: String? = null,
    @SerialName("widget_type") val widgetType: String? = null,
    @SerialName("grid_x") val gridX: Int? = null,
    @SerialName("grid_y") val gridY: Int? = null,
    @SerialName("grid_w") val gridW: Int? = null,
    @SerialName("grid_h") val gridH: Int? = null,
    @SerialName("query_configs") val queryConfigs: List<QueryDsl>? = null,
    @SerialName("display_config") val displayConfig: Map<String, String>? = null,
    @SerialName("sort_order") val sortOrder: Int? = null
)

@Serializable
data class ExecuteQueryRequest(
    @SerialName("query_config") val queryConfig: QueryDsl,
    @SerialName("time_range") val timeRange: TimeRangeDef? = null,
    val variables: Map<String, String> = emptyMap()
)

@Serializable
data class ExecuteBatchQueryRequest(
    val queries: List<QueryDsl>,
    @SerialName("time_range") val timeRange: TimeRangeDef? = null,
    val variables: Map<String, String> = emptyMap()
)

@Serializable
data class BatchQueryResult(
    val results: Map<String, List<Map<String, kotlinx.serialization.json.JsonElement>>>,
    val metadata: Map<String, BatchQueryResultMetadata> = emptyMap()
)

@Serializable
data class BatchQueryResultMetadata(
    @SerialName("original_ref_id") val originalRefId: String? = null,
    @SerialName("query_index") val queryIndex: Int
)

@Serializable
data class ImportDashboardRequest(
    val format: String,
    val json: String
)

@Serializable
data class DashboardImportResult(
    val dashboard: DashboardResponse,
    val warnings: List<String> = emptyList(),
    val variables: List<DashboardVariable> = emptyList()
)

@Serializable
data class DashboardTemplateCatalog(
    val templates: List<DashboardTemplateSummary> = emptyList()
)

@Serializable
data class DashboardTemplateSummary(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String = "community",
    val tags: List<String> = emptyList(),
    @SerialName("required_sources") val requiredSources: List<String> = emptyList(),
    @SerialName("widget_count") val widgetCount: Int = 0,
    @SerialName("variable_count") val variableCount: Int = 0,
    @SerialName("resource_path") val resourcePath: String
)

@Serializable
data class DashboardTemplateDetail(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String = "community",
    val tags: List<String> = emptyList(),
    @SerialName("required_sources") val requiredSources: List<String> = emptyList(),
    @SerialName("widget_count") val widgetCount: Int = 0,
    @SerialName("variable_count") val variableCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val dashboard: CreateDashboardRequest
)

@Serializable
data class InstantiateDashboardTemplateRequest(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("folder_id") val folderId: String? = null
)
