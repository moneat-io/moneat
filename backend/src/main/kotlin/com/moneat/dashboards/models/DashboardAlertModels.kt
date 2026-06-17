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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

object DashboardWidgetAlerts : Table("dashboard_widget_alerts") {
    val id = long("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val widgetId = long("widget_id").references(DashboardWidgets.id)
    val dashboardId = long("dashboard_id").references(Dashboards.id)
    val orgId = long("org_id")
    val name = varchar("name", 255)
    val condition = varchar("condition", 5)
    val threshold = double("threshold")
    val warningThreshold = double("warning_threshold").nullable()
    val metricIndex = integer("metric_index").default(0)
    val durationSeconds = integer("duration_seconds").default(0)
    val alertPriority = varchar("alert_priority", 20).nullable()
    val enabled = bool("enabled").default(true)
    val notificationChannels = jsonb("notification_channels")
    val lastTriggeredAt = timestamp("last_triggered_at").nullable()
    val lastTriggeredLevel = varchar("last_triggered_level", 20).nullable()
    val lastValue = double("last_value").nullable()
    val createdBy = long("created_by")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class NotificationChannels(
    val email: Boolean = true,
    val slack: Boolean = true,
    val discord: Boolean = true
)

@Serializable
data class DashboardAlertResponse(
    val id: String,
    @SerialName("widget_id") val widgetId: String,
    @SerialName("dashboard_id") val dashboardId: String,
    val name: String,
    val condition: String,
    val threshold: Double,
    @SerialName("warning_threshold") val warningThreshold: Double? = null,
    @SerialName("metric_index") val metricIndex: Int = 0,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    @SerialName("alert_priority") val alertPriority: String? = null,
    val enabled: Boolean = true,
    @SerialName("notification_channels") val notificationChannels: NotificationChannels = NotificationChannels(),
    @SerialName("last_triggered_at") val lastTriggeredAt: String? = null,
    @SerialName("last_triggered_level") val lastTriggeredLevel: String? = null,
    @SerialName("last_value") val lastValue: Double? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateDashboardAlertRequest(
    @SerialName("widget_id") val widgetId: String,
    val name: String,
    val condition: String,
    val threshold: Double,
    @SerialName("warning_threshold") val warningThreshold: Double? = null,
    @SerialName("metric_index") val metricIndex: Int = 0,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    @SerialName("alert_priority") val alertPriority: String? = null,
    @SerialName("incident_severity") val legacyIncidentSeverity: String? = null,
    val enabled: Boolean = true,
    @SerialName("notification_channels") val notificationChannels: NotificationChannels = NotificationChannels()
)

@Serializable
data class UpdateDashboardAlertRequest(
    val name: String? = null,
    val condition: String? = null,
    val threshold: Double? = null,
    @SerialName("warning_threshold") val warningThreshold: Double? = null,
    @SerialName("metric_index") val metricIndex: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("alert_priority") val alertPriority: String? = null,
    @SerialName("incident_severity") val legacyIncidentSeverity: String? = null,
    val enabled: Boolean? = null,
    @SerialName("notification_channels") val notificationChannels: NotificationChannels? = null,
    @Transient val warningThresholdProvided: Boolean = false
)
