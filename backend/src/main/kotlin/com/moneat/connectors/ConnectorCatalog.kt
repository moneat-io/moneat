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

package com.moneat.connectors

import com.moneat.secrets.SecretVaultPurpose
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConnectorFamily {
    @SerialName("notification")
    NOTIFICATION,

    @SerialName("workflow_egress")
    WORKFLOW_EGRESS,

    @SerialName("data_import")
    DATA_IMPORT,

    @SerialName("dashboard_query")
    DASHBOARD_QUERY,

    @SerialName("webhook_ingress")
    WEBHOOK_INGRESS
}

@Serializable
enum class ConnectorSetupMode {
    @SerialName("oauth")
    OAUTH,

    @SerialName("api_key")
    API_KEY,

    @SerialName("app_installation")
    APP_INSTALLATION,

    @SerialName("webhook")
    WEBHOOK,

    @SerialName("built_in")
    BUILT_IN
}

@Serializable
enum class ConnectorAvailability {
    @SerialName("available")
    AVAILABLE,

    @SerialName("planned")
    PLANNED,

    @SerialName("enterprise")
    ENTERPRISE
}

@Serializable
enum class ConnectorDirection {
    @SerialName("read")
    READ,

    @SerialName("write")
    WRITE,

    @SerialName("read_write")
    READ_WRITE,

    @SerialName("ingress")
    INGRESS,

    @SerialName("egress")
    EGRESS
}

@Serializable
enum class ConnectorAuthKind {
    @SerialName("oauth")
    OAUTH,

    @SerialName("api_key")
    API_KEY,

    @SerialName("app_installation")
    APP_INSTALLATION,

    @SerialName("bot_token")
    BOT_TOKEN,

    @SerialName("webhook_secret")
    WEBHOOK_SECRET
}

@Serializable
enum class ConnectorSubjectKind {
    @SerialName("organization")
    ORGANIZATION,

    @SerialName("installation")
    INSTALLATION,

    @SerialName("user")
    USER,

    @SerialName("service_account")
    SERVICE_ACCOUNT
}

@Serializable
enum class ConnectorTokenLifecycle {
    @SerialName("static")
    STATIC,

    @SerialName("refreshable")
    REFRESHABLE,

    @SerialName("short_lived")
    SHORT_LIVED,

    @SerialName("rotatable")
    ROTATABLE
}

@Serializable
enum class ConnectorStateKind {
    @SerialName("none")
    NONE,

    @SerialName("sync")
    SYNC,

    @SerialName("execution")
    EXECUTION,

    @SerialName("delivery")
    DELIVERY
}

@Serializable
enum class ConnectorUiGroup {
    @SerialName("import_data")
    IMPORT_DATA,

    @SerialName("send_actions")
    SEND_ACTIONS,

    @SerialName("receive_webhooks")
    RECEIVE_WEBHOOKS,

    @SerialName("send_notifications")
    SEND_NOTIFICATIONS,

    @SerialName("query_data")
    QUERY_DATA
}

@Serializable
data class ConnectorAuthProfileDefinition(
    val id: String,
    val name: String,
    val authKind: ConnectorAuthKind,
    val subjectKinds: List<ConnectorSubjectKind>,
    val tokenLifecycle: ConnectorTokenLifecycle,
    val defaultScopes: List<String>,
    val separateReconsentRequired: Boolean,
    val secretPurpose: String?
)

@Serializable
data class ConnectorProviderDefinition(
    val id: String,
    val name: String,
    val description: String,
    val authProfiles: List<ConnectorAuthProfileDefinition>,
    val uses: List<ConnectorUseDefinition>
)

