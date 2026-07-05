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

import com.moneat.authz.PermissionBridge
import com.moneat.config.EnvConfig
import com.moneat.enterprise.license.LicenseInfo
import com.moneat.enterprise.license.LicenseValidator
import com.moneat.workflows.WorkflowApprovalBridge
import com.moneat.workflows.WorkflowConnectionVault
import com.moneat.workflows.WorkflowPremiumConnectorBridge
import com.moneat.utils.suspendRunCatching
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import mu.KotlinLogging
import org.koin.core.module.Module
import java.util.ServiceLoader

private val logger = KotlinLogging.logger {}

/**
 * Registry that discovers and manages enterprise modules at runtime.
 * Uses Java's ServiceLoader to find EnterpriseModule implementations
 * on the classpath.
 */
object FeatureRegistry {
    private val modules = mutableListOf<EnterpriseModule>()
    private var initialized = false

    private var license: LicenseInfo? = null

    val isEnterpriseAvailable: Boolean
        get() = modules.isNotEmpty()

    val registeredModules: List<EnterpriseModule>
        get() = modules.toList()

    /** The validated license, or null if no valid license key is set. */
    val activeLicense: LicenseInfo? get() = license

    fun initialize() {
        if (initialized) return
        initialized = true

        val licenseKey = EnvConfig.get("MONEAT_LICENSE_KEY")
        val resolvedLicense = licenseKey?.takeIf { it.isNotBlank() }?.let { key ->
            LicenseValidator.validate(key).also { info ->
                if (info != null) {
                    logger.info {
                        "License valid — customer: ${info.customer}, plan: ${info.plan}, " +
                            "features: ${info.features}, expires: ${info.expiresAt ?: "never"}"
                    }
                } else {
                    logger.warn { "MONEAT_LICENSE_KEY is set but invalid or expired — licensed modules will not load" }
                }
            }
        }
        license = resolvedLicense

        val loader = ServiceLoader.load(EnterpriseModule::class.java)
        for (module in loader) {
            val feature = module.licenseFeature
            if (feature != null && (resolvedLicense == null || feature !in resolvedLicense.features)) {
                logger.info { "Skipping module: ${module.name} (requires '$feature' license feature)" }
                continue
            }
            modules.add(module)
            logger.info { "Registered module: ${module.name}" }
        }

        val licensedCount = modules.count { it.licenseFeature != null }
        val ossCount = modules.size - licensedCount
        if (modules.isEmpty()) {
            logger.info { "No modules found — running in core-only mode" }
        } else {
            logger.info { "Loaded ${modules.size} module(s) ($ossCount open-source, $licensedCount licensed)" }
        }
    }

    fun registerRoutes(route: Route) {
        for (module in modules) {
            logger.info { "Registering routes for enterprise module: ${module.name}" }
            suspendRunCatching {
                module.registerRoutes(route)
                logger.info { "Routes registered for enterprise module: ${module.name}" }
            }.getOrElse { e ->
                logger.error(e) { "Failed to register routes for enterprise module: ${module.name}" }
                throw e
            }
        }
    }

    fun registerIngestionRoutes(route: Route) {
        for (module in modules) {
            suspendRunCatching {
                module.registerIngestionRoutes(route)
            }.getOrElse { e ->
                logger.error(e) { "Failed to register ingestion routes for enterprise module: ${module.name}" }
                throw e
            }
        }
    }

    fun koinModules(): List<Module> =
        modules.flatMap { module ->
            suspendRunCatching {
                module.koinModules()
            }.getOrElse { e ->
                logger.error(e) { "Failed to collect Koin modules for enterprise module: ${module.name}" }
                throw e
            }
        }

    fun shouldStartIsolatedBackgroundJobs(application: Application): Boolean =
        modules.any { module ->
            suspendRunCatching {
                module.shouldStartIsolatedBackgroundJobs(application)
            }.getOrElse { e ->
                logger.error(e) { "Failed to resolve isolated startup state for enterprise module: ${module.name}" }
                throw e
            }
        }

    fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean = true,
        startIngestionWorkers: Boolean = true,
    ) {
        for (module in modules) {
            module.startBackgroundJobs(application, startSchedulers, startIngestionWorkers)
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

    /** Get the workflow connection vault if the licensed workflows module is loaded. */
    fun getWorkflowConnectionVault(): WorkflowConnectionVault? {
        return modules.filterIsInstance<WorkflowConnectionVault>().firstOrNull()
    }

    /** Get the workflow approval bridge if the licensed workflows module is loaded. */
    fun getWorkflowApprovalBridge(): WorkflowApprovalBridge? {
        return modules.filterIsInstance<WorkflowApprovalBridge>().firstOrNull()
    }

    /** Get the workflow premium connector bridge if the licensed workflows module is loaded. */
    fun getWorkflowPremiumConnectorBridge(): WorkflowPremiumConnectorBridge? {
        return modules.filterIsInstance<WorkflowPremiumConnectorBridge>().firstOrNull()
    }

    /** Get the cross-cutting permission bridge if the licensed RBAC module is loaded. */
    fun getPermissionBridge(): PermissionBridge? {
        return modules.filterIsInstance<PermissionBridge>().firstOrNull()
    }

    /** Get the detection evidence bridge if the security feature module is loaded. */
    fun getDetectionSignalEvidenceBridge(): DetectionSignalEvidenceBridge? {
        return modules.filterIsInstance<DetectionSignalEvidenceBridge>().firstOrNull()
    }

    fun resolveIngestionRateLimitKey(rateLimitName: String, call: ApplicationCall): String? =
        modules
            .filterIsInstance<IngestionRateLimitKeyResolver>()
            .firstOrNull { it.rateLimitName == rateLimitName }
            ?.resolveRateLimitKey(call)

    suspend fun seedDemoData() {
        for (module in modules) {
            val seeder = module as? DemoDataFeatureSeeder ?: continue
            suspendRunCatching {
                seeder.seedDemoData()
            }.getOrElse { e ->
                logger.warn(e) { "Feature demo data seed failed for module: ${module.name}" }
            }
        }
    }

    // For testing
    fun resetForTest() {
        reset()
    }

    internal fun registerForTest(vararg enterpriseModules: EnterpriseModule) {
        modules.addAll(enterpriseModules)
        initialized = true
    }

    internal fun reset() {
        modules.clear()
        license = null
        initialized = false
    }
}
