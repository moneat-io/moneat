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

import com.moneat.events.repositories.models.ErrorEventInsertData
import com.moneat.events.repositories.models.FeedbackInsertData
import com.moneat.events.repositories.models.LlmGenerationInsertData
import com.moneat.events.repositories.models.ProfileInsertData
import com.moneat.events.repositories.models.ProjectKeyVerification
import com.moneat.events.repositories.models.ReplayEventInsertData
import com.moneat.events.repositories.models.ReplayRecordingInsertData
import com.moneat.events.repositories.models.SessionInsertData
import com.moneat.events.repositories.models.SpanInsertData
import com.moneat.events.repositories.models.TransactionEventInsertData

/**
 * Repository for event ingestion and project key lookups.
 * Abstracts PostgreSQL (ProjectKeys, Projects) and ClickHouse event/transaction/feedback inserts.
 */
interface EventRepository {
    fun verifyProjectKey(projectId: Long, publicKey: String): ProjectKeyVerification
    fun getOrganizationIdForProject(projectId: Long): Int?
    suspend fun getEventCountForIssue(projectId: Long, issueId: String): Long

    suspend fun insertErrorEvent(data: ErrorEventInsertData): Boolean
    suspend fun insertTransaction(data: TransactionEventInsertData): Boolean
    suspend fun insertSessions(rows: List<SessionInsertData>): Boolean
    suspend fun insertSpans(rows: List<SpanInsertData>)
    suspend fun insertFeedback(data: FeedbackInsertData): Boolean
    suspend fun insertReplayEvent(data: ReplayEventInsertData): Boolean
    suspend fun insertReplayRecording(data: ReplayRecordingInsertData)
    suspend fun insertLlmGenerations(rows: List<LlmGenerationInsertData>): Boolean
    suspend fun insertProfile(data: ProfileInsertData): Boolean
}
