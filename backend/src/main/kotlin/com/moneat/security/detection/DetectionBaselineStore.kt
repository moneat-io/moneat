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

package com.moneat.security.detection

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

/**
 * Per-`(rule, group_key)` seen-set for the new-value evaluator. Org-keyed Postgres state, size-bounded
 * by deleting entries older than the baseline window so growth is bounded. The store is the only place
 * the new-value baseline lives; it never touches ClickHouse and is scoped by rule (hence by org).
 */
interface DetectionBaselineStore {
    /** Returns the group keys already known for [ruleId]. */
    fun knownKeys(ruleId: Int): Set<String>

    /** Records [groupKey] as seen for [ruleId] at [seenAt], or bumps its last_seen if already present. */
    fun record(ruleId: Int, organizationId: Int, groupKey: String, seenAt: Instant)

    /** Drops baseline entries for [ruleId] whose last_seen is older than [olderThan]. */
    fun evictOlderThan(ruleId: Int, olderThan: Instant)
}

/** Exposed-backed [DetectionBaselineStore] over [DetectionBaselineValues]. */
class PostgresDetectionBaselineStore : DetectionBaselineStore {

    override fun knownKeys(ruleId: Int): Set<String> = transaction {
        DetectionBaselineValues
            .selectAll()
            .where { DetectionBaselineValues.ruleId eq ruleId }
            .map { it[DetectionBaselineValues.groupKey] }
            .toSet()
    }

    override fun record(ruleId: Int, organizationId: Int, groupKey: String, seenAt: Instant) {
        transaction {
            val existing = DetectionBaselineValues
                .selectAll()
                .where {
                    (DetectionBaselineValues.ruleId eq ruleId) and
                        (DetectionBaselineValues.groupKey eq groupKey)
                }
                .firstOrNull()
            if (existing != null) {
                DetectionBaselineValues.update({
                    (DetectionBaselineValues.ruleId eq ruleId) and
                        (DetectionBaselineValues.groupKey eq groupKey)
                }) {
                    it[lastSeen] = seenAt
                }
            } else {
                DetectionBaselineValues.insert {
                    it[DetectionBaselineValues.ruleId] = ruleId
                    it[DetectionBaselineValues.organizationId] = organizationId
                    it[DetectionBaselineValues.groupKey] = groupKey
                    it[firstSeen] = seenAt
                    it[lastSeen] = seenAt
                }
            }
        }
    }

    override fun evictOlderThan(ruleId: Int, olderThan: Instant) {
        transaction {
            DetectionBaselineValues.deleteWhere {
                (DetectionBaselineValues.ruleId eq ruleId) and (lastSeen lessEq olderThan)
            }
        }
    }
}
