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

package com.moneat.datadog.security

import com.moneat.billing.services.BillingQuotaService
import com.moneat.datadog.auth.DatadogAuthMiddleware
import com.moneat.datadog.models.DdSbomPayload
import com.moneat.datadog.reserveDatadogQuota
import com.moneat.ingest.DecompressionService
import com.moneat.security.vulnerabilities.ParsedSbom
import com.moneat.security.vulnerabilities.SbomIngestResponse
import com.moneat.security.vulnerabilities.SbomParser
import com.moneat.security.vulnerabilities.SbomSource
import com.moneat.security.vulnerabilities.SbomUploadContext
import com.moneat.security.vulnerabilities.SbomValidationException
import com.moneat.security.vulnerabilities.SBOM_NO_USABLE_PACKAGES_MESSAGE
import com.moneat.security.vulnerabilities.VulnerabilityService
import com.moneat.utils.ErrorResponse
import com.moneat.utils.suspendRunCatching
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext

private const val INVALID_SBOM_MESSAGE = "Invalid SBOM payload"
private const val UNSUPPORTED_PROTOBUF_SBOM_MESSAGE = "Protobuf SBOM uploads are not supported"

private val routeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

fun Route.datadogSbomRoutes(
    service: VulnerabilityService = VulnerabilityService(),
    quotaService: BillingQuotaService = GlobalContext.get().get(),
) {
    route("/dd/api/v2") {
        post("/sbom") { handleDatadogAgentSbomUpload(service, quotaService) }
    }
    route("/api/v2") {
        post("/sbom") { handleDatadogAgentSbomUpload(service, quotaService) }
    }
}

private suspend fun RoutingContext.handleDatadogAgentSbomUpload(
    service: VulnerabilityService,
    quotaService: BillingQuotaService,
) {
    val orgId = DatadogAuthMiddleware.authenticate(call) ?: return
    val contentType = call.request.headers[HttpHeaders.ContentType].orEmpty()
    if (contentType.contains("protobuf", ignoreCase = true)) {
        call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse(UNSUPPORTED_PROTOBUF_SBOM_MESSAGE))
        return
    }
    val rawBody = call.receive<ByteArray>()
    val body = DecompressionService.decompress(rawBody, call.request.headers[HttpHeaders.ContentEncoding])
    if (!reserveAgentQuota(quotaService, orgId, body)) return
    suspendRunCatching { ingestAgentBody(service, orgId, body) }.fold(
        onSuccess = { call.respond(HttpStatusCode.Accepted, it) },
        onFailure = { respondIngestError(it) },
    )
}

private suspend fun ingestAgentBody(
    service: VulnerabilityService,
    orgId: Int,
    body: ByteArray,
): SbomIngestResponse {
    val direct = parseAgentDirectSbom(body)
    if (direct != null) {
        return service.ingestParsed(orgId, direct, SbomUploadContext(source = SbomSource.AGENT))
    }
    val payload = routeJson.decodeFromString<DdSbomPayload>(body.decodeToString())
    val parsed = parseAgentEnvelope(payload) ?: return emptyAgentSbomResponse()
    val context = SbomUploadContext(
        source = SbomSource.AGENT,
        targetType = if (payload.imageName.isNotBlank()) "image" else "host",
        targetName = payload.imageName.ifBlank { payload.host },
        host = payload.host,
        imageName = payload.imageName,
        containerId = payload.containerId,
        tags = parseAgentTags(payload.tags),
    )
    return service.ingestParsed(orgId, parsed, context)
}

private fun parseAgentEnvelope(payload: DdSbomPayload): ParsedSbom? =
    try {
        DatadogSbomParser.parseAgentPayload(payload)
    } catch (e: SbomValidationException) {
        if (e.message == SBOM_NO_USABLE_PACKAGES_MESSAGE) {
            null
        } else {
            throw e
        }
    }

private fun emptyAgentSbomResponse(): SbomIngestResponse =
    SbomIngestResponse(uploadId = "agent-empty-inventory", packageCount = 0, findingCount = 0)

private fun parseAgentDirectSbom(body: ByteArray): ParsedSbom? =
    try {
        SbomParser.parse(body)
    } catch (_: SbomValidationException) {
        null
    }

private suspend fun RoutingContext.reserveAgentQuota(
    quotaService: BillingQuotaService,
    organizationId: Int,
    body: ByteArray,
): Boolean =
    reserveDatadogQuota(
        call = call,
        quotaService = quotaService,
        organizationId = organizationId,
        requestedUnits = 1,
        eventType = "security_sbom",
        requestedBytes = body.size.toLong(),
    )

private suspend fun RoutingContext.respondIngestError(error: Throwable) {
    if (error is IllegalArgumentException) {
        val message = if (error is SbomValidationException) {
            error.message?.takeIf { it.isNotBlank() } ?: INVALID_SBOM_MESSAGE
        } else {
            INVALID_SBOM_MESSAGE
        }
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
    } else {
        throw error
    }
}

private fun parseAgentTags(tags: List<String>): Map<String, String> =
    tags.associate { tag ->
        val index = tag.indexOf(':')
        if (index > 0) {
            tag.substring(0, index) to tag.substring(index + 1)
        } else {
            tag to ""
        }
    }
