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

package com.moneat.enterprise

import io.ktor.server.application.*
import io.ktor.server.routing.*
import mu.KotlinLogging
import java.util.ServiceLoader

private val logger = KotlinLogging.logger {}

/**
 * Service Provider Interface for enterprise feature modules.
 * Enterprise modules implement this interface and register via
 * META-INF/services/com.moneat.enterprise.EnterpriseModule.
 */
interface EnterpriseModule {
    /** Human-readable name of the module (e.g., "SSO", "On-Call"). */
    val name: String

    /** Register enterprise routes into the core routing tree. */
    fun registerRoutes(route: Route)

    /** Start enterprise background jobs during application startup. */
    fun startBackgroundJobs(application: Application)

    /** Stop enterprise background jobs during application shutdown. */
    fun stopBackgroundJobs()
}

/**
 * Registry that discovers and manages enterprise modules at runtime.
 * Uses Java's ServiceLoader to find EnterpriseModule implementations
 * on the classpath.
 */
object FeatureRegistry {
    private val modules = mutableListOf<EnterpriseModule>()
    private var initialized = false

    val isEnterpriseAvailable: Boolean
        get() = modules.isNotEmpty()

    val registeredModules: List<EnterpriseModule>
        get() = modules.toList()

    fun initialize() {
        if (initialized) return
        initialized = true

        val loader = ServiceLoader.load(EnterpriseModule::class.java)
        for (module in loader) {
            modules.add(module)
            logger.info { "Registered enterprise module: ${module.name}" }
        }

        if (modules.isEmpty()) {
            logger.info { "No enterprise modules found — running in core-only mode" }
        } else {
            logger.info { "Loaded ${modules.size} enterprise module(s)" }
        }
    }

    fun registerRoutes(route: Route) {
        for (module in modules) {
            logger.info { "Registering routes for enterprise module: ${module.name}" }
            try {
                module.registerRoutes(route)
                logger.info { "Routes registered for enterprise module: ${module.name}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to register routes for enterprise module: ${module.name}" }
                throw e
            }
        }
    }

    fun startBackgroundJobs(application: Application) {
        for (module in modules) {
            module.startBackgroundJobs(application)
        }
    }

    fun stopBackgroundJobs() {
        for (module in modules) {
            module.stopBackgroundJobs()
        }
    }

    /** Check if a specific enterprise module is loaded by name. */
    fun hasModule(name: String): Boolean = modules.any { it.name == name }

    /** Get the OnCallBridge if the on-call enterprise module is loaded. */
    fun getOnCallBridge(): OnCallBridge? {
        return modules.filterIsInstance<OnCallBridge>().firstOrNull()
    }

    // For testing
    internal fun reset() {
        modules.clear()
        initialized = false
    }
}
