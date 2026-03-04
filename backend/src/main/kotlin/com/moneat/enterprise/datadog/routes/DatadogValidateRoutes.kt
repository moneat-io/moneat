// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog.routes

import com.moneat.enterprise.datadog.auth.DatadogAuthMiddleware
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.datadogValidateRoutes() {
    route("/dd") {
        route("/api/v1") {
            get("/validate") {
                val orgId = DatadogAuthMiddleware.authenticate(call) ?: return@get
                call.respond(
                    HttpStatusCode.OK,
                    mapOf("valid" to "true", "org_id" to orgId.toString())
                )
            }
        }
    }
}
