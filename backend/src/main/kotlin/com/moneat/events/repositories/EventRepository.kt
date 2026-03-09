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

import com.moneat.events.repositories.models.ProjectKeyVerification

/**
 * Repository for event ingestion and project key lookups.
 * Abstracts PostgreSQL (ProjectKeys, Projects) and ClickHouse event/transaction/feedback inserts.
 *
 * Note: ClickHouse insert methods accept pre-built SQL. SQL construction and escaping
 * remain in the service for Phase 1; a future refactor can move SQL building into the
 * repository for full encapsulation.
 */
interface EventRepository {
    fun verifyProjectKey(projectId: Long, publicKey: String): ProjectKeyVerification
    fun getOrganizationIdForProject(projectId: Long): Int?
    suspend fun executeClickHouseInsert(sql: String): Boolean
    suspend fun executeClickHouseInsertNoResult(sql: String)
    suspend fun getEventCountForIssue(projectId: Long, issueId: String): Long
}
