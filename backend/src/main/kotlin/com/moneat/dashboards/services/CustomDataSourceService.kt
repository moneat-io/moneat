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

package com.moneat.dashboards.services

import com.moneat.dashboards.models.CreateCustomDataSourceRequest
import com.moneat.dashboards.models.CustomDataSourceResponse
import com.moneat.dashboards.models.CustomDataSourceType
import com.moneat.dashboards.models.CustomDataSources
import com.moneat.dashboards.models.UpdateCustomDataSourceRequest
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

class CustomDataSourceService {

    fun listDataSources(orgId: Long): List<CustomDataSourceResponse> = transaction {
        CustomDataSources.selectAll()
            .where { CustomDataSources.orgId eq orgId }
            .orderBy(CustomDataSources.name)
            .map { it.toResponse() }
    }

    fun getDataSource(id: Long, orgId: Long): CustomDataSourceResponse? = transaction {
        CustomDataSources.selectAll()
            .where { (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId) }
            .firstOrNull()
            ?.toResponse()
    }

    fun createDataSource(orgId: Long, userId: Long, request: CreateCustomDataSourceRequest): CustomDataSourceResponse {
        val sourceType = CustomDataSourceType.fromString(request.sourceType)
            ?: throw IllegalArgumentException(
                "Unsupported source type: ${request.sourceType}"
            )

        val creds = DataSourceCredentials(
            username = request.username,
            password = request.password,
            apiKey = request.apiKey,
            accessKeyId = request.accessKeyId,
            secretAccessKey = request.secretAccessKey,
            serviceAccountJson = request.serviceAccountJson,
            accountIdentifier = request.accountIdentifier,
            connectionString = request.connectionString,
            projectId = request.projectId,
            region = request.region,
        )
        val encryptedCreds = encryptCredentials(creds)
        val now = Clock.System.now()

        return transaction {
            val id = CustomDataSources.insert {
                it[CustomDataSources.orgId] = orgId
                it[name] = request.name
                it[description] = request.description
                it[CustomDataSources.sourceType] = sourceType.name.lowercase()
                it[host] = request.host
                it[port] = request.port
                it[databaseName] = request.databaseName
                it[CustomDataSources.encryptedCredentials] = encryptedCreds
                it[extraConfig] = json.encodeToString(request.extraConfig)
                it[enabled] = true
                it[createdBy] = userId
                it[createdAt] = now
                it[updatedAt] = now
            } get CustomDataSources.id

            CustomDataSources.selectAll()
                .where { CustomDataSources.id eq id }
                .first()
                .toResponse()
        }
    }

