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

package com.moneat.alerts.services

import com.moneat.alerts.models.AlertEpisodeResponse
import com.moneat.alerts.models.AlertEpisodes
import com.moneat.alerts.models.AlertLifecycleEvent
import com.moneat.alerts.models.AlertSource
import com.moneat.shared.services.organizationResourceId
import com.moneat.shared.services.userResourceId
import com.moneat.shared.services.userResourceIds
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val FIRING_STATUS = "FIRING"
private const val RESOLVED_STATUS = "RESOLVED"
private const val INITIAL_NOTIFICATION_KIND = "initial"
private const val REMINDER_NOTIFICATION_KIND = "reminder"
private const val RESOLVED_NOTIFICATION_KIND = "resolved"
private const val MAX_SUPPRESS_REASON_CHARS = 255
private val ALERT_REMINDER_INTERVAL = 24.hours

private data class AlertEpisodeIdentity(
    val organizationId: Int,
    val source: String,
    val deduplicationKey: String,
)

private data class AlertEpisodeDisplayMetadata(
    val title: String?,
    val description: String?,
    val priority: String?,
) {
    companion object {
        val EMPTY = AlertEpisodeDisplayMetadata(title = null, description = null, priority = null)
    }
}

private fun AlertLifecycleEvent.displayMetadata(): AlertEpisodeDisplayMetadata =
    AlertEpisodeDisplayMetadata(
        title = title,
        description = description,
        priority = priority.wire,
    )

private fun AlertLifecycleEvent.episodeIdentity(): AlertEpisodeIdentity =
    AlertEpisodeIdentity(
        organizationId = organizationId,
        source = source.name,
        deduplicationKey = deduplicationKey,
    )

