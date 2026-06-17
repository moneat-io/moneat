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

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.shared.models.jsonb
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

object ConnectorInstallations : Table("connector_installations") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 64)
    val name = varchar("name", 255)
    val credentialType = varchar("credential_type", 64)
    val authProfileId = varchar("auth_profile_id", 64)
    val externalProjectId = varchar("external_project_id", 255).nullable()
    val externalProjectName = varchar("external_project_name", 255).nullable()
    val externalProjectDiscoveredAt = timestamp("external_project_discovered_at").nullable()
    val authPermissionsSummary = jsonb("auth_permissions_summary").default("{}")
    val status = varchar("status", 32).default("pending")
    val statusReason = text("status_reason").nullable()
    val lastTestedAt = timestamp("last_tested_at").nullable()
    val lastTestResult = varchar("last_test_result", 32).nullable()
    val lastSuccessfulProviderCallAt = timestamp("last_successful_provider_call_at").nullable()
    val lastError = text("last_error").nullable()
    val apiSecretCiphertext = text("api_secret_ciphertext").nullable()
    val apiSecretKeyId = varchar("api_secret_key_id", 64).nullable()
    val apiSecretLastFour = varchar("api_secret_last_four", 8).nullable()
    val webhookTokenHash = varchar("webhook_token_hash", 128).nullable()
    val webhookTokenPrefix = varchar("webhook_token_prefix", 16).nullable()
    val webhookTokenCreatedAt = timestamp("webhook_token_created_at").nullable()
    val webhookTokenRotatedAt = timestamp("webhook_token_rotated_at").nullable()
    val enabled = bool("enabled").default(true)
    val createdBy = integer("created_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    init {
        index(false, organizationId, provider)
    }

    override val primaryKey = PrimaryKey(id)
}

object ConnectorExternalResources : Table("connector_external_resources") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val installationId =
        integer("installation_id")
            .references(ConnectorInstallations.id, onDelete = ReferenceOption.CASCADE)
    val externalProjectId = varchar("external_project_id", 255).nullable()
    val externalResourceType = varchar("external_resource_type", 64)
    val externalResourceId = varchar("external_resource_id", 255)
    val displayName = varchar("display_name", 255).nullable()
    val providerMetadata = jsonb("provider_metadata").default("{}")
    val lastSeenAt = timestamp("last_seen_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(installationId, externalResourceType, externalResourceId)
        index(false, organizationId, externalResourceType)
    }

    override val primaryKey = PrimaryKey(id)
}

object ConnectorUseBindings : Table("connector_use_bindings") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val installationId =
        integer("installation_id")
            .references(ConnectorInstallations.id, onDelete = ReferenceOption.CASCADE)
    val externalProjectId = varchar("external_project_id", 255).nullable()
    val externalResourceType = varchar("external_resource_type", 64)
    val externalResourceId = varchar("external_resource_id", 255)
    val localResourceType = varchar("local_resource_type", 64)
    val localResourceId = uuid("local_resource_id")
    val localResourceNumericId = long("local_resource_numeric_id").nullable()
    val status = varchar("status", 32).default("active")
    val effectiveFrom = timestamp("effective_from")
    val effectiveTo = timestamp("effective_to").nullable()
    val bindingVersion = integer("binding_version").default(1)
    val createdBy = integer("created_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val updatedBy = integer("updated_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        index(false, organizationId, localResourceType, localResourceId)
        index(false, installationId, externalResourceType, externalResourceId)
    }

    override val primaryKey = PrimaryKey(id)
}

object ConnectorInboundEventsRaw : Table("connector_inbound_events_raw") {
    val id = long("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val installationId =
        integer("installation_id")
            .references(ConnectorInstallations.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 64)
    val providerEventId = varchar("provider_event_id", 255)
    val payloadSha256 = varchar("payload_sha256", 64)
    val requestHeaders = jsonb("request_headers").default("{}")
    val rawPayload = text("raw_payload")
    val receivedAt = timestamp("received_at")
    val providerEventTimestampMs = long("provider_event_timestamp_ms").nullable()
    val eventType = varchar("event_type", 128).nullable()
    val environment = varchar("environment", 64).nullable()
    val externalProjectId = varchar("external_project_id", 255).nullable()
    val externalResourceId = varchar("external_resource_id", 255).nullable()
    val authTokenPrefix = varchar("auth_token_prefix", 16).nullable()

    init {
        index(false, installationId, receivedAt)
    }

    override val primaryKey = PrimaryKey(id)
}

object ConnectorEventReceipts : Table("connector_event_receipts") {
    val id = integer("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val installationId =
        integer("installation_id")
            .references(ConnectorInstallations.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 64)
    val providerEventId = varchar("provider_event_id", 255)
    val payloadSha256 = varchar("payload_sha256", 64)
    val rawEventId = long("raw_event_id").references(ConnectorInboundEventsRaw.id, onDelete = ReferenceOption.CASCADE)
    val receivedAt = timestamp("received_at")
    val providerEventTimestampMs = long("provider_event_timestamp_ms").nullable()
    val firstSeenAt = timestamp("first_seen_at")
    val lastSeenAt = timestamp("last_seen_at")
    val attemptCount = integer("attempt_count").default(0)
    val state = varchar("state", 32).default("received")
    val workerClaimedAt = timestamp("worker_claimed_at").nullable()
    val appliedAt = timestamp("applied_at").nullable()
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val lastErrorMessage = text("last_error_message").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(installationId, providerEventId)
        index(false, installationId, state, updatedAt)
    }

    override val primaryKey = PrimaryKey(id)
}

object ConnectorImportRuns : Table("connector_import_runs") {
    val id = long("id").autoIncrement()
    val resourceId = uuid("resource_id").clientDefault { Uuid.random() }
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val installationId =
        integer("installation_id")
            .references(ConnectorInstallations.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 64)
    val importType = varchar("import_type", 64)
    val externalProjectId = varchar("external_project_id", 255).nullable()
    val externalResourceId = varchar("external_resource_id", 255).nullable()
    val dateStart = date("date_start")
    val dateEnd = date("date_end")
    val status = varchar("status", 32).default("queued")
    val rowsImported = integer("rows_imported").default(0)
    val requestedBy = integer("requested_by").references(Users.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val queuedAt = timestamp("queued_at")
    val startedAt = timestamp("started_at").nullable()
    val finishedAt = timestamp("finished_at").nullable()
    val attemptCount = integer("attempt_count").default(0)
    val lastErrorCode = varchar("last_error_code", 64).nullable()
    val lastErrorMessage = text("last_error_message").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex(organizationId, resourceId)
        index(false, installationId, status, updatedAt)
    }

    override val primaryKey = PrimaryKey(id)
}
