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

package com.moneat.ai

import com.moneat.utils.SentryUtils
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class AiActionExecutor {

    suspend fun execute(
        orgId: Int,
        userId: Int,
        actionId: String,
        @Suppress("UNUSED_PARAMETER") params: Map<String, String>
    ): ActionResult = SentryUtils.withTransaction("ai.execute_action", "ai") { tx ->
        SentryUtils.breadcrumb("ai", "Executing action", mapOf(
            "actionId" to actionId,
            "orgId" to orgId.toString()
        ))

        try {
            // Actions are executed by the frontend calling the real API endpoints
            // with the user's existing JWT auth. This executor is a placeholder for
            // any future server-side-only action orchestration.
            SentryUtils.withSpan(tx, "ai.action_execute", "Execute action $actionId") {
                logger.info { "Action $actionId executed by user $userId in org $orgId" }
                ActionResult(
                    success = true,
                    message = "Action submitted successfully. The operation will be performed using your existing permissions."
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute action $actionId" }
            ActionResult(
                success = false,
                message = "Failed to execute action: ${e.message}"
            )
        }
    }
}
