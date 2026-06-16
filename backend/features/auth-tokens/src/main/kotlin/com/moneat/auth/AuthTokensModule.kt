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

package com.moneat.auth

import com.moneat.auth.routes.authTokenRoutes
import com.moneat.auth.services.AuthTokenService
import com.moneat.auth.services.RefreshTokenCleanupService
import com.moneat.auth.services.RefreshTokenService
import com.moneat.enterprise.EnterpriseModule
import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicReference

class AuthTokensModule : EnterpriseModule {
    override val name: String = "Auth Tokens"
    private val runningBackgroundJobs = AtomicReference<RunningAuthTokenJobs?>(null)
    private val lifecycleLock = Any()

    override fun registerRoutes(route: Route) {
        route.authTokenRoutes()
    }

    override fun koinModules(): List<Module> =
        listOf(
            module {
                single { AuthTokenService() }
                single { RefreshTokenService() }
                single { RefreshTokenCleanupService() }
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

            val runningJobs = RunningAuthTokenJobs(
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

    private data class RunningAuthTokenJobs(
        val service: RefreshTokenCleanupService,
        val scope: CoroutineScope,
    ) {
        fun stop() {
            service.stop()
            scope.cancel()
        }
    }
}
