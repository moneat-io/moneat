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

package com.moneat.security.posture

import com.moneat.shared.models.Organizations
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp

object SecurityComplianceFindingStates : IntIdTable("security_compliance_finding_states") {
    val organizationId = integer("organization_id").references(Organizations.id, onDelete = ReferenceOption.CASCADE)
    val framework = varchar("framework", 128)
    val ruleId = varchar("rule_id", 255)
    val ruleName = text("rule_name").default("")
    val resourceType = text("resource_type").default("")
    val resourceId = text("resource_id").default("")
    val resourceName = text("resource_name").default("")
    val status = varchar("status", 16)
    val firstSeen = timestamp("first_seen")
    val lastSeen = timestamp("last_seen")
    val lastRegressedAt = timestamp("last_regressed_at").nullable()
    val updatedAt = timestamp("updated_at")
}

enum class ComplianceFindingStatus(val wire: String) {
    PASSED("passed"),
    FAILED("failed"),
    SKIPPED("skipped"),
    ERROR("error");

    companion object {
        fun normalize(value: String): ComplianceFindingStatus =
            entries.firstOrNull { it.wire == value } ?: ERROR
    }
}

data class ComplianceFindingInput(
    val framework: String = "",
    val ruleId: String = "",
    val ruleName: String = "",
    val status: String = "passed",
    val resourceType: String = "",
    val resourceId: String = "",
    val resourceName: String = "",
    val timestampMs: Long,
)
