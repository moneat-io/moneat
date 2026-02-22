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

package com.moneat.statuspage.models

import com.moneat.shared.models.Organizations
import com.moneat.uptime.models.UptimeMonitors
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Clock

// ==================== Exposed Tables ====================

object StatusPages : Table("status_pages") {
    val id = javaUUID("id").autoGenerate()
    val organizationId = integer("organization_id").references(Organizations.id)
    val name = varchar("name", 255)
    val slug = varchar("slug", 100).uniqueIndex()
    val description = text("description").nullable()

    // Branding
    val logoUrl = text("logo_url").nullable()
    val faviconUrl = text("favicon_url").nullable()
    val primaryColor = varchar("primary_color", 7).default("#3B82F6")
    val darkMode = bool("dark_mode").default(false)

    // Settings
    val showUptimeHistory = bool("show_uptime_history").default(true)
    val historyDays = integer("history_days").default(90)
    val isPublic = bool("is_public").default(true)

    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object StatusPageMonitors : Table("status_page_monitors") {
    val id = integer("id").autoIncrement()
    val statusPageId = javaUUID("status_page_id").references(StatusPages.id)
    val monitorId = javaUUID("monitor_id").references(UptimeMonitors.id)
    val displayName = varchar("display_name", 255).nullable()
    val sortOrder = integer("sort_order").default(0)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }

    override val primaryKey = PrimaryKey(id)
}

object StatusPageIncidents : Table("status_page_incidents") {
    val id = javaUUID("id").autoGenerate()
    val statusPageId = javaUUID("status_page_id").references(StatusPages.id)
    val title = varchar("title", 255)
    val status = varchar("status", 50)
    val type = varchar("type", 50).default("incident")
    val impact = varchar("impact", 50).default("none")
    val scheduledStartAt = timestamp("scheduled_start_at").nullable()
    val scheduledEndAt = timestamp("scheduled_end_at").nullable()
    val resolvedAt = timestamp("resolved_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object StatusPageIncidentUpdates : Table("status_page_incident_updates") {
    val id = javaUUID("id").autoGenerate()
    val incidentId = javaUUID("incident_id").references(StatusPageIncidents.id)
    val status = varchar("status", 50)
    val message = text("message")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

object StatusPageCustomDomains : Table("status_page_custom_domains") {
    val id = integer("id").autoIncrement()
    val statusPageId = javaUUID("status_page_id").references(StatusPages.id)
    val domain = varchar("domain", 255).uniqueIndex()
    val verificationToken = varchar("verification_token", 64)
    val verified = bool("verified").default(false)
    val verifiedAt = timestamp("verified_at").nullable()
    val sslProvisioned = bool("ssl_provisioned").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ==================== Request DTOs ====================

@Serializable
data class CreateStatusPageRequest(
    val name: String,
    val slug: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val faviconUrl: String? = null,
    val primaryColor: String = "#3B82F6",
    val darkMode: Boolean = false,
    val showUptimeHistory: Boolean = true,
    val historyDays: Int = 90,
    val isPublic: Boolean = true
)

@Serializable
data class UpdateStatusPageRequest(
    val name: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val logoUrl: String? = null,
    val faviconUrl: String? = null,
    val primaryColor: String? = null,
    val darkMode: Boolean? = null,
    val showUptimeHistory: Boolean? = null,
    val historyDays: Int? = null,
    val isPublic: Boolean? = null
)

@Serializable
data class AddMonitorsRequest(
    val monitors: List<MonitorAssignment>
)

@Serializable
data class MonitorAssignment(
    val monitorId: String,
    val displayName: String? = null,
    val sortOrder: Int = 0
)

@Serializable
data class CreateIncidentRequest(
    val title: String,
    val status: String,
    val type: String = "incident",
    val impact: String = "none",
    val message: String,
    val scheduledStartAt: String? = null,
    val scheduledEndAt: String? = null
)

@Serializable
data class UpdateIncidentRequest(
    val title: String? = null,
    val status: String? = null,
    val impact: String? = null,
    val scheduledStartAt: String? = null,
    val scheduledEndAt: String? = null
)

@Serializable
data class CreateIncidentUpdateRequest(
    val status: String,
    val message: String
)

@Serializable
data class AddCustomDomainRequest(
    val domain: String
)

// ==================== Response DTOs ====================

@Serializable
data class StatusPageResponse(
    val id: String,
    val organizationId: String,
    val name: String,
    val slug: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val faviconUrl: String? = null,
    val primaryColor: String,
    val darkMode: Boolean,
    val showUptimeHistory: Boolean,
    val historyDays: Int,
    val isPublic: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StatusPageDetailResponse(
    val id: String,
    val organizationId: String,
    val name: String,
    val slug: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val faviconUrl: String? = null,
    val primaryColor: String,
    val darkMode: Boolean,
    val showUptimeHistory: Boolean,
    val historyDays: Int,
    val isPublic: Boolean,
    val monitors: List<StatusPageMonitorResponse>,
    val customDomains: List<CustomDomainResponse>,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StatusPageMonitorResponse(
    val id: Int,
    val monitorId: String,
    val monitorName: String,
    val displayName: String? = null,
    val sortOrder: Int,
    val url: String? = null
)

@Serializable
data class IncidentResponse(
    val id: String,
    val statusPageId: String,
    val title: String,
    val status: String,
    val type: String,
    val impact: String,
    val scheduledStartAt: String? = null,
    val scheduledEndAt: String? = null,
    val resolvedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val updates: List<IncidentUpdateResponse>
)

@Serializable
data class IncidentUpdateResponse(
    val id: String,
    val status: String,
    val message: String,
    val createdAt: String
)

@Serializable
data class CustomDomainResponse(
    val id: Int,
    val domain: String,
    val verificationToken: String,
    val verified: Boolean,
    val verifiedAt: String? = null,
    val sslProvisioned: Boolean,
    val createdAt: String
)

@Serializable
data class PublicStatusPageResponse(
    val name: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val faviconUrl: String? = null,
    val primaryColor: String,
    val darkMode: Boolean,
    val showUptimeHistory: Boolean,
    val historyDays: Int,
    val monitors: List<PublicMonitorStatus>,
    val activeIncidents: List<IncidentResponse>,
    val scheduledMaintenance: List<IncidentResponse>
)

@Serializable
data class PublicMonitorStatus(
    val name: String,
    val displayName: String? = null,
    val status: String, // operational, degraded, down
    val uptimePercentage: Double,
    val uptimeHistory: List<UptimeDataPoint>? = null
)

@Serializable
data class UptimeDataPoint(
    val date: String,
    val uptime: Double
)
