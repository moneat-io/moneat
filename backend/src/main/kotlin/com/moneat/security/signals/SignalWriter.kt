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

package com.moneat.security.signals

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val UPSERT_MAX_ATTEMPTS = 3

private val signalJson = Json { encodeDefaults = true }

/**
 * Writes signals with **open-signal dedup**: while a signal is `open`, a repeat for the same
 * `(organizationId, ruleId, dedupKey)` folds into it — incrementing the sample count, raising
 * severity to the max seen, bumping `last_seen`, and appending an evidence reference. Once a
 * signal is archived a fresh occurrence opens a new signal.
 *
 * Race-safety: the fold is decided inside a single transaction against the live row. On Postgres a
 * partial unique index `(organization_id, rule_id, dedup_key) WHERE status = 'open'` makes two
 * concurrent inserts collide; the loser catches the unique violation and retries, on which pass it
 * now sees the winner's open row and folds. H2 (tests) has no partial index, so dedup there rests on
 * the in-transaction lookup, which is sufficient for the single-writer test path.
 */
object SignalWriter {

    fun upsert(organizationId: Int, spec: SignalSpec): SignalOutcome {
        repeat(UPSERT_MAX_ATTEMPTS) { attempt ->
            val outcome = attemptUpsert(organizationId, spec, lastAttempt = attempt == UPSERT_MAX_ATTEMPTS - 1)
            if (outcome != null) return outcome
        }
        // Unreachable: the final attempt never swallows the unique violation. Fold as a last resort.
        return foldOrThrow(organizationId, spec)
    }

    /**
     * One upsert pass. Returns null only when an insert lost the open-dedup race and a retry is
     * warranted; on the final attempt the violation is rethrown so the caller never silently drops.
     */
    private fun attemptUpsert(organizationId: Int, spec: SignalSpec, lastAttempt: Boolean): SignalOutcome? =
        transaction {
            val existing = findOpenSignal(organizationId, spec)
            if (existing != null) {
                return@transaction foldIntoExisting(existing, spec)
            }
            try {
                createNewSignal(organizationId, spec)
            } catch (e: ExposedSQLException) {
                if (e.sqlState == UNIQUE_VIOLATION_SQL_STATE && !lastAttempt) {
                    null
                } else {
                    throw e
                }
            }
        }

    private fun foldOrThrow(organizationId: Int, spec: SignalSpec): SignalOutcome =
        transaction {
            val existing = findOpenSignal(organizationId, spec)
                ?: error("Open signal dedup race did not resolve for rule ${spec.ruleId}")
            foldIntoExisting(existing, spec)
        }

