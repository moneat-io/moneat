// Moneat - Mobile-First Error Monitoring Platform
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

package com.moneat.services.oncall

import com.moneat.config.RedisClient
import com.moneat.models.*
import com.moneat.services.SlackService
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Background service that syncs on-call schedules to Slack user groups.
 * Runs every 60 seconds and updates Slack user groups to contain:
 * - The current on-call user (if they have a Slack mapping)
 * - The Moneat bot user
 *
 * Uses Redis caching to avoid redundant API calls when membership hasn't changed.
 */
class SlackUserGroupSyncService(
    private val onCallScheduleService: OnCallScheduleService,
    private val slackService: SlackService,
    private val redisClient: RedisClient
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
        
        syncJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            logger.info("SlackUserGroupSyncService started")
            
            while (isActive) {
                try {
                    syncAllSchedules()
                } catch (e: Exception) {
                    logger.error("Error in sync loop", e)
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
    
    private suspend fun syncOrganizationSchedules(organizationId: Int, schedules: List<ScheduleUsergroupMapping>) {
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
    
    private suspend fun syncSchedule(schedule: ScheduleUsergroupMapping, slackConfig: SlackConfig) {
        // Get current on-call user
        val currentOnCall = onCallScheduleService.getCurrentOnCall(schedule.scheduleId)
        
        // Resolve on-call user's Slack ID (if mapped)
        val onCallSlackId = currentOnCall?.let { getSlackUserId(it.userId) }
        
        // Build target member list: bot + on-call user (if mapped)
        val targetMembers = buildList {
            add(slackConfig.botUserId)
            if (onCallSlackId != null) {
                add(onCallSlackId)
            } else if (currentOnCall != null) {
                logger.warn("On-call user ${currentOnCall.userId} (${currentOnCall.userName}) has no Slack mapping for schedule ${schedule.scheduleName}")
            } else {
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
        val success = slackService.updateUsergroupMembers(
            accessToken = slackConfig.accessToken,
            usergroupId = schedule.usergroupId,
            userIds = targetMembers
        )
        
        if (success) {
            logger.info("Synced Slack usergroup ${schedule.usergroupHandle} for schedule ${schedule.scheduleName} with ${targetMembers.size} member(s)")
            // Cache the new state
            redisClient.set(cacheKey, targetState)
            redisClient.expire(cacheKey, 3600) // Expire in 1 hour as a safety net
        } else {
            logger.error("Failed to sync Slack usergroup ${schedule.usergroupHandle} for schedule ${schedule.scheduleName}")
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
        val usergroupHandle: String
    )
    
    private data class SlackConfig(
        val accessToken: String,
        val botUserId: String
    )
    
    private fun getSchedulesWithUsergroups(): List<ScheduleUsergroupMapping> = transaction {
        OnCallScheduleUsergroups
            .innerJoin(OnCallSchedules)
            .selectAll()
            .map { row ->
                ScheduleUsergroupMapping(
                    scheduleId = row[OnCallScheduleUsergroups.scheduleId],
                    scheduleName = row[OnCallSchedules.name],
                    organizationId = row[OnCallSchedules.organizationId],
                    usergroupId = row[OnCallScheduleUsergroups.slackUsergroupId],
                    usergroupHandle = row[OnCallScheduleUsergroups.slackUsergroupHandle]
                )
            }
    }
    
    private fun getScheduleUsergroupMapping(scheduleId: Int): ScheduleUsergroupMapping? = transaction {
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
                    usergroupHandle = row[OnCallScheduleUsergroups.slackUsergroupHandle]
                )
            }
    }
    
    private fun getSlackConfig(organizationId: Int): SlackConfig? = transaction {
        OrganizationIntegrations
            .selectAll()
            .where {
                (OrganizationIntegrations.organization_id eq organizationId) and
                (OrganizationIntegrations.integration_type eq "slack") and
                (OrganizationIntegrations.enabled eq true)
            }
            .singleOrNull()
            ?.let { row ->
                val accessToken = row[OrganizationIntegrations.access_token]
                val botUserId = row[OrganizationIntegrations.bot_user_id]
                if (accessToken != null && botUserId != null) {
                    SlackConfig(accessToken, botUserId)
                } else null
            }
    }
    
    private fun getSlackUserId(userId: Int): String? = transaction {
        SlackUserMappings
            .selectAll()
            .where { SlackUserMappings.userId eq userId }
            .singleOrNull()
            ?.get(SlackUserMappings.slackUserId)
    }
}
