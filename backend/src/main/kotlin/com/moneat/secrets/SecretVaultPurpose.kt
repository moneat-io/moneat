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

package com.moneat.secrets

/**
 * Trust domains for stored connector secrets.
 *
 * Each purpose has distinct key material and HKDF context. Reusing the same
 * plaintext secret across purposes is rejected when the value is visible to the
 * validator or cipher factory.
 */
enum class SecretVaultPurpose(
    val id: String,
    val activeSecretEnvVar: String,
    val activeKeyIdEnvVar: String,
    val previousSecretsEnvVar: String,
    val hkdfInfoPrefix: String
) {
    NOTIFICATION(
        id = "notification",
        activeSecretEnvVar = "NOTIFICATION_CONNECTOR_KEK",
        activeKeyIdEnvVar = "NOTIFICATION_CONNECTOR_KEK_ID",
        previousSecretsEnvVar = "NOTIFICATION_CONNECTOR_KEK_PREVIOUS",
        hkdfInfoPrefix = "moneat-notification-connector-kek"
    ),
    WORKFLOW_EGRESS(
        id = "workflow_egress",
        activeSecretEnvVar = "WORKFLOWS_CONNECTION_KEK",
        activeKeyIdEnvVar = "WORKFLOWS_CONNECTION_KEK_ID",
        previousSecretsEnvVar = "WORKFLOWS_CONNECTION_KEK_PREVIOUS",
        hkdfInfoPrefix = "moneat-workflows-connection-kek"
    ),
    DATA_IMPORT(
        id = "data_import",
        activeSecretEnvVar = "DATA_IMPORT_CONNECTOR_KEK",
        activeKeyIdEnvVar = "DATA_IMPORT_CONNECTOR_KEK_ID",
        previousSecretsEnvVar = "DATA_IMPORT_CONNECTOR_KEK_PREVIOUS",
        hkdfInfoPrefix = "moneat-data-import-connector-kek"
    ),
    DASHBOARD_QUERY(
        id = "dashboard_query",
        activeSecretEnvVar = "DASHBOARD_QUERY_CONNECTOR_KEK",
        activeKeyIdEnvVar = "DASHBOARD_QUERY_CONNECTOR_KEK_ID",
        previousSecretsEnvVar = "DASHBOARD_QUERY_CONNECTOR_KEK_PREVIOUS",
        hkdfInfoPrefix = "moneat-dashboard-query-connector-kek"
    ),
    WEBHOOK_INGRESS(
        id = "webhook_ingress",
        activeSecretEnvVar = "WEBHOOK_INGRESS_CONNECTOR_KEK",
        activeKeyIdEnvVar = "WEBHOOK_INGRESS_CONNECTOR_KEK_ID",
        previousSecretsEnvVar = "WEBHOOK_INGRESS_CONNECTOR_KEK_PREVIOUS",
        hkdfInfoPrefix = "moneat-webhook-ingress-connector-kek"
    );

    companion object {
        val secretEnvVars: Set<String> = entries.mapTo(linkedSetOf()) { it.activeSecretEnvVar }
    }
}