    private fun findOpenSignal(organizationId: Int, spec: SignalSpec): OpenSignalRow? =
        SecuritySignals
            .selectAll()
            .where {
                (SecuritySignals.organizationId eq organizationId) and
                    (SecuritySignals.ruleId eq spec.ruleId) and
                    (SecuritySignals.dedupKey eq spec.dedupKey) and
                    (SecuritySignals.status eq SignalStatus.OPEN.wire)
            }
            .forUpdate()
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                OpenSignalRow(
                    id = row[SecuritySignals.id].value,
                    organizationId = row[SecuritySignals.organizationId],
                    severity = SignalSeverity.fromWire(row[SecuritySignals.severity]),
                    sampleCount = row[SecuritySignals.sampleCount],
                    firstSeen = row[SecuritySignals.firstSeen],
                    lastSeen = row[SecuritySignals.lastSeen],
                )
            }

    private fun foldIntoExisting(existing: OpenSignalRow, spec: SignalSpec): SignalOutcome {
        val now = Clock.System.now()
        val occurred = occurrenceTime(spec.occurredAtMs, now)
        val escalated = spec.severity.rank > existing.severity.rank
        val newSeverity = if (escalated) spec.severity else existing.severity
        SecuritySignals.update({ SecuritySignals.id eq existing.id }) {
            it[ruleName] = spec.ruleName
            it[sampleCount] = existing.sampleCount + 1
            it[severity] = newSeverity.wire
            it[entities] = signalJson.encodeToString(spec.entities)
            it[tags] = signalJson.encodeToString(spec.tags)
            // Agent events can arrive out of order, so clamp the window monotonically: never move
            // last_seen backwards (it is the list sort key and time-filter bound) and lower first_seen
            // only when an earlier occurrence folds in.
            it[firstSeen] = minOf(existing.firstSeen, occurred)
            it[lastSeen] = maxOf(existing.lastSeen, occurred)
            it[updatedAt] = now
        }
        insertEvidence(existing.id, spec, now)
        return if (escalated) {
            outcome(SignalOutcomeKind.ESCALATED, existing.id, existing.organizationId, spec, newSeverity)
        } else {
            outcome(SignalOutcomeKind.UPDATED, existing.id, existing.organizationId, spec, newSeverity)
        }
    }

    private fun createNewSignal(organizationId: Int, spec: SignalSpec): SignalOutcome {
        val now = Clock.System.now()
        val occurred = occurrenceTime(spec.occurredAtMs, now)
        val signalId = SecuritySignals.insertAndGetId {
            it[SecuritySignals.organizationId] = organizationId
            it[signalSource] = spec.source.wire
            it[ruleId] = spec.ruleId
            it[ruleName] = spec.ruleName
            it[severity] = spec.severity.wire
            it[status] = SignalStatus.OPEN.wire
            it[dedupKey] = spec.dedupKey
            it[entities] = signalJson.encodeToString(spec.entities)
            it[sampleCount] = 1
            it[tags] = signalJson.encodeToString(spec.tags)
            it[firstSeen] = occurred
            it[lastSeen] = occurred
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        insertEvidence(signalId, spec, now)
        return outcome(SignalOutcomeKind.CREATED, signalId, organizationId, spec, spec.severity)
    }

    private fun insertEvidence(signalId: Int, spec: SignalSpec, now: kotlin.time.Instant) {
        SecuritySignalEvidence.insert {
            it[SecuritySignalEvidence.signalId] = signalId
            it[evidenceType] = spec.evidenceType
            it[reference] = spec.evidenceReference
            it[createdAt] = now
        }
    }

    private fun outcome(
        kind: SignalOutcomeKind,
        signalId: Int,
        organizationId: Int,
        spec: SignalSpec,
        severity: SignalSeverity,
    ): SignalOutcome =
        when (kind) {
            SignalOutcomeKind.CREATED -> SignalOutcome.Created(
                signalId,
                organizationId,
                spec.source,
                spec.ruleId,
                spec.ruleName,
                severity,
                spec.dedupKey,
                spec.entities,
            )
            SignalOutcomeKind.ESCALATED -> SignalOutcome.Escalated(
                signalId,
                organizationId,
                spec.source,
                spec.ruleId,
                spec.ruleName,
                severity,
                spec.dedupKey,
                spec.entities,
            )
            SignalOutcomeKind.UPDATED -> SignalOutcome.Updated(
                signalId,
                organizationId,
                spec.source,
                spec.ruleId,
                spec.ruleName,
                severity,
                spec.dedupKey,
                spec.entities,
            )
        }

    /**
     * The occurrence time clamped so a bad future timestamp from a producer cannot push `last_seen`
     * ahead of wall-clock now.
     */
    private fun occurrenceTime(occurredAtMs: Long, now: kotlin.time.Instant): kotlin.time.Instant {
        val occurred = kotlin.time.Instant.fromEpochMilliseconds(occurredAtMs)
        return if (occurred > now) now else occurred
    }

    private data class OpenSignalRow(
        val id: Int,
        val organizationId: Int,
        val severity: SignalSeverity,
        val sampleCount: Int,
        val firstSeen: kotlin.time.Instant,
        val lastSeen: kotlin.time.Instant,
    )

    private enum class SignalOutcomeKind {
        CREATED,
        ESCALATED,
        UPDATED,
    }
}
