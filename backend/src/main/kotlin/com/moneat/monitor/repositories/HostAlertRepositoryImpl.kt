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

package com.moneat.monitor.repositories

import com.moneat.monitor.models.AlertRow
import com.moneat.monitor.models.AlertSettingRow
import com.moneat.monitor.models.CreateAlertData
import com.moneat.monitor.models.UpdateAlertData
import com.moneat.shared.models.HostAlertSettings
import com.moneat.shared.models.HostAlertTemplateStates
import com.moneat.shared.models.HostAlerts
import com.moneat.shared.models.OrganizationAlertTemplates
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private const val SCOPE_HOST = "host"
private const val SCOPE_GLOBAL = "global"

class HostAlertRepositoryImpl : HostAlertRepository {

    override fun listByHostAndOrg(hostId: Int, organizationId: Int): List<AlertRow> =
        transaction {
            HostAlerts
                .selectAll()
                .where { (HostAlerts.host_id eq hostId) and (HostAlerts.organization_id eq organizationId) }
                .orderBy(HostAlerts.created_at to SortOrder.DESC)
                .map { row ->
                    AlertRow(
                        id = row[HostAlerts.id],
                        hostId = row[HostAlerts.host_id],
                        organizationId = row[HostAlerts.organization_id],
                        metric = row[HostAlerts.metric],
                        condition = row[HostAlerts.condition],
                        threshold = row[HostAlerts.threshold],
                        durationSeconds = row[HostAlerts.duration_seconds],
                        enabled = row[HostAlerts.enabled],
                        lastTriggeredAt = row[HostAlerts.last_triggered_at],
                        createdAt = row[HostAlerts.created_at],
                        scope = SCOPE_HOST
                    )
                }
        }

    override fun getAlertConfig(hostId: Int, organizationId: Int, metricName: String): AlertRow? =
        transaction {
            HostAlerts
                .selectAll()
                .where {
                    (HostAlerts.host_id eq hostId) and
                        (HostAlerts.organization_id eq organizationId) and
                        (HostAlerts.metric eq metricName)
                }
                .firstOrNull()
                ?.let { row ->
                    AlertRow(
                        id = row[HostAlerts.id],
                        hostId = row[HostAlerts.host_id],
                        organizationId = row[HostAlerts.organization_id],
                        metric = row[HostAlerts.metric],
                        condition = row[HostAlerts.condition],
                        threshold = row[HostAlerts.threshold],
                        durationSeconds = row[HostAlerts.duration_seconds],
                        enabled = row[HostAlerts.enabled],
                        lastTriggeredAt = row[HostAlerts.last_triggered_at],
                        createdAt = row[HostAlerts.created_at],
                        scope = SCOPE_HOST
                    )
                }
        }

    override fun getAlertSettings(hostId: Int): List<AlertSettingRow> =
        transaction {
            HostAlertSettings
                .selectAll()
                .where { HostAlertSettings.host_id eq hostId }
                .map { row ->
                    AlertSettingRow(
                        hostId = row[HostAlertSettings.host_id],
                        organizationId = row[HostAlertSettings.organization_id],
                        scope = row[HostAlertSettings.scope],
                        updatedAt = row[HostAlertSettings.updated_at]
                    )
                }
        }

    override fun upsertAlertSettings(hostId: Int, organizationId: Int, scope: String) {
        val now = Clock.System.now()
        transaction {
            val existing = HostAlertSettings
                .selectAll()
                .where {
                    (HostAlertSettings.host_id eq hostId) and
                        (HostAlertSettings.organization_id eq organizationId)
                }
                .firstOrNull()
            if (existing != null) {
                HostAlertSettings.update({ HostAlertSettings.host_id eq hostId }) {
                    it[HostAlertSettings.scope] = scope
                    it[HostAlertSettings.updated_at] = now
                }
            } else {
                HostAlertSettings.insert {
                    it[HostAlertSettings.host_id] = hostId
                    it[HostAlertSettings.organization_id] = organizationId
                    it[HostAlertSettings.scope] = scope
                    it[HostAlertSettings.updated_at] = now
                }
            }
        }
    }

    override fun createAlert(alert: CreateAlertData): Long {
        val now = Clock.System.now()
        return transaction {
            if (alert.scope == SCOPE_GLOBAL) {
                OrganizationAlertTemplates.insert {
                    it[OrganizationAlertTemplates.organization_id] = alert.organizationId
                    it[OrganizationAlertTemplates.metric] = alert.metric
                    it[OrganizationAlertTemplates.condition] = alert.condition
                    it[OrganizationAlertTemplates.threshold] = alert.threshold
                    it[OrganizationAlertTemplates.duration_seconds] = alert.durationSeconds
                    it[OrganizationAlertTemplates.enabled] = alert.enabled
                    it[OrganizationAlertTemplates.created_at] = now
                    it[OrganizationAlertTemplates.updated_at] = now
                } get OrganizationAlertTemplates.id
            } else {
                HostAlerts.insert {
                    it[HostAlerts.host_id] = alert.hostId
                    it[HostAlerts.organization_id] = alert.organizationId
                    it[HostAlerts.metric] = alert.metric
                    it[HostAlerts.condition] = alert.condition
                    it[HostAlerts.threshold] = alert.threshold
                    it[HostAlerts.duration_seconds] = alert.durationSeconds
                    it[HostAlerts.enabled] = alert.enabled
                    it[HostAlerts.last_triggered_at] = null
                    it[HostAlerts.created_at] = now
                } get HostAlerts.id
            }
        }.toLong()
    }

