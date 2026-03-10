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

package com.moneat.events.repositories.models

/**
 * Raw issue row from ClickHouse (before status override from PostgreSQL).
 */
data class IssueRow(
    val issueId: String,
    val projectId: Long,
    val title: String,
    val culprit: String,
    val level: String,
    val platform: String,
    val firstSeen: String,
    val lastSeen: String,
    val eventCount: Long,
    val userCount: Long,
    val status: String,
    val fingerprint: List<String>?
)