@Serializable
data class ConnectorUseDefinition(
    val id: String,
    val name: String,
    val family: ConnectorFamily,
    val availability: ConnectorAvailability,
    val setupMode: ConnectorSetupMode,
    val capabilities: List<String>,
    val stateSource: String,
    val secretPurpose: String?,
    val setupRoute: String? = null,
    val statusRoute: String? = null,
    val description: String,
    val direction: ConnectorDirection,
    val allowedAuthProfileIds: List<String>,
    val requiredScopes: List<String> = emptyList(),
    val resourceTypes: List<String> = emptyList(),
    val stateKind: ConnectorStateKind = ConnectorStateKind.NONE,
    val uiGroup: ConnectorUiGroup
)

@Serializable
data class ConnectorProvidersResponse(
    val providers: List<ConnectorProviderDefinition>
)

@Serializable
enum class ConnectorConnectionState {
    @SerialName("connected")
    CONNECTED,

    @SerialName("not_connected")
    NOT_CONNECTED,

    @SerialName("planned")
    PLANNED,

    @SerialName("enterprise")
    ENTERPRISE
}

@Serializable
data class ConnectorUseState(
    val useId: String,
    val availability: ConnectorAvailability,
    val stateSource: String,
    val state: ConnectorConnectionState,
    val connected: Boolean,
    val enabled: Boolean,
    val integrationId: String? = null,
    val message: String? = null,
)

@Serializable
data class ConnectorProviderState(
    val providerId: String,
    val uses: List<ConnectorUseState>,
)

@Serializable
data class ConnectorConnectionSummary(
    val providerId: String,
    val connected: Boolean,
    val health: String? = null,
    val detail: String? = null,
    val lastCheckedAt: String? = null,
)

@Serializable
data class ConnectorStateResponse(
    val connections: List<ConnectorConnectionSummary>,
    val providers: List<ConnectorProviderState> = emptyList(),
)

object ConnectorCatalog {
    private const val ORGANIZATION_INTEGRATIONS = "organization_integrations"
    private const val WORKFLOW_CONNECTIONS = "workflow_connections"
    private const val CLOUD_SOURCES = "cloud_sources"
    private const val CLOUD_SOURCES_STATUS_ROUTE = "/v1/cloud-sources"
    private const val WORKFLOW_ACTIONS_NAME = "Workflow actions"
    private const val WORKFLOW_CONNECTIONS_STATUS_ROUTE = "/v1/workflows/connections"
    private const val GITHUB_METADATA_READ_SCOPE = "metadata:read"
    private const val JIRA_READ_SCOPE = "read:jira-work"
    private const val SERVICENOW_TABLE_READ_SCOPE = "table.read"

