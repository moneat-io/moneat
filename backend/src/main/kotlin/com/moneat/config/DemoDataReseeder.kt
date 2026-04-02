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
            val snap = gatherReseedSnapshot()
            if (snap.fullyFresh()) {
                logSkippingReseed(snap)
                reseedUptimeHeartbeats()
                ensureDemoProfileFiles()
                return@suspendRunCatching
            }

            maybeReseedCore(snap.freshCoreCount)
            maybeReseedLlm(snap.freshLlmCount)
            maybeReseedAnalytics(snap.freshAnalyticsCount)
            maybeReseedLogs(snap.freshLogsCount)
            maybeReseedDatadog(snap.freshDatadogCount)
            maybeReseedKubernetes(snap.freshK8sCount)
            maybeReseedDbm(snap.freshDbmCount)
            maybeReseedDebugger(snap.hasFreshDebugger, snap)
            maybeReseedNdm(snap.hasFreshNdm, snap)
            maybeReseedSbom(snap.freshSbomCount)
            maybeReseedDashboards(snap.demoDashboardCount)
            maybeReseedSecurity(snap.freshSecurityCount)
            maybeReseedSynthetics(snap.freshSyntheticsCount)

            logger.info { "Demo data reseed complete" }
            reseedUptimeHeartbeats()
            ensureDemoProfileFiles()
        }.getOrElse { e ->
            logger.error(e) { "Demo data reseed failed (non-fatal): ${e.message}" }
        }
    }
}

private data class ReseedSnapshot(
    val freshCoreCount: Long,
    val freshLlmCount: Long,
    val freshAnalyticsCount: Long,
    val freshLogsCount: Long,
    val freshDatadogCount: Long,
    val freshK8sCount: Long,
    val freshDbmCount: Long,
    val freshDebuggerLogsCount: Long,
    val freshDebuggerDiagCount: Long,
    val freshNdmDevicesCount: Long,
    val freshNdmTrapsCount: Long,
    val freshNdmFlowsCount: Long,
    val freshNetworkPathsCount: Long,
    val freshSbomCount: Long,
    val freshSecurityCount: Long,
    val freshSyntheticsCount: Long,
    val demoDashboardCount: Long,
) {
    val hasFreshDebugger: Boolean
        get() = freshDebuggerLogsCount > 0 && freshDebuggerDiagCount > 0

    val hasFreshNdm: Boolean
        get() =
            freshNdmDevicesCount > 0 && freshNdmTrapsCount > 0 &&
                freshNdmFlowsCount > 0 && freshNetworkPathsCount > 0

    val hasFreshInfra: Boolean
        get() =
            freshK8sCount > 0 && freshDbmCount > 0 && hasFreshDebugger &&
                hasFreshNdm && freshSbomCount > 0

    fun fullyFresh(): Boolean =
        freshCoreCount > 0 &&
            freshLlmCount > 0 &&
            freshAnalyticsCount > 0 &&
            freshLogsCount > 0 &&
            freshDatadogCount > 0 &&
            hasFreshInfra &&
            freshSecurityCount > 0 &&
            freshSyntheticsCount > 0 &&
            demoDashboardCount >= 4
}

private suspend fun gatherReseedSnapshot(): ReseedSnapshot =
    ReseedSnapshot(
        freshCoreCount = checkFreshDataCount(),
        freshLlmCount = checkFreshLlmDataCount(),
        freshAnalyticsCount = checkFreshAnalyticsDataCount(),
        freshLogsCount = checkFreshLogsCount(),
        freshDatadogCount = checkFreshDatadogCount(),
        freshK8sCount = checkFreshKubernetesDataCount(),
        freshDbmCount = checkFreshDbmDataCount(),
        freshDebuggerLogsCount = checkFreshDebuggerLogsCount(),
        freshDebuggerDiagCount = checkFreshDebuggerDiagnosticsCount(),
        freshNdmDevicesCount = checkFreshNdmDevicesCount(),
        freshNdmTrapsCount = checkFreshNdmTrapsCount(),
        freshNdmFlowsCount = checkFreshNdmFlowsCount(),
        freshNetworkPathsCount = checkFreshNetworkPathsCount(),
        freshSbomCount = checkFreshSbomDataCount(),
        freshSecurityCount = checkFreshSecurityDataCount(),
        freshSyntheticsCount = checkFreshSyntheticsDataCount(),
        demoDashboardCount = countDemoDashboards(),
    )

private fun logSkippingReseed(s: ReseedSnapshot) {
    logger.info {
        "Demo data looks fresh (${s.freshCoreCount} recent core events, " +
            "${s.freshLlmCount} recent LLM generations, " +
            "${s.freshAnalyticsCount} recent analytics events, ${s.freshLogsCount} recent logs, " +
            "${s.freshDatadogCount} recent Datadog spans, " +
            "infra (k8s=${s.freshK8sCount} dbm=${s.freshDbmCount} " +
            "debugger_logs=${s.freshDebuggerLogsCount} " +
            "debugger_diag=${s.freshDebuggerDiagCount} ndm_dev=${s.freshNdmDevicesCount} " +
            "ndm_traps=${s.freshNdmTrapsCount} ndm_flows=${s.freshNdmFlowsCount} " +
            "net_paths=${s.freshNetworkPathsCount} sbom=${s.freshSbomCount}), " +
            "${s.freshSecurityCount} recent security events, ${s.freshSyntheticsCount} recent synthetics, " +
            "${s.demoDashboardCount} demo dashboards), skipping reseed"
    }
}

