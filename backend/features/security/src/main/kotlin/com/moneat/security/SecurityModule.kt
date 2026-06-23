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

package com.moneat.security

import com.moneat.enterprise.DemoDataFeatureSeeder
import com.moneat.enterprise.DetectionSignalEvidenceBridge
import com.moneat.enterprise.EnterpriseModule
import com.moneat.security.detection.DetectionScheduler
import com.moneat.security.detection.RuleQueryCompiler
import com.moneat.security.detection.detectionRuleRoutes
import com.moneat.security.detection.seedDemoDetectionRules
import com.moneat.security.signals.signalRoutes
import com.moneat.security.vulnerabilities.VulnerabilityAdvisorySyncJob
import com.moneat.security.vulnerabilities.vulnerabilityRoutes
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class SecurityModule : EnterpriseModule, DemoDataFeatureSeeder, DetectionSignalEvidenceBridge {
    override val name: String = "Security"

    private var detectionScheduler: DetectionScheduler? = null
    private var vulnerabilityAdvisorySyncJob: VulnerabilityAdvisorySyncJob? = null
    private var schedulerScope: CoroutineScope? = null

    override fun registerRoutes(route: Route) {
        route.rateLimit(RateLimitName("api")) {
            signalRoutes()
            detectionRuleRoutes()
            vulnerabilityRoutes()
        }
    }

    override suspend fun seedDemoData() {
        seedDemoDetectionRules()
    }

    override fun entityPredicate(column: String, value: String, escape: (String) -> String): String? =
        RuleQueryCompiler.entityPredicate(column, value, escape)

    override fun startBackgroundJobs(application: Application) {
        startBackgroundJobs(application, startSchedulers = true, startIngestionWorkers = true)
    }

    override fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean,
        startIngestionWorkers: Boolean,
    ) {
        if (!startSchedulers || schedulerScope != null) {
            return
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = DetectionScheduler()
        val advisorySyncJob = VulnerabilityAdvisorySyncJob()
        schedulerScope = scope
        detectionScheduler = scheduler
        vulnerabilityAdvisorySyncJob = advisorySyncJob
        try {
            scheduler.start(scope)
            advisorySyncJob.start(scope)
        } catch (e: Exception) {
            scheduler.stop()
            advisorySyncJob.stop()
            scope.cancel()
            schedulerScope = null
            detectionScheduler = null
            vulnerabilityAdvisorySyncJob = null
            throw e
        }
    }

    override fun stopBackgroundJobs() {
        detectionScheduler?.stop()
        detectionScheduler = null
        vulnerabilityAdvisorySyncJob?.stop()
        vulnerabilityAdvisorySyncJob = null
        schedulerScope?.cancel()
        schedulerScope = null
    }
}
