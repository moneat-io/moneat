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

package com.moneat.monitor.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InfrastructureMapSavedViewsResponse(
    val views: List<InfrastructureMapSavedViewResponse>
)

@Serializable
data class InfrastructureMapSavedViewResponse(
    val id: Int,
    val name: String,
    @SerialName("resource_kind") val resourceKind: String,
    @SerialName("group_by") val groupBy: String,
    @SerialName("fill_by") val fillBy: String,
    @SerialName("size_by") val sizeBy: String,
    @SerialName("search_query") val searchQuery: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class SaveInfrastructureMapViewRequest(
    val name: String,
    @SerialName("resource_kind") val resourceKind: String,
    @SerialName("group_by") val groupBy: String,
    @SerialName("fill_by") val fillBy: String,
    @SerialName("size_by") val sizeBy: String,
    @SerialName("search_query") val searchQuery: String = ""
)

@Serializable
data class InfrastructureMapViewStatePayload(
    @SerialName("resource_kind") val resourceKind: String,
    @SerialName("group_by") val groupBy: String,
    @SerialName("fill_by") val fillBy: String,
    @SerialName("size_by") val sizeBy: String,
    @SerialName("search_query") val searchQuery: String,
    @SerialName("schema_version") val schemaVersion: Int = INFRASTRUCTURE_MAP_SAVED_VIEW_SCHEMA_VERSION
)

const val INFRASTRUCTURE_MAP_SAVED_VIEW_SCHEMA_VERSION = 1
