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

package com.moneat.events.repositories

import com.moneat.config.ClickHouseClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects

private val logger = KotlinLogging.logger {}

class EventRepositoryImpl : EventRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val clickhouseDb: String get() = ClickHouseClient.getDatabase()

    override fun verifyProjectKey(projectId: Long, publicKey: String): ProjectKeyVerification =
        transaction {
            ProjectKeys
                .selectAll()
                .where {
                    (ProjectKeys.project_id eq projectId) and
                        (ProjectKeys.public_key eq publicKey) and
                        (ProjectKeys.is_active eq true)
                }.firstOrNull()
                ?.let { row ->
                    ProjectKeyVerification(true, row[ProjectKeys.platform_target])
                } ?: ProjectKeyVerification(false, null)
        }

    override fun getOrganizationIdForProject(projectId: Long): Int? =
        transaction {
            Projects
                .selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.get(Projects.organization_id)
        }

    override suspend fun executeClickHouseInsert(sql: String): Boolean {
        return try {
            val response = ClickHouseClient.execute(sql)
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.error(e) { "ClickHouse insert failed" }
            false
        }
    }

    override suspend fun executeClickHouseInsertNoResult(sql: String) {
        try {
            val response = ClickHouseClient.execute(sql)
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logger.error { "ClickHouse insert failed: $errorBody" }
            }
        } catch (e: Exception) {
            logger.error(e) { "ClickHouse insert failed" }
        }
    }

    override suspend fun getEventCountForIssue(projectId: Long, issueId: String): Long {
        val escapedIssueId = com.moneat.utils.ClickHouseSqlUtils.escapeSql(issueId)
        val query = """
            SELECT count() as cnt
            FROM `$clickhouseDb`.events
            WHERE project_id = $projectId
              AND issue_id = '$escapedIssueId'
            FORMAT JSON
        """.trimIndent()

        return try {
            val response = ClickHouseClient.execute(query)
            val jsonResponse = json.parseToJsonElement(response.bodyAsText()).jsonObject
            jsonResponse["data"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("cnt")
                ?.jsonPrimitive
                ?.longOrNull ?: 0
        } catch (e: Exception) {
            logger.error(e) { "Error checking event count for issue $issueId" }
            0
        }
    }
}