    fun updateDataSource(id: Long, orgId: Long, request: UpdateCustomDataSourceRequest): CustomDataSourceResponse? {
        val now = Clock.System.now()
        return transaction {
            val existing = CustomDataSources.selectAll()
                .where { (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId) }
                .firstOrNull() ?: return@transaction null

            // Only re-encrypt credentials if new values are provided
            val hasNewCreds = request.username != null || request.password != null || request.apiKey != null ||
                request.accessKeyId != null || request.secretAccessKey != null || request.serviceAccountJson != null ||
                request.accountIdentifier != null || request.connectionString != null || request.projectId != null ||
                request.region != null
            val newEncryptedCreds = if (hasNewCreds) {
                val existingCreds = try {
                    val dec = CredentialEncryption.decrypt(existing[CustomDataSources.encryptedCredentials])
                    json.decodeFromString<DataSourceCredentials>(dec)
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Failed to decrypt existing credentials for data source $id; " +
                            "cannot safely merge new credentials",
                        e
                    )
                }
                val merged = DataSourceCredentials(
                    username = request.username ?: existingCreds.username,
                    password = request.password ?: existingCreds.password,
                    apiKey = request.apiKey ?: existingCreds.apiKey,
                    accessKeyId = request.accessKeyId ?: existingCreds.accessKeyId,
                    secretAccessKey = request.secretAccessKey ?: existingCreds.secretAccessKey,
                    serviceAccountJson = request.serviceAccountJson ?: existingCreds.serviceAccountJson,
                    accountIdentifier = request.accountIdentifier ?: existingCreds.accountIdentifier,
                    connectionString = request.connectionString ?: existingCreds.connectionString,
                    projectId = request.projectId ?: existingCreds.projectId,
                    region = request.region ?: existingCreds.region,
                )
                encryptCredentials(merged)
            } else {
                null
            }

            CustomDataSources.update({ (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId) }) {
                request.name?.let { v -> it[name] = v }
                request.description?.let { v -> it[description] = v }
                request.host?.let { v -> it[host] = v }
                request.port?.let { v -> it[port] = v }
                request.databaseName?.let { v -> it[databaseName] = v }
                newEncryptedCreds?.let { v -> it[encryptedCredentials] = v }
                request.extraConfig?.let { v ->
                    it[extraConfig] = json.encodeToString(v)
                }
                request.enabled?.let { v -> it[enabled] = v }
                it[updatedAt] = now
            }

            CustomDataSources.selectAll()
                .where { CustomDataSources.id eq id }
                .firstOrNull()
                ?.toResponse()
        }
    }

    fun deleteDataSource(id: Long, orgId: Long): Boolean = transaction {
        CustomDataSources.deleteWhere {
            (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId)
        } > 0
    }

    /**
     * Returns the decrypted credentials for a data source (used internally by the executor).
     * Never expose this to API responses.
     */
    fun getDecryptedCredentials(id: Long, orgId: Long): DataSourceCredentials? = transaction {
        val row = CustomDataSources.selectAll()
            .where { (CustomDataSources.id eq id) and (CustomDataSources.orgId eq orgId) }
            .firstOrNull() ?: return@transaction null

        try {
            val decrypted = CredentialEncryption.decrypt(row[CustomDataSources.encryptedCredentials])
            json.decodeFromString<DataSourceCredentials>(decrypted)
        } catch (e: Exception) {
            logger.error(e) { "Failed to decrypt credentials for data source $id" }
            null
        }
    }

    private fun encryptCredentials(creds: DataSourceCredentials): String {
        val credsJson = json.encodeToString(DataSourceCredentials.serializer(), creds)
        return CredentialEncryption.encrypt(credsJson)
    }

    private fun ResultRow.toResponse() = CustomDataSourceResponse(
        id = this[CustomDataSources.id],
        orgId = this[CustomDataSources.orgId],
        name = this[CustomDataSources.name],
        description = this[CustomDataSources.description],
        sourceType = this[CustomDataSources.sourceType],
        host = this[CustomDataSources.host],
        port = this[CustomDataSources.port],
        databaseName = this[CustomDataSources.databaseName],
        extraConfig = try {
            json.decodeFromString<Map<String, String>>(this[CustomDataSources.extraConfig])
        } catch (_: Exception) { emptyMap() },
        enabled = this[CustomDataSources.enabled],
        createdBy = this[CustomDataSources.createdBy],
        createdAt = this[CustomDataSources.createdAt].toString(),
        updatedAt = this[CustomDataSources.updatedAt].toString(),
        hasCredentials = this[CustomDataSources.encryptedCredentials].isNotBlank()
    )
}

@kotlinx.serialization.Serializable
data class DataSourceCredentials(
    val username: String? = null,
    val password: String? = null,
    @kotlinx.serialization.SerialName("api_key") val apiKey: String? = null,
    @kotlinx.serialization.SerialName("access_key_id") val accessKeyId: String? = null,
    @kotlinx.serialization.SerialName("secret_access_key") val secretAccessKey: String? = null,
    @kotlinx.serialization.SerialName("service_account_json") val serviceAccountJson: String? = null,
    @kotlinx.serialization.SerialName("account_identifier") val accountIdentifier: String? = null,
    @kotlinx.serialization.SerialName("connection_string") val connectionString: String? = null,
    @kotlinx.serialization.SerialName("project_id") val projectId: String? = null,
    val region: String? = null,
)
