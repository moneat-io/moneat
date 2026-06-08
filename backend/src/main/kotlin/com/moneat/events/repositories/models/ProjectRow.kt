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

import com.moneat.events.models.ProjectKeyResponse

/**
 * Domain model for a project row from the database.
 */
data class ProjectRow(
    val projectId: Long,
    val name: String,
    val slug: String,
    val framework: String?,
    val keys: List<ProjectKeyResponse>,
    val dsn: String,
    val resourceId: String = projectId.toString(),
    val serviceId: Long = projectId,
    val serviceName: String = slug.trim().ifBlank { name.trim().ifBlank { projectId.toString() } },
)
