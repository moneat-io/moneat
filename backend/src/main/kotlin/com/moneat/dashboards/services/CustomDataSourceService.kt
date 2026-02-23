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

import com.moneat.dashboards.models.*
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

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
            ?: throw IllegalArgumentException("Unsupported source type: ${request.sourceType}. Use 'postgresql' or 'prometheus'")

        val encryptedCreds = encryptCredentials(request.username, request.password, request.apiKey)
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
            val newEncryptedCreds = if (request.username != null || request.password != null || request.apiKey != null) {
                encryptCredentials(request.username, request.password, request.apiKey)
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

    private fun encryptCredentials(username: String?, password: String?, apiKey: String?): String {
        val creds = DataSourceCredentials(
            username = username,
            password = password,
            apiKey = apiKey
        )
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
)
