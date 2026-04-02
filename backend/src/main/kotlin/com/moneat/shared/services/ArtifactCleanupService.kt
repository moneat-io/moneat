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

package com.moneat.shared.services

import com.moneat.auth.services.AuthTokenService
import com.moneat.org.services.OrgInvitationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.time.Duration.Companion.hours
import com.moneat.utils.suspendRunCatching

private val logger = KotlinLogging.logger {}

/**
 * Periodically purges expired auth tokens and old org invitations to prevent
 * unbounded storage growth. Auth tokens are rejected at read-time when expired
 * but accumulate until purged; invitations are status-flipped but never deleted.
 */
class ArtifactCleanupService(
    private val authTokenService: AuthTokenService,
    private val orgInvitationService: OrgInvitationService,
    private val cleanupInterval: kotlin.time.Duration = 24.hours
) {
    private var cleanupJob: Job? = null

    companion object {
        private const val INVITATION_EXPIRY_DAYS = 90
    }

    fun start(scope: CoroutineScope) {
        logger.info { "Starting artifact cleanup service (auth tokens, invitations)" }
        cleanupJob =
            scope.launch {
                while (isActive) {
                    suspendRunCatching {
                        val authDeleted = authTokenService.cleanupExpiredTokens()
                        if (authDeleted > 0) {
                            logger.info { "Cleaned up $authDeleted expired auth tokens" }
                        }
                        val inviteDeleted = orgInvitationService.purgeOldInvitations(INVITATION_EXPIRY_DAYS)
                        if (inviteDeleted > 0) {
                            logger.info { "Purged $inviteDeleted old invitations" }
                        }
                    }.getOrElse { e ->
                        logger.error(e) { "Error during artifact cleanup" }
                    }
                    delay(cleanupInterval)
                }
            }
    }

    fun stop() {
        logger.info { "Stopping artifact cleanup service" }
        cleanupJob?.cancel()
    }
}
