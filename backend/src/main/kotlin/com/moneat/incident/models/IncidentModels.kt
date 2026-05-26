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

package com.moneat.incident.models

import com.moneat.shared.models.Organizations
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp

// Provider config data class
data class ProviderConfig(
    val id: Int,
    val organizationId: Int,
    val providerType: String,
    val name: String,
    val apiKey: String,
    val configJson: JsonObject,
    val enabled: Boolean
)

// Exposed table objects
object IncidentProviderConfigs : IntIdTable("incident_provider_configs") {
    val organizationId = integer("organization_id").references(Organizations.id)
    val providerType = varchar("provider_type", 50)
    val name = varchar("name", 255)
    val apiKey = text("api_key")
    val configJson = text("config_json")
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object IncidentRoutingRules : IntIdTable("incident_routing_rules") {
    val providerConfigId = integer("provider_config_id").references(IncidentProviderConfigs.id)
    val alertSource = varchar("alert_source", 50)
    val alertType = varchar("alert_type", 100).nullable()
    val incidentSeverity = varchar("incident_severity", 20)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object IncidentEventLog : IntIdTable("incident_event_log") {
    val organizationId = integer("organization_id").references(Organizations.id)
    val providerConfigId = integer("provider_config_id").references(IncidentProviderConfigs.id)
    val alertSource = varchar("alert_source", 50)
    val deduplicationKey = varchar("deduplication_key", 255)
    val incidentSeverity = varchar("incident_severity", 20)
    val incidentStatus = varchar("incident_status", 20)
    val title = text("title")
    val description = text("description").nullable()
    val providerIncidentId = text("provider_incident_id").nullable()
    val success = bool("success")
    val errorMessage = text("error_message").nullable()
    val metadata = text("metadata").nullable()
    val createdAt = timestamp("created_at")
}
