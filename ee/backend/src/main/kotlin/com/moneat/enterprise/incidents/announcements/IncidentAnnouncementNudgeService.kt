// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.incidents.announcements

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Persists nudge visibility, dismissal, and activity state for Slack announcement cards. */
class IncidentAnnouncementNudgeService(
    private val clock: Clock = Clock.System,
) {
    data class Scope(
        val organizationId: Int,
        val incidentId: Int,
        val ruleKey: String,
        val teamId: String,
        val channelId: String,
    )

    fun visibleKeys(
        scope: Scope,
        applicableKeys: Set<String>,
    ): Set<String> = transaction {
        resetSatisfied(
            scope = scope,
            applicableKeys = applicableKeys,
        )
        val now = clock.now()
        val rows = NativeIncidentAnnouncementNudges
            .selectAll()
            .where {
                (NativeIncidentAnnouncementNudges.organizationId eq scope.organizationId) and
                    (NativeIncidentAnnouncementNudges.incidentId eq scope.incidentId) and
                    (NativeIncidentAnnouncementNudges.ruleKey eq scope.ruleKey) and
                    (NativeIncidentAnnouncementNudges.teamId eq scope.teamId) and
                    (NativeIncidentAnnouncementNudges.channelId eq scope.channelId)
            }
            .toList()
        val existingKeys = rows.map { it[NativeIncidentAnnouncementNudges.nudgeKey] }.toSet()
        rows.filter { row ->
                row[NativeIncidentAnnouncementNudges.nudgeKey] in applicableKeys &&
                    row[NativeIncidentAnnouncementNudges.dismissedAt] == null &&
                    ready(row[NativeIncidentAnnouncementNudges.lastShownAt], now) &&
                    ready(row[NativeIncidentAnnouncementNudges.lastActivityAt], now)
        }.map { it[NativeIncidentAnnouncementNudges.nudgeKey] }.toSet() + (applicableKeys - existingKeys)
    }

    fun recordShown(
        scope: Scope,
        keys: Set<String>,
        version: Int,
    ) {
        if (keys.isEmpty()) return
        transaction {
            val now = clock.now()
            keys.forEach { key ->
                val predicate = nudgePredicate(scope, key)
                val updated = NativeIncidentAnnouncementNudges.update({ predicate }) {
                    it[lastShownAt] = now
                    it[lastShownVersion] = version
                    it[lastActivityAt] = now
                    it[updatedAt] = now
                }
                if (updated == 0) {
                    NativeIncidentAnnouncementNudges.insert {
                        it[resourceId] = Uuid.random()
                        it[NativeIncidentAnnouncementNudges.organizationId] = scope.organizationId
                        it[NativeIncidentAnnouncementNudges.incidentId] = scope.incidentId
                        it[NativeIncidentAnnouncementNudges.ruleKey] = scope.ruleKey
                        it[NativeIncidentAnnouncementNudges.teamId] = scope.teamId
                        it[NativeIncidentAnnouncementNudges.channelId] = scope.channelId
                        it[nudgeKey] = key
                        it[dismissedBy] = null
                        it[dismissedAt] = null
                        it[lastShownAt] = now
                        it[lastShownVersion] = version
                        it[lastActivityAt] = now
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
        }
    }

    fun dismiss(organizationId: Int, incidentId: Int, nudgeKey: String, userId: Int): Boolean = transaction {
        val now = clock.now()
        NativeIncidentAnnouncementNudges.update({
            (NativeIncidentAnnouncementNudges.organizationId eq organizationId) and
                (NativeIncidentAnnouncementNudges.incidentId eq incidentId) and
                (NativeIncidentAnnouncementNudges.nudgeKey eq nudgeKey)
        }) {
            it[dismissedBy] = userId
            it[dismissedAt] = now
            it[updatedAt] = now
        } > 0
    }

    private fun resetSatisfied(scope: Scope, applicableKeys: Set<String>) {
        val predicate = (NativeIncidentAnnouncementNudges.organizationId eq scope.organizationId) and
            (NativeIncidentAnnouncementNudges.incidentId eq scope.incidentId) and
            (NativeIncidentAnnouncementNudges.ruleKey eq scope.ruleKey) and
            (NativeIncidentAnnouncementNudges.teamId eq scope.teamId) and
            (NativeIncidentAnnouncementNudges.channelId eq scope.channelId)
        NativeIncidentAnnouncementNudges
            .selectAll()
            .where { predicate }
            .filter { row -> row[NativeIncidentAnnouncementNudges.nudgeKey] !in applicableKeys }
            .forEach { row ->
                NativeIncidentAnnouncementNudges.update({
                    NativeIncidentAnnouncementNudges.id eq row[NativeIncidentAnnouncementNudges.id]
                }) {
                    it[dismissedBy] = null
                    it[dismissedAt] = null
                    it[lastShownAt] = null
                    it[lastShownVersion] = null
                    it[lastActivityAt] = null
                    it[updatedAt] = clock.now()
                }
            }
    }

    private fun nudgePredicate(scope: Scope, nudgeKey: String) =
        (NativeIncidentAnnouncementNudges.organizationId eq scope.organizationId) and
            (NativeIncidentAnnouncementNudges.incidentId eq scope.incidentId) and
            (NativeIncidentAnnouncementNudges.ruleKey eq scope.ruleKey) and
            (NativeIncidentAnnouncementNudges.teamId eq scope.teamId) and
            (NativeIncidentAnnouncementNudges.channelId eq scope.channelId) and
            (NativeIncidentAnnouncementNudges.nudgeKey eq nudgeKey)

    private fun ready(lastAt: Instant?, now: Instant): Boolean =
        lastAt == null || now - lastAt >= NUDGE_COOLDOWN

    companion object {
        private val NUDGE_COOLDOWN = 5.minutes
    }
}
