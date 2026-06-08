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

package com.moneat.contact.routes

import com.moneat.contact.models.SalesInquiryRequest
import com.moneat.contact.services.ContactService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.MessageResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import org.koin.core.context.GlobalContext

private const val SUCCESS_MESSAGE = "Thanks — our sales team will be in touch shortly."
private const val INVALID_BODY_MESSAGE = "Invalid request"

/**
 * Public, unauthenticated Enterprise sales-contact endpoint backing the pricing page form.
 * Mounted under the public `/v1` route group and rate-limited by IP at the registration site.
 */
fun Route.contactRoutes(
    contactService: ContactService = GlobalContext.get().get()
) {
    route("/contact") {
        post("/sales") {
            val request =
                try {
                    call.receive<SalesInquiryRequest>()
                } catch (_: BadRequestException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_BODY_MESSAGE))
                    return@post
                } catch (_: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(INVALID_BODY_MESSAGE))
                    return@post
                }

            try {
                contactService.submitInquiry(request)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: INVALID_BODY_MESSAGE))
                return@post
            }

            call.respond(MessageResponse(SUCCESS_MESSAGE))
        }
    }
}
