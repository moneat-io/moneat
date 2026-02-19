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

import io.sentry.Sentry
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

object SentryConfig {
    fun initialize() {
        val dsn = EnvConfig.get("SENTRY_DSN")

        if (dsn.isNullOrBlank()) {
            logger.info { "Sentry DSN not configured, skipping Sentry initialization" }
            return
        }

        Sentry.init { options ->
            options.dsn = dsn
            options.environment = EnvConfig.get("SENTRY_ENVIRONMENT", "production")
            options.release = EnvConfig.get("RELEASE_VERSION") ?: "moneat@${System.getProperty("app.version", "dev")}"

            // Performance monitoring
            val tracesSampleRate = EnvConfig.get("SENTRY_TRACES_SAMPLE_RATE", "0.1").toDoubleOrNull() ?: 0.1
            options.tracesSampleRate = tracesSampleRate

            // Enable profiling (if supported)
            val profilesSampleRate = EnvConfig.get("SENTRY_PROFILES_SAMPLE_RATE", "0.1").toDoubleOrNull() ?: 0.1
            options.profilesSampleRate = profilesSampleRate

            // Set server name
            options.serverName = EnvConfig.get("HOSTNAME", "moneat-backend")

            // Enable performance monitoring for database queries
            options.enableTracing = true

            // Enable breadcrumbs
            options.maxBreadcrumbs = 100

            // Send default PII (Personal Identifiable Information)
            options.isSendDefaultPii = false

            // Enable attaching threads
            options.isAttachThreads = true

            // Enable attaching stack traces
            options.isAttachStacktrace = true

            // Add custom tags
            options.setTag("service", "moneat-backend")
            options.setTag("component", "ktor")

            logger.info {
                "Sentry initialized with DSN: ${dsn.take(
                    20
                )}..., traces: $tracesSampleRate, profiles: $profilesSampleRate"
            }
        }
    }

    fun isEnabled(): Boolean {
        return Sentry.isEnabled()
    }
}