private suspend fun maybeReseedCore(freshCoreCount: Long) {
    if (freshCoreCount > 0) {
        logger.info { "Core demo data is fresh ($freshCoreCount recent events), skipping core reseed" }
        return
    }
    logger.info { "Core demo data is stale or missing, reseeding..." }
    purgeOldDemoData()
    reseedEvents()
    reseedSessions()
    reseedReplays()
}

private suspend fun maybeReseedLlm(freshLlmCount: Long) {
    if (freshLlmCount > 0) {
        logger.info { "LLM demo data is fresh ($freshLlmCount recent generations), skipping LLM reseed" }
        return
    }
    logger.info { "LLM demo data is stale or missing, reseeding..." }
    purgeLlmDemoData()
    reseedLlmGenerations()
}

private suspend fun maybeReseedAnalytics(freshAnalyticsCount: Long) {
    if (freshAnalyticsCount > 0) {
        logger.info {
            "Analytics demo data is fresh ($freshAnalyticsCount recent events), skipping analytics reseed"
        }
        return
    }
    logger.info { "Analytics demo data is stale or missing, reseeding..." }
    purgeAnalyticsDemoData()
    reseedAnalyticsEvents()
}

private suspend fun maybeReseedLogs(freshLogsCount: Long) {
    if (freshLogsCount > 0) {
        logger.info { "Log demo data is fresh ($freshLogsCount recent logs), skipping logs reseed" }
        return
    }
    logger.info { "Log demo data is stale or missing, reseeding..." }
    purgeLogsDemoData()
    reseedLogs()
}

private suspend fun maybeReseedDatadog(freshDatadogCount: Long) {
    if (freshDatadogCount > 0) {
        logger.info { "Datadog demo data is fresh ($freshDatadogCount recent spans), skipping Datadog reseed" }
        return
    }
    logger.info { "Datadog demo data is stale or missing, reseeding..." }
    purgeDatadogDemoData()
    reseedDatadogData()
}

private suspend fun maybeReseedKubernetes(freshK8sCount: Long) {
    if (freshK8sCount > 0) {
        logger.info {
            "Kubernetes demo data is fresh ($freshK8sCount recent rows), skipping Kubernetes reseed"
        }
        return
    }
    logger.info { "Kubernetes demo data is stale or missing, reseeding..." }
    purgeKubernetesDemoData()
    reseedKubernetesData(ORG1)
}

private suspend fun maybeReseedDbm(freshDbmCount: Long) {
    if (freshDbmCount > 0) {
        logger.info { "DBM demo data is fresh ($freshDbmCount recent rows), skipping DBM reseed" }
        return
    }
    logger.info { "DBM demo data is stale or missing, reseeding..." }
    purgeDbmDemoData()
    reseedDbmData(ORG1)
}

private suspend fun maybeReseedDebugger(hasFreshDebugger: Boolean, s: ReseedSnapshot) {
    if (hasFreshDebugger) {
        logger.info {
            "Debugger demo data is fresh (${s.freshDebuggerLogsCount} logs, " +
                "${s.freshDebuggerDiagCount} diagnostics), skipping debugger reseed"
        }
        return
    }
    logger.info { "Debugger demo data is stale or missing, reseeding..." }
    purgeDebuggerDemoData()
    reseedDebuggerData(ORG1)
}

private suspend fun maybeReseedNdm(hasFreshNdm: Boolean, s: ReseedSnapshot) {
    if (hasFreshNdm) {
        logger.info {
            "NDM demo data is fresh (${s.freshNdmDevicesCount} devices, ${s.freshNdmTrapsCount} traps, " +
                "${s.freshNdmFlowsCount} flows, ${s.freshNetworkPathsCount} paths), skipping NDM reseed"
        }
        return
    }
    logger.info { "NDM demo data is stale or missing, reseeding..." }
    purgeNdmDemoData()
    reseedNdmData(ORG1)
}

private suspend fun maybeReseedSbom(freshSbomCount: Long) {
    if (freshSbomCount > 0) {
        logger.info { "SBOM demo data is fresh ($freshSbomCount recent rows), skipping SBOM reseed" }
        return
    }
    logger.info { "SBOM demo data is stale or missing, reseeding..." }
    purgeSbomDemoData()
    reseedSbomData(ORG1)
}

private suspend fun maybeReseedDashboards(demoDashboardCount: Long) {
    if (demoDashboardCount >= 4) {
        logger.info { "Demo dashboards are present ($demoDashboardCount), skipping dashboard reseed" }
        return
    }
    logger.info {
        "Demo dashboards missing or incomplete ($demoDashboardCount found), reseeding..."
    }
    seedDemoDashboards()
}

private suspend fun maybeReseedSecurity(freshSecurityCount: Long) {
    if (freshSecurityCount > 0) {
        logger.info {
            "Security demo data is fresh ($freshSecurityCount recent events), skipping security reseed"
        }
        return
    }
    logger.info { "Security demo data is stale or missing, reseeding..." }
    purgeSecurityDemoData()
    reseedSecurityData()
}

private suspend fun maybeReseedSynthetics(freshSyntheticsCount: Long) {
    if (freshSyntheticsCount > 0) {
        logger.info {
            "Synthetics demo data is fresh ($freshSyntheticsCount recent results), skipping synthetics reseed"
        }
        return
    }
    logger.info { "Synthetics demo data is stale or missing, reseeding..." }
    purgeSyntheticsDemoData()
    reseedSyntheticsData()
}
