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

package com.moneat

import com.moneat.config.EnvConfig
import com.moneat.config.EnvironmentValidator
import com.moneat.config.configureClickHouse
import com.moneat.config.configureRedis
import com.moneat.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    // Load .env file into system properties before starting the server
    EnvConfig.initialize()
    
    // Validate critical environment variables and fail fast if missing
    EnvironmentValidator().validateAndFailFast()
    
    // Initialize Sentry for error monitoring (points to Moneat via SENTRY_DSN)
    com.moneat.config.SentryConfig.initialize()
    
    EngineMain.main(args)
}

fun Application.module() {
    try {
        configureSecurity()
        configureHTTP()
        configureSerialization()
        configureMonitoring()
        configureRateLimiting()
        configureDemoModeRestrictions()
        configureRedis()
        configureClickHouse()
        configureDatabases()
        configureBackgroundJobs()
        configureRouting()
    } catch (e: Exception) {
        log.error("Failed to start application", e)
        throw e
    }
}