    val providers: List<ConnectorProviderDefinition> =
        listOf(
            ConnectorProviderDefinition(
                id = "slack",
                name = "Slack",
                description = "Connect Slack workspaces for notifications, incident collaboration, and team workflows.",
                authProfiles = listOf(
                    ConnectorAuthProfileDefinition(
                        id = "workspace_bot",
                        name = "Workspace bot",
                        authKind = ConnectorAuthKind.OAUTH,
                        subjectKinds = listOf(ConnectorSubjectKind.INSTALLATION),
                        tokenLifecycle = ConnectorTokenLifecycle.REFRESHABLE,
                        defaultScopes = listOf("chat:write", "channels:read", "groups:read", "usergroups:write"),
                        separateReconsentRequired = true,
                        secretPurpose = SecretVaultPurpose.NOTIFICATION.id
                    )
                ),
                uses = listOf(
                    ConnectorUseDefinition(
                        id = "notifications",
                        name = "Notifications",
                        family = ConnectorFamily.NOTIFICATION,
                        availability = ConnectorAvailability.AVAILABLE,
                        setupMode = ConnectorSetupMode.OAUTH,
                        capabilities = listOf("alert_delivery", "incident_updates", "on_call_usergroup_sync"),
                        stateSource = ORGANIZATION_INTEGRATIONS,
                        secretPurpose = SecretVaultPurpose.NOTIFICATION.id,
                        setupRoute = "/v1/integrations/slack/oauth/start",
                        statusRoute = "/v1/integrations",
                        description = "Send operational notifications to Slack channels and on-call user groups.",
                        direction = ConnectorDirection.EGRESS,
                        allowedAuthProfileIds = listOf("workspace_bot"),
                        requiredScopes = listOf("chat:write"),
                        resourceTypes = listOf("channel", "user_group"),
                        stateKind = ConnectorStateKind.DELIVERY,
                        uiGroup = ConnectorUiGroup.SEND_NOTIFICATIONS
                    )
                )
            ),
            ConnectorProviderDefinition(
                id = "discord",
                name = "Discord",
                description = "Connect Discord servers for operational notifications and incident updates.",
                authProfiles = listOf(
                    ConnectorAuthProfileDefinition(
                        id = "server_bot",
                        name = "Server bot",
                        authKind = ConnectorAuthKind.OAUTH,
                        subjectKinds = listOf(ConnectorSubjectKind.INSTALLATION),
                        tokenLifecycle = ConnectorTokenLifecycle.STATIC,
                        defaultScopes = listOf("bot"),
                        separateReconsentRequired = true,
                        secretPurpose = SecretVaultPurpose.NOTIFICATION.id
                    )
                ),
                uses = listOf(
                    ConnectorUseDefinition(
                        id = "notifications",
                        name = "Notifications",
                        family = ConnectorFamily.NOTIFICATION,
                        availability = ConnectorAvailability.AVAILABLE,
                        setupMode = ConnectorSetupMode.OAUTH,
                        capabilities = listOf("alert_delivery", "incident_updates"),
                        stateSource = ORGANIZATION_INTEGRATIONS,
                        secretPurpose = SecretVaultPurpose.NOTIFICATION.id,
                        setupRoute = "/v1/integrations/discord/oauth/start",
                        statusRoute = "/v1/integrations",
                        description = "Send operational notifications to Discord channels.",
                        direction = ConnectorDirection.EGRESS,
                        allowedAuthProfileIds = listOf("server_bot"),
                        requiredScopes = listOf("bot"),
                        resourceTypes = listOf("server", "channel"),
                        stateKind = ConnectorStateKind.DELIVERY,
                        uiGroup = ConnectorUiGroup.SEND_NOTIFICATIONS
                    )
                )
            ),
            ConnectorProviderDefinition(
                id = "pagerduty",
                name = "PagerDuty",
                description = "Connect PagerDuty services for incident routing, escalation, and workflow actions.",
                authProfiles = listOf(
                    ConnectorAuthProfileDefinition(
                        id = "service_integration_key",
                        name = "Service integration key",
                        authKind = ConnectorAuthKind.API_KEY,
                        subjectKinds = listOf(ConnectorSubjectKind.SERVICE_ACCOUNT),
                        tokenLifecycle = ConnectorTokenLifecycle.ROTATABLE,
                        defaultScopes = listOf("events.write"),
                        separateReconsentRequired = false,
                        secretPurpose = SecretVaultPurpose.NOTIFICATION.id
                    ),
                    ConnectorAuthProfileDefinition(
                        id = "workflow_api_key",
                        name = "Workflow API key",
                        authKind = ConnectorAuthKind.API_KEY,
                        subjectKinds = listOf(ConnectorSubjectKind.SERVICE_ACCOUNT),
                        tokenLifecycle = ConnectorTokenLifecycle.ROTATABLE,
                        defaultScopes = listOf("incidents.write"),
                        separateReconsentRequired = false,
                        secretPurpose = SecretVaultPurpose.WORKFLOW_EGRESS.id
                    )
                ),
                uses = listOf(
                    ConnectorUseDefinition(
                        id = "notifications",
                        name = "Notifications",
                        family = ConnectorFamily.NOTIFICATION,
                        availability = ConnectorAvailability.PLANNED,
                        setupMode = ConnectorSetupMode.API_KEY,
                        capabilities = listOf("alert_delivery", "incident_delivery"),
                        stateSource = ORGANIZATION_INTEGRATIONS,
                        secretPurpose = SecretVaultPurpose.NOTIFICATION.id,
                        description = "Route alerts and incidents into PagerDuty services.",
                        direction = ConnectorDirection.EGRESS,
                        allowedAuthProfileIds = listOf("service_integration_key"),
                        requiredScopes = listOf("events.write"),
                        resourceTypes = listOf("service"),
                        stateKind = ConnectorStateKind.DELIVERY,
                        uiGroup = ConnectorUiGroup.SEND_NOTIFICATIONS
                    ),
                    ConnectorUseDefinition(
                        id = "workflow_actions",
                        name = WORKFLOW_ACTIONS_NAME,
                        family = ConnectorFamily.WORKFLOW_EGRESS,
                        availability = ConnectorAvailability.ENTERPRISE,
                        setupMode = ConnectorSetupMode.API_KEY,
                        capabilities = listOf("workflow_action", "incident_create", "incident_update"),
                        stateSource = WORKFLOW_CONNECTIONS,
                        secretPurpose = SecretVaultPurpose.WORKFLOW_EGRESS.id,
                        statusRoute = WORKFLOW_CONNECTIONS_STATUS_ROUTE,
                        description = "Let workflows create or update PagerDuty incidents through trusted egress.",
                        direction = ConnectorDirection.WRITE,
                        allowedAuthProfileIds = listOf("workflow_api_key"),
                        requiredScopes = listOf("incidents.write"),
                        resourceTypes = listOf("incident"),
                        stateKind = ConnectorStateKind.EXECUTION,
                        uiGroup = ConnectorUiGroup.SEND_ACTIONS
                    )
                )
            ),
            ConnectorProviderDefinition(
                id = "github",
                name = "GitHub",
                description = "Connect GitHub organizations and repositories for source metadata and automation.",
                authProfiles = listOf(
                    ConnectorAuthProfileDefinition(
                        id = "read_app_installation",
                        name = "Read-only GitHub App installation",
                        authKind = ConnectorAuthKind.APP_INSTALLATION,
                        subjectKinds = listOf(ConnectorSubjectKind.INSTALLATION),
                        tokenLifecycle = ConnectorTokenLifecycle.SHORT_LIVED,
                        defaultScopes = listOf(
                            GITHUB_METADATA_READ_SCOPE,
                            "contents:read",
                            "issues:read",
                            "pull_requests:read"
                        ),
                        separateReconsentRequired = true,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id
                    ),
                    ConnectorAuthProfileDefinition(
                        id = "workflow_app_installation",
                        name = "Workflow GitHub App installation",
                        authKind = ConnectorAuthKind.APP_INSTALLATION,
                        subjectKinds = listOf(ConnectorSubjectKind.INSTALLATION),
                        tokenLifecycle = ConnectorTokenLifecycle.SHORT_LIVED,
                        defaultScopes = listOf(GITHUB_METADATA_READ_SCOPE, "issues:write"),
                        separateReconsentRequired = true,
                        secretPurpose = SecretVaultPurpose.WORKFLOW_EGRESS.id
                    )
                ),
                uses = listOf(
                    ConnectorUseDefinition(
                        id = "repository_import",
                        name = "Repository import",
                        family = ConnectorFamily.DATA_IMPORT,
                        availability = ConnectorAvailability.PLANNED,
                        setupMode = ConnectorSetupMode.APP_INSTALLATION,
                        capabilities = listOf("repository_read", "issue_import", "pull_request_import"),
                        stateSource = CLOUD_SOURCES,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id,
                        statusRoute = CLOUD_SOURCES_STATUS_ROUTE,
                        description = "Import repository, issue, and pull request metadata.",
                        direction = ConnectorDirection.READ,
                        allowedAuthProfileIds = listOf("read_app_installation"),
                        requiredScopes = listOf(
                            GITHUB_METADATA_READ_SCOPE,
                            "contents:read",
                            "issues:read",
                            "pull_requests:read"
                        ),
                        resourceTypes = listOf("organization", "repository"),
                        stateKind = ConnectorStateKind.SYNC,
                        uiGroup = ConnectorUiGroup.IMPORT_DATA
                    ),
                    ConnectorUseDefinition(
                        id = "workflow_actions",
                        name = WORKFLOW_ACTIONS_NAME,
                        family = ConnectorFamily.WORKFLOW_EGRESS,
                        availability = ConnectorAvailability.ENTERPRISE,
                        setupMode = ConnectorSetupMode.APP_INSTALLATION,
                        capabilities = listOf("workflow_action", "issue_create", "issue_update"),
                        stateSource = WORKFLOW_CONNECTIONS,
                        secretPurpose = SecretVaultPurpose.WORKFLOW_EGRESS.id,
                        statusRoute = WORKFLOW_CONNECTIONS_STATUS_ROUTE,
                        description = "Let workflows create and update GitHub issues through trusted egress.",
                        direction = ConnectorDirection.WRITE,
                        allowedAuthProfileIds = listOf("workflow_app_installation"),
                        requiredScopes = listOf(GITHUB_METADATA_READ_SCOPE, "issues:write"),
                        resourceTypes = listOf("repository", "issue"),
                        stateKind = ConnectorStateKind.EXECUTION,
                        uiGroup = ConnectorUiGroup.SEND_ACTIONS
                    )
                )
            ),
            ConnectorProviderDefinition(
                id = "jira",
                name = "Jira",
                description = "Connect Jira projects for issue metadata and automation.",
                authProfiles = listOf(
                    ConnectorAuthProfileDefinition(
                        id = "site_oauth",
                        name = "Jira site OAuth grant",
                        authKind = ConnectorAuthKind.OAUTH,
                        subjectKinds = listOf(ConnectorSubjectKind.USER, ConnectorSubjectKind.ORGANIZATION),
                        tokenLifecycle = ConnectorTokenLifecycle.REFRESHABLE,
                        defaultScopes = listOf(JIRA_READ_SCOPE),
                        separateReconsentRequired = true,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id
                    ),
                    ConnectorAuthProfileDefinition(
                        id = "workflow_api_token",
                        name = "Workflow API token",
                        authKind = ConnectorAuthKind.API_KEY,
                        subjectKinds = listOf(ConnectorSubjectKind.SERVICE_ACCOUNT),
                        tokenLifecycle = ConnectorTokenLifecycle.ROTATABLE,
                        defaultScopes = listOf(JIRA_READ_SCOPE, "write:jira-work"),
                        separateReconsentRequired = false,
                        secretPurpose = SecretVaultPurpose.WORKFLOW_EGRESS.id
                    )
                ),
                uses = listOf(
                    ConnectorUseDefinition(
                        id = "issue_import",
                        name = "Issue import",
                        family = ConnectorFamily.DATA_IMPORT,
                        availability = ConnectorAvailability.PLANNED,
                        setupMode = ConnectorSetupMode.OAUTH,
                        capabilities = listOf("project_read", "issue_import"),
                        stateSource = CLOUD_SOURCES,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id,
                        statusRoute = CLOUD_SOURCES_STATUS_ROUTE,
                        description = "Import Jira project and issue metadata.",
                        direction = ConnectorDirection.READ,
                        allowedAuthProfileIds = listOf("site_oauth"),
                        requiredScopes = listOf(JIRA_READ_SCOPE),
                        resourceTypes = listOf("site", "project", "issue"),
                        stateKind = ConnectorStateKind.SYNC,
                        uiGroup = ConnectorUiGroup.IMPORT_DATA
                    ),
                    ConnectorUseDefinition(
                        id = "workflow_actions",
                        name = WORKFLOW_ACTIONS_NAME,
                        family = ConnectorFamily.WORKFLOW_EGRESS,
                        availability = ConnectorAvailability.ENTERPRISE,
                        setupMode = ConnectorSetupMode.API_KEY,
                        capabilities = listOf("workflow_action", "issue_create", "issue_update"),
                        stateSource = WORKFLOW_CONNECTIONS,
                        secretPurpose = SecretVaultPurpose.WORKFLOW_EGRESS.id,
                        statusRoute = WORKFLOW_CONNECTIONS_STATUS_ROUTE,
                        description = "Let workflows create and update Jira issues through trusted egress.",
                        direction = ConnectorDirection.WRITE,
                        allowedAuthProfileIds = listOf("workflow_api_token"),
                        requiredScopes = listOf("write:jira-work"),
                        resourceTypes = listOf("site", "project", "issue"),
                        stateKind = ConnectorStateKind.EXECUTION,
                        uiGroup = ConnectorUiGroup.SEND_ACTIONS
                    )
                )
            ),
            ConnectorProviderDefinition(
                id = "servicenow",
                name = "ServiceNow",
                description = "Connect ServiceNow tables for ticket metadata and automation.",
                authProfiles = listOf(
                    ConnectorAuthProfileDefinition(
                        id = "instance_read_api_key",
                        name = "Instance read API key",
                        authKind = ConnectorAuthKind.API_KEY,
                        subjectKinds = listOf(ConnectorSubjectKind.SERVICE_ACCOUNT),
                        tokenLifecycle = ConnectorTokenLifecycle.ROTATABLE,
                        defaultScopes = listOf(SERVICENOW_TABLE_READ_SCOPE),
                        separateReconsentRequired = false,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id
                    ),
                    ConnectorAuthProfileDefinition(
                        id = "workflow_api_key",
                        name = "Workflow API key",
                        authKind = ConnectorAuthKind.API_KEY,
                        subjectKinds = listOf(ConnectorSubjectKind.SERVICE_ACCOUNT),
                        tokenLifecycle = ConnectorTokenLifecycle.ROTATABLE,
                        defaultScopes = listOf(SERVICENOW_TABLE_READ_SCOPE, "table.write"),
                        separateReconsentRequired = false,
                        secretPurpose = SecretVaultPurpose.WORKFLOW_EGRESS.id
                    )
                ),
                uses = listOf(
                    ConnectorUseDefinition(
                        id = "ticket_import",
                        name = "Ticket import",
                        family = ConnectorFamily.DATA_IMPORT,
                        availability = ConnectorAvailability.PLANNED,
                        setupMode = ConnectorSetupMode.API_KEY,
                        capabilities = listOf("ticket_import", "cmdb_import"),
                        stateSource = CLOUD_SOURCES,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id,
                        statusRoute = CLOUD_SOURCES_STATUS_ROUTE,
                        description = "Import ticket and CMDB metadata.",
                        direction = ConnectorDirection.READ,
                        allowedAuthProfileIds = listOf("instance_read_api_key"),
                        requiredScopes = listOf(SERVICENOW_TABLE_READ_SCOPE),
                        resourceTypes = listOf("instance", "table"),
                        stateKind = ConnectorStateKind.SYNC,
                        uiGroup = ConnectorUiGroup.IMPORT_DATA
                    ),
                    ConnectorUseDefinition(
                        id = "workflow_actions",
                        name = WORKFLOW_ACTIONS_NAME,
                        family = ConnectorFamily.WORKFLOW_EGRESS,
                        availability = ConnectorAvailability.ENTERPRISE,
                        setupMode = ConnectorSetupMode.API_KEY,
                        capabilities = listOf("workflow_action", "ticket_create", "ticket_update"),
                        stateSource = WORKFLOW_CONNECTIONS,
                        secretPurpose = SecretVaultPurpose.WORKFLOW_EGRESS.id,
                        statusRoute = WORKFLOW_CONNECTIONS_STATUS_ROUTE,
                        description = "Let workflows create and update ServiceNow tickets through trusted egress.",
                        direction = ConnectorDirection.WRITE,
                        allowedAuthProfileIds = listOf("workflow_api_key"),
                        requiredScopes = listOf("table.write"),
                        resourceTypes = listOf("instance", "table", "ticket"),
                        stateKind = ConnectorStateKind.EXECUTION,
                        uiGroup = ConnectorUiGroup.SEND_ACTIONS
                    )
                )
            ),
            ConnectorProviderDefinition(
                id = "revenuecat",
                name = "RevenueCat",
                description = "Connect RevenueCat for subscription lifecycle and revenue data.",
                authProfiles = listOf(
                    ConnectorAuthProfileDefinition(
                        id = "project_api_key",
                        name = "Project API key",
                        authKind = ConnectorAuthKind.API_KEY,
                        subjectKinds = listOf(ConnectorSubjectKind.SERVICE_ACCOUNT),
                        tokenLifecycle = ConnectorTokenLifecycle.ROTATABLE,
                        defaultScopes = listOf("projects.read", "customers.read", "transactions.read"),
                        separateReconsentRequired = false,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id
                    )
                ),
                uses = listOf(
                    ConnectorUseDefinition(
                        id = "subscription_import",
                        name = "Subscription import",
                        family = ConnectorFamily.DATA_IMPORT,
                        availability = ConnectorAvailability.PLANNED,
                        setupMode = ConnectorSetupMode.API_KEY,
                        capabilities = listOf("subscription_lifecycle_import", "revenue_import", "cohort_revenue"),
                        stateSource = CLOUD_SOURCES,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id,
                        statusRoute = CLOUD_SOURCES_STATUS_ROUTE,
                        description = "Import subscription lifecycle and revenue events for cohort economics.",
                        direction = ConnectorDirection.READ,
                        allowedAuthProfileIds = listOf("project_api_key"),
                        requiredScopes = listOf("projects.read", "customers.read", "transactions.read"),
                        resourceTypes = listOf("project", "customer", "transaction"),
                        stateKind = ConnectorStateKind.SYNC,
                        uiGroup = ConnectorUiGroup.IMPORT_DATA
                    )
                )
            ),
            ConnectorProviderDefinition(
                id = "google_ads",
                name = "Google Ads",
                description = "Connect Google Ads for spend, campaign, and cohort cost data.",
                authProfiles = listOf(
                    ConnectorAuthProfileDefinition(
                        id = "manager_oauth",
                        name = "Google Ads manager OAuth grant",
                        authKind = ConnectorAuthKind.OAUTH,
                        subjectKinds = listOf(ConnectorSubjectKind.USER, ConnectorSubjectKind.ORGANIZATION),
                        tokenLifecycle = ConnectorTokenLifecycle.REFRESHABLE,
                        defaultScopes = listOf("https://www.googleapis.com/auth/adwords"),
                        separateReconsentRequired = true,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id
                    )
                ),
                uses = listOf(
                    ConnectorUseDefinition(
                        id = "ad_spend_import",
                        name = "Ad spend import",
                        family = ConnectorFamily.DATA_IMPORT,
                        availability = ConnectorAvailability.PLANNED,
                        setupMode = ConnectorSetupMode.OAUTH,
                        capabilities = listOf("ad_spend_import", "campaign_metrics", "cohort_cost"),
                        stateSource = CLOUD_SOURCES,
                        secretPurpose = SecretVaultPurpose.DATA_IMPORT.id,
                        statusRoute = CLOUD_SOURCES_STATUS_ROUTE,
                        description = "Import ad spend and campaign metrics for acquisition payback reporting.",
                        direction = ConnectorDirection.READ,
                        allowedAuthProfileIds = listOf("manager_oauth"),
                        requiredScopes = listOf("https://www.googleapis.com/auth/adwords"),
                        resourceTypes = listOf("manager_account", "customer_account", "campaign"),
                        stateKind = ConnectorStateKind.SYNC,
                        uiGroup = ConnectorUiGroup.IMPORT_DATA
                    )
                )
            )
        )
}
