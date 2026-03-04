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

package com.moneat.synthetics.models

import com.moneat.shared.models.Organizations
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import java.util.UUID

// Exposed Table Definition
object SyntheticTests : Table("synthetic_tests") {
    val id = javaUUID("id")
    val organizationId = integer("organization_id").references(Organizations.id)
    val name = varchar("name", 255)
    val testType = varchar("test_type", 20).default("api")
    val active = bool("active").default(true)
    val intervalSeconds = integer("interval_seconds").default(300)
    val timeoutSeconds = integer("timeout_seconds").default(30)
    val url = text("url").nullable()
    val method = varchar("method", 10).default("GET")
    val headers = text("headers").nullable()
    val body = text("body").nullable()
    val authMethod = varchar("auth_method", 20).nullable()
    val authUser = varchar("auth_user", 255).nullable()
    val authPass = varchar("auth_pass", 255).nullable()
    val assertions = text("assertions").default("[]")
    val steps = text("steps").nullable()
    val status = varchar("status", 20).default("pending")
    val lastRunAt = timestamp("last_run_at").nullable()
    val lastStatus = varchar("last_status", 20).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class SyntheticAssertion(
    val type: String,
    val target: String = "",
    val operator: String = "equals",
    val value: String = ""
)

@Serializable
data class VariableExtraction(
    val name: String,
    val source: String,
    val path: String = ""
)

@Serializable
data class SyntheticStep(
    val name: String = "",
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val assertions: List<SyntheticAssertion> = emptyList(),
    val extractVariables: List<VariableExtraction> = emptyList()
)

@Serializable
data class CreateSyntheticTestRequest(
    val name: String,
    val testType: String = "api",
    val intervalSeconds: Int = 300,
    val timeoutSeconds: Int = 30,
    val url: String? = null,
    val method: String = "GET",
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val assertions: List<SyntheticAssertion> = emptyList(),
    val steps: List<SyntheticStep> = emptyList()
)

@Serializable
data class UpdateSyntheticTestRequest(
    val name: String? = null,
    val active: Boolean? = null,
    val intervalSeconds: Int? = null,
    val timeoutSeconds: Int? = null,
    val url: String? = null,
    val method: String? = null,
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val assertions: List<SyntheticAssertion>? = null,
    val steps: List<SyntheticStep>? = null
)

@Serializable
data class SyntheticTestResponse(
    val id: String,
    val organizationId: Int,
    val name: String,
    val testType: String,
    val active: Boolean,
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val url: String? = null,
    val method: String,
    val headers: Map<String, String>? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val assertions: List<SyntheticAssertion>,
    val steps: List<SyntheticStep>,
    val status: String,
    val lastRunAt: Long? = null,
    val lastStatus: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class SyntheticTestData(
    val id: UUID,
    val organizationId: Int,
    val name: String,
    val testType: String,
    val active: Boolean,
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val url: String? = null,
    val method: String,
    val headers: String? = null,
    val body: String? = null,
    val authMethod: String? = null,
    val authUser: String? = null,
    val authPass: String? = null,
    val assertions: String,
    val steps: String? = null,
    val status: String,
    val lastRunAt: kotlin.time.Instant? = null,
    val lastStatus: String? = null,
    val createdAt: kotlin.time.Instant,
    val updatedAt: kotlin.time.Instant
)