class AlertEpisodeService(
    private val alertSilenceService: AlertSilenceService = AlertSilenceService()
) {
    fun isOrganizationSilenced(organizationId: Int): Boolean =
        alertSilenceService.isOrganizationSilenced(organizationId)

    fun recordFiring(
        event: AlertLifecycleEvent,
        now: Instant = Clock.System.now()
    ): AlertEpisodeDecision? =
        recordFiring(
            identity = event.episodeIdentity(),
            metadata = event.displayMetadata(),
            now = now,
            reservePublication = true
        )

    fun recordFiringWithoutNotification(
        event: AlertLifecycleEvent,
        now: Instant = Clock.System.now()
    ): AlertEpisodeContext? =
        recordFiring(
            identity = event.episodeIdentity(),
            metadata = event.displayMetadata(),
            now = now,
            reservePublication = false
        )?.episode

    fun ensureOpenEpisodeForWorkflow(
        event: AlertLifecycleEvent,
        now: Instant = Clock.System.now()
    ): AlertEpisodeContext? {
        val decision = recordFiring(
            identity = event.episodeIdentity(),
            metadata = event.displayMetadata(),
            now = now,
            reservePublication = false
        )
        return decision?.takeIf { it.shouldPublish }?.episode
    }

    fun latestEpisodeForWorkflow(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String
    ): AlertEpisodeContext? =
        transaction {
            AlertEpisodes
                .selectAll()
                .where {
                    (AlertEpisodes.organizationId eq organizationId) and
                        (AlertEpisodes.sourceName eq source.name) and
                        (AlertEpisodes.deduplicationKey eq deduplicationKey)
                }
                .orderBy(AlertEpisodes.lastSeenAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toContext()
                ?.takeIf { it.suppressedAt == null && it.notificationCount > 0 }
        }

    fun recordResolved(
        event: AlertLifecycleEvent,
        now: Instant = Clock.System.now()
    ): AlertEpisodeDecision? =
        recordResolved(
            organizationId = event.organizationId,
            source = event.source.name,
            deduplicationKey = event.deduplicationKey,
            now = now
        )

    fun recordResolved(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        now: Instant = Clock.System.now()
    ): AlertEpisodeDecision? =
        recordResolved(organizationId, source.name, deduplicationKey, now)

    fun closeCurrentEpisode(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        now: Instant = Clock.System.now()
    ) {
        recordResolved(organizationId, source.name, deduplicationKey, now)
    }

    fun openCurrentEpisode(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        now: Instant = Clock.System.now()
    ): AlertEpisodeContext? =
        recordFiring(
            identity = AlertEpisodeIdentity(organizationId, source.name, deduplicationKey),
            metadata = AlertEpisodeDisplayMetadata.EMPTY,
            now = now,
            reservePublication = false
        )?.episode

    fun suppressCurrentEpisode(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        userId: Int?,
        reason: String?,
        now: Instant = Clock.System.now()
    ): AlertEpisodeResponse? =
        transaction {
            val row = findOpenEpisodeForUpdate(organizationId, source.name, deduplicationKey) ?: return@transaction null
            suppressEpisodeInTransaction(row[AlertEpisodes.id].value, userId, reason, now)
            findEpisode(row[AlertEpisodes.id].value, organizationId)?.toResponse()
        }

    fun unsuppressCurrentEpisode(
        organizationId: Int,
        source: AlertSource,
        deduplicationKey: String,
        now: Instant = Clock.System.now()
    ): AlertEpisodeResponse? =
        transaction {
            val row = findOpenEpisodeForUpdate(organizationId, source.name, deduplicationKey) ?: return@transaction null
            AlertEpisodes.update({ AlertEpisodes.id eq row[AlertEpisodes.id].value }) {
                it[suppressedAt] = null
                it[suppressedByUserId] = null
                it[suppressReason] = null
                it[updatedAt] = now
            }
            findEpisode(row[AlertEpisodes.id].value, organizationId)?.toResponse()
        }

    fun suppressEpisode(
        organizationId: Int,
        episodeId: Int,
        userId: Int?,
        reason: String?,
        now: Instant = Clock.System.now()
    ): AlertEpisodeResponse? =
        transaction {
            val row = findEpisodeForUpdate(episodeId, organizationId) ?: return@transaction null
            if (row[AlertEpisodes.status] != FIRING_STATUS) return@transaction row.toResponse()
            suppressEpisodeInTransaction(episodeId, userId, reason, now)
            findEpisode(episodeId, organizationId)?.toResponse()
        }

    fun unsuppressEpisode(
        organizationId: Int,
        episodeId: Int,
        now: Instant = Clock.System.now()
    ): AlertEpisodeResponse? =
        transaction {
            val row = findEpisodeForUpdate(episodeId, organizationId) ?: return@transaction null
            AlertEpisodes.update({ AlertEpisodes.id eq row[AlertEpisodes.id].value }) {
                it[suppressedAt] = null
                it[suppressedByUserId] = null
                it[suppressReason] = null
                it[updatedAt] = now
            }
            findEpisode(episodeId, organizationId)?.toResponse()
        }

    fun listEpisodes(
        organizationId: Int,
        status: String? = null,
        limit: Int = DEFAULT_LIST_LIMIT
    ): List<AlertEpisodeResponse> =
        transaction {
            val baseQuery = AlertEpisodes.selectAll()
            val query =
                if (status.isNullOrBlank()) {
                    baseQuery.where { AlertEpisodes.organizationId eq organizationId }
                } else {
                    baseQuery.where {
                        (AlertEpisodes.organizationId eq organizationId) and
                            (AlertEpisodes.status eq status.uppercase())
                    }
                }
            query
                .orderBy(AlertEpisodes.lastSeenAt to SortOrder.DESC)
                .limit(limit.coerceIn(MIN_LIST_LIMIT, MAX_LIST_LIMIT))
                .toList()
                .let { rows ->
                    val orgResourceId = organizationResourceId(organizationId)
                    val userResourceIds =
                        userResourceIds(rows.mapNotNull { row -> row[AlertEpisodes.suppressedByUserId] })
                    rows.map { row -> row.toResponse(orgResourceId, userResourceIds) }
                }
        }

    private fun recordFiring(
        identity: AlertEpisodeIdentity,
        metadata: AlertEpisodeDisplayMetadata,
        now: Instant,
        reservePublication: Boolean
    ): AlertEpisodeDecision? {
        repeat(OPEN_EPISODE_ATTEMPTS) {
            val decision =
                transaction {
                    val row = findOpenEpisodeForUpdate(
                        identity.organizationId,
                        identity.source,
                        identity.deduplicationKey,
                    ) ?: createOpenEpisode(identity, metadata, now)
                        ?: return@transaction null
                    updateLastSeen(row[AlertEpisodes.id].value, metadata, now)
                    val current = findEpisode(row[AlertEpisodes.id].value, identity.organizationId)
                        ?.toContext()
                        ?: return@transaction null
                    if (!reservePublication) {
                        return@transaction AlertEpisodeDecision(
                            episode = current,
                            shouldPublish = current.suppressedAt == null,
                            notificationSequence = current.notificationCount,
                            notificationKind = INITIAL_NOTIFICATION_KIND
                        )
                    }
                    reserveNotification(current, now)
                }
            if (decision != null) return decision
        }
        return null
    }

    private fun recordResolved(
        organizationId: Int,
        source: String,
        deduplicationKey: String,
        now: Instant
    ): AlertEpisodeDecision? =
        transaction {
            val row = findOpenEpisodeForUpdate(organizationId, source, deduplicationKey) ?: return@transaction null
            val episodeId = row[AlertEpisodes.id].value
            AlertEpisodes.update({ AlertEpisodes.id eq episodeId }) {
                it[status] = RESOLVED_STATUS
                it[lastSeenAt] = now
                it[resolvedAt] = now
                it[updatedAt] = now
            }
            val resolved = findEpisode(episodeId, organizationId)?.toContext() ?: return@transaction null
            val publishedBefore = row[AlertEpisodes.notificationCount] > 0
            AlertEpisodeDecision(
                episode = resolved,
                shouldPublish = row[AlertEpisodes.suppressedAt] == null && publishedBefore,
                notificationSequence = row[AlertEpisodes.notificationCount],
                notificationKind = RESOLVED_NOTIFICATION_KIND
            )
        }

    private fun reserveNotification(
        episode: AlertEpisodeContext,
        now: Instant
    ): AlertEpisodeDecision {
        if (episode.suppressedAt != null || !isNotificationDue(episode, now)) {
            return AlertEpisodeDecision(
                episode = episode,
                shouldPublish = false,
                notificationSequence = episode.notificationCount,
                notificationKind = REMINDER_NOTIFICATION_KIND
            )
        }

        val nextCount = episode.notificationCount + 1
        AlertEpisodes.update({ AlertEpisodes.id eq episode.id }) {
            it[lastNotificationAt] = now
            it[notificationCount] = nextCount
            it[updatedAt] = now
        }
        return AlertEpisodeDecision(
            episode = episode.copy(lastNotificationAt = now, notificationCount = nextCount),
            shouldPublish = true,
            notificationSequence = nextCount,
            notificationKind = if (nextCount == 1) INITIAL_NOTIFICATION_KIND else REMINDER_NOTIFICATION_KIND
        )
    }

    private fun isNotificationDue(
        episode: AlertEpisodeContext,
        now: Instant
    ): Boolean {
        val lastNotificationAt = episode.lastNotificationAt ?: return true
        return now - lastNotificationAt >= ALERT_REMINDER_INTERVAL
    }

    private fun createOpenEpisode(
        identity: AlertEpisodeIdentity,
        metadata: AlertEpisodeDisplayMetadata,
        now: Instant
    ): ResultRow? {
        val nextSeq = nextEpisodeSequence(
            identity.organizationId,
            identity.source,
            identity.deduplicationKey
        )
        AlertEpisodes.insertIgnore {
            it[AlertEpisodes.organizationId] = identity.organizationId
            it[AlertEpisodes.sourceName] = identity.source
            it[AlertEpisodes.deduplicationKey] = identity.deduplicationKey
            it[AlertEpisodes.title] = metadata.title
            it[AlertEpisodes.description] = metadata.description
            it[AlertEpisodes.priority] = metadata.priority
            it[AlertEpisodes.episodeSeq] = nextSeq
            it[AlertEpisodes.episodeKey] = episodeKey(identity.deduplicationKey, nextSeq)
            it[AlertEpisodes.status] = FIRING_STATUS
            it[AlertEpisodes.openedAt] = now
            it[AlertEpisodes.lastSeenAt] = now
            it[AlertEpisodes.notificationCount] = 0
            it[AlertEpisodes.createdAt] = now
            it[AlertEpisodes.updatedAt] = now
        }
        return findOpenEpisodeForUpdate(
            identity.organizationId,
            identity.source,
            identity.deduplicationKey
        )
    }

    private fun nextEpisodeSequence(
        organizationId: Int,
        source: String,
        deduplicationKey: String
    ): Int {
        val latestSequence = AlertEpisodes
            .selectAll()
            .where {
                (AlertEpisodes.organizationId eq organizationId) and
                    (AlertEpisodes.sourceName eq source) and
                    (AlertEpisodes.deduplicationKey eq deduplicationKey)
            }
            .orderBy(AlertEpisodes.episodeSeq to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(AlertEpisodes.episodeSeq) ?: 0

        return latestSequence + 1
    }

    private fun episodeKey(
        deduplicationKey: String,
        episodeSeq: Int
    ): String =
        "$deduplicationKey#$episodeSeq"

    private fun updateLastSeen(
        episodeId: Int,
        metadata: AlertEpisodeDisplayMetadata,
        now: Instant
    ) {
        AlertEpisodes.update({ AlertEpisodes.id eq episodeId }) {
            if (metadata.title != null) {
                it[AlertEpisodes.title] = metadata.title
            }
            if (metadata.description != null) {
                it[AlertEpisodes.description] = metadata.description
            }
            if (metadata.priority != null) {
                it[AlertEpisodes.priority] = metadata.priority
            }
            it[lastSeenAt] = now
            it[updatedAt] = now
        }
    }

    private fun suppressEpisodeInTransaction(
        episodeId: Int,
        userId: Int?,
        reason: String?,
        now: Instant
    ) {
        AlertEpisodes.update({ AlertEpisodes.id eq episodeId }) {
            it[suppressedAt] = now
            it[suppressedByUserId] = userId
            it[suppressReason] = reason?.take(MAX_SUPPRESS_REASON_CHARS)
            it[updatedAt] = now
        }
    }

    private fun findOpenEpisodeForUpdate(
        organizationId: Int,
        source: String,
        deduplicationKey: String
    ): ResultRow? =
        AlertEpisodes
            .selectAll()
            .where {
                (AlertEpisodes.organizationId eq organizationId) and
                    (AlertEpisodes.sourceName eq source) and
                    (AlertEpisodes.deduplicationKey eq deduplicationKey) and
                    (AlertEpisodes.status eq FIRING_STATUS)
            }
            .forUpdate()
            .firstOrNull()

    private fun findEpisodeForUpdate(
        episodeId: Int,
        organizationId: Int
    ): ResultRow? =
        AlertEpisodes
            .selectAll()
            .where {
                (AlertEpisodes.id eq episodeId) and
                    (AlertEpisodes.organizationId eq organizationId)
            }
            .forUpdate()
            .firstOrNull()

    private fun findEpisode(
        episodeId: Int,
        organizationId: Int
    ): ResultRow? =
        AlertEpisodes
            .selectAll()
            .where {
                (AlertEpisodes.id eq episodeId) and
                    (AlertEpisodes.organizationId eq organizationId)
            }
            .firstOrNull()

    private fun ResultRow.toContext(): AlertEpisodeContext =
        AlertEpisodeContext(
            id = this[AlertEpisodes.id].value,
            resourceId = this[AlertEpisodes.resourceId],
            organizationId = this[AlertEpisodes.organizationId],
            source = this[AlertEpisodes.sourceName],
            deduplicationKey = this[AlertEpisodes.deduplicationKey],
            title = this[AlertEpisodes.title],
            description = this[AlertEpisodes.description],
            priority = this[AlertEpisodes.priority],
            episodeSeq = this[AlertEpisodes.episodeSeq],
            episodeKey = this[AlertEpisodes.episodeKey],
            status = this[AlertEpisodes.status],
            openedAt = this[AlertEpisodes.openedAt],
            lastSeenAt = this[AlertEpisodes.lastSeenAt],
            resolvedAt = this[AlertEpisodes.resolvedAt],
            lastNotificationAt = this[AlertEpisodes.lastNotificationAt],
            notificationCount = this[AlertEpisodes.notificationCount],
            suppressedAt = this[AlertEpisodes.suppressedAt],
            suppressedByUserId = this[AlertEpisodes.suppressedByUserId],
            suppressReason = this[AlertEpisodes.suppressReason]
        )

    private fun ResultRow.toResponse(): AlertEpisodeResponse {
        val context = toContext()
        return toResponse(
            orgResourceId = organizationResourceId(context.organizationId),
            userResourceIds = context.suppressedByUserId?.let { mapOf(it to userResourceId(it)) }
                .orEmpty()
        )
    }

    private fun ResultRow.toResponse(
        orgResourceId: String,
        userResourceIds: Map<Int, String>
    ): AlertEpisodeResponse {
        val context = toContext()
        return AlertEpisodeResponse(
            id = this[AlertEpisodes.resourceId].toString(),
            organizationId = orgResourceId,
            source = context.source,
            deduplicationKey = context.deduplicationKey,
            title = context.title,
            description = context.description,
            priority = context.priority,
            episodeSeq = context.episodeSeq,
            episodeKey = context.episodeKey,
            status = context.status,
            openedAt = context.openedAt.toString(),
            lastSeenAt = context.lastSeenAt.toString(),
            resolvedAt = context.resolvedAt?.toString(),
            lastNotificationAt = context.lastNotificationAt?.toString(),
            notificationCount = context.notificationCount,
            suppressedAt = context.suppressedAt?.toString(),
            suppressedByUserId = context.suppressedByUserId?.let(userResourceIds::get),
            suppressReason = context.suppressReason,
            createdAt = this[AlertEpisodes.createdAt].toString(),
            updatedAt = this[AlertEpisodes.updatedAt].toString()
        )
    }

    private companion object {
        const val DEFAULT_LIST_LIMIT = 50
        const val MIN_LIST_LIMIT = 1
        const val MAX_LIST_LIMIT = 100
        const val OPEN_EPISODE_ATTEMPTS = 2
    }
}

data class AlertEpisodeContext(
    val id: Int,
    val resourceId: Uuid,
    val organizationId: Int,
    val source: String,
    val deduplicationKey: String,
    val title: String? = null,
    val description: String? = null,
    val priority: String? = null,
    val episodeSeq: Int,
    val episodeKey: String,
    val status: String,
    val openedAt: Instant,
    val lastSeenAt: Instant,
    val resolvedAt: Instant?,
    val lastNotificationAt: Instant?,
    val notificationCount: Int,
    val suppressedAt: Instant?,
    val suppressedByUserId: Int?,
    val suppressReason: String?
)

data class AlertEpisodeDecision(
    val episode: AlertEpisodeContext,
    val shouldPublish: Boolean,
    val notificationSequence: Int,
    val notificationKind: String
)
