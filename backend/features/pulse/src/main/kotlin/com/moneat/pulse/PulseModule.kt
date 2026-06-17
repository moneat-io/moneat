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

package com.moneat.pulse

import com.moneat.enterprise.EnterpriseModule
import com.moneat.events.routes.telemetryIngestRoutes
import com.moneat.shared.services.PulseService
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}
private const val DEFAULT_PULSE_INTERVAL_HOURS = 4

class PulseModule : EnterpriseModule {
    private val pulseServiceFactory: (Duration) -> PulseService
    private var backgroundJobs: PulseBackgroundJobs? = null

    constructor() : this({ interval -> PulseService(interval = interval) })

    internal constructor(pulseServiceFactory: (Duration) -> PulseService) {
        this.pulseServiceFactory = pulseServiceFactory
    }

    override val name: String = "Pulse"

    override fun registerRoutes(route: Route) {
        route.rateLimit(RateLimitName("telemetry")) {
            telemetryIngestRoutes()
        }
    }

    override fun startBackgroundJobs(application: Application) {
        if (backgroundJobs != null || !PulseService.isEnabled()) return
        val telemetryIntervalHours =
            application.environment.config
                .propertyOrNull("pulse.intervalHours")
                ?.getString()
                ?.toIntOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_PULSE_INTERVAL_HOURS
        logger.info { "Telemetry pulse enabled for self-hosted deployment" }
        backgroundJobs = PulseBackgroundJobs(pulseServiceFactory(telemetryIntervalHours.hours))
            .also { it.start() }
    }

    override fun stopBackgroundJobs() {
        backgroundJobs?.stop()
        backgroundJobs = null
    }
}

private class PulseBackgroundJobs(
    private val pulseService: PulseService,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        pulseService.start(scope)
    }

    fun stop() {
        pulseService.stop()
        scope.cancel()
    }
}
