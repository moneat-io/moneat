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

package com.moneat.contact

import com.moneat.contact.routes.contactRoutes
import com.moneat.contact.services.ContactService
import com.moneat.enterprise.EnterpriseModule
import com.moneat.notifications.services.EmailService
import io.ktor.server.application.Application
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.koin.core.context.GlobalContext

class ContactModule : EnterpriseModule {
    override val name: String = "Contact"

    private val contactService by lazy {
        ContactService(GlobalContext.get().get<EmailService>())
    }

    override fun registerRoutes(route: Route) {
        route.route("/v1") {
            rateLimit(RateLimitName("contact")) {
                contactRoutes(contactService)
            }
        }
    }

    override fun startBackgroundJobs(application: Application) = Unit

    override fun stopBackgroundJobs() = Unit
}
