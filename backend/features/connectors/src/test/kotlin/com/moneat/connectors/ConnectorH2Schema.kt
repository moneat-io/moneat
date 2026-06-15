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

import com.moneat.testsupport.TestDatabaseHelper
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object ConnectorH2Schema {
    fun reset(vararg baseTables: Table) {
        TestDatabaseHelper.resetSchema(*baseTables)
        patchConnectorJsonbForH2()
        createConnectorTablesForH2()
    }

    private fun patchConnectorJsonbForH2() {
        val field = Column::class.java.getDeclaredField("columnType")
        field.isAccessible = true
        connectorTables().forEach { table ->
            table.columns.forEach { column ->
                val typeName = column.columnType::class.qualifiedName
                if (typeName == "com.moneat.shared.models.JsonbColumnType") {
                    field.set(column, h2TextJson(column.columnType.nullable))
                }
            }
        }
    }

    private fun h2TextJson(nullableColumn: Boolean): ColumnType<String> =
        object : ColumnType<String>() {
            override var nullable: Boolean = nullableColumn
            override fun sqlType(): String = "TEXT"
            override fun valueFromDB(value: Any): String = value.toString()
            override fun notNullValueToDB(value: String): Any = value
            override fun nonNullValueToString(value: String): String =
                "'${value.replace("'", "''")}'"
        }

    private fun createConnectorTablesForH2() {
        transaction {
            exec(
                """
                CREATE TABLE connector_installations (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL,
                    organization_id INT NOT NULL,
                    provider VARCHAR(64) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    credential_type VARCHAR(64) NOT NULL,
                    auth_profile_id VARCHAR(64) NOT NULL,
                    external_project_id VARCHAR(255),
                    external_project_name VARCHAR(255),
                    external_project_discovered_at TIMESTAMP(9),
                    auth_permissions_summary TEXT DEFAULT '{}' NOT NULL,
                    status VARCHAR(32) DEFAULT 'pending' NOT NULL,
                    status_reason TEXT,
                    last_tested_at TIMESTAMP(9),
                    last_test_result VARCHAR(32),
                    last_successful_provider_call_at TIMESTAMP(9),
                    last_error TEXT,
                    api_secret_ciphertext TEXT,
                    api_secret_key_id VARCHAR(64),
                    api_secret_last_four VARCHAR(8),
                    webhook_token_hash VARCHAR(128),
                    webhook_token_prefix VARCHAR(16),
                    webhook_token_created_at TIMESTAMP(9),
                    webhook_token_rotated_at TIMESTAMP(9),
                    enabled BOOLEAN DEFAULT TRUE NOT NULL,
                    created_by INT,
                    created_at TIMESTAMP(9) NOT NULL,
                    updated_at TIMESTAMP(9) NOT NULL,
                    deleted_at TIMESTAMP(9)
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE connector_external_resources (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL,
                    organization_id INT NOT NULL,
                    installation_id INT NOT NULL,
                    external_project_id VARCHAR(255),
                    external_resource_type VARCHAR(64) NOT NULL,
                    external_resource_id VARCHAR(255) NOT NULL,
                    display_name VARCHAR(255),
                    provider_metadata TEXT DEFAULT '{}' NOT NULL,
                    last_seen_at TIMESTAMP(9) NOT NULL,
                    created_at TIMESTAMP(9) NOT NULL,
                    updated_at TIMESTAMP(9) NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE connector_use_bindings (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL,
                    organization_id INT NOT NULL,
                    installation_id INT NOT NULL,
                    external_project_id VARCHAR(255),
                    external_resource_type VARCHAR(64) NOT NULL,
                    external_resource_id VARCHAR(255) NOT NULL,
                    local_resource_type VARCHAR(64) NOT NULL,
                    local_resource_id UUID NOT NULL,
                    local_resource_numeric_id BIGINT,
                    status VARCHAR(32) DEFAULT 'active' NOT NULL,
                    effective_from TIMESTAMP(9) NOT NULL,
                    effective_to TIMESTAMP(9),
                    binding_version INT DEFAULT 1 NOT NULL,
                    created_by INT,
                    updated_by INT,
                    created_at TIMESTAMP(9) NOT NULL,
                    updated_at TIMESTAMP(9) NOT NULL
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE connector_inbound_events_raw (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL,
                    organization_id INT NOT NULL,
                    installation_id INT NOT NULL,
                    provider VARCHAR(64) NOT NULL,
                    provider_event_id VARCHAR(255) NOT NULL,
                    payload_sha256 VARCHAR(64) NOT NULL,
                    request_headers TEXT DEFAULT '{}' NOT NULL,
                    raw_payload TEXT NOT NULL,
                    received_at TIMESTAMP(9) NOT NULL,
                    provider_event_timestamp_ms BIGINT,
                    event_type VARCHAR(128),
                    environment VARCHAR(64),
                    external_project_id VARCHAR(255),
                    external_resource_id VARCHAR(255),
                    auth_token_prefix VARCHAR(16)
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE connector_event_receipts (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    resource_id UUID NOT NULL,
                    organization_id INT NOT NULL,
                    installation_id INT NOT NULL,
                    provider VARCHAR(64) NOT NULL,
                    provider_event_id VARCHAR(255) NOT NULL,
                    payload_sha256 VARCHAR(64) NOT NULL,
                    raw_event_id BIGINT NOT NULL,
                    received_at TIMESTAMP(9) NOT NULL,
                    provider_event_timestamp_ms BIGINT,
                    first_seen_at TIMESTAMP(9) NOT NULL,
                    last_seen_at TIMESTAMP(9) NOT NULL,
                    attempt_count INT DEFAULT 0 NOT NULL,
                    state VARCHAR(32) DEFAULT 'received' NOT NULL,
                    worker_claimed_at TIMESTAMP(9),
                    applied_at TIMESTAMP(9),
                    last_error_code VARCHAR(64),
                    last_error_message TEXT,
                    created_at TIMESTAMP(9) NOT NULL,
                    updated_at TIMESTAMP(9) NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun connectorTables(): List<Table> =
        listOf(
            ConnectorInstallations,
            ConnectorExternalResources,
            ConnectorUseBindings,
            ConnectorInboundEventsRaw,
            ConnectorEventReceipts,
        )
}
