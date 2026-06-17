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

package com.moneat.datadog.models

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import java.util.UUID

object DebuggerProbes : Table("debugger_probes") {
    val id = javaUUID("id").clientDefault { UUID.randomUUID() }
    val organizationId = integer("organization_id").references(Organizations.id)
    val probeType = varchar("probe_type", 20).default("log_probe")
    val service = varchar("service", 255)
    val environment = varchar("environment", 255).default("*")
    val language = varchar("language", 20).default("java")
    val active = bool("active").default(true)

    val whereType = varchar("where_type", 10).default("method")
    val typeName = varchar("type_name", 500).nullable()
    val methodName = varchar("method_name", 255).nullable()
    val sourceFile = varchar("source_file", 500).nullable()
    val sourceLines = text("source_lines").nullable()

    val template = text("template").nullable()
    val metricName = varchar("metric_name", 255).nullable()
    val metricKind = varchar("metric_kind", 20).nullable()
    val tags = text("tags").nullable()
    val captureConfig = text("capture_config").nullable()

    val createdBy = integer("created_by").references(Users.id).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class DebuggerProbe(
    val id: String,
    val organizationId: String,
    val probeType: String,
    val service: String,
    val environment: String,
    val language: String,
    val active: Boolean,
    val whereType: String,
    val typeName: String? = null,
    val methodName: String? = null,
    val sourceFile: String? = null,
    val sourceLines: String? = null,
    val template: String? = null,
    val metricName: String? = null,
    val metricKind: String? = null,
    val tags: String? = null,
    val captureConfig: String? = null,
    val createdBy: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateDebuggerProbeRequest(
    val probeType: String = "log_probe",
    val service: String,
    val environment: String = "*",
    val language: String = "java",
    val active: Boolean = true,
    val whereType: String = "method",
    val typeName: String? = null,
    val methodName: String? = null,
    val sourceFile: String? = null,
    val sourceLines: String? = null,
    val template: String? = null,
    val metricName: String? = null,
    val metricKind: String? = null,
    val tags: String? = null,
    val captureConfig: String? = null,
)

@Serializable
data class UpdateDebuggerProbeRequest(
    val probeType: String? = null,
    val service: String? = null,
    val environment: String? = null,
    val language: String? = null,
    val active: Boolean? = null,
    val whereType: String? = null,
    val typeName: String? = null,
    val methodName: String? = null,
    val sourceFile: String? = null,
    val sourceLines: String? = null,
    val template: String? = null,
    val metricName: String? = null,
    val metricKind: String? = null,
    val tags: String? = null,
    val captureConfig: String? = null,
)

@Serializable
data class AgentDebuggerProbeConfig(
    val id: String,
    val probeType: String,
    val service: String,
    val environment: String,
    val language: String,
    val active: Boolean,
    val whereType: String,
    val typeName: String? = null,
    val methodName: String? = null,
    val sourceFile: String? = null,
    val sourceLines: String? = null,
    val template: String? = null,
    val metricName: String? = null,
    val metricKind: String? = null,
    val tags: String? = null,
    val captureConfig: String? = null,
)
