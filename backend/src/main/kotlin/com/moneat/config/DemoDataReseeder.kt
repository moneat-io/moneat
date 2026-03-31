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

import com.moneat.utils.suspendRunCatching
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Periodically re-inserts demo data so that ClickHouse TTL (90-day default)
 * does not silently delete the one-time seed migrations (V6/V7/V8/V10/V12).
 *
 * Strategy: delete demo-project rows older than 30 days, then re-insert fresh
 * rows relative to now(). Runs once at startup when DEMO_ENABLED=true.
 */
object DemoDataReseeder {

    suspend fun reseedIfNeeded() {
        if (!EnvConfig.Demo.enabled) return

        suspendRunCatching {
            val freshCoreCount = checkFreshDataCount()
            val freshLlmCount = checkFreshLlmDataCount()
            val freshAnalyticsCount = checkFreshAnalyticsDataCount()
            val freshLogsCount = checkFreshLogsCount()
            val freshDatadogCount = checkFreshDatadogCount()
            val freshInfraCount = checkFreshInfraDataCount()
            val freshSecurityCount = checkFreshSecurityDataCount()
            val freshSyntheticsCount = checkFreshSyntheticsDataCount()
            val demoDashboardCount = countDemoDashboards()

            val hasFreshCore = freshCoreCount > 0
            val hasFreshLlm = freshLlmCount > 0
            val hasFreshAnalytics = freshAnalyticsCount > 0
            val hasFreshLogs = freshLogsCount > 0
            val hasFreshDatadog = freshDatadogCount > 0
            val hasFreshInfra = freshInfraCount > 0
            val hasFreshSecurity = freshSecurityCount > 0
            val hasFreshSynthetics = freshSyntheticsCount > 0
            val hasEnoughDashboards = demoDashboardCount >= 4

            if (hasFreshCore && hasFreshLlm && hasFreshAnalytics && hasFreshLogs &&
                hasFreshDatadog && hasFreshInfra && hasFreshSecurity && hasFreshSynthetics && hasEnoughDashboards
            ) {
                logger.info {
                    "Demo data looks fresh ($freshCoreCount recent core events, " +
                        "$freshLlmCount recent LLM generations, " +
                        "$freshAnalyticsCount recent analytics events, $freshLogsCount recent logs, " +
                        "$freshDatadogCount recent Datadog spans, $freshInfraCount recent infra rows, " +
                        "$freshSecurityCount recent security events, $freshSyntheticsCount recent synthetics, " +
                        "$demoDashboardCount demo dashboards), skipping reseed"
                }
                reseedUptimeHeartbeats()
                ensureDemoProfileFiles()
                return
            }

            if (freshCoreCount > 0) {
                logger.info { "Core demo data is fresh ($freshCoreCount recent events), skipping core reseed" }
            } else {
                logger.info { "Core demo data is stale or missing, reseeding..." }
                purgeOldDemoData()
                reseedEvents()
                reseedSessions()
                reseedReplays()
            }

            if (freshLlmCount > 0) {
                logger.info { "LLM demo data is fresh ($freshLlmCount recent generations), skipping LLM reseed" }
            } else {
                logger.info { "LLM demo data is stale or missing, reseeding..." }
                purgeLlmDemoData()
                reseedLlmGenerations()
            }

            if (freshAnalyticsCount > 0) {
                logger.info {
                    "Analytics demo data is fresh ($freshAnalyticsCount recent events), skipping analytics reseed"
                }
            } else {
                logger.info { "Analytics demo data is stale or missing, reseeding..." }
                purgeAnalyticsDemoData()
                reseedAnalyticsEvents()
            }

            if (freshLogsCount > 0) {
                logger.info { "Log demo data is fresh ($freshLogsCount recent logs), skipping logs reseed" }
            } else {
                logger.info { "Log demo data is stale or missing, reseeding..." }
                purgeLogsDemoData()
                reseedLogs()
            }

            if (freshDatadogCount > 0) {
                logger.info { "Datadog demo data is fresh ($freshDatadogCount recent spans), skipping Datadog reseed" }
            } else {
                logger.info { "Datadog demo data is stale or missing, reseeding..." }
                purgeDatadogDemoData()
                reseedDatadogData()
            }

            if (freshInfraCount > 0) {
                logger.info { "Infra demo data is fresh ($freshInfraCount recent rows), skipping infra reseed" }
            } else {
                logger.info { "Infra demo data is stale or missing, reseeding for demo org..." }
                purgeInfraDemoData()
                reseedKubernetesData(ORG1)
                reseedDbmData(ORG1)
                reseedDebuggerData(ORG1)
                reseedNdmData(ORG1)
                reseedSbomData(ORG1)
            }

            if (demoDashboardCount >= 4) {
                logger.info { "Demo dashboards are present ($demoDashboardCount), skipping dashboard reseed" }
            } else {
                logger.info { "Demo dashboards missing or incomplete ($demoDashboardCount found), reseeding..." }
                seedDemoDashboards()
            }

            if (hasFreshSecurity) {
                logger.info {
                    "Security demo data is fresh ($freshSecurityCount recent events), skipping security reseed"
                }
            } else {
                logger.info { "Security demo data is stale or missing, reseeding..." }
                purgeSecurityDemoData()
                reseedSecurityData()
            }

            if (hasFreshSynthetics) {
                logger.info {
                    "Synthetics demo data is fresh ($freshSyntheticsCount recent results), skipping synthetics reseed"
                }
            } else {
                logger.info { "Synthetics demo data is stale or missing, reseeding..." }
                purgeSyntheticsDemoData()
                reseedSyntheticsData()
            }

            logger.info { "Demo data reseed complete" }
            reseedUptimeHeartbeats()
            ensureDemoProfileFiles()
        }.getOrElse { e ->
            logger.error(e) { "Demo data reseed failed (non-fatal): ${e.message}" }
        }
    }
}
