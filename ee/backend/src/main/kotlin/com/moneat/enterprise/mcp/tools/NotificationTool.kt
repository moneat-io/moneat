// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.mcp.tools

import com.moneat.enterprise.mcp.models.McpContext
import com.moneat.enterprise.mcp.protocol.InputSchema
import com.moneat.enterprise.mcp.protocol.McpTool
import com.moneat.enterprise.mcp.protocol.ToolCallResult
import com.moneat.notifications.services.AlertNotificationPreferencesService
import com.moneat.enterprise.mcp.services.NotificationPreferencesService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private val alertNotifService = AlertNotificationPreferencesService()
private val notifPrefsService = NotificationPreferencesService()

private val ALERT_SOURCES = listOf(
    "system_alert",
    "uptime_alert",
    "dashboard_alert"
)

class GetAlertNotificationChannelsTool : McpTool {
    override val name = "get_alert_notification_channels"
    override val description =
        "Get alert notification channel preferences"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val prefs = alertNotifService.getPreferences(
            context.userId,
            context.organizationId
        )
        return jsonResult(prefs)
    }
}

class UpdateAlertNotificationChannelsTool : McpTool {
    override val name = "update_alert_notification_channels"
    override val description =
        "Update alert notification channel preferences"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "alert_source" to schemaEnum(
                    "Alert source",
                    ALERT_SOURCES
                ),
                "email_enabled" to schemaBoolean(
                    "Enable email notifications"
                ),
                "slack_enabled" to schemaBoolean(
                    "Enable Slack notifications"
                ),
                "discord_enabled" to schemaBoolean(
                    "Enable Discord notifications"
                )
            )
        ),
        required = listOf(
            "alert_source",
            "email_enabled",
            "slack_enabled",
            "discord_enabled"
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val allowedSources = ALERT_SOURCES.toSet()
        val alertSource = args["alert_source"]
            ?.jsonPrimitive?.content
            ?: return errorResult("alert_source is required")
        if (alertSource !in allowedSources) {
            return errorResult("invalid alert_source")
        }
        val emailEnabled = if (args.containsKey("email_enabled")) {
            args["email_enabled"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult(
                    "email_enabled must be a boolean"
                )
        } else {
            return errorResult("email_enabled is required")
        }
        val slackEnabled = if (args.containsKey("slack_enabled")) {
            args["slack_enabled"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult(
                    "slack_enabled must be a boolean"
                )
        } else {
            return errorResult("slack_enabled is required")
        }
        val discordEnabled = if (args.containsKey("discord_enabled")) {
            args["discord_enabled"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult(
                    "discord_enabled must be a boolean"
                )
        } else {
            return errorResult("discord_enabled is required")
        }

        val pref = alertNotifService.updatePreference(
            userId = context.userId,
            organizationId = context.organizationId,
            alertSource = alertSource,
            emailEnabled = emailEnabled,
            slackEnabled = slackEnabled,
            discordEnabled = discordEnabled
        )
        return jsonResult(pref)
    }
}

class GetNotificationPreferencesTool : McpTool {
    override val name = "get_notification_preferences"
    override val description =
        "Get notification preferences (global and per-project)"
    override val inputSchema = InputSchema()

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val prefs = notifPrefsService.getPreferences(context.userId)
        return jsonResult(prefs)
    }
}

class UpdateNotificationPreferencesTool : McpTool {
    override val name = "update_notification_preferences"
    override val description =
        "Update global notification preferences"
    override val readOnly = false
    override val inputSchema = InputSchema(
        properties = JsonObject(
            mapOf(
                "issue_alerts" to schemaBoolean(
                    "Enable issue alerts"
                ),
                "error_alerts" to schemaBoolean(
                    "Enable error alerts"
                ),
                "weekly_summary" to schemaBoolean(
                    "Enable weekly summary emails"
                ),
                "alert_frequency_minutes" to schemaNumber(
                    "Minimum minutes between repeated alerts"
                )
            )
        ),
        required = listOf(
            "issue_alerts",
            "error_alerts",
            "weekly_summary",
            "alert_frequency_minutes"
        )
    )

    override suspend fun execute(
        args: JsonObject,
        context: McpContext
    ): ToolCallResult {
        val issueAlerts = if (args.containsKey("issue_alerts")) {
            args["issue_alerts"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult("issue_alerts must be a boolean")
        } else {
            return errorResult("issue_alerts is required")
        }
        val errorAlerts = if (args.containsKey("error_alerts")) {
            args["error_alerts"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult("error_alerts must be a boolean")
        } else {
            return errorResult("error_alerts is required")
        }
        val weeklySummary = if (args.containsKey("weekly_summary")) {
            args["weekly_summary"]?.jsonPrimitive?.content
                ?.toBooleanStrictOrNull()
                ?: return errorResult("weekly_summary must be a boolean")
        } else {
            return errorResult("weekly_summary is required")
        }
        val alertFrequencyMinutes = args["alert_frequency_minutes"]
            ?.jsonPrimitive?.intOrNull
            ?: return errorResult(
                "alert_frequency_minutes must be an integer"
            )
        if (alertFrequencyMinutes < 1) {
            return errorResult("alert_frequency_minutes must be >= 1")
        }

        val updated = notifPrefsService.updatePreferences(
            userId = context.userId,
            issueAlerts = issueAlerts,
            errorAlerts = errorAlerts,
            weeklySummary = weeklySummary,
            alertFrequencyMinutes = alertFrequencyMinutes
        )
        return jsonResult(updated)
    }
}
