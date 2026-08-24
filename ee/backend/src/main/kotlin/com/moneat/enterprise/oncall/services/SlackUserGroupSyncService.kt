// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.oncall.services

import com.moneat.config.RedisClient
import com.moneat.enterprise.oncall.models.OnCallScheduleUsergroups
import com.moneat.notifications.services.SlackService
import com.moneat.shared.models.OnCallSchedules
import com.moneat.shared.models.OrganizationIntegrations
import com.moneat.shared.models.SlackUserMappings
import com.moneat.shared.services.TaskLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

private const val ONE_HOUR_SECONDS = 3600L

/**
 * Background service that syncs on-call schedules to Slack user groups.
 * Runs every 60 seconds and updates Slack user groups to contain:
 * - Every current responder (if they have a Slack mapping)
 * - The Moneat bot user
 *
 * Uses Redis caching to avoid redundant API calls when membership hasn't changed.
 */
class SlackUserGroupSyncService(
    private val onCallScheduleService: OnCallScheduleService,
    private val slackService: SlackService,
    private val redisClient: RedisClient,
) {
    private val logger = LoggerFactory.getLogger(SlackUserGroupSyncService::class.java)
    private var syncJob: Job? = null

    companion object {
        private const val SYNC_INTERVAL_MS = 60_000L // 60 seconds
        private const val REDIS_KEY_PREFIX = "oncall:usergroup:sync:"
    }

    fun start() {
        if (syncJob?.isActive == true) {
            logger.warn("SlackUserGroupSyncService is already running")
            return
        }

        syncJob =
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                logger.info("SlackUserGroupSyncService started")

                while (isActive) {
                    TaskLock.tryWithLock("oncall-slack-sync", lockAtMostFor = 5.minutes) {
                        syncAllSchedules()
                    }

                    delay(SYNC_INTERVAL_MS)
                }

                logger.info("SlackUserGroupSyncService stopped")
            }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        logger.info("SlackUserGroupSyncService stopped")
    }

    private suspend fun syncAllSchedules() {
        val schedules = getSchedulesWithUsergroups()

        if (schedules.isEmpty()) {
            logger.debug("No schedules with Slack usergroup mappings")
            return
        }

        logger.debug("Syncing ${schedules.size} schedule(s) with Slack usergroups")

        // Group schedules by organization to batch token lookups
        val schedulesByOrg = schedules.groupBy { it.organizationId }

        schedulesByOrg.forEach { (organizationId, orgSchedules) ->
            try {
                syncOrganizationSchedules(organizationId, orgSchedules)
            } catch (e: Exception) {
                logger.error("Error syncing schedules for organization $organizationId", e)
            }
        }
    }

    private suspend fun syncOrganizationSchedules(
        organizationId: Int,
        schedules: List<ScheduleUsergroupMapping>,
    ) {
        // Get Slack access token and bot user ID for this organization
        val slackConfig = getSlackConfig(organizationId)
        if (slackConfig == null) {
            logger.debug("No Slack integration found for organization $organizationId")
            return
        }

        schedules.forEach { schedule ->
            try {
                syncSchedule(schedule, slackConfig)
            } catch (e: Exception) {
                logger.error("Error syncing schedule ${schedule.scheduleId} (${schedule.scheduleName})", e)
            }
        }
    }

    private suspend fun syncSchedule(
        schedule: ScheduleUsergroupMapping,
        slackConfig: SlackConfig,
    ) {
        val responders =
            onCallScheduleService.resolveCurrentResponderRecords(
                organizationId = schedule.organizationId,
                scheduleIds = listOf(schedule.scheduleId),
                all = true,
            )

        // Build target member list: bot + every current responder (if mapped)
        val targetMembers =
            buildList {
                add(slackConfig.botUserId)
                responders.forEach { responder ->
                    val slackId = getSlackUserId(responder.internalUserId)
                    if (slackId != null) {
                        add(slackId)
                    } else {
                        val responderDescription = "${responder.model.userId} (${responder.model.userName})"
                        logger.warn(
                            "On-call user $responderDescription has no Slack mapping " +
                                "for schedule ${schedule.scheduleName}",
                        )
                    }
                }
                if (responders.isEmpty()) {
                    logger.warn("Schedule ${schedule.scheduleName} has no current on-call user")
                }
            }.distinct().sorted() // Sort for consistent comparison

        // Check Redis cache to see if we already synced this state
        val cacheKey = "$REDIS_KEY_PREFIX${schedule.scheduleId}"
        val cachedState = redisClient.get(cacheKey)
        val targetState = targetMembers.joinToString(",")

        if (cachedState == targetState) {
            logger.debug("Schedule ${schedule.scheduleName} usergroup already in sync (cached)")
            return
        }

        // Update Slack usergroup
        val success =
            slackService.updateUsergroupMembers(
                accessToken = slackConfig.accessToken,
                usergroupId = schedule.usergroupId,
                userIds = targetMembers,
            )

        if (success) {
            logger.info(
                "Synced Slack usergroup ${schedule.usergroupHandle} for schedule ${schedule.scheduleName} " +
                    "with ${targetMembers.size} member(s)",
            )
            // Cache the new state
            redisClient.set(cacheKey, targetState)
            redisClient.expire(cacheKey, ONE_HOUR_SECONDS) // Expire in 1 hour as a safety net
        } else {
            logger.error(
                "Failed to sync Slack usergroup ${schedule.usergroupHandle} for schedule ${schedule.scheduleName}",
            )
        }
    }

    /**
     * Trigger an immediate sync for a specific schedule (called when mapping is created/updated)
     */
    suspend fun syncScheduleNow(scheduleId: Int) {
        val schedule = getScheduleUsergroupMapping(scheduleId)
        if (schedule == null) {
            logger.debug("No usergroup mapping found for schedule $scheduleId")
            return
        }

        val slackConfig = getSlackConfig(schedule.organizationId)
        if (slackConfig == null) {
            logger.warn("No Slack integration found for organization ${schedule.organizationId}")
            return
        }

        // Invalidate cache to force sync
        val cacheKey = "$REDIS_KEY_PREFIX$scheduleId"
        redisClient.del(cacheKey)

        syncSchedule(schedule, slackConfig)
    }

    // ===== Database Helpers =====

    private data class ScheduleUsergroupMapping(
        val scheduleId: Int,
        val scheduleName: String,
        val organizationId: Int,
        val usergroupId: String,
        val usergroupHandle: String,
    )

    private data class SlackConfig(
        val accessToken: String,
        val botUserId: String,
    )

    private fun getSchedulesWithUsergroups(): List<ScheduleUsergroupMapping> =
        transaction {
            OnCallScheduleUsergroups
                .innerJoin(OnCallSchedules)
                .selectAll()
                .map { row ->
                    ScheduleUsergroupMapping(
                        scheduleId = row[OnCallScheduleUsergroups.scheduleId],
                        scheduleName = row[OnCallSchedules.name],
                        organizationId = row[OnCallSchedules.organizationId],
                        usergroupId = row[OnCallScheduleUsergroups.slackUsergroupId],
                        usergroupHandle = row[OnCallScheduleUsergroups.slackUsergroupHandle],
                    )
                }
        }

    private fun getScheduleUsergroupMapping(scheduleId: Int): ScheduleUsergroupMapping? =
        transaction {
            OnCallScheduleUsergroups
                .innerJoin(OnCallSchedules)
                .selectAll()
                .where { OnCallScheduleUsergroups.scheduleId eq scheduleId }
                .singleOrNull()
                ?.let { row ->
                    ScheduleUsergroupMapping(
                        scheduleId = row[OnCallScheduleUsergroups.scheduleId],
                        scheduleName = row[OnCallSchedules.name],
                        organizationId = row[OnCallSchedules.organizationId],
                        usergroupId = row[OnCallScheduleUsergroups.slackUsergroupId],
                        usergroupHandle = row[OnCallScheduleUsergroups.slackUsergroupHandle],
                    )
                }
        }

    private fun getSlackConfig(organizationId: Int): SlackConfig? =
        transaction {
            OrganizationIntegrations
                .selectAll()
                .where {
                    (OrganizationIntegrations.organization_id eq organizationId) and
                        (OrganizationIntegrations.integration_type eq "slack") and
                        (OrganizationIntegrations.enabled eq true)
                }.singleOrNull()
                ?.let { row ->
                    val accessToken = row[OrganizationIntegrations.access_token]
                    val botUserId = row[OrganizationIntegrations.bot_user_id]
                    if (accessToken != null && botUserId != null) {
                        SlackConfig(accessToken, botUserId)
                    } else {
                        null
                    }
                }
        }

    private fun getSlackUserId(userId: Int): String? =
        transaction {
            SlackUserMappings
                .selectAll()
                .where { SlackUserMappings.userId eq userId }
                .singleOrNull()
                ?.get(SlackUserMappings.slackUserId)
        }
}
