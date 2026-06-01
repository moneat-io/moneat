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

package com.moneat.config

import com.moneat.security.detection.DetectionRuleService
import com.moneat.security.detection.DetectionRules
import com.moneat.security.detection.DetectionStarterPack
import com.moneat.utils.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

// ── Detection starter-pack demo seed ───────────────────────────────────
//
// Seeds the Moneat starter-pack rules as DISABLED detection rules for the demo org so the demo
// Detections tab is not empty. Unlike the ClickHouse demo data, detection rules live in Postgres
// (no TTL), so this is a one-time, idempotent insert rather than a purge-and-reseed: it is skipped
// once the demo org already owns starter rules. Each rule is created through the compiler-gated
// DetectionRuleService.create path, so a demo rule is sandboxed exactly like any other.

/** Postgres demo organization id (the single demo org seeded by V50). */
private const val DEMO_ORG_ID = -1

/**
 * Idempotently seed the starter pack for the demo org. Non-fatal: any failure is logged and swallowed
 * so it never blocks the rest of the demo reseed.
 */
internal suspend fun seedDemoDetectionRules() {
    // Detection rules live in Postgres, so this does blocking Exposed transaction work; run it on the
    // IO dispatcher so it never blocks the calling event-loop thread. Still non-fatal.
    suspendRunCatching {
        withContext(Dispatchers.IO) {
            if (demoStarterRulesPresent()) {
                logger.info { "Demo detection rules already present, skipping detection seed" }
                return@withContext
            }
            val service = DetectionRuleService()
            var created = 0
            for (request in DetectionStarterPack.seedRequests()) {
                suspendRunCatching { service.create(DEMO_ORG_ID, request.copy(enabled = false)) }
                    .onSuccess { created++ }
                    .onFailure {
                        logger.warn { "Demo detection rule '${request.name}' failed (non-fatal): ${it.message}" }
                    }
            }
            logger.info { "Seeded $created demo detection rules (disabled)" }
        }
    }.onFailure {
        logger.warn { "Demo detection rule seed failed (non-fatal): ${it.message}" }
    }
}

/** True if the demo org already owns at least one detection rule (the starter pack was seeded before). */
private fun demoStarterRulesPresent(): Boolean = transaction {
    DetectionRules
        .selectAll()
        .where { DetectionRules.organizationId eq DEMO_ORG_ID }
        .limit(1)
        .any()
}
