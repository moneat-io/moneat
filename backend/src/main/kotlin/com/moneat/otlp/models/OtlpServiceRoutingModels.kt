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

package com.moneat.otlp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateOtlpServiceMappingRequest(
    @SerialName("service_namespace") val serviceNamespace: String? = null,
    @SerialName("service_name") val serviceName: String,
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("project_resource_id") val projectResourceId: String? = null,
)

@Serializable
data class OtlpServiceMappingResponse(
    val id: Int,
    @SerialName("service_namespace") val serviceNamespace: String,
    @SerialName("service_name") val serviceName: String,
    @SerialName("project_id") val projectId: Long,
    @SerialName("project_resource_id") val projectResourceId: String,
    @SerialName("project_name") val projectName: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class OtlpObservedServiceResponse(
    val id: Int,
    @SerialName("mapping_id") val mappingId: Int? = null,
    @SerialName("service_namespace") val serviceNamespace: String,
    @SerialName("service_name") val serviceName: String,
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("project_resource_id") val projectResourceId: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("seen_logs") val seenLogs: Boolean,
    @SerialName("seen_traces") val seenTraces: Boolean,
    @SerialName("seen_metrics") val seenMetrics: Boolean,
    @SerialName("last_environment") val lastEnvironment: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
)
