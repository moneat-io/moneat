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

package com.moneat.billing

import com.moneat.billing.repositories.SubscriptionRepository
import com.moneat.billing.repositories.SubscriptionRepositoryImpl
import com.moneat.billing.routes.billingRoutes
import com.moneat.billing.routes.publicBillingRoutes
import com.moneat.billing.routes.stripeWebhookRoutes
import com.moneat.billing.services.AdminBillingService
import com.moneat.billing.services.BillingBackgroundService
import com.moneat.billing.services.BillingQuotaService
import com.moneat.billing.services.EntitlementService
import com.moneat.billing.services.PricingTierService
import com.moneat.billing.services.StripeService
import com.moneat.enterprise.EnterpriseModule
import com.moneat.shared.repositories.OrganizationRepository
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicReference

class BillingModule : EnterpriseModule {
    override val name: String = "Billing"
    private val runningBackgroundJobs = AtomicReference<RunningBillingJobs?>(null)
    private val lifecycleLock = Any()

    override fun registerRoutes(route: Route) {
        route.stripeWebhookRoutes()
        route.route("/v1") {
            publicBillingRoutes()
        }
        route.authenticate("auth-jwt") {
            rateLimit(RateLimitName("api")) {
                route("/v1") {
                    billingRoutes()
                }
            }
        }
    }

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single<SubscriptionRepository> { SubscriptionRepositoryImpl() }
                single { PricingTierService() }
                single { BillingQuotaService(get()) }
                single { EntitlementService(get()) }
                single {
                    StripeService(
                        subscriptionRepository = get(),
                        organizationRepository = get<OrganizationRepository>(),
                        pricingTierService = get(),
                    )
                }
                single {
                    BillingBackgroundService(
                        stripeService = get(),
                        quotaService = get(),
                        emailService = get(),
                        pricingTierService = get(),
                    )
                }
                single { AdminBillingService(get()) }
            }
        )

    override fun startBackgroundJobs(application: Application) {
        startBackgroundJobs(application, startSchedulers = true, startIngestionWorkers = true)
    }

    override fun startBackgroundJobs(
        application: Application,
        startSchedulers: Boolean,
        startIngestionWorkers: Boolean,
    ) {
        if (!startSchedulers) return

        synchronized(lifecycleLock) {
            if (runningBackgroundJobs.get() != null) return

            val runningJobs = RunningBillingJobs(
                service = GlobalContext.get().get(),
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            )
            try {
                runningJobs.service.start(runningJobs.scope)
                runningBackgroundJobs.set(runningJobs)
            } catch (e: Throwable) {
                runningJobs.stop()
                throw e
            }
        }
    }

    override fun stopBackgroundJobs() {
        synchronized(lifecycleLock) {
            runningBackgroundJobs.getAndSet(null)?.stop()
        }
    }

    private data class RunningBillingJobs(
        val service: BillingBackgroundService,
        val scope: CoroutineScope,
    ) {
        fun stop() {
            service.stop()
            scope.cancel()
        }
    }
}