    override fun updateAlert(
        alertId: Long,
        hostId: Int,
        organizationId: Int,
        data: UpdateAlertData,
        scope: String
    ): Boolean {
        val now = Clock.System.now()
        return transaction {
            if (scope == SCOPE_GLOBAL) {
                val count = OrganizationAlertTemplates.update({
                    (OrganizationAlertTemplates.id eq alertId.toInt()) and
                        (OrganizationAlertTemplates.organization_id eq organizationId)
                }) {
                    data.metric?.let { m -> it[OrganizationAlertTemplates.metric] = m }
                    data.condition?.let { c -> it[OrganizationAlertTemplates.condition] = c }
                    data.threshold?.let { t -> it[OrganizationAlertTemplates.threshold] = t }
                    data.durationSeconds?.let { d -> it[OrganizationAlertTemplates.duration_seconds] = d }
                    data.enabled?.let { e -> it[OrganizationAlertTemplates.enabled] = e }
                    it[OrganizationAlertTemplates.updated_at] = now
                }
                count > 0
            } else {
                val count = HostAlerts.update({
                    (HostAlerts.id eq alertId.toInt()) and
                        (HostAlerts.host_id eq hostId) and
                        (HostAlerts.organization_id eq organizationId)
                }) {
                    data.metric?.let { m -> it[HostAlerts.metric] = m }
                    data.condition?.let { c -> it[HostAlerts.condition] = c }
                    data.threshold?.let { t -> it[HostAlerts.threshold] = t }
                    data.durationSeconds?.let { d -> it[HostAlerts.duration_seconds] = d }
                    data.enabled?.let { e -> it[HostAlerts.enabled] = e }
                }
                count > 0
            }
        }
    }

    override fun deleteAlert(alertId: Long, hostId: Int, organizationId: Int, scope: String): Boolean =
        transaction {
            if (scope == SCOPE_GLOBAL) {
                val deleted = OrganizationAlertTemplates.deleteWhere {
                    (OrganizationAlertTemplates.id eq alertId.toInt()) and
                        (OrganizationAlertTemplates.organization_id eq organizationId)
                }
                deleted > 0
            } else {
                val deleted = HostAlerts.deleteWhere {
                    (HostAlerts.id eq alertId.toInt()) and
                        (HostAlerts.host_id eq hostId) and
                        (HostAlerts.organization_id eq organizationId)
                }
                deleted > 0
            }
        }

    override fun listGlobalAlertsForHost(organizationId: Int, hostId: Int): List<AlertRow> =
        transaction {
            val templateStates = HostAlertTemplateStates
                .selectAll()
                .where { HostAlertTemplateStates.host_id eq hostId }
                .associateBy(
                    keySelector = { it[HostAlertTemplateStates.template_alert_id] },
                    valueTransform = { it[HostAlertTemplateStates.last_triggered_at] }
                )

            OrganizationAlertTemplates
                .selectAll()
                .where { OrganizationAlertTemplates.organization_id eq organizationId }
                .orderBy(OrganizationAlertTemplates.created_at to SortOrder.DESC)
                .map { row ->
                    AlertRow(
                        id = row[OrganizationAlertTemplates.id],
                        hostId = hostId,
                        organizationId = organizationId,
                        metric = row[OrganizationAlertTemplates.metric],
                        condition = row[OrganizationAlertTemplates.condition],
                        threshold = row[OrganizationAlertTemplates.threshold],
                        durationSeconds = row[OrganizationAlertTemplates.duration_seconds],
                        enabled = row[OrganizationAlertTemplates.enabled],
                        lastTriggeredAt = templateStates[row[OrganizationAlertTemplates.id]],
                        createdAt = row[OrganizationAlertTemplates.created_at],
                        scope = SCOPE_GLOBAL
                    )
                }
        }

    override fun findAlertById(alertId: Long): AlertRow? =
        transaction {
            HostAlerts
                .selectAll()
                .where { HostAlerts.id eq alertId.toInt() }
                .firstOrNull()
                ?.let { row ->
                    AlertRow(
                        id = row[HostAlerts.id],
                        hostId = row[HostAlerts.host_id],
                        organizationId = row[HostAlerts.organization_id],
                        metric = row[HostAlerts.metric],
                        condition = row[HostAlerts.condition],
                        threshold = row[HostAlerts.threshold],
                        durationSeconds = row[HostAlerts.duration_seconds],
                        enabled = row[HostAlerts.enabled],
                        lastTriggeredAt = row[HostAlerts.last_triggered_at],
                        createdAt = row[HostAlerts.created_at],
                        scope = SCOPE_HOST
                    )
                }
        }
}
