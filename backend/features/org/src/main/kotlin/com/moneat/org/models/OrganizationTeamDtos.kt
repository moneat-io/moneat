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

package com.moneat.org.models

import kotlinx.serialization.Serializable

@Serializable
data class OrganizationTeamMemberResponse(
    val userId: String,
    val email: String,
    val name: String? = null,
)

@Serializable
data class OrganizationTeamCurrentOnCallResponse(
    val userId: String,
    val userName: String,
)

@Serializable
data class OrganizationTeamResponse(
    val id: String,
    val name: String,
    val slug: String,
    val description: String? = null,
    val slack: String? = null,
    val repo: String? = null,
    val onCallScheduleId: String? = null,
    val escalationPolicyId: String? = null,
    val currentOnCall: OrganizationTeamCurrentOnCallResponse? = null,
    val members: List<OrganizationTeamMemberResponse> = emptyList(),
)

@Serializable
data class CreateOrganizationTeamRequest(
    val name: String,
    val description: String? = null,
    val slack: String? = null,
    val repo: String? = null,
    val onCallScheduleId: String? = null,
    val escalationPolicyId: String? = null,
    val memberIds: List<String> = emptyList(),
)

@Serializable
data class UpdateOrganizationTeamRequest(
    val name: String? = null,
    val description: String? = null,
    val slack: String? = null,
    val repo: String? = null,
    val onCallScheduleId: String? = null,
    val escalationPolicyId: String? = null,
    val memberIds: List<String>? = null,
)
